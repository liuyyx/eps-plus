package com.github.epsilon.modules.impl.movement;

import com.github.epsilon.events.bus.EventHandler;
import com.github.epsilon.events.bus.EventPriority;
import com.github.epsilon.events.impl.KeyboardInputEvent;
import com.github.epsilon.events.impl.MousePressEvent;
import com.github.epsilon.events.impl.PlayerTickEvent;
import com.github.epsilon.events.impl.Render2DEvent;
import com.github.epsilon.events.impl.Render3DEvent;
import com.github.epsilon.events.impl.SendPositionEvent;
import com.github.epsilon.graphics.renderers.TextRenderer;
import com.github.epsilon.graphics.schedulers.render3d.Render3DScheduler;
import com.github.epsilon.interfaces.KeyMappingAccessor;
import com.github.epsilon.interfaces.MinecraftAccessor;
import com.github.epsilon.modules.Category;
import com.github.epsilon.modules.Module;
import com.github.epsilon.settings.impl.BoolSetting;
import com.github.epsilon.settings.impl.DoubleSetting;
import com.github.epsilon.settings.impl.EnumSetting;
import com.github.epsilon.utils.player.ChatUtils;
import com.github.epsilon.utils.rotation.RotationUtils;
import com.google.common.base.Suppliers;
import net.minecraft.ChatFormatting;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;
import org.lwjgl.glfw.GLFW;

import java.awt.Color;
import java.util.Locale;
import java.util.function.Supplier;

import static com.github.epsilon.Constants.mc;

/**
 * 逐函数移植自 Raven 脚本 {@code Normalbridge.java}（1.8.9），原作者 Monster_Sang。
 * Bilibili: https://space.bilibili.com/1581282621
 *
 * <p>这是独立脚本模块，不是 {@link Scaffold} 或 RavenB4Scaffold 的模式。原脚本的
 * {@code onPreUpdate/onPostPlayerInput/onPreMotion/onMouse/onRenderTick/onRenderWorld}
 * 分别映射到 Epsilon 对应事件；除本机实测要求的激活与持续放置修复外，
 * 判定顺序、阈值、状态机与时间常量保持不变。
 *
 * <p>脚本会直接驱动玩家真实视角，并通过原版 {@code Minecraft.startUseItem()} 放置；
 * 不要改成 RotationManager 静默角或直接发放置包。唯一量化适配是使用本机实时
 * {@link RotationUtils#mouseQuantum()}，替换源客户端的固定灵敏度网格。
 */
public final class GreatWall extends Module {

    public static final GreatWall INSTANCE = new GreatWall();

    private GreatWall() {
        super("Great Wall", Category.MOVEMENT);
    }

    @Override
    public String getInfo() {
        return "Made By Monster_Sang(AI)";
    }

    // ===== 脚本 onLoad：设置（声明顺序与原脚本一致）=====

    private enum BridgeMode {
        GreatWall, Sy, Clutch
    }

    private final EnumSetting<BridgeMode> mode = enumSetting("Mode", BridgeMode.GreatWall);
    private final BoolSetting extraClicksSetting = boolSetting("Extra Clicks", false);
    private final BoolSetting autoSwitchSetting = boolSetting("Auto Switch", true);
    private final BoolSetting showCountSetting = boolSetting("Show Count", true);
    private final BoolSetting hideOnLookSetting = boolSetting("Hide On Look", true);
    private final DoubleSetting activationTimeSetting = doubleSetting("Activation Time", 0.5, 0.0, 1.0, 0.1);
    private final BoolSetting debugSetting = boolSetting("Debug", false);

    private final Supplier<TextRenderer> textRenderer = Suppliers.memoize(() -> TextRenderer.create(64 * 1024));

    // ===== 脚本 24-147：顶层状态与常量 =====

    private boolean bridging = false;

    private boolean rotationActive = false;
    private long rotationStartedAt = 0;
    private long rotationDuration = 300;
    private float rotationStartYaw = 0, rotationStartPitch = 0;
    private float rotationTargetYaw = 0, rotationTargetPitch = 0;

    private static final int PHASE_IDLE = 0;
    private static final int PHASE_WALK = 1;
    private static final int PHASE_BRIDGE = 2;
    private int phase = PHASE_IDLE;
    private long phaseStart = 0;

    private boolean wasOnGround = false;
    private int placeTick = 0;
    private int placeCount = 0;
    private static final long RELEASE_S_MS = 70;
    private long releaseSUntil = 0;
    private long useStartAt = 0;
    private boolean placing = false;
    private long noPromptUntil = 0;

    private int bridgeBlockStartTotal = 0;
    private int bridgePlacedMax = 0;
    private int bridgeCount = 0;
    private String countLabel = null;
    private int countLabelValue = -1;
    private BridgeMode countLabelMode = null;
    private float countLabelWidth = 0;
    private double lastGroundY = 0;

    private long activateAt = 0;
    private float promptAlpha = 0;
    private long promptFadeLastAt = 0;
    private int promptRgb = 0xFF5555;
    private int[] aimPos = null;
    private int aimFace = -1;

    /** 判定与绘制共用的面内激活区域；不能放大成整面来掩盖坐标错位。 */
    private static final double ACT_ACROSS_MIN = 0.35;
    private static final double ACT_ACROSS_MAX = 0.65;
    private static final double ACT_HEIGHT_MIN = 0.35;
    private static final double ACT_HEIGHT_MAX = 0.65;
    private static final float ACTIVATION_PITCH_TOLERANCE = 6.0f;

    private boolean debug = false;
    private boolean hideOnLook = true;
    private boolean autoSwitch = true;
    private boolean showCount = true;
    private boolean extraClicks = false;
    private BridgeMode bridgeMode = BridgeMode.GreatWall;

