/*
 * This file is part of Epsilon.
 *
 * 逐行照抄自 OpenPal（https://github.com/OpenPal）MC 1.21.10 的
 * wtf.opal.utility.player.RotationUtility，只做 Yarn → 26.2 Mojang 命名替换：
 *   Vec2f(x, y)/.x/.y → Rot2f(yaw, pitch)/getYaw()/getPitch()
 *   MathHelper.angleBetween(a, b) → Math.abs(Mth.wrapDegrees(b - a))
 *   mc.options.getMouseSensitivity().getValue() → mc.options.sensitivity().get()
 *   Vec3d → Vec3、Box → AABB、Box.getLengthX/Y/Z() → AABB.getXsize/Ysize/Zsize()
 *   Direction.getOffsetX/Y/Z() → Direction.getStepX/Y/Z()
 *   player.getEntityPos() → player.position()、player.getEyePos() → player.getEyePosition()
 *   player.getEyeHeight(pose) → player.getEyeHeight()
 *   player.getBlockInteractionRange() → player.blockInteractionRange()
 *   entity.getTargetingMargin() → entity.getPickRadius()
 *   RandomUtility.getRandomFloat/getJoinRandomDouble → MathUtils.getRandom
 *   RandomUtility.RANDOM → 本类私有 RANDOM（OpenPal 的全局随机源在本仓库没有对应常量）
 *   Vec2f.add(Vec2f) → Rot2f 没有加法重载，按分量相加展开
 */
package com.github.epsilon.utils.player;

import com.github.epsilon.utils.math.MathUtils;
import com.github.epsilon.utils.rotation.Rot2f;
import com.github.epsilon.modules.impl.movement.scaffold.rotation.RotationHelper;
import com.github.epsilon.modules.impl.movement.scaffold.util.MovementUtils;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Random;
import java.util.function.Predicate;

import static com.github.epsilon.Constants.mc;

public final class RotationUtility {

    /** OpenPal {@code RandomUtility.RANDOM} 在本仓库的对应物（仅 {@code getRotationFromRaycastedEntity} 用到）。 */
    private static final Random RANDOM = new Random();

    private RotationUtility() {
    }

    public static float getRotationDifference(Rot2f a, Rot2f b) {
        return Math.abs(Mth.wrapDegrees(b.getYaw() - a.getYaw())) + Math.abs(a.getPitch() - b.getPitch());
    }

    public static double getCursorDelta(double rotationDelta, double sensitivityMultiplier) {
        return (float) (rotationDelta / sensitivityMultiplier) / 0.15F;
    }

    public static Rot2f patchConstantRotation(final Rot2f rotation, final Rot2f prevRotation) {
        final double sensitivity = mc.options.sensitivity().get() * 0.6F + 0.2F;
        final double multiplier = (sensitivity * sensitivity * sensitivity) * 8.0D;
        final double divisor = multiplier * 0.15F;

        final float yawDelta = rotation.getYaw() - prevRotation.getYaw();
        final float pitchDelta = rotation.getPitch() - prevRotation.getPitch();
        final float yaw = prevRotation.getYaw() + (float) (Math.round(yawDelta / divisor) * divisor);
        final float pitch = prevRotation.getPitch() + (float) (Math.round(pitchDelta / divisor) * divisor);
        return new Rot2f(yaw, pitch);
    }

    public static float getSensitivityModifiedRotation(double original) {
        final double sensitivity = mc.options.sensitivity().get() * 0.6F + 0.2F;
        final double multiplier = (sensitivity * sensitivity * sensitivity) * 8.0D;
        return (float) (getCursorDelta(original, multiplier) * multiplier) * 0.15F;
    }

    public static Rot2f getSentRotation(final Rot2f original) {
        return getSensitivityModifiedRotation(patchConstantRotation(original, getRotation()));
    }

    public static Rot2f getSensitivityModifiedRotation(Rot2f original) {
        return new Rot2f(getSensitivityModifiedRotation(original.getYaw()), getSensitivityModifiedRotation(original.getPitch()));
    }

    public static Rot2f getVanillaRotation(Rot2f original) {
        final Rot2f sentRotation = getSentRotation(original);
        final float wrappedYaw = getDuplicateWrapped(sentRotation.getYaw(), mc.player.getYRot());
        return new Rot2f(wrappedYaw, sentRotation.getPitch());
    }

    public static float getDuplicateWrapped(float value, float target) { // makes value in the same 360 range as target, e.g. value = 740 target = 0 it will return 20
        return target + Mth.wrapDegrees(value - target);
    }

    public static Rot2f getRotation() {
        return new Rot2f(mc.player.getYRot(), mc.player.getXRot());
    }

