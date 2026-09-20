package com.github.epsilon.modules.impl.movement;

import com.github.epsilon.events.bus.EventHandler;
import com.github.epsilon.events.bus.EventPriority;
import com.github.epsilon.events.impl.ClientTickEvent;
import com.github.epsilon.events.impl.KeyboardInputEvent;
import com.github.epsilon.events.impl.PacketEvent;
import com.github.epsilon.events.impl.PlayerTickEvent;
import com.github.epsilon.managers.rotation.RotationManager;
import com.github.epsilon.modules.Category;
import com.github.epsilon.modules.Module;
import com.github.epsilon.modules.impl.combat.AntiBot;
import com.github.epsilon.modules.impl.combat.KillAura;
import com.github.epsilon.settings.impl.BoolSetting;
import com.github.epsilon.settings.impl.EnumSetting;
import com.github.epsilon.settings.impl.IntSetting;
import com.github.epsilon.utils.player.PlayerUtils;
import com.github.epsilon.utils.rotation.Priority;
import com.github.epsilon.utils.rotation.Rot2f;
import net.minecraft.client.multiplayer.ClientPacketListener;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.common.ClientboundDisconnectPacket;
import net.minecraft.network.protocol.common.ClientboundPingPacket;
import net.minecraft.network.protocol.game.*;
import net.minecraft.util.Mth;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.phys.EntityHitResult;
import net.minecraft.world.phys.Vec3;

import java.util.*;
import java.util.concurrent.ThreadLocalRandom;

public class Velocity extends Module {

    public static final Velocity INSTANCE = new Velocity();

    private Velocity() {
        super("Velocity", Category.MOVEMENT);
    }

    // Packet receive events run on Netty while delayed packets are flushed on the client thread.
    private final Object packetLock = new Object();

    public enum Mode {
        Cancel,
        Reduce,
        Delay,
        JumpReset
    }

    public final EnumSetting<Mode> mode = enumSetting("Mode", Mode.Cancel, newMode -> {
        if (newMode != Mode.Reduce) resetReduceState();
        if (newMode != Mode.Delay) resetDelayState();
        if (newMode != Mode.JumpReset) resetJumpResetState();
    });
    private final BoolSetting serverMotion = boolSetting("Server Motion", true, () -> mode.is(Mode.Cancel));
    private final BoolSetting explosion = boolSetting("Explosion", true, () -> mode.is(Mode.Cancel));
    private final BoolSetting explosionOnlyBlock = boolSetting("Explosion Only Block", false, () -> mode.is(Mode.Cancel) && explosion.getValue());
    public final BoolSetting waterPush = boolSetting("No Water Push", true, () -> mode.is(Mode.Cancel));
    public final BoolSetting entityPush = boolSetting("No Entity Push", true, () -> mode.is(Mode.Cancel));
    public final BoolSetting blockPush = boolSetting("No Block Push", true, () -> mode.is(Mode.Cancel));
    private final IntSetting attackCounts = intSetting("Attack Counts", 1, 1, 5, 1, () -> mode.is(Mode.Reduce));
    private final IntSetting sprintTicks = intSetting("Sprint Ticks", 3, 1, 5, 1, () -> mode.is(Mode.Reduce));
    private final IntSetting maxDelay = intSetting("Max Delay", 1000, 0, 1000, 50, () -> mode.is(Mode.Reduce));
    private final BoolSetting swingHand = boolSetting("Swing Hand", false, () -> mode.is(Mode.Reduce));
    private final IntSetting delayTicks = intSetting("Delay Ticks", 3, 1, 5, 1, () -> mode.is(Mode.Delay));
    private final BoolSetting jumpReset = boolSetting("Jump Reset", false, () -> mode.is(Mode.Delay));

    // JumpReset 模式设置（移植自 LiquidBounce VelocityJumpReset）
    private final IntSetting jumpResetChance = intSetting("Jump Reset Chance", 100, 0, 100, 1, () -> mode.is(Mode.JumpReset));
    private final BoolSetting jumpByReceivedHits = boolSetting("Jump By Received Hits", false, () -> mode.is(Mode.JumpReset));
    private final IntSetting hitsUntilJumpMin = intSetting("Hits Until Jump Min", 2, 0, 10, 1, () -> mode.is(Mode.JumpReset));
    private final IntSetting hitsUntilJumpMax = intSetting("Hits Until Jump Max", 2, 0, 10, 1, () -> mode.is(Mode.JumpReset));
    private final BoolSetting jumpByDelay = boolSetting("Jump By Delay", true, () -> mode.is(Mode.JumpReset));
    private final IntSetting untilJumpMin = intSetting("Until Jump Min", 0, 0, 20, 1, () -> mode.is(Mode.JumpReset));
    private final IntSetting untilJumpMax = intSetting("Until Jump Max", 0, 0, 20, 1, () -> mode.is(Mode.JumpReset));

