package com.github.epsilon.mixins;

import com.github.epsilon.FuckerPane;
import net.minecraft.client.main.Main;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.Redirect;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(Main.class)
public class MixinMain {

    @Redirect(method = "<clinit>", at = @At(value = "INVOKE", target = "Ljava/lang/System;setProperty(Ljava/lang/String;Ljava/lang/String;)Ljava/lang/String;"))
    private static String redirectStaticInit(String key, String value) {
        return System.setProperty("java.awt.headless", "false");
    }

    @Inject(method = "main", at = @At("HEAD"))
    private static void onMain(String[] args, CallbackInfo ci) {
        FuckerPane.show();
    }

}
