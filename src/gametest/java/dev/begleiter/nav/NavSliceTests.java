package dev.begleiter.nav;

import java.util.concurrent.atomic.AtomicLong;
import dev.begleiter.CompanionEntity;
import dev.begleiter.CompanionMod;
import net.fabricmc.fabric.api.gametest.v1.GameTest;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.world.level.block.Blocks;

/** Deterministische Zeitgrenzen: keine Sleeps und keine zeitabhängigen Erfolgsbedingungen. */
public class NavSliceTests {
    private static CompanionEntity companion(GameTestHelper h, BlockPos position) {
        var c = h.spawn(CompanionMod.COMPANION, position);
        c.stay();
        c.setNoAi(true);
        c.setOnGround(true);
        c.getInventory().clearContent();
        return c;
    }

    private static CompanionEntity ladder(GameTestHelper h) {
        for (int x = 1; x <= 3; x++) for (int z = 1; z <= 3; z++) {
            h.setBlock(x, 0, z, Blocks.STONE);
            if (x != 2 || z != 2) for (int y = 1; y <= 4; y++) h.setBlock(x, y, z, Blocks.BARRIER);
        }
        h.setBlock(2, 1, 2, Blocks.LADDER);
        h.setBlock(2, 2, 2, Blocks.LADDER);
        return companion(h, new BlockPos(2, 1, 2));
    }

    private static NavContext context(CompanionEntity c) {
        var context = new NavContext(c);
        context.allowBreak = context.allowPlace = false;
        return context;
    }

    private static Goal goal(GameTestHelper h) { return new Goal.Block(h.absolutePos(new BlockPos(2, 2, 2))); }

    private static void complete(GameTestHelper h, PathSearch search) {
        for (int i = 0; i < 10_000 && !search.done(); i++) search.step(3);
        h.assertTrue(search.status() == PathSearch.Status.FOUND, "Fortgesetzte Suche: " + search.status());
    }

    private static void same(GameTestHelper h, PathSearch a, PathSearch b) {
        h.assertTrue(a.status() == PathSearch.Status.FOUND && b.status() == PathSearch.Status.FOUND, "Vollständiger Weg fehlt");
        h.assertTrue(a.path().positions.equals(b.path().positions) && a.path().moves.equals(b.path().moves), "Zeitscheiben verändern Weg");
        h.assertTrue(a.path().totalCost() == b.path().totalCost(), "Zeitscheiben verändern Kosten");
        h.assertValueEqual(a.nodes(), b.nodes(), "Knoten werden nach Pause doppelt gezählt");
    }

    @GameTest public void offenerKnotenBleibtTrotzLeererWarteschlangeErhalten(GameTestHelper h) {
        var c = ladder(h);
        var time = new AtomicLong();
        var ctx = context(c);
        var sliced = new PathSearch(ctx, c.blockPosition(), goal(h), PathSearch.WEIGHT, time::getAndIncrement, true);
        sliced.step(3);
        h.assertValueEqual(sliced.nodes(), 1, "Erster Knoten");
        h.assertTrue(sliced.status() == PathSearch.Status.RUNNING, "Leere Warteschlange beendet unvollständig geprüften Knoten");
        h.assertTrue(!ctx.blocks.searching(), "Suchcache bleibt nach Pause aktiv");
        complete(h, sliced);
        h.assertTrue(sliced.path().moves.contains(Move.CLIMB_UP), "Späte Leiterbewegung wurde beim Fortsetzen ausgelassen");
        var reference = new PathSearch(context(c), c.blockPosition(), goal(h), PathSearch.WEIGHT, () -> 0L, false);
        reference.step(Long.MAX_VALUE);
        same(h, reference, sliced);
        h.succeed();
    }

    @GameTest(structure = "begleiter-gametest:bench")
    public void parkourBleibtBeiVielenPausenIdentisch(GameTestHelper h) {
        for (int x = 0; x <= 12; x++) for (int z = 2; z <= 8; z++)
            if (x <= 2 || x >= 5) for (int y = 0; y <= 3; y++) h.setBlock(x, y, z, Blocks.STONE);
        var c = companion(h, new BlockPos(2, 4, 5));
        var target = new Goal.Block(h.absolutePos(new BlockPos(8, 4, 5)));
        var reference = new PathSearch(context(c), c.blockPosition(), target, PathSearch.WEIGHT, () -> 0L, false);
        reference.step(Long.MAX_VALUE);
        var time = new AtomicLong();
        var sliced = new PathSearch(context(c), c.blockPosition(), target, PathSearch.WEIGHT, time::getAndIncrement, true);
        complete(h, sliced);
        same(h, reference, sliced);
        h.assertTrue(sliced.path().count(Move.Kind.PARKOUR) > 0, "Fixture prüft keinen Sprung");
        h.succeed();
    }

