package com.github.epsilon.mixins;

import com.github.epsilon.managers.ShaderManager;
import com.github.epsilon.modules.impl.render.CameraClip;
import com.github.epsilon.modules.impl.render.FreeCamera;
import com.github.epsilon.modules.impl.render.Shaders;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.renderpearl.api.textures.GpuTextureView;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.FirstPersonHandsAndItemsRenderer;
import net.minecraft.client.renderer.GameRenderer;
import net.minecraft.client.renderer.GlobalSettingsUniform;
import net.minecraft.client.renderer.feature.FeatureRenderDispatcher;
import net.minecraft.client.renderer.item.ItemModelResolver;
import net.minecraft.client.renderer.state.level.CameraRenderState;
import net.minecraft.client.renderer.state.OptionsRenderState;
import net.minecraft.client.renderer.state.level.PlayerRenderState;
import net.minecraft.client.resources.model.ModelManager;
import net.minecraft.world.phys.Vec3;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(GameRenderer.class)
public class MixinGameRenderer {

    @Final
    @Shadow
    private Minecraft minecraft;

    @Final
    @Shadow
    private GlobalSettingsUniform globalSettingsUniform;

    @Inject(method = "<init>", at = @At("RETURN"))
    private void initializeGlobalSettingsUniform(Minecraft minecraft, FirstPersonHandsAndItemsRenderer firstPersonHandsAndItemsRenderer, ModelManager modelManager, ItemModelResolver itemModelResolver, CallbackInfo ci) {
        this.globalSettingsUniform.update(
                minecraft.getWindow().getWidth(),
                minecraft.getWindow().getHeight(),
                0.0,
                0L,
                0.0F,
                0,
                Vec3.ZERO,
                false
        );
    }

    /**
     * 在关闭手部渲染帧前把手部描边渲染进手部目标。
     *
     * <p>26.3 的手部渲染把 {@code PreparedFrame} 与 {@code RenderPass} 放在同一个 try-with-resources 中，
     * 关闭顺序为先 RenderPass 后帧。{@code renderAllFeatures} 不包含描边阶段，而描边需要自己的 RenderPass，
     * 此时原版 RenderPass 尚未关闭，{@code FrontendCommandEncoder} 会抛出
     * “Close the existing render pass before creating a new one!”，因此必须挪到帧关闭前执行。
     * {@code ordinal = 0} 只命中正常返回路径的关闭调用，异常展开路径上的关闭不应再触发渲染。
     */
    @WrapOperation(method = "renderItemInHand", at = @At(value = "INVOKE", target = "Lnet/minecraft/client/renderer/feature/FeatureRenderDispatcher$PreparedFrame;close()V", ordinal = 0))
    private void renderHandOutlineBeforeFrameClose(FeatureRenderDispatcher.PreparedFrame frame, Operation<Void> original) {
        Shaders shaders = Shaders.INSTANCE;
        if (shaders.isEnabled() && shaders.hands.getValue()) {
            try {
                ShaderManager.INSTANCE.renderHandOutline(frame);
            } finally {
                ShaderManager.INSTANCE.endHandOutlineCapture();
            }
        }
        original.call(frame);
    }

    /**
     * 处理胸箱与手部描边。
     *
     * <p>26.3 的手部与屏幕特效渲染结束后，渲染调度器不再持有帧，才能为模块自己的描边提交缓存准备帧。
     */
    @Inject(method = "render3dHud", at = @At("RETURN"))
    private void processShadersOutline(CameraRenderState cameraState, PlayerRenderState playerState, OptionsRenderState optionsState, boolean consistentDepthRequired, CallbackInfo ci) {
        Shaders shaders = Shaders.INSTANCE;
        if (shaders.isEnabled()) {
            ShaderManager.INSTANCE.processChestOutlineTarget(minecraft.gameRenderer.mainRenderTarget());
            ShaderManager.INSTANCE.processHandOutlineTarget(minecraft.gameRenderer.mainRenderTarget());
        }
    }

    @Inject(method = "renderItemInHand", at = @At("HEAD"), cancellable = true)
    private void renderItemInHand(CameraRenderState cameraState, PlayerRenderState playerState, GpuTextureView depthTextureView, CallbackInfo ci) {
        if (!FreeCamera.INSTANCE.renderHands()) {
            ci.cancel();
        }
    }

    @WrapOperation(method = "bobView", at = @At(value = "INVOKE", target = "Lcom/mojang/blaze3d/vertex/PoseStack;translate(FFF)V"))
    private void hookBobView(PoseStack instance, float xo, float yo, float zo, Operation<Void> original) {
        CameraClip cameraClip = CameraClip.INSTANCE;
        if (!cameraClip.isEnabled() || !cameraClip.betterBobView.getValue()) original.call(instance, xo, yo, zo);
    }

}
