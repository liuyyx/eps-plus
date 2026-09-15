package com.github.epsilon.modules.impl.movement;

import com.github.epsilon.assets.resources.ResourceLocationUtils;
import com.github.epsilon.events.bus.EventHandler;
import com.github.epsilon.events.bus.EventPriority;
import com.github.epsilon.events.impl.*;
import com.github.epsilon.graphics.immediate.LuminImmediateRenderer;
import com.github.epsilon.managers.ModuleManager;
import com.github.epsilon.managers.rotation.RotationManager;
import com.github.epsilon.modules.Category;
import com.github.epsilon.modules.Module;
import com.github.epsilon.settings.impl.*;
import com.github.epsilon.utils.client.KeybindUtils;
import com.github.epsilon.utils.player.InvUtils;
import com.github.epsilon.utils.rotation.Rot2f;
import com.mojang.blaze3d.PrimitiveTopology;
import com.mojang.blaze3d.pipeline.DepthStencilState;
import com.mojang.blaze3d.pipeline.RenderPipeline;
import com.mojang.blaze3d.platform.CompareOp;
import com.mojang.blaze3d.platform.InputConstants;
import com.mojang.blaze3d.vertex.DefaultVertexFormat;
import com.mojang.blaze3d.vertex.PoseStack;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.client.renderer.RenderPipelines;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.network.chat.Component;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.game.*;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;
import org.joml.Matrix4f;
import org.lwjgl.glfw.GLFW;

import java.util.*;

import static com.github.epsilon.Constants.mc;

/**
 * Telly — automated telly-bridge module ported from RavenBS.
 * Uses BSLegitTellyFix's improved findBelowPlacement (candidate-list + unified scoring).
 */
public class Telly extends Module {

    public static final Telly INSTANCE = new Telly();

    // ─── Settings ───────────────────────────────────────────────────────────
    private final BoolSetting autoSwap = boolSetting("Auto Swap", true);
    private final BoolSetting disableSafeWalk = boolSetting("Disable SafeWalk", true);
    private final BoolSetting showActivationHitbox = boolSetting("Show Activation Hitbox", true);
    /**
     * 激活诊断：潜行期间每秒往聊天栏输出一次探测结果与被卡住的条件。
     * 激活判据涉及「俯仰角 / 命中面 / 站位 / 命中点区域 / 行进朝向 / 唇距」等多项，
     * 逐项都成立才算通过；开启本开关可直接定位是哪一项不满足。
     *
     * <p>仅在按住潜行时输出 —— 不在手势中时完全安静，不会刷屏。</p>
     */
    private final BoolSetting debugActivation = boolSetting("Debug Activation", true);
    private final BoolSetting printStatus = boolSetting("Print Status", true);

    // ─── State fields ───────────────────────────────────────────────────────
    private boolean armed = false;
    private boolean running = false;
    private long activatePromptAt = 0L;
    private long promptBrokeAt = 0L;
    private float promptAlpha = 0.0f;
    private long promptFadeLastAt = 0L;
    /** Debug Activation 输出的节流时间戳（每秒最多一行）。 */
    private long lastActivationDebugAt = 0L;
    private int promptFadeRgb = 0xFF5555;
    private int[] hitboxLastPos = null;
    private int hitboxLastFace = -1;
    private boolean activationMovementHeld = false;
    private boolean antiSwayTapUsed = false;
    private final HashSet<String> cancelledGhostBlocks = new HashSet<>();
    private boolean tellyAutoPlaceWindow = false;
    private boolean autoPlaceDebugActive = false;
    private boolean safeWalkStateCaptured = false;
    private boolean safeWalkWasEnabled = false;

    private int setupTick = 0;
    private int cyclePhase = 19;
    private float baseYaw = 0.0f;
    private int travelX = 0;
    private int travelZ = 0;
    private double antiSwayLane = 0.0;
    private float antiSwayYawOffset = 0.0f;
    private int bridgeLaneBlock = 0;
    private int bridgeStartProgress = 0;
    private int[] latestStraightPlacedPos = null;
    private boolean firstTellyPlacementPending = false;
    private boolean adaptiveAimValid = false;
    private float adaptiveAimYaw = 0.0f;
    private float adaptiveAimPitch = 0.0f;
    private long adaptiveAimUpdatedAt = 0L;
    private long takeoverDetectionAt = 0L;
    private boolean takeoverCameraValid = false;
    private float takeoverCameraYaw = 0.0f;
    private float takeoverCameraPitch = 0.0f;
    private float takeoverAccumulated = 0.0f;
    private long takeoverLastFrameAt = 0L;
    private long freezeLastTickAt = 0L;
    private boolean ignoreForwardUntilRelease = false;
    private boolean ignoreBackUntilRelease = false;
    private boolean ignoreLeftUntilRelease = false;
    private boolean ignoreRightUntilRelease = false;
    private boolean ignoreJumpUntilRelease = false;
    private boolean ignoreSneakUntilRelease = false;
    private boolean ignoreSprintUntilRelease = false;

    // Rotation
    private boolean rotationActive = false;
    private long rotationStartedAt = 0L;
    private long rotationDuration = 50L;
    private float rotationStartYaw = 0.0f;
    private float rotationStartPitch = 0.0f;
    private float rotationTargetYaw = 0.0f;
    private float rotationTargetPitch = 0.0f;
    private float scriptedRotationYaw = 0.0f;
    private float scriptedRotationPitch = 0.0f;

    /**
     * 当前客户端的鼠标网格步长，与 {@code RotationUtils.applySensitivityPatch} 内部
     * 使用的 multiplier 同式：{@code f³ * 8 * 0.15}，{@code f = sensitivity * 0.6 + 0.2}。
     *
     * <p>源版把该值硬编码为 1.8.9 固定灵敏度下的 0.03404715。在 Epsilon 里脚本旋转最后
     * 还要经过 {@code applySensitivityPatch} 再按本机灵敏度量化一次，硬编码值通常不足一格，
     * 会被直接舍入成 0 —— 防检测抖动就此消失，旋转退化成机器般规整。用本机网格作为
     * 量子，抖动恰好是 ±1 格真实鼠标位移，量化后原样保留。</p>
     */
    private double mouseQuantum() {
        double f = mc.options.sensitivity().get() * 0.6 + 0.2;
        return f * f * f * 8.0 * 0.15;
    }
    private final int[] YAW_NUDGE_PATTERN = {0, 1, -1, 2, -2};
    private int rotationStepCounter = 0;
    private final double ACTIVATION_ACROSS_MIN = 0.38;
    private final double ACTIVATION_ACROSS_MAX = 0.65;
    private final double ACTIVATION_HEIGHT_MIN = 0.25;
    private final double ACTIVATION_HEIGHT_MAX = 0.75;
    private final float ACTIVATION_YAW_TOLERANCE = 2.0f;

    // 激活命中框渲染管线：无深度测试、不剔除，与 ESP 系列保持一致。
    private static final RenderPipeline ACTIVATION_FACE_PIPELINE = RenderPipeline.builder(RenderPipelines.DEBUG_FILLED_SNIPPET)
            .withLocation(ResourceLocationUtils.getIdentifier("pipeline/telly_activation_face"))
            .withDepthStencilState(new DepthStencilState(CompareOp.ALWAYS_PASS, false))
            .withCull(false)
            .withVertexBinding(0, DefaultVertexFormat.POSITION_COLOR)
            .withPrimitiveTopology(PrimitiveTopology.QUADS)
            .build();

    private static final RenderPipeline ACTIVATION_EDGE_PIPELINE = RenderPipeline.builder(RenderPipelines.LINES_SNIPPET)
            .withLocation(ResourceLocationUtils.getIdentifier("pipeline/telly_activation_edge"))
            .withDepthStencilState(new DepthStencilState(CompareOp.ALWAYS_PASS, false))
            .withCull(false)
            .build();

    // Curves
    private final float[] yawCurve = {
            91.68f, 98.88f, 78.94f, 37.45f, 1.61f, -21.69f, -33.98f,
            -35.80f, -34.64f, -33.85f, -33.06f, -31.55f, -29.26f, -26.65f,
            -24.19f, -21.07f, -18.84f, -17.06f, -8.87f, 2.61f, 41.94f
    };
    private final float[] pitchCurve = {
            64.31f, 59.95f, 60.57f, 61.46f, 60.64f, 58.89f, 56.91f,
            56.63f, 58.65f, 61.63f, 64.20f, 66.74f, 68.69f, 70.64f,
            73.01f, 75.37f, 77.46f, 78.56f, 78.90f, 77.22f, 72.25f
    };
    private final float[] forwardCurve = {
            1.0f, 1.0f, 0.0f, 0.0f, -1.0f, -1.0f, -1.0f,
            -1.0f, -1.0f, -1.0f, -1.0f, -1.0f, -1.0f, -1.0f,
            -1.0f, -1.0f, -1.0f, -1.0f, -1.0f, -1.0f, 1.0f
    };
    private final float[] strafeCurve = {
            -1.0f, -1.0f, -1.0f, -1.0f, 0.0f, 0.0f, 0.0f,
            0.0f, 0.0f, 0.0f, 0.0f, 0.0f, 0.0f, 0.0f,
            0.0f, 0.0f, 0.0f, -1.0f, -1.0f, -1.0f, -1.0f
    };

    // Placement constants
    private final double[] FACE_HIT_OFFSETS = {0.5, 0.25, 0.75, 0.15, 0.85};
    private final double[] EXTENDED_FACE_HIT_OFFSETS = {0.5, 0.25, 0.75, 0.15, 0.85, 0.35, 0.65, 0.05, 0.95};
    private final int[] ALLOWED_PLACE_FACES = {2, 3, 4, 5, 1};
    private static final String[] REPLACEABLE_BLOCKS = {
            "air", "cave_air", "void_air", "water", "flowing_water", "lava", "flowing_lava",
            "fire", "soul_fire", "tallgrass", "dead_bush", "snow_layer", "small_flowers",
            "tall_flowers", "vine", "cave_vines", "cave_vines_plant"
    };
    private static final Set<String> UNPLACEABLE_EXACT = Set.of(
            "snow_layer", "cobweb", "sweet_berry_bush", "daylight_detector", "beacon",
            "end_portal_frame", "end_portal", "lever", "stone_button", "polished_blackstone_button",
            "skeleton_skull", "cactus", "double_plant", "lily_pad", "carpet", "tripwire_hook",
            "tall_grass", "dead_bush", "poppy", "dandelion", "flower_pot", "sign",
            "ladder", "torch", "soul_torch", "redstone_wall_torch", "unlit_redstone_torch",
            "gravel", "clay", "sand", "red_sand", "soul_sand", "soul_soil",
            "chest", "trapped_chest", "ender_chest", "furnace", "blast_furnace", "smoker",
            "jukebox", "enchanting_table", "dropper", "dispenser", "hopper", "anvil",
            "chipped_anvil", "damaged_anvil", "note_block", "crafting_table",
            "mob_spawner", "brewing_stand", "bed"
    );
    private static final Set<String> UNPLACEABLE_CONTAINS = Set.of(
            "stairs", "slab", "fence", "pane", "rail", "door",
            "torch", "pumpkin", "flower", "sapling", "banner", "button",
            "skull", "web", "carpet", "cactus", "sign", "mushroom"
    );

    // Auto-place state
    private int currentClientTick = Integer.MIN_VALUE;
    private int placementEvaluationTick = Integer.MIN_VALUE;
    private int lastPlacementAttemptTick = Integer.MIN_VALUE;
    private int lastSuccessfulPlaceTick = Integer.MIN_VALUE;
    private int forceSuppressTick = Integer.MIN_VALUE;
    private long totalC08Counter = 0L;
    private long c08CounterAtTickBoundary = 0L;
    private boolean hasLastSentServerPos = false;
    private double lastSentServerPosX, lastSentServerPosY, lastSentServerPosZ;
    private Object[] cachedCandidate = null;
    private int cachedCandidateTick = Integer.MIN_VALUE;
    private float cachedCandidateYaw = Float.NaN;
    private float cachedCandidatePitch = Float.NaN;
    private boolean candidateResolvedThisTick = false;
    private int[] lastPlacedPos = null;
    private int[] lastSupportPos = null;
    private int lastSupportFace = -1;
    private List<int[]> cachedBelowTargets = null;
    private int cachedBelowTargetsTick = Integer.MIN_VALUE;
    private final Map<String, Integer> rejectedTargets = new HashMap<>();
    private int forcedModeCheck = 0;
    private boolean useSuppressed = false;
    private boolean silentPitchActive = false;
    private float silentPitch = 0f;
    private boolean placingViaModule = false;
    private boolean manualC08InWindow = false;

    // Keyboard input tracking
    private float currentForward = 0.0f;
    private float currentStrafe = 0.0f;

    // ─── Constructor ────────────────────────────────────────────────────────
    private Telly() {
        super("Telly", Category.MOVEMENT);
    }

    // ─── Lifecycle ──────────────────────────────────────────────────────────
    @Override
    protected void onEnable() {
        armAutomation();
        resetControllerState();
    }

    @Override
    protected void onDisable() {
        stopAutomation(false);
        resetControllerState();
        restoreUseToPhysicalState();
    }

    // ─── Events ─────────────────────────────────────────────────────────────
    @EventHandler
    private void onGameJoined(GameJoinedEvent event) {
        if (running) stopAutomation(false);
        resetControllerState();
    }

    @EventHandler
    private void onPlayerTickPre(PlayerTickEvent.Pre event) {
        if (nullCheck()) return;
        enforceSafeWalkDisabledForRun();
        if (running) {
            mc.options.keyAttack.setDown(false);
            applySmoothedRotation();
        }
        if (armed && !running) updateActivationPrompt();
        if (!running) return;

        long freezeNow = System.currentTimeMillis();
        if (freezeLastTickAt != 0L && freezeNow - freezeLastTickAt > 300L) {
            stopAutomation(true);
            return;
        }
        freezeLastTickAt = freezeNow;

        LocalPlayer player = mc.player;
        if (player == null || player.isDeadOrDying() || player.fallDistance > 7.0f) {
            stopAutomation(true);
            return;
        }
        handleAutoSwap(player);
        if (!isHoldingBlock(player)) {
            stopAutomation(true);
            return;
        }
        if (firstTellyPlacementPending) updateAdaptivePlacementAim(player);
        autoPlaceOnPreUpdate();
        if (firstTellyPlacementPending) updateAdaptivePlacementAim(player);
    }

    @EventHandler
    private void onPlayerTickPost(PlayerTickEvent.Post event) {
        if (nullCheck() || !running) return;
        autoPlaceOnPostMotion();
    }

    @EventHandler(priority = EventPriority.HIGHEST)
    private void onMoveInput(KeyboardInputEvent event) {
        currentForward = event.getForward();
        currentStrafe = event.getStrafe();

        if (!nullCheck() && running && mc.player.onGround() && !mc.options.keyJump.isDown()) {
            // Telly auto-jump when on ground during bridge
        }
    }

    @EventHandler
    private void onPacketSend(PacketEvent.Send event) {
        Packet<?> packet = event.getPacket();
        if (isDropProtected() && packet instanceof ServerboundPlayerActionPacket digging) {
            ServerboundPlayerActionPacket.Action action = digging.getAction();
            if (action == ServerboundPlayerActionPacket.Action.DROP_ITEM || action == ServerboundPlayerActionPacket.Action.DROP_ALL_ITEMS) {
                event.cancel();
                return;
            }
        }
        if (!running) return;
        if (packet instanceof ServerboundInteractPacket interaction) {
            // Block attack interactions during running
            return;
        }
        if (packet instanceof ServerboundPlayerActionPacket digging2) {
            ServerboundPlayerActionPacket.Action action2 = digging2.getAction();
            if (action2 == ServerboundPlayerActionPacket.Action.STOP_DESTROY_BLOCK) {
                event.cancel();
                return;
            }
        }
        if (packet instanceof ServerboundPlayerCommandPacket action2) {
            // Block sneak packets during running
            return;
        }
        if (packet instanceof ServerboundUseItemOnPacket placement) {
            // Track placement for straight telly validation
        }

        autoPlaceOnPacketSent(packet);
    }

