package com.github.epsilon.modules.impl.movement;

import com.github.epsilon.events.bus.EventHandler;
import com.github.epsilon.events.bus.EventPriority;
import com.github.epsilon.events.impl.GameJoinedEvent;
import com.github.epsilon.events.impl.KeyPressEvent;
import com.github.epsilon.events.impl.KeyboardInputEvent;
import com.github.epsilon.events.impl.MousePressEvent;
import com.github.epsilon.events.impl.PacketEvent;
import com.github.epsilon.events.impl.PlayerTickEvent;
import com.github.epsilon.events.impl.Render2DEvent;
import com.github.epsilon.events.impl.Render3DEvent;
import com.github.epsilon.events.impl.SendPositionEvent;
import com.github.epsilon.graphics.renderers.TextRenderer;
import com.github.epsilon.graphics.schedulers.render3d.Render3DScheduler;
import com.github.epsilon.managers.ModuleManager;
import com.github.epsilon.managers.NotificationManager;
import com.github.epsilon.modules.Category;
import com.github.epsilon.modules.Module;
import com.github.epsilon.settings.Setting;
import com.github.epsilon.settings.SettingGroup;
import com.github.epsilon.settings.impl.BoolSetting;
import com.github.epsilon.settings.impl.ButtonSetting;
import com.github.epsilon.settings.impl.DoubleSetting;
import com.github.epsilon.settings.impl.IntSetting;
import com.github.epsilon.utils.client.KeybindUtils;
import com.google.common.base.Suppliers;
import com.mojang.blaze3d.platform.InputConstants;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.game.ClientboundBlockUpdatePacket;
import net.minecraft.network.protocol.game.ClientboundPlayerPositionPacket;
import net.minecraft.network.protocol.game.ServerboundAttackPacket;
import net.minecraft.network.protocol.game.ServerboundMovePlayerPacket;
import net.minecraft.network.protocol.game.ServerboundPlayerActionPacket;
import net.minecraft.network.protocol.game.ServerboundPlayerCommandPacket;
import net.minecraft.network.protocol.game.ServerboundUseItemOnPacket;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.level.block.BedBlock;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.ButtonBlock;
import net.minecraft.world.level.block.DoorBlock;
import net.minecraft.world.level.block.FenceGateBlock;
import net.minecraft.world.level.block.LeverBlock;
import net.minecraft.world.level.block.TrapDoorBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;
import org.lwjgl.glfw.GLFW;

import java.awt.Color;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.function.Supplier;

/**
 * 移植自 RavenBS-Plus-Plus（1.8.9）的 telly 脚本 {@code BSLegitTellyFix}，行为逐函数对齐。
 * 脚本宿主 API 已换成 26.2 原版 + Epsilon 公共 API：发包由 {@link SendPositionEvent}
 * 承载，输入注入由 {@link KeyboardInputEvent} 承载，方块名走 1.13+ 注册名。
 */
public class Telly extends Module {

    public static final Telly INSTANCE = new Telly();

    private Telly() {
        super("Telly", Category.MOVEMENT);
    }

    private final BoolSetting autoSwap = boolSetting("Auto Swap", true);
    private final BoolSetting disableSafeWalk = boolSetting("Disable SafeWalk", true);
    private final BoolSetting showActivationHitbox = boolSetting("Show Activation Hitbox", false);
    private final BoolSetting print = boolSetting("Print", false);
    /** 调试日志开关：控制写进 latest.log 的 [Telly] 诊断行。默认开，排查完可自行关闭。 */
    private final BoolSetting debugLog = boolSetting("Debug Log", true);
    /**
     * 激活手势的「按住潜行」计时（毫秒）：蹲满这段时间提示才转绿、允许触发。
     *
     * <p>脚本原值固定 1000ms，但源客户端的 {@code activationPromptReady} 本来就是宿主侧的手感参数，
     * 与算法无关，做成设置便于按自己的节奏调。
     */
    // 组必须声明在使用它们的设置字段之前：Java 字段按声明顺序初始化，
    // 若设置写在组前面，.group(sgXxx) 拿到的会是 null。
    private final SettingGroup sgGuard = settingGroup("Guard");
    private final SettingGroup sgSway = settingGroup("Anti Sway");
    private final SettingGroup sgRot = settingGroup("Rotation");
    private final SettingGroup sgDevPlace = settingGroup("Placement");
    private final SettingGroup sgDevAct = settingGroup("Activation");

    private final IntSetting activationTime = intSetting("Activation Time", 1000, 200, 3000, 100).group(sgDevAct);

    /*
     * ===== Dev Tuning =====
     *
     * 把影响「搭得远不远 / 会不会掉」的参数全部暴露出来，便于现场逐个试。
     * 注释里标注的「脚本原值」就是可还原的基线。
     *
     * ⚠️ 这些参数**互相耦合**（例如放宽 sway 限幅就必须同时动 response/damping 才有意义），
     * 单改一个常常看不出效果。已试过并否证的组合记在各自注释里，别再重复试：
     *   - 放宽 sway 限幅 2.25→4：sway 用满了，横向误差 err 纹丝不动（±0.5 不变）。
     *   - 把 sway 修正从「相机」改到「走位」：同样无效。
     *   - antiSwayLane 取方块中心（让玩家居中）：err 收敛到 0，但 placedOk 从 100+ 掉到 19/37
     *     —— telly 靠「站在方块边缘」向后搭，居中会把放置距离拉远半格。
     */
    /** 护栏总开关。关掉 = 完全按脚本原样跑（不刹车）。 */
    private final BoolSetting guardEnabled = boolSetting("Enabled", true).group(sgGuard);
    /**
     * 超前「已铺桥面」超过这么多格就刹车等桥补齐。
     *
     * <p>⚠️ 默认 1 是「能搭很多格子」那个状态的取值。实测：设为 1 ⇒ placedOk 19~60；
     * 设为 2 ⇒ 刹车点后移；完全关闭护栏 ⇒ 6~12（4 格就踩空）。
     * 三者都能调，取 1 是为了回到用户认可的那个手感。
     */
    private final IntSetting overshootLimit = intSetting("Overshoot Limit", 1, 0, 8, 1).group(sgGuard);
    /** 横向偏离 lane 超过这么多格就刹车（脚本原值无此逻辑）。 */
    private final DoubleSetting offLaneLimit = doubleSetting("Off Lane Limit", 1.0, 0.1, 5.0, 0.1).group(sgGuard);

    /** 每帧允许的转向修正上限（度）。脚本原值 2.25。 */
    private final DoubleSetting swayYawLimit = doubleSetting("Yaw Limit", 2.25, 0.0, 15.0, 0.05).group(sgSway);
    /** 位置误差 → 期望横向速度的系数。脚本原值 0.42。 */
    private final DoubleSetting swayResponse = doubleSetting("Response", 0.42, 0.0, 2.0, 0.01).group(sgSway);
    /** 当前横向速度的阻尼系数。脚本原值 0.78。 */
    private final DoubleSetting swayDamping = doubleSetting("Damping", 0.78, 0.0, 2.0, 0.01).group(sgSway);
    /** 期望横向速度上限（格/tick）。脚本原值 0.16。 */
    private final DoubleSetting swayMaxVelocity = doubleSetting("Max Velocity", 0.16, 0.0, 1.0, 0.01).group(sgSway);
    /** 速度修正 → 转向角的增益。脚本原值 0.55。 */
    private final DoubleSetting swayGain = doubleSetting("Gain", 0.55, 0.0, 2.0, 0.01).group(sgSway);

    /** 旋转量化步长（度）。脚本原值 0.03404715；源客户端按本机灵敏度算约 0.085。 */
    private final DoubleSetting sensitivityQuantum = doubleSetting("Sensitivity Quantum", 0.03404715, 0.001, 0.2, 0.005).group(sgRot);
    /** YAW_NUDGE_PATTERN `{0,1,-1,2,-2}` 的倍率；抖动幅度 = 本倍率 × 量子。脚本原值 1.0。 */
    private final DoubleSetting yawNudgeScale = doubleSetting("Yaw Nudge Scale", 1.0, 0.0, 5.0, 0.1).group(sgRot);

    /** setup 阶段起跳前的惯性 tick 数。脚本原值 6；砍到 2 更容易在边缘踩空。 */
    private final IntSetting setupInertiaTicks = intSetting("Setup Inertia Ticks", 6, 0, 12, 1).group(sgDevPlace);

    /**
     * 激活时把朝向吸附到的「基准角」网格：{@code round((yaw - base)/90)*90 + base}。
     *
     * <p>⚠️ 默认 <b>44°</b> 而不是 45°，两个理由：
     * <ol>
     *   <li><b>躲机器特征</b>：45 是整度数，吸附后 {@code BEGIN yaw} 会变成 -315.00 / -495.00 这类
     *       精确值，真人做不到 —— 实测那样会被 Intave 报 {@code acting computer-like #1}。
     *       44° 是「像手抖停在的角度」。</li>
     *   <li><b>稳定 travel 判定</b>：45° 恰好是 {@code calculateTravelDirection} 里
     *       {@code rawX = sin - cos} 的零点（象限分界），浮点抖动会让 travelX/travelZ 在两侧跳。
     *       44° 落在分界同侧，判定稳定。</li>
     * </ol>
     * 注意激活判据 {@code ACTIVATION_YAW_TOLERANCE} 是 45°±2°，44° 只差 1°，仍可正常激活。
     */
    private final DoubleSetting snapDegrees = doubleSetting("Snap Degrees", 44.0, 0.0, 90.0, 0.5).group(sgDevAct);
    /** 按住潜行计时期间，把视角吸向上述网格的最大校正范围（度）。 */
    private final DoubleSetting snapRange = doubleSetting("Snap Range", 10.0, 0.0, 45.0, 0.5).group(sgDevAct);
    /** 上述校正的每 tick 步长（度），保证平滑推入而非瞬移。 */
    private final DoubleSetting snapStep = doubleSetting("Snap Step", 1.0, 0.1, 10.0, 0.1).group(sgDevAct);

    /** 相机偏离脚本朝向多少度就判定为玩家接管。脚本原值 ≈0.015（累积到 25）；5 是实测值。 */
    private final DoubleSetting takeoverDegrees = doubleSetting("Takeover Degrees", 5.0, 1.0, 45.0, 0.5).group(sgDevAct);

    /**
     * Dev Tuning 调乱了一键还原：遍历所有设置调用 {@link Setting#reset()}。
     *
     * <p>{@code reset()} 直接把 value 置回 defaultValue（不触发 onChanged）—— 对本模块这些
     * 纯数值设置来说没有副作用需求，够用。按钮自身也在 settings 列表里，跳过它。
     */
    private final ButtonSetting resetDefaults = buttonSetting("Reset Defaults", () -> {
        for (Setting<?> setting : List.copyOf(settings)) {
            if (!(setting instanceof ButtonSetting)) setting.reset();
        }
        NotificationManager.INSTANCE.info("Telly", "Settings reset to defaults");
    });

    private final Supplier<TextRenderer> promptRenderer = Suppliers.memoize(() -> TextRenderer.create(128 * 1024));

    // ===== 脚本顶层状态 =====

    private boolean armed = false;
    private boolean running = false;
    private long activatePromptAt = 0L;
    private long promptBrokeAt = 0L;
    private float promptAlpha = 0.0f;
    private long promptFadeLastAt = 0L;
    private int promptFadeRgb = 0xFF5555;
    private int[] hitboxLastPos = null;
    private int hitboxLastFace = -1;
    private boolean activationMovementHeld = false;
    private boolean antiSwayTapUsed = false;
    private final Set<String> cancelledGhostBlocks = new HashSet<>();
    private boolean tellyAutoPlaceWindow = false;
    private boolean autoPlaceDebugActive = false;
    private boolean safeWalkStateCaptured = false;
    private boolean safeWalkWasEnabled = false;

    private int setupTick = 0;
    private int cyclePhase = 19;
    private float baseYaw = 0.0f;
    private int travelX = 0;
    private int travelZ = 0;
    private double antiSwayLane = 0.0;
    private float antiSwayYawOffset = 0.0f;
    private int bridgeLaneBlock = 0;
    private int bridgeStartProgress = 0;
    private int[] latestStraightPlacedPos = null;
    private boolean firstTellyPlacementPending = false;
    private boolean adaptiveAimValid = false;
    private float adaptiveAimYaw = 0.0f;
    private float adaptiveAimPitch = 0.0f;
    private long adaptiveAimUpdatedAt = 0L;
    private long takeoverDetectionAt = 0L;
    private boolean takeoverCameraValid = false;
    private float takeoverCameraYaw = 0.0f;
    private float takeoverCameraPitch = 0.0f;
    private float takeoverAccumulated = 0.0f;
    private long takeoverLastFrameAt = 0L;
    private long freezeLastTickAt = 0L;
    private boolean ignoreForwardUntilRelease = false;
    private boolean ignoreBackUntilRelease = false;
    private boolean ignoreLeftUntilRelease = false;
    private boolean ignoreRightUntilRelease = false;
    private boolean ignoreJumpUntilRelease = false;
    private boolean ignoreSneakUntilRelease = false;
    private boolean ignoreSprintUntilRelease = false;

    private boolean rotationActive = false;
    private long rotationStartedAt = 0L;
    private long rotationDuration = 50L;
    private float rotationStartYaw = 0.0f;
    private float rotationStartPitch = 0.0f;
    private float rotationTargetYaw = 0.0f;
    private float rotationTargetPitch = 0.0f;
    private float scriptedRotationYaw = 0.0f;
    private float scriptedRotationPitch = 0.0f;

    private static final int[] YAW_NUDGE_PATTERN = {0, 1, -1, 2, -2};
    private int rotationStepCounter = 0;
    private static final double ACTIVATION_ACROSS_MIN = 0.38;
    private static final double ACTIVATION_ACROSS_MAX = 0.65;
    private static final double ACTIVATION_HEIGHT_MIN = 0.25;
    private static final double ACTIVATION_HEIGHT_MAX = 0.75;
    private static final float ACTIVATION_YAW_TOLERANCE = 2.0f;

    /**
     * 激活诊断的刷新间隔（毫秒）。 */
    private static final long ACTIVATION_DIAGNOSTICS_INTERVAL_MS = 100L;

    private long lastActivationDiagnosticsAt = 0L;
    /** 屏幕底部单行显示的激活诊断文本；null = 不显示。 */
    private String activationDiagnosticsLine = null;

    // 运行期诊断（只读，用于定位"搭几格后掉落"这类问题）
    private int runDiagPlaceOk = 0;
    private int runDiagPlaceFail = 0;
    /** 上一次「放置失败」诊断的输出时间，限流用（高帧率下每 tick 都可能失败）。 */
    private long lastPlacementFailLogAt = 0L;
    /**
     * 放置失败原因直方图（本轮累计）。
     *
     * <p>限流日志只采样，会漏掉「恰好掉下去那几 tick」的样本，所以停止时把完整分布打出来。
     * ⚠️ 已知事实：{@code look-misaligned} 属于**设计内**的筛选 ——
     * {@code findBelowPlacement} 会同时产出身前/身后候选，而 {@code resolveVerifiedHit(当前视角, …)}
     * 就是靠 YAW_CURVE 转过头去"够"不同候选（偏转大时够到身后格，偏转小时够到身前格）。
     * 曾试过把放置方向钉死在 baseYaw（新增稳定「逻辑朝向」），结果匹配率反而下降、
     * placedOk 从 105/137 崩到 9~28 —— **摆动是够取候选的手段，不能锁死。**
     */
    private final Map<String, Integer> placementFailCounts = new LinkedHashMap<>();
    /**
     * 最近成功放置过的格子，用于**精确**判定「服务端吞方块」。
     *
     * <p>原先的条件只有「在 lane 上 + 包内是空气」，任何相邻格更新都会命中 —— 单机实测刷出
     * 6 条全是噪声（{@code name=air}，那格本来就没放过东西），据此得出的「区块边界吞方块」
     * 结论不成立。现在只在**我们真的放过、又被改回空气**时才报。
     */
    private final Set<String> recentPlacedKeys = new HashSet<>();
    private boolean runDiagBelowAir = false;
    private float runDiagSway = 0f;
    private float runDiagTargetPitch = 0f;
    private float runDiagTargetYaw = 0f;
    private int runDiagPhase = 0;
    /** 最后一次站在地面时的脚部 Y（方块坐标）；空中放置以此为目标基准。 */
    private int lastGroundFeetY = Integer.MIN_VALUE;

    /*
     * ===== telly（塔里）的机制 —— 动这几条曲线之前必须先读懂 =====
     *
     * Java 版没法像基岩版那样"往前伸着手搭"，所以 telly 全程是「按住 S 后退 + 有节奏地补方块」
     * （普通蹲起搭是按住 S 并规律地按 Shift；telly 多一步：交替 A+S，因为 A+S 的合成速度比
     * 只按 S 快，能把桥更快铺出去）。
     *
     * 完整的 telly 是三拍动作：
     *   ① 先转头，边转头边起跳；
     *   ② 疾跑窜出去；
     *   ③ 再回头，把刚才那几格接上。
     *
     * 这三条曲线就是这套动作的分解，不是可随便调优的噪声：
     *   - FORWARD_CURVE：主旋律「按住 S」。相位 4~19 恒 -1（持续后退）；相位 0/1/20 为 +1
     *     （"窜出去"那两拍）；相位 2/3 为 0。
     *   - STRAFE_CURVE：交替的「A」。相位 0~3 与 17~20 为 -1（A+S），其余为 0（只按 S）。
     *     ⚠️ 正是这个 -1 把 forward=-1 掰成"沿桥轴向"：(-1,-1) 在 baseYaw 下合成出平行于桥的
     *     方向，而 (-1,0) 是斜 45° 的。别去"修正"它。
     *   - YAW_CURVE / PITCH_CURVE：对应 ①③ 的「转头 / 回头」，相对 baseYaw 摆动。
     *     走位方向**必须跟随这个摆动视角** —— MC 的 moveRelative() 就是用 getYRot() 旋转输入的。
     *
     * ⚠️⚠️ 踩过的坑（每个都让实测报废过一轮）：
     *   ① 把走位方向锁死在 baseYaw（订阅 StrafeEvent.setYaw），理由是我算出"21 帧矢量和的净方向
     *      斜 36.9°，跟摆会漂"。结果占 13/21 帧的 strafe=0 相位全变成恒定斜 45° 前进，角色笔直
     *      斜着飞出去，placedOk 从 105/137 崩到 5~34。**走位跟随视角是设计，不是 bug。**
     *   ② 把视角改写成只驱动模型（setYBodyRot/setYHeadRot），想"视角摆会让走位歪"。这直接把相机
     *      锁死了 —— 而"视角在摆"正是这个脚本的核心观感。**视角必须由 player.setYRot 驱动。**
     *   ③ 把 setup 的前置惯性从 6 刻砍到 2 刻（想"早点跳更快"），反而更容易在边缘踩空掉桥。
     *   ④ 曾把发包 yaw 改成脚本角、真实 yaw 固定，属于同一类误判，一并回滚。
     */
    private static final float[] YAW_CURVE = {
            91.68f, 98.88f, 78.94f, 37.45f, 1.61f, -21.69f, -33.98f,
            -35.80f, -34.64f, -33.85f, -33.06f, -31.55f, -29.26f, -26.65f,
            -24.19f, -21.07f, -18.84f, -17.06f, -8.87f, 2.61f, 41.94f
    };

    private static final float[] PITCH_CURVE = {
            64.31f, 59.95f, 60.57f, 61.46f, 60.64f, 58.89f, 56.91f,
            56.63f, 58.65f, 61.63f, 64.20f, 66.74f, 68.69f, 70.64f,
            73.01f, 75.37f, 77.46f, 78.56f, 78.90f, 77.22f, 72.25f
    };

    private static final float[] FORWARD_CURVE = {
            1.0f, 1.0f, 0.0f, 0.0f, -1.0f, -1.0f, -1.0f,
            -1.0f, -1.0f, -1.0f, -1.0f, -1.0f, -1.0f, -1.0f,
            -1.0f, -1.0f, -1.0f, -1.0f, -1.0f, -1.0f, 1.0f
    };

    private static final float[] STRAFE_CURVE = {
            -1.0f, -1.0f, -1.0f, -1.0f, 0.0f, 0.0f, 0.0f,
            0.0f, 0.0f, 0.0f, 0.0f, 0.0f, 0.0f, 0.0f,
            0.0f, 0.0f, 0.0f, -1.0f, -1.0f, -1.0f, -1.0f
    };

    // ===== 脚本 client.setForward/getForward 等的宿主内部状态 =====

    private float clientForward = 0.0f;
    private float clientStrafe = 0.0f;
    private boolean clientJump = false;
    private boolean clientSneak = false;
    private boolean clientSprinting = false;

    /** 本 tick 正在派发的键盘输入事件；脚本 client.setXxx 的注入目标。仅在运行时被写入。 */
    private KeyboardInputEvent activeInputEvent = null;

    // ===== 放置控制器状态 =====

    private int currentClientTick = Integer.MIN_VALUE;
    private int placementEvaluationTick = Integer.MIN_VALUE;
    private int lastPlacementAttemptTick = Integer.MIN_VALUE;
    private int lastSuccessfulPlaceTick = Integer.MIN_VALUE;
    private int forceSuppressTick = Integer.MIN_VALUE;
    private long totalC08Counter = 0L;
    private long c08CounterAtTickBoundary = 0L;
    private boolean hasLastSentServerPos = false;
    private double lastSentServerPosX, lastSentServerPosY, lastSentServerPosZ;
    private Object[] cachedCandidate = null;
    private int cachedCandidateTick = Integer.MIN_VALUE;
    private float cachedCandidateYaw = Float.NaN;
    private float cachedCandidatePitch = Float.NaN;
    private boolean candidateResolvedThisTick = false;
    private int[] lastPlacedPos = null;
    private int[] lastSupportPos = null;
    private int lastSupportFace = -1;
    private List<int[]> cachedBelowTargets = null;
    private int cachedBelowTargetsTick = Integer.MIN_VALUE;
    private final Map<String, Integer> rejectedTargets = new HashMap<>();
    private int forcedModeCheck = 0;
    private boolean useSuppressed = false;
    private boolean silentPitchActive = false;
    private float silentPitch = 0f;
    private boolean placingViaModule = false;
    private boolean manualC08InWindow = false;

