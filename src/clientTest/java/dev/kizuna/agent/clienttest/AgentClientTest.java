package dev.kizuna.agent.clienttest;

import com.mojang.brigadier.suggestion.Suggestion;
import dev.kizuna.agent.client.AgentClient;
import dev.kizuna.agent.config.AgentConfig;
import dev.kizuna.agent.pi.Transcript;
import net.fabricmc.fabric.api.client.command.v2.ClientCommands;
import net.fabricmc.fabric.api.client.command.v2.FabricClientCommandSource;
import net.fabricmc.fabric.api.client.gametest.v1.FabricClientGameTest;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestSingleplayerContext;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.gui.screens.ChatScreen;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.Blocks;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * 助手的真客户端验收：真的游戏窗口、真的 {@code /agent} 指令、真的 pi 进程和桥接。
 *
 * <p>两种场景（{@code -PagentScene=}）：</p>
 * <ul>
 *   <li>{@code fake}（默认）：模型换成照剧本答话的假接口，主模型回 429 逼出备用模型；逐项核对世界里的方块、聊天栏里的话、撤回，
 *   再核对指令补全、换模型、调思考档位以后假接口收到的模型名和 {@code reasoning_effort}。不联网、结果确定。</li>
 *   <li>{@code live}：接环境变量 {@code CLIPROXY_BASE_URL}、{@code CLIPROXY_API_KEY} 给的真模型接口，让它盖一座小建筑、
 *   查一个模组 JAR 的语言文件，隔一会儿拍一张，给人看。</li>
 * </ul>
 */
public final class AgentClientTest implements FabricClientGameTest {
    private static final String SCENE = System.getProperty("kizuna.agent.clientTest.scene", "fake");
    private static final String REPORT = System.getProperty("kizuna.agent.clientTest.report");
    private static final String LIVE_MODEL = System.getProperty("kizuna.agent.clientTest.model", "gemini-3.8-flash-high");
    private static final String LIVE_FALLBACK = System.getProperty("kizuna.agent.clientTest.fallback", "gpt-6.1-terra");
    private static final String LIVE_BUILD = System.getProperty("kizuna.agent.clientTest.build",
            "在我面前盖一座小石砖瞭望塔：底面 5×5，三层高，有门、有窗、有尖屋顶，里面放火把照明");
    private static final String LIVE_LOOKUP = System.getProperty("kizuna.agent.clientTest.lookup",
            "查一下 kizuna_players_animation 这个模组的中文语言文件里有哪几条，各是什么意思");
    /**
     * 假模型场景的配置：主模型用别名、备用模型直接写模型 ID（不在清单里）；清单里另有一个自己写档位表的、一个思考由模型名决定的。
     * 依次填：接口地址、密钥、备用模型 ID、主模型 ID、deep 的模型 ID、fixed 的模型 ID。
     */
    private static final String FAKE_CONFIG = """
            [model]
            base_url = "%s"
            api_key = "%s"
            model = "main"
            fallback_model = "%s"
            thinking = "low"

            [[models]]
            alias = "main"
            id = "%s"

            [[models]]
            alias = "deep"
            id = "%s"
            thinking_levels = { off = "none", low = "low", high = "high" }

            [[models]]
            alias = "fixed"
            id = "%s"
            thinking_levels = {}
            """;
    private static final int WINDOW_WIDTH = 1280;
    private static final int WINDOW_HEIGHT = 720;
    /** 假模型场景一步最多等这么多 tick（每 tick 另睡 20 毫秒，约合一分半）。 */
    private static final int FAKE_WAIT_TICKS = 2400;
    /** 真模型场景一句话最多等这么多 tick（约合八分钟）。 */
    private static final int LIVE_WAIT_TICKS = 9600;
    private static final int TICK_SLEEP_MILLIS = 20;
    /** 真模型场景每隔这么多 tick 拍一张。 */
    private static final int FILM_EVERY_TICKS = 25;
    /** 玩家站的地方：平地上一块空场。 */
    private static final int STAND_X = 8;
    private static final int STAND_Z = 8;
    /** 盖完以后从这几个位置（相对玩家脚下）看建筑：建筑大约在玩家东边这么远、这么高处。 */
    private static final int OVERVIEW_AHEAD = 6;
    private static final int OVERVIEW_TARGET_HEIGHT = 7;
    private static final int[][] OVERVIEW_CAMERAS = {{-8, 9, -12}, {20, 12, -10}, {18, 16, 14}, {6, 3, 12}};
    private static final int OVERVIEW_SHOTS = 4;

