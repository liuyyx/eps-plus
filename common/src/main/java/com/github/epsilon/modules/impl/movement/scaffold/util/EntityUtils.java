/*
 * This file is part of Epsilon.
 *
 * 逐行照搬自 LiquidBounce (https://github.com/CCBlueX/LiquidBounce) 的
 * utils/entity/EntityExtensions.kt（神桥/模拟路径用到的部分）与 utils/entity/InputExtensions.kt。
 *
 * LiquidBounce 是自由软件：你可以依据自由软件基金会发布的 GNU 通用公共许可证
 * （许可证第 3 版或你选择的任何更高版本）条款重新分发和/或修改它。
 */
package com.github.epsilon.modules.impl.movement.scaffold.util;

import com.github.epsilon.modules.impl.movement.scaffold.rotation.Rotation;
import com.github.epsilon.modules.impl.movement.scaffold.simulation.DirectionalInput;
import com.github.epsilon.modules.impl.movement.scaffold.simulation.SimulatedPlayer;
import net.minecraft.client.player.ClientInput;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Input;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec2;
import net.minecraft.world.phys.Vec3;

import static com.github.epsilon.Constants.mc;

/**
 * LB {@code utils/entity/EntityExtensions.kt} 的逐行移植（只取神桥路径与客户端模拟相关的部分）
 * 加上 {@code utils/entity/InputExtensions.kt} 全部内容。
 *
 * <p>不搬（映射表登记理由，全部为路径外）：爆炸伤害/血量/护甲链（getEffectiveDamage、
 * getExplosionDamageFromEntity、getDamageFromExplosion、getExposureToExplosion）、
 * 盾牌格挡（blockedByShield、getBlockedDamage、isBlockingServerside、wouldBlockHit）、
 * 战斗距离（box、boxedDistanceTo、squaredBoxedDistanceTo）、相机距离（cameraDistance*）、
 * 世界边界、下界坐标、ping、GameType.shortName、canStep、warp、isInHole/isBurrowed/getFeetBlockPos、
 * 岩浆/台阶免疫、以及依赖 LB Mixin 的 ClientInput.movementForward/movementSideways 写入端。
 */
public final class EntityUtils {

    /** [适配] LB 的 LocalPlayer.airTicks/onGroundTicks 由 MixinLocalPlayer 实例字段提供。 */
    private static int airTicks;
    private static int onGroundTicks;
    private static LocalPlayer groundStatePlayer;

    private EntityUtils() {
    }

    // Copied from 1.21.4
    public static boolean isInsideWaterOrBubbleColumn(Entity self) {
        return self.isInWater() || self.getInBlockState().is(Blocks.BUBBLE_COLUMN);
    }

    public static float movementForward(ClientInput self) {
        return self.getMoveVector().y;
    }

    public static float movementSideways(ClientInput self) {
        return self.getMoveVector().x;
    }

    public static ItemStack[] handItems(LivingEntity self) {
        return new ItemStack[]{self.getMainHandItem(), self.getOffhandItem()};
    }

    // Copied from 1.21.4 END

    public static boolean moving(LocalPlayer self) {
        return !self.input.getMoveVector().equals(Vec2.ZERO);
    }

    /**
     * [适配] LB 在 {@code MixinLocalPlayer.hookGroundAirTimeCounters}（{@code LocalPlayer.move} 的 RETURN）
     * 里递增这两个计数器；Epsilon 无对应 Mixin，改为本地计数：
     *
     * <pre>
     * if (this.onGround()) { onGroundTicks++; airTicks = 0; } else { airTicks++; onGroundTicks = 0; }
     * </pre>
     *
     * <p>[待接线] 阶段 7 必须每 tick 调用一次本方法（建议 PlayerTickEvent.Post / ClientTickEvent.Pre），
     * 否则 airTicks/onGroundTicks 恒为 0。
     */
    public static void tickGroundState(LocalPlayer player) {
        if (groundStatePlayer != player) {
            groundStatePlayer = player;
            airTicks = 0;
            onGroundTicks = 0;
        }

        if (player.onGround()) {
            onGroundTicks++;
            airTicks = 0;
        } else {
            airTicks++;
            onGroundTicks = 0;
        }
    }

    public static int airTicks(LocalPlayer self) {
        return airTicks;
    }

    public static int onGroundTicks(LocalPlayer self) {
        return onGroundTicks;
    }

    public static Vec3 lastRenderPos(Entity self) {
        return new Vec3(self.xOld, self.yOld, self.zOld);
    }

