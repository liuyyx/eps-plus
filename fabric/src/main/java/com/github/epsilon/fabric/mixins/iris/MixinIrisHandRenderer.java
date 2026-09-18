package com.github.epsilon.fabric.mixins.iris;

import com.github.epsilon.managers.ShaderManager;
import com.github.epsilon.modules.impl.render.Shaders;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.mojang.renderpearl.api.commands.RenderPass;
import net.minecraft.client.renderer.feature.FeatureRenderDispatcher;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.injection.At;

@Pseudo
@Mixin(targets = "net.irisshaders.iris.pathways.HandRenderer", remap = false)
public abstract class MixinIrisHandRenderer {

    @WrapOperation(method = {"renderSolid", "renderTranslucent"}, at = @At(value = "INVOKE", target = "Lnet/minecraft/client/renderer/feature/FeatureRenderDispatcher;renderAllFeatures(Lcom/mojang/renderpearl/api/commands/RenderPass;Lnet/minecraft/client/renderer/feature/FeatureRenderDispatcher$PreparedFrame;)V"), remap = true)
    private void epsilon$renderFeaturesWithOutline(RenderPass renderPass, FeatureRenderDispatcher.PreparedFrame frame, Operation<Void> original) {
        original.call(renderPass, frame);
        if (Shaders.INSTANCE.isEnabled() && Shaders.INSTANCE.hands.getValue()) {
            try {
                ShaderManager.INSTANCE.renderHandOutline(frame);
            } finally {
                ShaderManager.INSTANCE.endHandOutlineCapture();
            }
        }
    }

}
