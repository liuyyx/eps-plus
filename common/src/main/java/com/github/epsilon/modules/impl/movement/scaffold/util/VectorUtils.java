/*
 * This file is part of Epsilon.
 *
 * 逐行照搬自 LiquidBounce (https://github.com/CCBlueX/LiquidBounce) 的
 * utils/math/MinecraftVectorExtensions.kt、utils/math/BoxExtensions.kt、
 * utils/math/BlockBoxExtensions.kt、utils/math/MathExtensions.kt、
 * utils/math/NumberExtensions.kt（后两者仅搬神桥路径依赖的数值辅助）。
 *
 * LiquidBounce 是自由软件：你可以依据自由软件基金会发布的 GNU 通用公共许可证
 * （许可证第 3 版或你选择的任何更高版本）条款重新分发和/或修改它。
 */
package com.github.epsilon.modules.impl.movement.scaffold.util;

import it.unimi.dsi.fastutil.longs.LongComparator;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.Position;
import net.minecraft.core.Vec3i;
import net.minecraft.util.Mth;
import net.minecraft.world.level.levelgen.structure.BoundingBox;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec2;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.phys.shapes.VoxelShape;
import org.joml.Vector3f;
import org.joml.Vector3fc;

import java.util.ArrayList;
import java.util.List;

import static java.lang.Math.abs;
import static java.lang.Math.atan2;
import static java.lang.Math.sqrt;

/**
 * LB {@code utils/math/MinecraftVectorExtensions.kt} + {@code BoxExtensions.kt} + {@code BlockBoxExtensions.kt}
 * 中与 Vec2/Vec3/Vec3i/BlockPos/AABB 相关的扩展函数逐行移植。
 *
 * <p>不搬（映射表登记理由）：Kotlin 解构 component1..3、Vec3.toVec3f（LB 渲染引擎的 Vec3f 类型）、
 * Vec3.toVec3i（LB 已 @Deprecated）、ChunkPos.contains 与 ChunkPos/ChunkAccess.toBlockBox
 * （区块扫描，路径外）、AABB.getFace（已由 targetfinding.AlignedFace.getFace 承载）、
 * AABB.isHitByLine（依赖 LinearGeometry3.intersects，路径外未搬）。
 */
public final class VectorUtils {

    private VectorUtils() {
    }

    // ---------------------------------------------------------------- Vec2

    public static Vec2 copy(Vec2 vec) {
        return copy(vec, vec.x, vec.y);
    }

    public static Vec2 copy(Vec2 vec, float x) {
        return copy(vec, x, vec.y);
    }

    public static Vec2 copy(Vec2 vec, float x, float y) {
        return new Vec2(x, y);
    }

    public static boolean isLikelyZero(Vec2 vec) {
        return Mth.equal(vec.lengthSquared(), 0.0F);
    }

    // --------------------------------------------------------------- Vec3i

    /**
     * @see Vec3i#compareTo
     */
    public static final class BlockPosAsLongComparator implements LongComparator {

        public static final BlockPosAsLongComparator INSTANCE = new BlockPosAsLongComparator();

        private BlockPosAsLongComparator() {
        }

        @Override
        public int compare(long k1, long k2) {
            int y1 = BlockPos.getY(k1);
            int y2 = BlockPos.getY(k2);
            if (y1 == y2) {
                int z1 = BlockPos.getZ(k1);
                int z2 = BlockPos.getZ(k2);
                return z1 == z2 ? BlockPos.getX(k1) - BlockPos.getX(k2) : z1 - z2;
            } else {
                return y1 - y2;
            }
        }

    }

    public static BoundingBox rangeTo(BlockPos self, BlockPos other) {
        return BoundingBox.fromCorners(self, other);
    }

    public static BlockPos.MutableBlockPos set(BlockPos.MutableBlockPos self, Position pos) {
        return self.set(pos.x(), pos.y(), pos.z());
    }

    public static Vec3 center(Vec3i self) {
        return Vec3.atCenterOf(self);
    }

    public static Vec3 bottomCenter(Vec3i self) {
        return Vec3.atBottomCenterOf(self);
    }

    public static Vec3 topCenter(Vec3i self) {
        return Vec3.upFromBottomCenterOf(self, 1.0);
    }

