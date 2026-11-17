package dev.begleiter;

import java.util.List;

import dev.begleiter.CompanionEntity.Task;
import dev.begleiter.CompanionEntity.TaskType;
import dev.begleiter.CompanionNeeds.Need;
import dev.begleiter.ai.CompanionDecisionEngine;
import dev.begleiter.ai.CompanionExperienceMemory;
import dev.begleiter.ai.CompanionSourceMemory;
import dev.begleiter.ai.CompanionSourceMemory.Key;
import dev.begleiter.ai.DecisionOutcome.Reason;
import net.fabricmc.fabric.api.gametest.v1.GameTest;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.world.Container;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Blocks;

public class SourceGameTests {
    private static Key key(long position) {
        return new Key(Need.SUPPLY, "chest", "minecraft:coal", "minecraft:overworld", 1, position);
    }
    private static CompanionEntity companion(GameTestHelper h) {
        for (int x = 0; x < 8; x++) for (int z = 0; z < 8; z++) h.setBlock(x, 0, z, Blocks.STONE);
        CompanionEntity c = h.spawn(CompanionMod.COMPANION, new BlockPos(2, 1, 2));
        c.stay();
        c.getInventory().clearContent();
        c.setItemInHand(InteractionHand.MAIN_HAND, ItemStack.EMPTY);
        c.memory.clear();
        return c;
    }
    private static Container chest(GameTestHelper h, CompanionEntity c, BlockPos pos, int coal) {
        h.setBlock(pos, Blocks.CHEST);
        Container box = c.containerAt(h.getLevel(), h.absolutePos(pos));
        h.assertTrue(box != null, "Testkiste fehlt");
        if (coal > 0) box.setItem(0, new ItemStack(Items.COAL, coal));
        return box;
    }
    private static void start(CompanionEntity c, BlockPos pos) {
        start(c, pos, "chest");
    }
    private static void start(CompanionEntity c, BlockPos pos, String approach) {
        c.goal = Need.SUPPLY;
        c.activeSupplyResource = "kohle";
        c.activeSupplyApproach = approach;
        c.addTask(Task.take(pos, "coal", 8));
        c.planFeedback.begin(c, Need.SUPPLY, null);
    }

    @GameTest
    public void quelleIstNachOrtGegenstandWeltUndBeduerfnisGetrennt(GameTestHelper h) {
        var memory = new CompanionSourceMemory();
        memory.failed(key(10), Reason.UNREACHABLE, 100);
        h.assertTrue(memory.blocked(key(10), 100), "Quellensperre fehlt");
        var other = List.of(key(11),
                new Key(Need.SUPPLY, "chest", "minecraft:iron_ingot", "minecraft:overworld", 1, 10),
                new Key(Need.SUPPLY, "chest", "minecraft:coal", "minecraft:the_nether", 1, 10),
                new Key(Need.SUPPLY, "chest", "minecraft:coal", "minecraft:overworld", 2, 10),
                new Key(Need.GEAR, "chest", "minecraft:coal", "minecraft:overworld", 1, 10));
        h.assertTrue(other.stream().noneMatch(k -> memory.blocked(k, 100)), "Fehler auf fremden Kontext uebertragen");
        h.succeed();
    }

    @GameTest
    public void ursacheUndRestzeitUeberlebenDenNeustart(GameTestHelper h) {
        var memory = new CompanionExperienceMemory();
        memory.sources.failed(key(10), Reason.SOURCE_EMPTY, 100);
        var loaded = new CompanionExperienceMemory();
        loaded.load(memory.save(300), 0);
        h.assertTrue(loaded.sources.blocked(key(10), 399), "Restzeit verloren");
        h.assertTrue(!loaded.sources.blocked(key(10), 400), "Quelle bleibt dauerhaft gesperrt");
        h.assertTrue(loaded.sources.reason(key(10), 0) == Reason.SOURCE_EMPTY, "Ursache verloren");
        loaded.load(new CompoundTag(), 0);
        h.assertValueEqual(loaded.sources.size(), 0, "Altes Save ohne Quellen nicht kompatibel");
        h.succeed();
    }

