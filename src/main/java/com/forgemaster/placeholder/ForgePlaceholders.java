package com.forgemaster.placeholder;

import com.forgemaster.ForgingMasterPlugin;
import com.forgemaster.quality.Quality;
import com.forgemaster.stats.StatsStore;
import com.forgemaster.util.Msg;
import org.bukkit.Bukkit;
import org.bukkit.plugin.java.JavaPlugin;

import javax.tools.DiagnosticCollector;
import javax.tools.JavaCompiler;
import javax.tools.JavaFileObject;
import javax.tools.SimpleJavaFileObject;
import javax.tools.StandardJavaFileManager;
import javax.tools.StandardLocation;
import javax.tools.ToolProvider;
import java.io.File;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * PlaceholderAPI 桥（拓展第一档）：<code>%forgemaster_xxx%</code> 占位符。
 *
 * <p><b>不加编译依赖</b>：pom 里没有 PlaceholderAPI，本类全部走反射 + 运行时编译——
 * 检测到 PlaceholderAPI 插件与其 {@code PlaceholderExpansion} 类都存在时，才在
 * {@code plugins/ForgingMaster/papi-cache/} 里把一个继承
 * {@code me.clip.placeholderapi.expansion.PlaceholderExpansion} 的小类现场编译出来
 * （classpath = 本插件 jar + PAPI jar + 服务端 jar），再用反射注册进 PAPI。
 * 任何一步失败（没装 PAPI / 类不可见 / 服务器跑的是无编译器的 JRE）都只留一行日志、
 * 静默不启用，绝不影响插件其它功能。</p>
 *
 * <p><b>占位符与数据源</b>（数据一律读 {@link StatsStore} 已有字段，不做任何扫描；
 * 「forbidden」例外地走 {@code ContractManager#joined(UUID)} 的现成查询——那是
 * contract 模块自己落盘的契约状态，同样不新增扫描）：</p>
 * <ul>
 *   <li>{@code coins}        —— {@code Stats.coins}（当前剑币余额）</li>
 *   <li>{@code reputation}   —— {@code Stats.reputation}（声望）</li>
 *   <li>{@code title}        —— {@link StatsStore#titleOf(int)}（声望对应称号）</li>
 *   <li>{@code top_quality}  —— {@code Stats.bestQuality}（历史最高品质名，中文显示）</li>
 *   <li>{@code top_score}    —— {@code Stats.bestScore}（历史最高分）</li>
 *   <li>{@code strengthen}   —— {@link StatsStore#bestStrengthenOf(UUID)}（历史最高强化等级）</li>
 *   <li>{@code forge_count}  —— {@code Stats.forged}（累计锻造次数）</li>
 *   <li>{@code forbidden}    —— 契约状态：在契 true / 未在契 false（龙税周目未开启时恒 false）</li>
 * </ul>
 *
 * <p>查询用 {@link StatsStore#peek(UUID)}（不建空档案），玩家没档案时返回安全默认值，
 * 不认识的占位符返回 null 交还给 PAPI 处理。</p>
 */
public final class ForgePlaceholders {

    /** 运行时生成的扩展类全名（与下方 EXPANSION_SOURCE 必须一致） */
    private static final String EXPANSION_CLASS = "com.forgemaster.papi.ForgePapiExpansion";

    /**
     * 运行时编译的扩展类源码。
     *
     * <p>故意全 ASCII（避免运行时编译的编码坑）；插件实例经构造器传入，
     * 占位符本体全部委托回本类的 {@link #resolve}，这样 PAPI 相关类型只出现在
     * 这一小段源码里，主插件代码零 PAPI 依赖。</p>
     */
    private static final String EXPANSION_SOURCE = """
            package com.forgemaster.papi;

            import com.forgemaster.ForgingMasterPlugin;
            import com.forgemaster.placeholder.ForgePlaceholders;
            import me.clip.placeholderapi.expansion.PlaceholderExpansion;
            import org.bukkit.OfflinePlayer;

            public final class ForgePapiExpansion extends PlaceholderExpansion {

                private final ForgingMasterPlugin plugin;

                public ForgePapiExpansion(ForgingMasterPlugin plugin) {
                    this.plugin = plugin;
                }

                @Override
                public String getIdentifier() {
                    return "forgemaster";
                }

                @Override
                public String getAuthor() {
                    return ForgePlaceholders.author();
                }

                @Override
                public String getVersion() {
                    return ForgePlaceholders.version(plugin);
                }

                @Override
                public boolean persist() {
                    return true;
                }

                @Override
                public String onRequest(OfflinePlayer player, String params) {
                    return ForgePlaceholders.resolve(plugin,
                            player == null ? null : player.getUniqueId(), params);
                }
            }
            """;

    /** 已挂载的扩展实例（PAPI 在拥有它的插件卸载时会自动反注册，这里只留引用供排查） */
    private static volatile Object registeredExpansion;

    private ForgePlaceholders() {
    }

    // ------------------------------------------------------------ 挂载

    /**
     * 反射探测 + 挂载。任何失败只打一行日志。
     *
     * @param plugin 主插件实例
     */
    public static void register(ForgingMasterPlugin plugin) {
        if (!plugin.getConfig().getBoolean("placeholder.enabled", true)) {
            return; // 配置开关关闭：静默不启用
        }
        if (Bukkit.getPluginManager().getPlugin("PlaceholderAPI") == null) {
            plugin.getLogger().info("未检测到 PlaceholderAPI，%forgemaster_*% 占位符不启用。");
            return;
        }
        ClassLoader loader = plugin.getClass().getClassLoader();
        try {
            Class.forName("me.clip.placeholderapi.expansion.PlaceholderExpansion", false, loader);
        } catch (ClassNotFoundException ex) {
            plugin.getLogger().info("PlaceholderAPI 在但扩展类不可见，占位符桥不启用（检查 softdepend）。");
            return;
        }
        try {
            attach(plugin, loader);
        } catch (Throwable t) {
            plugin.getLogger().warning("PlaceholderAPI 桥挂载失败，占位符不启用：" + t);
        }
    }

    /** 编译 + 注册的实体步骤（异常向上抛，由 {@link #register} 统一兜住） */
    private static void attach(ForgingMasterPlugin plugin, ClassLoader loader) throws Exception {
        JavaCompiler compiler = ToolProvider.getSystemJavaCompiler();
        if (compiler == null) {
            plugin.getLogger().info("当前 Java 运行时没有编译器（JRE），PlaceholderAPI 桥不启用。");
            return;
        }
        // classpath：本插件 jar（编译期要引用 ForgePlaceholders/ForgingMasterPlugin）
        //          + PAPI jar（PlaceholderExpansion 基类）+ 服务端 jar（OfflinePlayer）
        File selfJar = fileOf(plugin);
        File papiJar = papiJarFile();
        File serverJar = fileOf(Bukkit.getServer());
        Path out = plugin.getDataFolder().toPath().resolve("papi-cache");
        Files.createDirectories(out);

        DiagnosticCollector<JavaFileObject> diags = new DiagnosticCollector<>();
        try (StandardJavaFileManager fm = compiler.getStandardFileManager(diags, null, StandardCharsets.UTF_8)) {
            fm.setLocation(StandardLocation.CLASS_OUTPUT, List.of(out.toFile()));
            fm.setLocation(StandardLocation.CLASS_PATH, classesPath(selfJar, papiJar, serverJar));
            JavaFileObject src = new SimpleJavaFileObject(
                    URI.create("string:///" + EXPANSION_CLASS.replace('.', '/') + ".java"),
                    JavaFileObject.Kind.SOURCE) {
                @Override
                public CharSequence getCharContent(boolean ignoreEncoding) {
                    return EXPANSION_SOURCE;
                }
            };
            boolean ok = compiler.getTask(null, fm, diags, null, null, List.of(src)).call();
            if (!ok) {
                throw new IllegalStateException("运行时编译扩展类失败："
                        + diags.getDiagnostics().stream().findFirst().map(Object::toString).orElse("未知错误"));
            }
        }
        Path classFile = out.resolve("com/forgemaster/papi/ForgePapiExpansion.class");
        byte[] bytes = Files.readAllBytes(classFile);
        deleteRecursively(out.toFile());

        Class<?> type = new ClassLoader(loader) {
            Class<?> define() {
                return defineClass(EXPANSION_CLASS, bytes, 0, bytes.length);
            }
        }.define();
        Object expansion = type.getDeclaredConstructor(ForgingMasterPlugin.class).newInstance(plugin);
        Class<?> papiClass = Class.forName("me.clip.placeholderapi.PlaceholderAPI", true, loader);
        papiClass.getMethod("registerExpansion", type).invoke(null, expansion);
        registeredExpansion = expansion;
        plugin.getLogger().info("PlaceholderAPI 桥已挂载：%forgemaster_coins% / reputation / title / "
                + "top_quality / top_score / strengthen / forge_count / forbidden 共 8 个占位符可用。");
    }

    private static List<File> classesPath(File... files) {
        return java.util.Arrays.stream(files)
                .filter(f -> f != null && f.exists())
                .collect(Collectors.toList());
    }

    private static File fileOf(Object obj) {
        try {
            return new File(obj.getClass().getProtectionDomain().getCodeSource().getLocation().toURI());
        } catch (Exception ex) {
            return null;
        }
    }

    /**
     * PAPI 插件 jar 的位置：新版 paper-api 把 {@code JavaPlugin#getFile()} 收成了 protected，
     * 这里反射取（拿不到就返回 null，编译期它会从 classpath 里缺这一个 jar，
     * 扩展类编译会失败并只留一行日志，符合「挂不上就静默」的约定）。
     */
    private static File papiJarFile() {
        try {
            JavaPlugin papi = (JavaPlugin) Bukkit.getPluginManager().getPlugin("PlaceholderAPI");
            if (papi == null) {
                return null;
            }
            java.lang.reflect.Method m = JavaPlugin.class.getDeclaredMethod("getFile");
            m.setAccessible(true);
            return (File) m.invoke(papi);
        } catch (Throwable t) {
            return null;
        }
    }

    private static void deleteRecursively(File f) {
        File[] children = f.listFiles();
        if (children != null) {
            for (File c : children) {
                deleteRecursively(c);
            }
        }
        if (!f.delete()) {
            f.deleteOnExit();
        }
    }

    // ------------------------------------------------------------ 生成类用的静态小助手

    static String author() {
        return "YG";
    }

    static String version(ForgingMasterPlugin plugin) {
        return plugin.getDescription().getVersion();
    }

    // ------------------------------------------------------------ 占位符本体

    /**
     * 占位符解析（PAPI 扩展类委托回来）。
     *
     * @param plugin 主插件
     * @param uuid   查询的玩家；控制台 / 全局占位符时为 null（返回空串）
     * @param params %forgemaster_ 后面的部分
     * @return 值；不认识的参数返回 null（交还给 PAPI / 下一个注册者）
     */
    public static String resolve(ForgingMasterPlugin plugin, UUID uuid, String params) {
        if (plugin == null || uuid == null || params == null) {
            return "";
        }
        String key = params.trim().toLowerCase(Locale.ROOT);
        // v2.26.6：插件未初始化/被卸载窗口内 stats() 可能为 null——原 case "strengthen" 裸调
        // stats().bestStrengthenOf 会把 NPE 打进 PAPI 调用方（聊天/TAB 插件），统一安全取值
        int bestStrengthen = plugin.stats() == null ? 0 : plugin.stats().bestStrengthenOf(uuid);
        // 只读查询：没有档案返回 null，不给陌生 UUID 建空档案（否则 data.yml 会被占位符查询撑爆）
        StatsStore.Stats st = plugin.stats() == null ? null : plugin.stats().peek(uuid);
        return switch (key) {
            case "coins" -> st == null ? "0" : String.valueOf(st.coins);                 // 当前剑币
            case "reputation" -> st == null ? "0" : String.valueOf(st.reputation);       // 声望
            case "title" -> // 称号扩展 v2.19：先查佩戴（佩戴 > 声望），本行是称号渲染唯一插入点。
                // v2.26.6（审计 #3）：wornOf 对离线玩家会走 OfflinePlayer PDC = 同步读 .dat，
                // 而 PAPI 占位符常被 TAB/异步聊天调——离线直接走声望称号回退，不碰磁盘。
                (org.bukkit.Bukkit.getPlayer(uuid) != null
                        ? com.forgemaster.title.TitleService.renderTitle(plugin, uuid, st == null ? 0 : st.reputation)
                        : com.forgemaster.stats.StatsStore.titleOf(st == null ? 0 : st.reputation));
            case "top_quality" -> topQuality(st);                                        // 历史最高品质名
            case "top_score" -> st == null ? "0" : Msg.fmt(st.bestScore, 1);             // 历史最高分
            case "strengthen" -> String.valueOf(bestStrengthen);  // 历史最高强化等级（v2.26.6：安全取值，未初始化窗口内不 NPE）
            case "forge_count" -> st == null ? "0" : String.valueOf(st.forged);          // 累计锻造次数
            case "forbidden" -> forbidden(plugin, uuid);                                 // 在契状态
            default -> null;
        };
    }

    private static String topQuality(StatsStore.Stats st) {
        if (st == null || st.bestQuality == null || st.bestQuality.isEmpty()) {
            return "无";
        }
        Quality q = Quality.byName(st.bestQuality);
        return q == null ? st.bestQuality : q.display();
    }

    /** 在契状态：龙税周目没开 = 恒 false；开了走 ContractManager 现成的 joined 查询（无扫描） */
    private static String forbidden(ForgingMasterPlugin plugin, UUID uuid) {
        try {
            var cm = plugin.contractManager();
            if (cm == null || !cm.active()) {
                return "false";
            }
            return cm.joined(uuid) ? "true" : "false";
        } catch (RuntimeException ex) {
            return "false";
        }
    }
}
