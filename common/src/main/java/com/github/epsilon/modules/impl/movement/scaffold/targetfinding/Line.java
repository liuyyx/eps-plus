package com.github.epsilon.modules.impl.movement.scaffold.targetfinding;

import net.minecraft.core.Direction;
import net.minecraft.util.Mth;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.phys.shapes.VoxelShape;

import com.github.epsilon.modules.impl.movement.scaffold.util.VectorUtils;

import java.util.Objects;
import java.util.function.DoubleConsumer;

/**
 * 逐行照搬自 LiquidBounce {@code utils/math/geometry/Line.kt} +
 * {@code utils/math/geometry/LinearGeometry3.kt} 中 Line 用到的默认方法。
 *
 * <p>不搬（路径外，或依赖未搬的 Ray/LineSegment 类型）：pointAtOrNull / getNearestPointsTo /
 * intersects / firstIntersectionWith / boxIntersectionInterval / firstIntersectionParameter。
 */
public final class Line {

    private static final double GEOMETRY_PARAMETER_EPSILON = 1e-9;

    public final Vec3 position;
    public final Vec3 direction;

    public Line(Vec3 position, Vec3 direction) {
        requireValidDirection(direction);
        this.position = position;
        this.direction = direction;
    }

    public Vec3 anchor() {
        return position;
    }

    public static Line fromPoints(Vec3 begin, Vec3 end) {
        return new Line(begin, end.subtract(begin));
    }

    /**
     * Returns the point on the supporting line at {@code parameter}.
     * This method does not validate the parameter domain.
     * LB: {@code anchor.fma(parameter, direction)}
     */
    public Vec3 pointAt(double parameter) {
        return VectorUtils.fma(anchor(), parameter, direction);
    }

    /**
     * Returns the unconstrained projection parameter of {@code point} on the supporting line.
     */
    public double parameterFor(Vec3 point) {
        return parameterFor(point.x, point.y, point.z);
    }

    /**
     * Returns the unconstrained projection parameter of input position on the supporting line.
     */
    public double parameterFor(double x, double y, double z) {
        // LB: direction.dot(x - anchor.x, y - anchor.y, z - anchor.z) / direction.lengthSqr()
        double dx = x - anchor().x;
        double dy = y - anchor().y;
        double dz = z - anchor().z;
        return (direction.x * dx + direction.y * dy + direction.z * dz) / direction.lengthSqr();
    }

    /**
     * Returns the nearest point on this geometry to {@code point}.
     */
    public Vec3 getNearestPointTo(Vec3 point) {
        double parameter = parameterDomain().project(parameterFor(point));
        if (Double.isNaN(parameter)) {
            throw new IllegalStateException("Unable to project point " + point + " on geometry " + this);
        }
        return pointAt(parameter);
    }

    /**
     * Returns the squared distance from {@code point} to this geometry.
     */
    public double distanceToSqr(Vec3 point) {
        return getNearestPointTo(point).distanceToSqr(point);
    }

    /**
     * Returns the nearest point on this geometry to {@code shape}.
     * The shape is expected to already be expressed in the same coordinate system as this geometry.
     *
     * @return nearest point on this geometry together with squared distance, or {@code null} if shape is empty
     */
    public NearestPointResult getNearestPointTo(VoxelShape shape) {
        if (shape.isEmpty()) {
            return null;
        }

        NearestPointResult[] bestResult = {null};

        shape.forAllBoxes((minX, minY, minZ, maxX, maxY, maxZ) -> {
            AABB box = new AABB(minX, minY, minZ, maxX, maxY, maxZ);
            NearestPointResult result = getNearestPointTo(box);
            NearestPointResult currentBest = bestResult[0];

            if (currentBest == null ||
                    result.distanceSquared < currentBest.distanceSquared - GEOMETRY_PARAMETER_EPSILON) {
                bestResult[0] = result;
            }
        });

        return Objects.requireNonNull(bestResult[0], () -> "Unable to find nearest point on geometry " + this);
    }

