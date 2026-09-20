package com.github.epsilon.modules.impl.combat;

import com.github.epsilon.events.bus.EventHandler;
import com.github.epsilon.events.impl.AttackEntityEvent;
import com.github.epsilon.events.impl.LevelUpdateEvent;
import com.github.epsilon.events.impl.PacketEvent;
import com.github.epsilon.events.impl.PlayerTickEvent;
import com.github.epsilon.modules.Category;
import com.github.epsilon.modules.Module;
import com.github.epsilon.settings.Setting;
import com.github.epsilon.settings.SettingGroup;
import com.github.epsilon.settings.impl.BoolSetting;
import com.github.epsilon.settings.impl.DoubleSetting;
import com.github.epsilon.settings.impl.EnumSetting;
import com.github.epsilon.settings.impl.IntSetting;
import com.github.epsilon.settings.impl.RegistryListSetting;
import it.unimi.dsi.fastutil.ints.Int2IntOpenHashMap;
import it.unimi.dsi.fastutil.ints.IntIterator;
import it.unimi.dsi.fastutil.ints.IntOpenHashSet;
import net.minecraft.client.multiplayer.ClientPacketListener;
import net.minecraft.client.multiplayer.PlayerInfo;
import net.minecraft.client.player.AbstractClientPlayer;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.game.ClientboundAnimatePacket;
import net.minecraft.network.protocol.game.ClientboundMoveEntityPacket;
import net.minecraft.network.protocol.game.ClientboundRemoveEntitiesPacket;
import net.minecraft.network.protocol.game.ClientboundUpdateAttributesPacket;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.Item;

import java.util.List;
import java.util.UUID;

public class AntiBot extends Module {

    public static final AntiBot INSTANCE = new AntiBot();

    private AntiBot() {
        super("Anti Bot", Category.COMBAT);
    }

    private enum Mode {
        Default,
        Advanced
    }

    /**
     * 按实体 id 跟踪的状态在异常服务端持续下发新实体时会累积，
     * 超过该上限即按“实体是否仍在客户端世界”做一次清理。
     */
    private static final int MAX_TRACKED_ENTITIES = 4096;

    private final EnumSetting<Mode> mode = enumSetting("Mode", Mode.Default, this::onModeChanged);

    private final SettingGroup sgAdvanced = settingGroup("Advanced");
    private final SettingGroup sgConditions = sgAdvanced.child("Conditions");

    /** 进阶模式设置共用的依赖。 */
    private final Setting.Dependency advancedDep = () -> mode.is(Mode.Advanced);

    // ── Advanced ────────────────────────────────────────────────────────────

    private final BoolSetting literalNpc = boolSetting("Literal NPC", true, advancedDep).group(sgAdvanced);
    private final BoolSetting notInTabList = boolSetting("Not In Tab List", false, advancedDep).group(sgAdvanced);

    private final BoolSetting invalidGround = boolSetting("Invalid Ground", false, advancedDep).group(sgAdvanced);
    private final IntSetting vlToConsiderAsBot = intSetting("VL To Consider As Bot", 10, 1, 50, 1,
            () -> advancedDep.check() && invalidGround.getValue()).group(sgAdvanced);

    private final BoolSetting alwaysInRadius = boolSetting("Always In Radius", true, advancedDep).group(sgAdvanced);
    private final DoubleSetting alwaysInRadiusRange = doubleSetting("Always In Radius Range", 20.0, 5.0, 30.0, 0.5,
            () -> advancedDep.check() && alwaysInRadius.getValue()).group(sgAdvanced);

    private final BoolSetting age = boolSetting("Age", false, advancedDep).group(sgAdvanced);
    private final IntSetting minimum = intSetting("Minimum", 20, 0, 120, 1,
            () -> advancedDep.check() && age.getValue()).group(sgAdvanced);

    private final BoolSetting armor = boolSetting("Armor", false, advancedDep).group(sgAdvanced);
    private final RegistryListSetting<Item> helmet = itemListSetting("Helmet", List.<Item>of(),
            () -> advancedDep.check() && armor.getValue()).group(sgAdvanced);
    private final RegistryListSetting<Item> chestplate = itemListSetting("Chestplate", List.<Item>of(),
            () -> advancedDep.check() && armor.getValue()).group(sgAdvanced);
    private final RegistryListSetting<Item> leggings = itemListSetting("Leggings", List.<Item>of(),
            () -> advancedDep.check() && armor.getValue()).group(sgAdvanced);
    private final RegistryListSetting<Item> boots = itemListSetting("Boots", List.<Item>of(),
            () -> advancedDep.check() && armor.getValue()).group(sgAdvanced);

