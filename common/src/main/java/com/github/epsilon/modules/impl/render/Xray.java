package com.github.epsilon.modules.impl.render;

import com.github.epsilon.modules.Category;
import com.github.epsilon.modules.Module;

public class Xray extends Module {

    public static final Xray INSTANCE = new Xray();

    private Xray() {
        super("Xray", Category.RENDER);
    }

}
