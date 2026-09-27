package com.github.epsilon.modules.impl.movement.scaffold;

import com.github.epsilon.Constants;
import com.github.epsilon.events.impl.KeyboardInputEvent;
import com.github.epsilon.events.impl.PacketEvent;
import com.github.epsilon.managers.rotation.RotationManager;
import com.github.epsilon.modules.impl.movement.MovementFix;
import com.github.epsilon.modules.impl.movement.Scaffold;
import com.github.epsilon.modules.impl.movement.scaffold.clicking.Clicker;
import com.github.epsilon.modules.impl.movement.scaffold.movement.ScaffoldMovementPlanner;
import com.github.epsilon.modules.impl.movement.scaffold.movement.ScaffoldMovementPrediction;
import com.github.epsilon.modules.impl.movement.scaffold.rotation.LinearAngleSmooth;
import com.github.epsilon.modules.impl.movement.scaffold.rotation.PolarRotationManager;
import com.github.epsilon.modules.impl.movement.scaffold.rotation.RequestHandler;
import com.github.epsilon.modules.impl.movement.scaffold.rotation.Rotation;
import com.github.epsilon.modules.impl.movement.scaffold.rotation.RotationUtil;
import com.github.epsilon.modules.impl.movement.scaffold.rotation.RotationsValueGroup;
import com.github.epsilon.modules.impl.movement.scaffold.rotation.SigmoidAngleSmooth;
import com.github.epsilon.modules.impl.movement.scaffold.simulation.DirectionalInput;
import com.github.epsilon.modules.impl.movement.scaffold.simulation.PlayerSimulationCache;
import com.github.epsilon.modules.impl.movement.scaffold.targetfinding.Line;
import com.github.epsilon.modules.impl.movement.scaffold.targetfinding.TargetFinding;
import com.github.epsilon.modules.impl.movement.scaffold.technique.ScaffoldEagleFeature;
import com.github.epsilon.modules.impl.movement.scaffold.technique.ScaffoldGodBridgeTechnique;
import com.github.epsilon.modules.impl.movement.scaffold.technique.ScaffoldLedgeFeature;
import com.github.epsilon.modules.impl.movement.scaffold.technique.ScaffoldTechnique;
import com.github.epsilon.modules.impl.movement.scaffold.util.EntityUtils;
import com.github.epsilon.utils.player.FindItemResult;
import com.github.epsilon.utils.rotation.Priority;
import com.github.epsilon.utils.rotation.Rot2f;
import com.github.epsilon.utils.rotation.RotationUtils;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.network.protocol.game.ServerboundMovePlayerPacket;
import net.minecraft.util.Mth;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.Pose;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.context.BlockPlaceContext;
import net.minecraft.world.item.context.UseOnContext;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;

import java.util.EnumSet;
import java.util.Random;
import java.util.concurrent.ThreadLocalRandom;
import java.util.function.BooleanSupplier;

import static com.github.epsilon.Constants.mc;

/**
 * Scaffold 神桥 Polar 变体的协调层。
 *
 * <p>逐行照搬 LiquidBounce nextgen 0.40.1
 * {@code features/module/modules/world/scaffold/ModuleScaffold.kt} 的 GodBridge 运行时路径
 * （commit 2d94475）。LB 里这些逻辑分散在 {@code RotationUpdateEvent} 处理器、
 * {@code MovementInputEvent} 处理器与 {@code tickHandler} 三处；这里按 Epsilon 的事件模型
 * 重排为三个入口，<b>内部顺序与分支一字不改</b>：</p>
 *
 * <ul>
 *   <li>{@link #handleMovementInput(KeyboardInputEvent)} ← LB {@code handleMovementInput}（391–399）</li>
 *   <li>{@link #handleMovementInputSafety(KeyboardInputEvent)} ← LB {@code movementInputHandler}（401–425）</li>
 *   <li>{@link #tickPolar()} ← LB {@code rotationUpdateHandler}（446–468） + {@code RotationManager.update()}
 *       + {@code tickHandler}（568–663）</li>
 * </ul>
 *
 * <p>LB 里直接引用 {@code ModuleScaffold} 全局对象的地方（technique / ledge）由本类的静态访问器承接。</p>
 */
public final class ScaffoldPolarCoordinator implements RequestHandler.Provider {

    public static final ScaffoldPolarCoordinator INSTANCE = new ScaffoldPolarCoordinator();

    private static final Scaffold module = Scaffold.INSTANCE;

    /**
     * LB {@code ModuleScaffold.ScaffoldRotationValueGroup}（只搬数据 + {@code calculateTicks} 的部分）。
     */
    private final RotationsValueGroup rotationValueGroup = new RotationsValueGroup();

    /** LB {@code ScaffoldRotationValueGroup.angleSmooth} 的两个候选（其余模式不搬）。 */
    private final LinearAngleSmooth linearAngleSmooth = new LinearAngleSmooth();
    private final SigmoidAngleSmooth sigmoidAngleSmooth = new SigmoidAngleSmooth();

    /** LB {@code ModuleScaffold.SimulatePlacementAttempts.clicker}（{@code Clicker(ModuleScaffold, mc.options.keyUse, null, maxCps = 100)}）。 */
    private final Clicker clicker = new Clicker(module, mc.options.keyUse, null, 100);

    /** LB {@code ModuleScaffold.currentTarget}。 */
    private TargetFinding.BlockPlacementTarget currentTarget;

    /** LB {@code ModuleScaffold.currentOptimalLine}。 */
    private Line currentOptimalLine;

    /** LB {@code ModuleScaffold.rawInput}。 */
    private DirectionalInput rawInput = DirectionalInput.NONE;

