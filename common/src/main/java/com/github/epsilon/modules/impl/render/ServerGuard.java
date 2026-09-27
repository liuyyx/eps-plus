package com.github.epsilon.modules.impl.render;

import com.github.epsilon.events.bus.EventHandler;
import com.github.epsilon.events.bus.EventPriority;
import com.github.epsilon.events.impl.PacketEvent;
import com.github.epsilon.events.impl.PlayerTickEvent;
import com.github.epsilon.events.impl.Render2DEvent;
import com.github.epsilon.graphics.LuminRenderSystem;
import com.github.epsilon.graphics.renderers.RectRenderer;
import com.github.epsilon.modules.Category;
import com.github.epsilon.modules.Module;
import com.github.epsilon.settings.impl.BoolSetting;
import com.github.epsilon.settings.impl.ColorSetting;
import com.github.epsilon.settings.impl.DoubleSetting;
import com.github.epsilon.settings.impl.IntSetting;
import com.github.epsilon.utils.player.ChatUtils;
import com.google.common.base.Suppliers;
import net.minecraft.ChatFormatting;
import net.minecraft.client.multiplayer.ClientPacketListener;
import net.minecraft.client.multiplayer.PlayerInfo;
import net.minecraft.client.player.AbstractClientPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.game.ClientboundBlockUpdatePacket;
import net.minecraft.network.protocol.game.ClientboundHurtAnimationPacket;
import net.minecraft.network.protocol.game.ClientboundPlayerPositionPacket;
import net.minecraft.network.protocol.game.ClientboundPlayerRotationPacket;
import net.minecraft.network.protocol.game.ServerboundAttackPacket;
import net.minecraft.network.protocol.game.ServerboundUseItemOnPacket;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.PositionMoveRotation;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;

import java.awt.Color;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.function.Supplier;

/**
 * 反作弊检测：把「服务端没有按预期回应」的四种情况打成聊天栏红色警告，
 * 并沿整圈屏幕边缘打出闪烁的渐变光晕（向内淡出）。**只收包、不发包**，
 * 不改变任何上行流量，因此不增加被反作弊盯上的面。
 *
 * <p>四类警告各自独立开关，互不影响：
 * <ul>
 *   <li><b>吞方块</b>——我方发出放置包后，服务端把那一格回成空气/可替换方块。
 *       客户端因为本地预测会先看到方块出现（{@code placedOk} 一切正常），
 *       只有服务端这个回撤包能暴露真相。</li>
 *   <li><b>吞刀</b>——我方发出攻击包后，迟迟收不到服务端广播的受伤动画。
 *       ⚠️ 判定**必须**用 {@link ClientboundHurtAnimationPacket}，不能用
 *       {@code target.hurtTime}：{@code MultiPlayerGameMode.attack} 在发包之后会本地执行
 *       {@code player.attack(target)}（本地预测），无条件把 {@code hurtTime} 置起来，
 *       所以那个字段在"服务端有没有接受"这个问题上信息量为零。</li>
 *   <li><b>回弹</b>——服务端强制把玩家拉回/扭转。位置回弹用
 *       {@link PositionMoveRotation#calculateAbsolute} 把包里的相对量换算成绝对坐标，
 *       与本地位置比对；差异超过 {@code Rubberband Range} 的按「传送」放行，
 *       否则就是反作弊纠正（setback）。</li>
 *   <li><b>假人</b>——服务端世界里的玩家不在下发的在线玩家列表（可叠加 Tab 列表校验）。
 *       判定条件与 {@link com.github.epsilon.modules.impl.combat.AntiBot} 的 Default 模式一致。</li>
 * </ul>
 *
 * <p>聊天栏刻意**不合并、不静默**：每次触发都是一条独立的红色消息，
 * 并带该类型的累计序号，方便回看「这一波被吞了几次」。
 * {@code Alert Cooldown} 默认 0；调大它才会开始丢弃冷却期内的重复警告。
 */
public class ServerGuard extends Module {

    public static final ServerGuard INSTANCE = new ServerGuard();

    private ServerGuard() {
        super("Server Guard", Category.RENDER);
    }

    // ── 设置 ────────────────────────────────────────────────────────────────

    private final BoolSetting swallowBlock = boolSetting("Swallow Block", true);
    private final BoolSetting swallowAttack = boolSetting("Swallow Attack", true);
    private final BoolSetting rubberbandWarning = boolSetting("Rubberband Warning", true);
    private final DoubleSetting rubberbandRange = doubleSetting("Rubberband Range", 8.0, 1.0, 32.0, 0.5);
    private final BoolSetting botWarning = boolSetting("Bot Warning", true);
    private final BoolSetting tabListCheck = boolSetting("Tab List Check", false);
    private final DoubleSetting botRange = doubleSetting("Bot Range", 64.0, 8.0, 128.0, 4.0);
    private final IntSetting alertCooldown = intSetting("Alert Cooldown", 0, 0, 5000, 50);

