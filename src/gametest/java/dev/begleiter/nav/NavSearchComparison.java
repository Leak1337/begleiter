package dev.begleiter.nav;

import java.util.Arrays;
import java.util.Locale;
import java.util.Map;
import dev.begleiter.CompanionEntity;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTestHelper;

/** Paarweise Messung ganzer Suchen in derselben Welt, samt Anlegen und Freigeben von Kontext und Cache. */
public final class NavSearchComparison {
    private NavSearchComparison() {}
    private record Sample(PathSearch search, long elapsed, long reads, long stateHits, long miningHits, long reusedFlights) {}

    private static Sample run(CompanionEntity mob, BlockPos start, BlockPos target, boolean fast) {
        long before = System.nanoTime();
        var blocks = new BlockCache((net.minecraft.server.level.ServerLevel) mob.level(), Map.of(), fast);
        var context = new NavContext(mob, blocks, mob.getHealth(), mob.scaffoldCount(), false);
        var search = new PathSearch(context, start, new Goal.Block(target));
        search.step(2_000_000_000L);
        return new Sample(search, System.nanoTime() - before, blocks.worldReads, blocks.stateCacheHits, context.miningCacheHits,
                blocks.reusedFlights);
    }

    private static void same(GameTestHelper h, Sample reference, Sample fast) {
        h.assertTrue(reference.search.status() == PathSearch.Status.FOUND && fast.search.status() == PathSearch.Status.FOUND,
                "A/B-Suche nicht vollständig: " + reference.search.status() + "/" + fast.search.status());
        Path a = reference.search.path(), b = fast.search.path();
        h.assertTrue(a.positions.equals(b.positions) && a.moves.equals(b.moves), "Caches verändern den gewählten Weg");
        h.assertTrue(Math.abs(a.totalCost() - b.totalCost()) < 1e-9, "Caches verändern Wegkosten");
        h.assertValueEqual(reference.search.nodes(), fast.search.nodes(), "Caches verändern Knotenerweiterungen");
    }

    public static void measure(GameTestHelper h, CompanionEntity mob, BlockPos start, BlockPos target, String name) {
        for (int i = 0; i < 12; i++) same(h, run(mob, start, target, false), run(mob, start, target, true));
        long[] baseline = new long[11], cached = new long[11];
        Sample reference = null, fast = null;
        for (int i = 0; i < baseline.length; i++) {
            if ((i & 1) == 0) {
                reference = run(mob, start, target, false);
                fast = run(mob, start, target, true);
            } else {
                fast = run(mob, start, target, true);
                reference = run(mob, start, target, false);
            }
            same(h, reference, fast);
            baseline[i] = reference.elapsed;
            cached[i] = fast.elapsed;
        }
        Arrays.sort(baseline);
        Arrays.sort(cached);
        double a = baseline[baseline.length / 2] / 1e6, b = cached[cached.length / 2] / 1e6;
        com.mojang.logging.LogUtils.getLogger().info(String.format(Locale.ROOT,
                "[Nav-AB] %s baseline=%.3fms cached=%.3fms change=%+.1f%% reads=%d->%d stateHits=%d miningHits=%d reusedFlights=%d nodes=%d cost=%.3f identical=true",
                name, a, b, (b / a - 1) * 100, reference.reads, fast.reads, fast.stateHits, fast.miningHits,
                fast.reusedFlights, fast.search.nodes(), fast.search.path().totalCost()));
    }
}
