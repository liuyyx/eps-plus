/*
 * This file is part of Epsilon.
 *
 * 逐段照抄自 OpenPal（https://github.com/OpenPal）MC 1.21.10 的
 * wtf.opal.client.feature.module.impl.world.scaffold.mode.HeypixelScaffold（842 行）。
 * 命名按 §2 映射表逐一替换；未在表中覆盖、且本仓库有对应写法的名字在类内就地注明。
 *
 * <p><b>类骨架的两处差异（§4 第 1 行「普通类，字段 protected final Scaffold module」）：</b></p>
 * <ul>
 *   <li>OpenPal 的 {@code ModuleMode<ScaffoldModule>} 在 {@code module} 字段与
 *       {@code isEnabled()/getSettings()/getEffectiveMode()} 转发；本仓库没有模式注册表，
 *       所以 {@code module} 直接是 {@link Scaffold}，{@code module.getSettings()} 一律换成
 *       {@code module.getScaffoldSettings()}，{@code getEffectiveMode()} 换成模块侧的
 *       {@code isHeypixelMode()/isHypixelMode()}。</li>
 *   <li>{@code getEnumValue()}（返回 {@code ScaffoldSettings.Mode}）没有对应物，已去掉；
 *       {@code isHandlingEvents()} 保留，判定等价改写。</li>
 *   <li>{@code @Subscribe} 的处理方法在本仓库由 {@link Scaffold} 的分流调用（事件在
 *       {@code Scaffold} 上订阅，见其 {@code onPlayerTick}/{@code onMoveInput}/
 *       {@code onUitemsSendPosition}），所以这里只是普通公开方法。</li>
 * </ul>
 */
package com.github.epsilon.modules.impl.movement.scaffold;

import com.github.epsilon.events.impl.KeyboardInputEvent;
import com.github.epsilon.events.impl.PlayerTickEvent;
import com.github.epsilon.events.impl.SendPositionEvent;
import com.github.epsilon.modules.impl.movement.Scaffold;
import com.github.epsilon.modules.impl.movement.Stuck;
import com.github.epsilon.modules.impl.movement.scaffold.rotation.RotationHelper;
import com.github.epsilon.modules.impl.movement.scaffold.rotation.model.impl.HeypixelRotationModel;
import com.github.epsilon.modules.impl.movement.scaffold.rotation.model.impl.InstantRotationModel;
import com.github.epsilon.modules.impl.movement.scaffold.slot.SlotHelper;
import com.github.epsilon.utils.network.NetworkUtils;
import com.github.epsilon.utils.player.RaycastUtility;
import com.github.epsilon.utils.player.RaytracedRotation;
import com.github.epsilon.utils.player.RotationUtility;
import com.github.epsilon.utils.player.SkipTickUtility;
import com.github.epsilon.utils.rotation.Rot2f;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.game.ServerboundMovePlayerPacket;
import net.minecraft.util.Mth;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.CropBlock;
import net.minecraft.world.level.block.FlowerBlock;
import net.minecraft.world.level.block.MushroomBlock;
import net.minecraft.world.level.block.SlabBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;

import java.util.Arrays;
import java.util.List;

import static com.github.epsilon.Constants.mc;

public class HeypixelScaffold {

    private static final Direction[] DIRECTIONS = Direction.values();

    protected final Scaffold module;

    public HeypixelScaffold(Scaffold module) {
        this.module = module;
    }

    private int airTick;
    private int yLevel;
    private BlockPos blockPos;
    private Direction enumFacing;
    private int oldSlot = -1;
    private float baseYaw;
    private float forwardYaw;
    private Rot2f lastTargetRotation;
    private Rot2f lastValidPlaceRotation;
    private BlockHitResult lastHitResult;
    private int rotateCount;
    private boolean checkedBlock;
    private int stuckTicks;
    private int skipRecoveryAttempts;
    private int skipRecoveryActiveTicks;
    private int skipRecoverySkipTicks;
    private int skipRecoveryNoPlaceTicks;
    private Rot2f packetRotation;
    private int packetRotationTicks;
    private int searchYTop;
    private int duplicateRotNonce;
    private int upTellyRotateTick;
    private int upTellyJumpTick;

    private static final int MAX_RESCUE_ATTEMPTS = 8;