    private final BoolSetting flash = boolSetting("Flash", true);
    private final IntSetting flashDuration = intSetting("Flash Duration", 1200, 200, 5000, 100);
    private final DoubleSetting flashThickness = doubleSetting("Flash Thickness", 0.30, 0.05, 1.0, 0.05);
    private final ColorSetting flashColor = colorSetting("Flash Color", new Color(255, 42, 42, 255), true);

    // ── 内部状态 ────────────────────────────────────────────────────────────

    /** 近期放置过的方块位置 -> 放置时刻（ms）；用于识别服务端把方块回撤成空气。 */
    private final Map<BlockPos, Long> placedBlocks = new HashMap<>();
    /** 已发出攻击包、尚未看到受伤动画的实体 id -> 发包时刻（ms）。 */
    private final Map<Integer, Long> pendingAttacks = new HashMap<>();
    /** 已经警告过的假人实体 id，避免同一个人反复刷屏。 */
    private final Set<Integer> warnedBots = new HashSet<>();
    /** 每类警告的累计次数，用作消息里的序号。 */
    private final Map<String, Integer> counters = new HashMap<>();
    /** 每类警告上次发出的时刻（ms），用于冷却。 */
    private final Map<String, Long> lastAlertAt = new HashMap<>();

    /** 最近一次警告的时刻（ms）；屏幕边缘光晕以它为起点倒计时。 */
    private long flashStartAt = 0L;

    /** 放置记录保留时长：超过这么久的方块变更不再算在「我刚放的」头上。 */
    private static final long PLACED_TTL_MS = 3_000L;
    /** 攻击包发出后等多久没有受伤动画就判为被吞。 */
    private static final long ATTACK_TIMEOUT_MS = 1_000L;
    /** 刚进世界的头若干刻位置包一定是落地同步，别当成回弹。 */
    private static final int JOIN_GRACE_TICKS = 20;

    private static final String KEY_BLOCK = "block";
    private static final String KEY_ATTACK = "attack";
    private static final String KEY_RUBBERBAND = "rubberband";
    private static final String KEY_BOT = "bot";

    /** 画屏幕边缘光晕用的批量渲染器；首次用到时才创建。 */
    private final Supplier<RectRenderer> rectRendererSupplier = Suppliers.memoize(RectRenderer::create);

    // ── 事件 ────────────────────────────────────────────────────────────────

    /**
     * 记录我方发出的放置包与攻击包。攻击这里刻意读取
     * {@link ServerboundAttackPacket} 而不是 {@code AttackEntityEvent}：
     * 后者在 {@code MultiPlayerGameMode.attack} 的 HEAD，还可能被别的模块取消，
     * 只有真正上了网的这个包才代表「我方请求了这次攻击」。
     */
    @EventHandler
    private void onPacketSend(PacketEvent.Send event) {
        if (nullCheck()) return;
        Packet<?> packet = event.getPacket();

        if (swallowBlock.getValue() && packet instanceof ServerboundUseItemOnPacket placement) {
            BlockHitResult hit = placement.getHitResult();
            if (hit == null || hit.getType() == HitResult.Type.MISS) return;
            placedBlocks.put(hit.getBlockPos().immutable(), System.currentTimeMillis());
            return;
        }

        if (swallowAttack.getValue() && packet instanceof ServerboundAttackPacket attack) {
            pendingAttacks.put(attack.entityId(), System.currentTimeMillis());
        }
    }

