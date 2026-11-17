package dev.begleiter;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Predicate;

import dev.begleiter.CompanionMemory.Kind;
import dev.begleiter.world.SectionScanner;
import net.fabricmc.fabric.api.gametest.v1.GameTest;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.material.Fluids;

public class OreScanGameTests {
    private static final Predicate<BlockState> DIAMOND = s -> s.is(Blocks.DIAMOND_ORE);

    private static List<BlockPos> reference(ServerLevel sl, int minX, int maxX, int y, int minZ, int maxZ,
                                           Predicate<BlockState> predicate) {
        List<BlockPos> result = new ArrayList<>();
        if (sl.isOutsideBuildHeight(y)) return result;
        for (int x = minX; x <= maxX; x++) for (int z = minZ; z <= maxZ; z++) {
            var p = new BlockPos(x, y, z);
            if (!sl.hasChunkAt(p)) continue;
            var state = sl.getBlockState(p);
            if (!state.isAir() && predicate.test(state)) result.add(p);
        }
        return result;
    }

    @GameTest
    public void ore_scan_matches_reference_and_reduces_reads(GameTestHelper h) {
        var sl = h.getLevel();
        int center = -32_000, y = sl.getMinY() + sl.getHeight() - 32;
        // Negative chunk boundaries, solid irrelevant sections and one ore-bearing section.
        for (int cx = (center - 16) >> 4; cx <= (center + 16) >> 4; cx++)
            for (int cz = (center - 16) >> 4; cz <= (center + 16) >> 4; cz++) sl.getChunk(cx, cz);
        for (int x = center - 16; x <= center + 16; x++) for (int z = center - 16; z <= center + 16; z++)
            sl.getChunkSource().getChunkNow(x >> 4, z >> 4).getSection(sl.getSectionIndex(y))
                    .setBlockState(x & 15, y & 15, z & 15, Blocks.STONE.defaultBlockState());
        for (int offset : new int[] {1, 4, 9}) {
            int x = center + offset, z = center + 2;
            sl.getChunkSource().getChunkNow(x >> 4, z >> 4).getSection(sl.getSectionIndex(y))
                    .setBlockState(x & 15, y & 15, z & 15, Blocks.DIAMOND_ORE.defaultBlockState());
        }
        var expected = reference(sl, center - 16, center + 16, y, center - 16, center + 16, DIAMOND);
        List<BlockPos> actual = new ArrayList<>();
        var result = SectionScanner.scanLayer(sl, center - 16, center + 16, y, center - 16, center + 16, DIAMOND,
                (x, scanY, z, state) -> { actual.add(new BlockPos(x, scanY, z)); return true; });
        h.assertValueEqual(actual, expected, "Treffer oder Reihenfolge weichen vom alten Scan ab");
        h.assertValueEqual(actual.size(), 3, "Fixture enthält nicht drei Diamanterze");
        h.assertValueEqual(result.chunksChecked(), 9, "Mehr als eine Chunkabfrage pro Chunk");
        h.assertValueEqual(result.sectionsSkipped(), 8, "Unpassende Paletten nicht übersprungen");
        h.assertValueEqual(result.blockReads(), 256L, "Unerwartete Zahl gelesener Blockzustände");
        h.assertTrue(result.completed(), "Scan unerwartet abgebrochen");
        com.mojang.logging.LogUtils.getLogger().info("ORE-SCAN referenceReads=1089 sectionReads={} chunks={} skipped={} hits={}",
                result.blockReads(), result.chunksChecked(), result.sectionsSkipped(), actual.size());
        var early = SectionScanner.scanLayer(sl, center - 16, center + 16, y, center - 16, center + 16,
                s -> s.is(Blocks.STONE), (x, scanY, z, state) -> false);
        h.assertTrue(!early.completed() && early.blockReads() == 1, "Früher Abbruch liest weitere Blöcke");
        h.succeed();
    }

