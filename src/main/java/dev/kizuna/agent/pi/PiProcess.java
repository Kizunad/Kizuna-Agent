package dev.kizuna.agent.pi;

import com.google.gson.Gson;
import com.google.gson.JsonObject;
import com.google.gson.JsonParseException;
import com.google.gson.JsonParser;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.io.Reader;
import java.nio.CharBuffer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;
import java.util.function.IntConsumer;

/**
 * 一个跑着的 pi（RPC 模式）：往它的标准输入写指令，从标准输出读事件。
 *
 * <p>事件在读线程上交给回调，回调自己负责换线程。pi 写到标准错误的东西进游戏日志，其中出现的密钥先抹掉。</p>
 */
public final class PiProcess implements AutoCloseable {
    private static final Logger LOGGER = LoggerFactory.getLogger("kizuna-agent");
    private static final Gson GSON = new Gson();
    private static final int BUFFER = 8192;
    private static final int EXIT_SECONDS = 3;

    private final Process process;
    private final OutputStream input;

    private PiProcess(Process process) {
        this.process = process;
        this.input = process.getOutputStream();
    }

    /**
     * 拉起 pi。
     *
     * @param secret 不许出现在日志里的字符串（密钥）；为空串则不抹
     * @param onEvent 每条事件或应答
     * @param onExit pi 进程结束时调一次，参数是退出码
     */
    public static PiProcess start(List<String> command, Path directory, Map<String, String> environment, String secret,
                                  Consumer<JsonObject> onEvent, IntConsumer onExit) throws IOException {
        var builder = new ProcessBuilder(command).directory(directory.toFile());
        builder.environment().putAll(environment);
        var started = new PiProcess(builder.start());
        thread("kizuna-agent-pi-events", () -> started.readEvents(onEvent, onExit));
        thread("kizuna-agent-pi-errors", () -> started.readErrors(secret));
        return started;
    }

    /** 发一条指令；pi 已经退出时抛 IOException。 */
    public synchronized void send(JsonObject command) throws IOException {
        input.write((GSON.toJson(command) + "\n").getBytes(StandardCharsets.UTF_8));
        input.flush();
    }

    public boolean alive() {
        return process.isAlive();
    }

    @Override
    public void close() {
        // 连它起的子进程（Python、shell）一起结束；几秒内不走就强杀。
        process.descendants().forEach(ProcessHandle::destroy);
        process.destroy();
        try {
            if (!process.waitFor(EXIT_SECONDS, TimeUnit.SECONDS)) {
                process.descendants().forEach(ProcessHandle::destroyForcibly);
                process.destroyForcibly();
            }
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            process.destroyForcibly();
        }
    }

    private void readEvents(Consumer<JsonObject> onEvent, IntConsumer onExit) {
        var lines = new JsonLines();
        var buffer = CharBuffer.allocate(BUFFER);
        try (Reader reader = new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8)) {
            while (reader.read(buffer) >= 0) {
                buffer.flip();
                for (String line : lines.feed(buffer)) {
                    dispatch(line, onEvent);
                }
                buffer.clear();
            }
        } catch (IOException closed) {
            // 进程结束时管道关闭，照常走到下面报退出。
        }
        int status;
        try {
            status = process.waitFor();
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            status = -1;
        }
        onExit.accept(status);
    }

    private static void dispatch(String line, Consumer<JsonObject> onEvent) {
        try {
            var parsed = JsonParser.parseString(line);
            if (parsed.isJsonObject()) {
                onEvent.accept(parsed.getAsJsonObject());
            }
        } catch (JsonParseException notJson) {
            // pi 启动时偶尔往标准输出打一行普通文字（扩展加载失败之类），记进日志。
            LOGGER.warn("pi：{}", line);
        } catch (RuntimeException failed) {
            LOGGER.error("处理 pi 的事件出错", failed);
        }
    }

    private void readErrors(String secret) {
        try (var reader = new BufferedReader(new InputStreamReader(process.getErrorStream(), StandardCharsets.UTF_8))) {
            for (String line = reader.readLine(); line != null; line = reader.readLine()) {
                if (!line.isBlank()) {
                    LOGGER.warn("pi：{}", secret.isEmpty() ? line : line.replace(secret, "***"));
                }
            }
        } catch (IOException closed) {
            // 进程结束。
        }
    }

    private static void thread(String name, Runnable task) {
        var thread = new Thread(task, name);
        thread.setDaemon(true);
        thread.start();
    }
}
