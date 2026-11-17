package dev.begleiter;

import java.util.List;
import java.util.Locale;

import com.mojang.logging.LogUtils;
import org.slf4j.Logger;

import net.fabricmc.fabric.api.gametest.v1.GameTest;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;

import dev.begleiter.CompanionEntity.Task;
import dev.begleiter.nav.Goal;
import dev.begleiter.nav.NavContext;
import dev.begleiter.nav.Path;
import dev.begleiter.nav.PathSearch;

/**
 * Feste Strecken zum Messen der Wegfindung (docs/wegfindung-plan.md). Je Strecke drei Tests:
 * bench… läuft per MOVE_TO-Aufgabe hin und loggt "[Bench] name: Ticks"; neu… läuft direkt mit Suche + Ausführung
 * ("[Bench-Neu] …"); suche… misst nur die Suche ("[Bench-Suche] …", "[Bench-Gewicht] …").
 * Basiswerte des alten Systems stehen im Plan (C11a).
 * Testfläche "bench": 48 x 14 x 12.
 */
public class NavBenchmarks {
    private static final Logger LOG = LogUtils.getLogger();

    private record Course(String name, BlockPos start, BlockPos goal, List<ItemStack> items) {}

    private static void box(GameTestHelper h, int x0, int y0, int z0, int x1, int y1, int z1, Block b) {
        for (int x = x0; x <= x1; x++)
            for (int y = y0; y <= y1; y++)
                for (int z = z0; z <= z1; z++) h.setBlock(x, y, z, b);
    }

    private static void carve(GameTestHelper h, int x, int y, int z) {
        box(h, x, y, z, x, y + 2, z, Blocks.AIR);
    }

    // ------------------------------------------------------------------ Strecken

    private static Course flach40(GameTestHelper h) {
        box(h, 0, 0, 0, 44, 0, 11, Blocks.STONE);
        return new Course("flach40", new BlockPos(2, 1, 6), new BlockPos(42, 1, 6), List.of());
    }

    private static Course treppe10(GameTestHelper h) {
        box(h, 0, 0, 3, 24, 0, 8, Blocks.STONE);
        for (int i = 0; i < 10; i++) box(h, 6 + i, 1, 3, 6 + i, 1 + i, 8, Blocks.STONE);
        box(h, 16, 1, 3, 20, 10, 8, Blocks.STONE);
        return new Course("treppe10", new BlockPos(2, 1, 6), new BlockPos(18, 11, 6), List.of());
    }

    /** Zwei Ufer (Oberkante y=4), dazwischen 5 Blöcke Schlucht, 4 tief bis zum Barriere-Grund: nur per Brücke. */
    private static Course schlucht(GameTestHelper h) {
        box(h, 0, 0, 0, 14, 4, 11, Blocks.STONE);
        box(h, 20, 0, 0, 30, 4, 11, Blocks.STONE);
        box(h, 15, 0, 0, 19, 0, 11, Blocks.BARRIER);
        return new Course("schlucht", new BlockPos(2, 5, 6), new BlockPos(28, 5, 6), List.of(new ItemStack(Items.DIRT, 32)));
    }

    /** Gang (z 5..7) mit Barriere-Wänden und -Decke, 20 Blöcke Stein zum Durchgraben. */
    private static Course tunnel20(GameTestHelper h) {
        box(h, 0, 0, 4, 40, 0, 8, Blocks.STONE);
        box(h, 0, 1, 4, 40, 3, 4, Blocks.BARRIER);
        box(h, 0, 1, 8, 40, 3, 8, Blocks.BARRIER);
        box(h, 0, 3, 4, 40, 3, 8, Blocks.BARRIER);
        box(h, 0, 1, 5, 0, 2, 7, Blocks.BARRIER);
        box(h, 10, 1, 5, 29, 2, 7, Blocks.STONE);
        return new Course("tunnel20", new BlockPos(2, 1, 6), new BlockPos(34, 1, 6), List.of(new ItemStack(Items.STONE_PICKAXE)));
    }

