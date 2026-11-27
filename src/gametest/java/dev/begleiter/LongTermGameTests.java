package dev.begleiter;

import java.util.List;

import dev.begleiter.CompanionEntity.Task;
import dev.begleiter.CompanionEntity.TaskType;
import dev.begleiter.CompanionNeeds.Need;
import dev.begleiter.ai.AdaptiveAcquisition;
import dev.begleiter.ai.CompanionDecisionEngine;
import dev.begleiter.ai.CompanionDecisionEngine.SupplyOption;
import dev.begleiter.ai.CompanionExperienceMemory;
import dev.begleiter.ai.DecisionOutcome;
import net.fabricmc.fabric.api.gametest.v1.GameTest;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;

/** Executed in both default and enabled game JVMs by CI. */
public class LongTermGameTests {
    private static List<CompanionPlanner.Alternative> routes(double miningCost, double chestCost) {
        var mine = new CompanionPlanner.Plan(List.of(Task.gather("kohle", 8)),
                miningCost, List.of("Bergbau"));
        var chest = new CompanionPlanner.Plan(List.of(Task.take(new BlockPos(3, 1, 2), "coal", 8)),
                chestCost, List.of("Kiste"));
        return List.of(new CompanionPlanner.Alternative(mine, CompanionPlanner.RoutePolicy.AVOID_CHESTS),
                new CompanionPlanner.Alternative(chest, CompanionPlanner.RoutePolicy.AVOID_MINING));
    }

    private static CompanionEntity companion(GameTestHelper h) {
        CompanionEntity c = h.spawn(CompanionMod.COMPANION, new BlockPos(2, 1, 2));
        c.stay();
        c.getInventory().clearContent();
        c.setItemInHand(InteractionHand.MAIN_HAND, ItemStack.EMPTY);
        c.food.set(20);
        c.giveToInventory(new ItemStack(Items.DIAMOND_PICKAXE));
        c.giveToInventory(new ItemStack(Items.DIAMOND_AXE));
        c.memory.clear();
        return c;
    }

    @GameTest
    public void erfolgeUndFehlerBleibenMitBegrenzterPraeferenzErhalten(GameTestHelper h) {
        var memory = new CompanionExperienceMemory();
        for (int i = 0; i < 12; i++) memory.succeeded(Need.SUPPLY, "kohle_chest");
        for (int i = 0; i < 8; i++) memory.recordTargetOnly(new DecisionOutcome(
                Need.SUPPLY, DecisionOutcome.Reason.PLAN_FAILED, "kohle_mining", i));
        var loaded = new CompanionExperienceMemory();
        loaded.load(memory.save(10000), 0); // Only persistent profiles remain.
        h.assertValueEqual(loaded.learnedPreference(Need.SUPPLY, "kohle_chest"), 5, "Erfolgsbonus nicht begrenzt/gespeichert");
        h.assertValueEqual(loaded.learnedPreference(Need.SUPPLY, "kohle_mining"), -5, "Fehlerpraeferenz nicht begrenzt/gespeichert");
        h.assertValueEqual(loaded.penalty(Need.SUPPLY, "kohle_mining", 0), 0, "Abgelaufene Sperre wiederhergestellt");
        h.assertTrue(loaded.hasRouteExperience(Need.SUPPLY, "kohle"), "Ressourcenprofil nicht erkannt");
        h.assertTrue(!loaded.hasRouteExperience(Need.ORDER, "kohle") && !loaded.hasRouteExperience(Need.SUPPLY, "holz"),
                "Erfahrung auf fremdes Ziel uebertragen");
        h.succeed();
    }

    @GameTest
    public void bewaehrteRouteWirdOhneAktuellenFehlerBevorzugt(GameTestHelper h) {
        var routes = routes(10, 14);
        var baseline = AdaptiveAcquisition.choose(routes, method -> 0);
        var learned = AdaptiveAcquisition.choose(routes, method -> 0,
                method -> method.equals("chest") ? 3 : 0, plan -> 0);
        h.assertTrue(baseline != null && baseline.approach().equals("mining"), "Unbekannte Route nicht nach Kosten gewaehlt");
        h.assertTrue(learned != null && learned.approach().equals("chest"), "Erfahrung erst nach neuem Fehler genutzt");
        h.assertTrue(AdaptiveAcquisition.choose(routes(10, 100), method -> 0,
                method -> method.equals("chest") ? Integer.MAX_VALUE : 0, plan -> 0).approach().equals("mining"),
                "Erfolgsbonus ueberschreibt viel hoeheren Aufwand");
        h.succeed();
    }

    @GameTest
    public void alteFehlerSperrenEineEinzigeVerfuegbareRouteNichtDauerhaft(GameTestHelper h) {
        var onlyMine = List.of(routes(10, 14).getFirst());
        var choice = AdaptiveAcquisition.choose(onlyMine, method -> 0, method -> -1000, plan -> 0);
        h.assertTrue(choice != null && choice.approach().equals("mining"), "Historischer Fehler erzeugt dauerhafte Sperre");
        h.assertTrue(choice.adjustedCost() <= 20, "Langzeitmalus nicht begrenzt");
        h.succeed();
    }

