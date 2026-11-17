package dev.begleiter.nav;

import java.util.Arrays;
import java.util.Locale;
import dev.begleiter.CompanionEntity;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTestHelper;

/** Gepaarte Messung der ganzen Suche mit 1-ms-Scheiben; gleiche Welt und wechselnde Reihenfolge. */
public final class NavSliceComparison {
    private NavSliceComparison() {}
    private record Sample(PathSearch search, long elapsed, long longest, int slices) {}

    private static Sample run(CompanionEntity mob, BlockPos from, BlockPos target, boolean fine) {
        long before = System.nanoTime();
        var search = new PathSearch(new NavContext(mob), from, new Goal.Block(target), PathSearch.WEIGHT, System::nanoTime, fine);
        long longest = 0;
        int slices = 0;
        while (!search.done() && slices < 10_000) {
            long start = System.nanoTime();
            search.step(1_000_000L);
            longest = Math.max(longest, System.nanoTime() - start);
            slices++;
        }
        return new Sample(search, System.nanoTime() - before, longest, slices);
    }

    private static void same(GameTestHelper h, Sample a, Sample b) {
        h.assertTrue(a.search.status() == PathSearch.Status.FOUND && b.search.status() == PathSearch.Status.FOUND, "Scheibenvergleich unvollständig");
        h.assertTrue(a.search.path().positions.equals(b.search.path().positions)
                && a.search.path().moves.equals(b.search.path().moves), "Scheibengrenze verändert Weg");
        h.assertTrue(a.search.path().totalCost() == b.search.path().totalCost(), "Scheibengrenze verändert Wegkosten");
        h.assertValueEqual(a.search.nodes(), b.search.nodes(), "Scheibengrenze verändert Knotenzahl");
    }

    public static void measure(GameTestHelper h, CompanionEntity mob, BlockPos from, BlockPos target, String name) {
        for (int i = 0; i < 8; i++) same(h, run(mob, from, target, false), run(mob, from, target, true));
        long[] a = new long[11], b = new long[11], peakA = new long[11], peakB = new long[11];
        long worstA = 0, worstB = 0;
        Sample coarse = null, fine = null;
        for (int i = 0; i < a.length; i++) {
            if ((i & 1) == 0) { coarse = run(mob, from, target, false); fine = run(mob, from, target, true); }
            else { fine = run(mob, from, target, true); coarse = run(mob, from, target, false); }
            same(h, coarse, fine);
            a[i] = coarse.elapsed; b[i] = fine.elapsed;
            peakA[i] = coarse.longest; peakB[i] = fine.longest;
            worstA = Math.max(worstA, coarse.longest); worstB = Math.max(worstB, fine.longest);
        }
        Arrays.sort(a); Arrays.sort(b); Arrays.sort(peakA); Arrays.sort(peakB);
        com.mojang.logging.LogUtils.getLogger().info(String.format(Locale.ROOT,
                "[Nav-Slices] %s coarse=%.3fms fine=%.3fms change=%+.1f%% medianPeak=%.3f->%.3fms worstPeak=%.3f->%.3fms slices=%d->%d identical=true",
                name, a[5] / 1e6, b[5] / 1e6, ((double)b[5] / a[5] - 1) * 100,
                peakA[5] / 1e6, peakB[5] / 1e6, worstA / 1e6, worstB / 1e6, coarse.slices, fine.slices));
    }
}
