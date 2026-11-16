package dev.kizuna.agent.gametest;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import dev.kizuna.agent.AgentMod;
import dev.kizuna.agent.bridge.BridgeHost;
import dev.kizuna.agent.marks.AgentItems;
import dev.kizuna.agent.marks.Marks;
import dev.kizuna.agent.marks.PointerItem;
import net.fabricmc.fabric.api.gametest.v1.GameTest;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.contents.TranslatableContents;
import net.minecraft.resources.Identifier;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.Blocks;

import java.io.IOException;
import java.nio.file.Files;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;

/**
 * 桥接接口的端到端回归：每个用例真起一套 HTTP 服务对着测试世界，从另一条线程像外部脚本那样发请求。
 *
 * <p>请求要等服务端主线程干活，所以脚本跑在别的线程上，用例每 tick 看它跑完没有。</p>
 */
public final class BridgeGameTest {
    private static final int TIMEOUT_TICKS = 600;
    private static final int TICK_MILLIS = 5;
    /** 多 tick 用例在自己场地正上方放一根方柱：底面 7×7，离场地这么高起，高这么多格。 */
    private static final int COLUMN_BASE = 10;
    private static final int COLUMN_HEIGHT = 240;
    private static final int COLUMN_WIDTH = 7;

    /** 一次接口调用的结果：HTTP 状态码和应答体。 */
    private record Answer(int status, JsonObject body) {
        private String error() {
            return body.has("error") ? body.getAsJsonObject("error").get("message").getAsString() : "";
        }
    }

    /** 像外部脚本一样调接口的小客户端。 */
    private static final class Client {
        private final HttpClient http = HttpClient.newHttpClient();
        private final BridgeHost host;

        private Client(BridgeHost host) {
            this.host = host;
        }

        private Answer call(String name, String body) {
            return send(HttpRequest.newBuilder(URI.create(host.url() + "/v1/" + name))
                    .header("Authorization", "Bearer " + host.token())
                    .POST(HttpRequest.BodyPublishers.ofString(body)));
        }

        private Answer send(HttpRequest.Builder request) {
            try {
                HttpResponse<String> response = http.send(request.build(), HttpResponse.BodyHandlers.ofString());
                return new Answer(response.statusCode(), JsonParser.parseString(response.body()).getAsJsonObject());
            } catch (IOException failed) {
                throw new AssertionError("请求没发出去：" + failed);
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                throw new AssertionError("请求被打断");
            }
        }

        private JsonObject ok(String name, String body) {
            Answer answer = call(name, body);
            check(answer.status() == 200 && answer.body().get("ok").getAsBoolean(), name + " 应当成功，实际 " + answer);
            return answer.body();
        }
    }

    @FunctionalInterface
    private interface Script {
        void run(Client client);
    }

