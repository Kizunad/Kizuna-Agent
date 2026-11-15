package dev.kizuna.agent.bridge;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassVisitor;
import org.objectweb.asm.FieldVisitor;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.Type;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.lang.reflect.Modifier;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.PathMatcher;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.stream.Stream;

/**
 * 查看已加载模组的 JAR：列条目、读文本资源、在文本里找词、列类和类的成员签名。
 *
 * <p>模组的内容经加载器给的根目录读，所以普通 JAR、JAR 里套的 JAR、开发环境里的目录都一样对待。
 * 只读，不反编译：类只读出字段和方法的签名。</p>
 */
public final class JarInspector {
    /** 一个已加载的模组：登记信息和内容的根目录。 */
    public record Mod(String id, String version, String name, Optional<Path> jar, Optional<String> nestedIn,
                      List<Path> roots) {
    }

    static final int DEFAULT_LIMIT = 500;
    static final int MAX_LIMIT = 5000;
    static final int DEFAULT_READ_BYTES = 64 * 1024;
    static final int MAX_READ_BYTES = 1024 * 1024;
    static final int MAX_SEARCH_FILE_BYTES = 2 * 1024 * 1024;
    private static final int MAX_LINE = 240;
    private static final int BINARY_PROBE = 4096;
    private static final String TEXT_FILES = "**.{json,json5,toml,txtpb,lang,txt,mcmeta,properties,cfg,yml,yaml,md,mcfunction,snbt,fsh,vsh,accesswidener}";

    private final Function<String, Optional<Mod>> lookup;
    private final List<Mod> mods;

    public JarInspector(List<Mod> mods) {
        this.mods = List.copyOf(mods);
        var byId = new LinkedHashMap<String, Mod>();
        mods.forEach(mod -> byId.put(mod.id(), mod));
        this.lookup = id -> Optional.ofNullable(byId.get(id));
    }

    public Map<String, BridgeServer.Call> calls() {
        var calls = new LinkedHashMap<String, BridgeServer.Call>();
        calls.put("mods", this::listMods);
        calls.put("jar/list", this::list);
        calls.put("jar/read", this::read);
        calls.put("jar/search", this::search);
        calls.put("jar/classes", this::classes);
        calls.put("jar/class", this::describeClass);
        return calls;
    }

    private JsonObject listMods(JsonObject request) {
        var entries = new JsonArray();
        for (Mod mod : mods) {
            var entry = new JsonObject();
            entry.addProperty("id", mod.id());
            entry.addProperty("version", mod.version());
            entry.addProperty("name", mod.name());
            mod.jar().ifPresent(path -> entry.addProperty("jar", path.toString()));
            mod.nestedIn().ifPresent(parent -> entry.addProperty("nested_in", parent));
            entries.add(entry);
        }
        var answer = new JsonObject();
        answer.add("mods", entries);
        return answer;
    }

    private JsonObject list(JsonObject request) {
        Mod mod = mod(request);
        String prefix = normalize(Requests.optionalText(request, "prefix").orElse(""));
        Optional<String> glob = Requests.optionalText(request, "glob");
        int limit = limit(request);
        var entries = new JsonArray();
        int[] total = {0};
        for (Path root : mod.roots()) {
            PathMatcher matcher = glob.map(pattern -> matcher(root, pattern)).orElse(null);
            walk(root, file -> {
                String name = name(root, file);
                if (!name.startsWith(prefix) || (matcher != null && !matcher.matches(root.relativize(file)))) {
                    return;
                }
                if (total[0]++ < limit) {
                    var entry = new JsonObject();
                    entry.addProperty("path", name);
                    entry.addProperty("size", size(file));
                    entries.add(entry);
                }
            });
        }
        var answer = new JsonObject();
        answer.addProperty("mod", mod.id());
        answer.add("entries", entries);
        answer.addProperty("total", total[0]);
        answer.addProperty("truncated", total[0] > limit);
        return answer;
    }

    private JsonObject read(JsonObject request) {
        Mod mod = mod(request);
        String name = normalize(Requests.text(request, "path"));
        int offset = Math.max(0, Requests.integer(request, "offset", 0));
        int wanted = Math.clamp(Requests.integer(request, "max_bytes", DEFAULT_READ_BYTES), 1, MAX_READ_BYTES);
        Path file = file(mod, name);
        byte[] bytes = bytes(file);
        if (binary(bytes)) {
            throw BridgeException.badRequest(name + " 不是文本文件（" + bytes.length + " 字节）；类文件用 jar/class 看签名");
        }
        int end = Math.min(bytes.length, offset + wanted);
        var answer = new JsonObject();
        answer.addProperty("mod", mod.id());
        answer.addProperty("path", name);
        answer.addProperty("size", bytes.length);
        answer.addProperty("offset", Math.min(offset, bytes.length));
        answer.addProperty("truncated", end < bytes.length);
        answer.addProperty("text", new String(bytes, Math.min(offset, bytes.length), Math.max(0, end - offset), StandardCharsets.UTF_8));
        return answer;
    }

