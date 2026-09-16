package com.github.epsilon.modules.impl.movement;

import com.github.epsilon.events.bus.EventHandler;
import com.github.epsilon.events.impl.KeyboardInputEvent;
import com.github.epsilon.events.impl.PlayerTickEvent;
import com.github.epsilon.managers.rotation.RotationManager;
import com.github.epsilon.modules.Category;
import com.github.epsilon.modules.Module;
import com.github.epsilon.settings.impl.BoolSetting;
import com.github.epsilon.settings.impl.IntSetting;
import com.github.epsilon.utils.player.InvUtils;
import com.github.epsilon.utils.rotation.Priority;
import com.github.epsilon.utils.rotation.Rot2f;
import com.github.epsilon.utils.rotation.RotationUtils;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.util.Mth;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

import static com.github.epsilon.Constants.mc;

/**
 * Telly —— 自动 telly 搭桥，逐行移植自 leader-lite 的 LegitTelly（mode 4）。
 *
 * <p>这是对旧实现的整体重写。旧版本在移植过程中反复打补丁，累积了若干与参考实现
 * 不同的结构性问题，因此按参考实现重新实现，不再继承旧代码：</p>
 *
 * <ul>
 *   <li><b>不再有"武装手势"</b> —— leader 的 LegitTelly 没有这个概念：模块启用即工作，
 *       相位机由"是否落地"驱动。旧版本那套潜行 + 对齐 + 松手的触发链，连同它的判据、
 *       诊断、命中框与提示全部删除。</li>
 *   <li><b>移动不脚本化</b> —— 参考实现只在「落地且按着前进」时注入跳跃，其余交给玩家
 *       自己的输入。旧版本播放 21 帧 WASD 方波，是 Intave 的 move.vert 直接命中的特征。</li>
 *   <li><b>旋转逐刻限速</b> —— 用 {@code smoothRotation} 累积推进，而不是预烘焙曲线配合
 *       不限速的 180 一步到位。</li>
 *   <li><b>目标方块锁定</b> —— 锁住一次搜索结果，避免每刻重搜导致的朝向抖动。</li>
 * </ul>
 *
 * <p>视角与发包同步推进：{@code setYRot/setXRot} 驱动玩家视角，同时提交给
 * {@link RotationManager} 让服务器看到一致的角度。</p>
 */
public class Telly extends Module {

    public static final Telly INSTANCE = new Telly();

    // ─── 设置（对应 leader 的 tellyTicks / forwardSpeed / backSpeed / placeSpeed /
    //     placeDelay / swing / moveFix）───────────────────────────────────────────
    private final IntSetting tellyTicks = intSetting("Telly Ticks", 1, 0, 6, 1);
    private final IntSetting forwardSpeed = intSetting("Forward Speed", 180, 1, 180, 5);
    private final IntSetting backSpeed = intSetting("Back Speed", 180, 1, 180, 5);
    private final IntSetting placeSpeed = intSetting("Place Speed", 180, 1, 180, 5);
    private final IntSetting placeDelay = intSetting("Place Delay", 1, 0, 5, 1);
    private final BoolSetting swing = boolSetting("Swing", true);
    private final BoolSetting moveFix = boolSetting("Move Fix", true);
    private final BoolSetting autoSwap = boolSetting("Auto Swap", true);

    // ─── 相位机（对应 legitTellyPhase / legitTellyPhaseTicks / legitTellyWasAirborne）──
    private static final int PHASE_GROUND = 0;
    private static final int PHASE_ASCENT = 1;
    private static final int PHASE_BRIDGE = 2;
    private static final int PHASE_AFTER_PLACE = 3;

    private int phase = PHASE_GROUND;
    private int phaseTicks = 0;
    private boolean wasAirborne = false;
    private boolean placedFirstBlock = false;

    // ─── 静默/脚本旋转（对应 legitTellySilentYaw/Pitch）─────────────────────────
    private float silentYaw = 0.0f;
    private float silentPitch = 0.0f;

    // ─── 锁定目标（对应 legitTellyLockedBlockData）──────────────────────────────
    private BlockPos lockedPos = null;
    private Direction lockedFace = null;

    // ─── 其它 ──────────────────────────────────────────────────────────────────
    private int placeDelayCounter = 0;
    private int blockCount = 0;
    private int startY = 0;
    private int swapBackSlot = -1;

    private Telly() {
        super("Telly", Category.MOVEMENT);
    }