    @EventHandler
    private void onPacketReceive(PacketEvent.Receive event) {
        Packet<?> packet = event.getPacket();
        if (running && packet instanceof ClientboundPlayerPositionPacket) {
            stopAutomation(true);
            return;
        }
    }

    @EventHandler
    private void onRender3D(Render3DEvent event) {
        if (nullCheck()) return;
        updateActivatePromptFade();
        drawActivationHitbox(event.getPoseStack());
        if (!running) return;
        if (detectManualCameraTakeover()) return;
        applySmoothedRotation();
    }

    @EventHandler
    private void onRender2D(Render2DEvent event) {
        if (nullCheck()) return;
        drawActivatePrompt(event);
    }

    @EventHandler
    private void onMousePress(MousePressEvent event) {
        if (nullCheck()) return;
        if (running) {
            if (event.getButton() == 0) {
                mc.options.keyAttack.setDown(false);
                event.cancel();
                return;
            }
            if (event.getButton() == 1) {
                mc.options.keyUse.setDown(tellyAutoPlaceWindow);
                event.cancel();
                return;
            }
            return;
        }
        if (armed && event.getButton() == 1 && event.getAction() == GLFW.GLFW_RELEASE) {
            setActivationMovementHold(false);
        }
        if (armed && activationSuppressUse() && event.getButton() == 1) {
            event.cancel();
        }
    }

    @EventHandler
    private void onKeyPress(KeyPressEvent event) {
        if (nullCheck()) return;
        int keyCode = event.getKey();
        boolean state = event.getAction() == GLFW.GLFW_PRESS;

        boolean dropKey = keyCode == KeybindUtils.getKey(mc.options.keyDrop);
        if (isDropProtected() && dropKey) {
            mc.options.keyDrop.setDown(false);
            event.cancel();
            return;
        }
        if (!running && activationMovementHeld && !state
                && (keyCode == KeybindUtils.getKey(mc.options.keyDown)
                || keyCode == KeybindUtils.getKey(mc.options.keyRight))) {
            mc.options.keyDown.setDown(true);
            mc.options.keyRight.setDown(true);
            event.cancel();
            return;
        }
        if (!running) return;
        if (keyCode == KeybindUtils.getKey(mc.options.keyShift)) {
            suppressSneakInput();
            event.cancel();
            return;
        }
        if (!state) clearInitialMovementHold(keyCode);
        if (state && setupTick < 0 && isManualMovementKey(keyCode)
                && !isInitialMovementHold(keyCode) && !isScriptHeldKey(keyCode)) {
            stopAutomation(true);
            return;
        }
        if (isManualMovementKey(keyCode)) {
            event.cancel();
        }
    }

    // ─── Activation logic ───────────────────────────────────────────────────
    private void armAutomation() {
        armed = true;
        running = false;
        activatePromptAt = 0L;
        promptBrokeAt = 0L;
        setupTick = 0;
        cyclePhase = 19;
        rotationActive = false;
        activationMovementHeld = false;
        printModuleStatus("Armed. Sneak looking down, wait for green, hold rmb and release sneak");
    }

    private void beginAutomation() {
        LocalPlayer player = mc.player;
        if (player == null || !isHoldingBlock(player)) {
            printModuleStatus("Hold blocks before starting");
            return;
        }
        if (!isActivationYawAligned(player.getYRot())) return;

        disableSafeWalkForRun();
        baseYaw = player.getYRot();
        calculateTravelDirection(baseYaw);
        antiSwayLane = travelX != 0 ? player.getZ() : player.getX();
        antiSwayYawOffset = 0.0f;
        antiSwayTapUsed = false;
        cancelledGhostBlocks.clear();
        initializeStraightBridgeLane(player);
        firstTellyPlacementPending = false;
        adaptiveAimValid = false;
        adaptiveAimUpdatedAt = 0L;
        setupTick = 0;
        cyclePhase = 19;
        armed = false;
        running = true;
        freezeLastTickAt = System.currentTimeMillis();
        activationMovementHeld = false;
        tellyAutoPlaceWindow = true;
        scriptedRotationYaw = player.getYRot();
        scriptedRotationPitch = player.getXRot();
        takeoverDetectionAt = 0L;
        takeoverCameraValid = false;
        clearInitialMovementHolds();
        resetControllerState();
        mc.options.keyAttack.setDown(false);
        applyMovement(-1.0f, -1.0f, false, false);
        setRotationTarget(baseYaw, 74.52f, 50L);
        applyUse(true);
        printModuleStatus("Started");
    }

    private void stopAutomation(boolean turnOffButton) {
        armed = false;
        running = false;
        setupTick = 0;
        cyclePhase = 19;
        rotationActive = false;
        activationMovementHeld = false;
        tellyAutoPlaceWindow = false;
        autoPlaceDebugActive = false;
        antiSwayYawOffset = 0.0f;
        antiSwayTapUsed = false;
        firstTellyPlacementPending = false;
        latestStraightPlacedPos = null;
        adaptiveAimValid = false;
        adaptiveAimUpdatedAt = 0L;
        scriptedRotationYaw = 0.0f;
        scriptedRotationPitch = 0.0f;
        takeoverDetectionAt = 0L;
        takeoverCameraValid = false;
        takeoverCameraYaw = 0.0f;
        takeoverCameraPitch = 0.0f;
        takeoverAccumulated = 0.0f;
        takeoverLastFrameAt = 0L;

        try {
            cancelledGhostBlocks.clear();
            clearInitialMovementHolds();
            resetControllerState();
            releaseMovementKeys();
            restorePhysicalUse();
            mc.options.keyAttack.setDown(false);
        } catch (Exception ignored) {}

        restoreSafeWalkState();
        freezeLastTickAt = 0L;
        armed = true;
        activatePromptAt = 0L;
        promptBrokeAt = 0L;
        if (turnOffButton) {
            printModuleStatus("Stopped. Sneak looking down to arm again");
        }
    }

    // ─── Activation prompt ──────────────────────────────────────────────────
    private float activationPitch() {
        return 75.0f;
    }

    /**
     * 全量诊断：一次列出九项判据 + 触发阶段的状态，而不是只报第一个失败项。
     * 仅用于 {@code Debug Activation} 的输出，不参与实际逻辑。
     *
     * <p>格式：{@code [总体] 俯仰… 朝向… 射线… 面… 点… 行进面… 站位… 前方… 唇距…
     * ‖ 潜行… 右键… 计时…}。{@code ✓} 表示该条通过。</p>
     */
    private String activationFullReport(LocalPlayer player) {
        StringBuilder sb = new StringBuilder();

        boolean pitchOk = player.getXRot() >= activationPitch();
        sb.append(pitchOk ? "§a俯仰✓§f" : "§c俯仰✗§f")
                .append(String.format("%.1f", player.getXRot())).append(' ');

        float nearestDiagonal = Math.round((player.getYRot() - 45.0f) / 90.0f) * 90.0f + 45.0f;
        float yawOff = Math.abs(tellyWrapAngle(player.getYRot() - nearestDiagonal));
        boolean yawOk = isActivationYawAligned(player.getYRot());
        sb.append(yawOk ? "§a朝向✓§f" : "§c朝向✗§f")
                .append(String.format("%.2f", yawOff)).append(' ');

        BlockHitResult hit = raycastBlock(4.5);
        boolean rayOk = hit != null && hit.getType() != HitResult.Type.MISS;
        sb.append(rayOk ? "§a射线✓§f " : "§c射线✗§f ");

        boolean faceOk = false, centerOk = false, travelOk = false;
        boolean standOk = false, aheadOk = false, lipOk = false;
        if (rayOk) {
            Direction dir = hit.getDirection();
            int f = directionToInt(dir);
            faceOk = dir != Direction.UP && dir != Direction.DOWN;
            sb.append(faceOk ? "§a面✓§f" : "§c面✗§f").append(dir.name()).append(' ');

            Vec3 localHit = hit.getLocation().subtract(new Vec3(hit.getBlockPos()));
            centerOk = isInActivationFaceCenter(f, localHit);
            sb.append(centerOk ? "§a点✓§f" : "§c点✗§f")
                    .append(String.format("(%.2f,%.2f,%.2f) ", localHit.x, localHit.y, localHit.z));

            int[] travel = travelDirectionFromYaw(player.getYRot());
            int travelFace = travel[0] > 0 ? 5 : travel[0] < 0 ? 4 : travel[1] > 0 ? 3 : 2;
            travelOk = f == travelFace;
            sb.append(travelOk ? "§a行进面✓§f" : "§c行进面✗§f").append(f).append('/').append(travelFace).append(' ');

            BlockPos bp = hit.getBlockPos();
            int[] pos = {bp.getX(), bp.getY(), bp.getZ()};
            standOk = isPlayerOnActivationBlock(player, pos);
            sb.append(standOk ? "§a站位✓§f " : "§c站位✗§f").append("feet=").append(floor(player.position().y - 0.01))
                    .append("/hit=").append(pos[1]).append(' ');

            if (standOk) {
                int aheadX = pos[0] + travel[0];
                int aheadZ = pos[2] + travel[1];
                String aheadName = blockNameAt(aheadX, pos[1] + 1, aheadZ);
                aheadOk = isReplaceableName(aheadName, false);
                sb.append(aheadOk ? "§a前方✓§f " : "§c前方✗§f").append(aheadName).append(' ');

                Vec3 pp = player.position();
                double lip = f == 5 ? (pos[0] + 1) - pp.x
                        : f == 4 ? pp.x - pos[0]
                        : f == 3 ? (pos[2] + 1) - pp.z
                        : pp.z - pos[2];
                lipOk = lip <= 0.65;
                sb.append(lipOk ? "§a唇距✓§f" : "§c唇距✗§f").append(String.format("%.2f", lip)).append(' ');
            }
        }

        sb.append("§7‖§f ");
        sb.append(mc.options.keyShift.isDown() ? "§a潜行✓§f " : "§c潜行✗§f ");
        sb.append(mc.mouseHandler.isRightPressed() ? "§a右键✓§f " : "§c右键✗§f ");
        sb.append(activatePromptAt == 0L ? "§c未计时§f"
                : (activationPromptReady() ? "§a就绪✓§f" : "§e计时中§f"));

        boolean allOk = pitchOk && yawOk && rayOk && faceOk && centerOk
                && travelOk && standOk && aheadOk && lipOk;
        return (allOk ? "§a[探测全通过]§f " : "§e[探测未通过]§f ") + sb;
    }

    private void updateActivationPrompt() {
        LocalPlayer player = mc.player;
        if (player == null || mc.gui.screen() != null) {
            clearActivationPrompt();
            return;
        }

        // 潜行期间持续输出；松开潜行后仍输出到计时器被清掉为止，
        // 这样「松手瞬间」那一刻也有输出，而不是诊断恰好静音。
        if (debugActivation.getValue() && (mc.options.keyShift.isDown() || activatePromptAt != 0L)) {
            long now = System.currentTimeMillis();
            if (now - lastActivationDebugAt >= 1000L) {
                lastActivationDebugAt = now;
                String stage = activationFullReport(player);
                // 直接发聊天消息，不经 printModuleStatus：
                // 诊断输出不能依赖 Print Status 开关，否则失败模式又多一个。
                player.sendSystemMessage(Component.literal(
                        "§bTelly §7| §f诊断 → §c" + stage));
            }
        }

        setActivationMovementHold(activationPromptReady() && mc.mouseHandler.isRightPressed());

        boolean lookingDown = player.getXRot() >= activationPitch();
        boolean atEdge = lookingDown && isLookingAtEdge(player);

        if (mc.options.keyShift.isDown() && atEdge) {
            if (activatePromptAt == 0L) activatePromptAt = System.currentTimeMillis();
            promptBrokeAt = 0L;
            if (activationSuppressUse()) mc.options.keyUse.setDown(false);
            // 注意：这里必须读物理鼠标键，不能读 mc.options.keyUse.isDown()。
            // keyUse 的 down 状态会被本方法自身 setDown(false) 清零（用于抑制原版右键使用），
            // 写后立刻读恒为 false，会导致激活永远无法完成。
            if (activationPromptReady() && mc.mouseHandler.isRightPressed()) {
                disableSafeWalkForRun();
                enforceSafeWalkDisabledForRun();
            } else if (safeWalkStateCaptured) {
                restoreSafeWalkState();
            }
            return;
        }

        if (activatePromptAt == 0L) return;
        if (!activationPromptReady()) {
            clearActivationPrompt();
            return;
        }
        if (promptBrokeAt == 0L) {
            rememberActivationPromptColor();
            promptBrokeAt = System.currentTimeMillis();
        }
        mc.options.keyUse.setDown(false);

        // 触发：松开潜行、仍按住右键、朝向对齐。
        // 右键同样读物理鼠标键 —— 上一行的 setDown(false) 会把 keyUse 清零。
        if (!mc.options.keyShift.isDown() && mc.mouseHandler.isRightPressed() && isActivationYawAligned(player.getYRot())) {
            rememberActivationPromptColor();
            activatePromptAt = 0L;
            promptBrokeAt = 0L;
            beginAutomation();
            if (!running) mc.options.keyUse.setDown(false);
            return;
        }

        if (System.currentTimeMillis() - promptBrokeAt > 300L) {
            clearActivationPrompt();
        }
    }

    private void clearActivationPrompt() {
        rememberActivationPromptColor();
        if (activationSuppressUse()) mc.options.keyUse.setDown(false);
        activatePromptAt = 0L;
        promptBrokeAt = 0L;
        setActivationMovementHold(false);
        if (!running) restoreSafeWalkState();
    }

    private boolean activationPromptReady() {
        return activatePromptAt != 0L && System.currentTimeMillis() - activatePromptAt >= 1000L;
    }

    private boolean activationSuppressUse() {
        return activatePromptAt != 0L && System.currentTimeMillis() - activatePromptAt >= 850L;
    }

    private void rememberActivationPromptColor() {
        if (activatePromptAt != 0L) {
            promptFadeRgb = activationPromptReady() ? 0x55FF55 : 0xFF5555;
        }
    }

    private void updateActivatePromptFade() {
        boolean show = armed && !running && activatePromptAt != 0L;
        if (show) rememberActivationPromptColor();
        long now = System.currentTimeMillis();
        long elapsed = promptFadeLastAt == 0L ? 0L : Math.min(100L, now - promptFadeLastAt);
        promptFadeLastAt = now;
        float step = elapsed / 200.0f;
        promptAlpha += show ? step : -step;
        promptAlpha = clamp(promptAlpha, 0.0f, 1.0f);
    }

    private void drawActivatePrompt(Render2DEvent event) {
        if (promptAlpha < 0.05f) return;
        if (!armed || running) return;

        // 提示必须说清「按哪个键」，而不是只显示 "Activate?"。
        // 触发条件是「按住右键 + 松开潜行」，只按右键不松潜行是永远触发不了的，
        // 光看一个问号无从得知。
        String text = activationPromptReady()
                ? "松开 " + mc.options.keyShift.getTranslatedKeyMessage().getString()
                        + " ＋ 按住 " + mc.options.keyUse.getTranslatedKeyMessage().getString()
                : "潜行对准边缘，等待变绿…";

        int alpha = (int) (promptAlpha * 255.0f);
        if (alpha < 16) alpha = 16;
        int color = (alpha << 24) | promptFadeRgb;
        int x = (int) (mc.getWindow().getGuiScaledWidth() / 2.0f - mc.font.width(text) / 2.0f);
        int y = (int) (mc.getWindow().getGuiScaledHeight() / 2.0f + 10.0f);

        event.getGuiGraphics().text(mc.font, text, x, y, color, true);
    }

