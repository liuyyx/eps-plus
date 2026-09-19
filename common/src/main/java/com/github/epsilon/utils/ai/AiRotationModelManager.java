package com.github.epsilon.utils.ai;

import com.github.epsilon.Constants;

import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;

/**
 * AI 转向模型的单例管理器。负责按需加载捆绑的 LiquidBounceNG 战斗回归模型
 * 并按名称选择激活模型。
 *
 * <p>模型以纯 Java 方式加载（无原生依赖），资源位于
 * {@code /assets/epsilon/ai/<name>.bin}。模型在首次使用 AI 转向时惰性加载，
 * 避免在未启用时把权重读入内存。</p>
 */
public final class AiRotationModelManager {

    public static final AiRotationModelManager INSTANCE = new AiRotationModelManager();

    private static final String[] BUILT_IN_MODELS = {"21kc11kp", "19kc8kp"};
    private static final String RESOURCE_PREFIX = "/assets/epsilon/ai/";

    /** 加载失败后的最小重试间隔，避免每个客户端 tick 都重新读取资源。 */
    private static final long RETRY_INTERVAL_MS = 5000L;

    private final Map<String, MlpModel> models = new LinkedHashMap<>();
    private volatile boolean loaded = false;
    private volatile String activeName = null;
    private volatile MlpModel activeModel = null;
    private String lastMissingWarned = null;
    private long lastLoadAttempt = 0L;

    private AiRotationModelManager() {
    }

    public boolean isLoaded() {
        return loaded;
    }

    public String getActiveName() {
        return activeName;
    }

    /**
     * 加载捆绑模型。至少成功加载一个模型时返回 {@code true}。
     *
     * <p>失败时保持未加载状态以便后续重试（与源实现一致），但以
     * {@link #RETRY_INTERVAL_MS} 退避限制重试频率，避免每个客户端 tick 都重新读取资源。</p>
     *
     * @return 是否至少有一个模型可用
     */
    public synchronized boolean load() {
        if (loaded) return true;

        long now = System.currentTimeMillis();
        if (now - lastLoadAttempt < RETRY_INTERVAL_MS) return false;
        lastLoadAttempt = now;

        for (String name : BUILT_IN_MODELS) {
            MlpModel model = MlpModel.load(RESOURCE_PREFIX + name + ".bin");
            if (model != null) {
                models.put(name, model);
            }
        }
        if (models.isEmpty()) {
            return false;
        }
        loaded = true;
        return true;
    }

    /**
     * 设置激活模型。名称不存在时保持当前选择不变。
     *
     * @param name 模型名称（大小写不敏感），例如 {@code "21KC11KP"}
     */
    public synchronized void setActiveModel(String name) {
        if (name == null) return;
        String key = name.toLowerCase(Locale.ROOT);
        MlpModel model = models.get(key);
        if (model == null) {
            if (!key.equals(lastMissingWarned)) {
                lastMissingWarned = key;
                Constants.LOGGER.warn("[AI] Requested model '{}' is unavailable; keeping '{}'", key, activeName);
            }
            return;
        }
        lastMissingWarned = null;
        activeName = key;
        activeModel = model;
    }

    /**
     * 确保模型已加载并返回激活模型；无可用模型时返回 {@code null}。
     *
     * @return 激活模型或 {@code null}
     */
    public synchronized MlpModel ensureReady() {
        if (!loaded) load();
        if (activeModel == null && !models.isEmpty()) {
            Map.Entry<String, MlpModel> first = models.entrySet().iterator().next();
            activeName = first.getKey();
            activeModel = first.getValue();
        }
        return activeModel;
    }

    /**
     * 对激活模型执行一次推理。无可用模型或推理失败时返回 {@code null}，
     * 调用方应回退到普通转向逻辑。
     *
     * @param input 6 维输入特征
     * @return 2 维输出 [yawDelta, pitchDelta]，失败返回 {@code null}
     */
    public float[] predictSafe(float[] input) {
        MlpModel model = ensureReady();
        if (model == null) return null;
        try {
            float[] output = model.predict(input);
            return output != null && output.length >= 2 ? output : null;
        } catch (Throwable error) {
            return null;
        }
    }
}
