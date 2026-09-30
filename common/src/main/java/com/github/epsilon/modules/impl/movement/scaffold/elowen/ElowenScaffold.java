package com.github.epsilon.modules.impl.movement.scaffold.elowen;

import com.github.epsilon.events.impl.PacketEvent;
import com.github.epsilon.events.impl.Render2DEvent;
import com.github.epsilon.events.impl.StartUseItemEvent;
import com.github.epsilon.graphics.renderers.RoundRectRenderer;
import com.github.epsilon.graphics.renderers.TextRenderer;
import com.github.epsilon.managers.rotation.RotationManager;
import com.github.epsilon.modules.impl.movement.Scaffold;
import com.github.epsilon.modules.impl.movement.scaffold.elowen.place.KeepYMode;
import com.github.epsilon.modules.impl.movement.scaffold.elowen.place.NormalMode;
import com.github.epsilon.modules.impl.movement.scaffold.elowen.place.PlaceModeHandler;
import com.github.epsilon.modules.impl.movement.scaffold.elowen.place.SnapMode;
import com.github.epsilon.modules.impl.movement.scaffold.elowen.place.TellyBridgeMode;
import com.github.epsilon.modules.impl.movement.scaffold.elowen.rotation.AcaRotation;
import com.github.epsilon.modules.impl.movement.scaffold.elowen.rotation.CurveRotation;
import com.github.epsilon.modules.impl.movement.scaffold.elowen.rotation.HeypixelRotation;
import com.github.epsilon.modules.impl.movement.scaffold.elowen.rotation.LinearRotation;
import com.github.epsilon.modules.impl.movement.scaffold.elowen.rotation.NoneRotation;
import com.github.epsilon.modules.impl.movement.scaffold.elowen.rotation.RotationHandler;
import com.github.epsilon.utils.client.KeybindUtils;
import com.github.epsilon.utils.math.MathUtils;
import com.github.epsilon.utils.player.SkipTickUtility;
import com.github.epsilon.utils.rotation.Priority;
import com.github.epsilon.utils.rotation.RaytraceUtils;
import com.github.epsilon.utils.rotation.Rot2f;
import com.github.epsilon.utils.rotation.RotationUtils;
import com.google.common.base.Suppliers;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.game.ClientboundSetEntityMotionPacket;
import net.minecraft.network.protocol.game.ServerboundMovePlayerPacket;
import net.minecraft.network.protocol.game.ServerboundSwingPacket;
import net.minecraft.util.Mth;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.level.block.AirBlock;
import net.minecraft.world.level.block.AnvilBlock;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.ChestBlock;
import net.minecraft.world.level.block.CraftingTableBlock;
import net.minecraft.world.level.block.EnchantingTableBlock;
import net.minecraft.world.level.block.EnderChestBlock;
import net.minecraft.world.level.block.FurnaceBlock;
import net.minecraft.world.level.block.LiquidBlock;
import net.minecraft.world.level.block.SnowLayerBlock;
import net.minecraft.world.level.block.TallGrassBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.phys.shapes.VoxelShape;
import com.mojang.blaze3d.platform.InputConstants;

import java.awt.Color;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.function.Supplier;

import static com.github.epsilon.Constants.mc;

/**
 * Elowen 的 Scaffold 引擎（逐行移植自 {@code com.elowen.o.Y.Z.Scaffold}）。
 *
 * <p>elowen 侧「模块」与「算法」在同一个类里；本移植把算法整段搬进本类，
 * Epsilon 的 {@link Scaffold} 只保留设置、生命周期与事件接线
 * （照 {@code scaffold/HeypixelScaffold} 的骨架）。**所有设置都从 {@code module} 读**，本类自己不持 Setting。</p>
 *
 * <p>三处移植纪律内的改动（§判定基线 / §API 对照表 / 语法适配）：</p>
 * <ul>
 *   <li>混淆哨兵 {@code Scaffold.S()==true} / {@code k()==false} / {@code Q.x()==true} / {@code Q.X()==false} /
 *       {@code getConfusionFlag()==null} / {@code h_0.e()==null} 对应的死分支全部删除。</li>
 *   <li>elowen 的 {@code com.elowen.z.h_0}（可变 yaw/pitch 对）→ {@link Rot2f}；
 *       {@code Z/z.N}→{@code Mth.wrapDegrees}、{@code Z/z.K}/{@code Z/W.s}→{@code Mth.clamp}、
 *       {@code Z/z.P}→区间最近点、{@code Z/W.K(a,b)}→区间随机。</li>
 *   <li>elowen 写 {@code player.setYRot/setXRot}（真实视角）来落地脚本角；Epsilon 的 Scaffold 受
 *       「静默旋转铁律」约束，脚本角只存在 {@link #currentRotation} 里，每刻经
 *       {@link RotationManager#setRotationsDirect} 提交发包角（玩家视角不动）。
 *       因此 elowen 里所有 {@code Q()}/{@code j}（= 玩家真实旋转）在本移植里统一读
 *       {@link #currentRotation}；elowen 的 {@code c()}/{@code a()}（服务器旋转）读
 *       {@link RotationManager#getRotation()}。</li>
 * </ul>
 *
 * <p>已知的两处**崩溃防护**（elowen 原样会对 null 解引用，见各方法内注释）：{@code isValidFace} 的
 * {@code hitResult == null}、{@code isNearCurrentTarget} 的 {@code targetBlock == null}。</p>
 *
 * <p>来源区间逐块登记在各方法头部，格式 {@code [elowen o/Y/Z/Scaffold.java:起-止]}。</p>
 */
public final class ElowenScaffold {

    private final Scaffold module;

    public ElowenScaffold(Scaffold module) {
        this.module = module;
    }

