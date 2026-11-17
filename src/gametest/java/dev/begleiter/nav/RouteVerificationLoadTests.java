package dev.begleiter.nav;

import java.util.Arrays;
import java.util.Locale;
import dev.begleiter.CompanionMod;
import net.fabricmc.fabric.api.gametest.v1.GameTest;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;

/** Begrenzte Lastfälle; Zeiten sind Messwerte, keine schwankenden CI-Grenzassertionen. */
public class RouteVerificationLoadTests {
    private static final String BENCH = "begleiter-gametest:bench";

    private static BlockPos cell(GameTestHelper h, int i) {
        return h.absolutePos(new BlockPos(2 + i % 44, 1 + i / (44 * 8), 2 + i / 44 % 8));
    }

    private static BlockState get(BlockCache cache, BlockPos p) {
        return cache.get(p.getX(), p.getY(), p.getZ());
    }

    private record SnapshotSample(long total, long capture, long finish) {}

    private static SnapshotSample snapshot(GameTestHelper h, int count) {
        var cells = new BlockPos[count];
        for (int i = 0; i < count; i++) cells[i] = cell(h, i);
        var cache = new BlockCache(h.getLevel());
        long reads = cache.worldReads, start = System.nanoTime();
        cache.beginVerificationSnapshot();
        for (BlockPos p : cells) get(cache, p);
        long captured = System.nanoTime();
        boolean unchanged = cache.verificationUnchanged();
        long finished = System.nanoTime();
        boolean overflow = cache.verificationOverflowed();
        cache.endVerificationSnapshot();
        long end = System.nanoTime();
        h.assertTrue(unchanged && !overflow, "Unveränderte Laststrecke verworfen");
        h.assertTrue(cache.worldReads - reads == 2L * count, "Lastfall prüft nicht alle geladenen Zellen");
        return new SnapshotSample(end - start, captured - start, finished - captured);
    }

    private static void measure(GameTestHelper h, int count) {
        for (int i = 0; i < 16; i++) snapshot(h, count);
        long[] total = new long[21], capture = new long[21], finish = new long[21];
        long peak = 0;
        for (int i = 0; i < total.length; i++) {
            var s = snapshot(h, count); total[i] = s.total; capture[i] = s.capture; finish[i] = s.finish;
            peak = Math.max(peak, s.finish);
        }
        Arrays.sort(total); Arrays.sort(capture); Arrays.sort(finish);
        com.mojang.logging.LogUtils.getLogger().info(String.format(Locale.ROOT,
                "[Nav-Final] cells=%d totalMedian=%.4fms captureMedian=%.4fms finalMedian=%.4fms finalWorst=%.4fms reads=%d",
                count, total[10] / 1e6, capture[10] / 1e6, finish[10] / 1e6, peak / 1e6, 2L * count));
    }

    @GameTest(structure = BENCH)
    public void kleineUndVolleSchnappschuesseVollstaendigVerglichen(GameTestHelper h) {
        measure(h, 256); measure(h, BlockCache.MAX_VERIFICATION_CELLS); h.succeed();
    }

    @GameTest(structure = BENCH)
    public void wachstumDoppelteZellenUndSpaeteAenderungen(GameTestHelper h) {
        var cache = new BlockCache(h.getLevel()); cache.beginVerificationSnapshot();
        for (int i = 0; i < BlockCache.MAX_VERIFICATION_CELLS; i++) {
            BlockPos p = cell(h, i); BlockState real = h.getLevel().getBlockState(p);
            h.assertTrue(get(cache, p) == real && get(cache, p) == real, "Doppelte Zelle verliert ihren Wert");
            cache.flags(p.getX(), p.getY(), p.getZ());
        }
        h.assertTrue(!cache.verificationOverflowed() && cache.verificationUnchanged(), "Wachstum zählt doppelte Zellen");
        for (int i : new int[]{0, 255, 256, 1023, 1024, 4095, 8191}) {
            BlockPos p = cell(h, i); BlockState old = h.getLevel().getBlockState(p);
            BlockState changed = old.is(Blocks.DIRT) ? Blocks.STONE.defaultBlockState() : Blocks.DIRT.defaultBlockState();
            try {
                h.getLevel().setBlock(p, changed, 3);
                h.assertTrue(get(cache, p) == old, "Wachstum verliert gefrorenen Wert");
                h.assertTrue(!cache.verificationUnchanged(), "Späte Änderung nach Wachstum übersehen: " + i);
            } finally { h.getLevel().setBlock(p, old, 3); }
        }
        h.assertTrue(cache.verificationUnchanged(), "Wiederhergestellte Welt bleibt gesperrt");
        cache.endVerificationSnapshot(); h.succeed();
    }

