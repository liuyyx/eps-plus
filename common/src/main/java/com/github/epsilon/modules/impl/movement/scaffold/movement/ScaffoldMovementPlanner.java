/*
 * This file is part of Epsilon.
 *
 * 逐行移植自 LiquidBounce (https://github.com/CCBlueX/LiquidBounce)
 * 源文件: src/main/kotlin/net/ccbluex/liquidbounce/features/module/modules/world/scaffold/ScaffoldMovementPlanner.kt
 * Copyright (c) 2015 - 2026 CCBlueX — GNU General Public License v3.0
 */
package com.github.epsilon.modules.impl.movement.scaffold.movement;

import com.github.epsilon.Constants;
import com.github.epsilon.modules.impl.movement.scaffold.simulation.DirectionalInput;
import com.github.epsilon.modules.impl.movement.scaffold.targetfinding.Line;
import com.github.epsilon.modules.impl.movement.scaffold.util.BlockUtils;
import com.github.epsilon.modules.impl.movement.scaffold.util.MovementUtils;
import com.github.epsilon.modules.impl.movement.scaffold.util.VectorUtils;
import it.unimi.dsi.fastutil.objects.Object2ObjectArrayMap;
import net.minecraft.core.BlockPos;
import net.minecraft.util.Mth;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.phys.shapes.VoxelShape;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Objects;

public final class ScaffoldMovementPlanner {

    private static final int MAX_LAST_PLACE_BLOCKS = 4;
    private static final float DIRECTION_HYSTERESIS_DEGREES = 30.0f;
    private static final double SUPPORT_SURFACE_EPSILON = 1.0E-3;
    private static final double SUPPORT_OVERLAP_HYSTERESIS = 0.02;

    // [适配] LB 用 kotlin.collections.ArrayDeque（支持 lastOrNull()/下标访问）；Java 侧用 ArrayList（同样的顺序/索引语义）
    private static final List<BlockPos> lastPlacedBlocks = new ArrayList<>(MAX_LAST_PLACE_BLOCKS);
    private static BlockPos lastPosition = null;
    private static SupportReference lastSupportReference = null;
    private static float lastDirectionAngle = Float.NaN;

    private ScaffoldMovementPlanner() {
    }

    /**
     * LB 原文: {@code data class SupportReference(val blockPos: BlockPos, val offsetX: Double, val offsetZ: Double)}
     */
    public static final class SupportReference {

        public final BlockPos blockPos;
        public final double offsetX;
        public final double offsetZ;

        public SupportReference(BlockPos blockPos, double offsetX, double offsetZ) {
            this.blockPos = blockPos;
            this.offsetX = offsetX;
            this.offsetZ = offsetZ;
        }

        @Override
        public boolean equals(Object o) {
            if (this == o) {
                return true;
            }
            if (!(o instanceof SupportReference other)) {
                return false;
            }
            return Double.compare(this.offsetX, other.offsetX) == 0
                    && Double.compare(this.offsetZ, other.offsetZ) == 0
                    && Objects.equals(this.blockPos, other.blockPos);
        }

        @Override
        public int hashCode() {
            return Objects.hash(this.blockPos, this.offsetX, this.offsetZ);
        }

        @Override
        public String toString() {
            return "SupportReference(blockPos=" + this.blockPos + ", offsetX=" + this.offsetX + ", offsetZ=" + this.offsetZ + ")";
        }

    }

    private static final class SupportCandidate implements Comparable<SupportCandidate> {

        public final BlockPos blockPos;
        public final double overlapArea;
        public final double surfaceDelta;
        public final double horizontalDistanceToPlayerSqr;

        private SupportCandidate(BlockPos blockPos, double overlapArea, double surfaceDelta, double horizontalDistanceToPlayerSqr) {
            this.blockPos = blockPos;
            this.overlapArea = overlapArea;
            this.surfaceDelta = surfaceDelta;
            this.horizontalDistanceToPlayerSqr = horizontalDistanceToPlayerSqr;
        }