    // ===== 状态字段 =====
    // [elowen o/Y/Z/Scaffold.java:115-142]（原 J「上一 tick 已发送旋转」holder 不移植：
    // Epsilon 的 RotationManager 自己维护 lastRotations，elowen 里 J 只被写不被读）

    /** 原 {@code Scaffold.j}：当前脚本角。 */
    public Rot2f currentRotation = new Rot2f(0.0f, 0.0f);
    /** 原 {@code Scaffold.Y}：本刻放置目标。 */
    private BlockPos targetBlock;
    /** 原 {@code Scaffold.B}：渲染用目标（与 {@code Y} 同值）。 */
    private BlockPos renderBlock;
    /** 原 {@code Scaffold.F}：刻计数。 */
    private long tickCounter;
    /** 原 {@code Scaffold.Z}：上次放置成功的刻。 */
    private long lastPlacedTick = -1L;
    /** 原 {@code Scaffold.x}：上次发送放置旋转包的刻。 */
    private long lastRotationPacketTick = -1L;
    /** 原 {@code Scaffold.t}：Sneak 计时。 */
    private int sneakCounter;
    /** 原 {@code Scaffold.V}：离地刻数。 */
    private int airTicksSinceGround;
    /** 原 {@code Scaffold.z}：连续卡住刻数。 */
    private int stuckCounter;
    /** 原 {@code Scaffold.e}：跳刻自救尝试次数。 */
    private int skipAttempts;
    /** 原 {@code Scaffold.R}：收到自身速度包后的冷却（原 60 刻）。 */
    private int motionCooldown;
    /** 原 {@code Scaffold.aP}：放置后闸门刻数。 */
    private int postPlaceGate;
    /** 原 {@code Scaffold.U}（静态 ±1 抖动方向）。 */
    private int nudgeDirection = 1;
    /** 原 {@code Scaffold.ak}：待执行的跳刻放置。 */
    private boolean pendingSkipPlace;
    /** 原 {@code Scaffold.q}：允许放置标记。 */
    private boolean canPlaceFlag = true;
    /** 原 {@code Scaffold.al}：物理跳跃键（每刻采样）。 */
    private boolean jumpKeyPhysicallyDown;
    /** 原 {@code Scaffold.d}：快捷栏可放置方块总数。 */
    private int hotbarBlockCount;
    /** 原 {@code Scaffold.b}：启用时的选中槽。 */
    private int savedSlot = -1;

    /**
     * 原 {@code Elowen.SKIP_TICKS != 0} 在本刻模块逻辑内的等价物（§2.8）。
     * elowen 是「读写全局 int」，Epsilon 是「{@link SkipTickUtility#addSkipTicks(int)} + 消费」，
     * 故用本布尔承接 {@code placeBlock} 的 {@code SKIP_TICKS != 0} 闸门；每刻在 {@link #onPreTick()} 开头复位。
     */
    private boolean skipTickArmed;

    // ===== 模式实例 =====
    // [elowen o/Y/Z/Scaffold.java:195-199] 四个放置模式是固定实例；旋转模式照 elowen 每刻 new（见 selectRotationMode）

    private final NormalMode normalMode = new NormalMode();
    private final SnapMode snapMode = new SnapMode();
    private final TellyBridgeMode tellyBridgeMode = new TellyBridgeMode();
    private final KeepYMode keepYMode = new KeepYMode();

    /** 原 {@code Scaffold.M}：当前放置模式。 */
    private PlaceModeHandler placeModeHandler = normalMode;
    /** 原 {@code Scaffold.m}：当前旋转模式。 */
    private RotationHandler rotationHandler = new LinearRotation();

    private final Supplier<RoundRectRenderer> roundRectSupplier = Suppliers.memoize(RoundRectRenderer::create);
    private final Supplier<TextRenderer> textRendererSupplier = Suppliers.memoize(TextRenderer::create);

    // ===== 对外访问器（放置/旋转模式读设置与状态）=====

    public Minecraft minecraft() {
        return mc;
    }

    /** OpenPal/elowen 的 {@code module.f.w()}（Eagle）。 */
    public boolean eagle() {
        return module.elowenEagle.getValue();
    }

    /** elowen 的 {@code module.i.r}（Jump Sprint）。 */
    public boolean jumpSprint() {
        return module.elowenJumpSprint.getValue();
    }

    /** elowen 的 {@code module.getRotationSpeed()}。 */
    public float rotationSpeed() {
        return module.elowenRotationSpeed.getValue();
    }

    /** elowen 的 {@code module.v()}（Back Rotation Speed）。 */
    public float backRotationSpeed() {
        return module.elowenBackRotationSpeed.getValue();
    }

    public RotationHandler rotationHandler() {
        return rotationHandler;
    }

    public BlockPos getRenderBlock() {
        return renderBlock;
    }

    public int airTicksSinceGround() {
        return airTicksSinceGround;
    }

    /** 原 {@code Scaffold.c()}：服务端看到的 yaw（elowen 的 {@code SkiaRenderManager.X} → 本仓库托管角）。 */
    public float serverYaw() {
        return Mth.wrapDegrees(RotationManager.INSTANCE.getRotation().getYaw());
    }

    /** 原 {@code Scaffold.a()}：服务端看到的 pitch（原式对 pitch 做 ±90 clamp）。 */
    public float serverPitch() {
        return Mth.clamp(RotationManager.INSTANCE.getRotation().getPitch(), -90.0f, 90.0f);
    }

    /**
     * 原 {@code RotationUtils.i(maxStep, from, to)} / {@code .q(from, to, maxStep)} 的 live 支：
     * {@code from + clamp(wrapDegrees(to - from), -maxStep, +maxStep)}。**返回值不 wrap**（与 elowen 一致，调用方自己 wrap）。
     *
     * <p>[elowen Z/k/RotationUtils.java:349-366]（{@code RotationUtils.t()==true} ⇒ 走 clamp 分支）</p>
     */
    public static float stepTo(float from, float to, float maxStep) {
        return from + Mth.clamp(Mth.wrapDegrees(to - from), -maxStep, maxStep);
    }

