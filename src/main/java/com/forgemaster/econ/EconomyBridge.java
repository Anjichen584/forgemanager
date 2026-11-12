package com.forgemaster.econ;

import com.forgemaster.ForgingMasterPlugin;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.plugin.RegisteredServiceProvider;

import java.lang.reflect.Method;

/**
 * 服务器货币桥接层。
 *
 * <p>优先用 Vault（CMI / Essentials / 各类经济插件都会注册 Vault 经济），
 * 其次是 CMI 自带的经济接口，两者都没有时退回插件内置的「剑币」。</p>
 *
 * <p>全部走反射，避免为了兼容而在编译期硬依赖 Vault / CMI。</p>
 */
public final class EconomyBridge {

    /** 实际生效的货币后端 */
    public enum Backend {
        /** Vault 经济（CMI、EssentialsX 等都走这里） */
        VAULT("Vault"),
        /** CMI 自带经济 */
        CMI("CMI"),
        /** 插件内置剑币 */
        COINS("剑币");

        private final String label;

        Backend(String label) {
            this.label = label;
        }

        public String label() {
            return label;
        }
    }

    private final ForgingMasterPlugin plugin;
    private Backend backend = Backend.COINS;
    private Object provider;
    private Method mGetBalance;
    private Method mHas;
    private Method mWithdraw;
    private Method mDeposit;
    private Method mFormat;
    private String unit = "金币";

    public EconomyBridge(ForgingMasterPlugin plugin) {
        this.plugin = plugin;
    }

    /** 启动时探测一次。配置 economy.provider 可强制指定 auto / vault / cmi / coins。 */
    public void init() {
        init(false);
    }

    /**
     * 再探一次。外部经济插件（CMI / EssentialsX）通常在本插件之后才注册 Vault 服务，
     * 所以启动时探不到很正常，需要在 enable 之后延迟重试。
     *
     * @return 这次是否新接上了外部经济
     */
    public boolean retry() {
        if (external()) {
            return false;
        }
        init(true);
        return external();
    }

