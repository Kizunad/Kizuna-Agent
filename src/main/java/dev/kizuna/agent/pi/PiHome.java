package dev.kizuna.agent.pi;

import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import dev.kizuna.agent.config.AgentConfig;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashSet;
import java.util.List;

/**
 * 照助手的配置写出 pi 自己的配置目录（models.json、settings.json）。
 *
 * <p>这个目录在实例里，和玩家自己的 {@code ~/.pi/agent} 没有关系。密钥不写进这里：models.json 里只写环境变量名，
 * 密钥本身由拉起进程时的环境变量给。</p>
 */
public final class PiHome {
    public static final String PROVIDER = "kizuna";
    public static final String KEY_VARIABLE = "KIZUNA_AGENT_KEY";
    private static final int RETRIES = 3;
    private static final int RETRY_DELAY_MILLIS = 2000;

    private PiHome() {
        // 只有静态写文件的方法。
    }

    /**
     * @param model 这次用作主模型的名字（一般是配置里的，/agent model 临时换的也从这里进）
     */
    public static void write(Path home, AgentConfig config, String model, boolean windows) throws IOException {
        Files.createDirectories(home);
        var gson = new GsonBuilder().setPrettyPrinting().create();
        Files.writeString(home.resolve("models.json"), gson.toJson(models(config.model(), model)) + "\n");
        Files.writeString(home.resolve("settings.json"), gson.toJson(settings(config, model, windows)) + "\n");
    }

    static JsonObject models(AgentConfig.Model settings, String model) {
        boolean thinking = !settings.thinking().equals("off");
        var provider = new JsonObject();
        provider.addProperty("baseUrl", settings.baseUrl());
        provider.addProperty("api", settings.api());
        provider.addProperty("apiKey", "$" + KEY_VARIABLE);
        if (settings.api().equals("openai-completions")) {
            // 系统提示词按 system 角色发，各家兼容接口都认；思考强度参数只在配置里开了思考时才发。
            var compat = new JsonObject();
            compat.addProperty("supportsDeveloperRole", false);
            compat.addProperty("supportsReasoningEffort", thinking);
            provider.add("compat", compat);
        }
        var models = new JsonArray();
        var names = new LinkedHashSet<String>(List.of(model));
        if (!settings.model().isBlank()) {
            names.add(settings.model());
        }
        if (!settings.fallbackModel().isBlank()) {
            names.add(settings.fallbackModel());
        }
        for (String name : names) {
            var entry = new JsonObject();
            entry.addProperty("id", name);
            entry.addProperty("reasoning", thinking);
            var input = new JsonArray();
            input.add("text");
            input.add("image");
            entry.add("input", input);
            entry.addProperty("contextWindow", settings.contextWindow());
            models.add(entry);
        }
        provider.add("models", models);
        var providers = new JsonObject();
        providers.add(PROVIDER, provider);
        var root = new JsonObject();
        root.add("providers", providers);
        return root;
    }

    static JsonObject settings(AgentConfig config, String model, boolean windows) {
        var settings = new JsonObject();
        settings.addProperty("defaultProvider", PROVIDER);
        settings.addProperty("defaultModel", model);
        settings.addProperty("defaultThinkingLevel", config.model().thinking());
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