    /**
     * 原 {@code RotationUtils.getQuantizedRotation(yaw, pitch, baseYaw, basePitch)}：
     * 相对基准角的偏移按灵敏度网格量化（{@code f - f % q}，向零取整）。
     * elowen 的 {@code f³*1.2} 与本仓库 {@link RotationUtils#mouseQuantum()}（{@code f³*8*0.15}）是同一式子。
     *
     * <p>[elowen Z/k/RotationUtils.java:40-49]</p>
     */
    public static Rot2f getQuantizedRotation(float yaw, float pitch, float baseYaw, float basePitch) {
        float quantum = (float) RotationUtils.mouseQuantum();
        float yawDelta = yaw - baseYaw;
        float pitchDelta = pitch - basePitch;
        float quantizedYaw = yawDelta - yawDelta % quantum;
        float quantizedPitch = pitchDelta - pitchDelta % quantum;
        return new Rot2f(baseYaw + quantizedYaw, basePitch + quantizedPitch);
    }

    /** 物理按键是否真的按下（elowen 的 {@code Scaffold.isKeyDown(KeyMapping)}，走 mixin accessor 取 key）。 */
    private static boolean physicalKeyDown(KeyMapping mapping) {
        int code = KeybindUtils.getKey(mapping);
        return code >= 0 && InputConstants.isKeyDown(mc.getWindow(), code);
    }

    /** 原 {@code Scaffold.s()}：物理跳跃键。 */
    public boolean jumpKeyPhysicallyDown() {
        return physicalKeyDown(mc.options.keyJump);
    }

    /** 原 {@code Scaffold.isMoving()} 的 live 支。 */
    public boolean isMoving() {
        if (mc.player == null) {
            return false;
        }
        return mc.player.getDeltaMovement().horizontalDistance() - 0.01 > 0.0;
    }

    /**
     * 原 {@code Scaffold.c(float)} 的 live 支：把玩家碰撞箱下移 0.5、水平内缩 {@code shrink} 后
     * **没有**碰撞 ⇒ 返回 true（即「脚下悬空/贴边」）。
     *
     * <p>[elowen o/Y/Z/Scaffold.java:326-333]</p>
     */
    public boolean noCollisionBelow(float shrink) {
        return !mc.level.getBlockCollisions(mc.player,
                mc.player.getBoundingBox().move(0.0, -0.5, 0.0).inflate(-shrink, 0.0, -shrink)).iterator().hasNext();
    }

    /** 原 {@code Scaffold.isJumpingWithoutMoving()}。 */
    public boolean isJumpingWithoutMoving() {
        if (jumpKeyPhysicallyDown()) {
            return !isMoving();
        }
        return false;
    }

    // ===== 生命周期 =====

    /** [elowen o/Y/Z/Scaffold.java:948-975] onEnable() */
    public void onEnable() {
        if (mc.player == null) {
            return;
        }
        savedSlot = mc.player.getInventory().getSelectedSlot();
        currentRotation.set(serverYaw(), serverPitch());
        targetBlock = null;
        renderBlock = null;
        motionCooldown = 0;
        stuckCounter = 0;
        skipAttempts = 0;
        canPlaceFlag = true;
        jumpKeyPhysicallyDown = false;
        sneakCounter = 0;
        lastPlacedTick = -1L;
        lastRotationPacketTick = -1L;
        tickCounter = 0L;
        postPlaceGate = 0;
        airTicksSinceGround = 0;
        pendingSkipPlace = false;
        skipTickArmed = false;
        autoSelectBlock();
        selectPlaceMode();
        selectRotationMode();
        placeModeHandler.onEnable();
        commitRotation();
    }

    /** [elowen o/Y/Z/Scaffold.java:977-990] onDisable() */
    public void onDisable() {
        if (mc.player != null) {
            if (savedSlot >= 0) {
                mc.player.getInventory().setSelectedSlot(savedSlot);
            }
            mc.options.keyJump.setDown(physicalKeyDown(mc.options.keyJump));
            mc.options.keyShift.setDown(physicalKeyDown(mc.options.keyShift));
            mc.options.keySprint.setDown(physicalKeyDown(mc.options.keySprint));
            mc.options.keyUse.setDown(false);
        }
        SkipTickUtility.reset();
        placeModeHandler.onDisable();
        // [适配·必须] elowen 的 RotationManager 有自己的停用路径；Epsilon 侧托管必须显式释放，
        // 否则准星与 MovementFix 会永久粘在旧角（见 RotationMouseHandler 注释）。
        RotationManager.INSTANCE.setActive(false);
    }

    // ===== 每刻 =====