    @GameTest
    public void ore_scan_clips_bounds_and_world_height(GameTestHelper h) {
        var sl = h.getLevel();
        var base = h.absolutePos(new BlockPos(2, 1, 2));
        int top = sl.getMinY() + sl.getHeight() - 1;
        for (int y : new int[] {sl.getMinY(), top}) {
            var pos = new BlockPos(base.getX(), y, base.getZ());
            var chunk = sl.getChunkSource().getChunkNow(pos.getX() >> 4, pos.getZ() >> 4);
            var section = chunk.getSection(sl.getSectionIndex(y));
            var old = section.setBlockState(pos.getX() & 15, y & 15, pos.getZ() & 15, Blocks.DIAMOND_ORE.defaultBlockState());
            try {
                List<BlockPos> hits = new ArrayList<>();
                var result = SectionScanner.scanLayer(sl, pos.getX(), pos.getX(), y, pos.getZ(), pos.getZ(), DIAMOND,
                        (x, scanY, z, state) -> { hits.add(new BlockPos(x, scanY, z)); return true; });
                h.assertValueEqual(hits, List.of(pos), "Welthöhe oder inklusive Grenzen falsch");
                h.assertValueEqual(result.blockReads(), 1L, "Außerhalb des Suchrechtecks gelesen");
            } finally { section.setBlockState(pos.getX() & 15, y & 15, pos.getZ() & 15, old); }
        }
        for (int y : new int[] {sl.getMinY() - 1, top + 1}) {
            var result = SectionScanner.scanLayer(sl, base.getX(), base.getX(), y, base.getZ(), base.getZ(), DIAMOND,
                    (x, scanY, z, state) -> { throw new AssertionError("Besuch außerhalb Welthöhe"); });
            h.assertTrue(result.blockReads() == 0 && result.chunksChecked() == 0, "Außerhalb der Welt gelesen");
        }
        h.succeed();
    }

    @GameTest
    public void ore_scan_skips_air_and_unloaded_chunks(GameTestHelper h) {
        var sl = h.getLevel();
        var base = h.absolutePos(new BlockPos(2, 1, 2));
        int y = sl.getMinY() + sl.getHeight() - 16;
        var empty = SectionScanner.scanLayer(sl, base.getX(), base.getX(), y, base.getZ(), base.getZ(), s -> true,
                (x, scanY, z, state) -> { throw new AssertionError("Luft wurde besucht"); });
        h.assertTrue(empty.blockReads() == 0 && empty.sectionsSkipped() == 1, "Leere Section nicht übersprungen");
        int far = 24_000_000;
        h.assertTrue(sl.getChunkSource().getChunkNow(far >> 4, -far >> 4) == null, "Testchunk bereits geladen");
        var result = SectionScanner.scanLayer(sl, far, far + 15, y, -far, -far + 15, DIAMOND,
                (x, scanY, z, state) -> { throw new AssertionError("Ungeladener Chunk besucht"); });
        h.assertTrue(result.blockReads() == 0 && result.chunksChecked() == 1, "Ungeladenen Chunk gelesen");
        h.assertTrue(sl.getChunkSource().getChunkNow(far >> 4, -far >> 4) == null, "Scanner hat Chunk nachgeladen");
        h.succeed();
    }

    private static void referenceSense(CompanionMemory memory, CompanionEntity bot, int y, int radius) {
        var sl = (ServerLevel) bot.level();
        var center = bot.blockPosition();
        var ores = new Resources.Res[] {Resources.COAL, Resources.IRON, Resources.GOLD, Resources.DIAMOND,
                Resources.REDSTONE, Resources.LAPIS, Resources.COPPER, Resources.EMERALD};
        for (int x = -radius; x <= radius; x++) for (int z = -radius; z <= radius; z++) {
            var p = new BlockPos(center.getX() + x, y, center.getZ() + z);
            if (!sl.hasChunkAt(p)) continue;
            var state = sl.getBlockState(p);
            if (Math.abs(x) > CompanionMemory.XRAY_RADIUS || Math.abs(z) > CompanionMemory.XRAY_RADIUS) {
                if (Resources.DIAMOND.block().test(state)) note(memory, Kind.ORE, Resources.DIAMOND.key(), sl, p);
                continue;
            }
            if (state.getFluidState().isSourceOfType(Fluids.LAVA)) note(memory, Kind.LAVA, "", sl, p);
            else if (state.getFluidState().isEmpty()) for (var res : ores)
                if (res.block().test(state)) { note(memory, Kind.ORE, res.key(), sl, p); break; }
        }
    }

    private static void note(CompanionMemory memory, Kind kind, String key, ServerLevel sl, BlockPos p) {
        var known = memory.nearest(kind, key, sl, p, kind.merge + 1);
        if (known != null) known.seen = sl.getGameTime();
        else memory.remember(kind, key, sl, p);
    }

