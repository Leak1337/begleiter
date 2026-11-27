package dev.begleiter.nav;

import java.util.HashMap;
import net.fabricmc.fabric.api.gametest.v1.GameTest;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import dev.begleiter.CompanionEntity;
import dev.begleiter.CompanionMod;

public class NavCacheGameTests {
    @GameTest public void sectionCacheReusesAlternatingSectionsWithoutChangingStates(GameTestHelper h) {
        BlockPos p = target(h);
        BlockPos q = p.offset(16, 0, 0);
        h.getLevel().getChunk(q.getX() >> 4, q.getZ() >> 4);
        var cache = new BlockCache(h.getLevel(), java.util.Map.of(), true, true);
        var reference = new BlockCache(h.getLevel(), java.util.Map.of(), true, false);
        cache.beginSearch(); reference.beginSearch();
        for (int i = 0; i < 32; i++) {
            BlockPos at = i % 2 == 0 ? p : q;
            h.assertTrue(cache.get(at.getX(), at.getY(), at.getZ()) == reference.get(at.getX(), at.getY(), at.getZ()),
                    "Section lookup changed the block state");
        }
        h.assertValueEqual(cache.chunkLookups, 2L, "Alternating sections should reuse the loaded chunks");
        h.assertValueEqual(reference.chunkLookups, 32L, "Reference did not alternate chunks");
        cache.endSearch(false);
        cache.beginSearch();
        h.setBlock(2, 1, 2, Blocks.MAGMA_BLOCK);
        h.assertTrue(cache.get(p.getX(), p.getY(), p.getZ()).is(Blocks.MAGMA_BLOCK), "New section slice kept an obsolete state");
        h.succeed();
    }

    private static NavContext context(GameTestHelper h) {
        var mob = CompanionMod.COMPANION.create(h.getLevel(), net.minecraft.world.entity.EntitySpawnReason.COMMAND);
        h.assertTrue(mob != null, "Testbegleiter fehlt");
        mob.stay();
        return new NavContext(mob);
    }

    private static BlockPos target(GameTestHelper h) {
        h.setBlock(2, 1, 2, Blocks.DIRT);
        return h.absolutePos(new BlockPos(2, 1, 2));
    }

    @GameTest public void blockSnapshotWirdInvalidiertUndLiveZugriffBleibtFrisch(GameTestHelper h) {
        BlockPos p = target(h);
        var cache = new BlockCache(h.getLevel());
        cache.beginSearch();
        for (int i = 0; i < 1024; i++) cache.get(p.getX(), p.getY(), p.getZ());
        h.assertTrue(cache.get(p.getX(), p.getY(), p.getZ()).is(Blocks.DIRT), "Erster Zustand fehlt");
        h.setBlock(2, 1, 2, Blocks.LAVA);
        h.assertTrue(cache.get(p.getX(), p.getY(), p.getZ()).is(Blocks.DIRT), "Suchsnapshot nicht wiederverwendet");
        cache.clearCache();
        h.assertTrue(cache.get(p.getX(), p.getY(), p.getZ()).is(Blocks.LAVA), "Neue Lava nach Invalidierung unsichtbar");
        cache.endSearch(false);
        h.setBlock(2, 1, 2, Blocks.STONE);
        h.assertTrue(cache.get(p.getX(), p.getY(), p.getZ()).is(Blocks.STONE), "Livezugriff verwendet Suchcache");
        h.succeed();
    }

    @GameTest public void projektionHatVorrangUndWirdNeuGelesen(GameTestHelper h) {
        BlockPos p = target(h);
        var projection = new HashMap<Long, BlockState>();
        projection.put(p.asLong(), Blocks.AIR.defaultBlockState());
        var cache = new BlockCache(h.getLevel(), projection);
        cache.beginSearch();
        for (int i = 0; i < 1024; i++) cache.get(p.getX(), p.getY(), p.getZ());
        h.assertTrue(cache.get(p.getX(), p.getY(), p.getZ()).isAir(), "Vorschau ignoriert");
        projection.put(p.asLong(), Blocks.OBSIDIAN.defaultBlockState());
        cache.clearCache();
        h.assertTrue(cache.get(p.getX(), p.getY(), p.getZ()).is(Blocks.OBSIDIAN), "Geänderte Vorschau ignoriert");
        cache.endSearch(true);
        h.assertTrue(h.getLevel().getBlockState(p).is(Blocks.DIRT), "Vorschau hat die Welt verändert");
        h.succeed();
    }

