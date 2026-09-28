/*
 * This file is part of Epsilon.
 *
 * 逐行照抄自 OpenPal（https://github.com/OpenPal）MC 1.21.10 的
 * wtf.opal.utility.player.RaycastUtility，只做 Yarn → 26.2 Mojang 命名替换：
 *   mc.world.raycast(RaycastContext) → mc.level.clip(ClipContext)
 *   RaycastContext.ShapeType/FluidHandling → ClipContext.Block/Fluid
 *   Vec3d → Vec3、MathHelper → Mth、Box → AABB
 *   Box.stretch/expand → AABB.expandTowards/inflate
 *   entity.lastX/lastY/lastZ → entity.xOld/yOld/zOld
 *   entity.getStandingEyeHeight() → entity.getEyeHeight()
 *   ProjectileUtil.raycast → ProjectileUtil.getEntityHitResult（6 参重载，签名一致）
 *   Guava Predicate → java.util.function.Predicate
 */
package com.github.epsilon.utils.player;

import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.projectile.ProjectileUtil;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.EntityHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;
import net.minecraft.util.Mth;

import java.util.function.Predicate;

import static com.github.epsilon.Constants.mc;

public final class RaycastUtility {

    private RaycastUtility() {
    }

    public static HitResult raycastBlock(final double maxDistance, final float tickDelta, final boolean includeFluids, final float yaw, final float pitch) {
        final Vec3 start = RaycastUtility.getCameraPosVec(tickDelta, mc.player);
        return raycastBlock(maxDistance, includeFluids, yaw, pitch, start);
    }

    public static HitResult raycastBlock(final double maxDistance, final boolean includeFluids, final float yaw, final float pitch, final Vec3 start) {
        final Vec3 rotationVector = RotationUtility.getRotationVector(pitch, yaw);

        final Vec3 end = start.add(rotationVector.x * maxDistance, rotationVector.y * maxDistance, rotationVector.z * maxDistance);

        return mc.level.clip(new ClipContext(start, end, ClipContext.Block.OUTLINE, includeFluids ? ClipContext.Fluid.ANY : ClipContext.Fluid.NONE, mc.player));
    }

    public static EntityHitResult raycastEntity(final double maxDistance, final float tickDelta, final float yaw, final float pitch, final Predicate<Entity> predicate) {
        return raycastEntity(maxDistance, RaycastUtility.getCameraPosVec(tickDelta, mc.player), yaw, pitch, predicate);
    }

    public static EntityHitResult raycastEntity(final double maxDistance, final Vec3 start, final float yaw, final float pitch, final Predicate<Entity> predicate) {
        final Vec3 rotationVector = RotationUtility.getRotationVector(pitch, yaw);

        final Vec3 end = start.add(rotationVector.x * maxDistance, rotationVector.y * maxDistance, rotationVector.z * maxDistance);

        final AABB box = mc.player.getBoundingBox().expandTowards(rotationVector.scale(maxDistance)).inflate(1, 1, 1);

        return ProjectileUtil.getEntityHitResult(mc.player, start, end, box, predicate, Mth.square(maxDistance));
    }

    public static Vec3 getCameraPosVec(final float tickDelta, final Entity entity) {
        final double x = Mth.lerp(tickDelta, entity.xOld, entity.getX());
        final double y = Mth.lerp(tickDelta, entity.yOld, entity.getY()) + (double) entity.getEyeHeight();
        final double z = Mth.lerp(tickDelta, entity.zOld, entity.getZ());
        return new Vec3(x, y, z);
    }
}
