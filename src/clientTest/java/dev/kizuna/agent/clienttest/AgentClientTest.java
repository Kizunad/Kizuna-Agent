package dev.kizuna.agent.clienttest;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.mojang.brigadier.suggestion.Suggestion;
import dev.kizuna.agent.AgentMod;
import dev.kizuna.agent.bridge.BridgeHost;
import dev.kizuna.agent.client.AgentClient;
import dev.kizuna.agent.config.AgentConfig;
import dev.kizuna.agent.marks.Marks;
import dev.kizuna.agent.pi.Transcript;
import net.fabricmc.fabric.api.client.command.v2.ClientCommands;
import net.fabricmc.fabric.api.client.command.v2.FabricClientCommandSource;
import net.fabricmc.fabric.api.client.gametest.v1.FabricClientGameTest;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestSingleplayerContext;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.CameraType;
import net.minecraft.client.gui.screens.ChatScreen;
import net.minecraft.client.gui.screens.PauseScreen;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.Blocks;
import org.lwjgl.glfw.GLFW;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.stream.Collectors;

/**
 * 助手的真客户端验收：真的游戏窗口、真的 {@code /agent} 指令、真的 pi 进程和桥接。
 *
 * <p>三种场景（{@code -PagentScene=}）：</p>
 * <ul>
 *   <li>{@code fake}（默认）：模型换成照剧本答话的假接口，主模型回 429 逼出备用模型；逐项核对世界里的方块、聊天栏里的话、撤回，
 *   再核对指令补全、换模型、调思考档位以后假接口收到的模型名和 {@code reasoning_effort}。不联网、结果确定。</li>
 *   <li>{@code live}：接环境变量 {@code CLIPROXY_BASE_URL}、{@code CLIPROXY_API_KEY} 给的真模型接口，让它盖一座小建筑、
 *   查一个模组 JAR 的语言文件，隔一会儿拍一张，给人看。</li>
 *   <li>{@code live3}：同样接真模型，验记忆、技能和截图：记住一条偏好后开新对话照着盖，把做法存成技能后再开新对话用上，
 *   最后让它截图看自己盖的东西。</li>
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
     * 再加一个登记成不能看图的。依次填：接口地址、密钥、备用模型 ID、主模型 ID、deep、fixed、blind 的模型 ID。
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

            [[models]]
            alias = "blind"
            id = "%s"
            vision = false
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
    /** 第三版真模型验收里第二间小屋盖在第一间南边这么远。 */
    private static final int SECOND_SITE_SOUTH = 24;

    /** 颜色场地的中心在玩家东边这么远。 */
    private static final int STAGE_AHEAD = 12;
    private static final int SAMPLE_RADIUS = 4;
    private static final HttpClient HTTP = HttpClient.newHttpClient();

    private final List<String> failures = new ArrayList<>();
    /** 顺带量的几个耗时，写进报告。 */
    private final List<String> timings = new ArrayList<>();
    private int frame;

    @Override
    public void runTest(ClientGameTestContext context) {
        context.getInput().resizeWindow(WINDOW_WIDTH, WINDOW_HEIGHT);
        try {
            if (SCENE.equals("live")) {
                live(context);
            } else if (SCENE.equals("live3")) {
                liveNotebook(context);
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
                    MockModel.PRIMARY, MockModel.DEEP, MockModel.FIXED, MockModel.BLIND));
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

                captures(context, world, feet);
                modelsAndThinking(context, model);
                notebook(context, world, model, feet);
            }
        }
    }

    /** 搭一块好认颜色的场地：地面北半红、南半黄绿，中间一堵东西向的墙，西半蓝、东半黄。返回墙脚正中那一格。 */
    private static BlockPos stage(TestSingleplayerContext world, BlockPos feet) {
        BlockPos center = feet.offset(STAGE_AHEAD, 0, 0);
        int x = center.getX();
        int y = center.getY();
        int z = center.getZ();
        world.getServer().runCommand("fill %d %d %d %d %d %d minecraft:air".formatted(x - 4, y, z - 4, x + 4, y + 8, z + 4));
        world.getServer().runCommand("fill %d %d %d %d %d %d minecraft:red_concrete".formatted(x - 4, y - 1, z - 4, x + 4, y - 1, z - 1));
        world.getServer().runCommand("fill %d %d %d %d %d %d minecraft:lime_concrete".formatted(x - 4, y - 1, z + 1, x + 4, y - 1, z + 4));
        world.getServer().runCommand("fill %d %d %d %d %d %d minecraft:blue_concrete".formatted(x - 3, y, z, x - 1, y + 3, z));
        world.getServer().runCommand("fill %d %d %d %d %d %d minecraft:white_concrete".formatted(x, y, z, x, y + 3, z));
        world.getServer().runCommand("fill %d %d %d %d %d %d minecraft:yellow_concrete".formatted(x + 1, y, z, x + 3, y + 3, z));
        return center;
    }

    /** 截图接口：指定机位、绕拍、玩家视角、连 HUD，各自截到的确实是该看到的东西；截完相机和视角设置原样。 */
    private void captures(ClientGameTestContext context, TestSingleplayerContext world, BlockPos feet) throws IOException {
        BlockPos center = stage(world, feet);
        context.waitTicks(20);
        world.getConnection().waitForChunksRender();
        double x = center.getX() + 0.5;
        int y = center.getY();
        double z = center.getZ() + 0.5;

        // 机位在墙北边 8 格、朝南看：面朝南时左手是东，所以图的左边是黄（东半），右边是蓝（西半）。玩家一步没动。
        String fromNorth = "{\"camera\": {\"position\": [%s, %s, %s], \"look_at\": [%s, %s, %s], \"fov\": 70}}"
                .formatted(x, y + 2.5, z - 8, x, y + 2, z);
        long started = System.currentTimeMillis();
        JsonObject shot = shots(context, fromNorth).get(0).getAsJsonObject();
        long took = System.currentTimeMillis() - started;
        BufferedImage north = image(shot);
        expect(north.getWidth() == 960 && north.getHeight() == 540, "单张默认 960 宽、比例和窗口一样，实际 " + north.getWidth() + "×" + north.getHeight());
        expect(yellow(sample(north, 0.40, 0.50)) && blue(sample(north, 0.60, 0.50)),
                "从北面朝南看，墙应是左黄右蓝；左 " + hex(sample(north, 0.40, 0.50)) + "、右 " + hex(sample(north, 0.60, 0.50)));
        expect(Files.isRegularFile(Path.of(shot.get("file").getAsString())) && shot.get("file").getAsString().contains("screenshots"),
                "截图应存进工作目录的 screenshots/：" + shot.get("file"));
        expect(shot.getAsJsonArray("notes").isEmpty(), "正常机位不该有要留意的事：" + shot.get("notes"));
        timings.add("指定机位截一张用了 " + took + " 毫秒");
        Files.copy(Path.of(shot.get("file").getAsString()), evidence("capture_camera_from_north.jpg"), StandardCopyOption.REPLACE_EXISTING);
        context.waitTicks(3);
        expect(context.computeOnClient(client -> client.gameRenderer.mainCamera().position().distanceTo(client.player.getEyePosition()) < 0.5
                        && !client.gameRenderer.mainCamera().isDetached() && client.options.getCameraType() == CameraType.FIRST_PERSON),
                "截完相机应回到玩家眼睛上，还是第一人称");
        expect(context.computeOnClient(client -> client.player.blockPosition().equals(feet)), "截图不该挪动玩家");

        // 绕着拍：只要北面和俯视两张。俯视那张上边是北，所以上半红、下半黄绿。
        JsonArray orbit = shots(context, "{\"orbit\": {\"target\": [%s, %s, %s], \"distance\": 14, \"sides\": [\"north\"], \"top\": true, \"fov\": 70}}"
                .formatted(x, y, z));
        expect(orbit.size() == 2, "应拍两张，实际 " + orbit.size());
        BufferedImage top = image(orbit.get(1).getAsJsonObject());
        expect(top.getWidth() == 768, "绕拍每张默认 768 宽，实际 " + top.getWidth());
        expect(red(sample(top, 0.45, 0.36)) && lime(sample(top, 0.45, 0.64)),
                "俯视图上边是北：上半应红、下半应黄绿；上 " + hex(sample(top, 0.45, 0.36)) + "、下 " + hex(sample(top, 0.45, 0.64)));
        expect(orbit.get(0).getAsJsonObject().get("label").getAsString().contains("北面")
                && orbit.get(1).getAsJsonObject().get("label").getAsString().contains("俯视"), "每张图应说明从哪儿看：" + orbit);
        Files.copy(Path.of(orbit.get(1).getAsJsonObject().get("file").getAsString()), evidence("capture_orbit_top.jpg"), StandardCopyOption.REPLACE_EXISTING);

        // 第三人称下指定机位：照样截得到，截完还是第三人称。
        context.runOnClient(client -> client.options.setCameraType(CameraType.THIRD_PERSON_BACK));
        context.waitTicks(3);
        BufferedImage third = image(shots(context, fromNorth).get(0).getAsJsonObject());
        expect(yellow(sample(third, 0.40, 0.50)) && blue(sample(third, 0.60, 0.50)), "第三人称下指定机位截到的应一样");
        context.waitTicks(3);
        expect(context.computeOnClient(client -> client.options.getCameraType() == CameraType.THIRD_PERSON_BACK
                && client.gameRenderer.mainCamera().isDetached()), "截完应还是第三人称");
        context.runOnClient(client -> client.options.setCameraType(CameraType.FIRST_PERSON));
        context.waitTicks(3);

        // 另一个模组接管了相机（越肩视角那种，相机对位中途就返回）：指定机位照样截得对，截完相机还归那个模组管。
        ForeignCamera.active = true;
        context.waitTicks(3);
        BufferedImage foreign = image(shots(context, fromNorth).get(0).getAsJsonObject());
        ForeignCamera.active = false;
        expect(yellow(sample(foreign, 0.40, 0.50)) && blue(sample(foreign, 0.60, 0.50)),
                "别的模组接管相机时，指定机位截到的也应是左黄右蓝；左 " + hex(sample(foreign, 0.40, 0.50)) + "、右 " + hex(sample(foreign, 0.60, 0.50)));
        context.waitTicks(3);

        // 玩家的视角：缺省不含 HUD；hud 为 true 时底下正中有快捷栏。开着聊天框时，只截世界的那张里也没有界面。
        BufferedImage plain = image(shots(context, "{}").get(0).getAsJsonObject());
        BufferedImage hud = image(shots(context, "{\"hud\": true}").get(0).getAsJsonObject());
        double hotbar = difference(plain, hud, 0.42, 0.58, 0.93, 0.99);
        expect(hotbar > 12, "连 HUD 截的那张底下应有快捷栏，和不含 HUD 的差别只有 " + hotbar);
        context.runOnClient(client -> client.gui.setScreen(new ChatScreen("", false)));
        context.waitTicks(3);
        BufferedImage behindChat = image(shots(context, "{}").get(0).getAsJsonObject());
        BufferedImage withChat = image(shots(context, "{\"hud\": true}").get(0).getAsJsonObject());
        double same = difference(plain, behindChat, 0.0, 1.0, 0.90, 1.0);
        double chatBox = difference(plain, withChat, 0.0, 1.0, 0.95, 1.0);
        expect(same < 4, "开着聊天框时只截世界，底下那一条应和没开时一样，差别 " + same);
        expect(chatBox > 12, "开着聊天框连界面一起截，底下应有输入框，差别只有 " + chatBox);
        context.runOnClient(client -> client.gui.setScreen(null));
        context.waitTicks(3);
        // 暂停菜单开着（单人游戏暂停，菜单后面的世界被模糊）：只截世界的那张照样是清楚的原画面，截完还停在暂停菜单。
        context.runOnClient(client -> client.gui.setScreen(new PauseScreen(true)));
        context.waitTicks(3);
        BufferedImage paused = image(shots(context, "{}").get(0).getAsJsonObject());
        BufferedImage pausedCamera = image(shots(context, fromNorth).get(0).getAsJsonObject());
        double clear = difference(plain, paused, 0.0, 1.0, 0.0, 1.0);
        expect(clear < 4, "暂停时只截世界，应和没暂停时一样清楚，差别 " + clear);
        expect(yellow(sample(pausedCamera, 0.40, 0.50)) && blue(sample(pausedCamera, 0.60, 0.50)), "暂停时指定机位也应截得到");
        expect(context.computeOnClient(client -> client.gui.screen() instanceof PauseScreen && client.isPaused()), "截完应还停在暂停菜单");
        context.runOnClient(client -> client.gui.setScreen(null));
        context.waitTicks(3);
        ImageIO.write(plain, "png", evidence("capture_player_view.png").toFile());
        ImageIO.write(withChat, "png", evidence("capture_player_view_hud_chat.png").toFile());

        // 截不了、可能有问题的都说明白，不给一张空图了事。
        Reply far = bridge(context, "screenshot", "{\"camera\": {\"position\": [%s, %s, %s], \"yaw\": 0}}".formatted(x + 5000, y + 2, z));
        expect(far.status() == 409 && far.error().contains("超出渲染距离"), "机位远在渲染距离外应拒绝并说明：" + far);
        Reply inside = bridge(context, "screenshot", "{\"camera\": {\"position\": [%s, %s, %s], \"yaw\": 0}}".formatted(x + 2, y + 1.5, z));
        expect(inside.status() == 200 && inside.body().getAsJsonArray("shots").get(0).getAsJsonObject().get("notes").toString().contains("方块里面"),
                "机位在方块里应随图说明：" + inside.status());
        Reply wrong = bridge(context, "screenshot", "{\"camera\": {\"yaw\": 0}}");
        expect(wrong.status() == 400 && wrong.error().contains("position"), "缺机位位置应报 400：" + wrong);
        expect(context.computeOnClient(client -> client.player.blockPosition().equals(feet)), "一轮截图下来玩家还在原地");
    }

    /** 指点杖、记忆、技能、截图工具、/agent memory 和 skills：真的点右键、真的 pi，模型照剧本调工具。 */
    private void notebook(ClientGameTestContext context, TestSingleplayerContext world, MockModel model, BlockPos feet) throws IOException {
        BlockPos center = feet.offset(STAGE_AHEAD, 0, 0);
        BridgeHost host = AgentMod.host().orElseThrow();
        command(context, "agent memory");
        waitUntil(context, "还没有记忆", FAKE_WAIT_TICKS, () -> said(Transcript.Kind.NOTE, "助手还没有记忆"));
        command(context, "agent skills");
        waitUntil(context, "还没有技能", FAKE_WAIT_TICKS, () -> said(Transcript.Kind.NOTE, "助手还没有技能"));
        expect(completions(context, "agent m").equals(Set.of("model", "memory")), "/agent m 应补全 model、memory：" + completions(context, "agent m"));
        expect(completions(context, "agent memory ").equals(Set.of("forget ")), "/agent memory 后面应补全 forget：" + completions(context, "agent memory "));

        // 拿着指点杖站到墙北边 3 格：右键墙上的蓝方块、黄方块（两个点，围成的框画出来），再指远处、指天。
        context.runOnClient(client -> client.gui.hud.getChat().clearMessages(false));
        world.getServer().runCommand("give @p kizuna_agent:pointer");
        world.getServer().runCommand("setblock %d %d %d minecraft:diamond_block".formatted(center.getX(), center.getY() + 2, center.getZ() - 30));
        world.getServer().runCommand("tp @p %s %d %s 0 0".formatted(center.getX() + 0.5, center.getY(), center.getZ() - 2.5));
        context.waitTicks(20);
        BlockPos blue = center.offset(-2, 1, 0);
        BlockPos yellow = center.offset(2, 2, 0);
        click(context, blue);
        waitUntil(context, "标下第 1 个点", FAKE_WAIT_TICKS, () -> host.marks().list().size() == 1);
        click(context, yellow);
        waitUntil(context, "标下第 2 个点", FAKE_WAIT_TICKS, () -> host.marks().list().size() == 2);
        expect(host.marks().list().size() == 2 && host.marks().list().get(0).equals(new Marks.Mark(blue.getX(), blue.getY(), blue.getZ(),
                        "minecraft:overworld", "minecraft:blue_concrete", "north", Marks.Kind.BLOCK))
                        && host.marks().list().get(1).state().equals("minecraft:yellow_concrete"),
                "右键墙上的方块应依次记下蓝、黄两格，点的是北面：" + host.marks().list());
        click(context, yellow);
        context.waitTicks(10);
        expect(host.marks().list().size() == 2, "同一格再点不该重复记：" + host.marks().list());
        context.getInput().lookAt(center.offset(0, 2, 0));
        context.waitTicks(5);
        context.takeScreenshot("fake_4_two_marks");

        context.getInput().lookAt(center.offset(0, 2, -30));
        context.waitTicks(3);
        context.getInput().pressMouse(GLFW.GLFW_MOUSE_BUTTON_RIGHT);
        waitUntil(context, "标下远处的点", FAKE_WAIT_TICKS, () -> host.marks().list().size() == 3);
        context.getInput().lookAt(0, -90);
        context.waitTicks(3);
        context.getInput().pressMouse(GLFW.GLFW_MOUSE_BUTTON_RIGHT);
        waitUntil(context, "标下脚下", FAKE_WAIT_TICKS, () -> host.marks().list().size() == 4);
        expect(host.marks().list().size() == 4 && host.marks().list().get(2).kind() == Marks.Kind.DISTANT
                        && host.marks().list().get(2).state().equals("minecraft:diamond_block")
                        && host.marks().list().get(3).kind() == Marks.Kind.FEET, "远处应记下钻石块，指天应记脚下：" + host.marks().list());
        context.getInput().lookAt(center.offset(0, 2, 0));
        context.waitTicks(5);

        // 下一句话带上标记；助手照剧本用一遍记忆、技能、标记、截图、技能里的脚本。
        long errors = count(Transcript.Kind.ERROR, "");
        command(context, "agent new");
        command(context, "agent " + MockModel.NOTEBOOK);
        waitUntil(context, "第三版剧本走完", FAKE_WAIT_TICKS, () -> said(Transcript.Kind.TEXT, MockModel.NOTEBOOK_DONE) && !AgentClient.busy());
        context.waitTicks(10);
        context.takeScreenshot("fake_5_notebook");
        int first = model.asked.indexOf(model.asked.stream().filter(said -> said.contains(MockModel.NOTEBOOK)).findFirst().orElse(""));
        String asked = first < 0 ? "" : model.asked.get(first);
        expect(asked.contains("标了 4 个点") && asked.contains("#1 [%d, %d, %d] minecraft:blue_concrete（点的是 north 面）".formatted(blue.getX(), blue.getY(), blue.getZ()))
                && asked.contains("（远处指的") && asked.contains("玩家当时脚下"), "玩家的话后面应附上标记清单：" + asked);
        expect(first >= 0 && model.systems.get(first).contains("mc_screenshot") && model.systems.get(first).contains("from kizuna_mc import mc")
                && model.systems.get(first).contains("还没有记忆"), "系统提示词应讲清本事，这时还没有记忆");
        for (String tool : List.of("记住：" + MockModel.MEMORY, "存技能：" + MockModel.SKILL, "看玩家标的点", "截图：绕 ", "截图：玩家的视角",
                "读技能：" + MockModel.SKILL, "跑 Python 脚本：")) {
            expect(said(Transcript.Kind.TOOL, tool), "聊天栏应显示「" + tool + "」：" + transcript());
        }
        expect(count(Transcript.Kind.ERROR, "") == errors, "不该有新的报错行：" + transcript());
        expect(model.images.stream().mapToInt(Integer::intValue).max().orElse(0) == 3,
                "绕拍两张加玩家视角一张，应有 3 张图发给模型：" + model.images);
        expect(block(world, blue.above(MockModel.GOLD_ABOVE_MARK)).equals("minecraft:gold_block"),
                "技能里的脚本应在第 1 个标记上方放了金块，实际 " + block(world, blue.above(MockModel.GOLD_ABOVE_MARK)));
        expect(host.memory().list().equals(List.of(MockModel.MEMORY)) && host.skills().list().size() == 1
                && Files.isRegularFile(host.skills().root().resolve(MockModel.SKILL).resolve("SKILL.md")), "记忆和技能应落在 pi home 里");
        expect(model.problems.isEmpty(), "假模型那头的问题：" + model.problems);

        // 开新对话：记忆和技能还在系统提示词里；标记没变也重新带上。
        // 同一段对话的下一句：刚存的记忆和技能已经在系统提示词里（不用重启助手）；标记没变，不重复带。
        long rolls = count(Transcript.Kind.TEXT, "到，第 ");
        long starts = count(Transcript.Kind.NOTE, "正在启动助手");
        command(context, "agent " + MockModel.ROLL_CALL);
        waitUntil(context, "同一段对话里报到", FAKE_WAIT_TICKS, () -> count(Transcript.Kind.TEXT, "到，第 ") == rolls + 1 && !AgentClient.busy());
        expect(model.systems.getLast().contains("1. " + MockModel.MEMORY)
                && model.systems.getLast().contains("- " + MockModel.SKILL + "：" + MockModel.SKILL_DESCRIPTION),
                "同一段对话里刚存的记忆和技能，下一句的系统提示词里应该就有");
        expect(!model.asked.getLast().contains("标了"), "标记没变，这一句不该重复带：" + model.asked.getLast());
        // 开新对话：记忆和技能还在系统提示词里；标记重新带上。
        command(context, "agent new");
        command(context, "agent " + MockModel.ROLL_CALL);
        waitUntil(context, "新对话里报到", FAKE_WAIT_TICKS, () -> count(Transcript.Kind.TEXT, "到，第 ") == rolls + 2 && !AgentClient.busy());
        expect(model.systems.getLast().contains("1. " + MockModel.MEMORY) && model.systems.getLast().contains("- " + MockModel.SKILL + "："),
                "新对话的系统提示词里应带着记忆和技能");
        expect(model.asked.getLast().contains("标了 4 个点"), "新对话的第一句应重新带上标记：" + model.asked.getLast());
        expect(count(Transcript.Kind.NOTE, "正在启动助手") == starts, "存记忆、存技能、开新对话都不该重启助手");

        // 不能看图的模型：截图照截、存成文件，工具结果里说明没法给它看，请求里不带图。
        command(context, "agent model blind");
        waitUntil(context, "换成 blind", FAKE_WAIT_TICKS, () -> said(Transcript.Kind.NOTE, "已换成模型 blind（" + MockModel.BLIND + "）"));
        command(context, "agent " + MockModel.ONE_SHOT);
        waitUntil(context, "blind 截完", FAKE_WAIT_TICKS, () -> said(Transcript.Kind.TEXT, MockModel.SHOT_DONE) && !AgentClient.busy());
        expect(model.requests.getLast().equals(MockModel.BLIND) && model.images.getLast() == 0 && model.results.getLast().contains("不能看图")
                && model.results.getLast().contains("screenshots"), "不能看图的模型应收到说明和文件路径、收不到图：" + model.results.getLast());

        // 聊天栏里看记忆、删记忆、看技能。
        command(context, "agent memory");
        waitUntil(context, "列出记忆", FAKE_WAIT_TICKS, () -> said(Transcript.Kind.TEXT, "1. " + MockModel.MEMORY));
        command(context, "agent skills");
        waitUntil(context, "列出技能", FAKE_WAIT_TICKS, () -> said(Transcript.Kind.TEXT, MockModel.SKILL + "：" + MockModel.SKILL_DESCRIPTION));
        context.waitTicks(5);
        context.takeScreenshot("fake_6_memory_skills");
        command(context, "agent memory forget 1");
        waitUntil(context, "删掉记忆", FAKE_WAIT_TICKS, () -> said(Transcript.Kind.NOTE, "已删掉第 1 条：" + MockModel.MEMORY));
        expect(host.memory().list().isEmpty(), "删掉以后记忆应是空的：" + host.memory().list());
        command(context, "agent memory forget 1");
        waitUntil(context, "没有可删的", FAKE_WAIT_TICKS, () -> said(Transcript.Kind.ERROR, "现在一条记忆都没有"));

        // 蹲下右键清空标记。
        context.getInput().holdKey(options -> options.keyShift);
        context.waitTicks(5);
        context.getInput().pressMouse(GLFW.GLFW_MOUSE_BUTTON_RIGHT);
        waitUntil(context, "清空标记", FAKE_WAIT_TICKS, () -> host.marks().list().isEmpty());
        context.getInput().releaseKey(options -> options.keyShift);
        expect(host.marks().list().isEmpty(), "蹲下右键应清空标记：" + host.marks().list());
        context.waitTicks(5);
        context.takeScreenshot("fake_7_cleared");
    }

    private static void click(ClientGameTestContext context, BlockPos block) {
        context.getInput().lookAt(block);
        context.waitTicks(3);
        context.getInput().pressMouse(GLFW.GLFW_MOUSE_BUTTON_RIGHT);
        context.waitTicks(3);
    }

    /** 一次桥接接口调用的结果。 */
    private record Reply(int status, JsonObject body) {
        private String error() {
            return body.has("error") ? body.getAsJsonObject("error").get("message").getAsString() : "";
        }

        @Override
        public String toString() {
            return status + " " + error();
        }
    }

    private Reply bridge(ClientGameTestContext context, String name, String body) {
        // 像助手那样从别的线程调接口；截图要游戏接着画帧，所以这边一边等一边走 tick。
        BridgeHost host = AgentMod.host().orElseThrow();
        CompletableFuture<HttpResponse<String>> pending = HTTP.sendAsync(HttpRequest.newBuilder(URI.create(host.url() + "/v1/" + name))
                .header("Authorization", "Bearer " + host.token()).POST(HttpRequest.BodyPublishers.ofString(body)).build(),
                HttpResponse.BodyHandlers.ofString());
        waitUntil(context, "接口 " + name + " 应答", FAKE_WAIT_TICKS, pending::isDone);
        HttpResponse<String> response = pending.join();
        return new Reply(response.statusCode(), JsonParser.parseString(response.body()).getAsJsonObject());
    }

    private JsonArray shots(ClientGameTestContext context, String request) {
        Reply reply = bridge(context, "screenshot", request);
        if (reply.status() != 200) {
            throw new AssertionError("截图应当成功：" + request + " → " + reply);
        }
        return reply.body().getAsJsonArray("shots");
    }

    private static BufferedImage image(JsonObject shot) throws IOException {
        BufferedImage image = ImageIO.read(new ByteArrayInputStream(Base64.getDecoder().decode(shot.get("data").getAsString())));
        if (image.getWidth() != shot.get("width").getAsInt() || image.getHeight() != shot.get("height").getAsInt()) {
            throw new AssertionError("图片的实际大小和报的不一样：" + image.getWidth() + "×" + image.getHeight());
        }
        return image;
    }

    /** 图上一个位置（按宽高的比例给）周围一小块的平均色。 */
    private static int[] sample(BufferedImage image, double across, double down) {
        int centerX = (int) (image.getWidth() * across);
        int centerY = (int) (image.getHeight() * down);
        long[] sum = new long[3];
        int count = 0;
        for (int y = centerY - SAMPLE_RADIUS; y <= centerY + SAMPLE_RADIUS; y++) {
            for (int x = centerX - SAMPLE_RADIUS; x <= centerX + SAMPLE_RADIUS; x++) {
                int pixel = image.getRGB(x, y);
                sum[0] += pixel >> 16 & 0xFF;
                sum[1] += pixel >> 8 & 0xFF;
                sum[2] += pixel & 0xFF;
                count++;
            }
        }
        return new int[] {(int) (sum[0] / count), (int) (sum[1] / count), (int) (sum[2] / count)};
    }

    /** 两张一样大的图在一块区域（按比例给）里每个像素每个颜色分量平均差多少。 */
    private static double difference(BufferedImage first, BufferedImage second, double left, double right, double top, double bottom) {
        long sum = 0;
        long count = 0;
        for (int y = (int) (first.getHeight() * top); y < (int) (first.getHeight() * bottom); y++) {
            for (int x = (int) (first.getWidth() * left); x < (int) (first.getWidth() * right); x++) {
                int one = first.getRGB(x, y);
                int two = second.getRGB(x, y);
                sum += Math.abs((one >> 16 & 0xFF) - (two >> 16 & 0xFF)) + Math.abs((one >> 8 & 0xFF) - (two >> 8 & 0xFF))
                        + Math.abs((one & 0xFF) - (two & 0xFF));
                count += 3;
            }
        }
        return (double) sum / count;
    }

    private static boolean red(int[] color) {
        return color[0] > 100 && color[1] < 80 && color[2] < 80;
    }

    private static boolean lime(int[] color) {
        return color[1] > 110 && color[2] < 80 && color[1] > color[0] + 30;
    }

    private static boolean blue(int[] color) {
        return color[2] > 80 && color[0] < 80 && color[2] > color[0] + 40;
    }

    private static boolean yellow(int[] color) {
        return color[0] > 140 && color[1] > 100 && color[2] < 90;
    }

    private static String hex(int[] color) {
        return "#%02x%02x%02x".formatted(color[0], color[1], color[2]);
    }

    /** 留给人看的图放在运行目录的 screenshots/ 里，和框架拍的那些在一起。 */
    private static Path evidence(String name) throws IOException {
        return Files.createDirectories(FabricLoader.getInstance().getGameDir().resolve("screenshots")).resolve(name);
    }

    private void modelsAndThinking(ClientGameTestContext context, MockModel model) throws IOException {
        // 补全：只列以已输入的字开头的；模型名单是清单里的别名加直接写了模型 ID 的备用模型。
        expect(completions(context, "agent t").equals(Set.of("thinking")), "/agent t 应补全出 thinking：" + completions(context, "agent t"));
        expect(completions(context, "agent model ").equals(Set.of("main", "deep", "fixed", "blind", MockModel.BACKUP)),
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
        expect(said(Transcript.Kind.NOTE, "模型清单：main、deep、fixed、blind"), "status 应列出模型清单：" + transcript());

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

    /**
     * 第三版的真模型验收，边做边拍：记住一条偏好 → 开新对话照偏好盖 → 把做法存成技能 → 再开新对话、换个地方用技能盖 →
     * 让它截图看自己盖的东西并说出看到了什么。
     */
    private void liveNotebook(ClientGameTestContext context) throws IOException {
        String url = System.getenv("CLIPROXY_BASE_URL");
        String key = System.getenv("CLIPROXY_API_KEY");
        if (url == null || key == null) {
            throw new IllegalStateException("live3 场景要环境变量 CLIPROXY_BASE_URL 和 CLIPROXY_API_KEY");
        }
        writeConfig(url.replaceAll("/+$", "") + "/v1", key, LIVE_MODEL, LIVE_FALLBACK);
        try (TestSingleplayerContext world = context.worldBuilder().create()) {
            BlockPos feet = settle(context, world);
            BridgeHost host = AgentMod.host().orElseThrow();
            context.runOnClient(client -> client.gui.setScreen(new ChatScreen("", false)));
            film(context, "live3");

            say(context, "记住：我盖房子屋顶一律用深色橡木（dark_oak），墙用石砖");
            expect(host.memory().list().stream().anyMatch(entry -> entry.contains("深色橡木") || entry.contains("dark_oak")),
                    "说了「记住」以后记忆里应有这条偏好：" + host.memory().list());
            command(context, "agent memory");
            context.waitTicks(20);
            film(context, "live3");

            // 新对话：没再提屋顶用什么，它该照记忆用深色橡木。
            int before = solid(world, feet);
            command(context, "agent new");
            say(context, "在我面前盖一间 5×5 的小屋，有门有窗，里面放火把");
            expect(solid(world, feet) - before >= 40, "应当盖出小屋，多出来的方块只有 " + (solid(world, feet) - before) + " 个");
            int darkOak = named(world, feet, "dark_oak");
            expect(darkOak >= 5, "新对话里没提屋顶，应照记忆用深色橡木；小屋里名字带 dark_oak 的方块只有 " + darkOak + " 个");
            overview(context, world, feet, "live3");
            context.runOnClient(client -> client.gui.setScreen(new ChatScreen("", false)));

            say(context, "把刚才盖这间小屋的做法存成技能，名字叫 small-hut，以后说盖小屋就用它");
            expect(host.skills().list().stream().anyMatch(skill -> skill.name().equals("small-hut")),
                    "应存下 small-hut 这个技能：" + host.skills().list());
            command(context, "agent skills");
            context.waitTicks(20);
            film(context, "live3");

            // 再开新对话、往南走 24 格：它该先读技能再盖。
            BlockPos second = feet.offset(0, 0, SECOND_SITE_SOUTH);
            world.getServer().runCommand("tp @p %d %d %d -90 0".formatted(second.getX(), second.getY(), second.getZ()));
            context.waitTicks(20);
            int secondBefore = solid(world, second);
            long reads = count(Transcript.Kind.TOOL, "读技能：small-hut");
            command(context, "agent new");
            say(context, "用你的技能在我面前再盖一间小屋");
            expect(count(Transcript.Kind.TOOL, "读技能：small-hut") > reads, "新对话里应先读 small-hut 这个技能：" + transcript());
            expect(solid(world, second) - secondBefore >= 40, "应当在新地方又盖出一间，多出来的方块只有 " + (solid(world, second) - secondBefore) + " 个");

            long shots = count(Transcript.Kind.TOOL, "截图：");
            say(context, "绕着你刚盖的这间小屋截图看一圈，告诉我你从图上看到了什么、有没有哪里不对");
            expect(count(Transcript.Kind.TOOL, "截图：") > shots, "应当用截图工具看：" + transcript());
            expect(!said(Transcript.Kind.ERROR, "助手没起来") && !said(Transcript.Kind.ERROR, "助手进程退出"), "助手进程应当正常：" + transcript());
            context.waitTicks(40);
            film(context, "live3");
            overview(context, world, second, "live3");
        } finally {
            // 真密钥不留在运行目录里。
            Files.deleteIfExists(configFile());
        }
    }

    private void say(ClientGameTestContext context, String text) {
        command(context, "agent " + text);
        waitUntil(context, "助手答完「" + text + "」", LIVE_WAIT_TICKS, () -> !AgentClient.busy(), "live3");
        context.waitTicks(20);
        film(context, "live3");
    }

    private static int named(TestSingleplayerContext world, BlockPos feet, String part) {
        // 玩家面前那块空间里，名字里带这个词的方块有多少个。
        return world.getServer().computeOnServer(server -> {
            int count = 0;
            for (BlockPos position : BlockPos.betweenClosed(feet.offset(1, 0, -12), feet.offset(24, 23, 11))) {
                if (BuiltInRegistries.BLOCK.getKey(server.overworld().getBlockState(position).getBlock()).getPath().contains(part)) {
                    count++;
                }
            }
            return count;
        });
    }

    private void overview(ClientGameTestContext context, TestSingleplayerContext world, BlockPos feet) {
        overview(context, world, feet, "live");
    }

    private void overview(ClientGameTestContext context, TestSingleplayerContext world, BlockPos feet, String scene) {
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
                film(context, scene);
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
        timings.forEach(timing -> lines.add("timing=" + timing));
        AgentClient.history().forEach(line -> lines.add("[" + line.kind() + "] " + line.text()));
        try {
            Files.write(Path.of(REPORT), lines);
        } catch (IOException failed) {
            throw new UncheckedIOException(failed);
        }
    }
}
