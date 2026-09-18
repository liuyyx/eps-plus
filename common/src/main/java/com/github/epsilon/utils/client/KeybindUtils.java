package com.github.epsilon.utils.client;

import com.github.epsilon.assets.i18n.EpsilonTranslations;
import com.github.epsilon.Constants;
import com.mojang.blaze3d.platform.InputConstants;
import net.minecraft.client.KeyMapping;
import org.lwjgl.sdl.SDLMouse;

import static com.github.epsilon.Constants.mc;

public class KeybindUtils {

    public static final int NONE = -1;
    public static final int MOUSE_OFFSET = -2;

    /** 26.2 及更早版本使用的 GLFW 鼠标按键编号顺序：左键、右键、中键、附加键。 */
    private static final int[] LEGACY_MOUSE_BUTTONS = {
            InputConstants.MOUSE_BUTTON_LEFT,
            InputConstants.MOUSE_BUTTON_RIGHT,
            InputConstants.MOUSE_BUTTON_MIDDLE,
            InputConstants.MOUSE_BUTTON_4,
            InputConstants.MOUSE_BUTTON_5,
            InputConstants.MOUSE_BUTTON_6,
            InputConstants.MOUSE_BUTTON_7,
            InputConstants.MOUSE_BUTTON_8
    };

    private KeybindUtils() {
    }

    /**
     * 把 26.2 及更早版本保存的键位编码迁移为 26.3 的编码。
     *
     * <p>26.3 的输入系统由 GLFW 换成 SDL：键盘保存扫描码，鼠标按键编号也改为 SDL 的
     * 左键 1、中键 2、右键 3。旧配置必须经过迁移，否则同一个数值会指向完全不同的按键。
     *
     * @param legacyBind 旧版本保存的键位编码
     * @return 迁移后的键位编码，旧值无法映射时返回 {@link #NONE}
     */
    public static int migrateLegacyKeyBind(int legacyBind) {
        if (legacyBind == NONE) {
            return NONE;
        }
        if (isMouseButton(legacyBind)) {
            int legacyButton = decodeMouseButton(legacyBind);
            return legacyButton >= 0 && legacyButton < LEGACY_MOUSE_BUTTONS.length
                    ? encodeMouseButton(LEGACY_MOUSE_BUTTONS[legacyButton])
                    : NONE;
        }
        return migrateLegacyKeyCode(legacyBind);
    }

    /**
     * 把 GLFW 键码迁移为 SDL 扫描码。
     *
     * @param legacyKey GLFW 键码
     * @return SDL 扫描码，未知键码返回 {@link #NONE}
     */
    private static int migrateLegacyKeyCode(int legacyKey) {
        if (legacyKey >= 65 && legacyKey <= 90) {
            // GLFW: 'A'..'Z'，SDL: KEY_A..KEY_Z
            return InputConstants.KEY_A + (legacyKey - 65);
        }
        if (legacyKey >= 49 && legacyKey <= 57) {
            // GLFW: '1'..'9'，SDL: KEY_1..KEY_9
            return InputConstants.KEY_1 + (legacyKey - 49);
        }
        if (legacyKey >= 290 && legacyKey <= 301) {
            // GLFW: F1..F12，SDL: KEY_F1..KEY_F12
            return InputConstants.KEY_F1 + (legacyKey - 290);
        }
        if (legacyKey >= 302 && legacyKey <= 313) {
            // GLFW: F13..F24，SDL: KEY_F13..KEY_F24
            return InputConstants.KEY_F13 + (legacyKey - 302);
        }
        if (legacyKey == 320) {
            return InputConstants.KEY_NUMPAD0;
        }
        if (legacyKey >= 321 && legacyKey <= 329) {
            // GLFW: KP_1..KP_9，SDL: KEY_NUMPAD1..KEY_NUMPAD9
            return InputConstants.KEY_NUMPAD1 + (legacyKey - 321);
        }

        return switch (legacyKey) {
            case 48 -> InputConstants.KEY_0;
            case 32 -> InputConstants.KEY_SPACE;
            case 39 -> InputConstants.KEY_APOSTROPHE;
            case 44 -> InputConstants.KEY_COMMA;
            case 45 -> InputConstants.KEY_MINUS;
            case 46 -> InputConstants.KEY_PERIOD;
            case 47 -> InputConstants.KEY_SLASH;
            case 59 -> InputConstants.KEY_SEMICOLON;
            case 61 -> InputConstants.KEY_EQUALS;
            case 91 -> InputConstants.KEY_LBRACKET;
            case 92 -> InputConstants.KEY_BACKSLASH;
            case 93 -> InputConstants.KEY_RBRACKET;
            case 96 -> InputConstants.KEY_GRAVE;
            case 256 -> InputConstants.KEY_ESCAPE;
            case 257 -> InputConstants.KEY_RETURN;
            case 258 -> InputConstants.KEY_TAB;
            case 259 -> InputConstants.KEY_BACKSPACE;
            case 260 -> InputConstants.KEY_INSERT;
            case 261 -> InputConstants.KEY_DELETE;
            case 262 -> InputConstants.KEY_RIGHT;
            case 263 -> InputConstants.KEY_LEFT;
            case 264 -> InputConstants.KEY_DOWN;
            case 265 -> InputConstants.KEY_UP;
            case 266 -> InputConstants.KEY_PAGEUP;
            case 267 -> InputConstants.KEY_PAGEDOWN;
            case 268 -> InputConstants.KEY_HOME;
            case 269 -> InputConstants.KEY_END;
            case 280 -> InputConstants.KEY_CAPSLOCK;
            case 281 -> InputConstants.KEY_SCROLLLOCK;
            case 282 -> InputConstants.KEY_NUMLOCK;
            case 283 -> InputConstants.KEY_PRINTSCREEN;
            case 284 -> InputConstants.KEY_PAUSE;
            case 332 -> InputConstants.KEY_MULTIPLY;
            case 334 -> InputConstants.KEY_ADD;
            case 335 -> InputConstants.KEY_NUMPADENTER;
            case 336 -> InputConstants.KEY_NUMPADEQUALS;
            case 340 -> InputConstants.KEY_LSHIFT;
            case 341 -> InputConstants.KEY_LCONTROL;
            case 342 -> InputConstants.KEY_LALT;
            case 343 -> InputConstants.KEY_LGUI;
            case 344 -> InputConstants.KEY_RSHIFT;
            case 345 -> InputConstants.KEY_RCONTROL;
            case 346 -> InputConstants.KEY_RALT;
            case 347 -> InputConstants.KEY_RGUI;
            default -> {
                Constants.LOGGER.warn("无法迁移旧键位编码 {}，该绑定已重置为未绑定。", legacyKey);
                yield NONE;
            }
        };
    }

