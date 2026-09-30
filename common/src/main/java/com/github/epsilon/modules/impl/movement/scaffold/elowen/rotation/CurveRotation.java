package com.github.epsilon.modules.impl.movement.scaffold.elowen.rotation;

import com.github.epsilon.modules.impl.movement.scaffold.elowen.ElowenScaffold;
import com.github.epsilon.utils.rotation.Rot2f;
import net.minecraft.util.Mth;

/**
 * Curve 旋转（elowen {@code com.elowen.o.Y.Z.l.J.M}）：步长从当前上限出发，每刻 ×0.85 衰减，
 * 下限 5°，到位（yaw/pitch 都 &lt; 0.5°）后直接返回目标角并复位。
 *
 * <p>elowen 原文里的 {@code Q[] qArray = J.getConfusionFlag()} 恒为 {@code null}（§判定基线），
 * 对应的 {@code else} 分支与结尾的 {@code Q.X()} 毒丸调用（该式恒为 false）整段删除。</p>
 *
 * <p>[elowen o/Y/Z/l/J/M.java:12-50]</p>
 */
public class CurveRotation implements RotationHandler {

    private static final float STEP_DECAY_FACTOR = 0.85f;
    private static final float MIN_STEP = 5.0f;

    private float currentStep = 0.0f;
    private boolean arrived = true;

    @Override
    public Rot2f apply(ElowenScaffold engine, Rot2f current, Rot2f target, float maxStep) {
        if (this.arrived) {
            this.currentStep = maxStep;
            this.arrived = false;
        }
        if (this.currentStep <= 0.0f) {
            this.currentStep = maxStep;
        }
        float dx = Mth.wrapDegrees(current.getYaw() - target.getYaw());
        float dy = target.getPitch() - current.getPitch();
        float moveX = Math.abs(dx);
        float limit = 0.5f;
        if (moveX < limit && Math.abs(dy) < limit) {
            this.arrived = true;
            return new Rot2f(target.getYaw(), target.getPitch());
        }
        moveX = -this.currentStep;
        limit = Math.min(this.currentStep, dx);
        float stepX = Math.max(moveX, limit);
        float stepY = Math.max(-this.currentStep, Math.min(this.currentStep, dy));
        this.currentStep = Math.max(MIN_STEP, this.currentStep * STEP_DECAY_FACTOR);
        return new Rot2f(current.getYaw() + stepX, current.getPitch() + stepY);
    }
}
