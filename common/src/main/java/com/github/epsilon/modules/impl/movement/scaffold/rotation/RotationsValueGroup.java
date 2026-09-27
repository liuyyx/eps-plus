package com.github.epsilon.modules.impl.movement.scaffold.rotation;

import net.minecraft.world.entity.Entity;

import java.util.ArrayList;
import java.util.List;

/**
 * 逐行移植自 LiquidBounce nextgen {@code utils/aiming/RotationsValueGroup.kt}（commit 2d94475）——只留数据 + 两个方法。
 * <p>
 * LB 原文：
 * <pre>
 * open class RotationsValueGroup(
 *     owner: EventListener,
 *     movementCorrection: MovementCorrection = MovementCorrection.SILENT,
 *     combatSpecific: Boolean = false
 * ) : ValueGroup("Rotations") {
 *     private val angleSmooth = modes(owner, "AngleSmooth", 0) { ... }
 *     private val shortStop = if (combatSpecific) tree(ShortStopRotationProcessor(owner)) else null
 *     private val fail = if (combatSpecific) tree(FailRotationProcessor(owner)) else null
 *     private val movementCorrection by enumChoice("MovementCorrection", movementCorrection)
 *     private val resetThreshold by float("ResetThreshold", 2f, 1f..180f)
 *     private val ticksUntilReset by int("TicksUntilReset", 5, 1..30, "ticks")
 *     fun toRotationTarget(rotation, entity = null, considerInventory = false, whenReached = null) = ...
 *     fun calculateTicks(rotation: Rotation) = angleSmooth.activeMode.calculateTicks(RotationManager.actualServerRotation, rotation)
 * }
 * </pre>
 * [适配] LB 的 {@code ValueGroup}/{@code modes}/{@code enumChoice}/{@code float}/{@code int} 设置容器不搬
 * （契约 §4.1）→ 全部改成字段 + getter/setter，默认值取 LB 源码默认；由阶段 7 接 Epsilon 设置（{@code [待接线]}）。
 * <p>
 * LB 为 {@code open class}（可实例化、允许继承）→ 本类同样非 final（协调层可直接 {@code new RotationsValueGroup()}）。
 */
public class RotationsValueGroup {

    /**
     * LB 原文：{@code modes(owner, "AngleSmooth", 0) { listOfNotNull(LinearAngleSmooth(it), SigmoidAngleSmooth(it), ...) }}
     * 的 {@code activeMode}。
     * <p>
     * [适配] 默认值取 polar 配置的 {@code Angle Smooth = Sigmoid}（LB 源码的 modes 默认索引 0 为 Linear，
     * 但本变体只使用 polar 存档值）；其它模式（Interpolation/Acceleration/Ai）不搬。
     */
    private AngleSmooth angleSmooth = new SigmoidAngleSmooth();

    // LB 原文：private val movementCorrection by enumChoice("MovementCorrection", movementCorrection)（默认 SILENT）
    private MovementCorrection movementCorrection = MovementCorrection.SILENT;

    // LB 原文：private val resetThreshold by float("ResetThreshold", 2f, 1f..180f)
    private float resetThreshold = 2f;

    // LB 原文：private val ticksUntilReset by int("TicksUntilReset", 5, 1..30, "ticks")
    private int ticksUntilReset = 5;

    // LB 原文：toRotationTarget(..., considerInventory: Boolean = false) 的默认值
    private boolean considerInventory = false;

    public AngleSmooth getAngleSmooth() {
        return angleSmooth;
    }

    public void setAngleSmooth(AngleSmooth angleSmooth) {
        this.angleSmooth = angleSmooth;
    }

    public MovementCorrection getMovementCorrection() {
        return movementCorrection;
    }

    public void setMovementCorrection(MovementCorrection movementCorrection) {
        this.movementCorrection = movementCorrection;
    }

    public float getResetThreshold() {
        return resetThreshold;
    }

    public void setResetThreshold(float resetThreshold) {
        this.resetThreshold = resetThreshold;
    }

    public int getTicksUntilReset() {
        return ticksUntilReset;
    }

    public void setTicksUntilReset(int ticksUntilReset) {
        this.ticksUntilReset = ticksUntilReset;
    }

    public boolean getConsiderInventory() {
        return considerInventory;
    }

    public void setConsiderInventory(boolean considerInventory) {
        this.considerInventory = considerInventory;
    }

    /**
     * LB 原文：
     * <pre>
     * fun toRotationTarget(rotation, entity = null, considerInventory = false, whenReached = null) = RotationTarget(
     *     rotation,
     *     entity,
     *     listOfNotNull(angleSmooth.activeMode, fail?.takeIf { it.running }, shortStop?.takeIf { it.running }),
     *     ticksUntilReset,
     *     resetThreshold,
     *     considerInventory,
     *     movementCorrection,
     *     whenReached
     * )
     * </pre>
     * [适配] {@code fail}/{@code shortStop} 只在 {@code combatSpecific = true} 时存在（FailRotationProcessor /
     * ShortStopRotationProcessor），不在神桥路径上 → 不搬，processors 只含 {@code angleSmooth}。
     * {@code whenReached} 的 LB 类型 {@code RestrictedSingleUseAction?} → {@link Runnable}。
     */
    public RotationTarget toRotationTarget(
            Rotation rotation,
            Entity entity,
            boolean considerInventory,
            Runnable whenReached
    ) {
        List<RotationProcessor> processors = new ArrayList<>();
        processors.add(angleSmooth);

        return new RotationTarget(
                rotation,
                entity,
                processors,
                ticksUntilReset,
                resetThreshold,
                considerInventory,
                movementCorrection,
                whenReached
        );
    }

    /**
     * 契约 §4.1 的重载（等价 LB 的默认参数 {@code entity = null}、{@code whenReached = null}）。
     */
    public RotationTarget toRotationTarget(Rotation rotation, Entity entity, boolean considerInventory) {
        return toRotationTarget(rotation, entity, considerInventory, null);
    }

    /**
     * 契约 §4.1 的重载（等价 LB 默认参数 {@code entity = null, considerInventory = false, whenReached = null}）。
     */
    public RotationTarget toRotationTarget(Rotation rotation) {
        return toRotationTarget(rotation, null, false, null);
    }

    /**
     * How long it takes to rotate to a rotation in ticks
     *
     * Calculates the difference from the server rotation to the target rotation and divides it by the
     * minimum turn speed (to make sure we are always there in time)
     *
     * @param rotation The rotation to rotate to
     * @return The amount of ticks it takes to rotate to the rotation
     */
    public int calculateTicks(Rotation rotation) {
        return angleSmooth.calculateTicks(PolarRotationManager.INSTANCE.getActualServerRotation(), rotation);
    }

}