    private final BoolSetting nameCheck = boolSetting("Name", true, advancedDep).group(sgAdvanced);
    private final IntSetting nameLengthMin = intSetting("Name Length Min", 3, 1, 32, 1,
            () -> advancedDep.check() && nameCheck.getValue()).group(sgAdvanced);
    private final IntSetting nameLengthMax = intSetting("Name Length Max", 16, 1, 32, 1,
            () -> advancedDep.check() && nameCheck.getValue()).group(sgAdvanced);
    private final BoolSetting validateVanillaChars = boolSetting("Validate Vanilla Chars", false,
            () -> advancedDep.check() && nameCheck.getValue()).group(sgAdvanced);
    private final BoolSetting validateCyrillicChars = boolSetting("Validate Cyrillic Chars", false,
            () -> advancedDep.check() && nameCheck.getValue()).group(sgAdvanced);
    private final BoolSetting validateCjkChars = boolSetting("Validate CJK Chars", false,
            () -> advancedDep.check() && nameCheck.getValue()).group(sgAdvanced);

    // ── Conditions ──────────────────────────────────────────────────────────

    private final BoolSetting noGameMode = boolSetting("No Game Mode", true, advancedDep).group(sgConditions);
    private final BoolSetting illegalPitch = boolSetting("Illegal Pitch", true, advancedDep).group(sgConditions);
    private final BoolSetting fakeEntityId = boolSetting("Fake Entity ID", true, advancedDep).group(sgConditions);
    private final BoolSetting duplicate = boolSetting("Duplicate", false, advancedDep).group(sgConditions);
    private final BoolSetting needHit = boolSetting("Need Hit", false, advancedDep).group(sgConditions);
    private final BoolSetting illegalHealth = boolSetting("Illegal Health", false, advancedDep).group(sgConditions);
    private final BoolSetting swung = boolSetting("Swung", false, advancedDep).group(sgConditions);
    private final BoolSetting critted = boolSetting("Critted", false, advancedDep).group(sgConditions);
    private final BoolSetting attributes = boolSetting("Attributes", false, advancedDep).group(sgConditions);
    private final BoolSetting illegalScale = boolSetting("Illegal Scale", false, advancedDep).group(sgConditions);

    /** 实体 id -> 无效地面违规次数。 */
    private final Int2IntOpenHashMap invalidGroundVl = new Int2IntOpenHashMap();
    /** 曾离开 Always In Radius 半径的实体 id；从未离开者被视为假人。 */
    private final IntOpenHashSet notAlwaysInRadiusSet = new IntOpenHashSet();
    private final IntOpenHashSet hitSet = new IntOpenHashSet();
    private final IntOpenHashSet swungSet = new IntOpenHashSet();
    private final IntOpenHashSet crittedSet = new IntOpenHashSet();
    private final IntOpenHashSet attributesSet = new IntOpenHashSet();

    /**
     * 判断实体是否为假人。
     * <p>
     * {@link Mode#Default} 完全沿用“UUID 不在在线玩家列表即为假人”的旧判定；
     * {@link Mode#Advanced} 使用进阶条件集合。
     */
    public boolean isBot(Entity entity) {
        if (!isEnabled()) return false;
        return mode.is(Mode.Default) ? defaultIsBot(entity) : advancedIsBot(entity);
    }

    private boolean defaultIsBot(Entity entity) {
        return !mc.getConnection().getOnlinePlayerIds().contains(entity.getUUID());
    }

    private boolean advancedIsBot(Entity entity) {
        if (entity == mc.player || !(entity instanceof Player player)) return false;

        ClientPacketListener connection = mc.getConnection();
        if (connection == null) return false;

        if (literalNpc.getValue() && !connection.getOnlinePlayerIds().contains(player.getUUID())) return true;
        if (notInTabList.getValue() && isMissingFromTabList(connection, player)) return true;
        if (invalidGround.getValue() && hasInvalidGround(player)) return true;
        if (alwaysInRadius.getValue() && !notAlwaysInRadiusSet.contains(player.getId())) return true;
        if (age.getValue() && player.tickCount < minimum.getValue()) return true;
        if (armor.getValue() && hasDisallowedArmor(player)) return true;
        if (nameCheck.getValue() && isBotName(player)) return true;

        return anyConditionMatched(connection, player);
    }

    // ── 状态管理 ────────────────────────────────────────────────────────────

    @Override
    protected void onEnable() {
        resetState();
    }

    @Override
    protected void onDisable() {
        resetState();
    }

    private void onModeChanged(Mode newMode) {
        resetState();
    }

    private void resetState() {
        invalidGroundVl.clear();
        notAlwaysInRadiusSet.clear();
        hitSet.clear();
        swungSet.clear();
        crittedSet.clear();
        attributesSet.clear();
    }

    @EventHandler
    private void onLevelUpdate(LevelUpdateEvent event) {
        resetState();
    }

