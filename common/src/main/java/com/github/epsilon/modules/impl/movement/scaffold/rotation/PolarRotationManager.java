package com.github.epsilon.modules.impl.movement.scaffold.rotation;

import com.github.epsilon.events.impl.PacketEvent;
import net.minecraft.client.gui.screens.inventory.ContainerScreen;
import net.minecraft.client.gui.screens.inventory.InventoryScreen;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.game.ClientboundPlayerPositionPacket;
import net.minecraft.network.protocol.game.ClientboundPlayerRotationPacket;
import net.minecraft.network.protocol.game.ServerboundMovePlayerPacket;
import net.minecraft.network.protocol.game.ServerboundUseItemPacket;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.Relative;

import static com.github.epsilon.Constants.mc;

/**
 * 逐行移植自 LiquidBounce nextgen {@code utils/aiming/RotationManager.kt}（commit 2d94475）——<b>只搬状态机</b>。
 * <p>
 * 移植范围（LB 原文 {@code object RotationManager : EventListener}）：
 * {@code rotationTargetHandler} / {@code rotationTarget} / {@code activeRotationTarget} / {@code previousRotationTarget} /
 * {@code currentRotation} / {@code playerRotation} / {@code previousRotation} / {@code actualServerRotation} /
 * {@code theoreticalServerRotation} / {@code serverRotation} / {@code movementYaw} / {@code reset()} /
 * {@code serverboundRotation()} / {@code clientboundRotation()} / {@code setRotationTarget(...)} 全部重载 /
 * {@code isRotatingAllowed()} / {@code allowedToUpdate()} / {@code update()} / {@code packetHandler} 的 serverRotation 追踪，
 * 以及同文件的顶层函数 {@code resolveMovementYaw}。
 * <p>
 * 明确<b>不搬</b>（映射表登记理由）：{@code applyChangeLookRotation}、{@code mouseMovement}（MouseRotationEvent /
 * CHANGE_LOOK 鼠标路径）、{@code velocityHandler}（PlayerVelocityStrafe）、{@code rotationMatchesPreviousRotation}
 * （依赖 {@code LocalPlayer.yRotLast/xRotLast}，26.2 为私有字段）、{@code lifecycleListener}（WorldChangeEvent 订阅）。
 * <p>
 * 本类<b>不订阅 Epsilon 事件</b>：LB 的事件处理方法体被抽成公开方法（{@link #onTick()}、{@link #onSendPacket}、
 * {@link #onReceivePacket}、{@link #onWorldChange()}），由阶段 7 的协调层调用。
 */
public final class PolarRotationManager implements RequestHandler.Provider {

    public static final PolarRotationManager INSTANCE = new PolarRotationManager();

    /**
     * LB 原文：{@code net.ccbluex.liquidbounce.utils.kotlin.Priority}（逐值照搬；本包的优先级队列只用其数值）。
     */
    public enum Priority {

        NOT_IMPORTANT(-20),
        NORMAL(0),

        IMPORTANT_FOR_USAGE_1(20),
        /**
         * KillAura, etc.
         */
        IMPORTANT_FOR_USAGE_2(30),
        IMPORTANT_FOR_USAGE_3(35),
        /**
         * Scaffold, etc.
         */
        IMPORTANT_FOR_PLAYER_LIFE(40),

        IMPORTANT_FOR_USER_SAFETY(60);

        public final int priority;

        Priority(int priority) {
            this.priority = priority;
        }

    }

    /**
     * Our final target rotation. This rotation is only used to define our current rotation.
     */
    private final RequestHandler<RotationTarget> rotationTargetHandler = new RequestHandler<>();

    private RotationTarget previousRotationTarget;

    /**
     * The rotation we want to aim at. This DOES NOT mean that the server already received this rotation.
     */
    private Rotation currentRotation = null;

    // Used for rotation interpolation
    private Rotation playerRotation = null;
    private Rotation previousRotation = null;

    /**
     * The rotation that was already sent to the server and is currently active.
     * The value is not being written by the packets, but we gather the Rotation from the last yaw and pitch variables
     * from our player instance handled by the sendMovementPackets() function.
     */
    private Rotation actualServerRotation = Rotation.ZERO;

