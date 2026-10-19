package com.github.epsilon.modules.impl.combat;

import com.github.epsilon.events.bus.EventHandler;
import com.github.epsilon.events.impl.PlayerTickEvent;
import com.github.epsilon.managers.rotation.RotationManager;
import com.github.epsilon.modules.Category;
import com.github.epsilon.modules.Module;
import com.github.epsilon.settings.impl.BoolSetting;
import com.github.epsilon.settings.impl.EnumSetting;
import com.github.epsilon.utils.player.FindItemResult;
import com.github.epsilon.utils.player.InvUtils;
import com.github.epsilon.utils.rotation.Priority;
import com.github.epsilon.utils.rotation.Rot2f;
import net.minecraft.network.protocol.game.ServerboundSwingPacket;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.item.Items;

public class AutoMend extends Module {

    public static final AutoMend INSTANCE = new AutoMend();

    private AutoMend() {
        super("Auto Mend", Category.COMBAT);
    }

    private enum SwitchMode {
        Normal,
        Silent
    }

    private final EnumSetting<SwitchMode> switchMode = enumSetting("Switch Mode", SwitchMode.Normal);
    private final BoolSetting swingHand = boolSetting("Swing Hand", false);

    private boolean shouldSwapBack;

    @Override
    protected void onEnable() {
        shouldSwapBack = false;
    }

    @Override
    protected void onDisable() {
        if (shouldSwapBack) {
            InvUtils.swapBack();
        }
    }

    @EventHandler
    private void onClientTick(PlayerTickEvent.Pre event) {
        // 原实现每 tick 无条件喝经验瓶（20 瓶/秒清空背包），完全没有「需要修补」的判据。
        if (!needsMending()) return;
        // 节流：每 4 刻最多一瓶，避免一次修补把整背包经验瓶喝光。
        if (mc.player.tickCount % 4 != 0) return;

        FindItemResult result = InvUtils.findInHotbar(Items.EXPERIENCE_BOTTLE);
        if (!result.found()) return;

        RotationManager.INSTANCE.setRotations(new Rot2f(mc.player.getYRot(), 90), 180, Priority.High);

        InvUtils.swap(result.slot(), true);

        InteractionHand hand = result.getHand();
        mc.gameMode.useItem(mc.player, hand);
        if (swingHand.getValue()) {
            mc.player.swing(hand);
        } else {
            mc.getConnection().send(new ServerboundSwingPacket(hand));
        }

        if (switchMode.is(SwitchMode.Silent)) {
            InvUtils.swapBack();
        } else {
            shouldSwapBack = true;
        }
    }

    /** 身上是否有需要修补的可损坏物品（装备/手持）。 */
    private boolean needsMending() {
        for (net.minecraft.world.entity.EquipmentSlot slot : new net.minecraft.world.entity.EquipmentSlot[]{
                net.minecraft.world.entity.EquipmentSlot.MAINHAND,
                net.minecraft.world.entity.EquipmentSlot.OFFHAND,
                net.minecraft.world.entity.EquipmentSlot.HEAD,
                net.minecraft.world.entity.EquipmentSlot.CHEST,
                net.minecraft.world.entity.EquipmentSlot.LEGS,
                net.minecraft.world.entity.EquipmentSlot.FEET}) {
            net.minecraft.world.item.ItemStack stack = mc.player.getItemBySlot(slot);
            if (stack.isDamageableItem() && stack.getDamageValue() > 0) {
                return true;
            }
        }
        return false;
    }

}
