/*
 * This file is part of LiquidBounce (https://github.com/CCBlueX/LiquidBounce)
 *
 * Copyright (c) 2015 - 2026 CCBlueX
 *
 * LiquidBounce is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * LiquidBounce is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with LiquidBounce. If not, see <https://www.gnu.org/licenses/>.
 */
package com.github.epsilon.modules.impl.movement.scaffold.technique;

import com.github.epsilon.modules.impl.movement.scaffold.ScaffoldPolarCoordinator;
import com.github.epsilon.modules.impl.movement.scaffold.rotation.Rotation;
import com.github.epsilon.modules.impl.movement.scaffold.simulation.DirectionalInput;
import com.github.epsilon.modules.impl.movement.scaffold.simulation.PlayerSimulationCache;
import com.github.epsilon.modules.impl.movement.scaffold.targetfinding.BlockPosOffsets;
import com.github.epsilon.modules.impl.movement.scaffold.targetfinding.FaceTargetPositionFactory;
import com.github.epsilon.modules.impl.movement.scaffold.targetfinding.Line;
import com.github.epsilon.modules.impl.movement.scaffold.targetfinding.TargetFinding;
import com.github.epsilon.modules.impl.movement.scaffold.util.EntityUtils;
import com.github.epsilon.modules.impl.movement.scaffold.technique.ScaffoldLedgeFeature.LedgeAction;
import com.github.epsilon.modules.impl.movement.scaffold.technique.ScaffoldLedgeFeature.ScaffoldLedgeExtension;
import com.github.epsilon.modules.impl.movement.scaffold.util.BlockUtils;
import com.github.epsilon.modules.impl.movement.scaffold.util.MovementUtils;
import com.github.epsilon.modules.impl.movement.scaffold.util.Raytracing;
import com.github.epsilon.modules.impl.movement.scaffold.util.VectorUtils;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.entity.Pose;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;

import java.util.Collection;
import java.util.EnumSet;
import java.util.NoSuchElementException;
import java.util.concurrent.ThreadLocalRandom;
import java.util.function.Supplier;

import static com.github.epsilon.Constants.mc;

/**
 * 逐行照搬 LiquidBounce `features/module/modules/world/scaffold/techniques/ScaffoldGodBridgeTechnique.kt`（commit 2d94475）。
 * <p>
 * [适配] LB 为 `object`（单例），Epsilon 侧保留单例语义（{@link #INSTANCE}）。
 * LB 直接引用 `ModuleScaffold` 全局对象的地方一律换成 {@link ScaffoldPolarCoordinator} 的同名静态访问器。
 */
public final class ScaffoldGodBridgeTechnique extends ScaffoldTechnique implements ScaffoldLedgeExtension {

    public static final ScaffoldGodBridgeTechnique INSTANCE = new ScaffoldGodBridgeTechnique();

    // LB:58-59
    private static final double VANILLA_GRAVITY = 0.08;
    private static final double VANILLA_VERTICAL_DRAG = 0.98;

    // LB:61-71
    public enum Mode {

        JUMP("Jump", new LedgeAction(true, 0, false, false)),
        SNEAK("Sneak", () -> new LedgeAction(false, INSTANCE.randomSneakTime(), false, false)),
        /**
         * Might not be as consistent as the other modes.
         */
        STOP_INPUT("StopInput", new LedgeAction(false, 0, true, false)),
        BACKWARDS("Backwards", new LedgeAction(false, 0, false, true));

        private final String tag;
        private final Supplier<LedgeAction> creator;

        // LB:70 constructor(tag: String, ledgeAction: LedgeAction) : this(tag, Suppliers.ofInstance(ledgeAction))
        Mode(String tag, LedgeAction ledgeAction) {
            this(tag, () -> ledgeAction);
        }

        Mode(String tag, Supplier<LedgeAction> creator) {
            this.tag = tag;
            this.creator = creator;
        }

        // LB: `override val tag: String`（LB 的 Tagged 接口 Epsilon 无对应，只保留 tag 字段与访问器）
        public String getTag() {
            return tag;
        }

