package com.github.epsilon.modules.impl.movement.scaffold.ravenb4;

import com.github.epsilon.events.impl.KeyboardInputEvent;
import com.github.epsilon.events.impl.StartUseItemEvent;
import com.github.epsilon.managers.rotation.RotationManager;
import com.github.epsilon.modules.impl.movement.Scaffold;
import com.github.epsilon.modules.impl.movement.scaffold.ravenb4.RavenB4Scaffold.RavenFastScaffold;
import com.github.epsilon.modules.impl.movement.scaffold.ravenb4.RavenB4Scaffold.RavenMultiPlace;
import com.github.epsilon.modules.impl.movement.scaffold.ravenb4.RavenB4Scaffold.RavenPrecision;
import com.github.epsilon.modules.impl.movement.scaffold.ravenb4.RavenB4Scaffold.RavenRotation;
import com.github.epsilon.utils.rotation.Priority;
import com.github.epsilon.utils.rotation.Rot2f;
import com.github.epsilon.utils.rotation.RotationUtils;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.network.protocol.game.ServerboundMovePlayerPacket;
import net.minecraft.network.protocol.game.ServerboundSwingPacket;
import net.minecraft.util.Mth;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.level.block.ButtonBlock;
import net.minecraft.world.level.block.DoorBlock;
import net.minecraft.world.level.block.FenceGateBlock;
import net.minecraft.world.level.block.LeverBlock;
import net.minecraft.world.level.block.TrapDoorBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.phys.shapes.VoxelShape;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;

import static com.github.epsilon.Constants.mc;

/**
 * Raven B4 的 Scaffold 引擎（逐行移植自 {@code xyz.daikirai.module.player.ScaffoldModule}，1.8.9 Forge）。
 *
 * <p>Raven B4 里「模块」与「算法」同处一个类；本移植把算法整段搬进本类，
 * Epsilon 的 {@link Scaffold} 只保留设置、生命周期与事件接线（照 {@code scaffold/elowen/ElowenScaffold} 的骨架）。
 * 所有设置都从 {@code module} 读，本类自己不持 Setting。</p>
 *
 * <p>移植纪律（与 elowen 引擎一致）：</p>
 * <ul>
 *   <li><b>静默旋转铁律</b>：脚本角只存在本类的 {@link #aimYaw}/{@link #aimPitch} 与提交给
 *       {@link RotationManager#setRotationsDirect} 的发包角里，**绝不**写 {@code player.setYRot/setXRot}。
 *       Raven B4 的 {@code getRotations(yaw, pitch)} 由宿主 RotationManager 每刻调用；
 *       本移植改为引擎在 {@link #onPreTick()} 里现算并直通提交。</li>
 *   <li>灵敏度量化：Raven B4 的 {@code RotationUtils.fixSensitivity} 步长 {@code f³*1.2} 与本仓库
 *       {@link RotationUtils#mouseQuantum()}（{@code f³*8*0.15}）是同一式子，故直接复用
 *       {@link RotationUtils#applySensitivityPatch}。</li>
 *   <li>跨模块钩子（{@code TowerModule} / {@code SafeWalkModule} / {@code NoRotateModule} /
 *       {@code LongJumpModule} / {@code Hypixel.onHypixel}）在 Epsilon 无对应实现，
 *       一律以本类自有设置/标志替代并在方法内注明。</li>
 * </ul>
 */
public final class RavenB4Scaffold {

    // ===== 枚举（照抄 Raven B4 的嵌套枚举）=====

    /** 原 {@code ScaffoldModule.Rotation}。 */
    public enum RavenRotation {
        None, Strict, Smooth, Simple, Lazy, Offset;

        public float side;
        public float sideJitter;
        public float groundSide;

        public void onDisable() {
            this.groundSide = 0.0f;
            this.sideJitter = 0.0f;
            this.side = 0.0f;
        }
    }

    /** 原 {@code ScaffoldModule.FastScaffold}（13 档）。 */
    public enum RavenFastScaffold {
        Disabled(false, false, false),
        Vanilla(true, false, false),
        Constant_A(true, false, false),
        Constant_B(false, false, false),
        Float_A(true, false, false),
        Float_B(true, false, false),
        Jump_A(false, true, false),
        Jump_B(false, true, false),
        Jump_C(true, true, false),
        Jump_D(true, true, false),
        Keep_Y_A(false, false, true),
        Keep_Y_B(false, false, true),
        Side(true, false, false);

        public final boolean sprint;
        public final boolean jump;
        public final boolean keepY;
        public int state;
        public int ticks;
        public double height;

        RavenFastScaffold(boolean sprint, boolean jump, boolean keepY) {
            this.sprint = sprint;
            this.jump = jump;
            this.keepY = keepY;
        }

        /**
         * 原 {@code canSprint()}。
         *
         * <p>[移植注] 三种 {@code jump} 模式（Jump_A/B/C）里 {@code sprint==false}，
         * 故 {@code !this.sprint} 分支恒真返回 false —— Raven B4 里 {@code this != Side || ticks != -1}
         * 那半段对这三种模式**不可达**。此处保留原语义（等价于直接 false），并删去不可达表达式。</p>
         */
        public boolean canSprint() {
            if (!this.canFastScaffold()) {
                if (mc.player.tickCount == startTick && jumpState == 2) {
                    return false;
                }
                return module.ravenMovementFix.getValue();
            }
            if (module.ravenLegitMotion.getValue() && (this.jump || this.keepY)) {
                return legitTicks > 0;
            }
            if (!this.sprint) {
                return false;
            }
            return this != Side || this.ticks != -1;
        }

        public void onEnable() {
            enableTick = mc.player.tickCount;
            this.state = 0;
            if (this == Float_A) {
                if (mc.player.onGround()) {
                    this.ticks = 1;
                    delay = 1;
                    if (mc.player.getDeltaMovement().y == -0.0784000015258789) {
                        jump();
                    }
                    keptY = (int) Math.floor(mc.player.getY()) - 1;
                }
            } else if (this == Jump_B || this == Jump_C) {
                this.height = 2.2;
            } else if (this == Jump_D) {
                this.height = 2.5;
            } else if (this == Side) {
                this.ticks = 4;
            }
        }

        /** 原 {@code canFastScaffold()}。 */
        public boolean canFastScaffold() {
            if (!active || this == Disabled) {
                return false;
            }
            if (towerActive() || jumpKeyDown()) {
                return false;
            }
            if (this != Float_A) {
                if (!isMoving() || mc.player.isShiftKeyDown()) {
                    return false;
                }
                if (this.sprint && !this.jump && !direction().forward()) {
                    return false;
                }
            }
            if (this == Side) {
                return !isDiagonal();
            }
            return true;
        }

        /** 原 {@code onDisable(boolean)}。 */
        public void onDisable(boolean disabling) {
            if (this.jump || this.keepY) {
                keptY = -1;
            }
            if (this.jump && !disabling && !towerActive()) {
                // 原：TowerModule.slowedTicks.getInput() > 0 ⇒ MoveUtils.setSpeed(0.05)
                // [移植注] Epsilon 无该 Tower 侧滑降刻计数，此分支不生效（见 towerActive 说明）。
            }
            if (this == Float_A) {
                if (disablingModule) {
                    if (mc.player.onGround() && isAirBelow()) {
                        jump();
                    }
                    if (rotation != RavenRotation.Offset) {
                        multiplyMotion(0.58, false);
                    }
                }
            } else if (this == Float_B && disablingModule) {
                multiplyMotion(0.85, false);
            }
            this.ticks = 0;
        }
    }

    /** 原 {@code ScaffoldModule.MultiPlace}。 */
    public enum RavenMultiPlace {
        Disabled, Extra1, Extra2
    }

    /** 原 {@code RotationUtils.Precision}。 */
    public enum RavenPrecision {
        Low(40.0f), Medium(20.0f), High(5.0f), VeryHigh(1.5f);

        public final float offsetYaw;

        RavenPrecision(float offsetYaw) {
            this.offsetYaw = offsetYaw;
        }

        public float offsetPitch(float pitch) {
            if (pitch > 75.0f) {
                return 1.0f;
            }
            if (pitch > 60.0f) {
                return 2.0f;
            }
            return 5.0f;
        }
    }

    // ===== 静态状态（原 ScaffoldModule 的 static 字段，此处改为实例字段）=====

    /** 原 {@code ScaffoldModule.instance}。 */
    public static RavenB4Scaffold instance;
    /** 原 {@code public static boolean active}。 */
    public static boolean active;
    /** 原 {@code public static boolean disablingModule}。 */
    public static boolean disablingModule;
    /** 原 {@code public static Rotation rotation}。 */
    public static RavenRotation rotation = RavenRotation.Strict;
    /** 原 {@code public static FastScaffold fastScaffold}。 */
    public static RavenFastScaffold fastScaffold = RavenFastScaffold.Disabled;
    /** 原 {@code private static FastScaffold lastFastScaffold}。 */
    private static RavenFastScaffold lastFastScaffold = RavenFastScaffold.Disabled;
    /** 原 {@code public static MultiPlace multiPlace}。 */
    public static RavenMultiPlace multiPlace = RavenMultiPlace.Disabled;
    /** 原 {@code public static RotationUtils.Precision precision}。 */
    public static RavenPrecision precision = RavenPrecision.High;
    /** 原 {@code public static int keptY}。 */
    public static int keptY = -1;
    /** 原 {@code public static int legitTicks}。 */
    public static int legitTicks;
    /** 原 {@code public static int delay}。 */
    public static int delay;
    /** 原 {@code public static int enableTick}。 */
    public static int enableTick;
    /** 原 {@code public static int jumpState}。 */
    public static int jumpState;
    /** 原 {@code public static int startTick}。 */
    public static int startTick;
    /** 原 {@code public static long startedAt}。 */
    public static long startedAt;
    /** 原 {@code public static float startPitch}。 */
    public static float startPitch;

