package dev.begleiter.nav;

import java.util.Locale;

import com.mojang.logging.LogUtils;
import org.slf4j.Logger;

import net.fabricmc.fabric.api.gametest.v1.GameTest;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.phys.Vec3;

import dev.begleiter.CompanionEntity;
import dev.begleiter.CompanionMod;

/**
 * Parkour (docs/PARKOUR_MECHANIC.md): Simulation gegen echte Physik, Profile mit Wiederholungen
 * ("[Parkour-Bench] …", Anzahl über die Umgebungsvariable PARKOUR_REPS, Standard 20), Kette, Diagonale, Fehlpfade,
 * niedrige Decke, Weltänderung mitten im Sprung und Eimer-Rettung. Testfläche "bench": 48 x 14 x 12.
 */
public class ParkourTests {
    private static final Logger LOG = LogUtils.getLogger();
    private static final String BENCH = "begleiter-gametest:bench";
    private static final int REPS = reps();

    private static int reps() {
        try {
            String s = System.getenv("PARKOUR_REPS");
            return s == null ? 20 : Math.max(1, Integer.parseInt(s.trim()));
        } catch (NumberFormatException e) {
            return 20;
        }
    }

    private static void box(GameTestHelper h, int x0, int y0, int z0, int x1, int y1, int z1, Block b) {
        for (int x = x0; x <= x1; x++)
            for (int y = y0; y <= y1; y++)
                for (int z = z0; z <= z1; z++) h.setBlock(x, y, z, b);
    }

    private static CompanionEntity companion(GameTestHelper h, BlockPos rel) {
        CompanionEntity c = h.spawn(CompanionMod.COMPANION, rel);
        c.setPersistenceRequired();
        c.stay();
        c.getInventory().clearContent();
        c.setItemSlot(EquipmentSlot.MAINHAND, ItemStack.EMPTY);
        return c;
    }

    /** Grube (Barriere-Boden y=0, Wände) über x 0..40, z 2..10. */
    private static void pit(GameTestHelper h, int top) {
        box(h, 0, 0, 2, 40, 0, 10, Blocks.BARRIER);
        box(h, 0, 1, 2, 40, top + 4, 2, Blocks.BARRIER);
        box(h, 0, 1, 10, 40, top + 4, 10, Blocks.BARRIER);
        box(h, 0, 1, 3, 0, top + 4, 9, Blocks.BARRIER);
    }

    /** Start-Plattform x 1..5 (Oberkante top), Lücke gap, Ziel-Plattform 5 lang, rise höher/tiefer. Gibt die Ziel-x. */
    private static int gapCourse(GameTestHelper h, int gap, int rise, int top) {
        pit(h, top + Math.max(0, rise));
        box(h, 1, 1, 3, 5, top, 9, Blocks.STONE);
        int bx = 6 + gap;
        box(h, bx, 1, 3, bx + 4, top + rise, 9, Blocks.STONE);
        return bx + 2;
    }

    // ------------------------------------------------------------------ Simulation gegen echte Physik

