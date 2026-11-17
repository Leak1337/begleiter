package dev.begleiter.nav;

import java.util.Arrays;
import java.util.Locale;
import java.util.Random;
import dev.begleiter.CompanionEntity;
import dev.begleiter.CompanionMemory;
import dev.begleiter.CompanionMod;
import net.fabricmc.fabric.api.gametest.v1.GameTest;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Blocks;

public class NavDangerIndexTests {
    private static final String BENCH = "begleiter-gametest:bench";
    private record Segment(BlockPos from, BlockPos to) {}

    private static CompanionEntity companion(GameTestHelper h) {
        var c = h.spawn(CompanionMod.COMPANION, new BlockPos(4, 1, 6));
        c.stay(); c.setNoAi(true); c.setOnGround(true); c.getInventory().clearContent();
        return c;
    }

    private static NavContext context(CompanionEntity c, boolean indexed) {
        return new NavContext(c, new BlockCache((net.minecraft.server.level.ServerLevel) c.level()),
                c.getHealth(), c.scaffoldCount(), true, indexed);
    }

    private static boolean safe(NavContext ctx, BlockPos p, BlockPos q) {
        return ctx.safeSegment(p.getX(), p.getY(), p.getZ(), q.getX(), q.getY(), q.getZ());
    }

    private static void farHazards(GameTestHelper h, CompanionEntity c, int count) {
        for (int i = 0; i < count; i++) c.memory.remember(CompanionMemory.Kind.DANGER, "far-" + i, h.getLevel(),
                new BlockPos(1000 + i * 16, 64, 1000));
    }

    @GameTest public void indexUndReferenzStimmenAnZellgrenzenUeberein(GameTestHelper h) {
        var c = companion(h);
        for (int i = 0; i < 96; i++) c.memory.remember(CompanionMemory.Kind.DANGER, "edge-" + i, h.getLevel(),
                new BlockPos(-25 + i % 8 * 8, 6 + i % 3 * 3, -25 + i / 8 * 8));
        var indexed = context(c, true); var reference = context(c, false);
        var random = new Random(0xDA63);
        for (int i = 0; i < 4000; i++) {
            var p = new BlockPos(random.nextInt(81) - 40, random.nextInt(24) - 4, random.nextInt(81) - 40);
            var q = p.offset(random.nextInt(17) - 8, random.nextInt(17) - 8, random.nextInt(17) - 8);
            h.assertTrue(safe(indexed, p, q) == safe(reference, p, q), "Zellgrenze verändert Korridor: " + p + "/" + q);
        }
        for (var place : c.memory.all()) for (int dx = -3; dx <= 3; dx++) for (int dy = -3; dy <= 3; dy++) {
            BlockPos p = place.pos.offset(dx, dy, 2), q = p.offset(1, -1, -2);
            h.assertTrue(safe(indexed, p, q) == safe(reference, p, q), "Gefahrenrand verändert Ausstieg");
        }
        h.succeed();
    }

    @GameTest public void ausstiegBleibtMonotonUndEintrittGesperrt(GameTestHelper h) {
        var c = companion(h); farHazards(h, c, 80); BlockPos p = new BlockPos(-8, 64, -8);
        c.memory.remember(CompanionMemory.Kind.DANGER, "near", h.getLevel(), p);
        var ctx = context(c, true);
        h.assertTrue(safe(ctx, p, p.east()) && safe(ctx, p.east(2), p.east(3)), "Flucht aus Gefahr blockiert");
        h.assertTrue(!safe(ctx, p.east(), p) && !safe(ctx, p.east(3), p.east(2)), "Eintritt in Gefahr erlaubt");
        h.assertTrue(!safe(ctx, p.west(4), p.east(4)), "Langer Sprung über Gefahr erlaubt");
        h.assertTrue(!safe(ctx, p.above(4), p.below(4)), "Langer Fall durch Gefahr erlaubt");
        h.succeed();
    }

