package com.github.epsilon.ysm.model;

import com.github.epsilon.ysm.resource.pojo.RawYsmModel;
import com.github.epsilon.ysm.compat.TaczCompat;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Locale;
import java.util.concurrent.atomic.AtomicBoolean;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.tags.ItemTags;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.Pose;
import net.minecraft.world.item.*;

/** Lightweight sampler for the animation data embedded in an OpenYSM model. */
public final class YsmAnimationPlayer {
    private static final float TRANSITION_TICKS = 2.0f;
    private static final ClayGunIndex CLAY_GUNS = new ClayGunIndex();
    private final Map<String, Clip> clips = new LinkedHashMap<>();
    private final int boneCount;
    private final float[] rotationX;
    private final float[] rotationY;
    private final float[] rotationZ;
    private final float[] positionX;
    private final float[] positionY;
    private final float[] positionZ;
    private final float[] scaleX;
    private final float[] scaleY;
    private final float[] scaleZ;
    private final float[] lastRotationX;
    private final float[] lastRotationY;
    private final float[] lastRotationZ;
    private final float[] lastPositionX;
    private final float[] lastPositionY;
    private final float[] lastPositionZ;
    private final float[] lastScaleX;
    private final float[] lastScaleY;
    private final float[] lastScaleZ;
    private final float[] transitionRotationX;
    private final float[] transitionRotationY;
    private final float[] transitionRotationZ;
    private final float[] transitionPositionX;
    private final float[] transitionPositionY;
    private final float[] transitionPositionZ;
    private final float[] transitionScaleX;
    private final float[] transitionScaleY;
    private final float[] transitionScaleZ;
    private final float[] locomotionRotationX;
    private final float[] locomotionRotationY;
    private final float[] locomotionRotationZ;
    private final float[] locomotionPositionX;
    private final float[] locomotionPositionY;
    private final float[] locomotionPositionZ;
    private final float[] locomotionScaleX;
    private final float[] locomotionScaleY;
    private final float[] locomotionScaleZ;
    private final boolean[] lowerBodyBones;
    /** True when this player samples the dedicated first-person arm clips. */
    private final boolean firstPerson;
    private final float[] sample = new float[3];
    private String currentClip = "";
    private float clipStartTick;
    private float transitionStartTick;
    private boolean hasPreviousFrame;
    /** Active use/swing clip and its own clock, independent from attackAnim. */
    private String currentActionClip = "";
    private float actionStartTick;
    private boolean actionWasActive;
    /** YSM 头发/布料物理（molang 的 ysm.first_order/second_order）。 */
    private final YsmPhysics physics = new YsmPhysics();
    private final java.util.Map<String, Object> molangVars = new java.util.HashMap<>();
    private final MolangContext molangContext = new MolangContext();
    private float lastPhysicsTick = Float.NaN;

    public YsmAnimationPlayer(RawYsmModel raw, Map<String, Integer> boneIndex) {
        this(raw, boneIndex, false);
    }

    public YsmAnimationPlayer(RawYsmModel raw, Map<String, Integer> boneIndex, boolean firstPerson) {
        this.firstPerson = firstPerson;
        this.boneCount = boneIndex.size();
        this.rotationX = new float[this.boneCount];
        this.rotationY = new float[this.boneCount];
        this.rotationZ = new float[this.boneCount];
        this.positionX = new float[this.boneCount];
        this.positionY = new float[this.boneCount];
        this.positionZ = new float[this.boneCount];
        this.scaleX = new float[this.boneCount];
        this.scaleY = new float[this.boneCount];
        this.scaleZ = new float[this.boneCount];
        this.lastRotationX = new float[this.boneCount];
        this.lastRotationY = new float[this.boneCount];
        this.lastRotationZ = new float[this.boneCount];
        this.lastPositionX = new float[this.boneCount];
        this.lastPositionY = new float[this.boneCount];
        this.lastPositionZ = new float[this.boneCount];
        this.lastScaleX = ones(this.boneCount);
        this.lastScaleY = ones(this.boneCount);
        this.lastScaleZ = ones(this.boneCount);
        this.transitionRotationX = new float[this.boneCount];
        this.transitionRotationY = new float[this.boneCount];
        this.transitionRotationZ = new float[this.boneCount];
        this.transitionPositionX = new float[this.boneCount];
        this.transitionPositionY = new float[this.boneCount];
        this.transitionPositionZ = new float[this.boneCount];
        this.transitionScaleX = ones(this.boneCount);
        this.transitionScaleY = ones(this.boneCount);
        this.transitionScaleZ = ones(this.boneCount);
        this.locomotionRotationX = new float[this.boneCount];
        this.locomotionRotationY = new float[this.boneCount];
        this.locomotionRotationZ = new float[this.boneCount];
        this.locomotionPositionX = new float[this.boneCount];
        this.locomotionPositionY = new float[this.boneCount];
        this.locomotionPositionZ = new float[this.boneCount];
        this.locomotionScaleX = ones(this.boneCount);
        this.locomotionScaleY = ones(this.boneCount);
        this.locomotionScaleZ = ones(this.boneCount);
        this.lowerBodyBones = new boolean[this.boneCount];
        for (Map.Entry<String, Integer> entry : boneIndex.entrySet()) {
            String name = entry.getKey() == null ? "" : entry.getKey().toLowerCase(Locale.ROOT);
            this.lowerBodyBones[entry.getValue()] = name.contains("leg")
                    || name.contains("thigh") || name.contains("calf")
                    || name.contains("knee") || name.contains("foot")
                    || name.contains("feet");
        }
        if (raw != null && raw.mainEntity != null) {
            for (RawYsmModel.RawAnimationFile file : raw.mainEntity.animationFiles.values()) {
                // The arm geometry has its own animation namespace.  Main-body clips
                // happen to use many of the same names, so mixing them makes the
                // first-person hand silently select the wrong pose.
                if (this.firstPerson && file.animType != 2 && file.animType != 4 && file.animType != 11) {
                    continue;
                }
                for (Map.Entry<String, RawYsmModel.RawAnimation> entry : file.animations.entrySet()) {
                    // The JSON key is the controller-facing animation id.  A
                    // number of OpenYSM exports leave RawAnimation.name empty;
                    // using that empty field made every non-looping action
                    // share one clock and left swing clips sampled at their
                    // final frame forever.
                    Clip clip = Clip.build(entry.getKey(), entry.getValue(), boneIndex, !this.firstPerson);
                    this.clips.putIfAbsent(entry.getKey(), clip);
                    String namespacedAlias = compactAnimationKey(entry.getKey());
                    if (namespacedAlias != null) {
                        this.clips.putIfAbsent(namespacedAlias, clip);
                    }
                    this.clips.putIfAbsent(shortName(entry.getKey()), clip);
                    if (entry.getValue().name != null) {
                        this.clips.putIfAbsent(entry.getValue().name, clip);
                        namespacedAlias = compactAnimationKey(entry.getValue().name);
                        if (namespacedAlias != null) {
                            this.clips.putIfAbsent(namespacedAlias, clip);
                        }
                        this.clips.putIfAbsent(shortName(entry.getValue().name), clip);
                    }
                }
            }
        }
    }

