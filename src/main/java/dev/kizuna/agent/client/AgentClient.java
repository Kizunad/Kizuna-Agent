package dev.kizuna.agent.client;

import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.mojang.brigadier.suggestion.Suggestions;
import com.mojang.brigadier.suggestion.SuggestionsBuilder;
import dev.kizuna.agent.AgentMod;
import dev.kizuna.agent.bridge.BridgeException;
import dev.kizuna.agent.bridge.BridgeHost;
import dev.kizuna.agent.config.AgentConfig;
import dev.kizuna.agent.pi.AgentSession;
import dev.kizuna.agent.pi.Transcript;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.command.v2.ClientCommands;
import net.fabricmc.fabric.api.client.command.v2.ClientCommandRegistrationCallback;
import net.fabricmc.fabric.api.client.command.v2.FabricClientCommandSource;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents;
import net.minecraft.ChatFormatting;
import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;

import java.util.Deque;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentLinkedDeque;

/**
 * 客户端入口：{@code /agent} 指令，和把助手的话显示到聊天栏。
 *
 * <p>{@code /agent <要它做什么>} 把话交给助手；{@code /agent stop|new|status|undo|model <名字>} 是几个管理指令。
 * 一句话恰好以这几个词开头也照样当成话交给助手（例如「/agent new house」）。</p>
 */
public final class AgentClient implements ClientModInitializer {
    private static final String COMMAND = "agent";
    private static final String TEXT = "text";
    private static final Component PREFIX = Component.literal("助手 › ").withStyle(ChatFormatting.DARK_AQUA);

    /** 最近显示过的行留这么多条，给以后的对话窗口和验收用。 */
    private static final int HISTORY = 400;

    private static final Deque<Transcript.Line> RECENT = new ConcurrentLinkedDeque<>();
    private static volatile AgentSession session;
    private static BridgeHost sessionHost;

    @Override
    public void onInitializeClient() {
        ClientCommandRegistrationCallback.EVENT.register((dispatcher, registries) -> dispatcher.register(command()));
        ClientPlayConnectionEvents.DISCONNECT.register((handler, client) -> closeSession());
    }

    /** 最近显示到聊天栏的各行，旧的在前。 */
    public static List<Transcript.Line> history() {
        return List.copyOf(RECENT);
    }

    /** 助手正在干活。 */
    public static boolean busy() {
        AgentSession current = session;
        return current != null && current.busy();
    }

    private static LiteralArgumentBuilder<FabricClientCommandSource> command() {
        return ClientCommands.literal(COMMAND)
                .executes(context -> usage())
                .then(management("stop", () -> session().ifPresent(AgentSession::stop)))
                .then(management("new", () -> session().ifPresent(AgentSession::reset)))
                .then(management("status", AgentClient::status))
                .then(management("undo", AgentClient::undo))
                .then(ClientCommands.literal("model")
                        .executes(context -> usage())
                        .then(ClientCommands.argument("name", StringArgumentType.greedyString())
                                .suggests((context, builder) -> models(builder))
                                .executes(context -> {
                                    String name = StringArgumentType.getString(context, "name").strip();
                                    session().ifPresent(current -> current.model(name));
                                    return 1;
                                })))
                .then(ClientCommands.argument(TEXT, StringArgumentType.greedyString())
                        .executes(context -> ask(StringArgumentType.getString(context, TEXT))));
    }

    private static LiteralArgumentBuilder<FabricClientCommandSource> management(String name, Runnable action) {
        // 单独一个词是管理指令；后面还跟着字就是一句以这个词开头的话。
        return ClientCommands.literal(name)
                .executes(context -> {
                    action.run();
                    return 1;
                })
                .then(ClientCommands.argument(TEXT, StringArgumentType.greedyString())
                        .executes(context -> ask(name + " " + StringArgumentType.getString(context, TEXT))));
    }

