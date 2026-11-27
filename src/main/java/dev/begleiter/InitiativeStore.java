package dev.begleiter;

import java.util.Map;
import java.util.Set;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.StringTag;
import net.minecraft.nbt.Tag;

/**
 * Speicherform der Eigeninitiative ({@code InitiativeV1}): Gewohnheitszähler, abgelehnte Vorschläge und die auf
 * Erztiefe abgesuchten 16×16-Felder. Reine NBT-Logik ohne Registry, damit sie ohne Spielstart testbar ist. Fremde
 * oder kaputte Werte werden übersprungen bzw. begrenzt; fehlt der Teil (alte Welten), bleibt alles leer.
 */
final class InitiativeStore {
    static final int VERSION = 1;
    static final int MAX_ASKS = 128;
    static final int MAX_DECLINED = 128;
    static final int MAX_CELLS = 4096;
    /** Obergrenze für gespeicherte Zähler; mehr braucht weder der Vorschlag (ab 3) noch die Grabrichtung. */
    static final int MAX_COUNT = 10_000;
    private static final int MAX_ID = 256;

    private InitiativeStore() { }

    /** Bestellzähler je Item-ID: [0] = Anzahl seit dem letzten Vorschlag, [1] = größte bestellte Menge. */
    static CompoundTag write(Map<String, int[]> asks, Set<String> declined, Map<Long, Integer> cells) {
        CompoundTag root = new CompoundTag();
        root.putInt("V", VERSION);
        ListTag askRows = new ListTag();
        asks.forEach((item, a) -> {
            if (askRows.size() >= MAX_ASKS || a[0] <= 0 && a[1] <= 0) return;
            CompoundTag row = new CompoundTag();
            row.putString("Item", item);
            row.putInt("Times", Math.clamp(a[0], 0, MAX_COUNT));
            row.putInt("Largest", Math.clamp(a[1], 0, MAX_COUNT));
            askRows.add(row);
        });
        if (!askRows.isEmpty()) root.put("Asks", askRows);
        ListTag no = new ListTag();
        for (String item : declined) {
            if (no.size() >= MAX_DECLINED) break;
            no.add(StringTag.valueOf(item));
        }
        if (!no.isEmpty()) root.put("Declined", no);
        if (!cells.isEmpty()) {
            int n = Math.min(cells.size(), MAX_CELLS), i = 0;
            long[] keys = new long[n];
            int[] visits = new int[n];
            for (var en : cells.entrySet()) {
                if (i >= n) break;
                keys[i] = en.getKey();
                visits[i++] = Math.clamp(en.getValue(), 1, MAX_COUNT);
            }
            root.putLongArray("CellKeys", keys);
            root.putIntArray("CellVisits", visits);
        }
        return root;
    }

    /** Liest in die (vorher geleerten) Sammlungen; unbekannte Version oder fehlender Teil lässt sie leer. */
    static void read(CompoundTag root, Map<String, int[]> asks, Set<String> declined, Map<Long, Integer> cells) {
        asks.clear();
        declined.clear();
        cells.clear();
        if (root == null || root.getIntOr("V", 0) != VERSION) return;
        int inspected = 0;
        for (Tag tag : root.getListOrEmpty("Asks")) {
            if (++inspected > MAX_ASKS * 2 || asks.size() >= MAX_ASKS) break;
            if (!(tag instanceof CompoundTag row)) continue;
            String item = row.getStringOr("Item", "");
            if (!validId(item)) continue;
            int times = Math.clamp(row.getIntOr("Times", 0), 0, MAX_COUNT);
            int largest = Math.clamp(row.getIntOr("Largest", 0), 0, MAX_COUNT);
            if (times == 0 && largest == 0) continue;
            asks.put(item, new int[]{times, largest});
        }
        ListTag no = root.getListOrEmpty("Declined");
        for (int i = 0; i < no.size() && i < MAX_DECLINED * 2 && declined.size() < MAX_DECLINED; i++) {
            String item = no.getStringOr(i, "");
            if (validId(item)) declined.add(item);
        }
        long[] keys = root.getLongArray("CellKeys").orElse(new long[0]);
        int[] visits = root.getIntArray("CellVisits").orElse(new int[0]);
        if (keys.length != visits.length) return; // nicht zusammengehörig: lieber nichts als falsche Felder
        for (int i = 0; i < keys.length && cells.size() < MAX_CELLS; i++)
            if (visits[i] > 0) cells.put(keys[i], Math.min(visits[i], MAX_COUNT));
    }

    /** "namespace:pfad" in vernünftiger Länge; die Registry prüft erst der Aufrufer. */
    static boolean validId(String id) {
        if (id == null || id.isEmpty() || id.length() > MAX_ID) return false;
        int colon = id.indexOf(':');
        return colon > 0 && colon < id.length() - 1 && id.indexOf(':', colon + 1) < 0 && id.chars().noneMatch(Character::isWhitespace);
    }
}