    @GameTest(maxTicks = TIMEOUT_TICKS)
    public void ownerRunsCommandsAndEditsBlocks(GameTestHelper context) {
        // 世界主人：指令照主人权限执行并收回输出；放方块、读回、填充、复制、撤回都对得上，箱子内容撤回后还在。
        ServerPlayer player = context.makeMockServerPlayerInLevel();
        BlockPos origin = context.absolutePos(new BlockPos(1, 1, 1));
        run(context, player, true, client -> {
            JsonObject command = client.ok("command", "{\"command\": \"/setblock %d %d %d minecraft:gold_block\"}"
                    .formatted(origin.getX(), origin.getY() + 4, origin.getZ()));
            check(command.get("success").getAsBoolean() && command.getAsJsonArray("output").size() == 1,
                    "主人执行 setblock 应当成功并有一行输出，实际 " + command);
            JsonObject placed = client.ok("blocks/set", """
                    {"origin": %s, "palette": ["minecraft:oak_stairs[facing=north,half=top]",
                     "minecraft:chest[facing=east]{Items:[{Slot:0b,id:\\"minecraft:diamond\\",count:3}]}"],
                     "blocks": [[0, 0, 0, 0], [1, 0, 0, 1]]}""".formatted(array(origin)));
            check(placed.get("changed").getAsInt() == 2 && placed.get("undoable").getAsBoolean(), "应当放下 2 个，实际 " + placed);
            JsonObject read = client.ok("blocks/get", "{\"from\": %s, \"to\": %s, \"nbt\": true}"
                    .formatted(array(origin), array(origin.offset(1, 0, 0))));
            check(read.getAsJsonArray("palette").get(0).getAsString()
                            .equals("minecraft:oak_stairs[facing=north,half=top,shape=straight,waterlogged=false]"),
                    "读回的楼梯写法不对：" + read.getAsJsonArray("palette"));
            check(read.getAsJsonArray("block_entities").toString().contains("minecraft:diamond"), "读回的箱子里没有钻石：" + read);

            // 把箱子盖掉再撤回：箱子和里面的钻石都回来。
            JsonObject covered = client.ok("blocks/fill", "{\"from\": %s, \"to\": %s, \"block\": \"minecraft:stone\"}"
                    .formatted(array(origin), array(origin.offset(1, 0, 0))));
            check(covered.get("changed").getAsInt() == 2, "应当盖掉 2 个，实际 " + covered);
            JsonObject undone = client.ok("undo", "{}");
            check(undone.get("restored").getAsInt() == 2 && undone.get("remaining").getAsInt() == 1, "撤回应还原 2 个、还剩 1 批，实际 " + undone);
            JsonObject restored = client.ok("blocks/get", "{\"from\": %s, \"to\": %s, \"nbt\": true}"
                    .formatted(array(origin.offset(1, 0, 0)), array(origin.offset(1, 0, 0))));
            check(restored.toString().contains("minecraft:chest") && restored.toString().contains("minecraft:diamond"),
                    "撤回后箱子或里面的钻石没回来：" + restored);

            // 只替换楼梯；空心壳；复制（连箱子内容）。
            JsonObject replaced = client.ok("blocks/fill", """
                    {"from": %s, "to": %s, "block": "minecraft:glass", "replace": "minecraft:oak_stairs"}"""
                    .formatted(array(origin), array(origin.offset(1, 0, 0))));
            check(replaced.get("changed").getAsInt() == 1, "只该换掉楼梯那 1 个，实际 " + replaced);
            JsonObject shell = client.ok("blocks/fill", """
                    {"from": %s, "to": %s, "block": "minecraft:stone_bricks", "hollow": true, "mode": "strict"}"""
                    .formatted(array(origin.offset(0, 0, 2)), array(origin.offset(2, 2, 4))));
            check(shell.get("changed").getAsInt() == 26, "3×3×3 的空心壳是 26 个，实际 " + shell);
            JsonObject cloned = client.ok("blocks/clone", "{\"from\": %s, \"to\": %s, \"destination\": %s}"
                    .formatted(array(origin), array(origin.offset(1, 0, 0)), array(origin.offset(4, 0, 0))));
            check(cloned.get("changed").getAsInt() == 2, "应当复制 2 个，实际 " + cloned);
            JsonObject copy = client.ok("blocks/get", "{\"from\": %s, \"to\": %s, \"nbt\": true}"
                    .formatted(array(origin.offset(4, 0, 0)), array(origin.offset(5, 0, 0))));
            check(copy.toString().contains("minecraft:glass") && copy.toString().contains("minecraft:diamond"),
                    "复制出来的应是玻璃和带钻石的箱子：" + copy);

            // 写错的请求说得清错在哪儿。
            Answer unknown = client.call("blocks/fill", "{\"from\": [0,0,0], \"to\": [0,0,0], \"block\": \"minecraft:no_such_block\"}");
            check(unknown.status() == 400 && unknown.error().contains("minecraft:no_such_block"), "不认识的方块应报 400 并点名：" + unknown);
            Answer huge = client.call("blocks/get", "{\"from\": [0,0,0], \"to\": [64,64,64]}");
            check(huge.status() == 400 && huge.error().contains("分块读"), "超过上限的读取应报 400：" + huge);
            Answer fraction = client.call("blocks/get", "{\"from\": [0.5,0,0], \"to\": [1,1,1]}");
            check(fraction.status() == 400, "小数坐标应报 400：" + fraction);
        });
    }

