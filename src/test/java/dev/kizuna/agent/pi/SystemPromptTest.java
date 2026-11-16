package dev.kizuna.agent.pi;

import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SystemPromptTest {
    private static final Map<String, String> VALUES = Map.of("python", "D:/mc/config/kizuna-agent/python",
            "workspace", "D:/mc/config/kizuna-agent/workspace", "shell", "powershell");

    @Test
    void sectionsFollowTheLocalCodeSwitch() {
        String template = "开头\n{{#local}}\n能动电脑：{{shell}}\n{{/local}}\n{{^local}}\n不能动电脑\n{{/local}}\n结尾\n";
        assertEquals("开头\n能动电脑：powershell\n结尾\n", SystemPrompt.build(template, true, VALUES));
        assertEquals("开头\n不能动电脑\n结尾\n", SystemPrompt.build(template, false, VALUES));
    }

    @Test
    void unknownNamesAreAnErrorNotLeftInThePrompt() {
        assertTrue(assertThrows(IllegalArgumentException.class, () -> SystemPrompt.build("在 {{nowhere}}\n", true, VALUES))
                .getMessage().contains("nowhere"));
    }

    @Test
    void shippedPromptTellsWhatTheAssistantCanDoWithRealPaths() {
        // JAR 里带的那份：能动电脑时写明 Python 库在哪儿、怎么 import、read 能看图；每样新本事都提到；没有剩下的占位符。
        String local = SystemPrompt.build(PiRuntime.promptTemplate(), true, VALUES);
        for (String part : new String[] {"from kizuna_mc import mc", "D:/mc/config/kizuna-agent/python/kizuna_mc.py", "read 也能读图片",
                "powershell", "D:/mc/config/kizuna-agent/workspace", "mc_screenshot", "orbit", "memory_save", "memory_forget",
                "skill_save", "skill_read", "mc_marks", "指点杖", "run_python 的 file"}) {
            assertTrue(local.contains(part), "系统提示词里应有：" + part);
        }
        assertFalse(local.contains("{{"), "不该剩占位符");
        assertFalse(local.contains("碰不了"));

        // 关掉动电脑：不提 Python 和读写文件，但截图、记忆、技能、标记照旧。
        String gameOnly = SystemPrompt.build(PiRuntime.promptTemplate(), false, VALUES);
        for (String part : new String[] {"run_python", "kizuna_mc", "powershell", "D:/mc"}) {
            assertFalse(gameOnly.contains(part), "关掉动电脑后不该提：" + part);
        }
        for (String part : new String[] {"碰不了", "mc_screenshot", "memory_save", "skill_save", "mc_marks", "技能里只写步骤"}) {
            assertTrue(gameOnly.contains(part), "关掉动电脑后仍应有：" + part);
        }
        assertFalse(gameOnly.contains("{{"));
    }
}