    @GameTest public void auffrischenErsetztIndexUndEntfernungsCache(GameTestHelper h) {
        var c = companion(h); farHazards(h, c, 80); BlockPos p = new BlockPos(7, 64, 7);
        var ctx = context(c, true);
        h.assertTrue(safe(ctx, p, p.east()), "Unbeteiligte Gefahr sperrt");
        var danger = c.memory.remember(CompanionMemory.Kind.DEATH, "new", h.getLevel(), p.east());
        ctx.refreshDangers();
        h.assertTrue(!safe(ctx, p, p.east()), "Neue Gefahr bleibt hinter altem Index verborgen");
        c.memory.forget(danger); ctx.refreshDangers();
        h.assertTrue(safe(ctx, p, p.east()), "Vergessene Gefahr bleibt aktiv");
        c.memory.clear(); ctx.refreshDangers();
        h.assertTrue(safe(ctx, p, p.east()), "Leeres Gedächtnis hält Index aktiv"); h.succeed();
    }

    @GameTest public void dimensionenUndModiBleibenGetrennt(GameTestHelper h) {
        var c = companion(h); farHazards(h, c, 80); BlockPos p = new BlockPos(8, 64, 8);
        var nether = h.getLevel().getServer().getLevel(Level.NETHER); h.assertTrue(nether != null, "Nether fehlt");
        c.memory.remember(CompanionMemory.Kind.DANGER, "other", nether, p);
        c.memory.remember(CompanionMemory.Kind.LAVA, "info", h.getLevel(), p);
        h.assertTrue(safe(context(c, true), p.west(3), p), "Andere Dimension oder reine Beobachtung sperrt");
        c.memory.remember(CompanionMemory.Kind.DEATH, "local", h.getLevel(), p);
        h.assertTrue(!safe(context(c, true), p.west(3), p), "Lokaler Todesort ignoriert");
        h.assertTrue(safe(new NavContext(c, false), p.west(3), p), "Ausdrücklicher Spielermodus verändert"); h.succeed();
    }

    @GameTest public void extremeEntfernungenLaufenNichtUeber(GameTestHelper h) {
        var c = companion(h); farHazards(h, c, 80);
        c.memory.remember(CompanionMemory.Kind.DANGER, "extreme", h.getLevel(), new BlockPos(Integer.MIN_VALUE, 64, Integer.MIN_VALUE));
        BlockPos p = new BlockPos(Integer.MAX_VALUE, 64, Integer.MAX_VALUE);
        for (boolean indexed : new boolean[]{false, true}) {
            var ctx = context(c, indexed);
            h.assertTrue(safe(ctx, p, p) && safe(ctx, p, p.west()), "Entfernte Gefahr wird durch Überlauf nah");
        }
        h.succeed();
    }

    @GameTest public void gefahrenAbfrageLaedtKeineChunks(GameTestHelper h) {
        var c = companion(h); farHazards(h, c, 80); BlockPos p = new BlockPos(29_000_000, 64, 29_000_000);
        h.assertTrue(h.getLevel().getChunkSource().getChunkNow(p.getX() >> 4, p.getZ() >> 4) == null, "Ziel schon geladen");
        c.memory.remember(CompanionMemory.Kind.DANGER, "unloaded", h.getLevel(), p);
        h.assertTrue(!safe(context(c, true), p.west(3), p.west(2)), "Ungeladene Gefahr übergangen");
        h.assertTrue(h.getLevel().getChunkSource().getChunkNow(p.getX() >> 4, p.getZ() >> 4) == null, "Gefahrenindex lädt Chunk"); h.succeed();
    }