    /** 脚本 bridge 的等价物：同一个客户端内脚本之间的共享槽位。 */
    private static final Map<String, Object> BRIDGE = new HashMap<>();

    // =====================================================================
    // 模块生命周期（脚本 onLoad / onEnable / onDisable / onWorldJoin）
    // =====================================================================

    @Override
    protected void onEnable() {
        autoPlaceOnEnable();
        armAutomation();
    }

    @Override
    protected void onDisable() {
        stopAutomation(false);
        autoPlaceOnDisable();
    }

    @EventHandler
    private void onWorldJoin(GameJoinedEvent event) {
        stopAutomation(false);
        autoPlaceOnWorldJoin();
    }

    // =====================================================================
    // 事件接线
    // =====================================================================

    /** 脚本 onPreUpdate()。 */
    @EventHandler
    private void onPreUpdate(PlayerTickEvent.Pre event) {
        // 世界加载期（集成服务器启动中）mc.level 可能尚未建立，而 mc.player 已经有了。
        // 此时任何方块查询（Level.clip / getBlockState）都会让主线程同步加载区块，
        // 与服务器区块生成争锁，表现为卡在「准备生成区域」长时间不动。
        if (nullCheck()) return;
        enforceSafeWalkDisabledForRun();
        if (running) {
            setPressed("attack", false);
            applySmoothedRotation();
        }

        if (armed && !running) updateActivationPrompt();
        if (running && mc.player != null) pushActivationDiagnostics(mc.player);

        if (!running) return;

        long freezeNow = clientTime();
        // 脚本原值 300ms 用于检测「游戏被外部冻结」。26.2 在高负载机器上单帧常超 300ms，
        // 会持续误判并把自动化打断（表现：跑 1~6 秒就自动停）。放宽到 2 秒。
        if (freezeLastTickAt != 0L && freezeNow - freezeLastTickAt > 2000L) {
            stopAutomation(true);
            return;
        }
        freezeLastTickAt = freezeNow;

        LocalPlayer player = mc.player;
        if (player == null || player.isDeadOrDying() || player.fallDistance > 7.0f) {
            stopAutomation(true);
            return;
        }
        handleAutoSwap(player);
        if (!player.isHolding(stack -> stack.getItem() instanceof BlockItem)) {
            stopAutomation(true);
            return;
        }
        if (firstTellyPlacementPending) updateAdaptivePlacementAim(player);

        autoPlaceOnPreUpdate();
        if (firstTellyPlacementPending) updateAdaptivePlacementAim(player);
    }

    /** 脚本 onPostPlayerInput()。 */
    @EventHandler(priority = EventPriority.HIGHEST)
    private void onPostPlayerInput(KeyboardInputEvent event) {
        // 未运行时绝不触碰玩家输入：KeyboardInputEvent 的产物就是本 tick 的 player.input，
        // 无条件写回缓存值会把玩家自己的按键清成 0（表现为完全无法移动）。
        if (nullCheck() || !running) return;

        activeInputEvent = event;
        suppressSneakInput();
        enforceSafeWalkDisabledForRun();

        if (setupTick >= 0) {
            if (setupTick < 12) {
                // 用户方案：先「轻微往回冲一小段」建立后退惯性，再起跳 ——
                // 从边缘静止直接起跳没有初速，退一点点再跳会明显更快，桥更容易接上。
                boolean setupJump = setupTick >= setupInertiaTicks.getValue();
                applyMovement(-1.0f, -1.0f, setupJump, false);
                applyUse(true);
                dbg("setup t=" + setupTick
                        + " jump=" + setupJump
                        + " pos=" + (mc.player == null ? "null" : fmtPos(mc.player))
                        + " vH=" + (mc.player == null ? "-" : String.format(Locale.ROOT, "%.3f", mc.player.getDeltaMovement().horizontal().length()))
                        + " vY=" + (mc.player == null ? "-" : String.format(Locale.ROOT, "%.3f", mc.player.getDeltaMovement().y))
                        + " onGround=" + (mc.player != null && mc.player.onGround()));

                if (setupTick == 11) {
                    setRotationTarget(baseYaw + YAW_CURVE[19], PITCH_CURVE[19], 50L);
                } else {
                    setRotationTarget(baseYaw, 74.52f, 50L);
                }
                setupTick++;
                return;
            }

            setupTick = -1;
            takeoverDetectionAt = clientTime() + 125L;
            LocalPlayer takeoverPlayer = mc.player;
            takeoverCameraValid = takeoverPlayer != null;
            takeoverAccumulated = 0.0f;
            takeoverLastFrameAt = clientTime();
            if (takeoverPlayer != null) {
                takeoverCameraYaw = takeoverPlayer.getYRot();
                takeoverCameraPitch = takeoverPlayer.getXRot();
            }
            captureInitialMovementHolds();
            cyclePhase = 19;
            firstTellyPlacementPending = true;
            adaptiveAimValid = false;
            clearCachedCandidate();
            updateAdaptivePlacementAim(mc.player);
        }

        int phase = cyclePhase;
        float strafe = STRAFE_CURVE[phase];

        boolean sprinting = phase == 0 || phase == 1;
        boolean jumping = phase >= 1 && phase <= 19;
        boolean use = phase >= 7;

        // 护栏：玩家冲到「已铺桥面」之外太远时归零移动，等桥补齐。
        // 阈值取 2 —— 实测正常超前 2.0~2.5、掉落发生在 2.5+，所以刹车点落在中间。
        // 阈值 1 会过度刹车（placedOk 19~60），完全移除则会 6~12 就踩空。详见方法注释。
        if (isPlayerOffLane(mc.player) || isPlayerAheadOfBridge(mc.player)) {
            applyMovement(0.0f, 0.0f, false, false);
        } else {
            applyMovement(FORWARD_CURVE[phase], strafe, jumping, sprinting);
        }
        applyUse(use);

        int nextPhase = (phase + 1) % YAW_CURVE.length;
        setRotationTarget(baseYaw + YAW_CURVE[nextPhase], PITCH_CURVE[nextPhase], 50L);
        cyclePhase = nextPhase;
    }

    /** 脚本 onPreMotion(state)。 */
    @EventHandler
    private void onPreMotion(SendPositionEvent event) {
        if (nullCheck() || !running) return;
        LocalPlayer player = mc.player;
        if (player == null) return;
        event.setYaw(player.getYRot());
        event.setPitch(player.getXRot());
        autoPlaceOnPreMotion(event);
    }

    /** 脚本 onPostMotion()。 */
    @EventHandler
    private void onPostMotion(PlayerTickEvent.Post event) {
        if (nullCheck() || !running) return;
        autoPlaceOnPostMotion();
    }

    /** 脚本 onRenderTick(pt) 与 onRenderWorld(pt) 的合并（同一 3D 渲染回调）。 */
    @EventHandler
    private void onRender3D(Render3DEvent event) {
        if (nullCheck()) return;
        updateActivatePromptFade();
        if (!running) {
            onRenderWorld();
            return;
        }
        if (detectManualCameraTakeover()) return;
        applySmoothedRotation();
        autoPlaceOnRenderTick();
    }

    /** 脚本 drawActivatePrompt()。 */
    @EventHandler
    private void onRender2D(Render2DEvent.Level event) {
        if (nullCheck()) return;
        TextRenderer renderer = promptRenderer.get();
        boolean drew = false;
        if (promptAlpha >= 0.05f) {
            drew |= drawActivatePrompt(renderer);
        }
        String diagnostics = activationDiagnosticsLine;
        if (diagnostics != null && !diagnostics.isEmpty()) {
            drew |= drawDiagnosticsLine(renderer, diagnostics);
        }
        if (drew) renderer.drawAndClear();
    }

    /** 脚本 onMouse(button, state, x, y)。 */
    @EventHandler
    private void onMouse(MousePressEvent event) {
        boolean state = event.getAction() == GLFW.GLFW_PRESS;
        if (!onMouse(event.getButton(), state)) event.cancel();
    }

    /** 脚本 onKey(name, code, state, inGui)。 */
    @EventHandler
    private void onKey(KeyPressEvent event) {
        boolean state = event.getAction() == InputConstants.PRESS;
        boolean inGui = mc.gui.screen() != null;
        if (!onKey(event.getKey(), state, inGui)) event.cancel();
    }

    /** 脚本 onPacketSent(packet)。 */
    @EventHandler
    private void onPacketSend(PacketEvent.Send event) {
        if (!onPacketSent(event.getPacket())) event.cancel();
    }

    /** 脚本 onPacketReceived(packet)。 */
    @EventHandler
    private void onPacketReceive(PacketEvent.Receive event) {
        // 返回值语义与 onPacketSent 相反：脚本 onPacketReceived 总是 return true 表示「放行」，
        // 只有显式 return false 才是拦截。这里若无条件 cancel，会把所有服务端包丢光，
        // 世界加载会直接卡死在「准备生成区域」（客户端等不到区块数据）。
        if (!onPacketReceived(event.getPacket())) event.cancel();
    }

    // =====================================================================
    // 激活手势（脚本 148-311、407-434）
    // =====================================================================

    private float activationPitch() {
        return 75.0f;
    }

    private void handleAutoSwap(LocalPlayer player) {
        if (!autoSwap.getValue()) return;

        int threshold = 5;
        ItemStack held = player.getMainHandItem();
        int heldCount = held != null && isUsableBlockStack(held) ? held.getCount() : 0;
        if (heldCount > threshold) return;

        int bestSlot = -1;
        int bestSize = heldCount;
        for (int slot = 0; slot <= 8; slot++) {
            if (slot == mc.player.getInventory().getSelectedSlot()) continue;
            ItemStack stack = mc.player.getInventory().getItem(slot);
            if (!isUsableBlockStack(stack)) continue;
            if (stack.getCount() > bestSize) {
                bestSize = stack.getCount();
                bestSlot = slot;
            }
        }

        if (bestSlot != -1) mc.player.getInventory().setSelectedSlot(bestSlot);
    }

    private boolean activationPromptReady() {
        return activatePromptAt != 0L && clientTime() - activatePromptAt >= activationTime.getValue();
    }

    /** 计时过 85% 后先吞掉右键，避免玩家提前按住右键把放置窗口提前打开。 */
    private boolean activationSuppressUse() {
        return activatePromptAt != 0L
                && clientTime() - activatePromptAt >= (long) (activationTime.getValue() * 0.85);
    }

    private void updateActivationPrompt() {
        LocalPlayer player = mc.player;
        if (player == null || mc.gui.screen() != null) {
            clearActivationPrompt();
            return;
        }

        setActivationMovementHold(activationPromptReady() && physicalRightMouseDown());

        boolean lookingDown = player.getXRot() >= activationPitch();
        boolean atEdge = lookingDown && isLookingAtEdge(player);
        pushActivationDiagnostics(player);

        if (player.isShiftKeyDown() && atEdge) {
            if (activatePromptAt == 0L) {
                activatePromptAt = clientTime();
                dbg("ARM sneak-start pos=" + fmtPos(player)
                        + " yaw=" + String.format(Locale.ROOT, "%.2f", player.getYRot())
                        + " pitch=" + String.format(Locale.ROOT, "%.2f", player.getXRot())
                        + " onGround=" + player.onGround());
            }
            if (activationSuppressUse()) setPressed("use", false);
            aimAtActivationGrid(player);
            if (activationPromptReady() && physicalRightMouseDown()) {
                // 蹲着 + 对准绿框 + 按住右键 = 直接触发；触发后右键可随意松开。
                disableSafeWalkForRun();
                enforceSafeWalkDisabledForRun();
                rememberActivationPromptColor();
                activatePromptAt = 0L;
                beginAutomation();
                if (!running) setPressed("use", false);
                return;
            }
            if (safeWalkStateCaptured) restoreSafeWalkState();
            return;
        }

        // 一旦离开「蹲着 + 对准」，计时作废（触发只可能发生在该状态下按住右键的那一刻）。
        clearActivationPrompt();
    }

    /**
     * 按住潜行计时期间，把视角平滑推向「{@code Snap Degrees} 网格」（默认 44°+k·90°）。
     *
     * <p>为什么需要：激活时 {@code baseYaw} 就取这个网格值，它决定桥的走向与整条 21 帧曲线序列。
     * 触发瞬间若带几度偏差，整轮搭桥都会扛着同一个偏置。
     *
     * <p>约束：校正量超过 {@code Snap Range} 直接放弃（说明玩家在找位置，别扳他）；
     * 每 tick 最多走 {@code Snap Step} 度，平滑推入而非瞬移。
     */
    private void aimAtActivationGrid(LocalPlayer player) {
        float base = snapDegrees.getValue().floatValue();
        float target = Math.round((player.getYRot() - base) / 90.0f) * 90.0f + base;
        float delta = tellyWrapAngle(target - player.getYRot());
        float snapRangeValue = snapRange.getValue().floatValue();
        float snapStepValue = snapStep.getValue().floatValue();
        if (Math.abs(delta) > snapRangeValue || Math.abs(delta) < 0.02f) return;
        player.setYRot(player.getYRot() + clampFloat(delta, -snapStepValue, snapStepValue));
    }

    private void clearActivationPrompt() {
        rememberActivationPromptColor();
        if (activationSuppressUse()) {
            setPressed("use", false);
        }
        activatePromptAt = 0L;
        promptBrokeAt = 0L;
        setActivationMovementHold(false);
        if (!running) restoreSafeWalkState();
    }

    private void rememberActivationPromptColor() {
        if (activatePromptAt != 0L) {
            promptFadeRgb = activationPromptReady() ? 0x55FF55 : 0xFF5555;
        }
    }

    private int[] travelDirectionFromYaw(float yaw) {
        double radians = Math.toRadians(yaw);
        double rawX = Math.sin(radians) - Math.cos(radians);
        double rawZ = -Math.cos(radians) - Math.sin(radians);
        if (Math.abs(rawX) >= Math.abs(rawZ)) return new int[]{rawX >= 0.0 ? 1 : -1, 0};
        return new int[]{0, rawZ >= 0.0 ? 1 : -1};
    }

    private boolean isLookingAtEdge(LocalPlayer player) {
        if (!isActivationYawAligned(player.getYRot())) return false;
        Object[] hit = hostRaycast(4.5);
        if (hit == null || hit.length < 3 || hit[0] == null || hit[1] == null || hit[2] == null) return false;

        int face = faceFromName((String) hit[2]);
        if (face < 2) return false;
        if (!isInActivationFaceCenter(face, (Vec3) hit[1])) return false;

        int[] travel = travelDirectionFromYaw(player.getYRot());
        int travelFace = travel[0] > 0 ? 5 : travel[0] < 0 ? 4 : travel[1] > 0 ? 3 : 2;
        if (face != travelFace) return false;

        int[] pos = posFromVec((Vec3) hit[0]);
        if (!isPlayerOnActivationBlock(player, pos)) return false;
        int aheadX = pos[0] + travel[0];
        int aheadZ = pos[2] + travel[1];
        if (!isReplaceableName(blockNameAt(aheadX, pos[1] + 1, aheadZ), false)) return false;

        Vec3 playerPos = player.position();
        double lipDistance;
        if (face == 5) lipDistance = (pos[0] + 1) - playerPos.x;
        else if (face == 4) lipDistance = playerPos.x - pos[0];
        else if (face == 3) lipDistance = (pos[2] + 1) - playerPos.z;
        else lipDistance = playerPos.z - pos[2];
        return lipDistance <= 0.65;
    }

    private boolean isActivationYawAligned(float yaw) {
        float nearestDiagonal = Math.round((yaw - 45.0f) / 90.0f) * 90.0f + 45.0f;
        return Math.abs(tellyWrapAngle(yaw - nearestDiagonal)) <= ACTIVATION_YAW_TOLERANCE;
    }

    private boolean isPlayerOnActivationBlock(LocalPlayer player, int[] pos) {
        if (pos == null) return false;
        Vec3 playerPos = player.position();
        if (pos[1] != floor(playerPos.y - 0.01)) return false;
        double centerX = pos[0] + 0.5;
        double centerZ = pos[2] + 0.5;
        return Math.abs(playerPos.x - centerX) <= 0.85
                && Math.abs(playerPos.z - centerZ) <= 0.85;
    }

    // 只允许在朝外表面的下半部分激活，并排除其内侧。
    private boolean isInActivationFaceCenter(int face, Vec3 localHit) {
        if (localHit == null) return false;
        double acrossFace = (face == 4 || face == 5) ? localHit.z : localHit.x;
        if (face == 3 || face == 4) acrossFace = 1.0 - acrossFace;
        return acrossFace >= ACTIVATION_ACROSS_MIN && acrossFace <= ACTIVATION_ACROSS_MAX
                && localHit.y >= ACTIVATION_HEIGHT_MIN && localHit.y <= ACTIVATION_HEIGHT_MAX;
    }

    /**
     * 临时排障：逐项列出 {@link #isLookingAtEdge} 的九项判据与实测数值。
     * 只读——不改变任何判定、阈值或显示逻辑，确认问题后可整段删除。
     */
    private void pushActivationDiagnostics(LocalPlayer player) {
        if (running) {
            activationDiagnosticsLine = String.format(Locale.ROOT,
                    "跑[%d] 放:%d 失:%d 脚空:%s 摆:%+.2f 目标p:%.0f 目标y:%.0f",
                    runDiagPhase, runDiagPlaceOk, runDiagPlaceFail,
                    runDiagBelowAir ? "√" : "×", runDiagSway, runDiagTargetPitch, runDiagTargetYaw);
            return;
        }
        if (!armed) {
            activationDiagnosticsLine = null;
            return;
        }
        if (!player.isShiftKeyDown() && player.getXRot() < 60.0f) {
            activationDiagnosticsLine = null;
            return;
        }
        long now = clientTime();
        if (now - lastActivationDiagnosticsAt < ACTIVATION_DIAGNOSTICS_INTERVAL_MS) return;
        lastActivationDiagnosticsAt = now;
        activationDiagnosticsLine = buildActivationDiagnostics(player);
    }

    private String buildActivationDiagnostics(LocalPlayer player) {
        float yaw = player.getYRot();
        float pitch = player.getXRot();
        StringBuilder sb = new StringBuilder();
        sb.append(pitch >= activationPitch() ? "√低头" : "×低头");
        sb.append(String.format(Locale.ROOT, "%.0f ", pitch));

        float nearestDiagonal = Math.round((yaw - 45.0f) / 90.0f) * 90.0f + 45.0f;
        float yawDelta = Math.abs(tellyWrapAngle(yaw - nearestDiagonal));
        sb.append(yawDelta <= ACTIVATION_YAW_TOLERANCE ? "√对角" : "×对角");
        sb.append(String.format(Locale.ROOT, "%.2f ", yawDelta));

        Object[] hit = hostRaycast(4.5);
        sb.append(hit != null ? "√命中 " : "×命中 ");
        if (hit != null) {
            int face = faceFromName((String) hit[2]);
            Vec3 local = (Vec3) hit[1];
            sb.append(face >= 2 ? "√侧面" : "×侧面").append(face).append(' ');

            double across = (face == 4 || face == 5) ? local.z : local.x;
            if (face == 3 || face == 4) across = 1.0 - across;
            boolean inRegion = across >= ACTIVATION_ACROSS_MIN && across <= ACTIVATION_ACROSS_MAX
                    && local.y >= ACTIVATION_HEIGHT_MIN && local.y <= ACTIVATION_HEIGHT_MAX;
            sb.append(inRegion ? "√区域" : "×区域");
            sb.append(String.format(Locale.ROOT, "a=%.2f y=%.2f ", across, local.y));

            int[] travel = travelDirectionFromYaw(yaw);
            int travelFace = travel[0] > 0 ? 5 : travel[0] < 0 ? 4 : travel[1] > 0 ? 3 : 2;
            sb.append(face == travelFace ? "√朝向面" : "×朝向面").append(travelFace).append(' ');

            int[] pos = posFromVec((Vec3) hit[0]);
            sb.append(isPlayerOnActivationBlock(player, pos) ? "√站块 " : "×站块 ");

            Vec3 playerPos = player.position();
            double lip;
            if (face == 5) lip = (pos[0] + 1) - playerPos.x;
            else if (face == 4) lip = playerPos.x - pos[0];
            else if (face == 3) lip = (pos[2] + 1) - playerPos.z;
            else lip = playerPos.z - pos[2];
            sb.append(lip <= 0.65 ? "√唇距" : "×唇距");
            sb.append(String.format(Locale.ROOT, "%.2f ", lip));
        }
        sb.append(player.isShiftKeyDown() ? "潜行中" : "未潜行");
        sb.append(physicalRightMouseDown() ? " 右键√" : " 右键×");
        if (activatePromptAt == 0L) {
            sb.append(" 未计时");
        } else if (activationPromptReady()) {
            sb.append(" 计时√可触发");
        } else {
            sb.append(String.format(Locale.ROOT, " 计时%.0f%%",
                    (clientTime() - activatePromptAt) * 100.0 / Math.max(1, activationTime.getValue())));
        }
        return sb.toString();
    }

