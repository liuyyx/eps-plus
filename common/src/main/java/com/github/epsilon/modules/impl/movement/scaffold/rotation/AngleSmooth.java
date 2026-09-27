package com.github.epsilon.modules.impl.movement.scaffold.rotation;

/**
 * 逐行移植自 LiquidBounce nextgen {@code utils/aiming/features/processors/anglesmooth/AngleSmooth.kt}
 * （commit 2d94475）。
 * <p>
 * LB 原文：
 * <pre>
 * abstract class AngleSmooth(
 *     name: String,
 *     override val parent: ModeValueGroup&lt;*&gt;,
 *     aliases: List&lt;String&gt; = emptyList()
 * ) : Mode(name, aliases), RotationProcessor {
 *     abstract fun calculateTicks(currentRotation: Rotation, targetRotation: Rotation): Int
 * }
 * </pre>
 * [适配] LB 的 {@code Mode}/{@code ModeValueGroup} 设置容器不搬（契约 §4.1）→ 本类只保留计算契约。
 */
public abstract class AngleSmooth implements RotationProcessor {

    @Override
    public abstract Rotation process(RotationTarget rotationTarget, Rotation currentRotation, Rotation targetRotation);

    public abstract int calculateTicks(Rotation currentRotation, Rotation targetRotation);

    /**
     * 契约 §4.1 要求的方法。
     * <p>
     * [新增] LB 的 {@code AngleSmooth}/{@code Mode}/{@code ValueGroup} 层级没有 {@code reset()}；本包的 4 个
     * AngleSmooth 实现都是无内部状态的纯计算器 → 基类为空实现，阶段 7 若引入带状态实现可覆写。
     */
    public void reset() {
    }

}
