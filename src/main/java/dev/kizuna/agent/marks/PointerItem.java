package dev.kizuna.agent.marks;

import dev.kizuna.agent.bridge.BlockPalette;
import net.minecraft.ChatFormatting;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.TooltipFlag;
import net.minecraft.world.item.component.TooltipDisplay;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;

import java.util.List;
import java.util.Optional;
import java.util.function.Consumer;
import java.util.function.Function;

/**
 * 指点杖：拿着右键，把一个位置标下来发给助手。
 *
 * <p>右键够得着的方块标那个方块（由 {@link AgentItems} 挂的回调抢在方块自己的交互前面处理，所以箱子、门也标得上）；
 * 够不着时标准星指着的远处方块，什么都没指着就标玩家脚下；蹲下右键清空。只在单人世界里有用。</p>
 */
public final class PointerItem extends Item {
    /** 远处最远能指多少格。 */
    public static final double REACH = 128;
    private static final String KEY = "kizuna_agent.pointer.";
    static final List<String> TOOLTIP_KEYS = List.of("item.kizuna_agent.pointer.tooltip.mark",
            "item.kizuna_agent.pointer.tooltip.far", "item.kizuna_agent.pointer.tooltip.clear",
            "item.kizuna_agent.pointer.tooltip.use");

    private final Function<ServerPlayer, Optional<Marks>> marks;

    /**
     * @param marks 这个玩家的标记记在哪儿；不是单人世界的主人时为空
     */
    PointerItem(Properties settings, Function<ServerPlayer, Optional<Marks>> marks) {
        super(settings);
        this.marks = marks;
    }

    @Override
    public void appendHoverText(ItemStack stack, Item.TooltipContext context, TooltipDisplay display,
                                Consumer<Component> tooltip, TooltipFlag flag) {
        for (String key : TOOLTIP_KEYS) {
            tooltip.accept(Component.translatable(key).withStyle(ChatFormatting.GRAY));
        }
    }

    @Override
    public InteractionResult use(Level level, Player player, InteractionHand hand) {
        // 右键时够不着任何方块才走到这里：指远处，或者标脚下。
        if (!(player instanceof ServerPlayer actor)) {
            return InteractionResult.SUCCESS;
        }
        if (actor.isShiftKeyDown()) {
            clear(actor);
        } else if (actor.pick(REACH, 1.0F, false) instanceof BlockHitResult hit && hit.getType() == HitResult.Type.BLOCK) {
            mark(actor, hit.getBlockPos(), hit.getDirection(), Marks.Kind.DISTANT);
        } else {
            mark(actor, actor.blockPosition(), null, Marks.Kind.FEET);
        }
        return InteractionResult.CONSUME;
    }

    /** 右键了一个够得着的方块。服务端上调。 */
    void click(ServerPlayer actor, BlockHitResult hit) {
        if (actor.isShiftKeyDown()) {
            clear(actor);
        } else {
            mark(actor, hit.getBlockPos(), hit.getDirection(), Marks.Kind.BLOCK);
        }
    }

    private void mark(ServerPlayer actor, BlockPos position, Direction face, Marks.Kind kind) {
        Optional<Marks> store = marks.apply(actor);
        say(actor, store.isEmpty() ? Component.translatable(KEY + "unavailable") : mark(store.get(), actor, position, face, kind));
    }

    private void clear(ServerPlayer actor) {
        Optional<Marks> store = marks.apply(actor);
        say(actor, store.isEmpty() ? Component.translatable(KEY + "unavailable") : clear(store.get()));
    }

    /**
     * 把一个位置记进 {@code store}，返回要回给玩家的那句话：记下了第几个、这一格已经标过，或者标满了。
     *
     * @param face 点的是哪个面；标脚下时为 null
     */
    public static Component mark(Marks store, ServerPlayer actor, BlockPos position, Direction face, Marks.Kind kind) {
        ServerLevel level = actor.level();
        BlockState state = level.getBlockState(position);
        var mark = new Marks.Mark(position.getX(), position.getY(), position.getZ(), level.dimension().identifier().toString(),
                BlockPalette.write(state), face == null ? "" : face.getName(), kind);
        int known = store.numberOf(mark);
        if (known > 0) {
            return Component.translatable(KEY + "already", known);
        }
        if (store.list().size() >= Marks.MAX) {
            return Component.translatable(KEY + "full", Marks.MAX);
        }
        int number = store.add(mark);
        return switch (kind) {
            case BLOCK -> Component.translatable(KEY + "marked", number, position.getX(), position.getY(), position.getZ(),
                    state.getBlock().getName());
            case DISTANT -> Component.translatable(KEY + "marked_distant", number, position.getX(), position.getY(), position.getZ(),
                    state.getBlock().getName(), (int) Math.round(Math.sqrt(actor.blockPosition().distSqr(position))));
            case FEET -> Component.translatable(KEY + "marked_feet", number, position.getX(), position.getY(), position.getZ());
        };
    }

    /** 清空 {@code store}，返回要回给玩家的那句话。 */
    public static Component clear(Marks store) {
        int removed = store.clear();
        return removed == 0 ? Component.translatable(KEY + "nothing") : Component.translatable(KEY + "cleared", removed);
    }

    private static void say(ServerPlayer actor, Component message) {
        actor.sendSystemMessage(Component.translatable(KEY + "prefix").withStyle(ChatFormatting.DARK_AQUA)
                .append(message.copy().withStyle(ChatFormatting.GRAY)));
    }
}