    private void onRenderWorld() {
        if (!showActivationHitbox.getValue()) return;
        if (!armed || running) return;
        if (promptAlpha < 0.05f) return;

        if (activatePromptAt != 0L) {
            Object[] hit = hostRaycast(4.5);
            if (hit != null && hit.length >= 3 && hit[0] != null && hit[2] != null) {
                int face = faceFromName((String) hit[2]);
                if (face >= 2) {
                    hitboxLastPos = posFromVec((Vec3) hit[0]);
                    hitboxLastFace = face;
                }
            }
        }

        if (hitboxLastPos == null || hitboxLastFace < 2) return;
        drawActivationFaceRegion(hitboxLastPos, hitboxLastFace);
    }

    private void drawActivationFaceRegion(int[] pos, int face) {
        double yMin = pos[1] + ACTIVATION_HEIGHT_MIN;
        double yMax = pos[1] + ACTIVATION_HEIGHT_MAX;
        double x1, z1, x2, z2;

        if (face == 5) {
            x1 = pos[0] + 1.005;
            x2 = x1;
            z1 = pos[2] + ACTIVATION_ACROSS_MIN;
            z2 = pos[2] + ACTIVATION_ACROSS_MAX;
        } else if (face == 4) {
            x1 = pos[0] - 0.005;
            x2 = x1;
            z1 = pos[2] + (1.0 - ACTIVATION_ACROSS_MAX);
            z2 = pos[2] + (1.0 - ACTIVATION_ACROSS_MIN);
        } else if (face == 3) {
            z1 = pos[2] + 1.005;
            z2 = z1;
            x1 = pos[0] + (1.0 - ACTIVATION_ACROSS_MAX);
            x2 = pos[0] + (1.0 - ACTIVATION_ACROSS_MIN);
        } else {
            z1 = pos[2] - 0.005;
            z2 = z1;
            x1 = pos[0] + ACTIVATION_ACROSS_MIN;
            x2 = pos[0] + ACTIVATION_ACROSS_MAX;
        }

        int r = (promptFadeRgb >> 16) & 0xFF;
        int g = (promptFadeRgb >> 8) & 0xFF;
        int b = promptFadeRgb & 0xFF;
        int fillAlpha = (int) (60.0f * promptAlpha);
        int lineAlpha = (int) (220.0f * promptAlpha);
        if (fillAlpha < 4) fillAlpha = 4;
        if (lineAlpha < 16) lineAlpha = 16;

        AABB box = new AABB(
                Math.min(x1, x2), yMin, Math.min(z1, z2),
                Math.max(x1, x2), yMax, Math.max(z1, z2)
        );
        Render3DScheduler.INSTANCE.addFilledBox(box, new Color(r, g, b, fillAlpha));
        Render3DScheduler.INSTANCE.addOutlineBox(box, new Color(r, g, b, lineAlpha), 2.0f);
    }

    private boolean drawActivatePrompt(TextRenderer renderer) {
        String text = "Activate?";
        int alpha = (int) (promptAlpha * 255.0f);
        if (alpha < 16) alpha = 16;
        int color = (alpha << 24) | promptFadeRgb;
        float width = renderer.getWidth(text, 1.0f);
        float x = mc.getWindow().getGuiScaledWidth() / 2.0f - width / 2.0f;
        float y = mc.getWindow().getGuiScaledHeight() / 2.0f + 10.0f;
        renderer.addText(text, x, y, 1.0f, new Color(color, true));
        return true;
    }

    /** 激活诊断：屏幕底部一行，不写聊天栏。 */
    private boolean drawDiagnosticsLine(TextRenderer renderer, String line) {
        float width = renderer.getWidth(line, 1.0f);
        float x = mc.getWindow().getGuiScaledWidth() / 2.0f - width / 2.0f;
        float y = mc.getWindow().getGuiScaledHeight() - 32.0f;
        renderer.addText(line, x, y, 1.0f, new Color(0xFFFFFFFF, true));
        return true;
    }

    private void updateActivatePromptFade() {
        boolean show = armed && !running && activatePromptAt != 0L;
        if (show) rememberActivationPromptColor();

        long now = clientTime();
        long elapsed = promptFadeLastAt == 0L ? 0L : Math.min(100L, now - promptFadeLastAt);
        promptFadeLastAt = now;
        float step = elapsed / 200.0f;
        promptAlpha += show ? step : -step;
        if (promptAlpha < 0.0f) promptAlpha = 0.0f;
        if (promptAlpha > 1.0f) promptAlpha = 1.0f;
    }

    // =====================================================================
    // 输入过滤（脚本 435-597）
    // =====================================================================

    private boolean onMouse(int button, boolean state) {
        if (running) {
            if (button == 0) {
                setPressed("attack", false);
                return false;
            }
            if (button == 1) {
                setPressed("use", tellyAutoPlaceWindow);
                return false;
            }
            return autoPlaceOnMouse(button, state);
        }
        if (armed && button == 1 && !state) setActivationMovementHold(false);
        if (armed && activationSuppressUse() && button == 1) return false;
        return true;
    }

    private boolean onKey(int keyCode, boolean state, boolean inGui) {
        boolean dropKey = keyCode == keyCode("drop") || isDropKeyName(keyCode);
        if (isDropProtected() && dropKey) {
            setPressed("drop", false);
            return false;
        }
        if (!running && activationMovementHeld && !state
                && (keyCode == keyCode("back")
                || keyCode == keyCode("right"))) {
            setPressed("back", true);
            setPressed("right", true);
            return false;
        }
        if (!running) return true;
        if (keyCode == keyCode("sneak")) {
            suppressSneakInput();
            return false;
        }
        if (!state) clearInitialMovementHold(keyCode);
        if (state
                && setupTick < 0
                && isManualMovementKey(keyCode)
                && !isInitialMovementHold(keyCode)
                && !isScriptHeldKey(keyCode)) {
            stopAutomation(true);
            return true;
        }
        if (inGui) return true;
        if (isManualMovementKey(keyCode)) return false;
        return true;
    }

    private void setActivationMovementHold(boolean hold) {
        if (hold) {
            activationMovementHeld = true;
            setPressed("back", true);
            setPressed("right", true);
            return;
        }
        if (!activationMovementHeld) return;
        activationMovementHeld = false;
        setPressed("back", isPhysicalKeyDown("back"));
        setPressed("right", isPhysicalKeyDown("right"));
    }

    private boolean isScriptHeldKey(int keyCode) {
        if (keyCode == keyCode("forward")) return isPressed("forward");
        if (keyCode == keyCode("back")) return isPressed("back");
        if (keyCode == keyCode("left")) return isPressed("left");
        if (keyCode == keyCode("right")) return isPressed("right");
        if (keyCode == keyCode("jump")) return isPressed("jump");
        if (keyCode == keyCode("sprint")) return isPressed("sprint");
        return false;
    }

    private boolean isManualMovementKey(int keyCode) {
        return keyCode == keyCode("forward")
                || keyCode == keyCode("back")
                || keyCode == keyCode("left")
                || keyCode == keyCode("right")
                || keyCode == keyCode("jump")
                || keyCode == keyCode("sneak")
                || keyCode == keyCode("sprint");
    }

    private void captureInitialMovementHolds() {
        ignoreForwardUntilRelease = isPhysicalKeyDown("forward");
        ignoreBackUntilRelease = isPhysicalKeyDown("back");
        ignoreLeftUntilRelease = isPhysicalKeyDown("left");
        ignoreRightUntilRelease = isPhysicalKeyDown("right");
        ignoreJumpUntilRelease = isPhysicalKeyDown("jump");
        ignoreSneakUntilRelease = isPhysicalKeyDown("sneak");
        ignoreSprintUntilRelease = isPhysicalKeyDown("sprint");
    }

    private boolean isInitialMovementHold(int keyCode) {
        if (keyCode == keyCode("forward")) return ignoreForwardUntilRelease;
        if (keyCode == keyCode("back")) return ignoreBackUntilRelease;
        if (keyCode == keyCode("left")) return ignoreLeftUntilRelease;
        if (keyCode == keyCode("right")) return ignoreRightUntilRelease;
        if (keyCode == keyCode("jump")) return ignoreJumpUntilRelease;
        if (keyCode == keyCode("sneak")) return ignoreSneakUntilRelease;
        if (keyCode == keyCode("sprint")) return ignoreSprintUntilRelease;
        return false;
    }

    private void clearInitialMovementHold(int keyCode) {
        if (keyCode == keyCode("forward")) ignoreForwardUntilRelease = false;
        if (keyCode == keyCode("back")) ignoreBackUntilRelease = false;
        if (keyCode == keyCode("left")) ignoreLeftUntilRelease = false;
        if (keyCode == keyCode("right")) ignoreRightUntilRelease = false;
        if (keyCode == keyCode("jump")) ignoreJumpUntilRelease = false;
        if (keyCode == keyCode("sneak")) ignoreSneakUntilRelease = false;
        if (keyCode == keyCode("sprint")) ignoreSprintUntilRelease = false;
    }

    private void clearInitialMovementHolds() {
        ignoreForwardUntilRelease = false;
        ignoreBackUntilRelease = false;
        ignoreLeftUntilRelease = false;
        ignoreRightUntilRelease = false;
        ignoreJumpUntilRelease = false;
        ignoreSneakUntilRelease = false;
        ignoreSprintUntilRelease = false;
    }

    private boolean detectManualCameraTakeover() {
        if (!running || setupTick >= 0 || clientTime() < takeoverDetectionAt) return false;
        LocalPlayer player = mc.player;
        if (player == null) return false;

        long now = clientTime();
        float expectedYaw = scriptedRotationYaw;
        float expectedPitch = scriptedRotationPitch;
        if (!takeoverCameraValid) {
            takeoverCameraValid = true;
            takeoverCameraYaw = player.getYRot();
            takeoverCameraPitch = player.getXRot();
            takeoverAccumulated = 0.0f;
            takeoverLastFrameAt = now;
            return false;
        }

        double yawInput = Math.abs(tellyWrapAngle(player.getYRot() - expectedYaw));
        double pitchInput = Math.abs(player.getXRot() - expectedPitch);

        takeoverCameraYaw = player.getYRot();
        takeoverCameraPitch = player.getXRot();
        takeoverLastFrameAt = now;

        // 见 Takeover Degrees 设置：超过该角度才判定为玩家接管（脚本原值 ≈0.015° 累积到 25）。
        double takeoverLimit = takeoverDegrees.getValue();
        if (yawInput > takeoverLimit || pitchInput > takeoverLimit) {
            stopAutomation(true);
            return true;
        }
        return false;
    }

    // =====================================================================
    // 发包拦截（脚本 598-642、727-740）
    // =====================================================================

    private boolean onPacketSent(Packet<?> packet) {
        if (isDropProtected() && packet instanceof ServerboundPlayerActionPacket digging) {
            String status = digging.getAction() == null ? "" : digging.getAction().name().toUpperCase(Locale.ROOT);
            if (status.contains("DROP")) return false;
        }
        if (!running) return true;
        if (packet instanceof ServerboundAttackPacket) {
            return false;
        }
        if (packet instanceof ServerboundPlayerActionPacket digging) {
            String status = digging.getAction() == null ? "" : digging.getAction().name().toUpperCase(Locale.ROOT);
            if (status.contains("DESTROY")) return false;
        }
        if (packet instanceof ServerboundPlayerCommandPacket action) {
            String name = action.getAction() == null ? "" : action.getAction().name();
            if ("START_SNEAKING".equals(name)) return false;
        }
        int[] placedTarget = null;
        if (packet instanceof ServerboundUseItemOnPacket placement) {
            BlockHitResult hit = placement.getHitResult();
            if (hit != null && hit.getType() != HitResult.Type.MISS && hit.getBlockPos() != null) {
                placedTarget = offsetPos(posFromBlockPos(hit.getBlockPos()), hit.getDirection().get3DDataValue());
                if (!isStraightTellyTarget(placedTarget)) {
                    cancelledGhostBlocks.add(posKey(placedTarget));
                    return false;
                }
            }
        }

        boolean allowed = autoPlaceOnPacketSent(packet);
        if (allowed && placedTarget != null) {
            cancelledGhostBlocks.remove(posKey(placedTarget));
            latestStraightPlacedPos = new int[]{placedTarget[0], placedTarget[1], placedTarget[2]};
            if (firstTellyPlacementPending && setupTick < 0) {
                firstTellyPlacementPending = false;
                adaptiveAimValid = false;
                adaptiveAimUpdatedAt = 0L;
            }
        }
        return allowed;
    }

    private boolean isActivationInProgress() {
        return armed && !running && activatePromptAt != 0L;
    }

    private boolean isDropProtected() {
        return running || isActivationInProgress();
    }

    private boolean onPacketReceived(Packet<?> packet) {
        if (running && packet instanceof ClientboundPlayerPositionPacket) {
            stopAutomation(true);
            return true;
        }
        if (packet instanceof ClientboundBlockUpdatePacket change && change.getPos() != null) {
            int[] changed = posFromBlockPos(change.getPos());
            cancelledGhostBlocks.remove(posKey(changed));

            // 「吞方块」诊断：联机时我们铺好的那一格被服务端改回空气/可替换方块。
            // 客户端因为本地预测会先看到方块出现，所以日志里 placedOk 与 placeFail 都正常，
            // 只有这里能抓到服务端回撤 —— 而桥面出现缺口正是随后踩空（fall=7.52）的直接原因。
            // 注意：此时 PacketEvent.Receive 还在 HEAD，客户端世界尚未应用这个包，
            // 所以要读包里的 state，不能去查 mc.level。
            BlockState changedState = change.getBlockState();
            boolean becamePassable = changedState == null
                    || changedState.isAir()
                    || changedState.canBeReplaced();
            if (running && becamePassable && recentPlacedKeys.contains(posKey(changed))) {
                dbg("SERVER-SWALLOW at=" + java.util.Arrays.toString(changed)
                        + " name=" + blockNameAt(changed[0], changed[1], changed[2])
                        + " tick=" + currentClientTick);
            }
        }
        return true;
    }

    // =====================================================================
    // 启停与状态（脚本 741-876）
    // =====================================================================

    private void armAutomation() {
        armed = true;
        running = false;
        activatePromptAt = 0L;
        promptBrokeAt = 0L;
        setupTick = 0;
        cyclePhase = 19;
        rotationActive = false;
        activationMovementHeld = false;
        printStatus("&eArmed. Sneak looking down, wait for green, hold rmb and release sneak");
    }

    private void beginAutomation() {
        LocalPlayer player = mc.player;
        if (player == null || !(player.getMainHandItem().getItem() instanceof BlockItem)) {
            printStatus("&cHold blocks before starting");
            return;
        }
        if (!isActivationYawAligned(player.getYRot())) return;

        disableSafeWalkForRun();
        dbg("BEGIN yaw=" + String.format(Locale.ROOT, "%.2f", player.getYRot())
                + " pitch=" + String.format(Locale.ROOT, "%.2f", player.getXRot())
                + " pos=" + fmtPos(player)
                + " v=" + String.format(Locale.ROOT, "%.3f,%.3f,%.3f",
                        player.getDeltaMovement().x, player.getDeltaMovement().y, player.getDeltaMovement().z)
                + " onGround=" + player.onGround()
                + " sneak=" + player.isShiftKeyDown()
                + " fall=" + String.format(Locale.ROOT, "%.2f", player.fallDistance));
        // 对齐到「Snap Degrees」网格（默认 44°+k·90°），与按住潜行期间的吸附保持一致：
        // 触发瞬间的瞄准偏差绝不能固化进 baseYaw，否则每一刻的旋转目标都带同一偏差，桥会越搭越歪。
        // 用 44 而非 45 —— 既躲开整度数特征（Intave 的 computer-like），又避开 45° 这个
        // calculateTravelDirection 的象限零点（浮点抖动会让 travelX/travelZ 两侧跳）。
        float alignBase = snapDegrees.getValue().floatValue();
        float alignedYaw = Math.round((player.getYRot() - alignBase) / 90.0f) * 90.0f + alignBase;
        baseYaw = alignedYaw;
        player.setYRot(alignedYaw);
        setActivationMovementHold(false);
        calculateTravelDirection(baseYaw);
        // ⚠️ 这里必须取[玩家精确坐标]，不是方块中心 —— 看似"偏差 0.5 格"像 bug，其实是设计。
        //
        // 曾改成 Math.floor(rawLane) + 0.5 让玩家居中：antiSway 的误差确实收敛到 0 了
        // （落点日志 err 从 ±0.5 变成 ≈0），但 placedOk 立刻从 100+ 掉到 19/37 ——
        // 因为 telly 的本质是「站在方块边缘向后搭」，贴着边缘时下一格才在够得着的范围内，
        // 居中等于把放置距离拉远半格，桥就接不上。
        antiSwayLane = travelX != 0 ? player.position().z : player.position().x;
        antiSwayYawOffset = 0.0f;
        antiSwayTapUsed = false;
        cancelledGhostBlocks.clear();
        initializeStraightBridgeLane(player);
        firstTellyPlacementPending = false;
        adaptiveAimValid = false;
        adaptiveAimUpdatedAt = 0L;
        setupTick = 0;
        cyclePhase = 19;
        armed = false;
        running = true;
        freezeLastTickAt = clientTime();
        activationMovementHeld = false;
        tellyAutoPlaceWindow = true;
        scriptedRotationYaw = player.getYRot();
        scriptedRotationPitch = player.getXRot();
        takeoverDetectionAt = 0L;
        takeoverCameraValid = false;
        clearInitialMovementHolds();
        resetControllerState();
        setPressed("attack", false);
        applyMovement(-1.0f, -1.0f, false, false);
        setRotationTarget(baseYaw, 74.52f, 50L);
        applyUse(true);
        printStatus("&aStarted");
    }

    private void stopAutomation(boolean turnOffButton) {
        stopAutomation(turnOffButton, callerHint());
    }

    /** 调用者提示：用栈帧给出"谁按停的"（方法名 + 行号），免去给每个调用点加参数。 */
    private static String callerHint() {
        for (StackTraceElement frame : Thread.currentThread().getStackTrace()) {
            if (frame.getClassName().contains("Telly")
                    && !frame.getMethodName().startsWith("stopAutomation")
                    && !frame.getMethodName().equals("callerHint")) {
                return frame.getMethodName() + ":" + frame.getLineNumber();
            }
        }
        return "unknown";
    }

    /** 便于定位"跑几秒就自己停"：把停止原因、相位、位置、累计放置数一并写进日志。 */
    private void stopAutomation(boolean turnOffButton, String reason) {
        if (running) {
            LocalPlayer p = mc.player;
            dbg("STOP reason=" + reason
                    + " phase=" + cyclePhase
                    + " setupTick=" + setupTick
                    + " tick=" + currentClientTick
                    + " placedOk=" + runDiagPlaceOk
                    + " placeFail=" + runDiagPlaceFail
                    + " failHist=" + placementFailCounts
                    + " pos=" + (p == null ? "null" : fmtPos(p))
                    + " vH=" + (p == null ? "-" : String.format(Locale.ROOT, "%.3f", p.getDeltaMovement().horizontal().length()))
                    + " fall=" + (p == null ? "-" : String.format(Locale.ROOT, "%.2f", p.fallDistance))
                    + " holdBlock=" + (p != null && p.isHolding(stack -> stack.getItem() instanceof BlockItem)));
        }
        stopAutomationInner(turnOffButton);
    }

    private void stopAutomationInner(boolean turnOffButton) {
        placementFailCounts.clear();
        armed = false;
        running = false;
        setupTick = 0;
        cyclePhase = 19;
        rotationActive = false;
        activationMovementHeld = false;
        tellyAutoPlaceWindow = false;
        autoPlaceDebugActive = false;
        antiSwayYawOffset = 0.0f;
        antiSwayTapUsed = false;
        firstTellyPlacementPending = false;
        latestStraightPlacedPos = null;
        adaptiveAimValid = false;
        adaptiveAimUpdatedAt = 0L;
        scriptedRotationYaw = 0.0f;
        scriptedRotationPitch = 0.0f;
        takeoverDetectionAt = 0L;
        takeoverCameraValid = false;
        takeoverCameraYaw = 0.0f;
        takeoverCameraPitch = 0.0f;
        takeoverAccumulated = 0.0f;
        takeoverLastFrameAt = 0L;

        try {
            cancelledGhostBlocks.clear();
            clearInitialMovementHolds();
            resetControllerState();
            setClientForward(0.0f);
            setClientStrafe(0.0f);
            setClientJump(false);
            setClientSprinting(false);
            releaseMovementKeys();
            restorePhysicalUse();
            setPressed("attack", physicalLeftMouseDown());
        } catch (Exception ignored) {
        }

        restoreSafeWalkState();

        freezeLastTickAt = 0L;
        armed = true;
        activatePromptAt = 0L;
        promptBrokeAt = 0L;
        if (turnOffButton) {
            printStatus("&eStopped. Sneak looking down to arm again");
        }
    }

    private void disableSafeWalkForRun() {
        if (safeWalkStateCaptured) {
            enforceSafeWalkDisabledForRun();
            return;
        }
        if (!disableSafeWalk.getValue()) return;

        try {
            Module safeWalk = findModule("SafeWalk");
            safeWalkWasEnabled = safeWalk != null && safeWalk.isEnabled();
            safeWalkStateCaptured = true;
            if (safeWalkWasEnabled) safeWalk.setEnabled(false);
        } catch (Exception ignored) {
            safeWalkStateCaptured = false;
        }
    }

    private void enforceSafeWalkDisabledForRun() {
        if (!safeWalkStateCaptured) return;
        try {
            Module safeWalk = findModule("SafeWalk");
            if (safeWalk != null && safeWalk.isEnabled()) safeWalk.setEnabled(false);
        } catch (Exception ignored) {
        }
    }

