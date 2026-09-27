/*
 * This file is part of Epsilon.
 *
 * 逐行照搬自 LiquidBounce (https://github.com/CCBlueX/LiquidBounce) 的
 * utils/block/BlockExtensions.kt、utils/block/DirectionExtensions.kt，
 * 以及 utils/math/ShapeExtensions.kt 中神桥路径依赖的 VoxelShape 辅助。
 *
 * LiquidBounce 是自由软件：你可以依据自由软件基金会发布的 GNU 通用公共许可证
 * （许可证第 3 版或你选择的任何更高版本）条款重新分发和/或修改它。
 */
package com.github.epsilon.modules.impl.movement.scaffold.util;

import com.google.common.base.Predicates;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.Vec3i;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntitySelector;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.context.BlockPlaceContext;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.SupportType;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.levelgen.structure.BoundingBox;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.phys.shapes.Shapes;
import net.minecraft.world.phys.shapes.VoxelShape;

import java.util.List;
import java.util.function.Predicate;

import static com.github.epsilon.Constants.mc;

/**
 * LB {@code utils/block/BlockExtensions.kt} + {@code utils/block/DirectionExtensions.kt} 的逐行移植
 * （只取神桥路径与通用方块查询相关的部分）。
 *
 * <p>不搬（映射表登记理由）：doPlacement/doBreak（用 Epsilon 的交互实现，且依赖 LB interaction/
 * gameRenderer/itemInHandRenderer）、isInteractable 系列（交互白名单，巨型 when）、
 * searchBlocksInCuboid/searchBlocksInRangeSorted/searchLayer/searchBedLayer/getSortedSphere
 * （方块搜索与床探测模块专用，且返回 Kotlin 懒序列）、BlockGetter.raycast（爆炸专用）、
 * isBreakable/isBlastResistant/fallDamageMultiplier/另一床方块方向（挖掘/爆炸/床模块）、
 * isBlockedByEntitiesReturnCrystal（末影水晶放置）。
 */
public final class BlockUtils {

    /** LB {@code render/RenderShortcuts.kt} 的 FULL_BOX（值一致）。 */
    public static final AABB FULL_BOX = new AABB(0.0, 0.0, 0.0, 1.0, 1.0, 1.0);

    private static final Predicate<Entity> PREDICATE_UNOBSTRUCTED =
            EntitySelector.NO_SPECTATORS.and(entity -> !entity.isRemoved() && entity.blocksBuilding);

    // LB utils/block/DirectionExtensions.kt
    public static final Direction[] DIRECTIONS_EXCLUDING_UP = new Direction[]{
            Direction.DOWN,
            Direction.WEST,
            Direction.EAST,
            Direction.NORTH,
            Direction.SOUTH,
    };

    public static final Direction[] DIRECTIONS_EXCLUDING_DOWN = new Direction[]{
            Direction.UP,
            Direction.WEST,
            Direction.EAST,
            Direction.NORTH,
            Direction.SOUTH,
    };

    public static final Direction[] DIRECTIONS_HORIZONTAL = new Direction[]{
            Direction.WEST,
            Direction.EAST,
            Direction.NORTH,
            Direction.SOUTH,
    };

    private BlockUtils() {
    }

    public static BlockPos toBlockPos(Vec3i self) {
        return new BlockPos(self);
    }

    public static BlockState state(BlockPos self) {
        return mc.level == null ? null : mc.level.getBlockState(self);
    }

    public static BlockState stateOrEmpty(BlockPos self) {
        BlockState state = state(self);
        return state != null ? state : Blocks.VOID_AIR.defaultBlockState();
    }

    public static Block getBlock(BlockPos self) {
        BlockState state = state(self);
        return state == null ? null : state.getBlock();
    }

    public static double getCenterDistanceSquared(BlockPos self) {
        return self.distToCenterSqr(mc.player.position());
    }

    public static double getCenterDistanceSquaredEyes(BlockPos self) {
        return self.distToCenterSqr(mc.player.getEyePosition());
    }

    /**
     * 必要时把该 BlockPos 转换为不可变实例。
     */
    public static BlockPos immutable(BlockPos self) {
        if (self instanceof BlockPos.MutableBlockPos mutable) {
            return mutable.immutable();
        }
        return self;
    }

    /**
     * 返回该位置方块的轮廓盒。空气或方块不存在时返回完整方块盒。
     * 轮廓盒仅用于渲染用途。
     */
    public static AABB outlineBox(BlockPos self) {
        BlockState blockState = state(self);
        if (blockState == null) {
            return FULL_BOX;
        }
        if (blockState.isAir()) {
            return FULL_BOX;
        }

        VoxelShape outlineShape = blockState.getShape(mc.level, self);
        AABB bounds = boundsOrNull(outlineShape);
        return bounds != null ? bounds : FULL_BOX;
    }

    public static VoxelShape collisionShape(BlockPos self) {
        BlockState state = state(self);
        return state != null ? state.getCollisionShape(mc.level, self) : Shapes.empty();
    }

    public static VoxelShape outlineShape(BlockPos self) {
        BlockState state = state(self);
        return state != null ? state.getShape(mc.level, self) : Shapes.empty();
    }

    public static AABB outlineBox(BlockState self, BlockPos blockPos) {
        VoxelShape outlineShape = self.getShape(mc.level, blockPos);

        AABB bounds = boundsOrNull(outlineShape);
        return bounds != null ? bounds : FULL_BOX;
    }

    public static boolean canStandOn(BlockPos self) {
        BlockState state = state(self);
        return state != null && state.isFaceSturdy(mc.level, self, Direction.UP, SupportType.CENTER);
    }

