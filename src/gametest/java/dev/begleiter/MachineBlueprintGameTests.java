package dev.begleiter;

import java.util.List;
import net.fabricmc.fabric.api.gametest.v1.GameTest;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.Rotation;

public class MachineBlueprintGameTests {
    private static final String ARENA = "begleiter-gametest:bench";

    @GameTest
    public void bauPruefungErlaubtKurzeWiederaufnahmeAberKeinenStillstand(GameTestHelper h) {
        var x = new TestScenarios.Ctx();
        x.sl = h.getLevel(); x.c = h.absolutePos(BlockPos.ZERO);
        x.bot = h.spawn(CompanionMod.COMPANION, new BlockPos(2, 1, 2));
        x.bot.setNoAi(true); x.bot.stay();
        x.bot.build = CompanionBuilder.create(x.sl, "Retry", java.util.Map.of(
                h.absolutePos(new BlockPos(4, 1, 4)), Blocks.STONE.defaultBlockState()));
        var check = TestScenarios.byId("farm_bambusfarm").check();
        x.ticks = 100;
        h.assertTrue(check.run(x) == null, "Ein normales Wiederaufnahmeintervall wurde als Fehler gewertet");
        x.ticks = 120;
        x.bot.addTask(CompanionEntity.Task.build());
        h.assertTrue(check.run(x) == null && x.buildIdleSince == -1, "Wiederaufnahme setzt Wartezeit nicht zurück");
        x.bot.finishTask();
        x.ticks = 130;
        h.assertTrue(check.run(x) == null, "Neue Unterbrechung übernimmt alte Wartezeit");
        x.ticks = 170;
        var stalled = check.run(x);
        h.assertTrue(stalled != null && !stalled.ok(), "Dauerhaft fehlender Bauauftrag wurde akzeptiert");
        h.succeed();
    }

    @GameTest(maxTicks = 3000, structure = ARENA)
    public void karottenErntenUndNachpflanzen(GameTestHelper h) { RedstoneScenarioGameTests.run(h, "farm_karottenfarm"); }

    @GameTest(maxTicks = 3000, structure = ARENA)
    public void kartoffelnErntenUndNachpflanzen(GameTestHelper h) { RedstoneScenarioGameTests.run(h, "farm_kartoffelfarm"); }

    @GameTest(maxTicks = 3000, structure = ARENA)
    public void kaktusWaechstUndWirdGesammelt(GameTestHelper h) { RedstoneScenarioGameTests.run(h, "farm_kaktusfarm"); }

    @GameTest(maxTicks = 3000, structure = ARENA)
    public void raeucherofenGartNahrung(GameTestHelper h) { RedstoneScenarioGameTests.run(h, "farm_raeucherofen"); }

    @GameTest(maxTicks = 3000, structure = ARENA)
    public void hochofenSchmilztErze(GameTestHelper h) { RedstoneScenarioGameTests.run(h, "farm_hochofen"); }

    @GameTest(maxTicks = 3000, structure = ARENA)
    public void schaltlagerSperrtTransport(GameTestHelper h) { RedstoneScenarioGameTests.run(h, "farm_schaltlager"); }

    @GameTest(maxTicks = 3000, structure = ARENA)
    public void portioniererGibtEinzelneItemsAus(GameTestHelper h) { RedstoneScenarioGameTests.run(h, "farm_portionierer"); }

    @GameTest(maxTicks = 3000, structure = ARENA)
    public void zuckerrohrFarmZweiErnten(GameTestHelper h) { RedstoneScenarioGameTests.run(h, "farm_zuckerrohrfarm"); }

    @GameTest(maxTicks = 3000, structure = ARENA)
    public void bambusFarmZweiErnten(GameTestHelper h) { RedstoneScenarioGameTests.run(h, "farm_bambusfarm"); }

    @GameTest(maxTicks = 3000, structure = ARENA)
    public void weizenErntenWasserZurueckUndNeuSaeen(GameTestHelper h) { RedstoneScenarioGameTests.run(h, "farm_wasserernte"); }

    @GameTest(maxTicks = 3000, structure = ARENA)
    public void ofenVerarbeitetEchtesRoheisen(GameTestHelper h) { RedstoneScenarioGameTests.run(h, "farm_schmelzofen"); }

