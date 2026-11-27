package dev.begleiter.nav;

import dev.begleiter.CompanionEntity;
import dev.begleiter.CompanionMod;
import net.fabricmc.fabric.api.gametest.v1.GameTest;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Blocks;

public class NavSchedulerTests {
    private static final String BENCH = "begleiter-gametest:bench";

    private static void arena(GameTestHelper h) {
        for (int x = 0; x < 48; x++) for (int z = 0; z < 12; z++) {
            h.setBlock(x, 0, z, Blocks.STONE);
            if (x == 0 || x == 47 || z == 0 || z == 11)
                for (int y = 1; y <= 5; y++) h.setBlock(x, y, z, Blocks.BARRIER);
        }
    }

    private static CompanionEntity companion(GameTestHelper h) {
        var c = h.spawn(CompanionMod.COMPANION, new BlockPos(2, 1, 6));
        c.stay();
        c.setNoAi(true);
        c.setOnGround(true);
        c.getInventory().clearContent();
        return c;
    }

    private static PathSearch search(CompanionEntity c, BlockPos target) {
        var context = new NavContext(c);
        context.allowBreak = context.allowPlace = context.allowParkour = false;
        return new PathSearch(context, c.blockPosition(), new Goal.Block(target));
    }

    @GameTest(maxTicks = 160, structure = BENCH)
    public void fuenfBegleiterSuchenFairDieselbenWege(GameTestHelper h) {
        arena(h);
        for (int wall : new int[]{12, 24, 36})
            for (int z = wall == 24 ? 3 : 1; z <= (wall == 24 ? 10 : 8); z++)
                for (int y = 1; y <= 5; y++) h.setBlock(wall, y, z, Blocks.BARRIER);
        var searches = new PathSearch[5];
        var references = new PathSearch[5];
        for (int i = 0; i < searches.length; i++) {
            var c = companion(h);
            BlockPos target = h.absolutePos(new BlockPos(44, 1, 4 + i));
            references[i] = search(c, target);
            references[i].step(2_000_000_000L);
            h.assertTrue(references[i].status() == PathSearch.Status.FOUND, "Referenzweg fehlt");
            searches[i] = search(c, target);
        }
        var scheduler = NavScheduler.forLevel(h.getLevel());
        long started = scheduler.snapshot().tick();
        long[] measurements = {started, 0, 0, 0}; // letzter Tick, Summe, Maximum, Überziehung
        h.succeedWhen(() -> {
            var previous = scheduler.lastTick();
            if (previous.tick() >= measurements[0]) {
                measurements[0] = previous.tick() + 1;
                measurements[1] += previous.usedNanos();
                measurements[2] = Math.max(measurements[2], previous.usedNanos());
                measurements[3] = Math.max(measurements[3], previous.overshootNanos());
            }
            for (PathSearch s : searches) {
                if (s.done()) continue;
                NavScheduler.run(h.getLevel(), s, NavScheduler.Priority.ACTIVE, 4_000_000L, deadline -> {
                    long left = NavScheduler.remaining(deadline);
                    if (left > 0) s.step(left);
                    return s.done();
                });
            }
            for (int i = 0; i < searches.length; i++) {
                var s = searches[i];
                h.assertTrue(s.status() == PathSearch.Status.FOUND, "Begleiter " + i + " wartet noch: " + s.nodes());
                h.assertTrue(s.path().positions.equals(references[i].path().positions)
                        && s.path().moves.equals(references[i].path().moves), "Budget verändert Weg " + i);
                h.assertTrue(s.path().totalCost() == references[i].path().totalCost(), "Budget verändert Kosten");
                h.assertValueEqual(s.nodes(), references[i].nodes(), "Budget verändert Knotenzahl");
            }
            var current = scheduler.snapshot();
            com.mojang.logging.LogUtils.getLogger().info(
                    "[Nav-Budget] 5/5 Wege identisch; Ticks={} Suchzeit={}ms Maximum={}ms Überziehung={}ms",
                    current.tick() - started + 1, (measurements[1] + current.usedNanos()) / 1e6,
                    Math.max(measurements[2], current.usedNanos()) / 1e6,
                    Math.max(measurements[3], current.overshootNanos()) / 1e6);
        });
    }