    /** LB {@code ModuleScaffold.placementY}。 */
    private int placementY;

    /** LB {@code ModuleScaffold.forceSneak}。 */
    private int forceSneak;

    /** LB {@code ModuleScaffold.startY}（仅 SameY=HYPIXEL 分支使用；SameY 固定 Off，保留字段以对齐结构）。 */
    private int startY;

    /** LB {@code ModuleScaffold.jumps}。 */
    private int jumps;

    /** LB {@code ModuleScaffold.wasTowering}：Tower 不搬 ⇒ 恒 false。 */
    private boolean wasTowering = false;

    /** LB {@code waitTicks(currentDelay)} 的 Epsilon 等价物（剩余刻数）。 */
    private int waitTicks;

    private ScaffoldPolarCoordinator() {
    }

    // ===================== 生命周期（LB onEnabled / onDisabled / reset） =====================

    /** LB {@code ModuleScaffold.onEnabled()}（404–412）。 */
    public void onEnabled() {
        // [适配] Epsilon 的 Module.onEnable() 在主菜单也会被调用（setEnabled 只对通知做了 nullCheck），
        //        而 LB 的模块只在游戏内启用 → 这里必须挡一次空玩家，否则 mc.player.blockPosition() 直接 NPE。
        if (mc.player == null) {
            return;
        }

        // Placement Y is the Y coordinate of the block below the player
        placementY = mc.player.blockPosition().getY() - 1;
        startY = mc.player.blockPosition().getY();
        jumps = 2;

        debugLines = 0;
        debugBurstTicks = 0;
        lastLedge = "none";
        lastPlaced = false;

        ScaffoldMovementPlanner.reset();
    }

    /** LB {@code ModuleScaffold.reset()}（417–427）。 */
    public void reset() {
        // LB: NoFallBlink.waitUntilGround = false;  —— Epsilon 无对应设施（不搬）
        ScaffoldMovementPlanner.reset();
        ScaffoldMovementPrediction.reset();
        // LB: SilentHotbar.resetSlot(this) —— Epsilon 用 Swap Mode 设施（不搬）
        // LB: nextBlock = null; updateRenderCount(null) —— 渲染计数用 Epsilon 的 renderBoxes（不搬）
        forceSneak = 0;
        currentTarget = null;
        waitTicks = 0;
        // LB: renderer.clearSilently() —— 不搬

        // [适配·必须] 关模块时必须把 Epsilon 侧的托管角一并释放。
        //   只清 LB 侧状态（上面那些）是不够的：RotationManager 会一直保持 active=true、
        //   rotations 还是最后一刻的神桥角，于是准星（modifyCrosshair）与 MovementFix 继续按旧角工作
        //   —— 表现就是"取消搭路后视角不回正"。
        //   注意 onSendPosition 的自动停用条件是"托管角与真实视角差 < 1°"，神桥角差约 140°，永不触发，
        //   所以这里必须显式释放。
        if (RotationManager.INSTANCE.isActive()) {
            RotationManager.INSTANCE.setActive(false);
        }
        lastAppliedRotation = null;
    }

    /**
     * 若 Polar 曾经落地过托管角、而现在 Polar 已不再生效，则把它释放掉。
     * 供「模式/变体从 Polar 切走」这一路径调用：Classic 在 {@code Snap + 在地面 + 不在边缘} 时
     * 不会设置旋转，遗留的托管角会一直粘住（与关模块时的"视角不回正"同一个根因）。
     */
    public void releaseRotationIfIdle() {
        if (lastAppliedRotation == null) {
            return;
        }

        if (RotationManager.INSTANCE.isActive()) {
            RotationManager.INSTANCE.setActive(false);
        }
        lastAppliedRotation = null;
    }

    /** LB {@code ModuleScaffold.blockCount}：用 Epsilon 的物品选择设施（用户决策）。 */
    public static int getBlockCount() {
        return module.getBlockCount();
    }

    /** LB {@code ModuleScaffold.rawInput}。 */
    public static DirectionalInput getRawInput() {
        return INSTANCE.rawInput;
    }

    /** LB {@code ModuleScaffold.ScaffoldRotationValueGroup}。 */
    public static RotationsValueGroup getRotationValueGroup() {
        return INSTANCE.rotationValueGroup;
    }

    @Override
    public boolean isRunning() {
        return module.isPolarActive();
    }

    // ===================== 每刻入口 =====================

    /**
     * Polar 的每刻主流程。LB 的顺序：{@code GameTickEvent(FIRST_PRIORITY)} 先派发
     * {@code RotationUpdateEvent}（模块设好转向目标）再执行 {@code RotationManager.update()}
     * 算出托管角；{@code tickHandler} 随后在同一个 GameTickEvent 上运行。
     *
     * <p>[适配] Epsilon 侧三项都在 {@code PlayerTickEvent.Pre} 的本方法里按同一顺序执行；
     * 转向落地用 {@link RotationManager#setRotationsDirect} 直通（见其文档）。</p>
     */
    public void tickPolar() {
        syncSettings();

        rotationUpdate();
        PolarRotationManager.INSTANCE.onTick();
        applyRotation();

        if (module.polarDebug.getValue()) {
            debugLog();
        }

        tick();
    }

