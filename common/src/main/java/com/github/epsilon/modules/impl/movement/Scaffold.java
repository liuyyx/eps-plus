package com.github.epsilon.modules.impl.movement;

import com.github.epsilon.assets.i18n.EpsilonTranslations;
import com.github.epsilon.events.bus.EventBus;
import com.github.epsilon.events.bus.EventHandler;
import com.github.epsilon.events.bus.EventPriority;
import com.github.epsilon.events.bus.listeners.ConsumerListener;
import com.github.epsilon.events.impl.ClientTickEvent;
import com.github.epsilon.events.impl.KeyboardInputEvent;
import com.github.epsilon.events.impl.LevelUpdateEvent;
import com.github.epsilon.events.impl.PacketEvent;
import com.github.epsilon.events.impl.PlaceBlockEvent;
import com.github.epsilon.events.impl.PlayerTickEvent;
import com.github.epsilon.events.impl.Render2DEvent;
import com.github.epsilon.events.impl.Render3DEvent;
import com.github.epsilon.events.impl.SendPositionEvent;
import com.github.epsilon.events.impl.StartUseItemEvent;
import com.github.epsilon.graphics.schedulers.render3d.Render3DScheduler;
import com.github.epsilon.managers.NotificationManager;
import com.github.epsilon.managers.rotation.RotationManager;
import com.github.epsilon.modules.Category;
import com.github.epsilon.modules.Module;
import com.github.epsilon.modules.impl.movement.scaffold.HeypixelScaffold;
import com.github.epsilon.modules.impl.movement.scaffold.HypixelScaffold;
import com.github.epsilon.modules.impl.movement.scaffold.ScaffoldPolarCoordinator;
import com.github.epsilon.modules.impl.movement.scaffold.ScaffoldSettings;
import com.github.epsilon.modules.impl.movement.scaffold.clicking.Clicker;
import com.github.epsilon.modules.impl.movement.scaffold.elowen.ElowenPlaceMode;
import com.github.epsilon.modules.impl.movement.scaffold.elowen.ElowenRotationMode;
import com.github.epsilon.modules.impl.movement.scaffold.elowen.ElowenScaffold;
import com.github.epsilon.modules.impl.movement.scaffold.ravenb4.RavenB4Scaffold;
import com.github.epsilon.modules.impl.movement.scaffold.rotation.MovementCorrection;
import com.github.epsilon.modules.impl.movement.scaffold.rotation.RotationHelper;
import com.github.epsilon.modules.impl.movement.scaffold.rotation.RotationProperty;
import com.github.epsilon.modules.impl.movement.scaffold.rotation.model.impl.InstantRotationModel;
import com.github.epsilon.modules.impl.movement.scaffold.slot.SlotHelper;
import com.github.epsilon.modules.impl.movement.scaffold.util.EntityUtils;
import com.github.epsilon.settings.Setting;
import com.github.epsilon.settings.SettingGroup;
import com.github.epsilon.settings.impl.*;
import com.github.epsilon.utils.math.MathUtils;
import com.github.epsilon.utils.player.FallingPlayer;
import com.github.epsilon.utils.player.FindItemResult;
import com.github.epsilon.utils.player.InvUtils;
import com.github.epsilon.utils.player.RotationUtility;
import com.github.epsilon.utils.player.SkipTickUtility;
import com.github.epsilon.utils.render.animation.Easing;
import com.github.epsilon.utils.rotation.RaytraceUtils;
import com.github.epsilon.utils.rotation.Rot2f;
import com.github.epsilon.utils.rotation.RotationUtils;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.network.protocol.game.ClientboundPlayerPositionPacket;
import net.minecraft.network.protocol.game.ServerboundMovePlayerPacket;
import net.minecraft.network.protocol.game.ServerboundSwingPacket;
import net.minecraft.network.protocol.game.ServerboundUseItemPacket;
import net.minecraft.util.Mth;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.StandingAndWallBlockItem;
import net.minecraft.world.level.block.*;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;

import java.awt.*;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;

public class Scaffold extends Module {

    public static final Scaffold INSTANCE = new Scaffold();

    private Scaffold() {
        super("Scaffold", Category.MOVEMENT);
        EventBus.INSTANCE.subscribe(new ConsumerListener<>(Render3DEvent.class,
                event -> {
                    // 该监听器在构造器里注册，不随模块启停订阅/退订 ⇒ 必须自己判启用，
                    // 否则模块关闭后仍会画 Elowen/Raven 的残留盒子。
                    if (!isEnabled()) return;
                    // Elowen 的 onRenderWorld：只画当前放置目标（elowen 用 B 字段，AABB 外扩 0.002）。
                    if (isElowenMode()) {
                        BlockPos elowenTarget = elowenScaffold.getRenderBlock();
                        if (render.getValue() && elowenTarget != null) {
                            AABB elowenBox = new AABB(elowenTarget).inflate(0.002);
                            Render3DScheduler.INSTANCE.addFilledBox(elowenBox, sideColor.getValue());
                            Render3DScheduler.INSTANCE.addOutlineBox(elowenBox, lineColor.getValue());
                        }
                    }

                    // Raven B4 的 onRenderWorld：画当前放置目标（原 ScaffoldModule.B）+ 最近放置高亮。
                    if (isRavenB4Mode() && render.getValue()) {
                        BlockPos ravenTarget = ravenScaffold.getRenderBlock();
                        if (ravenTarget != null) {
                            AABB ravenBox = new AABB(ravenTarget).inflate(0.002);
                            Render3DScheduler.INSTANCE.addFilledBox(ravenBox, sideColor.getValue());
                            Render3DScheduler.INSTANCE.addOutlineBox(ravenBox, lineColor.getValue());
                        }
                        if (ravenHighlightBlocks.getValue() && !ravenScaffold.getPlacedBlocks().isEmpty()) {
                            long now = System.currentTimeMillis();
                            ravenScaffold.getPlacedBlocks().entrySet().removeIf(entry -> now - Math.abs(entry.getValue()) >= 500L);
                            for (var entry : ravenScaffold.getPlacedBlocks().entrySet()) {
                                float progress = Math.min((now - Math.abs(entry.getValue())) / 500.0f, 1.0f);
                                int alpha = (int) (255.0f - progress * 255.0f);
                                if (alpha <= 0) continue;
                                AABB box = new AABB(entry.getKey()).inflate(0.002);
                                Render3DScheduler.INSTANCE.addFilledBox(box, new Color(sideColor.getValue().getRed(),
                                        sideColor.getValue().getGreen(), sideColor.getValue().getBlue(), alpha));
                            }
                        }
                    }

                    if (!render.getValue() || renderBoxes.isEmpty()) return;

                    long time = System.currentTimeMillis();
                    // fadeTime 设置下界是 0 ⇒ 0 作除数会产生 NaN（Mth.clamp 对 NaN 无效），
                    // 进而把 NaN 写进 AABB 与颜色。兜底为 1ms。
                    long fadeTime = Math.max(1L, this.fadeTime.getValue().longValue());

                    renderBoxes.removeIf(box -> time - box.startTime() > fadeTime);

                    for (RenderInfo box : renderBoxes) {
                        float progress = Mth.clamp((float) (time - box.startTime()) / fadeTime, 0.0f, 1.0f);

                        double scale = 1.0;
                        if (box.shrink()) {
                            scale = 1.0 - Easing.EASE_IN_OUT_EXPO.getFunction().apply(progress);
                            if (scale < 0) scale = 0;
                        }

                        float alphaFactor = box.fade() ? Mth.clamp(1.0f - progress, 0.0f, 1.0f) : 1.0f;

                        Color sideColor = box.sideColor();
                        Color lineColor = box.lineColor();

                        Color side = new Color(sideColor.getRed(), sideColor.getGreen(), sideColor.getBlue(), (int) (sideColor.getAlpha() * alphaFactor));
                        Color line = new Color(lineColor.getRed(), lineColor.getGreen(), lineColor.getBlue(), (int) (lineColor.getAlpha() * alphaFactor));

                        AABB renderBox = box.aabb;
                        if (box.shrink()) {
                            renderBox = AABB.ofSize(renderBox.getCenter(), renderBox.getXsize() * scale, renderBox.getYsize() * scale, renderBox.getZsize() * scale);
                        }

                        Render3DScheduler.INSTANCE.addFilledBox(renderBox, side);
                        Render3DScheduler.INSTANCE.addOutlineBox(renderBox, line);
                    }
                }
        ));
        // OpenPal 由 mod 启动阶段调用 SlotHelper.setInstance()；本仓库的模块单例就是启动点。
        SlotHelper.setInstance();
    }

    private enum Mode {
        TellyBridge,
        GodBridge,
        /**
         * OpenPal 的 Uitems 搭路（见 {@code scaffold/HeypixelScaffold}）。
         * 与 {@link #GodBridge} 的 Polar 变体并列，两者互不影响。
         */
        Heypixel,
        /** OpenPal 的 Uitems 搭路（Hypixel 变体，见 {@code scaffold/HypixelScaffold}）。 */
        Hypixel,
        /**
         * Elowen 的 Scaffold（见 {@code scaffold/elowen/ElowenScaffold}，逐行移植自
         * {@code com.elowen.o.Y.Z.Scaffold}）：4 个放置模式 × 5 个旋转模式，
         * 全部走静默托管角（不写玩家真实视角）。
         */
        Elowen,
        /**
         * Raven B4 Scaffold (ravenb4/), copied from Elowen template.
         */
        RavenB4
    }

    private enum RotationMode {
        Static,
        Hypixel,
        Heypixel
    }

    private enum RaytraceMode {
        Normal,
        Strict
    }

    private enum SwapMode {
        None,
        Normal,
        Silent,
        InvSwitch
    }

    /**
     * 神桥（GodBridge）内部的实现变体。
     * Classic 沿用 Epsilon 原有手感；Polar 走 LiquidBounce nextgen 0.40.1 的 GodBridge 链路
     * （逐行移植，见 NOTICE.md 的 LiquidBounce 段与 tmp/scaffold-polar-port-map.md）。
     */
    public enum GodBridgeVariant {
        Classic,
        Polar
    }

    /** LB {@code ModuleScaffold.ScaffoldRotationValueGroup.RotationTimingMode}，三个值与顺序照搬。 */
    public enum RotationTiming {
        Normal,
        OnTick,
        OnTickSnap
    }

    /** LB {@code RotationsValueGroup} 的 {@code AngleSmooth} 选择；本移植只搬 Linear / Sigmoid 两项。 */
    public enum AngleSmoothMode {
        Linear,
        Sigmoid
    }

    /** LB {@code utils/block/SwingMode}，四个值与语义照搬（决定挥手发给谁）。 */
    public enum SwingMode {
        DoNotHide,
        HideForBoth,
        HideForClient,
        HideForServer
    }

