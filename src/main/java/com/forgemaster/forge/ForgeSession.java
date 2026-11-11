package com.forgemaster.forge;

import com.forgemaster.affix.Affix;
import com.forgemaster.material.ForgeMaterial;
import com.forgemaster.quality.Quality;
import com.forgemaster.quench.Quench;
import org.bukkit.Bukkit;
import org.bukkit.boss.BossBar;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.UUID;

/**
 * 一次锻造的运行时状态。所有可变字段只在主线程读写。
 */
public final class ForgeSession {

    /** 单次敲击判定 */
    public enum Hit {
        PERFECT(1.0, "完美", "#4ee36b"),
        GOOD(0.62, "良好", "#ffd166"),
        MISS(0.08, "偏了", "#ff5252");

        public final double score;
        public final String display;
        public final String color;

        Hit(double score, String display, String color) {
            this.score = score;
            this.display = display;
            this.color = color;
        }
    }

    private final UUID uuid;
    private final String playerName;
    private final ForgeMaterial material;
    private final long startMillis = System.currentTimeMillis();
    private final Map<Affix, Double> affixValues = new LinkedHashMap<>();
    private final List<Hit> hits = new ArrayList<>();

    private ForgePhase phase = ForgePhase.HEATING;
    private double temperature = 20.0;
    private double heatScore;
    private int strikesDone;
    private int pointer;
    private int direction = 1;
    private int zoneCenter;
    private int bellowsCooldown;
    private int tickCounter;
    private long lastActionMillis = System.currentTimeMillis();
    private Quench quench;
    private double finalScore;
    private Quality quality = Quality.ROUGH;
    private ItemStack result;
    private boolean resultGiven;
    private boolean finished;
    private boolean transitioning;
    private BossBar bossBar;
    private com.forgemaster.stage.ForgeStage stage;

    /** 开局时的专精等级快照，整局判定都吃它 */
    private int mastery = 1;

    /** 锻造者称号（结算时写入 Lore） */
    private String forgerTitle = "";

    /** 极限锻造（RISK）：锻打加快、命中区变窄、分数倍率提高，但每个阶段都可能炸剑 */
    private boolean risk;

    /** 本局随机源：比试用同一种子，保证条件一致 */
    private Random rnd = new Random();

    // ---- 比试 ----
    private String duelId;

    // ---- 修复模式（研磨台） ----
    private boolean repairing;
    private org.bukkit.inventory.ItemStack repairTarget;

    /** 图纸带来的加热容差加成（0.15 = +15%） */
    private double blueprintBonus;
    /** v2.9 天赋：开工时固化，整局不变（中途洗点 / 升级不影响这一炉） */
    private com.forgemaster.growth.Talents.Kit talents = com.forgemaster.growth.Talents.Kit.NONE;

    /** 本局使用的材料档次（决定分数上限与额外词条） */
    private com.forgemaster.material.MaterialGrade grade = com.forgemaster.material.MaterialGrade.COMMON;

    /** 临摹：正在复刻的剑谱 id，null = 自由锻造 */
    private String manualId;
    /** 临摹复刻度 0~1 */
    private double copyRate;

    // ---- 叠钢（序列连点）----
    private final List<Integer> foldSequence = new ArrayList<>();
    private int foldCursor;
    private int foldMissed;
    private long foldDeadline;
    private double foldScore;

    // ---- 开刃（控压保持）----
    private double grindValue = 50.0;
    private double grindTargetCenter = 50.0;
    private double grindTargetWidth = 8.0;
    private double grindDrift;
    private int grindDriftTimer;
    private int grindTicksIn;
    private int grindTicksTotal;
    private double grindScore;

    // ---- 抛光（亮区下刀，v2.19）----
    /** 光泽指针位置 0~100，每 tick 走 polishSpeed */
    private double polishValue;
    /** 指针方向 +1 / -1 */
    private int polishDir = 1;
    /** 亮区中心 */
    private double polishCenter = 50.0;
    /** 亮区半宽 */
    private double polishWidth = 16.0;
    /** 每 tick 扫过的百分点 */
    private double polishSpeed = 2.6;
    /** 已下刀次数 / 本局总刀数 */
    private int polishPass;
    private int polishPasses = 3;
    /** 落在亮区半宽内的刀数（用于 Lore 与自检） */
    private int polishHits;
    /** 最近一刀的误差（百分点），-1 = 还没下过刀 */
    private double polishLastError = -1;
    private long polishDeadline;
    private double polishScore;

    // ---- 嵌银丝（亮窗抢点，v2.19）----
    private final List<Integer> inlaySequence = new ArrayList<>();
    private int inlayCursor;
    private int inlayHits;
    private int inlayMiss;
    /** 单根银丝的亮窗时长（tick），档位越高越短 */
    private int inlayWindow = 14;
    /** 当前这根银丝还剩几 tick 亮着 */
    private int inlayWindowLeft;
    private int inlayWindowTotal = 14;
    private long inlayDeadline;
    private double inlayScore;

