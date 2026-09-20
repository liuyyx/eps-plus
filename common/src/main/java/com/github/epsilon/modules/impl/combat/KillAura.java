package com.github.epsilon.modules.impl.combat;

import com.github.epsilon.events.bus.EventBus;
import com.github.epsilon.events.bus.EventHandler;
import com.github.epsilon.events.bus.listeners.ConsumerListener;
import com.github.epsilon.events.impl.ClientTickEvent;
import com.github.epsilon.events.impl.PlayerTickEvent;
import com.github.epsilon.events.impl.Render3DEvent;
import com.github.epsilon.events.impl.RespawnEvent;
import com.github.epsilon.managers.rotation.RotationManager;
import com.github.epsilon.managers.target.TargetManager;
import com.github.epsilon.managers.target.TargetRequest;
import com.github.epsilon.modules.Category;
import com.github.epsilon.modules.Module;
import com.github.epsilon.modules.impl.movement.NoSlowdown;
import com.github.epsilon.modules.impl.movement.Scaffold;
import com.github.epsilon.modules.impl.movement.Velocity;
import com.github.epsilon.settings.impl.*;
import com.github.epsilon.utils.ai.AiRotationModelManager;
import com.github.epsilon.utils.math.MathUtils;
import com.github.epsilon.utils.player.PlayerUtils;
import com.github.epsilon.utils.render.esp.CaptureMarkESP;
import com.github.epsilon.utils.render.esp.CircleESP;
import com.github.epsilon.utils.render.esp.DeobfESP;
import com.github.epsilon.utils.render.esp.FireflyESP;
import com.github.epsilon.utils.rotation.Priority;
import com.github.epsilon.utils.rotation.RaytraceUtils;
import com.github.epsilon.utils.rotation.Rot2f;
import com.github.epsilon.utils.rotation.RotationUtils;
import com.github.epsilon.utils.timer.TimerUtils;
import com.mojang.blaze3d.vertex.PoseStack;
import net.minecraft.client.multiplayer.ClientPacketListener;
import net.minecraft.client.multiplayer.PlayerInfo;
import net.minecraft.network.protocol.game.ServerboundSwingPacket;
import net.minecraft.util.Mth;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.phys.EntityHitResult;
import net.minecraft.world.phys.HitResult;

import java.awt.*;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

public class KillAura extends Module {

    public static final KillAura INSTANCE = new KillAura();

    private KillAura() {
        super("Kill Aura", Category.COMBAT);
        EventBus.INSTANCE.subscribe(new ConsumerListener<>(Render3DEvent.class, event -> {
            DeobfESP.render(
                    event.getPoseStack(),
                    deobfSize.getValue().floatValue(),
                    deobfSpins.getValue().floatValue(),
                    deobfWobble.getValue().floatValue(),
                    deobfFlyHeight.getValue().floatValue()
            );
        }));
    }

    private enum Mode {
        OnePointEight,
        OnePointNinePlus
    }

    private enum TargetMode {
        Single,
        Switch
    }

    private enum PriorityMode {
        None,
        Health,
        Fov,
        Range
    }

    private enum ESPMode {
        CaptureMark,
        Circle,
        Firefly,
        Deobf
    }

    private enum AimMode {
        Normal,
        Polar,
        Ai
    }

    private enum AiModel {
        Model21KC11KP,
        Model19KC8KP
    }