    private final RegistryListSetting<Block> blacklistedBlocks = blockListSetting("Blacklisted Blocks", List.of(
            Blocks.AIR,
            Blocks.WATER,
            Blocks.LAVA,
            Blocks.ENCHANTING_TABLE,
            Blocks.GLASS_PANE,
            Blocks.IRON_BARS,
            Blocks.SNOW,
            Blocks.COAL_ORE,
            Blocks.DIAMOND_ORE,
            Blocks.EMERALD_ORE,
            Blocks.CHEST,
            Blocks.TRAPPED_CHEST,
            Blocks.TORCH,
            Blocks.ANVIL,
            Blocks.NOTE_BLOCK,
            Blocks.JUKEBOX,
            Blocks.TNT,
            Blocks.GOLD_ORE,
            Blocks.IRON_ORE,
            Blocks.LAPIS_ORE,
            Blocks.STONE_PRESSURE_PLATE,
            Blocks.LIGHT_WEIGHTED_PRESSURE_PLATE,
            Blocks.HEAVY_WEIGHTED_PRESSURE_PLATE,
            Blocks.STONE_BUTTON,
            Blocks.LEVER,
            Blocks.TALL_GRASS,
            Blocks.TRIPWIRE,
            Blocks.TRIPWIRE_HOOK,
            Blocks.RAIL,
            Blocks.CORNFLOWER,
            Blocks.RED_MUSHROOM,
            Blocks.BROWN_MUSHROOM,
            Blocks.VINE,
            Blocks.SUNFLOWER,
            Blocks.LADDER,
            Blocks.FURNACE,
            Blocks.SAND,
            Blocks.CACTUS,
            Blocks.DISPENSER,
            Blocks.DROPPER,
            Blocks.CRAFTING_TABLE,
            Blocks.COBWEB,
            Blocks.PUMPKIN,
            Blocks.COBBLESTONE_WALL,
            Blocks.OAK_FENCE,
            Blocks.REDSTONE_TORCH,
            Blocks.FLOWER_POT,
            Blocks.SCAFFOLDING
    ));
    private final BoolSetting toggleOnTeleport = boolSetting("Toggle On Teleport", false);
    private final EnumSetting<Mode> mode = enumSetting("Mode", Mode.TellyBridge);
    private final EnumSetting<SwapMode> swapMode = enumSetting("Swap Mode", SwapMode.Normal);
    private final BoolSetting swapBack = boolSetting("Swap Back", true, () -> swapMode.is(SwapMode.Normal));
    /**
     * 神桥 Classic 的准星吸附。OpenPal 侧同一个 {@code Snap} 也服务 Uitems 两个模式
     * （依赖 {@code uitemsDependency && !Uitems Telly}，见 §5.1），故这里并上一条 or 条件。
     * 该设置不属于 {@code Uitems} 分组：{@code GodBridge(Classic)} 下它仍要显示在通用设置里。
     */
    private final BoolSetting snap = boolSetting("Snap", false, () -> mode.is(Mode.GodBridge) || (isUitemsMode() && !this.uitemsTelly.getValue()));
    private final BoolSetting degrees45 = boolSetting("45 Degrees", false);
    private final EnumSetting<RotationMode> rotationMode = enumSetting("Rotation Mode", RotationMode.Static);
    private final EnumSetting<RaytraceMode> raytrace = enumSetting("Raytrace Mode", RaytraceMode.Normal);
    private final IntSetting rotationSpeed = intSetting("Rotation Speed", 127, 10, 180, 10, () -> !rotationMode.is(RotationMode.Hypixel));
    private final IntSetting rotationSpeed2 = intSetting("Rotation Speed 2", 36, 10, 180, 10, () -> rotationMode.is(RotationMode.Heypixel));
    private final IntSetting rotationBackSpeed = intSetting("Rotation Back Speed", 180, 10, 180, 10, () -> mode.is(Mode.TellyBridge));
    private final IntSetting tellyTicks = intSetting("Telly Ticks", 1, 0, 6, 1, () -> mode.is(Mode.TellyBridge));
    /**
     * 放置后的冷却刻数（与 leader 的 Place Delay 同义，默认 1、范围 0~5）。
     * 缺少节流时只要方块搜索成功就每刻放置，形成完全规律的时序，
     * Matrix 的 sfd.place.t（scaffold place timing）会稳定判违规。
     */
    private final IntSetting placeDelay = intSetting("Place Delay", 1, 0, 5, 1);
    /**
     * 放置冷却的随机附加量（0~5）。实际冷却 = {@link #placeDelay} + [0, 本值]，
     * 每次放置重新掷一次，避免固定间隔本身成为新的可识别特征。
     */
    private final IntSetting placeDelayRandom = intSetting("Place Delay Random", 2, 0, 5, 1);
    /**
     * 失败/坠落时的强制放置重试（Epsilon 原有行为）：判定"够不到目标方块或水平速度过快"时，
     * 取消本刻玩家 tick 强行转向放置，最多连试 8 次。这段会连续发出转向包与放置包，
     * 容易被反作弊连着记违规，因此给一个能整段关掉的开关。
     */
    private final BoolSetting emergencyPlacement = boolSetting("Emergency Placement", true);

    /*
     * ===== 神桥 Polar 变体（LiquidBounce GodBridge 链路，逐行移植）=====
     * 默认值取自社区 polar（pika-network）配置里 Scaffold 段的存档值
     * （tmp/polar-scaffold.txt，作者 Eev3Thin / toppika），未覆盖到的项取 LiquidBounce 源码默认值。
     * 每项的来源在 tmp/scaffold-polar-port-map.md 逐项登记。
     */

    /** 变体开关：只有神桥模式才显示这组设置。 */
    public final EnumSetting<GodBridgeVariant> godBridgeVariant =
            enumSetting("God Bridge Variant", GodBridgeVariant.Classic, () -> mode.is(Mode.GodBridge));

    /** Polar 流水线的生效条件，供下面所有设置复用。 */
    public final Setting.Dependency polarDependency =
            () -> mode.is(Mode.GodBridge) && godBridgeVariant.is(GodBridgeVariant.Polar);

    private final SettingGroup sgGodBridge = settingGroup("God Bridge");
    private final SettingGroup sgFakeClick = settingGroup("Fake Click");
    private final SettingGroup sgRotation = settingGroup("Rotation");
    private final SettingGroup sgEagle = settingGroup("Eagle");

    /** LB {@code ScaffoldGodBridgeTechnique.Mode}：多选，展开成 4 个开关（polar 配置 = [Jump]）。 */
    public final BoolSetting polarModesJump = boolSetting("Modes Jump", true, polarDependency).group(sgGodBridge);
    public final BoolSetting polarModesSneak = boolSetting("Modes Sneak", false, polarDependency).group(sgGodBridge);
    public final BoolSetting polarModesStopInput = boolSetting("Modes Stop Input", false, polarDependency).group(sgGodBridge);
    public final BoolSetting polarModesStepBack = boolSetting("Modes Step Back", false, polarDependency).group(sgGodBridge);
    /** LB {@code forceSneakBelowCount}（polar 存档 = 5，LB 源码默认 3）。 */
    public final IntSetting polarForceSneakBelow = intSetting("Force Sneak Below Count", 5, 0, 10, 1, polarDependency).group(sgGodBridge);
    /** LB {@code sneakTime} → Epsilon 无 intRange，展开成 Min/Max（polar 存档 = 1..1）。 */
    public final IntSetting polarSneakTimeMin = intSetting("Sneak Time Min", 1, 1, 10, 1, polarDependency).group(sgGodBridge);
    public final IntSetting polarSneakTimeMax = intSetting("Sneak Time Max", 1, 1, 10, 1, polarDependency).group(sgGodBridge);
    public final EnumSetting<RotationTiming> polarRotationTiming = enumSetting("Rotation Timing", RotationTiming.Normal, polarDependency).group(sgGodBridge);
    public final BoolSetting polarConsiderInventory = boolSetting("Consider Inventory", false, polarDependency).group(sgGodBridge);
    /**
     * LB {@code delay}（intRange 0..0）。
     * <p>
     * [适配·反作弊] 语义说明：Epsilon 侧 `waitTicks = n` 表示"第 n 刻恢复"，因此
     * {@code 1} 等于**不额外节流**（允许下一刻就放，与 LB 的 {@code 0..0} 行为一致），
     * {@code n>=2} 才是真正跳过 n-1 刻。
     * <p>
     * 实测结论：走路速度 4.317 格/秒 ⇒ 桥需要 ≥4.3 块/秒；而放置闸门（准星必须命中搜出来的目标）
     * 实测只有 ~35% 的刻能过。若把 {@code Delay} 设成 3（≈6.7 次/秒上限），实际只有 ~2.3 块/秒，
     * **桥会跟不上玩家 → 掉落**。故默认取 1（不节流，与 LB 一致）；要压 Matrix 的
     * {@code sfd.dly} 时再用 `Place Delay`（Classic 那套）逐级上调 —— 但每上调 1 就要接受
     * 桥速下降 ~1 块/秒，1→2→3 是"跟得上→勉强→会掉"的序列。
     */
    public final IntSetting polarDelayMin = intSetting("Delay Min", 1, 0, 40, 1, polarDependency).group(sgGodBridge);
    public final IntSetting polarDelayMax = intSetting("Delay Max", 1, 0, 40, 1, polarDependency).group(sgGodBridge);
    /** LB {@code minDist}。 */
    public final DoubleSetting polarMinDist = doubleSetting("Min Dist", 0.0, 0.0, 0.25, 0.01, polarDependency).group(sgGodBridge);
    /** LB {@code ModuleScaffold.ledge}（polar 存档 = true）：边缘动作总开关。 */
    public final BoolSetting polarLedge = boolSetting("Ledge", true, polarDependency).group(sgGodBridge);
    /**
     * 搭桥时强制潜行。
     * <p>
     * [适配·必需] 神桥本身就是「蹲着走」：潜行速度约 1.3 格/秒，而实测放置速率约 3.9 块/秒，
     * 所以蹲着走时桥追得上；一旦全速走路（4.317 格/秒）桥就比人慢 ~0.5 块/秒，
     * 走几步必然踩空掉落（实测：连续走路段落 2.4~4.0 块/秒，全部低于走路速度）。
     * LB 里这一点是靠 {@code ScaffoldLedgeFeature.ledge()} 的 {@code ticks >= 1 → 强制潜行} 分支
     * 隐式实现的（它的托管角每刻都在动，所以该分支实战中几乎恒真）；
     * 本移植把该分支的 NaN 边界修正后 {@code ticks} 归 0，必须显式补上潜行。
     */
    public final BoolSetting polarSneakWhileBridging = boolSetting("Sneak While Bridging", true, polarDependency).group(sgGodBridge);
    /**
     * 诊断输出：**默认开**，但严格有上限（每次启用最多 {@value #DEBUG_MAX_LINES} 行，
     * 启用后前 40 刻逐刻打印以抓住"一开就被扳"的瞬间，之后每 10 刻一行），
     * 只读状态、不参与任何判定。排查完可关掉。
     */
    public final BoolSetting polarDebug = boolSetting("Debug Log", true, polarDependency).group(sgGodBridge);
    /** LB {@code Swing}（polar 存档 = HideForClient）。 */
    public final EnumSetting<SwingMode> polarSwing = enumSetting("Swing", SwingMode.HideForClient, polarDependency).group(sgGodBridge);

    /** LB {@code ModuleScaffold.SimulatePlacementAttempts}（polar 存档 = 开）。 */
    public final BoolSetting polarSimulatePlacements = boolSetting("Simulate Placement Attempts", true, polarDependency).group(sgFakeClick);
    /** LB {@code Clicker.CPS}（polar 存档 = 11..12）。 */
    public final IntSetting polarClickCpsMin = intSetting("Click CPS Min", 11, 1, 60, 1, polarDependency).group(sgFakeClick);
    public final IntSetting polarClickCpsMax = intSetting("Click CPS Max", 12, 1, 60, 1, polarDependency).group(sgFakeClick);
    /** LB {@code Clicker.ClickPatterns}（polar 存档 = Stabilized）。 */
    public final EnumSetting<Clicker.ClickPatterns> polarClickTechnique = enumSetting("Click Technique", Clicker.ClickPatterns.STABILIZED, polarDependency).group(sgFakeClick);
    /** LB {@code SimulatePlacementAttempts.failedAttemptsOnly}（polar 存档 = 关）。 */
    public final BoolSetting polarFailedAttemptsOnly = boolSetting("Failed Attempts Only", false, polarDependency).group(sgFakeClick);

