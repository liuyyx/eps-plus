package com.github.epsilon.modules.impl.movement.scaffold.elowen.place;

import com.github.epsilon.events.impl.StartUseItemEvent;
import com.github.epsilon.modules.impl.movement.scaffold.elowen.ElowenScaffold;
import com.github.epsilon.utils.rotation.Rot2f;
import net.minecraft.core.BlockPos;

import static com.github.epsilon.Constants.mc;

/**
 * Keep Y 放置模式（elowen {@code com.elowen.o.Y.Z.l.d}）。
 *
 * <p>elowen 原文的 {@code a.getConfusionFlag()} 恒为 {@code null}（§判定基线），对应分支整段删除。</p>
 *
 * <p>[elowen o/Y/Z/l/d.java:40-73]</p>
 */
public class KeepYMode implements PlaceModeHandler {

    @Override
    public void onEnable() {
    }

    @Override
    public void onDisable() {
    }

    @Override
    public void onTick(ElowenScaffold engine, BlockPos target) {
        if (mc.player == null) {
            return;
        }
        boolean moving = engine.isMoving();
        if (!moving) {
            moving = engine.jumpKeyPhysicallyDown();
        }
        engine.requestJump(moving);
        Rot2f targetRotation = engine.getTargetRotation();
        engine.currentRotation = engine.rotationHandler().apply(engine, engine.currentRotation, targetRotation, engine.rotationSpeed());
        engine.placeOrGuard(target);
    }

    @Override
    public void onUseItem(ElowenScaffold engine, BlockPos target, StartUseItemEvent event) {
        engine.onUseItemPlace(target);
    }
}