    /** 诊断输出（{@code Debug Log} 设置）：有上限地打印链路状态，只读、不改行为。 */
    private static final int DEBUG_MAX_LINES = 300;
    private int debugLines = 0;
    private int debugBurstTicks = 0;
    /** 最近一次边缘动作与放置结果（供诊断行显示）。 */
    private String lastLedge = "none";
    private boolean lastPlaced = false;
    /** 最近一次放置闸门卡在哪一步 / 放置格相对玩家的偏移（供诊断行显示）。 */
    private String lastGate = "-";
    private String lastPlacedRel = "-";
    /** 最近一次边缘判定与模拟 clipLedged（供诊断行显示）。 */
    private boolean lastCloseToEdge = false;
    private boolean lastClipLedged = false;
    /** 上一刻实际提交的落地角（用于判断是否需要抖动）。 */
    private Rot2f lastAppliedRotation;
    /** 抖动符号随机源（避免 ±1 交替形成周期 2 的规律）。 */
    private final Random jitterRandom = new Random();

    private void debugLog() {
        if (!module.polarDebug.getValue() || debugLines >= DEBUG_MAX_LINES) {
            return;
        }

        // 启用后前 40 刻逐刻打印（抓"一开就被扳"的瞬间），之后每 10 刻一行
        if (debugBurstTicks < 40) {
            debugBurstTicks++;
        } else if (mc.player.tickCount % 10 != 0) {
            return;
        }
        debugLines++;

        Rotation managed = PolarRotationManager.INSTANCE.getCurrentRotation();
        Rotation server = PolarRotationManager.INSTANCE.getActualServerRotation();
        Rot2f eps = RotationManager.INSTANCE.getRotation();
        FindItemResult result = module.getBlockResult();
        BlockPos pb = mc.player.blockPosition();

        Constants.LOGGER.info(
                "[ScaffoldPolar #{}/{}] t={} raw={} pos={},{},{} onGround={} dY={} blocks={} hand={}/{} "
                        + "target={} line={} edge={} clip={} cps={} wait={} fSnk={} ticks={} ledge={} gate={} placed={} rel={} "
                        + "managed={} server={} epsA={} epsRot={} real={}",
                debugLines, DEBUG_MAX_LINES,
                mc.player.tickCount,
                rawInput,
                String.format("%.2f", mc.player.getX()),
                String.format("%.2f", mc.player.getY()),
                String.format("%.2f", mc.player.getZ()),
                mc.player.onGround(),
                String.format("%.3f", mc.player.getDeltaMovement().y),
                module.getBlockCount(),
                result != null && result.found(),
                result != null && result.found() && result.isOffhand(),
                currentTarget != null,
                currentOptimalLine != null,
                lastCloseToEdge,
                lastClipLedged,
                clicker.isClickTick(),
                waitTicks,
                forceSneak,
                rotationValueGroup.calculateTicks(managed != null ? managed : playerRotation()),
                lastLedge,
                lastGate,
                lastPlaced,
                lastPlacedRel,
                managed == null ? "null" : fmt(managed),
                fmt(server),
                RotationManager.INSTANCE.isActive(),
                String.format("%.1f/%.1f", eps.getYaw(), eps.getPitch()),
                String.format("%.1f/%.1f", mc.player.getYRot(), mc.player.getXRot())
        );
    }

    private static String fmt(Rotation rotation) {
        return String.format("%.1f/%.1f", rotation.getYRot(), rotation.getXRot());
    }

    /** LB:446-468 {@code rotationUpdateHandler}。 */
    private void rotationUpdate() {
        // LB: NoFallBlink.waitUntilGround = true;  —— 不搬

        // LB: findBestValidHotbarSlotForTarget() + nextBlock
        //     [适配] 用 Epsilon 的 getBlockStack()（Swap Mode 设施）；取不到时沿用 LB 的 SANDSTONE 兜底。
        ItemStack bestStack = module.getBlockStack();
        if (bestStack.isEmpty()) {
            bestStack = new ItemStack(Items.SANDSTONE, 64);
        }

        Line optimalLine = this.currentOptimalLine;

        Vec3 predictedPos = ScaffoldMovementPrediction.getPredictedPlacementPos(optimalLine);
        if (predictedPos == null) {
            predictedPos = mc.player.position();
        }

        // Check if the player is probably going to sneak at the predicted position
        Pose predictedPose =
                (ScaffoldEagleFeature.isEnabled() && ScaffoldEagleFeature.INSTANCE.shouldEagle(new DirectionalInput(mc.player.input)))
                        ? Pose.CROUCHING
                        : Pose.STANDING;

        // LB: debugGeometry("predictedPos") —— 调试渲染不搬

        ScaffoldTechnique technique = activeTechnique();

        TargetFinding.BlockPlacementTarget target =
                technique.findPlacementTarget(predictedPos, predictedPose, optimalLine, bestStack);
        this.currentTarget = target;

        // LB: debugGeometry("lineToBlock") —— 调试渲染不搬

        // Do not aim yet in SKIP mode, since we want to aim at the block only when we are about to place it
        if (module.polarRotationTiming.is(Scaffold.RotationTiming.Normal)) {
            Rotation rotation = technique.getRotations(target);

            if (rotation == null) {
                return;
            }

            PolarRotationManager.INSTANCE.setRotationTarget(
                    rotation,
                    module.polarConsiderInventory.getValue(),
                    rotationValueGroup,
                    PolarRotationManager.Priority.IMPORTANT_FOR_PLAYER_LIFE.priority,
                    this,
                    null
            );
        }
    }

    /** LB {@code RotationManager.packetHandler}（{@code READ_FINAL_STATE}）的出站分支。 */
    public void onPacketSend(PacketEvent.Send event) {
        PolarRotationManager.INSTANCE.onSendPacket(event);
    }

    /** LB {@code RotationManager.packetHandler}（{@code READ_FINAL_STATE}）的入站分支。 */
    public void onPacketReceive(PacketEvent.Receive event) {
        PolarRotationManager.INSTANCE.onReceivePacket(event);
    }

