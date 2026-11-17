package dev.begleiter;

import net.fabricmc.fabric.api.gametest.v1.GameTest;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.phys.AABB;

public class TestMapExpansionGameTests {
    @GameTest
    public void ausstellungEnthaeltJedenBauplanGenauEinmal(GameTestHelper h) {
        var exhibits = TestMapExpansion.exhibits();
        h.assertValueEqual(exhibits.size(), CompanionMachines.ALL.size() + 2, "Standard-Weizenfarm oder Maschine fehlt");
        h.assertValueEqual(exhibits.stream().map(TestMapExpansion.Exhibit::id).distinct().count(), (long) exhibits.size(), "doppelte Parzelle");
        for (var blueprint : CompanionMachines.ALL)
            h.assertTrue(exhibits.stream().anyMatch(e -> e.id().equals(blueprint.id())), "Bauplan fehlt: " + blueprint.id());
        h.succeed();
    }

    @GameTest
    public void grosseTestflaecheUndAusstellungUeberlappenNicht(GameTestHelper h) {
        BlockPos origin = BlockPos.ZERO;
        BlockPos work = TestMap.corner(origin, TestMap.station("redstone"));
        h.assertValueEqual(TestMap.stationSize(TestMap.station("redstone")), 32, "Arbeitsfläche nicht vergrößert");
        var resetArea = new AABB(work.getX() - 1, -15, work.getZ() - 1, work.getX() + 33, 30, work.getZ() + 33);
        var occupied = new java.util.ArrayList<AABB>();
        for (int i = 0; i < TestMapExpansion.exhibits().size(); i++) {
            var pos = TestMapExpansion.plotCorner(origin, i);
            var plan = TestMapExpansion.exhibits().get(i).plan().get();
            var box = new AABB(pos.getX(), 0, pos.getZ(), pos.getX() + plan.sizeX(), plan.sizeY(), pos.getZ() + plan.sizeZ());
            h.assertTrue(!resetArea.intersects(box), "Testreset löscht Ausstellung");
            h.assertTrue(occupied.stream().noneMatch(box::intersects), "Ausstellungsanlagen überlappen");
            for (var station : TestMap.STATIONS) if (!station.id().equals("redstone")) {
                var corner = TestMap.corner(origin, station);
                h.assertTrue(!box.intersects(new AABB(corner.getX(), corner.getY(), corner.getZ(),
                        corner.getX() + 12, corner.getY() + 30, corner.getZ() + 12)), "Ausstellung überschreibt Station");
            }
            occupied.add(box);
        }
        h.succeed();
    }

    @GameTest
    public void farmfeldLiegtFreiNebenStationenArbeitsflaecheUndAusstellung(GameTestHelper h) {
        BlockPos origin = BlockPos.ZERO;
        BlockPos f = TestMapExpansion.fieldCorner(origin);
        int n = TestMapExpansion.FIELD_SIZE;
        // ganze bebaute Fläche inklusive Weg und Bodenrand (ground reicht bis x +193, z -101..+35)
        BlockPos lo = TestMapExpansion.fieldMin(origin), hi = TestMapExpansion.fieldMax(origin);
        var field = new AABB(lo.getX(), -15, lo.getZ(), hi.getX() + 1, 30, hi.getZ() + 1);
        h.assertTrue(n >= 128, "Farmfeld zu klein");
        h.assertTrue(field.contains(f.getX(), 0, f.getZ()) && field.contains(f.getX() + n - 1, 0, f.getZ() + n - 1), "Feld außerhalb seines Bodens");
        BlockPos work = TestMapExpansion.workCorner(origin);
        h.assertTrue(!field.intersects(new AABB(work.getX(), -15, work.getZ(), work.getX() + 32, 30, work.getZ() + 32)), "Farmfeld überdeckt Arbeitsfläche");
        for (var station : TestMap.STATIONS) {
            if (station.id().equals("redstone")) continue;
            var c = TestMap.corner(origin, station);
            h.assertTrue(!field.intersects(new AABB(c.getX() - 1, -15, c.getZ() - 1, c.getX() + 13, 30, c.getZ() + 13)), "Farmfeld überdeckt Station " + station.id());
        }
        for (int i = 0; i < TestMapExpansion.exhibits().size(); i++) {
            var p = TestMapExpansion.plotCorner(origin, i);
            h.assertTrue(!field.intersects(new AABB(p.getX(), -15, p.getZ(), p.getX() + 16, 30, p.getZ() + 16)), "Farmfeld überdeckt Ausstellung");
        }
        h.succeed();
    }

