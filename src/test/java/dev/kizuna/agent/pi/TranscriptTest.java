package dev.kizuna.agent.pi;

import com.google.gson.JsonParser;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TranscriptTest {
    private final List<Transcript.Line> lines = new ArrayList<>();
    private final Transcript transcript = new Transcript(lines::add);

    @Test
    void streamedTextComesOutLineByLine() {
        // 回答一点点流出来：攒到换行出一行，段落结束把剩下的出掉；空行不出。
        delta("第一行说了");
        assertTrue(lines.isEmpty());
        delta("一半\n\n第二");
        assertEquals(List.of(new Transcript.Line(Transcript.Kind.TEXT, "第一行说了一半")), lines);
        delta("行");
        feed("{\"type\":\"message_update\",\"assistantMessageEvent\":{\"type\":\"text_end\",\"contentIndex\":0,\"content\":\"…\"}}");
        assertEquals(new Transcript.Line(Transcript.Kind.TEXT, "第二行"), lines.get(1));
        assertEquals(2, lines.size());
    }

    @Test
    void toolCallsShowWhatIsBeingDoneAndFailuresShowWhy() {
        feed("{\"type\":\"tool_execution_start\",\"toolCallId\":\"c1\",\"toolName\":\"mc_fill\",\"args\":{\"from\":[0,64,0],\"to\":[4,64,4],\"block\":\"minecraft:stone\"}}");
        feed("{\"type\":\"tool_execution_end\",\"toolCallId\":\"c1\",\"toolName\":\"mc_fill\",\"result\":{\"content\":[{\"type\":\"text\",\"text\":\"{}\"}]},\"isError\":false}");
        feed("{\"type\":\"tool_execution_start\",\"toolCallId\":\"c2\",\"toolName\":\"mc_command\",\"args\":{\"command\":\"/time set day\"}}");
        feed("{\"type\":\"tool_execution_end\",\"toolCallId\":\"c2\",\"toolName\":\"mc_command\",\"result\":{\"content\":[{\"type\":\"text\",\"text\":\"方块写法不对\\n第二行\"}]},\"isError\":true}");
        assertEquals(List.of(new Transcript.Line(Transcript.Kind.TOOL, "填充：minecraft:stone，[0, 64, 0] 到 [4, 64, 4]"),
                new Transcript.Line(Transcript.Kind.TOOL, "执行指令：/time set day"),
                new Transcript.Line(Transcript.Kind.ERROR, "这一步没成：方块写法不对")), lines);
    }

    @Test
    void modelErrorsAreSaidOnceWhetherOrNotRetried() {
        // 不重试的错（密钥不对）在这一轮结束时说一次；会重试的由重试提示去说，重试用尽再说一次，不重复。
        feed("{\"type\":\"message_end\",\"message\":{\"role\":\"assistant\",\"stopReason\":\"error\",\"errorMessage\":\"401 invalid key\"}}");
        feed("{\"type\":\"agent_end\",\"messages\":[],\"willRetry\":false}");
        assertEquals(List.of(new Transcript.Line(Transcript.Kind.ERROR, "模型接口出错：401 invalid key")), lines);
        lines.clear();
        feed("{\"type\":\"message_end\",\"message\":{\"role\":\"assistant\",\"stopReason\":\"error\",\"errorMessage\":\"503 busy\"}}");
        feed("{\"type\":\"agent_end\",\"messages\":[],\"willRetry\":true}");
        feed("{\"type\":\"auto_retry_start\",\"attempt\":1,\"maxAttempts\":3,\"delayMs\":2000,\"errorMessage\":\"503 busy\"}");
        feed("{\"type\":\"message_end\",\"message\":{\"role\":\"assistant\",\"stopReason\":\"error\",\"errorMessage\":\"503 busy\"}}");
        feed("{\"type\":\"agent_end\",\"messages\":[],\"willRetry\":false}");
        feed("{\"type\":\"auto_retry_end\",\"success\":false,\"attempt\":3,\"finalError\":\"503 busy\"}");
        assertEquals(List.of(new Transcript.Line(Transcript.Kind.NOTE, "模型接口出错，第 1 次重试：503 busy"),
                new Transcript.Line(Transcript.Kind.ERROR, "重试几次都没成：503 busy")), lines);
    }

    @Test
    void abortAndRejectedCommandsAreReported() {
        feed("{\"type\":\"message_end\",\"message\":{\"role\":\"assistant\",\"stopReason\":\"aborted\"}}");
        feed("{\"type\":\"response\",\"command\":\"prompt\",\"success\":false,\"error\":\"already streaming\"}");
        feed("{\"type\":\"response\",\"command\":\"prompt\",\"success\":true}");
        feed("{\"type\":\"turn_start\"}");
        assertEquals(List.of(new Transcript.Line(Transcript.Kind.NOTE, "已停下"),
                new Transcript.Line(Transcript.Kind.ERROR, "助手没接受这次操作：already streaming")), lines);
    }

    @Test
    void rpcLinesSplitOnLineFeedOnly() {
        // 只按 \n 分行，行尾的 \r 去掉；JSON 字符串里合法出现的 U+2028 不当换行；半行等下一段。
        var framing = new JsonLines();
        assertEquals(List.of("{\"a\":\"x y\"}"), framing.feed("{\"a\":\"x y\"}\r\n{\"b\""));
        assertEquals(List.of("{\"b\":1}", "{\"c\":2}"), framing.feed(":1}\n\n{\"c\":2}\n"));
    }

    private void delta(String text) {
        var event = JsonParser.parseString("{\"type\":\"message_update\",\"assistantMessageEvent\":{\"type\":\"text_delta\",\"contentIndex\":0}}").getAsJsonObject();
        event.getAsJsonObject("assistantMessageEvent").addProperty("delta", text);
        transcript.accept(event);
    }

    private void feed(String json) {
        transcript.accept(JsonParser.parseString(json).getAsJsonObject());
    }
}
