package dev.begleiter;

import java.util.List;

import dev.begleiter.CompanionEntity.Task;
import dev.begleiter.CompanionNeeds.Need;
import dev.begleiter.ai.DecisionOutcome;
import dev.begleiter.ai.DecisionOutcome.Reason;
import dev.begleiter.task.TaskSpec;
import net.fabricmc.fabric.api.gametest.v1.GameTest;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.util.ProblemReporter;
import net.minecraft.world.Container;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.storage.TagValueInput;
import net.minecraft.world.level.storage.TagValueOutput;

/** Result verification, contextual feedback, owner-bound follow-ups and real departure preparation. */
public class SmarterGameTests {
    private static final String ARENA = "begleiter-gametest:request_isolation";

    private static CompanionEntity companion(GameTestHelper h) {
        for (int x = 18; x <= 30; x++) for (int z = 18; z <= 30; z++) h.setBlock(x, 0, z, Blocks.STONE);
        var c = h.spawn(CompanionMod.COMPANION, new BlockPos(24, 1, 24));
        c.stay(); c.setNoAi(true); c.setPersistenceRequired(); c.buildModeOverride = false;
        return c;
    }

    private static ServerPlayer owner(GameTestHelper h, CompanionEntity c) {
        var p = h.makeMockServerPlayerInLevel();
        c.setOwner(p.getUUID());
        p.snapTo(c.getX(), c.getY(), c.getZ(), 0, 0);
        return p;
    }

    private static void tick(CompanionEntity c, CompanionTaskGoal goal, int ticks) {
        for (int i = 0; i < ticks && c.hasTasks() && !c.isPaused(); i++) { c.tickCount++; goal.tick(); }
    }

    private static CompanionEntity restore(GameTestHelper h, CompanionEntity c) {
        var out = TagValueOutput.createWithContext(ProblemReporter.DISCARDING, h.getLevel().registryAccess());
        c.saveWithoutId(out);
        var copy = new CompanionEntity(CompanionMod.COMPANION, h.getLevel());
        copy.load(TagValueInput.create(ProblemReporter.DISCARDING, h.getLevel().registryAccess(), out.buildResult()));
        return copy;
    }

    @GameTest(structure = ARENA)
    public void smarter_build_retains_unconfirmed_result_and_repairs_after_resume(GameTestHelper h) {
        var c = companion(h); owner(h, c); c.buildModeOverride = true;
        var at = h.absolutePos(new BlockPos(26, 1, 24));
        h.setBlock(26, 1, 24, Blocks.GOLD_BLOCK);
        var job = new CompanionBuilder.BuildJob("verify", List.of(new CompanionBuilder.Placement(at, Blocks.STONE.defaultBlockState())));
        job.index = 1; job.repairRounds = 2; c.build = job; c.addTask(Task.build());
        var goal = new CompanionTaskGoal(c);
        tick(c, goal, 1);
        h.assertTrue(c.isPaused() && c.build == job && job.paused, "Unconfirmed build reported success or was lost");
        h.assertTrue(h.getLevel().getBlockState(at).is(Blocks.GOLD_BLOCK), "Verification changed a block");
        h.getLevel().setBlock(at, Blocks.AIR.defaultBlockState(), 3);
        CompanionBrain.resumeReply(c);
        tick(c, goal, 80);
        h.assertTrue(c.build == null && !c.hasTasks(), "Confirmed repair did not complete");
        h.assertTrue(h.getLevel().getBlockState(at).is(Blocks.STONE), "Completed without actual placement");
        h.succeed();
    }

    @GameTest(structure = ARENA)
    public void smarter_build_automatically_repairs_a_missing_block(GameTestHelper h) {
        var c = companion(h); c.buildModeOverride = true;
        var at = h.absolutePos(new BlockPos(26, 1, 24));
        var job = new CompanionBuilder.BuildJob("repair", List.of(new CompanionBuilder.Placement(at, Blocks.STONE.defaultBlockState())));
        job.index = 1; c.build = job; c.addTask(Task.build());
        tick(c, new CompanionTaskGoal(c), 80);
        h.assertTrue(c.build == null && !c.hasTasks() && !c.isPaused(), "Automatic repair did not finish");
        h.assertTrue(h.getLevel().getBlockState(at).is(Blocks.STONE), "Missing block was never repaired");
        h.succeed();
    }

    @GameTest(structure = ARENA)
    public void smarter_build_checks_clearance_and_unloaded_positions(GameTestHelper h) {
        var at = h.absolutePos(new BlockPos(26, 1, 24));
        h.setBlock(26, 1, 24, Blocks.STONE);
        var far = new BlockPos(24_000_000, 100, -24_000_000);
        var job = new CompanionBuilder.BuildJob("clearance", List.of(
                new CompanionBuilder.Placement(at, Blocks.AIR.defaultBlockState()),
                new CompanionBuilder.Placement(far, Blocks.STONE.defaultBlockState())));
        h.assertTrue(h.getLevel().getChunkSource().getChunkNow(far.getX() >> 4, far.getZ() >> 4) == null, "Fixture chunk loaded");
        var check = CompanionBuilder.finishChecked(h.getLevel(), job);
        h.assertTrue(!check.complete() && check.mismatches() == 1 && check.unknown() == 1,
                "Blocked clearance or unloaded position silently accepted");
        h.assertTrue(h.getLevel().getChunkSource().getChunkNow(far.getX() >> 4, far.getZ() >> 4) == null, "Verification loaded a chunk");
        h.succeed();
    }

    @GameTest(structure = ARENA)
    public void smarter_build_verification_accepts_world_driven_changes(GameTestHelper h) {
        // Gras auf geplanter Erde und eine umgeschaltete Weiche sind Betrieb; ein falscher Block bleibt ein Fehler.
        h.setBlock(26, 1, 24, Blocks.GRASS_BLOCK);
        h.setBlock(27, 0, 24, Blocks.STONE);
        h.setBlock(27, 1, 24, Blocks.RAIL.defaultBlockState().setValue(net.minecraft.world.level.block.state.properties.BlockStateProperties.RAIL_SHAPE,
                net.minecraft.world.level.block.state.properties.RailShape.SOUTH_EAST));
        h.setBlock(28, 1, 24, Blocks.COBBLESTONE);
        var rail = Blocks.RAIL.defaultBlockState().setValue(net.minecraft.world.level.block.state.properties.BlockStateProperties.RAIL_SHAPE,
                net.minecraft.world.level.block.state.properties.RailShape.NORTH_EAST);
        var job = new CompanionBuilder.BuildJob("world-driven", List.of(
                new CompanionBuilder.Placement(h.absolutePos(new BlockPos(26, 1, 24)), Blocks.DIRT.defaultBlockState()),
                new CompanionBuilder.Placement(h.absolutePos(new BlockPos(27, 1, 24)), rail)));
        var result = CompanionBuilder.finishChecked(h.getLevel(), job);
        h.assertTrue(result.complete() && result.mismatches() == 0, "Grass on planned dirt or a switched rail paused a finished build");
        var wrong = new CompanionBuilder.BuildJob("wrong-block", List.of(
                new CompanionBuilder.Placement(h.absolutePos(new BlockPos(28, 1, 24)), Blocks.DIRT.defaultBlockState())));
        result = CompanionBuilder.finishChecked(h.getLevel(), wrong);
        h.assertTrue(!result.complete() && result.mismatches() == 1, "A really wrong block was accepted as a natural change");
        h.succeed();
    }