    // ---- 环境修正（w7-env）：开局固化一次，整局使用 ----
    private com.forgemaster.env.EnvContext env = com.forgemaster.env.EnvContext.NEUTRAL;

    public ForgeSession(UUID uuid, String playerName, ForgeMaterial material) {
        this.uuid = uuid;
        this.playerName = playerName;
        this.material = material;
    }

    public static ForgeSession of(Player player, ForgeMaterial material) {
        ForgeSession s = new ForgeSession(player.getUniqueId(), player.getName(), material);
        s.seed(java.util.concurrent.ThreadLocalRandom.current().nextLong());
        return s;
    }

    public Player player() {
        return Bukkit.getPlayer(uuid);
    }

    public UUID uuid() {
        return uuid;
    }

    public String playerName() {
        return playerName;
    }

    public ForgeMaterial material() {
        return material;
    }

    public long startMillis() {
        return startMillis;
    }

    public ForgePhase phase() {
        return phase;
    }

    public void phase(ForgePhase phase) {
        this.phase = phase;
    }

    public double temperature() {
        return temperature;
    }

    public void temperature(double v) {
        this.temperature = Math.max(0.0, Math.min(120.0, v));
    }

    public double heatScore() {
        return heatScore;
    }

    public void heatScore(double v) {
        this.heatScore = Math.max(0.0, Math.min(1.0, v));
    }

    public int strikesDone() {
        return strikesDone;
    }

    public void addStrike(Hit hit) {
        hits.add(hit);
        strikesDone++;
    }

    public List<Hit> hits() {
        return hits;
    }

    public double hammerScore() {
        if (hits.isEmpty()) {
            return 0.0;
        }
        double sum = 0;
        for (Hit h : hits) {
            sum += h.score;
        }
        return sum / hits.size();
    }

    public int perfects() {
        int n = 0;
        for (Hit h : hits) {
            if (h == Hit.PERFECT) {
                n++;
            }
        }
        return n;
    }

    public int pointer() {
        return pointer;
    }

    public void pointer(int v) {
        this.pointer = v;
    }

    public int direction() {
        return direction;
    }

    public void flipDirection() {
        this.direction = -this.direction;
    }

    public int zoneCenter() {
        return zoneCenter;
    }

    public void zoneCenter(int v) {
        this.zoneCenter = v;
    }

    public int bellowsCooldown() {
        return bellowsCooldown;
    }

    public void bellowsCooldown(int v) {
        this.bellowsCooldown = v;
    }

    public int tickCounter() {
        return tickCounter;
    }

    public void tick() {
        this.tickCounter++;
        if (bellowsCooldown > 0) {
            bellowsCooldown--;
        }
    }

    public long lastActionMillis() {
        return lastActionMillis;
    }

    public void touch() {
        this.lastActionMillis = System.currentTimeMillis();
    }

    public Quench quench() {
        return quench;
    }

    public void quench(Quench q) {
        this.quench = q;
    }

    public Map<Affix, Double> affixValues() {
        return affixValues;
    }

    public double finalScore() {
        return finalScore;
    }

    public void finalScore(double v) {
        this.finalScore = v;
    }

    public Quality quality() {
        return quality;
    }

    public void quality(Quality q) {
        this.quality = q;
    }

    public ItemStack result() {
        return result;
    }

    public void result(ItemStack item) {
        this.result = item;
    }

    public boolean resultGiven() {
        return resultGiven;
    }

    public void resultGiven(boolean v) {
        this.resultGiven = v;
    }

    public boolean finished() {
        return finished;
    }

    public void finished(boolean v) {
        this.finished = v;
    }

    public boolean transitioning() {
        return transitioning;
    }

    public void transitioning(boolean v) {
        this.transitioning = v;
    }

    public BossBar bossBar() {
        return bossBar;
    }

    public void bossBar(BossBar bar) {
        this.bossBar = bar;
    }

    public com.forgemaster.stage.ForgeStage stage() {
        return stage;
    }

    public void stage(com.forgemaster.stage.ForgeStage stage) {
        this.stage = stage;
    }

    public int mastery() {
        return mastery;
    }

    public void mastery(int v) {
        this.mastery = Math.max(1, v);
    }

    public boolean risk() {
        return risk;
    }

    public void risk(boolean v) {
        this.risk = v;
    }

    public String forgerTitle() {
        return forgerTitle;
    }

    public void forgerTitle(String v) {
        this.forgerTitle = v == null ? "" : v;
    }

    public Random rnd() {
        return rnd;
    }

    public void seed(long seed) {
        this.rnd = new Random(seed);
    }