    public boolean hasAnimations() {
        return !this.clips.isEmpty();
    }

    public void reset() {
        this.currentClip = "";
        this.currentActionClip = "";
        this.actionWasActive = false;
        this.hasPreviousFrame = false;
        this.physics.clear();
        this.molangVars.clear();
        this.lastPhysicsTick = Float.NaN;
    }

    public boolean apply(LocalPlayer player, float partialTick,
                         float[] outRotationX, float[] outRotationY, float[] outRotationZ,
                         float[] outPositionX, float[] outPositionY, float[] outPositionZ,
                         float[] outScaleX, float[] outScaleY, float[] outScaleZ) {
        String selectedState = this.selectState(player, partialTick);
        Clip clip = this.firstPerson ? this.findOptionalClip(selectedState) : this.findClip(selectedState);
        if (clip == null) {
            return false;
        }
        float nowTick = player.tickCount + partialTick;
        // 物理按真实时间推进（每帧一步）
        float dtFrame = (this.lastPhysicsTick == this.lastPhysicsTick)
                ? Math.max(0.0f, Math.min(0.1f, (nowTick - this.lastPhysicsTick) / 20.0f)) : 0.0f;
        this.lastPhysicsTick = nowTick;
        this.physics.update(dtFrame);
        if (!clip.name.equals(this.currentClip)) {
            this.captureTransitionSource();
            this.currentClip = clip.name;
            this.clipStartTick = nowTick;
            this.transitionStartTick = nowTick;
        }
        this.clearCurrentPose();
        float elapsedSeconds = Math.max(0.0f, nowTick - this.clipStartTick) / 20.0f;
        MolangContext ctx = this.molangContext.begin(player, elapsedSeconds);
        clip.sample(elapsedSeconds, this.rotationX, this.rotationY, this.rotationZ,
                this.positionX, this.positionY, this.positionZ,
                this.scaleX, this.scaleY, this.scaleZ, this.sample, ctx);
        this.applyActionAnimation(player, partialTick, ctx);
        // TACZ/Clay clips describe the weapon and upper body, but many YSM
        // exports contain static leg tracks in those clips. Restore the
        // locomotion tracks so holding a gun never freezes both legs.
        if (isTaczGun(player.getMainHandItem()) && selectedState != null
                && selectedState.startsWith("tac:")) {
            this.restoreLocomotionLegs(player, partialTick, ctx);
        }

        float blend = this.hasPreviousFrame
                ? clamp((nowTick - this.transitionStartTick) / TRANSITION_TICKS, 0.0f, 1.0f)
                : 1.0f;
        for (int i = 0; i < this.boneCount; i++) {
            float rx = lerp(this.transitionRotationX[i], this.rotationX[i], blend);
            float ry = lerp(this.transitionRotationY[i], this.rotationY[i], blend);
            float rz = lerp(this.transitionRotationZ[i], this.rotationZ[i], blend);
            float px = lerp(this.transitionPositionX[i], this.positionX[i], blend);
            float py = lerp(this.transitionPositionY[i], this.positionY[i], blend);
            float pz = lerp(this.transitionPositionZ[i], this.positionZ[i], blend);
            float sx = lerp(this.transitionScaleX[i], this.scaleX[i], blend);
            float sy = lerp(this.transitionScaleY[i], this.scaleY[i], blend);
            float sz = lerp(this.transitionScaleZ[i], this.scaleZ[i], blend);
            outRotationX[i] += rx;
            outRotationY[i] += ry;
            outRotationZ[i] += rz;
            outPositionX[i] = px;
            outPositionY[i] = py;
            outPositionZ[i] = pz;
            outScaleX[i] = sx;
            outScaleY[i] = sy;
            outScaleZ[i] = sz;
            this.lastRotationX[i] = rx;
            this.lastRotationY[i] = ry;
            this.lastRotationZ[i] = rz;
            this.lastPositionX[i] = px;
            this.lastPositionY[i] = py;
            this.lastPositionZ[i] = pz;
            this.lastScaleX[i] = sx;
            this.lastScaleY[i] = sy;
            this.lastScaleZ[i] = sz;
        }
        this.hasPreviousFrame = true;
        return true;
    }