    private static CompanionEntity supply(GameTestHelper h) {
        var c = companion(h); c.goal = Need.SUPPLY;
        c.activeSupplyResource = "kohle"; c.activeSupplyApproach = "mining";
        c.addTask(Task.gather("kohle", 5)); c.planFeedback.begin(c, Need.SUPPLY, null);
        return c;
    }

    private static void complete(CompanionEntity c) {
        while (c.hasTasks()) c.finishTask();
        c.planFeedback.completeIfIdle(c);
    }

    @GameTest(structure = ARENA)
    public void smarter_learning_specific_tool_failure_replaces_generic_failure(GameTestHelper h) {
        var c = supply(h);
        c.planFeedback.failed(c, Reason.PLAN_FAILED, c.currentTask());
        c.planFeedback.failed(c, Reason.MISSING_TOOL, c.currentTask());
        complete(c);
        h.assertTrue(c.planFeedback.last().reason() == Reason.MISSING_TOOL, "Specific cause lost");
        h.assertValueEqual(c.experiences.penalty(Need.SUPPLY, c.tickCount), 0, "Tool shortage punished whole goal");
        h.assertValueEqual(c.experiences.penalty(Need.SUPPLY, "kohle_mining", c.tickCount), 0, "Tool shortage punished mine");
        h.assertValueEqual(c.experiences.learnedPreference(Need.SUPPLY, "kohle_mining"), 0, "Tool shortage damaged long-term preference");
        h.succeed();
    }

    @GameTest(structure = ARENA)
    public void smarter_learning_empty_source_is_local_and_rehabilitates(GameTestHelper h) {
        var c = supply(h);
        var source = Task.take(h.absolutePos(new BlockPos(26, 1, 24)), "coal", 5);
        c.planFeedback.failed(c, Reason.PLAN_FAILED, c.currentTask());
        c.planFeedback.failed(c, Reason.SOURCE_EMPTY, source);
        complete(c);
        var key = CompanionSources.key(c, Need.SUPPLY, Items.COAL, source.pos());
        var other = CompanionSources.key(c, Need.SUPPLY, Items.COAL, source.pos().east(5));
        h.assertTrue(c.experiences.sources.blocked(key, c.tickCount) && !c.experiences.sources.blocked(other, c.tickCount), "Source context leaked");
        h.assertValueEqual(c.experiences.learnedPreference(Need.SUPPLY, "kohle_chest"), 0, "Empty source punished all chests");
        c.experiences.sources.succeeded(key);
        h.assertTrue(!c.experiences.sources.blocked(key, c.tickCount), "Successful source not rehabilitated");
        h.succeed();
    }

    @GameTest(structure = ARENA)
    public void smarter_learning_unlocated_source_failure_does_not_generalize(GameTestHelper h) {
        var c = companion(h);
        c.experiences.record(new DecisionOutcome(Need.SUPPLY, Reason.SOURCE_MISSING, "kohle_mining", 1));
        h.assertValueEqual(c.experiences.penalty(Need.SUPPLY, 1), 0, "Missing source punished entire goal");
        h.assertValueEqual(c.experiences.learnedPreference(Need.SUPPLY, "kohle_mining"), 0, "Unknown source damaged mining preference");
        c.experiences.record(new DecisionOutcome(Need.SUPPLY, Reason.ROUTE_UNSAFE, "kohle_mining", 2));
        h.assertTrue(c.experiences.learnedPreference(Need.SUPPLY, "kohle_mining") < 0, "Real route danger was discarded");
        var restored = new dev.begleiter.ai.CompanionExperienceMemory();
        restored.load(c.experiences.save(2), 0);
        h.assertValueEqual(restored.learnedPreference(Need.SUPPLY, "kohle_mining"), c.experiences.learnedPreference(Need.SUPPLY, "kohle_mining"), "Contextual learning lost on save");
        h.succeed();
    }

    @GameTest(structure = ARENA)
    public void smarter_dialogue_armor_answer_preserves_quantity_and_old_work_until_answer(GameTestHelper h) {
        var c = companion(h); var p = owner(h, c); var old = Task.gather("holz", 3); c.addTask(old);
        CompanionBrain.onChat(p, "@ craft me 2 armor");
        h.assertTrue(c.requestDialogue.pending() && c.currentTask() == old, "Question replaced work");
        CompanionBrain.onChat(p, "@ status");
        h.assertTrue(c.requestDialogue.pending() && c.currentTask() == old, "Status discarded question");
        CompanionBrain.onChat(p, "iron");
        h.assertTrue(c.currentTask() == old, "Unaddressed answer executed");
        CompanionBrain.onChat(p, "@ Eisen");
        h.assertTrue(!c.requestDialogue.pending() && c.taskCount() == 4, "Armor answer did not continue original request");
        for (var task : c.peekTasks(8)) h.assertTrue(task.spec() instanceof TaskSpec.Request r && r.count() == 2
                && r.item().startsWith("minecraft:iron_") && !r.materials(), "Lost quantity, material or order kind");
        h.succeed();
    }

    @GameTest(structure = ARENA)
    public void smarter_dialogue_count_answer_and_cancel_are_explicit(GameTestHelper h) {
        var c = companion(h); var p = owner(h, c); var old = Task.gather("holz", 3); c.addTask(old);
        CompanionBrain.onChat(p, "@ craft me 0 stonecutters");
        h.assertTrue(c.requestDialogue.pending() && c.currentTask() == old, "Invalid count replaced work");
        CompanionBrain.onChat(p, "@ three");
        h.assertTrue(c.peekTasks(8).equals(List.of(Task.request("stonecutter", 3, false))), "Quantity answer did not continue request");
        CompanionBrain.onChat(p, "@ crafte mir eine Rüstung");
        h.assertTrue(c.requestDialogue.pending(), "No new armor question");
        CompanionBrain.onChat(p, "@ cancel");
        h.assertTrue(!c.requestDialogue.pending() && !c.hasTasks(), "Cancel retained a hidden question");
        CompanionBrain.onChat(p, "@ Eisen");
        h.assertTrue(!c.hasTasks(), "Cancelled question executed later");
        h.succeed();
    }

