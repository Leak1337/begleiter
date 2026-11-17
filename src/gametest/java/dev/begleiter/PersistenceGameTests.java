package dev.begleiter;

import net.fabricmc.fabric.api.gametest.v1.GameTest;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.util.ProblemReporter;
import net.minecraft.world.entity.EntitySpawnReason;
import net.minecraft.world.level.storage.TagValueInput;
import net.minecraft.world.level.storage.TagValueOutput;

/** Hungerzustand bei normalem Laden, Legacy-Daten und Wiederbelebung. */
public class PersistenceGameTests {
    private static CompanionEntity companion(GameTestHelper h) {
        CompanionEntity c = CompanionMod.COMPANION.create(h.getLevel(), EntitySpawnReason.LOAD);
        h.assertTrue(c != null, "Begleiter konnte nicht erzeugt werden");
        BlockPos at = h.absolutePos(new BlockPos(1, 1, 1));
        c.snapTo(at.getX() + 0.5, at.getY(), at.getZ() + 0.5, 0, 0);
        c.stay();
        return c;
    }

    private static CompoundTag save(GameTestHelper h, CompanionEntity c) {
        TagValueOutput out = TagValueOutput.createWithContext(ProblemReporter.DISCARDING, h.getLevel().registryAccess());
        c.saveWithoutId(out);
        return out.buildResult();
    }

    private static void load(GameTestHelper h, CompanionEntity c, CompoundTag data) {
        c.load(TagValueInput.create(ProblemReporter.DISCARDING, h.getLevel().registryAccess(), data));
    }

    private static CompanionEntity withHunger(GameTestHelper h, int hunger, int timer) {
        CompanionEntity c = companion(h);
        CompoundTag data = save(h, c);
        data.putInt("Hunger", hunger);
        data.putInt("HungerTimer", timer);
        load(h, c, data);
        return c;
    }

    /** Nur der alte Standardname wird zu "Leak", eigene Namen bleiben. */
    @GameTest
    public void alterStandardnameWirdBeimLadenZuLeak(GameTestHelper h) {
        for (String[] c : new String[][] {{"Begleiter", "Leak"}, {"Max", "Max"}, {"Begleiter Max", "Begleiter Max"}}) {
            CompanionEntity original = companion(h);
            original.setCustomName(net.minecraft.network.chat.Component.literal(c[0]));
            CompanionEntity restored = companion(h);
            load(h, restored, save(h, original));
            h.assertValueEqual(restored.getCustomName().getString(), c[1], "Name nach Laden von " + c[0]);
        }
        h.succeed();
    }

    // ------------------------------------------------------------------ Aufgabenwarteschlange

    /** "bring mir 10 Eisen" (GATHER → GIVE) übersteht Speichern/Laden samt Reihenfolge und Pause. */
    @GameTest
    public void spielerauftragUeberstehtSpeichernUndLaden(GameTestHelper h) {
        CompanionEntity original = companion(h);
        BlockPos spot = h.absolutePos(new BlockPos(3, 1, 3));
        original.addTask(CompanionEntity.Task.gather("eisen", 10));
        original.addTask(CompanionEntity.Task.place(spot, net.minecraft.world.level.block.Blocks.COBBLESTONE).retry().retry());
        original.addTask(CompanionEntity.Task.give("item:raw_iron", 10));
        original.pauseWork("Test");
        CompanionEntity restored = companion(h);
        load(h, restored, save(h, original));
        var q = restored.peekTasks(10);
        h.assertValueEqual(q.size(), 3, "Anzahl Aufgaben");
        h.assertTrue(q.get(0).equals(original.peekTasks(10).get(0)), "GATHER verändert: " + q.get(0));
        h.assertTrue(q.get(1).equals(original.peekTasks(10).get(1)), "PLACE verändert: " + q.get(1));
        h.assertTrue(q.get(2).equals(original.peekTasks(10).get(2)), "GIVE verändert: " + q.get(2));
        h.assertTrue(restored.isPaused(), "Pause nicht übernommen");
        h.succeed();
    }

