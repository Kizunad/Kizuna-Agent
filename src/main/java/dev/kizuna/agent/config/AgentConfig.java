package dev.kizuna.agent.config;

import org.tomlj.Toml;
import org.tomlj.TomlArray;
import org.tomlj.TomlParseResult;
import org.tomlj.TomlTable;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * 助手的全部设置，对应实例里的 {@code config/kizuna-agent.toml}。
 *
 * <p>文件不存在时写出带注释的默认文件。旧文件缺了新加的键就用默认值，缺了哪些记在 {@link #missing} 里由调用方说一声；
 * 写错的值（类型不对、不认识的选项）直接报错，不悄悄改成默认。</p>
 *
 * @param models 模型清单（{@code [[models]]}），可以为空
 */
public record AgentConfig(Model model, List<ModelChoice> models, Runtime runtime, Tools tools, Bridge bridge,
                          List<String> missing) {
    /**
     * 默认的模型接口，和进世界时用哪个模型。密钥在这里和清单里自带密钥的项里；{@link #toString} 不带它。
     *
     * @param model 进世界时用的模型：清单里的别名，或直接写模型 ID
     * @param fallbackModel 备用模型，写法同上；空串表示不换
     * @param thinking 清单里没单独写思考档位的模型用这一档
     * @param vision 模型能看图；清单里没单独写的模型用这一项
     */
    public record Model(String api, String baseUrl, String apiKey, String model, String fallbackModel, String thinking,
                        int contextWindow, boolean vision) {

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
    private static final Set<String> THINKING = Set.copyOf(ThinkingLevels.ALL);
    private static final Set<String> ENTRY_KEYS = Set.of("alias", "id", "thinking", "thinking_levels", "api", "base_url",
            "api_key", "context_window", "vision");
    private static final int MIN_CONTEXT = 1000;
    private static final int MAX_PORT = 65535;
    private static final int MAX_TICK_MILLIS = 45;

    /**
     * 按名字找模型：先当清单里的别名，再当清单里的模型 ID；都不是就当成一个不在清单里的模型 ID，
     * 用 {@code [model]} 那份接口和默认的档位表。
     */
    public ModelChoice choice(String name) {
        for (ModelChoice listed : models) {
            if (listed.alias().equals(name)) {
                return listed;
            }
        }
        for (ModelChoice listed : models) {
            if (listed.id().equals(name)) {
                return listed;
            }
        }
        return new ModelChoice(name, name, model.api(), model.baseUrl(), model.apiKey(),
                ThinkingLevels.fit(model.thinking(), ThinkingLevels.STANDARD.keySet()), ThinkingLevels.STANDARD,
                model.contextWindow(), model.vision(), false);
    }

    /** 进世界时用的模型。 */
    public ModelChoice primary() {
        return choice(model.model());
    }

    /** 备用模型；没配就为空。 */
    public Optional<ModelChoice> fallback() {
        return model.fallbackModel().isBlank() ? Optional.empty() : Optional.of(choice(model.fallbackModel()));
    }

    /** 进世界时用的模型，地址、密钥、模型名都填了。 */
    public boolean ready() {
        ModelChoice primary = primary();
        return !primary.baseUrl().isBlank() && !primary.apiKey().isBlank() && !primary.id().isBlank();
    }

    /** {@code /agent model} 能补全出来的名字：清单里的别名在前，再是 {@code [model]} 里直接写了模型 ID 的主、备模型。 */
    public List<String> names() {
        var names = new LinkedHashSet<String>();
        models.forEach(listed -> names.add(listed.alias()));
        for (String direct : List.of(model.model(), model.fallbackModel())) {
            if (!direct.isBlank()) {
                names.add(choice(direct).alias());
            }
        }
        return List.copyOf(names);
    }

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
                reader.integer("model.context_window", MIN_CONTEXT, Integer.MAX_VALUE), reader.flag("model.vision"));
        var runtime = new Runtime(reader.text("runtime.node").strip(), reader.text("runtime.python").strip());
        var tools = new Tools(reader.flag("tools.local_code"));
        var bridge = new Bridge(reader.integer("bridge.port", 0, MAX_PORT),
                reader.integer("bridge.blocks_per_tick_millis", 1, MAX_TICK_MILLIS));
        return new AgentConfig(model, models(given, model), runtime, tools, bridge, List.copyOf(reader.missing));
    }

    private static List<ModelChoice> models(TomlTable given, Model defaults) {
        if (!given.contains("models")) {
            return List.of();
        }
        if (!given.isArray("models")) {
            throw new IllegalArgumentException(FILE_NAME + " 里模型清单要写成一段段的 [[models]]");
        }
        TomlArray array = given.getArray("models");
        var models = new ArrayList<ModelChoice>();
        for (int index = 0; index < array.size(); index++) {
            String where = FILE_NAME + " 里第 " + (index + 1) + " 个 [[models]] ";
            if (!(array.get(index) instanceof TomlTable entry)) {
                throw new IllegalArgumentException(where + "要是一段表");
            }
            ModelChoice added = new Entry(entry, where).read(defaults);
            for (ModelChoice earlier : models) {
                if (earlier.alias().equals(added.alias())) {
                    throw new IllegalArgumentException(where + "的别名 " + added.alias() + " 和前面的重了");
                }
                if (earlier.id().equals(added.id()) && earlier.sameEndpoint(added)) {
                    throw new IllegalArgumentException(where + "和 " + earlier.alias() + " 是同一个接口上的同一个模型 " + added.id());
                }
            }
            models.add(added);
        }
        return List.copyOf(models);
    }

    /** 模型清单里的一项；没写的接口地址、密钥、协议、上下文长度、思考档位取 {@code [model]} 的。 */
    private record Entry(TomlTable table, String where) {
        private ModelChoice read(Model defaults) {
            for (String key : table.keySet()) {
                if (!ENTRY_KEYS.contains(key)) {
                    throw new IllegalArgumentException(where + "里不认识 " + key + "，能写的是 "
                            + String.join("、", ENTRY_KEYS.stream().sorted().toList()));
                }
            }
            String id = text("id", "");
            if (id.isBlank()) {
                throw new IllegalArgumentException(where + "要写 id（发给接口的模型名）");
            }
            String alias = text("alias", id);
            if (alias.isBlank() || alias.chars().anyMatch(Character::isWhitespace)) {
                throw new IllegalArgumentException(where + "的别名不能为空、不能带空格，现在是「" + alias + "」");
            }
            String api = text("api", defaults.api());
            if (!APIS.contains(api)) {
                throw new IllegalArgumentException(where + "的 api 只能是 " + String.join("、", APIS.stream().sorted().toList()) + "，现在是 " + api);
            }
            Map<String, String> levels = levels();
            return new ModelChoice(alias, id, api, text("base_url", defaults.baseUrl()), text("api_key", defaults.apiKey()),
                    thinking(levels, defaults.thinking()), levels, contextWindow(defaults.contextWindow()),
                    vision(defaults.vision()), true);
        }

        private boolean vision(boolean otherwise) {
            if (!table.contains("vision")) {
                return otherwise;
            }
            if (!table.isBoolean("vision")) {
                throw new IllegalArgumentException(where + "的 vision 要是 true 或 false");
            }
            return table.getBoolean("vision");
        }

        private String text(String key, String otherwise) {
            if (!table.contains(key)) {
                return otherwise;
            }
            if (!table.isString(key)) {
                throw new IllegalArgumentException(where + "的 " + key + " 要是带引号的文字");
            }
            return table.getString(key).strip();
        }

        private int contextWindow(int otherwise) {
            if (!table.contains("context_window")) {
                return otherwise;
            }
            if (!table.isLong("context_window") || table.getLong("context_window") < MIN_CONTEXT
                    || table.getLong("context_window") > Integer.MAX_VALUE) {
                throw new IllegalArgumentException(where + "的 context_window 要是不小于 " + MIN_CONTEXT + " 的整数");
            }
            return (int) (long) table.getLong("context_window");
        }

        /** 档位表按从少到多排好；没写就是默认那张，写成空表表示思考档位由模型名决定。 */
        private Map<String, String> levels() {
            if (!table.contains("thinking_levels")) {
                return ThinkingLevels.STANDARD;
            }
            if (!table.isTable("thinking_levels")) {
                throw new IllegalArgumentException(where + "的 thinking_levels 要写成 { 档位 = \"发给接口的值\", … }");
            }
            TomlTable written = table.getTable("thinking_levels");
            for (String level : written.keySet()) {
                if (!THINKING.contains(level)) {
                    throw new IllegalArgumentException(where + "的 thinking_levels 里不认识档位 " + level + "，能写的是 "
                            + String.join("、", ThinkingLevels.ALL));
                }
            }
            var levels = new LinkedHashMap<String, String>();
            for (String level : ThinkingLevels.ALL) {
                if (!written.contains(level)) {
                    continue;
                }
                if (!written.isString(level)) {
                    throw new IllegalArgumentException(where + "的 thinking_levels." + level + " 要是带引号的文字");
                }
                String sent = written.getString(level).strip();
                if (sent.isEmpty() && !level.equals(ThinkingLevels.OFF)) {
                    throw new IllegalArgumentException(where + "的 thinking_levels." + level + " 不能为空：只有 off 可以写成空的（表示不发参数）");
                }
                levels.put(level, sent);
            }
            return Collections.unmodifiableMap(levels);
        }

        private String thinking(Map<String, String> levels, String otherwise) {
            if (levels.isEmpty()) {
                // 思考档位由模型名决定：这一项不起作用。
                return ThinkingLevels.OFF;
            }
            if (!table.contains("thinking")) {
                return ThinkingLevels.fit(otherwise, levels.keySet());
            }
            String thinking = text("thinking", otherwise);
            if (!levels.containsKey(thinking)) {
                throw new IllegalArgumentException(where + "的 thinking 是 " + thinking + "，这个模型能用的档位是 "
                        + String.join("、", levels.keySet()));
            }
            return thinking;
        }
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