    private void restoreSafeWalkState() {
        if (!safeWalkStateCaptured) return;

        boolean restoreEnabled = safeWalkWasEnabled;
        safeWalkStateCaptured = false;
        try {
            Module safeWalk = findModule("SafeWalk");
            if (safeWalk == null) return;
            boolean currentlyEnabled = safeWalk.isEnabled();
            if (restoreEnabled && !currentlyEnabled) safeWalk.setEnabled(true);
            if (!restoreEnabled && currentlyEnabled) safeWalk.setEnabled(false);
        } catch (Exception ignored) {
        }
    }

    private void printStatus(String message) {
        try {
            if (!print.getValue()) return;
            String text = message == null ? "" : message;
            if (text.startsWith("&a")) {
                NotificationManager.INSTANCE.success("Telly", text.substring(2));
            } else if (text.startsWith("&c")) {
                NotificationManager.INSTANCE.error("Telly", text.substring(2));
            } else if (text.startsWith("&e")) {
                NotificationManager.INSTANCE.warning("Telly", text.substring(2));
            } else {
                NotificationManager.INSTANCE.info("Telly", text);
            }
        } catch (Exception ignored) {
        }
    }

    // =====================================================================
    // 旋转脚本（脚本 886-941）
    // =====================================================================

    private void setRotationTarget(float targetYaw, float targetPitch, long duration) {
        LocalPlayer player = mc.player;
        if (player == null) return;

        applySmoothedRotation();
        rotationStartYaw = player.getYRot();
        rotationStartPitch = player.getXRot();
        float correctedTargetYaw = targetYaw;
        boolean adaptivePlacementTarget = running
                && tellyAutoPlaceWindow
                && firstTellyPlacementPending
                && adaptiveAimValid
                && clientTime() - adaptiveAimUpdatedAt <= 125L;
        if (adaptivePlacementTarget) {
            correctedTargetYaw = adaptiveAimYaw;
            targetPitch = adaptiveAimPitch;
        } else if (running) {
            correctedTargetYaw += antiSwayYawOffset;
        }

        rotationStepCounter++;
        correctedTargetYaw += (float) (sensitivityQuantum.getValue() * yawNudgeScale.getValue()
                * YAW_NUDGE_PATTERN[rotationStepCounter % 5]);

        rotationTargetYaw = rotationStartYaw + tellyWrapAngle(correctedTargetYaw - rotationStartYaw);
        rotationTargetPitch = clamp(targetPitch, -90.0f, 90.0f);
        rotationStartedAt = clientTime();
        rotationDuration = Math.max(1L, duration);
        rotationActive = true;
    }

    private void applySmoothedRotation() {
        if (!rotationActive) return;
        LocalPlayer player = mc.player;
        if (player == null) return;

        double progress = (double) (clientTime() - rotationStartedAt) / (double) rotationDuration;
        if (progress < 0.0) progress = 0.0;
        if (progress > 1.0) progress = 1.0;

        float desiredYaw = rotationStartYaw + (rotationTargetYaw - rotationStartYaw) * (float) progress;
        float desiredPitch = rotationStartPitch + (rotationTargetPitch - rotationStartPitch) * (float) progress;
        float quantizedYaw = quantizeFrom(rotationStartYaw, desiredYaw);
        float quantizedPitch = quantizeFrom(rotationStartPitch, desiredPitch);

        scriptedRotationYaw = quantizedYaw;
        scriptedRotationPitch = clamp(quantizedPitch, -90.0f, 90.0f);
        player.setYRot(scriptedRotationYaw);
        player.setXRot(scriptedRotationPitch);
        if (progress >= 1.0) rotationActive = false;
    }

    private float quantizeFrom(float origin, float value) {
        double quantum = sensitivityQuantum.getValue();
        if (quantum <= 0.0) return value;
        double steps = Math.round((value - origin) / quantum);
        return (float) (origin + steps * quantum);
    }

    // =====================================================================
    // 移动与使用（脚本 942-1136）
    // =====================================================================

    private void applyMovement(float forward, float strafe, boolean jumping, boolean sprinting) {
        float controlledForward = forward;
        boolean controlledSprint = sprinting;

        float correctedStrafe = strafe;
        boolean antiSway = running;
        if (antiSway) correctedStrafe = applyAntiSwayCorrection(controlledForward, strafe);
        else antiSwayYawOffset = 0.0f;

        setPressed("forward", controlledForward > 0.03f);
        setPressed("back", controlledForward < -0.03f);
        setPressed("left", correctedStrafe > 0.5f);
        setPressed("right", correctedStrafe < -0.5f);
        setPressed("jump", jumping);
        setPressed("sprint", controlledSprint);
        setClientForward(controlledForward);
        setClientStrafe(correctedStrafe);
        setClientJump(jumping);
        setClientSneak(false);
        setClientSprinting(controlledSprint);
    }

    private void suppressSneakInput() {
        setPressed("sneak", false);
        setClientSneak(false);
    }

    private void calculateTravelDirection(float yaw) {
        double radians = Math.toRadians(yaw);
        double rawX = Math.sin(radians) - Math.cos(radians);
        double rawZ = -Math.cos(radians) - Math.sin(radians);

        if (Math.abs(rawX) >= Math.abs(rawZ)) {
            travelX = rawX >= 0.0 ? 1 : -1;
            travelZ = 0;
        } else {
            travelX = 0;
            travelZ = rawZ >= 0.0 ? 1 : -1;
        }
    }

    private void initializeStraightBridgeLane(LocalPlayer player) {
        Vec3 position = player.position();
        int startX = floor(position.x);
        int startY = floor(position.y) - 1;
        int startZ = floor(position.z);
        bridgeLaneBlock = travelX != 0 ? startZ : startX;
        bridgeStartProgress = startX * travelX + startZ * travelZ;

        Object[] hit = hostRaycast(4.5);
        if (hit != null && hit.length > 0 && hit[0] instanceof Vec3) {
            int[] hitPos = posFromVec((Vec3) hit[0]);
            int hitLane = travelX != 0 ? hitPos[2] : hitPos[0];
            int hitProgress = straightProgress(hitPos);
            if (hitLane == bridgeLaneBlock
                    && Math.abs(hitPos[0] - startX) <= 2
                    && Math.abs(hitPos[2] - startZ) <= 2
                    && hitProgress < bridgeStartProgress) {
                bridgeStartProgress = hitProgress;
            }
        }

        latestStraightPlacedPos = new int[]{startX, startY, startZ};
    }

    private int straightProgress(int[] position) {
        if (position == null) return Integer.MIN_VALUE;
        return position[0] * travelX + position[2] * travelZ;
    }

    private boolean isStraightTellyTarget(int[] position) {
        if (!running || position == null) return true;
        int lane = travelX != 0 ? position[2] : position[0];
        if (lane != bridgeLaneBlock) return false;
        return straightProgress(position) >= bridgeStartProgress;
    }

    private void updateAdaptivePlacementAim(LocalPlayer player) {
        if (!firstTellyPlacementPending) return;
        Object[] candidate = cachedCandidate;
        if (candidate != null) {
            int[] target = candidatePlacedPos(candidate);
            Vec3 hitVec = candidateHitVec(candidate);
            if (isStraightTellyTarget(target) && hitVec != null) {
                setAdaptiveAimToPoint(player, hitVec);
                return;
            }
        }

        int[] support = latestStraightPlacedPos != null ? latestStraightPlacedPos : lastPlacedPos;
        if (support == null || !isStraightTellyTarget(support)) return;
        int face = travelX > 0 ? 5 : travelX < 0 ? 4 : travelZ > 0 ? 3 : 2;
        int[] nextTarget = offsetPos(support, face);
        if (!isStraightTellyTarget(nextTarget) || !isReplaceable(nextTarget[0], nextTarget[1], nextTarget[2])) return;
        Vec3 fallbackHit = getSupportFaceHitVec(support, face, 0.5, 0.5);
        setAdaptiveAimToPoint(player, fallbackHit);
    }

    private void setAdaptiveAimToPoint(LocalPlayer player, Vec3 point) {
        if (player == null || point == null) return;
        Vec3 position = player.position();
        double eyeX = position.x;
        double eyeY = player.position().y + player.getEyeHeight();
        double eyeZ = position.z;
        double dx = point.x - eyeX;
        double dy = point.y - eyeY;
        double dz = point.z - eyeZ;
        double horizontal = Math.sqrt(dx * dx + dz * dz);
        if (horizontal < 1.0E-5 && Math.abs(dy) < 1.0E-5) return;

        adaptiveAimYaw = (float) (Math.toDegrees(Math.atan2(dz, dx)) - 90.0);
        adaptiveAimPitch = clamp((float) (-Math.toDegrees(Math.atan2(dy, horizontal))), -89.0f, 89.0f);
        adaptiveAimUpdatedAt = clientTime();
        adaptiveAimValid = true;
    }

    private float applyAntiSwayCorrection(float forward, float recordedStrafe) {
        LocalPlayer player = mc.player;
        if (player == null) return recordedStrafe;

        Vec3 position = player.position();
        Vec3 motion = player.getDeltaMovement();
        double lanePosition = travelX != 0 ? position.z : position.x;
        double laneVelocity = motion == null ? 0.0 : (travelX != 0 ? motion.z : motion.x);
        double error = antiSwayLane - lanePosition;

        if (Math.abs(error) < 0.015 && Math.abs(laneVelocity) < 0.008) {
            antiSwayTapUsed = false;
            antiSwayYawOffset *= 0.65f;
            if (Math.abs(antiSwayYawOffset) < 0.03f) antiSwayYawOffset = 0.0f;
            return recordedStrafe;
        }

        double maxVelocity = swayMaxVelocity.getValue();
        double desiredLaneVelocity = error * swayResponse.getValue() - laneVelocity * swayDamping.getValue();
        if (desiredLaneVelocity > maxVelocity) desiredLaneVelocity = maxVelocity;
        if (desiredLaneVelocity < -maxVelocity) desiredLaneVelocity = -maxVelocity;
        double velocityCorrection = desiredLaneVelocity - laneVelocity;

        // 导数按「稳定朝向」算：走位方向确实随 YAW_CURVE 摆动（见曲线处的机制说明），
        // 但摆动是周期对称的；用瞬时相机角会把 sin/cos 一起带进抖动，算出的修正量方向不稳，
        // 横向误差就一点点累积起来（实测：每格只偏一丁点，走几十格后明显跑偏）。
        double radians = Math.toRadians(baseYaw);
        double sin = Math.sin(radians);
        double cos = Math.cos(radians);
        double yawLaneDerivative = travelX != 0
                ? -forward * sin + recordedStrafe * cos
                : -forward * cos - recordedStrafe * sin;
        double desiredYawOffset = 0.0;
        if (Math.abs(yawLaneDerivative) >= 0.12) {
            desiredYawOffset = Math.toDegrees(velocityCorrection * swayGain.getValue() / yawLaneDerivative);
        }
        double yawLimit = swayYawLimit.getValue();
        if (desiredYawOffset > yawLimit) desiredYawOffset = yawLimit;
        if (desiredYawOffset < -yawLimit) desiredYawOffset = -yawLimit;
        antiSwayYawOffset = antiSwayYawOffset * 0.60f + (float) desiredYawOffset * 0.40f;

        double strafeLaneAxis = travelX != 0 ? sin : cos;
        boolean tapHelps = Math.abs(strafeLaneAxis) >= 0.20 && velocityCorrection * strafeLaneAxis > 0.0;
        if (tapHelps
                && !antiSwayTapUsed
                && Math.abs(velocityCorrection) >= 0.03
                && recordedStrafe < 0.5f) {
            antiSwayTapUsed = true;
            return recordedStrafe + 1.0f;
        }

        return recordedStrafe;
    }

    private void applyUse(boolean pressed) {
        if (pressed && !autoPlaceDebugActive) {
            printStatus("&aAutoPlace activated");
        }
        autoPlaceDebugActive = pressed;
        tellyAutoPlaceWindow = pressed;
        setPressed("use", pressed);
    }

    private void restorePhysicalUse() {
        tellyAutoPlaceWindow = false;
        autoPlaceDebugActive = false;
        setPressed("use", physicalRightMouseDown());
    }

    private void releaseMovementKeys() {
        restorePhysicalKey("forward");
        restorePhysicalKey("back");
        restorePhysicalKey("left");
        restorePhysicalKey("right");
        restorePhysicalKey("jump");
        restorePhysicalKey("sneak");
        restorePhysicalKey("sprint");
    }

    private void restorePhysicalKey(String key) {
        int code = keyCode(key);
        setPressed(key, code >= 0 && InputConstants.isKeyDown(mc.getWindow(), code));
    }

    // =====================================================================
    // 工具（脚本 1137-1205、2617-2805）
    // =====================================================================

    private float tellyWrapAngle(float angle) {
        while (angle <= -180.0f) angle += 360.0f;
        while (angle > 180.0f) angle -= 360.0f;
        return angle;
    }

    private float clamp(float value, float minimum, float maximum) {
        if (value < minimum) return minimum;
        if (value > maximum) return maximum;
        return value;
    }

    private static final double[] FACE_HIT_OFFSETS = {0.5, 0.25, 0.75, 0.15, 0.85};
    private static final double[] EXTENDED_FACE_HIT_OFFSETS = {0.5, 0.25, 0.75, 0.15, 0.85, 0.35, 0.65, 0.05, 0.95};
    private static final int[] ALLOWED_PLACE_FACES = {2, 3, 4, 5, 1};

    private static final String[] REPLACEABLE_BLOCKS = {
            "air", "cave_air", "void_air", "water", "lava", "fire",
            "short_grass", "tall_grass", "fern", "large_fern", "dead_bush",
            "snow", "vine", "seagrass", "kelp", "kelp_plant", "bubble_column", "light"
    };

    private static final String[] EXPERIMENTAL_REPLACEABLE_BLOCKS = {
            "oak_sapling", "dandelion", "poppy", "brown_mushroom", "red_mushroom",
            "wheat", "carrots", "potatoes", "nether_wart", "sugar_cane"
    };

    private static final String[] UNPLACEABLE_EXACT = {
            "snow", "cobweb", "oak_sapling", "daylight_detector", "beacon", "white_banner",
            "end_portal_frame", "end_portal", "lever", "stone_button", "oak_button",
            "skeleton_skull", "cactus", "tall_grass", "lily_pad", "white_carpet", "tripwire_hook",
            "short_grass", "dandelion", "poppy", "flower_pot", "oak_sign", "ladder",
            "torch", "redstone_torch", "gravel", "clay", "sand",
            "soul_sand", "chest", "trapped_chest", "ender_chest", "furnace",
            "jukebox", "enchanting_table", "dropper", "dispenser", "hopper", "anvil",
            "note_block", "crafting_table", "spawner", "brewing_stand", "red_bed"
    };

    private static final String[] UNPLACEABLE_CONTAINS = {
            "stairs", "slab", "fence", "pane", "rail", "door",
            "torch", "pumpkin", "flower", "sapling", "banner", "button",
            "skull", "web", "carpet", "cactus", "sign", "mushroom", "bed"
    };

    private void autoPlaceOnEnable() {
        setPressed("attack", false);
        resetControllerState();
    }

    private void autoPlaceOnDisable() {
        resetControllerState();
        restoreUseToPhysicalState();
        setPressed("attack", false);
        BRIDGE.remove("AutoPlacePlacing");
        releaseExperimentalPlacementClaim();
    }

    private void resetControllerState() {
        currentClientTick = Integer.MIN_VALUE;
        placementEvaluationTick = Integer.MIN_VALUE;
        lastPlacementAttemptTick = Integer.MIN_VALUE;
        lastSuccessfulPlaceTick = Integer.MIN_VALUE;
        forceSuppressTick = Integer.MIN_VALUE;
        totalC08Counter = 0L;
        c08CounterAtTickBoundary = 0L;
        hasLastSentServerPos = false;
        clearCachedCandidate();
        lastPlacedPos = null;
        lastSupportPos = null;
        lastSupportFace = -1;
        cachedBelowTargets = null;
        cachedBelowTargetsTick = Integer.MIN_VALUE;
        rejectedTargets.clear();
        forcedModeCheck = 0;
        useSuppressed = false;
        silentPitchActive = false;
        placingViaModule = false;
        manualC08InWindow = false;
    }

    private void autoPlaceOnWorldJoin() {
        resetControllerState();
    }

    private void autoPlaceOnPreUpdate() {
        LocalPlayer player = mc.player;
        if (player == null) return;

        syncPlacementTick(player);

        if (placementEvaluationTick != currentClientTick) {
            placementEvaluationTick = currentClientTick;
            processAutoPlaceTick(player);
        }
    }

    private void syncPlacementTick(LocalPlayer player) {
        int tick = placementTick(player);
        if (tick == currentClientTick) return;
        currentClientTick = tick;
        candidateResolvedThisTick = false;
        silentPitchActive = false;
    }

    private boolean useExtendedSearch() {
        return true;
    }

    private void autoPlaceOnPostMotion() {
        c08CounterAtTickBoundary = totalC08Counter;
        manualC08InWindow = false;
    }

    private void autoPlaceOnRenderTick() {
        LocalPlayer player = mc.player;
        if (player == null) return;
        if (!isAutoPlaceActiveWindow(player)) return;

        ItemStack heldStack = player.getMainHandItem();
        if (!isUsableBlockStack(heldStack)) return;

        float basePitch = sanitizePitch(player.getXRot(), player.getXRot());
        Object[] candidate = resolveCandidateWithOffCursorSilentPitch(player, player.getYRot(), basePitch, heldStack);
        if (candidate != null) {
            silentPitch = sanitizePitch(candidatePitch(candidate), basePitch);
            silentPitchActive = true;
            suppressUse();
        }
    }

    private void autoPlaceOnPreMotion(SendPositionEvent state) {
        if (silentPitchActive && !manualC08InWindow) {
            state.setPitch(sanitizePitch(silentPitch, state.getPitch()));
        }
    }

    private boolean autoPlaceOnPacketSent(Packet<?> packet) {
        if (packet instanceof ServerboundMovePlayerPacket c03) {
            if (c03.hasPosition()) {
                hasLastSentServerPos = true;
                lastSentServerPosX = c03.getX(0.0);
                lastSentServerPosY = c03.getY(0.0);
                lastSentServerPosZ = c03.getZ(0.0);
            }
            return true;
        }
        if (packet instanceof ServerboundUseItemOnPacket c08) {
            BlockHitResult hit = c08.getHitResult();
            boolean miss = hit == null || hit.getType() == HitResult.Type.MISS;
            if (miss) {
                if (shouldCancelAutoPlaceUseItem()) {
                    suppressUse();
                    return false;
                }
            } else {
                ItemStack held = mc.player == null ? ItemStack.EMPTY : mc.player.getMainHandItem();
                if (held.getItem() instanceof BlockItem) {
                    totalC08Counter++;
                    if (!placingViaModule) manualC08InWindow = true;
                }
            }
        }
        return true;
    }

    private boolean autoPlaceOnMouse(int button, boolean state) {
        if (!state || (button != 0 && button != 1)) return true;

        if (button == 1 && shouldCancelAutoPlaceUseItem()) {
            suppressUse();
            return false;
        }
        if (!shouldSuppressManualClicksThisTick()) return true;
        setPressed("attack", false);
        return false;
    }

    private boolean shouldSuppressManualClicksThisTick() {
        if (!isInGameContext()) return false;
        return lastSuccessfulPlaceTick == currentClientTick || forceSuppressTick == currentClientTick;
    }

    private boolean shouldCancelAutoPlaceUseItem() {
        if (!isInGameContext()) return false;
        if (shouldSuppressManualClicksThisTick()) return true;
        return useSuppressed && silentPitchActive;
    }

    private void suppressUse() {
        setPressed("use", false);
        useSuppressed = true;
    }

    private void restoreUseToPhysicalState() {
        setPressed("use", running
                ? tellyAutoPlaceWindow
                : physicalRightMouseDown());
        useSuppressed = false;
    }

    private boolean isInGameContext() {
        return mc.player != null && mc.gui.screen() == null;
    }

    private boolean areAutoPlaceConditionsMet(LocalPlayer player) {
        if (!tellyAutoPlaceWindow) return false;
        return isUsableBlockStack(player.getMainHandItem());
    }

    private boolean isAutoPlaceActiveWindow(LocalPlayer player) {
        if (!isInGameContext()) return false;
        if (hasBridge("ScaffoldRunning")) return false;
        if (!areAutoPlaceConditionsMet(player)) return false;
        return isUsableBlockStack(player.getMainHandItem());
    }

    private boolean isUsableBlockStack(ItemStack stack) {
        if (stack == null || stack.isEmpty() || !(stack.getItem() instanceof BlockItem)) return false;
        String name = BuiltInRegistries.ITEM.getKey(stack.getItem()).getPath().toLowerCase(Locale.ROOT);
        for (String bad : UNPLACEABLE_EXACT) {
            if (name.equals(bad)) return false;
        }
        for (String bad : UNPLACEABLE_CONTAINS) {
            if (name.contains(bad)) return false;
        }
        return true;
    }

    private boolean isBlockBelowPlayerReplaceable(LocalPlayer player) {
        Vec3 pos = player.position();
        return isReplaceable(floor(pos.x), floor(pos.y) - 1, floor(pos.z));
    }

    private boolean placedInCurrentWindow() {
        return totalC08Counter > c08CounterAtTickBoundary;
    }

    private boolean claimExperimentalPlacementTick() {
        Object tickValue = BRIDGE.get("PlacementArbiterTick");
        Object ownerValue = BRIDGE.get("PlacementArbiterOwner");
        if (tickValue instanceof Number
                && ((Number) tickValue).intValue() == currentClientTick
                && ownerValue != null
                && !getName().equals(String.valueOf(ownerValue))) {
            return false;
        }
        BRIDGE.put("PlacementArbiterTick", currentClientTick);
        BRIDGE.put("PlacementArbiterOwner", getName());
        return true;
    }