    /**
     * [elowen o/Y/Z/Scaffold.java:1124-1190] onTick(MinecraftTickEvent PRE)。
     *
     * <p>[适配·§2.8] elowen 的 {@code Elowen.SKIP_TICKS} 由 level mixin 消费；Epsilon 侧改为
     * {@link SkipTickUtility#addSkipTicks(int)} + 本地 {@link #skipTickArmed}，故末尾的
     * {@code SKIP_TICKS = state}（恒 0）清算动作不移植，改为开头复位 {@link #skipTickArmed}。</p>
     */
    public void onPreTick() {
        tickCounter++;
        skipTickArmed = false;
        if (mc.gui.screen() != null || mc.player == null) {
            currentRotation.set(serverYaw(), serverPitch());
            return;
        }
        updateGroundState();
        selectRotationMode();
        autoSelectBlock();
        selectPlaceMode();
        renderBlock = targetBlock = findPlacementTarget();
        if (pendingSkipPlace) {
            doPendingSkipPlace();
        }
        if (pendingSkipPlace) {
            sendPlacementRotation();
            SkipTickUtility.addSkipTicks(1);
            skipTickArmed = true;
        }
        if (module.elowenSneak.getValue()) {
            int sneakTicks = ++sneakCounter;
            if (sneakTicks == 18) {
                if (mc.player.isSprinting()) {
                    mc.options.keySprint.setDown(false);
                    mc.player.setSprinting(false);
                }
                mc.options.keyShift.setDown(true);
            }
            if (sneakCounter >= 21) {
                mc.options.keyShift.setDown(false);
                sneakCounter = 0;
            }
        }
        jumpKeyPhysicallyDown = physicalKeyDown(mc.options.keyJump);
        placeModeHandler.onTick(this, targetBlock);
        if (motionCooldown > 0) {
            motionCooldown--;
        }
        if (postPlaceGate > 0) {
            postPlaceGate--;
        }
        // [适配·§2.4] elowen 由 RotationManager 在玩家 tick 末读取脚本角发包；
        // 这里在本刻把脚本角直通提交（同刻的 SendPositionEvent 就会带上它）。
        commitRotation();
    }

    /** 脚本角提交（静默：只改发包角，不写 {@code player.setYRot/setXRot}）。 */
    private void commitRotation() {
        RotationManager.INSTANCE.setRotationsDirect(new Rot2f(currentRotation.getYaw(), currentRotation.getPitch()), Priority.Medium);
    }

    /**
     * 原 {@code Scaffold.p(UpdateEvent)} 的前半段（离地刻数）。
     * [elowen o/Y/Z/Scaffold.java:927-934]
     */
    private void updateGroundState() {
        if (mc.player.onGround()) {
            airTicksSinceGround = 0;
        } else {
            ++airTicksSinceGround;
        }
        countHotbarBlocks();
    }

    /** 右键被接管时调用（elowen {@code onUseItem}）。 */
    public void onUseItem(StartUseItemEvent event) {
        selectPlaceMode();
        placeModeHandler.onUseItem(this, targetBlock, event);
    }

    /**
     * 收包（elowen 的 {@code onPacket} → 四个模式的 {@code onPacket} → {@code module.E(z2)}）。
     * [elowen o/Y/Z/Scaffold.java:895-911]
     */
    public void onPacketReceive(PacketEvent.Receive event) {
        Packet<?> packet = event.getPacket();
        if (!(packet instanceof ClientboundSetEntityMotionPacket motionPacket)) {
            return;
        }
        if (mc.player == null || motionPacket.id() != mc.player.getId()) {
            return;
        }
        double horizontalSpeedSq = new Vec3(motionPacket.movement().x, 0.0, motionPacket.movement().z).lengthSqr();
        if (horizontalSpeedSq >= 1.5) {
            motionCooldown = 60;
        }
    }

    // ===== 模式选择 =====

    /** [elowen o/Y/Z/Scaffold.java:219-241] selectRotationHandler() */
    private void selectRotationMode() {
        ElowenRotationMode mode = module.elowenRotationMode.getValue();
        RotationHandler handler = switch (mode) {
            case ACA -> new AcaRotation();
            case None -> new NoneRotation();
            case Curve -> new CurveRotation();
            case Heypixel -> new HeypixelRotation();
            case Linear -> new LinearRotation();
        };
        if (rotationHandler == null || rotationHandler.getClass() != handler.getClass()) {
            rotationHandler = handler;
        }
    }

    /** [elowen o/Y/Z/Scaffold.java:243-261] selectPlaceModeHandler() */
    private void selectPlaceMode() {
        PlaceModeHandler handler = switch (module.elowenPlaceMode.getValue()) {
            case Snap -> snapMode;
            case TellyBridge -> tellyBridgeMode;
            case KeepY -> keepYMode;
            case Normal -> normalMode;
        };
        if (placeModeHandler != handler) {
            placeModeHandler.onDisable();
            placeModeHandler = handler;
            placeModeHandler.onEnable();
        }
    }

    // ===== 目标搜索 =====

    /**
     * [elowen o/Y/Z/Scaffold.java:506-543] findPlacementTarget()
     * （脚下 11×11×N 邻域里按眼睛距离取最近的合法方块）。
     */
    public BlockPos findPlacementTarget() {
        BlockPos playerBlock = BlockPos.containing(mc.player.getX(), mc.player.getY() - 1.0, mc.player.getZ());
        ArrayList<Vec3> candidates = new ArrayList<>();
        HashMap<Vec3, BlockPos> candidateBlocks = new HashMap<>();
        int dropDistance = 0;
        double fallSpeed = Math.abs(mc.player.getDeltaMovement().y);
        if (fallSpeed - 0.6 > 0.0) {
            dropDistance = Math.min(6, (int) Math.ceil((fallSpeed - 0.6) / 0.2));
        }
        int minY = Math.max(playerBlock.getY() - 1 - dropDistance, (int) Math.floor(mc.player.getEyeY() - 5.0));
        for (int x = playerBlock.getX() - 5; x <= playerBlock.getX() + 5; ++x) {
            for (int y = minY; y <= playerBlock.getY(); ++y) {
                for (int z = playerBlock.getZ() - 5; z <= playerBlock.getZ() + 5; ++z) {
                    BlockPos candidateBlock = new BlockPos(x, y, z);
                    if (!isValidTargetBlock(candidateBlock)) {
                        continue;
                    }
                    BlockState blockState = mc.level.getBlockState(candidateBlock);
                    Vec3 target = nearestPointInShape(candidateBlock, blockState);
                    if (target == null) {
                        continue;
                    }
                    candidates.add(target);
                    candidateBlocks.put(target, candidateBlock);
                }
            }
        }
        if (candidates.isEmpty()) {
            return null;
        }
        candidates.sort(Comparator.comparingDouble(this::distanceToEye));
        BlockPos nearest = candidateBlocks.get(candidates.get(0));
        if (!isJumpingWithoutMoving()) {
            return nearest;
        }
        if ((double) nearest.getY() == mc.player.getY() - 1.5) {
            return nearest;
        }
        return BlockPos.containing(mc.player.getX(), mc.player.getY() - 1.5, mc.player.getZ());
    }

