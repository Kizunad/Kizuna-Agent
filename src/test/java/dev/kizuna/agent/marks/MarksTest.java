package dev.kizuna.agent.marks;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MarksTest {
    private static final String OVERWORLD = "minecraft:overworld";

    private static Marks.Mark block(int x, int y, int z) {
        return new Marks.Mark(x, y, z, OVERWORLD, "minecraft:stone_bricks", "up", Marks.Kind.BLOCK);
    }

    @Test
    void marksAreNumberedInOrderAndVersionMovesOnEveryChange() {
        var marks = new Marks();
        assertEquals(0, marks.version());
        assertEquals(1, marks.add(block(1, 64, 1)));
        assertEquals(2, marks.add(block(5, 64, 9)));
        assertEquals(2, marks.version());
        assertEquals(2, marks.numberOf(new Marks.Mark(5, 64, 9, OVERWORLD, "minecraft:air", "", Marks.Kind.FEET)));
        assertEquals(0, marks.numberOf(new Marks.Mark(5, 64, 9, "minecraft:the_nether", "minecraft:air", "", Marks.Kind.FEET)));
        assertEquals(2, marks.clear());
        assertEquals(3, marks.version());
        assertEquals(0, marks.clear());
        assertEquals(3, marks.version(), "本来就是空的，清空不算变化");
        assertEquals(List.of(), marks.list());
    }

    @Test
    void fullStoreRefusesMore() {
        var marks = new Marks();
        for (int index = 0; index < Marks.MAX; index++) {
            marks.add(block(index, 64, 0));
        }
        assertThrows(IllegalStateException.class, () -> marks.add(block(99, 64, 0)));
        assertEquals(Marks.MAX, marks.list().size());
    }

    @Test
    void noteListsEveryMarkWithNumberPositionBlockAndHowItWasMarked() {
        assertEquals("", Marks.note(List.of()));
        String note = Marks.note(List.of(block(1, 64, 1),
                new Marks.Mark(40, 70, -3, OVERWORLD, "minecraft:oak_log[axis=y]", "north", Marks.Kind.DISTANT),
                new Marks.Mark(2, 65, 2, OVERWORLD, "minecraft:air", "", Marks.Kind.FEET)));
        assertTrue(note.contains("标了 3 个点，都在 minecraft:overworld"), note);
        assertTrue(note.contains("#1 [1, 64, 1] minecraft:stone_bricks（点的是 up 面）"), note);
        assertTrue(note.contains("#2 [40, 70, -3] minecraft:oak_log[axis=y]（远处指的，点的是 north 面）"), note);
        assertTrue(note.contains("#3 [2, 65, 2] minecraft:air（玩家当时脚下的位置）"), note);
    }

    @Test
    void noteNamesTheDimensionPerMarkWhenTheyDiffer() {
        String note = Marks.note(List.of(block(1, 64, 1),
                new Marks.Mark(2, 65, 2, "minecraft:the_nether", "minecraft:netherrack", "up", Marks.Kind.BLOCK)));
        assertTrue(note.contains("（点的是 up 面），在 minecraft:overworld"), note);
        assertTrue(note.contains("，在 minecraft:the_nether"), note);
    }
}