    /**
     * 「Show Activation Hitbox」命中框：高亮可激活方块面上、下蹲需要对准的矩形区域。
     * <p>
     * 相机偏移由 {@link Render3DEvent} 的 PoseStack 承载（与 ESP 系列同一约定），
     * 因此这里只做一次 {@code -camera} 平移后即可直接用世界坐标提交顶点。
     * </p>
     */
    private void drawActivationHitbox(PoseStack poseStack) {
        if (!showActivationHitbox.getValue() || !armed || running) return;
        if (mc.player == null || mc.level == null) return;

        // 注意：这里刻意不要求 activatePromptAt != 0。
        // 若沿用源版的写法，框只在「已经对准、计时已开始」之后才出现，
        // 那就没法拿来找位置 —— 而它唯一的价值恰恰是告诉你该往哪儿看。
        BlockHitResult hit = raycastBlock(4.5);
        if (hit != null && hit.getType() != HitResult.Type.MISS) {
            int face = directionToInt(hit.getDirection());
            if (face >= 2) {
                BlockPos blockPos = hit.getBlockPos();
                hitboxLastPos = new int[]{blockPos.getX(), blockPos.getY(), blockPos.getZ()};
                hitboxLastFace = face;
            }
        }
        if (hitboxLastPos == null || hitboxLastFace < 2) return;

        int[] pos = hitboxLastPos;
        int face = hitboxLastFace;

        double yMin = pos[1] + ACTIVATION_HEIGHT_MIN;
        double yMax = pos[1] + ACTIVATION_HEIGHT_MAX;
        double x1, x2, z1, z2;
        if (face == 5) {
            x1 = x2 = pos[0] + 1.005;
            z1 = pos[2] + ACTIVATION_ACROSS_MIN;
            z2 = pos[2] + ACTIVATION_ACROSS_MAX;
        } else if (face == 4) {
            x1 = x2 = pos[0] - 0.005;
            z1 = pos[2] + (1.0 - ACTIVATION_ACROSS_MAX);
            z2 = pos[2] + (1.0 - ACTIVATION_ACROSS_MIN);
        } else if (face == 3) {
            z1 = z2 = pos[2] + 1.005;
            x1 = pos[0] + (1.0 - ACTIVATION_ACROSS_MAX);
            x2 = pos[0] + (1.0 - ACTIVATION_ACROSS_MIN);
        } else {
            z1 = z2 = pos[2] - 0.005;
            x1 = pos[0] + ACTIVATION_ACROSS_MIN;
            x2 = pos[0] + ACTIVATION_ACROSS_MAX;
        }

        int red = (promptFadeRgb >> 16) & 0xFF;
        int green = (promptFadeRgb >> 8) & 0xFF;
        int blue = promptFadeRgb & 0xFF;
        // 未进入激活计时（promptAlpha 仍为 0）时给一个可见下限，
        // 否则作为瞄准辅助时框的透明度接近 0，看上去等于不存在。
        float visibility = Math.max(promptAlpha, 0.5f);
        int fillColor = (Math.max(8, (int) (60.0f * visibility)) << 24) | (red << 16) | (green << 8) | blue;
        int edgeColor = (Math.max(40, (int) (220.0f * visibility)) << 24) | (red << 16) | (green << 8) | blue;

        Vec3 camera = mc.getEntityRenderDispatcher().camera.position();

        poseStack.pushPose();
        poseStack.translate(-camera.x, -camera.y, -camera.z);
        Matrix4f matrix = poseStack.last().pose();

        LuminImmediateRenderer.PosColorQuads fill = LuminImmediateRenderer.beginPosColorQuads(ACTIVATION_FACE_PIPELINE);
        fill.vertex(matrix, (float) x1, (float) yMin, (float) z1, fillColor);
        fill.vertex(matrix, (float) x2, (float) yMin, (float) z2, fillColor);
        fill.vertex(matrix, (float) x2, (float) yMax, (float) z2, fillColor);
        fill.vertex(matrix, (float) x1, (float) yMax, (float) z1, fillColor);
        fill.end();

        float normalX = face == 5 ? 1.0f : face == 4 ? -1.0f : 0.0f;
        float normalZ = face == 3 ? 1.0f : face == 2 ? -1.0f : 0.0f;
        PoseStack.Pose pose = poseStack.last();

        LuminImmediateRenderer.Lines edge = LuminImmediateRenderer.beginLines(ACTIVATION_EDGE_PIPELINE);
        edge.vertex(matrix, pose, (float) x1, (float) yMin, (float) z1, edgeColor, normalX, 0.0f, normalZ, 2.0f);
        edge.vertex(matrix, pose, (float) x2, (float) yMin, (float) z2, edgeColor, normalX, 0.0f, normalZ, 2.0f);
        edge.vertex(matrix, pose, (float) x2, (float) yMin, (float) z2, edgeColor, normalX, 0.0f, normalZ, 2.0f);
        edge.vertex(matrix, pose, (float) x2, (float) yMax, (float) z2, edgeColor, normalX, 0.0f, normalZ, 2.0f);
        edge.vertex(matrix, pose, (float) x2, (float) yMax, (float) z2, edgeColor, normalX, 0.0f, normalZ, 2.0f);
        edge.vertex(matrix, pose, (float) x1, (float) yMax, (float) z1, edgeColor, normalX, 0.0f, normalZ, 2.0f);
        edge.vertex(matrix, pose, (float) x1, (float) yMax, (float) z1, edgeColor, normalX, 0.0f, normalZ, 2.0f);
        edge.vertex(matrix, pose, (float) x1, (float) yMin, (float) z1, edgeColor, normalX, 0.0f, normalZ, 2.0f);
        edge.end();

        poseStack.popPose();
    }

    // ─── Activation geometry ────────────────────────────────────────────────
    private int[] travelDirectionFromYaw(float yaw) {
        double radians = Math.toRadians(yaw);
        double rawX = Math.sin(radians) - Math.cos(radians);
        double rawZ = -Math.cos(radians) - Math.sin(radians);
        if (Math.abs(rawX) >= Math.abs(rawZ)) return new int[]{rawX >= 0.0 ? 1 : -1, 0};
        return new int[]{0, rawZ >= 0.0 ? 1 : -1};
    }

    private boolean isLookingAtEdge(LocalPlayer player) {
        if (!isActivationYawAligned(player.getYRot())) return false;
        BlockHitResult hit = raycastBlock(4.5);
        if (hit == null || hit.getType() == HitResult.Type.MISS) return false;

        Direction face = hit.getDirection();
        if (face == Direction.UP || face == Direction.DOWN) return false;
        // 偏移必须相对方块「角点」取（范围 [0,1]），与 ACTIVATION_ACROSS_*/HEIGHT_* 常量
        // 以及 drawActivationFaceRegion 的绘制区域同一基准。若误改成相对方块中心
        // （[-0.5,0.5]），判定窗口会缩到约 1/4 且整体偏移，导致几乎永远无法激活。
        Vec3 localHit = hit.getLocation().subtract(new Vec3(hit.getBlockPos()));
        if (!isInActivationFaceCenter(directionToInt(face), localHit)) return false;

        int[] travel = travelDirectionFromYaw(player.getYRot());
        int travelFace = travel[0] > 0 ? 5 : travel[0] < 0 ? 4 : travel[1] > 0 ? 3 : 2;
        if (directionToInt(face) != travelFace) return false;

        int[] pos = new int[]{hit.getBlockPos().getX(), hit.getBlockPos().getY(), hit.getBlockPos().getZ()};
        if (!isPlayerOnActivationBlock(player, pos)) return false;
        int aheadX = pos[0] + travel[0];
        int aheadZ = pos[2] + travel[1];
        if (!isReplaceableName(blockNameAt(aheadX, pos[1] + 1, aheadZ), false)) return false;

        Vec3 playerPos = player.position();
        double lipDistance;
        if (directionToInt(face) == 5) lipDistance = (pos[0] + 1) - playerPos.x;
        else if (directionToInt(face) == 4) lipDistance = playerPos.x - pos[0];
        else if (directionToInt(face) == 3) lipDistance = (pos[2] + 1) - playerPos.z;
        else lipDistance = playerPos.z - pos[2];
        return lipDistance <= 0.65;
    }

    private boolean isActivationYawAligned(float yaw) {
        float nearestDiagonal = Math.round((yaw - 45.0f) / 90.0f) * 90.0f + 45.0f;
        return Math.abs(tellyWrapAngle(yaw - nearestDiagonal)) <= ACTIVATION_YAW_TOLERANCE;
    }

    private boolean isPlayerOnActivationBlock(LocalPlayer player, int[] pos) {
        if (pos == null) return false;
        Vec3 playerPos = player.position();
        if (pos[1] != floor(playerPos.y - 0.01)) return false;
        double centerX = pos[0] + 0.5;
        double centerZ = pos[2] + 0.5;
        return Math.abs(playerPos.x - centerX) <= 0.85 && Math.abs(playerPos.z - centerZ) <= 0.85;
    }

    private boolean isInActivationFaceCenter(int face, Vec3 localHit) {
        if (localHit == null) return false;
        double acrossFace = (face == 4 || face == 5) ? localHit.z : localHit.x;
        if (face == 3 || face == 4) acrossFace = 1.0 - acrossFace;
        return acrossFace >= ACTIVATION_ACROSS_MIN && acrossFace <= ACTIVATION_ACROSS_MAX
                && localHit.y >= ACTIVATION_HEIGHT_MIN && localHit.y <= ACTIVATION_HEIGHT_MAX;
    }

    // ─── Movement hold ──────────────────────────────────────────────────────
    private void setActivationMovementHold(boolean hold) {
        if (hold) {
            activationMovementHeld = true;
            mc.options.keyDown.setDown(true);
            mc.options.keyRight.setDown(true);
            return;
        }
        if (!activationMovementHeld) return;
        activationMovementHeld = false;
        mc.options.keyDown.setDown(InputConstants.isKeyDown(mc.getWindow(), KeybindUtils.getKey(mc.options.keyDown)));
        mc.options.keyRight.setDown(InputConstants.isKeyDown(mc.getWindow(), KeybindUtils.getKey(mc.options.keyRight)));
    }

    // ─── Key helpers ────────────────────────────────────────────────────────
    private boolean isScriptHeldKey(int keyCode) {
        if (keyCode == KeybindUtils.getKey(mc.options.keyUp)) return mc.options.keyUp.isDown();
        if (keyCode == KeybindUtils.getKey(mc.options.keyDown)) return mc.options.keyDown.isDown();
        if (keyCode == KeybindUtils.getKey(mc.options.keyLeft)) return mc.options.keyLeft.isDown();
        if (keyCode == KeybindUtils.getKey(mc.options.keyRight)) return mc.options.keyRight.isDown();
        if (keyCode == KeybindUtils.getKey(mc.options.keyJump)) return mc.options.keyJump.isDown();
        if (keyCode == KeybindUtils.getKey(mc.options.keyShift)) return mc.options.keyShift.isDown();
        if (keyCode == KeybindUtils.getKey(mc.options.keySprint)) return mc.options.keySprint.isDown();
        return false;
    }

    private boolean isManualMovementKey(int keyCode) {
        return keyCode == KeybindUtils.getKey(mc.options.keyUp)
                || keyCode == KeybindUtils.getKey(mc.options.keyDown)
                || keyCode == KeybindUtils.getKey(mc.options.keyLeft)
                || keyCode == KeybindUtils.getKey(mc.options.keyRight)
                || keyCode == KeybindUtils.getKey(mc.options.keyJump)
                || keyCode == KeybindUtils.getKey(mc.options.keyShift)
                || keyCode == KeybindUtils.getKey(mc.options.keySprint);
    }

    private void captureInitialMovementHolds() {
        ignoreForwardUntilRelease = mc.options.keyUp.isDown();
        ignoreBackUntilRelease = mc.options.keyDown.isDown();
        ignoreLeftUntilRelease = mc.options.keyLeft.isDown();
        ignoreRightUntilRelease = mc.options.keyRight.isDown();
        ignoreJumpUntilRelease = mc.options.keyJump.isDown();
        ignoreSneakUntilRelease = mc.options.keyShift.isDown();
        ignoreSprintUntilRelease = mc.options.keySprint.isDown();
    }

    private boolean isInitialMovementHold(int keyCode) {
        if (keyCode == KeybindUtils.getKey(mc.options.keyUp)) return ignoreForwardUntilRelease;
        if (keyCode == KeybindUtils.getKey(mc.options.keyDown)) return ignoreBackUntilRelease;
        if (keyCode == KeybindUtils.getKey(mc.options.keyLeft)) return ignoreLeftUntilRelease;
        if (keyCode == KeybindUtils.getKey(mc.options.keyRight)) return ignoreRightUntilRelease;
        if (keyCode == KeybindUtils.getKey(mc.options.keyJump)) return ignoreJumpUntilRelease;
        if (keyCode == KeybindUtils.getKey(mc.options.keyShift)) return ignoreSneakUntilRelease;
        if (keyCode == KeybindUtils.getKey(mc.options.keySprint)) return ignoreSprintUntilRelease;
        return false;
    }

    private void clearInitialMovementHold(int keyCode) {
        if (keyCode == KeybindUtils.getKey(mc.options.keyUp)) ignoreForwardUntilRelease = false;
        if (keyCode == KeybindUtils.getKey(mc.options.keyDown)) ignoreBackUntilRelease = false;
        if (keyCode == KeybindUtils.getKey(mc.options.keyLeft)) ignoreLeftUntilRelease = false;
        if (keyCode == KeybindUtils.getKey(mc.options.keyRight)) ignoreRightUntilRelease = false;
        if (keyCode == KeybindUtils.getKey(mc.options.keyJump)) ignoreJumpUntilRelease = false;
        if (keyCode == KeybindUtils.getKey(mc.options.keyShift)) ignoreSneakUntilRelease = false;
        if (keyCode == KeybindUtils.getKey(mc.options.keySprint)) ignoreSprintUntilRelease = false;
    }

    private void clearInitialMovementHolds() {
        ignoreForwardUntilRelease = false;
        ignoreBackUntilRelease = false;
        ignoreLeftUntilRelease = false;
        ignoreRightUntilRelease = false;
        ignoreJumpUntilRelease = false;
        ignoreSneakUntilRelease = false;
        ignoreSprintUntilRelease = false;
    }

    // ─── Camera takeover detection ──────────────────────────────────────────
    private boolean detectManualCameraTakeover() {
        if (!running || setupTick >= 0 || System.currentTimeMillis() < takeoverDetectionAt) return false;
        LocalPlayer player = mc.player;
        if (player == null) return false;

        long now = System.currentTimeMillis();
        float expectedYaw = scriptedRotationYaw;
        float expectedPitch = scriptedRotationPitch;
        if (!takeoverCameraValid) {
            takeoverCameraValid = true;
            takeoverCameraYaw = player.getYRot();
            takeoverCameraPitch = player.getXRot();
            takeoverAccumulated = 0.0f;
            takeoverLastFrameAt = now;
            return false;
        }

        double yawInput = Math.abs(tellyWrapAngle(player.getYRot() - expectedYaw));
        double pitchInput = Math.abs(player.getXRot() - expectedPitch);
        double noiseFloor = mouseQuantum() * 0.45;

        long elapsed = Math.max(0L, now - takeoverLastFrameAt);
        takeoverLastFrameAt = now;
        takeoverAccumulated -= (float) (elapsed * 0.045);
        if (takeoverAccumulated < 0.0f) takeoverAccumulated = 0.0f;
        if (yawInput > noiseFloor || pitchInput > noiseFloor) {
            takeoverAccumulated += (float) (yawInput + pitchInput);
        }

        takeoverCameraYaw = player.getYRot();
        takeoverCameraPitch = player.getXRot();

        if (takeoverAccumulated >= 25.0f) {
            stopAutomation(true);
            return true;
        }
        return false;
    }

