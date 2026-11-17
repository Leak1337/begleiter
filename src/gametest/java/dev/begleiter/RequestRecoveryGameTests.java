package dev.begleiter;

import java.util.List;

import dev.begleiter.CompanionEntity.Task;
import dev.begleiter.task.TaskQueueCodec;
import dev.begleiter.task.TaskSpec;
import net.fabricmc.fabric.api.gametest.v1.GameTest;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.util.ProblemReporter;
import net.minecraft.world.Container;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.ChestBlock;
import net.minecraft.world.level.block.state.properties.ChestType;
import net.minecraft.world.level.storage.TagValueInput;
import net.minecraft.world.level.storage.TagValueOutput;

/** Echte Fehlschläge von Abhängigkeiten und genaue Lieferungen über das gemeinsame Aufgabenziel. */
public class RequestRecoveryGameTests {
    private static final String ARENA = "begleiter-gametest:request_isolation";

    private static CompanionEntity companion(GameTestHelper h) {
        for (int x = 18; x <= 39; x++) for (int z = 18; z <= 30; z++) h.setBlock(x, 0, z, Blocks.STONE);
        var mob = h.spawn(CompanionMod.COMPANION, new BlockPos(24, 1, 24));
        mob.stay(); mob.setNoAi(true); mob.setPersistenceRequired();
        return mob;
    }

    private static ServerPlayer owner(GameTestHelper h, CompanionEntity mob) {
        var owner = h.makeMockServerPlayerInLevel();
        owner.getInventory().clearContent();
        owner.snapTo(mob.getX(), mob.getY(), mob.getZ(), 0, 0);
        mob.setOwner(owner.getUUID());
        return owner;
    }

    private static Container barrel(GameTestHelper h, CompanionEntity mob, int x, Item item, int count) {
        var rel = new BlockPos(x, 1, 24);
        h.setBlock(rel, Blocks.BARREL);
        var box = mob.containerAt(h.getLevel(), h.absolutePos(rel));
        box.setItem(0, new ItemStack(item, count));
        return box;
    }

    private static Task delivery(String item, int count) {
        return new Task(new TaskSpec.Request(item, count, true, 2, 0));
    }

    private static void tick(CompanionEntity mob, CompanionTaskGoal goal, int count) {
        for (int i = 0; i < count && mob.hasTasks() && !mob.isPaused(); i++) {
            mob.tickCount++;
            goal.tick();
        }
    }

    private static int count(ServerPlayer owner, Item item) {
        int n = 0;
        for (int i = 0; i < owner.getInventory().getContainerSize(); i++)
            if (owner.getInventory().getItem(i).is(item)) n += owner.getInventory().getItem(i).getCount();
        return n;
    }

    @GameTest(structure = ARENA)
    public void request_recovery_unreachable_chest_uses_another_source(GameTestHelper h) {
        var mob = companion(h); var owner = owner(h, mob);
        var far = barrel(h, mob, 37, Items.IRON_INGOT, 5);
        // Wasser über dem Ziel wählt den Vanilla-Rückfall; dessen Zähler für fehlenden Fortschritt lässt sich
        // synchron prüfen, ohne den globalen Navigationsplaner weiterzuschalten.
        h.setBlock(37, 2, 24, Blocks.WATER);
        var nearby = barrel(h, mob, 26, Items.IRON_INGOT, 5);
        var failed = h.absolutePos(new BlockPos(37, 1, 24));
        mob.addTask(Task.take(failed, "iron_ingot", 5));
        mob.addTask(delivery("iron_ingot", 5));
        var goal = new CompanionTaskGoal(mob);
        // Keine Bewegung: die echte Erkennung für fehlenden Fortschritt prüfen, ohne künstliches Handler-Ergebnis.
        for (int i = 0; i < 400 && mob.currentTask().spec() instanceof TaskSpec.Take; i++) tick(mob, goal, 1);
        h.assertTrue(mob.currentTask().spec() instanceof TaskSpec.Request r && r.phase() == 4
                && failed.equals(r.excludedSource()), "Unreachable source was not excluded");
        tick(mob, goal, 140);
        h.assertTrue(!mob.hasTasks() && !mob.isPaused(), "Alternative source did not finish the order");
        h.assertValueEqual(count(owner, Items.IRON_INGOT), 5, "Wrong actual delivery");
        h.assertValueEqual(far.countItem(Items.IRON_INGOT), 5, "Retried the unreachable source");
        h.assertTrue(nearby.isEmpty(), "Alternative source not used");
        h.succeed();
    }