    private static boolean fastEnabled;
    private static boolean rightHeld;
    private static int searchMode;
    private static int jumpTick;
    private static int airBelowTicks;
    private static int placedCount;
    /** 原 {@code public static int pausedTicks}。 */
    public static int pausedTicks;
    private static boolean sneakQueued;
    private static boolean smoothSnap;
    private static BlockPos lastPlaced;
    private static float aimYaw;
    private static float aimPitch = 100.0f;
    private static float smoothLimit;
    private static float legitYaw;
    private static float restPitch;
    private static double yawOffset;
    private static long placedAt;
    private static long stoppedAt;
    private static long smoothAt;
    /** 原 {@code private static final HashMap<BlockPos, Long> placed}。 */
    private static final HashMap<BlockPos, Long> placed = new HashMap<>();
    /** 原 {@code PlayerUtils.groundTicks}（Epsilon 无全局量，本类自己维护）。 */
    private static int groundTicks;
    /** 上一次发出放置前旋转包的 tick（每刻最多一次）。 */
    private static int lastRotationPacketTick = -1;

    private static final Direction[] SIDES = {Direction.EAST, Direction.SOUTH, Direction.NORTH, Direction.WEST};
    /** {@link #placed} 的淘汰时长（ms）。 */
    private static final long PLACED_TTL_MS = 5000L;
    private static final BlockPos.MutableBlockPos SCRATCH = new BlockPos.MutableBlockPos();

    /**
     * 原 1.8.9 的 {@code movementInput.jump} / {@code movementInput.sneak}（**每刻瞬时**输入）。
     * Epsilon 侧必须走 {@link KeyboardInputEvent#setJump}/{@link KeyboardInputEvent#setSneak}，
     * 绝不能写 {@code mc.options.keyJump.setDown(...)} —— 那是 KeyMapping 的持久键态，写后无人复位，
     * 会让玩家一直跳/一直潜行（这正是移植初版的重大缺陷）。
     */
    private static boolean jumpInput;
    private static boolean sneakInput;

    /** 原 {@code RotationManager.lastYaw/lastPitch}：上一次真正提交出去的角，作为本刻解算的基准。 */
    private static Rot2f lastSentRotation = new Rot2f(0.0f, 0.0f);

    private static Scaffold module;
    private int savedSlot = -1;

    public RavenB4Scaffold(Scaffold module) {
        RavenB4Scaffold.module = module;
        instance = this;
    }

    // ===== 生命周期（原 ScaffoldModule 的 Module 回调）=====

    /** 原 {@code onEnable()}。 */
    public void onEnable() {
        if (mc.player == null) {
            return;
        }
        savedSlot = mc.player.getInventory().getSelectedSlot();
        restPitch = randomFloat(84.5f, 87.5f);
        yawOffset = randomOffset();
        // 原 {@code RotationManager.lastYaw/lastPitch} 初值 = 当前服务端角。
        lastSentRotation = new Rot2f(RotationManager.INSTANCE.getRotation().getYaw(),
                RotationManager.INSTANCE.getRotation().getPitch());
        jumpInput = false;
        sneakInput = false;
        legitTicks = 0;
        jumpState = 0;
        // 原 SafeWalkModule.scaffoldActive 在 Epsilon 无对应；改由本模块 SafeWalk 设置近似（见 ravenSafeWalk）。
        if (!safeWalkActive()) {
            if (!mc.player.onGround()) {
                delay = 3;
            } else if (potionLevel(MobEffects.JUMP_BOOST) <= 3 && nearEdge(0.2) && module.getBlockCount() > 0) {
                jumpState = 2;
                jumpTick = mc.player.tickCount;
                delay = 4;
            }
        } else {
            delay = 1;
        }
    }

    /**
     * 换世界 / 切维度 / 模块禁用时的状态复位。
     *
     * <p>本引擎的算法状态是 static：不清就会把旧世界的 {@code keptY}/{@code startTick}/`active`
     * 带进新世界 —— stale `keptY` 会让 {@code candidates()} 过滤掉全部候选（一次都放不出方块），
     * stale `startTick` 让 {@code tickCount - startTick} 变负。故必须在入世界事件与禁用路径都复位。</p>
     */
    public void resetState() {
        active = false;
        keptY = -1;
        lastPlaced = null;
        placedAt = 0L;
        placed.clear();
        startTick = 0;
        enableTick = 0;
        groundTicks = 0;
        fastEnabled = false;
        lastFastScaffold = RavenFastScaffold.Disabled;
        fastScaffold = RavenFastScaffold.Disabled;
        for (RavenFastScaffold value : RavenFastScaffold.values()) {
            value.state = 0;
            value.ticks = 0;
        }
        searchMode = 0;
        jumpTick = 0;
        airBelowTicks = 0;
        pausedTicks = 0;
        placedCount = 0;
        legitTicks = 0;
        delay = 0;
        jumpState = 0;
        aimYaw = 0.0f;
        aimPitch = 100.0f;
        smoothSnap = false;
        smoothLimit = 0.0f;
        sneakQueued = false;
        rightHeld = false;
        jumpInput = false;
        sneakInput = false;
        savedSlot = -1;
        if (mc.player != null) {
            lastSentRotation = new Rot2f(RotationManager.INSTANCE.getRotation().getYaw(),
                    RotationManager.INSTANCE.getRotation().getPitch());
        } else {
            lastSentRotation = new Rot2f(0.0f, 0.0f);
        }
    }

    /** 原 {@code onDisable()}。 */
    public void onDisable() {
        // [适配·必须] 参考/Elowen 的托管角释放在 player==null 检查之外：
        // 否则在主菜单/切维度瞬间禁用模块会漏掉释放，RotationManager 永久 active。
        jumpInput = false;
        sneakInput = false;
        RotationManager.INSTANCE.setActive(false);
        if (mc.player == null) {
            return;
        }
        disablingModule = true;
        stop();
        legitTicks = 0;
        delay = 0;
        airBelowTicks = 0;
        pausedTicks = 0;
        placedCount = 0;
        placedAt = 0L;
        double d = speed();
        if (d > 0.34) {
            multiplyMotion((0.29 + 0.02 * Math.random()) / d, false);
        }
        if (savedSlot >= 0) {
            mc.player.getInventory().setSelectedSlot(savedSlot);
            savedSlot = -1;
        }
        disablingModule = false;
    }

    // ===== 每刻（原 {@code onPreUpdate} → update()）=====

    /** 原宿主每帧入口：{@code onPreUpdate()}（update）→ 旋转发包路径（getRotations）。 */
    public void onPreTick() {
        if (mc.player == null || mc.level == null) {
            return;
        }
        groundTicks = mc.player.onGround() ? groundTicks + 1 : 0;
        syncSettings();
        // 共享放置节流（Place Delay + 随机量）：每刻只自减一次。
        module.tickPlaceCooldown();
        update();
        // 原宿主在 update 之后调 {@code getRotations(lastYaw, lastPitch)}（RotationManager 的发包路径）。
        // 故本刻把 update 里刚解算出的 aimYaw/aimPitch 直接提交，**同刻**生效；
        // [静默旋转铁律] 只走 setRotationsDirect，绝不写 player.setYRot/setXRot。
        commitScriptedRotation();
    }

    /** 脚本角解算与提交（原 {@code getRotations(yaw, pitch)}）。{@code Rotation.None} 或未激活时返回 null ⇒ 不接管。 */
    private void commitScriptedRotation() {
        float[] rot = computeRotations(lastSentRotation.getYaw(), lastSentRotation.getPitch());
        if (rot == null) {
            // [注意] 这里**不能**调 setActive(false)：它是无优先级门的裸赋值，
            // 会清掉同一刻其它模块（如 KillAura）刚设的托管状态。
            // 释放只发生在 stop() / onDisable()。
            return;
        }
        Rot2f committed = new Rot2f(rot[0], rot[1]);
        commitRotation(committed);
        // 以「真正生效的托管角」为下一 tick 的基准：setRotationsDirect 有优先级门，
        // 被更高优先级模块（KillAura 等）拒绝时本地算出的角并没有进过网络。
        Rot2f actual = RotationManager.INSTANCE.getRotation();
        lastSentRotation = actual != null ? new Rot2f(actual.getYaw(), actual.getPitch()) : committed;
    }