    public static Rot2f getPriorityAngle(final Rot2f currentRotation, final float steps, final boolean snap, final boolean diagonal) {
        // making the player walk towards the center of the block in the closest yaw rounding
        final float targetYaw;
        if (snap) {
            final float rounding = 45.0F / steps;
            final float roundedMoveDir = Math.round(getDirectionDegrees() / rounding) * rounding;

            final float yawRad = (float) Math.toRadians(roundedMoveDir);
            final float offset = 10.0F;
            final float dirX = -Mth.sin(yawRad) * offset;
            final float dirZ = Mth.cos(yawRad) * offset;

            final Vec3 playerPos = mc.player.position();
            final double targetBlockCenterX = Math.floor(playerPos.x) + dirX + 0.5D;
            final double targetBlockCenterZ = Math.floor(playerPos.z) + dirZ + 0.5D;

            final double deltaX = targetBlockCenterX - playerPos.x;
            final double deltaZ = targetBlockCenterZ - playerPos.z;

            targetYaw = (float) Math.toDegrees(Math.atan2(-deltaX, deltaZ));
        } else {
            targetYaw = getDirectionDegrees();
        }
        final float endYaw = targetYaw + MathUtils.getRandom(-0.01F, 0.01F);

        final List<Float> yaws = new ArrayList<>(4);
        if (!diagonal) {
            yaws.add(endYaw);
            yaws.add(endYaw + 180);
        }
        for (int f = 45; f < 180; f += 90) {
            yaws.add(endYaw + f);
            yaws.add(endYaw - f);
        }
        yaws.sort(Comparator.comparingDouble((y) -> Math.abs(Mth.wrapDegrees(y - currentRotation.getYaw()))));
        return new Rot2f(yaws.getFirst(), currentRotation.getPitch());
    }

    @Nullable
    public static RaytracedRotation getRotationFromRaycastedBlock(final BlockPos blockPos, final Direction side, final Rot2f priorityRotations, final Vec3 playerPos) {
//        {
//            final Vec2f currentRotations = getRotation();
//            final HitResult hitResult = RaycastUtility.raycastBlock(mc.player.getBlockInteractionRange(), false, currentRotations.x, currentRotations.y, playerPos);
//            if (hitResult != null && hitResult.getType() == HitResult.Type.BLOCK) {
//                final BlockHitResult blockHitResult = (BlockHitResult) hitResult;
//                final BlockPos hitResultPos = blockHitResult.getBlockPos();
//                final Direction hitResultSide = blockHitResult.getSide();
//
//                if (hitResultPos.equals(blockPos) && hitResultSide == side) {
//                    return new RaytracedRotation(currentRotations, hitResult);
//                }
//            }
//        }

        final AABB box = new AABB(blockPos);

        final Vec3 facedVector = box.getCenter();

        final double widthX = box.getXsize();
        final double height = box.getYsize();
        final double widthZ = box.getZsize();

        final List<RaytracedRotation> rotations = new ArrayList<>();

        final float step = 12.F;
        for (double vx = widthX, x = -vx; x < vx; x += vx / step) {
            for (double vy = height, y = -vy; y < vy; y += vy / step) {
                for (double vz = widthZ, z = -vz; z < vz; z += vz / step) {
                    final Vec3 offsetVector = new Vec3(x, y, z);
                    final Vec3 raytraceVector = facedVector.add(offsetVector);

                    final Rot2f raytraceRotation = getVanillaRotation(getRotationFromPosition(raytraceVector));

                    final HitResult hitResult = RaycastUtility.raycastBlock(mc.player.blockInteractionRange(), false, raytraceRotation.getYaw(), raytraceRotation.getPitch(), playerPos);

                    if (hitResult != null && hitResult.getType() == HitResult.Type.BLOCK) {
                        final BlockHitResult blockHitResult = (BlockHitResult) hitResult;
                        final BlockPos hitResultPos = blockHitResult.getBlockPos();
                        final Direction hitResultSide = blockHitResult.getDirection();

                        if (hitResultPos.equals(blockPos) && hitResultSide == side) {
                            rotations.add(new RaytracedRotation(raytraceRotation, hitResult));
                        }
                    }
                }
            }
        }

        if (rotations.isEmpty()) {
            return null;
        }

        rotations.sort(Comparator.comparingDouble(r -> getRotationDifference(r.rotation(), priorityRotations)));

        return rotations.getFirst();
    }

