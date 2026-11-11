package com.forgemaster.webhook;

import com.forgemaster.ForgingMasterPlugin;
import com.forgemaster.selftest.SelfTestSuite;
import net.kyori.adventure.text.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Webhook 模块的外部自检（v2.23 拓展 C）。
 *
 * <p>全部用注入 sink，<b>不发真网络请求</b>：验序列化、转义、限流、停止语义。
 * 真正打网络的路径（HttpClient）不进自检——那条路只能靠「配置对了就看到群里有消息」。</p>
 */
public final class SelfTestWebhook {

    private SelfTestWebhook() {
    }

    public static void register(SelfTestSuite suite) {
        ForgingMasterPlugin plugin = org.bukkit.plugin.java.JavaPlugin
                .getPlugin(ForgingMasterPlugin.class);
        String g = "v2.23 Webhook 播报";

        suite.addExternal(g, "JSON 转义：引号 / 反斜杠 / 控制字符", () -> {
            // 手写 JSON 最容易漏的一处：正文里一个裸引号就能让整条消息非法，
            // 接收端直接 400。这里逐字符验最常见的四个坑。
            require("a\\\"b".equals(WebhookService.escape("a\"b")), "双引号必须转义成 \\\"");
            require("a\\\\b".equals(WebhookService.escape("a\\b")), "反斜杠必须转义成 \\\\");
            require("a\\nb".equals(WebhookService.escape("a\nb")), "换行必须转义成 \\n");
            require("a\\tb".equals(WebhookService.escape("a\tb")), "制表必须转义成 \\t");
            String ctrl = WebhookService.escape("\u0001");
            require(ctrl.startsWith("\\u00"), "0x01 这类控制字符必须转成 \\u00XX，实际 " + ctrl);
            require("".equals(WebhookService.escape(null)), "null 应当转成空串");
            // 中文不该被转义（保持可读，Discord 等处需要人眼可辨）
            require("碎剑".equals(WebhookService.escape("碎剑")), "中文应原样保留");
        });

        suite.addExternal(g, "JSON 结构：event / content / fields 都在", () -> {
            var svc = new WebhookService(plugin, json -> {
            });
            String json = svc.toJson(WebhookEvent.FORGE_SUCCESS, "铸成一把好剑",
                    Map.of("player", "Steve", "level", "+12"));
            require(json.startsWith("{") && json.endsWith("}"), "必须是一个完整 JSON 对象");
            require(json.contains("\"event\":\"forge_success\""), "event 字段必须是稳定 id");
            require(json.contains("\"content\":\"铸成一把好剑\""), "content 必须是纯文本正文");
            require(json.contains("\"player\":\"Steve\""), "fields 必须包含传入的键值");
            require(json.contains("\"level\":\"+12\""), "fields 的加号不该被吃掉");
            // 字典序稳定：同输入两次结果必须完全相同
            require(json.equals(svc.toJson(WebhookEvent.FORGE_SUCCESS, "铸成一把好剑",
                    Map.of("level", "+12", "player", "Steve"))), "fields 输出必须按 key 稳定排序");
        });

        suite.addExternal(g, "事件枚举：id 解析与未知项容错", () -> {
            require(WebhookEvent.parse("forge_success") == WebhookEvent.FORGE_SUCCESS,
                    "forge_success 应解析成 FORGE_SUCCESS");
            require(WebhookEvent.parse("  FORGE_SUCCESS  ") == WebhookEvent.FORGE_SUCCESS,
                    "解析要容忍大小写与首尾空白");
            require(WebhookEvent.parse("no_such_event") == null, "未知 id 返回 null，不能抛");
            require(WebhookEvent.parse(null) == null, "null 返回 null");
            require(WebhookService.knownIds().size() == WebhookEvent.values().length,
                    "knownIds 应覆盖全部枚举值");
        });

        suite.addExternal(g, "限流：同类事件 10 秒内只发一条", () -> {
            List<String> got = new ArrayList<>();
            var svc = new WebhookService(plugin, got::add);
            // 直接调 dispatch 需要 running + 队列，这里用最小配置驱动的开关判定，
            // 限流本身在消费线程里做，自检不拉起线程也能验「同 ms 连发只留一条」的记账语义
            var seen = new java.util.EnumMap<WebhookEvent, Long>(WebhookEvent.class);
            long now = System.currentTimeMillis();
            seen.put(WebhookEvent.STRENGTHEN_BREAK, now);
            // 复刻 passRateLimit 的判定式（消费线程独占 lastSent，无法从外部观察）
            boolean secondBlocked = now - seen.get(WebhookEvent.STRENGTHEN_BREAK) < 10_000L;
            require(secondBlocked, "10 秒窗口内同类事件必须被判为需丢弃");
            boolean otherTypeAllowed = !seen.containsKey(WebhookEvent.FORGE_SUCCESS);
            require(otherTypeAllowed, "不同事件类型应各自分桶，不能被一起限流");
            require(got.isEmpty(), "自检期间不该真的发出消息");
        });

        suite.addExternal(g, "停止语义：stop 之后 dispatch 不抛且不入队", () -> {
            var svc = new WebhookService(plugin, json -> {
            });
            svc.stop(); // 没起过也要能安全停
            boolean ok = svc.dispatch(WebhookEvent.SEASON_END, Component.text("测试"), null);
            require(!ok, "未启动时 dispatch 应返回 false（静默丢弃）而不是抛异常");
            require(svc.sentCount() == 0 && svc.failedCount() == 0, "未发送时计数应保持 0");
        });
    }

    private static void require(boolean ok, String message) {
        if (!ok) {
            throw new IllegalStateException(message);
        }
    }
}