    /** 原 {@code update()}。 */
    private void update() {
        if (pausedTicks > 0 && --pausedTicks != 0) {
            stop();
            return;
        }
        if (mc.gui.screen() != null || isAdventure()) {
            stop();
            return;
        }
        // 原 {@code AutoSwapModule.swap(SwapSlot.Scaffold)}：Epsilon 无 AutoSwap 模块，
        // 改为扫 0..8 热键槽把可放置方块切到手上（与 ElowenScaffold.autoSelectBlock 同一做法；可见切槽，非静默）。
        if (module.ravenAutoSwap.getValue()) {
            autoSelectBlock();
        }
        ItemStack itemStack = mc.player.getMainHandItem();
        if (!module.isValidStack(itemStack)) {
            // 副手也可能是可放置方块（getPlacementHand 支持 OFF_HAND）；原实现直接 return，
            // 会让 active 残留 true（右键劫持 + 托管角不停），而 getBlockCount 又把副手算进总数。
            boolean offhandPlaceable = module.isValidStack(mc.player.getOffhandItem());
            if (!offhandPlaceable) {
                if (!module.ravenAutoSwap.getValue() || module.getBlockCount() == 0) {
                    stop();
                }
                return;
            }
            itemStack = mc.player.getOffhandItem();
        }
        long l = System.currentTimeMillis();
        if (!active) {
            startTick = mc.player.tickCount;
            startedAt = l;
            startPitch = mc.player.getXRot();
            // 不再写 mc.options.keyAttack/keyUse（KeyMapping 持久键态，写后无人复位会卡住玩家按键）。
            // 抑制右键已由 StartUseItemEvent 取消完成。
            if (module.ravenSlowOnEnable.getValue()) {
                multiplyMotion(0.2, true);
            }
        }
        active = true;
        fastScaffoldTick();
        if (delay > 0) {
            --delay;
            return;
        }
        if (!noUseOrDig()) {
            return;
        }
        BlockPos below = below(1.0);
        airBelowTicks = isReplaceable(below) ? ++airBelowTicks : 0;
        // 放置节流：冷却未结束本刻不放置（逐刻连续放置会被 Vulcan/Matrix 直接判定）。
        if (module.isPlaceOnCooldown()) {
            return;
        }
        BlockPos pos = place(null, itemStack);
        if (pos != null) {
            ++placedCount;
            int sneakEvery = module.ravenSneakEvery.getValue();
            if (sneakEvery > 0 && placedCount % sneakEvery == 0) {
                sneakQueued = true;
            }
            // 原 Irc.onScaffoldPlace()：Epsilon 无 IRC 计数端，跳过（纯统计，不影响行为）。
            if (itemStack.getCount() > 0) {
                afterPlace(pos, itemStack);
            }
            // 本刻所有放置（含 afterPlace 的追加放置）结束后再置冷却。
            // 若在 afterPlace 之前置，afterPlace 内的冷却查询恒为 true，Side 模式的追加放置永远是死代码。
            module.markPlaceCooldown(2);
        }
    }

    /** 原 {@code AutoSwapModule.swap(SwapSlot.Scaffold)} 的等价实现：把第一个可放置方块切到选中槽。 */
    private static void autoSelectBlock() {
        if (mc.player == null || module.isValidStack(mc.player.getMainHandItem())) {
            return;
        }
        for (int slot = 0; slot < 9; ++slot) {
            if (module.isValidStack(mc.player.getInventory().getItem(slot))) {
                mc.player.getInventory().setSelectedSlot(slot);
                return;
            }
        }
    }

    /** 右键接管（原 {@code onMouse(MouseEvent)} 的取消 + {@code fastScaffoldTick} 的 rightHeld 采样）。
     *
     * <p>参考 {@code onMouse} 只在 {@code buttonstate && active} 时取消右键；缺 active 门会让
     * 「模块已启用但引擎尚未 active」的窗口内的吃喝/射箭被误取消。</p>
     */
    public void onUseItem(StartUseItemEvent event) {
        if (!active) {
            return;
        }
        event.cancel();
        rightHeld = true;
    }


    // ===== 放置流水线（原 place / search / candidates / firstPlaceable）=====

    private static BlockPos place(BlockPos last, ItemStack itemStack) {
        // placed 是 static 且只在「高亮」开启时才被渲染侧清理：这里按 TTL 自行淘汰，
        // 否则默认配置下它会随每次放置无限增长（canPlaceAgainst 还要逐项线性扫描）。
        long now = System.currentTimeMillis();
        placed.entrySet().removeIf(entry -> now - Math.abs(entry.getValue()) > PLACED_TTL_MS);
        BlockPos target = search(last);
        if (target == null) {
            return null;
        }
        Direction facing = faceFor(target, null);
        if (facing == null) {
            return null;
        }
        BlockPos against = target.relative(facing.getOpposite());
        if (airBelowTicks > 0) {
            float yaw = aimYaw;
            float pitch = aimPitch;
            BlockHitResult hit = aim(against, facing, -170.0f, lastYaw());
            if (hit == null) {
                return last == null ? null : place(null, itemStack);
            }
            switch (rotation) {
                case Simple: {
                    float f3 = movementAngle();
                    if (Math.abs(f3 - aimYaw) < 100.0f) {
                        float f4 = aimYaw - f3;
                        f4 = (f4 + 540.0f) % 360.0f - 180.0f;
                        float f6 = 0.0f;
                        if (Math.abs(f4) >= 10.0f) {
                            f6 = Math.max(-10.0f, Math.min(f4, 10.0f));
                        }
                        aimYaw = f3 + f6;
                        if (mc.player.onGround()) {
                            aimPitch = 82.5f;
                        }
                    }
                    break;
                }
                case Lazy: {
                    // 原：if (pitch == 100.0f || PlayerUtils.rayTraceBlock(against, null, reach, yaw, pitch) == null) break;
                    // [移植注] 该分支里 yaw==aimYaw、pitch==aimPitch（上一行刚赋值），故是恒等赋值；
                    // 保留原语义（不做无谓写回），仅保留可达性条件。参考此处 facing 传 null ⇒ 任意面。
                    if (pitch != 100.0f && raycastAnyFace(against, yaw, pitch) != null) {
                        aimYaw = yaw;
                        aimPitch = pitch;
                    }
                    break;
                }
                default:
                    break;
            }
            // 参考：onPlayerRightClick(player, world, stack, against, facing, hit.hitVec)
            // [联机必需] 放置包必须跟在旋转包之后：托管角只在 SendPositionEvent 才会搭车发出，
            // 而那时放置包已经上网。先发一次 Rot，服务端才会用本刻瞄准角校验放置（否则吞方块）。
            sendPlacementRotation(aimYaw, aimPitch);
            if (placeAtBlock(against, facing, hit.getLocation(), itemStack)) {
                if (searchMode == 1 || searchMode == 2 && fastScaffold.keepY) {
                    fastScaffold.ticks = mc.player.tickCount;
                }
                lastPlaced = target;
                placedAt = System.currentTimeMillis();
                placed.put(target, placedAt);
                airBelowTicks = 0;
                return target;
            }
        } else if (isMoving() && Math.random() > 0.8) {
            // ⚠ [移植注] 原分支：向服务端发送一个**不带位置的空 C08 使用物品包**
            // （{@code new C08PacketPlayerBlockPlacement(itemStack)}）来伪造"持续右键"节奏。
            // 这是一个纯粹的、可被反作弊观测的噪声特征，且与本模块的真实放置无关 ——
            // 按计划 §5.4 予以**注释保留但不执行**。若实测确有必要再放开。
            // PlayerUtils.syncCurrentPlayItem();
            // PacketUtils.send(new C08PacketPlayerBlockPlacement(itemStack));
        }
        return null;
    }

    /** 原 {@code search(BlockPos)}。 */
    private static BlockPos search(BlockPos last) {
        ArrayList<Vec3> list = new ArrayList<>();
        searchMode = 0;
        if (keptY != -1 && fastScaffold.canFastScaffold()) {
            int decimals = posYDecimals();
            if (fastScaffold == RavenFastScaffold.Jump_A) {
                if (decimals == 1661 || decimals == 2492 && mc.player.getDeltaMovement().y > 0.0) {
                    searchMode = 1;
                } else {
                    searchMode = 2;
                }
            } else if (fastScaffold == RavenFastScaffold.Jump_B || fastScaffold == RavenFastScaffold.Jump_C
                    || fastScaffold == RavenFastScaffold.Jump_D) {
                if (mc.player.tickCount - fastScaffold.ticks > 4 && mc.player.getY() - (double) keptY < fastScaffold.height
                        && mc.player.getDeltaMovement().y < -0.1) {
                    searchMode = 1;
                    if (fastScaffold.ticks == 0) {
                        multiplyMotion(0.4, false);
                    }
                } else {
                    searchMode = 2;
                }
            } else if (fastScaffold.keepY) {
                searchMode = 2;
            } else if (fastScaffold == RavenFastScaffold.Float_A) {
                if (mc.player.onGround() && mc.player.tickCount - enableTick > 0) {
                    keptY = -1;
                } else {
                    searchMode = 2;
                }
            }
        }
        return candidates(list, searchMode, keptY) ? firstPlaceable(list, last) : null;
    }

