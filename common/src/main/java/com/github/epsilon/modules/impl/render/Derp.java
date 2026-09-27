package com.github.epsilon.modules.impl.render;

import com.github.epsilon.events.bus.EventHandler;
import com.github.epsilon.events.impl.ClientTickEvent;
import com.github.epsilon.events.impl.PlayerTickEvent;
import com.github.epsilon.managers.rotation.RotationManager;
import com.github.epsilon.modules.Category;
import com.github.epsilon.modules.Module;
import com.github.epsilon.settings.impl.BoolSetting;
import com.github.epsilon.settings.impl.DoubleSetting;
import com.github.epsilon.settings.impl.EnumSetting;
import com.github.epsilon.settings.impl.IntSetting;
import com.github.epsilon.utils.math.MathUtils;
import com.github.epsilon.utils.rotation.Priority;
import com.github.epsilon.utils.rotation.Rot2f;
import net.minecraft.util.Mth;

/**
 * LB: {@code features/module/modules/fun/ModuleDerp.kt}（CCBlueX/LiquidBounce nextgen，GPLv3）。
 * <p>
 * LB 原文的 {@code RotationsValueGroup} 走的是 LB 自己的角度平滑引擎（AngleSmooth / MovementCorrection）；
 * Epsilon 侧 {@link RotationManager} 的对等物是 {@code rotationSpeed} + {@link Priority}，且它内置
 * {@code RotationUtils.smooth} 的限速、灵敏度网格量化与抖动分支 —— 对 derp 那种逐刻大角度跳变而言，
 * 走 {@code setRotations} 会被削平成平滑弧线，功能直接失效。故此处改走
 * {@link RotationManager#setRotationsDirect}：算出来什么就发什么。
 * <p>
 * LB 原文的 {@code tickHandler}/{@code waitTicks} 协程（{@code YawJitter} / {@code YawSpin} 用）在本仓库没有
 * 对应物，改写成 {@link PlayerTickEvent.Pre} 里的计数状态机。
 */
public class Derp extends Module {

    public static final Derp INSTANCE = new Derp();

    private Derp() {
        super("Derp", Category.RENDER);
    }

    /** LB: {@code YawStatic} / {@code YawOffset} / {@code YawRandom} / {@code YawJitter} / {@code YawSpin}。 */
    public enum YawMode {
        Static,
        Offset,
        Random,
        Jitter,
        Spin
    }

    /** LB: {@code PitchStatic} / {@code PitchOffset} / {@code PitchRandom}。 */
    public enum PitchMode {
        Static,
        Offset,
        Random
    }

    private final EnumSetting<YawMode> yawMode = enumSetting("Yaw", YawMode.Random);
    private final EnumSetting<PitchMode> pitchMode = enumSetting("Pitch", PitchMode.Random);
    private final BoolSetting safePitch = boolSetting("Safe Pitch", true);
    private final BoolSetting notDuringSprint = boolSetting("Not During Sprint", true);

    // "Yaw"/"Pitch" 已被上方的模式选择器占用（i18n key 是设置名的小写，重名会共用同一个翻译节点），故加 Value 后缀。
    private final DoubleSetting yawValue = doubleSetting("Yaw Value", 0.0, -180.0, 180.0, 1.0, () -> yawMode.is(YawMode.Static));
    private final DoubleSetting yawOffsetValue = doubleSetting("Yaw Offset", 0.0, -180.0, 180.0, 1.0, () -> yawMode.is(YawMode.Offset));
    private final IntSetting jitterForwardTicks = intSetting("Forward Ticks", 2, 0, 100, 1, () -> yawMode.is(YawMode.Jitter));
    private final IntSetting jitterBackwardTicks = intSetting("Backward Ticks", 2, 0, 100, 1, () -> yawMode.is(YawMode.Jitter));
    private final IntSetting spinSpeed = intSetting("Spin Speed", 50, -70, 70, 1, () -> yawMode.is(YawMode.Spin));

