package com.github.epsilon.modules.impl.combat;

import com.github.epsilon.events.bus.EventHandler;
import com.github.epsilon.events.bus.EventPriority;
import com.github.epsilon.events.impl.PacketEvent;
import com.github.epsilon.events.impl.PlayerTickEvent;
import com.github.epsilon.managers.FriendManager;
import com.github.epsilon.managers.target.TargetManager;
import com.github.epsilon.modules.Category;
import com.github.epsilon.modules.Module;
import com.github.epsilon.settings.impl.BoolSetting;
import com.github.epsilon.settings.impl.DoubleSetting;
import com.github.epsilon.settings.impl.EnumSetting;
import com.github.epsilon.settings.impl.IntSetting;
import com.github.epsilon.utils.network.NetworkUtils;
import net.minecraft.client.player.AbstractClientPlayer;
import net.minecraft.core.component.DataComponents;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.common.ServerboundResourcePackPacket;
import net.minecraft.network.protocol.game.ClientboundExplodePacket;
import net.minecraft.network.protocol.game.ClientboundPlayerPositionPacket;
import net.minecraft.network.protocol.game.ClientboundSetEntityMotionPacket;
import net.minecraft.network.protocol.game.ClientboundSetHealthPacket;
import net.minecraft.network.protocol.game.ServerboundAttackPacket;
import net.minecraft.network.protocol.game.ServerboundInteractPacket;
import net.minecraft.network.protocol.game.ServerboundPlayerActionPacket;
import net.minecraft.network.protocol.game.ServerboundSignUpdatePacket;
import net.minecraft.network.protocol.game.ServerboundSpectatorActionPacket;
import net.minecraft.network.protocol.game.ServerboundSwingPacket;
import net.minecraft.network.protocol.game.ServerboundUseItemOnPacket;
import net.minecraft.network.protocol.handshake.ClientIntentionPacket;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.ThreadLocalRandom;

/**
 * 假延迟（FakeLag）。
 *
 * <p>拦下自己发出的数据包，延迟 {@code Delay Min}~{@code Delay Max} 毫秒后成批补发；
 * 让服务端看到的移动轨迹滞后于本地真实位置，降低被对手命中的概率。
 * 移植自 LiquidBounce 的 {@code ModuleFakeLag}，默认值取 polar 配置
 * （Range 3.0~4.5、Delay 50~155ms、RecoilTime 39ms、Mode Dynamic、FlushOn = EntityInteract）。
 *
 * <p>与原实现的差异：LB 命中「不压包」或结束压包的条件时只重置计时器、把已压的包继续留在队列里，
 * 会造成新旧包乱序甚至长期滞留；本实现统一改为先补发已压包再放行当前包，保证补发不丢包、不乱序。
 */
public class FakeLag extends Module {

    public static final FakeLag INSTANCE = new FakeLag();

    private FakeLag() {
        super("Fake Lag", Category.COMBAT);
    }

    /**
     * 压包模式。
     */
    private enum Mode {
        /** 始终压包。 */
        Constant,
        /** 只在敌人附近、且压包对己方更有利时压包。 */
        Dynamic
    }

    private final DoubleSetting rangeMin = doubleSetting("Range Min", 3.0, 0.0, 10.0, 0.1);
    private final DoubleSetting rangeMax = doubleSetting("Range Max", 4.5, 0.0, 10.0, 0.1);
    private final IntSetting delayMin = intSetting("Delay Min", 50, 0, 1000, 5);
    private final IntSetting delayMax = intSetting("Delay Max", 155, 0, 1000, 5);
    private final IntSetting recoilTime = intSetting("Recoil Time", 39, 0, 1000, 5);
    private final EnumSetting<Mode> mode = enumSetting("Mode", Mode.Dynamic);
    private final BoolSetting flushOnEntityInteract = boolSetting("Flush On Entity Interact", true);
    private final BoolSetting flushOnBlockInteract = boolSetting("Flush On Block Interact", false);
    private final BoolSetting flushOnAction = boolSetting("Flush On Action", false);

    private final ConcurrentLinkedQueue<Packet<?>> packets = new ConcurrentLinkedQueue<>();

