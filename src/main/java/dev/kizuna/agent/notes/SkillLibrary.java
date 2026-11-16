package dev.kizuna.agent.notes;

import com.google.gson.Gson;
import com.google.gson.JsonSyntaxException;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.regex.Pattern;
import java.util.stream.Stream;

/**
 * 助手的技能库：pi home 里的 {@code skills/}，每个技能一个目录，里面一份 {@code SKILL.md}（Agent Skills 标准的写法），
 * 可以再带脚本和参考资料。
 *
 * <p>助手自己写的技能存在 {@code skills/<名字>/}；玩家手放进来的技能目录（可以再套一层分组目录）也认。
 * 所有读写都限在技能库目录里面。</p>
 */
public final class SkillLibrary {
    /**
     * 一个技能。
     *
     * @param directory 技能目录，{@code SKILL.md} 在它里面
     */
    public record Skill(String name, String description, Path directory) {
    }

    /**
     * 技能里除 {@code SKILL.md} 之外的一个文件。
     *
     * @param path 相对技能目录的路径，用 / 分隔
     */
    public record Attachment(String path, String content) {
    }

    public static final String SKILL_FILE = "SKILL.md";
    public static final int MAX_SKILLS = 100;
    public static final int MAX_NAME_CHARS = 64;
    public static final int MAX_DESCRIPTION_CHARS = 1024;
    public static final int MAX_BODY_CHARS = 32_000;
    public static final int MAX_ATTACHMENTS = 20;
    public static final int MAX_ATTACHMENT_CHARS = 64_000;
    /** 小写字母、数字，中间可以用单个连字符隔开。 */
    private static final Pattern NAME = Pattern.compile("[a-z0-9]+(-[a-z0-9]+)*");
    /** 玩家手放的技能可以套分组目录，往下找这么多层。 */
    private static final int SEARCH_DEPTH = 4;
    private static final String FENCE = "---";
    private static final Gson GSON = new Gson();

    private final Path root;

    public SkillLibrary(Path root) {
        this.root = root;
    }

    public Path root() {
        return root;
    }

    /** 库里的全部技能，按名字排；读不出名字和说明的 {@code SKILL.md} 不算。重名的只留路径靠前的那个。 */
    public synchronized List<Skill> list() {
        if (Files.notExists(root)) {
            return List.of();
        }
        var skills = new LinkedHashMap<String, Skill>();
        try (Stream<Path> files = Files.walk(root, SEARCH_DEPTH)) {
            for (Path file : files.filter(path -> path.getFileName().toString().equals(SKILL_FILE)).sorted().toList()) {
                describe(file).ifPresent(skill -> skills.putIfAbsent(skill.name(), skill));
            }
        } catch (IOException failed) {
            throw new UncheckedIOException(failed);
        }
        return skills.values().stream().sorted(Comparator.comparing(Skill::name)).toList();
    }

    /**
     * 读一个技能的 {@code SKILL.md} 全文。
     *
     * @throws IllegalArgumentException 没有这个技能
     */
    public synchronized String read(String name) {
        return readText(find(name).directory().resolve(SKILL_FILE));
    }

    /**
     * 读技能目录里的一个文件。
     *
     * @param path 相对技能目录的路径
     * @throws IllegalArgumentException 没有这个技能、路径出了技能目录，或者没有这个文件
     */
    public synchronized String read(String name, String path) {
        Skill skill = find(name);
        Path file = inside(skill.directory(), path);
        if (!Files.isRegularFile(file)) {
            throw new IllegalArgumentException("技能 " + name + " 里没有文件 " + path + "；它带的文件有：" + String.join("、", attachments(name)));
        }
        return readText(file);
    }

    /** 技能目录里除 {@code SKILL.md} 之外的文件，相对路径，用 / 分隔。 */
    public synchronized List<String> attachments(String name) {
        Path directory = find(name).directory();
        try (Stream<Path> files = Files.walk(directory)) {
            return files.filter(Files::isRegularFile).map(file -> directory.relativize(file).toString().replace('\\', '/'))
                    .filter(path -> !path.equals(SKILL_FILE)).sorted().toList();
        } catch (IOException failed) {
            throw new UncheckedIOException(failed);
        }
    }