    private Rotation theoreticalServerRotation = Rotation.ZERO;

    private PolarRotationManager() {
    }

    /**
     * LB 原文：{@code private val rotationTarget get() = rotationTargetHandler.getActiveRequestValue()}
     */
    public RotationTarget getRotationTarget() {
        return rotationTargetHandler.getActiveRequestValue();
    }

    /**
     * LB 原文：{@code val activeRotationTarget: RotationTarget? get() = rotationTarget ?: previousRotationTarget}
     */
    public RotationTarget getActiveRotationTarget() {
        RotationTarget rotationTarget = getRotationTarget();
        return rotationTarget != null ? rotationTarget : previousRotationTarget;
    }

    /**
     * LB 原文：{@code internal var previousRotationTarget: RotationTarget? = null private set}
     */
    public RotationTarget getPreviousRotationTarget() {
        return previousRotationTarget;
    }

    public Rotation getCurrentRotation() {
        return currentRotation;
    }

    /**
     * LB 原文：{@code var currentRotation: Rotation? = null private set(value) { ... }}
     */
    private void setCurrentRotation(Rotation value) {
        previousRotation = value == null
                ? null
                : (currentRotation != null
                        ? currentRotation
                        : (mc.player != null ? new Rotation(mc.player.getYRot(), mc.player.getXRot(), true) : Rotation.ZERO));

        this.currentRotation = value;
    }

    public Rotation getPlayerRotation() {
        return playerRotation;
    }

    public Rotation getPreviousRotation() {
        return previousRotation;
    }

    /**
     * LB 原文：{@code private val fakeLagging get() = BlinkManager.isLagging || ModuleBacktrack.isLagging()}
     * <p>
     * [适配] Epsilon 无 BlinkManager / ModuleBacktrack 的对应机制 → 恒 false（保留字段与方法结构）。
     */
    private boolean fakeLagging() {
        return false;
    }

    /**
     * LB 原文：{@code private val freezing get() = ModuleFreeze.running}
     * <p>
     * [适配] Epsilon 无 ModuleFreeze 的对应机制 → 恒 false（保留字段与方法结构）。
     */
    private boolean freezing() {
        return false;
    }

    /**
     * LB 原文：{@code val serverRotation: Rotation get() = if (fakeLagging || freezing) theoreticalServerRotation else actualServerRotation}
     */
    public Rotation getServerRotation() {
        return fakeLagging() || freezing() ? theoreticalServerRotation : actualServerRotation;
    }

    /**
     * Yaw used by {@code Entity.moveRelative} after movement correction.
     */
    public float getMovementYaw() {
        return resolveMovementYaw(
                mc.player.getYRot(),
                currentRotation != null ? currentRotation.yaw : Float.NaN,
                getActiveRotationTarget() != null ? getActiveRotationTarget().getMovementCorrection() : null
        );
    }

    public Rotation getActualServerRotation() {
        return actualServerRotation;
    }

    public Rotation getTheoreticalServerRotation() {
        return theoreticalServerRotation;
    }

    /**
     * LB 原文：{@code private fun reset()}
     */
    public void reset() {
        rotationTargetHandler.clear();
        previousRotationTarget = null;
        setCurrentRotation(null);
        playerRotation = null;
        previousRotation = null;
        actualServerRotation = Rotation.ZERO;
        theoreticalServerRotation = Rotation.ZERO;
    }

    /**
     * LB 原文：{@code handler<WorldChangeEvent> { reset() }}（lifecycleListener）
     * <p>
     * [适配] 本类不订阅事件 → 由阶段 7 的协调层在世界切换时调用。
     */
    public void onWorldChange() {
        reset();
    }

    /**
     * Applies the rotation normalization performed by the vanilla server for rotation-bearing
     * movement and item-use packets.
     *
     * @see net.minecraft.server.network.ServerGamePacketListenerImpl#handleMovePlayer
     * @see net.minecraft.server.network.ServerGamePacketListenerImpl#handleUseItem
     */
    private static Rotation serverboundRotation(float yaw, float pitch) {
        return new Rotation(
                Mth.wrapDegrees(yaw),
                Mth.clamp(Mth.wrapDegrees(pitch), -90f, 90f),
                true
        );
    }

