package com.github.epsilon.modules.impl.movement.scaffold.elowen.rotation;

import com.github.epsilon.modules.impl.movement.scaffold.elowen.ElowenScaffold;
import com.github.epsilon.utils.rotation.Rot2f;

/**
 * Linear 旋转（elowen {@code com.elowen.o.Y.Z.l.J.s}）。
 *
 * <p>[elowen o/Y/Z/l/J/s.java:10-18]</p>
 */
public class LinearRotation implements RotationHandler {

    @Override
    public Rot2f apply(ElowenScaffold engine, Rot2f current, Rot2f target, float maxStep) {
        // 原：RotationUtils.i(maxStep, current.H, target.H) → stepTo(current, target, maxStep)
        float yaw = ElowenScaffold.stepTo(current.getYaw(), target.getYaw(), maxStep);
        // 原：RotationUtils.q(current.E, target.E, maxStep)
        float pitch = ElowenScaffold.stepTo(current.getPitch(), target.getPitch(), maxStep);
        return new Rot2f(yaw, pitch);
    }
}