    public static Vec3 bottomCenter(Vec3i self, double yOffset) {
        return Vec3.upFromBottomCenterOf(self, yOffset);
    }

    public static Vec3i negate(Vec3i self) {
        return new Vec3i(-self.getX(), -self.getY(), -self.getZ());
    }

    public static BlockPos negate(BlockPos self) {
        return new BlockPos(-self.getX(), -self.getY(), -self.getZ());
    }

    public static BlockPos copy(BlockPos self) {
        return copy(self, self.getX(), self.getY(), self.getZ());
    }

    public static BlockPos copy(BlockPos self, int x) {
        return copy(self, x, self.getY(), self.getZ());
    }

    public static BlockPos copy(BlockPos self, int x, int y) {
        return copy(self, x, y, self.getZ());
    }

    public static BlockPos copy(BlockPos self, int x, int y, int z) {
        return new BlockPos(x, y, z);
    }

    public static Vec3i plus(Vec3i self, Vec3i other) {
        return self.offset(other);
    }

    public static BlockPos plus(BlockPos self, Vec3i other) {
        return self.offset(other);
    }

    public static Vec3i minus(Vec3i self, Vec3i other) {
        return self.subtract(other);
    }

    public static Vec3i times(Vec3i self, int scalar) {
        return self.multiply(scalar);
    }

    public static long lengthSqr(Vec3i self) {
        long x1 = self.getX();
        long y1 = self.getY();
        long z1 = self.getZ();
        return x1 * x1 + y1 * y1 + z1 * z1;
    }

    public static Vec3 toVec3d(Vec3i self) {
        return toVec3d(self, 0.0, 0.0, 0.0);
    }

    public static Vec3 toVec3d(Vec3i self, double xOffset) {
        return toVec3d(self, xOffset, 0.0, 0.0);
    }

    public static Vec3 toVec3d(Vec3i self, double xOffset, double yOffset) {
        return toVec3d(self, xOffset, yOffset, 0.0);
    }

    public static Vec3 toVec3d(Vec3i self, double xOffset, double yOffset, double zOffset) {
        return new Vec3(self.getX() + xOffset, self.getY() + yOffset, self.getZ() + zOffset);
    }

    // ---------------------------------------------------------------- Vec3

    public static Vec3 negate(Vec3 self) {
        return self.reverse();
    }

    public static Vec3 plus(Vec3 self, Position other) {
        return self.add(other.x(), other.y(), other.z());
    }

    /**
     * @return {@code self + scale * other}
     */
    public static Vec3 fma(Vec3 self, double scale, Vec3 other) {
        return new Vec3(
                Math.fma(scale, other.x, self.x),
                Math.fma(scale, other.y, self.y),
                Math.fma(scale, other.z, self.z)
        );
    }

    public static Vec3 plus(Vec3 self, Vec3i other) {
        return self.add((double) other.getX(), (double) other.getY(), (double) other.getZ());
    }

    public static Vec3 minus(Vec3 self, Position other) {
        return self.subtract(other.x(), other.y(), other.z());
    }

    public static Vec3 minus(Vec3 self, Vec3i other) {
        return self.subtract((double) other.getX(), (double) other.getY(), (double) other.getZ());
    }

    public static Vec3 times(Vec3 self, double scalar) {
        return self.scale(scalar);
    }

    public static double dot(Vec3 self, double x, double y, double z) {
        return self.x * x + self.y * y + self.z * z;
    }

    public static double dot(Vec3 self, Vector3fc other) {
        return self.x * other.x() + self.y * other.y() + self.z * other.z();
    }

    /**
     * {@code self.normalize().scale(newLength)}
     *
     * @return 与 self 同方向、长度为 newLength 的 Vec3
     */
    public static Vec3 withLength(Vec3 self, double newLength) {
        double lengthSq = self.lengthSqr();
        return Mth.equal(lengthSq, 0.0) ? Vec3.ZERO : self.scale(newLength / sqrt(lengthSq));
    }

    public static boolean isNormalized(Vec3 self) {
        return isNormalized(self, 1e-4);
    }

    public static boolean isNormalized(Vec3 self, double tolerance) {
        return abs(self.lengthSqr() - 1.0) < tolerance;
    }

