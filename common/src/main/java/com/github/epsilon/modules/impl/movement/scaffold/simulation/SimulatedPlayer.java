/*
 * This file is part of Epsilon.
 *
 * 逐行移植自 LiquidBounce (https://github.com/CCBlueX/LiquidBounce)
 * 源文件: src/main/kotlin/net/ccbluex/liquidbounce/utils/entity/SimulatedPlayer.kt
 * Copyright (c) 2015 - 2026 CCBlueX — GNU General Public License v3.0
 */
package com.github.epsilon.modules.impl.movement.scaffold.simulation;

import com.github.epsilon.Constants;
import com.github.epsilon.interfaces.EntityFluidInteractionAccessor;
import com.github.epsilon.interfaces.EntityFluidInteractionTrackerAccessor;
import com.github.epsilon.modules.impl.movement.scaffold.util.BlockUtils;
import com.github.epsilon.modules.impl.movement.scaffold.util.MovementUtils;
import com.github.epsilon.modules.impl.movement.scaffold.util.VectorUtils;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Holder;
import net.minecraft.tags.BlockTags;
import net.minecraft.tags.FluidTags;
import net.minecraft.tags.TagKey;
import net.minecraft.util.Mth;
import net.minecraft.world.attribute.EnvironmentAttributes;
import net.minecraft.world.effect.MobEffect;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityFluidInteraction;
import net.minecraft.world.entity.ai.attributes.Attribute;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.LadderBlock;
import net.minecraft.world.level.block.PowderSnowBlock;
import net.minecraft.world.level.block.TrapDoorBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.material.Fluid;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.phys.shapes.VoxelShape;

import java.util.List;
import java.util.Map;

/**
 * LB 原文: {@code class SimulatedPlayer(...) : PlayerSimulation}
 */
public class SimulatedPlayer implements PlayerSimulation {

    // LB: private const val STEP_HEIGHT = 0.5
    private static final double STEP_HEIGHT = 0.5;

    private final Player player;
    public SimulatedPlayerInput input;
    public Vec3 pos;
    public Vec3 deltaMovement;
    public AABB boundingBox;
    public float yRot;
    public float xRot;
    public boolean isSprinting;

    public double fallDistance;
    private int jumpTriggerTime;
    private boolean jumping;
    private boolean fallFlying;
    public boolean onGround;
    public boolean horizontalCollision;
    private boolean verticalCollision;

    private boolean wasTouchingWater;
    private boolean isSwimming;
    private boolean wasUnderwater;
    private final EntityFluidInteraction fluidInteraction;

    public SimulatedPlayer(
            Player player,
            SimulatedPlayerInput input,
            Vec3 pos,
            Vec3 deltaMovement,
            AABB boundingBox,
            float yRot,
            float xRot,
            boolean isSprinting,

            double fallDistance,
            int jumpTriggerTime,
            boolean jumping,
            boolean fallFlying,
            boolean onGround,
            boolean horizontalCollision,
            boolean verticalCollision,

            boolean wasTouchingWater,
            boolean isSwimming,
            boolean wasUnderwater,
            EntityFluidInteraction fluidInteraction
    ) {
        this.player = player;
        this.input = input;
        this.pos = pos;
        this.deltaMovement = deltaMovement;
        this.boundingBox = boundingBox;
        this.yRot = yRot;
        this.xRot = xRot;
        this.isSprinting = isSprinting;

        this.fallDistance = fallDistance;
        this.jumpTriggerTime = jumpTriggerTime;
        this.jumping = jumping;
        this.fallFlying = fallFlying;
        this.onGround = onGround;
        this.horizontalCollision = horizontalCollision;
        this.verticalCollision = verticalCollision;

        this.wasTouchingWater = wasTouchingWater;
        this.isSwimming = isSwimming;
        this.wasUnderwater = wasUnderwater;
        this.fluidInteraction = fluidInteraction;
    }

    private Level getLevel() {
        return this.player.level();
    }

    // LB 原文（companion）: private fun EntityFluidInteraction.deepCopy(): EntityFluidInteraction
    // [适配] LB 用 MixinEntityFluidInteractionAccessor / MixinEntityFluidInteractionTrackerAccessor 访问
    //        包私有/私有的字段；Epsilon 侧沿用同一做法（interfaces + mixins 两个包）。
    //        Map 走 raw 类型：Fabric 与 NeoForge 的 trackerByFluid 键类型不同
    //        （TagKey<Fluid> vs FluidType），泛型无法同时满足两个加载器。
    @SuppressWarnings({"rawtypes", "unchecked"})
    private static EntityFluidInteraction deepCopy(EntityFluidInteraction source) {
        // LB: val sourceTrackers = source.trackerByFluid
        Map sourceTrackers = ((EntityFluidInteractionAccessor) source).epsilon$getTrackerByFluid();
        // LB: val copy = EntityFluidInteraction(sourceTrackers.keys)
        EntityFluidInteraction copy = new EntityFluidInteraction(sourceTrackers.keySet());
        // LB: val targetTrackers = copy.trackerByFluid
        Map targetTrackers = ((EntityFluidInteractionAccessor) copy).epsilon$getTrackerByFluid();

        // LB 原文：for ((fluid, sourceTracker) in sourceTrackers) { val targetTracker = targetTrackers[fluid] ?: continue; … }
        for (Object entryObject : sourceTrackers.entrySet()) {
            Map.Entry entry = (Map.Entry) entryObject;
            Object targetTrackerObject = targetTrackers.get(entry.getKey());
            if (targetTrackerObject == null) {
                continue;
            }

            EntityFluidInteractionTrackerAccessor sourceTracker =
                    (EntityFluidInteractionTrackerAccessor) entry.getValue();
            EntityFluidInteractionTrackerAccessor targetTracker =
                    (EntityFluidInteractionTrackerAccessor) targetTrackerObject;

            targetTracker.epsilon$setTrackerHeight(sourceTracker.epsilon$getTrackerHeight());
            targetTracker.epsilon$setEyesInside(sourceTracker.epsilon$isEyesInside());
            targetTracker.epsilon$setAccumulatedCurrent(sourceTracker.epsilon$getAccumulatedCurrent());
            targetTracker.epsilon$setCurrentCount(sourceTracker.epsilon$getCurrentCount());
        }

        return copy;
    }

