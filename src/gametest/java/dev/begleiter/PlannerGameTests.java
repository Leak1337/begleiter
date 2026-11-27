package dev.begleiter;

import net.fabricmc.fabric.api.gametest.v1.GameTest;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Blocks;

import dev.begleiter.CompanionEntity.TaskType;
import dev.begleiter.task.TaskSpec;

/** Resource accounting regressions; exclude other tests' chests from planning. */
public class PlannerGameTests {
    private static BlockPos explorationColumn(GameTestHelper h) {
        for (int y = 1; y < h.getLevel().getMaxY() - h.absolutePos(BlockPos.ZERO).getY(); y++) h.setBlock(5, y, 5, Blocks.AIR);
        h.setBlock(5, 0, 5, Blocks.DIRT);
        return h.absolutePos(new BlockPos(5, 1, 5));
    }

    @GameTest(structure = "begleiter-gametest:tall")
    public void erkundungFindetBodenUnterBaumkrone(GameTestHelper h) {
        var c = companion(h);
        var p = explorationColumn(h);
        h.setBlock(5, 12, 5, Blocks.JUNGLE_LOG);
        h.setBlock(5, 13, 5, Blocks.JUNGLE_LEAVES);
        h.assertValueEqual(c.explore.explorationSurfaceY(h.getLevel(), p.getX(), p.getZ()), p.getY(),
                "Nahrungssuche darf keinen Stamm in der Baumkrone als Boden wählen");
        h.succeed();
    }

    @GameTest(structure = "begleiter-gametest:tall")
    public void erkundungFindetBodenUnterRiesenpilz(GameTestHelper h) {
        var c = companion(h);
        var p = explorationColumn(h);
        h.setBlock(5, 10, 5, Blocks.BROWN_MUSHROOM_BLOCK);
        h.assertValueEqual(c.explore.explorationSurfaceY(h.getLevel(), p.getX(), p.getZ()), p.getY(),
                "Nahrungssuche darf keinen Riesenpilzhut ansteuern");
        h.succeed();
    }

    @GameTest(structure = "begleiter-gametest:tall")
    public void erkundungMeidetBambusOhneStandplatz(GameTestHelper h) {
        var c = companion(h);
        var p = explorationColumn(h);
        for (int y = 1; y <= 16; y++) h.setBlock(5, y, 5, Blocks.BAMBOO);
        h.assertValueEqual(c.explore.explorationSurfaceY(h.getLevel(), p.getX(), p.getZ()), Integer.MIN_VALUE,
                "Bambusspitzen und belegter Boden sind kein Erkundungsziel");
        h.succeed();
    }

    @GameTest(structure = "begleiter-gametest:tall")
    public void erkundungMeidetWasserUndSucheUnterGelaende(GameTestHelper h) {
        var c = companion(h);
        var p = explorationColumn(h);
        h.setBlock(5, 12, 5, Blocks.STONE);
        h.setBlock(5, 13, 5, Blocks.WATER);
        h.assertValueEqual(c.explore.explorationSurfaceY(h.getLevel(), p.getX(), p.getZ()), Integer.MIN_VALUE,
                "Unter einem See liegende trockene Hohlräume sind keine Oberflächenziele");
        h.succeed();
    }

    private static CompanionEntity companion(GameTestHelper h) {
        CompanionEntity c = h.spawn(CompanionMod.COMPANION, new BlockPos(2, 1, 2));
        c.stay();
        c.getInventory().clearContent();
        c.setItemInHand(net.minecraft.world.InteractionHand.MAIN_HAND, ItemStack.EMPTY);
        return c;
    }

    @GameTest
    public void zielbestandZaehltVorhandeneKohleEinmal(GameTestHelper h) {
        CompanionEntity c = companion(h);
        for (int stock : new int[]{0, 3, 4, 7, 8}) {
            c.getInventory().clearContent();
            c.giveToInventory(new ItemStack(Items.DIAMOND_PICKAXE));
            if (stock > 0) c.giveToInventory(new ItemStack(Items.COAL, stock));
            var plan = CompanionPlanner.obtain(c, Items.COAL, 8, CompanionPlanner.RoutePolicy.AVOID_CHESTS);
            h.assertTrue(plan != null, "Kein Kohleplan bei Bestand " + stock);
            int gathered = plan.tasks().stream()
                    .filter(t -> t.spec() instanceof TaskSpec.Gather g && Resources.COAL.key().equals(g.resource()))
                    .mapToInt(t -> ((TaskSpec.Gather) t.spec()).count()).sum();
            h.assertValueEqual(gathered, 8 - stock, "Kohlebedarf bei Bestand " + stock);
            if (stock == 8) h.assertTrue(plan.tasks().isEmpty(), "Voller Bestand braucht keinen Plan");
            h.assertValueEqual(CompanionCraft.count(c, "coal"), stock, "Planung veraendert echtes Inventar");
        }
        h.succeed();
    }

    private static CompanionEntity blastFurnaceInputs(GameTestHelper h) {
        CompanionEntity c = companion(h);
        c.giveToInventory(new ItemStack(Items.DIAMOND_PICKAXE));
        c.giveToInventory(new ItemStack(Items.FURNACE));
        c.giveToInventory(new ItemStack(Items.RAW_IRON, 5));
        c.giveToInventory(new ItemStack(Items.COBBLESTONE, 3));
        return c; // 5 iron + 3 stone + 3 smooth stone = 11 smelts
    }