    @GameTest(structure = ARENA)
    public void request_recovery_partial_take_replans_only_missing_stock(GameTestHelper h) {
        var mob = companion(h); var owner = owner(h, mob);
        mob.giveToInventory(new ItemStack(Items.IRON_INGOT));
        var changed = barrel(h, mob, 22, Items.IRON_INGOT, 1);
        var nearby = barrel(h, mob, 26, Items.IRON_INGOT, 2);
        mob.addTask(Task.take(h.absolutePos(new BlockPos(22, 1, 24)), "iron_ingot", 3));
        mob.addTask(delivery("iron_ingot", 4));
        tick(mob, new CompanionTaskGoal(mob), 140);
        h.assertTrue(!mob.hasTasks(), "Partial-source recovery still running");
        h.assertValueEqual(count(owner, Items.IRON_INGOT), 4, "Partial stock lost or duplicated");
        h.assertTrue(changed.isEmpty() && nearby.isEmpty(), "Did not use exactly the two missing ingots");
        h.assertValueEqual(CompanionCraft.count(mob, "iron_ingot"), 0, "Unordered surplus acquired");
        h.succeed();
    }

    @GameTest(structure = ARENA)
    public void request_recovery_missing_tool_survives_reload_and_resumes(GameTestHelper h) {
        var mob = companion(h); var owner = owner(h, mob);
        var ore = new BlockPos(26, 1, 24);
        h.setBlock(ore, Blocks.IRON_ORE);
        mob.addTask(Task.breakAt(h.absolutePos(ore), Blocks.IRON_ORE));
        mob.addTask(delivery("raw_iron", 1));
        tick(mob, new CompanionTaskGoal(mob), 1);
        h.assertTrue(mob.isPaused() && mob.taskCount() == 2, "Missing tool discarded dependent work");
        var out = TagValueOutput.createWithContext(ProblemReporter.DISCARDING, h.getLevel().registryAccess());
        mob.saveWithoutId(out);
        var restored = new CompanionEntity(CompanionMod.COMPANION, h.getLevel());
        restored.load(TagValueInput.create(ProblemReporter.DISCARDING, h.getLevel().registryAccess(), out.buildResult()));
        h.assertTrue(restored.isPaused() && restored.taskCount() == 2, "Paused order lost on reload");
        restored.giveToInventory(new ItemStack(Items.STONE_PICKAXE));
        CompanionBrain.resumeReply(restored);
        tick(restored, new CompanionTaskGoal(restored), 180);
        h.assertTrue(!restored.hasTasks() && !restored.isPaused(), "Tool repair did not resume original work");
        h.assertTrue(h.getBlockState(ore).isAir(), "Ore was not actually mined");
        h.assertValueEqual(count(owner, Items.RAW_IRON), 1, "Resumed order not actually delivered");
        h.succeed();
    }