    @GameTest public void ungeladenerChunkBleibtUnbekanntOhneNachladen(GameTestHelper h) {
        int x = 29_000_000, z = 29_000_000;
        var cache = new BlockCache(h.getLevel());
        cache.beginSearch();
        for (int i = 0; i < 1024; i++) cache.get(x, 64, z);
        for (int i = 0; i < 4; i++) h.assertTrue(cache.get(x, 64, z) == null, "Ungeladener Chunk als Luft behandelt");
        h.assertTrue(h.getLevel().getChunkSource().getChunkNow(x >> 4, z >> 4) == null, "Chunk wurde nachgeladen");
        h.assertValueEqual(cache.stateCacheMisses, 1L, "Null wurde nicht gecacht");
        cache.endSearch(true);
        h.succeed();
    }

    @GameTest public void zuvorUnbekannterChunkWirdNachInvalidierungErkannt(GameTestHelper h) {
        int x = 1_234_560, z = 1_234_560;
        var cache = new BlockCache(h.getLevel());
        h.assertTrue(cache.get(x, 64, z) == null, "Fixture bereits geladen");
        h.getLevel().getChunk(x >> 4, z >> 4); // Nur die Fixture lädt ausdrücklich einen Chunk.
        cache.clearCache();
        h.assertTrue(cache.get(x, 64, z) != null, "Negativer Chunkcache blieb nach Invalidierung hängen");
        h.succeed();
    }

    @GameTest public void abbaukostenBeachtenRegelwechselUndNeueFluessigkeit(GameTestHelper h) {
        BlockPos p = target(h);
        var context = context(h);
        context.blocks.beginSearch();
        double first = context.breakCost(p.getX(), p.getY(), p.getZ());
        h.assertTrue(first < Costs.INF, "Erde nicht abbaubar");
        h.assertTrue(context.breakCost(p.getX(), p.getY(), p.getZ()) == first, "Kosten nicht stabil");
        h.assertValueEqual(context.miningCacheHits, 1L, "Kosten wurden nicht wiederverwendet");
        context.allowBreak = false;
        h.assertTrue(context.breakCost(p.getX(), p.getY(), p.getZ()) >= Costs.INF, "Abbauverbot ignoriert");
        context.allowBreak = true;
        h.setBlock(3, 1, 2, Blocks.WATER);
        context.blocks.clearCache();
        h.assertTrue(context.breakCost(p.getX(), p.getY(), p.getZ()) >= Costs.INF, "Neue Flüssigkeit ignoriert");
        context.blocks.endSearch(true);
        h.succeed();
    }

    @GameTest public void warmeAbbaukostenUmgehenKeineRieselblockTiefengrenze(GameTestHelper h) {
        BlockPos base = target(h);
        var projection = new HashMap<Long, BlockState>();
        for (int y = 0; y < 18; y++) projection.put(base.above(y).asLong(), Blocks.GRAVEL.defaultBlockState());
        var mob = CompanionMod.COMPANION.create(h.getLevel(), net.minecraft.world.entity.EntitySpawnReason.COMMAND);
        h.assertTrue(mob != null, "Testbegleiter fehlt");
        var fast = new NavContext(mob, new BlockCache(h.getLevel(), projection), mob.getHealth(), 0, false);
        var reference = new NavContext(mob, new BlockCache(h.getLevel(), projection, false), mob.getHealth(), 0, false);
        fast.blocks.beginSearch();
        // Erst jedes kürzere Endstück vorwärmen: keines darf die Rekursionstiefe zurücksetzen, wenn es von unten erreicht wird.
        for (int dy = 17; dy > 0; dy--) {
            BlockPos p = base.above(dy);
            double expected = reference.breakCost(p.getX(), p.getY(), p.getZ());
            h.assertTrue(expected < Costs.INF, "Kurze Kiesfolge unerwartet verboten");
            h.assertTrue(fast.breakCost(p.getX(), p.getY(), p.getZ()) == expected, "Kosten der Kiesfolge verändert");
        }
        h.assertTrue(reference.breakCost(base.getX(), base.getY(), base.getZ()) >= Costs.INF, "Referenzgrenze fehlt");
        h.assertTrue(fast.breakCost(base.getX(), base.getY(), base.getZ()) >= Costs.INF, "Warme Kosten umgehen Tiefengrenze");
        fast.blocks.endSearch(true);
        h.succeed();
    }