    private final List<String> failures = new ArrayList<>();
    private int frame;

    @Override
    public void runTest(ClientGameTestContext context) {
        context.getInput().resizeWindow(WINDOW_WIDTH, WINDOW_HEIGHT);
        try {
            if (SCENE.equals("live")) {
                live(context);
            } else {
                fake(context);
            }
        } catch (IOException failed) {
            throw new UncheckedIOException(failed);
        } catch (RuntimeException | AssertionError failed) {
            failures.add("用例中途出错：" + failed);
            report();
            throw failed;
        }
        report();
        if (!failures.isEmpty()) {
            throw new AssertionError(String.join("\n", failures));
        }
    }

    private void fake(ClientGameTestContext context) throws IOException {
        try (var model = new MockModel()) {
            Files.createDirectories(configFile().getParent());
            Files.writeString(configFile(), FAKE_CONFIG.formatted(model.baseUrl(), MockModel.KEY, MockModel.BACKUP,
                    MockModel.PRIMARY, MockModel.DEEP, MockModel.FIXED));
            try (TestSingleplayerContext world = context.worldBuilder().create()) {
                BlockPos feet = settle(context, world);
                context.takeScreenshot("fake_0_before");

                command(context, "agent status");
                waitUntil(context, "status 列出各项状态", FAKE_WAIT_TICKS, () -> said(Transcript.Kind.NOTE, "游戏内接口："));
                expect(said(Transcript.Kind.NOTE, "Node.js"), "status 应报 Node.js 的版本和位置");

                command(context, "agent 在我东边盖个石砖小屋");
                waitUntil(context, "助手答完", FAKE_WAIT_TICKS, () -> said(Transcript.Kind.TEXT, MockModel.DONE) && !AgentClient.busy());
                context.waitTicks(10);
                context.takeScreenshot("fake_1_built");
                expect(said(Transcript.Kind.NOTE, "已换备用模型 " + MockModel.BACKUP), "主模型 429 时应换备用模型并说一声：" + transcript());
                expect(model.requests.getFirst().equals(MockModel.PRIMARY) && model.requests.getLast().equals(MockModel.BACKUP)
                        && model.requests.stream().filter(MockModel.PRIMARY::equals).count() == 1,
                        "应当只问主模型一次、之后都问备用模型：" + model.requests);
                expect(said(Transcript.Kind.TOOL, "填充：minecraft:stone_bricks") && said(Transcript.Kind.TOOL, "搜 JAR：「Kizuna Agent」")
                        && said(Transcript.Kind.TOOL, "跑 Python 脚本"), "聊天栏应逐步显示工具调用：" + transcript());
                expect(!said(Transcript.Kind.ERROR, ""), "不该有报错的行：" + transcript());
                expect(model.problems.isEmpty(), "假模型那头的问题：" + model.problems);
                expect(block(world, feet.offset(3, 0, -1)).equals("minecraft:stone_bricks"), "壳的一角应是石砖");
                expect(block(world, feet.offset(4, 1, 0)).equals("minecraft:air"), "壳里面应是空的");
                expect(block(world, feet.offset(4, 3, 0)).equals("minecraft:glowstone"), "Python 脚本应在壳顶放了萤石");

                // 撤回两次：先撤掉 Python 放的萤石，再撤掉石砖壳。
                command(context, "agent undo");
                waitUntil(context, "撤回萤石", FAKE_WAIT_TICKS, () -> block(world, feet.offset(4, 3, 0)).equals("minecraft:air"));
                expect(block(world, feet.offset(3, 0, -1)).equals("minecraft:stone_bricks"), "第一次撤回只该撤掉萤石");
                command(context, "agent undo");
                waitUntil(context, "撤回石砖壳", FAKE_WAIT_TICKS, () -> block(world, feet.offset(3, 0, -1)).equals("minecraft:air"));
                context.waitTicks(10);
                context.takeScreenshot("fake_2_undone");
                expect(said(Transcript.Kind.NOTE, "已撤回，还原了 26 个方块"), "撤回石砖壳应说还原了 26 个：" + transcript());
                expect(model.efforts.stream().allMatch("low"::equals), "主模型和备用模型都没单独写档位，应按 [model] 的 low 发：" + model.efforts);

                modelsAndThinking(context, model);
            }
        }
    }

