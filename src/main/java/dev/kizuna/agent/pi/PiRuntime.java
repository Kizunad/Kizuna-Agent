package dev.kizuna.agent.pi;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Comparator;
import java.util.List;
import java.util.Properties;
import java.util.stream.Stream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

/**
 * 把 JAR 里带的 pi 运行文件、pi 扩展、Python 库解到实例的数据目录。
 *
 * <p>pi 按版本分目录，解完才写完成标记：解到一半断掉的下次重解，换了版本的 JAR 自动解新的。
 * 扩展、Python 库和示例每次都用 JAR 里的覆盖，保证和模组同版本；玩家自己的脚本放在 workspace 里，不会被动。</p>
 */
public final class PiRuntime {
    /** 解好以后各样东西的位置。 */
    public record Layout(String piVersion, Path entry, Path extension, Path python, Path home, Path sessions,
                         Path workspace) {
    }

    private static final String RESOURCES = "/kizuna-agent/";
    private static final String COMPLETE = ".complete";
    /** 每次进世界都从 JAR 覆盖到数据目录的文件。 */
    private static final List<String> COPIED = List.of("extension/kizuna-mc.ts", "python/kizuna_mc.py",
            "python/examples/tower.py", "python/examples/dump_region.py", "python/examples/find_in_jars.py");
    private static final String SYSTEM_PROMPT = "system-prompt.md";

    private PiRuntime() {
        // 只有静态准备方法。
    }

    public static String piVersion() {
        var properties = new Properties();
        try (InputStream input = resource("runtime.properties")) {
            properties.load(input);
        } catch (IOException failed) {
            throw new IllegalStateException("读不了 JAR 里的 runtime.properties", failed);
        }
        return properties.getProperty("pi");
    }

    public static Layout prepare(Path data) throws IOException {
        String version = piVersion();
        Path pi = data.resolve("pi-" + version);
        if (Files.notExists(pi.resolve(COMPLETE))) {
            extract("pi-" + version + ".zip", pi);
        }
        for (String name : COPIED) {
            copy(name, data.resolve(name));
        }
        Path home = data.resolve("pi-home");
        // pi 把配置目录里的 SYSTEM.md 当系统提示词。
        copy(SYSTEM_PROMPT, home.resolve("SYSTEM.md"));
        Path sessions = Files.createDirectories(data.resolve("sessions"));
        Path workspace = Files.createDirectories(data.resolve("workspace"));
        return new Layout(version, pi.resolve("dist/bundle/rpc-entry.js"), data.resolve("extension/kizuna-mc.ts"),
                data.resolve("python"), home, sessions, workspace);
    }

    private static void extract(String archive, Path target) throws IOException {
        // 先解到旁边的临时目录，全部解完再改名并写完成标记。
        Path staging = target.resolveSibling(target.getFileName() + ".partial");
        delete(staging);
        delete(target);
        Files.createDirectories(staging);
        try (var zip = new ZipInputStream(resource(archive))) {
            for (ZipEntry entry = zip.getNextEntry(); entry != null; entry = zip.getNextEntry()) {
                Path file = staging.resolve(entry.getName()).normalize();
                if (!file.startsWith(staging)) {
                    throw new IOException("运行包里有越界的路径：" + entry.getName());
                }
                if (entry.isDirectory()) {
                    Files.createDirectories(file);
                } else {
                    Files.createDirectories(file.getParent());
                    Files.copy(zip, file, StandardCopyOption.REPLACE_EXISTING);
                }
            }
        }
        Files.move(staging, target);
        Files.writeString(target.resolve(COMPLETE), archive + "\n");
    }

    private static void copy(String name, Path target) throws IOException {
        Files.createDirectories(target.getParent());
        try (InputStream input = resource(name)) {
            Files.copy(input, target, StandardCopyOption.REPLACE_EXISTING);
        }
    }

    private static InputStream resource(String name) {
        InputStream input = PiRuntime.class.getResourceAsStream(RESOURCES + name);
        if (input == null) {
            throw new IllegalStateException("JAR 里缺少 " + RESOURCES + name);
        }
        return input;
    }

    private static void delete(Path directory) throws IOException {
        if (Files.notExists(directory)) {
            return;
        }
        try (Stream<Path> files = Files.walk(directory)) {
            for (Path file : files.sorted(Comparator.reverseOrder()).toList()) {
                Files.delete(file);
            }
        }
    }
}
