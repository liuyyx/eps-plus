package com.github.epsilon.utils.ai;

import com.github.epsilon.Constants;

import java.io.DataInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

/**
 * 纯 Java 推理实现，用于 LiquidBounceNG 战斗回归模型（21KC11KP / 19KC8KP）。
 *
 * <p>MLP 结构：
 * <pre>
 *   Linear(6 -&gt; 128) + BatchNorm(128) + ReLU
 *   Linear(128 -&gt; 64) + BatchNorm(64) + ReLU
 *   Linear(64 -&gt; 32) + BatchNorm(32) + ReLU
 *   Linear(32 -&gt; 2)
 * </pre>
 * 权重从捆绑的 {@code .bin} 文件加载，格式为：
 * 魔数 {@code "VMLP"} + 版本(int) + 参数个数(int)，随后每个参数依次为
 * 名称长度(int) + 名称(UTF-8) + 维度(int) + 形状(int[]) + 数据长度(int) + 数据(float[])。
 * 头部整数为大端（{@link DataInputStream} 默认），浮点数据为小端。</p>
 */
public final class MlpModel {

    private static final float BATCHNORM_EPS = 1e-5f;

    /** 输入特征维度（展开输入布局）。 */
    private static final int INPUT_SIZE = 6;
    /** 输出维度（[yawDelta, pitchDelta]）。 */
    private static final int OUTPUT_SIZE = 2;

    // 每层：权重 (out, in) 行优先存储，偏置 (out)
    private final float[][] w1; // 128x6
    private final float[] b1;   // 128
    private final float[][] w2; // 64x128
    private final float[] b2;   // 64
    private final float[][] w3; // 32x64
    private final float[] b3;   // 32
    private final float[][] w4; // 2x32
    private final float[] b4;   // 2

    // 每层 BatchNorm 参数（输出维度 128/64/32）
    private final float[] gamma1, beta1, mean1, var1;
    private final float[] gamma2, beta2, mean2, var2;
    private final float[] gamma3, beta3, mean3, var3;

    private MlpModel(float[][] w1, float[] b1,
                     float[] gamma1, float[] beta1, float[] mean1, float[] var1,
                     float[][] w2, float[] b2,
                     float[] gamma2, float[] beta2, float[] mean2, float[] var2,
                     float[][] w3, float[] b3,
                     float[] gamma3, float[] beta3, float[] mean3, float[] var3,
                     float[][] w4, float[] b4) {
        this.w1 = w1;
        this.b1 = b1;
        this.gamma1 = gamma1;
        this.beta1 = beta1;
        this.mean1 = mean1;
        this.var1 = var1;
        this.w2 = w2;
        this.b2 = b2;
        this.gamma2 = gamma2;
        this.beta2 = beta2;
        this.mean2 = mean2;
        this.var2 = var2;
        this.w3 = w3;
        this.b3 = b3;
        this.gamma3 = gamma3;
        this.beta3 = beta3;
        this.mean3 = mean3;
        this.var3 = var3;
        this.w4 = w4;
        this.b4 = b4;
    }

    /**
     * 从 classpath 资源加载模型。资源不存在或解析失败时返回 {@code null}。
     *
     * @param resourcePath classpath 资源路径，例如 {@code /assets/epsilon/ai/21kc11kp.bin}
     * @return 解析后的模型，失败返回 {@code null}
     */
    public static MlpModel load(String resourcePath) {
        try (InputStream stream = MlpModel.class.getResourceAsStream(resourcePath)) {
            if (stream == null) {
                Constants.LOGGER.warn("[AI] Bundled model resource not found: {}", resourcePath);
                return null;
            }
            return parse(stream);
        } catch (Throwable error) {
            Constants.LOGGER.warn("[AI] Failed to parse bundled model {}", resourcePath, error);
            return null;
        }
    }

