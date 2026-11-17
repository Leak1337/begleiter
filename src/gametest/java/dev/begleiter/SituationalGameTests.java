package dev.begleiter;

import java.util.List;

import dev.begleiter.CompanionNeeds.Need;
import dev.begleiter.CompanionNeeds.Score;
import dev.begleiter.ai.CompanionDecisionEngine;
import dev.begleiter.ai.CompanionDecisionEngine.SupplyOption;
import dev.begleiter.ai.SituationalAssessment;
import dev.begleiter.ai.SituationalAssessment.Context;
import dev.begleiter.ai.StrategyEvaluator;
import net.fabricmc.fabric.api.gametest.v1.GameTest;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;

/** Also run by CI with intelligenceEnabled=false (classic ranking), in a separate game JVM. */
public class SituationalGameTests {
    private static final Score SUPPLY = new Score(Need.SUPPLY, 40, "Test");

    private static Context ready(int health, int hunger, int capacity, int tier, int condition,
                                  double distance, boolean known, boolean danger, boolean night) {
        return new Context(health, hunger, 20, capacity, 100, 8, tier, 1, condition,
                distance, known, danger, night);
    }

    private static CompanionEntity companion(GameTestHelper h) {
        CompanionEntity c = h.spawn(CompanionMod.COMPANION, new BlockPos(2, 1, 2));
        c.stay();
        c.getInventory().clearContent();
        c.setItemInHand(InteractionHand.MAIN_HAND, ItemStack.EMPTY);
        c.hunger = 20;
        c.giveToInventory(new ItemStack(Items.DIAMOND_PICKAXE));
        c.giveToInventory(new ItemStack(Items.DIAMOND_AXE));
        c.memory.clear();
        return c;
    }

    private static void knownSources(GameTestHelper h, CompanionEntity c) {
        c.memory.remember(CompanionMemory.Kind.TREE, "", h.getLevel(), c.blockPosition().offset(100, 0, 0));
        c.memory.remember(CompanionMemory.Kind.ORE, Resources.COAL.key(), h.getLevel(), c.blockPosition().offset(4, 0, 0));
    }

    @GameTest
    public void verletzungUndHungerErhoehenRisiko(GameTestHelper h) {
        var healthy = SituationalAssessment.assess(SUPPLY, ready(100, 20, 100, 3, 100, 4, true, false, false), false);
        var weak = SituationalAssessment.assess(SUPPLY, ready(30, 4, 100, 3, 100, 4, true, false, false), false);
        h.assertTrue(healthy.successChance() > weak.successChance(), "Gesundheit/Hunger ohne Einfluss auf Erfolg");
        h.assertTrue(healthy.risk() < weak.risk(), "Gesundheit/Hunger ohne Einfluss auf Risiko");
        h.assertTrue(StrategyEvaluator.score(healthy) > StrategyEvaluator.score(weak), "Schwache Verfassung wird nicht abgewertet");
        h.succeed();
    }

    @GameTest
    public void entfernungUndUnbekannteQuellenKostenMehr(GameTestHelper h) {
        var near = SituationalAssessment.assess(SUPPLY, ready(100, 20, 100, 3, 100, 4, true, false, false), false);
        var far = SituationalAssessment.assess(SUPPLY, ready(100, 20, 100, 3, 100, 100, true, false, false), false);
        var unknown = SituationalAssessment.assess(SUPPLY, ready(100, 20, 100, 3, 100, 0, false, false, false), false);
        h.assertTrue(near.cost() < far.cost() && near.successChance() > far.successChance(), "Entfernung wird ignoriert");
        h.assertTrue(unknown.cost() > near.cost() && unknown.successChance() < near.successChance(),
                "Unbekannte Quelle wird wie eine kostenlose nahe Quelle bewertet");
        h.succeed();
    }

    @GameTest
    public void fehlendesOderVerschlissenesWerkzeugWirdBeruecksichtigt(GameTestHelper h) {
        var good = SituationalAssessment.assess(SUPPLY, ready(100, 20, 100, 3, 100, 4, true, false, false), false);
        var missing = SituationalAssessment.assess(SUPPLY, ready(100, 20, 100, 0, 100, 4, true, false, false), false);
        var worn = SituationalAssessment.assess(SUPPLY, ready(100, 20, 100, 3, 1, 4, true, false, false), false);
        h.assertTrue(missing.successChance() < good.successChance() && missing.cost() > good.cost(), "Werkzeug fehlt ohne Folgen");
        h.assertTrue(worn.successChance() < good.successChance() && worn.risk() > good.risk(), "Werkzeugverschleiss ignoriert");
        h.succeed();
    }

    @GameTest
    public void gefahrUndNachtSenkenDenNutzen(GameTestHelper h) {
        var safe = SituationalAssessment.assess(SUPPLY, ready(100, 20, 100, 3, 100, 4, true, false, false), false);
        var danger = SituationalAssessment.assess(SUPPLY, ready(100, 20, 100, 3, 100, 4, true, true, true), false);
        h.assertTrue(danger.risk() > safe.risk() && danger.successChance() < safe.successChance(), "Bekannte Gefahr/Nacht ignoriert");
        h.assertTrue(StrategyEvaluator.score(danger) < StrategyEvaluator.score(safe), "Gefahr erhoeht Nutzen");
        h.succeed();
    }