    public static Vec3 normalizeIfNeeded(Vec3 self) {
        return normalizeIfNeeded(self, 1e-4);
    }

    public static Vec3 normalizeIfNeeded(Vec3 self, double tolerance) {
        return isNormalized(self, tolerance) ? self : self.normalize();
    }

    public static boolean equals(Vec3 self, Vec3 other, double tolerance) {
        return abs(self.x - other.x) < tolerance
                && abs(self.y - other.y) < tolerance
                && abs(self.z - other.z) < tolerance;
    }

    public static boolean isLikelyZero(Vec3 self) {
        return Mth.equal(self.lengthSqr(), 0.0);
    }

    /**
     * @see Vec3#rotation()
     */
    public static float yaw(Vec3 self) {
        return (float) atan2(-self.x, self.z) * Mth.RAD_TO_DEG;
    }

    public static Vec3 copy(Vec3 self) {
        return copy(self, self.x, self.y, self.z);
    }

    public static Vec3 copy(Vec3 self, double x) {
        return copy(self, x, self.y, self.z);
    }

    public static Vec3 copy(Vec3 self, double x, double y) {
        return copy(self, x, y, self.z);
    }

    public static Vec3 copy(Vec3 self, double x, double y, double z) {
        return new Vec3(x, y, z);
    }

    public static Vec3 toVec3d(Vector3fc self) {
        return new Vec3(self.x(), self.y(), self.z());
    }

    public static Vector3f set(Vector3f self, Vec3 vec3d) {
        return self.set(vec3d.x, vec3d.y, vec3d.z);
    }

    public static Vector3f add(Vector3f self, Vec3 vec3d) {
        return self.add((float) vec3d.x, (float) vec3d.y, (float) vec3d.z);
    }

    public static Vector3f sub(Vector3f self, Vec3 vec3d) {
        return self.sub((float) vec3d.x, (float) vec3d.y, (float) vec3d.z);
    }

    /** LB: {@code Vec3.multiply(factorX: Float, factorY: Float, factorZ: Float)}（inline，无 JVM 重载） */
    public static Vec3 multiply(Vec3 self, float factorX, float factorY, float factorZ) {
        return self.multiply(factorX, factorY, factorZ);
    }

    /** LB: {@code Vec3.multiply(factorX: Double, factorY: Double, factorZ: Double)}（inline，无 JVM 重载） */
    public static Vec3 multiply(Vec3 self, double factorX, double factorY, double factorZ) {
        return self.multiply(factorX, factorY, factorZ);
    }

    public static double horizontalDistanceTo(Vec3 self, Vec3i other) {
        return horizontalDistanceTo(self, (double) other.getX(), (double) other.getZ());
    }

    public static double horizontalDistanceTo(Vec3 self, Vec3 other) {
        return horizontalDistanceTo(self, other.x, other.z);
    }

    public static double horizontalDistanceTo(Vec3 self, double x, double z) {
        return sqrt(horizontalDistanceToSqr(self, x, z));
    }

    public static double horizontalDistanceToSqr(Vec3 self, Vec3i other) {
        return horizontalDistanceToSqr(self, (double) other.getX(), (double) other.getZ());
    }

    public static double horizontalDistanceToSqr(Vec3 self, Vec3 other) {
        return horizontalDistanceToSqr(self, other.x, other.z);
    }

    public static double horizontalDistanceToSqr(Vec3 self, double x, double z) {
        return Mth.lengthSquared(self.x - x, self.z - z);
    }

    public static double distanceToCenterSqr(Position self, long blockPos) {
        double dx = self.x() - BlockPos.getX(blockPos);
        double dy = self.y() - BlockPos.getY(blockPos);
        double dz = self.z() - BlockPos.getZ(blockPos);
        return Mth.lengthSquared(dx, dy, dz);
    }

    public static Vec3 average(Iterable<Vec3> self) {
        double x = 0.0;
        double y = 0.0;
        double z = 0.0;
        int i = 0;
        for (Vec3 vec : self) {
            x += vec.x;
            y += vec.y;
            z += vec.z;
            i++;
        }
        return new Vec3(x / i, y / i, z / i);
    }