    private void applyActionAnimation(LocalPlayer player, float partialTick, MolangContext ctx) {
        // TACZ fire/reload/melee clips already contain their own hand action.
        // A passive TACZ hold/aim clip does not, so it must still be allowed to
        // fall through to the model's ordinary third-person swing clip.
        String taczState = isTaczGun(player.getMainHandItem())
                ? this.selectTaczState(player, partialTick) : null;
        if (this.isTaczActionState(taczState)) {
            this.actionWasActive = false;
            this.currentActionClip = "";
            return;
        }
        // Third-person OpenYSM locomotion is combined with the vanilla swing
        // delta by YsmModel. Sampling swing_hand here would apply a second,
        // model-authored attack on top of that direct hand swing.
        if (!this.firstPerson && !player.isUsingItem()
                && (player.swinging || player.getAttackAnim(partialTick) > 0.001f)) {
            this.actionWasActive = false;
            this.currentActionClip = "";
            return;
        }
        Clip action = null;
        float nowTick = player.tickCount + partialTick;
        if (player.isUsingItem()) {
            boolean mainHand = player.getUsedItemHand() == InteractionHand.MAIN_HAND;
            String prefix = mainHand ? "mainhand" : "offhand";
            String useName = this.firstClip(
                    "use_" + prefix + ":" + this.firstPersonItemName(mainHand
                            ? player.getMainHandItem() : player.getOffhandItem()),
                    "use_" + prefix, "use", "interact_" + prefix,
                    "interact", "place_" + prefix, "place");
            action = useName == null ? null : this.findOptionalClip(useName);
        } else {
            float attack = player.getAttackAnim(partialTick);
            // Placement and interaction also call LivingEntity.swing(), but at
            // the first rendered tick getAttackAnim() can still be zero. Use
            // the canonical swing flag so those actions enter the OpenYSM clip.
            if (player.swinging || attack > 0.001f) {
                InteractionHand swingHand = player.swingingArm == InteractionHand.OFF_HAND
                        ? InteractionHand.OFF_HAND : InteractionHand.MAIN_HAND;
                String swingPrefix = swingHand == InteractionHand.OFF_HAND
                        ? "swing_offhand" : "swing";
                // OpenYSM exports item-specific clips (for example
                // swing:minecraft:mace and swing:sword) instead of the old
                // generic swing_hand name. Prefer the specific item and keep
                // generic aliases for older models.
                String itemName = this.firstPersonItemName(swingHand == InteractionHand.OFF_HAND
                        ? player.getOffhandItem() : player.getMainHandItem());
                String itemSuffix = itemName == null ? "" : itemName;
                String actionName = this.firstClip(swingPrefix + ":" + itemSuffix,
                        swingPrefix + ":sword", swingPrefix + ":mace", swingPrefix,
                        swingHand == InteractionHand.OFF_HAND ? "swing_offhand" : "swing_hand");
                action = actionName == null ? null : this.findOptionalClip(actionName);
            }
        }
        // OpenYSM's swing controller owns a PLAY_ONCE clip after the swing
        // predicate fires.  Minecraft clears `swinging` before a longer YSM
        // clip has finished, so keep the third-person action clock alive until
        // that clip reaches its end instead of snapping back at six ticks.
        if (action == null && !this.firstPerson && !this.currentActionClip.isEmpty()
                && this.currentActionClip.toLowerCase(Locale.ROOT).startsWith("swing")) {
            Clip continuing = this.findOptionalClip(this.currentActionClip);
            if (continuing != null) {
                float elapsed = Math.max(0.0f, nowTick - this.actionStartTick) / 20.0f;
                if (elapsed < continuing.length) {
                    action = continuing;
                }
            }
        }
        if (action != null) {
            // OpenYSM controllers advance a PLAY_ONCE/LOOP clip from the moment
            // the predicate becomes active. Do the same here instead of mapping
            // time to attackAnim, which can be zero during placement or on the
            // first client tick of a hand swing.
            if (!this.actionWasActive || !action.name.equals(this.currentActionClip)) {
                this.currentActionClip = action.name;
                this.actionStartTick = nowTick;
            }
            float elapsed = Math.max(0.0f, nowTick - this.actionStartTick) / 20.0f;
            action.sample(elapsed, this.rotationX, this.rotationY, this.rotationZ,
                    this.positionX, this.positionY, this.positionZ,
                    this.scaleX, this.scaleY, this.scaleZ, this.sample, ctx);
        }
        this.actionWasActive = action != null;
        if (action == null) {
            this.currentActionClip = "";
        }
    }

    private boolean isTaczActionState(String state) {
        if (state == null) return false;
        String normalized = state.toLowerCase(Locale.ROOT);
        return normalized.contains(":fire") || normalized.contains(":reload")
                || normalized.contains(":melee");
    }

    private String selectState(LocalPlayer player, float partialTick) {
        if (this.firstPerson) {
            // Yatuoya exports its TACZ hand clips in tac.animation.json rather
            // than fp_arm.animation.json. Prefer those clips for native and
            // Clay weapons when the arm geometry contains them.
            if (isTaczGun(player.getMainHandItem()) && this.hasTaczClips()) {
                String taczState = this.selectTaczState(player, partialTick);
                if (taczState != null && this.findOptionalClip(taczState) != null) {
                    return taczState;
                }
            }
            return this.selectFirstPersonState(player, partialTick);
        }
        String taczState = this.selectTaczState(player, partialTick);
        if (taczState != null) {
            return taczState;
        }
        return this.selectLocomotionState(player, partialTick);
    }

    /**
     * Selects the same item/use/swing clip family used by OpenYSM's
     * FirstPersonArmAnimationController.  The lightweight renderer does not
     * have GeckoLib controllers, but the exported arm.animation.json names
     * are stable and are enough to keep the arm model animated.
     */
    private String selectFirstPersonState(LocalPlayer player, float partialTick) {
        InteractionHand hand;
        if (player.isUsingItem()) {
            hand = player.getUsedItemHand();
        } else if (player.swinging || player.getAttackAnim(partialTick) > 0.001f) {
            hand = player.swingingArm == null ? InteractionHand.MAIN_HAND : player.swingingArm;
        } else {
            hand = InteractionHand.MAIN_HAND;
        }
        ItemStack stack = hand == InteractionHand.MAIN_HAND
                ? player.getMainHandItem() : player.getOffhandItem();
        String prefix = hand == InteractionHand.MAIN_HAND ? "mainhand" : "offhand";
        String suffix = firstPersonItemName(stack);

        if (player.isUsingItem() && player.getUsedItemHand() == hand) {
            String use = this.firstClip("use_" + prefix + ":" + suffix,
                    "use_" + prefix + ":empty");
            if (use != null) {
                return use;
            }
        }
        if ((player.swinging || player.getAttackAnim(partialTick) > 0.001f)
                && player.swingingArm == hand) {
            String swingPrefix = hand == InteractionHand.MAIN_HAND ? "swing" : "swing_offhand";
            String swing = this.firstClip(swingPrefix + ":" + suffix,
                    swingPrefix + ":sword", swingPrefix);
            if (swing != null) {
                return swing;
            }
        }
        return this.firstClip("hold_" + prefix + ":" + suffix,
                "hold_" + prefix + ":empty");
    }

