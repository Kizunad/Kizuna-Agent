package dev.kizuna.agent.notes;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MemoryFileTest {
    @Test
    void entriesSurviveAsOneLineEachAndComeBackInOrder(@TempDir Path directory) throws Exception {
        // 记两条：文件里各占一行；换一个对象（下次进游戏）读出来还是那两条，先记的在前。
        Path file = directory.resolve("pi-home").resolve("MEMORY.md");
        var memory = new MemoryFile(file);
        assertEquals(List.of(), memory.list());
        assertEquals(1, memory.save("屋顶用深色橡木"));
        assertEquals(2, memory.save("主基地在\n  [120, 64, -340]"));
        assertEquals(List.of("屋顶用深色橡木", "主基地在 [120, 64, -340]"), new MemoryFile(file).list());
        assertTrue(Files.readString(file).contains("- 屋顶用深色橡木\n- 主基地在 [120, 64, -340]\n"));
    }

    @Test
    void sameEntryIsNotStoredTwice(@TempDir Path directory) {
        var memory = new MemoryFile(directory.resolve("MEMORY.md"));
        memory.save("屋顶用深色橡木");
        memory.save("墙用石砖");
        assertEquals(1, memory.save("  屋顶用深色橡木 "));
        assertEquals(2, memory.list().size());
    }

    @Test
    void forgettingRemovesThatEntryAndShiftsTheRest(@TempDir Path directory) {
        var memory = new MemoryFile(directory.resolve("MEMORY.md"));
        memory.save("第一条");
        memory.save("第二条");
        memory.save("第三条");
        assertEquals("第二条", memory.forget(2));
        assertEquals(List.of("第一条", "第三条"), memory.list());
        assertTrue(assertThrows(IllegalArgumentException.class, () -> memory.forget(3)).getMessage().contains("1 到 2"));
        assertThrows(IllegalArgumentException.class, () -> memory.forget(0));
    }

    @Test
    void emptyTooLongAndOverTheLimitAreRefusedWithAReason(@TempDir Path directory) {
        var memory = new MemoryFile(directory.resolve("MEMORY.md"));
        assertTrue(assertThrows(IllegalArgumentException.class, () -> memory.save(" \n ")).getMessage().contains("空的"));
        String tooLong = "长".repeat(MemoryFile.MAX_ENTRY_CHARS + 1);
        assertTrue(assertThrows(IllegalArgumentException.class, () -> memory.save(tooLong)).getMessage().contains("最多"));
        for (int index = 0; index < MemoryFile.MAX_ENTRIES; index++) {
            memory.save("第 " + index + " 件事");
        }
        String full = assertThrows(IllegalArgumentException.class, () -> memory.save("再多一条")).getMessage();
        assertTrue(full.contains("上限") && full.contains("memory_forget"), full);
        assertEquals(MemoryFile.MAX_ENTRIES, memory.list().size());
    }

    @Test
    void handEditedFileKeepsOnlyBulletLines(@TempDir Path directory) throws Exception {
        // 玩家手改过：标题、空行、随手写的说明都不算条目。
        Path file = directory.resolve("MEMORY.md");
        Files.writeString(file, "# 我的笔记\n\n随便写的一句\n- 喜欢圆塔\n  - 窗户要大\n-没有空格的不算\n");
        assertEquals(List.of("喜欢圆塔", "窗户要大"), new MemoryFile(file).list());
    }
}