        // LB: `val creator: Supplier<LedgeAction>`
        public Supplier<LedgeAction> getCreator() {
            return creator;
        }

    }

    // LB:73 private val modes by multiEnumChoice("Modes", Mode.JUMP, canBeNone = false)
    // [待接线] 阶段 7 用 4 个 bool 设置（Modes）驱动本字段
    private EnumSet<Mode> modes = EnumSet.of(Mode.JUMP);

    // LB:74 private val forceSneakBelowCount by int("ForceSneakBelowCount", 3, 0..10)
    // 注：LB 源码默认值为 3（polar 存档覆盖为 5，由阶段 7 的设置默认值负责）
    private int forceSneakBelowCount = 3;

    // LB:75 private val sneakTime by intRange("SneakTime", 1..1, 1..10)
    // [待接线] 阶段 7 用 SNEAK 的 Min/Max 两个 int 设置驱动
    private int sneakTimeMin = 1;
    private int sneakTimeMax = 1;

    // LB:131 private var isOnRightSide = false
    private boolean isOnRightSide = false;

    // [适配] LB `Mode.isSelected`（config.types.group.Mode，路径外）：该 technique 是否为当前选中的 mode。
    // 阶段 7 依 GodBridgeVariant 设置本字段。
    private boolean selected = false;

    private ScaffoldGodBridgeTechnique() {
        super("GodBridge");
    }

    public EnumSet<Mode> getModes() {
        return modes;
    }

    public void setModes(EnumSet<Mode> modes) {
        this.modes = modes;
    }

    public int getForceSneakBelowCount() {
        return forceSneakBelowCount;
    }

    public void setForceSneakBelowCount(int forceSneakBelowCount) {
        this.forceSneakBelowCount = forceSneakBelowCount;
    }

    public int getSneakTimeMin() {
        return sneakTimeMin;
    }

    public int getSneakTimeMax() {
        return sneakTimeMax;
    }

    public void setSneakTime(int min, int max) {
        this.sneakTimeMin = min;
        this.sneakTimeMax = max;
    }

    public boolean isSelected() {
        return selected;
    }

    public void setSelected(boolean selected) {
        this.selected = selected;
    }

