/*
 * 移植自 LiquidBounce (https://github.com/CCBlueX/LiquidBounce)，GPL-3.0-or-later
 * 源文件：src/main/kotlin/net/ccbluex/liquidbounce/utils/clicking/pattern/patterns/DragPattern.kt
 */
package com.github.epsilon.modules.impl.movement.scaffold.clicking;

/**
 * 拖动点击（Drag clicking）是一种用来突破 20 CPS 上限的手法。
 * <p>LB 原文：
 * <pre>
 * Drag clicking is a method that is used to bypass the CPS limit of 20.
 *
 * It can be done by gliding your finger over the mouse button and causing friction
 * to click very fast.
 *
 * Is not very easy to do as it requires a lot of practice and a good mouse,
 * as well as a good grip on the mouse. Sweaty hands are a big no-no.
 *
 * This is very hard to implement as I am not able to do this method myself,
 * so I will simply guess how it works.
 * </pre>
 * <p>LB: {@code object DragPattern : ClickPattern}
 */
public final class DragPattern implements ClickPattern {

    public static final DragPattern INSTANCE = new DragPattern();

    private DragPattern() {
    }

    @Override
    public void fill(int[] clickArray, IntRange cps, Clicker clicker) {
        int clicks = cps.random();

        /*
         * 行程时间（travel time）是把手指从鼠标顶部移到底部所需的时间。
         *
         * 走完这段行程后需要把手指移回顶部，期间无法点击。这样通常更稳定。
         */
        // LB: Clicker.RNG.nextInt(17, 19) → java.util.Random 的 RandomGenerator.nextInt(origin, bound)
        int travelTime = Clicker.RNG.nextInt(17, 19);

        // 把点击塞进行程时间内
        while (ClickPattern.sum(clickArray) < clicks) {
            // 在点击数组中填满行程区域内的点击

            // 取行程区域内点击次数最少的下标
            // LB: clickArray.copyOf(travelTime).indices.minByOrNull { clickArray[it] }!!
            int index = 0;
            for (int i = 1; i < travelTime; i++) {
                // 严格小于 → 保留 minByOrNull「取第一个最小值」的语义
                if (clickArray[i] < clickArray[index]) {
                    index = i;
                }
            }

            // 该下标的点击数 +1
            clickArray[index]++;
        }
    }

}