    private static Path longPath(GameTestHelper h) {
        BlockPos start = h.absolutePos(new BlockPos(4, 1, 6));
        var root = new Node(start.getX(), start.getY(), start.getZ(), 0); root.g = 0;
        Node last = root;
        for (int i = 0; i < RouteProjection.MAX_MOVES; i++) {
            Move move = i / 40 % 2 == 0 ? Move.TRAVERSE_E : Move.TRAVERSE_W;
            var n = new Node(last.x + move.dx, last.y, last.z, 0);
            n.prev = last; n.move = move; n.g = last.g + Costs.WALK; last = n;
        }
        return Path.from(last, true);
    }

    private static Path reverse(Path path) {
        BlockPos start = path.end(); var root = new Node(start.getX(), start.getY(), start.getZ(), 0); root.g = 0;
        Node last = root;
        for (int i = path.length() - 1; i >= 0; i--) {
            BlockPos p = path.positions.get(i); var n = new Node(p.getX(), p.getY(), p.getZ(), 0);
            n.prev = last; n.move = path.moves.get(i) == Move.TRAVERSE_E ? Move.TRAVERSE_W : Move.TRAVERSE_E;
            n.g = last.g + Costs.WALK; last = n;
        }
        return Path.from(last, true);
    }

    @GameTest(structure = BENCH)
    public void langeRundenBleibenVollstaendigUndPortioniert(GameTestHelper h) {
        for (int x = 0; x < 48; x++) for (int z = 0; z < 12; z++) h.setBlock(x, 0, z, Blocks.STONE);
        var mob = h.spawn(CompanionMod.COMPANION, new BlockPos(4, 1, 6));
        mob.stay(); mob.setNoAi(true); mob.getInventory().clearContent();
        Path path = longPath(h), returning = reverse(path);
        long[] total = new long[11], peak = new long[11]; int slices = 0;
        for (int run = -8; run < total.length; run++) {
            var out = new NavContext(mob, true); var back = new NavContext(mob, true);
            out.allowBreak = out.allowPlace = back.allowBreak = back.allowPlace = false;
            h.assertTrue(path.verify(out).complete && returning.verify(back).complete, "Referenz-Langweg nicht begehbar");
            var check = new RouteVerification(path, out, returning, back);
            long start = System.nanoTime(), worst = 0; int calls = 0;
            while (check.status() == RouteVerification.Status.RUNNING && calls < 1000) {
                long before = System.nanoTime(); check.step(before + 1_000_000L);
                worst = Math.max(worst, System.nanoTime() - before); calls++;
            }
            long elapsed = System.nanoTime() - start;
            h.assertTrue(check.status() == RouteVerification.Status.COMPLETE && check.steps() == 1024,
                    "Langprüfung nicht vollständig: " + check.reason());
            h.assertTrue(check.returnDamage() == 0, "Flacher Weg erzeugt Fallschaden");
            if (run >= 0) { total[run] = elapsed; peak[run] = worst; slices = calls; }
        }
        Arrays.sort(total); Arrays.sort(peak);
        com.mojang.logging.LogUtils.getLogger().info(String.format(Locale.ROOT,
                "[Nav-Final] moves=1024 totalMedian=%.4fms slicePeakMedian=%.4fms sliceWorst=%.4fms lastSlices=%d complete=true",
                total[5] / 1e6, peak[5] / 1e6, peak[10] / 1e6, slices));
        h.succeed();
    }
}