    // ─── Auto-swap ──────────────────────────────────────────────────────────
    private void handleAutoSwap(LocalPlayer player) {
        if (!autoSwap.getValue()) return;
        int threshold = 5;
        ItemStack held = player.getMainHandItem();
        int heldCount = held != null && isUsableBlockStack(held) ? held.getCount() : 0;
        if (heldCount > threshold) return;

        int bestSlot = -1;
        int bestSize = heldCount;
        for (int slot = 0; slot <= 8; slot++) {
            if (slot == player.getInventory().getSelectedSlot()) continue;
            ItemStack stack = player.getInventory().getItem(slot);
            if (!isUsableBlockStack(stack)) continue;
            if (stack.getCount() > bestSize) {
                bestSize = stack.getCount();
                bestSlot = slot;
            }
        }
        if (bestSlot != -1) InvUtils.swap(bestSlot, false);
    }

    // ─── Setup tick & movement ──────────────────────────────────────────────
    private void onPostPlayerInput() {
        if (!running) return;
        suppressSneakInput();
        enforceSafeWalkDisabledForRun();

        if (setupTick >= 0) {
            if (setupTick < 12) {
                boolean setupJump = setupTick >= 6;
                applyMovement(-1.0f, -1.0f, setupJump, false);
                applyUse(true);
                if (setupTick == 11) {
                    setRotationTarget(baseYaw + yawCurve[19], pitchCurve[19], 50L);
                } else {
                    setRotationTarget(baseYaw, 74.52f, 50L);
                }
                setupTick++;
                return;
            }
            setupTick = -1;
            takeoverDetectionAt = System.currentTimeMillis() + 125L;
            LocalPlayer takeoverPlayer = mc.player;
            takeoverCameraValid = takeoverPlayer != null;
            takeoverAccumulated = 0.0f;
            takeoverLastFrameAt = System.currentTimeMillis();
            if (takeoverPlayer != null) {
                takeoverCameraYaw = takeoverPlayer.getYRot();
                takeoverCameraPitch = takeoverPlayer.getXRot();
            }
            captureInitialMovementHolds();
            cyclePhase = 19;
            firstTellyPlacementPending = true;
            adaptiveAimValid = false;
            clearCachedCandidate();
            updateAdaptivePlacementAim(mc.player);
        }

        int phase = cyclePhase;
        float strafe = strafeCurve[phase];
        boolean sprinting = phase == 0 || phase == 1;
        boolean jumping = phase >= 1 && phase <= 19;
        boolean use = phase >= 7;

        applyMovement(forwardCurve[phase], strafe, jumping, sprinting);
        applyUse(use);

        int nextPhase = (phase + 1) % yawCurve.length;
        setRotationTarget(baseYaw + yawCurve[nextPhase], pitchCurve[nextPhase], 50L);
        cyclePhase = nextPhase;
    }

    // ─── Rotation system ────────────────────────────────────────────────────
    private void setRotationTarget(float targetYaw, float targetPitch, long duration) {
        LocalPlayer player = mc.player;
        if (player == null) return;

        applySmoothedRotation();
        rotationStartYaw = player.getYRot();
        rotationStartPitch = player.getXRot();
        float correctedTargetYaw = targetYaw;
        boolean adaptivePlacementTarget = running
                && tellyAutoPlaceWindow
                && firstTellyPlacementPending
                && adaptiveAimValid
                && System.currentTimeMillis() - adaptiveAimUpdatedAt <= 125L;
        if (adaptivePlacementTarget) {
            correctedTargetYaw = adaptiveAimYaw;
            targetPitch = adaptiveAimPitch;
        } else if (running) {
            correctedTargetYaw += antiSwayYawOffset;
        }

        rotationStepCounter++;
        correctedTargetYaw += (float) (mouseQuantum() * YAW_NUDGE_PATTERN[rotationStepCounter % 5]);

        rotationTargetYaw = rotationStartYaw + tellyWrapAngle(correctedTargetYaw - rotationStartYaw);
        rotationTargetPitch = clamp(targetPitch, -90.0f, 90.0f);
        rotationStartedAt = System.currentTimeMillis();
        rotationDuration = Math.max(1L, duration);
        rotationActive = true;
    }

    private void applySmoothedRotation() {
        if (!rotationActive) return;
        LocalPlayer player = mc.player;
        if (player == null) return;

        double progress = (double) (System.currentTimeMillis() - rotationStartedAt) / (double) rotationDuration;
        if (progress < 0.0) progress = 0.0;
        if (progress > 1.0) progress = 1.0;

        float desiredYaw = rotationStartYaw + (rotationTargetYaw - rotationStartYaw) * (float) progress;
        float desiredPitch = rotationStartPitch + (rotationTargetPitch - rotationStartPitch) * (float) progress;
        float quantizedYaw = quantizeFrom(rotationStartYaw, desiredYaw);
        float quantizedPitch = quantizeFrom(rotationStartPitch, desiredPitch);

        scriptedRotationYaw = quantizedYaw;
        scriptedRotationPitch = clamp(quantizedPitch, -90.0f, 90.0f);
        player.setYRot(scriptedRotationYaw);
        player.setXRot(scriptedRotationPitch);

        // Apply to rotation manager for server-side rotation spoofing
        RotationManager.INSTANCE.setRotations(new Rot2f(scriptedRotationYaw, scriptedRotationPitch), 180.0);

        if (progress >= 1.0) rotationActive = false;
    }

    private float quantizeFrom(float origin, float value) {
        double quantum = mouseQuantum();
        double steps = Math.round((value - origin) / quantum);
        return (float) (origin + steps * quantum);
    }

    // ─── Movement application ───────────────────────────────────────────────
    private void applyMovement(float forward, float strafe, boolean jumping, boolean sprinting) {
        float correctedStrafe = strafe;
        if (running) correctedStrafe = applyAntiSwayCorrection(forward, strafe);
        else antiSwayYawOffset = 0.0f;

        mc.options.keyUp.setDown(forward > 0.03f);
        mc.options.keyDown.setDown(forward < -0.03f);
        mc.options.keyLeft.setDown(correctedStrafe > 0.5f);
        mc.options.keyRight.setDown(correctedStrafe < -0.5f);
        mc.options.keyJump.setDown(jumping);
        mc.options.keySprint.setDown(sprinting);
        mc.options.keyShift.setDown(false);
    }

    private void suppressSneakInput() {
        mc.options.keyShift.setDown(false);
    }

    private void calculateTravelDirection(float yaw) {
        double radians = Math.toRadians(yaw);
        double rawX = Math.sin(radians) - Math.cos(radians);
        double rawZ = -Math.cos(radians) - Math.sin(radians);
        if (Math.abs(rawX) >= Math.abs(rawZ)) {
            travelX = rawX >= 0.0 ? 1 : -1;
            travelZ = 0;
        } else {
            travelX = 0;
            travelZ = rawZ >= 0.0 ? 1 : -1;
        }
    }

    private float applyAntiSwayCorrection(float forward, float recordedStrafe) {
        LocalPlayer player = mc.player;
        if (player == null) return recordedStrafe;

        Vec3 position = player.position();
        Vec3 motion = player.getDeltaMovement();
        double lanePosition = travelX != 0 ? position.z : position.x;
        double laneVelocity = travelX != 0 ? motion.z : motion.x;
        double error = antiSwayLane - lanePosition;

        if (Math.abs(error) < 0.015 && Math.abs(laneVelocity) < 0.008) {
            antiSwayTapUsed = false;
            antiSwayYawOffset *= 0.65f;
            if (Math.abs(antiSwayYawOffset) < 0.03f) antiSwayYawOffset = 0.0f;
            return recordedStrafe;
        }

        double desiredLaneVelocity = error * 0.42 - laneVelocity * 0.78;
        desiredLaneVelocity = clamp(desiredLaneVelocity, -0.16, 0.16);
        double velocityCorrection = desiredLaneVelocity - laneVelocity;

        double radians = Math.toRadians(player.getYRot());
        double sin = Math.sin(radians);
        double cos = Math.cos(radians);
        double yawLaneDerivative = travelX != 0
                ? -forward * sin + recordedStrafe * cos
                : -forward * cos - recordedStrafe * sin;
        double desiredYawOffset = 0.0;
        if (Math.abs(yawLaneDerivative) >= 0.12) {
            desiredYawOffset = Math.toDegrees(velocityCorrection * 0.55 / yawLaneDerivative);
        }
        desiredYawOffset = clamp(desiredYawOffset, -2.25, 2.25);
        antiSwayYawOffset = antiSwayYawOffset * 0.60f + (float) desiredYawOffset * 0.40f;

        double strafeLaneAxis = travelX != 0 ? sin : cos;
        boolean tapHelps = Math.abs(strafeLaneAxis) >= 0.20 && velocityCorrection * strafeLaneAxis > 0.0;
        if (tapHelps && !antiSwayTapUsed && Math.abs(velocityCorrection) >= 0.03 && recordedStrafe < 0.5f) {
            antiSwayTapUsed = true;
            return recordedStrafe + 1.0f;
        }

        return recordedStrafe;
    }

    // ─── Use key ────────────────────────────────────────────────────────────
    private void applyUse(boolean pressed) {
        if (pressed && !autoPlaceDebugActive) {
            printModuleStatus("AutoPlace activated");
        }
        autoPlaceDebugActive = pressed;
        tellyAutoPlaceWindow = pressed;
        mc.options.keyUse.setDown(pressed);
    }

    private void restorePhysicalUse() {
        tellyAutoPlaceWindow = false;
        autoPlaceDebugActive = false;
        // 必须回读物理鼠标键：setDown(keyUse.isDown()) 是自赋值，等于什么都没做，
        // 右键会一直停在被抑制的 false 上。
        mc.options.keyUse.setDown(mc.mouseHandler.isRightPressed());
    }

    private void releaseMovementKeys() {
        restorePhysicalKey(mc.options.keyUp);
        restorePhysicalKey(mc.options.keyDown);
        restorePhysicalKey(mc.options.keyLeft);
        restorePhysicalKey(mc.options.keyRight);
        restorePhysicalKey(mc.options.keyJump);
        restorePhysicalKey(mc.options.keyShift);
        restorePhysicalKey(mc.options.keySprint);
    }

    private void restorePhysicalKey(net.minecraft.client.KeyMapping key) {
        key.setDown(InputConstants.isKeyDown(mc.getWindow(), KeybindUtils.getKey(key)));
    }

    // ─── SafeWalk integration ───────────────────────────────────────────────
    private void disableSafeWalkForRun() {
        if (safeWalkStateCaptured) {
            enforceSafeWalkDisabledForRun();
            return;
        }
        if (!disableSafeWalk.getValue()) return;
        try {
            Module safeWalk = ModuleManager.INSTANCE.getModules().stream()
                    .filter(m -> m.getName().equals("SafeWalk"))
                    .findFirst().orElse(null);
            safeWalkWasEnabled = safeWalk != null && safeWalk.isEnabled();
            safeWalkStateCaptured = true;
            if (safeWalkWasEnabled && safeWalk != null) safeWalk.setEnabled(false);
        } catch (Exception ignored) {
            safeWalkStateCaptured = false;
        }
    }

    private void enforceSafeWalkDisabledForRun() {
        if (!safeWalkStateCaptured) return;
        try {
            Module safeWalk = ModuleManager.INSTANCE.getModules().stream()
                    .filter(m -> m.getName().equals("SafeWalk"))
                    .findFirst().orElse(null);
            if (safeWalk != null && safeWalk.isEnabled()) safeWalk.setEnabled(false);
        } catch (Exception ignored) {}
    }

    private void restoreSafeWalkState() {
        if (!safeWalkStateCaptured) return;
        boolean restoreEnabled = safeWalkWasEnabled;
        safeWalkStateCaptured = false;
        try {
            Module safeWalk = ModuleManager.INSTANCE.getModules().stream()
                    .filter(m -> m.getName().equals("SafeWalk"))
                    .findFirst().orElse(null);
            if (safeWalk != null) {
                boolean currentlyEnabled = safeWalk.isEnabled();
                if (restoreEnabled && !currentlyEnabled) safeWalk.setEnabled(true);
                if (!restoreEnabled && currentlyEnabled) safeWalk.setEnabled(false);
            }
        } catch (Exception ignored) {}
    }

    // ─── Straight bridge ────────────────────────────────────────────────────
    private void initializeStraightBridgeLane(LocalPlayer player) {
        Vec3 position = player.position();
        int startX = floor(position.x);
        int startY = floor(position.y) - 1;
        int startZ = floor(position.z);
        bridgeLaneBlock = travelX != 0 ? startZ : startX;
        bridgeStartProgress = startX * travelX + startZ * travelZ;

        BlockHitResult hit = raycastBlock(4.5);
        if (hit != null && hit.getType() != HitResult.Type.MISS) {
            BlockPos hitPos = hit.getBlockPos();
            int hitLane = travelX != 0 ? hitPos.getZ() : hitPos.getX();
            int hitProgress = straightProgress(new int[]{hitPos.getX(), hitPos.getY(), hitPos.getZ()});
            if (hitLane == bridgeLaneBlock
                    && Math.abs(hitPos.getX() - startX) <= 2
                    && Math.abs(hitPos.getZ() - startZ) <= 2
                    && hitProgress < bridgeStartProgress) {
                bridgeStartProgress = hitProgress;
            }
        }
        latestStraightPlacedPos = new int[]{startX, startY, startZ};
    }

    private int straightProgress(int[] position) {
        if (position == null) return Integer.MIN_VALUE;
        return position[0] * travelX + position[2] * travelZ;
    }

    private boolean isStraightTellyTarget(int[] position) {
        if (!running || position == null) return true;
        int lane = travelX != 0 ? position[2] : position[0];
        if (lane != bridgeLaneBlock) return false;
        return straightProgress(position) >= bridgeStartProgress;
    }

    // ─── Adaptive aim ───────────────────────────────────────────────────────
    private void updateAdaptivePlacementAim(LocalPlayer player) {
        if (!firstTellyPlacementPending) return;
        Object[] candidate = cachedCandidate;
        if (candidate != null) {
            int[] target = candidatePlacedPos(candidate);
            Vec3 hitVec = candidateHitVec(candidate);
            if (isStraightTellyTarget(target) && hitVec != null) {
                setAdaptiveAimToPoint(player, hitVec);
                return;
            }
        }
        int[] support = latestStraightPlacedPos != null ? latestStraightPlacedPos : lastPlacedPos;
        if (support == null || !isStraightTellyTarget(support)) return;
        int face = travelX > 0 ? 5 : travelX < 0 ? 4 : travelZ > 0 ? 3 : 2;
        int[] nextTarget = offsetPos(support, face);
        if (!isStraightTellyTarget(nextTarget) || !isReplaceable(nextTarget[0], nextTarget[1], nextTarget[2])) return;
        Vec3 fallbackHit = getSupportFaceHitVec(support, face, 0.5, 0.5);
        setAdaptiveAimToPoint(player, fallbackHit);
    }