    /** Plattformen (Oberkante y=3) mit Lücken 1, 2, 3 und zum Schluss 1 hoch; Grube 3 tief (Barriere), keine Blöcke. */
    private static Course parkour(GameTestHelper h) {
        box(h, 0, 0, 2, 27, 0, 10, Blocks.BARRIER);
        box(h, 0, 1, 2, 27, 7, 2, Blocks.BARRIER);
        box(h, 0, 1, 10, 27, 7, 10, Blocks.BARRIER);
        box(h, 0, 1, 3, 0, 7, 9, Blocks.BARRIER);
        box(h, 27, 1, 3, 27, 7, 9, Blocks.BARRIER);
        box(h, 1, 1, 3, 5, 3, 9, Blocks.STONE);   // Start
        box(h, 7, 1, 3, 9, 3, 9, Blocks.STONE);   // Lücke 1
        box(h, 12, 1, 3, 14, 3, 9, Blocks.STONE); // Lücke 2
        box(h, 18, 1, 3, 20, 3, 9, Blocks.STONE); // Lücke 3
        box(h, 22, 1, 3, 26, 4, 9, Blocks.STONE); // Lücke 1, einen höher
        return new Course("parkour", new BlockPos(2, 4, 6), new BlockPos(24, 5, 6), List.of());
    }

    /** Steinblock mit gewundenem Gang (3 hoch): geradeaus, Treppe runter, um zwei Ecken, Treppe hoch. Ohne Spitzhacke. */
    private static Course hoehle(GameTestHelper h) {
        box(h, 0, 0, 0, 40, 10, 11, Blocks.STONE);
        for (int x = 1; x <= 12; x++) carve(h, x, 6, 2);
        for (int i = 0; i < 4; i++) carve(h, 13 + i, 5 - i, 2);
        for (int z = 2; z <= 9; z++) carve(h, 16, 2, z);
        for (int x = 16; x <= 28; x++) carve(h, x, 2, 9);
        for (int i = 0; i < 4; i++) carve(h, 29 + i, 3 + i, 9);
        for (int x = 32; x <= 38; x++) carve(h, x, 6, 9);
        return new Course("hoehle", new BlockPos(2, 6, 2), new BlockPos(38, 6, 9), List.of());
    }

    // ------------------------------------------------------------------ Ablauf

    private static CompanionEntity companion(GameTestHelper h, Course k) {
        CompanionEntity c = h.spawn(CompanionMod.COMPANION, k.start);
        c.setPersistenceRequired();
        c.stay(); // nicht selbstständig losplanen
        for (ItemStack s : k.items) c.giveToInventory(s.copy());
        return c;
    }

    /** Läuft mit dem heutigen System zum Ziel und loggt beim Ankommen die Messwerte. */
    private static void run(GameTestHelper h, Course k) {
        CompanionEntity c = companion(h, k);
        BlockPos goal = h.absolutePos(k.goal);
        long t0 = h.getLevel().getGameTime();
        c.addTask(Task.moveTo(goal, 2));
        h.succeedWhen(() -> {
            h.assertTrue(c.blockPosition().distSqr(goal) <= 2, "Nicht am Ziel (bei " + c.blockPosition().toShortString()
                    + ", Ziel " + goal.toShortString() + ", Aufgabe " + c.currentTaskText() + ")");
            LOG.info("[Bench] {}: {} Ticks", k.name, h.getLevel().getGameTime() - t0);
        });
    }

