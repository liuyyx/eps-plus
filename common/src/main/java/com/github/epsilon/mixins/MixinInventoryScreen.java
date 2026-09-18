package com.github.epsilon.mixins;

import com.github.epsilon.modules.impl.render.GameAnimation;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.inventory.InventoryScreen;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.ModifyArgs;
import org.spongepowered.asm.mixin.injection.invoke.arg.Args;

@Mixin(InventoryScreen.class)
public class MixinInventoryScreen {

    /**
     * 缩放库存界面中的实体预览。
     *
     * <p>26.3 的原版把实体渲染合并进 {@code extractEntityInInventoryFollowsMouse}，而 NeoForge 补丁为兼容
     * 保留了旧结构：{@code extractEntityInInventoryFollowsMouse} 只是转发，真正的
     * {@code GuiGraphicsExtractor#entity} 调用在 {@code renderEntityInInventoryFollowsAngle} 里。
     * 两个注入点各自只在对应加载器中命中，因此都使用 {@code require = 0}（两边的字节码均已核对）。
     */
    @ModifyArgs(method = "extractEntityInInventoryFollowsMouse", at = @At(value = "INVOKE", target = "Lnet/minecraft/client/gui/GuiGraphicsExtractor;entity(Lnet/minecraft/client/renderer/entity/state/EntityRenderState;FLorg/joml/Vector3fc;Lorg/joml/Quaternionfc;Lorg/joml/Quaternionfc;IIII)V"), require = 0)
    private static void animateInventoryEntity(Args args) {
        epsilon$scaleInventoryEntity(args);
    }

    @ModifyArgs(method = "renderEntityInInventoryFollowsAngle", at = @At(value = "INVOKE", target = "Lnet/minecraft/client/gui/GuiGraphicsExtractor;entity(Lnet/minecraft/client/renderer/entity/state/EntityRenderState;FLorg/joml/Vector3fc;Lorg/joml/Quaternionfc;Lorg/joml/Quaternionfc;IIII)V"), require = 0)
    private static void animateInventoryEntityNeoForge(Args args) {
        epsilon$scaleInventoryEntity(args);
    }

    @Unique
    private static void epsilon$scaleInventoryEntity(Args args) {
        float animationScale = GameAnimation.INSTANCE.getCurrentInventoryScale();
        if (animationScale >= 1.0f) return;

        float centerX = Minecraft.getInstance().getWindow().getGuiScaledWidth() / 2.0f;
        float centerY = Minecraft.getInstance().getWindow().getGuiScaledHeight() / 2.0f;

        args.set(1, (Float) args.get(1) * animationScale);
        args.set(5, epsilon$scaleCoordinate(args.get(5), centerX, animationScale));
        args.set(6, epsilon$scaleCoordinate(args.get(6), centerY, animationScale));
        args.set(7, epsilon$scaleCoordinate(args.get(7), centerX, animationScale));
        args.set(8, epsilon$scaleCoordinate(args.get(8), centerY, animationScale));
    }

    @Unique
    private static int epsilon$scaleCoordinate(int coordinate, float center, float scale) {
        return Math.round(center + (coordinate - center) * scale);
    }

}
