package dev.begleiter;

import static dev.begleiter.HandlerTextGameTests.*;

import java.util.List;
import dev.begleiter.CompanionEntity.Task;
import dev.begleiter.CompanionEntity.TaskType;
import dev.begleiter.task.TaskQueueCodec;
import net.fabricmc.fabric.api.gametest.v1.GameTest;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.util.ProblemReporter;
import net.minecraft.world.level.storage.TagValueInput;
import net.minecraft.world.level.storage.TagValueOutput;

/** Echte Speicherwege und wiederholte Wächterausgaben, mit übersetzten Gründen und stabiler Warteschlange. */
public class QueueTextGameTests {
    private static CompoundTag save(GameTestHelper h, CompanionEntity c) {
        var out = TagValueOutput.createWithContext(ProblemReporter.DISCARDING, h.getLevel().registryAccess());
        c.saveWithoutId(out); return out.buildResult();
    }
    private static void load(GameTestHelper h, CompanionEntity c, CompoundTag data) {
        c.load(TagValueInput.create(ProblemReporter.DISCARDING, h.getLevel().registryAccess(), data));
    }

    @GameTest
    public void corruptedQueueStillRejectsEveryTaskAndTranslatesTheActualReason(GameTestHelper h) throws Exception {
        var original = companion(h); original.addTask(Task.gather("eisen", 3)); original.addTask(Task.give("item:raw_iron", 3));
        var good = save(h, original);
        for (boolean badVersion : new boolean[]{false, true}) {
            var data = good.copy(); var queue = data.getCompoundOrEmpty("TaskQueueV2");
            if (badVersion) queue.putInt("V", 99);
            else queue.getListOrEmpty("Tasks").getCompoundOrEmpty(0).putString("count", "drei %s Ä");
            var loaded = TaskQueueCodec.load(queue);
            h.assertTrue(loaded.problem() != null && loaded.problemText() != null && loaded.tasks().isEmpty()
                    && !loaded.paused() && loaded.skipped() == 0, "Corrupt queue was partially accepted");
            translated(h, loaded.problemText(), badVersion ? "unbekannte Version 99" : "beschädigter Wert",
                    badVersion ? "unknown version 99" : "corrupt value");
            var restored = companion(h); restored.addTask(Task.give(null, 0)); load(h, restored, data);
            h.assertValueEqual(restored.taskCount(), 0, "Entity retained a corrupt or previous queue");
            translated(h, restored.getLog(0, 1, false).getFirst().textComponent(), "Gespeicherte Aufträge verworfen", "Saved orders discarded");
            translated(h, restored.getLog(0, 1, false).getFirst().textComponent(), badVersion ? "Version 99" : "beschädigter Wert",
                    badVersion ? "version 99" : "corrupt value");
        }
        h.assertValueEqual(original.taskCount(), 2, "Inspecting corrupt copies changed original orders"); h.succeed();
    }

    @GameTest
    public void pausedQueueAndLegacyMigrationKeepOrderNamespacesAndSkippedTargets(GameTestHelper h) throws Exception {
        var c = companion(h); var pos = h.absolutePos(new BlockPos(4, 1, 2));
        var take = new Task(Task.take(pos, "begleiter-test:diamond", 3).spec(), 4);
        var give = Task.give("item:begleiter-test:diamond", 3);
        c.addTask(take); c.addTask(Task.fetch("cow")); c.addTask(give); c.pause("Pause %s Ä");
        var restored = companion(h); load(h, restored, save(h, c));
        h.assertTrue(restored.isPaused() && restored.taskCount() == 2 && restored.currentTask().equals(take), "Pause/order/tries lost");
        var saved = save(h, restored); var queue = saved.getCompoundOrEmpty("TaskQueueV2");
        h.assertTrue(!saved.contains("TaskQueueV1") && queue.equals(TaskQueueCodec.save(List.of(take, give), true)),
                "Display changes altered the queue format, item namespaces or FETCH skipping");
        var log = restored.getLog(0, 2, false);
        translated(h, log.getFirst().textComponent(), "Aufträge fortgesetzt: 2 (pausiert)", "Orders resumed: 2 (paused)");
        translated(h, log.get(1).textComponent(), "1 Aufgabe(n)", "Skipped 1 task(s)");
        var v1 = new CompoundTag(); v1.putInt("V", 1); var entry = new CompoundTag();
        entry.putString("T", "GATHER"); entry.putString("A", "eisen"); entry.putInt("N", 7);
        var list = new ListTag(); list.add(entry); v1.put("Tasks", list);
        var legacy = TaskQueueCodec.load(v1);
        h.assertTrue(legacy.problem() == null && legacy.problemText() == null && legacy.tasks().equals(List.of(Task.gather("eisen", 7))),
                "V1 migration changed while translating errors"); h.succeed();
    }

    @GameTest
    public void repeatedIdleDiagnosticsRetainOriginalProblemWithoutGrowing(GameTestHelper h) throws Exception {
        var c = companion(h); var task = Task.give(null, 0); c.addTask(task);
        c.setStage(CompanionTexts.taskLabel(TaskType.CRAFT)); c.problem(CompanionTexts.tr("queue.error.version", 99));
        var first = c.idleDiagnosticComponent(); int length = first.getString().length();
        for (int repeat = 0; repeat < 50; repeat++) c.problem(c.idleDiagnosticComponent());
        var repeated = c.problemComponent();
        h.assertValueEqual(repeated.getString().length(), length, "Idle diagnostics nested previous watchdogs");
        translated(h, repeated, "Letztes Problem: unbekannte Version 99", "Last problem: unknown version 99");
        translated(h, repeated, "30 Sekunden", "30 seconds");
        h.assertTrue(c.currentTask() == task && c.taskCount() == 1 && c.usedSlots() == 0, "Diagnostic display changed orders/inventory");
        c.lastStage = "Custom plan %s Ä"; c.problemText = "Custom reason %s Ä";
        var literal = c.idleDiagnosticComponent();
        translated(h, literal, "Custom plan %s Ä", "Custom plan %s Ä");
        translated(h, literal, "Custom reason %s Ä", "Custom reason %s Ä"); h.succeed();
    }
}