    private void setAdaptiveAimToPoint(LocalPlayer player, Vec3 point) {
        if (player == null || point == null) return;
        Vec3 position = player.position();
        double eyeX = position.x;
        double eyeY = position.y + player.getEyeHeight();
        double eyeZ = position.z;
        double dx = point.x - eyeX;
        double dy = point.y - eyeY;
        double dz = point.z - eyeZ;
        double horizontal = Math.sqrt(dx * dx + dz * dz);
        if (horizontal < 1.0E-5 && Math.abs(dy) < 1.0E-5) return;
        adaptiveAimYaw = (float) (Math.toDegrees(Math.atan2(dz, dx)) - 90.0);
        adaptiveAimPitch = clamp((float) (-Math.toDegrees(Math.atan2(dy, horizontal))), -89.0f, 89.0f);
        adaptiveAimUpdatedAt = System.currentTimeMillis();
        adaptiveAimValid = true;
    }

    // ─── Auto-place system ──────────────────────────────────────────────────
    private void autoPlaceOnPreUpdate() {
        LocalPlayer player = mc.player;
        if (player == null) return;
        syncPlacementTick(player);
        if (placementEvaluationTick != currentClientTick) {
            placementEvaluationTick = currentClientTick;
            processAutoPlaceTick(player);
        }
    }

    private void syncPlacementTick(LocalPlayer player) {
        int tick = player.tickCount;
        if (tick == currentClientTick) return;
        currentClientTick = tick;
        candidateResolvedThisTick = false;
        silentPitchActive = false;
    }

    private void autoPlaceOnPostMotion() {
        c08CounterAtTickBoundary = totalC08Counter;
        manualC08InWindow = false;
    }

    private boolean autoPlaceOnPacketSent(Packet<?> packet) {
        if (packet instanceof ServerboundMovePlayerPacket move) {
            // Track last sent position
            return true;
        }
        if (packet instanceof ServerboundUseItemOnPacket useItem) {
            if (shouldCancelAutoPlaceUseItem()) {
                suppressUse();
                return false;
            }
            // Track placement count
            totalC08Counter++;
            if (!placingViaModule) manualC08InWindow = true;
        }
        return true;
    }

    private void processAutoPlaceTick(LocalPlayer player) {
        pruneRejectedTargets();
        if (lastPlacedPos != null && !isSupportAvailable(lastPlacedPos[0], lastPlacedPos[1], lastPlacedPos[2])) {
            lastPlacedPos = null;
            lastSupportPos = null;
            lastSupportFace = -1;
        }
        if (!isAutoPlaceActiveWindow(player)) {
            clearCachedCandidate();
            if (useSuppressed) restoreUseToPhysicalState();
            return;
        }
        ItemStack heldStack = player.getMainHandItem();
        if (!isUsableBlockStack(heldStack)) {
            clearCachedCandidate();
            if (useSuppressed) restoreUseToPhysicalState();
            return;
        }
        if (!isBlockBelowPlayerReplaceable(player)) {
            clearCachedCandidate();
            if (useSuppressed) restoreUseToPhysicalState();
            return;
        }

        float yaw = player.getYRot();
        float basePitch = sanitizePitch(player.getXRot(), player.getXRot());
        Object[] candidate = resolveCandidateWithOffCursorSilentPitch(player, yaw, basePitch, heldStack);
        if (candidate != null) {
            silentPitch = sanitizePitch(candidatePitch(candidate), basePitch);
            silentPitchActive = true;
            suppressUse();
        } else if (useSuppressed && !placedInCurrentWindow() && lastPlacementAttemptTick != currentClientTick) {
            restoreUseToPhysicalState();
        }

        if (placedInCurrentWindow() || lastPlacementAttemptTick == currentClientTick) {
            suppressUse();
            return;
        }
        if (candidate == null) {
            clearCachedCandidate();
            return;
        }
        lastPlacementAttemptTick = currentClientTick;

        if (attemptPlacement(player, candidate, heldStack)) return;
        if (placedInCurrentWindow()) return;

        float retryYaw = player.getYRot();
        float retryPitch = player.getXRot();
        clearCachedCandidate();
        Object[] retryCandidate = findBelowPlacement(player, retryYaw, retryPitch, heldStack, System.currentTimeMillis() + 4L);
        cacheCandidate(retryCandidate, retryYaw, retryPitch);
        if (retryCandidate != null) {
            silentPitch = sanitizePitch(candidatePitch(retryCandidate), retryPitch);
            silentPitchActive = true;
            attemptPlacement(player, retryCandidate, heldStack);
        }
    }

    private boolean attemptPlacement(LocalPlayer player, Object[] candidate, ItemStack heldStack) {
        if (candidate == null) return false;
        int[] placedPos = candidatePlacedPos(candidate);
        int[] supportPos = candidateSupportPos(candidate);
        int face = candidateFace(candidate);
        if (placedPos == null || supportPos == null || face <= 0) return false;
        if (!isStraightTellyTarget(placedPos)) return false;
        if (!isBlockBelowPlayerReplaceable(player)) return false;
        if (!isUsableBlockStack(player.getMainHandItem())) return false;
        if (placedInCurrentWindow()) return false;

        float placementPitch = sanitizePitch(candidatePitch(candidate), player.getXRot());
        Object[] prePlaceHit = resolveVerifiedHit(player.getYRot(), placementPitch, supportPos, face, placedPos);
        if (prePlaceHit == null) return false;
        if (cancelledGhostBlocks.contains(posKey(supportPos))) return false;
        if (!isReplaceable(placedPos[0], placedPos[1], placedPos[2])) return false;
        if (!isSupportAvailable(supportPos[0], supportPos[1], supportPos[2])) return false;
        if (doesPlacementIntersectPlayer(player, placedPos)) return false;

        long counterBefore = totalC08Counter;
        placingViaModule = true;
        // Perform placement via MC game mode
        BlockPos supportBlockPos = new BlockPos(supportPos[0], supportPos[1], supportPos[2]);
        Direction placeDir = intToDirection(face);
        Vec3 hitVec = getSupportFaceHitVec(supportPos, face, 0.5, 0.5);
        BlockHitResult blockHit = new BlockHitResult(hitVec, placeDir, supportBlockPos, false);
        mc.gameMode.useItemOn(player, InteractionHand.MAIN_HAND, blockHit);
        placingViaModule = false;
        boolean packetSent = totalC08Counter > counterBefore;

        if (!packetSent) {
            markRejectedTarget(placedPos);
            return false;
        }

        lastPlacedPos = placedPos;
        lastSupportPos = supportPos;
        lastSupportFace = face;
        lastSuccessfulPlaceTick = currentClientTick;
        forceSuppressTick = currentClientTick;
        player.swing(InteractionHand.MAIN_HAND);
        return true;
    }

    private Object[] resolveVerifiedHit(float yaw, float pitch, int[] expectedSupport, int expectedFace, int[] expectedPlaced) {
        Object[] traced = rayCast(yaw, pitch);
        if (traced == null) return null;
        int[] tracedSupport = (int[]) traced[0];
        int tracedFace = (Integer) traced[1];
        if (!posEquals(tracedSupport, expectedSupport) || tracedFace != expectedFace) return null;
        int[] tracedPlaced = offsetPos(tracedSupport, tracedFace);
        if (!posEquals(tracedPlaced, expectedPlaced)) return null;
        return traced;
    }

    private Object[] resolveCandidateWithOffCursorSilentPitch(LocalPlayer player, float yaw, float basePitch, ItemStack heldStack) {
        float safeBasePitch = sanitizePitch(basePitch, player.getXRot());
        Object[] previousCandidate = cachedCandidate;
        Object[] baseCandidate = resolveCandidateForCurrentTick(player, yaw, safeBasePitch, heldStack);
        if (baseCandidate == null) {
            if (previousCandidate != null) {
                float previousBlockPitch = getBlockDerivedSilentPitch(player, previousCandidate, safeBasePitch);
                Object[] recovered = resolveCandidateForCurrentTick(player, yaw, previousBlockPitch, heldStack);
                if (recovered != null) return recovered;
                cacheCandidate(previousCandidate, yaw, safeBasePitch);
                return previousCandidate;
            }
            return null;
        }
        if (isPlacementLookAligned(yaw, safeBasePitch, candidateSupportPos(baseCandidate), candidateFace(baseCandidate), candidatePlacedPos(baseCandidate))) {
            return baseCandidate;
        }
        float blockPitch = getBlockDerivedSilentPitch(player, baseCandidate, safeBasePitch);
        if (isPlacementLookAligned(yaw, blockPitch, candidateSupportPos(baseCandidate), candidateFace(baseCandidate), candidatePlacedPos(baseCandidate))) {
            return new Object[]{blockPitch, candidateSupportPos(baseCandidate), candidateFace(baseCandidate), candidateHitVec(baseCandidate), candidatePlacedPos(baseCandidate)};
        }
        Object[] corrected = resolveCandidateForCurrentTick(player, yaw, blockPitch, heldStack);
        if (corrected != null && posEquals(candidatePlacedPos(baseCandidate), candidatePlacedPos(corrected))) {
            return corrected;
        }
        cacheCandidate(baseCandidate, yaw, safeBasePitch);
        return baseCandidate;
    }

    private Object[] resolveCandidateForCurrentTick(LocalPlayer player, float yaw, float pitch, ItemStack heldStack) {
        float safePitch = sanitizePitch(pitch, player.getXRot());
        if (hasCachedCandidateForCurrentTick(yaw, safePitch)) return cachedCandidate;
        Object[] candidate = findBelowPlacement(player, yaw, safePitch, heldStack, System.currentTimeMillis() + 8L);
        cacheCandidate(candidate, yaw, safePitch);
        return candidate;
    }

    private float getBlockDerivedSilentPitch(LocalPlayer player, Object[] candidate, float fallbackPitch) {
        if (candidate == null) return sanitizePitch(fallbackPitch, fallbackPitch);
        Vec3 hitVec = candidateHitVec(candidate);
        if (hitVec != null) {
            Float derived = computePitchToHitVec(player, hitVec);
            if (derived != null) return sanitizePitch(derived, fallbackPitch);
        }
        return sanitizePitch(candidatePitch(candidate), fallbackPitch);
    }

    // ─── Candidate cache ────────────────────────────────────────────────────
    private void cacheCandidate(Object[] candidate, float yaw, float pitch) {
        cachedCandidate = candidate;
        cachedCandidateTick = currentClientTick;
        cachedCandidateYaw = yaw;
        cachedCandidatePitch = pitch;
        candidateResolvedThisTick = candidate != null;
    }

    private boolean hasCachedCandidateForCurrentTick(float yaw, float pitch) {
        if (cachedCandidateTick != currentClientTick || !candidateResolvedThisTick || cachedCandidate == null) return false;
        if (Float.isNaN(cachedCandidateYaw) || Float.isNaN(cachedCandidatePitch)) return false;
        return Math.abs(wrapAngle(yaw - cachedCandidateYaw)) <= 0.75f && Math.abs(pitch - cachedCandidatePitch) <= 0.75f;
    }

    private void clearCachedCandidate() {
        cachedCandidate = null;
        cachedCandidateTick = Integer.MIN_VALUE;
        cachedCandidateYaw = Float.NaN;
        cachedCandidatePitch = Float.NaN;
        candidateResolvedThisTick = false;
    }

    // ─── findBelowPlacement (BSLegitTellyFix — candidate-list + scoring) ───
    private Object[] findBelowPlacement(LocalPlayer player, float yaw, float currentPitch, ItemStack heldStack, long deadlineMs) {
        if (System.currentTimeMillis() >= deadlineMs) return null;

        Object[] cursorRayCandidate = findDirectCursorRayPlacement(player, yaw, currentPitch, heldStack);
        if (cursorRayCandidate != null) return cursorRayCandidate;

        int currentY = getCurrentBelowTargetY(player);
        int strictY = getStrictBelowTargetY(player);
        int previousY = getPreviousBelowTargetY(player);
        int[] feetPos = getFeetBelowTargetAtY(player, currentY);

        List<int[]> targets = new ArrayList<>();
        addBelowTarget(player, targets, feetPos);
        addBelowTarget(player, targets, offsetPos(feetPos, facingFromYaw(yaw)));

        for (int dy = 0; dy <= 2; dy++) {
            int targetY = dy == 0 ? currentY : dy == 1 ? strictY : previousY;
            if (targetY == Integer.MIN_VALUE || (dy == 1 && targetY == currentY) || (dy == 2 && (targetY == currentY || targetY == strictY))) continue;
            addBelowTarget(player, targets, new int[]{feetPos[0], targetY, feetPos[2]});
        }

        for (int dx = -1; dx <= 1; dx++) {
            for (int dz = -1; dz <= 1; dz++) {
                addBelowTarget(player, targets, new int[]{feetPos[0] + dx, currentY, feetPos[2] + dz});
                if (strictY != currentY) addBelowTarget(player, targets, new int[]{feetPos[0] + dx, strictY, feetPos[2] + dz});
                if (previousY != currentY && previousY != strictY) addBelowTarget(player, targets, new int[]{feetPos[0] + dx, previousY, feetPos[2] + dz});
            }
        }

        if (!player.onGround()) {
            addBelowTarget(player, targets, getMotionBelowTargetAtY(player, currentY, 1.0));
            if (previousY != currentY && previousY != strictY) {
                addBelowTarget(player, targets, getMotionBelowTargetAtY(player, previousY, 1.0));
            }
        }

        Object[] bestCandidate = null;
        double bestScore = Double.POSITIVE_INFINITY;
        for (int[] targetPos : targets) {
            if (System.currentTimeMillis() >= deadlineMs) return null;
            if (!isPlacementTargetAvailable(player, targetPos)) continue;
            Object[] candidate = findPitchPlacementForTarget(player, yaw, currentPitch, targetPos, heldStack, null, -1, deadlineMs, false, true);
            if (candidate == null) continue;
            double score = scorePlacementCandidate(player, currentPitch, candidatePitch(candidate), candidateFace(candidate), 0.5, 0.5);
            if (score < bestScore) {
                bestScore = score;
                bestCandidate = candidate;
            }
        }
        return bestCandidate;
    }

    private Object[] findDirectCursorRayPlacement(LocalPlayer player, float yaw, float pitch, ItemStack heldStack) {
        if (!isUsableBlockStack(heldStack)) return null;
        Object[] traced = rayCast(yaw, pitch);
        if (traced == null) return null;
        int[] supportPos = (int[]) traced[0];
        int face = (Integer) traced[1];
        if (face == 0) return null;
        int[] targetPos = offsetPos(supportPos, face);
        if (!isPlacementTargetAvailable(player, targetPos)) return null;
        if (!isSupportAvailable(supportPos[0], supportPos[1], supportPos[2])) return null;
        if (shouldRejectStraightSideSwitch(player, targetPos, face)) return null;
        float tracedPitch = clampFloat(pitch, -89.0f, 89.0f);
        return new Object[]{tracedPitch, supportPos, face, (Vec3) traced[2], targetPos};
    }

