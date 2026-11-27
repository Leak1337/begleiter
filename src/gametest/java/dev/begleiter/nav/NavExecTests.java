package dev.begleiter.nav;

import net.fabricmc.fabric.api.gametest.v1.GameTest;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;

import dev.begleiter.CompanionEntity;
import dev.begleiter.CompanionMod;
import dev.begleiter.ServerTextGameTests;

/** Wegfindung: Ausführung der Bewegungen und des Weges (Spulen, vom Weg, Vorausprüfung). */
public class NavExecTests {

    private static void floor(GameTestHelper h) {
        for (int x = 0; x < 8; x++) for (int z = 0; z < 8; z++) h.setBlock(x, 0, z, Blocks.STONE);
    }

    private static void box(GameTestHelper h, int x0, int y0, int z0, int x1, int y1, int z1, Block b) {
        for (int x = x0; x <= x1; x++)
            for (int y = y0; y <= y1; y++)
                for (int z = z0; z <= z1; z++) h.setBlock(x, y, z, b);
    }

    private static void ring(GameTestHelper h, int fromY) {
        for (int i = 0; i <= 7; i++)
            for (int y = fromY; y <= 7; y++) {
                h.setBlock(i, y, 0, Blocks.BARRIER);
                h.setBlock(i, y, 7, Blocks.BARRIER);
                h.setBlock(0, y, i, Blocks.BARRIER);
                h.setBlock(7, y, i, Blocks.BARRIER);
            }
    }

    private static CompanionEntity companion(GameTestHelper h, BlockPos rel) {
        CompanionEntity c = h.spawn(CompanionMod.COMPANION, rel);
        c.setPersistenceRequired();
        c.stay();
        c.getInventory().clearContent();
        c.setItemSlot(EquipmentSlot.MAINHAND, ItemStack.EMPTY);
        return c;
    }

    private static void driveTo(GameTestHelper h, CompanionEntity c, BlockPos goalRel, Runnable extraCheck) {
        BlockPos goal = h.absolutePos(goalRel);
        NavDriver d = new NavDriver(c, new Goal.Block(goal));
        h.succeedWhen(() -> {
            d.tick();
            h.assertTrue(d.arrived, "Nicht am Ziel (bei " + c.blockPosition().toShortString() + ", Ziel " + goal.toShortString()
                    + ", " + d.describe() + ")");
            if (extraCheck != null) extraCheck.run();
        });
    }

    @GameTest(maxTicks = 600)
    public void bautSaeuleAufsPlateau(GameTestHelper h) {
        floor(h);
        ring(h, 1);
        box(h, 5, 1, 1, 6, 3, 6, Blocks.BARRIER); // unzerstörbar: nur per Säule/Stufen hoch
        CompanionEntity c = companion(h, new BlockPos(2, 1, 3));
        c.giveToInventory(new ItemStack(Items.DIRT, 8));
        driveTo(h, c, new BlockPos(5, 4, 3), () -> h.assertTrue(c.scaffoldCount() < 8, "Keine Blöcke verbaut"));
    }

    @GameTest(maxTicks = 400)
    public void springtTiefRunter(GameTestHelper h) {
        floor(h);
        ring(h, 1);
        box(h, 1, 1, 1, 3, 5, 6, Blocks.BARRIER); // Podest, oben y=6, 5 tief bis zum Boden
        CompanionEntity c = companion(h, new BlockPos(2, 6, 3));
        c.setHealth(c.getMaxHealth());
        driveTo(h, c, new BlockPos(5, 1, 3), null);
    }