    private final DoubleSetting pitchValue = doubleSetting("Pitch Value", -90.0, -180.0, 180.0, 1.0, () -> pitchMode.is(PitchMode.Static));
    private final DoubleSetting pitchOffsetValue = doubleSetting("Pitch Offset", 0.0, -180.0, 180.0, 1.0, () -> pitchMode.is(PitchMode.Offset));

    /** {@code YawJitter} 的段内剩余刻数；{@code jitterForward} 标记当前处于正向段（LB 的 {@code repeat} 两段交替）。 */
    private int jitterTick;
    private boolean jitterForward;

    /** {@code YawSpin} 的累积角；{@link Rot2f} 不做归一化，必须自己 wrap，否则几刻后就会溢出 360。 */
    private float spinYaw;

    /** 本模块是否已提交过托管角；决定跳过提交时要不要主动把 {@link RotationManager} 复位。 */
    private boolean submitted;

    /** 上一次"前进键按下沿"所在的刻（用于预判双击 W 疾跑）。用 -1000 而不是 MIN_VALUE，避免相减溢出假阳性。 */
    private int forwardPressTick = -1000;
    private boolean forwardWasDown;
    /** 双击疾跑窗口的截止刻；截止前视为"正在进入疾跑"。 */
    private int doubleTapUntil = -1000;

    @Override
    protected void onEnable() {
        submitted = false;
        // LB 的 YawJitter / YawSpin 是 tickHandler 协程，enable 时重启（段从 ForwardTicks 起、累积角归零）；
        // 这里的状态是普通字段，必须自己复位，否则会带着上次的残值进场。
        jitterTick = 0;
        jitterForward = false;
        spinYaw = 0.0F;
        forwardPressTick = -1000;
        forwardWasDown = false;
        doubleTapUntil = -1000;
    }

    @Override
    protected void onDisable() {
        releaseRotation();
    }

    /**
     * 早于 {@code Minecraft.handleKeybinds} 的让路检查。
     *
     * <p>26.2 的 {@code ServerboundUseItemPacket} 是在 {@code handleKeybinds} 里构造的，且它自带
     * yaw/pitch（{@code SilentRotationManager.onPacketSend} 会把它改成托管角），此时玩家 tick 还没跑 ——
     * 只在 {@link PlayerTickEvent.Pre} 里让路会晚一整刻，拉弓/投掷/放置仍会带着上一刻的 derp 角发出去。
     * {@link ClientTickEvent.Pre} 在 {@code Minecraft.tick} 入口（= {@code handleKeybinds} 之前）派发，
     * 在这里回真实视角才能保证同一刻构造的动作包用的是真实角。</p>
     */
    @EventHandler
    private void onClientTick(ClientTickEvent.Pre event) {
        if (nullCheck()) return;
        updateForwardDoubleTap();
        if (shouldYield()) {
            releaseRotation();
        }
    }

    /**
     * 预判"本刻即将进入疾跑"。
     *
     * <p>原版 26.2 的疾跑是在 {@code LocalPlayer.aiStep} <b>内部</b>才置位的
     * （{@code if (sprintTriggerTime > 0) setSprinting(true)} / {@code keyPresses.sprint()}），
     * 而双击 W 这条路径连 {@code keySprint.isDown()} 都是 false —— 只看这两个信号的话，
     * 疾跑开始的那一拍（正好是起步/起跳那一拍）仍会被 derp 带偏。
     * 这里用前进键按下沿自己复现原版的双击窗口（{@code options.sprintWindow()}，默认 7 刻）：
     * 第二拍在 {@link ClientTickEvent.Pre}（tick 开头）就能判定，比原版置位早一整拍。</p>
     */
    private void updateForwardDoubleTap() {
        boolean down = mc.options.keyUp.isDown();
        if (down && !forwardWasDown) {
            int now = mc.player.tickCount;
            if (now - forwardPressTick <= mc.options.sprintWindow().get()) {
                doubleTapUntil = now + mc.options.sprintWindow().get();
            }
            forwardPressTick = now;
        }
        forwardWasDown = down;
    }

