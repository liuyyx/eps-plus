package com.github.epsilon.modules.impl.movement.scaffold.rotation;

import net.minecraft.util.Mth;
import net.minecraft.world.phys.Vec3;
import org.joml.Quaternionf;

import static com.github.epsilon.Constants.mc;

/**
 * 逐行移植自 LiquidBounce nextgen {@code utils/aiming/data/Rotation.kt}（commit 2d94475）。
 * <p>
 * LB 原文：{@code @AddonApi @JvmRecord data class Rotation(val yaw: Float, val pitch: Float, val isNormalized: Boolean = false)}。
 * LB 的 {@code data class} 语义（equals/hashCode/toString）在此照搬；{@code isNormalized} 按契约 §4.1 改为私有字段 + 访问器。
 */
public final class Rotation {

    public static final Rotation ZERO = new Rotation(0f, 0f);

    public final float yaw;
    public final float pitch;

    private final boolean isNormalized;

    public Rotation(float yaw, float pitch) {
        this(yaw, pitch, false);
    }

    public Rotation(float yaw, float pitch, boolean isNormalized) {
        this.yaw = yaw;
        this.pitch = pitch;
        this.isNormalized = isNormalized;
    }

    // LB 原文：companion object 中的 @JvmStatic fun lookingAt(point: Vec3, from: Vec3)
    public static Rotation lookingAt(Vec3 point, Vec3 from) {
        return fromRotationVec(point.subtract(from));
    }

    // LB 原文：@JvmStatic fun fromRotationVec(lookVec: Vec3) = fromRotationVec(lookVec.x, lookVec.y, lookVec.z)
    public static Rotation fromRotationVec(Vec3 lookVec) {
        return fromRotationVec(lookVec.x, lookVec.y, lookVec.z);
    }

    // LB 原文：@JvmStatic fun fromRotationVec(diffX: Double, diffY: Double, diffZ: Double)
    public static Rotation fromRotationVec(double diffX, double diffY, double diffZ) {
        return new Rotation(
                // yaw = Mth.wrapDegrees(atan2(diffZ, diffX).toDegrees().toFloat() - 90f)
                Mth.wrapDegrees((float) (Math.atan2(diffZ, diffX) * Mth.RAD_TO_DEG) - 90f),
                // pitch = Mth.wrapDegrees(-atan2(diffY, hypot(diffX, diffZ)).toDegrees().toFloat())
                Mth.wrapDegrees(-((float) (Math.atan2(diffY, Math.hypot(diffX, diffZ)) * Mth.RAD_TO_DEG)))
        );
    }

    // LB 原文：val directionVector: Vec3 get() = Vec3.directionFromRotation(pitch, yaw)
    public Vec3 directionVector() {
        return Vec3.directionFromRotation(pitch, yaw);
    }

    // LB 原文：val xRot: Float get() = pitch
    public float getXRot() {
        return pitch;
    }

    // LB 原文：val yRot: Float get() = yaw
    public float getYRot() {
        return yaw;
    }

    public boolean isNormalized() {
        return isNormalized;
    }

    // LB 原文：@JvmOverloads fun toQuaternion(dest: Quaternionf = Quaternionf()): Quaternionf
    public Quaternionf toQuaternion() {
        return toQuaternion(new Quaternionf());
    }

    // LB 原文：dest.rotationYXZ(Mth.PI - yRot.toRadians(), -xRot.toRadians(), 0f)
    public Quaternionf toQuaternion(Quaternionf dest) {
        return dest.rotationYXZ(Mth.PI - yaw * Mth.DEG_TO_RAD, -(pitch * Mth.DEG_TO_RAD), 0f);
    }

    /**
     * Fixes GCD and Modulo 360° at yaw
     *
     * @return [Rotation] with fixed yaw and pitch
     */
    public Rotation normalize() {
        if (isNormalized) {
            return this;
        }

        double gcd = RotationUtil.gcd();

        // We use the [currentRotation] to calculate the normalized rotation, if it's null, we use
        // the player's rotation
        // [适配] LB 原文：RotationManager.currentRotation ?: player.rotation
        //        → PolarRotationManager.INSTANCE.getCurrentRotation()，为 null 时取 mc.player 的 yaw/pitch
        Rotation currentRotation = PolarRotationManager.INSTANCE.getCurrentRotation();
        if (currentRotation == null) {
            currentRotation = new Rotation(mc.player.getYRot(), mc.player.getXRot(), true);
        }

        // get rotation differences
        RotationDelta diff = currentRotation.rotationDeltaTo(this);

        // proper rounding
        // LB 原文：(diff.deltaYaw / gcd).roundToInt() * gcd
        double g1 = (int) Math.round(diff.deltaYaw() / gcd) * gcd;
        double g2 = (int) Math.round(diff.deltaPitch() / gcd) * gcd;

        // fix rotation
        float yaw = currentRotation.yaw + (float) g1;
        float pitch = currentRotation.pitch + (float) g2;

        // LB 原文：pitch.coerceIn(-90f, 90f)
        return new Rotation(yaw, Mth.clamp(pitch, -90f, 90f), true);
    }