    @GameTest
    public void quellenEintraegeSindBegrenztUndBeschaedigteZeilenWerdenIgnoriert(GameTestHelper h) {
        var memory = new CompanionSourceMemory();
        for (int i = 0; i < 40; i++) memory.failed(key(i), Reason.UNREACHABLE, 0);
        h.assertValueEqual(memory.size(), 32, "Quellengedächtnis unbegrenzt");
        h.assertTrue(!memory.blocked(key(0), 0) && memory.blocked(key(39), 0), "Alte Eintraege nicht entfernt");
        ListTag rows = new ListTag();
        CompoundTag bad = new CompoundTag();
        bad.putString("Need", "INVALID");
        bad.putLong("Seed", 1);
        bad.putLong("Position", 10);
        rows.add(bad);
        rows.addAll(memory.save(0));
        memory.load(rows, 0);
        h.assertValueEqual(memory.size(), 32, "Beschaedigte Zeile blockiert gueltige Quellen");
        h.succeed();
    }

    @GameTest
    public void fehlgeschlageneKisteBestraftNichtDieGanzeMethode(GameTestHelper h) {
        CompanionEntity c = companion(h);
        BlockPos pos = h.absolutePos(new BlockPos(3, 1, 2));
        start(c, pos);
        c.reportAutonomousFailure(Reason.UNREACHABLE, c.currentTask());
        c.reportAutonomousFailure(Reason.STALLED, c.currentTask());
        c.planFeedback.finish(c, false);
        c.cancelAll();
        Key source = CompanionSources.key(c, Need.SUPPLY, Items.COAL, pos);
        h.assertTrue(c.experiences.sources.blocked(source, c.tickCount), "Konkrete Quelle nicht erfasst");
        h.assertTrue(c.planFeedback.last().source().equals(source), "Quellenkontext fehlt im Ergebnis");
        h.assertTrue(c.experiences.sources.reason(source, c.tickCount) == Reason.UNREACHABLE, "Erste Ursache ueberschrieben");
        h.assertValueEqual(c.experiences.sources.size(), 1, "Schrittfehler mehrfach gespeichert");
        h.assertValueEqual(((CompoundTag) c.experiences.sources.save(c.tickCount).get(0)).getIntOr("Count", 0),
                1, "Ein Plan erzeugt mehrere Quellenfehler");
        h.assertValueEqual(c.experiences.penalty(Need.SUPPLY, c.tickCount), 0, "Ganzes Ziel bestraft");
        h.assertValueEqual(c.experiences.learnedProfiles(), 0, "Alle Kisten abgewertet");
        h.succeed();
    }

    @GameTest
    public void spielerabbruchLerntAuchKeineQuellensperre(GameTestHelper h) {
        CompanionEntity c = companion(h);
        start(c, h.absolutePos(new BlockPos(3, 1, 2)));
        c.reportAutonomousFailure(Reason.SOURCE_EMPTY, c.currentTask());
        c.cancelAll();
        h.assertValueEqual(c.experiences.sources.size(), 0, "Spielerabbruch sperrt Quelle");
        h.assertValueEqual(c.experiences.learnedProfiles(), 0, "Spielerabbruch aendert Methodenwissen");
        Key old = CompanionSources.key(c, Need.SUPPLY, Items.COAL, h.absolutePos(new BlockPos(3, 1, 2)));
        c.experiences.sources.failed(old, Reason.SOURCE_EMPTY, c.tickCount);
        start(c, h.absolutePos(new BlockPos(3, 1, 2)));
        c.planFeedback.sourceSucceeded(c, c.currentTask());
        c.cancelAll();
        h.assertTrue(c.experiences.sources.blocked(old, c.tickCount), "Spielerabbruch entsperrt alte Quelle");
        h.succeed();
    }

    @GameTest
    public void fehlendeVoraussetzungenSindKeineUnzuverlaessigeQuelle(GameTestHelper h) {
        for (Reason reason : List.of(Reason.INVENTORY_FULL, Reason.MISSING_TOOL, Reason.MISSING_INPUT, Reason.MISSING_STATION)) {
            CompanionEntity c = companion(h);
            start(c, h.absolutePos(new BlockPos(3, 1, 2)));
            c.reportAutonomousFailure(reason, c.currentTask());
            c.planFeedback.finish(c, false);
            h.assertTrue(c.planFeedback.last().reason() == reason, "Voraussetzung nicht diagnostiziert");
            h.assertValueEqual(c.experiences.sources.size(), 0, "Fehlende Voraussetzung sperrt Kiste");
            h.assertValueEqual(c.experiences.penalty(Need.SUPPLY, c.tickCount), 0, "Voraussetzung als Zielversagen gelernt");
            h.assertValueEqual(c.experiences.learnedProfiles(), 0, "Voraussetzung als Methodenfehler gelernt");
        }
        h.succeed();
    }