    private Object[] findPitchPlacementForTarget(LocalPlayer player, float yaw, float currentPitch, int[] targetPos, ItemStack heldStack, int[] preferredSupportPos, int preferredSupportFace, long deadlineMs, boolean requireLookAlignment, boolean allowNonCursorTarget) {
        if (System.currentTimeMillis() >= deadlineMs || targetPos == null) return null;
        boolean effectiveAllowNonCursorTarget = allowNonCursorTarget || shouldAllowPlayerOneNonCursorTarget(player, targetPos);
        if (!effectiveAllowNonCursorTarget && !isCursorOrBelowPlayerTarget(player, targetPos, yaw, currentPitch)) return null;
        if (!isPlacementTargetAvailable(player, targetPos)) return null;

        Object[] bestCandidate = null;
        double bestScore = Double.POSITIVE_INFINITY;
        for (int placeFace : getAllowedPlaceFacesForContext(player, yaw)) {
            if (System.currentTimeMillis() >= deadlineMs) break;
            if (shouldRejectStraightSideSwitch(player, targetPos, placeFace)) continue;
            int[] supportPos = offsetPos(targetPos, opposite(placeFace));
            if (preferredSupportPos != null && !posEquals(supportPos, preferredSupportPos)) continue;
            if (preferredSupportFace >= 0 && placeFace != preferredSupportFace) continue;
            if (!isSupportAvailable(supportPos[0], supportPos[1], supportPos[2])) continue;
            if (!isWithinReach(player, supportPos)) continue;

            for (double primaryOffset : EXTENDED_FACE_HIT_OFFSETS) {
                for (double secondaryOffset : EXTENDED_FACE_HIT_OFFSETS) {
                    if (System.currentTimeMillis() >= deadlineMs) break;
                    Vec3 hitVec = getSupportFaceHitVec(supportPos, placeFace, primaryOffset, secondaryOffset);
                    Object[] candidate = buildPlacementCandidateForHitVec(player, yaw, targetPos, supportPos, placeFace, hitVec, requireLookAlignment, effectiveAllowNonCursorTarget);
                    if (candidate == null) continue;
                    double candidateScore = scorePlacementCandidate(player, currentPitch, candidatePitch(candidate), placeFace, primaryOffset, secondaryOffset);
                    if (candidateScore < bestScore) {
                        bestScore = candidateScore;
                        bestCandidate = candidate;
                    }
                }
            }
        }
        return bestCandidate;
    }

    // ─── Placement scoring ──────────────────────────────────────────────────
    private double scorePlacementCandidate(LocalPlayer player, float currentPitch, float candidatePitchValue, int placeFace, double primaryOffset, double secondaryOffset) {
        double pitchPenalty = Math.abs(wrapAngle(candidatePitchValue - currentPitch));
        double centerPenalty = Math.abs(primaryOffset - 0.5) + Math.abs(secondaryOffset - 0.5);
        double facePenalty = placeFace == 1 ? 0.0 : 0.35;
        double straightSidePenalty = getStraightSideSwitchPenalty(player, placeFace);
        return pitchPenalty + centerPenalty * 2.0 + facePenalty + straightSidePenalty;
    }

    private double getStraightSideSwitchPenalty(LocalPlayer player, int placeFace) {
        if (getConditionModeCheck(player) != 1) return 0.0;
        if (lastSupportFace < 2) return 0.0;
        if (placeFace == lastSupportFace) return 0.0;
        return 0.8;
    }

    private boolean shouldRejectStraightSideSwitch(LocalPlayer player, int[] targetPos, int placeFace) {
        if (targetPos == null || getConditionModeCheck(player) != 1) return false;
        if (placeFace < 2 || lastSupportFace < 2 || placeFace == lastSupportFace) return false;
        if (isNearStraightSupportEdge(player)) return false;
        int[] laneSupportPos = offsetPos(targetPos, opposite(lastSupportFace));
        return isSupportAvailable(laneSupportPos[0], laneSupportPos[1], laneSupportPos[2]) && isWithinReach(player, laneSupportPos);
    }

    // ─── Placement helpers ──────────────────────────────────────────────────
    private Object[] buildPlacementCandidateForHitVec(LocalPlayer player, float yaw, int[] targetPos, int[] supportPos, int placeFace, Vec3 hitVec, boolean requireLookAlignment, boolean allowNonCursorTarget) {
        if (hitVec == null) return null;
        int[] offsetTarget = offsetPos(supportPos, placeFace);
        if (!posEquals(offsetTarget, targetPos)) return null;
        if (!isStrictOneBelowPlayer(player, offsetTarget)) return null;
        Float pitch = computePitchToHitVec(player, hitVec);
        if (pitch == null) return null;
        if (!isPlacementLookAligned(yaw, pitch, supportPos, placeFace, targetPos)) return null;
        if (!(allowNonCursorTarget || isDiagonalMovementContext(player) || isSupportFaceVisible(player, supportPos, placeFace, hitVec))) return null;
        return new Object[]{pitch, supportPos, placeFace, hitVec, offsetTarget};
    }

    private int[] getAllowedPlaceFacesForContext(LocalPlayer player, float yaw) {
        if (getConditionModeCheck(player) != 1) return ALLOWED_PLACE_FACES;
        int forward = getStraightForwardFacing(player, yaw);
        return new int[]{rotateY(forward), rotateYCCW(forward), forward, opposite(forward), 1};
    }

    private boolean isPlacementLookAligned(float yaw, float pitch, int[] supportPos, int placeFace, int[] targetPos) {
        if (supportPos == null || placeFace < 0 || targetPos == null) return false;
        Object[] traced = rayCast(yaw, pitch);
        if (traced == null) return false;
        if (!posEquals((int[]) traced[0], supportPos) || (Integer) traced[1] != placeFace) return false;
        int[] tracedOffset = offsetPos((int[]) traced[0], (Integer) traced[1]);
        return posEquals(tracedOffset, targetPos);
    }

    private boolean isSupportFaceVisible(LocalPlayer player, int[] supportPos, int placeFace, Vec3 hitVec) {
        Vec3 eyes = getEyes(player);
        double dx = hitVec.x - eyes.x;
        double dy = hitVec.y - eyes.y;
        double dz = hitVec.z - eyes.z;
        double distance = Math.sqrt(dx * dx + dy * dy + dz * dz);
        if (distance < 1.0E-4) return false;
        float traceYaw = (float) (Math.toDegrees(Math.atan2(dz, dx)) - 90.0);
        float tracePitch = (float) (-Math.toDegrees(Math.atan2(dy, Math.sqrt(dx * dx + dz * dz))));
        BlockHitResult traced = raycastBlock(distance + 0.5, traceYaw, tracePitch);
        if (traced == null || traced.getType() == HitResult.Type.MISS) return false;
        int[] tracedPos = new int[]{traced.getBlockPos().getX(), traced.getBlockPos().getY(), traced.getBlockPos().getZ()};
        int tracedFace = directionToInt(traced.getDirection());
        return posEquals(tracedPos, supportPos) && tracedFace == placeFace;
    }

    private Vec3 getSupportFaceHitVec(int[] supportPos, int placeFace, double primaryOffset, double secondaryOffset) {
        double primary = clamp(primaryOffset, 0.001, 0.999);
        double secondary = clamp(secondaryOffset, 0.001, 0.999);
        if (placeFace == 2) return new Vec3(supportPos[0] + primary, supportPos[1] + secondary, supportPos[2] + 0.001);
        if (placeFace == 3) return new Vec3(supportPos[0] + primary, supportPos[1] + secondary, supportPos[2] + 0.999);
        if (placeFace == 5) return new Vec3(supportPos[0] + 0.999, supportPos[1] + primary, supportPos[2] + secondary);
        if (placeFace == 4) return new Vec3(supportPos[0] + 0.001, supportPos[1] + primary, supportPos[2] + secondary);
        if (placeFace == 0) return new Vec3(supportPos[0] + primary, supportPos[1] + 0.001, supportPos[2] + secondary);
        return new Vec3(supportPos[0] + primary, supportPos[1] + 0.999, supportPos[2] + secondary);
    }

    private Float computePitchToHitVec(LocalPlayer player, Vec3 hitVec) {
        Vec3 eyes = getEyes(player);
        double dx = hitVec.x - eyes.x;
        double dz = hitVec.z - eyes.z;
        double horizontal = Math.sqrt(dx * dx + dz * dz);
        double dy = hitVec.y - eyes.y;
        float pitch = (float) (-Math.toDegrees(Math.atan2(dy, horizontal)));
        return clamp(pitch, -89.0f, 89.0f);
    }

    // ─── Below target helpers ───────────────────────────────────────────────
    private List<int[]> getBelowTargets(LocalPlayer player, float yaw, float pitch) {
        if (cachedBelowTargetsTick == currentClientTick && cachedBelowTargets != null) return cachedBelowTargets;
        List<int[]> belowTargets = new ArrayList<>();
        boolean diagonal = isDiagonalMovementContext(player);
        if (!diagonal) {
            int currentY = getCurrentBelowTargetY(player);
            addBelowTarget(player, belowTargets, getCursorStartTargetAtY(player, yaw, pitch, currentY));
            if (belowTargets.isEmpty()) {
                int strictY = getStrictBelowTargetY(player);
                if (strictY != currentY) addBelowTarget(player, belowTargets, getCursorStartTargetAtY(player, yaw, pitch, strictY));
            }
            if (belowTargets.isEmpty()) addBelowTarget(player, belowTargets, getCursorPlacedTargetFromRay(yaw, pitch, currentY));
            if (belowTargets.isEmpty()) {
                int strictY = getStrictBelowTargetY(player);
                if (strictY != currentY) addBelowTarget(player, belowTargets, getCursorPlacedTargetFromRay(yaw, pitch, strictY));
            }
            if (belowTargets.isEmpty()) addBelowTarget(player, belowTargets, getCursorTargetAtY(player, yaw, pitch, currentY));
        } else {
            int currentY = getCurrentBelowTargetY(player);
            addBelowTarget(player, belowTargets, getMotionBelowTargetAtY(player, currentY, 1.0));
            addBelowTarget(player, belowTargets, getMotionBelowTargetAtY(player, currentY, 1.7));
            for (int[] endpoint : getBelowPlayerFallbackEndpoints(player, yaw, pitch, currentY)) {
                addBelowTarget(player, belowTargets, endpoint);
            }
        }
        cachedBelowTargets = belowTargets;
        cachedBelowTargetsTick = currentClientTick;
        return belowTargets;
    }

    private boolean isCursorOrBelowPlayerTarget(LocalPlayer player, int[] targetPos, float yaw, float pitch) {
        if (targetPos == null) return false;
        if (!isDiagonalMovementContext(player)) {
            int currentY = getCurrentBelowTargetY(player);
            if (posEquals(getCursorStartTargetAtY(player, yaw, pitch, currentY), targetPos)) return true;
            if (posEquals(getCursorPlacedTargetFromRay(yaw, pitch, currentY), targetPos)) return true;
            int strictY = getStrictBelowTargetY(player);
            if (strictY != currentY) {
                if (posEquals(getCursorStartTargetAtY(player, yaw, pitch, strictY), targetPos)) return true;
                if (posEquals(getCursorPlacedTargetFromRay(yaw, pitch, strictY), targetPos)) return true;
            }
            if (isCursorInsideTargetAtY(player, targetPos, yaw, pitch, currentY)) return true;
            return posEquals(getCursorTargetAtY(player, yaw, pitch, currentY), targetPos);
        }
        int strictY = getStrictBelowTargetY(player);
        if (isBelowPlayerTargetAtY(player, targetPos, strictY, yaw, pitch)) return true;
        return isBelowPlayerTargetAtY(player, targetPos, getCurrentBelowTargetY(player), yaw, pitch);
    }

    private boolean isBelowPlayerTargetAtY(LocalPlayer player, int[] targetPos, int targetY, float yaw, float pitch) {
        for (int[] candidate : getBelowPlayerFallbackEndpoints(player, yaw, pitch, targetY)) {
            if (posEquals(targetPos, candidate)) return true;
        }
        return false;
    }

    private int[] getFeetBelowTargetAtY(LocalPlayer player, int targetY) {
        Vec3 pos = player.position();
        return new int[]{floor(pos.x), targetY, floor(pos.z)};
    }

    private boolean shouldAllowPlayerOneNonCursorTarget(LocalPlayer player, int[] targetPos) {
        if (targetPos == null) return false;
        if (isDiagonalMovementContext(player) || player.onGround()) return false;
        if (!isPlayerHitboxFullyInsideSingleBlockColumn(player)) return false;
        if (!hasValidLastSupportFace(player) || lastSupportFace == 0) return false;
        int[] continuationTarget = offsetPos(lastSupportPos, lastSupportFace);
        if (!posEquals(targetPos, continuationTarget)) return false;
        int targetY = targetPos[1];
        int currentY = getCurrentBelowTargetY(player);
        int strictY = getStrictBelowTargetY(player);
        if (targetY != currentY && targetY != strictY) return false;
        int[] feetBelow = getFeetBelowTargetAtY(player, targetY);
        int horizontalDistance = Math.abs(targetPos[0] - feetBelow[0]) + Math.abs(targetPos[2] - feetBelow[2]);
        return horizontalDistance <= 1;
    }

    private boolean isPlayerHitboxFullyInsideSingleBlockColumn(LocalPlayer player) {
        Vec3 pos = player.position();
        double half = player.getBbWidth() / 2.0;
        int minX = floor(pos.x - half + 1.0E-4);
        int maxX = floor(pos.x + half - 1.0E-4);
        if (minX != maxX) return false;
        int minZ = floor(pos.z - half + 1.0E-4);
        int maxZ = floor(pos.z + half - 1.0E-4);
        return minZ == maxZ;
    }

    private int[] getMotionBelowTargetAtY(LocalPlayer player, int targetY, double multiplier) {
        Vec3 pos = player.position();
        Vec3 motion = player.getDeltaMovement();
        return new int[]{floor(pos.x + motion.x * multiplier), targetY, floor(pos.z + motion.z * multiplier)};
    }

    private boolean hasDirectSupportNeighbor(int[] targetPos) {
        for (int placeFace : ALLOWED_PLACE_FACES) {
            int[] supportPos = offsetPos(targetPos, opposite(placeFace));
            if (isSupportAvailable(supportPos[0], supportPos[1], supportPos[2])) return true;
        }
        return false;
    }

    private void addBelowTargetIfUnique(LocalPlayer player, List<int[]> targets, int[] candidate) {
        if (candidate == null) return;
        if (!isStrictOneBelowPlayer(player, candidate)) return;
        for (int[] existing : targets) {
            if (posEquals(existing, candidate)) return;
        }
        targets.add(candidate);
    }

    private void addBelowTarget(LocalPlayer player, List<int[]> targets, int[] candidate) {
        addBelowTargetIfUnique(player, targets, candidate);
    }

    private List<int[]> getBelowPlayerFallbackEndpoints(LocalPlayer player, float yaw, float pitch, int targetY) {
        List<int[]> endpoints = new ArrayList<>();
        if (!isDiagonalMovementContext(player)) {
            if (!player.onGround()) {
                addBelowTargetIfUnique(player, endpoints, getFeetBelowTargetAtY(player, targetY));
                addBelowTargetIfUnique(player, endpoints, getMotionBelowTargetAtY(player, targetY, 1.0));
                addBelowTargetIfUnique(player, endpoints, getMotionBelowTargetAtY(player, targetY, 1.7));
            }
            addBelowTargetIfUnique(player, endpoints, getCursorStartTargetAtY(player, yaw, pitch, targetY));
            addBelowTargetIfUnique(player, endpoints, getCursorPlacedTargetFromRay(yaw, pitch, targetY));
            addBelowTargetIfUnique(player, endpoints, getCursorTargetAtY(player, yaw, pitch, targetY));
            return endpoints;
        }
        addBelowTargetIfUnique(player, endpoints, getMotionBelowTargetAtY(player, targetY, 1.0));
        addBelowTargetIfUnique(player, endpoints, getMotionBelowTargetAtY(player, targetY, 1.7));
        return endpoints;
    }