    @GameTest
    public void aktuelleSperreUndGefahrSindStaerkerAlsPositiveErfahrung(GameTestHelper h) {
        var routes = routes(10, 14);
        var blocked = AdaptiveAcquisition.choose(routes, method -> method.equals("chest") ? 12 : 0,
                method -> method.equals("chest") ? 5 : 0, plan -> 0);
        h.assertTrue(blocked != null && blocked.approach().equals("mining"), "Lernen umgeht aktuelle Fehlersperre");
        var unsafe = AdaptiveAcquisition.choose(routes, method -> 0, method -> method.equals("chest") ? 5 : 0,
                plan -> plan == routes.get(1).plan() ? 150 : 0);
        h.assertTrue(unsafe != null && unsafe.approach().equals("mining"), "Lernen umgeht Gefahrensperre");
        h.succeed();
    }

    @GameTest
    public void erfolgeRehabilitierenEineFrueherSchlechteMethode(GameTestHelper h) {
        var memory = new CompanionExperienceMemory();
        for (int i = 0; i < 4; i++) memory.recordTargetOnly(new DecisionOutcome(
                Need.SUPPLY, DecisionOutcome.Reason.PLAN_FAILED, "kohle_chest", i));
        h.assertValueEqual(memory.learnedPreference(Need.SUPPLY, "kohle_chest"), -4, "Fehler fehlen");
        for (int i = 0; i < 5; i++) memory.succeeded(Need.SUPPLY, "kohle_chest");
        h.assertTrue(memory.learnedPreference(Need.SUPPLY, "kohle_chest") > 0, "Neue Erfolge koennen alte Fehler nicht ausgleichen");
        h.assertValueEqual(memory.learnedPenalty(Need.SUPPLY, "kohle_chest"), 0, "Alte API bleibt dauerhaft negativ");
        h.succeed();
    }

    @GameTest
    public void gespeicherteErfolgeBeeinflussenDieRessourcenwahl(GameTestHelper h) {
        CompanionEntity c = companion(h);
        BlockPos near = c.blockPosition().offset(4, 0, 0);
        c.memory.remember(CompanionMemory.Kind.TREE, "", h.getLevel(), near);
        c.memory.remember(CompanionMemory.Kind.ORE, "kohle", h.getLevel(), near);
        var options = List.of(new SupplyOption("holz", 16), new SupplyOption("kohle", 8));
        h.assertTrue(CompanionSituation.chooseSupply(c, options).resourceKey().equals("holz"), "Ausgangssituation unerwartet");
        for (int i = 0; i < 4; i++) c.experiences.succeeded(Need.SUPPLY, "kohle_chest");
        c.experiences.load(c.experiences.save(c.tickCount), c.tickCount);
        h.assertTrue(CompanionSituation.chooseSupply(c, options).resourceKey().equals("kohle"), "Geladene Erfolge ignoriert");
        h.assertValueEqual(c.experiences.penalty(Need.SUPPLY, c.tickCount), 0, "Test darf keinen neuen Fehler benoetigen");
        h.succeed();
    }

    private static CompanionEntity chestSupply(GameTestHelper h) {
        CompanionEntity c = companion(h);
        c.giveToInventory(new ItemStack(Items.OAK_LOG, 16));
        c.giveToInventory(new ItemStack(Items.COBBLESTONE, 32));
        var chest = new BlockPos(3, 1, 2);
        h.setBlock(chest, net.minecraft.world.level.block.Blocks.CHEST);
        var box = c.containerAt(h.getLevel(), h.absolutePos(chest));
        h.assertTrue(box != null, "Testkiste fehlt");
        box.setItem(0, new ItemStack(Items.COAL, 8));
        for (int i = 0; i < 4; i++) c.experiences.succeeded(Need.SUPPLY, "kohle_chest");
        c.experiences.load(c.experiences.save(c.tickCount), c.tickCount);
        return c;
    }

    @GameTest
    public void aktivierteKIVerwendetGeladeneRouteSchonVorDemErstenFehler(GameTestHelper h) {
        CompanionEntity c = chestSupply(h);
        CompanionNeeds.act(c, new CompanionNeeds.Score(Need.SUPPLY, 20, "Kohle"));
        h.assertValueEqual(c.experiences.penalty(Need.SUPPLY, "kohle", c.tickCount), 0, "Aktueller Fehler darf nicht noetig sein");
        TaskType expected = CompanionDecisionEngine.enabled() ? TaskType.TAKE : TaskType.GATHER;
        h.assertTrue(c.currentTask() != null && c.currentTask().type() == expected,
                "Routenwissen ist nicht korrekt an die aktivierte Vorratsplanung angeschlossen");
        h.succeed();
    }

    @GameTest
    public void geladeneKistenpraeferenzErlaubtKeinenBekanntenGefahrenort(GameTestHelper h) {
        CompanionEntity c = chestSupply(h);
        c.memory.remember(CompanionMemory.Kind.DANGER, "test", h.getLevel(), h.absolutePos(new BlockPos(3, 1, 2)));
        CompanionNeeds.act(c, new CompanionNeeds.Score(Need.SUPPLY, 20, "Kohle"));
        h.assertTrue(c.currentTask() != null && c.currentTask().type() == TaskType.GATHER,
                "Erfolgreiche Kistenhistorie ueberschreibt die aktuelle Gefahr");
        h.succeed();
    }
}
