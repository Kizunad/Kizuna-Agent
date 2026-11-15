package dev.kizuna.agent.bridge;

import com.google.gson.Gson;
import com.google.gson.JsonObject;
import com.google.gson.JsonParseException;
import com.google.gson.JsonParser;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * 桥接接口的 HTTP 外壳：只听本机回环地址，每个请求都要带令牌。
 *
 * <p>全部是 {@code POST /v1/<名字>}，请求体和应答都是 JSON。成功应答带 {@code "ok": true}；
 * 失败带 {@code "ok": false} 和 {@code error.code}、{@code error.message}。</p>
 */
public final class BridgeServer implements AutoCloseable {
    /** 一个接口：收请求体，回应答体。 */
    @FunctionalInterface
    public interface Call {
        JsonObject handle(JsonObject request);
    }

    static final String PREFIX = "/v1/";
    /** 请求体上限：一百万个方块的列表大约十几 MB。 */
    static final int MAX_BODY_BYTES = 32 * 1024 * 1024;
    private static final int WORKERS = 4;
    private static final Logger LOGGER = LoggerFactory.getLogger("kizuna-agent");
    private static final Gson GSON = new Gson();

    private final HttpServer server;
    private final ExecutorService workers;
    private final String token;
    private final Map<String, Call> calls;

    public BridgeServer(int port, String token, Map<String, Call> calls) throws IOException {
        this.token = token;
        this.calls = Map.copyOf(calls);
        this.server = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), port), 16);
        this.workers = Executors.newFixedThreadPool(WORKERS, task -> {
            Thread thread = new Thread(task, "kizuna-agent-bridge");
            thread.setDaemon(true);
            return thread;
        });
        server.setExecutor(workers);
        server.createContext("/", this::serve);
        server.start();
    }

    public int port() {
        return server.getAddress().getPort();
    }

    public String url() {
        return "http://127.0.0.1:" + port();
    }

    @Override
    public void close() {
        server.stop(0);
        workers.shutdownNow();
    }

    private void serve(HttpExchange exchange) throws IOException {
        // 任何出错都回一份 JSON，不让连接悬着；异常细节只进日志。
        try (exchange) {
            JsonObject answer;
            int status = 200;
            try {
                answer = answer(exchange);
                answer.addProperty("ok", true);
            } catch (BridgeException rejected) {
                status = rejected.status();
                answer = failure(rejected.code(), rejected.getMessage());
            } catch (RuntimeException failed) {
                LOGGER.error("桥接接口 {} 出错", exchange.getRequestURI().getPath(), failed);
                status = 500;
                answer = failure("internal", "接口内部出错：" + failed);
            }
            byte[] body = GSON.toJson(answer).getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type", "application/json; charset=utf-8");
            exchange.sendResponseHeaders(status, body.length);
            exchange.getResponseBody().write(body);
        }
    }

    private JsonObject answer(HttpExchange exchange) throws IOException {
        // 先验来路和令牌，再看接口名；验不过的请求不读请求体。
        if (exchange.getRequestHeaders().containsKey("Origin")) {
            throw BridgeException.forbidden("不接受网页发来的请求");
        }
        if (!authorized(exchange.getRequestHeaders().getFirst("Authorization"), token)) {
            throw new BridgeException(401, "unauthorized", "令牌不对");
        }
        if (!"POST".equals(exchange.getRequestMethod())) {
            throw new BridgeException(405, "method", "只接受 POST");
        }
        String path = exchange.getRequestURI().getPath();
        Call call = path.startsWith(PREFIX) ? calls.get(path.substring(PREFIX.length())) : null;
        if (call == null) {
            throw new BridgeException(404, "unknown", "没有这个接口：" + path + "。有：" + String.join("、", calls.keySet().stream().sorted().toList()));
        }
        return call.handle(body(exchange.getRequestBody()));
    }

    static boolean authorized(String header, String token) {
        // 比较用定长时间，不因为前几位对上就多花时间。
        if (header == null || !header.startsWith("Bearer ")) {
            return false;
        }
        byte[] given = header.substring("Bearer ".length()).trim().getBytes(StandardCharsets.UTF_8);
        return MessageDigest.isEqual(given, token.getBytes(StandardCharsets.UTF_8));
    }

    static JsonObject body(InputStream input) throws IOException {
        // 空请求体当作空对象；超过上限直接拒，不往内存里读完。
        byte[] bytes = input.readNBytes(MAX_BODY_BYTES + 1);
        if (bytes.length > MAX_BODY_BYTES) {
            throw new BridgeException(413, "too_large", "请求体超过 " + MAX_BODY_BYTES / (1024 * 1024) + " MB，分几次发");
        }
        String text = new String(bytes, StandardCharsets.UTF_8).strip();
        if (text.isEmpty()) {
            return new JsonObject();
        }
        try {
            var parsed = JsonParser.parseString(text);
            if (!parsed.isJsonObject()) {
                throw BridgeException.badRequest("请求体要是一个 JSON 对象");
            }
            return parsed.getAsJsonObject();
        } catch (JsonParseException malformed) {
            throw BridgeException.badRequest("请求体不是合法的 JSON：" + malformed.getMessage());
        }
    }

    /** 把一段文字当请求体解析；测试和内部调用用。 */
    static JsonObject bodyOf(String text) {
        try {
            return body(new ByteArrayInputStream(text.getBytes(StandardCharsets.UTF_8)));
        } catch (IOException impossible) {
            throw new IllegalStateException(impossible);
        }
    }

    private static JsonObject failure(String code, String message) {
        var error = new JsonObject();
        error.addProperty("code", code);
        error.addProperty("message", message);
        var answer = new JsonObject();
        answer.addProperty("ok", false);
        answer.add("error", error);
        return answer;
    }
}