    @GameTest(maxTicks = TIMEOUT_TICKS)
    public void plainPlayerIsRefused(GameTestHelper context) {
        // 不是世界主人、也没有管理员权限的玩家：改方块的接口拒掉，指令照他自己的权限执行所以 setblock 不成；只读的照常。
        ServerPlayer player = context.makeMockServerPlayerInLevel();
        BlockPos origin = context.absolutePos(new BlockPos(1, 1, 1));
        run(context, player, false, client -> {
            Answer fill = client.call("blocks/fill", "{\"from\": %s, \"to\": %s, \"block\": \"minecraft:stone\"}"
                    .formatted(array(origin), array(origin)));
            check(fill.status() == 403 && fill.error().contains("权限"), "没权限的玩家填充应报 403：" + fill);
            Answer undo = client.call("undo", "{}");
            check(undo.status() == 403, "没权限的玩家撤回应报 403：" + undo);
            JsonObject command = client.ok("command", "{\"command\": \"setblock %d %d %d minecraft:gold_block\"}"
                    .formatted(origin.getX(), origin.getY(), origin.getZ()));
            check(!command.get("success").getAsBoolean(), "没权限的玩家执行 setblock 不该成功：" + command);
            JsonObject read = client.ok("blocks/get", "{\"from\": %s, \"to\": %s}".formatted(array(origin), array(origin)));
            check(read.getAsJsonArray("palette").toString().contains("minecraft:air"), "方块不该被改动：" + read);
            JsonObject info = client.ok("player", "{}");
            check(!info.get("can_edit").getAsBoolean(), "player 应说明这个玩家不能改方块：" + info);
            check(info.getAsJsonArray("block").toString().equals(array(player.blockPosition()).replace(" ", "")),
                    "player 报的脚下坐标不对：" + info + "，应为 " + player.blockPosition());
        });
    }

    @GameTest(maxTicks = TIMEOUT_TICKS)
    public void bigFillSpreadsOverTicksAndUndoes(GameTestHelper context) {
        // 一万多个方块、每 tick 只给 1 毫秒：分好多 tick 才放完，服务端照常走 tick；撤回同样分 tick，撤完一个不剩。
        ServerPlayer player = context.makeMockServerPlayerInLevel();
        BlockPos corner = context.absolutePos(new BlockPos(0, COLUMN_BASE, 0));
        BlockPos opposite = corner.offset(COLUMN_WIDTH - 1, COLUMN_HEIGHT - 1, COLUMN_WIDTH - 1);
        int volume = COLUMN_WIDTH * COLUMN_HEIGHT * COLUMN_WIDTH;
        run(context, player, true, 1, client -> {
            JsonObject filled = client.ok("blocks/fill", "{\"from\": %s, \"to\": %s, \"block\": \"minecraft:glass\", \"mode\": \"strict\"}"
                    .formatted(array(corner), array(opposite)));
            check(filled.get("changed").getAsInt() == volume, "应当放下 " + volume + " 个，实际 " + filled);
            check(filled.get("ticks").getAsInt() >= 2, "每 tick 1 毫秒放 " + volume + " 个应当分好几个 tick，实际 " + filled);
            JsonObject sample = client.ok("blocks/get", "{\"from\": %s, \"to\": %s}".formatted(array(opposite), array(opposite)));
            check(sample.getAsJsonArray("palette").toString().contains("minecraft:glass"), "最后一个角应是玻璃：" + sample);
            JsonObject undone = client.ok("undo", "{}");
            check(undone.get("restored").getAsInt() == volume, "撤回应还原 " + volume + " 个，实际 " + undone);
            JsonObject after = client.ok("blocks/get", "{\"from\": %s, \"to\": %s}".formatted(array(corner), array(corner.offset(COLUMN_WIDTH - 1, 63, COLUMN_WIDTH - 1))));
            check(after.getAsJsonArray("palette").size() == 1 && after.getAsJsonArray("palette").toString().contains("minecraft:air"),
                    "撤回后应全是空气：" + after.getAsJsonArray("palette"));
            Answer nothing = client.call("undo", "{}");
            check(nothing.status() == 409, "没有可撤的时候应报 409：" + nothing);
        });
    }

