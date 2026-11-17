package dev.begleiter;

import dev.begleiter.CompanionEntity.Task;
import dev.begleiter.CompanionEntity.TaskType;
import dev.begleiter.CompanionNeeds.Need;
import dev.begleiter.CompanionPlanFeedback.Status;
import dev.begleiter.ai.DecisionOutcome;
import net.fabricmc.fabric.api.gametest.v1.GameTest;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;

public class PlanFeedbackGameTests {
    private static CompanionEntity companion(GameTestHelper h) {
        for (int x = 0; x < 8; x++)
            for (int z = 0; z < 8; z++) h.setBlock(x, 0, z, net.minecraft.world.level.block.Blocks.STONE);
        CompanionEntity c = h.spawn(CompanionMod.COMPANION, new BlockPos(2, 1, 2));
        c.stay();
        c.getInventory().clearContent();
        c.setItemInHand(InteractionHand.MAIN_HAND, ItemStack.EMPTY);
        return c;
    }

    private static CompanionEntity supply(GameTestHelper h) {
        CompanionEntity c = companion(h);
        c.giveToInventory(new ItemStack(Items.COAL, 3));
        c.goal = Need.SUPPLY;
        c.activeSupplyResource = "kohle";
        c.activeSupplyApproach = "mining";
        c.addTask(Task.gather("kohle", 5));
        c.addTask(Task.collect(false));
        c.planFeedback.begin(c, Need.SUPPLY, null);
        return c;
    }

    private static void drain(CompanionEntity c) {
        while (c.hasTasks()) c.finishTask();
        c.planFeedback.completeIfIdle(c);
    }

    @GameTest
    public void ganzerPlanLerntErstNachLetzterAufgabe(GameTestHelper h) {
        CompanionEntity c = supply(h);
        c.giveToInventory(new ItemStack(Items.COAL, 5));
        c.finishTask();
        c.planFeedback.completeIfIdle(c);
        h.assertTrue(c.planFeedback.active() && c.planFeedback.last() == null,
                "Zwischenschritt wurde als ganzer Plan gewertet");
        c.tickCount += 42;
        c.setHealth(c.getHealth() - 2);
        drain(c);
        var result = c.planFeedback.last();
        h.assertTrue(result.status() == Status.SUCCESS, "Zielbestand nicht als Erfolg erkannt");
        h.assertValueEqual(result.remainingBefore(), 5, "Falscher Anfangsbedarf");
        h.assertValueEqual(result.remainingAfter(), 0, "Falscher Restbedarf");
        h.assertValueEqual(result.durationTicks(), 42, "Plandauer fehlt");
        h.assertTrue(result.healthChange() == -2, "Gesundheitsaenderung fehlt");
        h.assertTrue(c.activeSupplyResource == null && c.activeSupplyApproach == null, "Alter Vorratskontext bleibt aktiv");
        c.planFeedback.completeIfIdle(c);
        h.assertTrue(c.planFeedback.last() == result, "Plan wurde zweimal gewertet");
        h.succeed();
    }

    @GameTest
    public void teilerfolgBestraftNichtDasGanzeZiel(GameTestHelper h) {
        CompanionEntity c = supply(h);
        c.giveToInventory(new ItemStack(Items.COAL, 2));
        c.reportAutonomousFailure(DecisionOutcome.Reason.UNREACHABLE, c.currentTask());
        drain(c);
        h.assertTrue(c.planFeedback.last().status() == Status.PARTIAL, "Teilfortschritt verloren");
        h.assertValueEqual(c.experiences.penalty(Need.SUPPLY, "kohle", c.tickCount), 0,
                "Teilerfolg wurde als kompletter Fehlschlag gelernt");
        h.assertTrue(c.experiences.penalty(Need.SUPPLY, "kohle_mining", c.tickCount) > 0,
                "Tatsaechlich gescheiterte Methode wurde vergessen");
        h.succeed();
    }