    private static final List<Block> BLACKLISTED_BLOCKS = Arrays.asList(
            Blocks.AIR, Blocks.WATER, Blocks.LAVA, Blocks.ENCHANTING_TABLE, Blocks.GLASS_PANE,
            Blocks.IRON_BARS, Blocks.SNOW, Blocks.COAL_ORE, Blocks.DIAMOND_ORE, Blocks.EMERALD_ORE,
            Blocks.CHEST, Blocks.TRAPPED_CHEST, Blocks.TORCH, Blocks.ANVIL, Blocks.NOTE_BLOCK,
            Blocks.JUKEBOX, Blocks.TNT, Blocks.GOLD_ORE, Blocks.IRON_ORE, Blocks.LAPIS_ORE,
            Blocks.STONE_PRESSURE_PLATE, Blocks.LIGHT_WEIGHTED_PRESSURE_PLATE, Blocks.HEAVY_WEIGHTED_PRESSURE_PLATE,
            Blocks.STONE_BUTTON, Blocks.LEVER, Blocks.TALL_GRASS, Blocks.TRIPWIRE, Blocks.TRIPWIRE_HOOK,
            Blocks.RAIL, Blocks.CORNFLOWER, Blocks.RED_MUSHROOM, Blocks.BROWN_MUSHROOM, Blocks.VINE,
            Blocks.SUNFLOWER, Blocks.LADDER, Blocks.FURNACE, Blocks.SAND, Blocks.CACTUS, Blocks.DISPENSER,
            Blocks.DROPPER, Blocks.CRAFTING_TABLE, Blocks.COBWEB, Blocks.PUMPKIN, Blocks.COBBLESTONE_WALL,
            Blocks.OAK_FENCE, Blocks.REDSTONE_TORCH, Blocks.FLOWER_POT
    );

    public boolean isHandlingEvents() {
        return module.isEnabled() && module.isHeypixelMode();
    }

    public void onEnable() {
        if (mc.player != null) {
            oldSlot = mc.player.getInventory().getSelectedSlot();
            baseYaw = mc.player.getYRot();
            forwardYaw = resolveBaseYaw();
            stuckTicks = 0;
            skipRecoveryAttempts = 0;
            skipRecoveryActiveTicks = 0;
            skipRecoverySkipTicks = 0;
            skipRecoveryNoPlaceTicks = 0;
            lastValidPlaceRotation = null;
            packetRotation = null;
            packetRotationTicks = 0;
            duplicateRotNonce = 0;
            upTellyRotateTick = 0;
            upTellyJumpTick = 0;
            rotateCount = 0;
            checkedBlock = false;
            lastHitResult = null;
        }
    }

    public void onDisable() {
        SlotHelper.getInstance().stop();
        if (mc.player != null && oldSlot != -1) {
            mc.player.getInventory().setSelectedSlot(oldSlot);
        }
        final var handler = RotationHelper.getHandler();
        if (mc.player != null) {
            final float clientYaw = RotationHelper.getClientHandler().getYawOr(mc.player.getYRot());
            final float clientPitch = RotationHelper.getClientHandler().getPitchOr(mc.player.getXRot());
            handler.rotate(new Rot2f(clientYaw, clientPitch), new HeypixelRotationModel(this.getRotateBackSpeed()));
            handler.reverse();
        } else {
            handler.reset();
        }
        rotateCount = 0;
        checkedBlock = false;
        lastHitResult = null;
        upTellyRotateTick = 0;
        upTellyJumpTick = 0;
        skipRecoveryActiveTicks = 0;
        skipRecoverySkipTicks = 0;
        skipRecoveryNoPlaceTicks = 0;
    }

    protected float resolveBaseYaw() {
        return RotationHelper.getClientHandler().getYawOr(mc.player.getYRot());
    }

    protected boolean shouldTrackMovementYawDuringTelly() {
        return false;
    }

    protected boolean isTellyEnabled() {
        return module.getScaffoldSettings().isTelly();
    }

    protected boolean isSafeWalkEnabled() {
        return module.getScaffoldSettings().isSafeWalk();
    }

    protected boolean isSnapEnabled() {
        return module.getScaffoldSettings().isSnap();
    }