    public static SimulatedPlayer fromClientPlayer(SimulatedPlayerInput input) {
        // LB 原文使用全局 player，Epsilon 侧为 Constants.mc.player
        Player player = Constants.mc.player;
        return new SimulatedPlayer(
                player,
                input,
                player.position(),
                player.getDeltaMovement(),
                player.getBoundingBox(),
                player.getYRot(),
                player.getXRot(),

                player.isSprinting(),

                player.fallDistance,
                // LB: player.noJumpDelay（AW: accessible/mutable field LivingEntity noJumpDelay I）
                player.noJumpDelay,
                player.isJumping(),
                player.isFallFlying(),
                player.onGround(),
                player.horizontalCollision,
                player.verticalCollision,

                player.isInWater(),
                player.isSwimming(),
                player.isUnderWater(),
                deepCopy(player.fluidInteraction)
        );
    }

    public static SimulatedPlayer fromOtherPlayer(Player player, SimulatedPlayerInput input) {
        return new SimulatedPlayer(
                player,
                input,
                player.position(),
                // LB: player.position().subtract(player.lastPos)（lastPos = Vec3(xo, yo, zo) ≡ Entity.oldPosition()）
                player.position().subtract(player.oldPosition()),
                player.getBoundingBox(),
                player.getYRot(),
                player.getXRot(),

                player.isSprinting(),

                player.fallDistance,
                // LB: player.noJumpDelay（AW: accessible/mutable field LivingEntity noJumpDelay I）
                player.noJumpDelay,
                player.isJumping(),
                player.isFallFlying(),
                player.onGround(),
                player.horizontalCollision,
                player.verticalCollision,

                player.isInWater(),
                player.isSwimming(),
                player.isUnderWater(),
                deepCopy(player.fluidInteraction)
        );
    }

    private int simulatedTicks = 0;
    private boolean clipLedged = false;

    public boolean isClipLedged() {
        return this.clipLedged;
    }

    @Override
    public Vec3 getPos() {
        return this.pos;
    }

    public Vec3 getDeltaMovement() {
        return this.deltaMovement;
    }

    @Override
    public void tick() {
        this.clipLedged = false;

        if (this.pos.y <= -70) {
            return;
        }

        this.input.update();

        this.updateFluidInteraction();
        this.updateIsUnderwater();
        this.updateSwimming();

        if (this.jumpTriggerTime > 0) {
            this.jumpTriggerTime--;
        }

        this.jumping = this.input.keyPresses.jump();

        Vec3 movement = this.deltaMovement;

        double motionX = movement.x;
        double motionY = movement.y;
        double motionZ = movement.z;

        if (Math.abs(movement.x) < 0.003) {
            motionX = 0.0;
        }
        if (Math.abs(movement.y) < 0.003) {
            motionY = 0.0;
        }
        if (Math.abs(movement.z) < 0.003) {
            motionZ = 0.0;
        }
        if (this.onGround) {
            this.fallFlying = false;
        }

        this.deltaMovement = new Vec3(motionX, motionY, motionZ);

        if (this.jumping) {
            double fluidHeight = this.isInLava() ? this.getFluidHeight(FluidTags.LAVA) : this.getFluidHeight(FluidTags.WATER);
            boolean inWater = this.isInWater() && fluidHeight > 0.0;

            double swimHeight = this.getFluidJumpThreshold();

            if (inWater && (!this.onGround || fluidHeight > swimHeight)) {
                this.swimUpward(FluidTags.WATER);
            } else if (this.isInLava() && (!this.onGround || fluidHeight > swimHeight)) {
                this.swimUpward(FluidTags.LAVA);
            } else if ((this.onGround || inWater && fluidHeight <= swimHeight) && this.jumpTriggerTime == 0) {
                this.jumpFromGround();
                this.jumpTriggerTime = 10;
            }
        }

        double sidewaysSpeed = this.input.getMovementSideways() * 0.98;
        double forwardSpeed = this.input.getMovementForward() * 0.98;
        double upwardsSpeed = 0.0;

        if (this.hasStatusEffect(MobEffects.SLOW_FALLING) || this.hasStatusEffect(MobEffects.LEVITATION)) {
            this.onLanding();
        }

        this.travel(new Vec3(sidewaysSpeed, upwardsSpeed, forwardSpeed));
        this.simulatedTicks++;
    }

