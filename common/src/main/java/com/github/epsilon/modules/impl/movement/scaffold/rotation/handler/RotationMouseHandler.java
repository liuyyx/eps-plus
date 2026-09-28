/*
 * This file is part of Epsilon.
 *
 * 照抄自 OpenPal（https://github.com/OpenPal）MC 1.21.10 的
 * wtf.opal.client.feature.helper.impl.player.rotation.handler.RotationMouseHandler。
 *
 * <p><b>全移植里唯一一处不能"只改版本"的地方（§2 例外 1），说明如下：</b>
 * OpenPal 的落地方式是伪造鼠标增量 —— 它的 {@code MouseMixin} 把 {@code Mouse.updateMouse}
 * 读的 {@code cursorDeltaX/cursorDeltaY} 换成这里的 {@code event.setDeltaX/setDeltaY}，
 * 再交给原版 {@code player.turnPlayer(...)} 真正改玩家视角；{@code reset()} 时再把进入前的
 * {@code yaw/pitch/bodyYaw/headYaw} 还原。
 * 本仓库既没有鼠标注入事件、搭路也要求视角不动，所以这里保留 handler 的<b>状态机与模型推进</b>
 * （{@code model.tick(from, to, delta)} 逐刻插值、{@code originalRotation}/{@code reverse()} 语义），
 * 只把最后一步的落地换成 {@link RotationManager#setRotationsDirect}（静默托管：服务器看到的角一致，视角不动）。</p>
 *
 * <p>随之而来的三处同源调整（都属于同一例外）：</p>
 * <ul>
 *   <li>两个 OpenPal 订阅点保留原名与方法体（{@link #onPreTick()} ←
 *       {@code @Subscribe(priority = 8) onPreTick(PreGameTickEvent)}；
 *       {@link #onMouseUpdate()} ← {@code @Subscribe onMouseUpdate(MouseUpdateEvent)}），
 *       由 {@code Scaffold} 的 Uitems 分流按 OpenPal 的同一顺序逐刻调用：
 *       {@code onPreTick()} → 模式类的 {@code onPreTick(...)} → {@code onMouseUpdate()}。
 *       顺序不能颠倒 —— {@code reverse()} 必须先清掉上一刻的请求、模式的 {@code rotate(...)}
 *       再重新下请求、最后才推进模型；否则 {@code reverse()} 会把本刻刚下的目标冲掉。</li>
 *   <li>{@code forceTick()} 里 OpenPal 调的 {@code mc.mouse.tick()} 在 26.2 不存在
 *       （{@code MouseHandler} 只有 {@code handleAccumulatedMovement()}），且本仓库不走鼠标通道；
 *       只保留 {@code ticked} 置位语义 —— 紧随其后的 {@link #onMouseUpdate()} 会以
 *       {@code tickDelta = 1} 走一步，与 OpenPal 那次强制鼠标 tick 等价。</li>
 *   <li>托管角必须显式释放：OpenPal 靠"还原玩家真实旋转"结束托管，本仓库没有这一步，
 *       不释放会让 {@code RotationManager} 永久 active（准星与 {@code MovementFix} 停在旧角，
 *       表现就是"取消搭路后视角回不来"）。</li>
 * </ul>
 */
package com.github.epsilon.modules.impl.movement.scaffold.rotation.handler;

import com.github.epsilon.managers.rotation.RotationManager;
import com.github.epsilon.modules.impl.movement.scaffold.rotation.RotationHelper;
import com.github.epsilon.modules.impl.movement.scaffold.rotation.model.IRotationModel;
import com.github.epsilon.utils.player.RotationUtility;
import com.github.epsilon.utils.rotation.Priority;
import com.github.epsilon.utils.rotation.Rot2f;

import static com.github.epsilon.Constants.mc;

public final class RotationMouseHandler {

    public RotationMouseHandler() {
    }

    private IRotationModel rotationModel;
    private Rot2f targetRotation;
    private boolean active, forward;
    private Rot2f originalRotation;

    private Rot2f tickRotation;
    private boolean ticked, unlockCursor;

    /** OpenPal {@code @Subscribe(priority = 8) onPreTick(PreGameTickEvent)}。 */
    public void onPreTick() {
        if (mc.player == null) {
            return;
        }

        this.forceTick();
        this.reverse();

        this.setTickRotation(RotationUtility.getRotation());
        this.unlockCursor = false;
    }