    /**
     * 判定服务端回撤的方块、服务端拉回，同时清掉已经收到受伤动画的攻击。
     *
     * <p>注意 {@code PacketEvent.Receive} 跑在 HEAD，客户端世界尚未应用这个包，
     * 所以只能读包里的 {@code state}，不能去查 {@code mc.level}。
     *
     * <p>优先级拉满是为了先于 {@code Velocity} / {@code FakeLag} 之类的模块看到位置包 ——
     * 它们会把 {@link ClientboundPlayerPositionPacket} 拦下来攒延迟，之后我们就收不到了。
     */
    @EventHandler(priority = EventPriority.HIGHEST)
    private void onPacketReceive(PacketEvent.Receive event) {
        if (nullCheck()) return;
        Packet<?> packet = event.getPacket();

        if (swallowBlock.getValue() && packet instanceof ClientboundBlockUpdatePacket change) {
            BlockPos pos = change.getPos();
            Long placedAt = placedBlocks.remove(pos);
            if (placedAt == null) return;

            BlockState state = change.getBlockState();
            boolean passable = state == null || state.isAir() || state.canBeReplaced();
            if (!passable) return;

            alert(KEY_BLOCK, "服务器吞方块", String.format(Locale.ROOT,
                    "位置 %d, %d, %d（发出 %d ms 后被服务端回成空气）",
                    pos.getX(), pos.getY(), pos.getZ(), System.currentTimeMillis() - placedAt));
            return;
        }

        if (swallowAttack.getValue() && packet instanceof ClientboundHurtAnimationPacket hurt) {
            pendingAttacks.remove(hurt.id());
            return;
        }

        if (rubberbandWarning.getValue() && packet instanceof ClientboundPlayerPositionPacket position) {
            onSetback(position);
            return;
        }

        if (rubberbandWarning.getValue() && packet instanceof ClientboundPlayerRotationPacket rotation) {
            alert(KEY_RUBBERBAND, "服务器回弹", String.format(Locale.ROOT,
                    "服务端强行扭转视角 -> yaw %.1f / pitch %.1f", rotation.yRot(), rotation.xRot()));
        }
    }

    @EventHandler
    private void onPlayerTick(PlayerTickEvent.Post event) {
        if (nullCheck()) return;
        long now = System.currentTimeMillis();

        if (swallowBlock.getValue() && !placedBlocks.isEmpty()) {
            placedBlocks.values().removeIf(at -> now - at > PLACED_TTL_MS);
        }

        if (swallowAttack.getValue() && !pendingAttacks.isEmpty()) {
            checkSwallowedAttacks(now);
        }

        if (botWarning.getValue()) {
            scanBots();
        }
    }

    // ── 判定 ────────────────────────────────────────────────────────────────

    /**
     * 服务端把玩家拉回（反作弊 setback）。
     *
     * <p>包里的坐标可能是相对的（{@code Relative.X/Y/Z}），所以先用
     * {@link PositionMoveRotation#calculateAbsolute} 与本地状态合成出「服务端想要我站的位置」，
     * 再和本地位置比对：差得远的是传送（{@code /spawn}、切维度），差得近的才是纠正。
     */
    private void onSetback(ClientboundPlayerPositionPacket packet) {
        if (mc.player.tickCount < JOIN_GRACE_TICKS) return;

        PositionMoveRotation absolute = PositionMoveRotation.calculateAbsolute(
                PositionMoveRotation.of(mc.player), packet.change(), packet.relatives());
        Vec3 target = absolute.position();
        Vec3 local = mc.player.position();
        double distance = local.distanceTo(target);

        if (distance > rubberbandRange.getValue()) return;

        alert(KEY_RUBBERBAND, "服务器回弹", String.format(Locale.ROOT,
                "被拉回 (%.2f, %.2f, %.2f)，与本地相差 %.2f 格",
                target.x, target.y, target.z, distance));
    }

    /**
     * 攻击包发出超过 {@link #ATTACK_TIMEOUT_MS} 仍未等到服务端的受伤动画，即判为被吞。
     */
    private void checkSwallowedAttacks(long now) {
        Iterator<Map.Entry<Integer, Long>> iterator = pendingAttacks.entrySet().iterator();
        while (iterator.hasNext()) {
            Map.Entry<Integer, Long> entry = iterator.next();
            long elapsed = now - entry.getValue();
            if (elapsed <= ATTACK_TIMEOUT_MS) continue;

            int entityId = entry.getKey();
            iterator.remove();

            Entity target = mc.level.getEntity(entityId);
            alert(KEY_ATTACK, "服务器吞刀", target == null
                    ? String.format(Locale.ROOT, "目标 id=%d 已不可见，攻击包发出 %d ms 无受伤反馈",
                    entityId, elapsed)
                    : String.format(Locale.ROOT, "目标 %s（%.2f 格）攻击包发出 %d ms 无受伤反馈",
                    target.getName().getString(), mc.player.distanceTo(target), elapsed));
        }
    }

