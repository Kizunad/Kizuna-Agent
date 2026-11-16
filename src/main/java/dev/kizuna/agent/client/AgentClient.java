package dev.kizuna.agent.client;

import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.mojang.brigadier.suggestion.Suggestions;
import com.mojang.brigadier.suggestion.SuggestionsBuilder;
import dev.kizuna.agent.AgentMod;
import dev.kizuna.agent.bridge.BridgeException;
import dev.kizuna.agent.bridge.BridgeHost;
import dev.kizuna.agent.client.capture.ScreenCapture;
import dev.kizuna.agent.config.AgentConfig;
import dev.kizuna.agent.config.ThinkingLevels;
import dev.kizuna.agent.marks.Marks;
import dev.kizuna.agent.notes.MemoryFile;
import dev.kizuna.agent.notes.SkillLibrary;
import dev.kizuna.agent.pi.AgentSession;
import dev.kizuna.agent.pi.Transcript;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.command.v2.ClientCommands;
import net.fabricmc.fabric.api.client.command.v2.ClientCommandRegistrationCallback;
import net.fabricmc.fabric.api.client.command.v2.FabricClientCommandSource;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents;
import net.fabricmc.fabric.api.client.rendering.v1.level.LevelRenderEvents;
import net.minecraft.ChatFormatting;
import net.minecraft.client.Minecraft;
import net.minecraft.commands.SharedSuggestionProvider;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;

import java.util.Deque;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentLinkedDeque;
import java.util.function.Consumer;
import java.util.function.Predicate;
import java.util.function.Supplier;
import java.util.regex.Pattern;

/**
 * 客户端入口：{@code /agent} 指令，和把助手的话显示到聊天栏。
 *
 * <p>{@code /agent <要它做什么>} 把话交给助手；{@code /agent stop|new|status|undo|model <名字>|thinking <档>|memory|skills}
 * 是几个管理指令。一句话恰好以这几个词开头也照样当成话交给助手（例如「/agent new house」「/agent model 这个词是什么意思」）。</p>
 *
 * <p>客户端才做得了的事也在这里挂上：截游戏画面的接口，和把指点杖标的点画在世界里。</p>
 */
public final class AgentClient implements ClientModInitializer {
    private static final String COMMAND = "agent";
    private static final String TEXT = "text";
    /** 模型 ID 的样子：一串不带空格的字母、数字和几种标点。清单里的别名不受这个限制。 */
    private static final Pattern MODEL_ID = Pattern.compile("[A-Za-z0-9._:/@()\\[\\]+-]+");
    /** 「/agent memory forget 3」后半句的样子。 */
    private static final Pattern FORGET = Pattern.compile("forget\\s+(\\d{1,3})");
    private static final String SCREENSHOT_DIRECTORY = "workspace/screenshots";
    private static final Component PREFIX = Component.literal("助手 › ").withStyle(ChatFormatting.DARK_AQUA);

    /** 最近显示过的行留这么多条，给以后的对话窗口和验收用。 */
    private static final int HISTORY = 400;

    private static final Deque<Transcript.Line> RECENT = new ConcurrentLinkedDeque<>();
    private static volatile AgentSession session;
    private static BridgeHost sessionHost;
    /** 上一次随玩家的话带给助手的是哪一版标记；标记没变就不重复带。 */
    private static int toldMarks = -1;

