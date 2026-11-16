package dev.kizuna.agent.marks;

import net.fabricmc.fabric.api.creativetab.v1.FabricCreativeModeTab;
import net.fabricmc.fabric.api.event.player.UseBlockCallback;
import net.minecraft.core.Registry;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.item.CreativeModeTab;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;

import java.util.Optional;
import java.util.function.Function;

/** 本模组的物品和创造栏；只在模组初始化时注册一次。 */
public final class AgentItems {
    public static final String NAMESPACE = "kizuna_agent";
    public static final Identifier POINTER_ID = Identifier.fromNamespaceAndPath(NAMESPACE, "pointer");
    private static Item pointer;

    private AgentItems() {
        // 只保存注册结果。
    }

    /**
     * @param marks 一个玩家的标记记在哪儿；不是单人世界的主人时为空
     */
    public static void register(Function<ServerPlayer, Optional<Marks>> marks) {
        Item.Properties settings = new Item.Properties();
        settings.stacksTo(1);
        settings.setId(ResourceKey.create(Registries.ITEM, POINTER_ID));
        var item = new PointerItem(settings, marks);
        pointer = Registry.register(BuiltInRegistries.ITEM, POINTER_ID, item);
        CreativeModeTab.Builder group = FabricCreativeModeTab.builder();
        group.title(Component.translatable("itemGroup." + NAMESPACE));
        group.icon(() -> new ItemStack(pointer));
        group.displayItems((context, entries) -> entries.accept(pointer));
        Registry.register(BuiltInRegistries.CREATIVE_MODE_TAB, Identifier.fromNamespaceAndPath(NAMESPACE, "main"), group.build());
        // 抢在方块自己的交互（开箱子、开门）前面：拿着指点杖右键方块一律是标记，不触发方块。
        UseBlockCallback.EVENT.register((player, level, hand, hit) -> {
            if (player.isSpectator() || !player.getItemInHand(hand).is(pointer)) {
                return InteractionResult.PASS;
            }
            if (player instanceof ServerPlayer actor) {
                item.click(actor, hit);
                return InteractionResult.CONSUME;
            }
            return InteractionResult.SUCCESS;
        });
    }

    public static Item pointer() {
        return pointer;
    }
}