    @GameTest(maxTicks = 600)
    public void plantNeuWennWegVersperrt(GameTestHelper h) {
        floor(h);
        ring(h, 1);
        CompanionEntity c = companion(h, new BlockPos(1, 1, 1));
        BlockPos goal = h.absolutePos(new BlockPos(6, 1, 6));
        NavDriver d = new NavDriver(c, new Goal.Block(goal));
        boolean[] built = {false};
        h.succeedWhen(() -> {
            d.tick();
            // sobald ein Weg läuft: Mauer quer über die Mitte (mit Lücke bei z=6)
            if (!built[0] && d.executor() != null) {
                built[0] = true;
                box(h, 4, 1, 1, 4, 2, 5, Blocks.BARRIER);
            }
            h.assertTrue(d.arrived, "Nicht am Ziel: " + d.describe());
            h.assertTrue(d.searches >= 2, "Nicht neu geplant: " + d.describe());
        });
    }

    @GameTest(maxTicks = 100)
    public void spultVorWennWeiterGeschoben(GameTestHelper h) {
        floor(h);
        ring(h, 1);
        CompanionEntity c = companion(h, new BlockPos(1, 1, 3));
        NavContext ctx = new NavContext(c);
        ctx.allowBreak = false;
        PathSearch s = new PathSearch(ctx, h.absolutePos(new BlockPos(1, 1, 3)), new Goal.Block(h.absolutePos(new BlockPos(6, 1, 3))));
        s.step(1_000_000_000L);
        PathExecutor ex = new PathExecutor(s.path(), ctx);
        ex.tick(c);
        BlockPos ahead = h.absolutePos(new BlockPos(4, 1, 3));
        c.teleportTo(ahead.getX() + 0.5, ahead.getY(), ahead.getZ() + 0.5);
        h.succeedWhen(() -> {
            if (ex.status() == PathExecutor.Status.RUNNING && ex.index() < 3) ex.tick(c);
            h.assertTrue(ex.index() >= 3 && c.getX() > ahead.getX() - 0.5, "Nicht vorgespult: Schritt " + ex.index() + ", " + ex.status());
        });
    }

    @GameTest(maxTicks = 60)
    public void stopptBeiLavaNachBewegungsbeginn(GameTestHelper h) {
        floor(h);
        ring(h, 1);
        CompanionEntity c = companion(h, new BlockPos(1, 1, 3));
        c.setOnGround(true);
        NavContext ctx = new NavContext(c);
        PathSearch s = new PathSearch(ctx, h.absolutePos(new BlockPos(1, 1, 3)),
                new Goal.Block(h.absolutePos(new BlockPos(6, 1, 3))));
        s.step(1_000_000_000L);
        h.assertTrue(s.status() == PathSearch.Status.FOUND, "Ausgangsweg fehlt");
        PathExecutor ex = new PathExecutor(s.path(), ctx);
        h.assertTrue(ex.tick(c) == PathExecutor.Status.RUNNING && ex.current() != null, "Bewegung nicht gestartet");
        BlockPos dest = ex.current().dest.subtract(h.absolutePos(BlockPos.ZERO));
        h.setBlock(dest, Blocks.LAVA);
        h.assertTrue(ex.tick(c) == PathExecutor.Status.FAILED, "Laufende Bewegung läuft weiter in neue Lava");
        ServerTextGameTests.assertTranslatedContains(h, ex.failureComponent(), "nicht mehr sicher", "no longer safe");
        h.succeed();
    }

    @GameTest(maxTicks = 60)
    public void erkenntVerbautenWeg(GameTestHelper h) {
        floor(h);
        ring(h, 1);
        CompanionEntity c = companion(h, new BlockPos(1, 1, 3));
        NavContext ctx = new NavContext(c);
        ctx.allowBreak = false;
        PathSearch s = new PathSearch(ctx, h.absolutePos(new BlockPos(1, 1, 3)), new Goal.Block(h.absolutePos(new BlockPos(6, 1, 3))));
        s.step(1_000_000_000L);
        box(h, 3, 1, 1, 3, 2, 6, Blocks.BARRIER);
        PathExecutor ex = new PathExecutor(s.path(), ctx);
        h.assertTrue(ex.tick(c) == PathExecutor.Status.FAILED, "Verbauten Weg nicht erkannt: " + ex.status());
        ServerTextGameTests.assertTranslatedContains(h, ex.failureComponent(), "versperrt", "Path blocked");
        h.succeed();
    }

