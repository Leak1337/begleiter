package dev.begleiter.nav;

import it.unimi.dsi.fastutil.longs.Long2IntOpenHashMap;

import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;

/**
 * Trampelpfade: wie oft der Begleiter auf einer Stelle gelaufen ist (pro Welt, über den Seed). Oft gelaufene Stellen
 * macht {@link NavContext} günstiger, so werden Wege stabil und der Minenschacht von selbst zum Hauptweg.
 * Gespeichert im Kern und in der Entity.
 */
public final class RouteMemory {
    /** Ab so vielen Durchgängen gilt eine Stelle als Trampelpfad. */
    public static final int TRAMPLED = 3;
    /** Kosten-Faktor auf Trampelpfaden. */
    public static final double FACTOR = 0.7;
    static final int MAX_ENTRIES = 4096;
    private static final int MAX_COUNT = 100;

    private final Long2IntOpenHashMap steps = new Long2IntOpenHashMap();
    private long seed;

    public int size() { return steps.size(); }

    /**
     * Schlüssel einer Welt: Oberwelt, Nether und End teilen sich den Seed, ihre Koordinaten aber nicht. Die Oberwelt
     * behält den reinen Seed (ältere Spielstände bleiben gültig), andere Dimensionen mischen ihre ID hinein.
     */
    public static long worldKey(net.minecraft.server.level.ServerLevel level) {
        long seed = level.getSeed();
        if (level.dimension() == net.minecraft.world.level.Level.OVERWORLD) return seed;
        return seed ^ (0x9E3779B97F4A7C15L * level.dimension().identifier().toString().hashCode());
    }

    public int count(BlockPos p) { return steps.get(p.asLong()); }

    /** Er stand gerade auf p (fertige Bewegung). Andere Welt: altes Gedächtnis gilt nicht mehr. */
    public void noteStep(BlockPos p, long worldSeed) {
        if (worldSeed != seed) {
            steps.clear();
            seed = worldSeed;
        }
        long k = p.asLong();
        steps.put(k, Math.min(MAX_COUNT, steps.get(k) + 1));
        if (steps.size() > MAX_ENTRIES) decay();
    }

    /** Voll: alles halbieren, einmal Gelaufenes vergessen (bleibt nur, was wirklich oft benutzt wird). */
    private void decay() {
        var it = steps.long2IntEntrySet().fastIterator();
        while (it.hasNext()) {
            var e = it.next();
            int n = e.getIntValue() / 2;
            if (n == 0) it.remove(); else e.setValue(n);
        }
    }

    /** Trampelpfade dieser Welt als Faktoren in die Kosten eintragen. */
    void applyTo(it.unimi.dsi.fastutil.longs.Long2DoubleOpenHashMap favoring, long worldSeed) {
        if (worldSeed != seed) return;
        for (var e : steps.long2IntEntrySet())
            if (e.getIntValue() >= TRAMPLED) favoring.put(e.getLongKey(), favoring.get(e.getLongKey()) * FACTOR);
    }

    public CompoundTag save() {
        CompoundTag t = new CompoundTag();
        long[] pos = new long[steps.size()];
        int[] n = new int[steps.size()];
        int i = 0;
        for (var e : steps.long2IntEntrySet()) {
            pos[i] = e.getLongKey();
            n[i++] = e.getIntValue();
        }
        t.putLongArray("Pos", pos);
        t.putIntArray("N", n);
        t.putLong("Seed", seed);
        return t;
    }

    public void load(CompoundTag t) {
        steps.clear();
        seed = t.getLongOr("Seed", 0);
        long[] pos = t.getLongArray("Pos").orElse(new long[0]);
        int[] n = t.getIntArray("N").orElse(new int[0]);
        // Gespeicherte Felder sind nicht vertrauenswürdig: alte/kaputte Dateien dürfen die Grenze nicht umgehen
        int length = Math.min(MAX_ENTRIES, Math.min(pos.length, n.length));
        for (int i = 0; i < length; i++) {
            if (n[i] > 0) steps.put(pos[i], Math.min(MAX_COUNT, n[i]));
        }
    }
}
