package com.github.epsilon.modules.impl.movement;

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

    /** Polar 变体走到边缘时的动作，对应 LB GodBridge 技术的 {@code Modes}。 */
    private enum PolarLedgeAction {
        Jump,
        Sneak,
        StopInput,
        Backwards
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
    private final IntSetting legitSneakDelay = intSetting("Legit Sneak Delay", 4, 1, 5, 1, () -> mode.is(Mode.Legit));
    private final IntSetting legitSneakRandom = intSetting("Legit Sneak Random", 2, 0, 5, 1, () -> mode.is(Mode.Legit));
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
    private final IntSetting legitModeSpeed = intSetting("Legit Mode Speed", 180, 1, 180, 1, () -> mode.is(Mode.Legit));

    /*
     * ===== 神桥 Polar 变体 =====
     * 默认值取自社区 polar（pika-network）配置里 Scaffold 段的存档值，
     * 未覆盖到的项取 LiquidBounce 源码默认值。
     */

    /** 变体开关：只有神桥模式 + Polar 才启用下面这组设置。 */
    private final EnumSetting<GodBridgeVariant> godBridgeVariant =
            enumSetting("God Bridge Variant", GodBridgeVariant.Classic, () -> mode.is(Mode.GodBridge));

    /** Polar 变体的生效条件，供下面所有设置复用。 */
    private final Setting.Dependency polarDependency =
            () -> mode.is(Mode.GodBridge) && godBridgeVariant.is(GodBridgeVariant.Polar);

    /** 边缘动作，对应 LB GodBridge 的 Modes（配置里只勾了 Jump）。 */
    private final EnumSetting<PolarLedgeAction> polarLedgeAction =
            enumSetting("Ledge Action", PolarLedgeAction.Jump, polarDependency);
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

    private static final double LEGIT_EDGE_THRESHOLD = 0.15;
    private int legitEdgeState = 0;
    private int legitEdgeTimer = 0;
    private boolean legitWasOnEdge = false;

    /**
     * 转向未到位时的放置闸门（与 leader 的 {@code rotationTick} 同义）。
     * 目标角偏离当前托管角超过 {@code legitModeSpeed} 容差时置 1，逐刻递减；
     * 非 0 期间不放置，避免转向过程中的放置包朝向与服务器所见不一致而被丢弃。
     */
    private int legitRotationTick = 0;
    private final Random legitRandom = new Random();

    /** 放置冷却剩余刻数；>0 时不放置。见 {@link #placeDelay}。 */
    private int placeDelayCounter = 0;

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
        resetLegitEdgeState();
        resetPolarState();
    }

    @Override
    protected void onDisable() {
        yLevel = 0;
        emergencyPlacementActive = false;
        pearlUsePacketSent = false;
        resetLegitEdgeState();
        resetPolarState();
        if (shouldSwapBack) {
            InvUtils.swapBack();
            shouldSwapBack = false;
        }
    }

    @EventHandler
    private void onPlayerTick(PlayerTickEvent.Pre event) {
        if (!event.isCancelled()) emergencyPlacementActive = false;

        resetPolarLedgeAction();

        if (placeDelayCounter > 0) placeDelayCounter--;

        blockResult = findBlockResult();

        if (mode.is(Mode.Legit)) {
            updateLegitEdgeState();
        } else {
            resetLegitEdgeState();
        }

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
        if ((!reachable || strength >= 1.5) && rotateCount <= 8 && getBlockCount() >= 1 && canUseBlockResult()) {
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

        if (mode.is(Mode.Legit) && mc.gui.screen() == null
                && mc.player.onGround() && (legitEdgeState == 1 || legitEdgeState == 2)) {
            event.setSneak(true);
            event.setSprint(false);
            mc.player.setSprinting(false);
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
            rotation = polarDependency.check() ? getPolarRotation(blockPos, direction) : getRotation(blockPos, direction);
            applyRotation(rotation);
        }
        place();
    }

    /**
     * Polar 变体的转向目标：采用 LB {@code ScaffoldGodBridgeTechnique.getRotations} 的角度算法
     * （直行时按移动方向 ±45° 交替、俯仰 75.7；斜向直接朝移动方向、俯仰 75.6；无输入时朝目标面 +45°、俯仰 75），
     * 再用射线校验；打不中目标方块时退回 Epsilon 原有的候选搜索，避免因为角度问题漏放。
     *
     * <p>之所以不能用原来的候选搜索当目标：它按"哪个候选先打中"选角，逐刻可能整块换候选，
     * 角度会突然跳 45°~180°，反作弊会判成 erratic 转向。</p>
     */
    private Rot2f getPolarRotation(BlockPos pos, Direction dir) {
        Rot2f target = calculatePolarGodBridgeRotation(pos, dir);
        if (target != null && polarRaycastHits(target)) return target;

        // 打不中时退到"正对目标面中心"的解析角：与 LB 定角同量级（都是向下俯视的搭桥角），
        // 而且是稳定值。**绝不能**退回候选择角搜索——那套会逐刻整块换候选，
        // 挑到 ±135° 偏航或俯仰 -90° 之类的角度，表现就是"视角突然一转"。
        if (pos != null && dir != null) return RotationUtils.calculate(pos, dir);

        return rotation != null ? rotation : new Rot2f(mc.player.getYRot(), mc.player.getXRot());
    }

    private Rot2f calculatePolarGodBridgeRotation(BlockPos pos, Direction dir) {
        // 无输入：以目标面朝向为基准取 +45°，俯仰 75
        if (forwardInput == 0.0f && strafeInput == 0.0f) {
            if (pos == null || dir == null) return null;
            float targetYaw = RotationUtils.calculate(pos, dir).getYaw();
            return new Rot2f((float) (Math.floor(targetYaw / 90.0) * 90.0) + 45.0f, 75.0f);
        }

        float movingYaw = Mth.wrapDegrees(Math.round((rawInputYaw + 180.0f) / 45.0f) * 45.0f);

        // 斜向：直接朝移动方向，俯仰 75.6
        if (movingYaw % 90.0f != 0.0f) {
            return new Rot2f(movingYaw, 75.6f);
        }

        // 直行：按"身体偏向哪一侧"交替 ±45，俯仰 75.7；踩在方块外沿且前方脚下是空气时翻转一次
        if (mc.player.onGround()) {
            polarOnRightSide = Mth.floor(mc.player.getX() + Math.cos(Math.toRadians(movingYaw)) * 0.5) != Mth.floor(mc.player.getX())
                    || Mth.floor(mc.player.getZ() + Math.sin(Math.toRadians(movingYaw)) * 0.5) != Mth.floor(mc.player.getZ());

            BlockPos ahead = BlockPos.containing(mc.player.position().relative(Direction.fromYRot(movingYaw), 0.6));
            boolean leaningOffBlock = mc.level.getBlockState(mc.player.blockPosition().below()).isAir();
            boolean aheadIsAir = mc.level.getBlockState(ahead.below()).isAir();
            if (leaningOffBlock && aheadIsAir) polarOnRightSide = !polarOnRightSide;
        }

        return new Rot2f(movingYaw + (polarOnRightSide ? 45.0f : -45.0f), 75.7f);
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
    private BlockHitResult polarPlacementHit() {
        if (blockPos == null || direction == null) return null;

        HitResult result = RaytraceUtils.raytrace(RotationManager.INSTANCE.getRotation(), 4.5, 0.0f);
        if (result == null || result.getType() != HitResult.Type.BLOCK) return null;

        BlockHitResult hit = (BlockHitResult) result;
        if (!hit.getBlockPos().equals(blockPos) || hit.getDirection() != direction) return null;

        return hit;
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
        updatePolarLedgeAction();
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

        // 提交与管线基准（lastRotations）完全相同的角度，会让 RotationUtils.move 出现 0/0 的 NaN，
        // 而 NaN 会一直留在托管角里直到重生。偏一个远小于鼠标灵敏度网格的微小量即可避开，
        // 量化后会舍回同一格，观感仍是原地不动。
        if (polarDeltaLength(stepped, RotationManager.INSTANCE.lastRotations) < 1.0E-3) {
            stepped = new Rot2f(stepped.getYaw() + 1.0E-3f, stepped.getPitch());
        }

        // 步长已在此算好，speed 传 180 让管理器直接落到该角度，避免被二次平滑。
        RotationManager.INSTANCE.setRotations(stepped, 180.0);
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
    private void updatePolarLedgeAction() {
        if (blockPos == null || direction == null || rotation == null) return;

        // 是否"快到边缘"用本模块自己的 isOnEdge()（Legit 模式一直在用的同一判据）：
        // 千万别用 Eagle.isOverEdge()——它把碰撞箱整体下移 1 格再判碰撞，
        // 站在桥面上时箱体仍与脚下那块重叠，几乎恒为 false，整条边缘动作链会被它挡死
        // （实测就是不蹲、不跳、不停）。
        if (!isOnEdge()) return;

        // 1) 手里没方块：蹲住（LB 通用分支的"没方块"）
        if (getBlockCount() <= 0) {
            polarLedgeSneakTicks = polarSneakTicks();
            return;
        }

        // 2) 方块低于阈值：强制潜行
        if (getBlockCount() < polarForceSneakBelow.getValue()) {
            polarLedgeSneakTicks = polarSneakTicks();
            return;
        }

        // 3) 冷却中的那一拍只是放置节奏，不算"放不下去"
        if (placeDelayCounter > 0) return;

        // 4) 本刻已经放上方块，不需要自保
        if (polarPlacedThisTick) return;

        // 5) 射线已经压在目标方块上（下拍就能放）→ 不动作
        if (raytraceOverTarget()) return;

        // 6) 边缘 + 这一拍放不下去 → 执行边缘动作
        PolarLedgeAction action = polarLedgeAction.getValue();
        if (action == PolarLedgeAction.Jump && !canJumpTwoBlocksHigh()) {
            // LB：跳不上两格时退化为潜行。
            action = PolarLedgeAction.Sneak;
        }

        switch (action) {
            case Jump -> polarLedgeJump = true;
            case Sneak -> polarLedgeSneakTicks = polarSneakTicks();
            case StopInput -> polarLedgeStopInput = true;
            case Backwards -> polarLedgeBackwards = true;
        }
    }

    private int polarSneakTicks() {
        int min = polarSneakTimeMin.getValue();
        int max = polarSneakTimeMax.getValue();
        return min + (max > min ? legitRandom.nextInt(max - min + 1) : 0);
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
    }

    private void resetPolarState() {
        polarClickAccumulator = 0.0;
        polarForceSneak = 0;
        polarOnRightSide = false;
        resetPolarLedgeAction();
    }

    private void handleLegit() {
        // 每刻递减一次（等价于 leader 在 tick 处理入口的 rotationTick--）。
        if (legitRotationTick > 0) legitRotationTick--;

        float beforeYaw = RotationManager.INSTANCE.getRotation().getYaw();
        rotation = getRotation(blockPos, direction);
        RotationManager.INSTANCE.setRotations(rotation, legitModeSpeed.getValue());

        // 剩余偏角超过容差 => 本刻仍在转向，标记延后放置。
        // 转向尚未到位时发出的放置包，其朝向与服务器所见不一致，会被服务器丢弃
        // （单机无此校验，故只在联机时表现为“吞方块”）。
        if (Math.abs(Mth.wrapDegrees(rotation.getYaw() - beforeYaw)) > legitModeSpeed.getValue()) {
            legitRotationTick = Math.max(legitRotationTick, 1);
        }
        if (legitRotationTick > 0) return;

        if (legitCanPlace()) {
            place();
        }
    }

    /**
     * Legit（蹲起搭）边缘状态机：
     * 0 = 未在边缘；1 = 刚踏上边缘，潜行等待 legitSneakDelay 刻（此阶段不放置）；
     * 2 = 等待结束，潜行继续但允许放置。
     */
    private void updateLegitEdgeState() {
        boolean onGround = mc.player.onGround();
        boolean atEdge = onGround && isOnEdge();
        boolean holdingBlock = blockResult != null && blockResult.found() && canUseBlockResult();
        boolean justReachedEdge = atEdge && !legitWasOnEdge;

        if (!onGround) {
            legitEdgeState = 0;
            legitEdgeTimer = 0;
        } else if (atEdge && holdingBlock) {
            switch (legitEdgeState) {
                case 0 -> {
                    if (justReachedEdge || legitEdgeTimer == 0) {
                        legitEdgeState = 1;
                        // 蹲起时长 = 基准 + [0, random]；每次进入状态 1 重新掷一次，
                        // 避免固定刻数形成可被反作弊识别的周期性节奏。
                        legitEdgeTimer = legitSneakDelay.getValue()
                                + (legitSneakRandom.getValue() > 0 ? legitRandom.nextInt(legitSneakRandom.getValue() + 1) : 0);
                    }
                }
                case 1 -> {
                    legitEdgeTimer--;
                    if (legitEdgeTimer <= 0) {
                        legitEdgeState = 2;
                        legitEdgeTimer = 0;
                    }
                }
                case 2 -> {
                }
                default -> {
                    legitEdgeState = 0;
                    legitEdgeTimer = 0;
                }
            }
        } else {
            legitEdgeState = 0;
            legitEdgeTimer = 0;
        }
        legitWasOnEdge = atEdge;
    }

    private void resetLegitEdgeState() {
        legitEdgeState = 0;
        legitEdgeTimer = 0;
        legitWasOnEdge = false;
        legitRotationTick = 0;
    }

    /**
     * Legit 放置闸门：在地面且处于状态 1（潜行等待期）时禁止放置。
     */
    private boolean legitCanPlace() {
        return !mc.player.onGround() || legitEdgeState == 0 || legitEdgeState == 2;
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
        if (xOff < LEGIT_EDGE_THRESHOLD || xOff > 1.0 - LEGIT_EDGE_THRESHOLD
                || zOff < LEGIT_EDGE_THRESHOLD || zOff > 1.0 - LEGIT_EDGE_THRESHOLD) {
            int checkX = playerX + (xOff < LEGIT_EDGE_THRESHOLD ? -1 : (xOff > 1.0 - LEGIT_EDGE_THRESHOLD ? 1 : 0));
            int checkZ = playerZ + (zOff < LEGIT_EDGE_THRESHOLD ? -1 : (zOff > 1.0 - LEGIT_EDGE_THRESHOLD ? 1 : 0));
            if (checkX != playerX || checkZ != playerZ) {
                if (mc.level.getBlockState(new BlockPos(checkX, playerY - 1, checkZ)).canBeReplaced()) return true;
            }
        }
        return false;
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

        // Polar：命中点用托管转向的真实射线交点；Classic 等其余分支保持原来的合成点。
        BlockHitResult hit = polarDependency.check()
                ? polarPlacementHit()
                : new BlockHitResult(getVec3(blockPos, direction), direction, blockPos, false);
        if (hit == null) return;

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
                    + (placeDelayRandom.getValue() > 0 ? legitRandom.nextInt(placeDelayRandom.getValue() + 1) : 0);
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
