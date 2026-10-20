package com.github.epsilon.mixins;

import com.github.epsilon.interfaces.KeyMappingAccessor;
import net.minecraft.client.KeyMapping;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

@Mixin(KeyMapping.class)
public abstract class MixinKeyMapping implements KeyMappingAccessor {

    @Override
    @Accessor("isDown")
    public abstract void epsilon$setDownState(boolean down);

}