    /**
     * 判断编码后的绑定值是否表示鼠标按键。
     *
     * @param keyBind Epsilon 键位编码值
     * @return 判断结果
     */
    public static boolean isMouseButton(int keyBind) {
        return keyBind <= MOUSE_OFFSET;
    }

    /**
     * 将 SDL 鼠标按键编号编码为 Epsilon 键位值。
     *
     * @param button 点击按钮编号
     * @return 操作结果
     */
    public static int encodeMouseButton(int button) {
        return MOUSE_OFFSET - button;
    }

    /**
     * 从 Epsilon 键位值解码 SDL 鼠标按键编号。
     *
     * @param keyBind Epsilon 键位编码值
     * @return 操作结果
     */
    public static int decodeMouseButton(int keyBind) {
        return MOUSE_OFFSET - keyBind;
    }

    /**
     * 获取按键映射当前绑定的原始键值。
     *
     * @param keyMapping Minecraft 按键映射
     * @return 获取或计算得到的结果
     */
    public static int getKey(KeyMapping keyMapping) {
        return keyMapping.key.getValue();
    }

    /**
     * 判断指定按键绑定当前是否按下。
     *
     * @param keyMapping Minecraft 按键映射
     * @return 判断结果
     */
    public static boolean isPressed(KeyMapping keyMapping) {
        return isPressed(getKey(keyMapping));
    }

    /**
     * 判断指定按键绑定当前是否按下。
     *
     * @param keyBind Epsilon 键位编码值
     * @return 判断结果
     */
    public static boolean isPressed(int keyBind) {
        if (keyBind == NONE) {
            return false;
        }
        if (isMouseButton(keyBind)) {
            return isMouseButtonDown(decodeMouseButton(keyBind));
        }
        return InputConstants.isKeyDown(keyBind);
    }

    /**
     * 判断指定 SDL 鼠标按键当前是否按下。
     *
     * @param button SDL 鼠标按键编号，1 为左键
     * @return 判断结果
     */
    private static boolean isMouseButtonDown(int button) {
        if (button < InputConstants.MOUSE_BUTTON_LEFT || button > InputConstants.MOUSE_BUTTON_8) {
            return false;
        }
        int state = SDLMouse.SDL_GetMouseState(null, null);
        return (state & (1 << (button - 1))) != 0;
    }

    /**
     * 将键位值格式化为面向用户的名称。
     *
     * @param keyBind Epsilon 键位编码值
     * @return 操作结果
     */
    public static String format(int keyBind) {
        if (keyBind == NONE) {
            return EpsilonTranslations.Keybind.NONE.getTranslatedName();
        }
        if (isMouseButton(keyBind)) {
            return "Mouse " + (decodeMouseButton(keyBind) + 1);
        }
        return InputConstants.Type.KEYBOARD.getOrCreate(keyBind).getDisplayName().getString();
    }

}
