package com.github.epsilon.mixins;

import com.github.epsilon.events.bus.EventBus;
import com.github.epsilon.events.impl.PlaceBlockEvent;
import com.github.epsilon.modules.impl.render.NoGhostBlocks;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.context.BlockPlaceContext;
import net.minecraft.world.level.block.state.BlockState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(BlockItem.class)
public abstract class MixinBlockItem {

    @Inject(method = "placeBlock(Lnet/minecraft/world/item/context/BlockPlaceContext;Lnet/minecraft/world/level/block/state/BlockState;)Z", at = @At("HEAD"), cancellable = true)
    private void onPlace(BlockPlaceContext context, BlockState placementState, CallbackInfoReturnable<Boolean> cir) {
        if (!context.getLevel().isClientSide()) return;

        // 幽灵方块拦截直接查询模块：原先用 @ModifyVariable 改错 local（placedState 是**世界状态**
        // 而不是放置状态），会替换成可能为 null 的 getPlacementState 并让后续更新逻辑走错分支。
        NoGhostBlocks noGhostBlocks = NoGhostBlocks.INSTANCE;
        if (noGhostBlocks.isEnabled() && noGhostBlocks.placing.getValue()) {
            cir.setReturnValue(true);
            return;
        }

        if (EventBus.INSTANCE.post(new PlaceBlockEvent(context.getClickedPos(), placementState.getBlock())).isCancelled()) {
            cir.setReturnValue(true);
        }
    }

}