    public static AABB expandToCube(Vec3 self, double halfExtents) {
        return new AABB(
                self.x - halfExtents, self.y - halfExtents, self.z - halfExtents,
                self.x + halfExtents, self.y + halfExtents, self.z + halfExtents
        );
    }

    public static BlockPos toBlockPos(Vec3 self) {
        return toBlockPos(self, 0.0, 0.0, 0.0);
    }

    public static BlockPos toBlockPos(Vec3 self, double xOffset) {
        return toBlockPos(self, xOffset, 0.0, 0.0);
    }

    public static BlockPos toBlockPos(Vec3 self, double xOffset, double yOffset) {
        return toBlockPos(self, xOffset, yOffset, 0.0);
    }

    public static BlockPos toBlockPos(Vec3 self, double xOffset, double yOffset, double zOffset) {
        return BlockPos.containing(self.x + xOffset, self.y + yOffset, self.z + zOffset);
    }

    public static Vec3 preferOver(Vec3 self, Vec3 other) {
        double x = self.x == 0.0 ? other.x : self.x;
        double y = self.y == 0.0 ? other.y : self.y;
        double z = self.z == 0.0 ? other.z : self.z;
        return new Vec3(x, y, z);
    }

    // Mutable Vec3d
    // （Epsilon 的 epsilon.accesswidener 已声明 `mutable field net/minecraft/world/phys/Vec3 x/y/z`，
    //   故与 LB 一样支持原地写；MixinMinecraft 也在原地写 cameraEntity.position().x 等字段。）

    public static Vec3 set(Vec3 self) {
        return set(self, self.x, self.y, self.z);
    }

    public static Vec3 set(Vec3 self, double x) {
        return set(self, x, self.y, self.z);
    }

    public static Vec3 set(Vec3 self, double x, double y) {
        return set(self, x, y, self.z);
    }

    public static Vec3 set(Vec3 self, double x, double y, double z) {
        self.x = x;
        self.y = y;
        self.z = z;
        return self;
    }

    public static Vec3 set(Vec3 self, Vec3 other) {
        return set(self, other.x, other.y, other.z);
    }

    public static Vec3 move(Vec3 self) {
        return move(self, 0.0, 0.0, 0.0);
    }

    public static Vec3 move(Vec3 self, double x) {
        return move(self, x, 0.0, 0.0);
    }

    public static Vec3 move(Vec3 self, double x, double y) {
        return move(self, x, y, 0.0);
    }

    public static Vec3 move(Vec3 self, double x, double y, double z) {
        self.x += x;
        self.y += y;
        self.z += z;
        return self;
    }

    public static Vec3 move(Vec3 self, Vec3 other) {
        return move(self, other.x, other.y, other.z);
    }

    /**
     * [适配] LB 同时定义 {@code scaleMut(x = 0.0, y = 0.0, z = 0.0)} 与 {@code scaleMut(scale = 1.0)}，
     * Java 无法用同一签名表达单 Double 的两种默认语义；此处单 Double 形式对应 LB 的 {@code scale} 重载。
     */
    public static Vec3 scaleMut(Vec3 self, double scale) {
        return scaleMut(self, scale, scale, scale);
    }

    public static Vec3 scaleMut(Vec3 self, double x, double y, double z) {
        self.x *= x;
        self.y *= y;
        self.z *= z;
        return self;
    }

    // ---------------------------------------------------------------- AABB

    public static Vec3[] vertices(AABB self) {
        return new Vec3[]{
                new Vec3(self.minX, self.minY, self.minZ),
                new Vec3(self.minX, self.minY, self.maxZ),
                new Vec3(self.minX, self.maxY, self.minZ),
                new Vec3(self.minX, self.maxY, self.maxZ),
                new Vec3(self.maxX, self.minY, self.minZ),
                new Vec3(self.maxX, self.minY, self.maxZ),
                new Vec3(self.maxX, self.maxY, self.minZ),
                new Vec3(self.maxX, self.maxY, self.maxZ),
        };
    }

    public static AABB plus(AABB self, Position offset) {
        return self.move(offset.x(), offset.y(), offset.z());
    }

    public static AABB minus(AABB self, Position offset) {
        return self.move(-offset.x(), -offset.y(), -offset.z());
    }

    public static AABB plus(AABB self, Vec3i offset) {
        return self.move((double) offset.getX(), (double) offset.getY(), (double) offset.getZ());
    }

