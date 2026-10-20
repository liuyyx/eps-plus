package com.github.epsilon.utils.network;

import net.minecraft.network.protocol.Packet;

import java.util.concurrent.ConcurrentHashMap;
import java.util.Set;

import static com.github.epsilon.Constants.mc;

public class NetworkUtils {

    // netty 线程（FakeLag.releaseAll）与发送线程会并发 add/contains/remove。
    public static final Set<Packet<?>> bypassedPackets = ConcurrentHashMap.newKeySet();

    /**
     * 发送网络包，并使该包绕过 Epsilon 的发送事件。
     *
     * @param packet 待发送或过滤的网络包
     */
    public static void sendPacketNoEvent(Packet<?> packet) {
        if (mc.getConnection() != null) {
            bypassedPackets.add(packet);
            mc.getConnection().send(packet);
        }
    }

}
