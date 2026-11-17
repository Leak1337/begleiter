package dev.begleiter;

import dev.begleiter.ai.StrategyCandidate;
import dev.begleiter.ai.StrategyEvaluator;
import net.fabricmc.fabric.api.gametest.v1.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;

/** Reine Bewertungstests; erzeugt keinen Begleiter und verändert keinen Zustand. */
public class IntelligenceGameTests {
    @GameTest
    public void strategieBewertungIstDeterministisch(GameTestHelper h) {
        var need = new CompanionNeeds.Score(CompanionNeeds.Need.SUPPLY, 50, "Test");
        var candidate = new StrategyCandidate(need, 70, 80, 50, 10, 30);
        double expected = 0.35 * 50 + 0.25 * 70 + 0.15 * 80 + 0.10 * 50 - 0.10 * 10 - 0.05 * 30;
        h.assertTrue(Math.abs(StrategyEvaluator.score(candidate) - expected) < 0.000001, "Score stimmt nicht");
        h.assertTrue(StrategyEvaluator.score(candidate) == StrategyEvaluator.score(candidate), "Score nicht deterministisch");
        h.succeed();
    }

    @GameTest
    public void strategiefaktorGrenzenUndRisiko(GameTestHelper h) {
        var need = new CompanionNeeds.Score(CompanionNeeds.Need.ORDER, 70, "Test");
        var safe = new StrategyCandidate(need, 60, 75, 50, 0, 20);
        var dangerous = new StrategyCandidate(need, 60, 75, 50, 100, 20);
        h.assertTrue(StrategyEvaluator.score(safe) > StrategyEvaluator.score(dangerous), "Risiko wird nicht bestraft");
        var clamped = new StrategyCandidate(need, 150, -5, 1000, -10, 200);
        h.assertTrue(clamped.benefit() == 100 && clamped.successChance() == 0
                && clamped.compatibility() == 100 && clamped.risk() == 0 && clamped.cost() == 100,
                "Kandidatenwerte nicht begrenzt");
        h.succeed();
    }
    @GameTest
    public void erfahrungsStrafeVerblasst(GameTestHelper h) {
        var memory = new dev.begleiter.ai.CompanionExperienceMemory();
        var need = CompanionNeeds.Need.SUPPLY;
        memory.failed(need, 100);
        int first = memory.penalty(need, 100);
        h.assertTrue(first > 0, "Erster Fehlschlag muss Konsequenz haben");
        h.assertTrue(memory.penalty(need, 3100) < first, "Strafe muss verblassen");
        h.assertTrue(memory.penalty(need, 6100) == 0, "Strafe darf nicht dauerhaft sein");
        memory.failed(need, 6200);
        memory.succeeded(need);
        h.assertTrue(memory.penalty(need, 6200) == 0, "Erfolg muss Fehler zurücksetzen");
        h.succeed();
    }

    @GameTest
    public void sicherheitsZieleWerdenNichtUebersprungen(GameTestHelper h) {
        var order = new CompanionNeeds.Score(CompanionNeeds.Need.ORDER, 70, "Ordnung");
        var survival = new CompanionNeeds.Score(CompanionNeeds.Need.SURVIVAL, 65, "Gefahr");
        var supply = new CompanionNeeds.Score(CompanionNeeds.Need.SUPPLY, 40, "Vorrat");
        var ranked = dev.begleiter.ai.CompanionDecisionEngine.reorderEligible(
                java.util.List.of(order, survival, supply),
                score -> score.need() == CompanionNeeds.Need.SUPPLY ? 100 : 0);
        h.assertTrue(ranked.equals(java.util.List.of(order, survival, supply)),
                "Die Alternative darf ein geschuetztes Beduerfnis nicht ueberholen");

        var adjacent = dev.begleiter.ai.CompanionDecisionEngine.reorderEligible(
                java.util.List.of(order, supply, survival),
                score -> score.need() == CompanionNeeds.Need.SUPPLY ? 100 : 0);
        h.assertTrue(adjacent.equals(java.util.List.of(supply, order, survival)),
                "Benachbarte unkritische Beduerfnisse duerfen umsortiert werden");
        h.succeed();
    }

