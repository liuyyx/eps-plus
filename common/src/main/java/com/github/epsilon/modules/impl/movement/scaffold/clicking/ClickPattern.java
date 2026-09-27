/*
 * 移植自 LiquidBounce (https://github.com/CCBlueX/LiquidBounce)，GPL-3.0-or-later
 * 源文件：src/main/kotlin/net/ccbluex/liquidbounce/utils/clicking/pattern/ClickPattern.kt
 */
package com.github.epsilon.modules.impl.movement.scaffold.clicking;

import java.util.NoSuchElementException;
import java.util.concurrent.ThreadLocalRandom;

/**
 * LB: {@code interface ClickPattern}（utils/clicking/pattern/ClickPattern.kt:23-25）
 */
public interface ClickPattern {

    /**
     * LB: {@code fun fill(clickArray: IntArray, cps: IntRange, clicker: Clicker<*>)}
     */
    void fill(int[] clickArray, IntRange cps, Clicker clicker);

    /**
     * [适配] {@code kotlin.ranges.IntRange} 在 Epsilon（纯 Java，无 kotlin-stdlib）没有对应类型。
     * 这里用 record 承载 LB 用到的全部语义：闭区间两端 + {@code random()}。
     * 字段名沿用 Kotlin（first / last），区间为闭区间（含 first 与 last）。
     */
    record IntRange(int first, int last) {

        /**
         * LB: {@code IntRange.random()}（kotlin.ranges）
         */
        public int random() {
            return randomInt(this);
        }
    }

    /**
     * LB: {@code IntRange.random()} → {@code Random.nextInt(range)}（kotlin.random.RandomKt）：
     * <pre>
     * if (range.isEmpty()) throw NoSuchElementException("Cannot get random in empty range: $range")
     * else if (range.last &lt; Int.MAX_VALUE) nextInt(range.first, range.last + 1)
     * else if (range.first &gt; Int.MIN_VALUE) nextInt(range.first - 1, range.last) + 1
     * else nextInt()
     * </pre>
     * [适配] {@code kotlin.random.Random.Default} 在 JVM 由 {@code kotlin.random.jdk8.PlatformThreadLocalRandom}
     * 实现，其 {@code nextInt(from, until)} 直接委托
     * {@code java.util.concurrent.ThreadLocalRandom.current().nextInt(from, until)}（已核对 kotlin-stdlib
     * 2.4.10 字节码），因此此处用同一 API，随机算法与调用顺序与 LB 完全一致。
     */
    static int randomInt(IntRange range) {
        int first = range.first();
        int last = range.last();

        if (first > last) {
            // Kotlin: IntRange.isEmpty() 分支（错误信息格式同 IntRange.toString()）
            throw new NoSuchElementException("Cannot get random in empty range: " + first + ".." + last);
        } else if (last < Integer.MAX_VALUE) {
            return ThreadLocalRandom.current().nextInt(first, last + 1);
        } else if (first > Integer.MIN_VALUE) {
            return ThreadLocalRandom.current().nextInt(first - 1, last) + 1;
        } else {
            return ThreadLocalRandom.current().nextInt();
        }
    }

    /**
     * LB: {@code Collection.random()}（kotlin.collections）
     * <pre>
     * if (isEmpty()) throw NoSuchElementException("Collection is empty.")
     * return elementAt(Random.nextInt(size))
     * </pre>
     * 返回随机下标；{@code Random.nextInt(until)} 同样委托
     * {@code ThreadLocalRandom.current().nextInt(bound)}。
     */
    static int randomIndex(int size) {
        if (size == 0) {
            throw new NoSuchElementException("Collection is empty.");
        }
        return ThreadLocalRandom.current().nextInt(size);
    }

    /**
     * LB: {@code IntArray.sum()}（kotlin.collections；int 累加）
     */
    static int sum(int[] array) {
        int sum = 0;
        for (int value : array) {
            sum += value;
        }
        return sum;
    }

}
