package com.forgemaster.webhook;

import java.util.Locale;

/**
 * 可播报的事件类型（v2.23 拓展 C，Webhook 广播）。
 *
 * <p>枚举值刻意少而稳：只挑「服主真的想在群里看到」的里程碑（锻出好剑、碎剑、
 * 蜕变、赛季结算…）。每个值带一个稳定的 {@link #id()}，配置里
 * {@code webhook.events} 用这个字符串白名单，改枚举名不会静默破配置。</p>
 *
 * <p>{@link #parse(String)} 对未知 id 返回 {@code null} 而不是抛——配置写错一个
 * 单词不该让插件起不来。</p>
 */
public enum WebhookEvent {

    /** 锻造成功（炸剑/粗糙不出） */
    FORGE_SUCCESS("forge_success", "锻造成功"),
    /** 强化成功（含大成 / 无损连击） */
    STRENGTHEN_SUCCESS("strengthen_success", "强化成功"),
    /** 强化碎剑 */
    STRENGTHEN_BREAK("strengthen_break", "碎剑"),
    /** 蜕变成功 */
    EVOLUTION_SUCCESS("evolution_success", "蜕变成功"),
    /** 剑冢残影挑战胜利 */
    TOMB_WIN("tomb_win", "剑冢胜利"),
    /** 比试获胜 */
    DUEL_WIN("duel_win", "比试获胜"),
    /** 行会协作委托达成 */
    GUILD_QUEST_DONE("guild_quest_done", "行会委托达成"),
    /** 赛季结算 */
    SEASON_END("season_end", "赛季结算");

    private final String id;
    private final String label;

    WebhookEvent(String id, String label) {
        this.id = id;
        this.label = label;
    }

    /** 配置 / JSON 用的稳定 id（小写蛇形） */
    public String id() {
        return id;
    }

    /** 中文显示名（写进 JSON 的 fields.type 便于人读） */
    public String label() {
        return label;
    }

    /** 按 id 解析；大小写与首尾空白都容忍，未知返回 null */
    public static WebhookEvent parse(String raw) {
        if (raw == null) {
            return null;
        }
        String k = raw.trim().toLowerCase(Locale.ROOT);
        if (k.isEmpty()) {
            return null;
        }
        for (WebhookEvent e : values()) {
            if (e.id.equals(k)) {
                return e;
            }
        }
        return null;
    }
}