    /** LB {@code RotationsValueGroup.AngleSmooth}（polar 存档 = Sigmoid）。 */
    public final EnumSetting<AngleSmoothMode> polarAngleSmooth = enumSetting("Angle Smooth", AngleSmoothMode.Sigmoid, polarDependency).group(sgRotation);
    public final DoubleSetting polarHorizontalTurnSpeedMin = doubleSetting("Horizontal Turn Speed Min", 19.8, 0.0, 180.0, 0.1, polarDependency).group(sgRotation);
    public final DoubleSetting polarHorizontalTurnSpeedMax = doubleSetting("Horizontal Turn Speed Max", 40.5, 0.0, 180.0, 0.1, polarDependency).group(sgRotation);
    public final DoubleSetting polarVerticalTurnSpeedMin = doubleSetting("Vertical Turn Speed Min", 8.1, 0.0, 180.0, 0.1, polarDependency).group(sgRotation);
    public final DoubleSetting polarVerticalTurnSpeedMax = doubleSetting("Vertical Turn Speed Max", 30.6, 0.0, 180.0, 0.1, polarDependency).group(sgRotation);
    public final DoubleSetting polarSteepness = doubleSetting("Steepness", 10.0, 0.0, 20.0, 0.1, polarDependency).group(sgRotation);
    public final DoubleSetting polarMidpoint = doubleSetting("Midpoint", 0.3, 0.0, 1.0, 0.01, polarDependency).group(sgRotation);
    public final DoubleSetting polarResetThreshold = doubleSetting("Reset Threshold", 1.0, 1.0, 180.0, 0.1, polarDependency).group(sgRotation);
    public final IntSetting polarTicksUntilReset = intSetting("Ticks Until Reset", 20, 1, 30, 1, polarDependency).group(sgRotation);
    public final EnumSetting<MovementCorrection> polarMovementCorrection = enumSetting("Movement Correction", MovementCorrection.SILENT, polarDependency).group(sgRotation);

    /** LB {@code ScaffoldEagleFeature}（polar 存档 = 关）。 */
    public final BoolSetting polarEagleEnabled = boolSetting("Eagle Enabled", false, polarDependency).group(sgEagle);
    public final IntSetting polarBlocksToEagleMin = intSetting("Blocks To Eagle Min", 0, 0, 10, 1, polarDependency).group(sgEagle);
    public final IntSetting polarBlocksToEagleMax = intSetting("Blocks To Eagle Max", 0, 0, 10, 1, polarDependency).group(sgEagle);
    public final DoubleSetting polarEdgeDistanceMin = doubleSetting("Edge Distance Min", 0.01, 0.01, 1.3, 0.01, polarDependency).group(sgEagle);
    public final DoubleSetting polarEdgeDistanceMax = doubleSetting("Edge Distance Max", 0.05, 0.01, 1.3, 0.01, polarDependency).group(sgEagle);
    public final BoolSetting polarOnlyOnGround = boolSetting("Only On Ground", true, polarDependency).group(sgEagle);

    /*
     * ===== Uitems 搭路（OpenPal 的 HeypixelScaffold / HypixelScaffold，逐段照抄）=====
     * 显示名一律照 OpenPal 的 ScaffoldSettings 字面量；分组见 §5.2：非 Uitems 模式下
     * 这一组所有子项都不可见，靠 SettingSectionRenderer.hasVisibleContent 让整张卡片消失。
     *
     * ⚠ 三处显示名**必须**加 "Uitems " 前缀（Telly Ticks / Rotation Speed / Rotation Back Speed）：
     * 本模块的配置文件 `~/.epsilon/configs/<cfg>/epsilon/Scaffold.json` 以**显示名**为键
     * （见 ConfigManager.saveModuleToDisk 的 settingsObj.add(setting.getName(), …)），
     * 而同名的 Classic 设置（TellyBridge 的 Telly Ticks、通用 Rotation Speed / Rotation Back Speed）
     * 已经占用了这三个键 —— 不加前缀会与它们**互相覆盖**（改一个模式会连带改另一个模式）。
     */

    private final Setting.Dependency heypixelDependency = () -> mode.is(Mode.Heypixel);
    private final Setting.Dependency uitemsDependency = () -> mode.is(Mode.Heypixel) || mode.is(Mode.Hypixel);

    private final SettingGroup sgUitems = settingGroup("Uitems");

    /** OpenPal {@code uitemsTelly}（Hypixel 恒开，故只在 Heypixel 下有效）。 */
    public final BoolSetting uitemsTelly = boolSetting("Uitems Telly", true, heypixelDependency).group(sgUitems);
    /** OpenPal {@code tellyTick}。 */
    public final IntSetting uitemsTellyTicks = intSetting("Uitems Telly Ticks", 5, 0, 10, 1, heypixelDependency).group(sgUitems);
    /** OpenPal {@code rotateSpeed}（Hypixel 侧硬编码 180，故只在 Heypixel 下有效）。 */
    public final IntSetting uitemsRotationSpeed = intSetting("Uitems Rotation Speed", 180, 10, 180, 10, heypixelDependency).group(sgUitems);
    /** OpenPal {@code rotateBackSpeed}（同上）。 */
    public final IntSetting uitemsRotationBackSpeed = intSetting("Uitems Rotation Back Speed", 180, 10, 180, 10, heypixelDependency).group(sgUitems);
    /** OpenPal {@code duplicateRotPlace}。 */
    public final BoolSetting uitemsDuplicateRotPlace = boolSetting("Duplicate Rot Place", false, uitemsDependency).group(sgUitems);
    /** OpenPal {@code overrideRaycast}。 */
    public final BoolSetting uitemsOverrideRaycast = boolSetting("Override raycast", true, uitemsDependency).group(sgUitems);
    /** OpenPal {@code interactBeforePlace}。 */
    public final BoolSetting uitemsInteractBeforePlace = boolSetting("Interact before place", false, uitemsDependency).group(sgUitems);
    /** OpenPal {@code safeWalk}。 */
    public final BoolSetting uitemsSafeWalk = boolSetting("SafeWalk", true, uitemsDependency).group(sgUitems);
    /** OpenPal {@code selfRescueMode}。 */
    public final EnumSetting<ScaffoldSettings.SelfRescueMode> uitemsSelfRescueMode =
            enumSetting("Self rescue mode", ScaffoldSettings.SelfRescueMode.Disabled, uitemsDependency).group(sgUitems);

    /**
     * OpenPal 的 {@code RotationProperty}（Uitems 设置清单里没有"旋转模型"下拉框，见
     * {@link RotationProperty} 注释）；只用于 {@link ScaffoldSettings#isRotationModel}。
     */
    public final RotationProperty rotationProperty = new RotationProperty(InstantRotationModel.INSTANCE);

    /** OpenPal {@code ModuleMode} 注册表在本仓库的替代：两个引擎实例 + 当前生效者。 */
    private final HeypixelScaffold heypixelScaffold = new HeypixelScaffold(this);
    private final HypixelScaffold hypixelScaffold = new HypixelScaffold(this);

    /** {@code module.getSettings()} 的对应物（§2 映射表）。 */
    private final ScaffoldSettings scaffoldSettings = new ScaffoldSettings(this);

    public ScaffoldSettings getScaffoldSettings() {
        return scaffoldSettings;
    }

    public boolean isUitemsMode() {
        return mode.is(Mode.Heypixel) || mode.is(Mode.Hypixel);
    }

    public boolean isHeypixelMode() {
        return mode.is(Mode.Heypixel);
    }

    public boolean isHypixelMode() {
        return mode.is(Mode.Hypixel);
    }

    public HeypixelScaffold getActiveUitemsMode() {
        return mode.is(Mode.Hypixel) ? hypixelScaffold : heypixelScaffold;
    }

    /** §4.9：{@code SlotHelper.Silence} 由既有的 {@code Swap Mode} 派生。 */
    public ScaffoldSettings.SwitchMode getUitemsSwitchMode() {
        if (swapMode.is(SwapMode.Silent)) return ScaffoldSettings.SwitchMode.HOTBAR;
        if (swapMode.is(SwapMode.InvSwitch)) return ScaffoldSettings.SwitchMode.FULL;
        return ScaffoldSettings.SwitchMode.NORMAL;
    }

    /** OpenPal {@code ScaffoldSettings.isSnap()}（见 {@link #snap} 的共用说明）。 */
    public boolean uitemsSnap() {
        return snap.getValue();
    }

    /*
     * ===== Elowen 搭路（elowen 的 Scaffold，逐行移植）=====
     * 显示名一律加 "Elowen " 前缀：配置文件以显示名为键，本模块已有同名设置
     * （Rotation Speed / Rotation Back Speed / Place Delay / Swing …），不加前缀会互相覆盖。
     */

    private final Setting.Dependency elowenDependency = () -> mode.is(Mode.Elowen);

    private final SettingGroup sgElowen = settingGroup("Elowen");

    /** 原 {@code Scaffold.o}（"Mode"）。 */
    public final EnumSetting<ElowenPlaceMode> elowenPlaceMode =
            enumSetting("Elowen Mode", ElowenPlaceMode.Normal, elowenDependency).group(sgElowen);
    /** 原 {@code Scaffold.P}（"Rotation Mode"）。 */
    public final EnumSetting<ElowenRotationMode> elowenRotationMode =
            enumSetting("Elowen Rotation Mode", ElowenRotationMode.Linear, elowenDependency).group(sgElowen);

    /** 原 {@code lambda$new$0}：Eagle 只在 Normal / Snap 两个放置模式下可见。 */
    private final Setting.Dependency elowenEagleDependency = () -> isElowenMode()
            && (elowenPlaceMode.is(ElowenPlaceMode.Normal) || elowenPlaceMode.is(ElowenPlaceMode.Snap));
    /** 原 {@code lambda$new$2}：Jump Sprint 只在 Normal 下可见。 */
    private final Setting.Dependency elowenNormalDependency = () -> isElowenMode() && elowenPlaceMode.is(ElowenPlaceMode.Normal);
    /** 原 {@code lambda$new$4}：Back Rotation Speed（Sideway Rotation 同集合）。 */
    private final Setting.Dependency elowenBackSpeedDependency = () -> isElowenMode()
            && (elowenPlaceMode.is(ElowenPlaceMode.Normal)
            || elowenPlaceMode.is(ElowenPlaceMode.TellyBridge)
            || elowenPlaceMode.is(ElowenPlaceMode.KeepY));
    /** 原 {@code lambda$new$3}：Rotation Speed 与 Heypixel 旋转模式互斥。 */
    private final Setting.Dependency elowenRotationSpeedDependency = () -> isElowenMode()
            && !elowenRotationMode.is(ElowenRotationMode.Heypixel)
            && (elowenPlaceMode.is(ElowenPlaceMode.Normal)
            || elowenPlaceMode.is(ElowenPlaceMode.TellyBridge)
            || elowenPlaceMode.is(ElowenPlaceMode.KeepY)
            || elowenRotationMode.is(ElowenRotationMode.Curve));