    @GameTest(structure = ARENA)
    public void smarter_dialogue_target_answer_keeps_material_order_kind(GameTestHelper h) {
        var c = companion(h); var p = owner(h, c);
        CompanionBrain.onChat(p, "@ bring me ingredients for");
        h.assertTrue(c.requestDialogue.pending() && !c.hasTasks(), "Missing target did not ask");
        CompanionBrain.onChat(p, "@ iron sword");
        h.assertTrue(c.peekTasks(8).equals(List.of(Task.request("iron_sword", 1, true))), "Follow-up changed material request into crafting");
        h.succeed();
    }

    @GameTest(structure = ARENA)
    public void smarter_dialogue_saved_question_is_owner_bound_and_expires(GameTestHelper h) {
        var c = companion(h); var p = owner(h, c); c.addTask(Task.gather("holz", 3));
        CompanionBrain.onChat(p, "@ crafte mir eine Rüstung");
        var copy = restore(h, c);
        h.assertTrue(copy.requestDialogue.pending(), "Question lost on entity reload");
        var stranger = h.makeMockServerPlayerInLevel();
        var before = copy.peekTasks(8);
        h.assertTrue(copy.requestDialogue.answer(stranger, copy, "iron") == null && copy.peekTasks(8).equals(before), "Other player consumed question");
        CompanionChatActions.interpretReply(p, copy, "iron", "en_us");
        h.assertTrue(copy.taskCount() == 4 && !copy.requestDialogue.pending(), "Owner could not continue saved question");
        CompanionBrain.onChat(p, "@ craft me armor");
        var saved = c.requestDialogue.save(h.getLevel().getGameTime());
        c.requestDialogue.load(saved, h.getLevel().getGameTime() - 1201);
        before = c.peekTasks(8);
        h.assertTrue(c.requestDialogue.answer(p, c, "iron") == null && c.peekTasks(8).equals(before), "Expired question executed");
        saved.putString("Kind", "broken"); c.requestDialogue.load(saved, h.getLevel().getGameTime());
        h.assertTrue(!c.requestDialogue.pending(), "Corrupt question restored");
        h.succeed();
    }

    @GameTest(structure = ARENA)
    public void smarter_dialogue_only_accepts_offered_item_choices(GameTestHelper h) {
        var c = companion(h); var p = owner(h, c); var old = Task.gather("holz", 3); c.addTask(old);
        c.requestDialogue.ask(c, CompanionRequestDialogue.Kind.ITEM, "boat", 2, false,
                List.of("minecraft:oak_boat", "minecraft:spruce_boat"));
        h.assertTrue(c.requestDialogue.answer(p, c, "diamond") == null && c.currentTask() == old, "Accepted an unoffered choice");
        h.assertTrue(CompanionChatActions.interpretReply(p, c, "diamond", "en_us") != null && c.currentTask() == old,
                "Invalid clarification fell through to an unrelated LLM order");
        CompanionBrain.onChat(p, "@ minecraft:spruce_boat");
        h.assertTrue(c.peekTasks(8).equals(List.of(Task.request("spruce_boat", 2, false))), "Offered choice did not continue order");
        h.succeed();
    }

    @GameTest(structure = ARENA)
    public void smarter_stock_rule_refills_after_reload_and_waits_for_idle(GameTestHelper h) {
        var c = companion(h);
        var player = owner(h, c);
        h.setBlock(26, 1, 24, Blocks.BARREL);
        var home = h.absolutePos(new BlockPos(26, 1, 24));
        c.setHome(home);
        var box = (Container) h.getLevel().getBlockEntity(home);
        c.giveToInventory(new ItemStack(Items.TORCH));
        c.giveToInventory(new ItemStack(Items.COAL));
        c.giveToInventory(new ItemStack(Items.STICK));

        var travel = Task.moveTo(c.blockPosition().east(4), 16);
        c.addTask(travel);
        CompanionBrain.onChat(player, "@ Halte hier immer 4 Fackeln auf Vorrat");
        h.assertTrue(c.stockRules.size() == 1 && c.currentTask() == travel, "Stock rule replaced the current order or was not set");
        c.tickCount = 200;
        CompanionStockRoutine.tick(c, h.getLevel());
        h.assertTrue(box.isEmpty(), "Stock rule ran while another order was active");

        c.cancelAll();
        CompanionStockRoutine.tick(c, h.getLevel());
        h.assertValueEqual(box.countItem(Items.TORCH), 4, "Wrong amount delivered from inventory and crafting");
        h.assertValueEqual(c.getInventory().countItem(Items.TORCH), 1, "Recipe surplus was not retained");
        h.assertValueEqual(c.getInventory().countItem(Items.COAL), 0, "Recipe did not consume the input");
        var restored = restore(h, c);
        h.assertTrue(c.stockRules.equals(restored.stockRules), "Saved stock rule changed on reload");
        box.getItem(0).shrink(1);
        restored.tickCount = 200;
        CompanionStockRoutine.tick(restored, h.getLevel());
        h.assertValueEqual(box.countItem(Items.TORCH), 4, "Restored rule did not top up the shortage");
        h.assertValueEqual(restored.getInventory().countItem(Items.TORCH), 0, "Surplus transfer was not booked");
        h.succeed();
    }

    @GameTest(structure = ARENA)
    public void smarter_stock_rule_respects_capacity_and_can_be_cleared(GameTestHelper h) {
        var c = companion(h);
        var player = owner(h, c);
        h.setBlock(26, 1, 24, Blocks.BARREL);
        var home = h.absolutePos(new BlockPos(26, 1, 24));
        c.setHome(home);
        var box = (Container) h.getLevel().getBlockEntity(home);
        for (int slot = 0; slot < box.getContainerSize(); slot++) box.setItem(slot, new ItemStack(Items.STONE, 64));
        c.giveToInventory(new ItemStack(Items.COAL));
        c.giveToInventory(new ItemStack(Items.STICK));
        CompanionBrain.onChat(player, "@ keep 32 minecraft:torch in stock");
        h.assertTrue(c.stockRules.size() == 1, "English command with a registry ID was not accepted");
        c.tickCount = 200;
        CompanionStockRoutine.tick(c, h.getLevel());
        h.assertTrue(c.getInventory().countItem(Items.COAL) == 1 && c.getInventory().countItem(Items.TORCH) == 0,
                "A full chest still triggered crafting");
        box.setItem(0, ItemStack.EMPTY);
        c.tickCount = 400;
        CompanionStockRoutine.tick(c, h.getLevel());
        h.assertValueEqual(box.countItem(Items.TORCH), 4, "Available ingredients did not top up a partial batch");

        CompanionBrain.onChat(player, "@ Vorratsregel löschen");
        h.assertTrue(c.stockRules.isEmpty(), "Owner could not clear the rule");
        h.succeed();
    }

