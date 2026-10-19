package com.github.epsilon.settings.impl;

import com.github.epsilon.settings.Setting;
import net.minecraft.util.Mth;

import java.util.function.Consumer;

public class IntSetting extends Setting<Integer> {

    private final int min;
    private final int max;
    private final int step;

    public IntSetting(String name, int defaultValue, int min, int max, int step, Dependency dependency, Consumer<Integer> onChanged) {
        super(name, dependency, onChanged);
        this.value = defaultValue;
        this.defaultValue = defaultValue;
        this.min = min;
        this.max = max;
        this.step = step;
    }

    @Override
    public void setValue(Integer value) {
        super.setValue(Mth.clamp(value, min, max));
    }

    public void setUnboundedValue(Integer value) {
        // GUI 文本输入与配置回填都走这里：不夹紧会让越界值进入运行态并被写回配置文件。
        super.setValue(Mth.clamp(value, min, max));
    }

    public int getMin() {
        return min;
    }

    public int getMax() {
        return max;
    }

    public int getStep() {
        return step;
    }
}
