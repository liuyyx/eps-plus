/*
 * This file is part of Epsilon.
 *
 * 逐行照搬自 LiquidBounce (https://github.com/CCBlueX/LiquidBounce) 的
 * utils/movement/MovementUtils.kt，以及 utils/entity/EntityExtensions.kt 中的
 * getMovementDirectionOfInput（LB 的两个重载）。
 *
 * LiquidBounce 是自由软件：你可以依据自由软件基金会发布的 GNU 通用公共许可证
 * （许可证第 3 版或你选择的任何更高版本）条款重新分发和/或修改它。
 */
package com.github.epsilon.modules.impl.movement.scaffold.util;

import com.github.epsilon.modules.impl.movement.scaffold.rotation.PolarRotationManager;
import com.github.epsilon.modules.impl.movement.scaffold.rotation.Rotation;
import com.github.epsilon.modules.impl.movement.scaffold.simulation.DirectionalInput;
import it.unimi.dsi.fastutil.objects.ObjectOpenHashSet;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.Pose;
import net.minecraft.world.entity.EntityDimensions;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.phys.shapes.VoxelShape;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Optional;
import java.util.function.UnaryOperator;

import static com.github.epsilon.Constants.mc;

/**
 * LB {@code utils/movement/MovementUtils.kt} 的逐行移植。
 */
public final class MovementUtils {

    private MovementUtils() {
    }

    /**
     * 返回该位置相对玩家位置的偏航角差。
     *
     * @param positionRelativeToPlayer 相对玩家的位置
     */
    public static float getDegreesRelativeToView(Vec3 positionRelativeToPlayer) {
        Rotation currentRotation = PolarRotationManager.INSTANCE.getCurrentRotation();
        return getDegreesRelativeToView(
                positionRelativeToPlayer,
                currentRotation != null ? currentRotation.yaw : mc.player.getYRot()
        );
    }

    public static float getDegreesRelativeToView(Vec3 positionRelativeToPlayer, float yaw) {
        float optimalYaw = VectorUtils.yaw(positionRelativeToPlayer);
        float currentYaw = Mth.wrapDegrees(yaw);

        return Mth.wrapDegrees(optimalYaw - currentYaw);
    }

    public static DirectionalInput getDirectionalInputForDegrees(DirectionalInput directionalInput, float dgs) {
        return getDirectionalInputForDegrees(directionalInput, dgs, 20.0F);
    }

    public static DirectionalInput getDirectionalInputForDegrees(DirectionalInput directionalInput, float dgs, float deadAngle) {
        boolean forwards = directionalInput.forwards;
        boolean backwards = directionalInput.backwards;
        boolean left = directionalInput.left;
        boolean right = directionalInput.right;

        if (dgs > -90.0F + deadAngle && dgs < 90.0F - deadAngle) {
            forwards = true;
        } else if (dgs < -90.0F - deadAngle || dgs > 90.0F + deadAngle) {
            backwards = true;
        }

        if (dgs > 0.0F + deadAngle && dgs < 180.0F - deadAngle) {
            right = true;
        } else if (dgs > -180.0F + deadAngle && dgs < 0.0F - deadAngle) {
            left = true;
        }

        return new DirectionalInput(forwards, backwards, left, right);
    }

    public static Vec3 findEdgeCollision(Vec3 from, Vec3 to) {
        return findEdgeCollision(from, to, 0.5F);
    }

    public static Vec3 findEdgeCollision(Vec3 from, Vec3 to, float allowedDropDown) {
        Vec3 lineVec = VectorUtils.minus(to, from);
        if (lineVec.lengthSqr() <= 1.0E-12) {
            return null;
        }

        ArrayList<AABB> boundingBoxes = collectCollisionBoundingBoxes(from, to, allowedDropDown);

        Vec3 currentFrom = from;

        Vec3 extendedFrom = VectorUtils.fma(from, -1000.0, lineVec);
        Vec3 extendedTo = VectorUtils.fma(to, 1000.0, lineVec);

        // LB: objectHashSetOf<AABB>()
        ObjectOpenHashSet<AABB> cache = new ObjectOpenHashSet<>();
        while (true) {
            // LB: boundingBoxes.filterTo(cache) { it.contains(currentFrom) }（返回的就是 cache 本身）
            ObjectOpenHashSet<AABB> boxesContainingFrom = cache;
            for (AABB box : boundingBoxes) {
                if (box.contains(currentFrom)) {
                    boxesContainingFrom.add(box);
                }
            }

            // 若没有包围盒包含 from，说明我们会掉下去
            if (boxesContainingFrom.isEmpty()) {
                return currentFrom;
            }

            // 若存在同时包含 from 与 to 的包围盒，则不会撞到边缘
            for (AABB box : boxesContainingFrom) {
                if (box.contains(to)) {
                    return null;
                }
            }

            Vec3[] hits = new Vec3[boxesContainingFrom.size()];
            int index = 0;
            for (AABB box : boxesContainingFrom) {
                Optional<Vec3> res = box.clip(extendedTo, extendedFrom);

                // 这次射线检测不应失败。
                Vec3 hit = res.orElse(null);
                if (hit == null) {
                    throw new IllegalArgumentException(
                            "Raycast failed. This should be impossible. AABB=" + box + " from=" + from + " to=" + to
                    );
                }
                hits[index++] = hit;
            }

            currentFrom = Collections.min(
                    Arrays.asList(hits),
                    (a, b) -> Double.compare(a.distanceToSqr(to), b.distanceToSqr(to))
            );

            boundingBoxes.removeAll(boxesContainingFrom);
            cache.clear();
        }
    }