    @GameTest
    public void inventarkapazitaetIstNichtNurAnzahlLeererSlots(GameTestHelper h) {
        CompanionEntity c = companion(h);
        for (int i = 0; i < c.getInventory().getContainerSize(); i++)
            c.getInventory().setItem(i, new ItemStack(Items.DIRT, 64));
        var option = new SupplyOption("kohle", 8);
        var full = CompanionSituation.supplyCandidate(c, SUPPLY, option);
        c.getInventory().setItem(0, new ItemStack(Items.COAL, 56));
        var merging = CompanionSituation.supplyCandidate(c, SUPPLY, option);
        h.assertValueEqual(c.usedSlots(), 36, "Inventar muss auch mit Platz zum Stapeln voll aussehen");
        h.assertTrue(merging.successChance() > full.successChance(), "Freie Teilstapel nicht erkannt");
        h.succeed();
    }

    @GameTest
    public void mengenBeruecksichtigenDenTatsaechlichenFehlbestand(GameTestHelper h) {
        CompanionEntity c = companion(h);
        c.giveToInventory(new ItemStack(Items.OAK_LOG, 15));
        c.giveToInventory(new ItemStack(Items.COBBLESTONE, 31));
        var options = CompanionSituation.supplyOptions(c);
        h.assertTrue(options.stream().anyMatch(o -> o.resourceKey().equals("stein") && o.missing() == 1),
                "Steinbedarf ist pauschal 32 statt Restbedarf");
        var wood = CompanionSituation.supplyCandidate(c, SUPPLY, new SupplyOption("holz", 1));
        var coal = CompanionSituation.supplyCandidate(c, SUPPLY, new SupplyOption("kohle", 8));
        h.assertTrue(coal.benefit() > wood.benefit(), "Leerer Vorrat hat keinen hoeheren Nutzen");
        h.succeed();
    }

    @GameTest
    public void fundorteUndFehlerBeeinflussenDieRessourcenwahl(GameTestHelper h) {
        CompanionEntity c = companion(h);
        knownSources(h, c);
        var options = List.of(new SupplyOption("holz", 16), new SupplyOption("kohle", 8));
        h.assertTrue(CompanionSituation.chooseSupply(c, options).resourceKey().equals("kohle"),
                "Nahe Kohle wird gegenueber fernem Holz nicht bevorzugt");
        c.experiences.record(new dev.begleiter.ai.DecisionOutcome(Need.SUPPLY,
                dev.begleiter.ai.DecisionOutcome.Reason.UNREACHABLE, "kohle", c.tickCount));
        h.assertTrue(CompanionSituation.chooseSupply(c, options).resourceKey().equals("holz"),
                "Aktueller Fehlschlag wird trotz Situation ignoriert");
        h.succeed();
    }

    @GameTest
    public void aktivierterModusAendertRankingUndTatsaechlichenSammelauftrag(GameTestHelper h) {
        CompanionEntity c = companion(h);
        knownSources(h, c);
        c.scaffold.add(c.blockPosition().offset(100, 0, 0));
        var order = new Score(Need.ORDER, 40, "Geruest");
        var supply = new Score(Need.SUPPLY, 40, "Vorrat");
        var legacy = List.of(order, supply);
        var ranked = CompanionDecisionEngine.rank(c, legacy);
        h.assertTrue(ranked.getFirst().need() == (CompanionDecisionEngine.enabled() ? Need.SUPPLY : Need.ORDER),
                "Ranking passt nicht zum Aktivierungsmodus");
        c.scaffold.clear();
        CompanionNeeds.act(c, supply);
        String expected = CompanionDecisionEngine.enabled() ? Resources.COAL.key() : Resources.WOOD.key();
        h.assertTrue(c.currentTask() != null && c.currentTask().spec() instanceof dev.begleiter.task.TaskSpec.ResourceGoal g
                && expected.equals(g.resource()),
                "Tatsaechlicher Sammelauftrag nutzt nicht die bewertete Ressource");
        h.succeed();
    }

    @GameTest
    public void geschuetzteBeduerfnisseBleibenBarrieren(GameTestHelper h) {
        CompanionEntity c = companion(h);
        knownSources(h, c);
        c.scaffold.add(c.blockPosition().offset(100, 0, 0));
        var ranked = List.of(new Score(Need.ORDER, 42, "Ordnung"), new Score(Need.SURVIVAL, 41, "Schutz"),
                new Score(Need.SUPPLY, 40, "Vorrat"));
        h.assertTrue(CompanionDecisionEngine.rank(c, ranked).equals(ranked), "Situationsbewertung ueberspringt geschuetztes Ziel");
        h.succeed();
    }

    @GameTest
    public void blockierteOptionenUndUngueltigeWerteBleibenBegrenzt(GameTestHelper h) {
        var options = List.of(new SupplyOption("holz", 16), new SupplyOption("kohle", 8));
        h.assertTrue(CompanionDecisionEngine.chooseSupply(options, o -> 24, o -> 100) == null,
                "Wiederholt gescheiterte Ziele werden nicht blockiert");
        h.assertTrue(CompanionDecisionEngine.chooseSupply(options, o -> 0, o -> Double.NaN) == null,
                "Ungueltiger Nutzwert wird ausgewaehlt");
        var invalid = new Context(-10, 200, -5, -1, 900, Integer.MAX_VALUE, -1, 100, 1000,
                Double.NaN, true, true, true);
        var candidate = SituationalAssessment.assess(SUPPLY, invalid, false);
        h.assertTrue(candidate.successChance() >= 0 && candidate.successChance() <= 100
                && candidate.cost() >= 0 && candidate.cost() <= 100
                && candidate.risk() >= 0 && candidate.risk() <= 100, "Situationswerte ausserhalb der Grenzen");
        h.succeed();
    }
}
