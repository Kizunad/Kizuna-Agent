package dev.kizuna.agent.pi;

import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonNull;
import com.google.gson.JsonObject;
import dev.kizuna.agent.config.AgentConfig;
import dev.kizuna.agent.config.ModelChoice;
import dev.kizuna.agent.config.ThinkingLevels;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * 照助手的配置写出 pi 自己的配置目录（models.json、settings.json）。
 *
 * <p>这个目录在实例里，和玩家自己的 {@code ~/.pi/agent} 没有关系。密钥不写进这里：models.json 里只写环境变量名，
 * 密钥本身由拉起进程时的环境变量给。</p>
 *
 * <p>清单里的模型、主模型、备用模型全都登记，之后换到其中任何一个都不用重启 pi。
 * 每个模型的档位表原样写成 pi 的 {@code thinkingLevelMap}，所以每一档发给接口什么值由配置说了算。</p>
 */
public final class PiHome {
    public static final String PROVIDER = "kizuna";
    public static final String KEY_VARIABLE = "KIZUNA_AGENT_KEY";
    private static final int RETRIES = 3;
    private static final int RETRY_DELAY_MILLIS = 2000;

    /**
     * pi 里的一个 provider：同一个接口地址、协议、密钥上的各个模型。
     *
     * @param name pi 里的 provider 名
     * @param keyVariable 这个接口的密钥放在哪个环境变量里
     */
    public record Endpoint(String name, String keyVariable, List<ModelChoice> models) {
        /** 这个接口的密钥。 */
        public String key() {
            return models.getFirst().apiKey();
        }

        @Override
        public String toString() {
            return "Endpoint[name=" + name + ", models=" + models.stream().map(ModelChoice::alias).toList() + "]";
        }
    }

    private PiHome() {
        // 只有静态写文件的方法。
    }

    /**
     * 要登记进 pi 的全部模型，按接口分好组：清单里的、主模型、备用模型，再加上现在要用的这个（可能不在清单里）。
     * 第一个接口叫 {@link #PROVIDER}，密钥在 {@link #KEY_VARIABLE}；后面的依次带上序号。
     */
    public static List<Endpoint> endpoints(AgentConfig config, ModelChoice current) {
        var wanted = new ArrayList<>(config.models());
        wanted.add(config.primary());
        config.fallback().ifPresent(wanted::add);
        wanted.add(current);
        var groups = new ArrayList<List<ModelChoice>>();
        for (ModelChoice model : wanted) {
            List<ModelChoice> group = groups.stream().filter(members -> members.getFirst().sameEndpoint(model)).findFirst()
                    .orElseGet(() -> {
                        groups.add(new ArrayList<>());
                        return groups.getLast();
                    });
            if (group.stream().noneMatch(member -> member.id().equals(model.id()))) {
                group.add(model);
            }
        }
        var endpoints = new ArrayList<Endpoint>();
        for (List<ModelChoice> group : groups) {
            String suffix = endpoints.isEmpty() ? "" : String.valueOf(endpoints.size() + 1);
            endpoints.add(new Endpoint(PROVIDER + (suffix.isEmpty() ? "" : "-" + suffix),
                    KEY_VARIABLE + (suffix.isEmpty() ? "" : "_" + suffix), List.copyOf(group)));
        }
        return List.copyOf(endpoints);
    }

    /** 这个模型登记在哪个接口下；没登记就为空。 */
    public static Optional<Endpoint> endpointOf(List<Endpoint> endpoints, ModelChoice model) {
        return endpoints.stream().filter(endpoint -> endpoint.models().getFirst().sameEndpoint(model)
                && endpoint.models().stream().anyMatch(member -> member.id().equals(model.id()))).findFirst();
    }

