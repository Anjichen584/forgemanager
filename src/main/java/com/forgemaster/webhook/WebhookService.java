package com.forgemaster.webhook;

import com.forgemaster.ForgingMasterPlugin;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.EnumMap;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Consumer;

/**
 * 出站 Webhook 播报服务（v2.23 拓展 C）。
 *
 * <h2>为什么是这个形状</h2>
 * <ul>
 *   <li><b>主线程零 IO</b>：{@link #dispatch} 只把一条已序列化好的 JSON 塞进有界队列就返回。
 *       队列满时<b>丢最新的一条</b>——播报是「锦上添花」，任何时候都不该让玩家的一次锻造
 *       卡在网络上（`offer()` 不阻塞，因此永远不会拖慢主线程）。</li>
 *   <li><b>独立守护线程</b>：不用 Bukkit 调度器跑网络（调度器是主线程时间轴，异步任务
 *       也只是复用同一线程池语义），一个 {@code Thread} + 阻塞 {@code take()} 最省心；
 *       守护线程保证关服时不会因为忘了停而挂住 JVM。</li>
 *   <li><b>每类事件 10 秒限流</b>：全服同时刻碎剑 50 把是常态，不限流等于对目标站点打
 *       POST 洪水（也容易把 Discord webhook 打到 429）。按 {@link WebhookEvent} 分桶，
 *       窗口之外的直接丢，不打日志。</li>
 *   <li><b>失败静默 + 熔断</b>：单个插件不该因为对方服务挂了而刷屏控制台。连续失败 5 次
 *       就静默 60 秒再试，期间的失败也不记日志。</li>
 *   <li><b>手写 JSON 转义</b>：项目零第三方依赖（只有 Paper API），所以引号 / 反斜杠 /
 *       控制字符的转义必须自己写对——这是整个类唯一「写错就出安全/兼容问题」的地方，
 *       单独抽成 {@link #escape(String)} 方便自检直接测。</li>
 * </ul>
 *
 * <h2>线程模型</h2>
 * {@code dispatch}（主线程）→ 入队 → 消费线程 {@code take()} → 限流判定 → HTTP POST。
 * 停止时置 {@code running=false} + {@code interrupt()} + {@code join(1000)}，
 * 消费线程在 {@code InterruptedException} 上退出。
 */
public final class WebhookService {

    /** 每类事件的最小发送间隔（毫秒）：10 秒 */
    private static final long RATE_LIMIT_MS = 10_000L;
    /** 连续失败多少次后熔断 */
    private static final int BREAKER_THRESHOLD = 5;
    /** 熔断静默时长（毫秒）：60 秒 */
    private static final long BREAKER_OPEN_MS = 60_000L;
    /** 队列容量下限 / 上限（配置写飞了也不至于 OOM 或等于没有） */
    private static final int MIN_QUEUE = 8;
    private static final int MAX_QUEUE = 1024;

    private final ForgingMasterPlugin plugin;

    /**
     * 自检注入的接收器：非 null 时完全不碰网络，只把 JSON 交给它。
     *
     * <p>自检要验「序列化 + 限流 + 停止语义」，但绝不该真的发请求出去，
     * 所以生产路径与自检路径只在这一处分开。</p>
     */
    private final Consumer<String> sink;

    private volatile boolean running;
    private volatile Thread worker;
    private volatile BlockingQueue<String> queue;

    /** 每类事件上次真正发送的时刻（消费线程独占写，主线程不读，无需同步） */
    private final Map<WebhookEvent, Long> lastSent = new EnumMap<>(WebhookEvent.class);

    private final AtomicLong sent = new AtomicLong();
    private final AtomicLong dropped = new AtomicLong();
    private final AtomicLong failed = new AtomicLong();

    private int consecutiveFailures;
    private long breakerOpenUntil;

    public WebhookService(ForgingMasterPlugin plugin) {
        this(plugin, null);
    }

    /** 自检专用构造：{@code sink != null} 则不发网络请求 */
    public WebhookService(ForgingMasterPlugin plugin, Consumer<String> sink) {
        this.plugin = plugin;
        this.sink = sink;
    }

    // ------------------------------------------------------------ 生命周期

    /** 起消费线程（幂等：先停再起）。未启用时什么都不做。 */
    public synchronized void start() {
        stop();
        if (!enabled()) {
            return;
        }
        int cap = clampQueue(plugin.settings().intOf("webhook.queue-size", 64));
        queue = new ArrayBlockingQueue<>(cap);
        // v2.26.6（审计 #5）：reload 路径 start()→stop()→start() 会继承上一轮的
        // 熔断状态——热重载后新 worker 带着旧 breakerOpenUntil 继续静默丢消息。
        running = true;
        consecutiveFailures = 0;
        breakerOpenUntil = 0;
        Thread t = new Thread(this::loop, "ForgingMaster-Webhook");
        t.setDaemon(true);
        worker = t;
        t.start();
    }

    /** 停消费线程：置标志 + 打断 + 等 1 秒。幂等，关服可重复调。 */
    public synchronized void stop() {
        running = false;
        Thread t = worker;
        worker = null;
        if (t != null) {
            t.interrupt();
            try {
                t.join(1000L);
            } catch (InterruptedException ex) {
                Thread.currentThread().interrupt();
            }
        }
        queue = null;
        lastSent.clear();
    }

