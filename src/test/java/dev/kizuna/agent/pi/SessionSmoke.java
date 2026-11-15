package dev.kizuna.agent.pi;

import dev.kizuna.agent.config.AgentConfig;

import java.nio.file.Path;
import java.util.concurrent.TimeUnit;

/**
 * 不开游戏，单独把「找 Node 和 Python → 解出 pi → 拉起 → 问一句 → 收回答」走一遍，逐行打印聊天栏会显示的内容。
 *
 * <p>用来在别的系统上（比如 Windows）验证这条链路：只要一个 Java 25 和本仓的 JAR，不用装进游戏实例。
 * 用法：{@code java -cp <类路径> dev.kizuna.agent.pi.SessionSmoke <数据目录> <桥接地址> <桥接令牌> <一句话>}，
 * 模型接口取环境变量 {@code SMOKE_BASE_URL}、{@code SMOKE_API_KEY}、{@code SMOKE_MODEL}、{@code SMOKE_FALLBACK}。</p>
 */
public final class SessionSmoke {
    private static final int WAIT_SECONDS = 240;
    private static final int POLL_MILLIS = 200;

    private SessionSmoke() {
        // 只有 main。
    }

    public static void main(String[] arguments) throws Exception {
        AgentConfig config = AgentConfig.parse("""
                [model]
                base_url = "%s"
                api_key = "%s"
                model = "%s"
                fallback_model = "%s"
                """.formatted(System.getenv("SMOKE_BASE_URL"), System.getenv("SMOKE_API_KEY"), System.getenv("SMOKE_MODEL"),
                System.getenv().getOrDefault("SMOKE_FALLBACK", "")));
        var session = new AgentSession(Path.of(arguments[0]), config, new AgentSession.Connection(arguments[1], arguments[2]),
                line -> System.out.println("[" + line.kind() + "] " + line.text()));
        session.status();
        session.ask(arguments[3]);
        // 问出去以后一直是「忙」，这一轮了结（或没起来）才变「闲」。
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(WAIT_SECONDS);
        while (session.busy() && System.nanoTime() < deadline) {
            Thread.sleep(POLL_MILLIS);
        }
        boolean finished = !session.busy();
        session.close();
        System.out.println(finished ? "SMOKE DONE" : "SMOKE TIMEOUT");
        System.exit(finished ? 0 : 1);
    }
}