    @GameTest public void suchendeUndAusnahmenGebenCacheFrei(GameTestHelper h) {
        var context = context(h);
        BlockPos p = target(h);
        var failing = new Goal() {
            public boolean isInGoal(int x, int y, int z) { throw new IllegalStateException("Testziel"); }
            public double heuristic(int x, int y, int z) { return 0; }
        };
        try { new PathSearch(context, p, failing).step(1_000_000); }
        catch (IllegalStateException expected) { }
        h.assertTrue(!context.blocks.searching(), "Cache nach Ausnahme noch aktiv");
        var search = new PathSearch(context, p, new Goal.Block(p));
        search.step(1_000_000);
        h.assertTrue(search.done() && !context.blocks.searching(), "Cache nach Suchende noch aktiv");
        h.succeed();
    }

    @GameTest(structure = "begleiter-gametest:bench")
    public void wiederverwendeteFluegeBehaltenAllePlanwerte(GameTestHelper h) {
        for (int x = 0; x <= 12; x++) for (int z = 2; z <= 8; z++)
            if (x <= 2 || x >= 5) for (int y = 0; y <= 3; y++) h.setBlock(x, y, z, Blocks.STONE);
        var fast = context(h);
        var mob = CompanionMod.COMPANION.create(h.getLevel(), net.minecraft.world.entity.EntitySpawnReason.COMMAND);
        var reference = new NavContext(mob, new BlockCache(h.getLevel(), java.util.Map.of(), false),
                mob.getHealth(), mob.scaffoldCount(), false);
        BlockPos from = h.absolutePos(new BlockPos(2, 4, 5));
        fast.blocks.beginSearch();
        int valid = 0;
        for (int distance : new int[]{3, 4, 8}) {
            BlockPos to = from.offset(distance, 0, 0);
            JumpSim.Plan a = JumpSim.plan(reference, from.getX(), from.getY(), from.getZ(), to.getX(), to.getY(), to.getZ());
            JumpSim.Plan b = JumpSim.plan(fast, from.getX(), from.getY(), from.getZ(), to.getX(), to.getY(), to.getZ());
            h.assertTrue((a == null) == (b == null), "Sprungfreigabe verändert");
            if (a == null) continue;
            valid++;
            h.assertTrue(a.profile == b.profile && a.sprint == b.sprint && a.flightTicks == b.flightTicks
                    && a.unsafeMisses == b.unsafeMisses && a.deadlyMisses == b.deadlyMisses, "Sprung-/Risikoprofil verändert");
            double[] expected = {a.vRun, a.takeoffX, a.takeoffY, a.takeoffZ, a.dirX, a.dirZ, a.runUp,
                    a.landX, a.landY, a.landZ, a.margin, a.reach, a.cost};
            double[] actual = {b.vRun, b.takeoffX, b.takeoffY, b.takeoffZ, b.dirX, b.dirZ, b.runUp,
                    b.landX, b.landY, b.landZ, b.margin, b.reach, b.cost};
            h.assertTrue(java.util.Arrays.equals(expected, actual), "Numerische Sprungwerte verändert");
        }
        h.assertTrue(valid > 0 && fast.blocks.reusedFlights > 0, "Fixture hat keine gültigen/redundanten Flüge geprüft");
        fast.blocks.endSearch(true);
        h.succeed();
    }
}
