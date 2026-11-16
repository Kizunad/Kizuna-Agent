package dev.kizuna.agent.bridge;

import com.google.gson.JsonObject;
import dev.kizuna.agent.marks.Marks;
import dev.kizuna.agent.notes.MemoryFile;
import dev.kizuna.agent.notes.SkillLibrary;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class NotebookCallsTest {
    @TempDir
    Path home;
    private Marks marks;
    private NotebookCalls notebook;
    private Map<String, BridgeServer.Call> calls;

    @BeforeEach
    void open() {
        marks = new Marks();
        notebook = new NotebookCalls(new MemoryFile(home.resolve("MEMORY.md")), new SkillLibrary(home.resolve("skills")), marks);
        calls = notebook.calls();
    }

    private JsonObject call(String name, String body) {
        return calls.get(name).handle(BridgeServer.bodyOf(body));
    }

    @Test
    void memoryIsSavedListedAndForgottenThroughTheCalls() {
        assertEquals(1, call("memory/save", "{\"text\": \"屋顶用深色橡木\"}").get("number").getAsInt());
        JsonObject second = call("memory/save", "{\"text\": \"墙用石砖\"}");
        assertEquals(2, second.get("count").getAsInt());
        assertEquals(MemoryFile.MAX_ENTRIES, second.get("limit").getAsInt());
        assertEquals("[\"屋顶用深色橡木\",\"墙用石砖\"]", call("memory/list", "{}").get("entries").toString());
        JsonObject forgotten = call("memory/forget", "{\"number\": 1}");
        assertEquals("屋顶用深色橡木", forgotten.get("removed").getAsString());
        assertEquals(1, forgotten.get("count").getAsInt());
        // 写坏的请求回 400，带上原因。
        assertEquals(400, assertThrows(BridgeException.class, () -> call("memory/forget", "{\"number\": 9}")).status());
        assertEquals(400, assertThrows(BridgeException.class, () -> call("memory/forget", "{}")).status());
        assertEquals(400, assertThrows(BridgeException.class, () -> call("memory/save", "{\"text\": \"  \"}")).status());
    }

    @Test
    void skillsAreSavedReadAndDeletedThroughTheCalls() throws Exception {
        JsonObject saved = call("skills/save", """
                {"name": "stone-watchtower", "description": "盖石砖瞭望塔", "body": "先看地面，再跑脚本",
                 "files": [{"path": "scripts/build.py", "content": "print('tower')"}]}""");
        assertFalse(saved.get("updated").getAsBoolean());
        assertEquals(1, saved.get("count").getAsInt());
        Path directory = home.resolve("skills").resolve("stone-watchtower");
        assertEquals(directory.toString(), saved.get("directory").getAsString());
        assertTrue(Files.isRegularFile(directory.resolve("scripts").resolve("build.py")));

        JsonObject read = call("skills/read", "{\"name\": \"stone-watchtower\"}");
        assertTrue(read.get("text").getAsString().contains("先看地面，再跑脚本"));
        assertEquals("[\"scripts/build.py\"]", read.get("files").toString());
        assertEquals("print('tower')", call("skills/read", "{\"name\": \"stone-watchtower\", \"file\": \"scripts/build.py\"}")
                .get("text").getAsString());
        assertTrue(call("skills/save", "{\"name\": \"stone-watchtower\", \"description\": \"新说明\", \"body\": \"新正文\"}")
                .get("updated").getAsBoolean());
        assertEquals("新说明", call("skills/list", "{}").getAsJsonArray("skills").get(0).getAsJsonObject().get("description").getAsString());

        assertEquals(400, assertThrows(BridgeException.class, () -> call("skills/save",
                "{\"name\": \"Bad Name\", \"description\": \"x\", \"body\": \"y\"}")).status());
        assertEquals(400, assertThrows(BridgeException.class, () -> call("skills/read",
                "{\"name\": \"stone-watchtower\", \"file\": \"../../MEMORY.md\"}")).status());
        assertEquals(0, call("skills/delete", "{\"name\": \"stone-watchtower\"}").get("count").getAsInt());
        assertEquals(400, assertThrows(BridgeException.class, () -> call("skills/read", "{\"name\": \"stone-watchtower\"}")).status());
    }

    @Test
    void contextPromptCarriesCurrentMemoryAndSkills() {
        // 每句话开头接在系统提示词后面的那段：刚存的记忆和技能马上就在里面。
        String empty = call("context", "{}").get("prompt").getAsString();
        assertTrue(empty.contains("还没有记忆") && empty.contains("还没有技能"), empty);
        call("memory/save", "{\"text\": \"屋顶用深色橡木\"}");
        call("skills/save", "{\"name\": \"stone-watchtower\", \"description\": \"盖石砖瞭望塔\", \"body\": \"步骤\"}");
        String prompt = notebook.contextPrompt();
        assertTrue(prompt.contains("全局记忆，1 条") && prompt.contains("1. 屋顶用深色橡木"), prompt);
        assertTrue(prompt.contains("你的技能（1 个）") && prompt.contains("- stone-watchtower：盖石砖瞭望塔"), prompt);
        assertTrue(prompt.contains("skill_read"), prompt);
        assertFalse(prompt.contains("快满了"));
        for (int index = 0; index < MemoryFile.NEARLY_FULL; index++) {
            call("memory/save", "{\"text\": \"第 " + index + " 件事\"}");
        }
        assertTrue(notebook.contextPrompt().contains("快满了"));
    }

    @Test
    void marksAreListedWithNumbers() {
        assertEquals("[]", call("marks", "{}").get("marks").toString());
        marks.add(new Marks.Mark(3, 64, -5, "minecraft:overworld", "minecraft:stone_bricks", "up", Marks.Kind.BLOCK));
        marks.add(new Marks.Mark(9, 70, 2, "minecraft:overworld", "minecraft:air", "", Marks.Kind.FEET));
        JsonObject second = call("marks", "{}").getAsJsonArray("marks").get(1).getAsJsonObject();
        assertEquals(2, second.get("number").getAsInt());
        assertEquals("[9,70,2]", second.get("block").toString());
        assertEquals("feet", second.get("kind").getAsString());
    }
}
