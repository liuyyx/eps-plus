/*
 * This file is part of Epsilon.
 *
 * 逐行移植自 LiquidBounce (https://github.com/CCBlueX/LiquidBounce)
 * 源文件: src/main/kotlin/net/ccbluex/liquidbounce/features/module/modules/world/scaffold/features/ScaffoldMovementPrediction.kt
 * Copyright (c) 2015 - 2026 CCBlueX — GNU General Public License v3.0
 */
package com.github.epsilon.modules.impl.movement.scaffold.movement;

import com.github.epsilon.Constants;
import com.github.epsilon.modules.impl.movement.scaffold.targetfinding.Line;
import com.github.epsilon.modules.impl.movement.scaffold.util.EntityUtils;
import com.github.epsilon.modules.impl.movement.scaffold.util.MovementUtils;
import com.github.epsilon.modules.impl.movement.scaffold.util.VectorUtils;
import net.minecraft.util.Mth;
import net.minecraft.world.phys.Vec3;

import java.util.ArrayDeque;

/**
 * LB 原文: {@code object ScaffoldMovementPrediction : ToggleableValueGroup(ModuleScaffold, "Prediction", true)}
 * <p>
 * [适配] LB 的 ValueGroup 设置字段在 Epsilon 侧先以静态字段 + getter/setter 暴露（默认值取 LB 源码），
 * 由阶段 7 接线到 Epsilon 设置。
 */
public final class ScaffoldMovementPrediction {

    // [适配] Java 禁止字段初始化式前向引用常量 → 常量声明提前（LB 原文中常量写在 lastPlacementOffsets 之后）
    private static final int MAX_PLACEMENT_OFFSETS = 4;

    private static final ArrayDeque<Vec3> lastPlacementOffsets = new ArrayDeque<>(MAX_PLACEMENT_OFFSETS + 1);

    // [待接线] 阶段 7 接 Epsilon 设置 —— LB: ToggleableValueGroup(ModuleScaffold, "Prediction", true)
    private static boolean enabled = true;

    /**
     * How far the bootstrap prediction stays behind the detected edge before placement history exists.
     * <p>
     * LB: float("BootstrapBackoff", 0.2f, 0.0f..0.4f)
     */
    private static float bootstrapBackoff = 0.2f;

    /**
     * How close to the edge the player can get before future-position prediction is disabled.
     * <p>
     * LB: float("PredictionCutoffDistance", 0.05f, 0.0f..0.3f)
     */
    private static float predictionCutoffDistance = 0.05f;

    /**
     * How many recorded placements are used to blend from bootstrap prediction into history-based prediction.
     * <p>
     * LB: int("WarmupPlacements", 2, 0..MAX_PLACEMENT_OFFSETS)
     */
    private static int warmupPlacements = 2;

    private ScaffoldMovementPrediction() {
    }

    public static boolean isEnabled() {
        return enabled;
    }

    public static void setEnabled(boolean value) {
        enabled = value;
    }

    public static float getBootstrapBackoff() {
        return bootstrapBackoff;
    }

    public static void setBootstrapBackoff(float value) {
        bootstrapBackoff = value;
    }

    public static float getPredictionCutoffDistance() {
        return predictionCutoffDistance;
    }

    public static void setPredictionCutoffDistance(float value) {
        predictionCutoffDistance = value;
    }

    public static int getWarmupPlacements() {
        return warmupPlacements;
    }

    public static void setWarmupPlacements(int value) {
        warmupPlacements = value;
    }

    public static void reset() {
        lastPlacementOffsets.clear();
    }

    // LB: override fun onDisabled() { reset(); super.onDisabled() }（组禁用本身由阶段 7 的 Epsilon 设置接管）
    public static void onDisabled() {
        reset();
    }