    private static String firstPersonItemName(ItemStack stack) {
        if (stack == null || stack.isEmpty()) {
            return "empty";
        }
        String useName = switch (stack.getUseAnimation()) {
            case BOW -> "bow";
            case CROSSBOW -> "crossbow";
            case SPEAR -> "spear";
            case BLOCK -> "shield";
            case DRINK -> "drink";
            case EAT -> "eat";
            case SPYGLASS -> "spyglass";
            default -> null;
        };
        if (useName != null) {
            return useName;
        }
        Item item = stack.getItem();
        // 26.2 移除了 SwordItem/PickaxeItem 等工具子类（改为 TOOL/WEAPON 数据组件），
        // 因此改用原版物品标签判定，同时也覆盖正确打标签的模组物品。
        if (stack.is(ItemTags.SWORDS)) return "sword";
        if (stack.is(ItemTags.AXES)) return "axe";
        if (stack.is(ItemTags.PICKAXES)) return "pickaxe";
        if (stack.is(ItemTags.SHOVELS)) return "shovel";
        if (stack.is(ItemTags.HOES)) return "hoe";
        if (item instanceof FishingRodItem) return "fishing";
        try {
            String path = BuiltInRegistries.ITEM.getKey(item).getPath().toLowerCase(Locale.ROOT);
            if (path.contains("mace")) return "mace";
        } catch (Throwable ignored) {
        }
        return "empty";
    }

    private String selectLocomotionState(LocalPlayer player, float partialTick) {
        float movement = Math.abs(player.walkAnimation.speed(partialTick));
        if (player.isDeadOrDying()) return "death";
        if (player.isAutoSpinAttack()) return "riptide";
        if (player.getPose() == Pose.SLEEPING) return "sleep";
        if (player.isSwimming()) return "swim";
        if (player.onClimbable()) {
            double verticalSpeed = player.getY() - player.yo;
            if (verticalSpeed > 0.001) return "ladder_up";
            if (verticalSpeed < -0.001) return "ladder_down";
            return "ladder_stillness";
        }
        if (player.getAbilities().flying) return "fly";
        if (player.getPose() == Pose.FALL_FLYING && player.isFallFlying()) return "elytra_fly";
        if (player.isInWater() && !player.onGround()) return "swim_stand";
        if (player.hurtTime > 0) return "attacked";
        if (!player.onGround() && !player.isInWater()) return "jump";
        if (player.getPose() == Pose.CROUCHING && movement > 0.05f) return "sneak";
        if (player.getPose() == Pose.CROUCHING) return "sneaking";
        if (player.isSprinting()) return "run";
        if (movement > 0.05f) return "walk";
        return "idle";
    }

    private void restoreLocomotionLegs(LocalPlayer player, float partialTick, MolangContext ctx) {
        Clip locomotion = this.findOptionalClip(this.selectLocomotionState(player, partialTick));
        if (locomotion == null) {
            return;
        }
        Arrays.fill(this.locomotionRotationX, 0.0f);
        Arrays.fill(this.locomotionRotationY, 0.0f);
        Arrays.fill(this.locomotionRotationZ, 0.0f);
        Arrays.fill(this.locomotionPositionX, 0.0f);
        Arrays.fill(this.locomotionPositionY, 0.0f);
        Arrays.fill(this.locomotionPositionZ, 0.0f);
        Arrays.fill(this.locomotionScaleX, 1.0f);
        Arrays.fill(this.locomotionScaleY, 1.0f);
        Arrays.fill(this.locomotionScaleZ, 1.0f);
        float nowTick = player.tickCount + partialTick;
        locomotion.sample(Math.max(0.0f, nowTick) / 20.0f,
                this.locomotionRotationX, this.locomotionRotationY, this.locomotionRotationZ,
                this.locomotionPositionX, this.locomotionPositionY, this.locomotionPositionZ,
                this.locomotionScaleX, this.locomotionScaleY, this.locomotionScaleZ, this.sample, ctx);
        for (int i = 0; i < this.boneCount; i++) {
            if (!this.lowerBodyBones[i]) {
                continue;
            }
            this.rotationX[i] = this.locomotionRotationX[i];
            this.rotationY[i] = this.locomotionRotationY[i];
            this.rotationZ[i] = this.locomotionRotationZ[i];
            this.positionX[i] = this.locomotionPositionX[i];
            this.positionY[i] = this.locomotionPositionY[i];
            this.positionZ[i] = this.locomotionPositionZ[i];
            this.scaleX[i] = this.locomotionScaleX[i];
            this.scaleY[i] = this.locomotionScaleY[i];
            this.scaleZ[i] = this.locomotionScaleZ[i];
        }
    }

    /**
     * Mirrors OpenYSM's TACZ controller priority.  The returned value is only
     * used when the model actually contains that clip, so models without a TACZ
     * animation file retain the normal YSM state machine.
     */
    private String selectTaczState(LocalPlayer player, float partialTick) {
        ItemStack stack = player.getMainHandItem();
        String gunType = taczGunType(stack);
        if (gunType == null || !this.hasTaczClips()) {
            return null;
        }

        String suffix = gunType;
        try {
            Object operator = TaczCompat.operator(player);
            if (operator != null) {
                if (TaczCompat.isReloading(operator)) {
                    String reload = this.firstClip("tac:reload:" + suffix,
                            "tac:hold:" + suffix);
                    if (reload != null) {
                        return reload;
                    }
                }

                if (TaczCompat.meleeCooldown(operator) > 0) {
                    String melee = this.firstClip("tac:melee:" + suffix,
                            "tac:hold:" + suffix);
                    if (melee != null) {
                        return melee;
                    }
                }

                long shootCooldown = TaczCompat.shootCooldown(operator);
                shootCooldown = Math.max(shootCooldown, TaczCompat.clientShootCooldown(player));

                boolean swimmingPose = !player.isSwimming()
                        && player.getPose() == Pose.SWIMMING;
                if (shootCooldown > 0) {
                    String firePrefix;
                    if (swimmingPose
                            && Math.abs(player.walkAnimation.speed(partialTick)) <= 0.05f) {
                        firePrefix = "tac:climbing:fire:";
                    } else if (TaczCompat.aimingProgress(operator) > 0.0f) {
                        firePrefix = "tac:aim:fire:";
                    } else {
                        firePrefix = "tac:hold:fire:";
                    }
                    String fire = this.firstClip(firePrefix + suffix,
                            "tac:fire:" + suffix,
                            "tac:hold:" + suffix);
                    if (fire != null) {
                        return fire;
                    }
                }

                if (swimmingPose) {
                    String climb = Math.abs(player.walkAnimation.speed(partialTick)) > 0.05f
                            ? this.firstClip("tac:climb:" + suffix,
                                    "tac:climbing:" + suffix,
                                    "tac:hold:" + suffix)
                            : this.firstClip("tac:climbing:" + suffix,
                                    "tac:climb:" + suffix,
                                    "tac:hold:" + suffix);
                    if (climb != null) {
                        return climb;
                    }
                }

                if (TaczCompat.aimingProgress(operator) > 0.0f) {
                    String aim = this.firstClip("tac:aim:" + suffix,
                            "tac:hold:" + suffix);
                    if (aim != null) {
                        return aim;
                    }
                }
            }
        } catch (Throwable ignored) {
            // TACZ is an optional runtime dependency; fall through to vanilla YSM.
        }

        // ClayCore weapons do not expose TACZ's IGunOperator, but their use
        // still drives Minecraft's swing timer. Reuse the TACZ fire clip for
        // that timer so custom weapons get the same recoil/arm animation.
        if (!isNativeTaczGun(stack)
                && (player.swinging || player.getAttackAnim(partialTick) > 0.001f)) {
            String fire = this.firstClip("tac:hold:fire:" + suffix,
                    "tac:aim:fire:" + suffix,
                    "tac:fire:" + suffix,
                    "tac:hold:" + suffix);
            if (fire != null) {
                return fire;
            }
        }
        if (player.onGround() && player.isSprinting()) {
            String run = this.firstClip("tac:run:" + suffix, "tac:run");
            if (run != null) {
                return run;
            }
        }
        return this.firstClip("tac:hold:" + suffix, "tac:idle");
    }