    /**
     * Läuft 30 Ticks geradeaus (Tempo wie simuliert?), springt dann mit Sprint wie die Ausführung und vergleicht die
     * Landestelle mit der Simulation.
     */
    @GameTest(maxTicks = 200, structure = BENCH)
    public void simulationWieEcht(GameTestHelper h) {
        box(h, 0, 0, 0, 44, 0, 11, Blocks.STONE);
        CompanionEntity c = companion(h, new BlockPos(2, 1, 6));
        NavContext ctx = new NavContext(c);
        double[] st = {0, Double.NaN, Double.NaN, 0}; // Tick, Simulation x, Simulation-Ticks, Flugticks
        h.onEachTick(() -> {
            int t = (int) st[0]++;
            c.navControlUntil = c.tickCount + 2; // Sprint steuert der Test (wie die Ausführung)
            if (t < 30) {
                c.setSprinting(false);
                c.getMoveControl().setWantedPosition(c.getX() + 10, c.getY(), c.getZ(), Movement.WALK_SPEED);
                return;
            }
            if (t == 30) {
                double v = c.getDeltaMovement().x;
                LOG.info(String.format(Locale.ROOT, "[Parkour-Kalib] Gehen: gemessen %.4f, simuliert %.4f Blöcke/Tick (gespeichert)",
                        v, ctx.body.runSpeed(false)));
                h.assertTrue(Math.abs(v - ctx.body.runSpeed(false)) < 0.01, "Gehtempo passt nicht: " + v + " statt " + ctx.body.runSpeed(false));
                JumpSim.Flight f = JumpSim.fly(ctx.blocks, ctx.body, c.getX(), c.getY(), c.getZ(), 1, 0, v, true, null);
                st[1] = f.x;
                st[2] = f.ticks;
                c.setYRot(-90f);
                c.setYBodyRot(-90f);
                c.setSprinting(true);
                c.setDeltaMovement(v + JumpSim.SPRINT_BOOST, ctx.body.jumpPower(), 0);
                c.getMoveControl().setWantedPosition(c.getX() + 10, c.getY(), c.getZ(), Movement.WALK_SPEED);
                return;
            }
            st[3]++;
            c.getMoveControl().setWantedPosition(c.getX() + 10, c.getY(), c.getZ(), Movement.WALK_SPEED);
            if (c.onGround() && st[3] >= 2) {
                double dx = c.getX() - st[1];
                LOG.info(String.format(Locale.ROOT, "[Parkour-Kalib] Sprint-Sprung: Landung x %.3f, simuliert %.3f (Abw %.3f), Flug %d/%d Ticks",
                        c.getX(), st[1], dx, (int) st[3], (int) st[2]));
                h.assertTrue(Math.abs(dx) < 0.1 && Math.abs(st[3] - st[2]) <= 1, String.format(Locale.ROOT,
                        "Simulation weicht ab: %.3f Blöcke, %d statt %d Ticks", dx, (int) st[3], (int) st[2]));
                h.succeed();
            }
        });
    }

    // ------------------------------------------------------------------ Profile mit Wiederholungen

    /**
     * Läuft REPS-mal vom Start zum Ziel (jedes Mal neu gesetzt, volle Leben). Erfolgsquote mindestens minRate,
     * keine Todesfälle; loggt Quote und mittlere Ticks.
     */
    private static void repeat(GameTestHelper h, String name, BlockPos startRel, BlockPos goalRel, double minRate) {
        CompanionEntity c = companion(h, startRel);
        Vec3 start = Vec3.atBottomCenterOf(h.absolutePos(startRel));
        BlockPos goal = h.absolutePos(goalRel);
        int lowest = Math.min(startRel.getY(), goalRel.getY());
        int[] st = {0, 0, 0, 0, 0}; // Läufe, Erfolge, Ticks gesamt, Ticks dieses Laufs, längste Kette
        NavDriver[] d = {new NavDriver(c, new Goal.Block(goal))};
        StringBuilder fails = new StringBuilder();
        h.onEachTick(() -> {
            if (st[0] >= REPS) return;
            if (!c.isAlive()) {
                h.fail(name + ": Begleiter gestorben (" + fails + ")");
                return;
            }
            st[3]++;
            d[0].tick();
            PathExecutor ex = d[0].executor();
            if (ex != null && ex.current() != null) st[4] = Math.max(st[4], ex.current().chainLength);
            boolean fell = c.onGround() && c.getY() < h.absolutePos(new BlockPos(0, lowest, 0)).getY() - 1.5;
            if (!d[0].arrived && !fell && st[3] < 400) return;
            if (d[0].arrived) {
                st[1]++;
                st[2] += st[3];
            } else if (fails.length() < 400) fails.append(fell ? "gefallen" : "Zeit").append(": ").append(d[0].describe()).append("; ");
            st[0]++;
            st[3] = 0;
            c.setDeltaMovement(Vec3.ZERO);
            c.snapTo(start.x, start.y, start.z, 0, 0);
            c.fallDistance = 0;
            c.setHealth(c.getMaxHealth());
            d[0] = new NavDriver(c, new Goal.Block(goal));
            if (st[0] < REPS) return;
            double rate = st[1] / (double) REPS;
            LOG.info(String.format(Locale.ROOT, "[Parkour-Bench] %s: %d/%d (%.0f %%), Ø %.0f Ticks, längste Kette %d%s", name,
                    st[1], REPS, rate * 100, st[1] == 0 ? 0.0 : st[2] / (double) st[1], st[4], fails.isEmpty() ? "" : " | " + fails));
            if (rate + 1e-9 < minRate) h.fail(String.format(Locale.ROOT, "%s: nur %.0f %% (mind. %.0f %%): %s", name, rate * 100, minRate * 100, fails));
            else h.succeed();
        });
    }