    /**
     * Calculates the great-circle angle between the two view directions.
     *
     * This intentionally ignores differences that do not change the forward vector, such as yaw
     * at a vertical pitch. Use [rotationDeltaLengthTo] for mouse movement, smoothing and rotation
     * state comparisons.
     *
     * @return direction angle in degrees
     */
    public float directionAngleTo(Rotation other) {
        Vec3 direction = directionVector();
        Vec3 otherDirection = other.directionVector();

        return (float) (Math.atan2(
                direction.cross(otherDirection).length(),
                direction.dot(otherDirection)
        ) * Mth.RAD_TO_DEG);
    }

    /**
     * Calculates what angles would need to be added to arrive at [other].
     *
     * Wrapped 360°
     */
    public RotationDelta rotationDeltaTo(Rotation other) {
        return new RotationDelta(
                RotationUtil.angleDifference(other.yaw, this.yaw),
                RotationUtil.angleDifference(other.pitch, this.pitch)
        );
    }

    /**
     * Calculates the Euclidean length of the wrapped yaw/pitch control delta.
     *
     * Unlike [directionAngleTo], this preserves yaw differences at vertical pitches and therefore
     * matches Minecraft's independent mouse, packet and movement rotation axes.
     */
    public float rotationDeltaLengthTo(Rotation other) {
        return rotationDeltaTo(other).length();
    }

    /**
     * Calculates a new rotation that is closer to the [other] rotation by a limiting factor of
     * [horizontalFactor] and [verticalFactor], which should be between 0 and 180 degrees.
     */
    public Rotation towardsLinear(Rotation other, float horizontalFactor, float verticalFactor) {
        RotationDelta diff = rotationDeltaTo(other);
        float rotationDifference = diff.length();
        float straightLineYaw = Math.abs(diff.deltaYaw() / rotationDifference) * horizontalFactor;
        float straightLinePitch = Math.abs(diff.deltaPitch() / rotationDifference) * verticalFactor;

        // LB 原文：
        //   y = diff.deltaYaw.coerceIn(-straightLineYaw, straightLineYaw)
        //   x = diff.deltaPitch.coerceIn(-straightLinePitch, straightLinePitch)
        return this.add(
                Mth.clamp(diff.deltaPitch(), -straightLinePitch, straightLinePitch),
                Mth.clamp(diff.deltaYaw(), -straightLineYaw, straightLineYaw)
        );
    }

    /**
     * Interpolates this rotation towards [other] using the given [factor].
     */
    public Rotation interpolateTo(Rotation other, float factor) {
        return new Rotation(
                Math.fma(factor, other.yaw - yaw, yaw),
                Math.fma(factor, other.pitch - pitch, pitch)
        );
    }

    // LB 原文：@JvmOverloads fun isDirectionCloseTo(other: Rotation, tolerance: Float = 2f)
    public boolean isDirectionCloseTo(Rotation other) {
        return isDirectionCloseTo(other, 2f);
    }

    public boolean isDirectionCloseTo(Rotation other, float tolerance) {
        return directionAngleTo(other) <= tolerance;
    }

    // LB 原文：@JvmOverloads fun isRotationDeltaCloseTo(other: Rotation, tolerance: Float = 2f)
    public boolean isRotationDeltaCloseTo(Rotation other) {
        return isRotationDeltaCloseTo(other, 2f);
    }

    public boolean isRotationDeltaCloseTo(Rotation other, float tolerance) {
        return rotationDeltaLengthTo(other) <= tolerance;
    }

    // LB 原文：fun add(x: Float, y: Float) = Rotation(yaw = this.yRot + y, pitch = this.xRot + x)
    public Rotation add(float x, float y) {
        return new Rotation(this.getYRot() + y, this.getXRot() + x);
    }

    // [适配] LB 为 data class，以下为 Kotlin 生成语义的等价实现
    @Override
    public boolean equals(Object obj) {
        if (this == obj) {
            return true;
        }
        if (!(obj instanceof Rotation other)) {
            return false;
        }
        return Float.compare(yaw, other.yaw) == 0
                && Float.compare(pitch, other.pitch) == 0
                && isNormalized == other.isNormalized;
    }

    @Override
    public int hashCode() {
        int result = Float.hashCode(yaw);
        result = 31 * result + Float.hashCode(pitch);
        result = 31 * result + Boolean.hashCode(isNormalized);
        return result;
    }

    @Override
    public String toString() {
        return "Rotation(yaw=" + yaw + ", pitch=" + pitch + ", isNormalized=" + isNormalized + ")";
    }

}