    private void travel(Vec3 movementInput) {
        if (this.isSwimming && !this.player.isPassenger()) {
            double viewY = this.getViewVector().y;
            double swimLift = viewY < -0.2 ? 0.085 : 0.06;
            if (viewY <= 0.0 || this.input.keyPresses.jump() || !this.player.level()
                    .getBlockState(BlockPos.containing(this.pos.x, this.pos.y + 1.0 - 0.1, this.pos.z))
                    .getFluidState().isEmpty()
            ) {
                this.deltaMovement = this.deltaMovement.add(0.0, (viewY - this.deltaMovement.y) * swimLift, 0.0);
            }
        }

        double beforeTravelVelocityY = this.deltaMovement.y;

        double gravity = 0.08;
        boolean isFalling = this.deltaMovement.y <= 0.0;
        if (this.deltaMovement.y <= 0.0 && this.hasStatusEffect(MobEffects.SLOW_FALLING)) {
            gravity = 0.01;
            this.onLanding();
        }

        if (this.isInWater() && this.player.isAffectedByFluids()) {
            double playerY = this.pos.y;
            float movementSpeed = this.isSprinting ? 0.9f : 0.8f;
            float movementEfficiency = 0.02f;
            float waterMovementEfficiency = (float) this.getAttributeValue(Attributes.WATER_MOVEMENT_EFFICIENCY);

            if (!this.onGround) {
                waterMovementEfficiency *= 0.5f;
            }
            if (waterMovementEfficiency > 0.0f) {
                movementSpeed += (0.54600006f - movementSpeed) * waterMovementEfficiency / 3.0f;
                movementEfficiency += (this.getSpeed() - movementEfficiency) * waterMovementEfficiency / 3.0f;
            }
            if (this.hasStatusEffect(MobEffects.DOLPHINS_GRACE)) {
                movementSpeed = 0.96f;
            }
            this.updateVelocity(movementEfficiency, movementInput);
            this.move(this.deltaMovement);
            Vec3 movement = this.deltaMovement;
            if (this.horizontalCollision && this.isClimbing()) {
                movement = new Vec3(movement.x, 0.2, movement.z);
            }
            this.deltaMovement = movement.multiply((double) movementSpeed, 0.8, (double) movementSpeed);
            Vec3 adjustedMovement = this.player.getFluidFallingAdjustedMovement(gravity, isFalling, this.deltaMovement);
            this.deltaMovement = adjustedMovement;
            if (this.horizontalCollision && this.doesNotCollide(
                    adjustedMovement.x,
                    adjustedMovement.y + 0.6 - this.pos.y + playerY,
                    adjustedMovement.z
            )) {
                this.deltaMovement = new Vec3(adjustedMovement.x, 0.3, adjustedMovement.z);
            }
        } else if (this.isInLava() && this.player.isAffectedByFluids()) {
            double playerY = this.pos.y;
            this.updateVelocity(0.02f, movementInput);
            this.move(this.deltaMovement);
            if (this.getFluidHeight(FluidTags.LAVA) <= this.getFluidJumpThreshold()) {
                this.deltaMovement = this.deltaMovement.multiply(0.5, 0.8, 0.5);
                this.deltaMovement = this.player.getFluidFallingAdjustedMovement(gravity, isFalling, this.deltaMovement);
            } else {
                this.deltaMovement = this.deltaMovement.scale(0.5);
            }
            if (!this.player.isNoGravity()) {
                this.deltaMovement = this.deltaMovement.add(0.0, -gravity / 4.0, 0.0);
            }
            if (this.horizontalCollision && this.doesNotCollide(
                    this.deltaMovement.x,
                    this.deltaMovement.y + 0.6 - this.pos.y + playerY,
                    this.deltaMovement.z
            )) {
                this.deltaMovement = new Vec3(this.deltaMovement.x, 0.3, this.deltaMovement.z);
            }
        } else if (this.fallFlying) {
            double lift;
            Vec3 velocity = this.deltaMovement;
            if (velocity.y > -0.5) {
                this.fallDistance = 1.0;
            }
            Vec3 vec3d3 = this.getViewVector();
            float pitchRadians = this.xRot * (((float) Math.PI) / 180);
            double horizontalViewMagnitude = Math.sqrt(vec3d3.x * vec3d3.x + vec3d3.z * vec3d3.z);
            double horizontalVelocity = velocity.horizontalDistance();
            double viewVectorLength = vec3d3.length();
            float liftFactor = fastCos(pitchRadians);
            liftFactor =
                    (float) ((double) liftFactor * ((double) liftFactor * Math.min(1.0, viewVectorLength / 0.4)));
            velocity = this.deltaMovement.add(0.0, gravity * (-1.0 + (double) liftFactor * 0.75), 0.0);
            if (velocity.y < 0.0 && horizontalViewMagnitude > 0.0) {
                lift = velocity.y * -0.1 * (double) liftFactor;
                velocity = velocity.add(
                        vec3d3.x * lift / horizontalViewMagnitude,
                        lift,
                        vec3d3.z * lift / horizontalViewMagnitude
                );
            }
            if (pitchRadians < 0.0f && horizontalViewMagnitude > 0.0) {
                lift = horizontalVelocity * (double) (-fastSin(pitchRadians)) * 0.04;
                velocity = velocity.add(
                        -vec3d3.x * lift / horizontalViewMagnitude,
                        lift * 3.2,
                        -vec3d3.z * lift / horizontalViewMagnitude
                );
            }
            if (horizontalViewMagnitude > 0.0) {
                velocity = velocity.add(
                        (vec3d3.x / horizontalViewMagnitude * horizontalVelocity - velocity.x) * 0.1,
                        0.0,
                        (vec3d3.z / horizontalViewMagnitude * horizontalVelocity - velocity.z) * 0.1
                );
            }
            this.deltaMovement = velocity.multiply(0.99, 0.98, 0.99);

            this.move(this.deltaMovement);
        } else {
            BlockPos blockPos = this.getBlockPosBelowThatAffectsMyMovement();
            float p = this.player.level().getBlockState(blockPos).getBlock().getFriction();
            float friction = this.onGround ? p * 0.91f : 0.91f;
            Vec3 movement = this.applyMovementInput(movementInput, p);
            double verticalMovement = movement.y;
            if (this.hasStatusEffect(MobEffects.LEVITATION)) {
                verticalMovement += (0.05 * (double) (this.getStatusEffect(MobEffects.LEVITATION).getAmplifier() + 1) - movement.y) * 0.2;
            } else if (this.player.level().isClientSide() && !this.player.level().hasChunkAt(blockPos.getX(), blockPos.getZ())) {
                verticalMovement = this.pos.y > (double) this.player.level().getMinY() ? -0.1 : 0.0;
            } else if (!this.player.isNoGravity()) {
                verticalMovement -= gravity;
            }

            this.deltaMovement = this.player.shouldDiscardFriction()
                    ? new Vec3(movement.x, verticalMovement, movement.z)
                    : new Vec3(
                            movement.x * (double) friction,
                            verticalMovement * 0.9800000190734863,
                            movement.z * (double) friction
                    );
        }

        if (this.player.getAbilities().flying && !this.player.isPassenger()) {
            this.deltaMovement = new Vec3(this.deltaMovement.x, beforeTravelVelocityY * 0.6, this.deltaMovement.z);
            this.onLanding();
        }
    }

