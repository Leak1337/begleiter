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
    private record Sample(PathSearch search, long elapsed, long reads, long sections, long chunks) {}

    private static Sample run(CompanionEntity mob, BlockPos start, BlockPos target, boolean fast) {
        long before = System.nanoTime();
        // Both lanes retain the existing state, mining and flight caches.
        var blocks = new BlockCache((net.minecraft.server.level.ServerLevel) mob.level(), Map.of(), true, fast);
        var context = new NavContext(mob, blocks, mob.getHealth(), mob.scaffoldCount(), false);
        var search = new PathSearch(context, start, new Goal.Block(target));
        search.step(2_000_000_000L);
        return new Sample(search, System.nanoTime() - before, blocks.worldReads, blocks.sectionLookups, blocks.chunkLookups);
    }

    private static void same(GameTestHelper h, Sample reference, Sample fast) {
        h.assertTrue(reference.search.status() == PathSearch.Status.FOUND && fast.search.status() == PathSearch.Status.FOUND,
                "A/B-Suche nicht vollständig: " + reference.search.status() + "/" + fast.search.status());
        Path a = reference.search.path(), b = fast.search.path();
        h.assertTrue(a.positions.equals(b.positions) && a.moves.equals(b.moves), "Caches verändern den gewählten Weg");
        h.assertTrue(Math.abs(a.totalCost() - b.totalCost()) < 1e-9, "Caches verändern Wegkosten");
        h.assertValueEqual(reference.search.nodes(), fast.search.nodes(), "Caches verändern Knotenerweiterungen");
        h.assertValueEqual(reference.reads, fast.reads, "Der Abschnittscache ändert die eigentlichen Blocklesungen");
    }

    public static void measure(GameTestHelper h, CompanionEntity mob, BlockPos start, BlockPos target, String name) {
        for (int i = 0; i < 12; i++) same(h, run(mob, start, target, false), run(mob, start, target, true));
        long[] baseline = new long[100], cached = new long[100];
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
        double a = medianMillis(baseline), b = medianMillis(cached);
        com.mojang.logging.LogUtils.getLogger().info(String.format(Locale.ROOT,
                "[Nav-Fast] %s median=%.3f->%.3fms max=%.3f->%.3fms sections=%d->%d chunks=%d->%d blockReads=%d nodes=%d identical=true",
                name, a, b, baseline[baseline.length - 1] / 1e6, cached[cached.length - 1] / 1e6,
                reference.sections, fast.sections, reference.chunks, fast.chunks, fast.reads, fast.search.nodes()));
    }

    private static double medianMillis(long[] times) {
        return (times[times.length / 2 - 1] + times[times.length / 2]) / 2e6;
    }
}