    /** 配置变了：重起消费线程（队列里的旧消息随旧队列丢弃） */
    public synchronized void reload() {
        start();
    }

    /** 是否真的会发：开关打开 + url 是合法 http(s) */
    public boolean enabled() {
        if (!plugin.settings().flag("webhook.enabled", false)) {
            return false;
        }
        String url = url();
        if (url.isEmpty()) {
            return false;
        }
        // 配置写错（少了协议头 / 拼错）不该让插件抛异常，只当没配
        return url.startsWith("http://") || url.startsWith("https://");
    }

    public boolean running() {
        return running;
    }

    public long sentCount() {
        return sent.get();
    }

    public long droppedCount() {
        return dropped.get();
    }

    public long failedCount() {
        return failed.get();
    }

    // ------------------------------------------------------------ 发送

    /**
     * 投递一个事件。<b>主线程安全</b>：只做序列化 + 入队。
     *
     * @param type   事件类型（null 直接忽略）
     * @param text   正文（会被取纯文本；null 当空串）
     * @param fields 附加字段（可 null；按 key 字典序稳定输出）
     * @return true = 已入队；false = 未启用 / 未订阅 / 队列满被丢弃
     */
    public boolean dispatch(WebhookEvent type, Component text, Map<String, String> fields) {
        if (type == null || !running) {
            return false;
        }
        if (!subscribed(type)) {
            return false;
        }
        BlockingQueue<String> q = queue;
        if (q == null) {
            return false;
        }
        String json = toJson(type, plain(text), fields);
        // offer 不阻塞：满即丢最新，绝不拖慢主线程
        if (!q.offer(json)) {
            dropped.incrementAndGet();
            return false;
        }
        return true;
    }

    /** 便捷重载：纯文本正文 */
    public boolean dispatch(WebhookEvent type, String text) {
        return dispatch(type, Component.text(text == null ? "" : text), null);
    }

    // ------------------------------------------------------------ 内部

    private void loop() {
        while (running) {
            String json;
            try {
                BlockingQueue<String> q = queue;
                if (q == null) {
                    return;
                }
                json = q.poll(500L, TimeUnit.MILLISECONDS);
            } catch (InterruptedException ex) {
                return; // stop() 打断
            }
            if (json == null) {
                continue;
            }
            WebhookEvent type = typeOf(json);
            if (type != null && !passRateLimit(type)) {
                dropped.incrementAndGet();
                continue;
            }
            if (breakerOpen()) {
                dropped.incrementAndGet();
                continue;
            }
            send(json);
        }
    }

    /** 限流：同一类型 10 秒内只发一条。返回 true = 可以发（并已记账） */
    private boolean passRateLimit(WebhookEvent type) {
        long now = System.currentTimeMillis();
        Long last = lastSent.get(type);
        if (last != null && now - last < RATE_LIMIT_MS) {
            return false;
        }
        lastSent.put(type, now);
        return true;
    }

    private boolean breakerOpen() {
        return System.currentTimeMillis() < breakerOpenUntil;
    }