    @GameTest(structure = BENCH)
    public void dimensionenTeilenDasselbeServerbudget(GameTestHelper h) {
        var nether = h.getLevel().getServer().getLevel(Level.NETHER);
        h.assertTrue(nether != null, "Nether fehlt");
        h.assertTrue(NavScheduler.forLevel(h.getLevel()) == NavScheduler.forLevel(nether), "Budget pro Dimension vervielfacht");
        h.succeed();
    }

    @GameTest(maxTicks = 80, structure = BENCH)
    public void navigatorStopGibtWartendeSucheSofortFrei(GameTestHelper h) {
        arena(h);
        var c = companion(h);
        var scheduler = NavScheduler.forLevel(h.getLevel());
        var goal = new Goal.NearXZ(29_000_000, 29_000_000, 1);
        h.succeedWhen(() -> {
            // Alle Tests eines Laufs teilen sich das Budget der Welt. Unter Last wartet die neue Anfrage hinter älteren
            // in der Warteschlange, bevor überhaupt eine Suche entsteht – auch das ist eine wartende Suche.
            c.nav.stop();
            int idle = scheduler.snapshot().tracked();
            // Ohne KI hält niemand den Bodenkontakt aktuell; in der Luft startet der Navigator absichtlich keine Suche.
            c.setOnGround(true);
            c.nav.lastNodes = -1;
            c.nav.goTo(goal);
            // Auf einem leeren Server kann die kleine Arena schon im ersten Takt fertig durchsucht sein: auch dann gab es
            // eine Suche, sie ist nur nicht mehr offen.
            h.assertTrue(c.nav.searching() || scheduler.snapshot().tracked() == idle + 1 || c.nav.lastNodes >= 0,
                    "Noch keine wartende Suche");
            c.nav.stop();
            h.assertTrue(!c.nav.searching(), "Navigator sucht nach Stopp weiter");
            h.assertValueEqual(scheduler.snapshot().tracked(), idle, "Stopp hält Budgetreservierung fest");
        });
    }

    @GameTest(maxTicks = 120, structure = BENCH)
    public void oeffentlicheRundenpruefungKannBudgetNichtDurchMehrfachaufrufUmgehen(GameTestHelper h) {
        arena(h);
        var c = companion(h);
        var check = new RoundTripCheck(c, h.absolutePos(new BlockPos(18, 1, 6)), 1);
        var scheduler = NavScheduler.forLevel(h.getLevel());
        int grants = scheduler.snapshot().grants();
        for (int i = 0; i < 100; i++) check.step();
        h.assertTrue(scheduler.snapshot().grants() - grants <= 1, "Rundenprüfung multipliziert Tickbudget");
        h.assertTrue(check.status() == RoundTripCheck.Status.CHECKING, "Hin- und Rückweg im selben Budget mehrfach gerechnet");
        h.succeedWhen(() -> {
            check.step();
            h.assertTrue(check.status() == RoundTripCheck.Status.SAFE, "Rundenprüfung endet nicht sicher: " + check.reason());
            h.assertValueEqual(c.blockPosition(), h.absolutePos(new BlockPos(2, 1, 6)), "Prüfung bewegt Begleiter");
        });
    }

    @GameTest(maxTicks = 80, structure = BENCH)
    public void neueSucheLaedtEntferntesZielNicht(GameTestHelper h) {
        arena(h);
        var c = companion(h);
        var target = new BlockPos(29_000_000, c.getBlockY(), 29_000_000);
        h.assertTrue(h.getLevel().getChunkSource().getChunkNow(target.getX() >> 4, target.getZ() >> 4) == null,
                "Testziel bereits geladen");
        h.succeedWhen(() -> {
            c.nav.goTo(new Goal.Block(target));
            h.assertTrue(c.nav.searching() || c.nav.executor() != null, "Suche wartet noch auf ihr Budget");
            h.assertTrue(h.getLevel().getChunkSource().getChunkNow(target.getX() >> 4, target.getZ() >> 4) == null,
                    "Portalprüfung lädt entferntes Ziel nach");
            c.nav.stop();
        });
    }
}
