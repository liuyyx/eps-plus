package com.github.epsilon.modules.impl.movement.scaffold.elowen.place;

import com.github.epsilon.events.impl.StartUseItemEvent;
import com.github.epsilon.modules.impl.movement.scaffold.elowen.ElowenScaffold;
import net.minecraft.core.BlockPos;

/**
 * elowen {@code com.elowen.o.Y.Z.l.Y}（放置模式接口）。
 *
 * <p>elowen 原接口还有 {@code onPacket} / {@code onUpdate} / {@code onMoveInput} / {@code getModule()}：
 * 前两者在四个实现里都只是转发给主类同名方法（{@code module.E(z2)} / {@code module.p(c2)}），
 * 而 {@code onMoveInput} 四个实现全为空 —— 这些在 Epsilon 侧一律由 {@link ElowenScaffold} 自己订阅，
 * 故不进接口（§2.10）。</p>
 *
 * <p>[elowen o/Y/Z/l/Y.java:11-26]</p>
 */
public interface PlaceModeHandler {

    void onEnable();

    void onDisable();

    /**
     * 每刻推进（原 {@code onTick(MinecraftTickEvent, BlockPos towerPos)}）。
     *
     * @param engine 引擎（原 {@code module}）
     * @param target 本刻放置目标（原 {@code towerPos}，即 {@code Scaffold.Y}）
     */
    void onTick(ElowenScaffold engine, BlockPos target);

    /**
     * 右键被本模块接管时调用（原 {@code onUseItem(UseItemEvent, BlockPos towerPos)}）。
     *
     * @param event 原版右键事件（elowen 侧已在上游 {@code setCancelled(true)}）
     */
    void onUseItem(ElowenScaffold engine, BlockPos target, StartUseItemEvent event);
}