    private final BoolSetting pauseOnEat = boolSetting("Pause On Eat", true);
    private final BoolSetting pauseOnScaffold = boolSetting("Pause On Scaffold", true);
    private final BoolSetting hitSelect = boolSetting("Hit Select", true);
    private final EnumSetting<Mode> mode = enumSetting("Mode", Mode.OnePointEight);
    private final EnumSetting<TargetMode> targetMode = enumSetting("Target Mode", TargetMode.Single);
    private final IntSetting switchDelay = intSetting("Switch Delay", 100, 0, 500, 1, () -> targetMode.is(TargetMode.Switch));
    private final EnumSetting<PriorityMode> priorityMode = enumSetting("Priority Mode", PriorityMode.None);
    public final DoubleSetting searchRange = doubleSetting("Search Range", 4.0, 1.0, 6.0, 0.1);
    public final DoubleSetting aimRange = doubleSetting("Aim Range", 3.0, 1.0, 6.0, 0.1);
    private final IntSetting fov = intSetting("FOV", 360, 10, 360, 1);
    private final IntSetting rotationSpeed = intSetting("Rotation Speed", 180, 10, 180, 10);
    private final EnumSetting<Priority> rotationPriority = enumSetting("Rotation Priority", Priority.High);
    private final EnumSetting<AimMode> aimMode = enumSetting("Aim Mode", AimMode.Normal);
    private final EnumSetting<AiModel> aiModel = enumSetting("AI Model", AiModel.Model21KC11KP, () -> aimMode.is(AimMode.Ai));
    private final DoubleSetting aiYawMultiplier = doubleSetting("AI Yaw Multiplier", 1.5, 0.5, 2.0, 0.05, () -> aimMode.is(AimMode.Ai));
    private final DoubleSetting aiPitchMultiplier = doubleSetting("AI Pitch Multiplier", 1.0, 0.5, 2.0, 0.05, () -> aimMode.is(AimMode.Ai));
    // Polar 瞄准：把 LiquidBounce AccelerationAngleSmooth 的「区间设置」拆成 Min/Max 两项
    // （Epsilon 没有区间型设置），每刻在区间内随机取一次加速度上限。
    private final DoubleSetting polarYawAccelerationMin = doubleSetting("Polar Yaw Acceleration Min", 22.0, 1.0, 180.0, 0.1, () -> aimMode.is(AimMode.Polar));
    private final DoubleSetting polarYawAccelerationMax = doubleSetting("Polar Yaw Acceleration Max", 25.0, 1.0, 180.0, 0.1, () -> aimMode.is(AimMode.Polar));
    private final DoubleSetting polarPitchAccelerationMin = doubleSetting("Polar Pitch Acceleration Min", 15.0, 1.0, 180.0, 0.1, () -> aimMode.is(AimMode.Polar));
    private final DoubleSetting polarPitchAccelerationMax = doubleSetting("Polar Pitch Acceleration Max", 17.0, 1.0, 180.0, 0.1, () -> aimMode.is(AimMode.Polar));
    private final IntSetting polarClickMinCps = intSetting("Polar Click Min CPS", 9, 1, 60, 1, () -> aimMode.is(AimMode.Polar));
    private final IntSetting polarClickMaxCps = intSetting("Polar Click Max CPS", 11, 1, 60, 1, () -> aimMode.is(AimMode.Polar));
    private final BoolSetting polarIgnoreAttackCooldown = boolSetting("Polar Ignore Attack Cooldown", true, () -> aimMode.is(AimMode.Polar));
    private final DoubleSetting polarCooldownMin = doubleSetting("Polar Cooldown Min", 0.15, 0.0, 2.0, 0.01, () -> aimMode.is(AimMode.Polar));
    private final DoubleSetting polarCooldownMax = doubleSetting("Polar Cooldown Max", 0.36, 0.0, 2.0, 0.01, () -> aimMode.is(AimMode.Polar));
    private final IntSetting cps = intSetting("CPS", 12, 1, 20, 1, () -> mode.is(Mode.OnePointEight));

    private final BoolSetting players = boolSetting("Players", true);
    private final BoolSetting mobs = boolSetting("Mobs", true);
    private final BoolSetting animals = boolSetting("Animals", true);
    private final BoolSetting villagers = boolSetting("Villagers", false);
    private final BoolSetting ambient = boolSetting("Ambient", false);
    private final BoolSetting water = boolSetting("Water", false);
    private final BoolSetting others = boolSetting("Others", false);
    private final BoolSetting invisible = boolSetting("Invisible", true);

