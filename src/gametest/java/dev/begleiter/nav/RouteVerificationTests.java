package dev.begleiter.nav;

import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;
import dev.begleiter.CompanionEntity;
import dev.begleiter.CompanionMemory;
import dev.begleiter.CompanionMod;
import net.fabricmc.fabric.api.gametest.v1.GameTest;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.world.level.block.Blocks;

public class RouteVerificationTests {
    private static final String BENCH = "begleiter-gametest:bench";

    private static void arena(GameTestHelper h) {
        for (int x = 0; x < 48; x++) for (int z = 0; z < 12; z++) {
            h.setBlock(x, 0, z, Blocks.STONE);
            if (x == 0 || x == 47 || z == 0 || z == 11)
                for (int y = 1; y < 14; y++) h.setBlock(x, y, z, Blocks.BARRIER);
        }
    }

    private static CompanionEntity companion(GameTestHelper h) {
        var c = h.spawn(CompanionMod.COMPANION, new BlockPos(4, 1, 6));
        c.stay(); c.setNoAi(true); c.setOnGround(true); c.getInventory().clearContent();
        return c;
    }

    private record Fixture(CompanionEntity mob, NavContext out, NavContext back, Path outward, Path returning) {}

    private static Path search(GameTestHelper h, NavContext ctx, BlockPos from, BlockPos to) {
        var s = new PathSearch(ctx, from, new Goal.Block(to));
        for (int i = 0; i < 100 && !s.done(); i++) s.step(50_000_000L);
        h.assertTrue(s.status() == PathSearch.Status.FOUND, "Testweg fehlt");
        return s.path();
    }

    private static Fixture fixture(GameTestHelper h) {
        arena(h); var c = companion(h); var out = new NavContext(c, true); var back = new NavContext(c, true);
        out.allowBreak = out.allowPlace = out.allowParkour = false;
        back.allowBreak = back.allowPlace = back.allowParkour = false;
        BlockPos start = c.blockPosition(), goal = h.absolutePos(new BlockPos(20, 1, 6));
        return new Fixture(c, out, back, search(h, out, start, goal), search(h, back, goal, start));
    }

    private static RouteVerification verifier(Fixture f, AtomicLong time) {
        return new RouteVerification(f.outward, f.out, f.returning, f.back, time::getAndIncrement);
    }

    private static void finish(RouteVerification check, AtomicLong time) {
        for (int i = 0; i < 1000 && check.status() == RouteVerification.Status.RUNNING; i++) check.step(time.get() + 8);
    }

    @GameTest public void schnappschussBleibtFestUndAbschlussSiehtAenderung(GameTestHelper h) {
        BlockPos p = h.absolutePos(new BlockPos(2, 1, 2)); h.setBlock(2, 1, 2, Blocks.DIRT);
        var cache = new BlockCache(h.getLevel()); cache.beginVerificationSnapshot();
        h.assertTrue(cache.get(p.getX(), p.getY(), p.getZ()).is(Blocks.DIRT), "Ausgangszustand fehlt");
        h.setBlock(2, 1, 2, Blocks.LAVA);
        h.assertTrue(cache.get(p.getX(), p.getY(), p.getZ()).is(Blocks.DIRT), "Schnappschuss liest gemischte Weltstände");
        h.assertTrue(!cache.verificationUnchanged(), "Abschluss übersieht neue Lava");
        cache.endVerificationSnapshot();
        h.assertTrue(cache.get(p.getX(), p.getY(), p.getZ()).is(Blocks.LAVA), "Livezugriff bleibt nach Abschluss eingefroren");
        h.succeed();
    }

    @GameTest public void virtuelleZelleVerdecktKeineRealeAenderung(GameTestHelper h) {
        BlockPos p = h.absolutePos(new BlockPos(2, 1, 2)); h.setBlock(2, 1, 2, Blocks.STONE);
        var cache = new BlockCache(h.getLevel(), Map.of(p.asLong(), Blocks.AIR.defaultBlockState()));
        cache.beginVerificationSnapshot();
        h.assertTrue(cache.get(p.getX(), p.getY(), p.getZ()).isAir(), "Vorschau fehlt");
        h.setBlock(2, 1, 2, Blocks.DIRT);
        h.assertTrue(!cache.verificationUnchanged(), "Virtuelle Luft verdeckt reale Änderung");
        cache.endVerificationSnapshot(); h.succeed();
    }

