package dev.kizuna.agent.clienttest;

import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 一个照剧本答话的假模型接口（OpenAI Chat Completions 的流式写法），让验收不依赖真模型也能把整条链路走一遍：
 * 真的 pi、真的扩展、真的桥接，只有模型的回答是写死的。
 *
 * <p>主模型一律回 429（额度用尽），逼助手换备用模型。备用模型按「已经收到几个工具结果」一步步来：
 * 看玩家位置 → 在玩家东边盖一个空心石砖壳 → 搜 JAR → 跑 Python 在壳顶放一块萤石 → 说结果。</p>
 *
 * <p>玩家的话里带「{@value #ROLL_CALL}」时不走剧本，哪个模型都直接答一句「到」并报这是第几次，
 * 用来查换模型、调思考档位以后请求里的模型名和 {@code reasoning_effort}。</p>
 */
final class MockModel implements AutoCloseable {
    static final String PRIMARY = "primary-model";
    static final String BACKUP = "backup-model";
    /** 配置里哪儿都没写的模型 ID。 */
    static final String UNLISTED = "unlisted-model";
    static final String DEEP = "deep-model";
    static final String FIXED = "fixed-model";
    static final String ROLL_CALL = "报个到";
    /** 请求里没带 {@code reasoning_effort} 时记成这个。 */
    static final String NOT_SENT = "没发";
    static final String KEY = "mock-key-not-a-secret";
    static final String DONE = "盖好了：石砖小屋在你东边，屋顶有一块萤石。";
    private static final Gson GSON = new Gson();
    private static final Pattern BLOCK = Pattern.compile("\\\\?\"block\\\\?\":\\s*\\[(-?\\d+),\\s*(-?\\d+),\\s*(-?\\d+)]");

    private final HttpServer server;
    /** 收到的每个请求用的模型名，按先后。 */
    final List<String> requests = new CopyOnWriteArrayList<>();
    /** 收到的每个请求带的 {@code reasoning_effort}，和 {@link #requests} 一一对应；没带记成 {@link #NOT_SENT}。 */
    final List<String> efforts = new CopyOnWriteArrayList<>();
    /** 收到的每个请求里玩家最后说的那句话，和 {@link #requests} 一一对应。 */
    final List<String> asked = new CopyOnWriteArrayList<>();
    /** 收到的每个请求里一共带了几条消息，和 {@link #requests} 一一对应。 */
    final List<Integer> messages = new CopyOnWriteArrayList<>();
    private final AtomicInteger rollCalls = new AtomicInteger();
    /** 请求头里带的密钥不对时记在这里。 */
    final List<String> problems = new CopyOnWriteArrayList<>();

    MockModel() throws IOException {
        server = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 8);
        server.createContext("/v1/chat/completions", this::serve);
        server.start();
    }

    String baseUrl() {
        return "http://127.0.0.1:" + server.getAddress().getPort() + "/v1";
    }

    @Override
    public void close() {
        server.stop(0);
    }

    private void serve(HttpExchange exchange) throws IOException {
        try (exchange) {
            JsonObject request = JsonParser.parseString(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8)).getAsJsonObject();
            String model = request.get("model").getAsString();
            String said = lastUserText(request.getAsJsonArray("messages"));
            efforts.add(request.has("reasoning_effort") ? request.get("reasoning_effort").getAsString() : NOT_SENT);
            asked.add(said);
            messages.add(request.getAsJsonArray("messages").size());
            requests.add(model);
            if (!("Bearer " + KEY).equals(exchange.getRequestHeaders().getFirst("Authorization"))) {
                problems.add("密钥没带对：" + exchange.getRequestHeaders().getFirst("Authorization"));
            }
            if (model.equals(PRIMARY)) {
                byte[] body = "{\"error\":{\"code\":429,\"message\":\"Resource has been exhausted (e.g. check quota).\",\"status\":\"RESOURCE_EXHAUSTED\"}}".getBytes(StandardCharsets.UTF_8);
                exchange.getResponseHeaders().set("Content-Type", "application/json");
                exchange.sendResponseHeaders(429, body.length);
                exchange.getResponseBody().write(body);
                return;
            }
            exchange.getResponseHeaders().set("Content-Type", "text/event-stream");
            exchange.sendResponseHeaders(200, 0);
            OutputStream output = exchange.getResponseBody();
            JsonObject step = said.contains(ROLL_CALL) ? text(rollCall(rollCalls.incrementAndGet()))
                    : step(request.getAsJsonArray("messages"));
            boolean tool = step.has("tool_calls");
            chunk(output, model, step, null);
            chunk(output, model, new JsonObject(), tool ? "tool_calls" : "stop");
            output.write("data: [DONE]\n\n".getBytes(StandardCharsets.UTF_8));
            output.flush();
        } catch (RuntimeException failed) {
            problems.add("假模型出错：" + failed);
        }
    }

    /** 第几次点名时假模型答的话。 */
    static String rollCall(int count) {
        return "到，第 " + count + " 次";
    }

    private static String lastUserText(JsonArray messages) {
        // 内容可能是一段文字，也可能是分块的数组；只用来找关键词，整个转成文字就行。
        String said = "";
        for (JsonElement element : messages) {
            JsonObject message = element.getAsJsonObject();
            if (message.get("role").getAsString().equals("user")) {
                said = message.get("content").toString();
            }
        }
        return said;
    }

    private static JsonObject step(JsonArray messages) {
        // 数一数这一轮（最后一条玩家的话之后）已经回来几个工具结果，决定下一步。
        int results = 0;
        String player = "";
        for (JsonElement element : messages) {
            JsonObject message = element.getAsJsonObject();
            String role = message.get("role").getAsString();
            if (role.equals("user")) {
                results = 0;
            } else if (role.equals("tool")) {
                if (results == 0) {
                    player = message.get("content").toString();
                }
                results++;
            }
        }
        Matcher block = BLOCK.matcher(player);
        int x = 0;
        int y = 0;
        int z = 0;
        if (block.find()) {
            x = Integer.parseInt(block.group(1));
            y = Integer.parseInt(block.group(2));
            z = Integer.parseInt(block.group(3));
        }
        return switch (results) {
            case 0 -> call("mc_player", "{}");
            case 1 -> call("mc_fill", "{\"from\":[%d,%d,%d],\"to\":[%d,%d,%d],\"block\":\"minecraft:stone_bricks\",\"hollow\":true}"
                    .formatted(x + 3, y, z - 1, x + 5, y + 2, z + 1));
            case 2 -> call("mc_jar_search", "{\"text\":\"Kizuna Agent\",\"mod\":\"kizuna_agent\",\"glob\":\"*.json\"}");
            case 3 -> call("run_python", GSON.toJson(code(x + 4, y + 3, z)));
            default -> text(DONE);
        };
    }

    private static JsonObject code(int x, int y, int z) {
        var arguments = new JsonObject();
        arguments.addProperty("code", """
                from kizuna_mc import mc
                result = mc.set_block((%d, %d, %d), "minecraft:glowstone")
                print("changed", result.changed, mc.get_block((%d, %d, %d)))
                """.formatted(x, y, z, x, y, z));
        return arguments;
    }

    private static JsonObject call(String name, String arguments) {
        var function = new JsonObject();
        function.addProperty("name", name);
        function.addProperty("arguments", arguments);
        var call = new JsonObject();
        call.addProperty("index", 0);
        call.addProperty("id", "call_" + name);
        call.addProperty("type", "function");
        call.add("function", function);
        var calls = new JsonArray();
        calls.add(call);
        var delta = new JsonObject();
        delta.addProperty("role", "assistant");
        delta.add("tool_calls", calls);
        return delta;
    }

    private static JsonObject text(String content) {
        var delta = new JsonObject();
        delta.addProperty("role", "assistant");
        delta.addProperty("content", content);
        return delta;
    }

    private static void chunk(OutputStream output, String model, JsonObject delta, String finish) throws IOException {
        var choice = new JsonObject();
        choice.addProperty("index", 0);
        choice.add("delta", delta);
        if (finish == null) {
            choice.add("finish_reason", com.google.gson.JsonNull.INSTANCE);
        } else {
            choice.addProperty("finish_reason", finish);
        }
        var choices = new JsonArray();
        choices.add(choice);
        var chunk = new JsonObject();
        chunk.addProperty("id", "chatcmpl-mock");
        chunk.addProperty("object", "chat.completion.chunk");
        chunk.addProperty("model", model);
        chunk.add("choices", choices);
        output.write(("data: " + GSON.toJson(chunk) + "\n\n").getBytes(StandardCharsets.UTF_8));
        output.flush();
    }
}