    private final BoolSetting swingHand = boolSetting("SwingHand", true);
    private final BoolSetting esp = boolSetting("ESP", true);
    private final EnumSetting<ESPMode> espMode = enumSetting("ESP Mode", ESPMode.Circle, esp::getValue);
    public final EnumSetting<DeobfESP.TextureMode> deobfMode = enumSetting("Deobf Mode", DeobfESP.TextureMode.Mengcha, () -> esp.getValue() && espMode.is(ESPMode.Deobf));
    private final DoubleSetting deobfSize = doubleSetting("Deobf Size", 0.75, 0.25, 2.0, 0.05, () -> esp.getValue() && espMode.is(ESPMode.Deobf));
    private final DoubleSetting deobfSpins = doubleSetting("Deobf Spins", 3.0, 0.5, 8.0, 0.25, () -> esp.getValue() && espMode.is(ESPMode.Deobf));
    private final DoubleSetting deobfWobble = doubleSetting("Deobf Wobble", 1.0, 0.0, 2.0, 0.1, () -> esp.getValue() && espMode.is(ESPMode.Deobf));
    private final DoubleSetting deobfFlyHeight = doubleSetting("Deobf Fly Height", 5.0, 1.0, 12.0, 0.5, () -> esp.getValue() && espMode.is(ESPMode.Deobf));
    private final ColorSetting espColor1 = colorSetting("ESP Main", new Color(255, 183, 197), () -> esp.getValue() && espMode.is(ESPMode.CaptureMark));
    private final ColorSetting espColor2 = colorSetting("ESP Second", new Color(255, 133, 161), () -> esp.getValue() && espMode.is(ESPMode.CaptureMark));
    private final DoubleSetting espSize = doubleSetting("ESP Size", 1.2, 0.5, 3.0, 0.1, () -> esp.getValue() && espMode.is(ESPMode.CaptureMark));
    private final DoubleSetting espRotSpeed = doubleSetting("Rot Speed", 2.0, 0.5, 10.0, 0.1, () -> esp.getValue() && espMode.is(ESPMode.CaptureMark));
    private final DoubleSetting waveSpeed = doubleSetting("Wave Speed", 3.0, 0.5, 10.0, 0.1, () -> esp.getValue() && espMode.is(ESPMode.CaptureMark));
    private final ColorSetting sideColor = colorSetting("Side Color", Color.WHITE, false, () -> esp.getValue() && espMode.is(ESPMode.Circle));
    private final ColorSetting lineColor = colorSetting("Line Color", new Color(255, 255, 255, 233), () -> esp.getValue() && espMode.is(ESPMode.Circle));
    private final DoubleSetting circleRadius = doubleSetting("Circle Radius", 0.75, 0.1, 2.0, 0.05, () -> esp.getValue() && espMode.is(ESPMode.Circle));
    private final DoubleSetting circleAlphaFactor = doubleSetting("Circle Alpha Factor", 1.0, 0.0, 2.0, 0.05, () -> esp.getValue() && espMode.is(ESPMode.Circle));
    private final EnumSetting<FireflyESP.ColorMode> fireflyColorMode = enumSetting("Firefly Color Mode", FireflyESP.ColorMode.Blend, () -> esp.getValue() && espMode.is(ESPMode.Firefly));
    private final ColorSetting fireflyColor = colorSetting("Firefly Color", new Color(149, 149, 149, 255), () -> esp.getValue() && espMode.is(ESPMode.Firefly));
    private final ColorSetting fireflyColor2 = colorSetting("Firefly Color 2", new Color(255, 133, 161, 255), () -> esp.getValue() && espMode.is(ESPMode.Firefly) && fireflyColorMode.is(FireflyESP.ColorMode.Blend));
    private final DoubleSetting fireflyColorMix = doubleSetting("Firefly Color Mix", 0.65, 0.0, 1.0, 0.05, () -> esp.getValue() && espMode.is(ESPMode.Firefly) && fireflyColorMode.is(FireflyESP.ColorMode.Blend));
    private final DoubleSetting fireflyColorSpeed = doubleSetting("Firefly Color Speed", 1.2, 0.1, 6.0, 0.1, () -> esp.getValue() && espMode.is(ESPMode.Firefly) && fireflyColorMode.is(FireflyESP.ColorMode.Blend));
    private final DoubleSetting fireflyRainbowSpeed = doubleSetting("Firefly Rainbow Speed", 1.0, 0.1, 6.0, 0.1, () -> esp.getValue() && espMode.is(ESPMode.Firefly) && fireflyColorMode.is(FireflyESP.ColorMode.Rainbow));
    private final DoubleSetting fireflyRainbowSaturation = doubleSetting("Firefly Rainbow Saturation", 0.85, 0.1, 1.0, 0.05, () -> esp.getValue() && espMode.is(ESPMode.Firefly) && fireflyColorMode.is(FireflyESP.ColorMode.Rainbow));
    private final DoubleSetting fireflyRainbowBrightness = doubleSetting("Firefly Rainbow Brightness", 1.0, 0.1, 1.0, 0.05, () -> esp.getValue() && espMode.is(ESPMode.Firefly) && fireflyColorMode.is(FireflyESP.ColorMode.Rainbow));
    private final IntSetting fireflyLength = intSetting("Firefly Length", 14, 8, 128, 1, () -> esp.getValue() && espMode.is(ESPMode.Firefly));
    private final IntSetting fireflyFactor = intSetting("Firefly Factor", 8, 1, 10, 1, () -> esp.getValue() && espMode.is(ESPMode.Firefly));
    private final DoubleSetting fireflyShaking = doubleSetting("Firefly Shaking", 1.8, 0.25, 10.0, 0.25, () -> esp.getValue() && espMode.is(ESPMode.Firefly));
    private final DoubleSetting fireflyAmplitude = doubleSetting("Firefly Amplitude", 3.0, 0.0, 10.0, 0.25, () -> esp.getValue() && espMode.is(ESPMode.Firefly));

    public LivingEntity target;
    private List<LivingEntity> targets;
    private int targetIndex;