    private static final float GREATWALL_PITCH = 67.0f;
    private static final float SY_BASE_PITCH = 68.0f;
    private static final int SY_JUMP_TICKS = 11;
    private static final int SY_GIVEUP_TICKS = 3;
    private static final double SY_SPEED_GUESS = 0.17;
    private static final double SY_LAND_MAX_INTO = 0.28;
    private static final double SY_RUNWAY_INTO = 0.15;
    private static final int SY_HOLD_MAX_TICKS = 2;
    private static final double SY_DRIFT_HI = 0.06;
    private static final double SY_DRIFT_LO = -0.06;
    private static final float SY_DRIFT_MIN_DEG = 32.0f;
    private static final float SY_DRIFT_MAX_DEG = 48.0f;
    private static final long SY_DRIFT_MS = 50;
    private static final long SY_FLICK_MS = 50;
    private static final float SY_AXIS_TOL_DEG = 2.0f;
    private static final int SY_CYCLE_BLOCKS = 2;
    private static final long SY_CYCLE_MS = 550;
    private static final int SY_USE_HOLD_PERIOD_TICKS = 4;
    private static final long CLUTCH_PAIR_GAP_MS = 100;
    private static final long CLUTCH_PAIR_WAIT_MS = 250;
    private static final long CLUTCH_PAIR_REST_MS = 350;
    private static final long CLUTCH_TAKEOFF_RELEASE_MS = 60;
    private static final long CLUTCH_VERIFY_MS = 50;
    private static final int CLUTCH_RETRY_MAX = 2;
    private static final long CLUTCH_EXTRA_GAP_MS = 30;
    private static final int CLUTCH_PHASE_EVERY = 9;
    private static final long CLUTCH_PHASE_CORRECT_MS = 20;
    private static final int SY_STATE_TURN = 1;
    private static final int SY_STATE_DRIFT = 2;
    private static final int SY_STATE_SETTLE = 3;
    private int syState = SY_STATE_SETTLE;
    private long syStateAt = 0;
    private long sySwingAt = 0;
    private int syCyclePlaced = 0;
    private float syDriftAmp = 0;
    private int syUseTick = 0;
    private long syClickAt = 0;
    private boolean syGround = false;
    private Vec3 syLastCountTarget = null;
    private Vec3 syPendingFill = null;
    private long syPendingFillAt = 0;
    private boolean syStartupClicks = true;
    private float syBaseYaw = 0;
    private Vec3 syPrevPos = null;
    private int syGroundTicks = 0;
    private double syDbgSpeed = 0;
    private double syDbgLanding = 0;
    private int syAirHoldTicks = 0;
    private int syAirHoldLeft = 0;
    private double syLastLandingFrac = -1;
    private long clutchSecondAt = 0;
    private long clutchNextAt = 0;
    private long clutchLastPlaceAt = 0;
    private int clutchJumpCount = 0;
    private int clutchRetryCount = 0;
    private double clutchMmAvg = 0;
    private double clutchMmX = 0;
    private double clutchMmZ = 0;
    private boolean clutchMmInit = false;
    private boolean clutchFirstTakeoff = false;
    private double startY = 0;
    private double bridgeStartX = 0;
    private long bridgeStartMs = 0;
    private long dbgTickAt = -1;
    private int dbgTickLines = 0;
    private String dbgBuf = "";
    private int dbgBufLines = 0;
    private long dbgBufAt = 0;
    private long dbgFlushAt = 0;
    private long dbgRecAt = -1;
    private double lastActX = 0, lastActY = 0, lastActZ = 0;
    private boolean lastActInit = false;
    private float lastActYaw = 0, lastActPitch = 0;
    private boolean lastActLookInit = false;

    /** 本 tick 正在派发的输入事件，对应脚本 client.setForward/setStrafe/... 的写入目标。 */
    private KeyboardInputEvent activeInputEvent = null;

    /** 自救搭（Clutch）的点击硬节流：26.2 startUseItem 不检查 rightClickDelay。 */
    private long lastRightClickAt = 0;

    /** 本刻脚本射线命中的面，用于长城限定桥面/垛口两层。 */
    private String placementFace = null;

    // ===== 生命周期与事件接线 =====

    @Override
    protected void onEnable() {
        refreshSettings();
        stopBridge();
    }

    @Override
    protected void onDisable() {
        stopBridge();
    }

    /** 脚本 {@code m424b6f29722c0f()}：onPreUpdate。 */
    @EventHandler
    private void onPreUpdate(PlayerTickEvent.Pre event) {
        if (nullCheck()) return;
        LocalPlayer player = mc.player;
        refreshSettings();
        dbgFlushStale();

        if (!bridging) {
            updateActivation();
            if (promptReady() && physicalRightMouseDown()) startBridge();
            return;
        }

        if (!physicalRightMouseDown()) {
            debugForce("RIGHT-RELEASE", "&c");
            stopBridge();
            return;
        }

        double py = player.position().y;
        if (player.onGround()) lastGroundY = py;
        if (py < lastGroundY - 1.5) {
            debugForce("FELL", "&e");
            stopBridge();
            return;
        }

        if (!ensureBlock()) {
            debugForce("NOBLOCK", "&e");
            stopBridge();
            return;
        }

        if (showCount) {
            int remaining = totalBlocks();
            if (remaining > 0) {
                int placed = bridgeBlockStartTotal - remaining;
                if (placed > bridgePlacedMax && placed - bridgePlacedMax <= 8) bridgePlacedMax = placed;
            }
            bridgeCount = bridgeMode == BridgeMode.GreatWall ? bridgePlacedMax / 3 : bridgePlacedMax / 2;
        }

        dbgRec(player);

        if (phase == PHASE_WALK) {
            if (isAtEdge()) {
                enterPhase(PHASE_BRIDGE);
                debugLog("EDGE");
            } else if (clientTime() - phaseStart >= 900) {
                enterPhase(PHASE_BRIDGE);
                debugWarn("EDGE timeout");
            }
        } else if (phase == PHASE_BRIDGE) {
            if (bridgeMode == BridgeMode.Sy) {
                updateSyCycle(player);
            } else {
                boolean grounded = player.onGround();
                if (!wasOnGround && grounded) {
                    debugLog("LAND dx" + r2(player.position().x - bridgeStartX));
                } else if (wasOnGround && !grounded && bridgeMode == BridgeMode.Clutch) {
                    Vec3 tp = player.position();
                    if (clutchMmInit) {
                        double mm = Math.hypot(tp.x - clutchMmX, tp.z - clutchMmZ);
                        clutchMmAvg = clutchMmAvg <= 0.0 ? mm : clutchMmAvg * 0.7 + mm * 0.3;
                    }
                    clutchMmInit = true;
                    clutchMmX = tp.x;
                    clutchMmZ = tp.z;
                    clutchRetryCount = 0;
                    if (clutchFirstTakeoff) {
                        clutchFirstTakeoff = false;
                    } else {
                        clutchJumpCount++;
                        long rel = clutchJumpCount % CLUTCH_PHASE_EVERY == 0
                                ? CLUTCH_PHASE_CORRECT_MS : CLUTCH_TAKEOFF_RELEASE_MS;
                        releaseSUntil = clientTime() + rel;
                        debugLog("J mm" + r2(clutchMmAvg) + " w" + (isPressed("forward") ? 1 : 0) + " s" + rel);
                    }
                }
                wasOnGround = grounded;
            }
        }
    }

