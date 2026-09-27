/*
 * 移植自 LiquidBounce (https://github.com/CCBlueX/LiquidBounce)，GPL-3.0-or-later
 * 源文件：src/main/kotlin/net/ccbluex/liquidbounce/utils/clicking/Clicker.kt
 *
 * [适配] 汇总（逐条见 tmp/scaffold-map-WCLICK.md）：
 *  1. 不继承 LB 的 `ValueGroup` / `EventListener`（Epsilon 无此体系）：构造参数改建字段，
 *     LB 的设置项改成字段 + 供阶段 7 接线的方法。
 *  2. 不订阅事件：`KeybindIsPressedEvent` 与 `GameTickEvent` 的处理器抽成 public 方法
 *     （`keybindIsPressedHandler` / `gameHandler`），由阶段 7 协调层在等价时机调用。
 *  3. 需要 epsilon.accesswidener 追加 `accessible field net/minecraft/client/Minecraft missTime I`
 *     （LB 的 liquidbounce.accesswidener:8 同款）。
 *  4. LB 的 `debugParameter(...)` 调试输出不搬（ModuleDebug 路径外）。
 */
package com.github.epsilon.modules.impl.movement.scaffold.clicking;

import com.github.epsilon.Constants;
import net.minecraft.client.KeyMapping;
import net.minecraft.world.entity.ai.attributes.Attributes;

import java.util.Arrays;
import java.util.Random;
import java.util.function.BooleanSupplier;

/**
 * 攻击调度器（attack scheduler）。
 * <p>LB 原文：
 * <pre>
 * An attack scheduler
 *
 * Minecraft is counting every click until it handles all inputs.
 * code:
 * while (this.options.keyAttack.wasPressed()) {
 *     this.doAttack();
 * }
 * @see [Minecraft.handleKeybinds]
 *
 * We are simulating this behaviour by calculating how many times we could have been clicked in the meantime of a tick.
 * This allows us to predict future actions and behave accordingly.
 * </pre>
 */
public class Clicker {

    /**
     * LB: {@code internal val RNG = Random()}（java.util.Random；Drag/Butterfly/NormalDistribution 直接引用）
     */
    public static final Random RNG = new Random();

    /** LB: {@code private const val DEFAULT_CYCLE_LENGTH = 20} */
    private static final int DEFAULT_CYCLE_LENGTH = 20;

    /** LB: {@code private var lastClickTime = 0L}（在 companion 中 → 所有 Clicker 实例共享同一静态值） */
    private static long lastClickTime = 0L;

    /** LB: {@code private val lastClickPassed get() = System.currentTimeMillis() - lastClickTime} */
    private static long lastClickPassed() {
        return System.currentTimeMillis() - lastClickTime;
    }

    // ---- 构造参数 ----

    /**
     * LB: {@code val parent: T}（T : EventListener）
     * [适配] Epsilon 无 LB 的 Module/EventListener 基类 → Object 占位，仅表示归属，不做任何事。
     */
    public final Object parent;

    /** LB: {@code val keyBinding: KeyMapping} */
    public final KeyMapping keyBinding;

    /** LB: {@code val itemCooldown: ItemCooldown? = ItemCooldown()}（可为 null） */
    public final ItemCooldown itemCooldown;

    /**
     * LB: {@code maxCps: Int = 60}（LB 里不是属性，只用于 {@code intRange(..., 1..maxCps)} 的上界）
     * [适配] 存字段供阶段 7 建设置项的上界使用。
     */
    public final int maxCps;

    /** LB: {@code name: String = "Clicker"}（LB 里作为 ValueGroup 组名） */
    public final String name;

    /** LB: {@code simulateAttackKeyDown: Boolean = false} */
    private final boolean simulateAttackKeyDown;

    // ---- Options ----

    /**
     * LB: {@code private val cps by intRange("CPS", 5..8, 1..maxCps, "clicks").onChanged { fill() }}
     * [待接线] 阶段 7 接 Epsilon 设置（Min/Max CPS，上界 maxCps），默认值同 LB（5..8）。
     */
    private ClickPattern.IntRange cps = new ClickPattern.IntRange(5, 8);

    /**
     * LB: {@code private val pattern by enumChoice("Technique", ClickPatterns.STABILIZED).onChanged { fill() }}
     * [待接线] 阶段 7 接 Epsilon 设置（Technique 枚举，默认 STABILIZED）。
     */
    private ClickPatterns pattern = ClickPatterns.STABILIZED;

