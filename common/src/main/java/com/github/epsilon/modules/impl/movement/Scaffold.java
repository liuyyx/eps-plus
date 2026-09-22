package com.github.epsilon.modules.impl.movement;

import com.github.epsilon.Constants;
import com.github.epsilon.assets.i18n.EpsilonTranslations;
import com.github.epsilon.events.bus.EventBus;
import com.github.epsilon.events.bus.EventHandler;
import com.github.epsilon.events.bus.EventPriority;
import com.github.epsilon.events.bus.listeners.ConsumerListener;
import com.github.epsilon.events.impl.KeyboardInputEvent;
import com.github.epsilon.events.impl.PacketEvent;
import com.github.epsilon.events.impl.PlayerTickEvent;
import com.github.epsilon.events.impl.Render3DEvent;
import com.github.epsilon.graphics.schedulers.render3d.Render3DScheduler;
import com.github.epsilon.managers.NotificationManager;
import com.github.epsilon.managers.rotation.RotationManager;
import com.github.epsilon.modules.Category;
import com.github.epsilon.modules.Module;
import com.github.epsilon.settings.Setting;
import com.github.epsilon.settings.impl.*;
import com.github.epsilon.utils.math.MathUtils;
import com.github.epsilon.utils.player.FallingPlayer;
import com.github.epsilon.utils.player.FindItemResult;
import com.github.epsilon.utils.player.InvUtils;
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
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.StandingAndWallBlockItem;
import net.minecraft.world.item.context.BlockPlaceContext;
import net.minecraft.world.item.context.UseOnContext;
import net.minecraft.world.level.block.*;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;

import java.awt.*;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Random;

public class Scaffold extends Module {

    public static final Scaffold INSTANCE = new Scaffold();

