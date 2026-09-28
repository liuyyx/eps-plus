/*
 * This file is part of Epsilon.
 *
 * 逐行照抄自 OpenPal（https://github.com/OpenPal）MC 1.21.10 的
 * wtf.opal.utility.player.RaytracedRotation，只做 Yarn → 26.2 Mojang 命名替换
 * （Vec2f → Rot2f）。
 */
package com.github.epsilon.utils.player;

import com.github.epsilon.utils.rotation.Rot2f;
import net.minecraft.world.phys.HitResult;

public record RaytracedRotation(Rot2f rotation, HitResult hitResult) {
    public RaytracedRotation withRotation(final Rot2f rotation) {
        return new RaytracedRotation(rotation, this.hitResult);
    }
}