    /**
     * 打空时 Minecraft 会有一段冷却才能再次攻击；该选项决定是否考虑这段冷却。
     * <p>LB 原文：This is useful for anti-cheats that detect if you are ignoring this cooldown.
     * Applies to the FailSwing feature as well.
     * <p>LB: {@code private val attackCooldown: Value<Boolean>? =
     * if (keyBinding == mc.options.keyAttack) boolean("AttackCooldown", true) else null}
     * [待接线] LB 该设置默认值为 true，且只有 keyBinding 是攻击键时才存在（否则为 null）；
     * 阶段 7 可用 Epsilon 的 {@code AttackCooldown} 设置替换这里的常量 true。
     */
    private final Boolean attackCooldown;

    /** LB: {@code private val clickArray = RollingClickArray(DEFAULT_CYCLE_LENGTH, 2)} */
    private final RollingClickArray clickArray = new RollingClickArray(DEFAULT_CYCLE_LENGTH, 2);

    /** 本 tick 内被 {@link #click} 真正执行掉的点击次数。LB: {@code var clickAmount: Int? = null private set} */
    private Integer clickAmount = null;

    /** LB: {@code var ticksSinceLastClick = 0 private set} */
    private int ticksSinceLastClick = 0;

    /** LB: {@code Clicker(parent, keyBinding)}（Kotlin 默认参数 → Java 重载链；默认 itemCooldown = ItemCooldown()） */
    public Clicker(Object parent, KeyMapping keyBinding) {
        this(parent, keyBinding, new ItemCooldown(), 60, "Clicker", false);
    }

    /** LB: {@code Clicker(parent, keyBinding, itemCooldown)} */
    public Clicker(Object parent, KeyMapping keyBinding, ItemCooldown itemCooldown) {
        this(parent, keyBinding, itemCooldown, 60, "Clicker", false);
    }

    /** LB: {@code Clicker(parent, keyBinding, itemCooldown, maxCps)} */
    public Clicker(Object parent, KeyMapping keyBinding, ItemCooldown itemCooldown, int maxCps) {
        this(parent, keyBinding, itemCooldown, maxCps, "Clicker", false);
    }

    /** LB: {@code Clicker(parent, keyBinding, itemCooldown, maxCps, name)} */
    public Clicker(Object parent, KeyMapping keyBinding, ItemCooldown itemCooldown, int maxCps, String name) {
        this(parent, keyBinding, itemCooldown, maxCps, name, false);
    }

    /** LB 的完整构造签名 */
    public Clicker(Object parent, KeyMapping keyBinding, ItemCooldown itemCooldown, int maxCps, String name,
                   boolean simulateAttackKeyDown) {
        this.parent = parent;
        this.keyBinding = keyBinding;
        this.itemCooldown = itemCooldown;
        this.maxCps = maxCps;
        this.name = name;
        this.simulateAttackKeyDown = simulateAttackKeyDown;

        // LB: init { itemCooldown?.let(this::tree) }（Epsilon 无 ValueGroup 树 → 仅持有引用，见字段注释）

        // LB: private val attackCooldown: Value<Boolean>? = if (keyBinding == mc.options.keyAttack) ...
        this.attackCooldown = keyBinding == Constants.mc.options.keyAttack ? Boolean.TRUE : null;

        // LB: init { fill() }（Clicker.kt:109-111）
        fill();
    }

    /**
     * LB: {@code private val passesAttackCooldown get() = !(attackCooldown?.get() == true && mc.missTime > 0)}
     */
    private boolean passesAttackCooldown() {
        return !(attackCooldown != null && attackCooldown && Constants.mc.missTime > 0);
    }

    /** LB: {@code open val isClickTick: Boolean get() = willClickAt(0)} */
    public boolean isClickTick() {
        return willClickAt(0);
    }

    /**
     * LB: {@code val ticksUntilClick: Int get() { ... } }
     */
    public int getTicksUntilClick() {
        for (int i = 0; i < clickArray.iterations; i++) {
            if (willClickAt(i)) {
                return i;
            }
        }

        return clickArray.iterations;
    }

    public int getTicksSinceLastClick() {
        return ticksSinceLastClick;
    }

    /** LB: {@code fun willClickAt(tick: Int = 1)}（Kotlin 默认参数 → Java 重载） */
    public boolean willClickAt() {
        return willClickAt(1);
    }

    public boolean willClickAt(int tick) {
        return getClickAmount(tick) > 0;
    }

    /**
     * LB: {@code fun getClickAmount(tick: Int = 0): Int}
     */
    public int getClickAmount(int tick) {
        if (isEnforcedClick(tick)) {
            return 1;
        }
        return clickArray.get(tick);
    }