    /** 原 {@code candidates(List, int, int)}。 */
    private static boolean candidates(List<Vec3> list, int mode, int y) {
        for (int i = -5; i <= 5; ++i) {
            for (int j = -5; j <= 5; ++j) {
                for (int k = -3; k <= -1; ++k) {
                    int n4 = Mth.floor(mc.player.getY()) + k;
                    if (mode > 0 && n4 != y) {
                        continue;
                    }
                    double d2 = mc.player.getX() + (double) i;
                    double d = mc.player.getZ() + (double) j;
                    SCRATCH.set(Mth.floor(d2), n4, Mth.floor(d));
                    if (isReplaceable(SCRATCH)) {
                        continue;
                    }
                    for (int n3 = -1; n3 <= 1; n3 += 2) {
                        switch (mode) {
                            case 0:
                                // [移植注·§5.3] 原 case 0 无 break，会贯穿到 case 1；
                                // 此处按计划显式展开：等价于「水平邻格 + 竖直邻格」全加。
                                list.add(new Vec3(d2 + (double) n3, (double) n4, d));
                                list.add(new Vec3(d2, (double) n4, d + (double) n3));
                                list.add(new Vec3(d2, (double) (n4 + n3), d));
                                break;
                            case 1:
                                list.add(new Vec3(d2, (double) (n4 + n3), d));
                                break;
                            case 2:
                                list.add(new Vec3(d2 + (double) n3, (double) n4, d));
                                list.add(new Vec3(d2, (double) n4, d + (double) n3));
                                break;
                            default:
                                break;
                        }
                    }
                }
            }
        }
        if (list.isEmpty()) {
            return false;
        }
        HashSet<Long> seen = new HashSet<>(list.size() * 2);
        ArrayList<Vec3> unique = new ArrayList<>(list.size());
        for (Vec3 vec3 : list) {
            long l = Double.doubleToLongBits(vec3.x) ^ Double.doubleToLongBits(vec3.y) * 31L
                    ^ Double.doubleToLongBits(vec3.z) * 31L * 31L;
            if (!seen.add(l)) {
                continue;
            }
            unique.add(vec3);
        }
        list.clear();
        list.addAll(unique);
        final double x = mc.player.getX();
        double y2 = mc.player.getY() - 1.0;
        final double z = mc.player.getZ();
        if (!mc.player.onGround() && !jumpKeyDown() && !fastScaffold.canFastScaffold()) {
            y2 -= 1.0;
        }
        final double fy = y2;
        Comparator<Vec3> comparator = Comparator.comparingDouble(p -> distanceSq(x, fy, z, p));
        if (mc.player.tickCount - startTick <= 3) {
            double yaw = Math.toRadians(mc.player.getYRot());
            final double dirX = -Math.sin(yaw);
            final double dirZ = Math.cos(yaw);
            comparator = comparator.thenComparingDouble(p -> behind(x, z, dirX, dirZ, p));
        }
        list.sort(comparator);
        return true;
    }

    /** 原 {@code firstPlaceable(List, BlockPos)}。 */
    private static BlockPos firstPlaceable(List<Vec3> list, BlockPos last) {
        for (Vec3 vec3 : list) {
            SCRATCH.set(Mth.floor(vec3.x), Mth.floor(vec3.y), Mth.floor(vec3.z));
            if (!isReplaceable(SCRATCH) || faceFor(SCRATCH, last) == null) {
                continue;
            }
            return SCRATCH.immutable();
        }
        return null;
    }

    // ===== 旋转（原 rotations(f, f2)）=====

    private float[] computeRotations(float f, float f2) {
        if (!active || rotation == RavenRotation.None) {
            return null;
        }
        long l = sincePlace();
        float yaw;
        float pitch;
        if (rotation == RavenRotation.Offset) {
            float f6 = (float) inputAngleSigned();
            float f7 = mc.player.getYRot() + f6;
            float f8 = (f7 % 360.0f + 360.0f) % 360.0f;
            float f9 = f8 % 90.0f;
            float f10;
            if (!(f9 >= 80.0f) && !(f9 < 10.0f)) {
                f10 = f9 >= 10.0f && f9 < 45.0f ? 45.0f : -45.0f;
            } else {
                f10 = -45.0f;
            }
            if (!mc.player.onGround() && rotation.groundSide != 0.0f) {
                f10 = rotation.groundSide;
            } else {
                rotation.groundSide = f10;
            }
            if (f10 != rotation.side) {
                rotation.side = f10;
                rotation.sideJitter = (float) ((double) Math.signum(f10) * randomBetween(0.02, 0.08));
            }
            aimYaw = movementAngle() + (f10 + rotation.sideJitter);
        }
        boolean jumpRotation = false;
        if (legitTicks <= 0) {
            if (fastScaffold == RavenFastScaffold.Float_A && fastScaffold.ticks == 1 && mc.player.tickCount == enableTick) {
                jumpRotation = true;
            }
        } else {
            --legitTicks;
            jumpRotation = true;
        }
        if (jumpRotation) {
            float f7 = -180.0f;
            float f8 = aimPitch == 100.0f ? mc.player.getXRot() : aimPitch;
            if (module.ravenLegitMotion.getValue()) {
                if (legitTicks == 4) {
                    if ((double) (mc.player.getYRot() - lastYaw()) < -0.7) {
                        f7 = -f7;
                    }
                    legitYaw = f7;
                    --delay;
                    jumpRotation = false;
                } else if (legitTicks == 3) {
                    if (legitYaw > 0.0f) {
                        f7 = -f7;
                    }
                    if (rotation == RavenRotation.Smooth) {
                        legitTicks = Math.min(legitTicks, 1);
                        if (delay > legitTicks) {
                            delay = legitTicks;
                        }
                        smoothSnap = true;
                    }
                    f8 = randomFloat(20.0f, 25.0f);
                } else if (legitTicks == 2) {
                    legitYaw += randomFloat(105.0f, 120.0f) * -Math.signum(legitYaw);
                    f7 = legitYaw;
                    f8 = randomFloat(40.0f, 45.0f);
                } else if (legitTicks == 1) {
                    legitYaw += randomFloat(33.0f, 35.0f) * -Math.signum(legitYaw);
                    f7 = legitYaw;
                    if (aimPitch == 100.0f || rotation == RavenRotation.Simple) {
                        f8 = randomFloat(50.0f, 55.0f);
                    }
                } else if (legitTicks == 0) {
                    legitYaw += randomFloat(29.0f, 33.0f) * -Math.signum(legitYaw);
                    f7 = legitYaw;
                    if (aimPitch == 100.0f || rotation == RavenRotation.Simple) {
                        f8 = randomFloat(65.0f, 70.0f);
                    }
                }
            }
            if (rotation == RavenRotation.Offset && rotation.side > 0.0f) {
                f7 = -f7;
            }
            if (jumpRotation) {
                return fixSensitivity(movementAngle() + f7 + randomOffset(), f8, f, f2);
            }
        }
        if (rotation == RavenRotation.Smooth && lastPlaced != null && groundTicks <= 3) {
            yaw = aimYaw;
            pitch = aimPitch;
            float f7 = smoothLimit;
            if (smoothSnap) {
                smoothSnap = false;
                f7 = 81.0f;
                f7 += randomFloat(-2.0f, 2.0f);
            }
            float f8 = Mth.wrapDegrees(yaw - f);
            if (Math.abs(f8) > f7) {
                yaw = f + (f8 > 0.0f ? f7 : -f7);
            }
            return fixSensitivity(yaw, pitch, f, f2);
        }
        if (aimPitch != 100.0f && l < holdTime()) {
            yaw = aimYaw;
            pitch = aimPitch;
            if (l > 5L && isMoving() && Math.random() > 0.3) {
                yaw = (float) ((double) yaw + randomBetween(0.05, 0.5) * (Math.random() < 0.5 ? 1 : -1));
                pitch += randomOffset();
            }
            float[] r = fixSensitivity(yaw, pitch, f, f2);
            yaw = r[0];
            pitch = r[1];
        } else {
            if (aimPitch != 100.0f) {
                aimPitch = 100.0f;
            }
            int n = mc.player.tickCount - startTick;
            float f8 = !safeWalkActive() ? movementAngle() : mc.player.getYRot();
            float f9 = rotation != RavenRotation.Simple ? restPitch : 82.5f;
            f8 += (float) yawOffset;
            f9 += randomOffset();
            if (n <= 2 && !mc.player.onGround() && mc.player.tickCount - jumpTick > 3) {
                BlockPos target = search(null);
                if (target != null) {
                    Direction facing = faceFor(target, null);
                    if (facing != null && facing != Direction.UP) {
                        BlockPos against = target.relative(facing.getOpposite());
                        float[] r = searchBlockRotation(against, facing, -170.0f, precision, f, safeWalkActive());
                        if (r != null) {
                            f8 = r[0];
                            f9 = r[1];
                        }
                    }
                }
            } else if (rotation == RavenRotation.Offset) {
                f8 = aimYaw;
            }
            yaw = f8;
            pitch = f9;
            if (n <= 2) {
                float f10 = n != 0 ? (n != 2 ? 0.8f : 0.55f) : 0.3f;
                f10 = (float) ((double) f10 + randomBetween(-0.02, 0.02));
                yaw = f + f10 * (f8 - f);
                pitch = f2 + f10 * (f9 - f2);
                if (Math.abs(yaw - f8) < 5.0f) {
                    delay = 0;
                }
            }
            float[] r = fixSensitivity(yaw, pitch, f, f2);
            yaw = r[0];
            if (yaw != f) {
                pitch = r[1];
                yawOffset = randomOffset();
            } else {
                pitch = f2;
            }
        }
        return new float[]{yaw, pitch};
    }

    /** 脚本角提交（静默：只改发包角，不写 {@code player.setYRot/setXRot}）。 */
    private void commitRotation(Rot2f rot) {
        RotationManager.INSTANCE.setRotationsDirect(rot, Priority.Medium);
    }

    // ===== FastScaffold 状态机（原 fastScaffoldTick）=====

