package dev.begleiter.ai;

import java.util.LinkedHashMap;
import java.util.Map;

import dev.begleiter.CompanionNeeds.Need;
import dev.begleiter.ai.DecisionOutcome.Reason;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;

/** Vorübergehende Fehlschläge bestimmter Quellen in der Welt, getrennt von den allgemeinen Beschaffungsprofilen. */
public final class CompanionSourceMemory {
    private static final int MAX_SOURCES = 32;
    public record Key(Need need, String method, String item, String dimension, long seed, long position) {
        public Key {
            if (need == null || !"chest".equals(method) || !identifier(item) || !identifier(dimension))
                throw new IllegalArgumentException("Invalid source context");
        }
    }
    private record Entry(int count, int tick, Reason reason) {}
    private final Map<Key, Entry> failures = new LinkedHashMap<>();

    private static boolean identifier(String id) {
        return id != null && id.length() <= 128 && id.matches("[a-z0-9_.-]+:[a-z0-9_/.-]+");
    }

    private static int duration(Reason reason) {
        return reason == Reason.UNREACHABLE || reason == Reason.STALLED || reason == Reason.SOURCE_MISSING
                || reason == Reason.ROUTE_UNSAFE ? 1200 : 600;
    }

    private static boolean active(Entry entry, int now) {
        return entry != null && now >= entry.tick() && (long) now - entry.tick() < duration(entry.reason());
    }

    public void failed(Key key, Reason reason, int now) {
        if (key == null || reason == null || reason.preparationOnly()) return;
        failures.entrySet().removeIf(e -> !active(e.getValue(), now));
        Entry old = failures.get(key);
        int count = old == null ? 1 : Math.min(5, old.count() + 1);
        if (!failures.containsKey(key) && failures.size() >= MAX_SOURCES)
            failures.remove(failures.keySet().iterator().next());
        failures.put(key, new Entry(count, now, reason));
    }

    public boolean blocked(Key key, int now) { return key != null && active(failures.get(key), now); }
    public Reason reason(Key key, int now) {
        Entry e = failures.get(key);
        return active(e, now) ? e.reason() : null;
    }
    public void succeeded(Key key) { if (key != null) failures.remove(key); }
    public int size() { return failures.size(); }

    public boolean hasActive(Need need, String dimension, long seed, int now) {
        return failures.entrySet().stream().anyMatch(e -> e.getKey().need() == need
                && e.getKey().dimension().equals(dimension) && e.getKey().seed() == seed && active(e.getValue(), now));
    }

    /** Store remaining durations; entity tick counters restart on load. */
    public ListTag save(int now) {
        ListTag rows = new ListTag();
        for (var e : failures.entrySet()) {
            if (!active(e.getValue(), now)) continue;
            Key key = e.getKey();
            CompoundTag row = new CompoundTag();
            row.putString("Need", key.need().name());
            row.putString("Method", key.method());
            row.putString("Item", key.item());
            row.putString("Dimension", key.dimension());
            row.putLong("Seed", key.seed());
            row.putLong("Position", key.position());
            row.putInt("Count", e.getValue().count());
            row.putString("Reason", e.getValue().reason().name());
            row.putInt("Remaining", duration(e.getValue().reason()) - (now - e.getValue().tick()));
            rows.add(row);
        }
        return rows;
    }

    public void load(ListTag rows, int now) {
        failures.clear();
        int inspected = 0;
        for (Tag tag : rows) {
            if (++inspected > MAX_SOURCES * 4 || failures.size() >= MAX_SOURCES) break;
            if (!(tag instanceof CompoundTag row)) continue;
            if (!row.contains("Seed") || !row.contains("Position")) continue;
            try {
                Key key = new Key(Need.valueOf(row.getStringOr("Need", "")), row.getStringOr("Method", ""),
                        row.getStringOr("Item", ""), row.getStringOr("Dimension", ""),
                        row.getLongOr("Seed", 0), row.getLongOr("Position", 0));
                Reason reason = Reason.valueOf(row.getStringOr("Reason", ""));
                int remaining = Math.min(duration(reason), row.getIntOr("Remaining", 0));
                if (reason.preparationOnly() || remaining <= 0) continue;
                int count = Math.max(1, Math.min(5, row.getIntOr("Count", 1)));
                failures.put(key, new Entry(count, now - (duration(reason) - remaining), reason));
            } catch (IllegalArgumentException ignored) {
                // Unbekannte oder beschädigte Quelleinträge dürfen alte Spielstände nicht unbrauchbar machen.
            }
        }
    }
}
