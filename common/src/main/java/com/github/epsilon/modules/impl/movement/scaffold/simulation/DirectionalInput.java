/*
 * This file is part of Epsilon.
 *
 * 逐行移植自 LiquidBounce (https://github.com/CCBlueX/LiquidBounce)
 * 源文件: src/main/kotlin/net/ccbluex/liquidbounce/utils/movement/DirectionalInput.kt
 * Copyright (c) 2015 - 2026 CCBlueX — GNU General Public License v3.0
 */
package com.github.epsilon.modules.impl.movement.scaffold.simulation;

import net.minecraft.client.Options;
import net.minecraft.client.player.ClientInput;
import net.minecraft.world.entity.player.Input;

import java.util.Objects;

/**
 * LB 原文: {@code data class DirectionalInput(val forwards: Boolean, val backwards: Boolean, val left: Boolean, val right: Boolean)}
 */
public final class DirectionalInput {

    public final boolean forwards;
    public final boolean backwards;
    public final boolean left;
    public final boolean right;

    public DirectionalInput(boolean forwards, boolean backwards, boolean left, boolean right) {
        this.forwards = forwards;
        this.backwards = backwards;
        this.left = left;
        this.right = right;
    }

    public DirectionalInput(Options options) {
        this(
                options.keyUp.isDown(),
                options.keyDown.isDown(),
                options.keyLeft.isDown(),
                options.keyRight.isDown()
        );
    }

    // [适配] LB 原文为 constructor(input: ClientInput) : this(input.untransformed)；
    //        Epsilon 无 LB Mixin 提供的 ClientInput.untransformed，改用本轮键位输入 keyPresses（语义等价）。
    public DirectionalInput(ClientInput input) {
        this(input.keyPresses);
    }

    public DirectionalInput(Input input) {
        this(
                input.forward(),
                input.backward(),
                input.left(),
                input.right()
        );
    }

    public DirectionalInput(float movementForward, float movementSideways) {
        this(
                movementForward > 0.0f,
                movementForward < 0.0f,
                movementSideways > 0.0f,
                movementSideways < 0.0f
        );
    }

    public DirectionalInput invert() {
        return new DirectionalInput(
                this.backwards,
                this.forwards,
                this.right,
                this.left
        );
    }

    public boolean isMoving() {
        return this.forwards != this.backwards || this.left != this.right;
    }

    public static final DirectionalInput NONE = new DirectionalInput(false, false, false, false);

    public static final DirectionalInput FORWARDS = new DirectionalInput(true, false, false, false);

    public static final DirectionalInput BACKWARDS = new DirectionalInput(false, true, false, false);

    public static final DirectionalInput LEFT = new DirectionalInput(false, false, true, false);

    public static final DirectionalInput RIGHT = new DirectionalInput(false, false, false, true);

    public static final DirectionalInput FORWARDS_LEFT = new DirectionalInput(true, false, true, false);

    public static final DirectionalInput FORWARDS_RIGHT = new DirectionalInput(true, false, false, true);

    public static final DirectionalInput BACKWARDS_LEFT = new DirectionalInput(false, true, true, false);

    public static final DirectionalInput BACKWARDS_RIGHT = new DirectionalInput(false, true, false, true);

    // data class 语义（PlayerSimulationCache 依赖值相等）
    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (!(o instanceof DirectionalInput other)) {
            return false;
        }
        return this.forwards == other.forwards
                && this.backwards == other.backwards
                && this.left == other.left
                && this.right == other.right;
    }

    @Override
    public int hashCode() {
        return Objects.hash(this.forwards, this.backwards, this.left, this.right);
    }

    @Override
    public String toString() {
        return "DirectionalInput(forwards=" + this.forwards
                + ", backwards=" + this.backwards
                + ", left=" + this.left
                + ", right=" + this.right
                + ")";
    }

}
