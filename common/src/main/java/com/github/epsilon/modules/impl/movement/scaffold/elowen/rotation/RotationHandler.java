package com.github.epsilon.modules.impl.movement.scaffold.elowen.rotation;

import com.github.epsilon.modules.impl.movement.scaffold.elowen.ElowenScaffold;
import com.github.epsilon.utils.rotation.Rot2f;

/**
 * elowen {@code com.elowen.o.Y.Z.l.J.X}（旋转模式接口）。
 *
 * <p>elowen 原签名：{@code h_0 applyRotation(h_0 current, h_0 target, float maxStep, Scaffold module)}。
 * 本移植把 {@code Scaffold module} 换成 {@link ElowenScaffold} 引擎实例 ——
 * elowen 里模式类持有的 {@code this.module} 就是那个 {@code Scaffold}，
 * 而 Epsilon 侧「模块」与「引擎」是分开的，模式只允许碰引擎。</p>
 *
 * <p>[elowen o/Y/Z/l/J/X.java:1-8]</p>
 */
public interface RotationHandler {

    /**
     * @param engine  引擎（原 {@code module}）
     * @param current 当前脚本角（原 {@code current}）
     * @param target  目标角（原 {@code target}）
     * @param maxStep 每刻最大步长（原 {@code maxStep}，调用方传 Rotation Speed / Back Rotation Speed）
     * @return 本刻新的脚本角
     */
    Rot2f apply(ElowenScaffold engine, Rot2f current, Rot2f target, float maxStep);
}
