package com.github.epsilon.modules.impl.movement.scaffold.elowen.place;

import com.github.epsilon.events.impl.StartUseItemEvent;
import com.github.epsilon.modules.impl.movement.scaffold.elowen.ElowenScaffold;
import com.github.epsilon.utils.math.MathUtils;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;

import static com.github.epsilon.Constants.mc;

/**
 * Snap 放置模式（elowen {@code com.elowen.o.Y.Z.l.g}）。
 *
 * <p>elowen 原文开头的 {@code stringArray = a.getConfusionFlag()} 恒为 {@code null}（§判定基线），
 * 那 10 行毒丸死代码（连续给 {@code j} 赋 yaw 后被 {@code j.D(c(), a())} 覆盖）整段删除，
 * 只保留 {@code stringArray == null} 之后的部分。旋转**不经** {@code rotationHandler}，
 * 直接改 {@code currentRotation}（elowen 的「隐藏旋转」）。</p>
 *
 * <p>[elowen o/Y/Z/l/g.java:41-81]</p>
 */
public class SnapMode implements PlaceModeHandler {

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
        if (engine.eagle()) {
            mc.options.keyShift.setDown(mc.player.onGround() && engine.noCollisionBelow(0.3f));
        }
        if (mc.options.keyUse.isDown() && target != null) {
            boolean lookingAtBlock = false;
            HitResult hitResult = engine.raycastRotation(engine.currentRotation);
            if (hitResult != null && hitResult.getType() == HitResult.Type.BLOCK) {
                BlockHitResult blockHitResult = (BlockHitResult) hitResult;
                lookingAtBlock = blockHitResult.getBlockPos().equals(target) && blockHitResult.getDirection() != Direction.UP;
            }
            if (lookingAtBlock) {
                engine.currentRotation.setYaw(engine.serverYaw() - 180.0f);
            } else {
                engine.currentRotation.setYaw(engine.serverYaw() + MathUtils.getRandom(0.0f, 0.5f) - 0.25f);
            }
        } else {
            engine.currentRotation.set(engine.serverYaw(), engine.serverPitch());
        }
        engine.placeOrGuard(target);
    }

    @Override
    public void onUseItem(ElowenScaffold engine, BlockPos target, StartUseItemEvent event) {
        engine.onUseItemPlace(target);
    }
}