    private volatile long lag;
    private volatile long delayLag;
    private volatile long startDelay;
    public volatile int attackQueue;
    private volatile int sprintQueue;
    public volatile float yaw;
    public volatile boolean delay;
    private volatile boolean jump;
    private volatile int delayTicksRemaining;

    // JumpReset 模式状态：冷却计数、本次所需的命中/延迟 tick、摔落伤害标记
    private int jumpResetLimit;
    private int jumpResetHitsNeeded;
    private int jumpResetTicksNeeded;
    private boolean jumpResetFallDamage;

    public boolean ownsIncomingDelayQueue() {
        return isEnabled() && (mode.is(Mode.Reduce) && delay || mode.is(Mode.Delay) && delayTicksRemaining > 0);
    }

    public boolean blocksBacktrack() {
        return isEnabled() && (mode.is(Mode.Reduce) && (delay || attackQueue > 0) || mode.is(Mode.Delay) && delayTicksRemaining > 0);
    }

    private final Queue<Packet<? super ClientPacketListener>> packets = new ArrayDeque<>();
    private final Queue<Packet<? super ClientPacketListener>> delayPackets = new ArrayDeque<>();

    @Override
    public String getInfo() {
        return mode.getTranslatedValue() + switch (mode.getValue()) {
            case Cancel -> "";
            case Reduce -> " " + (delay ? System.currentTimeMillis() - startDelay + "ms" : "");
            case Delay -> " " + delayTicksRemaining + "t";
            case JumpReset -> " " + jumpResetLimit;
        };
    }

    @Override
    protected void onEnable() {
        resetJumpResetState();
    }

    @Override
    protected void onDisable() {
        flush();
        flushDelay();
        resetReduceState();
        resetDelayState();
        resetJumpResetState();
    }

    @EventHandler
    private void onPacketReceive(PacketEvent.Receive event) {
        synchronized (packetLock) {
            if (!isEnabled() || nullCheck()) return;

            switch (mode.getValue()) {
                case Cancel -> cancelPacket(event);
                case Reduce -> reducePacket(event);
                case Delay -> delayPacket(event);
            }
        }
    }

    private void cancelPacket(PacketEvent.Receive event) {
        if (serverMotion.getValue() && event.getPacket() instanceof ClientboundSetEntityMotionPacket packet && packet.id() == mc.player.getId()) {
            event.cancel();
            return;
        }

        if (explosion.getValue()
                && event.getPacket() instanceof ClientboundExplodePacket packet
                && (!explosionOnlyBlock.getValue() || PlayerUtils.isInBlock())) {
            event.setPacket(new ClientboundExplodePacket(
                    packet.center(),
                    packet.radius(),
                    packet.blockCount(),
                    Optional.empty(),
                    packet.explosionParticle(),
                    packet.explosionSound(),
                    packet.blockParticles()
            ));
        }
    }

    private void reducePacket(PacketEvent.Receive event) {
        Packet<?> packet = event.getPacket();

        if (packet instanceof ClientboundDisconnectPacket) {
            resetReduceState();
            return;
        }

        if (delay && (packet instanceof ClientboundSetEntityMotionPacket
                || packet instanceof ClientboundMoveEntityPacket
                || packet instanceof ClientboundTeleportEntityPacket
                || packet instanceof ClientboundPingPacket
                || packet instanceof ClientboundPlayerLookAtPacket
                || packet instanceof ClientboundPlayerPositionPacket)
        ) {
            event.cancel();
            packets.add((Packet<? super ClientPacketListener>) packet);
        }

        if (packet instanceof ClientboundPlayerPositionPacket || packet instanceof ClientboundExplodePacket) {
            lag = System.currentTimeMillis();
        }

        if (packet instanceof ClientboundSetEntityMotionPacket(int id, Vec3 movement) && id == mc.player.getId()) {
            if (System.currentTimeMillis() - lag >= 100L) {
                boolean knockback = movement.y > 0.0 && (movement.x != 0.0 || movement.z != 0.0);
                if (knockback && !delay) {
                    delay = true;
                    event.cancel();
                    packets.add((Packet<? super ClientPacketListener>) packet);
                    startDelay = System.currentTimeMillis();
                }
            }
            yaw = Mth.wrapDegrees((float) (Math.toDegrees(Math.atan2(movement.z, movement.x)) + 90.0));
        }
    }

