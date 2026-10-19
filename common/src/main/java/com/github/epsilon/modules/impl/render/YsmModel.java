package com.github.epsilon.modules.impl.render;

import com.github.epsilon.Constants;
import com.github.epsilon.events.bus.EventHandler;
import com.github.epsilon.events.impl.ClientTickEvent;
import com.github.epsilon.modules.Category;
import com.github.epsilon.modules.Module;
import com.github.epsilon.settings.impl.BoolSetting;
import com.github.epsilon.settings.impl.DoubleSetting;
import com.github.epsilon.settings.impl.EnumSetting;
import com.github.epsilon.settings.impl.StringSetting;
import com.github.epsilon.utils.player.ChatUtils;
import com.github.epsilon.ysm.YsmResources;
import com.github.epsilon.ysm.model.YsmAnimationPlayer;
import com.github.epsilon.ysm.model.YsmModelData;
import com.github.epsilon.ysm.resource.YSMBinaryDeserializer;
import com.github.epsilon.ysm.resource.YSMFolderDeserializer;
import com.github.epsilon.ysm.resource.pojo.RawYsmModel;
import com.mojang.blaze3d.platform.NativeImage;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import com.mojang.math.Axis;
import net.minecraft.client.model.Model;
import net.minecraft.client.model.player.PlayerModel;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.client.renderer.SubmitNodeCollector;
import net.minecraft.client.renderer.rendertype.RenderType;
import net.minecraft.client.renderer.rendertype.RenderTypes;
import net.minecraft.client.renderer.entity.state.AvatarRenderState;
import net.minecraft.client.renderer.entity.state.LivingEntityRenderState;
import net.minecraft.client.renderer.texture.DynamicTexture;
import net.minecraft.client.renderer.texture.OverlayTexture;
import net.minecraft.client.resources.DefaultPlayerSkin;
import net.minecraft.core.Direction;
import net.minecraft.resources.Identifier;
import net.minecraft.util.Mth;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.HumanoidArm;
import net.minecraft.world.entity.Pose;
import net.minecraft.world.item.ItemDisplayContext;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.phys.Vec3;
import org.joml.Matrix3f;
import org.joml.Matrix4f;
import org.joml.Vector3f;

import java.io.ByteArrayInputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.stream.Stream;

/**
 * 改人物模型 —— 用 YSM（Yes Steve Model）模型替换本地玩家的第三人称模型与第一人称手臂。
 *
 * <p>架构：数据链 模型文件夹/文件 → YSMFolderDeserializer/YSMBinaryDeserializer → RawYsmModel
 * → YsmModelData.build（骨骼+四边形，顶点已 ÷16 为方块单位）→ 渲染。</p>
 *
 * <p>渲染由 {@code MixinYsmLivingEntityRenderer} 驱动：在 {@code LivingEntityRenderer.submit}
 * 的 HEAD 处记录当前世界矩阵，然后在原版提交身体模型的位置（{@code submitModel} 调用点）
 * 用 {@code 当前矩阵⁻¹ × HEAD 矩阵 × YSM 位姿链} 换算出相对矩阵后提交 YSM 几何。
 * 这样 YSM 模型精确落在原版身体所在的空间，而不必复刻原版的变换链。</p>
 *
 * <p>动画：{@code Vanilla} 模式跟随原版 {@link PlayerModel} 骨骼；{@code OpenYsm} 模式播放
 * 模型自带关键帧/Molang/物理动画。</p>
 */
public class YsmModel extends Module {

    public static final YsmModel INSTANCE = new YsmModel();

    public enum ModelPreset {
        Auto, Alice, Boqijiang, ToyotaAe86, Yatuoya, Custom
    }

    public enum AnimationMode {
        Vanilla, OpenYsm
    }

    /** 原版骨骼旋转快照，布局：head, body, leftArm, rightArm, leftLeg, rightLeg × (xRot, yRot, zRot)。 */
    public static final float[] VANILLA_BONE_ROTATIONS = new float[18];

    private static final String[] CHARACTER_ONLY_AUXILIARY_TOKENS = {
            "background", "sign", "ground", "flower", "bed", "ghost", "cat",
            "particle", "effect", "beam", "trail", "arrow", "projectile",
            "pole", "rail", "board", "vehicle", "car", "wheel", "tyre", "door",
            "computer", "monitor", "suitcase", "lamp", "broom", "gui"
    };

    // ===== 设置 =====
    private final EnumSetting<ModelPreset> modelPreset = enumSetting("Model", ModelPreset.Auto);
    private final StringSetting customPath = stringSetting("Custom Path", "",
            () -> this.modelPreset.is(ModelPreset.Custom));
    private final DoubleSetting scale = doubleSetting("Scale", 1.0, 0.1, 5.0, 0.05);
    private final EnumSetting<AnimationMode> animation = enumSetting("Animation", AnimationMode.Vanilla);
    /** 骨骼过滤：空 = 全部；"关键词" = 只渲染包含关键词；"!关键词" = 排除 */
    private final StringSetting boneFilter = stringSetting("Bone Filter", "");
    /**
     * 只渲染角色层级。YSM 导出文件常在 mainModel 里附带场景/告示牌/粒子等展示几何，
     * 关掉此项可恢复完整模型渲染。
     */
    private final BoolSetting characterOnly = boolSetting("Character Only", true);
    // ===== 手持物品渲染 =====
    private final BoolSetting showItem = boolSetting("Show Item", true);
    private final DoubleSetting itemScale = doubleSetting("Item Scale", 1.0, 0.1, 5.0, 0.05);
    // ===== 方向开关 =====
    /** 开关一：走路时左手摆臂方向 */
    private final BoolSetting flipLeftWalk = boolSetting("Flip Left Walk", false,
            () -> this.animation.is(AnimationMode.Vanilla));
    /** 开关二：走路时右手摆臂方向 */
    private final BoolSetting flipRightWalk = boolSetting("Flip Right Walk", false,
            () -> this.animation.is(AnimationMode.Vanilla));
    /** 开关三：除走路摆臂外的所有动作方向（攻击/持物等） */
    private final BoolSetting flipOther = boolSetting("Flip Other Anim", false,
            () -> this.animation.is(AnimationMode.Vanilla));

    // ===== 模型状态 =====
    private YsmModelData model;
    /** 第一人称手臂专用几何。 */
    private YsmModelData armModel;
    private final java.util.IdentityHashMap<YsmModelData, boolean[]> characterFilterCache =
            new java.util.IdentityHashMap<>();
    private YsmAnimationPlayer animationPlayer;
    private YsmAnimationPlayer armAnimationPlayer;
    private boolean loadingModel;
    private Identifier textureLocation;
    private boolean failedLogged;
    private int failCount;
    private float modelHorizontalScale = 1.0f;
    private float modelVerticalScale = 1.0f;
    private String loadedKey = "";
    private float[] poseRotationX = new float[0];
    private float[] poseRotationY = new float[0];
    private float[] poseRotationZ = new float[0];
    private float[] posePositionX = new float[0];
    private float[] posePositionY = new float[0];
    private float[] posePositionZ = new float[0];
    private float[] poseScaleX = new float[0];
    private float[] poseScaleY = new float[0];
    private float[] poseScaleZ = new float[0];
    private float[] armPoseRotationX = new float[0];
    private float[] armPoseRotationY = new float[0];
    private float[] armPoseRotationZ = new float[0];
    private float[] armPosePositionX = new float[0];
    private float[] armPosePositionY = new float[0];
    private float[] armPosePositionZ = new float[0];
    private float[] armPoseScaleX = new float[0];
    private float[] armPoseScaleY = new float[0];
    private float[] armPoseScaleZ = new float[0];
    /** 骨骼矩阵池（第三人称与手臂各一套，避免延迟执行的几何回调读到被覆盖的数据）。 */
    private Matrix4f[] poseLocal = new Matrix4f[0];
    private Matrix4f[] poseGlobal = new Matrix4f[0];
    private boolean[] poseComputed = new boolean[0];
    private Matrix4f[] armLocal = new Matrix4f[0];
    private Matrix4f[] armGlobal = new Matrix4f[0];
    private boolean[] armComputed = new boolean[0];