    @GameTest
    public void ore_scan_memory_never_loads_chunks(GameTestHelper h) {
        var sl = h.getLevel();
        var c = new CompanionEntity(CompanionMod.COMPANION, sl);
        c.setPos(-24_000_000.5, 100, 24_000_000.5);
        int cx = c.blockPosition().getX() >> 4, cz = c.blockPosition().getZ() >> 4;
        for (int x = cx - 2; x <= cx + 2; x++) for (int z = cz - 2; z <= cz + 2; z++)
            h.assertTrue(sl.getChunkSource().getChunkNow(x, z) == null, "Testbereich bereits geladen");
        int loaded = sl.getChunkSource().getLoadedChunksCount();
        for (int layer = 0; layer < CompanionMemory.XRAY_HEIGHT * 2 + 1; layer++) c.memory.xrayScan(c, sl);
        h.assertValueEqual(sl.getChunkSource().getLoadedChunksCount(), loaded, "Erz-Gespür lädt Chunks nach");
        h.assertTrue(c.memory.places().isEmpty(), "Erz-Gespür erfindet ungeladene Quellen");
        h.succeed();
    }

    @GameTest
    public void ore_scan_preserves_memory_and_extended_diamond_ring(GameTestHelper h) {
        var sl = h.getLevel();
        var c = new CompanionEntity(CompanionMod.COMPANION, sl) {
            @Override public int diamondSenseRadius() { return 48; }
        };
        var center = h.absolutePos(new BlockPos(2, 25, 2));
        c.setPos(center.getX() + 0.5, center.getY(), center.getZ() + 0.5);
        int y = center.getY() - CompanionMemory.XRAY_HEIGHT;
        // Testaufbau vor dem Vergleich der beiden nur lesenden Suchen.
        var blocks = new net.minecraft.world.level.block.Block[] {Blocks.COAL_ORE, Blocks.IRON_ORE, Blocks.GOLD_ORE,
                Blocks.DIAMOND_ORE, Blocks.REDSTONE_ORE, Blocks.LAPIS_ORE, Blocks.COPPER_ORE, Blocks.EMERALD_ORE,
                Blocks.OBSIDIAN, Blocks.LAVA, Blocks.WATER, Blocks.DEEPSLATE_DIAMOND_ORE};
        for (int i = 0; i < blocks.length; i++) sl.setBlock(new BlockPos(center.getX() + i - 6, y, center.getZ() + 1), blocks[i].defaultBlockState(), 2);
        sl.setBlock(new BlockPos(center.getX() + 33, y, center.getZ()), Blocks.DIAMOND_ORE.defaultBlockState(), 2);
        sl.setBlock(new BlockPos(center.getX() + 33, y, center.getZ() + 1), Blocks.IRON_ORE.defaultBlockState(), 2);
        sl.setBlock(new BlockPos(center.getX() + 33, y, center.getZ() + 2), Blocks.LAVA.defaultBlockState(), 2);
        sl.setBlock(new BlockPos(center.getX() + 15, y, center.getZ()), Blocks.LAVA.defaultBlockState()
                .setValue(net.minecraft.world.level.block.LiquidBlock.LEVEL, 1), 2);
        int loaded = sl.getChunkSource().getLoadedChunksCount();
        var expected = new CompanionMemory();
        referenceSense(expected, c, y, 48);
        c.memory.xrayScan(c, sl);
        h.assertValueEqual(c.memory.save(), expected.save(), "Optimierung verändert Erz-/Lavagedächtnis");
        h.assertValueEqual(sl.getChunkSource().getLoadedChunksCount(), loaded, "Vergleichsscan lädt Chunks nach");
        var outside = new BlockPos(center.getX() + 33, y, center.getZ());
        h.assertTrue(c.memory.nearest(Kind.ORE, Resources.DIAMOND.key(), sl, outside, 1) != null, "Erweiterter Diamantring fehlt");
        h.assertTrue(c.memory.nearest(Kind.ORE, Resources.IRON.key(), sl, outside.south(), 1) == null, "Andere Erze außerhalb Kernradius erkannt");
        h.assertTrue(c.memory.nearest(Kind.LAVA, null, sl, outside.south(2), 1) == null, "Lava außerhalb Kernradius erkannt");
        h.succeed();
    }
}