    /**
     * 托管角落地：LB 的 {@code currentRotation} 由 {@code RotationManager} 持有并被发包含；
     * Epsilon 侧把它直通写进 {@link RotationManager}，不走平滑管线。
     *
     * <p>LB 侧 {@code currentRotation} 归 {@code null} 表示托管结束（目标过期，
     * {@code RotationManager.update()} 把视角还给玩家）——Epsilon 侧必须同步**释放**，
     * 否则伪造角会永久驻留：{@code MovementFix} 会一直按旧角度改写 WASD、
     * 准星也一直停在旧角度（实测反馈：搭路后视角回不来、被扳住）。</p>
     */
    private void applyRotation() {
        Rotation current = PolarRotationManager.INSTANCE.getCurrentRotation();

        if (current == null) {
            if (RotationManager.INSTANCE.isActive()) {
                RotationManager.INSTANCE.setActive(false);
            }
            lastAppliedRotation = null;
            return;
        }

        Rot2f target = new Rot2f(current.getYRot(), current.getXRot());

        // [适配·反作弊] Matrix `sfd.eqr`（equal rotation）专抓"连续多刻完全相同的发包角"（实测 vl 5→25），
        //   `sfd.place.a`（scaffold-like placement, type A）则抓"恒定的俯仰角"（神桥固定 75° 正是它的特征）。
        //   LB 的托管角由平滑器每刻按随机速度推进，天然带变化；直通落地会收敛成一个常量角，
        //   所以按 AGENTS.md 的规则补抖动：幅度取**本机鼠标灵敏度网格的整 1 步**，偏航与俯仰都加，
        //   符号随机（避免 ±1 交替本身成为周期 2 的规律），且只在角度与上一刻完全相同时施加。
        if (lastAppliedRotation != null
                && target.getYaw() == lastAppliedRotation.getYaw()
                && target.getPitch() == lastAppliedRotation.getPitch()) {
            double quantum = RotationUtils.mouseQuantum();
            target = new Rot2f(
                    target.getYaw() + (float) ((jitterRandom.nextBoolean() ? quantum : -quantum)),
                    Mth.clamp(target.getPitch() + (float) ((jitterRandom.nextBoolean() ? quantum : -quantum)), -90.0f, 90.0f)
            );
        }
        lastAppliedRotation = target;

        RotationManager.INSTANCE.setRotationsDirect(target, Priority.Highest);
    }

    /** LB:391-399 {@code handleMovementInput}（{@code MovementInputEvent}，MODEL_STATE）。 */
    public void handleMovementInput(KeyboardInputEvent event) {
        this.currentOptimalLine = null;
        this.rawInput = new DirectionalInput(event.getForward(), event.getStrafe());

        DirectionalInput currentInput = this.rawInput;

        if (currentInput.equals(DirectionalInput.NONE)) {
            return;
        }

        this.currentOptimalLine = ScaffoldMovementPlanner.getOptimalMovementLine(currentInput);
    }

    /** LB:401-425 {@code movementInputHandler}（{@code MovementInputEvent}，SAFETY_FEATURE）。 */
    public void handleMovementInputSafety(KeyboardInputEvent event) {
        if (forceSneak > 0) {
            event.setSneak(true);
            forceSneak--;
        }

        // Ledge feature - AutoJump and AutoSneak
        if (module.polarLedge.getValue()) {
            ScaffoldGodBridgeTechnique technique = activeTechnique();

            Rotation managedRotation = managedRotation();

            // 诊断：边缘判定与模拟的 clipLedged（"不跳"就卡在这两步之一）
            lastCloseToEdge = EntityUtils.isCloseToEdge(mc.player);
            lastClipLedged = PlayerSimulationCache.getSimulationForLocalPlayer().getSnapshotAt(1).clipLedged();

            ScaffoldLedgeFeature.LedgeAction ledgeAction = ScaffoldLedgeFeature.ledge(
                    this.currentTarget,
                    managedRotation,
                    technique
            );

            if (ledgeAction.jump()) {
                event.setJump(true);
            }

            lastLedge = "j" + (ledgeAction.jump() ? 1 : 0)
                    + " s" + ledgeAction.sneakTime()
                    + " st" + (ledgeAction.stopInput() ? 1 : 0)
                    + " b" + (ledgeAction.stepBack() ? 1 : 0);

            if (ledgeAction.stopInput()) {
                event.setForward(0.0f);
                event.setStrafe(0.0f);
            }

            if (ledgeAction.stepBack()) {
                // LB: event.directionalInput = event.directionalInput.copy(forwards = false, backwards = true)
                // [适配] LB 的 directionalInput 之后由 MovementCorrection 按**托管角**解释；
                //        Epsilon 的 MovementFix（HIGH）已经先按托管角重写过 forward/strafe，
                //        所以这里必须借同一个修正器把「在托管坐标系里后退」表达出来。
                event.setForward(-1.0f);
                event.setStrafe(0.0f);

                if (MovementFix.INSTANCE.isEnabled()) {
                    MovementFix.INSTANCE.fixMovement(event, managedRotation.getYRot() + 180.0f);
                }
            }

            if (ledgeAction.sneakTime() > forceSneak) {
                event.setSneak(true);
                forceSneak = ledgeAction.sneakTime();
            }
        }

        // [适配·必需] 搭桥时强制潜行（默认开）：神桥是「蹲着走」——潜行 1.3 格/秒，
        //   而实测放置速率约 3.9 块/秒；全速走路（4.317 格/秒）时桥比人慢，必然踩空。
        if (module.polarSneakWhileBridging.getValue()
                && (event.getForward() != 0.0f || event.getStrafe() != 0.0f)) {
            event.setSneak(true);
        }
    }

