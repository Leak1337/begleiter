package dev.begleiter.nav;

import dev.begleiter.CompanionEntity;
import dev.begleiter.CompanionMod;
import net.fabricmc.fabric.api.gametest.v1.GameTest;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.world.level.block.Blocks;

/** Bewegungsmessungen von Anfang bis Ende mit dem echten Navigator und seinem Serverbudget. */
public class NavMotionTests {
    private static final String BENCH = "begleiter-gametest:bench";

    private static CompanionEntity arena(GameTestHelper h) {
        for (int x = 0; x < 48; x++) for (int z = 0; z < 12; z++) h.setBlock(x, 0, z, Blocks.STONE);
        var c = h.spawn(CompanionMod.COMPANION, new BlockPos(2, 1, 3));
        c.stay(); c.getInventory().clearContent(); c.nav.trace.enable();
        return c;
    }

    private static void drive(GameTestHelper h, CompanionEntity c, BlockPos target, String name) {
        var goal = new Goal.Block(h.absolutePos(target));
        h.succeedWhen(() -> {
            var result = c.nav.goTo(goal);
            h.assertTrue(result != Navigator.Result.UNREACHABLE, "Navigator gab auf: " + c.nav.lastEvent);
            h.assertTrue(result == Navigator.Result.ARRIVED, "Noch unterwegs: " + c.nav.debugText());
            h.assertTrue(c.nav.trace.samples().size() > 5, "Tick-Trace fehlt");
            com.mojang.logging.LogUtils.getLogger().info("[Nav-Motion] {} {}", name, c.nav.trace.metrics());
        });
    }

    @GameTest(maxTicks = 400, structure = BENCH)
    public void geradeausMitEchtemNavigator(GameTestHelper h) { drive(h, arena(h), new BlockPos(42, 1, 3), "flat"); }

    @GameTest(maxTicks = 400, structure = BENCH)
    public void schraegerOffenerWeg(GameTestHelper h) { drive(h, arena(h), new BlockPos(42, 1, 9), "diagonal"); }

    @GameTest(maxTicks = 500, structure = BENCH)
    public void sKurveMitEchtemNavigator(GameTestHelper h) {
        var c = arena(h);
        for (int x : new int[]{12, 24, 36}) for (int z = 0; z < 12; z++)
            if (x == 24 ? z > 3 : z < 8) for (int y = 1; y <= 4; y++) h.setBlock(x, y, z, Blocks.BARRIER);
        drive(h, c, new BlockPos(43, 1, 3), "s-curve");
    }

    @GameTest(maxTicks = 400, structure = BENCH)
    public void zielwechselWaerendLaufen(GameTestHelper h) {
        var c = arena(h);
        h.succeedWhen(() -> {
            var target = c.tickCount < 35 ? new BlockPos(42, 1, 3) : new BlockPos(40, 1, 9);
            var result = c.nav.goToMoving(new Goal.Block(h.absolutePos(target)), "owner");
            h.assertTrue(c.tickCount >= 35 && result == Navigator.Result.ARRIVED, "Zielwechsel noch unterwegs");
            com.mojang.logging.LogUtils.getLogger().info("[Nav-Motion] retarget {}", c.nav.trace.metrics());
        });
    }

    @GameTest(maxTicks = 400, structure = BENCH)
    public void zweiAnschluesseErhaltenDenExecutor(GameTestHelper h) {
        var c = arena(h);
        Navigator nav = new Navigator(c, (ctx, start, goal) -> {
            ctx.allowBreak = ctx.allowPlace = ctx.allowParkour = false;
            var search = new PathSearch(ctx, start, goal); search.maxNodes = 12; return search;
        });
        nav.trace.enable();
        var goal = new Goal.Block(h.absolutePos(new BlockPos(43, 1, 3)));
        PathExecutor[] first = {null};
        h.succeedWhen(() -> {
            var result = nav.goTo(goal);
            if (first[0] == null) first[0] = nav.executor();
            if (nav.executor() != null) h.assertTrue(nav.executor() == first[0], "Abschnittswechsel verwirft Executor");
            h.assertTrue(result == Navigator.Result.ARRIVED, "Anschluesse noch unterwegs: " + nav.debugText());
            String attached = dev.begleiter.CompanionTexts.tr("nav.event.continuation_attached").getString();
            long seams = nav.trace.samples().stream().filter(s -> s.event().equals(attached)).count();
            h.assertTrue(seams >= 2, "Zwei vorzeitige Anschluesse fehlen: " + seams);
            h.assertTrue(nav.trace.metrics().pauseTicks() == 0, "Unbeabsichtigte Pause an Abschnittsgrenze");
        });
    }