    private static CompanionPlanner.Plan blastFurnacePlan(GameTestHelper h, CompanionEntity c) {
        var plan = CompanionPlanner.obtain(c, Items.BLAST_FURNACE, 1, CompanionPlanner.RoutePolicy.AVOID_CHESTS);
        h.assertTrue(plan != null, "Kein Hochofenplan");
        h.assertTrue(plan.tasks().stream().filter(t -> t.type() == TaskType.SMELT).count() >= 3,
                "Mehrere Schmelzschritte fehlen: " + plan.explain());
        return plan;
    }

    @GameTest
    public void schmelzschritteTeilenRestwaermeAberNichtVerbrauchteKohle(GameTestHelper h) {
        CompanionEntity c = blastFurnaceInputs(h);
        c.giveToInventory(new ItemStack(Items.COAL));
        var plan = blastFurnacePlan(h, c);
        int coal = plan.tasks().stream()
                .filter(t -> t.spec() instanceof TaskSpec.Gather g && Resources.COAL.key().equals(g.resource()))
                .mapToInt(t -> ((TaskSpec.Gather) t.spec()).count()).sum();
        h.assertValueEqual(coal, 1, "11 Schmelzvorgaenge brauchen insgesamt 2 Kohle");
        h.assertValueEqual(CompanionCraft.count(c, "coal"), 1, "Planung verbraucht echte Kohle");
        h.succeed();
    }

    @GameTest
    public void schmelzplanNutztVorhandeneRestwaerme(GameTestHelper h) {
        CompanionEntity c = blastFurnaceInputs(h);
        c.fuelLeft = 11;
        var plan = blastFurnacePlan(h, c);
        h.assertTrue(plan.tasks().stream().noneMatch(t -> t.type() == TaskType.GATHER),
                "Sammelt trotz ausreichender Restwaerme: " + plan.explain());
        h.assertTrue(c.fuelLeft == 11, "Planung veraendert echte Restwaerme");
        h.succeed();
    }

    @GameTest
    public void schmelzplanNutztHolzMitGebrochenerBrennleistung(GameTestHelper h) {
        CompanionEntity c = blastFurnaceInputs(h);
        c.giveToInventory(new ItemStack(Items.OAK_PLANKS, 8)); // 12 smelts, no coal needed
        var plan = blastFurnacePlan(h, c);
        h.assertTrue(plan.tasks().stream().noneMatch(t -> t.type() == TaskType.GATHER),
                "Sammelt trotz ausreichendem Holz: " + plan.explain());
        h.succeed();
    }
    private static net.minecraft.world.Container knownCoalChest(
            GameTestHelper h, CompanionEntity c, BlockPos relative, int coal) {
        h.setBlock(relative, Blocks.CHEST);
        BlockPos absolute = h.absolutePos(relative);
        var box = c.containerAt(h.getLevel(), absolute);
        h.assertTrue(box != null, "Testkiste fehlt");
        box.setItem(0, new ItemStack(Items.COAL, coal));
        c.memory.noteChest(h.getLevel(), absolute, box);
        return box;
    }

    @GameTest
    public void teilmengenAusZweiBekanntenKistenStattBergbau(GameTestHelper h) {
        CompanionEntity c = companion(h);
        knownCoalChest(h, c, new BlockPos(4, 1, 2), 3);
        knownCoalChest(h, c, new BlockPos(6, 1, 2), 5);
        var combined = CompanionPlanner.obtainPrepared(c, Items.COAL, 8,
                CompanionPlanner.RoutePolicy.AVOID_MINING);
        h.assertTrue(combined != null, "Kein vorbereiteter Kistenplan");
        h.assertTrue(combined.tasks().size() == 2 && combined.tasks().stream()
                .allMatch(t -> t.type() == TaskType.TAKE),
                "Kohlevorrat sollte aus zwei TAKE-Aufgaben bestehen");
        h.assertTrue(combined != null, "Zwei Teilmengen wurden nicht als ein Plan erkannt");
        h.assertValueEqual(combined.tasks().stream().mapToInt(t -> ((TaskSpec.Take) t.spec()).count()).sum(),
                8, "Kistenteilmengen nicht genau verteilt");
        h.assertValueEqual(CompanionCraft.count(c, "coal"), 0,
                "Planung darf das reale Inventar nicht veraendern");
        h.succeed();
    }

    @GameTest
    public void bekannteGesperrteKisteWirdNichtKombiniert(GameTestHelper h) {
        CompanionEntity c = companion(h);
        BlockPos first = new BlockPos(4, 1, 2);
        knownCoalChest(h, c, first, 3);
        knownCoalChest(h, c, new BlockPos(6, 1, 2), 5);
        c.experiences.sources.failed(CompanionSources.key(c, CompanionNeeds.Need.SUPPLY,
                Items.COAL, h.absolutePos(first)),
                dev.begleiter.ai.DecisionOutcome.Reason.SOURCE_EMPTY, c.tickCount);
        var planned = CompanionPlanner.obtainPrepared(c, Items.COAL, 8,
                CompanionPlanner.RoutePolicy.AVOID_MINING);
        h.assertTrue(planned == null || planned.tasks().stream()
                        .filter(t -> t.type() == TaskType.TAKE)
                        .noneMatch(t -> h.absolutePos(first).equals(t.pos())),
                "Gesperrte Kistenquelle darf im Ausweichplan nicht auftauchen");
        h.succeed();
    }

}