    @GameTest(maxTicks = 13000, structure = BENCH)
    public void sprungLuecke1(GameTestHelper h) {
        int gx = gapCourse(h, 1, 0, 3);
        repeat(h, "Lücke 1", new BlockPos(2, 4, 6), new BlockPos(gx, 4, 6), 0.98);
    }

    @GameTest(maxTicks = 13000, structure = BENCH)
    public void sprungLuecke2(GameTestHelper h) {
        int gx = gapCourse(h, 2, 0, 3);
        repeat(h, "Lücke 2", new BlockPos(2, 4, 6), new BlockPos(gx, 4, 6), 0.98);
    }

    @GameTest(maxTicks = 13000, structure = BENCH)
    public void sprungLuecke3Sprint(GameTestHelper h) {
        int gx = gapCourse(h, 3, 0, 3);
        repeat(h, "Lücke 3", new BlockPos(2, 4, 6), new BlockPos(gx, 4, 6), 0.95);
    }

    /** Lücke 4 nur, wenn die Simulation die Reserve zeigt; sonst darf die Suche den Sprung nicht nehmen. */
    @GameTest(maxTicks = 13000, structure = BENCH)
    public void sprungLuecke4NurMitReserve(GameTestHelper h) {
        int gx = gapCourse(h, 4, 0, 3);
        CompanionEntity probe = companion(h, new BlockPos(2, 4, 4));
        NavContext ctx = new NavContext(probe);
        BlockPos a = h.absolutePos(new BlockPos(5, 4, 6)), b = h.absolutePos(new BlockPos(10, 4, 6));
        JumpSim.Plan p = JumpSim.plan(ctx, a.getX(), a.getY(), a.getZ(), b.getX(), b.getY(), b.getZ());
        probe.discard();
        if (p == null) {
            NavContext c2 = new NavContext(companion(h, new BlockPos(2, 4, 6)));
            PathSearch s = new PathSearch(c2, h.absolutePos(new BlockPos(2, 4, 6)), new Goal.Block(h.absolutePos(new BlockPos(gx, 4, 6))));
            s.step(2_000_000_000L);
            LOG.info("[Parkour-Bench] Lücke 4: Simulation zeigt keine Reserve – Sprung verboten (Suche {})", s.status());
            h.assertTrue(s.status() != PathSearch.Status.FOUND, "Lücke 4 ohne Reserve gefunden");
            h.succeed();
            return;
        }
        LOG.info("[Parkour-Bench] Lücke 4: Plan {}", p.describe());
        h.assertTrue(p.margin >= JumpSim.MIN_MARGIN, "Marge zu klein: " + p.margin);
        repeat(h, "Lücke 4", new BlockPos(2, 4, 6), new BlockPos(gx, 4, 6), 0.95);
    }

    @GameTest(maxTicks = 13000, structure = BENCH)
    public void sprungStufeHoch(GameTestHelper h) {
        int gx = gapCourse(h, 1, 1, 3);
        repeat(h, "Stufe hoch (Lücke 1, +1)", new BlockPos(2, 4, 6), new BlockPos(gx, 5, 6), 0.98);
    }