    @GameTest
    public void wiederholteSchrittfehlerZaehlenNurEinmal(GameTestHelper h) {
        CompanionEntity c = supply(h);
        c.reportAutonomousFailure(DecisionOutcome.Reason.STALLED, c.currentTask());
        c.reportAutonomousFailure(DecisionOutcome.Reason.UNREACHABLE, c.currentTask());
        h.assertValueEqual(c.experiences.penalty(Need.SUPPLY, c.tickCount), 0,
                "Plan wurde schon waehrend der Ausfuehrung gelernt");
        drain(c);
        h.assertTrue(c.planFeedback.last().status() == Status.FAILURE, "Kein Fehlschlag ohne Fortschritt");
        h.assertTrue(c.planFeedback.last().reason() == DecisionOutcome.Reason.UNREACHABLE, "Konkrete Ursache ersetzt allgemeines Feststecken nicht");
        h.assertValueEqual(c.experiences.penalty(Need.SUPPLY, c.tickCount), 12, "Ziel doppelt bestraft");
        h.assertValueEqual(c.experiences.penalty(Need.SUPPLY, "kohle_mining", c.tickCount), 12,
                "Methode doppelt bestraft");
        h.succeed();
    }

    @GameTest
    public void spielerabbruchBleibtAuchNachSchrittfehlerNeutral(GameTestHelper h) {
        CompanionEntity c = supply(h);
        c.reportAutonomousFailure(DecisionOutcome.Reason.STALLED, c.currentTask());
        c.cancelAll();
        h.assertTrue(c.planFeedback.last().status() == Status.CANCELLED, "Abbruch nicht erkannt");
        h.assertValueEqual(c.experiences.penalty(Need.SUPPLY, c.tickCount), 0, "Abbruch bestraft");
        h.assertValueEqual(c.experiences.learnedProfiles(), 0, "Abbruch hat Langzeitprofil veraendert");
        h.succeed();
    }

    @GameTest
    public void leereAufgabenlisteAlleinIstKeinErfolg(GameTestHelper h) {
        CompanionEntity c = supply(h);
        drain(c);
        h.assertTrue(c.planFeedback.last().status() == Status.FAILURE, "Leere Queue als Erfolg gelernt");
        h.assertTrue(c.planFeedback.last().reason() == DecisionOutcome.Reason.PLAN_FAILED,
                "Fehlender Zielzustand braucht einen Fehlergrund");
        h.succeed();
    }

    @GameTest
    public void fehlerabbruchWirdVorCancelAllAbgeschlossen(GameTestHelper h) {
        CompanionEntity c = supply(h);
        c.reportAutonomousFailure(DecisionOutcome.Reason.PLAN_FAILED, c.currentTask());
        c.planFeedback.finish(c, false);
        var result = c.planFeedback.last();
        c.cancelAll();
        h.assertTrue(result.status() == Status.FAILURE && c.planFeedback.last() == result,
                "Fehlerabbruch wurde nachtraeglich als Spielerabbruch gewertet");
        h.assertValueEqual(c.experiences.penalty(Need.SUPPLY, c.tickCount), 12, "Fehlschlag nicht einmal erfasst");
        h.succeed();
    }

    @GameTest
    public void beduerfnisPlanungStartetMessungFuerGesamteAusruestung(GameTestHelper h) {
        CompanionEntity c = companion(h);
        CompanionNeeds.decide(c);
        h.assertTrue(c.goal == Need.GEAR && c.planFeedback.active(), "Beduerfnisplanung startet keine Messung");
        c.giveToInventory(new ItemStack(Items.WOODEN_PICKAXE));
        drain(c);
        h.assertTrue(c.planFeedback.last().status() == Status.PARTIAL,
                "Spitzhacke allein darf nicht als vollstaendige Werkzeugausruestung gelten");
        h.succeed();
    }

    @GameTest
    public void ausruestungsplanBestaetigtTatsaechlicheWerkzeuge(GameTestHelper h) {
        CompanionEntity c = companion(h);
        CompanionNeeds.decide(c);
        for (var item : new net.minecraft.world.item.Item[]{Items.WOODEN_PICKAXE, Items.WOODEN_AXE,
                Items.WOODEN_SWORD, Items.WOODEN_SHOVEL}) c.giveToInventory(new ItemStack(item));
        drain(c);
        h.assertTrue(c.planFeedback.last().status() == Status.SUCCESS, "Vollstaendige Werkzeuge nicht erkannt");
        h.succeed();
    }

    @GameTest
    public void spielerauftraegeUndBewusstesErkundenErzeugenKeinenFehler(GameTestHelper h) {
        CompanionEntity c = companion(h);
        c.addTask(Task.collect(false));
        c.reportAutonomousFailure(DecisionOutcome.Reason.STALLED, c.currentTask());
        drain(c);
        h.assertTrue(c.planFeedback.last() == null && c.experiences.learnedProfiles() == 0,
                "Spielerauftrag beeinflusst autonomes Lernen");
        c.goal = Need.SUPPLY;
        c.addTask(Task.moveTo(c.blockPosition().offset(8, 0, 0)));
        c.planFeedback.begin(c, Need.SUPPLY, null);
        c.reportAutonomousFailure(DecisionOutcome.Reason.UNREACHABLE, c.currentTask());
        drain(c);
        h.assertTrue(c.planFeedback.last() == null, "Erkunden wird als gescheiterte Versorgung gewertet");
        h.assertValueEqual(c.experiences.penalty(Need.SUPPLY, c.tickCount), 0,
                "Nicht erreichbares Erkundungsziel erzeugt falschen Versorgungsfehler");
        h.succeed();
    }

