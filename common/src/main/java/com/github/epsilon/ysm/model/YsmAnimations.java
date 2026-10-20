package com.github.epsilon.ysm.model;

import com.github.epsilon.ysm.resource.pojo.RawYsmModel;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * YSM 动画系统（移植自 OpenYSM，简化版——不含 Molang 表达式/控制器状态机）。
 *
 * 数据链：RawYsmModel.animationFiles（animType=1 main）→ YsmAnimations
 * → 每帧按玩家状态选动画（idle/walk/run/jump/attacked）→ keyframe 插值
 * （linear/catmullrom）→ 每骨骼 rot/pos/scale（弧度+像素，X/Y 已取反——
 * 与 parseGeometry 的绑定 rotation 同一约定）。
 */
public class YsmAnimations {

    public static final int LINEAR = 0;
    public static final int CATMULLROM = 1;

    public static class KeyFrame {
        public float tick;
        public int mode;
        public float[] values = new float[3];
    }

    public static class BoneAnim {
        public List<KeyFrame> rotation = new ArrayList<>();
        public List<KeyFrame> position = new ArrayList<>();
        public List<KeyFrame> scale = new ArrayList<>();
    }

    public static class Anim {
        public String name;
        public float lengthTicks;
        public boolean loop;
        public Map<String, BoneAnim> bones = new HashMap<>();
    }

    public final Map<String, Anim> animations = new HashMap<>();

    /** 从 RawYsmModel 解析动画（animType=1 main 动画文件） */
    public static YsmAnimations parse(RawYsmModel raw) {
        YsmAnimations result = new YsmAnimations();
        if (raw == null || raw.mainEntity == null || raw.mainEntity.animationFiles == null) {
            return result;
        }
        for (RawYsmModel.RawAnimationFile file : raw.mainEntity.animationFiles.values()) {
            if (file.animType != 1) {
                continue; // 只解析 main 动画
            }
            if (file.animations == null) {
                continue;
            }
            for (RawYsmModel.RawAnimation ra : file.animations.values()) {
                if (ra == null) {
                    continue;
                }
                Anim anim = new Anim();
                anim.name = ra.name;
                anim.lengthTicks = Math.max(0.01f, ra.length * 20.0f);
                anim.loop = ra.loopMode == 1;
                if (ra.boneAnimations != null) {
                    for (RawYsmModel.RawBoneAnimation ba : ra.boneAnimations) {
                        if (ba == null || ba.boneName == null) {
                            continue;
                        }
                        BoneAnim b = new BoneAnim();
                        if (ba.rotation != null) {
                            b.rotation = parseKeyframes(ba.rotation, true);
                        }
                        if (ba.position != null) {
                            b.position = parseKeyframes(ba.position, false);
                        }
                        if (ba.scale != null) {
                            b.scale = parseKeyframes(ba.scale, false);
                        }
                        anim.bones.put(ba.boneName, b);
                    }
                }
                result.animations.put(anim.name, anim);
            }
        }
        return result;
    }

    /** 移植 YSMClientMapper.parseKeyframes：timestamp×20 → tick；interpolationMode==2 → CATMULLROM */
    private static List<KeyFrame> parseKeyframes(List<RawYsmModel.RawKeyframe> frames, boolean isRotation) {
        List<KeyFrame> out = new ArrayList<>();
        for (RawYsmModel.RawKeyframe rf : frames) {
            if (rf == null) {
                continue;
            }
            KeyFrame kf = new KeyFrame();
            kf.tick = rf.timestamp * 20.0f;
            kf.mode = rf.interpolationMode == 2 ? CATMULLROM : LINEAR;
            float[] v = new float[3];
            for (int i = 0; i < 3; i++) {
                Object o = rf.postData != null && i < rf.postData.length ? rf.postData[i] : null;
                if (!(o instanceof Number) && rf.preData != null && i < rf.preData.length) {
                    o = rf.preData[i];
                }
                v[i] = o instanceof Number num ? num.floatValue() : 0.0f;
                if (isRotation) {
                    // 移植 RotationValue.convert：toRadians + X/Y 取负（Z 不变）
                    v[i] = (float) Math.toRadians(v[i]);
                    if (i < 2) {
                        v[i] = -v[i];
                    }
                }
            }
            kf.values = v;
            out.add(kf);
        }
        return out;
    }

    /** 线性插值 */
    private static float[] lerp(float[] a, float[] b, float t) {
        return new float[]{
                a[0] + (b[0] - a[0]) * t,
                a[1] + (b[1] - a[1]) * t,
                a[2] + (b[2] - a[2]) * t};
    }

    /** Catmull-Rom（0.5 切线，移植 MathUtil.catmullRom） */
    private static float catmull(float p0, float p1, float p2, float p3, float t) {
        float t2 = t * t;
        float t3 = t2 * t;
        return 0.5f * ((2.0f * p1)
                + (-p0 + p2) * t
                + (2.0f * p0 - 5.0f * p1 + 4.0f * p2 - p3) * t2
                + (-p0 + 3.0f * p1 - 3.0f * p2 + p3) * t3);
    }

    /** 在动画时间 tick 处求值通道。
     *  返回 {x, y, z, progress}——progress = 当前关键帧内播放进度（0~1），
     *  官方 applyRotationBlendTo 用它衰减：偏移×(1-progress)。
     *  frames 为空返回 null；单帧返回该帧值 + progress=1。 */
    public static float[] eval(List<KeyFrame> frames, float tick) {
        if (frames == null || frames.isEmpty()) {
            return null;
        }
        if (frames.size() == 1) {
            float[] v = frames.get(0).values;
            return new float[]{v[0], v[1], v[2], 1.0f};
        }
        if (tick <= frames.get(0).tick) {
            float[] v = frames.get(0).values;
            return new float[]{v[0], v[1], v[2], 0.0f};
        }
        if (tick >= frames.get(frames.size() - 1).tick) {
            float[] v = frames.get(frames.size() - 1).values;
            return new float[]{v[0], v[1], v[2], 1.0f};
        }
        KeyFrame prev = frames.get(0);
        KeyFrame next = frames.get(frames.size() - 1);
        int prevIdx = 0;
        for (int i = 0; i < frames.size() - 1; i++) {
            if (tick >= frames.get(i).tick && tick <= frames.get(i + 1).tick) {
                prev = frames.get(i);
                next = frames.get(i + 1);
                prevIdx = i;
                break;
            }
        }
        float span = Math.max(0.0001f, next.tick - prev.tick);
        float t = (tick - prev.tick) / span;
        float[] out;
        if (prev.mode == CATMULLROM && prevIdx > 0 && prevIdx + 1 < frames.size() - 1) {
            float[] p0 = frames.get(prevIdx - 1).values;
            float[] p1 = prev.values;
            float[] p2 = next.values;
            float[] p3 = frames.get(prevIdx + 2).values;
            out = new float[]{
                    catmull(p0[0], p1[0], p2[0], p3[0], t),
                    catmull(p0[1], p1[1], p2[1], p3[1], t),
                    catmull(p0[2], p1[2], p2[2], p3[2], t)};
        } else {
            out = lerp(prev.values, next.values, t);
        }
        // 官方 progress = 当前关键帧内进度（KeyFramePoint: (tick - frame.startTick)/frame.totalTick）
        float progress = Math.min(1.0f, Math.max(0.0f, (tick - prev.tick) / span));
        return new float[]{out[0], out[1], out[2], progress};
    }
}