    @GameTest
    public void autonomeSucheUeberspringtNurDieGesperrteKiste(GameTestHelper h) {
        CompanionEntity c = companion(h);
        c.giveToInventory(new ItemStack(Items.DIAMOND_PICKAXE));
        BlockPos a = new BlockPos(3, 1, 2), b = new BlockPos(5, 1, 2);
        chest(h, c, a, 8).setItem(1, new ItemStack(Items.IRON_INGOT, 8));
        chest(h, c, b, 8);
        c.experiences.sources.failed(CompanionSources.key(c, Need.SUPPLY, Items.COAL, h.absolutePos(a)), Reason.UNREACHABLE, c.tickCount);
        h.assertValueEqual(c.findContainerWith(Items.COAL, 16), h.absolutePos(a), "Spielersuche wurde eingeschraenkt");
        h.assertValueEqual(c.findContainerWith(Items.IRON_INGOT, 16, p -> CompanionSources.allowed(c, Items.IRON_INGOT, p)),
                h.absolutePos(a), "Anderer Kisteninhalt wurde gesperrt");
        var plans = CompanionPlanner.alternatives(c, Items.COAL, 8);
        h.assertTrue(plans.stream().flatMap(p -> p.plan().tasks().stream())
                .noneMatch(t -> t.type() == TaskType.TAKE && h.absolutePos(a).equals(t.pos())), "Gesperrte Kiste bleibt im Plan");
        h.assertTrue(plans.stream().flatMap(p -> p.plan().tasks().stream())
                .anyMatch(t -> t.type() == TaskType.TAKE && h.absolutePos(b).equals(t.pos())), "Andere Kiste nicht beruecksichtigt");
        var explicit = CompanionPlanner.obtain(c, Items.COAL, 8);
        h.assertTrue(explicit.tasks().getFirst().type() == TaskType.TAKE && explicit.tasks().getFirst().pos().equals(h.absolutePos(a)),
                "Expliziter Spielerplan uebernimmt autonome Quellensperre");
        h.succeed();
    }

    @GameTest
    public void auchGemerkteEntfernteKistenWerdenGefiltert(GameTestHelper h) {
        CompanionEntity c = companion(h);
        BlockPos a = c.blockPosition().offset(20, 0, 0), b = c.blockPosition().offset(30, 0, 0);
        c.memory.remember(CompanionMemory.Kind.CHEST, "", h.getLevel(), a).items.put("minecraft:coal", 8);
        c.memory.remember(CompanionMemory.Kind.CHEST, "", h.getLevel(), b).items.put("minecraft:coal", 8);
        c.experiences.sources.failed(CompanionSources.key(c, Need.SUPPLY, Items.COAL, a), Reason.UNREACHABLE, c.tickCount);
        var known = c.memory.chestWith(Items.COAL, h.getLevel(), c.blockPosition(), 48,
                p -> CompanionSources.allowed(c, Items.COAL, p));
        h.assertTrue(known != null && known.pos.equals(b), "Gedächtnissuche bleibt an gesperrter Kiste haengen");
        h.succeed();
    }

    @GameTest
    public void aktivierteVorratsplanungWechseltOhnePauschalenMethodenfehlerDieKiste(GameTestHelper h) {
        CompanionEntity c = companion(h);
        c.giveToInventory(new ItemStack(Items.DIAMOND_PICKAXE));
        c.giveToInventory(new ItemStack(Items.OAK_LOG, 16));
        c.giveToInventory(new ItemStack(Items.COBBLESTONE, 32));
        var a = new BlockPos(3, 1, 2);
        var b = new BlockPos(5, 1, 2);
        chest(h, c, a, 8);
        chest(h, c, b, 8);
        c.experiences.sources.failed(CompanionSources.key(c, Need.SUPPLY, Items.COAL, h.absolutePos(a)), Reason.UNREACHABLE, c.tickCount);
        CompanionNeeds.act(c, new CompanionNeeds.Score(Need.SUPPLY, 20, "Kohle"));
        var task = c.currentTask();
        h.assertTrue(task != null && task.type() == (CompanionDecisionEngine.enabled() ? TaskType.TAKE : TaskType.GATHER),
                "Quellensperre nicht an Auswahl angeschlossen");
        if (CompanionDecisionEngine.enabled()) h.assertValueEqual(task.pos(), h.absolutePos(b), "Falsche Ersatzkiste");
        h.assertValueEqual(c.experiences.penalty(Need.SUPPLY, c.tickCount), 0, "Kistenwechsel benoetigt pauschalen Fehler");
        h.succeed();
    }