    /** 脚本 {@code m01359d34fb99f5()}：onPostPlayerInput。 */
    @EventHandler(priority = EventPriority.HIGHEST)
    private void onPostPlayerInput(KeyboardInputEvent event) {
        if (nullCheck() || !bridging) return;
        activeInputEvent = event;

        if (phase == PHASE_WALK) {
            applyMovement(-1.0f, 0.0f, false);
            return;
        }

        if (bridgeMode == BridgeMode.Sy) {
            LocalPlayer syP = mc.player;
            if (syStartupClicks) {
                if (clientTime() >= useStartAt) {
                    if (!placing) {
                        placing = true;
                        placeTick = 0;
                        placeBlock();
                    } else {
                        placeTick++;
                        if (placeTick >= 4) {
                            placeBlock();
                            placeTick = 0;
                        }
                    }
                    verifyFill();
                }
            } else {
                syUseTick++;
                if (syP != null && clientTime() - syClickAt >= 100 && syRescue(syP)) {
                    syUseTick = SY_USE_HOLD_PERIOD_TICKS;
                }
                if (syP != null && clientTime() >= useStartAt && syUseTick >= SY_USE_HOLD_PERIOD_TICKS) {
                    Vec3 target = raycastTarget();
                    if (target != null && onBridgeLine(target, syP)) {
                        syUseTick = 0;
                        syClickAt = clientTime();
                        syPendingFill = target;
                        syPendingFillAt = clientTime();
                        rightClick();
                    }
                }
                verifyFill();
            }
        } else if (bridgeMode == BridgeMode.Clutch) {
            long now = clientTime();
            if (now >= useStartAt) {
                boolean clicked = false;
                if (clutchSecondAt != 0) {
                    if (now >= clutchSecondAt && (now - clutchSecondAt >= CLUTCH_PAIR_WAIT_MS || clutchSideFill())) {
                        clutchSecondAt = 0;
                        clutchNextAt = now + CLUTCH_PAIR_REST_MS;
                        placeBlock();
                        clicked = true;
                    }
                } else if (now >= clutchNextAt && clutchSideFill()) {
                    placeBlock();
                    clutchSecondAt = now + CLUTCH_PAIR_GAP_MS;
                    clicked = true;
                }
                if (extraClicks && !clicked && now - clutchLastPlaceAt >= CLUTCH_EXTRA_GAP_MS) {
                    LocalPlayer clutchP = mc.player;
                    if (clutchP != null && !clutchP.onGround() && clutchSideFill()) placeBlock();
                }
                verifyFill();
            }
        } else if (clientTime() >= useStartAt) {
            if (!placing) {
                placing = true;
                placeTick = 0;
                placeBlock();
            } else {
                placeTick++;
                if (placeTick >= 4) {
                    placeBlock();
                    placeTick = 0;
                }
            }
        }

        if (bridgeMode == BridgeMode.Sy) {
            LocalPlayer syP = mc.player;
            boolean inSwing = syState == SY_STATE_DRIFT || syState == SY_STATE_TURN;
            float yawErr = syP == null ? 0.0f : wrapAngle(syP.getYRot() - syBaseYaw);
            if (yawErr < 0.0f) yawErr = -yawErr;
            boolean releaseS = inSwing && yawErr > SY_AXIS_TOL_DEG;
            boolean holdAir = false;
            boolean jump = false;
            if (syP != null) {
                Vec3 p = syP.position();
                double speed = syPrevPos == null ? SY_SPEED_GUESS : Math.hypot(p.x - syPrevPos.x, p.z - syPrevPos.z);
                syPrevPos = p;
                if (syP.onGround()) {
                    double dir = syAxisDir();
                    double rawInto = syAxisFrac(syP);
                    if (dir < 0.0) rawInto = 1.0 - rawInto;
                    if (syGroundTicks == 0) {
                        double rawF = syAxisFrac(syP);
                        if (syLastLandingFrac >= 0.0) {
                            double drift = rawF - syLastLandingFrac;
                            if (drift > 0.5) drift -= 1.0;
                            else if (drift < -0.5) drift += 1.0;
                            if (drift > SY_DRIFT_HI) {
                                if (syAirHoldTicks < SY_HOLD_MAX_TICKS) syAirHoldTicks++;
                            } else if (drift < SY_DRIFT_LO) {
                                if (syAirHoldTicks > 0) syAirHoldTicks--;
                            }
                        }
                        syLastLandingFrac = rawF;
                        syAirHoldLeft = 0;
                    }
                    double advance = speed * SY_JUMP_TICKS;
                    advance -= Math.floor(advance);
                    double landing = syAxisFrac(syP) + dir * advance;
                    landing -= Math.floor(landing);
                    double into = dir > 0.0 ? landing : 1.0 - landing;
                    syDbgSpeed = speed;
                    syDbgLanding = into;
                    syGroundTicks++;
                    if (syStartupClicks || into <= SY_LAND_MAX_INTO || rawInto > SY_RUNWAY_INTO
                            || syGroundTicks >= SY_GIVEUP_TICKS) {
                        jump = true;
                        syGroundTicks = 0;
                    }
                } else {
                    syGroundTicks = 0;
                    holdAir = syAirHoldLeft > 0;
                    if (holdAir) syAirHoldLeft--;
                }
            }
            applyMovement(releaseS || holdAir ? 0.0f : -1.0f, 0.0f, jump);
        } else {
            float forward = clientTime() < releaseSUntil ? 0.0f : -1.0f;
            applyMovement(forward, 0.0f, true);
        }
    }

    /** 脚本 {@code m08a5fa9257cbf3(PlayerState)}：onPreMotion。 */
    @EventHandler
    private void onPreMotion(SendPositionEvent event) {
        if (nullCheck() || !bridging) return;
        LocalPlayer player = mc.player;
        event.setYaw(player.getYRot());
        event.setPitch(player.getXRot());
    }

    /** 脚本 {@code mcabe0ae344b444(float)} 的旋转部分与 onRenderWorld。 */
    @EventHandler
    private void onRender3D(Render3DEvent event) {
        if (nullCheck()) return;
        if (bridging) {
            applySmoothedRotation();
            return;
        }
        updateActivatePromptFade();
        if (aimPos == null || aimFace < 0) return;
        drawActivationFace(aimPos, aimFace);
    }

    /** 脚本 onRenderTick 的 HUD 部分。 */
    @EventHandler
    private void onRender2D(Render2DEvent.Level event) {
        if (nullCheck()) return;
        TextRenderer renderer = textRenderer.get();
        boolean drew = bridging ? drawBridgeCount(renderer) : drawActivatePrompt(renderer);
        if (drew) renderer.drawAndClear();
    }

    /** 脚本 {@code mfabc713a1e8692(...)}：onMouse。 */
    @EventHandler
    private void onMouse(MousePressEvent event) {
        if (event.getButton() == GLFW.GLFW_MOUSE_BUTTON_RIGHT && promptReady()) event.cancel();
    }

    // ===== 脚本 169-269：模式、起停与阶段 =====

    private String modeName() {
        return switch (bridgeMode) {
            case Sy -> "Sy";
            case Clutch -> "Clutch";
            case GreatWall -> "GreatWall";
        };
    }

    private float basePitch() {
        return bridgeMode == BridgeMode.Sy ? SY_BASE_PITCH : GREATWALL_PITCH;
    }

    private void refreshSettings() {
        debug = debugSetting.getValue();
        hideOnLook = hideOnLookSetting.getValue();
        autoSwitch = autoSwitchSetting.getValue();
        showCount = showCountSetting.getValue();
        bridgeMode = mode.getValue();
        extraClicks = bridgeMode == BridgeMode.Clutch && extraClicksSetting.getValue();
    }