    @GameTest(structure = ARENA)
    public void smarter_stock_rule_combines_storage_and_carried_ingredients(GameTestHelper h) {
        var c = companion(h);
        var player = owner(h, c);
        h.setBlock(26, 1, 24, Blocks.BARREL);
        var home = h.absolutePos(new BlockPos(26, 1, 24));
        c.setHome(home);
        var box = (Container) h.getLevel().getBlockEntity(home);
        box.setItem(0, new ItemStack(Items.COAL, 3));
        box.setItem(1, new ItemStack(Items.STICK, 2));
        c.giveToInventory(new ItemStack(Items.STICK));

        CompanionBrain.onChat(player, "@ keep 8 torches in stock");
        h.assertTrue(c.stockRules.size() == 1, "Stock rule was not set");
        c.tickCount = 200;
        CompanionStockRoutine.tick(c, h.getLevel());
        h.assertValueEqual(box.countItem(Items.TORCH), 8, "The mixed ingredients did not provide the full batch");
        h.assertValueEqual(box.countItem(Items.COAL), 1, "Wrong coal deduction");
        h.assertValueEqual(box.countItem(Items.STICK), 1, "Carried ingredient was not used first");
        h.assertValueEqual(c.getInventory().countItem(Items.STICK), 0, "Carried ingredient was not consumed");

        box.setItem(1, ItemStack.EMPTY);
        box.setItem(2, ItemStack.EMPTY);
        c.tickCount = 400;
        CompanionStockRoutine.tick(c, h.getLevel());
        h.assertValueEqual(box.countItem(Items.COAL), 1, "A failed craft consumed stored coal");
        h.assertValueEqual(box.countItem(Items.TORCH), 0, "Crafted without enough ingredients");
        h.succeed();
    }

    @GameTest(structure = ARENA)
    public void smarter_stock_rule_crafts_directly_into_freed_storage_slots(GameTestHelper h) {
        var c = companion(h);
        var player = owner(h, c);
        h.setBlock(26, 1, 24, Blocks.BARREL);
        var home = h.absolutePos(new BlockPos(26, 1, 24));
        c.setHome(home);
        var box = (Container) h.getLevel().getBlockEntity(home);
        for (int slot = 2; slot < box.getContainerSize(); slot++)
            box.setItem(slot, new ItemStack(Items.STONE, 64));
        box.setItem(0, new ItemStack(Items.COAL));
        box.setItem(1, new ItemStack(Items.STICK));
        for (int slot = 0; slot < c.getInventory().getContainerSize(); slot++)
            c.getInventory().setItem(slot, new ItemStack(Items.BEDROCK, 64));
        CompanionBrain.onChat(player, "@ keep 3 torches in stock");
        h.assertTrue(c.stockRules.size() == 1, "Stock rule was not set");
        c.tickCount = 200;
        CompanionStockRoutine.tick(c, h.getLevel());
        h.assertValueEqual(box.countItem(Items.COAL), 1, "A batch with no room for surplus consumed coal");
        h.assertValueEqual(box.countItem(Items.STICK), 1, "A batch with no room for surplus consumed sticks");
        h.assertValueEqual(box.countItem(Items.TORCH), 0, "Crafted despite not being able to place the surplus");

        CompanionBrain.onChat(player, "@ keep 4 torches in stock");
        c.tickCount = 400;
        CompanionStockRoutine.tick(c, h.getLevel());
        h.assertValueEqual(box.countItem(Items.TORCH), 4, "Did not craft into spaces freed by the ingredients");
        h.assertValueEqual(box.countItem(Items.COAL) + box.countItem(Items.STICK), 0, "Box ingredients were not consumed");
        h.assertValueEqual(box.countItem(Items.STONE), 64 * (box.getContainerSize() - 2), "Unrelated goods were changed");
        h.assertValueEqual(c.getInventory().countItem(Items.BEDROCK), 64 * c.getInventory().getContainerSize(),
                "The full inventory changed during direct storage");
        h.succeed();
    }

    @GameTest(structure = ARENA)
    public void smarter_stock_rules_share_chest_without_using_reserved_ingredients(GameTestHelper h) {
        var c = companion(h);
        var player = owner(h, c);
        h.setBlock(26, 1, 24, Blocks.BARREL);
        var home = h.absolutePos(new BlockPos(26, 1, 24));
        c.setHome(home);
        var box = (Container) h.getLevel().getBlockEntity(home);
        box.setItem(0, new ItemStack(Items.STICK, 2));
        box.setItem(1, new ItemStack(Items.STICK, 2));
        box.setItem(2, new ItemStack(Items.COAL, 2));
        c.giveToInventory(new ItemStack(Items.BREAD));

        CompanionBrain.onChat(player, "@ keep 4 torches in stock");
        CompanionBrain.onChat(player, "@ keep 4 minecraft:stick in stock");
        CompanionBrain.onChat(player, "@ keep 1 minecraft:bread in stock");
        h.assertValueEqual(c.stockRules.size(), 3, "New rules replaced existing ones");
        var restored = restore(h, c);
        h.assertTrue(c.stockRules.equals(restored.stockRules), "Saved rules changed on reload");
        c.tickCount = 200;
        CompanionStockRoutine.tick(c, h.getLevel());
        h.assertValueEqual(box.countItem(Items.TORCH), 0, "Torches consumed reserved sticks");
        h.assertValueEqual(box.countItem(Items.BREAD), 1, "An unavailable recipe blocked another rule");

        box.getItem(1).grow(1);
        c.tickCount = 400;
        CompanionStockRoutine.tick(c, h.getLevel());
        h.assertValueEqual(box.countItem(Items.TORCH), 4, "Surplus sticks were not used");
        h.assertValueEqual(box.getItem(0).getCount(), 2, "A fully reserved stack was changed");
        h.assertValueEqual(box.getItem(1).getCount(), 2, "Consumed more than the surplus in a mixed stack");

        CompanionBrain.onChat(player, "@ Vorratsregel minecraft:bread löschen");
        h.assertValueEqual(c.stockRules.size(), 2, "Removing bread affected other rules");
        CompanionBrain.onChat(player, "@ keep 8 torches in stock");
        h.assertValueEqual(c.stockRules.size(), 2, "Updating torches added a duplicate rule");
        h.assertValueEqual(c.stockRules.getFirst().target(), 8, "Torch target was not updated");
        h.succeed();
    }

    @GameTest(structure = ARENA)
    public void smarter_stock_rules_load_old_single_rule(GameTestHelper h) {
        var c = companion(h);
        var player = owner(h, c);
        c.setHome(h.absolutePos(new BlockPos(26, 1, 24)));
        CompanionBrain.onChat(player, "@ keep 4 torches in stock");
        var out = TagValueOutput.createWithContext(ProblemReporter.DISCARDING, h.getLevel().registryAccess());
        out.store("StockRoutineV1", CompanionStockRoutine.CODEC, c.stockRules.getFirst());
        var legacy = out.buildResult();
        var saved = TagValueOutput.createWithContext(ProblemReporter.DISCARDING, h.getLevel().registryAccess());
        c.saveWithoutId(saved);
        var tag = saved.buildResult();
        tag.remove("StockRulesV2");
        tag.merge(legacy);
        var restored = new CompanionEntity(CompanionMod.COMPANION, h.getLevel());
        restored.load(TagValueInput.create(ProblemReporter.DISCARDING, h.getLevel().registryAccess(), tag));
        h.assertTrue(c.stockRules.equals(restored.stockRules), "The previous single-rule format was not loaded");
        h.succeed();
    }