    private int attacks;
    private long lastAttackTime;

    // AI 转向：模块自持的上一刻托管角快照 + tick 戳。
    // 不能复用 RotationManager.lastRotations —— 它在 onSendPosition 中被赋为 rotations
    // 的同一对象引用，读取瞬间两者恒等，会导致角速度特征恒为 0。
    // tick 戳用于判定相邻性：目标消失、模式切换、重生等跳刻场景会使 delta != 1，
    // 此时角速度特征取 0 而非跨间隔的虚假值。
    // 注意：采样位于遮挡早退之前，故被遮挡的 tick 仍会推进 tick 戳；
    // 该刻不施加转向，因此紧随其后的采样刻差值自然为 0，属正确行为。
    private float previousManagedYaw;
    private float previousManagedPitch;
    private int previousManagedTick = Integer.MIN_VALUE;

    // Polar 转向：上一刻实际提交的步长（yaw/pitch），是加速度模型里的「动量」项。
    // polarLastTarget / polarLastTick 用于识别目标切换与跳刻：一旦不连续就把动量清零，
    // 否则恢复瞄准的首刻会按中断前的旧步长跳一大步。
    private float polarPrevYawDelta;
    private float polarPrevPitchDelta;
    private Entity polarLastTarget;
    private int polarLastTick = Integer.MIN_VALUE;

    // Polar 点击：与 MultiAura 同一写法的每分钟点击累加器，整点击落转交 attacks。
    private double polarClickAccumulator;

    // 提交速度取满旋转管线的步进上限（见 RotationUtils.smooth），使 Polar 自己算出的逐刻步长
    // 原样生效，不被旋转管线按速度再次截断。
    private static final double POLAR_ROTATION_SPEED = 180.0;

    private final TimerUtils switchTimer = new TimerUtils();

    @Override
    public String getInfo() {
        return target == null ? null : target.getName().getString();
    }

    @Override
    protected void onDisable() {
        resetState();
        releaseAiRotation();
        DeobfESP.retainRisingEffects();
    }

    /**
     * AI 模式把「模型步长」当作速度提交给旋转管线，收敛时该值趋近 0；而
     * {@link RotationManager} 会把这个速度复用为「托管角 lerp 回玩家视角」的速率。
     * 若不重新提交，{@link RotationUtils#smooth} 的鼠标灵敏度网格量化会把亚网格步长
     * 舍入为 0，托管角便永久停在目标方向，头再也转不回来。
     *
     * <p>这里以模块设定的角速度、玩家当前视角为目标提交一次，使托管角正常收敛，
     * 随后由 {@code RotationManager.onSendPosition} 的归位判据释放。
     * 普通模式提交的本来就是正常速度，无需干预。</p>
     */
    private void releaseAiRotation() {
        if (!aimMode.is(AimMode.Ai) || mc.player == null) return;

        RotationManager.INSTANCE.setRotations(
                new Rot2f(mc.player.getYRot(), mc.player.getXRot()),
                rotationSpeed.getValue(),
                rotationPriority.getValue()
        );
    }