    /**
     * 扫描附近假人。
     *
     * <p>判定条件与 {@link com.github.epsilon.modules.impl.combat.AntiBot} 的 Default 模式
     * 及其「Not In Tab List」条件一致：UUID 不在服务端下发的在线玩家列表里即为假人，
     * 可选再校验一次 Tab 列表。
     */
    private void scanBots() {
        ClientPacketListener connection = mc.getConnection();
        if (connection == null) return;

        // 实体 id 会被复用，玩家卸载后要放行，否则同 id 的新玩家不会再被警告
        warnedBots.removeIf(id -> mc.level.getEntity(id) == null);

        double range = botRange.getValue();
        double rangeSquared = range * range;

        for (AbstractClientPlayer player : mc.level.players()) {
            if (player == mc.player) continue;
            if (mc.player.distanceToSqr(player) > rangeSquared) continue;
            if (!isBot(connection, player)) continue;
            if (!warnedBots.add(player.getId())) continue;

            alert(KEY_BOT, "服务器假人", String.format(Locale.ROOT, "%s（%.2f 格）不在服务端玩家列表",
                    player.getName().getString(), mc.player.distanceTo(player)));
        }
    }

    private boolean isBot(ClientPacketListener connection, Player player) {
        UUID uuid = player.getUUID();
        if (!connection.getOnlinePlayerIds().contains(uuid)) return true;
        if (!tabListCheck.getValue()) return false;

        for (PlayerInfo info : connection.getListedOnlinePlayers()) {
            if (info.getProfile().id().equals(uuid)) return false;
        }
        return true;
    }

    // ── 屏幕边缘闪烁渲染 ────────────────────────────────────────────────────

    /**
     * 沿**整圈屏幕边缘**打出闪烁的红色光晕，向内（屏幕中心方向）渐变淡出。
     *
     * <p>四条边各画一条渐变带：贴着边的那一侧是不透明的警示色，向内侧渐隐到全透明。
     * 四条带在四角自然叠出更浓的一层，所以整圈边缘都是实的、没有缺口。
     * 强度 = 「离上次警告越久越淡」× 「正弦脉动的明暗」。
     */
    @EventHandler
    private void onRender2D(Render2DEvent.HUD event) {
        if (nullCheck() || !flash.getValue() || flashStartAt == 0L) return;

        long now = System.currentTimeMillis();
        long elapsed = now - flashStartAt;
        int duration = flashDuration.getValue();
        if (elapsed < 0 || elapsed > duration) return;

        float life = 1.0f - (float) elapsed / duration;
        float blink = 0.35f + 0.65f * Math.abs((float) Math.sin(now * 0.012D));

        Color base = flashColor.getValue();
        int alpha = Mth.clamp((int) (base.getAlpha() * life * blink), 0, 255);
        if (alpha <= 2) return;

        Color edge = new Color(base.getRed(), base.getGreen(), base.getBlue(), alpha);
        Color inner = new Color(base.getRed(), base.getGreen(), base.getBlue(), 0);

        float width = LuminRenderSystem.getScaledWidth();
        float height = LuminRenderSystem.getScaledHeight();
        float band = Math.min(width, height) * flashThickness.getValue().floatValue();
        if (band <= 0.0f) return;

        RectRenderer renderer = rectRendererSupplier.get();
        renderer.addVerticalGradient(0.0f, 0.0f, width, band, edge, inner);           // 上边缘
        renderer.addVerticalGradient(0.0f, height - band, width, band, inner, edge);  // 下边缘
        renderer.addHorizontalGradient(0.0f, 0.0f, band, height, edge, inner);        // 左边缘
        renderer.addHorizontalGradient(width - band, 0.0f, band, height, inner, edge);// 右边缘
        renderer.drawAndClear();
    }

    // ── 输出 ────────────────────────────────────────────────────────────────

    /**
     * 往聊天栏发一条红色警告，并点亮屏幕边缘的闪烁光晕。
     *
     * <p>刻意走不带 hash 的重载 —— 每次触发都是**独立的一条新消息**，不做合并。
     * 冷却到了才会静默丢弃（默认 0，即不冷却）。
     */
    private void alert(String key, String title, String detail) {
        long now = System.currentTimeMillis();
        int cooldown = alertCooldown.getValue();
        Long last = lastAlertAt.get(key);
        if (cooldown > 0 && last != null && now - last < cooldown) return;
        lastAlertAt.put(key, now);

        int count = counters.merge(key, 1, Integer::sum);
        MutableComponent message = Component.empty()
                .append(Component.literal("⚠ " + title + " #" + count).withStyle(ChatFormatting.RED))
                .append(Component.literal(" " + detail).withStyle(ChatFormatting.RED));

        ChatUtils.addChatMessage(true, message);
        flashStartAt = now;
    }

    // ── 生命周期 ────────────────────────────────────────────────────────────

    @Override
    protected void onEnable() {
        resetState();
    }

    @Override
    protected void onDisable() {
        resetState();
    }

    private void resetState() {
        placedBlocks.clear();
        pendingAttacks.clear();
        warnedBots.clear();
        counters.clear();
        lastAlertAt.clear();
        flashStartAt = 0L;
    }

}