    /**
     * Resolves the relative rotation flags used by vanilla player position and rotation updates.
     *
     * @see net.minecraft.world.entity.PositionMoveRotation#calculateAbsolute
     * @see net.minecraft.world.entity.Entity#forceSetRotation
     */
    private Rotation clientboundRotation(float yaw, float pitch, boolean relativeYaw, boolean relativePitch) {
        return new Rotation(
                yaw + (relativeYaw ? actualServerRotation.yaw : 0f),
                Mth.clamp(pitch + (relativePitch ? actualServerRotation.pitch : 0f), -90f, 90f),
                true
        );
    }

    /**
     * LB 原文：
     * <pre>
     * fun setRotationTarget(
     *     rotation: Rotation,
     *     considerInventory: Boolean = true,
     *     valueGroup: RotationsValueGroup,
     *     priority: Priority,
     *     provider: ClientModule,
     *     whenReached: RestrictedSingleUseAction? = null
     * )
     * </pre>
     * [适配] {@code priority: Priority} → {@code int}（{@link Priority} 的数值）；{@code provider} → {@link RequestHandler.Provider}；
     * {@code whenReached} → {@link Runnable}。
     */
    public void setRotationTarget(
            Rotation rotation,
            boolean considerInventory,
            RotationsValueGroup valueGroup,
            int priority,
            RequestHandler.Provider provider,
            Runnable whenReached
    ) {
        setRotationTarget(valueGroup.toRotationTarget(
                rotation, null, considerInventory, whenReached
        ), priority, provider);
    }

    /**
     * 契约 §4.1 重载。LB 的默认参数 {@code considerInventory = true}、{@code whenReached = null}，
     * 优先级取 LB {@code ModuleScaffold} 调用点使用的 {@code Priority.IMPORTANT_FOR_PLAYER_LIFE}。
     */
    public void setRotationTarget(Rotation rotation, RotationsValueGroup valueGroup) {
        setRotationTarget(rotation, true, valueGroup, Priority.IMPORTANT_FOR_PLAYER_LIFE.priority, this, null);
    }

    /**
     * 契约 §4.1 重载。优先级取 LB {@code ModuleScaffold} 调用点使用的 {@code Priority.IMPORTANT_FOR_PLAYER_LIFE}；
     * provider 为 {@code this}（{@link #isRunning()} 等价 LB 的 {@code EventListener.running = inGame}）。
     * <p>
     * 阶段 7 的协调层应优先使用带 provider 的重载，以便模块关闭时请求被自动回收。
     */
    public void setRotationTarget(RotationTarget plan) {
        setRotationTarget(plan, Priority.IMPORTANT_FOR_PLAYER_LIFE.priority, this);
    }

    /**
     * LB 原文：{@code @AddonApi fun setRotationTarget(plan: RotationTarget, priority: Int, provider: ClientModule)}
     */
    public void setRotationTarget(RotationTarget plan, int priority, RequestHandler.Provider provider) {
        if (!allowedToUpdate()) {
            return;
        }

        rotationTargetHandler.request(
                new RequestHandler.Request<>(
                        plan.getMovementCorrection() == MovementCorrection.CHANGE_LOOK ? 1 : plan.getTicksUntilReset(),
                        priority,
                        provider,
                        plan
                )
        );
    }

    /**
     * Checks if the rotation is allowed to be updated.
     * <p>
     * LB 原名 {@code isRotatingAllowed}；契约 §4.1 命名为 {@code isRotationAllowed}，两个名字都保留。
     */
    public boolean isRotatingAllowed(RotationTarget rotationTarget) {
        if (!allowedToUpdate()) {
            return false;
        }

        if (rotationTarget.getConsiderInventory()) {
            if (isInventoryOpen() || mc.gui.screen() instanceof ContainerScreen) {
                return false;
            }
        }

        return true;
    }

    /**
     * 契约 §4.1 的命名（等价 {@link #isRotatingAllowed(RotationTarget)}）。
     */
    public boolean isRotationAllowed(RotationTarget rotationTarget) {
        return isRotatingAllowed(rotationTarget);
    }

