package com.github.epsilon.modules.impl.movement.scaffold.targetfinding;

import net.minecraft.core.BlockPos;
import net.minecraft.world.phys.Vec3;

/**
 * 逐行照搬自 LiquidBounce {@code utils/block/targetfinding/FaceTargetPositionFactory.kt} 的
 * 接口 + {@code trimFace} + {@code CenterTargetPositionFactory}。
 *
 * <p>其它工厂（NearestRotation / Stabilized / Random / ClickableCenter / BaseYaw 系列 / EdgePoint）
 * 不在神桥 Polar 路径上，未搬。CenterTargetPositionFactory 不依赖 VisibilityPredicate /
 * PositionFactoryConfiguration，故二者未搬。
 */
public interface FaceTargetPositionFactory {

    /**
     * Samples a position (relative to {@code targetPos}).
     *
     * @param face is relative to origin.
     */
    Vec3 producePositionOnFace(AlignedFace face, BlockPos targetPos);

    /**
     * Trims a face to be only as wide as the config allows it to be.
     *
     * @param scale fraction of the face's width removed on each side
     */
    static AlignedFace trimFace(AlignedFace face) {
        return trimFace(face, 0.15);
    }

    static AlignedFace trimFace(AlignedFace face, double scale) {
        Vec3 offsets = face.dimensions().scale(scale);

        double lowX = face.from.x + offsets.x;
        double highX = face.to.x - offsets.x;
        double lowY = face.from.y + offsets.y;
        double highY = face.to.y - offsets.y;
        double lowZ = face.from.z + offsets.z;
        double highZ = face.to.z - offsets.z;

        // Collapse to the center when the interval inverts (scale >= 0.5 or a zero-width axis)
        Vec3 center = face.center();
        double fromX = lowX > highX ? center.x : lowX;
        double toX = lowX > highX ? center.x : highX;
        double fromY = lowY > highY ? center.y : lowY;
        double toY = lowY > highY ? center.y : highY;
        double fromZ = lowZ > highZ ? center.z : lowZ;
        double toZ = lowZ > highZ ? center.z : highZ;

        return new AlignedFace(
                new Vec3(fromX, fromY, fromZ),
                new Vec3(toX, toY, toZ)
        );
    }

    /**
     * Always targets the center of the face.
     */
    final class CenterTargetPositionFactory implements FaceTargetPositionFactory {

        public static final CenterTargetPositionFactory INSTANCE = new CenterTargetPositionFactory();

        private CenterTargetPositionFactory() {
        }

        @Override
        public Vec3 producePositionOnFace(AlignedFace face, BlockPos targetPos) {
            return face.center();
        }
    }
}