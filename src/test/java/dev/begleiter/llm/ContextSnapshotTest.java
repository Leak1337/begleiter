package dev.begleiter.llm;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class ContextSnapshotTest {
    private static ContextSnapshot minimal() {
        return new ContextSnapshot("folgt dir", 40, 40, 20, "overworld", false, false, -1, List.of(), 0, Map.of(),
                List.of(), Map.of(), null, false, null, List.of(), Map.of(), null, "");
    }

    @Test void minimalSnapshotIsShortAndSkipsEmptyLines() {
        String text = minimal().render();
        assertEquals("""
                mode: folgt dir
                health 40/40, hunger 20/20, overworld, day
                tasks: none
                inventory: empty
                home storage: not set
                """, text);
    }

    @Test void fullSnapshotListsTheImportantThings() {
        var s = new ContextSnapshot("spielt selbstständig", 12, 40, 6, "overworld", true, true, 23,
                List.of("GATHER sucht eisen, noch 8", "GIVE eisen"), 2, Map.of("torch", 5, "cobblestone", 40),
                List.of("stone_pickaxe", "iron_helmet"), Map.of("zombie", 2, "skeleton", 1), "Lava 4 Blöcke", true,
                "Haus (paused)", List.of("eisenmine"), Map.of("ore eisen", 3, "village", 1), "SUPPLY eisen fehlgeschlagen",
                "kein Weg");
        String text = s.render();
        assertTrue(text.contains("health 12/40, hunger 6/20, overworld, night, rain"), text);
        assertTrue(text.contains("player distance: 23"));
        assertTrue(text.contains("tasks (2): GATHER sucht eisen, noch 8; GIVE eisen\n"));
        assertTrue(text.contains("inventory: cobblestone 40, torch 5"));
        assertTrue(text.contains("hostile nearby: zombie 2, skeleton 1"));
        assertTrue(text.contains("home storage: set"));
        assertTrue(text.contains("waypoints: eisenmine"));
        assertTrue(text.contains("remembered: ore eisen 3, village 1"));
        assertTrue(text.contains("last own plan: SUPPLY eisen fehlgeschlagen"));
        assertTrue(text.contains("problem: kein Weg"));
    }

    @Test void coordinatesNeverReachTheService() {
        var s = new ContextSnapshot("m", 1, 1, 1, "d", false, false, -1, List.of("MOVE_TO 120, 64, -30", "BREAK stone x2 -5, 70, 8"), 2,
                Map.of(), List.of(), Map.of(), "Lava 4 Blöcke", false, null, List.of("basis"), Map.of(),
                "SUPPLY eisen BlockPos{x=1, y=2, z=3}", "kein Weg nach 12 64 -30");
        String text = s.render();
        assertFalse(text.matches("(?s).*-?\\d+,? -?\\d+,? -?\\d+.*"), text);
        assertFalse(text.contains("BlockPos"), text);
        assertTrue(text.contains("tasks (2): MOVE_TO (pos); BREAK stone x2 (pos)"), text);
        assertTrue(text.contains("problem: kein Weg nach (pos)"), text);
        assertTrue(text.contains("hazard: Lava 4 Blöcke"), text);
        assertEquals("x5 64 Fackeln", ContextSnapshot.withoutCoordinates("x5 64 Fackeln"));
    }

    @Test void listsAreCappedSortedAndDeterministic() {
        Map<String, Integer> items = new HashMap<>();
        for (int i = 0; i < 30; i++) items.put("item_" + (char) ('a' + i % 26) + i, i % 5 + 1);
        var a = new ContextSnapshot("m", 1, 1, 1, "d", false, false, -1, List.of("t1", "t2", "t3", "t4", "t5"), 9, items,
                List.of(), Map.of(), null, false, null, List.of(), Map.of(), null, null);
        String text = a.render();
        String inv = text.lines().filter(l -> l.startsWith("inventory: ")).findFirst().orElseThrow();
        assertEquals(ContextSnapshot.MAX_ITEMS, inv.substring("inventory: ".length()).split(", ").length - 1, inv);
        assertTrue(inv.endsWith(", …"));
        assertTrue(inv.startsWith("inventory: item_d29 5, item_e4 5, item_j9 5"), inv); // höchste Menge, dann alphabetisch
        assertTrue(text.contains("tasks (9): t1; t2; t3; …"));
        assertEquals(text, new ContextSnapshot("m", 1, 1, 1, "d", false, false, -1, List.of("t1", "t2", "t3", "t4", "t5"), 9,
                new HashMap<>(items), List.of(), Map.of(), null, false, null, List.of(), Map.of(), null, null).render());
    }

    @Test void totalLengthIsBoundedAndKeepsTheFirstLines() {
        String longText = "x".repeat(500);
        Map<String, Integer> many = new HashMap<>();
        for (int i = 0; i < 100; i++) many.put(longText + i, i);
        var s = new ContextSnapshot(longText, 1, 1, 1, longText, false, false, 5, List.of(longText, longText, longText), 3,
                many, List.of(longText, longText), many, longText, true, longText, List.of(longText), many, longText, longText);
        String text = s.render();
        assertTrue(text.length() <= ContextSnapshot.MAX_CHARS, "Länge " + text.length());
        assertTrue(text.startsWith("mode: " + "x".repeat(ContextSnapshot.MAX_TEXT) + "…\n"));
        assertTrue(text.contains("health 1/1"));
        assertTrue(text.endsWith("\n"), "Zeilen werden nur ganz weggelassen, nie abgeschnitten");
    }
}
