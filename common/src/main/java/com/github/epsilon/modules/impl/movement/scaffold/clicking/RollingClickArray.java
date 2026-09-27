/*
 * 移植自 LiquidBounce (https://github.com/CCBlueX/LiquidBounce)，GPL-3.0-or-later
 * 源文件：src/main/kotlin/net/ccbluex/liquidbounce/utils/clicking/RollingClickArray.kt
 */
package com.github.epsilon.modules.impl.movement.scaffold.clicking;

import java.util.Arrays;

/**
 * 维护两倍 cycle 长度的循环缓冲区，并在到达中点时重新生成后半段。
 * <p>LB 原文：A circular buffer that maintains double the cycle length and regenerates the second half
 * when reaching the midpoint
 */
public final class RollingClickArray {

    /** LB: {@code private val cycleLength: Int} */
    private final int cycleLength;

    /** LB: {@code val iterations: Int} */
    public final int iterations;

    /** LB: {@code internal val array = IntArray(cycleLength * iterations)} */
    public final int[] array;

    /** LB: {@code var head = 0 private set} */
    private int head;

    public RollingClickArray(int cycleLength, int iterations) {
        this.cycleLength = cycleLength;
        this.iterations = iterations;
        this.array = new int[cycleLength * iterations];
    }

    /** LB: {@code private val size get() = array.size} */
    private int size() {
        return array.length;
    }

    public int getHead() {
        return head;
    }

    /**
     * 取相对当前 head 的偏移处的值。
     * LB: {@code fun get(relativeIndex: Int): Int}
     */
    public int get(int relativeIndex) {
        int actualIndex = (head + relativeIndex) % size();
        return array[actualIndex];
    }

    /**
     * 写相对当前 head 的偏移处的值。
     * LB: {@code fun set(relativeIndex: Int, value: Int)}
     */
    public void set(int relativeIndex, int value) {
        int actualIndex = (head + relativeIndex) % size();
        array[actualIndex] = value;
    }

    /**
     * LB: {@code fun advance(amount: Int = 1): Boolean}（Kotlin 默认参数 → Java 重载）
     */
    public boolean advance() {
        return advance(1);
    }

    /**
     * 前进 head 位置，并在到达中点时返回 true。
     * LB: {@code fun advance(amount: Int)}
     */
    public boolean advance(int amount) {
        head = (head + amount) % size();
        return head % cycleLength == 0;
    }

    /**
     * 清空数组。
     * LB: {@code array.fill(0)} → {@link Arrays#fill(int[], int)}
     */
    public void clear() {
        Arrays.fill(array, 0);
        head = 0;
    }

    /** LB: {@code fun push(cycleArray: IntArray)} */
    public void push(int[] cycleArray) {
        // LB: require(cycleArray.size == cycleLength) { "Array size must match cycle length" }
        if (cycleArray.length != cycleLength) {
            throw new IllegalArgumentException("Array size must match cycle length");
        }

        // LB: when (head) { 0 -> ...; cycleLength -> ...; else -> error(...) }
        if (head == 0) {
            System.arraycopy(cycleArray, 0, array, cycleLength, cycleLength);
        } else if (head == cycleLength) {
            System.arraycopy(cycleArray, 0, array, 0, cycleLength);
        } else {
            throw new IllegalStateException("Head must be at 0 or cycle length");
        }
    }

}
