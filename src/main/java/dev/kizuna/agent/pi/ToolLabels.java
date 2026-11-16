package dev.kizuna.agent.pi;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

/** 工具调用显示成的那一行：在做什么、对着什么。 */
final class ToolLabels {
    private static final int MAX_DETAIL = 90;

    private ToolLabels() {
        // 只有静态描述方法。
    }

    static String describe(String tool, JsonObject args) {
        return switch (tool) {
            case "mc_command" -> "执行指令：/" + clip(text(args, "command").replaceFirst("^/", ""));
            case "mc_player" -> "看玩家位置";
            case "mc_get_blocks" -> "读方块：" + text(args, "from") + " 到 " + text(args, "to");
            case "mc_set_blocks" -> "放方块：" + size(args, "blocks") + " 个，起点 " + text(args, "origin");
            case "mc_fill" -> "填充：" + clip(text(args, "block")) + "，" + text(args, "from") + " 到 " + text(args, "to");
            case "mc_clone" -> "复制区域：" + text(args, "from") + " 到 " + text(args, "to") + "，放到 " + text(args, "destination");
            case "mc_undo" -> "撤回上一次放的方块";
            case "mc_screenshot" -> "截图：" + shot(args);
            case "mc_marks" -> "看玩家标的点";
            case "memory_save" -> "记住：" + clip(text(args, "text"));
            case "memory_forget" -> "忘掉第 " + text(args, "number") + " 条记忆";
            case "skill_save" -> "存技能：" + clip(text(args, "name"));
            case "skill_read" -> "读技能：" + clip(text(args, "name") + (args.has("file") ? " " + text(args, "file") : ""));
            case "skill_delete" -> "删技能：" + clip(text(args, "name"));
            case "mc_mods" -> "看模组列表";
            case "mc_jar_list" -> "列 JAR 文件：" + text(args, "mod") + " " + clip(text(args, "prefix") + text(args, "glob"));
            case "mc_jar_read" -> "读 JAR：" + text(args, "mod") + " " + clip(text(args, "path"));
            case "mc_jar_search" -> "搜 JAR：「" + clip(text(args, "text")) + "」" + (args.has("mod") ? "，在 " + text(args, "mod") : "");
            case "mc_jar_classes" -> "列类：" + text(args, "mod") + " " + clip(text(args, "prefix"));
            case "mc_jar_class" -> "看类签名：" + clip(text(args, "class"));
            case "run_python" -> args.has("file") ? "跑 Python 脚本：" + clip(text(args, "file"))
                    : "跑 Python 脚本（" + text(args, "code").lines().count() + " 行）";
            case "read" -> "读文件：" + clip(text(args, "path"));
            case "write" -> "写文件：" + clip(text(args, "path"));
            case "edit" -> "改文件：" + clip(text(args, "path"));
            case "bash", "powershell" -> "跑命令：" + clip(text(args, "command"));
            default -> "用工具：" + tool;
        };
    }

    private static String shot(JsonObject args) {
        // 拍的是哪儿：绕着一个点、指定机位，还是玩家眼前。
        if (args.has("orbit") && args.get("orbit").isJsonObject()) {
            return "绕 " + text(args.getAsJsonObject("orbit"), "target") + " 拍一圈";
        }
        if (args.has("camera") && args.get("camera").isJsonObject()) {
            return "机位 " + text(args.getAsJsonObject("camera"), "position");
        }
        return args.has("hud") && args.get("hud").isJsonPrimitive() && args.get("hud").getAsBoolean() ? "玩家眼前的画面（含界面）"
                : "玩家的视角";
    }

    private static String text(JsonObject args, String name) {
        // 字符串原样；坐标这类数组写成 [x, y, z]。
        JsonElement value = args.get(name);
        if (value == null || value.isJsonNull()) {
            return "";
        }
        return value.isJsonPrimitive() ? value.getAsString() : value.toString().replace(",", ", ");
    }

    private static int size(JsonObject args, String name) {
        JsonElement value = args.get(name);
        return value != null && value.isJsonArray() ? value.getAsJsonArray().size() : 0;
    }

    private static String clip(String detail) {
        String single = detail.replace('\n', ' ').strip();
        return single.length() <= MAX_DETAIL ? single : single.substring(0, MAX_DETAIL) + "…";
    }
}
