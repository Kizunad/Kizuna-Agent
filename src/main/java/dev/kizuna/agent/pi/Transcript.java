package dev.kizuna.agent.pi;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

import java.util.function.Consumer;

/**
 * 把 pi 的事件流变成一行行给人看的话。
 *
 * <p>模型的回答是一点点流出来的：攒到换行就出一行，一段结束把剩下的出掉。工具调用出一行说明在做什么，
 * 工具出错、模型接口出错各出一行。思考过程不显示。</p>
 */
public final class Transcript {
    public enum Kind { TEXT, TOOL, NOTE, ERROR }

    public record Line(Kind kind, String text) {
    }

    private static final int MAX_ERROR = 300;

    private final Consumer<Line> sink;
    private final StringBuilder text = new StringBuilder();
    private String failure;
    private boolean retrying;

    public Transcript(Consumer<Line> sink) {
        this.sink = sink;
    }

    public void accept(JsonObject event) {
        switch (string(event, "type")) {
            case "message_update" -> update(event.getAsJsonObject("assistantMessageEvent"));
            case "message_end" -> messageEnd(event.getAsJsonObject("message"));
            case "tool_execution_start" -> sink.accept(new Line(Kind.TOOL,
                    ToolLabels.describe(string(event, "toolName"), object(event, "args"))));
            case "tool_execution_end" -> toolEnd(event);
            case "agent_end" -> runEnd(event);
            case "auto_retry_start" -> {
                retrying = true;
                sink.accept(new Line(Kind.NOTE, "模型接口出错，第 " + event.get("attempt").getAsInt() + " 次重试："
                        + clip(string(event, "errorMessage"))));
            }
            case "auto_retry_end" -> {
                retrying = false;
                failure = null;
                if (!event.get("success").getAsBoolean()) {
                    sink.accept(new Line(Kind.ERROR, "重试几次都没成：" + clip(string(event, "finalError"))));
                }
            }
            case "compaction_start" -> sink.accept(new Line(Kind.NOTE, "对话太长，正在压缩前文…"));
            case "extension_error" -> sink.accept(new Line(Kind.ERROR, "助手的工具出错：" + clip(string(event, "error"))));
            case "response" -> {
                if (!event.get("success").getAsBoolean()) {
                    sink.accept(new Line(Kind.ERROR, "助手没接受这次操作：" + clip(string(event, "error"))));
                }
            }
            default -> {
                // 其余事件（回合开始结束、队列变化等）不用给人看。
            }
        }
    }

    private void update(JsonObject delta) {
        switch (string(delta, "type")) {
            case "text_delta" -> {
                text.append(string(delta, "delta"));
                flush(false);
            }
            case "text_end" -> flush(true);
            default -> {
                // 思考和工具调用参数的增量不显示。
            }
        }
    }

    private void flush(boolean all) {
        // 出掉已经完整的行；all 时连最后没换行的那半行也出掉。
        int newline;
        while ((newline = text.indexOf("\n")) >= 0) {
            emit(text.substring(0, newline));
            text.delete(0, newline + 1);
        }
        if (all) {
            emit(text.toString());
            text.setLength(0);
        }
    }

    private void emit(String line) {
        if (!line.isBlank()) {
            sink.accept(new Line(Kind.TEXT, line.stripTrailing()));
        }
    }

    private void messageEnd(JsonObject message) {
        // 出错的回答先记着：还会自动重试的由重试事件去说，不重试的等这一轮结束时说。
        if (message == null || !"assistant".equals(string(message, "role"))) {
            return;
        }
        flush(true);
        switch (string(message, "stopReason")) {
            case "aborted" -> sink.accept(new Line(Kind.NOTE, "已停下"));
            case "error" -> failure = string(message, "errorMessage");
            default -> failure = null;
        }
    }

    private void runEnd(JsonObject event) {
        // 一轮结束而且不会再重试：把记着的错说出来。重试用尽的由 auto_retry_end 说，不重复。
        boolean willRetry = event.has("willRetry") && event.get("willRetry").getAsBoolean();
        if (failure != null && !willRetry && !retrying) {
            sink.accept(new Line(Kind.ERROR, "模型接口出错：" + clip(failure)));
        }
        if (!willRetry) {
            failure = null;
        }
    }

    private void toolEnd(JsonObject event) {
        if (!event.get("isError").getAsBoolean()) {
            return;
        }
        String detail = "";
        JsonObject result = object(event, "result");
        if (result.has("content") && result.get("content").isJsonArray()) {
            for (JsonElement part : result.getAsJsonArray("content")) {
                if (part.isJsonObject() && "text".equals(string(part.getAsJsonObject(), "type"))) {
                    detail = string(part.getAsJsonObject(), "text");
                    break;
                }
            }
        }
        sink.accept(new Line(Kind.ERROR, "这一步没成：" + clip(detail.strip().lines().findFirst().orElse(""))));
    }

    static String string(JsonObject object, String name) {
        JsonElement value = object == null ? null : object.get(name);
        return value != null && value.isJsonPrimitive() ? value.getAsString() : "";
    }

    private static JsonObject object(JsonObject parent, String name) {
        JsonElement value = parent.get(name);
        return value != null && value.isJsonObject() ? value.getAsJsonObject() : new JsonObject();
    }

    static String clip(String message) {
        String single = message.replace('\n', ' ').strip();
        return single.length() <= MAX_ERROR ? single : single.substring(0, MAX_ERROR) + "…";
    }
}
