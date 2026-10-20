package com.github.epsilon.interfaces;

/**
 * 直接写入 {@code KeyMapping.isDown} 的访问器。
 * <p>26.2 的 use/attack/sneak/sprint 是 {@code ToggleKeyMapping}：开启原版 toggle 选项时
 * {@code setDown(false)} 是空操作、{@code setDown(true)} 会取反，脚本无法可靠地按住/松开。
 */
public interface KeyMappingAccessor {

    void epsilon$setDownState(boolean down);

}