    @GameTest(maxTicks = TIMEOUT_TICKS)
    public void inspectsLoadedModJars(GameTestHelper context) {
        // 模组列表里有本模组；能列它的文件、读它的 fabric.mod.json、在文本里搜到、看到入口类的签名；想跳出 JAR 的路径读不到。
        ServerPlayer player = context.makeMockServerPlayerInLevel();
        run(context, player, false, client -> {
            JsonObject mods = client.ok("mods", "{}");
            check(mods.toString().contains("\"id\":\"kizuna_agent\"") && mods.toString().contains("\"id\":\"minecraft\""),
                    "模组列表里应有 kizuna_agent 和 minecraft");
            JsonObject listed = client.ok("jar/list", "{\"mod\": \"kizuna_agent\", \"glob\": \"**.json\"}");
            check(listed.toString().contains("\"path\":\"fabric.mod.json\""), "应列出 fabric.mod.json：" + listed);
            JsonObject read = client.ok("jar/read", "{\"mod\": \"kizuna_agent\", \"path\": \"fabric.mod.json\"}");
            check(read.get("text").getAsString().contains("\"id\": \"kizuna_agent\"") && !read.get("truncated").getAsBoolean(),
                    "读到的 fabric.mod.json 不对：" + read);
            JsonObject part = client.ok("jar/read", "{\"mod\": \"kizuna_agent\", \"path\": \"fabric.mod.json\", \"max_bytes\": 10}");
            check(part.get("text").getAsString().length() == 10 && part.get("truncated").getAsBoolean(), "分段读应只给 10 字节并标明没读完：" + part);
            JsonObject found = client.ok("jar/search", "{\"text\": \"Kizuna Agent\", \"mod\": \"kizuna_agent\", \"glob\": \"*.json\"}");
            JsonArray matches = found.getAsJsonArray("matches");
            check(matches.size() == 1 && matches.get(0).getAsJsonObject().get("path").getAsString().equals("fabric.mod.json"),
                    "应在 fabric.mod.json 里搜到一处：" + found);
            JsonObject classes = client.ok("jar/classes", "{\"mod\": \"kizuna_agent\", \"prefix\": \"dev.kizuna.agent.bridge\"}");
            check(classes.toString().contains("dev.kizuna.agent.bridge.BridgeHost"), "应列出 BridgeHost：" + classes);
            JsonObject signature = client.ok("jar/class", "{\"mod\": \"kizuna_agent\", \"class\": \"dev.kizuna.agent.AgentMod\"}");
            check(signature.getAsJsonArray("methods").toString().contains("public void onInitialize()")
                            && signature.getAsJsonArray("implements").toString().contains("net.fabricmc.api.ModInitializer")
                            && !signature.toString().contains("private"),
                    "入口类的签名不对：" + signature);
            Answer escaped = client.call("jar/read", "{\"mod\": \"kizuna_agent\", \"path\": \"../../../etc/passwd\"}");
            check(escaped.status() == 404, "跳出 JAR 的路径应读不到：" + escaped);
            Answer binary = client.call("jar/read", "{\"mod\": \"kizuna_agent\", \"path\": \"dev/kizuna/agent/AgentMod.class\"}");
            check(binary.status() == 400 && binary.error().contains("jar/class"), "类文件不当文本读，并指路去看签名：" + binary);
            Answer missing = client.call("jar/list", "{\"mod\": \"no_such_mod\"}");
            check(missing.status() == 400 && missing.error().contains("no_such_mod"), "没加载的模组应报 400：" + missing);
        });
    }