    // ===================== LB tickHandler（568–663） =====================

    private void tick() {
        // LB: updateRenderCount(blockCount) —— 渲染计数不搬
        // LB: waitTicks(currentDelay) 的等价物：等待期整段跳过 tickHandler（但 rotationUpdate/update 照常）
        if (waitTicks > 1) {
            waitTicks--;
            return;
        }
        waitTicks = 0;

        if (mc.player.onGround()) {
            // Placement Y is the Y coordinate of the block below the player
            placementY = mc.player.blockPosition().getY() - 1;
            jumps++;
            wasTowering = false;
        }

        if (mc.options.keyJump.isDown()) {
            startY = mc.player.blockPosition().getY();
            jumps = 2;
        }

        // LB: debugParameter("IsTowering") / debugParameter("WasTowering") —— 调试不搬

        TargetFinding.BlockPlacementTarget target = this.currentTarget;
        ScaffoldTechnique technique = activeTechnique();

        Rotation managed = managedRotation();

        Rotation currentRotation;
        if ((module.polarRotationTiming.is(Scaffold.RotationTiming.OnTick)
                || module.polarRotationTiming.is(Scaffold.RotationTiming.OnTickSnap)) && target != null) {
            Rotation techniqueRotation = technique.getRotations(target);
            currentRotation = techniqueRotation != null ? techniqueRotation : managed;
        } else {
            currentRotation = managed;
        }
        currentRotation = currentRotation.normalize();

        BlockHitResult currentCrosshairTarget = technique.getCrosshairTarget(target, currentRotation);

        // [适配] LB 的 delay 决定放置节奏（1 = 不额外节流）。
        //   `Place Delay`(Classic 那套) 仍作为**用户可调的上限**叠加：默认 1/0 时不会额外拖慢
        //   （`nextPlaceDelayTicks()` = placeDelay + rand(0..random) = 1 = 不等待），
        //   想压 Matrix `sfd.dly` 时上调它即可 —— 代价是桥速下降，调到 3 以上就会跟不上走路速度。
        int currentDelay = Math.max(randomDelay(), module.nextPlaceDelayTicks());

        // LB: hasBlockInMainHand / hasBlockInOffHand / handleSilentBlockSelection
        //     [适配] 用 Epsilon 的 findBlockResult()/Swap Mode 设施（用户决策）。
        boolean hasBlockInMainHand = hasBlockInMainHand();
        boolean hasBlockInOffHand = hasBlockInOffHand();

        // Prioritize by all means the main hand if it has a block
        InteractionHand suitableHand = suitableHand(hasBlockInMainHand, hasBlockInOffHand);

        if (simulatePlacementAttempts(currentCrosshairTarget, suitableHand)
                && EntityUtils.moving(mc.player)
                && clicker.isClickTick()) {
            final BlockHitResult fakeHit = currentCrosshairTarget;
            final Rotation fakeRotation = currentRotation;
            final InteractionHand fakeHand = suitableHand;

            clicker.click(() -> {
                // LB: handleSilentBlockSelection → SilentHotbar 静默换槽
                //     [适配] Epsilon 用 Swap Mode 设施（swap()/swapBack()）在交互前后换手，
                //            否则手上不是方块时 useItemOn 用的就是当前手持物，一格都放不出去。
                module.swapForPlacement();
                doPlacement(fakeHit, fakeRotation, fakeHand, () -> {
                    commonPlaceSucceed(fakeHit.getBlockPos().relative(fakeHit.getDirection()));
                    return true;
                }, () -> true, module.polarSwing.getValue());
                module.swapBackAfterPlacement();
                return true;
            });
        }

        if (target == null || currentCrosshairTarget == null) {
            lastGate = currentCrosshairTarget == null ? "no-crosshair" : "no-target";
            return;
        }

        // Does the crosshair target meet the requirements?
        if (!target.doesCrosshairTargetMatchRequirements(currentCrosshairTarget)) {
            lastGate = "crosshair-mismatch";
            return;
        }

        if (!isValidCrosshairTarget(currentCrosshairTarget)) {
            lastGate = "min-dist";
            return;
        }

        if (!hasBlockInMainHand && !hasBlockInOffHand) {
            lastGate = "no-hand";
            return;
        }

        lastGate = "placed";

        InteractionHand handToInteractWith = hasBlockInMainHand ? InteractionHand.MAIN_HAND : InteractionHand.OFF_HAND;
        boolean[] wasSuccessful = new boolean[1];

        if (module.polarRotationTiming.is(Scaffold.RotationTiming.OnTick)
                || module.polarRotationTiming.is(Scaffold.RotationTiming.OnTickSnap)) {
            // Check if server rotation matches the current rotation
            if (!rotationsEqual(currentRotation, PolarRotationManager.INSTANCE.getServerRotation())) {
                sendPosRot(currentRotation);
            }

            if (module.polarRotationTiming.is(Scaffold.RotationTiming.OnTickSnap)) {
                PolarRotationManager.INSTANCE.setRotationTarget(
                        currentRotation,
                        module.polarConsiderInventory.getValue(),
                        rotationValueGroup,
                        PolarRotationManager.Priority.IMPORTANT_FOR_PLAYER_LIFE.priority,
                        this,
                        null
                );
            }
        }

        // Take the fall off position before placing the block
        Vec3 previousFallOffPos = currentOptimalLine != null
                ? ScaffoldMovementPrediction.getFallOffPositionOnLine(currentOptimalLine)
                : null;

        final BlockHitResult placementHit = currentCrosshairTarget;
        final TargetFinding.BlockPlacementTarget placementTarget = target;
        // LB: handleSilentBlockSelection → SilentHotbar 静默换槽
        //     [适配] Epsilon 用 Swap Mode 设施在交互前后换手（同上）。
        module.swapForPlacement();
        doPlacement(placementHit, currentRotation, handToInteractWith, () -> {
            commonPlaceSucceed(placementTarget.placedBlock());
            this.currentTarget = null;
            wasSuccessful[0] = true;
            return true;
        }, () -> true, module.polarSwing.getValue());
        module.swapBackAfterPlacement();

        if (module.polarRotationTiming.is(Scaffold.RotationTiming.OnTick)
                && !rotationsEqual(PolarRotationManager.INSTANCE.getServerRotation(), playerRotation())) {
            sendPosRot(new Rotation(
                    withFixedYaw(currentRotation, mc.player.getYRot()),
                    mc.player.getXRot(),
                    true
            ));
        }

        if (wasSuccessful[0]) {
            ScaffoldMovementPrediction.onPlace(currentOptimalLine, previousFallOffPos);

            waitTicks = currentDelay;
        } else {
            lastGate = "interact-failed";
        }

        lastPlaced = wasSuccessful[0];
        BlockPos placedBlock = placementTarget.placedBlock();
        BlockPos playerBlock = mc.player.blockPosition();
        lastPlacedRel = (placedBlock.getX() - playerBlock.getX()) + "," + (placedBlock.getZ() - playerBlock.getZ())
                + "," + (placedBlock.getY() - playerBlock.getY());
    }