    private static ArrayList<AABB> collectCollisionBoundingBoxes(Vec3 from, Vec3 to, float allowedDropDown) {
        EntityDimensions playerDims = mc.player.getDimensions(Pose.STANDING);

        AABB fromBox = playerDims.makeBoundingBox(from);
        AABB toBox = playerDims.makeBoundingBox(to);

        AABB unionBox = fromBox.minmax(toBox);

        BlockPos fromBlockPos = BlockPos.containing(
                unionBox.minX - 0.3 - 1.0E-7,
                unionBox.minY - allowedDropDown - 1.0E-7,
                unionBox.minZ - 0.3 - 1.0E-7
        );
        BlockPos toBlockPos = BlockPos.containing(
                unionBox.maxX + 0.3 + 1.0E-7,
                unionBox.minY + 1.0E-7,
                unionBox.maxZ + 0.3 + 1.0E-7
        );

        Vec3 lineVec = to.subtract(from);
        Vec3 extendedFrom = VectorUtils.fma(from, -1000.0, lineVec);
        Vec3 extendedTo = VectorUtils.fma(to, 1000.0, lineVec);

        ArrayList<AABB> foundBoxes = new ArrayList<>();

        Level world = mc.level;

        for (BlockPos pos : VectorUtils.iterate(VectorUtils.rangeTo(fromBlockPos, toBlockPos))) {
            BlockState state = world.getBlockState(pos);

            VoxelShape collisionShape = state.getCollisionShape(world, pos);

            collisionShape.forAllBoxes((minX, minY, minZ, maxX, maxY, maxZ) -> {
                AABB adjustedBox = new AABB(
                        minX - 0.3,
                        minY - 1.0,
                        minZ - 0.3,
                        maxX + 0.3,
                        maxY + allowedDropDown + 0.05,
                        maxZ + 0.3
                ).move(pos);

                if (!adjustedBox.clip(extendedFrom, extendedTo).isPresent()) {
                    return;
                }

                foundBoxes.add(adjustedBox);
            });
        }

        return foundBoxes;
    }

    public static void setDeltaMovement(LocalPlayer self, UnaryOperator<Vec3> block) {
        self.setDeltaMovement(block.apply(self.getDeltaMovement()));
    }

    public static void stopXZVelocity(LocalPlayer self) {
        self.setDeltaMovement(VectorUtils.copy(self.getDeltaMovement(), 0.0, self.getDeltaMovement().y, 0.0));
    }

    // ------------------------------------ LB utils/entity/EntityExtensions.kt: getMovementDirectionOfInput

    public static float getMovementDirectionOfInput(LocalPlayer player) {
        return getMovementDirectionOfInput(player, new DirectionalInput(player.input));
    }

    public static float getMovementDirectionOfInput(LocalPlayer player, DirectionalInput input) {
        return getMovementDirectionOfInput(player.getYRot(), input);
    }

    public static float getMovementDirectionOfInput(float facingYaw) {
        return getMovementDirectionOfInput(facingYaw, new DirectionalInput(mc.player.input));
    }

    public static float getMovementDirectionOfInput(float facingYaw, DirectionalInput input) {
        float actualYaw = facingYaw;
        float forwardMultiplier;
        if (input.backwards && !input.forwards) {
            actualYaw += 180f;
            forwardMultiplier = -0.5f;
        } else if (input.forwards && !input.backwards) {
            forwardMultiplier = 0.5f;
        } else {
            forwardMultiplier = 1f;
        }

        if (input.left && !input.right) {
            actualYaw -= 90f * forwardMultiplier;
        }
        if (input.right && !input.left) {
            actualYaw += 90f * forwardMultiplier;
        }

        return actualYaw;
    }

}