    // ===== 每帧渲染握手状态 =====
    private boolean handling;
    private AvatarRenderState capturedState;
    private LocalPlayer capturedPlayer;
    private final Matrix4f capturedMatrix = new Matrix4f();
    private final Matrix4f helperMatrix = new Matrix4f();
    private final Matrix4f relativeMatrix = new Matrix4f();
    private int counter;

    private YsmModel() {
        super("Ysm Model", Category.RENDER);
    }

    @Override
    protected void onEnable() {
        // loadModel() 自己维护 loadingModel 守卫；原实现先在外部置 true，
        // 导致首次加载必然被自身的 `if (loadingModel) return;` 跳过（要等下一 tick 才补载）。
        this.loadModel();
    }

    @Override
    protected void onDisable() {
        this.model = null;
        this.armModel = null;
        this.characterFilterCache.clear();
        this.animationPlayer = null;
        this.armAnimationPlayer = null;
        this.modelHorizontalScale = 1.0f;
        this.modelVerticalScale = 1.0f;
        this.loadedKey = "";
        this.handling = false;
        this.capturedState = null;
        this.capturedPlayer = null;
        if (this.textureLocation != null) {
            mc.getTextureManager().release(this.textureLocation);
            this.textureLocation = null;
        }
    }

    /**
     * 用户反馈：始终写日志（便于排查/自动验证），再尝试进聊天栏。
     * 启动阶段从配置启用模块时 GUI 可能尚未就绪，聊天失败只忽略。
     */
    private static void notify(String message) {
        Constants.LOGGER.info("YsmModel: {}", message);
        try {
            ChatUtils.addChatMessage("[Ysm Model] " + message);
        } catch (Throwable ignored) {
        }
    }

    /** 运行中切换预设/路径时即时重建模型（设置工厂不提供变更回调的替代方案）。 */
    @EventHandler
    private void onClientTick(ClientTickEvent.Pre event) {
        if (!this.isEnabled() || this.loadingModel) {
            return;
        }
        if (!this.selectionKey().equals(this.loadedKey)) {
            this.loadModel();
        }
    }

    // ================= 加载 =================

    private String selectionKey() {
        ModelPreset preset = this.modelPreset.getValue();
        String extra = preset == ModelPreset.Custom ? this.customPath.getValue() : "";
        return preset.name() + "|" + (extra == null ? "" : extra);
    }

    private void loadModel() {
        if (this.loadingModel) {
            return;
        }
        this.loadingModel = true;
        this.model = null;
        this.armModel = null;
        this.characterFilterCache.clear();
        if (this.textureLocation != null) {
            mc.getTextureManager().release(this.textureLocation);
            this.textureLocation = null;
        }
        try {
            Path file = this.resolveModelPath();
            if (file == null) {
                notify("model not found: " + this.modelPreset.getValue()
                        + (this.modelPreset.is(ModelPreset.Custom) ? " (" + this.customPath.getValue() + ")" : ""));
                this.toggle();
                return;
            }
            RawYsmModel raw;
            if (Files.isDirectory(file)) {
                try (YSMFolderDeserializer deser = new YSMFolderDeserializer(file)) {
                    raw = deser.deserialize();
                }
            } else {
                raw = tryDeserialize(Files.readAllBytes(file));
            }
            if (raw == null) {
                notify("cannot parse (maybe encrypted YSGP). File: " + file);
                this.toggle();
                return;
            }
            // YSM 导出文件常把展示几何挂在 Root 下，单靠骨骼名过滤不可靠，加载时直接裁掉。
            this.stripNonCharacterGeometry(raw);
            this.model = YsmModelData.build(raw);
            RawYsmModel.RawGeometry rawArm = raw.mainEntity == null ? null : raw.mainEntity.armModel;
            // 没有专用 armModel 的导出文件沿用主网格作为手臂网格。
            if (rawArm == null || rawArm.bones.isEmpty()) {
                rawArm = raw.mainEntity == null ? null : raw.mainEntity.mainModel;
            }
            this.armModel = rawArm == null ? null : YsmModelData.buildGeometry(rawArm);
            this.animationPlayer = new YsmAnimationPlayer(raw, this.model.boneIndex);
            this.armAnimationPlayer = this.armModel == null ? null
                    : new YsmAnimationPlayer(raw, this.armModel.boneIndex, true);
            this.ensurePoseCapacity(this.model.bones.size());
            this.ensureArmPoseCapacity(this.armModel == null ? 0 : this.armModel.bones.size());
            this.modelHorizontalScale = raw.properties == null ? 1.0f : raw.properties.heightScale;
            this.modelVerticalScale = raw.properties == null ? 1.0f : raw.properties.widthScale;
            String defaultTexture = raw.properties == null ? null : raw.properties.defaultTexture;
            this.textureLocation = this.registerTexture(defaultTexture);
            this.failCount = 0;
            this.failedLogged = false;
            this.loadedKey = this.selectionKey();
            notify("loaded " + this.model.bones.size() + " bones, "
                    + this.model.textures.size() + " textures"
                    + (this.armModel == null ? " (no arm model)" : ", " + this.armModel.bones.size() + " arm bones"));
        } catch (Throwable e) {
            Constants.LOGGER.error("YsmModel: load failed", e);
            notify("load failed: " + e);
            this.toggle();
        } finally {
            this.loadingModel = false;
        }
    }

    private static RawYsmModel tryDeserialize(byte[] data) {
        try {
            return new YSMBinaryDeserializer(data).deserialize();
        } catch (Exception ignored) {
        }
        try {
            byte[] decompressed = com.github.epsilon.ysm.algorithms.YsmZstd.decompress(data);
            return new YSMBinaryDeserializer(decompressed).deserialize();
        } catch (Exception ignored) {
        }
        return null;
    }

    /** 解析当前预设对应的模型路径：目录（ysm.json / models/main.json）或 .ysm 文件。 */
    private Path resolveModelPath() {
        ModelPreset preset = this.modelPreset.getValue();
        if (preset == ModelPreset.Custom) {
            return this.resolveCustom(this.customPath.getValue());
        }
        if (preset != ModelPreset.Auto) {
            return YsmResources.builtin(presetDirectory(preset));
        }
        return this.resolveAuto();
    }

    private static String presetDirectory(ModelPreset preset) {
        return switch (preset) {
            case Alice -> "Alice";
            case Boqijiang -> "boqijiang";
            case ToyotaAe86 -> "toyota_ae86";
            case Yatuoya -> "yatuoya";
            default -> "";
        };
    }

    private Path resolveCustom(String raw) {
        if (raw == null || raw.isEmpty()) {
            return null;
        }
        try {
            Path direct = Paths.get(raw);
            if (Files.exists(direct)) {
                return direct;
            }
        } catch (Exception ignored) {
        }
        try {
            Path relative = YsmResources.root().resolve(raw);
            if (Files.exists(relative)) {
                return relative;
            }
        } catch (Exception ignored) {
        }
        return null;
    }

    /** Auto：外部模型目录优先（目录格式 > .ysm 文件），无外部模型时回退内置 Alice。 */
    private Path resolveAuto() {
        Path root;
        try {
            root = YsmResources.root();
        } catch (Exception ignored) {
            root = null;
        }
        if (root != null && Files.isDirectory(root)) {
            try (Stream<Path> dirs = Files.list(root)) {
                List<Path> list = dirs.filter(Files::isDirectory)
                        .filter(d -> Files.exists(d.resolve("ysm.json"))
                                || Files.exists(d.resolve("models").resolve("main.json")))
                        .sorted()
                        .toList();
                if (!list.isEmpty()) {
                    return list.get(0);
                }
            } catch (Exception ignored) {
            }
            try (Stream<Path> files = Files.list(root)) {
                List<Path> list = files
                        .filter(f -> f.getFileName().toString().toLowerCase(Locale.ROOT).endsWith(".ysm"))
                        .sorted()
                        .toList();
                if (!list.isEmpty()) {
                    return list.get(0);
                }
            } catch (Exception ignored) {
            }
        }
        return YsmResources.builtin(presetDirectory(ModelPreset.Alice));
    }