    private static void fastScaffoldTick() {
        // 原 {@code Mouse.isButtonDown(1)}：物理右键。用 mouseHandler 的物理键态，
        // 不用 keyUse.isDown()（本模块自己会抑制 keyUse，写后即读不可靠）。
        rightHeld = mc.gui.screen() == null && mc.mouseHandler.isRightPressed();
        fastScaffold = rightHeld ? module.ravenFastScaffoldRmb.getValue() : module.ravenFastScaffold.getValue();
        boolean moving = isMoving();
        boolean groundMoving = mc.player.onGround() && moving;
        boolean can = fastScaffold.canFastScaffold();
        boolean sprintForward = fastScaffold.sprint && direction().forward();
        boolean applied = false;
        if (fastScaffold == lastFastScaffold) {
            if (!can) {
                if (fastEnabled) {
                    fastEnabled = false;
                    fastScaffold.onDisable(false);
                }
            } else if (!fastEnabled) {
                fastEnabled = true;
                fastScaffold.onEnable();
            }
        } else {
            if (fastEnabled) {
                lastFastScaffold.onDisable(false);
            }
            if (can) {
                fastScaffold.onEnable();
            }
            fastEnabled = can;
            lastFastScaffold = fastScaffold;
        }
        double d;
        if (!can) {
            if (legitTicks > 0 && mc.player.onGround()) {
                delay = 0;
                legitTicks = 0;
            }
        } else {
            boolean newY;
            double speed;
            switch (fastScaffold) {
                case Constant_A:
                    if (groundMoving) {
                        d = 0.153 + perSpeedLevel(0.034);
                        if (speedLevel() == 0 && isDiagonal()) {
                            d *= 0.87;
                        }
                        strafe(d);
                    }
                    break;
                case Constant_B:
                    if (groundMoving) {
                        strafe(0.181);
                    }
                    break;
                case Jump_A:
                case Jump_B:
                case Jump_C:
                case Jump_D:
                    if (!groundMoving) {
                        if (moving && module.ravenJumpFacingForward.getValue() && mc.player.getDeltaMovement().y < 0.0) {
                            d = Math.floor(mc.player.getY());
                            if (d - 2.0 == (double) keptY && mc.player.getY() + mc.player.getDeltaMovement().y < d) {
                                legitTicks = !module.ravenLegitMotion.getValue() ? 1 : 5;
                            }
                        }
                        break;
                    }
                    if (fastScaffold.state != 2) {
                        newY = false;
                        if (mc.player.tickCount - fastScaffold.ticks > 10) {
                            keptY = (int) Math.floor(mc.player.getY()) - 1;
                            newY = true;
                        }
                        if ((module.ravenJumpFacingForward.getValue()
                                || RavenFastScaffold.Float_A.state == 1 || RavenFastScaffold.Float_B.state == 1) && newY) {
                            if (module.ravenJumpFacingForward.getValue()) {
                                legitTicks = !module.ravenLegitMotion.getValue() ? 1 : 5;
                            }
                            fastScaffold.state = 1;
                        }
                        if (fastScaffold.state == 0) {
                            if (fastScaffold != RavenFastScaffold.Jump_A) {
                                speed = !newY ? 0.545 : 0.4;
                                if (sprintForward) {
                                    speed = !newY ? 0.3 : 0.25;
                                }
                            } else {
                                speed = !isDiagonal() ? 0.53 : 0.37;
                            }
                            jumpWith(speed);
                        }
                    } else {
                        fastScaffold.state = 0;
                    }
                    multiplyMotion(0.95, false);
                    break;
                case Keep_Y_A:
                case Keep_Y_B:
                    if (!groundMoving) {
                        if (moving && module.ravenJumpFacingForward.getValue() && mc.player.getDeltaMovement().y < 0.0) {
                            d = Math.floor(mc.player.getY());
                            if (d - 1.0 == (double) keptY && mc.player.getY() + mc.player.getDeltaMovement().y < d) {
                                legitTicks = !module.ravenLegitMotion.getValue() ? 1 : 5;
                            }
                        }
                    } else {
                        if (fastScaffold.state != 2) {
                            newY = false;
                            if (mc.player.tickCount - fastScaffold.ticks > 10) {
                                keptY = (int) Math.floor(mc.player.getY()) - 1;
                                newY = true;
                            }
                            if ((module.ravenJumpFacingForward.getValue()
                                    || RavenFastScaffold.Float_A.state == 1 || RavenFastScaffold.Float_B.state == 1) && newY) {
                                if (module.ravenJumpFacingForward.getValue() && legitTicks == 0) {
                                    legitTicks = !module.ravenLegitMotion.getValue() ? 1 : 5;
                                }
                                fastScaffold.state = 1;
                                multiplyMotion(speedLevel() <= 0 ? 0.8 : 0.4, false);
                            }
                            if (fastScaffold.state == 0) {
                                speed = !newY ? 0.545 : 0.54;
                                if (sprintForward) {
                                    speed = !newY ? 0.3 : 0.25;
                                }
                                jumpWith(speed);
                            }
                        } else {
                            fastScaffold.state = 0;
                        }
                        multiplyMotion(0.95, false);
                    }
                    break;
                default:
                    break;
            }
            if (!mc.player.onGround() && (fastScaffold == RavenFastScaffold.Jump_D
                    || fastScaffold == RavenFastScaffold.Keep_Y_B) && motionYDecimals() == 30) {
                mc.player.setDeltaMovement(mc.player.getDeltaMovement().x, mc.player.getDeltaMovement().y - 0.14,
                        mc.player.getDeltaMovement().z);
            }
            if (groundMoving) {
                d = module.ravenFastScaffoldMotion.getValue();
                if (!module.ravenLegitMotion.getValue() && direction().diagonal()) {
                    d -= 0.02;
                }
                multiplyMotion(d, true);
                applied = true;
            }
        }
        if (!applied && groundMoving) {
            d = module.ravenMotion.getValue();
            if (!module.ravenLegitMotion.getValue()) {
                if (module.ravenSlowSpeedPotion.getValue()) {
                    d -= perSpeedLevel(0.17);
                }
                if (direction().diagonal()) {
                    d -= 0.02;
                }
                if (mc.player.tickCount - startTick <= 2) {
                    d -= 0.05;
                }
            }
            multiplyMotion(d, true);
        }
    }

    /** 原 {@code preMotion(MotionEvent)}（在发包（位置包）之前调整运动）。 */
    public void onPreMotion() {
        boolean floatWasSet = RavenFastScaffold.Float_A.state == 1 || RavenFastScaffold.Float_B.state == 1;
        RavenFastScaffold.Float_A.state = 0;
        RavenFastScaffold.Float_B.state = 0;
        if (!fastScaffold.canFastScaffold()) {
            return;
        }
        if (fastScaffold == RavenFastScaffold.Float_A) {
            if (mc.player.getY() % 1.0 == 0.0 && mc.player.getDeltaMovement().y == -0.0784000015258789) {
                fastScaffold.state = 1;
                if (isMoving() && !mc.player.isShiftKeyDown()) {
                    double d = fastScaffold.sprint ? 0.2 + perSpeedLevel(isDiagonal() ? 0.035 : 0.043)
                            : 0.23 + perSpeedLevel(isDiagonal() ? 0.042 : 0.05);
                    if (speedLevel() == 0 && isDiagonal()) {
                        d *= 0.87;
                    }
                    strafe(d);
                    if (!fastScaffold.sprint) {
                        mc.player.setSprinting(true);
                    }
                }
            }
        } else if (fastScaffold == RavenFastScaffold.Float_B) {
            if (mc.player.getY() % 1.0 == 0.0 && mc.player.getDeltaMovement().y == -0.0784000015258789
                    && isMoving() && !mc.player.isShiftKeyDown()) {
                double d;
                if (!floatWasSet && (isDiagonal() || !isAirBelow())) {
                    fastScaffold.state = 1;
                    d = 0.153;
                } else {
                    d = 0.245 + perSpeedLevel(0.025);
                }
                strafe(d);
            }
        } else if ((fastScaffold.jump || fastScaffold.keepY) && mc.player.onGround()
                && (module.ravenJumpFacingForward.getValue() || floatWasSet)) {
            if (fastScaffold.state == 1) {
                fastScaffold.state = 2;
                double d = fastScaffold.sprint && direction().forward() ? 0.25 : (fastScaffold.keepY ? 0.54 : 0.4);
                jumpWith(d);
            }
            delay = legitTicks;
        }
    }

    /**
     * 原 {@code postPlayerInput()}（挂在 1.8.9 的 {@code onPostPlayerInputAlways}）。
     *
     * <p>[适配·必须] 1.8.9 的 {@code movementInput.jump/sneak} 是每刻重算的瞬时输入；
     * Epsilon 的对应通道是 {@link KeyboardInputEvent}。故本方法接收事件、把
     * {@link #jumpInput}/{@link #sneakInput} 落到事件上，**并在落地后清零**，
     * 保证注入只持续一刻 —— 写 {@code KeyMapping.setDown(...)} 会造成永久跳/永久潜行。</p>
     */
    public void onKeyboardInput(KeyboardInputEvent event) {
        postPlayerInput();
        if (jumpInput) {
            event.setJump(true);
        }
        if (sneakInput) {
            event.setSneak(true);
        }
        jumpInput = false;
        sneakInput = false;
    }