    private void releaseExperimentalPlacementClaim() {
        Object ownerValue = BRIDGE.get("PlacementArbiterOwner");
        if (ownerValue == null || !getName().equals(String.valueOf(ownerValue))) return;
        BRIDGE.remove("PlacementArbiterTick");
        BRIDGE.remove("PlacementArbiterOwner");
    }

    private boolean hasBridge(String key) {
        if ("ScaffoldRunning".equals(key)) return Scaffold.INSTANCE.isEnabled();
        return BRIDGE.containsKey(key);
    }

    private void processAutoPlaceTick(LocalPlayer player) {
        pruneRejectedTargets();

        if (lastPlacedPos != null && !isSupportAvailable(lastPlacedPos[0], lastPlacedPos[1], lastPlacedPos[2])) {
            lastPlacedPos = null;
            lastSupportPos = null;
            lastSupportFace = -1;
        }

        // 运行期诊断采样（只读）
        runDiagBelowAir = isBlockBelowPlayerReplaceable(player);
        runDiagSway = antiSwayYawOffset;
        runDiagTargetPitch = rotationTargetPitch;
        runDiagTargetYaw = rotationTargetYaw;
        runDiagPhase = cyclePhase;

        if (!isAutoPlaceActiveWindow(player)) {
            clearCachedCandidate();
            BRIDGE.remove("AutoPlacePlacing");
            if (useSuppressed) restoreUseToPhysicalState();
            return;
        }

        ItemStack heldStack = player.getMainHandItem();
        if (!isUsableBlockStack(heldStack)) {
            clearCachedCandidate();
            if (useSuppressed) restoreUseToPhysicalState();
            return;
        }

        if (!isBlockBelowPlayerReplaceable(player)) {
            logNoPlacement(player, "below-not-replaceable");
            clearCachedCandidate();
            if (useSuppressed) restoreUseToPhysicalState();
            return;
        }

        float yaw = player.getYRot();
        float basePitch = sanitizePitch(player.getXRot(), player.getXRot());
        Object[] candidate = resolveCandidateWithOffCursorSilentPitch(player, yaw, basePitch, heldStack);
        if (candidate != null) {
            silentPitch = sanitizePitch(candidatePitch(candidate), basePitch);
            silentPitchActive = true;
            suppressUse();
        } else if (useSuppressed && !placedInCurrentWindow() && lastPlacementAttemptTick != currentClientTick) {
            restoreUseToPhysicalState();
        }

        if (placedInCurrentWindow() || lastPlacementAttemptTick == currentClientTick) {
            suppressUse();
            return;
        }

        if (candidate == null) {
            logNoPlacement(player, "candidate-null");
            clearCachedCandidate();
            return;
        }

        if (!claimExperimentalPlacementTick()) {
            clearCachedCandidate();
            return;
        }

        BRIDGE.put("AutoPlacePlacing", Boolean.TRUE);
        lastPlacementAttemptTick = currentClientTick;

        if (attemptPlacement(player, candidate, heldStack)) {
            runDiagPlaceOk++;
            return;
        }

        if (placedInCurrentWindow()) return;

        float retryYaw = player.getYRot();
        float retryPitch = player.getXRot();
        clearCachedCandidate();
        Object[] retryCandidate = findBelowPlacement(player, retryYaw, retryPitch, heldStack, clientTime() + (useExtendedSearch() ? 4L : 2L));
        cacheCandidate(retryCandidate, retryYaw, retryPitch);
        if (retryCandidate != null) {
            silentPitch = sanitizePitch(candidatePitch(retryCandidate), retryPitch);
            silentPitchActive = true;
            if (attemptPlacement(player, retryCandidate, heldStack)) {
                runDiagPlaceOk++;
                return;
            }
            runDiagPlaceFail++;
        }
        releaseExperimentalPlacementClaim();
    }

    // =====================================================================
    // 放置执行（脚本 1421-1537）
    // =====================================================================

    /**
     * 「搭不上」诊断出口：记录放置失败的**原因**与当时的视角。
     *
     * <p>⚠️ 这条日志只做采样（250ms 限流），完整分布看停止时打印的 {@code failHist} 直方图。
     *
     * @return 恒为 {@code false}，便于在早退处直接 {@code return placementFail(...)}
     */
    private boolean placementFail(String reason, int[] placedPos, int[] supportPos, int face, LocalPlayer player) {
        placementFailCounts.merge(reason, 1, Integer::sum);
        long now = clientTime();
        if (now - lastPlacementFailLogAt >= 250L) {
            lastPlacementFailLogAt = now;
            dbg("PLACE-FAIL[" + reason + "]"
                    + " target=" + (placedPos == null ? "-" : java.util.Arrays.toString(placedPos))
                    + " support=" + (supportPos == null ? "-" : java.util.Arrays.toString(supportPos))
                    + " face=" + face
                    + " yaw=" + String.format(Locale.ROOT, "%.2f", player.getYRot())
                    + " pitch=" + String.format(Locale.ROOT, "%.2f", player.getXRot())
                    + " phase=" + cyclePhase
                    + " tick=" + currentClientTick);
        }
        return false;
    }

    /**
     * 「候选搜索失败」诊断出口 —— 补齐 {@code placementFail} 覆盖不到的盲区。
     *
     * <p>当 {@code findBelowPlacement} 返回 null 或前置条件不满足时，{@code attemptPlacement}
     * **根本不会被调用**，于是所有失败原因日志都不会打印。实测正是这种形态：连续 24 tick
     * 一条放置都没有（{@code failHist} 只有 1 条），玩家冲出桥端 4 格后踩空。
     * 这里把「为什么没走到放置」直接打出来。
     */
    private void logNoPlacement(LocalPlayer player, String reason) {
        long now = clientTime();
        if (now - lastPlacementFailLogAt < 250L) return;
        lastPlacementFailLogAt = now;
        dbg("NO-PLACE[" + reason + "]"
                + " phase=" + cyclePhase
                + " yaw=" + String.format(Locale.ROOT, "%.2f", player.getYRot())
                + " pitch=" + String.format(Locale.ROOT, "%.2f", player.getXRot())
                + " pos=" + fmtPos(player)
                + " onGround=" + player.onGround()
                + " belowAir=" + isBlockBelowPlayerReplaceable(player)
                + " lastPlaced=" + (lastPlacedPos == null ? "null" : java.util.Arrays.toString(lastPlacedPos))
                + " tick=" + currentClientTick);
    }

    private boolean attemptPlacement(LocalPlayer player, Object[] candidate, ItemStack heldStack) {
        if (candidate == null) return false;
        int[] placedPos = candidatePlacedPos(candidate);
        int[] supportPos = candidateSupportPos(candidate);
        int face = candidateFace(candidate);
        if (placedPos == null || supportPos == null || face <= 0) {
            return placementFail("bad-candidate", placedPos, supportPos, face, player);
        }
        if (!isStraightTellyTarget(placedPos)) {
            return placementFail("not-straight", placedPos, supportPos, face, player);
        }
        if (!isBlockBelowPlayerReplaceable(player)) {
            return placementFail("below-not-empty", placedPos, supportPos, face, player);
        }
        if (!isUsableBlockStack(player.getMainHandItem())) {
            return placementFail("no-block", placedPos, supportPos, face, player);
        }
        if (placedInCurrentWindow()) {
            return placementFail("already-placed-window", placedPos, supportPos, face, player);
        }

        float placementPitch = sanitizePitch(candidatePitch(candidate), player.getXRot());
        Object[] prePlaceHit = resolveVerifiedHit(player.getYRot(), placementPitch, supportPos, face, placedPos);
        if (prePlaceHit == null) {
            // 设计内的筛选，不是 bug：候选里同时有身前/身后格，靠 YAW_CURVE 转头去够。
            // 偏转小时够到身前格，偏转大时够到身后格；够不到就换下一格。
            // （曾误判成"摆动把校验打空"，把放置方向钉在 baseYaw —— 实测反而更差，见 placementFailCounts。）
            return placementFail("look-misaligned", placedPos, supportPos, face, player);
        }

        if (cancelledGhostBlocks.contains(posKey(supportPos))) {
            return placementFail("cancelled-ghost", placedPos, supportPos, face, player);
        }
        if (!isReplaceable(placedPos[0], placedPos[1], placedPos[2])) {
            return placementFail("target-not-replaceable", placedPos, supportPos, face, player);
        }
        if (!isSupportAvailable(supportPos[0], supportPos[1], supportPos[2])) {
            return placementFail("no-support", placedPos, supportPos, face, player);
        }
        if (doesPlacementIntersectPlayer(player, placedPos)) {
            return placementFail("intersects-player", placedPos, supportPos, face, player);
        }

        long counterBefore = totalC08Counter;
        Vec3 hitAbs = (Vec3) prePlaceHit[2];
        placingViaModule = true;
        boolean placed = hostPlaceBlock(supportPos, faceName(face), hitAbs);
        placingViaModule = false;
        boolean packetSent = totalC08Counter > counterBefore;

        if (!placed && !packetSent) {
            return placementFail("game-mode-rejected", placedPos, supportPos, face, player);
        }
        if (!packetSent) {
            markRejectedTarget(placedPos);
            return placementFail("packet-cancelled", placedPos, supportPos, face, player);
        }

        lastPlacedPos = placedPos;
        recentPlacedKeys.add(posKey(placedPos));
        if (recentPlacedKeys.size() > 128) recentPlacedKeys.clear();
        lastSupportPos = supportPos;
        lastSupportFace = face;
        lastSuccessfulPlaceTick = currentClientTick;
        forceSuppressTick = currentClientTick;
        mc.player.swing(InteractionHand.MAIN_HAND);
        // 落点日志：写进 latest.log（不走聊天栏），用于定位"某一格放错/叠高"。
        Vec3 placedAtPlayerPos = player.position();
        double laneNow = travelX != 0 ? placedAtPlayerPos.z : placedAtPlayerPos.x;
        dbg("placed at=" + java.util.Arrays.toString(placedPos)
                + " support=" + java.util.Arrays.toString(supportPos)
                + " face=" + face
                + " pY=" + String.format(Locale.ROOT, "%.2f", player.getY())
                + " feetY=" + floor(player.getY())
                + " curY=" + getCurrentBelowTargetY(player)
                + " strictY=" + getStrictBelowTargetY(player)
                + " prevY=" + getPreviousBelowTargetY(player)
                + " onGround=" + player.onGround()
                + " sneak=" + player.isShiftKeyDown()
                + " physSneak=" + mc.options.keyShift.isDown()
                + " sprint=" + player.isSprinting()
                + " vH=" + String.format(Locale.ROOT, "%.3f", player.getDeltaMovement().horizontal().length())
                // 横向漂移诊断：err = lane 基准 − 当前横向坐标（正 = 需往正侧修正）。
                // 判断口径：
                //   err 恒定   ⇒ 站位/基准问题（antiSwayLane 取自激活瞬间的玩家坐标）；
                //   err 逐格增 ⇒ 修正量失效（antiSway 没把误差拉回来）。
                + " lane=" + String.format(Locale.ROOT, "%.2f", laneNow)
                + " err=" + String.format(Locale.ROOT, "%+.3f", antiSwayLane - laneNow)
                + " sway=" + String.format(Locale.ROOT, "%+.2f", antiSwayYawOffset)
                + " tick=" + currentClientTick);
        return true;
    }

    private Object[] resolveVerifiedHit(float yaw, float pitch, int[] expectedSupport, int expectedFace, int[] expectedPlaced) {
        Object[] traced = rayCast(yaw, pitch);
        if (traced == null) return null;
        int[] tracedSupport = (int[]) traced[0];
        int tracedFace = (Integer) traced[1];
        if (!posEquals(tracedSupport, expectedSupport) || tracedFace != expectedFace) return null;
        int[] tracedPlaced = offsetPos(tracedSupport, tracedFace);
        if (!posEquals(tracedPlaced, expectedPlaced)) return null;
        return traced;
    }

    private Object[] resolveCandidateWithOffCursorSilentPitch(LocalPlayer player, float yaw, float basePitch, ItemStack heldStack) {
        float safeBasePitch = sanitizePitch(basePitch, player.getXRot());
        Object[] previousCandidate = cachedCandidate;
        Object[] baseCandidate = resolveCandidateForCurrentTick(player, yaw, safeBasePitch, heldStack);
        if (baseCandidate == null) {
            if (previousCandidate != null) {
                float previousBlockPitch = getBlockDerivedSilentPitch(player, previousCandidate, safeBasePitch);
                Object[] recovered = resolveCandidateForCurrentTick(player, yaw, previousBlockPitch, heldStack);
                if (recovered != null) return recovered;
                cacheCandidate(previousCandidate, yaw, safeBasePitch);
                return previousCandidate;
            }
            return null;
        }
        if (isPlacementLookAligned(yaw, safeBasePitch, candidateSupportPos(baseCandidate), candidateFace(baseCandidate), candidatePlacedPos(baseCandidate))) {
            return baseCandidate;
        }
        float blockPitch = getBlockDerivedSilentPitch(player, baseCandidate, safeBasePitch);
        if (isPlacementLookAligned(yaw, blockPitch, candidateSupportPos(baseCandidate), candidateFace(baseCandidate), candidatePlacedPos(baseCandidate))) {
            return new Object[]{blockPitch, candidateSupportPos(baseCandidate), candidateFace(baseCandidate), candidateHitVec(baseCandidate), candidatePlacedPos(baseCandidate)};
        }
        Object[] corrected = resolveCandidateForCurrentTick(player, yaw, blockPitch, heldStack);
        if (corrected != null && posEquals(candidatePlacedPos(baseCandidate), candidatePlacedPos(corrected))) {
            return corrected;
        }
        cacheCandidate(baseCandidate, yaw, safeBasePitch);
        return baseCandidate;
    }

    private Object[] resolveCandidateForCurrentTick(LocalPlayer player, float yaw, float pitch, ItemStack heldStack) {
        float safePitch = sanitizePitch(pitch, player.getXRot());
        if (hasCachedCandidateForCurrentTick(yaw, safePitch)) return cachedCandidate;
        Object[] candidate = findBelowPlacement(player, yaw, safePitch, heldStack, clientTime() + (useExtendedSearch() ? 8L : 4L));
        cacheCandidate(candidate, yaw, safePitch);
        return candidate;
    }

    private float getBlockDerivedSilentPitch(LocalPlayer player, Object[] candidate, float fallbackPitch) {
        if (candidate == null) return sanitizePitch(fallbackPitch, fallbackPitch);
        Vec3 hitVec = candidateHitVec(candidate);
        if (hitVec != null) {
            Float derived = computePitchToHitVec(player, hitVec);
            if (derived != null) return sanitizePitch(derived, fallbackPitch);
        }
        return sanitizePitch(candidatePitch(candidate), fallbackPitch);
    }

    private void cacheCandidate(Object[] candidate, float yaw, float pitch) {
        cachedCandidate = candidate;
        cachedCandidateTick = currentClientTick;
        cachedCandidateYaw = yaw;
        cachedCandidatePitch = pitch;
        candidateResolvedThisTick = candidate != null;
    }

    private boolean hasCachedCandidateForCurrentTick(float yaw, float pitch) {
        if (cachedCandidateTick != currentClientTick || !candidateResolvedThisTick || cachedCandidate == null) return false;
        if (Float.isNaN(cachedCandidateYaw) || Float.isNaN(cachedCandidatePitch)) return false;
        return Math.abs(wrapAngle(yaw - cachedCandidateYaw)) <= 0.75f && Math.abs(pitch - cachedCandidatePitch) <= 0.75f;
    }

    private void clearCachedCandidate() {
        cachedCandidate = null;
        cachedCandidateTick = Integer.MIN_VALUE;
        cachedCandidateYaw = Float.NaN;
        cachedCandidatePitch = Float.NaN;
        candidateResolvedThisTick = false;
    }

    // =====================================================================
    // 放置搜索（脚本 1538-1669）
    // =====================================================================

    private Object[] findBelowPlacement(LocalPlayer player, float yaw, float currentPitch, ItemStack heldStack, long deadlineMs) {
        if (clientTime() >= deadlineMs) return null;

        Object[] cursorRayCandidate = findDirectCursorRayPlacement(player, yaw, currentPitch, heldStack);
        if (cursorRayCandidate != null) return cursorRayCandidate;

        int currentY = getCurrentBelowTargetY(player);
        int strictY = getStrictBelowTargetY(player);
        int previousY = getPreviousBelowTargetY(player);
        int[] feetPos = getFeetBelowTargetAtY(player, currentY);

        List<int[]> targets = new ArrayList<>();
        addBelowTarget(player, targets, feetPos);
        addBelowTarget(player, targets, offsetPos(feetPos, facingFromYaw(yaw)));

        for (int dy = 0; dy <= 2; dy++) {
            int targetY = dy == 0 ? currentY : dy == 1 ? strictY : previousY;
            if (targetY == Integer.MIN_VALUE || (dy == 1 && targetY == currentY) || (dy == 2 && (targetY == currentY || targetY == strictY))) continue;
            addBelowTarget(player, targets, new int[]{feetPos[0], targetY, feetPos[2]});
        }

        for (int dx = -1; dx <= 1; dx++) {
            for (int dz = -1; dz <= 1; dz++) {
                addBelowTarget(player, targets, new int[]{feetPos[0] + dx, currentY, feetPos[2] + dz});
                if (strictY != currentY) addBelowTarget(player, targets, new int[]{feetPos[0] + dx, strictY, feetPos[2] + dz});
                if (previousY != currentY && previousY != strictY) addBelowTarget(player, targets, new int[]{feetPos[0] + dx, previousY, feetPos[2] + dz});
            }
        }

        if (!player.onGround()) {
            addBelowTarget(player, targets, getMotionBelowTargetAtY(player, currentY, 1.0));
            if (previousY != currentY && previousY != strictY) {
                addBelowTarget(player, targets, getMotionBelowTargetAtY(player, previousY, 1.0));
            }
        }

        Object[] bestCandidate = null;
        double bestScore = Double.POSITIVE_INFINITY;
        for (int[] targetPos : targets) {
            if (clientTime() >= deadlineMs) return null;
            if (!isPlacementTargetAvailable(player, targetPos)) continue;
            Object[] candidate = findPitchPlacementForTarget(player, yaw, currentPitch, targetPos, heldStack, null, -1, deadlineMs, false, true);
            if (candidate == null) continue;
            double score = scorePlacementCandidate(player, currentPitch, candidatePitch(candidate), candidateFace(candidate), 0.5, 0.5);
            if (score < bestScore) {
                bestScore = score;
                bestCandidate = candidate;
            }
        }
        return bestCandidate;
    }

    private Object[] findDirectCursorRayPlacement(LocalPlayer player, float yaw, float pitch, ItemStack heldStack) {
        if (!isUsableBlockStack(heldStack)) return null;
        Object[] traced = rayCast(yaw, pitch);
        if (traced == null) return null;
        int[] supportPos = (int[]) traced[0];
        int face = (Integer) traced[1];
        if (face == 0) return null;
        int[] targetPos = offsetPos(supportPos, face);
        if (!isPlacementTargetAvailable(player, targetPos)) return null;
        if (!isSupportAvailable(supportPos[0], supportPos[1], supportPos[2])) return null;
        if (shouldRejectStraightSideSwitch(player, targetPos, face)) return null;
        float tracedPitch = clampFloat(pitch, -89.0f, 89.0f);
        return new Object[]{tracedPitch, supportPos, face, (Vec3) traced[2], targetPos};
    }

    private Object[] findStraightGroundExceptionCandidate(LocalPlayer player, float yaw, float currentPitch, ItemStack heldStack, long deadlineMs) {
        int previousForcedMode = forcedModeCheck;
        forcedModeCheck = 2;
        Object[] candidate = findBelowPlacementForSupport(player, yaw, currentPitch, heldStack, null, -1, deadlineMs);
        if (candidate == null) {
            candidate = findBelowPlayerAirborneFallback(player, yaw, currentPitch, heldStack, Math.max(deadlineMs, clientTime() + (useExtendedSearch() ? 4L : 2L)));
        }
        if (candidate == null) {
            candidate = findNearestSupportToBelowPlayerFallback(player, yaw, currentPitch, heldStack, Math.max(deadlineMs, clientTime() + (useExtendedSearch() ? 4L : 2L)));
        }
        forcedModeCheck = previousForcedMode;
        return candidate;
    }

    private Object[] findPreviousBlockAirborneFallback(LocalPlayer player, float yaw, float currentPitch, ItemStack heldStack, long deadlineMs) {
        if (!hasValidLastSupportFace(player) || clientTime() >= deadlineMs) return null;
        int[] exactTarget = offsetPos(lastSupportPos, lastSupportFace);
        if (!isPlacementTargetAvailable(player, exactTarget)) return null;
        boolean diagonal = isDiagonalMovementContext(player);
        return findPitchPlacementForTarget(player, yaw, currentPitch, exactTarget, heldStack, lastSupportPos, lastSupportFace, deadlineMs, false, diagonal);
    }

