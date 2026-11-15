package dev.kizuna.agent.bridge;

import com.google.gson.JsonObject;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class BridgeServerTest {
    @Test
    void onlyTheExactBearerTokenIsAccepted() {
        assertTrue(BridgeServer.authorized("Bearer abc123", "abc123"));
        assertFalse(BridgeServer.authorized("Bearer abc12", "abc123"));
        assertFalse(BridgeServer.authorized("abc123", "abc123"));
        assertFalse(BridgeServer.authorized("Basic abc123", "abc123"));
        assertFalse(BridgeServer.authorized(null, "abc123"));
    }

    @Test
    void bodyIsAnObjectOrEmpty() throws Exception {
        // 空请求体当空对象；数组、写坏的 JSON 报 400。
        assertEquals(new JsonObject(), BridgeServer.body(stream("  ")));
        assertEquals("a", BridgeServer.body(stream("{\"x\": \"a\"}")).get("x").getAsString());
        assertEquals(400, assertThrows(BridgeException.class, () -> BridgeServer.body(stream("[1]"))).status());
        assertEquals(400, assertThrows(BridgeException.class, () -> BridgeServer.body(stream("{\"x\": "))).status());
    }

    @Test
    void integersOnly() {
        // 坐标只收整数：小数、字符串都拒，免得悄悄取整放错位置。
        var request = BridgeServer.bodyOf("{\"a\": 3, \"b\": 1.5, \"c\": \"3\", \"d\": -7.0}");
        assertEquals(3, Requests.integer(request, "a", 0));
        assertEquals(-7, Requests.integer(request, "d", 0));
        assertEquals(9, Requests.integer(request, "missing", 9));
        assertThrows(BridgeException.class, () -> Requests.integer(request, "b", 0));
        assertThrows(BridgeException.class, () -> Requests.integer(request, "c", 0));
    }

    private static ByteArrayInputStream stream(String text) {
        return new ByteArrayInputStream(text.getBytes(StandardCharsets.UTF_8));
    }
}