    // ─── 生命周期 ───────────────────────────────────────────────────────────
    @Override
    protected void onEnable() {
        resetCycle();
        startY = mc.player == null ? 0 : Mth.floor(mc.player.getY());
        silentYaw = mc.player == null ? 0.0f : mc.player.getYRot();
        silentPitch = mc.player == null ? 0.0f : mc.player.getXRot();
        blockCount = countBlocksInHotbar();
    }

    @Override
    protected void onDisable() {
        resetCycle();
        if (lockedPos != null) lockedPos = null;
        lockedFace = null;
        placeDelayCounter = 0;
        releaseKey(mc.options.keyJump);
        if (swapBackSlot >= 0) {
            InvUtils.swap(swapBackSlot, false);
            swapBackSlot = -1;
        }
    }

    private void resetCycle() {
        phase = PHASE_GROUND;
        phaseTicks = 0;
        wasAirborne = false;
        placedFirstBlock = false;
        lockedPos = null;
        lockedFace = null;
    }

    // ─── 主循环（对应 updateLegitTelly）───────────────────────────────────────
    @EventHandler
    private void onPlayerTick(PlayerTickEvent.Pre event) {
        if (nullCheck()) return;

        if (placeDelayCounter > 0) placeDelayCounter--;

        boolean onGround = mc.player.onGround();

        // 落地 → 重置一轮；离地 → 进入上升相位
        if (onGround && wasAirborne) resetCycle();
        if (!onGround && !wasAirborne && phase == PHASE_GROUND) {
            phase = PHASE_ASCENT;
            phaseTicks = 0;
        }
        if (phase == PHASE_ASCENT) {
            phaseTicks++;
            if (phaseTicks >= tellyTicks.getValue()) {
                phase = PHASE_BRIDGE;
                phaseTicks = 0;
            }
        }

        // 每相位用不同转速（对应 forward/back/placeSpeed）
        float speed = forwardSpeed.getValue().floatValue();
        if (phase == PHASE_BRIDGE) speed = backSpeed.getValue().floatValue();
        else if (phase == PHASE_AFTER_PLACE) speed = placeSpeed.getValue().floatValue();

        // 目标角默认是玩家自己的视角 —— 静态帧没有可识别的运动签名
        float targetYaw = mc.player.getYRot();
        float targetPitch = mc.player.getXRot();

        if (phase == PHASE_ASCENT) {
            // 上升段：对准行进方向、看平（对应 getCurrentYaw() + pitch 0）
            targetYaw = movementYaw();
            targetPitch = 0.0f;
        }

        if (phase >= PHASE_BRIDGE) {
            updateLockedTarget();
            if (lockedPos != null && lockedFace != null) {
                Rot2f aim = solveAimAtFace(lockedPos, lockedFace);
                if (aim != null) {
                    targetYaw = aim.getYaw();
                    targetPitch = aim.getPitch();
                }
            }
        }

        // 逐刻限速推进（对应 smoothLegitTellyRotation：俯仰步长为偏航的 0.55 倍）
        float yawStep = clampStep(speed);
        float pitchStep = Math.max(1.0f, yawStep * 0.55f);
        silentYaw += clampFloat(Mth.wrapDegrees(targetYaw - silentYaw), -yawStep, yawStep);
        silentPitch += clampFloat(targetPitch - silentPitch, -pitchStep, pitchStep);
        silentPitch = Mth.clamp(silentPitch, -90.0f, 90.0f);

        // 视角与发包同步推进
        mc.player.setYRot(silentYaw);
        mc.player.setXRot(silentPitch);

        Rot2f base = RotationManager.INSTANCE.lastRotations;
        double distance = Math.hypot(
                Mth.wrapDegrees(silentYaw - base.getYaw()),
                silentPitch - base.getPitch()
        );
        if (distance > 1.0E-6) {
            RotationManager.INSTANCE.setRotations(new Rot2f(silentYaw, silentPitch), distance, Priority.High);
        }

        // 放置：相位 ≥ 2、目标已锁定、冷却结束，且射线自检确认准星确实落在锁定的面上
        if (phase >= PHASE_BRIDGE && lockedPos != null && lockedFace != null && placeDelayCounter <= 0) {
            if (aimsAtLockedFace()) {
                if (placeBlock(lockedPos, lockedFace)) {
                    placeDelayCounter = placeDelay.getValue();
                    if (phase == PHASE_BRIDGE && !placedFirstBlock) {
                        placedFirstBlock = true;
                        phase = PHASE_AFTER_PLACE;
                    }
                    lockedPos = null;
                    lockedFace = null;
                }
            }
        }

        wasAirborne = !onGround;
    }