    @GameTest
    public void alternativeVorratsSucheBeiFehler(GameTestHelper h) {
        var wood = new dev.begleiter.ai.CompanionDecisionEngine.SupplyOption("holz", 16);
        var stone = new dev.begleiter.ai.CompanionDecisionEngine.SupplyOption("stein", 32);
        var choices = java.util.List.of(wood, stone);
        var first = dev.begleiter.ai.CompanionDecisionEngine.chooseSupply(choices, o -> 0);
        h.assertTrue(wood.equals(first), "Ohne Fehler muss Holz Vorrang behalten");

        var second = dev.begleiter.ai.CompanionDecisionEngine.chooseSupply(choices,
                o -> o.resourceKey().equals("holz") ? 12 : 0);
        h.assertTrue(stone.equals(second), "Nach Holz-Fehlschlag soll Stein gewaehlt werden");

        var none = dev.begleiter.ai.CompanionDecisionEngine.chooseSupply(choices, o -> 30);
        h.assertTrue(none == null, "Bei wiederholtem Scheitern soll neu erkundet werden");
        h.succeed();
    }

    @GameTest
    public void erfahrungsFehlerHatZielUndGrund(GameTestHelper h) {
        var experience = new dev.begleiter.ai.CompanionExperienceMemory();
        var need = CompanionNeeds.Need.SUPPLY;
        experience.record(new dev.begleiter.ai.DecisionOutcome(
                need, dev.begleiter.ai.DecisionOutcome.Reason.UNREACHABLE, "holz", 100));
        h.assertTrue(experience.penalty(need, "holz", 100) > 0, "Holzfehler fehlt");
        h.assertTrue(experience.penalty(need, "stein", 100) == 0, "Fehler auf fremdes Ziel uebertragen");
        h.assertTrue(experience.lastReason(need, "holz")
                == dev.begleiter.ai.DecisionOutcome.Reason.UNREACHABLE, "Fehlerursache fehlt");
        experience.succeeded(need, "holz");
        h.assertTrue(experience.penalty(need, "holz", 100) == 0, "Erfolg muss Fehler loeschen");
        h.succeed();
    }

    @GameTest
    public void alternativeBeschaffungWechseltQuelle(GameTestHelper h) {
        var mine = new CompanionPlanner.Plan(java.util.List.of(
                CompanionEntity.Task.gather("kohle", 8)),
                10, java.util.List.of("Kohle abbauen"));
        var chest = new CompanionPlanner.Plan(java.util.List.of(
                CompanionEntity.Task.take(new net.minecraft.core.BlockPos(2, 1, 2), "coal", 8)),
                24, java.util.List.of("Aus Kiste nehmen"));
        var alternatives = java.util.List.of(
                new CompanionPlanner.Alternative(mine, CompanionPlanner.RoutePolicy.ANY),
                new CompanionPlanner.Alternative(chest, CompanionPlanner.RoutePolicy.AVOID_MINING));
        var normal = dev.begleiter.ai.AdaptiveAcquisition.choose(alternatives, route -> 0);
        h.assertTrue(normal != null && "mining".equals(normal.approach()),
                "Ohne Fehler soll der guenstigere Bergbau bevorzugt werden");
        var rerouted = dev.begleiter.ai.AdaptiveAcquisition.choose(
                alternatives, route -> "mining".equals(route) ? 12 : 0);
        h.assertTrue(rerouted != null && "chest".equals(rerouted.approach()),
                "Nach Mining-Fehler muss Kiste als Alternative moeglich sein");
        h.assertTrue(dev.begleiter.ai.AdaptiveAcquisition.choose(alternatives, route -> 12) == null,
                "Bei Blockade aller Wege muss die KI erkunden statt dauernd neu versuchen");
        var safe = dev.begleiter.ai.AdaptiveAcquisition.choose(alternatives, route -> 0,
                plan -> plan == chest ? 150 : 0);
        h.assertTrue(safe != null && "mining".equals(safe.approach()),
                "Gefaehrlicher Kistenweg muss aussortiert werden");
        h.succeed();
    }