    /** Rücksetz-Wand: je Knopf ein eigener Befehlsblock mit dem passenden, gültigen Befehl; Nachbarn lösen nicht mit aus. */
    @GameTest(structure = "begleiter-gametest:tall")
    public void ruecksetzWandHatDreiKnoepfeMitGueltigenBefehlen(GameTestHelper h) {
        BlockPos origin = h.absolutePos(new BlockPos(-50, 1, 5));
        TestMapExpansion.buildResetWall(h.getLevel(), origin);
        var dispatcher = h.getLevel().getServer().getCommands().getDispatcher();
        var source = h.getLevel().getServer().createCommandSourceStack().withLevel(h.getLevel());
        for (var reset : TestMapExpansion.FieldReset.values()) {
            BlockPos button = TestMapExpansion.resetWallButton(origin, reset);
            h.assertTrue(h.getLevel().getBlockState(button).getBlock() instanceof net.minecraft.world.level.block.ButtonBlock, "Knopf fehlt: " + reset.id);
            h.assertTrue(h.getLevel().getBlockEntity(button.west()) instanceof net.minecraft.world.level.block.entity.CommandBlockEntity cb
                    && cb.getCommandBlock().getCommand().equals("companion testmap farmfeld " + reset.id), "Befehlsblock falsch: " + reset.id);
            h.assertTrue(!h.getLevel().getBlockState(button.west().north()).is(Blocks.COMMAND_BLOCK)
                    && !h.getLevel().getBlockState(button.west().south()).is(Blocks.COMMAND_BLOCK), "Befehlsblöcke berühren sich");
            var parsed = dispatcher.parse("companion testmap farmfeld " + reset.id, source);
            h.assertTrue(!parsed.getReader().canRead() && parsed.getExceptions().isEmpty(), "Befehl ungültig: " + reset.id);
        }
        h.succeed();
    }

    /** Knöpfe "items" und "tiere": räumen nur ihren Teil, Begleiter und benannte Tiere bleiben. */
    @GameTest(structure = "begleiter-gametest:tall")
    public void ruecksetzKnoepfeRaeumenGegenstaendeUndTiere(GameTestHelper h) {
        for (int x = 0; x < 40; x++) for (int z = 0; z < 40; z++) h.setBlock(x, 0, z, Blocks.GRASS_BLOCK);
        // Nur das eigene Teststück räumen (das echte Feld ist 136×137 groß und träfe Nachbartests)
        var area = h.getBounds();
        var item = h.spawnItem(net.minecraft.world.item.Items.WHEAT, new BlockPos(5, 2, 5));
        var cow = h.spawn(net.minecraft.world.entity.EntityTypes.COW, new BlockPos(6, 1, 6));
        var named = h.spawn(net.minecraft.world.entity.EntityTypes.COW, new BlockPos(8, 1, 6));
        named.setCustomName(net.minecraft.network.chat.Component.literal("Berta"));
        var bot = h.spawn(CompanionMod.COMPANION, new BlockPos(10, 1, 10));
        TestMapExpansion.clearEntities(h.getLevel(), area, TestMapExpansion.FieldReset.ITEMS);
        h.assertTrue(item.isRemoved() && cow.isAlive(), "Gegenstände-Knopf räumt falsch");
        TestMapExpansion.clearEntities(h.getLevel(), area, TestMapExpansion.FieldReset.TIERE);
        h.assertTrue(cow.isRemoved() && named.isAlive() && bot.isAlive(), "Tiere-Knopf entfernt falsch");
        h.succeed();
    }

    @GameTest(maxTicks = 150, structure = "begleiter-gametest:bench")
    public void ausstellungsfarmWirdMitLebendenPflanzenAufgebaut(GameTestHelper h) {
        var at = h.absolutePos(new BlockPos(1, 1, 1));
        TestMapExpansion.placeExhibit(h.getLevel(), at, CompanionMachines.find("zuckerrohrfarm").plan().get());
        h.runAfterDelay(80, () -> {
            for (int x = 2; x <= 5; x++)
                h.assertTrue(h.getLevel().getBlockState(at.offset(x, 2, 3)).is(Blocks.SUGAR_CANE), "Zuckerrohr vor Bewässerung zerstört");
            h.assertTrue(h.getLevel().getBlockEntity(at.offset(0, 0, 2)) instanceof net.minecraft.world.Container, "Sammelkiste fehlt");
            h.assertTrue(!h.getLevel().getFluidState(at.offset(2, 1, 2)).isEmpty(), "Wasserrinne trocken");
            h.succeed();
        });
    }
}