    /** LB: {@code var clickAmount: Int? private set} 的 getter（与上面的 {@link #getClickAmount(int)} 同名重载，同 LB） */
    public Integer getClickAmount() {
        return clickAmount;
    }

    /**
     * LB: {@code private fun isEnforcedClick(tick: Int = 0): Boolean}
     * <p>[不搬] LB 第一行的 {@code debugParameter("HasCooldown")}（ModuleDebug 调试输出）。
     */
    private boolean isEnforcedClick(int tick) {
        boolean hasCooldown = hasCooldown();

        if (hasCooldown && itemCooldown != null && itemCooldown.isCooldownPassed(tick)) {
            return true;
        }

        return lastClickPassed() + (tick * 50L) >= 1000L;
    }

    /**
     * LB: {@code player.hasCooldown}（utils/entity/EntityExtensions.kt:212-213）：
     * <pre>
     * get() = !isOlderThanOrEqual1_8 && this.getAttributeValue(Attributes.ATTACK_SPEED) &lt; 20.0
     * </pre>
     * [适配] {@code isOlderThanOrEqual1_8} 来自 ViaFabricPlus 的服务器版本判定
     * （utils/client/ProtocolUtil.kt:89-95；未装 VFP 或判定失败时为 false）。Epsilon 无该机制 → 恒 false，
     * 因此 {@code !isOlderThanOrEqual1_8} 恒为 true，只剩属性判定。
     */
    private static boolean hasCooldown() {
        return Constants.mc.player.getAttributeValue(Attributes.ATTACK_SPEED) < 20.0;
    }

    /**
     * 每次调用（tick）点击 {@code cps} 次。冷却未过时不会点击。
     * {@code block} 应返回 true 表示这次点击成功，否则不计入点击次数。
     * <p>LB: {@code fun click(block: () -> Boolean)}
     * <p>[不搬] LB 里的 5 个 {@code debugParameter(...)}（Current Clicks / Peek Clicks / Last Click Passed /
     * Attack Cooldown / Item Cooldown）。
     */
    public void click(BooleanSupplier block) {
        int clicks = getClickAmount(0);

        int clickAmount = 0;

        // LB: repeat(clicks) { ... return@repeat ... }
        for (int i = 0; i < clicks; i++) {
            if (!passesAttackCooldown()) {
                continue;
            }

            // LB: itemCooldown?.isCooldownPassed() != false && block()
            if ((itemCooldown == null || itemCooldown.isCooldownPassed()) && block.getAsBoolean()) {
                clickAmount++;
                // LB: itemCooldown?.newCooldown()
                if (itemCooldown != null) {
                    itemCooldown.newCooldown();
                }
                lastClickTime = System.currentTimeMillis();
                ticksSinceLastClick = 0;
            }
        }

        this.clickAmount = clickAmount;
    }

    /**
     * 返回当前是否可以立刻执行一次点击尝试。门禁逻辑与 {@link #click} 调用 block 前完全一致。
     * <p>LB: {@code fun canExecuteClickNow(): Boolean}
     */
    public boolean canExecuteClickNow() {
        if (getClickAmount(0) <= 0) {
            return false;
        }

        if (!passesAttackCooldown()) {
            return false;
        }

        // LB: itemCooldown?.isCooldownPassed() != false
        return itemCooldown == null || itemCooldown.isCooldownPassed();
    }

    /**
     * LB: {@code private val gameHandler = handler<GameTickEvent>(priority = EventPriorityConvention.FIRST_PRIORITY) { ... } }
     * [适配] Epsilon 无 GameTickEvent/事件订阅 → 抽成 public 方法，由阶段 7 协调层在
     * {@code ClientTickEvent.Pre} 调用（LB 的 FIRST_PRIORITY 时机）。
     * <p>[不搬] LB 末尾两个 {@code debugParameter(...)}（Click Technique / Click Array）。
     */
    public void gameHandler() {
        ticksSinceLastClick++;
        clickAmount = null;

        if (clickArray.advance()) {
            int[] cycleArray = new int[DEFAULT_CYCLE_LENGTH];
            // LB: pattern.pattern.fill(cycleArray, cps, this)
            pattern.getPattern().fill(cycleArray, cps, this);
            clickArray.push(cycleArray);
        }
    }

