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
import com.github.epsilon.settings.impl.*;
import com.github.epsilon.utils.math.MathUtils;
import com.github.epsilon.utils.player.FallingPlayer;
import com.github.epsilon.utils.player.FindItemResult;
import com.github.epsilon.utils.player.InvUtils;
import com.github.epsilon.utils.render.animation.Easing;
import com.github.epsilon.utils.rotation.RaytraceUtils;
import com.github.epsilon.utils.rotation.Rot2f;
import com.github.epsilon.utils.rotation.RotationUtils;
import com.github.epsilon.utils.player.PlayerUtils;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.network.protocol.game.ClientboundPlayerPositionPacket;
import net.minecraft.network.protocol.game.ServerboundMovePlayerPacket;
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
    }

    @Override
    protected void onDisable() {
        yLevel = 0;
        emergencyPlacementActive = false;
        pearlUsePacketSent = false;
        resetLegitEdgeState();
        if (shouldSwapBack) {
            InvUtils.swapBack();
            shouldSwapBack = false;
        }
    }

    @EventHandler
    private void onPlayerTick(PlayerTickEvent.Pre event) {
        if (!event.isCancelled()) emergencyPlacementActive = false;

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
                if (swingHand.getValue()) {
                    PlayerUtils.swingHand(hand);
                }
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
            case GodBridge -> handleNormal();
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
            ItemStack usedStack = mc.player.getItemInHand(packet.hand());
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

        if (switch (raytrace.getValue()) {
            case Normal -> !RaytraceUtils.overBlock(RotationManager.INSTANCE.getRotation(), blockPos);
            case Strict -> !RaytraceUtils.overBlock(RotationManager.INSTANCE.getRotation(), blockPos, direction);
        }) {
            return;
        }

        swap();

        InteractionHand hand = blockResult.getHand();

        InteractionResult result = mc.gameMode.useItemOn(mc.player, hand, new BlockHitResult(getVec3(blockPos, direction), direction, blockPos, false));

        if (result.consumesAction()) {
            // 冷却 = 基准 + 随机量，每次放置重新掷一次
            placeDelayCounter = placeDelay.getValue()
                    + (placeDelayRandom.getValue() > 0 ? legitRandom.nextInt(placeDelayRandom.getValue() + 1) : 0);
            if (swingHand.getValue()) {
                PlayerUtils.swingHand(hand);
            }

            if (render.getValue()) {
                renderBoxes.add(new RenderInfo(new AABB(blockPos.relative(direction)), lineColor.getValue(), sideColor.getValue(), System.currentTimeMillis(), fade.getValue(), shrink.getValue()));
            }
        }

        swapBack();
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