    /** Suche vom Start aus (mehrere Heuristik-Gewichte): muss ankommen, Knoten und Zeit werden geloggt. */
    private static void compare(GameTestHelper h, Course k) {
        CompanionEntity c = companion(h, k);
        BlockPos start = h.absolutePos(k.start), goal = h.absolutePos(k.goal);
        // je Gewicht dreimal rechnen (der erste Lauf wärmt JIT und Block-Tabelle auf), schnellste Zeit zählt
        StringBuilder sweep = new StringBuilder();
        for (double w : new double[]{1.0, 1.25, 1.5, 2.0}) {
            PathSearch best = null;
            for (int i = 0; i < 3; i++) {
                PathSearch t = new PathSearch(new NavContext(c), start, new Goal.Block(goal), w);
                t.step(2_000_000_000L);
                if (best == null || t.nanos() < best.nanos()) best = t;
            }
            Path bp = best.path();
            sweep.append(String.format(Locale.ROOT, " | w%.2f: %s %d Kn %s ms Kosten %.0f", w, best.status(), best.nodes(),
                    ms(best.nanos()), bp == null ? 0.0 : bp.totalCost()));
        }
        LOG.info("[Bench-Gewicht] {}:{}", k.name, sweep);
        PathSearch s = new PathSearch(new NavContext(c), start, new Goal.Block(goal));
        s.step(2_000_000_000L);
        Path p = s.path();
        LOG.info("[Bench-Suche] {}: {} ({} Knoten, {} ms, {} Schritte, Kosten {})", k.name,
                s.status(), s.nodes(), ms(s.nanos()), p == null ? 0 : p.length(), p == null ? "-" : String.format(Locale.ROOT, "%.0f", p.totalCost()));
        h.assertTrue(s.status() == PathSearch.Status.FOUND, "Neue Suche: " + s.status() + " nach " + s.nodes() + " Knoten"
                + (p == null ? "" : ", Ende " + h.relativePos(p.end()).toShortString()));
        if (Boolean.getBoolean("begleiter.nav.benchmark"))
            dev.begleiter.nav.NavSearchComparison.measure(h, c, start, goal, k.name);
        if (Boolean.getBoolean("begleiter.nav.sliceBenchmark"))
            dev.begleiter.nav.NavSliceComparison.measure(h, c, start, goal, k.name);
        h.succeed();
    }

    /** Läuft mit der neuen Wegfindung (Suche + Ausführung) zum Ziel und loggt "[Bench-Neu] …". */
    private static void runNew(GameTestHelper h, Course k) {
        CompanionEntity c = companion(h, k);
        BlockPos goal = h.absolutePos(k.goal);
        long t0 = h.getLevel().getGameTime();
        dev.begleiter.nav.NavDriver d = new dev.begleiter.nav.NavDriver(c, new Goal.Block(goal));
        h.succeedWhen(() -> {
            d.tick();
            h.assertTrue(d.arrived, "Nicht am Ziel (bei " + c.blockPosition().toShortString() + ", Ziel "
                    + goal.toShortString() + ", " + d.describe() + ")");
            LOG.info("[Bench-Neu] {}: {} Ticks, {} Suchen ({} ms), {} Fehlschläge{}", k.name, h.getLevel().getGameTime() - t0,
                    d.searches, ms(d.searchNanos), d.failures, d.lastFail.isEmpty() ? "" : " (" + d.lastFail + ")");
        });
    }

    @GameTest(maxTicks = 600, structure = "begleiter-gametest:bench")
    public void neuFlach40(GameTestHelper h) { runNew(h, flach40(h)); }

    @GameTest(maxTicks = 800, structure = "begleiter-gametest:bench")
    public void neuTreppe10(GameTestHelper h) { runNew(h, treppe10(h)); }

    @GameTest(maxTicks = 1500, structure = "begleiter-gametest:bench")
    public void neuSchlucht(GameTestHelper h) { runNew(h, schlucht(h)); }

    @GameTest(maxTicks = 3000, structure = "begleiter-gametest:bench")
    public void neuTunnel20(GameTestHelper h) { runNew(h, tunnel20(h)); }

    @GameTest(maxTicks = 1200, structure = "begleiter-gametest:bench")
    public void neuParkour(GameTestHelper h) { runNew(h, parkour(h)); }