    @GameTest(maxTicks = 13000, structure = BENCH)
    public void sprungStufeHochLuecke2(GameTestHelper h) {
        int gx = gapCourse(h, 2, 1, 3);
        repeat(h, "Stufe hoch (Lücke 2, +1)", new BlockPos(2, 4, 6), new BlockPos(gx, 5, 6), 0.95);
    }

    /** Über eine 7 tiefe Grube auf eine 3 Blöcke tiefere Plattform (die Testfläche hat bei 14 eine Decke). */
    @GameTest(maxTicks = 13000, structure = BENCH)
    public void sprungRunter(GameTestHelper h) {
        pit(h, 7);
        box(h, 1, 1, 3, 5, 7, 9, Blocks.STONE);
        box(h, 8, 1, 3, 12, 4, 9, Blocks.STONE);
        repeat(h, "Runter (Lücke 2, -3)", new BlockPos(2, 8, 6), new BlockPos(10, 5, 6), 0.98);
    }

    /** Ecke über Eck: nur schräg (oder seitlich versetzt) erreichbar. */
    @GameTest(maxTicks = 13000, structure = BENCH)
    public void sprungDiagonal(GameTestHelper h) {
        pit(h, 3);
        box(h, 1, 1, 3, 4, 3, 5, Blocks.STONE);
        box(h, 6, 1, 7, 10, 3, 9, Blocks.STONE);
        repeat(h, "Diagonal", new BlockPos(2, 4, 4), new BlockPos(8, 4, 8), 0.98);
    }

    /** Trittsteine (je 1 lang, 3 breit, Lücke 1): Kette aus vier Sprüngen mit Schwung. */
    @GameTest(maxTicks = 13000, structure = BENCH)
    public void sprungKette(GameTestHelper h) {
        pit(h, 3);
        box(h, 1, 1, 5, 3, 3, 7, Blocks.STONE);
        for (int x = 5; x <= 9; x += 2) box(h, x, 1, 5, x, 3, 7, Blocks.STONE);
        box(h, 11, 1, 5, 14, 3, 7, Blocks.STONE);
        repeat(h, "Kette", new BlockPos(2, 4, 6), new BlockPos(13, 4, 6), 0.95);
    }

    /**
     * Die Vorführ-Arena (/companion parkourtest): 100 Blöcke über der Testfläche bauen (stört keine anderen Tests),
     * Vorführung starten, alle 11 Bahnen müssen wie erwartet ausgehen.
     */
    @GameTest(maxTicks = 4000)
    public void arenaVorfuehrungAlleBahnen(GameTestHelper h) {
        var p = h.makeMockServerPlayerInLevel();
        BlockPos high = h.absolutePos(new BlockPos(1, 100, 1));
        p.teleportTo(high.getX() + 0.5, high.getY(), high.getZ() + 0.5);
        // ein Mock-Spieler lädt keine Chunks: die ganze Arena fest laden, damit der Begleiter überall tickt
        var sl = h.getLevel();
        java.util.List<long[]> forced = new java.util.ArrayList<>();
        for (int cx = (high.getX() - 8) >> 4; cx <= (high.getX() + 40) >> 4; cx++)
            for (int cz = (high.getZ() - 8) >> 4; cz <= (high.getZ() + 100) >> 4; cz++)
                if (sl.setChunkForced(cx, cz, true)) forced.add(new long[]{cx, cz});
        String built = dev.begleiter.ParkourTestHelper.build(p);
        h.assertTrue(built.startsWith("Parkour-Arena gebaut"), built);
        CompanionEntity c = CompanionMod.COMPANION.create(h.getLevel(), net.minecraft.world.entity.EntitySpawnReason.MOB_SUMMONED);
        c.snapTo(p.getX(), p.getY(), p.getZ(), 0, 0);
        c.setOwner(p.getUUID());
        h.getLevel().addFreshEntity(c);
        c.getInventory().clearContent();
        dev.begleiter.ParkourCourse.startShow(p, c);
        h.succeedWhen(() -> {
            int ok = dev.begleiter.ParkourCourse.lastShowOk();
            h.assertTrue(ok >= 0, "Vorführung läuft noch");
            LOG.info("[Parkour-Bench] Arena-Vorführung: {}/{} wie erwartet", ok, dev.begleiter.ParkourCourse.lanes());
            for (long[] f : forced) sl.setChunkForced((int) f[0], (int) f[1], false);
            forced.clear();
            h.assertTrue(ok == dev.begleiter.ParkourCourse.lanes(), "Nur " + ok + " Bahnen wie erwartet");
            c.discard();
        });
    }