    private JsonObject search(JsonObject request) {
        String text = Requests.text(request, "text");
        if (text.isBlank()) {
            throw BridgeException.badRequest("text 是空的");
        }
        boolean exact = Requests.flag(request, "case_sensitive", false);
        String needle = exact ? text : text.toLowerCase(Locale.ROOT);
        String glob = Requests.optionalText(request, "glob").orElse(TEXT_FILES);
        int limit = Math.clamp(Requests.integer(request, "limit", 100), 1, MAX_LIMIT);
        List<Mod> searched = Requests.optionalText(request, "mod").map(id -> List.of(mod(id))).orElse(mods);
        var matches = new JsonArray();
        int[] total = {0};
        for (Mod mod : searched) {
            for (Path root : mod.roots()) {
                PathMatcher matcher = matcher(root, glob);
                walk(root, file -> {
                    if (!matcher.matches(root.relativize(file)) || size(file) > MAX_SEARCH_FILE_BYTES) {
                        return;
                    }
                    byte[] bytes = bytes(file);
                    if (binary(bytes)) {
                        return;
                    }
                    String[] lines = new String(bytes, StandardCharsets.UTF_8).split("\n", -1);
                    for (int line = 0; line < lines.length; line++) {
                        String candidate = exact ? lines[line] : lines[line].toLowerCase(Locale.ROOT);
                        if (candidate.contains(needle) && total[0]++ < limit) {
                            var match = new JsonObject();
                            match.addProperty("mod", mod.id());
                            match.addProperty("path", name(root, file));
                            match.addProperty("line", line + 1);
                            match.addProperty("text", clip(lines[line].strip()));
                            matches.add(match);
                        }
                    }
                });
            }
        }
        var answer = new JsonObject();
        answer.add("matches", matches);
        answer.addProperty("total", total[0]);
        answer.addProperty("truncated", total[0] > limit);
        return answer;
    }

    private JsonObject classes(JsonObject request) {
        Mod mod = mod(request);
        String prefix = normalize(Requests.optionalText(request, "prefix").orElse("").replace('.', '/'));
        int limit = limit(request);
        var names = new JsonArray();
        int[] total = {0};
        for (Path root : mod.roots()) {
            walk(root, file -> {
                String name = name(root, file);
                if (name.endsWith(".class") && name.startsWith(prefix) && !name.endsWith("package-info.class")
                        && !name.equals("module-info.class") && total[0]++ < limit) {
                    names.add(name.substring(0, name.length() - ".class".length()).replace('/', '.'));
                }
            });
        }
        var answer = new JsonObject();
        answer.addProperty("mod", mod.id());
        answer.add("classes", names);
        answer.addProperty("total", total[0]);
        answer.addProperty("truncated", total[0] > limit);
        return answer;
    }

    private JsonObject describeClass(JsonObject request) {
        Mod mod = mod(request);
        String className = Requests.text(request, "class").strip();
        boolean all = Requests.flag(request, "private", false);
        Path file = file(mod, className.replace('.', '/') + ".class");
        var answer = describe(bytes(file), all);
        answer.addProperty("mod", mod.id());
        return answer;
    }

    /** 读一个类文件的签名；all 为假时不列私有成员和编译器合成的成员。 */
    static JsonObject describe(byte[] classFile, boolean all) {
        var answer = new JsonObject();
        var fields = new JsonArray();
        var methods = new JsonArray();
        new ClassReader(classFile).accept(new ClassVisitor(Opcodes.ASM9) {
            @Override
            public void visit(int version, int access, String name, String signature, String superName, String[] interfaces) {
                answer.addProperty("class", name.replace('/', '.'));
                answer.addProperty("kind", kind(access));
                answer.addProperty("modifiers", modifiers(access & ~Opcodes.ACC_SUPER, true));
                if (superName != null && !superName.equals("java/lang/Object")) {
                    answer.addProperty("extends", superName.replace('/', '.'));
                }
                var implemented = new JsonArray();
                Arrays.stream(interfaces == null ? new String[0] : interfaces).map(type -> type.replace('/', '.')).forEach(implemented::add);
                answer.add("implements", implemented);
            }

            @Override
            public FieldVisitor visitField(int access, String name, String descriptor, String signature, Object value) {
                if (listed(access, all)) {
                    fields.add((modifiers(access, false) + " " + Type.getType(descriptor).getClassName() + " " + name).strip());
                }
                return null;
            }

            @Override
            public MethodVisitor visitMethod(int access, String name, String descriptor, String signature, String[] exceptions) {
                if (listed(access, all) && !name.equals("<clinit>")) {
                    String parameters = String.join(", ", Arrays.stream(Type.getArgumentTypes(descriptor)).map(Type::getClassName).toList());
                    String returned = name.equals("<init>") ? "" : Type.getReturnType(descriptor).getClassName() + " ";
                    methods.add((modifiers(access, false) + " " + returned + name + "(" + parameters + ")").strip());
                }
                return null;
            }
        }, ClassReader.SKIP_CODE | ClassReader.SKIP_DEBUG | ClassReader.SKIP_FRAMES);
        answer.add("fields", fields);
        answer.add("methods", methods);
        return answer;
    }