    public static boolean wouldBeCloseToFallOff(Player self, Vec3 position) {
        // [适配] LB：this.dimensions（无参）；26.2 只有 getDimensions(Pose)
        AABB hitbox = self.getDimensions(self.getPose())
                .makeBoundingBox(position)
                .inflate(-0.05, 0.0, -0.05)
                .move(0.0, self.fallDistance - self.maxUpStep(), 0.0);

        return self.level().noCollision(self, hitbox);
    }

    public static boolean isCloseToEdge(LocalPlayer self) {
        return isCloseToEdge(self, new DirectionalInput(self.input), 0.1, self.position());
    }

    public static boolean isCloseToEdge(LocalPlayer self, DirectionalInput directionalInput) {
        return isCloseToEdge(self, directionalInput, 0.1, self.position());
    }

    /** LB 的具名实参调用 {@code isCloseToEdge(distance = x)} 的等价重载。 */
    public static boolean isCloseToEdge(LocalPlayer self, double distance) {
        return isCloseToEdge(self, new DirectionalInput(self.input), distance, self.position());
    }

    public static boolean isCloseToEdge(LocalPlayer self, DirectionalInput directionalInput, double distance) {
        return isCloseToEdge(self, directionalInput, distance, self.position());
    }

    public static boolean isCloseToEdge(LocalPlayer self, DirectionalInput directionalInput, double distance, Vec3 pos) {
        SimulatedPlayer.SimulatedPlayerInput simulatedInput =
                SimulatedPlayer.SimulatedPlayerInput.fromClientPlayer(directionalInput);
        simulatedInput.set(
                false,
                false
        );

        SimulatedPlayer simulatedPlayer = SimulatedPlayer.fromClientPlayer(
                simulatedInput
        );

        simulatedPlayer.pos = pos;
        simulatedPlayer.tick();

        Vec3 nextVelocity = simulatedPlayer.getDeltaMovement();
        Vec3 direction;
        if (nextVelocity.horizontalDistanceSqr() > 0.003 * 0.003) {
            direction = VectorUtils.copy(nextVelocity, nextVelocity.x, 0.0, nextVelocity.z).normalize();
        } else {
            float movementYaw = MovementUtils.getMovementDirectionOfInput(self, directionalInput);
            direction = Vec3.directionFromRotation(0.0F, movementYaw);
        }

        Vec3 from = pos.add(0.0, -0.1, 0.0);
        Vec3 to = VectorUtils.fma(from, distance, direction);

        if (MovementUtils.findEdgeCollision(from, to) != null) {
            return true;
        }

        Vec3 playerPosInTwoTicks =
                simulatedPlayer.pos.add(VectorUtils.copy(nextVelocity, nextVelocity.x, 0.0, nextVelocity.z));

        return wouldBeCloseToFallOff(self, pos) || wouldBeCloseToFallOff(self, playerPosInTwoTicks);
    }

    public static double horizontalSpeed(Entity self) {
        return self.getDeltaMovement().horizontalDistance();
    }

    public static Vec3 withStrafe(Vec3 self) {
        return withStrafe(self, self.horizontalDistance(), 1.0, new DirectionalInput(mc.player.input));
    }

    public static Vec3 withStrafe(Vec3 self, double speed) {
        return withStrafe(self, speed, 1.0, new DirectionalInput(mc.player.input));
    }

    public static Vec3 withStrafe(Vec3 self, double speed, double strength) {
        return withStrafe(self, speed, strength, new DirectionalInput(mc.player.input));
    }

    public static Vec3 withStrafe(Vec3 self, double speed, double strength, DirectionalInput input) {
        return withStrafe(
                self,
                speed,
                strength,
                input,
                MovementUtils.getMovementDirectionOfInput(
                        mc.player,
                        input != null ? input : new DirectionalInput(mc.player.input)
                )
        );
    }

    public static Vec3 withStrafe(Vec3 self, double speed, double strength, DirectionalInput input, float yaw) {
        if (input != null && !input.isMoving()) {
            return new Vec3(0.0, self.y, 0.0);
        }

        double oneMinusStrength = 1.0 - strength;
        double prevX = self.x * oneMinusStrength;
        double prevZ = self.z * oneMinusStrength;
        double usedSpeed = speed * strength;

        double angle = Math.toRadians(yaw);
        double newX = prevX - Math.sin(angle) * usedSpeed;
        double newZ = prevZ + Math.cos(angle) * usedSpeed;

        return new Vec3(newX, self.y, newZ);
    }

    public static Vec3 lastPos(Entity self) {
        return new Vec3(self.xo, self.yo, self.zo);
    }

    public static Rotation rotation(Entity self) {
        return new Rotation(self.getYRot(), self.getXRot(), true);
    }

