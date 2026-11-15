package dev.kizuna.agent.config;

import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 思考档位的名字和先后，以及一个模型没写 {@code thinking_levels} 时用的那张表。
 *
 * <p>一张档位表的键是这个模型认的档位，值是这一档发给接口的 {@code reasoning_effort}；值为空串表示这一档不发参数。</p>
 */
public final class ThinkingLevels {
    public static final String OFF = "off";
    /** 全部档位，从少到多。 */
    public static final List<String> ALL = List.of(OFF, "minimal", "low", "medium", "high", "xhigh", "max");
    /** 没写档位表的模型：off 不发参数，其余四档照原名发。 */
    public static final Map<String, String> STANDARD = standard();

    private ThinkingLevels() {
        // 只有常量和静态方法。
    }

    /** 把想要的档位落到模型认的档位里：认就原样用，不认就取最近的一档（一样近取少的）。表为空时原样返回。 */
    public static String fit(String wanted, Collection<String> available) {
        if (available.isEmpty() || available.contains(wanted)) {
            return wanted;
        }
        int target = ALL.indexOf(wanted);
        String best = null;
        int bestDistance = Integer.MAX_VALUE;
        for (String level : ALL) {
            int distance = Math.abs(ALL.indexOf(level) - target);
            if (available.contains(level) && distance < bestDistance) {
                best = level;
                bestDistance = distance;
            }
        }
        return best;
    }

    private static Map<String, String> standard() {
        var levels = new LinkedHashMap<String, String>();
        levels.put(OFF, "");
        for (String level : List.of("minimal", "low", "medium", "high")) {
            levels.put(level, level);
        }
        return Collections.unmodifiableMap(levels);
    }
}