    @GameTest(structure = BENCH)
    public void vollstaendigeSucheHatIdentischenUmweg(GameTestHelper h) {
        for (int x = 0; x < 48; x++) for (int z = 0; z < 12; z++) {
            h.setBlock(x, 0, z, Blocks.STONE);
            if (x == 0 || x == 47 || z == 0 || z == 11) for (int y = 1; y < 5; y++) h.setBlock(x, y, z, Blocks.BARRIER);
        }
        var c = companion(h); farHazards(h, c, 80);
        c.memory.remember(CompanionMemory.Kind.DANGER, "path", h.getLevel(), h.absolutePos(new BlockPos(14, 1, 6)));
        var a = context(c, false); var b = context(c, true);
        a.allowBreak = a.allowPlace = a.allowParkour = b.allowBreak = b.allowPlace = b.allowParkour = false;
        var goal = new Goal.Block(h.absolutePos(new BlockPos(40, 1, 6)));
        var old = new PathSearch(a, c.blockPosition(), goal); var fast = new PathSearch(b, c.blockPosition(), goal);
        old.step(2_000_000_000L); fast.step(2_000_000_000L);
        h.assertTrue(old.status() == PathSearch.Status.FOUND && fast.status() == PathSearch.Status.FOUND, "Umweg fehlt");
        h.assertTrue(old.path().positions.equals(fast.path().positions) && old.path().moves.equals(fast.path().moves)
                && old.path().totalCost() == fast.path().totalCost() && old.nodes() == fast.nodes(), "Index verändert Suche");
        h.succeed();
    }

    private record Sample(boolean[] answers, long total, long query) {}

    private static Sample sample(CompanionEntity c, Segment[] segments, boolean indexed) {
        boolean[] answers = new boolean[segments.length]; long start = System.nanoTime();
        var ctx = context(c, indexed); long beforeQueries = System.nanoTime();
        for (int i = 0; i < segments.length; i++) answers[i] = safe(ctx, segments[i].from, segments[i].to);
        long end = System.nanoTime(); return new Sample(answers, end - start, end - beforeQueries);
    }

    private static void measure(GameTestHelper h, CompanionEntity c, int count, boolean dense) {
        c.memory.clear(); BlockPos anchor = c.blockPosition();
        for (int i = 0; i < count; i++) c.memory.remember(i < 160 ? CompanionMemory.Kind.DANGER : CompanionMemory.Kind.DEATH,
                "bench-" + i, h.getLevel(), anchor.offset(dense ? i % 8 : i % 20 * 12, dense ? i / 64 : 0, dense ? i / 8 % 8 : i / 20 * 12));
        var segments = new Segment[2048]; var random = new Random(0xDA63);
        for (int i = 0; i < segments.length; i++) {
            BlockPos p = anchor.offset(random.nextInt(dense ? 24 : 256) - 4, random.nextInt(dense ? 16 : 6) - 2,
                    random.nextInt(dense ? 24 : 208) - 4);
            segments[i] = new Segment(p, p.offset(random.nextInt(9) - 4, random.nextInt(5) - 2, random.nextInt(9) - 4));
        }
        long[] linear = new long[11], indexed = new long[11], queryA = new long[11], queryB = new long[11];
        int allowed = 0;
        for (int run = -8; run < linear.length; run++) {
            Sample a, b;
            if ((run & 1) == 0) { a = sample(c, segments, false); b = sample(c, segments, true); }
            else { b = sample(c, segments, true); a = sample(c, segments, false); }
            h.assertTrue(Arrays.equals(a.answers, b.answers), "Index verändert Lastentscheidungen");
            if (run >= 0) { linear[run] = a.total; indexed[run] = b.total; queryA[run] = a.query; queryB[run] = b.query; }
            if (run == 0) for (boolean answer : a.answers) if (answer) allowed++;
        }
        Arrays.sort(linear); Arrays.sort(indexed); Arrays.sort(queryA); Arrays.sort(queryB);
        com.mojang.logging.LogUtils.getLogger().info(String.format(Locale.ROOT,
                "[Nav-Danger] hazards=%d dense=%s segments=%d totalMedian=%.4f->%.4fms queryMedian=%.4f->%.4fms allowed=%d identical=true",
                count, dense, segments.length, linear[5] / 1e6, indexed[5] / 1e6, queryA[5] / 1e6, queryB[5] / 1e6, allowed));
    }

    @GameTest(structure = BENCH)
    public void gepaarteMessungKleinerUndGrosserGefahrenlisten(GameTestHelper h) {
        var c = companion(h);
        for (int count : new int[]{0, 1, 16, 32, 64, 160, 320}) measure(h, c, count, false);
        measure(h, c, 320, true); h.succeed();
    }
}
