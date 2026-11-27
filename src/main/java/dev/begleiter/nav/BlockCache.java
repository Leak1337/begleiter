package dev.begleiter.nav;

import net.minecraft.server.level.ServerLevel;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.chunk.LevelChunkSection;
import dev.begleiter.thirdparty.baritone.PositionCache;

/**
 * Schneller Blockzugriff für die Suche: merkt sich den zuletzt benutzten Chunk und lädt nie selbst Chunks nach.
 * Nur im Server-Thread benutzen (liest die echte, aktuelle Welt).
 */
public final class BlockCache {
    private static final BlockState VOID = Blocks.VOID_AIR.defaultBlockState();
    /** Unter der Welt: ins Nichts fallen – meiden. */
    private static final int BELOW_WORLD = BlockFlags.AVOID_INTO | BlockFlags.AVOID_BREAK;

    private final ServerLevel level;
    private final java.util.Map<Long, BlockState> projection;
    public final int minY, maxY;
    private final int minSection;
    private static final int INITIAL_CELLS = 4096, TRIM_ABOVE = 4 * INITIAL_CELLS;
    private final it.unimi.dsi.fastutil.longs.Long2IntOpenHashMap seen = new it.unimi.dsi.fastutil.longs.Long2IntOpenHashMap(INITIAL_CELLS);
    private LevelChunk chunk;
    private int chunkX = Integer.MIN_VALUE, chunkZ = Integer.MIN_VALUE;
    private long revision;
    private final boolean fastSearchCaches;
    private final boolean fastSections;
    private final LevelChunkSection[] sections = new LevelChunkSection[8];
    private final long[] sectionKeys = new long[sections.length];
    private int cachedSections;
    private int lastSectionX = Integer.MIN_VALUE, lastSectionY, lastSectionZ;
    private LevelChunkSection lastSection;
    private boolean searching;
    private PositionCache<BlockState> states;
    private long stateRequests;
    private VerificationSnapshot verification;
    static final int MAX_VERIFICATION_CELLS = 8192;
    private static final class VerificationSnapshot {
        final it.unimi.dsi.fastutil.longs.Long2IntOpenHashMap indices =
                new it.unimi.dsi.fastutil.longs.Long2IntOpenHashMap(256);
        // Lesereihenfolge bleibt räumlich nah am Weg; der Abschluss braucht keine Hash-Iteration.
        long[] positions = new long[256];
        BlockState[] real = new BlockState[256], values = new BlockState[256];
        int size;
        boolean overflow;

        VerificationSnapshot() { indices.defaultReturnValue(-1); }

        void add(long key, BlockState actual, BlockState value) {
            if (size == positions.length) {
                int capacity = Math.min(MAX_VERIFICATION_CELLS, size * 2);
                positions = java.util.Arrays.copyOf(positions, capacity);
                real = java.util.Arrays.copyOf(real, capacity);
                values = java.util.Arrays.copyOf(values, capacity);
            }
            positions[size] = key; real[size] = actual; values[size] = value;
            indices.put(key, size++);
        }
    }
    long stateCacheHits, stateCacheMisses, worldReads, reusedFlights, sectionLookups, chunkLookups;

    public BlockCache(ServerLevel level) {
        this(level, java.util.Map.of());
    }

    /** Nur lesende Sicht auf geplante Änderungen; die Map dahinter kann eine Vorschau füllen. */
    BlockCache(ServerLevel level, java.util.Map<Long, BlockState> projection) {
        this(level, projection, true);
    }

    /** Referenzmodus ohne Zusatz-Cache, nur für Vergleichstests und Messungen. */
    BlockCache(ServerLevel level, java.util.Map<Long, BlockState> projection, boolean fastSearchCaches) {
        this(level, projection, fastSearchCaches, true);
    }

    BlockCache(ServerLevel level, java.util.Map<Long, BlockState> projection, boolean fastSearchCaches, boolean fastSections) {
        this.level = level;
        this.projection = projection;
        this.fastSearchCaches = fastSearchCaches;
        this.fastSections = fastSections;
        this.minY = level.getMinY();
        this.maxY = level.getMaxY();
        this.minSection = minY >> 4;
        seen.defaultReturnValue(-1);
    }

    public ServerLevel level() { return level; }

    void beginSearch() {
        if (verification != null) throw new IllegalStateException("route verification uses the block cache");
        searching = fastSearchCaches;
        // Die Suche läuft über mehrere Ticks: den gemerkten Chunk neu holen, er kann inzwischen entladen sein
        chunk = null;
        chunkX = chunkZ = Integer.MIN_VALUE;
        invalidateSections();
    }
    void endSearch(boolean finished) {
        searching = false;
        if (finished) { states = null; stateRequests = 0; }
    }
    boolean searching() { return searching; }
    boolean optimizedReads() { return fastSections; }

    private LevelChunk chunk(int cx, int cz) {
        if (cx != chunkX || cz != chunkZ) {
            chunkLookups++;
            chunk = level.getChunkSource().getChunkNow(cx, cz);
            chunkX = cx;
            chunkZ = cz;
        }
        return chunk;
    }

    public boolean loaded(int x, int z) { return chunk(x >> 4, z >> 4) != null; }

