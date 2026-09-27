package com.github.epsilon.modules.impl.movement.scaffold.rotation;

import net.minecraft.world.entity.Entity;

import java.util.List;

import static com.github.epsilon.Constants.mc;

/**
 * 逐行移植自 LiquidBounce nextgen {@code utils/aiming/RotationTarget.kt}（commit 2d94475）。
 * <p>
 * LB 原文：{@code class RotationTarget @JvmOverloads constructor(...)}，默认参数按契约 §6 展开为 Java 重载。
 * <p>
 * [适配] LB 的 {@code whenReached: RestrictedSingleUseAction?} → Java {@link Runnable}（LB 的该类只是
 * 「只执行一次」包装，本移植的调用点均传 null；映射表登记）。
 */
public final class RotationTarget {

    private final Rotation rotation;
    private Entity entity;
    private final List<RotationProcessor> processors;
    private final int ticksUntilReset;
    private final float resetThreshold;
    private final boolean considerInventory;
    private final MovementCorrection movementCorrection;
    private final Runnable whenReached;

    public RotationTarget(Rotation rotation) {
        this(rotation, null, List.of(), 1, 1f, false, MovementCorrection.SILENT, null);
    }

    public RotationTarget(
            Rotation rotation,
            Entity entity,
            List<RotationProcessor> processors,
            int ticksUntilReset,
            float resetThreshold,
            boolean considerInventory,
            MovementCorrection movementCorrection
    ) {
        this(rotation, entity, processors, ticksUntilReset, resetThreshold, considerInventory, movementCorrection, null);
    }

    public RotationTarget(
            Rotation rotation,
            Entity entity,
            List<RotationProcessor> processors,
            int ticksUntilReset,
            float resetThreshold,
            boolean considerInventory,
            MovementCorrection movementCorrection,
            Runnable whenReached
    ) {
        this.rotation = rotation;
        this.entity = entity;
        this.processors = processors;
        this.ticksUntilReset = ticksUntilReset;
        this.resetThreshold = resetThreshold;
        this.considerInventory = considerInventory;
        this.movementCorrection = movementCorrection;
        this.whenReached = whenReached;
    }

    public Rotation getRotation() {
        return rotation;
    }

    public Entity getEntity() {
        return entity;
    }

    public void setEntity(Entity entity) {
        this.entity = entity;
    }

    /**
     * The rotation processors which are being used to calculate the next rotation.
     * This list should start with {@link AngleSmooth}
     */
    public List<RotationProcessor> getProcessors() {
        return processors;
    }

    /**
     * The ticks until reset defines the amount of ticks until we are rotating back.
     */
    public int getTicksUntilReset() {
        return ticksUntilReset;
    }

    /**
     * The reset threshold defines the threshold at which we are going to reset the aim plan.
     * The threshold is being calculated by the distance between the current rotation and the rotation we want to aim.
     */
    public float getResetThreshold() {
        return resetThreshold;
    }

    /**
     * Consider if the inventory is open or not. If the inventory is open, we might not want to continue updating.
     */
    public boolean getConsiderInventory() {
        return considerInventory;
    }

    public MovementCorrection getMovementCorrection() {
        return movementCorrection;
    }

    /**
     * What should be done if the target rotation has been reached. Can be {@code null}.
     */
    public Runnable getWhenReached() {
        return whenReached;
    }

    /**
     * Calculates the next rotation to aim at.
     * [currentRotation] is the current rotation or rather last rotation we aimed at. It is being used to calculate the
     * next rotation.
     *
     * We might even return null if we do not want to aim at anything yet.
     */
    public Rotation towards(Rotation currentRotation, boolean isResetting) {
        if (isResetting) {
            entity = null;
            // LB 原文：process(currentRotation, player.rotation)
            return process(currentRotation, new Rotation(mc.player.getYRot(), mc.player.getXRot(), true));
        }

        return process(currentRotation, rotation);
    }

    private Rotation process(Rotation currentRotation, Rotation targetRotation) {
        if (processors.isEmpty()) {
            return targetRotation;
        }

        for (RotationProcessor processor : processors) {
            // We process the rotation with the processor but only the [targetRotation] is being updated.
            targetRotation = processor.process(this, currentRotation, targetRotation);
        }
        return targetRotation;
    }

}
