package com.github.epsilon.mixins;

import com.github.epsilon.interfaces.EntityFluidInteractionTrackerAccessor;
import net.minecraft.world.phys.Vec3;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/**
 * {@code EntityFluidInteraction$Tracker} 的字段访问器。
 * 供 Scaffold 神桥 Polar 变体（LiquidBounce 的 {@code SimulatedPlayer} 移植）深拷贝流体状态使用。
 * <p>
 * 目标类在 26.2 是包私有，故用 {@code targets} 字符串形式，避免编译期直接引用该类型。
 */
@Mixin(targets = "net.minecraft.world.entity.EntityFluidInteraction$Tracker")
public abstract class MixinEntityFluidInteractionTracker implements EntityFluidInteractionTrackerAccessor {

    @Override
    @Accessor("height")
    public abstract double epsilon$getTrackerHeight();

    @Override
    @Accessor("height")
    public abstract void epsilon$setTrackerHeight(double height);

    @Override
    @Accessor("eyesInside")
    public abstract boolean epsilon$isEyesInside();

    @Override
    @Accessor("eyesInside")
    public abstract void epsilon$setEyesInside(boolean eyesInside);

    @Override
    @Accessor("accumulatedCurrent")
    public abstract Vec3 epsilon$getAccumulatedCurrent();

    @Override
    @Accessor("accumulatedCurrent")
    public abstract void epsilon$setAccumulatedCurrent(Vec3 accumulatedCurrent);

    @Override
    @Accessor("currentCount")
    public abstract int epsilon$getCurrentCount();

    @Override
    @Accessor("currentCount")
    public abstract void epsilon$setCurrentCount(int currentCount);

}
