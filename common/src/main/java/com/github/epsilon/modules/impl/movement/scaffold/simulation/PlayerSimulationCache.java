/*
 * This file is part of Epsilon.
 *
 * 逐行移植自 LiquidBounce (https://github.com/CCBlueX/LiquidBounce)
 * 源文件: src/main/kotlin/net/ccbluex/liquidbounce/utils/entity/PlayerSimulationCache.kt
 * Copyright (c) 2015 - 2026 CCBlueX — GNU General Public License v3.0
 */
package com.github.epsilon.modules.impl.movement.scaffold.simulation;

import com.github.epsilon.Constants;
import com.github.epsilon.events.bus.EventBus;
import com.github.epsilon.events.bus.EventHandler;
import com.github.epsilon.events.bus.EventPriority;
import com.github.epsilon.events.impl.ClientTickEvent;
import com.github.epsilon.events.impl.KeyboardInputEvent;
import it.unimi.dsi.fastutil.objects.ObjectArrayList;
import it.unimi.dsi.fastutil.objects.ObjectImmutableList;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.phys.Vec3;

import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.locks.ReentrantReadWriteLock;

/**
 * LB 原文: {@code object PlayerSimulationCache : EventListener}
 * <p>
 * [适配] 三个同文件的二级类型（{@code SimulatedPlayerCache} / {@code SimulatedPlayerSnapshot} /
 * {@code CachedPlayerSimulation}）在 Java 中作为本类的 public static 嵌套类型保留原名（Java 一个文件只能有一个 public 顶层类）。
 * 事件订阅：LB 的 object 由 LB 事件总线全局注册；Epsilon 侧本类非 Module，无自动注册路径 → 静态初始化时自注册。
 */
public final class PlayerSimulationCache {

    private static final Map<Player, SimulatedPlayerCache> otherPlayerCache = new ConcurrentHashMap<>();
    private static SimulatedPlayerCache localPlayerCache = null;

    static {
        EventBus.INSTANCE.subscribe(PlayerSimulationCache.class);
    }

    private PlayerSimulationCache() {
    }

    // LB: handler<GameTickEvent>(priority = FIRST_PRIORITY) { otherPlayerCache.clear() }
    @EventHandler(priority = EventPriority.HIGHEST)
    public static void onClientTickPre(ClientTickEvent.Pre event) {
        otherPlayerCache.clear();
    }

    // LB: handler<MovementInputEvent>(priority = CRITICAL_MODIFICATION) { localPlayerCache = null; updatePlayerCache(it.directionalInput) }
    @EventHandler(priority = EventPriority.HIGH)
    public static void onKeyboardInputCritical(KeyboardInputEvent event) {
        localPlayerCache = null;
        updatePlayerCache(new DirectionalInput(event.getForward(), event.getStrafe()));
    }

    // LB: handler<MovementInputEvent> { updatePlayerCache(it.directionalInput, verify = true) }
    @EventHandler(priority = EventPriority.MEDIUM)
    public static void onKeyboardInput(KeyboardInputEvent event) {
        updatePlayerCache(new DirectionalInput(event.getForward(), event.getStrafe()), true);
    }

    // LB: handler<MovementInputEvent>(priority = MODEL_STATE) { updatePlayerCache(it.directionalInput, verify = true) }
    @EventHandler(priority = EventPriority.LOW)
    public static void onKeyboardInputModelState(KeyboardInputEvent event) {
        updatePlayerCache(new DirectionalInput(event.getForward(), event.getStrafe()), true);
    }

    /**
     * Updates the cache for the local player,
     * this will be called on every movement input event
     * to ensure the cache is up to date.
     *
     * @param directionalInput the input to update the cache with
     */
    private static void updatePlayerCache(DirectionalInput directionalInput, boolean verify) {
        // Check if we even need to update the cache
        if (verify && localPlayerCache != null
                && localPlayerCache.simulatedPlayer.input.directionalInput.equals(directionalInput)) {
            return;
        }

        SimulatedPlayer simulatedPlayer = SimulatedPlayer.fromClientPlayer(
                SimulatedPlayer.SimulatedPlayerInput.fromClientPlayer(directionalInput)
        );

        localPlayerCache = new SimulatedPlayerCache(simulatedPlayer);
    }

    private static void updatePlayerCache(DirectionalInput directionalInput) {
        updatePlayerCache(directionalInput, false);
    }

    public static SimulatedPlayerCache getSimulationForOtherPlayers(Player player) {
        return otherPlayerCache.computeIfAbsent(player, it -> {
            SimulatedPlayer simulatedPlayer = SimulatedPlayer.fromOtherPlayer(
                    it,
                    SimulatedPlayer.SimulatedPlayerInput.guessInput(it)
            );

            return new SimulatedPlayerCache(simulatedPlayer);
        });
    }

