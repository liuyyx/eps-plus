package com.github.epsilon.modules.impl.movement.scaffold.elowen.place;

import com.github.epsilon.events.impl.StartUseItemEvent;
import com.github.epsilon.modules.impl.movement.scaffold.elowen.ElowenScaffold;
import com.github.epsilon.utils.rotation.Rot2f;
import net.minecraft.core.BlockPos;
import net.minecraft.util.Mth;

import static com.github.epsilon.Constants.mc;

/**
 * Telly Bridge 放置模式（elowen {@code com.elowen.o.Y.Z.l.a}）。
 *
 * <p>elowen 原文的 {@code a.getConfusionFlag()} 恒为 {@code null}（§判定基线），对应分支整段删除；
 * {@code onUseItem} 的门控 {@code !module.al && module.V < 1} 保留（{@code al} = 物理跳跃键、
 * {@code V} = 离地刻数）。</p>
 *
 * <p>[elowen o/Y/Z/l/a.java:41-104]</p>
 */
public class TellyBridgeMode implements PlaceModeHandler {

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
        if (mc.player.onGround() && engine.isMoving()) {
            float baseYaw = engine.serverYaw();
            Rot2f stepTarget = new Rot2f(baseYaw, engine.currentRotation.getPitch());
            engine.currentRotation = engine.rotationHandler().apply(engine, engine.currentRotation, stepTarget, engine.backRotationSpeed());
            // 原：float f5 = Math.abs(RotationUtils.e(j.H, f4)) - 1.0f; 再把「负号」折成 1 / 0
            float alignedDelta = Math.abs(Mth.wrapDegrees(engine.currentRotation.getYaw() - baseYaw)) - 1.0f;
            float alignedSign = alignedDelta == 0.0f ? 0 : (alignedDelta < 0.0f ? -1 : 1);
            alignedSign = alignedSign < 0 ? 1.0f : 0.0f;
            boolean noCollision = engine.noCollisionBelow(0.1f);
            engine.requestJump((alignedSign != 0.0f || noCollision) && (engine.isMoving() || engine.jumpKeyPhysicallyDown()));
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
        if (!engine.jumpKeyPhysicallyDown() && engine.airTicksSinceGround() < 1) {
            return;
        }
        engine.onUseItemPlace(target);
    }
}
