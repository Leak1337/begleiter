package dev.begleiter;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.StringTag;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/** Gewohnheiten und abgesuchte Felder überstehen das Neuladen; kaputte oder alte Daten bleiben harmlos. */
class InitiativeStoreTest {
    private final Map<String, int[]> asks = new HashMap<>();
    private final Set<String> declined = new HashSet<>();
    private final Map<Long, Integer> cells = new HashMap<>();

    @Test
    void speichernUndLadenIstVerlustfrei() {
        asks.put("minecraft:torch", new int[]{2, 10});
        asks.put("minecraft:bread", new int[]{0, 4}); // nach einem Vorschlag: Zähler 0, größte Menge bleibt
        declined.add("minecraft:cobblestone");
        cells.put(5L << 32 | 7L, 3);
        cells.put(-1L, 1);
        CompoundTag tag = InitiativeStore.write(asks, declined, cells);
        Map<String, int[]> a = new HashMap<>();
        Set<String> d = new HashSet<>();
        Map<Long, Integer> c = new HashMap<>();
        InitiativeStore.read(tag, a, d, c);
        assertArrayEquals(new int[]{2, 10}, a.get("minecraft:torch"));
        assertArrayEquals(new int[]{0, 4}, a.get("minecraft:bread"));
        assertEquals(Set.of("minecraft:cobblestone"), d);
        assertEquals(cells, c);
    }

    @Test
    void alteWeltOhneTeilBleibtLeer() {
        asks.put("minecraft:torch", new int[]{1, 1});
        InitiativeStore.read(new CompoundTag(), asks, declined, cells);
        assertTrue(asks.isEmpty() && declined.isEmpty() && cells.isEmpty());
        InitiativeStore.read(null, asks, declined, cells);
        assertTrue(asks.isEmpty());
    }

    @Test
    void leererZustandSchreibtNurDieVersion() {
        CompoundTag tag = InitiativeStore.write(asks, declined, cells);
        assertEquals(1, tag.size());
        assertEquals(InitiativeStore.VERSION, tag.getIntOr("V", 0));
    }

    @Test
    void kaputteWerteWerdenVerworfenOderBegrenzt() {
        CompoundTag tag = new CompoundTag();
        tag.putInt("V", InitiativeStore.VERSION);
        ListTag rows = new ListTag();
        CompoundTag good = new CompoundTag();
        good.putString("Item", "minecraft:torch");
        good.putInt("Times", 999_999);
        good.putInt("Largest", -5);
        rows.add(good);
        CompoundTag noId = new CompoundTag();
        noId.putString("Item", "torch");
        noId.putInt("Times", 2);
        rows.add(noId);
        rows.add(StringTag.valueOf("kein Eintrag"));
        tag.put("Asks", rows);
        ListTag no = new ListTag();
        no.add(StringTag.valueOf("minecraft:bread"));
        no.add(StringTag.valueOf("a:b:c"));
        no.add(StringTag.valueOf(""));
        tag.put("Declined", no);
        tag.putLongArray("CellKeys", new long[]{1, 2, 3});
        tag.putIntArray("CellVisits", new int[]{1, 2});
        InitiativeStore.read(tag, asks, declined, cells);
        assertEquals(Set.of("minecraft:torch"), asks.keySet());
        assertArrayEquals(new int[]{InitiativeStore.MAX_COUNT, 0}, asks.get("minecraft:torch"));
        assertEquals(Set.of("minecraft:bread"), declined);
        assertTrue(cells.isEmpty(), "ungleich lange Felder werden nicht halb übernommen");
    }

    @Test
    void fremdeVersionWirdIgnoriert() {
        asks.put("minecraft:torch", new int[]{2, 10});
        CompoundTag tag = InitiativeStore.write(asks, declined, cells);
        tag.putInt("V", 99);
        InitiativeStore.read(tag, asks, declined, cells);
        assertTrue(asks.isEmpty());
    }

    @Test
    void obergrenzenGeltenBeimSchreibenUndLesen() {
        for (int i = 0; i < InitiativeStore.MAX_CELLS + 50; i++) cells.put((long) i, 1);
        for (int i = 0; i < InitiativeStore.MAX_ASKS + 20; i++) asks.put("minecraft:item_" + i, new int[]{1, 1});
        CompoundTag tag = InitiativeStore.write(asks, declined, cells);
        Map<String, int[]> a = new HashMap<>();
        Map<Long, Integer> c = new HashMap<>();
        InitiativeStore.read(tag, a, new HashSet<>(), c);
        assertEquals(InitiativeStore.MAX_CELLS, c.size());
        assertEquals(InitiativeStore.MAX_ASKS, a.size());
    }

    @Test
    void itemIdsWerdenGrobGeprueft() {
        assertTrue(InitiativeStore.validId("minecraft:torch"));
        assertTrue(InitiativeStore.validId("othermod:fancy_torch"));
        assertFalse(InitiativeStore.validId("torch"));
        assertFalse(InitiativeStore.validId(":torch"));
        assertFalse(InitiativeStore.validId("minecraft:"));
        assertFalse(InitiativeStore.validId("minecraft:to rch"));
        assertFalse(InitiativeStore.validId(null));
    }
}