    // ------------------------------------------------------------------ Türen und Tore

    /** Mauer aus Brettern (nicht natürlich, nicht abbaubar) mit einer Holztür bzw. einem Zauntor. */
    private static void wallWith(GameTestHelper h, net.minecraft.world.level.block.state.BlockState passage, boolean twoHigh) {
        floor(h);
        ring(h, 1);
        box(h, 4, 1, 1, 4, 3, 6, Blocks.OAK_PLANKS);
        h.setBlock(4, 1, 3, Blocks.AIR);
        h.setBlock(4, 2, 3, Blocks.AIR);
        if (twoHigh) {
            h.setBlock(4, 1, 3, passage.setValue(net.minecraft.world.level.block.DoorBlock.HALF,
                    net.minecraft.world.level.block.state.properties.DoubleBlockHalf.LOWER));
            h.setBlock(4, 2, 3, passage.setValue(net.minecraft.world.level.block.DoorBlock.HALF,
                    net.minecraft.world.level.block.state.properties.DoubleBlockHalf.UPPER));
        } else h.setBlock(4, 1, 3, passage);
    }

    @GameTest(maxTicks = 300)
    public void oeffnetHolztuer(GameTestHelper h) {
        wallWith(h, Blocks.OAK_DOOR.defaultBlockState().setValue(net.minecraft.world.level.block.DoorBlock.FACING,
                net.minecraft.core.Direction.EAST), true);
        CompanionEntity c = companion(h, new BlockPos(1, 1, 3));
        driveTo(h, c, new BlockPos(6, 1, 3), () -> h.assertTrue(h.getBlockState(new BlockPos(4, 1, 3)).is(Blocks.OAK_DOOR),
                "Tür abgebaut statt geöffnet"));
    }

    /** Beobachtet: Haus mit Birkentür, vor der Türschwelle geht es 2 Blöcke in einen Krater hinab. */
    @GameTest(maxTicks = 400)
    public void gehtDurchBirkentuerUndSpringtInDenKrater(GameTestHelper h) {
        floor(h);
        ring(h, 1);
        box(h, 1, 1, 1, 5, 2, 6, Blocks.DIRT);          // erhöhter Boden für Haus und Schwelle (Oberkante y=2)
        box(h, 4, 3, 1, 4, 5, 6, Blocks.OAK_PLANKS);    // Hauswand x=4
        var door = Blocks.BIRCH_DOOR.defaultBlockState().setValue(net.minecraft.world.level.block.DoorBlock.FACING,
                net.minecraft.core.Direction.EAST);
        h.setBlock(4, 3, 3, door.setValue(net.minecraft.world.level.block.DoorBlock.HALF,
                net.minecraft.world.level.block.state.properties.DoubleBlockHalf.LOWER));
        h.setBlock(4, 4, 3, door.setValue(net.minecraft.world.level.block.DoorBlock.HALF,
                net.minecraft.world.level.block.state.properties.DoubleBlockHalf.UPPER));
        CompanionEntity c = companion(h, new BlockPos(2, 3, 3));
        driveTo(h, c, new BlockPos(6, 1, 3), null);     // Kraterboden: x=6 liegt 2 tiefer als die Schwelle x=5
    }

    @GameTest(maxTicks = 300)
    public void oeffnetZauntor(GameTestHelper h) {
        wallWith(h, Blocks.OAK_FENCE_GATE.defaultBlockState().setValue(net.minecraft.world.level.block.FenceGateBlock.FACING,
                net.minecraft.core.Direction.EAST), false);
        CompanionEntity c = companion(h, new BlockPos(1, 1, 3));
        driveTo(h, c, new BlockPos(6, 1, 3), null);
    }

