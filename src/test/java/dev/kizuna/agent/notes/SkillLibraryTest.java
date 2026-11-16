package dev.kizuna.agent.notes;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SkillLibraryTest {
    private static final String BODY = "# 石砖瞭望塔\n\n1. 先看地面\n2. 跑 scripts/build.py";

    @Test
    void savedSkillIsAStandardSkillDirectoryAndReadsBack(@TempDir Path directory) throws Exception {
        // 存一个带脚本的技能：目录里是标准的 SKILL.md（开头一段名字和说明）加脚本；换一个对象照样读得出来。
        var library = new SkillLibrary(directory.resolve("skills"));
        SkillLibrary.Skill saved = library.save("stone-watchtower", "盖石砖瞭望塔：\"圆\"的那种。\n玩家要塔的时候用。", BODY,
                List.of(new SkillLibrary.Attachment("scripts/build.py", "print('tower')\n")));
        assertEquals(directory.resolve("skills").resolve("stone-watchtower"), saved.directory());
        String file = Files.readString(saved.directory().resolve("SKILL.md"));
        assertTrue(file.startsWith("---\nname: stone-watchtower\ndescription: \"盖石砖瞭望塔：\\\"圆\\\"的那种。 玩家要塔的时候用。\"\n---\n\n# 石砖瞭望塔"), file);

        var reopened = new SkillLibrary(directory.resolve("skills"));
        assertEquals(List.of(new SkillLibrary.Skill("stone-watchtower", "盖石砖瞭望塔：\"圆\"的那种。 玩家要塔的时候用。", saved.directory())),
                reopened.list());
        assertTrue(reopened.read("stone-watchtower").contains("2. 跑 scripts/build.py"));
        assertEquals(List.of("scripts/build.py"), reopened.attachments("stone-watchtower"));
        assertEquals("print('tower')\n", reopened.read("stone-watchtower", "scripts/build.py"));
    }

    @Test
    void savingAgainReplacesTheTextAndKeepsOtherFiles(@TempDir Path directory) {
        var library = new SkillLibrary(directory);
        library.save("tower", "旧说明", "旧正文", List.of(new SkillLibrary.Attachment("notes.md", "资料")));
        library.save("tower", "新说明", "新正文", List.of());
        assertEquals("新说明", library.list().getFirst().description());
        assertTrue(library.read("tower").contains("新正文"));
        assertFalse(library.read("tower").contains("旧正文"));
        assertEquals(List.of("notes.md"), library.attachments("tower"));
        assertEquals(1, library.list().size());
    }

    @Test
    void deletingRemovesTheWholeDirectory(@TempDir Path directory) {
        var library = new SkillLibrary(directory);
        library.save("tower", "说明", "正文", List.of(new SkillLibrary.Attachment("scripts/a.py", "pass")));
        library.delete("tower");
        assertEquals(List.of(), library.list());
        assertTrue(Files.notExists(directory.resolve("tower")));
        assertTrue(assertThrows(IllegalArgumentException.class, () -> library.read("tower")).getMessage().contains("还没有技能"));
    }

    @Test
    void badNamesTextsAndPathsAreRefusedAndNothingIsWritten(@TempDir Path directory) {
        var library = new SkillLibrary(directory.resolve("skills"));
        for (String name : List.of("Stone", "-tower", "tower-", "to--wer", "塔", "a b", "", "a".repeat(SkillLibrary.MAX_NAME_CHARS + 1))) {
            assertTrue(assertThrows(IllegalArgumentException.class, () -> library.save(name, "说明", "正文", List.of()))
                    .getMessage().contains("技能名"), name);
        }
        assertTrue(assertThrows(IllegalArgumentException.class, () -> library.save("tower", " ", "正文", List.of()))
                .getMessage().contains("description"));
        assertTrue(assertThrows(IllegalArgumentException.class, () -> library.save("tower", "说明", "\n", List.of()))
                .getMessage().contains("正文"));
        // 带的文件不能跑到技能目录外面，也不能顶替 SKILL.md。
        for (String path : List.of("../outside.py", "/etc/passwd", "C:/Windows/x.py", "scripts/../../x.py", "", "SKILL.md")) {
            assertThrows(IllegalArgumentException.class, () -> library.save("tower", "说明", "正文",
                    List.of(new SkillLibrary.Attachment(path, "x"))), path);
        }
        assertTrue(Files.notExists(directory.resolve("skills")), "被拒的请求不该留下任何文件");
        assertTrue(Files.notExists(directory.resolve("outside.py")));
    }

    @Test
    void readingCannotLeaveTheSkillDirectory(@TempDir Path directory) throws Exception {
        var library = new SkillLibrary(directory.resolve("skills"));
        library.save("tower", "说明", "正文", List.of());
        Files.writeString(directory.resolve("secret.txt"), "密钥");
        assertThrows(IllegalArgumentException.class, () -> library.read("tower", "../../secret.txt"));
        assertTrue(assertThrows(IllegalArgumentException.class, () -> library.read("tower", "scripts/none.py"))
                .getMessage().contains("没有文件"));
    }

    @Test
    void handPlacedSkillsAreFoundAndBrokenOnesSkipped(@TempDir Path directory) throws Exception {
        // 玩家手放的技能：套了一层分组目录、说明折成两行写的，认；没有开头那段、没有说明的，不认。
        Path nested = Files.createDirectories(directory.resolve("building").resolve("round-tower"));
        Files.writeString(nested.resolve("SKILL.md"), "---\nname: round-tower\ndescription: >\n  盖圆塔。\n  要塔的时候用。\nlicense: MIT\n---\n\n正文\n");
        Path unnamed = Files.createDirectories(directory.resolve("bridge-builder"));
        Files.writeString(unnamed.resolve("SKILL.md"), "---\ndescription: '造桥'\n---\n正文\n");
        Files.writeString(Files.createDirectories(directory.resolve("no-front-matter")).resolve("SKILL.md"), "# 只有正文\n");
        Files.writeString(Files.createDirectories(directory.resolve("no-description")).resolve("SKILL.md"), "---\nname: x\n---\n正文\n");
        var library = new SkillLibrary(directory);
        assertEquals(List.of(new SkillLibrary.Skill("bridge-builder", "造桥", unnamed),
                new SkillLibrary.Skill("round-tower", "盖圆塔。 要塔的时候用。", nested)), library.list());
        // 存一个同名的：改的是原来那个目录，不另起一个。
        library.save("round-tower", "新说明", "新正文", List.of());
        assertEquals(nested, library.list().get(1).directory());
        assertTrue(Files.notExists(directory.resolve("round-tower")));
    }
}