    @Nullable
    public static RaytracedRotation getRotationFromRaycastedEntity(final LivingEntity entity, final Vec3 closestVector, final double entityInteractionRange) {
        final Predicate<Entity> targetPredicate = e -> e == entity; // to ignore other entities in the raytrace

//        {
//            final Vec2f currentRotations = getRotation();
//            final HitResult hitResult = RaycastUtility.raycastEntity(entityInteractionRange, 1F, currentRotations.x, currentRotations.y, targetPredicate);
//            if (hitResult != null) {
//                return new RaytracedRotation(currentRotations, hitResult);
//            }
//        }

        final AABB box = entity.getBoundingBox().inflate(entity.getPickRadius());
        final Vec3 facedVector = box.getCenter();
        double widthX = box.getXsize();
        double height = box.getYsize();
        double widthZ = box.getZsize();

        final List<RaytracedRotation> rotations = new ArrayList<>();

        final Rot2f rotationFromPosition = RotationUtility.getRotationFromPosition(closestVector);
        final float range = (float) MathUtils.getRandom(0.01D, 0.05D);
        final Rot2f randomAddition = new Rot2f(
                MathUtils.getRandom(-range, range),
                MathUtils.getRandom(-range, range)
        );
        final Rot2f randomClosestRotation = new Rot2f(
                rotationFromPosition.getYaw() + randomAddition.getYaw(),
                rotationFromPosition.getPitch() + randomAddition.getPitch()
        );
        final Rot2f closestVectorRotation = getVanillaRotation(randomClosestRotation);
        final HitResult closestHitResult = RaycastUtility.raycastEntity(entityInteractionRange, 1F, closestVectorRotation.getYaw(), closestVectorRotation.getPitch(), targetPredicate);
        if (closestHitResult != null) {
            return new RaytracedRotation(closestVectorRotation, closestHitResult);
        }

        final float step = 8.F - (RANDOM.nextFloat() * 0.25F);
        for (double vx = widthX, x = -vx; x < vx; x += vx / step) {
            for (double vy = height, y = -vy; y < vy; y += vy / step) {
                for (double vz = widthZ, z = -vz; z < vz; z += vz / step) {
                    final Vec3 offsetVector = new Vec3(x, y, z);
                    final Vec3 raytraceVector = facedVector.add(offsetVector);

                    final Rot2f raytraceRotation = getVanillaRotation(RotationUtility.getRotationFromPosition(raytraceVector));

                    final HitResult hitResult = RaycastUtility.raycastEntity(entityInteractionRange, 1F, raytraceRotation.getYaw(), raytraceRotation.getPitch(), targetPredicate);

                    if (hitResult != null) {
                        rotations.add(new RaytracedRotation(raytraceRotation, hitResult));
                    }
                }
            }
        }

        if (rotations.isEmpty()) {
            return null;
        }

        rotations.sort(Comparator.comparingDouble(r -> RotationUtility.getRotationDifference(r.rotation(), closestVectorRotation)));

        return rotations.getFirst();
    }

    public static Rot2f getRotationFromBlock(final BlockPos blockPos, final Direction direction) {
        final float xDiff = (float) (blockPos.getX() + 0.5 - mc.player.getX() + direction.getStepX() * 0.5);
        final float yDiff = (float) (mc.player.getY() + mc.player.getEyeHeight() - blockPos.getY() - direction.getStepY() * 0.5);
        final float zDiff = (float) (blockPos.getZ() + 0.5 - mc.player.getZ() + direction.getStepZ() * 0.5);

        final double distance = Mth.sqrt(xDiff * xDiff + zDiff * zDiff);

        final float yaw = (float) Math.toDegrees(-Math.atan2(xDiff, zDiff));
        final float pitch = (float) Math.toDegrees(Math.atan(yDiff / distance));

        return new Rot2f(yaw, pitch);
    }

    public static Rot2f getRotationFromPosition(final Vec3 pos) {
        return getRotationFromPosition(mc.player.getEyePosition(), pos);
    }

    public static Rot2f getRotationFromPosition(final Vec3 from, final Vec3 to) {
        final double xDiff = to.x - from.x;
        final double yDiff = to.y - from.y;
        final double zDiff = to.z - from.z;

        final double distance = Math.sqrt(xDiff * xDiff + zDiff * zDiff);

        final float yaw = (float) Math.toDegrees(-Math.atan2(xDiff, zDiff));
        final float pitch = (float) -Math.toDegrees(Math.atan2(yDiff, distance));

        return new Rot2f(yaw, pitch);
    }

    public static Vec3 getRotationVector(float pitch, float yaw) {
        float f = pitch * (float) (Math.PI / 180.0);
        float g = -yaw * (float) (Math.PI / 180.0);
        float h = Mth.cos(g);
        float i = Mth.sin(g);
        float j = Mth.cos(f);
        float k = Mth.sin(f);
        return new Vec3(i * j, -k, h * j);
    }

    public static double getEntityFOV(final Entity entity) {
        final double yawDiff = (RotationHelper.getClientHandler().getYawOr(mc.player.getYRot()) - getRotationFromPosition(entity.position()).getYaw()) % 360.0 + 540.0;
        return yawDiff % 360.0 - 180.0;
    }

    public static boolean isEntityInFOV(final Entity entity, final float fov) {
        if (fov >= 180.F) {
            return true;
        }
        final double angle = getEntityFOV(entity);
        return Math.abs(angle) < fov;
    }

    /**
     * OpenPal {@code MoveUtility.getDirectionDegrees()}（无参）。
     * OpenPal 原文：{@code getDirectionDegrees(RotationHelper.getClientHandler().getYawOr(mc.player.getYaw()))}。
     */
    private static float getDirectionDegrees() {
        return getDirectionDegrees(RotationHelper.getClientHandler().getYawOr(mc.player.getYRot()));
    }

    /**
     * OpenPal {@code MoveUtility.getDirectionDegrees(float yaw)} 的对应物：
     * §2 映射表指定用本仓库已移植的 LB
     * {@link MovementUtils#getMovementDirectionOfInput(float)}（逐分支与 OpenPal 的 {@code getDirection} 等价）。
     */
    private static float getDirectionDegrees(float yaw) {
        return MovementUtils.getMovementDirectionOfInput(yaw);
    }

}
