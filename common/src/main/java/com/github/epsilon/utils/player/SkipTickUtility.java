/*
 * This file is part of Epsilon.
 *
 * 逐行照抄自 OpenPal（https://github.com/OpenPal）MC 1.21.10 的
 * wtf.opal.utility.player.SkipTickUtility，只做 Yarn → 26.2 Mojang 命名替换。
 */
package com.github.epsilon.utils.player;

public final class SkipTickUtility {

    private static int skipTicks;

    private SkipTickUtility() {
    }

    public static void addSkipTicks(final int ticks) {
        if (ticks <= 0) {
            return;
        }
        skipTicks += ticks;
    }

    public static boolean consumeSkipTick() {
        if (skipTicks > 0) {
            skipTicks--;
            return true;
        }
        return false;
    }

    public static void reset() {
        skipTicks = 0;
    }
}
