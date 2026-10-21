package com.github.epsilon.managers.rotation;

import net.minecraft.util.Mth;
import com.github.epsilon.events.impl.PlayerTickEvent;
import com.github.epsilon.events.impl.SendPositionEvent;
import com.github.epsilon.utils.rotation.Rot2f;
import net.minecraft.network.protocol.game.ServerboundMovePlayerPacket;

import static com.github.epsilon.Constants.mc;

public class SnapRotationManager extends RotationManager {

    private Rot2f snappedRot = null;

    @Override
    protected void onRotationsSet() {
        snapToCurrentRotation();
    }

    private void snapToCurrentRotation() {
        if (snappedRot != null) {
            restoreSnappedRotation(snappedRot, false);
        }

        snappedRot = new Rot2f(mc.player.getYRot(), mc.player.getXRot());
        sendRotationPacket(rotations);
    }

    private void restoreSnappedRotation(Rot2f expectedSnappedRot, boolean finishRotation) {
        if (expectedSnappedRot == null || snappedRot != expectedSnappedRot) return;

        sendRotationPacket(snappedRot);
        snappedRot = null;

        if (finishRotation) {
            active = false;
            priority = 0;
            targetRotations = new Rot2f(mc.player.getYRot(), mc.player.getXRot());
        }
    }

    private void sendRotationPacket(Rot2f rotation) {
        if (rotation == null) return;

        float yaw = rotation.getYaw();
        float pitch = Mth.clamp(rotation.getPitch(), -90.0f, 90.0f);
        if (!Float.isFinite(yaw) || !Float.isFinite(pitch)) return;

        mc.getConnection().send(new ServerboundMovePlayerPacket.PosRot(
                mc.player.position(), yaw, pitch,
                mc.player.onGround(), mc.player.horizontalCollision)
        );
    }

    @Override
    protected void onPlayerTick(PlayerTickEvent.Pre event) {
        Rot2f tickSnappedRot = snappedRot;
        super.onPlayerTick(event);
        restoreSnappedRotation(tickSnappedRot, true);
    }

    @Override
    protected void handleSendPosition(SendPositionEvent event) {
    }

    @Override
    protected void resetModeState() {
        snappedRot = null;
    }

}
