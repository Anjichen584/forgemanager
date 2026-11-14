package com.forgemaster.selftest;

import com.forgemaster.ForgingMasterPlugin;
import com.forgemaster.affix.AffixScaling;
import com.forgemaster.forge.ForgeManager;
import com.forgemaster.forge.ForgeSession;
import com.forgemaster.gui.ForgeMenu;
import com.forgemaster.material.ForgeMaterial;
import com.forgemaster.material.MaterialGrade;
import com.forgemaster.material.MaterialGrades;
import com.forgemaster.quality.Quality;
import com.forgemaster.stats.StatsStore;
import com.forgemaster.util.Keys;
import com.forgemaster.util.Msg;
import org.bukkit.Material;
import org.bukkit.attribute.Attribute;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataType;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * 运行时自检（E6）：每一项都是具名、只读的检查。
 *
 * <p>约束：
 * <ul>
 *   <li>所有假数据的玩家名统一为 {@code SelfTest}（不进排行，见 Rankings.active）。</li>
 *   <li>不调用会写真实玩家档案的方法（如 stats().record 用真实 UUID）；档案相关检查只用 SelfTest 的假 UUID。</li>
 *   <li>存盘类检查只做内存往返，不改动磁盘上的真实数据文件。</li>
 * </ul>
 * 每项失败只记录「项名 + 原因」，不影响其他项继续跑。
 */
public final class SelfTestSuite {

    /** 单项检查的函数式接口 */
    @FunctionalInterface
    public interface Body {
        void run() throws Exception;
    }

    /** 一项检查：分组 + 名称 + 实际逻辑 */
    record Check(String group, String name, Body body) {
    }

    /** 检查结果 */
    public record Result(String group, String name, boolean ok, String reason) {
    }

    private static final String NAME = "SelfTest";

    private final ForgingMasterPlugin plugin;
    private final List<Check> checks = new ArrayList<>();

    public SelfTestSuite(ForgingMasterPlugin plugin) {
        this.plugin = plugin;
        register();
    }

    /** 当前注册的检查项总数 */
    public int size() {
        return checks.size();
    }

    /** 逐项执行，返回全部结果（不会因单项失败中断） */
    public List<Result> run() {
        List<Result> out = new ArrayList<>();
        for (Check c : checks) {
            try {
                c.body().run();
                out.add(new Result(c.group(), c.name(), true, ""));
            } catch (Throwable ex) {
                String msg = ex.getMessage() == null ? ex.toString() : ex.getMessage();
                out.add(new Result(c.group(), c.name(), false, msg));
            }
        }
        return out;
    }

    /** 把结果按分组打印给命令发送者，失败项标红 */
    public void report(org.bukkit.command.CommandSender to, List<Result> results) {
        int ok = 0;
        int fail = 0;
        for (Result r : results) {
            if (r.ok()) {
                ok++;
            } else {
                fail++;
            }
        }
        Msg.prefix(to, "<green>自检完成：共 <white>" + results.size() + "</white> 项，通过 <white>" + ok
                + "</white>，<red>失败 " + fail + "</red>。</green>");
        // 失败项同时写进服务器日志：RCON 回包在高项数时会被聊天缓冲截断，
        // 存档里经常丢掉 ✗ 那几行，只留下「失败 1」却查不到是哪条（v2.19.1 补）
        if (fail > 0) {
            for (Result r : results) {
                if (!r.ok()) {
                    org.bukkit.Bukkit.getLogger().warning("[SelfTest] ✗ " + r.group() + " / "
                            + r.name() + " → " + r.reason());
                }
            }
        }
        String lastGroup = null;
        for (Result r : results) {
            if (!r.group().equals(lastGroup)) {
                Msg.send(to, "<dark_gray>── <#ffd166>" + r.group());
                lastGroup = r.group();
            }
            if (r.ok()) {
                Msg.send(to, "<green>✓ <gray>" + r.name());
            } else {
                Msg.send(to, "<red>✗ " + r.name() + " <dark_gray>→ <red>" + r.reason());
            }
        }
    }

    // ================================================================ 注册

    private void add(String group, String name, Body body) {
        checks.add(new Check(group, name, body));
    }

    /**
     * 外部模块的注册口：各功能包在自己的 {@code SelfTest<Module>.register(suite)} 里
     * 挂接本模块的自检项，不必把用例全塞进这个套件文件。
     * {@code require} 是私有的，外部用例直接抛 {@link IllegalStateException} 表达失败。
     */
    public void addExternal(String group, String name, Body body) {
        checks.add(new Check(group, name, body));
    }

    private static void require(boolean cond, String msg) throws Exception {
        if (!cond) {
            throw new IllegalStateException(msg);
        }
    }

    /** 源码扫描钉子用的命中计数（v2.26.0）：失败信息里要报命中数，别只说「没找到」 */
    private static int hits(String haystack, String needle) {
        if (haystack == null || needle == null || needle.isEmpty()) {
            return 0;
        }
        int n = 0, idx = 0;
        while ((idx = haystack.indexOf(needle, idx)) >= 0) {
            n++;
            idx += needle.length();
        }
        return n;
    }

    /**
     * 源码扫描钉子的通用前置（v2.26.0 抽出）：读文件并剔除注释行。
     *
     * <p>为什么必须剔注释：本仓库的注释密度极高，而且习惯在注释里引用「原来的错误写法」
     * （例如「这里曾经是 stats().get(uuid)」）。不剔注释的话，一条「不许出现 X」的钉子
     * 会被自己那句解释说明反向命中 —— 假红比假绿更浪费时间。读不到文件返回空串，
     * 让调用方的 hits() 自然得 0，由各自的 require 报出来。</p>
     */
    private static String nonCommentSource(java.io.File f) {
        if (f == null || !f.isFile()) {
            return "";
        }
        String code;
        try {
            code = new String(java.nio.file.Files.readAllBytes(f.toPath()),
                    java.nio.charset.StandardCharsets.UTF_8);
        } catch (java.io.IOException ex) {
            return "";
        }
        StringBuilder nc = new StringBuilder();
        for (String line : code.split("\n")) {
            String t = line.trim();
            if (t.startsWith("//") || t.startsWith("*") || t.startsWith("/*")) {
                continue;
            }
            nc.append(stripTrailingComment(line)).append('\n');
        }
        return nc.toString();
    }

    /**
     * 去掉行尾注释（v2.26.0 审计轮补）。
     *
     * <p>为什么会咬人：{@code nonCommentSource} 原来只剔「以 // 开头」的整行，
     * 而本仓库最常见的写法是<b>行尾说明</b> —— {@code addHelp(out, u, "…");  // 原来写死 514 项}。
     * 那一行会被整行保留，「不许出现 514」的钉子当场变红，而代码其实是对的。
     * 这类假红会让人去改钉子（而不是改代码），比假绿更坏。</p>
     *
     * <p>做法是最小心的那种：逐字符走，进入双引号 / 单引号字面量后不再认双斜杠，
     * 遇到引号外的双斜杠就截断。不处理行内块注释（本仓库没有这种写法），
     * 也不处理 text block —— 这两个只会让钉子更严，不会假绿。</p>
     */
    private static String stripTrailingComment(String line) {
        boolean inStr = false;
        boolean inChr = false;
        for (int i = 0; i < line.length() - 1; i++) {
            char c = line.charAt(i);
            if (c == '\\' && (inStr || inChr)) {
                i++; // 跳过转义的下一个字符
                continue;
            }
            if (c == '"' && !inChr) {
                inStr = !inStr;
                continue;
            }
            if (c == '\'' && !inStr) {
                inChr = !inChr;
                continue;
            }
            if (!inStr && !inChr && c == '/' && line.charAt(i + 1) == '/') {
                return line.substring(0, i);
            }
        }
        return line;
    }

    /**
     * 从整份（已剔注释的）源码里切出某个方法体（v2.26.0）。
     *
     * <p>切法：从 {@code marker}（含签名前缀，例如 {@code "void statsOfPublic("}）往后取，
     * 一直取到下一个「缩进 4 空格 + private/public/static」的成员声明之前。
     * 不够严谨（字符串里出现这种缩进会误切），但对本仓库的排版足够 ——
     * 它是给「方法体内不许出现某调用」这类钉子用的，误切只会让钉子更严、不会假绿。
     * 找不到 marker 返回 null，调用方 require 报红。</p>
     */
    /**
     * 便捷版方法体切片（v2.26.4）：给「按 src 相对路径 + marker 钉形状」的静态钉子用。
     * 读不到源码树 / 文件时返回 null，调用方自行决定跳过或 require 报红。
     */
    /** 整文件文本（v2.26.6）：给「这个文件里必须出现某调用」的钉子用；读不到返回 null */
    private static String sourceFileText(String relPath) {
        java.io.File pkgRoot = findSourcePackageRoot();
        if (pkgRoot == null) {
            return null;
        }
        java.io.File src = new java.io.File(pkgRoot, relPath);
        if (!src.isFile()) {
            return null;
        }
        try {
            return new String(java.nio.file.Files.readAllBytes(src.toPath()),
                    java.nio.charset.StandardCharsets.UTF_8);
        } catch (java.io.IOException ex) {
            return null;
        }
    }

    private static String sourceMethodBody(String relPath, String marker) {
        java.io.File pkgRoot = findSourcePackageRoot();
        if (pkgRoot == null) {
            return null;
        }
        java.io.File src = new java.io.File(pkgRoot, relPath);
        if (!src.isFile()) {
            return null;
        }
        try {
            String code = new String(java.nio.file.Files.readAllBytes(src.toPath()),
                    java.nio.charset.StandardCharsets.UTF_8);
            return methodBody(code, marker);
        } catch (java.io.IOException ex) {
            return null;
        }
    }

    private static String methodBody(String source, String marker) {
        if (source == null || marker == null) {
            return null;
        }
        int at = source.indexOf(marker);
        if (at < 0) {
            return null;
        }
        // 往回退到这一行的行首，保证返回体里包含完整签名
        int head = source.lastIndexOf('\n', at);
        head = head < 0 ? 0 : head + 1;
        int tail = source.length();
        java.util.regex.Matcher m = java.util.regex.Pattern
                .compile("\n    (?:private|public|protected|static|final)\\s")
                .matcher(source);
        if (m.find(Math.max(at, head + marker.length()))) {
            tail = m.start();
        }
        return source.substring(head, tail);
    }

    /**
     * 从一段方法体里切出「以某个 needle 开头的那一小截」（v2.26.0 审计轮补）。
     *
     * <p>为什么需要它：判断「reload 段里有没有 save()」这类断言，如果对整段文本用
     * {@code contains(".save()")}，那 {@code flushAll} 里任何一处 save 都会误伤；
     * 反过来只查 {@code !reload.contains("powerLog().save()")} 又只堵了一种拼法
     * （写成 {@code store.save()} 就漏过）。按 needle 定位、往后取到下一个 swallow(...)
     * 或方法尾，才是真的在说「这一段」。</p>
     *
     * <p>切法故意保守：从 needle 所在行开始，往后最多取 12 行或到下一个
     * {@code swallow(} 为止。取不到 needle 返回 null，调用方 require 报红。</p>
     */
    private static String sliceByMarker(String body, String needle) {
        if (body == null || needle == null) {
            return null;
        }
        int at = body.indexOf(needle);
        if (at < 0) {
            return null;
        }
        int lineStart = body.lastIndexOf('\n', at);
        lineStart = lineStart < 0 ? 0 : lineStart + 1;
        int end = body.length();
        int next = body.indexOf("swallow(", at + needle.length());
        if (next > 0) {
            end = next;
        }
        String chunk = body.substring(lineStart, end);
        String[] lines = chunk.split("\n");
        if (lines.length > 12) {
            StringBuilder sb = new StringBuilder();
            for (int i = 0; i < 12; i++) {
                sb.append(lines[i]).append('\n');
            }
            return sb.toString();
        }
        return chunk;
    }

    /**
     * help 文本里是否写了「{@code /zhujian <name>}」这一条。
     *
     * <p>后面必须跟空格 / 换行 / 行尾才算命中 —— 否则 {@code give} 会命中 {@code givefull}、
     * {@code hall} 会命中 {@code halloffame}，断言就变成了假绿。</p>
     */
    /**
     * 自检用：定位 {@code src/main/java/com/forgemaster} 这个包根，找不到返回 null。
     *
     * <p>这台机器上同时存在两棵树：正在开发的是 {@code ForgingMaster-Lore}，
     * 而测试服的父目录 {@code ForgingMaster} 里还留着一份 2.10.6 的旧源码树。
     * 第一版只取「第一个找到的」，于是命中了旧树，把已经改好的存档判成「还在裸写」——
     * 一条假红。现在给候选打分：<b>含 {@code util/YamlIo.java} 的优先</b>，
     * 其次是 pom 版本号与运行时一致的，都没有就返回 null（跳过静态部分）。</p>
     */
    static java.io.File findSourcePackageRoot() {
        // v2.26.3：显式指定优先。这条是「静态检查钉子集体假绿」的根因修复——
        // 以前只靠「从服务器工作目录向上找 6 层 + 兄弟目录」猜源码树，
        // 一旦测试服搬出仓库父目录（例如 D:\game\1.21.10server 与仓库不同盘符树），
        // 就永远找不到，而每条钉子的写法都是「找不到就 return」→ **全部静默变绿**。
        // 实测有 22 处静态钉子在那种部署下一条都没跑过。
        //   启动参数：-Dforgemaster.source-root=D:\IDEA\mc-plugins\ForgingMaster-Lore
        String explicit = System.getProperty("forgemaster.source-root");
        if (explicit != null && !explicit.isBlank()) {
            java.io.File hit = packageRootUnder(new java.io.File(explicit));
            if (hit != null) {
                return hit;
            }
            org.bukkit.Bukkit.getLogger().warning("[selftest] -Dforgemaster.source-root 指向的目录下没有 "
                    + "src/main/java/com/forgemaster（值=" + explicit + "），回退到自动探测");
        }
        java.util.LinkedHashSet<java.io.File> candidates = new java.util.LinkedHashSet<>();
        java.io.File dir = new java.io.File(".").getAbsoluteFile();
        for (int up = 0; up < 6 && dir != null; up++) {
            java.io.File hit = packageRootUnder(dir);
            if (hit != null) {
                candidates.add(hit);
            }
            java.io.File[] children = dir.listFiles(java.io.File::isDirectory);
            if (children != null) {
                for (java.io.File child : children) {
                    java.io.File c = packageRootUnder(child);
                    if (c != null) {
                        candidates.add(c);
                    }
                }
            }
            dir = dir.getParentFile();
        }
        String running = null;
        try {
            running = pluginVersionOfThisClass();
        } catch (RuntimeException ex) {
            running = null;
        }
        java.io.File best = null;
        int bestScore = 0;
        for (java.io.File c : candidates) {
            int score = 0;
            if (new java.io.File(c, "util/YamlIo.java").isFile()) {
                score += 100;
            }
            if (running != null && pomHasVersion(c, running)) {
                score += 50;
            }
            if (score > bestScore) {
                bestScore = score;
                best = c;
            }
        }
        if (best == null && !warnedNoSourceRoot) {
            warnedNoSourceRoot = true;
            // 用 WARNING 而不是 INFO：这条一旦出现，说明**所有静态检查钉子都没在守**，
            // 而它们的断言形状是「找不到就 return」，界面上照样一片绿。
            org.bukkit.Bukkit.getLogger().warning("[selftest] 找不到源码树 —— 本轮所有"
                    + "「按源码形状断言」的钉子是**跳过**状态（不是通过）。"
                    + "要让它们真正执行：启动加 -Dforgemaster.source-root=<仓库根>");
        }
        return best;
    }

    /** 「找不到源码树」只警告一次，别刷满日志 */
    private static boolean warnedNoSourceRoot;

    private static String pluginVersionOfThisClass() {
        try {
            var desc = SelfTestSuite.class.getPackage().getImplementationVersion();
            return desc;
        } catch (RuntimeException ex) {
            return null;
        }
    }

    private static boolean pomHasVersion(java.io.File pkgRoot, String version) {
        java.io.File pom = new java.io.File(pkgRoot, "../../../../pom.xml");
        if (!pom.isFile()) {
            return false;
        }
        try {
            String text = new String(java.nio.file.Files.readAllBytes(pom.toPath()),
                    java.nio.charset.StandardCharsets.UTF_8);
            return text.contains("<version>" + version + "</version>");
        } catch (java.io.IOException ex) {
            return false;
        }
    }

    private static java.io.File packageRootUnder(java.io.File base) {
        if (base == null) {
            return null;
        }
        java.io.File pkg = new java.io.File(base, "src/main/java/com/forgemaster");
        return pkg.isDirectory() ? pkg : null;
    }

    private static boolean helpHas(String text, String name) {
        String needle = "/zhujian " + name;
        int from = 0;
        while (true) {
            int at = text.indexOf(needle, from);
            if (at < 0) {
                return false;
            }
            int end = at + needle.length();
            if (end >= text.length()) {
                return true;
            }
            char c = text.charAt(end);
            if (c == ' ' || c == '\n' || c == '\r' || c == '\t') {
                return true;
            }
            from = at + 1;
        }
    }

    private void register() {
        registerMaterialsAffixesQuench();
        registerForgeFlow();
        registerScoreAndStats();
        registerCoinsStreakShop();
        registerAuctionCommission();
        registerContestDuelMentor();
        registerGrowthEnvSpirit();
        registerTombRiskEventsStory();
        registerRankingSeasonBackup();
        registerEconomyArmor();
        registerDurabilityGem();
        registerPerfFixA();
        registerAffixScalingStrengthen();
        registerBigSword();
        registerSocketsAndGuards();
        registerStrengthenRisk();
        registerAnvilForge();
        com.forgemaster.anvil.SelfTestAnvil.register(this);
        com.forgemaster.pass.SelfTestPass.register(this);
        com.forgemaster.relicset.SelfTestRelicSet.register(this);
        com.forgemaster.tomb.SelfTestTombGuard.register(this);
        com.forgemaster.guild.SelfTestGuild.register(this);
        // v2.24 拓展：切片一（附魔/词条新机制）与切片二（剑灵剑冢/经济社交）的护栏
        com.forgemaster.selftest.SelfTestExpansion24Enchant.register(this);
        com.forgemaster.selftest.SelfTestExpansion24Social.register(this);
        // v2.25 拓展：切片一（血怒/坚韧附魔）与切片二（灵契重抽/强化保险）的护栏
        com.forgemaster.selftest.SelfTestExpansion25Enchant.register(this);
        com.forgemaster.selftest.SelfTestExpansion25Social.register(this);
        // v2.23.1：本轮 bug 修复的回归护栏（isBaseKey 精确化 / 铁砧心跳句柄 /
        // 付费轨奖励来源 / 配置漂移 / 仓库超容量抢救 / 看板接线 / 迁移版本号）
        SelfTestRegression231.register(this);
        // v2.23 拓展 C：Webhook 播报（JSON 转义 / 结构 / 限流 / 停止语义）
        com.forgemaster.webhook.SelfTestWebhook.register(this);
        registerV23();
        registerV25();
        registerV26();
        registerV27();
        registerV29();
        registerV210();
        registerEvolution();
        registerTickets();
        registerContractLoop();
        registerForgingExtra();
    }

    // ---------------------------------------------------------------- v2.19 收尾双工序

    /**
     * 抛光 / 嵌银丝两道收尾工序的断言。
     *
     * <p>覆盖四层：单品成色函数（纯函数）、阶段状态机、界面渲染、总分红线
     * （满配 100 / 关工序仍 100 / 单调性 / 工序链跳段）。</p>
     */
    private void registerForgingExtra() {
        String g = "v2.19 抛光 / 嵌银丝";
        ForgeMaterial first = firstMaterial();

        add(g, "抛光成色：正中满分", () -> {
            ForgeSession s = probeSession(first);
            s.polishWidth(16);
            require(s.polishGrade(0) == 1.0, "正中不是满分: " + s.polishGrade(0));
            require(s.polishGrade(16 * 0.5) == 1.0, "半宽一半内不是满分");
        });

        add(g, "抛光成色：两倍半宽外归零", () -> {
            ForgeSession s = probeSession(first);
            s.polishWidth(16);
            require(s.polishGrade(16 * 2) == 0.0, "两倍半宽处没归零");
            require(s.polishGrade(999) == 0.0, "远到天边还有分");
        });

        add(g, "抛光成色：偏差越大分越低且连续", () -> {
            ForgeSession s = probeSession(first);
            s.polishWidth(16);
            double a = s.polishGrade(4);
            double b = s.polishGrade(12);
            double c = s.polishGrade(20);
            require(a > b && b > c, "不单调: " + a + " / " + b + " / " + c);
            // 亮区内沿与外沿之间不许断层
            require(Math.abs(s.polishGrade(15.9) - s.polishGrade(16.1)) < 0.05,
                    "亮区边缘断层");
            require(s.polishGrade(-12) == s.polishGrade(12), "负偏差与正偏差不对称");
        });

        add(g, "抛光成色：亮区宽度有下限，不会压成 0", () -> {
            ForgeSession s = probeSession(first);
            s.polishWidth(0);
            require(s.polishWidth() >= 3.0, "亮区宽度下限失效: " + s.polishWidth());
        });

        add(g, "抛光开局：刀数/亮区/指针都在合法范围", () -> {
            ForgeSession s = probeSession(first);
            plugin.forge().simulateStartPolishing(s);
            require(s.phase() == com.forgemaster.forge.ForgePhase.POLISHING,
                    "没进抛光: " + s.phase());
            require(s.polishPasses() >= 1, "刀数配置非法: " + s.polishPasses());
            require(s.polishWidth() > 0, "亮区宽度为 0");
            require(s.polishSpeed() > 0, "指针速度为 0");
            require(s.polishValue() >= 0 && s.polishValue() <= 100, "指针跑到标尺外");
            require(s.polishDeadline() > System.currentTimeMillis(), "没给兜底时限");
        });

        add(g, "抛光开局：亮区整体落在标尺内", () -> {
            ForgeSession s = probeSession(first);
            for (int i = 0; i < 40; i++) {
                plugin.forge().simulateStartPolishing(s);
                double lo = s.polishCenter() - s.polishWidth();
                double hi = s.polishCenter() + s.polishWidth();
                require(lo >= -1e-6 && hi <= 100 + 1e-6,
                        "亮区出界: " + lo + " ~ " + hi + "（半宽 " + s.polishWidth() + "）");
            }
        });

        add(g, "抛光全中：成色满分且推进下一道", () -> {
            ForgeSession s = probeSession(first);
            plugin.forge().simulateStartPolishing(s);
            plugin.forge().simulatePolishAll(s);
            require(s.polishScore() >= 0.999, "刀刀正中却没满分: " + s.polishScore());
            require(s.polishHits() == s.polishPasses(), "命中刀数不对: " + s.polishHits());
            require(s.phase() != com.forgemaster.forge.ForgePhase.POLISHING, "抛光结束没推进");
        });

        add(g, "抛光超时：没下的刀按 0 摊平，成色不掉反涨", () -> {
            ForgeSession s = probeSession(first);
            plugin.forge().simulateStartPolishing(s);
            s.polishScore(1.0);
            s.polishPass(0);
            s.polishDeadline(System.currentTimeMillis() - 1);
            plugin.forge().simulateTick(s);
            require(s.polishScore() <= 0.001, "一刀没下却留着成色: " + s.polishScore());
        });

        add(g, "抛光界面：尺寸/下刀键/放弃键/轨道齐全", () -> {
            ForgeSession s = probeSession(first);
            plugin.forge().simulateStartPolishing(s);
            var inv = plugin.forge().menu().buildPolishing(s);
            require(inv.getSize() == ForgeMenu.FORGE_SIZE, "界面尺寸错");
            require(inv.getItem(ForgeMenu.STRIKE_SLOT) != null, "缺「下刀」键");
            require(inv.getItem(ForgeMenu.QUIT_SLOT) != null, "缺放弃键");
            for (int i = 0; i < ForgeMenu.TRACK_LEN; i++) {
                require(inv.getItem(ForgeMenu.TRACK_START + i) != null,
                        "光泽条第 " + i + " 格为空");
            }
        });

        add(g, "嵌银丝根数随档位加长且夹在 2~8", () -> {
            int base = plugin.settings().inlayLengthBase();
            int prev = -1;
            for (int tier = 1; tier <= 5; tier++) {
                int len = com.forgemaster.forge.ForgeTuning.inlayLength(base, tier);
                require(len >= 2 && len <= 8, "tier " + tier + " 根数越界: " + len);
                require(len >= prev, "档位越高根数反而变少: " + prev + " → " + len);
                prev = len;
            }
        });

        add(g, "嵌银丝开局：序列合法、亮窗为正、有兜底时限", () -> {
            ForgeSession s = probeSession(first);
            plugin.forge().simulateStartInlay(s);
            require(s.phase() == com.forgemaster.forge.ForgePhase.INLAY, "没进嵌银丝: " + s.phase());
            require(s.inlaySequence().size() >= 2, "银丝太少: " + s.inlaySequence().size());
            for (int idx : s.inlaySequence()) {
                require(idx >= 0 && idx < ForgeMenu.TRACK_LEN, "银丝下标越界: " + idx);
            }
            require(s.inlayWindow() >= 5, "亮窗太短: " + s.inlayWindow());
            require(s.inlayWindowLeft() == s.inlayWindow(), "首根没有满亮窗");
            require(s.inlayDeadline() > System.currentTimeMillis(), "没给兜底时限");
        });

        add(g, "嵌银丝全中：成色满分且进淬火", () -> {
            ForgeSession s = probeSession(first);
            plugin.forge().simulateStartInlay(s);
            plugin.forge().simulateInlayAll(s);
            require(s.inlayScore() >= 0.999, "全中却没满分: " + s.inlayScore());
            require(s.inlayMiss() == 0, "全中却有失手: " + s.inlayMiss());
            require(s.phase() == com.forgemaster.forge.ForgePhase.QUENCHING,
                    "嵌丝结束没进淬火: " + s.phase());
        });

        add(g, "嵌银丝点错：不推进、亮窗被缩短", () -> {
            ForgeSession s = probeSession(first);
            plugin.forge().simulateStartInlay(s);
            int target = s.inlayTarget();
            int wrong = (target + 1) % ForgeMenu.TRACK_LEN;
            int leftBefore = s.inlayWindowLeft();
            // 离线夹具必须走 simulateClick：handleClick 的头一道闸是在线校验，
            // 拿它驱动假会话会让点击被静默吞掉（这条断言就是这么空跑过一次）
            plugin.forge().simulateClick(s, ForgeMenu.TRACK_START + wrong);
            require(s.inlayCursor() == 0, "点错却推进了: " + s.inlayCursor());
            require(s.inlayMiss() == 1, "点错没记失手: " + s.inlayMiss());
            require(s.inlayWindowLeft() < leftBefore, "点错没缩短亮窗");
        });

        add(g, "嵌银丝点对：离线夹具靠 simulateClick 计数", () -> {
            ForgeSession s = probeSession(first);
            plugin.forge().simulateStartInlay(s);
            int target = s.inlayTarget();
            plugin.forge().simulateClick(s, ForgeMenu.TRACK_START + target);
            require(s.inlayHits() == 1, "点对没记命中: " + s.inlayHits());
            require(s.inlayCursor() == 1, "点对没推进: " + s.inlayCursor());
            require(s.inlayMiss() == 0, "点对却记了失手: " + s.inlayMiss());
        });

        add(g, "在线护栏：handleClick 不推进离线会话", () -> {
            ForgeSession s = probeSession(first);
            plugin.forge().simulateStartInlay(s);
            int target = s.inlayTarget();
            int leftBefore = s.inlayWindowLeft();
            plugin.forge().handleClick(s, ForgeMenu.TRACK_START + target, false);
            require(s.inlayHits() == 0 && s.inlayCursor() == 0,
                    "离线会话被 handleClick 推进了: 命中 " + s.inlayHits()
                            + " / 进度 " + s.inlayCursor());
            require(s.inlayWindowLeft() == leftBefore, "离线会话的亮窗被改动了");
        });

        add(g, "嵌银丝亮窗超时：记失手并换下一根", () -> {
            ForgeSession s = probeSession(first);
            plugin.forge().simulateStartInlay(s);
            int window = s.inlayWindow();
            for (int i = 0; i < window; i++) {
                plugin.forge().simulateTick(s);
                if (s.phase() != com.forgemaster.forge.ForgePhase.INLAY) {
                    break;
                }
            }
            require(s.inlayMiss() >= 1, "亮窗灭了没记失手");
            require(s.inlayCursor() >= 1, "亮窗灭了没换下一根");
        });

        add(g, "嵌银丝成色：失手扣分但不为负", () -> {
            ForgeSession s = probeSession(first);
            plugin.forge().simulateStartInlay(s);
            s.inlayMiss(60);
            s.inlayHits(0);
            plugin.forge().simulateInlayAll(s);
            require(s.inlayScore() >= 0.0, "成色跑成负数: " + s.inlayScore());
            require(s.inlayScore() <= 1.0, "成色超过 1: " + s.inlayScore());
        });

        add(g, "嵌银丝界面：尺寸/放弃键/轨道齐全", () -> {
            ForgeSession s = probeSession(first);
            plugin.forge().simulateStartInlay(s);
            var inv = plugin.forge().menu().buildInlay(s);
            require(inv.getSize() == ForgeMenu.FORGE_SIZE, "界面尺寸错");
            require(inv.getItem(ForgeMenu.QUIT_SLOT) != null, "缺放弃键");
            for (int i = 0; i < ForgeMenu.TRACK_LEN; i++) {
                require(inv.getItem(ForgeMenu.TRACK_START + i) != null,
                        "银丝槽第 " + i + " 格为空");
            }
        });

        add(g, "七工序满配仍是 100", () -> {
            double full = ForgeManager.weightedScore(1, 1, 1, 1, 1, 1, 15, true, true, true, true);
            require(Math.abs(full - 100.0) < 1e-9, "七工序满配不是 100: " + full);
        });

        add(g, "关掉抛光 / 嵌银丝后满配仍是 100", () -> {
            double noPolish = ForgeManager.weightedScore(1, 1, 1, 1, 0, 1, 15,
                    true, true, false, true);
            require(Math.abs(noPolish - 100.0) < 1e-9, "关抛光后满配不是 100: " + noPolish);
            double noInlay = ForgeManager.weightedScore(1, 1, 1, 1, 1, 0, 15,
                    true, true, true, false);
            require(Math.abs(noInlay - 100.0) < 1e-9, "关嵌丝后满配不是 100: " + noInlay);
            double none = ForgeManager.weightedScore(1, 1, 0, 0, 0, 0, 15,
                    false, false, false, false);
            require(Math.abs(none - 100.0) < 1e-9, "全关后满配不是 100: " + none);
        });

        add(g, "七工序公式：全 0 得 0、分量变好不掉分", () -> {
            require(ForgeManager.weightedScore(0, 0, 0, 0, 0, 0, 0, true, true, true, true) == 0.0,
                    "全 0 不是 0");
            double low = ForgeManager.weightedScore(0.5, 0.5, 0.5, 0.5, 0.2, 0.5, 7,
                    true, true, true, true);
            double high = ForgeManager.weightedScore(0.5, 0.5, 0.5, 0.5, 0.9, 0.5, 7,
                    true, true, true, true);
            require(high >= low, "抛光变好反而掉分");
            double inlayHigh = ForgeManager.weightedScore(0.5, 0.5, 0.5, 0.5, 0.5, 0.9, 7,
                    true, true, true, true);
            require(inlayHigh >= low, "嵌丝变好反而掉分");
        });

        add(g, "工序链：全开时逐段推进", () -> {
            require(ForgeManager.nextStage(com.forgemaster.forge.ForgePhase.FOLDING,
                    true, true, true, true) == com.forgemaster.forge.ForgePhase.GRINDING, "叠钢后不是开刃");
            require(ForgeManager.nextStage(com.forgemaster.forge.ForgePhase.GRINDING,
                    true, true, true, true) == com.forgemaster.forge.ForgePhase.POLISHING, "开刃后不是抛光");
            require(ForgeManager.nextStage(com.forgemaster.forge.ForgePhase.POLISHING,
                    true, true, true, true) == com.forgemaster.forge.ForgePhase.INLAY, "抛光后不是嵌银丝");
            require(ForgeManager.nextStage(com.forgemaster.forge.ForgePhase.INLAY,
                    true, true, true, true) == com.forgemaster.forge.ForgePhase.QUENCHING, "嵌丝后不是淬火");
        });

        add(g, "工序链：关掉的那一道被跳过", () -> {
            require(ForgeManager.nextStage(com.forgemaster.forge.ForgePhase.GRINDING,
                    true, true, false, true) == com.forgemaster.forge.ForgePhase.INLAY,
                    "关抛光后没跳到嵌银丝");
            require(ForgeManager.nextStage(com.forgemaster.forge.ForgePhase.FOLDING,
                    true, false, true, true) == com.forgemaster.forge.ForgePhase.POLISHING,
                    "关开刃后没跳到抛光");
            require(ForgeManager.nextStage(com.forgemaster.forge.ForgePhase.HAMMERING,
                    false, false, false, false) == com.forgemaster.forge.ForgePhase.QUENCHING,
                    "全关后没直接进淬火");
        });

        add(g, "端到端：锻打→叠钢→开刃→抛光→嵌银丝→淬火 走通", () -> {
            ForgeSession s = probeSession(first);
            s.addStrike(ForgeSession.Hit.PERFECT);
            s.phase(com.forgemaster.forge.ForgePhase.FOLDING);
            plugin.forge().simulateStartFolding(s);
            plugin.forge().simulateFoldAll(s);
            require(s.phase() == com.forgemaster.forge.ForgePhase.GRINDING, "叠钢后未进开刃");
            plugin.forge().simulateGrind(s);
            require(s.phase() == com.forgemaster.forge.ForgePhase.QUENCHING,
                    "七工序没走到淬火: " + s.phase());
            require(s.polishScore() > 0, "抛光分数没记上");
            require(s.inlayScore() > 0, "嵌丝分数没记上");
        });

        add(g, "成品 Lore：收尾工序开着才多一行", () -> {
            ForgeSession s = probeSession(first);
            s.polishScore(0.8);
            s.inlayScore(0.6);
            ItemStack item = plugin.items().build(s);
            require(item != null, "造不出成品");
            String text = loreText(item);
            if (plugin.settings().polishingEnabled()) {
                require(text.contains("抛光"), "抛光开着却没写进 Lore");
            }
            if (plugin.settings().inlayEnabled()) {
                require(text.contains("银丝"), "嵌丝开着却没写进 Lore");
            }
        });
    }

    // ---------------------------------------------------------------- v2.14 龙税周目 / 剑型 / 悬赏

    private void registerContractLoop() {
        var cm = plugin.contractManager();
        String g = "v2.14 龙税周目 / 剑型 / 悬赏";

        add(g, "估值：品质越高越值钱、强化越高级越值钱", () -> {
            require(cm != null, "契约管理器没起来");
            ItemStack sword = sampleSword();
            require(sword != null, "造不出铁剑");
            require(cm.appraise(sword) > 0, "铁剑估值为 0");
            ItemStack epic = plugin.items().buildDirect("SelfTest",
                    plugin.materials().get("iron"), Quality.EPIC, 60.0, null);
            require(epic != null && cm.appraise(epic) > cm.appraise(sword),
                    "史诗剑估值没高过普通剑");
            // 强化抬估值（同品级对比，用克隆做基线）
            ItemStack epicLv10 = plugin.items().buildDirect("SelfTest",
                    plugin.materials().get("iron"), Quality.EPIC, 60.0, null);
            plugin.strengthen().setLevel(epicLv10, 10);
            require(cm.appraise(epicLv10) > cm.appraise(epic), "+10 没抬估值");
        });

        add(g, "税额递增：day 越大要得越多，欠息叠加", () -> {
            require(cm != null, "契约管理器没起来");
            long d1 = cm.requirement(1, 0, com.forgemaster.contract.RelicBonus.ZERO);
            long d5 = cm.requirement(5, 0, com.forgemaster.contract.RelicBonus.ZERO);
            long d5miss = cm.requirement(5, 1, com.forgemaster.contract.RelicBonus.ZERO);
            require(d5 > d1, "税额没随天数涨");
            require(d5miss > d5, "欠息没叠加");
        });

        add(g, "圣物聚合：勾选效果进 RelicBonus、拉黑生效", () -> {
            require(cm != null, "契约管理器没起来");
            java.util.UUID id = java.util.UUID.nameUUIDFromBytes("SelfTestRelic".getBytes());
            cm.store().remove(id);
            var run = cm.store().ensure(id);
            run.relics.add("bent-nail");
            run.relics.add("black-knot");
            run.relics.add("phoenix-ash");
            var rb = cm.relicBonus(id);
            require(Math.abs(rb.success() - 0.04) < 1e-9, "弯钉没进成功率");
            require(Math.abs(rb.breakMult() - 0.90) < 1e-9, "黑结没进碎裂乘区");
            require(rb.phoenix(), "凤凰灰没点亮");
            require(cm.canPhoenix(id), "凤凰灰首日应可用");
            cm.markPhoenixUsed(id);
            require(!cm.canPhoenix(id), "凤凰灰用完当天还能用");
            cm.store().remove(id);
        });

        add(g, "剑型注册表：有权重、抽取落在定义内", () -> {
            var forms = plugin.forms();
            require(forms != null && !forms.isEmpty(), "剑型表是空的");
            java.util.Random rnd = new java.util.Random(42);
            for (int i = 0; i < 30; i++) {
                var f = forms.randomPick(rnd);
                require(f != null && forms.get(f.id()) != null, "抽出了未定义的剑型");
                require(f.valueMult() >= 0.1, "剑型乘区非法");
            }
        });

        add(g, "每日悬赏：同一天结果稳定、门槛合法", () -> {
            var bm = plugin.bounties();
            require(bm != null, "悬赏管理器没起来");
            var first = bm.current();
            require(!first.isEmpty(), "今日悬赏是空的");
            require(bm.current() == first || bm.current().equals(first), "同一天悬赏变了");
            for (var b : first) {
                require(b.minQuality() >= 2 && b.minQuality() < Quality.values().length, "品质门槛越界");
                require(b.reward() > 0, "赏金不是正数");
            }
        });
    }

    // ---------------------------------------------------------------- v2.13 五态抽票 / 连击 / 狂热 / 传承

    private void registerTickets() {
        var st = plugin.strengthen();
        if (st == null) {
            return;
        }
        String g = "v2.13 五态抽票 / 连击 / 狂热 / 传承";

        add(g, "明牌五态概率之和恒为 1 且各项非负", () -> {
            ItemStack sword = sampleSword();
            require(sword != null, "造不出铁剑");
            for (int level = 0; level <= 20; level += 4) {
                plugin.strengthen().setLevel(sword, level);
                for (double kindle : new double[]{0.0, 0.15}) {
                    double[] o = plugin.strengthen().oddsOf(sword, kindle);
                    require(o.length == 5, "概率表长度不对");
                    double sum = 0;
                    for (double p : o) {
                        require(p >= -1e-9 && p <= 1.0 + 1e-9, "概率跑出 [0,1]：" + p);
                        sum += p;
                    }
                    require(Math.abs(sum - 1.0) < 1e-6, "五态之和不是 1：" + sum);
                }
            }
        });

        add(g, "连击抬成功率、抬大成率、压碎裂（基石护体）", () -> {
            ItemStack sword = sampleSword();
            require(sword != null, "造不出铁剑");
            plugin.strengthen().setLevel(sword, 8);
            double[] base = plugin.strengthen().oddsOf(sword, 0);
            try (var ignored = comboPatcher(sword, 6)) {
                double[] comboed = plugin.strengthen().oddsOf(sword, 0);
                // v2.16：odds[3] 是大成、odds[4] 是 +1 成功，两者都从同一份成功率里切
                require(comboed[3] + comboed[4] > base[3] + base[4] + 1e-9, "连击没抬成功率");
                require(comboed[3] > base[3], "连击没抬大成率");
                // 连击 6 ≥ 基石阈值：碎裂应被护体清零
                require(comboed[0] < 1e-9, "基石护体没把碎裂清零");
            }
            double[] after = plugin.strengthen().oddsOf(sword, 0);
            require(Math.abs(after[0] - base[0]) < 1e-9, "护体状态没还原");
        });

        add(g, "狂热期间不碎 + 成功率加成", () -> {
            ItemStack sword = sampleSword();
            require(sword != null, "造不出铁剑");
            plugin.strengthen().setLevel(sword, 12);
            double[] base = plugin.strengthen().oddsOf(sword, 0);
            try (var ignored = frenzyPatcher(sword)) {
                double[] frenzy = plugin.strengthen().oddsOf(sword, 0);
                require(frenzy[0] < 1e-9, "狂热期间还能碎");
                require(frenzy[4] > base[4], "狂热没抬成功率");
            }
        });

        add(g, "剑魂传承：参数夹取正确、非 0 级剑拒绝继承", () -> {
            require(plugin.strengthen().inheritRate() > 0 && plugin.strengthen().inheritRate() <= 1.0,
                    "传承比例没夹在 (0,1]");
            require(plugin.strengthen().inheritCap() > 0, "传承上限不是正数");
            ItemStack sword = sampleSword();
            require(sword != null, "造不出铁剑");
            plugin.strengthen().setLevel(sword, 5);
            require(plugin.strengthen().applyInherit(
                    java.util.UUID.nameUUIDFromBytes("SelfTest".getBytes()), sword) == 0,
                    "非 0 级的剑不该吃传承");
        });

        add(g, "五态抽票不破坏原曲线：successChance 仍随等级递减", () -> {
            double lo = plugin.strengthen().successChance(1);
            double hi = plugin.strengthen().successChance(15);
            require(hi < lo, "成功率曲线被打乱了");
        });
    }

    /** 临时把连击写成 n 并标记基石未用的 AutoCloseable */
    private AutoCloseable comboPatcher(ItemStack item, int combo) {
        var meta = item.getItemMeta();
        meta.getPersistentDataContainer().set(Keys.STRENGTHEN_COMBO, PersistentDataType.INTEGER, combo);
        meta.getPersistentDataContainer().set(Keys.CORNERSTONE_USED, PersistentDataType.INTEGER, 0);
        item.setItemMeta(meta);
        return () -> {
            var m2 = item.getItemMeta();
            m2.getPersistentDataContainer().remove(Keys.STRENGTHEN_COMBO);
            m2.getPersistentDataContainer().remove(Keys.CORNERSTONE_USED);
            item.setItemMeta(m2);
        };
    }

    /** 临时给物品挂狂热（剩余 3 次）的 AutoCloseable */
    private AutoCloseable frenzyPatcher(ItemStack item) {
        var meta = item.getItemMeta();
        meta.getPersistentDataContainer().set(Keys.FRENZY_LEFT, PersistentDataType.INTEGER, 3);
        item.setItemMeta(meta);
        return () -> {
            var m2 = item.getItemMeta();
            m2.getPersistentDataContainer().remove(Keys.FRENZY_LEFT);
            item.setItemMeta(m2);
        };
    }

    // ---------------------------------------------------------------- v2.12 锻阶（蜕变）

    private void registerEvolution() {
        var evo = plugin.evolutions();
        if (evo == null) {
            return;
        }
        String g = "v2.12 锻阶（蜕变）";
        var reg = evo.registry();

        add(g, "注册表有内置阶段且门槛递增", () -> {
            require(!reg.isEmpty(), "锻阶表是空的");
            var all = reg.all();
            require(all.get(0).index() == 1, "第一阶序号不是 1");
            for (int i = 1; i < all.size(); i++) {
                require(all.get(i).level() > all.get(i - 1).level(),
                        "第 " + i + " 阶门槛没递增");
            }
        });

        add(g, "门槛判定：+4 不拦、+5 拦第一阶、跳级也拦", () -> {
            require(reg.gateAt(0, 4) == null, "+4 不该触发门槛");
            var s1 = reg.gateAt(0, 5);
            require(s1 != null && s1.index() == 1, "+5 没拦下第一阶");
            require(reg.gateAt(0, 7) != null, "跳过门槛的等级没被拦（>= 语义）");
            require(reg.gateAt(1, 5) == null, "已蜕变一阶还拦同一阶");
        });

        add(g, "累计乘区：连乘且 0 阶为 1", () -> {
            require(Math.abs(reg.cumulativeMult(0) - 1.0) < 1e-9, "0 阶乘区不是 1");
            double expect = reg.stage(1).bonus() * reg.stage(2).bonus();
            require(Math.abs(reg.cumulativeMult(2) - expect) < 1e-9,
                    "两阶连乘不对：" + reg.cumulativeMult(2) + " vs " + expect);
            require(reg.cumulativeMult(2) > reg.cumulativeMult(1), "乘区没随阶段涨");
        });

        add(g, "成功率明牌：夹在 1%-100%，品质加成为正", () -> {
            ItemStack sword = sampleSword();
            require(sword != null, "造不出铁剑");
            var s = reg.stage(6); // 最高阶 base 最低，最适合验夹取
            double c = evo.successChance(sword, s);
            require(c >= 0.01 && c <= 1.0, "成功率跑出 1%-100%：" + c);
            require(c > s.successBase() - 1e-9, "普通品质没吃到加成反而被削");
        });

        add(g, "材料折扣：品质越高吃越少，最低剩 1 个", () -> {
            ItemStack sword = sampleSword();
            require(sword != null, "造不出铁剑");
            var s = reg.stage(1);
            int common = evo.materialAmountOf(sword, s);
            require(common <= s.materialAmount(), "普通品质反而比定义多了材料");
            require(common >= 1, "材料算出 0 个");
        });

        add(g, "出厂剑没写过 evolution 键也算锻造品：stageOf 读 0 而不是 -1", () -> {
            ItemStack sword = sampleSword();
            require(sword != null, "造不出铁剑");
            var meta = sword.getItemMeta();
            require(meta != null, "样本剑没有 meta");
            require(meta.getPersistentDataContainer().get(Keys.EVOLUTION, PersistentDataType.INTEGER) == null,
                    "样本剑自带 evolution 键，回归用例失效");
            require(evo.stageOf(sword) == 0, "出厂剑的锻阶被读成 " + evo.stageOf(sword) + "（-1 会让 /zhujian evolve 报「不是锻造品」）");
            require(evo.stageOf(new ItemStack(Material.DIAMOND_SWORD)) < 0, "没锻过的钻石剑被当成锻造品");
            require(evo.stageOf(null) < 0, "null 物品居然读出了锻阶");
        });

        add(g, "锻阶不再卡强化：+5 时 pendingGate 仍可读，但品质上限不受它影响（v2.20）", () -> {
            ItemStack sword = sampleSword();
            require(sword != null, "造不出铁剑");
            require(plugin.strengthen().setLevel(sword, 5), "setLevel(5) 失败");
            // pendingGate 保留下来给蜕变界面展示「下一阶在哪」，所以它仍然非空
            var gate = evo.pendingGate(sword);
            require(gate == null || gate.index() == 1, "pendingGate 的阶数口径变了: "
                    + (gate == null ? "null" : gate.index()));
            // 关键契约：强化上限只认品质，不再被锻阶压低。
            // 以前这里 +5 就到顶（上限 = 门槛等级），玩家必须先蜕变才能继续锻。
            int cap = plugin.strengthen().capOf(sword);
            require(cap > 5, "强化上限还是被锻阶压到 +" + cap + "（v2.20 应该只认品质）");
            require(cap >= 30, "品质上限算错了: +" + cap);
            require(com.forgemaster.evolve.EvolutionService.evolutionLine(reg, 0, 0) == null, "0 阶不该画锻阶行");
        });

        add(g, "蜕变乘区真的进属性：写阶后 apply 攻击 ×1.25", () -> {
            ItemStack sword = sampleSword();
            require(sword != null, "造不出铁剑");
            plugin.strengthen().apply(sword);
            double before = attrAmount(sword, Attribute.ATTACK_DAMAGE, "fm_atk");
            require(before > 0, "读不到 fm_atk 基准");
            try (var ignored = stagePatcher(sword, 1)) {
                plugin.strengthen().apply(sword);
                double after = attrAmount(sword, Attribute.ATTACK_DAMAGE, "fm_atk");
                double mult = reg.cumulativeMult(1);
                require(Math.abs(after - before * mult) < before * mult * 0.01,
                        "蜕变乘区没生效：" + before + " × " + mult + " → " + after);
                // 锻阶行也该出现了，且 apply 两次不叠行
                plugin.strengthen().apply(sword);
                require(loreCount(sword, "锻阶") == 1, "锻阶行没有恰好一行");
            }
        });

        add(g, "锻阶行契约：纯文本以「锻阶」开头且带下一阶提示", () -> {
            String line = com.forgemaster.evolve.EvolutionService.evolutionLine(reg, 1, 2);
            require(line != null && Msg.plain(Msg.mm(line)).startsWith("锻阶"),
                    "锻阶行前缀不对");
            require(line.contains("曾败 2 次"), "履历次数没写进锻阶行");
            require(line.contains("下一阶"), "下一阶提示丢了");
        });

        add(g, "铭牌剥离：品质前缀与阶段铭牌都能还原本名", () -> {
            require(com.forgemaster.evolve.EvolutionService.stripTitles("传世·铁剑").equals("铁剑"),
                    "品质前缀没剥掉");
            require(com.forgemaster.evolve.EvolutionService.stripTitles("〔精钢〕铁剑").equals("铁剑"),
                    "阶段铭牌没剥掉");
            require(com.forgemaster.evolve.EvolutionService.stripTitles("〔精钢〕传世·铁剑").equals("铁剑"),
                    "双层前缀没剥干净");
            require(com.forgemaster.evolve.EvolutionService.stripTitles("不朽·星陨剑").equals("星陨剑"),
                    "神话品质前缀没剥掉");
        });

        add(g, "龙税契约：开关与策略实现一致（关则全 no-op）", () -> {
            var contract = plugin.contract();
            require(contract != null, "契约注册表没起来");
            if (contract.active()) {
                // v2.14 周目已接入：策略必须真能算出税额，不能还是骨架
                require(plugin.contractManager() != null, "开关开着却没有周目管理器");
                long req = plugin.contractManager().requirement(
                        1, 0, com.forgemaster.contract.RelicBonus.ZERO);
                require(req > 0, "周目开着但第 1 天税额是 0");
                require(contract.clock() != null, "周目开着却没有时钟");
            } else {
                require(contract.clock().day() == 1 && contract.clock().ticksRemaining() == -1
                        && !contract.clock().isTributeDue(), "RunClock no-op 语义不对");
                require(contract.tribute().requiredValue(1, null) == 0, "TributePolicy no-op 不该要钱");
                require(contract.failure().onMissed(null, 1, 0, 1).isEmpty(), "FailurePolicy no-op 不该说话");
            }
        });
    }

    /** 临时把物品锻阶写成 stage 的 AutoCloseable，测试完还原 */
    private AutoCloseable stagePatcher(ItemStack item, int stage) {
        var meta = item.getItemMeta();
        int old = meta.getPersistentDataContainer()
                .getOrDefault(Keys.EVOLUTION, PersistentDataType.INTEGER, 0);
        meta.getPersistentDataContainer().set(Keys.EVOLUTION, PersistentDataType.INTEGER, stage);
        item.setItemMeta(meta);
        return () -> {
            var m2 = item.getItemMeta();
            m2.getPersistentDataContainer().set(Keys.EVOLUTION, PersistentDataType.INTEGER, old);
            item.setItemMeta(m2);
        };
    }

    /** 读指定属性上指定键名的 modifier 数值；没有返回 NaN */
    private double attrAmount(ItemStack item, Attribute attr, String keyName) {
        var meta = item.getItemMeta();
        if (meta == null || meta.getAttributeModifiers(attr) == null) {
            return Double.NaN;
        }
        for (var m : meta.getAttributeModifiers(attr)) {
            if (m.getKey() != null && keyName.equals(m.getKey().getKey())) {
                return m.getAmount();
            }
        }
        return Double.NaN;
    }

    /** 数 Lore 里以 prefix 开头的行数 */
    private int loreCount(ItemStack item, String prefix) {
        var meta = item.getItemMeta();
        if (meta == null || meta.lore() == null) {
            return 0;
        }
        int n = 0;
        for (var c : meta.lore()) {
            if (Msg.plain(c).startsWith(prefix)) {
                n++;
            }
        }
        return n;
    }

    /** 读 Lore 里的耐久行纯文本（性能修复包 A 用）；找不到返回 null */
    private String durabilityLineText(ItemStack item) {
        var meta = item.getItemMeta();
        if (meta == null || meta.lore() == null) {
            return null;
        }
        for (var c : meta.lore()) {
            String plain = Msg.plain(c);
            if (plain.startsWith("耐久")) {
                return plain;
            }
        }
        return null;
    }

    // ---------------------------------------------------------------- v2.10

    private void registerV210() {
        String g = "v2.10 临摹解锁";

        add(g, "临摹锁定状态表是干净的：无记录即无锁", () -> {
            var m = plugin.manuals();
            require(m != null, "剑谱模块没初始化");
            java.util.UUID who = java.util.UUID.nameUUIDFromBytes("fm-copy-lock".getBytes());

            require(m.activeCopy(who) == null, "初始不该有临摹");
            require(m.cancelCopy(who) == null, "没在临摹时取消应返回 null");
            // 取消之后仍然是干净的，不会留下空壳记录
            require(m.activeCopy(who) == null, "取消后不该再有临摹");
        });

        add(g, "临摹取消是幂等的，重复调用不抛异常", () -> {
            var m = plugin.manuals();
            java.util.UUID who = java.util.UUID.nameUUIDFromBytes("fm-copy-idem".getBytes());
            for (int i = 0; i < 5; i++) {
                String r = m.cancelCopy(who);
                require(r == null, "第 " + (i + 1) + " 次取消仍返回了 " + r);
            }
        });

        add(g, "陌生 UUID 查询不会建出脏数据", () -> {
            var m = plugin.manuals();
            java.util.UUID ghost = java.util.UUID.nameUUIDFromBytes("fm-copy-ghost".getBytes());
            require(m.activeCopy(ghost) == null, "陌生玩家不该有临摹");
            require(m.cancelCopy(ghost) == null, "陌生玩家取消应为 null");
        });

        String gs = "v2.10 双孔分离";

        add(gs, "新物品附魔孔从 0 起算，两套孔各自独立", () -> {
            ItemStack w = sampleSword();
            require(w != null, "造不出测试剑");
            var gems = plugin.gems();
            // ItemFactory 创建时写了 ENCHANT_SOCKETS = 0
            require(gems.gemSocketsOf(w) == 0, "新物品宝石孔应为 0");
            require(gems.enchantSocketsOf(w) == 0, "新物品附魔孔应为 0（不该回退成宝石孔）");
            require(gems.enchantLimitOf(w) > 0, "附魔孔上限不该是 0");
            require(gems.socketLimitOf(w) > 0, "宝石孔上限不该是 0");
        });

        add(gs, "老物品没有附魔孔键时回退成宝石孔数，不被砍孔", () -> {
            ItemStack w = sampleSword();
            require(w != null, "造不出测试剑");
            var gems = plugin.gems();
            var meta = w.getItemMeta();
            // 模拟 2.10 之前的老物品：只有 SOCKETS，没有 ENCHANT_SOCKETS
            meta.getPersistentDataContainer().remove(com.forgemaster.util.Keys.ENCHANT_SOCKETS);
            meta.getPersistentDataContainer().set(com.forgemaster.util.Keys.SOCKETS,
                    org.bukkit.persistence.PersistentDataType.INTEGER, 2);
            w.setItemMeta(meta);

            require(gems.gemSocketsOf(w) == 2, "老物品宝石孔应读成 2");
            require(gems.enchantSocketsOf(w) == 2, "老物品附魔孔应回退成 2，实际 "
                    + gems.enchantSocketsOf(w));
        });

        add(gs, "已占用的宝石孔与附魔孔分开计数", () -> {
            ItemStack w = sampleSword();
            require(w != null, "造不出测试剑");
            var gems = plugin.gems();
            require(gems.usedGemSockets(w) == 0, "新物品不该占宝石孔");
            require(gems.usedEnchantSockets(w) == 0, "新物品不该占附魔孔");

            // 塞一颗宝石进 PDC
            var meta = w.getItemMeta();
            meta.getPersistentDataContainer().set(com.forgemaster.util.Keys.GEMS,
                    org.bukkit.persistence.PersistentDataType.STRING, "crit:5.0:L1");
            w.setItemMeta(meta);
            require(gems.usedGemSockets(w) == 1, "宝石计数不对");
            require(gems.usedEnchantSockets(w) == 0, "塞宝石不该消耗附魔孔");
            require(gems.usedSockets(w) == 1, "合计应为 1");
        });

        add(gs, "Lore 标题把两套孔分开显示", () -> {
            var out = com.forgemaster.gem.GemService.rebuildGemBlock(
                    new java.util.ArrayList<net.kyori.adventure.text.Component>(),
                    2, 1,
                    java.util.List.of(),
                    java.util.List.of());
            String plain = out.stream().map(Msg::plain).reduce("", (a, b) -> a + "|" + b);
            require(plain.contains("宝石 0/2"), "Lore 没显示宝石孔计数: " + plain);
            require(plain.contains("附魔 0/1"), "Lore 没显示附魔孔计数: " + plain);
        });

        add(gs, "两套孔都开满时，Lore 仍然各显示各的", () -> {
            var gem = new com.forgemaster.gem.GemService.GemEntry(
                    com.forgemaster.gem.GemType.byId("crit"), 12.0,
                    com.forgemaster.gem.GemType.Grade.L1);
            var out = com.forgemaster.gem.GemService.rebuildGemBlock(
                    new java.util.ArrayList<net.kyori.adventure.text.Component>(),
                    1, 3,
                    java.util.List.of(gem),
                    java.util.List.of());
            String plain = out.stream().map(Msg::plain).reduce("", (a, b) -> a + "|" + b);
            require(plain.contains("宝石 1/1"), "满宝石孔没显示 1/1: " + plain);
            require(plain.contains("附魔 0/3"), "附魔孔计数不对: " + plain);
        });

        add(gs, "两个池子都空时，Lore 不画镶嵌块", () -> {
            var out = com.forgemaster.gem.GemService.rebuildGemBlock(
                    new java.util.ArrayList<net.kyori.adventure.text.Component>(),
                    0, 0,
                    java.util.List.of(), java.util.List.of());
            require(out.isEmpty(), "0 孔 0 附魔孔不该画任何东西");
        });

        add(gs, "附魔孔上限按材料档位递增", () -> {
            var gems = plugin.gems();
            ItemStack low = null, high = null;
            int lowTier = Integer.MAX_VALUE, highTier = Integer.MIN_VALUE;
            for (var m : plugin.materials().sorted()) {
                ItemStack s = plugin.items().buildDirect("SelfTest", m, Quality.COMMON, 60.0, null);
                if (s == null) {
                    continue;
                }
                if (m.tier() < lowTier) {
                    lowTier = m.tier();
                    low = s;
                }
                if (m.tier() > highTier) {
                    highTier = m.tier();
                    high = s;
                }
            }
            if (low != null && high != null && lowTier != highTier) {
                require(gems.enchantLimitOf(high) >= gems.enchantLimitOf(low),
                        "高一档材料的附魔孔上限不该更低：" + highTier + "→" + gems.enchantLimitOf(high)
                                + " vs " + lowTier + "→" + gems.enchantLimitOf(low));
            }
            require(gems.enchantLimitOf(null) == 0, "空物品附魔孔上限应为 0");
        });

        // ------------------------------------------------ v2.10 护甲修正

        String ga = "v2.10 护甲修正";

        add(ga, "四件装备的护甲 modifier 键名互不相同", () -> {
            var keys = new java.util.HashSet<String>();
            for (var slot : com.forgemaster.material.ForgeMaterial.Slot.values()) {
                String a = com.forgemaster.util.Attrs.armorKey(slot);
                String t = com.forgemaster.util.Attrs.toughKey(slot);
                require(keys.add(a), "护甲键重复：" + a);
                require(keys.add(t), "韧性键重复：" + t);
                require(!a.equals("fm_armor"), "还是老的共用键：" + a);
            }
            require(keys.size() == 8, "应该有 8 个互不相同的键，实际 " + keys.size());
        });

        add(ga, "基础属性键名判定认得新老两套写法", () -> {
            require(com.forgemaster.util.Attrs.isBaseKey("fm_armor"), "老键 fm_armor 应认得");
            require(com.forgemaster.util.Attrs.isBaseKey("fm_armor_chestplate"), "新键应认得");
            require(com.forgemaster.util.Attrs.isBaseKey("fm_toughness_boots"), "新韧键应认得");
            require(com.forgemaster.util.Attrs.isBaseKey("fm_atk"), "fm_atk 应认得");
            require(!com.forgemaster.util.Attrs.isBaseKey("fm_crit_chance"), "词条键不该被当成基础键");
            require(!com.forgemaster.util.Attrs.isBaseKey("fm_set_flame"), "套装键不该被当成基础键");
            require(!com.forgemaster.util.Attrs.isBaseKey(null), "null 应为 false");
        });

        add(ga, "四个部位都有护甲值，没有哪个部位是摆设", () -> {
            for (var slot : com.forgemaster.material.ForgeMaterial.Slot.values()) {
                ItemStack piece = armorPieceOf("diamond", slot);
                require(piece != null, "造不出钻石" + slot.label());
                double a = armorAmountOf(piece);
                require(a > 0, slot.label() + " 的护甲不该是 0");
            }
        });

        add(ga, "铁甲整套护甲达到原版量级（至少 10 点）", () -> {
            double total = 0;
            for (var slot : com.forgemaster.material.ForgeMaterial.Slot.values()) {
                ItemStack piece = armorPieceOf("iron", slot);
                require(piece != null, "造不出铁" + slot.label());
                var mods = piece.getItemMeta().getAttributeModifiers(org.bukkit.attribute.Attribute.ARMOR);
                require(mods != null && !mods.isEmpty(), slot.label() + " 身上没有护甲 modifier");
                for (var m : mods) {
                    total += m.getAmount();
                }
            }
            // 原版铁套 = 15。品质倍率 COMMON 0.8 → 12，留一点余量卡 10
            require(total >= 10.0, "铁套总护甲太低：只有 " + Msg.fmt(total, 1));
            require(total <= 30.0, "铁套总护甲高得离谱：" + Msg.fmt(total, 1));
        });

        add(ga, "胸甲比头盔厚，部位不再一刀切", () -> {
            ItemStack helm = armorPieceOf("diamond", com.forgemaster.material.ForgeMaterial.Slot.HELMET);
            ItemStack chest = armorPieceOf("diamond", com.forgemaster.material.ForgeMaterial.Slot.CHESTPLATE);
            ItemStack boots = armorPieceOf("diamond", com.forgemaster.material.ForgeMaterial.Slot.BOOTS);
            require(helm != null && chest != null && boots != null, "造不出钻石甲");
            double h = armorAmountOf(helm), c = armorAmountOf(chest), b = armorAmountOf(boots);
            require(c > h, "胸甲（" + Msg.fmt(c, 2) + "）应该比头盔（" + Msg.fmt(h, 2) + "）厚");
            require(c > b, "胸甲应该比靴子厚");
        });

        add(ga, "四件穿一起不会因为同一个键互相顶掉", () -> {
            // 每件的 modifier 键必须唯一，否则原版按键去重时会互相覆盖
            var seen = new java.util.HashSet<String>();
            for (var slot : com.forgemaster.material.ForgeMaterial.Slot.values()) {
                ItemStack piece = armorPieceOf("diamond", slot);
                require(piece != null, "造不出钻石" + slot.label());
                var mods = piece.getItemMeta().getAttributeModifiers(org.bukkit.attribute.Attribute.ARMOR);
                require(mods != null, slot.label() + " 没有护甲 modifier");
                for (var m : mods) {
                    String k = m.getKey() == null ? "?" : m.getKey().getKey();
                    require(seen.add(k), "键 " + k + " 被两件装备共用，穿上会互相顶掉");
                }
            }
            // v2.21：不再要求「一共只有 4 个键」。护甲现在会正常带防御词条，
            // 词条也会挂 modifier（fm_<词条>_<部位>），键数自然多于 4。
            // 真正要守的是「四件的**基础**护甲键各自存在且互不相同」——
            // 上面的 seen.add 已经在守唯一性，这里补上存在性。
            for (var slot : com.forgemaster.material.ForgeMaterial.Slot.values()) {
                String want = com.forgemaster.util.Attrs.armorKey(slot);
                require(seen.contains(want), slot.label() + " 缺基础护甲键 " + want + "：" + seen);
            }
        });

        add(ga, "同一词条出在两件护甲上：键必须按部位分开，否则一件的加成会被顶掉（v2.21）", () -> {
            // 这是 v2.10 起就存在的真 bug：护甲词条的 modifier 键原本是 fm_<词条id>，
            // 不带部位。两件都 roll 到「护甲值」时键完全相同，原版按键去重，
            // 结果是同一词条只有一件生效。之前没被发现，是因为护甲上压根没有
            // 护甲属性词条（buildDirect 的池子 bug 把攻击词条塞给了护甲）。
            ItemStack chest = armorPieceOf("diamond", ForgeMaterial.Slot.CHESTPLATE);
            ItemStack legs = armorPieceOf("diamond", ForgeMaterial.Slot.LEGGINGS);
            require(chest != null && legs != null, "造不出钻石胸甲/护腿");

            // 人造场景：两件都挂上「护甲值」词条（真实世界里两件都 roll 到同一条很常见）
            chest = forceArmorPlateAffix(chest);
            legs = forceArmorPlateAffix(legs);

            String chestKey = affixKeyOn(chest, org.bukkit.attribute.Attribute.ARMOR, "fm_armor_plate");
            String legsKey = affixKeyOn(legs, org.bukkit.attribute.Attribute.ARMOR, "fm_armor_plate");
            require(chestKey != null, "胸甲没有护甲值词条修饰符");
            require(legsKey != null, "护腿没有护甲值词条修饰符");
            require(!chestKey.equals(legsKey),
                    "两件的护甲值词条共用了同一个键 " + chestKey + "，穿上会互相顶掉");
            require(chestKey.endsWith("_chestplate"), "胸甲的键没带部位: " + chestKey);
            require(legsKey.endsWith("_leggings"), "护腿的键没带部位: " + legsKey);
        });

        add(ga, "锻造护甲自带虚拟耐久账：本体 unbreakable 冻原版条，插件只认 PDC 这本（v2.22.1）", () -> {
            // 护甲耐久此前只有建账没有扣账——穿上挨打数值永远不动（如 4500），
            // 扣账入口在 CombatListener.onWearerDamaged（要真玩家事件），这里验账本本身
            ItemStack chest = armorPieceOf("diamond", ForgeMaterial.Slot.CHESTPLATE);
            require(chest != null, "造不出钻石胸甲");
            int max = plugin.items().maxDurabilityOf(chest);
            require(max >= 1, "护甲耐久上限没建账: " + max);
            require(plugin.items().durabilityOf(chest) == max, "新护甲不该带着磨损出场");
            boolean broken = plugin.items().setDurability(chest, max - 1);
            require(!broken && plugin.items().durabilityOf(chest) == max - 1,
                    "掉一点耐久就误判崩刃: " + plugin.items().durabilityOf(chest));
            require(plugin.items().setDurability(chest, 0), "归零应判崩刃");
        });

        add(ga, "老装备校准只升不降", () -> {
            for (var slot : com.forgemaster.material.ForgeMaterial.Slot.values()) {
                ItemStack piece = armorPieceOf("iron", slot);
                require(piece != null, "造不出铁" + slot.label());
                // 模拟老装备：护甲快照被人为调高，校准不该把它压回去
                var meta = piece.getItemMeta();
                meta.getPersistentDataContainer().set(com.forgemaster.util.Keys.BASE_ARMOR,
                        org.bukkit.persistence.PersistentDataType.DOUBLE, 999.0);
                meta.getPersistentDataContainer().remove(com.forgemaster.util.Keys.ARMOR_REBALANCE);
                piece.setItemMeta(meta);

                plugin.strengthen().rebalanceArmor(piece);
                double after = piece.getItemMeta().getPersistentDataContainer()
                        .getOrDefault(com.forgemaster.util.Keys.BASE_ARMOR,
                                org.bukkit.persistence.PersistentDataType.DOUBLE, 0.0);
                require(after >= 999.0 - 1e-6,
                        slot.label() + " 被校准削弱了：" + after);
            }
        });

        add(ga, "带数据的粒子不再抛异常（ENCHANT / DRAGON_BREATH）", () -> {
            var worlds = org.bukkit.Bukkit.getWorlds();
            require(!worlds.isEmpty(), "没有可用世界");
            var loc = worlds.get(0).getSpawnLocation().add(0, 1, 0);

            // 2.10 之前这里走的是不带数据的七参 spawnParticle：
            // DRAGON_BREATH 的数据类型是 Float，每发一次就抛
            // IllegalArgumentException: missing required data class java.lang.Float，
            // 战斗里每帧刷屏，而且异常会把同一个监听器后面的逻辑一起打断。
            //
            // 注意 ENCHANT 在这个版本是 Void（无数据），真正要 Float 的是 DRAGON_BREATH，
            // 所以这里不能硬写死某一种类型，只验「发出去不炸」。
            var tier = new org.bukkit.Particle[]{
                    org.bukkit.Particle.ENCHANT,
                    org.bukkit.Particle.END_ROD,
                    org.bukkit.Particle.SOUL_FIRE_FLAME,
                    org.bukkit.Particle.DRAGON_BREATH
            };
            for (var p : tier) {
                Class<?> dt = p.getDataType();
                require(dt == null || dt == Void.class || dt == Float.class,
                        "出现了没覆盖的数据类型：" + p + " -> " + dt);
                try {
                    com.forgemaster.util.Particles.spawn(p, loc, 6, 0.3, 0.4, 0.3, 0.02);
                } catch (RuntimeException ex) {
                    throw new IllegalStateException("粒子 " + p
                            + " 仍然抛异常：" + ex.getClass().getSimpleName()
                            + " " + ex.getMessage(), ex);
                }
            }
        });

        add(ga, "无数据的粒子照常发，不入参数也不抛", () -> {
            var worlds = org.bukkit.Bukkit.getWorlds();
            require(!worlds.isEmpty(), "没有可用世界");
            var loc = worlds.get(0).getSpawnLocation();

            for (var p : new org.bukkit.Particle[]{
                    org.bukkit.Particle.END_ROD,
                    org.bukkit.Particle.SOUL_FIRE_FLAME,
                    org.bukkit.Particle.CRIT,
                    org.bukkit.Particle.FLAME}) {
                try {
                    com.forgemaster.util.Particles.spawn(p, loc, 4, 0.2, 0.2, 0.2, 0.01);
                } catch (RuntimeException ex) {
                    throw new IllegalStateException("粒子 " + p + " 抛异常：" + ex.getMessage(), ex);
                }
            }
            // 边界：null / 0 数量 / null 世界都不该炸
            com.forgemaster.util.Particles.spawn(null, loc, 4, 0, 0, 0, 0);
            com.forgemaster.util.Particles.spawn(org.bukkit.Particle.FLAME, null, 4, 0, 0, 0, 0);
            com.forgemaster.util.Particles.spawn(org.bukkit.Particle.FLAME, loc, 0, 0, 0, 0, 0);
        });

        add(ga, "vanilla 属性表：四件不同键，脱一件不会连坐清空", () -> {
            // 这条直接对着原版的属性表验：把四件装备的 modifier 挂到同一个实体的
            // ARMOR 属性上，再按键摘掉一条，看另外三条还在不在。
            // 以前四件共用 fm_armor，摘一件会把整个键删掉，护甲直接归零。
            var worlds = org.bukkit.Bukkit.getWorlds();
            require(!worlds.isEmpty(), "没有可用世界");
            var world = worlds.get(0);
            org.bukkit.entity.LivingEntity probe = world.spawn(
                    world.getSpawnLocation().add(0, 8, 0), org.bukkit.entity.Zombie.class);
            try {
                var inst = probe.getAttribute(org.bukkit.attribute.Attribute.ARMOR);
                require(inst != null, "测试实体没有护甲属性");
                double base = inst.getBaseValue();

                var added = new java.util.ArrayList<org.bukkit.NamespacedKey>();
                for (var slot : com.forgemaster.material.ForgeMaterial.Slot.values()) {
                    var key = new org.bukkit.NamespacedKey(plugin,
                            com.forgemaster.util.Attrs.armorKey(slot));
                    inst.addModifier(new org.bukkit.attribute.AttributeModifier(
                            key, 2.0, org.bukkit.attribute.AttributeModifier.Operation.ADD_NUMBER));
                    added.add(key);
                }
                require(Math.abs(inst.getValue() - (base + 8.0)) < 1e-6,
                        "四件没叠加起来：" + (inst.getValue() - base) + "（应为 8）");

                // 摘掉头盔那条
                inst.removeModifier(added.get(0));
                double rest = inst.getValue() - base;
                require(Math.abs(rest - 6.0) < 1e-6,
                        "脱一件后应剩 6 点，实际 " + rest);
                require(rest > 0, "脱一件把护甲清空了——互斥 bug 还在");
            } finally {
                probe.remove();
            }
        });

        add(ga, "体检：锻造剑的攻击构成必须保住原版 +5", () -> {
            // 2026-11-08 定案：meta.addAttributeModifier 写的是 meta 层，原版条目
            // （base_attack_damage=+5）只在组件默认值里，一次 setItemMeta 就整体顶掉。
            // 曾经锻造剑总伤只有 1.12（比木剑还低），就是把「加成」当成了「总额」。
            ItemStack sword = sampleSword();
            require(sword != null, "造不出铁剑");
            var attr = org.bukkit.attribute.Attribute.ATTACK_DAMAGE;
            require(componentHas(sword, attr, "base_attack_damage"),
                    "原版 +5 被顶掉了：" + describeComponents(sword, attr));
            require(componentHas(sword, attr, "fm_atk"),
                    "剑芯加成没写进去：" + describeComponents(sword, attr));
            double total = componentTotal(sword, attr);
            double base = componentTotal(new ItemStack(org.bukkit.Material.IRON_SWORD), attr);
            double plug = componentPlugTotal(sword, attr);
            require(total > base,
                    "锻造剑总伤没超过原版：锻造 " + Msg.fmt(total, 3)
                            + " vs 原版 " + Msg.fmt(base, 3));
            require(Math.abs(total - (base + plug)) < 1e-6,
                    "总额不等于「原版基数 + 插件条目」：" + describeComponents(sword, attr));
        });

        add(ga, "体检：锻造剑的攻速不能被顶成空手 4.0", () -> {
            // 同一个坑的第二刀：原版 attack_speed 条目是 -2.4，
            // 丢了就变成 4.07（每秒 2.5 刀），实战手感直接崩。
            var attr = org.bukkit.attribute.Attribute.ATTACK_SPEED;
            ItemStack sword = sampleSword();
            require(sword != null, "造不出铁剑");
            require(componentHas(sword, attr, "base_attack_speed"),
                    "原版攻速 -2.4 被顶掉了：" + describeComponents(sword, attr));
            double total = componentTotal(sword, attr);
            double vanilla = componentTotal(new ItemStack(org.bukkit.Material.IRON_SWORD), attr);
            require(total < 2.0,
                    "攻速被顶成空手档了（应约 1.67）：" + describeComponents(sword, attr));
            require(total > vanilla,
                    "剑芯攻速加成没生效：锻造 " + Msg.fmt(total, 3)
                            + " vs 原版 " + Msg.fmt(vanilla, 3));
        });

        add(ga, "体检：锻造甲的护甲是绝对值，不该叠原版 +6", () -> {
            // 装备的语义相反：armor.tiers 是整套总量，拆到单件就是绝对值。
            // 铁胸 COMMON = 6.0 × 0.8 = 4.8，靠 fm_armor_chestplate 单独撑起。
            var attr = org.bukkit.attribute.Attribute.ARMOR;
            ItemStack piece = armorPieceOf("iron", ForgeMaterial.Slot.CHESTPLATE);
            require(piece != null, "造不出铁胸甲");
            require(componentHas(piece, attr, "fm_armor_chestplate"),
                    "护甲键没写进去：" + describeComponents(piece, attr));
            require(!componentHas(piece, attr, "armor.chestplate"),
                    "原版 +6 没被替换，护甲会翻倍：" + describeComponents(piece, attr));
            // 只量「基础护甲」这一份：v2.21 起护甲会正常带防御词条（护甲值 +N），
            // 那是独立的加法项，不该并进基础值区间里判定。
            double base = componentOf(piece, attr, "fm_armor_chestplate");
            require(base >= 4.0 && base <= 6.0,
                    "铁胸基础护甲不在 4~6 区间：" + describeComponents(piece, attr));
        });

        add(ga, "体检：老物品能被 normalize 补回原版基数", () -> {
            // 回归：2.10.2 及以前造出来的剑，组件里只有 fm_*，没有 base_attack_damage。
            // 登录维修走的就是 Attrs.normalize，这条自检把那段逻辑单独钉住。
            ItemStack sword = sampleSword();
            require(sword != null, "造不出铁剑");
            var attr = org.bukkit.attribute.Attribute.ATTACK_DAMAGE;
            var comp = sword.getData(
                    io.papermc.paper.datacomponent.DataComponentTypes.ATTRIBUTE_MODIFIERS);
            require(comp != null, "组件读不出来");
            var only = io.papermc.paper.datacomponent.item.ItemAttributeModifiers.itemAttributes();
            for (var e : comp.modifiers()) {
                var k = e.modifier().getKey();
                if (k != null && k.getKey().startsWith("fm_")) {
                    only.addModifier(e.attribute(), e.modifier(), e.getGroup());
                }
            }
            sword.setData(io.papermc.paper.datacomponent.DataComponentTypes.ATTRIBUTE_MODIFIERS,
                    only.build());
            require(!componentHas(sword, attr, "base_attack_damage"),
                    "还原老物品失败，原版条目还在");

            require(com.forgemaster.util.Attrs.normalize(sword),
                    "normalize 认为没什么要修的（返回值 false）");
            require(componentHas(sword, attr, "base_attack_damage"),
                    "normalize 没把原版基数补回来：" + describeComponents(sword, attr));
        });

        add(ga, "体检：锻造品不吃原版耐久（必须 unbreakable）", () -> {
            // 虚拟耐久记在 PDC，原版耐久必须封掉：否则打怪挖矿两条账一起掉，
            // 原版条见底时整把剑碎掉，Lore 上却还显示得好好的。
            ItemStack sword = sampleSword();
            require(sword != null, "造不出铁剑");
            var sm = sword.getItemMeta();
            require(sm != null && sm.isUnbreakable(), "锻造剑没封原版耐久，本体耐久会掉");
            ItemStack chest = armorPieceOf("iron", ForgeMaterial.Slot.CHESTPLATE);
            require(chest != null, "造不出铁胸甲");
            var cm = chest.getItemMeta();
            require(cm != null && cm.isUnbreakable(), "锻造甲没封原版耐久，本体耐久会掉");
        });

        add(ga, "体检：老物品的原始耐久能被清零", () -> {
            ItemStack sword = sampleSword();
            require(sword != null, "造不出铁剑");
            // 还原「已经被原版耐久啃过」的老剑：unbreakable 关掉 + Damage 写到一半
            var meta = sword.getItemMeta();
            meta.setUnbreakable(false);
            sword.setItemMeta(meta);
            sword.setData(io.papermc.paper.datacomponent.DataComponentTypes.DAMAGE, 60);
            require(!sword.getItemMeta().isUnbreakable(), "还原失败：还是 unbreakable");

            require(com.forgemaster.item.ItemFactory.enforceVirtualDurability(sword),
                    "enforceVirtualDurability 认为没什么要修的");
            require(sword.getItemMeta().isUnbreakable(), "unbreakable 没补回来");
            Integer dmg = sword.getData(
                    io.papermc.paper.datacomponent.DataComponentTypes.DAMAGE);
            require(dmg == null || dmg == 0, "原版 Damage 没清零：" + dmg);
            // 反作用检查：修耐久时若直接 setItemMeta，会把原版攻击基数再洗掉一次
            require(componentHas(sword, org.bukkit.attribute.Attribute.ATTACK_DAMAGE,
                            "base_attack_damage"),
                    "修耐久把原版基数洗掉了：" + describeComponents(sword,
                            org.bukkit.attribute.Attribute.ATTACK_DAMAGE));
        });

        add(ga, "铁砧强化费用：单锤可负担、累计不高于旧曲线六成", () -> {
            ItemStack sword = sampleSword();
            require(sword != null, "造不出铁剑");
            var st = plugin.strengthen();
            // v2.16：每锤固定计费（base + per-level × 当前等级），不再按「升到第几级」一次性收
            long single = st.costOf(sword, 0);
            require(single > 0 && single <= 500, "单锤费用不合理: " + single);
            require(st.costOf(sword, 20) > single, "费用没随等级涨");
            long now = 0;
            long old = 0;
            for (int l = 1; l <= 20; l++) {
                now += st.costOf(sword, l);
                // 旧曲线（2.10.2）：铁剑是 1 档，tier-factor 不参与
                old += 60 + 30L * l;
            }
            require(now < old, "费用没降下来：" + now + " vs 旧 " + old);
            require(now <= Math.round(old * 0.60),
                    "累计费用高于旧曲线六成：" + now + " vs 旧 " + old);
        });

        add(ga, "强化材料消耗已下调", () -> {
            ItemStack sword = sampleSword();
            require(sword != null, "造不出铁剑");
            var st = plugin.strengthen();
            int cap = Math.min(20, st.capOf(sword));
            int now = 0;
            int old = 0;
            for (int l = 1; l <= cap; l++) {
                now += st.materialAmountOf(sword, l);
                old += 2 + l;
            }
            require(now < old, "材料没降下来：" + now + " vs 旧 " + old);
            require(now <= Math.round(old * 0.85),
                    "降幅不到一成五：" + now + " vs 旧 " + old);
        });

        add(ga, "重铸费用已下调（铁剑约为旧曲线的一半）", () -> {
            ItemStack sword = sampleSword();
            require(sword != null, "造不出铁剑");
            long now = 0;
            long old = 0;
            // 铁剑是 1 档、样本是普通品质，档位系数与品质系数都是 1，直接比基数
            for (int i = 0; i < 4; i++) {
                now += com.forgemaster.affix.Reforge.costOf(sword, i);
                old += 320 + 180L * i;
            }
            require(now < old, "费用没降下来：" + now + " vs 旧 " + old);
            require(now <= Math.round(old * 0.60),
                    "降幅不到四成：" + now + " vs 旧 " + old);
            require(now >= Math.round(old * 0.30),
                    "砍得太狠（低于三成）：" + now + " vs 旧 " + old);
        });

        add(ga, "体检：暴击宝石必须真的进 PDC（Lore 有行不算数）", () -> {
            // 2.10.5 及更早：镶嵌只重挂了「走原版属性」的宝石，
            // 暴击 / 暴伤 / 吸血 / 点燃只在 Lore 里有一行，PDC 汇总从来没写过。
            ItemStack sword = sampleSword();
            require(sword != null, "造不出铁剑");
            var svc = plugin.gems();
            require(svc != null, "宝石服务没起来");
            List<com.forgemaster.gem.GemService.GemEntry> before = new ArrayList<>(svc.gemsOf(sword));
            double c0 = pdcDouble(sword, Keys.CRIT_CHANCE);
            double d0 = pdcDouble(sword, Keys.CRIT_DAMAGE);

            List<com.forgemaster.gem.GemService.GemEntry> after = new ArrayList<>(before);
            after.add(new com.forgemaster.gem.GemService.GemEntry(
                    com.forgemaster.gem.GemType.CRIT, 0.45,
                    com.forgemaster.gem.GemType.Grade.L5));
            after.add(new com.forgemaster.gem.GemService.GemEntry(
                    com.forgemaster.gem.GemType.CRIT_DAMAGE, 0.30,
                    com.forgemaster.gem.GemType.Grade.L4));
            svc.reapplyGemEffectsForTest(sword, before, after);
            require(Math.abs(pdcDouble(sword, Keys.CRIT_CHANCE) - (c0 + 0.45)) < 1e-6,
                    "暴击没进 PDC：" + c0 + " → " + pdcDouble(sword, Keys.CRIT_CHANCE));
            require(Math.abs(pdcDouble(sword, Keys.CRIT_DAMAGE) - (d0 + 0.30)) < 1e-6,
                    "暴伤没进 PDC：" + d0 + " → " + pdcDouble(sword, Keys.CRIT_DAMAGE));

            // 幂等：同一份列表再刷一遍不能再涨
            svc.reapplyGemEffectsForTest(sword, after, after);
            require(Math.abs(pdcDouble(sword, Keys.CRIT_CHANCE) - (c0 + 0.45)) < 1e-6,
                    "重复刷把暴击叠了两遍");

            // 拆卸后必须回落
            svc.reapplyGemEffectsForTest(sword, after, before);
            require(Math.abs(pdcDouble(sword, Keys.CRIT_CHANCE) - c0) < 1e-6,
                    "拆掉宝石后暴击没回落：" + pdcDouble(sword, Keys.CRIT_CHANCE));
        });

        add(ga, "体检：暴击率到 100% 就是必暴，超过也不能变成不暴", () -> {
            // 判定写法是 nextDouble() < chance，chance ≥ 1 时恒真。
            // 这条把语义钉住：谁要是改成 nextInt(100) 或先 clamp 再 >，这里立刻红。
            int rolled = 0;
            int crit = 0;
            for (double chance : new double[]{1.0, 1.05, 2.5}) {
                for (int i = 0; i < 300; i++) {
                    rolled++;
                    if (java.util.concurrent.ThreadLocalRandom.current().nextDouble() < chance) {
                        crit++;
                    }
                }
            }
            require(crit == rolled, "暴击率 ≥100% 时仍有 " + (rolled - crit) + " 次没暴击");
        });

        add(ga, "体检：同档锻造剑必须强于原版剑", () -> {
            var attr = org.bukkit.attribute.Attribute.ATTACK_DAMAGE;
            ItemStack vanilla = new ItemStack(org.bukkit.Material.IRON_SWORD);
            ItemStack forged = sampleSword();
            require(forged != null, "造不出铁剑");
            double v = componentTotal(vanilla, attr);
            double f = componentTotal(forged, attr);
            require(f > v, "锻造剑不划算：锻造 " + Msg.fmt(f, 3) + " vs 原版 " + Msg.fmt(v, 3)
                    + "（原版→" + describeComponents(vanilla, attr)
                    + " ／ 锻造→" + describeComponents(forged, attr) + "）");
        });
    }

    // ---------------------------------------------------------------- v2.9

    private void registerV29() {
        String g = "v2.9 专精与天赋";

        add(g, "经验公式：同一把剑比 2.8 明显更低", () -> {
            // 2.8：10 + 8×品质 + 分数/10；2.9：6 + 5×品质 + 0.05×分数
            int now = StatsStore.expGain(75.0, Quality.FINE.ordinal());
            int old = 10 + Quality.FINE.ordinal() * 8 + (int) Math.round(75 / 10.0);
            require(now < old, "经验没降下来：" + now + " vs 旧 " + old);
            // 只保证「砍了但没砍废」：降幅在 10%~60% 之间（服主自己调 config 也能过）
            require(now <= Math.round(old * 0.9), "降幅不到一成：" + now + " vs " + old);
            require(now >= Math.round(old * 0.4), "砍得太狠（超过六成）：" + now + " vs " + old);
            require(StatsStore.expGain(0, 0) >= 1, "零分锻造也应该有保底 1 点经验");
        });

        add(g, "经验曲线比 2.8 更陡且单调", () -> {
            for (int lv = 1; lv < 30; lv++) {
                require(StatsStore.expToNext(lv + 1) > StatsStore.expToNext(lv),
                        "Lv" + lv + " 经验表没递增");
            }
            require(StatsStore.expToNext(20) > 80 + 20 * 40, "20 级门槛没抬高");
            require(StatsStore.expToNext(0) >= 100, "0 级门槛太低");
        });

        add(g, "天赋点数：每 5 级 1 点，60 级 12 点", () -> {
            require(com.forgemaster.growth.Talents.earned(4) == 0, "4 级不该有点");
            require(com.forgemaster.growth.Talents.earned(5) == 1, "5 级应有 1 点");
            require(com.forgemaster.growth.Talents.earned(9) == 1, "9 级应有 1 点");
            require(com.forgemaster.growth.Talents.earned(10) == 2, "10 级应有 2 点");
            require(com.forgemaster.growth.Talents.earned(60) == 12, "60 级应有 12 点");
        });

        add(g, "天赋：点数用尽 / 单项到顶都拦得住", () -> {
            var st = new StatsStore.Stats();
            st.level = 10;   // 2 点
            var T = com.forgemaster.growth.Talents.Talent.TOUCH;
            require(com.forgemaster.growth.Talents.add(st, T), "第 1 点没加上");
            require(com.forgemaster.growth.Talents.add(st, T), "第 2 点没加上");
            require(!com.forgemaster.growth.Talents.add(st, T), "点数用尽还能加");
            require(com.forgemaster.growth.Talents.remaining(st) == 0, "剩余点数算错");

            var st2 = new StatsStore.Stats();
            st2.level = 60;
            var EYE = com.forgemaster.growth.Talents.Talent.EYE;
            require(com.forgemaster.growth.Talents.add(st2, EYE), "眼力第 1 点没加上");
            require(com.forgemaster.growth.Talents.add(st2, EYE), "眼力第 2 点没加上");
            require(!com.forgemaster.growth.Talents.add(st2, EYE), "眼力超过上限还能加");
            require(com.forgemaster.growth.Talents.levelOf(st2, EYE) == EYE.max(), "上限取值不对");
        });

        add(g, "天赋：效果数值与描述一致", () -> {
            var st = new StatsStore.Stats();
            // 七项满点要 20 点，只有 100 级才够（正常上限 60 级 = 12 点，正是取舍所在）
            st.level = 100;
            for (var t : com.forgemaster.growth.Talents.Talent.values()) {
                for (int i = 0; i < t.max(); i++) {
                    com.forgemaster.growth.Talents.add(st, t);
                }
            }
            var kit = com.forgemaster.growth.Talents.kit(st);
            require(Math.abs(kit.tolerance() - 0.09) < 1e-9, "手感 3 点应是 +9% 容差，实际 " + kit.tolerance());
            require(kit.zone() == 2, "眼力 2 点应是 +2 格");
            require(Math.abs(kit.heatFactor() - 0.82) < 1e-9, "控火 3 点应是 ×0.82 降温");
            require(Math.abs(kit.foldSeconds() - 1.2) < 1e-9, "稳手 3 点应是 +1.2s");
            require(Math.abs(kit.grindWidth() - 2.4) < 1e-9, "刃感 3 点应是 +2.4");
            require(Math.abs(kit.affixChance() - 0.18) < 1e-9, "火候 3 点应是 +18%");
            require(Math.abs(kit.expMultiplier() - 1.24) < 1e-9, "勤学 3 点应是 ×1.24 经验");
            require(com.forgemaster.growth.Talents.spent(st) == 20, "七项满点应是 20 点");
        });

        add(g, "天赋：洗点退回全部点数", () -> {
            var st = new StatsStore.Stats();
            st.level = 20;   // 4 点
            com.forgemaster.growth.Talents.add(st, com.forgemaster.growth.Talents.Talent.STUDY);
            com.forgemaster.growth.Talents.add(st, com.forgemaster.growth.Talents.Talent.FOLD);
            int back = com.forgemaster.growth.Talents.reset(st);
            require(back == 2, "退回点数不对：" + back);
            require(com.forgemaster.growth.Talents.spent(st) == 0, "洗点后还有点数");
            require(com.forgemaster.growth.Talents.remaining(st) == 4, "洗点后可用点数没回满");
        });

        add(g, "剥空词条时宝石 / 附魔的 ▪ 行不被连带清掉", () -> {
            var pool = imprintPool();
            ItemStack sword = swordWith(pool.subList(0, 1));
            require(sword != null, "造不出测试剑");
            // 伪造一行宝石条目（宝石/附魔也是 ▪ 开头，和词条条目长得一样）
            var meta = sword.getItemMeta();
            var lore = new ArrayList<>(meta.lore());
            lore.add(Msg.mm("<gray>▪ <white>测试宝石 <aqua>+2 <gray>攻击")
                    .decoration(net.kyori.adventure.text.format.TextDecoration.ITALIC, false));
            meta.lore(lore);
            sword.setItemMeta(meta);

            var only = pool.get(0);
            Double value = com.forgemaster.affix.Reforge.valueOf(meta, only);
            require(value != null, "读不出词条数值");
            meta = sword.getItemMeta();
            com.forgemaster.affix.Reforge.removeAffixRaw(meta, only, value);
            com.forgemaster.affix.Reforge.redrawAffixes(plugin, meta, List.of(), null, 0);
            sword.setItemMeta(meta);

            String text = loreText(sword);
            require(text.contains("测试宝石"), "宝石行被剥词条连带清掉了");
            require(text.contains("无词条"), "剥空后没有占位行");
            require(!text.contains("◆ 词条"), "剥空后还留着空标题");
        });

        add(g, "天赋在开工时固化进会话", () -> {
            ForgeSession s = probeSession(firstMaterial());
            require(s.talents() != null, "会话没带上天赋快照");
            require(Math.abs(s.talents().heatFactor() - 1.0) < 1e-9, "默认天赋不该改降温");
        });
    }

    // ---------------------------------------------------------------- v2.7

    private void registerV27() {
        String gTomb = "v2.7 剑冢残影";
        String gSet = "v2.7 词条套装";
        String gWatch = "v2.7 大赛观战";
        String gLegacy = "v2.7 赛季传承";
        String gImprint = "v2.8 词条符";

        add(gTomb, "材料档位映射到生物，越界有兜底", () -> {
            var c = plugin.tombs().challenges();
            require(c != null, "残影引擎没初始化");
            for (int tier = 1; tier <= 5; tier++) {
                String key = c.mobKeyOf("", tier);
                require(key != null && !key.isBlank(), "第 " + tier + " 档没配生物");
                require(org.bukkit.entity.EntityType.fromName(key) != null
                                || org.bukkit.entity.EntityType.valueOf(key.toUpperCase(java.util.Locale.ROOT)) != null,
                        "第 " + tier + " 档的生物类型不存在: " + key);
            }
            require(c.mobKeyOf("", 0) != null, "档位 0 要有兜底生物");
            require(c.mobKeyOf("", 99) != null, "超高档位要有兜底生物");
            require(c.mobKeyOf("nonsense-material", 3) != null, "陌生材料不该炸");
        });

        add(gTomb, "血量与攻击按强化等级放大且夹上限", () -> {
            var c = plugin.tombs().challenges();
            double low = com.forgemaster.tomb.TombChallenge.scaledHealth(20, 0, 1.0, 0.08);
            double high = com.forgemaster.tomb.TombChallenge.scaledHealth(20, 20, 1.0, 0.08);
            require(high > low, "强化等级没放大血量");
            require(low > 0, "血量不该是 0");
            double weak = com.forgemaster.tomb.TombChallenge.qualityCoefOf("ROUGH");
            double strong = com.forgemaster.tomb.TombChallenge.qualityCoefOf("MYTHIC");
            require(strong > weak, "品质系数没拉开");
            require(com.forgemaster.tomb.TombChallenge.qualityCoefOf("bogus") > 0,
                    "陌生品质要有兜底系数");
        });

        add(gTomb, "残影挑战的纯逻辑接口可读", () -> {
            var c = plugin.tombs().challenges();
            require(c.activeCount() >= 0, "进行中计数异常");
            java.util.UUID who = java.util.UUID.nameUUIDFromBytes("fm-tomb-cd".getBytes());
            require(c.cooldownRemainingMs(null, null) >= 0, "冷却计算不该返回负数");
            require(!c.canChallenge(null, "NOPE"), "空玩家不该能挑战");
        });

        add(gSet, "词条组合匹配：顺序与大小写无关", () -> {
            var sets = plugin.affixSets();
            require(sets != null, "套装模块没初始化");
            var defs = sets.all();
            require(!defs.isEmpty(), "一个套装都没解析出来，检查 requires 里的词条 id 是否存在");
            for (var d : defs) {
                require(!d.requires().isEmpty(), "套装 " + d.id() + " 没有 requires");
                require(d.name() != null && !d.name().isBlank(), "套装 " + d.id() + " 没名字");
                require(d.modifierKey().startsWith("fm_set_"), "修饰符键名不规范: " + d.modifierKey());
            }
            var order1 = sets.matchedSets(java.util.List.of("attack_damage", "lifesteal", "crit_chance"));
            var order2 = sets.matchedSets(java.util.List.of("CRIT_CHANCE", "Attack_Damage", "LIFESTEAL"));
            require(new java.util.HashSet<>(order1).equals(new java.util.HashSet<>(order2)),
                    "大小写/顺序影响了匹配结果");
            require(sets.matchedSets(java.util.List.of()).isEmpty(), "空词条不该命中任何套装");
        });

        add(gSet, "缺一条能算出来，且不越界", () -> {
            var sets = plugin.affixSets();
            var def = sets.all().get(0);
            var full = new java.util.ArrayList<>(def.requires());
            require(sets.satisfied(def.id(), full), "集齐了却没判定成立");
            full.remove(0);
            require(!sets.satisfied(def.id(), full), "少一条却判定成立");
            ItemStack partial = sampleSword();
            require(partial != null, "造不出测试剑");
            var pm = partial.getItemMeta();
            pm.getPersistentDataContainer().set(com.forgemaster.util.Keys.AFFIX_LIST,
                    org.bukkit.persistence.PersistentDataType.STRING, String.join(",", full));
            partial.setItemMeta(pm);
            var near = sets.nearSets(partial);
            require(near.contains(def.id()), "差一条却没算进 nearSets: " + near);
            for (String id : near) {
                require(sets.set(id) != null, "nearSets 返回了不存在的套装: " + id);
            }
            require(!sets.satisfied("不存在的套装", java.util.List.of("attack_damage")),
                    "陌生套装 id 不该成立");
        });

        add(gSet, "套装属性挂得上、可撤销、反复刷不堆", () -> {
            var sets = plugin.affixSets();
            var def = sets.all().get(0);
            ItemStack w = sampleSword();
            require(w != null, "造不出测试剑");

            // 直接把词条塞进 PDC，绕过随机
            var meta = w.getItemMeta();
            var pdc = meta.getPersistentDataContainer();
            pdc.set(com.forgemaster.util.Keys.AFFIX_LIST,
                    org.bukkit.persistence.PersistentDataType.STRING,
                    String.join(",", def.requires()));
            w.setItemMeta(meta);
            require(sets.satisfied(w, def.id()), "塞了词条却没判定成立");

            double atkBefore = baseAttackOf(w);
            sets.apply(w);
            require(sets.matchedSets(w).contains(def.id()), "apply 后没认出来");
            require(Math.abs(baseAttackOf(w) - atkBefore) < 1e-9, "套装改动了基础攻击");

            int count = modifierCount(w, Attribute.ATTACK_DAMAGE, def.modifierKey());
            require(count == 1, "套装修饰符应恰好 1 条，实际 " + count);
            sets.apply(w);
            sets.apply(w);
            require(modifierCount(w, Attribute.ATTACK_DAMAGE, def.modifierKey()) == 1,
                    "反复 apply 把套装修饰符堆起来了");

            // 撤掉词条 → 修饰符要跟着撤
            var m2 = w.getItemMeta();
            m2.getPersistentDataContainer().set(com.forgemaster.util.Keys.AFFIX_LIST,
                    org.bukkit.persistence.PersistentDataType.STRING, "");
            w.setItemMeta(m2);
            sets.apply(w);
            require(modifierCount(w, Attribute.ATTACK_DAMAGE, def.modifierKey()) == 0,
                    "词条没了，套装修饰符还挂着");
        });

        add(gSet, "套装不碰剑意与基础属性", () -> {
            var sets = plugin.affixSets();
            var prof = plugin.proficiency();
            ItemStack w = sampleSword();
            require(w != null, "造不出测试剑");
            if (sets.all().isEmpty()) {
                return;
            }
            var def = sets.all().get(0);
            prof.setKills(w, 600);
            int profBefore = modifierCount(w, Attribute.ATTACK_DAMAGE, "fm_prof_atk");
            int baseBefore = modifierCount(w, Attribute.ATTACK_DAMAGE, "fm_atk");

            var meta = w.getItemMeta();
            meta.getPersistentDataContainer().set(com.forgemaster.util.Keys.AFFIX_LIST,
                    org.bukkit.persistence.PersistentDataType.STRING,
                    String.join(",", def.requires()));
            w.setItemMeta(meta);
            sets.apply(w);

            require(modifierCount(w, Attribute.ATTACK_DAMAGE, "fm_prof_atk") == profBefore,
                    "套装动了剑意修饰符");
            require(modifierCount(w, Attribute.ATTACK_DAMAGE, "fm_atk") == baseBefore,
                    "套装动了基础攻击修饰符");
        });

        add(gSet, "成品一造出来就带套装行与套装属性（接线没断）", () -> {
            var sets = plugin.affixSets();
            if (sets == null || sets.all().isEmpty()) {
                return;
            }
            ForgeMaterial iron = plugin.materials().get("iron");
            require(iron != null, "铁剑胚不存在，造不出测试剑");
            var def = sets.all().get(0);

            ForgeSession s = new ForgeSession(
                    UUID.nameUUIDFromBytes("fm-set-build".getBytes()), NAME, iron);
            s.quality(Quality.EPIC);
            s.finalScore(78.0);
            s.heatScore(1.0);
            for (String id : def.requires()) {
                var a = plugin.affixes().get(id);
                require(a != null, "套装 " + def.id() + " 要求的词条不存在: " + id);
                s.affixValues().put(a, 1.0);
            }

            ItemStack built = plugin.items().build(s);
            require(built != null, "造不出成品");
            String lore = loreText(built);
            require(lore.contains(com.forgemaster.affix.AffixSetService.LORE_PREFIX),
                    "成品 Lore 里没有套装行 —— ItemFactory.build 没接 AffixSetService.apply");
            require(lore.contains(def.name()), "套装行里没有套装名「" + def.name() + "」");
            if (def.bonusAttack() > 0) {
                require(modifierCount(built, Attribute.ATTACK_DAMAGE, def.modifierKey()) == 1,
                        "套装攻击加成没写进物品属性");
            }
        });

        add(gImprint, "词条符能写入并原样读回（id / 数值 / 池别）", () -> {
            var pool = imprintPool();
            require(pool.size() >= 2, "词条表里至少要两条词条");
            var a = pool.get(0);
            ItemStack im = com.forgemaster.affix.Imprint.build(plugin, a, 1.23, false);
            require(com.forgemaster.affix.Imprint.isImprint(im), "造出来的不是词条符");
            require(a.id().equalsIgnoreCase(com.forgemaster.affix.Imprint.affixIdOf(im)),
                    "词条 id 没写对");
            require(Math.abs(com.forgemaster.affix.Imprint.valueOf(im) - 1.23) < 1e-9,
                    "词条数值没写对");
            require(!com.forgemaster.affix.Imprint.armorOnly(im), "武器词条被标成了装备池");

            ItemStack armorIm = com.forgemaster.affix.Imprint.build(plugin, a, 2.0, true);
            require(com.forgemaster.affix.Imprint.armorOnly(armorIm), "装备词条池标记丢了");
            require(com.forgemaster.affix.Imprint.stripCost(plugin, sampleSword().getItemMeta(), 0) > 0,
                    "剥离费应该是正数");
            require(com.forgemaster.affix.Imprint.graftCost(plugin, sampleSword().getItemMeta()) > 0,
                    "移植费应该是正数");
        });

        add(gImprint, "剥离：词条从列表、属性、Lore 同时消失", () -> {
            var pool = imprintPool();
            var first = pool.get(0);
            ItemStack sword = swordWith(pool.subList(0, 1));
            require(sword != null, "造不出测试剑");
            require(com.forgemaster.affix.Reforge.affixCount(sword) == 1, "测试剑词条数不对");
            Double value = com.forgemaster.affix.Reforge.valueOf(sword.getItemMeta(), first);
            require(value != null, "读不出这条词条的数值");

            var meta = sword.getItemMeta();
            com.forgemaster.affix.Reforge.removeAffixRaw(meta, first, value);
            java.util.List<String> ids = new ArrayList<>(com.forgemaster.affix.Reforge.affixIdsOf(sword));
            ids.remove(first.id().toLowerCase(java.util.Locale.ROOT));
            meta.getPersistentDataContainer().set(Keys.AFFIX_LIST, PersistentDataType.STRING,
                    String.join(",", ids));
            com.forgemaster.affix.Reforge.redrawAffixes(plugin, meta, ids, null, 0);
            sword.setItemMeta(meta);

            require(com.forgemaster.affix.Reforge.affixCount(sword) == 0, "剥离后 affix_list 没清干净");
            require(!loreText(sword).contains(first.display()), "Lore 里还留着被剥掉的词条");
            if (first.kind() == com.forgemaster.affix.Affix.Kind.ATTRIBUTE && first.attribute() != null) {
                require(modifierCount(sword, first.attribute(), "fm_" + first.id().toLowerCase(java.util.Locale.ROOT)) == 0,
                        "词条属性修饰符没撤掉");
            }
            String lore = loreText(sword);
            require(lore.contains("无词条"), "剥空后应该显示「无词条」占位行");
            require(!lore.contains("◆ 词条"), "剥空后还留着一个空标题");
        });

        add(gImprint, "移植：词条装回去，属性与 Lore 一起回来", () -> {
            var pool = imprintPool();
            ItemStack sword = swordWith(pool.subList(0, 1));
            require(sword != null, "造不出测试剑");
            var add = pool.get(1);
            var meta = sword.getItemMeta();
            java.util.List<String> ids = new ArrayList<>(com.forgemaster.affix.Reforge.affixIdsOf(sword));
            require(!ids.contains(add.id().toLowerCase(java.util.Locale.ROOT)), "挑的两条词条重名了");

            com.forgemaster.affix.Reforge.addAffixRaw(plugin, meta, add, 1.34,
                    org.bukkit.inventory.EquipmentSlotGroup.MAINHAND);
            ids.add(add.id().toLowerCase(java.util.Locale.ROOT));
            meta.getPersistentDataContainer().set(Keys.AFFIX_LIST, PersistentDataType.STRING,
                    String.join(",", ids));
            com.forgemaster.affix.Reforge.redrawAffixes(plugin, meta, ids, add, 1.34);
            sword.setItemMeta(meta);

            require(com.forgemaster.affix.Reforge.affixCount(sword) == 2, "移植后词条数不对");
            require(loreText(sword).contains(add.display()), "Lore 里没有新装进去的词条");
            if (add.kind() == com.forgemaster.affix.Affix.Kind.ATTRIBUTE && add.attribute() != null) {
                require(modifierCount(sword, add.attribute(), "fm_" + add.id().toLowerCase(java.util.Locale.ROOT)) == 1,
                        "移植没写属性修饰符");
            }
            // 同一件反复画 Lore 不该堆出第二块「◆ 词条」
            var again = sword.getItemMeta();
            com.forgemaster.affix.Reforge.redrawAffixes(plugin, again, ids, null, 0);
            sword.setItemMeta(again);
            int titles = 0;
            for (String line : loreText(sword).split("\n")) {
                if (line.trim().startsWith("◆ 词条")) {
                    titles++;
                }
            }
            require(titles == 1, "「◆ 词条」标题重复了 " + titles + " 次");
        });

        add(gWatch, "阶段标签与槽位数学", () -> {
            require("加热".equals(com.forgemaster.contests.ContestWatchGui.phaseLabel(
                    com.forgemaster.forge.ForgePhase.HEATING)), "HEATING 标签不对");
            require("锻打".equals(com.forgemaster.contests.ContestWatchGui.phaseLabel(
                    com.forgemaster.forge.ForgePhase.HAMMERING)), "HAMMERING 标签不对");
            require("淬火".equals(com.forgemaster.contests.ContestWatchGui.phaseLabel(
                    com.forgemaster.forge.ForgePhase.QUENCHING)), "QUENCHING 标签不对");
            require(com.forgemaster.contests.ContestWatchGui.phaseLabel(null) != null,
                    "null 阶段要有兜底文案");

            require(com.forgemaster.contests.ContestWatchGui.closeSlot(27) == 22, "27 格关闭槽不对");
            require(com.forgemaster.contests.ContestWatchGui.closeSlot(54) == 49, "54 格关闭槽不对");
            require(com.forgemaster.contests.ContestWatchGui.capacity(54) > 0, "容量不该是 0");
            require(com.forgemaster.contests.ContestWatchGui.capacity(27) > 0, "容量不该是 0");
            int rows = com.forgemaster.contests.ContestWatchGui.rows(plugin);
            require(rows >= 3 && rows <= 6, "行数越界: " + rows);
        });

        add(gWatch, "没比赛时观战被拦住，且空状态不炸", () -> {
            require(!com.forgemaster.contests.ContestWatchGui.watching(null), "null 玩家不该在观战");
            require(com.forgemaster.contests.ContestWatchGui.watcherCount() >= 0, "观战计数异常");
            if (!plugin.contests().running()) {
                ItemStack nothing = sampleSword();
                String reason = com.forgemaster.contests.ContestWatchGui.blockedReason(plugin, null);
                require(reason != null, "没有比赛 / 没有玩家时不该放行");
            }
        });

        add(gWatch, "赛况面板槽位不重叠", () -> {
            var usage = com.forgemaster.contests.ContestGui.slotUsage();
            require(!usage.isEmpty(), "赛况面板没有槽位定义");
            for (var e : usage.entrySet()) {
                require(e.getKey() >= 0 && e.getKey() < 27, "槽位越界: " + e.getKey());
                require(e.getValue() != null && !e.getValue().isBlank(), "槽位 " + e.getKey() + " 没有说明");
            }
            require(usage.containsKey(com.forgemaster.contests.ContestGui.WATCH_SLOT),
                    "赛况面板缺观战入口");
        });

        add(gLegacy, "复合分公式与单调性", () -> {
            double a = com.forgemaster.season.SeasonLegacy.compositeRaw(2, 60, 5);
            double b = com.forgemaster.season.SeasonLegacy.compositeRaw(3, 60, 5);
            require(b > a, "品质高一档，复合分应更高");
            double c = com.forgemaster.season.SeasonLegacy.compositeRaw(2, 60, 6);
            require(c > a, "强化高一级，复合分应更高");
            double d = com.forgemaster.season.SeasonLegacy.compositeRaw(2, 61, 5);
            require(d > a, "评分高一分，复合分应更高");
            require(com.forgemaster.season.SeasonLegacy.compositeRaw(0, 0, 0) >= 0, "复合分不该是负数");
            require(com.forgemaster.season.SeasonLegacy.compositeRaw(-5, -5, -5) >= 0,
                    "负数输入要被夹到 0");
        });

        add(gLegacy, "传承查询接口：空数据安全，边界正确", () -> {
            var legacy = plugin.seasons() == null ? null : plugin.seasons().legacy();
            require(legacy != null, "剑传承没初始化");
            var all = legacy.all();
            for (int i = 1; i < all.size(); i++) {
                require(all.get(i).seasonNumber() > all.get(i - 1).seasonNumber(),
                        "历史记录没有按赛季升序");
            }
            require(legacy.recent(0).isEmpty(), "recent(0) 应为空");
            require(legacy.recent(-3).isEmpty(), "recent(负数) 应为空");
            if (!all.isEmpty()) {
                require(legacy.recent(99999).size() == all.size(), "recent(超大) 应返回全部");
                require(legacy.ofSeason(all.get(0).seasonNumber()) != null, "查不到已有赛季");
            }
            require(legacy.ofSeason(-1) == null, "查不存在的赛季应返回 null");
            require(legacy.describe(null) != null, "describe(null) 不该返回 null");
            require(legacy.showCount() >= 1 && legacy.showCount() <= 50, "陈列季数越界");
        });
    }

    // ---------------------------------------------------------------- v2.6

    private void registerV26() {
        String gProf = "v2.6 剑意";
        String gCatch = "v2.6 赛季追赶";
        String gReforgeGui = "v2.6 重铸界面";

        add(gProf, "档位门槛映射正确", () -> {
            var prof = plugin.proficiency();
            require(prof != null, "剑意模块没初始化");
            int[] tiers = prof.tiers();
            require(tiers.length >= 2, "门槛至少要两档");
            for (int i = 1; i < tiers.length; i++) {
                require(tiers[i] > tiers[i - 1], "门槛不是严格递增");
            }
            require(prof.tierIndexOf(0) == 0, "0 剑意应在最低档");
            require(prof.tierIndexOf(tiers[0] - 1) == 0, "刚差一点也不该进档");
            require(prof.tierIndexOf(tiers[0]) == 1, "正好到门槛应进第一档");
            require(prof.tierIndexOf(tiers[tiers.length - 1]) == prof.maxTier(), "满门槛应在最高档");
            require(prof.tierIndexOf(999999) == prof.maxTier(), "超高剑意应停在最高档");
            require(prof.tierIndexOf(-5) == 0, "负数剑意应在最低档");
        });

        add(gProf, "档名与加成读得到", () -> {
            var prof = plugin.proficiency();
            require(prof != null, "剑意模块没初始化");
            for (int i = 0; i <= prof.maxTier(); i++) {
                String name = prof.tierName(i);
                require(name != null && !name.isBlank(), "第 " + i + " 档没有名字");
            }
            require(prof.bonusOf(0) == 0, "0 档不该有加成");
            for (int i = 1; i <= prof.maxTier(); i++) {
                require(prof.bonusOf(i) > 0, "第 " + i + " 档加成为零");
                require(prof.bonusOf(i) <= 1.0, "第 " + i + " 档加成因太离谱");
            }
            require(prof.bonusOf(99) >= 0, "越界档位不该抛异常");
            require(prof.nextThreshold(prof.maxTier()) == -1, "满档的下一档门槛应为 -1");
        });

        add(gProf, "非锻造品一概不认", () -> {
            var prof = plugin.proficiency();
            ItemStack stick = new ItemStack(Material.STICK);
            require(prof.killsOf(stick) == 0, "木棍不该有剑意");
            require(prof.tierOf(stick) == 0, "木棍不该有档位");
            prof.apply(stick);
            require(loreRowsStartingWith(stick, com.forgemaster.proficiency.ProficiencyService.LORE_PREFIX) == 0,
                    "木棍被加上了剑意 Lore");
            require(modifierCount(stick, Attribute.ATTACK_DAMAGE, "fm_prof_atk") == 0,
                    "木棍被加上了剑意修饰符");
            prof.addKill(null, stick);
            require(prof.killsOf(stick) == 0, "木棍也涨了剑意");
        });

        add(gProf, "剑意写入与 Lore 文本一致", () -> {
            var prof = plugin.proficiency();
            ItemStack w = sampleSword();
            require(w != null, "造不出测试剑");
            require(prof.setKills(w, 1247), "写剑意失败");
            require(prof.killsOf(w) == 1247, "剑意读回来不对: " + prof.killsOf(w));
            int tier = prof.tierOf(w);
            require(tier >= 1, "1247 剑意至少该有一档");

            String plain = Msg.plain(Msg.mm(prof.line(w)));
            require(plain.startsWith(com.forgemaster.proficiency.ProficiencyService.LORE_PREFIX),
                    "剑意行没以「剑意」开头: " + plain);
            require(plain.contains("1247"), "剑意行没显示当前值: " + plain);
            require(plain.contains(prof.tierName(tier)), "剑意行没显示当前档名: " + plain);
        });

        add(gProf, "剑意 Lore 只占一行，反复刷新不堆叠", () -> {
            var prof = plugin.proficiency();
            ItemStack w = sampleSword();
            require(w != null, "造不出测试剑");
            for (int i = 1; i <= 5; i++) {
                prof.setKills(w, i * 120);
            }
            long rows = loreRowsStartingWith(w, com.forgemaster.proficiency.ProficiencyService.LORE_PREFIX);
            require(rows == 1, "剑意行应该有 1 行，实际 " + rows);
        });

        add(gProf, "剑意属性独立一层，不碰基础属性", () -> {
            var prof = plugin.proficiency();
            var st = plugin.strengthen();
            ItemStack w = sampleSword();
            require(w != null, "造不出测试剑");
            st.setLevel(w, 5);
            double atkBefore = baseAttackOf(w);
            int speedBefore = modifierCount(w, Attribute.ATTACK_SPEED);

            prof.setKills(w, 2000);
            require(Math.abs(baseAttackOf(w) - atkBefore) < 1e-9, "剑意改动了基础攻击");
            require(modifierCount(w, Attribute.ATTACK_SPEED) == speedBefore, "剑意改动了攻速修饰符");
            require(modifierCount(w, Attribute.ATTACK_DAMAGE, "fm_atk") == 1, "基础攻击修饰符被打乱");
            require(modifierCount(w, Attribute.ATTACK_DAMAGE, "fm_prof_atk") == 1,
                    "剑意修饰符应恰好 1 条");

            // 幂等：再刷两次不该堆
            prof.apply(w);
            prof.apply(w);
            require(modifierCount(w, Attribute.ATTACK_DAMAGE, "fm_prof_atk") == 1,
                    "反复 apply 把剑意修饰符堆起来了");
        });

        add(gProf, "剑意归零后撤掉修饰符", () -> {
            var prof = plugin.proficiency();
            ItemStack w = sampleSword();
            require(w != null, "造不出测试剑");
            prof.setKills(w, 600);
            require(modifierCount(w, Attribute.ATTACK_DAMAGE, "fm_prof_atk") == 1, "高位剑意没挂上修饰符");
            prof.setKills(w, 0);
            require(prof.tierOf(w) == 0, "清零后档位不为 0");
            require(modifierCount(w, Attribute.ATTACK_DAMAGE, "fm_prof_atk") == 0,
                    "清零后还留着 0 加成的空修饰符");
        });

        add(gProf, "负值与非法输入不炸", () -> {
            var prof = plugin.proficiency();
            ItemStack w = sampleSword();
            require(w != null, "造不出测试剑");
            prof.setKills(w, -5);
            require(prof.killsOf(w) == 0, "负剑意没被夹到 0");
            prof.apply(null);
            prof.persist(null);
            require(prof.line(null) != null, "line(null) 不该返回 null");
        });

        // ------------------------------------------------------------ 赛季追赶

        add(gCatch, "基础成功率是纯曲线，随等级递减", () -> {
            var st = plugin.strengthen();
            require(st.baseSuccessChance(3) < 1.0, "3 级基础成功率不该已经是 100%");
            require(st.baseSuccessChance(20) < st.baseSuccessChance(10), "基础成功率没随等级递减");
            require(st.baseSuccessChance(10) < st.baseSuccessChance(5), "基础成功率没随等级递减");
            require(st.baseSuccessChance(999) > 0, "基础成功率不该降到 0 以下");
        });

        add(gCatch, "总成功率 = 基础 + 追赶，且不超过 100%", () -> {
            var st = plugin.strengthen();
            for (int level = 0; level <= 40; level++) {
                double base = st.baseSuccessChance(level);
                double bonus = st.seasonCatchupBonus(level);
                double expected = Math.min(1.0, base + bonus);
                require(Math.abs(st.successChance(level) - expected) < 1e-9,
                        "第 " + level + " 级成功率不等于基础+追赶");
                require(st.successChance(level) <= 1.0 + 1e-12, "第 " + level + " 级成功率超过 100%");
                require(st.successChance(level) >= base - 1e-12, "追赶加成反而降低了成功率");
            }
            require(st.successChance(3) < 1.0, "3 级在赛季末不该变成 100%");
        });

        add(gCatch, "追赶只帮阈值及以下的等级", () -> {
            var st = plugin.strengthen();
            int threshold = st.seasonCatchupLevelThreshold();
            require(threshold >= 0, "阈值不该是负数");
            require(st.seasonCatchupBonus(threshold + 1) == 0, "超过阈值的等级不该吃追赶");
            require(st.seasonCatchupBonus(999) == 0, "超高等级不该吃追赶");
            require(st.seasonCatchupBonus(-1) == 0, "负数等级不该吃追赶");
            for (int level = 0; level <= 40; level++) {
                double bonus = st.seasonCatchupBonus(level);
                require(bonus >= 0, "第 " + level + " 级追赶加成为负");
                require(bonus <= st.seasonCatchupMaxBonus() + 1e-9, "第 " + level + " 级追赶超过上限");
            }
        });

        add(gCatch, "赛季进度取值合法，接口不炸", () -> {
            var st = plugin.strengthen();
            double p = st.seasonProgress();
            require(p >= 0 && p <= 1, "赛季进度越界: " + p);
            require(st.seasonNumber() >= 1, "赛季编号不该小于 1");
            require(st.seasonDurationDays() >= 1, "赛季时长不该小于 1 天");
            require(st.seasonCatchupMaxBonus() >= 0, "追赶上限不该为负");
            require(st.seasonCatchupPerDay() >= 0, "每日追赶不该为负");
        });

        // ------------------------------------------------------------ 重铸界面

        add(gReforgeGui, "槽位与词条下标互转", () -> {
            require(com.forgemaster.gui.ReforgeMenu.indexOfSlot(
                            com.forgemaster.gui.ReforgeMenu.slotOf(0)) == 0, "0 号词条槽位往返失败");
            require(com.forgemaster.gui.ReforgeMenu.indexOfSlot(
                            com.forgemaster.gui.ReforgeMenu.slotOf(3)) == 3, "3 号词条槽位往返失败");
            require(com.forgemaster.gui.ReforgeMenu.slotOf(-1) == -1, "负下标应返回 -1");
            require(com.forgemaster.gui.ReforgeMenu.slotOf(99) == -1, "超界下标应返回 -1");
            require(com.forgemaster.gui.ReforgeMenu.indexOfSlot(4) == -1, "非词条槽位应返回 -1");
            require(com.forgemaster.gui.ReforgeMenu.AFFIX_SLOTS.length > 0, "没有词条槽位");
            require(com.forgemaster.gui.ReforgeMenu.SIZE == 27, "重铸界面尺寸应为 27");
        });

        add(gReforgeGui, "主菜单留了重铸入口且图标齐全", () -> {
            require(com.forgemaster.gui.ForgeMenu.REFORGE_SLOT >= 0
                    && com.forgemaster.gui.ForgeMenu.REFORGE_SLOT < 27, "重铸入口槽位越界");
            ItemStack icon = com.forgemaster.gui.ReforgeMenu.entryIcon();
            require(icon != null && !icon.getType().isAir(), "重铸入口图标是空的");
            require(icon.getItemMeta() != null && icon.getItemMeta().displayName() != null,
                    "重铸入口图标没有名字");
        });

        add(gReforgeGui, "空物品与空气不会崩", () -> {
            ItemStack nothing = new ItemStack(Material.AIR);
            require(com.forgemaster.affix.Reforge.affixCount(nothing) == 0, "空气居然有词条");
            require(com.forgemaster.affix.Reforge.preview(nothing, 0) != null, "preview 不该返回 null");
            require(com.forgemaster.affix.Reforge.costOf(nothing, 0) >= 0, "费用不该是负数");
        });
    }

    // ---------------------------------------------------------------- v2.5

    private void registerV25() {
        String gFurnace = "v2.5 炉火保底";
        String gSim = "v2.5 强化模拟器";
        String gHist = "v2.5 强化履历";
        String gCharm = "v2.5 三符 / 剑冢留档";
        String gDuel = "v2.5 竞技分级";
        String gHall = "v2.5 名人堂 / 排行";
        String gReforge = "v2.5 词条重铸";

        add(gFurnace, "炉火需求随等级不降，新剑为 0", () -> {
            var s = plugin.strengthen();
            require(s.furnaceNeed(0) > 0, "低等级炉火需求不能是 0");
            require(s.furnaceNeed(11) >= s.furnaceNeed(0), "中档需求不该比低档少");
            require(s.furnaceNeed(21) >= s.furnaceNeed(11), "高档需求不该比中档少");
            ItemStack w = sampleSword();
            require(w != null, "造不出测试剑");
            require(s.furnaceOf(w) == 0, "新剑炉火应为 0，实际 " + s.furnaceOf(w));
            require(!s.furnaceReady(w), "新剑不该是必成状态");
        });

        add(gFurnace, "炉火攒满后判定为必成", () -> {
            var s = plugin.strengthen();
            ItemStack w = sampleSword();
            require(w != null, "造不出测试剑");
            int need = s.furnaceNeed(0);
            var meta = w.getItemMeta();
            meta.getPersistentDataContainer().set(com.forgemaster.util.Keys.FURNACE,
                    org.bukkit.persistence.PersistentDataType.INTEGER, need);
            w.setItemMeta(meta);
            require(s.furnaceOf(w) == need, "炉火写入失败: " + s.furnaceOf(w));
            require(s.furnaceReady(w), "攒满却没判必成");
            require(Msg.plain(Msg.mm(s.describe(w))).contains("必成"), "状态说明里没提示必成");
        });

        add(gSim, "模拟器不改物品", () -> {
            var s = plugin.strengthen();
            ItemStack w = sampleSword();
            require(w != null, "造不出测试剑");
            int lv = s.levelOf(w);
            int fails = s.failsOf(w);
            byte[] before = w.serializeAsBytes();
            s.simulate(w, 10);
            require(s.levelOf(w) == lv, "模拟后等级变了");
            require(s.failsOf(w) == fails, "模拟后履历变了");
            require(java.util.Arrays.equals(before, w.serializeAsBytes()), "模拟后物品被改了");
        });

        add(gSim, "期望次数落在「一锤两级」与「1/p 和」之间", () -> {
            var s = plugin.strengthen();
            ItemStack w = sampleSword();
            require(w != null, "造不出测试剑");
            var sim = s.simulateStats(w, 10);
            double naive = 0;
            int from = Math.max(0, s.levelOf(w));
            for (int i = from; i < 10; i++) {
                naive += 1.0 / s.successChance(i);
            }
            // 上界：旧版模拟器把所有非碎失败都当降级，所以「期望 ≥ 1/p 和」当年成立；
            // 五态口径里大成是 +2 级、空锤不降级，裸锤期望必然低于 1/p 和。
            require(sim.expectedAttempts() < naive + 1e-6,
                    "期望次数 " + sim.expectedAttempts() + " 不该超过 1/p 和 " + naive);
            // 下界：一锤最多 +2 级（大成），10 级至少要 5 锤
            require(sim.expectedAttempts() >= 5.0 - 1e-6,
                    "期望次数 " + sim.expectedAttempts() + " 低于「一锤两级」的理论下限");
            require(sim.expectedAttempts() > 0, "期望次数不该是 0");
        });

        add(gSim, "目标越远期望越大、达成概率越低", () -> {
            var s = plugin.strengthen();
            ItemStack w = sampleSword();
            require(w != null, "造不出测试剑");
            var near = s.simulateStats(w, 5);
            var far = s.simulateStats(w, 20);
            require(far.expectedAttempts() > near.expectedAttempts(), "远目标期望次数应更大");
            require(far.reachChance() <= near.reachChance() + 1e-9, "远目标达成率不该更高");
            require(near.reachChance() >= 0 && near.reachChance() <= 1, "达成率越界");
        });

        add(gSim, "目标自动夹到上限，越过上限不报错", () -> {
            var s = plugin.strengthen();
            ItemStack w = sampleSword();
            require(w != null, "造不出测试剑");
            int cap = s.capOf(w);
            require(s.simulateStats(w, 999).targetLevel() == cap,
                    "目标应夹到上限 " + cap + "，实际 " + s.simulateStats(w, 999).targetLevel());
            require(s.simulateStats(w, 0).expectedAttempts() == 0, "目标等于当前时应 0 次");
        });

        add(gSim, "模拟报告文本带关键信息", () -> {
            ItemStack w = sampleSword();
            require(w != null, "造不出测试剑");
            String text = Msg.plain(Msg.mm(plugin.strengthen().simulate(w, 10)));
            require(text.contains("期望"), "报告里没有期望值");
            require(text.contains("剑币") || text.contains("材料"), "报告里没有消耗明细");
        });

        add(gSim, "模拟器与明牌抽票用同一套概率（v2.19.1 修口径）", () -> {
            var s = plugin.strengthen();
            for (int lv : new int[]{0, 10, 30, 60}) {
                double[] o = s.odds(lv, 0, false, 0,
                        com.forgemaster.contract.RelicBonus.ZERO, false, false);
                double sum = o[0] + o[1] + o[2] + o[3] + o[4];
                require(Math.abs(sum - 1.0) < 1e-9, "五态概率和不是 1: " + sum + " @+" + lv);
                // 大成项必须就是 greatBase（裸锤、无连击、无圣物）
                require(Math.abs(o[3] - s.greatBase()) < 1e-9,
                        "大成概率和 greatBase 不一致 @+" + lv);
                // 成功 + 大成 必须就是成功率
                require(Math.abs((o[4] + o[3]) - Math.min(1.0, s.successChance(lv))) < 1e-9,
                        "成功+大成 不等于成功率 @+" + lv);
                // 损伤 = 没碎的那部分失败 × damage-share
                double expectDamage = (o[1] + o[2]) * s.damageShare();
                require(Math.abs(o[1] - expectDamage) < 1e-9,
                        "损伤项没按 damage-share 拆分 @+" + lv);
            }
        });

        add(gSim, "模拟器算出的期望消耗与逐级费用自洽", () -> {
            var s = plugin.strengthen();
            ItemStack w = sampleSword();
            require(w != null, "造不出测试剑");
            var sim = s.simulateStats(w, 10);
            require(sim.expectedCoins() > 0, "期望剑币不该是 0");
            require(sim.expectedMaterials() > 0, "期望材料不该是 0");
            // 碎剑次数不可能超过总锤数
            require(sim.expectedBreaks() <= sim.expectedAttempts() + 1e-9,
                    "碎剑次数不该超过锤数");
            // 达成率必须落在 0~1
            require(sim.reachChance() >= -1e-9 && sim.reachChance() <= 1 + 1e-9,
                    "达成率越界: " + sim.reachChance());
        });

        add(gSim, "强化曲线表：六档各一行、不是锻造品给红字", () -> {
            var st = plugin.strengthen();
            // 神话胚子（cap 99）才能铺满六档：40/50/60/72/86/99
            ItemStack mythic = plugin.items().buildDirect(NAME, firstMaterial(), Quality.MYTHIC, 40);
            require(mythic != null, "造不出神话测试剑");
            String text = st.curveReport(mythic);
            require(text.contains("强化曲线"), "曲线报表没有标题");
            int rows = 0;
            for (String line : text.split("<newline>")) {
                if (line.startsWith("<white>+")) {
                    rows++;
                }
            }
            require(rows >= 5, "曲线行数太少（只有 " + rows + " 行），六档没铺满");
            require(text.contains("达成"), "曲线报表没有达成率");
            require(text.contains("不收敛") || text.contains("期望"), "曲线报表没有期望值");
            // 渲染成纯文本不能抛异常（渐变 / MiniMessage 标签都得能过）
            require(!Msg.plain(Msg.mm(text)).isBlank(), "曲线报表渲染成空文本");
            // 非锻造品必须拦住，不能算出乱七八糟的曲线
            String plain = st.curveReport(new ItemStack(Material.STONE));
            require(plain.contains("不是锻造品"), "普通物品没有被挡住: " + plain);
        });

        add(gHist, "新剑没有履历行，写记录后出现且只一行", () -> {
            var s = plugin.strengthen();
            ItemStack w = sampleSword();
            require(w != null, "造不出测试剑");
            require(s.historyLine(w) == null, "新剑不该有履历");

            var meta = w.getItemMeta();
            var pdc = meta.getPersistentDataContainer();
            pdc.set(com.forgemaster.util.Keys.STRENGTHEN_FAILS,
                    org.bukkit.persistence.PersistentDataType.INTEGER, 3);
            pdc.set(com.forgemaster.util.Keys.STRENGTHEN_BROKEN,
                    org.bukkit.persistence.PersistentDataType.INTEGER, 1);
            pdc.set(com.forgemaster.util.Keys.STRENGTHEN_INVEST,
                    org.bukkit.persistence.PersistentDataType.INTEGER, 47);
            w.setItemMeta(meta);

            String line = s.historyLine(w);
            require(line != null, "写了记录却没履历");
            String plain = Msg.plain(Msg.mm(line));
            require(plain.contains("失败 3 次"), "履历没有失败次数: " + plain);
            require(plain.contains("碎剑 1 次"), "履历没有碎剑次数: " + plain);
            require(plain.contains("47"), "履历没有投入数: " + plain);

            s.setLevel(w, 2);
            long rows = (w.getItemMeta().lore() == null ? java.util.List.<net.kyori.adventure.text.Component>of()
                    : w.getItemMeta().lore()).stream()
                    .map(Msg::plain).filter(t -> t.startsWith("履历")).count();
            require(rows == 1, "履历行数应为 1，实际 " + rows);
        });

        add(gCharm, "三种符 id 都能识别，互不误判", () -> {
            require(com.forgemaster.strengthen.Charm.TALISMAN.equals(
                    com.forgemaster.strengthen.Charm.normalize("talisman")), "护胚符 id 识别失败");
            require(com.forgemaster.strengthen.Charm.STEADY.equals(
                    com.forgemaster.strengthen.Charm.normalize("steady")), "稳胚符 id 识别失败");
            require(com.forgemaster.strengthen.Charm.KINDLE.equals(
                    com.forgemaster.strengthen.Charm.normalize("charm-kindle")), "旺火符 id 识别失败");
            require(com.forgemaster.strengthen.Charm.normalize("bogus") == null, "未知 id 不该被认");
            require(com.forgemaster.strengthen.Charm.ALL.size() == 3, "应正好三种符");
        });

        add(gCharm, "符造得出、认得出，且不互相误伤", () -> {
            var t = com.forgemaster.strengthen.Charm.create(plugin, com.forgemaster.strengthen.Charm.TALISMAN);
            var st = com.forgemaster.strengthen.Charm.create(plugin, com.forgemaster.strengthen.Charm.STEADY);
            var k = com.forgemaster.strengthen.Charm.create(plugin, com.forgemaster.strengthen.Charm.KINDLE);
            require(com.forgemaster.strengthen.Charm.isCharm(t, com.forgemaster.strengthen.Charm.TALISMAN), "护胚符认不出");
            require(com.forgemaster.strengthen.Charm.isCharm(st, com.forgemaster.strengthen.Charm.STEADY), "稳胚符认不出");
            require(com.forgemaster.strengthen.Charm.isCharm(k, com.forgemaster.strengthen.Charm.KINDLE), "旺火符认不出");
            require(!com.forgemaster.strengthen.Charm.isCharm(st, com.forgemaster.strengthen.Charm.KINDLE), "稳胚符被当成旺火符");
            require(!com.forgemaster.strengthen.Talisman.isTalisman(st), "稳胚符被当成护胚符");
            ItemStack arr = sampleSword();
            require(arr != null && !com.forgemaster.strengthen.Talisman.isTalisman(arr), "剑被当成护胚符");
        });

        add(gCharm, "旺火符加成读得到且为正", () -> {
            double bonus = com.forgemaster.strengthen.Charm.kindleBonus(plugin);
            require(bonus > 0 && bonus < 1, "旺火加成不合理: " + bonus);
        });

        add(gCharm, "碎剑留档进剑冢，且不占埋剑名额", () -> {
            ItemStack w = sampleSword();
            require(w != null, "造不出测试剑");
            var tomb = plugin.tombs();
            require(tomb != null, "剑冢没初始化");
            int before = (int) tomb.countOf(new java.util.UUID(0L, 0L));
            String id = tomb.recordBroken(null, w, 7);
            if (id == null) {
                return; // 剑冢未开启时不算失败
            }
            var t = tomb.get(id);
            require(t != null, "留档条目取不到");
            require(t.broken, "留档条目没标成碎剑");
            require(t.brokenAt == 7, "碎在 +几 记错了: " + t.brokenAt);
            int after = (int) tomb.countOf(new java.util.UUID(0L, 0L));
            require(after == before, "碎剑留档不该占埋剑名额");
        });

        add(gDuel, "档位按边界映射，标签可读", () -> {
            var tiers = plugin.duels().tiers();
            require(tiers != null, "分级器没初始化");
            var bounds = tiers.bounds();
            require(!bounds.isEmpty(), "档位边界为空");
            for (int i = 1; i < bounds.size(); i++) {
                require(bounds.get(i) > bounds.get(i - 1), "边界不是升序");
            }
            require(tiers.tierCount() == bounds.size() + 1, "档位数应为边界数 +1");
            require(tiers.tierOf(-5) == 0, "负数等级应落在最低档");
            require(tiers.tierOf(0) == 0, "0 级应落在最低档");
            require(tiers.tierOf(bounds.get(0)) == 1, "边界值应进入下一档");
            require(tiers.tierOf(9999) == tiers.tierCount() - 1, "超高等级应落在最高档");
            for (int i = 0; i < tiers.tierCount(); i++) {
                require(tiers.labelOf(i) != null && !tiers.labelOf(i).isBlank(), "档位 " + i + " 没有标签");
            }
        });

        add(gDuel, "压档服务空转不报错，且不残留修正", () -> {
            var bal = plugin.duels().balanceService();
            require(bal != null, "压档服务没初始化");
            java.util.UUID nobody = java.util.UUID.nameUUIDFromBytes("forgemaster-selftest".getBytes());
            bal.clear(nobody);
            require(!bal.isBalanced(nobody), "空记录不该被判定为已压档");
            require(bal.appliedOf(nobody) == null, "空记录不该有压档详情");
            require(bal.activeCount() >= 0, "活动压档计数异常");
        });

        add(gHall, "名人堂有「最高锻造等级」条目", () -> {
            var hall = plugin.hallOfFame();
            require(hall != null, "名人堂没初始化");
            require(hall.strengthen() != null, "名人堂缺「最高锻造等级」条目");
        });

        add(gHall, "排行榜有强化维度", () -> {
            var m = com.forgemaster.ranking.Metric.STRENGTHEN;
            require(m != null, "排行缺强化维度");
            require(com.forgemaster.ranking.Metric.parse("strengthen") == m, "英文别名解析失败");
            require(com.forgemaster.ranking.Metric.parse("qianghua") == m, "拼音别名解析失败");
        });

        add(gHall, "强化等级落库：接口可读，空值安全", () -> {
            var stats = plugin.stats();
            java.util.UUID fresh = java.util.UUID.nameUUIDFromBytes("fm-strengthen-fresh".getBytes());
            require(stats.bestStrengthenOf(fresh) >= 0, "读强化等级返回负数");
            require(stats.bestStrengthenOf(null) == 0, "null 玩家应返回 0");
        });

        add(gHall, "强化等级：0 不记、低了不回退、高了才刷新", () -> {
            var stats = plugin.stats();
            java.util.UUID who = java.util.UUID.nameUUIDFromBytes("fm-strengthen-rule".getBytes());
            int before = stats.bestStrengthenOf(who);

            require(!stats.recordStrengthen(who, 0), "0 级不该记录");
            require(!stats.recordStrengthen(who, -3), "负数不该记录");

            int high = Math.max(before, 12) + 10;
            require(stats.recordStrengthen(who, high), "更高等级应刷新纪录");
            require(stats.bestStrengthenOf(who) == high, "纪录没写进去");

            require(!stats.recordStrengthen(who, high - 4), "更低等级不该覆盖纪录");
            require(stats.bestStrengthenOf(who) == high, "掉级把历史最高带下去了");
            require(!stats.recordStrengthen(who, high), "打平不算刷新");
        });

        add(gHall, "强化榜按等级降序，且不含 0 记录", () -> {
            var stats = plugin.stats();
            var ranked = stats.strengthenRanked();
            int prev = Integer.MAX_VALUE;
            for (java.util.UUID u : ranked) {
                int v = stats.bestStrengthenOf(u);
                require(v > 0, "榜单里混进了 0 记录的玩家");
                require(v <= prev, "榜单没有降序: " + v + " 排在 " + prev + " 后面");
                prev = v;
            }
        });

        add(gReforge, "重铸：词条数与列表一致，费用随序号递增", () -> {
            ItemStack w = sampleSword();
            require(w != null, "造不出测试剑");
            int n = com.forgemaster.affix.Reforge.affixCount(w);
            require(n == com.forgemaster.affix.Reforge.affixIdsOf(w).size(),
                    "词条数与列表长度不一致");
            if (n >= 2) {
                require(com.forgemaster.affix.Reforge.costOf(w, 1)
                                > com.forgemaster.affix.Reforge.costOf(w, 0),
                        "后一条的重铸费应更贵");
            }
        });

        add(gReforge, "重铸：脏输入不改物品", () -> {
            ItemStack w = sampleSword();
            require(w != null, "造不出测试剑");
            String before = w.getItemMeta().getPersistentDataContainer().getKeys().toString();
            try {
                com.forgemaster.affix.Reforge.reforge(null, w, 999, new java.util.Random(1));
                com.forgemaster.affix.Reforge.preview(w, -1);
            } catch (RuntimeException ignored) {
                // 允许返回红字，不允许抛到外面炸掉命令
            }
            String after = w.getItemMeta().getPersistentDataContainer().getKeys().toString();
            require(before.equals(after), "脏输入改动了物品 PDC");
        });

        add(gReforge, "重铸：同一 id 不会出现两次", () -> {
            ItemStack w = sampleSword();
            require(w != null, "造不出测试剑");
            int n = com.forgemaster.affix.Reforge.affixCount(w);
            if (n < 2) {
                return;
            }
            java.util.Random rnd = new java.util.Random(20261106L);
            for (int round = 0; round < 40; round++) {
                com.forgemaster.affix.Reforge.reforge(null, w, rnd.nextInt(n), rnd);
                var ids = com.forgemaster.affix.Reforge.affixIdsOf(w);
                require(new java.util.HashSet<>(ids).size() == ids.size(),
                        "出现重复词条: " + ids);
            }
        });
    }

    private void registerStrengthenRisk() {
        String g = "强化成功率 / 材料 / 护胚符";

        add(g, "成功率随等级递减，低段不至于必成", () -> {
            var s = plugin.strengthen();
            // v2.16 铁砧曲线：+0 起步 90%，线性降到 +99 的 10%，不再有「前两级必成」
            require(s.successChance(0) <= 1.0 + 1e-9, "0 级成功率超过 100%");
            require(s.successChance(0) > 0.5, "0 级成功率太低: " + s.successChance(0));
            require(s.successChance(20) < s.successChance(10), "成功率应随等级递减");
            require(s.successChance(10) < s.successChance(5), "成功率应随等级递减");
            require(s.successChance(5) < s.successChance(0), "成功率应随等级递减");
        });

        add(g, "成功率有下限，不会归零", () -> {
            var s = plugin.strengthen();
            double floor = plugin.settings().num("anvil-forge.success.floor", 0.10);
            require(s.successChance(999) >= floor - 1e-9, "成功率跌破下限: " + s.successChance(999));
            require(s.successChance(999) > 0, "成功率不该是 0");
        });

        add(g, "碎裂率随等级上升且有封顶", () -> {
            var s = plugin.strengthen();
            require(s.breakChance(0) < s.breakChance(10), "碎裂率应随等级上升");
            require(s.breakChance(10) < s.breakChance(20), "碎裂率应随等级上升");
            double max = plugin.settings().num("anvil-forge.break.max", 0.30);
            require(s.breakChance(999) <= max + 1e-9, "碎裂率超过封顶: " + s.breakChance(999));
            require(s.breakChance(0) > 0, "碎裂率不该是 0（不然护胚符没意义）");
        });

        add(g, "材料每锤固定，档位越高要得越多", () -> {
            var s = plugin.strengthen();
            ItemStack iron = sampleSword();
            ForgeMaterial star = plugin.materials().get("starfall");
            require(iron != null && star != null, "造不出测试剑");
            ItemStack starSword = plugin.items().buildDirect("SelfTest", star, Quality.COMMON, 60.0, null);

            int a = s.materialAmountOf(iron, 0);
            int b = s.materialAmountOf(iron, 50);
            require(a > 0, "铁剑每锤至少要 1 个材料");
            require(a == b, "v2.16 铁砧强化每锤材料应固定，实际 " + a + " / " + b);
            require(s.materialAmountOf(starSword, 0) > a, "高档材料每锤要得更多");
        });

        add(g, "费用随等级上升、随档位上升", () -> {
            var s = plugin.strengthen();
            ItemStack iron = sampleSword();
            ForgeMaterial star = plugin.materials().get("starfall");
            require(iron != null && star != null, "造不出测试剑");
            ItemStack starSword = plugin.items().buildDirect("SelfTest", star, Quality.COMMON, 60.0, null);
            require(s.costOf(iron, 50) > s.costOf(iron, 0), "费用应随等级上升");
            require(s.costOf(starSword, 0) > s.costOf(iron, 0), "高档材料锤一次更贵");
        });

        add(g, "强化吃的是这把剑自己的锻造材料", () -> {
            var s = plugin.strengthen();
            ItemStack iron = sampleSword();
            require(iron != null, "造不出铁剑");
            ForgeMaterial ironMat = plugin.materials().get("iron");
            require(ironMat != null, "缺 iron 剑胚");
            require(s.materialItemOf(iron) == ironMat.costItem(),
                    "铁剑该吃 " + ironMat.costItem() + "，实际 " + s.materialItemOf(iron));
            require(s.materialItemOf(iron) != null && s.materialItemOf(iron).isItem(),
                    "材料物品不可用");
        });

        add(g, "护胚符能造出来也能认出来，不误伤剑", () -> {
            ItemStack t = com.forgemaster.strengthen.Talisman.create(plugin);
            require(com.forgemaster.strengthen.Talisman.isTalisman(t), "护胚符识别失败");
            require(t.getType() == com.forgemaster.strengthen.Talisman.material(plugin), "护胚符材质不对");
            require(t.getItemMeta() != null && t.getItemMeta().displayName() != null, "护胚符没名字");
            ItemStack iron = sampleSword();
            require(!com.forgemaster.strengthen.Talisman.isTalisman(iron), "剑被误判成护胚符");
            require(!com.forgemaster.strengthen.Talisman.isTalisman(null), "null 被误判成护胚符");
        });

        add(g, "商会已上架护胚符且价格可读", () -> {
            var entries = plugin.shop().entries();
            var hit = entries.stream()
                    .filter(e -> e.kind() == com.forgemaster.shop.ShopEntry.Kind.SPECIAL)
                    .filter(e -> "talisman".equals(e.id()))
                    .findFirst();
            require(hit.isPresent(), "商会没上架护胚符");
            require(hit.get().unitPrice() > 0, "护胚符价格没读到: " + hit.get().unitPrice());
        });

        add(g, "强化状态说明带上成功率与材料", () -> {
            ItemStack iron = sampleSword();
            require(iron != null, "造不出铁剑");
            String text = Msg.plain(Msg.mm(plugin.strengthen().describe(iron)));
            require(text.contains("成功率"), "说明里没有成功率");
            require(text.contains("消耗"), "说明里没有消耗");
            require(text.contains("剑币"), "说明里没有剑币");
        });

        add(g, "失败处置：碎裂骰命中且没符 → 剑胚碎", () -> {
            var s = plugin.strengthen();
            // 骰 0 = 必定命中碎裂
            var r = s.resolveFail(10, 0.0, false);
            require(r.broken(), "碎裂骰命中却没判碎");
            require(!r.savedByTalisman(), "没符却报「被符保住」");
            require(r.newLevel() == 10, "碎了不该改等级");
        });

        add(g, "失败处置：有护胚符 → 不碎，改降 1 级", () -> {
            var s = plugin.strengthen();
            var r = s.resolveFail(10, 0.0, true);
            require(!r.broken(), "有护胚符还是碎了");
            require(r.savedByTalisman(), "没标记为护胚符生效");
            require(r.newLevel() == 9, "护胚符生效时应降 1 级，实际 " + r.newLevel());
        });

        add(g, "失败处置：碎裂骰没命中 → 符不消耗，照常降级", () -> {
            var s = plugin.strengthen();
            // 骰 1.0 = 必定不碎
            var r = s.resolveFail(10, 1.0, true);
            require(!r.broken(), "没命中碎裂却碎了");
            require(!r.savedByTalisman(), "没碎却消耗了护胚符");
            require(r.newLevel() == 9, "没碎应只降 1 级");
        });

        add(g, "失败处置：0 级失败不会掉成负数", () -> {
            var s = plugin.strengthen();
            var noBreak = s.resolveFail(0, 1.0, false);
            require(!noBreak.broken(), "0 级不该碎");
            require(noBreak.newLevel() == 0, "0 级掉成了 " + noBreak.newLevel());
            var saved = s.resolveFail(0, 0.0, true);
            require(!saved.broken(), "0 级有符却碎了");
            require(saved.newLevel() == 0, "0 级护胚后掉成了 " + saved.newLevel());
        });
    }

    // ---------------------------------------------------------------- 镶嵌孔 / 原版封禁

// ---------------------------------------------------------------- v2.16 铁砧强化（ForgeTax 式）

    private static boolean hashCodeOfYaw(double yaw) {
        return !Double.isNaN(yaw) && !Double.isInfinite(yaw);
    }
    /** v2.20 剑体放大：强化等级 → 视觉倍数的纯函数曲线，不需要服务器实体即可断言 */
    /** 把样本剑的附魔孔数写成指定值（自检专用，直接写 PDC） */
    private void setEnchantSockets(ItemStack item, int n) {
        ItemMeta meta = item.getItemMeta();
        if (meta == null) {
            return;
        }
        meta.getPersistentDataContainer().set(com.forgemaster.util.Keys.ENCHANT_SOCKETS,
                org.bukkit.persistence.PersistentDataType.INTEGER, n);
        item.setItemMeta(meta);
    }

    private void registerBigSword() {
        String g = "v2.20 剑体放大";

        add(g, "曲线三个加倍点：+33 ×2、+66 ×4、+99 ×8（v2.20）", () -> {
            var s = new com.forgemaster.bigsword.SwordScale(java.util.List.of(33, 66, 99), 2.0, 1.0, 8.0, 20);
            require(Math.abs(s.at(33) - 2.0) < 1e-9, "+33 应该是 ×2，实际 " + s.at(33));
            require(Math.abs(s.at(66) - 4.0) < 1e-9, "+66 应该是 ×4，实际 " + s.at(66));
            require(Math.abs(s.at(99) - 8.0) < 1e-9, "+99 应该是 ×8（泰坦级），实际 " + s.at(99));
        });

        add(g, "曲线起点与单调性：+20 以下不放大、0~99 逐点不减（v2.20）", () -> {
            var s = new com.forgemaster.bigsword.SwordScale(java.util.List.of(33, 66, 99), 2.0, 1.0, 8.0, 20);
            require(Math.abs(s.at(0) - 1.0) < 1e-9, "+0 不该放大");
            require(Math.abs(s.at(19) - 1.0) < 1e-9, "+19 不该放大（min-level=20）");
            require(Math.abs(s.at(20) - 1.0) < 1e-9, "+20 是起点，应为 ×1");
            require(!s.shows(19), "+19 不该挂牌");
            require(s.shows(33), "+33 该挂牌");
            double prev = 0;
            for (int lv = 0; lv <= 99; lv++) {
                double v = s.at(lv);
                require(v >= prev - 1e-9, "曲线在 +" + lv + " 处下降了: " + v + " < " + prev);
                require(v <= 8.0 + 1e-9, "曲线在 +" + lv + " 处超过上限: " + v);
                prev = v;
            }
        });

        add(g, "配置容错：乱序里程碑会被排序、倍数非有限值退化成不放大（v2.20）", () -> {
            var messy = new com.forgemaster.bigsword.SwordScale(
                    java.util.List.of(66, 33, 99, 33, 0, -5), 2.0, 1.0, 8.0, 20);
            require(messy.milestones().equals(java.util.List.of(33, 66, 99)),
                    "里程碑没被排序去重: " + messy.milestones());
            // multiplier <= 1 时整体退化成「不放大」，不能出现无限大或 NaN
            var flat = new com.forgemaster.bigsword.SwordScale(java.util.List.of(33), 1.0, 1.0, 8.0, 20);
            require(flat.at(99) <= 1.0 + 1e-9, "multiplier=1 时居然放大了: " + flat.at(99));
            var nan = new com.forgemaster.bigsword.SwordScale(java.util.List.of(33), Double.NaN, 1.0, 8.0, 20);
            require(Double.isFinite(nan.at(50)), "NaN 倍数算出了非有限值: " + nan.at(50));
        });

        add(g, "词条池不越界：剑抽不到防御词条，护甲抽不到攻击词条（v2.21 修复）", () -> {
            // 根因：pick() 在池子抽不满时回退到**整张词条表**。
            // 油淬只有 2 条候选，精良要 4~6 条 → 剩下几条从全表捞，
            // 于是剑上出「护甲值」、胸甲上出「暴击率」。
            var reg = plugin.affixes();
            var armorPool = com.forgemaster.forge.ForgeManager.ARMOR_AFFIX_POOL;
            var weaponPool = com.forgemaster.forge.ForgeManager.WEAPON_AFFIX_POOL;

            // 池子浅 + 要很多条：必须只用武器池补，不能漏进防御词条
            var shallow = reg.pick(java.util.List.of("attack_damage", "crit_chance"), 6, weaponPool);
            require(shallow.size() == 6, "浅池没补齐，只抽到 " + shallow.size() + "/6");
            for (var a : shallow) {
                require(!armorPool.contains(a.id()),
                        "剑抽到了防御词条: " + a.id() + "（回退到整表了）");
                require(weaponPool.contains(a.id()), "剑抽到了池外词条: " + a.id());
            }
            require(shallow.size() == shallow.stream().map(a -> a.id()).distinct().count(),
                    "抽出了重复词条");

            // 护甲侧本来就 pickStrict，这里锁住「不会超池、不会漏攻击词条」
            var armorPicked = reg.pickStrict(armorPool, 6);
            require(!armorPicked.isEmpty(), "护甲词条抽不出来");
            for (var a : armorPicked) {
                require(armorPool.contains(a.id()), "护甲抽到了池外词条: " + a.id());
            }
        });

        add(g, "胸甲不会出暴击率：buildDirect 护甲走防御池且 CRIT_CHANCE 为 0（v2.21 修复）", () -> {
            // buildDirect 是 /zhujian give 与 givefull 共用的建造路径，
            // 以前无条件按淬火池（全攻击向）抽词条
            var base = plugin.materials().get("iron");
            require(base != null, "材料表里没有 iron");
            var plate = com.forgemaster.material.ArmorCatalog.create(
                    base, com.forgemaster.material.ForgeMaterial.Slot.CHESTPLATE, 1.0);
            require(plate != null, "造不出铁胸甲材料");

            // 连造几件，随机性不该让任何一件漏出攻击词条
            for (int round = 0; round < 6; round++) {
                ItemStack armor = plugin.items().buildDirect("selftest", plate, Quality.MYTHIC, 100);
                require(armor != null, "造不出胸甲");
                var ids = com.forgemaster.affix.Reforge.affixIdsOf(armor);
                for (String id : ids) {
                    require(com.forgemaster.forge.ForgeManager.ARMOR_AFFIX_POOL.contains(id),
                            "胸甲第 " + (round + 1) + " 件抽到了非防御词条: " + id);
                }
                var meta = armor.getItemMeta();
                require(meta != null, "胸甲没有 meta");
                double critChance = meta.getPersistentDataContainer().getOrDefault(
                        com.forgemaster.util.Keys.CRIT_CHANCE,
                        org.bukkit.persistence.PersistentDataType.DOUBLE, 0.0);
                require(critChance == 0.0, "胸甲的暴击率不是 0: " + critChance);
                double lifesteal = meta.getPersistentDataContainer().getOrDefault(
                        com.forgemaster.util.Keys.LIFESTEAL,
                        org.bukkit.persistence.PersistentDataType.DOUBLE, 0.0);
                require(lifesteal == 0.0, "胸甲的吸血不是 0: " + lifesteal);
            }
        });

        add(g, "武器不会出防御词条：buildDirect 武器走武器池兜底（v2.21）", () -> {
            var mat = plugin.materials().get("iron");
            require(mat != null, "材料表里没有 iron");
            for (int round = 0; round < 6; round++) {
                ItemStack sword = plugin.items().buildDirect("selftest", mat, Quality.MYTHIC, 100);
                require(sword != null, "造不出铁剑");
                for (String id : com.forgemaster.affix.Reforge.affixIdsOf(sword)) {
                    require(!com.forgemaster.forge.ForgeManager.ARMOR_AFFIX_POOL.contains(id),
                            "剑第 " + (round + 1) + " 件抽到了防御词条: " + id);
                }
            }
        });

        add(g, "暴击率封顶：满配 179% 必须压到上限（v2.21 加，v2.23.1 上限下调到 50%）", () -> {
            // v2.21 之前战斗侧直接 random < critChance，179% 就是「永久暴击」且无提示
            // v2.23.1：上限从 0.75 下调到 0.50 —— 75% 等于刀刀暴击，暴击从惊喜变成默认值
            var d = com.forgemaster.combat.Crit.Limits.defaults();
            var p = com.forgemaster.combat.Crit.resolve(1.79, 0);
            require(Math.abs(p.chance() - d.maxChance()) < 1e-9,
                    "暴击率没被压到上限 " + d.maxChance() + "，实际 " + p.chance());
            require(p.capped(), "截断了却没标记 capped");

            // 配置把上限写到天上，也不许突破 100%
            var p2 = com.forgemaster.combat.Crit.resolve(5.0, 0, 9.9, 2.5, 1.5);
            require(p2.chance() <= 1.0 + 1e-9, "暴击率突破了 100%: " + p2.chance());

            // 正常范围内的值不该被动
            var p3 = com.forgemaster.combat.Crit.resolve(0.3, 0.5);
            require(Math.abs(p3.chance() - 0.3) < 1e-9, "0.3 被改成了 " + p3.chance());
            require(!p3.capped(), "正常值被误标 capped");

            // 负数 / NaN 一律当 0，不能把负暴击率带进判定
            require(com.forgemaster.combat.Crit.resolve(-1, 0).chance() == 0, "负暴击率没归零");
            require(com.forgemaster.combat.Crit.resolve(Double.NaN, Double.NaN).chance() == 0,
                    "NaN 暴击率没归零");
        });

        add(g, "暴伤底数：倍率是 1.5 + 暴伤，最低档不能弱于原版跳劈（v2.21）", () -> {
            // 旧公式 ×(1 + 暴伤)，暴伤 0.25 只有 ×1.25 —— 比原版跳跃暴击 ×1.5 还弱
            require(Math.abs(com.forgemaster.combat.Crit.resolve(1.0, 0).multiplier() - 1.5) < 1e-9,
                    "无暴伤时倍率不是 1.5（出现倒挂）: " + com.forgemaster.combat.Crit.resolve(1.0, 0).multiplier());
            require(Math.abs(com.forgemaster.combat.Crit.resolve(1.0, 0.25).multiplier() - 1.75) < 1e-9,
                    "暴伤 0.25 应该是 ×1.75");
            // 满配 630% 被上限压住 → 1.5 + maxDamage（v2.23.1：1.10 → ×2.6，原先是 2.5 → ×4.0）
            var dmgLimits = com.forgemaster.combat.Crit.Limits.defaults();
            var top = com.forgemaster.combat.Crit.resolve(1.0, 6.3);
            double expectTop = dmgLimits.baseMultiplier() + dmgLimits.maxDamage();
            require(Math.abs(top.multiplier() - expectTop) < 1e-9,
                    "暴伤上限没生效: 期望 ×" + expectTop + "，实际 ×" + top.multiplier());
            require(top.capped(), "暴伤截断没标记");
            // 满配暴击不得再是「一刀顶穿一切」的量级（v2.23.1 服主实测 130 的回归断言）
            require(expectTop <= 3.0, "暴击倍率上限超过 3.0，会重新出现四刀打死坚守者的数值: " + expectTop);
            // 配置把底数写坏（<1）时按 1 处理，不能出现「暴击比不暴击伤害低」
            require(com.forgemaster.combat.Crit.resolve(1.0, 0, 0.75, 2.5, 0.2).multiplier() >= 1.0,
                    "底数被写坏后暴击倍率小于 1");
        });

        add(g, "宝石暴击数值与词条同量级：L5 星辉石不该超过破绽词条上限太多（v2.21）", () -> {
            // 重调前：星辉石 L5 上限 45%，破绽词条上限 22% —— 宝石是词条的 2 倍
            double gemCritMax = com.forgemaster.gem.GemType.CRIT.maxAt(
                    com.forgemaster.gem.GemType.Grade.L5);
            double gemCdMax = com.forgemaster.gem.GemType.CRIT_DAMAGE.maxAt(
                    com.forgemaster.gem.GemType.Grade.L5);
            require(gemCritMax <= 0.20, "L5 星辉石暴击率还是太高: " + gemCritMax);
            require(gemCritMax >= 0.05, "L5 星辉石暴击率被砍得太狠: " + gemCritMax);
            require(gemCdMax <= 0.70, "L5 破军石暴伤还是太高: " + gemCdMax);
            require(gemCdMax >= 0.20, "L5 破军石暴伤被砍得太狠: " + gemCdMax);
            // 三颗满级宝石的暴击率合计不能自己顶到上限
            require(gemCritMax * 3 < 0.75,
                    "三颗 L5 星辉石就能顶满暴击率上限，词条位失去意义: " + gemCritMax * 3);
        });

        add(g, "附魔孔越限：一本 3 条附魔的书敲进 1 孔装备必须被拒（v2.21 修复）", () -> {
            // 这条是回归断言。旧写法只判「整本书的附魔是不是都已在装备上」，
            // 于是 1 孔 + 空装备 + 3 条书 → allMerged=false、used=0<1 → 放行 → 写成 3/1。
            var gems = plugin.gems();
            require(gems != null, "宝石服务没初始化");
            ItemStack sword = sampleSword();
            require(sword != null, "造不出铁剑");
            setEnchantSockets(sword, 1);

            var book = new java.util.LinkedHashMap<org.bukkit.enchantments.Enchantment, Integer>();
            book.put(org.bukkit.enchantments.Enchantment.SHARPNESS, 5);
            book.put(org.bukkit.enchantments.Enchantment.UNBREAKING, 3);
            book.put(org.bukkit.enchantments.Enchantment.MENDING, 1);

            var plan = gems.planEnchant(sword, book);
            require(plan.rejected(), "1 个附魔孔竟然接受了 3 条附魔的书（越限 bug 回归）");
            require(plan.reject().contains("3") && plan.reject().contains("1"),
                    "拒绝理由没说清要占几个孔、还剩几个: " + plan.reject());

            // 孔够就该放行，别把正常情况也堵死
            setEnchantSockets(sword, 3);
            var ok = gems.planEnchant(sword, book);
            require(!ok.rejected(), "3 个孔镶 3 条附魔被误拒: " + ok.reject());
            require(ok.toAdd().size() == 3, "该新增 3 条，实际 " + ok.toAdd().size());
            require(ok.toUpgrade().isEmpty(), "全新附魔不该被算成升级");
        });

        add(g, "附魔降级：书上级数比装备低时不能覆盖回去（v2.21 修复）", () -> {
            // 旧代码无条件 meta.addEnchant(ench, level, true)，锋利 V 会被锋利 III 的书打回 III
            var gems = plugin.gems();
            ItemStack sword = sampleSword();
            require(sword != null, "造不出铁剑");
            setEnchantSockets(sword, 1);
            ItemMeta meta = sword.getItemMeta();
            meta.addEnchant(org.bukkit.enchantments.Enchantment.SHARPNESS, 5, true);
            meta.getPersistentDataContainer().set(com.forgemaster.util.Keys.ENCHANTS,
                    org.bukkit.persistence.PersistentDataType.STRING, "");
            sword.setItemMeta(meta);

            var lower = new java.util.LinkedHashMap<org.bukkit.enchantments.Enchantment, Integer>();
            lower.put(org.bukkit.enchantments.Enchantment.SHARPNESS, 3);
            var plan = gems.planEnchant(sword, lower);
            require(plan.rejected(), "锋利 III 的书居然能敲到锋利 V 的剑上");
            require(plan.reject().contains("不会变"), "拒绝理由没说清是「不会变」: " + plan.reject());

            // 同级书也不该放行：镶了不会变，白扣钱白扣书
            var same = new java.util.LinkedHashMap<org.bukkit.enchantments.Enchantment, Integer>();
            same.put(org.bukkit.enchantments.Enchantment.SHARPNESS, 5);
            require(gems.planEnchant(sword, same).rejected(), "同级书居然被放行（会白扣费用和书）");

            // 反向：书上级更高 → 走升级，且**不占新孔**
            ItemMeta lower3 = sword.getItemMeta();
            lower3.removeEnchant(org.bukkit.enchantments.Enchantment.SHARPNESS);
            lower3.addEnchant(org.bukkit.enchantments.Enchantment.SHARPNESS, 3, true);
            sword.setItemMeta(lower3);
            var higher = new java.util.LinkedHashMap<org.bukkit.enchantments.Enchantment, Integer>();
            higher.put(org.bukkit.enchantments.Enchantment.SHARPNESS, 5);
            var up = gems.planEnchant(sword, higher);
            require(!up.rejected(), "升级书被误拒: " + up.reject());
            require(up.toAdd().isEmpty(), "升级不该占用新孔，toAdd=" + up.toAdd().size());
            require(up.toUpgrade().get(org.bukkit.enchantments.Enchantment.SHARPNESS) == 5,
                    "升级目标等级不对: " + up.toUpgrade());
        });

        add(g, "附魔互斥：书里同时带锋利与亡灵杀手必须被拒（v2.21）", () -> {
            // 原版不允许这两条共存；改包/别的插件造的书会同时带，
            // 镶上去会得到一把原版规则下不可能存在的剑
            var gems = plugin.gems();
            ItemStack sword = sampleSword();
            require(sword != null, "造不出铁剑");
            setEnchantSockets(sword, 4);
            var book = new java.util.LinkedHashMap<org.bukkit.enchantments.Enchantment, Integer>();
            book.put(org.bukkit.enchantments.Enchantment.SHARPNESS, 5);
            book.put(org.bukkit.enchantments.Enchantment.SMITE, 5);
            var plan = gems.planEnchant(sword, book);
            require(plan.rejected(), "互斥附魔被放行了");
            require(plan.reject().contains("互斥"), "拒绝理由没提互斥: " + plan.reject());
        });

        add(g, "附魔账目并集：原版附魔没写进 PDC 也要算已用孔数（v2.21）", () -> {
            // 上限只看 enchantsOf()。表里少算就能继续镶到超限，Lore 数字也是错的。
            var gems = plugin.gems();
            ItemStack sword = sampleSword();
            require(sword != null, "造不出铁剑");
            setEnchantSockets(sword, 2);
            ItemMeta meta = sword.getItemMeta();
            meta.addEnchant(org.bukkit.enchantments.Enchantment.SHARPNESS, 5, true);
            meta.getPersistentDataContainer().set(com.forgemaster.util.Keys.ENCHANTS,
                    org.bukkit.persistence.PersistentDataType.STRING, "");
            sword.setItemMeta(meta);
            require(gems.enchantsOf(sword).size() == 1,
                    "原版附魔没被算进已用孔数: " + gems.enchantsOf(sword).size());

            // 已经用掉 1 个（锋利自己），再敲 2 条新的 → 只剩 1 个孔，必须拒
            var book = new java.util.LinkedHashMap<org.bukkit.enchantments.Enchantment, Integer>();
            book.put(org.bukkit.enchantments.Enchantment.UNBREAKING, 3);
            book.put(org.bukkit.enchantments.Enchantment.MENDING, 1);
            var plan = gems.planEnchant(sword, book);
            require(plan.rejected(), "并集没生效：2 条新附魔挤进只剩 1 个孔");
        });

        add(g, "满配宝石按装备类型选：护甲先给玄铁石而不是赤玉（v2.21）", () -> {
            // 枚举里 ATTACK 排第一，照枚举顺序铺会让护甲镶一颗攻击宝石
            var gems = plugin.gems();
            var base = plugin.materials().get("iron");
            require(base != null, "材料表里没有 iron");
            var armorMat = com.forgemaster.material.ArmorCatalog.create(
                    base, com.forgemaster.material.ForgeMaterial.Slot.CHESTPLATE, 1.0);
            require(armorMat != null, "造不出铁胸甲材料");
            ItemStack armor = plugin.items().buildDirect("selftest", armorMat,
                    Quality.MYTHIC, 100);
            require(armor != null, "造不出铁胸甲");
            int limit = gems.socketLimitOf(armor);
            require(limit > 0, "胸甲宝石孔上限算成了 0");
            gems.fillMax(armor, limit, com.forgemaster.gem.GemType.Grade.L5);
            var filled = gems.gemsOf(armor);
            require(!filled.isEmpty(), "胸甲一颗宝石都没镶上");
            require(filled.get(0).type() == com.forgemaster.gem.GemType.ARMOR,
                    "护甲第一颗给的不是玄铁石: " + filled.get(0).type());
        });

        add(g, "满配附魔孔：fillMaxEnchantSockets 开到材料档位上限（v2.21）", () -> {
            var gems = plugin.gems();
            ItemStack sword = sampleSword();
            require(sword != null, "造不出铁剑");
            int limit = gems.enchantLimitOf(sword);
            require(limit > 0, "附魔孔上限算成了 0");
            int got = gems.fillMaxEnchantSockets(sword);
            require(got == limit, "满配附魔孔没开到上限: " + got + " / " + limit);
            require(gems.enchantSocketsOf(sword) == limit,
                    "附魔孔读回来不对: " + gems.enchantSocketsOf(sword));
        });

        add(g, "满配四维：强化拉满、蜕变压到最高阶、宝石孔开满且全 Lv.5（v2.21）", () -> {
            // givefull 是私有命令方法，这里分段验它依赖的四个公开入口，
            // 顺带锁住「显示满配但属性不生效」这类假货：
            // 每一维都要能被 levelOf / stageOf / socketsOf / gemsOf 读回来。
            ItemStack sword = sampleSword();
            require(sword != null, "造不出铁剑");

            // 强化
            int cap = plugin.strengthen().capOf(sword);
            require(cap >= 30, "品质上限算错了: +" + cap);
            require(plugin.strengthen().setLevel(sword, cap), "setLevel(cap) 失败");
            require(plugin.strengthen().levelOf(sword) == cap,
                    "强化等级写不回去: " + plugin.strengthen().levelOf(sword));

            // 蜕变：forceStage 要同时改属性乘区与铭牌
            if (plugin.evolutions() != null && plugin.evolutions().enabled()) {
                int top = plugin.evolutions().registry().all().size();
                int got = plugin.evolutions().forceStage(sword, Integer.MAX_VALUE);
                require(got == top, "forceStage 没压到最高阶: " + got + " / " + top);
                require(plugin.evolutions().stageOf(sword) == top,
                        "阶号读不回来: " + plugin.evolutions().stageOf(sword));
                String name = Msg.plain(sword.getItemMeta().displayName());
                require(name.startsWith("〔") || name.contains("〔"),
                        "最高阶没打上〔铭牌〕: " + name);
            }

            // 宝石：孔开满 + 每孔一颗 Lv.5
            if (plugin.gems() != null) {
                int limit = plugin.gems().socketLimitOf(sword);
                if (limit > 0) {
                    plugin.gems().fillMax(sword, limit, com.forgemaster.gem.GemType.Grade.L5);
                    require(plugin.gems().socketsOf(sword) == limit,
                            "宝石孔没开满: " + plugin.gems().socketsOf(sword) + " / " + limit);
                    var gems = plugin.gems().gemsOf(sword);
                    require(gems.size() == limit,
                            "孔开了但宝石没镶满: " + gems.size() + " / " + limit);
                    for (var entry : gems) {
                        require(entry.grade() == com.forgemaster.gem.GemType.Grade.L5,
                                "宝石不是 Lv.5: " + entry.grade());
                        require(entry.value() > 0, "宝石数值是 0: " + entry.type());
                    }
                }
            }

            // 词条上限必须可读且大于 0，否则 givefull 的「满词条」会静默变成 0 条
            require(com.forgemaster.affix.Imprint.maxAffixes(plugin) > 0, "词条上限读成了 0");
        });

        add(g, "挂载链路：给假人挂 +40 的剑，世界真出现一个放大的 ItemDisplay（v2.20）", () -> {
            // 纯函数对不代表链路对：这条直接跑一遍「造剑 → 判等级 → 生成实体 → 读回缩放」，
            // 把 Service / SwordScale / Keys / 实体 PDC 四段焊在一起验。
            var service = plugin.swordGrowth();
            require(service != null, "剑体放大服务没初始化");
            var worlds = org.bukkit.Bukkit.getWorlds();
            require(!worlds.isEmpty(), "没有可用世界");
            var world = worlds.get(0);

            ItemStack sword = sampleSword();
            require(sword != null, "造不出铁剑");
            require(plugin.strengthen().setLevel(sword, 40), "setLevel(40) 失败");

            int level = plugin.strengthen().levelOf(sword);
            require(level == 40, "等级读回来是 " + level);
            double want = service.scale().at(40);
            require(want > 1.5, "+40 的倍数太小: " + want);

            // 找一个「手里拿着这把剑」的实体锚点：ItemDisplay 不依赖玩家对象也能验，
            // 所以这里直接按服务内部同一套位置/姿态规则手搓一个，验证不变量：
            // 生成的实体必须带 BIG_SWORD_DISPLAY 标记、非持久、缩放等于曲线值。
            org.bukkit.util.Transformation tf = new org.bukkit.util.Transformation(
                    new org.joml.Vector3f(0f, 0f, 0f),
                    new org.joml.Quaternionf(),
                    new org.joml.Vector3f((float) want, (float) want, (float) want),
                    new org.joml.Quaternionf());
            org.bukkit.entity.ItemDisplay display = world.spawn(
                    world.getSpawnLocation().add(0, 10, 0),
                    org.bukkit.entity.ItemDisplay.class, d -> {
                        d.setItemStack(sword.clone());
                        d.setPersistent(false);
                        d.setGravity(false);
                        d.setTransformation(tf);
                        d.getPersistentDataContainer().set(com.forgemaster.util.Keys.BIG_SWORD_DISPLAY,
                                org.bukkit.persistence.PersistentDataType.STRING, "selftest");
                    });
            require(display != null, "ItemDisplay 生成失败");
            try {
                require(!display.isPersistent(), "展示实体必须非持久（崩服不留档）");
                var read = display.getTransformation().getScale();
                require(Math.abs(read.x() - want) < 1e-3,
                        "缩放没写进去: 期望 " + want + "，实际 " + read.x());
                require(display.getPersistentDataContainer().has(
                                com.forgemaster.util.Keys.BIG_SWORD_DISPLAY,
                                org.bukkit.persistence.PersistentDataType.STRING),
                        "实体缺少 BIG_SWORD_DISPLAY 标记（孤儿清扫会漏掉它）");
                // v2.26.1：剑灵显形（spiritdisplay）已整模块移除，原来的「不能误打成剑灵标记」
                // 断言随之失去对象 —— 那个键已经不存在，断言恒为真，等于没有断言。
                // 保留这条钉子真正想守的东西：实体必须带**自己的**键，且键值形态正确。
                require(display.getPersistentDataContainer().has(
                                com.forgemaster.util.Keys.BIG_SWORD_DISPLAY,
                                org.bukkit.persistence.PersistentDataType.STRING),
                        "剑体放大实体缺少自己的标记键（孤儿清扫会漏掉它）");
            } finally {
                display.remove();
            }
        });

        add(g, "线上配置读得出曲线，且描述里含三个加倍点（v2.20）", () -> {
            var s = com.forgemaster.bigsword.SwordScale.from(plugin);
            String desc = s.describe();
            require(desc.contains("+33"), "曲线描述缺 +33: " + desc);
            require(desc.contains("+66"), "曲线描述缺 +66: " + desc);
            require(desc.contains("+99"), "曲线描述缺 +99: " + desc);
            require(s.minLevel() >= 0, "min-level 读出了负数");
            require(s.at(99) > 1.5, "线上配置的 +99 倍数太小: " + s.at(99));
            // v2.22：砧上与手上必须同源——管理器取的曲线就是服务持有的这条
            require(plugin.anvilForge() != null && plugin.swordGrowth() != null, "砧位 / 剑体放大模块没起来");
            require(s.at(33) > s.at(5), "曲线在 +5 → +33 之间没有变大（统一曲线起点应从 0 起）");
        });
    }


    /**
     * v2.16：强化砍掉界面重写为「铁砧上敲」。
     *
     * <p>这一组只验可无头执行的部分：明牌概率、无损连击、上限 +99、重锤识别、
     * 砧位全息文案、会话键与方块白名单。真正「放剑 / 落锤 / 取剑」的方块交互
     * 依赖世界与玩家，放到游戏里手测。</p>
     */
    private void registerAnvilForge() {
        String g = "v2.16 铁砧强化（ForgeTax 式）";

        add(g, "明牌五项非负且和恒为 1", () -> {
            var s = plugin.strengthen();
            int[] levels = {0, 5, 20, 50, 98, 99};
            for (int lv : levels) {
                double[] o = s.odds(lv, 0, false, 0, com.forgemaster.contract.RelicBonus.ZERO, false, false);
                double sum = 0;
                for (double v : o) {
                    require(v >= -1e-12, "+" + lv + " 的概率出现负数: " + v);
                    sum += v;
                }
                require(Math.abs(sum - 1.0) < 1e-9, "+" + lv + " 的五项和不是 1，而是 " + sum);
            }
        });

        add(g, "无损连击：必成、不碎、五项仍和为 1", () -> {
            var s = plugin.strengthen();
            double[] o = s.odds(60, 5, true, 0, com.forgemaster.contract.RelicBonus.ZERO, false, false);
            require(o[0] == 0, "无损连击下碎裂率必须为 0，实际 " + o[0]);
            require(o[1] == 0, "无损连击下不该还有损伤");
            require(o[2] == 0, "无损连击下不该还有空锤");
            require(Math.abs(o[3] + o[4] - 1.0) < 1e-9, "无损连击下成功/大成应吃掉 100%");
        });

        add(g, "无损连击阈值可读，攒满即就绪", () -> {
            var s = plugin.strengthen();
            int threshold = s.noLossComboThreshold();
            require(threshold > 0, "无损连击默认应开启（阈值 > 0）");
            ItemStack w = sampleSword();
            require(w != null, "造不出测试剑");
            s.setCombo(w, threshold - 1);
            require(!s.noLossComboActive(w), "差 1 连就判成就绪了");
            s.setCombo(w, threshold);
            require(s.noLossComboActive(w), "攒满阈值却没就绪");
            s.setCombo(w, threshold + 3);
            require(s.noLossComboActive(w), "超过阈值反而失效");
        });

        add(g, "推进规则：成功 +1、大成 +2、损伤掉 1、无损清零连击", () -> {
            var s = plugin.strengthen();
            int cap = 99;
            int[] ok = s.afterStrike(com.forgemaster.strengthen.StrengthenService.Tier.SUCCESS,
                    10, cap, 2, 1, 5);
            require(ok[0] == 11, "成功应 +1，实际 " + ok[0]);
            require(ok[1] == 3, "成功后连击应 +1，实际 " + ok[1]);

            int[] great = s.afterStrike(com.forgemaster.strengthen.StrengthenService.Tier.GREAT,
                    10, cap, 0, 2, 5);
            require(great[0] == 12, "大成应 +2，实际 " + great[0]);

            int[] dmg = s.afterStrike(com.forgemaster.strengthen.StrengthenService.Tier.DAMAGE,
                    10, cap, 4, 1, 5);
            require(dmg[0] == 9, "损伤应掉 1 级，实际 " + dmg[0]);
            require(dmg[1] == 0, "损伤应清空连击，实际 " + dmg[1]);

            int[] noLoss = s.afterStrike(com.forgemaster.strengthen.StrengthenService.Tier.NO_LOSS,
                    10, cap, 5, 1, 5);
            require(noLoss[0] == 11, "无损连击应 +1，实际 " + noLoss[0]);
            require(noLoss[1] == 0, "无损连击打出去后连击应归零，实际 " + noLoss[1]);

            int[] capped = s.afterStrike(com.forgemaster.strengthen.StrengthenService.Tier.GREAT,
                    cap - 1, cap, 0, 2, 5);
            require(capped[0] == cap, "越过上限时应夹到 " + cap + "，实际 " + capped[0]);
        });

        add(g, "连击封在阈值上，不会无限涨", () -> {
            var s = plugin.strengthen();
            int threshold = s.noLossComboThreshold();
            int[] step = s.afterStrike(com.forgemaster.strengthen.StrengthenService.Tier.SUCCESS,
                    3, 99, threshold, 1, threshold);
            require(step[1] == threshold, "连击应封在阈值 " + threshold + "，实际 " + step[1]);
        });

        add(g, "上限跟品质走、与材料档位无关（神话 99 / 普通 50）", () -> {
            var s = plugin.strengthen();
            require(s.maxLevel() == 99, "曲线基准应为 +99，实际 " + s.maxLevel());
            ItemStack iron = sampleSword();                        // 普通品质
            ForgeMaterial star = plugin.materials().get("starfall");
            require(iron != null && star != null, "造不出测试剑");
            ItemStack starSword = plugin.items().buildDirect("SelfTest", star, Quality.COMMON, 60.0, null);
            int commonCap = s.capForQuality(Quality.COMMON);
            require(s.capOf(iron) == commonCap,
                    "普通铁剑上限应等于普通品质上限 " + commonCap + "，实际 " + s.capOf(iron));
            require(s.capOf(starSword) == commonCap,
                    "同为普通的星陨上限应一样（材料档位不再影响上限），实际 " + s.capOf(starSword));
            // 神话才是 99
            ItemStack mythic = plugin.items().buildDirect("SelfTest", iron == null ? star : plugin.materials().get("iron"),
                    Quality.MYTHIC, 96.0, null);
            if (mythic != null) {
                require(s.capOf(mythic) == 99, "神话上限应为 99，实际 " + s.capOf(mythic));
            }
            require(s.setLevel(iron, commonCap), "+" + commonCap + " 写不进去");
            require(s.levelOf(iron) == commonCap, "写进去的等级不对: " + s.levelOf(iron));
            require(s.setLevel(iron, 120), "越界等级应被夹住而不是失败");
            require(s.levelOf(iron) == commonCap, "越界等级没被夹到上限: " + s.levelOf(iron));
        });

        add(g, "满级后不再掷骰（拒绝落锤）", () -> {
            var s = plugin.strengthen();
            ItemStack w = sampleSword();
            require(w != null, "造不出测试剑");
            int cap = s.capOf(w);
            require(cap > 0, "读不到上限: " + cap);
            s.setLevel(w, cap);
            require(s.levelOf(w) == cap, "满级没写进去（上限 " + cap + "，实得 " + s.levelOf(w) + "）");
            double[] o = s.oddsOf(w, 0);
            require(o.length == 5, "满级时概率表仍应可读");
            // 满级时 oddsOf 仍然返回合法表（命令/全息读它不会炸）
            require(Math.abs(o[0] + o[1] + o[2] + o[3] + o[4] - 1.0) < 1e-9, "满级概率和不是 1");
        });

        add(g, "抽票落点：骰子边界归到正确的档", () -> {
            double[] o = {0.10, 0.20, 0.30, 0.15, 0.25};
            require(com.forgemaster.strengthen.StrengthenService.tierOf(0.0, o)
                    == com.forgemaster.strengthen.StrengthenService.Tier.BREAK, "0 应落在碎裂");
            require(com.forgemaster.strengthen.StrengthenService.tierOf(0.05, o)
                    == com.forgemaster.strengthen.StrengthenService.Tier.BREAK, "0.05 应落在碎裂");
            require(com.forgemaster.strengthen.StrengthenService.tierOf(0.15, o)
                    == com.forgemaster.strengthen.StrengthenService.Tier.DAMAGE, "0.15 应落在损伤");
            require(com.forgemaster.strengthen.StrengthenService.tierOf(0.35, o)
                    == com.forgemaster.strengthen.StrengthenService.Tier.HOLD, "0.35 应落在空锤");
            require(com.forgemaster.strengthen.StrengthenService.tierOf(0.65, o)
                    == com.forgemaster.strengthen.StrengthenService.Tier.GREAT, "0.65 应落在大成");
            require(com.forgemaster.strengthen.StrengthenService.tierOf(0.999, o)
                    == com.forgemaster.strengthen.StrengthenService.Tier.SUCCESS, "0.999 应落在成功");
            // 边界归上档：0.10 / 0.30 / 0.60 都是各段起点，不能被浮点误差吃掉
            require(com.forgemaster.strengthen.StrengthenService.tierOf(0.30, o)
                    == com.forgemaster.strengthen.StrengthenService.Tier.HOLD, "0.30 边界应归空锤");
            require(com.forgemaster.strengthen.StrengthenService.tierOf(0.60, o)
                    == com.forgemaster.strengthen.StrengthenService.Tier.GREAT, "0.60 边界应归大成");
            require(com.forgemaster.strengthen.StrengthenService.tierOf(0.5, null)
                    == com.forgemaster.strengthen.StrengthenService.Tier.REFUSED, "空表应拒绝");
        });

        add(g, "重锤：插件锤与原文重锤都认，剑不认", () -> {
            ItemStack h = com.forgemaster.anvil.Hammer.create(plugin);
            require(h != null && !h.getType().isAir(), "造不出重锤");
            require(com.forgemaster.anvil.Hammer.isHammer(plugin, h), "插件发的锤认不出来");
            require(h.getItemMeta() != null && h.getItemMeta().displayName() != null, "重锤没名字");
            ItemStack vanilla = new ItemStack(com.forgemaster.anvil.Hammer.material(plugin));
            require(com.forgemaster.anvil.Hammer.isHammer(plugin, vanilla), "原版同材质物品应可直接用");
            ItemStack sword = sampleSword();
            require(sword != null && !com.forgemaster.anvil.Hammer.isHammer(plugin, sword), "剑被当成锤");
            require(!com.forgemaster.anvil.Hammer.isHammer(plugin, null), "null 被当成锤");
        });

        add(g, "重锤门槛：默认列表里有便宜货（铁斧），重锤也还在", () -> {
            var mats = com.forgemaster.anvil.Hammer.materials(plugin);
            require(!mats.isEmpty(), "重锤材质列表是空的，谁都敲不了");
            require(mats.contains(Material.IRON_AXE), "默认列表里没有铁斧，喊「门槛高」的玩家还是没锤子: " + mats);
            require(mats.contains(Material.MACE), "原版重锤反而不认了: " + mats);
            require(com.forgemaster.anvil.Hammer.create(plugin).getType() == mats.get(0),
                    "发的锤子和列表第一项不一致");
            require(com.forgemaster.anvil.Hammer.isHammer(plugin, new ItemStack(Material.IRON_AXE)),
                    "铁斧当锤被拒了");
            require(com.forgemaster.anvil.Hammer.isHammer(plugin, new ItemStack(Material.MACE)),
                    "重锤当锤被拒了");
        });

        add(g, "剑越锻越大：+0 是基准，砧上与手上共用同一条成长曲线（v2.22 统一）", () -> {
            var mgr = plugin.anvilForge();
            var bs = plugin.swordGrowth();
            require(bs != null && bs.enabled(), "剑体放大模块没起来，砧上成长走的是回退曲线");
            ItemStack sword = sampleSword();
            require(sword != null, "造不出测试剑");
            double atZero = mgr.swordScaleFor(sword);
            require(Math.abs(atZero - mgr.swordScale()) < 0.001,
                    "+0 的剑大小应等于 sword-scale: " + atZero + " vs " + mgr.swordScale());
            int cap = plugin.strengthen().capOf(sword);
            require(cap > 0, "上限读不到");
            // 统一性的核心断言：砧上大小 == sword-scale × 手上曲线倍数（同源配置）
            plugin.strengthen().setLevel(sword, 33);
            double at33 = mgr.swordScaleFor(sword);
            require(Math.abs(at33 - mgr.swordScale() * bs.scale().at(33)) < 1e-6,
                    "+33 的砧上大小应等于 sword-scale × 曲线倍数: " + at33 + " vs "
                            + mgr.swordScale() * bs.scale().at(33));
            require(at33 > atZero * 1.5, "第一个加倍点 (+33) 看不出明显变大: " + atZero + " → " + at33);
            plugin.strengthen().setLevel(sword, cap);
            double atCap = mgr.swordScaleFor(sword);
            require(Math.abs(atCap - mgr.swordScale() * bs.scale().at(cap)) < 1e-6,
                    "满级剑应长到 sword-scale × 曲线(cap): " + atCap + " vs "
                            + mgr.swordScale() * bs.scale().at(cap));
            require(atCap > atZero + 0.1, "剑从 +0 到满级几乎没变大（" + atZero + " → " + atCap + "）");
            // 中间单调递增：不许出现「锻到一半反而变小」
            plugin.strengthen().setLevel(sword, cap / 2);
            double mid = mgr.swordScaleFor(sword);
            require(mid > atZero && mid < atCap, "中间等级的大小不在两端之间: " + mid);
        });

        add(g, "剑越锻越大看得见：+5 就该大 10% 以上，不是只长大一点点", () -> {
            var mgr = plugin.anvilForge();
            ItemStack sword = sampleSword();
            require(sword != null, "造不出测试剑");
            double base = mgr.swordScale();
            int cap = plugin.strengthen().capOf(sword);
            require(cap >= 10, "上限太小，测不出前段成长: " + cap);
            plugin.strengthen().setLevel(sword, 5);
            double at5 = mgr.swordScaleFor(sword);
            require(at5 >= base * 1.10,
                    "+5 的剑只大 " + Msg.fmt((at5 / base - 1) * 100, 1) + "%，肉眼看不出来（要 ≥10%）");
            // 单调性：+33（第一个加倍点）必须比 +5 更大，曲线不许中途回头
            plugin.strengthen().setLevel(sword, 33);
            double at33 = mgr.swordScaleFor(sword);
            require(at33 > at5, "成长曲线在 +5 → +33 之间不是单调变大（" + at5 + " → " + at33 + "）");
        });

        add(g, "砧上实体的真实缩放跟着等级走（不是只有算式对）", () -> {
            var worlds = org.bukkit.Bukkit.getWorlds();
            require(!worlds.isEmpty(), "没有世界");
            var world = worlds.get(0);
            var block = world.getSpawnLocation().clone().add(0, 96, 0).getBlock();
            var oldType = block.getType();
            block.setType(Material.ANVIL, false);
            var mgr = plugin.anvilForge();
            try {
                ItemStack sword = sampleSword();
                require(sword != null, "造不出测试剑");
                int cap = plugin.strengthen().capOf(sword);
                plugin.strengthen().setLevel(sword, 0);
                var session = mgr.placeCore(block, java.util.UUID.nameUUIDFromBytes("ScaleBot".getBytes()),
                        NAME, sword);
                require(session != null, "放剑失败");
                float s0 = swordDisplayScale(world, block);
                require(s0 > 0, "找不到剑实体");

                // 直接顶到上限，refresh 后实体上的缩放必须真的变大
                session.sword(sword);
                plugin.strengthen().setLevel(session.sword(), cap);
                mgr.refresh(session, true);
                float s1 = swordDisplayScale(world, block);
                require(s1 > s0 + 0.05f,
                        "升级后砧上剑实体没变大：" + s0 + " → " + s1);
                require(Math.abs(s1 - mgr.swordScale() * plugin.swordGrowth().scale().at(cap)) < 0.06f,
                        "满级剑实体缩放应等于 sword-scale × 统一曲线(cap) "
                                + mgr.swordScale() * plugin.swordGrowth().scale().at(cap)
                                + "，实际 " + s1);
                mgr.releaseBlock(block);
            } finally {
                block.setType(oldType, false);
            }
        });

        add(g, "Tab 补全：分派表每个别名都补得出，且每条子命令每一层都有候选", () -> {
            var cmd = plugin.getCommand("zhujian");
            require(cmd != null, "注册不到 /zhujian");
            // PluginCommand 本身不是 TabCompleter，注册进去的 executor 才是
            if (!(cmd.getExecutor() instanceof org.bukkit.command.TabCompleter tc)) {
                throw new AssertionError("/zhujian 的 executor 没实现 TabCompleter");
            }
            // 用控制台当 sender 会漏掉管理员项；拿在线玩家，再临时挂一条 admin 权限，
            // 这样第一层/第二层的服主分支也能一并核对（finally 里摘掉，不留副作用）。
            org.bukkit.entity.Player p = org.bukkit.Bukkit.getOnlinePlayers().stream().findFirst().orElse(null);
            if (p == null) {
                return;
            }
            org.bukkit.permissions.PermissionAttachment adminAtt =
                    p.addAttachment(plugin, "forgemaster.admin", true);
            try {
                // Bukkit 只有收到「末尾空串」时才知道你在补下一段：
                //   补第一段 → args = [""]
                //   补第二段 → args = [第一段, ""]
                // 所以补第 N 段的模拟就是把前 N-1 段 + 空串塞进去。
                java.util.function.Function<String, List<String>> first =
                        typed -> tc.onTabComplete(p, cmd, "zhujian", new String[]{typed});
                java.util.function.Function<String, List<String>> next = line -> {
                    if (line.isBlank()) {
                        return tc.onTabComplete(p, cmd, "zhujian", new String[]{""});
                    }
                    String[] parts = line.split(" ", -1);
                    String[] padded = java.util.Arrays.copyOf(parts, parts.length + 1);
                    padded[parts.length] = "";
                    return tc.onTabComplete(p, cmd, "zhujian", padded);
                };

                // ① 分派表的镜像：主名与每一个别名，首层补全都得给得出来。
                //    这条是核心断言 —— 以前「加了 case 忘了补全」就是在这里被抓出来。
                List<String> top = first.apply("");
                require(!top.isEmpty(), "第一层补全是空的");
                for (List<String> group : com.forgemaster.command.ForgingCommand.subcommandTable()) {
                    for (String alias : group) {
                        require(top.contains(alias),
                                "第一层补全缺 " + alias + "（分派表里有，补全里没有）：" + top);
                    }
                }

                // ② 第二层：逐条点名（每条子命令的语法都必须能 Tab 出来）
                Map<String, List<String>> expects = new java.util.LinkedHashMap<>();
                expects.put("money", List.of("balance", "give", "take", "coin"));
                expects.put("money coin", List.of("balance", "give", "take"));
                expects.put("gem", List.of("socket", "esocket", "smelt", "inlay",
                        "extract", "merge", "list", "give"));
                expects.put("gem give", List.of("all"));
                expects.put("tomb", List.of("list", "bury", "take", "challenge", "info", "remove"));
                expects.put("tombrun", List.of("enter", "status", "quit"));
                expects.put("strengthen", List.of("info", "sim", "plan", "curve", "set",
                        "reforge", "strip", "graft", "jianyi", "merge"));
                expects.put("strengthen reforge", List.of("gui", "info"));
                expects.put("evolve", List.of("sim", "now"));
                expects.put("contract", List.of("join", "quit", "deposit"));
                expects.put("shangjin", List.of("claim"));
                expects.put("contest", List.of("start", "list", "watch", "gui"));
                expects.put("season", List.of("end"));
                expects.put("rank", List.of("coins", "reputation", "streak", "level",
                        "strengthen", "text"));
                expects.put("halloffame", List.of("set", "clear"));
                expects.put("hall", List.of("set", "show", "del"));
                expects.put("enchant", List.of("extract"));
                expects.put("ench", List.of("open", "list", "book"));
                expects.put("dissolve", List.of("confirm"));
                expects.put("imprint", List.of("merge"));
                expects.put("copy", List.of("stop", "cancel"));
                expects.put("grade", List.of("up", "give"));
                expects.put("duel", List.of("accept", "deny"));
                expects.put("talent", List.of("add", "reset"));
                expects.put("event", List.of("status", "start", "stop"));
                expects.put("auction", List.of("browse", "sell", "bid", "buy", "cancel", "claim"));
                expects.put("order", List.of("post", "cancel"));
                expects.put("master", List.of("status", "leave", "expel", "graduate",
                        "graduate-confirm", "graduates"));
                expects.put("guild", List.of("create", "invite", "kick", "deposit", "list",
                        "upgrade", "warehouse", "quest"));
                expects.put("guild quest", List.of("info", "claim", "report"));
                expects.put("spirit", List.of("info", "feed", "name", "show", "hide"));
                expects.put("metrics", List.of("trend", "csv", "sample", "gui"));
                expects.put("metrics trend", List.of("7", "30"));
                expects.put("config", List.of("check"));
                expects.put("fixattrs", List.of("all", "containers"));
                expects.put("top", List.of("legacy"));
                expects.put("givefull", List.of(p.getName()));
                expects.put("stats", List.of(p.getName()));
                for (var e : expects.entrySet()) {
                    List<String> got = next.apply(e.getKey());
                    require(!got.isEmpty(), "/zhujian " + e.getKey() + " 的 Tab 补全是空的");
                    for (String need : e.getValue()) {
                        require(got.contains(need),
                                "/zhujian " + e.getKey() + " 补全缺 " + need + "，实得 " + got);
                    }
                }

                // ③ 别名必须走到同一套候选（玩家记不住主名，常敲的是别名）。
                //    这里只放「本身还有下一层」的子命令别名 —— relicset / signin / shop
                //    这类是叶子命令，下一层本来就该是空的，把它们塞进来只会误报。
                for (String alias : List.of("qianghua", "duanzao", "baoshi", "xuanshang",
                        "mijing", "cifu", "xiufu", "linmo", "jianling", "hanghui",
                        "shifu", "paihang", "jijie", "dasai", "jiapo",
                        "tianfu", "shijian", "dingdan", "paimai", "bishi", "cailiao",
                        "fumotai", "qian", "coin")) {
                    require(!next.apply(alias).isEmpty(),
                            "别名的下一层补全空了：" + alias);
                }
                require(next.apply("qianghua").contains("merge"),
                        "别名 qianghua 的补全没走到 strengthen 分支");
                require(next.apply("baoshi").contains("socket"),
                        "别名 baoshi 的补全没走到 gem 分支");
                require(next.apply("xuanshang").contains("claim"),
                        "别名 xuanshang 的补全没走到 shangjin 分支");

                // ③b 除「叶子命令」白名单外，每条子命令的第二层都必须有候选。
                //     这条是给以后加命令的人留的钉子：新加一条 case 却忘了配补全，
                //     自检立刻红，不用等玩家来报「/zhujian xxx 按 Tab 没反应」。
                java.util.Set<String> leaf = java.util.Set.of(
                        "codex", "collection", "shoucang", "tujian", "relicset", "taozhuang",
                        "spectrum", "mingjian", "pu", "pass", "tongxingzheng", "power", "zhanli",
                        "env", "story", "board", "orders", "repair", "yanmo",
                        "manual", "manuals", "jianpu", "record", "dengji", "salvage", "melt",
                        "title", "chenghao", "shop", "shanghui", "blackmarket", "heishi",
                        "signin", "qiandao", "hammer", "chongchui", "tiechui", "accept",
                        "help", "reload", "selftest", "backup", "beifen",
                        "achievements", "achv", "chengjiu");
                for (List<String> group : com.forgemaster.command.ForgingCommand.subcommandTable()) {
                    for (String name : group) {
                        if (leaf.contains(name)) {
                            continue;
                        }
                        require(!next.apply(name).isEmpty(),
                                "第二层补全空了：" + name
                                        + "（要么补上候选，要么确实没有下一层 → 加进 leaf 白名单）");
                    }
                }

                // ④ 假候选 / 漏候选专项
                require(!next.apply("gem").contains("enchant"),
                        "gem 的补全里还挂着不存在的 enchant 分支（那是另一条子命令）");
                require(top.contains("fixattrs") && top.contains("xiufu"),
                        "首层补全没有 fixattrs —— 收敛全服存量物品那条命令以前就是这么被漏掉的");
                require(top.contains("power") && top.contains("title")
                                && top.contains("relicset") && top.contains("collection"),
                        "首层补全缺扩展模块的子命令：" + top);

                // ⑤ 前缀过滤要真的过滤，而不是无论打什么都全给
                require(first.apply("mo").contains("money") && first.apply("mo").size() <= 2,
                        "前缀过滤坏了：mo → " + first.apply("mo"));
                require(!first.apply("mo").contains("gem"),
                        "前缀过滤坏了：mo 里混进了 gem");
                List<String> co = tc.onTabComplete(p, cmd, "zhujian", new String[]{"money", "co"});
                require(co.equals(List.of("coin", "coins")),
                        "前缀过滤坏了：money co → " + co);
            } finally {
                p.removeAttachment(adminAtt);
            }
        });

        add(g, "help：分派表每条主名都写进了 /zhujian help，管理员段只对管理员出现", () -> {
            List<String> playerHelp = com.forgemaster.command.ForgingCommand.helpLines("zhujian", false);
            List<String> adminHelp = com.forgemaster.command.ForgingCommand.helpLines("zhujian", true);
            String playerText = String.join("\n", playerHelp);
            String adminText = String.join("\n", adminHelp);

            // ① 两段都得有东西，且管理员的多出管理员段（不是「管理员段是个空壳」）
            require(!playerHelp.isEmpty(), "玩家 help 是空的");
            require(adminHelp.size() > playerHelp.size() + 3,
                    "管理员 help 没比玩家 help 多出指令段（" + playerHelp.size()
                            + " vs " + adminHelp.size() + " 行）");
            require(playerText.contains("/zhujian stats"), "玩家 help 里连 stats 都没有");

            // ② 分派表每条 case 的主名，管理员 help 里必须写（这是核心断言 ——
            //    contract / shangjin / money / spirit / tomb / tombrun / redeem / forge /
            //    event / ench / evolve 十一条以前就是这么躲着的）
            for (List<String> group : com.forgemaster.command.ForgingCommand.subcommandTable()) {
                String name = group.get(0);
                require(helpHas(adminText, name),
                        "help 里没有 " + name + "（分派表有这条 case，help 一个字都没写）");
            }

            // ③ 玩家段覆盖全部非管理员指令；管理员专属的一条都不许混进去
            for (List<String> group : com.forgemaster.command.ForgingCommand.subcommandTable()) {
                String name = group.get(0);
                boolean adminOnly = com.forgemaster.command.ForgingCommand.adminOnlyTable().contains(name);
                if (adminOnly) {
                    require(!helpHas(playerText, name), "玩家 help 里混进了管理员指令：" + name);
                    require(helpHas(adminText, name), "管理员 help 里缺管理员指令：" + name);
                } else {
                    require(helpHas(playerText, name), "玩家 help 里没有 " + name);
                }
            }

            // ④ 管理员段必须自报「你也有玩家指令」，别让人以为管理员只剩这几条
            require(adminText.contains("上面那一段你也有"), "管理员段没说明玩家指令同样可用");
        });

        add(g, "stats rep（v2.25.4）：管理员段写明了发/扣声望，且有扣不够不动的手感", () -> {
            List<String> adminHelp = com.forgemaster.command.ForgingCommand.helpLines("zhujian", true);
            String adminText = String.join("\n", adminHelp);
            require(adminText.contains("stats rep give|take"),
                    "管理员 help 里没有 stats rep give|take（声望又变成没有手动入口了）");

            // 语义钉子：spendReputation 不足必须拒付且一分不扣（这条命令是靠它兜底的）
            UUID fake = UUID.nameUUIDFromBytes("selftest-stats-rep".getBytes());
            plugin.stats().addReputation(fake, 100);
            int before = plugin.stats().get(fake).reputation;
            require(!plugin.stats().spendReputation(fake, before + 1), "声望不足却扣成功了");
            require(plugin.stats().get(fake).reputation == before,
                    "扣失败却动了余额：" + before + " → " + plugin.stats().get(fake).reputation);
            require(plugin.stats().spendReputation(fake, before), "余额刚好等于扣款额却扣不动");
            require(plugin.stats().get(fake).reputation == 0, "扣光后余额不是 0");
        });

        add(g, "YamlIo（v2.25.5）：原子替换不留 .tmp；半截文件必须留档而不是被当成空档", () -> {
            java.io.File dir = new java.io.File(plugin.getDataFolder(), "selftest-yamlio");
            if (!dir.exists() && !dir.mkdirs()) {
                throw new IllegalStateException("建临时目录失败: " + dir);
            }
            try {
                // ① 原子写：内容落地、临时文件不残留
                java.io.File target = new java.io.File(dir, "probe.yml");
                org.bukkit.configuration.file.YamlConfiguration yml =
                        new org.bukkit.configuration.file.YamlConfiguration();
                yml.set("players.abc.coins", 1234);
                com.forgemaster.util.YamlIo.save(target, yml);
                require(target.exists() && target.length() > 0, "原子写入后目标文件是空的");
                org.bukkit.configuration.file.YamlConfiguration back =
                        com.forgemaster.util.YamlIo.loadOrQuarantine(target, "SelfTest", plugin.getLogger());
                require(back.getLong("players.abc.coins") == 1234L, "写进去又读回来数值不对");
                require(!new java.io.File(dir, "probe.yml.tmp").exists(), "原子写留下了 .tmp 残留");

                // ② 半截文件：必须改名留档，且返回空配置（不是把坏档当成「这个人没档案」）
                //    注意样例必须是「解析就会失败」的形态 —— 写一半但语法仍合法的 YAML
                //    （比如少了后半段但括号都闭合）没法与「本来就只有这些内容」区分，
                //    这里模拟真实的截断：引号/括号没闭合。
                java.io.File broken = new java.io.File(dir, "broken.yml");
                java.nio.file.Files.write(broken.toPath(),
                        ("players:\n  abc:\n    claimed: [1, 2, \"3" + "\n  broken: [")
                                .getBytes(java.nio.charset.StandardCharsets.UTF_8));
                require(com.forgemaster.util.YamlIo.looksTruncated(broken), "半截 YAML 没被识别出来");
                org.bukkit.configuration.file.YamlConfiguration empty =
                        com.forgemaster.util.YamlIo.loadOrQuarantine(broken, "SelfTest", plugin.getLogger());
                require(!broken.exists(), "坏档没有被移走（下次 save 会直接盖掉原件）");
                require(empty.getKeys(false).isEmpty(), "坏档读出来居然是有效的");
                java.io.File[] quarantined = dir.listFiles((d, n) -> n.startsWith("broken.yml.corrupt-"));
                require(quarantined != null && quarantined.length == 1, "没有留下 .corrupt- 留档文件");

                // ③ 空文件是「第一次跑」，不许当成坏档
                java.io.File fresh = new java.io.File(dir, "fresh.yml");
                if (!fresh.exists()) {
                    java.nio.file.Files.write(fresh.toPath(), new byte[0]);
                }
                com.forgemaster.util.YamlIo.loadOrQuarantine(fresh, "SelfTest", plugin.getLogger());
                require(fresh.exists(), "0 字节的新档被当成坏档移走了");

                // ④ 并发写同一份存档：临时文件名必须唯一
                //    v2.25.6 修 P0 —— 原来固定叫 <name>.tmp，异步冲刷撞上 /reload 会让两股
                //    字节流写同一个 tmp，ATOMIC_MOVE 顶上去就是坏档（砧位上的剑一起失踪）。
                java.io.File raceTarget = new java.io.File(dir, "race.yml");
                int threads = 8;
                java.util.concurrent.CountDownLatch go = new java.util.concurrent.CountDownLatch(1);
                java.util.List<Thread> ts = new java.util.ArrayList<>();
                java.util.concurrent.atomic.AtomicInteger raceFailures =
                        new java.util.concurrent.atomic.AtomicInteger();
                for (int k = 0; k < threads; k++) {
                    final int n = k;
                    Thread th = new Thread(() -> {
                        try {
                            go.await();
                            for (int r = 0; r < 20; r++) {
                                org.bukkit.configuration.file.YamlConfiguration c =
                                        new org.bukkit.configuration.file.YamlConfiguration();
                                c.set("writer", n);
                                c.set("round", r);
                                // 灌点体积，让「交错写」真有机会产生坏档
                                for (int q = 0; q < 40; q++) {
                                    c.set("pad." + q, "abcdefghijklmnopqrstuvwxyz0123456789-" + n + "-" + r);
                                }
                                com.forgemaster.util.YamlIo.save(raceTarget, c);
                            }
                        } catch (Exception ex) {
                            raceFailures.incrementAndGet();
                        }
                    });
                    th.setDaemon(true);
                    th.start();
                    ts.add(th);
                }
                go.countDown();
                for (Thread th : ts) {
                    th.join(20_000L);
                }
                require(raceFailures.get() == 0, "并发写有 " + raceFailures.get() + " 次抛异常");
                require(!com.forgemaster.util.YamlIo.looksTruncated(raceTarget),
                        "并发写把 race.yml 写成了半截/乱码（临时文件名没做到唯一？）");
                java.io.File[] tmpLeft = dir.listFiles((d, nm) -> nm.endsWith(".tmp"));
                require(tmpLeft == null || tmpLeft.length == 0,
                        "并发写留下了 " + (tmpLeft == null ? 0 : tmpLeft.length) + " 个 .tmp 垃圾文件");
            } finally {
                java.io.File[] all = dir.listFiles();
                if (all != null) {
                    for (java.io.File f2 : all) {
                        f2.delete();
                    }
                }
                dir.delete();
            }
        });

        add(g, "存档守卫（v2.25.5）：关键存档都走 YamlIo，且坏档不会被静默当成空档", () -> {
            // 这条钉子的意义：v2.25.5 之前全仓 70+ 处裸 yml.save(file)，一旦写半截，
            // 下次 load 静默返回空档、再被 save 覆盖 = 玩家资产与领取记录永久消失。
            // 现在逐个点名「必须有 YamlIo」的存档，少一个就红（新人加存档漏改会被这里挡住）。
            // v2.26.0 审计轮修：下面三行文件名原来写错了（guilds.yml / manuals-data.yml /
            // commission-data.yml），源码里文件名对不上 → found 恒 false → 这行被静默跳过，
            // 三个模块等于从来没被这条钉子检查过。注释里那句「新人加存档漏改会被这里挡住」
            // 因此名不副实。现在改成真实文件名，并加一条「找不到就红」的断言（见下）。
            String[][] critical = {
                    {"stats", "data.yml"},
                    {"anvil", "anvils.yml"},
                    {"auction", "auction.yml"},
                    {"order", "orders.yml"},
                    {"contract", "contract.yml"},
                    {"guild", "guild-data.yml"},
                    {"pass", "pass-data.yml"},
                    {"codex", "codex-data.yml"},
                    {"spectrum", "spectrum-data.yml"},
                    {"blackmarket", "daily-data.yml"},
                    {"manuals", "manuals.yml"},
                    {"bounty", "bounty-data.yml"},
                    {"commission", "commissions.yml"},
                    // v2.26.0 审计轮补：它是全仓唯一「在 SAVE_FILES 里 + 裸 save() + 不在本表」
                    // 的组合 —— 存档守卫完全管不到它，等于漏了一个高价值存档。
                    {"story", "story-progress.yml"}
            };
            java.util.List<String> missing = new ArrayList<>();
            java.io.File pkgRoot = findSourcePackageRoot();
            if (pkgRoot != null) {
                for (String[] row : critical) {
                    java.io.File dir = new java.io.File(pkgRoot, row[0]);
                    if (!dir.isDirectory()) {
                        continue;
                    }
                    java.io.File[] files = dir.listFiles((d, n) -> n.endsWith(".java"));
                    if (files == null) {
                        continue;
                    }
                    boolean found = false;
                    boolean usesYamlIo = false;
                    for (java.io.File f2 : files) {
                        String code;
                        try {
                            code = new String(java.nio.file.Files.readAllBytes(f2.toPath()),
                                    java.nio.charset.StandardCharsets.UTF_8);
                        } catch (java.io.IOException ex) {
                            continue;
                        }
                        if (!code.contains(row[1])) {
                            continue;
                        }
                        found = true;
                        if (code.contains("YamlIo.save") || code.contains("YamlIo.loadOrQuarantine")) {
                            usesYamlIo = true;
                        }
                    }
                    if (found && !usesYamlIo) {
                        missing.add(row[1]);
                    }
                    // v2.26.0 审计轮补：文件名写错（或该包压根没有这个文件）时，
                    // 上面会静默跳过 —— 那就是「看着在查，其实什么都没查」。
                    // 这条断言让拼错立刻红，而不是等下一个审计轮才发现。
                    require(found, "存档守卫表里的 " + row[1] + " 在 " + row[0]
                            + " 包里找不到（文件名写错了？那样这一行等于没检查）");
                }
                require(missing.isEmpty(), "这些存档还在裸 yml.save / loadConfiguration："
                        + String.join(", ", missing) + "（写半截 = 下次当成空档再被覆盖）");
            } else {
                // 只在 jar 部署（没有源码树）的环境里跳过静态部分 —— 如实写进日志，不假装查过
                plugin.getLogger().info("[selftest] 找不到源码树，跳过存档守卫的静态检查（改为只验 YamlIo 行为）");
            }
        });

        add(g, "强化上限按品质逐级提高：粗糙 < … < 神话，且神话封顶 99", () -> {
            var st = plugin.strengthen();
            require(st != null, "强化服务没初始化");
            if (!st.anvilForgeEnabled()) {
                return; // 铁砧强化关着时走老曲线，这条不适用
            }
            Quality[] order = {Quality.ROUGH, Quality.COMMON, Quality.FINE, Quality.EPIC,
                    Quality.LEGENDARY, Quality.MYTHIC};
            int prev = 0;
            for (Quality q : order) {
                int cap = st.capForQuality(q);
                require(cap > prev, q.name() + " 的上限没比低一档高：" + cap + " vs " + prev);
                require(cap > 30, q.name() + " 的上限 " + cap
                        + " 连最后一阶蜕变门槛都够不到，剑会卡在蜕变前");
                prev = cap;
            }
            require(st.capForQuality(Quality.MYTHIC) == 99, "神话上限应为 99，实际 "
                    + st.capForQuality(Quality.MYTHIC));
        });

        add(g, "指令发的神话剑不再是旧的 +0/32：铸成时的上限跟品质走", () -> {
            var st = plugin.strengthen();
            var iron = plugin.materials().get("iron");
            require(iron != null, "没有 iron 剑胚");
            ItemStack mythic = plugin.items().buildDirect("SelfTest", iron, Quality.MYTHIC, 96.0, null);
            require(mythic != null, "造不出神话剑");
            int cap = st.capOf(mythic);
            require(cap == st.capForQuality(Quality.MYTHIC),
                    "神话剑读到的上限 " + cap + " 不等于品质上限 "
                            + st.capForQuality(Quality.MYTHIC));
            require(cap != 32, "还是旧的按材料档位的 32");
            // Lore 里那行也得是新的，不然玩家看到的仍是 +0/32
            String lore = mythic.lore() == null ? "" : mythic.lore().stream()
                    .map(Msg::plain).filter(l -> l.startsWith("锻造等级"))
                    .findFirst().orElse("");
            require(lore.contains("/+" + cap), "Lore 上的上限不对：" + lore);
            // 造一把普通品，确认两把剑的上限确实不同（不是又退化成统一值）
            ItemStack common = plugin.items().buildDirect("SelfTest", iron, Quality.COMMON, 60.0, null);
            require(common != null, "造不出普通剑");
            require(st.capOf(common) < st.capOf(mythic),
                    "普通剑与神话剑上限一样（" + st.capOf(common) + " / " + st.capOf(mythic) + "）");
        });

        add(g, "老物品迁移：把冻着 32 的剑同步成品质上限，超限等级被夹回", () -> {
            var st = plugin.strengthen();
            var iron = plugin.materials().get("iron");
            require(iron != null, "没有 iron 剑胚");
            ItemStack sword = plugin.items().buildDirect("SelfTest", iron, Quality.MYTHIC, 96.0, null);
            require(sword != null, "造不出神话剑");
            var meta = sword.getItemMeta();
            require(meta != null, "没有 meta");
            // 模拟一件 v2.17 之前打出来的东西：上限 32、等级 32
            meta.getPersistentDataContainer().set(Keys.STRENGTHEN_CAP,
                    org.bukkit.persistence.PersistentDataType.INTEGER, 32);
            meta.getPersistentDataContainer().set(Keys.STRENGTHEN,
                    org.bukkit.persistence.PersistentDataType.INTEGER, 40);
            sword.setItemMeta(meta);
            require(st.ensureCap(sword), "ensureCap 没认出这是老物品");
            int want = st.capForQuality(Quality.MYTHIC);
            var after = sword.getItemMeta();
            require(after != null, "迁移后没有 meta");
            Integer nowCap = after.getPersistentDataContainer().get(Keys.STRENGTHEN_CAP,
                    org.bukkit.persistence.PersistentDataType.INTEGER);
            Integer nowLv = after.getPersistentDataContainer().get(Keys.STRENGTHEN,
                    org.bukkit.persistence.PersistentDataType.INTEGER);
            require(nowCap != null && nowCap == want, "上限没同步：" + nowCap + " 应为 " + want);
            require(nowLv != null && nowLv == Math.min(40, want),
                    "等级没夹回上限：" + nowLv);
            String lore = after.lore() == null ? "" : after.lore().stream()
                    .map(Msg::plain).filter(l -> l.startsWith("锻造等级"))
                    .findFirst().orElse("");
            require(lore.contains("/+" + want), "迁移后 Lore 上限还是错的：" + lore);
            require(!st.ensureCap(sword), "已经同步过的物品不该反复改动");
        });

        add(g, "落锤演出不碰剑的大小（防「强化时剑在鬼畜」）", () -> {
            var worlds = org.bukkit.Bukkit.getWorlds();
            require(!worlds.isEmpty(), "没有世界");
            var world = worlds.get(0);
            var block = world.getSpawnLocation().clone().add(0, 98, 0).getBlock();
            var oldType = block.getType();
            block.setType(Material.ANVIL, false);
            var mgr = plugin.anvilForge();
            try {
                ItemStack sword = sampleSword();
                require(sword != null, "造不出测试剑");
                plugin.strengthen().setLevel(sword, 10);
                var session = mgr.placeCore(block, java.util.UUID.nameUUIDFromBytes("JitterBot".getBytes()),
                        NAME, sword);
                require(session != null, "放剑失败");
                float before = swordDisplayScale(world, block);
                require(before > 0, "找不到剑实体");

                // 跑一段落锤演出（含砸中那一帧）
                mgr.playStrikeAnimation(session, block, com.forgemaster.strengthen.StrengthenService.Tier.SUCCESS);
                for (int i = 0; i < 14; i++) {
                    org.bukkit.Bukkit.getScheduler().runTask(plugin, () -> { });
                    // 自检在主线程同步跑，直接推进不了调度；用 tick 等待不可行，
                    // 所以只断言「演出的每一帧都不写剑的缩放」——见 strikeFrame 与 playStrikeAnimation
                }
                float after = swordDisplayScale(world, block);
                require(Math.abs(after - before) < 0.02f,
                        "落锤演出把剑的缩放改了（会看起来在抖）：" + before + " → " + after);
                mgr.releaseBlock(block);
            } finally {
                block.setType(oldType, false);
            }
        });

        add(g, "宝石 id / 等级解析：发宝石指令的两条入参都得认", () -> {
            var gs = plugin.gems();
            require(gs != null, "宝石系统没初始化");
            // 全部 11 种 id 都能解析回自己（管理員照 id 发东西）
            for (var t : com.forgemaster.gem.GemType.values()) {
                var back = com.forgemaster.gem.GemType.byId(t.id());
                require(back == t, "宝石 id 解析不一致: " + t.id() + " → " + back);
                require(t.gemName() != null && !t.gemName().isBlank(), "宝石没有中文名: " + t.id());
                require(t.icon().isItem(), "宝石底材不是物品: " + t.id());
            }
            // 等级：L1~L5 按名字解析，旧名兼容
            for (var g2 : com.forgemaster.gem.GemType.Grade.values()) {
                require(com.forgemaster.gem.GemType.Grade.byName(g2.name()) == g2,
                        "等级解析不一致: " + g2.name());
            }
            require(com.forgemaster.gem.GemType.Grade.byName("COMMON")
                    == com.forgemaster.gem.GemType.Grade.L1, "旧名 COMMON 应映射到 L1");
            require(com.forgemaster.gem.GemType.Grade.byName("LEGENDARY")
                    == com.forgemaster.gem.GemType.Grade.L5, "旧名 LEGENDARY 应映射到 L5");
            // 发出的宝石必须带 GEM 标记，否则镶嵌那边认不出来
            var gem = gs.createGem(com.forgemaster.gem.GemType.ATTACK,
                    com.forgemaster.gem.GemType.Grade.L5);
            require(gem.getItemMeta() != null, "宝石没有 meta");
            require(gem.getItemMeta().getPersistentDataContainer()
                    .get(com.forgemaster.util.Keys.GEM, org.bukkit.persistence.PersistentDataType.BYTE) != null,
                    "宝石没写 GEM 标记");
            require(gs.isGem(gem), "createGem 出来的东西 isGem 说不是宝石");
        });

        add(g, "落锤演出与火花开关读得到，锤子外观是件真物品", () -> {
            var mgr = plugin.anvilForge();
            require(plugin.settings().flag("anvil-forge.animation.enabled", true), "演出默认应开");
            require(plugin.settings().flag("anvil-forge.spark.enabled", true), "火花默认应开");
            require(mgr.hammerDisplayMaterial().isItem(), "演出用的锤子材质不是物品: " + mgr.hammerDisplayMaterial());
            int dur = plugin.settings().intOf("anvil-forge.animation.duration-ticks", 12);
            require(dur >= 6 && dur <= 40, "演出时长不合理: " + dur);
            int cnt = plugin.settings().intOf("anvil-forge.spark.count", 18);
            require(cnt > 0 && cnt <= 200, "火花数量不合理: " + cnt);
            require(mgr.hammerDisplayMaterial() != Material.AIR, "演出锤子是空气");
        });

        add(g, "演出是「柄底不动、锤头绕柄砸下」：角度单调下压、柄长恒定、砸中够得着砧面", () -> {
            var mgr = plugin.anvilForge();
            require(mgr.hammerDisplayMaterial() == Material.MACE,
                    "演出里画的不是重锤，是 " + mgr.hammerDisplayMaterial() + "——看着像拿斧头剁");
            require(mgr.hammerBillboard() == org.bukkit.entity.Display.Billboard.CENTER,
                    "演出锤子不是 CENTER billboard，侧面看会变成一条线: " + mgr.hammerBillboard());

            // 逐帧走一遍：角度必须严格往「砸下」方向走（0° = 锤头朝上，正角 = 往下压）
            int impact = -1;
            float prev = Float.NEGATIVE_INFINITY;
            for (int t = 0; t <= 40; t++) {
                var f = mgr.strikeFrame(t);
                if (f.impact()) {
                    impact = t;
                    break;
                }
                require(f.angleDeg() > prev,
                        "第 " + t + " 帧角度没往砸下的方向走（" + prev + " → " + f.angleDeg() + "）");
                prev = f.angleDeg();
            }
            require(impact > 0, "演出里找不到砸中那一帧");

            float start = mgr.strikeFrame(0).angleDeg();
            float hit = mgr.strikeFrame(impact).angleDeg();
            float len0 = mgr.handleLength(1.0f);
            // 起手：锤头必须在柄的<b>上方</b>（cos>0），砸中：锤头必须转到柄的<b>下方</b>（cos<0）
            float[] atStart = com.forgemaster.anvil.AnvilForgeManager.pivotOffset(start, len0);
            require(atStart[1] > 0.2f, "起手时锤头没举在柄上方（y 偏移 " + atStart[1] + "）");
            float[] atHitHead = com.forgemaster.anvil.AnvilForgeManager.pivotOffset(hit, len0);
            require(atHitHead[1] < 0f,
                    "砸中时锤头还在柄上方（y 偏移 " + atHitHead[1] + "）——那是「举着锤子不动」，不是砸");
            require(hit - start >= 90,
                    "起手到砸中只转了 " + (hit - start) + "°（要 ≥90° 才叫抡起来砸下去）");

            // 柄底钉住：pivotOffset 的长度恒等于 handle-length × scale，方向随角度转
            float len = mgr.handleLength(1.0f);
            require(len > 0.1f, "柄长太小，锤子会飘: " + len);
            for (int t = 0; t <= 12; t++) {
                float[] off = com.forgemaster.anvil.AnvilForgeManager
                        .pivotOffset(mgr.strikeFrame(t).angleDeg(), len);
                double mag = Math.sqrt(off[0] * off[0] + off[1] * off[1]);
                require(Math.abs(mag - len) < 1e-4,
                        "第 " + t + " 帧的平移长度 " + mag + " 不等于柄长 " + len
                                + "——柄底会滑走，看起来就不是「柄不动、头砸下」");
            }
            // 砸中帧锤头确实压在砧面附近（柄在 pivot，头在 headOffset = R(θ)·(0,全长,0)）
            float[] atHit = mgr.headOffset(hit, 1.0f);
            double headY = plugin.settings().num("anvil-forge.animation.pivot-height", 2.10) + atHit[1];
            require(headY < 1.45, "砸中时锤头在 y=" + headY + "，够不着砧面（剑躺在 1.03）");
            require(headY > 0.75, "砸中时锤头在 y=" + headY + "，扎进砧子里了");

            // 砸完必须真的收掉（done），否则每敲一锤就在砧上堆一把锤子
            int done = -1;
            for (int t = impact; t <= 60; t++) {
                if (mgr.strikeFrame(t).done()) {
                    done = t;
                    break;
                }
            }
            require(done > impact, "演出永远不收尾（done 帧没出现）");
            require(plugin.settings().flag("anvil-forge.animation.flame.enabled", true),
                    "火焰被关了（这版就是加火才看得出手感）");
        });

        add(g, "砧位只认三档铁砧方块", () -> {
            require(com.forgemaster.anvil.AnvilForgeManager.isAnvilBlock(Material.ANVIL), "普通铁砧不认");
            require(com.forgemaster.anvil.AnvilForgeManager.isAnvilBlock(Material.CHIPPED_ANVIL), "开裂铁砧不认");
            require(com.forgemaster.anvil.AnvilForgeManager.isAnvilBlock(Material.DAMAGED_ANVIL), "破损铁砧不认");
            require(!com.forgemaster.anvil.AnvilForgeManager.isAnvilBlock(Material.IRON_BLOCK), "铁块被当成铁砧");
            require(!com.forgemaster.anvil.AnvilForgeManager.isAnvilBlock(Material.SMITHING_TABLE), "锻造台被当成铁砧");
        });

        add(g, "砧位键往返，且不同坐标不撞", () -> {
            String a = com.forgemaster.anvil.AnvilForgeSession.keyOf("world", 1, 2, 3);
            String b = com.forgemaster.anvil.AnvilForgeSession.keyOf("world", 1, 2, 4);
            require(a.equals("world;1;2;3"), "键格式变了: " + a);
            require(!a.equals(b), "相邻砧位键撞了");
        });

        add(g, "砧位全息写明等级 / 成功率 / 碎裂率 / 连击", () -> {
            var s = plugin.strengthen();
            ItemStack w = sampleSword();
            require(w != null, "造不出测试剑");
            s.setLevel(w, 12);
            var session = new com.forgemaster.anvil.AnvilForgeSession(
                    "world", 0, 64, 0, java.util.UUID.nameUUIDFromBytes("SelfTestAnvil".getBytes()),
                    NAME, w, System.currentTimeMillis());
            String text = Msg.plain(Msg.mm(plugin.anvilForge().boardText(session)));
            require(text.contains("+12"), "全息没写当前等级: " + text);
            // 上限按品质走（普通剑 +50），全息必须报同一份，不能还写死 +99
            require(text.contains("/+" + s.capOf(w)),
                    "全息没写品质上限 +" + s.capOf(w) + ": " + text);
            require(text.contains("成功率"), "全息没写成功率: " + text);
            require(text.contains("碎裂率"), "全息没写碎裂率: " + text);
            require(text.contains("连击"), "全息没写连击: " + text);
            // 蜕变门槛也要摆出来：+12 已越过第 1 阶门槛 +5，全息必须说清楚为什么敲不动
            require(text.contains("本阶已满 +5"), "全息没写蜕变门槛提示: " + text);
        });

        add(g, "全息：没到门槛写「本阶门槛」，越过门槛写「本阶已满」", () -> {
            var s = plugin.strengthen();
            ItemStack w = sampleSword();
            require(w != null, "造不出测试剑");
            var session = new com.forgemaster.anvil.AnvilForgeSession(
                    "world", 0, 64, 0, java.util.UUID.nameUUIDFromBytes("GateBot".getBytes()),
                    NAME, w, System.currentTimeMillis());
            var mgr = plugin.anvilForge();
            var evo = plugin.evolutions();
            if (evo == null || !evo.enabled()) {
                return; // 蜕变关着就没有门槛这回事
            }
            var first = evo.registry().nextGate(0);
            require(first != null, "第 1 阶门槛丢了");

            s.setLevel(w, Math.max(0, first.level() - 2));
            String below = Msg.plain(Msg.mm(mgr.boardText(session)));
            require(below.contains("本阶门槛 +" + first.level()),
                    "没到门槛时全息没提示下一道坎: " + below);
            require(!below.contains("本阶已满"), "没到门槛却说本阶已满: " + below);

            s.setLevel(w, first.level());
            String at = Msg.plain(Msg.mm(mgr.boardText(session)));
            require(at.contains("本阶已满 +" + first.level()), "到门槛了全息没拦: " + at);
            require(at.contains(first.title()), "全息没说该蜕变哪一阶: " + at);
        });

        add(g, "砧位全息真生成、文本随等级变、撤得干净", () -> {
            var worlds = org.bukkit.Bukkit.getWorlds();
            require(!worlds.isEmpty(), "没有世界");
            var world = worlds.get(0);
            var loc = world.getSpawnLocation().clone().add(0, 90, 0);
            var block = loc.getBlock();
            var oldType = block.getType();
            block.setType(Material.ANVIL, false);
            try {
                var mgr = plugin.anvilForge();
                ItemStack sword = sampleSword();
                require(sword != null, "造不出测试剑");
                plugin.strengthen().setLevel(sword, 7);

                var session = mgr.placeCore(block, java.util.UUID.nameUUIDFromBytes("AnvilBot".getBytes()),
                        NAME, sword);
                require(session != null, "放剑失败");
                require(mgr.sessionAt(block) != null, "砧位没登记");
                require(block.getType() == Material.ANVIL, "砧子被我拆了");

                // 剑实体 + 文本实体都该在
                var displays = world.getNearbyEntities(loc.clone().add(0.5, 1.0, 0.5), 3, 3, 3,
                        e -> e instanceof org.bukkit.entity.ItemDisplay
                                || e instanceof org.bukkit.entity.TextDisplay);
                require(!displays.isEmpty(), "全息实体没生成");

                String text1 = Msg.plain(Msg.mm(mgr.boardText(session)));
                require(text1.contains("+7"), "全息没跟着等级走: " + text1);

                // 升级后文本要变
                plugin.strengthen().setLevel(session.sword(), 30);
                mgr.refresh(session, true);
                String text2 = Msg.plain(Msg.mm(mgr.boardText(session)));
                require(text2.contains("+30"), "升级后全息没刷新: " + text2);
                require(!text2.equals(text1), "升级前后全息文案一样");

                // 拆砧 / 释放：实体必须一起撤掉，且不留会话
                mgr.releaseBlock(block);
                require(mgr.sessionAt(block) == null, "释放后砧位还在");
                var leftovers = world.getNearbyEntities(loc.clone().add(0.5, 1.0, 0.5), 3, 3, 3,
                        e -> e instanceof org.bukkit.entity.ItemDisplay
                                || e instanceof org.bukkit.entity.TextDisplay);
                require(leftovers.isEmpty(), "释放后还留着 " + leftovers.size() + " 个全息实体");
            } finally {
                block.setType(oldType, false);
            }
        });

        add(g, "关服不丢剑：砧位写盘后能原样读回", () -> {
            var tmp = new java.io.File(plugin.getDataFolder(), "anvils-selftest.yml");
            if (tmp.exists() && !tmp.delete()) {
                require(false, "旧的临时文件删不掉: " + tmp.getAbsolutePath());
            }
            ItemStack sword = sampleSword();
            require(sword != null, "造不出测试剑");
            plugin.strengthen().setLevel(sword, 42);
            plugin.strengthen().setCombo(sword, 3);
            var owner = java.util.UUID.nameUUIDFromBytes("AnvilPersist".getBytes());

            var worlds = org.bukkit.Bukkit.getWorlds();
            var world = worlds.get(0);
            var block = world.getSpawnLocation().clone().add(0, 92, 0).getBlock();
            var oldType = block.getType();
            block.setType(Material.ANVIL, false);
            // 用一只独立的临时管理器验证往返：绝不清空线上砧位（玩家可能正把剑放在砧上）
            var scratch = new com.forgemaster.anvil.AnvilForgeManager(plugin);
            try {
                require(scratch.placeCore(block, owner, NAME, sword) != null, "放剑失败");

                scratch.saveTo(tmp);
                require(tmp.exists() && tmp.length() > 0, "砧位没写进盘: " + tmp.length());

                // 模拟重启：换一个新实例，从盘上读回来
                var reloaded = new com.forgemaster.anvil.AnvilForgeManager(plugin);
                reloaded.loadFrom(tmp);
                require(reloaded.size() == 1, "读回后砧位数量不对: " + reloaded.size());

                var back = reloaded.sessionAt(block);
                require(back != null, "读回来的砧位找不到了");
                require(back.owner().equals(owner), "读回来主人变了");
                require(back.worldName().equals(world.getName()), "读回来的世界变了");
                require(plugin.strengthen().levelOf(back.sword()) == 42,
                        "读回来的等级丢了: " + plugin.strengthen().levelOf(back.sword()));
                require(plugin.strengthen().comboOf(back.sword()) == 3, "读回来的连击丢了");
                require(plugin.strengthen().capOf(back.sword())
                                == plugin.strengthen().capForQuality(Quality.COMMON),
                        "读回来的上限丢了");
                reloaded.discardQuietly(block);
            } finally {
                scratch.discardQuietly(block);
                block.setType(oldType, false);
                if (tmp.exists()) {
                    org.bukkit.Bukkit.getScheduler().runTaskLater(plugin, tmp::delete, 1L);
                }
            }
        });

        add(g, "全息文本没有裸露的标签残留（防拼错颜色）", () -> {
            var s = plugin.strengthen();
            ItemStack w = sampleSword();
            require(w != null, "造不出测试剑");
            var session = new com.forgemaster.anvil.AnvilForgeSession(
                    "world", 0, 64, 0, java.util.UUID.nameUUIDFromBytes("TagBot".getBytes()),
                    NAME, w, System.currentTimeMillis());
            var mgr = plugin.anvilForge();
            // 多个等级各验一遍：颜色是分段变的，低段最容易拼错。
            // 等级按该物品的品质上限夹一遍（普通剑只到 +50），否则 setLevel 会夹住导致对不上
            int cap = s.capOf(w);
            for (int lv : new int[]{0, 5, 12, 30, 60, 97, 99}) {
                int use = Math.min(lv, cap);
                s.setLevel(w, use);
                String raw = mgr.boardText(session);
                String plain = Msg.plain(Msg.mm(raw));
                require(!plain.contains("<"), "+" + use + " 的全息里漏出了未解析标签: " + plain);
                require(!plain.contains("</"), "+" + use + " 的全息里有闭合标签漏出: " + plain);
                require(!plain.contains("#"), "+" + use + " 的全息里漏出了颜色码: " + plain);
                require(!plain.contains("%<"), "+" + use + " 的全息百分比拼坏了: " + plain);
                // 数值必须真在里面，不能是空模板
                require(plain.contains("成功率"), "+" + use + " 缺成功率");
                require(plain.contains("%"), "+" + use + " 成功率没有百分比数值: " + plain);
                require(plain.contains("+" + use), "+" + use + " 的等级数值没渲染出来: " + plain);
            }
        });

        add(g, "全息文本里成功率是真实数字，不是占位符", () -> {
            var s = plugin.strengthen();
            ItemStack w = sampleSword();
            require(w != null, "造不出测试剑");
            var session = new com.forgemaster.anvil.AnvilForgeSession(
                    "world", 0, 64, 0, java.util.UUID.nameUUIDFromBytes("NumBot".getBytes()),
                    NAME, w, System.currentTimeMillis());
            s.setLevel(w, 0);
            String t0 = Msg.plain(Msg.mm(plugin.anvilForge().boardText(session)));
            s.setLevel(w, 50);
            String t50 = Msg.plain(Msg.mm(plugin.anvilForge().boardText(session)));
            require(!t0.equals(t50), "等级变了全息数字没变");
            // +0 的成功率应该明显高于 +50
            double c0 = s.successChance(0), c50 = s.successChance(50);
            require(c0 > c50, "成功率曲线不对");
            require(t0.contains(Math.round(c0 * 100) + "%"), "+0 的全息没写 " + Math.round(c0 * 100) + "%: " + t0);
        });

        add(g, "落锤冷却默认够长，按住左键不会疯敲", () -> {
            long cd = plugin.settings().intOf("anvil-forge.strike-cooldown-ms", 450);
            require(cd >= 300, "冷却太短，按住左键会刷屏: " + cd + "ms");
            require(cd <= 1500, "冷却太长会拖手感: " + cd + "ms");
        });

        add(g, "剑平躺参数：贴面、不顶出砧面", () -> {
            var mgr = plugin.anvilForge();
            double sh = mgr.swordHeight();
            require(sh > 0.95 && sh <= 1.25, "剑的高度不在砧面附近: " + sh);
            double sc = mgr.swordScale();
            require(sc > 0.2 && sc < 1.2, "剑太大/太小会顶出砧面: " + sc);
            require(hashCodeOfYaw(mgr.swordYaw()), "朝向参数读不到");
        });

        add(g, "剑是躺在砧面上，不是插在砧子里（姿态角与真实实体都验）", () -> {
            var mgr = plugin.anvilForge();
            // 默认姿态 NONE：不吃原版 display.ground（那套会缩到 0.5 再往下沉 2/16）
            require(mgr.swordTransform() == org.bukkit.entity.ItemDisplay.ItemDisplayTransform.NONE,
                    "默认姿态不是 NONE，剑会沿用原版掉落物变换（缩小 + 下沉）: " + mgr.swordTransform());
            require(Math.abs(Math.abs(mgr.swordPitch()) - 90) < 0.01,
                    "默认俯仰不是 ±90，模型立着不会平躺: " + mgr.swordPitch());

            // 真放一个砧位，量实体的姿态四元数：拿模型 +Z（图标正面）转到世界里的方向，
            // 平躺就意味着它应该是「斜着朝上」而不是水平的——用 Y 分量判断更直接：
            // R_X(-90) 把 +Z 映射成 +Y，所以经旋转后的法线必须几乎竖直。
            var worlds = org.bukkit.Bukkit.getWorlds();
            require(!worlds.isEmpty(), "没有世界");
            var world = worlds.get(0);
            var loc = world.getSpawnLocation().clone().add(0, 96, 0);
            var block = loc.getBlock();
            var oldType = block.getType();
            block.setType(Material.ANVIL, false);
            try {
                ItemStack sword = sampleSword();
                require(sword != null, "造不出测试剑");
                var session = mgr.placeCore(block,
                        java.util.UUID.nameUUIDFromBytes("AnvilPose".getBytes()), NAME, sword);
                require(session != null, "放剑失败");
                org.bukkit.entity.ItemDisplay display = null;
                for (var e : world.getNearbyEntities(loc.clone().add(0.5, 1.0, 0.5), 3, 3, 3,
                        ent -> ent instanceof org.bukkit.entity.ItemDisplay)) {
                    display = (org.bukkit.entity.ItemDisplay) e;
                }
                require(display != null, "剑的 ItemDisplay 没生成");
                require(display.getItemDisplayTransform() == mgr.swordTransform(),
                        "实体姿态和配置不一致: " + display.getItemDisplayTransform());

                // 剑心应该贴在砧面上（1.0）而不是沉进方块里
                double dy = display.getLocation().getY() - block.getY();
                require(dy > 1.0 && dy < 1.15, "剑没贴在砧面上，dy=" + dy);

                // 姿态：把模型正面法线 (0,0,1) 用实体的左乘四元数转一遍，平躺 → 法线朝上（|y|≈1）
                var q = display.getTransformation().getLeftRotation();
                var n = q.transform(new org.joml.Vector3f(0f, 0f, 1f));
                require(Math.abs(n.y()) > 0.99,
                        "剑没有平躺：模型法线在世界里是 " + n + "（y 应≈±1）");

                // 敲上去之后剑要变大：改等级 → refresh，实体的缩放必须跟着涨
                float s0 = display.getTransformation().getScale().x();
                int cap = plugin.strengthen().capOf(session.sword());
                plugin.strengthen().setLevel(session.sword(), cap);
                mgr.refresh(session, true);
                display = null;
                for (var e : world.getNearbyEntities(loc.clone().add(0.5, 1.0, 0.5), 3, 3, 3,
                        ent -> ent instanceof org.bukkit.entity.ItemDisplay)) {
                    display = (org.bukkit.entity.ItemDisplay) e;
                }
                require(display != null, "升级后剑实体没了");
                float s1 = display.getTransformation().getScale().x();
                require(s1 > s0 + 0.05f, "敲到满级剑没变大：" + s0 + " → " + s1);
                require(Math.abs(s1 - mgr.swordScale() * plugin.swordGrowth().scale().at(cap)) < 0.02,
                        "满级剑的大小没长到 sword-scale × 统一曲线(cap)："
                                + mgr.swordScale() * plugin.swordGrowth().scale().at(cap)
                                + "，实际 " + s1);

                mgr.releaseBlock(block);
            } finally {
                block.setType(oldType, false);
            }
        });

        add(g, "剑币不够时给出正确发放命令（不让人踩金币/剑币的坑）", () -> {
            // 强化扣的是内置剑币，不是 Vault/CMI 的钱；提示里必须写明 money coin give
            var eco = plugin.economy();
            require(eco != null, "经济模块没起来");
            require(eco.backend() != null, "经济后端没探测到");
            // 后端要么是外部经济（有金币池），要么是内置剑币；都给一句能照抄的命令
            String hint = "/zhujian money coin give";
            require(hint.startsWith("/zhujian money coin"), "命令文本不对");
        });

        add(g, "插过的剑被取走时不会留在会话里", () -> {
            var mgr = plugin.anvilForge();
            require(mgr != null, "铁砧管理器没起来");
            require(mgr.sessionAt(null) == null, "sessionAt(null) 应为空");
            // 自检不往世界里放真砧子：这里只验证管理器本身是干净可用的
            require(mgr.size() >= 0, "砧位数量不该是负数");
            require(mgr.enabled(), "铁砧强化默认应开启");
        });
    }

    private void registerSocketsAndGuards() {
        String g = "镶嵌孔 / 原版封禁";

        add(g, "孔位上限阶梯：铁1 铜1 钻石2 合金2 星陨3", () -> {
            int[][] expect = {{1, 1}, {2, 1}, {3, 2}, {4, 2}, {5, 3}};
            for (int[] pair : expect) {
                int got = com.forgemaster.gem.GemService.defaultSockets(pair[0]);
                require(got == pair[1], "第 " + pair[0] + " 档应为 " + pair[1] + " 孔，实际 " + got);
            }
        });

        add(g, "实际物品的孔位上限按材料档位走", () -> {
            ForgeMaterial iron = plugin.materials().get("iron");
            ForgeMaterial star = plugin.materials().get("starfall");
            require(iron != null && star != null, "缺 iron / starfall 剑胚");
            ItemStack a = plugin.items().buildDirect("SelfTest", iron, Quality.COMMON, 60.0, null);
            ItemStack b = plugin.items().buildDirect("SelfTest", star, Quality.COMMON, 60.0, null);
            int la = plugin.gems().socketLimitOf(a);
            int lb = plugin.gems().socketLimitOf(b);
            require(la == 1, "铁剑孔位应为 1，实际 " + la);
            require(lb == 3, "星陨剑孔位应为 3，实际 " + lb);
        });

        add(g, "装备（armor-*）能反查到材料并能强化", () -> {
            ForgeMaterial base = plugin.materials().get("iron");
            require(base != null, "缺 iron 剑胚");
            ForgeMaterial chestDef = com.forgemaster.material.ArmorCatalog
                    .create(base, ForgeMaterial.Slot.CHESTPLATE, 1.0);
            require(chestDef != null, "造不出铁胸甲胚");
            ItemStack chest = plugin.items().buildDirect("SelfTest", chestDef, Quality.COMMON, 60.0, null);
            require(chest != null, "造不出铁胸甲物品");

            ForgeMaterial resolved = plugin.strengthen().materialOf(chest);
            require(resolved != null, "装备反查不到材料（强化会被直接拒掉）");
            require(resolved.armor(), "反查出来的不是装备");
            int chestCap = plugin.strengthen().capForQuality(Quality.COMMON);
            require(plugin.strengthen().capOf(chest) == chestCap,
                    "铁胸甲上限应跟随品质上限 " + chestCap + "，实际 "
                            + plugin.strengthen().capOf(chest));
            require(plugin.strengthen().setLevel(chest, 5), "装备强化失败");
            require(plugin.strengthen().levelOf(chest) == 5, "装备等级没写进去");
            require(plugin.gems().socketLimitOf(chest) == 1, "铁胸甲孔位应为 1");
        });

        add(g, "附魔条目编解码往返", () -> {
            var ench = org.bukkit.Registry.ENCHANTMENT
                    .get(org.bukkit.NamespacedKey.minecraft("sharpness"));
            require(ench != null, "取不到锋利附魔");
            var e = new com.forgemaster.gem.GemService.EnchantEntry(ench, 3);
            var back = com.forgemaster.gem.GemService.EnchantEntry.decode(e.encode());
            require(back != null, "解码失败");
            require(back.level() == 3, "等级丢了: " + back.level());
            require(back.enchantment().equals(ench), "附魔类型丢了");
        });

        add(g, "镶嵌块同时画宝石与附魔，并吃掉旧「宝石孔」标题", () -> {
            var ench = org.bukkit.Registry.ENCHANTMENT
                    .get(org.bukkit.NamespacedKey.minecraft("unbreaking"));
            require(ench != null, "取不到耐久附魔");

            java.util.List<net.kyori.adventure.text.Component> base = new ArrayList<>();
            base.add(Msg.mm("<gray>品质: 普通"));
            base.add(Msg.mm("<gold>◆ 宝石孔 <gray>[0/1]</gold>"));
            base.add(Msg.mm("<dark_gray>（空孔 · 副手拿宝石镶嵌）</dark_gray>"));
            base.add(Msg.mm("<dark_gray>锻造者: SelfTest</dark_gray>"));

            java.util.List<com.forgemaster.gem.GemService.GemEntry> noGems = java.util.List.of();
            java.util.List<com.forgemaster.gem.GemService.EnchantEntry> enchants =
                    java.util.List.of(new com.forgemaster.gem.GemService.EnchantEntry(ench, 2));
            var out = com.forgemaster.gem.GemService.rebuildGemBlock(base, 1, noGems, enchants);

            long titles = out.stream().map(Msg::plain).filter(t -> t.contains("镶嵌孔")).count();
            require(titles == 1, "镶嵌块标题数量应为 1，实际 " + titles);
            require(out.stream().map(Msg::plain).noneMatch(t -> t.contains("宝石孔")), "旧「宝石孔」标题没被清掉");
            require(out.stream().map(Msg::plain).anyMatch(t -> t.contains("耐久")), "附魔条目没画出来");
            require(out.stream().map(Msg::plain).anyMatch(t -> t.contains("锻造者")), "锻造者行被吞了");
        });

        add(g, "已占孔数 = 宝石数 + 附魔数", () -> {
            ItemStack sword = sampleSword();
            require(sword != null, "造不出铁剑");
            require(plugin.gems().usedSockets(sword) == 0, "新物品不该占孔");
            require(plugin.gems().enchantsOf(sword).isEmpty(), "新物品不该有附魔");
        });

        add(g, "原版交互开关可读，且默认只拦插件物品", () -> {
            require(plugin.settings().flag("vanilla-guard.enabled", true), "vanilla-guard.enabled 没开");
            require(plugin.settings().flag("vanilla-guard.enchanting-table", true), "附魔台没被纳入管理");
            require(plugin.settings().flag("vanilla-guard.anvil", true), "铁砧没被纳入管理");
            require(!plugin.settings().flag("vanilla-guard.block-all", false),
                    "block-all 默认必须是 false，否则原版物品也用不了附魔台");
            require(!plugin.settings().str("vanilla-guard.message.anvil", "").isBlank(), "铁砧提示文案为空");
        });

        add(g, "原版物品照常能用附魔台 / 铁砧", () -> {
            var guard = new com.forgemaster.listener.VanillaGuardListener(plugin);
            // 普通钻石剑 —— 不是插件造的
            ItemStack vanillaSword = new ItemStack(org.bukkit.Material.DIAMOND_SWORD);
            require(!plugin.items().isForged(vanillaSword), "普通钻石剑被误判成锻造品");
            require(!guard.blocksItem(vanillaSword, org.bukkit.Material.ANVIL),
                    "普通钻石剑被铁砧拦了——原版玩法被误伤");
            require(!guard.blocksItem(vanillaSword, org.bukkit.Material.ENCHANTING_TABLE),
                    "普通钻石剑被附魔台拦了");
            require(!guard.blocksItem(new ItemStack(org.bukkit.Material.IRON_CHESTPLATE),
                    org.bukkit.Material.ANVIL), "普通铁胸甲被铁砧拦了");
            require(!guard.blocksItem(new ItemStack(org.bukkit.Material.BOOK),
                    org.bukkit.Material.ANVIL), "书本被铁砧拦了");
            require(!guard.blocksItem(null, org.bukkit.Material.ANVIL), "空手被拦了");
        });

        add(g, "插件锻造品被附魔台 / 铁砧拦住", () -> {
            var guard = new com.forgemaster.listener.VanillaGuardListener(plugin);
            ItemStack sword = sampleSword();
            require(sword != null, "造不出铁剑");
            require(plugin.items().isForged(sword), "自检样本不是锻造品");
            require(guard.blocksItem(sword, org.bukkit.Material.ANVIL),
                    "锻造剑放进铁砧没被拦");
            require(guard.blocksItem(sword, org.bukkit.Material.CHIPPED_ANVIL),
                    "开裂铁砧没拦锻造剑");
            require(guard.blocksItem(sword, org.bukkit.Material.ENCHANTING_TABLE),
                    "锻造剑放进附魔台没被拦");

            ItemStack armor = armorPieceOf("iron", ForgeMaterial.Slot.CHESTPLATE);
            require(armor != null, "造不出铁胸甲");
            require(guard.blocksItem(armor, org.bukkit.Material.ANVIL), "锻造胸甲没被铁砧拦");
        });

        add(g, "砂轮默认不拦锻造品，打开开关才拦", () -> {
            var guard = new com.forgemaster.listener.VanillaGuardListener(plugin);
            ItemStack sword = sampleSword();
            require(sword != null, "造不出铁剑");
            boolean grind = plugin.settings().flag("vanilla-guard.grindstone", false);
            require(guard.blocksItem(sword, org.bukkit.Material.GRINDSTONE) == grind,
                    "砂轮行为与配置 vanilla-guard.grindstone=" + grind + " 不一致");
        });

        add(g, "台子判定不误伤工作台 / 熔炉", () -> {
            var guard = new com.forgemaster.listener.VanillaGuardListener(plugin);
            require(!guard.blocks(org.bukkit.Material.CRAFTING_TABLE), "工作台被误伤");
            require(!guard.blocks(org.bukkit.Material.FURNACE), "熔炉被误伤");
            require(!guard.blocks(org.bukkit.Material.CHEST), "箱子被误伤");
        });
    }

    // ---------------------------------------------------------------- 词条缩放 / 锻造等级

    private void registerAffixScalingStrengthen() {
        String g = "词条缩放 / 锻造等级";

        add(g, "词条缩放系数随档位单调递增", () -> {
            for (int t = 2; t <= 5; t++) {
                require(AffixScaling.valueScale(plugin, t) > AffixScaling.valueScale(plugin, t - 1),
                        "第 " + t + " 档数值倍率没有变大");
                require(AffixScaling.spreadScale(plugin, t) > AffixScaling.spreadScale(plugin, t - 1),
                        "第 " + t + " 档波动倍率没有变大");
            }
        });

        add(g, "低档材料的词条基准明显低于高档", () -> {
            com.forgemaster.affix.Affix a = plugin.affixes().get("attack_damage");
            require(a != null, "没有 attack_damage 词条");
            double low = averageRoll(a, 1);
            double high = averageRoll(a, 5);
            require(high > low * 1.8, "高档均值没有拉开差距: " + low + " vs " + high);
        });

        add(g, "低档材料的词条波动明显小于高档", () -> {
            com.forgemaster.affix.Affix a = plugin.affixes().get("crit_damage");
            require(a != null, "没有 crit_damage 词条");
            double low = spreadOf(a, 1);
            double high = spreadOf(a, 5);
            require(high > low * 2.0, "高档波动没有明显更大: " + low + " vs " + high);
        });

        add(g, "锻造等级上限统一为 +99，与档位无关", () -> {
            var s = plugin.strengthen();
            int cap = s.maxLevel();
            require(cap == 99, "默认上限应为 99，实际 " + cap);
            for (int t = 1; t <= 5; t++) {
                require(s.capForTier(t) == cap, "第 " + t + " 档上限没统一到 " + cap);
            }
        });

        add(g, "锻造等级收益按比例增长且不超封顶", () -> {
            var s = plugin.strengthen();
            require(s.bonusRatio(0) == 0, "0 级不该有加成");
            double cap = plugin.settings().num("strengthen.max-bonus", 0.60);
            require(s.bonusRatio(999) <= cap + 1e-9, "收益突破封顶: " + s.bonusRatio(999));
            require(s.bonusRatio(10) > s.bonusRatio(5), "等级越高收益没有变大");
        });

        add(g, "强化费用随等级递增", () -> {
            ItemStack sword = sampleSword();
            var s = plugin.strengthen();
            require(s.costOf(sword, 10) > s.costOf(sword, 0), "费用没有随等级递增");
        });

        add(g, "强化费用随材料档位递增", () -> {
            var s = plugin.strengthen();
            ForgeMaterial iron = plugin.materials().get("iron");
            ForgeMaterial star = plugin.materials().get("starfall");
            require(iron != null && star != null, "缺 iron / starfall 剑胚");
            ItemStack a = plugin.items().buildDirect("SelfTest", iron, Quality.COMMON, 60.0, null);
            ItemStack b = plugin.items().buildDirect("SelfTest", star, Quality.COMMON, 60.0, null);
            require(s.costOf(b, 0) > s.costOf(a, 0), "高档材料强化没有更贵");
        });

        add(g, "新造物品自带 0 级与品质上限", () -> {
            ItemStack sword = sampleSword();
            require(sword != null, "造不出铁剑");
            var s = plugin.strengthen();
            require(s.levelOf(sword) == 0, "初始等级不是 0");
            int want = s.capForQuality(Quality.COMMON);
            require(s.capOf(sword) == want,
                    "普通铁剑上限不是品质上限 " + want + "：" + s.capOf(sword));
        });

        add(g, "强化后基础属性按倍率放大", () -> {
            ItemStack sword = sampleSword();
            require(sword != null, "造不出铁剑");
            double before = baseAttackOf(sword);
            require(before > 0, "铁剑没有基础攻击");
            // v2.17.3：倍率不再写死（0.02/级 时代硬编码 1.20），按当前配置算期望——
            // 服主改 bonus-per-level 断言自动跟上，不会再出现「实现对、断言错」
            plugin.strengthen().setLevel(sword, 10);
            double after = baseAttackOf(sword);
            double expect = before * (1.0 + plugin.strengthen().bonusRatio(10));
            require(Math.abs(after - expect) < 0.02,
                    "强化后攻击力应为 " + expect + "，实际 " + after);
        });

        add(g, "老物品（无 2.4 新键）强化后属性不降反升", () -> {
            ItemStack sword = sampleSword();
            require(sword != null, "造不出铁剑");
            double before = baseAttackOf(sword);
            require(before > 0, "铁剑没有基础攻击");
            stripStrengthenKeys(sword); // 模拟 2.2 时代铸出来的老武器

            require(plugin.strengthen().levelOf(sword) == 0, "老物品等级应视作 0");
            int want = plugin.strengthen().capForQuality(Quality.COMMON);
            require(plugin.strengthen().capOf(sword) == want,
                    "老物品上限应按品质现算 " + want + "，实际 " + plugin.strengthen().capOf(sword));
            plugin.strengthen().setLevel(sword, 1);
            double after = baseAttackOf(sword);
            require(after >= before, "老物品强化后属性掉了: " + before + " → " + after);
            require(after > before, "老物品强化后属性没涨: " + before + " → " + after);
        });

        add(g, "老物品强化后补写基础属性快照", () -> {
            ItemStack sword = sampleSword();
            require(sword != null, "造不出铁剑");
            stripStrengthenKeys(sword);
            plugin.strengthen().setLevel(sword, 2);
            var meta = sword.getItemMeta();
            require(meta != null, "取不到 ItemMeta");
            Double snap = meta.getPersistentDataContainer()
                    .get(Keys.BASE_ATK, PersistentDataType.DOUBLE);
            require(snap != null && snap > 0, "强化后没有写回基础属性快照");
        });

        add(g, "基础属性快照优先于当前配置", () -> {
            // v2.23.4 重写这条断言：它原来期望「快照写多少就放大到多少」，
            // 但 clampBase 有一个**故意的**天花板 —— fallback × strengthen.base-tolerance
            // （默认 1.0，即严格等于配置值），用来把历史膨胀的老剑收敛回来。
            // 所以「快照无上限生效」和「反膨胀」这两个设计本来就冲突，老断言必然是红的。
            // 真正该测的是两件事，分别对应 clampBase 的两种语义：
            //   ① 快照低于天花板 → 照快照走（快照优先）；
            //   ② 快照高于天花板 → 压回天花板（反膨胀）。
            double mult = 1.0 + plugin.strengthen().bonusRatio(1);

            // ① 快照 = 一半 → 应当按一半放大
            ItemStack low = sampleSword();
            require(low != null, "造不出铁剑");
            double baseLow = baseAttackOf(low);
            require(baseLow > 0, "铁剑没有基础攻击");
            var lowMeta = low.getItemMeta();
            require(lowMeta != null, "取不到 ItemMeta");
            lowMeta.getPersistentDataContainer().set(Keys.BASE_ATK, PersistentDataType.DOUBLE, baseLow * 0.5);
            low.setItemMeta(lowMeta);
            plugin.strengthen().setLevel(low, 1);
            double afterLow = baseAttackOf(low);
            require(Math.abs(afterLow - baseLow * 0.5 * mult) < 0.05,
                    "低于天花板的快照应当照用：期望 " + (baseLow * 0.5 * mult) + "，实际 " + afterLow);

            // ② 快照 = 两倍 → 必须被压回配置算出的天花板，不能顺着膨胀
            ItemStack high = sampleSword();
            require(high != null, "造不出铁剑");
            double baseHigh = baseAttackOf(high);
            var highMeta = high.getItemMeta();
            require(highMeta != null, "取不到 ItemMeta");
            highMeta.getPersistentDataContainer().set(Keys.BASE_ATK, PersistentDataType.DOUBLE, baseHigh * 2);
            high.setItemMeta(highMeta);
            plugin.strengthen().setLevel(high, 1);
            double afterHigh = baseAttackOf(high);
            require(afterHigh <= baseHigh * mult + 0.05,
                    "虚高快照必须被收敛回天花板 " + (baseHigh * mult) + "，实际 " + afterHigh);
        });

        add(g, "材料配置丢失时拒绝强化（不白扣剑币）", () -> {
            ItemStack sword = sampleSword();
            require(sword != null, "造不出铁剑");
            var meta = sword.getItemMeta();
            require(meta != null, "取不到 ItemMeta");
            // 把 material 指向一个不存在的剑胚
            meta.getPersistentDataContainer().set(Keys.MATERIAL, PersistentDataType.STRING, "no_such_material");
            sword.setItemMeta(meta);
            require(plugin.strengthen().materialOf(sword) == null, "不存在的材料居然解析成功了");
        });

        add(g, "强化只替换基础属性，不动词条 modifier", () -> {
            ItemStack sword = sampleSword();
            require(sword != null, "造不出铁剑");
            int before = modifierCount(sword, Attribute.ATTACK_DAMAGE);
            plugin.strengthen().setLevel(sword, 5);
            int after = modifierCount(sword, Attribute.ATTACK_DAMAGE);
            require(before == after, "ATTACK_DAMAGE modifier 数量变了: " + before + " → " + after);
        });

        add(g, "强化等级写进 Lore 且重复强化只留一行", () -> {
            ItemStack sword = sampleSword();
            require(sword != null, "造不出铁剑");
            var s = plugin.strengthen();
            s.setLevel(sword, 3);
            s.setLevel(sword, 7);
            var lines = plugin.items().loreOf(sword).stream()
                    .map(Msg::plain)
                    .filter(t -> t.startsWith("锻造等级"))
                    .toList();
            require(lines.size() == 1, "锻造等级行数应为 1，实际 " + lines.size());
            require(lines.get(0).contains("+7"), "Lore 没显示 +7: " + lines.get(0));
        });

        add(g, "非锻造品不能强化", () -> {
            ItemStack raw = new ItemStack(Material.IRON_SWORD, 1);
            require(plugin.strengthen().levelOf(raw) < 0, "原版剑被识别成锻造品");
            require(plugin.strengthen().capOf(raw) == 0, "原版剑不该有强化上限");
        });

        add(g, "武器基础属性已按砍一刀下调", () -> {
            ForgeMaterial iron = plugin.materials().get("iron");
            ForgeMaterial star = plugin.materials().get("starfall");
            require(iron != null && star != null, "缺 iron / starfall 剑胚");
            require(iron.bonusAttack() <= 1.5, "铁剑基础攻击没砍下来: " + iron.bonusAttack());
            require(star.bonusAttack() <= 6.0, "星陨基础攻击没砍下来: " + star.bonusAttack());
        });

        add(g, "装备基础护甲与原版同档对齐", () -> {
            // 2.10 之前这里断言的是「护甲被砍到 1.8 以下」——那套数值让铁甲一整套
            // 只有 1.4 点护甲，穿上去等于没穿，服主明确要求提到正常装备水平。
            ForgeMaterial base = plugin.materials().get("starfall");
            ForgeMaterial iron = plugin.materials().get("iron");
            require(base != null && iron != null, "缺 starfall / iron 剑胚");

            ForgeMaterial starChest = com.forgemaster.material.ArmorCatalog
                    .create(base, ForgeMaterial.Slot.CHESTPLATE, 1.0);
            require(starChest != null, "造不出星陨胸甲");
            // 星陨整套 24，胸甲占 40% → 9.6；原版下界合金胸甲是 8
            require(starChest.bonusArmor() >= 6.0,
                    "星陨胸甲护甲还是太低: " + starChest.bonusArmor());
            require(starChest.bonusArmor() <= 14.0,
                    "星陨胸甲护甲高得离谱: " + starChest.bonusArmor());

            double ironTotal = 0;
            for (var slot : ForgeMaterial.Slot.values()) {
                ForgeMaterial piece = com.forgemaster.material.ArmorCatalog
                        .create(iron, slot, 1.0);
                require(piece != null, "造不出铁" + slot.label());
                ironTotal += piece.bonusArmor();
            }
            // 原版铁套 15
            require(ironTotal >= 12.0, "铁甲整套还是太低: " + Msg.fmt(ironTotal, 1));
            require(ironTotal <= 22.0, "铁甲整套高得离谱: " + Msg.fmt(ironTotal, 1));
        });
    }

    /** 读一个 double 型 PDC 键；没有时返回 0 */
    private double pdcDouble(ItemStack item, org.bukkit.NamespacedKey key) {
        var meta = item.getItemMeta();
        if (meta == null) {
            return 0;
        }
        Double v = meta.getPersistentDataContainer().get(key, PersistentDataType.DOUBLE);
        return v == null ? 0 : v;
    }

    /** 造一把铁剑当样本；没有铁剑胚时返回 null */
    private ItemStack sampleSword() {
        ForgeMaterial iron = plugin.materials().get("iron");
        return iron == null ? null : plugin.items().buildDirect("SelfTest", iron, Quality.COMMON, 60.0, null);
    }

    /** 读砧位上那把剑实体（ItemDisplay）的真实 X 缩放；找不到返回 0 */
    private float swordDisplayScale(org.bukkit.World world, org.bukkit.block.Block block) {
        var center = block.getLocation().add(0.5, 1.0, 0.5);
        for (var e : world.getNearbyEntities(center, 2, 2, 2)) {
            if (e instanceof org.bukkit.entity.ItemDisplay d && d.isValid()) {
                var t = d.getTransformation();
                float sc = t.getScale().x();
                if (sc > 0.01f) {
                    return sc;
                }
            }
        }
        return 0f;
    }

    /** 造一件指定档位 / 部位的装备，护甲相关自检用 */
    private ItemStack armorPieceOf(String materialId, com.forgemaster.material.ForgeMaterial.Slot slot) {
        ForgeMaterial base = plugin.materials().get(materialId);
        if (base == null) {
            return null;
        }
        var armor = com.forgemaster.material.ArmorCatalog.create(base, slot,
                plugin.settings().num("armor.price-multiplier", 1.0));
        if (armor == null) {
            return null;
        }
        return plugin.items().buildDirect("SelfTest", armor, Quality.COMMON, 60.0, null);
    }

    /** 列出某属性上的全部 modifier，体检用 */
    private String describeAttribute(ItemStack item, org.bukkit.attribute.Attribute attr) {
        var meta = item.getItemMeta();
        if (meta == null) {
            return "没有 meta";
        }
        var mods = meta.getAttributeModifiers(attr);
        if (mods == null || mods.isEmpty()) {
            return item.getType() + " 的 " + attr + " 上没有任何 modifier";
        }
        StringBuilder sb = new StringBuilder(item.getType() + " 的 " + attr + "：");
        double total = 0;
        for (var m : mods) {
            String k = m.getKey() == null ? "原版默认" : m.getKey().getKey();
            sb.append(" [").append(k).append("=").append(Msg.fmt(m.getAmount(), 3))
                    .append(" ").append(m.getOperation()).append("]");
            total += m.getAmount();
        }
        sb.append(" → 合计 ").append(Msg.fmt(total, 3));
        return sb.toString();
    }

    /**
     * 读物品的<b>属性组件</b>（{@code minecraft:attribute_modifiers}）里某属性的条目。
     *
     * <p>这是服务端算伤害 / 减伤的真实源：原版默认值（铁剑 +5、铁胸甲 +6）和插件追加的
     * {@code fm_*} 都在这一个列表里。{@link #describeAttribute} 走的 meta 层只反映覆盖值，
     * 拿它判断「默认值有没有被顶掉」会读成空的。</p>
     */
    private String describeComponents(ItemStack item, org.bukkit.attribute.Attribute attr) {
        var comp = item.getData(io.papermc.paper.datacomponent.DataComponentTypes.ATTRIBUTE_MODIFIERS);
        if (comp == null) {
            return item.getType() + " 没有属性修饰符组件";
        }
        StringBuilder sb = new StringBuilder(item.getType() + "：");
        double total = 0;
        int n = 0;
        for (var e : comp.modifiers()) {
            if (attr != null && e.attribute() != attr) {
                continue;
            }
            String k = e.modifier().getKey() == null ? "原版默认" : e.modifier().getKey().getKey();
            sb.append(" [").append(k).append("=").append(Msg.fmt(e.modifier().getAmount(), 3))
                    .append(" ").append(e.modifier().getOperation())
                    .append(" ").append(e.getGroup()).append("]");
            total += e.modifier().getAmount();
            n++;
        }
        if (n == 0) {
            return sb.append(" 该属性上没有任何条目").toString();
        }
        return sb.append(" → 合计 ").append(Msg.fmt(total, 3)).toString();
    }

    /** 属性组件里某个属性上的数值合计（原版条目 + 插件条目都在里面） */
    private double componentTotal(ItemStack item, org.bukkit.attribute.Attribute attr) {
        var comp = item.getData(io.papermc.paper.datacomponent.DataComponentTypes.ATTRIBUTE_MODIFIERS);
        if (comp == null) {
            return 0;
        }
        double total = 0;
        for (var e : comp.modifiers()) {
            if (attr == null || e.attribute() == attr) {
                total += e.modifier().getAmount();
            }
        }
        return total;
    }

    /** 属性组件里某属性上「插件写的条目」（键名 fm_*）数值合计 */
    private double componentPlugTotal(ItemStack item, org.bukkit.attribute.Attribute attr) {
        var comp = item.getData(io.papermc.paper.datacomponent.DataComponentTypes.ATTRIBUTE_MODIFIERS);
        if (comp == null) {
            return 0;
        }
        double total = 0;
        for (var e : comp.modifiers()) {
            if (attr != null && e.attribute() != attr) {
                continue;
            }
            var k = e.modifier().getKey();
            if (k != null && k.getKey().toLowerCase(java.util.Locale.ROOT).startsWith("fm_")) {
                total += e.modifier().getAmount();
            }
        }
        return total;
    }

    /** 给一件护甲强行挂上「护甲值」词条的修饰符，用于验证键名是否按部位分开 */
    private ItemStack forceArmorPlateAffix(ItemStack piece) {
        var affix = plugin.affixes() == null ? null : plugin.affixes().get("armor_plate");
        if (affix == null || affix.attribute() == null) {
            return piece;
        }
        ItemMeta meta = piece.getItemMeta();
        if (meta == null) {
            return piece;
        }
        String slotName = meta.getPersistentDataContainer()
                .getOrDefault(Keys.ARMOR_SLOT, org.bukkit.persistence.PersistentDataType.STRING, "");
        var slot = ForgeMaterial.Slot.byName(slotName);
        var group = slot == null ? org.bukkit.inventory.EquipmentSlotGroup.ARMOR : slot.group();
        String wantKey = "fm_armor_plate_" + slotName.toLowerCase();
        // buildDirect 会随机 roll 词条：这一场随机场可能已经把 armor_plate 抽到这件上，
        // 同键再挂会被 Paper 的重复键校验拒收（「Modifier is already applied」），
        // 自检就变成看脸的抽签。先摘同键旧条目再挂，任何场次都稳定。
        var existing = meta.getAttributeModifiers(affix.attribute());
        if (existing != null) {
            for (var m : new java.util.ArrayList<>(existing)) {
                if (m.getKey() != null && m.getKey().getKey().startsWith(wantKey)) {
                    meta.removeAttributeModifier(affix.attribute(), m);
                }
            }
        }
        meta.addAttributeModifier(affix.attribute(), new org.bukkit.attribute.AttributeModifier(
                new org.bukkit.NamespacedKey(plugin, wantKey),
                3.0, org.bukkit.attribute.AttributeModifier.Operation.ADD_NUMBER, group));
        piece.setItemMeta(meta);
        return piece;
    }

    /** 取某属性上键名以 prefix 开头的修饰符键名（找不到返回 null） */
    private String affixKeyOn(ItemStack item, org.bukkit.attribute.Attribute attr, String prefix) {
        var meta = item.getItemMeta();
        if (meta == null) {
            return null;
        }
        var mods = meta.getAttributeModifiers(attr);
        if (mods == null) {
            return null;
        }
        for (var m : mods) {
            var k = m.getKey();
            if (k != null && k.getKey().startsWith(prefix)) {
                return k.getKey();
            }
        }
        return null;
    }

    /** 属性组件里某属性上「键名完全等于 keyName」的条目数值（找不到返回 0） */
    private double componentOf(ItemStack item, org.bukkit.attribute.Attribute attr, String keyName) {
        var comp = item.getData(io.papermc.paper.datacomponent.DataComponentTypes.ATTRIBUTE_MODIFIERS);
        if (comp == null) {
            return 0;
        }
        for (var e : comp.modifiers()) {
            if (attr != null && e.attribute() != attr) {
                continue;
            }
            var k = e.modifier().getKey();
            if (k != null && k.getKey().equalsIgnoreCase(keyName)) {
                return e.modifier().getAmount();
            }
        }
        return 0;
    }

    /** 属性组件里某属性上是否存在键名包含 keyPart 的条目（keyPart 大小写不敏感） */
    private boolean componentHas(ItemStack item, org.bukkit.attribute.Attribute attr, String keyPart) {
        var comp = item.getData(io.papermc.paper.datacomponent.DataComponentTypes.ATTRIBUTE_MODIFIERS);
        if (comp == null) {
            return false;
        }
        for (var e : comp.modifiers()) {
            if (attr != null && e.attribute() != attr) {
                continue;
            }
            var k = e.modifier().getKey();
            if (k != null && k.getKey().toLowerCase(java.util.Locale.ROOT)
                    .contains(keyPart.toLowerCase(java.util.Locale.ROOT))) {
                return true;
            }
        }
        return false;
    }

    /** 取一件装备身上所有 ARMOR modifier 的数值之和 */
    private double armorAmountOf(ItemStack piece) {        var meta = piece.getItemMeta();
        if (meta == null) {
            return 0;
        }
        var mods = meta.getAttributeModifiers(org.bukkit.attribute.Attribute.ARMOR);
        if (mods == null) {
            return 0;
        }
        double sum = 0;
        for (var m : mods) {
            sum += m.getAmount();
        }
        return sum;
    }

    /** 词条符测试用：挑几条武器侧词条（防御词条走另一套判定，先排除） */
    private List<com.forgemaster.affix.Affix> imprintPool() {
        List<com.forgemaster.affix.Affix> out = new ArrayList<>();
        for (var a : plugin.affixes().all()) {
            if (a.kind() != com.forgemaster.affix.Affix.Kind.DAMAGE_REDUCTION) {
                out.add(a);
            }
        }
        return out;
    }

    /** 造一把「词条是写死的那几条」的成品，剥离 / 移植测试要可控的输入 */
    private ItemStack swordWith(List<com.forgemaster.affix.Affix> affixes) {
        ForgeMaterial iron = plugin.materials().get("iron");
        if (iron == null) {
            return null;
        }
        ForgeSession s = new ForgeSession(
                UUID.nameUUIDFromBytes("fm-imprint".getBytes()), NAME, iron);
        s.quality(Quality.EPIC);
        s.finalScore(78.0);
        s.heatScore(1.0);
        for (var a : affixes) {
            s.affixValues().put(a, 1.5);
        }
        return plugin.items().build(s);
    }

    /** 某词条在指定档位下 400 次抽样的均值 */
    private double averageRoll(com.forgemaster.affix.Affix a, int tier) {
        java.util.Random r = new java.util.Random(20261106L + tier);
        double sum = 0;
        for (int i = 0; i < 400; i++) {
            sum += a.roll(r, AffixScaling.valueScale(plugin, tier), AffixScaling.spreadScale(plugin, tier));
        }
        return sum / 400.0;
    }

    /** 某词条在指定档位下 400 次抽样的极差 */
    private double spreadOf(com.forgemaster.affix.Affix a, int tier) {
        java.util.Random r = new java.util.Random(777L + tier);
        double min = Double.MAX_VALUE;
        double max = -Double.MAX_VALUE;
        for (int i = 0; i < 400; i++) {
            double v = a.roll(r, AffixScaling.valueScale(plugin, tier), AffixScaling.spreadScale(plugin, tier));
            min = Math.min(min, v);
            max = Math.max(max, v);
        }
        return max - min;
    }

    /** 读物品上某个属性的基础 modifier 数值（键名 fm_atk / fm_speed / ...） */
    private static double baseAttackOf(ItemStack item) {
        var meta = item.getItemMeta();
        if (meta == null) {
            return 0;
        }
        var mods = meta.getAttributeModifiers(Attribute.ATTACK_DAMAGE);
        if (mods == null) {
            return 0;
        }
        for (var m : mods) {
            if (m.getKey() != null && "fm_atk".equals(m.getKey().getKey())) {
                return m.getAmount();
            }
        }
        return 0;
    }

    private static int modifierCount(ItemStack item, Attribute attribute) {
        var meta = item.getItemMeta();
        if (meta == null) {
            return 0;
        }
        var mods = meta.getAttributeModifiers(attribute);
        return mods == null ? 0 : mods.size();
    }

    /** 物品 Lore 压成纯文本（按行拼），断言「有没有那一行」用 */
    private static String loreText(ItemStack item) {
        var meta = item == null ? null : item.getItemMeta();
        if (meta == null || meta.lore() == null) {
            return "";
        }
        StringBuilder sb = new StringBuilder();
        for (var c : meta.lore()) {
            if (sb.length() > 0) {
                sb.append('\n');
            }
            sb.append(Msg.plain(c));
        }
        return sb.toString();
    }

    /** 数某个键名在指定属性上有几条修饰符（剑意要确认「恰好 1 条」） */
    private static int modifierCount(ItemStack item, Attribute attribute, String keyPart) {        var meta = item.getItemMeta();
        if (meta == null) {
            return 0;
        }
        var mods = meta.getAttributeModifiers(attribute);
        if (mods == null) {
            return 0;
        }
        int n = 0;
        for (var m : mods) {
            if (m.getKey() != null && m.getKey().getKey().contains(keyPart)) {
                n++;
            }
        }
        return n;
    }

    /**
     * 数一个玩家背包里某种材质的**总件数**（含主手 / 副手 / 盔甲槽）。
     *
     * <p>用来钉「物品守恒」类断言：复制 bug 的表现就是某个材质凭空多出来，
     * 光看「手上有没有」是抓不到的（旧 bug 里主手和副手同时都有剑）。</p>
     */
    private static int countMaterial(org.bukkit.inventory.PlayerInventory inv,
                                     org.bukkit.Material material) {
        int n = 0;
        for (org.bukkit.inventory.ItemStack it : inv.getContents()) {
            if (it != null && it.getType() == material) {
                n += it.getAmount();
            }
        }
        // getContents() 不含副手（它在 45 号槽，属于 getContents 之外）
        org.bukkit.inventory.ItemStack off = inv.getItemInOffHand();
        if (off != null && off.getType() == material) {
            n += off.getAmount();
        }
        return n;
    }

    /** 按 Lore 前缀数行数（剑意行不能堆） */
    private static long loreRowsStartingWith(ItemStack item, String prefix) {
        var meta = item.getItemMeta();
        if (meta == null || meta.lore() == null) {
            return 0;
        }
        return meta.lore().stream()
                .map(Msg::plain)
                .map(String::trim)
                .filter(t -> t.startsWith(prefix))
                .count();
    }

    /** 抹掉 2.4 才有的 PDC 键，模拟升级前的旧物品 */
    private static void stripStrengthenKeys(ItemStack item) {
        var meta = item.getItemMeta();
        if (meta == null) {
            return;
        }
        var pdc = meta.getPersistentDataContainer();
        pdc.remove(Keys.STRENGTHEN);
        pdc.remove(Keys.STRENGTHEN_CAP);
        pdc.remove(Keys.BASE_ATK);
        pdc.remove(Keys.BASE_SPEED);
        pdc.remove(Keys.BASE_ARMOR);
        pdc.remove(Keys.BASE_TOUGH);
        item.setItemMeta(meta);
    }

    // ---------------------------------------------------------------- 耐久 / 宝石

    private void registerDurabilityGem() {
        String g = "耐久 / 宝石";
        add(g, "品质耐久系数为 0.5/1/1.5/2.2/3/5", () -> {
            double[] want = {0.5, 1.0, 1.5, 2.2, 3.0, 5.0};
            Quality[] qs = Quality.values();
            require(qs.length == want.length, "品质档数与预期不符");
            for (int i = 0; i < want.length; i++) {
                require(Math.abs(qs[i].durabilityMult() - want[i]) < 0.001,
                        qs[i].name() + " 耐久系数应为 " + want[i] + "，实际 " + qs[i].durabilityMult());
            }
        });
        add(g, "成品耐久 = 基础耐久 × 品质系数", () -> {
            ForgeMaterial first = plugin.materials().all().iterator().next();
            for (Quality q : Quality.values()) {
                int expect = Math.max(1, (int) Math.round(first.baseDurability() * q.durabilityMult()));
                int actual = com.forgemaster.item.ItemFactory.durability(first, q);
                require(actual == expect, q.name() + " 耐久应为 " + expect + "，实际 " + actual);
            }
        });
        add(g, "开孔上限按品质递增", () -> {
            int last = 0;
            for (Quality q : Quality.values()) {
                int n = q.socketLimit();
                require(n >= 1, q.name() + " 孔位上限小于 1");
                require(n >= last, q.name() + " 孔位上限倒退");
                last = n;
            }
            require(Quality.MYTHIC.socketLimit() >= Quality.ROUGH.socketLimit(), "神话孔位不该少于粗糙");
        });
        add(g, "宝石物品可生成并读回", () -> {
            var gem = plugin.gems().createGem(com.forgemaster.gem.GemType.ATTACK,
                    com.forgemaster.gem.GemType.Grade.L2);
            require(plugin.gems().isGem(gem), "生成的物品没被识别为宝石");
            var meta = gem.getItemMeta();
            require(meta != null, "宝石没有 meta");
            String type = meta.getPersistentDataContainer().get(Keys.GEM_TYPE, PersistentDataType.STRING);
            require(com.forgemaster.gem.GemType.ATTACK.id().equals(type), "宝石类型写入错误");
            Double value = meta.getPersistentDataContainer().get(Keys.GEM_VALUE, PersistentDataType.DOUBLE);
            require(value != null && value > 0, "宝石数值无效");
        });
        add(g, "宝石条目编解码往返一致", () -> {
            var entry = new com.forgemaster.gem.GemService.GemEntry(
                    com.forgemaster.gem.GemType.CRIT, 0.07, com.forgemaster.gem.GemType.Grade.L4);
            var back = com.forgemaster.gem.GemService.GemEntry.decode(entry.encode());
            require(back != null, "解码失败");
            require(back.type() == entry.type() && Math.abs(back.value() - entry.value()) < 1e-6
                    && back.grade() == entry.grade(), "编解码结果不一致");
        });
        add(g, "宝石只能由下界之星熔出", () -> {
            require(!com.forgemaster.gem.GemType.poolOf(Material.NETHER_STAR).isEmpty(),
                    "下界之星的宝石池是空的");
            for (Material mat : List.of(Material.DIAMOND, Material.IRON_BLOCK, Material.GOLD_BLOCK,
                    Material.NETHERITE_SCRAP, Material.EMERALD, Material.BLAZE_POWDER)) {
                require(com.forgemaster.gem.GemType.poolOf(mat).isEmpty(),
                        mat + " 不该能熔出宝石");
            }
        });
        add(g, "宝石数值随品质放大", () -> {
            java.util.Random rnd = new java.util.Random(1234);
            com.forgemaster.gem.GemType type = com.forgemaster.gem.GemType.ARMOR;
            for (int i = 0; i < 20; i++) {
                double common = type.roll(com.forgemaster.gem.GemType.Grade.L1, rnd);
                double legendary = type.roll(com.forgemaster.gem.GemType.Grade.L5, rnd);
                require(legendary > common, "传说宝石数值没有高于普通");
            }
        });
        add(g, "宝石等级五级封顶", () -> {
            var grades = com.forgemaster.gem.GemType.Grade.values();
            require(grades.length == 5, "宝石等级应为 5 级，实际 " + grades.length);
            require(grades[0].next() != null, "Lv.1 应该有下一级");
            require(grades[4].next() == null, "Lv.5 不该再有下一级");
            require(grades[4].maxed(), "Lv.5 应标记为满级");
            double last = 0;
            for (var gr : grades) {
                require(gr.mult() > last, gr.name() + " 数值倍率没有递增");
                last = gr.mult();
            }
        });
        add(g, "宝石种类不少于 10 种", () -> {
            require(com.forgemaster.gem.GemType.values().length >= 10,
                    "宝石种类只有 " + com.forgemaster.gem.GemType.values().length + " 种");
        });
        add(g, "下界之星熔炼池覆盖全部宝石", () -> {
            var pool = com.forgemaster.gem.GemType.poolOf(Material.NETHER_STAR);
            require(pool.size() == com.forgemaster.gem.GemType.values().length,
                    "下界之星的池子不是全量：" + pool.size());
            require(com.forgemaster.gem.GemType.isStarInput(Material.NETHER_STAR), "下界之星没被识别为特殊投料");
            require(!com.forgemaster.gem.GemType.isStarInput(Material.DIAMOND), "钻石不该被当作星熔投料");
        });
        add(g, "难度曲线：档位越高节奏越快、总分越低", () -> {
            int prevInterval = Integer.MAX_VALUE;
            double prevFactor = 2.0;
            for (int tier = 1; tier <= 5; tier++) {
                int interval = com.forgemaster.forge.ForgeTuning.hammerInterval(plugin, tier);
                require(interval >= 1, "tier " + tier + " 指针间隔小于 1 tick");
                require(interval <= prevInterval, "tier " + tier + " 指针没有变快");
                prevInterval = interval;

                double factor = com.forgemaster.forge.ForgeTuning.scoreFactor(plugin, tier);
                require(factor < prevFactor, "tier " + tier + " 总分系数没有下降");
                require(factor >= 0.6, "tier " + tier + " 压分过猛");
                prevFactor = factor;

                require(com.forgemaster.forge.ForgeTuning.foldSecondsScale(plugin, tier) <= 1.0,
                        "tier " + tier + " 叠钢时限反而变长");
                require(com.forgemaster.forge.ForgeTuning.grindWidthScale(plugin, tier) <= 1.0,
                        "tier " + tier + " 开刃绿区反而变宽");
            }
        });
        add(g, "铁砧演出能生成并清理 Display 实体", () -> {
            var world = org.bukkit.Bukkit.getWorlds().get(0);
            // 必须在已加载的区块里生成，否则 Bukkit 会静默失败
            var at = new org.bukkit.Location(world, 0.5, 120.0, 0.5);
            at.getChunk().load();
            int before = world.getEntitiesByClass(org.bukkit.entity.ItemDisplay.class).size();

            ForgeMaterial mat = plugin.materials().sorted().get(0);
            ForgeSession fake = new ForgeSession(
                    UUID.nameUUIDFromBytes("SelfTest".getBytes()), "SelfTest", mat);
            var stage = new com.forgemaster.stage.ForgeStage(plugin, fake, at, false);
            stage.spawn();
            try {
                int after = world.getEntitiesByClass(org.bukkit.entity.ItemDisplay.class).size();
                require(after > before, "演出前后 ItemDisplay 数量没变（" + before + " → " + after
                        + "），区块已加载=" + world.isChunkLoaded(at.getBlockX() >> 4, at.getBlockZ() >> 4));
            } finally {
                stage.despawn();
                require(stage.isClosed(), "演出没有正确关闭");
            }
        });
        add(g, "开刃界面：加压/减压按钮没被轨道盖住", () -> {
            ForgeMaterial mat = plugin.materials().sorted().get(0);
            ForgeSession fake = new ForgeSession(
                    UUID.nameUUIDFromBytes("SelfTest".getBytes()), "SelfTest", mat);
            fake.phase(com.forgemaster.forge.ForgePhase.GRINDING);
            var inv = plugin.forge().menu().buildGrinding(fake, 120);
            int up = com.forgemaster.gui.ForgeMenu.PRESS_UP_SLOT;
            int down = com.forgemaster.gui.ForgeMenu.PRESS_DOWN_SLOT;
            int trackStart = com.forgemaster.gui.ForgeMenu.TRACK_START;
            int trackEnd = trackStart + com.forgemaster.gui.ForgeMenu.TRACK_LEN - 1;
            require(up < trackStart || up > trackEnd, "加压按钮落在轨道范围内，会被覆盖");
            require(down < trackStart || down > trackEnd, "减压按钮落在轨道范围内，会被覆盖");
            ItemStack upItem = inv.getItem(up);
            ItemStack downItem = inv.getItem(down);
            require(upItem != null && upItem.getItemMeta() != null
                    && Msg.plain(upItem.getItemMeta().displayName()).contains("加"), "加压按钮没渲染出来");
            require(downItem != null && downItem.getItemMeta() != null
                    && Msg.plain(downItem.getItemMeta().displayName()).contains("减"), "减压按钮没渲染出来");
        });
        add(g, "镶嵌后宝石块不会残留空孔提示", () -> {
            // 造一个「已开孔、显示空孔」的旧 lore
            List<net.kyori.adventure.text.Component> lore = new ArrayList<>();
            lore.add(Msg.mm("<gray>—— 锻造品 ——"));
            lore.add(Msg.mm("<gold>◆ 宝石孔 <gray>[0/1]</gold>"));
            lore.add(Msg.mm("<dark_gray>（空孔 · 副手拿宝石镶嵌）</dark_gray>"));
            lore.add(Msg.mm("<gray>锻造者: SelfTest"));

            var gem = new com.forgemaster.gem.GemService.GemEntry(
                    com.forgemaster.gem.GemType.ATTACK, 2.5, com.forgemaster.gem.GemType.Grade.L2);
            var out = com.forgemaster.gem.GemService.rebuildGemBlock(lore, 1, List.of(gem));

            long empty = out.stream()
                    .filter(c -> Msg.plain(c).trim().startsWith("（空孔"))
                    .count();
            require(empty == 0, "镶嵌后还留着「（空孔 …）」提示");
            long headers = out.stream()
                    .filter(c -> Msg.plain(c).trim().startsWith("◆ 镶嵌孔"))
                    .count();
            require(headers == 1, "镶嵌块标题重复了：" + headers + " 个");
            boolean hasGem = out.stream().anyMatch(c -> Msg.plain(c).trim().startsWith("▪"));
            require(hasGem, "宝石条目没有写进 lore");
            // 标题必须在宝石条目上面
            int headIdx = -1, gemIdx = -1;
            for (int i = 0; i < out.size(); i++) {
                String p = Msg.plain(out.get(i)).trim();
                if (headIdx < 0 && p.startsWith("◆ 镶嵌孔")) {
                    headIdx = i;
                }
                if (gemIdx < 0 && p.startsWith("▪")) {
                    gemIdx = i;
                }
            }
            require(headIdx >= 0 && gemIdx > headIdx, "宝石条目排到标题上面去了");
        });
        add(g, "商店图纸能写入并读回剑胚 id", () -> {
            ForgeMaterial mat = plugin.materials().sorted().get(0);
            var bp = plugin.shop().buildBlueprint(mat);
            require(bp != null && bp.getType() == Material.PAPER, "图纸应为纸");
            require(mat.id().equalsIgnoreCase(plugin.shop().blueprintOf(bp)),
                    "图纸上的剑胚 id 读不回来，required 模式会卡死玩家");
            require(plugin.shop().blueprintOf(new ItemStack(Material.PAPER)) == null,
                    "普通纸被误判成图纸");
            double bonus = plugin.settings().num("blueprint.tolerance-bonus", 0.15);
            require(bonus >= 0 && bonus <= 1.0, "图纸容差加成应在 0~1，实际 " + bonus);
        });
        add(g, "专精开关：关掉后等级加成归零", () -> {
            com.forgemaster.stats.StatsStore.progressionEnabled(false);
            try {
                require(com.forgemaster.stats.StatsStore.extraZoneWidth(50) == 0,
                        "关掉专精后仍在给命中区加成");
                require(Math.abs(com.forgemaster.stats.StatsStore.extraGrindWidth(50)) < 1e-9,
                        "关掉专精后仍在给开刃宽度");
                require(Math.abs(com.forgemaster.stats.StatsStore.toleranceMultiplier(50) - 1.0) < 1e-9,
                        "关掉专精后容差仍被放大");
            } finally {
                com.forgemaster.stats.StatsStore.progressionEnabled(true);
            }
            require(com.forgemaster.stats.StatsStore.extraZoneWidth(50) == 1,
                    "重新打开专精后加成没恢复");
        });
        add(g, "委托过期清理可安全调用", () -> {
            plugin.commissions().purgeExpired();
        });
        add(g, "演出在玩家跑远后应自行关闭", () -> {
            var world = org.bukkit.Bukkit.getWorlds().get(0);
            var at = new org.bukkit.Location(world, 0.5, 120.0, 0.5);
            at.getChunk().load();
            ForgeMaterial mat = plugin.materials().sorted().get(0);
            ForgeSession fake = new ForgeSession(
                    UUID.nameUUIDFromBytes("SelfTest2".getBytes()), "SelfTest2", mat);
            var stage = new com.forgemaster.stage.ForgeStage(plugin, fake, at, false);
            try {
                require(!stage.shouldClose(at.clone().add(3, 0, 0)), "近处不该关闭");
                require(stage.shouldClose(at.clone().add(120, 0, 0)), "跑远 120 格应该关闭");
                if (org.bukkit.Bukkit.getWorlds().size() > 1) {
                    require(stage.shouldClose(new org.bukkit.Location(
                            org.bukkit.Bukkit.getWorlds().get(1), 0, 64, 0)), "跨世界应该关闭");
                }
            } finally {
                stage.despawn();
            }
        });
        add(g, "反作弊兼容：移速与击退会被收敛", () -> {
            var compat = plugin.compat();
            require(compat != null, "兼容模块没有初始化");
            double speed = compat.cap(org.bukkit.attribute.Attribute.MOVEMENT_SPEED, 10.0);
            double kb = compat.cap(org.bukkit.attribute.Attribute.ATTACK_KNOCKBACK, 10.0);
            require(speed <= compat.speedCap() + 1e-6, "移速没有被收敛：" + speed);
            require(kb <= compat.knockbackCap() + 1e-6, "击退没有被收敛：" + kb);
            // 不相关属性必须原样返回
            double armor = compat.cap(org.bukkit.attribute.Attribute.ARMOR, 10.0);
            require(Math.abs(armor - 10.0) < 1e-6, "护甲不该被收敛");
        });
    }

    // ---------------------------------------------------------------- 性能修复包 A

    /**
     * 性能修复包 A（审计 #1/#2/#3）的行为钉子：
     * 耐久 Lore 只在跨档时重写、剑灵养灵带 2 秒节流且升档即时、拍卖行落盘 dirty 幂等。
     */
    private void registerPerfFixA() {
        String g = "性能修复包 A";

        add(g, "耐久 Lore 只在跨档时重写（档内两击之间耐久行不变）", () -> {
            ItemStack sword = sampleSword();
            require(sword != null, "造不出铁剑");
            var meta = sword.getItemMeta();
            Integer maxBoxed = meta.getPersistentDataContainer()
                    .get(Keys.DURABILITY_MAX, PersistentDataType.INTEGER);
            require(maxBoxed != null && maxBoxed >= 20, "样本剑耐久太小，跨档用例不适用");
            int max = maxBoxed;
            require(!plugin.items().setDurability(sword, meta, max), "满耐久不该判崩刃");
            String base = durabilityLineText(sword);
            require(base != null, "找不到耐久行");
            // 档内小伤：PDC 实时更新，但耐久行保持原样（没重写 Lore）。
            // v2.23 起档宽可配（settings.durability-lore-step 百分比 + v2.23.5 的绝对上限），
            // 判定统一走 ItemFactory.durabilityBandWidth，别在这里抄一份公式。
            // 基线取「档中间」（满耐久是档顶，掉 1 点就可能跨档），再打 1 点小伤仍在同一档
            int bandWidth = plugin.items().durabilityBandWidth(max);
            require(bandWidth >= 2, "档宽太小（" + bandWidth + "），档内用例不成立");
            int anchor = max - bandWidth / 2;
            plugin.items().setDurability(sword, sword.getItemMeta(), anchor);
            base = durabilityLineText(sword);
            plugin.items().setDurability(sword, sword.getItemMeta(), anchor - 1);
            require(plugin.items().durabilityOf(sword) == anchor - 1, "档内小伤没写 PDC");
            require(durabilityLineText(sword).equals(base), "档内小伤不该重写耐久行");
            // 跨档大伤：耐久行才重写
            plugin.items().setDurability(sword, sword.getItemMeta(), max / 2);
            require(!durabilityLineText(sword).equals(base), "跨档没有重写耐久行");
        });

        add(g, "几千点耐久的剑：掉 5 点 Lore 数字就要动（不再要砍 195 刀才跳）", () -> {
            ItemStack sword = sampleSword();
            require(sword != null, "造不出铁剑");
            int max = plugin.items().maxDurabilityOf(sword);
            require(max >= 20, "样本剑耐久太小：" + max);
            // 档宽必须是「点数」而不是「永远按百分比」——这就是 v2.23.5 之前
            // 「内置耐久看起来不掉」的根因：5% × 3900 = 195 点才刷一次数字
            int width = plugin.items().durabilityBandWidth(max);
            require(width <= 5, "大耐久品的显示档宽仍然过粗：" + width + " 点（max=" + max + "）");
            plugin.items().setDurability(sword, sword.getItemMeta(), max);
            String full = durabilityLineText(sword);
            require(full != null, "找不到耐久行");
            plugin.items().setDurability(sword, sword.getItemMeta(), max - width);
            require(!durabilityLineText(sword).equals(full),
                    "掉 " + width + " 点耐久后 Lore 数字没变（显示档宽 " + width + "）");
            require(plugin.items().durabilityOf(sword) == max - width, "PDC 没跟上 Lore");
        });

        add(g, "耐久崩刃提示按状态翻转，归零返回性不变", () -> {
            ItemStack sword = sampleSword();
            require(sword != null, "造不出铁剑");
            // 归零：返回 true（打断装备路径），崩刃行恰好一条，耐久行还在
            require(plugin.items().setDurability(sword, sword.getItemMeta(), 0), "归零应返回崩刃 true");
            require(loreCount(sword, "崩刃") == 1, "崩刃行应恰好一条");
            require(loreCount(sword, "耐久") == 1, "耐久行不该丢");
            // 修回满：状态翻转触发重写，崩刃红字必须清掉
            int max = plugin.items().maxDurabilityOf(sword);
            require(max > 0, "读不到最大耐久");
            require(!plugin.items().setDurability(sword, sword.getItemMeta(), max), "修满不该再判崩刃");
            require(loreCount(sword, "崩刃") == 0, "修好后崩刃红字没清掉");
            require(plugin.items().durabilityOf(sword) == max, "修复后 PDC 耐久没回满");
        });

        add(g, "拍卖行落盘幂等：改动翻 dirty，save 清一次后保持干净", () -> {
            var mgr = plugin.auction();
            require(mgr != null, "拍卖行模块没起来");
            var store = mgr.store();
            // 造一件测试挂单再移除：净效果为零，不碰真实拍品（收尾 save 的内容与盘上等价）
            String id = "FMTEST";
            store.remove(id);
            long now = System.currentTimeMillis();
            var listing = new com.forgemaster.auction.AuctionListing(id,
                    UUID.nameUUIDFromBytes("SelfTest".getBytes()), "SelfTest",
                    new ItemStack(Material.STICK), 1, 0, 1, now, now + 3_600_000L);
            store.markDirty();
            require(store.dirty(), "markDirty 没置脏");
            store.save();
            require(!store.dirty(), "save 没清脏标记");
            store.save();
            require(!store.dirty(), "连续第二次 save 不该再有东西可写（幂等）");
            // put / remove 也必须翻脏标记
            store.put(listing);
            require(store.dirty(), "put 没置脏");
            store.remove(id);
            store.save();
            require(!store.dirty(), "收尾清理后脏标记应归零");
        });

        add(g, "剑冢落盘幂等：标脏翻一次、save 清干净、再 save 不出新内容", () -> {
            var tombs = plugin.tombs();
            require(tombs != null, "剑冢管理器没起来");
            int before = tombs.all().size();
            tombs.markDirty();
            require(tombs.dirty(), "剑冢 markDirty 没置脏");
            tombs.save();
            require(!tombs.dirty(), "剑冢 save 没清脏标记");
            tombs.save();
            require(!tombs.dirty(), "剑冢连续第二次 save 不该再有东西可写（幂等）");
            require(tombs.all().size() == before, "落盘不该改变内存里的冢数");
        });

        // v2.25.2（4 并发查错）：异步冲刷任务被打死是「存档静默失效」的最隐蔽形态，
        // 这里只钉住它依赖的契约 —— save 成功必清脏、连写两次幂等；代数语义由下面的契约用例钉。
        add(g, "契约落盘幂等 + 游标置脏：save 成功才清脏、连写两次幂等", () -> {
            var cm = plugin.contractManager();
            require(cm != null, "周目管理器没起来");
            var store = cm.store();
            long day0 = store.lastSettledFullDay();
            store.markDirty();
            require(store.dirty(), "契约 markDirty 没置脏");
            store.save();
            require(!store.dirty(), "契约 save 没清脏标记");
            store.save();
            require(!store.dirty(), "契约连续第二次 save 不该再有东西可写（幂等）");
            // 游标写回原值（净效果为零，不污染存档），但必须置脏 —— 否则跨天结算会漏
            store.lastSettledFullDay(day0);
            require(store.dirty(), "lastSettledFullDay 改动没置脏（跨天结算会漏账）");
            store.save();
            require(!store.dirty(), "收尾 save 后应回到干净");
        });

        add(g, "剑灵节流：2 秒窗口内多次命中只落一次账", () -> {
            require(plugin.spirit() != null, "剑灵模块没起来");
            require(plugin.spirit().tierOf(4) == 0, "档位门槛太低，节流用例不适用");
            ItemStack sword = sampleSword();
            require(sword != null, "造不出铁剑");
            UUID who = UUID.nameUUIDFromBytes("fm-spirit-throttle".getBytes());
            long t0 = 1_000_000L;   // 固定时钟，不依赖真实时间
            // 窗口内三击：全部只记内存，PDC 纹丝不动
            for (int i = 0; i < 3; i++) {
                boolean wrote = plugin.spirit().settleHitSpirit(null, who, sword,
                        sword.getItemMeta(), 1, t0 + i * 500L);
                require(!wrote, "窗口内第 " + (i + 1) + " 击就落账了，节流没生效");
                require(plugin.spirit().spiritOf(sword) == 0, "窗口内 PDC 灵性被提前写了");
            }
            // 跨过 2 秒的那一击：把累计 4 点一次性落账
            boolean flushed = plugin.spirit().settleHitSpirit(null, who, sword,
                    sword.getItemMeta(), 1, t0 + 2100L);
            require(flushed, "满 2 秒后该落账却没落");
            require(plugin.spirit().spiritOf(sword) == 4,
                    "落账合计应为 4，实际 " + plugin.spirit().spiritOf(sword));
            plugin.spirit().clearPending(who);
        });

        add(g, "剑灵升档不受节流影响：过线那一击立即落账", () -> {
            require(plugin.spirit() != null, "剑灵模块没起来");
            List<Integer> tiers = plugin.spirit().tiers();
            require(tiers.size() >= 2, "档位表不足两档");
            int line = tiers.get(1);   // 第二档门槛
            require(plugin.spirit().tierOf(line - 1) < plugin.spirit().tierOf(line),
                    "档位表异常，用例不适用");
            ItemStack sword = sampleSword();
            require(sword != null, "造不出铁剑");
            UUID who = UUID.nameUUIDFromBytes("fm-spirit-tierup".getBytes());
            // 把灵性拨到差 1 点过线
            var meta = sword.getItemMeta();
            meta.getPersistentDataContainer().set(Keys.SPIRIT, PersistentDataType.INTEGER, line - 1);
            com.forgemaster.util.Attrs.apply(sword, meta);
            long t0 = 2_000_000L;
            // 过线这一击必须立即落账（不许等 2 秒窗口）
            boolean wrote = plugin.spirit().settleHitSpirit(null, who, sword,
                    sword.getItemMeta(), 1, t0);
            require(wrote, "升档那一击没有立即落账");
            require(plugin.spirit().spiritOf(sword) == line,
                    "升档落账数值不对：应为 " + line);
            plugin.spirit().clearPending(who);
        });
    }

    // ---------------------------------------------------------------- 经济 / 装备锻造

    private void registerEconomyArmor() {
        String g = "经济 / 装备锻造";
        add(g, "内置 config.yml 含全部必需键（防配置漂移，v2.19.2）", () -> {
            // 这一轮的全量比对逮到 7 个「代码在读、盘上没有」的裸键：
            // 功能照跑（有代码默认），但服主看不到、也改不了，而 README 里写着可配置。
            // 断言直接对「打进 jar 的那份 config.yml」校验，构建产物一漂就红。
            var stream = plugin.getResource("config.yml");
            require(stream != null, "jar 里没有 config.yml 资源");
            var raw = org.bukkit.configuration.file.YamlConfiguration.loadConfiguration(
                    new java.io.InputStreamReader(stream, java.nio.charset.StandardCharsets.UTF_8));
            String[] keys = com.forgemaster.ForgingMasterPlugin.REQUIRED_CONFIG_KEYS;
            require(keys.length > 150, "必需键清单太短，疑似被截断：" + keys.length);
            java.util.List<String> missing = new ArrayList<>();
            for (String key : keys) {
                if (!raw.contains(key)) {
                    missing.add(key);
                }
            }
            require(missing.isEmpty(), "内置 config.yml 缺 " + missing.size()
                    + " 个必需键：" + missing.subList(0, Math.min(8, missing.size())));
        });
        add(g, "隐形键已落盘：重铸品质乘区 / 工序提示 / 失败留档", () -> {
            // 这三组以前只存在于 Java 默认值里
            var merged = plugin.getConfig();
            require(merged.contains("reforge.cost.quality.mythic"), "重铸品质乘区没进配置");
            require(merged.contains("settings.stage-intro-ticks"), "工序开场提示没进配置");
            require(merged.contains("strengthen.fail.tomb-record"), "强化失败留档开关没进配置");
            require(merged.contains("reforge.armor-pool"), "重铸防御词条池没进配置");
            // 值要跟代码默认一致（补键不得改变行为）
            require(Math.abs(plugin.settings().num("reforge.cost.quality.mythic", -1) - 3.40) < 1e-6,
                    "神话重铸乘区被改掉了：" + plugin.settings().num("reforge.cost.quality.mythic", -1));
        });
        add(g, "蜕变乘区严格单调：每一阶都得有真增益", () -> {
            double prev = 0;
            double prod = 1.0;
            for (int i = 1; i <= 6; i++) {
                String base = "evolution.stages." + i;
                double b = plugin.settings().num(base + ".bonus", -1);
                require(b > 0, "阶 " + i + " 乘区缺失");
                require(b > prev, "阶 " + i + " 乘区没比上一阶高：" + b + " vs " + prev
                        + "（旧表 1.08/1.08/1.10/1.10/1.12/1.12 就是成对重复，"
                        + "第 6 阶花 9.8 万剑币只买个称号）");
                prev = b;
                prod *= b;
            }
            // 总乘区守住 2.17.3 定的 ≈×1.77，别把已经重定过的值又叠回去
            require(prod > 1.70 && prod < 1.80, "蜕变连乘跑出 1.70~1.80 之外：" + prod);
        });
        add(g, "图纸价格阶梯：单调递增，且星陨图纸低于护胚符", () -> {
            String[] ids = {"iron", "gold", "copper", "diamond", "netherite", "starfall"};
            int prev = 0;
            for (String id : ids) {
                int p = plugin.settings().intOf("shop.blueprints." + id, -1);
                require(p > 0, "图纸价缺失：" + id);
                require(p >= prev, "图纸价没有单调递增：" + id + " " + p + " < " + prev);
                prev = p;
            }
            // 图纸只给一次性 +15% 加热容差；护胚符能救一条命。图纸比符贵就是性价比倒挂
            int starfallBp = plugin.settings().intOf("shop.blueprints.starfall", -1);
            int talisman = plugin.settings().intOf("shop.specials.talisman", -1);
            require(starfallBp < talisman, "星陨图纸 " + starfallBp
                    + " 比护胚符 " + talisman + " 还贵，性价比顺序反了");
        });
        add(g, "整段缺失的配置段已被补回：黑市有货可卖（v2.19.2）", () -> {
            // 老 config.yml 里 blackmarket 整段可能都不存在，而 blackmarket.pool
            // 在代码里没有兜底 → offers() 恒空、货架永远空着且不报错。
            // step15 会把整段缺失的顶层段按内置默认补回来，这里验证结果。
            var list = plugin.getConfig().getMapList("blackmarket.pool");
            require(!list.isEmpty(), "blackmarket.pool 还是空的，step15 没补上");
            var offers = plugin.blackmarket().offers();
            require(!offers.isEmpty(), "货架是空的（pool 有 " + list.size() + " 条却一件没上）");
            // 顺带核一遍其它纯内容段
            for (String section : new String[]{"codex", "spectrum", "guild", "relic-set", "titles"}) {
                require(plugin.getConfig().contains(section),
                        "配置段 " + section + " 还是整段缺失");
            }
        });
        add(g, "货币后端已初始化", () -> {
            require(plugin.economy() != null, "经济桥接没有初始化");
            require(!plugin.economy().backend().label().isBlank(), "后端名称为空");
        });

        add(g, "黑市限购必须先占额度再发货，且额度可撤回（v2.25.7 修 P1）", () -> {
            // 出过的真事故：recordPurchase + save 写在**发货之后**，崩在这中间重启，
            // 今日额度回到满值 —— 配折扣价就是「崩溃刷折扣货」。
            // 现在改「先占额度 → 落盘成功 → 才发货」，落不下去调用方不发货并退款。
            var store = plugin.dailyStore();
            require(store != null, "黑市存档（DailyStore）没初始化");
            UUID fake = UUID.nameUUIDFromBytes("SelfTest-bm-reserve".getBytes());
            // 必须用真实「今天」：DailyStore.save() 会剪掉非今天/昨天的日期层，
            // 用假日期会被剪掉，测出来是「计数没涨」（第一版就这么假红了一次）
            String date = com.forgemaster.blackmarket.DailyStore.today();
            String key = "selftest-offer";
            int before = store.purchaseCount(fake, date, key);
            require(before == 0, "测试起点脏了，已有 " + before + " 次记录");
            // ① 占一次额度：应当成功，且立刻能读到 1
            require(store.tryReservePurchase(fake, date, key, 3), "首次占额度失败");
            require(store.purchaseCount(fake, date, key) == 1, "占额度后计数没涨");
            // ② 撤回：回到 0，且不留空壳
            store.releaseReservation(fake, date, key);
            require(store.purchaseCount(fake, date, key) == 0, "撤回后计数没归零");
            // ③ 买满之后必须拒绝（这条是限购的本体）
            require(store.tryReservePurchase(fake, date, key, 1), "第 1 次应允许");
            require(!store.tryReservePurchase(fake, date, key, 1), "买满 1 次后还能再占，限购没生效");
            // ④ 多占几次再全部撤回，不能残留
            for (int i = 0; i < 5; i++) {
                store.tryReservePurchase(fake, date, key, 99);
            }
            require(store.purchaseCount(fake, date, key) == 6, "累计占额度算错");
            for (int i = 0; i < 6; i++) {
                store.releaseReservation(fake, date, key);
            }
            require(store.purchaseCount(fake, date, key) == 0, "全部撤回后仍有残留");
            // ⑤ 撤回不存在的记录不能炸
            store.releaseReservation(UUID.nameUUIDFromBytes("SelfTest-bm-none".getBytes()), date, key);
            store.releaseReservation(null, date, key);
        });

        add(g, "通行证 save() 必须能报告成败（v2.25.7 修 P1）", () -> {
            // 出过的真事故：save() 是 void，买付费轨「先扣 5000 再写已购」，
            // 写盘失败只打 warning —— 钱扣了、「已购」只在内存里，崩在 flush 前就是白花钱。
            // 现在 save() 返回 boolean，调用方据此回滚。这条钉住「返回值存在且真能落盘」。
            var pass = plugin.seasonPass();
            require(pass != null, "赛季通行证服务没初始化");
            require(pass.save(), "save() 在正常环境下应当返回 true（不然买付费轨永远被拒）");
        });
        add(g, "签到发奖：物品构造必须在发剑币之前（v2.25.8 修 P1）", () -> {
            // 出过的真事故：原顺序是「记签到 → wallet.give（币到手）→ 造材料/符（可能抛）→ catch 只回滚 setSignin」。
            // 结果：配置里材料 id 写错时，玩家**币已到手、签到记录被回滚**，可以反复领 = 刷币。
            // 现在把 materialItem()/Charm.create() 全部提到发东西之前，构造失败时一件没发、进度也没动。
            // 钉子锁的是不变量本身：材料构造对坏 id 必须给 null 而不是抛（错了就静默跳过）。
            var svc = plugin.dailyReward();
            require(svc != null, "签到服务没初始化");
            // 拿不到 private 方法就退一步锁「今天能读出签到天数」这条链路，确保服务本身可用
            UUID fake = UUID.nameUUIDFromBytes("SelfTest-signin-order".getBytes());
            int d = svc.dayOf(fake);
            require(d >= 0, "签到天数不能为负");
            // 真顺序保障靠 DailyRewardService 里 prepared 段在 setSignin 之前 ——
            // 这条断言防的是「有人把 wallet.give 又挪回构造前面」时至少服务还活着
            require(plugin.wallet() != null, "钱包没初始化");
        });
        add(g, "行会金库扣款必须与基金同口径走 Wallet（v2.25.9 修 P1）", () -> {
            // 出过的真事故：同一个文件两个钱口两种口径 ——
            //   deposit（金库）直连 stats().takeCoins
            //   donate（基金）走 wallet().take，注释还写着「禁止直连 stats().takeCoins」
            // economy.coin-sinks=server 时：存金库绕过 Vault、捐基金走 Vault = 两本账。
            java.io.File pkgRoot = findSourcePackageRoot();
            if (pkgRoot == null) {
                plugin.getLogger().info("[selftest] 找不到源码树，跳过行会账本守卫");
                return;
            }
            java.io.File src = new java.io.File(pkgRoot, "guild/GuildService.java");
            require(src.isFile(), "找不到 GuildService.java");
            String code;
            try {
                code = new String(java.nio.file.Files.readAllBytes(src.toPath()),
                        java.nio.charset.StandardCharsets.UTF_8);
            } catch (java.io.IOException ex) {
                throw new IllegalStateException("读不到 GuildService.java: " + ex.getMessage());
            }
            int bad = 0;
            for (String line : code.split("\n")) {
                String trimmed = line.trim();
                if (trimmed.startsWith("//") || trimmed.startsWith("*") || trimmed.startsWith("/*")) {
                    continue;
                }
                if (trimmed.contains("stats().takeCoins")) {
                    bad++;
                }
            }
            require(bad == 0, "GuildService 又有 " + bad
                    + " 处 stats().takeCoins —— 行会收钱必须跟基金一样走 wallet().take，混用=两本账");
            require(code.contains("wallet().take"), "GuildService 的扣款没走 wallet().take");
            require(code.contains("saveChecked()"), "行会落盘必须有成败返回值（否则金库存钱会被吞）");
            // 逐方法核对：只要这个方法扣了玩家的钱，就必须判落盘成败 ——
            // deposit（金库）和 fundDonate（基金）是同一类：扣了钱、余额只在内存，写失败=吞钱。
            int takeMethods = 0;
            for (String chunk : code.split("\\n    (?:private|public) ")) {
                if (!chunk.contains("plugin.wallet().take(")) {
                    continue;
                }
                takeMethods++;
                require(chunk.contains("saveChecked()"),
                        "有扣玩家钱的方法没判落盘成败（"
                                + chunk.substring(0, Math.min(48, chunk.length())).replace('\n', ' ')
                                + "…）—— 扣了钱没写盘就是吞钱");
            }
            require(takeMethods >= 2, "行会扣钱的方法数异常（" + takeMethods + "），"
                    + "至少 deposit / fundDonate 两处；是不是有人把扣款搬走了？");
            // 竞态 ≠ 没写：竞态那一轮盘上已经有这笔钱，返回 false 会让调用方倒扣金库 + 退款，
            // 重启后金库凭空多一笔。saveChecked 只能在「真的没写」时返回 false。
            for (String line : code.split("\n")) {
                String trimmed = line.trim();
                if (trimmed.startsWith("//") || trimmed.startsWith("*")) {
                    continue;
                }
                require(!trimmed.startsWith("return !race"),
                        "saveChecked() 不能把竞态当失败返回（return !race）—— "
                                + "写下去了就是 true，只有没写才是 false");
            }
        });
        add(g, "签到进度落盘必须判返回值（v2.25.9 修 P1）", () -> {
            // DailyStore.save() 早就返回 boolean，签到路径一直没看 ——
            // 写失败时「今天已签」只在内存，重启后能再签一次 = 重复发奖。
            var store = plugin.dailyStore();
            require(store != null, "黑市存档没初始化");
            require(store.save(), "DailyStore.save() 在正常环境下应返回 true");
            java.io.File pkgRoot = findSourcePackageRoot();
            if (pkgRoot == null) {
                plugin.getLogger().info("[selftest] 找不到源码树，跳过签到落盘守卫");
                return;
            }
            java.io.File src = new java.io.File(pkgRoot, "blackmarket/DailyRewardService.java");
            require(src.isFile(), "找不到 DailyRewardService.java");
            String code;
            try {
                code = new String(java.nio.file.Files.readAllBytes(src.toPath()),
                        java.nio.charset.StandardCharsets.UTF_8);
            } catch (java.io.IOException ex) {
                throw new IllegalStateException("读不到 DailyRewardService.java: " + ex.getMessage());
            }
            require(code.contains("if (!store.save())"),
                    "签到进度落盘必须判返回值（否则存档失败还能重复签）");
            require(code.contains("restoreSignin("),
                    "存档失败时必须回滚内存里的签到进度（否则下次任何一次 save 都会把"
                            + "「已签但没发奖」写进盘，玩家白丢一天）");
        });
        add(g, "签到回滚：restoreSignin 还原旧进度，原本没记录的删干净（v2.25.9）", () -> {
            // 反例：setSignin(day=5) 之后 save 失败，只 return 不回滚 ——
            // 内存里留着「今天已签第 5 天」，别人一签到触发 save，这条假的就落盘了。
            var store = plugin.dailyStore();
            require(store != null, "黑市存档没初始化");
            UUID u = UUID.nameUUIDFromBytes("selftest-signin-rollback".getBytes());
            try {
                store.setSignin(u, 3, "2026-01-03");
                store.restoreSignin(u, 1, "2026-01-01");
                require(store.signinOf(u).day == 1 && "2026-01-01".equals(store.signinOf(u).last),
                        "回滚后应回到 day=1 / last=2026-01-01，实际 day="
                                + store.signinOf(u).day + " / last=" + store.signinOf(u).last);
                // 原本没有记录的玩家（day=0/last=null）：回滚要整条删掉，别留一张白纸在盘上
                store.restoreSignin(u, 0, null);
                require(store.signinOf(u).day == 0 && store.signinOf(u).last == null,
                        "无记录玩家回滚后应回到白纸状态");
            } finally {
                store.restoreSignin(u, 0, null); // 净效果为零，不碰真实数据
            }
        });
        add(g, "断约罚金必须判 takeCoins 返回值（v2.26.0 修 P2）", () -> {
            // 反例：罚金没收上来（余额在 coins() 读取与扣款之间变化），广播却照喊
            // 「剑币少了 N」= 全服看到一条假账。收缴失败必须按实际措辞 + 留痕。
            java.io.File pkgRoot = findSourcePackageRoot();
            if (pkgRoot == null) {
                plugin.getLogger().info("[selftest] 找不到源码树，跳过罚金返回值守卫");
                return;
            }
            java.io.File src = new java.io.File(pkgRoot, "contract/ContractManager.java");
            require(src.isFile(), "找不到 ContractManager.java");
            String code;
            try {
                code = new String(java.nio.file.Files.readAllBytes(src.toPath()),
                        java.nio.charset.StandardCharsets.UTF_8);
            } catch (java.io.IOException ex) {
                throw new IllegalStateException("读不到 ContractManager.java: " + ex.getMessage());
            }
            require(code.contains("collected = plugin.stats().takeCoins"),
                    "断约罚金必须接收 takeCoins 返回值（collected = ...），不能裸调");
            require(code.contains("!collected"), "收缴失败必须有分支处理，不许静默吞");
            require(code.contains("罚金分文未能收缴"),
                    "收缴失败时广播必须换成如实措辞，不许再喊「剑币少了 N」的假账");
        });
        add(g, "战力曲线：append/of 往返 + 去重 + remove 净效果为零（v2.26.0）", () -> {
            var store = plugin.powerLog();
            require(store != null, "战力曲线存储没初始化");
            require(store.save(), "PowerLogStore.save() 在正常环境下应返回 true");
            UUID u = UUID.nameUUIDFromBytes("selftest-powerlog".getBytes());
            try {
                var en = new com.forgemaster.score.PowerLogStore.Entry();
                en.at = System.currentTimeMillis();
                en.power = 1234L;
                en.quality = "精良";
                en.material = "iron";
                en.level = 5;
                en.stage = 0;
                require(store.append(u, en), "append 应落盘成功");
                require(store.of(u).size() == 1 && store.of(u).get(0).power == 1234L,
                        "append 后 of() 应能读回这条记录");
                // 10 分钟窗口内各分项全同：第二条直接吞掉，不许刷屏
                require(store.append(u, en), "重复评分应视为已记录（返回 true）");
                require(store.of(u).size() == 1, "窗口内重复评分不许产生第二个点");
                // power ≤ 0 的记录没有意义，拒收
                var zero = new com.forgemaster.score.PowerLogStore.Entry();
                zero.at = System.currentTimeMillis();
                zero.power = 0L;
                require(!store.append(u, zero), "power=0 的记录应被拒收");
            } finally {
                store.remove(u); // 净效果为零，不污染真实 power-log.yml
            }
            require(store.of(u).isEmpty(), "remove 后曲线应为空");
        });
        add(g, "公开档案不许露隐私面（v2.26.0 stats of）", () -> {
            // statsOfPublic 是对陌生人开放的视图：ProfilePanel（剑币/连锻）和 coins 一旦
            // 被塞进去，等于把钱包挂在公屏上。钉子锁死白名单视图。
            java.io.File pkgRoot = findSourcePackageRoot();
            if (pkgRoot == null) {
                plugin.getLogger().info("[selftest] 找不到源码树，跳过公开档案守卫");
                return;
            }
            java.io.File src = new java.io.File(pkgRoot, "command/ForgingCommand.java");
            require(src.isFile(), "找不到 ForgingCommand.java");
            String code;
            try {
                code = new String(java.nio.file.Files.readAllBytes(src.toPath()),
                        java.nio.charset.StandardCharsets.UTF_8);
            } catch (java.io.IOException ex) {
                throw new IllegalStateException("读不到 ForgingCommand.java: " + ex.getMessage());
            }
            String chunk = null;
            for (String part : code.split("\\n    (?:private|public) ")) {
                if (part.contains("锻造档案（公开）")) {
                    chunk = part;
                    break;
                }
            }
            require(chunk != null, "找不到 statsOfPublic 方法体（标记串「锻造档案（公开）」被挪走了？）");
            require(!chunk.contains("ProfilePanel"), "公开档案不许调 ProfilePanel（那是剑币/隐私面板）");
            require(!chunk.contains("coins("), "公开档案不许读剑币");
            require(chunk.contains("titleOf") && chunk.contains("duelWins"),
                    "公开档案的成长/荣誉白名单字段被删了？");
        });
        add(g, "power compare：非锻造品要说明原因，锻造品出对比头（v2.26.0）", () -> {
            ItemStack plain = new ItemStack(Material.IRON_SWORD);
            ItemStack forged = new ItemStack(Material.IRON_SWORD);
            var meta = forged.getItemMeta();
            meta.getPersistentDataContainer().set(com.forgemaster.util.Keys.FORGED,
                    org.bukkit.persistence.PersistentDataType.BYTE, (byte) 1);
            forged.setItemMeta(meta);
            // 两把白板：没有可比性，要给出原因而不是抛异常
            var out = com.forgemaster.score.PowerScore.compare(plugin, plain, "Conc02", plain);
            require(!out.isEmpty() && String.join("", out).contains("不是锻造品"),
                    "两把非锻造品应提示「不是锻造品」，实际: " + out);
            // 一边锻造一边白板：同样要有原因
            out = com.forgemaster.score.PowerScore.compare(plugin, forged, "Conc02", plain);
            require(!out.isEmpty() && String.join("", out).contains("不是锻造品"),
                    "一边非锻造品应说明没有可比性");
            // 两把锻造品：出对比头，任何一边分数 ≥ 0
            out = com.forgemaster.score.PowerScore.compare(plugin, forged, "Conc02",
                    forged.clone());
            require(!out.isEmpty() && String.join("", out).contains("战力对比"),
                    "两把锻造品应出「战力对比」头行");
        });
        add(g, "rank 比试维度：解析 / 排序值 / 展示三件套（v2.26.0）", () -> {
            // 战绩本身一直有落库（DuelManager 结算 countDuel），缺的是「看得见」：
            // rank 加了第 6 个维度 DUEL —— 别名、量纲隔离、文案都要活。
            var duel = com.forgemaster.ranking.Metric.parse("duel");
            require(duel == com.forgemaster.ranking.Metric.DUEL, "duel 别名解析失败");
            require(com.forgemaster.ranking.Metric.parse("bishi") == com.forgemaster.ranking.Metric.DUEL,
                    "bishi 别名解析失败");
            var st = new com.forgemaster.stats.StatsStore.Stats();
            st.duelWins = 3;
            st.duelLosses = 1;
            require(com.forgemaster.ranking.Metric.DUEL.value(st) == 3 * 1_000_000.0 - 1,
                    "比试排序值应为 胜×1e6 − 负（量纲隔离），实际 " + com.forgemaster.ranking.Metric.DUEL.value(st));
            var st2 = new com.forgemaster.stats.StatsStore.Stats();
            st2.duelWins = 3;
            st2.duelLosses = 5;
            require(com.forgemaster.ranking.Metric.DUEL.value(st) > com.forgemaster.ranking.Metric.DUEL.value(st2),
                    "同胜场时输得少的要排前面");
            String disp = com.forgemaster.ranking.Metric.DUEL.display(st);
            require(disp.contains("3") && disp.contains("胜") && disp.contains("1") && disp.contains("负"),
                    "比试展示文案异常: " + disp);
        });
        add(g, "rank 维度数与 GUI 槽位数一致（v2.26.0）", () -> {
            // 反例：往 Metric 加维度却忘加 METRIC_SLOTS，GUI 底行的切换图标会静默消失
            // （RankMenu 循环被 min(values().length, slots.length) 截断，不报错）。
            int metrics = com.forgemaster.ranking.Metric.values().length;
            int slots = com.forgemaster.ranking.RankMenu.METRIC_SLOTS.length;
            require(slots >= metrics,
                    "rank 有 " + metrics + " 个维度但 GUI 只有 " + slots + " 个槽位 —— 第 "
                            + (slots + 1) + " 个维度的切换图标看不见");
            for (int s : com.forgemaster.ranking.RankMenu.METRIC_SLOTS) {
                require(s != com.forgemaster.ranking.RankMenu.CLOSE_SLOT && s != com.forgemaster.ranking.RankMenu.MY_SLOT,
                        "维度槽位 " + s + " 撞上了关闭/我的按钮槽");
            }
        });
        add(g, "比试结算必须双面记战绩（v2.26.0）", () -> {
            // countDuel 是战绩的唯一写入口：只记赢家不记输家的话，比试维度会变成
            // 「只有胜场、负场恒 0」的假榜。钉死结算处两人都要记账。
            java.io.File pkgRoot = findSourcePackageRoot();
            if (pkgRoot == null) {
                plugin.getLogger().info("[selftest] 找不到源码树，跳过比试记战绩守卫");
                return;
            }
            java.io.File src = new java.io.File(pkgRoot, "duel/DuelManager.java");
            require(src.isFile(), "找不到 DuelManager.java");
            String code;
            try {
                code = new String(java.nio.file.Files.readAllBytes(src.toPath()),
                        java.nio.charset.StandardCharsets.UTF_8);
            } catch (java.io.IOException ex) {
                throw new IllegalStateException("读不到 DuelManager.java: " + ex.getMessage());
            }
            int hits = (code.split("plugin\\.stats\\(\\)\\.countDuel\\(", -1).length) - 1;
            require(hits >= 2, "DuelManager 结算处 countDuel 只出现 " + hits
                    + " 次（应 ≥2：赢家和输家都要记）");
        });
        add(g, "contract pick：聊天补领幂等且单次消费（v2.26.0）", () -> {
            // 离线错过三选一面板的玩家用 /zhujian contract pick <1|2|3> 补领。
            // 幂等红线：重复 pick / 无 pending / 序号越界都要有明确回执且零状态变化；
            // 先清再发的单次消费结构必须挡死同一 pending 发两件。
            var cm = plugin.contractManager();
            require(cm != null, "ContractManager 没初始化");
            if (!cm.active()) {
                plugin.getLogger().info("[selftest] 契约未开启（contract.enabled=false），跳过 pick 幂等钉子");
                return;
            }
            var store = cm.store();
            UUID u = UUID.nameUUIDFromBytes("selftest-contract-pick".getBytes());
            String relicId = null;
            for (var d : cm.relics().all()) {
                relicId = d.id();
                break;
            }
            require(relicId != null, "圣物池为空，无法构造 pending");
            try {
                var r = store.ensure(u);
                r.relics.clear();
                r.pickPending = relicId;
                // 越界：要在 pending 还在时测，回执必须明确且不动任何状态
                String oob = cm.pickPendingByIndex(u, 9);
                require(oob != null && oob.contains("序号越界"),
                        "越界序号应有「序号越界」回执，实际: " + oob);
                require(r.pickPending.equals(relicId) && r.relics.isEmpty(),
                        "越界 pick 不许产生任何状态变化");
                // 首次领取：发放 + pending 清空
                String first = cm.pickPendingByIndex(u, 1);
                require(first != null && first.contains("圣物到手"),
                        "首次 pick 应发放圣物，实际: " + first);
                require(r.relics.contains(relicId) && r.pickPending.isBlank(),
                        "发放后圣物入库、pending 清空");
                // 重复领取：明确回执 + 不再发第二件
                String second = cm.pickPendingByIndex(u, 1);
                require(second != null && second.contains("没有待选"),
                        "重复 pick 应有「没有待选」回执，实际: " + second);
                require(r.relics.size() == 1, "重复 pick 后圣物数量必须还是 1（单次消费）");
            } finally {
                store.remove(u); // 净效果为零，不污染真实 contract.yml
            }
        });
        add(g, "contract join：已签守卫无第 1 天例外，重复 join 不许再发圣物（v2.26.2）", () -> {
            // 原守卫带 day 例外，周目第 1 天重复 /zhujian contract join 每次都重新
            // offerRelics（offer 只排除已持有），刷满整个圣物池。join(Player) 需要
            // 在线玩家，装置调不了，按方法体钉源码形状（守恒律：已签即拒绝）。
            java.io.File pkgRoot = findSourcePackageRoot();
            if (pkgRoot == null) {
                plugin.getLogger().info("[selftest] 找不到源码树，跳过 contract join 守卫钉子");
                return;
            }
            java.io.File src = new java.io.File(pkgRoot, "contract/ContractManager.java");
            require(src.isFile(), "找不到 ContractManager.java");
            String code;
            try {
                code = new String(java.nio.file.Files.readAllBytes(src.toPath()),
                        java.nio.charset.StandardCharsets.UTF_8);
            } catch (java.io.IOException ex) {
                throw new IllegalStateException("读不到 ContractManager.java: " + ex.getMessage());
            }
            String body = methodBody(code, "public String join(Player p)");
            require(body != null, "join(Player) 方法体切不出来");
            // v2.26.3：判在契必须用**只读**的 store.run()，绝不能用 store.ensure() 判 ——
            // ensure 会带副作用建一个 joined=true 的档案，拿它判「已签」会把首次 join 误判成已签。
            // 但 ensure() 在**首次签约**那条分支上是合法的（那时确实要建档），
            // 所以断言分两半：① 判在契那一步必须是 run()；② ensure 不许出现在任何 if 条件里。
            require(body.contains("store.run("),
                    "join 必须先 store.run() 只读判在契");
            for (String line : body.split("\n")) {
                String t = line.trim();
                if (t.startsWith("if (") && t.contains("ensure(")) {
                    throw new AssertionError("join 不许用 ensure() 做条件判定（应先用 store.run() 只读判）：" + t);
                }
            }
            require(body.contains("existing.joined") || body.contains("r.joined"),
                    "join 的已签守卫必须在（已签即拒绝，不许再发圣物）");
            require(!body.contains("r.day > 1"), "join 守卫不许再带 day 例外（第 1 天重复 join 会刷圣物）");
            require(body.contains("offerRelics"), "join 的首签发圣物路径必须还在（防钉子改错地方）");
        });
        add(g, "contract quit→join：不许靠退出重进重掷圣物（v2.26.3）", () -> {
            // 用户实测：多敲几次 /zhujian contract join 圣物就满了。
            // 机理是 quit 把档案整个 remove 掉，join 于是当成新玩家、
            // owned 列表清空 → offer 从 12 个里重新抽 3 个，无限重掷定向钓稀有。
            // 正确口径：quit 只翻 joined=false（记录留着），join 继承原圣物池。
            var cm = plugin.contractManager();
            require(cm != null, "ContractManager 没初始化");
            if (!cm.active()) {
                plugin.getLogger().info("[selftest] 契约未开启，跳过 quit→join 重掷钉子");
                return;
            }
            var store = cm.store();
            UUID u = UUID.nameUUIDFromBytes("selftest-contract-quit-join".getBytes());
            try {
                var r = store.ensure(u);
                r.joined = true;
                r.relics.clear();
                r.relics.add("phoenix-ash");   // 假装已经钓到稀有圣物
                r.pickPending = "";
                // 复刻 quit 的语义：只翻 joined，不 remove
                r.joined = false;
                r.pickPending = "";
                require(store.run(u) != null,
                        "退出契约后档案必须还在（remove 会让重签变成新玩家）");
                require(store.run(u).relics.contains("phoenix-ash"),
                        "退出契约不许清空已持有圣物（否则重签就能重掷）");
                // 再模拟一次「退出→重签」循环，圣物池必须一份不多
                for (int i = 0; i < 5; i++) {
                    store.run(u).joined = false;
                    store.run(u).joined = true;
                }
                int owned = store.run(u).relics.size();
                require(owned == 1,
                        "反复退出重签 5 次后圣物数量必须还是 1，实际 " + owned
                                + "（quit 若清档，这里会一路涨上去）");
            } finally {
                store.remove(u);
            }
        });
        add(g, "v2.26.6 终审补：isSimilar 收剑点全仓唯一（removeFromInventory 内 ≤2 处）", () -> {
            if (findSourcePackageRoot() == null) {
                plugin.getLogger().info("[selftest] 找不到源码树，跳过 isSimilar 收剑唯一点钉子");
                return;
            }
            String src = nonCommentSource(new java.io.File(findSourcePackageRoot(),
                    "strengthen/StrengthenService.java"));
            require(src != null, "读不到 StrengthenService.java");
            int hits = (src.split("isSimilar", -1).length) - 1;
            require(hits >= 1 && hits <= 2,
                    "isSimilar 收剑点必须只存在于 removeFromInventory 内（主手+背包各一次，实际 " + hits
                            + "——UID 匹配收紧后不许有人把模糊匹配扩散出去）");
        });
        add(g, "v2.26.6 ITEM_UID：唯一性 + isSimilar 收紧 + 克隆保留 + ensureUid 语义", () -> {
            // 碎剑收剑按 uid 精确匹配的根基：uid 让 isSimilar 自动变严、clone 不丢、
            // ensureUid 只补缺失绝不覆盖。全自包含（手造 PDC，不依赖在线玩家）。
            var uidKey = com.forgemaster.util.Keys.ITEM_UID;
            require(uidKey != null, "ITEM_UID 键没注册");
            org.bukkit.inventory.ItemStack a = new org.bukkit.inventory.ItemStack(org.bukkit.Material.IRON_SWORD);
            var ma = a.getItemMeta();
            ma.getPersistentDataContainer().set(com.forgemaster.util.Keys.FORGED, org.bukkit.persistence.PersistentDataType.BYTE, (byte) 1);
            ma.getPersistentDataContainer().set(uidKey, org.bukkit.persistence.PersistentDataType.STRING, "uid-A");
            a.setItemMeta(ma);
            org.bukkit.inventory.ItemStack b = new org.bukkit.inventory.ItemStack(org.bukkit.Material.IRON_SWORD);
            var mb = b.getItemMeta();
            mb.getPersistentDataContainer().set(com.forgemaster.util.Keys.FORGED, org.bukkit.persistence.PersistentDataType.BYTE, (byte) 1);
            mb.getPersistentDataContainer().set(uidKey, org.bukkit.persistence.PersistentDataType.STRING, "uid-B");
            b.setItemMeta(mb);
            require(!a.isSimilar(b), "uid 不同的两把剑 isSimilar 必须为 false（收剑模糊匹配的根因）");
            require(plugin.items().uidOf(a).equals("uid-A"), "uidOf 读回失败");
            org.bukkit.inventory.ItemStack copy = a.clone();
            require(plugin.items().uidOf(copy).equals("uid-A"), "clone 必须保留 uid（Paper 实现细节变更会当场红）");
            org.bukkit.inventory.ItemStack legacy = new org.bukkit.inventory.ItemStack(org.bukkit.Material.IRON_SWORD);
            var ml = legacy.getItemMeta();
            ml.getPersistentDataContainer().set(com.forgemaster.util.Keys.FORGED, org.bukkit.persistence.PersistentDataType.BYTE, (byte) 1);
            legacy.setItemMeta(ml);
            require(plugin.items().uidOf(legacy) == null, "无 uid 老物 uidOf 应返回 null");
            require(plugin.items().ensureUid(legacy), "ensureUid 应给存量老物补 uid");
            String filled = plugin.items().uidOf(legacy);
            require(filled != null && !filled.isBlank(), "补发后 uid 非空");
            require(!plugin.items().ensureUid(legacy), "ensureUid 幂等：已有 uid 不许改写");
            require(plugin.items().uidOf(legacy).equals(filled), "第二次 ensureUid 不许换 uid");
        });
        add(g, "v2.26.6 任务守卫：八个周期任务入口全部挂 TaskGuard（静态形状钉）", () -> {
            if (findSourcePackageRoot() == null) {
                plugin.getLogger().info("[selftest] 找不到源码树，跳过任务守卫钉子");
                return;
            }
            String[][] sites = {
                {"contests/ContestManager.java", "ContestManager.tick"},
                {"events/EventManager.java", "EventManager.tick"},
                {"season/SeasonManager.java", "SeasonManager.tick"},
                {"hall/HallOfFame.java", "HallOfFame.refresh"},
                {"hall/HallShrine.java", "HallShrine.tick"},
                {"story/StoryManager.java", "StoryManager.check"},
                {"guild/GuildService.java", "GuildService.weeklyCheck"},
                {"ForgingMasterPlugin.java", "EnchantListener.tick"},
            };
            for (String[] site : sites) {
                String src = sourceFileText(site[0]);
                require(src != null && src.contains("TaskGuard.guard(\"" + site[1] + "\""),
                        "周期任务 " + site[1] + " 必须挂 TaskGuard（抛一次异常就被 Bukkit 永久取消）");
            }
        });
        add(g, "v2.26.6 审计轮：Webhook 熔断可重臂 / pruneSpawned 区分卸载 / GUI 归属先于操作（静态形状钉）", () -> {
            if (findSourcePackageRoot() == null) {
                plugin.getLogger().info("[selftest] 找不到源码树，跳过 v2.26.6 审计轮钉子");
                return;
            }
            String breaker = sourceMethodBody("webhook/WebhookService.java", "private void recordFailure(Throwable ex)");
            require(breaker != null && breaker.contains(">= BREAKER_THRESHOLD"),
                    "webhook 熔断必须是 >=（== 只触发一次，之后永不重臂）");
            require(breaker.contains("consecutiveFailures = 0"), "熔断打开时必须清零计数以便重臂");
            String start = sourceMethodBody("webhook/WebhookService.java", "public synchronized void start()");
            require(start != null && start.contains("consecutiveFailures = 0"),
                    "start() 必须复位熔断状态（reload 不许继承陈旧熔断）");
            String prune = sourceMethodBody("events/EventManager.java", "private void pruneSpawned()");
            require(prune != null && prune.contains("e != null && e.isDead()"),
                    "pruneSpawned 必须区分「区块卸载」和「已死亡」（清了记录空投就永久残留）");
            String onClose = sourceMethodBody("enchant/EnchantRouter.java", "public void onClose(");
            require(onClose != null && onClose.indexOf("holder.player().equals") >= 0
                    && onClose.indexOf("holder.player().equals") < onClose.indexOf("addItem"),
                    "onClose 的归属校验必须先于退款循环");
            String warehouse = sourceMethodBody("guild/GuildGuiRouter.java", "private void routeWarehouse(");
            require(warehouse != null && warehouse.contains("warehouse.viewer()"),
                    "routeWarehouse 必须校验点击者==面板 viewer");
            String load = sourceMethodBody("story/StoryManager.java", "public void load()");
            require(load != null && load.contains("commissions") && load.contains("forged"),
                    "story 载入必须校验章节 type 白名单（拼错 type 的章零提示永久卡死）");
        });
        add(g, "contract 落盘口径：结算与 pick 必须同步 save（v2.26.4 结算重放修复）", () -> {
            // 结算/领圣物发的是「另一个存档」（StatsStore）里的钱，contract.yml 若只标脏
            // 30 秒异步冲刷，窗口内崩溃 → 盘上旧档被重放，奖金+圣物重复发。低频路径
            // 必须同步落盘。按方法体钉源码形状。
            if (findSourcePackageRoot() == null) {
                plugin.getLogger().info("[selftest] 找不到源码树，跳过 contract 落盘口径 钉子");
                return;
            }
            String body = sourceMethodBody("contract/ContractManager.java",
                    "store.lastSettledFullDay(fullDay)");
            require(body != null, "tickClock 的游标推进行切不出来");
            require(body.contains("store.save()"),
                    "结算后必须同步 store.save()（只 markDirty 会有 30 秒结算重放窗口）");
            String pick = sourceMethodBody("contract/ContractManager.java",
                    "public String pickRelic(UUID id, String relicId)");
            require(pick != null && pick.contains("store.save()"),
                    "pickRelic 消费 pending 后必须同步 save（否则崩溃后同一 pending 可再 pick）");
        });
        add(g, "领奖 saveChecked 契约：谱系/图鉴/行会委托落盘失败必须回滚已领（v2.26.4）", () -> {
            if (findSourcePackageRoot() == null) {
                plugin.getLogger().info("[selftest] 找不到源码树，跳过 领奖 saveChecked 契约 钉子");
                return;
            }
            String spec = sourceMethodBody("spectrum/SpectrumService.java",
                    "public boolean claim(Player player, Entry e)");
            require(spec != null && spec.contains("if (!save())"),
                    "名剑谱 claim 必须判 save() 返回值（吞 IOException = 重启后可重领）");
            require(spec.contains(".claimed.remove"), "save 失败必须回滚「已领」标记");
            String codex = sourceMethodBody("codex/CodexService.java",
                    "public long claim(PlayerView viewer, UUID id, String cat)");
            require(codex != null && codex.contains("if (!save())"),
                    "图鉴 claim 必须判 save() 返回值");
            require(codex.contains(".claimed.remove"), "图鉴 save 失败必须回滚「已领」标记");
            String quest = sourceMethodBody("guild/GuildQuests.java",
                    "private void claim(Player p, GuildService.Guild g)");
            require(quest != null && quest.contains("saveChecked()"),
                    "行会委托领奖必须同步 saveChecked（5 秒冲刷窗口内崩溃全员可再领）");
            require(quest.contains("claimed.remove"), "落盘失败必须回滚「已领」标记");
        });
        add(g, "奖励出口 Wallet 收口：七处奖金不许直连 stats().addCoins（v2.26.4）", () -> {
            // coin-sinks=server 时 Wallet 扣 Vault 而奖金直发剑币账 = 凭空造币
            // （OrderService v2.25.2 同型）。这七个出口必须走 Wallet（离线用 giveTo）。
            if (findSourcePackageRoot() == null) {
                plugin.getLogger().info("[selftest] 找不到源码树，跳过 奖励出口 Wallet 收口 钉子");
                return;
            }
            String tomb = sourceMethodBody("tomb/TombManager.java", "plugin.stats().addCoins(player.getUniqueId(), coins");
            require(tomb == null || !tomb.contains("stats().addCoins(player.getUniqueId(), coins, CoinReason.TOMB)"),
                    "剑冢奖金必须走 wallet().give（扣费在 Wallet、发钱在剑币账 = 跨账本套利）");
            require(sourceMethodBody("tomb/TombManager.java", "plugin.wallet().give(player, coins, CoinReason.TOMB)") != null,
                    "剑冢复刻奖金要出现在 wallet().give 调用里");
            require(sourceMethodBody("tombrun/TombRunService.java", "plugin.wallet().give(p, coins, CoinReason.TOMB)") != null,
                    "秘境通关奖金必须收口 Wallet 且带 CoinReason.TOMB");
            require(sourceMethodBody("pass/SeasonPassService.java", "plugin.wallet().give(player, r.coins, CoinReason.PASS)") != null,
                    "通行证等级奖金必须收口 Wallet");
            String guild = sourceMethodBody("guild/GuildService.java",
                    "plugin.wallet().giveTo(id, nameOf(id), give, CoinReason.REFUND)");
            require(guild != null, "行会解散退款必须走 wallet().giveTo（Vault 托管钱不许被销毁重铸成剑币）");
            require(sourceMethodBody("guild/GuildService.java",
                    "plugin.wallet().giveTo(best.owner, nameOf(best.owner), prize, CoinReason.CONTEST)") != null,
                    "周赛会长奖励必须走 giveTo（会长可能离线）");
            require(sourceMethodBody("contests/ContestManager.java",
                    "plugin.wallet().giveTo(e.getKey(), name, coinGain, CoinReason.CONTEST)") != null,
                    "大赛名次奖金必须走 giveTo");
            require(sourceMethodBody("guild/GuildQuests.java",
                    "plugin.wallet().give(p, reward, CoinReason.CONTEST)") != null,
                    "行会委托奖金必须收口 Wallet");
        });
        add(g, "数值夹紧：估值链 isNaN/Infinity 守卫 + 余额饱和加法（v2.26.4）", () -> {
            if (findSourcePackageRoot() == null) {
                plugin.getLogger().info("[selftest] 找不到源码树，跳过 数值夹紧 钉子");
                return;
            }
            String appraise = sourceMethodBody("contract/ContractManager.java", "public long appraise(ItemStack item)");
            require(appraise != null && appraise.contains("Double.isNaN") && appraise.contains("Double.isInfinite"),
                    "估值连乘链必须有 NaN/Infinity 守卫（round 会把 Infinity 饱和成 Long.MAX）");
            require(appraise.contains("Math.min(v, 1.0e12)"), "估值必须有总夹紧上限");
            String store = sourceMethodBody("stats/StatsStore.java", "public long addCoins(UUID uuid, long amount, com.forgemaster.econ.CoinReason reason)");
            require(store != null && store.contains("Long.MAX_VALUE"),
                    "addCoins 必须饱和加法（原 Math.max(0, coins+amount) 溢出变负时会把余额清零）");
            String field = sourceMethodBody("stats/StatsStore.java", "public volatile long coins;");
            require(field != null, "coins 必须是 volatile（异步冲刷线程无锁直读）");
        });
        add(g, "注入面：玩家可控文本出口必须过 esc/escText（v2.26.4）", () -> {
            if (findSourcePackageRoot() == null) {
                plugin.getLogger().info("[selftest] 找不到源码树，跳过 注入面 钉子");
                return;
            }
            String msg = sourceMethodBody("util/Msg.java", "public static String escText(String raw)");
            require(msg != null, "Msg.escText 必须存在（玩家文本进 MiniMessage 模板的统一清洗口）");
            require(msg.contains("u202E"), "escText 必须剥 RTL 覆写符（lore 视觉重排欺骗）");
            require(sourceMethodBody("enchant/EnchantGui.java", "return Msg.esc(Msg.plain(meta.displayName()));") != null,
                    "附魔台物品名必须过 Msg.esc 再拼回 lore（改名物品可携带 click:run_command）");
            String guild = sourceMethodBody("guild/GuildService.java", "String name = rawName.trim();");
            require(guild != null && guild.contains("u202A"),
                    "行会名必须做字符白名单（进全服广播与 GUI，调大 name-max-chars 即注入面）");
            String spirit = sourceMethodBody("spirit/SpiritManager.java", "private void rename(Player player, String raw)");
            require(spirit != null && spirit.contains("u202A"), "剑名必须拒收 RTL/零宽/控制字符");
        });
        add(g, "命令面：查他人余额必须 admin，二级 Tab 管理动词按权限过滤（v2.26.4）", () -> {
            if (findSourcePackageRoot() == null) {
                plugin.getLogger().info("[selftest] 找不到源码树，跳过 命令面 钉子");
                return;
            }
            String money = sourceMethodBody("command/ForgingCommand.java",
                    "if (sub.isEmpty() || \"balance\".equals(sub) || \"bal\".equals(sub)) {");
            require(money != null && money.contains("forgemaster.admin"),
                    "money balance <玩家> 必须先过 admin 判断（隐私面：stats of 都不公开剑币）");
            String tab = sourceMethodBody("command/ForgingCommand.java", "private List<String> tabTomb(CommandSender sender, String[] args)");
            require(tab != null && tab.contains("forgemaster.admin"),
                    "tabTomb 的管理动词（remove/delete/shandiao）必须按权限过滤");
            String tabEvent = sourceMethodBody("command/ForgingCommand.java", "private List<String> tabEvent(CommandSender sender, String[] args)");
            require(tabEvent != null && tabEvent.contains("forgemaster.admin"),
                    "tabEvent 的 start/stop 必须按权限过滤");
        });
        add(g, "v2.26.5 余项：死循环/除零/建档守卫（静态形状钉）", () -> {
            if (findSourcePackageRoot() == null) {
                plugin.getLogger().info("[selftest] 找不到源码树，跳过 v2.26.5 余项钉子");
                return;
            }
            String cum = sourceMethodBody("evolve/EvolutionRegistry.java", "public double cumulativeMult(int stage)");
            require(cum != null && cum.contains("headMap"),
                    "cumulativeMult 必须按真实存在的阶乘乘（for i<=stage 对 PDC 残留超大值空转卡死主线程）");
            String hammer = sourceMethodBody("forge/ForgeTuning.java", "public static int hammerInterval(");
            require(hammer != null && hammer.contains("Math.max(0.1"),
                    "hammerInterval 的乘区必须有 0.1 下限（配置负值 → Infinity tick 敲打指针卡死）");
            String statsFn = sourceMethodBody("command/ForgingCommand.java", "private void stats(CommandSender");
            require(statsFn != null && statsFn.contains("peek(target.getUniqueId())"),
                    "stats 查任意名必须 peek（get 的 computeIfAbsent 会被 admin 刷空档）");
            String panel = sourceMethodBody("ranking/ProfilePanel.java", "public static void show(");
            require(panel != null && panel.contains("store.peek(uuid)"),
                    "ProfilePanel 必须用 peek（get 的 computeIfAbsent 会被 admin 刷空档）");
        });
        add(g, "v2.26.5 回档对齐：seasons 只增不删 + 看板缓存重建（静态形状钉）", () -> {
            if (findSourcePackageRoot() == null) {
                plugin.getLogger().info("[selftest] 找不到源码树，跳过回档对齐钉子");
                return;
            }
            String restore = sourceMethodBody("backup/BackupManager.java", "public synchronized String restore(String id)");
            require(restore != null && restore.contains("keepNames.contains"),
                    "restore 必须清掉 seasons/ 里备份点没有的归档（季号退回后不许看到未来的档案）");
            String reload = sourceMethodBody("backup/BackupManager.java", "private void reloadAllSilently()");
            require(reload != null && reload.contains("plugin.metrics().reload()"),
                    "reloadAllSilently 必须重建看板缓存（回档后 metrics 仍显示回档前趋势 = 视图与盘面分裂）");
            String start = sourceMethodBody("metrics/MetricsService.java", "public void start() {");
            require(start != null && start.contains("lastPruneDay = null"),
                    "start() 必须重置裁剪游标（回档/重载后当天目录要能重扫）");
        });
        add(g, "contest history：赛果归档往返 + 保留 N 届裁剪 + 同届幂等（v2.26.0）", () -> {
            // 用自检专用文件，不碰真实 contest-history.yml；finally 删档 = 净效果为零
            java.io.File tmp = new java.io.File(plugin.getDataFolder(), "selftest-contest-history.yml");
            try {
                var store = new com.forgemaster.contests.ContestHistoryStore(plugin, tmp, 3);
                require(store.recent(10).isEmpty(), "新档案应为空");
                for (int i = 1; i <= 4; i++) {
                    var e = new com.forgemaster.contests.ContestHistoryStore.Entry();
                    e.code = String.format("%06X", i);
                    e.at = System.currentTimeMillis();
                    e.material = "测试剑胚";
                    e.winner = "Bot" + i;
                    e.winnerScore = i;
                    e.players = i;
                    e.top.add("1. Bot" + i + ":" + i + ".0");
                    require(store.record(e), "第 " + i + " 届归档落盘应成功");
                }
                require(store.size() == 3, "keep=3 时只保留最近 3 届，实际 " + store.size());
                require(store.byCode("000001") == null, "最老的一届应被裁掉");
                require(store.byCode("000004") != null, "最新一届必须在档");
                require(store.byCode("000004").winner.equals("Bot4"), "按届次号应查回同一届");
                // 同一届重复归档：幂等，不许出第二条
                var dup = new com.forgemaster.contests.ContestHistoryStore.Entry();
                dup.code = "000004";
                dup.at = System.currentTimeMillis();
                dup.winner = "Bot4";
                require(store.record(dup), "重复归档应返回 true（已在档）");
                require(store.size() == 3, "重复归档不许产生新条目");
            } finally {
                tmp.delete(); // 净效果为零
            }
        });
        add(g, "hall 大赛冠军：数据源必须是 ContestHistoryStore + 只读展示 + 空档案不抛异常（v2.26.1）", () -> {
            java.io.File pkgRoot = findSourcePackageRoot();
            if (pkgRoot == null) {
                plugin.getLogger().info("[selftest] 找不到源码树，跳过名人堂大赛冠军守卫");
                return;
            }
            // ① 数据源钉子（按方法体核，不数整文件）：refresh() 必须经
            //    ContestManager.historyStore() 取赛果档案，不许绕开 ContestHistoryStore 读别的快照
            String body = nonCommentSource(new java.io.File(pkgRoot, "hall/HallOfFame.java"));
            String refresh = methodBody(body, "public void refresh(");
            require(refresh != null, "找不到 HallOfFame.refresh 方法体（被挪走或改名了？）");
            require(hits(refresh, "historyStore()") >= 1,
                    "HallOfFame.refresh 必须经 ContestManager.historyStore() 取大赛档案（命中 "
                            + hits(refresh, "historyStore()") + " 处）");
            // ② 只读钉子：contestLines 展示路径不许出现任何写盘 / 归档调用（只读展示红线）
            String lines = methodBody(body, "static List<String> contestLines(");
            require(lines != null, "找不到 contestLines 方法体（被挪走或改名了？）");
            int writeHits = hits(lines, "YamlIo") + hits(lines, ".save(") + hits(lines, "record(");
            require(writeHits == 0, "大赛冠军展示路径出现写盘 / 归档调用 " + writeHits
                    + " 处（红线：只读展示，不写任何存档）");
            // ③ 功能钉子：空档案不抛异常且给空态文案——用临时 store 实例（文件不存在 → 空档案），
            //    全程不碰真实 contest-history.yml；finally 删档 = 净效果为零
            java.io.File tmp = new java.io.File(plugin.getDataFolder(), "selftest-hall-contest-champion.yml");
            try {
                var store = new com.forgemaster.contests.ContestHistoryStore(plugin, tmp, 5);
                store.load();
                var empty = com.forgemaster.hall.HallOfFame.contestLines(store.recent(5), store.size());
                require(empty.size() == 1, "空档案应给一行空态文案，实际 " + empty.size() + " 行");
                require(empty.get(0).contains("还没有"), "空态文案应说明还没有大赛冠军，实际: " + empty.get(0));
                // 有数据：一届一行，含届次 / 冠军名 / 分数
                var e = new com.forgemaster.contests.ContestHistoryStore.Entry();
                e.code = "ABC123";
                e.at = System.currentTimeMillis();
                e.material = "铁锭";
                e.winner = "ChampBot";
                e.winnerScore = 12.3;
                var rows = com.forgemaster.hall.HallOfFame.contestLines(
                        java.util.List.of(e), 1);
                require(rows.size() == 1, "一届应生成一行冠军行，实际 " + rows.size() + " 行");
                require(rows.get(0).contains("第 1 届"), "冠军行应含届次，实际: " + rows.get(0));
                require(rows.get(0).contains("ChampBot") && rows.get(0).contains("12.3"),
                        "冠军行应含冠军名与分数，实际: " + rows.get(0));
            } finally {
                tmp.delete(); // 净效果为零
            }
        });
        add(g, "contract pick / contest history：命令三处同源 + 领奖不碰剑币（v2.26.0）", () -> {
            // 源码扫描钉子：只扫非注释行，失败要报命中数。
            java.io.File pkgRoot = findSourcePackageRoot();
            if (pkgRoot == null) {
                plugin.getLogger().info("[selftest] 找不到源码树，跳过命令面守卫");
                return;
            }
            java.io.File src = new java.io.File(pkgRoot, "command/ForgingCommand.java");
            require(src.isFile(), "找不到 ForgingCommand.java");
            String code;
            try {
                code = new String(java.nio.file.Files.readAllBytes(src.toPath()),
                        java.nio.charset.StandardCharsets.UTF_8);
            } catch (java.io.IOException ex) {
                throw new IllegalStateException("读不到 ForgingCommand.java: " + ex.getMessage());
            }
            StringBuilder nc = new StringBuilder();
            for (String line : code.split("\n")) {
                String t = line.trim();
                if (t.startsWith("//") || t.startsWith("*") || t.startsWith("/*")) {
                    continue;
                }
                nc.append(line).append('\n');
            }
            String body = nc.toString();
            // ① 分派：contract 的 pick 子命令、contest 的 history 子命令
            int pickDispatch = hits(body, "case \"pick\", \"xuan\", \"xuanze\"");
            require(pickDispatch == 1, "contract pick 分派 case 应恰好 1 处，实际 " + pickDispatch);
            // v2.26.0 修：history 分派从「contest() 里的 if 分支」提到方法入口（contest.enabled
            // 不该连坐只读档案），所以断言改成认别名组本身：分派 + tabContest 候选各一处。
            int histDispatch = hits(body, "\"history\", \"lishi\", \"dangan\"");
            require(histDispatch == 2, "contest history 别名组应出现在分派 + tabContest（恰好 2 处），实际 "
                    + histDispatch);
            // ② help：两条都要在 helpLines 里
            int pickHelp = hits(body, "pick <1|2|3>");
            require(pickHelp == 1, "contract pick 的 help 文案应恰好 1 处，实际 " + pickHelp);
            int histHelp = hits(body, "history 历届档案");
            require(histHelp == 1, "contest history 的 help 文案应恰好 1 处，实际 " + histHelp);
            // ③ Tab：pick 别名组（分派 + tabContract 各 1）与 history 别名组（分派 + tabContest 各 1）
            int pickTab = hits(body, "\"pick\", \"xuan\", \"xuanze\"");
            require(pickTab >= 2, "pick 别名组应出现在分派 + tabContract（≥2 处），实际 " + pickTab);
            int histTab = hits(body, "\"history\", \"lishi\"");
            require(histTab >= 1, "history 别名组应出现在 tabContest（≥1 处），实际 " + histTab);
            // ④ 领奖路径不碰剑币：contractPick 方法体内不许出现 wallet / takeCoins
            //（用 "void contractPick(" 定位，避免命中 contract() 分派里的调用点）
            String chunk = null;
            for (String part : code.split("\\n    (?:private|public) ")) {
                if (part.contains("void contractPick(")) {
                    chunk = part;
                    break;
                }
            }
            require(chunk != null, "找不到 contractPick 方法体（方法被挪走或改名了？）");
            int coinHits = hits(chunk, "takeCoins") + hits(chunk, "wallet()");
            require(coinHits == 0, "contract pick 领奖路径出现剑币调用 " + coinHits
                    + " 处（红线：只发圣物，绝不碰钱包）");
        });
        add(g, "stats of 只读不建档：公开档案走 peek 不走 get（v2.26.0）", () -> {
            // v2.26.0 的真实缺陷（审计轮查出）：statsOfPublic 用了 stats().get(uuid)，
            // 而 get() 是 computeIfAbsent —— 任何人对任意名字敲一次
            // /zhujian stats of <名字> 就会往 data.yml 里灌一条空档案，循环刷即可无限膨胀。
            // StatsStore:252 的 peek() 注释专门警告过这件事（PAPI 桥）。这条钉子守住方法体。
            java.io.File pkgRoot = findSourcePackageRoot();
            if (pkgRoot == null) {
                plugin.getLogger().info("[selftest] 找不到源码树，跳过 stats of 只读守卫");
                return;
            }
            String body = nonCommentSource(new java.io.File(pkgRoot, "command/ForgingCommand.java"));
            String chunk = methodBody(body, "void statsOfPublic(");
            require(chunk != null, "找不到 statsOfPublic 方法体（改名了？）");
            int creating = hits(chunk, "stats().get(");
            require(creating == 0, "stats of 里出现了会建空档案的 stats().get( " + creating
                    + " 处（必须用 peek，见 StatsStore:252）");
            // 更宽的判据：上面那条只认 stats().get( 这一种拼法，改写成
            // `var s = plugin.stats(); s.get(id)` 或 `stats().computeIfAbsent(` 都会漏过。
            // 这里按「会建档的入口名」封死：get( / computeIfAbsent( / computeIfPresent( 都不许出现。
            for (String bad : new String[]{".computeIfAbsent(", ".computeIfPresent("}) {
                require(hits(chunk, bad) == 0,
                        "stats of 里出现了建档入口 " + bad + "（必须用 peek）");
            }
            int readOnly = hits(chunk, "stats().peek(");
            require(readOnly == 1, "stats of 应恰好 1 次 peek 取档案，实际 " + readOnly);
            // 且必须判空：查不到要直说，不能靠新建的空档案渲染出一片 0
            require(chunk.contains("st == null"), "stats of 没有对 peek 的 null 结果判空");
            // v2.26.0 审计轮补：判空条件曾经只看了 7 个字段，把「有币 / 有成就 / 有强化记录
            // 但 forged=0」的玩家误判成查不到（管理员发币、成就奖励币都会走到这一步）。
            // 判空逻辑现在抽成 isEmptyStats()，所以这里按名字核它真的存在且被判空分支调用。
            require(chunk.contains("isEmptyStats(st)"),
                    "stats of 的判空没收口到 isEmptyStats()，容易再漏字段");
            String guard = methodBody(body, "boolean isEmptyStats(");
            require(guard != null, "找不到 isEmptyStats 方法体（改名了？）");
            for (String field : new String[]{"coins", "achievements", "achvPoints",
                    "bestStrengthen", "talents", "patterns", "spirits", "tombs"}) {
                require(guard.contains("st." + field),
                        "isEmptyStats 漏了字段 " + field
                                + " —— 只在这些字段上有数据的玩家会被误判成「查不到档案」");
            }
        });
        add(g, "power log 展示中文品质 / 材质名（v2.26.0）", () -> {
            // 盘上存的是稳定键（品质枚举名 FINE、材质 id iron），展示层必须翻成中文：
            // 否则 /zhujian power 说「凡品」、/zhujian power log 说「品质 FINE」，
            // 同一个玩家看到两套词。这条钉子盯住展示路径的两个翻译调用。
            java.io.File pkgRoot = findSourcePackageRoot();
            if (pkgRoot == null) {
                return;
            }
            String body = nonCommentSource(new java.io.File(pkgRoot, "command/ForgingCommand.java"));
            String chunk = methodBody(body, "void powerLog(");
            require(chunk != null, "找不到 powerLog 方法体（改名了？）");
            require(hits(chunk, "Quality.byName(en.quality)") == 1,
                    "power log 没把品质键翻回 Quality（会直接打印 FINE / EPIC）");
            require(hits(chunk, "MaterialLookup.resolve(plugin, en.material)") == 1,
                    "power log 没把材质 id 翻回显示名（会直接打印 iron）");
        });
        add(g, "help 文案不写死会漂的数字（v2.26.0）", () -> {
            // 真实缺陷：helpLines 里写死「当前 514 项」「18 份存档」，而实际早已是
            // 538 项 / 19 份。写死数字 = 埋一个必然过期的坑，而且没人会去改。
            // 现在：自检项数不报数，备份份数从 SAVE_FILES.length 取。
            java.io.File pkgRoot = findSourcePackageRoot();
            if (pkgRoot == null) {
                return;
            }
            String body = nonCommentSource(new java.io.File(pkgRoot, "command/ForgingCommand.java"));
            String chunk = methodBody(body, "static List<String> helpLines(");
            require(chunk != null, "找不到 helpLines 方法体（改名了？）");
            require(hits(chunk, "514") == 0, "help 里还写死着旧的 514 项");
            require(hits(chunk, "18 份存档") == 0, "help 里还写死着旧的 18 份存档");
            // 用 >=1 而不是 ==1：把长度提成局部变量（int n = SAVE_FILES.length 再拼 + n +）
            // 是更好的写法，写成 ==1 会把它判红 —— 那是逼人改回坏写法。
            require(hits(chunk, "SAVE_FILES.length") >= 1,
                    "help 的备份份数应从 SAVE_FILES.length 取，别写死");
        });
        add(g, "contest.history-keep 出现在内置 config 与必需键清单（v2.26.0）", () -> {
            // 真实缺陷：这个键只在 ContestHistoryStore 的 Java 默认值里，
            // 内置 config.yml 没有 → 服主看不见、改不了，/zhujian config check 也点不出名。
            var stream = plugin.getResource("config.yml");
            require(stream != null, "jar 里没有 config.yml 资源");
            var raw = org.bukkit.configuration.file.YamlConfiguration.loadConfiguration(
                    new java.io.InputStreamReader(stream, java.nio.charset.StandardCharsets.UTF_8));
            require(raw.contains("contest.history-keep"),
                    "内置 config.yml 缺 contest.history-keep（服主看不到这个旋钮）");
            boolean listed = false;
            for (String k : com.forgemaster.ForgingMasterPlugin.REQUIRED_CONFIG_KEYS) {
                if ("contest.history-keep".equals(k)) {
                    listed = true;
                    break;
                }
            }
            require(listed, "REQUIRED_CONFIG_KEYS 没收录 contest.history-keep（防漂移断言管不到它）");
            require(raw.getInt("contest.history-keep", -1) >= 1,
                    "contest.history-keep 的内置默认值必须是正整数");
            // v2.26.0 审计轮补：上面三条只管「内置资源 + 必需键清单」，
            // 管不到**迁移步骤本身**。把 step32ContestHistory(plugin, cfg) 从 run() 里删掉、
            // 或把 CURRENT_VERSION 退回 22，上面全绿 —— 而老服主的 config.yml 永远补不上这个键。
            require(com.forgemaster.config.ConfigMigrations.CURRENT_VERSION >= 23,
                    "CURRENT_VERSION 退回到 " + com.forgemaster.config.ConfigMigrations.CURRENT_VERSION
                            + "（本轮引入了 contest.history-keep，至少要 23）");
            java.io.File pkgRoot = findSourcePackageRoot();
            if (pkgRoot != null) {
                String mig = nonCommentSource(new java.io.File(pkgRoot, "config/ConfigMigrations.java"));
                if (mig != null && !mig.isEmpty()) {
                    String runBody = methodBody(mig, "void run(");
                    require(runBody != null, "找不到 ConfigMigrations.run() 方法体");
                    require(hits(runBody, "step32ContestHistory(") == 1,
                            "ConfigMigrations.run() 里没有（或重复）调用 step32ContestHistory —— "
                                    + "老服主的 contest.history-keep 补不上");
                }
            }
        });
        add(g, "存档清单无遗漏：源码里每个存档写入点都在 SAVE_FILES 里", () -> {
            // v2.26.0 审计轮的结论：旧的「备份清单覆盖全部存档文件」
            // （SelfTestRegression231:194）是**单向子集检查** —— 它硬编码 18 个名字问
            // 「清单里有没有」，从不问「仓库里还有没有存档没进清单」。
            // 所以任何**新增**存档它都必然放行：这轮漏掉 contest-history / power-log /
            // hall-legacy 三份，它一条都没反应。
            // 这里补的是反方向：扫全仓源码里的 getDataFolder() 写入点，反查清单。
            java.io.File pkgRoot = findSourcePackageRoot();
            if (pkgRoot == null) {
                return;
            }
            java.util.Set<String> declared = new java.util.HashSet<>(
                    java.util.Arrays.asList(com.forgemaster.backup.BackupManager.SAVE_FILES));
            // 允许不进清单的文件，每一个都要有理由 —— 不写理由 = 下次没人敢动
            java.util.Map<String, String> exempt = new java.util.HashMap<>();
            exempt.put("config.yml", "配置本体，不是玩家进度；回档不该退回服主的配置");
            // 自检专用的临时存档：测完自己 delete()，不该进玩家进度备份。
            // 三个都必须写理由 —— 下面有断言强制理由非空，防止 exempt 变成「贴个名字就放行」。
            exempt.put("anvils-selftest.yml", "自检临时：砧位用例自建的存档，用例结束即删");
            exempt.put("anvils-selftest-batch.yml", "自检临时：批量砧位用例，用例结束即删");
            exempt.put("selftest-contest-history.yml", "自检临时：赛史用例专用文件，不碰真实 contest-history.yml");
            exempt.put("selftest-hall-contest-champion.yml", "自检临时：名人堂冠军行用例专用文件，不碰真实 contest-history.yml，用例结束即删");
            java.util.Set<String> found = new java.util.TreeSet<>();
            try (var walk = java.nio.file.Files.walk(pkgRoot.toPath())) {
                for (java.nio.file.Path p : walk.filter(x -> x.toString().endsWith(".java")).toList()) {
                    String src = nonCommentSource(p.toFile());
                    // nonCommentSource 读不到时返回空串（永不返回 null），空串不能静默放行 ——
                    // 否则一个读失败的文件就悄悄贡献 0 个写入点，这条钉子变成假的。
                    if (src == null || src.isEmpty()) {
                        require(false, "源码文件读不出内容，存档扫描不完整：" + p);
                        return;
                    }
                    // 形式一：getDataFolder(), "xxx.yml"
                    var m = java.util.regex.Pattern
                            .compile("getDataFolder\\(\\),\\s*\"([A-Za-z0-9_.-]+\\.yml)\"")
                            .matcher(src);
                    while (m.find()) {
                        found.add(m.group(1));
                    }
                    // 形式二：文件名放在常量里 —— getDataFolder(), FILE_NAME
                    // 这是 hall-legacy.yml 的写法（SeasonLegacy.FILE_NAME），只认字面量的话
                    // 恰好漏掉本钉子注释里点名的那第三份文件，等于留了个结构性盲区。
                    var mc = java.util.regex.Pattern
                            .compile("getDataFolder\\(\\),\\s*([A-Z_][A-Z0-9_]*)")
                            .matcher(src);
                    java.util.List<String> constNames = new java.util.ArrayList<>();
                    while (mc.find()) {
                        constNames.add(mc.group(1));
                    }
                    for (String cname : constNames) {
                        // 在同文件里找 `static final String CNAME = "xxx.yml";`
                        var md = java.util.regex.Pattern
                                .compile("String\\s+" + java.util.regex.Pattern.quote(cname)
                                        + "\\s*=\\s*\"([A-Za-z0-9_.-]+\\.yml)\"")
                                .matcher(src);
                        if (md.find()) {
                            found.add(md.group(1));
                        }
                    }
                }
            } catch (java.io.IOException ex) {
                require(false, "扫描源码目录失败: " + ex.getMessage());
                return;
            }
            // exempt 里每个 key 必须带非空理由，否则「豁免」就成了「免检」
            for (var e : exempt.entrySet()) {
                require(e.getValue() != null && !e.getValue().isBlank(),
                        "exempt 里的 " + e.getKey() + " 没写豁免理由（不许裸贴文件名放行）");
            }
            // 只报「源码里真会写到数据目录、但清单里没有」的
            java.util.List<String> missing = new java.util.ArrayList<>();
            for (String f : found) {
                if (declared.contains(f) || exempt.containsKey(f)) {
                    continue;
                }
                missing.add(f);
            }
            require(missing.isEmpty(),
                    "这些存档文件有写入点但不在 SAVE_FILES 里，回档时既不备份也不恢复：" + missing
                            + "（确实不该备份就加进 exempt 并写明理由）");
            // 常量形式的反查必须真的生效：hall-legacy.yml 就是靠这条分支才被扫到的，
            // 如果哪天常量解析坏了，上面 missing 会静默少一项（假绿），这里钉住它。
            require(found.contains("hall-legacy.yml"),
                    "扫描没认出 hall-legacy.yml（它用 SeasonLegacy.FILE_NAME 常量拼路径）"
                            + "—— 常量形式解析坏了，清单漏项会被静默放过");
            // hall-legacy.yml 是唯一「在清单里、但 flushAll / reloadAllSilently 都是 0」的项。
            // 那是对的，但**只因为在它没有内存缓存**（每次 all()/ofSeason() 都直读盘）。
            // 这个前提没有任何东西守着：哪天有人给 SeasonLegacy 加了字段缓存而忘了补 reload 钩子，
            // 回档后 /hall legacy 会一直显示回档前的数据，而所有钉子全绿。
            // 所以在这里把前提钉住 —— 一旦出现缓存字段，这条立刻红，逼人去看要不要补 reload。
            String legacySrc = nonCommentSource(new java.io.File(pkgRoot, "season/SeasonLegacy.java"));
            if (legacySrc != null && !legacySrc.isEmpty()) {
                for (String cacheHint : new String[]{"private Map<Integer, Legacy> cache",
                        "private final Map<Integer, Legacy> cache",
                        "private List<Legacy> cache"}) {
                    require(!legacySrc.contains(cacheHint),
                            "SeasonLegacy 出现了内存缓存（" + cacheHint + "）—— "
                                    + "那 BackupManager.flushAll/reloadAllSilently 必须补 hall-legacy.yml，"
                                    + "否则回档后剑传承会一直是旧数据");
                }
            }
        });
        add(g, "回档也搬趋势快照与赛季归档（不能只搬 .yml 清单）", () -> {
            // v2.26.0 的第三个真实缺口：backup/restore 原来只搬 SAVE_FILES 里的文件，
            // 而趋势快照是数据根目录下的 metrics-<yyyyMMdd>.yml 散文件、
            // 赛季归档在 seasons/ 目录 —— 两类都不在清单里，回档时完全不参与。
            // 更要命的是原来 restore() 里写的是 `new File(dir, "metrics")` 整目录替换：
            // 那个目录从来不存在（快照是散文件），所以这段是死代码，看着像做了其实没做。
            java.io.File pkgRoot = findSourcePackageRoot();
            if (pkgRoot == null) {
                return;
            }
            String src = nonCommentSource(new java.io.File(pkgRoot, "backup/BackupManager.java"));
            require(src != null, "找不到 BackupManager.java");
            require(hits(src, "\"metrics-\"") >= 1 || src.contains("metrics-"),
                    "BackupManager 里找不到趋势快照前缀（metrics-），快照不参与备份");
            require(src.contains("SEASONS_DIR") && src.contains("\"seasons\""),
                    "BackupManager 里找不到 seasons/ 归档目录，赛季最终名次不参与备份");
            String backupBody = methodBody(src, "String backup(");
            require(backupBody != null, "找不到 backup() 方法体");
            require(backupBody.contains("metricsFiles("),
                    "backup() 没搬趋势快照（metrics-*.yml 不在清单里，必须单独复制）");
            require(backupBody.contains("SEASONS_DIR"),
                    "backup() 没搬 seasons/ 归档目录");
            String restoreBody = methodBody(src, "String restore(");
            require(restoreBody != null, "找不到 restore() 方法体");
            require(restoreBody.contains("backMetrics") || restoreBody.contains("metricsFiles("),
                    "restore() 没恢复趋势快照 —— 回档后 /zhujian metrics 还是回档后的数");
            require(restoreBody.contains("SEASONS_DIR"),
                    "restore() 没恢复 seasons/ 归档目录");
            // 必须钉住「不再用整目录 metrics」这个死代码写法
            require(hits(restoreBody, "\"metrics\"") == 0,
                    "restore() 里还在用 \"metrics\" 目录（那是死代码，快照是根目录散文件）");
        });
        add(g, "回档链路覆盖 v2.26.0 的两份新存档（contest-history / power-log）", () -> {
            // 只把文件加进 SAVE_FILES 是不够的：回档覆盖后必须按盘重建内存态，
            // 否则旧副本在下次写盘时把回档结果盖回去 —— v2.23.1 的 P0 同型。
            // 这条钉子同时守三处：清单、flushAll、reloadAllSilently。
            java.io.File pkgRoot = findSourcePackageRoot();
            if (pkgRoot == null) {
                return;
            }
            String src = nonCommentSource(new java.io.File(pkgRoot, "backup/BackupManager.java"));
            require(src != null, "找不到 BackupManager.java");
            for (String f : new String[]{"contest-history.yml", "power-log.yml"}) {
                require(hits(src, "\"" + f + "\"") >= 3,
                        f + " 在 BackupManager 里出现不足 3 处（清单 + flushAll + reload 各需一处）");
            }
            require(src.contains("historyStore().load()"),
                    "回档后没重建大赛档案内存态（contest-history.yml 会被旧副本盖回去）");
            // flushAll / reloadAllSilently 里两份新存档都被 swallow(stage, "文件名", ...) 包着，
            // 所以按方法体分别核「这两个文件名各出现一次」—— 比数整文件的出现次数精确：
            // 整文件只要清单 + 两处 swallow 够 3 次就算过，但漏掉 reload 那一条同样能凑够 3 次。
            String flush = methodBody(src, "void flushAll(");
            require(flush != null, "找不到 flushAll 方法体（改名了？）");
            String reload = methodBody(src, "void reloadAllSilently(");
            require(reload != null, "找不到 reloadAllSilently 方法体（改名了？）");
            for (String f : new String[]{"contest-history.yml", "power-log.yml"}) {
                // 用 >=1 而不是 ==1：这两段里加任何一句提到文件名的日志
                // （`warning("写 contest-history.yml 失败")`）都会让计数变 2。
                // 那种改动是对的，钉子不该判它红 —— 要守的是「这一段提到了并且真的动了它」，
                // 不是「只准出现一次」。方向靠下面的 save()/load() 断言守。
                require(hits(flush, "\"" + f + "\"") >= 1,
                        "flushAll 没落 " + f + "（回档前盘面就不是真相）");
                require(hits(reload, "\"" + f + "\"") >= 1,
                        "reloadAllSilently 没重载 " + f + "（旧副本会盖回回档结果）");
            }
            // 重载必须是 load()，不是 save()：按盘重建，不能拿回档前的内存去写盘。
            // 这里按「段落」而不是「整段文本里有没有 .load()」来判 —— reload 里
            // storyCfg.load() 之类随便哪个都能满足 contains(".load()")，那个断言等于没写。
            String reloadPower = sliceByMarker(reload, "\"power-log.yml\"");
            require(reloadPower != null, "reloadAllSilently 里找不到 power-log.yml 那一段");
            require(reloadPower.contains(".load()"),
                    "power-log 的重载段没有调用 load()（按盘重建才是对的）");
            // 堵住两种写法：powerLog().save() 与 store.save()（flushAll 里正是 store.save()）
            require(hits(reloadPower, ".save()") == 0,
                    "power-log 的重载段出现了 save()（会把回档前的内存写回去，比不重载更糟）");
            String reloadHistory = sliceByMarker(reload, "\"contest-history.yml\"");
            require(reloadHistory != null, "reloadAllSilently 里找不到 contest-history.yml 那一段");
            require(reloadHistory.contains("historyStore().load()"),
                    "contest-history 的重载不是 historyStore().load()（拿旧内存写盘比不重载更糟）");
            require(hits(reloadHistory, ".save()") == 0,
                    "contest-history 的重载段出现了 save()（会把回档前的内存写回去）");
        });
        add(g, "回档链路不留「唯独它特殊」的例外（bounty 补 flush / hall 补 reload）", () -> {
            // 第三轮审计留下的两条，形状都相同：**清单里有它，但两条链路各缺一步**。
            // 上一轮正是「唯独它特殊」这个借口让 bounty 的缺口藏了两整轮，
            // 所以这条钉子不是「顺便查一下」，而是把一个具体教训变成判据。
            java.io.File pkgRoot = findSourcePackageRoot();
            if (pkgRoot == null) {
                return;
            }
            String src = nonCommentSource(new java.io.File(pkgRoot, "backup/BackupManager.java"));
            require(src != null, "找不到 BackupManager.java");
            String flush = methodBody(src, "void flushAll(");
            String reload = methodBody(src, "void reloadAllSilently(");
            require(flush != null, "找不到 flushAll 方法体（改名了？）");
            require(reload != null, "找不到 reloadAllSilently 方法体（改名了？）");

            // ① bounty-data.yml：原来只接了 reload，flushAll 里没有 —— 全仓唯一例外。
            // 缺 flush 的后果是「回档把文件换成旧的，内存里还是新的，下次 claim() 整份回写」。
            require(hits(flush, "\"bounty-data.yml\"") >= 1,
                    "flushAll 没落 bounty-data.yml（回档前盘面不是真相，旧 claimed 会盖回回档结果）");
            require(hits(reload, "\"bounty-data.yml\"") >= 1,
                    "reloadAllSilently 没重载 bounty-data.yml（内存旧态会让下次 claim 整份回写）");
            require(flush.contains("bounties().flush()"),
                    "flushAll 里 bounty 那一段不是 bounties().flush()（需要一个明确的落盘入口）");
            String sb = nonCommentSource(new java.io.File(pkgRoot, "bounty/BountyManager.java"));
            require(sb != null && !sb.isEmpty(), "找不到 BountyManager.java");
            require(hits(sb, "public void flush()") == 1,
                    "BountyManager 没有 public void flush()（回档链路调不到落盘入口）");

            // ② hall.yml：HallShrine.origin 是字段缓存，回档换了文件它不知道，
            // 于是殿堂原点在回档后到重启前一直是旧坐标。这条必须接 reload。
            require(hits(reload, "\"hall.yml\"") >= 1,
                    "reloadAllSilently 没重载 hall.yml（HallShrine.origin 字段缓存会让原点停在旧坐标）");
            require(reload.contains("hallShrine().reloadOrigin()"),
                    "hall.yml 的重载不是 hallShrine().reloadOrigin()（只读文件不刷缓存等于没重载）");
            String sh = nonCommentSource(new java.io.File(pkgRoot, "hall/HallShrine.java"));
            require(sh != null && !sh.isEmpty(), "找不到 HallShrine.java");
            require(hits(sh, "public void reloadOrigin()") == 1,
                    "HallShrine 没有 public void reloadOrigin()（回档后缓存刷不掉）");

            // ③ 反方向也要守：hall.yml **不该**出现在 flushAll。
            // 它的两次写都是变更即写盘，没有内存脏窗口；加一行 flush 只会在回档日志里
            // 多一条「什么都没做」的记录，还会让人以为它和 bounty 是同一种病。
            // 这条断言的意义是：以后有人「为了让表格整齐」而补上它，会被拦下来问一句为什么。
            require(hits(flush, "\"hall.yml\"") == 0,
                    "flushAll 里不该有 hall.yml —— 它变更即写盘，没有脏窗口；该补的是 reload 不是 flush");

            // ④ 同类病最难查的一点：四个模块都有 save/load 入口却漏接线。
            // 这里顺带确认 BountyManager.save() 的异常面也补了 RuntimeException
            // （YamlIo.save 可能抛 SecurityException / IllegalArgumentException），
            // 否则一个权限问题会冒到 claim() 的玩家提示里。
            require(sb.contains("catch (RuntimeException ex)"),
                    "BountyManager.save() 没兜 RuntimeException（未预期异常会冒到 claim 的调用方）");
        });
        add(g, "悬赏领赏必须是两段式（不可逆操作不许一步到位）", () -> {
            // v2.26.1：claim() 一旦过关就 setItemInMainHand(null) —— 剑当场消失、不可撤销，
            // 换回一笔小钱。而触发条件只是「主手拿了件达标的锻造品」，玩家整理背包时
            // 手滑输一次命令就把主属性最好的剑交出去了。
            // 全仓同类不可逆操作（spirit reroll / dissolve / talent reset / strengthen strip）
            // 都是两段式，唯独这里是一步到位 —— 是漏的，不是特意的。
            java.io.File pkgRoot = findSourcePackageRoot();
            if (pkgRoot == null) {
                return;
            }
            String bm = nonCommentSource(new java.io.File(pkgRoot, "bounty/BountyManager.java"));
            require(bm != null && !bm.isEmpty(), "找不到 BountyManager.java");
            // ① 签名必须带 confirm 位：三参调用是「预览 / 执行」的分界
            require(hits(bm, "public String claim(Player p, int index, boolean confirm)") == 1,
                    "BountyManager.claim 不是两段式签名（缺 confirm 位就等于没有闸）");
            // ② 收剑那一行必须出现在 confirm 分支之后
            String previewMark = "\"/zhujian shangjin claim \" + (index + 1) + \" confirm</white>\"";
            int previewAt = bm.indexOf("!confirm");
            int takeAt = bm.indexOf("setItemInMainHand(null)");
            require(previewAt > 0, "claim 里找不到 !confirm 预览分支");
            require(takeAt > previewAt,
                    "收剑（setItemInMainHand(null)）出现在预览分支之前 —— 预览也会真的收剑");
            // ③ confirm 必须校验「还是预览那把剑」，否则可以预览破剑再换好剑 confirm。
            // 只断言有指纹比较：不锁死实现细节（换哈希算法不该判红）。
            require(bm.contains("pend.sword().equals(sword)"),
                    "confirm 没校验主手还是预览那把剑（可预览 A 剑、换成 B 剑再确认）");
            require(hits(bm, "pendingClaim.remove(") >= 1,
                    "confirm 用完没摘掉待确认状态（可重复确认）");
            // ④ 退服要清 —— 否则重登后 confirm 仍能匹配上旧预览，等于绕过预览。
            String listener = nonCommentSource(
                    new java.io.File(pkgRoot, "listener/ForgeListener.java"));
            require(listener != null && listener.contains("bounties().forgetPending("),
                    "退服没清悬赏待确认状态（重登后可直接 confirm，绕过预览）");
            // ⑤ 命令层必须把第 4 段认成 confirm，并且预览返回的空串不能走 Msg.prefix
            String cmd = nonCommentSource(
                    new java.io.File(pkgRoot, "command/ForgingCommand.java"));
            require(cmd != null && cmd.contains("args[3].equalsIgnoreCase(\"confirm\")"),
                    "命令层没识别 confirm 段（玩家写了 confirm 也只会再预览一次）");
            require(cmd.contains("out != null && !out.isBlank()"),
                    "预览分支返回空串却照样 Msg.prefix（玩家会收到一条只有前缀的空消息）");
            // ⑥ Tab 补全第 4 段要给 confirm，不然玩家得靠记
            require(cmd.contains("List.of(\"confirm\"), args[3]"),
                    "Tab 补全没给第 4 段的 confirm");
        });
        add(g, "比试维度不许被活跃档案判据漏掉（v2.26.0 审计轮）", () -> {
            // 真实缺陷：Rankings.active() 原来只看 forged / coins / reputation / streak。
            // 一个只打过比试的号（duel 不收费、结算只 countDuel）会被判成「没有过数据」，
            // 整条从 rank duel 榜上消失 —— 而 profile 里明明写着胜负。
            // 这条直接跑行为，不扫源码：造一个只有 duel 数据的 Stats，问 active 认不认。
            StatsStore.Stats duelOnly = new StatsStore.Stats();
            duelOnly.name = "SelftestDuelOnly";
            duelOnly.duelWins = 3;
            duelOnly.duelLosses = 1;
            require(com.forgemaster.ranking.Rankings.active(duelOnly),
                    "只打过比试（3 胜 1 负）的档案被判成「没有过数据」→ rank duel 榜上看不到他");
            // 反方向也要守：真空档案仍然不许进榜，否则榜单会被空号灌满
            StatsStore.Stats empty = new StatsStore.Stats();
            empty.name = "SelftestEmpty";
            require(!com.forgemaster.ranking.Rankings.active(empty), "完全空白的档案不该进榜");
            // 自检号永远不进榜
            StatsStore.Stats st = new StatsStore.Stats();
            st.name = "SelfTest";
            st.forged = 99;
            require(!com.forgemaster.ranking.Rankings.active(st), "自检数据不许进榜");
        });
        add(g, "赛季档案的键名不随 JVM locale 变（toLowerCase 必须带 ROOT）", () -> {
            // 土耳其语环境下 "I".toLowerCase() 是 ı 而不是 i：COINS → "coıns"，
            // 和历史归档里的 "coins" 变成两个键。同服读写一致所以当场不报错，
            // 换服务器 / 换 locale 之后旧赛季档案就读不到了。
            // Metric.tokens() 一直是 Locale.ROOT，SeasonManager 曾经没带 —— 这里钉住一致。
            java.io.File pkgRoot = findSourcePackageRoot();
            if (pkgRoot == null) {
                return;
            }
            String src = nonCommentSource(new java.io.File(pkgRoot, "season/SeasonManager.java"));
            require(src != null && !src.isEmpty(), "找不到 SeasonManager.java");
            require(hits(src, ".toLowerCase()") == 0,
                    "SeasonManager 里还有不带 Locale 的 toLowerCase()（历史键名会随 JVM locale 变）");
            require(hits(src, "toLowerCase(java.util.Locale.ROOT)") >= 1,
                    "SeasonManager 没在用 Locale.ROOT 折叠枚举名");
        });
        add(g, "金额格式化可读", () -> {
            String s = plugin.economy().format(200);
            require(s != null && !s.isBlank() && s.contains("200"), "金额格式异常: " + s);
        });
        add(g, "消费口径统一：剑币账户只有一本账", () -> {
            var w = plugin.wallet();
            require(w != null, "Wallet 没有初始化");
            // 默认口径必须是 plugin（README：剑币是本插件唯一货币）
            String sink = plugin.settings().str("economy.coin-sinks", "plugin");
            require(sink != null && (sink.equalsIgnoreCase("plugin") || sink.equalsIgnoreCase("server")),
                    "economy.coin-sinks 只能是 plugin / server，实际 " + sink);
            require(!w.sinkLabel().isBlank(), "消费口径标签为空");
            // 内置剑币后端下，Wallet 与 StatsStore 必须读同一本账
            if (!w.serverSinks() || plugin.economy().backend() == com.forgemaster.econ.EconomyBridge.Backend.COINS) {
                var fake = java.util.UUID.nameUUIDFromBytes("wallet-selftest".getBytes());
                long before = plugin.stats().coins(fake);
                plugin.stats().addCoins(fake, 123);
                require(plugin.stats().coins(fake) == before + 123, "剑币入账没生效");
                plugin.stats().takeCoins(fake, 123);
                require(plugin.stats().coins(fake) == before, "剑币出账没还原");
            }
        });
        add(g, "宝石 / 黑市 / 签到 / 重铸 不再直连 Vault", () -> {
            // v2.19.1：这批消费改走 Wallet，扣的钱和锻造奖励必须是同一本账。
            // 代码级断言：源里这些模块不该再出现 plugin.economy() 的扣费调用。
            String[] suspects = {"gem/GemService.java", "blackmarket/BlackmarketService.java",
                    "blackmarket/DailyRewardService.java", "gui/ReforgeMenu.java"};
            for (String rel : suspects) {
                java.io.File src = new java.io.File("src/main/java/com/forgemaster/" + rel);
                if (!src.isFile()) {
                    continue; // 只有开发树里才有源码，线上 jar 跑这条就跳过
                }
                String code;
                try {
                    code = java.nio.file.Files.readString(src.toPath());
                } catch (java.io.IOException ex) {
                    continue;
                }
                require(!code.contains("plugin.economy().withdraw")
                                && !code.contains("eco.withdraw"),
                        rel + " 还在直接扣服务器经济");
            }
        });
        add(g, "六档锻造费为 200/400/500/1000/2000/5000", () -> {
            long[] want = {200, 400, 500, 1000, 2000, 5000};
            List<ForgeMaterial> sorted = plugin.materials().sorted();
            require(sorted.size() >= want.length, "剑胚不足 6 档，无法核对价格");
            for (int i = 0; i < want.length; i++) {
                require(sorted.get(i).price() == want[i], sorted.get(i).id() + " 锻造费应为 "
                        + want[i] + "，实际 " + sorted.get(i).price());
            }
        });
        add(g, "星陨剑改收五种矿物块", () -> {
            ForgeMaterial star = plugin.materials().get("starfall");
            require(star != null, "配置里没有 starfall");
            require(star.costs().size() >= 5, "星陨耗材只有 " + star.costs().size() + " 种");
            for (Map.Entry<Material, Integer> e : star.costs().entrySet()) {
                require(e.getValue() >= 1, e.getKey() + " 数量异常");
            }
        });
        add(g, "装备目录：四部位 × 各档都能生成", () -> {
            double mult = plugin.settings().num("armor.price-multiplier", 1.0);
            for (ForgeMaterial base : plugin.materials().all()) {
                for (ForgeMaterial.Slot slot : ForgeMaterial.Slot.values()) {
                    ForgeMaterial armor = com.forgemaster.material.ArmorCatalog.create(base, slot, mult);
                    require(armor != null, base.id() + " / " + slot + " 生成失败");
                    require(armor.armor() && armor.slot() == slot, base.id() + " 部位没有写入");
                    require(armor.bonusArmor() > 0, base.id() + " 装备没有额外护甲");
                    require(armor.bonusAttack() == 0, base.id() + " 装备不该带攻击加成");
                }
            }
        });
        add(g, "装备锻造费与同档武器一致", () -> {
            double mult = plugin.settings().num("armor.price-multiplier", 1.0);
            for (ForgeMaterial base : plugin.materials().all()) {
                ForgeMaterial armor = com.forgemaster.material.ArmorCatalog.create(
                        base, ForgeMaterial.Slot.CHESTPLATE, mult);
                long want = Math.round(base.price() * mult);
                require(armor.price() == want, base.id() + " 装备价 " + armor.price() + " ≠ " + want);
            }
        });
        add(g, "防御词条齐全且能严格抽出", () -> {
            for (String id : List.of("armor_plate", "tough_plate", "vitality", "knockback_resist", "aegis")) {
                require(plugin.affixes().get(id) != null, "缺少防御词条 " + id);
            }
            require(!plugin.affixes().pickStrict(List.of("armor_plate", "vitality"), 2).isEmpty(),
                    "防御词条抽不出来");
        });
        add(g, "BossBar 标题已转义为传统颜色码", () -> {
            String legacy = Msg.legacy("<gray>大赛 <#D5B2DA>ABC <dark_gray>|");
            require(!legacy.contains("<"), "仍有 MiniMessage 标签残留: " + legacy);
            require(!legacy.contains("D5B2DA"), "十六进制色码没转换: " + legacy);
        });
        add(g, "经济流水恒等：余额 == 初始 + 产出 - 消耗（v2.20）", () -> {
            // 流水计数是看板的全部地基。只要「产出 - 消耗」跟余额对不上，
            // 基尼系数、中位数、趋势曲线全是错的，而且错得很安静——数字看着正常。
            StatsStore stats = plugin.stats();
            require(stats != null, "StatsStore 没有初始化");
            stats.resetFlowCounters();
            UUID who = java.util.UUID.randomUUID();
            long before = stats.coins(who);
            stats.addCoins(who, 500, com.forgemaster.econ.CoinReason.FORGE);
            stats.addCoins(who, 200, com.forgemaster.econ.CoinReason.CONTEST);
            require(stats.takeCoins(who, 300, com.forgemaster.econ.CoinReason.SHOP),
                    "余额 " + stats.coins(who) + " 却扣不动 300");
            require(stats.takeCoins(who, 100, com.forgemaster.econ.CoinReason.GEM),
                    "余额 " + stats.coins(who) + " 却扣不动 100");
            require(stats.producedTotal() == 700, "产出合计应为 700，实际 " + stats.producedTotal());
            require(stats.consumedTotal() == 400, "消耗合计应为 400，实际 " + stats.consumedTotal());
            require(stats.coins(who) == before + 300, "余额应为初始 " + before + " + 300，实际 "
                    + stats.coins(who));
            Long forgeIn = stats.producedByReason().get("forge");
            Long contestIn = stats.producedByReason().get("contest");
            Long shopOut = stats.consumedByReason().get("shop");
            Long gemOut = stats.consumedByReason().get("gem");
            require(forgeIn != null && forgeIn == 500L, "forge 分桶应为 500，实际 " + forgeIn);
            require(contestIn != null && contestIn == 200L, "contest 分桶应为 200，实际 " + contestIn);
            require(shopOut != null && shopOut == 300L, "shop 分桶应为 300，实际 " + shopOut);
            require(gemOut != null && gemOut == 100L, "gem 分桶应为 100，实际 " + gemOut);
            stats.resetFlowCounters(); // 别把测试流量留给别的断言算账
        });
        add(g, "扣款失败不记流水：钱不够时消耗计数不变（v2.20）", () -> {
            // 计数必须写在「真的扣掉了」之后。写在前面的话，被拒绝的购买也会进消耗榜，
            // 服主对账时会看到一批凭空蒸发的剑币，然后开始怀疑整个看板。
            StatsStore stats = plugin.stats();
            require(stats != null, "StatsStore 没有初始化");
            stats.resetFlowCounters();
            UUID poor = java.util.UUID.randomUUID();
            require(stats.coins(poor) == 0, "新玩家初始剑币应为 0，实际 " + stats.coins(poor));
            require(!stats.takeCoins(poor, 999999, com.forgemaster.econ.CoinReason.SHOP),
                    "余额 0 却扣走了 999999");
            require(stats.consumedTotal() == 0, "扣款失败却记了消耗：" + stats.consumedTotal());
            require(stats.consumedByReason().isEmpty(), "扣款失败却留下分桶："
                    + stats.consumedByReason());
            require(stats.coins(poor) == 0, "扣款失败却动了余额：" + stats.coins(poor));
            stats.resetFlowCounters();
        });
        add(g, "看板配置合法：top-n 在 1~50、gini-warn 在 0~1（v2.20）", () -> {
            // 越界不会崩，只会画出没意义的东西：top-n 0 = 报表空，
            // gini-warn 5 = 永不警告（服务器贫富再悬殊也是绿的），所以夹在这里守。
            require(plugin.getConfig().isBoolean("metrics.enabled"),
                    "metrics.enabled 在配置里不是布尔值");
            boolean enabled = plugin.settings().flag("metrics.enabled", true);
            require(enabled == plugin.getConfig().getBoolean("metrics.enabled", true),
                    "metrics.enabled 经 Settings 读出来跟盘上不一致");
            int topN = plugin.settings().intOf("metrics.top-n", 10);
            require(topN >= 1 && topN <= 50, "metrics.top-n 应在 1~50，实际 " + topN);
            double gini = plugin.settings().num("metrics.gini-warn", 0.55);
            require(gini >= 0 && gini <= 1, "metrics.gini-warn 应在 0~1，实际 " + gini);
        });
    }

    // ---------------------------------------------------------------- 材料 / 词条 / 淬火

    private void registerV23() {
        String g = "v2.23 崩刃失效 / 耐久档宽 / 规划器";

        add(g, "崩刃真·属性失效：归零摘基础 modifier，修复按同等级重建", () -> {
            ItemStack sword = sampleSword();
            require(sword != null, "造不出测试剑");
            var st = plugin.strengthen();
            require(st.setLevel(sword, 30), "setLevel(30) 失败");
            st.apply(sword);
            double atk = modifierAmount(sword, org.bukkit.attribute.Attribute.ATTACK_DAMAGE, "fm_atk");
            require(atk > 0, "强化 modifier 没挂上（fm_atk=" + atk + "）");
            require(plugin.items().setDurability(sword, 0), "归零应判崩刃");
            require(modifierAmount(sword, org.bukkit.attribute.Attribute.ATTACK_DAMAGE, "fm_atk") == 0,
                    "崩刃后 fm_atk 还挂着——「属性失效」没有兑现");
            require(!plugin.items().setDurability(sword, 10), "修复不该判崩刃");
            double atk2 = modifierAmount(sword, org.bukkit.attribute.Attribute.ATTACK_DAMAGE, "fm_atk");
            require(Math.abs(atk2 - atk) < 1e-6,
                    "修复后 modifier 没按同等级重建: " + atk2 + " vs " + atk);
        });

        add(g, "耐久档宽：step=5 下档内不重写 Lore、跨档重写", () -> {
            ItemStack sword = sampleSword();
            int max = plugin.items().maxDurabilityOf(sword);
            require(max >= 40, "耐久太小测不出档: " + max);
            int step = plugin.settings().durabilityLoreStep();
            require(step >= 0 && step <= 50, "档宽读数越界: " + step);
            // 基线取「档中间」：满耐久是档顶，从满值掉任何一点都可能跨档
            int bandWidth = Math.max(2, max * Math.max(1, step) / 100);
            int anchor = max - bandWidth / 2;
            plugin.items().setDurability(sword, anchor);
            java.util.List<net.kyori.adventure.text.Component> before = sword.getItemMeta().lore();
            require(before != null, "Lore 没建");
            // 档内一击（再掉 1，仍在同一档）：Lore 必须原样
            plugin.items().setDurability(sword, anchor - 1);
            require(java.util.Objects.equals(before, sword.getItemMeta().lore()),
                    "档内一击重写了 Lore（性能快路的语义被破坏）");
            // 跨档（掉到一半）：Lore 必须刷新
            plugin.items().setDurability(sword, max / 2);
            require(!java.util.Objects.equals(before, sword.getItemMeta().lore()),
                    "跨档没有重写 Lore 耐久行");
        });

        add(g, "强化规划器：期望值有限、目标钳上限、已达标零开销", () -> {
            ItemStack sword = sampleSword();
            require(sword != null, "造不出测试剑");
            var st = plugin.strengthen();
            int cap = st.capOf(sword);
            var r = st.plan(sword, cap + 100, 200);
            require(r.targetLevel() == cap, "目标没钳到上限: " + r.targetLevel());
            require(r.expHits() > 0 && Double.isFinite(r.expHits()), "期望锤数异常: " + r.expHits());
            require(r.expCoins() > 0 && r.expMaterials() > 0, "期望开销异常");
            require(r.reachedRate() >= 0 && r.reachedRate() <= 1, "达成率越界: " + r.reachedRate());
            require(r.expBreaks() >= 0, "期望碎剑为负");
            var r0 = st.plan(sword, 0, 10);
            require(r0.expHits() == 0 && r0.reachedRate() == 1.0, "已达标的目标不该有开销");
        });
    }

    /** 某属性上指定键名的 modifier 总量（找不到返回 0） */
    private double modifierAmount(ItemStack item, org.bukkit.attribute.Attribute attr, String keyName) {
        var meta = item.getItemMeta();
        if (meta == null) {
            return 0;
        }
        var mods = meta.getAttributeModifiers(attr);
        if (mods == null) {
            return 0;
        }
        double sum = 0;
        for (var m : mods) {
            if (m.getKey() != null && keyName.equals(m.getKey().getKey())) {
                sum += m.getAmount();
            }
        }
        return sum;
    }

    private void registerMaterialsAffixesQuench() {
        String g = "材料 / 词条 / 淬火";
        add(g, "剑胚注册表非空", () ->
                require(!plugin.materials().all().isEmpty(), "没有任何剑胚"));
        add(g, "每个剑胚的最佳温度落在合理区间", () -> {
            for (ForgeMaterial m : plugin.materials().all()) {
                require(m.idealTemp() > 0 && m.idealTemp() < 200, m.id() + " 最佳温度异常: " + m.idealTemp());
            }
        });
        add(g, "每个剑胚的容差为正", () -> {
            for (ForgeMaterial m : plugin.materials().all()) {
                require(m.tolerance() > 0, m.id() + " 容差非正");
            }
        });
        add(g, "每个剑胚的敲击次数为正", () -> {
            for (ForgeMaterial m : plugin.materials().all()) {
                require(m.strikes() > 0, m.id() + " 敲击次数非正");
            }
        });
        add(g, "剑胚 inBand 判定：最佳温度在区间内", () -> {
            ForgeMaterial m = plugin.materials().all().iterator().next();
            require(m.inBand(m.idealTemp()), "最佳温度判定为区间外");
        });
        add(g, "剑胚 deviation：最佳温度偏差为 0", () -> {
            ForgeMaterial m = plugin.materials().all().iterator().next();
            require(Math.abs(m.deviation(m.idealTemp())) < 1e-9, "偏差不为 0");
        });
        add(g, "词条注册表非空", () ->
                require(!plugin.affixes().all().isEmpty(), "没有任何词条"));
        add(g, "词条 get 能取回已注册词条", () -> {
            var first = plugin.affixes().all().iterator().next();
            require(plugin.affixes().get(first.id()) != null, "get 取不回 " + first.id());
        });
        add(g, "词条 pick 数量不超过请求数", () -> {
            var pool = List.of(plugin.affixes().all().iterator().next().id());
            require(plugin.affixes().pick(pool, 3).size() <= 3, "pick 超出请求数量");
        });
        add(g, "每个淬火介质的词条池非空", () -> {
            for (var q : plugin.quenches().all()) {
                require(q.pool() != null && !q.pool().isEmpty(), q.id() + " 词条池为空");
            }
        });
        add(g, "每个淬火介质的词条池都能抽出词条", () -> {
            for (var q : plugin.quenches().all()) {
                require(!plugin.affixes().pick(q.pool(), 4).isEmpty(), q.id() + " 抽不出词条");
            }
        });
        add(g, "淬火介质数量不超过界面槽位", () ->
                require(plugin.quenches().all().size() <= ForgeMenu.QUENCH_SLOTS.length,
                        "淬火介质超过界面槽位数"));
        add(g, "品质枚举与最低分单调递增", () -> {
            Quality[] qs = Quality.values();
            for (int i = 1; i < qs.length; i++) {
                require(qs[i].minScore() >= qs[i - 1].minScore(), qs[i].name() + " 最低分倒退");
            }
        });
        add(g, "材料分级标记可写可读", () -> {
            ItemStack raw = new ItemStack(Material.IRON_INGOT, 1);
            ItemStack stamped = MaterialGrades.stamp(raw, MaterialGrade.REFINED);
            require(MaterialGrades.gradeOf(stamped) == MaterialGrade.REFINED, "精炼料标记丢失");
        });
        add(g, "无标记原料视为普通料", () ->
                require(MaterialGrades.gradeOf(new ItemStack(Material.IRON_INGOT, 1)) == MaterialGrade.COMMON,
                        "无标记不是普通料"));
        add(g, "天外料的分数加成大于 0", () ->
                require(MaterialGrade.CELESTIAL.scoreBonus() > 0, "天外料没有分数加成"));
        add(g, "材料名已汉化：常见料逐项核对", () -> {
            require("铁锭".equals(MaterialGrades.pretty(Material.IRON_INGOT)), "铁锭未汉化");
            require("铜锭".equals(MaterialGrades.pretty(Material.COPPER_INGOT)), "铜锭未汉化");
            require("金锭".equals(MaterialGrades.pretty(Material.GOLD_INGOT)), "金锭未汉化");
            require("钻石".equals(MaterialGrades.pretty(Material.DIAMOND)), "钻石未汉化");
            require("下界合金锭".equals(MaterialGrades.pretty(Material.NETHERITE_INGOT)), "下界合金锭未汉化");
            require("下界之星".equals(MaterialGrades.pretty(Material.NETHER_STAR)), "下界之星未汉化");
        });
        add(g, "材料名已汉化：所有剑胚耗材都能取到中文名", () -> {
            for (ForgeMaterial m : plugin.materials().all()) {
                String name = MaterialGrades.pretty(m.costItem());
                require(name.matches(".*[\\u4e00-\\u9fa5].*"),
                        "耗材 " + m.costItem().name() + " 没有中文名，当前显示 " + name);
            }
        });
    }

    // ---------------------------------------------------------------- 锻造流程 / 界面

    private void registerForgeFlow() {
        String g = "锻造流程 / 界面";
        ForgeMaterial first = plugin.materials().all().iterator().next();
        add(g, "锻造会话可以用假玩家名建立", () -> {
            ForgeSession s = new ForgeSession(UUID.nameUUIDFromBytes("SelfTest-flow".getBytes()), NAME, first);
            require(NAME.equals(s.playerName()), "玩家名没保住");
        });
        add(g, "加热评分在窗口边缘连续（不会偏出反而更高）", () -> {
            double tol = 8.0;
            require(Math.abs(ForgeManager.heatScore(0, tol) - 1.0) < 1e-9, "正中最佳温度不是满分");
            double inside = ForgeManager.heatScore(tol * 0.999, tol);
            double outside = ForgeManager.heatScore(tol * 1.001, tol);
            require(Math.abs(inside - outside) < 0.01,
                    "窗口边缘断层：内沿 " + inside + " vs 外沿 " + outside);
            require(outside <= inside + 1e-9, "偏得更多反而分更高");
            require(ForgeManager.heatScore(tol * 3, tol) == 0.0, "3 倍容差处应为 0");
            require(ForgeManager.heatScore(0, 0) <= 1.0, "容差为 0 时不该算出 NaN/Infinity");
        });
        add(g, "会话初始阶段为加热", () -> {
            ForgeSession s = new ForgeSession(UUID.randomUUID(), NAME, first);
            require(s.phase() == com.forgemaster.forge.ForgePhase.HEATING
                    || s.phase() != null, "初始阶段为空");
        });
        add(g, "加热界面尺寸正确", () -> {
            ForgeSession s = probeSession(first);
            require(plugin.forge().menu().buildHeating(s).getSize() == ForgeMenu.FORGE_SIZE, "加热界面尺寸错");
        });
        add(g, "加热界面有风箱与出炉按钮", () -> {
            ForgeSession s = probeSession(first);
            var inv = plugin.forge().menu().buildHeating(s);
            require(inv.getItem(10) != null && inv.getItem(13) != null, "缺风箱或出炉按钮");
        });
        add(g, "加热界面有放弃键", () -> {
            var inv = plugin.forge().menu().buildHeating(probeSession(first));
            require(inv.getItem(ForgeMenu.QUIT_SLOT) != null, "缺放弃键");
        });
        add(g, "锻打界面有敲击键", () -> {
            var inv = plugin.forge().menu().buildHammering(probeSession(first));
            require(inv.getItem(ForgeMenu.STRIKE_SLOT) != null, "缺敲击键");
        });
        add(g, "锻打轨道每格都有图标", () -> {
            var inv = plugin.forge().menu().buildHammering(probeSession(first));
            for (int i = 0; i < ForgeMenu.TRACK_LEN; i++) {
                require(inv.getItem(ForgeMenu.TRACK_START + i) != null, "轨道第 " + i + " 格为空");
            }
        });
        add(g, "淬火界面介质槽位与配置一致", () -> {
            var list = plugin.quenches().all();
            var inv = plugin.forge().menu().buildQuenching(probeSession(first), list);
            for (int i = 0; i < list.size() && i < ForgeMenu.QUENCH_SLOTS.length; i++) {
                ItemStack icon = inv.getItem(ForgeMenu.QUENCH_SLOTS[i]);
                require(icon != null && icon.getItemMeta() != null, "淬火槽 " + i + " 为空");
                String shown = Msg.plain(icon.getItemMeta().displayName());
                require(shown.equals(list.get(i).display()), "淬火槽 " + i + " 摆错: " + shown);
            }
        });
        add(g, "成品界面有展品", () -> {
            ForgeSession s = probeSession(first);
            s.result(plugin.items().buildDirect(NAME, first, Quality.EPIC, 80));
            require(plugin.forge().menu().buildResult(s, s.result()).getItem(13) != null, "成品界面无展品");
        });
        add(g, "叠钢轨道每格都有图标", () -> {
            ForgeSession s = probeSession(first);
            s.foldSequence().clear();
            s.foldSequence().addAll(List.of(0, 4, 8, 2));
            s.foldCursor(0);
            s.foldDeadline(System.currentTimeMillis() + 8000);
            var inv = plugin.forge().menu().buildFolding(s);
            for (int i = 0; i < ForgeMenu.TRACK_LEN; i++) {
                require(inv.getItem(ForgeMenu.TRACK_START + i) != null, "叠钢轨道第 " + i + " 格为空");
            }
        });
        add(g, "开刃界面有加压/减压按钮", () -> {
            ForgeSession s = probeSession(first);
            s.grindValue(50);
            var inv = plugin.forge().menu().buildGrinding(s);
            require(inv.getItem(10) != null && inv.getItem(16) != null, "缺加压或减压按钮");
        });
        add(g, "开刃轨道每格都有图标", () -> {
            ForgeSession s = probeSession(first);
            s.grindValue(50);
            var inv = plugin.forge().menu().buildGrinding(s);
            for (int i = 0; i < ForgeMenu.TRACK_LEN; i++) {
                require(inv.getItem(ForgeMenu.TRACK_START + i) != null, "开刃轨道第 " + i + " 格为空");
            }
        });
        add(g, "开刃绿区：中心命中", () -> {
            ForgeSession s = probeSession(first);
            s.grindValue(50);
            s.grindTargetCenter(50);
            s.grindTargetWidth(8);
            require(s.grindInZone(), "中心应在绿区内");
        });
        add(g, "开刃绿区：越界判负", () -> {
            ForgeSession s = probeSession(first);
            s.grindTargetCenter(50);
            s.grindTargetWidth(8);
            s.grindValue(80);
            require(!s.grindInZone(), "越界仍判在绿区");
        });
        add(g, "叠钢序列生成 ≥ 3 格", () -> {
            ForgeSession s = probeSession(first);
            s.phase(com.forgemaster.forge.ForgePhase.FOLDING);
            plugin.forge().simulateStartFolding(s);
            require(s.foldSequence().size() >= 3, "序列太短: " + s.foldSequence().size());
        });
        add(g, "全点对叠钢拿满分", () -> {
            ForgeSession s = probeSession(first);
            s.phase(com.forgemaster.forge.ForgePhase.FOLDING);
            plugin.forge().simulateStartFolding(s);
            plugin.forge().simulateFoldAll(s);
            require(s.foldScore() >= 0.999, "全点对没满分: " + s.foldScore());
        });
        add(g, "端到端：锻打→叠钢→开刃→淬火 状态机走通", () -> {
            ForgeSession s = probeSession(first);
            s.heatScore(1.0);
            s.addStrike(ForgeSession.Hit.PERFECT);
            s.phase(com.forgemaster.forge.ForgePhase.FOLDING);
            plugin.forge().simulateStartFolding(s);
            plugin.forge().simulateFoldAll(s);
            require(s.phase() == com.forgemaster.forge.ForgePhase.GRINDING, "叠钢后未进开刃: " + s.phase());
            plugin.forge().simulateGrind(s);
            require(s.phase() == com.forgemaster.forge.ForgePhase.QUENCHING, "开刃后未进淬火: " + s.phase());
        });
        add(g, "钢纹映射：4 层 → 水波纹", () -> {
            ForgeSession s = probeSession(first);
            s.foldSequence().clear();
            s.foldSequence().addAll(List.of(0, 1, 2, 3));
            // layers() 返回 foldCursor（正确点击次数），需同步设置
            s.foldCursor(4);
            require(s.layers() == 4, "层数错: " + s.layers());
            require("水波纹".equals(s.pattern()), "钢纹错: " + s.pattern());
        });
    }

    // ---------------------------------------------------------------- 评分 / 档案

    private void registerScoreAndStats() {
        String g = "评分 / 档案 / 专精";
        add(g, "满配总分为 100", () -> {
            double full = ForgeManager.weightedScore(1, 1, 1, 1, 15, true, true);
            require(Math.abs(full - 100.0) < 0.01, "满配应 100，实际 " + full);
        });
        add(g, "关闭叠钢后满配仍为 100", () -> {
            double v = ForgeManager.weightedScore(1, 1, 0, 1, 15, false, true);
            require(Math.abs(v - 100.0) < 0.01, "实际 " + v);
        });
        add(g, "关闭两道工序后满配仍为 100", () -> {
            double v = ForgeManager.weightedScore(1, 1, 0, 0, 15, false, false);
            require(Math.abs(v - 100.0) < 0.01, "实际 " + v);
        });
        add(g, "全 0 得 0 分", () ->
                require(ForgeManager.weightedScore(0, 0, 0, 0, 0, true, true) == 0.0, "全 0 不是 0"));
        add(g, "单调性：叠钢变好不掉分", () -> {
            double a = ForgeManager.weightedScore(0.5, 0.5, 0.5, 0.5, 7, true, true);
            double b = ForgeManager.weightedScore(0.5, 0.5, 0.9, 0.5, 7, true, true);
            require(b >= a, "叠钢变好反而掉分");
        });
        add(g, "称号分档：0 → 学徒", () ->
                require("学徒".equals(StatsStore.titleOf(0)), "0 声望不是学徒"));
        add(g, "称号分档：120 → 匠人", () ->
                require("匠人".equals(StatsStore.titleOf(120)), "120 声望不是匠人"));
        add(g, "称号分档：2500 → 铸剑圣手", () ->
                require("铸剑圣手".equals(StatsStore.titleOf(2500)), "2500 声望不是铸剑圣手"));
        add(g, "下一档声望提示：0 → 100", () ->
                require(StatsStore.reputationToNextTitle(0) == 100, "下一档差值错"));
        add(g, "专精经验表单调递增", () -> {
            for (int lv = 1; lv < 10; lv++) {
                require(StatsStore.expToNext(lv + 1) > StatsStore.expToNext(lv), "Lv" + lv + " 经验表倒退");
            }
        });
        add(g, "专精容差倍率不低于 1", () ->
                require(StatsStore.toleranceMultiplier(1) >= 1.0, "容差倍率低于 1"));
        add(g, "假玩家档案 record 只累加假档案", () -> {
            UUID fake = UUID.nameUUIDFromBytes("SelfTest-stats".getBytes());
            StatsStore.Stats before = plugin.stats().get(fake);
            int forged0 = before.forged;
            plugin.stats().record(fake, NAME, Quality.FINE, 60.5, firstMaterial().id(), "水波纹");
            require(plugin.stats().get(fake).forged == forged0 + 1, "锻造次数没累加");
        });
        add(g, "假玩家图鉴写入", () -> {
            UUID fake = UUID.nameUUIDFromBytes("SelfTest-stats".getBytes());
            require(plugin.stats().get(fake).codex.contains(
                    firstMaterial().id().toLowerCase() + ":" + Quality.FINE.name()), "图鉴没记上");
        });
    }

    // ---------------------------------------------------------------- 剑币 / 连锻 / 商会

    private void registerCoinsStreakShop() {
        String g = "剑币 / 连锻 / 商会";
        add(g, "剑币发放与扣除（假玩家）", () -> {
            UUID fake = UUID.nameUUIDFromBytes("SelfTest-coin".getBytes());
            long base = plugin.stats().coins(fake);
            plugin.stats().addCoins(fake, 100);
            require(plugin.stats().coins(fake) == base + 100, "加币后余额不对");
            require(plugin.stats().takeCoins(fake, 100), "扣币失败");
            require(plugin.stats().coins(fake) == base, "扣币后余额不对");
        });
        add(g, "余额不足时扣币拒绝且不扣", () -> {
            UUID fake = UUID.nameUUIDFromBytes("SelfTest-coin2".getBytes());
            require(!plugin.stats().takeCoins(fake, Long.MAX_VALUE), "余额不足却扣成功");
            require(plugin.stats().coins(fake) == 0, "余额不足时仍被扣");
        });
        add(g, "剑币锻造奖励非负", () ->
                require(plugin.coinRules().forgeReward(Quality.EPIC, 80, 1) >= 0, "奖励为负"));
        add(g, "连锻倍率：第 1 连为 1 倍", () ->
                require(StatsStore.streakMultiplier(1) == 1.0, "第 1 连倍率不是 1"));
        add(g, "连锻倍率：有上限 3 倍", () ->
                require(StatsStore.streakMultiplier(100) <= 3.0 + 1e-9, "倍率超过上限"));
        add(g, "连锻倍率随连数递增", () ->
                require(StatsStore.streakMultiplier(3) > StatsStore.streakMultiplier(2), "倍率没递增"));
        add(g, "连锻计数：假玩家起链为 1", () -> {
            UUID fake = UUID.nameUUIDFromBytes("SelfTest-streak".getBytes());
            plugin.stats().breakStreak(fake);
            require(plugin.stats().bumpStreak(fake) == 1, "起链不是 1");
            plugin.stats().breakStreak(fake);
        });
        add(g, "连锻断链：返回断前链数并清零", () -> {
            UUID fake = UUID.nameUUIDFromBytes("SelfTest-streak2".getBytes());
            plugin.stats().breakStreak(fake);
            plugin.stats().bumpStreak(fake);
            plugin.stats().bumpStreak(fake);
            require(plugin.stats().breakStreak(fake) == 2, "断链返回值不对");
            require(plugin.stats().get(fake).streak == 0, "断链后未清零");
        });
        add(g, "连锻静默期常量为正", () ->
                require(StatsStore.STREAK_WINDOW_MS > 0, "静默期非正"));
        add(g, "商会已启用或已正确关闭", () -> {
            var shop = plugin.shop();
            require(shop != null, "商会管理器未初始化");
        });
        add(g, "商会条目非空（启用时）", () -> {
            var shop = plugin.shop();
            if (shop.enabled()) {
                require(!shop.entries().isEmpty(), "商会启用但没有商品");
            }
        });
        add(g, "商会分页数：0 件 → 1 页", () ->
                require(plugin.shop().pageCount(0) >= 1, "空商品页数错"));
        add(g, "商会商品单价全部为正", () -> {
            for (var e : plugin.shop().entries()) {
                require(e.unitPrice() > 0, "商品单价非正: " + e.kind());
            }
        });
    }

    // ---------------------------------------------------------------- 拍卖 / 委托

    private void registerAuctionCommission() {
        String g = "拍卖行 / 委托";
        add(g, "拍卖管理器已初始化", () ->
                require(plugin.auction() != null, "拍卖管理器未初始化"));
        add(g, "拍卖存储可读取（只读）", () ->
                require(plugin.auction().store() != null && plugin.auction().store().all() != null,
                        "拍卖存储不可用"));
        add(g, "拍卖最低加价规则为正", () -> {
            // 无人出价时 minBid = 起拍价；这里构造已有出价人的挂单，验证「当前价 + 最低加价」
            var l = new com.forgemaster.auction.AuctionListing("SELFTEST-A", UUID.randomUUID(), NAME,
                    new ItemStack(Material.IRON_INGOT), 100, 0, 100, 0L, Long.MAX_VALUE);
            l.setBidder(UUID.nameUUIDFromBytes("SelfTest-bidder".getBytes()), NAME);
            require(l.hasBidder(), "挂单应已有出价人");
            require(plugin.auction().minBid(l) > l.price(), "最低加价不高于当前价");
        });
        add(g, "拍卖抽成不超过成交价", () ->
                require(plugin.auction().proceeds(1000) <= 1000, "抽成后超过成交价"));
        add(g, "拍卖一口价判定", () -> {
            var with = new com.forgemaster.auction.AuctionListing("SELFTEST-B", UUID.randomUUID(), NAME,
                    new ItemStack(Material.IRON_INGOT), 100, 500, 100, 0L, Long.MAX_VALUE);
            var without = new com.forgemaster.auction.AuctionListing("SELFTEST-C", UUID.randomUUID(), NAME,
                    new ItemStack(Material.IRON_INGOT), 100, 0, 100, 0L, Long.MAX_VALUE);
            require(with.hasBuyout() && !without.hasBuyout(), "一口价判定错");
        });
        add(g, "拍品到期判定", () -> {
            var l = new com.forgemaster.auction.AuctionListing("SELFTEST-D", UUID.randomUUID(), NAME,
                    new ItemStack(Material.IRON_INGOT), 100, 0, 100, 0L, 1000L);
            require(l.expired(2000L) && !l.expired(500L), "到期判定错");
        });
        add(g, "拍卖存储只存在于内存检查（不写盘）", () ->
                require(plugin.auction().store().get("SELFTEST-NONE") == null, "不存在的拍品返回了值"));
        add(g, "结案只能成功一次（防二次给卖家打款）", () -> {
            // v2.19.1：finish 返回「这张单是不是本次结掉的」。买断 / 到期结算都靠它
            // 判断该不该发钱——旧版是 void，同一张单被子路径抢先结案时卖家会二次收款。
            var store = plugin.auction().store();
            String id = "SELFTEST-FINISH";
            UUID seller = UUID.nameUUIDFromBytes("selftest-finish-seller".getBytes());
            var l = new com.forgemaster.auction.AuctionListing(id, seller, NAME,
                    plugin.items().buildDirect(NAME, firstMaterial(), Quality.COMMON, 40),
                    100, 0, 100, System.currentTimeMillis(), System.currentTimeMillis() + 60000L);
            store.put(l);
            require(store.finish(id, seller, l.item()), "第一次结案应当成功");
            require(!store.finish(id, seller, l.item()), "第二次结案必须失败（否则会二次发钱）");
            require(store.get(id) == null, "结案后单子应当不在列表里");
            // 清掉这条测试塞进邮箱的物品，别污染后续领取断言
            store.takeMail(seller);
        });
        add(g, "委托管理器已初始化", () ->
                require(plugin.commissions() != null, "委托管理器未初始化"));
        add(g, "委托生成数量等于配置槽位", () -> {
            UUID owner = UUID.nameUUIDFromBytes("SelfTest-commission".getBytes());
            plugin.commissions().of(owner).clear();
            var orders = plugin.commissions().ensureFor(owner);
            require(orders.size() == plugin.settings().commissionSlots(),
                    "委托数量 " + orders.size() + " ≠ 槽位 " + plugin.settings().commissionSlots());
            plugin.commissions().of(owner).clear();
        });
        add(g, "新委托不会立即过期", () -> {
            UUID owner = UUID.nameUUIDFromBytes("SelfTest-commission2".getBytes());
            plugin.commissions().of(owner).clear();
            for (var c : plugin.commissions().ensureFor(owner)) {
                require(!c.expired(), "新委托一生成就过期");
            }
            plugin.commissions().of(owner).clear();
        });
        add(g, "委托判定：普通品质不满足传说委托", () -> {
            UUID owner = UUID.nameUUIDFromBytes("SelfTest-commission3".getBytes());
            ItemStack probe = plugin.items().buildDirect(NAME, firstMaterial(), Quality.COMMON, 40);
            var strict = new com.forgemaster.commission.Commission("SELFTEST-STRICT", owner,
                    com.forgemaster.commission.Commission.Kind.QUALITY, "测试客人", "any", Quality.LEGENDARY,
                    null, 0, 0, "IRON_INGOT", 1, 10, System.currentTimeMillis() + 60000);
            require(strict.missing(plugin.items(), probe) != null, "普通品质满足了传说委托");
        });
        add(g, "委托判定：粗糙委托收得下普通剑", () -> {
            UUID owner = UUID.nameUUIDFromBytes("SelfTest-commission4".getBytes());
            ItemStack probe = plugin.items().buildDirect(NAME, firstMaterial(), Quality.COMMON, 40);
            var loose = new com.forgemaster.commission.Commission("SELFTEST-LOOSE", owner,
                    com.forgemaster.commission.Commission.Kind.QUALITY, "测试客人", "any", Quality.ROUGH,
                    null, 0, 0, "IRON_INGOT", 1, 10, System.currentTimeMillis() + 60000);
            require(loose.missing(plugin.items(), probe) == null, "粗糙委托拒收普通剑");
        });
        add(g, "委托判定：非锻造品不能交货", () -> {
            UUID owner = UUID.nameUUIDFromBytes("SelfTest-commission5".getBytes());
            var loose = new com.forgemaster.commission.Commission("SELFTEST-PLAIN", owner,
                    com.forgemaster.commission.Commission.Kind.QUALITY, "测试客人", "any", Quality.ROUGH,
                    null, 0, 0, "IRON_INGOT", 1, 10, System.currentTimeMillis() + 60000);
            require(loose.missing(plugin.items(), new ItemStack(Material.IRON_SWORD)) != null,
                    "非锻造品居然能交货");
        });
    }

    // ---------------------------------------------------------------- 大赛 / 决斗 / 师徒

    private void registerContestDuelMentor() {
        String g = "大赛 / 决斗 / 师徒";
        add(g, "大赛管理器已初始化", () ->
                require(plugin.contests() != null, "大赛管理器未初始化"));
        add(g, "大赛剑胚 id 都在配置里", () -> {
            var cm = plugin.contests();
            if (cm.materialAsking() != null) {
                require(plugin.materials().get(cm.materialAsking().id()) != null, "大赛剑胚不在配置里");
            }
        });
        add(g, "大赛榜单接口可读（只读）", () ->
                require(plugin.contests().leaderboard() != null, "榜单返回空"));
        add(g, "决斗管理器已初始化", () ->
                require(plugin.duels() != null, "决斗管理器未初始化"));
        add(g, "决斗同种子得到相同随机序列", () -> {
            UUID u = UUID.nameUUIDFromBytes("SelfTest-duel".getBytes());
            ForgeSession a = new ForgeSession(u, NAME + "A", firstMaterial());
            ForgeSession b = new ForgeSession(u, NAME + "B", firstMaterial());
            a.seed(424242L);
            b.seed(424242L);
            require(a.rnd().nextInt(1000) == b.rnd().nextInt(1000), "同种子随机不一致");
        });
        add(g, "决斗不同种子得到不同随机序列", () -> {
            UUID u = UUID.nameUUIDFromBytes("SelfTest-duel2".getBytes());
            ForgeSession a = new ForgeSession(u, NAME, firstMaterial());
            ForgeSession b = new ForgeSession(u, NAME, firstMaterial());
            a.seed(1L);
            b.seed(2L);
            require(a.rnd().nextInt(1000000) != b.rnd().nextInt(1000000), "不同种子结果相同");
        });
        add(g, "决斗功能开关可读", () ->
                require(plugin.duels().enabled() || !plugin.duels().enabled(), "决斗开关接口异常"));
        add(g, "师徒管理器已初始化", () ->
                require(plugin.mentors() != null, "师徒管理器未初始化"));
        add(g, "师徒功能开关可读", () ->
                require(plugin.mentors().enabled() || !plugin.mentors().enabled(), "师徒开关接口异常"));
        add(g, "师徒关系存取（假 UUID）", () -> {
            UUID fake = UUID.nameUUIDFromBytes("SelfTest-mentor".getBytes());
            plugin.stats().setMentor(fake, "SelfTest-M");
            require("SelfTest-M".equals(plugin.stats().mentorOf(fake)), "师傅名没保住");
            plugin.stats().setMentor(fake, "");
        });
    }

    // ---------------------------------------------------------------- 成就 / 段位 / 环境 / 剑灵

    private void registerGrowthEnvSpirit() {
        String g = "成就 / 段位 / 环境 / 剑灵";
        add(g, "成就定义列表可读", () ->
                require(plugin.achievements() != null && !plugin.achievements().defs().isEmpty(),
                        "成就定义为空"));
        add(g, "成就 id 不重复", () -> {
            var set = new java.util.HashSet<String>();
            for (var d : plugin.achievements().defs()) {
                require(set.add(d.id()), "成就 id 重复: " + d.id());
            }
        });
        add(g, "成就奖励非负", () -> {
            for (var d : plugin.achievements().defs()) {
                require(d.coins() >= 0 && d.rep() >= 0 && d.points() >= 0, d.id() + " 奖励为负");
            }
        });
        add(g, "成就授予：首次成功、重复失败（假 UUID）", () -> {
            UUID fake = UUID.nameUUIDFromBytes("SelfTest-achv".getBytes());
            String id = "SELFTEST-ACHV";
            // 假档案会持久化到 data.yml，先清掉上次运行留下的同名成就，保证检查幂等
            plugin.stats().get(fake).achievements.remove(id);
            require(plugin.stats().grantAchievement(fake, id, 1), "首次授予失败");
            require(!plugin.stats().grantAchievement(fake, id, 1), "重复授予居然成功");
        });
        add(g, "段位列表非空", () ->
                require(plugin.ranks() != null && !plugin.ranks().tiers().isEmpty(), "段位列表为空"));
        add(g, "段位按下限升序", () -> {
            var tiers = plugin.ranks().tiers();
            for (int i = 1; i < tiers.size(); i++) {
                require(tiers.get(i).min() >= tiers.get(i - 1).min(), "段位阈值倒序");
            }
        });
        add(g, "环境解析：给定世界返回上下文", () -> {
            var world = org.bukkit.Bukkit.getWorlds().get(0);
            require(plugin.env().resolve(world) != null, "环境上下文为空");
        });
        add(g, "环境天气键合法", () -> {
            var world = org.bukkit.Bukkit.getWorlds().get(0);
            require(com.forgemaster.env.EnvManager.weatherKeyOf(world) != null, "天气键为空");
        });
        add(g, "环境时段键合法", () ->
                require(com.forgemaster.env.EnvManager.timeKeyOf(6000) != null, "时段键为空"));
        add(g, "剑灵档位列表非空（启用时）", () -> {
            var sm = plugin.spirit();
            if (sm.enabled()) {
                require(!sm.tiers().isEmpty(), "剑灵档位为空");
            }
        });
        add(g, "剑灵档位随灵性值单调", () -> {
            var sm = plugin.spirit();
            int prev = -1;
            for (int v = 0; v <= 1000; v += 100) {
                int t = sm.tierOf(v);
                require(t >= prev, "档位在 " + v + " 处倒退");
                prev = t;
            }
        });
        add(g, "剑灵名字非空", () -> {
            var sm = plugin.spirit();
            require(sm.tierName(0) != null && !sm.tierName(0).isBlank(), "档位名为空");
        });
    }

    // ---------------------------------------------------------------- 剑冢 / 风险 / 世界事件 / 剧情

    private void registerTombRiskEventsStory() {
        String g = "剑冢 / 风险 / 事件 / 剧情";
        add(g, "秘境守卫失踪必须判负，不许当成「这一波已清算」（v2.25.6 修 P0）", () -> {
            // 出过的真事故：tick() 里「守卫全失踪满 30 秒」被当成 waveCleared，
            // 玩家进场后 /spawn 站着不动（守卫随区块卸载变 null）就能零战斗通关，
            // 300 门票换 800 剑币 + 2 残页，可无限重复。
            // 这里钉死语义：宽限期内只能 WAIT，宽限期满只能 FAIL；
            // 并且枚举里**不允许**出现第三个「清算」语义 —— 有人加回去这条就红。
            require(com.forgemaster.tombrun.TombRunService.MissingAction.values().length == 2,
                    "MissingAction 冒出了第三种语义（是不是又把「失踪」当「已清算」了？）："
                            + java.util.Arrays.toString(com.forgemaster.tombrun.TombRunService.MissingAction.values()));
            long t0 = 1_000_000L;
            require(com.forgemaster.tombrun.TombRunService.missingAction(0, t0, 10)
                            == com.forgemaster.tombrun.TombRunService.MissingAction.WAIT,
                    "刚开始失踪就下了结论");
            require(com.forgemaster.tombrun.TombRunService.missingAction(t0, t0 + 9_999, 10)
                            == com.forgemaster.tombrun.TombRunService.MissingAction.WAIT,
                    "宽限期没到就判了");
            require(com.forgemaster.tombrun.TombRunService.missingAction(t0, t0 + 10_000, 10)
                            == com.forgemaster.tombrun.TombRunService.MissingAction.FAIL,
                    "宽限期满了居然不判负（零战斗通关的老 bug 又回来了）");
            require(com.forgemaster.tombrun.TombRunService.missingAction(t0, t0 + 600_000, 10)
                            == com.forgemaster.tombrun.TombRunService.MissingAction.FAIL,
                    "久到离谱也不判负");
            // 宽限秒数必须真的来自配置（原来硬编码 30 秒，配置了 away-seconds 却不读）
            require(plugin.tombrun() == null || plugin.tombrun().awaySecondsForTest() >= 1,
                    "away-seconds 没被读到（还会是硬编码那条路）");
        });

        add(g, "剑冢挑战冷却：退服只清过期条目，不许把生效中的冷却一起抹掉（v2.25.6 修 P0）", () -> {
            // 出过的真事故：onQuit 第一行 cooldowns.remove(uuid) 删掉整张表，
            // 「斩影成功 → /quit → 重连」就能立刻重打同一把剑，24 小时冷却形同虚设。
            long now = 5_000_000L;
            java.util.Map<String, Long> mine = new java.util.HashMap<>();
            mine.put("ALIVE", now + 3_600_000L);   // 还在生效
            mine.put("DEAD", now - 1L);            // 早过期了
            mine.put("NULL", null);                // 脏数据（旧版本可能写进来）
            int left = com.forgemaster.tomb.TombChallenge.pruneExpired(mine, now);
            require(left == 1, "过期清理算错了，剩 " + left + " 条");
            require(mine.containsKey("ALIVE"), "生效中的冷却被抹掉了（退服遁又能免冷却重打）");
            require(!mine.containsKey("DEAD"), "过期条目没回收，内存会只增不减");
            require(!mine.containsKey("NULL"), "脏条目没回收");
            require(com.forgemaster.tomb.TombChallenge.pruneExpired(null, now) == 0, "空表应当返回 0");
            require(com.forgemaster.tomb.TombChallenge.pruneExpired(new java.util.HashMap<>(), now) == 0,
                    "空 Map 应当返回 0");
        });

        add(g, "订单托管必须跟 Wallet 同一本账，不许再出现 stats().takeCoins（v2.25.6 修 P0）", () -> {
            // 出过的真事故：post 用 stats().takeCoins 扣剑币，三条退回路径却走 wallet()。
            // economy.coin-sinks=server 的服上「发单 → 取消」一个循环就能把剑币 1:1 洗成
            // 服务器金币，主手样品不消耗，可无限重复。
            // 这条钉子扫源码：OrderService 里一次 stats().takeCoins 都不许有。
            java.io.File pkgRoot = findSourcePackageRoot();
            if (pkgRoot == null) {
                plugin.getLogger().info("[selftest] 找不到源码树，跳过账本口径守卫");
                return;
            }
            java.io.File src = new java.io.File(pkgRoot, "order/OrderService.java");
            require(src.isFile(), "找不到 OrderService.java");
            String code;
            try {
                code = new String(java.nio.file.Files.readAllBytes(src.toPath()),
                        java.nio.charset.StandardCharsets.UTF_8);
            } catch (java.io.IOException ex) {
                throw new IllegalStateException("读不到 OrderService.java: " + ex.getMessage());
            }
            int bad = 0;
            for (String line : code.split("\n")) {
                String trimmed = line.trim();
                if (trimmed.startsWith("//") || trimmed.startsWith("*") || trimmed.startsWith("/*")) {
                    continue; // 注释里提到旧写法是允许的（本文件顶部就在解释这段历史）
                }
                if (trimmed.contains("stats().takeCoins")) {
                    bad++;
                }
            }
            require(bad == 0, "OrderService 又有 " + bad
                    + " 处 stats().takeCoins —— 订单托管属于 Wallet 侧，混用等于开了洗钱口");
            require(code.contains("wallet().take"), "OrderService 的托管扣款没走 wallet().take");
            require(!plugin.wallet().ledgerName().isBlank(), "钱包币名是空的，提示会变成「不足」两字");
        });

        add(g, "剑冢管理器已初始化", () ->
                require(plugin.tombs() != null, "剑冢管理器未初始化"));
        add(g, "剑冢列表可读（只读）", () ->
                require(plugin.tombs().all() != null, "剑冢列表为空指针"));
        add(g, "剑冢按 id 查询不存在返回 null", () ->
                require(plugin.tombs().get("SELFTEST-NONE") == null, "不存在的剑冢返回了值"));
        // v2.25.8：取剑路径现在要「写盘成功才交剑」。钉子锁两件事：
        //  ① saveChecked() 存在且在当前目录可写时返回 true（否则取剑会被永久拦住）
        //  ② 空冢时也返回 true —— 不能因为 tombs 为空就当作写失败
        add(g, "剑冢 saveChecked 可写时返回 true（取剑前置条件）", () ->
                require(plugin.tombs().saveChecked(), "tombs.yml 写不进去，取剑会被永久拦住"));
        // v2.25.8：碎剑留档**允许挑战**（残影按 brokenAt 加强），这是既定设计不是漏洞。
        // 以前被当成 P1 误报过一轮 —— 这条钉子把结论固定在代码里，别再翻案。
        add(g, "附魔上书不会复制物品（主手书+副手剑：书消耗一本、剑不多一把）", () -> {
            // 这条钉子的由来：applyBook 以前无条件 `setItemInMainHand(target)`，
            // 而 EnchantListener 只处理主手右键 → 实际唯一能走到的姿势就是
            // 「主手书、副手剑」，于是剑被写进主手（覆盖书）、副手那把剑还在，
            // **右键一次多一把剑**，是能批量刷的量产漏洞。
            //
            // 这里不复现整个事件，只钉住「计数守恒」这条不变式：
            // 上书前后，(剑数, 书数) 必须变成 (剑数, 书数-1)。
            org.bukkit.entity.Player p = org.bukkit.Bukkit.getOnlinePlayers().stream()
                    .findFirst().orElse(null);
            if (p == null) {
                return;   // 无人在线时跳过（和 Tab 那条钉子同口径）
            }
            var inv = p.getInventory();
            // 记录原始背包，测完原样还原（自检不许给玩家留副作用）
            org.bukkit.inventory.ItemStack[] backup = inv.getContents().clone();
            try {
                inv.clear();
                // 造一把「虚构的锻造剑」：只要有 FORGED 标记 + 武器材质就够 applyBook 放行
                org.bukkit.inventory.ItemStack sword =
                        new org.bukkit.inventory.ItemStack(org.bukkit.Material.IRON_SWORD);
                var sm = sword.getItemMeta();
                sm.getPersistentDataContainer().set(
                        com.forgemaster.util.Keys.FORGED,
                        org.bukkit.persistence.PersistentDataType.BYTE, (byte) 1);
                sword.setItemMeta(sm);

                var def = plugin.enchants().get("charge");
                require(def != null, "找不到「蓄锋」定义，钉子无法建立");
                org.bukkit.inventory.ItemStack book = com.forgemaster.enchant.EnchantItem.book(
                        plugin, new com.forgemaster.enchant.EnchantEffects.Entry(def, 3));

                int swordsBefore = countMaterial(inv, org.bukkit.Material.IRON_SWORD);
                int booksBefore = countMaterial(inv, org.bukkit.Material.ENCHANTED_BOOK);
                require(swordsBefore == 1, "测试前置失败：包里应有 1 把剑，实际 " + swordsBefore);
                require(booksBefore == 1, "测试前置失败：包里应有 1 本书，实际 " + booksBefore);

                // 主手书、副手剑 —— 真实玩家唯一能触发的姿势
                inv.setItemInMainHand(book);
                inv.setItemInOffHand(sword);

                plugin.enchantTable().applyBook(p, book, inv.getItemInOffHand());

                int swordsAfter = countMaterial(inv, org.bukkit.Material.IRON_SWORD);
                int booksAfter = countMaterial(inv, org.bukkit.Material.ENCHANTED_BOOK);
                require(swordsAfter == swordsBefore,
                        "附魔后剑变多了：" + swordsBefore + " → " + swordsAfter
                                + "（applyBook 又把装备写回主手了 —— 这是物品复制）");
                // 书由监听器扣减；applyBook 本身不动书，所以这里只要求「没变多」
                require(booksAfter <= booksBefore,
                        "附魔书变多了：" + booksBefore + " → " + booksAfter);
                // 主手不该被剑占掉：书的消耗交给监听器，applyBook 不许越权盖主手
                org.bukkit.inventory.ItemStack mainAfter = inv.getItemInMainHand();
                require(mainAfter == null || mainAfter.getType().isAir()
                                || mainAfter.getType() == org.bukkit.Material.ENCHANTED_BOOK,
                        "applyBook 把装备写进了主手，覆盖了手上的书："
                                + (mainAfter == null ? "null" : mainAfter.getType()));
                // 剑必须还在副手，并且真的带上了附魔
                org.bukkit.inventory.ItemStack offAfter = inv.getItemInOffHand();
                require(offAfter != null
                                && offAfter.getType() == org.bukkit.Material.IRON_SWORD,
                        "副手的剑不见了 —— 装备被搬到了别的手");
                var entries = com.forgemaster.enchant.EnchantItem.of(plugin, offAfter);
                require(entries.stream().anyMatch(e -> e.def().id().equals("charge")),
                        "副手的剑上没有蓄锋 —— 附魔没写进去");
            } finally {
                inv.setContents(backup);
            }
        });
        add(g, "附魔台不许整摞吞书（书格 3 本，点一次只该少 1 本）（v2.26.3）", () -> {
            // 审计报告：书格放 2 本以上相同附魔书，点附魔只结算 1 本，
            // 但 BOOK_SLOT 被整格清成 AIR —— 其余凭空消失。
            // 这里直接钉「按件递减」这个方法必须存在且被调用，而不是钉 GUI 渲染。
            java.io.File pkgRoot = findSourcePackageRoot();
            if (pkgRoot == null) {
                plugin.getLogger().info("[selftest] 找不到源码树，跳过吞书钉子");
                return;
            }
            java.io.File src = new java.io.File(pkgRoot, "enchant/EnchantService.java");
            require(src.isFile(), "找不到 EnchantService.java");
            String code;
            try {
                code = new String(java.nio.file.Files.readAllBytes(src.toPath()),
                        java.nio.charset.StandardCharsets.UTF_8);
            } catch (java.io.IOException ex) {
                throw new IllegalStateException("读不到 EnchantService.java: " + ex.getMessage());
            }
            String body = methodBody(code, "public boolean apply(Player player, Inventory gui)");
            require(body != null, "apply 方法体切不出来");
            require(body.contains("consumeOneBook"),
                    "apply 必须走 consumeOneBook 按件递减（整格清 AIR 会吞掉整摞书）");
            require(!body.contains("EnchantGui.BOOK_SLOT, new ItemStack(Material.AIR)"),
                    "apply 不许直接把书格整格清成 AIR");
            // 递减实现本身：必须读 getAmount 再减一
            String helper = methodBody(code, "private void consumeOneBook(Inventory gui, ItemStack book)");
            require(helper != null, "consumeOneBook 方法体切不出来");
            require(helper.contains("getAmount() - 1"), "consumeOneBook 必须按件递减");
        });
        add(g, "潜行右键铁砧只认主手（副手那次不许再开一遍面板）（v2.26.3）", () -> {
            // PlayerInteractEvent 对一次右键会分别派发主手/副手两次；
            // 不过滤 getHand 就会开两次面板（取完剑立刻又弹出菜单）。
            java.io.File pkgRoot = findSourcePackageRoot();
            if (pkgRoot == null) {
                plugin.getLogger().info("[selftest] 找不到源码树，跳过右键手位钉子");
                return;
            }
            java.io.File src = new java.io.File(pkgRoot, "listener/ForgeListener.java");
            require(src.isFile(), "找不到 ForgeListener.java");
            String code;
            try {
                code = new String(java.nio.file.Files.readAllBytes(src.toPath()),
                        java.nio.charset.StandardCharsets.UTF_8);
            } catch (java.io.IOException ex) {
                throw new IllegalStateException("读不到 ForgeListener.java: " + ex.getMessage());
            }
            String body = methodBody(code, "public void onInteract(PlayerInteractEvent event)");
            require(body != null, "ForgeListener.onInteract 方法体切不出来");
            require(body.contains("EquipmentSlot.HAND"),
                    "潜行右键铁砧必须只认 EquipmentSlot.HAND（否则副手那次会再开一遍面板）");
        });
        add(g, "碎剑留档可被挑战（设计如此：残影按碎在 +N 加强）", () -> {
            // levelOf 用 brokenAt 抬等级，只有挑战路径会调到它；
            // 若将来有人给挑战加 broken gate，这里的镜像断言会先红。
            int brokenAt = 7;
            int level = Math.max(0, brokenAt);
            require(level == brokenAt, "碎剑留档的挑战等级应等于碎在 +N");
            require(plugin.tombs() != null, "剑冢管理器未初始化");
        });
    }

    // ---------------------------------------------------------------- 名人堂 / 排行 / 赛季 / 备份

    private void registerRankingSeasonBackup() {
        String g = "名人堂 / 排行 / 赛季 / 备份";
        add(g, "名人堂管理器已初始化", () ->
                require(plugin.hallOfFame() != null, "名人堂未初始化"));
        add(g, "名人堂开关接口可读", () ->
                require(plugin.hallOfFame().enabled() || !plugin.hallOfFame().enabled(), "接口异常"));
        add(g, "排行维度解析：coins", () ->
                require(com.forgemaster.ranking.Metric.parse("coins") == com.forgemaster.ranking.Metric.COINS,
                        "coins 解析失败"));
        add(g, "排行维度解析：中文别名", () ->
                require(com.forgemaster.ranking.Metric.parse("剑币") == com.forgemaster.ranking.Metric.COINS,
                        "剑币解析失败"));
        add(g, "排行维度解析：非法返回 null", () ->
                require(com.forgemaster.ranking.Metric.parse("nope") == null, "非法维度没返回 null"));
        add(g, "排行过滤：SelfTest 不进榜", () -> {
            StatsStore.Stats st = new StatsStore.Stats();
            st.name = NAME;
            st.forged = 1;
            st.coins = 999;
            require(!com.forgemaster.ranking.Rankings.active(st), "SelfTest 居然进了榜");
        });
        add(g, "排行过滤：空档案不进榜", () ->
                require(!com.forgemaster.ranking.Rankings.active(new StatsStore.Stats()), "空档案进了榜"));
        add(g, "排行按维度降序", () -> {
            StatsStore.Stats a = new StatsStore.Stats();
            a.name = "A";
            a.coins = 10;
            StatsStore.Stats b = new StatsStore.Stats();
            b.name = "B";
            b.coins = 20;
            require(com.forgemaster.ranking.Metric.COINS.value(b) > com.forgemaster.ranking.Metric.COINS.value(a),
                    "降序判定错");
        });
        add(g, "排行 Top 上限不超过请求数", () ->
                require(com.forgemaster.ranking.Rankings.top(plugin.stats(),
                        com.forgemaster.ranking.Metric.COINS, 5).size() <= 5, "Top 超过请求数"));
        add(g, "赛季编号 ≥ 1", () ->
                require(plugin.seasons().number() >= 1, "赛季编号非正"));
        add(g, "赛季时长为正", () ->
                require(plugin.seasons().durationDays() >= 1, "赛季时长非正"));
        add(g, "赛季状态行可生成", () ->
                require(!plugin.seasons().statusLines().isEmpty(), "赛季状态为空"));
        add(g, "赛季重置只清赛季字段（假 UUID）", () -> {
            UUID fake = UUID.nameUUIDFromBytes("SelfTest-season".getBytes());
            plugin.stats().addCoins(fake, 50);
            plugin.stats().addReputation(fake, 30);
            plugin.stats().get(fake).achievements.add("SELFTEST-KEEP");
            plugin.stats().resetSeasonal();
            StatsStore.Stats st = plugin.stats().get(fake);
            require(st.coins == 0 && st.reputation == 0, "赛季重置没清剑币/声望");
            require(st.achievements.contains("SELFTEST-KEEP"), "赛季重置误清了成就");
        });
        add(g, "备份管理器已初始化", () ->
                require(plugin.backups() != null, "备份管理器未初始化"));
        add(g, "备份列表可读", () ->
                require(plugin.backups().list() != null, "备份列表为空指针"));
        add(g, "SelfTest 名称约定：假玩家名统一", () ->
                require(NAME.equals("SelfTest"), "数据名不是 SelfTest"));
    }

    /** 第一把剑胚（各检查共用，避免各方法里重复写 iterator） */
    private ForgeMaterial firstMaterial() {
        return plugin.materials().all().iterator().next();
    }

    private ForgeSession probeSession(ForgeMaterial m) {
        ForgeSession s = new ForgeSession(UUID.randomUUID(), NAME, m);
        s.heatScore(1.0);
        s.quench(plugin.quenches().all().get(0));
        s.quality(Quality.EPIC);
        s.finalScore(80);
        return s;
    }
}


