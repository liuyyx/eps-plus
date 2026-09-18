package com.github.epsilon.mixins;

import com.github.epsilon.modules.impl.render.Chams;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import net.minecraft.client.renderer.SubmitNodeCollection;
import net.minecraft.client.renderer.feature.ModelFeatureRenderer;
import net.minecraft.client.renderer.feature.phase.FeatureRenderPhase;
import net.minecraft.client.renderer.feature.phase.SimpleFeatureRenderPhase;
import net.minecraft.client.renderer.feature.submit.SubmitNode;
import net.minecraft.client.renderer.feature.submit.TranslucentSubmit;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;

@Mixin(SubmitNodeCollection.class)
public abstract class MixinSubmitNodeCollection {

    @Shadow
    @Final
    public SimpleFeatureRenderPhase alwaysOnTopGizmos;

    @Shadow
    @Final
    public SimpleFeatureRenderPhase solid;

    @Shadow
    @Final
    public SimpleFeatureRenderPhase translucentCustomGeometry;

    @Shadow
    @Final
    public FeatureRenderPhase<? super TranslucentSubmit> translucentBlocksAndItems;

    @Shadow
    @Final
    public FeatureRenderPhase<? super TranslucentSubmit> translucentModels;

    @WrapOperation(method = {"submitModel", "submitItem", "submitCustomGeometry"}, at = @At(value = "INVOKE", target = "Lnet/minecraft/client/renderer/feature/phase/SimpleFeatureRenderPhase;submit(Lnet/minecraft/client/renderer/feature/submit/SubmitNode;)V"))
    private void submitChamsGeometry(SimpleFeatureRenderPhase phase, SubmitNode submit, Operation<Void> original) {
        Chams chams = Chams.INSTANCE;
        if ((phase == this.solid || phase == this.translucentCustomGeometry) && (chams.isSubmittingPlayer() || submit instanceof ModelFeatureRenderer.Submit<?> model && chams.isChamsRenderType(model.renderType()))) {
            this.alwaysOnTopGizmos.submit(submit);
        } else {
            original.call(phase, submit);
        }
    }

    /**
     * 把 chams 的半透明模型/物品提交改到最高层。
     *
     * <p>26.3 的半透明模型与物品容器字段类型是 {@code FeatureRenderPhase}，调用点走接口描述符，
     * 因此需要按 phase 实例过滤，只处理 translucentModels / translucentBlocksAndItems 两处。
     */
    @WrapOperation(method = {"submitModel", "submitItem"}, at = @At(value = "INVOKE", target = "Lnet/minecraft/client/renderer/feature/phase/FeatureRenderPhase;submit(Lnet/minecraft/client/renderer/feature/submit/SubmitNode;)V"))
    private void submitChamsTranslucentGeometry(FeatureRenderPhase<SubmitNode> phase, SubmitNode submit, Operation<Void> original) {
        Chams chams = Chams.INSTANCE;
        boolean translucentPhase = (Object) phase == this.translucentModels || (Object) phase == this.translucentBlocksAndItems;
        if (translucentPhase && (chams.isSubmittingPlayer() || submit instanceof ModelFeatureRenderer.Submit<?> model && chams.isChamsRenderType(model.renderType()))) {
            this.alwaysOnTopGizmos.submit(submit);
        } else {
            original.call(phase, submit);
        }
    }

}
