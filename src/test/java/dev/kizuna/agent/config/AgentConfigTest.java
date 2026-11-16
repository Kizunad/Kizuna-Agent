package dev.kizuna.agent.config;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

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
        assertFalse(config.ready());
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
        assertTrue(config.ready());
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
        // 配置打进日志时不带密钥，清单里自带的密钥也不带。
        AgentConfig config = AgentConfig.parse("[model]\napi_key = \"secret-key\"\n[[models]]\nid = \"m\"\napi_key = \"other-secret\"\n");
        assertFalse(config.toString().contains("secret-key"));
        assertFalse(config.toString().contains("other-secret"));
        assertTrue(config.toString().contains("已填"));
    }

    private static final String LISTED = """
            [model]
            base_url = "http://127.0.0.1:8317/v1"
            api_key = "secret-key"
            model = "gemini"
            fallback_model = "gpt-6.1-terra"
            thinking = "low"

            [[models]]
            alias = "gemini"
            id = "gemini-3.8-flash-high"

            [[models]]
            alias = "sol"
            id = "gpt-6.1-sol"
            thinking = "high"
            thinking_levels = { xhigh = "xhigh", off = "none", low = "low", medium = "medium", high = "high" }

            [[models]]
            alias = "fixed"
            id = "gemini-3.8-flash-tiered"
            thinking_levels = {}

            [[models]]
            id = "claude-sonnet-4-6"
            base_url = "https://example.invalid/v1"
            api_key = "other-secret"
            context_window = 150000
            """;

    @Test
    void modelListFillsGapsFromTheModelSection() {
        // 清单里每一项没写的东西取 [model] 的；别名没写就用模型 ID。
        AgentConfig config = AgentConfig.parse(LISTED);
        assertEquals(4, config.models().size());
        ModelChoice gemini = config.choice("gemini");
        assertEquals("gemini-3.8-flash-high", gemini.id());
        assertEquals("http://127.0.0.1:8317/v1", gemini.baseUrl());
        assertEquals("secret-key", gemini.apiKey());
        assertEquals("low", gemini.thinking());
        assertEquals(List.of("off", "minimal", "low", "medium", "high"), List.copyOf(gemini.levels().keySet()));
        assertEquals("", gemini.levels().get("off"));
        assertEquals(200000, gemini.contextWindow());
        ModelChoice claude = config.choice("claude-sonnet-4-6");
        assertTrue(claude.listed());
        assertEquals("claude-sonnet-4-6", claude.alias());
        assertEquals("https://example.invalid/v1", claude.baseUrl());
        assertEquals("other-secret", claude.apiKey());
        assertEquals(150000, claude.contextWindow());
        assertFalse(claude.sameEndpoint(gemini));
    }

    @Test
    void levelTableDecidesWhichLevelsAModelTakes() {
        // 自己写了档位表的模型：只认表里的档位，从少到多排好，每一档发什么值照表。
        AgentConfig config = AgentConfig.parse(LISTED);
        ModelChoice sol = config.choice("sol");
        assertEquals(List.of("off", "low", "medium", "high", "xhigh"), List.copyOf(sol.levels().keySet()));
        assertEquals("none", sol.levels().get("off"));
        assertEquals("high", sol.thinking());
        assertTrue(sol.adjustable());
        // 空表：思考档位由模型名决定，调不了。
        ModelChoice fixed = config.choice("fixed");
        assertFalse(fixed.adjustable());
        assertEquals("off", fixed.thinking());
    }

    @Test
    void defaultLevelLandsOnTheNearestLevelAModelTakes() {
        // [model] 的档位这个模型不认时，落到它认的最近一档（一样近取少的），不报错。
        AgentConfig config = AgentConfig.parse("""
                [model]
                thinking = "minimal"
                [[models]]
                id = "a"
                thinking_levels = { low = "low", high = "high" }
                [[models]]
                id = "b"
                thinking_levels = { off = "none", low = "low" }
                """);
        assertEquals("low", config.choice("a").thinking());
        assertEquals("off", config.choice("b").thinking());
        assertEquals("high", ThinkingLevels.fit("max", List.of("off", "low", "high")));
    }

    @Test
    void namesResolveAsAliasThenIdThenPlainModelId() {
        // 主模型、备用模型、/agent model 后面的名字都这么找：别名 → 清单里的模型 ID → 不在清单里的模型 ID。
        AgentConfig config = AgentConfig.parse(LISTED);
        assertEquals("gemini-3.8-flash-high", config.primary().id());
        assertEquals("sol", config.choice("gpt-6.1-sol").alias());
        ModelChoice terra = config.fallback().orElseThrow();
        assertFalse(terra.listed());
        assertEquals("gpt-6.1-terra", terra.id());
        assertEquals("http://127.0.0.1:8317/v1", terra.baseUrl());
        assertEquals("low", terra.thinking());
        assertTrue(config.ready());
        // 补全名单：清单里的别名在前，再是直接写了模型 ID 的备用模型；主模型已经在清单里，不重复。
        assertEquals(List.of("gemini", "sol", "fixed", "claude-sonnet-4-6", "gpt-6.1-terra"), config.names());
        // 没有清单的旧配置：名单就是主、备模型。
        AgentConfig old = AgentConfig.parse("[model]\nmodel = \"m1\"\nfallback_model = \"m2\"\n");
        assertEquals(List.of("m1", "m2"), old.names());
        assertTrue(old.fallback().isPresent());
        assertTrue(AgentConfig.parse("[model]\nmodel = \"m1\"\n").fallback().isEmpty());
    }

    @Test
    void visionDefaultsToTrueAndCanBeTurnedOffPerModel() {
        // 没写 vision：能看图。[model] 里关掉，清单里没单独写的跟着关；清单里单独写的各算各的。
        assertTrue(AgentConfig.parse("""
                [model]
                base_url = "http://127.0.0.1:8317/v1"
                api_key = "secret-key"
                model = "anything"
                """).primary().vision());
        AgentConfig config = AgentConfig.parse("""
                [model]
                base_url = "http://127.0.0.1:8317/v1"
                api_key = "secret-key"
                model = "plain"
                vision = false

                [[models]]
                alias = "plain"
                id = "text-model"

                [[models]]
                alias = "eyes"
                id = "vision-model"
                vision = true
                """);
        assertFalse(config.choice("plain").vision());
        assertTrue(config.choice("eyes").vision());
        assertFalse(config.choice("not-listed").vision());
        assertListError("""
                [[models]]
                id = "x"
                vision = "yes"
                """, "vision", "true 或 false");
    }

    @Test
    void wrongListEntriesSayWhichOneAndWhy() {
        // 清单写错了：报是第几个 [[models]]、错在哪儿。
        assertListError("[[models]]\nalias = \"a\"\n", "第 1 个 [[models]]", "id");
        assertListError("[[models]]\nid = \"a\"\n[[models]]\nid = \"b\"\nalias = \"a\"\n", "第 2 个 [[models]]", "重了");
        assertListError("[[models]]\nid = \"a\"\n[[models]]\nid = \"a\"\nalias = \"again\"\n", "第 2 个 [[models]]", "同一个模型");
        assertListError("[[models]]\nid = \"a\"\nalias = \"two words\"\n", "第 1 个 [[models]]", "空格");
        assertListError("[[models]]\nid = \"a\"\nthinkng = \"low\"\n", "第 1 个 [[models]]", "thinkng");
        assertListError("[[models]]\nid = \"a\"\nthinking = \"xhigh\"\n", "thinking 是 xhigh", "off、minimal、low、medium、high");
        assertListError("[[models]]\nid = \"a\"\nthinking_levels = { extreme = \"x\" }\n", "不认识档位 extreme", "max");
        assertListError("[[models]]\nid = \"a\"\nthinking_levels = { low = \"\" }\n", "thinking_levels.low", "只有 off");
        assertListError("[[models]]\nid = \"a\"\napi = \"grpc\"\n", "api", "openai-completions");
        assertListError("[[models]]\nid = \"a\"\ncontext_window = 10\n", "context_window", "1000");
        assertListError("models = \"a\"\n", "[[models]]", "一段段");
    }

    private static void assertListError(String text, String... parts) {
        var error = assertThrows(IllegalArgumentException.class, () -> AgentConfig.parse(text));
        for (String part : parts) {
            assertTrue(error.getMessage().contains(part), error.getMessage() + " 里应当有「" + part + "」");
        }
    }
}
