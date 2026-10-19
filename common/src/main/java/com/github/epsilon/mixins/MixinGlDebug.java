package com.github.epsilon.mixins;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.llamalad7.mixinextras.sugar.Local;
import com.mojang.blaze3d.opengl.GlDebug;
import org.slf4j.Logger;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

@Mixin(GlDebug.class)
public class MixinGlDebug {

    @WrapOperation(method = "printDebugLog", at = @At(value = "INVOKE", target = "Lorg/slf4j/Logger;info(Ljava/lang/String;Ljava/lang/Object;)V"))
    private static void avoidSpam(Logger instance, String s, Object o, Operation<Void> original, @Local(argsOnly = true, index = 4) int severity) {
        // index=4 才是 severity（1 source / 2 type / 3 id / 4 severity）；原 index=3 取的是消息 id。
        // 无论是否升级，都必须转发原日志，否则驱动真实 GL 错误会从日志里静默消失。
        original.call(instance, s, o);
        if (severity == 37190) {
            instance.error(s, o, new RuntimeException());
        }
    }

}