    @GameTest(maxTicks = 400, structure = BENCH)
    public void startetTeilwegVorSuchende(GameTestHelper h) {
        var c = arena(h);
        long[] clock = {0};
        Navigator nav = new Navigator(c, (ctx, start, goal) -> {
            ctx.allowBreak = ctx.allowPlace = ctx.allowParkour = false;
            return new PathSearch(ctx, start, goal, PathSearch.WEIGHT, () -> clock[0] += 40_000, true);
        });
        nav.trace.enable();
        var goal = new Goal.Block(h.absolutePos(new BlockPos(43, 1, 3)));
        boolean[] early = {false};
        h.succeedWhen(() -> {
            var result = nav.goTo(goal);
            if (nav.searching() && nav.executor() != null) early[0] = true;
            h.assertTrue(result == Navigator.Result.ARRIVED, "Frueher Teilweg noch unterwegs");
            h.assertTrue(early[0], "Stand bis zum Ende der gesamten Suche still");
        });
    }

    @GameTest(structure = BENCH)
    public void glattungSchneidetKeineEckeUeberAbgrund(GameTestHelper h) {
        var c = arena(h);
        var from = c.position();
        var to = from.add(2, 0, 2);
        NavContext ctx = new NavContext(c);
        h.assertTrue(WalkCorridor.clear(c, ctx, from, to), "Freie Diagonale abgelehnt");
        h.setBlock(3, 0, 4, Blocks.AIR);
        h.assertTrue(!WalkCorridor.clear(c, ctx, from, to), "Mob-Fussabdruck schneidet ueber fehlenden Boden");
        h.succeed();
    }

    @GameTest(structure = BENCH)
    public void glattungPrueftKoerperTuerLavaUndNeueWand(GameTestHelper h) {
        var c = arena(h);
        NavContext ctx = new NavContext(c);
        var from = c.position(); var to = from.add(2.5, 0, 0);
        h.assertTrue(WalkCorridor.clear(c, ctx, from, to), "Freier Gang abgelehnt");
        h.setBlock(4, 2, 3, Blocks.STONE);
        h.assertTrue(!WalkCorridor.clear(c, ctx, from, to), "Kopfkollision uebersehen");
        h.setBlock(4, 2, 3, Blocks.AIR); h.setBlock(4, 1, 3, Blocks.OAK_DOOR);
        h.assertTrue(!WalkCorridor.clear(c, ctx, from, to), "Tuerschritt uebersprungen");
        h.setBlock(4, 1, 3, Blocks.LAVA);
        h.assertTrue(!WalkCorridor.clear(c, ctx, from, to), "Neue Lava uebersehen");
        h.succeed();
    }

    @GameTest(structure = BENCH)
    public void zielAktualisierungErhaeltLaufendenWeg(GameTestHelper h) {
        var c = arena(h); c.setOnGround(true);
        var first = new Goal.Block(h.absolutePos(new BlockPos(35, 1, 3)));
        h.succeedWhen(() -> {
            c.nav.goToMoving(first, "owner");
            var executor = c.nav.executor();
            h.assertTrue(executor != null, "Warte auf Vergabe des gemeinsamen Suchbudgets");
            long generation = c.nav.generation();
            c.nav.goToMoving(new Goal.Block(h.absolutePos(new BlockPos(40, 1, 8))), "owner");
            h.assertTrue(c.nav.executor() == executor, "Laufender Weg wurde durch Zielupdate verworfen");
            h.assertTrue(c.nav.generation() > generation, "Alte Suchgeneration nicht ungueltig");
            c.nav.stop();
            h.assertTrue(!c.nav.searching() && c.nav.executor() == null, "Stop laesst Ersatzsuche aktiv");
        });
    }

