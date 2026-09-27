/*
 * 移植自 LiquidBounce (https://github.com/CCBlueX/LiquidBounce)，GPL-3.0-or-later
 * 源文件：src/main/kotlin/net/ccbluex/liquidbounce/utils/clicking/pattern/patterns/EfficientPattern.kt
 */
package com.github.epsilon.modules.impl.movement.scaffold.clicking;

/**
 * 保证每次点击之间至少相隔一个 tick。
 * <p>LB 原文：Keeps at least one-tick interval between each click.
 * <p>LB: {@code object EfficientPattern : ClickPattern}
 */
public final class EfficientPattern implements ClickPattern {

    public static final EfficientPattern INSTANCE = new EfficientPattern();

    private EfficientPattern() {
    }

    @Override
    public void fill(int[] clickArray, IntRange cps, Clicker clicker) {
        int clicks = cps.random();

        // 当 CPS 低于 cycle 长度的一半时，Efficient 会产生很大的空隙，因此改用 StabilizedPattern。
        // LB: return StabilizedPattern.fill(clickArray, cps, clicker)
        if (clicks < 10) {
            StabilizedPattern.INSTANCE.fill(clickArray, cps, clicker);
            return;
        }

        for (int i = 0; i < clicks; i++) {
            clickArray[i * 2 % clickArray.length]++;
        }
    }

}