    /**
     * @see net.minecraft.world.entity.LivingEntity#handleRelativeFrictionAndCalculateMovement(Vec3, float)
     */
    private Vec3 applyMovementInput(Vec3 movementInput, float slipperiness) {
        this.updateVelocity(this.getFrictionInfluencedSpeed(slipperiness), movementInput);
        this.deltaMovement = this.handleOnClimbable(this.deltaMovement);
        this.deltaMovement = this.applyWebSpeed(this.deltaMovement);
        this.move(this.deltaMovement);


        Vec3 vec3d = this.deltaMovement;
        if ((this.horizontalCollision || this.jumping) && (
                this.isClimbing() || isPowderSnow(VectorUtils.toBlockPos(this.pos))
                        && PowderSnowBlock.canEntityWalkOnPowderSnow(this.player)
        )
        ) {
            vec3d = new Vec3(vec3d.x, 0.2, vec3d.z);
        }

        return vec3d;
    }

    // LB 原文: pos.toBlockPos().state?.`is`(Blocks.POWDER_SNOW) == true
    private static boolean isPowderSnow(BlockPos blockPos) {
        BlockState state = BlockUtils.state(blockPos);
        return state != null && state.is(Blocks.POWDER_SNOW);
    }

    private void updateVelocity(float speed, Vec3 movementInput) {
        // LB: Entity.getInputVector(movementInput, speed, this.yRot)（LB 用 AW 打开 protected static）
        Vec3 vec3d = getInputVector(movementInput, speed, this.yRot);

        this.deltaMovement = this.deltaMovement.add(vec3d);
    }

    // [适配] LB 调用 Entity.getInputVector(Vec3, float, float)（protected static，LB 用 AW 打开）；
    //        Epsilon 无对应 AW → 就地复刻 26.2 原版实现，签名与语义一致。
    private static Vec3 getInputVector(Vec3 relative, float motionScaler, float facing) {
        double d = relative.lengthSqr();
        if (d < 1.0E-7) {
            return Vec3.ZERO;
        }
        Vec3 vec3 = (d > 1.0 ? relative.normalize() : relative).scale((double) motionScaler);
        float f = Mth.sin((double) (facing * 0.017453292f));
        float g = Mth.cos((double) (facing * 0.017453292f));
        return new Vec3(vec3.x * (double) g - vec3.z * (double) f, vec3.y, vec3.z * (double) g + vec3.x * (double) f);
    }

    /**
     * @see net.minecraft.world.entity.LivingEntity#getFrictionInfluencedSpeed(float)
     */
    private float getFrictionInfluencedSpeed(float slipperiness) {
        return this.onGround
                ? this.getSpeed() * (0.21600002f / (slipperiness * slipperiness * slipperiness))
                : this.getAirStrafingSpeed();
    }

    private float getAirStrafingSpeed() {
        float speed = 0.02f;

        if (this.input.sprinting) {
            return (float) ((double) speed + 0.005999999865889549);
        }

        return speed;
    }

    private float getSpeed() {
        return 0.10000000149011612f;
    }

    private void move(Vec3 input) {
        // [适配] 纯客户端模拟，不派发事件（Epsilon 无对应事件总线语义）
        // LB 原文：val event = callEvent(PlayerMoveEvent(MoverType.SELF, input)); val movement = event.movement
        Vec3 movement = input;

        Vec3 backedOffMovement = this.maybeBackOffFromEdge(movement);
        Vec3 adjustedMovement = this.adjustMovementForCollisions(backedOffMovement);

        if (adjustedMovement.lengthSqr() > 1.0E-7) {
            this.pos = this.pos.add(adjustedMovement);
            // [适配] LB: player.dimensions.makeBoundingBox(this.pos)（Entity.dimensions 为 private，LB 用 AW 打开）
            //        Epsilon 无访问器 → 用公开的 getDimensions(Pose)（STANDING + scale=1 时等价）
            this.boundingBox = this.player.getDimensions(this.player.getPose()).makeBoundingBox(this.pos);
        }

        boolean xCollision = !Mth.equal(backedOffMovement.x, adjustedMovement.x);
        boolean zCollision = !Mth.equal(backedOffMovement.z, adjustedMovement.z);

        this.horizontalCollision = xCollision || zCollision;
        this.verticalCollision = backedOffMovement.y != adjustedMovement.y;

        this.onGround = this.verticalCollision && backedOffMovement.y < 0.0;

        if (!this.isInWater()) {
            this.updateFluidInteraction();
        }

        if (this.onGround) {
            this.onLanding();
        } else if (backedOffMovement.y < 0) {
            this.fallDistance -= (float) backedOffMovement.y;
        }

        Vec3 vec3d2 = this.deltaMovement;
        if (this.horizontalCollision || this.verticalCollision) {
            this.deltaMovement = new Vec3(
                    xCollision ? 0.0 : vec3d2.x,
                    this.onGround ? 0.0 : vec3d2.y,
                    zCollision ? 0.0 : vec3d2.z
            );
        }
    }