    protected boolean useInteractBeforePlace() {
        return module.getScaffoldSettings().isInteractBeforePlace();
    }

    protected int getTellyTick() {
        return module.getScaffoldSettings().getTellyTick();
    }

    protected float getRotateSpeed() {
        return module.getScaffoldSettings().getRotateSpeed();
    }

    protected float getRotateBackSpeed() {
        return module.getScaffoldSettings().getRotateBackSpeed();
    }

    public void onPreTick(PlayerTickEvent.Pre event) {
        if (mc.player == null || mc.level == null) return;

        final Stuck stuckModule = Stuck.INSTANCE;
        final boolean skipTickRecovery = module.isSkipTickRecoveryActive();
        if (stuckModule.isEnabled() || skipTickRecovery) {
            stuckTicks++;
            boolean hasBlock = false;
            for (int i = 0; i < 9; i++) {
                if (isValidStack(mc.player.getInventory().getItem(i))) {
                    hasBlock = true;
                    break;
                }
            }

            if (!hasBlock || (stuckTicks > 10 && blockPos == null)) {
                if (stuckModule.isEnabled()) {
                    stuckModule.setEnabled(false);
                }
                module.setSkipTickRecoveryActive(false);
                stuckTicks = 0;
                skipRecoveryAttempts = 0;
                rotateCount = 0;
                skipRecoveryActiveTicks = 0;
                skipRecoverySkipTicks = 0;
                skipRecoveryNoPlaceTicks = 0;
            }
        } else {
            stuckTicks = 0;
            skipRecoveryAttempts = 0;
            rotateCount = 0;
            skipRecoveryActiveTicks = 0;
            skipRecoverySkipTicks = 0;
            skipRecoveryNoPlaceTicks = 0;
        }

        int slotID = -1;
        for (int i = 0; i < 9; i++) {
            ItemStack stack = mc.player.getInventory().getItem(i);
            if (isValidStack(stack)) {
                slotID = i;
                break;
            }
        }
        if (slotID != -1) {
            final SlotHelper.Silence silence = switch (module.getScaffoldSettings().getSwitchMode()) {
                case NORMAL -> SlotHelper.Silence.NONE;
                case FULL -> SlotHelper.Silence.FULL;
                case HOTBAR -> SlotHelper.Silence.DEFAULT;
            };
            SlotHelper.setCurrentItem(slotID).silence(silence);
        } else {
            SlotHelper.getInstance().stop();
        }

        if (mc.player.onGround()) yLevel = (int) Math.floor(mc.player.getY()) - 1;

        getBlockInfo();

        ScaffoldSettings settings = module.getScaffoldSettings();

        if (handleSkipTickRecovery()) {
            return;
        }

        if (this.isSafeWalkEnabled() && !this.isTellyEnabled()) {
            boolean edge = mc.player.onGround() && isOnBlockEdge(0.3F);
            mc.options.keyShift.setDown(edge);
        }

        if (this.isTellyEnabled()) {
            if (mc.player.onGround()) {
                airTick = 0;
                blockPos = null;
                enumFacing = null;

                float clientYaw = resolveBaseYaw();
                float clientPitch = RotationHelper.getClientHandler().getPitchOr(mc.player.getXRot());

                forwardYaw = clientYaw;
                final Rot2f target = new Rot2f(clientYaw, clientPitch);
                this.lastTargetRotation = target;
                RotationHelper.getHandler().rotate(target, new HeypixelRotationModel(this.getRotateBackSpeed()));
            } else {
                if (shouldTrackMovementYawDuringTelly()) {
                    forwardYaw = resolveBaseYaw();
                }

                final int baseTellyTick = this.getTellyTick();
                int dynamicTellyTick = baseTellyTick;
                if (shouldAllowUpTelly()) {
                    dynamicTellyTick = Math.max(1, baseTellyTick - (isDiagonalYaw(forwardYaw) ? 3 : 2));
                }

                if (airTick < dynamicTellyTick) {
                    final Rot2f forward = new Rot2f(forwardYaw, mc.player.getXRot());
                    if (shouldAllowUpTelly()) {
                        upTellyRotateTick++;
                        if (upTellyRotateTick % 2 == 0) {
                            this.lastTargetRotation = forward;
                            RotationHelper.getHandler().rotate(forward, new HeypixelRotationModel(this.getRotateBackSpeed()));
                        }
                    } else {
                        upTellyRotateTick = 0;
                        this.lastTargetRotation = forward;
                        RotationHelper.getHandler().rotate(forward, new HeypixelRotationModel(this.getRotateBackSpeed()));
                    }

                    if (shouldAllowUpTelly() && isDiagonalYaw(forwardYaw) && airTick >= 1 && blockPos != null && enumFacing != null) {
                        Rot2f earlyRotation = getRotation(blockPos, enumFacing);
                        if (earlyRotation != null) {
                            this.lastTargetRotation = earlyRotation;
                            RotationHelper.getHandler().rotate(earlyRotation, InstantRotationModel.INSTANCE);
                            place();
                        }
                    }
                } else {
                    Rot2f rotation = getRotation(blockPos, enumFacing);
                    if (rotation != null) {
                        this.lastTargetRotation = rotation;
                        RotationHelper.getHandler().rotate(rotation, InstantRotationModel.INSTANCE);
                        place();
                    }
                }
                airTick++;
            }
        } else {
            if (blockPos == null) {
                final Rot2f target = new Rot2f(Mth.wrapDegrees(baseYaw - 180), 89.64F);
                this.lastTargetRotation = target;
                this.packetRotation = target;
                this.packetRotationTicks = 2;
                RotationHelper.getHandler().rotate(target, new HeypixelRotationModel(this.getRotateSpeed()));
            }
            if (onAir() || !this.isSnapEnabled()) {
                Rot2f rotation = getRotation(blockPos, enumFacing);
                if (rotation != null) {
                    this.lastTargetRotation = rotation;
                    this.packetRotation = rotation;
                    this.packetRotationTicks = 2;
                    RotationHelper.getHandler().rotate(rotation, new HeypixelRotationModel(this.getRotateSpeed()));
                }
            }
            place();
        }

    }

