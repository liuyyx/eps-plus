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

    private final TimerUtils switchTimer = new TimerUtils();

    @Override
    public String getInfo() {
        return target == null ? null : target.getName().getString();
    }

    @Override
    protected void onDisable() {
        resetState();
        DeobfESP.retainRisingEffects();
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
        } else {
            Rot2f calculate = RotationUtils.calculate(target, true, aimRange.getValue());
            if (RaytraceUtils.raytrace(calculate, aimRange.getValue()).getType() == HitResult.Type.BLOCK) return;
            RotationManager.INSTANCE.setRotations(calculate, rotationSpeed.getValue(), rotation -> RaytraceUtils.raytrace(rotation, 3.0f) instanceof EntityHitResult entityHitResult && entityHitResult.getEntity() == target, rotationPriority.getValue());
        }

        HitResult hitResult = RotationManager.INSTANCE.getHitResult();
        if (hitSelect.getValue() && hitResult instanceof EntityHitResult entityHitResult && entityHitResult.getEntity() instanceof Player player && !AntiBot.INSTANCE.isBot(player) && !TargetManager.INSTANCE.isSameTeam(player) && velocity.attackQueue <= 0) {
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
        if (target != null && Velocity.INSTANCE.attackQueue <= 0) {
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

    private void resetState() {
        targets = null;
        target = null;
        attacks = 0;
        lastAttackTime = 0L;
        previousManagedTick = Integer.MIN_VALUE;
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