    private static CompletableFuture<Suggestions> models(SuggestionsBuilder builder) {
        AgentMod.config().map(AgentConfig::model).ifPresent(model -> {
            if (!model.model().isBlank()) {
                builder.suggest(model.model());
            }
            if (!model.fallbackModel().isBlank()) {
                builder.suggest(model.fallbackModel());
            }
        });
        return builder.buildFuture();
    }

    private static int usage() {
        show(Transcript.Kind.NOTE, "用法：/agent <要它做什么>。管理：/agent stop 停下、new 开新对话、status 看状态、undo 撤回上一次放的方块、model <名字> 换模型");
        return 1;
    }

    private static int ask(String text) {
        session().ifPresent(current -> {
            Minecraft.getInstance().player.sendSystemMessage(Component.literal("你 › ").withStyle(ChatFormatting.GRAY)
                    .append(Component.literal(text).withStyle(ChatFormatting.WHITE)));
            current.ask(text);
        });
        return 1;
    }

    private static void status() {
        // 没配好时 session() 自己会说缺什么；配好了再列各项状态。
        session().ifPresent(AgentSession::status);
    }

    private static void undo() {
        Optional<BridgeHost> host = AgentMod.host();
        if (host.isEmpty()) {
            show(Transcript.Kind.ERROR, unavailable());
            return;
        }
        // 撤回要等服务端主线程把方块放回去，换一条线程等。
        Thread.ofVirtual().name("kizuna-agent-undo").start(() -> {
            try {
                int restored = host.get().undo();
                show(Transcript.Kind.NOTE, "已撤回，还原了 " + restored + " 个方块");
            } catch (BridgeException rejected) {
                show(Transcript.Kind.ERROR, rejected.getMessage());
            }
        });
    }

    /** 当前世界的会话；现在用不了时说明原因并返回空。 */
    private static Optional<AgentSession> session() {
        Optional<BridgeHost> host = AgentMod.host();
        Optional<AgentConfig> config = AgentMod.config();
        if (host.isEmpty() || config.isEmpty()) {
            show(Transcript.Kind.ERROR, unavailable());
            return Optional.empty();
        }
        if (!config.get().model().ready()) {
            show(Transcript.Kind.ERROR, "还没配置模型。请在 " + AgentMod.configFile()
                    + " 的 [model] 里填 base_url、api_key、model，然后重新进一次世界");
            return Optional.empty();
        }
        if (session == null || sessionHost != host.get()) {
            closeSession();
            sessionHost = host.get();
            session = new AgentSession(AgentMod.dataDirectory(), config.get(),
                    new AgentSession.Connection(sessionHost.url(), sessionHost.token()),
                    line -> show(line.kind(), line.text()));
        }
        return Optional.of(session);
    }

    private static String unavailable() {
        return AgentMod.problem().orElse("助手只在单人世界里可用");
    }

    private static void closeSession() {
        if (session != null) {
            session.close();
            session = null;
            sessionHost = null;
        }
    }

    private static void show(Transcript.Kind kind, String text) {
        // 可能从助手的线程上来，换到游戏线程再往聊天栏写；玩家已经退出世界就不写了。
        RECENT.addLast(new Transcript.Line(kind, text));
        while (RECENT.size() > HISTORY) {
            RECENT.pollFirst();
        }
        Minecraft minecraft = Minecraft.getInstance();
        minecraft.execute(() -> {
            if (minecraft.player != null) {
                minecraft.player.sendSystemMessage(PREFIX.copy().append(styled(kind, text)));
            }
        });
    }

    private static MutableComponent styled(Transcript.Kind kind, String text) {
        return switch (kind) {
            case TEXT -> Component.literal(text).withStyle(ChatFormatting.WHITE);
            case TOOL -> Component.literal(text).withStyle(ChatFormatting.GRAY, ChatFormatting.ITALIC);
            case NOTE -> Component.literal(text).withStyle(ChatFormatting.GRAY);
            case ERROR -> Component.literal(text).withStyle(ChatFormatting.RED);
        };
    }
}