    // ------------------------------------------------------------------ Sicherheit

    @GameTest(structure = BENCH)
    public void sprungCacheWirdBeiWeltaenderungVerworfen(GameTestHelper h) {
        gapCourse(h, 2, 0, 3);
        CompanionEntity c = companion(h, new BlockPos(2, 4, 6));
        NavContext ctx = new NavContext(c);
        BlockPos a = h.absolutePos(new BlockPos(5, 4, 6)), b = h.absolutePos(new BlockPos(8, 4, 6));
        JumpSim.Plan first = JumpSim.plan(ctx, a.getX(), a.getY(), a.getZ(), b.getX(), b.getY(), b.getZ());
        h.assertTrue(first != null, "Ausgangssprung nicht möglich: " + JumpSim.lastReject);
        JumpSim.Plan cached = JumpSim.plan(ctx, a.getX(), a.getY(), a.getZ(), b.getX(), b.getY(), b.getZ());
        h.assertTrue(cached == first && ctx.jumpCacheHits == 1, "Sprung unnötig neu simuliert");
        box(h, 6, 5, 3, 7, 8, 9, Blocks.BARRIER);
        ctx.blocks.clearCache();
        h.assertTrue(JumpSim.plan(ctx, a.getX(), a.getY(), a.getZ(), b.getX(), b.getY(), b.getZ()) == null,
                "Cache ignoriert neue Wand");
        h.assertTrue(ctx.jumpCacheMisses == 2, "Weltänderung nicht neu simuliert");
        h.succeed();
    }

    @GameTest(structure = BENCH)
    public void simulationErkenntGefahrNebenKoerpermitte(GameTestHelper h) {
        box(h, 1, 0, 3, 14, 0, 9, Blocks.STONE);
        h.setBlock(5, 2, 7, Blocks.COBWEB);
        CompanionEntity c = companion(h, new BlockPos(3, 1, 6));
        NavContext ctx = new NavContext(c);
        Vec3 start = Vec3.atBottomCenterOf(h.absolutePos(new BlockPos(3, 1, 6))).add(0, 0, 0.35);
        JumpSim.Flight f = JumpSim.fly(ctx.blocks, ctx.body, start.x, start.y, start.z, 1, 0,
                ctx.body.runSpeed(true), true, null);
        h.assertTrue(f.hazard, "Spinnwebe an der Koerperseite nicht erkannt");
        h.succeed();
    }