    /** LB {@code commonPlaceSucceed}(placed)。 */
    private void commonPlaceSucceed(BlockPos placed) {
        ScaffoldMovementPlanner.trackPlacedBlock(placed);
        // LB: renderer.addBlock(placed) → Epsilon 的渲染盒
        module.addPlacedRenderBox(placed);
        ScaffoldEagleFeature.INSTANCE.onBlockPlacement();
        // LB: ScaffoldBlinkFeature / ScaffoldSprintControlFeature.onBlockPlacement()
        //     —— Epsilon 有同名模块，这些 feature 在神桥路径只提供放置回调（不搬，映射表登记）
    }

    // ===================== LB getTargetedPosition（687–705） =====================

    /** LB {@code ModuleScaffold.getTargetedPosition(blockPos)}。 */
    public static BlockPos getTargetedPosition(BlockPos blockPos) {
        // LB: isTowering || wasTowering -> towerMode.activeMode.getTargetedPosition(blockPos)
        // Tower 不搬（polar 配置 Tower = None）⇒ 恒 false
        if (false) {
            return blockPos;
        }

        // LB: ScaffoldDownFeature.running && ScaffoldDownFeature.shouldGoDown -> blockPos.offset(0, -2, 0)
        // Epsilon 无对应模块 ⇒ 恒 false
        if (false) {
            return blockPos.offset(0, -2, 0);
        }

        // LB: ScaffoldCeilingFeature.running && ScaffoldCeilingFeature.canConstructCeiling() -> blockPos.offset(0, 3, 0)
        // Epsilon 无对应模块 ⇒ 恒 false
        if (false) {
            return blockPos.offset(0, 3, 0);
        }

        // LB: player.input.keyPresses.jump && (!player.moving || player.horizontalCollision) -> blockPos.offset(0, -1, 0)
        if (mc.player.input.keyPresses.jump() && (!EntityUtils.moving(mc.player) || mc.player.horizontalCollision)) {
            return blockPos.offset(0, -1, 0);
        }

        // LB: else -> sameYMode.getTargetedBlockPos(blockPos) ?: blockPos.offset(0, -1, 0)
        // sameYMode 固定 OFF（polar 配置 SameY = Off）：OFF 的 getTargetedBlockPos 恒为 null
        BlockPos sameYTarget = null;

        return sameYTarget != null ? sameYTarget : blockPos.offset(0, -1, 0);
    }

    // ===================== LB isValidCrosshairTarget（709–720） =====================

    /** LB {@code ModuleScaffold.isValidCrosshairTarget(rayTraceResult)}。 */
    public static boolean isValidCrosshairTarget(BlockHitResult rayTraceResult) {
        Vec3 diff = rayTraceResult.getLocation().subtract(mc.player.getEyePosition());

        Direction side = rayTraceResult.getDirection();

        // Apply minDist
        if (side.getAxis() != Direction.Axis.Y) {
            double dist = (side == Direction.NORTH || side == Direction.SOUTH) ? diff.z : diff.x;

            if (Math.abs(dist) < module.polarMinDist.getValue()) {
                return false;
            }
        }

        return true;
    }

    // ===================== LB simulatePlacementAttempts（722–749） =====================

    private boolean simulatePlacementAttempts(BlockHitResult hitResult, InteractionHand suitableHand) {
        if (suitableHand == null) {
            return false;
        }

        ItemStack stack = mc.player.getItemInHand(suitableHand);

        if (hitResult == null || !module.polarSimulatePlacements.getValue()) {
            return false;
        }

        if (hitResult.getType() != HitResult.Type.BLOCK) {
            return false;
        }

        UseOnContext context = new UseOnContext(mc.player, suitableHand, hitResult);

        boolean canPlaceOnFace = stack.getItem() instanceof BlockItem blockItem
                && blockItem.getPlacementState(new BlockPlaceContext(context)) != null;

        if (module.polarFailedAttemptsOnly.getValue()) {
            return !canPlaceOnFace;
        }

        // LB: sameYMode != SameYMode.OFF && (sameYMode != SameYMode.JUMP_KEY || mc.options.keyJump.isDown)
        // sameYMode 固定 OFF（polar 配置 SameY = Off）⇒ 该分支恒不成立
        if (false) {
            return context.getClickedPos().getY() == placementY
                    && (hitResult.getDirection() != Direction.UP || !canPlaceOnFace);
        }

        boolean isTargetUnderPlayer = context.getClickedPos().getY() <= mc.player.getBlockY() - 1;
        boolean isTowering =
                context.getClickedPos().getY() == mc.player.getBlockY() - 1
                        && canPlaceOnFace
                        && context.getClickedFace() == Direction.UP;

        return isTargetUnderPlayer && !isTowering;
    }

