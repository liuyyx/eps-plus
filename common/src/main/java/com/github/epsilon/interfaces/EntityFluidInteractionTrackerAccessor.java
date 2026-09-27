package com.github.epsilon.interfaces;

import net.minecraft.world.phys.Vec3;

/**
 * {@code EntityFluidInteraction$Tracker} 的访问器（该类及其字段在 26.2 都是包私有/私有）。
 *
 * <p>Scaffold 神桥 Polar 变体需要把玩家当前的流体交互 tracker 深拷贝进
 * {@code SimulatedPlayer}（LiquidBounce 的 {@code SimulatedPlayer.deepCopy()}）。
 * LB 自己也是靠 {@code MixinEntityFluidInteractionTrackerAccessor} 访问，
 * 这里沿用同一做法。</p>
 */
public interface EntityFluidInteractionTrackerAccessor {

    double epsilon$getTrackerHeight();

    void epsilon$setTrackerHeight(double height);

    boolean epsilon$isEyesInside();

    void epsilon$setEyesInside(boolean eyesInside);

    Vec3 epsilon$getAccumulatedCurrent();

    void epsilon$setAccumulatedCurrent(Vec3 accumulatedCurrent);

    int epsilon$getCurrentCount();

    void epsilon$setCurrentCount(int currentCount);

}
