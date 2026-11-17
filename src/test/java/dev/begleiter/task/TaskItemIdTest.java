package dev.begleiter.task;

import dev.begleiter.ItemIds;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class TaskItemIdTest {
    @Test
    void itemFieldsCanonicalizeOnlyVanillaAliases() {
        assertEquals("minecraft:bucket", new TaskSpec.Craft("bucket", 2).item());
        assertEquals("other:bucket", new TaskSpec.Craft("other:bucket", 2).item());
        assertEquals("minecraft:coal", new TaskSpec.Take(null, "coal", 3).item());
        assertEquals("minecraft:iron_ingot", new TaskSpec.Smelt("iron_ingot", 3).item());
        assertEquals("minecraft:furnace", new TaskSpec.Station("furnace", true).item());
        assertEquals("minecraft:bread", new TaskSpec.Trade("bread", 3).item());
        assertEquals("minecraft:iron_helmet", new TaskSpec.GearUp(3, "iron_helmet", 2, 0).lastPiece());
        assertEquals("minecraft:birch_log", new TaskSpec.Gather("holz", 3, 7, "birch_log").exactItem());
        assertEquals("minecraft:rotten_flesh", new TaskSpec.Fight(null, "zombie", "rotten_flesh", 3).loot());
        assertEquals("item:other:coal", new TaskSpec.Give("item:other:coal", 3).what());
        assertEquals("item:minecraft:coal", new TaskSpec.Give("item:coal", 3).what());
    }

    @Test
    void markersResourcesAndGenericActionsKeepTheirMeaning() {
        assertEquals("#planks_from_logs", new TaskSpec.Craft("#planks_from_logs", 4).item());
        assertEquals("eisen", new TaskSpec.Gather("eisen", 4).resource());
        assertEquals("eisen", new TaskSpec.Give("eisen", 4).what());
        assertNull(new TaskSpec.Trade(null, 4).item());
        assertNull(new TaskSpec.Smelt(null, 4).item());
        assertNull(new TaskSpec.Give(null, 4).what());
    }

    @Test
    void invalidOptionalItemDoesNotBecomeAnUntargetedAction() {
        for (String invalid : new String[]{"", "other:", ":coal", "INVALID ITEM", "a:b:c", "x".repeat(257)}) {
            assertEquals(invalid, new TaskSpec.Trade(invalid, 4).item());
            assertEquals(invalid, new TaskSpec.Smelt(invalid, 4).item());
            assertNull(ItemIds.canonical(invalid));
        }
    }

    @Test
    void normalizedIdsAreBoundedAndIdempotent() {
        assertNull(ItemIds.canonical("x".repeat(250))); // Auch das ergänzte minecraft: zählt zur Grenze.
        assertEquals("other:item", ItemIds.canonicalOrOriginal(ItemIds.canonicalOrOriginal("other:item")));
        assertEquals("minecraft:bucket", ItemIds.canonicalOrOriginal(ItemIds.canonicalOrOriginal("bucket")));
    }
}