    private void modelsAndThinking(ClientGameTestContext context, MockModel model) throws IOException {
        // 补全：只列以已输入的字开头的；模型名单是清单里的别名加直接写了模型 ID 的备用模型。
        expect(completions(context, "agent t").equals(Set.of("thinking")), "/agent t 应补全出 thinking：" + completions(context, "agent t"));
        expect(completions(context, "agent model ").equals(Set.of("main", "deep", "fixed", MockModel.BACKUP)),
                "/agent model 的补全应是清单里的别名加备用模型：" + completions(context, "agent model "));
        expect(completions(context, "agent model d").equals(Set.of("deep")), "/agent model d 应只补全 deep：" + completions(context, "agent model d"));
        expect(completions(context, "agent thinking ").equals(Set.of("off", "minimal", "low", "medium", "high")),
                "现在是备用模型（没写档位表），思考档位应补全默认五档：" + completions(context, "agent thinking "));

        // 换到清单里的模型：pi 不重启，下一句就落在新模型上，带着它在配置里的档位。
        command(context, "agent model deep");
        waitUntil(context, "换成 deep", FAKE_WAIT_TICKS, () -> said(Transcript.Kind.NOTE, "已换成模型 deep（" + MockModel.DEEP + "），思考档位 low"));
        rollCall(context, model, 1, MockModel.DEEP, "low", "换到 deep 以后");
        expect(count(Transcript.Kind.NOTE, "正在启动助手") == 1, "换清单里的模型不该重启助手：" + transcript());
        expect(completions(context, "agent thinking ").equals(Set.of("off", "low", "high")),
                "deep 只认 off、low、high 三档，补全应跟着变：" + completions(context, "agent thinking "));
        expect(completions(context, "agent thinking h").equals(Set.of("high")), "/agent thinking h 应只补全 high");

        // 调档：下一句带新档位，并写回配置文件里 deep 那一段；别的模型的档位不动。
        command(context, "agent thinking high");
        waitUntil(context, "档位改成 high", FAKE_WAIT_TICKS, () -> said(Transcript.Kind.NOTE, "思考档位已改成 high"));
        rollCall(context, model, 2, MockModel.DEEP, "high", "deep 调到 high 以后");
        expect(said(Transcript.Kind.NOTE, "已存进配置"), "调档应说已存进配置：" + transcript());
        AgentConfig saved = AgentConfig.parse(Files.readString(configFile()));
        expect(saved.choice("deep").thinking().equals("high") && saved.choice("main").thinking().equals("low"),
                "配置文件里应只有 deep 的档位变成 high：" + saved.choice("deep").thinking() + "、" + saved.choice("main").thinking());
        // off 这一档 deep 的表里写的是发 none。
        command(context, "agent thinking off");
        waitUntil(context, "档位改成 off", FAKE_WAIT_TICKS, () -> said(Transcript.Kind.NOTE, "思考档位已改成 off"));
        rollCall(context, model, 3, MockModel.DEEP, "none", "deep 调到 off 以后");
        // 表里没有的档位：说清能用哪几档，不改。
        command(context, "agent thinking minimal");
        waitUntil(context, "拒绝 minimal", FAKE_WAIT_TICKS, () -> said(Transcript.Kind.ERROR, "没有 minimal 这一档，能用的是 off、low、high"));

        // 思考档位由模型名决定的模型：什么思考参数都不发，调档和 status 都明说。
        command(context, "agent model fixed");
        waitUntil(context, "换成 fixed", FAKE_WAIT_TICKS, () -> said(Transcript.Kind.NOTE, "已换成模型 fixed（" + MockModel.FIXED + "），这个模型的思考档位由模型名决定"));
        command(context, "agent thinking high");
        waitUntil(context, "fixed 调不了", FAKE_WAIT_TICKS, () -> said(Transcript.Kind.NOTE, "这个模型的思考档位由模型名决定，调不了"));
        rollCall(context, model, 4, MockModel.FIXED, MockModel.NOT_SENT, "换到 fixed 以后");
        expect(completions(context, "agent thinking ").isEmpty(), "fixed 没有能调的档位，补全应为空：" + completions(context, "agent thinking "));
        command(context, "agent status");
        waitUntil(context, "status 说明档位由模型名决定", FAKE_WAIT_TICKS, () -> said(Transcript.Kind.NOTE, "思考档位：这个模型的思考档位由模型名决定"));
        expect(said(Transcript.Kind.NOTE, "模型清单：main、deep、fixed"), "status 应列出模型清单：" + transcript());

        // 换回 deep：档位还是刚才调的 off（发 none）。后面跟的不是档位就是一句话，照常交给助手。
        command(context, "agent model deep");
        waitUntil(context, "换回 deep", FAKE_WAIT_TICKS, () -> said(Transcript.Kind.NOTE, "已换成模型 deep（" + MockModel.DEEP + "），思考档位 off"));
        command(context, "agent thinking " + MockModel.ROLL_CALL);
        waitUntil(context, "第 5 次点名", FAKE_WAIT_TICKS, () -> said(Transcript.Kind.TEXT, MockModel.rollCall(5)) && !AgentClient.busy());
        expect(model.asked.getLast().contains("thinking " + MockModel.ROLL_CALL) && model.requests.getLast().equals(MockModel.DEEP)
                && model.efforts.getLast().equals("none"), "「thinking + 一句话」应当成话交给 deep，档位 none：" + model.asked.getLast()
                + "、" + model.requests.getLast() + "、" + model.efforts.getLast());
        // 开新对话：模型和档位都不该变回启动时的，前面聊过的不再带着。
        int before = model.messages.getLast();
        command(context, "agent new");
        waitUntil(context, "开新对话", FAKE_WAIT_TICKS, () -> said(Transcript.Kind.NOTE, "已开新对话"));
        rollCall(context, model, 6, MockModel.DEEP, "none", "开新对话以后");
        expect(model.messages.getLast() < before, "新对话不该带着前面的话：请求里的消息数从 " + before + " 变成 " + model.messages.getLast());
        int alone = model.messages.getLast();
        // 开新对话后不等它应答就换模型、马上说话：这一句要落在新对话里，答得回来。
        command(context, "agent new");
        command(context, "agent model fixed");
        rollCall(context, model, 7, MockModel.FIXED, MockModel.NOT_SENT, "开新对话紧跟着换到 fixed 以后");
        expect(model.messages.getLast() == alone, "开新对话紧跟着换模型，这一句应落在新对话里：请求里的消息数应是 " + alone + "，实际 " + model.messages.getLast());
        // 开新对话后换到没登记的模型（要重启助手）：重启后不该接回旧对话。
        command(context, "agent new");
        command(context, "agent model " + MockModel.UNLISTED);
        rollCall(context, model, 8, MockModel.UNLISTED, "low", "开新对话后换到清单外的模型以后");
        expect(count(Transcript.Kind.NOTE, "正在启动助手") == 2, "换清单外的模型应重启一次助手：" + transcript());
        expect(model.messages.getLast() == alone, "开新对话后重启助手不该接回旧对话：请求里的消息数应是 " + alone + "，实际 " + model.messages.getLast());
        expect(model.problems.isEmpty(), "假模型那头的问题：" + model.problems);
        context.takeScreenshot("fake_3_models");
    }