    @EventHandler
    private void onClientTick(ClientTickEvent.Pre event) {
        if (nullCheck()) return;

        if (!esp.getValue() || !espMode.is(ESPMode.Deobf)) {
            DeobfESP.clear();
        }

        if (pauseOnScaffold.getValue() && Scaffold.INSTANCE.isEnabled()) {
            resetState();
            return;
        }

        targets = new ArrayList<>(TargetManager.INSTANCE.acquireTargets(TargetRequest.of(
                searchRange.getValue(),
                fov.getValue().floatValue(),
                players.getValue(),
                mobs.getValue(),
                animals.getValue(),
                villagers.getValue(),
                ambient.getValue(),
                water.getValue(),
                others.getValue(),
                invisible.getValue(),
                64
        )));

        Velocity velocity = Velocity.INSTANCE;

        if (velocity.delay) {
            targets.sort(
                    Comparator.comparingDouble(o -> (double) Math.abs(velocity.yaw - RotationUtils.calculate(o).getYaw()))
            );
        }

        switch (targetMode.getValue()) {
            case Single -> targetIndex = 0;
            case Switch -> {
                if (switchTimer.passedMillise(switchDelay.getValue())) {
                    switchTimer.reset();
                    if (++targetIndex >= targets.size()) {
                        targetIndex = 0;
                    }
                }
            }
        }

        if (targetIndex >= targets.size()) {
            targetIndex = 0;
        }

        if (targets.isEmpty()) {
            target = null;
            return;
        }

        switch (priorityMode.getValue()) {
            case Range -> targets.sort(Comparator.comparingDouble(o -> (double) o.distanceTo(mc.player)));
            case Fov -> {
                targets.sort(Comparator.comparingDouble(o -> (double) Math.abs(Mth.wrapDegrees(mc.player.getXRot() - RotationUtils.calculate(o).getYaw()))));
            }
            case Health -> {
                targets.sort(Comparator.comparingDouble(o -> o instanceof LivingEntity living ? (double) living.getHealth() : 0.0));
            }
        }

        target = targets.get(targetIndex);

        if (aimMode.is(AimMode.Ai)) {
            if (!applyAiRotation(target)) return;
        } else if (aimMode.is(AimMode.Polar)) {
            if (!applyPolarRotation(target)) return;
        } else {
            Rot2f calculate = calculateAimRotation(target);
            if (calculate == null) return;
            RotationManager.INSTANCE.setRotations(calculate, rotationSpeed.getValue(), rotation -> RaytraceUtils.raytrace(rotation, 3.0f) instanceof EntityHitResult entityHitResult && entityHitResult.getEntity() == target, rotationPriority.getValue());
        }

        // Polar 的点击节奏按刻推进，与 1.8 秒表 / 1.9+ 蓄力两条既有路径互斥
        if (aimMode.is(AimMode.Polar)) {
            advancePolarClicks();
        }

        HitResult hitResult = RotationManager.INSTANCE.getHitResult();
        if (!aimMode.is(AimMode.Polar) && hitSelect.getValue() && hitResult instanceof EntityHitResult entityHitResult && entityHitResult.getEntity() instanceof Player player && !AntiBot.INSTANCE.isBot(player) && !TargetManager.INSTANCE.isSameTeam(player) && velocity.attackQueue <= 0) {
            ClientPacketListener connection = mc.getConnection();
            PlayerInfo localPlayerInfo = connection == null ? null : connection.getPlayerInfo(mc.player.getUUID());
            int latencyTicks = localPlayerInfo == null ? 0 : localPlayerInfo.getLatency() / 50;
            if (player.hurtTime <= latencyTicks + 1 || (mc.player.hurtTime >= 6 && !Velocity.INSTANCE.isEnabled()) || Criticals.INSTANCE.fallTicks == 2) {
                switch (mode.getValue()) {
                    case OnePointNinePlus -> {
                        if (attacks == 0 && mc.player.getAttackStrengthScale(0.5f) >= 1.0f) {
                            attacks++;
                        }
                    }
                    case OnePointEight -> {
                        long time = System.currentTimeMillis();
                        if (time - lastAttackTime >= (long) (1000.0 / cps.getValue())) {
                            attacks++;
                            lastAttackTime = time;
                        }
                    }
                }
            }
        }
    }

    @EventHandler
    private void onPlayerTick(PlayerTickEvent.Pre event) {
        HitResult hitResult = RotationManager.INSTANCE.getHitResult();
        while (attacks > 0) {
            attacks--;
            if (pauseOnEat.getValue() && PlayerUtils.isEating() || NoSlowdown.INSTANCE.isWorking()) return;
            if (hitResult instanceof EntityHitResult entityHitResult) {
                Entity entity = entityHitResult.getEntity();
                if (!entity.isAlive()) return;

                mc.gameMode.attack(mc.player, entity);

                if (espMode.is(ESPMode.Deobf)) DeobfESP.markHit(entity);

                if (swingHand.getValue()) {
                    mc.player.swing(InteractionHand.MAIN_HAND);
                } else {
                    mc.getConnection().send(new ServerboundSwingPacket(InteractionHand.MAIN_HAND));
                }
            }
        }
    }