    private static MlpModel parse(InputStream in) throws IOException {
        DataInputStream dis = new DataInputStream(in);

        // 头部
        byte[] magic = new byte[4];
        dis.readFully(magic);
        if (magic[0] != 'V' || magic[1] != 'M' || magic[2] != 'L' || magic[3] != 'P') {
            throw new IOException("Bad model magic: " + new String(magic, StandardCharsets.UTF_8));
        }
        int version = dis.readInt();
        if (version != 1) {
            throw new IOException("Unsupported model version: " + version);
        }
        int nParams = dis.readInt();
        List<Param> params = new ArrayList<>(nParams);
        for (int i = 0; i < nParams; i++) {
            int nameLen = dis.readInt();
            byte[] nameBytes = new byte[nameLen];
            dis.readFully(nameBytes);
            String name = new String(nameBytes, StandardCharsets.UTF_8);
            int ndim = dis.readInt();
            int[] shape = new int[ndim];
            for (int d = 0; d < ndim; d++) {
                shape[d] = dis.readInt();
            }
            int dataLen = dis.readInt();
            byte[] raw = new byte[dataLen * 4];
            dis.readFully(raw);
            ByteBuffer bb = ByteBuffer.wrap(raw).order(ByteOrder.LITTLE_ENDIAN);
            float[] data = new float[dataLen];
            for (int j = 0; j < dataLen; j++) {
                data[j] = bb.getFloat();
            }
            params.add(new Param(name, shape, data));
        }

        // 参数按声明顺序排列：
        //  Linear1.weight (out,in)、Linear1.bias (out)、
        //  BN1.gamma、BN1.beta、BN1.runningMean、BN1.runningVar、
        //  Linear2.*、BN2.*、Linear3.*、BN3.*、Linear4.*
        // 名称在各层间重复，因此按顺序（形状）消费。
        int idx = 0;
        float[][] w1 = asMatrix2d(params.get(idx++)); // (128, 6)
        float[] b1 = asVector(params.get(idx++), w1.length);
        float[] gamma1 = asVector(params.get(idx++), w1.length);
        float[] beta1 = asVector(params.get(idx++), w1.length);
        float[] mean1 = asVector(params.get(idx++), w1.length);
        float[] var1 = asVector(params.get(idx++), w1.length);

        float[][] w2 = asMatrix2d(params.get(idx++)); // (64, 128)
        float[] b2 = asVector(params.get(idx++), w2.length);
        float[] gamma2 = asVector(params.get(idx++), w2.length);
        float[] beta2 = asVector(params.get(idx++), w2.length);
        float[] mean2 = asVector(params.get(idx++), w2.length);
        float[] var2 = asVector(params.get(idx++), w2.length);

        float[][] w3 = asMatrix2d(params.get(idx++)); // (32, 64)
        float[] b3 = asVector(params.get(idx++), w3.length);
        float[] gamma3 = asVector(params.get(idx++), w3.length);
        float[] beta3 = asVector(params.get(idx++), w3.length);
        float[] mean3 = asVector(params.get(idx++), w3.length);
        float[] var3 = asVector(params.get(idx++), w3.length);

        float[][] w4 = asMatrix2d(params.get(idx++)); // (2, 32)
        float[] b4 = asVector(params.get(idx++), w4.length);

        if (idx != nParams) {
            throw new IOException("Param count mismatch: expected " + nParams + ", consumed " + idx);
        }

        // 层间维度链校验：形状错配的模型必须被拒绝，而不是静默产出错误角度。
        requireShape("input", w1[0].length, INPUT_SIZE);
        requireShape("hidden1", w2[0].length, w1.length);
        requireShape("hidden2", w3[0].length, w2.length);
        requireShape("hidden3", w4[0].length, w3.length);
        requireShape("output", w4.length, OUTPUT_SIZE);

        return new MlpModel(w1, b1, gamma1, beta1, mean1, var1,
                w2, b2, gamma2, beta2, mean2, var2,
                w3, b3, gamma3, beta3, mean3, var3,
                w4, b4);
    }

    private static float[][] asMatrix2d(Param p) {
        if (p.shape().length != 2) {
            throw new IllegalArgumentException("Expected 2-D weight, got shape rank " + p.shape().length);
        }
        int rows = p.shape()[0];
        int cols = p.shape()[1];
        if (p.data().length != rows * cols) {
            throw new IllegalArgumentException("Weight data length " + p.data().length
                    + " does not match shape " + rows + "x" + cols);
        }
        float[][] m = new float[rows][cols];
        for (int r = 0; r < rows; r++) {
            System.arraycopy(p.data(), r * cols, m[r], 0, cols);
        }
        return m;
    }

    private static float[] asVector(Param p, int expected) {
        float[] data = p.data();
        if (data.length != expected) {
            throw new IllegalArgumentException("Parameter '" + p.name() + "' length " + data.length
                    + " does not match expected " + expected);
        }
        return data;
    }

    private static void requireShape(String layer, int actual, int expected) {
        if (actual != expected) {
            throw new IllegalArgumentException("Model layer '" + layer + "' has dimension " + actual
                    + ", expected " + expected);
        }
    }

    /**
     * 执行一次前向传播。{@code input} 长度必须为 6。
     *
     * @param input 6 维输入特征
     * @return 2 维输出 [yawDelta, pitchDelta]
     */
    public float[] predict(float[] input) {
        float[] h = linear(input, w1, b1);
        h = batchNorm(h, gamma1, beta1, mean1, var1);
        reluInPlace(h);
        h = linear(h, w2, b2);
        h = batchNorm(h, gamma2, beta2, mean2, var2);
        reluInPlace(h);
        h = linear(h, w3, b3);
        h = batchNorm(h, gamma3, beta3, mean3, var3);
        reluInPlace(h);
        return linear(h, w4, b4);
    }

    /**
     * y = W * x + b，其中 W 形状为 (out, in)，x 为长度 {@code in} 的向量。
     */
    private static float[] linear(float[] x, float[][] w, float[] b) {
        int out = w.length;
        int in = w[0].length;
        float[] y = new float[out];
        for (int o = 0; o < out; o++) {
            float[] row = w[o];
            float acc = b[o];
            for (int i = 0; i < in; i++) {
                acc += row[i] * x[i];
            }
            y[o] = acc;
        }
        return y;
    }

    private static float[] batchNorm(float[] x, float[] gamma, float[] beta,
                                     float[] mean, float[] var) {
        int n = x.length;
        float[] y = new float[n];
        for (int i = 0; i < n; i++) {
            float std = (float) Math.sqrt(var[i] + BATCHNORM_EPS);
            y[i] = gamma[i] * (x[i] - mean[i]) / std + beta[i];
        }
        return y;
    }

    private static void reluInPlace(float[] x) {
        for (int i = 0; i < x.length; i++) {
            if (x[i] < 0f) {
                x[i] = 0f;
            }
        }
    }

    private record Param(String name, int[] shape, float[] data) {
    }
}