    private boolean handleSkipTickRecovery() {
        if (!module.isSkipTickRecoveryActive()) {
            return false;
        }

        skipRecoveryActiveTicks++;
        if (skipRecoveryActiveTicks > 26) {
            module.markSkipTickRecoveryFailed();
            skipRecoveryAttempts = 0;
            skipRecoverySkipTicks = 0;
            skipRecoveryNoPlaceTicks = 0;
            return true;
        }

        if (skipRecoverySkipTicks > 0 && skipRecoveryNoPlaceTicks > 6) {
            module.markSkipTickRecoveryFailed();
            skipRecoveryAttempts = 0;
            skipRecoverySkipTicks = 0;
            skipRecoveryNoPlaceTicks = 0;
            return true;
        }

        if (!onAir()) {
            module.setSkipTickRecoveryActive(false);
            skipRecoveryAttempts = 0;
            rotateCount = 0;
            skipRecoveryActiveTicks = 0;
            skipRecoverySkipTicks = 0;
            skipRecoveryNoPlaceTicks = 0;
            return true;
        }

        final BlockPos recoveryBlockPos = module.getSkipTickRecoveryBlockPos();
        final Direction recoveryFace = module.getSkipTickRecoveryFace();
        if (recoveryBlockPos != null && recoveryFace != null) {
            this.blockPos = recoveryBlockPos;
            this.enumFacing = recoveryFace;
        }

        final boolean hasTarget = blockPos != null && enumFacing != null;
        if (!hasTarget) {
            skipRecoveryAttempts++;
            skipRecoveryNoPlaceTicks++;
            if (skipRecoveryAttempts > 10) {
                module.markSkipTickRecoveryFailed();
                skipRecoveryAttempts = 0;
                skipRecoverySkipTicks = 0;
                skipRecoveryNoPlaceTicks = 0;
            }
            return true;
        }

        if (skipRecoveryAttempts > 12) {
            module.markSkipTickRecoveryFailed();
            skipRecoveryAttempts = 0;
            rotateCount = 0;
            skipRecoverySkipTicks = 0;
            skipRecoveryNoPlaceTicks = 0;
            return true;
        }

        final Rot2f rotation = getRotation(blockPos, enumFacing);
        if (rotation == null) {
            skipRecoveryAttempts++;
            skipRecoveryNoPlaceTicks++;
            return true;
        }

        applyPlacementRotation(rotation, this.getRotateSpeed());
        final boolean placed = place(false);
        if (placed) {
            skipRecoveryNoPlaceTicks = 0;
            skipRecoveryAttempts = 0;
            module.setSkipTickRecoveryActive(false);
            skipRecoveryActiveTicks = 0;
            skipRecoverySkipTicks = 0;
        } else {
            SkipTickUtility.addSkipTicks(1);
            skipRecoverySkipTicks++;
            skipRecoveryNoPlaceTicks++;
        }
        skipRecoveryAttempts++;

        return true;
    }

