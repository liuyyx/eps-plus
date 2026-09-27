/*
 * This file is part of LiquidBounce (https://github.com/CCBlueX/LiquidBounce)
 *
 * Copyright (c) 2015 - 2026 CCBlueX
 *
 * LiquidBounce is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * LiquidBounce is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with LiquidBounce. If not, see <https://www.gnu.org/licenses/>.
 */
package com.github.epsilon.modules.impl.movement.scaffold.technique;

import com.github.epsilon.modules.impl.movement.scaffold.rotation.Rotation;
import com.github.epsilon.modules.impl.movement.scaffold.targetfinding.Line;
import com.github.epsilon.modules.impl.movement.scaffold.targetfinding.TargetFinding;
import com.github.epsilon.modules.impl.movement.scaffold.util.Raytracing;
import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.Pose;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;

import java.util.Comparator;

/**
 * 逐行照搬 LiquidBounce `features/module/modules/world/scaffold/techniques/ScaffoldTechnique.kt`（commit 2d94475）。
 * <p>
 * [适配] LB 为 `sealed class ScaffoldTechnique(name: String) : Mode(name)`，并覆写 `parent = ModuleScaffold.technique`。
 * Epsilon 侧没有 ModeValueGroup / parent 容器，故本类为普通抽象类（无 parent），实例由协调层持有。
 */
public abstract class ScaffoldTechnique {

    /**
     * LB: Mode(name) 的 name 属性。
     */
    private final String name;

    protected ScaffoldTechnique(String name) {
        this.name = name;
    }

    public String getName() {
        return name;
    }

    // LB:39-44
    public abstract TargetFinding.BlockPlacementTarget findPlacementTarget(
            Vec3 predictedPos,
            Pose predictedPose,
            Line optimalLine,
            ItemStack bestStack
    );

    // LB:46
    public Rotation getRotations(TargetFinding.BlockPlacementTarget target) {
        return target != null ? target.rotation() : null;
    }

    // LB:48-49
    public BlockHitResult getCrosshairTarget(TargetFinding.BlockPlacementTarget target, Rotation rotation) {
        return Raytracing.traceFromPlayer(rotation);
    }

    /**
     * Prioritize the block that is closest to the line, if there was no line found, prioritize the nearest block.
     */
    // LB:51-61
    protected Comparator<BlockPos> priorityComparator(
            Vec3 predictedPos,
            Line optimalLine
    ) {
        if (optimalLine != null) {
            return TargetFinding.BlockPlacementTargetFindingOptions.leastBlockDistanceToLine(optimalLine);
        } else {
            return TargetFinding.BlockPlacementTargetFindingOptions.leastBlockDistanceToPos(predictedPos);
        }
    }

}