    // LB:77-129
    @Override
    public LedgeAction ledge(
            TargetFinding.BlockPlacementTarget target,
            Rotation rotation
    ) {
        if (!isSelected()) {
            return LedgeAction.NO_LEDGE;
        }

        PlayerSimulationCache.SimulatedPlayerCache simulatedPlayerCache = PlayerSimulationCache.getSimulationForLocalPlayer();

        // Check if the current rotation is capable of placing a block on the next tick position,
        // this might be inconsistent when the rotation changes on the next tick as well,
        // but we hope it does not. :)
        PlayerSimulationCache.SimulatedPlayerSnapshot snapshotOne = simulatedPlayerCache.getSnapshotAt(1);

        // LB:92 debugParameter("Snapshot Ledged") { snapshotOne.clipLedged } —— 调试渲染不搬（路径外）

        // [适配·修正] LB 原文是 `if (snapshotOne.clipLedged)`，但实测该标志在本移植里几乎恒为 false：
        //   clipLedged 只在 `maybeBackOffFromEdge` 里"模拟玩家本 tick 的位移被边缘收缩"时才置位
        //   （且要求 `isAboveGround()`，跳跃/腾空时为 false），于是 Modes 动作
        //   （Jump/Sneak/StopInput/Backwards）永不触发 —— 实测日志 68/69 行 `ledge=j0`，用户反馈"边缘跳跃/潜行/停止不起作用"。
        //   外层 `ScaffoldLedgeFeature.ledge()` 已经用它自己的 `EntityUtils.isCloseToEdge()` 做过同一判断，
        //   能走到本方法就说明"已经在边缘上"，故这里改为 `clipLedged || isCloseToEdge(...)`；
        //   其余判定（target 为空 / 准星已满足放置要求 → NO_LEDGE；blockCount < forceSneakBelowCount → SNEAK；
        //   JUMP 且能跳两格 → 剔除 JUMP）与结构全部保持 LB 原样。
        boolean atLedge = snapshotOne.clipLedged() || EntityUtils.isCloseToEdge(mc.player);

        if (atLedge) {
            Vec3 cameraPosition = snapshotOne.pos().add(0.0, mc.player.getEyeHeight(), 0.0);
            BlockHitResult currentCrosshairTarget = Raytracing.traceFromPoint(cameraPosition, rotation.directionVector());

            if (target == null) {
                return LedgeAction.NO_LEDGE;
            }

            boolean targetFulfillsRequirements = target.doesCrosshairTargetMatchRequirements(currentCrosshairTarget);
            boolean isValidCrosshairTarget = ScaffoldPolarCoordinator.isValidCrosshairTarget(currentCrosshairTarget);

            // LB:105-106 debugParameter("targetFulfillsRequirements"/"isValidCrosshairTarget") —— 不搬（路径外）

            // Does the crosshair target meet the requirements?
            if (targetFulfillsRequirements && isValidCrosshairTarget) {
                return LedgeAction.NO_LEDGE;
            }

            // If the crosshair target does not meet the requirements,
            // we need to prevent the player from falling off the ledge e.g. by jumping or sneaking.
            Mode currentMode = ScaffoldPolarCoordinator.getBlockCount() < forceSneakBelowCount ? Mode.SNEAK : randomMode(modes);
            Mode effectiveMode;
            if (currentMode == Mode.JUMP && canJumpTwoBlocksHigh()) {
                EnumSet<Mode> filtered = EnumSet.copyOf(modes);
                filtered.remove(Mode.JUMP);
                Mode randomOrNull = randomModeOrNull(filtered);
                effectiveMode = randomOrNull != null ? randomOrNull : Mode.SNEAK;
            } else {
                effectiveMode = currentMode;
            }

            // LB:123-125 effectiveMode.creator.get().also { debugParameter("LastLedgeAction") { it } } —— 调试不搬
            return effectiveMode.getCreator().get();
        } else {
            return LedgeAction.NO_LEDGE;
        }
    }

    // LB:133-150
    @Override
    public TargetFinding.BlockPlacementTarget findPlacementTarget(
            Vec3 predictedPos,
            Pose predictedPose,
            Line optimalLine,
            ItemStack bestStack
    ) {
        TargetFinding.BlockPlacementTargetFindingOptions searchOptions = new TargetFinding.BlockPlacementTargetFindingOptions(
                new TargetFinding.BlockOffsetOptions(
                        BlockPosOffsets.NORMAL.getOffsets(),
                        TargetFinding.BlockPlacementTargetFindingOptions.leastBlockDistanceToPos(predictedPos)
                ),
                new TargetFinding.FaceHandlingOptions(FaceTargetPositionFactory.CenterTargetPositionFactory.INSTANCE),
                bestStack,
                new TargetFinding.PlayerLocationOnPlacement(predictedPos, predictedPose)
        );

        return TargetFinding.findBestBlockPlacementTarget(
                ScaffoldPolarCoordinator.getTargetedPosition(VectorUtils.toBlockPos(predictedPos)),
                searchOptions
        );
    }

    // LB:152-171
    @Override
    public Rotation getRotations(TargetFinding.BlockPlacementTarget target) {
        if (ScaffoldPolarCoordinator.getRawInput().equals(DirectionalInput.NONE)) {
            if (target == null) {
                return null;
            }

            return getRotationForNoInput(target);
        }

        float direction = MovementUtils.getMovementDirectionOfInput(mc.player, ScaffoldPolarCoordinator.getRawInput()) + 180;

        // Round to 45°-steps (NORTH, NORTH_EAST, etc.)
        float movingYaw = (float) Math.rint((double) (direction / 45)) * 45;
        boolean isMovingStraight = movingYaw % 90 == 0f;

        if (isMovingStraight) {
            return getRotationForStraightInput(movingYaw);
        } else {
            return getRotationForDiagonalInput(movingYaw);
        }
    }