    private void startBridge() {
        LocalPlayer player = mc.player;
        if (player == null || bridging) return;
        if (!ensureBlock()) {
            debugWarn("No placeable blocks in hotbar");
            return;
        }
        bridging = true;
        clearActivation();
        bridgeBlockStartTotal = totalBlocks();
        bridgePlacedMax = 0;
        bridgeCount = 0;
        enterPhase(PHASE_WALK);
        wasOnGround = player.onGround();
        placeTick = 0;
        placeCount = 0;
        releaseSUntil = 0;
        useStartAt = 0;
        placing = false;
        startY = player.position().y;
        bridgeStartX = player.position().x;
        bridgeStartMs = clientTime();
        lastGroundY = startY;
        float snappedYaw = Math.round(player.getYRot() / 90.0f) * 90.0f;
        syBaseYaw = snappedYaw;
        syState = SY_STATE_SETTLE;
        syStateAt = clientTime();
        syCyclePlaced = 0;
        syLastCountTarget = null;
        syUseTick = 0;
        syClickAt = clientTime();
        sySwingAt = clientTime();
        setRotationTarget(snappedYaw, basePitch(), 300);
        debugForce("START", "&a");
    }

    private void stopBridge() {
        if (!bridging) return;
        bridging = false;
        phase = PHASE_IDLE;
        rotationActive = false;
        placing = false;
        releaseAll();
        clearActivation();
        noPromptUntil = clientTime() + 1000;
        debugForce("STOP", "&c");
        dbgFlush();
    }

    private void enterPhase(int next) {
        phase = next;
        phaseStart = clientTime();
        if (next == PHASE_BRIDGE) {
            useStartAt = clientTime() + 320;
            clutchSecondAt = 0;
            clutchNextAt = useStartAt;
            clutchLastPlaceAt = 0;
            clutchJumpCount = 0;
            clutchFirstTakeoff = true;
            clutchRetryCount = 0;
            clutchMmAvg = 0;
            clutchMmInit = false;
            if (bridgeMode == BridgeMode.Sy) {
                syState = SY_STATE_SETTLE;
                syStateAt = clientTime();
                syCyclePlaced = 0;
                syStartupClicks = true;
                syPrevPos = null;
                syGroundTicks = 0;
                syAirHoldTicks = 0;
                syAirHoldLeft = 0;
                syLastLandingFrac = -1;
            }
        }
    }

    // ===== 脚本 358-449：Sy 周期与坐标 =====

    private void syLanding(LocalPlayer player) {
        boolean grounded = player.onGround();
        if (grounded && !syGround) syUseTick = SY_USE_HOLD_PERIOD_TICKS;
        syGround = grounded;
    }

    private void updateSyCycle(LocalPlayer player) {
        long now = clientTime();
        syLanding(player);

        if (syState == SY_STATE_SETTLE) {
            boolean ready = syStartupClicks
                    ? syCyclePlaced >= SY_CYCLE_BLOCKS
                    : now - sySwingAt >= SY_CYCLE_MS && !syRescue(player);
            if (ready) {
                syDriftAmp = syDriftDeg(player);
                debugLog("D a" + r2(syDriftAmp) + " C" + syCyclePlaced + " L" + r2(syLag(player))
                        + " H" + syAirHoldTicks + " I" + r2(syLastLandingFrac));
                syState = SY_STATE_DRIFT;
                syStateAt = now;
                sySwingAt = now;
                syStartupClicks = false;
                setRotationTarget(syBaseYaw + syDriftAmp, SY_BASE_PITCH, SY_DRIFT_MS);
            }
        } else if (syState == SY_STATE_DRIFT) {
            if (now - syStateAt >= SY_DRIFT_MS) {
                player.setYRot(syBaseYaw + syDriftAmp);
                debugLog("K L" + r2(syLag(player)));
                syCyclePlaced = 0;
                setRotationTarget(syBaseYaw, SY_BASE_PITCH, SY_FLICK_MS);
                syState = SY_STATE_TURN;
                syStateAt = now;
            }
        } else if (syState == SY_STATE_TURN) {
            if (now - syStateAt >= SY_FLICK_MS) {
                syState = SY_STATE_SETTLE;
                syStateAt = now;
                syUseTick = SY_USE_HOLD_PERIOD_TICKS;
                syAirHoldLeft = syAirHoldTicks;
            }
        }
    }

    private double syAxisFrac(LocalPlayer player) {
        Vec3 p = player.position();
        double rad = Math.toRadians(syBaseYaw);
        return Math.abs(-Math.sin(rad)) > 0.5 ? p.x - Math.floor(p.x) : p.z - Math.floor(p.z);
    }

    private double syAxisDir() {
        double rad = Math.toRadians(syBaseYaw);
        return (Math.abs(-Math.sin(rad)) > 0.5 ? Math.sin(rad) : -Math.cos(rad)) > 0.0 ? 1.0 : -1.0;
    }

    @SuppressWarnings("unused")
    private double syAxisPos(Vec3 p) {
        double rad = Math.toRadians(syBaseYaw);
        return p.x * -Math.sin(rad) + p.z * Math.cos(rad);
    }

    private double r2(double v) {
        return Math.round(v * 100) / 100.0;
    }

    private double syLateralOffset(LocalPlayer player) {
        if (syLastCountTarget == null) return 0;
        Vec3 p = player.position();
        double rad = Math.toRadians(syBaseYaw);
        double rx = -Math.cos(rad);
        double rz = -Math.sin(rad);
        double dx = p.x - (Math.floor(syLastCountTarget.x) + 0.5);
        double dz = p.z - (Math.floor(syLastCountTarget.z) + 0.5);
        return dx * rx + dz * rz;
    }

    private float syDriftDeg(LocalPlayer player) {
        double t = syLateralOffset(player) + 0.5;
        if (t < 0.0) t = 0.0;
        if (t > 1.0) t = 1.0;
        return (float) (SY_DRIFT_MIN_DEG + (SY_DRIFT_MAX_DEG - SY_DRIFT_MIN_DEG) * t);
    }

    // ===== 脚本 585-688：放置、验证与桥线 =====

    private void placeBlock() {
        Vec3 target = raycastTarget();
        if (target != null && bridgeMode == BridgeMode.GreatWall && !isGreatWallLayer(target, placementFace)) {
            return;
        }
        if (target != null) {
            syPendingFill = target;
            syPendingFillAt = clientTime();
        }
        rightClick();
        clutchLastPlaceAt = clientTime();
        placeCount++;
        if (placeCount >= 3) {
            if (bridgeMode != BridgeMode.Clutch) releaseSUntil = clientTime() + RELEASE_S_MS;
            placeCount = 0;
        }
    }

