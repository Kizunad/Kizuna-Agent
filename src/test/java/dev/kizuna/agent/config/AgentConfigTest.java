package dev.kizuna.agent.config;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AgentConfigTest {
    @Test
    void firstLoadWritesCommentedDefaultsThatAreNotReady(@TempDir Path directory) throws Exception {
        // 第一次启动：写出带注释的默认文件；地址、密钥、模型都空着，所以还不能用。
        Path file = directory.resolve("config").resolve(AgentConfig.FILE_NAME);
        AgentConfig config = AgentConfig.load(file);
        assertTrue(Files.readString(file).contains("# 密钥。只写在这里"));
        assertFalse(config.model().ready());
        assertTrue(config.missing().isEmpty());
        assertTrue(config.tools().localCode());
        assertEquals(0, config.bridge().port());
        assertEquals("openai-completions", config.model().api());
    }

    @Test
    void oldFileWithoutNewKeysRunsOnDefaultsAndReportsThem() {
        // 旧文件只有模型三项：其余用默认值，缺了哪些键逐个报出来。
        AgentConfig config = AgentConfig.parse("""
                [model]
                base_url = "http://127.0.0.1:8317/v1"
                api_key = "secret-key"
                model = "gemini-3.8-flash-high"
                """);
        assertTrue(config.model().ready());
        assertEquals("", config.model().fallbackModel());
        assertEquals(10, config.bridge().blocksPerTickMillis());
        assertTrue(config.missing().contains("model.fallback_model"));
        assertTrue(config.missing().contains("tools.local_code"));
        assertFalse(config.missing().contains("model.model"));
    }

    @Test
    void wrongValuesAreRejectedNotDefaulted() {
        // 写错的值直接报错并点名是哪个键，不悄悄换成默认。
        var thinking = assertThrows(IllegalArgumentException.class, () -> AgentConfig.parse("[model]\nthinking = \"extreme\"\n"));
        assertTrue(thinking.getMessage().contains("model.thinking"));
        var flag = assertThrows(IllegalArgumentException.class, () -> AgentConfig.parse("[tools]\nlocal_code = \"yes\"\n"));
        assertTrue(flag.getMessage().contains("tools.local_code"));
        var port = assertThrows(IllegalArgumentException.class, () -> AgentConfig.parse("[bridge]\nport = 70000\n"));
        assertTrue(port.getMessage().contains("bridge.port"));
        assertThrows(IllegalArgumentException.class, () -> AgentConfig.parse("[model\n"));
    }

    @Test
    void keyNeverShowsUpWhenConfigIsPrinted() {
        // 配置打进日志时不带密钥。
        AgentConfig config = AgentConfig.parse("[model]\napi_key = \"secret-key\"\n");
        assertFalse(config.toString().contains("secret-key"));
        assertTrue(config.toString().contains("已填"));
    }
}