    @GameTest(maxTicks = 400)
    public void klettertLeiterHoch(GameTestHelper h) {
        floor(h);
        ring(h, 1);
        box(h, 5, 1, 1, 6, 4, 6, Blocks.STONE); // ohne Spitzhacke und Blöcke: nur über die Leiter
        for (int y = 1; y <= 4; y++)
            h.setBlock(4, y, 3, Blocks.LADDER.defaultBlockState().setValue(net.minecraft.world.level.block.LadderBlock.FACING,
                    net.minecraft.core.Direction.WEST));
        CompanionEntity c = companion(h, new BlockPos(1, 1, 3));
        driveTo(h, c, new BlockPos(5, 5, 3), null);
    }

    @GameTest(maxTicks = 400)
    public void schwimmtDurchsWasser(GameTestHelper h) {
        floor(h);
        ring(h, 1);
        box(h, 1, 1, 1, 1, 1, 6, Blocks.STONE);
        box(h, 6, 1, 1, 6, 1, 6, Blocks.STONE);
        box(h, 2, 1, 1, 5, 1, 6, Blocks.WATER); // 4 breit: zu weit zum Springen, keine Blöcke für eine Brücke
        CompanionEntity c = companion(h, new BlockPos(1, 2, 3));
        driveTo(h, c, new BlockPos(6, 2, 3), null);
    }

    @GameTest
    public void bedrohungMachtWegTeurer(GameTestHelper h) {
        floor(h);
        CompanionEntity c = companion(h, new BlockPos(1, 1, 1));
        var creeper = h.spawn(net.minecraft.world.entity.EntityTypes.CREEPER, new BlockPos(5, 1, 5));
        creeper.setNoAi(true);
        NavContext ctx = new NavContext(c);
        BlockPos at = creeper.blockPosition();
        h.assertTrue(ctx.favor(at.getX(), at.getY(), at.getZ()) >= 3, "Neben dem Creeper nicht teurer: " + ctx.favor(at.getX(), at.getY(), at.getZ()));
        h.assertValueEqual(ctx.favor(at.getX() + 12, at.getY(), at.getZ()), 1.0, "Weit weg teurer");
        h.succeed();
    }

    @GameTest(maxTicks = 300)
    public void springtMitWassereimerTief(GameTestHelper h) {
        floor(h);
        ring(h, 1);
        box(h, 3, 1, 3, 3, 5, 3, Blocks.BARRIER); // Säule, oben y=6, 5 tief
        CompanionEntity c = companion(h, new BlockPos(3, 6, 3));
        c.setHealth(9); // ohne Eimer höchstens 4 tief
        c.giveToInventory(new ItemStack(Items.WATER_BUCKET));
        BlockPos land = h.absolutePos(new BlockPos(4, 1, 3));
        driveTo(h, c, new BlockPos(4, 1, 3), () -> {
            h.assertTrue(c.getHealth() >= 9, "Fallschaden trotz Eimer: " + c.getHealth());
            h.assertTrue(c.findItem(Items.WATER_BUCKET) >= 0, "Wasser nicht wieder aufgenommen");
            h.assertTrue(h.getLevel().getFluidState(land).isEmpty(), "Wasser liegen gelassen");
        });
    }