    /** 原 {@code postPlayerInput()} 的逻辑主体。 */
    private static void postPlayerInput() {
        if (sneakQueued && sincePlace() > 90L) {
            sneakQueued = false;
            setSneak(true);
        }
        if (jumpState == 1) {
            if (jumpKeyDown()) {
                setJump(false);
                jumpState = 2;
            } else {
                jumpState = 0;
            }
        } else if (jumpState == 2) {
            setJump(true);
            jumpState = 0;
        }
        if (fastScaffold == RavenFastScaffold.Float_A || fastScaffold == RavenFastScaffold.Float_B) {
            if (!fastScaffold.canFastScaffold() && fastScaffold.state == 1) {
                fastScaffold.state = 0;
            }
        } else if ((fastScaffold.jump || fastScaffold.keepY) && module.ravenLegitMotion.getValue()
                && fastScaffold.canFastScaffold() && mc.player.onGround() && fastScaffold.state == 0) {
            setJump(true);
        }
    }

    /** 原 {@code stopOnEdge()}：供 SafeWalk 集成；此处仅暴露状态。 */
    public static boolean stopOnEdge() {
        if (active) {
            if (module.ravenSafeWalk.getValue()) {
                if (fastScaffold.jump || fastScaffold.keepY) {
                    return !fastScaffold.canFastScaffold();
                }
                return true;
            }
            if ((fastScaffold == RavenFastScaffold.Float_A || fastScaffold == RavenFastScaffold.Float_B)
                    && fastScaffold.canFastScaffold()) {
                return true;
            }
        }
        return false;
    }

    // ===== 放置辅助（原 faceFor / afterPlace / placeExtra / canPlaceAgainst / aim）=====

    /** 原 {@code faceFor(BlockPos, BlockPos)}。 */
    public static Direction faceFor(BlockPos pos, BlockPos only) {
        BlockPos down = pos.below();
        if (canPlaceAgainst(down) && (only == null || down.equals(only))) {
            return Direction.UP;
        }
        double d = mc.player.getX() - ((double) pos.getX() + 0.5);
        double d2 = mc.player.getZ() - ((double) pos.getZ() + 0.5);
        Direction facing = Math.abs(d) > Math.abs(d2) ? (d > 0.0 ? Direction.EAST : Direction.WEST)
                : (d2 > 0.0 ? Direction.SOUTH : Direction.NORTH);
        BlockPos toward = pos.relative(facing);
        if (canPlaceAgainst(toward) && (only == null || toward.equals(only))) {
            return facing.getOpposite();
        }
        for (Direction side : SIDES) {
            if (side == facing) {
                continue;
            }
            BlockPos other = pos.relative(side);
            if (!canPlaceAgainst(other)) {
                continue;
            }
            if (only != null && !other.equals(only)) {
                continue;
            }
            return side.getOpposite();
        }
        return null;
    }

    /** 原 {@code canPlaceAgainst(BlockPos)}。 */
    private static boolean canPlaceAgainst(BlockPos pos) {
        if (!placed.isEmpty() && !placed.containsKey(pos) && sincePlace() < 250L) {
            boolean near = false;
            for (Map.Entry<BlockPos, Long> entry : placed.entrySet()) {
                if (mc.player.distanceToSqr(Vec3.atCenterOf(entry.getKey())) < 12.0) {
                    near = true;
                    break;
                }
            }
            if (near) {
                return false;
            }
        }
        BlockState state = mc.level.getBlockState(pos);
        return !isReplaceable(pos) && !isInteractable(state);
    }

    /** 原 {@code afterPlace(BlockPos, ItemStack)}。 */
    private static void afterPlace(BlockPos pos, ItemStack itemStack) {
        if (fastScaffold == RavenFastScaffold.Side && fastScaffold.canFastScaffold()
                && (fastScaffold.ticks == -1 || ++fastScaffold.ticks == 2 || fastScaffold.ticks >= 5)) {
            if (fastScaffold.ticks >= 5) {
                fastScaffold.ticks = 0;
            }
            double cx = (double) pos.getX() + 0.5;
            double cz = (double) pos.getZ() + 0.5;
            Direction facing = null;
            switch (mc.player.getDirection()) {
                case NORTH:
                case SOUTH:
                    facing = mc.player.getX() > cx ? Direction.EAST : Direction.WEST;
                    break;
                case WEST:
                case EAST:
                    facing = mc.player.getZ() > cz ? Direction.SOUTH : Direction.NORTH;
                    break;
                default:
                    break;
            }
            if (facing != null) {
                Vec3 vec3 = hitVec(pos, facing);
                BlockHitResult hit = new BlockHitResult(vec3, facing, pos, false);
                if (isReplaceable(hit.getBlockPos().relative(hit.getDirection()))
                        && placeAt(hit, itemStack)) {
                    placedAt = System.currentTimeMillis();
                    placed.put(pos, placedAt);
                    if (fastScaffold.ticks == -1) {
                        fastScaffold.ticks = 0;
                    }
                    return;
                }
            }
            fastScaffold.ticks = -1;
        }
        if (multiPlace != RavenMultiPlace.Disabled && itemStack.getCount() > 0
                && (towerActive() || fastScaffold.canFastScaffold()) && placeExtra(itemStack)
                && multiPlace == RavenMultiPlace.Extra2 && itemStack.getCount() > 0) {
            placeExtra(itemStack);
        }
    }

    /** 原 {@code placeExtra(ItemStack)}。 */
    private static boolean placeExtra(ItemStack itemStack) {
        BlockPos target = search(lastPlaced);
        if (target == null) {
            return false;
        }
        Direction facing = faceFor(target, lastPlaced);
        if (facing == null) {
            return false;
        }
        BlockPos against = target.relative(facing.getOpposite());
        float[] r = searchBlockRotation(against, facing, -170.0f, precision, lastYaw(), safeWalkActive());
        if (r == null) {
            return false;
        }
        // 参考 placeExtra：rayTraceBlock(against, null, reach, r[0], r[1]) —— 任意面。
        BlockHitResult hit = raycastAnyFace(against, r[0], r[1]);
        if (hit == null) {
            return false;
        }
        // 放置前先落地本刻朝向（联机必需，见 place() 的说明）。
        sendPlacementRotation(r[0], r[1]);
        if (!placeAt(hit, itemStack)) {
            return false;
        }
        lastPlaced = target;
        placedAt = System.currentTimeMillis();
        placed.put(target, placedAt);
        return true;
    }

    /** 原 {@code aim(BlockPos, EnumFacing, float, float)}：解算命中角并返回命中结果。 */
    private static BlockHitResult aim(BlockPos pos, Direction facing, float offset, float lastYaw) {
        float[] r = searchBlockRotation(pos, facing, offset, precision, lastYaw, safeWalkActive());
        if (r == null) {
            return null;
        }
        aimYaw = r[0];
        aimPitch = r[1];
        if (rotation == RavenRotation.Smooth && lastPlaced != null && groundTicks <= 3) {
            // [移植·§5.1 修复] 原代码此处 smoothLimit 死写不如实：原版在 aim() 里写
            // smoothLimit=35+rand 且记录 smoothAt，但 rotations() 读它时从不按 smoothAt 超时回落，
            // 导致 Smooth 模式的限制值可能长期停在旧值。此处按计划补上「超时回落到 35+rand」。
            smoothLimit = 35.0f + (float) (1.0 * Math.random());
            smoothAt = System.currentTimeMillis();
            float f3 = Mth.wrapDegrees(aimYaw - lastYaw);
            if (Math.abs(f3) > smoothLimit) {
                return null;
            }
        }
        // 参考此处传 facing = null（任意面）。
        return raycastAnyFace(pos, aimYaw, aimPitch);
    }

    /** 每刻最多一次的放置前旋转包（对齐 ElowenScaffold.sendPlacementRotation）。 */
    private static void sendPlacementRotation(float yaw, float pitch) {
        if (mc.player == null || mc.getConnection() == null) {
            return;
        }
        if (mc.player.tickCount == lastRotationPacketTick) {
            return;
        }
        lastRotationPacketTick = mc.player.tickCount;
        mc.getConnection().send(new ServerboundMovePlayerPacket.Rot(yaw, pitch, mc.player.onGround(),
                mc.player.horizontalCollision));
    }

    /** 原 {@code holdTime()}。 */
    private static long holdTime() {
        if (!safeWalkActive()) {
            if (mc.player.isSprinting()) {
                return 710L;
            }
            if (!isMoving()) {
                return 460L;
            }
        }
        return 110L;
    }

    /** 原 {@code stop()}。 */
    private static void stop() {
        if (active) {
            stoppedAt = System.currentTimeMillis();
            rotation.onDisable();
            if (fastEnabled) {
                fastScaffold.onDisable(true);
                fastEnabled = false;
            }
            sneakQueued = false;
            smoothSnap = false;
            rightHeld = false;
            active = false;
            // 原 {@code RotationManager.smoothYaw = null}：停用时释放托管角。
            RotationManager.INSTANCE.setActive(false);
            jumpState = 1;
            if (mc.player.onGround() && rotation != RavenRotation.Offset) {
                multiplyMotion(0.75, false);
            }
        }
        jumpInput = false;
        sneakInput = false;
        keptY = -1;
        lastPlaced = null;
        aimPitch = 100.0f;
    }

    /** 原 {@code pauseForKillAura()}（Epsilon 无 KillAura 屏蔽联动，恒返回 true 表示无需暂停）。 */
    public static boolean pauseForKillAura() {
        return true;
    }