    private void applyPlacementRotation(Rot2f rotation, float rotateSpeed) {
        if (rotation == null) {
            return;
        }
        this.lastTargetRotation = rotation;
        this.packetRotation = rotation;
        this.packetRotationTicks = 2;
        RotationHelper.getHandler().rotate(rotation, new HeypixelRotationModel(rotateSpeed));
    }

    private void runSkidRescueRecursive(boolean tellyMode, float rotateSpeed, int depth) {
        if (depth >= MAX_RESCUE_ATTEMPTS || mc.player == null || mc.level == null) {
            return;
        }

        getBlockInfo();
        if (blockPos == null || enumFacing == null) {
            return;
        }

        Rot2f rotation = getRotation(blockPos, enumFacing);
        if (rotation == null) {
            return;
        }

        boolean reachable = computeReachable(tellyMode ? true : blockPos != null);
        if (checkedBlock && !reachable && rotateCount < MAX_RESCUE_ATTEMPTS) {
            rotateCount++;
            skipRecoveryAttempts++;
            SkipTickUtility.addSkipTicks(1);
            sendRescueRotationPacket(rotation);
            place(false);
            runSkidRescueRecursive(tellyMode, rotateSpeed, depth + 1);
        } else {
            applyPlacementRotation(rotation, rotateSpeed);
            place();
            rotateCount = Math.max(0, rotateCount - 1);
        }
    }

    private boolean computeReachable(boolean defaultReachable) {
        boolean reachable = defaultReachable;
        if (mc.player != null && blockPos != null && mc.player.getDeltaMovement().y < -0.1D) {
            if (blockPos.getY() > predictYAfterTicks(2)) {
                reachable = false;
            }
        }
        return reachable;
    }

    private double predictYAfterTicks(int ticks) {
        double y = mc.player.getY();
        double motionY = mc.player.getDeltaMovement().y;
        for (int i = 0; i < ticks; i++) {
            motionY = (motionY - 0.08D) * 0.98D;
            y += motionY;
        }
        return y;
    }

    private void sendRescueRotationPacket(Rot2f rotation) {
        if (rotation == null || mc.player == null) {
            return;
        }
        this.packetRotation = rotation;
        this.packetRotationTicks = 2;
        sendPacketSilent(new ServerboundMovePlayerPacket.Rot(rotation.getYaw(), rotation.getPitch(), mc.player.onGround(), false));
    }

    private void sendPacketSilent(Packet<?> packet) {
        NetworkUtils.sendPacketNoEvent(packet);
    }

    public void onMoveInput(KeyboardInputEvent event) {
        if (!this.isTellyEnabled() || !mc.player.isMoving()) {
            upTellyJumpTick = 0;
            return;
        }

        if (!mc.player.onGround()) {
            return;
        }

        if (mc.options.keyJump.isDown()) {
            upTellyJumpTick++;
            event.setJump(upTellyJumpTick % 2 == 0);
            return;
        }

        upTellyJumpTick = 0;
        event.setJump(true);
    }

    private boolean place() {
        return place(true);
    }