    @GameTest
    public void trampelpfadWirdGuenstiger(GameTestHelper h) {
        floor(h);
        CompanionEntity c = companion(h, new BlockPos(1, 1, 1));
        long seed = h.getLevel().getSeed();
        BlockPos p = h.absolutePos(new BlockPos(3, 1, 3)), q = h.absolutePos(new BlockPos(5, 1, 3));
        for (int i = 0; i < RouteMemory.TRAMPLED; i++) c.routes.noteStep(p, seed);
        c.routes.noteStep(q, seed);
        NavContext ctx = new NavContext(c);
        h.assertValueEqual(ctx.favor(p.getX(), p.getY(), p.getZ()), RouteMemory.FACTOR, "Trampelpfad nicht günstiger");
        h.assertValueEqual(ctx.favor(q.getX(), q.getY(), q.getZ()), 1.0, "Einmal gelaufen schon günstiger");
        // gespeichert im Kern (saveMemory) und wieder geladen
        CompanionEntity copy = companion(h, new BlockPos(6, 1, 6));
        copy.loadMemory(c.saveMemory());
        h.assertValueEqual(copy.routes.count(p), RouteMemory.TRAMPLED, "Nicht gespeichert");
        // andere Welt: vergessen
        copy.routes.noteStep(q, seed + 1);
        h.assertValueEqual(copy.routes.count(p), 0, "Aus anderer Welt übernommen");
        // gleicher Seed, andere Dimension: eigene Trampelpfade; die Oberwelt behält den reinen Seed
        h.assertValueEqual(RouteMemory.worldKey(h.getLevel()), seed, "Oberwelt-Schlüssel geändert (alte Spielstände)");
        var nether = h.getLevel().getServer().getLevel(net.minecraft.world.level.Level.NETHER);
        h.assertTrue(nether == null || RouteMemory.worldKey(nether) != seed, "Nether teilt die Trampelpfade der Oberwelt");
        h.succeed();
    }

    @GameTest
    public void netherportalNurAlsZiel(GameTestHelper h) {
        floor(h);
        // Rahmen 4 breit, 5 hoch bei z=4, innen 2x3 Portal
        for (int x = 2; x <= 5; x++) for (int y = 1; y <= 5; y++)
            if (x == 2 || x == 5 || y == 1 || y == 5) h.setBlock(x, y, 4, Blocks.OBSIDIAN);
        for (int x = 3; x <= 4; x++) for (int y = 2; y <= 4; y++)
            h.setBlock(x, y, 4, Blocks.NETHER_PORTAL.defaultBlockState()
                    .setValue(net.minecraft.world.level.block.NetherPortalBlock.AXIS, net.minecraft.core.Direction.Axis.X));
        CompanionEntity c = companion(h, new BlockPos(3, 1, 1));
        BlockPos portal = h.absolutePos(new BlockPos(3, 2, 4));
        h.assertTrue(h.getLevel().getBlockState(portal).is(Blocks.NETHER_PORTAL), "Portal nicht stehen geblieben");
        NavContext ctx = new NavContext(c);
        ctx.allowPortal = true;
        PathSearch in = new PathSearch(ctx, h.absolutePos(new BlockPos(3, 1, 1)), new Goal.Block(portal));
        in.step(1_000_000_000L);
        h.assertTrue(in.status() == PathSearch.Status.FOUND, "Nicht ins Portal: " + in.status());
        // ohne Erlaubnis: kein Weg hinein, und hinters Portal nicht hindurch
        NavContext no = new NavContext(c);
        PathSearch s2 = new PathSearch(no, h.absolutePos(new BlockPos(3, 1, 1)), new Goal.Block(portal));
        s2.step(1_000_000_000L);
        h.assertTrue(s2.status() != PathSearch.Status.FOUND, "Ins Portal ohne Erlaubnis");
        PathSearch behind = new PathSearch(no, h.absolutePos(new BlockPos(3, 1, 1)), new Goal.Block(h.absolutePos(new BlockPos(3, 1, 6))));
        behind.step(1_000_000_000L);
        h.assertTrue(behind.status() == PathSearch.Status.FOUND, "Kein Weg ums Portal herum");
        for (BlockPos p : behind.path().positions)
            h.assertTrue(!h.getLevel().getBlockState(p).is(Blocks.NETHER_PORTAL), "Weg führt durchs Portal: " + p.toShortString());
        h.succeed();
    }