    public static AABB minus(AABB self, Vec3i offset) {
        return self.move((double) -offset.getX(), (double) -offset.getY(), (double) -offset.getZ());
    }

    public record WorldLocalBox(Vec3 origin, AABB localBox) {
    }

    public static WorldLocalBox worldToLocal(AABB self) {
        Vec3 origin = self.getMinPosition();
        return new WorldLocalBox(origin, minus(self, origin));
    }

    public static Iterable<BlockPos> iterateBlockPos(AABB self) {
        return iterateBlockPos(self, Mth.floor(self.minY), Mth.ceil(self.maxY));
    }

    public static Iterable<BlockPos> iterateBlockPos(AABB self, int minYInclusive, int maxYInclusive) {
        return BlockPos.betweenClosed(
                Mth.floor(self.minX),
                minYInclusive,
                Mth.floor(self.minZ),
                Mth.ceil(self.maxX),
                maxYInclusive,
                Mth.ceil(self.maxZ)
        );
    }

    public static Iterable<BlockPos> iterateBottomLayerBlockPos(AABB self) {
        return iterateBlockPos(self, Mth.floor(self.minY), Mth.ceil(self.minY));
    }

    public static Vec3 centerOnSide(AABB self, Direction side) {
        double cx = self.minX + self.getXsize() * 0.5;
        double cy = self.minY + self.getYsize() * 0.5;
        double cz = self.minZ + self.getZsize() * 0.5;

        return pointOnSide(self, cx, cy, cz, side);
    }

    public static double getCoordinate(AABB self, Direction direction) {
        return direction.getAxisDirection() == Direction.AxisDirection.POSITIVE
                ? self.max(direction.getAxis())
                : self.min(direction.getAxis());
    }

    /** Ray–AABB 首个命中点（进入或离开）。 */
    public static Vec3 firstHit(AABB self, Vec3 from, Vec3 to) {
        return self.contains(from) ? self.clip(to, from).orElse(null) : self.clip(from, to).orElse(null);
    }

    /**
     * 取盒子上距离 from 最近的点。用于计算与目标的距离。
     */
    public static Vec3 getNearestPoint(AABB self, Position from) {
        return new Vec3(
                Mth.clamp(from.x(), self.minX, self.maxX),
                Mth.clamp(from.y(), self.minY, self.maxY),
                Mth.clamp(from.z(), self.minZ, self.maxZ)
        );
    }

    /**
     * 本盒到某点的平方距离，不分配临时 Vec3。
     *
     * @see AABB#distanceToSqr(Vec3)
     */
    public static double distanceToSqr(AABB self, double x, double y, double z) {
        double dx = Math.max(Math.max(self.minX - x, x - self.maxX), 0.0);
        double dy = Math.max(Math.max(self.minY - y, y - self.maxY), 0.0);
        double dz = Math.max(Math.max(self.minZ - z, z - self.maxZ), 0.0);
        return Mth.lengthSquared(dx, dy, dz);
    }

    /**
     * LB {@code ShapeExtensions.kt}: {@code VoxelShape.distanceToSqr(position: Vec3)}
     */
    public static double distanceToSqr(VoxelShape self, Vec3 position) {
        Vec3 closest = self.closestPointTo(position).orElse(null);
        return closest != null ? closest.distanceToSqr(position) : Double.POSITIVE_INFINITY;
    }

    public static Vec3 getNearestPointOnSide(AABB self, Vec3 from, Direction side) {
        Vec3 nearest = getNearestPoint(self, from);
        return pointOnSide(self, nearest.x, nearest.y, nearest.z, side);
    }

    public static Vec3 samplePointOnSide(AABB self, Direction side, double a, double b) {
        Vec3 spot = switch (side) {
            case DOWN -> new Vec3(a, 0.0, b);
            case UP -> new Vec3(a, 1.0, b);
            case NORTH -> new Vec3(a, b, 0.0);
            case SOUTH -> new Vec3(a, b, 1.0);
            case WEST -> new Vec3(0.0, a, b);
            case EAST -> new Vec3(1.0, a, b);
        };

        return pointAtProportion(self, spot.x, spot.y, spot.z);
    }