    @GameTest(maxTicks = 200, structure = BENCH)
    public void tieferClutchLandetSchadensfreiUndHoltEimerZurueck(GameTestHelper h) {
        pit(h, 11);
        box(h, 1, 1, 3, 5, 11, 9, Blocks.STONE);
        CompanionEntity c = companion(h, new BlockPos(5, 12, 6));
        c.setHealth(9);
        c.giveToInventory(new ItemStack(Items.WATER_BUCKET));
        BlockPos src = h.absolutePos(new BlockPos(5, 12, 6)), dst = h.absolutePos(new BlockPos(6, 1, 6));
        NavContext ctx = new NavContext(c);
        Movement move = new Movement(Move.DESCEND_E, src, dst, 50);
        Movement.Status[] status = {Movement.Status.RUNNING};
        h.onEachTick(() -> {
            if (h.getTick() < 3 || status[0] != Movement.Status.RUNNING) return;
            c.navControlUntil = c.tickCount + 2;
            status[0] = move.tick(c, ctx);
            h.assertTrue(status[0] != Movement.Status.FAILED, "Clutch fehlgeschlagen: " + move.failReason);
        });
        h.succeedWhen(() -> {
            h.assertTrue(status[0] == Movement.Status.SUCCESS, "Noch nicht sicher gelandet");
            h.assertTrue(c.getHealth() >= 9, "Fallschaden: " + c.getHealth());
            h.assertTrue(c.findItem(Items.WATER_BUCKET) >= 0 && c.findItem(Items.BUCKET) < 0, "Eimer nicht korrekt zurückgenommen");
            h.assertTrue(h.getLevel().getFluidState(dst).isEmpty(), "Quelle zurückgelassen");
        });
    }

    @GameTest(structure = BENCH)
    public void clutchBrauchtTragfaehigesErsetzbaresZiel(GameTestHelper h) {
        box(h, 1, 0, 3, 12, 0, 9, Blocks.STONE);
        CompanionEntity c = companion(h, new BlockPos(2, 1, 6));
        c.giveToInventory(new ItemStack(Items.WATER_BUCKET));
        BlockPos land = h.absolutePos(new BlockPos(8, 1, 6));
        NavContext ctx = new NavContext(c);
        h.assertTrue(ClutchPlan.water(ctx, land, 10) != null, "Sicheres Wasserziel verworfen");
        h.setBlock(8, 1, 6, Blocks.OAK_SIGN);
        h.assertTrue(ClutchPlan.water(ctx, land, 10) == null, "Nicht ersetzbares Ziel akzeptiert");
        h.setBlock(8, 1, 6, Blocks.AIR);
        h.setBlock(8, 0, 6, Blocks.SAND);
        h.assertTrue(ClutchPlan.water(ctx, land, 10) == null, "Rieselnden Untergrund akzeptiert");
        h.setBlock(8, 0, 6, Blocks.MAGMA_BLOCK);
        h.assertTrue(ClutchPlan.water(ctx, land, 10) == null, "Gefährlichen Untergrund akzeptiert");
        h.setBlock(8, 0, 6, Blocks.AIR);
        h.assertTrue(ClutchPlan.water(ctx, land, 10) == null, "Wasserplatzierung ohne Boden akzeptiert");
        h.succeed();
    }

    @GameTest(structure = BENCH)
    public void clutchBrauchtItemUndPlatziererlaubnis(GameTestHelper h) {
        box(h, 1, 0, 3, 12, 0, 9, Blocks.STONE);
        CompanionEntity c = companion(h, new BlockPos(2, 1, 6));
        BlockPos land = h.absolutePos(new BlockPos(8, 1, 6));
        h.assertTrue(ClutchPlan.water(new NavContext(c), land, 10) == null, "Clutch ohne Eimer");
        c.giveToInventory(new ItemStack(Items.WATER_BUCKET));
        NavContext ctx = new NavContext(c);
        ctx.allowPlace = false;
        h.assertTrue(ClutchPlan.water(ctx, land, 10) == null, "Platzierverbot ignoriert");
        ctx.allowPlace = true;
        h.assertTrue(ClutchPlan.water(ctx, land, Move.BUCKET_FALL + 1) == null, "Fallgrenze ignoriert");
        h.succeed();
    }

