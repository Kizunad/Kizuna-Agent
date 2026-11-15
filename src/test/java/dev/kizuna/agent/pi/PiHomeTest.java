package dev.kizuna.agent.pi;

import com.google.gson.JsonObject;
import dev.kizuna.agent.config.AgentConfig;
import dev.kizuna.agent.config.ModelChoice;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PiHomeTest {
    private static final String CONFIG = """
            [model]
            base_url = "http://127.0.0.1:8317/v1"
            api_key = "secret-key"
            model = "gemini-3.8-flash-high"
            fallback_model = "gpt-6.1-terra"
            """;
    /** 清单：一个照默认档位的、一个自己写档位表的、一个思考由模型名决定的、一个在别的接口上的。 */
    private static final String LISTED = """
            [model]
            base_url = "http://127.0.0.1:8317/v1"
            api_key = "secret-key"
            model = "gemini"
            fallback_model = "sol"
            thinking = "low"

            [[models]]
            alias = "gemini"
            id = "gemini-3.8-flash-high"

            [[models]]
            alias = "sol"
            id = "gpt-6.1-sol"
            thinking = "high"
            thinking_levels = { off = "none", low = "low", medium = "medium", high = "high", xhigh = "xhigh" }

            [[models]]
            alias = "fixed"
            id = "gemini-3.8-flash-tiered"
            thinking_levels = {}

            [[models]]
            alias = "elsewhere"
            id = "claude-sonnet-4-6"
            base_url = "https://example.invalid/v1"
            api_key = "other-secret"
            """;

    @Test
    void keysStayOutOfEveryFilePiReads(@TempDir Path home) throws Exception {
        // pi 的配置目录里不落密钥：models.json 只写环境变量名，每个接口一个。
        AgentConfig config = AgentConfig.parse(LISTED);
        List<PiHome.Endpoint> endpoints = PiHome.endpoints(config, config.primary());
        PiHome.write(home, config, endpoints, config.primary(), "low", true);
        for (String name : new String[]{"models.json", "settings.json"}) {
            String written = Files.readString(home.resolve(name));
            assertFalse(written.contains("secret-key"), name);
            assertFalse(written.contains("other-secret"), name);
        }
        String models = Files.readString(home.resolve("models.json"));
        assertTrue(models.contains("\"$KIZUNA_AGENT_KEY\""));
        assertTrue(models.contains("\"$KIZUNA_AGENT_KEY_2\""));
        assertEquals("secret-key", endpoints.get(0).key());
        assertEquals("other-secret", endpoints.get(1).key());
    }

    @Test
    void primaryFallbackAndCurrentAreRegisteredWithoutAList() {
        // 没有清单的旧配置：主模型、备用模型、/agent model 临时换的模型 ID 都登记在同一个接口下。
        AgentConfig config = AgentConfig.parse(CONFIG);
        List<PiHome.Endpoint> endpoints = PiHome.endpoints(config, config.choice("gpt-6-luna"));
        assertEquals(1, endpoints.size());
        JsonObject provider = PiHome.models(endpoints).getAsJsonObject("providers").getAsJsonObject(PiHome.PROVIDER);
        assertEquals(List.of("gemini-3.8-flash-high", "gpt-6.1-terra", "gpt-6-luna"), ids(provider));
        assertFalse(provider.getAsJsonObject("compat").get("supportsDeveloperRole").getAsBoolean());
        assertTrue(provider.getAsJsonObject("compat").get("supportsReasoningEffort").getAsBoolean());
    }

    @Test
    void listedModelsAreGroupedByEndpoint() {
        // 清单里的模型全登记；自带接口地址和密钥的另起一个 provider。主、备模型已经在清单里，不重复登记。
        AgentConfig config = AgentConfig.parse(LISTED);
        List<PiHome.Endpoint> endpoints = PiHome.endpoints(config, config.primary());
        JsonObject providers = PiHome.models(endpoints).getAsJsonObject("providers");
        assertEquals(List.of("gemini-3.8-flash-high", "gpt-6.1-sol", "gemini-3.8-flash-tiered"), ids(providers.getAsJsonObject("kizuna")));
        assertEquals(List.of("claude-sonnet-4-6"), ids(providers.getAsJsonObject("kizuna-2")));
        assertEquals("https://example.invalid/v1", providers.getAsJsonObject("kizuna-2").get("baseUrl").getAsString());
        assertEquals("kizuna-2", PiHome.endpointOf(endpoints, config.choice("elsewhere")).orElseThrow().name());
        // 不在清单里的模型 ID 没登记过：会话据此知道要重启 pi。
        assertTrue(PiHome.endpointOf(endpoints, config.choice("gpt-6-luna")).isEmpty());
    }

    @Test
    void levelTableBecomesThinkingLevelMap() {
        // 档位表原样变成 pi 的 thinkingLevelMap：表里没有的档位是 null；off 发空值就是不写 off。
        AgentConfig config = AgentConfig.parse(LISTED);
        JsonObject provider = PiHome.models(PiHome.endpoints(config, config.primary())).getAsJsonObject("providers").getAsJsonObject("kizuna");
        JsonObject standard = provider.getAsJsonArray("models").get(0).getAsJsonObject();
        assertTrue(standard.get("reasoning").getAsBoolean());
        assertEquals("{\"minimal\":\"minimal\",\"low\":\"low\",\"medium\":\"medium\",\"high\":\"high\",\"xhigh\":null,\"max\":null}",
                standard.get("thinkingLevelMap").toString());
        JsonObject written = provider.getAsJsonArray("models").get(1).getAsJsonObject();
        assertEquals("{\"off\":\"none\",\"minimal\":null,\"low\":\"low\",\"medium\":\"medium\",\"high\":\"high\",\"xhigh\":\"xhigh\",\"max\":null}",
                written.get("thinkingLevelMap").toString());
        // 思考由模型名决定的模型按不会思考的登记，pi 就什么思考参数都不发。
        JsonObject fixed = provider.getAsJsonArray("models").get(2).getAsJsonObject();
        assertFalse(fixed.get("reasoning").getAsBoolean());
        assertFalse(fixed.has("thinkingLevelMap"));
    }

    @Test
    void nullLevelsSurviveBeingWrittenToDisk(@TempDir Path home) throws Exception {
        // 写文件时 null 不能被省掉：省掉了 pi 会当成「这一档照默认发」。
        AgentConfig config = AgentConfig.parse(LISTED);
        PiHome.write(home, config, PiHome.endpoints(config, config.primary()), config.primary(), "low", true);
        assertTrue(Files.readString(home.resolve("models.json")).contains("\"xhigh\": null"));
    }

    @Test
    void settingsStartOnTheCurrentModelAndLevel() {
        AgentConfig config = AgentConfig.parse(LISTED);
        ModelChoice elsewhere = config.choice("elsewhere");
        List<PiHome.Endpoint> endpoints = PiHome.endpoints(config, elsewhere);
        JsonObject settings = PiHome.settings(config, endpoints, elsewhere, "medium", true);
        assertEquals("kizuna-2", settings.get("defaultProvider").getAsString());
        assertEquals("claude-sonnet-4-6", settings.get("defaultModel").getAsString());
        assertEquals("medium", settings.get("defaultThinkingLevel").getAsString());
        // 思考由模型名决定的模型：pi 那边一律按 off。
        ModelChoice fixed = config.choice("fixed");
        assertEquals("off", PiHome.settings(config, endpoints, fixed, "high", true).get("defaultThinkingLevel").getAsString());
    }

    @Test
    void shellToolFollowsPlatformAndSwitch() {
        // Windows 上给 PowerShell（那边通常没有 bash），别处给 bash；关掉动电脑的开关就一个自带工具都不给。
        assertEquals("[\"read\",\"powershell\",\"edit\",\"write\"]", tools(CONFIG, true));
        assertEquals("[\"read\",\"bash\",\"edit\",\"write\"]", tools(CONFIG, false));
        assertEquals("[]", tools(CONFIG + "[tools]\nlocal_code = false\n", true));
    }

    @Test
    void nodeVersionGate() {
        assertTrue(Executables.atLeast("v22.19.0", Executables.MINIMUM_NODE));
        assertTrue(Executables.atLeast("24.14.0", Executables.MINIMUM_NODE));
        assertFalse(Executables.atLeast("v22.18.9", Executables.MINIMUM_NODE));
        assertFalse(Executables.atLeast("v20.11.1", Executables.MINIMUM_NODE));
        assertFalse(Executables.atLeast("not a version", Executables.MINIMUM_NODE));
    }

    private static List<String> ids(JsonObject provider) {
        return provider.getAsJsonArray("models").asList().stream().map(model -> model.getAsJsonObject().get("id").getAsString()).toList();
    }

    private static String tools(String text, boolean windows) {
        AgentConfig config = AgentConfig.parse(text);
        return PiHome.settings(config, PiHome.endpoints(config, config.primary()), config.primary(), "off", windows)
                .getAsJsonArray("defaultTools").toString();
    }
}
