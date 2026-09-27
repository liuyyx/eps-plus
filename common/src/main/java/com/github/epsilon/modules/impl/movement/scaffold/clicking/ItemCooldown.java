/*
 * 移植自 LiquidBounce (https://github.com/CCBlueX/LiquidBounce)，GPL-3.0-or-later
 * 源文件：src/main/kotlin/net/ccbluex/liquidbounce/utils/clicking/ItemCooldown.kt
 *
 * [适配] Epsilon 无 ValueGroup/Value 体系：LB 的设置项改为字段 + setter（阶段 7 接线）。
 * [适配] 需要 epsilon.accesswidener 追加
 * `accessible field net/minecraft/world/entity/LivingEntity attackStrengthTicker I`
 * （LB 的 liquidbounce.accesswidener:66 同款）。
 */
package com.github.epsilon.modules.impl.movement.scaffold.clicking;

import com.github.epsilon.Constants;

import java.util.concurrent.ThreadLocalRandom;

/**
 * 物品（攻击）冷却。
 * <p>LB: {@code open class ItemCooldown : ValueGroup("ItemCooldown", aliases = listOf("Cooldown"))}
 */
public class ItemCooldown {

    /**
     * LB: {@code private val minimumCooldown by floatRange("Minimum", 1.0f..1.0f, 0.0f..2.0f)}（闭区间）
     * [待接线] 阶段 7 用 Epsilon 的 {@code Minimum} 设置驱动（{@link #setMinimumCooldown}）。
     */
    private float minimumStart = 1.0f;

    private float minimumEndInclusive = 1.0f;

    /** LB: {@code private var nextCooldown = minimumCooldown.random()} */
    private float nextCooldown = randomMinimum();

    /**
     * LB: {@code ClosedFloatingPointRange<Float>.random()}（utils/kotlin/ArrayExtensions.kt:86-88）：
     * <pre>
     * return if (start >= endInclusive) start else ThreadLocalRandom.current().nextFloat(start, endInclusive)
     * </pre>
     */
    private float randomMinimum() {
        float start = minimumStart;
        float endInclusive = minimumEndInclusive;
        return start >= endInclusive ? start : ThreadLocalRandom.current().nextFloat(start, endInclusive);
    }

    /** [待接线] 阶段 7 写入 LB 的 {@code Minimum}（floatRange，边界 0.0f..2.0f）。 */
    public void setMinimumCooldown(float start, float endInclusive) {
        this.minimumStart = start;
        this.minimumEndInclusive = endInclusive;
    }

    public float getMinimumStart() {
        return minimumStart;
    }

    public float getMinimumEndInclusive() {
        return minimumEndInclusive;
    }

    /**
     * LB: {@code open fun isCooldownPassed(ticks: Int = 0): Boolean}（Kotlin 默认参数 → Java 重载）
     */
    public boolean isCooldownPassed() {
        return isCooldownPassed(0);
    }

    public boolean isCooldownPassed(int ticks) {
        return cooldownProgress(ticks) >= nextCooldown;
    }

    /**
     * 计算当前冷却进度。
     * <p>LB 原文：This can be out of percentage range [0, 1] to allow for higher minimum cooldowns.
     * 参见 {@code Player.getAttackStrengthScale}。
     * <p>LB: {@code fun cooldownProgress(baseTime: Int = 0)}（Kotlin 默认参数 → Java 重载）
     */
    public float cooldownProgress() {
        return cooldownProgress(0);
    }

    public float cooldownProgress(int baseTime) {
        return (float) (Constants.mc.player.attackStrengthTicker + baseTime) / Constants.mc.player.getCurrentItemAttackStrengthDelay();
    }

    /**
     * 按用户设置的区间生成一个新的冷却。
     * LB: {@code fun newCooldown()}
     */
    public void newCooldown() {
        nextCooldown = randomMinimum();
    }

}
