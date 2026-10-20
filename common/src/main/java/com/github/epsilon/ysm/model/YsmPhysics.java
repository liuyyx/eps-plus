package com.github.epsilon.ysm.model;

import java.util.LinkedHashMap;
import java.util.Map;
import net.minecraft.util.Mth;

/**
 * YSM 头发/布料/飘带物理（移植自 OpenYSM 的
 * {@code molang/functions/physics/FirstOrder|SecondOrder} + {@code PhysicsManager}）。
 *
 * <p>按“名字”维护一个物理状态，每帧 {@link #update(float)} 推进一次；
 * molang 里的 {@code ysm.first_order(name, input[, response])} /
 * {@code ysm.second_order(name, input[, freq[, coeff[, response]]])} 读取平滑后的值。
 */
public final class YsmPhysics {

    private interface Model {
        void update(float timeStep);

        float value();

        void setArgs(float a, float b, float c, float d);
    }

    /** 一阶低通：lastSim += (dt/response) * (input - lastSim)。 */
    private static final class FirstOrder implements Model {
        private float input;
        private float response = 1.0f;
        private float lastSimulation;

        @Override
        public void update(float timeStep) {
            // response 来自模型文件（外部输入）：NaN/Inf/denormal 会让 t 变成 NaN/Inf，
            // 且 NaN 会粘在 lastSimulation 上污染后续所有帧。与 SecondOrder 同样做校验。
            float resp = !Float.isFinite(this.response) || this.response < 0.001f
                    ? 1.0f : Math.min(this.response, 1000.0f);
            float dt = Float.isFinite(timeStep) ? Math.max(0.0f, timeStep) : 0.0f;
            float t = Mth.clamp(dt / resp, 0.0f, 1.0f);
            // input 同样是模型文件里的外部输入：非有限值不能写回状态，否则 NaN/Inf 会永久粘住。
            float in = Float.isFinite(this.input) ? this.input
                    : (Float.isFinite(this.lastSimulation) ? this.lastSimulation : 0.0f);
            float next = (1.0f - t) * this.lastSimulation + t * in;
            this.lastSimulation = Float.isFinite(next) ? next : in;
        }

        @Override
        public float value() {
            return this.lastSimulation;
        }

        @Override
        public void setArgs(float a, float b, float c, float d) {
            this.input = a;
            this.response = b;
        }
    }

    /** 二阶弹簧-阻尼（作者 MicroCraft，“Giving Personality to Procedural Animations using Math”）。 */
    private static final class SecondOrder implements Model {
        private float inputFunction;
        private float lastSimulation;
        private float lastSimulationDot;
        private float input;
        private float frequency = 1.0f;
        private float coefficient = 1.0f;
        private float response = 1.0f;

        @Override
        public void update(float timeStep) {
            // input 是外部输入：非有限值回落到当前状态，避免污染并永久持 Inf。
            float in = Float.isFinite(this.input) ? this.input
                    : (Float.isFinite(this.lastSimulation) ? this.lastSimulation : 0.0f);
            // freq/coeff 下限必须 > 0：原 clamp 允许 0 ⇒ k1/k2/k3 出现 Inf，Inf-Inf=NaN 并常驻骨骼变换
            // （模型整只炸开）。模型文件里的参数是外部输入，必须做非退化与有限性校验。
            float freq = !Float.isFinite(this.frequency) || this.frequency <= 0.0f
                    ? 1.0f : Mth.clamp(this.frequency, 0.01f, 5.0f);
            float coeff = !Float.isFinite(this.coefficient) || this.coefficient <= 0.0f
                    ? 0.01f : Mth.clamp(this.coefficient, 0.01f, 1.0f);
            float resp = !Float.isFinite(this.response) || this.response < 0.0f
                    ? 0.0f : Math.min(this.response, 1000.0f);

            float k1 = coeff / Mth.PI / freq;
            float k2 = 1.0f / (2.0f * Mth.PI * freq) / (2.0f * Mth.PI * freq);
            float k3 = resp * coeff / 2.0f / Mth.PI / freq;

            float inputFunctionDot = (in - this.inputFunction) / timeStep;
            this.inputFunction = in;

            float maxTimeStep = (float) Math.sqrt(4.0f * k2 + k1 * k1) - k1;
            int cycleTime = maxTimeStep <= 0.0f ? 1 : (int) Math.ceil(timeStep / maxTimeStep);
            // timeStep 理论上有上限（调用方 clamp 0..0.1），但别让异常值触发 2^31 次积分循环。
            cycleTime = Mth.clamp(cycleTime, 1, 256);
            float step = timeStep / cycleTime;

            float sim = this.lastSimulation;
            float dot = this.lastSimulationDot;
            for (int i = 0; i < cycleTime; i++) {
                sim = sim + step * dot;
                dot = dot + step * (k3 * inputFunctionDot + in - sim - k1 * dot) / k2;
            }
            // 末次兜底：任何非有限结果都不要写回状态，回到输入值而不是把 NaN 扩散到骨骼矩阵。
            if (!Float.isFinite(sim) || !Float.isFinite(dot)) {
                sim = in;
                dot = 0.0f;
            }
            this.lastSimulation = sim;
            this.lastSimulationDot = dot;
        }

        @Override
        public float value() {
            return this.lastSimulation;
        }

        @Override
        public void setArgs(float a, float b, float c, float d) {
            this.input = a;
            this.frequency = b;
            this.coefficient = c;
            this.response = d;
        }
    }

    private final Map<String, Model> models = new LinkedHashMap<>();

    /** 一阶：不存在则以 input 初始化；存在则设置参数并返回当前平滑值。 */
    public float firstOrder(String name, float input, float response) {
        Model model = this.models.get(name);
        if (model == null) {
            model = new FirstOrder();
            model.setArgs(input, response, 0.0f, 0.0f);
            this.models.put(name, model);
            return input;
        }
        model.setArgs(input, response, 0.0f, 0.0f);
        return model.value();
    }

    /** 二阶。 */
    public float secondOrder(String name, float input, float frequency, float coefficient, float response) {
        Model model = this.models.get(name);
        if (model == null) {
            model = new SecondOrder();
            model.setArgs(input, frequency, coefficient, response);
            this.models.put(name, model);
            return input;
        }
        model.setArgs(input, frequency, coefficient, response);
        return model.value();
    }

    /** 每帧推进（timeStep 为秒）。 */
    public void update(float timeStep) {
        if (!Float.isFinite(timeStep) || timeStep <= 0.0f) {
            return;
        }
        for (Model model : this.models.values()) {
            model.update(timeStep);
        }
    }

    public void clear() {
        this.models.clear();
    }
}