    @GameTest(maxTicks = 3000, structure = ARENA)
    public void komposterProduziertKnochenmehl(GameTestHelper h) { RedstoneScenarioGameTests.run(h, "farm_komposter"); }

    @GameTest(maxTicks = 3000, structure = ARENA)
    public void sortiererTrenntZielitemsVomRest(GameTestHelper h) { RedstoneScenarioGameTests.run(h, "farm_itemsortierer"); }

    @GameTest(maxTicks = 3000, structure = ARENA)
    public void bruchsteinBildetSichNeu(GameTestHelper h) { RedstoneScenarioGameTests.run(h, "farm_bruchsteingenerator"); }

    @GameTest(maxTicks = 3000, structure = ARENA)
    public void lichtschalterWechseltBeiJedemDruck(GameTestHelper h) { RedstoneScenarioGameTests.run(h, "farm_lichtschalter"); }

    @GameTest(maxTicks = 3000, structure = ARENA)
    public void tresorOeffnetNurMitKombination(GameTestHelper h) { RedstoneScenarioGameTests.run(h, "farm_tresorschloss"); }

    @GameTest(maxTicks = 8000, structure = ARENA)
    public void zweistoeckigesHausAusSchematic(GameTestHelper h) { RedstoneScenarioGameTests.run(h, "schem_haus"); }

    @GameTest
    public void bauplaeneUndTestauswahlSindIdentisch(GameTestHelper h) {
        var unknown = new java.util.ArrayList<String>();
        // farmen: ein Vertreter je Prinzip (11), farmen_alle: jede Anlage
        var farms = TestScenarios.pick("farmen", unknown);
        h.assertValueEqual(farms.size(), CompanionMachines.ALL.size() - 5, "ein Vertreter je Farm-Prinzip");
        h.assertValueEqual(TestScenarios.pick("farmen_alle", unknown).size(), CompanionMachines.ALL.size(), "Anlagen fehlen in Testmap");
        h.assertTrue(unknown.isEmpty(), "Gruppe farmen unbekannt");
        h.assertTrue(TestScenarios.pick("redstone", unknown).containsAll(farms), "Redstone-Gruppe enthält Farmen nicht");
        for (var blueprint : CompanionMachines.ALL) {
            h.assertTrue(TestScenarios.byId("farm_" + blueprint.id()) != null, "Szenario fehlt");
            var plan = blueprint.plan().get();
            h.assertTrue(plan.sizeX() <= TestMap.CELL && plan.sizeZ() <= TestMap.CELL, "Bauplan passt nicht auf Testplatte");
            // obere Tür-/Pflanzenhälften kommen mit der unteren, haben also kein eigenes Item
            h.assertTrue(plan.blocks().values().stream().filter(s -> !s.isAir())
                            .filter(s -> !s.hasProperty(net.minecraft.world.level.block.state.properties.BlockStateProperties.DOUBLE_BLOCK_HALF)
                                    || s.getValue(net.minecraft.world.level.block.state.properties.BlockStateProperties.DOUBLE_BLOCK_HALF)
                                    == net.minecraft.world.level.block.state.properties.DoubleBlockHalf.LOWER)
                            .allMatch(s -> CompanionBuilder.itemFor(s) != null),
                    "Bauplan enthält nicht beschaffbare Blöcke: " + blueprint.id());
        }
        h.assertTrue(CompanionMachines.find("sugar_cane_farm") == CompanionMachines.find("zuckerrohrfarm"), "englischer Alias fehlt");
        h.succeed();
    }

