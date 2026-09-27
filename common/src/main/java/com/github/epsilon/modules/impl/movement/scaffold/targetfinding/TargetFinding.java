package com.github.epsilon.modules.impl.movement.scaffold.targetfinding;

import com.github.epsilon.Constants;
import com.github.epsilon.modules.impl.movement.scaffold.rotation.PolarRotationManager;
import com.github.epsilon.modules.impl.movement.scaffold.rotation.Rotation;
import com.github.epsilon.modules.impl.movement.scaffold.util.BlockUtils;
import com.github.epsilon.modules.impl.movement.scaffold.util.VectorUtils;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.Vec3i;
import net.minecraft.world.entity.Pose;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.SupportType;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.phys.shapes.CollisionContext;
import net.minecraft.world.phys.shapes.VoxelShape;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * 逐行照搬自 LiquidBounce {@code utils/block/targetfinding/TargetFinding.kt}。
 *
 * <p>LB 把以下类型都声明在同一文件，故这里作为 {@link TargetFinding} 的静态嵌套类型：
 * AimMode / BlockPlacementTargetFindingOptions / BlockOffsetOptions / FaceHandlingOptions /
 * PlayerLocationOnPlacement / BlockTargetPlan / BlockTargetingMode / BlockPlacementTarget。
 *
 * <p>未搬：{@code PlacementPlan}（不在神桥路径上，仅 NoFall/Extinguish/LiquidPlacementHelper 使用，
 * 且依赖 LB 物品层 HotbarItemSlot）。
 */
public final class TargetFinding {

    private TargetFinding() {
    }

    public enum AimMode {
        CENTER("Center"),
        RANDOM("Random"),
        STABILIZED("Stabilized"),
        NEAREST_ROTATION("NearestRotation"),
        REVERSE_YAW("ReverseYaw"),
        DIAGONAL_YAW("DiagonalYaw"),
        ANGLE_YAW("AngleYaw"),
        EDGE_POINT("EdgePoint"),
        ;

        private final String tag;

        AimMode(String tag) {
            this.tag = tag;
        }

        public String getTag() {
            return tag;
        }
    }

    /**
     * Parameters used when generating a targeting plan for a block placement.
     */
    public record BlockPlacementTargetFindingOptions(
            BlockOffsetOptions offsetOptions,
            FaceHandlingOptions faceHandlingOptions,
            ItemStack stackToPlaceWith,
            PlayerLocationOnPlacement playerLocationOnPlacement
    ) {

        public static Comparator<BlockPos> leastBlockDistanceToLine(Line line) {
            return Comparator.comparingDouble((BlockPos blockPos) -> {
                VoxelShape shape = BlockUtils.outlineShape(blockPos).move(blockPos);
                if (shape.isEmpty()) {
                    return -line.distanceToSqr(VectorUtils.center(blockPos));
                } else {
                    Line.NearestPointResult nearest = line.getNearestPointTo(shape);
                    return -(nearest != null ? nearest.distanceSquared() : Double.POSITIVE_INFINITY);
                }
            });
        }

        public static Comparator<BlockPos> leastBlockDistanceToPos(Vec3 pos) {
            return Comparator.comparingDouble((BlockPos blockPos) -> {
                VoxelShape shape = BlockUtils.outlineShape(blockPos).move(blockPos);
                if (shape.isEmpty()) {
                    return -blockPos.distToCenterSqr(pos);
                } else {
                    return -VectorUtils.distanceToSqr(shape, pos);
                }
            });
        }
    }

    /**
     * Contains information about offsets (to the target pos) which should be investigated.
     *
     * @param offsetsToInvestigate the offsets (to the position) which the targeting algorithm will consider to place.
     *                             Prioritized with {@code priorityComparator}
     * @param priorityComparator   compares two offsets by their priority. An offset which ranks higher is prioritized.
     */
    public record BlockOffsetOptions(
            List<Vec3i> offsetsToInvestigate,
            Comparator<BlockPos> priorityComparator
    ) {

        public static final BlockOffsetOptions DEFAULT = new BlockOffsetOptions(
                BlockPosOffsets.NO_OFFSET.getOffsets(),
                Comparator.comparingDouble((BlockPos blockPos) -> {
                    Vec3 pos = Constants.mc.player.position();
                    VoxelShape shape = BlockUtils.outlineShape(blockPos).move(blockPos);
                    if (shape.isEmpty()) {
                        return -blockPos.distToCenterSqr(pos);
                    } else {
                        return -VectorUtils.distanceToSqr(shape, pos);
                    }
                })
        );
    }

