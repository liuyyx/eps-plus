package com.github.epsilon.modules.impl.movement.scaffold.rotation;

import net.minecraft.util.Mth;
import net.minecraft.world.phys.Vec2;

/**
 * 逐行移植自 LiquidBounce nextgen
 * {@code utils/aiming/features/processors/anglesmooth/impl/SigmoidAngleSmooth.kt}（commit 2d94475）。
 * <p>
 * LB 原文：
 * <pre>
 * @Deprecated("Interpolation mode combines Sigmoid and Bezier interpolation", ReplaceWith("InterpolationAngleSmooth"))
 * class SigmoidAngleSmooth(parent: ModeValueGroup&lt;*&gt;) : FactorAngleSmooth("Sigmoid", parent) {
 *     private val horizontalTurnSpeed by floatRange("HorizontalTurnSpeed", 180f..180f, 0.0f..180f)
 *     private val verticalTurnSpeed by floatRange("VerticalTurnSpeed", 180f..180f, 0.0f..180f)
 *     private val steepness by float("Steepness", 10f, 0.0f..20f)
 *     private val midpoint by float("Midpoint", 0.3f, 0.0f..1.0f)
 *     ...
 * }
 * </pre>
 * [适配] LB 的 {@code floatRange}/{@code float} 设置 → Min/Max 与标量字段 + getter/setter，由阶段 7 接 Epsilon 设置
 * （{@code [待接线]}）。数值与计算顺序一字未改。LB 的 {@code @Deprecated} 提示指向 {@code InterpolationAngleSmooth}
 * （本移植不搬该模式），故此处仅保留说明、不加注解。
 */
public final class SigmoidAngleSmooth extends FactorAngleSmooth {

    // LB 原文：horizontalTurnSpeed 的 start / endInclusive
    private float horizontalTurnSpeedMin = 180f;
    private float horizontalTurnSpeedMax = 180f;

    // LB 原文：verticalTurnSpeed 的 start / endInclusive
    private float verticalTurnSpeedMin = 180f;
    private float verticalTurnSpeedMax = 180f;

    private float steepness = 10f;
    private float midpoint = 0.3f;

    public SigmoidAngleSmooth() {
    }

    /**
     * Calculate the factors for the rotation towards the target rotation.
     *
     * @param currentRotation The current rotation
     * @param targetRotation The target rotation
     */
    @Override
    public Vec2 calculateFactors(RotationTarget rotationTarget, Rotation currentRotation, Rotation targetRotation) {
        // LB 原文：currentRotation.rotationDeltaLengthTo(targetRotation).coerceAtMost(180f)
        float rotationDifference = Math.min(currentRotation.rotationDeltaLengthTo(targetRotation), 180f);

        final float horizontalTurnSpeed;
        final float verticalTurnSpeed;
        if (rotationTarget != null) {
            horizontalTurnSpeed = random(horizontalTurnSpeedMin, horizontalTurnSpeedMax);
            verticalTurnSpeed = random(verticalTurnSpeedMin, verticalTurnSpeedMax);
        } else {
            // Slowest turn speed, so we can calculate the slowest turn speed
            // LB 原文：horizontalTurnSpeed.start, verticalTurnSpeed.start
            horizontalTurnSpeed = horizontalTurnSpeedMin;
            verticalTurnSpeed = verticalTurnSpeedMin;
        }

        float horizontalFactor = computeFactor(rotationDifference, horizontalTurnSpeed);
        float verticalFactor = computeFactor(rotationDifference, verticalTurnSpeed);

        return new Vec2(horizontalFactor, verticalFactor);
    }

    private float computeFactor(float rotationDifference, float turnSpeed) {
        float scaledDifference = rotationDifference / 120f;
        double sigmoid = 1 / (1 + Math.exp(-steepness * (scaledDifference - midpoint)));
        double interpolatedSpeed = sigmoid * turnSpeed;

        // LB 原文：interpolatedSpeed.toFloat().coerceIn(0f, 180f)
        return Mth.clamp((float) interpolatedSpeed, 0f, 180f);
    }

    public float getHorizontalTurnSpeedMin() {
        return horizontalTurnSpeedMin;
    }

    public void setHorizontalTurnSpeedMin(float horizontalTurnSpeedMin) {
        this.horizontalTurnSpeedMin = horizontalTurnSpeedMin;
    }

    public float getHorizontalTurnSpeedMax() {
        return horizontalTurnSpeedMax;
    }

    public void setHorizontalTurnSpeedMax(float horizontalTurnSpeedMax) {
        this.horizontalTurnSpeedMax = horizontalTurnSpeedMax;
    }

    public float getVerticalTurnSpeedMin() {
        return verticalTurnSpeedMin;
    }

    public void setVerticalTurnSpeedMin(float verticalTurnSpeedMin) {
        this.verticalTurnSpeedMin = verticalTurnSpeedMin;
    }

    public float getVerticalTurnSpeedMax() {
        return verticalTurnSpeedMax;
    }

    public void setVerticalTurnSpeedMax(float verticalTurnSpeedMax) {
        this.verticalTurnSpeedMax = verticalTurnSpeedMax;
    }

    public float getSteepness() {
        return steepness;
    }

    public void setSteepness(float steepness) {
        this.steepness = steepness;
    }

    public float getMidpoint() {
        return midpoint;
    }

    public void setMidpoint(float midpoint) {
        this.midpoint = midpoint;
    }

}
