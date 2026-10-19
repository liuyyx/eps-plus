package com.github.epsilon.modules.impl.movement;

import com.github.epsilon.events.bus.EventHandler;
import com.github.epsilon.events.bus.EventPriority;
import com.github.epsilon.events.impl.KeyboardInputEvent;
import com.github.epsilon.modules.Category;
import com.github.epsilon.modules.Module;
import com.github.epsilon.modules.impl.player.InvManager;

public class AutoSprint extends Module {

    public static final AutoSprint INSTANCE = new AutoSprint();

    private AutoSprint() {
        super("Auto Sprint", Category.MOVEMENT);
    }

    @EventHandler(priority = EventPriority.LOWEST)
    private void onKeyboardInput(KeyboardInputEvent event) {
        if (nullCheck()) return;
        boolean sprintTransition = InvManager.INSTANCE.isEnabled() && InvManager.INSTANCE.isSprintTransitionPending();
        if (sprintTransition) return;

        // 每刻瞬时输入：写 KeyMapping 会粘住玩家自己的疾跑键（松开无效、其它模块误读）。
        if (mc.gui.screen() == null) {
            event.setSprint(true);
        }
    }

}