        @Override
        public int compareTo(SupportCandidate other) {
            if (this.surfaceDelta + SUPPORT_SURFACE_EPSILON < other.surfaceDelta) {
                return -1;
            } else if (other.surfaceDelta + SUPPORT_SURFACE_EPSILON < this.surfaceDelta) {
                return 1;
            } else if (this.overlapArea > other.overlapArea + SUPPORT_OVERLAP_HYSTERESIS) {
                return -1;
            } else if (this.overlapArea + SUPPORT_OVERLAP_HYSTERESIS < other.overlapArea) {
                return 1;
            } else if (this.horizontalDistanceToPlayerSqr < other.horizontalDistanceToPlayerSqr) {
                return -1;
            } else if (this.horizontalDistanceToPlayerSqr > other.horizontalDistanceToPlayerSqr) {
                return 1;
            } else {
                return 0;
            }
        }

    }

    /**
     * When using scaffold the player wants to follow the line and the scaffold should support them in doing so.
     * This function estimates the line the player is trying to move on while preserving the player's offset on the
     * current support block until placed block history can provide a stable line.
     */
    public static Line getOptimalMovementLine(DirectionalInput directionalInput) {
        Vec3 direction = chooseDirection(MovementUtils.getMovementDirectionOfInput(Constants.mc.player, directionalInput));

        // Keep the current in-block offset so starting away from the block center does not snap the line sideways.
        SupportReference supportReference = findSupportReferenceUnderPlayer();
        if (supportReference == null) {
            return null;
        }
        lastSupportReference = supportReference;

        Line lastBlocksLine = fitLinesThroughLastPlacedBlocks();

        // If the recent placements match the current movement direction, follow them. Otherwise use the current
        // support block as a fresh anchor because the user probably started a new direction.
        Vec3 lineAnchor;
        if (lastBlocksLine != null && !divergesTooMuchFromDirection(lastBlocksLine, direction)) {
            lineAnchor = lastBlocksLine.getNearestPointTo(Constants.mc.player.position());
        } else {
            lineAnchor = new Vec3(
                    supportReference.blockPos.getX() + 0.5 + supportReference.offsetX,
                    Constants.mc.player.position().y,
                    supportReference.blockPos.getZ() + 0.5 + supportReference.offsetZ
            );
        }

        // We try to make the player run on this line.
        // LB 原文: Line(lineAnchor.copy(y = player.position().y), direction)
        Line optimalLine = new Line(
                VectorUtils.copy(lineAnchor, lineAnchor.x, Constants.mc.player.position().y, lineAnchor.z),
                direction
        );

        // LB: ModuleScaffold.debugGeometry("optimalLine") { ... } → 不搬（调试渲染，路径外）

        return optimalLine;
    }

    private static boolean divergesTooMuchFromDirection(Line lastBlocksLine, Vec3 direction) {
        return lastBlocksLine.direction.dot(direction) < 0.5; // cos(60deg)
    }

    /**
     * Tries to fit a line that goes through the last placed blocks. Currently only considers the last two.
     */
    private static Line fitLinesThroughLastPlacedBlocks() {
        // Take the last 2 blocks placed
        if (lastPlacedBlocks.size() < 2) {
            return null;
        }
        BlockPos last = lastPlacedBlocks.get(lastPlacedBlocks.size() - 1);
        BlockPos secondToLast = lastPlacedBlocks.get(lastPlacedBlocks.size() - 2);

        // LB: ModuleDebug 调试绘制（debugLastPlacedBlocks）→ 不搬（调试渲染，路径外）

        Vec3 secondToLastCenter = Vec3.atBottomCenterOf(secondToLast);
        Vec3 lastCenter = Vec3.atBottomCenterOf(last);
        Vec3 avgPos = secondToLastCenter.add(lastCenter).scale(0.5);
        Vec3 dir = lastCenter.subtract(secondToLastCenter).normalize();

        // Calculate the average direction of the last placed blocks
        return new Line(avgPos, dir);
    }

    private static final double[] offsetsToTry = {0.301, 0.0, -0.301};