    @GameTest(maxTicks = TIMEOUT_TICKS)
    public void rejectsRequestsWithoutTokenOrFromWebPages(GameTestHelper context) {
        // 没带令牌、令牌不对、网页发来的（带 Origin）、不是 POST、没有的接口：一律拒，而且说明原因。
        ServerPlayer player = context.makeMockServerPlayerInLevel();
        run(context, player, true, client -> {
            URI player1 = URI.create(client.host.url() + "/v1/player");
            HttpRequest.BodyPublisher empty = HttpRequest.BodyPublishers.ofString("{}");
            check(client.send(HttpRequest.newBuilder(player1).POST(empty)).status() == 401, "没带令牌应报 401");
            check(client.send(HttpRequest.newBuilder(player1).header("Authorization", "Bearer wrong").POST(empty)).status() == 401,
                    "令牌不对应报 401");
            check(client.send(HttpRequest.newBuilder(player1).header("Authorization", "Bearer " + client.host.token())
                    .header("Origin", "http://example.com").POST(empty)).status() == 403, "带 Origin 的请求应报 403");
            check(client.send(HttpRequest.newBuilder(player1).header("Authorization", "Bearer " + client.host.token()).GET()).status() == 405,
                    "GET 应报 405");
            Answer unknown = client.call("no/such", "{}");
            check(unknown.status() == 404 && unknown.error().contains("blocks/set"), "没有的接口应报 404 并列出有哪些：" + unknown);
            check(client.call("player", "[1, 2").status() == 400, "不是 JSON 的请求体应报 400");
            client.ok("player", "");
        });
    }

    @GameTest(maxTicks = TIMEOUT_TICKS)
    public void memorySkillsAndMarksAreServedOverTheBridge(GameTestHelper context) {
        // 记忆、技能、标记这几组接口都挂在桥接上：存进去、读回来，「每句话开头带的那段」里马上就有。
        ServerPlayer player = context.makeMockServerPlayerInLevel();
        BlockPos origin = context.absolutePos(new BlockPos(1, 1, 1));
        context.getLevel().setBlockAndUpdate(origin, Blocks.STONE_BRICKS.defaultBlockState());
        run(context, player, true, (client, host) -> {
            check(client.ok("memory/save", "{\"text\": \"屋顶用深色橡木\"}").get("number").getAsInt() == 1, "第一条记忆应是第 1 条");
            check(client.ok("memory/list", "{}").getAsJsonArray("entries").size() == 1, "应列出 1 条记忆");
            check(client.call("memory/forget", "{\"number\": 7}").status() == 400, "删不存在的记忆应报 400");
            JsonObject saved = client.ok("skills/save", """
                    {"name": "stone-watchtower", "description": "盖石砖瞭望塔", "body": "先看地面",
                     "files": [{"path": "scripts/build.py", "content": "print('tower')"}]}""");
            check(!saved.get("updated").getAsBoolean() && Files.isRegularFile(
                            host.skills().root().resolve("stone-watchtower").resolve("scripts").resolve("build.py")),
                    "技能和它带的脚本应写进技能库目录：" + saved);
            check(client.ok("skills/read", "{\"name\": \"stone-watchtower\"}").get("text").getAsString().contains("先看地面"),
                    "应读回技能正文");
            check(client.call("skills/save", "{\"name\": \"../x\", \"description\": \"a\", \"body\": \"b\"}").status() == 400,
                    "技能名不合规矩应报 400");

            check(client.ok("marks", "{}").getAsJsonArray("marks").isEmpty(), "一开始没有标记");
            host.marks().add(new Marks.Mark(origin.getX(), origin.getY(), origin.getZ(), "minecraft:overworld",
                    "minecraft:stone_bricks", "up", Marks.Kind.BLOCK));
            JsonObject mark = client.ok("marks", "{}").getAsJsonArray("marks").get(0).getAsJsonObject();
            check(mark.get("number").getAsInt() == 1 && mark.get("block").toString().equals(array(origin).replace(" ", "")),
                    "标记应带编号和坐标：" + mark);

            String prompt = client.ok("context", "{}").get("prompt").getAsString();
            check(prompt.contains("1. 屋顶用深色橡木") && prompt.contains("- stone-watchtower：盖石砖瞭望塔"),
                    "每句话开头带的那段里应有刚存的记忆和技能：" + prompt);
        });
    }