    /**
     * 契约 §4.1 的无参重载：等价 LB {@code allowedToUpdate()}（与具体 rotationTarget 无关的那部分检查）。
     */
    public boolean isRotationAllowed() {
        return allowedToUpdate();
    }

    /**
     * LB 原文：{@code private fun allowedToUpdate() = !CombatManager.shouldPauseRotation}
     * <p>
     * [适配] Epsilon 无 {@code CombatManager.shouldPauseRotation} 机制 → 恒 true（保留方法结构）。
     */
    private boolean allowedToUpdate() {
        return true;
    }

    /**
     * LB 原文：{@code if (InventoryManager.isInventoryOpen || mc.gui.screen() is ContainerScreen)}
     * <p>
     * [适配] Epsilon 无 InventoryManager：其 {@code isInventoryOpen = isInInventoryScreen || isInventoryOpenServerSide}，
     * 其中服务端侧标记属于 LB 的物品栏管理设施 → 等价取 {@code isInInventoryScreen}（{@code mc.gui.screen() is InventoryScreen}）。
     */
    private boolean isInventoryOpen() {
        return mc.gui.screen() instanceof InventoryScreen;
    }

    /**
     * LB 原文：{@code override val running: Boolean get() = inGame}
     */
    @Override
    public boolean isRunning() {
        // LB 原文 utils/client/ClientUtils.kt: val inGame get() = Minecraft.getInstance()?.let { it.player != null && it.level != null } == true
        return mc.player != null && mc.level != null;
    }

    /**
     * Update current rotation to a new rotation step
     */
    public void update() {
        // LB 原文：val playerRotation = player.rotation.also { this.playerRotation = it }
        Rotation playerRotation = new Rotation(mc.player.getYRot(), mc.player.getXRot(), true);
        this.playerRotation = playerRotation;
        RotationTarget activeRotationTarget = getActiveRotationTarget();
        if (activeRotationTarget == null) {
            return;
        }
        RotationTarget rotationTarget = getRotationTarget();

        // Prevents any rotation changes when inventory is opened
        if (isRotatingAllowed(activeRotationTarget)) {
            Rotation fromRotation = currentRotation != null ? currentRotation : playerRotation;
            Rotation rotation = activeRotationTarget.towards(fromRotation, rotationTarget == null)
                    // After generating the next rotation, we need to normalize it
                    .normalize();

            float diff = rotation.rotationDeltaLengthTo(playerRotation);

            if (rotationTarget == null && (activeRotationTarget.getMovementCorrection() == MovementCorrection.CHANGE_LOOK
                    || activeRotationTarget.getProcessors().isEmpty()
                    || diff <= activeRotationTarget.getResetThreshold())) {
                Rotation currentRotation = this.currentRotation;
                if (currentRotation != null) {
                    // LB 原文：player.yRot = player.withFixedYaw(currentRotation); player.yBob = player.yRot; player.yBobO = player.yRot
                    mc.player.setYRot(RotationUtil.withFixedYaw(mc.player, currentRotation));
                    mc.player.yBob = mc.player.getYRot();
                    mc.player.yBobO = mc.player.getYRot();
                }

                setCurrentRotation(null);
                previousRotationTarget = null;
            } else {
                setCurrentRotation(rotation);
                previousRotationTarget = activeRotationTarget;

                if (rotationTarget != null && rotationTarget.getWhenReached() != null) {
                    rotationTarget.getWhenReached().run();
                }
            }
        }

        // Update reset ticks
        rotationTargetHandler.tick();
    }

    /**
     * LB 原文：
     * <pre>
     * private val gameTickHandler = handler&lt;GameTickEvent&gt;(priority = FIRST_PRIORITY) { event -&gt;
     *     EventManager.callEvent(RotationUpdateEvent)
     *     update()
     * }
     * </pre>
     * 阶段 7 的协调层调用（Epsilon 侧挂 {@code ClientTickEvent.Pre}）。
     * <p>
     * [适配] Epsilon 无 {@code RotationUpdateEvent} 等价事件 → 不派发，只执行 {@code update()}。
     */
    public void onTick() {
        update();
    }