    @GameTest
    public void eigenerPlanWirdNichtGespeichert(GameTestHelper h) {
        CompanionEntity original = companion(h);
        original.addTask(CompanionEntity.Task.gather("holz", 8));
        original.goal = CompanionNeeds.Need.SUPPLY; // autonomer Plan: nach dem Laden neu planen
        CompanionEntity restored = companion(h);
        load(h, restored, save(h, original));
        h.assertValueEqual(restored.taskCount(), 0, "Autonomer Plan wurde gespeichert");
        h.succeed();
    }

    /** Kaputte Daten verwerfen die ganze Queue (keine Kette mit Lücke); FETCH wird einzeln ausgelassen. */
    @GameTest
    public void beschaedigteAufgabenVerwerfenDieQueue(GameTestHelper h) {
        CompanionEntity original = companion(h);
        original.addTask(CompanionEntity.Task.gather("eisen", 3));
        original.addTask(CompanionEntity.Task.give("item:raw_iron", 3));
        CompoundTag good = save(h, original);

        String[][] broken = {{"T", "FLIEGEN"}, {"block", "minecraft:gibtsnicht"}, {"V", null}, {"resource", null},
                {"count", "zehn"}};
        for (String[] b : broken) {
            CompoundTag data = good.copy();
            CompoundTag queue = data.getCompoundOrEmpty("TaskQueueV2");
            CompoundTag first = queue.getListOrEmpty("Tasks").getCompoundOrEmpty(0);
            switch (b[0]) {
                case "V" -> queue.putInt("V", 99);
                case "resource" -> first.remove("resource"); // GATHER ohne Ressource
                case "block" -> { // PLACE mit unbekanntem Block
                    first.putString("T", "PLACE");
                    first.putLong("pos", 0L);
                    first.putString("block", b[1]);
                }
                default -> first.putString(b[0], b[1]); // count als Text: falscher Werttyp
            }
            CompanionEntity restored = companion(h);
            load(h, restored, data);
            h.assertValueEqual(restored.taskCount(), 0, "Beschädigte Queue geladen (" + b[0] + ")");
        }

        CompanionEntity withFetch = companion(h);
        withFetch.addTask(CompanionEntity.Task.fetch("cow"));
        withFetch.addTask(CompanionEntity.Task.give(null, 0));
        CompanionEntity restored = companion(h);
        load(h, restored, save(h, withFetch));
        h.assertValueEqual(restored.taskCount(), 1, "FETCH nicht ausgelassen");
        h.assertTrue(restored.currentTask().type() == CompanionEntity.TaskType.GIVE, "GIVE fehlt nach FETCH");
        h.succeed();
    }

    /** Welten von vor V2: die alte Queue (TaskQueueV1 mit "A"/"N") wird weiter gelesen und typisiert. */
    @GameTest
    public void alteQueueV1WirdGelesen(GameTestHelper h) {
        BlockPos spot = h.absolutePos(new BlockPos(3, 1, 3));
        CompoundTag gather = new CompoundTag();
        gather.putString("T", "GATHER");
        gather.putString("A", "eisen");
        gather.putInt("N", 10);
        CompoundTag place = new CompoundTag();
        place.putString("T", "PLACE");
        place.putLong("P", spot.asLong());
        place.putString("B", "minecraft:cobblestone");
        place.putInt("R", 2);
        CompoundTag fight = new CompoundTag();
        fight.putString("T", "FIGHT");
        fight.putString("A", "zombie|rotten_flesh");
        fight.putInt("N", 4);
        net.minecraft.nbt.ListTag list = new net.minecraft.nbt.ListTag();
        list.add(gather);
        list.add(place);
        list.add(fight);
        CompoundTag queue = new CompoundTag();
        queue.putInt("V", 1);
        queue.put("Tasks", list);

        CompanionEntity c = companion(h);
        CompoundTag data = save(h, c);
        data.put("TaskQueueV1", queue);
        load(h, c, data);
        var q = c.peekTasks(10);
        h.assertValueEqual(q.size(), 3, "Anzahl Aufgaben aus V1");
        h.assertTrue(q.get(0).equals(CompanionEntity.Task.gather("eisen", 10)), "GATHER aus V1: " + q.get(0));
        h.assertTrue(q.get(1).equals(CompanionEntity.Task.place(spot, net.minecraft.world.level.block.Blocks.COBBLESTONE).retry().retry()),
                "PLACE aus V1: " + q.get(1));
        h.assertTrue(q.get(2).equals(CompanionEntity.Task.fight(null, "zombie", "rotten_flesh", 4)), "FIGHT aus V1: " + q.get(2));
        CompoundTag again = save(h, c);
        h.assertTrue(again.contains("TaskQueueV2") && !again.contains("TaskQueueV1"), "Nicht als V2 neu gespeichert");
        h.succeed();
    }