    private void verifyFill() {
        if (syPendingFill == null) return;
        long wait = bridgeMode == BridgeMode.Clutch ? CLUTCH_VERIFY_MS : 100;
        if (clientTime() - syPendingFillAt < wait) return;
        boolean filled = !isAirBlock(syPendingFill);
        debugLog("F " + (filled ? "ok" : "AIR") + " " + (int) syPendingFill.x + ","
                + (int) syPendingFill.y + "," + (int) syPendingFill.z);
        if (filled) {
            countSyPlacement(syPendingFill);
        } else if (bridgeMode == BridgeMode.Clutch && bridging && clutchRetryCount < CLUTCH_RETRY_MAX && clutchSideFill()) {
            clutchRetryCount++;
            placeBlock();
            return;
        }
        syPendingFill = null;
    }

    private boolean clutchSideFill() {
        Object[] info = raycastInfo();
        if (info == null) return false;
        String face = (String) info[2];
        if (face == null || face.equals("UP") || face.equals("DOWN")) return false;
        Vec3 target = (Vec3) info[0];
        LocalPlayer player = mc.player;
        if (player == null) return false;
        Vec3 p = player.position();
        if (Math.abs(target.y - (lastGroundY - 1.0)) > 0.5) return false;
        double rad = Math.toRadians(syBaseYaw);
        boolean alongX = Math.abs(-Math.sin(rad)) > 0.5;
        double tLat = alongX ? target.z : target.x;
        double pLat = alongX ? p.z : p.x;
        double tAlong = alongX ? target.x : target.z;
        double pAlong = alongX ? p.x : p.z;
        if (Math.abs(tLat - Math.floor(pLat)) > 0.5) return false;
        if (syAxisDir() * (tAlong - pAlong) > 0.5) return false;
        return isAirBlock(target);
    }

    private double syLag(LocalPlayer player) {
        if (syLastCountTarget == null) return 0;
        Vec3 p = player.position();
        boolean alongX = Math.abs(-Math.sin(Math.toRadians(syBaseYaw))) > 0.5;
        double a = alongX ? Math.floor(p.x) : Math.floor(p.z);
        double b = alongX ? syLastCountTarget.x : syLastCountTarget.z;
        return Math.abs(a - b);
    }

    private boolean syRescue(LocalPlayer player) {
        if (syLastCountTarget == null) return false;
        Vec3 m = player.getDeltaMovement();
        if (m.y >= 0.0) return false;
        Vec3 p = player.position();
        return isAirBlock(new Vec3(Math.floor(p.x), syLastCountTarget.y, Math.floor(p.z)));
    }

    private void countSyPlacement(Vec3 target) {
        syCyclePlaced++;
        syLastCountTarget = target;
    }

    private boolean onBridgeLine(Vec3 target, LocalPlayer player) {
        if (syLastCountTarget != null && Math.abs(target.y - syLastCountTarget.y) > 0.5) return false;
        Vec3 p = player.position();
        double rad = Math.toRadians(syBaseYaw);
        boolean alongX = Math.abs(-Math.sin(rad)) > 0.5;
        double tLat = alongX ? Math.floor(target.z) : Math.floor(target.x);
        double pLat = alongX ? Math.floor(p.z) : Math.floor(p.x);
        return Math.abs(tLat - pLat) < 0.5;
    }

    // ===== 脚本 690-773：移动、射线与真实视角旋转 =====

    private void applyMovement(float forward, float strafe, boolean jumping) {
        float fwd = forward > 0.5f ? 1.0f : (forward < -0.5f ? -1.0f : 0.0f);
        float str = strafe > 0.5f ? 1.0f : (strafe < -0.5f ? -1.0f : 0.0f);
        setPressed("forward", fwd > 0.5f);
        setPressed("back", fwd < -0.5f);
        setPressed("left", str > 0.5f);
        setPressed("right", str < -0.5f);
        setPressed("jump", jumping);
        setClientForward(fwd);
        setClientStrafe(str);
        setClientJump(jumping);
        setClientSneak(false);
    }

    private Object[] raycastInfo() {
        Object[] hit = hostRaycast(4.5);
        if (hit == null || hit.length < 3 || hit[0] == null || hit[2] == null) return null;
        Vec3 support = (Vec3) hit[0];
        String face = (String) hit[2];
        placementFace = face;
        Vec3 target = targetFromFace(support, face);
        if (target == null) return null;
        return new Object[]{target, support, face};
    }

    private Vec3 raycastTarget() {
        Object[] info = raycastInfo();
        if (info == null) {
            logPlaceMiss();
            return null;
        }
        logPlaceHit(info);
        return (Vec3) info[0];
    }

    /** 长城只允许两层：侧面把桥面锁在 startY-1，顶面把垛口锁在 startY。 */
    private boolean isGreatWallLayer(Vec3 target, String face) {
        int f = faceFromName(face);
        if (f <= 0) return false;
        if (f == 1) return target.y == startY;
        return target.y == startY - 1.0;
    }

    private void logPlaceHit(Object[] info) {
        if (!debug) return;
        Vec3 support = (Vec3) info[1];
        Vec3 target = (Vec3) info[0];
        String face = (String) info[2];
        debugLog("P " + (face == null ? "?" : face.substring(0, 1))
                + " " + (int) support.x + "," + (int) support.y + "," + (int) support.z
                + " " + (int) target.x + "," + (int) target.y + "," + (int) target.z);
    }

    private void logPlaceMiss() {
        if (!debug) return;
        debugLog("M");
    }

    private Vec3 targetFromFace(Vec3 support, String face) {
        if (face == null) return null;
        return switch (face.toUpperCase(Locale.ROOT)) {
            case "UP" -> new Vec3(support.x, support.y + 1, support.z);
            case "DOWN" -> new Vec3(support.x, support.y - 1, support.z);
            case "NORTH" -> new Vec3(support.x, support.y, support.z - 1);
            case "SOUTH" -> new Vec3(support.x, support.y, support.z + 1);
            case "WEST" -> new Vec3(support.x - 1, support.y, support.z);
            case "EAST" -> new Vec3(support.x + 1, support.y, support.z);
            default -> null;
        };
    }

    private void setRotationTarget(float targetYaw, float targetPitch, long duration) {
        LocalPlayer player = mc.player;
        if (player == null) return;
        rotationStartYaw = player.getYRot();
        rotationStartPitch = player.getXRot();
        rotationTargetYaw = rotationStartYaw + wrapAngle(targetYaw - rotationStartYaw);
        rotationTargetPitch = clamp(targetPitch, -90.0f, 90.0f);
        rotationStartedAt = clientTime();
        rotationDuration = Math.max(1L, duration);
        rotationActive = true;
    }