    @EventHandler
    private void onRender3D(Render3DEvent event) {
        // Polar 的点击节奏在 onClientTick 按刻推进；渲染事件是逐帧的，不能作为其计时基准
        if (target != null && !aimMode.is(AimMode.Polar) && Velocity.INSTANCE.attackQueue <= 0) {
            HitResult hitResult = RotationManager.INSTANCE.getHitResult();
            if (!hitSelect.getValue() || !(hitResult instanceof EntityHitResult entityHitResult && entityHitResult.getEntity() instanceof Player)) {
                switch (mode.getValue()) {
                    case OnePointNinePlus -> {
                        if (attacks == 0 && mc.player.getAttackStrengthScale(0.5f) >= 1.0f) {
                            attacks++;
                        }
                    }
                    case OnePointEight -> {
                        long time = System.currentTimeMillis();
                        if (time - lastAttackTime >= (long) (1000.0 / cps.getValue())) {
                            attacks++;
                            lastAttackTime = time;
                        }
                    }
                }
            }
        }

        if (!esp.getValue() || espMode.is(ESPMode.Deobf) || target == null) return;

        PoseStack stack = event.getPoseStack();

        switch (espMode.getValue()) {
            case CaptureMark -> {
                CaptureMarkESP.render(
                        stack,
                        target,
                        espSize.getValue(),
                        espRotSpeed.getValue(),
                        waveSpeed.getValue(),
                        espColor1.getValue(),
                        espColor2.getValue()
                );
            }
            case Circle -> {
                CircleESP.render(
                        stack,
                        target,
                        circleRadius.getValue().floatValue(),
                        sideColor.getValue(),
                        lineColor.getValue(),
                        circleAlphaFactor.getValue().floatValue()
                );
            }
            case Firefly -> {
                FireflyESP.render(
                        stack,
                        target,
                        fireflyLength.getValue(),
                        fireflyFactor.getValue(),
                        fireflyShaking.getValue(),
                        fireflyAmplitude.getValue(),
                        fireflyColor.getValue(),
                        fireflyColorMode.getValue(),
                        fireflyColor2.getValue(),
                        fireflyColorMix.getValue(),
                        fireflyColorSpeed.getValue(),
                        fireflyRainbowSpeed.getValue(),
                        fireflyRainbowSaturation.getValue(),
                        fireflyRainbowBrightness.getValue()
                );
            }
        }
    }

    /**
     * AI 转向模式：由捆绑的 MLP 战斗回归模型直接输出 yaw/pitch 增量，
     * 取代普通模式下「直接设定目标角度」的做法。
     *
     * <p>输入布局镜像训练样本 CombatSample：
     * [yaw 误差, pitch 误差, yaw 角速度, pitch 角速度, 玩家+目标水平速度, 距离]。
     * 模型输出为当前刻应施加的角度增量（度）。</p>
     *
     * <p>与源实现的差异：源走 {@code applyAiRotationDelta} 的鼠标增量空间并短路控制器速度逻辑；
     * 本移植映射为「绝对目标 + 速度」提交。不传射线命中谓词，因而跳过
     * {@code RotationManager.smooth()} 中依赖谓词的伪装抖动分支；
     * 但仍会经过 {@link RotationUtils#smooth} 的灵敏度量化与亚度级噪声（全局旋转管线行为）。</p>
     *
     * <p>回退行为：模型不可用或输出非法时，源实现保持当前角不动；本移植改为提交普通转向
     * （可用性优先），属刻意适配。模型资源缺失时管理器会输出一次警告。</p>
     *
     * @return {@code true} 表示本刻转向已处理；{@code false} 表示被方块遮挡，
     * 调用方应中止整个 tick（与普通模式行为一致）
     */
    private boolean applyAiRotation(LivingEntity aimTarget) {
        // 先采样托管角与角速度：该采样与是否被遮挡无关，放在遮挡早退之前可保证
        // 快照逐刻连续，避免目标消失/被遮挡数秒后恢复时喂入跨间隔的虚假角速度。
        Rot2f current = RotationManager.INSTANCE.getRotation();
        float managedYaw = current.getYaw();
        float managedPitch = current.getPitch();

        // 角速度特征只在「上一刻刚采样过」时可信；否则（跳刻/重生/目标消失）取 0。
        int nowTick = mc.player.tickCount;
        boolean velocityFresh = previousManagedTick != Integer.MIN_VALUE && nowTick - previousManagedTick == 1;
        float velocityYaw = velocityFresh ? Mth.wrapDegrees(managedYaw - previousManagedYaw) : 0.0f;
        float velocityPitch = velocityFresh ? managedPitch - previousManagedPitch : 0.0f;
        previousManagedYaw = managedYaw;
        previousManagedPitch = managedPitch;
        previousManagedTick = nowTick;

        Rot2f desired = RotationUtils.calculate(aimTarget, true, aimRange.getValue());
        if (RaytraceUtils.raytrace(desired, aimRange.getValue()).getType() == HitResult.Type.BLOCK) return false;

        float deltaYaw = Mth.wrapDegrees(desired.getYaw() - managedYaw);
        float deltaPitch = Mth.wrapDegrees(desired.getPitch() - managedPitch);

        double playerSpeed = mc.player.getDeltaMovement().horizontal().length();
        double targetSpeed = aimTarget.getDeltaMovement().horizontal().length();
        float speedFeature = (float) (playerSpeed + targetSpeed);
        // 源用 computeAimCoords 返回的目标「脚部」坐标做 player.i(x,y,z)，即脚-脚距离；
        // Entity.distanceTo 与之同构（脚点欧氏距离）。
        float distanceFeature = mc.player.distanceTo(aimTarget);

        float[] input = {deltaYaw, deltaPitch, velocityYaw, velocityPitch, speedFeature, distanceFeature};

        // 仅在目标模型与当前激活模型不一致时才切换，避免每 tick 的字符串分配与加锁。
        String modelName = aiModel.is(AiModel.Model19KC8KP) ? "19KC8KP" : "21KC11KP";
        if (!modelName.equalsIgnoreCase(AiRotationModelManager.INSTANCE.getActiveName())) {
            AiRotationModelManager.INSTANCE.ensureReady();
            AiRotationModelManager.INSTANCE.setActiveModel(modelName);
        }
        float[] output = AiRotationModelManager.INSTANCE.predictSafe(input);

        if (output == null || output.length < 2 || !Float.isFinite(output[0]) || !Float.isFinite(output[1])) {
            RotationManager.INSTANCE.setRotations(desired, rotationSpeed.getValue(), rotation -> RaytraceUtils.raytrace(rotation, 3.0f) instanceof EntityHitResult entityHitResult && entityHitResult.getEntity() == aimTarget, rotationPriority.getValue());
            return true;
        }

        float yawStep = output[0] * aiYawMultiplier.getValue().floatValue();
        float pitchStep = output[1] * aiPitchMultiplier.getValue().floatValue();
        Rot2f stepTarget = new Rot2f(managedYaw + yawStep, Mth.clamp(managedPitch + pitchStep, -90.0f, 90.0f));

        // 位移距离必须按 smooth() 内部同一基准（lastRotations）计算，
        // 否则提交的速度与实际位移不符。
        Rot2f moveBase = RotationManager.INSTANCE.lastRotations;
        double stepDistance = Math.hypot(
                Mth.wrapDegrees(stepTarget.getYaw() - moveBase.getYaw()),
                stepTarget.getPitch() - moveBase.getPitch()
        );
        // 零步长时 RotationUtils.move 会出现 0/0 的 NaN 分配，且本身无需转向。
        if (!(stepDistance > 1.0e-6)) return true;

        // 以等于位移距离的速度提交，等价于本刻全额施加模型增量。
        // 不传射线命中谓词：跳过 RotationManager.smooth() 中依赖谓词的抖动分支
        // （RotationUtils.smooth 的灵敏度量化与亚度级噪声仍会生效）。
        RotationManager.INSTANCE.setRotations(stepTarget, stepDistance, rotationPriority.getValue());
        return true;
    }

