package dev.kizuna.agent.pi;

import com.google.gson.Gson;
import com.google.gson.JsonObject;
import dev.kizuna.agent.config.AgentConfig;
import dev.kizuna.agent.config.ConfigEditor;
import dev.kizuna.agent.config.ModelChoice;
import dev.kizuna.agent.config.ThinkingLevels;

import java.io.File;
import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.function.Consumer;
import java.util.regex.Pattern;

/**
 * 一次进世界期间和助手的对话：按需拉起 pi，转交玩家的话，把 pi 的事件变成一行行输出。
 *
 * <p>启动（找 Node、解运行文件）和发指令都在自己的一条工作线程上按先后做，不占游戏线程。
 * 输出回调可能在任何线程上被调，由调用方换到自己的线程。</p>
 *
 * <p>主模型报 429 或额度用尽时，趁 pi 等着重试的那两秒把模型换成备用的，重试就落在备用模型上；之后这次进世界期间一直用备用模型。</p>
 *
 * <p>换模型：登记在 pi 里的（清单里的、主模型、备用模型）让跑着的 pi 直接换，对话不断；别的模型 ID 要重启 pi。
 * 思考档位跟着模型走：每次换完模型都把这个模型的档位再发一遍；开新对话后模型和档位也都再发一遍。
 * 这些 pi 都是异步做的，所以下一句话要等它应答了再发。</p>
 */
public final class AgentSession implements AutoCloseable {
    /** 桥接在哪儿、令牌是什么。 */
    public record Connection(String url, String token) {
    }

    private static final Pattern OUT_OF_QUOTA = Pattern.compile(
            "\\b429\\b|resource.?exhausted|quota|rate.?limit|insufficient", Pattern.CASE_INSENSITIVE);
    private static final Gson GSON = new Gson();
    private static final String FIXED_BY_NAME = "这个模型的思考档位由模型名决定";
    /** 让 pi 换模型后最多等它这么久。 */
    private static final int SWITCH_SECONDS = 5;

    private final Path data;
    private final Path configFile;
    private final AgentConfig config;
    private final Connection connection;
    private final Consumer<Transcript.Line> output;
    private final ExecutorService worker = Executors.newSingleThreadExecutor(task -> {
        var thread = new Thread(task, "kizuna-agent-session");
        thread.setDaemon(true);
        return thread;
    });

    private volatile PiProcess process;
    /** 第几次拉起 pi。已经关掉的那个 pi 晚到的事件和退出通知靠它认出来、不理。 */
    private volatile int generation;
    /** 这次拉起 pi 时登记进去的各个接口和模型。 */
    private volatile List<PiHome.Endpoint> endpoints = List.of();
    private volatile ModelChoice model;
    /** 这次进世界期间在游戏里改过的思考档位，按 {@link #slot} 记。 */
    private final Map<String, String> chosen = new ConcurrentHashMap<>();
    /** 让 pi 换模型或开新对话后，它应答了、模型和档位都补发过去了才算完；没在换的时候是已完成的。 */
    private volatile CompletableFuture<Void> switching = CompletableFuture.completedFuture(null);
    /** 开了新对话以后还没说过话：这时候重启 pi 不接上一次的对话。只在工作线程上碰。 */
    private boolean fresh;
    /** pi 报的：模型在答话或在跑工具。 */
    private volatile boolean busy;
    /** 玩家的话已经交出去、这一轮还没了结（含启动 pi 的那段时间）。 */
    private volatile boolean asked;
    private volatile boolean quitting;

    /**
     * @param configFile 配置文件；游戏里改的思考档位存回这里
     */
    public AgentSession(Path data, Path configFile, AgentConfig config, Connection connection, Consumer<Transcript.Line> output) {
        this.data = data;
        this.configFile = configFile;
        this.config = config;
        this.connection = connection;
        this.output = output;
        this.model = config.primary();
    }

