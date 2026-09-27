package com.github.epsilon.modules.impl.movement.scaffold.targetfinding;

import net.minecraft.core.Direction;
import net.minecraft.core.Vec3i;
import net.minecraft.util.Mth;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

/**
 * A face. Axis aligned.
 *
 * <p>逐行照搬自 LiquidBounce {@code utils/math/geometry/AlignedFace.kt}。
 * 仅保留神桥 Polar 路径实际使用到的方法（getFace / center / truncateY / requireNonEmpty / offset）。
 * 未搬（路径外，且依赖未搬的 LineSegment/NormalizedPlane）：coerceInFace/toPlane/nearestPointTo/
 * getEdges/getDirectionVectors/randomPointOnFace/samplePointOnFace/clamp。
 */
public final class AlignedFace {

    public final Vec3 from;
    public final Vec3 to;

    private Vec3 dimensions;

    public AlignedFace(Vec3 from, Vec3 to) {
        this.from = new Vec3(
                Math.min(from.x, to.x),
                Math.min(from.y, to.y),
                Math.min(from.z, to.z)
        );
        this.to = new Vec3(
                Math.max(from.x, to.x),
                Math.max(from.y, to.y),
                Math.max(from.z, to.z)
        );
    }

    public AABB asBox() {
        return new AABB(from, to);
    }

    public double area() {
        Vec3 dims = dimensions();
        return dims.x * dims.y + dims.y * dims.z + dims.x * dims.z;
    }

    public Vec3 center() {
        return from.lerp(to, 0.5);
    }

    public Vec3 dimensions() {
        // LB: val dimensions: Vec3 by lazy(NONE) { ... }
        if (dimensions == null) {
            dimensions = new Vec3(
                    this.to.x - this.from.x,
                    this.to.y - this.from.y,
                    this.to.z - this.from.z
            );
        }
        return dimensions;
    }

    public AlignedFace requireNonEmpty() {
        return Mth.equal(area(), 0.0) ? null : this;
    }

    public AlignedFace truncateY(double minY) {
        return new AlignedFace(
                new Vec3(from.x, Math.max(from.y, minY), from.z),
                new Vec3(to.x, Math.max(to.y, minY), to.z)
        );
    }

    public AlignedFace offset(Vec3 vec) {
        return new AlignedFace(from.add(vec.x, vec.y, vec.z), to.add(vec.x, vec.y, vec.z));
    }

    public AlignedFace offset(Vec3i vec) {
        return new AlignedFace(
                from.add(vec.getX(), vec.getY(), vec.getZ()),
                to.add(vec.getX(), vec.getY(), vec.getZ())
        );
    }

    /**
     * LB: {@code utils/math/BoxExtensions.kt:164} {@code fun AABB.getFace(direction: Direction): AlignedFace}
     */
    public static AlignedFace getFace(AABB box, Direction direction) {
        return switch (direction) {
            case DOWN -> new AlignedFace(
                    new Vec3(box.minX, box.minY, box.minZ),
                    new Vec3(box.maxX, box.minY, box.maxZ)
            );
            case UP -> new AlignedFace(
                    new Vec3(box.minX, box.maxY, box.minZ),
                    new Vec3(box.maxX, box.maxY, box.maxZ)
            );
            case SOUTH -> new AlignedFace(
                    new Vec3(box.minX, box.minY, box.maxZ),
                    new Vec3(box.maxX, box.maxY, box.maxZ)
            );
            case NORTH -> new AlignedFace(
                    new Vec3(box.minX, box.minY, box.minZ),
                    new Vec3(box.maxX, box.maxY, box.minZ)
            );
            case EAST -> new AlignedFace(
                    new Vec3(box.maxX, box.minY, box.minZ),
                    new Vec3(box.maxX, box.maxY, box.maxZ)
            );
            case WEST -> new AlignedFace(
                    new Vec3(box.minX, box.minY, box.minZ),
                    new Vec3(box.minX, box.maxY, box.maxZ)
            );
        };
    }
}