    @GameTest(structure = ARENA)
    public void request_recovery_station_can_be_replaced_without_duplicate_production(GameTestHelper h) {
        var mob = companion(h); var owner = owner(h, mob);
        mob.giveToInventory(new ItemStack(Items.STONE));
        mob.giveToInventory(new ItemStack(Items.STONE_SLAB, 3));
        mob.addTask(Task.process("stone_slab", 2, "minecraft:stone_slab_from_stone_stonecutting"));
        mob.addTask(new Task(new TaskSpec.Request("stone_slab", 2, false, 1, 3)));
        var goal = new CompanionTaskGoal(mob);
        tick(mob, goal, 1);
        h.assertTrue(mob.isPaused() && mob.taskCount() == 2, "Missing station discarded order");
        h.assertValueEqual(count(owner, Items.STONE_SLAB), 0, "Old stock delivered as fresh production");
        h.setBlock(26, 1, 24, Blocks.STONECUTTER);
        CompanionBrain.resumeReply(mob);
        tick(mob, goal, 90);
        h.assertTrue(!mob.hasTasks(), "Restored station did not resume processing");
        h.assertValueEqual(count(owner, Items.STONE_SLAB), 2, "Wrong fresh delivery");
        h.assertValueEqual(CompanionCraft.count(mob, "stone_slab"), 3, "Existing stock changed");
        h.assertValueEqual(CompanionCraft.count(mob, "stone"), 0, "Input not processed exactly once");
        h.succeed();
    }

    @GameTest(structure = ARENA)
    public void request_recovery_retry_budget_pauses_and_manual_resume_rechecks_source(GameTestHelper h) {
        var mob = companion(h); var owner = owner(h, mob);
        var source = h.absolutePos(new BlockPos(26, 1, 24));
        mob.addTask(new Task(new TaskSpec.Request("iron_ingot", 5, false, 4, 0, source), 3));
        mob.addTask(delivery("iron_ingot", 5));
        tick(mob, new CompanionTaskGoal(mob), 1);
        h.assertTrue(mob.isPaused() && mob.taskCount() == 2, "Retry limit did not retain paused order");
        var loaded = TaskQueueCodec.load(TaskQueueCodec.save(mob.peekTasks(20), mob.isPaused()));
        h.assertTrue(loaded.problem() == null && loaded.paused(), "Recovery checkpoint not persistable");
        h.assertTrue(loaded.tasks().get(0).spec() instanceof TaskSpec.Request r && source.equals(r.excludedSource()),
                "Excluded source lost on reload");
        mob.cancelAll();
        loaded.tasks().forEach(mob::addTask);
        mob.pauseWork("test");
        barrel(h, mob, 26, Items.IRON_INGOT, 5);
        CompanionBrain.resumeReply(mob);
        tick(mob, new CompanionTaskGoal(mob), 140);
        h.assertTrue(!mob.hasTasks(), "Explicit resume did not recheck repaired source");
        h.assertValueEqual(count(owner, Items.IRON_INGOT), 5, "Repaired source not delivered");
        h.succeed();
    }

    @GameTest(structure = ARENA)
    public void request_recovery_double_chest_exclusion_is_local_to_the_plan(GameTestHelper h) {
        var mob = companion(h);
        var left = new BlockPos(22, 1, 24); var right = left.east();
        var state = Blocks.CHEST.defaultBlockState().setValue(ChestBlock.FACING, Direction.NORTH);
        h.setBlock(left, state.setValue(ChestBlock.TYPE, ChestType.LEFT));
        h.setBlock(right, state.setValue(ChestBlock.TYPE, ChestType.RIGHT));
        var box = mob.containerAt(h.getLevel(), h.absolutePos(left));
        h.assertTrue(box != null && box.getContainerSize() == 54, "Fixture is not a double chest");
        box.setItem(0, new ItemStack(Items.IRON_INGOT, 2));
        mob.setHome(h.absolutePos(left));
        barrel(h, mob, 26, Items.IRON_INGOT, 2);
        var recovery = CompanionPlanner.refillRequest(mob, Items.IRON_INGOT, 2, h.absolutePos(right));
        h.assertTrue(recovery != null && recovery.tasks().stream().anyMatch(t -> t.spec() instanceof TaskSpec.Take), "No alternative plan");
        for (Task task : recovery.tasks()) if (task.spec() instanceof TaskSpec.Take take)
            h.assertValueEqual(take.pos(), h.absolutePos(new BlockPos(26, 1, 24)), "Used other half of excluded source");
        var independent = CompanionPlanner.refillRequest(mob, Items.IRON_INGOT, 2);
        h.assertTrue(independent != null && independent.tasks().stream().anyMatch(t -> t.spec() instanceof TaskSpec.Take take
                && (take.pos().equals(h.absolutePos(left)) || take.pos().equals(h.absolutePos(right)))), "Source exclusion leaked into another plan");
        h.succeed();
    }