    private boolean place(boolean checkRotation) {
        if (!onAir()) return false;
        if (blockPos == null || enumFacing == null) return false;

        if (!checkRotation) {
            return performPlace(new BlockHitResult(getVec3(blockPos, enumFacing), enumFacing, blockPos, false));
        }

        Rot2f currentRot = RotationHelper.getHandler().isActive() ?
                RotationHelper.getClientHandler().getRotation() :
                null;
        if (currentRot == null) {
            currentRot = this.lastValidPlaceRotation != null ? this.lastValidPlaceRotation : new Rot2f(mc.player.getYRot(), mc.player.getXRot());
        }

        final HitResult hitResult = RaycastUtility.raycastBlock(4.5, 1.0F, false, currentRot.getYaw(), currentRot.getPitch());

        if (hitResult instanceof BlockHitResult blockHitResult && blockHitResult.getBlockPos().equals(blockPos) && blockHitResult.getDirection() == enumFacing) {
            this.lastValidPlaceRotation = currentRot;
            return performPlace(blockHitResult);
        } else {
            if (this.isTellyEnabled() || module.getScaffoldSettings().isOverrideRaycast()) {
                if (lastHitResult != null && lastHitResult.getBlockPos().equals(blockPos) && lastHitResult.getDirection() == enumFacing) {
                    this.lastValidPlaceRotation = this.lastTargetRotation;
                    return performPlace(lastHitResult);
                }
            }
        }

        return false;
    }

    private boolean performPlace(BlockHitResult hitResult) {
        ItemStack stack = mc.player.getMainHandItem();
        if (!(stack.getItem() instanceof BlockItem)) return false;

        if (this.useInteractBeforePlace()) {
            mc.gameMode.useItem(mc.player, InteractionHand.MAIN_HAND);
        }

        mc.player.swing(InteractionHand.MAIN_HAND);
        final boolean success = mc.gameMode.useItemOn(mc.player, InteractionHand.MAIN_HAND, hitResult).consumesAction();
        if (!success) {
            return false;
        }
        stuckTicks = 0;
        skipRecoveryAttempts = 0;
        return true;
    }

    private boolean overBlock(BlockPos pos, Direction facing) {
        final HitResult hitResult = RaycastUtility.raycastBlock(4.5, 1.0F, false, mc.player.getYRot(), mc.player.getXRot());
        if (hitResult instanceof BlockHitResult blockHitResult) {
            return blockHitResult.getBlockPos().equals(pos) && blockHitResult.getDirection() == facing;
        }
        return false;
    }

    private int getYLevel() {
        if (!mc.options.keyJump.isDown()
                && mc.player.isMoving()
                && mc.player.fallDistance <= 0.25
                && this.isTellyEnabled()
                && mc.player.getDeltaMovement().y <= 0.02) {
            return yLevel;
        } else {
            return (int) Math.floor(mc.player.getY()) - 1;
        }
    }

    private boolean shouldAllowUpTelly() {
        if (mc.player.horizontalCollision) {
            return false;
        }

        return this.isTellyEnabled()
                && mc.player.isMoving()
                && !mc.player.onGround()
                && (mc.options.keyJump.isDown() || mc.player.getDeltaMovement().y > -0.08);
    }

    public boolean shouldSuppressSkipTickRecoveryTrigger() {
        if (mc.player == null || mc.level == null) {
            return false;
        }

        if (!this.isTellyEnabled() || mc.player.onGround() || !mc.player.isMoving()) {
            return false;
        }

        final int baseTellyTick = this.getTellyTick();
        int dynamicTellyTick = baseTellyTick;
        if (shouldAllowUpTelly()) {
            dynamicTellyTick = Math.max(1, baseTellyTick - (isDiagonalYaw(forwardYaw) ? 3 : 2));
        }

        if (airTick > dynamicTellyTick + 1) {
            return false;
        }

        return mc.player.getDeltaMovement().y > -0.16D && mc.player.fallDistance < 1.6F;
    }

    private boolean isDiagonalYaw(final float yaw) {
        final double radians = Math.toRadians(Mth.wrapDegrees(yaw));
        final double diagonalStrength = Math.abs(Math.sin(radians * 2.0));
        return diagonalStrength > 0.58;
    }