    /** 现在这个模型能用的思考档位，从少到多；思考档位由模型名决定的模型为空。 */
    public List<String> levels() {
        return List.copyOf(model.levels().keySet());
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
                settle();
                // 排在前面的换模型可能刚重启过 pi、把「在忙」清掉了，这里重新记上。
                asked = true;
                var prompt = new JsonObject();
                prompt.addProperty("type", "prompt");
                prompt.addProperty("message", text);
                if (busy) {
                    prompt.addProperty("streamingBehavior", "steer");
                }
                process.send(prompt);
                fresh = false;
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
            fresh = true;
            if (running()) {
                // 前一次换模型 pi 还没应答就再发，两件事在 pi 里会抢先后。
                settle();
                switching = new CompletableFuture<>();
                command("new_session");
            }
            say(Transcript.Kind.NOTE, "已开新对话");
        });
    }

    /** 换模型：下一句话开始用。{@code name} 是清单里的别名，或直接是一个模型 ID。 */
    public void model(String name) {
        worker.execute(() -> {
            ModelChoice next = config.choice(name);
            if (next.baseUrl().isBlank() || next.apiKey().isBlank()) {
                say(Transcript.Kind.ERROR, "模型 " + next.label() + " 没有接口地址或密钥，换不了");
                return;
            }
            use(next);
            say(Transcript.Kind.NOTE, "已换成模型 " + next.label()
                    + (next.listed() ? "" : "（不在模型清单里，按模型 ID 直接用 [model] 的接口）")
                    + "，" + (next.adjustable() ? "思考档位 " + level(next) : FIXED_BY_NAME) + "；下一句开始生效");
        });
    }

    /** 调现在这个模型的思考档位：下一句话开始用，并存回配置文件。 */
    public void thinking(String level) {
        worker.execute(() -> {
            ModelChoice current = model;
            if (!current.adjustable()) {
                say(Transcript.Kind.NOTE, current.label() + "：" + FIXED_BY_NAME + "，调不了");
                return;
            }
            if (!current.levels().containsKey(level)) {
                say(Transcript.Kind.ERROR, current.label() + " 没有 " + level + " 这一档，能用的是 " + String.join("、", current.levels().keySet()));
                return;
            }
            chosen.put(slot(current), level);
            sendThinking();
            try {
                ConfigEditor.saveThinking(configFile, current, level);
                say(Transcript.Kind.NOTE, current.label() + " 的思考档位已改成 " + level + "，下一句开始生效，已存进配置");
            } catch (IOException | IllegalArgumentException failed) {
                say(Transcript.Kind.NOTE, current.label() + " 的思考档位已改成 " + level + "，下一句开始生效");
                say(Transcript.Kind.ERROR, "没能存进配置，只在这次进世界期间有效：" + failed.getMessage());
            }
        });
    }

    /** 说一下现在用的模型和能换的模型。 */
    public void showModel() {
        worker.execute(() -> {
            say(Transcript.Kind.NOTE, modelLine());
            say(Transcript.Kind.NOTE, listLine());
        });
    }

    /** 说一下现在的思考档位和能调的档位。 */
    public void showThinking() {
        worker.execute(() -> say(Transcript.Kind.NOTE, thinkingLine()));
    }

    /** 现在各样东西的状态，每项一行；查完经输出回调给出。 */
    public void status() {
        worker.execute(() -> {
            var lines = new ArrayList<String>();
            lines.add(modelLine());
            lines.add(thinkingLine());
            if (!config.models().isEmpty()) {
                lines.add(listLine());
            }
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

    private String modelLine() {
        ModelChoice current = model;
        return "模型：" + current.label() + "，接口 " + current.baseUrl()
                + config.fallback().map(fallback -> "；备用 " + fallback.label()).orElse("");
    }

    private String thinkingLine() {
        ModelChoice current = model;
        if (!current.adjustable()) {
            return "思考档位：" + FIXED_BY_NAME;
        }
        return "思考档位：" + level(current) + "（能用 " + String.join("、", current.levels().keySet()) + "；/agent thinking <档> 调）";
    }

    private String listLine() {
        return config.models().isEmpty() ? "模型清单：配置里没写 [[models]]，/agent model 后面直接跟模型 ID"
                : "模型清单：" + String.join("、", config.models().stream().map(ModelChoice::alias).toList()) + "（/agent model <别名> 换）";
    }

    /** 这个模型现在的思考档位：游戏里改过就用改的，否则用配置里的。 */
    private String level(ModelChoice choice) {
        return ThinkingLevels.fit(chosen.getOrDefault(slot(choice), choice.thinking()), choice.levels().keySet());
    }

    /** 清单里的模型各记各的档位；不在清单里的共用 {@code [model]} 的那一档。 */
    private static String slot(ModelChoice choice) {
        return choice.listed() ? choice.alias() : "";
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
        PiRuntime.Layout layout = PiRuntime.prepare(data, config.tools().localCode(), PiHome.shell(Executables.windows()));
        ModelChoice current = model;
        String thinking = level(current);
        endpoints = PiHome.endpoints(config, current);
        PiHome.write(layout.home(), config, endpoints, current, thinking, Executables.windows());
        // 技能和记忆不走 pi 自己的扫描（--no-skills、--no-context-files）：pi 只在启动时读一次，还会顺着工作目录往上
        // 把别处的 AGENTS.md 也读进来。这里由扩展每句话开头向游戏那头要现成的清单，接在系统提示词后面。
        var command = new ArrayList<>(node.command());
        command.addAll(List.of(layout.entry().toString(), "--session-dir", layout.sessions().toString(),
                "--no-extensions", "--extension", layout.extension().toString(), "--no-skills",
                "--no-prompt-templates", "--no-themes", "--no-context-files", "--offline",
                "--provider", PiHome.endpointOf(endpoints, current).orElseThrow().name(), "--model", current.id()));
        if (current.adjustable()) {
            // 接着上次对话时 pi 会沿用那次的档位；命令行给的优先。
            command.addAll(List.of("--thinking", thinking));
        }
        if (!fresh) {
            command.add("--continue");
        }
        busy = false;
        quitting = false;
        var transcript = new Transcript(output);
        int mine = ++generation;
        process = PiProcess.start(command, layout.workspace(), environment(layout),
                endpoints.stream().map(PiHome.Endpoint::key).toList(), event -> handle(mine, event, transcript),
                status -> exited(mine, status));
    }

    private HashMap<String, String> environment(PiRuntime.Layout layout) {
        var environment = new HashMap<String, String>();
        environment.put("PI_CODING_AGENT_DIR", layout.home().toString());
        environment.put("PI_OFFLINE", "1");
        environment.put("PI_TELEMETRY", "0");
        endpoints.forEach(endpoint -> environment.put(endpoint.keyVariable(), endpoint.key()));
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

    private void handle(int from, JsonObject event, Transcript transcript) {
        // 读线程上：记下忙闲，额度用尽时换备用模型，其余交给显示。
        if (from != generation) {
            return;
        }
        String type = Transcript.string(event, "type");
        if (type.equals("agent_start")) {
            busy = true;
        } else if (type.equals("agent_settled")) {
            busy = false;
            asked = false;
        } else if (type.equals("response")) {
            boolean accepted = event.get("success").getAsBoolean();
            if (!accepted) {
                asked = false;
            }
            String command = Transcript.string(event, "command");
            if (command.equals("new_session")) {
                // 新对话里 pi 用回它启动时的模型：把现在这个模型再发一遍，档位随后跟上。
                if (!accepted || !sendModel()) {
                    switching.complete(null);
                }
            } else if (command.equals("set_model")) {
                // pi 换模型时会把思考档位改回它自己的默认，等它换完再把这个模型的档位发过去。
                if (accepted) {
                    sendThinking();
                }
                switching.complete(null);
            }
        } else if (type.equals("auto_retry_start") && fallBack(Transcript.string(event, "errorMessage"))) {
            return;
        }
        transcript.accept(event);
    }

    private boolean fallBack(String error) {
        Optional<ModelChoice> fallback = config.fallback();
        if (fallback.isEmpty() || !OUT_OF_QUOTA.matcher(error).find()) {
            return false;
        }
        // 换模型排到工作线程上做（那里才能碰 process 和 model）；已经在用备用模型就照常重试。
        later(() -> {
            if (model.equals(fallback.get()) || !running()) {
                return;
            }
            ModelChoice previous = model;
            use(fallback.get());
            say(Transcript.Kind.NOTE, "模型 " + previous.label() + " 额度用尽（429），已换备用模型 " + fallback.get().label() + " 重试；这次进世界期间都用它");
        });
        return !model.equals(fallback.get());
    }

    /** 换到这个模型：登记过的让跑着的 pi 直接换；没登记的关掉 pi，下一句话时带着新的登记重新起。 */
    private void use(ModelChoice next) {
        model = next;
        if (!running()) {
            return;
        }
        if (PiHome.endpointOf(endpoints, next).isEmpty()) {
            shutdown();
            return;
        }
        // pi 还在开新对话（或还在换上一个模型）时先等它做完：它是并发处理指令的，抢在前头换的模型会落在旧对话上。
        settle();
        switching = new CompletableFuture<>();
        sendModel();
    }

    /** 让跑着的 pi 用现在这个模型；这个模型没登记在 pi 里（或 pi 没在跑）就不发，返回 false。读线程上也会调。 */
    private boolean sendModel() {
        ModelChoice current = model;
        Optional<PiHome.Endpoint> endpoint = PiHome.endpointOf(endpoints, current);
        if (!running() || endpoint.isEmpty()) {
            return false;
        }
        var command = new JsonObject();
        command.addProperty("type", "set_model");
        command.addProperty("provider", endpoint.get().name());
        command.addProperty("modelId", current.id());
        send(command);
        return true;
    }

    /** 刚让 pi 换了模型或开了新对话：等它做完、模型和档位都补发过去，再往下发话，免得这一句落在旧对话或 pi 默认的档位上。 */
    private void settle() {
        try {
            switching.get(SWITCH_SECONDS, TimeUnit.SECONDS);
        } catch (ExecutionException | TimeoutException late) {
            // 没等到也照常往下走，档位再发一遍。
            sendThinking();
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
        }
    }

    /** 把现在这个模型的思考档位发给跑着的 pi。读线程上也会调。 */
    private void sendThinking() {
        ModelChoice current = model;
        if (!running() || !current.adjustable()) {
            return;
        }
        var command = new JsonObject();
        command.addProperty("type", "set_thinking_level");
        command.addProperty("level", level(current));
        send(command);
    }

    /** 从读线程把事排到工作线程上；会话已经关了就不做。 */
    private void later(Runnable task) {
        try {
            worker.execute(task);
        } catch (RejectedExecutionException closed) {
            // 世界已经退出。
        }
    }

    private void exited(int from, int status) {
        if (from != generation) {
            return;
        }
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
            generation++;
            process.close();
            process = null;
        }
        switching.complete(null);
        busy = false;
        asked = false;
    }

    private void say(Transcript.Kind kind, String text) {
        output.accept(new Transcript.Line(kind, text));
    }
}