    private Identifier registerTexture(String defaultTexture) {
        if (this.model == null || this.model.textures.isEmpty()) {
            return null;
        }
        try {
            byte[] png = defaultTexture == null ? null : this.model.textures.get(defaultTexture);
            if (png == null) {
                png = this.model.textures.values().iterator().next();
            }
            NativeImage img = NativeImage.read(new ByteArrayInputStream(png));
            DynamicTexture tex = new DynamicTexture(() -> "epsilon-ysm-model", img);
            Identifier loc = Identifier.fromNamespaceAndPath("epsilon", "ysm_model_" + this.counter++);
            mc.getTextureManager().register(loc, tex);
            return loc;
        } catch (Exception e) {
            Constants.LOGGER.warn("YsmModel: texture registration failed", e);
            return null;
        }
    }

    // ================= 展示几何裁剪 =================

    private void stripNonCharacterGeometry(RawYsmModel raw) {
        if (raw == null || raw.mainEntity == null || isPreservedModel(raw)) {
            return;
        }
        stripNonCharacterBranches(raw.mainEntity.mainModel);
        stripNonCharacterBranches(raw.mainEntity.armModel);
    }

    private boolean isPreservedModel(RawYsmModel raw) {
        String mode = this.modelPreset.getValue().name().toLowerCase(Locale.ROOT);
        String metadata = raw.metadata == null || raw.metadata.name == null
                ? "" : raw.metadata.name.toLowerCase(Locale.ROOT);
        return mode.contains("boqijiang") || mode.contains("toyota") || mode.contains("ae86")
                || metadata.contains("boqijiang") || metadata.contains("波奇")
                || metadata.contains("toyota") || metadata.contains("ae86");
    }

    private void stripNonCharacterBranches(RawYsmModel.RawGeometry geometry) {
        if (geometry == null || geometry.bones == null || geometry.bones.isEmpty()) {
            return;
        }
        java.util.Map<String, RawYsmModel.RawBone> byName = new java.util.HashMap<>();
        boolean hasRoot = false;
        for (RawYsmModel.RawBone bone : geometry.bones) {
            if (bone == null || bone.name == null) continue;
            byName.putIfAbsent(bone.name, bone);
            if ("root".equals(normalizeBoneName(bone.name))) hasRoot = true;
        }
        // 载具等非玩家几何没有标准 Root 层级，这类文件原样保留。
        if (!hasRoot) return;

        java.util.Map<String, String> branchByBone = new java.util.HashMap<>();
        java.util.Set<String> characterBranches = new java.util.HashSet<>();
        boolean foundAnchor = false;
        for (RawYsmModel.RawBone bone : geometry.bones) {
            if (bone == null || bone.name == null) continue;
            String branch = findRootBranch(bone, byName);
            branchByBone.put(bone.name, branch);
            if (isCharacterAnchor(bone.name)) {
                foundAnchor = true;
                if (branch != null) characterBranches.add(branch);
            }
        }
        if (!foundAnchor || characterBranches.isEmpty()) return;

        java.util.Set<String> removedNames = new java.util.HashSet<>();
        geometry.bones.removeIf(bone -> {
            if (bone == null || bone.name == null) return true;
            String normalized = normalizeBoneName(bone.name);
            if ("root".equals(normalized)) return false;
            String branch = branchByBone.get(bone.name);
            boolean remove = branch == null || !characterBranches.contains(branch);
            if (remove) removedNames.add(bone.name);
            return remove;
        });
        // 父名会在 YsmModelData.buildMesh 里重新解析；这里清掉悬空父引用，
        // 避免被移除的场景骨骼继续影响角色变换。
        for (RawYsmModel.RawBone bone : geometry.bones) {
            if (bone.parentName != null && removedNames.contains(bone.parentName)) {
                bone.parentName = "";
            }
        }
    }

    private static String findRootBranch(RawYsmModel.RawBone bone,
                                        java.util.Map<String, RawYsmModel.RawBone> byName) {
        RawYsmModel.RawBone current = bone;
        java.util.Set<String> visited = new java.util.HashSet<>();
        while (current != null && current.name != null && visited.add(current.name)) {
            String parentName = current.parentName;
            if (parentName == null || parentName.isEmpty()) {
                return current.name;
            }
            RawYsmModel.RawBone parent = byName.get(parentName);
            if (parent == null) return current.name;
            if ("root".equals(normalizeBoneName(parent.name))) {
                return current.name;
            }
            current = parent;
        }
        return bone.name;
    }

    private static boolean isCharacterAnchor(String name) {
        String n = normalizeBoneName(name);
        return n.equals("mallbody") || n.equals("allbody") || n.equals("upbody")
                || n.equals("mupbody") || n.equals("mupperbody") || n.equals("upperbody")
                || n.equals("allhead") || n.equals("head") || n.equals("arm")
                || n.equals("leftarm") || n.equals("rightarm") || n.equals("downbody")
                || n.equals("leg") || n.equals("leftleg") || n.equals("rightleg");
    }

    private static String normalizeBoneName(String name) {
        return name == null ? "" : name.toLowerCase(Locale.ROOT)
                .replace("_", "").replace("-", "").replace(" ", "");
    }

    // ================= 渲染入口 =================

    /** 当前过滤下可见骨骼数 */
    public int visibleBoneCount() {
        if (this.model == null) {
            return 0;
        }
        String filter = this.boneFilter.getValue();
        boolean exclude = filter.startsWith("!");
        String fw = exclude ? filter.substring(1) : filter;
        int count = 0;
        for (int i = 0; i < this.model.bones.size(); i++) {
            YsmModelData.YsmBone b = this.model.bones.get(i);
            if (!this.shouldRenderBone(this.model, i)) {
                continue;
            }
            if (!fw.isEmpty()) {
                boolean match = b.name != null
                        && b.name.toLowerCase().contains(fw.toLowerCase());
                if (exclude ? match : !match) {
                    continue;
                }
            }
            count++;
        }
        return count;
    }

    public boolean isActive() {
        return this.isEnabled() && this.model != null && this.visibleBoneCount() > 0;
    }

    /**
     * 每帧由 Mixin 在 {@code LivingEntityRenderer.submit} 的 HEAD 调用。
     * 命中时记录当前世界矩阵，返回 true 表示本帧由 YSM 接管。
     */
    public boolean beginThirdPerson(PoseStack poseStack, LivingEntityRenderState state, Entity entity) {
        this.capturedState = null;
        this.capturedPlayer = null;
        this.handling = false;
        if (entity != mc.player || !(state instanceof AvatarRenderState avatarState)) {
            return false;
        }
        // 隐身时原版本来就不提交身体，交给原版处理（盔甲层等行为也保持原样）。
        if (avatarState.isInvisible) {
            return false;
        }
        if (!this.isActive()) {
            return false;
        }
        this.capturedState = avatarState;
        this.capturedPlayer = (LocalPlayer) entity;
        this.capturedMatrix.set(poseStack.last().pose());
        this.handling = true;
        return true;
    }

    public boolean isHandlingAvatar() {
        return this.handling;
    }

    /** 每帧由 Mixin 在 {@code submit} 的 RETURN 调用。 */
    public void endThirdPerson() {
        this.handling = false;
        this.capturedState = null;
        this.capturedPlayer = null;
    }

