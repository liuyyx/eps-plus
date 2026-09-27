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

import com.github.epsilon.events.impl.KeyboardInputEvent;
import com.github.epsilon.modules.impl.movement.scaffold.simulation.DirectionalInput;
import com.github.epsilon.modules.impl.movement.scaffold.util.EntityUtils;

import java.util.concurrent.ThreadLocalRandom;

import static com.github.epsilon.Constants.mc;

/**
 * 逐行照搬 LiquidBounce `features/module/modules/world/scaffold/techniques/normal/ScaffoldEagleFeature.kt`（commit 2d94475）。
 * <p>
 * [适配] LB 为 `object ScaffoldEagleFeature : ToggleableValueGroup(ScaffoldNormalTechnique, "Eagle", false)`；
 * Epsilon 侧无 ToggleableValueGroup，设置项先以静态字段暴露，由阶段 7 接 Epsilon 设置（默认值取自 LB 源码）。
 */
public final class ScaffoldEagleFeature {

    public static final ScaffoldEagleFeature INSTANCE = new ScaffoldEagleFeature();

    // LB:30 ToggleableValueGroup(ScaffoldNormalTechnique, "Eagle", false) 的 enabled
    // [待接线] 阶段 7 接 Epsilon 设置（Eagle / Enabled，默认 false）
    public static boolean enabled = false;

    // LB:32 intRange("BlocksToEagle", 0..0, 0..10).asRefreshable()
    // [待接线] 阶段 7 接 Epsilon 设置（Eagle / Blocks To Eagle Min-Max，默认 0..0）
    public static int blocksToEagleMin = 0;
    public static int blocksToEagleMax = 0;

    // LB:33 floatRange("EdgeDistance", 0.01f..0.05f, 0.01f..1.3f).asRefreshable()
    // [待接线] 阶段 7 接 Epsilon 设置（Eagle / Edge Distance Min-Max，默认 0.01f..0.05f）
    public static float edgeDistanceMin = 0.01f;
    public static float edgeDistanceMax = 0.05f;

    // LB:34 private val onlyOnGround by boolean("OnlyOnGround", true)
    // [待接线] 阶段 7 接 Epsilon 设置（Eagle / Only On Ground，默认 true）
    public static boolean onlyOnGround = true;

    // LB: `blocksToEagle.current`（asRefreshable 的 current = get().random()；默认范围 0..0 → 恒为 0）
    private static int blocksToEagle = 0;

    // LB: `edgeDistance.current`（asRefreshable 的 current = get().random()；默认范围 0.01f..0.05f）
    private static float edgeDistance = randomEdgeDistance();

    // LB:36-37 private var placedBlocks = 0
    private int placedBlocks = 0;

    private ScaffoldEagleFeature() {
    }

    public static boolean isEnabled() {
        return enabled;
    }

    public static void setEnabled(boolean value) {
        enabled = value;
    }

    public static int getBlocksToEagleMin() {
        return blocksToEagleMin;
    }

    public static void setBlocksToEagleMin(int value) {
        blocksToEagleMin = value;
    }

    public static int getBlocksToEagleMax() {
        return blocksToEagleMax;
    }

    public static void setBlocksToEagleMax(int value) {
        blocksToEagleMax = value;
    }

    public static float getEdgeDistanceMin() {
        return edgeDistanceMin;
    }

    public static void setEdgeDistanceMin(float value) {
        edgeDistanceMin = value;
    }

    public static float getEdgeDistanceMax() {
        return edgeDistanceMax;
    }

    public static void setEdgeDistanceMax(float value) {
        edgeDistanceMax = value;
    }

    public static boolean isOnlyOnGround() {
        return onlyOnGround;
    }

    public static void setOnlyOnGround(boolean value) {
        onlyOnGround = value;
    }

    // LB: `blocksToEagle.current`
    public static int getBlocksToEagle() {
        return blocksToEagle;
    }

    // LB: `edgeDistance.current`
    public static float getEdgeDistance() {
        return edgeDistance;
    }

    // LB:39-45
    // @Suppress("unused") private val stateUpdateHandler = handler<MovementInputEvent>(priority = SAFETY_FEATURE) { … }
    // [适配] LB 内部订阅 MovementInputEvent（事件带 directionalInput）；Epsilon 的等价事件为 KeyboardInputEvent（无 directionalInput），
    //   故抽成公共方法，由阶段 7 的协调层在 KeyboardInputEvent 处理点上调用并传入其维护的 DirectionalInput；
    //   LB 的 EventPriorityConvention.SAFETY_FEATURE 优先级落在阶段 7 的订阅点。
    public void handleMovementInput(KeyboardInputEvent event, DirectionalInput directionalInput) {
        if (!event.isSneak() && shouldEagle(directionalInput)) {
            event.setSneak(true);
        }
    }

    // LB:47-59
    public boolean shouldEagle(DirectionalInput input) {
        // LB:48-50 ScaffoldDownFeature.shouldFallOffBlock() —— Epsilon 无对应模块（ScaffoldDownFeature 不搬），恒 false
        boolean shouldFallOffBlock = false;
        if (shouldFallOffBlock) {
            return false;
        }

        if (!mc.player.onGround() && onlyOnGround) {
            return false;
        }

        boolean shouldBeActive = !mc.player.getAbilities().flying && placedBlocks == 0;

        return shouldBeActive && EntityUtils.isCloseToEdge(mc.player, input, (double) edgeDistance);
    }

    // LB:61-73
    public void onBlockPlacement() {
        if (!enabled) {
            return;
        }

        placedBlocks++;

        if (placedBlocks > blocksToEagle) {
            placedBlocks = 0;
            refreshBlocksToEagle();
            refreshEdgeDistance();
        }
    }

    // LB: blocksToEagle.refresh() → RefreshableIntState.refresh() = current = get().random()
    // [适配] RNG：Kotlin Random.Default → ThreadLocalRandom；IntRange.random() 为含两端均匀分布（范围为 0..0 时恒为 0）
    private static void refreshBlocksToEagle() {
        blocksToEagle = blocksToEagleMin + ThreadLocalRandom.current().nextInt(blocksToEagleMax - blocksToEagleMin + 1);
    }

    // LB: edgeDistance.refresh() → RefreshableFloatState.refresh() = current = get().random()
    // [适配] RNG：Kotlin Random.Default → ThreadLocalRandom；ClosedFloatingPointRange<Float>.random() 为 [min, max) 均匀分布
    private static void refreshEdgeDistance() {
        edgeDistance = randomEdgeDistance();
    }

    private static float randomEdgeDistance() {
        return ThreadLocalRandom.current().nextFloat() * (edgeDistanceMax - edgeDistanceMin) + edgeDistanceMin;
    }

}