    /**
     * Decides how scaffold processes the faces of the considered target blocks.
     *
     * @param facePositionFactory     given a face, it will yield a point on the face to target.
     * @param considerFacingAwayFaces decides whether scaffold will consider faces which point away from the
     *                                player camera as possible targets.
     */
    public record FaceHandlingOptions(
            FaceTargetPositionFactory facePositionFactory,
            boolean considerFacingAwayFaces
    ) {

        public FaceHandlingOptions(FaceTargetPositionFactory facePositionFactory) {
            this(facePositionFactory, false);
        }
    }

    /**
     * Contains information about where the player will be <em>on placement</em>.
     */
    public record PlayerLocationOnPlacement(Vec3 position, Pose pose) {

        public PlayerLocationOnPlacement() {
            this(Constants.mc.player.position(), Constants.mc.player.getPose());
        }

        public float eyeHeight() {
            return Constants.mc.player.getEyeHeight(pose);
        }

        public Vec3 eyePos() {
            return position.add(0.0, (double) eyeHeight(), 0.0);
        }
    }

    /**
     * A draft of a block placement.
     */
    public static final class BlockTargetPlan {

        private final BlockPos blockPosToInteractWith;
        private final Direction interactionDirection;

        /**
         * The center on the target block face.
         * <p>Note: no check for raycast!
         */
        private final Vec3 targetPositionOnBlock;

        public BlockTargetPlan(BlockPos blockPosToInteractWith, Direction interactionDirection) {
            this.blockPosToInteractWith = blockPosToInteractWith;
            this.interactionDirection = interactionDirection;
            this.targetPositionOnBlock = VectorUtils.centerOnSide(new AABB(blockPosToInteractWith), interactionDirection);
        }

        public BlockPos blockPosToInteractWith() {
            return blockPosToInteractWith;
        }

        public Direction interactionDirection() {
            return interactionDirection;
        }

        public Vec3 targetPositionOnBlock() {
            return targetPositionOnBlock;
        }

        /**
         * cosine of the angle between the expected player's eye position and the normal of the targeted face.
         */
        public double calculateAngleToPlayerEyeCosine(Vec3 eyePos) {
            Vec3 deltaToPlayerPos = eyePos.subtract(targetPositionOnBlock);

            return deltaToPlayerPos.dot(interactionDirection.getUnitVec3()) / deltaToPlayerPos.length();
        }
    }

    public enum BlockTargetingMode {
        PLACE_AT_NEIGHBOR,
        REPLACE_EXISTING_BLOCK
    }

    private static BlockTargetPlan findBestTargetPlanForTargetPosition(
            BlockPos posToInvestigate,
            BlockTargetingMode mode,
            BlockPlacementTargetFindingOptions targetFindingOptions
    ) {
        Direction[] directions = Direction.values();

        Vec3 playerEyePositionOnPlacement = targetFindingOptions.playerLocationOnPlacement().eyePos();

        List<BlockTargetPlan> options = new ArrayList<>();

        for (Direction direction : directions) {
            BlockTargetPlan targetPlan =
                    getTargetPlanForPositionAndDirection(posToInvestigate, direction, mode);

            if (targetPlan == null) {
                continue;
            }

            // Check if the target face is pointing away from the player
            if (!targetFindingOptions.faceHandlingOptions().considerFacingAwayFaces() &&
                    targetPlan.calculateAngleToPlayerEyeCosine(playerEyePositionOnPlacement) < 0) {
                continue;
            }

            options.add(targetPlan);
        }

        Rotation currentRotation = PolarRotationManager.INSTANCE.getActualServerRotation();

        BlockTargetPlan best = null;
        float bestValue = 0.0f;
        for (BlockTargetPlan plan : options) {
            Rotation targetRotation = Rotation.lookingAt(plan.targetPositionOnBlock(), playerEyePositionOnPlacement);

            float value = currentRotation.rotationDeltaLengthTo(targetRotation);

            if (best == null || value < bestValue) {
                best = plan;
                bestValue = value;
            }
        }
        return best;
    }