    /**
     * 由 Mixin 在原版提交身体模型处调用。返回 true = 已接管（调用方跳过原版身体）。
     * 渲染失败时返回 false，让原版身体回退显示。
     */
    public boolean submitThirdPersonBody(PoseStack poseStack, SubmitNodeCollector collector, Model<?> rendererModel) {
        if (!this.handling || this.capturedState == null || this.capturedPlayer == null) {
            return false;
        }
        try {
            this.renderThirdPerson(poseStack, collector, rendererModel);
            return true;
        } catch (Throwable t) {
            this.handling = false;
            if (++this.failCount > 20) {
                notify("render failed repeatedly, disabling");
                this.toggle();
            } else if (!this.failedLogged) {
                this.failedLogged = true;
                Constants.LOGGER.error("YsmModel: render failed", t);
                notify("render failed: " + t);
            }
            return false;
        }
    }

    private void renderThirdPerson(PoseStack poseStack, SubmitNodeCollector collector, Model<?> rendererModel) {
        AvatarRenderState state = this.capturedState;
        LocalPlayer player = this.capturedPlayer;
        float partialTick = mc.getDeltaTracker().getGameTimeDeltaPartialTick(true);

        // 原版身体提交被跳过，setupAnim 不会被调用；这里手动补上并采样骨骼旋转。
        if (rendererModel instanceof PlayerModel playerModel) {
            playerModel.setupAnim(state);
            sampleVanillaBoneRotations(playerModel);
        }

        int boneCount = this.model.bones.size();
        this.ensurePoseCapacity(boneCount);
        this.prepareBindPose();

        boolean openYsm = this.animation.is(AnimationMode.OpenYsm)
                && this.animationPlayer != null
                && this.animationPlayer.hasAnimations()
                && this.animationPlayer.apply(player, partialTick,
                this.poseRotationX, this.poseRotationY, this.poseRotationZ,
                this.posePositionX, this.posePositionY, this.posePositionZ,
                this.poseScaleX, this.poseScaleY, this.poseScaleZ);
        if (openYsm) {
            // OpenYSM 的移动/待机动画保持权威，只叠加头部追踪与原版挥击增量，
            // 避免整套复制原版姿势导致手臂/腿的双倍运动。
            this.applyHeadTracking();
            if (!YsmAnimationPlayer.isTaczGun(player.getMainHandItem())) {
                this.applyVanillaSwing(player, partialTick);
            }
        } else {
            this.applyVanillaAnimation(player, partialTick);
        }

        // 目标矩阵 = HEAD 时的世界矩阵 × YSM 位姿链；再换算成相对当前矩阵的增量。
        PoseStack scratch = new PoseStack();
        this.applyEntityRotations(player, scratch, state.bodyRot, partialTick, state.isUpsideDown);
        scratch.translate(0.0f, 0.01f, 0.0f);
        float s = this.scale.getValue().floatValue();
        scratch.scale(s * this.modelHorizontalScale, s * this.modelVerticalScale,
                s * this.modelHorizontalScale);
        this.helperMatrix.set(this.capturedMatrix).mul(scratch.last().pose());
        this.relativeMatrix.set(poseStack.last().pose()).invert().mul(this.helperMatrix);

        poseStack.pushPose();
        try {
            poseStack.mulPose(this.relativeMatrix);

            this.ensureMatrixCapacity(boneCount);
            for (int i = 0; i < boneCount; i++) {
                YsmModelData.YsmBone bone = this.model.bones.get(i);
                float px = bone.pivotX / 16.0f;
                float py = bone.pivotY / 16.0f;
                float pz = bone.pivotZ / 16.0f;
                this.poseLocal[i].identity()
                        .translate(-this.posePositionX[i] / 16.0f,
                                this.posePositionY[i] / 16.0f,
                                this.posePositionZ[i] / 16.0f)
                        .translate(px, py, pz)
                        .rotateZ(this.poseRotationZ[i])
                        .rotateY(this.poseRotationY[i])
                        .rotateX(this.poseRotationX[i])
                        .scale(this.poseScaleX[i], this.poseScaleY[i], this.poseScaleZ[i])
                        .translate(-bone.pivotX / 16.0f, -bone.pivotY / 16.0f, -bone.pivotZ / 16.0f);
            }
            // 递归合成全局矩阵（父链顺序无关；parent × local——子先绕自己 pivot 转再跟随父）
            for (int i = 0; i < boneCount; i++) {
                this.computeGlobal(i);
            }

            String filter = this.boneFilter.getValue();
            boolean exclude = filter.startsWith("!");
            String filterWord = exclude ? filter.substring(1) : filter;
            // 延迟执行的几何回调里只读这些预先算好的数据，避免读可变状态带来不一致。
            boolean[] draw = new boolean[boneCount];
            for (int i = 0; i < boneCount; i++) {
                YsmModelData.YsmBone bone = this.model.bones.get(i);
                draw[i] = this.shouldRenderBone(this.model, i)
                        // 吉他骨骼跳过渲染（bone2 = 手拿吉他，bone3 = 背背吉他）
                        && !(bone.name != null && (bone.name.equals("bone2") || bone.name.equals("bone3")))
                        && this.matchesFilter(bone, filterWord, exclude);
            }
            final int packedLight = state.lightCoords;
            final Matrix4f[] globals = this.poseGlobal;
            final List<YsmModelData.YsmBone> bones = this.model.bones;
            collector.submitCustomGeometry(poseStack, this.entityRenderType(), (pose, vc) -> {
                Matrix3f boneNormalMatrix = new Matrix3f();
                Vector3f transformedPosition = new Vector3f();
                Vector3f transformedNormal = new Vector3f();
                for (int i = 0; i < boneCount; i++) {
                    if (!draw[i]) {
                        continue;
                    }
                    Matrix4f global = globals[i];
                    boneNormalMatrix.set(global).invert().transpose();
                    for (YsmModelData.YsmCube cube : bones.get(i).cubes) {
                        for (YsmModelData.YsmQuad quad : cube.quads) {
                            boneNormalMatrix.transform(quad.normal, transformedNormal).normalize();
                            for (int j = 0; j < 4; j++) {
                                global.transformPosition(quad.positions[j], transformedPosition);
                                vc.addVertex(pose, transformedPosition.x, transformedPosition.y, transformedPosition.z)
                                        .setColor(255, 255, 255, 255)
                                        .setUv(quad.uvs[j].x, quad.uvs[j].y)
                                        .setOverlay(OverlayTexture.NO_OVERLAY)
                                        .setLight(packedLight)
                                        .setNormal(pose, transformedNormal.x, transformedNormal.y, transformedNormal.z);
                            }
                        }
                    }
                }
            });

            this.renderHeldItems(player, poseStack, collector, packedLight);
        } finally {
            poseStack.popPose();
        }
    }

