package com.github.epsilon.modules.impl.movement.scaffold.elowen.rotation;

import com.github.epsilon.modules.impl.movement.scaffold.elowen.ElowenScaffold;
import com.github.epsilon.utils.rotation.Rot2f;
import net.minecraft.util.Mth;

/**
 * ACA 旋转（elowen {@code com.elowen.o.Y.Z.l.J.T}）。
 *
 * <p>elowen 原文里的 {@code confused = J.getConfusionFlag() != null} 是混淆哨兵：
 * {@code J.getConfusionFlag()} 运行期恒为 {@code null}（见 §判定基线），
 * 故所有 {@code confused} 分支与 {@code Q.d(Q.x())} 毒丸调用整段删除。</p>
 *
 * <p>[elowen o/Y/Z/l/J/T.java:13-48]</p>
 */
public class AcaRotation implements RotationHandler {

    private static final float MAX_STEP = 12.0f;
    private static final float STEP_FACTOR = 0.35f;

    @Override
    public Rot2f apply(ElowenScaffold engine, Rot2f current, Rot2f target, float maxStep) {
        // 原：RotationUtils.e(current.H, target.H) * STEP_FACTOR（e = wrapDegrees(a - b)）
        float stepYaw = Mth.wrapDegrees(current.getYaw() - target.getYaw()) * STEP_FACTOR;
        float stepPitch = (target.getPitch() - current.getPitch()) * STEP_FACTOR;
        double distance = Math.sqrt(stepYaw * stepYaw + stepPitch * stepPitch);
        if (distance > MAX_STEP) {
            distance = MAX_STEP / distance;
            stepYaw = (float) ((double) stepYaw * distance);
            stepPitch = (float) ((double) stepPitch * distance);
        }
        float yaw = current.getYaw() + stepYaw;
        float pitch = current.getPitch() + stepPitch;
        float yawDistance = Math.abs(Mth.wrapDegrees(yaw - target.getYaw())) - 0.5f;
        int yawSign = yawDistance == 0.0f ? 0 : (yawDistance < 0.0f ? -1 : 1);
        if (yawSign < 0) {
            yaw = target.getYaw();
        }
        float pitchDistance = Math.abs(pitch - target.getPitch()) - 0.5f;
        yawSign = pitchDistance == 0.0f ? 0 : (pitchDistance < 0.0f ? -1 : 1);
        if (yawSign < 0) {
            // 原实现把 pitch 无条件覆盖为 |pitch-target|（差值），导致 pitch 永远在震荡、收敛不到目标。
            pitch = target.getPitch();
        }
        return new Rot2f(yaw, pitch);
    }
}