    private String firstClip(String... names) {
        for (String name : names) {
            if (this.findOptionalClip(name) != null) {
                return name;
            }
        }
        return null;
    }

    private boolean hasTaczClips() {
        for (String name : this.clips.keySet()) {
            if (name != null && name.startsWith("tac:")) {
                return true;
            }
        }
        return false;
    }

    public static boolean isTaczGun(ItemStack stack) {
        return taczGunType(stack) != null;
    }

    /** True only for an actual TACZ IGun item. Clay weapons use normal item
     * model transforms while still sharing the TACZ animation state names. */
    public static boolean isNativeTaczGun(ItemStack stack) {
        if (stack == null || stack.isEmpty()) {
            return false;
        }
        try {
            return TaczCompat.isNativeGun(stack);
        } catch (Throwable ignored) {
            return false;
        }
    }

    /**
     * Returns the OpenYSM suffix: pistol, rpg, or rifle. In addition to real
     * TACZ IGun stacks this recognizes ClayCore weapons by the weapon names in
     * the clayData JSON files, so those items can use the same TACZ animation
     * clips without linking against ClayCore's obfuscated classes.
     */
    public static String taczGunType(ItemStack stack) {
        if (stack == null || stack.isEmpty()) {
            return null;
        }
        String nativeType = TaczCompat.gunType(stack);
        if (nativeType != null) {
            return nativeType;
        }
        String indexedType = CLAY_GUNS.findType(stack);
        if (indexedType != null) {
            return indexedType;
        }
        // Some ClayCore servers do not ship the external clayData files on the
        // client. Keep the compatibility path working from the registered item
        // id alone when it clearly belongs to a Clay/COD weapon namespace.
        try {
            String itemId = BuiltInRegistries.ITEM.getKey(stack.getItem()).toString()
                    .toLowerCase(Locale.ROOT);
            if (itemId.contains("clay") || itemId.contains("codweapon")
                    || itemId.contains("clayweapon")) {
                return itemId.contains("pistol") || itemId.contains("handgun")
                        || itemId.contains("手枪") ? "pistol"
                        : itemId.contains("rpg") || itemId.contains("rocket")
                        || itemId.contains("火箭") || itemId.contains("榴弹") ? "rpg" : "rifle";
            }
        } catch (Throwable ignored) {
        }
        return null;
    }

    /** One-time, best-effort index of ClayCore's external weapon definitions. */
    private static final class ClayGunIndex {
        private final AtomicBoolean loaded = new AtomicBoolean();
        private final Map<String, String> names = new LinkedHashMap<>();

        String findType(ItemStack stack) {
            this.load();
            if (this.names.isEmpty() || stack == null || stack.isEmpty()) {
                return null;
            }
            String registryName = "";
            try {
                registryName = BuiltInRegistries.ITEM.getKey(stack.getItem()).toString();
            } catch (Throwable ignored) {
            }
            String displayName = "";
            try {
                displayName = stack.getHoverName().getString();
            } catch (Throwable ignored) {
            }
            String[] candidates = {registryName, displayName};
            for (String candidate : candidates) {
                String normalized = normalize(candidate);
                if (normalized.isEmpty()) {
                    continue;
                }
                for (Map.Entry<String, String> entry : this.names.entrySet()) {
                    String token = entry.getKey();
                    if (normalized.equals(token)
                            || (token.length() >= 4 && (normalized.contains(token)
                            || token.contains(normalized)))) {
                        return entry.getValue();
                    }
                }
            }
            return null;
        }

        private void load() {
            if (!this.loaded.compareAndSet(false, true)) {
                return;
            }
            List<Path> roots = new ArrayList<>();
            // 不再包含开发机硬编码绝对路径（D:\thesave\...），只扫相对用户目录的 clayData。
            roots.add(Paths.get("clayData"));
            String userDir = System.getProperty("user.dir");
            if (userDir != null && !userDir.isEmpty()) {
                roots.add(Paths.get(userDir, "clayData"));
            }
            for (Path root : roots) {
                if (!Files.isDirectory(root)) {
                    continue;
                }
                try (java.util.stream.Stream<Path> stream = Files.walk(root)) {
                    stream.filter(path -> path.getFileName().toString().endsWith(".json"))
                            .forEach(this::readFile);
                } catch (Throwable ignored) {
                }
            }
        }

        private void readFile(Path path) {
            try {
                String text = Files.readString(path, StandardCharsets.UTF_8);
                JsonElement root = JsonParser.parseString(text);
                if (!root.isJsonObject()) {
                    return;
                }
                for (Map.Entry<String, JsonElement> entry : root.getAsJsonObject().entrySet()) {
                    if (!entry.getValue().isJsonObject()) {
                        continue;
                    }
                    JsonObject value = entry.getValue().getAsJsonObject();
                    String type = value.has("type") && value.get("type").isJsonPrimitive()
                            ? value.get("type").getAsString() : "";
                    String fileName = path.getFileName().toString().toLowerCase(Locale.ROOT);
                    if (!"bullet".equalsIgnoreCase(type) && !fileName.contains("武器")
                            && !fileName.contains("weapon")) {
                        continue;
                    }
                    String display = value.has("displayName") && value.get("displayName").isJsonPrimitive()
                            ? value.get("displayName").getAsString() : "";
                    String suffix = classify(entry.getKey() + " " + display + " " + fileName);
                    this.add(entry.getKey(), suffix);
                    this.add(display, suffix);
                }
            } catch (Throwable ignored) {
            }
        }

