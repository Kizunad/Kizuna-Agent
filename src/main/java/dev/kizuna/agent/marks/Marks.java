package dev.kizuna.agent.marks;

import java.util.ArrayList;
import java.util.List;

/**
 * 玩家用指点杖标下的点：一个世界开着期间的一份，按标的先后编号（从 1 起）。
 *
 * <p>服务端线程上增删；客户端渲染线程（描框）和桥接的工作线程（给助手读）只读快照，所以整份列表每次改都换新的。</p>
 */
public final class Marks {
    /** 标的是什么：够得着的方块、准星指着的远处方块，还是玩家脚下。 */
    public enum Kind { BLOCK, DISTANT, FEET }

    /**
     * 一个标记。
     *
     * @param dimension 维度 id，例如 minecraft:overworld
     * @param state 那一格的方块写法，例如 minecraft:oak_stairs[facing=north]
     * @param face 点的是哪个面（up、north…）；标脚下时为空串
     */
    public record Mark(int x, int y, int z, String dimension, String state, String face, Kind kind) {
        boolean sameBlock(Mark other) {
            return x == other.x && y == other.y && z == other.z && dimension.equals(other.dimension);
        }
    }

    /** 最多同时标这么多个点。 */
    public static final int MAX = 16;

    private volatile List<Mark> marks = List.of();
    private volatile int version;

    /** 现在的各个标记，第 1 个在前。 */
    public List<Mark> list() {
        return marks;
    }

    /** 每次增删加一：用来判断「上次说话以后标记变没变」。 */
    public int version() {
        return version;
    }

    /** 这一格已经标过时它的编号；没标过为 0。 */
    public int numberOf(Mark mark) {
        List<Mark> current = marks;
        for (int index = 0; index < current.size(); index++) {
            if (current.get(index).sameBlock(mark)) {
                return index + 1;
            }
        }
        return 0;
    }

    /**
     * 标一个点，返回它的编号。
     *
     * @throws IllegalStateException 已经标满
     */
    public synchronized int add(Mark mark) {
        if (marks.size() >= MAX) {
            throw new IllegalStateException("标记已满 " + MAX + " 个");
        }
        var next = new ArrayList<>(marks);
        next.add(mark);
        marks = List.copyOf(next);
        version++;
        return next.size();
    }

    /** 清空，返回清掉了几个。 */
    public synchronized int clear() {
        int removed = marks.size();
        if (removed > 0) {
            marks = List.of();
            version++;
        }
        return removed;
    }

    /** 给助手看的一行：编号、坐标、方块、点的面。 */
    public static String describe(int number, Mark mark) {
        String where = "#" + number + " [" + mark.x() + ", " + mark.y() + ", " + mark.z() + "] " + mark.state();
        return switch (mark.kind()) {
            case BLOCK -> where + "（点的是 " + mark.face() + " 面）";
            case DISTANT -> where + "（远处指的，点的是 " + mark.face() + " 面）";
            case FEET -> where + "（玩家当时脚下的位置）";
        };
    }

    /** 附在玩家那句话后面的说明；没有标记时为空串。 */
    public static String note(List<Mark> marks) {
        if (marks.isEmpty()) {
            return "";
        }
        var text = new StringBuilder("\n\n（玩家用指点杖标了 " + marks.size() + " 个点");
        if (marks.stream().map(Mark::dimension).distinct().count() == 1) {
            text.append("，都在 ").append(marks.getFirst().dimension());
        }
        text.append("：");
        for (int index = 0; index < marks.size(); index++) {
            Mark mark = marks.get(index);
            text.append("\n").append(describe(index + 1, mark));
            if (marks.stream().map(Mark::dimension).distinct().count() > 1) {
                text.append("，在 ").append(mark.dimension());
            }
        }
        return text.append("）").toString();
    }
}