    @GameTest(structure = ARENA)
    public void request_recovery_exhausted_gather_and_cancel_keep_their_meaning(GameTestHelper h) {
        var mob = companion(h); var owner = owner(h, mob);
        mob.addTask(new Task(new TaskSpec.Gather("sand", 1), 5));
        mob.addTask(delivery("sand", 1));
        tick(mob, new CompanionTaskGoal(mob), 1);
        h.assertTrue(mob.isPaused() && mob.taskCount() == 2, "Failed gather discarded the whole order");
        mob.cancelOrders();
        h.assertTrue(!mob.hasTasks() && !mob.isPaused(), "Explicit cancellation retained work");
        mob.giveToInventory(new ItemStack(Items.SAND));
        CompanionBrain.resumeReply(mob);
        tick(mob, new CompanionTaskGoal(mob), 100);
        h.assertValueEqual(count(owner, Items.SAND), 0, "Cancelled order resumed itself");
        h.succeed();
    }

    @GameTest(structure = ARENA)
    public void request_recovery_messages_are_bilingual(GameTestHelper h) {
        for (String key : List.of("request.paused", "request.retained", "request.replanning", "request.retry_limit")) {
            var message = switch (key) {
                case "request.paused" -> CompanionTexts.tr(key, CompanionTexts.task(Task.take(BlockPos.ZERO, "iron_ingot", 2)));
                case "request.replanning" -> CompanionTexts.tr(key, 2, CompanionTexts.item("iron_ingot"));
                case "request.retry_limit" -> CompanionTexts.tr(key, CompanionTexts.item("iron_ingot"));
                default -> CompanionTexts.tr(key);
            };
            ServerTextGameTests.assertTranslatedContains(h, message, key.equals("request.replanning") ? "Ersatzweg" : "Auftrag",
                    key.equals("request.replanning") ? "another way" : "order");
        }
        h.succeed();
    }

    @GameTest(structure = ARENA)
    public void request_recovery_full_inventory_pauses_then_resumes_without_losing_stock(GameTestHelper h) {
        var mob = companion(h); var owner = owner(h, mob);
        for (int i = 0; i < mob.getInventory().getContainerSize(); i++)
            mob.getInventory().setItem(i, new ItemStack(Items.DIAMOND, 64));
        var source = barrel(h, mob, 26, Items.IRON_INGOT, 3);
        mob.addTask(Task.take(h.absolutePos(new BlockPos(26, 1, 24)), "iron_ingot", 3));
        mob.addTask(delivery("iron_ingot", 3));
        var goal = new CompanionTaskGoal(mob);
        tick(mob, goal, 100);
        h.assertTrue(mob.isPaused() && mob.hasTasks(), "Full inventory cancelled the order");
        ServerTextGameTests.assertTranslatedContains(h, mob.uiFeedbackComponent(), "Platz im Inventar", "inventory space");
        h.assertValueEqual(source.countItem(Items.IRON_INGOT), 3, "Unaccepted items lost from source");
        h.assertValueEqual(count(owner, Items.IRON_INGOT), 0, "Unconfirmed delivery counted");
        h.assertValueEqual(CompanionCraft.count(mob, "diamond"), 36 * 64, "Existing stock lost");
        mob.getInventory().setItem(0, ItemStack.EMPTY); // Player makes space.
        CompanionBrain.resumeReply(mob);
        tick(mob, goal, 140);
        h.assertTrue(!mob.hasTasks(), "Freeing space did not resume the order");
        h.assertValueEqual(count(owner, Items.IRON_INGOT), 3, "Wrong delivery after freeing space");
        h.assertValueEqual(CompanionCraft.count(mob, "diamond"), 35 * 64, "Retry consumed unrelated stock");
        h.succeed();
    }
}
