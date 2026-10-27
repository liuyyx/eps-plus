package com.github.epsilon.modules.impl.movement;

import com.github.epsilon.events.bus.EventHandler;
import com.github.epsilon.events.bus.EventPriority;
import com.github.epsilon.events.impl.*;
import com.github.epsilon.graphics.schedulers.render3d.Render3DScheduler;
import com.github.epsilon.modules.Category;
import com.github.epsilon.modules.Module;
import com.github.epsilon.modules.impl.movement.scaffold.ravenb4.RavenB4Scaffold;
import com.github.epsilon.modules.impl.movement.scaffold.ravenb4.RavenB4Scaffold.*;
import com.github.epsilon.settings.impl.*;
import net.minecraft.core.BlockPos;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.phys.AABB;
import java.awt.Color;

public final class GreatWall extends Module implements RavenB4Scaffold.Host {
    public static final GreatWall INSTANCE = new GreatWall();
    private static final Color LINE_COLOR = new Color(255, 170, 0, 255);
    private static final long HIGHLIGHT_MS = 500L;
    private final DoubleSetting motion = doubleSetting("Motion", 1.0, 0.5, 1.2, 0.01);
    private final DoubleSetting fastScaffoldMotion = doubleSetting("Fast Scaffold Motion", 1.0, 0.5, 1.2, 0.01);
    private final EnumSetting<RavenPrecision> precision = enumSetting("Precision", RavenPrecision.High);
    private final IntSetting sneakEvery = intSetting("Sneak Every", 0, 0, 20, 1);
    private final BoolSetting autoSwap = boolSetting("Auto Swap", false);
    private final BoolSetting highlightBlocks = boolSetting("Highlight Blocks", true);
    private final RavenB4Scaffold engine = new RavenB4Scaffold(this);
    private int placeCooldown;

    private GreatWall() { super("Great Wall", Category.MOVEMENT); }

    @Override protected void onEnable() {
        if (Scaffold.INSTANCE.isEnabled()) Scaffold.INSTANCE.setEnabled(false);
        placeCooldown = 0;
        engine.resetState();
        engine.bindHost();
        engine.onEnable();
    }

    @Override protected void onDisable() {
        engine.bindHost();
        engine.onDisable();
        placeCooldown = 0;
    }

    @EventHandler private void onTick(PlayerTickEvent.Pre event) {
        if (!nullCheck()) engine.onPreTick();
    }
    @EventHandler(priority = EventPriority.HIGH) private void onUse(StartUseItemEvent event) { engine.onUseItem(event); }
    @EventHandler private void onPosition(SendPositionEvent event) { engine.onPreMotion(); }
    @EventHandler private void onInput(KeyboardInputEvent event) { engine.onKeyboardInput(event); }
    @EventHandler private void onLevel(LevelUpdateEvent event) { engine.resetState(); placeCooldown = 0; }
    @EventHandler private void onRender(Render3DEvent event) {
        if (!isEnabled() || !highlightBlocks.getValue()) return;
        BlockPos target = engine.getRenderBlock();
        if (target != null) Render3DScheduler.INSTANCE.addOutlineBox(new AABB(target).inflate(0.002), LINE_COLOR);
        long now = System.currentTimeMillis();
        var placed = engine.getPlacedBlocks();
        placed.entrySet().removeIf(entry -> now - Math.abs(entry.getValue()) >= HIGHLIGHT_MS);
        for (var entry : placed.entrySet()) {
            int alpha = (int) (255 * (1.0 - Math.max(0, now - Math.abs(entry.getValue())) / (double) HIGHLIGHT_MS));
            if (alpha > 0) Render3DScheduler.INSTANCE.addOutlineBox(new AABB(entry.getKey()).inflate(0.002),
                    new Color(LINE_COLOR.getRed(), LINE_COLOR.getGreen(), LINE_COLOR.getBlue(), alpha));
        }
    }

    @Override public boolean ravenMovementFix() { return true; }
    @Override public boolean ravenLegitMotion() { return true; }
    @Override public boolean ravenJumpFacingForward() { return true; }
    @Override public boolean ravenAutoSwap() { return autoSwap.getValue(); }
    @Override public boolean ravenSlowOnEnable() { return false; }
    @Override public boolean ravenSlowSpeedPotion() { return false; }
    @Override public boolean ravenSafeWalk() { return false; }
    @Override public boolean ravenSilentSwing() { return false; }
    @Override public boolean ravenTower() { return false; }
    @Override public int ravenSneakEvery() { return sneakEvery.getValue(); }
    @Override public double ravenSpeedBoost() { return 1.3; }
    @Override public double ravenMotion() { return motion.getValue(); }
    @Override public double ravenFastScaffoldMotion() { return fastScaffoldMotion.getValue(); }
    @Override public RavenRotation ravenRotationMode() { return RavenRotation.Offset; }
    @Override public RavenFastScaffold ravenFastScaffold() { return RavenFastScaffold.Side; }
    @Override public RavenFastScaffold ravenFastScaffoldRmb() { return RavenFastScaffold.Side; }
    @Override public RavenMultiPlace ravenMultiPlace() { return RavenMultiPlace.Disabled; }
    @Override public RavenPrecision ravenPrecision() { return precision.getValue(); }
    @Override public int getBlockCount() { return Scaffold.INSTANCE.getBlockCount(); }
    @Override public boolean isValidStack(ItemStack stack) { return Scaffold.INSTANCE.isValidStack(stack); }
    @Override public boolean isPlaceOnCooldown() { return placeCooldown > 0; }
    @Override public void tickPlaceCooldown() { if (placeCooldown > 0) --placeCooldown; }
    @Override public void markPlaceCooldown(int minTicks) { placeCooldown = Math.max(minTicks, 2); }
}
