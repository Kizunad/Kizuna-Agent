package dev.kizuna.agent.pi;

import com.google.gson.JsonObject;
import dev.kizuna.agent.config.AgentConfig;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;

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

    @Test
    void keyStaysOutOfEveryFilePiReads(@TempDir Path home) throws Exception {
        // pi 的配置目录里不落密钥：models.json 只写环境变量名。
        PiHome.write(home, AgentConfig.parse(CONFIG), "gemini-3.8-flash-high", true);
        for (String name : new String[]{"models.json", "settings.json"}) {
            assertFalse(Files.readString(home.resolve(name)).contains("secret-key"), name);
        }
        assertTrue(Files.readString(home.resolve("models.json")).contains("\"$KIZUNA_AGENT_KEY\""));
    }

    @Test
    void primaryAndFallbackAreBothRegistered() {
        // 主模型和备用模型都登记，换备用时 pi 认得；/agent model 临时换的名字排在最前。
        JsonObject provider = PiHome.models(AgentConfig.parse(CONFIG).model(), "gpt-6-luna")
                .getAsJsonObject("providers").getAsJsonObject(PiHome.PROVIDER);
        assertEquals("[\"gpt-6-luna\",\"gemini-3.8-flash-high\",\"gpt-6.1-terra\"]",
                provider.getAsJsonArray("models").asList().stream().map(model -> model.getAsJsonObject().get("id")).toList().toString().replace(" ", ""));
        assertFalse(provider.getAsJsonObject("compat").get("supportsDeveloperRole").getAsBoolean());
    }

    @Test
    void shellToolFollowsPlatformAndSwitch() {
        // Windows 上给 PowerShell（那边通常没有 bash），别处给 bash；关掉动电脑的开关就一个自带工具都不给。
        AgentConfig open = AgentConfig.parse(CONFIG);
        assertEquals("[\"read\",\"powershell\",\"edit\",\"write\"]", PiHome.settings(open, "m", true).getAsJsonArray("defaultTools").toString());
        assertEquals("[\"read\",\"bash\",\"edit\",\"write\"]", PiHome.settings(open, "m", false).getAsJsonArray("defaultTools").toString());
        AgentConfig closed = AgentConfig.parse(CONFIG + "[tools]\nlocal_code = false\n");
        assertEquals("[]", PiHome.settings(closed, "m", true).getAsJsonArray("defaultTools").toString());
    }

    @Test
    void nodeVersionGate() {
        assertTrue(Executables.atLeast("v22.19.0", Executables.MINIMUM_NODE));
        assertTrue(Executables.atLeast("24.14.0", Executables.MINIMUM_NODE));
        assertFalse(Executables.atLeast("v22.18.9", Executables.MINIMUM_NODE));
        assertFalse(Executables.atLeast("v20.11.1", Executables.MINIMUM_NODE));
        assertFalse(Executables.atLeast("not a version", Executables.MINIMUM_NODE));
    }
}
