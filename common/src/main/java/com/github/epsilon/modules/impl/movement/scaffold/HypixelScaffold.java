/*
 * This file is part of Epsilon.
 *
 * 逐行照抄自 OpenPal（https://github.com/OpenPal）MC 1.21.10 的
 * wtf.opal.client.feature.module.impl.world.scaffold.mode.HypixelScaffold（64 行）。
 * 命名替换与 {@link HeypixelScaffold} 一致（含 {@code getEnumValue()} 无对应物、已去掉；
 * {@code module.getSettings()} → {@code module.getScaffoldSettings()}；
 * {@code MoveUtility.getDirectionDegrees(yaw)} → {@code MovementUtils.getMovementDirectionOfInput(yaw)}）。
 */
package com.github.epsilon.modules.impl.movement.scaffold;

import com.github.epsilon.modules.impl.movement.Scaffold;
import com.github.epsilon.modules.impl.movement.scaffold.rotation.RotationHelper;
import com.github.epsilon.modules.impl.movement.scaffold.rotation.model.EnumRotationModel;
import com.github.epsilon.modules.impl.movement.scaffold.util.MovementUtils;

import static com.github.epsilon.Constants.mc;

public final class HypixelScaffold extends HeypixelScaffold {

    public HypixelScaffold(final Scaffold module) {
        super(module);
    }

    public boolean isHandlingEvents() {
        return module.isEnabled() && module.isHypixelMode();
    }

    @Override
    protected float resolveBaseYaw() {
        if (!module.getScaffoldSettings().isRotationModel(EnumRotationModel.SIDEWAYS)) {
            return super.resolveBaseYaw();
        }

        return resolveSideYaw();
    }

    @Override
    protected boolean shouldTrackMovementYawDuringTelly() {
        return module.getScaffoldSettings().isRotationModel(EnumRotationModel.SIDEWAYS);
    }

    @Override
    protected boolean isTellyEnabled() {
        return true;
    }

    @Override
    protected int getTellyTick() {
        return 5;
    }

    @Override
    protected float getRotateSpeed() {
        return 180.0F;
    }

    @Override
    protected float getRotateBackSpeed() {
        return 180.0F;
    }

    private float resolveSideYaw() {
        return MovementUtils.getMovementDirectionOfInput(RotationHelper.getClientHandler().getYawOr(mc.player.getYRot()));
    }
}
