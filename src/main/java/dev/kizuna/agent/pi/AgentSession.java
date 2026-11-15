package dev.kizuna.agent.pi;

import com.google.gson.Gson;
import com.google.gson.JsonObject;
import dev.kizuna.agent.config.AgentConfig;

import java.io.File;
import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.function.Consumer;
import java.util.regex.Pattern;

/**
 * 一次进世界期间和助手的对话：按需拉起 pi，转交玩家的话，把 pi 的事件变成一行行输出。
 *
 * <p>启动（找 Node、解运行文件）和发指令都在自己的一条工作线程上按先后做，不占游戏线程。
 * 输出回调可能在任何线程上被调，由调用方换到自己的线程。</p>
 *
 * <p>主模型报 429 或额度用尽时，趁 pi 等着重试的那两秒把模型换成备用的，重试就落在备用模型上；之后这次进世界期间一直用备用模型。</p>
 */
public final class AgentSession implements AutoCloseable {
    /** 桥接在哪儿、令牌是什么。 */
    public record Connection(String url, String token) {
    }

    private static final Pattern OUT_OF_QUOTA = Pattern.compile(
            "\\b429\\b|resource.?exhausted|quota|rate.?limit|insufficient", Pattern.CASE_INSENSITIVE);
    private static final Gson GSON = new Gson();

    private final Path data;
    private final AgentConfig config;
    private final Connection connection;
    private final Consumer<Transcript.Line> output;
    private final ExecutorService worker = Executors.newSingleThreadExecutor(task -> {
        var thread = new Thread(task, "kizuna-agent-session");
        thread.setDaemon(true);
        return thread;
    });

    private PiProcess process;
    private volatile String model;
    private boolean fresh;
    /** pi 报的：模型在答话或在跑工具。 */
    private volatile boolean busy;
    /** 玩家的话已经交出去、这一轮还没了结（含启动 pi 的那段时间）。 */
    private volatile boolean asked;
    private volatile boolean quitting;

    public AgentSession(Path data, AgentConfig config, Connection connection, Consumer<Transcript.Line> output) {
        this.data = data;
        this.config = config;
        this.connection = connection;
        this.output = output;
        this.model = config.model().model();
    }

    /** 助手正在干活（模型在答话或在跑工具）。 */
    public boolean busy() {
        return busy || asked;
    }

    /** 把玩家的一句话交给助手；助手正在干活时这句话插到它下一步之前。 */
    public void ask(String text) {
        asked = true;
        worker.execute(() -> {
            try {
                start();
                var prompt = new JsonObject();
                prompt.addProperty("type", "prompt");
                prompt.addProperty("message", text);
                if (busy) {
                    prompt.addProperty("streamingBehavior", "steer");
                }
                process.send(prompt);
            } catch (Executables.Unavailable missing) {
                asked = false;
                say(Transcript.Kind.ERROR, missing.getMessage());
            } catch (IOException | RuntimeException failed) {
                asked = false;
                say(Transcript.Kind.ERROR, "助手没起来：" + failed.getMessage() + "。详情见游戏日志");
            }
        });
    }

    /** 让助手停下手上的事。 */
    public void stop() {
        worker.execute(() -> {
            if (!running() || !busy) {
                say(Transcript.Kind.NOTE, "助手现在没在干活");
                return;
            }
            command("abort");
        });
    }

    /** 开一段新对话，前面聊过的不再带着。 */
    public void reset() {
        worker.execute(() -> {
            if (running()) {
                command("new_session");
            } else {
                fresh = true;
            }
            say(Transcript.Kind.NOTE, "已开新对话");
        });
    }

    /** 换模型：下一句话开始用。 */
    public void model(String name) {
        worker.execute(() -> {
            model = name;
            shutdown();
            say(Transcript.Kind.NOTE, "已换成模型 " + name + "，下一句开始生效");
        });
    }

    /** 现在各样东西的状态，每项一行；查完经输出回调给出。 */
    public void status() {
        worker.execute(() -> {
            var lines = new ArrayList<String>();
            lines.add("模型：" + model + (config.model().fallbackModel().isBlank() ? "" : "（备用 " + config.model().fallbackModel() + "）")
                    + "，接口 " + config.model().baseUrl());
            lines.add(tool("Node.js", () -> Executables.node(config.runtime().node())));
            lines.add(config.tools().localCode() ? tool("Python", () -> Executables.python(config.runtime().python()))
                    : "动电脑的工具：已在配置里关掉，助手只能用游戏内工具");
            lines.add("pi " + PiRuntime.piVersion() + "：" + (running() ? (busy ? "正在干活" : "待命") : "还没启动，第一次 /agent 时启动"));
            lines.add("游戏内接口：" + connection.url());
            lines.forEach(line -> say(Transcript.Kind.NOTE, line));
        });
    }

    @Override
    public void close() {
        worker.execute(this::shutdown);
        worker.shutdown();
    }

    private interface Probe {
        Executables.Tool find() throws Executables.Unavailable;
    }