    private void applySmoothedRotation() {
        if (!rotationActive) return;
        LocalPlayer player = mc.player;
        if (player == null) return;

        double progress = (double) (clientTime() - rotationStartedAt) / (double) rotationDuration;
        if (progress < 0.0) progress = 0.0;
        if (progress > 1.0) progress = 1.0;

        float desiredYaw = rotationStartYaw + (rotationTargetYaw - rotationStartYaw) * (float) progress;
        float desiredPitch = rotationStartPitch + (rotationTargetPitch - rotationStartPitch) * (float) progress;
        float qYaw = quantizeFrom(rotationStartYaw, desiredYaw);
        float qPitch = quantizeFrom(rotationStartPitch, desiredPitch);

        player.setYRot(qYaw);
        player.setXRot(clamp(qPitch, -90.0f, 90.0f));
        if (progress >= 1.0) rotationActive = false;
    }

    private float quantizeFrom(float origin, float value) {
        double quantum = RotationUtils.mouseQuantum();
        if (quantum <= 0.0) return value;
        double steps = Math.round((value - origin) / quantum);
        return (float) (origin + steps * quantum);
    }

    private void releaseAll() {
        setPressed("forward", false);
        setPressed("back", false);
        setPressed("left", false);
        setPressed("right", false);
        setPressed("jump", false);
        setPressed("use", false);
        setPressed("sneak", false);
        setPressed("sprint", false);
        setClientForward(0);
        setClientStrafe(0);
        setClientJump(false);
        setClientSneak(false);
        setClientSprinting(false);
    }

    private float wrapAngle(float angle) {
        while (angle > 180.0f) angle -= 360.0f;
        while (angle < -180.0f) angle += 360.0f;
        return angle;
    }

    private float clamp(float value, float min, float max) {
        if (value < min) return min;
        if (value > max) return max;
        return value;
    }

    // ===== 脚本 801-903：调试输出 =====

    private String debugPrefix() {
        return "&7[&d" + modeName() + "&7]&r ";
    }

    private void debugLog(String msg) {
        if (!debug || !dbgTake()) return;
        dbgAppend(debugPrefix() + "&7" + msg);
    }

    private void debugWarn(String msg) {
        if (!debug || !dbgTake()) return;
        dbgAppend(debugPrefix() + "&e" + msg);
    }

    private void debugForce(String msg, String color) {
        if (debug) dbgAppend(debugPrefix() + color + msg);
    }

    private void dbgAppend(String colored) {
        if (dbgBufLines == 0) dbgBufAt = clientTime();
        dbgBuf = dbgBufLines == 0 ? colored : dbgBuf + "&r | &r" + colored;
        dbgBufLines++;
        if (dbgBufLines >= 5 && clientTime() - dbgFlushAt >= 200) dbgFlush();
    }

    private void dbgFlush() {
        if (dbgBufLines == 0) return;
        clientPrint(dbgBuf);
        dbgBuf = "";
        dbgBufLines = 0;
        dbgFlushAt = clientTime();
    }

    private void dbgFlushStale() {
        if (dbgBufLines > 0 && clientTime() - dbgBufAt >= 500) dbgFlush();
    }

    private boolean dbgTake() {
        long t = clientTime() / 50;
        if (t != dbgTickAt) {
            dbgTickAt = t;
            dbgTickLines = 0;
        }
        if (dbgTickLines >= 4) return false;
        dbgTickLines++;
        return true;
    }

    private String dbgStateChar() {
        if (phase == PHASE_WALK) return "w";
        if (bridgeMode == BridgeMode.Clutch) return "c";
        if (bridgeMode != BridgeMode.Sy) return "g";
        if (syState == SY_STATE_TURN) return "k";
        if (syState == SY_STATE_DRIFT) return "d";
        return "b";
    }

    private void dbgRec(LocalPlayer player) {
        if (!debug) return;
        long t = clientTime() / 50;
        if (t == dbgRecAt) return;
        dbgRecAt = t;
        Vec3 p = player.position();
        String head = debugPrefix() + "&7R" + ((clientTime() - bridgeStartMs) / 50) + " " + dbgStateChar();
        String tail = " " + r2(p.x) + "," + r2(p.y) + "," + r2(p.z)
                + " h" + r2(p.y - lastGroundY) + " g" + (player.onGround() ? 1 : 0)
                + " S" + (isPressed("back") ? "+" : "-")
                + " J" + (isPressed("jump") ? "+" : "-")
                + " U" + (physicalRightMouseDown() ? "+" : "-");
        if (bridgeMode != BridgeMode.Sy) {
            dbgAppend(head + tail);
            return;
        }
        float yawErr = wrapAngle(player.getYRot() - syBaseYaw);
        if (yawErr < 0.0f) yawErr = -yawErr;
        dbgAppend(head
                + " y" + r2(player.getYRot())
                + tail
                + " e" + r2(yawErr)
                + " L" + r2(syLag(player)) + " C" + syCyclePlaced
                + " v" + r2(syDbgSpeed * 100) + " l" + r2(syDbgLanding));
    }

    // ===== 脚本 951-1058：激活与 HUD =====

    private boolean promptShown() {
        return activateAt != 0 && clientTime() - activateAt >= Math.min(500L, activationWaitMs());
    }

    private boolean promptReady() {
        return activateAt != 0 && clientTime() - activateAt >= activationWaitMs();
    }

    private long activationWaitMs() {
        return Math.round(activationTimeSetting.getValue() * 1000.0);
    }

    private void updateActivation() {
        LocalPlayer player = mc.player;
        if (player == null || mc.gui.screen() != null) {
            clearActivation();
            return;
        }
        if (clientTime() < noPromptUntil) {
            clearActivation();
            return;
        }

        Vec3 pos = player.position();
        if (lastActInit) {
            double dx = pos.x - lastActX;
            double dy = pos.y - lastActY;
            double dz = pos.z - lastActZ;
            if (dx * dx + dy * dy + dz * dz > 0.0001) {
                clearActivation();
                lastActInit = false;
                return;
            }
        }
        lastActX = pos.x;
        lastActY = pos.y;
        lastActZ = pos.z;
        lastActInit = true;

        float curYaw = player.getYRot();
        float curPitch = player.getXRot();
        if (hideOnLook && lastActLookInit) {
            if (Math.abs(curYaw - lastActYaw) > 0.01f || Math.abs(curPitch - lastActPitch) > 0.01f) {
                // Hide On Look 只重置激活计时；候选面和范围继续显示，才能用来对准。
                activateAt = 0;
                promptRgb = 0xFF5555;
                lastActLookInit = false;
                return;
            }
        }
        lastActYaw = curYaw;
        lastActPitch = curPitch;
        lastActLookInit = true;

        if (isAimingForBridge(player)) {
            if (activateAt == 0) activateAt = clientTime();
            promptRgb = promptReady() ? 0x55FF55 : 0xFF5555;
        } else {
            // 有命中面但不在区域内时保留 aimPos/aimFace，继续绘制红色引导框。
            activateAt = 0;
            promptRgb = 0xFF5555;
        }
    }

    private boolean isAimingForBridge(LocalPlayer player) {
        float pitch = player.getXRot();
        float base = basePitch();
        if (pitch < base - ACTIVATION_PITCH_TOLERANCE || pitch > base + ACTIVATION_PITCH_TOLERANCE) {
            aimPos = null;
            aimFace = -1;
            return false;
        }
        return updateAimFromHit(hostRaycast(4.5));
    }

