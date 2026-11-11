package com.forgemaster.contract;

import com.forgemaster.ForgingMasterPlugin;

import java.util.List;
import java.util.UUID;

/**
 * 龙税契约的装配点（<b>暂定</b>）：持有三个策略接口的当前实现，
 * 全部默认 no-op。玩法循环（每日结算、贡品窗口 GUI、破契约）定下来后，
 * 在这里换实现即可，游戏侧代码只认接口。
 *
 * <p>config 键 {@code contract.enabled}（默认 false）：false 时永远返回 no-op，
 * 就算有人把实现注册进来也不生效——保证这个骨架绝不影响现有玩法。</p>
 */
public final class ContractRegistry {

    private final ForgingMasterPlugin plugin;
    private RunClock clock;
    private TributePolicy tribute;
    private FailurePolicy failure;

    public ContractRegistry(ForgingMasterPlugin plugin) {
        this.plugin = plugin;
        this.clock = noop();
        this.tribute = noopTribute();
        this.failure = noopFailure();
    }

    public void reload() {
        // 暂定阶段只关心开关；策略实现等玩法定型后再挂进来
        if (!plugin.settings().flag("contract.enabled", false)) {
            this.clock = noop();
            this.tribute = noopTribute();
            this.failure = noopFailure();
        }
    }

    public boolean active() {
        return plugin.settings().flag("contract.enabled", false);
    }

    public RunClock clock() {
        return clock;
    }

    public TributePolicy tribute() {
        return tribute;
    }

    public FailurePolicy failure() {
        return failure;
    }

    /** 玩法定型后注入实现用（active() 为 false 时注入无效） */
    public void bind(RunClock clock, TributePolicy tribute, FailurePolicy failure) {
        if (!active()) {
            return;
        }
        if (clock != null) {
            this.clock = clock;
        }
        if (tribute != null) {
            this.tribute = tribute;
        }
        if (failure != null) {
            this.failure = failure;
        }
    }

    // ------------------------------------------------------------ no-op 默认

    public static RunClock noop() {
        return new RunClock() {
            @Override
            public int day() {
                return 1;
            }

            @Override
            public long ticksRemaining() {
                return -1;
            }

            @Override
            public boolean isTributeDue() {
                return false;
            }
        };
    }

    public static TributePolicy noopTribute() {
        return new TributePolicy() {
            @Override
            public long requiredValue(int day, UUID playerId) {
                return 0;
            }

            @Override
            public long appraise(int day, List<org.bukkit.inventory.ItemStack> items) {
                return 0;
            }
        };
    }

    public static FailurePolicy noopFailure() {
        return (playerId, day, shortfall, missedStreak) -> "";
    }
}