    /**
     * 阶段 7 的协调层调用：LB 原文 {@code handler<PacketEvent>(priority = READ_FINAL_STATE)} 的出站分支。
     * <p>
     * [适配] Epsilon 的 {@code PacketEvent} 拆成 {@code Send}/{@code Receive}（都 Cancellable），
     * LB 的 {@code event.origin == TransferOrigin.INCOMING} → {@code incoming} 参数。
     */
    public void onSendPacket(PacketEvent.Send event) {
        handlePacket(event.getPacket(), false, event.isCancelled());
    }

    /**
     * 阶段 7 的协调层调用：LB 原文 {@code handler<PacketEvent>(priority = READ_FINAL_STATE)} 的入站分支。
     */
    public void onReceivePacket(PacketEvent.Receive event) {
        handlePacket(event.getPacket(), true, event.isCancelled());
    }

    /**
     * LB 原文：
     * <pre>
     * val packetHandler = handler&lt;PacketEvent&gt;(priority = READ_FINAL_STATE) { event -&gt;
     *     val rotation = when (val packet = event.packet) { ... }
     *
     *     // Incoming corrections already changed the server-side player before they were sent. For
     *     // outgoing packets, only packets that pass the event pipeline can affect the server.
     *     if (event.origin == TransferOrigin.INCOMING || !event.isCancelled) {
     *         actualServerRotation = rotation
     *     }
     *     theoreticalServerRotation = rotation
     * }
     * </pre>
     * <p>
     * [适配] LB 用 AW 公开了这些包字段，26.2 为访问器：{@code packet.hasRot} → {@code hasRotation()}、
     * {@code packet.yRot/xRot} → {@code getYRot(0f)/getXRot(0f)}（{@code hasRotation()} 为真时返回原字段，见字节码）、
     * {@code packet.change.yRot} → {@code change().yRot()}、{@code Relative.Y_ROT in packet.relatives} → {@code relatives().contains(...)}。
     */
    private void handlePacket(Packet<?> packet, boolean incoming, boolean cancelled) {
        Rotation rotation;

        if (packet instanceof ServerboundMovePlayerPacket movePlayerPacket) {
            // If we are not changing the look, we don't need to update the rotation
            if (!movePlayerPacket.hasRotation()) {
                return;
            }

            rotation = serverboundRotation(movePlayerPacket.getYRot(0f), movePlayerPacket.getXRot(0f));
        } else if (packet instanceof ClientboundPlayerPositionPacket positionPacket) {
            rotation = clientboundRotation(
                    positionPacket.change().yRot(),
                    positionPacket.change().xRot(),
                    positionPacket.relatives().contains(Relative.Y_ROT),
                    positionPacket.relatives().contains(Relative.X_ROT)
            );
        } else if (packet instanceof ClientboundPlayerRotationPacket rotationPacket) {
            rotation = clientboundRotation(
                    rotationPacket.yRot(),
                    rotationPacket.xRot(),
                    rotationPacket.relativeY(),
                    rotationPacket.relativeX()
            );
        } else if (packet instanceof ServerboundUseItemPacket useItemPacket) {
            rotation = serverboundRotation(useItemPacket.getYRot(), useItemPacket.getXRot());
        } else {
            return;
        }

        // Incoming corrections already changed the server-side player before they were sent. For
        // outgoing packets, only packets that pass the event pipeline can affect the server.
        if (incoming || !cancelled) {
            actualServerRotation = rotation;
        }
        theoreticalServerRotation = rotation;
    }

    /**
     * LB 原文（同文件的顶层 internal 函数）：
     * <pre>
     * internal fun resolveMovementYaw(playerYaw: Float, managedYaw: Float, movementCorrection: MovementCorrection?): Float =
     *     if (managedYaw.isFinite() &amp;&amp; movementCorrection != null &amp;&amp; movementCorrection != MovementCorrection.OFF) {
     *         managedYaw
     *     } else {
     *         playerYaw
     *     }
     * </pre>
     */
    public static float resolveMovementYaw(float playerYaw, float managedYaw, MovementCorrection movementCorrection) {
        if (Float.isFinite(managedYaw) && movementCorrection != null && movementCorrection != MovementCorrection.OFF) {
            return managedYaw;
        } else {
            return playerYaw;
        }
    }

}