    /** 本批压包的起始时间，同时作为 recoil（补发后不再压包）的计时起点。 */
    private long holdStartAt;
    /** 下一次允许压包的超时时间（毫秒）。 */
    private int nextDelay;
    /** 一定范围内是否存在敌人（Dynamic 模式的前提）。 */
    private boolean isEnemyNearby;
    /** 本批压包开始时本地所在位置，Dynamic 模式用它代表「服务端所见位置」。 */
    private Vec3 heldPosition;

    @Override
    public String getInfo() {
        return packets.isEmpty() ? null : System.currentTimeMillis() - holdStartAt + "ms";
    }

    @Override
    protected void onEnable() {
        resetState();
    }

    @Override
    protected void onDisable() {
        // 必须先补发，任何已压的包都不能丢。
        releaseAll();
        resetState();
    }

    @EventHandler
    private void onPlayerTick(PlayerTickEvent.Pre event) {
        if (nullCheck()) return;
        isEnemyNearby = nearestEnemyDistance(mc.player.position()) <= Math.max(rangeMin.getValue(), rangeMax.getValue());
    }

    @EventHandler(priority = EventPriority.HIGH)
    private void onPacketSend(PacketEvent.Send event) {
        if (!isEnabled() || nullCheck()) return;

        Packet<?> packet = event.getPacket();
        // 连接握手包必须立刻发出，压住会让登录流程卡死。
        if (packet instanceof ClientIntentionPacket) return;

        // 死亡、水中、开着界面时不压包；已压的包立即补发，避免新旧包乱序。
        if (mc.player.isDeadOrDying() || mc.player.isInWater() || mc.gui.screen() != null) {
            releaseAll();
            return;
        }

        if (shouldFlushOnSend(packet)) {
            releaseAll();
            return;
        }

        // 超过随机延时：补发全部已压包、本次放行，并重新抽取下一次延时。
        if (!packets.isEmpty() && System.currentTimeMillis() - holdStartAt >= nextDelay) {
            releaseAll();
            nextDelay = randomBetween(delayMin.getValue(), delayMax.getValue());
            return;
        }

        // 刚补发过的 Recoil Time 内不再开始新一批压包；队列非空说明正在压包，不能在这里中断补发判定。
        if (packets.isEmpty() && System.currentTimeMillis() - holdStartAt < recoilTime.getValue()) return;

        // 正在使用非食物 / 药水 / 牛奶类物品（弓、盾、望远镜等）时不压包。
        if (isUsingNonConsumable()) {
            releaseAll();
            return;
        }

        // Dynamic 模式仅在有敌人且压包更有利时压包，一旦决定不压就把已压的包补发掉。
        if (mode.is(Mode.Dynamic) && !shouldLagDynamic()) {
            releaseAll();
            return;
        }

        event.cancel();
        if (packets.isEmpty()) holdStartAt = System.currentTimeMillis();
        if (heldPosition == null) heldPosition = mc.player.position();
        packets.add(packet);
    }

    @EventHandler(priority = EventPriority.HIGH)
    private void onPacketReceive(PacketEvent.Receive event) {
        if (!isEnabled() || nullCheck()) return;

        Packet<?> packet = event.getPacket();

        // 服务端传送、自身击退、自身爆炸击退、生命值变化：立刻补发，防止拉回与伤害被本地滞后掩盖。
        if (packet instanceof ClientboundPlayerPositionPacket || packet instanceof ClientboundSetHealthPacket) {
            releaseAll();
            return;
        }

        if (packet instanceof ClientboundSetEntityMotionPacket motion) {
            if (motion.id() == mc.player.getId() && !Vec3.ZERO.equals(motion.movement())) {
                releaseAll();
            }
            return;
        }

        if (packet instanceof ClientboundExplodePacket explosion
                && !Vec3.ZERO.equals(explosion.playerKnockback().orElse(Vec3.ZERO))) {
            releaseAll();
        }
    }

    /**
     * 判断当前发出的包是否需要立刻补发队列中已压的包。
     */
    private boolean shouldFlushOnSend(Packet<?> packet) {
        if (flushOnEntityInteract.getValue()
                && (packet instanceof ServerboundInteractPacket
                || packet instanceof ServerboundAttackPacket
                || packet instanceof ServerboundSpectatorActionPacket
                || packet instanceof ServerboundSwingPacket)) {
            return true;
        }

        if (flushOnBlockInteract.getValue()
                && (packet instanceof ServerboundUseItemOnPacket || packet instanceof ServerboundSignUpdatePacket)) {
            return true;
        }

        if (flushOnAction.getValue() && packet instanceof ServerboundPlayerActionPacket) {
            return true;
        }

        // 资源包应答必须在服务端等待超时前送达。
        return packet instanceof ServerboundResourcePackPacket;
    }