    private Vec3 adjustMovementForCollisions(Vec3 movement) {
        boolean onGroundOrFalling;
        AABB collisionBox = new AABB(-0.3, 0.0, -0.3, 0.3, 1.8, 0.3).move(this.pos);

        List<VoxelShape> entityCollisionList = List.of();

        Vec3 adjustedMovement = movement.lengthSqr() == 0.0
                ? movement
                : Entity.collideBoundingBox(
                        this.player,
                        movement,
                        collisionBox,
                        this.player.level(),
                        entityCollisionList
                );
        boolean collidedX = movement.x != adjustedMovement.x;
        boolean collidedY = movement.y != adjustedMovement.y;
        boolean collidedZ = movement.z != adjustedMovement.z;

        onGroundOrFalling = this.onGround || collidedY && movement.y < 0.0;

        if (this.player.maxUpStep() > 0.0f && onGroundOrFalling && (collidedX || collidedZ)) {
            Vec3 steppedMovement = Entity.collideBoundingBox(
                    this.player,
                    new Vec3(movement.x, (double) this.player.maxUpStep(), movement.z),
                    collisionBox,
                    this.player.level(),
                    entityCollisionList
            );
            Vec3 stepUpMovement = Entity.collideBoundingBox(
                    this.player,
                    new Vec3(0.0, (double) this.player.maxUpStep(), 0.0),
                    collisionBox.expandTowards(movement.x, 0.0, movement.z),
                    this.player.level(),
                    entityCollisionList
            );
            Vec3 stepDownMovement = Entity.collideBoundingBox(
                    this.player,
                    new Vec3(movement.x, 0.0, movement.z),
                    collisionBox.move(stepUpMovement),
                    this.player.level(),
                    entityCollisionList
            ).add(stepUpMovement);

            if (stepUpMovement.y < (double) this.player.maxUpStep()
                    && stepDownMovement.horizontalDistanceSqr() > steppedMovement.horizontalDistanceSqr()
            ) {
                steppedMovement = stepDownMovement;
            }

            if (steppedMovement.horizontalDistanceSqr() > adjustedMovement.horizontalDistanceSqr()) {
                return steppedMovement.add(
                        Entity.collideBoundingBox(
                                this.player,
                                new Vec3(0.0, -steppedMovement.y + movement.y, 0.0),
                                collisionBox.move(steppedMovement),
                                this.player.level(),
                                entityCollisionList
                        )
                );
            }
        }
        return adjustedMovement;
    }

    private void onLanding() {
        this.fallDistance = 0.0;
    }

    /**
     * @see net.minecraft.world.entity.LivingEntity#jumpFromGround()
     */
    public void jumpFromGround() {
        double jumpPower = (double) this.getJumpPower();
        this.deltaMovement = new Vec3(this.deltaMovement.x, Math.max(jumpPower, this.deltaMovement.y), this.deltaMovement.z);

        if (this.isSprinting) {
            float yawRadians = toRadians(this.yRot);

            this.deltaMovement = this.deltaMovement.add(
                    (double) (-fastSin(yawRadians) * 0.2f),
                    0.0,
                    (double) (fastCos(yawRadians) * 0.2f)
            );
        }

    }

    public void jump() {
        this.jumpFromGround();
    }

    /**
     * @see net.minecraft.world.entity.LivingEntity#handleOnClimbable(Vec3)
     */
    private Vec3 handleOnClimbable(Vec3 motion) {
        if (!this.isClimbing()) {
            return motion;
        }

        this.onLanding();
        double clampedX = Mth.clamp(motion.x, -0.15000000596046448, 0.15000000596046448);
        double clampedZ = Mth.clamp(motion.z, -0.15000000596046448, 0.15000000596046448);
        double clampedY = Math.max(motion.y, -0.15000000596046448);
        // LB 原文: pos.toBlockPos().state!!.`is`(Blocks.SCAFFOLDING)（!! 表示 state 可能为 null）
        if (clampedY < 0.0 && !BlockUtils.state(VectorUtils.toBlockPos(this.pos)).is(Blocks.SCAFFOLDING)
                && this.player.isSuppressingSlidingDownLadder()
        ) {
            clampedY = 0.0;
        }

        return new Vec3(clampedX, clampedY, clampedZ);
    }

    private Vec3 applyWebSpeed(Vec3 motion) {
        BlockState blockState = this.getLevel().getBlockState(VectorUtils.toBlockPos(this.pos));
        if (blockState.getBlock() != Blocks.COBWEB) {
            return motion;
        }
        Vec3 multiplier = this.hasStatusEffect(MobEffects.WEAVING)
                ? new Vec3(0.5, 0.25, 0.5)
                : new Vec3(0.25, 0.05, 0.25);
        return motion.multiply(multiplier.x, multiplier.y, multiplier.z);
    }

    private boolean isClimbing() {
        BlockPos blockPos = VectorUtils.toBlockPos(this.pos);
        // LB 原文: blockPos.state!!（!! 表示 state 可能为 null）
        BlockState blockState = BlockUtils.state(blockPos);
        if (blockState.is(BlockTags.CLIMBABLE)) {
            return true;
        } else if (blockState.getBlock() instanceof TrapDoorBlock && this.trapdoorUsableAsLadder(blockPos, blockState)) {
            return true;
        } else {
            return false;
        }
    }

    /**
     * @see net.minecraft.world.entity.LivingEntity#trapdoorUsableAsLadder(BlockPos, BlockState)
     */
    private boolean trapdoorUsableAsLadder(BlockPos pos, BlockState state) {
        if (!state.getValue(TrapDoorBlock.OPEN)) {
            return false;
        }
        BlockState blockState = this.player.level().getBlockState(pos.below());
        return blockState.is(Blocks.LADDER) && blockState.getValue(LadderBlock.FACING) == state.getValue(TrapDoorBlock.FACING);
    }