    @Override
    public void onInitializeClient() {
        ClientCommandRegistrationCallback.EVENT.register((dispatcher, registries) -> dispatcher.register(command()));
        ClientPlayConnectionEvents.DISCONNECT.register((handler, client) -> closeSession());
        BridgeHost.provide("screenshot", request -> ScreenCapture.call(request, AgentMod.dataDirectory().resolve(SCREENSHOT_DIRECTORY)));
        LevelRenderEvents.COLLECT_SUBMITS.register(MarkOutlines::submit);
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
                .then(management("new", AgentClient::startOver))
                .then(management("status", AgentClient::status))
                .then(management("undo", AgentClient::undo))
                .then(management("skills", AgentClient::skills))
                .then(memory())
                .then(choice("model", AgentSession::showModel, AgentClient::modelNames, AgentClient::modelName, AgentSession::model))
                .then(choice("thinking", AgentSession::showThinking, AgentClient::thinkingLevels, ThinkingLevels.ALL::contains,
                        AgentSession::thinking))
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

    /** {@code /agent memory} 列出记着的事；{@code /agent memory forget <第几条>} 删一条；别的后半句是一句以 memory 开头的话。 */
    private static LiteralArgumentBuilder<FabricClientCommandSource> memory() {
        return ClientCommands.literal("memory")
                .executes(context -> {
                    memories();
                    return 1;
                })
                .then(ClientCommands.argument(TEXT, StringArgumentType.greedyString())
                        .suggests((context, builder) -> suggest(List.of("forget "), builder))
                        .executes(context -> {
                            String rest = StringArgumentType.getString(context, TEXT).strip();
                            var forget = FORGET.matcher(rest);
                            if (!forget.matches()) {
                                return ask("memory " + rest);
                            }
                            forget(Integer.parseInt(forget.group(1)));
                            return 1;
                        }));
    }

    private interface Setter {
        void set(AgentSession session, String value);
    }

    /**
     * 「词 + 一个选项」的管理指令（换模型、调思考档位）：单独一个词说现状；后面跟的是一个选项就照办，
     * 不是选项就是一句以这个词开头的话。补全只列以已输入的字开头的选项。
     */
    private static LiteralArgumentBuilder<FabricClientCommandSource> choice(String name, Consumer<AgentSession> show,
                                                                           Supplier<List<String>> options,
                                                                           Predicate<String> isOption, Setter setter) {
        return ClientCommands.literal(name)
                .executes(context -> {
                    session().ifPresent(show);
                    return 1;
                })
                .then(ClientCommands.argument(TEXT, StringArgumentType.greedyString())
                        .suggests((context, builder) -> suggest(options.get(), builder))
                        .executes(context -> {
                            String value = StringArgumentType.getString(context, TEXT).strip();
                            if (!isOption.test(value)) {
                                return ask(name + " " + value);
                            }
                            session().ifPresent(current -> setter.set(current, value));
                            return 1;
                        }));
    }

    private static CompletableFuture<Suggestions> suggest(List<String> options, SuggestionsBuilder builder) {
        return SharedSuggestionProvider.suggest(options, builder);
    }

    /** 能换的模型：配置里清单的别名，加上 {@code [model]} 里直接写了模型 ID 的主、备模型。 */
    private static List<String> modelNames() {
        return AgentMod.config().map(AgentConfig::names).orElse(List.of());
    }

    private static boolean modelName(String text) {
        return modelNames().contains(text) || MODEL_ID.matcher(text).matches();
    }

    /** 现在这个模型能用的思考档位；会话还没建时按进世界用的那个模型算。 */
    private static List<String> thinkingLevels() {
        AgentSession current = session;
        if (current != null) {
            return current.levels();
        }
        return AgentMod.config().map(config -> List.copyOf(config.primary().levels().keySet())).orElse(List.of());
    }

    private static int usage() {
        show(Transcript.Kind.NOTE, "用法：/agent <要它做什么>。管理：/agent stop 停下、new 开新对话、status 看状态、undo 撤回上一次放的方块、"
                + "model <名字> 换模型、thinking <档> 调思考档位、memory 看它记着的事（memory forget <第几条> 删一条）、skills 看它的技能");
        return 1;
    }

    private static int ask(String text) {
        session().ifPresent(current -> {
            Minecraft.getInstance().player.sendSystemMessage(Component.literal("你 › ").withStyle(ChatFormatting.GRAY)
                    .append(Component.literal(text).withStyle(ChatFormatting.WHITE)));
            current.ask(text + marksNote());
        });
        return 1;
    }

    /** 玩家用指点杖标了点、而且上次说话以后有变化：把清单附在这句话后面。 */
    private static String marksNote() {
        Marks marks = sessionHost.marks();
        int version = marks.version();
        if (version == toldMarks) {
            return "";
        }
        toldMarks = version;
        return Marks.note(marks.list());
    }

    private static void startOver() {
        // 新对话里助手不记得前面附过的标记，下一句话重新带上。
        toldMarks = -1;
        session().ifPresent(AgentSession::reset);
    }

    private static void memories() {
        Optional<BridgeHost> host = AgentMod.host();
        if (host.isEmpty()) {
            show(Transcript.Kind.ERROR, unavailable());
            return;
        }
        MemoryFile memory = host.get().memory();
        List<String> entries = memory.list();
        if (entries.isEmpty()) {
            show(Transcript.Kind.NOTE, "助手还没有记忆。对它说「记住…」它就会记下，以后每段对话都带着。文件在 " + memory.file());
            return;
        }
        show(Transcript.Kind.NOTE, "助手记着 " + entries.size() + " 条（上限 " + MemoryFile.MAX_ENTRIES + "）；/agent memory forget <第几条> 删一条：");
        for (int index = 0; index < entries.size(); index++) {
            show(Transcript.Kind.TEXT, (index + 1) + ". " + entries.get(index));
        }
        if (entries.size() >= MemoryFile.NEARLY_FULL) {
            show(Transcript.Kind.NOTE, "快满了：删掉过时的，或者让助手「整理一下记忆」");
        }
    }

    private static void forget(int number) {
        Optional<BridgeHost> host = AgentMod.host();
        if (host.isEmpty()) {
            show(Transcript.Kind.ERROR, unavailable());
            return;
        }
        try {
            show(Transcript.Kind.NOTE, "已删掉第 " + number + " 条：" + host.get().memory().forget(number));
        } catch (IllegalArgumentException missing) {
            show(Transcript.Kind.ERROR, missing.getMessage());
        }
    }

    private static void skills() {
        Optional<BridgeHost> host = AgentMod.host();
        if (host.isEmpty()) {
            show(Transcript.Kind.ERROR, unavailable());
            return;
        }
        SkillLibrary library = host.get().skills();
        List<SkillLibrary.Skill> skills = library.list();
        if (skills.isEmpty()) {
            show(Transcript.Kind.NOTE, "助手还没有技能。一件事做成以后对它说「存成技能」，以后它就能照着再做。目录在 " + library.root());
            return;
        }
        show(Transcript.Kind.NOTE, "助手有 " + skills.size() + " 个技能（目录 " + library.root() + "）：");
        skills.forEach(skill -> show(Transcript.Kind.TEXT, skill.name() + "：" + skill.description()));
    }

    private static void status() {
        // 没配好时 session() 自己会说缺什么；配好了再列各项状态。
        session().ifPresent(current -> {
            show(Transcript.Kind.NOTE, "记忆 " + sessionHost.memory().list().size() + " 条、技能 " + sessionHost.skills().list().size()
                    + " 个、指点杖标了 " + sessionHost.marks().list().size() + " 个点（/agent memory、/agent skills 看）");
            current.status();
        });
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
        if (!config.get().ready()) {
            show(Transcript.Kind.ERROR, "还没配置模型。请在 " + AgentMod.configFile()
                    + " 的 [model] 里填 base_url、api_key、model，然后重新进一次世界");
            return Optional.empty();
        }
        if (session == null || sessionHost != host.get()) {
            closeSession();
            sessionHost = host.get();
            toldMarks = -1;
            session = new AgentSession(AgentMod.dataDirectory(), AgentMod.configFile(), config.get(),
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