    // ─── 移动（对应 leader 的 onMoveInput：唯一的注入就是"落地且前进时跳跃"）──
    @EventHandler
    private void onMoveInput(KeyboardInputEvent event) {
        if (nullCheck() || !mc.player.onGround()) return;
        if (!mc.options.keyUp.isDown()) return;
        // 不修改 forward/strafe —— 移动完全交给玩家自己的输入
        event.setJump(true);
    }

    // ─── 目标锁定 ───────────────────────────────────────────────────────────
    private void updateLockedTarget() {
        // 锁定失效的条件：目标方块前方已经不再是可替换方块
        if (lockedPos != null && lockedFace != null) {
            BlockState front = mc.level.getBlockState(lockedPos.relative(lockedFace));
            if (front.canBeReplaced()) return;
            lockedPos = null;
            lockedFace = null;
        }

        BlockPos search = new BlockPos(
                Mth.floor(mc.player.getX()),
                startY,
                Mth.floor(mc.player.getZ())
        );
        findPlacement(search);
    }

    /** 在目标位置周围 9×5×9 内找一个"可被放置的支撑面"（对应 getBlockData）。 */
    private void findPlacement(BlockPos targetPos) {
        if (!mc.level.getBlockState(targetPos).canBeReplaced()) return;

        List<BlockPos> candidates = new ArrayList<>();
        double reach = 4.5;

        for (int x = -4; x <= 4; x++) {
            for (int y = -4; y <= 0; y++) {
                for (int z = -4; z <= 4; z++) {
                    BlockPos pos = targetPos.offset(x, y, z);
                    BlockState state = mc.level.getBlockState(pos);
                    if (state.canBeReplaced()) continue;
                    // 有方块实体的（箱子/熔炉等）视为可交互，跳过 —— 对应 leader 的 isInteractable
                    if (state.hasBlockEntity()) continue;
                    if (mc.player.position().distanceTo(Vec3.atCenterOf(pos)) > reach) continue;

                    for (Direction facing : Direction.values()) {
                        if (facing == Direction.DOWN) continue;
                        if (mc.level.getBlockState(pos.relative(facing)).canBeReplaced()) {
                            candidates.add(pos);
                            break;
                        }
                    }
                }
            }
        }
        if (candidates.isEmpty()) return;

        Vec3 center = Vec3.atCenterOf(targetPos);
        candidates.sort(Comparator.comparingDouble(p -> p.distToCenterSqr(center.x, center.y, center.z)));

        // 逐个尝试：找到第一个能真正瞄准到某个面的候选（对应 getBestFacing + 射线自检）
        for (BlockPos pos : candidates) {
            for (Direction facing : Direction.values()) {
                if (facing == Direction.DOWN) continue;
                if (!mc.level.getBlockState(pos.relative(facing)).canBeReplaced()) continue;
                if (solveAimAtFace(pos, facing) != null) {
                    lockedPos = pos;
                    lockedFace = facing;
                    return;
                }
            }
        }
    }

    /**
     * 求一个能真正命中 {@code pos} 的 {@code face} 的朝向（对应 leader 在 5 个偏移点上
     * 试射线并取离当前托管角最近的那个解）。
     *
     * @return 命中该面的旋转；找不到可用命中点时返回 {@code null}
     */
    private Rot2f solveAimAtFace(BlockPos pos, Direction face) {
        float[] offsets = {0.15f, 0.35f, 0.5f, 0.65f, 0.85f};
        Rot2f best = null;
        double bestScore = Double.MAX_VALUE;

        for (float a : offsets) {
            for (float b : offsets) {
                Vec3 point = facePoint(pos, face, a, b);
                Rot2f rot = RotationUtils.calculate(point);
                if (rot == null) continue;
                BlockHitResult hit = raycastBlock(rot.getYaw(), rot.getPitch(), 4.5);
                if (hit == null || hit.getType() != HitResult.Type.BLOCK) continue;
                if (!hit.getBlockPos().equals(pos)) continue;
                if (hit.getDirection() != face) continue;

                double score = Math.abs(Mth.wrapDegrees(rot.getYaw() - silentYaw))
                        + Math.abs(rot.getPitch() - silentPitch);
                if (score < bestScore) {
                    bestScore = score;
                    best = rot;
                }
            }
        }
        return best;
    }

