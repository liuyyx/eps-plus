package com.github.epsilon.mixins;

import com.github.epsilon.modules.impl.render.YsmModel;
import com.mojang.blaze3d.vertex.PoseStack;
import net.minecraft.client.renderer.SubmitNodeCollector;
import net.minecraft.client.renderer.entity.player.AvatarRenderer;
import net.minecraft.resources.Identifier;
import net.minecraft.world.entity.HumanoidArm;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * 第一人称手臂替换。
 *
 * <p>{@code AvatarRenderer.renderRightHand/renderLeftHand} 是原版第一人称手臂（含手持地图）
 * 唯一的几何入口，注入 HEAD 并用 {@code cancellable} 取消即可整体换成 YSM 手臂几何。</p>
 */
@Mixin(AvatarRenderer.class)
public class MixinYsmAvatarRenderer {

    private static final String HAND =
            "(Lcom/mojang/blaze3d/vertex/PoseStack;"
                    + "Lnet/minecraft/client/renderer/SubmitNodeCollector;I"
                    + "Lnet/minecraft/resources/Identifier;Z)V";

    @Inject(method = "renderRightHand" + HAND, at = @At("HEAD"), cancellable = true)
    private void ysm$rightHand(PoseStack poseStack, SubmitNodeCollector collector, int lightCoords,
                               Identifier skin, boolean hasSleeve, CallbackInfo ci) {
        if (YsmModel.INSTANCE.submitFirstPersonArm(HumanoidArm.RIGHT, poseStack, collector, lightCoords)) {
            ci.cancel();
        }
    }

    @Inject(method = "renderLeftHand" + HAND, at = @At("HEAD"), cancellable = true)
    private void ysm$leftHand(PoseStack poseStack, SubmitNodeCollector collector, int lightCoords,
                              Identifier skin, boolean hasSleeve, CallbackInfo ci) {
        if (YsmModel.INSTANCE.submitFirstPersonArm(HumanoidArm.LEFT, poseStack, collector, lightCoords)) {
            ci.cancel();
        }
    }
}