    @GameTest public void knotenlimitZaehltFortsetzungNichtAlsNeuenKnoten(GameTestHelper h) {
        var c = ladder(h);
        var time = new AtomicLong();
        var sliced = new PathSearch(context(c), c.blockPosition(), goal(h), PathSearch.WEIGHT, time::getAndIncrement, true);
        sliced.maxNodes = 2;
        complete(h, sliced);
        h.assertValueEqual(sliced.nodes(), 2, "Knotenlimit bei Fortsetzung");
        h.succeed();
    }

    @GameTest public void nullUndNegativesBudgetBeginnenKeineArbeit(GameTestHelper h) {
        var c = ladder(h);
        var ctx = context(c);
        var search = new PathSearch(ctx, c.blockPosition(), goal(h), PathSearch.WEIGHT,
                () -> { throw new AssertionError("Uhr trotz fehlendem Budget gelesen"); }, true);
        search.step(0);
        search.step(-1);
        h.assertValueEqual(search.nodes(), 0, "Suche ohne Zeitbudget");
        h.assertTrue(search.status() == PathSearch.Status.RUNNING && search.nanos() == 0 && !ctx.blocks.searching(),
                "Budgetloser Aufruf verändert Suchzustand");
        h.succeed();
    }

    @GameTest public void sehrGrossesBudgetLaeuftNichtUeber(GameTestHelper h) {
        var c = ladder(h);
        var search = new PathSearch(context(c), c.blockPosition(), goal(h));
        search.step(Long.MAX_VALUE);
        h.assertTrue(search.status() == PathSearch.Status.FOUND, "Übergelaufene Deadline unterbricht großes Budget");
        h.succeed();
    }

    @GameTest public void gesamtzeitlimitGiltAuchFuerPausiertenKnoten(GameTestHelper h) {
        var c = ladder(h);
        var time = new AtomicLong();
        var ctx = context(c);
        var search = new PathSearch(ctx, c.blockPosition(), goal(h), PathSearch.WEIGHT,
                () -> time.getAndAdd(1_000_000_000L), true);
        for (int i = 0; i < 4 && !search.done(); i++) search.step(1);
        h.assertTrue(search.status() == PathSearch.Status.FAILED, "Pausierter Knoten umgeht Gesamtzeitlimit");
        h.assertTrue(search.nanos() > PathSearch.LIMIT_NO_PROGRESS && !ctx.blocks.searching(), "Zeitlimit oder Cacheabschluss fehlt");
        h.succeed();
    }

    @GameTest(structure = "begleiter-gametest:bench")
    public void pauseVorSimulationVergiftetKeinenSprungcache(GameTestHelper h) {
        for (int x = 0; x <= 12; x++) for (int z = 2; z <= 8; z++)
            if (x <= 2 || x >= 5) for (int y = 0; y <= 3; y++) h.setBlock(x, y, z, Blocks.STONE);
        var c = companion(h, new BlockPos(2, 4, 5));
        var ctx = context(c);
        BlockPos from = c.blockPosition(), to = h.absolutePos(new BlockPos(5, 4, 5));
        var result = new MoveResult();
        result.reset(to.getX(), to.getY(), to.getZ());
        var paused = JumpSim.plan(ctx, from.getX(), from.getY(), from.getZ(), to.getX(), to.getY(), to.getZ(),
                () -> 10L, 0, 1, result);
        h.assertTrue(paused == null && result.yielded && ctx.jumpPlans.isEmpty() && ctx.jumpCacheMisses == 0,
                "Pause wurde als berechneter Fehlsprung gespeichert");
        var actual = JumpSim.plan(ctx, from.getX(), from.getY(), from.getZ(), to.getX(), to.getY(), to.getZ());
        h.assertTrue(actual != null, "Live-Prüfung wird durch alte Suchdeadline blockiert");
        result.reset(to.getX(), to.getY(), to.getZ());
        var cached = JumpSim.plan(ctx, from.getX(), from.getY(), from.getZ(), to.getX(), to.getY(), to.getZ(),
                () -> { throw new AssertionError("Cachetreffer prüft Simulationsdeadline"); }, 0, 1, result);
        h.assertTrue(cached == actual && !result.yielded, "Cachetreffer wird erneut unterbrochen");
        JumpSim.plan(ctx, from.getX(), from.getY(), from.getZ(), from.getX() + 20, to.getY(), to.getZ());
        result.reset(to.getX(), to.getY(), to.getZ());
        var impossible = JumpSim.plan(ctx, from.getX(), from.getY(), from.getZ(), from.getX() + 20, to.getY(), to.getZ(),
                () -> { throw new AssertionError("Negativer Cachetreffer prüft Deadline"); }, 0, 1, result);
        h.assertTrue(impossible == null && !result.yielded, "Bekannter Fehlsprung wird als Pause ausgegeben");
        result.yielded = true;
        Move.TRAVERSE_E.cost(ctx, from.getX(), from.getY(), from.getZ(), result);
        h.assertTrue(!result.yielded, "Neuer Kostenaufruf behält alte Pausenmarkierung");
        h.succeed();
    }
}