    public static void onPlace(Line optimalLine, Vec3 lastFallOffPosition) {
        if (optimalLine == null || !enabled) {
            return;
        }

        if (lastFallOffPosition == null) {
            return;
        }
        Vec3 fallOffPoint = lastFallOffPosition;

        float lineDirAngle = (float) Math.atan2(optimalLine.direction.z, optimalLine.direction.x);

        Vec3 unrotatedOffset = Constants.mc.player.position().subtract(fallOffPoint).yRot(lineDirAngle);

        // LB: debugParameter("AvgPlacementPos") { ... } → 不搬（调试渲染，路径外）

        lastPlacementOffsets.addLast(unrotatedOffset);

        if (lastPlacementOffsets.size() > MAX_PLACEMENT_OFFSETS) {
            lastPlacementOffsets.removeFirst();
        }
    }

    public static Vec3 getAvgPlacementPos() {
        if (lastPlacementOffsets.isEmpty()) {
            return null;
        }

        return VectorUtils.average(lastPlacementOffsets);
    }

    /**
     * Calculates where the player will stand when he places the block. Useful for rotations
     *
     * @return the predicted pos or {@code null} if the prediction failed
     */
    public static Vec3 getPredictedPlacementPos(Line optimalLine) {
        if (optimalLine == null || !enabled) {
            return null;
        }

        // When we are close to the edge, we are able to place right now. Thus, we don't want to use a future position
        if (EntityUtils.isCloseToEdge(Constants.mc.player, (double) predictionCutoffDistance)) {
            return null;
        }

        // If the next placement point is far in the future. Don't predict for now
        Vec3 fallOffPoint = getFallOffPositionOnLine(optimalLine);
        if (fallOffPoint == null) {
            return null;
        }

        Vec3 playerPos = Constants.mc.player.position();
        Vec3 fallOffPointToPlayer = fallOffPoint.subtract(playerPos);
        Vec3 bootstrapPos = getBootstrapPlacementPos(fallOffPoint, fallOffPointToPlayer);
        // Keep the current lateral offset before enough history is available.
        Vec3 last = getAvgPlacementPos();
        if (last == null) {
            ScaffoldMovementPlanner.SupportReference supportReference = ScaffoldMovementPlanner.getCurrentSupportReference();
            return supportReference != null
                    ? bootstrapPos.add(supportReference.offsetX, 0.0, supportReference.offsetZ)
                    : bootstrapPos;
        }

        float lineDirAngle = (float) Math.atan2(optimalLine.direction.z, optimalLine.direction.x);
        Vec3 predictedPos = fallOffPoint.add(last.yRot(-lineDirAngle));

        return bootstrapPos.lerp(predictedPos, getWarmupBlendFactor());
    }

    public static Vec3 getFallOffPositionOnLine(Line optimalLine) {
        // TODO Check if the player is moving away from the line and implement another prediction method for that case

        Vec3 nearestPosToPlayer = optimalLine.getNearestPointTo(Constants.mc.player.position());

        Vec3 fromLine = nearestPosToPlayer.add(0.0, -0.1, 0.0);
        Vec3 toLine = fromLine.add(VectorUtils.withLength(optimalLine.direction, 3.0));

        Vec3 edgeCollision = MovementUtils.findEdgeCollision(fromLine, toLine);
        if (edgeCollision == null) {
            return null;
        }

        // LB: edgeCollision.copy(y = player.y)
        Vec3 fallOffPoint = VectorUtils.copy(edgeCollision, edgeCollision.x, Constants.mc.player.getY(), edgeCollision.z);

        return fallOffPoint;
    }

    private static Vec3 getBootstrapPlacementPos(Vec3 fallOffPoint, Vec3 fallOffPointToPlayer) {
        if (bootstrapBackoff <= 0.0f) {
            return fallOffPoint;
        }

        return fallOffPoint.subtract(VectorUtils.withLength(fallOffPointToPlayer, (double) bootstrapBackoff));
    }

    private static double getWarmupBlendFactor() {
        if (warmupPlacements <= 0) {
            return 1.0;
        }

        return Mth.clamp((double) lastPlacementOffsets.size() / (double) warmupPlacements, 0.0, 1.0);
    }

}
