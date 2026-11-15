package dev.kizuna.agent.bridge;

import com.google.gson.JsonArray;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import net.minecraft.commands.arguments.blocks.BlockStateParser;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 方块的文字写法和调色板。
 *
 * <p>写法和 {@code /setblock} 一样：{@code minecraft:oak_stairs[facing=north,half=top]}，
 * 带内容的方块可以跟一段 NBT：{@code minecraft:chest[facing=east]{Items:[...]}}。
 * 没写的属性取默认值；读出来的写法总是把属性写全。</p>
 */
final class BlockPalette {
    /** 调色板的一项：方块状态，和可选的方块实体数据。 */
    record Entry(BlockState state, CompoundTag nbt) {
    }

    private BlockPalette() {
        // 只有静态转换方法。
    }

    static Entry parse(HolderLookup<Block> blocks, String text) {
        try {
            var parsed = BlockStateParser.parseForBlock(blocks, text, true);
            return new Entry(parsed.blockState(), parsed.nbt());
        } catch (CommandSyntaxException invalid) {
            throw BridgeException.badRequest("方块写法不对「" + text + "」：" + invalid.getMessage());
        }
    }

    static List<Entry> parse(HolderLookup<Block> blocks, JsonArray palette) {
        var entries = new ArrayList<Entry>(palette.size());
        for (var element : palette) {
            if (!element.isJsonPrimitive() || !element.getAsJsonPrimitive().isString()) {
                throw BridgeException.badRequest("palette 里每一项都要是方块写法的字符串");
            }
            entries.add(parse(blocks, element.getAsString()));
        }
        return entries;
    }

    static String write(BlockState state) {
        return BlockStateParser.serialize(state);
    }

    /** 读区域时边读边攒的调色板：同一种状态只出现一次，下标按第一次遇到的先后。 */
    static final class Builder {
        private final Map<BlockState, Integer> indices = new HashMap<>();
        private final JsonArray names = new JsonArray();

        int index(BlockState state) {
            Integer known = indices.get(state);
            if (known != null) {
                return known;
            }
            int index = names.size();
            indices.put(state, index);
            names.add(write(state));
            return index;
        }

        JsonArray names() {
            return names;
        }
    }
}