    /** 第一人称手臂替换：由 Mixin 在 {@code AvatarRenderer.renderRightHand/renderLeftHand} 的 HEAD 调用。 */
    public boolean submitFirstPersonArm(HumanoidArm arm, PoseStack poseStack,
                                       SubmitNodeCollector collector, int packedLight) {
        YsmModelData data = this.armModel;
        LocalPlayer player = mc.player;
        if (!this.isEnabled() || data == null || data.bones.isEmpty() || player == null) {
            return false;
        }
        if (this.textureLocation == null) {
            return false;
        }
        try {
            float partialTick = mc.getDeltaTracker().getGameTimeDeltaPartialTick(true);
            int boneCount = data.bones.size();
            this.ensureArmPoseCapacity(boneCount);
            prepareBindPose(data, this.armPoseRotationX, this.armPoseRotationY,
                    this.armPoseRotationZ, this.armPosePositionX, this.armPosePositionY,
                    this.armPosePositionZ, this.armPoseScaleX, this.armPoseScaleY,
                    this.armPoseScaleZ);

            if (this.armAnimationPlayer != null && this.armAnimationPlayer.hasAnimations()) {
                this.armAnimationPlayer.apply(player, partialTick,
                        this.armPoseRotationX, this.armPoseRotationY, this.armPoseRotationZ,
                        this.armPosePositionX, this.armPosePositionY, this.armPosePositionZ,
                        this.armPoseScaleX, this.armPoseScaleY, this.armPoseScaleZ);
            }
            // 调用点的 poseStack 已含原版第一人称装备/挥击变换。没有专用手臂动画时
            // 保持绑定姿态，而不是复用第三人称骨骼角。

            this.ensureArmMatrixCapacity(boneCount);
            for (int i = 0; i < boneCount; i++) {
                YsmModelData.YsmBone bone = data.bones.get(i);
                float px = bone.pivotX / 16.0f;
                float py = bone.pivotY / 16.0f;
                float pz = bone.pivotZ / 16.0f;
                this.armLocal[i].identity()
                        .translate(-this.armPosePositionX[i] / 16.0f,
                                this.armPosePositionY[i] / 16.0f,
                                this.armPosePositionZ[i] / 16.0f)
                        .translate(px, py, pz)
                        .rotateZ(this.armPoseRotationZ[i])
                        .rotateY(this.armPoseRotationY[i])
                        .rotateX(this.armPoseRotationX[i])
                        .scale(this.armPoseScaleX[i], this.armPoseScaleY[i], this.armPoseScaleZ[i])
                        .translate(-bone.pivotX / 16.0f, -bone.pivotY / 16.0f, -bone.pivotZ / 16.0f);
            }
            for (int i = 0; i < boneCount; i++) {
                computeGlobal(data, i, this.armLocal, this.armGlobal, this.armComputed);
            }

            String filter = this.boneFilter.getValue();
            boolean exclude = filter.startsWith("!");
            String filterWord = exclude ? filter.substring(1) : filter;
            boolean[] draw = new boolean[boneCount];
            for (int i = 0; i < boneCount; i++) {
                draw[i] = this.shouldRenderBone(data, i)
                        && this.isHandBone(data, i, arm)
                        && this.matchesFilter(data.bones.get(i), filterWord, exclude);
            }

            poseStack.pushPose();
            try {
                if (arm == HumanoidArm.LEFT) {
                    poseStack.translate(0.25d, 1.8d, 0.0d);
                } else {
                    poseStack.translate(-0.25d, 1.8d, 0.0d);
                }
                poseStack.scale(-1.0f, -1.0f, 1.0f);
                final Matrix4f[] globals = this.armGlobal;
                final List<YsmModelData.YsmBone> bones = data.bones;
                collector.submitCustomGeometry(poseStack, this.entityRenderType(), (pose, vc) -> {
                    Matrix3f normalMatrix = new Matrix3f();
                    Vector3f transformedPosition = new Vector3f();
                    Vector3f transformedNormal = new Vector3f();
                    for (int i = 0; i < boneCount; i++) {
                        if (!draw[i]) {
                            continue;
                        }
                        Matrix4f boneMatrix = globals[i];
                        normalMatrix.set(boneMatrix).invert().transpose();
                        for (YsmModelData.YsmCube cube : bones.get(i).cubes) {
                            for (YsmModelData.YsmQuad quad : cube.quads) {
                                normalMatrix.transform(quad.normal, transformedNormal).normalize();
                                for (int j = 0; j < 4; j++) {
                                    boneMatrix.transformPosition(quad.positions[j], transformedPosition);
                                    vc.addVertex(pose, transformedPosition.x, transformedPosition.y, transformedPosition.z)
                                            .setColor(255, 255, 255, 255)
                                            .setUv(quad.uvs[j].x, quad.uvs[j].y)
                                            .setOverlay(OverlayTexture.NO_OVERLAY)
                                            .setLight(packedLight)
                                            .setNormal(pose, transformedNormal.x, transformedNormal.y, transformedNormal.z);
                                }
                            }
                        }
                    }
                });
            } finally {
                poseStack.popPose();
            }
            return true;
        } catch (Throwable t) {
            if (!this.failedLogged) {
                this.failedLogged = true;
                Constants.LOGGER.error("YsmModel: first-person arm render failed", t);
            }
            return false;
        }
    }

    private RenderType entityRenderType() {
        Identifier texture = this.textureLocation != null
                ? this.textureLocation : DefaultPlayerSkin.getDefaultTexture();
        return RenderTypes.entityCutout(texture);
    }

    // ================= 骨骼矩阵 =================

    private void sampleVanillaBoneRotations(PlayerModel playerModel) {
        float[] out = VANILLA_BONE_ROTATIONS;
        out[0] = playerModel.head.xRot;
        out[1] = playerModel.head.yRot;
        out[2] = playerModel.head.zRot;
        out[3] = playerModel.body.xRot;
        out[4] = playerModel.body.yRot;
        out[5] = playerModel.body.zRot;
        out[6] = playerModel.leftArm.xRot;
        out[7] = playerModel.leftArm.yRot;
        out[8] = playerModel.leftArm.zRot;
        out[9] = playerModel.rightArm.xRot;
        out[10] = playerModel.rightArm.yRot;
        out[11] = playerModel.rightArm.zRot;
        out[12] = playerModel.leftLeg.xRot;
        out[13] = playerModel.leftLeg.yRot;
        out[14] = playerModel.leftLeg.zRot;
        out[15] = playerModel.rightLeg.xRot;
        out[16] = playerModel.rightLeg.yRot;
        out[17] = playerModel.rightLeg.zRot;
    }

    private void ensureMatrixCapacity(int boneCount) {
        if (this.poseLocal.length >= boneCount) {
            java.util.Arrays.fill(this.poseComputed, 0, boneCount, false);
            return;
        }
        this.poseLocal = fillMatrices(boneCount);
        this.poseGlobal = fillMatrices(boneCount);
        this.poseComputed = new boolean[boneCount];
    }

    private void ensureArmMatrixCapacity(int boneCount) {
        if (this.armLocal.length >= boneCount) {
            java.util.Arrays.fill(this.armComputed, 0, boneCount, false);
            return;
        }
        this.armLocal = fillMatrices(boneCount);
        this.armGlobal = fillMatrices(boneCount);
        this.armComputed = new boolean[boneCount];
    }

    private static Matrix4f[] fillMatrices(int size) {
        Matrix4f[] pool = new Matrix4f[size];
        for (int i = 0; i < size; i++) {
            pool[i] = new Matrix4f();
        }
        return pool;
    }

    private void computeGlobal(int index) {
        if (this.poseComputed[index]) {
            return;
        }
        this.poseComputed[index] = true;
        Matrix4f result = this.poseGlobal[index].set(this.poseLocal[index]);
        YsmModelData.YsmBone bone = this.model.bones.get(index);
        int parent = bone.parentIdx;
        if (parent >= 0 && parent < this.poseLocal.length && parent != index) {
            this.computeGlobal(parent);
            this.poseGlobal[parent].mul(this.poseLocal[index], result);
        }
    }

    private static void computeGlobal(YsmModelData data, int index, Matrix4f[] local,
                                     Matrix4f[] cache, boolean[] computed) {
        if (computed[index]) {
            return;
        }
        computed[index] = true;
        Matrix4f result = cache[index].set(local[index]);
        YsmModelData.YsmBone bone = data.bones.get(index);
        int parent = bone.parentIdx;
        if (parent >= 0 && parent < local.length && parent != index) {
            computeGlobal(data, parent, local, cache, computed);
            cache[parent].mul(local[index], result);
        }
    }

    private void ensurePoseCapacity(int boneCount) {
        if (this.poseRotationX.length == boneCount) {
            return;
        }
        this.poseRotationX = new float[boneCount];
        this.poseRotationY = new float[boneCount];
        this.poseRotationZ = new float[boneCount];
        this.posePositionX = new float[boneCount];
        this.posePositionY = new float[boneCount];
        this.posePositionZ = new float[boneCount];
        this.poseScaleX = new float[boneCount];
        this.poseScaleY = new float[boneCount];
        this.poseScaleZ = new float[boneCount];
        this.ensureMatrixCapacity(boneCount);
    }