    // ── 数据收集 ────────────────────────────────────────────────────────────

    @EventHandler
    private void onAttack(AttackEntityEvent event) {
        if (!mode.is(Mode.Advanced)) return;
        hitSet.add(event.getEntity().getId());
    }

    @EventHandler
    private void onPlayerTick(PlayerTickEvent.Post event) {
        if (!mode.is(Mode.Advanced) || nullCheck()) return;

        double range = alwaysInRadiusRange.getValue();
        double rangeSquared = range * range;
        for (AbstractClientPlayer player : mc.level.players()) {
            if (player == mc.player) continue;
            if (mc.player.distanceToSqr(player) > rangeSquared) {
                notAlwaysInRadiusSet.add(player.getId());
            }
        }

        pruneTrackedEntities();
    }

    @EventHandler
    private void onPacketReceive(PacketEvent.Receive event) {
        Packet<?> raw = event.getPacket();

        if (raw instanceof ClientboundRemoveEntitiesPacket removal) {
            // 实体卸载：清理所有按实体 id 跟踪的状态，避免集合无限增长
            mc.execute(() -> {
                IntIterator iterator = removal.getEntityIds().iterator();
                while (iterator.hasNext()) {
                    int entityId = iterator.nextInt();
                    invalidGroundVl.remove(entityId);
                    notAlwaysInRadiusSet.remove(entityId);
                    hitSet.remove(entityId);
                    swungSet.remove(entityId);
                    crittedSet.remove(entityId);
                    attributesSet.remove(entityId);
                }
            });
            return;
        }

        if (!mode.is(Mode.Advanced)) return;

        if (raw instanceof ClientboundMoveEntityPacket movement && movement.hasPosition()) {
            if (!invalidGround.getValue()) return;
            mc.execute(() -> trackInvalidGround(movement));
            return;
        }

        if (raw instanceof ClientboundUpdateAttributesPacket attributePacket) {
            int entityId = attributePacket.getEntityId();
            mc.execute(() -> attributesSet.add(entityId));
            return;
        }

        if (raw instanceof ClientboundAnimatePacket animatePacket) {
            int action = animatePacket.getAction();
            int entityId = animatePacket.getId();
            // 挥手与暴击同走 ClientboundAnimatePacket，按 action 区分。
            if (action == ClientboundAnimatePacket.SWING_MAIN_HAND
                    || action == ClientboundAnimatePacket.SWING_OFF_HAND) {
                mc.execute(() -> swungSet.add(entityId));
                return;
            }
            if (action == ClientboundAnimatePacket.CRITICAL_HIT
                    || action == ClientboundAnimatePacket.MAGIC_CRITICAL_HIT) {
                mc.execute(() -> crittedSet.add(entityId));
            }
        }
    }

    /**
     * 统计“贴地却发生了 Y 位移”的违规次数；正常落地时按半衰衰减。
     */
    private void trackInvalidGround(ClientboundMoveEntityPacket movement) {
        if (mc.level == null) return;
        Entity entity = movement.getEntity(mc.level);
        if (entity == null) return;

        int entityId = entity.getId();
        int violations = invalidGroundVl.getOrDefault(entityId, 0);
        if (entity.onGround() && entity.yOld != entity.getY()) {
            invalidGroundVl.put(entityId, violations + 1);
        } else if (!entity.onGround() && violations > 0) {
            int reduced = violations / 2;
            if (reduced <= 0) {
                invalidGroundVl.remove(entityId);
            } else {
                invalidGroundVl.put(entityId, reduced);
            }
        }
    }

    /**
     * 跟踪状态总量超限时，丢弃客户端世界已不存在的实体，保证集合有界。
     */
    private void pruneTrackedEntities() {
        int tracked = hitSet.size() + swungSet.size() + crittedSet.size() + attributesSet.size()
                + notAlwaysInRadiusSet.size() + invalidGroundVl.size();
        if (tracked <= MAX_TRACKED_ENTITIES) return;

        pruneSet(hitSet);
        pruneSet(swungSet);
        pruneSet(crittedSet);
        pruneSet(attributesSet);
        pruneSet(notAlwaysInRadiusSet);

        IntIterator iterator = invalidGroundVl.keySet().iterator();
        while (iterator.hasNext()) {
            if (mc.level.getEntity(iterator.nextInt()) == null) iterator.remove();
        }
    }

    private void pruneSet(IntOpenHashSet set) {
        IntIterator iterator = set.iterator();
        while (iterator.hasNext()) {
            if (mc.level.getEntity(iterator.nextInt()) == null) iterator.remove();
        }
    }

    // ── 判定实现 ────────────────────────────────────────────────────────────