    /**
     * 计算普通（Normal）瞄准的目标旋转：自适应瞄准点 + 方块遮挡检查。
     *
     * <p>Normal 与 Polar 共用这一段，确保两种模式瞄准的是同一个角度，
     * Polar 只改变「如何逼近该角度」，不改变目标本身。</p>
     *
     * @return 目标旋转；被方块遮挡时返回 {@code null}，调用方应中止整个 tick
     */
    private Rot2f calculateAimRotation(Entity aimTarget) {
        Rot2f calculate = RotationUtils.calculate(aimTarget, true, aimRange.getValue());
        if (RaytraceUtils.raytrace(calculate, aimRange.getValue()).getType() == HitResult.Type.BLOCK) return null;
        return calculate;
    }

    /**
     * Polar 转向模式：移植 LiquidBounce 的 AccelerationAngleSmooth（误差注入关闭）。
     *
     * <p>与普通模式「直接设定目标角 + 速度上限」不同，这里把上一刻的步长当作动量：
     * 先取本刻与目标角的残差，用区间内随机取的加速度上限夹取残差得到本刻加速度，
     * 本刻步长 = 上刻步长 + 加速度。角差越大步长越大，接近目标时残差反向而自然减速，
     * 因此形成加速曲线而非恒速逼近。</p>
     *
     * <p>提交速度为 {@link #POLAR_ROTATION_SPEED}（旋转管线的步进上限），使本方法算出的
     * 逐步旋转原样生效，不被管线二次平滑。旋转优先级固定 {@link Priority#High}。</p>
     *
     * @return {@code true} 表示本刻转向已处理；{@code false} 表示被方块遮挡，
     * 调用方应中止整个 tick（与普通模式行为一致）
     */
    private boolean applyPolarRotation(Entity aimTarget) {
        Rot2f current = RotationManager.INSTANCE.getRotation();
        Rot2f wanted = calculateAimRotation(aimTarget);
        if (wanted == null) return false;

        // 目标切换或跳刻后，上一刻步长不再是有效的动量依据，清零以免首刻跳变
        int nowTick = mc.player.tickCount;
        if (polarLastTarget != aimTarget || polarLastTick == Integer.MIN_VALUE || nowTick - polarLastTick != 1) {
            polarPrevYawDelta = 0.0f;
            polarPrevPitchDelta = 0.0f;
        }
        polarLastTarget = aimTarget;
        polarLastTick = nowTick;

        float diffYaw = Mth.wrapDegrees(wanted.getYaw() - current.getYaw());
        float diffPitch = wanted.getPitch() - current.getPitch();

        float maxYawAcceleration = (float) MathUtils.getRandom(polarYawAccelerationMin.getValue().doubleValue(), polarYawAccelerationMax.getValue().doubleValue());
        float maxPitchAcceleration = (float) MathUtils.getRandom(polarPitchAccelerationMin.getValue().doubleValue(), polarPitchAccelerationMax.getValue().doubleValue());

        float accelerationYaw = Mth.clamp(Mth.wrapDegrees(diffYaw - polarPrevYawDelta), -maxYawAcceleration, maxYawAcceleration);
        float accelerationPitch = Mth.clamp(diffPitch - polarPrevPitchDelta, -maxPitchAcceleration, maxPitchAcceleration);

        float nextYaw = current.getYaw() + polarPrevYawDelta + accelerationYaw;
        float nextPitch = Mth.clamp(current.getPitch() + polarPrevPitchDelta + accelerationPitch, -90.0f, 90.0f);

        // 旋转管线以 lastRotations 为基准，提交与基准完全相同的角度会让 RotationUtils.move
        // 出现 0/0 的 NaN（本模型收敛后步长恰为 0，会稳定命中该情形，而 NaN 会一直留在托管角里
        // 直到重生）。偏一个 ULP 量级的微小量即可避开，灵敏度量化会把它舍入回原位，观感仍是原地不动。
        if (!(polarSubmittedDelta(nextYaw, nextPitch) > 1.0e-6)) {
            nextYaw += Math.max(1.0e-3f, Math.ulp(nextYaw) * 4.0f);
        }

        polarPrevYawDelta = Mth.wrapDegrees(nextYaw - current.getYaw());
        polarPrevPitchDelta = nextPitch - current.getPitch();

        RotationManager.INSTANCE.setRotations(new Rot2f(nextYaw, nextPitch), POLAR_ROTATION_SPEED, Priority.High);
        return true;
    }

