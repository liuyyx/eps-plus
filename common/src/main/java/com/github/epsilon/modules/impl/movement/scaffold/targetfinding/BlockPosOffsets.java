package com.github.epsilon.modules.impl.movement.scaffold.targetfinding;

import com.github.epsilon.modules.impl.movement.scaffold.util.VectorUtils;
import it.unimi.dsi.fastutil.longs.LongOpenHashSet;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Vec3i;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * 逐行照搬自 LiquidBounce {@code utils/block/targetfinding/BlockPosOffsets.kt}。
 */
public enum BlockPosOffsets {

    NO_OFFSET(List.<Vec3i>of(BlockPos.ZERO)),
    NORMAL(Offsets.generateScaffoldOffsets(0, -1, 1)),
    DOWN(Offsets.generateScaffoldOffsets(0, -1, 1, -2, 2)),
    FULL(Offsets.generateScaffoldOffsets(0, -1, 1, -2, 2, -3, 3, -4, 4)),
    ;

    // LB 为 List<BlockPos>，Kotlin 的 List 是协变类型；Java 用 List<Vec3i> 表达同样的可赋值性。
    private final List<Vec3i> offsets;

    BlockPosOffsets(List<Vec3i> offsets) {
        this.offsets = offsets;
    }

    public List<Vec3i> getOffsets() {
        return offsets;
    }

    public boolean containsOffset(int x, int y, int z) {
        return offsets.contains(new BlockPos(x, y, z));
    }

    /**
     * 顶层 COMPARATOR / generateScaffoldOffsets 需要在枚举常量初始化之前可用，
     * 故放入惰性初始化的静态嵌套类（Kotlin 顶层 private val 的等价物）。
     */
    private static final class Offsets {

        private static final Comparator<Vec3i> COMPARATOR =
                Comparator.comparingLong(VectorUtils::lengthSqr)
                        .thenComparingInt(Vec3i::getY)
                        .thenComparingInt(Vec3i::getX)
                        .thenComparingInt(Vec3i::getZ);

        private static List<Vec3i> generateScaffoldOffsets(int... xzValues) {
            LongOpenHashSet longs = new LongOpenHashSet(xzValues.length * xzValues.length * 2);
            for (int x : xzValues) {
                for (int z : xzValues) {
                    longs.add(BlockPos.asLong(x, 0, z));
                    longs.add(BlockPos.asLong(x, -1, z));
                }
            }

            // LB: longs.mapToArray(BlockPos::of)
            long[] raw = longs.toLongArray();
            List<Vec3i> result = new ArrayList<>(raw.length);
            for (long value : raw) {
                result.add(BlockPos.of(value));
            }
            result.sort(COMPARATOR);

            return List.copyOf(result);
        }
    }
}