    private void ensureArmPoseCapacity(int boneCount) {
        if (this.armPoseRotationX.length == boneCount) {
            return;
        }
        this.armPoseRotationX = new float[boneCount];
        this.armPoseRotationY = new float[boneCount];
        this.armPoseRotationZ = new float[boneCount];
        this.armPosePositionX = new float[boneCount];
        this.armPosePositionY = new float[boneCount];
        this.armPosePositionZ = new float[boneCount];
        this.armPoseScaleX = new float[boneCount];
        this.armPoseScaleY = new float[boneCount];
        this.armPoseScaleZ = new float[boneCount];
        this.ensureArmMatrixCapacity(boneCount);
    }

    private void prepareBindPose() {
        prepareBindPose(this.model, this.poseRotationX, this.poseRotationY, this.poseRotationZ,
                this.posePositionX, this.posePositionY, this.posePositionZ,
                this.poseScaleX, this.poseScaleY, this.poseScaleZ);
    }

    private static void prepareBindPose(YsmModelData data, float[] rotationX, float[] rotationY,
                                        float[] rotationZ, float[] positionX, float[] positionY,
                                        float[] positionZ, float[] scaleX, float[] scaleY,
                                        float[] scaleZ) {
        for (int i = 0; i < data.bones.size(); i++) {
            YsmModelData.YsmBone bone = data.bones.get(i);
            rotationX[i] = bone.rotX;
            rotationY[i] = bone.rotY;
            rotationZ[i] = bone.rotZ;
            positionX[i] = positionY[i] = positionZ[i] = 0.0f;
            scaleX[i] = scaleY[i] = scaleZ[i] = 1.0f;
        }
    }

    private boolean shouldRenderBone(YsmModelData data, int index) {
        if (!this.characterOnly.getValue()) {
            return true;
        }
        boolean[] visibility = this.characterFilterCache.get(data);
        if (visibility == null || visibility.length != data.bones.size()) {
            visibility = new boolean[data.bones.size()];
            for (int i = 0; i < data.bones.size(); i++) {
                int current = i;
                boolean visible = true;
                while (current >= 0 && current < data.bones.size()) {
                    YsmModelData.YsmBone bone = data.bones.get(current);
                    if (isCharacterOnlyExcludedName(bone.name)) {
                        visible = false;
                        break;
                    }
                    current = bone.parentIdx;
                }
                visibility[i] = visible;
            }
            this.characterFilterCache.put(data, visibility);
        }
        return index >= 0 && index < visibility.length && visibility[index];
    }

    private static boolean isCharacterOnlyExcludedName(String name) {
        if (name == null || name.isEmpty()) {
            return false;
        }
        String normalized = name.toLowerCase(Locale.ROOT)
                .replace("_", "")
                .replace("-", "")
                .replace(" ", "");
        if (normalized.endsWith("locator") || normalized.startsWith("molang")) {
            return true;
        }
        for (String token : CHARACTER_ONLY_AUXILIARY_TOKENS) {
            if (normalized.contains(token)) {
                return true;
            }
        }
        return false;
    }

    private boolean matchesFilter(YsmModelData.YsmBone bone, String filter, boolean exclude) {
        if (filter.isEmpty()) {
            return true;
        }
        boolean match = bone.name != null && bone.name.toLowerCase().contains(filter.toLowerCase());
        return exclude ? !match : match;
    }

    private boolean isHandBone(YsmModelData data, int index, HumanoidArm arm) {
        int current = index;
        String target = arm == HumanoidArm.LEFT ? "left" : "right";
        while (current >= 0 && current < data.bones.size()) {
            String name = data.bones.get(current).name == null
                    ? "" : data.bones.get(current).name.toLowerCase();
            if (name.equals(arm == HumanoidArm.LEFT ? "leftarm" : "rightarm")
                    || name.equals(arm == HumanoidArm.LEFT ? "arml" : "armr")) {
                return true;
            }
            current = data.bones.get(current).parentIdx;
        }
        // 有些只有手臂的导出文件省略了 LeftArm/RightArm 根骨骼，直接以分支命名；
        // 这类模型也要能正常渲染。
        String name = data.bones.get(index).name == null
                ? "" : data.bones.get(index).name.toLowerCase();
        return name.contains(target + "arm") || name.contains(target + "hand")
                || name.contains(target + "forearm");
    }

    // ================= 原版骨骼绑定 =================

    private int findVanillaTorsoBone() {
        String[] names = {"UpperBody", "Body", "UpBody", "MUpperBody", "MUpBody"};
        for (String wanted : names) {
            for (int i = 0; i < this.model.bones.size(); i++) {
                String actual = this.model.bones.get(i).name;
                if (actual != null && actual.equalsIgnoreCase(wanted)) {
                    return i;
                }
            }
        }
        return -1;
    }

    private boolean isDescendantOf(int index, int ancestor) {
        int current = index;
        while (current >= 0 && current < this.model.bones.size()) {
            YsmModelData.YsmBone bone = this.model.bones.get(current);
            current = bone.parentIdx;
            if (current == ancestor) {
                return true;
            }
        }
        return false;
    }

    private void applyVanillaAnimation(LocalPlayer player, float partialTick) {
        float[] rotation = VANILLA_BONE_ROTATIONS;
        // YSM 与 OpenYSM 使用同一套轴约定：X/Y 取反，Z 保持。
        float headX = -rotation[0];
        float headY = -rotation[1];
        float headZ = rotation[2];
        float bodyX = -rotation[3];
        float bodyY = -rotation[4];
        float bodyZ = rotation[5];
        float leftArmX = -rotation[6];
        float leftArmY = -rotation[7];
        float leftArmZ = rotation[8];
        float rightArmX = -rotation[9];
        float rightArmY = -rotation[10];
        float rightArmZ = rotation[11];
        float leftLegX = -rotation[12];
        float leftLegY = -rotation[13];
        float leftLegZ = rotation[14];
        float rightLegX = -rotation[15];
        float rightLegY = -rotation[16];
        float rightLegZ = rotation[17];
        int torsoBone = this.findVanillaTorsoBone();

        // 骨骼旋转来自原版 PlayerModel 完整 setupAnim 之后的结果。这里不要重建攻击或持物姿势，
        // 否则会二次施加这些旋转，在 Killaura 攻击时最明显。
        if (this.flipLeftWalk.getValue()) leftArmX = -leftArmX;
        if (this.flipRightWalk.getValue()) rightArmX = -rightArmX;
        if (this.flipOther.getValue()) {
            bodyY = -bodyY;
            leftArmX = -leftArmX;
            leftArmY = -leftArmY;
            leftArmZ = -leftArmZ;
            rightArmX = -rightArmX;
            rightArmY = -rightArmY;
            rightArmZ = -rightArmZ;
        }

        for (int i = 0; i < this.model.bones.size(); i++) {
            YsmModelData.YsmBone bone = this.model.bones.get(i);
            String name = bone.name == null ? "" : bone.name;
            // YSM 玩家模型常用若干空骨骼层层包住躯干（UpBody/MUpperBody/UpperBody）。
            // 原版 body 旋转只能作用于一个 pivot；套到每个包装骨骼上会让下蹲角度翻倍。
            // 其子级 head/arm 的旋转相对该 pivot 补偿，因为原版 PlayerModel 里它们是兄弟节点。
            boolean underTorso = torsoBone >= 0 && i != torsoBone && this.isDescendantOf(i, torsoBone);
            float childBodyX = underTorso ? bodyX : 0.0f;
            float childBodyY = underTorso ? bodyY : 0.0f;
            float childBodyZ = underTorso ? bodyZ : 0.0f;
            // 几何自带旋转是绑定姿态；原版动画是叠加在其上的偏移，
            // 直接替换会丢掉模型作者对齐好的腿/臂姿态。
            if (i == torsoBone) {
                this.poseRotationX[i] = bone.rotX + bodyX;
                this.poseRotationY[i] = bone.rotY + bodyY;
                this.poseRotationZ[i] = bone.rotZ + bodyZ;
            } else if (name.equals("LeftLeg") || name.equals("LegL")) {
                this.poseRotationX[i] = bone.rotX + leftLegX;
                this.poseRotationY[i] = bone.rotY + leftLegY;
                this.poseRotationZ[i] = bone.rotZ + leftLegZ;
            } else if (name.equals("RightLeg") || name.equals("LegR")) {
                this.poseRotationX[i] = bone.rotX + rightLegX;
                this.poseRotationY[i] = bone.rotY + rightLegY;
                this.poseRotationZ[i] = bone.rotZ + rightLegZ;
            } else if (name.equals("LeftArm") || name.equals("ArmL")) {
                this.poseRotationX[i] = bone.rotX + leftArmX - childBodyX;
                this.poseRotationY[i] = bone.rotY + leftArmY - childBodyY;
                this.poseRotationZ[i] = bone.rotZ + leftArmZ - childBodyZ;
            } else if (name.equals("RightArm") || name.equals("ArmR")) {
                this.poseRotationX[i] = bone.rotX + rightArmX - childBodyX;
                this.poseRotationY[i] = bone.rotY + rightArmY - childBodyY;
                this.poseRotationZ[i] = bone.rotZ + rightArmZ - childBodyZ;
            } else if (name.equals("Head") || name.equals("head")) {
                this.poseRotationX[i] = bone.rotX + headX - childBodyX;
                this.poseRotationY[i] = bone.rotY + headY - childBodyY;
                this.poseRotationZ[i] = bone.rotZ + headZ - childBodyZ;
            }
        }
    }