    private void rollCall(ClientGameTestContext context, MockModel model, int count, String expectedModel, String expectedEffort, String when) {
        // 让助手报个到，核对这一句发到了哪个模型、带的 reasoning_effort 是什么。
        command(context, "agent " + MockModel.ROLL_CALL);
        waitUntil(context, "第 " + count + " 次点名", FAKE_WAIT_TICKS, () -> said(Transcript.Kind.TEXT, MockModel.rollCall(count)) && !AgentClient.busy());
        expect(model.requests.getLast().equals(expectedModel) && model.efforts.getLast().equals(expectedEffort),
                when + "，应发给 " + expectedModel + "、reasoning_effort " + expectedEffort + "；实际是 " + model.requests.getLast() + "、" + model.efforts.getLast());
    }

    private static Set<String> completions(ClientGameTestContext context, String typed) {
        // 和玩家在聊天栏里按 Tab 走同一个指令树。
        return context.computeOnClient(client -> {
            var dispatcher = ClientCommands.getActiveDispatcher();
            var source = (FabricClientCommandSource) client.player.connection.getSuggestionsProvider();
            return dispatcher.getCompletionSuggestions(dispatcher.parse(typed, source)).join().getList().stream()
                    .map(Suggestion::getText).collect(Collectors.toSet());
        });
    }