    private boolean updateAimFromHit(Object[] hit) {
        if (hit == null || hit.length < 3 || hit[0] == null || hit[1] == null || hit[2] == null) {
            aimPos = null;
            aimFace = -1;
            return false;
        }
        aimPos = posFromVec((Vec3) hit[0]);
        aimFace = faceFromName((String) hit[2]);
        return aimPos != null && aimFace >= 0 && isInActivationRegion((Vec3) hit[1], aimFace);
    }

    /** 返回当前命中点是否位于 drawActivationFace 所画的同一区域。 */
    private boolean isInActivationRegion(Vec3 local, int face) {
        if (face == 0 || face == 1) {
            return inRange(local.x, ACT_ACROSS_MIN, ACT_ACROSS_MAX)
                    && inRange(local.z, ACT_ACROSS_MIN, ACT_ACROSS_MAX);
        }
        double across = face == 4 || face == 5 ? local.z : local.x;
        return inRange(across, ACT_ACROSS_MIN, ACT_ACROSS_MAX)
                && inRange(local.y, ACT_HEIGHT_MIN, ACT_HEIGHT_MAX);
    }

    private boolean inRange(double value, double min, double max) {
        return value >= min && value <= max;
    }

    private void clearActivation() {
        activateAt = 0;
        aimPos = null;
        aimFace = -1;
    }

    private void updateActivatePromptFade() {
        boolean show = promptShown();
        long now = clientTime();
        long elapsed = promptFadeLastAt == 0 ? 0 : Math.min(100, now - promptFadeLastAt);
        promptFadeLastAt = now;
        float step = elapsed / 200.0f;
        promptAlpha += show ? step : -step;
        if (promptAlpha < 0) promptAlpha = 0;
        if (promptAlpha > 1) promptAlpha = 1;
    }

    private boolean drawActivatePrompt(TextRenderer renderer) {
        if (promptAlpha < 0.05f) return false;
        String text = "Activate?";
        int alpha = (int) (promptAlpha * 255.0f);
        if (alpha < 16) alpha = 16;
        int color = (alpha << 24) | promptRgb;
        float x = mc.getWindow().getGuiScaledWidth() / 2.0f - renderer.getWidth(text, 1.0f) / 2.0f;
        float y = mc.getWindow().getGuiScaledHeight() / 2.0f + 10.0f;
        renderer.addText(text, x, y, 1.0f, new Color(color, true));
        return true;
    }

    private boolean drawBridgeCount(TextRenderer renderer) {
        if (!showCount) return false;
        if (countLabel == null || countLabelValue != bridgeCount || countLabelMode != bridgeMode) {
            countLabel = modeName() + " " + bridgeCount;
            countLabelValue = bridgeCount;
            countLabelMode = bridgeMode;
            countLabelWidth = renderer.getWidth(countLabel, 1.0f);
        }
        renderer.addText(countLabel, mc.getWindow().getGuiScaledWidth() / 2.0f - countLabelWidth / 2.0f,
                mc.getWindow().getGuiScaledHeight() / 2.0f + 20.0f, 1.0f, new Color(0xFFFFFFFF, true));
        return true;
    }

    /** 源脚本的单个面四边形；scheduler 的 Side 绘制与其填充/四边线框等价。 */
    private void drawActivationFace(int[] pos, int face) {
        double px = pos[0], py = pos[1], pz = pos[2];
        double yMin = py + ACT_HEIGHT_MIN;
        double yMax = py + ACT_HEIGHT_MAX;
        double aMin = ACT_ACROSS_MIN;
        double aMax = ACT_ACROSS_MAX;
        double x1, x2, z1, z2;

        if (face == 5) {
            x1 = x2 = px + 1.005;
            z1 = pz + aMin;
            z2 = pz + aMax;
        } else if (face == 4) {
            x1 = x2 = px - 0.005;
            z1 = pz + 1 - aMax;
            z2 = pz + 1 - aMin;
        } else if (face == 3) {
            z1 = z2 = pz + 1.005;
            x1 = px + 1 - aMax;
            x2 = px + 1 - aMin;
        } else if (face == 2) {
            z1 = z2 = pz - 0.005;
            x1 = px + aMin;
            x2 = px + aMax;
        } else {
            x1 = px + aMin;
            x2 = px + aMax;
            z1 = pz + aMin;
            z2 = pz + aMax;
            yMin = yMax = face == 1 ? py + 1.005 : py - 0.005;
        }

        int r = (promptRgb >> 16) & 0xFF;
        int g = (promptRgb >> 8) & 0xFF;
        int b = promptRgb & 0xFF;
        float visibility = Math.max(promptAlpha, 0.35f);
        int fillAlpha = Math.max(4, (int) (60.0f * visibility));
        int lineAlpha = Math.max(16, (int) (220.0f * visibility));
        AABB box = new AABB(Math.min(x1, x2), Math.min(yMin, yMax), Math.min(z1, z2),
                Math.max(x1, x2), Math.max(yMin, yMax), Math.max(z1, z2));
        Direction direction = Direction.from3DDataValue(face);
        Render3DScheduler.INSTANCE.addFilledSide(box, new Color(r, g, b, fillAlpha).getRGB(), direction);
        Render3DScheduler.INSTANCE.addSideOutline(box, new Color(r, g, b, lineAlpha).getRGB(), 2.0f, direction);
    }

    // ===== 脚本 1090-1172：物品、方块与边缘 =====

    private int faceFromName(String name) {
        if (name == null) return -1;
        return switch (name.toUpperCase(Locale.ROOT)) {
            case "DOWN" -> 0;
            case "UP" -> 1;
            case "NORTH" -> 2;
            case "SOUTH" -> 3;
            case "WEST" -> 4;
            case "EAST" -> 5;
            default -> -1;
        };
    }

    private int[] posFromVec(Vec3 vec) {
        return new int[]{floor(vec.x), floor(vec.y), floor(vec.z)};
    }

    private int floor(double v) {
        int i = (int) v;
        return v < i ? i - 1 : i;
    }

    private boolean isPlaceableBlock(ItemStack stack) {
        if (stack == null || stack.isEmpty() || !(stack.getItem() instanceof BlockItem)) return false;
        String name = itemName(stack);
        return !name.contains("slab") && !name.contains("stairs");
    }

    private int heldBlockCount() {
        LocalPlayer player = mc.player;
        if (player == null) return 0;
        ItemStack held = player.getMainHandItem();
        if (!isPlaceableBlock(held) || held.getCount() <= 0) return 0;
        return held.getCount();
    }

    private int totalBlocks() {
        int total = 0;
        for (int i = 0; i < 9; i++) {
            ItemStack stack = hotbarStack(i);
            if (isPlaceableBlock(stack) && stack.getCount() > 0) total += stack.getCount();
        }
        return total;
    }

