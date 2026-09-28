/*
 * This file is part of Epsilon.
 *
 * 照抄自 OpenPal（https://github.com/OpenPal）MC 1.21.10 的
 * wtf.opal.client.feature.helper.impl.player.rotation.RotationProperty。
 *
 * <p>OpenPal 的 RotationProperty 自己持有 {@code ModeProperty/NumberProperty/GroupProperty}
 * 三件套（属于 OpenPal 的设置框架）；本仓库的设置挂在模块上（见 {@code Scaffold}），
 * 而两个 Uitems 模式并没有对应的"旋转模型"下拉框（§5.1 设置清单里没有这一项），
 * 所以这里只保留 OpenPal 的取值面（{@code getMaxAngle/getDriftIntensity/getJitterIntensity/
 * createModel/isModel}）与默认值（{@code InstantRotationModel} + Max angle 90 / Drift 1.2 / Jitter 0.12），
 * 供 {@code EnumRotationModel.supply(...)} 与 {@code ScaffoldSettings.isRotationModel(...)} 使用。</p>
 */
package com.github.epsilon.modules.impl.movement.scaffold.rotation;

import com.github.epsilon.modules.impl.movement.scaffold.rotation.model.EnumRotationModel;
import com.github.epsilon.modules.impl.movement.scaffold.rotation.model.IRotationModel;

public final class RotationProperty {

    private final EnumRotationModel modelProperty;

    private final int maxAngle;
    private final double driftIntensity, jitterIntensity;

    public RotationProperty(final IRotationModel defaultModel) {
        this.modelProperty = defaultModel.getEnum();

        this.maxAngle = 90;

        this.driftIntensity = 1.2;
        this.jitterIntensity = 0.12;
    }

    public int getMaxAngle() {
        return this.maxAngle;
    }

    public double getDriftIntensity() {
        return this.driftIntensity;
    }

    public double getJitterIntensity() {
        return this.jitterIntensity;
    }

    public IRotationModel createModel() {
        return this.modelProperty.supply(this);
    }

    public boolean isModel(final EnumRotationModel model) {
        return this.modelProperty == model;
    }

}