    private Object[] findStraightLegacyLaneFallback(LocalPlayer player, float yaw, float currentPitch, ItemStack heldStack, long deadlineMs) {
        if (clientTime() >= deadlineMs) return null;
        int currentY = getCurrentBelowTargetY(player);
        int strictY = getStrictBelowTargetY(player);
        int previousY = getPreviousBelowTargetY(player);
        int upwardY = isStraightAscendingContext(player) ? currentY + 1 : Integer.MIN_VALUE;

        List<int[]> laneTargets = new ArrayList<>();
        addBelowTarget(player, laneTargets, getCursorStartTargetAtY(player, currentY));
        addBelowTarget(player, laneTargets, getCursorPlacedTargetFromRay(yaw, currentPitch, currentY));
        addBelowTarget(player, laneTargets, getCursorTargetAtY(player, currentY));
        if (strictY != currentY) {
            addBelowTarget(player, laneTargets, getCursorStartTargetAtY(player, strictY));
            addBelowTarget(player, laneTargets, getCursorPlacedTargetFromRay(yaw, currentPitch, strictY));
            addBelowTarget(player, laneTargets, getCursorTargetAtY(player, strictY));
        }
        if (previousY != currentY && previousY != strictY) {
            addBelowTarget(player, laneTargets, getCursorStartTargetAtY(player, previousY));
            addBelowTarget(player, laneTargets, getCursorPlacedTargetFromRay(yaw, currentPitch, previousY));
            addBelowTarget(player, laneTargets, getCursorTargetAtY(player, previousY));
        }
        if (upwardY != Integer.MIN_VALUE && upwardY != currentY && upwardY != strictY && upwardY != previousY) {
            addBelowTarget(player, laneTargets, getCursorStartTargetAtY(player, upwardY));
            addBelowTarget(player, laneTargets, getCursorPlacedTargetFromRay(yaw, currentPitch, upwardY));
            addBelowTarget(player, laneTargets, getCursorTargetAtY(player, upwardY));
        }

        for (int[] targetPos : laneTargets) {
            if (clientTime() >= deadlineMs) return null;
            if (!isStraightLaneTargetAvailable(player, targetPos, currentY, strictY, previousY, upwardY)) continue;
            Object[] candidate = findLegacyPitchPlacementForTarget(player, yaw, currentPitch, targetPos, heldStack, null, deadlineMs);
            if (candidate != null) return candidate;
        }
        return null;
    }

    // =====================================================================
    // 回退链与评分（脚本 1670-2137）
    // =====================================================================

    private boolean isCursorDirectedAtBlock(float yaw, float pitch) {
        return rayCast(yaw, pitch) != null;
    }

    private boolean isStraightCenterBelowAir(LocalPlayer player) {
        Vec3 pos = player.position();
        return isReplaceableName(blockNameAt(floor(pos.x), getCurrentBelowTargetY(player), floor(pos.z)), true);
    }

    private boolean isStraightPreviousTickCenterOnGroundSupport(LocalPlayer player) {
        Vec3 last = lastPosition(player);
        return !isReplaceableName(blockNameAt(floor(last.x), floor(last.y) - 1, floor(last.z)), true);
    }

    private boolean isNearStraightSupportEdge(LocalPlayer player) {
        if (lastSupportPos == null || lastSupportFace < 2) return false;
        Vec3 pos = player.position();
        double localX = pos.x - lastSupportPos[0];
        double localZ = pos.z - lastSupportPos[2];
        if (isPastStraightSupportEdgeThreshold(lastSupportFace, localX, localZ)) return true;
        Vec3 motion = player.getDeltaMovement();
        if (motion.x * motion.x + motion.z * motion.z < 1.0E-4) return false;
        if (!isMovingTowardStraightSupportEdge(lastSupportFace, motion.x, motion.z)) return false;
        return isPastStraightSupportEdgeThreshold(lastSupportFace, localX + motion.x * 1.45, localZ + motion.z * 1.45);
    }

    private boolean isPastStraightSupportEdgeThreshold(int supportFace, double localX, double localZ) {
        if (supportFace == 5) return localX >= 0.52;
        if (supportFace == 4) return localX <= 0.48;
        if (supportFace == 3) return localZ >= 0.52;
        if (supportFace == 2) return localZ <= 0.48;
        return false;
    }

    private boolean isMovingTowardStraightSupportEdge(int supportFace, double motionX, double motionZ) {
        if (supportFace == 5) return motionX > 0.0;
        if (supportFace == 4) return motionX < 0.0;
        if (supportFace == 3) return motionZ > 0.0;
        if (supportFace == 2) return motionZ < 0.0;
        return false;
    }

    private Object[] findStraightPreviousVisibleFaceFallback(LocalPlayer player, float yaw, float currentPitch, ItemStack heldStack, long deadlineMs) {
        if (clientTime() >= deadlineMs || !hasValidLastSupportFace(player)) return null;
        if (lastSupportFace <= 0) return null;
        int[] targetPos = offsetPos(lastSupportPos, lastSupportFace);
        if (!isPlacementTargetAvailable(player, targetPos)) return null;
        return findPitchPlacementForTarget(player, yaw, currentPitch, targetPos, heldStack, lastSupportPos, lastSupportFace, deadlineMs, true, true);
    }

    private List<int[]> getBelowPlayerFallbackEndpoints(LocalPlayer player, float yaw, float pitch, int targetY) {
        List<int[]> endpoints = new ArrayList<>();
        if (!isDiagonalMovementContext(player)) {
            if (!player.onGround()) {
                addBelowTargetIfUnique(player, endpoints, getFeetBelowTargetAtY(player, targetY));
                addBelowTargetIfUnique(player, endpoints, getMotionBelowTargetAtY(player, targetY, 1.0));
                addBelowTargetIfUnique(player, endpoints, getMotionBelowTargetAtY(player, targetY, 1.7));
            }
            addBelowTargetIfUnique(player, endpoints, getCursorStartTargetAtY(player, targetY));
            addBelowTargetIfUnique(player, endpoints, getCursorPlacedTargetFromRay(yaw, pitch, targetY));
            addBelowTargetIfUnique(player, endpoints, getCursorTargetAtY(player, targetY));
            return endpoints;
        }
        addBelowTargetIfUnique(player, endpoints, getMotionBelowTargetAtY(player, targetY, 1.0));
        addBelowTargetIfUnique(player, endpoints, getMotionBelowTargetAtY(player, targetY, 1.7));
        return endpoints;
    }

    private Object[] findBelowPlayerAirborneFallback(LocalPlayer player, float yaw, float currentPitch, ItemStack heldStack, long deadlineMs) {
        if (clientTime() >= deadlineMs) return null;
        int playerBelowY = getCurrentBelowTargetY(player);
        boolean diagonal = isDiagonalMovementContext(player);
        boolean allowNonCursorTarget = diagonal || !player.onGround();
        List<int[]> fallbackTargets = new ArrayList<>();
        for (int[] endpoint : getBelowPlayerFallbackEndpoints(player, yaw, currentPitch, playerBelowY)) {
            addBelowTarget(player, fallbackTargets, endpoint);
        }
        for (int[] targetPos : fallbackTargets) {
            if (clientTime() >= deadlineMs) return null;
            if (!isPlacementTargetAvailable(player, targetPos)) continue;
            Object[] candidate = findPitchPlacementForTarget(player, yaw, currentPitch, targetPos, heldStack, null, -1, deadlineMs, false, allowNonCursorTarget);
            if (candidate != null) return candidate;
        }
        return null;
    }

    private Object[] findNearestSupportToBelowPlayerFallback(LocalPlayer player, float yaw, float currentPitch, ItemStack heldStack, long deadlineMs) {
        if (clientTime() >= deadlineMs) return null;
        int targetY = getCurrentBelowTargetY(player);
        int[] belowPlayer = getFeetBelowTargetAtY(player, targetY);
        if (belowPlayer == null || hasDirectSupportNeighbor(belowPlayer)) return null;

        int[] searchOrigin = getPathStartTowardBelowPlayer(player, targetY, belowPlayer);
        int[] nearestStart = findNearestSupportedReplaceableTarget(player, searchOrigin, belowPlayer, targetY, deadlineMs);
        if (nearestStart == null) return null;

        List<int[]> requiredPath = rasterizeHorizontalLineAtY(nearestStart, belowPlayer, targetY, 64);
        for (int i = requiredPath.size() - 1; i >= 0; i--) {
            if (clientTime() >= deadlineMs) return null;
            int[] pathPos = requiredPath.get(i);
            if (!isPlacementTargetAvailable(player, pathPos)) continue;
            Object[] candidate = findPitchPlacementForTarget(player, yaw, currentPitch, pathPos, heldStack, null, -1, deadlineMs, false, true);
            if (candidate != null) return candidate;
        }
        return null;
    }

    private int[] findNearestSupportedReplaceableTarget(LocalPlayer player, int[] origin, int[] belowPlayer, int targetY, long deadlineMs) {
        if (origin == null || belowPlayer == null || clientTime() >= deadlineMs) return null;
        for (int radius = 0; radius <= 3; radius++) {
            int[] bestAtRadius = null;
            double bestScore = Double.POSITIVE_INFINITY;
            for (int dx = -radius; dx <= radius; dx++) {
                int dzAbs = radius - Math.abs(dx);
                int[] positive = new int[]{origin[0] + dx, targetY, origin[2] + dzAbs};
                if (isPlacementTargetAvailable(player, positive) && hasDirectSupportNeighbor(positive)) {
                    double score = scoreAirPathStartCandidate(positive, belowPlayer, origin);
                    if (score < bestScore) {
                        bestScore = score;
                        bestAtRadius = positive;
                    }
                }
                if (dzAbs == 0) continue;
                int[] negative = new int[]{origin[0] + dx, targetY, origin[2] - dzAbs};
                if (isPlacementTargetAvailable(player, negative) && hasDirectSupportNeighbor(negative)) {
                    double score = scoreAirPathStartCandidate(negative, belowPlayer, origin);
                    if (score < bestScore) {
                        bestScore = score;
                        bestAtRadius = negative;
                    }
                }
            }
            if (bestAtRadius != null) return bestAtRadius;
        }
        return null;
    }

    private double scoreAirPathStartCandidate(int[] candidate, int[] belowPlayer, int[] origin) {
        double sampleY = candidate[1] + 0.5;
        double goalDistSq = distSq(candidate[0] + 0.5, sampleY, candidate[2] + 0.5, belowPlayer[0] + 0.5, sampleY, belowPlayer[2] + 0.5);
        double originDistSq = distSq(candidate[0] + 0.5, sampleY, candidate[2] + 0.5, origin[0] + 0.5, sampleY, origin[2] + 0.5);
        return goalDistSq * 4.0 + originDistSq;
    }

    private int[] getPathStartTowardBelowPlayer(LocalPlayer player, int targetY, int[] fallback) {
        int[] pathStart = null;
        if (lastPlacedPos != null && lastPlacedPos[1] == targetY) pathStart = lastPlacedPos;
        if (pathStart == null) pathStart = getMotionBelowTargetAtY(player, targetY, 1.7);
        if (pathStart == null) pathStart = getMotionBelowTargetAtY(player, targetY, 1.0);
        return pathStart != null ? pathStart : fallback;
    }

    private boolean hasValidLastPlacedPos(LocalPlayer player) {
        if (lastPlacedPos == null) return false;
        return isWithinReach(player, lastPlacedPos) && isSupportAvailable(lastPlacedPos[0], lastPlacedPos[1], lastPlacedPos[2]) && !isInteractable(lastPlacedPos[0], lastPlacedPos[1], lastPlacedPos[2]);
    }

    private boolean hasValidLastSupportFace(LocalPlayer player) {
        if (lastSupportPos == null || lastSupportFace < 0) return false;
        return isWithinReach(player, lastSupportPos) && isSupportAvailable(lastSupportPos[0], lastSupportPos[1], lastSupportPos[2]) && !isInteractable(lastSupportPos[0], lastSupportPos[1], lastSupportPos[2]);
    }

    private Object[] findLegacyBelowPlacement(LocalPlayer player, float yaw, float currentPitch, ItemStack heldStack, long deadlineMs) {
        if (clientTime() >= deadlineMs || !isUsableBlockStack(heldStack)) return null;
        if (isDiagonalMovementContext(player)) {
            Object[] diagonalCandidate = findLegacyDiagonalPlacement(player, yaw, currentPitch, heldStack, deadlineMs);
            if (diagonalCandidate != null) return diagonalCandidate;
        }
        if (hasValidLastPlacedPos(player)) {
            Object[] preferred = findLegacyBelowPlacementForSupport(player, yaw, currentPitch, heldStack, lastPlacedPos, deadlineMs);
            if (preferred != null) return preferred;
        }
        return findLegacyBelowPlacementForSupport(player, yaw, currentPitch, heldStack, null, deadlineMs);
    }

    private Object[] findLegacyDiagonalPlacement(LocalPlayer player, float yaw, float currentPitch, ItemStack heldStack, long deadlineMs) {
        if (clientTime() >= deadlineMs) return null;
        List<int[]> diagonalTargets = new ArrayList<>();
        int currentY = getCurrentBelowTargetY(player);
        int strictY = getStrictBelowTargetY(player);
        for (int[] endpoint : getBelowPlayerFallbackEndpoints(player, yaw, currentPitch, currentY)) {
            addBelowTarget(player, diagonalTargets, endpoint);
        }
        if (strictY != currentY) {
            for (int[] endpoint : getBelowPlayerFallbackEndpoints(player, yaw, currentPitch, strictY)) {
                addBelowTarget(player, diagonalTargets, endpoint);
            }
        }
        if (diagonalTargets.isEmpty()) return null;
        int[] preferredSupportPos = hasValidLastPlacedPos(player) ? lastPlacedPos : null;
        for (int[] targetPos : diagonalTargets) {
            if (clientTime() >= deadlineMs) return null;
            if (!isPlacementTargetAvailable(player, targetPos)) continue;
            Object[] candidate = findLegacyPitchPlacementForTarget(player, yaw, currentPitch, targetPos, heldStack, preferredSupportPos, deadlineMs);
            if (candidate != null) return candidate;
        }
        if (preferredSupportPos == null) return null;
        for (int[] targetPos : diagonalTargets) {
            if (clientTime() >= deadlineMs) return null;
            if (!isPlacementTargetAvailable(player, targetPos)) continue;
            Object[] candidate = findLegacyPitchPlacementForTarget(player, yaw, currentPitch, targetPos, heldStack, null, deadlineMs);
            if (candidate != null) return candidate;
        }
        return null;
    }

    private Object[] findLegacyBelowPlacementForSupport(LocalPlayer player, float yaw, float currentPitch, ItemStack heldStack, int[] preferredSupportPos, long deadlineMs) {
        for (int[] targetPos : getMessageStyleBelowTargets(player)) {
            if (clientTime() >= deadlineMs) return null;
            if (!isPlacementTargetAvailable(player, targetPos)) continue;
            Object[] candidate = findLegacyPitchPlacementForTarget(player, yaw, currentPitch, targetPos, heldStack, preferredSupportPos, deadlineMs);
            if (candidate != null) return candidate;
        }
        return null;
    }

    private Object[] findLegacyPitchPlacementForTarget(LocalPlayer player, float yaw, float currentPitch, int[] targetPos, ItemStack heldStack, int[] preferredSupportPos, long deadlineMs) {
        float clampedBasePitch = clampFloat(currentPitch, 40.0f, 89.0f);
        Object[] direct = tryLegacyPitch(yaw, clampedBasePitch, targetPos, preferredSupportPos, deadlineMs);
        if (direct != null) return direct;
        for (int offset = 1; offset <= 49; offset++) {
            if (clientTime() >= deadlineMs) return null;
            float up = clampedBasePitch + offset;
            if (up <= 89.0f) {
                Object[] candidate = tryLegacyPitch(yaw, up, targetPos, preferredSupportPos, deadlineMs);
                if (candidate != null) return candidate;
            }
            float down = clampedBasePitch - offset;
            if (down >= 40.0f) {
                Object[] candidate = tryLegacyPitch(yaw, down, targetPos, preferredSupportPos, deadlineMs);
                if (candidate != null) return candidate;
            }
        }
        return null;
    }

    private Object[] tryLegacyPitch(float yaw, float pitch, int[] targetPos, int[] preferredSupportPos, long deadlineMs) {
        if (clientTime() >= deadlineMs) return null;
        Object[] traced = rayCast(yaw, pitch);
        if (traced == null) return null;
        int[] supportPos = (int[]) traced[0];
        int face = (Integer) traced[1];
        if (preferredSupportPos != null && !posEquals(supportPos, preferredSupportPos)) return null;
        if (face == 0) return null;
        if (isReplaceable(supportPos[0], supportPos[1], supportPos[2]) || isInteractable(supportPos[0], supportPos[1], supportPos[2])) return null;
        int[] placedPos = offsetPos(supportPos, face);
        if (!posEquals(placedPos, targetPos)) return null;
        return new Object[]{Math.min(pitch, 89.0f), supportPos, face, (Vec3) traced[2], placedPos};
    }

    private List<int[]> getMessageStyleBelowTargets(LocalPlayer player) {
        double[] offsets = {0.0, 0.29, -0.29};
        Vec3 pos = player.position();
        int maxY = floor(pos.y) - 1;
        int minY = floor(pos.y) - 2;
        List<int[]> targets = new ArrayList<>();
        for (int targetY = maxY; targetY >= minY; targetY--) {
            for (double xOffset : offsets) {
                for (double zOffset : offsets) {
                    targets.add(new int[]{floor(pos.x + xOffset), targetY, floor(pos.z + zOffset)});
                }
            }
        }
        return targets;
    }

    private Object[] findBelowPlacementForSupport(LocalPlayer player, float yaw, float currentPitch, ItemStack heldStack, int[] preferredSupportPos, int preferredSupportFace, long deadlineMs) {
        boolean diagonal = isDiagonalMovementContext(player);
        for (int[] targetPos : getBelowTargets(player, yaw, currentPitch)) {
            if (clientTime() >= deadlineMs) return null;
            if (!isPlacementTargetAvailable(player, targetPos)) continue;
            if (!isStrictOneBelowPlayer(player, targetPos)) continue;
            Object[] candidate = findPitchPlacementForTarget(player, yaw, currentPitch, targetPos, heldStack, preferredSupportPos, preferredSupportFace, deadlineMs, false, diagonal);
            if (candidate != null) return candidate;
        }
        return null;
    }

    private boolean isWithinReach(LocalPlayer player, int[] pos) {
        if (pos == null) return false;
        Vec3 eyes = getEyes(player);
        double cx = Math.max(pos[0], Math.min(eyes.x, pos[0] + 1.0));
        double cy = Math.max(pos[1], Math.min(eyes.y, pos[1] + 1.0));
        double cz = Math.max(pos[2], Math.min(eyes.z, pos[2] + 1.0));
        double dx = eyes.x - cx;
        double dy = eyes.y - cy;
        double dz = eyes.z - cz;
        return dx * dx + dy * dy + dz * dz <= reach() * reach();
    }

    private Object[] findPitchPlacementForTarget(LocalPlayer player, float yaw, float currentPitch, int[] targetPos, ItemStack heldStack, int[] preferredSupportPos, int preferredSupportFace, long deadlineMs, boolean requireLookAlignment, boolean allowNonCursorTarget) {
        if (clientTime() >= deadlineMs || targetPos == null) return null;
        boolean effectiveAllowNonCursorTarget = allowNonCursorTarget || shouldAllowPlayerOneNonCursorTarget(player, targetPos);
        if (!effectiveAllowNonCursorTarget && !isCursorOrBelowPlayerTarget(player, targetPos, yaw, currentPitch)) return null;
        if (!isPlacementTargetAvailable(player, targetPos)) return null;

        Object[] bestCandidate = null;
        double bestScore = Double.POSITIVE_INFINITY;
        for (int placeFace : getAllowedPlaceFacesForContext(player, yaw)) {
            if (clientTime() >= deadlineMs) break;
            if (shouldRejectStraightSideSwitch(player, targetPos, placeFace)) continue;
            int[] supportPos = offsetPos(targetPos, opposite(placeFace));
            if (preferredSupportPos != null && !posEquals(supportPos, preferredSupportPos)) continue;
            if (preferredSupportFace >= 0 && placeFace != preferredSupportFace) continue;
            if (!isSupportAvailable(supportPos[0], supportPos[1], supportPos[2])) continue;
            if (!isWithinReach(player, supportPos)) continue;

            double[] hitOffsets = useExtendedSearch() ? EXTENDED_FACE_HIT_OFFSETS : FACE_HIT_OFFSETS;
            for (double primaryOffset : hitOffsets) {
                for (double secondaryOffset : hitOffsets) {
                    if (clientTime() >= deadlineMs) break;
                    Vec3 hitVec = getSupportFaceHitVec(supportPos, placeFace, primaryOffset, secondaryOffset);
                    Object[] candidate = buildPlacementCandidateForHitVec(player, yaw, targetPos, supportPos, placeFace, hitVec, requireLookAlignment, effectiveAllowNonCursorTarget);
                    if (candidate == null) continue;
                    double candidateScore = scorePlacementCandidate(player, currentPitch, candidatePitch(candidate), placeFace, primaryOffset, secondaryOffset);
                    if (candidateScore < bestScore) {
                        bestScore = candidateScore;
                        bestCandidate = candidate;
                    }
                }
            }
        }
        if (bestCandidate == null && preferredSupportPos != null && preferredSupportFace >= 0) {
            return findRayAlignedPitchCandidate(yaw, currentPitch, targetPos, preferredSupportPos, preferredSupportFace, deadlineMs);
        }
        return bestCandidate;
    }

    private Object[] findRayAlignedPitchCandidate(float yaw, float currentPitch, int[] targetPos, int[] supportPos, int placeFace, long deadlineMs) {
        float clampedBasePitch = clampFloat(currentPitch, 40.0f, 89.0f);
        for (int offset = 0; offset <= 49; offset++) {
            if (clientTime() >= deadlineMs) return null;
            float upPitch = clampedBasePitch + offset;
            if (upPitch <= 89.0f) {
                Object[] candidate = tryRayAlignedPitch(yaw, upPitch, targetPos, supportPos, placeFace);
                if (candidate != null) return candidate;
            }
            if (offset == 0) continue;
            float downPitch = clampedBasePitch - offset;
            if (downPitch >= 40.0f) {
                Object[] candidate = tryRayAlignedPitch(yaw, downPitch, targetPos, supportPos, placeFace);
                if (candidate != null) return candidate;
            }
        }
        return null;
    }

