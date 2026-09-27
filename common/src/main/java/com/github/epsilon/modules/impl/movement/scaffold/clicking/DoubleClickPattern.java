/*
 * 移植自 LiquidBounce (https://github.com/CCBlueX/LiquidBounce)，GPL-3.0-or-later
 * 源文件：src/main/kotlin/net/ccbluex/liquidbounce/utils/clicking/pattern/patterns/DoubleClickPattern.kt
 */
package com.github.epsilon.modules.impl.movement.scaffold.clicking;

/**
 * 双击不是一种点击手法，而是少数作弊鼠标上的一个按键（FIRE 键），按一次会触发两次点击。
 * <p>LB 原文：
 * <pre>
 * Double-clicking is NOT a method but a button on a few cheating mice.
 * This button is called the FIRE button and will result in two clicks when pressed once.
 *
 * This is a method that is not allowed on most servers and is considered cheating.
 * Unlikely to bypass and will result in twice the CPS (!!!).
 *
 * @note In the past I had a mouse with this feature and I always used it. @1zuna
 * </pre>
 * <p>LB: {@code object DoubleClickPattern : ClickPattern}
 */
public final class DoubleClickPattern implements ClickPattern {

    public static final DoubleClickPattern INSTANCE = new DoubleClickPattern();

    private DoubleClickPattern() {
    }

    @Override
    public void fill(int[] clickArray, IntRange cps, Clicker clicker) {
        int clicks = cps.random();

        for (int i = 0; i < clicks; i++) {
            // 把点击数组中的随机下标 +2
            // LB: clickArray.indices.random().let { index -> clickArray[index] += 2 }（indices 是 IntRange）
            clickArray[new IntRange(0, clickArray.length - 1).random()] += 2;
        }
    }

}