        private void add(String value, String suffix) {
            String normalized = normalize(value);
            if (!normalized.isEmpty()) {
                this.names.putIfAbsent(normalized, suffix);
            }
        }

        private static String classify(String value) {
            String lower = value.toLowerCase(Locale.ROOT);
            if (lower.contains("pistol") || lower.contains("handgun")
                    || value.contains("手枪") || value.contains("手銃")) {
                return "pistol";
            }
            if (lower.contains("rpg") || lower.contains("rocket")
                    || value.contains("火箭") || value.contains("榴弹")) {
                return "rpg";
            }
            return "rifle";
        }

        private static String normalize(String value) {
            return value == null ? "" : value.toLowerCase(Locale.ROOT)
                    .replaceAll("[^\\p{L}\\p{N}]", "");
        }
    }

    private Clip findClip(String preferred) {
        Clip direct = this.findRawClip(preferred);
        // Some OpenYSM exports (notably yatuoya) include empty English state
        // placeholders and put the actual keyed animation under a localized
        // name. Prefer a non-empty localized alias over an empty placeholder.
        if (direct != null && !direct.tracks.isEmpty()) {
            return direct;
        }
        for (String alias : this.locomotionAliases(preferred)) {
            Clip aliasClip = this.findRawClip(alias);
            if (aliasClip != null && !aliasClip.tracks.isEmpty()) {
                return aliasClip;
            }
        }
        if (direct != null) {
            return direct;
        }
        for (String alias : this.locomotionAliases(preferred)) {
            Clip aliasClip = this.findRawClip(alias);
            if (aliasClip != null) {
                return aliasClip;
            }
        }
        return this.clips.isEmpty() ? null : this.clips.values().iterator().next();
    }

    private Clip findOptionalClip(String name) {
        Clip direct = this.findRawClip(name);
        if (direct != null && !direct.tracks.isEmpty()) {
            return direct;
        }
        for (String alias : this.locomotionAliases(name)) {
            Clip aliasClip = this.findRawClip(alias);
            if (aliasClip != null && !aliasClip.tracks.isEmpty()) {
                return aliasClip;
            }
        }
        return direct;
    }

    private Clip findRawClip(String name) {
        if (name == null) {
            return null;
        }
        Clip clip = this.clips.get(name);
        if (clip != null) return clip;
        clip = this.clips.get(shortName(name));
        if (clip != null) return clip;
        // Model exporters are inconsistent about action key casing.  The
        // controller ids are case-insensitive, so mirror that behavior here.
        for (Map.Entry<String, Clip> entry : this.clips.entrySet()) {
            if (entry.getKey().equalsIgnoreCase(name)
                    || shortName(entry.getKey()).equalsIgnoreCase(shortName(name))) {
                return entry.getValue();
            }
        }
        return null;
    }

    private static List<String> locomotionAliases(String state) {
        if (state == null) {
            return List.of();
        }
        return switch (state.toLowerCase(Locale.ROOT)) {
            case "idle" -> List.of("海边", "静止", "待机");
            case "walk" -> List.of("行走动画", "移动", "走路");
            case "run" -> List.of("跑步动画", "跑步", "奔跑");
            case "jump" -> List.of("跳跃动画", "跳跃", "跳");
            case "sneak" -> List.of("潜行", "潜行动画");
            case "sneaking" -> List.of("蹲下", "潜行", "蹲下动画");
            case "swim", "swim_stand" -> List.of("游泳", "游泳动画", "爬行");
            case "ladder_up" -> List.of("攀爬", "爬梯");
            case "ladder_down" -> List.of("下梯", "攀爬");
            default -> List.of();
        };
    }

    private void clearCurrentPose() {
        Arrays.fill(this.rotationX, 0.0f);
        Arrays.fill(this.rotationY, 0.0f);
        Arrays.fill(this.rotationZ, 0.0f);
        Arrays.fill(this.positionX, 0.0f);
        Arrays.fill(this.positionY, 0.0f);
        Arrays.fill(this.positionZ, 0.0f);
        Arrays.fill(this.scaleX, 1.0f);
        Arrays.fill(this.scaleY, 1.0f);
        Arrays.fill(this.scaleZ, 1.0f);
    }

    private void captureTransitionSource() {
        System.arraycopy(this.lastRotationX, 0, this.transitionRotationX, 0, this.boneCount);
        System.arraycopy(this.lastRotationY, 0, this.transitionRotationY, 0, this.boneCount);
        System.arraycopy(this.lastRotationZ, 0, this.transitionRotationZ, 0, this.boneCount);
        System.arraycopy(this.lastPositionX, 0, this.transitionPositionX, 0, this.boneCount);
        System.arraycopy(this.lastPositionY, 0, this.transitionPositionY, 0, this.boneCount);
        System.arraycopy(this.lastPositionZ, 0, this.transitionPositionZ, 0, this.boneCount);
        System.arraycopy(this.lastScaleX, 0, this.transitionScaleX, 0, this.boneCount);
        System.arraycopy(this.lastScaleY, 0, this.transitionScaleY, 0, this.boneCount);
        System.arraycopy(this.lastScaleZ, 0, this.transitionScaleZ, 0, this.boneCount);
    }

    private static String shortName(String name) {
        if (name == null) return "";
        int index = Math.max(name.lastIndexOf('.'), name.lastIndexOf(':'));
        return index >= 0 ? name.substring(index + 1) : name;
    }

    /**
     * OpenYSM uses a '$' separator before an optional item namespace, e.g.
     * swing$minecraft:mace. The controller asks for swing:mace, so retain an
     * explicit alias instead of relying on shortName(), which would collide
     * with hold_mainhand:mace and silently select the wrong clip.
     */
    private static String compactAnimationKey(String name) {
        if (name == null) return null;
        int dollar = name.indexOf('$');
        if (dollar < 0) return null;
        int suffix = name.lastIndexOf(':');
        if (suffix > dollar && suffix + 1 < name.length()) {
            return name.substring(0, dollar) + ":" + name.substring(suffix + 1);
        }
        return name.replace('$', ':');
    }

