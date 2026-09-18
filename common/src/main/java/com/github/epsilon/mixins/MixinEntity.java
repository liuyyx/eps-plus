package com.github.epsilon.mixins;

import com.github.epsilon.events.bus.EventBus;
import com.github.epsilon.events.impl.RaytraceEvent;
import com.github.epsilon.events.impl.StrafeEvent;
import com.github.epsilon.modules.impl.movement.Velocity;
import com.github.epsilon.modules.impl.render.FreeCamera;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.phys.Vec3;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.ModifyArgs;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.invoke.arg.Args;

import static com.github.epsilon.Constants.mc;

@Mixin(Entity.class)
public class MixinEntity {

    @Inject(method = "turn", at = @At("HEAD"), cancellable = true)
    private void updateTurn(double xo, double yo, CallbackInfo ci) {
        if ((Object) this == mc.player) {
            FreeCamera freeCamera = FreeCamera.INSTANCE;
            if (freeCamera.isEnabled()) {
                freeCamera.changeLookDirection(xo * 0.15, yo * 0.15);
                ci.cancel();
            }
        }
    }

    // 26.3 起 calculateViewVector 是静态方法，包装方法不能再接收实体接收者，改为读取 mixin 自身的实例。
    @WrapOperation(method = "getViewVector", at = @At(value = "INVOKE", target = "Lnet/minecraft/world/entity/Entity;calculateViewVector(FF)Lnet/minecraft/world/phys/Vec3;"))
    private Vec3 redirectGetViewYRot(float xRot, float yRot, Operation<Vec3> original) {
        if ((Object) this == mc.player) {
            RaytraceEvent event = EventBus.INSTANCE.post(new RaytraceEvent(yRot, xRot));
            return original.call(event.getPitch(), event.getYaw());
        }
        return original.call(xRot, yRot);
    }

    @WrapOperation(method = "moveRelative", at = @At(value = "INVOKE", target = "Lnet/minecraft/world/entity/Entity;getYRot()F"))
    private float redirectGetYRotInMoveRelative(Entity instance, Operation<Float> original) {
        if (instance == mc.player) {
            StrafeEvent event = EventBus.INSTANCE.post(new StrafeEvent(instance.getYRot()));
            return event.getYaw();
        }
        return original.call(instance);
    }

    @ModifyArgs(method = "push(Lnet/minecraft/world/entity/Entity;)V", at = @At(value = "INVOKE", target = "Lnet/minecraft/world/entity/Entity;push(DDD)V"))
    private void pushAwayFromHook(Args args) {
        if ((Entity) (Object) this == mc.player) {
            if (Velocity.INSTANCE.isEnabled() && Velocity.INSTANCE.mode.is(Velocity.Mode.Cancel) && Velocity.INSTANCE.entityPush.getValue()) {
                args.set(0, 0.0);
                args.set(1, 0.0);
                args.set(2, 0.0);
            }
        }
    }

}
