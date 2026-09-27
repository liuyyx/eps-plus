package com.github.epsilon.modules.impl.movement.scaffold.rotation;

import net.minecraft.client.player.LocalPlayer;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.phys.Vec3;

import static com.github.epsilon.Constants.mc;

/**
 * 逐行移植自 LiquidBounce nextgen {@code utils/aiming/utils/RotationUtil.kt}（commit 2d94475）。
 * <p>
 * LB 原文中的两个 {@code LocalPlayer} 扩展函数（{@code setRotation}、{@code withFixedYaw}）按契约 §6
 * 改成静态方法并保留接收者在第一个参数位置。
 */
public final class RotationUtil {

    private static final float MOUSE_TURN_SCALE_FLOAT = 0.15f;
    private static final double MOUSE_TURN_SCALE_DOUBLE = 0.15;

    /**
     * [适配] LB 原文按 {@code isOlderThanOrEqual1_12_2} 分支；Epsilon 只有 26.2 → 该分支不适用，恒取新版。
     * 分支代码结构原样保留。
     */
    private static final boolean IS_OLDER_THAN_OR_EQUAL_1_12_2 = false;

    private RotationUtil() {
    }

    /**
     * LB 原文（顶层扩展函数）：
     * <pre>
     * fun LocalPlayer.setRotation(rotation: Rotation) {
     *     rotation.normalize().let { normalizedRotation ->
     *         xRotO = xRot
     *         yRotO = yRot
     *         yBob = yRot
     *         yBobO = yRot
     *
     *         yRot = normalizedRotation.yaw
     *         xRot = normalizedRotation.pitch
     *     }
     * }
     * </pre>
     */
    public static void setRotation(LocalPlayer player, Rotation rotation) {
        Rotation normalizedRotation = rotation.normalize();

        player.xRotO = player.getXRot();
        player.yRotO = player.getYRot();
        player.yBob = player.getYRot();
        player.yBobO = player.getYRot();

        player.setYRot(normalizedRotation.yaw);
        player.setXRot(normalizedRotation.pitch);
    }

    // LB 原文（顶层扩展函数）：fun LocalPlayer.withFixedYaw(rotation: Rotation) = rotation.yaw + angleDifference(yRot, rotation.yaw)
    public static float withFixedYaw(LocalPlayer player, Rotation rotation) {
        return rotation.yaw + angleDifference(player.getYRot(), rotation.yaw);
    }

    // LB 原文：val gcd: Double get() { ... }
    public static double gcd() {
        double sensitivityFactor = mouseSensitivityFactor();

        // LB 原文按 isOlderThanOrEqual1_12_2 分支；Epsilon 只有 26.2 → 该分支不适用，恒取新版
        if (IS_OLDER_THAN_OR_EQUAL_1_12_2) {
            // LB 原文：(sensitivityFactor * MOUSE_TURN_SCALE_DOUBLE).toFloat().toDouble()
            return (double) (float) (sensitivityFactor * MOUSE_TURN_SCALE_DOUBLE);
        } else {
            // LB 原文：(sensitivityFactor.toFloat() * MOUSE_TURN_SCALE_FLOAT).toDouble()
            return (double) ((float) sensitivityFactor * MOUSE_TURN_SCALE_FLOAT);
        }
    }

    /**
     * Calculates the sensitivity part from the vanilla mouse input path.
     *
     * [1.12.2 reference](https://github.com/WangTingZheng/mcp940/blob/d0c030a4139ce7cf3f284b180f0d9ea87bdf8141/src/minecraft/net/minecraft/client/renderer/EntityRenderer.java#L1268-L1299)
     *
     * @see net.minecraft.client.MouseHandler.turnPlayer
     */
    private static double mouseSensitivityFactor() {
        // LB 原文：val sensitivity = mc.options.sensitivity().get()
        double sensitivity = mc.options.sensitivity().get();

        // LB 原文按 isOlderThanOrEqual1_12_2 分支；Epsilon 只有 26.2 → 该分支不适用，恒取新版
        if (IS_OLDER_THAN_OR_EQUAL_1_12_2) {
            float f = (float) sensitivity * 0.6f + 0.2f;
            return (double) (f * f * f * 8.0f);
        } else {
            double f = sensitivity * 0.6f + 0.2f;
            return f * f * f * 8.0;
        }
    }

    /**
     * Converts the values passed from `MouseHandler.turnPlayer` to the yaw/pitch delta applied by vanilla.
     *
     * [1.12.2 reference](https://github.com/WangTingZheng/mcp940/blob/d0c030a4139ce7cf3f284b180f0d9ea87bdf8141/src/minecraft/net/minecraft/entity/Entity.java#L479-L497)
     *
     * @see net.minecraft.world.entity.Entity.turn
     */
    public static RotationDelta mouseTurnDelta(double cursorDeltaX, double cursorDeltaY) {
        final float deltaPitch;
        final float deltaYaw;

        // LB 原文按 isOlderThanOrEqual1_12_2 分支；Epsilon 只有 26.2 → 该分支不适用，恒取新版
        if (IS_OLDER_THAN_OR_EQUAL_1_12_2) {
            deltaPitch = (float) (cursorDeltaY * MOUSE_TURN_SCALE_DOUBLE);
            deltaYaw = (float) (cursorDeltaX * MOUSE_TURN_SCALE_DOUBLE);
        } else {
            deltaPitch = (float) cursorDeltaY * MOUSE_TURN_SCALE_FLOAT;
            deltaYaw = (float) cursorDeltaX * MOUSE_TURN_SCALE_FLOAT;
        }

        return new RotationDelta(deltaYaw, deltaPitch);
    }

    public static Rotation applyMouseTurnDelta(Rotation rotation, double cursorDeltaX, double cursorDeltaY) {
        RotationDelta delta = mouseTurnDelta(cursorDeltaX, cursorDeltaY);

        return new Rotation(
                rotation.yaw + delta.deltaYaw(),
                // LB 原文：(rotation.pitch + delta.deltaPitch).coerceIn(-90f, 90f)
                Mth.clamp(rotation.pitch + delta.deltaPitch(), -90f, 90f)
        );
    }

    /**
     * Calculates the angle between the cross-hair and the entity.
     *
     * Useful for deciding if the player is looking at something or not.
     */
    public static float crosshairAngleToEntity(Entity entity) {
        LocalPlayer player = mc.player;
        if (player == null) {
            return 0.0F;
        }
        Vec3 eyes = player.getEyePosition();

        Rotation rotationToEntity = Rotation.lookingAt(entity.getBoundingBox().getCenter(), eyes);

        // LB 原文：player.rotation.directionAngleTo(rotationToEntity)
        return new Rotation(player.getYRot(), player.getXRot(), true).directionAngleTo(rotationToEntity);
    }

    /**
     * Calculate difference between two angle points
     */
    public static float angleDifference(float a, float b) {
        return Mth.wrapDegrees(a - b);
    }

}
