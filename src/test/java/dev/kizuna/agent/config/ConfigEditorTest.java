package dev.kizuna.agent.config;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ConfigEditorTest {
    private static final String TEXT = """
            # 开头的注释
            [model]
            base_url = "http://127.0.0.1:8317/v1"
            api_key = "secret-key"
            model = "gemini"
            thinking = "off"   # 默认档位

            [[models]]
            alias = "gemini"
            id = "gemini-3.8-flash-high"

            [[models]]
            alias = "sol"
            id = "gpt-6.1-sol"
            thinking = 'medium' # 单引号也行
            thinking_levels = { off = "none", low = "low", medium = "medium", high = "high" }

            [bridge]
            port = 0
            """;

    @Test
    void listedModelGetsItsOwnLineChangedAndNothingElse() {
        // 清单里的模型：只改它那一段的 thinking，别的行（注释、别的模型、[model] 的默认档位）一字不动。
        AgentConfig config = AgentConfig.parse(TEXT);
        String edited = ConfigEditor.withThinking(TEXT, config.choice("sol"), "high");
        assertEquals(TEXT.replace("thinking = 'medium' # 单引号也行", "thinking = \"high\" # 单引号也行"), edited);
        assertEquals("high", AgentConfig.parse(edited).choice("sol").thinking());
        assertEquals("off", AgentConfig.parse(edited).model().thinking());
    }

    @Test
    void listedModelWithoutTheLineGetsOneAdded() {
        // 那一段没写 thinking：加一行在这一段开头，别的模型不受影响。
        AgentConfig config = AgentConfig.parse(TEXT);
        String edited = ConfigEditor.withThinking(TEXT, config.choice("gemini"), "low");
        assertEquals(TEXT.replace("alias = \"gemini\"\n", "thinking = \"low\"\nalias = \"gemini\"\n"), edited);
        AgentConfig reread = AgentConfig.parse(edited);
        assertEquals("low", reread.choice("gemini").thinking());
        assertEquals("medium", reread.choice("sol").thinking());
    }

    @Test
    void unlistedModelChangesTheModelSection() {
        // 不在清单里的模型用的是 [model] 的档位：改那一行；[model] 里没写就加在这一段末尾。
        AgentConfig config = AgentConfig.parse(TEXT);
        String edited = ConfigEditor.withThinking(TEXT, config.choice("gpt-6-luna"), "medium");
        assertEquals(TEXT.replace("thinking = \"off\"   # 默认档位", "thinking = \"medium\"   # 默认档位"), edited);
        String bare = "[model]\nmodel = \"m\"\napi_key = \"k\"\n\n[bridge]\nport = 0\n";
        assertEquals("[model]\nmodel = \"m\"\napi_key = \"k\"\nthinking = \"high\"\n\n[bridge]\nport = 0\n",
                ConfigEditor.withThinking(bare, AgentConfig.parse(bare).choice("m"), "high"));
    }

    @Test
    void windowsLineEndingsAreKept() {
        // 记事本存的 \r\n：改的行、加的行都照旧用 \r\n。
        String windows = TEXT.replace("\n", "\r\n");
        AgentConfig config = AgentConfig.parse(windows);
        String changed = ConfigEditor.withThinking(windows, config.choice("sol"), "low");
        String added = ConfigEditor.withThinking(windows, config.choice("gemini"), "low");
        assertEquals(windows.replace("thinking = 'medium'", "thinking = \"low\""), changed);
        assertTrue(added.contains("thinking = \"low\"\r\nalias = \"gemini\"\r\n"));
        assertEquals(added.replace("\r\n", "").length(), added.replace("\n", "").replace("\r", "").length());
    }

    @Test
    void fileIsLeftAloneWhenTheModelIsGoneOrTheLineCannotBeEdited(@TempDir Path directory) throws Exception {
        // 进世界以后配置被人改过、找不到这个模型了；或者清单写成一行里好几项的样子：报错，文件不动。
        AgentConfig config = AgentConfig.parse(TEXT);
        Path file = directory.resolve(AgentConfig.FILE_NAME);
        String without = TEXT.replace("alias = \"sol\"", "alias = \"renamed\"");
        Files.writeString(file, without);
        var gone = assertThrows(IllegalArgumentException.class, () -> ConfigEditor.saveThinking(file, config.choice("sol"), "high"));
        assertTrue(gone.getMessage().contains("找不到 sol"));
        assertEquals(without, Files.readString(file));
        String inline = "models = [ { alias = \"sol\", id = \"gpt-6.1-sol\", thinking = \"low\" } ]\n[model]\nmodel = \"sol\"\n";
        Files.writeString(file, inline);
        var stuck = assertThrows(IllegalArgumentException.class,
                () -> ConfigEditor.saveThinking(file, AgentConfig.parse(inline).choice("sol"), "high"));
        assertTrue(stuck.getMessage().contains("请手改"));
        assertEquals(inline, Files.readString(file));
        // 正常情况：文件真的改了。
        Files.writeString(file, TEXT);
        ConfigEditor.saveThinking(file, config.choice("sol"), "high");
        assertEquals("high", AgentConfig.load(file).choice("sol").thinking());
    }
}