    @GameTest(structure = ARENA)
    public void smarter_home_order_uses_stock_and_preferred_fuel_after_reload(GameTestHelper h) {
        var c = companion(h);
        var player = owner(h, c);
        h.setBlock(26, 1, 24, Blocks.BARREL);
        h.setBlock(24, 1, 26, Blocks.FURNACE);
        h.setBlock(24, 1, 25, Blocks.FURNACE);
        var busy = h.absolutePos(new BlockPos(24, 1, 25));
        var occupied = (Container) h.getLevel().getBlockEntity(busy);
        occupied.setItem(0, new ItemStack(Items.RAW_IRON));
        c.rememberStation("furnace", busy);
        h.assertValueEqual(c.findOrderStation("furnace", 8), h.absolutePos(new BlockPos(24, 1, 26)),
                "A cached occupied furnace hid the available furnace");
        var home = h.absolutePos(new BlockPos(26, 1, 24));
        c.setHome(home);
        var box = (Container) h.getLevel().getBlockEntity(home);
        box.setItem(0, new ItemStack(Items.GLASS, 2));
        box.setItem(1, new ItemStack(Items.SAND, 4));
        box.setItem(2, new ItemStack(Items.CHARCOAL));
        c.giveToInventory(new ItemStack(Items.COAL));

        CompanionBrain.onChat(player, "@ Bring mir 6 Glas ins Lager mit Holzkohle");
        h.assertTrue(c.currentTask() != null && c.currentTask().spec() instanceof TaskSpec.Request r && r.homeOrder(),
                "The chat command did not enqueue a home delivery");
        var goal = new CompanionTaskGoal(c);
        tick(c, goal, 10);
        CompanionBrain.onChat(player, "@ pause");
        h.assertTrue(c.isPaused(), "The order did not pause");
        var restored = restore(h, c);
        h.assertTrue(restored.isPaused()
                        && restored.pausedReason().getContents() instanceof net.minecraft.network.chat.contents.TranslatableContents reason
                        && reason.getKey().equals("server.begleiter.order.held")
                        && CompanionTexts.status(restored).getString().contains(restored.pausedReason().getString()),
                "The pause reason did not survive reload");
        restored.resume();
        tick(restored, new CompanionTaskGoal(restored), 310);
        h.assertValueEqual(box.countItem(Items.GLASS), 6, "Home order did not deliver the shortage");
        h.assertValueEqual(box.countItem(Items.SAND), 0, "Home order did not use the accessible inputs");
        h.assertValueEqual(box.countItem(Items.CHARCOAL), 0, "The requested fuel was not used");
        h.assertValueEqual(restored.getInventory().countItem(Items.COAL), 1, "Coal was burned instead of the preferred charcoal");
        h.assertValueEqual(occupied.countItem(Items.RAW_IRON), 1, "The owner's furnace input was changed");
        h.assertTrue(!restored.hasTasks(), "The finished order remained in the queue");
        h.succeed();
    }

    @GameTest(structure = ARENA)
    public void smarter_home_order_resumes_a_partial_delivery_when_storage_was_full(GameTestHelper h) {
        var c = companion(h);
        var player = owner(h, c);
        h.setBlock(26, 1, 24, Blocks.BARREL);
        var home = h.absolutePos(new BlockPos(26, 1, 24));
        c.setHome(home);
        var box = (Container) h.getLevel().getBlockEntity(home);
        for (int i = 1; i < box.getContainerSize(); i++) box.setItem(i, new ItemStack(Items.COBBLESTONE, 64));
        box.setItem(0, new ItemStack(Items.GLASS, 63));
        c.giveToInventory(new ItemStack(Items.GLASS, 3));
        CompanionBrain.onChat(player, "@ Bring 66 Glas ins Lager");
        tick(c, new CompanionTaskGoal(c), 5);
        h.assertTrue(c.isPaused(), "A full chest did not pause the order");
        h.assertValueEqual(box.countItem(Items.GLASS), 64, "The partial delivery did not count");
        h.assertValueEqual(c.getInventory().countItem(Items.GLASS), 2, "Items that did not fit were lost");
        var restored = restore(h, c);
        box.setItem(1, ItemStack.EMPTY);
        restored.resume();
        tick(restored, new CompanionTaskGoal(restored), 5);
        h.assertValueEqual(box.countItem(Items.GLASS), 66, "The remainder did not resume");
        h.assertTrue(!restored.hasTasks(), "The finished order remained active");
        h.succeed();
    }

    @GameTest(structure = ARENA)
    public void smarter_home_orders_bundle_pickups_only_from_an_allowed_storage_area(GameTestHelper h) {
        var c = companion(h);
        var player = owner(h, c);
        h.setBlock(26, 1, 24, Blocks.BARREL);
        h.setBlock(24, 1, 26, Blocks.BARREL);
        var home = h.absolutePos(new BlockPos(26, 1, 24));
        var source = h.absolutePos(new BlockPos(24, 1, 26));
        c.setHome(home);
        var box = (Container) h.getLevel().getBlockEntity(home);
        var supply = (Container) h.getLevel().getBlockEntity(source);
        supply.setItem(0, new ItemStack(Items.DIAMOND_BLOCK, 3));
        supply.setItem(1, new ItemStack(Items.NETHERITE_BLOCK, 2));
        h.assertTrue(c.orderStorage(h.getLevel(), source) == null, "An unmarked chest was usable for a home order");
        c.zones.put(CompanionZones.between(CompanionZones.Kind.STORAGE, CompanionMemory.dim(h.getLevel()), source, source));

        CompanionBrain.onChat(player, "@ Bring 2 minecraft:netherite_block ins Lager");
        CompanionBrain.onChat(player, "@ Dringend bring 1 minecraft:diamond_block ins Lager");
        CompanionBrain.onChat(player, "@ Dringend bring 3 minecraft:diamond_block ins Lager");
        h.assertValueEqual(c.taskCount(), 2, "A compatible order was not merged");
        var goal = new CompanionTaskGoal(c);
        tick(c, goal, 1);
        var tasks = c.peekTasks(4);
        h.assertTrue(tasks.size() == 4 && tasks.get(0).spec() instanceof TaskSpec.Take first
                && tasks.get(1).spec() instanceof TaskSpec.Take second && first.pos().equals(second.pos()),
                "Compatible pickups were not bundled");
        tick(c, goal, 20);
        h.assertValueEqual(box.countItem(Items.DIAMOND_BLOCK), 3, "Urgent order did not deliver");
        h.assertValueEqual(box.countItem(Items.NETHERITE_BLOCK), 2, "Bundled order did not deliver");
        h.succeed();
    }

