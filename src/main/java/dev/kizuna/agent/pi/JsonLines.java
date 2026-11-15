package dev.kizuna.agent.pi;

import java.util.ArrayList;
import java.util.List;

/**
 * pi 的 RPC 分行：一条记录一行，只认 {@code \n} 作分隔，行尾多出来的 {@code \r} 去掉。
 *
 * <p>不用通用的按行读取：那会把 JSON 字符串里合法出现的别的换行符也当成分隔。</p>
 */
final class JsonLines {
    private final StringBuilder pending = new StringBuilder();

    /** 喂进一段刚读到的文字，返回其中已经完整的各行（不含空行）。 */
    List<String> feed(CharSequence chunk) {
        var lines = new ArrayList<String>();
        for (int index = 0; index < chunk.length(); index++) {
            char character = chunk.charAt(index);
            if (character != '\n') {
                pending.append(character);
                continue;
            }
            int end = pending.length();
            if (end > 0 && pending.charAt(end - 1) == '\r') {
                end--;
            }
            if (end > 0) {
                lines.add(pending.substring(0, end));
            }
            pending.setLength(0);
        }
        return lines;
    }
}