    /**
     * @return null if it is impossible to target the block with the given parameters
     */
    private static BlockTargetPlan getTargetPlanForPositionAndDirection(
            BlockPos pos,
            Direction direction,
            BlockTargetingMode mode
    ) {
        return switch (mode) {
            case PLACE_AT_NEIGHBOR -> {
                BlockPos currPos = pos.relative(direction.getOpposite());
                BlockState currState = BlockUtils.state(currPos);

                if (currState == null) {
                    yield null;
                }

                if (currState.canBeReplaced()) {
                    yield null;
                }

                yield new BlockTargetPlan(currPos, direction);
            }
            case REPLACE_EXISTING_BLOCK -> new BlockTargetPlan(pos, direction);
        };
    }

    private record PointOnFace(AlignedFace face, Direction side, Vec3 point) {
    }

    public static BlockPlacementTarget findBestBlockPlacementTarget(BlockPos pos, BlockPlacementTargetFindingOptions options) {
        BlockState state = BlockUtils.stateOrEmpty(pos);

        // We cannot place blocks when there is already a block at that position
        if (isBlockSolid(state, pos)) {
            return null;
        }

        List<Vec3i> offsetsToInvestigate = new ArrayList<>(options.offsetOptions().offsetsToInvestigate());
        offsetsToInvestigate.sort((a, b) ->
                // Sort DESCENDING!
                options.offsetOptions().priorityComparator().compare(pos.offset(b), pos.offset(a))
        );

        for (Vec3i offset : offsetsToInvestigate) {
            BlockPos posToInvestigate = pos.offset(offset);
            BlockState blockStateToInvestigate = BlockUtils.stateOrEmpty(posToInvestigate);

            // Already a block in that position?
            if (isBlockSolid(blockStateToInvestigate, posToInvestigate)) {
                continue;
            }

            // Do we want to replace a block or place a block at a neighbor? This makes a difference as we would need to
            // target the block in order to replace it. If there is no block at the target position yet, we need to target
            // a neighboring block
            BlockTargetingMode targetMode = (blockStateToInvestigate.isAir() || !blockStateToInvestigate.getFluidState().isEmpty())
                    ? BlockTargetingMode.PLACE_AT_NEIGHBOR
                    : BlockTargetingMode.REPLACE_EXISTING_BLOCK;

            // Check if we can actually replace the block?
            if (targetMode == BlockTargetingMode.REPLACE_EXISTING_BLOCK
                    && !BlockUtils.canBeReplacedWith(blockStateToInvestigate, posToInvestigate, options.stackToPlaceWith())
            ) {
                continue;
            }

            // Find the best plan to do the placement
            BlockTargetPlan targetPlan = findBestTargetPlanForTargetPosition(posToInvestigate, targetMode, options);
            if (targetPlan == null) {
                continue;
            }

            BlockPos currPos = targetPlan.blockPosToInteractWith();

            // We found the optimal block to place the block/face to place at. Now we need to find a point on the face.
            // to rotate to
            PointOnFace pointOnFace = findTargetPointOnFace(BlockUtils.stateOrEmpty(currPos), currPos, targetPlan, options);
            if (pointOnFace == null) {
                continue;
            }

            Vec3 interactionPoint = pointOnFace.point().add(currPos.getX(), currPos.getY(), currPos.getZ());
            Rotation rotation = Rotation.lookingAt(
                    interactionPoint,
                    options.playerLocationOnPlacement().eyePos()
            );

            return new BlockPlacementTarget(
                    currPos,
                    posToInvestigate,
                    pointOnFace.side(),
                    interactionPoint,
                    pointOnFace.face().from.y + currPos.getY(),
                    rotation
            );
        }

        return null;
    }

