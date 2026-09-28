/*
 * This file is part of Epsilon.
 *
 * 逐行照抄自 OpenPal（https://github.com/OpenPal）MC 1.21.10 的
 * wtf.opal.client.feature.helper.impl.player.rotation.model.impl.OrganicRotationModel，
 * 只做 Yarn → 26.2 Mojang 命名替换（Vec2f → Rot2f、MathHelper → Mth、
 * RandomUtility.getRandomDouble → MathUtils.getRandom）。
 */
package com.github.epsilon.modules.impl.movement.scaffold.rotation.model.impl;

import com.github.epsilon.modules.impl.movement.scaffold.rotation.model.EnumRotationModel;
import com.github.epsilon.modules.impl.movement.scaffold.rotation.model.IRotationModel;
import com.github.epsilon.utils.math.MathUtils;
import com.github.epsilon.utils.player.RotationUtility;
import com.github.epsilon.utils.rotation.Rot2f;
import net.minecraft.util.Mth;

import java.util.Random;

public final class OrganicRotationModel implements IRotationModel {

    private final double speed;
    private final double driftIntensity;
    private final double jitterIntensity;

    private final double freqYaw1, freqYaw2, freqPitch1, freqPitch2;
    private final double phaseYaw1, phaseYaw2, phasePitch1, phasePitch2;
    private double timeAccumulator;

    private final Random random;

    public OrganicRotationModel(final double speed, final double driftIntensity, final double jitterIntensity) {
        this.speed = speed;
        this.driftIntensity = driftIntensity;
        this.jitterIntensity = jitterIntensity;

        random = new Random(System.nanoTime());
        this.freqYaw1 = random.nextDouble() * 0.3 + 0.1;
        this.freqYaw2 = random.nextDouble() * 0.5 + 0.5;
        this.freqPitch1 = random.nextDouble() * 0.3 + 0.1;
        this.freqPitch2 = random.nextDouble() * 0.5 + 0.5;
        this.phaseYaw1 = random.nextDouble() * Math.PI * 2;
        this.phaseYaw2 = random.nextDouble() * Math.PI * 2;
        this.phasePitch1 = random.nextDouble() * Math.PI * 2;
        this.phasePitch2 = random.nextDouble() * Math.PI * 2;
        this.timeAccumulator = 0.0;
    }

    @Override
    public Rot2f tick(Rot2f from, Rot2f to, float timeDelta) {
        final float rawYaw = Mth.wrapDegrees(to.getYaw() - from.getYaw());
        final float rawPitch = to.getPitch() - from.getPitch();
        float deltaYaw = rawYaw * timeDelta;
        float deltaPitch = rawPitch * timeDelta;

        final double distance = Math.hypot(deltaYaw, deltaPitch);
        if (distance < driftIntensity) {
            return new Rot2f(from.getYaw() + deltaYaw, from.getPitch() + deltaPitch);
        }

        if (distance > 0) {
            final double ratioYaw = Math.abs(deltaYaw) / distance;
            final double ratioPitch = Math.abs(deltaPitch) / distance;
            final double maxYaw = speed * ratioYaw * timeDelta;
            final double maxPitch = speed * ratioPitch * timeDelta;
            deltaYaw = Mth.clamp(deltaYaw, (float) -maxYaw, (float) maxYaw);
            deltaPitch = Mth.clamp(deltaPitch, (float) -maxPitch, (float) maxPitch);
        }

        timeAccumulator += timeDelta;

        final double sinYaw = Math.sin(timeAccumulator * freqYaw1 + phaseYaw1)
                + MathUtils.getRandom(0.45, 0.55) * Math.sin(timeAccumulator * freqYaw2 + phaseYaw2);
        final double sinPitch = Math.sin(timeAccumulator * freqPitch1 + phasePitch1)
                + MathUtils.getRandom(0.45, 0.55) * Math.sin(timeAccumulator * freqPitch2 + phasePitch2);
        final double driftYaw = sinYaw * driftIntensity * timeDelta;
        final double driftPitch = sinPitch * driftIntensity * timeDelta;

        final double jitterYaw = (random.nextDouble() * 2 - 1) * jitterIntensity * timeDelta;
        final double jitterPitch = (random.nextDouble() * 2 - 1) * jitterIntensity * timeDelta;

        final float moveYaw = deltaYaw + (float) driftYaw + (float) jitterYaw;
        final float movePitch = deltaPitch + (float) driftPitch + (float) jitterPitch;

        final Rot2f rotation = new Rot2f(from.getYaw() + moveYaw, from.getPitch() + movePitch);

        return RotationUtility.patchConstantRotation(rotation, from);
    }

    @Override
    public EnumRotationModel getEnum() {
        return EnumRotationModel.ORGANIC;
    }
}