    @GameTest(maxTicks = 200)
    public void teilgefuellteKisteWirdAlsTeilquelleErkannt(GameTestHelper h) {
        CompanionEntity c = companion(h);
        var pos = new BlockPos(3, 1, 2);
        chest(h, c, pos, 2);
        start(c, h.absolutePos(pos), "mining"); // The first method must not hide a later chest failure.
        h.succeedWhen(() -> {
            h.assertTrue(c.planFeedback.last() != null, "Kistenplan nicht abgeschlossen");
            h.assertTrue(c.planFeedback.last().status() == CompanionPlanFeedback.Status.PARTIAL, "Teilfortschritt verloren");
            h.assertTrue(c.planFeedback.last().reason() == Reason.SOURCE_PARTIAL, "Fehlergrund ungenau");
            h.assertTrue(c.experiences.sources.blocked(CompanionSources.key(c, Need.SUPPLY, Items.COAL, h.absolutePos(pos)), c.tickCount), "Teilquelle nicht erfasst");
            h.assertValueEqual(c.experiences.learnedProfiles(), 0, "Teilquelle wertet ganze Methode ab");
        });
    }

    @GameTest(maxTicks = 200)
    public void volleTaschenSperrenKeineGutGefuellteKiste(GameTestHelper h) {
        CompanionEntity c = companion(h);
        var pos = new BlockPos(3, 1, 2);
        chest(h, c, pos, 8);
        for (int i = 0; i < c.getInventory().getContainerSize(); i++) c.getInventory().setItem(i, new ItemStack(Items.DIAMOND, 64));
        start(c, h.absolutePos(pos));
        h.succeedWhen(() -> {
            h.assertTrue(c.planFeedback.last() != null, "Kistenplan nicht abgeschlossen");
            h.assertTrue(c.planFeedback.last().reason() == Reason.INVENTORY_FULL, "Inventarmangel als Quellenfehler gewertet");
            h.assertValueEqual(c.experiences.sources.size(), 0, "Volle Taschen sperren Kiste");
            h.assertValueEqual(c.experiences.learnedProfiles(), 0, "Volle Taschen bestrafen Beschaffungsmethode");
        });
    }

    @GameTest(maxTicks = 200)
    public void verschwundeneKisteWirdAlsFehlendeQuelleErkannt(GameTestHelper h) {
        CompanionEntity c = companion(h);
        var pos = new BlockPos(3, 1, 2);
        h.setBlock(pos, Blocks.AIR);
        start(c, h.absolutePos(pos));
        h.succeedWhen(() -> {
            h.assertTrue(c.planFeedback.last() != null, "Fehlende Kiste nicht abgeschlossen");
            h.assertTrue(c.planFeedback.last().reason() == Reason.SOURCE_MISSING, "Fehlende Kiste nicht diagnostiziert");
            h.assertValueEqual(c.experiences.penalty(Need.SUPPLY, c.tickCount), 0, "Fehlende Kiste bestraft ganze Ressource");
        });
    }

    @GameTest(maxTicks = 200)
    public void bestaetigteEntnahmeLoeschtNurDiePassendeQuellensperre(GameTestHelper h) {
        CompanionEntity c = companion(h);
        var pos = new BlockPos(3, 1, 2);
        chest(h, c, pos, 8);
        Key used = CompanionSources.key(c, Need.SUPPLY, Items.COAL, h.absolutePos(pos));
        Key other = CompanionSources.key(c, Need.SUPPLY, Items.COAL, h.absolutePos(new BlockPos(5, 1, 2)));
        c.experiences.sources.failed(used, Reason.SOURCE_EMPTY, c.tickCount);
        c.experiences.sources.failed(other, Reason.SOURCE_EMPTY, c.tickCount);
        start(c, h.absolutePos(pos)); // Explicit confirmed access can rehabilitate a refilled source.
        h.succeedWhen(() -> {
            h.assertTrue(c.planFeedback.last() != null, "Erfolgreicher Plan nicht abgeschlossen");
            h.assertTrue(c.planFeedback.last().status() == CompanionPlanFeedback.Status.SUCCESS, "Erfolgreiche Quelle nicht erkannt");
            h.assertTrue(!c.experiences.sources.blocked(used, c.tickCount), "Bestaetigte Quelle bleibt gesperrt");
            h.assertTrue(c.experiences.sources.blocked(other, c.tickCount), "Erfolg entsperrt fremde Quelle");
        });
    }
}