    /**
     * 盒子的碰撞区域（LB {@code AABB.collidingRegion}）。
     */
    public static BoundingBox collidingRegion(AABB self) {
        return new BoundingBox(
                Mth.floor(self.minX), Mth.floor(self.minY), Mth.floor(self.minZ),
                Mth.ceil(self.maxX), Mth.ceil(self.maxY), Mth.ceil(self.maxZ)
        );
    }

    /**
     * 检查盒子是否触及指定方块。
     */
    public static boolean isBlockAtPosition(AABB self, Predicate<Block> isCorrectBlock) {
        BlockPos.MutableBlockPos blockPos = new BlockPos.MutableBlockPos(0, Mth.floor(self.minY), 0);

        for (int x = Mth.floor(self.minX); x <= Mth.ceil(self.maxX); x++) {
            for (int z = Mth.floor(self.minZ); z <= Mth.ceil(self.maxZ); z++) {
                blockPos.setX(x);
                blockPos.setZ(z);

                if (isCorrectBlock.test(getBlock(blockPos))) {
                    return true;
                }
            }
        }

        return false;
    }

    /**
     * 检查盒子是否与指定方块的包围盒相交（LB 的 {@code checkCollisionShape = true} 默认值）。
     */
    public static boolean collideBlockIntersects(AABB self, Predicate<Block> isCorrectBlock) {
        return collideBlockIntersects(self, true, isCorrectBlock);
    }

    /**
     * 检查盒子是否与指定方块的包围盒相交。
     */
    public static boolean collideBlockIntersects(AABB self, boolean checkCollisionShape, Predicate<Block> isCorrectBlock) {
        for (BlockPos blockPos : VectorUtils.iterate(collidingRegion(self))) {
            BlockState blockState = state(blockPos);

            if (blockState == null || !isCorrectBlock.test(blockState.getBlock())) {
                continue;
            }

            if (!checkCollisionShape) {
                return true;
            }

            VoxelShape shape = blockState.getCollisionShape(mc.level, blockPos);

            if (shape.isEmpty()) {
                continue;
            }

            if (self.intersects(shape.bounds())) {
                return true;
            }
        }

        return false;
    }

    public static boolean canBeReplacedWith(BlockState self, BlockPos pos, ItemStack usedStack) {
        BlockPlaceContext placementContext = new BlockPlaceContext(
                mc.player,
                InteractionHand.MAIN_HAND,
                usedStack,
                new BlockHitResult(Vec3.atLowerCornerOf(pos), Direction.UP, pos, false)
        );

        return self.canBeReplaced(placementContext);
    }

    public static BlockPos targetBlockPos(BlockHitResult self) {
        return self.getBlockPos().relative(self.getDirection());
    }

    public static boolean hasAnySolidPlacementNeighbor(BlockPos self) {
        BlockPos.MutableBlockPos cache = new BlockPos.MutableBlockPos();
        for (Direction direction : Direction.values()) {
            if (!stateOrEmpty(cache.setWithOffset(self, direction)).canBeReplaced()) {
                return true;
            }
        }
        return false;
    }

    /**
     * 检查该位置是否对放置方块无遮挡。
     *
     * @see net.minecraft.world.level.EntityGetter#isUnobstructed
     */
    public static boolean isUnobstructed(BlockPos self) {
        return isUnobstructed(self, null, FULL_BOX, Predicates.alwaysTrue());
    }

    public static boolean isUnobstructed(BlockPos self, Entity except) {
        return isUnobstructed(self, except, FULL_BOX, Predicates.alwaysTrue());
    }

    public static boolean isUnobstructed(BlockPos self, Entity except, AABB box) {
        return isUnobstructed(self, except, box, Predicates.alwaysTrue());
    }

    public static boolean isUnobstructed(BlockPos self, Entity except, AABB box, Predicate<Entity> predicate) {
        AABB posBox = VectorUtils.plus(box, self);
        return mc.level.getEntities(except, posBox, PREDICATE_UNOBSTRUCTED.and(predicate)).isEmpty();
    }

    public static List<Entity> getBlockingEntities(BlockPos self) {
        return getBlockingEntities(self, null, FULL_BOX, Predicates.alwaysTrue());
    }

    public static List<Entity> getBlockingEntities(BlockPos self, Entity except) {
        return getBlockingEntities(self, except, FULL_BOX, Predicates.alwaysTrue());
    }

    public static List<Entity> getBlockingEntities(BlockPos self, Entity except, AABB box) {
        return getBlockingEntities(self, except, box, Predicates.alwaysTrue());
    }

    public static List<Entity> getBlockingEntities(BlockPos self, Entity except, AABB box, Predicate<Entity> predicate) {
        AABB posBox = VectorUtils.plus(box, self);
        return mc.level.getEntities(except, posBox, PREDICATE_UNOBSTRUCTED.and(predicate));
    }

    // --------------------------------------------------- VoxelShape 辅助（ShapeExtensions.kt）

    public static boolean allEmpty(Iterable<VoxelShape> self) {
        for (VoxelShape shape : self) {
            if (!shape.isEmpty()) {
                return false;
            }
        }
        return true;
    }

    public static boolean anyNotEmpty(Iterable<VoxelShape> self) {
        for (VoxelShape shape : self) {
            if (!shape.isEmpty()) {
                return true;
            }
        }
        return false;
    }

    /**
     * @return shape 为空时返回 null
     */
    public static AABB boundsOrNull(VoxelShape self) {
        return self.isEmpty() ? null : self.bounds();
    }

}