    @GameTest(structure = ARENA)
    public void menuUndBefehlErstellenDenselbenBauplan(GameTestHelper h) throws Exception {
        var c = h.spawn(CompanionMod.COMPANION, new BlockPos(5, 1, 1));
        c.setNoAi(true); c.stay();
        var p = h.makeMockServerPlayerInLevel();
        c.setOwner(p.getUUID());
        p.snapTo(c.getX(), c.getY(), c.getZ(), 0, 0);
        var menu = new CompanionMenu(0, p.getInventory(), c, CompanionMenu.Page.BUILD);
        menu.quickMoveStack(p, 18);
        h.assertTrue(c.build == null, "Baumenü baut vor Bestätigung");
        var blueprint = CompanionMachines.find("zuckerrohrfarm");
        var preview = CompanionPlacement.select(p, c, blueprint, Rotation.NONE);
        var expected = CompanionSchematics.place(blueprint.plan().get(), p.blockPosition().below(), p.getDirection(), Rotation.NONE, true);
        int minX = expected.keySet().stream().mapToInt(BlockPos::getX).min().orElseThrow();
        int maxX = expected.keySet().stream().mapToInt(BlockPos::getX).max().orElseThrow();
        int minZ = expected.keySet().stream().mapToInt(BlockPos::getZ).min().orElseThrow();
        int maxZ = expected.keySet().stream().mapToInt(BlockPos::getZ).max().orElseThrow();
        var center = new BlockPos(minX + (maxX - minX + 1) / 2, p.blockPosition().getY() - 1, minZ + (maxZ - minZ + 1) / 2);
        var result = CompanionPlacement.confirm(p, new CompanionPlacement.Confirm(preview.token(), center, preview.rotation(), false));
        h.assertTrue(result.finished() && c.build != null, "Bestätigte Vorschau startet Farm nicht: " + result.message().getString());
        var fromMenu = c.build.order;
        c.cancelOrders();
        h.getLevel().getServer().getCommands().getDispatcher().execute("begleiter bauplan zuckerrohrfarm", p.createCommandSourceStack());
        h.assertTrue(c.build != null && c.build.order.equals(fromMenu), "Befehl und Menü verwenden andere Pläne");
        c.cancelOrders();
        var reply = CompanionChatActions.interpretReply(p, c, "bau eine bambusfarm", "de_de");
        h.assertTrue(reply != null && c.build != null && c.build.name.equals("bambusfarm"), "Chat startet Farm nicht");
        h.succeed();
    }

    @GameTest(structure = ARENA)
    public void besetzterBauplatzErhaeltContainerUndAuftrag(GameTestHelper h) {
        var c = h.spawn(CompanionMod.COMPANION, new BlockPos(5, 1, 1));
        c.setNoAi(true); c.stay();
        var p = h.makeMockServerPlayerInLevel();
        c.setOwner(p.getUUID()); p.snapTo(c.getX(), c.getY(), c.getZ(), 0, 0);
        var blueprint = CompanionMachines.find("schmelzofen");
        var targets = CompanionSchematics.place(blueprint.plan().get(), p.blockPosition().below(), p.getDirection(), Rotation.NONE, true);
        BlockPos occupied = targets.keySet().iterator().next();
        h.getLevel().setBlockAndUpdate(occupied, Blocks.CHEST.defaultBlockState());
        var chest = (net.minecraft.world.Container) h.getLevel().getBlockEntity(occupied);
        chest.setItem(0, new net.minecraft.world.item.ItemStack(net.minecraft.world.item.Items.DIAMOND, 3));
        c.addTask(CompanionEntity.Task.moveTo(c.blockPosition(), 1));
        var old = c.currentTask();
        CompanionBrain.startMachineReply(p, c, blueprint, Rotation.NONE);
        h.assertTrue(c.build == null && c.currentTask() == old, "Abgelehnter Bau ersetzt bisherigen Auftrag");
        h.assertValueEqual(chest.getItem(0).getCount(), 3, "Containerinhalt verändert");
        h.succeed();
    }

    @GameTest
    public void alleDrehungenErhaltenBauteileUndMaterial(GameTestHelper h) {
        for (var blueprint : CompanionMachines.ALL) {
            var plan = blueprint.plan().get();
            var original = new java.util.HashMap<net.minecraft.world.item.Item, Integer>();
            plan.blocks().values().stream().filter(s -> !s.isAir()).forEach(s -> original.merge(CompanionBuilder.itemFor(s), CompanionBuilder.itemCount(s), Integer::sum));
            for (Direction direction : List.of(Direction.NORTH, Direction.SOUTH, Direction.EAST, Direction.WEST)) {
                var world = CompanionSchematics.place(plan, BlockPos.ZERO, direction, Rotation.NONE, true);
                var rotated = new java.util.HashMap<net.minecraft.world.item.Item, Integer>();
                world.values().stream().filter(s -> !s.isAir()).forEach(s -> rotated.merge(CompanionBuilder.itemFor(s), CompanionBuilder.itemCount(s), Integer::sum));
                h.assertTrue(original.equals(rotated) && world.size() == plan.blocks().size(), "Drehung verliert Material oder Blöcke");
            }
        }
        h.succeed();
    }
}
