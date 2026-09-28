/*
 * This file is part of Epsilon.
 *
 * 照抄自 OpenPal（https://github.com/OpenPal）MC 1.21.10 的
 * wtf.opal.client.feature.module.impl.world.scaffold.ScaffoldSettings 中
 * {@code HeypixelScaffold}/{@code HypixelScaffold} 会用到的那一组 getter。
 *
 * <p>OpenPal 的 {@code ScaffoldSettings} 自己持有全部设置对象；本仓库的设置字段直接挂在模块上，
 * 所以这里做成一层薄视图，把 OpenPal 的 getter 名映射到模块的设置 —— 这样两个模式类的方法体
 * 才能与 OpenPal 逐字一致（{@code module.getScaffoldSettings().isTelly()} 这种写法原样保留）。</p>
 *
 * <p>两处接口形状差异：</p>
 * <ul>
 *   <li>OpenPal 的 {@code getSwitchMode()} 返回 {@code ModeProperty<SwitchMode>}，调用点写
 *       {@code getSwitchMode().getValue()}。本仓库没有这个设置项（换手由既有的 {@code Swap Mode}
 *       派生，见 §4.9），所以这里直接返回 {@link SwitchMode} 值，调用点去掉 {@code .getValue()}。</li>
 *   <li>{@code isRotationModel(...)}：本仓库的 Uitems 设置清单（§5.1）里没有"旋转模型"下拉框，
 *       恒按 OpenPal 的默认模型 {@code InstantRotationModel} 判定 ⇒ 只会在
 *       {@code HypixelScaffold} 的 Sideways 分支上返回 false。</li>
 * </ul>
 */
package com.github.epsilon.modules.impl.movement.scaffold;

import com.github.epsilon.modules.impl.movement.Scaffold;
import com.github.epsilon.modules.impl.movement.scaffold.rotation.model.EnumRotationModel;

public final class ScaffoldSettings {

    private final Scaffold module;

    public ScaffoldSettings(final Scaffold module) {
        this.module = module;
    }

    public boolean isTelly() {
        return module.uitemsTelly.getValue();
    }

    public boolean isSafeWalk() {
        return module.uitemsSafeWalk.getValue();
    }

    public boolean isSnap() {
        return module.uitemsSnap();
    }

    public boolean isInteractBeforePlace() {
        return module.uitemsInteractBeforePlace.getValue();
    }

    public boolean isOverrideRaycast() {
        return module.uitemsOverrideRaycast.getValue();
    }

    public boolean isDuplicateRotPlace() {
        return module.uitemsDuplicateRotPlace.getValue();
    }

    public int getTellyTick() {
        return module.uitemsTellyTicks.getValue();
    }

    public float getRotateSpeed() {
        return module.uitemsRotationSpeed.getValue();
    }

    public float getRotateBackSpeed() {
        return module.uitemsRotationBackSpeed.getValue();
    }

    public SwitchMode getSwitchMode() {
        return module.getUitemsSwitchMode();
    }

    public SelfRescueMode getSelfRescueMode() {
        return module.uitemsSelfRescueMode.getValue();
    }

    public boolean isRotationModel(final EnumRotationModel model) {
        return module.rotationProperty.isModel(model);
    }

    /** OpenPal {@code ScaffoldSettings.SwitchMode}；由本仓库的 {@code Swap Mode} 派生（见 §4.9）。 */
    public enum SwitchMode {
        NORMAL,
        HOTBAR,
        FULL
    }

    /** OpenPal {@code ScaffoldSettings.SelfRescueMode}，两个值与顺序照搬。 */
    public enum SelfRescueMode {
        Disabled,
        SkipTick
    }
}