    // ─── Fallback placement methods ─────────────────────────────────────────
    private Object[] findBelowPlayerAirborneFallback(LocalPlayer player, float yaw, float currentPitch, ItemStack heldStack, long deadlineMs) {
        if (System.currentTimeMillis() >= deadlineMs) return null;
        int playerBelowY = getCurrentBelowTargetY(player);
        boolean diagonal = isDiagonalMovementContext(player);
        boolean allowNonCursorTarget = diagonal || !player.onGround();
        List<int[]> fallbackTargets = new ArrayList<>();
        for (int[] endpoint : getBelowPlayerFallbackEndpoints(player, yaw, currentPitch, playerBelowY)) {
            addBelowTarget(player, fallbackTargets, endpoint);
        }
        for (int[] targetPos : fallbackTargets) {
            if (System.currentTimeMillis() >= deadlineMs) return null;
            if (!isPlacementTargetAvailable(player, targetPos)) continue;
            Object[] candidate = findPitchPlacementForTarget(player, yaw, currentPitch, targetPos, heldStack, null, -1, deadlineMs, false, allowNonCursorTarget);
            if (candidate != null) return candidate;
        }
        return null;
    }

    private Object[] findLegacyBelowPlacement(LocalPlayer player, float yaw, float currentPitch, ItemStack heldStack, long deadlineMs) {
        if (System.currentTimeMillis() >= deadlineMs || !isUsableBlockStack(heldStack)) return null;
        if (isDiagonalMovementContext(player)) {
            Object[] diagonalCandidate = findLegacyDiagonalPlacement(player, yaw, currentPitch, heldStack, deadlineMs);
            if (diagonalCandidate != null) return diagonalCandidate;
        }
        if (hasValidLastPlacedPos(player)) {
            Object[] preferred = findLegacyBelowPlacementForSupport(player, yaw, currentPitch, heldStack, lastPlacedPos, deadlineMs);
            if (preferred != null) return preferred;
        }
        return findLegacyBelowPlacementForSupport(player, yaw, currentPitch, heldStack, null, deadlineMs);
    }

    private Object[] findLegacyDiagonalPlacement(LocalPlayer player, float yaw, float currentPitch, ItemStack heldStack, long deadlineMs) {
        if (System.currentTimeMillis() >= deadlineMs) return null;
        List<int[]> diagonalTargets = new ArrayList<>();
        int currentY = getCurrentBelowTargetY(player);
        int strictY = getStrictBelowTargetY(player);
        for (int[] endpoint : getBelowPlayerFallbackEndpoints(player, yaw, currentPitch, currentY)) {
            addBelowTarget(player, diagonalTargets, endpoint);
        }
        if (strictY != currentY) {
            for (int[] endpoint : getBelowPlayerFallbackEndpoints(player, yaw, currentPitch, strictY)) {
                addBelowTarget(player, diagonalTargets, endpoint);
            }
        }
        if (diagonalTargets.isEmpty()) return null;
        int[] preferredSupportPos = hasValidLastPlacedPos(player) ? lastPlacedPos : null;
        for (int[] targetPos : diagonalTargets) {
            if (System.currentTimeMillis() >= deadlineMs) return null;
            if (!isPlacementTargetAvailable(player, targetPos)) continue;
            Object[] candidate = findLegacyPitchPlacementForTarget(player, yaw, currentPitch, targetPos, heldStack, preferredSupportPos, deadlineMs);
            if (candidate != null) return candidate;
        }
        if (preferredSupportPos == null) return null;
        for (int[] targetPos : diagonalTargets) {
            if (System.currentTimeMillis() >= deadlineMs) return null;
            if (!isPlacementTargetAvailable(player, targetPos)) continue;
            Object[] candidate = findLegacyPitchPlacementForTarget(player, yaw, currentPitch, targetPos, heldStack, null, deadlineMs);
            if (candidate != null) return candidate;
        }
        return null;
    }

    private Object[] findLegacyBelowPlacementForSupport(LocalPlayer player, float yaw, float currentPitch, ItemStack heldStack, int[] preferredSupportPos, long deadlineMs) {
        for (int[] targetPos : getMessageStyleBelowTargets(player)) {
            if (System.currentTimeMillis() >= deadlineMs) return null;
            if (!isPlacementTargetAvailable(player, targetPos)) continue;
            Object[] candidate = findLegacyPitchPlacementForTarget(player, yaw, currentPitch, targetPos, heldStack, preferredSupportPos, deadlineMs);
            if (candidate != null) return candidate;
        }
        return null;
    }

    private Object[] findLegacyPitchPlacementForTarget(LocalPlayer player, float yaw, float currentPitch, int[] targetPos, ItemStack heldStack, int[] preferredSupportPos, long deadlineMs) {
        float clampedBasePitch = clampFloat(currentPitch, 40.0f, 89.0f);
        Object[] direct = tryLegacyPitch(yaw, clampedBasePitch, targetPos, preferredSupportPos, deadlineMs);
        if (direct != null) return direct;
        for (int offset = 1; offset <= 49; offset++) {
            if (System.currentTimeMillis() >= deadlineMs) return null;
            float up = clampedBasePitch + offset;
            if (up <= 89.0f) {
                Object[] candidate = tryLegacyPitch(yaw, up, targetPos, preferredSupportPos, deadlineMs);
                if (candidate != null) return candidate;
            }
            float down = clampedBasePitch - offset;
            if (down >= 40.0f) {
                Object[] candidate = tryLegacyPitch(yaw, down, targetPos, preferredSupportPos, deadlineMs);
                if (candidate != null) return candidate;
            }
        }
        return null;
    }

    private Object[] tryLegacyPitch(float yaw, float pitch, int[] targetPos, int[] preferredSupportPos, long deadlineMs) {
        if (System.currentTimeMillis() >= deadlineMs) return null;
        Object[] traced = rayCast(yaw, pitch);
        if (traced == null) return null;
        int[] supportPos = (int[]) traced[0];
        int face = (Integer) traced[1];
        if (preferredSupportPos != null && !posEquals(supportPos, preferredSupportPos)) return null;
        if (face == 0) return null;
        if (isReplaceable(supportPos[0], supportPos[1], supportPos[2])) return null;
        int[] placedPos = offsetPos(supportPos, face);
        if (!posEquals(placedPos, targetPos)) return null;
        return new Object[]{Math.min(pitch, 89.0f), supportPos, face, (Vec3) traced[2], placedPos};
    }

    private List<int[]> getMessageStyleBelowTargets(LocalPlayer player) {
        double[] offsets = {0.0, 0.29, -0.29};
        Vec3 pos = player.position();
        int maxY = floor(pos.y) - 1;
        int minY = floor(pos.y) - 2;
        List<int[]> targets = new ArrayList<>();
        for (int targetY = maxY; targetY >= minY; targetY--) {
            for (double xOffset : offsets) {
                for (double zOffset : offsets) {
                    targets.add(new int[]{floor(pos.x + xOffset), targetY, floor(pos.z + zOffset)});
                }
            }
        }
        return targets;
    }

    // ─── Target availability ────────────────────────────────────────────────
    private boolean isPlacementTargetAvailable(LocalPlayer player, int[] pos) {
        return isBasePlacementTargetAvailable(player, pos) && isStrictOneBelowPlayer(player, pos);
    }

    private boolean isBasePlacementTargetAvailable(LocalPlayer player, int[] pos) {
        return pos != null
                && isStraightTellyTarget(pos)
                && !isRejectedTarget(pos)
                && !doesPlacementIntersectPlayer(player, pos)
                && isReplaceable(pos[0], pos[1], pos[2]);
    }

    private boolean doesPlacementIntersectPlayer(LocalPlayer player, int[] placePos) {
        if (placePos == null) return false;
        if (isInsideAnyPlayerPositionCell(player, placePos)) return true;
        Vec3 pos = player.position();
        double half = player.getBbWidth() / 2.0;
        double height = player.getBbHeight();
        if (boxIntersectsBlock(pos.x - half, pos.y, pos.z - half, pos.x + half, pos.y + height, pos.z + half, placePos)) return true;
        if (isBlockPosInsideBounds(placePos, pos.x - half, pos.y, pos.z - half, pos.x + half, pos.y + height, pos.z + half)) return true;
        return false;
    }

    private boolean boxIntersectsBlock(double minX, double minY, double minZ, double maxX, double maxY, double maxZ, int[] pos) {
        return maxX > pos[0] && minX < pos[0] + 1.0 && maxY > pos[1] && minY < pos[1] + 1.0 && maxZ > pos[2] && minZ < pos[2] + 1.0;
    }

    private boolean isBlockPosInsideBounds(int[] pos, double minX, double minY, double minZ, double maxX, double maxY, double maxZ) {
        int bMinX = floor(minX + 1.0E-4);
        int bMaxX = floor(maxX - 1.0E-4);
        if (pos[0] < bMinX || pos[0] > bMaxX) return false;
        int bMinZ = floor(minZ + 1.0E-4);
        int bMaxZ = floor(maxZ - 1.0E-4);
        if (pos[2] < bMinZ || pos[2] > bMaxZ) return false;
        int bMinY = floor(minY + 1.0E-4);
        int bMaxY = floor(maxY - 1.0E-4);
        return pos[1] >= bMinY && pos[1] <= bMaxY;
    }

    private boolean isInsideAnyPlayerPositionCell(LocalPlayer player, int[] placePos) {
        Vec3 pos = player.position();
        return isInsidePlayerPositionCell(placePos, pos.x, pos.y, pos.z);
    }

    private boolean isInsidePlayerPositionCell(int[] placePos, double x, double y, double z) {
        int playerX = floor(x);
        int playerY = floor(y);
        int playerZ = floor(z);
        return placePos[0] == playerX && placePos[2] == playerZ && (placePos[1] == playerY || placePos[1] == playerY + 1);
    }

    private boolean isStrictOneBelowPlayer(LocalPlayer player, int[] pos) {
        if (pos == null) return false;
        int targetY = pos[1];
        int currentY = getCurrentBelowTargetY(player);
        if (targetY == currentY) return true;
        if (targetY == getStrictBelowTargetY(player)) return true;
        int previousY = getPreviousBelowTargetY(player);
        if (previousY != Integer.MIN_VALUE && targetY == previousY) return true;
        return isStraightAscendingContext(player) && targetY == currentY + 1;
    }

    // ─── Y-level helpers ────────────────────────────────────────────────────
    private double getStableBelowReferenceY(LocalPlayer player) {
        Vec3 pos = player.position();
        double referenceY = pos.y;
        Vec3 motion = player.getDeltaMovement();
        if (!player.onGround() && motion.y > -0.12 && motion.y <= 0.0) {
            referenceY = Math.max(referenceY, player.yOld);
        }
        return referenceY;
    }

    private int getStrictBelowTargetY(LocalPlayer player) {
        if (isDiagonalMovementContext(player)) return getCurrentBelowTargetY(player);
        double projectedY = getStableBelowReferenceY(player);
        Vec3 motion = player.getDeltaMovement();
        if (!player.onGround() && motion.y < -0.12) {
            projectedY = player.position().y + motion.y * 0.75;
        }
        return floor(projectedY) - 1;
    }

    private int getCurrentBelowTargetY(LocalPlayer player) {
        return floor(getStableBelowReferenceY(player)) - 1;
    }

    private int getPreviousBelowTargetY(LocalPlayer player) {
        return floor(player.yOld) - 1;
    }

    private boolean isStraightAscendingContext(LocalPlayer player) {
        if (getConditionModeCheck(player) != 1) return false;
        Vec3 motion = player.getDeltaMovement();
        return motion.y > 0.0 || player.position().y > player.yOld + 1.0E-4;
    }

    // ─── Mode detection ─────────────────────────────────────────────────────
    private int getDetectedModeCheck(LocalPlayer player) {
        float forwardInput = Math.abs(currentForward);
        float strafeInput = Math.abs(currentStrafe);
        if (forwardInput >= 0.08f || strafeInput >= 0.08f) {
            return (forwardInput >= 0.08f && strafeInput >= 0.08f) ? 1 : 2;
        }
        double[] direction = getMotionDirectionComponents(player);
        if (direction == null) return 1;
        double angleDeg = Math.toDegrees(Math.atan2(direction[1], direction[0]));
        double norm90 = (angleDeg % 90.0 + 90.0) % 90.0;
        return Math.abs(norm90 - 45.0) <= 18.0 ? 2 : 1;
    }

    private double[] getMotionDirectionComponents(LocalPlayer player) {
        Vec3 pos = player.position();
        Vec3 last = new Vec3(player.xOld, player.yOld, player.zOld);
        double dirX = pos.x - last.x;
        double dirZ = pos.z - last.z;
        double speedSq = dirX * dirX + dirZ * dirZ;
        if (speedSq < 1.0E-4) {
            Vec3 motion = player.getDeltaMovement();
            dirX = motion.x;
            dirZ = motion.z;
            speedSq = dirX * dirX + dirZ * dirZ;
        }
        if (speedSq < 1.0E-4) return null;
        return new double[]{dirX, dirZ};
    }

    private double[] getInputDirectionComponents(float referenceYaw) {
        float forwardInput = currentForward;
        float strafeInput = currentStrafe;
        if (Math.abs(forwardInput) < 0.08f && Math.abs(strafeInput) < 0.08f) return null;
        double yawRadians = Math.toRadians(referenceYaw);
        double sinYaw = Math.sin(yawRadians);
        double cosYaw = Math.cos(yawRadians);
        double dirX = forwardInput * -sinYaw + strafeInput * cosYaw;
        double dirZ = forwardInput * cosYaw + strafeInput * sinYaw;
        if (dirX * dirX + dirZ * dirZ < 1.0E-4) return null;
        return new double[]{dirX, dirZ};
    }

    private int getStraightForwardFacing(LocalPlayer player, float fallbackYaw) {
        double[] direction = getInputDirectionComponents(fallbackYaw);
        if (direction == null) direction = getMotionDirectionComponents(player);
        if (direction == null) return facingFromYaw(fallbackYaw);
        float directionYaw = (float) (Math.toDegrees(Math.atan2(direction[1], direction[0])) - 90.0);
        return facingFromYaw(directionYaw);
    }

    private int getConditionModeCheck(LocalPlayer player) {
        if (forcedModeCheck != 0) return forcedModeCheck;
        return getDetectedModeCheck(player);
    }

    private boolean isDiagonalMovementContext(LocalPlayer player) {
        return getConditionModeCheck(player) == 2;
    }

    // ─── Cursor targeting ───────────────────────────────────────────────────
    private int[] getCursorPlacedTargetFromRay(float yaw, float pitch, int targetY) {
        Object[] traced = rayCast(yaw, pitch);
        if (traced == null) return null;
        int[] offsetTarget = offsetPos((int[]) traced[0], (Integer) traced[1]);
        if (offsetTarget[1] != targetY) return null;
        return offsetTarget;
    }

    private int[] getCursorStartTargetAtY(LocalPlayer player, float fallbackYaw, float fallbackPitch, int targetY) {
        Vec3 cursorPoint = getCursorIntersectionAtY(player, targetY);
        Vec3 lookVec = getCursorLookVec(player);
        if (cursorPoint == null || lookVec == null) return null;
        double startX = cursorPoint.x - lookVec.x * 0.03;
        double startZ = cursorPoint.z - lookVec.z * 0.03;
        return new int[]{floor(startX), targetY, floor(startZ)};
    }

    private int[] getCursorTargetAtY(LocalPlayer player, float fallbackYaw, float fallbackPitch, int targetY) {
        Vec3 cursorPoint = getCursorIntersectionAtY(player, targetY);
        if (cursorPoint == null) return null;
        return new int[]{floor(cursorPoint.x), targetY, floor(cursorPoint.z)};
    }