    /** 原 {@code Scaffold.f}（"Eagle"）。 */
    public final BoolSetting elowenEagle = boolSetting("Elowen Eagle", false, elowenEagleDependency).group(sgElowen);
    /** 原 {@code Scaffold.aB}（"Sneak"）：搭桥中每 21 刻强制一次潜行。 */
    public final BoolSetting elowenSneak = boolSetting("Elowen Sneak", true, elowenDependency).group(sgElowen);
    /** 原 {@code Scaffold.i}（"Jump Sprint"）。 */
    public final BoolSetting elowenJumpSprint = boolSetting("Elowen Jump Sprint", false, elowenNormalDependency).group(sgElowen);
    /** 原 {@code Scaffold.a5}（"Rotation Speed"，原 float 50/30..180 ⇒ 本仓库 IntSetting）。 */
    public final IntSetting elowenRotationSpeed = intSetting("Elowen Rotation Speed", 50, 30, 180, 1, elowenRotationSpeedDependency).group(sgElowen);
    /** 原 {@code Scaffold.y}（"Back Rotation Speed"）。 */
    public final IntSetting elowenBackRotationSpeed = intSetting("Elowen Back Rotation Speed", 180, 30, 180, 1, elowenBackSpeedDependency).group(sgElowen);
    /** 原 {@code Scaffold.r}（"Place Delay (Ticks)"，0..2）。 */
    public final IntSetting elowenPlaceDelay = intSetting("Elowen Place Delay", 0, 0, 2, 1, elowenDependency).group(sgElowen);
    /** 原 {@code Scaffold.p}（"Swing"）。 */
    public final BoolSetting elowenSwing = boolSetting("Elowen Swing", false, elowenDependency).group(sgElowen);
    /** 原 {@code Scaffold.l}（"Packet Order N"）：放置前先发一次 {@code useItem}。 */
    public final BoolSetting elowenPacketOrderN = boolSetting("Elowen Packet Order N", false, elowenDependency).group(sgElowen);
    /** 原 {@code Scaffold.K}（"Block Count"）。 */
    public final BoolSetting elowenBlockCount = boolSetting("Elowen Block Count", true, elowenDependency).group(sgElowen);
    /** 原 {@code Scaffold.aR}（"HUD Y"）。 */
    public final IntSetting elowenHudY = intSetting("Elowen HUD Y", -50, -500, 500, 1,
            () -> isElowenMode() && elowenBlockCount.getValue()).group(sgElowen);
    /** 原 {@code Scaffold.a_}（"Sideway Rotation"）。 */
    public final BoolSetting elowenSidewayRotation = boolSetting("Elowen Sideway Rotation", false, elowenBackSpeedDependency).group(sgElowen);

    private final ElowenScaffold elowenScaffold = new ElowenScaffold(this);

    /** 上一次生效的 Elowen 状态，用于驱动引擎的 {@code onEnable/onDisable}（照 {@link #syncUitemsModeLifecycle}）。 */
    private boolean lastElowenActive;

    public boolean isElowenMode() {
        return mode.is(Mode.Elowen);
    }

    public ElowenScaffold getElowenScaffold() {
        return elowenScaffold;
    }

    /*
     * ===== Raven B4 搭路（ravenb4/，逐行移植自 xyz.daikirai.module.player.ScaffoldModule）=====
     * 显示名一律加 "Raven " 前缀：配置文件以显示名为键，本模块已有同名设置，不加前缀会互相覆盖。
     */

    private final Setting.Dependency ravenDependency = () -> mode.is(Mode.RavenB4);

    private final SettingGroup sgRaven = settingGroup("Raven");

    /** 原 {@code rotationSetting}（"Rotation"）：None / Strict / Smooth / Simple / Lazy / Offset。 */
    public final EnumSetting<RavenB4Scaffold.RavenRotation> ravenRotationMode =
            enumSetting("Raven Rotation Mode", RavenB4Scaffold.RavenRotation.Strict, ravenDependency).group(sgRaven);
    /** 原 {@code fastScaffoldSetting}（"Fast scaffold"）。 */
    public final EnumSetting<RavenB4Scaffold.RavenFastScaffold> ravenFastScaffold =
            enumSetting("Raven Fast Scaffold", RavenB4Scaffold.RavenFastScaffold.Disabled, ravenDependency).group(sgRaven);
    /** 原 {@code fastScaffoldRmbSetting}（"Fast scaffold RMB"：按住右键时生效的档位）。 */
    public final EnumSetting<RavenB4Scaffold.RavenFastScaffold> ravenFastScaffoldRmb =
            enumSetting("Raven Fast Scaffold RMB", RavenB4Scaffold.RavenFastScaffold.Disabled, ravenDependency).group(sgRaven);
    /** 原 {@code multiPlaceSetting}（"Multi-place"）。 */
    public final EnumSetting<RavenB4Scaffold.RavenMultiPlace> ravenMultiPlace =
            enumSetting("Raven Multi Place", RavenB4Scaffold.RavenMultiPlace.Disabled, ravenDependency).group(sgRaven);
    /** 原 {@code precisionSetting}（"Precision"）。 */
    public final EnumSetting<RavenB4Scaffold.RavenPrecision> ravenPrecision =
            enumSetting("Raven Precision", RavenB4Scaffold.RavenPrecision.High, ravenDependency).group(sgRaven);
    /** 原 {@code motion}（"Motion"：地面每刻水平速度乘子）。 */
    public final DoubleSetting ravenMotion = doubleSetting("Raven Motion", 0.95, 0.1, 1.2, 0.01, ravenDependency).group(sgRaven);
    /** 原 {@code fastScaffoldMotion}（"Fast scaffold motion"）。 */
    public final DoubleSetting ravenFastScaffoldMotion = doubleSetting("Raven Fast Scaffold Motion", 0.95, 0.1, 1.2, 0.01, ravenDependency).group(sgRaven);
    /** 原 {@code sneakEvery}（"Sneak every"：每 N 次放置强制一次潜行）；0 表示关闭。 */
    public final IntSetting ravenSneakEvery = intSetting("Raven Sneak Every", 0, 0, 20, 1, ravenDependency).group(sgRaven);
    /** 原 {@code speedBoost}（"Speed boost"：迅捷 II 时的额外加成）。 */
    public final DoubleSetting ravenSpeedBoost = doubleSetting("Raven Speed Boost", 0.0, 0.0, 1.0, 0.05, ravenDependency).group(sgRaven);
    /** 原 {@code legitMotion}（"Legit motion"）。 */
    public final BoolSetting ravenLegitMotion = boolSetting("Raven Legit Motion", false, ravenDependency).group(sgRaven);
    /** 原 {@code safeWalk}（"SafeWalk"）：开启后以真实视角为旋转基准。 */
    public final BoolSetting ravenSafeWalk = boolSetting("Raven Safe Walk", false, ravenDependency).group(sgRaven);
    /** 原 {@code jumpFacingForward}（"Jump facing forward"）。 */
    public final BoolSetting ravenJumpFacingForward = boolSetting("Raven Jump Facing Forward", false, ravenDependency).group(sgRaven);
    /** 原 {@code autoSwap}（"Auto swap"）：主手不是方块时自动切到热键栏里的可放置方块。 */
    public final BoolSetting ravenAutoSwap = boolSetting("Raven Auto Swap", true, ravenDependency).group(sgRaven);
    /** 原 {@code silentSwing}（"Silent swing"）。 */
    public final BoolSetting ravenSilentSwing = boolSetting("Raven Silent Swing", false, ravenDependency).group(sgRaven);
    /** 原 {@code slowOnEnable}（"Slow on enable"）。 */
    public final BoolSetting ravenSlowOnEnable = boolSetting("Raven Slow On Enable", false, ravenDependency).group(sgRaven);
    /** 原 {@code slowSpeedPotion}（"Slow speed potion"）。 */
    public final BoolSetting ravenSlowSpeedPotion = boolSetting("Raven Slow Speed Potion", false, ravenDependency).group(sgRaven);
    /** 原 {@code highlightBlocks}（"Highlight blocks"）。 */
    public final BoolSetting ravenHighlightBlocks = boolSetting("Raven Highlight Blocks", false, ravenDependency).group(sgRaven);
    /** 原 {@code tower}（"Tower"）：Epsilon 无联动塔模块，预留开关。 */
    public final BoolSetting ravenTower = boolSetting("Raven Tower", false, ravenDependency).group(sgRaven);
    /** 原 {@code canSprint()} 里 {@code SettingsModule.movementFix}：非快速搭路时的移动修正开关。 */
    public final BoolSetting ravenMovementFix = boolSetting("Raven Movement Fix", true, ravenDependency).group(sgRaven);

    private final RavenB4Scaffold ravenScaffold = new RavenB4Scaffold(this);

    /** 上一次生效的 Raven 状态，用于驱动引擎的 {@code onEnable/onDisable}（照 {@link #syncElowenModeLifecycle}）。 */
    private boolean lastRavenActive;

    public boolean isRavenB4Mode() {
        return mode.is(Mode.RavenB4);
    }

    public RavenB4Scaffold getRavenScaffold() {
        return ravenScaffold;
    }

    /**
     * Raven B4 的启用/停用同步（照 {@link #syncElowenModeLifecycle}）：
     * 切到 {@link Mode#RavenB4} 时调引擎 {@code onEnable()}，切走时调 {@code onDisable()} 并释放托管角。
     * 必须在引擎 {@code onPreTick()} 之前执行。
     */
    private void syncRavenB4ModeLifecycle() {
        final boolean raven = isRavenB4Mode();
        if (raven == lastRavenActive) return;

        if (lastRavenActive) {
            ravenScaffold.onDisable();
            RotationManager.INSTANCE.setActive(false);
        }
        lastRavenActive = raven;
        if (raven) {
            ravenScaffold.onEnable();
        }
    }

    private final BoolSetting swingHand = boolSetting("Swing Hand", true);
    private final BoolSetting render = boolSetting("Render", true);
    private final BoolSetting fade = boolSetting("Fade", true, render::getValue);
    private final IntSetting fadeTime = intSetting("Fade Time", 500, 0, 3000, 50, () -> render.getValue() && fade.getValue());
    private final BoolSetting shrink = boolSetting("Shrink", false, render::getValue);
    private final ColorSetting sideColor = colorSetting("Side Color", new Color(255, 183, 197, 100), render::getValue);
    private final ColorSetting lineColor = colorSetting("Line Color", new Color(255, 105, 180), render::getValue);

    private int airTicks;
    private int yLevel;
    private BlockPos blockPos;
    private Direction direction;
    private Rot2f rotation;
    private int rotateCount = 0;
    private float forwardInput, strafeInput;
    private float inputYaw;
    private float rawInputYaw;
    private double lengthSqr = 4.5 * 4.5;

    private FindItemResult blockResult;
    private boolean shouldSwapBack;
    private boolean emergencyPlacementActive;
    private boolean pearlUsePacketSent;

    private final Random placeRandom = new Random();

    /** 放置冷却剩余刻数；>0 时不放置。见 {@link #placeDelay}。 */
    private int placeDelayCounter = 0;

    /*
     * ===== OpenPal ScaffoldModule 的 SkipTick 自救设施（§4.16）=====
     * 只搬两个 Uitems 模式会调用到的部分；OpenPal 的通用搜索/放置流水线不搬。
     */

    private boolean skipTickRecoveryActive;
    private boolean skipTickRecoveryFailed;
    private int skipTickRecoveryCandidateTicks;
    private BlockPos skipTickRecoveryBlockPos;
    private Direction skipTickRecoveryFace;

    /** 上一次生效的 Uitems 模式，用于驱动两个引擎的 {@code onEnable/onDisable}。 */
    private Mode lastUitemsMode;

    private final List<RenderInfo> renderBoxes = new ArrayList<>();

    @Override
    protected void onEnable() {
        airTicks = 0;
        blockPos = null;
        direction = null;
        rotation = null;
        rotateCount = 0;
        blockResult = null;
        shouldSwapBack = false;
        emergencyPlacementActive = false;
        pearlUsePacketSent = false;
        lastUitemsMode = null;
        lastElowenActive = false;
        lastRavenActive = false;
        renderBoxes.clear();
        // LB ModuleScaffold.onEnabled()（404–412）
        ScaffoldPolarCoordinator.INSTANCE.onEnabled();
    }