    @GameTest public void begrenzterSchnappschussLaedtKeineChunks(GameTestHelper h) {
        int x = 29_000_000, z = 29_000_000;
        var cache = new BlockCache(h.getLevel()); cache.beginVerificationSnapshot();
        for (int i = 0; i <= BlockCache.MAX_VERIFICATION_CELLS; i++)
            h.assertTrue(cache.get(x + i, 64, z) == null, "Unbekannter Chunk als Luft behandelt");
        h.assertTrue(cache.verificationOverflowed() && !cache.verificationUnchanged(), "Überfüllter Schnappschuss wird freigegeben");
        cache.endVerificationSnapshot();
        h.assertTrue(h.getLevel().getChunkSource().getChunkNow(x >> 4, z >> 4) == null, "Prüfung lädt Chunk nach");
        h.succeed();
    }

    @GameTest(structure = BENCH)
    public void aufgeteiltePruefungEntsprichtFrischerGesamtpruefung(GameTestHelper h) {
        var f = fixture(h);
        h.assertTrue(f.outward.verify(f.out).complete && f.returning.verify(f.back).complete, "Referenzprüfung scheitert");
        var time = new AtomicLong(); var check = verifier(f, time);
        check.step(8);
        h.assertTrue(check.status() == RouteVerification.Status.RUNNING && check.steps() > 0
                && check.steps() < f.outward.length() + f.returning.length(), "Prüfung nicht portioniert");
        finish(check, time);
        h.assertTrue(check.status() == RouteVerification.Status.COMPLETE, "Fortsetzung: " + check.reason());
        h.assertValueEqual(check.steps(), f.outward.length() + f.returning.length(), "Schritt doppelt oder ausgelassen");
        h.assertTrue(check.returnDamage() == 0 && f.outward.verify(f.out).complete && f.returning.verify(f.back).complete,
                "Abschluss verändert Wege oder Cachezustand");
        h.succeed();
    }

    @GameTest(structure = BENCH)
    public void aenderungImSchonGeprueftenAbschnittVerhindertFreigabe(GameTestHelper h) throws Exception {
        var f = fixture(h); var time = new AtomicLong(); var check = verifier(f, time);
        check.step(8); h.assertTrue(check.steps() > 0, "Kein Abschnitt geprüft");
        BlockPos p = f.outward.positions.get(1).below();
        h.getLevel().setBlock(p, Blocks.LAVA.defaultBlockState(), 3);
        finish(check, time);
        h.assertTrue(check.status() == RouteVerification.Status.UNPROVEN, "Späte Lava im frühen Abschnitt freigegeben");
        h.assertTrue(f.out.blocks.get(p.getX(), p.getY(), p.getZ()).is(Blocks.LAVA), "Fehlerpfad hält Schnappschuss aktiv");
        dev.begleiter.HandlerTextGameTests.translated(h, check.reasonComponent(), "verändert", "changed");
        h.succeed();
    }

    @GameTest(structure = BENCH)
    public void fremdeAenderungAusserhalbDesWegesBlockiertNicht(GameTestHelper h) {
        var f = fixture(h); var time = new AtomicLong(); var check = verifier(f, time);
        check.step(8); h.setBlock(45, 10, 6, Blocks.DIRT); finish(check, time);
        h.assertTrue(check.status() == RouteVerification.Status.COMPLETE, "Unbeteiligte Zelle blockiert Weg: " + check.reason());
        h.succeed();
    }

    @GameTest(structure = BENCH)
    public void neueErinnerteGefahrVerhindertFreigabe(GameTestHelper h) {
        var f = fixture(h); var time = new AtomicLong(); var check = verifier(f, time);
        check.step(8);
        f.mob.memory.remember(CompanionMemory.Kind.DANGER, "test", h.getLevel(), f.outward.positions.get(2));
        finish(check, time);
        h.assertTrue(check.status() == RouteVerification.Status.UNPROVEN, "Neue Gefahr nach Teilprüfung ignoriert");
        h.succeed();
    }