    /**
     * 该角度相对旋转管线基准（{@link RotationManager#lastRotations}）的位移量，
     * 用于识别会触发 {@link RotationUtils#move} 中 0/0 的零位移提交。
     */
    private double polarSubmittedDelta(float yaw, float pitch) {
        Rot2f moveBase = RotationManager.INSTANCE.lastRotations;
        return Math.hypot(Mth.wrapDegrees(yaw - moveBase.getYaw()), pitch - moveBase.getPitch());
    }

    /**
     * Polar 瞄准模式的点击节奏：每刻以区间内随机 CPS 推进累加器（与 {@code MultiAura} 同一写法），
     * 整点击落累加到既有的 {@link #attacks} 计数上，由 {@code onPlayerTick} 统一消费。
     *
     * <p>开启 {@code Polar Ignore Attack Cooldown} 时不看攻击冷却；否则要求
     * {@code getAttackStrengthScale(0.0f)} 达到 {@code Polar Cooldown Min~Max} 内的随机阈值，
     * 未达标的点落被丢弃，因此实际频率只会低于设定 CPS。</p>
     */
    private void advancePolarClicks() {
        if (target == null || Velocity.INSTANCE.attackQueue > 0) return;

        polarClickAccumulator += MathUtils.getRandom(polarClickMinCps.getValue(), polarClickMaxCps.getValue()) / 20.0;

        while (polarClickAccumulator >= 1.0) {
            polarClickAccumulator -= 1.0;
            if (polarIgnoreAttackCooldown.getValue()
                    || mc.player.getAttackStrengthScale(0.0f) >= MathUtils.getRandom(polarCooldownMin.getValue().doubleValue(), polarCooldownMax.getValue().doubleValue())) {
                attacks++;
            }
        }
    }

    private void resetState() {
        targets = null;
        target = null;
        attacks = 0;
        lastAttackTime = 0L;
        previousManagedTick = Integer.MIN_VALUE;
        polarClickAccumulator = 0.0;
        polarPrevYawDelta = 0.0f;
        polarPrevPitchDelta = 0.0f;
        polarLastTarget = null;
        polarLastTick = Integer.MIN_VALUE;
    }

    /**
     * 重生/换维度后玩家实体重建、{@code RotationManager} 会将托管旋转归零，
     * 此处同步作废角速度快照，避免复活首刻喂入跨间隔的虚假角速度。
     */
    @EventHandler
    private void onRespawn(RespawnEvent event) {
        previousManagedTick = Integer.MIN_VALUE;
    }

}
