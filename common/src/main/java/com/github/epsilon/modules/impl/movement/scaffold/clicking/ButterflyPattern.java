/*
 * 移植自 LiquidBounce (https://github.com/CCBlueX/LiquidBounce)，GPL-3.0-or-later
 * 源文件：src/main/kotlin/net/ccbluex/liquidbounce/utils/clicking/pattern/patterns/ButterflyPattern.kt
 */
package com.github.epsilon.modules.impl.movement.scaffold.clicking;

import java.util.ArrayList;
import java.util.List;

/**
 * 蝴蝶点击（Butterfly clicking）是一种用来突破 20 CPS 上限的手法。
 * <p>LB 原文：
 * <pre>
 * Butterfly clicking is a method that is used to bypass the CPS limit of 20.
 *
 * It will often result in double click (very similar to the double click technique - but randomized).
 * </pre>
 * <p>LB: {@code object ButterflyPattern : ClickPattern}
 */
public final class ButterflyPattern implements ClickPattern {

    public static final ButterflyPattern INSTANCE = new ButterflyPattern();

    private ButterflyPattern() {
    }

    @Override
    public void fill(int[] clickArray, IntRange cps, Clicker clicker) {
        int clicks = cps.random();

        while (ClickPattern.sum(clickArray) < clicks) {
            // 找出点击数组中所有还没被点过的下标
            // LB: val indices = clickArray.indices.filter { clickArray[it] == 0 }
            List<Integer> indices = new ArrayList<>();
            for (int i = 0; i < clickArray.length; i++) {
                if (clickArray[i] == 0) {
                    indices.add(i);
                }
            }

            if (!indices.isEmpty()) {
                // 随机挑一个还没点过的下标，写入 1~2 的随机点击数
                // LB: indices.random().let { index -> clickArray[index] = Clicker.RNG.nextInt(1, 3) }
                clickArray[indices.get(ClickPattern.randomIndex(indices.size()))] = Clicker.RNG.nextInt(1, 3);
            } else {
                // 随机给一个下标 +1
                // LB: clickArray.indices.random().let { index -> clickArray[index]++ }
                clickArray[new IntRange(0, clickArray.length - 1).random()]++;
            }
        }
    }

}