    /**
     * Find the support block reference the player is currently standing on.
     * It samples nearby blocks below the player, ranks them by support surface height, hitbox overlap area, and
     * distance to the player, then keeps a stable previous choice when it is still close enough.
     */
    private static SupportReference findSupportReferenceUnderPlayer() {
        Map<BlockPos, SupportCandidate> candidates = collectSupportCandidates();
        if (candidates.isEmpty()) {
            lastSupportReference = null;
            lastPosition = null;
            return null;
        }

        // LB: candidates.values.minOrNull()
        SupportCandidate bestCandidate = minOrNull(candidates.values());
        if (bestCandidate == null) {
            return null;
        }
        SupportCandidate chosenCandidate = chooseStableSupportCandidate(candidates, bestCandidate);

        lastPosition = chosenCandidate.blockPos;

        return new SupportReference(
                chosenCandidate.blockPos,
                Constants.mc.player.position().x - (chosenCandidate.blockPos.getX() + 0.5),
                Constants.mc.player.position().z - (chosenCandidate.blockPos.getZ() + 0.5)
        );
    }

    // [适配] kotlin.collections.minOrNull()：空集合返回 null，否则按 compareTo 取最小（并列时保留先遇到者）
    private static SupportCandidate minOrNull(Collection<SupportCandidate> candidates) {
        SupportCandidate min = null;
        for (SupportCandidate candidate : candidates) {
            if (min == null || candidate.compareTo(min) < 0) {
                min = candidate;
            }
        }
        return min;
    }

    private static Map<BlockPos, SupportCandidate> collectSupportCandidates() {
        // [offsetsToTry] makes the result map has up to 4 entries
        Map<BlockPos, SupportCandidate> candidates = new Object2ObjectArrayMap<>();

        for (double xOffset : offsetsToTry) {
            for (double zOffset : offsetsToTry) {
                BlockPos blockPos = VectorUtils.toBlockPos(Constants.mc.player.position(), xOffset, -1.0, zOffset);

                if (candidates.containsKey(blockPos)) continue;

                // LB: blockPos.state?.getCollisionShape(world, blockPos) ?: continue
                BlockState state = BlockUtils.state(blockPos);
                if (state == null) continue;

                VoxelShape collisionShape = state.getCollisionShape(Constants.mc.level, blockPos);

                if (!collisionShape.isEmpty()) {
                    candidates.put(blockPos, createSupportCandidate(blockPos));
                }
            }
        }

        return candidates;
    }

    private static SupportCandidate chooseStableSupportCandidate(
            Map<BlockPos, SupportCandidate> candidates,
            SupportCandidate bestCandidate
    ) {
        BlockPos lastPlacedBlock = lastOrNull();
        SupportCandidate preferredLastPlaced = lastPlacedBlock == null ? null : candidates.get(lastPlacedBlock);
        SupportCandidate preferredLastPosition = lastPosition == null ? null : candidates.get(lastPosition);

        if (preferredLastPlaced != null && isStableComparedTo(preferredLastPlaced, bestCandidate)) {
            return preferredLastPlaced;
        }
        if (preferredLastPosition != null && isStableComparedTo(preferredLastPosition, bestCandidate)) {
            return preferredLastPosition;
        }
        return bestCandidate;
    }

    private static boolean isStableComparedTo(SupportCandidate candidate, SupportCandidate bestCandidate) {
        if (candidate.surfaceDelta > bestCandidate.surfaceDelta + SUPPORT_SURFACE_EPSILON) {
            return false;
        }

        if (candidate.overlapArea + SUPPORT_OVERLAP_HYSTERESIS < bestCandidate.overlapArea) {
            return false;
        }

        return true;
    }

