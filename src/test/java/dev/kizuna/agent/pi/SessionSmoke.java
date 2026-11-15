package dev.kizuna.agent.pi;

import dev.kizuna.agent.config.AgentConfig;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.TimeUnit;

/**
 * 不开游戏，单独把「找 Node 和 Python → 解出 pi → 拉起 → 问一句 → 收回答」走一遍，逐行打印聊天栏会显示的内容。
 *
 * <p>用来在别的系统上（比如 Windows）验证这条链路：只要一个 Java 25 和本仓的 JAR，不用装进游戏实例。
 * 用法：{@code java -cp <类路径> dev.kizuna.agent.pi.SessionSmoke <数据目录> <桥接地址> <桥接令牌> <一句话>}，
 * 模型接口取环境变量 {@code SMOKE_BASE_URL}、{@code SMOKE_API_KEY}、{@code SMOKE_MODEL}、{@code SMOKE_FALLBACK}。</p>
 *
 * <p>要试模型清单和思考档位：环境变量 {@code SMOKE_CONFIG} 给一份完整的配置文件（就不看上面四个了），
 * 一句话的位置可以写多步，用 {@code ;;} 隔开：{@code model <名字>}、{@code thinking <档>}、{@code new}、{@code status}，
 * 其余的当成话交给助手。每句话最多等 {@code SMOKE_WAIT} 秒（默认 240）。</p>
 */
public final class SessionSmoke {
    private static final int WAIT_SECONDS = Integer.parseInt(System.getenv().getOrDefault("SMOKE_WAIT", "240"));
    private static final int POLL_MILLIS = 200;
    private static final int SETTLE_MILLIS = 1500;

    private SessionSmoke() {
        // 只有 main。
    }

    public static void main(String[] arguments) throws Exception {
        Path data = Path.of(arguments[0]);
        // 会话要一份能写回的配置文件：给了就用给的，没给就照环境变量写一份最小的。
        Path file = data.resolve(AgentConfig.FILE_NAME);
        Files.createDirectories(data);
        String given = System.getenv("SMOKE_CONFIG");
        Files.writeString(file, given != null ? Files.readString(Path.of(given)) : """
                [model]
                base_url = "%s"
                api_key = "%s"
                model = "%s"
                fallback_model = "%s"
                """.formatted(System.getenv("SMOKE_BASE_URL"), System.getenv("SMOKE_API_KEY"), System.getenv("SMOKE_MODEL"),
                System.getenv().getOrDefault("SMOKE_FALLBACK", "")));
        var session = new AgentSession(data, file, AgentConfig.load(file), new AgentSession.Connection(arguments[1], arguments[2]),
                line -> System.out.println("[" + line.kind() + "] " + line.text()));
        boolean finished = true;
        for (String step : arguments[3].split(";;")) {
            String text = step.strip();
            System.out.println("> " + text);
            if (text.equals("status")) {
                session.status();
            } else if (text.equals("new")) {
                session.reset();
            } else if (text.startsWith("model ")) {
                session.model(text.substring("model ".length()).strip());
            } else if (text.startsWith("thinking ")) {
                session.thinking(text.substring("thinking ".length()).strip());
            } else {
                session.ask(text);
                // 问出去以后一直是「忙」，这一轮了结（或没起来）才变「闲」。
                long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(WAIT_SECONDS);
                while (session.busy() && System.nanoTime() < deadline) {
                    Thread.sleep(POLL_MILLIS);
                }
                finished &= !session.busy();
            }
        }
        // 最后一步要是换模型、调档位，等它在会话线程上做完、把话打出来。
        Thread.sleep(SETTLE_MILLIS);
        Files.deleteIfExists(file);
        session.close();
        System.out.println(finished ? "SMOKE DONE" : "SMOKE TIMEOUT");
        System.exit(finished ? 0 : 1);
    }
}
