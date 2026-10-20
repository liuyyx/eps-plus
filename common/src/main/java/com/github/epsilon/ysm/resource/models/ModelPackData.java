package com.github.epsilon.ysm.resource.models;


import java.util.Map;

/**
 * 读取ysm_pack.json内容
 * https://ysm.cfpa.team/wiki/model-pack/#%E5%88%B6%E4%BD%9C%E6%A8%A1%E5%9E%8B%E5%8C%85
 * （OpenZen 裁剪：OuterFileTexture（外部纹理 GUI 预览）已移除）
 */
public class ModelPackData {

    private final String path;

    private final String name;

    private final String description;

    private final Map<String, Map<String, String>> translations;

    public ModelPackData(String path, String name, String description, Map<String, Map<String, String>> translations) {
        this.path = path;
        this.name = name;
        this.description = description;
        this.translations = translations;
    }

    public String getPath() {
        return this.path;
    }

    public String getName() {
        return this.name;
    }

    public String getDescription() {
        return this.description;
    }

    public Map<String, Map<String, String>> getTranslations() {
        return this.translations;
    }
}