    /**
     * 让路条件：本模块要"服务器与第三人称看得见乱转，但不影响任何交互动作"，
     * 故凡是玩家自己在发起的动作期间都不许抢占托管角。
     *
     * <p>这是相对 LB 的刻意偏离：LB 的 Derp 会一直抢占 {@code RotationManager}，
     * 手动打人/射箭/投掷/放置一律被带偏。</p>
     */
    private boolean shouldYield() {
        // LB:52-55 —— NotDuringSprint（疾跑：任意触发方式都必须零滞后地让路，否则走位/起跳会被带偏）
        if (notDuringSprint.getValue()
                && (mc.options.keySprint.isDown() || mc.player.isSprinting() || mc.player.tickCount <= doubleTapUntil)) {
            return true;
        }

        // 交互让路：左键=打人/挖方块，右键=射箭/投掷/放置/搭路，isUsingItem=整个使用过程（拉弓、吃东西、举盾）
        return mc.options.keyAttack.isDown() || mc.options.keyUse.isDown() || mc.player.isUsingItem();
    }

    @EventHandler
    private void onPlayerTick(PlayerTickEvent.Pre event) {
        if (nullCheck()) {
            releaseRotation();
            return;
        }

        if (shouldYield()) {
            releaseRotation();
            return;
        }

        // LB:57-65
        float yaw = resolveYaw();
        float pitch = resolvePitch();
        if (safePitch.getValue()) {
            pitch = Mth.clamp(pitch, -90.0F, 90.0F);
        }

        RotationManager.INSTANCE.setRotationsDirect(new Rot2f(yaw, pitch), Priority.Lowest);
        submitted = true;
    }

    private float resolveYaw() {
        return switch (yawMode.getValue()) {
            case Static -> yawValue.getValue().floatValue();
            case Offset -> mc.player.getYRot() + yawOffsetValue.getValue().floatValue();
            case Random -> MathUtils.getRandom(-180.0F, 180.0F);
            case Jitter -> jitterYaw();
            case Spin -> spinYaw = Mth.wrapDegrees(spinYaw + spinSpeed.getValue());
        };
    }

    private float resolvePitch() {
        return switch (pitchMode.getValue()) {
            case Static -> pitchValue.getValue().floatValue();
            case Offset -> mc.player.getXRot() + pitchOffsetValue.getValue().floatValue();
            // LB:154 —— SafePitch 时随机区间收窄到 -90..90
            case Random -> safePitch.getValue() ? MathUtils.getRandom(-90.0F, 90.0F) : MathUtils.getRandom(-180.0F, 180.0F);
        };
    }

    /**
     * LB:101-112 {@code YawJitter} 的 {@code repeat(ForwardTicks){...}; repeat(BackwardTicks){...}} 状态机版。
     * 段内每刻重读玩家真实 yaw，段切换时按设置的刻数倒计时。
     */
    private float jitterYaw() {
        if (jitterTick <= 0) {
            jitterForward = !jitterForward;
            jitterTick = Math.max(0, (jitterForward ? jitterForwardTicks : jitterBackwardTicks).getValue());
        }

        if (jitterTick > 0) {
            jitterTick--;
        }

        return mc.player.getYRot() + (jitterForward ? 0.0F : 180.0F);
    }

    /**
     * 跳过提交时把托管角交还给玩家真实视角。
     *
     * <p>LB 侧由 {@code RotationManager} 统一处理"本刻无模块提交"；Epsilon 侧 {@code active} 没有超时机制，
     * {@link RotationManager#onSendPosition} 只在托管角与真实视角相差不足 1° 时才复位 —— derp 的角差必然远超该阈值，
     * 所以这里必须显式提交一次真实视角，否则关闭模块/疾跑期间会一直续发上一刻的 derp 角。
     */
    private void releaseRotation() {
        if (!submitted || nullCheck()) {
            submitted = false;
            return;
        }

        submitted = false;
        RotationManager.INSTANCE.setRotationsDirect(
                new Rot2f(mc.player.getYRot(), mc.player.getXRot()), Priority.Lowest);
    }

}
