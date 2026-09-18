package com.github.epsilon.utils.player;

import net.minecraft.core.BlockPos;
import net.minecraft.core.component.DataComponents;
import net.minecraft.util.Mth;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.item.component.SwingAnimation;
import net.minecraft.world.level.block.WebBlock;
import net.minecraft.world.phys.AABB;

import static com.github.epsilon.Constants.mc;

public class PlayerUtils {

    /**
     * 播放本地玩家的挥手动画。
     *
     * <p>26.3 移除了 {@code ServerboundSwingPacket}：服务端的挥手动画改由攻击、破坏方块等行为广播，
     * 客户端不再有独立的挥手网络包。因此所有旧代码里“只发包不播动画”的模式都无法保留，
     * 统一改为播放本地动画，保证各模块的视觉表现与旧版本一致。
     *
     * @param hand 挥手使用的手
     */
    public static void swingHand(InteractionHand hand) {
        if (mc.player == null) {
            return;
        }
        mc.player.swing(hand, SwingAnimation.DEFAULT, false);
    }

    /**
     * 判断本地玩家是否正在使用食物。
     *
     * @return 判断结果
     */
    public static boolean isEating() {
        return (mc.player.getMainHandItem().getComponents().has(DataComponents.FOOD) || mc.player.getOffhandItem().getComponents().has(DataComponents.FOOD)) && mc.player.isUsingItem();
    }

    /**
     * 判断本地玩家的包围盒是否与蜘蛛网相交。
     *
     * @return 判断结果
     */
    public static boolean isInWeb() {
        AABB box = mc.player.getBoundingBox().deflate(1.0E-6);

        int minX = Mth.floor(box.minX);
        int minY = Mth.floor(box.minY);
        int minZ = Mth.floor(box.minZ);
        int maxX = Mth.floor(box.maxX);
        int maxY = Mth.floor(box.maxY);
        int maxZ = Mth.floor(box.maxZ);

        BlockPos.MutableBlockPos mutablePos = new BlockPos.MutableBlockPos();
        for (int x = minX; x <= maxX; x++) {
            for (int y = minY; y <= maxY; y++) {
                for (int z = minZ; z <= maxZ; z++) {
                    mutablePos.set(x, y, z);
                    if (mc.level.getBlockState(mutablePos).getBlock() instanceof WebBlock) {
                        return true;
                    }
                }
            }
        }

        return false;
    }

    /**
     * 判断本地玩家的包围盒是否与实体方块相交。
     *
     * @return 判断结果
     */
    public static boolean isInBlock() {
        AABB box = mc.player.getBoundingBox().deflate(1.0E-6);

        int minX = Mth.floor(box.minX);
        int minY = Mth.floor(box.minY);
        int minZ = Mth.floor(box.minZ);
        int maxX = Mth.floor(box.maxX);
        int maxY = Mth.floor(box.maxY);
        int maxZ = Mth.floor(box.maxZ);

        BlockPos.MutableBlockPos mutablePos = new BlockPos.MutableBlockPos();
        for (int x = minX; x <= maxX; x++) {
            for (int y = minY; y <= maxY; y++) {
                for (int z = minZ; z <= maxZ; z++) {
                    mutablePos.set(x, y, z);
                    if (mc.level.getBlockState(mutablePos).isSolidRender()) {
                        return true;
                    }
                }
            }
        }

        return false;
    }

}
