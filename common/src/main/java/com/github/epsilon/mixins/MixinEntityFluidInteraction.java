package com.github.epsilon.mixins;

import com.github.epsilon.interfaces.EntityFluidInteractionAccessor;
import net.minecraft.world.entity.EntityFluidInteraction;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

import java.util.Map;

/**
 * {@code EntityFluidInteraction.trackerByFluid} 的字段访问器。
 * 供 Scaffold 神桥 Polar 变体（LiquidBounce 的 {@code SimulatedPlayer} 移植）深拷贝流体状态使用。
 */
@Mixin(EntityFluidInteraction.class)
public abstract class MixinEntityFluidInteraction implements EntityFluidInteractionAccessor {

    @Override
    @Accessor("trackerByFluid")
    public abstract Map<?, ?> epsilon$getTrackerByFluid();

}