    private void live(ClientGameTestContext context) throws IOException {
        String url = System.getenv("CLIPROXY_BASE_URL");
        String key = System.getenv("CLIPROXY_API_KEY");
        if (url == null || key == null) {
            throw new IllegalStateException("live 场景要环境变量 CLIPROXY_BASE_URL 和 CLIPROXY_API_KEY");
        }
        writeConfig(url.replaceAll("/+$", "") + "/v1", key, LIVE_MODEL, LIVE_FALLBACK);
        try (TestSingleplayerContext world = context.worldBuilder().create()) {
            BlockPos feet = settle(context, world);
            int before = solid(world, feet);
            // 聊天框开着，助手说过的话不会十秒后淡掉。
            context.runOnClient(client -> client.gui.setScreen(new ChatScreen("", false)));
            film(context, "live");
            command(context, "agent " + LIVE_BUILD);
            waitUntil(context, "助手盖完", LIVE_WAIT_TICKS, () -> !AgentClient.busy(), "live");
            int built = solid(world, feet) - before;
            expect(built >= 40, "助手应当在玩家面前盖出东西，多出来的方块只有 " + built + " 个");
            overview(context, world, feet);
            context.runOnClient(client -> client.gui.setScreen(new ChatScreen("", false)));
            command(context, "agent " + LIVE_LOOKUP);
            waitUntil(context, "助手查完", LIVE_WAIT_TICKS, () -> !AgentClient.busy(), "live");
            context.waitTicks(20);
            film(context, "live");
            expect(said(Transcript.Kind.TOOL, "JAR"), "助手应当用过看 JAR 的工具：" + transcript());
            expect(!said(Transcript.Kind.ERROR, "助手没起来") && !said(Transcript.Kind.ERROR, "助手进程退出"), "助手进程应当正常：" + transcript());
            context.runOnClient(client -> client.gui.setScreen(null));
        } finally {
            // 真密钥不留在运行目录里。
            Files.deleteIfExists(configFile());
        }
    }

    private void overview(ClientGameTestContext context, TestSingleplayerContext world, BlockPos feet) {
        // 收起聊天框，飞到几个远一点的位置各看几眼盖出来的东西（旁观模式才能停在半空），再回到原地。
        context.runOnClient(client -> client.gui.setScreen(null));
        world.getServer().runCommand("gamemode spectator @p");
        BlockPos target = feet.offset(OVERVIEW_AHEAD, OVERVIEW_TARGET_HEIGHT, 0);
        for (int[] offset : OVERVIEW_CAMERAS) {
            world.getServer().runCommand("tp @p %d %d %d facing %d %d %d".formatted(feet.getX() + offset[0], feet.getY() + offset[1],
                    feet.getZ() + offset[2], target.getX(), target.getY(), target.getZ()));
            context.waitTicks(20);
            world.getConnection().waitForChunksRender();
            for (int shot = 0; shot < OVERVIEW_SHOTS; shot++) {
                context.waitTicks(5);
                film(context, "live");
            }
        }
        world.getServer().runCommand("gamemode creative @p");
        world.getServer().runCommand("tp @p %d %d %d -90 0".formatted(feet.getX(), feet.getY(), feet.getZ()));
        context.waitTicks(20);
    }

    private static void writeConfig(String url, String key, String model, String fallback) throws IOException {
        // 进世界之前写好配置：模组在开世界时读它。
        Path file = configFile();
        Files.createDirectories(file.getParent());
        Files.writeString(file, """
                [model]
                base_url = "%s"
                api_key = "%s"
                model = "%s"
                fallback_model = "%s"
                """.formatted(url, key, model, fallback));
    }