    /** Block an der Stelle; null = Chunk nicht geladen. Außerhalb der Bauhöhe: Leere. */
    public BlockState get(int x, int y, int z) {
        if (y < minY || y > maxY) return VOID;
        if (!searching) return uncached(x, y, z);
        if (states == null) {
            // Flag-Abfragen sind schon gemerkt. Den Zustands-Cache erst anlegen, wenn viel wiederholt gelesen
            // wird (vor allem die Sprungphysik).
            stateRequests++;
            if (stateRequests < 1024 || stateRequests <= seen.size() * 2L) return uncached(x, y, z);
            states = new PositionCache<>(12);
        }
        long key = net.minecraft.core.BlockPos.asLong(x, y, z);
        int slot = states.find(key);
        if (slot >= 0) { stateCacheHits++; return states.value(slot); }
        stateCacheMisses++;
        BlockState state = uncached(x, y, z);
        states.put(key, state);
        return state;
    }

    private BlockState uncached(int x, int y, int z) {
        if (verification != null) {
            long key = net.minecraft.core.BlockPos.asLong(x, y, z);
            int index = verification.indices.get(key);
            if (index >= 0) return verification.values[index];
            if (verification.size >= MAX_VERIFICATION_CELLS) {
                verification.overflow = true;
                return null;
            }
            BlockState real = currentWorldState(x, y, z);
            BlockState projected = projection.isEmpty() ? null : projection.get(key);
            BlockState value = projected == null ? real : projected;
            verification.add(key, real, value);
            return value;
        }
        BlockState projected = projection.isEmpty() ? null : projection.get(net.minecraft.core.BlockPos.asLong(x, y, z));
        if (projected != null) return projected;
        return currentWorldState(x, y, z);
    }

    /** Echter Zustand ohne Vorschau/Schnappschuss; lädt weiterhin keinen Chunk nach. */
    private BlockState currentWorldState(int x, int y, int z) {
        if (y < minY || y > maxY) return VOID;
        int cx = x >> 4, cy = y >> 4, cz = z >> 4;
        LevelChunkSection sec = fastSections && (searching || verification != null)
                ? cachedSection(cx, cy, cz) : section(cx, cy, cz);
        if (sec == null) return null;
        worldReads++;
        return sec.getBlockState(x & 15, y & 15, z & 15);
    }

    private LevelChunkSection section(int cx, int cy, int cz) {
        sectionLookups++;
        LevelChunk c = chunk(cx, cz);
        return c == null ? null : c.getSection(cy - minSection);
    }

    private LevelChunkSection cachedSection(int cx, int cy, int cz) {
        if (cx == lastSectionX && cy == lastSectionY && cz == lastSectionZ) return lastSection;
        int slot = (cx + 3 * cz + 5 * cy) & (sections.length - 1);
        int bit = 1 << slot;
        long key = BlockPos.asLong(cx, cy, cz);
        if ((cachedSections & bit) == 0 || sectionKeys[slot] != key) {
            sections[slot] = section(cx, cy, cz);
            sectionKeys[slot] = key;
            cachedSections |= bit;
        }
        lastSectionX = cx;
        lastSectionY = cy;
        lastSectionZ = cz;
        return lastSection = sections[slot];
    }

    private void invalidateSections() {
        cachedSections = 0;
        lastSectionX = Integer.MIN_VALUE;
        lastSection = null;
        java.util.Arrays.fill(sections, null);
    }

    void beginVerificationSnapshot() {
        if (searching || verification != null) throw new IllegalStateException("Blockcache bereits belegt");
        clearCache();
        verification = new VerificationSnapshot();
    }

    boolean verificationOverflowed() { return verification != null && verification.overflow; }

    /** Atomarer Abgleich auf dem Server-Thread: jede tatsächlich verwendete Zelle muss noch passen. */
    boolean verificationUnchanged() {
        if (verification == null || verification.overflow) return false;
        clearCache();
        for (int i = 0; i < verification.size; i++) {
            long key = verification.positions[i];
            if (currentWorldState(BlockPos.getX(key), BlockPos.getY(key), BlockPos.getZ(key)) != verification.real[i]) return false;
        }
        return true;
    }

    void endVerificationSnapshot() {
        if (verification != null) { verification = null; clearCache(); }
    }

    public int flags(int x, int y, int z) {
        if (y < minY) return BELOW_WORLD;
        long key = net.minecraft.core.BlockPos.asLong(x, y, z);
        int f = seen.get(key);
        if (f != -1) return f;
        // Flags merken sich jede Position schon. Kein zweiter Nachschlag, und diese einmaligen Lesezugriffe zählen
        // nicht für den Zustands-Cache der Sprungphysik.
        BlockState s = y > maxY ? VOID : uncached(x, y, z);
        f = s == null ? BlockFlags.UNLOADED : BlockFlags.of(s);
        seen.put(key, f);
        return f;
    }

    /**
     * Gemerkte Flags vergessen (nach Weltänderungen, z. B. vor dem Nachprüfen eines Weges). Nachbarknoten fragen
     * dieselben Zellen viele Male ab, deshalb merkt sich die Suche jede Zelle einmal.
     */
    public void clearCache() {
        // Die Ausführung leert jeden Tick. Die Tabelle behält sonst die Größe aus der Suche (oft Hunderttausende
        // Zellen), und jedes Leeren liefe über das ganze Array.
        boolean large = seen.size() > TRIM_ABOVE;
        seen.clear();
        if (large) seen.trim(INITIAL_CELLS);
        if (states != null) states.clear();
        chunk = null;
        chunkX = chunkZ = Integer.MIN_VALUE;
        invalidateSections();
        revision++;
    }

    long revision() { return revision; }
}
