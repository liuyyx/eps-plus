package com.github.epsilon.managers;

import com.github.epsilon.modules.impl.player.Timer;

public class TimerManager {

    public static final TimerManager INSTANCE = new TimerManager();

    public float timer = 1f;
    public float lastTimer;

    private TimerManager() {
    }

    public void set(float factor) {
        if (factor < 0.1f) factor = 0.1f;
        timer = factor;
    }

    public void reset() {
        timer = getDefault();
        lastTimer = timer;
    }

    public void tryReset() {
        // 必须比较**当前生效值**：lastTimer 只在 reset() 里更新，用它比较永远检测不到
        // 其它模块（Step/HoleSnap）写进去的临时倍率。
        if (timer != getDefault()) {
            reset();
        }
    }

    public float get() {
        return timer;
    }

    public float getDefault() {
        Timer timer = Timer.INSTANCE;
        // Balance/SlowBalance 的基线是 1.0x，只有 Always 才是 multiplier。
        return timer.isEnabled() ? timer.baselineFactor() : 1f;
    }

}