    @GameTest(structure = ARENA)
    public void smarter_stock_crafts_intermediates_and_guards_reserved_inputs(GameTestHelper h) {
        var c = companion(h);
        var player = owner(h, c);
        h.setBlock(26, 1, 24, Blocks.BARREL);
        var home = h.absolutePos(new BlockPos(26, 1, 24));
        c.setHome(home);
        var box = (Container) h.getLevel().getBlockEntity(home);
        box.setItem(0, new ItemStack(Items.OAK_PLANKS, 4));
        CompanionBrain.onChat(player, "@ keep 4 torches in stock");
        CompanionBrain.onChat(player, "@ keep 2 minecraft:oak_planks in stock");
        c.tickCount = 200;
        CompanionStockRoutine.tick(c, h.getLevel());
        h.assertValueEqual(box.countItem(Items.OAK_PLANKS), 4, "An incomplete chain used planks");
        h.assertValueEqual(c.getInventory().countItem(Items.STICK), 0, "An incomplete chain produced sticks");
        box.setItem(1, new ItemStack(Items.COAL));
        c.tickCount = 400;
        CompanionStockRoutine.tick(c, h.getLevel());
        h.assertValueEqual(box.countItem(Items.TORCH), 4, "The chain did not make sticks and torches");
        h.assertValueEqual(box.countItem(Items.OAK_PLANKS), 2, "The chain broke the plank reserve");
        h.assertValueEqual(c.getInventory().countItem(Items.STICK), 3, "Surplus sticks were not kept");
        h.succeed();
    }

    @GameTest(structure = ARENA)
    public void smarter_stock_smelting_uses_only_surplus_fuel(GameTestHelper h) {
        var c = companion(h);
        var player = owner(h, c);
        h.setBlock(26, 1, 24, Blocks.BARREL);
        h.setBlock(24, 1, 26, Blocks.FURNACE);
        var home = h.absolutePos(new BlockPos(26, 1, 24));
        c.setHome(home);
        var box = (Container) h.getLevel().getBlockEntity(home);
        box.setItem(0, new ItemStack(Items.SAND, 2));
        box.setItem(1, new ItemStack(Items.COAL));
        CompanionBrain.onChat(player, "@ keep 2 glass in stock");
        CompanionBrain.onChat(player, "@ keep 1 coal in stock");
        c.tickCount = 200;
        CompanionStockRoutine.tick(c, h.getLevel());
        h.assertValueEqual(box.countItem(Items.GLASS), 0, "Stock routine burned reserved coal");
        box.getItem(1).grow(1);
        c.tickCount = 400;
        CompanionStockRoutine.tick(c, h.getLevel());
        var restored = restore(h, c);
        restored.tickCount = 600;
        CompanionStockRoutine.tick(restored, h.getLevel());
        h.assertValueEqual(box.countItem(Items.GLASS), 2, "Local smelting did not use surplus fuel or its burn credit");
        h.assertValueEqual(box.countItem(Items.COAL), 1, "Smelting did not honor the reserve");
        h.succeed();
    }

    @GameTest(structure = ARENA)
    public void smarter_stock_collection_stays_in_the_marked_area_and_rechecks_permission(GameTestHelper h) {
        var c = companion(h);
        var player = owner(h, c);
        h.setBlock(26, 1, 24, Blocks.BARREL);
        h.setBlock(24, 1, 23, Blocks.SAND);
        h.setBlock(24, 1, 26, Blocks.SAND);
        var home = h.absolutePos(new BlockPos(26, 1, 24));
        var source = h.absolutePos(new BlockPos(24, 1, 26));
        c.setHome(home);
        c.makeKeeper();
        CompanionBrain.onChat(player, "@ keep 1 sand in stock");
        CompanionBrain.onChat(player, "@ stock rule sand gather");
        c.tickCount = 200;
        CompanionStockRoutine.tick(c, h.getLevel());
        h.assertTrue(!c.hasTasks(), "No zone was configured but harvesting was scheduled");
        var zone = CompanionZones.between(CompanionZones.Kind.ROAM, CompanionMemory.dim(h.getLevel()), source, source);
        c.zones.put(zone);
        CompanionStockRoutine.tick(c, h.getLevel());
        h.assertTrue(c.currentTask() != null && source.equals(c.currentTask().pos()), "The nearer outside source was chosen");
        c.zones.remove(CompanionZones.Kind.ROAM);
        tick(c, new CompanionTaskGoal(c), 30);
        h.assertTrue(h.getLevel().getBlockState(source).is(Blocks.SAND), "A stale task mined after zone removal");
        c.zones.put(zone);
        c.tickCount = 2000;
        CompanionStockRoutine.tick(c, h.getLevel());
        tick(c, new CompanionTaskGoal(c), 70);
        c.tickCount = 2200;
        CompanionStockRoutine.tick(c, h.getLevel());
        var box = (Container) h.getLevel().getBlockEntity(home);
        h.assertValueEqual(box.countItem(Items.SAND), 1, "A permitted source was not harvested and stored");
        h.assertTrue(h.getLevel().getBlockState(h.absolutePos(new BlockPos(24, 1, 23))).is(Blocks.SAND), "Outside sand was touched");
        h.succeed();
    }

    @GameTest(structure = ARENA)
    public void smarter_trip_checks_food_tools_space_without_loading_destination(GameTestHelper h) {
        var c = companion(h); c.giveToInventory(new ItemStack(Items.COOKED_BEEF, 8));
        c.giveToInventory(new ItemStack(Items.IRON_PICKAXE)); c.addTask(Task.gather("diamant", 10));
        var target = new BlockPos(24_000_000, 100, -24_000_000);
        h.assertValueEqual(CompanionTripPreparation.check(c, target).reason(), Reason.MISSING_INPUT,
                "The longer trip did not require additional food");
        c.giveToInventory(new ItemStack(Items.COOKED_BEEF, 4));
        h.assertTrue(CompanionTripPreparation.check(c, target).ready(), "Prepared trip rejected");
        h.assertTrue(h.getLevel().getChunkSource().getChunkNow(target.getX() >> 4, target.getZ() >> 4) == null, "Preparation loaded destination");
        c.getInventory().getItem(c.findItem(Items.IRON_PICKAXE)).setDamageValue(Items.IRON_PICKAXE.getDefaultInstance().getMaxDamage() - 2);
        var check = CompanionTripPreparation.check(c, target);
        h.assertTrue(!check.ready() && check.reason() == Reason.MISSING_TOOL, "Nearly broken pick accepted for mining trip");
        h.assertValueEqual(c.experiences.penalty(Need.SUPPLY, c.tickCount), 0, "Preparation punished a source");
        h.succeed();
    }

