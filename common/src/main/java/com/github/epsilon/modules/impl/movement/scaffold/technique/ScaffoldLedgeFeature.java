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

import com.github.epsilon.modules.impl.movement.scaffold.ScaffoldPolarCoordinator;
import com.github.epsilon.modules.impl.movement.scaffold.rotation.Rotation;
import com.github.epsilon.modules.impl.movement.scaffold.targetfinding.TargetFinding;
import com.github.epsilon.modules.impl.movement.scaffold.util.EntityUtils;

import static com.github.epsilon.Constants.mc;

/**
 * 逐行照搬 LiquidBounce `features/module/modules/world/scaffold/features/ScaffoldLedgeFeature.kt`（commit 2d94475）。
 * <p>
 * [适配] LB 的 `LedgeAction` / `ledge` / `ScaffoldLedgeExtension` 都是该文件的顶层声明；Java 一个文件只允许一个 public 顶层类型，
 * 故三者作为 {@link ScaffoldLedgeFeature} 的嵌套公共类型保留（FQN：`ScaffoldLedgeFeature.LedgeAction`、
 * `ScaffoldLedgeFeature.ScaffoldLedgeExtension`）。逻辑与调用关系不变。
 */
public final class ScaffoldLedgeFeature {

    private ScaffoldLedgeFeature() {
    }

    // LB:29-41
    public record LedgeAction(boolean jump, int sneakTime, boolean stopInput, boolean stepBack) {

        // LB:36-39
        public static final LedgeAction NO_LEDGE = new LedgeAction(false, 0, false, false);

    }

    // LB:43-65
    public static LedgeAction ledge(
            TargetFinding.BlockPlacementTarget target,
            Rotation rotation,
            ScaffoldLedgeExtension extension
    ) {
        if (EntityUtils.isCloseToEdge(mc.player)) {
            int ticks = ScaffoldPolarCoordinator.getRotationValueGroup().calculateTicks(rotation);

            // LB:51 ModuleDebug.debugParameter(ModuleScaffold, "TicksUntilDestination", ticks) —— 调试渲染不搬（路径外）

            boolean isLowOnBlocks = ScaffoldPolarCoordinator.getBlockCount() <= 0;
            boolean isNotReady = ticks >= 1;

            if (isLowOnBlocks || isNotReady) {
                return new LedgeAction(false, Math.max(1, ticks), false, false);
            }
        }

        return extension != null ? extension.ledge(target, rotation) : LedgeAction.NO_LEDGE;
    }

    // LB:46 参数默认值 `extension: ScaffoldLedgeExtension? = null` → Java 重载
    public static LedgeAction ledge(
            TargetFinding.BlockPlacementTarget target,
            Rotation rotation
    ) {
        return ledge(target, rotation, null);
    }

    // LB:67-72 fun interface ScaffoldLedgeExtension
    @FunctionalInterface
    public interface ScaffoldLedgeExtension {

        LedgeAction ledge(TargetFinding.BlockPlacementTarget target, Rotation rotation);

    }

}