    @GameTest
    public void zielUndBeschaffungsMethodeBleibenGetrennt(GameTestHelper h) {
        var memory = new dev.begleiter.ai.CompanionExperienceMemory();
        var need = CompanionNeeds.Need.SUPPLY;
        memory.record(new dev.begleiter.ai.DecisionOutcome(
                need, dev.begleiter.ai.DecisionOutcome.Reason.STALLED, "kohle", 100));
        memory.recordTargetOnly(new dev.begleiter.ai.DecisionOutcome(
                need, dev.begleiter.ai.DecisionOutcome.Reason.STALLED, "kohle_mining", 100));
        h.assertTrue(memory.penalty(need, "kohle", 100) > 0, "Zielstrafe fehlt");
        h.assertTrue(memory.penalty(need, "kohle_mining", 100) > 0, "Methode wurde nicht erfasst");
        h.assertTrue(memory.penalty(need, "kohle_chest", 100) == 0,
                "Eine unbenutzte Alternativroute darf keine Strafe haben");
        memory.succeeded(need, "kohle_chest");
        h.assertTrue(memory.penalty(need, "kohle_mining", 100) > 0,
                "Erfolg am anderen Weg darf alte Fehler nicht irrtuemlich ueberschreiben");
        h.succeed();
    }

    @GameTest
    public void zielplanerFindetZweiWegeFuerKohle(GameTestHelper h) {
        for (int x = 0; x < 8; x++)
            for (int z = 0; z < 8; z++) h.setBlock(x, 0, z, net.minecraft.world.level.block.Blocks.STONE);
        CompanionEntity c = h.spawn(CompanionMod.COMPANION, new net.minecraft.core.BlockPos(2, 1, 2));
        c.setPersistenceRequired();
        c.stay();
        c.giveToInventory(new net.minecraft.world.item.ItemStack(net.minecraft.world.item.Items.STONE_PICKAXE));
        var chestPos = new net.minecraft.core.BlockPos(5, 1, 5);
        h.setBlock(chestPos, net.minecraft.world.level.block.Blocks.CHEST);
        var box = c.containerAt(h.getLevel(), h.absolutePos(chestPos));
        h.assertTrue(box != null, "Test-Kiste fehlt");
        box.setItem(0, new net.minecraft.world.item.ItemStack(net.minecraft.world.item.Items.COAL, 8));

        var alternatives = CompanionPlanner.alternatives(c, net.minecraft.world.item.Items.COAL, 8);
        h.assertTrue(alternatives.stream().anyMatch(a -> a.plan().tasks().stream()
                .anyMatch(task -> task.type() == CompanionEntity.TaskType.TAKE)),
                "Planner findet Kistenquelle nicht");
        h.assertTrue(alternatives.stream().anyMatch(a -> a.plan().tasks().stream()
                .anyMatch(task -> task.type() == CompanionEntity.TaskType.GATHER)),
                "Planner findet Bergbaualternative nicht");
        h.succeed();
    }

    @GameTest
    public void erfahrungUeberlebtSpeichernMitRestzeit(GameTestHelper h) {
        var memory = new dev.begleiter.ai.CompanionExperienceMemory();
        var need = CompanionNeeds.Need.SUPPLY;
        memory.record(new dev.begleiter.ai.DecisionOutcome(
                need, dev.begleiter.ai.DecisionOutcome.Reason.UNREACHABLE, "kohle", 200));
        memory.recordTargetOnly(new dev.begleiter.ai.DecisionOutcome(
                need, dev.begleiter.ai.DecisionOutcome.Reason.UNREACHABLE, "kohle_mining", 200));
        var data = memory.save(2200); // 4000 Ticks bleiben
        var afterLoad = new dev.begleiter.ai.CompanionExperienceMemory();
        afterLoad.load(data, 0); // neue Entity beginnt wieder bei Tick 0
        h.assertTrue(afterLoad.penalty(need, "kohle_mining", 0) > 0,
                "Erfahrung beim Speichern verloren");
        h.assertTrue(afterLoad.penalty(need, "kohle", 0) > 0,
                "Gesamtes Beschaffungsziel vergessen");
        h.assertTrue(afterLoad.lastReason(need, "kohle_mining")
                == dev.begleiter.ai.DecisionOutcome.Reason.UNREACHABLE,
                "Grund wurde nicht gespeichert");
        h.assertTrue(afterLoad.penalty(need, "kohle_mining", 4000) == 0,
                "Abgelaufene Erfahrung darf nach Neustart nicht permanent werden");
        h.assertTrue(afterLoad.rememberedTargets() == 2, "Zielgebundene Erfahrungen fehlen");
        h.succeed();
    }

