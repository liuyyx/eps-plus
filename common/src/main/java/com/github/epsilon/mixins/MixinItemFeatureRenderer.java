package com.github.epsilon.mixins;

import com.github.epsilon.modules.impl.render.NoRender;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import net.minecraft.client.renderer.feature.ItemFeatureRenderer;
import net.minecraft.client.renderer.item.ItemStackRenderState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

@Mixin(ItemFeatureRenderer.class)
public abstract class MixinItemFeatureRenderer {

    /**
     * 禁用附魔光效。
     *
     * <p>26.3 把光效提交合并进 {@code prepareMainSubmit}，直接按 {@code foilType} 选择附魔渲染类型，
     * 因此改为读取时回退到 {@code NONE}，等价于旧版取消 {@code prepareFoilSubmit}。
     */
    @WrapOperation(method = "prepareMainSubmit", at = @At(value = "INVOKE", target = "Lnet/minecraft/client/renderer/feature/ItemFeatureRenderer$Submit;foilType()Lnet/minecraft/client/renderer/item/ItemStackRenderState$FoilType;"))
    private ItemStackRenderState.FoilType cancelEnchantGlint(ItemFeatureRenderer.Submit submit, Operation<ItemStackRenderState.FoilType> original) {
        if (NoRender.INSTANCE.isEnabled() && NoRender.INSTANCE.enchantGlint.getValue()) {
            return ItemStackRenderState.FoilType.NONE;
        }
        return original.call(submit);
    }

}
