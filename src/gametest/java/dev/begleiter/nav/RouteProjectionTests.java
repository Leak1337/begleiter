package dev.begleiter.nav;

import java.util.concurrent.atomic.AtomicLong;
import dev.begleiter.CompanionEntity;
import dev.begleiter.CompanionMod;
import net.fabricmc.fabric.api.gametest.v1.GameTest;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Blocks;

public class RouteProjectionTests {
    private static final String BENCH = "begleiter-gametest:bench";

    private static void arena(GameTestHelper h) {
        for (int x = 0; x < 48; x++) for (int z = 0; z < 12; z++) {
            h.setBlock(x, 0, z, Blocks.STONE);
            if (x == 0 || x == 47 || z == 0 || z == 11)
                for (int y = 1; y < 14; y++) h.setBlock(x, y, z, Blocks.BARRIER);
        }
    }

    private static CompanionEntity companion(GameTestHelper h, BlockPos pos) {
        var c = h.spawn(CompanionMod.COMPANION, pos);
        c.stay(); c.setNoAi(true); c.setOnGround(true);
        c.getInventory().clearContent();
        return c;
    }

    private static NavContext context(CompanionEntity c) {
        var ctx = new NavContext(c, true);
        ctx.allowParkour = false;
        return ctx;
    }

    private static Path path(GameTestHelper h, CompanionEntity c, NavContext ctx, BlockPos target) {
        var search = new PathSearch(ctx, c.blockPosition(), new Goal.Block(h.absolutePos(target)));
        for (int i = 0; i < 100 && !search.done(); i++) search.step(50_000_000L);
        h.assertTrue(search.status() == PathSearch.Status.FOUND, "Testweg fehlt: " + search.status());
        return search.path();
    }

    private static void complete(GameTestHelper h, RouteProjection projection, AtomicLong clock) {
        for (int i = 0; i < 1000 && projection.status() == RouteProjection.Status.RUNNING; i++)
            projection.step(clock.get() + 8);
        h.assertTrue(projection.status() == RouteProjection.Status.COMPLETE, "Vorschau: " + projection.status() + " " + projection.reason());
    }

    private static void same(GameTestHelper h, Path path, RouteProjection a, RouteProjection b) {
        h.assertTrue(a.status() == RouteProjection.Status.COMPLETE && b.status() == RouteProjection.Status.COMPLETE, "Vorschau unvollständig");
        h.assertValueEqual(a.context().throwaway, b.context().throwaway, "Material nach Fortsetzung");
        h.assertTrue(a.context().health == b.context().health && java.util.Arrays.equals(a.damage(), b.damage()), "Schadensbilanz nach Fortsetzung");
        for (BlockPos p : path.positions) for (int dy = -1; dy <= 2; dy++) {
            BlockPos at = p.offset(0, dy, 0);
            h.assertTrue(a.context().blocks.get(at.getX(), at.getY(), at.getZ())
                    == b.context().blocks.get(at.getX(), at.getY(), at.getZ()), "Virtuelle Welt verändert: " + at);
        }
    }

    @GameTest(structure = BENCH)
    public void tunnelVorschauWirdIdentischFortgesetztOhneWeltumbau(GameTestHelper h) {
        arena(h);
        for (int z = 1; z < 11; z++) for (int y = 1; y <= 3; y++) h.setBlock(8, y, z, Blocks.STONE);
        var c = companion(h, new BlockPos(4, 1, 6));
        c.giveToInventory(new ItemStack(Items.DIAMOND_PICKAXE));
        var original = context(c);
        var path = path(h, c, original, new BlockPos(18, 1, 6));
        var full = new RouteProjection(c, original, path);
        full.step(Long.MAX_VALUE);
        var time = new AtomicLong();
        var sliced = new RouteProjection(c, original, path, time::getAndIncrement);
        sliced.step(8);
        h.assertTrue(sliced.steps() > 0 && sliced.steps() < path.length() && sliced.context() == null, "Vorschau wurde nicht unterbrochen");
        complete(h, sliced, time);
        same(h, path, full, sliced);
        h.assertTrue(sliced.worldUnchanged(), "Unveränderte Welt abgelehnt");
        for (int z = 1; z < 11; z++) for (int y = 1; y <= 3; y++)
            h.assertTrue(h.getLevel().getBlockState(h.absolutePos(new BlockPos(8, y, z))).is(Blocks.STONE), "Vorschau baut echte Wand ab");
        h.succeed();
    }

