package com.github.epsilon.mixins;

import com.github.epsilon.interfaces.EntityRenderStateAccessor;
import com.github.epsilon.modules.impl.render.YsmModel;
import com.llamalad7.mixinextras.injector.ModifyExpressionValue;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.mojang.blaze3d.vertex.PoseStack;
import net.minecraft.client.model.Model;
import net.minecraft.client.renderer.SubmitNodeCollector;
import net.minecraft.client.renderer.entity.LivingEntityRenderer;
import net.minecraft.client.renderer.entity.state.LivingEntityRenderState;
import net.minecraft.client.renderer.feature.ModelFeatureRenderer;
import net.minecraft.client.renderer.rendertype.RenderType;
import net.minecraft.client.renderer.state.level.CameraRenderState;
import net.minecraft.client.renderer.texture.TextureAtlasSprite;
import net.minecraft.world.entity.Entity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * 用 YSM 几何替换本地玩家的原版身体。
 *
 * <p>HEAD 处只记录「当前世界矩阵 + 实体」，真正的几何在 {@code submitModel} 调用点用
 * {@code 当前矩阵⁻¹ × HEAD 矩阵 × YSM 位姿链} 相对矩阵提交，这样无需复刻原版的变换链。
 * 原版身体与层被跳过，名字牌/影子/拴绳仍走 {@code super.submit} 保持原样。</p>
 */
@Mixin(LivingEntityRenderer.class)
public abstract class MixinYsmLivingEntityRenderer {

    private static final String SUBMIT =
            "submit(Lnet/minecraft/client/renderer/entity/state/LivingEntityRenderState;"
                    + "Lcom/mojang/blaze3d/vertex/PoseStack;"
                    + "Lnet/minecraft/client/renderer/SubmitNodeCollector;"
                    + "Lnet/minecraft/client/renderer/state/level/CameraRenderState;)V";

    @Inject(method = SUBMIT, at = @At("HEAD"))
    private void ysm$beginBody(LivingEntityRenderState state, PoseStack poseStack,
                               SubmitNodeCollector collector, CameraRenderState camera, CallbackInfo ci) {
        Entity entity = state instanceof EntityRenderStateAccessor accessor ? accessor.epsilon$getEntity() : null;
        YsmModel.INSTANCE.beginThirdPerson(poseStack, state, entity);
    }

    @WrapOperation(
            method = SUBMIT,
            at = @At(value = "INVOKE", target = "Lnet/minecraft/client/renderer/SubmitNodeCollector;"
                    + "submitModel(Lnet/minecraft/client/model/Model;Ljava/lang/Object;"
                    + "Lcom/mojang/blaze3d/vertex/PoseStack;"
                    + "Lnet/minecraft/client/renderer/rendertype/RenderType;III"
                    + "Lnet/minecraft/client/renderer/texture/TextureAtlasSprite;I"
                    + "Lnet/minecraft/client/renderer/feature/ModelFeatureRenderer$CrumblingOverlay;)V")
    )
    private void ysm$replaceBody(SubmitNodeCollector receiver, Model<?> model, Object state,
                                 PoseStack poseStack, RenderType renderType,
                                 int lightCoords, int overlayCoords, int tintedColor,
                                 TextureAtlasSprite sprite, int outlineColor,
                                 ModelFeatureRenderer.CrumblingOverlay crumbling,
                                 Operation<Void> original) {
        if (YsmModel.INSTANCE.submitThirdPersonBody(poseStack, receiver, model)) {
            return;
        }
        original.call(receiver, model, state, poseStack, renderType, lightCoords, overlayCoords,
                tintedColor, sprite, outlineColor, crumbling);
    }

    @ModifyExpressionValue(
            method = SUBMIT,
            at = @At(value = "INVOKE", target = "Lnet/minecraft/client/renderer/entity/LivingEntityRenderer;"
                    + "shouldRenderLayers(Lnet/minecraft/client/renderer/entity/state/LivingEntityRenderState;)Z")
    )
    private boolean ysm$skipLayers(boolean original) {
        return original && !YsmModel.INSTANCE.isHandlingAvatar();
    }

    @Inject(method = SUBMIT, at = @At("RETURN"))
    private void ysm$endBody(LivingEntityRenderState state, PoseStack poseStack,
                             SubmitNodeCollector collector, CameraRenderState camera, CallbackInfo ci) {
        YsmModel.INSTANCE.endThirdPerson();
    }
}
