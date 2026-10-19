package com.github.epsilon.modules.impl.movement;

import com.github.epsilon.events.bus.EventHandler;
import com.github.epsilon.events.bus.EventPriority;
import com.github.epsilon.events.impl.KeyboardInputEvent;
import com.github.epsilon.events.impl.MousePressEvent;
import com.github.epsilon.events.impl.PacketEvent;
import com.github.epsilon.events.impl.PlayerTickEvent;
import com.github.epsilon.events.impl.SendPositionEvent;
import com.github.epsilon.modules.Category;
import com.github.epsilon.modules.Module;
import com.github.epsilon.modules.impl.combat.KillAura;
import com.github.epsilon.settings.impl.BoolSetting;
import com.github.epsilon.settings.impl.EnumSetting;
import com.github.epsilon.settings.impl.IntSetting;
import com.github.epsilon.utils.network.NetworkUtils;
import com.github.epsilon.utils.player.ChatUtils;
import com.github.epsilon.utils.rotation.Rot2f;
import com.github.epsilon.utils.rotation.RotationUtils;
import net.minecraft.ChatFormatting;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.network.protocol.game.ClientboundPlayerPositionPacket;
import net.minecraft.network.protocol.game.ServerboundPlayerActionPacket;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;
import org.lwjgl.glfw.GLFW;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * 移植自 1.8.9 脚本 {@code AutoBuild.java}（{@code //Made by Monster_Sang & DeepSeek-V4-Pro}，
 * Monster_Sang Bilibili: https://space.bilibili.com/1581282621）。
 *
 * <p>脚本描述第二句：{@code Builds a rocket in front of you.}
 * （Epsilon 无 {@code registerDescription} 对应控件，故第一句进 {@link #getInfo()}、本句留在此处。）
 *
 * <p>移植纪律：判定顺序、阈值、分支结构、时间常量一律照脚本原版；只做 1.8.9 → 26.2 的
 * API 等价替换。宿主 API 的替换点集中在文件末尾「宿主替换层」一节，正文与脚本逐行对应。
 *
 * <p>⚠️ 本模块<b>不是静默瞄准模块</b>：脚本原版在 {@code !silentAim} 时直接写玩家真实视角
 * （{@code player.setYaw/setPitch}），移植版保持该行为（{@code player.setYRot/setXRot}）；
 * {@code silentAim} 打开时才只改发包角（由 {@link SendPositionEvent} 承载）。
 */
public class AutoBuild extends Module {

    public static final AutoBuild INSTANCE = new AutoBuild();

    private AutoBuild() {
        super("Auto Build", Category.MOVEMENT);
    }

    @Override
    public String getInfo() {
        return "Made By Monster_Sang(AI)";
    }

    // ===== 脚本 5-18 行：常量 =====

    private static final String[] UNPLACEABLE = {
            "chest", "barrel", "furnace", "crafting_table", "enchanting_table", "anvil",
            "brewing_stand", "hopper", "dropper", "dispenser", "door", "trapdoor",
            "fence_gate", "lever", "button", "pressure_plate", "sign", "bed",
            "beacon", "shulker_box", "loom", "cartography_table", "smithing_table",
            "stonecutter", "grindstone", "note_block", "jukebox", "bell", "campfire"
    };

    private static final int PHASE_IDLE = 0;
    private static final int PHASE_ALIGN = 1;
    private static final int PHASE_CHECK = 2;
    private static final int PHASE_PLACE = 3;
    private static final int PHASE_DIG = 4;

    /** 脚本 20 行作者串（脚本内从不被引用，属作者标记，原样保留）。 */
    private static final char[] _c0 = {'M', 'o', 'n', 's', 't', 'e', 'r', '_', 'S', 'a', 'n', 'g'};

    // ===== 脚本 70-81 行：设置项（声明顺序 = onLoad 顺序） =====

    /**
     * 脚本 {@code triggerOptions = { "Right Click", "Middle Click" }} +
     * {@code registerSlider("Trigger", "", 0, triggerOptions)} 的等价物：
     * 滑块下标 0 → {@link #RIGHT_CLICK}、1 → {@link #MIDDLE_CLICK}。
     */
    private enum Trigger {
        RIGHT_CLICK, MIDDLE_CLICK
    }

    private final EnumSetting<Trigger> trigger = enumSetting("Trigger", Trigger.RIGHT_CLICK);
    /**
     * 脚本 {@code registerSlider("Rotate Speed", "", 99, buildSpeedOptions())} —— 100 档带标签滑块
     * （Min / 2%…99% / Max）在 Epsilon 无对应控件，故退化为 0..99 的整数滑块，只保留数值语义；
     * 脚本的 {@code buildSpeedOptions()}（831-839 行）因此<b>不移植</b>。
     */
    private final IntSetting rotateSpeed = intSetting("Rotate Speed", 99, 0, 99, 1);
    private final BoolSetting autoSwitch = boolSetting("Auto Switch", true);
    private final BoolSetting allowKillAura = boolSetting("Allow KillAura", false);
    private final BoolSetting allowScaffold = boolSetting("Allow Scaffold", false);
    private final BoolSetting speedBuild = boolSetting("Speed Build", false);
    private final BoolSetting throughWall = boolSetting("Through Wall", false);
    private final BoolSetting silentAim = boolSetting("Silent Aim", false);
    private final BoolSetting perfect = boolSetting("Perfect", false);
    private final BoolSetting debug = boolSetting("Debug", false);

    // ===== 脚本 29-64 行：全局状态 =====

    /** 脚本 {@code triggerButton}：由 {@link #refreshSettings()} 从 Trigger 滑块换算。 */
    private int triggerButton = 1;
    /**
     * 脚本 {@code rotateSpeed}（int，运行时值）= 滑块值 + 1。
     * 滑块设置本身是 {@link #rotateSpeed}（IntSetting），故此处加 {@code Value} 后缀避免重名。
     */
    private int rotateSpeedValue = 100;

    private boolean building = false;
    private int phase = PHASE_IDLE;
    private int step = 0;
    private int previousSlot = 0;
    private int buildSlot = -1;
    private long placeStart = 0;
    private long lastPrompt = 0;
    private float aimYaw = 0;
    private float aimPitch = 0;
    private boolean jumped = false;
    private long jumpStart = 0;

    private double originX = 0, originY = 0, originZ = 0;

    private int aimStep = -1;
    private boolean rotActive = false;
    private float rotTargetYaw = 0, rotTargetPitch = 0;

    private Vec3 verifyPos = null;
    private long verifyStart = 0;

    private double buildBaseY = 0;
    private Vec3 middleBlock = null;
    private int buildFx = 0;
    private int buildFz = 0;
    private String buildBlockName = "";
    private int digSlot = -1;
    private int digOriginalSlot = -1;
    private long digStart = 0;
    private int digStage = 0;
    private long digSentAt = 0;

    private boolean walking = false;
    private float walkForward = 0, walkStrafe = 0;

    private final List<Vec3> queue = new ArrayList<>();

    /** 本 tick 正在派发的键盘输入事件；脚本 {@code client.setForward/setStrafe} 的注入目标。 */
    private KeyboardInputEvent activeInputEvent = null;

    // ===== 脚本 85-94 行：生命周期 =====

    @Override
    protected void onEnable() {
        resetBuild();
        refreshSettings();
        debugSuccess("Enabled");
    }

    @Override
    protected void onDisable() {
        resetBuild();
        debugError("Disabled");
    }

    @Override
    protected void resetCustomState() {
        resetBuild();
    }

    // ===== 脚本 96-114 行：鼠标触发 =====

    @EventHandler
    private void onMouse(MousePressEvent event) {
        if (event.getButton() != triggerButton || event.getAction() != GLFW.GLFW_PRESS || mc.gui.screen() != null) {
            return;
        }
        if (building) {
            clientPrint("&7[&dAutoBuild&7]&r &cStop");
            resetBuild();
            event.cancel();
            return;
        }
        if (isBlocked()) {
            debugLog("Blocked");
            return;
        }
        if (mc.level == null || mc.player == null) {
            return;
        }
        startBuild(mc.player);
        event.cancel();
    }

    // ===== 脚本 116-122 行：位置回弹包 =====

    @EventHandler(priority = EventPriority.HIGHEST)
    private void onPacketReceived(PacketEvent.Receive event) {
        // 该事件在 netty 线程派发：不能在这里直接改客户端线程状态（queue/输入/KeyMapping）。
        if (event.getPacket() instanceof ClientboundPlayerPositionPacket) {
            mc.execute(() -> {
                if (!building) return;
                prompt("Flag (teleport)");
                resetBuild();
            });
        }
    }

    // ===== 脚本 124-158 行：刻更新 =====

    @EventHandler
    private void onPreUpdate(PlayerTickEvent.Pre event) {
        if (mc.level == null) return;
        LocalPlayer player = mc.player;
        if (player == null) return;

        refreshSettings();
        applyRotation();

        if (isBlocked()) {
            if (building) resetBuild();
            return;
        }

        if (!building) return;

        if (tooFar(player)) {
            prompt("Stopped (too far)");
            resetBuild();
            return;
        }

        verifyPlaced();

        if (phase == PHASE_ALIGN) {
            alignPhase(player);
        } else if (phase == PHASE_CHECK) {
            checkPhase(player);
        } else if (phase == PHASE_PLACE) {
            placePhase(player);
        } else if (phase == PHASE_DIG) {
            digPhase(player);
        }
    }

    // ===== 脚本 160-173 行：输入注入 =====

    @EventHandler(priority = EventPriority.HIGHEST)
    private void onPostPlayerInput(KeyboardInputEvent event) {
        activeInputEvent = event;
        if (!building) return;
        if (walking) {
            setClientForward(walkForward);
            setClientStrafe(walkStrafe);
        } else if (phase == PHASE_PLACE && step < queue.size()) {
            applyMovementFix();
        } else {
            releaseMovement();
        }
    }

    // ===== 脚本 175-180 行：发包角 =====

    @EventHandler
    private void onPreMotion(SendPositionEvent event) {
        if (!building) return;
        LocalPlayer player = mc.player;
        if (player == null) return;
        event.setYaw(silentAim.getValue() ? aimYaw : player.getYRot());
        event.setPitch(silentAim.getValue() ? aimPitch : player.getXRot());
    }

    // ===== 脚本 182-223 行：走位对齐 =====

    private void applyMovementFix() {
        LocalPlayer player = mc.player;
        if (player == null) return;
        float forward = clientForward();
        float strafe = clientStrafe();
        if (forward == 0 && strafe == 0) {
            setClientForward(0);
            setClientStrafe(0);
            return;
        }
        double angle = wrapTo180(Math.toDegrees(getDirection(player.getYRot(), forward, strafe)));
        float bestForward = 0;
        float bestStrafe = 0;
        double bestDiff = Double.MAX_VALUE;
        for (float pf = -1f; pf <= 1f; pf += 1f) {
            for (float ps = -1f; ps <= 1f; ps += 1f) {
                if (pf == 0 && ps == 0) continue;
                double predicted = wrapTo180(Math.toDegrees(getDirection(player.getYRot(), pf, ps)));
                double diff = Math.abs(angle - predicted);
                if (diff < bestDiff) {
                    bestDiff = diff;
                    bestForward = pf;
                    bestStrafe = ps;
                }
            }
        }
        setClientForward(bestForward);
        setClientStrafe(bestStrafe);
    }

    private void releaseMovement() {
        setClientForward(0);
        setClientStrafe(0);
    }

    private double getDirection(float yaw, float forward, float strafe) {
        if (forward < 0f) yaw += 180f;
        float f = 1f;
        if (forward < 0f) f = -0.5f;
        else if (forward > 0f) f = 0.5f;
        if (strafe > 0f) yaw -= 90f * f;
        if (strafe < 0f) yaw += 90f * f;
        return Math.toRadians(yaw);
    }

    private double wrapTo180(double angle) {
        while (angle > 180.0) angle -= 360.0;
        while (angle < -180.0) angle += 360.0;
        return angle;
    }

    // ===== 脚本 234-266 行：脚本化旋转 =====

    private void setRotationTarget(float targetYaw, float targetPitch) {
        rotTargetYaw = targetYaw;
        rotTargetPitch = clamp(targetPitch, -90.0f, 90.0f);
        rotActive = true;
    }

    private void applyRotation() {
        if (!rotActive) return;
        LocalPlayer player = mc.player;
        if (player == null) return;
        float factor = rotationFactor();
        float yawDelta = normalizeYawDelta(rotTargetYaw - aimYaw);
        float pitchDelta = rotTargetPitch - aimPitch;
        if (Math.abs(yawDelta) < 1.0f && Math.abs(pitchDelta) < 1.0f) {
            aimYaw = rotTargetYaw;
            aimPitch = rotTargetPitch;
            rotActive = false;
        } else {
            aimYaw += yawDelta * factor;
            aimPitch += pitchDelta * factor;
        }
        if (!silentAim.getValue()) {
            player.setYRot(aimYaw);
            player.setXRot(aimPitch);
        }
    }

    // ===== 脚本 268-296 行：设置刷新与节流 =====

    private void refreshSettings() {
        // 脚本此处还有 8 行 modules.getButton(...) 把开关抄进布尔字段；
        // Epsilon 里开关本身就是设置对象，正文直接读 getValue()，故不再抄写。
        int t = trigger.getValue() == Trigger.MIDDLE_CLICK ? 1 : 0;
        triggerButton = (t == 1) ? 2 : 1;

        int s = rotateSpeed.getValue();
        rotateSpeedValue = s + 1;
    }

    private float rotationFactor() {
        if (speedBuild.getValue()) return 0.9f;
        return 0.05f + rotateSpeedValue * 0.009f;
    }

    private long placementDelay() {
        return (long) clamp(300f - rotateSpeedValue * 2.5f, 50f, 300f);
    }

    private long verifyDelay() {
        return (long) clamp(300f - rotateSpeedValue * 2.0f, 100f, 300f);
    }

    private boolean isBlocked() {
        return (!allowKillAura.getValue() && KillAura.INSTANCE.target != null)
                || (!allowScaffold.getValue() && Scaffold.INSTANCE.isEnabled());
    }

    private boolean tooFar(LocalPlayer player) {
        Vec3 pos = player.position();
        double dx = pos.x - originX;
        double dy = pos.y - originY;
        double dz = pos.z - originZ;
        return dx * dx + dy * dy + dz * dz > 9.0;
    }

    // ===== 脚本 311-332 行：开始 =====

    private void startBuild(LocalPlayer player) {
        previousSlot = selectedSlot();
        step = 0;
        buildSlot = -1;
        placeStart = 0;
        jumped = false;
        jumpStart = 0;
        walking = false;
        aimStep = -1;
        rotActive = false;
        verifyPos = null;
        Vec3 pos = player.position();
        originX = pos.x;
        originY = pos.y;
        originZ = pos.z;
        aimYaw = player.getYRot();
        aimPitch = player.getXRot();
        queue.clear();
        building = true;
        phase = PHASE_ALIGN;
        debugLog("Build started");
    }

    // ===== 脚本 334-361 行：对齐相位 =====

    private void alignPhase(LocalPlayer player) {
        Vec3 pos = player.position();
        double cx = Math.floor(pos.x) + 0.5;
        double cz = Math.floor(pos.z) + 0.5;

        double dx = cx - pos.x;
        double dz = cz - pos.z;

        if (Math.abs(dx) < 0.25 && Math.abs(dz) < 0.25) {
            walking = false;
            walkForward = 0;
            walkStrafe = 0;
            phase = PHASE_CHECK;
            debugLog("Aligned");
            return;
        }

        float yaw = player.getYRot();
        float rad = (float) Math.toRadians(yaw);
        float sin = (float) Math.sin(rad);
        float cos = (float) Math.cos(rad);
        float fwd = (float) (-sin * dx + cos * dz);
        float str = (float) (cos * dx + sin * dz);
        walkForward = fwd > 0.1f ? 1.0f : (fwd < -0.1f ? -1.0f : 0.0f);
        walkStrafe = str > 0.1f ? 1.0f : (str < -0.1f ? -1.0f : 0.0f);
        if (!walking) debugLog("Walking");
        walking = true;
    }

    // ===== 脚本 363-407 行：检查相位 =====

    private void checkPhase(LocalPlayer player) {
        buildQueue(player);
        List<Vec3> full = new ArrayList<>(queue);

        List<Vec3> missing = new ArrayList<>();
        for (Vec3 pos : queue) {
            if (isAir(pos)) missing.add(pos);
        }
        queue.clear();
        queue.addAll(missing);

        if (queue.isEmpty()) {
            prompt("Already built");
            resetBuild();
            return;
        }

        if (!throughWall.getValue() && !areaClear(full)) {
            prompt("Blocks detected nearby");
            resetBuild();
            return;
        }

        buildSlot = findBlockSlot();
        if (buildSlot < 0) {
            prompt("No placeable blocks in hotbar");
            resetBuild();
            return;
        }

        ItemStack block = hotbarStack(buildSlot);
        if (block.getCount() < queue.size()) {
            prompt("Not enough blocks (need " + queue.size() + ")");
            resetBuild();
            return;
        }

        setSelectedSlot(buildSlot);
        phase = PHASE_PLACE;
        step = 0;
        placeStart = 0;
        aimStep = -1;
        buildBlockName = normalize(itemName(block));
        debugLog("Building with " + buildBlockName);
    }

    // ===== 脚本 409-479 行：放置相位 =====

    private void placePhase(LocalPlayer player) {
        if (verifyPos != null) return;

        if (step >= queue.size()) {
            if (perfect.getValue()) startDig(player);
            else finishBuild();
            return;
        }

        Vec3 target = queue.get(step);
        Vec3 support = target.add(0, -1, 0);
        Vec3 aimPoint = support.add(0.5, 1.0, 0.5);
        float[] rot = getRotations(aimPoint);
        boolean top = isTop(target);

        if (speedBuild.getValue()) {
            placeFast(player, target, support, rot, top);
            return;
        }

        if (aimStep != step) {
            setRotationTarget(rot[0], rot[1]);
            aimStep = step;
        }

        if (top && jumped) {
            aimAt(aimPoint);
            if (clientTime() - jumpStart < 150) return;
            if (clientTime() - jumpStart > 3000) {
                prompt("Placement failed");
                resetBuild();
                return;
            }
            if (!throughWall.getValue() && !canPlace(support)) return;
            if (hostPlaceBlock(support, "UP", new Vec3(0.5, 1.0, 0.5))) {
                player.swing(InteractionHand.MAIN_HAND);
                verifyPos = target;
                verifyStart = clientTime();
                debugLog("Placed " + (step + 1) + "/" + queue.size());
            }
            return;
        }

        if (rotActive || !aimClose(rot[0], rot[1])) {
            placeStart = 0;
            return;
        }

        if (top) {
            player.jumpFromGround();
            jumped = true;
            jumpStart = clientTime();
            return;
        }

        if (placeStart == 0) placeStart = clientTime();
        if (clientTime() - placeStart < placementDelay()) return;
        if (clientTime() - placeStart > 3000) {
            prompt("Placement failed");
            resetBuild();
            return;
        }

        if (!throughWall.getValue() && !canPlace(support)) return;
        if (hostPlaceBlock(support, "UP", new Vec3(0.5, 1.0, 0.5))) {
            player.swing(InteractionHand.MAIN_HAND);
            verifyPos = target;
            verifyStart = clientTime();
            debugLog("Placed " + (step + 1) + "/" + queue.size());
        }
    }

    // ===== 脚本 481-519 行：快速搭建 =====

    private void placeFast(LocalPlayer player, Vec3 target, Vec3 support, float[] rot, boolean top) {
        aimYaw = rot[0];
        aimPitch = rot[1];
        if (!silentAim.getValue()) {
            player.setYRot(aimYaw);
            player.setXRot(aimPitch);
        }

        if (top) {
            if (!jumped) {
                player.jumpFromGround();
                jumped = true;
                jumpStart = clientTime();
                return;
            }
            if (clientTime() - jumpStart < 100) return;
            if (clientTime() - jumpStart > 3000) {
                prompt("Placement failed");
                resetBuild();
                return;
            }
            if (!throughWall.getValue() && !canPlace(support)) return;
            if (hostPlaceBlock(support, "UP", new Vec3(0.5, 1.0, 0.5))) {
                player.swing(InteractionHand.MAIN_HAND);
                step++;
                placeStart = 0;
                debugLog("Placed " + step + "/" + queue.size());
            }
            return;
        }

        if (!throughWall.getValue() && !canPlace(support)) return;
        if (hostPlaceBlock(support, "UP", new Vec3(0.5, 1.0, 0.5))) {
            player.swing(InteractionHand.MAIN_HAND);
            step++;
            placeStart = 0;
            debugLog("Placed " + step + "/" + queue.size());
        }
    }

    // ===== 脚本 521-557 行：瞄准与验证 =====

    private void aimAt(Vec3 point) {
        LocalPlayer player = mc.player;
        if (player == null) return;
        float[] r = getRotations(point);
        aimYaw = r[0];
        aimPitch = r[1];
        if (!silentAim.getValue()) {
            player.setYRot(aimYaw);
            player.setXRot(aimPitch);
        }
    }

    private boolean canPlace(Vec3 support) {
        Object[] hit = hostRaycast(4.5, aimYaw, aimPitch);
        if (hit == null) return false;
        Vec3 block = (Vec3) hit[0];
        return Math.floor(block.x) == Math.floor(support.x)
                && Math.floor(block.y) == Math.floor(support.y)
                && Math.floor(block.z) == Math.floor(support.z);
    }

    private void verifyPlaced() {
        if (verifyPos == null) return;
        if (clientTime() - verifyStart < verifyDelay()) return;
        if (isAir(verifyPos)) {
            prompt("Flag (block not placed)");
            resetBuild();
            return;
        }
        verifyPos = null;
        step++;
        placeStart = 0;
    }

    private boolean aimClose(float targetYaw, float targetPitch) {
        return Math.abs(normalizeYawDelta(aimYaw - targetYaw)) < 1.0f
                && Math.abs(aimPitch - targetPitch) < 1.0f;
    }

    /** 脚本 560 行作者串（同 {@link #_c0}，原样保留）。 */
    private static final char[] _c1 = {'M', 'o', 'n', 's', 't', 'e', 'r', '_', 'S', 'a', 'n', 'g'};

    // ===== 脚本 562-586 行：开始挖掘 =====

    private void startDig(LocalPlayer player) {
        if (middleBlock == null) {
            finishBuild();
            return;
        }
        digOriginalSlot = selectedSlot();
        String tool = toolForBlock(buildBlockName);
        digSlot = tool.isEmpty() ? -1 : findToolSlot(tool);
        if (digSlot < 0) digSlot = findEmptySlot();
        if (digSlot >= 0) setSelectedSlot(digSlot);
        digStage = 0;
        digSentAt = 0;
        rotActive = false;
        aimStep = -1;
        digStart = clientTime();
        phase = PHASE_DIG;
        debugLog("Perfect: digging");
    }

    private Vec3 digAimPoint() {
        if (buildFz == 1) return middleBlock.add(0.5, 0.5, 0.0);
        if (buildFz == -1) return middleBlock.add(0.5, 0.5, 1.0);
        if (buildFx == 1) return middleBlock.add(0.0, 0.5, 0.5);
        return middleBlock.add(1.0, 0.5, 0.5);
    }

    // ===== 脚本 588-629 行：挖掘相位 =====

    private void digPhase(LocalPlayer player) {
        Vec3 aimPoint = digAimPoint();
        float[] r = getRotations(aimPoint);
        aimYaw = r[0];
        aimPitch = r[1];
        if (!silentAim.getValue()) {
            player.setYRot(aimYaw);
            player.setXRot(aimPitch);
        }

        if (clientTime() - digStart > 5000) {
            stopDig();
            prompt("Dig failed");
            finishBuild();
            return;
        }

        if (!player.onGround()) return;

        if (silentAim.getValue()) {
            // Silent mining: the client crosshair is not rotated, so pressing "attack"
            // would hit whatever the player is looking at. Send the dig packets for the
            // target block directly instead, keeping the local view unchanged.
            if (digStage == 0) {
                sendC07(middleBlock, "START_DESTROY_BLOCK", digFacing());
                player.swing(InteractionHand.MAIN_HAND);
                digStage = 1;
                digSentAt = clientTime();
                debugLog("Perfect: mining");
            } else if (digStage == 1 && clientTime() - digSentAt >= digDelay()) {
                sendC07(middleBlock, "STOP_DESTROY_BLOCK", digFacing());
                digStage = 2;
            }
        } else {
            mc.options.keyAttack.setDown(true);
        }

        if (isAir(middleBlock)) {
            stopDig();
            finishBuild();
        }
    }

    // ===== 脚本 631-660 行：停止挖掘 =====

    private void stopDig() {
        mc.options.keyAttack.setDown(false);
        abortSilentDig();
        if (digSlot >= 0 && digOriginalSlot >= 0 && selectedSlot() == digSlot) {
            setSelectedSlot(digOriginalSlot);
        }
    }

    private void abortSilentDig() {
        if (digStage == 1 && middleBlock != null) {
            sendC07(middleBlock, "ABORT_DESTROY_BLOCK", digFacing());
        }
        digStage = 0;
    }

    private String digFacing() {
        if (buildFz == 1) return "NORTH";
        if (buildFz == -1) return "SOUTH";
        if (buildFx == 1) return "WEST";
        return "EAST";
    }

    private long digDelay() {
        String tool = toolForBlock(buildBlockName);
        if (tool.equals("shears")) return 150;
        if (tool.equals("axe")) return 300;
        if (tool.equals("shovel")) return 250;
        if (tool.equals("pickaxe")) return 500;
        return 400;
    }

    // ===== 脚本 662-691 行：收尾与重置 =====

    private void finishBuild() {
        if (autoSwitch.getValue() && previousSlot >= 0 && previousSlot != selectedSlot()) {
            setSelectedSlot(previousSlot);
        }
        resetBuild();
        debugSuccess("Build complete");
    }

    private void resetBuild() {
        building = false;
        phase = PHASE_IDLE;
        step = 0;
        buildSlot = -1;
        placeStart = 0;
        jumped = false;
        jumpStart = 0;
        walking = false;
        aimStep = -1;
        rotActive = false;
        verifyPos = null;
        queue.clear();
        releaseMovement();
        mc.options.keyAttack.setDown(false);
        abortSilentDig();
        digStage = 0;
        digSentAt = 0;
        digSlot = -1;
        digOriginalSlot = -1;
        digStart = 0;
    }

    // ===== 脚本 693-718 行：目标队列 =====

    private void buildQueue(LocalPlayer player) {
        queue.clear();
        Vec3 feet = floor(player.position());
        float yaw = normalizeYaw(player.getYRot());
        int dir = (int) (Math.round(yaw / 90.0f) * 90.0f) % 360;

        int fx = forwardX(dir);
        int fz = forwardZ(dir);
        int rx = rightX(dir);
        int rz = rightZ(dir);

        double mx = feet.x + fx;
        double my = feet.y;
        double mz = feet.z + fz;

        buildBaseY = my;
        middleBlock = new Vec3(mx, my, mz);
        buildFx = fx;
        buildFz = fz;

        queue.add(new Vec3(mx - rx, my, mz - rz));
        queue.add(new Vec3(mx, my, mz));
        queue.add(new Vec3(mx + rx, my, mz + rz));
        queue.add(new Vec3(mx, my + 1, mz));
        queue.add(new Vec3(mx, my + 2, mz));
    }

    // ===== 脚本 720-756 行：区域检查 =====

    private boolean areaClear(List<Vec3> full) {
        Vec3 middle = full.get(1);
        for (int dx = -1; dx <= 1; dx++) {
            for (int dz = -1; dz <= 1; dz++) {
                Vec3 p = middle.add(dx, 0, dz);
                if (isTarget(full, p)) continue;
                if (!isAir(p)) return false;
            }
        }
        Vec3 up1 = middle.add(0, 1, 0);
        Vec3 up2 = middle.add(0, 2, 0);
        if (!isTarget(full, up1) && !isAir(up1)) return false;
        if (!isTarget(full, up2) && !isAir(up2)) return false;
        return true;
    }

    private boolean isTarget(List<Vec3> full, Vec3 pos) {
        for (Vec3 q : full) {
            if (Math.floor(q.x) == Math.floor(pos.x)
                    && Math.floor(q.y) == Math.floor(pos.y)
                    && Math.floor(q.z) == Math.floor(pos.z)) {
                return true;
            }
        }
        return false;
    }

    private boolean isTop(Vec3 target) {
        return Math.floor(target.y) == Math.floor(buildBaseY) + 2;
    }

    private boolean isAir(Vec3 pos) {
        String n = normalize(blockNameAt(pos));
        return n.equals("air") || n.equals("cave_air") || n.equals("void_air");
    }

    // ===== 脚本 758-829 行：选槽 =====

    private int findBlockSlot() {
        int best = -1;
        int bestCount = 0;
        for (int i = 0; i < 9; i++) {
            ItemStack s = hotbarStack(i);
            if (s.isEmpty() || !(s.getItem() instanceof BlockItem)) continue;
            if (!isPlaceable(s)) continue;
            if (s.getCount() > bestCount) {
                best = i;
                bestCount = s.getCount();
            }
        }
        return best;
    }

    private boolean isPlaceable(ItemStack s) {
        String n = normalize(itemName(s));
        if (n.equals("air")) return false;
        if (n.contains("slab")) return false;
        if (n.contains("stairs")) return false;
        for (String bad : UNPLACEABLE) {
            if (n.contains(bad)) return false;
        }
        return true;
    }

    private String toolForBlock(String blockName) {
        if (blockName.contains("wool") || blockName.contains("leaves") || blockName.contains("cobweb")
                || blockName.contains("vine") || blockName.contains("fern") || blockName.contains("grass")) {
            return "shears";
        }
        if (blockName.contains("log") || blockName.contains("wood") || blockName.contains("plank")
                || blockName.contains("fence") || blockName.contains("bamboo")) {
            return "axe";
        }
        if (blockName.contains("dirt") || blockName.contains("sand") || blockName.contains("gravel")
                || blockName.contains("clay") || blockName.contains("snow") || blockName.contains("soul")) {
            return "shovel";
        }
        if (blockName.contains("stone") || blockName.contains("cobble") || blockName.contains("ore")
                || blockName.contains("deepslate") || blockName.contains("granite") || blockName.contains("diorite")
                || blockName.contains("andesite") || blockName.contains("copper") || blockName.contains("iron")
                || blockName.contains("gold") || blockName.contains("diamond") || blockName.contains("netherite")
                || blockName.contains("concrete") || blockName.contains("obsidian") || blockName.contains("netherrack")
                || blockName.contains("basalt") || blockName.contains("quartz")) {
            return "pickaxe";
        }
        return "";
    }

    private int findToolSlot(String tool) {
        for (int i = 0; i < 9; i++) {
            ItemStack s = hotbarStack(i);
            if (s.isEmpty()) continue;
            String n = normalize(itemName(s));
            if ("axe".equals(tool)) {
                if (n.contains("axe") && !n.contains("pickaxe")) return i;
            } else if (n.contains(tool)) {
                return i;
            }
        }
        return -1;
    }

    private int findEmptySlot() {
        for (int i = 0; i < 9; i++) {
            ItemStack s = hotbarStack(i);
            if (s.isEmpty()) return i;
            if (normalize(itemName(s)).equals("air")) return i;
        }
        return -1;
    }

    // ===== 脚本 841-887 行：方向与角度工具 =====

    private String normalize(String name) {
        if (name == null) return "";
        if (name.startsWith("minecraft:")) return name.substring(10);
        return name;
    }

    private int forwardX(int dir) {
        if (dir == 90) return -1;
        if (dir == 270) return 1;
        return 0;
    }

    private int forwardZ(int dir) {
        if (dir == 0) return 1;
        if (dir == 180) return -1;
        return 0;
    }

    private int rightX(int dir) {
        if (dir == 0) return -1;
        if (dir == 180) return 1;
        return 0;
    }

    private int rightZ(int dir) {
        if (dir == 90) return -1;
        if (dir == 270) return 1;
        return 0;
    }

    private float normalizeYaw(float yaw) {
        while (yaw >= 360.0f) yaw -= 360.0f;
        while (yaw < 0.0f) yaw += 360.0f;
        return yaw;
    }

    private float normalizeYawDelta(float d) {
        while (d > 180.0f) d -= 360.0f;
        while (d < -180.0f) d += 360.0f;
        return d;
    }

    private float clamp(float value, float min, float max) {
        if (value < min) return min;
        if (value > max) return max;
        return value;
    }

    // ===== 脚本 889-912 行：提示与调试输出 =====

    private void prompt(String msg) {
        long now = clientTime();
        if (now - lastPrompt < 1000) return;
        lastPrompt = now;
        clientPrint("&7[&dAutoBuild&7]&r &c" + msg);
    }

    private void debugLog(String msg) {
        if (debug.getValue()) {
            clientPrint("&7[&dAutoBuild&7]&r &7" + colorNumbers(msg, "&7"));
        }
    }

    private void debugSuccess(String msg) {
        if (debug.getValue()) {
            clientPrint("&7[&dAutoBuild&7]&r &a" + colorNumbers(msg, "&a"));
        }
    }

    private void debugError(String msg) {
        if (debug.getValue()) {
            clientPrint("&7[&dAutoBuild&7]&r &c" + colorNumbers(msg, "&c"));
        }
    }

    private String colorNumbers(String msg, String base) {
        StringBuilder sb = new StringBuilder();
        boolean inNumber = false;
        for (int i = 0; i < msg.length(); i++) {
            char c = msg.charAt(i);
            boolean digit = c >= '0' && c <= '9';
            if (digit && !inNumber) {
                sb.append("&b");
                inNumber = true;
            } else if (!digit && inNumber) {
                sb.append(base);
                inNumber = false;
            }
            sb.append(c);
        }
        if (inNumber) sb.append(base);
        return sb.toString();
    }

    /** 脚本 933-935 行作者串（原样保留）。 */
    private String _c2() {
        return new String(new char[]{'M', 'o', 'n', 's', 't', 'e', 'r', '_', 'S', 'a', 'n', 'g'});
    }

    // =====================================================================
    // 宿主替换层：1.8.9 脚本宿主 API 的 26.2 等价物
    // =====================================================================

    private long clientTime() {
        return System.currentTimeMillis();
    }

    /** 脚本 {@code client.setForward(v)}（键盘输入事件承载；脚本里并行的 keybinds.setPressed 与之同义）。 */
    private void setClientForward(float forward) {
        if (activeInputEvent != null) activeInputEvent.setForward(forward);
    }

    /** 脚本 {@code client.setStrafe(v)}。 */
    private void setClientStrafe(float strafe) {
        if (activeInputEvent != null) activeInputEvent.setStrafe(strafe);
    }

    /** 脚本 {@code client.getForward()}：本 tick 玩家输入（可能已被同刻其它模块改写）。 */
    private float clientForward() {
        return activeInputEvent == null ? 0.0f : activeInputEvent.getForward();
    }

    /** 脚本 {@code client.getStrafe()}。 */
    private float clientStrafe() {
        return activeInputEvent == null ? 0.0f : activeInputEvent.getStrafe();
    }

    /** 脚本 {@code client.getRotations(Vec3)}：返回 {yaw, pitch}。 */
    private float[] getRotations(Vec3 point) {
        Rot2f rot = RotationUtils.calculate(point);
        return new float[]{rot.getYaw(), rot.getPitch()};
    }

    /** 脚本 {@code client.raycastBlock(distance, yaw, pitch)}：返回 {支撑块坐标 Vec3, 面内偏移 Vec3, 面名 String}。 */
    private Object[] hostRaycast(double reach, float yaw, float pitch) {
        LocalPlayer player = mc.player;
        if (player == null || mc.level == null) return null;
        Vec3 eyes = getEyes(player);
        Vec3 to = eyes.add(getLookVec(yaw, pitch).scale(reach));
        BlockHitResult hit = mc.level.clip(new ClipContext(eyes, to, ClipContext.Block.OUTLINE, ClipContext.Fluid.NONE, player));
        if (hit == null || hit.getType() == HitResult.Type.MISS) return null;
        BlockPos blockPos = hit.getBlockPos();
        Vec3 localOffset = hit.getLocation().subtract(blockPos.getX(), blockPos.getY(), blockPos.getZ());
        return new Object[]{
                new Vec3(blockPos.getX(), blockPos.getY(), blockPos.getZ()),
                localOffset,
                faceName(hit.getDirection().get3DDataValue())
        };
    }

    /**
     * 脚本 {@code client.placeBlock(support, face, 面内偏移)}。
     * 第三参数与脚本一致是<b>面内偏移</b>（相对角点），内部再折算成绝对命中点。
     */
    private boolean hostPlaceBlock(Vec3 support, String face, Vec3 offsetInBlock) {
        LocalPlayer player = mc.player;
        if (player == null || mc.gameMode == null) return false;
        int f = faceFromName(face);
        if (f < 0) return false;
        Vec3 hitAbs = support.add(offsetInBlock);
        BlockHitResult result = new BlockHitResult(hitAbs, Direction.from3DDataValue(f),
                new BlockPos((int) Math.floor(support.x), (int) Math.floor(support.y), (int) Math.floor(support.z)), false);
        InteractionResult interaction = mc.gameMode.useItemOn(player, InteractionHand.MAIN_HAND, result);
        return interaction != null && interaction.consumesAction();
    }

    /** 脚本 {@code new C07(pos, action, facing)}（C07 = PlayerAction 包）。 */
    private void sendC07(Vec3 pos, String action, String facing) {
        if (mc.getConnection() == null || pos == null) return;
        ServerboundPlayerActionPacket.Action act = switch (action) {
            case "START_DESTROY_BLOCK" -> ServerboundPlayerActionPacket.Action.START_DESTROY_BLOCK;
            case "STOP_DESTROY_BLOCK" -> ServerboundPlayerActionPacket.Action.STOP_DESTROY_BLOCK;
            case "ABORT_DESTROY_BLOCK" -> ServerboundPlayerActionPacket.Action.ABORT_DESTROY_BLOCK;
            default -> null;
        };
        if (act == null) return;
        // 必须绕过发包事件管线：普通 send 会重入 MixinConnection → 再派发一次 PacketEvent.Send，
        // 而 Blink/Telly 等会在那次嵌套派发里把 DESTROY 动作包取消掉（静默挖掘被吞）。
        NetworkUtils.sendPacketNoEvent(new ServerboundPlayerActionPacket(act, BlockPos.containing(pos),
                Direction.from3DDataValue(faceFromName(facing))));
    }

    private Vec3 getEyes(LocalPlayer player) {
        Vec3 pos = player.position();
        return new Vec3(pos.x, pos.y + player.getEyeHeight(), pos.z);
    }

    private Vec3 getLookVec(float yaw, float pitch) {
        double yawRad = Math.toRadians(yaw);
        double pitchRad = Math.toRadians(pitch);
        double cosPitch = Math.cos(pitchRad);
        return new Vec3(-Math.sin(yawRad) * cosPitch, -Math.sin(pitchRad), Math.cos(yawRad) * cosPitch);
    }

    /** 脚本 {@code Vec3.floor()}（26.2 的 Vec3 没有该方法）。 */
    private Vec3 floor(Vec3 pos) {
        return new Vec3(Math.floor(pos.x), Math.floor(pos.y), Math.floor(pos.z));
    }

    /** 脚本 {@code world.getBlockAt(pos).name}（1.13+ 注册名，已去命名空间）。 */
    private String blockNameAt(Vec3 pos) {
        if (mc.level == null) return "air";
        BlockState state = mc.level.getBlockState(
                new BlockPos((int) Math.floor(pos.x), (int) Math.floor(pos.y), (int) Math.floor(pos.z)));
        return BuiltInRegistries.BLOCK.getKey(state.getBlock()).getPath().toLowerCase(Locale.ROOT);
    }

    /** 脚本 {@code inventory.getSlot()}。 */
    private int selectedSlot() {
        return mc.player == null ? -1 : mc.player.getInventory().getSelectedSlot();
    }

    /** 脚本 {@code inventory.setSlot(i)}。 */
    private void setSelectedSlot(int slot) {
        if (mc.player != null) mc.player.getInventory().setSelectedSlot(slot);
    }

    /** 脚本 {@code inventory.getStackInSlot(i)}（空槽 = 空栈）。 */
    private ItemStack hotbarStack(int slot) {
        if (mc.player == null) return ItemStack.EMPTY;
        return mc.player.getInventory().getItem(slot);
    }

    /** 脚本 {@code stack.name}。 */
    private String itemName(ItemStack stack) {
        if (stack.isEmpty()) return "air";
        return BuiltInRegistries.ITEM.getKey(stack.getItem()).getPath().toLowerCase(Locale.ROOT);
    }

    private String faceName(int face) {
        if (face == 0) return "DOWN";
        if (face == 1) return "UP";
        if (face == 2) return "NORTH";
        if (face == 3) return "SOUTH";
        if (face == 4) return "WEST";
        return "EAST";
    }

    private int faceFromName(String name) {
        if (name == null) return -1;
        String upper = name.toUpperCase(Locale.ROOT);
        if (upper.equals("DOWN")) return 0;
        if (upper.equals("UP")) return 1;
        if (upper.equals("NORTH")) return 2;
        if (upper.equals("SOUTH")) return 3;
        if (upper.equals("WEST")) return 4;
        if (upper.equals("EAST")) return 5;
        return -1;
    }

    /** 脚本 {@code client.print(util.color(...))}：脚本消息自带 {@code [AutoBuild]} 前缀与颜色，故不加 Epsilon 前缀。 */
    private void clientPrint(String message) {
        ChatUtils.addChatMessage(false, styled(message));
    }

    /** 把脚本里的 &X 颜色码转成 {@link ChatFormatting} 组件（'&' 后跟 0-9a-fk-or 之一才生效）。 */
    private Component styled(String text) {
        MutableComponent out = Component.empty();
        ChatFormatting format = null;
        StringBuilder buf = new StringBuilder();
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (c == '&' && i + 1 < text.length()) {
                ChatFormatting f = ChatFormatting.getByCode(Character.toLowerCase(text.charAt(i + 1)));
                if (f != null) {
                    if (buf.length() > 0) {
                        out.append(applyStyle(buf.toString(), format));
                        buf.setLength(0);
                    }
                    format = f;
                    i++;
                    continue;
                }
            }
            buf.append(c);
        }
        out.append(applyStyle(buf.toString(), format));
        return out;
    }

    private MutableComponent applyStyle(String s, ChatFormatting format) {
        return format == null ? Component.literal(s) : Component.literal(s).withStyle(format);
    }

}