    @GameTest(maxTicks = 200)
    public void ausgefuehrteKistenversorgungWirdImEntityTickAbgeschlossen(GameTestHelper h) {
        CompanionEntity c = companion(h);
        c.giveToInventory(new ItemStack(Items.COAL, 3));
        BlockPos chest = new BlockPos(3, 1, 2);
        h.setBlock(chest, net.minecraft.world.level.block.Blocks.CHEST);
        var box = c.containerAt(h.getLevel(), h.absolutePos(chest));
        h.assertTrue(box != null, "Testkiste fehlt");
        box.setItem(0, new ItemStack(Items.COAL, 5));
        c.goal = Need.SUPPLY;
        c.activeSupplyResource = "kohle";
        c.activeSupplyApproach = "chest";
        c.addTask(Task.take(h.absolutePos(chest), "coal", 5));
        c.planFeedback.begin(c, Need.SUPPLY, null);
        h.succeedWhen(() -> {
            h.assertTrue(c.planFeedback.last() != null, "Ausgefuehrter Plan wird nicht abgeschlossen");
            h.assertTrue(c.planFeedback.last().status() == Status.SUCCESS, "Erfolgreiche Kistenversorgung falsch gewertet");
            h.assertValueEqual(CompanionCraft.count(c, "coal"), 8, "Zielbestand fehlt");
        });
    }

    @GameTest
    public void rueckzugVersprichtSchutzStattSofortigerHeilung(GameTestHelper h) {
        CompanionEntity c = companion(h);
        var memory = c.saveMemory();
        memory.putLong("HouseOrigin", h.absolutePos(new BlockPos(2, 0, 2)).asLong());
        memory.putBoolean("HouseBuilt", true);
        c.loadMemory(memory);
        c.setHealth(2);
        c.goal = Need.SURVIVAL;
        var objective = CompanionPlanFeedback.prepare(c, Need.SURVIVAL);
        c.addTask(Task.moveTo(c.houseInside()));
        c.planFeedback.begin(c, Need.SURVIVAL, objective);
        BlockPos inside = c.houseInside();
        c.snapTo(inside.getX() + .5, inside.getY(), inside.getZ() + .5, 0, 0);
        drain(c);
        h.assertTrue(c.planFeedback.last().status() == Status.SUCCESS,
                "Erfolgreicher Rueckzug wurde wegen fehlender Sofortheilung bestraft");
        h.succeed();
    }

    @GameTest(maxTicks = 200)
    public void leereKisteFuehrtZuEinmaligemPlanfehler(GameTestHelper h) {
        CompanionEntity c = companion(h);
        BlockPos chest = new BlockPos(3, 1, 2);
        h.setBlock(chest, net.minecraft.world.level.block.Blocks.CHEST);
        c.goal = Need.SUPPLY;
        c.activeSupplyResource = "kohle";
        c.activeSupplyApproach = "chest";
        c.addTask(Task.take(h.absolutePos(chest), "coal", 8));
        c.planFeedback.begin(c, Need.SUPPLY, null);
        h.succeedWhen(() -> {
            h.assertTrue(c.planFeedback.last() != null, "Leere Kiste wird nicht abgeschlossen");
            h.assertTrue(c.planFeedback.last().status() == Status.FAILURE, "Leere Kiste als Abbruch/Erfolg gewertet");
            h.assertTrue(c.planFeedback.last().reason() == DecisionOutcome.Reason.SOURCE_EMPTY, "Leere Quelle nicht erkannt");
            h.assertTrue(c.experiences.sources.blocked(CompanionSources.key(c, Need.SUPPLY, Items.COAL,
                    h.absolutePos(chest)), c.tickCount), "Konkrete Kiste nicht gesperrt");
            h.assertValueEqual(c.experiences.penalty(Need.SUPPLY, c.tickCount), 0, "Eine leere Kiste bestraft alle Vorratsziele");
        });
    }
}