    @Override
    protected void onDisable() {
        yLevel = 0;
        emergencyPlacementActive = false;
        pearlUsePacketSent = false;
        // LB ModuleScaffold.onDisabled() → reset()（417–427）
        ScaffoldPolarCoordinator.INSTANCE.reset();
        if (shouldSwapBack) {
            InvUtils.swapBack();
            shouldSwapBack = false;
        }

        // Uitems：OpenPal 的 ModuleMode 框架会在模块关闭时调模式的 onDisable()。
        // [适配] 模式的 onDisable() 走 handler.rotate(...)+reverse() 让托管角平滑回到玩家视角，
        //        但本仓库的推进只在 Uitems 分流里调用（模块已关，不会再跑）⇒ 必须显式 reset() 释放，
        //        否则 RotationManager 永久 active、准星与 MovementFix 停在旧角（见 RotationMouseHandler 注释）。
        final HeypixelScaffold engine = getUitemsMode(lastUitemsMode);
        if (engine != null) {
            engine.onDisable();
            RotationHelper.getHandler().reset();
        }
        lastUitemsMode = null;

        // Elowen：模块关闭时释放托管角并还原按键（elowen 的 onDisable）。
        if (lastElowenActive) {
            elowenScaffold.onDisable();
            lastElowenActive = false;
        }

        // Raven B4：模块关闭时释放托管角并还原按键（原 ScaffoldModule.onDisable）。
        if (lastRavenActive) {
            ravenScaffold.onDisable();
            lastRavenActive = false;
        }
    }

    private HeypixelScaffold getUitemsMode(Mode mode) {
        if (mode == Mode.Heypixel) return heypixelScaffold;
        if (mode == Mode.Hypixel) return hypixelScaffold;
        return null;
    }

    /**
     * Elowen 的启用/停用同步（照 {@link #syncUitemsModeLifecycle}）：
     * 切到 {@link Mode#Elowen} 时调引擎 {@code onEnable()}，切走时调 {@code onDisable()} 并释放托管角。
     * 必须在引擎 {@code onPreTick()} 之前执行。
     */
    private void syncElowenModeLifecycle() {
        final boolean elowen = isElowenMode();
        if (elowen == lastElowenActive) return;

        if (lastElowenActive) {
            elowenScaffold.onDisable();
            RotationManager.INSTANCE.setActive(false);
        }
        lastElowenActive = elowen;
        if (elowen) {
            elowenScaffold.onEnable();
        }
    }

    /**
     * OpenPal 的 {@code ModuleMode} 框架在模式切换时调旧模式的 {@code onDisable()}、新模式的
     * {@code onEnable()}；本仓库没有该框架，改为每刻比对 {@link #lastUitemsMode} 现算。
     */
    private void syncUitemsModeLifecycle() {
        final Mode current = mode.getValue();
        if (lastUitemsMode == current) return;

        final HeypixelScaffold previous = getUitemsMode(lastUitemsMode);
        if (previous != null) {
            previous.onDisable();
            RotationHelper.getHandler().reset();
        }
        lastUitemsMode = current;

        final HeypixelScaffold next = getUitemsMode(current);
        if (next != null) {
            next.onEnable();
        }
    }

    /**
     * Polar：原样采集玩家 WASD —— LB 的 {@code MovementInputEvent.directionalInput} 始终是**未被修正**的按键输入，
     * 所以必须取在 {@code MovementFix}（{@link EventPriority#HIGH}）改写之前。
     */
    @EventHandler(priority = EventPriority.HIGHEST)
    private void onPolarMovementInput(KeyboardInputEvent event) {
        if (!isPolarActive()) return;

        ScaffoldPolarCoordinator.INSTANCE.handleMovementInput(event);
    }

    /**
     * Polar 的边缘动作注入（LB {@code movementInputHandler}，SAFETY_FEATURE）：
     * 放在 {@link EventPriority#LOW}，让 MovementFix 先按托管角修正完 WASD，再叠加"停手/后退"，
     * 否则这两个动作会被移动修正再旋转一次。LB 同样在移动修正之后处理边缘动作。
     */
    @EventHandler(priority = EventPriority.LOW)
    private void onPolarMovementInputSafety(KeyboardInputEvent event) {
        if (!isPolarActive()) return;

        ScaffoldPolarCoordinator.INSTANCE.handleMovementInputSafety(event);
    }

    /** Polar 的假点击节奏推进（LB {@code Clicker.gameHandler}，{@code GameTickEvent} FIRST_PRIORITY）。 */
    @EventHandler
    private void onPolarClientTick(ClientTickEvent.Pre event) {
        if (!isPolarActive()) return;

        ScaffoldPolarCoordinator.INSTANCE.clickerTick();
    }

    /**
     * LB {@code RotationManager.packetHandler}（{@code READ_FINAL_STATE}）的出站分支：
     * 追踪 {@code actualServerRotation} / {@code theoreticalServerRotation}。
     *
     * <p>必须接线，否则 {@code actualServerRotation} 恒为 {@code Rotation.ZERO}，
     * {@code RotationsValueGroup.calculateTicks()} 会从 yaw 0 起算，
     * 使 {@code ScaffoldLedgeFeature.ledge()} 的 {@code ticks >= 1} 几乎恒真 ——
     * 边缘动作退化成"每刻强制潜行"（实测反馈：神桥时蹲不住/蹲死）。</p>
     */
    @EventHandler
    private void onPolarPacketSend(PacketEvent.Send event) {
        if (!isEnabled()) return;

        ScaffoldPolarCoordinator.INSTANCE.onPacketSend(event);
    }

    /** LB {@code RotationManager.packetHandler}（{@code READ_FINAL_STATE}）的入站分支。 */
    @EventHandler
    private void onPolarPacketReceive(PacketEvent.Receive event) {
        if (!isEnabled()) return;

        ScaffoldPolarCoordinator.INSTANCE.onPacketReceive(event);
    }

    @EventHandler
    private void onPlayerTick(PlayerTickEvent.Pre event) {
        if (!event.isCancelled()) emergencyPlacementActive = false;

        // LB 的 airTicks / onGroundTicks 由 mixin 维护；Epsilon 侧由这里每刻推进
        // （见 util/EntityUtils.tickGroundState）。
        EntityUtils.tickGroundState(mc.player);

        syncUitemsModeLifecycle();
        syncElowenModeLifecycle();
        syncRavenB4ModeLifecycle();

        // Polar 以 Priority.Highest 提交托管角，且从不自行降级：若在其它引擎分支之后才释放，
        // 这里永远跑不到（各分支都已 return），Elowen/Raven 的 Medium 提交会被优先级门丢弃，
        // 准星与 MovementFix 卡在神桥角。故在分流**之前**按「Polar 是否生效」统一释放。
        if (!isPolarActive()) {
            ScaffoldPolarCoordinator.INSTANCE.releaseRotationIfIdle();
        }

        if (isPolarActive()) {
            // Polar 走 LiquidBounce 的 GodBridge 链路：本刻的转向目标、转向落地与放置
            // 全部由协调层按 LB 的顺序完成（rotationUpdate → RotationManager.update → tickHandler）。
            // Epsilon 既有的 Emergency Placement 仍然前置执行（非照搬范围）。
            blockResult = findBlockResult();
            if (blockResult.found() && handleEmergencyPlacement(event)) return;
            ScaffoldPolarCoordinator.INSTANCE.tickPolar();
            return;
        }

        if (isUitemsMode()) {
            // OpenPal 的每刻顺序：ScaffoldModule.onPreGameTick（换槽 + 自救武装）→ 模式的
            // onPreTick（下转向目标）→ RotationMouseHandler 推进托管角。
            // [适配] OpenPal 的两个旋转订阅点在本仓库合并为一次分流里的两次调用，
            //        顺序与 OpenPal 的 PreGameTick 派发顺序一致（见 RotationMouseHandler 注释）。
            SlotHelper.getInstance().tick();
            armSkipTickRecovery();
            updateSkipTickRecoveryGroundState();
            RotationHelper.getHandler().onPreTick();
            getActiveUitemsMode().onPreTick(event);
            RotationHelper.getHandler().onMouseUpdate();
            return;
        }

        if (isElowenMode()) {
            // elowen 的每刻顺序全在引擎里（选模式 → 找目标 → 跳刻清算 → 潜行 → 放置模式 onTick → 提交托管角）。
            elowenScaffold.onPreTick();
            return;
        }

        if (isRavenB4Mode()) {
            // Raven B4 的每刻顺序全在引擎里（计数 → 暂停/换槽检查 → 激活 → FastScaffold 状态机 → 放置 → 提交托管角）。
            // onPreMotion / onPostPlayerInput 分别由 SendPositionEvent / KeyboardInputEvent 触发（见下方事件）。
            ravenScaffold.onPreTick();
            return;
        }

        if (placeDelayCounter > 0) placeDelayCounter--;

        blockResult = findBlockResult();

        if (!blockResult.found()) return;

        if (handleEmergencyPlacement(event)) return;

        switch (mode.getValue()) {
            case TellyBridge -> handleTelly();
            case GodBridge -> handleGodBridge();
        }
    }

    /** Polar（LB 神桥链路）是否生效。 */
    public boolean isPolarActive() {
        return mode.is(Mode.GodBridge) && godBridgeVariant.is(GodBridgeVariant.Polar);
    }

    /**
     * Epsilon 既有：坠落 / 够不到时的强制放置重试（**非照搬范围**，Classic 与 Polar 前置共用）。
     * 函数体与原 {@link #onPlayerTick} 里的内联版本逐行相同，只为两处复用而搬进方法。
     *
     * @return true 表示本刻已做强制放置并取消事件，调用方必须立即返回
     */
    private boolean handleEmergencyPlacement(PlayerTickEvent.Pre event) {
        if (mc.player.onGround()) {
            airTicks = 0;
            yLevel = Mth.floor(mc.player.getY()) - 1;
        } else {
            airTicks++;
        }

        getBlockInfo();

        boolean reachable = true;
        if (mc.player.getDeltaMovement().y < -0.1 && blockPos != null) {
            FallingPlayer fallingPlayer = new FallingPlayer(mc.player).calculate(2);
            if (blockPos.getY() > fallingPlayer.getY()) {
                reachable = false;
            }
        }
        double strength = mc.player.getDeltaMovement().horizontal().length();
        if (strength >= 1.5) {
            NotificationManager.INSTANCE.warning(this.getTranslatedName(), EpsilonTranslations.Notifications.SCAFFOLD_FLYING_WARNING.getTranslatedName(), this.hashCode());
        }
        if (emergencyPlacement.getValue() && (!reachable || strength >= 1.5) && rotateCount <= 8 && getBlockCount() >= 1 && canUseBlockResult()) {
            // 先判 target：原实现先 cancel 再判空，导致「没有可放方块」的那几刻整刻被取消
            // （玩家不移动、不下落、不发位置包），而 AutoMLG 又因 emergencyPlacementActive 跳过自救。
            if (blockPos == null) {
                rotateCount = 0;
                return false;
            }
            emergencyPlacementActive = true;
            event.cancel();

            rotateCount++;
            Rot2f rotation = getRotation(blockPos, direction);
            RotationManager.INSTANCE.rotations = rotation;
            RotationManager.INSTANCE.setActive(true);

            mc.getConnection().send(new ServerboundMovePlayerPacket.Rot(rotation.getYaw(), rotation.getPitch(), mc.player.onGround(), mc.player.horizontalCollision));

            swap();

            InteractionHand hand = blockResult.getHand();

            InteractionResult result = mc.gameMode.useItemOn(
                    mc.player,
                    hand,
                    new BlockHitResult(getVec3(blockPos, direction), direction, blockPos, false)
            );
            if (result.consumesAction()) {
                swing(hand);

                if (render.getValue()) {
                    renderBoxes.add(new RenderInfo(new AABB(blockPos.relative(direction)), lineColor.getValue(), sideColor.getValue(), System.currentTimeMillis(), fade.getValue(), shrink.getValue()));
                }
            }

            swapBack();
            return true;
        } else {
            rotateCount = 0;
        }

        return false;
    }

