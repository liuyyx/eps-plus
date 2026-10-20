package com.github.epsilon.ysm.compat;

import net.minecraft.client.player.LocalPlayer;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.item.ItemStack;

import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodType;
import java.lang.reflect.Method;
import java.util.Locale;
import java.util.Optional;

/**
 * TACZ（Timeless and Classics Zero）枪械动画的可选集成桥。
 *
 * <p>Epsilon 不依赖 TACZ。本类全部通过反射访问 TACZ 的公开 API，
 * 首次解析失败后永久降级；任何异常都被吞掉，绝不影响模型渲染主路径。</p>
 */
public final class TaczCompat {

    private static final String CLASS_IGUN = "com.tacz.guns.api.item.IGun";
    private static final String CLASS_OPERATOR = "com.tacz.guns.api.entity.IGunOperator";
    private static final String CLASS_CLIENT_OPERATOR = "com.tacz.guns.api.client.gameplay.IClientPlayerGunOperator";
    private static final String CLASS_TIMELESS = "com.tacz.guns.api.TimelessAPI";

    private static volatile boolean resolved;
    private static boolean available;

    private static MethodHandle hGetGunOrNull;
    private static MethodHandle hGetGunId;
    private static MethodHandle hOperatorFrom;
    private static MethodHandle hSynReloadState;
    private static MethodHandle hCountDown;
    private static MethodHandle hSynMeleeCoolDown;
    private static MethodHandle hSynShootCoolDown;
    private static MethodHandle hSynAimingProgress;
    private static MethodHandle hClientOperatorFrom;
    private static MethodHandle hClientShootCoolDown;
    private static MethodHandle hCommonGunIndex;
    private static MethodHandle hIndexGetType;

    private TaczCompat() {
    }

    /** 真正的 TACZ {@code IGun} 物品；ClayCore 物品返回 false。 */
    public static boolean isNativeGun(ItemStack stack) {
        resolve();
        if (!available || stack == null || stack.isEmpty()) {
            return false;
        }
        return getGunOrNull(stack) != null;
    }

    /** 返回 {@code pistol} / {@code rpg} / {@code rifle}；非 TACZ 枪械返回 null。 */
    public static String gunType(ItemStack stack) {
        resolve();
        if (!available || stack == null || stack.isEmpty()) {
            return null;
        }
        try {
            Object gun = getGunOrNull(stack);
            if (gun == null || hGetGunId == null) {
                return null;
            }
            Object gunId = hGetGunId.invoke(gun, stack);
            if (gunId == null || hCommonGunIndex == null) {
                return "rifle";
            }
            Object indexOptional = hCommonGunIndex.invoke(gunId);
            if (!(indexOptional instanceof Optional<?> optional) || optional.isEmpty()) {
                return "rifle";
            }
            Object index = optional.get();
            if (hIndexGetType == null) {
                return "rifle";
            }
            Object rawType = hIndexGetType.invoke(index);
            if (rawType == null) {
                return "rifle";
            }
            String type = rawType.toString().toLowerCase(Locale.ROOT);
            if (type.contains("pistol")) {
                return "pistol";
            }
            if (type.contains("rpg")) {
                return "rpg";
            }
            return "rifle";
        } catch (Throwable ignored) {
            return null;
        }
    }

    /** 取 {@code IGunOperator}，作为后续查询的不透明句柄。 */
    public static Object operator(LivingEntity entity) {
        resolve();
        if (!available || entity == null || hOperatorFrom == null) {
            return null;
        }
        try {
            return hOperatorFrom.invoke(entity);
        } catch (Throwable ignored) {
            return null;
        }
    }

    public static boolean isReloading(Object operator) {
        if (operator == null || hSynReloadState == null || hCountDown == null) {
            return false;
        }
        try {
            Object state = hSynReloadState.invoke(operator);
            if (state == null) {
                return false;
            }
            Object countDown = hCountDown.invoke(state);
            return countDown instanceof Number number && number.floatValue() > 0.0f;
        } catch (Throwable ignored) {
            return false;
        }
    }

    public static long meleeCooldown(Object operator) {
        return number(operator, hSynMeleeCoolDown);
    }

    public static long shootCooldown(Object operator) {
        return number(operator, hSynShootCoolDown);
    }

    public static float aimingProgress(Object operator) {
        if (operator == null || hSynAimingProgress == null) {
            return 0.0f;
        }
        try {
            Object value = hSynAimingProgress.invoke(operator);
            return value instanceof Number number ? number.floatValue() : 0.0f;
        } catch (Throwable ignored) {
            return 0.0f;
        }
    }

