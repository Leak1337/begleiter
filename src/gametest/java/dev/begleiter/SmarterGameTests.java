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
    public void smarter_trip_checks_food_tools_space_without_loading_destination(GameTestHelper h) {
        var c = companion(h); c.giveToInventory(new ItemStack(Items.COOKED_BEEF, 8));
        c.giveToInventory(new ItemStack(Items.IRON_PICKAXE)); c.addTask(Task.gather("diamant", 10));
        var target = new BlockPos(24_000_000, 100, -24_000_000);
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
    public void smarter_trip_does_not_eat_reserved_delivery_and_retains_paused_order(GameTestHelper h) {
        var c = companion(h); c.hunger = 8; c.giveToInventory(new ItemStack(Items.COOKED_BEEF, 8));
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
        var c = companion(h); c.hunger = 8; c.giveToInventory(new ItemStack(Items.COOKED_BEEF, 8));
        var target = c.blockPosition().east(80); c.addTask(Task.travel(target));
        tick(c, new CompanionTaskGoal(c), 1);
        h.assertTrue(!c.isPaused() && c.hunger >= 12 && c.foodCount() == 7, "Did not eat available food before departure");
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