    public static Vec3 pointAtProportion(AABB self, double p) {
        return pointAtProportion(self, p, p, p);
    }

    public static Vec3 pointAtProportion(AABB self, double pX, double pY, double pZ) {
        return new Vec3(
                Math.fma(self.getXsize(), pX, self.minX),
                Math.fma(self.getYsize(), pY, self.minY),
                Math.fma(self.getZsize(), pZ, self.minZ)
        );
    }

    private static Vec3 pointOnSide(AABB self, double x, double y, double z, Direction side) {
        return switch (side) {
            case DOWN -> new Vec3(x, self.minY, z);
            case UP -> new Vec3(x, self.maxY, z);
            case NORTH -> new Vec3(x, y, self.minZ);
            case SOUTH -> new Vec3(x, y, self.maxZ);
            case WEST -> new Vec3(self.minX, y, z);
            case EAST -> new Vec3(self.maxX, y, z);
        };
    }

    /**
     * 从盒子外的 eyes 看，哪些面可见。
     *
     * @return 大小在 [0..3]，0 表示在盒子内部
     */
    public static List<Direction> visibleSidesTo(AABB self, Vec3 eyes) {
        List<Direction> faces = new ArrayList<>(3);

        if (eyes.x < self.minX) {
            faces.add(Direction.WEST);
        } else if (eyes.x > self.maxX) {
            faces.add(Direction.EAST);
        }

        if (eyes.y < self.minY) {
            faces.add(Direction.DOWN);
        } else if (eyes.y > self.maxY) {
            faces.add(Direction.UP);
        }

        if (eyes.z < self.minZ) {
            faces.add(Direction.NORTH);
        } else if (eyes.z > self.maxZ) {
            faces.add(Direction.SOUTH);
        }

        return faces;
    }

    public static boolean isSideVisible(AABB self, Direction direction, Vec3 eyes) {
        return switch (direction) {
            case WEST -> eyes.x < self.minX;
            case EAST -> eyes.x > self.maxX;
            case DOWN -> eyes.y < self.minY;
            case UP -> eyes.y > self.maxY;
            case NORTH -> eyes.z < self.minZ;
            case SOUTH -> eyes.z > self.maxZ;
        };
    }

    // ---------------------------------------------------------- BoundingBox

    public static Iterable<BlockPos> iterate(BoundingBox self) {
        return BlockPos.betweenClosed(self.minX(), self.minY(), self.minZ(), self.maxX(), self.maxY(), self.maxZ());
    }

    private static int lengthX(BoundingBox self) {
        return self.maxX() - self.minX() + 1;
    }

    private static int lengthY(BoundingBox self) {
        return self.maxY() - self.minY() + 1;
    }

    private static int lengthZ(BoundingBox self) {
        return self.maxZ() - self.minZ() + 1;
    }

    private static double centerX(BoundingBox self) {
        return self.minX() + lengthX(self) * 0.5;
    }

    private static double centerY(BoundingBox self) {
        return self.minY() + lengthY(self) * 0.5;
    }

    private static double centerZ(BoundingBox self) {
        return self.minZ() + lengthZ(self) * 0.5;
    }

    public static int size(BoundingBox self) {
        return lengthX(self) * lengthY(self) * lengthZ(self);
    }

    public static BlockPos from(BoundingBox self) {
        return new BlockPos(self.minX(), self.minY(), self.minZ());
    }

    public static BlockPos to(BoundingBox self) {
        return new BlockPos(self.maxX(), self.maxY(), self.maxZ());
    }

    public static boolean contains(BoundingBox self, BoundingBox other) {
        return other.minX() >= self.minX()
                && other.maxX() <= self.maxX()
                && other.minY() >= self.minY()
                && other.maxY() <= self.maxY()
                && other.minZ() >= self.minZ()
                && other.maxZ() <= self.maxZ();
    }

    public static AABB boundingBox(BoundingBox self) {
        return new AABB(
                (double) self.minX(), (double) self.minY(), (double) self.minZ(),
                (double) self.maxX() + 1.0, (double) self.maxY() + 1.0, (double) self.maxZ() + 1.0
        );
    }

    public static AABB box(BoundingBox self) {
        return new AABB(
                0.0, 0.0, 0.0,
                (double) lengthX(self), (double) lengthY(self), (double) lengthZ(self)
        );
    }