    /**
     * Reiner Bewegungsvergleich auf demselben unveränderlichen Weg, ohne zweiten Bot, der Kollisionen beeinflusst.
     * Die Wegprüfungen der Glättung arbeiten mit einem Zeitbudget; ein erster, nicht gezählter Lauf wärmt die JVM auf,
     * sonst misst der Test bei einem Einzellauf die Aufwärmzeit statt der Glättung.
     */
    @GameTest(maxTicks = 900, structure = "begleiter-gametest:bench")
    public void parkourGlattGegenSchritte(GameTestHelper h) {
        Course course = parkour(h);
        CompanionEntity[] bot = {companion(h, course)};
        bot[0].setOnGround(true);
        BlockPos goal = h.absolutePos(course.goal);
        var ctx = new NavContext(bot[0]);
        ctx.allowBreak = ctx.allowPlace = false;
        var search = new PathSearch(ctx, bot[0].blockPosition(), new Goal.Block(goal));
        search.step(1_000_000_000L);
        h.assertTrue(search.status() == PathSearch.Status.FOUND, "Vollstaendiger Vergleichsweg fehlt");
        Path path = search.path();
        dev.begleiter.nav.PathExecutor[] executor = {new dev.begleiter.nav.PathExecutor(path, ctx, true)};
        // Läufe: 0 = Aufwärmen mit Glättung (nicht gezählt), 1 = mit Glättung, 2 = in Schritten
        boolean[] smooth = {true, true, false};
        int[] finished = {-1, -1, -1};
        int[] active = {0};
        long[] started = {h.getLevel().getGameTime()};
        h.succeedWhen(() -> {
            int i = active[0];
            if (finished[i] < 0) {
                if (bot[0].onGround() && bot[0].blockPosition().equals(goal))
                    finished[i] = (int) (h.getLevel().getGameTime() - started[0]);
                else h.assertTrue(executor[0].tick(bot[0]) != dev.begleiter.nav.PathExecutor.Status.FAILED,
                        "Vergleichsbewegung gescheitert: " + executor[0].failReason);
            }
            if (i < 2 && finished[i] >= 0) {
                bot[0].discard();
                bot[0] = companion(h, course);
                bot[0].setOnGround(true);
                var nextContext = new NavContext(bot[0]);
                nextContext.allowBreak = nextContext.allowPlace = false;
                executor[0] = new dev.begleiter.nav.PathExecutor(path, nextContext, smooth[i + 1]);
                active[0] = i + 1;
                started[0] = h.getLevel().getGameTime();
            }
            h.assertTrue(finished[1] >= 0 && finished[2] >= 0, "Vergleich noch unterwegs");
            LOG.info("[Nav-Motion-Paired] parkour warmup={} smooth={} steps={}", finished[0], finished[1], finished[2]);
            h.assertTrue(finished[1] <= Math.ceil(finished[2] * 1.05), "Glaettung verlangsamt Parkour: " + finished[1] + " statt " + finished[2]);
        });
    }

    @GameTest(maxTicks = 1500, structure = "begleiter-gametest:bench")
    public void neuHoehle(GameTestHelper h) { runNew(h, hoehle(h)); }

    /**
     * Weite Reise (TRAVEL): fertig, sobald er waagerecht 20 Blöcke vor dem Ziel ist (das alte System kam hier nicht an).
     */
    @GameTest(maxTicks = 600, structure = "begleiter-gametest:bench")
    public void reiseUeberTreppeUndGraben(GameTestHelper h) {
        box(h, 0, 0, 0, 47, 0, 11, Blocks.STONE);
        for (int i = 0; i < 3; i++) box(h, 10 + i, 1, 0, 10 + i, 1 + i, 11, Blocks.STONE); // Stufen hoch
        box(h, 13, 1, 0, 20, 3, 11, Blocks.STONE);
        box(h, 21, 0, 0, 22, 0, 11, Blocks.AIR); // Graben dahinter (2 breit, runter auf den Boden der Welt)
        Course k = new Course("reise", new BlockPos(2, 1, 6), new BlockPos(46, 1, 6), List.of());
        CompanionEntity c = companion(h, k);
        BlockPos target = h.absolutePos(k.goal);
        long t0 = h.getLevel().getGameTime();
        c.addTask(Task.travel(target));
        h.succeedWhen(() -> {
            h.assertTrue(c.taskCount() == 0, "Reise nicht fertig (bei " + c.blockPosition().toShortString() + ", Aufgabe "
                    + c.currentTaskText() + ", " + c.nav.debugText() + ")");
            LOG.info("[Bench] reise: {} Ticks", h.getLevel().getGameTime() - t0);
        });
    }