    private void getBlockInfo() {
        Vec3 baseVec = mc.player.getEyePosition();
        int baseY = getYLevel();
        if (shouldAllowUpTelly()) {
            baseY += 1;
        }
        this.searchYTop = baseY;

        BlockPos base = BlockPos.containing(baseVec.x, baseY, baseVec.z);
        int baseX = base.getX();
        int baseZ = base.getZ();

        if (isSolidAndNonInteractive(mc.level.getBlockState(base), base)) {
            checkedBlock = false;
            return;
        }

        if (checkBlock(baseVec, base)) {
            return;
        }

        for (int d = 1; d <= 6; d++) {
            if (checkBlock(baseVec, new BlockPos(baseX, baseY - d, baseZ))) {
                return;
            }
            for (int x = 0; x <= d; x++) {
                for (int z = 0; z <= d - x; z++) {
                    int y = d - x - z;
                    for (int rev1 = 0; rev1 <= 1; rev1++) {
                        for (int rev2 = 0; rev2 <= 1; rev2++) {
                            if (checkBlock(baseVec, new BlockPos(baseX + (rev1 == 0 ? x : -x), baseY - y, baseZ + (rev2 == 0 ? z : -z))))
                                return;
                        }
                    }
                }
            }
        }

        checkedBlock = false;
    }

    private boolean isSolidAndNonInteractive(BlockState state, BlockPos pos) {
        boolean hasCollision = !state.getCollisionShape(mc.level, pos).isEmpty();
        boolean hasNoMenu = state.getMenuProvider(mc.level, pos) == null;
        return hasCollision && hasNoMenu;
    }

    private boolean checkBlock(Vec3 baseVec, BlockPos pos) {
        if (!mc.level.getBlockState(pos).isAir() && mc.level.getBlockState(pos).getBlock() != Blocks.LILY_PAD) {
            checkedBlock = false;
            return false;
        }

        if (pos.getY() > this.searchYTop) {
            checkedBlock = false;
            return false;
        }

        Vec3 center = new Vec3(pos.getX() + 0.5, pos.getY(), pos.getZ() + 0.5);
        for (Direction dir : DIRECTIONS) {
            Vec3 hit = center.add(new Vec3(dir.getStepX(), dir.getStepY(), dir.getStepZ()).scale(0.5));
            BlockPos baseBlockPos = pos.relative(dir);

            if (!isSolidAndNonInteractive(mc.level.getBlockState(baseBlockPos), baseBlockPos)) continue;

            Vec3 relevant = hit.subtract(baseVec);
            if (relevant.lengthSqr() <= 4.5 * 4.5 && relevant.dot(new Vec3(dir.getStepX(), dir.getStepY(), dir.getStepZ())) >= 0) {
                if (dir.getOpposite() == Direction.UP && mc.player.isMoving() && !mc.options.keyJump.isDown())
                    continue;
                blockPos = baseBlockPos;
                enumFacing = dir.getOpposite();
                checkedBlock = true;
                return true;
            }
        }

        checkedBlock = false;
        return false;
    }

    private boolean isValidStack(ItemStack stack) {
        if (stack == null || !(stack.getItem() instanceof BlockItem) || stack.getCount() <= 0) {
            return false;
        }
        Block block = ((BlockItem) stack.getItem()).getBlock();
        if (block instanceof FlowerBlock || block instanceof MushroomBlock || block instanceof CropBlock || block instanceof SlabBlock) {
            return false;
        }
        return !BLACKLISTED_BLOCKS.contains(block);
    }

    private Vec3 getVec3(BlockPos pos, Direction face) {
        double x = (double) pos.getX() + 0.5;
        double y = (double) pos.getY() + 0.5;
        double z = (double) pos.getZ() + 0.5;
        if (face != Direction.UP && face != Direction.DOWN) {
            y += 0.08;
        } else {
            x += (Math.random() - 0.5) * 0.4;
            z += (Math.random() - 0.5) * 0.4;
        }

        if (face == Direction.WEST || face == Direction.EAST) {
            z += (Math.random() - 0.5) * 0.4;
        }

        if (face == Direction.SOUTH || face == Direction.NORTH) {
            x += (Math.random() - 0.5) * 0.4;
        }

        return new Vec3(x, y, z);
    }