    /**
     * Returns the nearest point on this geometry to {@code box}.
     */
    public NearestPointResult getNearestPointTo(AABB box) {
        Vec3 position = anchor();
        Vec3 directionVector = direction;
        double px = position.x;
        double py = position.y;
        double pz = position.z;

        double dx = directionVector.x;
        double dy = directionVector.y;
        double dz = directionVector.z;

        double[] breakpoints = new double[6];
        int breakpointSize = 0;

        if (!Mth.equal(dx, 0.0)) {
            breakpointSize = addFinite(breakpoints, breakpointSize, (box.minX - px) / dx);
            breakpointSize = addFinite(breakpoints, breakpointSize, (box.maxX - px) / dx);
        }
        if (!Mth.equal(dy, 0.0)) {
            breakpointSize = addFinite(breakpoints, breakpointSize, (box.minY - py) / dy);
            breakpointSize = addFinite(breakpoints, breakpointSize, (box.maxY - py) / dy);
        }
        if (!Mth.equal(dz, 0.0)) {
            breakpointSize = addFinite(breakpoints, breakpointSize, (box.minZ - pz) / dz);
            breakpointSize = addFinite(breakpoints, breakpointSize, (box.maxZ - pz) / dz);
        }

        breakpointSize = sortAndUnique(breakpoints, breakpointSize);

        ParameterDomain domain = parameterDomain();
        NearestPointEvaluator evaluate = new NearestPointEvaluator(box, domain, px, py, pz, dx, dy, dz);

        domain.forEachFiniteBoundary(evaluate::evaluate);
        for (int index = 0; index < breakpointSize; index++) {
            evaluate.evaluate(breakpoints[index]);
        }

        double[] markers = new double[8];
        int[] markerSize = {0};

        for (int index = 0; index < breakpointSize; index++) {
            addMarker(markers, markerSize, breakpoints[index], domain);
        }
        domain.forEachFiniteBoundary(parameter -> addMarker(markers, markerSize, parameter, domain));

        markerSize[0] = sortAndUnique(markers, markerSize[0]);

        double intervalStart = domain.lowerBound;
        for (int index = 0; index < markerSize[0]; index++) {
            double marker = markers[index];
            evaluateInterval(box, domain, intervalStart, marker, position, directionVector, evaluate::evaluate);
            intervalStart = marker;
        }
        evaluateInterval(box, domain, intervalStart, domain.upperBound, position, directionVector, evaluate::evaluate);

        if (Double.isNaN(evaluate.bestParameter)) {
            evaluate.evaluate(0.0);
        }

        if (Double.isNaN(evaluate.bestParameter)) {
            throw new IllegalStateException("Unable to find nearest point on geometry " + this);
        }

        return new NearestPointResult(pointAt(evaluate.bestParameter), evaluate.bestDistance);
    }

    private void evaluateInterval(
            AABB box,
            ParameterDomain domain,
            double start,
            double end,
            Vec3 position,
            Vec3 direction,
            DoubleConsumer evaluate
    ) {
        double intervalStart = Math.max(start, domain.lowerBound);
        double intervalEnd = Math.min(end, domain.upperBound);
        double sample = sampleOpenInterval(intervalStart, intervalEnd);
        if (Double.isNaN(sample)) return;

        Vec3 samplePoint = VectorUtils.fma(position, sample, direction);

        double quadraticA = 0.0;
        double quadraticB = 0.0;

        for (Direction.Axis axis : Direction.Axis.VALUES) {
            double sampleCoordinate = samplePoint.get(axis);
            double directionCoordinate = direction.get(axis);
            double positionCoordinate = position.get(axis);

            if (sampleCoordinate < box.min(axis)) {
                quadraticA += directionCoordinate * directionCoordinate;
                quadraticB += directionCoordinate * (positionCoordinate - box.min(axis));
            } else if (sampleCoordinate > box.max(axis)) {
                quadraticA += directionCoordinate * directionCoordinate;
                quadraticB += directionCoordinate * (positionCoordinate - box.max(axis));
            }
        }

        if (Math.abs(quadraticA) <= GEOMETRY_PARAMETER_EPSILON) {
            evaluate.accept(sample);
            return;
        }

        double root = -quadraticB / quadraticA;
        if (inOpenInterval(root, intervalStart, intervalEnd)) {
            evaluate.accept(root);
        }
    }

    private ParameterDomain parameterDomain() {
        return ParameterDomain.UNBOUNDED;
    }

    private static int addFinite(double[] values, int size, double k) {
        if (!Double.isFinite(k)) {
            return size;
        }
        values[size] = k;
        return size + 1;
    }

    private static void addMarker(double[] markers, int[] size, double parameter, ParameterDomain domain) {
        if (parameter < domain.lowerBound - GEOMETRY_PARAMETER_EPSILON ||
                parameter > domain.upperBound + GEOMETRY_PARAMETER_EPSILON) {
            return;
        }
        markers[size[0]] = parameter;
        size[0] = size[0] + 1;
    }