    public static long clientShootCooldown(LocalPlayer player) {
        resolve();
        if (!available || player == null || hClientOperatorFrom == null || hClientShootCoolDown == null) {
            return 0L;
        }
        try {
            Object operator = hClientOperatorFrom.invoke(player);
            if (operator == null) {
                return 0L;
            }
            Object value = hClientShootCoolDown.invoke(operator);
            return value instanceof Number number ? number.longValue() : 0L;
        } catch (Throwable ignored) {
            return 0L;
        }
    }

    private static long number(Object target, MethodHandle handle) {
        if (target == null || handle == null) {
            return 0L;
        }
        try {
            Object value = handle.invoke(target);
            return value instanceof Number num ? num.longValue() : 0L;
        } catch (Throwable ignored) {
            return 0L;
        }
    }

    private static Object getGunOrNull(ItemStack stack) {
        if (hGetGunOrNull == null) {
            return null;
        }
        try {
            return hGetGunOrNull.invoke(stack);
        } catch (Throwable ignored) {
            return null;
        }
    }

    private static void resolve() {
        if (resolved) {
            return;
        }
        synchronized (TaczCompat.class) {
            if (resolved) {
                return;
            }
            try {
                Class<?> igun = Class.forName(CLASS_IGUN);
                Class<?> operator = Class.forName(CLASS_OPERATOR);
                Class<?> timeless = Class.forName(CLASS_TIMELESS);
                hGetGunOrNull = findStatic(igun, "getIGunOrNull", ItemStack.class);
                hGetGunId = findVirtual(igun, "getGunId", ItemStack.class);
                hOperatorFrom = findStatic(operator, "fromLivingEntity", LivingEntity.class);
                hSynMeleeCoolDown = findVirtual(operator, "getSynMeleeCoolDown");
                hSynShootCoolDown = findVirtual(operator, "getSynShootCoolDown");
                hSynAimingProgress = findVirtual(operator, "getSynAimingProgress");
                Method reloadState = findMethod(operator, "getSynReloadState", 0);
                if (reloadState != null) {
                    hSynReloadState = MethodHandles.lookup().unreflect(reloadState);
                    hCountDown = findVirtual(reloadState.getReturnType(), "getCountDown");
                }
                Method commonGunIndex = findMethod(timeless, "getCommonGunIndex", 1);
                if (commonGunIndex != null) {
                    hCommonGunIndex = MethodHandles.lookup().unreflect(commonGunIndex);
                    Class<?> indexType = guessGunIndexType(commonGunIndex);
                    if (indexType != null) {
                        hIndexGetType = findVirtual(indexType, "getType");
                    }
                }
                try {
                    Class<?> clientOperator = Class.forName(CLASS_CLIENT_OPERATOR);
                    hClientOperatorFrom = findStatic(clientOperator, "fromLocalPlayer", LocalPlayer.class);
                    hClientShootCoolDown = findVirtual(clientOperator, "getClientShootCoolDown");
                } catch (Throwable ignored) {
                    hClientOperatorFrom = null;
                    hClientShootCoolDown = null;
                }
                available = hGetGunOrNull != null && hGetGunId != null;
            } catch (Throwable ignored) {
                available = false;
            }
            resolved = true;
        }
    }

    private static Class<?> guessGunIndexType(Method method) {
        try {
            java.lang.reflect.Type generic = method.getGenericReturnType();
            if (!(generic instanceof java.lang.reflect.ParameterizedType parameterized)) {
                return null;
            }
            java.lang.reflect.Type[] args = parameterized.getActualTypeArguments();
            if (args.length != 1 || !(args[0] instanceof Class<?> type)) {
                return null;
            }
            return type;
        } catch (Throwable ignored) {
            return null;
        }
    }

    private static MethodHandle findStatic(Class<?> owner, String name, Class<?>... params) {
        try {
            return MethodHandles.lookup().findStatic(owner, name, MethodType.methodType(owner, params));
        } catch (Throwable ignored) {
        }
        Method method = findMethod(owner, name, params.length);
        if (method == null) {
            return null;
        }
        try {
            return MethodHandles.lookup().unreflect(method);
        } catch (Throwable ignored) {
            return null;
        }
    }

    private static MethodHandle findVirtual(Class<?> owner, String name, Class<?>... params) {
        if (owner == null) {
            return null;
        }
        Method method = findMethod(owner, name, params.length);
        if (method == null) {
            return null;
        }
        try {
            return MethodHandles.lookup().unreflect(method);
        } catch (Throwable ignored) {
            return null;
        }
    }

    private static Method findMethod(Class<?> owner, String name, int paramCount) {
        if (owner == null) {
            return null;
        }
        for (Method method : owner.getMethods()) {
            if (method.getName().equals(name) && method.getParameterCount() == paramCount) {
                return method;
            }
        }
        return null;
    }
}
