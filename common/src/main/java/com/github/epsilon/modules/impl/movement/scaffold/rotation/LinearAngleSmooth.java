package com.github.epsilon.modules.impl.movement.scaffold.rotation;

import net.minecraft.world.phys.Vec2;

/**
 * 逐行移植自 LiquidBounce nextgen
 * {@code utils/aiming/features/processors/anglesmooth/impl/LinearAngleSmooth.kt}（commit 2d94475）。
 * <p>
 * LB 原文：
 * <pre>
 * class LinearAngleSmooth(
 *     parent: ModeValueGroup&lt;*&gt;,
 *     horizontalTurnSpeed: ClosedFloatingPointRange&lt;Float&gt; = 180f..180f,
 *     verticalTurnSpeed: ClosedFloatingPointRange&lt;Float&gt; = 180f..180f,
 * ) : FactorAngleSmooth("Linear", parent) {
 *     private val horizontalTurnSpeed by floatRange("HorizontalTurnSpeed", horizontalTurnSpeed, 0.0f..180f)
 *     private val verticalTurnSpeed by floatRange("VerticalTurnSpeed", verticalTurnSpeed, 0.0f..180f)
 *     ...
 * }
 * </pre>
 * [适配] LB 的 {@code floatRange} 设置（start/endInclusive）→ 本类的 Min/Max 字段 + getter/setter，
 * 由阶段 7 接 Epsilon 设置（{@code [待接线]}）。数值与计算顺序一字未改。
 */
public final class LinearAngleSmooth extends FactorAngleSmooth {

    // LB 原文：horizontalTurnSpeed 的 start / endInclusive
    private float horizontalTurnSpeedMin = 180f;
    private float horizontalTurnSpeedMax = 180f;

    // LB 原文：verticalTurnSpeed 的 start / endInclusive
    private float verticalTurnSpeedMin = 180f;
    private float verticalTurnSpeedMax = 180f;

    public LinearAngleSmooth() {
    }

    // LB 原文：构造参数 horizontalTurnSpeed / verticalTurnSpeed 的默认值（180f..180f / 180f..180f）
    public LinearAngleSmooth(
            float horizontalTurnSpeedMin,
            float horizontalTurnSpeedMax,
            float verticalTurnSpeedMin,
            float verticalTurnSpeedMax
    ) {
        this.horizontalTurnSpeedMin = horizontalTurnSpeedMin;
        this.horizontalTurnSpeedMax = horizontalTurnSpeedMax;
        this.verticalTurnSpeedMin = verticalTurnSpeedMin;
        this.verticalTurnSpeedMax = verticalTurnSpeedMax;
    }

    @Override
    public Vec2 calculateFactors(RotationTarget rotationTarget, Rotation currentRotation, Rotation targetRotation) {
        if (rotationTarget != null) {
            return new Vec2(
                    random(horizontalTurnSpeedMin, horizontalTurnSpeedMax),
                    random(verticalTurnSpeedMin, verticalTurnSpeedMax)
            );
        } else {
            // Slowest turn speed, so we can calculate the slowest turn speed
            // LB 原文：horizontalTurnSpeed.start, verticalTurnSpeed.start
            return new Vec2(horizontalTurnSpeedMin, verticalTurnSpeedMin);
        }
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

}