    @GameTest(structure = BENCH)
    public void brueckenMaterialWirdBeiPausenNurEinmalGebucht(GameTestHelper h) {
        arena(h);
        for (int x = 1; x < 47; x++) for (int z = 1; z < 11; z++) h.setBlock(x, 8, z, Blocks.STONE);
        for (int x = 8; x <= 14; x++) for (int z = 1; z < 11; z++) {
            h.setBlock(x, 8, z, Blocks.AIR); h.setBlock(x, 0, z, Blocks.LAVA);
        }
        var c = companion(h, new BlockPos(4, 9, 6));
        c.giveToInventory(new ItemStack(Items.DIRT, 8));
        var original = context(c);
        var path = path(h, c, original, new BlockPos(20, 9, 6));
        var full = new RouteProjection(c, original, path); full.step(Long.MAX_VALUE);
        var time = new AtomicLong();
        var sliced = new RouteProjection(c, original, path, time::getAndIncrement);
        complete(h, sliced, time);
        same(h, path, full, sliced);
        h.assertTrue(sliced.context().throwaway < 8, "Kein Brückenmaterial reserviert");
        h.assertValueEqual(dev.begleiter.CompanionCraft.count(c, "dirt"), 8, "Vorschau verbraucht echte Erde");
        h.assertTrue(h.getLevel().getBlockState(h.absolutePos(new BlockPos(8, 8, 6))).isAir(), "Vorschau setzt echte Brücke");
        h.succeed();
    }

    @GameTest(structure = BENCH)
    public void abgelaufeneScheibeBeginntKeineBewegung(GameTestHelper h) {
        arena(h);
        var c = companion(h, new BlockPos(4, 1, 6)); var original = context(c);
        var route = path(h, c, original, new BlockPos(18, 1, 6));
        var time = new AtomicLong();
        var projection = new RouteProjection(c, original, route, time::getAndIncrement);
        projection.step(0);
        h.assertValueEqual(projection.steps(), 0, "Bewegung trotz abgelaufener Scheibe");
        h.assertTrue(projection.context() == null && projection.status() == RouteProjection.Status.RUNNING, "Unfertige Vorschau freigegeben");
        complete(h, projection, time);
        h.succeed();
    }

    @GameTest(structure = BENCH)
    public void hindernisImNochUngeprueftenAbschnittWirdErkannt(GameTestHelper h) {
        arena(h);
        var c = companion(h, new BlockPos(4, 1, 6)); var original = context(c);
        var route = path(h, c, original, new BlockPos(20, 1, 6));
        var time = new AtomicLong();
        var projection = new RouteProjection(c, original, route, time::getAndIncrement);
        projection.step(8);
        h.assertTrue(projection.steps() < route.length(), "Vorschau bereits vollständig");
        BlockPos next = route.positions.get(projection.steps() + 1);
        h.getLevel().setBlock(next, Blocks.BARRIER.defaultBlockState(), 3);
        for (int i = 0; i < 100 && projection.status() == RouteProjection.Status.RUNNING; i++) projection.step(time.get() + 8);
        h.assertTrue(projection.status() == RouteProjection.Status.UNPROVEN && projection.context() == null, "Neues Hindernis übergangen");
        h.succeed();
    }

    @GameTest(structure = BENCH)
    public void geaenderteAuflageWirdVorFreigabeErkannt(GameTestHelper h) {
        arena(h);
        var c = companion(h, new BlockPos(4, 1, 6)); var original = context(c);
        var route = path(h, c, original, new BlockPos(18, 1, 6));
        var projection = new RouteProjection(c, original, route); projection.step(Long.MAX_VALUE);
        h.assertTrue(projection.worldUnchanged(), "Ausgangsannahmen falsch");
        h.getLevel().setBlock(route.positions.get(1).below(), Blocks.DIRT.defaultBlockState(), 3);
        h.assertTrue(!projection.worldUnchanged(), "Geänderte Auflage bleibt unbemerkt");
        h.succeed();
    }

    @GameTest(structure = BENCH)
    public void neuerRieselblockUeberVirtuellemAbbauWirdErkannt(GameTestHelper h) {
        arena(h);
        h.setBlock(8, 1, 6, Blocks.DIRT); h.setBlock(8, 2, 6, Blocks.DIRT);
        var c = companion(h, new BlockPos(7, 1, 6)); var original = context(c);
        var start = new Node(c.getBlockX(), c.getBlockY(), c.getBlockZ(), 0); start.g = 0;
        var end = new Node(start.x + 1, start.y, start.z, 0);
        end.prev = start; end.move = Move.TRAVERSE_E; end.g = 20;
        var route = Path.from(end, true);
        var projection = new RouteProjection(c, original, route); projection.step(Long.MAX_VALUE);
        h.assertTrue(projection.status() == RouteProjection.Status.COMPLETE && projection.worldUnchanged(), "Abbaufixture ungültig");
        h.setBlock(8, 3, 6, Blocks.GRAVEL);
        h.assertTrue(!projection.worldUnchanged(), "Neuer Rieselblock oberhalb des Abbaus unbemerkt");
        h.succeed();
    }

