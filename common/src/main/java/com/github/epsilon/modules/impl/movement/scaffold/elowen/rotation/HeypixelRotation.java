package com.github.epsilon.modules.impl.movement.scaffold.elowen.rotation;

import com.github.epsilon.modules.impl.movement.scaffold.elowen.ElowenScaffold;
import com.github.epsilon.utils.rotation.Rot2f;
import net.minecraft.util.Mth;

/**
 * Heypixel 旋转（elowen {@code com.elowen.o.Y.Z.l.J.V}）。
 *
 * <p>着地时：yaw 按 speed 逼近目标、pitch 直奔 90；yaw/pitch 都进 1° 内（且上一刻未对齐）
 * 时冻结 2 刻（{@code stillTicks}）。空中：{@code airTicks == 1} 用 80° 上限、否则 50°，
 * {@code airTicks >= 3} 直接返回目标角；上限再减去一个 0.001~0.005 的随机量。</p>
 *
 * <p>elowen 原文里的 {@code confusionFlag = J.getConfusionFlag()} 恒为 {@code null}（§判定基线），
 * 对应分支整段删除。</p>
 *
 * <p>[elowen o/Y/Z/l/J/V.java:14-89]</p>
 */
public class HeypixelRotation implements RotationHandler {

    private static final int MAX_AIR_TICKS = 3;
    private static final float GROUND_MAX_DELTA = 80.0f;
    private static final float AIR_MAX_DELTA = 50.0f;
    private static final int STILL_TICK_HOLD = 2;

    private int groundTicks = 0;
    private int airTicks = 0;
    private int stillTicks = 0;
    private boolean wasAligned = false;

    @Override
    public Rot2f apply(ElowenScaffold engine, Rot2f current, Rot2f target, float maxStep) {
        if (engine.minecraft().player == null) {
            return target;
        }
        boolean onGround = engine.minecraft().player.onGround();
        if (onGround) {
            ++this.groundTicks;
            this.airTicks = 0;
        } else {
            ++this.airTicks;
            this.groundTicks = 0;
        }
        int tick = this.stillTicks--;
        if (tick > 0) {
            return current;
        }
        tick = onGround ? 1 : 0;
        if (tick != 0) {
            float yaw = ElowenScaffold.stepTo(current.getYaw(), target.getYaw(), maxStep);
            float pitch = ElowenScaffold.stepTo(current.getPitch(), 90.0f, maxStep);
            float yawDelta = Math.abs(Mth.wrapDegrees(yaw - target.getYaw())) - 1.0f;
            int yawSign = yawDelta == 0.0f ? 0 : (yawDelta < 0.0f ? -1 : 1);
            boolean aligned;
            if (yawSign >= 0) {
                aligned = false;
            } else {
                float pitchDelta = Math.abs(pitch - 90.0f) - 1.0f;
                int pitchSign = pitchDelta == 0.0f ? 0 : (pitchDelta < 0.0f ? -1 : 1);
                aligned = pitchSign < 0;
            }
            if (aligned && !this.wasAligned) {
                this.stillTicks = STILL_TICK_HOLD;
            }
            this.wasAligned = aligned;
            return new Rot2f(Mth.wrapDegrees(yaw), pitch);
        }
        this.wasAligned = false;
        int currentAirTicks = this.airTicks;
        if (currentAirTicks >= MAX_AIR_TICKS) {
            return target;
        }
        float maxDelta = currentAirTicks == 1 ? GROUND_MAX_DELTA : AIR_MAX_DELTA;
        // 原：Z/W.K(0.001, 0.005)（区间随机）
        float remaining = maxDelta - (float) (0.001 + Math.random() * (0.005 - 0.001));
        return new Rot2f(Mth.wrapDegrees(ElowenScaffold.stepTo(current.getYaw(), target.getYaw(), remaining)), target.getPitch());
    }
}