    public static Rotation lastRotation(LocalPlayer self) {
        // [适配] LB 用 yRotLast/xRotLast；26.2 对应字段名为 yRotO/xRotO（上一 tick 朝向，语义相同）
        return new Rotation(self.yRotO, self.xRotO, true);
    }

    public static Vec3 interpolateCurrentPosition(Entity self, float tickDelta) {
        if (self.tickCount == 0) {
            return self.position();
        }

        double delta = tickDelta;

        return new Vec3(
                Math.fma(delta, self.getX() - self.xOld, self.xOld),
                Math.fma(delta, self.getY() - self.yOld, self.yOld),
                Math.fma(delta, self.getZ() - self.zOld, self.zOld)
        );
    }

    public static Rotation interpolateCurrentRotation(Entity self, float tickDelta) {
        if (self.tickCount == 0) {
            return rotation(self);
        }

        return new Rotation(
                Math.fma(tickDelta, self.getYRot() - self.yRotO, self.yRotO),
                Math.fma(tickDelta, self.getXRot() - self.xRotO, self.xRotO)
        );
    }

    public static AABB getBoundingBoxAt(Entity self, Vec3 pos) {
        return self.getBoundingBox().move(pos.subtract(self.position()));
    }

    /**
     * 检查实体包围盒下方是否与任何东西碰撞。
     */
    public static boolean doesNotCollideBelow(Entity self) {
        return doesNotCollideBelow(self, -64.0);
    }

    public static boolean doesNotCollideBelow(Entity self, double until) {
        if (self.getY() < until || self.getBoundingBox().minY < until) {
            return true;
        }

        AABB offsetBb = self.getBoundingBox().setMinY(until);
        return BlockUtils.allEmpty(self.level().getBlockCollisions(self, offsetBb));
    }

    /**
     * 检查实体包围盒在给定 pos 处是否与世界中的任何方块碰撞。
     */
    public static boolean doesCollideAt(Entity self) {
        return doesCollideAt(self, self.position());
    }

    public static boolean doesCollideAt(Entity self, Vec3 pos) {
        return !BlockUtils.allEmpty(self.level().getBlockCollisions(self, getBoundingBoxAt(self, pos)));
    }

    /**
     * 依据给定位置与包围盒，判断实体是否可能掉入虚空。
     */
    public static boolean wouldFallIntoVoid(Entity self, Vec3 pos) {
        return wouldFallIntoVoid(self, pos, -64.0, 0.0);
    }

    public static boolean wouldFallIntoVoid(Entity self, Vec3 pos, double voidLevel) {
        return wouldFallIntoVoid(self, pos, voidLevel, 0.0);
    }

    public static boolean wouldFallIntoVoid(Entity self, Vec3 pos, double voidLevel, double safetyExpand) {
        AABB offsetBb = getBoundingBoxAt(self, pos);

        if (pos.y < voidLevel || offsetBb.minY < voidLevel) {
            return true;
        }

        // 若到虚空阈值之间没有任何碰撞，我们不想直接传送下去。
        AABB boundingBox = offsetBb
                // 把最小 Y 设为虚空阈值，以检测玩家下方的碰撞
                .setMinY(voidLevel)
                // 扩大包围盒，检查是否有可以安全落脚的方块
                .inflate(safetyExpand, 0.0, safetyExpand);
        return BlockUtils.allEmpty(self.level().getBlockCollisions(self, boundingBox));
    }

    // ------------------------------------------------ LB utils/entity/InputExtensions.kt

    public static boolean anyHorizontal(Input self) {
        return self.forward() || self.backward() || self.left() || self.right();
    }

    public static Input copy(Input self) {
        return copy(
                self,
                self.forward(),
                self.backward(),
                self.left(),
                self.right(),
                self.jump(),
                self.shift(),
                self.sprint()
        );
    }

    public static Input copy(
            Input self,
            boolean forward,
            boolean backward,
            boolean left,
            boolean right,
            boolean jump,
            boolean sneak,
            boolean sprint
    ) {
        return new Input(
                forward,
                backward,
                left,
                right,
                jump,
                sneak,
                sprint
        );
    }

    public static void set(ClientInput self) {
        set(
                self,
                self.keyPresses.forward(),
                self.keyPresses.backward(),
                self.keyPresses.left(),
                self.keyPresses.right(),
                self.keyPresses.jump(),
                self.keyPresses.shift(),
                self.keyPresses.sprint()
        );
    }

    public static void set(
            ClientInput self,
            boolean forward,
            boolean backward,
            boolean left,
            boolean right,
            boolean jump,
            boolean sneak,
            boolean sprint
    ) {
        self.keyPresses = new Input(
                forward,
                backward,
                left,
                right,
                jump,
                sneak,
                sprint
        );
    }

}
