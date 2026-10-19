package com.github.epsilon.modules.impl.render;

import com.github.epsilon.events.bus.EventHandler;
import com.github.epsilon.events.impl.DestroyBlockEvent;
import com.github.epsilon.events.impl.PlaceBlockEvent;
import com.github.epsilon.modules.Category;
import com.github.epsilon.modules.Module;
import com.github.epsilon.settings.impl.BoolSetting;
import net.minecraft.world.level.block.state.BlockState;

public class NoGhostBlocks extends Module {

    public static final NoGhostBlocks INSTANCE = new NoGhostBlocks();

    private NoGhostBlocks() {
        super("No Ghost Blocks", Category.RENDER);
    }

    private final BoolSetting breaking = boolSetting("Breaking", true);
    public final BoolSetting placing = boolSetting("Placing", true);

    @EventHandler
    private void onBreakBlock(DestroyBlockEvent event) {
        if (mc.isLocalServer() || !breaking.getValue()) return;

        event.cancel();

        BlockState blockState = mc.level.getBlockState(event.getBlockPos());
        blockState.getBlock().playerWillDestroy(mc.level, event.getBlockPos(), blockState, mc.player);
    }

    // 幽灵方块拦截不通过取消 PlaceBlockEvent 实现：
    // EventBus 一旦看到 isCancelled() 就中断后续派发，会吞掉 Scaffold 等模块的 PlaceBlockEvent 观察者。
    // 真正的拦截点在 MixinBlockItem.modifyState（直接查询本模块），这里不再 cancel。

}