    private Object[] tryRayAlignedPitch(float yaw, float pitch, int[] targetPos, int[] supportPos, int placeFace) {
        Object[] traced = rayCast(yaw, pitch);
        if (traced == null) return null;
        int[] tracedSupport = (int[]) traced[0];
        int tracedFace = (Integer) traced[1];
        if (!posEquals(tracedSupport, supportPos) || tracedFace != placeFace) return null;
        int[] tracedPlaced = offsetPos(tracedSupport, tracedFace);
        if (!posEquals(tracedPlaced, targetPos)) return null;
        return new Object[]{pitch, tracedSupport, tracedFace, (Vec3) traced[2], tracedPlaced};
    }

    private double scorePlacementCandidate(LocalPlayer player, float currentPitch, float candidatePitchValue, int placeFace, double primaryOffset, double secondaryOffset) {
        double pitchPenalty = Math.abs(wrapAngle(candidatePitchValue - currentPitch));
        double centerPenalty = Math.abs(primaryOffset - 0.5) + Math.abs(secondaryOffset - 0.5);
        double facePenalty = placeFace == 1 ? 0.0 : 0.35;
        double straightSidePenalty = getStraightSideSwitchPenalty(player, placeFace);
        return pitchPenalty + centerPenalty * 2.0 + facePenalty + straightSidePenalty;
    }

    private double getStraightSideSwitchPenalty(LocalPlayer player, int placeFace) {
        if (getConditionModeCheck(player) != 1) return 0.0;
        if (lastSupportFace < 2) return 0.0;
        if (placeFace == lastSupportFace) return 0.0;
        return 0.8;
    }

    private boolean shouldRejectStraightSideSwitch(LocalPlayer player, int[] targetPos, int placeFace) {
        if (targetPos == null || getConditionModeCheck(player) != 1) return false;
        if (placeFace < 2) return false;
        if (lastSupportFace < 2) return false;
        if (placeFace == lastSupportFace) return false;
        if (isNearStraightSupportEdge(player)) return false;
        int[] laneSupportPos = offsetPos(targetPos, opposite(lastSupportFace));
        return isSupportAvailable(laneSupportPos[0], laneSupportPos[1], laneSupportPos[2]) && isWithinReach(player, laneSupportPos);
    }

    private Object[] buildPlacementCandidateForHitVec(LocalPlayer player, float yaw, int[] targetPos, int[] supportPos, int placeFace, Vec3 hitVec, boolean requireLookAlignment, boolean allowNonCursorTarget) {
        if (hitVec == null) return null;
        int[] offsetTarget = offsetPos(supportPos, placeFace);
        if (!posEquals(offsetTarget, targetPos)) return null;
        if (!isStrictOneBelowPlayer(player, offsetTarget)) return null;
        Float pitch = computePitchToHitVec(player, hitVec);
        if (pitch == null) return null;
        if (!isPlacementLookAligned(yaw, pitch, supportPos, placeFace, targetPos)) return null;
        if (!(allowNonCursorTarget || isDiagonalMovementContext(player) || isSupportFaceVisible(player, supportPos, placeFace, hitVec))) return null;
        return new Object[]{pitch, supportPos, placeFace, hitVec, offsetTarget};
    }

    private int[] getAllowedPlaceFacesForContext(LocalPlayer player, float yaw) {
        if (getConditionModeCheck(player) != 1) return ALLOWED_PLACE_FACES;
        int forward = getStraightForwardFacing(player, yaw);
        if (useExtendedSearch()) {
            return new int[]{rotateY(forward), rotateYCCW(forward), forward, opposite(forward), 1};
        }
        return new int[]{rotateY(forward), rotateYCCW(forward), forward, opposite(forward)};
    }

    private boolean isPlacementLookAligned(float yaw, float pitch, int[] supportPos, int placeFace, int[] targetPos) {
        if (supportPos == null || placeFace < 0 || targetPos == null) return false;
        Object[] traced = rayCast(yaw, pitch);
        if (traced == null) return false;
        if (!posEquals((int[]) traced[0], supportPos) || (Integer) traced[1] != placeFace) return false;
        int[] tracedOffset = offsetPos((int[]) traced[0], (Integer) traced[1]);
        return posEquals(tracedOffset, targetPos);
    }

    private boolean isSupportFaceVisible(LocalPlayer player, int[] supportPos, int placeFace, Vec3 hitVec) {
        Vec3 eyes = getEyes(player);
        double dx = hitVec.x - eyes.x;
        double dy = hitVec.y - eyes.y;
        double dz = hitVec.z - eyes.z;
        double distance = Math.sqrt(dx * dx + dy * dy + dz * dz);
        if (distance < 1.0E-4) return false;
        float traceYaw = (float) (Math.toDegrees(Math.atan2(dz, dx)) - 90.0);
        float tracePitch = (float) (-Math.toDegrees(Math.atan2(dy, Math.sqrt(dx * dx + dz * dz))));
        Object[] traced = hostRaycast(distance + 0.5, traceYaw, tracePitch);
        if (traced == null) return false;
        int[] tracedPos = posFromVec((Vec3) traced[0]);
        int tracedFace = faceFromName((String) traced[2]);
        return posEquals(tracedPos, supportPos) && tracedFace == placeFace;
    }

    private Vec3 getSupportFaceHitVec(int[] supportPos, int placeFace, double primaryOffset, double secondaryOffset) {
        double primary = Math.max(0.001, Math.min(0.999, primaryOffset));
        double secondary = Math.max(0.001, Math.min(0.999, secondaryOffset));
        if (placeFace == 2) return new Vec3(supportPos[0] + primary, supportPos[1] + secondary, supportPos[2] + 0.001);
        if (placeFace == 3) return new Vec3(supportPos[0] + primary, supportPos[1] + secondary, supportPos[2] + 0.999);
        if (placeFace == 5) return new Vec3(supportPos[0] + 0.999, supportPos[1] + primary, supportPos[2] + secondary);
        if (placeFace == 4) return new Vec3(supportPos[0] + 0.001, supportPos[1] + primary, supportPos[2] + secondary);
        if (placeFace == 0) return new Vec3(supportPos[0] + primary, supportPos[1] + 0.001, supportPos[2] + secondary);
        return new Vec3(supportPos[0] + primary, supportPos[1] + 0.999, supportPos[2] + secondary);
    }

    private Float computePitchToHitVec(LocalPlayer player, Vec3 hitVec) {
        Vec3 eyes = getEyes(player);
        double dx = hitVec.x - eyes.x;
        double dz = hitVec.z - eyes.z;
        double horizontal = Math.sqrt(dx * dx + dz * dz);
        double dy = hitVec.y - eyes.y;
        float pitch = (float) (-Math.toDegrees(Math.atan2(dy, horizontal)));
        return Math.max(-89.0f, Math.min(89.0f, pitch));
    }

    // =====================================================================
    // 目标枚举与空间判定（脚本 2138-2616）
    // =====================================================================

    private List<int[]> getBelowTargets(LocalPlayer player, float yaw, float pitch) {
        if (cachedBelowTargetsTick == currentClientTick && cachedBelowTargets != null) return cachedBelowTargets;
        List<int[]> belowTargets = new ArrayList<>();
        boolean diagonal = isDiagonalMovementContext(player);
        if (!diagonal) {
            int currentY = getCurrentBelowTargetY(player);
            addBelowTarget(player, belowTargets, getCursorStartTargetAtY(player, currentY));
            if (belowTargets.isEmpty()) {
                int strictY = getStrictBelowTargetY(player);
                if (strictY != currentY) addBelowTarget(player, belowTargets, getCursorStartTargetAtY(player, strictY));
            }
            if (belowTargets.isEmpty()) addBelowTarget(player, belowTargets, getCursorPlacedTargetFromRay(yaw, pitch, currentY));
            if (belowTargets.isEmpty()) {
                int strictY = getStrictBelowTargetY(player);
                if (strictY != currentY) addBelowTarget(player, belowTargets, getCursorPlacedTargetFromRay(yaw, pitch, strictY));
            }
            if (belowTargets.isEmpty()) addBelowTarget(player, belowTargets, getCursorTargetAtY(player, currentY));
        } else {
            int currentY = getCurrentBelowTargetY(player);
            addBelowTarget(player, belowTargets, getMotionBelowTargetAtY(player, currentY, 1.0));
            addBelowTarget(player, belowTargets, getMotionBelowTargetAtY(player, currentY, 1.7));
            for (int[] endpoint : getBelowPlayerFallbackEndpoints(player, yaw, pitch, currentY)) {
                addBelowTarget(player, belowTargets, endpoint);
            }
        }
        cachedBelowTargets = belowTargets;
        cachedBelowTargetsTick = currentClientTick;
        return belowTargets;
    }

    private boolean isCursorOrBelowPlayerTarget(LocalPlayer player, int[] targetPos, float yaw, float pitch) {
        if (targetPos == null) return false;
        if (!isDiagonalMovementContext(player)) {
            int currentY = getCurrentBelowTargetY(player);
            if (posEquals(getCursorStartTargetAtY(player, currentY), targetPos)) return true;
            if (posEquals(getCursorPlacedTargetFromRay(yaw, pitch, currentY), targetPos)) return true;
            int strictY = getStrictBelowTargetY(player);
            if (strictY != currentY) {
                if (posEquals(getCursorStartTargetAtY(player, strictY), targetPos)) return true;
                if (posEquals(getCursorPlacedTargetFromRay(yaw, pitch, strictY), targetPos)) return true;
            }
            if (isCursorInsideTargetAtY(player, targetPos, currentY)) return true;
            return posEquals(getCursorTargetAtY(player, currentY), targetPos);
        }
        int strictY = getStrictBelowTargetY(player);
        if (isBelowPlayerTargetAtY(player, targetPos, strictY, yaw, pitch)) return true;
        return isBelowPlayerTargetAtY(player, targetPos, getCurrentBelowTargetY(player), yaw, pitch);
    }

    private boolean isBelowPlayerTargetAtY(LocalPlayer player, int[] targetPos, int targetY, float yaw, float pitch) {
        for (int[] candidate : getBelowPlayerFallbackEndpoints(player, yaw, pitch, targetY)) {
            if (posEquals(targetPos, candidate)) return true;
        }
        return false;
    }

    private int[] getFeetBelowTargetAtY(LocalPlayer player, int targetY) {
        Vec3 pos = player.position();
        return new int[]{floor(pos.x), targetY, floor(pos.z)};
    }

    private boolean shouldAllowPlayerOneNonCursorTarget(LocalPlayer player, int[] targetPos) {
        if (targetPos == null) return false;
        if (isDiagonalMovementContext(player) || player.onGround()) return false;
        if (!isPlayerHitboxFullyInsideSingleBlockColumn(player)) return false;
        if (!hasValidLastSupportFace(player) || lastSupportFace == 0) return false;
        int[] continuationTarget = offsetPos(lastSupportPos, lastSupportFace);
        if (!posEquals(targetPos, continuationTarget)) return false;
        int targetY = targetPos[1];
        int currentY = getCurrentBelowTargetY(player);
        int strictY = getStrictBelowTargetY(player);
        if (targetY != currentY && targetY != strictY) return false;
        int[] feetBelow = getFeetBelowTargetAtY(player, targetY);
        int horizontalDistance = Math.abs(targetPos[0] - feetBelow[0]) + Math.abs(targetPos[2] - feetBelow[2]);
        return horizontalDistance <= 1;
    }

    private boolean isPlayerHitboxFullyInsideSingleBlockColumn(LocalPlayer player) {
        Vec3 pos = player.position();
        double half = player.getBbWidth() / 2.0;
        int minX = floor(pos.x - half + 1.0E-4);
        int maxX = floor(pos.x + half - 1.0E-4);
        if (minX != maxX) return false;
        int minZ = floor(pos.z - half + 1.0E-4);
        int maxZ = floor(pos.z + half - 1.0E-4);
        return minZ == maxZ;
    }

    private int[] getMotionBelowTargetAtY(LocalPlayer player, int targetY, double multiplier) {
        Vec3 pos = player.position();
        Vec3 motion = player.getDeltaMovement();
        return new int[]{floor(pos.x + motion.x * multiplier), targetY, floor(pos.z + motion.z * multiplier)};
    }

    private boolean hasDirectSupportNeighbor(int[] targetPos) {
        for (int placeFace : ALLOWED_PLACE_FACES) {
            int[] supportPos = offsetPos(targetPos, opposite(placeFace));
            if (isSupportAvailable(supportPos[0], supportPos[1], supportPos[2])) return true;
        }
        return false;
    }

    private void addBelowTargetIfUnique(LocalPlayer player, List<int[]> targets, int[] candidate) {
        if (candidate == null) return;
        if (!isStrictOneBelowPlayer(player, candidate)) return;
        for (int[] existing : targets) {
            if (posEquals(existing, candidate)) return;
        }
        targets.add(candidate);
    }

    private void addBelowTarget(LocalPlayer player, List<int[]> targets, int[] candidate) {
        addBelowTargetIfUnique(player, targets, candidate);
    }

    private List<int[]> rasterizeHorizontalLineAtY(int[] start, int[] end, int y, int maxSteps) {
        List<int[]> line = new ArrayList<>();
        int x0 = start[0];
        int z0 = start[2];
        int x1 = end[0];
        int z1 = end[2];
        int dx = Math.abs(x1 - x0);
        int dz = Math.abs(z1 - z0);
        int sx = Integer.compare(x1, x0);
        int sz = Integer.compare(z1, z0);
        int movedX = 0;
        int movedZ = 0;
        for (int steps = 0; steps < maxSteps; steps++) {
            line.add(new int[]{x0, y, z0});
            if ((x0 == x1 && z0 == z1) || (movedX >= dx && movedZ >= dz)) break;
            if (movedX >= dx) {
                z0 += sz;
                movedZ++;
            } else if (movedZ >= dz) {
                x0 += sx;
                movedX++;
            } else if ((1 + 2 * movedX) * dz < (1 + 2 * movedZ) * dx) {
                x0 += sx;
                movedX++;
            } else {
                z0 += sz;
                movedZ++;
            }
        }
        return line;
    }

    private int getDetectedModeCheck(LocalPlayer player) {
        float forwardInput = Math.abs(clientForward);
        float strafeInput = Math.abs(clientStrafe);
        if (forwardInput >= 0.08f || strafeInput >= 0.08f) {
            return (forwardInput >= 0.08f && strafeInput >= 0.08f) ? 1 : 2;
        }
        double[] direction = getMotionDirectionComponents(player);
        if (direction == null) return 1;
        double angleDeg = Math.toDegrees(Math.atan2(direction[1], direction[0]));
        double norm90 = (angleDeg % 90.0 + 90.0) % 90.0;
        return Math.abs(norm90 - 45.0) <= 18.0 ? 2 : 1;
    }

    private double[] getMotionDirectionComponents(LocalPlayer player) {
        Vec3 pos = player.position();
        Vec3 last = lastPosition(player);
        double dirX = pos.x - last.x;
        double dirZ = pos.z - last.z;
        double speedSq = dirX * dirX + dirZ * dirZ;
        if (speedSq < 1.0E-4) {
            Vec3 motion = player.getDeltaMovement();
            dirX = motion.x;
            dirZ = motion.z;
            speedSq = dirX * dirX + dirZ * dirZ;
        }
        if (speedSq < 1.0E-4) return null;
        return new double[]{dirX, dirZ};
    }

    private double[] getInputDirectionComponents(float referenceYaw) {
        float forwardInput = clientForward;
        float strafeInput = clientStrafe;
        if (Math.abs(forwardInput) < 0.08f && Math.abs(strafeInput) < 0.08f) return null;
        double yawRadians = Math.toRadians(referenceYaw);
        double sinYaw = Math.sin(yawRadians);
        double cosYaw = Math.cos(yawRadians);
        double dirX = forwardInput * -sinYaw + strafeInput * cosYaw;
        double dirZ = forwardInput * cosYaw - strafeInput * sinYaw;
        if (dirX * dirX + dirZ * dirZ < 1.0E-4) return null;
        return new double[]{dirX, dirZ};
    }

    private int getStraightForwardFacing(LocalPlayer player, float fallbackYaw) {
        double[] direction = getInputDirectionComponents(fallbackYaw);
        if (direction == null) direction = getMotionDirectionComponents(player);
        if (direction == null) return facingFromYaw(fallbackYaw);
        float directionYaw = (float) (Math.toDegrees(Math.atan2(direction[1], direction[0])) - 90.0);
        return facingFromYaw(directionYaw);
    }

    private int getConditionModeCheck(LocalPlayer player) {
        if (forcedModeCheck != 0) return forcedModeCheck;
        return getDetectedModeCheck(player);
    }

    private boolean isDiagonalMovementContext(LocalPlayer player) {
        return getConditionModeCheck(player) == 2;
    }

    private int[] getCursorPlacedTargetFromRay(float yaw, float pitch, int targetY) {
        Object[] traced = rayCast(yaw, pitch);
        if (traced == null) return null;
        int[] offsetTarget = offsetPos((int[]) traced[0], (Integer) traced[1]);
        if (offsetTarget[1] != targetY) return null;
        return offsetTarget;
    }

    private int[] getCursorStartTargetAtY(LocalPlayer player, int targetY) {
        Vec3 cursorPoint = getCursorIntersectionAtY(player, targetY);
        Vec3 lookVec = getCursorLookVec(player);
        if (cursorPoint == null || lookVec == null) return null;
        double startX = cursorPoint.x - lookVec.x * 0.03;
        double startZ = cursorPoint.z - lookVec.z * 0.03;
        return new int[]{floor(startX), targetY, floor(startZ)};
    }

    private int[] getCursorTargetAtY(LocalPlayer player, int targetY) {
        Vec3 cursorPoint = getCursorIntersectionAtY(player, targetY);
        if (cursorPoint == null) return null;
        return new int[]{floor(cursorPoint.x), targetY, floor(cursorPoint.z)};
    }

    private Vec3 getCursorIntersectionAtY(LocalPlayer player, int targetY) {
        Vec3 eyes = getEyes(player);
        Vec3 lookVec = getCursorLookVec(player);
        if (lookVec == null || Math.abs(lookVec.y) < 1.0E-4) return null;
        double t = (targetY - eyes.y) / lookVec.y;
        if (t <= 0.0) return null;
        return new Vec3(eyes.x + lookVec.x * t, targetY + 0.5, eyes.z + lookVec.z * t);
    }

    private Vec3 getCursorLookVec(LocalPlayer player) {
        try {
            var camera = mc.gameRenderer.mainCamera();
            if (camera != null) {
                return getLookVec(camera.yRot(), camera.xRot());
            }
        } catch (Exception ignored) {
        }
        return getLookVec(player.getYRot(), player.getXRot());
    }

    private Vec3 getLookVec(float yaw, float pitch) {
        double yawRad = Math.toRadians(yaw);
        double pitchRad = Math.toRadians(pitch);
        double cosPitch = Math.cos(pitchRad);
        return new Vec3(-Math.sin(yawRad) * cosPitch, -Math.sin(pitchRad), Math.cos(yawRad) * cosPitch);
    }

    private boolean isCursorInsideTargetAtY(LocalPlayer player, int[] targetPos, int targetY) {
        if (targetPos == null || targetPos[1] != targetY) return false;
        Vec3 cursorPoint = getCursorIntersectionAtY(player, targetY);
        if (cursorPoint == null) return false;
        double x = cursorPoint.x;
        double z = cursorPoint.z;
        return x >= targetPos[0] - 1.0E-6 && x <= targetPos[0] + 1.0 + 1.0E-6 && z >= targetPos[2] - 1.0E-6 && z <= targetPos[2] + 1.0 + 1.0E-6;
    }

    private boolean isPlacementTargetAvailable(LocalPlayer player, int[] pos) {
        return isBasePlacementTargetAvailable(player, pos) && isStrictOneBelowPlayer(player, pos);
    }

    private boolean isStraightLaneTargetAvailable(LocalPlayer player, int[] pos, int currentY, int strictY, int previousY, int upwardY) {
        if (!isBasePlacementTargetAvailable(player, pos)) return false;
        int targetY = pos[1];
        if (targetY == currentY || targetY == strictY) return true;
        if (previousY != Integer.MIN_VALUE && targetY == previousY) return true;
        return upwardY != Integer.MIN_VALUE && targetY == upwardY;
    }

    private boolean isBasePlacementTargetAvailable(LocalPlayer player, int[] pos) {
        return pos != null
                && isStraightTellyTarget(pos)
                && !isRejectedTarget(pos)
                && !doesPlacementIntersectPlayer(player, pos)
                && isReplaceable(pos[0], pos[1], pos[2]);
    }

