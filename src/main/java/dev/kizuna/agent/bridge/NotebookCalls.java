package dev.kizuna.agent.bridge;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import dev.kizuna.agent.marks.Marks;
import dev.kizuna.agent.notes.MemoryFile;
import dev.kizuna.agent.notes.SkillLibrary;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Supplier;

/**
 * 不碰世界的那几组接口：助手的记忆、技能库，和玩家用指点杖标的点。
 *
 * <p>这些只读写模组自己数据目录里的文件和内存里的标记，直接在 HTTP 工作线程上做。
 * 记忆和技能写坏了的请求（太长、名字不对、条数满了）回成 400，带一句说得清怎么改的话。</p>
 */
public final class NotebookCalls {
    private final MemoryFile memory;
    private final SkillLibrary skills;
    private final Marks marks;

    public NotebookCalls(MemoryFile memory, SkillLibrary skills, Marks marks) {
        this.memory = memory;
        this.skills = skills;
        this.marks = marks;
    }

    /** 接口名到实现。 */
    public Map<String, BridgeServer.Call> calls() {
        var calls = new LinkedHashMap<String, BridgeServer.Call>();
        calls.put("memory/list", request -> memoryList());
        calls.put("memory/save", request -> checked(() -> memorySave(request)));
        calls.put("memory/forget", request -> checked(() -> memoryForget(request)));
        calls.put("skills/list", request -> skillList());
        calls.put("skills/read", request -> checked(() -> skillRead(request)));
        calls.put("skills/save", request -> checked(() -> skillSave(request)));
        calls.put("skills/delete", request -> checked(() -> skillDelete(request)));
        calls.put("marks", request -> markList());
        calls.put("context", request -> context());
        return calls;
    }

    /** 每句话开头接在系统提示词后面的那一段：现在记着的各条，和技能库里有哪些技能。 */
    public String contextPrompt() {
        var text = new StringBuilder();
        List<String> entries = memory.list();
        text.append("## 你记着的事（全局记忆，").append(entries.size()).append(" 条，上限 ").append(MemoryFile.MAX_ENTRIES).append("）\n");
        if (entries.isEmpty()) {
            text.append("还没有记忆。\n");
        }
        for (int index = 0; index < entries.size(); index++) {
            text.append(index + 1).append(". ").append(entries.get(index)).append('\n');
        }
        if (entries.size() >= MemoryFile.NEARLY_FULL) {
            text.append("记忆快满了：找机会用 memory_forget 删掉过时的、把相近的合并成一条。\n");
        }
        List<SkillLibrary.Skill> known = skills.list();
        text.append("\n## 你的技能（").append(known.size()).append(" 个）\n");
        if (known.isEmpty()) {
            text.append("还没有技能。\n");
        } else {
            known.forEach(skill -> text.append("- ").append(skill.name()).append("：").append(skill.description()).append('\n'));
            text.append("要做的事对得上某个技能时，先用 skill_read 读它的全文，再照着做。\n");
        }
        return text.toString();
    }

    private JsonObject context() {
        var answer = new JsonObject();
        answer.addProperty("prompt", contextPrompt());
        return answer;
    }

    private JsonObject memoryList() {
        var answer = new JsonObject();
        var entries = new JsonArray();
        memory.list().forEach(entries::add);
        answer.add("entries", entries);
        answer.addProperty("limit", MemoryFile.MAX_ENTRIES);
        return answer;
    }

    private JsonObject memorySave(JsonObject request) {
        int number = memory.save(Requests.text(request, "text"));
        var answer = new JsonObject();
        answer.addProperty("number", number);
        answer.addProperty("count", memory.list().size());
        answer.addProperty("limit", MemoryFile.MAX_ENTRIES);
        return answer;
    }

    private JsonObject memoryForget(JsonObject request) {
        if (!request.has("number")) {
            throw BridgeException.badRequest("缺少字段 number（第几条，从 1 数起）");
        }
        String removed = memory.forget(Requests.integer(request, "number", 0));
        var answer = new JsonObject();
        answer.addProperty("removed", removed);
        answer.addProperty("count", memory.list().size());
        return answer;
    }

    private JsonObject skillList() {
        var answer = new JsonObject();
        var list = new JsonArray();
        for (SkillLibrary.Skill skill : skills.list()) {
            var entry = new JsonObject();
            entry.addProperty("name", skill.name());
            entry.addProperty("description", skill.description());
            entry.addProperty("directory", skill.directory().toString());
            list.add(entry);
        }
        answer.add("skills", list);
        answer.addProperty("root", skills.root().toString());
        return answer;
    }

    private JsonObject skillRead(JsonObject request) {
        String name = Requests.text(request, "name");
        Optional<String> file = Requests.optionalText(request, "file");
        var answer = new JsonObject();
        answer.addProperty("text", file.isPresent() ? skills.read(name, file.get()) : skills.read(name));
        var files = new JsonArray();
        skills.attachments(name).forEach(files::add);
        answer.add("files", files);
        answer.addProperty("directory", skills.list().stream().filter(skill -> skill.name().equals(name)).findFirst()
                .orElseThrow().directory().toString());
        return answer;
    }

    private JsonObject skillSave(JsonObject request) {
        var attachments = new ArrayList<SkillLibrary.Attachment>();
        if (request.has("files") && !request.get("files").isJsonNull()) {
            for (JsonElement element : Requests.array(request, "files")) {
                if (!element.isJsonObject()) {
                    throw BridgeException.badRequest("files 里每一项要是 {path, content}");
                }
                JsonObject file = element.getAsJsonObject();
                attachments.add(new SkillLibrary.Attachment(Requests.text(file, "path"), Requests.text(file, "content")));
            }
        }
        String name = Requests.text(request, "name");
        boolean existed = skills.list().stream().anyMatch(skill -> skill.name().equals(name));
        SkillLibrary.Skill saved = skills.save(name, Requests.text(request, "description"), Requests.text(request, "body"),
                attachments);
        var answer = new JsonObject();
        answer.addProperty("name", saved.name());
        answer.addProperty("directory", saved.directory().toString());
        answer.addProperty("updated", existed);
        answer.addProperty("count", skills.list().size());
        return answer;
    }

    private JsonObject skillDelete(JsonObject request) {
        skills.delete(Requests.text(request, "name"));
        var answer = new JsonObject();
        answer.addProperty("count", skills.list().size());
        return answer;
    }

    private JsonObject markList() {
        var answer = new JsonObject();
        var list = new JsonArray();
        List<Marks.Mark> current = marks.list();
        for (int index = 0; index < current.size(); index++) {
            Marks.Mark mark = current.get(index);
            var entry = new JsonObject();
            entry.addProperty("number", index + 1);
            var block = new JsonArray();
            block.add(mark.x());
            block.add(mark.y());
            block.add(mark.z());
            entry.add("block", block);
            entry.addProperty("dimension", mark.dimension());
            entry.addProperty("state", mark.state());
            entry.addProperty("face", mark.face());
            entry.addProperty("kind", mark.kind().name().toLowerCase(java.util.Locale.ROOT));
            list.add(entry);
        }
        answer.add("marks", list);
        return answer;
    }

    private static JsonObject checked(Supplier<JsonObject> action) {
        try {
            return action.get();
        } catch (IllegalArgumentException rejected) {
            throw BridgeException.badRequest(rejected.getMessage());
        }
    }
}
