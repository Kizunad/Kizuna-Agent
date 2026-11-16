package dev.kizuna.agent.notes;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * 助手的全局记忆：实例里一个 Markdown 文件，一条一行，每段新对话都带上。
 *
 * <p>文件可以手改：以「- 」开头的行各算一条，别的行不算。助手增删时整个文件按「说明 + 各条」重写。
 * 条数和每条的长度都有上限，免得越记越长、每句话都拖着一大段。</p>
 */
public final class MemoryFile {
    /** 最多记多少条。 */
    public static final int MAX_ENTRIES = 50;
    /** 一条最多多少个字。 */
    public static final int MAX_ENTRY_CHARS = 300;
    /** 记到这么多条就提醒整理。 */
    public static final int NEARLY_FULL = 40;
    private static final String BULLET = "- ";
    private static final String HEADER = """
            # 助手的记忆

            每行一条，以「- 」开头。可以直接改这个文件；助手自己用 memory_save、memory_forget 增删。

            """;

    private final Path file;

    public MemoryFile(Path file) {
        this.file = file;
    }

    public Path file() {
        return file;
    }

    /** 现在记着的各条，先记的在前。 */
    public synchronized List<String> list() {
        if (Files.notExists(file)) {
            return List.of();
        }
        try {
            return Files.readAllLines(file).stream().map(String::strip).filter(line -> line.startsWith(BULLET))
                    .map(line -> line.substring(BULLET.length()).strip()).filter(entry -> !entry.isEmpty()).toList();
        } catch (IOException failed) {
            throw new UncheckedIOException(failed);
        }
    }

    /**
     * 记一条，返回它是第几条。一模一样的已经记着就不重复记，返回原来那条的序号。
     *
     * @throws IllegalArgumentException 内容是空的、太长，或者已经记满
     */
    public synchronized int save(String text) {
        String entry = text.lines().map(String::strip).filter(line -> !line.isEmpty()).reduce((left, right) -> left + " " + right)
                .orElse("");
        if (entry.isEmpty()) {
            throw new IllegalArgumentException("要记的内容是空的");
        }
        if (entry.length() > MAX_ENTRY_CHARS) {
            throw new IllegalArgumentException("一条记忆最多 " + MAX_ENTRY_CHARS + " 个字，这条有 " + entry.length() + " 个字；写短一些，或者拆成几条");
        }
        var entries = new ArrayList<>(list());
        int existing = entries.indexOf(entry);
        if (existing >= 0) {
            return existing + 1;
        }
        if (entries.size() >= MAX_ENTRIES) {
            throw new IllegalArgumentException("记忆已有 " + entries.size() + " 条，到上限了。先用 memory_forget 删掉过时的，或者把几条合并成一条再记");
        }
        entries.add(entry);
        write(entries);
        return entries.size();
    }

    /**
     * 删掉第 {@code number} 条（从 1 数起），返回删掉的内容。
     *
     * @throws IllegalArgumentException 没有这一条
     */
    public synchronized String forget(int number) {
        var entries = new ArrayList<>(list());
        if (number < 1 || number > entries.size()) {
            throw new IllegalArgumentException(entries.isEmpty() ? "现在一条记忆都没有"
                    : "没有第 " + number + " 条记忆，现在有 1 到 " + entries.size() + " 条");
        }
        String removed = entries.remove(number - 1);
        write(entries);
        return removed;
    }

    private void write(List<String> entries) {
        var text = new StringBuilder(HEADER);
        entries.forEach(entry -> text.append(BULLET).append(entry).append('\n'));
        try {
            Files.createDirectories(file.getParent());
            Files.writeString(file, text);
        } catch (IOException failed) {
            throw new UncheckedIOException(failed);
        }
    }
}
