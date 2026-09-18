package com.github.epsilon.mixins;

import com.github.epsilon.modules.impl.render.HandView;
import net.minecraft.client.player.FirstPersonHandsAndItems;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.world.item.ItemStack;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * 第一人称手部状态扩展。
 *
 * <p>26.3 把物品切换动画的计时从渲染器移到了 {@link FirstPersonHandsAndItems}，
 * 因此 HandView 的“禁用切换动画”需要在状态 tick 结束后把高度与可见物品锁到目标值。
 */
@Mixin(FirstPersonHandsAndItems.class)
public class MixinFirstPersonHandsAndItems {

    @Shadow
    private float mainHandHeight;

    @Shadow
    private float offHandHeight;

    @Shadow
    private ItemStack mainHandItem;

    @Shadow
    private ItemStack offHandItem;

    @Inject(method = "tick", at = @At("RETURN"))
    private void hideHotbarSwitchAnimation(LocalPlayer player, CallbackInfo ci) {
        HandView handView = HandView.INSTANCE;
        if (!handView.isEnabled()) {
            return;
        }

        if (handView.disableSwapMain.getValue()) {
            this.mainHandHeight = 1.0F;
            this.mainHandItem = player.getMainHandItem();
        }

        if (handView.disableSwapOff.getValue()) {
            this.offHandHeight = 1.0F;
            this.offHandItem = player.getOffhandItem();
        }
    }

}