    public static SimulatedPlayerCache getSimulationForLocalPlayer() {
        SimulatedPlayerCache cached = localPlayerCache;

        if (cached != null) {
            return cached;
        }

        SimulatedPlayer simulatedPlayer = SimulatedPlayer.fromClientPlayer(
                SimulatedPlayer.SimulatedPlayerInput.fromClientPlayer(new DirectionalInput(Constants.mc.player.input))
        );

        SimulatedPlayerCache simulatedPlayerCache = new SimulatedPlayerCache(simulatedPlayer);

        localPlayerCache = simulatedPlayerCache;

        return simulatedPlayerCache;
    }

    public static final class SimulatedPlayerCache {

        public final SimulatedPlayer simulatedPlayer;

        private int currentSimulationStep = 0;
        private final ObjectArrayList<SimulatedPlayerSnapshot> simulationSteps = new ObjectArrayList<>();
        private final ReentrantReadWriteLock lock = new ReentrantReadWriteLock();

        public SimulatedPlayerCache(SimulatedPlayer simulatedPlayer) {
            this.simulatedPlayer = simulatedPlayer;
            this.simulationSteps.add(new SimulatedPlayerSnapshot(simulatedPlayer));
        }

        public void simulateUntil(int ticks) {
            if (ticks < 0) {
                throw new IllegalStateException("ticks may not be negative");
            }

            if (this.currentSimulationStep >= ticks) {
                return;
            }

            this.lock.writeLock().lock();
            try {
                while (this.currentSimulationStep < ticks) {
                    this.simulatedPlayer.tick();
                    this.simulationSteps.add(new SimulatedPlayerSnapshot(this.simulatedPlayer));

                    this.currentSimulationStep++;
                }
            } finally {
                this.lock.writeLock().unlock();
            }
        }

        public SimulatedPlayerSnapshot getSnapshotAt(int ticks) {
            this.simulateUntil(ticks);

            this.lock.readLock().lock();
            try {
                return this.simulationSteps.get(ticks);
            } finally {
                this.lock.readLock().unlock();
            }
        }

        public Iterator<SimulatedPlayerSnapshot> simulate() {
            return new Iterator<>() {

                private int idx = 0;

                @Override
                public boolean hasNext() {
                    return true;
                }

                @Override
                public SimulatedPlayerSnapshot next() {
                    return getSnapshotAt(this.idx++);
                }

            };
        }

        public List<SimulatedPlayerSnapshot> getSnapshotsBetween(int firstTick, int lastTick) {
            if (lastTick >= 60 * 20) {
                throw new IllegalStateException("tried to simulate a player for more than a minute!");
            }

            this.simulateUntil(lastTick + 1);

            this.lock.readLock().lock();
            try {
                return new ObjectImmutableList<>(this.simulationSteps.subList(firstTick, lastTick + 1));
            } finally {
                this.lock.readLock().unlock();
            }
        }

        public Iterator<SimulatedPlayerSnapshot> simulateBetween(int firstTick, int lastTick) {
            if (lastTick >= 60 * 20) {
                throw new IllegalStateException("tried to simulate a player for more than a minute!");
            }

            this.simulateUntil(lastTick + 1);

            return new Iterator<>() {

                private int i = firstTick;

                @Override
                public boolean hasNext() {
                    return this.i <= lastTick;
                }

                @Override
                public SimulatedPlayerSnapshot next() {
                    return getSnapshotAt(this.i++);
                }

            };
        }

    }

    public record SimulatedPlayerSnapshot(
            Vec3 pos,
            double fallDistance,
            Vec3 velocity,
            boolean onGround,
            boolean clipLedged
    ) {

        public SimulatedPlayerSnapshot(SimulatedPlayer s) {
            this(
                    s.pos,
                    s.fallDistance,
                    s.deltaMovement,
                    s.onGround,
                    s.isClipLedged()
            );
        }

    }

    /**
     * Yes, this name sucks as {@link SimulatedPlayerCache} already exists, but I don't know a better name :/
     */
    public static final class CachedPlayerSimulation implements PlayerSimulation {

        private final SimulatedPlayerCache simulatedPlayer;

        private int ticks = 0;

        public CachedPlayerSimulation(SimulatedPlayerCache simulatedPlayer) {
            this.simulatedPlayer = simulatedPlayer;
        }

        @Override
        public Vec3 getPos() {
            return this.simulatedPlayer.getSnapshotAt(this.ticks).pos();
        }

        @Override
        public void tick() {
            this.ticks++;
        }

    }

}