    @GameTest
    public void pointerRecordsWhatWasClickedAndSaysSo(GameTestHelper context) {
        // 指点杖记下的是：坐标、维度、那一格是什么方块、点的哪个面、怎么标的；回给玩家的话对得上编号。
        ServerPlayer player = context.makeMockServerPlayerInLevel();
        BlockPos bricks = context.absolutePos(new BlockPos(1, 1, 1));
        BlockPos stairs = context.absolutePos(new BlockPos(3, 1, 1));
        context.getLevel().setBlockAndUpdate(bricks, Blocks.STONE_BRICKS.defaultBlockState());
        context.getLevel().setBlockAndUpdate(stairs, Blocks.OAK_STAIRS.defaultBlockState());
        var marks = new Marks();

        Component first = PointerItem.mark(marks, player, bricks, Direction.UP, Marks.Kind.BLOCK);
        context.assertTrue(key(first).equals("kizuna_agent.pointer.marked") && argument(first, 0).equals(1)
                && argument(first, 1).equals(bricks.getX()), "第一下应回「已标记 #1」和坐标，实际 " + first);
        context.assertTrue(marks.list().getFirst().equals(new Marks.Mark(bricks.getX(), bricks.getY(), bricks.getZ(),
                "minecraft:overworld", "minecraft:stone_bricks", "up", Marks.Kind.BLOCK)), "记下的标记不对：" + marks.list());

        Component again = PointerItem.mark(marks, player, bricks, Direction.NORTH, Marks.Kind.BLOCK);
        context.assertTrue(key(again).equals("kizuna_agent.pointer.already") && argument(again, 0).equals(1) && marks.list().size() == 1,
                "同一格再点应说它已经是 #1，不重复记，实际 " + again);

        Component far = PointerItem.mark(marks, player, stairs, Direction.WEST, Marks.Kind.DISTANT);
        context.assertTrue(key(far).equals("kizuna_agent.pointer.marked_distant") && argument(far, 0).equals(2), "远处指的应是 #2，实际 " + far);
        context.assertTrue(marks.list().get(1).state().startsWith("minecraft:oak_stairs[facing=") && marks.list().get(1).face().equals("west"),
                "带属性的方块应连属性一起记，实际 " + marks.list().get(1));

        Component feet = PointerItem.mark(marks, player, player.blockPosition(), null, Marks.Kind.FEET);
        context.assertTrue(key(feet).equals("kizuna_agent.pointer.marked_feet") && marks.list().get(2).face().isEmpty()
                && marks.list().get(2).kind() == Marks.Kind.FEET, "标脚下应是 #3、不带面，实际 " + feet);

        for (int index = marks.list().size(); index < Marks.MAX; index++) {
            PointerItem.mark(marks, player, bricks.above(index + 2), Direction.UP, Marks.Kind.BLOCK);
        }
        Component full = PointerItem.mark(marks, player, bricks.above(Marks.MAX + 5), Direction.UP, Marks.Kind.BLOCK);
        context.assertTrue(key(full).equals("kizuna_agent.pointer.full") && marks.list().size() == Marks.MAX, "标满以后应说满了，实际 " + full);

        Component cleared = PointerItem.clear(marks);
        context.assertTrue(key(cleared).equals("kizuna_agent.pointer.cleared") && argument(cleared, 0).equals(Marks.MAX)
                && marks.list().isEmpty(), "清空应说清掉了几个，实际 " + cleared);
        context.assertTrue(key(PointerItem.clear(marks)).equals("kizuna_agent.pointer.nothing"), "没有标记时清空应说现在没有标记");
        context.succeed();
    }

