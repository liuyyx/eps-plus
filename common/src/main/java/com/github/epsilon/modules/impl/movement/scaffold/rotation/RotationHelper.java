/*
 * This file is part of Epsilon.
 *
 * 逐行照抄自 OpenPal（https://github.com/OpenPal）MC 1.21.10 的
 * wtf.opal.client.feature.helper.impl.player.rotation.RotationHelper。
 */
package com.github.epsilon.modules.impl.movement.scaffold.rotation;

import com.github.epsilon.modules.impl.movement.scaffold.rotation.handler.ClientRotationHandler;
import com.github.epsilon.modules.impl.movement.scaffold.rotation.handler.RotationMouseHandler;

public final class RotationHelper {

    private RotationHelper() {
    }

    private static final ClientRotationHandler clientHandler = new ClientRotationHandler();
    private static final RotationMouseHandler mouseHandler = new RotationMouseHandler();

    public static RotationMouseHandler getHandler() {
        return mouseHandler;
    }

    public static ClientRotationHandler getClientHandler() {
        return clientHandler;
    }
}