    /**
     * 写一个技能：没有就新建，有就整份换掉说明和正文；带的文件逐个写，没提到的旧文件留着。
     *
     * @param body {@code SKILL.md} 的正文（不含开头那段名字和说明）
     * @return 写好的技能
     * @throws IllegalArgumentException 名字、说明、正文或带的文件不合规矩
     */
    public synchronized Skill save(String name, String description, String body, List<Attachment> attachments) {
        checkName(name);
        String summary = description.lines().map(String::strip).filter(line -> !line.isEmpty())
                .reduce((left, right) -> left + " " + right).orElse("");
        if (summary.isEmpty()) {
            throw new IllegalArgumentException("技能要有 description：一两句话说清它做什么、什么时候用");
        }
        if (summary.length() > MAX_DESCRIPTION_CHARS) {
            throw new IllegalArgumentException("description 最多 " + MAX_DESCRIPTION_CHARS + " 个字，现在 " + summary.length() + " 个字");
        }
        if (body.isBlank()) {
            throw new IllegalArgumentException("技能的正文是空的：写清楚步骤、要用的工具和脚本");
        }
        if (body.length() > MAX_BODY_CHARS) {
            throw new IllegalArgumentException("技能正文最多 " + MAX_BODY_CHARS + " 个字，现在 " + body.length() + " 个字；细节挪到带的文件里");
        }
        if (attachments.size() > MAX_ATTACHMENTS) {
            throw new IllegalArgumentException("一个技能一次最多带 " + MAX_ATTACHMENTS + " 个文件，这次有 " + attachments.size() + " 个");
        }
        List<Skill> existing = list();
        Optional<Skill> previous = existing.stream().filter(skill -> skill.name().equals(name)).findFirst();
        if (previous.isEmpty() && existing.size() >= MAX_SKILLS) {
            throw new IllegalArgumentException("技能库已有 " + existing.size() + " 个技能，到上限了；先删掉不用的");
        }
        Path directory = previous.map(Skill::directory).orElse(root.resolve(name));
        var targets = new LinkedHashMap<Path, String>();
        for (Attachment attachment : attachments) {
            if (attachment.content().length() > MAX_ATTACHMENT_CHARS) {
                throw new IllegalArgumentException("文件 " + attachment.path() + " 有 " + attachment.content().length()
                        + " 个字，一个文件最多 " + MAX_ATTACHMENT_CHARS + " 个字");
            }
            Path target = inside(directory, attachment.path());
            if (target.equals(directory.resolve(SKILL_FILE))) {
                throw new IllegalArgumentException(SKILL_FILE + " 由 name、description、body 写出，不要放在带的文件里");
            }
            targets.put(target, attachment.content());
        }
        try {
            Files.createDirectories(directory);
            Files.writeString(directory.resolve(SKILL_FILE), FENCE + "\nname: " + name + "\ndescription: " + GSON.toJson(summary)
                    + "\n" + FENCE + "\n\n" + body.strip() + "\n");
            for (Map.Entry<Path, String> target : targets.entrySet()) {
                Files.createDirectories(target.getKey().getParent());
                Files.writeString(target.getKey(), target.getValue());
            }
        } catch (IOException failed) {
            throw new UncheckedIOException(failed);
        }
        return new Skill(name, summary, directory);
    }

    /**
     * 删掉一个技能，连它带的文件一起。
     *
     * @throws IllegalArgumentException 没有这个技能
     */
    public synchronized void delete(String name) {
        Path directory = find(name).directory();
        try (Stream<Path> files = Files.walk(directory)) {
            for (Path file : files.sorted(Comparator.reverseOrder()).toList()) {
                Files.delete(file);
            }
        } catch (IOException failed) {
            throw new UncheckedIOException(failed);
        }
    }

