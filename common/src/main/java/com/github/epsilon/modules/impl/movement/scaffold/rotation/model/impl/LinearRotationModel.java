/*
 * This file is part of Epsilon.
 *
 * 逐行照抄自 OpenPal（https://github.com/OpenPal）MC 1.21.10 的
 * wtf.opal.client.feature.helper.impl.player.rotation.model.impl.LinearRotationModel，
 * 只做 Yarn → 26.2 Mojang 命名替换（Vec2f → Rot2f、MathHelper → Mth）。
 */
package com.github.epsilon.modules.impl.movement.scaffold.rotation.model.impl;

import com.github.epsilon.modules.impl.movement.scaffold.rotation.model.EnumRotationModel;
import com.github.epsilon.modules.impl.movement.scaffold.rotation.model.IRotationModel;
import com.github.epsilon.utils.rotation.Rot2f;
import net.minecraft.util.Mth;

public final class LinearRotationModel implements IRotationModel {
    private final double speed;

    public LinearRotationModel(double speed) {
        this.speed = speed;
    }

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

        final double maxYaw = this.speed * distributionYaw;
        final double maxPitch = this.speed * distributionPitch;

        final float moveYaw = (float) Math.max(Math.min(deltaYaw, maxYaw), -maxYaw);
        final float movePitch = (float) Math.max(Math.min(deltaPitch, maxPitch), -maxPitch);

        return new Rot2f(from.getYaw() + moveYaw, from.getPitch() + movePitch);
    }

    @Override
    public EnumRotationModel getEnum() {
        return EnumRotationModel.LINEAR;
    }
}