    // LB:173-192
    private Rotation getRotationForStraightInput(float movingYaw) {
        if (mc.player.onGround()) {
            isOnRightSide = Math.floor(mc.player.getX() + (float) Math.cos(VectorUtils.toRadians(movingYaw)) * 0.5) != Math.floor(mc.player.getX()) ||
                    Math.floor(mc.player.getZ() + (float) Math.sin(VectorUtils.toRadians(movingYaw)) * 0.5) != Math.floor(mc.player.getZ());

            BlockPos posInDirection = VectorUtils.toBlockPos(mc.player.position()
                    .relative(Direction.fromYRot((double) movingYaw), 0.6));

            // LB: `player.blockPosition().below().state?.isAir == true`
            BlockState leaningState = BlockUtils.state(mc.player.blockPosition().below());
            boolean isLeaningOffBlock = leaningState != null && leaningState.isAir();
            BlockState nextState = BlockUtils.state(posInDirection.below());
            boolean nextBlockIsAir = nextState != null && nextState.isAir();

            if (isLeaningOffBlock && nextBlockIsAir) {
                isOnRightSide = !isOnRightSide;
            }
        }

        float finalYaw = movingYaw + (isOnRightSide ? 45 : -45);
        return new Rotation(finalYaw, 75.7f);
    }

    // LB:194-196
    private Rotation getRotationForDiagonalInput(float movingYaw) {
        return new Rotation(movingYaw, 75.6f);
    }

    // LB:198-205
    private Rotation getRotationForNoInput(TargetFinding.BlockPlacementTarget target) {
        float axisMovement = (float) Math.floor(target.rotation().yaw / 90) * 90;

        float yaw = axisMovement + 45;
        float pitch = 75f;

        return new Rotation(yaw, pitch);
    }

    /**
     * Uses vanilla jump power as the initial upward velocity and then integrates the
     * vanilla per-tick gravity/drag until the upward motion is exhausted.
     *
     * @see net.minecraft.world.entity.LivingEntity#getJumpPower()
     * @see net.minecraft.world.entity.LivingEntity#jumpFromGround()
     */
    // LB:207-225
    private boolean canJumpTwoBlocksHigh() {
        // LB: `player.jumpPower.toDouble()`（= `LocalPlayer.getJumpPower()`，由 AW 打开为 public）
        double verticalMotion = (double) mc.player.getJumpPower();
        double height = 0.0;

        while (verticalMotion > 0.0) {
            height += verticalMotion;
            verticalMotion = (verticalMotion - VANILLA_GRAVITY) * VANILLA_VERTICAL_DRAG;
        }

        // Player can only move up more than one block at a time
        return height >= 2.0;
    }

    // LB: `sneakTime.random()`（config 层 IntRange.random() → Random.nextInt(first, last + 1)，含两端）
    // [适配] RNG：Kotlin Random.Default → ThreadLocalRandom（均匀分布语义一致）
    private int randomSneakTime() {
        return ThreadLocalRandom.current().nextInt(sneakTimeMin, sneakTimeMax + 1);
    }

    // LB: `modes.random()`（kotlin.collections.Collection.random()）
    private static Mode randomMode(Collection<Mode> modes) {
        if (modes.isEmpty()) {
            throw new NoSuchElementException("Collection is empty.");
        }

        return elementAt(modes, ThreadLocalRandom.current().nextInt(modes.size()));
    }

    // LB: `filtered.randomOrNull()`（kotlin.collections.Collection.randomOrNull()）
    private static Mode randomModeOrNull(Collection<Mode> modes) {
        if (modes.isEmpty()) {
            return null;
        }

        return elementAt(modes, ThreadLocalRandom.current().nextInt(modes.size()));
    }

    // LB: kotlin.collections.Collection.elementAt(index)
    private static Mode elementAt(Collection<Mode> modes, int index) {
        int i = 0;
        for (Mode mode : modes) {
            if (i == index) {
                return mode;
            }
            i++;
        }

        throw new IndexOutOfBoundsException("index: " + index + ", size: " + modes.size());
    }

}