    private void init(boolean quiet) {
        unit = plugin.settings().str("economy.unit", "金币");
        String want = plugin.settings().str("economy.provider", "auto").toLowerCase();
        if (!"coins".equals(want)) {
            if (!"cmi".equals(want) && tryVault()) {
                return;
            }
            if (!"vault".equals(want) && tryCmi()) {
                return;
            }
            if ("vault".equals(want) || "cmi".equals(want)) {
                plugin.getLogger().warning("配置要求 " + want + " 经济，但没找到可用的接口，已退回内置剑币");
            }
        }
        backend = Backend.COINS;
        provider = null;
        if (!quiet) {
            plugin.getLogger().info("货币后端：" + backend.label() + "（内置）");
        }
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    private boolean tryVault() {
        try {
            Class<?> economyClass = Class.forName("net.milkbowl.vault.economy.Economy");
            RegisteredServiceProvider<?> rsp = Bukkit.getServicesManager().getRegistration((Class) economyClass);
            if (rsp == null || rsp.getProvider() == null) {
                return false;
            }
            Object eco = rsp.getProvider();
            Method balance = economyClass.getMethod("getBalance", String.class);
            Method has = economyClass.getMethod("has", String.class, double.class);
            Method withdraw = economyClass.getMethod("withdrawPlayer", String.class, double.class);
            Method deposit = economyClass.getMethod("depositPlayer", String.class, double.class);
            Method format = null;
            try {
                format = economyClass.getMethod("format", double.class);
            } catch (NoSuchMethodException ignored) {
                // 部分实现没有 format，退回自带格式
            }
            provider = eco;
            mGetBalance = balance;
            mHas = has;
            mWithdraw = withdraw;
            mDeposit = deposit;
            mFormat = format;
            backend = Backend.VAULT;
            plugin.getLogger().info("货币后端：Vault（" + eco.getClass().getSimpleName() + "）");
            return true;
        } catch (Throwable ignored) {
            return false;
        }
    }

    /**
     * CMI 的经济接口在 {@code com.Zrips.CMI.Modules.Economy.Economy} 的静态方法上
     * （不是 EconomyManager，那边只有格式化和排行榜）。
     */
    private boolean tryCmi() {
        try {
            Class<?> eco = Class.forName("com.Zrips.CMI.Modules.Economy.Economy");
            // CMI 的 Economy 类在「经济模块关闭」时不会抛异常，只会一直返回 0，
            // 所以必须先看它自己的配置开关，否则会把不可用的经济当成可用。
            if (!cmiEconomyEnabled()) {
                return false;
            }
            Method balance = eco.getMethod("getBalance", String.class);
            Method has = eco.getMethod("has", String.class, double.class);
            Method withdraw = eco.getMethod("withdrawPlayer", String.class, double.class);
            Method deposit = eco.getMethod("depositPlayer", String.class, double.class);
            Method format = null;
            try {
                format = eco.getMethod("format", double.class);
            } catch (NoSuchMethodException ignored) {
                // 没有就退回自带格式
            }
            // 探一次：经济没启用时这里会抛异常
            balance.invoke(null, "__forgemaster_probe__");

            provider = null;
            mGetBalance = balance;
            mHas = has;
            mWithdraw = withdraw;
            mDeposit = deposit;
            mFormat = format;
            backend = Backend.CMI;
            plugin.getLogger().info("货币后端：CMI Economy");
            return true;
        } catch (Throwable ignored) {
            return false;
        }
    }

    /**
     * 读 CMI 自己的 config.yml，确认经济模块真的开着。
     *
     * <p>装了 CMIInjector 的服会强制开启，就不再拦。</p>
     */
    private boolean cmiEconomyEnabled() {
        try {
            if (Bukkit.getPluginManager().getPlugin("CMIInjector") != null) {
                return true;
            }
            var cmi = Bukkit.getPluginManager().getPlugin("CMI");
            if (cmi == null) {
                return false;
            }
            java.io.File file = new java.io.File(cmi.getDataFolder(), "config.yml");
            if (!file.exists()) {
                return false;
            }
            var yml = org.bukkit.configuration.file.YamlConfiguration.loadConfiguration(file);
            return yml.getBoolean("Economy.Enabled", false);
        } catch (Throwable ignored) {
            return false;
        }
    }

    public Backend backend() {
        return backend;
    }

    /** 是否接了外部经济（否则就是内置剑币） */
    public boolean external() {
        return backend != Backend.COINS;
    }

    public String unit() {
        return unit;
    }

    public double balance(Player player) {
        if (player == null) {
            return 0;
        }
        try {
            return switch (backend) {
                case VAULT -> ((Number) mGetBalance.invoke(provider, player.getName())).doubleValue();
                case CMI -> ((Number) mGetBalance.invoke(null, player.getName())).doubleValue();
                case COINS -> plugin.stats().coins(player.getUniqueId());
            };
        } catch (Throwable ex) {
            plugin.getLogger().warning("读取余额失败：" + ex.getMessage());
            return 0;
        }
    }

    public boolean has(Player player, long amount) {
        if (amount <= 0) {
            return true;
        }
        if (backend == Backend.COINS) {
            return plugin.stats().coins(player.getUniqueId()) >= amount;
        }
        try {
            return switch (backend) {
                case VAULT -> (Boolean) mHas.invoke(provider, player.getName(), (double) amount);
                case CMI -> (Boolean) mHas.invoke(null, player.getName(), (double) amount);
                default -> true;
            };
        } catch (Throwable ex) {
            return false;
        }
    }

    /** 扣钱。成功返回 true；余额不足或接口异常返回 false。 */
    public boolean withdraw(Player player, long amount) {
        if (amount <= 0) {
            return true;
        }
        if (!has(player, amount)) {
            return false;
        }
        try {
            switch (backend) {
                case VAULT -> {
                    Object resp = mWithdraw.invoke(provider, player.getName(), (double) amount);
                    return responseOk(resp);
                }
                case CMI -> {
                    Object resp = mWithdraw.invoke(null, player.getName(), (double) amount);
                    return responseOk(resp);
                }
                case COINS -> {
                    return plugin.stats().takeCoins(player.getUniqueId(), amount);
                }
            }
        } catch (Throwable ex) {
            plugin.getLogger().warning("扣款失败：" + ex.getMessage());
        }
        return false;
    }

    // ------------------------------------------------------------ v2.23.1 修 P1：按名字收发（离线玩家）

    /*
     * 原来会怎样：拍卖卖家、订单发单人、行会成员经常不在线，调用方只能拿到 UUID，
     * 于是所有「离线发钱」都绕开本类直接写 StatsStore——一旦 coin-sinks=server，
     * 这些钱就记进了另一本账（剑币），玩家在 Vault 侧看不到。
     *
     * 现在为什么不会：补上按玩家名收发的入口。Vault / CMI 的接口本来就是 String 玩家名，
     * 离线玩家也能打款；内置剑币后端需要 UUID，交给 Wallet 分流（见 Wallet.giveTo）。
     * 名字为空或查不到时返回 false，让调用方按「漏发」处理，绝不静默成功。
     */

    /** 按玩家名读余额（离线可用）；内置剑币后端读不到，返回 0 */
    public double balanceByName(String name) {
        if (name == null || name.isBlank() || backend == Backend.COINS) {
            return 0;
        }
        try {
            return switch (backend) {
                case VAULT -> ((Number) mGetBalance.invoke(provider, name)).doubleValue();
                case CMI -> ((Number) mGetBalance.invoke(null, name)).doubleValue();
                default -> 0;
            };
        } catch (Throwable ex) {
            plugin.getLogger().warning("读取余额失败（" + name + "）：" + ex.getMessage());
            return 0;
        }
    }

    /** 按玩家名扣钱（离线可用）；内置剑币后端返回 false，由 Wallet 走 StatsStore */
    public boolean withdrawByName(String name, long amount) {
        if (amount <= 0) {
            return true;
        }
        if (name == null || name.isBlank() || backend == Backend.COINS) {
            return false;
        }
        try {
            Object resp = switch (backend) {
                case VAULT -> mWithdraw.invoke(provider, name, (double) amount);
                case CMI -> mWithdraw.invoke(null, name, (double) amount);
                default -> null;
            };
            return responseOk(resp);
        } catch (Throwable ex) {
            plugin.getLogger().warning("扣款失败（" + name + "）：" + ex.getMessage());
            return false;
        }
    }

    /**
     * 按玩家名给钱（离线可用）。
     *
     * <p>内置剑币后端返回 false 而不是假装成功——剑币必须记到 UUID 上，
     * 由 {@link Wallet#giveTo} 转去 StatsStore。这里返回 true 会造成「钱没进任何账」。</p>
     */
    public boolean depositByName(String name, long amount) {
        if (amount <= 0) {
            return true;
        }
        if (name == null || name.isBlank() || backend == Backend.COINS) {
            return false;
        }
        try {
            Object resp = switch (backend) {
                case VAULT -> mDeposit.invoke(provider, name, (double) amount);
                case CMI -> mDeposit.invoke(null, name, (double) amount);
                default -> null;
            };
            return responseOk(resp);
        } catch (Throwable ex) {
            plugin.getLogger().warning("入账失败（" + name + "）：" + ex.getMessage());
            return false;
        }
    }

    /** 给钱（退费、奖励用） */
    public boolean deposit(Player player, long amount) {
        if (amount <= 0) {
            return true;
        }
        try {
            switch (backend) {
                case VAULT -> {
                    Object resp = mDeposit.invoke(provider, player.getName(), (double) amount);
                    return responseOk(resp);
                }
                case CMI -> {
                    Object resp = mDeposit.invoke(null, player.getName(), (double) amount);
                    return responseOk(resp);
                }
                case COINS -> {
                    plugin.stats().addCoins(player.getUniqueId(), amount);
                    return true;
                }
            }
        } catch (Throwable ex) {
            plugin.getLogger().warning("入账失败：" + ex.getMessage());
        }
        return false;
    }

    /** Vault 的 EconomyResponse 只能反射读 transactionSuccess() */
    private boolean responseOk(Object response) {
        if (response == null) {
            return false;
        }
        try {
            Method ok = response.getClass().getMethod("transactionSuccess");
            return (Boolean) ok.invoke(response);
        } catch (Throwable ignored) {
            // v2.25.2（审计轮4）：读不出成败时按<b>失败</b>处理。
            // 原来 return true 等于「接口拒了也当成功」—— coin-sinks=server 时
            // withdraw 明明没扣到钱却照发货（白拿），而且 data.yml 里没有这笔记录，对账查不出。
            // 宁可让玩家点两次，也不能凭空发货。
            return false;
        }
    }

    /** 金额文案，如 "200 金币"；接了 Vault 就交给它格式化 */
    public String format(long amount) {
        if (mFormat != null && external()) {
            try {
                Object target = backend == Backend.VAULT ? provider : null;
                Object out = mFormat.invoke(target, (double) amount);
                if (out != null) {
                    return String.valueOf(out);
                }
            } catch (Throwable ignored) {
                // 退回自带格式
            }
        }
        return amount + " " + unit;
    }
}
