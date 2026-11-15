package dev.kizuna.agent.config;

import org.tomlj.Toml;
import org.tomlj.TomlParseResult;
import org.tomlj.TomlTable;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/**
 * 助手的全部设置，对应实例里的 {@code config/kizuna-agent.toml}。
 *
 * <p>文件不存在时写出带注释的默认文件。旧文件缺了新加的键就用默认值，缺了哪些记在 {@link #missing} 里由调用方说一声；
 * 写错的值（类型不对、不认识的选项）直接报错，不悄悄改成默认。</p>
 */
public record AgentConfig(Model model, Runtime runtime, Tools tools, Bridge bridge, List<String> missing) {
    /** 模型接口。密钥只在这里；{@link #toString} 不带它。 */
    public record Model(String api, String baseUrl, String apiKey, String model, String fallbackModel, String thinking,
                        int contextWindow) {
        public boolean ready() {
            return !baseUrl.isBlank() && !apiKey.isBlank() && !model.isBlank();
        }

        @Override
        public String toString() {
            return "Model[api=" + api + ", baseUrl=" + baseUrl + ", apiKey=" + (apiKey.isBlank() ? "未填" : "已填")
                    + ", model=" + model + ", fallbackModel=" + fallbackModel + ", thinking=" + thinking + "]";
        }
    }

    /** 本机运行时的位置；空串表示自动找。 */
    public record Runtime(String node, String python) {
    }

    public record Tools(boolean localCode) {
    }

    public record Bridge(int port, int blocksPerTickMillis) {
    }

    public static final String FILE_NAME = "kizuna-agent.toml";
    static final String DEFAULT_RESOURCE = "/kizuna-agent/default-config.toml";
    private static final Set<String> APIS = Set.of("openai-completions", "openai-responses", "anthropic-messages",
            "google-generative-ai");
    private static final Set<String> THINKING = Set.of("off", "minimal", "low", "medium", "high", "xhigh", "max");
    private static final int MAX_PORT = 65535;
    private static final int MAX_TICK_MILLIS = 45;

    /** 读配置文件；没有就先写出默认文件。 */
    public static AgentConfig load(Path file) {
        try {
            if (Files.notExists(file)) {
                Files.createDirectories(file.getParent());
                Files.writeString(file, defaults());
            }
            return parse(Files.readString(file));
        } catch (IOException failed) {
            throw new UncheckedIOException(failed);
        }
    }

    static String defaults() {
        try (InputStream input = AgentConfig.class.getResourceAsStream(DEFAULT_RESOURCE)) {
            if (input == null) {
                throw new IllegalStateException("JAR 里缺少默认配置 " + DEFAULT_RESOURCE);
            }
            return new String(input.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException failed) {
            throw new UncheckedIOException(failed);
        }
    }

    /** 解析配置文件的全文。 */
    public static AgentConfig parse(String text) {
        TomlParseResult given = Toml.parse(text);
        if (given.hasErrors()) {
            throw new IllegalArgumentException(FILE_NAME + " 写法有错：" + given.errors().getFirst());
        }
        var reader = new Reader(given, Toml.parse(defaults()));
        var model = new Model(reader.choice("model.api", APIS), reader.text("model.base_url").strip(),
                reader.text("model.api_key").strip(), reader.text("model.model").strip(),
                reader.text("model.fallback_model").strip(), reader.choice("model.thinking", THINKING),
                reader.integer("model.context_window", 1000, Integer.MAX_VALUE));
        var runtime = new Runtime(reader.text("runtime.node").strip(), reader.text("runtime.python").strip());
        var tools = new Tools(reader.flag("tools.local_code"));
        var bridge = new Bridge(reader.integer("bridge.port", 0, MAX_PORT),
                reader.integer("bridge.blocks_per_tick_millis", 1, MAX_TICK_MILLIS));
        return new AgentConfig(model, runtime, tools, bridge, List.copyOf(reader.missing));
    }

    /** 按「表.键」取值：文件里没有就取默认文件里的，并记下缺了哪个键。 */
    private static final class Reader {
        private final TomlTable given;
        private final TomlTable fallback;
        private final List<String> missing = new ArrayList<>();

        private Reader(TomlTable given, TomlTable fallback) {
            this.given = given;
            this.fallback = fallback;
        }

        private TomlTable source(String key) {
            if (given.contains(key)) {
                return given;
            }
            missing.add(key);
            return fallback;
        }

        private String text(String key) {
            TomlTable source = source(key);
            if (!source.isString(key)) {
                throw new IllegalArgumentException(FILE_NAME + " 里 " + key + " 要是带引号的文字");
            }
            return source.getString(key);
        }

        private String choice(String key, Set<String> allowed) {
            String value = text(key).strip();
            if (!allowed.contains(value)) {
                throw new IllegalArgumentException(FILE_NAME + " 里 " + key + " 只能是 " + String.join("、", allowed.stream().sorted().toList()) + "，现在是 " + value);
            }
            return value;
        }

        private boolean flag(String key) {
            TomlTable source = source(key);
            if (!source.isBoolean(key)) {
                throw new IllegalArgumentException(FILE_NAME + " 里 " + key + " 要是 true 或 false");
            }
            return source.getBoolean(key);
        }

        private int integer(String key, int minimum, int maximum) {
            TomlTable source = source(key);
            if (!source.isLong(key)) {
                throw new IllegalArgumentException(FILE_NAME + " 里 " + key + " 要是整数");
            }
            long value = source.getLong(key);
            if (value < minimum || value > maximum) {
                throw new IllegalArgumentException(FILE_NAME + " 里 " + key + " 要在 " + minimum + " 到 " + maximum + " 之间，现在是 " + value);
            }
            return (int) value;
        }
    }
}