    private void send(String json) {
        if (sink != null) {
            try {
                sink.accept(json);
                sent.incrementAndGet();
                consecutiveFailures = 0;
            } catch (RuntimeException ex) {
                recordFailure(ex);
            }
            return;
        }
        try {
            HttpClient client = HttpClient.newBuilder()
                    .connectTimeout(Duration.ofSeconds(5))
                    .build();
            HttpRequest req = HttpRequest.newBuilder(URI.create(url()))
                    .timeout(Duration.ofSeconds(Math.max(1, plugin.settings().intOf(
                            "webhook.timeout-seconds", 10))))
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(json, StandardCharsets.UTF_8))
                    .build();
            HttpResponse<String> resp = client.send(req, HttpResponse.BodyHandlers.ofString());
            if (resp.statusCode() >= 200 && resp.statusCode() < 300) {
                sent.incrementAndGet();
                consecutiveFailures = 0;
            } else {
                recordFailure(new IOException("HTTP " + resp.statusCode()));
            }
        } catch (IOException | InterruptedException | IllegalArgumentException ex) {
            if (ex instanceof InterruptedException) {
                Thread.currentThread().interrupt();
            }
            recordFailure(ex);
        } catch (RuntimeException ex) {
            recordFailure(ex);
        }
    }

    /** 失败一律静默；连续 5 次熔断 60 秒。日志压到 fine 级，正常服主看不到。 */
    private void recordFailure(Throwable ex) {
        failed.incrementAndGet();
        consecutiveFailures++;
        // v2.26.6（审计 #1）：原来 `==` 只在恰好等于阈值时熔断一次——60 秒冷却后计数
        // 继续涨到 6、7、8…，熔断从此永不重臂，每条消息照常打向死端点。改 >= 并在
        // 熔断打开时把计数归零，让 60 秒后能再次熔断。
        if (consecutiveFailures >= BREAKER_THRESHOLD) {
            breakerOpenUntil = System.currentTimeMillis() + BREAKER_OPEN_MS;
            consecutiveFailures = 0;
            plugin.getLogger().fine("Webhook 连续失败 " + BREAKER_THRESHOLD
                    + " 次，熔断 60 秒（失败的这条已丢弃，不是延迟重试）：" + ex);
        } else {
            plugin.getLogger().fine("Webhook 发送失败（已忽略）：" + ex);
        }
    }

    private String url() {
        String raw = plugin.getConfig().getString("webhook.url", "");
        return raw == null ? "" : raw.trim();
    }

    /** 配置白名单；空列表 = 全开；写错的事件名直接忽略 */
    private boolean subscribed(WebhookEvent type) {
        List<String> ids = plugin.getConfig().getStringList("webhook.events");
        if (ids == null || ids.isEmpty()) {
            return true;
        }
        Set<WebhookEvent> allow = EnumSet.noneOf(WebhookEvent.class);
        for (String id : ids) {
            WebhookEvent e = WebhookEvent.parse(id);
            if (e != null) {
                allow.add(e);
            }
        }
        return allow.isEmpty() || allow.contains(type);
    }

    // ------------------------------------------------------------ JSON

    /**
     * 手写 JSON（零依赖）。
     *
     * <p>结构固定，便于接收端用一行规则解析：</p>
     * <pre>{"username":"…","content":"…","event":"forge_success","fields":{"k":"v"}}</pre>
     */
    String toJson(WebhookEvent type, String content, Map<String, String> fields) {
        StringBuilder sb = new StringBuilder(160);
        sb.append('{');
        sb.append("\"username\":\"").append(escape(username())).append("\",");
        sb.append("\"content\":\"").append(escape(content == null ? "" : content)).append("\",");
        sb.append("\"event\":\"").append(escape(type.id())).append("\",");
        sb.append("\"type\":\"").append(escape(type.label())).append("\",");
        sb.append("\"fields\":{");
        // 字典序输出：同样的输入永远得到同样的 JSON，日志/自检才好比对
        Map<String, String> sorted = new TreeMap<>();
        if (fields != null) {
            for (Map.Entry<String, String> e : fields.entrySet()) {
                if (e.getKey() != null && !e.getKey().isBlank() && e.getValue() != null) {
                    sorted.put(e.getKey(), e.getValue());
                }
            }
        }
        boolean first = true;
        for (Map.Entry<String, String> e : sorted.entrySet()) {
            if (!first) {
                sb.append(',');
            }
            first = false;
            sb.append('"').append(escape(e.getKey())).append("\":\"")
                    .append(escape(e.getValue())).append('"');
        }
        sb.append("}}");
        return sb.toString();
    }

    private String username() {
        String u = plugin.getConfig().getString("webhook.username", "铸剑大师");
        return u == null || u.isBlank() ? "ForgingMaster" : u;
    }

    /**
     * JSON 字符串转义（RFC 8259）。
     *
     * <p>必须处理：{@code "} {@code \} 以及所有 {@code < 0x20} 的控制字符
     * （换行 / 制表 / NUL）。这里统一转 {@code \\u00XX}，比手写 {\@code \n} 分支
     * 更不容易漏（少转义一个字符就能让整条 JSON 非法）。</p>
     */
    static String escape(String raw) {
        if (raw == null) {
            return "";
        }
        StringBuilder sb = new StringBuilder(raw.length() + 8);
        for (int i = 0; i < raw.length(); i++) {
            char c = raw.charAt(i);
            switch (c) {
                case '"' -> sb.append("\\\"");
                case '\\' -> sb.append("\\\\");
                case '\n' -> sb.append("\\n");
                case '\r' -> sb.append("\\r");
                case '\t' -> sb.append("\\t");
                default -> {
                    if (c < 0x20 || c == 0x7F) {
                        sb.append(String.format("\\u%04x", (int) c));
                    } else {
                        sb.append(c);
                    }
                }
            }
        }
        return sb.toString();
    }

    /** 从已序列化的 JSON 里取回 event id（限流按类型分桶需要它） */
    private static WebhookEvent typeOf(String json) {
        if (json == null) {
            return null;
        }
        int at = json.indexOf("\"event\":\"");
        if (at < 0) {
            return null;
        }
        int start = at + 9;
        int end = json.indexOf('"', start);
        if (end <= start) {
            return null;
        }
        return WebhookEvent.parse(json.substring(start, end));
    }

    private static String plain(Component c) {
        if (c == null) {
            return "";
        }
        try {
            return PlainTextComponentSerializer.plainText().serialize(c);
        } catch (RuntimeException ex) {
            return c.toString();
        }
    }

    private static int clampQueue(int raw) {
        return Math.max(MIN_QUEUE, Math.min(MAX_QUEUE, raw));
    }

    /** 自检用：把事件白名单解析成集合（未知项忽略，空表 = 全开） */
    public static List<String> knownIds() {
        List<String> ids = new ArrayList<>();
        for (WebhookEvent e : WebhookEvent.values()) {
            ids.add(e.id());
        }
        return Collections.unmodifiableList(ids);
    }
}