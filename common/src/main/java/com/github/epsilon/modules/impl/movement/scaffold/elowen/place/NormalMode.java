package com.github.epsilon.modules.impl.movement.scaffold.elowen.place;

import com.github.epsilon.events.impl.StartUseItemEvent;
import com.github.epsilon.modules.impl.movement.scaffold.elowen.ElowenScaffold;
import com.github.epsilon.utils.rotation.Rot2f;
import net.minecraft.core.BlockPos;

import static com.github.epsilon.Constants.mc;

/**
 * Normal 放置模式（elowen {@code com.elowen.o.y.z.l.d_0}）。
 *
 * <p>elowen 原文的 {@code a.getConfusionFlag()} 恒为 {@code null}（§判定基线），
 * 对应分支整段删除；{@code Q.x()} 为 true 的毒丸调用删除。</p>
 *
 * <p>[elowen o/y/z/l/d_0.java:36-91]</p>
 */
public class NormalMode implements PlaceModeHandler {

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
        boolean onGround = engine.eagle();
        if (onGround) {
            mc.options.keyShift.setDown(mc.player.onGround() && engine.noCollisionBelow(0.3f));
        }
        onGround = mc.player.onGround();
        if (onGround
                && engine.isMoving()
                && engine.jumpKeyPhysicallyDown()
                && engine.jumpSprint()
                && !mc.options.keyShift.isDown()) {
            // 原：module.j.Z(RotationUtils.i(module.v(), module.j.H, module.c()))
            engine.currentRotation.setYaw(ElowenScaffold.stepTo(engine.currentRotation.getYaw(), engine.serverYaw(), engine.backRotationSpeed()));
            return;
        }
        Rot2f targetRotation = engine.getTargetRotation();
        engine.currentRotation = engine.rotationHandler().apply(engine, engine.currentRotation, targetRotation, engine.rotationSpeed());
        engine.placeOrGuard(target);
    }

    @Override
    public void onUseItem(ElowenScaffold engine, BlockPos target, StartUseItemEvent event) {
        engine.onUseItemPlace(target);
    }
}
