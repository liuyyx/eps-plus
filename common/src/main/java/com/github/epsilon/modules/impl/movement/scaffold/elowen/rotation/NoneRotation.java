package com.github.epsilon.modules.impl.movement.scaffold.elowen.rotation;

import com.github.epsilon.modules.impl.movement.scaffold.elowen.ElowenScaffold;
import com.github.epsilon.utils.rotation.Rot2f;

/**
 * None 旋转（elowen {@code com.elowen.o.Y.Z.l.J.J}）：不做任何插值，直接取服务器旋转。
 *
 * <p>原：{@code return new h_0(scaffold.c(), scaffold.a());}
 * —— {@code c()}/{@code a()} 是「服务端看到的托管角」的 yaw / pitch。</p>
 *
 * <p>[elowen o/Y/Z/l/J/J.java:10-13]</p>
 */
public class NoneRotation implements RotationHandler {

    @Override
    public Rot2f apply(ElowenScaffold engine, Rot2f current, Rot2f target, float maxStep) {
        return new Rot2f(engine.serverYaw(), engine.serverPitch());
    }
}
