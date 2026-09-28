/*
 * This file is part of Epsilon.
 *
 * 逐行照抄自 OpenPal（https://github.com/OpenPal）MC 1.21.10 的
 * wtf.opal.client.feature.helper.impl.player.rotation.model.impl.InstantRotationModel，
 * 只做 Yarn → 26.2 Mojang 命名替换（Vec2f → Rot2f、MathHelper → Mth）。
 */
package com.github.epsilon.modules.impl.movement.scaffold.rotation.model.impl;

import com.github.epsilon.modules.impl.movement.scaffold.rotation.model.EnumRotationModel;
import com.github.epsilon.modules.impl.movement.scaffold.rotation.model.IRotationModel;
import com.github.epsilon.utils.rotation.Rot2f;
import net.minecraft.util.Mth;

public final class InstantRotationModel implements IRotationModel {
    public static final InstantRotationModel INSTANCE = new InstantRotationModel();

    private InstantRotationModel() {
    }

    @Override
    public Rot2f tick(Rot2f from, Rot2f to, float timeDelta) { // Interpolates between ticks, finishes rotation instantly tick-wise
        final float deltaYaw = Mth.wrapDegrees(to.getYaw() - from.getYaw()) * timeDelta;
        final float deltaPitch = (to.getPitch() - from.getPitch()) * timeDelta;
        return new Rot2f(from.getYaw() + deltaYaw, from.getPitch() + deltaPitch);
    }

    @Override
    public EnumRotationModel getEnum() {
        return EnumRotationModel.INSTANT;
    }
}