    private Scaffold() {
        super("Scaffold", Category.MOVEMENT);
        EventBus.INSTANCE.subscribe(new ConsumerListener<>(Render3DEvent.class,
                event -> {
                    if (!render.getValue() || renderBoxes.isEmpty()) return;

                    long time = System.currentTimeMillis();
                    long fadeTime = this.fadeTime.getValue().longValue();

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
    }

    private enum Mode {
        TellyBridge,
        GodBridge,
        Legit
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
     * Classic 沿用 Epsilon 原有手感；Polar 追加 LiquidBounce GodBridge 的
     * 假点击、边缘动作与 Sigmoid 转向。
     */
    private enum GodBridgeVariant {
        Classic,
        Polar
    }

    /** Polar 变体的转向平滑方式，对应 LB 的 {@code AngleSmooth}。 */
    private enum PolarRotationSmooth {
        Linear,
        Sigmoid
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
    private final BoolSetting snap = boolSetting("Snap", false, () -> mode.is(Mode.GodBridge));
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
     * ===== 神桥 Polar 变体 =====
     * 默认值取自社区 polar（pika-network）配置里 Scaffold 段的存档值，
     * 未覆盖到的项取 LiquidBounce 源码默认值。
     */

    /** 变体开关：只有神桥模式 + Polar 才启用下面这组设置。 */
    private final EnumSetting<GodBridgeVariant> godBridgeVariant =
            enumSetting("God Bridge Variant", GodBridgeVariant.Classic, () -> mode.is(Mode.GodBridge));

    /**
     * Polar 流水线的生效条件，供下面所有设置复用。
     * 含 Legit（原"蹲起搭"）：该模式已被这套 LB 实现覆盖，因此同样使用这些设置。
     */
    private final Setting.Dependency polarDependency =
            () -> mode.is(Mode.Legit) || (mode.is(Mode.GodBridge) && godBridgeVariant.is(GodBridgeVariant.Polar));

    /**
     * 边缘动作。LB 的 {@code GodBridge.Modes} 是**多选**（可以同时勾 Jump 和 StopInput），
     * Epsilon 没有多选枚举设置，这里按同义展开成 4 个开关：默认只开 Jump（= polar 配置的 Modes=[Jump]）。
     * 四个都不勾 = 不做任何边缘动作。
     */
    private final BoolSetting polarLedgeJumpAction =
            boolSetting("Ledge Jump", true, polarDependency);
    private final BoolSetting polarLedgeSneakAction =
            boolSetting("Ledge Sneak", false, polarDependency);
    private final BoolSetting polarLedgeStopAction =
            boolSetting("Ledge Stop Input", false, polarDependency);
    private final BoolSetting polarLedgeBackAction =
            boolSetting("Ledge Step Back", false, polarDependency);
    /** 方块少于该数量时强制改用潜行（LB ForceSneakBelowCount）。 */
    private final IntSetting polarForceSneakBelow =
            intSetting("Force Sneak Below Count", 5, 0, 10, 1, polarDependency);
    /** 潜行边缘动作的持续刻数（LB SneakTime）。 */
    private final IntSetting polarSneakTimeMin =
            intSetting("Sneak Time Min", 1, 1, 10, 1, polarDependency);
    private final IntSetting polarSneakTimeMax =
            intSetting("Sneak Time Max", 1, 1, 10, 1, polarDependency);
    /** 假点击总开关（LB SimulatePlacementAttempts）。 */
    private final BoolSetting polarFakeClick = boolSetting("Fake Click", true, polarDependency);
    /** 假点击节奏（LB Clicker.CPS）。 */
    private final IntSetting polarFakeClickMinCps =
            intSetting("Fake Click Min CPS", 11, 1, 60, 1, polarDependency);
    private final IntSetting polarFakeClickMaxCps =
            intSetting("Fake Click Max CPS", 12, 1, 60, 1, polarDependency);
    /** 只在"这一下放不下去"时补点（LB FailedAttemptsOnly，配置为关）。 */
    private final BoolSetting polarFakeClickFailedOnly =
            boolSetting("Fake Click Failed Only", false, polarDependency);
    /** 转向平滑方式（LB AngleSmooth，配置为 Sigmoid）。 */
    private final EnumSetting<PolarRotationSmooth> polarRotationSmooth =
            enumSetting("Rotation Smooth", PolarRotationSmooth.Sigmoid, polarDependency);
    /** Sigmoid 转向的速度区间与曲线参数（LB SigmoidAngleSmooth）。 */
    private final DoubleSetting polarSigmoidHorizontalSpeedMin =
            doubleSetting("Sigmoid Horizontal Speed Min", 19.8, 0.0, 180.0, 0.1, polarDependency);
    private final DoubleSetting polarSigmoidHorizontalSpeedMax =
            doubleSetting("Sigmoid Horizontal Speed Max", 40.5, 0.0, 180.0, 0.1, polarDependency);
    private final DoubleSetting polarSigmoidVerticalSpeedMin =
            doubleSetting("Sigmoid Vertical Speed Min", 8.1, 0.0, 180.0, 0.1, polarDependency);
    private final DoubleSetting polarSigmoidVerticalSpeedMax =
            doubleSetting("Sigmoid Vertical Speed Max", 30.6, 0.0, 180.0, 0.1, polarDependency);
    private final DoubleSetting polarSigmoidSteepness =
            doubleSetting("Sigmoid Steepness", 10.0, 0.0, 20.0, 0.1, polarDependency);
    private final DoubleSetting polarSigmoidMidpoint =
            doubleSetting("Sigmoid Midpoint", 0.3, 0.0, 1.0, 0.01, polarDependency);
    /** 挥手只在服务端可见（LB Swing = HideForClient）。 */
    private final BoolSetting polarHideSwingOnClient =
            boolSetting("Hide Swing On Client", true, polarDependency);

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

    /** 边缘判定的阈值（玩家距离所在方块边界的距离）。 */
    private static final double EDGE_THRESHOLD = 0.15;
    private final Random placeRandom = new Random();

    /** 放置冷却剩余刻数；>0 时不放置。见 {@link #placeDelay}。 */
    private int placeDelayCounter = 0;

    /** Polar 诊断输出（默认关闭）。排查放置闸门时临时打开。 */
    private static final boolean POLAR_DEBUG = false;

    /** Polar 假点击节奏累加器：每刻按 CPS 累加，满 1 发一次点击。 */
    private double polarClickAccumulator;
    /** Polar 本刻的边缘动作（每刻重算，避免残留）。 */
    private boolean polarLedgeJump;
    private boolean polarLedgeStopInput;
    private boolean polarLedgeBackwards;
    private int polarLedgeSneakTicks;
    /** Polar 强制潜行的剩余刻数，跨刻保留（LB 的 forceSneak）。 */
    private int polarForceSneak;
    /** Polar 直行搭桥时的左右侧交替状态（LB GodBridge 的 isOnRightSide）。 */
    private boolean polarOnRightSide;
    /** 本刻是否真的放上了方块（边缘动作的等价判据，每刻复位）。 */
    private boolean polarPlacedThisTick;
    /** 上一拍是否真的放上了方块；tick 开头顺延，供边缘动作判定使用。 */
    private boolean polarPlacedLastTick;
    /**
     * LB {@code BlockPlacementTarget}：搜出来的放置目标。
     *
     * @param interacted 要点右键的那一格（LB interactedBlockPos）
     * @param placed     新方块落下的那一格（LB placedBlock）
     * @param direction  点的是哪一面（LB interactionDirection）
     * @param point      该面上的命中点（LB CenterTargetPositionFactory = 面中心）
     * @param minY       命中高度下限（LB minPlacementY）
     * @param rotation   朝该命中点的角度（LB Rotation.lookingAt）
     */
    private record PolarTarget(BlockPos interacted, BlockPos placed, Direction direction,
                               Vec3 point, double minY, Rot2f rotation) {

        /** LB {@code BlockPlacementTarget.doesCrosshairTargetMatchRequirements}。 */
        private boolean matches(BlockHitResult hit) {
            return hit.getBlockPos().equals(interacted)
                    && hit.getDirection() == direction
                    && hit.getLocation().y >= minY;
        }
    }

    /** LB {@code BlockPosOffsets.NORMAL}：以目标格为中心、Y 取 0 / -1 的 3×3 两层共 18 个偏移。 */
    private static final List<BlockPos> POLAR_OFFSETS = polarOffsets();
    /** 每刻复用的候选排序结果（避免每刻新建列表）。 */
    private static final List<BlockPos> POLAR_CANDIDATES = new ArrayList<>(18);

    private static List<BlockPos> polarOffsets() {
        List<BlockPos> offsets = new ArrayList<>(18);
        for (int x = -1; x <= 1; x++) {
            for (int z = -1; z <= 1; z++) {
                offsets.add(new BlockPos(x, 0, z));
                offsets.add(new BlockPos(x, -1, z));
            }
        }
        return offsets;
    }

    /** 本刻的 Polar 放置目标（每刻算一次，放置与边缘动作共用同一结果）。 */
    private PolarTarget polarTarget;

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
        resetPolarState();
    }

    @Override
    protected void onDisable() {
        yLevel = 0;
        emergencyPlacementActive = false;
        pearlUsePacketSent = false;
        resetPolarState();
        if (shouldSwapBack) {
            InvUtils.swapBack();
            shouldSwapBack = false;
        }
    }

    @EventHandler
    private void onPlayerTick(PlayerTickEvent.Pre event) {
        if (!event.isCancelled()) emergencyPlacementActive = false;

        // 先顺延"上一拍是否放上"，再清本拍状态
        polarPlacedLastTick = polarPlacedThisTick;
        resetPolarLedgeAction();

        // Polar：本刻先算一次"能不能放"（托管转向的真实射线命中可替换格），
        // 放置与边缘动作共用它，避免两边口径不一致（日志里曾出现两边都判 false、
        // 动作每刻狂触发、而放置一秒才一两个）。
        boolean polar = polarDependency.check();
        polarTarget = polar ? findPolarTarget(predictPolarPlacementPos()) : null;

        // 边缘动作在 tick 最开头评估：它只看"上一拍的状态"，因此不受后面那些早退
        // （没方块 / 没有放置目标 / 紧急放置）影响。之前挂在 handlePolar() 尾部，
        // 只有"手里有方块 && 站在虚空格上(onAir) && 这一拍没放成"那种极窄状态才会执行到。
        if (polar) updatePolarLedgeAction();

        if (placeDelayCounter > 0) placeDelayCounter--;

        blockResult = findBlockResult();

        if (!blockResult.found()) return;

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
            emergencyPlacementActive = true;
            event.cancel();

            rotateCount++;
            if (blockPos == null) return;
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
            return;
        } else {
            rotateCount = 0;
        }

        switch (mode.getValue()) {
            case TellyBridge -> handleTelly();
            case GodBridge -> handleGodBridge();
            case Legit -> handleLegit();
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST)
    private void onMoveInput(KeyboardInputEvent event) {
        forwardInput = event.getForward();
        strafeInput = event.getStrafe();
        inputYaw = Mth.wrapDegrees(Math.round((mc.player.getYRot() + (float) Math.toDegrees(Math.atan2(-strafeInput, forwardInput))) / 45.0F) * 45.0F);
        rawInputYaw = Mth.wrapDegrees(Math.round(mc.player.getYRot() + (float) Math.toDegrees(Math.atan2(-strafeInput, forwardInput))));

        if (mode.is(Mode.TellyBridge) && mc.player.onGround() && !mc.options.keyJump.isDown() && mc.player.isMoving()) {
            event.setJump(true);
        }
    }

    /**
     * Polar 边缘动作的注入点：放在 {@link EventPriority#LOW}，
     * 让 MovementFix（HIGH）先按托管转向修正完 WASD，再叠加"停手/后退"，
     * 否则这两个动作会被移动修正再旋转一次。LB 同样在移动修正之后处理边缘动作。
     */
    @EventHandler(priority = EventPriority.LOW)
    private void onPolarLedgeInput(KeyboardInputEvent event) {
        if (!polarDependency.check()) return;

        // LB 的 forceSneak 是跨刻倒计时：先扣减，再让本刻动作续期。
        if (polarForceSneak > 0) {
            event.setSneak(true);
            polarForceSneak--;
        }

        if (polarLedgeJump) {
            event.setJump(true);
        }

        if (polarLedgeStopInput) {
            event.setForward(0.0f);
            event.setStrafe(0.0f);
        }

        if (polarLedgeBackwards) {
            event.setForward(-1.0f);
        }

        if (polarLedgeSneakTicks > polarForceSneak) {
            event.setSneak(true);
            polarForceSneak = polarLedgeSneakTicks;
        }
    }

    @EventHandler
    private void onPacketReceive(PacketEvent.Receive event) {
        if (!nullCheck() && toggleOnTeleport.getValue() && pearlUsePacketSent && event.getPacket() instanceof ClientboundPlayerPositionPacket) {
            pearlUsePacketSent = false;
            NotificationManager.INSTANCE.error(this.getTranslatedName(), EpsilonTranslations.Notifications.SCAFFOLD_TOGGLE_ON_TELEPORT.getTranslatedName());
            setEnabled(false);
        }
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
            rotation = polarDependency.check() ? getPolarRotation() : getRotation(blockPos, direction);
            applyRotation(rotation);
        }
        place();
    }

    /**
     * Polar 的转向目标。
     *
     * <p><b>搜到目标就朝它看。</b>{@link #findPolarTarget} 给出的 {@code rotation} 是用
     * <b>真实眼睛</b>算到目标面中心的（见该方法的注释），因此只要转向到位，
     * {@link #polarCrosshairHit()} 就必然压在那一面上，{@link #polarPlace()} 的命中闸门才可能通过。
     * LB 的 {@code getRotationForNoInput} 也是这条路（{@code Rotation.lookingAt(facePoint)}）。</p>
     *
     * <p>LB 的 {@code getRotationForStraightInput/DiagonalInput} 那套"移动方向 ±45°、俯仰 75.7/75.6"
     * 定角<b>不能照搬</b>：那组常量是为 LB 自己的移动/渲染体系（托管角会渲染给玩家看、
     * movementCorrection 让走位与视线一致）调出来的；在本客户端里视线只是发包伪装，
     * 俯仰 75.7° 从眼睛出发的射线在水平 0.5 格处已降到 y≈62.85，够不到相邻方块的任何面 ——
     * 闸门永远不通过，表现就是"一格都放不下去"。</p>
     *
     * <p>只在没有目标时（前方还没踏空、或手里没有方块）才退到那套定角，避免转向停住。</p>
     *
     * <p>关于 LB 源码里的 {@code +180}：{@code getMovementDirectionOfInput} 前进时返回的就是
     * 移动方向本身（已对照源码逐分支核算，与 {@link #rawInputYaw} 完全等价），所以
     * {@code +180} 是货真价实的"朝向移动方向的反面"。LB 之所以能这么写，是因为它的托管角
     * 会渲染给玩家、且 movementCorrection 会把走位扭到托管角上；本客户端两者都没有，
     * 照搬就会变成"看着前方却往身后搭"。</p>
     */
    private Rot2f getPolarRotation() {
        PolarTarget target = polarTarget;
        if (target != null) {
            return target.rotation();
        }

        if (forwardInput == 0.0f && strafeInput == 0.0f) {
            return rotation != null ? rotation : new Rot2f(mc.player.getYRot(), mc.player.getXRot());
        }

        float movingYaw = Mth.wrapDegrees(Math.round(rawInputYaw / 45.0f) * 45.0f);

        if (movingYaw % 90.0f != 0.0f) {
            return new Rot2f(movingYaw, 75.6f);
        }

        if (mc.player.onGround()) {
            polarOnRightSide = Mth.floor(mc.player.getX() + Math.cos(Math.toRadians(movingYaw)) * 0.5) != Mth.floor(mc.player.getX())
                    || Mth.floor(mc.player.getZ() + Math.sin(Math.toRadians(movingYaw)) * 0.5) != Mth.floor(mc.player.getZ());

            BlockPos ahead = BlockPos.containing(mc.player.position().relative(Direction.fromYRot(movingYaw), 0.6));
            boolean leaningOffBlock = mc.level.getBlockState(mc.player.blockPosition().below()).isAir();
            boolean aheadIsAir = mc.level.getBlockState(ahead.below()).isAir();
            if (leaningOffBlock && aheadIsAir) polarOnRightSide = !polarOnRightSide;
        }

        return new Rot2f(Mth.wrapDegrees(movingYaw + (polarOnRightSide ? 45.0f : -45.0f)), 75.7f);
    }


    /** 该角度下射线是否命中目标方块（按 {@link #raytrace} 设置的宽严）。 */
    private boolean polarRaycastHits(Rot2f rot) {
        if (blockPos == null || direction == null) return false;

        return switch (raytrace.getValue()) {
            case Normal -> RaytraceUtils.overBlock(rot, blockPos);
            case Strict -> RaytraceUtils.overBlock(rot, blockPos, direction);
        };
    }

    /**
     * Polar 的放置命中结果：只接受"当前托管转向的真实射线正好命中目标方块的目标面"的结果。
     *
     * <p>合法客户端发的永远是射线与面的交点；原来那个合成点落在方块中心平面上（比如 UP 面给的是
     * {@code pos.y + 0.5} 而不是面所在的 {@code +1.0}），既不在面上也不在射线上，
     * NCP 一类检查会直接判"Tried to place a block in an unusual way"。
     * 取不到就这一拍不放，下一拍重新取角。</p>
     */
    /**
     * LB {@code findBestBlockPlacementTarget}：以"预测位置脚下一格"为中心，
     * 在 {@link #POLAR_OFFSETS}（3×3 两层共 18 个偏移）里按"离预测位置最近"逐个尝试，
     * 每个候选格再挑一个"面朝向玩家、且点上去最接近当前朝向"的邻居面作为点击目标。
     *
     * <p>候选格是**先搜出来**的，不依赖当前视线 —— 这正是 LB 的做法；之前拿射线去"搜"格子，
     * 会把玩家自己站的格子当成目标，放置必然失败（反馈的"连搭路都搭不了"）。</p>
     */
    private PolarTarget findPolarTarget(Vec3 predictedPos) {
        if (nullCheck() || predictedPos == null) return null;

        BlockPos targetPos = polarTargetedPosition(predictedPos);
        if (isPolarSolid(targetPos)) return null;

        POLAR_CANDIDATES.clear();
        POLAR_CANDIDATES.addAll(POLAR_OFFSETS);
        POLAR_CANDIDATES.sort(Comparator.comparingDouble(offset -> {
            BlockPos cell = targetPos.offset(offset);
            return cell.distToCenterSqr(predictedPos.x, predictedPos.y, predictedPos.z);
        }));

        // 面的"朝向玩家"检查用预测位置的眼睛（LB 的 PlayerLocationOnPlacement = predictedPos），
        Vec3 searchEye = predictedPos.add(0.0, mc.player.getEyeHeight(mc.player.getPose()), 0.0);
        // 但朝目标的角度必须用**真实眼睛**算：真正放出去的那条射线是从玩家当前眼睛出发的
        // （LB getCrosshairTarget = traceFromPlayer(rotation)），拿 predictedPos 算角度会让
        // 目标离玩家越远偏差越大，polarPlace() 的命中闸门就永远过不去 —— 一格都放不出来。
        Vec3 realEye = mc.player.getEyePosition();

        for (BlockPos offset : POLAR_CANDIDATES) {
            BlockPos cell = targetPos.offset(offset);
            if (isPolarSolid(cell)) continue;

            BlockState state = mc.level.getBlockState(cell);
            boolean replaceExisting = !state.isAir() && state.getFluidState().isEmpty();
            if (replaceExisting && !state.canBeReplaced()) continue;

            PolarTarget best = null;
            double bestDelta = Double.MAX_VALUE;

            for (Direction direction : Direction.values()) {
                BlockPos interacted = cell.relative(direction.getOpposite());
                if (mc.level.getBlockState(interacted).canBeReplaced()) continue;

                Vec3 point = polarFaceCenter(interacted, direction);
                Vec3 toFace = searchEye.subtract(point);
                double length = Math.max(1.0E-4, toFace.length());
                double facing = (toFace.x * direction.getStepX() + toFace.y * direction.getStepY()
                        + toFace.z * direction.getStepZ()) / length;
                if (facing < 0.0) continue;

                Rot2f rotation = RotationUtils.calculate(realEye, point);
                double delta = polarDeltaLength(RotationManager.INSTANCE.getRotation(), rotation);

                if (delta < bestDelta) {
                    bestDelta = delta;
                    best = new PolarTarget(interacted, cell, direction, point,
                            polarMinY(interacted, direction), rotation);
                }
            }

            if (best != null) return best;
        }

        return null;
    }

    /** LB {@code ModuleScaffold.getTargetedPosition}（SameY = Off）：预测位置脚下一格。 */
    private BlockPos polarTargetedPosition(Vec3 predictedPos) {
        return BlockPos.containing(predictedPos).below();
    }

    /**
     * LB {@code ScaffoldMovementPrediction.getPredictedPlacementPos}：预测"下一步要补的那一格"。
     *
     * <p>沿移动方向以 0.25 格为步长探测，取第一个脚下没有支撑的格子，返回它的中心。
     * 该点交给 {@link #polarTargetedPosition} 取 below() 后正好落在那一格上，
     * 于是 {@link #findPolarTarget} 搜的是"前方踏空的格子"而不是玩家脚下的实心格。</p>
     *
     * <p><b>曾经的写法是在"已在边缘"时直接返回玩家当前位置</b>（{@code if (isOnEdge()) return pos;}），
     * 于是放置格退化成玩家自己站的那一格；那一格是实心的，{@link #findPolarTarget} 第一句
     * {@link #isPolarSolid} 就返回 null —— 而 {@link #isOnEdge} 在**腾空时恒为 true**，
     * 也就是说跳跃搭桥与贴边行走（搭桥的绝大多数时刻）**一格都放不下去**。
     * 观测到的"一秒只放一两个方块"即由此而来。</p>
     */
    private Vec3 predictPolarPlacementPos() {
        Vec3 pos = mc.player.position();
        Vec3 dir = polarMovementDirection();
        if (dir == null) return pos;

        double feetY = mc.player.getY();

        for (int step = 1; step <= 8; step++) {
            Vec3 probe = pos.add(dir.x * step * 0.25, 0.0, dir.z * step * 0.25);
            // 与 polarTargetedPosition 的 BlockPos.containing(predictedPos).below() 同层：
            // predictedPos.y 取 feetY，below() 得到 floor(feetY - 0.5) 那一层，这里必须一致。
            BlockPos support = BlockPos.containing(probe.x, feetY - 0.5, probe.z);
            if (!isPolarSolid(support)) {
                return new Vec3(support.getX() + 0.5, feetY, support.getZ() + 0.5);
            }
        }

        return pos;
    }

    /** 移动方向：有输入取输入方向，无输入退到速度方向（LB 用 optimalLine.direction）。 */
    private Vec3 polarMovementDirection() {
        float yaw;
        if (forwardInput != 0.0f || strafeInput != 0.0f) {
            yaw = rawInputYaw;
        } else {
            Vec3 velocity = mc.player.getDeltaMovement();
            if (velocity.x * velocity.x + velocity.z * velocity.z < 1.0E-6) return null;
            yaw = (float) Math.toDegrees(Math.atan2(-velocity.x, velocity.z));
        }

        double radians = Math.toRadians(yaw);
        return new Vec3(-Math.sin(radians), 0.0, Math.cos(radians));
    }

    /** LB {@code isBlockSolid}：以 UP 面支撑判定"实心"（台阶/火把这类非整方块会被排除）。 */
    private boolean isPolarSolid(BlockPos pos) {
        return mc.level.getBlockState(pos).isFaceSturdy(mc.level, pos, Direction.UP);
    }

    /** LB {@code CenterTargetPositionFactory}：整方块的"面中心"。 */
    private Vec3 polarFaceCenter(BlockPos pos, Direction direction) {
        return new Vec3(pos.getX() + 0.5 + direction.getStepX() * 0.5,
                pos.getY() + 0.5 + direction.getStepY() * 0.5,
                pos.getZ() + 0.5 + direction.getStepZ() * 0.5);
    }

    /** LB {@code BlockPlacementTarget.minPlacementY}：命中高度下限（整方块侧面 = 底面，顶面 = 顶面）。 */
    private double polarMinY(BlockPos pos, Direction direction) {
        return pos.getY() + (direction == Direction.UP ? 1.0 : 0.0);
    }

    /** LB {@code technique.getCrosshairTarget} = {@code traceFromPlayer(rotation)}。 */
    private BlockHitResult polarCrosshairHit() {
        HitResult result = RaytraceUtils.raytrace(RotationManager.INSTANCE.getRotation(), 4.5, 0.0f);
        return result instanceof BlockHitResult hit ? hit : null;
    }

    /** LB：当前托管转向的射线是否正好压在搜出来的目标上。 */
    private boolean polarCrosshairMatchesTarget() {
        if (polarTarget == null || nullCheck()) return false;

        BlockHitResult hit = polarCrosshairHit();
        return hit != null && polarTarget.matches(hit);
    }

    /**
     * 神桥入口：Classic 走原逻辑；Polar 在完全复用原放置流程的基础上，
     * 追加 LiquidBounce GodBridge 的假点击与边缘动作。
     */
    private void handleGodBridge() {
        if (!godBridgeVariant.is(GodBridgeVariant.Polar)) {
            handleNormal();
            return;
        }

        handlePolar();
    }

    private void handlePolar() {
        handleNormal();
        polarFakeClick();
    }

    /**
     * 应用转向。Polar + Sigmoid 时改为按加速度曲线自行步进（LB SigmoidAngleSmooth），
     * 其余情况沿用原本的固定 {@link #rotationSpeed}。
     */
    private void applyRotation(Rot2f target) {
        if (!polarDependency.check() || !polarRotationSmooth.is(PolarRotationSmooth.Sigmoid)) {
            RotationManager.INSTANCE.setRotations(target, rotationSpeed.getValue());
            return;
        }

        Rot2f stepped = polarStep(RotationManager.INSTANCE.getRotation(), target,
                MathUtils.getRandom(polarSigmoidHorizontalSpeedMin.getValue(), polarSigmoidHorizontalSpeedMax.getValue()),
                MathUtils.getRandom(polarSigmoidVerticalSpeedMin.getValue(), polarSigmoidVerticalSpeedMax.getValue()));

        // 规范化后再提交：偏航必须落在 (-180,180]、俯仰钳到 ±90。
        // 实测日志里出现过 rot=-214.8（超范围偏航）—— 这种角度发给服务器就是明牌违规。
        stepped = new Rot2f(Mth.wrapDegrees(stepped.getYaw()), Mth.clamp(stepped.getPitch(), -90.0f, 90.0f));

        // 提交与管线基准（lastRotations）完全相同的角度，会让 RotationUtils.move 出现 0/0 的 NaN，
        // 而 NaN 会一直留在托管角里直到重生。偏一个远小于鼠标灵敏度网格的微小量即可避开，
        // 量化后会舍回同一格，观感仍是原地不动。
        if (polarDeltaLength(stepped, RotationManager.INSTANCE.lastRotations) < 1.0E-3) {
            stepped = new Rot2f(stepped.getYaw() + 1.0E-3f, stepped.getPitch());
        }

        // 速度取"本步位移"而不是 180：管线是从 lastRotations 出发算 move() 的，
        // 这一步位移本身就很小，速度略大于它必然落到 stepped；
        // 而把 180 度/刻 这种极端速度留在管线里，会让"停止瞄准后归还视角"
        // 以及 SNAP 模式下的可见转向变成一次瞬移 —— 表现就是"视角被强制拧一下"。
        double step = Math.max(0.5, polarDeltaLength(RotationManager.INSTANCE.lastRotations, stepped) * 1.05);
        RotationManager.INSTANCE.setRotations(stepped, step);
    }

    /**
     * 移植 LB {@code SigmoidAngleSmooth}：从 {@code from} 朝目标推进一刻，
     * 水平/垂直步长按 {@code 1 / (1 + e^(-steepness * (角差 / 120 - midpoint)))} 缩放后
     * 再按方向分配，角差越大步长越大、越接近目标越小。
     */
    private Rot2f polarStep(Rot2f from, Rot2f target, double horizontalSpeed, double verticalSpeed) {
        float deltaYaw = Mth.wrapDegrees(target.getYaw() - from.getYaw());
        float deltaPitch = target.getPitch() - from.getPitch();
        double length = Math.sqrt(deltaYaw * deltaYaw + deltaPitch * deltaPitch);
        if (length < 1.0E-4) return target;

        double factor = polarSigmoidFactor((float) Math.min(length, 180.0));
        double maxYaw = Math.abs(deltaYaw / length) * Math.clamp(factor * horizontalSpeed, 0.0, 180.0);
        double maxPitch = Math.abs(deltaPitch / length) * Math.clamp(factor * verticalSpeed, 0.0, 180.0);

        float yaw = from.getYaw() + (float) Math.clamp(deltaYaw, -maxYaw, maxYaw);
        float pitch = from.getPitch() + (float) Math.clamp(deltaPitch, -maxPitch, maxPitch);
        return new Rot2f(yaw, Mth.clamp(pitch, -90.0f, 90.0f));
    }

    /** Sigmoid 系数，曲线输入为限制到 180 度以内的角差（LB 除以 120 归一）。 */
    private double polarSigmoidFactor(float rotationDifference) {
        double scaled = rotationDifference / 120.0;
        return 1.0 / (1.0 + Math.exp(-polarSigmoidSteepness.getValue() * (scaled - polarSigmoidMidpoint.getValue())));
    }

    private double polarDeltaLength(Rot2f from, Rot2f to) {
        float deltaYaw = Mth.wrapDegrees(to.getYaw() - from.getYaw());
        float deltaPitch = to.getPitch() - from.getPitch();
        return Math.sqrt(deltaYaw * deltaYaw + deltaPitch * deltaPitch);
    }

    /**
     * Polar 假点击：移植 LB {@code ModuleScaffold.SimulatePlacementAttempts}。
     * 与普通放置不同，这一步不要求射线已压在目标方块上，只按 CPS 节奏对着
     * 当前托管转向命中的方块点一下，让"放置尝试"的时序更接近真人。
     */
    private void polarFakeClick() {
        if (!polarFakeClick.getValue() || nullCheck() || !mc.player.isMoving()) return;

        polarClickAccumulator += MathUtils.getRandom(
                polarFakeClickMinCps.getValue(), polarFakeClickMaxCps.getValue()) / 20.0;
        if (polarClickAccumulator < 1.0) return;

        // 节奏已到：先扣掉这一发，再看条件是否允许发出去（与 LB Clicker 被门控跳过时一致）。
        polarClickAccumulator -= 1.0;

        if (!blockResult.found() || !canUseBlockResult()) return;

        HitResult hitResult = RotationManager.INSTANCE.getHitResult();
        if (hitResult.getType() != HitResult.Type.BLOCK || !(hitResult instanceof BlockHitResult hit)) return;

        InteractionHand hand = blockResult.getHand();
        if (!shouldPolarFakeClick(hit, hand)) return;

        placeOn(hit, true);
    }

    /**
     * 是否满足假点击条件（LB {@code simulatePlacementAttempts}）。
     * LB 的 {@code sameYMode} 在配置中为 Off，此时只按"点击位置在脚下高度且不是普通垫塔"
     * 判定，因此这里只保留 {@code FailedAttemptsOnly} 开关：开启时仅在"这一下放不下去"时点击。
     */
    private boolean shouldPolarFakeClick(BlockHitResult hit, InteractionHand hand) {
        ItemStack stack = mc.player.getItemInHand(hand);
        if (!(stack.getItem() instanceof BlockItem blockItem)) return false;

        BlockPlaceContext context = new BlockPlaceContext(new UseOnContext(mc.player, hand, hit));
        // BlockItem#getPlacementState 是 protected：这里用等价的公开组合，
        // 即"能算出放置状态，且该状态能在点击位置存活"。
        BlockState placementState = blockItem.getBlock().getStateForPlacement(context);
        boolean canPlaceOnFace = placementState != null && placementState.canSurvive(mc.level, context.getClickedPos());

        if (polarFakeClickFailedOnly.getValue()) {
            return !canPlaceOnFace;
        }

        boolean targetUnderPlayer = context.getClickedPos().getY() <= mc.player.getBlockY() - 1;
        boolean towering = context.getClickedPos().getY() == mc.player.getBlockY() - 1
                && canPlaceOnFace && context.getClickedFace() == Direction.UP;
        return targetUnderPlayer && !towering;
    }

    /**
     * Polar 边缘动作：移植 LB {@code ScaffoldLedgeFeature.ledge} 与
     * {@code ScaffoldGodBridgeTechnique.ledge}。
     *
     * <p>判据是"在边缘 + 这一拍放不下去"，其中"放不下去"取**当前托管转向的射线没压在目标方块上**
     * （LB 的 crosshair 不满足放置要求）。注意两处坑：不能拿"目标角"去算——{@link #getRotation}
     * 的目标角本来就压在目标方块上，条件恒成立会变成死代码；也不能拿"步进还差几刻"当判据——
     * 那个值在搭桥时几乎恒 ≥1，会变成每刻都在蹲。</p>
     */
    /**
     * Polar 边缘动作：LB {@code ScaffoldLedgeFeature.ledge} + {@code ScaffoldGodBridgeTechnique.ledge}。
     *
     * <p>第一段与勾选的动作无关：边缘上"没方块"或"转向还没到位"（LB {@code ticks >= 1}）→ 潜行。</p>
     *
     * <p>第二段是 GodBridge 扩展：边缘上射线没能压在搜出来的目标上 → 按勾选的动作自保；
     * 方块少于 ForceSneakBelowCount 强制潜行；勾了 Jump 但能跳两格高时改走别的动作。</p>
     */
    private void updatePolarLedgeAction() {
        boolean edge = isOnEdge();
        int blocks = getBlockCount();
        boolean crosshairOnTarget = polarCrosshairMatchesTarget();
        boolean rotationReady = rotation != null
                && polarDeltaLength(RotationManager.INSTANCE.getRotation(), rotation) < 1.0;

        if (POLAR_DEBUG && mc.player.tickCount % 10 == 0) {
            Constants.LOGGER.info("[ScaffoldPolar] edge={} blocks={} delay={} placedLast={} target={} crosshair={} ready={} onAir={} rot={}",
                    edge, blocks, placeDelayCounter, polarPlacedLastTick, polarTarget != null, crosshairOnTarget,
                    rotationReady, onAir(),
                    rotation == null ? "null" : String.format("%.1f/%.1f", rotation.getYaw(), rotation.getPitch()));
        }

        if (!edge) return;

        // 1) LB ledge()：边缘 + （没方块 | 转向没到位）→ 潜行
        if (blocks <= 0 || !rotationReady) {
            polarLedgeSneakTicks = polarSneakTicks();
            debugAction("sneak(blocks=" + blocks + ",ready=" + rotationReady + ")");
            return;
        }

        // 2) LB GodBridge.ledge()：射线已经压在目标上 → 不需要任何自保动作
        if (crosshairOnTarget) return;

        // 3) 方块少于阈值 → 强制潜行
        if (blocks < polarForceSneakBelow.getValue()) {
            polarLedgeSneakTicks = polarSneakTicks();
            debugAction("sneak(low-blocks=" + blocks + ")");
            return;
        }

        // 4) 按勾选的动作执行（可多选，对应 LB Modes 的多选）
        if (polarLedgeJumpAction.getValue()) {
            if (!canJumpTwoBlocksHigh()) {
                polarLedgeJump = true;
                debugAction("action=jump");
            } else {
                polarLedgeSneakTicks = polarSneakTicks();
                debugAction("action=jump->sneak(can-jump-2-high)");
            }
        }
        if (polarLedgeSneakAction.getValue()) {
            polarLedgeSneakTicks = polarSneakTicks();
            debugAction("action=sneak");
        }
        if (polarLedgeStopAction.getValue()) {
            polarLedgeStopInput = true;
            debugAction("action=stop-input");
        }
        if (polarLedgeBackAction.getValue()) {
            polarLedgeBackwards = true;
            debugAction("action=step-back");
        }
    }

    private void debugAction(String what) {
        if (POLAR_DEBUG && mc.player.tickCount % 10 == 0) {
            Constants.LOGGER.info("[ScaffoldPolar] {}", what);
        }
    }

    private int polarSneakTicks() {
        int min = polarSneakTimeMin.getValue();
        int max = polarSneakTimeMax.getValue();
        return min + (max > min ? placeRandom.nextInt(max - min + 1) : 0);
    }

    /**
     * 原版跳跃能否上 2 格（LB {@code canJumpTwoBlocksHigh}）。
     * LB 用的是 {@code player.jumpPower}，而 {@code LivingEntity#getJumpPower} 是 protected，
     * 这里改读同源的跳跃强度属性（含跳跃提升等修饰符）。
     */
    private boolean canJumpTwoBlocksHigh() {
        double verticalMotion = mc.player.getAttributeValue(Attributes.JUMP_STRENGTH);
        double height = 0.0;

        while (verticalMotion > 0.0) {
            height += verticalMotion;
            verticalMotion = (verticalMotion - 0.08) * 0.98;
        }

        return height >= 2.0;
    }

    /** 当前托管转向的射线是否压在目标方块（面上）；与 {@code place()} 的放置闸门同一判据。 */
    private boolean raytraceOverTarget() {
        return polarRaycastHits(RotationManager.INSTANCE.getRotation());
    }

    private void resetPolarLedgeAction() {
        polarLedgeJump = false;
        polarLedgeStopInput = false;
        polarLedgeBackwards = false;
        polarLedgeSneakTicks = 0;
        polarPlacedThisTick = false;
        polarTarget = null;
    }

    private void resetPolarState() {
        polarClickAccumulator = 0.0;
        polarForceSneak = 0;
        polarOnRightSide = false;
        resetPolarLedgeAction();
    }

    /**
     * Legit（原"蹲起搭"）。原实现（固定潜行刻数 + rotationTick 闸门）只能过 Grim 一类，
     * 过不了 Polar，因此整段被 LB 那套 polar 搭桥覆盖：走"定角转向 + Sigmoid 平滑 +
     * 假点击 + 边缘动作（走一步蹲一步 / 到边缘跳）"的同一条流程。
     */
    private void handleLegit() {
        handlePolar();
    }

    /**
     * 边缘检测：脚下为可替换方块，或玩家位于方块边缘阈值内且相邻方块下方可替换。
     */
    private boolean isOnEdge() {
        if (!mc.player.onGround()) return true;

        int playerX = Mth.floor(mc.player.getX());
        int playerY = Mth.floor(mc.player.getY());
        int playerZ = Mth.floor(mc.player.getZ());

        if (mc.level.getBlockState(new BlockPos(playerX, playerY - 1, playerZ)).canBeReplaced()) return true;

        double xOff = mc.player.getX() - playerX;
        double zOff = mc.player.getZ() - playerZ;
        if (xOff < EDGE_THRESHOLD || xOff > 1.0 - EDGE_THRESHOLD
                || zOff < EDGE_THRESHOLD || zOff > 1.0 - EDGE_THRESHOLD) {
            int checkX = playerX + (xOff < EDGE_THRESHOLD ? -1 : (xOff > 1.0 - EDGE_THRESHOLD ? 1 : 0));
            int checkZ = playerZ + (zOff < EDGE_THRESHOLD ? -1 : (zOff > 1.0 - EDGE_THRESHOLD ? 1 : 0));
            if (checkX != playerX || checkZ != playerZ) {
                if (mc.level.getBlockState(new BlockPos(checkX, playerY - 1, checkZ)).canBeReplaced()) return true;
            }
        }
        return false;
    }

    private void place() {
        if (polarDependency.check()) {
            polarPlace();
            return;
        }

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
     * Polar 的放置：不套 Epsilon 那套"脚下必须是虚空格(onAir) 才去找目标"的闸门 ——
     * 实测日志里那个闸门让 target 常年为 false、一秒只放一两个方块（搭桥必掉）。
     * 改成 LB 的模型：以**当前托管转向的真实射线**为准。
     */
    /**
     * Polar 的放置（LB {@code ModuleScaffold.tickHandler}）：取"当前托管转向的真实射线"，
     * 要求它**正好命中搜出来的那个目标**（方块 + 面 + 命中高度下限），命中则把射线结果原样发出。
     *
     * <p>合法客户端发的永远是射线与面的交点；合成点（比如 UP 面给 {@code pos.y + 0.5}）不在面上，
     * 会被 NCP 判成 "Tried to place a block in an unusual way"。</p>
     */
    private void polarPlace() {
        if (placeDelayCounter > 0 || !canUseBlockResult() || polarTarget == null) return;

        BlockHitResult hit = polarCrosshairHit();
        if (hit == null || !polarTarget.matches(hit)) return;

        if (POLAR_DEBUG && mc.player.tickCount % 20 == 0) {
            Constants.LOGGER.info("[ScaffoldPolar] place interacted={} face={} placed={} player={}",
                    hit.getBlockPos(), hit.getDirection(), polarTarget.placed(), mc.player.blockPosition());
        }

        placeOn(hit, true);
    }

    /**
     * 真正执行一次放置交互（换手 → useItemOn → 冷却/挥手/渲染）。
     * 与 {@link #place()} 的分工：调用方负责条件与冷却校验，
     * 这样 Polar 假点击可以复用同一套交互与挥手逻辑。
     *
     * @param hit             命中结果，普通放置传自己算出的目标面，假点击传托管转向的命中
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
            polarPlacedThisTick = true;

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

    /**
     * 挥手。Polar + Hide Swing On Client 时只把 swing 包发给服务端、本地不播动画，
     * 对应 LB 的 {@code Swing = HideForClient}。
     */
    private void swing(InteractionHand hand) {
        if (swingHand.getValue() && !(polarDependency.check() && polarHideSwingOnClient.getValue())) {
            mc.player.swing(hand);
        } else {
            mc.getConnection().send(new ServerboundSwingPacket(hand));
        }
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

    private boolean isValidStack(ItemStack stack) {
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


    private record RenderInfo(
            AABB aabb, Color lineColor, Color sideColor, long startTime, boolean fade, boolean shrink
    ) {
    }

}