    private Skill find(String name) {
        List<Skill> skills = list();
        return skills.stream().filter(skill -> skill.name().equals(name)).findFirst()
                .orElseThrow(() -> new IllegalArgumentException(skills.isEmpty() ? "技能库里还没有技能"
                        : "没有叫 " + name + " 的技能，现有：" + String.join("、", skills.stream().map(Skill::name).toList())));
    }

    private static void checkName(String name) {
        if (name.length() > MAX_NAME_CHARS || !NAME.matcher(name).matches()) {
            throw new IllegalArgumentException("技能名只能用小写字母、数字和连字符（不能开头结尾、不能连用），最多 " + MAX_NAME_CHARS
                    + " 个字符，例如 stone-watchtower；收到的是「" + name + "」");
        }
    }

    /** 技能目录里的一个路径；绝对路径、带 .. 跑出目录的都拒。 */
    private static Path inside(Path directory, String path) {
        if (path.isBlank()) {
            throw new IllegalArgumentException("文件路径是空的");
        }
        Path relative;
        try {
            relative = Path.of(path.replace('\\', '/'));
        } catch (java.nio.file.InvalidPathException invalid) {
            throw new IllegalArgumentException("文件路径写得不对：" + path);
        }
        Path target = directory.resolve(relative).normalize();
        if (relative.isAbsolute() || path.matches("^[A-Za-z]:.*") || !target.startsWith(directory) || target.equals(directory)) {
            throw new IllegalArgumentException("文件路径要是技能目录里面的相对路径（例如 scripts/build.py），收到的是 " + path);
        }
        return target;
    }

    /** 从 {@code SKILL.md} 开头那段读名字和说明；没有那段、没有说明的不算技能。名字没写就用目录名。 */
    private static Optional<Skill> describe(Path file) {
        List<String> lines;
        try {
            lines = Files.readAllLines(file);
        } catch (IOException | UncheckedIOException unreadable) {
            return Optional.empty();
        }
        if (lines.isEmpty() || !lines.getFirst().strip().equals(FENCE)) {
            return Optional.empty();
        }
        var fields = new LinkedHashMap<String, String>();
        String key = null;
        for (String line : lines.subList(1, lines.size())) {
            if (line.strip().equals(FENCE)) {
                break;
            }
            int colon = line.indexOf(':');
            boolean continued = line.startsWith(" ") || line.startsWith("\t");
            if (!continued && colon > 0) {
                key = line.substring(0, colon).strip();
                fields.put(key, line.substring(colon + 1).strip());
            } else if (continued && key != null) {
                // 折成多行写的值：接在上一个键后面。
                fields.merge(key, line.strip(), (before, more) -> (before + " " + more).strip());
            }
        }
        String description = unquote(fields.getOrDefault("description", ""));
        if (description.isBlank()) {
            return Optional.empty();
        }
        String name = unquote(fields.getOrDefault("name", ""));
        Path directory = file.getParent();
        return Optional.of(new Skill(name.isBlank() ? directory.getFileName().toString() : name, description, directory));
    }

    private static String unquote(String value) {
        // 「>」「|」是 YAML 多行值的记号，正文在后面几行。
        String plain = value.replaceFirst("^[>|][+-]?\\s*", "");
        if (plain.length() >= 2 && plain.startsWith("\"") && plain.endsWith("\"")) {
            try {
                return GSON.fromJson(plain, String.class);
            } catch (JsonSyntaxException notJson) {
                return plain.substring(1, plain.length() - 1);
            }
        }
        if (plain.length() >= 2 && plain.startsWith("'") && plain.endsWith("'")) {
            return plain.substring(1, plain.length() - 1).replace("''", "'");
        }
        return plain;
    }

    private static String readText(Path file) {
        try {
            return Files.readString(file);
        } catch (IOException failed) {
            throw new UncheckedIOException(failed);
        }
    }
}