    private boolean hasInvalidGround(Player player) {
        return invalidGroundVl.getOrDefault(player.getId(), 0) >= vlToConsiderAsBot.getValue();
    }

    /**
     * 四个护甲槽位各自的白名单非空且所穿物品不在其中即视为假人；空白名单表示不检查该槽位。
     */
    private boolean hasDisallowedArmor(Player player) {
        return isDisallowedArmor(player, EquipmentSlot.HEAD, helmet)
                || isDisallowedArmor(player, EquipmentSlot.CHEST, chestplate)
                || isDisallowedArmor(player, EquipmentSlot.LEGS, leggings)
                || isDisallowedArmor(player, EquipmentSlot.FEET, boots);
    }

    private boolean isDisallowedArmor(Player player, EquipmentSlot slot, RegistryListSetting<Item> allowed) {
        if (allowed.isEmpty()) return false;
        return !allowed.contains(player.getItemBySlot(slot).getItem());
    }

    /**
     * 名字长度越界，或包含任一已启用字符集之外的字符，即视为假人。
     */
    private boolean isBotName(Player player) {
        String name = player.getScoreboardName();
        if (name == null) return false;

        int min = Math.min(nameLengthMin.getValue(), nameLengthMax.getValue());
        int max = Math.max(nameLengthMin.getValue(), nameLengthMax.getValue());
        if (name.length() < min || name.length() > max) return true;

        boolean vanilla = validateVanillaChars.getValue();
        boolean cyrillic = validateCyrillicChars.getValue();
        boolean cjk = validateCjkChars.getValue();
        if (!vanilla && !cyrillic && !cjk) return false;

        for (int index = 0; index < name.length(); index++) {
            int codePoint = name.codePointAt(index);
            boolean valid = (vanilla && isVanillaChar(codePoint))
                    || (cyrillic && codePoint >= 0x0400 && codePoint <= 0x052F)
                    || (cjk && codePoint >= 0x4E00 && codePoint <= 0x9FA5);
            if (!valid) return true;
            if (Character.charCount(codePoint) == 2) index++;
        }
        return false;
    }

    private static boolean isVanillaChar(int codePoint) {
        return (codePoint >= '0' && codePoint <= '9')
                || (codePoint >= 'a' && codePoint <= 'z')
                || (codePoint >= 'A' && codePoint <= 'Z')
                || codePoint == '_';
    }

    private boolean anyConditionMatched(ClientPacketListener connection, Player player) {
        if (noGameMode.getValue() && isMissingGameMode(connection, player)) return true;
        if (illegalPitch.getValue() && isIllegalPitch(player)) return true;
        if (fakeEntityId.getValue() && isFakeEntityId(player)) return true;
        if (duplicate.getValue() && isDuplicate(connection, player)) return true;
        if (needHit.getValue() && !hitSet.contains(player.getId())) return true;
        if (illegalHealth.getValue() && isIllegalHealth(player)) return true;
        if (swung.getValue() && !swungSet.contains(player.getId())) return true;
        if (critted.getValue() && !crittedSet.contains(player.getId())) return true;
        if (attributes.getValue() && !attributesSet.contains(player.getId())) return true;
        return illegalScale.getValue() && isIllegalScale(player);
    }

    /**
     * 玩家存在于服务器玩家列表，却未出现在 Tab 列表中——服务端隐藏 NPC 的典型特征。
     */
    private boolean isMissingFromTabList(ClientPacketListener connection, Player player) {
        UUID uuid = player.getUUID();
        for (PlayerInfo info : connection.getListedOnlinePlayers()) {
            if (info.getProfile().id().equals(uuid)) return false;
        }
        return true;
    }

    private boolean isMissingGameMode(ClientPacketListener connection, Player player) {
        PlayerInfo info = connection.getPlayerInfo(player.getUUID());
        return info == null || info.getGameMode() == null;
    }

    private boolean isIllegalPitch(Player player) {
        return Math.abs(player.getXRot()) > 90.0f;
    }

    private boolean isFakeEntityId(Player player) {
        return player.getId() < 0 || player.getId() > 1_000_000_000;
    }

    /**
     * 在线玩家列表中存在同名但 UUID 不同的条目。
     */
    private boolean isDuplicate(ClientPacketListener connection, Player player) {
        String name = player.getScoreboardName();
        if (name == null) return false;
        for (PlayerInfo info : connection.getOnlinePlayers()) {
            if (info.getProfile().id().equals(player.getUUID())) continue;
            if (name.equals(info.getProfile().name())) return true;
        }
        return false;
    }

    private boolean isIllegalHealth(Player player) {
        return player.getHealth() > mc.player.getMaxHealth();
    }

    private boolean isIllegalScale(Player player) {
        return player.getAttributeValue(Attributes.SCALE) != 1.0;
    }

}