    /** 原 {@code Scaffold.T(Vec3)}：眼睛到候选点的平方距离。 */
    private double distanceToEye(Vec3 vec3) {
        return mc.player.getEyePosition().distanceToSqr(vec3.x, vec3.y, vec3.z);
    }

    /** [elowen o/Y/Z/Scaffold.java:544-559] J(BlockPos)：候选方块黑名单（live 支）。 */
    public boolean isValidTargetBlock(BlockPos pos) {
        Block block = mc.level.getBlockState(pos).getBlock();
        return !(block instanceof LiquidBlock
                || block instanceof AirBlock
                || block instanceof ChestBlock
                || block instanceof FurnaceBlock
                || block instanceof EnderChestBlock
                || block instanceof TallGrassBlock
                || block instanceof SnowLayerBlock
                || block instanceof EnchantingTableBlock
                || block instanceof AnvilBlock
                || block instanceof CraftingTableBlock);
    }

    /**
     * [elowen o/Y/Z/Scaffold.java:315-324] P(BlockPos, BlockState)：
     * 眼睛坐标在「方块起点 ~ 方块起点 + 碰撞盒 max」区间内的最近点
     * （{@code Z/z.P(v, min, max)} → {@code Math.max(min, Math.min(v, max))}）。
     */
    private Vec3 nearestPointInShape(BlockPos pos, BlockState state) {
        VoxelShape shape = state.getShape(mc.level, pos);
        if (shape.isEmpty()) {
            return null;
        }
        Vec3 eyePos = mc.player.getEyePosition();
        double x = Math.max(pos.getX(), Math.min(eyePos.x, pos.getX() + shape.max(Direction.Axis.X)));
        double y = Math.max(pos.getY(), Math.min(eyePos.y, pos.getY() + shape.max(Direction.Axis.Y)));
        double z = Math.max(pos.getZ(), Math.min(eyePos.z, pos.getZ() + shape.max(Direction.Axis.Z)));
        return new Vec3(x, y, z);
    }

    /** [elowen o/Y/Z/Scaffold.java:331-333] isStandingOnRenderableBlock() */
    public boolean isStandingOnRenderableBlock() {
        if (mc.player == null || mc.level == null) {
            return false;
        }
        BlockPos pos = mc.player.blockPosition().below();
        BlockState blockState = mc.level.getBlockState(pos);
        return blockState.isSolidRender();
    }

    // ===== 自动换槽 / 计数 =====

    /** [elowen o/Y/Z/Scaffold.java:610-632] I()：主/副手都没方块时不动作，否则扫 0..8 槽切到第一个可放置方块。 */
    private void autoSelectBlock() {
        if (mc.player == null) {
            return;
        }
        boolean mainHandPlaceable = module.isValidStack(mc.player.getMainHandItem());
        boolean offHandPlaceable = module.isValidStack(mc.player.getOffhandItem());
        if (!mainHandPlaceable && !offHandPlaceable) {
            return;
        }
        for (int slot = 0; slot < 9; ++slot) {
            ItemStack slotStack = mc.player.getInventory().getItem(slot);
            if (!(slotStack.getItem() instanceof BlockItem)) {
                continue;
            }
            if (!module.isValidStack(slotStack)) {
                continue;
            }
            if (mc.player.getInventory().getSelectedSlot() == slot) {
                break;
            }
            mc.player.getInventory().setSelectedSlot(slot);
        }
    }

    /** [elowen o/Y/Z/Scaffold.java:936-951] countHotbarBlocks() */
    private void countHotbarBlocks() {
        hotbarBlockCount = 0;
        if (mc.player == null) {
            return;
        }
        for (int slot = 0; slot < 9; ++slot) {
            ItemStack slotStack = mc.player.getInventory().getItem(slot);
            if (slotStack.getItem() instanceof BlockItem && module.isValidStack(slotStack)) {
                hotbarBlockCount += slotStack.getCount();
            }
        }
        ItemStack offHandStack = mc.player.getOffhandItem();
        if (offHandStack.getItem() instanceof BlockItem && module.isValidStack(offHandStack)) {
            hotbarBlockCount += offHandStack.getCount();
        }
    }

