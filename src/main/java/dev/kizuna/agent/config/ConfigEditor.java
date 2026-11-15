package dev.kizuna.agent.config;

import org.tomlj.Toml;
import org.tomlj.TomlArray;
import org.tomlj.TomlParseResult;
import org.tomlj.TomlPosition;
import org.tomlj.TomlTable;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 把游戏里改的思考档位写回配置文件，只动那一行，注释和别的内容原样留着。
 *
 * <p>清单里的模型改它那一段 {@code [[models]]} 的 {@code thinking}（没有这一行就加在那一段开头）；
 * 不在清单里的模型改 {@code [model]} 的 {@code thinking}。</p>
 */
public final class ConfigEditor {
    private static final Pattern VALUE = Pattern.compile("^(\\s*thinking\\s*=\\s*)(\"[^\"]*\"|'[^']*')");

    private ConfigEditor() {
        // 只有静态方法。
    }

    /** 改文件；文件已经被改得找不到这个模型、或写法读不了时抛 {@link IllegalArgumentException}，文件不动。 */
    public static void saveThinking(Path file, ModelChoice model, String level) throws IOException {
        Files.writeString(file, withThinking(Files.readString(file), model, level));
    }

    /** 配置全文 → 把这个模型的默认思考档位改成 {@code level} 以后的全文。 */
    public static String withThinking(String text, ModelChoice model, String level) {
        TomlParseResult parsed = Toml.parse(text);
        if (parsed.hasErrors()) {
            throw new IllegalArgumentException(AgentConfig.FILE_NAME + " 写法有错：" + parsed.errors().getFirst());
        }
        var lines = new ArrayList<>(List.of(text.split("\n", -1)));
        if (model.listed()) {
            TomlTable entry = entry(parsed, model);
            if (entry.contains("thinking")) {
                replace(lines, entry.inputPositionOf("thinking"), level);
            } else {
                // 加在这一段第一个键的前面，缩进照它的。
                TomlPosition first = entry.keySet().stream().map(entry::inputPositionOf)
                        .min((left, right) -> Integer.compare(left.line(), right.line())).orElseThrow();
                String anchor = lines.get(first.line() - 1);
                String indent = anchor.substring(0, anchor.length() - anchor.stripLeading().length());
                lines.add(first.line() - 1, indent + "thinking = \"" + level + "\"" + ending(anchor));
            }
        } else if (parsed.contains("model.thinking")) {
            replace(lines, parsed.inputPositionOf("model.thinking"), level);
        } else if (parsed.isTable("model")) {
            TomlTable table = parsed.getTable("model");
            int last = table.keySet().stream().mapToInt(key -> table.inputPositionOf(key).line()).max()
                    .orElseThrow(() -> new IllegalArgumentException(AgentConfig.FILE_NAME + " 的 [model] 是空的"));
            lines.add(last, "thinking = \"" + level + "\"" + ending(lines.get(last - 1)));
        } else {
            throw new IllegalArgumentException(AgentConfig.FILE_NAME + " 里没有 [model]");
        }
        return String.join("\n", lines);
    }

    private static TomlTable entry(TomlParseResult parsed, ModelChoice model) {
        TomlArray models = parsed.isArray("models") ? parsed.getArray("models") : null;
        for (int index = 0; models != null && index < models.size(); index++) {
            if (models.get(index) instanceof TomlTable entry && entry.isString("id")) {
                String alias = entry.isString("alias") ? entry.getString("alias").strip() : entry.getString("id").strip();
                if (alias.equals(model.alias())) {
                    return entry;
                }
            }
        }
        throw new IllegalArgumentException(AgentConfig.FILE_NAME + " 的模型清单里找不到 " + model.alias());
    }

    /** 新加的一行跟着旁边那行用同一种换行（Windows 上记事本存的是 \\r\\n）。 */
    private static String ending(String neighbour) {
        return neighbour.endsWith("\r") ? "\r" : "";
    }

    private static void replace(List<String> lines, TomlPosition key, String level) {
        int index = key.line() - 1;
        Matcher matcher = VALUE.matcher(lines.get(index));
        if (!matcher.find()) {
            throw new IllegalArgumentException(AgentConfig.FILE_NAME + " 第 " + key.line() + " 行的 thinking 写法改不了，请手改成 \"" + level + "\"");
        }
        lines.set(index, matcher.replaceFirst(Matcher.quoteReplacement(matcher.group(1) + "\"" + level + "\"")));
    }
}