    private static RoundTripCheck prepared(GameTestHelper h, CompanionEntity c) {
        var check = new RoundTripCheck(c, h.absolutePos(new BlockPos(18, 1, 6)), 1, 12000, 1_000_000_000L);
        for (int i = 0; i < 2000 && check.status() == RoundTripCheck.Status.CHECKING && check.returnContext() == null; i++) check.step();
        h.assertTrue(check.status() == RoundTripCheck.Status.CHECKING && check.returnContext() != null, "Rückwegsuche nicht vorbereitet");
        return check;
    }

    @GameTest(structure = BENCH)
    public void entfernteWasserlandungNutztKeineAltenSuchflags(GameTestHelper h) {
        arena(h);
        h.setBlock(4, 4, 6, Blocks.STONE);
        h.setBlock(5, 1, 6, Blocks.WATER);
        var c = companion(h, new BlockPos(4, 5, 6)); var original = context(c);
        BlockPos landing = h.absolutePos(new BlockPos(5, 1, 6));
        h.assertTrue(BlockFlags.has(original.blocks.flags(landing.getX(), landing.getY(), landing.getZ()), BlockFlags.WATER),
                "Wasserfixture fehlt");
        h.setBlock(5, 1, 6, Blocks.AIR);
        var start = new Node(c.getBlockX(), c.getBlockY(), c.getBlockZ(), 0); start.g = 0;
        var end = new Node(landing.getX(), landing.getY(), landing.getZ(), 0);
        end.prev = start; end.move = Move.DESCEND_E; end.g = 20;
        var projection = new RouteProjection(c, original, Path.from(end, true)); projection.step(Long.MAX_VALUE);
        h.assertTrue(projection.status() == RouteProjection.Status.COMPLETE, "Trockener sicherer Abstieg fehlt: " + projection.reason());
        float expected = original.fallDamage(start.y - end.y);
        h.assertTrue(expected > 0 && projection.context().health == original.health - expected,
                "Entferntes Wasser wird weiterhin als schadensfreie Landung gerechnet");
        h.assertTrue(projection.damage()[1] == expected && projection.worldUnchanged(), "Schadensbilanz oder Weltannahmen falsch");
        h.succeed();
    }

    @GameTest(structure = BENCH)
    public void rundenpruefungGibtVeralteteWeltannahmenNichtFrei(GameTestHelper h) throws Exception {
        arena(h); var c = companion(h, new BlockPos(4, 1, 6)); var check = prepared(h, c);
        h.getLevel().setBlock(check.outbound().positions.get(1).below(), Blocks.DIRT.defaultBlockState(), 3);
        for (int i = 0; i < 2000 && check.status() == RoundTripCheck.Status.CHECKING; i++) check.step();
        h.assertTrue(check.status() == RoundTripCheck.Status.UNPROVEN
                && check.reasonComponent().getContents() instanceof net.minecraft.network.chat.contents.TranslatableContents tr
                && tr.getKey().equals("server.begleiter.route.projection_changed"), "Alte Vorschau freigegeben: " + check.reason());
        dev.begleiter.HandlerTextGameTests.translated(h, check.reasonComponent(), "Weltannahmen der Vorschau verändert", "preview world assumptions changed");
        h.succeed();
    }

    @GameTest(structure = BENCH)
    public void rundenpruefungAktualisiertDieWeltgrenzeVorFreigabe(GameTestHelper h) {
        arena(h); var c = companion(h, new BlockPos(4, 1, 6)); var check = prepared(h, c);
        var border = h.getLevel().getWorldBorder();
        double x = border.getCenterX(), z = border.getCenterZ(), size = border.getSize();
        try {
            border.setCenter(c.getX(), c.getZ()); border.setSize(8);
            for (int i = 0; i < 2000 && check.status() == RoundTripCheck.Status.CHECKING; i++) check.step();
            h.assertTrue(check.status() == RoundTripCheck.Status.UNPROVEN, "Veränderte Weltgrenze ignoriert");
        } finally { border.setCenter(x, z); border.setSize(size); }
        h.succeed();
    }
}