    /**
     * @see net.minecraft.world.entity.player.Player#maybeBackOffFromEdge(Vec3, MoverType)
     */
    private Vec3 maybeBackOffFromEdge(Vec3 movement) {
        Vec3 adjustedMovement = movement;

        if (adjustedMovement.y <= 0.0 && this.isAboveGround()) {
            double xMovement = adjustedMovement.x;
            double zMovement = adjustedMovement.z;
            double step = 0.05;
            while (xMovement != 0.0 && this.getLevel().noCollision(
                    this.player,
                    this.boundingBox.move(xMovement, -STEP_HEIGHT, 0.0)
            )
            ) {
                if (xMovement < step && xMovement >= -step) {
                    xMovement = 0.0;
                    continue;
                }
                if (xMovement > 0.0) {
                    xMovement -= step;
                    continue;
                }
                xMovement += step;
            }
            while (zMovement != 0.0 && this.getLevel().noCollision(
                    this.player,
                    this.boundingBox.move(0.0, -STEP_HEIGHT, zMovement)
            )
            ) {
                if (zMovement < step && zMovement >= -step) {
                    zMovement = 0.0;
                    continue;
                }
                if (zMovement > 0.0) {
                    zMovement -= step;
                    continue;
                }
                zMovement += step;
            }
            while (xMovement != 0.0 && zMovement != 0.0 && this.getLevel().noCollision(
                    this.player,
                    this.boundingBox.move(xMovement, -STEP_HEIGHT, zMovement)
            )
            ) {
                xMovement =
                        xMovement < step && xMovement >= -step ? 0.0 : xMovement > 0.0
                                ? xMovement - step
                                : xMovement + step;
                if (zMovement < step && zMovement >= -step) {
                    zMovement = 0.0;
                    continue;
                }
                if (zMovement > 0.0) {
                    zMovement -= step;
                    continue;
                }
                zMovement += step;
            }

            if (adjustedMovement.x != xMovement || adjustedMovement.z != zMovement) {
                this.clipLedged = true;
            }

            if (this.shouldClipAtLedge()) {
                adjustedMovement = new Vec3(xMovement, adjustedMovement.y, zMovement);
            }
        }
        return adjustedMovement;
    }

    private boolean shouldClipAtLedge() {
        return !this.input.ignoreClippingAtLedge && (this.input.keyPresses.shift() || this.input.forceSafeWalk);
    }

    private boolean isAboveGround() {
        return this.onGround || this.fallDistance < STEP_HEIGHT && !this.getLevel().noCollision(
                this.player,
                this.boundingBox.move(0.0, this.fallDistance - STEP_HEIGHT, 0.0)
        );
    }

    /**
     * Mirrors 26.1 {@code LivingEntity#getJumpPower()}.
     *
     * @see net.minecraft.world.entity.LivingEntity#getJumpPower
     */
    private float getJumpPower() {
        return (float) this.getAttributeValue(Attributes.JUMP_STRENGTH)
                * this.getJumpVelocityMultiplier() + this.getJumpBoostPower();
    }

    /**
     * Mirrors 26.1 {@code LivingEntity#getJumpBoostPower()}.
     *
     * @see net.minecraft.world.entity.LivingEntity#getJumpBoostPower
     */
    private float getJumpBoostPower() {
        if (this.hasStatusEffect(MobEffects.JUMP_BOOST)) {
            return 0.1f * ((float) this.getStatusEffect(MobEffects.JUMP_BOOST).getAmplifier() + 1f);
        } else {
            return 0f;
        }
    }

    /**
     * Mirrors the 26.1 block jump factor lookup used by {@code LivingEntity#getJumpPower()}.
     *
     * @see net.minecraft.world.entity.Entity#getBlockJumpFactor
     */
    private float getJumpVelocityMultiplier() {
        Block fBlock = BlockUtils.getBlock(VectorUtils.toBlockPos(this.pos));
        float f = fBlock == null ? 0f : fBlock.getJumpFactor();
        Block gBlock = BlockUtils.getBlock(this.getBlockPosBelowThatAffectsMyMovement());
        float g = gBlock == null ? 0f : gBlock.getJumpFactor();

        return (double) f == 1.0 ? g : f;
    }

    private boolean doesNotCollide(double offsetX, double offsetY, double offsetZ) {
        return this.doesNotCollide(this.boundingBox.move(offsetX, offsetY, offsetZ));
    }

    private boolean doesNotCollide(AABB box) {
        return this.player.level().noCollision(this.player, box) && !this.player.level().containsAnyLiquid(box);
    }

    private void swimUpward(TagKey<Fluid> fluid) {
        this.deltaMovement = this.deltaMovement.add(
                0.0,
                fluid == FluidTags.WATER ? 0.03999999910593033 : 0.005999999865889549,
                0.0
        );
    }

    /**
     * Mirrors 26.1 {@code Entity#getBlockPosBelowThatAffectsMyMovement()}.
     *
     * @see net.minecraft.world.entity.Entity#getBlockPosBelowThatAffectsMyMovement
     */
    private BlockPos getBlockPosBelowThatAffectsMyMovement() {
        return BlockPos.containing(this.pos.x, this.boundingBox.minY - 0.5000001, this.pos.z);
    }

    /**
     * Mirrors 26.1 {@code Entity#getFluidJumpThreshold()}.
     *
     * @see net.minecraft.world.entity.Entity#getFluidJumpThreshold
     */
    private double getFluidJumpThreshold() {
        return this.player.getEyeHeight() < 0.4 ? 0.0 : 0.4;
    }