    @GameTest(structure = BENCH)
    public void clutchFensterRespektiertReichweiteUndLandespalte(GameTestHelper h) {
        ClutchPlan plan = new ClutchPlan(new BlockPos(0, 1, 0), 10, 20);
        h.assertTrue(plan.inPlacementWindow(new Vec3(0.5, 3, 0.5), new Vec3(0, -1, 0), 1.6, 0.3), "Gültiges Fenster verworfen");
        h.assertTrue(!plan.inPlacementWindow(new Vec3(0.5, 8, 0.5), new Vec3(0, -3, 0), 1.6, 0.3), "Fernplatzierung erlaubt");
        h.assertTrue(!plan.inPlacementWindow(new Vec3(0.9, 3, 0.5), new Vec3(0.3, -1, 0), 1.6, 0.3), "Überschießen ignoriert");
        h.assertTrue(!plan.inPlacementWindow(new Vec3(0.5, 3, 0.5), new Vec3(0, 0.2, 0), 1.6, 0.3), "Clutch im Aufstieg");
        h.succeed();
    }

    @GameTest(maxTicks = 100, structure = BENCH)
    public void verlorenerEimerStopptVorDemAbgrund(GameTestHelper h) {
        pit(h, 7);
        box(h, 1, 1, 3, 5, 7, 9, Blocks.STONE);
        CompanionEntity c = companion(h, new BlockPos(5, 8, 6));
        c.setHealth(9);
        c.giveToInventory(new ItemStack(Items.WATER_BUCKET));
        NavContext old = new NavContext(c);
        c.getInventory().clearContent();
        BlockPos src = h.absolutePos(new BlockPos(5, 8, 6)), dst = h.absolutePos(new BlockPos(6, 1, 6));
        Movement move = new Movement(Move.DESCEND_E, src, dst, 40);
        h.runAfterDelay(3, () -> {
            h.assertTrue(move.tick(c, old) == Movement.Status.FAILED, "Verlorener Eimer wurde nicht erkannt");
            h.assertTrue(c.onGround() && c.getY() >= src.getY() - 0.1, "Trotz fehlendem Eimer gefallen");
            h.succeed();
        });
    }

    /**
     * Fehlpfade: ein 1 breiter Steg (nur in Sprungrichtung) hinter 3 Blöcken Lücke über einer Grube, die bei 10 Leben
     * zu tief ist. Zu kurz, links und rechts fiele er tief: verboten. Derselbe Sprung auf eine breite Plattform geht.
     */
    @GameTest(maxTicks = 100, structure = BENCH)
    public void fehlpfadeOhneRueckfallVerboten(GameTestHelper h) {
        pit(h, 7);
        box(h, 1, 1, 3, 5, 7, 9, Blocks.STONE);
        box(h, 9, 1, 6, 14, 7, 6, Blocks.STONE); // Steg
        CompanionEntity c = companion(h, new BlockPos(2, 8, 6));
        c.setHealth(10);
        NavContext ctx = new NavContext(c);
        BlockPos a = h.absolutePos(new BlockPos(5, 8, 6)), b = h.absolutePos(new BlockPos(9, 8, 6));
        JumpSim.Plan narrow = JumpSim.plan(ctx, a.getX(), a.getY(), a.getZ(), b.getX(), b.getY(), b.getZ());
        h.assertTrue(narrow == null, "Sprung auf den Steg erlaubt: " + (narrow == null ? "" : narrow.describe()));
        LOG.info("[Parkour-Bench] Steg verworfen: {}", JumpSim.lastReject);
        box(h, 9, 1, 3, 14, 7, 9, Blocks.STONE); // jetzt breit
        NavContext wide = new NavContext(c);
        JumpSim.Plan ok = JumpSim.plan(wide, a.getX(), a.getY(), a.getZ(), b.getX(), b.getY(), b.getZ());
        h.assertTrue(ok != null && ok.unsafeMisses < 3, "Breite Plattform nicht erlaubt: " + (ok == null ? JumpSim.lastReject : ok.describe()));
        h.succeed();
    }