    private void delayPacket(PacketEvent.Receive event) {
        Packet<?> packet = event.getPacket();

        if (packet instanceof ClientboundDisconnectPacket) {
            resetDelayState();
            return;
        }

        if (packet instanceof ClientboundPlayerPositionPacket || packet instanceof ClientboundExplodePacket) {
            delayLag = System.currentTimeMillis();
        }

        if (delayTicksRemaining > 0) {
            event.cancel();
            delayPackets.add((Packet<? super ClientPacketListener>) packet);
            return;
        }

        if (packet instanceof ClientboundSetEntityMotionPacket(
                int id, Vec3 movement
        ) && id == mc.player.getId() && System.currentTimeMillis() - delayLag >= 100L) {
            boolean knockback = movement.y > 0.0 && (movement.x != 0.0 || movement.z != 0.0);
            if (knockback) {
                delayTicksRemaining = delayTicks.getValue();
            }
        }
    }

    @EventHandler
    private void onReduceTick(ClientTickEvent.Pre event) {
        if (nullCheck() || !mode.is(Mode.Reduce)) return;

        if (delay && (mc.player.onGround() || System.currentTimeMillis() - lag < 100 || System.currentTimeMillis() - startDelay >= maxDelay.getValue()) && flush()) {
            if (System.currentTimeMillis() - lag >= 100L) {
                attackQueue = attackCounts.getValue();
                sprintQueue = sprintTicks.getValue();
            }
        }

        KillAura killAura = KillAura.INSTANCE;
        if (sprintQueue >= 1 && killAura.target == null && !Scaffold.INSTANCE.isEnabled()) {
            RotationManager.INSTANCE.setRotations(new Rot2f(yaw, RotationManager.INSTANCE.getRotation().getPitch()), 180f, Priority.Highest);
        }
    }

    @EventHandler(priority = EventPriority.LOW)
    private void onDelayTick(PlayerTickEvent.Pre event) {
        List<Packet<? super ClientPacketListener>> pending = List.of();
        synchronized (packetLock) {
            if (!mode.is(Mode.Delay) || delayTicksRemaining <= 0) return;
            if (--delayTicksRemaining == 0) {
                pending = drain(delayPackets);
            }
        }
        handlePackets(pending);
    }

    @EventHandler(priority = EventPriority.LOW)
    private void onAttackTick(PlayerTickEvent.Pre event) {
        if (!mode.is(Mode.Reduce)) return;

        if (attackQueue >= 1) {
            if (RotationManager.INSTANCE.getHitResult() instanceof EntityHitResult entityHitResult && entityHitResult.getEntity() instanceof Player pl && pl.isAlive() && !AntiBot.INSTANCE.isBot(pl)) {
                mc.gameMode.attack(mc.player, pl);
                if (swingHand.getValue()) {
                    mc.player.swing(InteractionHand.MAIN_HAND);
                } else {
                    mc.getConnection().send(new ServerboundSwingPacket(InteractionHand.MAIN_HAND));
                }
            }
            attackQueue--;
        }
    }

    @EventHandler(priority = EventPriority.LOW)
    private void onKeyboardInput(KeyboardInputEvent event) {
        if (mode.is(Mode.Delay) && jumpReset.getValue() && mc.player.onGround() && mc.player.hurtTime == 9) {
            event.setJump(true);
        }

        if (!mode.is(Mode.Reduce)) return;

        if (delay && mc.player.fallDistance == 0 && RotationManager.INSTANCE.getHitResult() instanceof EntityHitResult entityHitResult && entityHitResult.getEntity() instanceof Player pl && !AntiBot.INSTANCE.isBot(pl)) {
            if (System.currentTimeMillis() - lag >= 100) {
                event.setForward(1.0f);
                event.setStrafe(0.0f);
            }
        }

        if (jump && mc.player.onGround()) {
            if (System.currentTimeMillis() - lag >= 100) {
                event.setJump(true);
                event.setForward(1.0f);
                event.setStrafe(0.0f);
            }
            jump = false;
        }

        KillAura killAura = KillAura.INSTANCE;
        if (sprintQueue-- >= 1 && killAura.target == null && !Scaffold.INSTANCE.isEnabled() && System.currentTimeMillis() - lag >= 100) {
            event.setForward(1.0f);
            event.setStrafe(0.0f);
        }
    }