    private static SupportCandidate createSupportCandidate(BlockPos blockPos) {
        AABB playerBoundingBox = Constants.mc.player.getBoundingBox();

        // LB: blockPos.state?.getCollisionShape(world, blockPos)
        BlockState state = BlockUtils.state(blockPos);
        VoxelShape collisionShape = state == null ? null : state.getCollisionShape(Constants.mc.level, blockPos);

        double bestSurfaceDelta = Double.POSITIVE_INFINITY;
        double overlapAreaOnBestSurface = 0.0;

        if (collisionShape != null) {
            // [适配] Kotlin lambda 可捕获并改写外部 var；Java lambda 不可 → 用 2 元素数组承载：
            //        [0] = bestSurfaceDelta, [1] = overlapAreaOnBestSurface（写入位置与 LB 一致）
            double[] bestSurface = {bestSurfaceDelta, overlapAreaOnBestSurface};

            collisionShape.forAllBoxes((minX, minY, minZ, maxX, maxY, maxZ) -> {
                double boxMinX = blockPos.getX() + minX;
                double boxMaxX = blockPos.getX() + maxX;
                double boxMaxY = blockPos.getY() + maxY;
                double boxMinZ = blockPos.getZ() + minZ;
                double boxMaxZ = blockPos.getZ() + maxZ;

                double overlapX = Math.min(playerBoundingBox.maxX, boxMaxX) - Math.max(playerBoundingBox.minX, boxMinX);
                double overlapZ = Math.min(playerBoundingBox.maxZ, boxMaxZ) - Math.max(playerBoundingBox.minZ, boxMinZ);

                if (overlapX <= 0.0 || overlapZ <= 0.0) {
                    return;
                }

                double surfaceDelta = Math.abs(playerBoundingBox.minY - boxMaxY);
                double overlapArea = overlapX * overlapZ;

                if (surfaceDelta + SUPPORT_SURFACE_EPSILON < bestSurface[0]) {
                    bestSurface[0] = surfaceDelta;
                    bestSurface[1] = overlapArea;
                } else if (Math.abs(surfaceDelta - bestSurface[0]) <= SUPPORT_SURFACE_EPSILON) {
                    bestSurface[1] += overlapArea;
                }
            });

            bestSurfaceDelta = bestSurface[0];
            overlapAreaOnBestSurface = bestSurface[1];
        }

        return new SupportCandidate(
                blockPos,
                overlapAreaOnBestSurface,
                bestSurfaceDelta,
                VectorUtils.horizontalDistanceToSqr(
                        Constants.mc.player.position(),
                        blockPos.getX() + 0.5,
                        blockPos.getZ() + 0.5
                )
        );
    }

    /**
     * The player can move in a lot of directions. But there are only 8 directions which make sense for scaffold to
     * follow (NORTH, NORTH_EAST, EAST, etc.). This function chooses such a direction based on the current angle.
     * i.e. if we were looking like 30° to the right, we would choose the direction NORTH_EAST (1.0, 0.0, 1.0).
     * And scaffold would move diagonally to the right.
     * The last selected direction is kept while the input angle remains close enough, which avoids oscillation near
     * 8-way direction boundaries.
     *
     * @return normalized direction vector without y value
     */
    private static Vec3 chooseDirection(float currentAngle) {
        if (!Float.isNaN(lastDirectionAngle)
                && Mth.degreesDifferenceAbs(currentAngle, lastDirectionAngle) <= DIRECTION_HYSTERESIS_DEGREES
        ) {
            return Vec3.directionFromRotation(0.0f, lastDirectionAngle);
        }

        // Transform the angle ([-180; 180]) to [0; 8]
        float currentDirection = currentAngle / 180.0f * 4 + 4;

        // Round the angle to the nearest integer, which represents the direction.
        float newDirectionNumber = (float) Math.round(currentDirection);
        // Do this transformation backwards, and we have an angle that follows one of the 8 directions.
        float newDirectionAngle = Mth.wrapDegrees((newDirectionNumber - 4) / 4.0f * 180.0f);
        lastDirectionAngle = newDirectionAngle;

        return Vec3.directionFromRotation(0.0f, newDirectionAngle);
    }

    /**
     * Remembers the last placed blocks and removes old ones.
     */
    public static void trackPlacedBlock(BlockPos target) {
        if (target.equals(lastOrNull())) return;

        while (lastPlacedBlocks.size() >= MAX_LAST_PLACE_BLOCKS) {
            lastPlacedBlocks.remove(0);
        }

        lastPlacedBlocks.add(target);
    }

    public static void reset() {
        lastPosition = null;
        lastSupportReference = null;
        lastDirectionAngle = Float.NaN;
        lastPlacedBlocks.clear();
    }

    public static SupportReference getCurrentSupportReference() {
        return lastSupportReference;
    }

    private static BlockPos lastOrNull() {
        return lastPlacedBlocks.isEmpty() ? null : lastPlacedBlocks.get(lastPlacedBlocks.size() - 1);
    }

}
