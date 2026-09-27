package com.github.epsilon.modules.impl.movement.scaffold.rotation;

import net.minecraft.world.phys.Vec2;

/**
 * 逐行移植自 LiquidBounce nextgen {@code utils/aiming/data/RotationDelta.kt}（commit 2d94475）。
 * <p>
 * LB 原文：{@code @JvmRecord data class RotationDelta(val deltaYaw: Float, val deltaPitch: Float)}。
 */
public record RotationDelta(float deltaYaw, float deltaPitch) {

    // LB 原文：fun length() = hypot(deltaYaw, deltaPitch)
    public float length() {
        return (float) Math.hypot(deltaYaw, deltaPitch);
    }

    // LB 原文：fun toVec2f() = Vec2(deltaYaw, deltaPitch)
    public Vec2 toVec2f() {
        return new Vec2(deltaYaw, deltaPitch);
    }

}
