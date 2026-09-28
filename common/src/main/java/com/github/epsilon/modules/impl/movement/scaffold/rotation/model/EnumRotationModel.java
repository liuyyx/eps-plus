/*
 * This file is part of Epsilon.
 *
 * 逐行照抄自 OpenPal（https://github.com/OpenPal）MC 1.21.10 的
 * wtf.opal.client.feature.helper.impl.player.rotation.model.EnumRotationModel。
 */
package com.github.epsilon.modules.impl.movement.scaffold.rotation.model;

import com.github.epsilon.modules.impl.movement.scaffold.rotation.RotationProperty;
import com.github.epsilon.modules.impl.movement.scaffold.rotation.model.impl.HeypixelRotationModel;
import com.github.epsilon.modules.impl.movement.scaffold.rotation.model.impl.InstantRotationModel;
import com.github.epsilon.modules.impl.movement.scaffold.rotation.model.impl.LinearRotationModel;
import com.github.epsilon.modules.impl.movement.scaffold.rotation.model.impl.OrganicRotationModel;
import com.github.epsilon.modules.impl.movement.scaffold.rotation.model.impl.SidewaysRotationModel;

import java.util.function.Function;

public enum EnumRotationModel {
    INSTANT("Instant", r -> InstantRotationModel.INSTANCE),
    HEYPIXEL("Heypixel", r -> new HeypixelRotationModel(r.getMaxAngle())),
    LINEAR("Linear", r -> new LinearRotationModel(r.getMaxAngle())),
    ORGANIC("Organic", r -> new OrganicRotationModel(r.getMaxAngle(), r.getDriftIntensity(), r.getJitterIntensity())),
    SIDEWAYS("Sideways", r -> new SidewaysRotationModel(r.getMaxAngle()));

    private final String name;
    private final Function<RotationProperty, IRotationModel> modelSupplier;

    EnumRotationModel(String name, Function<RotationProperty, IRotationModel> modelSupplier) {
        this.name = name;
        this.modelSupplier = modelSupplier;
    }

    @Override
    public String toString() {
        return name;
    }

    public IRotationModel supply(final RotationProperty property) {
        return modelSupplier.apply(property);
    }
}