    @EventHandler(priority = EventPriority.HIGHEST)
    private void onMoveInput(KeyboardInputEvent event) {
        if (isUitemsMode()) {
            getActiveUitemsMode().onMoveInput(event);
            return;
        }

        forwardInput = event.getForward();
        strafeInput = event.getStrafe();
        inputYaw = Mth.wrapDegrees(Math.round((mc.player.getYRot() + (float) Math.toDegrees(Math.atan2(-strafeInput, forwardInput))) / 45.0F) * 45.0F);
        rawInputYaw = Mth.wrapDegrees(Math.round(mc.player.getYRot() + (float) Math.toDegrees(Math.atan2(-strafeInput, forwardInput))));

        if (mode.is(Mode.TellyBridge) && mc.player.onGround() && !mc.options.keyJump.isDown() && mc.player.isMoving()) {
            event.setJump(true);
        }
    }

    /**
     * OpenPal {@code HeypixelScaffold.onPreMovementPacket}（{@code @Subscribe(priority = 3)}）的转发。
     *
     * <p>优先级必须低于 {@code RotationManager.onSendPosition}（{@link EventPriority#LOWEST} = -200）：
     * 模式侧的 {@code packetRotation} 是刻意的两刻包伪装，晚于托管角写入才会真正落到出站包上。</p>
     */
    @EventHandler(priority = -300)
    private void onUitemsSendPosition(SendPositionEvent event) {
        if (isUitemsMode()) {
            getActiveUitemsMode().onPreMovementPacket(event);
        }
    }

    /**
     * OpenPal {@code ClientWorldMixin.hookSkipTicks} 的等价物：消耗一次跳刻即取消
     * {@code PlayerTickEvent.Pre}，{@code MixinLocalPlayer} 随即取消 {@code LocalPlayer.tick()}。
     *
     * <p>[适配·必须] 计划里写的是 {@link EventPriority#HIGHEST}，但 Epsilon 的 EventBus 一旦看到
     * {@code isCancelled()} 就**中断后续派发**（见 {@code EventBus.post}），HIGHEST 会先于
     * {@code Scaffold.onPlayerTick}（默认 MEDIUM）执行 ⇒ Uitems 的每刻逻辑（含
     * {@code handleSkipTickRecovery} 自身）整段不会跑，自救循环直接失效。
     * OpenPal 里这两件事分处两个事件（模块逻辑在 {@code PreGameTickEvent}，跳刻在实体 tick），
     * 所以这里取 LOWEST：模块逻辑先跑完，再取消本刻实体 tick。</p>
     */
    @EventHandler(priority = EventPriority.LOWEST)
    private void onUitemsSkipTick(PlayerTickEvent.Pre event) {
        if ((isUitemsMode() || isElowenMode() || isRavenB4Mode()) && SkipTickUtility.consumeSkipTick()) event.cancel();
    }

    /** OpenPal 的渲染分工在模块侧：模式类不做渲染，放置成功由模块补渲染盒。 */
    @EventHandler
    private void onUitemsBlockPlaced(PlaceBlockEvent event) {
        if (isUitemsMode()) addPlacedRenderBox(event.getBlockPos());
    }

    @EventHandler
    private void onPacketReceive(PacketEvent.Receive event) {
        if (!nullCheck() && toggleOnTeleport.getValue() && pearlUsePacketSent && event.getPacket() instanceof ClientboundPlayerPositionPacket) {
            pearlUsePacketSent = false;
            NotificationManager.INSTANCE.error(this.getTranslatedName(), EpsilonTranslations.Notifications.SCAFFOLD_TOGGLE_ON_TELEPORT.getTranslatedName());
            setEnabled(false);
        }
    }

    /**
     * Elowen 的收包（elowen {@code Scaffold.onPacket} → 放置模式 {@code onPacket} → {@code module.E(z2)}）：
     * 只观察自身速度包，水平速度² ≥ 1.5 时给 60 刻冷却（防坠自救的抑制条件之一）。
     */
    @EventHandler
    private void onElowenPacketReceive(PacketEvent.Receive event) {
        if (isElowenMode()) {
            elowenScaffold.onPacketReceive(event);
        }
    }

    /**
     * Elowen 的右键接管（elowen {@code Scaffold.onUseItem}：{@code r2.setCancelled(true)} 后交给当前放置模式）。
     *
     * <p>elowen 该方法是普通订阅（无优先级声明），本仓库取 {@link EventPriority#HIGH}：
     * 必须在原版右键处理之前取消，且晚于 Uitems/Polar 的同类接管（它们各自带模式判定，互不冲突）。</p>
     */
    @EventHandler(priority = EventPriority.HIGH)
    private void onElowenUseItem(StartUseItemEvent event) {
        if (!isElowenMode()) return;

        // 只在手上真有可放置方块时接管右键：原实现无条件 cancel，
        // 会让 Elowen 模式下的吃/喝/开箱/射箭全部失效（Raven 引擎同样只在 active 时接管）。
        if (!isValidStack(mc.player.getMainHandItem()) && !isValidStack(mc.player.getOffhandItem())) {
            return;
        }

        event.cancel();
        elowenScaffold.onUseItem(event);
    }

    /** Elowen 的 HUD（elowen {@code onRenderHUD}）：屏幕中央药丸，显示 {@code Block: N}。 */
    @EventHandler
    private void onElowenHud(Render2DEvent.HUD event) {
        if (isElowenMode()) {
            elowenScaffold.renderHud(event);
        }
    }

    /**
     * Raven B4 的右键接管（原 {@code onMouse(MouseEvent)}：{@code e.setCanceled(true)}）。
     * 与 Elowen 同优先级 HIGH：必须在原版右键处理之前取消。
     */
    @EventHandler(priority = EventPriority.HIGH)
    private void onRavenUseItem(StartUseItemEvent event) {
        if (!isRavenB4Mode()) return;

        ravenScaffold.onUseItem(event);
    }

    /** Raven B4 的 {@code onPreMotion(MotionEvent)}：在位置包发出前调整运动（浮动/起跳相位）。 */
    @EventHandler
    private void onRavenSendPosition(SendPositionEvent event) {
        if (isRavenB4Mode()) {
            ravenScaffold.onPreMotion();
        }
    }

    /** Raven B4 的 {@code onPostPlayerInputAlways()}：注入跳跃/潜行按键（每刻瞬时，落地后清零）。 */
    @EventHandler
    private void onRavenKeyboardInput(KeyboardInputEvent event) {
        if (isRavenB4Mode()) {
            ravenScaffold.onKeyboardInput(event);
        }
    }

    /** Elowen 的每刻瞬时输入（替代旧版直接写 KeyMapping 的持久键态）。 */
    @EventHandler
    private void onElowenKeyboardInput(KeyboardInputEvent event) {
        if (isElowenMode()) {
            elowenScaffold.onKeyboardInput(event);
        }
    }

    /** Uitems（Heypixel/Hypixel）的每刻瞬时输入（替代旧版直接写 KeyMapping 的持久键态）。 */
    @EventHandler
    private void onUitemsKeyboardInput(KeyboardInputEvent event) {
        if (isUitemsMode()) {
            getActiveUitemsMode().onKeyboardInput(event);
        }
    }

    /**
     * 换世界 / 切维度：引擎的 static 状态必须复位。
     * 否则旧世界的 keptY/tickCount/active 会带进新世界 —— keptY 会让 candidates() 过滤掉所有候选
     * （一次都放不出方块），startTick 旧值让 tickCount-startTick 变负数。
     */
    @EventHandler
    private void onLevelUpdate(LevelUpdateEvent event) {
        ravenScaffold.resetState();
        elowenScaffold.resetState();
    }

    @EventHandler
    private void onPacketSend(PacketEvent.Send event) {
        if (event.getPacket() instanceof ServerboundUseItemPacket packet) {
            ItemStack usedStack = mc.player.getItemInHand(packet.getHand());
            if (usedStack.is(Items.ENDER_PEARL) || usedStack.isEmpty() && mc.player.getCooldowns().isOnCooldown(Items.ENDER_PEARL.getDefaultInstance())) {
                pearlUsePacketSent = true;
            }
        }
    }

    public int getBlockCount() {
        if (nullCheck()) return 91;

        int total = 0;

        if (isValidStack(mc.player.getOffhandItem())) {
            total += mc.player.getOffhandItem().getCount();
        }

        int maxSlot = swapMode.is(SwapMode.InvSwitch) ? mc.player.getInventory().getContainerSize() : 9;
        for (int i = 0; i < maxSlot; i++) {
            ItemStack stack = mc.player.getInventory().getItem(i);
            if (isValidStack(stack)) {
                total += stack.getCount();
            }
        }

        return total;
    }

    public ItemStack getBlockStack() {
        ItemStack offhand = mc.player.getOffhandItem();
        if (isValidStack(offhand)) return offhand;

        if (blockResult != null && blockResult.found()) {
            ItemStack stack = blockResult.isOffhand() ? mc.player.getOffhandItem() : mc.player.getInventory().getItem(blockResult.slot());
            if (isValidStack(stack)) return stack;
        }

        return ItemStack.EMPTY;
    }

    private void handleTelly() {
        if (mc.player.onGround() && (strafeInput != 0 || forwardInput != 0)) {
            RotationManager.INSTANCE.setRotations(new Rot2f(rawInputYaw, rotation == null ? mc.player.getXRot() : rotation.getPitch()), rotationBackSpeed.getValue());
            return;
        }

        rotation = getRotation(blockPos, direction);
        int speed = rotationSpeed.getValue();

        if (rotationMode.is(RotationMode.Hypixel)) {
            speed = airTicks <= 1 ? 127 : 35;
        } else if (rotationMode.is(RotationMode.Heypixel)) {
            speed = airTicks <= 1 ? rotationSpeed.getValue() : rotationSpeed2.getValue();
        }

        RotationManager.INSTANCE.setRotations(rotation, speed);

        if (airTicks > tellyTicks.getValue()) {
            place();
        }
    }

    private void handleNormal() {
        if (Eagle.INSTANCE.isOverEdge() || !snap.getValue() | !mc.player.onGround()) {
            rotation = getRotation(blockPos, direction);
            RotationManager.INSTANCE.setRotations(rotation, rotationSpeed.getValue());
        }
        place();
    }

    private void handleGodBridge() {
        // Polar 已在 onPlayerTick 顶端分流（走协调层），到这里只剩 Classic。
        handleNormal();
    }

    /** 当前托管转向的射线是否压在目标方块（面上）；与 {@code place()} 的放置闸门同一判据。 */
    private boolean raytraceOverTarget() {
        if (blockPos == null || direction == null) return false;

        return switch (raytrace.getValue()) {
            case Normal -> RaytraceUtils.overBlock(RotationManager.INSTANCE.getRotation(), blockPos);
            case Strict -> RaytraceUtils.overBlock(RotationManager.INSTANCE.getRotation(), blockPos, direction);
        };
    }

    private void place() {
        if (!onAir() || blockPos == null || direction == null || !canUseBlockResult()) {
            return;
        }

        // 放置节流：冷却未结束时不再放置，避免逐刻连续放置形成规律时序。
        if (placeDelayCounter > 0) return;

        if (!raytraceOverTarget()) {
            return;
        }

        placeOn(new BlockHitResult(getVec3(blockPos, direction), direction, blockPos, false), true);
    }

