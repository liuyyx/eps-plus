/*
 * This file is part of Epsilon.
 *
 * 逐行照抄自 OpenPal（https://github.com/OpenPal）MC 1.21.10 的
 * wtf.opal.client.feature.helper.impl.player.rotation.model.impl.HypixelRotationModel，
 * 只做 Yarn → 26.2 Mojang 命名替换（Vec2f → Rot2f、MathHelper → Mth）；
 * LocalDataWatch.get().airTicks → EntityUtils.airTicks(mc.player)；
 * MoveUtility.getDirectionDegrees() → MovementUtils.getMovementDirectionOfInput(yaw)。
 */
package com.github.epsilon.modules.impl.movement.scaffold.rotation.model.impl;

import com.github.epsilon.modules.impl.movement.scaffold.rotation.RotationHelper;
import com.github.epsilon.modules.impl.movement.scaffold.rotation.model.EnumRotationModel;
import com.github.epsilon.modules.impl.movement.scaffold.rotation.model.IRotationModel;
import com.github.epsilon.modules.impl.movement.scaffold.util.EntityUtils;
import com.github.epsilon.modules.impl.movement.scaffold.util.MovementUtils;
import com.github.epsilon.utils.rotation.Rot2f;
import net.minecraft.util.Mth;

import static com.github.epsilon.Constants.mc;


public class HypixelRotationModel implements IRotationModel {


    @Override
    public Rot2f tick(Rot2f from, Rot2f to, float timeDelta) {
        final float deltaYaw = Mth.wrapDegrees(to.getYaw() - from.getYaw()) * timeDelta;
        final float deltaPitch = (to.getPitch() - from.getPitch()) * timeDelta;

        final double distance = Math.sqrt(deltaYaw * deltaYaw + deltaPitch * deltaPitch);
        if (distance == 0.D) {
            return new Rot2f(from.getYaw() + deltaYaw, from.getPitch() + deltaPitch);
        }
        final double distributionYaw = Math.abs(deltaYaw / distance);
        final double distributionPitch = Math.abs(deltaPitch / distance);

        final double maxYaw = this.getSpeed() * distributionYaw;
        final double maxPitch = this.getSpeed() * distributionPitch;

        final float moveYaw = (float) Math.max(Math.min(deltaYaw, maxYaw), -maxYaw);
        final float movePitch = (float) Math.max(Math.min(deltaPitch, maxPitch), -maxPitch);

        return new Rot2f(from.getYaw() + moveYaw, from.getPitch() + movePitch);
    }

    private float getSpeed() {

        return isYawDiagonal() ? (EntityUtils.airTicks(mc.player) == 1 ? 65f : 36f) : 35f;
    }

    private boolean isYawDiagonal() {
        final float direction = Math.abs(getDirectionDegrees() % 90);
        final int range = 30;
        return direction > 45 - range && direction < 45 + range;
    }

    /** OpenPal {@code MoveUtility.getDirectionDegrees()} 的对应物（见 §2 映射表）。 */
    private static float getDirectionDegrees() {
        return MovementUtils.getMovementDirectionOfInput(RotationHelper.getClientHandler().getYawOr(mc.player.getYRot()));
    }

    @Override
    public EnumRotationModel getEnum() {
        return null;
    }
}