    public String duelId() {
        return duelId;
    }

    public void duelId(String v) {
        this.duelId = v;
    }

    public boolean repairing() {
        return repairing;
    }

    public void repairing(boolean v) {
        this.repairing = v;
    }

    public org.bukkit.inventory.ItemStack repairTarget() {
        return repairTarget;
    }

    public void repairTarget(org.bukkit.inventory.ItemStack v) {
        this.repairTarget = v;
    }

    public com.forgemaster.growth.Talents.Kit talents() {
        return talents;
    }

    public void talents(com.forgemaster.growth.Talents.Kit kit) {
        this.talents = kit == null ? com.forgemaster.growth.Talents.Kit.NONE : kit;
    }

    public double blueprintBonus() {
        return blueprintBonus;
    }

    public void blueprintBonus(double v) {
        this.blueprintBonus = Math.max(0.0, v);
    }

    public com.forgemaster.material.MaterialGrade grade() {
        return grade;
    }

    public void grade(com.forgemaster.material.MaterialGrade g) {
        this.grade = g == null ? com.forgemaster.material.MaterialGrade.COMMON : g;
    }

    public String manualId() {
        return manualId;
    }

    public void manualId(String v) {
        this.manualId = v;
    }

    /** 剑型 id（v2.14）：结算时按权重随机定，写进成品 PDC 与 Lore */
    private String formId;

    public String formId() {
        return formId;
    }

    public void formId(String v) {
        this.formId = v;
    }

    public double copyRate() {
        return copyRate;
    }

    public void copyRate(double v) {
        this.copyRate = Math.max(0, Math.min(1, v));
    }

    // ---------------------------------------------------------- 叠钢

    public List<Integer> foldSequence() {
        return foldSequence;
    }

    public int foldCursor() {
        return foldCursor;
    }

    public void foldCursor(int v) {
        this.foldCursor = v;
    }

    public int foldMissed() {
        return foldMissed;
    }

    public void foldMissed(int v) {
        this.foldMissed = v;
    }

    public long foldDeadline() {
        return foldDeadline;
    }

    public void foldDeadline(long v) {
        this.foldDeadline = v;
    }

    public double foldScore() {
        return foldScore;
    }

    public void foldScore(double v) {
        this.foldScore = Math.max(0.0, Math.min(1.0, v));
    }

    /** 叠钢打成的层数 = 正确点击次数 */
    public int layers() {
        return foldCursor;
    }

    /** 层数对应的钢纹名 */
    public String pattern() {
        int l = layers();
        if (l >= 8) {
            return "羽毛纹";
        }
        if (l >= 6) {
            return "雪花纹";
        }
        if (l >= 4) {
            return "水波纹";
        }
        if (l >= 2) {
            return "直纹";
        }
        return "无纹";
    }

    // ---------------------------------------------------------- 开刃

    public double grindValue() {
        return grindValue;
    }

    public void grindValue(double v) {
        this.grindValue = Math.max(0.0, Math.min(100.0, v));
    }

    public double grindTargetCenter() {
        return grindTargetCenter;
    }

    public void grindTargetCenter(double v) {
        this.grindTargetCenter = v;
    }

    public double grindTargetWidth() {
        return grindTargetWidth;
    }

    public void grindTargetWidth(double v) {
        this.grindTargetWidth = v;
    }

    public double grindDrift() {
        return grindDrift;
    }

    public void grindDrift(double v) {
        this.grindDrift = Math.max(-2.4, Math.min(2.4, v));
    }

    public int grindDriftTimer() {
        return grindDriftTimer;
    }

    public void grindDriftTimer(int v) {
        this.grindDriftTimer = v;
    }

    public int grindTicksIn() {
        return grindTicksIn;
    }

    public void grindTicksIn(int v) {
        this.grindTicksIn = Math.max(0, v);
    }

    public void incGrindTicksIn() {
        this.grindTicksIn++;
    }

    public int grindTicksTotal() {
        return grindTicksTotal;
    }

    public void grindTicksTotal(int v) {
        this.grindTicksTotal = Math.max(0, v);
    }

    public void incGrindTicksTotal() {
        this.grindTicksTotal++;
    }

    public double grindScore() {
        return grindScore;
    }

    public void grindScore(double v) {
        this.grindScore = Math.max(0.0, Math.min(1.0, v));
    }

    // ---------------------------------------------------------- 抛光

    public double polishValue() {
        return polishValue;
    }

    /** 0~100 硬夹：指针不许跑出标尺 */
    public void polishValue(double v) {
        this.polishValue = Math.max(0.0, Math.min(100.0, v));
    }

    public int polishDir() {
        return polishDir;
    }

    public void polishDir(int v) {
        this.polishDir = v >= 0 ? 1 : -1;
    }

