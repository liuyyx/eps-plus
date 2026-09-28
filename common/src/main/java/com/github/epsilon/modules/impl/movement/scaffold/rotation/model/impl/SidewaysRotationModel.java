/*
 * This file is part of Epsilon.
 *
 * 逐行照抄自 OpenPal（https://github.com/OpenPal）MC 1.21.10 的
 * wtf.opal.client.feature.helper.impl.player.rotation.model.impl.SidewaysRotationModel，
 * 只做 Yarn → 26.2 Mojang 命名替换（Vec2f → Rot2f、MathHelper → Mth）。
 */
package com.github.epsilon.modules.impl.movement.scaffold.rotation.model.impl;

import com.github.epsilon.modules.impl.movement.scaffold.rotation.model.EnumRotationModel;
import com.github.epsilon.modules.impl.movement.scaffold.rotation.model.IRotationModel;
import com.github.epsilon.utils.player.RotationUtility;
import com.github.epsilon.utils.rotation.Rot2f;
import net.minecraft.util.Mth;

public final class SidewaysRotationModel implements IRotationModel {

    private final float speed;

    public SidewaysRotationModel(final float speed) {
        this.speed = speed;
    }

    @Override
    public Rot2f tick(final Rot2f from, final Rot2f to, final float timeDelta) {
        final float targetYaw = to.getYaw();
        final float targetPitch = to.getPitch();
        final float lastYaw = from.getYaw();
        final float lastPitch = from.getPitch();

        final float deltaYaw = Mth.wrapDegrees(targetYaw - lastYaw);
        final float deltaPitch = targetPitch - lastPitch;

        final double distance = Math.sqrt(deltaYaw * deltaYaw + deltaPitch * deltaPitch);
        if (distance <= 1.0E-6D) {
            return from;
        }

        final double distributionYaw = Math.abs(deltaYaw / distance);
        final double distributionPitch = Math.abs(deltaPitch / distance);

        final double maxYaw = speed * distributionYaw;
        final double maxPitch = speed * distributionPitch;

        final float moveYaw = (float) Math.max(Math.min(deltaYaw, maxYaw), -maxYaw);
        final float movePitch = (float) Math.max(Math.min(deltaPitch, maxPitch), -maxPitch);

        final Rot2f rotation = new Rot2f(lastYaw + moveYaw, Mth.clamp(lastPitch + movePitch, -90.0F, 90.0F));
        return RotationUtility.patchConstantRotation(rotation, from);
    }

    @Override
    public EnumRotationModel getEnum() {
        return EnumRotationModel.SIDEWAYS;
    }
}
