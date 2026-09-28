/*
 * This file is part of Epsilon.
 *
 * 逐行照抄自 OpenPal（https://github.com/OpenPal）MC 1.21.10 的
 * wtf.opal.client.feature.helper.impl.player.rotation.model.IRotationModel，
 * 只做 Yarn → 26.2 Mojang 命名替换（Vec2f → Rot2f）。
 */
package com.github.epsilon.modules.impl.movement.scaffold.rotation.model;

import com.github.epsilon.utils.rotation.Rot2f;

public interface IRotationModel {
    Rot2f tick(Rot2f from, Rot2f to, float timeDelta);
    EnumRotationModel getEnum();
}