    @GameTest
    public void hungerUndTimerUeberstehenSpeichernUndLaden(GameTestHelper h) {
        CompanionEntity original = withHunger(h, 7, 23);
        CompanionEntity restored = companion(h);
        load(h, restored, save(h, original));
        h.assertValueEqual(restored.hunger, 7, "Hunger nach Laden");
        h.assertValueEqual(save(h, restored).getIntOr("HungerTimer", -1), 23, "Timer nach Laden");
        h.succeed();
    }

    @GameTest
    public void alterSpielstandOhneHungerNutztStandardwerte(GameTestHelper h) {
        CompoundTag legacy = save(h, companion(h));
        legacy.remove("Hunger");
        legacy.remove("HungerTimer");
        CompanionEntity restored = withHunger(h, 2, 17);
        load(h, restored, legacy);
        h.assertValueEqual(restored.hunger, 20, "Legacy-Hunger");
        h.assertValueEqual(save(h, restored).getIntOr("HungerTimer", -1), 0, "Legacy-Timer");
        h.succeed();
    }

    @GameTest
    public void negativeHungerwerteWerdenBegrenzt(GameTestHelper h) {
        CompanionEntity restored = withHunger(h, Integer.MIN_VALUE, Integer.MIN_VALUE);
        h.assertValueEqual(restored.hunger, 0, "Negativer Hunger");
        h.assertValueEqual(save(h, restored).getIntOr("HungerTimer", -1), 0, "Negativer Timer");
        h.succeed();
    }

    @GameTest
    public void zuGrosseHungerwerteWerdenBegrenzt(GameTestHelper h) {
        CompanionEntity restored = withHunger(h, Integer.MAX_VALUE, Integer.MAX_VALUE);
        h.assertValueEqual(restored.hunger, 20, "Zu großer Hunger");
        h.assertValueEqual(save(h, restored).getIntOr("HungerTimer", -1), 29, "Zu großer Timer");
        h.succeed();
    }

    @GameTest
    public void falscheDatentypenNutzenStandardwerte(GameTestHelper h) {
        CompoundTag data = save(h, companion(h));
        data.putString("Hunger", "kaputt");
        data.putString("HungerTimer", "kaputt");
        CompanionEntity restored = withHunger(h, 1, 9);
        load(h, restored, data);
        h.assertValueEqual(restored.hunger, 20, "Ungültiger Hunger-Typ");
        h.assertValueEqual(save(h, restored).getIntOr("HungerTimer", -1), 0, "Ungültiger Timer-Typ");
        h.succeed();
    }

    @GameTest
    public void hungerCountdownLaeuftNachLadenWeiter(GameTestHelper h) {
        CompanionEntity original = withHunger(h, 7, 29);
        CompanionEntity restored = companion(h);
        load(h, restored, save(h, original));
        restored.tickCount = 20;
        restored.customServerAiStep(h.getLevel());
        h.assertValueEqual(restored.hunger, 6, "Hunger-Countdown wurde beim Laden zurückgesetzt");
        h.assertValueEqual(save(h, restored).getIntOr("HungerTimer", -1), 0, "Timer nach Hungerabzug");
        h.succeed();
    }

    @GameTest
    public void kernBelebtWieBisherSattWieder(GameTestHelper h) {
        CompanionEntity original = withHunger(h, 7, 23);
        CompoundTag memory = original.saveMemory();
        h.assertTrue(!memory.contains("Hunger") && !memory.contains("HungerTimer"), "Hunger gelangt in den Erinnerungskern");
        CompanionEntity revived = withHunger(h, 1, 29);
        revived.loadMemory(memory);
        h.assertValueEqual(revived.hunger, 20, "Kern-Wiederbelebung ist nicht satt");
        h.assertValueEqual(save(h, revived).getIntOr("HungerTimer", -1), 0, "Kern übernimmt alten Timer");
        h.succeed();
    }
}