    @GameTest(structure = BENCH)
    public void bewegungsauftragHatEineZustaendigkeit(GameTestHelper h) {
        var c = arena(h);
        c.motion.move(MotionController.Owner.COMBAT, 12, 1, 4, 1, false);
        h.assertTrue(!c.motion.move(MotionController.Owner.NAVIGATION, 8, 1, 9, 1, true), "Navigation ueberschreibt Kampf");
        h.assertTrue(c.motion.commands() == 1 && c.getMoveControl().getWantedX() == 12, "Mehrere wirksame Auftraege");
        h.assertTrue(PathExecutor.aligned(new BlockPos(0, 1, 0), new BlockPos(3, 1, 0), new BlockPos(6, 1, 0)), "Gerade Kette blockiert");
        h.assertTrue(!PathExecutor.aligned(new BlockPos(0, 1, 0), new BlockPos(3, 1, 0), new BlockPos(3, 1, 3)), "90-Grad-Sprung nimmt Schwung blind mit");
        h.succeed();
    }

    @GameTest(maxTicks = 500, structure = BENCH)
    public void zweiBegleiterWeichenAufBreitemBodenAus(GameTestHelper h) {
        var a = arena(h);
        a.snapTo(h.absoluteVec(new net.minecraft.world.phys.Vec3(4.5, 1, 6.5)));
        var b = h.spawn(CompanionMod.COMPANION, new BlockPos(36, 1, 6)); b.stay(); b.getInventory().clearContent();
        var goalA = new Goal.Block(h.absolutePos(new BlockPos(36, 1, 6)));
        var goalB = new Goal.Block(h.absolutePos(new BlockPos(4, 1, 6)));
        boolean[] arrived = {false, false};
        h.succeedWhen(() -> {
            if (!arrived[0]) arrived[0] = a.nav.goTo(goalA) == Navigator.Result.ARRIVED;
            if (!arrived[1]) arrived[1] = b.nav.goTo(goalB) == Navigator.Result.ARRIVED;
            h.assertTrue(a.getHealth() == a.getMaxHealth() && b.getHealth() == b.getMaxHealth(), "Ausweichen verursacht Schaden");
            h.assertTrue(arrived[0] && arrived[1], "Begleiter blockieren sich");
        });
    }

    private static void load(GameTestHelper h, int count) {
        var first = arena(h);
        var bots = new CompanionEntity[count];
        var arrived = new boolean[count];
        for (int i = 0; i < count; i++) {
            bots[i] = i == 0 ? first : h.spawn(CompanionMod.COMPANION, new BlockPos(2, 1, i + 1));
            bots[i].snapTo(h.absoluteVec(new net.minecraft.world.phys.Vec3(2.5, 1, i + 1.5)));
            bots[i].stay(); bots[i].getInventory().clearContent(); bots[i].nav.trace.enable();
        }
        h.succeedWhen(() -> {
            boolean all = true;
            for (int i = 0; i < count; i++) {
                if (!arrived[i]) arrived[i] = bots[i].nav.goTo(new Goal.Block(h.absolutePos(new BlockPos(42, 1, i + 1)))) == Navigator.Result.ARRIVED;
                all &= arrived[i];
            }
            h.assertTrue(all, "Warte auf " + count + " laufende Begleiter");
            double max = 0, p95 = 0;
            for (var bot : bots) { max = Math.max(max, bot.nav.trace.metrics().totalMaxMs()); p95 += bot.nav.trace.metrics().steeringP95Ms(); }
            com.mojang.logging.LogUtils.getLogger().info("[Nav-Motion-Load] bots={} steeringSumP95Ms={} individualTotalMaxMs={}", count, p95, max);
        });
    }

    @GameTest(maxTicks = 400, structure = BENCH)
    public void lastEinBegleiter(GameTestHelper h) { load(h, 1); }
    @GameTest(maxTicks = 400, structure = BENCH)
    public void lastFuenfBegleiter(GameTestHelper h) { load(h, 5); }
    @GameTest(maxTicks = 400, structure = BENCH)
    public void lastZehnBegleiter(GameTestHelper h) { load(h, 10); }
}