    /**
     * Mirrors 26.1 {@code Entity#isEyeInFluid(TagKey)}, restricted to water for the underwater state.
     *
     * @see net.minecraft.world.entity.Entity#isEyeInFluid
     */
    private boolean isEyeInWater() {
        return this.fluidInteraction.isEyeInFluid(FluidTags.WATER);
    }

    private boolean isInWater() {
        return this.wasTouchingWater;
    }

    /**
     * @see net.minecraft.world.entity.Entity#isInLava
     */
    private boolean isInLava() {
        return this.fluidInteraction.isInFluid(FluidTags.LAVA);
    }

    /**
     * Mirrors 26.1 {@code Player#updateSwimming()}.
     *
     * @see net.minecraft.world.entity.player.Player#updateSwimming
     */
    private void updateSwimming() {
        this.isSwimming = this.isSwimming
                ? this.isSprinting && this.isInWater() && !this.player.isPassenger()
                : this.isSprinting && this.isSubmergedInWater()
                  && !this.player.isPassenger()
                  && this.player.level()
                          .getFluidState(VectorUtils.toBlockPos(this.pos))
                          .is(FluidTags.WATER);
    }

    /**
     * Mirrors 26.1 {@code Entity#updateFluidInteraction()}.
     *
     * @see net.minecraft.world.entity.Entity#updateFluidInteraction
     */
    private boolean updateFluidInteraction() {
        this.fluidInteraction.update(this.player, !this.player.isAffectedByFluids());

        boolean inWater = this.fluidInteraction.isInFluid(FluidTags.WATER);
        boolean inLava = this.fluidInteraction.isInFluid(FluidTags.LAVA);

        if (inWater) {
            this.onLanding();
        }

        this.wasTouchingWater = inWater;
        if (this.player.isAffectedByFluids()) {
            if (inWater) {
                this.fluidInteraction.applyCurrentTo(FluidTags.WATER, this.player, 0.014);
            }

            if (inLava) {
                double lavaFlowScale = this.getLevel().environmentAttributes()
                        .getDimensionValue(EnvironmentAttributes.FAST_LAVA)
                        ? 0.007
                        : 0.0023333333333333335;
                this.fluidInteraction.applyCurrentTo(FluidTags.LAVA, this.player, lavaFlowScale);
            }
        }

        return inWater || inLava;
    }

    /**
     * Mirrors 26.1 {@code Player#updateIsUnderwater()}.
     *
     * @see net.minecraft.world.entity.player.Player#updateIsUnderwater
     */
    private boolean updateIsUnderwater() {
        this.wasUnderwater = this.isEyeInWater();
        return this.wasUnderwater;
    }

    private boolean isSubmergedInWater() {
        return this.wasUnderwater && this.isInWater();
    }

    private double getFluidHeight(TagKey<Fluid> tags) {
        return this.fluidInteraction.getFluidHeight(tags);
    }

    /**
     * Mirrors 26.1 {@code Entity#getViewVector()}.
     *
     * @see net.minecraft.world.entity.Entity#getViewVector
     */
    private Vec3 getViewVector() {
        return this.calculateViewVector(this.xRot, this.yRot);
    }

    /**
     * Mirrors 26.1 {@code Entity#calculateViewVector(float, float)}.
     *
     * @see net.minecraft.world.entity.Entity#calculateViewVector
     */
    private Vec3 calculateViewVector(float xRot, float yRot) {
        float realXRot = xRot * (((float) Math.PI) / 180f);
        float realYRot = -yRot * (((float) Math.PI) / 180f);
        float yCos = Mth.cos((double) realYRot);
        float ySin = Mth.sin((double) realYRot);
        float xCos = Mth.cos((double) realXRot);
        float xSin = Mth.sin((double) realXRot);
        return new Vec3((double) (ySin * xCos), (double) (-xSin), (double) (yCos * xCos));
    }

    private boolean hasStatusEffect(Holder<MobEffect> effect) {
        MobEffectInstance instance = this.player.getEffect(effect);
        if (instance == null) {
            return false;
        }

        return instance.getDuration() >= this.simulatedTicks;
    }

    private MobEffectInstance getStatusEffect(Holder<MobEffect> effect) {
        MobEffectInstance instance = this.player.getEffect(effect);
        if (instance == null) {
            return null;
        }

        if (instance.getDuration() < this.simulatedTicks) {
            return null;
        }

        return instance;
    }

    public double getAttributeValue(Holder<Attribute> attribute) {
        return this.player.getAttributes().getValue(attribute);
    }

    public SimulatedPlayer clone() {
        return new SimulatedPlayer(
                this.player,
                this.input,
                this.pos,
                this.deltaMovement,
                this.boundingBox,
                this.yRot,
                this.xRot,
                this.isSprinting,
                this.fallDistance,
                this.jumpTriggerTime,
                this.jumping,
                this.fallFlying,
                this.onGround,
                this.horizontalCollision,
                this.verticalCollision,
                this.wasTouchingWater,
                this.isSwimming,
                this.wasUnderwater,
                deepCopy(this.fluidInteraction)
        );
    }

    // [适配] LB 的 Float.fastSin/fastCos（utils/math/MathExtensions.kt: Mth.sin/Mth.cos）就地实现；
    //        因 scaffold/util/VectorUtils.java 由他人持有，此处先私有复刻，命名与 LB 一致。
    private static float fastSin(float value) {
        return Mth.sin((double) value);
    }

    private static float fastCos(float value) {
        return Mth.cos((double) value);
    }

    // [适配] LB 的 Float.toRadians（utils/math/MathExtensions.kt: this * Mth.DEG_TO_RAD）
    private static float toRadians(float value) {
        return value * Mth.DEG_TO_RAD;
    }

    public static class SimulatedPlayerInput extends net.minecraft.client.player.ClientInput {