    /**
     * @param current 启动时用的模型，必须在 {@code endpoints} 里
     * @param thinking 启动时的思考档位
     */
    public static void write(Path home, AgentConfig config, List<Endpoint> endpoints, ModelChoice current, String thinking,
                             boolean windows) throws IOException {
        Files.createDirectories(home);
        // 档位表里「这一档不认」写成 null，所以 null 也要写出来。
        var gson = new GsonBuilder().setPrettyPrinting().serializeNulls().create();
        Files.writeString(home.resolve("models.json"), gson.toJson(models(endpoints)) + "\n");
        Files.writeString(home.resolve("settings.json"), gson.toJson(settings(config, endpoints, current, thinking, windows)) + "\n");
    }

    static JsonObject models(List<Endpoint> endpoints) {
        var providers = new JsonObject();
        for (Endpoint endpoint : endpoints) {
            ModelChoice first = endpoint.models().getFirst();
            var provider = new JsonObject();
            provider.addProperty("baseUrl", first.baseUrl());
            provider.addProperty("api", first.api());
            provider.addProperty("apiKey", "$" + endpoint.keyVariable());
            if (first.api().equals("openai-completions")) {
                // 系统提示词按 system 角色发，各家兼容接口都认；思考强度发不发、发什么值看每个模型自己的档位表。
                var compat = new JsonObject();
                compat.addProperty("supportsDeveloperRole", false);
                compat.addProperty("supportsReasoningEffort", true);
                provider.add("compat", compat);
            }
            var models = new JsonArray();
            endpoint.models().forEach(model -> models.add(model(model)));
            provider.add("models", models);
            providers.add(endpoint.name(), provider);
        }
        var root = new JsonObject();
        root.add("providers", providers);
        return root;
    }

    private static JsonObject model(ModelChoice model) {
        var entry = new JsonObject();
        entry.addProperty("id", model.id());
        // 档位表为空的模型（思考由模型名决定）按不会思考的模型登记，pi 就什么思考参数都不发。
        entry.addProperty("reasoning", model.adjustable());
        if (model.adjustable()) {
            entry.add("thinkingLevelMap", levelMap(model));
        }
        var input = new JsonArray();
        input.add("text");
        input.add("image");
        entry.add("input", input);
        entry.addProperty("contextWindow", model.contextWindow());
        return entry;
    }

    /**
     * 配置里的档位表 → pi 的 thinkingLevelMap：表里有的档位写它发的值；没有的写 null（pi 就不让选这一档）。
     * off 的值为空表示关思考时不发参数，pi 那边的写法是不写 off 这个键。
     */
    private static JsonObject levelMap(ModelChoice model) {
        var map = new JsonObject();
        for (String level : ThinkingLevels.ALL) {
            String sent = model.levels().get(level);
            if (sent == null) {
                map.add(level, JsonNull.INSTANCE);
            } else if (!sent.isEmpty()) {
                map.addProperty(level, sent);
            }
        }
        return map;
    }

    static JsonObject settings(AgentConfig config, List<Endpoint> endpoints, ModelChoice current, String thinking, boolean windows) {
        var settings = new JsonObject();
        settings.addProperty("defaultProvider", endpointOf(endpoints, current).orElseThrow().name());
        settings.addProperty("defaultModel", current.id());
        settings.addProperty("defaultThinkingLevel", current.adjustable() ? thinking : ThinkingLevels.OFF);
        settings.addProperty("quietStartup", true);
        settings.addProperty("defaultProjectTrust", "never");
        // 允许动这台电脑时给读写文件和一个 shell：Windows 上是 PowerShell（那边通常没有 bash），别处是 bash。
        var tools = new JsonArray();
        if (config.tools().localCode()) {
            List.of("read", windows ? "powershell" : "bash", "edit", "write").forEach(tools::add);
        }
        settings.add("defaultTools", tools);
        var retry = new JsonObject();
        retry.addProperty("enabled", true);
        retry.addProperty("maxRetries", RETRIES);
        retry.addProperty("baseDelayMs", RETRY_DELAY_MILLIS);
        settings.add("retry", retry);
        return settings;
    }
}