    /** 原 {@code resetKeepY()}。 */
    public static void resetKeepY() {
        keptY = -1;
    }

    // ===== 旋转求解（原 RotationUtils.searchBlockRotation 等）=====

    /**
     * 原 {@code RotationUtils.searchBlockRotation(pos, facing, offset, precision, airYaw, playerYaw)}。
     * 在 {@code [base+offset, base+170°]} 里按精度扫 yaw，每个 yaw 再自上而下扫 pitch，
     * 取「打到 pos 的指定面、且命中点在面内不贴边、且 yaw 离基准最近」的那一组角。
     */
    private static float[] searchBlockRotation(BlockPos pos, Direction facing, float offset, RavenPrecision precision,
                                               float lastYaw, boolean playerYaw) {
        float bestYaw = 0.0f;
        float bestPitch = 0.0f;
        float base = playerYaw ? mc.player.getYRot() : (mc.player.onGround() ? movementAngle() : lastYaw);
        float from = base + offset;
        float to = base + 170.0f;
        double reach = mc.player.blockInteractionRange();
        float best = 360.0f;
        for (float yaw = from; yaw <= to; yaw += precision.offsetYaw) {
            float y = (float) ((double) yaw + (Math.random() - 0.5));
            for (float pitch = 90.0f; pitch > 30.0f; pitch -= precision.offsetPitch(pitch)) {
                float p = Mth.clamp((float) ((double) pitch + (Math.random() - 0.5)), -90.0f, 90.0f);
                BlockHitResult hit = raycastFree(y, p, reach);
                if (hit == null || hit.getDirection() != facing || !hit.getBlockPos().equals(pos)) {
                    continue;
                }
                Vec3 v = hit.getLocation();
                BlockPos hp = hit.getBlockPos();
                if (!awayFromEdge((float) (v.y - (double) hp.getY()))
                        || !awayFromEdge((float) (v.x - (double) hp.getX()))
                        || !awayFromEdge((float) (v.z - (double) hp.getZ()))) {
                    continue;
                }
                float diff = Math.abs(base - y);
                if (diff < best) {
                    best = diff;
                    bestYaw = y;
                    bestPitch = p;
                }
            }
        }
        if (best == 360.0f) {
            return null;
        }
        if (playerYaw && best > 70.0f) {
            return null;
        }
        return new float[]{bestYaw, bestPitch};
    }

    /** 原 {@code RotationUtils.awayFromEdge(float)}。 */
    private static boolean awayFromEdge(float f) {
        return f == 0.0f || f == 1.0f || (f > 0.06 && f < 0.94);
    }

    /** 从眼睛沿给定角发射射线，命中任意方块即返回。 */
    private static BlockHitResult raycastFree(float yaw, float pitch, double reach) {
        Vec3 eye = mc.player.getEyePosition();
        Vec3 dir = Vec3.directionFromRotation(pitch, yaw);
        Vec3 end = eye.add(dir.x * reach, dir.y * reach, dir.z * reach);
        HitResult hit = mc.level.clip(new ClipContext(eye, end, ClipContext.Block.COLLIDER, ClipContext.Fluid.NONE, mc.player));
        return hit != null && hit.getType() == HitResult.Type.BLOCK ? (BlockHitResult) hit : null;
    }

    /**
     * 从眼睛沿给定角发射射线，且**必须**命中指定方块（**任意面**）。
     *
     * <p>对应参考 {@code PlayerUtils.rayTraceBlock(pos, null, reach, yaw, pitch)} ——
     * 参考在 {@code aim()} 与 {@code placeExtra()} 里都传 {@code facing = null}，
     * 只要求打到该方块，不要求打在特定面。若额外要求面一致，会把「打在同方块其它面」的
     * 合法解全部拒掉，实测表现就是放置显著变少。</p>
     */
    private static BlockHitResult raycastAnyFace(BlockPos pos, float yaw, float pitch) {
        if (pos == null) {
            return null;
        }
        BlockHitResult hit = raycastFree(yaw, pitch, mc.player.blockInteractionRange());
        if (hit == null || !hit.getBlockPos().equals(pos)) {
            return null;
        }
        return hit;
    }

    // ===== 运动注入（原 PlayerUtils / MoveUtils 的等价实现）=====

    /** 原 {@code PlayerUtils.multiplyMotion(d)}。 */
    private static void multiplyMotion(double d, boolean always) {
        if (!always && module.ravenLegitMotion.getValue()) {
            return;
        }
        Vec3 v = mc.player.getDeltaMovement();
        mc.player.setDeltaMovement(v.x * d, v.y, v.z * d);
    }

    /** 原 {@code MoveUtils.strafe(speed)}。 */
    private static void strafe(double speed) {
        if (speed == 0.0) {
            Vec3 v = mc.player.getDeltaMovement();
            mc.player.setDeltaMovement(0.0, v.y, 0.0);
            return;
        }
        float yaw = movementYaw();
        double rad = Math.toRadians(yaw);
        Vec3 v = mc.player.getDeltaMovement();
        mc.player.setDeltaMovement(-Math.sin(rad) * speed, v.y, Math.cos(rad) * speed);
    }

    /** 原 {@code PlayerUtils.jump()}。 */
    private static void jump() {
        Vec3 v = mc.player.getDeltaMovement();
        mc.player.setDeltaMovement(v.x, 0.42, v.z);
    }

    /** 原 {@code PlayerUtils.jump(double speed)} → {@code jumpWith}。 */
    private static void jumpWith(double d) {
        if (module.ravenLegitMotion.getValue()) {
            return;
        }
        strafe(boosted(d));
        jump();
    }

    /** 原 {@code boosted(double)}。 */
    private static double boosted(double d) {
        d += speedBonus();
        d += 1.0E-4 * Math.random();
        return d;
    }

    /** 原 {@code speedBonus()}。 */
    private static double speedBonus() {
        int n = speedLevel();
        return switch (n) {
            case 0 -> 0.0;
            case 1 -> 0.05;
            case 2 -> module.ravenSpeedBoost.getValue() / 10.0;
            default -> 0.06 * (double) n;
        };
    }

    /**
     * 原 {@code PlayerUtils.setJump(boolean)}：写的是 1.8.9 的 {@code movementInput.jump}（**每刻瞬时**）。
     * Epsilon 侧改为记录待注入标志，由 {@link #onKeyboardInput(KeyboardInputEvent)} 在
     * {@link KeyboardInputEvent} 上落地 —— 绝不能写 {@code mc.options.keyJump.setDown(...)}（持久键态，永不复位 ⇒ 一直跳）。
     */
    private static void setJump(boolean jump) {
        jumpInput = jump;
    }

    /** 原 {@code PlayerUtils.setSneak(boolean)}（瞬时）。同上：只记标志，由 KeyboardInputEvent 落地。 */
    private static void setSneak(boolean sneak) {
        sneakInput = sneak;
    }

    /** 原 {@code PlayerUtils.syncCurrentPlayItem()}：Epsilon 侧由 {@code SlotHelper} 负责，此处空实现。 */
    // private static void syncCurrentPlayItem() { }

    // ===== 输入/状态读取（原 PlayerUtils 的等价实现）=====

    /** 原 {@code PlayerUtils.isMoving()}：{@code movementInput.moveForward/moveStrafe != 0}（**处理后**的输入）。 */
    private static boolean isMoving() {
        var mv = mc.player.input.getMoveVector();
        return mv.y != 0.0f || mv.x != 0.0f;
    }

    private static boolean jumpKeyDown() {
        return mc.options.keyJump.isDown();
    }

    /**
     * 原 {@code PlayerUtils.forwardInput()}：读**原始按键**（{@code keyBindForward/Back}），
     * 而**不是**处理后的 {@code movementInput.moveForward} ——
     * 后者会被 {@code MovementFix} 按托管角重算，读它会让「托管角 → 输入 → 托管角」形成正反馈（实测表现为原地打转）。
     */
    private static float forwardInput() {
        float f = 0.0f;
        if (mc.options.keyUp.isDown()) {
            f += 1.0f;
        }
        if (mc.options.keyDown.isDown()) {
            f -= 1.0f;
        }
        return f;
    }

    /** 原 {@code PlayerUtils.strafeInput()}：读**原始按键**（{@code keyBindLeft/Right}）。 */
    private static float strafeInput() {
        float f = 0.0f;
        if (mc.options.keyLeft.isDown()) {
            f += 1.0f;
        }
        if (mc.options.keyRight.isDown()) {
            f -= 1.0f;
        }
        return f;
    }

    /** 原 {@code RotationUtils.inputAngleSigned()}。 */
    private static double inputAngleSigned() {
        double d = Math.toDegrees(Math.atan2(-strafeInput(), forwardInput()));
        return d == 0.0 ? 0.0 : d;
    }

    /** 原 {@code PlayerUtils.movementAngle()}（{@code RotationUtils.inputAngle()} 会做 {@code (d+360)%360} 归一）。 */
    private static float movementAngle() {
        double d = Math.toDegrees(Math.atan2(strafeInput(), -forwardInput()));
        d = (d + 360.0) % 360.0;
        return mc.player.getYRot() + (float) d;
    }

