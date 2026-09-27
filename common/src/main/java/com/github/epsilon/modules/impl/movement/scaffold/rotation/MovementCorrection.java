package com.github.epsilon.modules.impl.movement.scaffold.rotation;

/**
 * 逐行移植自 LiquidBounce nextgen {@code utils/aiming/features/MovementCorrection.kt}（commit 2d94475）。
 * <p>
 * LB 原文：{@code enum class MovementCorrection(override val tag: String) : Tagged}，
 * 值的顺序与 tag 与 LB 完全一致（OFF / STRICT / SILENT / CHANGE_LOOK）。
 * <p>
 * Corrects movement when aiming away from client-side view direction.
 */
public enum MovementCorrection {

    /**
     * No movement correction is applied. This feels the best, as it does not
     * change the movement of the player and also not affects Sprinting.
     * However, this can be detected by anti-cheats.
     */
    OFF("Off"),

    /**
     * Corrects movement by changing the yaw when updating the movement.
     */
    STRICT("Strict"),

    /**
     * Correct movement by changing the yaw when updating the movement,
     * but also tweaks the keyboard input to not aggressively change the
     * players walk direction.
     */
    SILENT("Silent"),

    /**
     * Corrects movement by changing the actual look direction of the player.
     */
    CHANGE_LOOK("ChangeLook");

    // LB 原文：override val tag: String
    private final String tag;

    MovementCorrection(String tag) {
        this.tag = tag;
    }

    public String getTag() {
        return tag;
    }

}
