package com.github.epsilon.mixins;

import com.github.epsilon.modules.impl.render.Filter;
import com.github.epsilon.modules.impl.render.Fullbright;
import com.github.epsilon.modules.impl.render.Xray;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.textures.GpuTexture;
import net.minecraft.client.renderer.Lightmap;
import net.minecraft.client.renderer.state.LightmapRenderState;
import org.joml.Vector4f;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(Lightmap.class)
public class MixinLightmap {

    @Final
    @Shadow
    private GpuTexture texture;

    @Inject(method = "render", at = @At("HEAD"), cancellable = true)
    private void onRender(LightmapRenderState renderState, CallbackInfo ci) {
        if (Xray.INSTANCE.isEnabled() || Fullbright.INSTANCE.isGammaMode() || Filter.INSTANCE.isLightMapMode()) {
            Vector4f color = Filter.INSTANCE.isLightMapMode()
                    ? toVector(Filter.INSTANCE.getLightMapColor())
                    : new Vector4f(1f, 1f, 1f, 1f);
            RenderSystem.getDevice().createCommandEncoder().clearColorTexture(this.texture, color);
            ci.cancel();
        }
    }

    private static Vector4f toVector(java.awt.Color color) {
        return new Vector4f(
                color.getRed() / 255.0f,
                color.getGreen() / 255.0f,
                color.getBlue() / 255.0f,
                color.getAlpha() / 255.0f
        );
    }

}