    @GameTest(structure = BENCH)
    public void weltgrenzeWirdZumAbschlussFrischGeprueft(GameTestHelper h) {
        var f = fixture(h); var time = new AtomicLong(); var check = verifier(f, time); check.step(8);
        var border = h.getLevel().getWorldBorder(); double x = border.getCenterX(), z = border.getCenterZ(), size = border.getSize();
        try {
            border.setCenter(f.mob.getX(), f.mob.getZ()); border.setSize(8); finish(check, time);
            h.assertTrue(check.status() == RouteVerification.Status.UNPROVEN, "Verkleinerte Grenze übergangen");
        } finally { border.setCenter(x, z); border.setSize(size); }
        h.succeed();
    }

    @GameTest(structure = BENCH)
    public void weiterhinPassendeWeltgrenzeErlaubtAbschluss(GameTestHelper h) {
        var f = fixture(h); var time = new AtomicLong(); var check = verifier(f, time); check.step(8);
        var border = h.getLevel().getWorldBorder(); double x = border.getCenterX(), z = border.getCenterZ(), size = border.getSize();
        try {
            border.setCenter(f.mob.getX(), f.mob.getZ()); border.setSize(200); finish(check, time);
            h.assertTrue(check.status() == RouteVerification.Status.COMPLETE, "Gültige Grenze pauschal abgelehnt: " + check.reason());
        } finally { border.setCenter(x, z); border.setSize(size); }
        h.succeed();
    }

    @GameTest(structure = BENCH)
    public void abbruchUndAusnahmeGebenLiveZugriffFrei(GameTestHelper h) {
        var f = fixture(h); var time = new AtomicLong(); var check = verifier(f, time); check.step(8); check.close();
        h.assertTrue(check.status() == RouteVerification.Status.UNPROVEN, "Abbruch gibt Zertifikat frei");
        BlockPos p = f.outward.positions.get(1).below(); h.getLevel().setBlock(p, Blocks.DIRT.defaultBlockState(), 3);
        h.assertTrue(f.out.blocks.get(p.getX(), p.getY(), p.getZ()).is(Blocks.DIRT), "Abbruch lässt alte Welt aktiv");
        var failingTime = new AtomicLong();
        var failing = new RouteVerification(f.outward, f.out, f.returning, f.back, () -> {
            if (failingTime.getAndIncrement() >= 2) throw new IllegalStateException("Testuhr");
            return 0;
        });
        boolean thrown = false; try { failing.step(8); } catch (IllegalStateException expected) { thrown = true; }
        h.assertTrue(thrown && failing.status() == RouteVerification.Status.UNPROVEN, "Ausnahme wird verschluckt oder gibt frei");
        f.out.blocks.beginVerificationSnapshot(); f.out.blocks.endVerificationSnapshot();
        f.back.blocks.beginVerificationSnapshot(); f.back.blocks.endVerificationSnapshot();
        h.succeed();
    }

    @GameTest(structure = BENCH)
    public void rueckwegSchadenssummeWirdNichtProScheibeZurueckgesetzt(GameTestHelper h) {
        arena(h); var c = companion(h); c.setHealth(10);
        c.getAttribute(net.minecraft.world.entity.ai.attributes.Attributes.SAFE_FALL_DISTANCE).setBaseValue(2);
        h.setBlock(4, 9, 6, Blocks.STONE); h.setBlock(5, 6, 6, Blocks.STONE); h.setBlock(6, 3, 6, Blocks.STONE);
        BlockPos start = h.absolutePos(new BlockPos(4, 10, 6));
        var root = new Node(start.getX(), start.getY(), start.getZ(), 0); root.g = 0;
        Node end = root;
        for (int i = 1; i <= 3; i++) { var n = new Node(root.x + i, root.y - i * 3, root.z, 0); n.prev = end; n.move = Move.DESCEND_E; n.g = end.g + 20; end = n; }
        var ctx = new NavContext(c, true); ctx.allowPlace = ctx.allowBreak = false;
        h.assertTrue(Path.from(end, true).verify(ctx).complete, "Sturz-Teststrecke ist nicht begehbar");
        var time = new AtomicLong(); var check = new RouteVerification(Path.from(root, true), ctx, Path.from(end, true), ctx, time::getAndIncrement);
        finish(check, time);
        h.assertTrue(check.status() == RouteVerification.Status.UNSAFE && check.returnDamage() == 3,
                "Drei sichere Einzelfälle umgehen Schadenssumme: " + check.reason() + "/" + check.returnDamage());
        h.succeed();
    }
}
