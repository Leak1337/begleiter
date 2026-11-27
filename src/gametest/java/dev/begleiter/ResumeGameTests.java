package dev.begleiter;

import net.fabricmc.fabric.api.gametest.v1.GameTest;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.contents.TranslatableContents;
import net.minecraft.util.ProblemReporter;
import net.minecraft.world.entity.EntitySpawnReason;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.storage.TagValueInput;
import net.minecraft.world.level.storage.TagValueOutput;

/** Gemeinsame Wiederaufnahme nach dem Laden – Widersprüche halten sichtbar an statt blind weiterzuarbeiten. */
public class ResumeGameTests {
    private static CompanionEntity companion(GameTestHelper h) {
        CompanionEntity c = CompanionMod.COMPANION.create(h.getLevel(), EntitySpawnReason.LOAD);
        h.assertTrue(c != null, "Companion not created");
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

    private static CompanionEntity reload(GameTestHelper h, CompoundTag data) {
        var back = companion(h);
        back.load(TagValueInput.create(ProblemReporter.DISCARDING, h.getLevel().registryAccess(), data));
        return back;
    }

    private static boolean feedbackIs(CompanionEntity c, String key) {
        return c.uiFeedbackComponent().getContents() instanceof TranslatableContents t && t.getKey().endsWith(key);
    }

    @GameTest
    public void buildOrderWithoutAPlanHoldsTheFollowingSteps(GameTestHelper h) {
        var c = companion(h);
        BlockPos target = h.absolutePos(new BlockPos(3, 1, 3));
        c.addTask(CompanionEntity.Task.build());
        c.addTask(CompanionEntity.Task.travel(target));
        var back = reload(h, save(h, c)); // kein Bauplan gespeichert: wie ein unlesbarer Bauteil
        h.assertValueEqual(back.taskCount(), 1, "Queue after reconciliation");
        h.assertTrue(back.currentTask().type() == CompanionEntity.TaskType.TRAVEL, "Following step lost");
        h.assertTrue(back.isPaused(), "Following steps ran without the build");
        h.assertTrue(feedbackIs(back, "resume.build_lost"), "Problem not visible: " + back.uiFeedbackComponent().getString());
        h.succeed();
    }

    @GameTest
    public void dataFromANewerVersionIsReadButNotContinued(GameTestHelper h) {
        var c = companion(h);
        c.giveToInventory(new ItemStack(Items.IRON_INGOT, 7));
        c.addTask(CompanionEntity.Task.travel(h.absolutePos(new BlockPos(3, 1, 3))));
        var data = save(h, c);
        h.assertValueEqual(data.getIntOr("BegleiterDataV", -1), CompanionEntity.DATA_VERSION, "Data version not written");
        data.putInt("BegleiterDataV", CompanionEntity.DATA_VERSION + 5);
        var back = reload(h, data);
        h.assertValueEqual(CompanionCraft.count(back, "iron_ingot"), 7, "Inventory lost");
        h.assertValueEqual(back.taskCount(), 1, "Orders lost");
        h.assertTrue(back.isPaused(), "Newer data continued on its own");
        h.assertTrue(feedbackIs(back, "resume.newer_data"), "Problem not visible");
        h.succeed();
    }

    @GameTest
    public void legacyDataWithoutVersionResumesNormally(GameTestHelper h) {
        var c = companion(h);
        c.addTask(CompanionEntity.Task.travel(h.absolutePos(new BlockPos(3, 1, 3))));
        var data = save(h, c);
        data.remove("BegleiterDataV");
        var back = reload(h, data);
        h.assertValueEqual(back.taskCount(), 1, "Orders lost");
        h.assertTrue(!back.isPaused(), "Legacy data paused");
        h.succeed();
    }

    @GameTest
    public void rejectedQueueIsVisible(GameTestHelper h) {
        var c = companion(h);
        c.addTask(CompanionEntity.Task.travel(h.absolutePos(new BlockPos(3, 1, 3))));
        var data = save(h, c);
        var queue = data.getCompoundOrEmpty("TaskQueueV2");
        h.assertTrue(!queue.isEmpty(), "Fixture: queue not saved");
        queue.putInt("V", 999); // fremde Version: ganze Queue wird verworfen
        data.put("TaskQueueV2", queue);
        var back = reload(h, data);
        h.assertTrue(!back.hasTasks(), "Rejected queue still loaded");
        h.assertTrue(feedbackIs(back, "queue.load.rejected"), "Rejection not visible: " + back.uiFeedbackComponent().getString());
        h.succeed();
    }
}