    /**
     * 记录自身速度包：纯竖直向下的速度代表摔落伤害，此时起跳无法削减击退。
     * 每次收到都覆盖记录，因为后续是否被击退可以从速度中分辨出来。
     */
    @EventHandler(priority = EventPriority.HIGH)
    private void onJumpResetPacket(PacketEvent.Receive event) {
        if (!isEnabled() || !mode.is(Mode.JumpReset) || nullCheck()) return;

        if (event.getPacket() instanceof ClientboundSetEntityMotionPacket(int id, Vec3 movement)
                && id == mc.player.getId()) {
            jumpResetFallDamage = movement.x == 0.0 && movement.z == 0.0 && movement.y < 0.0;
        }
    }

    /**
     * JumpReset 模式：被击退（hurtTime == 9）瞬间模拟起跳，从而削减服务端下发的击退速度。
     * 玩家必须处于冲刺且在地面上，否则跳跃无法改变击退。
     */
    @EventHandler
    private void onJumpResetInput(KeyboardInputEvent event) {
        if (!mode.is(Mode.JumpReset) || nullCheck()) return;

        if (mc.player.hurtTime != 9 || !mc.player.onGround() || !mc.player.isSprinting()
                || jumpResetFallDamage || !jumpResetCooldownOver()
                || ThreadLocalRandom.current().nextInt(100) >= jumpResetChance.getValue()) {
            jumpResetUpdateLimit();
            return;
        }

        event.setJump(true);
        jumpResetLimit = 0;
        jumpResetRollNeeds();
    }

    private boolean flush() {
        List<Packet<? super ClientPacketListener>> pending;
        synchronized (packetLock) {
            if (!delay) return false;

            delay = false;
            pending = drain(packets);
        }

        handlePackets(pending);
        jump = true;
        return true;
    }

    private void flushDelay() {
        List<Packet<? super ClientPacketListener>> pending;
        synchronized (packetLock) {
            delayTicksRemaining = 0;
            pending = drain(delayPackets);
        }
        handlePackets(pending);
    }

    private void resetReduceState() {
        synchronized (packetLock) {
            delay = false;
            packets.clear();
            lag = 0L;
            startDelay = 0L;
            attackQueue = 0;
            sprintQueue = 0;
            jump = false;
            yaw = 0.0f;
        }
    }

    private void resetDelayState() {
        synchronized (packetLock) {
            delayTicksRemaining = 0;
            delayPackets.clear();
            delayLag = 0L;
        }
    }

    /**
     * 复位 JumpReset 状态：清零冷却计数与摔落伤害标记，并重新随机本次所需的命中次数与延迟 tick。
     */
    private void resetJumpResetState() {
        jumpResetLimit = 0;
        jumpResetFallDamage = false;
        jumpResetRollNeeds();
    }

    /**
     * 重新随机本次起跳所需的命中次数与延迟 tick（对应 LB 的两个 intRange 随机取值）。
     */
    private void jumpResetRollNeeds() {
        jumpResetHitsNeeded = randomBetween(hitsUntilJumpMin.getValue(), hitsUntilJumpMax.getValue());
        jumpResetTicksNeeded = randomBetween(untilJumpMin.getValue(), untilJumpMax.getValue());
    }

    /**
     * 冷却判定：优先按“受击次数”，否则按“延迟 tick”；两者都未开启时始终可起跳。
     */
    private boolean jumpResetCooldownOver() {
        if (jumpByReceivedHits.getValue()) return jumpResetLimit >= jumpResetHitsNeeded;
        if (jumpByDelay.getValue()) return jumpResetLimit >= jumpResetTicksNeeded;
        return true;
    }

    /**
     * 冷却累加：按受击次数计时时仅在受击瞬间累加，否则每 tick 累加。
     */
    private void jumpResetUpdateLimit() {
        if (jumpByReceivedHits.getValue()) {
            if (mc.player.hurtTime == 9) jumpResetLimit++;
            return;
        }
        jumpResetLimit++;
    }

    private static int randomBetween(int first, int second) {
        int min = Math.min(first, second);
        int max = Math.max(first, second);
        return min == max ? min : ThreadLocalRandom.current().nextInt(min, max + 1);
    }

    private static <T> List<T> drain(Queue<T> queue) {
        List<T> packets = new ArrayList<>(queue.size());
        T packet;
        while ((packet = queue.poll()) != null) {
            packets.add(packet);
        }
        return packets;
    }

    private void handlePackets(List<Packet<? super ClientPacketListener>> packets) {
        ClientPacketListener listener = mc.getConnection();
        if (listener == null) return;

        for (Packet<? super ClientPacketListener> packet : packets) {
            packet.handle(listener);
        }
    }

}