    private void applyHeadTracking() {
        float[] rotation = VANILLA_BONE_ROTATIONS;
        for (int i = 0; i < this.model.bones.size(); i++) {
            YsmModelData.YsmBone bone = this.model.bones.get(i);
            String name = bone.name == null ? "" : bone.name;
            if (name.equals("Head") || name.equals("head")) {
                this.poseRotationX[i] -= rotation[0];
                this.poseRotationY[i] -= rotation[1];
                this.poseRotationZ[i] += rotation[2];
            }
        }
    }

    /**
     * 只施加 HumanoidModel 的攻击增量。待机/行走/奔跑/下蹲由 OpenYSM 负责，
     * 因此那些轨道绝不会被叠加两次。
     */
    private void applyVanillaSwing(LocalPlayer player, float partialTick) {
        float attack = player.getAttackAnim(partialTick);
        if (attack <= 0.001f) return;

        HumanoidArm mainArm = player.getMainArm();
        InteractionHand swingHand = player.swingingArm;
        HumanoidArm attackArm = swingHand == InteractionHand.MAIN_HAND
                ? mainArm
                : (mainArm == HumanoidArm.LEFT
                ? HumanoidArm.RIGHT
                : HumanoidArm.LEFT);

        float bodyYaw = Mth.sin(Mth.sqrt(attack) * Mth.TWO_PI) * 0.2f;
        if (attackArm == HumanoidArm.LEFT) {
            bodyYaw = -bodyYaw;
        }
        float eased = 1.0f - attack;
        eased *= eased;
        eased *= eased;
        eased = 1.0f - eased;
        float armX = -(Mth.sin(eased * Mth.PI) * 1.2f
                + Mth.sin(attack * Mth.PI)
                * -(VANILLA_BONE_ROTATIONS[0] - 0.7f) * 0.75f);
        float armY = bodyYaw * 2.0f;
        float armZ = Mth.sin(attack * Mth.PI) * -0.4f;
        int torsoBone = this.findVanillaTorsoBone();

        for (int i = 0; i < this.model.bones.size(); i++) {
            YsmModelData.YsmBone bone = this.model.bones.get(i);
            String name = bone.name == null ? "" : bone.name;
            if (i == torsoBone) {
                this.poseRotationY[i] -= bodyYaw;
                continue;
            }
            boolean leftArm = name.equalsIgnoreCase("LeftArm") || name.equalsIgnoreCase("ArmL");
            boolean rightArm = name.equalsIgnoreCase("RightArm") || name.equalsIgnoreCase("ArmR");
            if (leftArm) {
                // 原版把躯干的攻击 yaw 加到左臂的 X 轴上。
                this.poseRotationX[i] -= bodyYaw;
            }
            if ((leftArm || rightArm)
                    && (torsoBone < 0 || !this.isDescendantOf(i, torsoBone))) {
                // 常规 YSM 骨架从 UpperBody 继承这个量；扁平骨架需要显式补上等效的局部 yaw。
                this.poseRotationY[i] -= bodyYaw;
            }
            boolean selectedArm = attackArm == HumanoidArm.LEFT ? leftArm : rightArm;
            if (selectedArm) {
                // YSM/OpenYSM 相对原版把 X/Y 取反并保留 Z。
                this.poseRotationX[i] -= armX;
                this.poseRotationY[i] -= armY;
                this.poseRotationZ[i] += armZ;
            }
        }
    }

    private void applyEntityRotations(LocalPlayer player, PoseStack poseStack,
                                      float bodyYaw, float partialTick, boolean upsideDown) {
        if (player.getPose() == Pose.SLEEPING) {
            Direction bedDirection = player.getBedOrientation();
            if (bedDirection != null) {
                float eyeHeight = player.getEyeHeight(Pose.STANDING) - 0.1f;
                poseStack.translate(-bedDirection.getStepX() * eyeHeight, 0.0f,
                        -bedDirection.getStepZ() * eyeHeight);
            }
        }
        if (player.isFullyFrozen()) {
            bodyYaw += Mth.cos(player.tickCount * 3.25f) * (float) Math.PI * 0.4f;
        }
        if (player.getPose() != Pose.SLEEPING) {
            poseStack.mulPose(Axis.YP.rotationDegrees(180.0f - bodyYaw));
        }
        if (player.deathTime > 0) {
            float death = ((float) player.deathTime + partialTick - 1.0f) / 20.0f * 1.6f;
            death = Mth.sqrt(death);
            poseStack.mulPose(Axis.ZP.rotationDegrees(Math.min(death, 1.0f) * 90.0f));
        } else if (player.isAutoSpinAttack()) {
            poseStack.mulPose(Axis.XP.rotationDegrees(-90.0f - player.getXRot()));
            poseStack.mulPose(Axis.YP.rotationDegrees(
                    ((float) player.tickCount + partialTick) * -75.0f));
        } else if (player.getPose() == Pose.SLEEPING) {
            Direction direction = player.getBedOrientation();
            float rotation = direction == null ? bodyYaw : switch (direction) {
                case SOUTH -> 90.0f;
                case WEST -> 0.0f;
                case NORTH -> 270.0f;
                case EAST -> 180.0f;
                default -> bodyYaw;
            };
            poseStack.mulPose(Axis.YP.rotationDegrees(rotation));
            poseStack.mulPose(Axis.ZP.rotationDegrees(90.0f));
            poseStack.mulPose(Axis.YP.rotationDegrees(270.0f));
        } else if (upsideDown) {
            poseStack.translate(0.0f, player.getBbHeight() + 0.1f, 0.0f);
            poseStack.mulPose(Axis.ZP.rotationDegrees(180.0f));
        }

        float swimAmount = player.getSwimAmount(partialTick);
        if (player.isFallFlying()) {
            float flyingTicks = (float) player.getFallFlyingTicks() + partialTick;
            float flyingAmount = Mth.clamp(flyingTicks * flyingTicks / 100.0f, 0.0f, 1.0f);
            if (!player.isAutoSpinAttack()) {
                poseStack.mulPose(Axis.XP.rotationDegrees(
                        flyingAmount * (-90.0f - player.getXRot())));
            }
            Vec3 view = player.getViewVector(partialTick);
            Vec3 movement = player.avatarState().deltaMovementOnPreviousTick()
                    .lerp(player.getDeltaMovement(), partialTick);
            double movementHorizontal = movement.horizontalDistanceSqr();
            double viewHorizontal = view.horizontalDistanceSqr();
            if (movementHorizontal > 0.0 && viewHorizontal > 0.0) {
                double dot = (movement.x * view.x + movement.z * view.z)
                        / Math.sqrt(movementHorizontal * viewHorizontal);
                double cross = movement.x * view.z - movement.z * view.x;
                poseStack.mulPose(Axis.YP.rotation(
                        (float) (Math.signum(cross) * Math.acos(Mth.clamp(dot, -1.0, 1.0)))));
            }
        } else if (swimAmount > 0.0f) {
            float targetPitch = player.isInWater() ? -90.0f - player.getXRot() : -90.0f;
            poseStack.mulPose(Axis.XP.rotationDegrees(
                    Mth.lerp(swimAmount, 0.0f, targetPitch)));
            if (player.isVisuallySwimming()) {
                poseStack.translate(0.0f, -1.0f, 0.3f);
            }
        }
    }

