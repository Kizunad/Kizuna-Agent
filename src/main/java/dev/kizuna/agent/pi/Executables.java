package dev.kizuna.agent.pi;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

/**
 * 在这台电脑上找 Node.js 和 Python。
 *
 * <p>配置里填了路径就只认那个路径；没填按 PATH、再按常见安装位置找。找到的都实际跑一次 {@code --version} 确认能用。</p>
 */
public final class Executables {
    /** 找到的可执行程序：起它用的命令（可能带固定参数，如 {@code py -3}）和它报的版本。 */
    public record Tool(List<String> command, String version) {
    }

    /** 没找到或不能用：一句给玩家看的话。 */
    public static final class Unavailable extends Exception {
        public Unavailable(String message) {
            super(message);
        }
    }

    /** pi 要求的最低 Node 版本。 */
    static final int[] MINIMUM_NODE = {22, 19, 0};
    private static final Pattern VERSION = Pattern.compile("(\\d+)\\.(\\d+)\\.(\\d+)");
    private static final int PROBE_SECONDS = 15;
    private static final boolean WINDOWS = System.getProperty("os.name").toLowerCase(Locale.ROOT).contains("win");

    private Executables() {
        // 只有静态查找方法。
    }

    public static boolean windows() {
        return WINDOWS;
    }

    public static Tool node(String configured) throws Unavailable {
        List<List<String>> candidates = new ArrayList<>();
        if (!configured.isBlank()) {
            candidates.add(List.of(configured));
        } else {
            onPath("node").ifPresent(path -> candidates.add(List.of(path.toString())));
            nodeLocations().filter(Files::isRegularFile).forEach(path -> candidates.add(List.of(path.toString())));
        }
        String tooOld = null;
        for (List<String> candidate : candidates) {
            Optional<String> version = version(candidate);
            if (version.isEmpty()) {
                continue;
            }
            if (atLeast(version.get(), MINIMUM_NODE)) {
                return new Tool(candidate, version.get());
            }
            tooOld = candidate.getFirst() + "（" + version.get() + "）";
        }
        if (tooOld != null) {
            throw new Unavailable("Node.js 版本太低：" + tooOld + "，要 22.19 以上。装新版，或在配置的 [runtime] node 里填新版的路径");
        }
        throw new Unavailable(configured.isBlank()
                ? "没找到 Node.js（要 22.19 以上）。装好后重试，或在配置的 [runtime] node 里填 node 的完整路径"
                : "配置里的 Node.js 路径跑不起来：" + configured);
    }

    public static Tool python(String configured) throws Unavailable {
        List<List<String>> candidates = new ArrayList<>();
        if (!configured.isBlank()) {
            candidates.add(List.of(configured));
        } else {
            if (WINDOWS) {
                onPath("py").ifPresent(path -> candidates.add(List.of(path.toString(), "-3")));
            }
            Stream.of("python3", "python").map(Executables::onPath).flatMap(Optional::stream)
                    .forEach(path -> candidates.add(List.of(path.toString())));
        }
        for (List<String> candidate : candidates) {
            Optional<String> version = version(candidate);
            if (version.isPresent() && atLeast(version.get(), new int[]{3, 8, 0})) {
                return new Tool(candidate, version.get());
            }
        }
        throw new Unavailable(configured.isBlank()
                ? "没找到 Python 3。装好后重试，或在配置的 [runtime] python 里填 python 的完整路径"
                : "配置里的 Python 路径跑不起来：" + configured);
    }

    /** 在 PATH 里找一个程序；跳过 Windows 商店那个只会弹安装提示的占位程序。 */
    static Optional<Path> onPath(String name) {
        String path = System.getenv("PATH");
        if (path == null) {
            return Optional.empty();
        }
        List<String> names = WINDOWS ? List.of(name + ".exe", name + ".cmd", name + ".bat") : List.of(name);
        for (String directory : path.split(File.pathSeparator)) {
            if (directory.isBlank() || directory.replace('\\', '/').toLowerCase(Locale.ROOT).contains("/windowsapps")) {
                continue;
            }
            for (String candidate : names) {
                try {
                    Path file = Path.of(directory, candidate);
                    if (Files.isRegularFile(file) && (WINDOWS || Files.isExecutable(file))) {
                        return Optional.of(file);
                    }
                } catch (RuntimeException invalid) {
                    // PATH 里有写坏的项，跳过。
                }
            }
        }
        return Optional.empty();
    }

    private static Stream<Path> nodeLocations() {
        var locations = new ArrayList<Path>();
        String home = System.getProperty("user.home", "");
        if (WINDOWS) {
            for (String variable : List.of("ProgramFiles", "ProgramFiles(x86)", "LOCALAPPDATA")) {
                String base = System.getenv(variable);
                if (base != null) {
                    locations.add(Path.of(base, "nodejs", "node.exe"));
                    locations.add(Path.of(base, "Programs", "nodejs", "node.exe"));
                }
            }
            for (char drive = 'C'; drive <= 'F'; drive++) {
                locations.add(Path.of(drive + ":\\NodeJS\\node.exe"));
                locations.add(Path.of(drive + ":\\nodejs\\node.exe"));
            }
            String nvm = System.getenv("NVM_SYMLINK");
            if (nvm != null) {
                locations.add(Path.of(nvm, "node.exe"));
            }
        } else {
            for (String directory : List.of("/usr/local/bin", "/usr/bin", "/opt/homebrew/bin", home + "/.local/bin",
                    home + "/.npm-global/bin", home + "/.volta/bin")) {
                locations.add(Path.of(directory, "node"));
            }
            locations.addAll(newestFirst(Path.of(home, ".nvm", "versions", "node"), "bin/node"));
        }
        return locations.stream();
    }

    private static List<Path> newestFirst(Path versions, String relative) {
        // nvm 这类按版本分目录装的：版本号大的排前面。
        if (!Files.isDirectory(versions)) {
            return List.of();
        }
        try (Stream<Path> entries = Files.list(versions)) {
            return entries.sorted((first, second) -> second.getFileName().toString().compareTo(first.getFileName().toString()))
                    .map(entry -> entry.resolve(relative)).toList();
        } catch (IOException unreadable) {
            return List.of();
        }
    }

    /** 跑一次 --version，返回输出里的 x.y.z；跑不起来返回空。 */
    static Optional<String> version(List<String> command) {
        var probe = new ArrayList<>(command);
        probe.add("--version");
        try {
            Process process = new ProcessBuilder(probe).redirectErrorStream(true).start();
            process.getOutputStream().close();
            if (!process.waitFor(PROBE_SECONDS, TimeUnit.SECONDS)) {
                process.destroyForcibly();
                return Optional.empty();
            }
            String output = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
            Matcher matcher = VERSION.matcher(output);
            return process.exitValue() == 0 && matcher.find() ? Optional.of(matcher.group()) : Optional.empty();
        } catch (IOException failed) {
            return Optional.empty();
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            return Optional.empty();
        }
    }

    static boolean atLeast(String version, int[] minimum) {
        Matcher matcher = VERSION.matcher(version);
        if (!matcher.find()) {
            return false;
        }
        for (int part = 0; part < minimum.length; part++) {
            int given = Integer.parseInt(matcher.group(part + 1));
            if (given != minimum[part]) {
                return given > minimum[part];
            }
        }
        return true;
    }
}