    private int findBlockSlot() {
        int best = -1;
        int bestCount = 0;
        for (int i = 0; i < 9; i++) {
            ItemStack stack = hotbarStack(i);
            if (!isPlaceableBlock(stack) || stack.getCount() <= 0) continue;
            if (stack.getCount() > bestCount) {
                best = i;
                bestCount = stack.getCount();
            }
        }
        return best;
    }

    private boolean ensureBlock() {
        if (heldBlockCount() > 0) return true;
        if (!autoSwitch) return false;
        int slot = findBlockSlot();
        if (slot < 0) return false;
        setSelectedSlot(slot);
        return true;
    }

    private boolean isAirBlock(Vec3 pos) {
        if (mc.level == null) return true;
        BlockPos blockPos = new BlockPos(floor(pos.x), floor(pos.y), floor(pos.z));
        BlockState state = mc.level.getBlockState(blockPos);
        String name = BuiltInRegistries.BLOCK.getKey(state.getBlock()).toString();
        if (name.startsWith("minecraft:")) name = name.substring(10);
        return name.equals("air") || name.equals("cave_air") || name.equals("void_air");
    }

    private boolean isAtEdge() {
        LocalPlayer player = mc.player;
        if (player == null) return false;
        Vec3 feet = player.position();
        float yaw = (float) Math.toRadians(player.getYRot());
        double fx = -Math.sin(yaw);
        double fz = Math.cos(yaw);
        Vec3 sample = new Vec3(feet.x - fx * 0.3, lastGroundY - 0.5, feet.z - fz * 0.3);
        return isAirBlock(sample);
    }

    // ===== 宿主替换层：Raven ScriptDefaults → 26.2 / Epsilon =====

    private long clientTime() {
        return System.currentTimeMillis();
    }

    private void rightClick() {
        if (bridgeMode == BridgeMode.Clutch) {
            long now = clientTime();
            if (now - lastRightClickAt < 50) return;
            lastRightClickAt = now;
        }
        ((MinecraftAccessor) mc).epsilon$startUseItem();
    }

    private Object[] hostRaycast(double reach) {
        LocalPlayer player = mc.player;
        if (player == null || mc.level == null) return null;
        Vec3 eyes = player.position().add(0, player.getEyeHeight(), 0);
        Vec3 to = eyes.add(getLookVec(player.getYRot(), player.getXRot()).scale(reach));
        BlockHitResult hit = mc.level.clip(new ClipContext(eyes, to, ClipContext.Block.OUTLINE, ClipContext.Fluid.NONE, player));
        if (hit.getType() == HitResult.Type.MISS) return null;
        BlockPos blockPos = hit.getBlockPos();
        Vec3 localOffset = hit.getLocation().subtract(blockPos.getX(), blockPos.getY(), blockPos.getZ());
        return new Object[]{new Vec3(blockPos.getX(), blockPos.getY(), blockPos.getZ()), localOffset,
                faceName(hit.getDirection().get3DDataValue())};
    }

    private Vec3 getLookVec(float yaw, float pitch) {
        double yawRad = Math.toRadians(yaw);
        double pitchRad = Math.toRadians(pitch);
        double cosPitch = Math.cos(pitchRad);
        return new Vec3(-Math.sin(yawRad) * cosPitch, -Math.sin(pitchRad), Math.cos(yawRad) * cosPitch);
    }

    private String faceName(int face) {
        return switch (face) {
            case 0 -> "DOWN";
            case 1 -> "UP";
            case 2 -> "NORTH";
            case 3 -> "SOUTH";
            case 4 -> "WEST";
            default -> "EAST";
        };
    }

    private ItemStack hotbarStack(int slot) {
        return mc.player == null ? ItemStack.EMPTY : mc.player.getInventory().getItem(slot);
    }

    private void setSelectedSlot(int slot) {
        if (mc.player != null) mc.player.getInventory().setSelectedSlot(slot);
    }

    private String itemName(ItemStack stack) {
        if (stack.isEmpty()) return "air";
        return BuiltInRegistries.ITEM.getKey(stack.getItem()).getPath().toLowerCase(Locale.ROOT);
    }

    private boolean physicalRightMouseDown() {
        return GLFW.glfwGetMouseButton(mc.getWindow().handle(), GLFW.GLFW_MOUSE_BUTTON_RIGHT) == GLFW.GLFW_PRESS;
    }

    private KeyMapping mapping(String key) {
        return switch (key) {
            case "forward" -> mc.options.keyUp;
            case "back" -> mc.options.keyDown;
            case "left" -> mc.options.keyLeft;
            case "right" -> mc.options.keyRight;
            case "jump" -> mc.options.keyJump;
            case "sneak" -> mc.options.keyShift;
            case "sprint" -> mc.options.keySprint;
            case "use" -> mc.options.keyUse;
            default -> null;
        };
    }

    private void setPressed(String key, boolean state) {
        KeyMapping mapping = mapping(key);
        if (mapping != null) ((KeyMappingAccessor) mapping).epsilon$setDownState(state);
    }

    private boolean isPressed(String key) {
        KeyMapping mapping = mapping(key);
        return mapping != null && mapping.isDown();
    }

    private void setClientForward(float forward) {
        if (activeInputEvent != null) activeInputEvent.setForward(forward);
    }

    private void setClientStrafe(float strafe) {
        if (activeInputEvent != null) activeInputEvent.setStrafe(strafe);
    }

    private void setClientJump(boolean jump) {
        if (activeInputEvent != null) activeInputEvent.setJump(jump);
    }

    private void setClientSneak(boolean sneak) {
        if (activeInputEvent != null) activeInputEvent.setSneak(sneak);
    }

    private void setClientSprinting(boolean sprinting) {
        if (activeInputEvent != null) activeInputEvent.setSprint(sprinting);
        if (mc.player != null) mc.player.setSprinting(sprinting);
    }

    /** 脚本 {@code client.print(util.color(...))}：消息已含模式前缀，不加 Epsilon 前缀。 */
    private void clientPrint(String message) {
        ChatUtils.addChatMessage(false, styled(message));
    }

    private Component styled(String text) {
        MutableComponent out = Component.empty();
        ChatFormatting format = null;
        StringBuilder buf = new StringBuilder();
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (c == '&' && i + 1 < text.length()) {
                ChatFormatting f = ChatFormatting.getByCode(Character.toLowerCase(text.charAt(i + 1)));
                if (f != null) {
                    if (buf.length() > 0) {
                        out.append(applyStyle(buf.toString(), format));
                        buf.setLength(0);
                    }
                    format = f;
                    i++;
                    continue;
                }
            }
            buf.append(c);
        }
        out.append(applyStyle(buf.toString(), format));
        return out;
    }

    private MutableComponent applyStyle(String text, ChatFormatting format) {
        return format == null ? Component.literal(text) : Component.literal(text).withStyle(format);
    }
}