    /**
     * Dynamic 模式的压包条件：敌人附近、服务端所见位置不比自己更近、且未与敌人碰撞箱相交。
     */
    private boolean shouldLagDynamic() {
        if (!isEnemyNearby) return false;
        // 尚未压过包，先压一个以记录位置。
        if (heldPosition == null) return true;

        List<AbstractClientPlayer> enemies = nearbyEnemies(heldPosition);
        if (enemies.isEmpty()) return false;

        AABB serverBox = mc.player.getDimensions(mc.player.getPose()).makeBoundingBox(heldPosition);
        double serverDistance = Double.MAX_VALUE;
        double clientDistance = Double.MAX_VALUE;

        for (AbstractClientPlayer enemy : enemies) {
            if (enemy.getBoundingBox().intersects(serverBox)) return false;
            serverDistance = Math.min(serverDistance, enemy.position().distanceTo(heldPosition));
            clientDistance = Math.min(clientDistance, enemy.position().distanceTo(mc.player.position()));
        }

        // 服务端位置离敌人更近时压包反而会被打到，此时立即放行。
        return serverDistance >= clientDistance;
    }

    /**
     * 收集以 {@code pos} 为中心、{@code Range Max} 范围内的敌人。
     */
    private List<AbstractClientPlayer> nearbyEnemies(Vec3 pos) {
        double range = Math.max(rangeMin.getValue(), rangeMax.getValue());
        List<AbstractClientPlayer> enemies = new ArrayList<>();
        for (AbstractClientPlayer player : mc.level.players()) {
            if (isValidEnemy(player) && player.position().distanceTo(pos) <= range) {
                enemies.add(player);
            }
        }
        return enemies;
    }

    /**
     * 到最近敌人的距离，没有敌人时返回 {@link Double#MAX_VALUE}。
     */
    private double nearestEnemyDistance(Vec3 pos) {
        double nearest = Double.MAX_VALUE;
        for (AbstractClientPlayer player : mc.level.players()) {
            if (isValidEnemy(player)) {
                nearest = Math.min(nearest, player.position().distanceTo(pos));
            }
        }
        return nearest;
    }

    /**
     * 候选过滤与 {@code Backtrack#isValidTarget} 保持一致：排除自身、假人、队友与好友。
     */
    private boolean isValidEnemy(AbstractClientPlayer player) {
        if (player == mc.player || !player.isAlive() || player.isDeadOrDying()) return false;
        if (AntiBot.INSTANCE.isBot(player)) return false;
        if (TargetManager.INSTANCE.isSameTeam(player)) return false;
        return !FriendManager.INSTANCE.isFriend(player);
    }

    /**
     * 当前是否正在使用非食物 / 药水 / 牛奶类物品。
     */
    private boolean isUsingNonConsumable() {
        if (!mc.player.isUsingItem()) return false;
        ItemStack useItem = mc.player.getUseItem();
        return !useItem.has(DataComponents.FOOD) && !useItem.has(DataComponents.CONSUMABLE);
    }

    /**
     * 按入队顺序补发队列中的全部数据包，并重置位置与计时状态。
     */
    private void releaseAll() {
        Packet<?> packet;
        while ((packet = packets.poll()) != null) {
            NetworkUtils.sendPacketNoEvent(packet);
        }
        heldPosition = null;
        holdStartAt = System.currentTimeMillis();
    }

    private void resetState() {
        packets.clear();
        heldPosition = null;
        isEnemyNearby = false;
        holdStartAt = System.currentTimeMillis();
        nextDelay = randomBetween(delayMin.getValue(), delayMax.getValue());
    }

    private static int randomBetween(int first, int second) {
        int min = Math.min(first, second);
        int max = Math.max(first, second);
        return min == max ? min : ThreadLocalRandom.current().nextInt(min, max + 1);
    }

}