    private static float[] ones(int size) {
        float[] values = new float[size];
        Arrays.fill(values, 1.0f);
        return values;
    }

    private static float lerp(float from, float to, float amount) {
        return from + (to - from) * amount;
    }

    private static float clamp(float value, float min, float max) {
        return Math.max(min, Math.min(max, value));
    }

    /** 一帧的 molang 求值上下文（查询变量 + ysm 物理函数）。 */
    private final class MolangContext implements YsmMolang.Context {
        private LocalPlayer player;
        private float animTime;

        private MolangContext begin(LocalPlayer p, float animTime) {
            this.player = p;
            this.animTime = animTime;
            return this;
        }

        @Override
        public float variable(String name) {
            if (name == null) {
                return 0.0f;
            }
            int dot = name.indexOf('.');
            String ns = dot < 0 ? "" : name.substring(0, dot).toLowerCase(Locale.ROOT);
            String key = dot < 0 ? name : name.substring(dot + 1);
            return switch (ns) {
                case "q", "query", "c", "context", "" -> this.query(key);
                case "v", "variable", "t", "temp" -> YsmMolang.asFloat(molangVars.get(key));
                case "math" -> key.equalsIgnoreCase("pi") ? (float) Math.PI
                        : key.equalsIgnoreCase("e") ? (float) Math.E : 0.0f;
                default -> 0.0f;
            };
        }

        @Override
        public void setVariable(String name, Object value) {
            int dot = name.indexOf('.');
            molangVars.put(dot < 0 ? name : name.substring(dot + 1), value);
        }

        @Override
        public boolean function(String name, java.util.List<Object> args, Object[] out) {
            String fn = name.toLowerCase(Locale.ROOT);
            if (fn.startsWith("ysm.")) {
                fn = fn.substring(4);
            }
            switch (fn) {
                case "first_order" -> {
                    if (args.size() < 2) return false;
                    float input = YsmMolang.asFloat(args.get(1));
                    float response = args.size() >= 3 ? YsmMolang.asFloat(args.get(2)) : 1.0f;
                    out[0] = physics.firstOrder(String.valueOf(args.get(0)), input, response);
                    return true;
                }
                case "second_order" -> {
                    if (args.size() < 2) return false;
                    float input = YsmMolang.asFloat(args.get(1));
                    float freq = args.size() >= 3 ? YsmMolang.asFloat(args.get(2)) : 1.0f;
                    float coeff = args.size() >= 4 ? YsmMolang.asFloat(args.get(3)) : 1.0f;
                    float response = args.size() >= 5 ? YsmMolang.asFloat(args.get(4)) : 1.0f;
                    out[0] = physics.secondOrder(String.valueOf(args.get(0)), input, freq, coeff, response);
                    return true;
                }
                default -> {
                    return false;
                }
            }
        }

        private float query(String key) {
            LocalPlayer p = this.player;
            return switch (key.toLowerCase(Locale.ROOT)) {
                case "anim_time" -> this.animTime;
                case "life_time" -> p == null ? 0.0f : p.tickCount / 20.0f;
                case "delta_time" -> 1.0f / 20.0f;
                case "time_of_day" -> p == null || p.level() == null ? 0.0f : p.level().getOverworldClockTime() % 24000L;
                case "is_close_eyes" -> isCloseEyes(p) ? 1.0f : 0.0f;
                case "is_sleep", "is_sleeping" -> p != null && p.getPose() == Pose.SLEEPING ? 1.0f : 0.0f;
                case "is_sneak" -> p != null && p.onGround() && p.getPose() == Pose.CROUCHING ? 1.0f : 0.0f;
                case "swinging" -> p != null && p.swinging ? 1.0f : 0.0f;
                case "swing_time" -> p == null ? 0.0f : p.swingTime;
                case "attack_time" -> p == null ? 0.0f : p.getAttackAnim(0.0f);
                case "on_ground", "is_on_ground" -> p != null && p.onGround() ? 1.0f : 0.0f;
                case "is_swimming", "swimming" -> p != null && p.isSwimming() ? 1.0f : 0.0f;
                case "is_sprinting" -> p != null && p.isSprinting() ? 1.0f : 0.0f;
                case "is_in_water" -> p != null && p.isInWater() ? 1.0f : 0.0f;
                case "ground_speed" -> p == null ? 0.0f : (float) (p.getDeltaMovement().horizontalDistance() * 20.0);
                case "vertical_speed" -> p == null ? 0.0f : (float) ((p.getY() - p.yo) * 20.0);
                default -> 0.0f;
            };
        }
    }

    /** 眨眼：每 90 tick 睁开约 85 tick（与 OpenYSM 的 is_close_eyes 一致）。 */
    private static boolean isCloseEyes(LocalPlayer player) {
        if (player == null) {
            return false;
        }
        if (player.isSleeping()) {
            return true;
        }
        float f = (player.tickCount + (Math.abs(player.getUUID().getLeastSignificantBits()) % 10)) % 90.0f;
        return f > 85.0f && f < 90.0f;
    }

    private static final class Clip {
        private final String name;
        private final float length;
        private final int loopMode;
        private final List<BoneTrack> tracks;

        private Clip(String name, float length, int loopMode, List<BoneTrack> tracks) {
            this.name = name;
            this.length = length;
            this.loopMode = loopMode;
            this.tracks = tracks;
        }

        private static Clip build(String animationKey, RawYsmModel.RawAnimation raw,
                                  Map<String, Integer> boneIndex, boolean resolveMolang) {
            List<BoneTrack> tracks = new ArrayList<>();
            float maxTime = 0.0f;
            for (RawYsmModel.RawBoneAnimation bone : raw.boneAnimations) {
                Integer index = boneIndex.get(bone.boneName);
                if (index == null) continue;
                Curve rotation = Curve.build(bone.rotation, resolveMolang);
                Curve position = Curve.build(bone.position, resolveMolang);
                Curve scale = Curve.build(bone.scale, resolveMolang);
                maxTime = Math.max(maxTime, Math.max(rotation.maxTime(), Math.max(position.maxTime(), scale.maxTime())));
                tracks.add(new BoneTrack(index, rotation, position, scale));
            }
            float length = Float.isFinite(raw.length) && raw.length > 0.0f ? raw.length : maxTime;
            String name = raw.name == null || raw.name.isBlank() ? animationKey : raw.name;
            return new Clip(name == null ? "" : name, Math.max(length, 0.001f), raw.loopMode, tracks);
        }

