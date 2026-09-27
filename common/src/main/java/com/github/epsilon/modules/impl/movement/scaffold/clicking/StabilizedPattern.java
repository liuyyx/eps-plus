/*
 * 移植自 LiquidBounce (https://github.com/CCBlueX/LiquidBounce)，GPL-3.0-or-later
 * 源文件：src/main/kotlin/net/ccbluex/liquidbounce/utils/clicking/pattern/patterns/StabilizedPattern.kt
 */
package com.github.epsilon.modules.impl.movement.scaffold.clicking;

/**
 * 普通点击，但使用稳定的点击周期。
 * <p>LB 原文：Normal clicking but with a stabilized click cycle.
 * <p>LB: {@code object StabilizedPattern : ClickPattern}（无状态单例 → Java 常量 INSTANCE）
 */
public final class StabilizedPattern implements ClickPattern {

    public static final StabilizedPattern INSTANCE = new StabilizedPattern();

    private StabilizedPattern() {
    }

    @Override
    public void fill(int[] clickArray, IntRange cps, Clicker clicker) {
        int clicks = cps.random();

        // 计算间隔，并把余数分散开以均匀分布
        int interval = clicks > 0 ? clickArray.length / clicks : 0;
        int remainder = clicks > 0 ? clickArray.length % clicks : 0;

        int currentIndex = 0;

        for (int i = 0; i < clicks; i++) {
            clickArray[currentIndex % clickArray.length]++;
            currentIndex += Math.max(interval, 1);
            if (remainder > 0) {
                currentIndex++;
                remainder--;
            }
        }
    }

}