    private static String tool(String name, Probe probe) {
        try {
            Executables.Tool found = probe.find();
            return name + " " + found.version() + "：" + String.join(" ", found.command());
        } catch (Executables.Unavailable missing) {
            return name + "：" + missing.getMessage();
        }
    }

    private boolean running() {
        return process != null && process.alive();
    }

    private void start() throws Executables.Unavailable, IOException {
        if (running()) {
            return;
        }
        Executables.Tool node = Executables.node(config.runtime().node());
        say(Transcript.Kind.NOTE, "正在启动助手…");
        PiRuntime.Layout layout = PiRuntime.prepare(data);
        PiHome.write(layout.home(), config, model, Executables.windows());
        var command = new ArrayList<>(node.command());
        command.addAll(List.of(layout.entry().toString(), "--session-dir", layout.sessions().toString(),
                "--no-extensions", "--extension", layout.extension().toString(), "--no-skills",
                "--no-prompt-templates", "--no-themes", "--no-context-files", "--offline",
                "--provider", PiHome.PROVIDER, "--model", model));
        if (!fresh) {
            command.add("--continue");
        }
        fresh = false;
        busy = false;
        quitting = false;
        var transcript = new Transcript(output);
        process = PiProcess.start(command, layout.workspace(), environment(layout), config.model().apiKey(),
                event -> handle(event, transcript), this::exited);
    }

    private HashMap<String, String> environment(PiRuntime.Layout layout) {
        var environment = new HashMap<String, String>();
        environment.put("PI_CODING_AGENT_DIR", layout.home().toString());
        environment.put("PI_OFFLINE", "1");
        environment.put("PI_TELEMETRY", "0");
        environment.put(PiHome.KEY_VARIABLE, config.model().apiKey());
        environment.put("KIZUNA_MC_URL", connection.url());
        environment.put("KIZUNA_MC_TOKEN", connection.token());
        environment.put("KIZUNA_AGENT_LOCAL_CODE", config.tools().localCode() ? "1" : "0");
        environment.put("KIZUNA_MC_PYTHON", GSON.toJson(python()));
        // 脚本里 import kizuna_mc 直接能用；原有的 PYTHONPATH 接在后面。
        String inherited = System.getenv("PYTHONPATH");
        environment.put("PYTHONPATH", layout.python() + (inherited == null || inherited.isBlank() ? "" : File.pathSeparator + inherited));
        return environment;
    }

    private List<String> python() {
        // 没找到 Python 不拦着启动：用到 run_python 时那个工具自己报找不到。
        if (!config.tools().localCode()) {
            return List.of();
        }
        try {
            return Executables.python(config.runtime().python()).command();
        } catch (Executables.Unavailable missing) {
            return List.of();
        }
    }

    private void handle(JsonObject event, Transcript transcript) {
        // 读线程上：记下忙闲，额度用尽时换备用模型，其余交给显示。
        String type = Transcript.string(event, "type");
        if (type.equals("agent_start")) {
            busy = true;
        } else if (type.equals("agent_settled")) {
            busy = false;
            asked = false;
        } else if (type.equals("response") && !event.get("success").getAsBoolean()) {
            asked = false;
        } else if (type.equals("auto_retry_start") && fallBack(Transcript.string(event, "errorMessage"))) {
            return;
        }
        transcript.accept(event);
    }

    private boolean fallBack(String error) {
        String fallback = config.model().fallbackModel();
        if (fallback.isBlank() || !OUT_OF_QUOTA.matcher(error).find()) {
            return false;
        }
        // 换模型排到工作线程上做（那里才能碰 process 和 model）；已经在用备用模型就照常重试。
        worker.execute(() -> {
            if (model.equals(fallback) || !running()) {
                return;
            }
            String previous = model;
            model = fallback;
            var command = new JsonObject();
            command.addProperty("type", "set_model");
            command.addProperty("provider", PiHome.PROVIDER);
            command.addProperty("modelId", fallback);
            send(command);
            say(Transcript.Kind.NOTE, "主模型 " + previous + " 额度用尽（429），已换备用模型 " + fallback + " 重试；这次进世界期间都用它");
        });
        return !model.equals(fallback);
    }

    private void exited(int status) {
        busy = false;
        asked = false;
        if (!quitting && status != 0) {
            say(Transcript.Kind.ERROR, "助手进程退出了（退出码 " + status + "），下次 /agent 会重新启动。详情见游戏日志");
        }
    }

    private void command(String type) {
        var command = new JsonObject();
        command.addProperty("type", type);
        send(command);
    }

    private void send(JsonObject command) {
        try {
            process.send(command);
        } catch (IOException failed) {
            say(Transcript.Kind.ERROR, "发不到助手那边：" + failed.getMessage());
        }
    }

    private void shutdown() {
        // 自己关的进程退出不算出错。
        quitting = true;
        if (process != null) {
            process.close();
            process = null;
        }
        busy = false;
        asked = false;
    }

    private void say(Transcript.Kind kind, String text) {
        output.accept(new Transcript.Line(kind, text));
    }
}
