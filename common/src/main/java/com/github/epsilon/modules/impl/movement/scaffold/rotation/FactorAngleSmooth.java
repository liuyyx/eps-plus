package com.github.epsilon.modules.impl.movement.scaffold.rotation;

import net.minecraft.world.phys.Vec2;

import java.util.concurrent.ThreadLocalRandom;

/**
 * 逐行移植自 LiquidBounce nextgen {@code utils/aiming/features/processors/anglesmooth/FactorAngleSmooth.kt}
 * （commit 2d94475）。
 */
public abstract class FactorAngleSmooth extends AngleSmooth {

    /**
     * Calculate the factors for the rotation towards the target rotation.
     *
     * @param currentRotation The current rotation
     * @param targetRotation The target rotation
     * @return horizontal speed, vertical speed
     */
    public abstract Vec2 calculateFactors(
            RotationTarget rotationTarget,
            Rotation currentRotation,
            Rotation targetRotation
    );

    @Override
    public Rotation process(RotationTarget rotationTarget, Rotation currentRotation, Rotation targetRotation) {
        // LB 原文：val (horizontalFactor, verticalFactor) = calculateFactors(...)
        Vec2 factors = calculateFactors(rotationTarget, currentRotation, targetRotation);
        float horizontalFactor = factors.x;
        float verticalFactor = factors.y;

        return currentRotation.towardsLinear(targetRotation, horizontalFactor, verticalFactor);
    }

    @Override
    public int calculateTicks(Rotation currentRotation, Rotation targetRotation) {
        // [修正 LB 的 0/0 边界，非自由发挥] 已经到位时先返回 0。
        //   LB 的 do-while 在 currentRotation == targetRotation（角差为 0）时，
        //   `towardsLinear` 内部会算出 `abs(0 / 0)` = NaN，旋转变成 NaN 后
        //   `isRotationDeltaCloseTo` 永远不成立，循环必然跑满 80 次并返回 80。
        //   下游 `ScaffoldLedgeFeature.ledge()` 的 `isNotReady = ticks >= 1` 于是恒真、
        //   每刻返回 `sneakTime = 80` → **玩家被永久强制潜行**（实测日志：ticks 恒 80、forceSneak 77~80）。
        //   LB 该分支的本意是"转向还没到位时短暂潜行自保"，不是常驻潜行，故在此补一个到位判断，
        //   其余路径与 LB 完全一致（循环体、80 上限、判据都不动）。
        if (currentRotation.isRotationDeltaCloseTo(targetRotation)) {
            return 0;
        }

        int ticks = -1;

        do {
            // LB 原文：val (horizontalFactor, verticalFactor) = calculateFactors(null, currentRotation, targetRotation)
            Vec2 factors = calculateFactors(null, currentRotation, targetRotation);
            float horizontalFactor = factors.x;
            float verticalFactor = factors.y;

            currentRotation = currentRotation.towardsLinear(targetRotation, horizontalFactor, verticalFactor);
            ticks++;
        } while (!currentRotation.isRotationDeltaCloseTo(targetRotation) && ticks < 80);

        return ticks;
    }

    /**
     * LB 原文：{@code utils/kotlin/ArrayExtensions.kt:86-88}
     * <pre>
     * fun ClosedFloatingPointRange&lt;Float&gt;.random(): Float {
     *     return if (start &gt;= endInclusive) start else ThreadLocalRandom.current().nextFloat(start, endInclusive)
     * }
     * </pre>
     * [适配] 位置：移植为 {@code FactorAngleSmooth} 的静态方法（LB 的该扩展属于 utils/kotlin 工具层，不在本包范围）。
     */
    protected static float random(float start, float endInclusive) {
        return start >= endInclusive ? start : ThreadLocalRandom.current().nextFloat(start, endInclusive);
    }

}