    private Rot2f getRotation(BlockPos pos, Direction face) {
        if (pos == null || face == null) return null;
        lastHitResult = null;
        final Rot2f directRot = RotationUtility.getVanillaRotation(RotationUtility.getRotationFromBlock(pos, face));
        final Rot2f reverseYawRot = new Rot2f(Mth.wrapDegrees(mc.player.getYRot() - 180F), directRot.getPitch());

        if (onAir()) {
            BlockHitResult reverse = raycastBlockWithRotation(reverseYawRot, pos, face);
            if (reverse != null) {
                lastHitResult = reverse;
                return withDuplicateRotJitter(reverseYawRot, pos, face);
            }
        }

        BlockHitResult direct = raycastBlockWithRotation(directRot, pos, face);
        if (direct != null) {
            lastHitResult = direct;
            return withDuplicateRotJitter(directRot, pos, face);
        }

        for (float yawOffset : new float[]{-2F, 2F, -4F, 4F, -7F, 7F, -12F, 12F}) {
            for (float pitchOffset : new float[]{0F, -1F, 1F, -2F, 2F, -4F, 4F}) {
                final Rot2f trial = new Rot2f(
                        Mth.wrapDegrees(directRot.getYaw() + yawOffset),
                        Mth.clamp(directRot.getPitch() + pitchOffset, -90F, 90F)
                );
                BlockHitResult result = raycastBlockWithRotation(trial, pos, face);
                if (result != null) {
                    lastHitResult = result;
                    return withDuplicateRotJitter(trial, pos, face);
                }
            }
        }

        RaytracedRotation raytraced = RotationUtility.getRotationFromRaycastedBlock(pos, face, directRot, mc.player.getEyePosition());
        if (raytraced != null) {
            if (raytraced.hitResult() instanceof BlockHitResult bhr) {
                lastHitResult = bhr;
            }
            return withDuplicateRotJitter(raytraced.rotation(), pos, face);
        }

        return withDuplicateRotJitter(directRot, pos, face);
    }

    private Rot2f withDuplicateRotJitter(final Rot2f base, final BlockPos pos, final Direction face) {
        if (!module.getScaffoldSettings().isDuplicateRotPlace() || base == null) {
            return base;
        }

        duplicateRotNonce++;
        final int idx = duplicateRotNonce % 6;
        final float yawJitter = switch (idx) {
            case 0 -> 0.032F;
            case 1 -> -0.037F;
            case 2 -> 0.021F;
            case 3 -> -0.026F;
            case 4 -> 0.014F;
            default -> -0.018F;
        };
        final float pitchJitter = (idx % 2 == 0) ? 0.012F : -0.009F;

        final Rot2f jittered = new Rot2f(
                Mth.wrapDegrees(base.getYaw() + yawJitter),
                Mth.clamp(base.getPitch() + pitchJitter, -89.9F, 89.9F)
        );

        final BlockHitResult jitterHit = raycastBlockWithRotation(jittered, pos, face);
        return jitterHit != null ? jittered : base;
    }

    public void onPreMovementPacket(final SendPositionEvent event) {
        if (mc.player == null || this.packetRotation == null || this.packetRotationTicks <= 0) {
            return;
        }

        event.setYaw(this.packetRotation.getYaw());
        event.setPitch(this.packetRotation.getPitch());
        this.packetRotationTicks--;
    }

    private BlockHitResult raycastBlockWithRotation(Rot2f rotation, BlockPos pos, Direction face) {
        final HitResult hitResult = RaycastUtility.raycastBlock(4.5, 1.0F, false, rotation.getYaw(), rotation.getPitch());
        BlockHitResult fallback = null;
        if (hitResult instanceof BlockHitResult blockHitResult) {
            if (blockHitResult.getBlockPos().equals(pos)) {
                if (blockHitResult.getDirection() == face) {
                    return blockHitResult;
                }
                fallback = blockHitResult;
            }
        }
        return fallback;
    }

    private boolean canOverBlockWithRotation(Rot2f rotation, BlockPos pos, Direction face) {
        return raycastBlockWithRotation(rotation, pos, face) != null;
    }

    private boolean onAir() {
        Vec3 baseVec = mc.player.getEyePosition();
        BlockPos base = BlockPos.containing(baseVec.x, getYLevel(), baseVec.z);
        return mc.level.getBlockState(base).isAir() || mc.level.getBlockState(base).getBlock() == Blocks.LILY_PAD;
    }

    private boolean isOnBlockEdge(float sensitivity) {
        AABB box = mc.player.getBoundingBox().move(0.0, -0.5, 0.0).inflate(-sensitivity, 0.0, -sensitivity);
        return mc.level.getBlockCollisions(mc.player, box).iterator().hasNext() == false;
    }
}
