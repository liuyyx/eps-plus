/*
 * This file is part of Epsilon.
 *
 * 逐行照抄自 OpenPal（https://github.com/OpenPal）MC 1.21.10 的
 * wtf.opal.client.feature.helper.impl.player.slot.SlotHelper，只做 Yarn → 26.2 Mojang 命名替换：
 *   ClientPlayerEntity → LocalPlayer、PlayerInventory → Inventory
 *   player.getInventory().getMainStacks().get(i) → player.getInventory().getItem(i)（0..8 同为快捷栏）
 *   player.getMainHandStack() → player.getMainHandItem()
 *   mc.currentScreen == null → mc.gui.screen() == null
 *   mc.getOverlay() → 26.2 的 Minecraft 没有该入口（加载遮罩已并入 gui），判定只剩 gui.screen() == null
 *   MathHelper.clamp → Mth.clamp
 *
 * <p><b>§2 例外 2 —— {@link Silence#FULL} 的视觉解耦在本仓库退化：</b>
 * OpenPal 的 {@code Silence.FULL} 依赖它的滚轮重定向 mixin（把 {@code setSelectedSlot} 的视觉结果
 * 改回 {@code currentItem}），所以"服务端看到目标槽、玩家看到原槽"。
 * 本仓库没有这条通道，{@link #sync(boolean, boolean)} 会真的调用
 * {@code inventory.setSelectedSlot(...)} ⇒ 视觉上就是普通的切槽，
 * 只是 {@link #getSelectedSlot} 仍按 OpenPal 的语义返回 {@code currentItem}。
 * 因此 {@code Silence.FULL}（对应本模块 {@code Swap Mode = InvSwitch}）不提供视觉解耦。</p>
 */
package com.github.epsilon.modules.impl.movement.scaffold.slot;

import net.minecraft.client.player.LocalPlayer;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.item.ItemStack;

import static com.github.epsilon.Constants.mc;

public final class SlotHelper {
    private SlotHelper() {
    }

    private int currentItem, targetItem;
    private boolean active;
    private int activeTick, ticks;
    private Silence silence = Silence.DEFAULT;

    public SlotHelper setTargetItem(int currentItem) {
        currentItem = Mth.clamp(currentItem, 0, 8);

        if (!this.active) {
            this.currentItem = mc.player.getInventory().getSelectedSlot();
        }
        this.targetItem = currentItem;
        this.activeTick = this.ticks;
        this.active = true;
        this.sync(true, true);
        return this;
    }

    public SlotHelper silence(Silence silence) {
        this.silence = silence;
        return this;
    }

    public void setVisualSlot(int currentItem) {
        this.currentItem = currentItem;
    }

    public void stop() { // if you need something to swap back instantly use this, otherwise there will be a 1 tick delay purposefully to prevent collisions with mc
        this.activeTick = -1;
        this.sync(true, true);
    }

    public void sync(boolean reset, boolean check) {
        if (this.active) {
            if (!check || mc.gui.screen() == null) {
                if (reset && this.activeTick != this.ticks) {
                    mc.player.getInventory().setSelectedSlot(this.currentItem);
                    this.silence = Silence.DEFAULT;
                    this.active = false;
                } else {
                    mc.player.getInventory().setSelectedSlot(this.targetItem);
                }
            }
        }
    }

    public void tick() {
        this.sync(true, true);
        this.ticks++;
    }

    public ItemStack getMainHandStack(LocalPlayer player) {
        return this.active && this.silence != Silence.NONE ? player.getInventory().getItem(this.currentItem) : player.getMainHandItem();
    }

    public int getSelectedSlot(Inventory inventory) {
        return this.active && this.silence == Silence.FULL ? this.currentItem : inventory.getSelectedSlot();
    }

    public boolean isActive() {
        return active;
    }

    public int getVisualSlot() {
        return currentItem;
    }

    public Silence getSilence() {
        return silence;
    }

    private static SlotHelper instance;

    public static SlotHelper getInstance() {
        return instance;
    }

    public static void setInstance() {
        instance = new SlotHelper();
    }

    public static SlotHelper setCurrentItem(int currentItem) {
        return instance.setTargetItem(currentItem);
    }

    public enum Silence {
        NONE,
        DEFAULT,
        FULL
    }
}
