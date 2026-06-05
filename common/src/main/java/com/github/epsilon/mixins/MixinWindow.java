package com.github.epsilon.mixins;

import com.github.epsilon.EpsilonCommon;
import com.github.epsilon.modules.impl.ClientSetting;
import com.mojang.blaze3d.platform.IconSet;
import com.mojang.blaze3d.platform.Window;
import net.minecraft.server.packs.PackResources;
import net.minecraft.server.packs.resources.IoSupplier;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

import java.io.IOException;
import java.io.InputStream;
import java.util.List;

@Mixin(Window.class)
public class MixinWindow {

    @Redirect(method = "setIcon", at = @At(value = "INVOKE", target = "Lcom/mojang/blaze3d/platform/IconSet;getStandardIcons(Lnet/minecraft/server/packs/PackResources;)Ljava/util/List;"))
    private List<IoSupplier<InputStream>> onSetIcon(IconSet instance, PackResources resources) throws IOException {
        final InputStream stream16 = EpsilonCommon.class.getResourceAsStream("/assets/epsilon/textures/icons/icon_16x16.png");
        final InputStream stream32 = EpsilonCommon.class.getResourceAsStream("/assets/epsilon/textures/icons/icon_32x32.png");
        return ClientSetting.INSTANCE.customIcon.getValue() && stream16 != null && stream32 != null ?
                List.of(() -> stream16, () -> stream32) :
                instance.getStandardIcons(resources);
    }

}
