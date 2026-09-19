package com.github.epsilon.neoforge.mixins.iris;

import com.github.epsilon.managers.ShaderManager;
import com.github.epsilon.modules.impl.render.Shaders;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import net.minecraft.client.renderer.feature.FeatureRenderDispatcher;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.injection.At;

@Pseudo
@Mixin(targets = "net.irisshaders.iris.pathways.HandRenderer", remap = false)
public abstract class MixinIrisHandRenderer {

    /**
     * Iris 的手部渲染同样在 {@code renderAllFeatures} 之后才关闭帧，而 Frame 关闭紧跟在 RenderPass 之后。
     * 描边需要独立 RenderPass，必须在原版 RenderPass 关闭后、帧关闭前执行，否则会触发
     * “Close the existing render pass before creating a new one!”。
     */
    @WrapOperation(method = {"renderSolid", "renderTranslucent"}, at = @At(value = "INVOKE", target = "Lnet/minecraft/client/renderer/feature/FeatureRenderDispatcher$PreparedFrame;close()V", ordinal = 0), remap = true)
    private void epsilon$renderOutlineBeforeFrameClose(FeatureRenderDispatcher.PreparedFrame frame, Operation<Void> original) {
        if (Shaders.INSTANCE.isEnabled() && Shaders.INSTANCE.hands.getValue()) {
            try {
                ShaderManager.INSTANCE.renderHandOutline(frame);
            } finally {
                ShaderManager.INSTANCE.endHandOutlineCapture();
            }
        }
        original.call(frame);
    }

}
