package dev.kizuna.agent.bridge;

import net.minecraft.core.BlockPos;

import java.util.Arrays;

/** 两个角定出的长方体，两个角都包含在内；给的角不分先后。 */
record Box(BlockPos min, BlockPos max) {
    static Box of(BlockPos first, BlockPos second) {
        return new Box(new BlockPos(Math.min(first.getX(), second.getX()), Math.min(first.getY(), second.getY()),
                Math.min(first.getZ(), second.getZ())),
                new BlockPos(Math.max(first.getX(), second.getX()), Math.max(first.getY(), second.getY()),
                        Math.max(first.getZ(), second.getZ())));
    }

    BlockPos size() {
        return new BlockPos(max.getX() - min.getX() + 1, max.getY() - min.getY() + 1, max.getZ() - min.getZ() + 1);
    }

    long volume() {
        BlockPos size = size();
        return (long) size.getX() * size.getY() * size.getZ();
    }

    /** 全部位置，顺序先 y、再 z、再 x，和读区域的下标顺序一致。调用方先查过体积上限。 */
    long[] positions() {
        long[] positions = new long[(int) volume()];
        int index = 0;
        for (int y = min.getY(); y <= max.getY(); y++) {
            for (int z = min.getZ(); z <= max.getZ(); z++) {
                for (int x = min.getX(); x <= max.getX(); x++) {
                    positions[index++] = BlockPos.asLong(x, y, z);
                }
            }
        }
        return positions;
    }

    /** 只有六个面上的位置（空心壳）。 */
    long[] shell() {
        long[] all = positions();
        long[] shell = new long[all.length];
        int count = 0;
        for (long position : all) {
            int x = BlockPos.getX(position);
            int y = BlockPos.getY(position);
            int z = BlockPos.getZ(position);
            if (x == min.getX() || x == max.getX() || y == min.getY() || y == max.getY()
                    || z == min.getZ() || z == max.getZ()) {
                shell[count++] = position;
            }
        }
        return Arrays.copyOf(shell, count);
    }
}