    /**
     * LB: {@code private fun fill()}
     */
    private void fill() {
        clickArray.clear();
        int[] cycleArray = new int[DEFAULT_CYCLE_LENGTH];
        for (int i = 0; i < clickArray.iterations; i++) {
            Arrays.fill(cycleArray, 0);
            // LB: pattern.pattern.fill(cycleArray, cps, this)
            pattern.getPattern().fill(cycleArray, cps, this);
            clickArray.push(cycleArray);
            clickArray.advance(DEFAULT_CYCLE_LENGTH);
        }
    }

    /**
     * LB: {@code override fun parent() = parent}
     * [适配] Epsilon 无 LB 的 ValueGroup 基类 → 只保留结构性的 getParent()。
     */
    public Object getParent() {
        return parent;
    }

    // ---- 设置接线（对应 LB 的 Value 绑定；[待接线] 阶段 7 调用） ----

    /**
     * LB: {@code private val cps by intRange("CPS", 5..8, 1..maxCps, "clicks").onChanged { fill() }}
     * <p>写入 LB 的 CPS 闭区间（min = first，max = last），并按 {@code onChanged} 重填点击数组。
     */
    public void setCps(int min, int max) {
        this.cps = new ClickPattern.IntRange(min, max);
        fill();
    }

    public ClickPattern.IntRange getCps() {
        return cps;
    }

    /**
     * LB: {@code private val pattern by enumChoice("Technique", ClickPatterns.STABILIZED).onChanged { fill() }}
     */
    public void setPattern(ClickPatterns pattern) {
        this.pattern = pattern;
        fill();
    }

    public ClickPatterns getPattern() {
        return pattern;
    }

    public boolean isSimulateAttackKeyDown() {
        return simulateAttackKeyDown;
    }

    /**
     * LB 原文（Clicker.kt:153-167）：
     * <pre>
     * init {
     *     if (simulateAttackKeyDown &amp;&amp; keyBinding == mc.options.keyAttack) {
     *         handler&lt;KeybindIsPressedEvent&gt; { event -&gt;
     *             val clickAmount = this.clickAmount ?: return@handler
     *
     *             // It turns out, we only want to do this with [attackKey], otherwise
     *             // [useKey] will do unexpected things.
     *             if (event.keyBinding == keyBinding) {
     *                 // We want to simulate the click in order to
     *                 // allow the game to handle the logic as if we clicked
     *                 event.isPressed = clickAmount &gt; 0
     *             }
     *         }
     *     }
     * }
     * </pre>
     * [适配] Epsilon 无 KeybindIsPressedEvent（KeyboardInputEvent 不含「按键是否按下」位）→ 不订阅事件，
     * 抽成 public 方法供阶段 7 协调层在对应按键事件上调用。
     *
     * @param eventKeyBinding 触发事件的 KeyMapping（对应 LB 的 {@code event.keyBinding}）
     * @return 应写入 {@code event.isPressed} 的值；{@code null} 表示不修改事件
     * （含 LB 里 handler 未注册、以及 {@code clickAmount == null} 提前返回两种情况）
     */
    public Boolean keybindIsPressedHandler(KeyMapping eventKeyBinding) {
        // LB 的注册条件（不满足时等价于没有这个 handler）
        if (!(simulateAttackKeyDown && keyBinding == Constants.mc.options.keyAttack)) {
            return null;
        }

        // LB: val clickAmount = this.clickAmount ?: return@handler
        Integer clickAmount = this.clickAmount;
        if (clickAmount == null) {
            return null;
        }

        if (eventKeyBinding == keyBinding) {
            return clickAmount > 0;
        }

        return null;
    }

    /**
     * LB: {@code enum class ClickPatterns(override val tag: String, val pattern: ClickPattern) : Tagged}
     */
    public enum ClickPatterns {
        STABILIZED("Stabilized", StabilizedPattern.INSTANCE),
        EFFICIENT("Efficient", EfficientPattern.INSTANCE),
        SPAMMING("Spamming", SpammingPattern.INSTANCE),
        DOUBLE_CLICK("DoubleClick", DoubleClickPattern.INSTANCE),
        DRAG("Drag", DragPattern.INSTANCE),
        BUTTERFLY("Butterfly", ButterflyPattern.INSTANCE),
        NORMAL_DISTRIBUTION("NormalDistribution", NormalDistributionPattern.INSTANCE);

        private final String tag;

        private final ClickPattern pattern;

        ClickPatterns(String tag, ClickPattern pattern) {
            this.tag = tag;
            this.pattern = pattern;
        }

        public String getTag() {
            return tag;
        }

        public ClickPattern getPattern() {
            return pattern;
        }
    }

}