    /**
     * 真正执行一次放置交互（换手 → useItemOn → 冷却/挥手/渲染）。
     * 与 {@link #place()} 的分工：调用方负责条件与冷却校验。
     *
     * @param hit             命中结果
     * @param renderPlacement 是否按本次命中渲染放置方块
     * @return 本次交互是否真的放下了方块
     */
    private boolean placeOn(BlockHitResult hit, boolean renderPlacement) {
        if (!canUseBlockResult()) return false;

        swap();

        InteractionHand hand = blockResult.getHand();
        InteractionResult result = mc.gameMode.useItemOn(mc.player, hand, hit);
        boolean placed = result.consumesAction();

        if (placed) {
            // 冷却 = 基准 + 随机量，每次放置重新掷一次
            placeDelayCounter = placeDelay.getValue()
                    + (placeDelayRandom.getValue() > 0 ? placeRandom.nextInt(placeDelayRandom.getValue() + 1) : 0);

            swing(hand);

            if (renderPlacement && render.getValue()) {
                renderBoxes.add(new RenderInfo(new AABB(hit.getBlockPos().relative(hit.getDirection())), lineColor.getValue(), sideColor.getValue(), System.currentTimeMillis(), fade.getValue(), shrink.getValue()));
            }
        }

        swapBack();
        return placed;
    }

    /** 挥手。 */
    private void swing(InteractionHand hand) {
        if (swingHand.getValue()) mc.player.swing(hand);
    }

    // ===== 以下为 Polar（LB 神桥链路）协调层使用的接触点 =====

    /** LB {@code SwingMode.swing(hand)} 的四个分支，逐值照搬。 */
    public void swingWithMode(InteractionHand hand, SwingMode swingMode) {
        switch (swingMode) {
            case DoNotHide -> mc.player.swing(hand);
            case HideForBoth -> {
            }
            case HideForClient -> mc.getConnection().send(new ServerboundSwingPacket(hand));
            case HideForServer -> mc.player.swing(hand, false);
        }
    }

    /** 当前选中的可放置方块查找结果（LB 用 SilentHotbar，这里用 Epsilon 的 Swap Mode 设施）。 */
    public FindItemResult getBlockResult() {
        return blockResult;
    }

    /** Epsilon 的换手（{@link #swap()}）。 */
    public void swapForPlacement() {
        swap();
    }

    /** Epsilon 的换回（{@link #swapBack()}）。 */
    public void swapBackAfterPlacement() {
        swapBack();
    }

    /** 判断当前是否真的能用选中的方块（{@link #canUseBlockResult()}）。 */
    public boolean canPlaceNow() {
        return canUseBlockResult();
    }

    /** 放置成功后的渲染（与 {@link #placeOn} 的渲染分支同一实现）。 */
    public void addPlacedRenderBox(BlockPos placed) {
        if (!render.getValue()) return;

        renderBoxes.add(new RenderInfo(new AABB(placed), lineColor.getValue(), sideColor.getValue(), System.currentTimeMillis(), fade.getValue(), shrink.getValue()));
    }

    /**
     * 下一次放置前需要等待的刻数（Classic 的 {@code Place Delay} + {@code Place Delay Random}）。
     * Polar 链路同样复用这套节流：LB 的 {@code Delay} 默认为 0..0（逐刻放置），
     * 实测被 Matrix {@code sfd.dly (delay) bridge too fast} 判违规（见 AGENTS.md）。
     */
    public int nextPlaceDelayTicks() {
        return placeDelay.getValue()
                + (placeDelayRandom.getValue() > 0 ? placeRandom.nextInt(placeDelayRandom.getValue() + 1) : 0);
    }

    /** 放置节流闸门：冷却中返回 true（Elowen / Raven B4 引擎在放置前查询，不改变计数）。 */
    public boolean isPlaceOnCooldown() {
        return placeDelayCounter > 0;
    }

    /** 每刻自减一次放置冷却（Elowen / Raven B4 引擎在 onPreTick 开头调用，保证每刻只减一次）。 */
    public void tickPlaceCooldown() {
        if (placeDelayCounter > 0) placeDelayCounter--;
    }

    /**
     * 放置成功后重掷冷却 = {@link #placeDelay} + [0, {@link #placeDelayRandom}]，
     * 且**不低于** {@code minTicks}。
     *
     * <p>下限是为反作弊留的最小间隔：默认值下若用户把 Place Delay 调到 0/1，
     * 逐刻连发会被 Vulcan（Type C/K/M）与 Matrix（bridge quickly）直接判定。</p>
     */
    public void markPlaceCooldown(int minTicks) {
        placeDelayCounter = Math.max(minTicks, nextPlaceDelayTicks());
    }

    private int getYLevel() {
        if (((!mc.options.keyJump.isDown() && mc.player.isMoving() && mode.is(Mode.TellyBridge))) && Math.abs(yLevel - (Mth.floor(mc.player.getY()) - 1)) <= 1.25) {
            return yLevel;
        }
        return Mth.floor(mc.player.getY()) - 1;
    }

    private void getBlockInfo() {
        lengthSqr = 4.5 * 4.5;
        blockPos = null;
        direction = null;

        Vec3 baseVec = mc.player.getEyePosition();
        BlockPos base = BlockPos.containing(baseVec.x, getYLevel(), baseVec.z);
        int baseX = base.getX();
        int baseZ = base.getZ();

        if (!onAir()) {
            return;
        }

        if (checkBlock(baseVec, base)) {
            return;
        }

        for (int d = 1; d <= 6; d++) {
            if (checkBlock(baseVec, new BlockPos(baseX, getYLevel() - d, baseZ))) {
                return;
            }

            for (int x = 0; x <= d; x++) {
                for (int z = 0; z <= d - x; z++) {
                    int y = d - x - z;
                    for (int rev1 = 0; rev1 <= 1; rev1++) {
                        for (int rev2 = 0; rev2 <= 1; rev2++) {
                            BlockPos pos = new BlockPos(
                                    baseX + (rev1 == 0 ? x : -x),
                                    getYLevel() - y,
                                    baseZ + (rev2 == 0 ? z : -z)
                            );
                            checkBlock(baseVec, pos);
                        }
                    }
                }
            }
        }
    }

    private boolean checkBlock(Vec3 baseVec, BlockPos pos) {
        if (!onAir() || pos.getY() > getYLevel()) {
            return false;
        }

        Vec3 center = Vec3.atBottomCenterOf(pos);
        for (Direction dir : Direction.values()) {
            Vec3 normal = dir.getUnitVec3();
            Vec3 hit = center.add(normal.scale(0.5));
            BlockPos baseBlockPos = pos.relative(dir);

            BlockState state = mc.level.getBlockState(baseBlockPos);
            if (state.getCollisionShape(mc.level, baseBlockPos).isEmpty() || state.getMenuProvider(mc.level, baseBlockPos) != null) {
                continue;
            }

            Direction face = dir.getOpposite();
            Vec3 relevant = hit.subtract(baseVec);

            if (relevant.lengthSqr() > lengthSqr || relevant.dot(normal) < 0.0) {
                continue;
            }

            if (face == Direction.UP && mc.player.isMoving() && !mc.options.keyJump.isDown()) {
                continue;
            }

            lengthSqr = relevant.lengthSqr();
            blockPos = baseBlockPos;
            direction = face;
            return true;
        }

        return false;
    }

    private Rot2f getRotation(BlockPos pos, Direction direction) {
        if (rotation == null) {
            return new Rot2f(Mth.wrapDegrees(mc.player.getYRot() - 135.0F), 82.0F);
        }

        if (!onAir() || pos == null || direction == null) {
            return rotation;
        }

        Rot2f calculated = RotationUtils.calculate(pos, direction);
        float[] yawArray = new float[]{
                -135F,
                -90F,
                -45F,
                0F,
                45F,
                90F,
                135F,
                180F
        };
        float baseYaw = Mth.wrapDegrees(inputYaw - 180);

        for (int i = 1; i < yawArray.length; i++) {
            float key = yawArray[i];
            int j = i - 1;
            while (j >= 0 && Math.abs(Mth.wrapDegrees(baseYaw - yawArray[j])) > Math.abs(Mth.wrapDegrees(baseYaw - key))) {
                yawArray[j + 1] = yawArray[j];
                j = j - 1;
            }
            yawArray[j + 1] = key;
        }

        float[] pitchArray = {75.0F, 82.0F, 87.0F};

        float[] finalYawArray = new float[yawArray.length + 1];
        System.arraycopy(yawArray, 0, finalYawArray, 0, yawArray.length);
        finalYawArray[yawArray.length] = calculated.getYaw();

        for (float yaw : finalYawArray) {
            if (degrees45.getValue() && yaw % 90 == 0) {
                continue;
            }
            for (float pitch : pitchArray) {
                Rot2f candidate = new Rot2f(yaw + MathUtils.getRandom(-0.3f, 0.3f), pitch + MathUtils.getRandom(-0.3f, 0.3f));
                boolean matches = raytrace.is(RaytraceMode.Normal)
                        ? RaytraceUtils.overBlock(candidate, pos)
                        : RaytraceUtils.overBlock(candidate, pos, direction);
                if (matches) {
                    return candidate;
                }
            }

            for (int pitch = -90; pitch < 90; pitch++) {
                Rot2f candidate = new Rot2f(yaw, pitch);
                boolean matches = raytrace.is(RaytraceMode.Normal)
                        ? RaytraceUtils.overBlock(candidate, pos)
                        : RaytraceUtils.overBlock(candidate, pos, direction);
                if (matches) {
                    return candidate;
                }
            }
        }

        return calculated;
    }

    private boolean onAir() {
        Vec3 baseVec = mc.player.getEyePosition();
        BlockPos base = BlockPos.containing(baseVec.x, getYLevel(), baseVec.z);
        return mc.level.getBlockState(base).canBeReplaced();
    }

    private Vec3 getVec3(BlockPos pos, Direction face) {
        double x = pos.getX() + 0.5;
        double y = pos.getY() + 0.5;
        double z = pos.getZ() + 0.5;

        if (face != Direction.UP && face != Direction.DOWN) {
            y += 0.08;
        } else {
            x += MathUtils.getRandom(-0.3, 0.3);
            z += MathUtils.getRandom(-0.3, 0.3);
        }

        if (face == Direction.WEST || face == Direction.EAST) {
            z += MathUtils.getRandom(-0.3, 0.3);
        }

        if (face == Direction.SOUTH || face == Direction.NORTH) {
            x += MathUtils.getRandom(-0.3, 0.3);
        }

        return new Vec3(x, y, z);
    }

    private FindItemResult findBlockResult() {
        ItemStack offhandStack = mc.player.getOffhandItem();
        if (isValidStack(offhandStack)) {
            return new FindItemResult(40, offhandStack.getCount(), offhandStack.getMaxStackSize());
        }
        return swapMode.is(SwapMode.InvSwitch) ? InvUtils.find(this::isValidStack) : InvUtils.findInHotbar(this::isValidStack);
    }

    private boolean canUseBlockResult() {
        if (blockResult.isOffhand()) return isValidStack(mc.player.getOffhandItem());
        return !swapMode.is(SwapMode.None) || isValidStack(mc.player.getInventory().getSelectedItem());
    }

    private void swap() {
        if (blockResult.isOffhand()) {
            return;
        }

        switch (swapMode.getValue()) {
            case Normal -> {
                int selectedSlot = mc.player.getInventory().getSelectedSlot();
                InvUtils.swap(blockResult.slot(), true);
                if (swapBack.getValue() && blockResult.slot() != selectedSlot) {
                    shouldSwapBack = true;
                }
            }
            case Silent -> InvUtils.swap(blockResult.slot(), true);
            case InvSwitch -> InvUtils.invSwap(blockResult.slot());
        }
    }

