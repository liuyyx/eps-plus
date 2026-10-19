package com.github.epsilon.ysm.model;

import com.github.epsilon.ysm.resource.pojo.RawYsmModel;
import org.joml.Vector2f;
import org.joml.Vector3f;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * OpenZen 的轻量 YSM 网格数据（移植自 YSMClientMapper.buildMesh，
 * 去掉 geckolib 依赖）：骨骼树 + 四边形 + UV + 法线 + 纹理 PNG。
 */
public class YsmModelData {
    public final List<YsmBone> bones = new ArrayList<>();
    /** 骨骼名 → 索引（渲染时快速查找） */
    public final Map<String, Integer> boneIndex = new HashMap<>();
    /** 纹理名 → PNG 字节 */
    public final Map<String, byte[]> textures = new LinkedHashMap<>();

    public static class YsmBone {
        public String name;
        public float pivotX, pivotY, pivotZ;
        public float rotX, rotY, rotZ;
        public int parentIdx = -1;
        public boolean glow;
        public final List<YsmCube> cubes = new ArrayList<>();
    }

    public static class YsmCube {
        public final List<YsmQuad> quads = new ArrayList<>();
    }

    public static class YsmQuad {
        public final Vector3f normal = new Vector3f();
        public final Vector3f[] positions = new Vector3f[4];
        public final Vector2f[] uvs = new Vector2f[4];
    }

    /**
     * 从 RawYsmModel 构建主模型网格（移植 buildMesh：保留镜像体积 + 父索引回填）。
     * 第一人称手模通过 {@link #buildGeometry(RawYsmModel.RawGeometry)} 单独构建，
     * 因为 YSM 文件的 armModel 与 mainModel 使用不同的骨骼树。
     */
    public static YsmModelData build(RawYsmModel raw) {
        if (raw == null || raw.mainEntity == null) {
            return new YsmModelData();
        }
        YsmModelData model = new YsmModelData();
        RawYsmModel.RawGeometry rawGeo = raw.mainEntity.mainModel;
        if (rawGeo != null) {
            buildMesh(model, rawGeo);
        }
        // 纹理（简化 toPng：只支持 PNG/RGBA 原始数据，webp/avif 不支持）
        for (Map.Entry<String, RawYsmModel.RawTexture> entry : raw.mainEntity.textures.entrySet()) {
            RawYsmModel.RawTexture rt = entry.getValue();
            byte[] png = toPng(rt.data, rt.imageFormat, rt.width, rt.height);
            if (png != null) {
                model.textures.put(entry.getKey(), png);
            }
        }
        return model;
    }

    /** Build one geometry section without copying the texture payload. */
    public static YsmModelData buildGeometry(RawYsmModel.RawGeometry rawGeo) {
        YsmModelData model = new YsmModelData();
        if (rawGeo != null) {
            buildMesh(model, rawGeo);
        }
        return model;
    }

    private static void buildMesh(YsmModelData model, RawYsmModel.RawGeometry rawGeo) {
        List<String> parentNames = new ArrayList<>();
        for (RawYsmModel.RawBone rb : rawGeo.bones) {
            YsmBone bb = new YsmBone();
            bb.name = rb.name;
            bb.glow = rb.name != null && rb.name.startsWith("ysmGlow");
            bb.pivotX = rb.pivot[0];
            bb.pivotY = rb.pivot[1];
            bb.pivotZ = rb.pivot[2];
            bb.rotX = rb.rotation[0];
            bb.rotY = rb.rotation[1];
            bb.rotZ = rb.rotation[2];
            parentNames.add(rb.parentName);

            for (RawYsmModel.RawCube rc : rb.cubes) {
                // OpenYSM keeps mirrored/negative-volume cubes in the mesh and
                // handles their winding during rendering.  Dropping them here
                // makes otherwise valid model parts disappear.
                YsmCube bc = new YsmCube();
                for (RawYsmModel.RawFace rf : rc.faces) {
                    YsmQuad bq = new YsmQuad();
                    bq.normal.set(rf.normal[0], rf.normal[1], rf.normal[2]);
                    for (int i = 0; i < 4; i++) {
                        bq.positions[i] = new Vector3f(rf.positions[i][0], rf.positions[i][1], rf.positions[i][2]);
                        bq.uvs[i] = new Vector2f(rf.u[i], rf.v[i]);
                    }
                    bc.quads.add(bq);
                }
                bb.cubes.add(bc);
            }
            model.boneIndex.put(bb.name, model.bones.size());
            model.bones.add(bb);
        }

        // 回填父级索引
        for (int i = 0; i < model.bones.size(); i++) {
            YsmBone b = model.bones.get(i);
            String parentName = parentNames.get(i);
            if (parentName != null && !parentName.isEmpty()) {
                Integer pi = model.boneIndex.get(parentName);
                if (pi != null) {
                    b.parentIdx = pi;
                }
            }
        }
    }

    /**
     * 简化 toPng：imageFormat 2 → 原样返回；-1 → RGBA 原始数据转 PNG；
     * 0/1 → 按 PNG/JPEG 用 ImageIO 解码后重编码 PNG；3/4/5（webp/avif）不支持返回 null。
     */
    private static byte[] toPng(byte[] data, int imageFormat, int width, int height) {
        if (data == null || data.length == 0 || imageFormat == 2) {
            return data;
        }
        try {
            BufferedImage img;
            if (imageFormat == -1) {
                // 宽高来自 .ysm 文件：必须用 long 运算，否则 width*height*4 溢出会绕过校验，
                // 随后 new BufferedImage(65536,65536) 直接 OOM / new int[负数] 抛 NegativeArraySizeException。
                long pixelCount = (long) width * (long) height;
                if (width <= 0 || height <= 0 || width > 8192 || height > 8192
                        || pixelCount > 64L * 1024L * 1024L || data.length < pixelCount * 4L) {
                    return null;
                }
                img = new BufferedImage(width, height, BufferedImage.TYPE_INT_ARGB);
                int[] pixels = new int[(int) pixelCount];
                for (int i = 0; i < pixels.length; i++) {
                    int r = data[i * 4] & 0xFF;
                    int g = data[i * 4 + 1] & 0xFF;
                    int b = data[i * 4 + 2] & 0xFF;
                    int a = data[i * 4 + 3] & 0xFF;
                    pixels[i] = (a << 24) | (r << 16) | (g << 8) | b;
                }
                img.setRGB(0, 0, width, height, pixels, 0, width);
            } else {
                img = ImageIO.read(new ByteArrayInputStream(data));
            }
            if (img == null) {
                return null;
            }
            ByteArrayOutputStream baos = new ByteArrayOutputStream();
            ImageIO.write(img, "png", baos);
            return baos.toByteArray();
        } catch (Exception e) {
            return null;
        }
    }
}