    @GameTest(maxTicks = 100)
    public void brueckeUeberFackel(GameTestHelper h) {
        for (int x = 0; x < 8; x++) for (int z = 0; z < 8; z++) if (x != 3) h.setBlock(x, 2, z, Blocks.STONE);
        h.setBlock(3, 2, 3, Blocks.WALL_TORCH.defaultBlockState()
                .setValue(net.minecraft.world.level.block.WallTorchBlock.FACING, net.minecraft.core.Direction.EAST));
        CompanionEntity c = companion(h, new BlockPos(2, 3, 3));
        c.giveToInventory(new ItemStack(Items.DIRT, 4));
        NavContext ctx = new NavContext(c);
        Movement m = new Movement(Move.TRAVERSE_E, h.absolutePos(new BlockPos(2, 3, 3)), h.absolutePos(new BlockPos(3, 3, 3)), 30);
        h.succeedWhen(() -> {
            Movement.Status st = m.tick(c, ctx);
            h.assertTrue(st != Movement.Status.FAILED, "Gescheitert: " + m.failReason);
            h.assertTrue(st == Movement.Status.SUCCESS, "Noch nicht drüben");
            h.assertBlockPresent(Blocks.DIRT, new BlockPos(3, 2, 3));
        });
    }

    /** Seelensand ist nur 7/8 hoch: er steht 0,125 unter der Blockgrenze und muss trotzdem als angekommen gelten. */
    @GameTest(maxTicks = 100)
    public void steigtAufSeelensand(GameTestHelper h) {
        floor(h);
        ring(h, 1);
        h.setBlock(3, 1, 3, Blocks.SOUL_SAND);
        CompanionEntity c = companion(h, new BlockPos(2, 1, 3));
        NavContext ctx = new NavContext(c);
        Movement m = new Movement(Move.ASCEND_E, h.absolutePos(new BlockPos(2, 1, 3)), h.absolutePos(new BlockPos(3, 2, 3)), 10);
        h.succeedWhen(() -> {
            Movement.Status st = m.tick(c, ctx);
            h.assertTrue(st != Movement.Status.FAILED, "Gescheitert: " + m.failReason + " bei y=" + c.getY());
            h.assertTrue(st == Movement.Status.SUCCESS, "Noch nicht oben (y=" + c.getY() + ")");
        });
    }

    /** Wasserfall als einziger Weg nach oben: ohne Blöcke und ohne Abbauen (Barrieren) hochschwimmen. */
    @GameTest(maxTicks = 600)
    public void schwimmtWasserfallHoch(GameTestHelper h) {
        floor(h);
        ring(h, 1);
        box(h, 4, 1, 1, 6, 4, 6, Blocks.BARRIER); // Plateau, oben y=5
        box(h, 3, 1, 1, 3, 4, 2, Blocks.BARRIER); // Schacht-Wände um x=3, z=3
        box(h, 3, 1, 4, 3, 4, 6, Blocks.BARRIER);
        h.setBlock(3, 5, 3, Blocks.WATER); // Quelle oben: fließt als Wasserfall durch x=3, z=3 hinunter
        CompanionEntity c = companion(h, new BlockPos(1, 1, 3));
        driveTo(h, c, new BlockPos(5, 5, 3), null);
    }

