/*
 * This file is part of Epsilon.
 *
 * 照抄自 OpenPal（https://github.com/OpenPal）MC 1.21.10 的
 * wtf.opal.client.feature.helper.impl.player.rotation.handler.ClientRotationHandler。
 *
 * <p><b>本仓库的两处落地差异（§2 例外 1 的一部分）：</b></p>
 * <ul>
 *   <li>OpenPal 通过 {@code MouseUpdateEvent}（由它的 {@code MouseMixin} 在
 *       {@code Mouse.updateMouse} 里派发）拿到本帧鼠标增量，累加成"玩家自己（鼠标）想看的角"。
 *       本仓库没有该通道、搭路也不允许动视角 ⇒ 改由 {@code RotationMouseHandler.tick()}
 *       每刻调用一次 {@link #onMouseUpdate()}，并把"玩家真实视角"当作这个量
 *       （本仓库的静默旋转从不改写 {@code player.getYRot/getXRot}，所以两者恒等）。</li>
 *   <li>{@code lastRenderYaw/renderYaw/lastRenderPitch/renderPitch} 在 1.21.10 是
 *       {@code ClientPlayerEntity} 的四个字段（Yarn 名）。26.2 对应位置只剩私有的
 *       {@code yRotLast/xRotLast}，公开可读的"上一刻朝向"是 {@code yRotO/xRotO}
 *       （见 {@code EntityUtils.lastRotation} 的同一处适配）⇒ 这里取
 *       {@code yRotO/getYRot()}、{@code xRotO/getXRot()} 这一对。这四个量在本仓库
 *       只有 {@code tickCamera()} 与 {@code get*RenderYaw/PitchOr} 在读，而它们的调用方
 *       （OpenPal 的 {@code HeldItemRendererMixin} 等渲染 mixin）不在本次移植范围内。</li>
 * </ul>
 */
package com.github.epsilon.modules.impl.movement.scaffold.rotation.handler;

import com.github.epsilon.modules.impl.movement.scaffold.rotation.RotationHelper;
import com.github.epsilon.utils.player.RotationUtility;
import com.github.epsilon.utils.rotation.Rot2f;

import static com.github.epsilon.Constants.mc;

public final class ClientRotationHandler {

    public ClientRotationHandler() {
    }

    private Rot2f rotation;
    private boolean ticking;

    /** OpenPal {@code @Subscribe(priority = 1) onMouseUpdate(MouseUpdateEvent)}。 */
    public void onMouseUpdate() {
        if (mc.player != null && !RotationHelper.getHandler().isUnlockCursor()) {
            if (this.rotation == null) {
                this.initializeRotation();
            }

            // [适配] OpenPal 在此按 sensitivityMultiplier 累加鼠标增量；
            //        本仓库的玩家真实视角即"模块未介入时会看到的角"。
            this.rotation = new Rot2f(mc.player.getYRot(), mc.player.getXRot());
        }
        this.ticking = true;
    }

    private void initializeRotation() {
        this.rotation = RotationUtility.getRotation();
        this.lastRenderYaw = mc.player.yRotO;
        this.renderYaw = mc.player.getYRot();
        this.lastRenderPitch = mc.player.xRotO;
        this.renderPitch = mc.player.getXRot();
    }

    private float lastRenderYaw, renderYaw;
    private float lastRenderPitch, renderPitch;

    public void tickCamera() {
        if (this.rotation != null) {
            this.lastRenderYaw = this.renderYaw;
            this.lastRenderPitch = this.renderPitch;
            this.renderPitch = this.renderPitch + (this.rotation.getPitch() - this.renderPitch) * 0.5F;
            this.renderYaw = this.renderYaw + (this.rotation.getYaw() - this.renderYaw) * 0.5F;
        }
    }

    public void onPostMouseUpdate() {
        this.ticking = false;
    }

    public void onRotationSet() {
        if (!this.ticking) {
            this.rotation = null;
        }
    }

    public float getYawOr(float fallback) {
        return this.rotation == null ? fallback : this.rotation.getYaw();
    }

    public float getPitchOr(float fallback) {
        return this.rotation == null ? fallback : this.rotation.getPitch();
    }

    public float getLastRenderYawOr(float fallback) {
        return this.rotation == null ? fallback : this.lastRenderYaw;
    }

    public float getLastRenderPitchOr(float fallback) {
        return this.rotation == null ? fallback : this.lastRenderPitch;
    }

    public float getRenderYawOr(float fallback) {
        return this.rotation == null ? fallback : this.renderYaw;
    }

    public float getRenderPitchOr(float fallback) {
        return this.rotation == null ? fallback : this.renderPitch;
    }

    public Rot2f getRotation() {
        return rotation;
    }

    public void setRotation(Rot2f rotation) {
        this.rotation = rotation;
    }

    public void setTicking(boolean ticking) {
        this.ticking = ticking;
    }
}