        public final DirectionalInput directionalInput;
        public boolean sprinting;
        public boolean ignoreClippingAtLedge;

        public boolean forceSafeWalk = false;

        public SimulatedPlayerInput(
                DirectionalInput directionalInput,
                boolean jumping,
                boolean sprinting,
                boolean sneaking,
                boolean ignoreClippingAtLedge
        ) {
            this.directionalInput = directionalInput;
            this.sprinting = sprinting;
            this.ignoreClippingAtLedge = ignoreClippingAtLedge;

            // LB: set(forward = ..., backward = ..., left = ..., right = ..., jump = ..., sneak = ...)
            //     第七个参数 sprint 沿用 keyPresses 原值（新建的 ClientInput → false）
            this.set(
                    directionalInput.forwards,
                    directionalInput.backwards,
                    directionalInput.left,
                    directionalInput.right,
                    jumping,
                    sneaking,
                    false
            );
        }

        public SimulatedPlayerInput(DirectionalInput directionalInput, boolean jumping, boolean sprinting, boolean sneaking) {
            this(directionalInput, jumping, sprinting, sneaking, false);
        }

        // LB: inline fun ClientInput.set(...) —— this.keyPresses = Input(forward, backward, left, right, jump, sneak, sprint)
        public void set(boolean forward, boolean backward, boolean left, boolean right, boolean jump, boolean sneak, boolean sprint) {
            this.keyPresses = new net.minecraft.world.entity.player.Input(forward, backward, left, right, jump, sneak, sprint);
        }

        // LB: set(jump = false, sneak = false)（其余保持 keyPresses 原值）
        public void set(boolean jump, boolean sneak) {
            this.set(
                    this.keyPresses.forward(),
                    this.keyPresses.backward(),
                    this.keyPresses.left(),
                    this.keyPresses.right(),
                    jump,
                    sneak,
                    this.keyPresses.sprint()
            );
        }

        // LB 扩展属性 ClientInput.movementForward（= moveVector.y）
        public float getMovementForward() {
            return this.getMoveVector().y;
        }

        // LB 扩展属性 ClientInput.movementSideways（= moveVector.x）
        public float getMovementSideways() {
            return this.getMoveVector().x;
        }

        public void update() {
            if (this.keyPresses.forward() != this.keyPresses.backward()) {
                this.moveVector = new net.minecraft.world.phys.Vec2(this.moveVector.x, this.keyPresses.forward() ? 1.0f : -1.0f);
            } else {
                this.moveVector = new net.minecraft.world.phys.Vec2(this.moveVector.x, 0.0f);
            }

            this.moveVector = new net.minecraft.world.phys.Vec2(
                    this.keyPresses.left() == this.keyPresses.right()
                            ? 0.0f
                            : this.keyPresses.left() ? 1.0f : -1.0f,
                    this.moveVector.y
            );

            if (this.keyPresses.shift()) {
                this.moveVector = new net.minecraft.world.phys.Vec2(
                        (float) ((double) this.moveVector.x * 0.3),
                        (float) ((double) this.moveVector.y * 0.3)
                );
            }
        }

        @Override
        public String toString() {
            return "SimulatedPlayerInput(forwards={" + this.keyPresses.forward() + "}, backwards={" + this.keyPresses.backward()
                    + "}, left={" + this.keyPresses.left() + "}, right={" + this.keyPresses.right()
                    + "}, jumping={" + this.keyPresses.jump() + "}, sprinting=" + this.sprinting
                    + ", slowDown=" + this.keyPresses.shift() + ")";
        }

        private static final double MAX_WALKING_SPEED = 0.121;

        public static SimulatedPlayerInput fromClientPlayer(DirectionalInput directionalInput, boolean jump, boolean sprinting, boolean sneaking) {
            SimulatedPlayerInput input = new SimulatedPlayerInput(
                    directionalInput,
                    jump,
                    sprinting,
                    sneaking
            );

            // [适配] 纯客户端模拟，不派发事件（Epsilon 无对应事件总线语义）
            // LB 原文：val safeWalkEvent = PlayerSafeWalkEvent(); callEvent(safeWalkEvent);
            //          if (safeWalkEvent.isSafeWalk) input.forceSafeWalk = true

            return input;
        }

        public static SimulatedPlayerInput fromClientPlayer(DirectionalInput directionalInput) {
            // LB 原文使用全局 player（LocalPlayer），Epsilon 侧为 Constants.mc.player
            LocalPlayer player = Constants.mc.player;
            return fromClientPlayer(
                    directionalInput,
                    player.input.keyPresses.jump(),
                    player.isSprinting(),
                    player.isShiftKeyDown()
            );
        }

        /**
         * Guesses the current input of a server player based on player position and velocity
         */
        public static SimulatedPlayerInput guessInput(Player entity) {
            // LB: entity.position().subtract(entity.lastPos)（lastPos = Vec3(xo, yo, zo) ≡ Entity.oldPosition()）
            Vec3 velocity = entity.position().subtract(entity.oldPosition());

            double horizontalVelocity = velocity.horizontalDistanceSqr();

            boolean sprinting = horizontalVelocity >= MAX_WALKING_SPEED * MAX_WALKING_SPEED;

            DirectionalInput input = horizontalVelocity > 0.05 * 0.05
                    ? MovementUtils.getDirectionalInputForDegrees(
                            DirectionalInput.NONE,
                            Mth.wrapDegrees(MovementUtils.getDegreesRelativeToView(velocity, entity.getYRot()))
                    )
                    : DirectionalInput.NONE;

            boolean jumping = !entity.onGround();

            return new SimulatedPlayerInput(
                    input,
                    jumping,
                    sprinting,
                    entity.isShiftKeyDown()
            );
        }

    }

}