    @GameTest
    public void erfahrungAeltererSpeicherstandBleibtKompatibel(GameTestHelper h) {
        var memory = new dev.begleiter.ai.CompanionExperienceMemory();
        var need = CompanionNeeds.Need.SUPPLY;
        memory.record(new dev.begleiter.ai.DecisionOutcome(
                need, dev.begleiter.ai.DecisionOutcome.Reason.STALLED, "holz", 80));
        memory.load(new net.minecraft.nbt.CompoundTag(), 0);
        h.assertTrue(memory.penalty(need, "holz", 0) == 0,
                "Altes Save ohne Experience muss problemlos initialisieren");
        var corrupt = new net.minecraft.nbt.CompoundTag();
        corrupt.putInt("Version", 1);
        var unknown = new net.minecraft.nbt.ListTag();
        var bad = new net.minecraft.nbt.CompoundTag();
        bad.putString("Key", "INVALID:holz_mining");
        bad.putInt("Count", 999);
        bad.putInt("Remaining", 999999);
        unknown.add(bad);
        corrupt.put("Targets", unknown);
        memory.load(corrupt, 0);
        h.assertTrue(memory.rememberedTargets() == 0, "Unbekannte NBT-Daten nicht abgefangen");
        h.succeed();
    }

    @GameTest
    public void erfahrungImKernUndEntitySave(GameTestHelper h) {
        CompanionEntity c = h.spawn(CompanionMod.COMPANION,
                new net.minecraft.core.BlockPos(2, 1, 2));
        c.stay();
        c.experiences.record(new dev.begleiter.ai.DecisionOutcome(
                CompanionNeeds.Need.SUPPLY,
                dev.begleiter.ai.DecisionOutcome.Reason.PLAN_FAILED,
                "stein", c.tickCount));

        var tag = c.saveMemory();
        CompanionEntity revived = CompanionMod.COMPANION.create(
                h.getLevel(), net.minecraft.world.entity.EntitySpawnReason.LOAD);
        h.assertTrue(revived != null, "Kein Begleiter fuer Kern-Test");
        revived.loadMemory(tag);
        h.assertTrue(revived.experiences.penalty(CompanionNeeds.Need.SUPPLY, "stein", revived.tickCount) > 0,
                "Lernzustand im Kern verloren");

        net.minecraft.world.level.storage.TagValueOutput out =
                net.minecraft.world.level.storage.TagValueOutput.createWithContext(
                        net.minecraft.util.ProblemReporter.DISCARDING, h.getLevel().registryAccess());
        c.saveWithoutId(out);
        CompanionEntity loaded = CompanionMod.COMPANION.create(
                h.getLevel(), net.minecraft.world.entity.EntitySpawnReason.LOAD);
        h.assertTrue(loaded != null, "Kein Begleiter fuer Entity-Test");
        loaded.load(net.minecraft.world.level.storage.TagValueInput.create(
                net.minecraft.util.ProblemReporter.DISCARDING,
                h.getLevel().registryAccess(), out.buildResult()));
        h.assertTrue(loaded.experiences.penalty(CompanionNeeds.Need.SUPPLY, "stein", loaded.tickCount) > 0,
                "Lernzustand beim Entity-Laden verloren");
        h.succeed();
    }

