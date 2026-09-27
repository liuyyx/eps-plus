package com.github.epsilon.interfaces;

import java.util.Map;

/**
 * {@code EntityFluidInteraction} 的访问器（{@code trackerByFluid} 是 private final）。
 *
 * <p>Scaffold 神桥 Polar 变体需要把玩家当前的流体交互 tracker 深拷贝进
 * {@code SimulatedPlayer}（LiquidBounce 的 {@code SimulatedPlayer.deepCopy()}）。
 * LB 自己也是靠 {@code MixinEntityFluidInteractionAccessor} 访问，这里沿用同一做法。</p>
 *
 * <p>返回 {@code Map<?, ?>} 而不是具体泛型：Fabric 与 NeoForge 的
 * {@code EntityFluidInteraction.trackerByFluid} 键类型不同（{@code TagKey<Fluid>} vs {@code FluidType}），
 * 用通配符可以同时编译两个加载器。</p>
 */
public interface EntityFluidInteractionAccessor {

    Map<?, ?> epsilon$getTrackerByFluid();

}
