/*
 * This file is part of Epsilon.
 *
 * 逐行移植自 LiquidBounce (https://github.com/CCBlueX/LiquidBounce)
 * 源文件: src/main/kotlin/net/ccbluex/liquidbounce/utils/entity/PlayerSimulation.kt
 * Copyright (c) 2015 - 2026 CCBlueX — GNU General Public License v3.0
 */
package com.github.epsilon.modules.impl.movement.scaffold.simulation;

import net.minecraft.world.phys.Vec3;

import java.util.Objects;

public interface PlayerSimulation {

    Vec3 getPos();

    void tick();

    /**
     * LB 原文: {@code data class Rigid(override val pos: Vec3) : PlayerSimulation}
     */
    final class Rigid implements PlayerSimulation {

        public final Vec3 pos;

        public Rigid(Vec3 pos) {
            this.pos = pos;
        }

        @Override
        public Vec3 getPos() {
            return this.pos;
        }

        @Override
        public void tick() {
            // Do nothing.
        }

        @Override
        public boolean equals(Object o) {
            if (this == o) {
                return true;
            }
            return o instanceof Rigid other && Objects.equals(this.pos, other.pos);
        }

        @Override
        public int hashCode() {
            return Objects.hashCode(this.pos);
        }

        @Override
        public String toString() {
            return "Rigid(pos=" + this.pos + ")";
        }

    }

}
