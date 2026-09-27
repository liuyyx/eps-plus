/*
 * This file is part of Epsilon.
 *
 * 逐行照搬自 LiquidBounce (https://github.com/CCBlueX/LiquidBounce) 的
 * utils/raytracing/Raytracing.kt。
 *
 * LiquidBounce 是自由软件：你可以依据自由软件基金会发布的 GNU 通用公共许可证
 * （许可证第 3 版或你选择的任何更高版本）条款重新分发和/或修改它。
 */
package com.github.epsilon.modules.impl.movement.scaffold.util;

import com.github.epsilon.modules.impl.movement.scaffold.rotation.PolarRotationManager;
import com.github.epsilon.modules.impl.movement.scaffold.rotation.Rotation;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.phys.shapes.CollisionContext;

import static com.github.epsilon.Constants.mc;

/**
 * LB {@code utils/raytracing/Raytracing.kt} 的逐行移植。
 *
 * <p>[适配] {@code player.rotation} → {@link EntityUtils#rotation(Entity)}；
 * {@code RotationManager.currentRotation} → {@code PolarRotationManager.INSTANCE.getCurrentRotation()}；
 * {@code world} → {@code mc.level}；{@code mc.cameraEntity} → {@code mc.getCameraEntity()}。
 *
 * <p>不搬（路径外）：BlockRaytracing.kt 的 raytraceBlock/rayTraceCollidingBlocks/isFacingBlock 与
 * EntityRaytracing.kt 的 findEntityInCrosshair/isLookingAtEntity（分别服务挖掘与战斗模块，
 * 神桥路径不调用它们，traceFromPlayer/traceFromPoint/hasLineOfSight 也不依赖它们）。
 */
public final class Raytracing {

    private Raytracing() {
    }

    public static BlockHitResult clip(
            BlockGetter self,
            Vec3 from,
            Vec3 to,
            ClipContext.Block block,
            ClipContext.Fluid fluid,
            Entity entity
    ) {
        return self.clip(new ClipContext(from, to, block, fluid, entity));
    }

    public static BlockHitResult clip(
            BlockGetter self,
            Vec3 from,
            Vec3 to,
            ClipContext.Block block,
            ClipContext.Fluid fluid,
            CollisionContext collisionContext
    ) {
        return self.clip(new ClipContext(from, to, block, fluid, collisionContext));
    }

    public static BlockHitResult traceFromPlayer() {
        Rotation currentRotation = PolarRotationManager.INSTANCE.getCurrentRotation();
        return traceFromPlayer(currentRotation != null ? currentRotation : EntityUtils.rotation(mc.player));
    }

    public static BlockHitResult traceFromPlayer(Rotation rotation) {
        return traceFromPlayer(
                rotation,
                Math.max(mc.player.blockInteractionRange(), mc.player.entityInteractionRange())
        );
    }

    public static BlockHitResult traceFromPlayer(Rotation rotation, double range) {
        return traceFromPlayer(rotation, range, ClipContext.Block.OUTLINE);
    }

    public static BlockHitResult traceFromPlayer(Rotation rotation, double range, ClipContext.Block block) {
        return traceFromPlayer(rotation, range, block, ClipContext.Fluid.NONE);
    }

    public static BlockHitResult traceFromPlayer(
            Rotation rotation,
            double range,
            ClipContext.Block block,
            ClipContext.Fluid fluid
    ) {
        return traceFromPlayer(rotation, range, block, fluid, 1f);
    }

    public static BlockHitResult traceFromPlayer(
            Rotation rotation,
            double range,
            ClipContext.Block block,
            ClipContext.Fluid fluid,
            float tickDelta
    ) {
        return traceFromPoint(
                range,
                block,
                fluid,
                mc.player.getEyePosition(tickDelta),
                rotation.directionVector()
        );
    }

    /**
     * [适配] LB 用具名实参调用 {@code traceFromPoint(start = …, direction = …)}（其余取默认值）；
     * Java 不支持具名实参，故补此「前置参数」重载，语义与 LB 的默认取值完全一致。
     */
    public static BlockHitResult traceFromPoint(Vec3 start, Vec3 direction) {
        return traceFromPoint(
                Math.max(mc.player.blockInteractionRange(), mc.player.entityInteractionRange()),
                ClipContext.Block.OUTLINE,
                ClipContext.Fluid.NONE,
                start,
                direction,
                mc.getCameraEntity()
        );
    }

    /**
     * [适配] LB 的 `traceFromPoint` 全部参数都有默认值，`@JvmOverloads` 会生成「省略尾部 entity」的重载；
     * 本重载即该产物（`entity` 取 LB 默认 `mc.cameraEntity!!`）。
     */
    public static BlockHitResult traceFromPoint(
            double range,
            ClipContext.Block block,
            ClipContext.Fluid fluid,
            Vec3 start,
            Vec3 direction
    ) {
        return traceFromPoint(
                range,
                block,
                fluid,
                start,
                direction,
                mc.getCameraEntity()
        );
    }

    public static BlockHitResult traceFromPoint(
            double range,
            ClipContext.Block block,
            ClipContext.Fluid fluid,
            Vec3 start,
            Vec3 direction,
            Entity entity
    ) {
        Vec3 end = start.add(direction.x * range, direction.y * range, direction.z * range);

        return clip(
                mc.level,
                start,
                end,
                block,
                fluid,
                entity
        );
    }

    /**
     * 用于判断某个点是否被墙挡住。
     *
     * @see net.minecraft.world.entity.LivingEntity#hasLineOfSight
     */
    public static boolean hasLineOfSight(Vec3 eyes, Vec3 vec3) {
        return hasLineOfSight(eyes, vec3, mc.player);
    }

    public static boolean hasLineOfSight(Vec3 eyes, Vec3 vec3, Entity entity) {
        return clip(
                entity.level(),
                eyes,
                vec3,
                ClipContext.Block.COLLIDER,
                ClipContext.Fluid.NONE,
                entity
        ).getType() == HitResult.Type.MISS;
    }

}