    /**
     * OpenPal {@code @Subscribe onMouseUpdate(MouseUpdateEvent)} 的对应物。
     *
     * <p>OpenPal 里这个事件每帧派发一次，{@code ClientRotationHandler}（priority = 1）先于
     * 本处理器收到 ⇒ 这里按同一顺序先推进客户端角，再推进托管角。</p>
     */
    public void onMouseUpdate() {
        RotationHelper.getClientHandler().onMouseUpdate();

        if (this.tickRotation == null || this.targetRotation == null || mc.player == null || !this.active) {
            this.ticked = false;
            RotationHelper.getClientHandler().onPostMouseUpdate();
            return;
        }

        if (this.originalRotation != null) {
            // [适配] OpenPal 在此把本帧鼠标增量累加进 originalRotation（它自己改写过玩家视角，
            //        需要一份"未被模块改过"的副本）。本仓库视角从不被改写 ⇒ 真实视角即该副本。
            this.originalRotation = new Rot2f(mc.player.getYRot(), mc.player.getXRot());
        }

        if (!this.forward) {
            this.resetToClient();
            if (this.targetRotation == null) {
                this.ticked = false;
                RotationHelper.getClientHandler().onPostMouseUpdate();
                return;
            }
        }

        float tickDelta;
        if (this.ticked) { // Fixes rotation tick interpolation since tickDelta otherwise never reaches 1
            tickDelta = 1.F;
            this.ticked = false;
        } else {
            tickDelta = mc.getDeltaTracker().getGameTimeDeltaPartialTick(false);
        }
        final Rot2f tickedRotation = this.rotationModel.tick(this.tickRotation, this.targetRotation, tickDelta);

        // [适配·唯一替换点] OpenPal 把结果换算成鼠标增量（getCursorDelta）交给原版 turnPlayer；
        //                   本仓库直接提交静默托管角。
        RotationManager.INSTANCE.setRotationsDirect(tickedRotation, Priority.Medium);

        if (!this.forward && RotationUtility.getRotationDifference(tickedRotation, this.targetRotation) == 0.D) {
            this.rotationModel = null;
            this.targetRotation = null;
            this.active = false;
            // [适配] 见类注释：托管角必须显式释放。
            if (RotationManager.INSTANCE.isActive()) {
                RotationManager.INSTANCE.setActive(false);
            }
        }

        RotationHelper.getClientHandler().onPostMouseUpdate();
    }

    public boolean isUnlockCursor() {
        return this.unlockCursor && this.ticked;
    }

    public void setTickRotation(Rot2f tickRotation) {
        this.tickRotation = tickRotation;
    }

    public void reverse() {
        if (this.forward) {
            this.resetToClient();
            this.forward = false;
        }
    }

    private void forceTick() {
        if (this.active) {
            this.ticked = true;
            // [适配] OpenPal 此处调 mc.mouse.tick() 以在游戏刻内多跑一次鼠标更新；
            //        26.2 的 MouseHandler 没有该入口，且本仓库不走鼠标通道。
            //        ticked 由紧随其后的 onMouseUpdate() 消费（tickDelta = 1）。
        }
    }

    private void resetToClient() {
        final ClientRotationHandler clientHandler = RotationHelper.getClientHandler();
        this.targetRotation = clientHandler.getRotation();
    }

    /** OpenPal {@code RotationMouseHandler.reset()}。视角从未被改写，故只清状态 + 释放托管。 */
    public void reset() {
        this.active = false;
        this.forward = false;
        this.targetRotation = null;
        this.rotationModel = null;
        this.unlockCursor = false;
        this.originalRotation = null;
        if (RotationManager.INSTANCE.isActive()) {
            RotationManager.INSTANCE.setActive(false);
        }
    }

    public void rotate(Rot2f targetRotation, IRotationModel rotationModel) {
        if (!this.active && mc.player != null) {
            this.originalRotation = new Rot2f(mc.player.getYRot(), mc.player.getXRot());
        }
        this.targetRotation = targetRotation;
        this.rotationModel = rotationModel;
        this.forward = true;
        this.active = true;
        this.forceTick();
    }

    public void unlockCursor() {
        this.unlockCursor = true;
    }

    public Rot2f getTargetRotation() {
        return targetRotation;
    }

    public IRotationModel getRotationModel() {
        return rotationModel;
    }

    public boolean isActive() {
        return active;
    }

    public boolean isForward() {
        return forward;
    }
}