    private static boolean listed(int access, boolean all) {
        return all || (access & (Opcodes.ACC_PRIVATE | Opcodes.ACC_SYNTHETIC | Opcodes.ACC_BRIDGE)) == 0;
    }

    private static String kind(int access) {
        if ((access & Opcodes.ACC_ANNOTATION) != 0) {
            return "annotation";
        }
        if ((access & Opcodes.ACC_INTERFACE) != 0) {
            return "interface";
        }
        if ((access & Opcodes.ACC_ENUM) != 0) {
            return "enum";
        }
        return (access & Opcodes.ACC_RECORD) != 0 ? "record" : "class";
    }

    private static String modifiers(int access, boolean type) {
        // 类型上的 interface、abstract 由 kind 说了，这里只留访问级别、static、final、abstract。
        int shown = access & (Modifier.PUBLIC | Modifier.PROTECTED | Modifier.PRIVATE | Modifier.STATIC | Modifier.FINAL | Modifier.ABSTRACT);
        if (type && (access & Opcodes.ACC_INTERFACE) != 0) {
            shown &= ~Modifier.ABSTRACT;
        }
        return Modifier.toString(shown);
    }

    private Mod mod(JsonObject request) {
        return mod(Requests.text(request, "mod"));
    }

    private Mod mod(String id) {
        return lookup.apply(id).orElseThrow(() -> BridgeException.badRequest("没有加载这个模组：" + id + "。用 mods 接口看有哪些"));
    }

    private static Path file(Mod mod, String name) {
        for (Path root : mod.roots()) {
            Path file = root.resolve(name).normalize();
            // 写了 ../ 想跳出模组内容的一律当没有。
            if (file.startsWith(root) && Files.isRegularFile(file)) {
                return file;
            }
        }
        throw new BridgeException(404, "not_found", mod.id() + " 里没有 " + name + "。用 jar/list 看有哪些文件");
    }

    private static int limit(JsonObject request) {
        return Math.clamp(Requests.integer(request, "limit", DEFAULT_LIMIT), 1, MAX_LIMIT);
    }

    private static String normalize(String path) {
        String cleaned = path.replace('\\', '/');
        return cleaned.startsWith("/") ? cleaned.substring(1) : cleaned;
    }

    private static PathMatcher matcher(Path root, String glob) {
        try {
            return root.getFileSystem().getPathMatcher("glob:" + glob);
        } catch (RuntimeException invalid) {
            throw BridgeException.badRequest("glob 写法不对「" + glob + "」：" + invalid.getMessage());
        }
    }

    private static String name(Path root, Path file) {
        return root.relativize(file).toString().replace(root.getFileSystem().getSeparator(), "/");
    }

    private static void walk(Path root, Consumer<Path> visitor) {
        try (Stream<Path> files = Files.walk(root)) {
            files.filter(Files::isRegularFile).sorted().forEach(visitor);
        } catch (IOException failed) {
            throw new UncheckedIOException(failed);
        }
    }

    private static long size(Path file) {
        try {
            return Files.size(file);
        } catch (IOException failed) {
            throw new UncheckedIOException(failed);
        }
    }

    private static byte[] bytes(Path file) {
        try {
            return Files.readAllBytes(file);
        } catch (IOException failed) {
            throw new UncheckedIOException(failed);
        }
    }

    static boolean binary(byte[] bytes) {
        // 开头一段里有 0 字节就当二进制。
        for (int index = 0; index < Math.min(bytes.length, BINARY_PROBE); index++) {
            if (bytes[index] == 0) {
                return true;
            }
        }
        return false;
    }

    private static String clip(String line) {
        return line.length() <= MAX_LINE ? line : line.substring(0, MAX_LINE) + "…";
    }
}
