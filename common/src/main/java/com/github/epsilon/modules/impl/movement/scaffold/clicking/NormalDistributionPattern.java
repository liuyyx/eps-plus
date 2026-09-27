/*
 * 移植自 LiquidBounce (https://github.com/CCBlueX/LiquidBounce)，GPL-3.0-or-later
 * 源文件：src/main/kotlin/net/ccbluex/liquidbounce/utils/clicking/pattern/patterns/NormalDistributionPattern.kt
 */
package com.github.epsilon.modules.impl.movement.scaffold.clicking;

/**
 * 正态分布点击模式。
 * <p>LB 原文：Normal distribution clicking pattern.
 * <p>LB: {@code object NormalDistributionPattern : ClickPattern}
 */
public final class NormalDistributionPattern implements ClickPattern {

    public static final NormalDistributionPattern INSTANCE = new NormalDistributionPattern();

    private NormalDistributionPattern() {
    }

    /** LB 文件内的局部 {@code data class Band(val top: Double, val mean: Double, val std: Double)} */
    private record Band(double top, double mean, double std) {
    }

    @Override
    public void fill(int[] clickArray, IntRange cps, Clicker clicker) {
        Band[] frequencyBands = new Band[]{
                new Band(10.0 / 110.0, 179.5242718446602, 20.416937885616676),
                new Band(0.0, 87.88, 13.420088130563776)
        };

        double t = 0.0;

        while (true) {
            double v = Clicker.RNG.nextDouble();

            // LB: frequencyBands.first { v >= it.top }（末项 top = 0.0，必定命中）
            Band band = null;
            for (Band candidate : frequencyBands) {
                if (v >= candidate.top()) {
                    band = candidate;
                    break;
                }
            }

            // LB: RNG.nextGaussian(mean, std)；java.util.Random 继承 RandomGenerator.nextGaussian(mean, stddev)
            //     = mean + stddev * computeNextGaussian(this)（即 java.util.Random 自己的带缓存高斯）
            t += Clicker.RNG.nextGaussian(band.mean(), band.std()) * 20.0 / 1000.0;

            // 一秒已过
            if (t > 20.0) {
                break;
            }

            // LB: clickArray[t.toInt()]++（toInt() = 向零截断）
            clickArray[(int) t]++;
        }
    }

}