    @GameTest
    public void pointerIsARegisteredItemThatDoesNothingForNonOwners(GameTestHelper context) {
        // 物品注册上了、创造栏里有；不是单人世界主人的玩家拿着右键，什么都不记。
        ServerPlayer player = context.makeMockServerPlayerInLevel();
        Item pointer = AgentItems.pointer();
        context.assertTrue(BuiltInRegistries.ITEM.getKey(pointer).equals(AgentItems.POINTER_ID), "指点杖应注册成 kizuna_agent:pointer");
        context.assertTrue(BuiltInRegistries.CREATIVE_MODE_TAB.containsKey(Identifier.fromNamespaceAndPath(AgentItems.NAMESPACE, "main")),
                "应有自己的创造栏");
        int before = AgentMod.host().map(host -> host.marks().version()).orElse(0);
        player.setItemInHand(InteractionHand.MAIN_HAND, new ItemStack(pointer));
        context.assertTrue(pointer.use(context.getLevel(), player, InteractionHand.MAIN_HAND) == InteractionResult.CONSUME,
                "服务端上右键应算处理过了");
        context.assertTrue(AgentMod.host().map(host -> host.marks().version()).orElse(0) == before, "不是世界主人不该记下标记");
        context.succeed();
    }

    private static String key(Component message) {
        return message.getContents() instanceof TranslatableContents translated ? translated.getKey() : "";
    }

    private static Object argument(Component message, int index) {
        return ((TranslatableContents) message.getContents()).getArgs()[index];
    }

    @FunctionalInterface
    private interface HostScript {
        void run(Client client, BridgeHost host);
    }

    private static void run(GameTestHelper context, ServerPlayer player, boolean owner, HostScript script) {
        BridgeHost[] opened = new BridgeHost[1];
        run(context, player, owner, TICK_MILLIS, opened, client -> script.run(client, opened[0]));
    }

    private static void run(GameTestHelper context, ServerPlayer player, boolean owner, Script script) {
        run(context, player, owner, TICK_MILLIS, script);
    }

    private static void run(GameTestHelper context, ServerPlayer player, boolean owner, int tickMillis, Script script) {
        run(context, player, owner, tickMillis, new BridgeHost[1], script);
    }

    private static void run(GameTestHelper context, ServerPlayer player, boolean owner, int tickMillis, BridgeHost[] opened,
                            Script script) {
        // 起一套桥接，脚本在别的线程上跑完后关掉；用例每 tick 看结果：空串是通过，别的是失败原因。
        BridgeHost host;
        try {
            host = BridgeHost.open(context.getLevel().getServer(), () -> Optional.of(player), candidate -> owner, 0,
                    tickMillis, null, Files.createTempDirectory("kizuna-agent-home"));
        } catch (IOException failed) {
            throw new IllegalStateException(failed);
        }
        opened[0] = host;
        CompletableFuture<String> outcome = CompletableFuture.supplyAsync(() -> {
            try {
                script.run(new Client(host));
                return "";
            } catch (AssertionError | RuntimeException failed) {
                return String.valueOf(failed.getMessage() == null ? failed : failed.getMessage());
            } finally {
                host.close();
            }
        });
        context.succeedWhen(() -> {
            context.assertTrue(outcome.isDone(), "接口调用还没做完");
            context.assertTrue(outcome.join().isEmpty(), outcome.join());
        });
    }

    private static void check(boolean condition, String message) {
        if (!condition) {
            throw new AssertionError(message);
        }
    }

    private static String array(BlockPos position) {
        return "[%d, %d, %d]".formatted(position.getX(), position.getY(), position.getZ());
    }
}