    public double polishCenter() {
        return polishCenter;
    }

    public void polishCenter(double v) {
        this.polishCenter = Math.max(0.0, Math.min(100.0, v));
    }

    public double polishWidth() {
        return polishWidth;
    }

    /** 亮区半宽下限 3：再窄指针一格就飞过去，纯看脸 */
    public void polishWidth(double v) {
        this.polishWidth = Math.max(3.0, Math.min(45.0, v));
    }

    public double polishSpeed() {
        return polishSpeed;
    }

    public void polishSpeed(double v) {
        this.polishSpeed = Math.max(0.2, Math.min(12.0, v));
    }

    public int polishPass() {
        return polishPass;
    }

    public void polishPass(int v) {
        this.polishPass = Math.max(0, v);
    }

    public int polishPasses() {
        return polishPasses;
    }

    public void polishPasses(int v) {
        this.polishPasses = Math.max(1, v);
    }

    public int polishHits() {
        return polishHits;
    }

    public void polishHits(int v) {
        this.polishHits = Math.max(0, v);
    }

    public double polishLastError() {
        return polishLastError;
    }

    public void polishLastError(double v) {
        this.polishLastError = v;
    }

    public long polishDeadline() {
        return polishDeadline;
    }

    public void polishDeadline(long v) {
        this.polishDeadline = v;
    }

    public double polishScore() {
        return polishScore;
    }

    public void polishScore(double v) {
        this.polishScore = Math.max(0.0, Math.min(1.0, v));
    }

    /** 指针是否落在亮区里（半宽判定，和开刃绿区同口径） */
    public boolean polishInZone() {
        return Math.abs(polishValue - polishCenter) <= polishWidth;
    }

    /**
     * 一刀的成色（0~1）。误差在半宽一半以内算满分，到两倍半宽归零——
     * 亮区边缘不是断崖，但出了两倍半宽就一分不给，避免瞎点刷分。
     */
    public double polishGrade(double err) {
        double w = polishWidth;
        double full = w * 0.5;
        double e = Math.abs(err);
        if (e <= full) {
            return 1.0;
        }
        double zero = w * 2.0;
        if (e >= zero) {
            return 0.0;
        }
        return (zero - e) / (zero - full);
    }

    // ---------------------------------------------------------- 嵌银丝

    public List<Integer> inlaySequence() {
        return inlaySequence;
    }

    public int inlayCursor() {
        return inlayCursor;
    }

    public void inlayCursor(int v) {
        this.inlayCursor = Math.max(0, v);
    }

    public int inlayHits() {
        return inlayHits;
    }

    public void inlayHits(int v) {
        this.inlayHits = Math.max(0, v);
    }

    public int inlayMiss() {
        return inlayMiss;
    }

    public void inlayMiss(int v) {
        this.inlayMiss = Math.max(0, v);
    }

    public int inlayWindow() {
        return inlayWindow;
    }

    public void inlayWindow(int v) {
        this.inlayWindow = Math.max(3, v);
    }

    public int inlayWindowLeft() {
        return inlayWindowLeft;
    }

    public void inlayWindowLeft(int v) {
        this.inlayWindowLeft = Math.max(0, v);
    }

    public int inlayWindowTotal() {
        return inlayWindowTotal;
    }

    public void inlayWindowTotal(int v) {
        this.inlayWindowTotal = Math.max(1, v);
    }

    public long inlayDeadline() {
        return inlayDeadline;
    }

    public void inlayDeadline(long v) {
        this.inlayDeadline = v;
    }

    public double inlayScore() {
        return inlayScore;
    }

    public void inlayScore(double v) {
        this.inlayScore = Math.max(0.0, Math.min(1.0, v));
    }

    /** 当前该点的那根银丝的轨道下标；序列走完返回 -1 */
    public int inlayTarget() {
        if (inlayCursor < 0 || inlayCursor >= inlaySequence.size()) {
            return -1;
        }
        return inlaySequence.get(inlayCursor);
    }

    /** 亮窗还剩多少比例（1 = 刚亮，0 = 要灭了） */
    public double inlayWindowRatio() {
        return inlayWindowTotal <= 0 ? 0.0 : (double) inlayWindowLeft / inlayWindowTotal;
    }

    public boolean inlayDone() {
        return inlayCursor >= inlaySequence.size();
    }

    /** 开局固化的环境修正（天气 + 时辰），整局只读 */
    public com.forgemaster.env.EnvContext env() {
        return env;
    }

    public void env(com.forgemaster.env.EnvContext v) {
        this.env = v == null ? com.forgemaster.env.EnvContext.NEUTRAL : v;
    }

    /** 当前刀刃压力是否落在开刃区 */
    public boolean grindInZone() {
        return Math.abs(grindValue - grindTargetCenter) <= grindTargetWidth;
    }
}