    /** Leiter oben zugebaut: an der Leiter ist Abbrechen sicher, also greift das normale Zeitlimit (kein ewiges Hängen). */
    @GameTest(maxTicks = 200)
    public void leiterObenZuGibtNachZeitlimitAuf(GameTestHelper h) {
        floor(h);
        ring(h, 1);
        box(h, 4, 1, 3, 4, 5, 3, Blocks.STONE);
        for (int y = 1; y <= 3; y++)
            h.setBlock(3, y, 3, Blocks.LADDER.defaultBlockState()
                    .setValue(net.minecraft.world.level.block.LadderBlock.FACING, net.minecraft.core.Direction.WEST));
        h.setBlock(3, 4, 3, Blocks.BARRIER);
        CompanionEntity c = companion(h, new BlockPos(3, 2, 3));
        NavContext ctx = new NavContext(c);
        Movement m = new Movement(Move.CLIMB_UP, h.absolutePos(new BlockPos(3, 2, 3)), h.absolutePos(new BlockPos(3, 3, 3)), 5);
        int[] t = {0};
        boolean[] grounded = {false};
        Movement.Status[] st = {Movement.Status.RUNNING};
        h.succeedWhen(() -> {
            if (st[0] == Movement.Status.RUNNING) { t[0]++; st[0] = m.tick(c, ctx); grounded[0] = c.onGround(); }
            h.assertTrue(st[0] != Movement.Status.SUCCESS, "Durch die Barriere geklettert");
            h.assertTrue(st[0] == Movement.Status.FAILED, "Hängt noch an der Leiter (Tick " + t[0] + ")");
            h.assertTrue(!grounded[0] && c.onClimbable(), "Test ungültig: beim Abbruch nicht an der Leiter (y=" + c.getY() + ")");
            ServerTextGameTests.assertTranslatedContains(h, m.failureComponent(), "Zeitlimit", "Time limit");
            h.assertValueEqual(((net.minecraft.network.chat.contents.TranslatableContents) m.failureComponent().getContents()).getKey(),
                    "server.begleiter.nav.failure.timeout", "Falscher Zeitlimit-Typ");
        });
    }

    /** Schwebt er ohne sicheren Abbruchzustand fest (weder Boden, Wasser noch Leiter), beendet die harte Grenze die Bewegung. */
    @GameTest(maxTicks = 400)
    public void harteZeitgrenzeOhneSicherenZustand(GameTestHelper h) {
        floor(h);
        ring(h, 1);
        CompanionEntity c = companion(h, new BlockPos(2, 1, 3));
        c.setNoGravity(true);
        BlockPos air = h.absolutePos(new BlockPos(2, 4, 3));
        c.teleportTo(air.getX() + 0.5, air.getY(), air.getZ() + 0.5);
        NavContext ctx = new NavContext(c);
        Movement m = new Movement(Move.TRAVERSE_E, h.absolutePos(new BlockPos(2, 1, 3)), h.absolutePos(new BlockPos(3, 1, 3)), 5);
        int[] t = {0};
        Movement.Status[] st = {Movement.Status.RUNNING};
        h.succeedWhen(() -> {
            if (st[0] == Movement.Status.RUNNING) {
                t[0]++;
                c.setDeltaMovement(0, 0, 0);
                c.teleportTo(air.getX() + 0.5, air.getY(), air.getZ() + 0.5);
                st[0] = m.tick(c, ctx);
            }
            h.assertTrue(st[0] == Movement.Status.FAILED, "Hängt noch in der Luft (Tick " + t[0] + ")");
            ServerTextGameTests.assertTranslatedContains(h, m.failureComponent(), "Zeitlimit (hart)", "Time limit (hard)");
        });
    }

    @GameTest(maxTicks = 100)
    public void verworfenWennWeitVomWeg(GameTestHelper h) {
        floor(h);
        CompanionEntity c = companion(h, new BlockPos(1, 1, 1));
        NavContext ctx = new NavContext(c);
        PathSearch s = new PathSearch(ctx, h.absolutePos(new BlockPos(1, 1, 1)), new Goal.Block(h.absolutePos(new BlockPos(6, 1, 1))));
        s.step(1_000_000_000L);
        PathExecutor ex = new PathExecutor(s.path(), ctx);
        ex.tick(c);
        BlockPos away = h.absolutePos(new BlockPos(1, 1, 6));
        c.teleportTo(away.getX() + 0.5, away.getY(), away.getZ() + 0.5);
        int[] t = {0};
        h.succeedWhen(() -> {
            if (++t[0] >= 2 && ex.status() == PathExecutor.Status.RUNNING) ex.tick(c);
            h.assertTrue(ex.status() == PathExecutor.Status.FAILED, "Nicht verworfen: " + ex.status());
        });
    }
}