    // ================= 手持物品 =================

    private void renderHeldItems(LocalPlayer player, PoseStack poseStack,
                                 SubmitNodeCollector collector, int packedLight) {
        if (!this.showItem.getValue()) {
            return;
        }
        try {
            float itemSize = this.itemScale.getValue().floatValue();
            HumanoidArm mainArm = player.getMainArm();
            HumanoidArm offArm = mainArm == HumanoidArm.RIGHT ? HumanoidArm.LEFT : HumanoidArm.RIGHT;
            this.renderHeldItem(player, player.getMainHandItem(),
                    mainArm == HumanoidArm.RIGHT ? "RightHandLocator" : "LeftHandLocator",
                    mainArm == HumanoidArm.RIGHT
                            ? ItemDisplayContext.THIRD_PERSON_RIGHT_HAND
                            : ItemDisplayContext.THIRD_PERSON_LEFT_HAND,
                    mainArm == HumanoidArm.LEFT,
                    itemSize, poseStack, collector, packedLight);
            this.renderHeldItem(player, player.getOffhandItem(),
                    offArm == HumanoidArm.RIGHT ? "RightHandLocator" : "LeftHandLocator",
                    offArm == HumanoidArm.RIGHT
                            ? ItemDisplayContext.THIRD_PERSON_RIGHT_HAND
                            : ItemDisplayContext.THIRD_PERSON_LEFT_HAND,
                    offArm == HumanoidArm.LEFT,
                    itemSize, poseStack, collector, packedLight);
        } catch (Exception ignored) {
        }
    }

    private void renderHeldItem(LocalPlayer player, ItemStack item, String locatorName,
                                ItemDisplayContext displayContext, boolean leftHand, float itemSize,
                                PoseStack poseStack, SubmitNodeCollector collector, int packedLight) {
        if (item == null || item.isEmpty()) {
            return;
        }
        // 枪械姿势围绕专用 PistolLocator/RifleLocator 动画，而不是普通手部 locator。
        // Clay 武器共用这些 OpenYSM 姿势，尽管它们的物品模型不是真正的 TACZ IGun。
        if (this.renderGunItem(player, item, displayContext, leftHand, itemSize,
                poseStack, collector, packedLight)) {
            return;
        }
        int locator = this.findBone(locatorName);
        if (locator < 0) {
            return;
        }
        Matrix4f locatorMatrix = this.computeLocatorGlobal(locator);
        poseStack.pushPose();
        // OpenYSM 施加完整的 locator 链（平移、旋转、缩放）。只施加原点位置会让
        // 所有物品朝向模型默认方向，而不是跟随动画中的手。
        poseStack.mulPose(locatorMatrix);
        poseStack.translate(0.0d, -0.0625d, -0.1d);
        poseStack.mulPose(Axis.XP.rotationDegrees(-90.0f));
        poseStack.scale(itemSize, itemSize, itemSize);
        mc.gameRenderer.itemInHandRenderer.renderItem(player, item, displayContext,
                poseStack, collector, packedLight);
        poseStack.popPose();
    }

    private boolean renderGunItem(LocalPlayer player, ItemStack item,
                                  ItemDisplayContext displayContext, boolean leftHand,
                                  float itemSize, PoseStack poseStack,
                                  SubmitNodeCollector collector, int packedLight) {
        if (!YsmAnimationPlayer.isTaczGun(item)) {
            return false;
        }
        boolean nativeTacz = YsmAnimationPlayer.isNativeTaczGun(item);
        if (!nativeTacz && !this.animation.is(AnimationMode.OpenYsm)) {
            return false;
        }
        String gunType = YsmAnimationPlayer.taczGunType(item);
        if (gunType == null) {
            return false;
        }
        String locatorName = gunType.equals("pistol") ? "PistolLocator" : "RifleLocator";
        int locator = this.findBone(locatorName);
        if (locator < 0) {
            return false;
        }
        Matrix4f locatorMatrix = this.computeLocatorGlobal(locator);
        poseStack.pushPose();
        poseStack.mulPose(locatorMatrix);
        if (nativeTacz) {
            // 原生 TACZ：模型按 FIXED 上下文建模，套用枪位专用朝向
            if (gunType.equals("pistol")) {
                poseStack.translate(0.0d, -0.125d, 0.0d);
                poseStack.scale(0.65f * itemSize, 0.65f * itemSize, 0.65f * itemSize);
                poseStack.mulPose(Axis.YP.rotationDegrees(-90.0f));
                poseStack.mulPose(Axis.ZP.rotationDegrees(90.0f));
            } else {
                poseStack.scale(0.65f * itemSize, 0.65f * itemSize, 0.65f * itemSize);
                poseStack.mulPose(Axis.YP.rotationDegrees(-180.0f));
            }
            mc.gameRenderer.itemInHandRenderer.renderItem(player, item,
                    ItemDisplayContext.FIXED, poseStack, collector, packedLight);
        } else {
            // Clay（非原生 TACZ）：物品自带三视角变换，不能套 TACZ 专用的枪位旋转
            // （否则朝向被多转 180°/侧翻 → “朝向和原版不一致”）。这里改用与
            // "Vanilla Animation" 手部路径一致的方向，但仍锚定枪位 locator 以跟随
            // TACZ 上半身动画。
            poseStack.translate(0.0d, -0.0625d, -0.1d);
            poseStack.mulPose(Axis.XP.rotationDegrees(-90.0f));
            poseStack.scale(itemSize, itemSize, itemSize);
            mc.gameRenderer.itemInHandRenderer.renderItem(player, item, displayContext,
                    poseStack, collector, packedLight);
        }
        poseStack.popPose();
        return true;
    }

    /**
     * Locator 末端没有普通骨骼的 pivot 回退平移；这是 OpenYSM
     * RenderUtils.prepMatrixForLocator 的关键区别，物品因此会落在预设手持点。
     */
    private Matrix4f computeLocatorGlobal(int index) {
        Matrix4f parent = null;
        YsmModelData.YsmBone bone = this.model.bones.get(index);
        int parentIdx = bone.parentIdx;
        if (parentIdx >= 0 && parentIdx < this.poseGlobal.length && parentIdx != index) {
            this.computeGlobal(parentIdx);
            parent = this.poseGlobal[parentIdx];
        }
        Matrix4f locatorLocal = new Matrix4f().identity()
                .translate(-this.posePositionX[index] / 16.0f,
                        this.posePositionY[index] / 16.0f,
                        this.posePositionZ[index] / 16.0f)
                .translate(bone.pivotX / 16.0f, bone.pivotY / 16.0f, bone.pivotZ / 16.0f)
                .rotateZ(this.poseRotationZ[index])
                .rotateY(this.poseRotationY[index])
                .rotateX(this.poseRotationX[index])
                .scale(this.poseScaleX[index], this.poseScaleY[index], this.poseScaleZ[index]);
        return parent == null ? locatorLocal : new Matrix4f(parent).mul(locatorLocal);
    }

    private int findBone(String name) {
        Integer exact = this.model.boneIndex.get(name);
        if (exact != null) {
            return exact;
        }
        String lower = name.toLowerCase(Locale.ROOT);
        for (int i = 0; i < this.model.bones.size(); i++) {
            String boneName = this.model.bones.get(i).name;
            if (boneName != null && boneName.toLowerCase(Locale.ROOT).contains(lower)) {
                return i;
            }
        }
        return -1;
    }
}
