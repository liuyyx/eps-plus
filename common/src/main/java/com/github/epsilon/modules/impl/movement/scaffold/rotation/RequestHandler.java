package com.github.epsilon.modules.impl.movement.scaffold.rotation;

import java.util.Comparator;
import java.util.concurrent.PriorityBlockingQueue;

import static com.github.epsilon.Constants.mc;

/**
 * 逐行移植自 LiquidBounce nextgen {@code utils/client/RequestHandler.kt}（commit 2d94475）。
 * <p>
 * LB 原文：{@code class RequestHandler<T>}，内部持有
 * {@code PriorityBlockingQueue<Request<T>>(11, compareBy { -it.priority })}。
 * <p>
 * [适配] LB 的 {@code Request.provider} 类型是 {@code EventListener}（用于 {@code provider.running} 判定），
 * Epsilon 无该基类 → 抽出 {@link Provider} 接口，只保留 {@code running} 语义；
 * 阶段 7 的协调层传入稳定实例（身份相等用于「同一 provider 的旧请求被替换」）。
 */
public final class RequestHandler<T> {

    /**
     * [适配] LB 原文：{@code EventListener.running} / {@code ClientModule.running}
     */
    public interface Provider {

        boolean isRunning();

    }

    private int currentTick = 0;

    private final PriorityBlockingQueue<Request<T>> activeRequests =
            new PriorityBlockingQueue<>(11, Comparator.comparingInt(request -> -request.priority));

    // LB 原文：fun tick(deltaTime: Int = 1)
    public void tick() {
        tick(1);
    }

    public void tick(int deltaTime) {
        currentTick += deltaTime;
    }

    public void clear() {
        activeRequests.clear();
        currentTick = 0;
    }

    public void request(Request<T> request) {
        // we remove all requests provided by module on new request
        // LB 原文：activeRequests.removeIf { it.provider === request.provider }
        activeRequests.removeIf(active -> active.provider == request.provider);
        request.expiresIn += currentTick;
        activeRequests.add(request);
    }

    public T getActiveRequestValue() {
        Request<T> top = activeRequests.peek();
        if (top == null) {
            return null;
        }

        // LB 原文：if (Minecraft.getInstance()?.isSameThread != false)
        // [适配] Minecraft.getInstance() → Constants.mc（同一实例），null 语义保持「进入分支」
        if (mc == null || mc.isSameThread()) {
            // we remove all outdated requests here
            while (top.expiresIn <= currentTick || !top.provider.isRunning()) {
                activeRequests.remove();
                top = activeRequests.peek();
                if (top == null) {
                    return null;
                }
            }
        }

        return top.value;
    }

    /**
     * A requested state of the system.
     *
     * Note: A request is deleted when its corresponding module is disabled.
     *
     * @param expiresIn in how many ticks units should this request expire?
     * @param priority higher = higher priority
     * @param provider module which requested value
     */
    public static final class Request<T> {

        public int expiresIn;
        public final int priority;
        public final Provider provider;
        public final T value;

        public Request(int expiresIn, int priority, Provider provider, T value) {
            this.expiresIn = expiresIn;
            this.priority = priority;
            this.provider = provider;
            this.value = value;
        }

    }

}