    // ===================== LB doPlacement（BlockExtensions.kt:501–575） =====================

    /**
     * LB {@code utils/block/BlockExtensions.kt} 的 {@code doPlacement(hitResult, rotation, hand, onPlacementSuccess, onItemUseSuccess, swingMode)}。
     */
    private void doPlacement(BlockHitResult hitResult, Rotation rotation, InteractionHand hand,
                             BooleanSupplier onPlacementSuccess, BooleanSupplier onItemUseSuccess,
                             Scaffold.SwingMode swingMode) {
        ItemStack stack = mc.player.getItemInHand(hand);
        int count = stack.getCount();

        InteractionResult useItemOnResult = mc.gameMode.useItemOn(mc.player, hand, hitResult);

        if (useItemOnResult instanceof InteractionResult.Fail) {
            return;
        } else if (useItemOnResult instanceof InteractionResult.Pass) {
            // Ok, we cannot place on the block, so let's just use the item in the direction
            // without targeting a block (for buckets, etc.)
            if (!stack.isEmpty()) {
                // [适配] LB: interaction.useItem(player, hand, rotation.yRot, rotation.xRot)
                //     —— LB 的自定义旋转版 useItem 依赖它自己的 accesswidener / UseItemPacketRotation，
                //     Epsilon 无该设施 ⇒ 用原版 MultiPlayerGameMode.useItem。
                //     神桥路径上 useItemOn 命中合法面时返回 SUCCESS，该分支实际不会走到。
                InteractionResult useItemResult = mc.gameMode.useItem(mc.player, hand);

                if (useItemResult instanceof InteractionResult.Success success) {
                    if (success.swingSource() == InteractionResult.SwingSource.CLIENT && onItemUseSuccess.getAsBoolean()) {
                        module.swingWithMode(hand, swingMode);
                    }

                    mc.gameRenderer.itemInHandRenderer.itemUsed(hand); // <- no condition on this
                }
            }
        } else if (useItemOnResult.consumesAction()) {
            boolean wasStackUsed = !stack.isEmpty() && (stack.getCount() != count || mc.player.hasInfiniteMaterials());

            handleActionsOnAccept(hand, useItemOnResult, wasStackUsed, onPlacementSuccess, swingMode);
        }
    }

    /** LB {@code handleActionsOnAccept}。 */
    private void handleActionsOnAccept(InteractionHand hand, InteractionResult interactionResult,
                                       boolean wasStackUsed, BooleanSupplier shouldSwing,
                                       Scaffold.SwingMode swingMode) {
        if (interactionResult instanceof InteractionResult.Success success
                && success.swingSource() != InteractionResult.SwingSource.CLIENT) {
            return;
        }

        if (shouldSwing.getAsBoolean()) {
            module.swingWithMode(hand, swingMode);
        }

        if (wasStackUsed) {
            mc.gameRenderer.itemInHandRenderer.itemUsed(hand);
        }
    }

    // ===================== 设置同步（[待接线] 阶段 7） =====================