        private void sample(float elapsed, float[] rx, float[] ry, float[] rz,
                            float[] px, float[] py, float[] pz,
                            float[] sx, float[] sy, float[] sz, float[] sample, MolangContext ctx) {
            float time = this.loopMode == 1 ? elapsed % this.length : Math.min(elapsed, this.length);
            for (BoneTrack track : this.tracks) {
                int i = track.boneIndex;
                if (track.rotation.sample(time, sample, ctx)) {
                    rx[i] = (float) -Math.toRadians(sample[0]);
                    ry[i] = (float) -Math.toRadians(sample[1]);
                    rz[i] = (float) Math.toRadians(sample[2]);
                }
                if (track.position.sample(time, sample, ctx)) {
                    px[i] = sample[0];
                    py[i] = sample[1];
                    pz[i] = sample[2];
                }
                if (track.scale.sample(time, sample, ctx)) {
                    sx[i] = sample[0];
                    sy[i] = sample[1];
                    sz[i] = sample[2];
                }
            }
        }
    }

    private record BoneTrack(int boneIndex, Curve rotation, Curve position, Curve scale) {
    }

    private static final class Curve {
        private static final Curve EMPTY = new Curve(new Keyframe[0]);
        private final Keyframe[] frames;

        private Curve(Keyframe[] frames) {
            this.frames = frames;
        }

        private static Curve build(List<RawYsmModel.RawKeyframe> rawFrames, boolean resolveMolang) {
            if (rawFrames == null || rawFrames.isEmpty()) return EMPTY;
            return new Curve(rawFrames.stream()
                    .sorted(Comparator.comparingDouble(frame -> frame.timestamp))
                    .map(frame -> new Keyframe(frame, resolveMolang))
                    .toArray(Keyframe[]::new));
        }

        private float maxTime() {
            return this.frames.length == 0 ? 0.0f : this.frames[this.frames.length - 1].time;
        }

        private boolean sample(float time, float[] out, MolangContext ctx) {
            if (this.frames.length == 0) return false;
            if (this.frames.length == 1 || time <= this.frames[0].time) {
                this.frames[0].copyPost(out, ctx);
                return true;
            }
            int leftIndex = this.frames.length - 1;
            for (int i = 0; i < this.frames.length - 1; i++) {
                if (time < this.frames[i + 1].time) {
                    leftIndex = i;
                    break;
                }
            }
            if (leftIndex == this.frames.length - 1) {
                this.frames[leftIndex].copyPost(out, ctx);
                return true;
            }
            Keyframe left = this.frames[leftIndex];
            Keyframe right = this.frames[leftIndex + 1];
            float amount = clamp((time - left.time) / Math.max(0.0001f, right.time - left.time), 0.0f, 1.0f);
            for (int axis = 0; axis < 3; axis++) {
                float a = left.post(axis, ctx);
                float b = right.hasPre ? right.pre(axis, ctx) : right.post(axis, ctx);
                if (left.interpolation == 2) {
                    float p0 = leftIndex > 0 ? this.frames[leftIndex - 1].post(axis, ctx) : a;
                    float p3 = leftIndex + 2 < this.frames.length ? this.frames[leftIndex + 2].post(axis, ctx) : b;
                    float t2 = amount * amount;
                    float t3 = t2 * amount;
                    out[axis] = 0.5f * ((2.0f * a) + (-p0 + b) * amount
                            + (2.0f * p0 - 5.0f * a + 4.0f * b - p3) * t2
                            + (-p0 + 3.0f * a - 3.0f * b + p3) * t3);
                } else {
                    out[axis] = lerp(a, b, amount);
                }
            }
            return true;
        }
    }

    private static final class Keyframe {
        private final float time;
        private final int interpolation;
        private final Object[] rawPre = new Object[3];
        private final Object[] rawPost = new Object[3];
        private final YsmMolang[] preMolang = new YsmMolang[3];
        private final YsmMolang[] postMolang = new YsmMolang[3];
        private final boolean hasPre;

        private Keyframe(RawYsmModel.RawKeyframe raw, boolean resolveMolang) {
            this.time = raw.timestamp;
            this.interpolation = raw.interpolationMode;
            this.hasPre = raw.hasPreData;
            for (int axis = 0; axis < 3; axis++) {
                this.rawPre[axis] = raw.preData[axis];
                this.rawPost[axis] = raw.postData[axis];
                if (resolveMolang) {
                    this.preMolang[axis] = compile(raw.preData[axis]);
                    this.postMolang[axis] = compile(raw.postData[axis]);
                }
            }
        }

        private void copyPost(float[] out, MolangContext ctx) {
            for (int axis = 0; axis < 3; axis++) {
                out[axis] = this.post(axis, ctx);
            }
        }

        private float pre(int axis, MolangContext ctx) {
            return resolve(this.rawPre[axis], this.preMolang[axis], ctx);
        }

        private float post(int axis, MolangContext ctx) {
            return resolve(this.rawPost[axis], this.postMolang[axis], ctx);
        }

        private static YsmMolang compile(Object value) {
            return value instanceof String string ? YsmMolang.compile(string) : null;
        }

        private static float resolve(Object value, YsmMolang molang, MolangContext ctx) {
            if (value instanceof Number number) {
                return number.floatValue();
            }
            if (value instanceof String string) {
                if (molang != null) {
                    try {
                        return YsmMolang.asFloat(molang.eval(ctx));
                    } catch (Throwable ignored) {
                        // 求值失败 → 回退到字面量
                    }
                }
                return literal(string);
            }
            return 0.0f;
        }

        /** 回退：取表达式里第一个数字字面量（保留旧行为）。 */
        private static float literal(String expression) {
            try {
                return Float.parseFloat(expression.trim());
            } catch (NumberFormatException ignored) {
            }
            java.util.regex.Matcher matcher = java.util.regex.Pattern
                    .compile("[-+]?(?:\\d+(?:\\.\\d*)?|\\.\\d+)")
                    .matcher(expression);
            if (matcher.find()) {
                try {
                    return Float.parseFloat(matcher.group());
                } catch (NumberFormatException ignored) {
                }
            }
            return 0.0f;
        }

        private static float number(Object value, boolean resolveMolang) {
            return resolve(value, resolveMolang ? compile(value) : null, null);
        }
    }
}