    /**
     * Bergbau: Gang im Stein mit 6 Eisenerzen in Wänden/Decke, eins hat Lava, eins Wasser dahinter. Er soll
     * die 4 sicheren holen und die 2 gefährlichen stehen lassen. Läuft mit Schalter aus und an, loggt die Ticks.
     */
    @GameTest(maxTicks = 2400, structure = "begleiter-gametest:bench")
    public void bergbauVierErze(GameTestHelper h) {
        box(h, 0, 0, 0, 40, 8, 11, Blocks.STONE);
        box(h, 1, 1, 4, 34, 3, 7, Blocks.AIR);
        BlockPos[] safe = {new BlockPos(6, 2, 3), new BlockPos(12, 4, 6), new BlockPos(18, 2, 8), new BlockPos(26, 1, 3)};
        BlockPos lavaOre = new BlockPos(14, 2, 8), waterOre = new BlockPos(22, 2, 3);
        for (BlockPos p : safe) h.setBlock(p, Blocks.IRON_ORE);
        h.setBlock(lavaOre, Blocks.IRON_ORE);
        h.setBlock(lavaOre.south(), Blocks.LAVA);
        h.setBlock(waterOre, Blocks.IRON_ORE);
        h.setBlock(waterOre.north(), Blocks.WATER);
        Course k = new Course("bergbau", new BlockPos(2, 1, 5), new BlockPos(2, 1, 5), List.of(new ItemStack(Items.STONE_PICKAXE)));
        CompanionEntity c = companion(h, k);
        long t0 = h.getLevel().getGameTime();
        c.addTask(Task.mine(Resources.IRON.key(), 4));
        h.succeedWhen(() -> {
            h.assertBlockPresent(Blocks.IRON_ORE, lavaOre);
            h.assertBlockPresent(Blocks.IRON_ORE, waterOre);
            h.assertTrue(CompanionCraft.count(c, "raw_iron") >= 4, "Erst " + CompanionCraft.count(c, "raw_iron")
                    + " Eisen (bei " + c.blockPosition().toShortString() + ", Aufgabe " + c.currentTaskText() + ", " + c.nav.debugText() + ")");
            LOG.info("[Bench] bergbau: {} Ticks", h.getLevel().getGameTime() - t0);
        });
    }

    private static String ms(long nanos) {
        return String.format(Locale.ROOT, "%.1f", nanos / 1e6);
    }

    @GameTest(maxTicks = 600, structure = "begleiter-gametest:bench")
    public void benchFlach40(GameTestHelper h) { run(h, flach40(h)); }

    @GameTest(maxTicks = 800, structure = "begleiter-gametest:bench")
    public void benchTreppe10(GameTestHelper h) { run(h, treppe10(h)); }

    @GameTest(maxTicks = 1500, structure = "begleiter-gametest:bench")
    public void benchSchluchtBruecke(GameTestHelper h) { run(h, schlucht(h)); }

    @GameTest(maxTicks = 3000, structure = "begleiter-gametest:bench")
    public void benchTunnel20(GameTestHelper h) { run(h, tunnel20(h)); }

    @GameTest(maxTicks = 1200, structure = "begleiter-gametest:bench")
    public void benchParkour(GameTestHelper h) { run(h, parkour(h)); }

    @GameTest(maxTicks = 1500, structure = "begleiter-gametest:bench")
    public void benchHoehle(GameTestHelper h) { run(h, hoehle(h)); }

    @GameTest(structure = "begleiter-gametest:bench")
    public void sucheFlach40(GameTestHelper h) { compare(h, flach40(h)); }

    @GameTest(structure = "begleiter-gametest:bench")
    public void sucheTreppe10(GameTestHelper h) { compare(h, treppe10(h)); }

    @GameTest(structure = "begleiter-gametest:bench")
    public void sucheSchlucht(GameTestHelper h) { compare(h, schlucht(h)); }

    @GameTest(structure = "begleiter-gametest:bench")
    public void sucheTunnel20(GameTestHelper h) { compare(h, tunnel20(h)); }

    @GameTest(structure = "begleiter-gametest:bench")
    public void sucheParkour(GameTestHelper h) { compare(h, parkour(h)); }

    @GameTest(structure = "begleiter-gametest:bench")
    public void sucheHoehle(GameTestHelper h) { compare(h, hoehle(h)); }
}
