package com.github.epsilon.modules.impl.render;

import com.github.epsilon.modules.Category;
import com.github.epsilon.modules.Module;
import com.github.epsilon.settings.SettingGroup;
import com.github.epsilon.settings.impl.BoolSetting;
import com.mojang.blaze3d.pipeline.BlendFunction;
import com.mojang.blaze3d.pipeline.ColorTargetState;
import com.mojang.blaze3d.pipeline.DepthStencilState;
import com.mojang.blaze3d.pipeline.RenderPipeline;
import com.mojang.blaze3d.platform.CompareOp;
import net.minecraft.client.renderer.RenderPipelines;
import net.minecraft.client.renderer.rendertype.RenderSetup;
import net.minecraft.client.renderer.rendertype.RenderType;
import net.minecraft.resources.Identifier;
import net.minecraft.util.Util;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.animal.Animal;
import net.minecraft.world.entity.monster.Monster;
import net.minecraft.world.entity.player.Player;

import java.awt.*;
import java.util.function.Function;

public class Chams extends Module {

    public static final Chams INSTANCE = new Chams();

    private Chams() {
        super("Chams", Category.RENDER);
    }

    private final SettingGroup sgGlow = settingGroup("Glow");

    public final BoolSetting noDepth = boolSetting("No Depth", true);

    private final BoolSetting glow = boolSetting("Glow", true).group(sgGlow);
    private final BoolSetting self = boolSetting("Self", true, glow::getValue).group(sgGlow);
    private final BoolSetting player = boolSetting("Player", true, glow::getValue).group(sgGlow);
    private final BoolSetting mob = boolSetting("Mob", true, glow::getValue).group(sgGlow);
    private final BoolSetting animal = boolSetting("Animal", true, glow::getValue).group(sgGlow);

    private static final RenderPipeline ENTITY_CHAMS_PIPELINE = RenderPipeline.builder(RenderPipelines.ENTITY_SNIPPET)
            .withLocation("pipeline/epsilon_entity_chams")
            .withShaderDefine("ALPHA_CUTOUT", 0.1f)
            .withShaderDefine("PER_FACE_LIGHTING")
            .withSampler("Sampler1")
            .withColorTargetState(new ColorTargetState(BlendFunction.TRANSLUCENT))
            .withCull(false)
            .withDepthStencilState(new DepthStencilState(CompareOp.LESS_THAN_OR_EQUAL, true, -1.0f, -1100000.0f))
            .build();

    private static final Function<Identifier, RenderType> ENTITY_CHAMS_TYPE = Util.memoize(
            texture -> RenderType.create("sakura_entity_chams", RenderSetup.builder(ENTITY_CHAMS_PIPELINE)
                    .withTexture("Sampler0", texture)
                    .useLightmap()
                    .useOverlay()
                    .affectsCrumbling()
                    .sortOnUpload()
                    .setOutline(RenderSetup.OutlineProperty.AFFECTS_OUTLINE)
                    .createRenderSetup()));

    public RenderType getRenderType(Identifier texture) {
        return ENTITY_CHAMS_TYPE.apply(texture);
    }

    public boolean shouldRenderGlow(Entity entity) {
        if (!glow.getValue()) {
            return false;
        }
        if (entity == mc.player) {
            return self.getValue();
        }
        if (entity instanceof Player) {
            return player.getValue();
        }
        if (entity instanceof Animal) {
            return animal.getValue();
        }
        if (entity instanceof Monster) {
            return mob.getValue();
        }
        return false;
    }

    public int getGlowColor(Entity entity) {
        if (entity == mc.player) {
            return getRainbowColor();
        }
        if (entity instanceof Monster) {
            return Color.RED.getRGB();
        }
        return 0xFFFFFFFF;
    }

    private int getRainbowColor() {
        float hue = (System.currentTimeMillis() % 3000) / 3000.0f;
        return Color.HSBtoRGB(hue, 1.0f, 1.0f);
    }

}