    @GameTest(structure = ARENA)
    public void smarter_trip_deposits_actual_stock_before_departure(GameTestHelper h) {
        var c = companion(h);
        for (int i = 0; i < 36; i++) c.getInventory().setItem(i, new ItemStack(Items.DIAMOND, 64));
        c.getInventory().setItem(0, new ItemStack(Items.COOKED_BEEF, 8));
        h.setBlock(26, 1, 24, Blocks.BARREL); h.setBlock(27, 1, 24, Blocks.BARREL);
        var home = h.absolutePos(new BlockPos(26, 1, 24)); c.setHome(home);
        var target = c.blockPosition().east(80); c.addTask(Task.travel(target));
        var goal = new CompanionTaskGoal(c); tick(c, goal, 1);
        h.assertTrue(c.currentTask().spec() instanceof TaskSpec.Deliver, "Did not queue storage before departure");
        tick(c, goal, 1);
        int stored = ((Container) h.getLevel().getBlockEntity(home)).countItem(Items.DIAMOND)
                + ((Container) h.getLevel().getBlockEntity(h.absolutePos(new BlockPos(27, 1, 24)))).countItem(Items.DIAMOND);
        h.assertValueEqual(stored, 35 * 64, "Storage step lost supplies");
        h.assertTrue(c.currentTask().spec() instanceof TaskSpec.Travel travel && travel.pos().equals(target), "Lost original destination");
        h.assertTrue(CompanionTripPreparation.check(c, target).ready(), "Did not actually free space");
        h.assertValueEqual(c.foodCount(), 8, "Departure preparation stored food reserve");
        h.succeed();
    }

    @GameTest(structure = ARENA)
    public void smarter_trip_packs_only_the_meals_it_needs_from_home(GameTestHelper h) {
        var c = companion(h);
        c.food.set(8);
        h.setBlock(26, 1, 24, Blocks.BARREL);
        var home = h.absolutePos(new BlockPos(26, 1, 24));
        c.setHome(home);
        var box = (Container) h.getLevel().getBlockEntity(home);
        box.setItem(0, new ItemStack(Items.GOLDEN_APPLE, 3));
        box.setItem(1, new ItemStack(Items.COOKED_BEEF, 10));
        var travel = Task.travel(c.blockPosition().east(80));
        c.addTask(travel);
        var position = c.position();
        tick(c, new CompanionTaskGoal(c), 1);
        h.assertTrue(!c.isPaused() && c.currentTask() == travel && c.position().equals(position), "Resupply interrupted travel");
        h.assertTrue(CompanionTripPreparation.check(c, travel.pos()).ready(), "Resupply did not make the trip ready");
        h.assertValueEqual(c.foodCount(), 4, "Wrong reserve after eating");
        h.assertValueEqual(box.countItem(Items.COOKED_BEEF), 5, "Took more than the meal and reserve");
        h.assertValueEqual(box.countItem(Items.GOLDEN_APPLE), 3, "Took valuable food unnecessarily");
        h.succeed();
    }

    @GameTest(structure = ARENA)
    public void smarter_trip_skips_home_food_without_spare_capacity(GameTestHelper h) {
        var c = companion(h);
        h.setBlock(26, 1, 24, Blocks.BARREL);
        var home = h.absolutePos(new BlockPos(26, 1, 24));
        c.setHome(home);
        var box = (Container) h.getLevel().getBlockEntity(home);
        box.setItem(0, new ItemStack(Items.COOKED_BEEF, 10));
        for (int i = 0; i < c.getInventory().getContainerSize() - 2; i++)
            c.getInventory().setItem(i, new ItemStack(Items.COBBLESTONE, 64));
        var travel = Task.travel(c.blockPosition().east(80));
        c.addTask(travel);
        tick(c, new CompanionTaskGoal(c), 1);
        h.assertTrue(c.isPaused() && c.currentTask() == travel, "Resupply took the space reserved for the trip");
        h.assertValueEqual(box.countItem(Items.COOKED_BEEF), 10, "Storage changed despite the capacity limit");
        h.succeed();
    }

    @GameTest(structure = ARENA)
    public void smarter_trip_packs_a_pickaxe_for_the_remaining_ore(GameTestHelper h) {
        var c = companion(h);
        var home = h.absolutePos(new BlockPos(26, 1, 24));
        h.setBlock(26, 1, 24, Blocks.BARREL);
        c.setHome(home);
        var box = (Container) h.getLevel().getBlockEntity(home);
        box.setItem(0, new ItemStack(Items.COOKED_BEEF, 8));
        box.setItem(1, new ItemStack(Items.DIAMOND_PICKAXE));
        box.setItem(2, new ItemStack(Items.STONE_PICKAXE));
        ItemStack worn = new ItemStack(Items.IRON_PICKAXE);
        worn.setDamageValue(worn.getMaxDamage() - 6); // Three safe uses per pickaxe.
        box.setItem(3, worn.copy());
        c.giveToInventory(worn.copy());
        c.giveToInventory(new ItemStack(Items.DIAMOND, 2));
        var travel = Task.travel(c.blockPosition().east(80));
        var gather = new Task(new TaskSpec.Gather("diamant", 8, 8));
        c.addTask(travel);
        c.addTask(gather);

        tick(c, new CompanionTaskGoal(c), 1);
        h.assertTrue(!c.isPaused() && c.peekTasks(2).equals(List.of(travel, gather)), "Resupply changed the intended expedition");
        h.assertTrue(CompanionTripPreparation.check(c, travel.pos()).ready(), "Food and tools did not make the trip ready");
        h.assertValueEqual(CompanionPrerequisites.availablePickUses(c, 3), 6, "Pickaxe wear was not preserved");
        h.assertTrue(box.getItem(3).isEmpty() && box.getItem(1).is(Items.DIAMOND_PICKAXE)
                && box.getItem(2).is(Items.STONE_PICKAXE), "Took a higher-tier or inadequate tool");
        h.succeed();
    }

    @GameTest(structure = ARENA)
    public void smarter_trip_combines_worn_tools_only_when_they_fit(GameTestHelper h) {
        var c = companion(h);
        var home = h.absolutePos(new BlockPos(26, 1, 24));
        h.setBlock(26, 1, 24, Blocks.BARREL);
        c.setHome(home);
        var box = (Container) h.getLevel().getBlockEntity(home);
        ItemStack worn = new ItemStack(Items.IRON_PICKAXE);
        worn.setDamageValue(worn.getMaxDamage() - 7); // Four safe uses per pickaxe.
        box.setItem(0, worn.copy());
        box.setItem(1, worn.copy());
        var inventory = c.getInventory();
        inventory.setItem(0, new ItemStack(Items.COOKED_BEEF, 8));
        for (int slot = 1; slot < inventory.getContainerSize() - 3; slot++)
            inventory.setItem(slot, new ItemStack(Items.COBBLESTONE, 64));
        var travel = Task.travel(c.blockPosition().east(80));
        c.addTask(travel);
        c.addTask(Task.gather("diamant", 8));
        var goal = new CompanionTaskGoal(c);
        tick(c, goal, 1);
        h.assertTrue(c.isPaused() && c.currentTask() == travel, "Tools took up travel reserve space");
        h.assertValueEqual(box.countItem(Items.IRON_PICKAXE), 2, "Partially withdrew tools that could not fit");

        inventory.setItem(1, ItemStack.EMPTY);
        CompanionBrain.resumeReply(c);
        tick(c, goal, 1);
        h.assertTrue(!c.isPaused() && c.currentTask() == travel, "Two adequate tools did not unblock the trip");
        h.assertValueEqual(CompanionPrerequisites.availablePickUses(c, 3), 8, "Wrong combined durability");
        h.assertTrue(box.isEmpty(), "Tools still in storage");
        h.succeed();
    }