    /** 按面取该面上的一个点；two 个参数为面内的两个归一化偏移。 */
    private Vec3 facePoint(BlockPos pos, Direction face, float u, float v) {
        double x = pos.getX();
        double y = pos.getY();
        double z = pos.getZ();
        return switch (face) {
            case NORTH -> new Vec3(x + u, y + v, z + 0.02);
            case SOUTH -> new Vec3(x + u, y + v, z + 0.98);
            case WEST -> new Vec3(x + 0.02, y + u, z + v);
            case EAST -> new Vec3(x + 0.98, y + u, z + v);
            case UP -> new Vec3(x + u, y + 0.98, z + v);
            case DOWN -> new Vec3(x + u, y + 0.02, z + v);
        };
    }

    private boolean aimsAtLockedFace() {
        BlockHitResult hit = raycastBlock(silentYaw, silentPitch, 4.5);
        return hit != null
                && hit.getType() == HitResult.Type.BLOCK
                && hit.getBlockPos().equals(lockedPos)
                && hit.getDirection() == lockedFace;
    }

    // ─── 放置 ───────────────────────────────────────────────────────────────
    private boolean placeBlock(BlockPos pos, Direction face) {
        if (autoSwap.getValue()) ensureBlockInHand();
        if (!holdsBlock()) return false;

        BlockHitResult hit = raycastBlock(silentYaw, silentPitch, 4.5);
        if (hit == null) return false;

        InteractionHand hand = mc.player.getOffhandItem().getItem() instanceof BlockItem
                ? InteractionHand.OFF_HAND
                : InteractionHand.MAIN_HAND;

        InteractionResult result = mc.gameMode.useItemOn(mc.player, hand, hit);
        if (!result.consumesAction()) return false;

        if (swing.getValue()) {
            mc.player.swing(hand);
        }
        return true;
    }

    private boolean holdsBlock() {
        return mc.player.getMainHandItem().getItem() instanceof BlockItem
                || mc.player.getOffhandItem().getItem() instanceof BlockItem;
    }

    private void ensureBlockInHand() {
        if (holdsBlock()) {
            blockCount = Math.max(blockCount, 1);
            return;
        }
        int selected = mc.player.getInventory().getSelectedSlot();
        for (int i = 0; i < 9; i++) {
            if (mc.player.getInventory().getItem(i).getItem() instanceof BlockItem) {
                InvUtils.swap(i, false);
                blockCount = Math.max(blockCount, 1);
                return;
            }
        }
        blockCount = 0;
    }

    private int countBlocksInHotbar() {
        int total = 0;
        for (int i = 0; i < 9; i++) {
            ItemStack stack = mc.player.getInventory().getItem(i);
            if (stack.getItem() instanceof BlockItem) total += stack.getCount();
        }
        return total;
    }

    // ─── 工具 ───────────────────────────────────────────────────────────────
    /** 行进方向的 yaw（对应 MoveUtil.adjustYaw：把当前视角按移动输入偏移）。 */
    private float movementYaw() {
        float forward = mc.options.keyUp.isDown() ? 1.0f : mc.options.keyDown.isDown() ? -1.0f : 0.0f;
        float left = mc.options.keyLeft.isDown() ? 1.0f : mc.options.keyRight.isDown() ? -1.0f : 0.0f;
        if (forward == 0.0f && left == 0.0f) return mc.player.getYRot();
        return mc.player.getYRot() + (float) Math.toDegrees(Math.atan2(-left, forward));
    }

    private BlockHitResult raycastBlock(float yaw, float pitch, double reach) {
        Vec3 eyes = mc.player.getEyePosition();
        Vec3 look = lookVector(yaw, pitch);
        Vec3 end = eyes.add(look.x * reach, look.y * reach, look.z * reach);
        return mc.level.clip(new ClipContext(eyes, end,
                ClipContext.Block.COLLIDER, ClipContext.Fluid.NONE, mc.player));
    }

    private Vec3 lookVector(float yaw, float pitch) {
        float yawRad = -yaw * Mth.DEG_TO_RAD;
        float pitchRad = -pitch * Mth.DEG_TO_RAD;
        float cosPitch = Mth.cos(pitchRad);
        return new Vec3(Mth.sin(yawRad) * cosPitch, Mth.sin(pitchRad), Mth.cos(yawRad) * cosPitch);
    }

    private float clampStep(float speed) {
        return Mth.clamp(speed, 1.0f, 180.0f);
    }

    private float clampFloat(float value, float min, float max) {
        return value < min ? min : (value > max ? max : value);
    }

    private void releaseKey(net.minecraft.client.KeyMapping key) {
        key.setDown(false);
    }
}