    private static Path configFile() {
        return FabricLoader.getInstance().getConfigDir().resolve("kizuna-agent.toml");
    }

    private static BlockPos settle(ClientGameTestContext context, TestSingleplayerContext world) {
        // 把玩家放到一块平地上，面朝东，白天、不下雨；返回脚下那格。
        world.getConnection().waitForChunksRender();
        int ground = world.getServer().computeOnServer(server -> {
            ServerLevel level = server.overworld();
            int y = level.getMaxY();
            while (y > level.getMinY() && level.getBlockState(new BlockPos(STAND_X, y - 1, STAND_Z)).isAir()) {
                y--;
            }
            return y;
        });
        world.getServer().runCommand("gamemode creative @p");
        world.getServer().runCommand("time set noon");
        world.getServer().runCommand("weather clear");
        world.getServer().runCommand("tp @p %d %d %d -90 0".formatted(STAND_X, ground, STAND_Z));
        context.waitTicks(40);
        world.getConnection().waitForChunksRender();
        return new BlockPos(STAND_X, ground, STAND_Z);
    }

    private static void command(ClientGameTestContext context, String command) {
        // 走玩家在聊天栏里敲指令的同一条路。
        context.runOnClient(client -> client.player.connection.sendCommand(command));
    }

    private interface Condition {
        boolean met();
    }

    private void waitUntil(ClientGameTestContext context, String what, int ticks, Condition condition) {
        waitUntil(context, what, ticks, condition, null);
    }

    private void waitUntil(ClientGameTestContext context, String what, int ticks, Condition condition, String film) {
        // 助手在别的进程里干活，要等真实时间：每走一 tick 睡一小会儿。给了 film 就边等边拍。
        for (int tick = 0; tick < ticks; tick++) {
            context.waitTick();
            if (film != null && tick % FILM_EVERY_TICKS == 0) {
                film(context, film);
            }
            if (tick > 5 && condition.met()) {
                return;
            }
            try {
                Thread.sleep(TICK_SLEEP_MILLIS);
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException(interrupted);
            }
        }
        failures.add("等「" + what + "」超时。聊天栏里有：" + transcript());
    }

    private void film(ClientGameTestContext context, String scene) {
        context.takeScreenshot("film_%s_%03d".formatted(scene, frame++));
    }

    private void expect(boolean condition, String message) {
        if (!condition) {
            failures.add(message);
        }
    }

    private static long count(Transcript.Kind kind, String part) {
        return AgentClient.history().stream().filter(line -> line.kind() == kind && line.text().contains(part)).count();
    }

    private static boolean said(Transcript.Kind kind, String part) {
        return AgentClient.history().stream().anyMatch(line -> line.kind() == kind && line.text().contains(part));
    }

    private static String transcript() {
        return AgentClient.history().stream().map(line -> "[" + line.kind() + "] " + line.text()).collect(Collectors.joining(" | "));
    }

    private static String block(TestSingleplayerContext world, BlockPos position) {
        return world.getServer().computeOnServer(server -> BuiltInRegistries.BLOCK
                .getKey(server.overworld().getBlockState(position).getBlock()).toString());
    }

    private static int solid(TestSingleplayerContext world, BlockPos feet) {
        // 玩家面前（东边）一块 24×24×24 的空间里有多少个不是空气的方块。
        return world.getServer().computeOnServer(server -> {
            int count = 0;
            for (BlockPos position : BlockPos.betweenClosed(feet.offset(1, 0, -12), feet.offset(24, 23, 11))) {
                if (!server.overworld().getBlockState(position).is(Blocks.AIR)) {
                    count++;
                }
            }
            return count;
        });
    }

    private void report() {
        // 第一行给 kz 判成败；后面是失败原因和聊天栏里的全部内容，给人看。
        if (REPORT == null) {
            return;
        }
        var lines = new ArrayList<String>();
        lines.add("success=" + failures.isEmpty());
        lines.add("scene=" + SCENE);
        failures.forEach(failure -> lines.add("failure=" + failure));
        AgentClient.history().forEach(line -> lines.add("[" + line.kind() + "] " + line.text()));
        try {
            Files.write(Path.of(REPORT), lines);
        } catch (IOException failed) {
            throw new UncheckedIOException(failed);
        }
    }
}