    @GameTest(structure = ARENA)
    public void smarter_trip_does_not_eat_reserved_delivery_and_retains_paused_order(GameTestHelper h) {
        var c = companion(h); c.food.set(8); c.giveToInventory(new ItemStack(Items.COOKED_BEEF, 8));
        var target = c.blockPosition().east(80); c.addTask(Task.travel(target));
        c.addTask(new Task(new TaskSpec.Request("cooked_beef", 8, true, 2, 0)));
        var position = c.position(); tick(c, new CompanionTaskGoal(c), 1);
        h.assertTrue(c.isPaused() && c.taskCount() == 2 && c.position().equals(position), "Unprepared departure did not retain work");
        h.assertValueEqual(CompanionCraft.count(c, "cooked_beef"), 8, "Preparation ate promised delivery");
        var copy = restore(h, c);
        h.assertTrue(copy.isPaused() && copy.taskCount() == 2, "Paused departure lost on reload");
        h.succeed();
    }

    @GameTest(structure = ARENA)
    public void smarter_trip_eats_before_departure_and_checks_return_hazards(GameTestHelper h) {
        var c = companion(h); c.food.set(8); c.giveToInventory(new ItemStack(Items.COOKED_BEEF, 8));
        var target = c.blockPosition().east(80); c.addTask(Task.travel(target));
        tick(c, new CompanionTaskGoal(c), 1);
        h.assertTrue(!c.isPaused() && c.food.level() >= 12 && c.foodCount() == 7, "Did not eat available food before departure");
        var home = c.blockPosition().west(2); c.setHome(home);
        c.memory.remember(CompanionMemory.Kind.DANGER, "lava", h.getLevel(), home);
        var check = CompanionTripPreparation.check(c, target);
        h.assertTrue(!check.ready() && check.reason() == Reason.ROUTE_UNPROVEN, "Known return-point danger ignored");
        h.assertTrue(CompanionTripPreparation.check(c, c.blockPosition().east(32)).ready(), "Preparation changed short trips");
        c.buildModeOverride = true;
        h.assertTrue(CompanionTripPreparation.check(c, target).ready(), "Creative helper requires survival supplies");
        h.succeed();
    }

    @GameTest(structure = ARENA)
    public void smarter_messages_are_bilingual(GameTestHelper h) {
        ServerTextGameTests.assertTranslatedContains(h, CompanionTexts.tr("build.verification.blocked", 1, 1, 0), "nicht bestätigt", "not confirmed");
        ServerTextGameTests.assertTranslatedContains(h, CompanionTexts.tr("dialog.ask_count", 256), "Welche Menge", "How many");
        ServerTextGameTests.assertTranslatedContains(h, CompanionTexts.tr("trip.blocked", Component.literal("test")), "Vorbereitung", "preparation");
        h.succeed();
    }

    @GameTest(structure = ARENA)
    public void smarter_trip_does_not_deadlock_planned_tools_or_finished_resource_goals(GameTestHelper h) {
        var c = companion(h); c.giveToInventory(new ItemStack(Items.COOKED_BEEF, 8));
        var target = c.blockPosition().east(80);
        c.addTask(Task.travel(target)); c.addTask(Task.gather("holz", 6));
        c.addTask(Task.craft("wooden_pickaxe", 1)); c.addTask(Task.gather("stein", 8));
        h.assertTrue(CompanionTripPreparation.check(c, target).ready(), "Wood trip requires a pick planned for later");
        c.cancelOrders(); c.addTask(Task.travel(target));
        c.addTask(Task.craft("iron_pickaxe", 1)); c.addTask(Task.gather("diamant", 8));
        h.assertTrue(CompanionTripPreparation.check(c, target).ready(), "Future pick preparation was ignored");
        c.cancelOrders(); c.giveToInventory(new ItemStack(Items.DIAMOND, 8));
        c.addTask(Task.travel(target)); c.addTask(new Task(new TaskSpec.Gather("diamant", 8, 8)));
        h.assertTrue(CompanionTripPreparation.check(c, target).ready(), "Already acquired target requires further tool uses");
        h.succeed();
    }

    @GameTest(structure = ARENA)
    public void smarter_trip_resume_rechecks_without_waiting_for_the_periodic_timer(GameTestHelper h) {
        var c = companion(h); c.addTask(Task.travel(c.blockPosition().east(80)));
        var goal = new CompanionTaskGoal(c);
        tick(c, goal, 1); h.assertTrue(c.isPaused(), "Unprepared departure not paused");
        CompanionBrain.resumeReply(c);
        tick(c, goal, 1); h.assertTrue(c.isPaused(), "Resume bypassed unchanged missing supplies");
        c.giveToInventory(new ItemStack(Items.COOKED_BEEF, 8));
        CompanionBrain.resumeReply(c);
        tick(c, goal, 1); h.assertTrue(!c.isPaused() && c.hasTasks(), "Fixed preparation did not continue original trip");
        h.succeed();
    }

    @GameTest(structure = ARENA)
    public void smarter_build_natural_only_reports_protected_exceptions(GameTestHelper h) {
        var at = h.absolutePos(new BlockPos(26, 1, 24));
        h.setBlock(26, 1, 24, Blocks.OAK_PLANKS);
        var job = new CompanionBuilder.BuildJob("natural-only", List.of(new CompanionBuilder.Placement(at, Blocks.AIR.defaultBlockState())));
        job.terrainOnly = true;
        var result = CompanionBuilder.finishChecked(h.getLevel(), job);
        h.assertTrue(result.complete() && result.preserved() == 1 && result.mismatches() == 0,
                "Intentional protection treated as a failed terrain operation");
        h.assertTrue(h.getLevel().getBlockState(at).is(Blocks.OAK_PLANKS), "Verification destroyed construction");
        job.terrainOnly = false;
        result = CompanionBuilder.finishChecked(h.getLevel(), job);
        h.assertTrue(!result.complete() && result.preserved() == 0 && result.mismatches() == 1,
                "Ordinary build silently skipped a real mismatch");
        ServerTextGameTests.assertTranslatedContains(h, CompanionTexts.tr("build.verification.preserved", 1), "unverändert", "unchanged");
        h.succeed();
    }
}
