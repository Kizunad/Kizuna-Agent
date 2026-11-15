package dev.kizuna.agent.bridge;

import com.google.gson.JsonObject;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileSystem;
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class JarInspectorTest {
    @TempDir
    Path directory;
    private FileSystem jar;
    private Map<String, BridgeServer.Call> calls;

    @BeforeEach
    void buildJar() throws Exception {
        // 一个真的 JAR：语言文件、配置、一个类文件、一个二进制文件。
        Path file = directory.resolve("demo.jar");
        try (var zip = new ZipOutputStream(Files.newOutputStream(file))) {
            entry(zip, "fabric.mod.json", "{\"id\": \"demo\"}".getBytes(StandardCharsets.UTF_8));
            entry(zip, "assets/demo/lang/zh_cn.json", "{\n  \"item.demo.sword\": \"青锋剑\",\n  \"item.demo.bow\": \"柘木弓\"\n}\n".getBytes(StandardCharsets.UTF_8));
            entry(zip, "assets/demo/lang/en_us.json", "{\n  \"item.demo.sword\": \"Verdant Sword\"\n}\n".getBytes(StandardCharsets.UTF_8));
            entry(zip, "data/demo/config.toml", "speed = 3\n".getBytes(StandardCharsets.UTF_8));
            entry(zip, "assets/demo/icon.png", new byte[]{(byte) 0x89, 'P', 'N', 'G', 0, 0, 0, 1});
            try (InputStream input = Box.class.getResourceAsStream("Box.class")) {
                entry(zip, "dev/kizuna/agent/bridge/Box.class", input.readAllBytes());
            }
        }
        jar = FileSystems.newFileSystem(file);
        var mod = new JarInspector.Mod("demo", "1.0", "Demo", Optional.of(file), Optional.empty(), List.of(jar.getPath("/")));
        calls = new JarInspector(List.of(mod)).calls();
    }

    @AfterEach
    void closeJar() throws Exception {
        jar.close();
    }

    @Test
    void listsFilesByPrefixAndGlobAndSaysWhenCutShort() {
        JsonObject all = call("jar/list", "{\"mod\": \"demo\"}");
        assertEquals(6, all.get("total").getAsInt());
        JsonObject lang = call("jar/list", "{\"mod\": \"demo\", \"glob\": \"**/lang/*.json\"}");
        assertEquals(2, lang.getAsJsonArray("entries").size());
        JsonObject data = call("jar/list", "{\"mod\": \"demo\", \"prefix\": \"data/\"}");
        assertEquals("data/demo/config.toml", data.getAsJsonArray("entries").get(0).getAsJsonObject().get("path").getAsString());
        JsonObject cut = call("jar/list", "{\"mod\": \"demo\", \"limit\": 2}");
        assertTrue(cut.get("truncated").getAsBoolean());
        assertEquals(2, cut.getAsJsonArray("entries").size());
        assertEquals(6, cut.get("total").getAsInt());
    }

    @Test
    void readsTextInPiecesAndRefusesBinaryAndEscapes() {
        JsonObject whole = call("jar/read", "{\"mod\": \"demo\", \"path\": \"assets/demo/lang/zh_cn.json\"}");
        assertTrue(whole.get("text").getAsString().contains("青锋剑"));
        assertFalse(whole.get("truncated").getAsBoolean());
        JsonObject piece = call("jar/read", "{\"mod\": \"demo\", \"path\": \"/fabric.mod.json\", \"offset\": 1, \"max_bytes\": 4}");
        assertEquals("\"id\"", piece.get("text").getAsString());
        assertTrue(piece.get("truncated").getAsBoolean());
        assertEquals(400, rejected("jar/read", "{\"mod\": \"demo\", \"path\": \"assets/demo/icon.png\"}").status());
        assertEquals(404, rejected("jar/read", "{\"mod\": \"demo\", \"path\": \"../demo.jar\"}").status());
        assertEquals(404, rejected("jar/read", "{\"mod\": \"demo\", \"path\": \"no/such.json\"}").status());
        assertEquals(400, rejected("jar/read", "{\"mod\": \"other\", \"path\": \"fabric.mod.json\"}").status());
    }

    @Test
    void searchFindsLinesAcrossTextFilesOnly() {
        // 找词：给出文件、行号和那一行；不分大小写是默认；二进制文件不搜。
        JsonObject chinese = call("jar/search", "{\"text\": \"青锋\"}");
        var match = chinese.getAsJsonArray("matches").get(0).getAsJsonObject();
        assertEquals("assets/demo/lang/zh_cn.json", match.get("path").getAsString());
        assertEquals(2, match.get("line").getAsInt());
        assertEquals(2, call("jar/search", "{\"text\": \"SWORD\"}").get("total").getAsInt());
        assertEquals(0, call("jar/search", "{\"text\": \"SWORD\", \"case_sensitive\": true}").get("total").getAsInt());
        assertEquals(1, call("jar/search", "{\"text\": \"sword\", \"glob\": \"**/en_us.json\"}").get("total").getAsInt());
        assertEquals(0, call("jar/search", "{\"text\": \"PNG\"}").get("total").getAsInt());
    }

    @Test
    void classSignaturesWithoutPrivateMembersByDefault() {
        JsonObject listed = call("jar/classes", "{\"mod\": \"demo\", \"prefix\": \"dev.kizuna\"}");
        assertEquals("dev.kizuna.agent.bridge.Box", listed.getAsJsonArray("classes").get(0).getAsString());
        JsonObject signature = call("jar/class", "{\"mod\": \"demo\", \"class\": \"dev.kizuna.agent.bridge.Box\"}");
        assertEquals("record", signature.get("kind").getAsString());
        assertTrue(signature.getAsJsonArray("methods").toString().contains("long volume()"));
        assertTrue(signature.getAsJsonArray("methods").toString().contains("static dev.kizuna.agent.bridge.Box of(net.minecraft.core.BlockPos, net.minecraft.core.BlockPos)"));
        assertFalse(signature.getAsJsonArray("fields").toString().contains("min"));
        JsonObject everything = call("jar/class", "{\"mod\": \"demo\", \"class\": \"dev.kizuna.agent.bridge.Box\", \"private\": true}");
        assertTrue(everything.getAsJsonArray("fields").toString().contains("private final net.minecraft.core.BlockPos min"));
    }

    private JsonObject call(String name, String body) {
        return calls.get(name).handle(BridgeServer.bodyOf(body));
    }

    private BridgeException rejected(String name, String body) {
        return assertThrows(BridgeException.class, () -> call(name, body));
    }

    private static void entry(ZipOutputStream zip, String name, byte[] content) throws Exception {
        zip.putNextEntry(new ZipEntry(name));
        zip.write(content);
        zip.closeEntry();
    }
}