    private boolean doesPlacementIntersectPlayer(LocalPlayer player, int[] placePos) {
        if (placePos == null) return false;
        if (isInsideAnyPlayerPositionCell(player, placePos)) return true;

        Vec3 pos = player.position();
        double half = player.getBbWidth() / 2.0;
        double height = player.getBbHeight();
        if (boxIntersectsBlock(pos.x - half, pos.y, pos.z - half, pos.x + half, pos.y + height, pos.z + half, placePos)) return true;
        if (isBlockPosInsideBounds(placePos, pos.x - half, pos.y, pos.z - half, pos.x + half, pos.y + height, pos.z + half)) return true;

        if (!shouldUseHistoricalPlayerCollisionChecks(player, placePos)) return false;

        Vec3 last = lastPosition(player);
        if (last.x != pos.x || last.y != pos.y || last.z != pos.z) {
            if (boxIntersectsBlock(last.x - half, last.y, last.z - half, last.x + half, last.y + height, last.z + half, placePos)) return true;
            if (isBlockPosInsideBounds(placePos, last.x - half, last.y, last.z - half, last.x + half, last.y + height, last.z + half)) return true;
        }
        if (hasLastSentServerPos && (lastSentServerPosX != pos.x || lastSentServerPosY != pos.y || lastSentServerPosZ != pos.z)) {
            double sx = lastSentServerPosX;
            double sy = lastSentServerPosY;
            double sz = lastSentServerPosZ;
            if (boxIntersectsBlock(sx - half, sy, sz - half, sx + half, sy + height, sz + half, placePos)) return true;
            if (isBlockPosInsideBounds(placePos, sx - half, sy, sz - half, sx + half, sy + height, sz + half)) return true;
        }
        return false;
    }

    private boolean boxIntersectsBlock(double minX, double minY, double minZ, double maxX, double maxY, double maxZ, int[] pos) {
        return maxX > pos[0] && minX < pos[0] + 1.0 && maxY > pos[1] && minY < pos[1] + 1.0 && maxZ > pos[2] && minZ < pos[2] + 1.0;
    }

    private boolean isBlockPosInsideBounds(int[] pos, double minX, double minY, double minZ, double maxX, double maxY, double maxZ) {
        int bMinX = floor(minX + 1.0E-4);
        int bMaxX = floor(maxX - 1.0E-4);
        if (pos[0] < bMinX || pos[0] > bMaxX) return false;
        int bMinZ = floor(minZ + 1.0E-4);
        int bMaxZ = floor(maxZ - 1.0E-4);
        if (pos[2] < bMinZ || pos[2] > bMaxZ) return false;
        int bMinY = floor(minY + 1.0E-4);
        int bMaxY = floor(maxY - 1.0E-4);
        return pos[1] >= bMinY && pos[1] <= bMaxY;
    }

    private boolean isInsideAnyPlayerPositionCell(LocalPlayer player, int[] placePos) {
        Vec3 pos = player.position();
        if (isInsidePlayerPositionCell(placePos, pos.x, pos.y, pos.z)) return true;
        if (!shouldUseHistoricalPlayerCollisionChecks(player, placePos)) return false;
        Vec3 last = lastPosition(player);
        if (isInsidePlayerPositionCell(placePos, last.x, last.y, last.z)) return true;
        return hasLastSentServerPos && isInsidePlayerPositionCell(placePos, lastSentServerPosX, lastSentServerPosY, lastSentServerPosZ);
    }

    private boolean shouldUseHistoricalPlayerCollisionChecks(LocalPlayer player, int[] placePos) {
        if (!player.onGround()) return false;
        if (placePos == null) return true;
        return placePos[1] > getCurrentBelowTargetY(player);
    }

    private boolean isInsidePlayerPositionCell(int[] placePos, double x, double y, double z) {
        int playerX = floor(x);
        int playerY = floor(y);
        int playerZ = floor(z);
        return placePos[0] == playerX && placePos[2] == playerZ && (placePos[1] == playerY || placePos[1] == playerY + 1);
    }

    private boolean isStrictOneBelowPlayer(LocalPlayer player, int[] pos) {
        if (pos == null) return false;
        int targetY = pos[1];
        int currentY = getCurrentBelowTargetY(player);
        if (targetY == currentY) return true;
        if (targetY == getStrictBelowTargetY(player)) return true;
        int previousY = getPreviousBelowTargetY(player);
        if (previousY != Integer.MIN_VALUE && targetY == previousY) return true;
        return isStraightAscendingContext(player) && targetY == currentY + 1;
    }

    private double getStableBelowReferenceY(LocalPlayer player) {
        Vec3 pos = player.position();
        if (player.onGround()) {
            lastGroundFeetY = floor(pos.y);
            return pos.y;
        }
        // 空中：用「最后一次触地的高度」当参考，绝不跟随身体浮沉。
        // telly 全程在跳，若用实时 position().y，目标 Y 会随跳跃上下漂移，
        // 桥面就会时高时低、叠格错位（实测 placed Y 在 -56/-55/-54/-60/-59 之间乱跳）。
        if (lastGroundFeetY != Integer.MIN_VALUE) {
            return lastGroundFeetY;
        }
        return pos.y;
    }

    private int getStrictBelowTargetY(LocalPlayer player) {
        if (isDiagonalMovementContext(player)) return getCurrentBelowTargetY(player);
        double projectedY = getStableBelowReferenceY(player);
        Vec3 motion = player.getDeltaMovement();
        if (!player.onGround() && motion.y < -0.12) {
            projectedY = player.position().y + motion.y * 0.75;
        }
        return floor(projectedY) - 1;
    }

    private int getCurrentBelowTargetY(LocalPlayer player) {
        return floor(getStableBelowReferenceY(player)) - 1;
    }

    private int getPreviousBelowTargetY(LocalPlayer player) {
        return floor(lastPosition(player).y) - 1;
    }

    private boolean isStraightAscendingContext(LocalPlayer player) {
        // 原实现把「此刻正在上升」（跳跃时 motion.y > 0）当成「在往上走」，于是放行把方块
        // 叠到 curY + 1 —— 而 UP 面（face=1）的评分惩罚是 0，一旦可用必被选中。
        // telly 全程起跳，这个判定几乎恒为真，结果每格都在往上叠、桥面起伏接不上
        // （实测 placed Y 在 -56/-55/-54/-60 之间乱跳，而 curY 已稳定在 -56）。
        // 真需要抬升时，玩家会落到更高一层并触发 onGround，基准 lastGroundFeetY 自然跟新。
        return false;
    }

    private boolean isSupportAvailable(int x, int y, int z) {
        if (isInteractable(x, y, z)) return false;
        return !isReplaceable(x, y, z);
    }

    private boolean isRejectedTarget(int[] pos) {
        Integer rejectedAtTick = rejectedTargets.get(posKey(pos));
        if (rejectedAtTick == null) return false;
        return currentClientTick - rejectedAtTick <= 4;
    }

    private void markRejectedTarget(int[] pos) {
        if (pos == null) return;
        rejectedTargets.put(posKey(pos), currentClientTick);
    }

    private void pruneRejectedTargets() {
        if (rejectedTargets.isEmpty()) return;
        Iterator<Map.Entry<String, Integer>> iterator = rejectedTargets.entrySet().iterator();
        while (iterator.hasNext()) {
            Map.Entry<String, Integer> entry = iterator.next();
            if (currentClientTick - entry.getValue() > 4) {
                iterator.remove();
                continue;
            }
            String[] parts = entry.getKey().split(",");
            if (!isReplaceable(Integer.parseInt(parts[0]), Integer.parseInt(parts[1]), Integer.parseInt(parts[2]))) {
                iterator.remove();
            }
        }
    }

    // =====================================================================
    // 世界访问与工具（脚本 2617-2805）
    // =====================================================================

    private Object[] rayCast(float yaw, float pitch) {
        Object[] hit = hostRaycast(reach(), yaw, pitch);
        if (hit == null || hit[0] == null || hit[2] == null) return null;
        int face = faceFromName((String) hit[2]);
        if (face < 0 || face == 0) return null;
        int[] supportPos = posFromVec((Vec3) hit[0]);
        Vec3 offset = (Vec3) hit[1];
        Vec3 hitAbs = new Vec3(supportPos[0] + offset.x, supportPos[1] + offset.y, supportPos[2] + offset.z);
        return new Object[]{supportPos, face, hitAbs};
    }

    /** 脚本宿主的 client.raycastBlock，返回 {方块坐标(Vec3), 面内偏移(Vec3，相对角点), 面名(String)}。 */
    private Object[] hostRaycast(double reach) {
        LocalPlayer player = mc.player;
        if (player == null) return null;
        return hostRaycast(reach, player.getYRot(), player.getXRot());
    }

    private Object[] hostRaycast(double reach, float yaw, float pitch) {
        LocalPlayer player = mc.player;
        if (player == null || mc.level == null) return null;
        Vec3 eyes = getEyes(player);
        Vec3 to = eyes.add(getLookVec(yaw, pitch).scale(reach));
        BlockHitResult hit = mc.level.clip(new ClipContext(eyes, to, ClipContext.Block.OUTLINE, ClipContext.Fluid.NONE, player));
        if (hit == null || hit.getType() == HitResult.Type.MISS) return null;
        BlockPos blockPos = hit.getBlockPos();
        if (blockPos == null) return null;
        Vec3 localOffset = hit.getLocation().subtract(blockPos.getX(), blockPos.getY(), blockPos.getZ());
        return new Object[]{
                new Vec3(blockPos.getX(), blockPos.getY(), blockPos.getZ()),
                localOffset,
                faceName(hit.getDirection().get3DDataValue())
        };
    }

    /** 脚本宿主的 client.placeBlock。 */
    private boolean hostPlaceBlock(int[] supportPos, String faceName, Vec3 hitAbs) {
        LocalPlayer player = mc.player;
        if (player == null || mc.gameMode == null) return false;
        int face = faceFromName(faceName);
        if (face < 0) return false;
        BlockHitResult result = new BlockHitResult(hitAbs, Direction.from3DDataValue(face), new BlockPos(supportPos[0], supportPos[1], supportPos[2]), false);
        InteractionResult interaction = mc.gameMode.useItemOn(player, InteractionHand.MAIN_HAND, result);
        return interaction != null && interaction.consumesAction();
    }

    private Vec3 getEyes(LocalPlayer player) {
        Vec3 pos = player.position();
        return new Vec3(pos.x, pos.y + player.getEyeHeight(), pos.z);
    }

    private Vec3 lastPosition(LocalPlayer player) {
        return new Vec3(player.xo, player.yo, player.zo);
    }

    private String blockNameAt(int x, int y, int z) {
        if (mc.level == null) return "air";
        BlockState state = mc.level.getBlockState(new BlockPos(x, y, z));
        return BuiltInRegistries.BLOCK.getKey(state.getBlock()).getPath().toLowerCase(Locale.ROOT);
    }

    private boolean isReplaceable(int x, int y, int z) {
        if (mc.level == null) return false;
        BlockState state = mc.level.getBlockState(new BlockPos(x, y, z));
        if (isReplaceableName(blockNameAt(x, y, z), false)) return true;
        return state.canBeReplaced();
    }

    private boolean isReplaceableName(String name, boolean airOnly) {
        if (name == null) return false;
        if (airOnly) return name.equals("air");
        for (String replaceable : REPLACEABLE_BLOCKS) {
            if (name.equals(replaceable)) return true;
        }
        for (String replaceable : EXPERIMENTAL_REPLACEABLE_BLOCKS) {
            if (name.equals(replaceable)) return true;
        }
        return false;
    }

    private boolean isInteractable(int x, int y, int z) {
        if (mc.level == null) return false;
        BlockState state = mc.level.getBlockState(new BlockPos(x, y, z));
        if (state.hasBlockEntity()) return true;
        Block block = state.getBlock();
        return block instanceof DoorBlock
                || block instanceof TrapDoorBlock
                || block instanceof FenceGateBlock
                || block instanceof ButtonBlock
                || block instanceof LeverBlock
                || block instanceof BedBlock;
    }

    private double reach() {
        LocalPlayer player = mc.player;
        return player != null && player.isCreative() ? 5.0 : 4.5;
    }

    private int placementTick(LocalPlayer player) {
        if (isRavenTimerActive()) return (int) (clientTime() / 50L);
        return player.tickCount;
    }

    private boolean isRavenTimerActive() {
        try {
            Module timer = findModule("Timer");
            return timer != null && timer.isEnabled();
        } catch (Exception ignored) {
            return false;
        }
    }

    private float candidatePitch(Object[] candidate) {
        return clampFloat((Float) candidate[0], -90.0f, 90.0f);
    }

    private int[] candidateSupportPos(Object[] candidate) {
        return (int[]) candidate[1];
    }

    private int candidateFace(Object[] candidate) {
        return (Integer) candidate[2];
    }

    private Vec3 candidateHitVec(Object[] candidate) {
        return (Vec3) candidate[3];
    }

    private int[] candidatePlacedPos(Object[] candidate) {
        return (int[]) candidate[4];
    }

    private float sanitizePitch(float pitch, float fallbackPitch) {
        float safeFallback = clampFloat(Float.isNaN(fallbackPitch) ? 0.0f : fallbackPitch, -90.0f, 90.0f);
        if (Float.isNaN(pitch) || Float.isInfinite(pitch)) return safeFallback;
        return clampFloat(pitch, -90.0f, 90.0f);
    }

    private int floor(double value) {
        int i = (int) value;
        return value < i ? i - 1 : i;
    }

    private float clampFloat(float value, float min, float max) {
        return value < min ? min : (value > max ? max : value);
    }

    private float wrapAngle(float angle) {
        angle = angle % 360f;
        if (angle >= 180f) angle -= 360f;
        if (angle < -180f) angle += 360f;
        return angle;
    }

    private double distSq(double x1, double y1, double z1, double x2, double y2, double z2) {
        double dx = x1 - x2;
        double dy = y1 - y2;
        double dz = z1 - z2;
        return dx * dx + dy * dy + dz * dz;
    }

    private boolean posEquals(int[] a, int[] b) {
        return a != null && b != null && a[0] == b[0] && a[1] == b[1] && a[2] == b[2];
    }

    private String posKey(int[] pos) {
        return pos[0] + "," + pos[1] + "," + pos[2];
    }

    private int[] posFromVec(Vec3 vec) {
        return new int[]{floor(vec.x), floor(vec.y), floor(vec.z)};
    }

    private int[] posFromBlockPos(BlockPos pos) {
        return new int[]{pos.getX(), pos.getY(), pos.getZ()};
    }

    private int[] offsetPos(int[] pos, int face) {
        if (pos == null) return null;
        if (face == 0) return new int[]{pos[0], pos[1] - 1, pos[2]};
        if (face == 1) return new int[]{pos[0], pos[1] + 1, pos[2]};
        if (face == 2) return new int[]{pos[0], pos[1], pos[2] - 1};
        if (face == 3) return new int[]{pos[0], pos[1], pos[2] + 1};
        if (face == 4) return new int[]{pos[0] - 1, pos[1], pos[2]};
        return new int[]{pos[0] + 1, pos[1], pos[2]};
    }

    private int opposite(int face) {
        if (face == 0) return 1;
        if (face == 1) return 0;
        if (face == 2) return 3;
        if (face == 3) return 2;
        if (face == 4) return 5;
        return 4;
    }

    private int rotateY(int face) {
        if (face == 2) return 5;
        if (face == 5) return 3;
        if (face == 3) return 4;
        if (face == 4) return 2;
        return face;
    }

    private int rotateYCCW(int face) {
        if (face == 2) return 4;
        if (face == 4) return 3;
        if (face == 3) return 5;
        if (face == 5) return 2;
        return face;
    }

    private int facingFromYaw(float yaw) {
        int index = floor(yaw / 90.0 + 0.5) & 3;
        if (index == 0) return 3;
        if (index == 1) return 4;
        if (index == 2) return 2;
        return 5;
    }

    private String faceName(int face) {
        if (face == 0) return "DOWN";
        if (face == 1) return "UP";
        if (face == 2) return "NORTH";
        if (face == 3) return "SOUTH";
        if (face == 4) return "WEST";
        return "EAST";
    }

    private int faceFromName(String name) {
        if (name == null) return -1;
        String upper = name.toUpperCase(Locale.ROOT);
        if (upper.equals("DOWN")) return 0;
        if (upper.equals("UP")) return 1;
        if (upper.equals("NORTH")) return 2;
        if (upper.equals("SOUTH")) return 3;
        if (upper.equals("WEST")) return 4;
        if (upper.equals("EAST")) return 5;
        return -1;
    }

    // =====================================================================
    // 宿主替换层：时间 / 键位 / 客户端输入状态 / 文本宽度
    // =====================================================================

    private long clientTime() {
        return System.currentTimeMillis();
    }

    private void setClientForward(float forward) {
        this.clientForward = forward;
        if (activeInputEvent != null) activeInputEvent.setForward(forward);
    }

    private void setClientStrafe(float strafe) {
        this.clientStrafe = strafe;
        if (activeInputEvent != null) activeInputEvent.setStrafe(strafe);
    }

    private void setClientJump(boolean jump) {
        this.clientJump = jump;
        if (activeInputEvent != null) activeInputEvent.setJump(jump);
    }

    private void setClientSneak(boolean sneak) {
        this.clientSneak = sneak;
        if (activeInputEvent != null) activeInputEvent.setSneak(sneak);
    }

    private void setClientSprinting(boolean sprinting) {
        this.clientSprinting = sprinting;
        if (activeInputEvent != null) activeInputEvent.setSprint(sprinting);
        if (mc.player != null) mc.player.setSprinting(sprinting);
    }

    /**
     * 物理鼠标按键状态 —— 脚本 {@code keybinds.isMouseDown(n)} 的等价物。
     *
     * <p>不能读 {@code MouseHandler.isLeftPressed()/isRightPressed()}：本模块在
     * {@code activationSuppressUse()} 与自动放置期间会 cancel {@link MousePressEvent}，
     * 而 {@code MixinMouseHandler} 是在 {@code onButton} 的 HEAD 处直接 {@code ci.cancel()}，
     * 原版方法体根本不执行，MouseHandler 的内部布尔量因此不会更新 —— 读它恒为 false，
     * 触发条件里的「按住右键」永远不会成立。GLFW 的物理按键状态不受此影响。</p>
     */
    private boolean isPhysicalMouseDown(int glfwButton) {
        return GLFW.glfwGetMouseButton(mc.getWindow().handle(), glfwButton) == GLFW.GLFW_PRESS;
    }

    private boolean physicalRightMouseDown() {
        return isPhysicalMouseDown(GLFW.GLFW_MOUSE_BUTTON_RIGHT);
    }

    private boolean physicalLeftMouseDown() {
        return isPhysicalMouseDown(GLFW.GLFW_MOUSE_BUTTON_LEFT);
    }

    private KeyMapping mapping(String key) {
        return switch (key) {
            case "forward" -> mc.options.keyUp;
            case "back" -> mc.options.keyDown;
            case "left" -> mc.options.keyLeft;
            case "right" -> mc.options.keyRight;
            case "jump" -> mc.options.keyJump;
            case "sneak" -> mc.options.keyShift;
            case "sprint" -> mc.options.keySprint;
            case "attack" -> mc.options.keyAttack;
            case "use" -> mc.options.keyUse;
            case "drop" -> mc.options.keyDrop;
            default -> null;
        };
    }

    private void setPressed(String key, boolean state) {
        KeyMapping mapping = mapping(key);
        if (mapping != null) mapping.setDown(state);
    }

    private int keyCode(String key) {
        KeyMapping mapping = mapping(key);
        return mapping == null ? -1 : KeybindUtils.getKey(mapping);
    }

    private boolean isPressed(String key) {
        KeyMapping mapping = mapping(key);
        return mapping != null && KeybindUtils.isPressed(mapping);
    }

    /** 脚本 keybinds.isKeyDown：读物理键盘状态（不是 KeyMapping 的抑制状态）。 */
    private boolean isPhysicalKeyDown(String key) {
        int code = keyCode(key);
        return code >= 0 && InputConstants.isKeyDown(mc.getWindow(), code);
    }

    private boolean isDropKeyName(int keyCode) {
        try {
            return InputConstants.Type.KEYSYM.getOrCreate(keyCode).getName().toLowerCase(Locale.ROOT).contains("drop");
        } catch (Exception ignored) {
            return false;
        }
    }

    /** 紧凑位置串，供调试日志使用。 */
    private static String fmtPos(LocalPlayer player) {
        Vec3 pos = player.position();
        return String.format(Locale.ROOT, "%.2f,%.2f,%.2f", pos.x, pos.y, pos.z);
    }

    /**
     * 玩家是否已冲到「已铺桥面」之外太远。
     *
     * <p>阈值由 {@code Guard / Overshoot Limit} 设置控制（默认 1 —— 用户认可的「能搭很多格子」状态）。
     * 实测参考：1 ⇒ placedOk 19~60；2 ⇒ 刹车后移；关闭护栏 ⇒ 6~12（4 格就踩空）。
     */
    private boolean isPlayerAheadOfBridge(LocalPlayer player) {
        if (player == null || lastPlacedPos == null) return false;
        Vec3 pos = player.position();
        int ahead = (floor(pos.x) - lastPlacedPos[0]) * travelX
                + (floor(pos.z) - lastPlacedPos[2]) * travelZ;
        return guardEnabled.getValue() && ahead > overshootLimit.getValue();
    }

    /** 玩家是否已横向偏离桥的通道（垂直于推进方向超过 1 格）。 */
    private boolean isPlayerOffLane(LocalPlayer player) {
        if (player == null) return false;
        if (travelX == 0 && travelZ == 0) return false;
        double lateral = travelX != 0 ? player.position().z : player.position().x;
        return guardEnabled.getValue() && Math.abs(lateral - antiSwayLane) > offLaneLimit.getValue();
    }

    /** 调试日志出口：受 Debug Log 设置控制，只写 latest.log，不进聊天栏。 */
    private void dbg(String message) {
        if (debugLog.getValue()) {
            System.out.println("[Telly] " + message);
        }
    }

    private static Module findModule(String name) {
        for (Module module : ModuleManager.INSTANCE.getModules()) {
            if (module.getName().equals(name)) return module;
        }
        return null;
    }

    @Override
    public String getInfo() {
        if (running) return "Running";
        if (armed) return "Armed";
        return null;
    }
}