    public static Vec3 centerOnSide(BoundingBox self, Direction side) {
        return switch (side) {
            case DOWN -> new Vec3(centerX(self), self.minY() - 0.5, centerZ(self));
            case UP -> new Vec3(centerX(self), self.maxY() + 0.5, centerZ(self));
            case EAST -> new Vec3(self.maxX() + 0.5, centerY(self), centerZ(self));
            case WEST -> new Vec3(self.minX() - 0.5, centerY(self), centerZ(self));
            case SOUTH -> new Vec3(centerX(self), centerY(self), self.maxZ() + 0.5);
            case NORTH -> new Vec3(centerX(self), centerY(self), self.minZ() - 0.5);
        };
    }

    public static BoundingBox copy(BoundingBox self) {
        return copy(self, self.minX(), self.minY(), self.minZ(), self.maxX(), self.maxY(), self.maxZ());
    }

    public static BoundingBox copy(BoundingBox self, int minX) {
        return copy(self, minX, self.minY(), self.minZ(), self.maxX(), self.maxY(), self.maxZ());
    }

    public static BoundingBox copy(BoundingBox self, int minX, int minY) {
        return copy(self, minX, minY, self.minZ(), self.maxX(), self.maxY(), self.maxZ());
    }

    public static BoundingBox copy(BoundingBox self, int minX, int minY, int minZ) {
        return copy(self, minX, minY, minZ, self.maxX(), self.maxY(), self.maxZ());
    }

    public static BoundingBox copy(BoundingBox self, int minX, int minY, int minZ, int maxX) {
        return copy(self, minX, minY, minZ, maxX, self.maxY(), self.maxZ());
    }

    public static BoundingBox copy(BoundingBox self, int minX, int minY, int minZ, int maxX, int maxY) {
        return copy(self, minX, minY, minZ, maxX, maxY, self.maxZ());
    }

    public static BoundingBox copy(BoundingBox self, int minX, int minY, int minZ, int maxX, int maxY, int maxZ) {
        return new BoundingBox(minX, minY, minZ, maxX, maxY, maxZ);
    }

    public static BoundingBox expandToBoundingBox(BlockPos self) {
        return expandToBoundingBox(self, 0, 0, 0);
    }

    public static BoundingBox expandToBoundingBox(BlockPos self, int offsetX) {
        return expandToBoundingBox(self, offsetX, 0, 0);
    }

    public static BoundingBox expandToBoundingBox(BlockPos self, int offsetX, int offsetY) {
        return expandToBoundingBox(self, offsetX, offsetY, 0);
    }

    public static BoundingBox expandToBoundingBox(BlockPos self, int offsetX, int offsetY, int offsetZ) {
        return new BoundingBox(
                self.getX() - offsetX, self.getY() - offsetY, self.getZ() - offsetZ,
                self.getX() + offsetX, self.getY() + offsetY, self.getZ() + offsetZ
        );
    }

    // ------------------------------------------------ 数值辅助（MathExtensions / NumberExtensions）

    public static int sq(int value) {
        return value * value;
    }

    public static float sq(float value) {
        return value * value;
    }

    public static double sq(double value) {
        return value * value;
    }

    public static float toRadians(float value) {
        return value * Mth.DEG_TO_RAD;
    }

    public static double toRadians(double value) {
        return value * Mth.DEG_TO_RAD;
    }

    public static float toDegrees(float value) {
        return value * Mth.RAD_TO_DEG;
    }

    public static double toDegrees(double value) {
        return value * Mth.RAD_TO_DEG;
    }

    public static int floorToInt(float value) {
        return Mth.floor(value);
    }

    public static int floorToInt(double value) {
        return Mth.floor(value);
    }

    public static int ceilToInt(float value) {
        return Mth.ceil(value);
    }

    public static int ceilToInt(double value) {
        return Mth.ceil(value);
    }

    public static float fastSin(float value) {
        return fastSin((double) value);
    }

    public static float fastSin(double value) {
        return Mth.sin(value);
    }

    public static float fastCos(float value) {
        return fastCos((double) value);
    }

    public static float fastCos(double value) {
        return Mth.cos(value);
    }

}