    @GameTest
    public void langfristigeMethodenErfahrungBleibtNachNeustart(GameTestHelper h) {
        var memory = new dev.begleiter.ai.CompanionExperienceMemory();
        var need = CompanionNeeds.Need.SUPPLY;
        for (int i = 0; i < 4; i++)
            memory.recordTargetOnly(new dev.begleiter.ai.DecisionOutcome(
                    need, dev.begleiter.ai.DecisionOutcome.Reason.PLAN_FAILED,
                    "kohle_chest", 100 + i));
        h.assertTrue(memory.learnedPenalty(need, "kohle_chest") == 4,
                "Methode merkt sich nicht wiederholte Fehler");
        var saved = memory.save(6100); // Kurzfristiger Fehler ist abgelaufen.
        var loaded = new dev.begleiter.ai.CompanionExperienceMemory();
        loaded.load(saved, 0);
        h.assertTrue(loaded.penalty(need, "kohle_chest", 0) == 0,
                "Abgelaufene Strafzeit darf nicht wiederhergestellt werden");
        h.assertTrue(loaded.learnedPenalty(need, "kohle_chest") == 4,
                "Langfristige Methodenerfahrung ging verloren");
        loaded.succeeded(need, "kohle_chest");
        h.assertTrue(loaded.learnedPenalty(need, "kohle_chest") == 3,
                "Erfolg soll Methode wieder rehabilitieren");
        h.assertTrue(loaded.learnedPenalty(need, "kohle_mining") == 0,
                "Lernen darf sich nicht auf andere Beschaffungsmethode auswirken");
        h.succeed();
    }

    @GameTest
    public void fehlerUrsacheStammtAusTatsaechlicherAufgabe(GameTestHelper h) {
        var mining = CompanionEntity.Task.gather("eisen", 4);
        var chest = CompanionEntity.Task.take(new net.minecraft.core.BlockPos(1, 1, 1), "iron_ingot", 4);
        var crafting = CompanionEntity.Task.craft("iron_pickaxe", 1);
        var travel = CompanionEntity.Task.moveTo(new net.minecraft.core.BlockPos(4, 1, 4), 1);

        h.assertValueEqual(dev.begleiter.ai.AdaptiveAcquisition.failedApproach(mining, "chest"),
                "mining", "Minenfehler darf Kistensuche nicht bestrafen");
        h.assertValueEqual(dev.begleiter.ai.AdaptiveAcquisition.failedApproach(chest, "mining"),
                "chest", "Kistenfehler darf Bergbau nicht bestrafen");
        h.assertValueEqual(dev.begleiter.ai.AdaptiveAcquisition.failedApproach(crafting, "mining"),
                "crafting", "Craftingfehler muss der Herstellung zugeordnet werden");
        h.assertValueEqual(dev.begleiter.ai.AdaptiveAcquisition.failedApproach(travel, "chest"),
                "chest", "Bewegung allein sagt nichts ueber die Beschaffungsmethode");
        h.assertValueEqual(dev.begleiter.ai.AdaptiveAcquisition.failedApproach(null, "mining"),
                "mining", "Fehlende Aufgabe muss sichere Fallback-Methode verwenden");
        h.succeed();
    }

    @GameTest
    public void fehlgeschlageneTeilaufgabeWirdRichtigGelernt(GameTestHelper h) {
        CompanionEntity c = h.spawn(CompanionMod.COMPANION,
                new net.minecraft.core.BlockPos(2, 1, 2));
        c.stay();
        c.goal = CompanionNeeds.Need.SUPPLY;
        c.activeSupplyResource = "kohle";
        c.activeSupplyApproach = "chest";
        c.reportAutonomousFailure(dev.begleiter.ai.DecisionOutcome.Reason.STALLED,
                CompanionEntity.Task.gather("kohle", 8));
        h.assertTrue(c.experiences.penalty(CompanionNeeds.Need.SUPPLY, "kohle_mining", c.tickCount) > 0,
                "Die tatsaechlich gescheiterte Methode wurde nicht gelernt");
        h.assertTrue(c.experiences.penalty(CompanionNeeds.Need.SUPPLY, "kohle_chest", c.tickCount) == 0,
                "Die geplante, aber nicht gescheiterte Methode wurde bestraft");

        c.goal = null; // Player task: no automatic learning.
        c.reportAutonomousFailure(dev.begleiter.ai.DecisionOutcome.Reason.STALLED,
                CompanionEntity.Task.take(new net.minecraft.core.BlockPos(4, 1, 4), "coal", 8));
        h.assertTrue(c.experiences.penalty(CompanionNeeds.Need.SUPPLY, "kohle_chest", c.tickCount) == 0,
                "Spielerauftraege duerfen keine KI-Fehlerstrafe erhalten");
        h.succeed();
    }

}