    private void swapBack() {
        if (blockResult.isOffhand()) {
            return;
        }

        switch (swapMode.getValue()) {
            case Silent -> InvUtils.swapBack();
            case InvSwitch -> InvUtils.invSwapBack();
        }
    }

    /** Elowen 引擎也要用它（原 elowen {@code Scaffold.s(ItemStack)}）。 */
    public boolean isValidStack(ItemStack stack) {
        if (stack == null || stack.isEmpty() || !(stack.getItem() instanceof BlockItem)) {
            return false;
        }

        String name = stack.getDisplayName().getString();
        if (name.contains("Click") || name.contains("点击")) {
            return false;
        }

        if (stack.getItem() instanceof StandingAndWallBlockItem) {
            return false;
        }

        Block block = ((BlockItem) stack.getItem()).getBlock();
        if (block instanceof FlowerBlock || block instanceof BushBlock || block instanceof NetherFungusBlock || block instanceof CropBlock) {
            return false;
        }

        return !(block instanceof SlabBlock) && !blacklistedBlocks.contains(block);
    }

    public boolean isEmergencyPlacementActive() {
        return isEnabled() && emergencyPlacementActive;
    }

    // ===== 以下为 OpenPal ScaffoldModule 的 SkipTick 自救设施（§4.16，逐行照抄）=====

    private static final Direction[] DIRECTIONS = Direction.values();

    /** OpenPal {@code ScaffoldModule.isSolidAndNonInteractive}。 */
    private boolean isSolidAndNonInteractive(final BlockState state, final BlockPos pos) {
        return mc.level != null
                && !state.getCollisionShape(mc.level, pos).isEmpty()
                && state.getMenuProvider(mc.level, pos) == null;
    }

    /**
     * OpenPal {@code ScaffoldModule.getPlaceableBlock()}。
     * [适配] OpenPal 用 {@code realStackSizeMap}（未搬运的通用流水线的堆叠计数缓存）与
     * {@code InventoryUtility.isGoodBlock}；本仓库改用既有的 {@link #isValidStack}（同一套方块白/黑名单）。
     */
    private int getPlaceableBlock() {
        for (int i = 0; i < 9; i++) {
            final ItemStack itemStack = mc.player.getInventory().getItem(i);
            if (isValidStack(itemStack)) {
                return i;
            }
        }
        return -1;
    }

    /** OpenPal {@code ScaffoldModule.updateSkipTickRecoveryGroundState}。 */
    private void updateSkipTickRecoveryGroundState() {
        final Stuck stuckModule = Stuck.INSTANCE;
        if (!stuckModule.isEnabled() && !this.skipTickRecoveryActive) {
            return;
        }

        boolean groundBelow = mc.player.onGround();
        if (!groundBelow) {
            for (double offset = 0.01; offset <= 1.5; offset += 0.5) {
                if (!mc.level.getBlockState(BlockPos.containing(mc.player.getX(), mc.player.getY() - offset, mc.player.getZ())).isAir()) {
                    groundBelow = true;
                    break;
                }
            }
        }

        if (groundBelow) {
            if (stuckModule.isEnabled()) {
                stuckModule.setEnabled(false);
            }
            this.skipTickRecoveryActive = false;
            SkipTickUtility.reset();
            this.skipTickRecoveryFailed = false;
        }
    }

    /** OpenPal {@code ScaffoldModule.findRecoveryBlock}。 */
    private BlockPos findRecoveryBlock() {
        if (mc.player == null || mc.level == null) return null;

        BlockPos playerPos = mc.player.blockPosition();
        for (int y = 0; y >= -3; y--) {
            for (int x = -2; x <= 2; x++) {
                for (int z = -2; z <= 2; z++) {
                    BlockPos target = playerPos.offset(x, y, z);
                    if (mc.level.getBlockState(target).canBeReplaced()) {
                        for (Direction dir : DIRECTIONS) {
                            BlockPos neighbor = target.relative(dir);
                            if (!mc.level.getBlockState(neighbor).isAir() && !mc.level.getBlockState(neighbor).canBeReplaced()) {
                                return target;
                            }
                        }
                    }
                }
            }
        }
        return null;
    }

    /** OpenPal {@code ScaffoldModule.findReadySkipTickRecoveryTarget}。 */
    private SkipTickRecoveryTarget findReadySkipTickRecoveryTarget() {
        if (mc.player == null || mc.level == null) {
            return null;
        }

        final Rot2f currentRotation = getCurrentClientRotation();
        if (currentRotation == null) {
            return null;
        }

        final Vec3 eyePos = mc.player.getEyePosition();
        final BlockPos playerPos = mc.player.blockPosition();
        SkipTickRecoveryTarget bestTarget = null;

        for (int y = 0; y >= -3; y--) {
            for (int x = -2; x <= 2; x++) {
                for (int z = -2; z <= 2; z++) {
                    final BlockPos targetPos = playerPos.offset(x, y, z);
                    if (!mc.level.getBlockState(targetPos).canBeReplaced()) {
                        continue;
                    }

                    for (Direction direction : DIRECTIONS) {
                        final BlockPos supportPos = targetPos.relative(direction);
                        if (!isSolidAndNonInteractive(mc.level.getBlockState(supportPos), supportPos)) {
                            continue;
                        }

                        final Direction face = direction.getOpposite();
                        final Rot2f rotation = RotationUtility.getRotationFromBlock(supportPos, face);
                        final float rotationDifference = RotationUtility.getRotationDifference(currentRotation, rotation);
                        if (rotationDifference > 16.0F) {
                            continue;
                        }

                        final Vec3 supportCenter = new Vec3(
                                supportPos.getX() + 0.5D,
                                supportPos.getY() + 0.5D,
                                supportPos.getZ() + 0.5D
                        );
                        final double dx = eyePos.x - supportCenter.x;
                        final double dy = eyePos.y - supportCenter.y;
                        final double dz = eyePos.z - supportCenter.z;
                        final double distanceSq = dx * dx + dy * dy + dz * dz;
                        if (distanceSq > 4.85D * 4.85D) {
                            continue;
                        }

                        if (bestTarget == null
                                || distanceSq < bestTarget.distanceSq
                                || (Math.abs(distanceSq - bestTarget.distanceSq) < 1.0E-4D
                                && rotationDifference < bestTarget.rotationDifference)) {
                            bestTarget = new SkipTickRecoveryTarget(targetPos, supportPos, face, rotation, rotationDifference, distanceSq);
                        }
                    }
                }
            }
        }

        return bestTarget;
    }

    /** OpenPal {@code ScaffoldModule.getCurrentClientRotation}。 */
    private Rot2f getCurrentClientRotation() {
        if (mc.player == null) {
            return null;
        }
        return RotationHelper.getClientHandler().getRotation();
    }

    /** OpenPal {@code ScaffoldModule.isFallingIntoVoid}。 */
    private boolean isFallingIntoVoid() {
        if (mc.player == null || mc.level == null) return false;
        if (mc.player.getY() < -5) return true;

        BlockPos pos = mc.player.blockPosition();
        for (int y = (int) mc.player.getY(); y > -64; y--) {
            if (!mc.level.getBlockState(new BlockPos(pos.getX(), y, pos.getZ())).isAir()) {
                return false;
            }
        }
        return true;
    }

    /** OpenPal {@code ScaffoldModule.shouldTriggerSkipTickRecovery}。 */
    private boolean shouldTriggerSkipTickRecovery() {
        if (mc.player == null || mc.level == null || this.skipTickRecoveryActive || this.skipTickRecoveryFailed) {
            return false;
        }

        if (getScaffoldSettings().getSelfRescueMode() != ScaffoldSettings.SelfRescueMode.SkipTick) {
            return false;
        }

        if (isUitemsMode()) {
            // OpenPal: getActiveMode() instanceof HeypixelScaffold → shouldSuppressSkipTickRecoveryTrigger()
            if (getActiveUitemsMode().shouldSuppressSkipTickRecoveryTrigger()) {
                return false;
            }
        }

        final boolean overVoid = isFallingIntoVoid();
        final boolean fallingFast = mc.player.getDeltaMovement().y < -0.35D;
        final boolean hardFall = mc.player.fallDistance > 2.0F;
        if (!overVoid && !fallingFast && !hardFall) {
            return false;
        }
        return getPlaceableBlock() != -1 || mc.player.getMainHandItem().getItem() instanceof BlockItem;
    }

    /** OpenPal {@code ScaffoldModule.tryArmSkipTickRecovery}（§4.3 接线里叫 {@code armSkipTickRecovery}）。 */
    private void armSkipTickRecovery() {
        if (!shouldTriggerSkipTickRecovery()) {
            this.skipTickRecoveryCandidateTicks = 0;
            this.skipTickRecoveryBlockPos = null;
            this.skipTickRecoveryFace = null;
            return;
        }

        if (mode.is(Mode.Heypixel) && getScaffoldSettings().isTelly() && mc.player != null && !mc.player.onGround() && mc.player.getDeltaMovement().y > -0.16D) {
            this.skipTickRecoveryCandidateTicks = 0;
            this.skipTickRecoveryBlockPos = null;
            this.skipTickRecoveryFace = null;
            return;
        }

        if (findRecoveryBlock() == null) {
            this.skipTickRecoveryCandidateTicks = 0;
            this.skipTickRecoveryBlockPos = null;
            this.skipTickRecoveryFace = null;
            return;
        }

        final SkipTickRecoveryTarget recoveryTarget = findReadySkipTickRecoveryTarget();
        if (recoveryTarget == null) {
            this.skipTickRecoveryCandidateTicks = 0;
            this.skipTickRecoveryBlockPos = null;
            this.skipTickRecoveryFace = null;
            return;
        }

        this.skipTickRecoveryBlockPos = recoveryTarget.supportPos();
        this.skipTickRecoveryFace = recoveryTarget.face();

        this.skipTickRecoveryCandidateTicks = Math.min(this.skipTickRecoveryCandidateTicks + 1, 2);
        if (this.skipTickRecoveryCandidateTicks < 2) {
            return;
        }

        if (this.skipTickRecoveryActive) {
            return;
        }

        this.skipTickRecoveryActive = true;
        this.skipTickRecoveryCandidateTicks = 0;
        SkipTickUtility.reset();
        SkipTickUtility.addSkipTicks(12);

        final Stuck stuckModule = Stuck.INSTANCE;
        if (stuckModule.isEnabled()) {
            stuckModule.setEnabled(false);
        }
    }

    public BlockPos getSkipTickRecoveryBlockPos() {
        return skipTickRecoveryBlockPos;
    }

    public Direction getSkipTickRecoveryFace() {
        return skipTickRecoveryFace;
    }

    public boolean isSkipTickRecoveryActive() {
        return skipTickRecoveryActive;
    }

    public void setSkipTickRecoveryActive(final boolean skipTickRecoveryActive) {
        this.skipTickRecoveryActive = skipTickRecoveryActive;
        if (!skipTickRecoveryActive) {
            SkipTickUtility.reset();
        }
        this.skipTickRecoveryCandidateTicks = 0;
        this.skipTickRecoveryBlockPos = null;
        this.skipTickRecoveryFace = null;
    }

    public void markSkipTickRecoveryFailed() {
        this.skipTickRecoveryFailed = true;
        this.skipTickRecoveryActive = false;
        SkipTickUtility.reset();
        this.skipTickRecoveryCandidateTicks = 0;
        this.skipTickRecoveryBlockPos = null;
        this.skipTickRecoveryFace = null;
    }

    private record SkipTickRecoveryTarget(BlockPos targetPos, BlockPos supportPos, Direction face, Rot2f rotation,
                                          float rotationDifference, double distanceSq) {
    }


    private record RenderInfo(
            AABB aabb, Color lineColor, Color sideColor, long startTime, boolean fade, boolean shrink
    ) {
    }

}