    private static final Comparator<PointOnFace> COMPARATOR_POINT_ON_FACE =
            Comparator.comparingDouble((PointOnFace it) ->
                            it.point().subtract(0.5, 0.5, 0.5)
                                    .multiply(it.side().getUnitVec3())
                                    .lengthSqr()
                    ).thenComparingDouble((PointOnFace it) -> it.point().y);

    private static PointOnFace findTargetPointOnFace(
            BlockState currState,
            BlockPos currPos,
            BlockTargetPlan targetPlan,
            BlockPlacementTargetFindingOptions options
    ) {
        List<AABB> shapeBBs = currState.getShape(Constants.mc.level, currPos, CollisionContext.of(Constants.mc.player)).toAabbs();

        PointOnFace best = null;

        for (AABB shapeBB : shapeBBs) {
            AlignedFace face = AlignedFace.getFace(shapeBB, targetPlan.interactionDirection());

            AlignedFace searchFace = face;

            // Try to aim at the upper portion of the block which makes it easier to switch from full blocks to half blocks
            if (searchFace.to.y >= 0.9) {
                AlignedFace truncated = searchFace.truncateY(0.6).requireNonEmpty();
                searchFace = truncated != null ? truncated : face;
            }

            Vec3 targetPos = options.faceHandlingOptions().facePositionFactory().producePositionOnFace(searchFace, currPos);
            if (targetPos == null) {
                continue;
            }

            PointOnFace candidate = new PointOnFace(
                    face,
                    targetPlan.interactionDirection(),
                    targetPos
            );

            if (best == null || COMPARATOR_POINT_ON_FACE.compare(candidate, best) > 0) {
                best = candidate;
            }
        }

        return best;
    }

    /**
     * BlockPos which is right-clicked, and the exact point on it selected by target finding.
     *
     * <p>访问器命名按契约 §4.2：LB 的 {@code direction} → {@link #interactionDirection()}，
     * LB 的 {@code interactionPoint} → {@link #point()}。
     */
    public static final class BlockPlacementTarget {

        /**
         * BlockPos which is right-clicked
         */
        private final BlockPos interactedBlockPos;
        /**
         * Block pos at which a new block is placed
         */
        private final BlockPos placedBlock;
        private final Direction direction;
        /**
         * Exact point on {@code interactedBlockPos} selected by target finding.
         */
        private final Vec3 interactionPoint;
        /**
         * Some blocks must be placed above a certain height of the block. For example stairs and slabs must be placed
         * at the upper half (=> minY = 0.5) in order to be placed correctly
         */
        private final double minPlacementY;
        private final Rotation rotation;

        public BlockPlacementTarget(
                BlockPos interactedBlockPos,
                BlockPos placedBlock,
                Direction direction,
                Vec3 interactionPoint,
                double minPlacementY,
                Rotation rotation
        ) {
            this.interactedBlockPos = interactedBlockPos;
            this.placedBlock = placedBlock;
            this.direction = direction;
            this.interactionPoint = interactionPoint;
            this.minPlacementY = minPlacementY;
            this.rotation = rotation;
        }

        public BlockPos interactedBlockPos() {
            return interactedBlockPos;
        }

        public BlockPos placedBlock() {
            return placedBlock;
        }

        public Direction interactionDirection() {
            return direction;
        }

        public Vec3 point() {
            return interactionPoint;
        }

        public double minPlacementY() {
            return minPlacementY;
        }

        public Rotation rotation() {
            return rotation;
        }

        public BlockHitResult blockHitResult() {
            return new BlockHitResult(
                    interactionPoint,
                    direction,
                    interactedBlockPos,
                    false
            );
        }

        public boolean doesCrosshairTargetMatchRequirements(BlockHitResult crosshairTarget) {
            if (crosshairTarget.getType() != HitResult.Type.BLOCK) {
                return false;
            }
            if (!crosshairTarget.getBlockPos().equals(this.interactedBlockPos)) {
                return false;
            }
            if (crosshairTarget.getDirection() != this.direction) {
                return false;
            }
            if (crosshairTarget.getLocation().y < this.minPlacementY) {
                return false;
            }
            return true;
        }
    }

    private static boolean isBlockSolid(BlockState state, BlockPos pos) {
        return state.isFaceSturdy(Constants.mc.level, pos, Direction.UP, SupportType.CENTER);
    }
}