package com.github.epsilon.modules.impl.movement.scaffold.rotation;

/**
 * 逐行移植自 LiquidBounce nextgen {@code utils/aiming/features/processors/RotationProcessor.kt}（commit 2d94475）。
 * <p>
 * LB 原文：
 * <pre>
 * interface RotationProcessor {
 *     fun process(
 *         rotationTarget: RotationTarget,
 *         currentRotation: Rotation,
 *         targetRotation: Rotation
 *     ): Rotation
 * }
 * </pre>
 */
public interface RotationProcessor {

    Rotation process(
            RotationTarget rotationTarget,
            Rotation currentRotation,
            Rotation targetRotation
    );

}
