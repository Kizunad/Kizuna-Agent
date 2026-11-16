package dev.kizuna.agent.pi;

import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 照这次的配置拼出系统提示词：模板在 JAR 里，这里填上实例里的真实路径，并按「能不能动这台电脑」留下对应的段落。
 *
 * <p>模板里的写法：{@code {{名字}}} 换成值；{@code {{#local}}…{{/local}}} 只在能动电脑时留下，
 * {@code {{^local}}…{{/local}}} 只在不能时留下。</p>
 */
public final class SystemPrompt {
    private static final Pattern SECTION = Pattern.compile("\\{\\{([#^])local}}\\n(.*?)\\{\\{/local}}\\n", Pattern.DOTALL);
    private static final Pattern VALUE = Pattern.compile("\\{\\{([a-z_]+)}}");

    private SystemPrompt() {
        // 只有静态方法。
    }

    /**
     * @param template 模板全文
     * @param localCode 助手能动这台电脑（读写文件、跑 Python 和命令）
     * @param values 模板里各个名字的值
     * @throws IllegalArgumentException 模板里有没给值的名字
     */
    public static String build(String template, boolean localCode, Map<String, String> values) {
        Matcher section = SECTION.matcher(template);
        String chosen = section.replaceAll(match -> Matcher.quoteReplacement(match.group(1).equals("#") == localCode ? match.group(2) : ""));
        Matcher value = VALUE.matcher(chosen);
        return value.replaceAll(match -> {
            String replacement = values.get(match.group(1));
            if (replacement == null) {
                throw new IllegalArgumentException("系统提示词模板里的 {{" + match.group(1) + "}} 没有给值");
            }
            return Matcher.quoteReplacement(replacement);
        });
    }
}