    /** 当前手上（主手优先）的可放置手，都没有返回 null（elowen {@code getPlacementHand()}）。 */
    public InteractionHand getPlacementHand() {
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

    // ===== 射线 =====

    /** [elowen o/Y/Z/Scaffold.java:345-372] i(h_0, BlockPos)：按给定角打到指定方块才算命中。 */
    public BlockHitResult raycastTargetBlock(Rot2f rotation, BlockPos pos) {
        if (mc.player == null || mc.level == null || pos == null || rotation == null) {
            return null;
        }
        BlockState blockState = mc.level.getBlockState(pos);
        if (blockState.getShape(mc.level, pos).isEmpty()) {
            return null;
        }
        Vec3 eyePos = mc.player.getEyePosition();
        Vec3 lookVec = Vec3.directionFromRotation(rotation.getPitch(), rotation.getYaw());
        double range = mc.player.blockInteractionRange();
        Vec3 target = eyePos.add(lookVec.x * range, lookVec.y * range, lookVec.z * range);
        HitResult hitResult = mc.level.clip(new ClipContext(eyePos, target, ClipContext.Block.COLLIDER, ClipContext.Fluid.NONE, mc.player));
        if (hitResult == null || hitResult.getType() != HitResult.Type.BLOCK) {
            return null;
        }
        BlockHitResult blockHitResult = (BlockHitResult) hitResult;
        if (!blockHitResult.getBlockPos().equals(pos)) {
            return null;
        }
        return blockHitResult;
    }

    /** 原 {@code Scaffold.e(h_0, BlockPos)}。 */
    public boolean canReachBlock(Rot2f rotation, BlockPos pos) {
        return raycastTargetBlock(rotation, pos) != null;
    }

    /** [elowen o/Y/Z/Scaffold.java:374-376] e → RaycastUtils.B(1.0f, rotation) 的等价物。 */
    public HitResult raycastRotation(Rot2f rotation) {
        return RaytraceUtils.raytrace(rotation, mc.player.blockInteractionRange(), 1.0f);
    }

    // ===== 旋转求解 =====

    /** [elowen o/Y/Z/Scaffold.java:638-643] getTargetRotation() */
    public Rot2f getTargetRotation() {
        if (isJumpingWithoutMoving() && mc.hitResult != null) {
            return new Rot2f(serverYaw() - 180.0f, 90.0f);
        }
        return computeTargetRotation();
    }

    /** [elowen o/Y/Z/Scaffold.java:646-698] computeTargetRotation() */
    public Rot2f computeTargetRotation() {
        Rot2f rotation = new Rot2f(serverYaw() - 180.0f, 82.0f);
        float yaw = serverYaw();
        float jitter = MathUtils.getRandom(0.0f, 0.5f) - 0.25f;
        if (!mc.options.keyDown.isDown()) {
            yaw += 180.0f;
        }
        if (!mc.options.keyLeft.isDown()) {
            yaw += 45.0f;
        }
        if (mc.options.keyRight.isDown()) {
            yaw -= 45.0f;
        }
        if (!mc.options.keyUp.isDown()) {
            yaw -= 45.0f;
        }
        if (mc.options.keyRight.isDown()) {
            yaw += 45.0f;
        }
        if (!mc.options.keyRight.isDown()) {
            yaw += 90.0f;
        }
        if (mc.options.keyLeft.isDown()) {
            yaw -= 90.0f;
        }
        float target = yaw - 180.0f + jitter;
        if (module.elowenSidewayRotation.getValue()) {
            float currentYaw = currentRotation.getYaw();
            float lowerBound = target - 45.0f;
            float upperBound = target + 45.0f;
            target = Math.abs(Mth.wrapDegrees(lowerBound - currentYaw)) <= Math.abs(Mth.wrapDegrees(upperBound - currentYaw))
                    ? lowerBound
                    : upperBound;
        }
        rotation.setYaw(target);
        if (canPlaceAtPlayer()) {
            HitResult directHit = raycastRotation(rotation);
            if (isValidFace(directHit)) {
                return rotation;
            }
            ArrayList<Float> pitchCandidates = pitchCandidates(target);
            if (!pitchCandidates.isEmpty()) {
                pitchCandidates.sort(Comparator.comparingDouble(this::pitchDistance));
                rotation.setPitch(pitchCandidates.get(0));
                return rotation;
            }
            Rot2f searched = searchRotation(target);
            if (searched != null) {
                return searched;
            }
        }
        return rotation;
    }

    /** [elowen o/Y/Z/Scaffold.java:700-703] canPlaceAtPlayer() */
    private boolean canPlaceAtPlayer() {
        BlockPos belowPlayer = BlockPos.containing(mc.player.getX(), mc.player.getY() - 0.5, mc.player.getZ());
        return mc.level.isEmptyBlock(belowPlayer) && getPlacementHand() != null;
    }

    /** [elowen o/Y/Z/Scaffold.java:705-716] j(float yaw)：在当前 pitch 附近扫 ±(-30/+20) 度。 */
    private ArrayList<Float> pitchCandidates(float yaw) {
        ArrayList<Float> pitches = new ArrayList<>();
        for (float pitch = Math.max(currentRotation.getPitch() - 30.0f, -90.0f); pitch < Math.min(currentRotation.getPitch() + 20.0f, 90.0f); pitch += 0.3f) {
            Rot2f candidate = getQuantizedRotation(yaw, pitch, currentRotation.getYaw(), currentRotation.getPitch());
            HitResult hitResult = raycastRotation(new Rot2f(yaw, candidate.getPitch()));
            if (isValidFace(hitResult)) {
                pitches.add(candidate.getPitch());
            }
        }
        return pitches;
    }

    /** [elowen o/Y/Z/Scaffold.java:722-733] isValidFace(HitResult) */
    private boolean isValidFace(HitResult hitResult) {
        // [适配·防崩] elowen 直接对 hitResult 解引用；Epsilon 的 RaytraceUtils 在 level/entity 为空时返回 null。
        if (hitResult == null || hitResult.getType() != HitResult.Type.BLOCK) {
            return false;
        }
        BlockHitResult blockHitResult = (BlockHitResult) hitResult;
        if (!isValidTargetBlock(blockHitResult.getBlockPos())) {
            return false;
        }
        if (!isNearCurrentTarget(blockHitResult.getBlockPos())) {
            return false;
        }
        if (blockHitResult.getDirection() == Direction.DOWN) {
            return false;
        }
        return blockHitResult.getDirection() != Direction.UP;
    }

    /** [elowen o/Y/Z/Scaffold.java:735-748] isNearCurrentTarget(BlockPos) */
    private boolean isNearCurrentTarget(BlockPos pos) {
        // [适配·防崩] elowen 在 targetBlock == null 时对 Y 解引用（NPE）；无目标时本判据不可能为真。
        if (targetBlock == null) {
            return false;
        }
        if (mc.player.onGround()) {
            return pos.equals(targetBlock);
        }
        for (int x = targetBlock.getX() - 1; x <= targetBlock.getX() + 1; ++x) {
            for (int z = targetBlock.getZ() - 1; z <= targetBlock.getZ() + 1; ++z) {
                if (pos.equals(new BlockPos(x, targetBlock.getY(), z))) {
                    return true;
                }
            }
        }
        return false;
    }

    /** 原 {@code Scaffold.K(float pitch)}：候选 pitch 与当前 pitch 的差。 */
    private double pitchDistance(float pitch) {
        return Math.abs(pitch - currentRotation.getPitch());
    }

    /** [elowen o/Y/Z/Scaffold.java:752-770] searchRotation(float yaw) */
    private Rot2f searchRotation(float yaw) {
        for (float yawOffset = 0.0f; yawOffset - 180.0f < 0.0f; yawOffset += 1.0f) {
            for (float pitch = 0.0f; pitch < 25.0f; pitch += 1.0f) {
                for (int directionIndex = 0; directionIndex < 2; ++directionIndex) {
                    float pitchCandidate = currentRotation.getPitch() - pitch * (directionIndex == 0 ? 1.0f : -1);
                    float[][] candidates = new float[][]{{yaw + yawOffset, pitchCandidate}, {yaw - yawOffset, pitchCandidate}};
                    for (float[] candidate : candidates) {
                        float clampedPitch = Mth.clamp(candidate[1], -90.0f, 90.0f);
                        Rot2f rotation = getQuantizedRotation(candidate[0], clampedPitch, currentRotation.getYaw(), currentRotation.getPitch());
                        HitResult hitResult = raycastRotation(rotation);
                        if (isValidFace(hitResult)) {
                            return rotation;
                        }
                    }
                }
            }
        }
        return null;
    }

    // ===== 放置 =====

    /** [elowen o/Y/Z/Scaffold.java:378-403] placeBlock(h_0) */
    public boolean placeBlock(Rot2f rotation) {
        InteractionHand hand = getPlacementHand();
        if (targetBlock == null) {
            return false;
        }
        if (hand == null) {
            return false;
        }
        if (rotation == null) {
            return false;
        }
        if (skipTickArmed) {
            return false;
        }
        BlockHitResult hitResult = raycastTargetBlock(rotation, targetBlock);
        if (hitResult == null) {
            return false;
        }
        boolean packetOrderNormal;
        if (hitResult.getDirection() != Direction.UP) {
            packetOrderNormal = module.elowenPacketOrderN.getValue();
        } else if (mc.player.onGround() || isMoving() || jumpKeyPhysicallyDown) {
            packetOrderNormal = module.elowenPacketOrderN.getValue();
        } else {
            return false;
        }
        if (packetOrderNormal) {
            mc.gameMode.useItem(mc.player, hand);
        }
        InteractionResult interactionResult = mc.gameMode.useItemOn(mc.player, hand, hitResult);
        // [适配] 26.2 的 InteractionResult.SwingSource 只剩 CLIENT/SERVER（elowen 用 PREDICTED 判定），
        // 故按 §Assumptions 的退路：consumesAction() 为真且 Swing 开就挥手。
        if (!interactionResult.consumesAction()) {
            return false;
        }
        if (module.elowenSwing.getValue()) {
            mc.player.swing(hand, false);
        }
        mc.getConnection().send(new ServerboundSwingPacket(InteractionHand.MAIN_HAND));
        return true;
    }

    /** [elowen o/Y/Z/Scaffold.java:436-457] sendPlacementRotation()（Q() → currentRotation） */
    private void sendPlacementRotation() {
        if (mc.player == null || mc.level == null) {
            return;
        }
        if (mc.getConnection() == null) {
            return;
        }
        long currentTick = tickCounter;
        if (currentTick == lastRotationPacketTick) {
            return;
        }
        lastRotationPacketTick = currentTick;
        Rot2f rotation = currentRotation;
        mc.getConnection().send(new ServerboundMovePlayerPacket.Rot(rotation.getYaw(), rotation.getPitch(), mc.player.onGround(), false));
    }

    /** [elowen o/Y/Z/Scaffold.java:459-489] t()：待执行跳刻放置的清算。 */
    private void doPendingSkipPlace() {
        if (targetBlock == null || mc.player == null || mc.level == null) {
            pendingSkipPlace = false;
            return;
        }
        if (isStandingOnRenderableBlock()) {
            pendingSkipPlace = false;
            canPlaceFlag = true;
            stuckCounter = 0;
            skipAttempts = 0;
            return;
        }
        Rot2f rotation = currentRotation;
        Rot2f nudged = new Rot2f(rotation.getYaw() + 1.0E-4f * nudgeDirection, rotation.getPitch());
        boolean placed = false;
        if (canReachBlock(nudged, targetBlock)) {
            placed = placeBlock(nudged);
        }
        if (!placed && canReachBlock(rotation, targetBlock)) {
            placed = placeBlock(rotation);
        }
        if (placed) {
            lastPlacedTick = tickCounter;
            postPlaceGate = 0;
            nudgeDirection *= -1;
            SkipTickUtility.addSkipTicks(1);
            skipTickArmed = true;
        }
        postPlaceGate = 3;
        pendingSkipPlace = false;
        stuckCounter = 0;
        skipAttempts = 0;
        canPlaceFlag = true;
    }

    /** [elowen o/Y/Z/Scaffold.java:790-815] w(BlockPos)：放置闸门。 */
    public void placeOrGuard(BlockPos blockPos) {
        if (!mc.options.keyUse.isDown() || blockPos == null || mc.level == null) {
            return;
        }
        long currentTick = tickCounter;
        int placeDelay = module.elowenPlaceDelay.getValue();
        if (placeDelay > 0) {
            if (currentTick < lastPlacedTick) {
                return;
            }
            if (currentTick - lastPlacedTick - (long) placeDelay >= 0L) {
                return;
            }
        }
        if (canReachBlock(currentRotation, blockPos)) {
            return;
        }
        // 原：if (this.getKeyCode() == 0) return;（elowen 的 0 = 未绑定；Epsilon 的未绑定哨兵是 -1）
        if (module.getKeyBind() <= 0) {
            return;
        }
        lastPlacedTick = currentTick;
        stuckCounter = 0;
        skipAttempts = 0;
        canPlaceFlag = true;
    }

    /** [elowen o/Y/Z/Scaffold.java:817-825] R(BlockPos) */
    public void onUseItemPlace(BlockPos blockPos) {
        if (mc.player == null) {
            return;
        }
        if (mc.gui.screen() != null || blockPos == null) {
            return;
        }
        if (tickCounter == lastPlacedTick) {
            return;
        }
        placeAtTarget(blockPos);
    }

    /** [elowen o/Y/Z/Scaffold.java:827-894] c(BlockPos) */
    private void placeAtTarget(BlockPos target) {
        if (target == null) {
            return;
        }
        if (mc.player.getDeltaMovement().y < -0.1) {
            double simX = mc.player.getX();
            double simY = mc.player.getY();
            double simZ = mc.player.getZ();
            double simVelX = mc.player.getDeltaMovement().x;
            double simVelY = mc.player.getDeltaMovement().y;
            double simVelZ = mc.player.getDeltaMovement().z;
            for (int step = 0; step < 2; ++step) {
                simVelY -= 0.08;
                simX += simVelX;
                simVelY *= 0.98;
                simY += simVelY;
                simZ += simVelZ;
                if (simVelY < 0.0) {
                    continue;
                }
                if (!mc.level.isEmptyBlock(BlockPos.containing(simX, simY - 0.5, simZ))) {
                    continue;
                }
                simY = Math.floor(simY) + 0.5;
                simVelY = 0.0;
                break;
            }
            if ((double) target.getY() - simY > 0.0) {
                canPlaceFlag = false;
            }
        }
        BlockState targetState = mc.level.getBlockState(target);
        Vec3 targetCenter = nearestPointInShape(target, targetState);
        if (targetCenter == null) {
            return;
        }
        if (targetCenter.subtract(mc.player.getEyePosition()).lengthSqr() - 20.25 <= 0.0) {
            return;
        }
        int placeDelay = module.elowenPlaceDelay.getValue();
        if (placeDelay > 0) {
            if (lastPlacedTick < 0L) {
                return;
            }
            if (tickCounter - lastPlacedTick >= (long) placeDelay) {
                return;
            }
        }
        Rot2f current = currentRotation;
        if (canReachBlock(current, target)) {
            return;
        }
        if (postPlaceGate <= 0 && canPlaceFlag && motionCooldown <= 0 && stuckCounter >= 8) {
            if (skipAttempts >= 7) {
                canPlaceFlag = true;
                stuckCounter = 0;
                return;
            }
            if (isStandingOnRenderableBlock()) {
                canPlaceFlag = true;
                stuckCounter = 0;
                return;
            }
            if (!pendingSkipPlace && !skipTickArmed) {
                return;
            }
            SkipTickUtility.addSkipTicks(1);
            skipTickArmed = true;
            ++skipAttempts;
            ++stuckCounter;
            pendingSkipPlace = true;
            sendPlacementRotation();
        }
        if (placeBlock(current)) {
            lastPlacedTick = tickCounter;
            stuckCounter = 0;
            skipAttempts = 0;
        }
    }

    // ===== HUD =====

    /** [elowen o/Y/Z/Scaffold.java:992-1032] onRenderHUD(k)：屏幕中央药丸，显示 {@code Block: N}。 */
    public void renderHud(Render2DEvent.HUD event) {
        if (!module.elowenBlockCount.getValue()) {
            return;
        }
        if (mc.player == null) {
            return;
        }
        countHotbarBlocks();
        int screenWidth = mc.getWindow().getGuiScaledWidth();
        int screenHeight = mc.getWindow().getGuiScaledHeight();
        float hudOffsetY = module.elowenHudY.getValue();
        String label = "Block: " + hotbarBlockCount;
        // [适配] elowen 走 Skia（textScale 9.5564）；Epsilon 的 TextRenderer 以 1.0 为常规字号。
        float textScale = 1.0f;
        TextRenderer textRenderer = textRendererSupplier.get();
        float pillWidth = textRenderer.getWidth(label, textScale) + 10.0f;
        float pillHeight = 20.5f;
        float pillX = ((float) screenWidth - pillWidth) / 2.0f;
        float pillY = (float) (screenHeight / 2) - hudOffsetY;
        float cornerRadius = 5.0f;
        Color accentColor = new Color(6, 176, 241, 255);
        Color backgroundColor = new Color(20, 20, 20, 180);
        RoundRectRenderer roundRectRenderer = roundRectSupplier.get();
        roundRectRenderer.addRoundRect(pillX, pillY, pillWidth, pillHeight, cornerRadius, backgroundColor);
        roundRectRenderer.addRoundRect(pillX, pillY, pillWidth, 3.0f, 0.0f, accentColor);
        roundRectRenderer.drawAndClear();
        textRenderer.addText(label, pillX + 5.0f, pillY + 6.0f, textScale, new Color(0xFFFFFFFF, true));
        textRenderer.drawAndClear();
    }
}