    private static int sortAndUnique(double[] values, int size) {
        if (size <= 1) {
            return size;
        }

        for (int index = 1; index < size; index++) {
            double value = values[index];
            int insertionIndex = index;

            while (insertionIndex > 0 && values[insertionIndex - 1] > value) {
                values[insertionIndex] = values[insertionIndex - 1];
                insertionIndex--;
            }

            values[insertionIndex] = value;
        }

        int uniqueCount = 1;

        for (int index = 1; index < size; index++) {
            double value = values[index];

            if (value == values[uniqueCount - 1]) {
                continue;
            }

            values[uniqueCount++] = value;
        }

        return uniqueCount;
    }

    private static double sampleOpenInterval(double start, double end) {
        if (Double.isFinite(start) && Double.isFinite(end)) {
            return start < end - GEOMETRY_PARAMETER_EPSILON ? (start + end) * 0.5 : Double.NaN;
        }

        if (Double.isFinite(start)) {
            return start + 1.0;
        }

        if (Double.isFinite(end)) {
            return end - 1.0;
        }

        return 0.0;
    }

    private static boolean inOpenInterval(double value, double start, double end) {
        if (!Double.isFinite(value)) {
            return false;
        }

        boolean aboveLower = !Double.isFinite(start) || value > start + GEOMETRY_PARAMETER_EPSILON;
        boolean belowUpper = !Double.isFinite(end) || value < end - GEOMETRY_PARAMETER_EPSILON;
        return aboveLower && belowUpper;
    }

    private static void requireValidDirection(Vec3 direction) {
        if (Mth.equal(direction.lengthSqr(), 0.0)) {
            throw new IllegalArgumentException("Direction should be not zero, actual: " + direction);
        }
    }

    /**
     * LB {@code utils/math/geometry/LinearGeometry3.kt:24-27} {@code @JvmRecord data class NearestPointResult}
     */
    public record NearestPointResult(Vec3 point, double distanceSquared) {
    }

    private static final class NearestPointEvaluator {
        private final AABB box;
        private final ParameterDomain domain;
        private final double px;
        private final double py;
        private final double pz;
        private final double dx;
        private final double dy;
        private final double dz;
        private double bestParameter = Double.NaN;
        private double bestDistance = Double.POSITIVE_INFINITY;

        NearestPointEvaluator(AABB box, ParameterDomain domain, double px, double py, double pz, double dx, double dy, double dz) {
            this.box = box;
            this.domain = domain;
            this.px = px;
            this.py = py;
            this.pz = pz;
            this.dx = dx;
            this.dy = dy;
            this.dz = dz;
        }

        void evaluate(double parameterCandidate) {
            double parameter = domain.normalize(parameterCandidate);
            if (Double.isNaN(parameter)) {
                return;
            }
            double x = px + dx * parameter;
            double y = py + dy * parameter;
            double z = pz + dz * parameter;
            double distance = VectorUtils.distanceToSqr(box, x, y, z);

            if (Double.isNaN(bestParameter) || distance < bestDistance - GEOMETRY_PARAMETER_EPSILON) {
                bestParameter = parameter;
                bestDistance = distance;
            }
        }
    }

    private enum ParameterDomain {
        UNBOUNDED(Double.NEGATIVE_INFINITY, Double.POSITIVE_INFINITY),
        FORWARD(0.0, Double.POSITIVE_INFINITY),
        SEGMENT_01(0.0, 1.0);

        final double lowerBound;
        final double upperBound;

        ParameterDomain(double lowerBound, double upperBound) {
            this.lowerBound = lowerBound;
            this.upperBound = upperBound;
        }

        /**
         * @return {@link Double#NaN} if parameter out of bounds
         */
        double normalize(double parameter) {
            if (!Double.isFinite(parameter)) {
                return Double.NaN;
            }

            if (parameter < lowerBound - GEOMETRY_PARAMETER_EPSILON
                    || parameter > upperBound + GEOMETRY_PARAMETER_EPSILON) {
                return Double.NaN;
            }

            return Math.min(Math.max(parameter, lowerBound), upperBound);
        }

        /**
         * @return {@link Double#NaN} if parameter out of bounds
         */
        double project(double parameter) {
            if (!Double.isFinite(parameter)) {
                return Double.NaN;
            }

            return Math.min(Math.max(parameter, lowerBound), upperBound);
        }

        void forEachFiniteBoundary(DoubleConsumer action) {
            switch (this) {
                case UNBOUNDED -> {
                }
                case FORWARD -> action.accept(0.0);
                case SEGMENT_01 -> {
                    action.accept(0.0);
                    action.accept(1.0);
                }
            }
        }
    }
}