    /**
     * 把 Epsilon 的设置推到 LB 侧对象上。LB 里这些值由设置容器直接读取，
     * Epsilon 侧改为每刻推送（只在变化时写入，避免 {@link Clicker#setCps} 的 {@code fill()} 每刻重建阵列）。
     */
    private void syncSettings() {
        boolean linear = module.polarAngleSmooth.is(Scaffold.AngleSmoothMode.Linear);
        rotationValueGroup.setAngleSmooth(linear ? linearAngleSmooth : sigmoidAngleSmooth);

        if (linear) {
            linearAngleSmooth.setHorizontalTurnSpeedMin(module.polarHorizontalTurnSpeedMin.getValue().floatValue());
            linearAngleSmooth.setHorizontalTurnSpeedMax(module.polarHorizontalTurnSpeedMax.getValue().floatValue());
            linearAngleSmooth.setVerticalTurnSpeedMin(module.polarVerticalTurnSpeedMin.getValue().floatValue());
            linearAngleSmooth.setVerticalTurnSpeedMax(module.polarVerticalTurnSpeedMax.getValue().floatValue());
        } else {
            sigmoidAngleSmooth.setHorizontalTurnSpeedMin(module.polarHorizontalTurnSpeedMin.getValue().floatValue());
            sigmoidAngleSmooth.setHorizontalTurnSpeedMax(module.polarHorizontalTurnSpeedMax.getValue().floatValue());
            sigmoidAngleSmooth.setVerticalTurnSpeedMin(module.polarVerticalTurnSpeedMin.getValue().floatValue());
            sigmoidAngleSmooth.setVerticalTurnSpeedMax(module.polarVerticalTurnSpeedMax.getValue().floatValue());
            sigmoidAngleSmooth.setSteepness(module.polarSteepness.getValue().floatValue());
            sigmoidAngleSmooth.setMidpoint(module.polarMidpoint.getValue().floatValue());
        }

        rotationValueGroup.setMovementCorrection(module.polarMovementCorrection.getValue());
        rotationValueGroup.setResetThreshold(module.polarResetThreshold.getValue().floatValue());
        rotationValueGroup.setTicksUntilReset(module.polarTicksUntilReset.getValue());
        rotationValueGroup.setConsiderInventory(module.polarConsiderInventory.getValue());

        ScaffoldGodBridgeTechnique technique = ScaffoldGodBridgeTechnique.INSTANCE;
        technique.setSelected(true);

        EnumSet<ScaffoldGodBridgeTechnique.Mode> modes = EnumSet.noneOf(ScaffoldGodBridgeTechnique.Mode.class);
        if (module.polarModesJump.getValue()) modes.add(ScaffoldGodBridgeTechnique.Mode.JUMP);
        if (module.polarModesSneak.getValue()) modes.add(ScaffoldGodBridgeTechnique.Mode.SNEAK);
        if (module.polarModesStopInput.getValue()) modes.add(ScaffoldGodBridgeTechnique.Mode.STOP_INPUT);
        if (module.polarModesStepBack.getValue()) modes.add(ScaffoldGodBridgeTechnique.Mode.BACKWARDS);
        technique.setModes(modes);

        technique.setForceSneakBelowCount(module.polarForceSneakBelow.getValue());
        technique.setSneakTime(module.polarSneakTimeMin.getValue(), module.polarSneakTimeMax.getValue());

        ScaffoldEagleFeature.setEnabled(module.polarEagleEnabled.getValue());
        ScaffoldEagleFeature.setBlocksToEagleMin(module.polarBlocksToEagleMin.getValue());
        ScaffoldEagleFeature.setBlocksToEagleMax(module.polarBlocksToEagleMax.getValue());
        ScaffoldEagleFeature.setEdgeDistanceMin(module.polarEdgeDistanceMin.getValue().floatValue());
        ScaffoldEagleFeature.setEdgeDistanceMax(module.polarEdgeDistanceMax.getValue().floatValue());
        ScaffoldEagleFeature.setOnlyOnGround(module.polarOnlyOnGround.getValue());

        int cpsMin = module.polarClickCpsMin.getValue();
        int cpsMax = module.polarClickCpsMax.getValue();
        if (clicker.getCps().first() != cpsMin || clicker.getCps().last() != cpsMax) {
            clicker.setCps(cpsMin, cpsMax);
        }
        if (clicker.getPattern() != module.polarClickTechnique.getValue()) {
            clicker.setPattern(module.polarClickTechnique.getValue());
        }
    }

    /** LB {@code Clicker} 的每刻推进（{@code GameTickEvent} FIRST_PRIORITY）。 */
    public void clickerTick() {
        clicker.gameHandler();
    }

    // ===================== 小工具 =====================

    /** LB {@code activeTechnique}：Tower 不搬 ⇒ 恒为 GodBridge。 */
    private static ScaffoldGodBridgeTechnique activeTechnique() {
        return ScaffoldGodBridgeTechnique.INSTANCE;
    }

    /** LB: {@code RotationManager.currentRotation ?: player.rotation}。 */
    private static Rotation managedRotation() {
        Rotation current = PolarRotationManager.INSTANCE.getCurrentRotation();
        return current != null ? current : playerRotation();
    }

    /** LB: {@code player.rotation} = {@code Rotation(yRot, xRot, true)}。 */
    private static Rotation playerRotation() {
        return new Rotation(mc.player.getYRot(), mc.player.getXRot(), true);
    }

    /** LB: {@code player.withFixedYaw(rotation)} = {@code rotation.yaw + angleDifference(player.yRot, rotation.yaw)}。 */
    private static float withFixedYaw(Rotation rotation, float yaw) {
        return rotation.getYRot() + RotationUtil.angleDifference(yaw, rotation.getYRot());
    }

    /** Kotlin {@code data class Rotation} 的 {@code ==}（含 {@code isNormalized}）。 */
    private static boolean rotationsEqual(Rotation a, Rotation b) {
        if (a == null || b == null) {
            return a == b;
        }
        return a.getYRot() == b.getYRot() && a.getXRot() == b.getXRot() && a.isNormalized() == b.isNormalized();
    }

    /** LB: {@code delay.random()}（{@code intRange} 在 [from, to] 上取均匀随机）。 */
    private int randomDelay() {
        int min = module.polarDelayMin.getValue();
        int max = module.polarDelayMax.getValue();
        return min >= max ? min : ThreadLocalRandom.current().nextInt(min, max + 1);
    }

    /** LB: {@code player.getItemInHand(hand)} 是否是可放置方块（用 Epsilon 的物品选择设施）。 */
    private boolean hasBlockInMainHand() {
        FindItemResult result = module.getBlockResult();
        return result != null && result.found() && !result.isOffhand() && module.canPlaceNow();
    }

    private boolean hasBlockInOffHand() {
        FindItemResult result = module.getBlockResult();
        return result != null && result.found() && result.isOffhand() && module.canPlaceNow();
    }

    /** LB: {@code InteractionHand.entries.firstOrNull { isValidBlock(player.getItemInHand(it)) }}。 */
    private static InteractionHand suitableHand(boolean hasBlockInMainHand, boolean hasBlockInOffHand) {
        if (hasBlockInMainHand) {
            return InteractionHand.MAIN_HAND;
        }
        return hasBlockInOffHand ? InteractionHand.OFF_HAND : null;
    }

    private static void sendPosRot(Rotation rotation) {
        mc.getConnection().send(new ServerboundMovePlayerPacket.PosRot(
                mc.player.position(),
                rotation.getYRot(),
                rotation.getXRot(),
                mc.player.onGround(),
                mc.player.horizontalCollision
        ));
    }

}