    private Vec3 getCursorIntersectionAtY(LocalPlayer player, int targetY) {
        Vec3 eyes = getEyes(player);
        Vec3 lookVec = getCursorLookVec(player);
        if (lookVec == null || Math.abs(lookVec.y) < 1.0E-4) return null;
        double t = (targetY - eyes.y) / lookVec.y;
        if (t <= 0.0) return null;
        return new Vec3(eyes.x + lookVec.x * t, targetY + 0.5, eyes.z + lookVec.z * t);
    }

    private Vec3 getCursorLookVec(LocalPlayer player) {
        return getLookVec(player.getYRot(), player.getXRot());
    }

    private Vec3 getLookVec(float yaw, float pitch) {
        double yawRad = Math.toRadians(yaw);
        double pitchRad = Math.toRadians(pitch);
        double cosPitch = Math.cos(pitchRad);
        return new Vec3(-Math.sin(yawRad) * cosPitch, -Math.sin(pitchRad), Math.cos(yawRad) * cosPitch);
    }

    private boolean isCursorInsideTargetAtY(LocalPlayer player, int[] targetPos, float yaw, float pitch, int targetY) {
        if (targetPos == null || targetPos[1] != targetY) return false;
        Vec3 cursorPoint = getCursorIntersectionAtY(player, targetY);
        if (cursorPoint == null) return false;
        double x = cursorPoint.x;
        double z = cursorPoint.z;
        return x >= targetPos[0] - 1.0E-6 && x <= targetPos[0] + 1.0 + 1.0E-6 && z >= targetPos[2] - 1.0E-6 && z <= targetPos[2] + 1.0 + 1.0E-6;
    }

    // ─── Block access ───────────────────────────────────────────────────────
    private boolean isSupportAvailable(int x, int y, int z) {
        return !isReplaceable(x, y, z);
    }

    private boolean isRejectedTarget(int[] pos) {
        Integer rejectedAtTick = rejectedTargets.get(posKey(pos));
        if (rejectedAtTick == null) return false;
        return currentClientTick - rejectedAtTick <= 4;
    }

    private void markRejectedTarget(int[] pos) {
        if (pos == null) return;
        rejectedTargets.put(posKey(pos), currentClientTick);
    }

    private void pruneRejectedTargets() {
        if (rejectedTargets.isEmpty()) return;
        Iterator<Map.Entry<String, Integer>> iterator = rejectedTargets.entrySet().iterator();
        while (iterator.hasNext()) {
            Map.Entry<String, Integer> entry = iterator.next();
            if (currentClientTick - entry.getValue() > 4) {
                iterator.remove();
            }
        }
    }

    private boolean isBlockBelowPlayerReplaceable(LocalPlayer player) {
        Vec3 pos = player.position();
        return isReplaceable(floor(pos.x), floor(pos.y) - 1, floor(pos.z));
    }

    private boolean placedInCurrentWindow() {
        return totalC08Counter > c08CounterAtTickBoundary;
    }

    private boolean areAutoPlaceConditionsMet(LocalPlayer player) {
        if (!tellyAutoPlaceWindow) return false;
        return isUsableBlockStack(player.getMainHandItem());
    }

    private boolean isAutoPlaceActiveWindow(LocalPlayer player) {
        if (mc.gui.screen() != null) return false;
        if (!areAutoPlaceConditionsMet(player)) return false;
        return isUsableBlockStack(player.getMainHandItem());
    }

    private boolean isUsableBlockStack(ItemStack stack) {
        if (stack == null || !(stack.getItem() instanceof BlockItem) || stack.isEmpty()) return false;
        String name = stack.getItem().getDescriptionId().toLowerCase();
        for (String bad : UNPLACEABLE_EXACT) {
            if (name.contains(bad)) return false;
        }
        for (String bad : UNPLACEABLE_CONTAINS) {
            if (name.contains(bad)) return false;
        }
        return true;
    }

    private boolean isHoldingBlock(LocalPlayer player) {
        return player != null && isUsableBlockStack(player.getMainHandItem());
    }

    // ─── Raycast ────────────────────────────────────────────────────────────
    private Object[] rayCast(float yaw, float pitch) {
        BlockHitResult hit = raycastBlock(reach(), yaw, pitch);
        if (hit == null || hit.getType() == HitResult.Type.MISS) return null;
        int face = directionToInt(hit.getDirection());
        if (face < 0 || face == 0) return null;
        int[] supportPos = new int[]{hit.getBlockPos().getX(), hit.getBlockPos().getY(), hit.getBlockPos().getZ()};
        Vec3 hitAbs = hit.getLocation();
        return new Object[]{supportPos, face, hitAbs};
    }

    private BlockHitResult raycastBlock(double reach, float yaw, float pitch) {
        if (mc.player == null || mc.level == null) return null;
        Vec3 eyes = getEyes(mc.player);
        Vec3 lookVec = getLookVec(yaw, pitch);
        Vec3 end = eyes.add(lookVec.x * reach, lookVec.y * reach, lookVec.z * reach);
        return mc.level.clip(new net.minecraft.world.level.ClipContext(eyes, end,
                net.minecraft.world.level.ClipContext.Block.COLLIDER,
                net.minecraft.world.level.ClipContext.Fluid.NONE, mc.player));
    }

    private BlockHitResult raycastBlock(double reach) {
        if (mc.player == null || mc.level == null) return null;
        Vec3 eyes = getEyes(mc.player);
        Vec3 lookVec = getLookVec(mc.player.getYRot(), mc.player.getXRot());
        Vec3 end = eyes.add(lookVec.x * reach, lookVec.y * reach, lookVec.z * reach);
        return mc.level.clip(new net.minecraft.world.level.ClipContext(eyes, end,
                net.minecraft.world.level.ClipContext.Block.COLLIDER,
                net.minecraft.world.level.ClipContext.Fluid.NONE, mc.player));
    }

    private String blockNameAt(int x, int y, int z) {
        if (mc.level == null) return "air";
        BlockState state = mc.level.getBlockState(new BlockPos(x, y, z));
        return state.getBlock().getDescriptionId().toLowerCase();
    }

    private boolean isReplaceable(int x, int y, int z) {
        return isReplaceableName(blockNameAt(x, y, z), false);
    }

    private boolean isReplaceableName(String name, boolean airOnly) {
        if (airOnly) return name.equals("air") || name.equals("cave_air") || name.equals("void_air");
        for (String replaceable : REPLACEABLE_BLOCKS) {
            if (name.contains(replaceable)) return true;
        }
        return false;
    }

    private double reach() {
        return mc.player != null && mc.player.isCreative() ? 5.0 : 4.5;
    }

    // ─── Utility methods ────────────────────────────────────────────────────
    private Vec3 getEyes(LocalPlayer player) {
        Vec3 pos = player.position();
        return new Vec3(pos.x, pos.y + player.getEyeHeight(), pos.z);
    }

    private boolean isWithinReach(LocalPlayer player, int[] pos) {
        if (pos == null) return false;
        Vec3 eyes = getEyes(player);
        double cx = Math.max(pos[0], Math.min(eyes.x, pos[0] + 1.0));
        double cy = Math.max(pos[1], Math.min(eyes.y, pos[1] + 1.0));
        double cz = Math.max(pos[2], Math.min(eyes.z, pos[2] + 1.0));
        double dx = eyes.x - cx;
        double dy = eyes.y - cy;
        double dz = eyes.z - cz;
        return dx * dx + dy * dy + dz * dz <= reach() * reach();
    }

    private boolean hasValidLastPlacedPos(LocalPlayer player) {
        if (lastPlacedPos == null) return false;
        return isWithinReach(player, lastPlacedPos) && isSupportAvailable(lastPlacedPos[0], lastPlacedPos[1], lastPlacedPos[2]);
    }

    private boolean hasValidLastSupportFace(LocalPlayer player) {
        if (lastSupportPos == null || lastSupportFace < 0) return false;
        return isWithinReach(player, lastSupportPos) && isSupportAvailable(lastSupportPos[0], lastSupportPos[1], lastSupportPos[2]);
    }

    private boolean isNearStraightSupportEdge(LocalPlayer player) {
        if (lastSupportPos == null || lastSupportFace < 2) return false;
        Vec3 pos = player.position();
        double localX = pos.x - lastSupportPos[0];
        double localZ = pos.z - lastSupportPos[2];
        if (isPastStraightSupportEdgeThreshold(lastSupportFace, localX, localZ)) return true;
        Vec3 motion = player.getDeltaMovement();
        if (motion.x * motion.x + motion.z * motion.z < 1.0E-4) return false;
        if (!isMovingTowardStraightSupportEdge(lastSupportFace, motion.x, motion.z)) return false;
        return isPastStraightSupportEdgeThreshold(lastSupportFace, localX + motion.x * 1.45, localZ + motion.z * 1.45);
    }

    private boolean isPastStraightSupportEdgeThreshold(int supportFace, double localX, double localZ) {
        if (supportFace == 5) return localX >= 0.52;
        if (supportFace == 4) return localX <= 0.48;
        if (supportFace == 3) return localZ >= 0.52;
        if (supportFace == 2) return localZ <= 0.48;
        return false;
    }

    private boolean isMovingTowardStraightSupportEdge(int supportFace, double motionX, double motionZ) {
        if (supportFace == 5) return motionX > 0.0;
        if (supportFace == 4) return motionX < 0.0;
        if (supportFace == 3) return motionZ > 0.0;
        if (supportFace == 2) return motionZ < 0.0;
        return false;
    }

    private void resetControllerState() {
        currentClientTick = Integer.MIN_VALUE;
        placementEvaluationTick = Integer.MIN_VALUE;
        lastPlacementAttemptTick = Integer.MIN_VALUE;
        lastSuccessfulPlaceTick = Integer.MIN_VALUE;
        forceSuppressTick = Integer.MIN_VALUE;
        totalC08Counter = 0L;
        c08CounterAtTickBoundary = 0L;
        hasLastSentServerPos = false;
        clearCachedCandidate();
        lastPlacedPos = null;
        lastSupportPos = null;
        lastSupportFace = -1;
        cachedBelowTargets = null;
        cachedBelowTargetsTick = Integer.MIN_VALUE;
        rejectedTargets.clear();
        forcedModeCheck = 0;
        useSuppressed = false;
        silentPitchActive = false;
        placingViaModule = false;
        manualC08InWindow = false;
    }

    private void suppressUse() {
        mc.options.keyUse.setDown(false);
        useSuppressed = true;
    }

    private void restoreUseToPhysicalState() {
        mc.options.keyUse.setDown(running ? tellyAutoPlaceWindow : mc.mouseHandler.isRightPressed());
        useSuppressed = false;
    }

    private boolean shouldSuppressManualClicksThisTick() {
        if (mc.player == null || mc.gui.screen() != null) return false;
        return lastSuccessfulPlaceTick == currentClientTick || forceSuppressTick == currentClientTick;
    }

    private boolean shouldCancelAutoPlaceUseItem() {
        if (mc.player == null || mc.gui.screen() != null) return false;
        if (shouldSuppressManualClicksThisTick()) return true;
        return useSuppressed && silentPitchActive;
    }

    private boolean isDropProtected() {
        return running || (armed && !running && activatePromptAt != 0L);
    }

    private boolean isInGameContext() {
        return mc.player != null && mc.gui.screen() == null;
    }

    // ─── Primitive type helpers ─────────────────────────────────────────────
    private float candidatePitch(Object[] candidate) {
        return clampFloat((Float) candidate[0], -90.0f, 90.0f);
    }

    private int[] candidateSupportPos(Object[] candidate) {
        return (int[]) candidate[1];
    }

    private int candidateFace(Object[] candidate) {
        return (Integer) candidate[2];
    }

    private Vec3 candidateHitVec(Object[] candidate) {
        return (Vec3) candidate[3];
    }

    private int[] candidatePlacedPos(Object[] candidate) {
        return (int[]) candidate[4];
    }

    private float sanitizePitch(float pitch, float fallbackPitch) {
        float safeFallback = clampFloat(Float.isNaN(fallbackPitch) ? 0.0f : fallbackPitch, -90.0f, 90.0f);
        if (Float.isNaN(pitch) || Float.isInfinite(pitch)) return safeFallback;
        return clampFloat(pitch, -90.0f, 90.0f);
    }

    private int floor(double value) {
        int i = (int) value;
        return value < i ? i - 1 : i;
    }

    private float clampFloat(float value, float min, float max) {
        return value < min ? min : (value > max ? max : value);
    }

    private float clamp(float value, float min, float max) {
        return value < min ? min : (value > max ? max : value);
    }

    private double clamp(double value, double min, double max) {
        return value < min ? min : (value > max ? max : value);
    }

    private float tellyWrapAngle(float angle) {
        while (angle <= -180.0f) angle += 360.0f;
        while (angle > 180.0f) angle -= 360.0f;
        return angle;
    }

    private float wrapAngle(float angle) {
        angle = angle % 360f;
        if (angle >= 180f) angle -= 360f;
        if (angle < -180f) angle += 360f;
        return angle;
    }

    private boolean posEquals(int[] a, int[] b) {
        return a != null && b != null && a[0] == b[0] && a[1] == b[1] && a[2] == b[2];
    }

    private String posKey(int[] pos) {
        return pos[0] + "," + pos[1] + "," + pos[2];
    }

    private int[] posFromVec(Vec3 vec) {
        return new int[]{floor(vec.x), floor(vec.y), floor(vec.z)};
    }

    private int[] offsetPos(int[] pos, int face) {
        if (face == 0) return new int[]{pos[0], pos[1] - 1, pos[2]};
        if (face == 1) return new int[]{pos[0], pos[1] + 1, pos[2]};
        if (face == 2) return new int[]{pos[0], pos[1], pos[2] - 1};
        if (face == 3) return new int[]{pos[0], pos[1], pos[2] + 1};
        if (face == 4) return new int[]{pos[0] - 1, pos[1], pos[2]};
        return new int[]{pos[0] + 1, pos[1], pos[2]};
    }

    private int opposite(int face) {
        if (face == 0) return 1;
        if (face == 1) return 0;
        if (face == 2) return 3;
        if (face == 3) return 2;
        if (face == 4) return 5;
        return 4;
    }

    private int rotateY(int face) {
        if (face == 2) return 5;
        if (face == 5) return 3;
        if (face == 3) return 4;
        if (face == 4) return 2;
        return face;
    }

    private int rotateYCCW(int face) {
        if (face == 2) return 4;
        if (face == 4) return 3;
        if (face == 3) return 5;
        if (face == 5) return 2;
        return face;
    }

    private int facingFromYaw(float yaw) {
        int index = floor(yaw / 90.0 + 0.5) & 3;
        if (index == 0) return 3;
        if (index == 1) return 4;
        if (index == 2) return 2;
        return 5;
    }

    private int directionToInt(Direction dir) {
        return switch (dir) {
            case DOWN -> 0;
            case UP -> 1;
            case NORTH -> 2;
            case SOUTH -> 3;
            case WEST -> 4;
            case EAST -> 5;
        };
    }

    private Direction intToDirection(int face) {
        return switch (face) {
            case 0 -> Direction.DOWN;
            case 1 -> Direction.UP;
            case 2 -> Direction.NORTH;
            case 3 -> Direction.SOUTH;
            case 4 -> Direction.WEST;
            case 5 -> Direction.EAST;
            default -> Direction.NORTH;
        };
    }

    private void printModuleStatus(String message) {
        if (printStatus.getValue() && mc.player != null) {
            mc.player.sendSystemMessage(Component.literal("§bTelly §7| " + message));
        }
    }
}
