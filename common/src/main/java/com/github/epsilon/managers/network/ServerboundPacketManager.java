package com.github.epsilon.managers.network;

import com.github.epsilon.assets.i18n.EpsilonTranslations;
import com.github.epsilon.events.bus.EventBus;
import com.github.epsilon.events.bus.EventHandler;
import com.github.epsilon.events.impl.LevelUpdateEvent;
import com.github.epsilon.managers.NotificationManager;
import net.minecraft.network.protocol.Packet;

import java.util.concurrent.LinkedBlockingQueue;

import static com.github.epsilon.Constants.mc;

// This is BlinkManager
public class ServerboundPacketManager {

    public static final ServerboundPacketManager INSTANCE = new ServerboundPacketManager();

    private ServerboundPacketManager() {
        EventBus.INSTANCE.subscribe(this);
    }

    public final LinkedBlockingQueue<Packet<?>> packets = new LinkedBlockingQueue<>();

    public boolean blinking = false;
    static boolean forceFlush;
    /** flush 重入保护：flush 发出的包会再次进入 onPacketSend。 */
    private boolean flushing;

    @EventHandler
    private void onLevelUpdate(LevelUpdateEvent event) {
        // 旧会话的队列不能跨会话投递：退服时 getConnection() 可能为 null，进新世界后又会发到新连接。
        packets.clear();
        forceFlush = false;
        blinking = false;
    }

    public void flush() {
        if (flushing) {
            return;
        }
        flushing = true;
        try {
            Packet<?> packet;
            while ((packet = packets.poll()) != null) {
                try {
                    mc.getConnection().send(packet);
                } catch (Exception e) {
                    NotificationManager.INSTANCE.error("Serverbound Packet", EpsilonTranslations.Notifications.SERVERBOUND_PACKET_FLUSH_FAILED.getTranslatedName() + ": " + e.getMessage());
                }
            }
        } finally {
            flushing = false;
        }
    }

    public void stopBlinking() {
        blinking = false;
        flush();
    }

    public void startBlinking() {
        blinking = true;
    }

    public boolean onPacketSend(Packet<?> packet) {
        // flush 期间放行（直接发出），既不递归也不再入队（否则队列永远不空）。
        if (flushing) return false;

        if (forceFlush) {
            forceFlush = false;
            flush();
            return false;
        }

        if (!blinking) return false;

        if (mc.player == null || mc.level == null) return false;

        packets.add(packet);
        return true;
    }

}