    /** Niedrige Decke über der Lücke: Kopf stieße an – kein Sprung (die Simulation verwirft die Flugbahn). */
    @GameTest(maxTicks = 200, structure = BENCH)
    public void keinSprungUnterNiedrigerDecke(GameTestHelper h) {
        int gx = gapCourse(h, 2, 0, 3);
        box(h, 6, 6, 3, 7, 6, 9, Blocks.BARRIER);
        CompanionEntity c = companion(h, new BlockPos(2, 4, 6));
        NavDriver d = new NavDriver(c, new Goal.Block(h.absolutePos(new BlockPos(gx, 4, 6))));
        boolean[] jumped = {false};
        h.onEachTick(() -> {
            d.tick();
            PathExecutor ex = d.executor();
            if (ex != null && ex.current() != null && ex.current().move.kind == Move.Kind.PARKOUR
                    && ex.current().phase != Movement.Phase.APPROACH) jumped[0] = true;
        });
        h.runAfterDelay(150, () -> {
            h.assertTrue(c.isAlive() && !jumped[0], "Unter der Decke gesprungen: " + d.describe());
            h.succeed();
        });
    }

    /** Ziel-Plattform verschwindet mitten im Sprung: er fällt (3 tief, harmlos), meldet die Landung daneben, lebt. */
    @GameTest(maxTicks = 400, structure = BENCH)
    public void weltaenderungImSprung(GameTestHelper h) {
        int gx = gapCourse(h, 2, 0, 3);
        CompanionEntity c = companion(h, new BlockPos(2, 4, 6));
        NavDriver d = new NavDriver(c, new Goal.Block(h.absolutePos(new BlockPos(gx, 4, 6))));
        boolean[] removed = {false};
        h.onEachTick(() -> {
            d.tick();
            PathExecutor ex = d.executor();
            if (!removed[0] && ex != null && ex.current() != null && ex.current().phase == Movement.Phase.AIRBORNE) {
                box(h, 8, 1, 3, 12, 3, 9, Blocks.AIR);
                removed[0] = true;
            }
        });
        h.succeedWhen(() -> {
            h.assertTrue(removed[0], "nie gesprungen: " + d.describe());
            h.assertTrue(c.isAlive() && c.onGround(), "noch in der Luft");
            dev.begleiter.ServerTextGameTests.assertTranslatedContains(h, d.lastFailureText,
                    "Landung daneben", "Missed landing");
        });
    }

    /** Wie oben, aber 7 tief bei 12 Leben (zu viel Schaden): der Wassereimer fängt ihn ab, Eimer wieder voll. */
    @GameTest(maxTicks = 400, structure = BENCH)
    public void eimerRettetFehlsprung(GameTestHelper h) {
        pit(h, 7);
        box(h, 1, 1, 3, 5, 7, 9, Blocks.STONE);
        box(h, 8, 1, 3, 12, 7, 9, Blocks.STONE);
        CompanionEntity c = companion(h, new BlockPos(2, 8, 6));
        c.setHealth(12);
        c.giveToInventory(new ItemStack(Items.WATER_BUCKET));
        NavDriver d = new NavDriver(c, new Goal.Block(h.absolutePos(new BlockPos(10, 8, 6))));
        boolean[] removed = {false};
        h.onEachTick(() -> {
            d.tick();
            PathExecutor ex = d.executor();
            if (!removed[0] && ex != null && ex.current() != null && ex.current().phase == Movement.Phase.AIRBORNE) {
                box(h, 8, 1, 3, 12, 7, 9, Blocks.AIR);
                removed[0] = true;
            }
        });
        h.succeedWhen(() -> {
            h.assertTrue(removed[0], "nie gesprungen: " + d.describe());
            h.assertTrue(c.isAlive() && (c.onGround() || c.isInWater()) && c.getY() < h.absolutePos(BlockPos.ZERO).getY() + 3, "noch nicht unten");
            h.assertTrue(c.getHealth() >= 12, "Schaden genommen: " + c.getHealth());
            h.assertTrue(c.findItem(Items.WATER_BUCKET) >= 0, "Eimer nicht wieder gefüllt");
        });
    }
}
