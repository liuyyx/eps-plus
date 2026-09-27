/*
 * 移植自 LiquidBounce (https://github.com/CCBlueX/LiquidBounce)，GPL-3.0-or-later
 * 源文件：src/main/kotlin/net/ccbluex/liquidbounce/utils/clicking/pattern/patterns/SpammingPattern.kt
 */
package com.github.epsilon.modules.impl.movement.scaffold.clicking;

/**
 * 普通点击是最常见的点击方式，CPS 通常是 5-8，激进时偶尔 10-12。
 * <p>LB 原文：
 * <pre>
 * Normal clicking is the most common clicking method and usually
 * results in a CPS of 5-8 and sometimes when aggressive 10-12.
 *
 * It is when clicking normally with your finger.
 *
 * @note I was not able to press faster than 8 CPS. @1zuna
 * </pre>
 * <p>LB: {@code object SpammingPattern : ClickPattern}
 */
public final class SpammingPattern implements ClickPattern {

    public static final SpammingPattern INSTANCE = new SpammingPattern();

    private SpammingPattern() {
    }

    @Override
    public void fill(int[] clickArray, IntRange cps, Clicker clicker) {
        int clicks = cps.random();

        for (int i = 0; i < clicks; i++) {
            // 把点击数组中的随机下标 +1
            // LB: clickArray.indices.random().let { index -> clickArray[index]++ }（indices 是 IntRange）
            clickArray[new IntRange(0, clickArray.length - 1).random()]++;
        }
    }

}