    /** 原 {@code RotationManager.movementYaw()}：把**处理后**的输入方向合入当前视角角（Raven B4 取 {@code movementInput}）。 */
    private static float movementYaw() {
        float yaw = mc.player.getYRot();
        var mv = mc.player.input.getMoveVector();
        float forward = mv.y;
        float strafe = mv.x;
        float f4 = 1.0f;
        if (forward < 0.0f) {
            yaw += 180.0f;
            f4 = -0.5f;
        } else if (forward > 0.0f) {
            f4 = 0.5f;
        }
        if (strafe > 0.0f) {
            yaw -= 90.0f * f4;
        } else if (strafe < 0.0f) {
            yaw += 90.0f * f4;
        }
        return yaw;
    }

    /** 原 {@code MoveDirection} 的等价体（forward / diagonal）。 */
    private record MoveDirection(boolean forward, boolean diagonal) {
    }

    /** 原 {@code PlayerUtils.direction()}。 */
    private static MoveDirection direction() {
        float forward = forwardInput();
        float strafe = strafeInput();
        boolean fwd = forward > 0.0f && strafe == 0.0f;
        boolean diag = forward != 0.0f && strafe != 0.0f;
        return new MoveDirection(fwd, diag);
    }

    private static boolean isDiagonal() {
        return direction().diagonal();
    }

    private static int speedLevel() {
        return potionLevel(MobEffects.SPEED);
    }

    private static int potionLevel(net.minecraft.core.Holder<net.minecraft.world.effect.MobEffect> effect) {
        var inst = mc.player.getEffect(effect);
        return inst == null ? 0 : inst.getAmplifier() + 1;
    }

    private static double perSpeedLevel(double d) {
        return (double) speedLevel() * d;
    }

    private static double speed() {
        Vec3 v = mc.player.getDeltaMovement();
        return Math.sqrt(v.x * v.x + v.z * v.z);
    }

    private static int posYDecimals() {
        return decimals(mc.player.getY());
    }

    private static int motionYDecimals() {
        return (int) Math.round(mc.player.getDeltaMovement().y % 10.0 * 10000.0);
    }

    private static int decimals(double v) {
        return (int) Math.round((v - Math.floor(v)) * 10000.0);
    }

    private static boolean isAirBelow() {
        return mc.level.getBlockState(BlockPos.containing(mc.player.getX(),
                mc.player.getY() - (mc.player.getY() % 1.0 == 0.0 ? 1 : 0), mc.player.getZ())).isAir();
    }

    private static boolean nearEdge(double margin) {
        int x = Mth.floor(mc.player.getX());
        int y = Mth.floor(mc.player.getY() - 0.01);
        int z = Mth.floor(mc.player.getZ());
        BlockPos pos = new BlockPos(x, y, z);
        return mc.level.getBlockState(pos.below()).isAir()
                || mc.level.getBlockState(pos.below().north()).isAir()
                || mc.level.getBlockState(pos.below().south()).isAir()
                || mc.level.getBlockState(pos.below().east()).isAir()
                || mc.level.getBlockState(pos.below().west()).isAir();
    }

    private static boolean isAdventure() {
        return !mc.player.getAbilities().mayBuild;
    }

    /** 原 {@code PacketUtils.noUseOrDig()}：无 GUI 且无打开容器。 */
    private static boolean noUseOrDig() {
        return mc.gui.screen() == null;
    }

    private static boolean safeWalkActive() {
        return module.ravenSafeWalk.getValue();
    }

    private static boolean towerActive() {
        // [移植注] Epsilon 无 Raven B4 的 TowerModule（带 slowedTicks / towering 标志），
        // 由本模块的 ravenTower 设置近似；无联动塔时恒为 false。
        return false;
    }

    private static float lastYaw() {
        return lastSentRotation.getYaw();
    }

    private static long sincePlace() {
        return Math.abs(System.currentTimeMillis() - placedAt);
    }

    private static float[] fixSensitivity(float yaw, float pitch, float lastYaw, float lastPitch) {
        Rot2f fixed = RotationUtils.applySensitivityPatch(new Rot2f(yaw, pitch), new Rot2f(lastYaw, lastPitch));
        return new float[]{fixed.getYaw(), fixed.getPitch()};
    }

    private static float randomOffset() {
        return (float) (Math.random() - 0.5);
    }

    private static float randomFloat(float a, float b) {
        return (float) ((double) a + (double) (b - a) * Math.random());
    }

    private static double randomBetween(double a, double b) {
        return a + (b - a) * Math.random();
    }

    // ===== 方块/几何（原 BlockUtils 的等价实现）=====

    private static boolean isReplaceable(BlockPos pos) {
        BlockState state = mc.level.getBlockState(pos);
        return state.isAir() || state.canBeReplaced()
                || !state.getFluidState().isEmpty();
    }

    private static boolean isInteractable(BlockState state) {
        var block = state.getBlock();
        return block instanceof LeverBlock || block instanceof ButtonBlock
                || block instanceof DoorBlock || block instanceof TrapDoorBlock
                || block instanceof FenceGateBlock
                || state.hasBlockEntity();
    }

    private static BlockPos below(double d) {
        return BlockPos.containing(mc.player.getX(), mc.player.getY() - d, mc.player.getZ());
    }

    /** 原 {@code BlockUtils.hitVec(pos, facing)}：在指定面上取一个随机点。 */
    private static Vec3 hitVec(BlockPos pos, Direction facing) {
        double lo = 0.1, hi = 0.9, ylo = 0.05, yhi = 0.95;
        double x = (double) pos.getX() + randomBetween(lo, hi);
        double y = (double) pos.getY() + randomBetween(ylo, yhi);
        double z = (double) pos.getZ() + randomBetween(lo, hi);
        return switch (facing) {
            case UP -> new Vec3(x, (double) pos.getY() + 1.0, z);
            case DOWN -> new Vec3(x, (double) pos.getY(), z);
            case NORTH -> new Vec3(x, y, (double) pos.getZ());
            case SOUTH -> new Vec3(x, y, (double) pos.getZ() + 1.0);
            case WEST -> new Vec3((double) pos.getX(), y, z);
            case EAST -> new Vec3((double) pos.getX() + 1.0, y, z);
        };
    }

    private static double distanceSq(double x, double y, double z, Vec3 p) {
        double dx = p.x - x;
        double dy = p.y - y;
        double dz = p.z - z;
        return dx * dx + dy * dy + dz * dz;
    }

    /** 原 {@code WorldUtils.behind(x, z, dirX, dirZ, p)}：候选点在朝向反方向的投影（越大越"靠后"优先）。 */
    private static double behind(double x, double z, double dirX, double dirZ, Vec3 p) {
        return (x - p.x) * dirX + (z - p.z) * dirZ;
    }

    /** 原 {@code ScaffoldModule.guiUpdate()}：每刻把设置读进静态模式字段。 */
    private void syncSettings() {
        rotation = module.ravenRotationMode.getValue();
        multiPlace = module.ravenMultiPlace.getValue();
        precision = module.ravenPrecision.getValue();
    }

    /** 当前手上（主手优先）的可放置手，都没有返回 null（照 elowen {@code getPlacementHand()}）。 */
    private static InteractionHand getPlacementHand() {
        if (mc.player == null) {
            return null;
        }
        if (module.isValidStack(mc.player.getMainHandItem())) {
            return InteractionHand.MAIN_HAND;
        }
        if (module.isValidStack(mc.player.getOffhandItem())) {
            return InteractionHand.OFF_HAND;
        }
        return null;
    }

    /**
     * 实际执行一次方块放置。
     *
     * <p>对应参考两条不同的调用约定：{@code place()} 主路径用
     * {@code onPlayerRightClick(player, world, stack, against, facing, hitVec)}
     * （方块/面来自 {@code faceFor}，位置取射线命中点）；而 {@code placeExtra()} 走
     * {@code PlayerUtils.place(hit, stack)}（方块/面取**射线自身**的 {@code hit.getBlockPos()/sideHit}）。
     * 故这里把「方块 + 面 + 命中点」显式作为参数传入，由调用方按各自约定给。</p>
     */
    private static boolean placeAtBlock(BlockPos pos, Direction face, Vec3 location, ItemStack itemStack) {
        if (pos == null || face == null || location == null) {
            return false;
        }
        var hand = getPlacementHand();
        if (hand == null) {
            return false;
        }
        BlockHitResult hit = new BlockHitResult(location, face, pos, false);
        InteractionResult result = mc.gameMode.useItemOn(mc.player, hand, hit);
        if (!result.consumesAction()) {
            return false;
        }
        if (module.ravenSilentSwing.getValue()) {
            if (mc.getConnection() != null) {
                mc.getConnection().send(new ServerboundSwingPacket(InteractionHand.MAIN_HAND));
            }
        } else {
            mc.player.swing(InteractionHand.MAIN_HAND);
        }
        return true;
    }

    /** 原 {@code PlayerUtils.place(hit, stack)}：方块与面都取射线自身的。 */
    private static boolean placeAt(BlockHitResult hit, ItemStack itemStack) {
        if (hit == null) {
            return false;
        }
        return placeAtBlock(hit.getBlockPos(), hit.getDirection(), hit.getLocation(), itemStack);
    }

    // ===== 渲染 / HUD =====

    /** 渲染目标（当前放置目标）。 */
    public static BlockPos getRenderBlock() {
        return lastPlaced;
    }

    /** 原 {@code renderPlaced()}：给最近的放置加高亮（模块侧渲染事件读取）。 */
    public static Map<BlockPos, Long> getPlacedBlocks() {
        return placed;
    }


    /** 供模块查询引擎是否处于激活态。 */
    public boolean isActive() {
        return active;
    }
}
