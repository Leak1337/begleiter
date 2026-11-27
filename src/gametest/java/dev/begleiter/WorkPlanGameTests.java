package dev.begleiter;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import dev.begleiter.CompanionEntity.Task;
import dev.begleiter.task.TaskSpec;
import net.fabricmc.fabric.api.gametest.v1.GameTest;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.util.ProblemReporter;
import net.minecraft.world.Container;
import net.minecraft.world.entity.EntityTypes;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.trading.ItemCost;
import net.minecraft.world.item.trading.MerchantOffer;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.CropBlock;
import net.minecraft.world.level.storage.TagValueInput;
import net.minecraft.world.level.storage.TagValueOutput;

public class WorkPlanGameTests {
    private static final String ARENA = "begleiter-gametest:request_isolation";
    private record Fixture(CompanionEntity mob, ServerPlayer owner) {}

    private static Fixture fixture(GameTestHelper h) {
        for (int x = 18; x <= 30; x++) for (int z = 18; z <= 30; z++) h.setBlock(x, 0, z, Blocks.STONE);
        var mob = h.spawn(CompanionMod.COMPANION, new BlockPos(24, 1, 24));
        mob.stay();
        mob.setNoAi(true);
        mob.setPersistenceRequired();
        mob.buildModeOverride = false;
        @SuppressWarnings("removal") var player = h.makeMockServerPlayerInLevel();
        mob.setOwner(player.getUUID());
        player.snapTo(mob.getX(), mob.getY(), mob.getZ(), 0, 0);
        return new Fixture(mob, player);
    }

    private static void command(Fixture f, String body) { CompanionBrain.onChat(f.owner(), "@ " + body); }

    private static BlockPos at(GameTestHelper h, int x, int y, int z) {
        return h.absolutePos(new BlockPos(x, y, z));
    }

    private static void zone(GameTestHelper h, Fixture f, CompanionZones.Kind kind, BlockPos a, BlockPos b) {
        f.mob().zones.put(CompanionZones.between(kind, CompanionMemory.dim(h.getLevel()), a, b));
    }

    private static Container barrel(GameTestHelper h, BlockPos relative) {
        h.setBlock(relative, Blocks.BARREL);
        return (Container) h.getLevel().getBlockEntity(h.absolutePos(relative));
    }

    private static void wheat(GameTestHelper h, int x, int z) {
        h.setBlock(x, 0, z, Blocks.FARMLAND);
        h.setBlock(x, 1, z, Blocks.WHEAT.defaultBlockState().setValue(CropBlock.AGE, 7));
    }

    private static void run(CompanionEntity mob, CompanionTaskGoal goal, int ticks) {
        for (int i = 0; i < ticks && mob.hasTasks(); i++) {
            mob.tickCount++;
            goal.tick();
        }
    }

    private static CompanionEntity restore(GameTestHelper h, CompanionEntity original) {
        var output = TagValueOutput.createWithContext(ProblemReporter.DISCARDING, h.getLevel().registryAccess());
        original.saveWithoutId(output);
        var restored = new CompanionEntity(CompanionMod.COMPANION, h.getLevel());
        restored.load(TagValueInput.create(ProblemReporter.DISCARDING, h.getLevel().registryAccess(), output.buildResult()));
        return restored;
    }

    private static CompoundTag row(CompanionEntity mob, CompanionWork.Kind kind) {
        var rows = mob.work.save(mob.level().getGameTime()).getListOrEmpty("Entries");
        for (var value : rows)
            if (value instanceof CompoundTag tag && kind.name().equals(tag.getStringOr("Kind", ""))) return tag;
        return new CompoundTag();
    }

    @GameTest(structure = ARENA)
    public void work_plan_prioritizes_player_orders_then_high_priority_and_revoked_scope(GameTestHelper h) {
        var f = fixture(h);
        var c = f.mob();
        var field = at(h, 25, 1, 24);
        wheat(h, 25, 24);
        zone(h, f, CompanionZones.Kind.FIELDS, field, field);
        c.setHome(at(h, 26, 1, 24));
        var storage = barrel(h, new BlockPos(26, 1, 24));
        c.giveToInventory(new ItemStack(Items.BREAD));
        command(f, "keep 1 bread in stock");
        var manual = Task.moveTo(c.blockPosition(), 4);
        c.addTask(manual);
        command(f, "work stock add");
        command(f, "Dienst Feld hinzufügen");
        command(f, "Dienst Feld Priorität 5");
        command(f, "work stock priority 1");
        c.work.tick(h.getLevel());
        h.assertTrue(c.currentTask() == manual && !c.work.active(), "Background work preempted the player's existing task");
        c.finishTask();
        c.work.tick(h.getLevel());
        h.assertTrue(c.work.is(CompanionWork.Kind.FARM) && c.currentTask().type() == CompanionEntity.TaskType.FARM,
                "Higher-priority eligible field job was not chosen");
        zone(h, f, CompanionZones.Kind.FIELDS, field.east(3), field.east(3));
        run(c, new CompanionTaskGoal(c), 10);
        h.assertTrue(CompanionJobs.isMatureCrop(h.getLevel().getBlockState(field)), "Revoked field plan changed the world");
        c.work.tick(h.getLevel());
        h.assertValueEqual(row(c, CompanionWork.Kind.FARM).getStringOr("State", ""), "AREA", "Revoked area wasn't reported");
        h.assertValueEqual(storage.countItem(Items.BREAD), 0, "Stock changed before getting its own turn");
        h.succeed();
    }

    @GameTest(structure = ARENA, maxTicks = 330)
    public void work_plan_harvests_replants_and_stocks_bread_across_reload(GameTestHelper h) {
        var f = fixture(h);
        var c = f.mob();
        wheat(h, 23, 24);
        wheat(h, 24, 23);
        wheat(h, 25, 24);
        wheat(h, 27, 24);
        zone(h, f, CompanionZones.Kind.FIELDS, at(h, 23, 1, 23), at(h, 25, 1, 24));
        c.setHome(at(h, 26, 1, 24));
        var storage = barrel(h, new BlockPos(26, 1, 24));
        h.setBlock(24, 1, 26, Blocks.CRAFTING_TABLE);
        c.giveToInventory(new ItemStack(Items.WHEAT_SEEDS, 5));
        command(f, "keep 1 bread in stock");
        command(f, "work farm add");
        command(f, "work farm limit 1");
        command(f, "work stock add");
        c.work.tick(h.getLevel());
        run(c, new CompanionTaskGoal(c), 10);
        h.assertValueEqual(c.getInventory().countItem(Items.WHEAT), 1, "No confirmed harvest before the save");
        var restored = restore(h, c);
        c.discard();
        h.assertTrue(restored.work.enabled() && !restored.hasTasks(), "Reload replayed a stale task chain");
        h.assertValueEqual(row(restored, CompanionWork.Kind.FARM).getIntOr("Actions", 0), 1, "Partial progress was not saved");
        var goal = new CompanionTaskGoal(restored);
        h.onEachTick(() -> {
            restored.work.tick(h.getLevel());
            run(restored, goal, 2);
        });
        h.succeedWhen(() -> {
            h.assertValueEqual(storage.countItem(Items.BREAD), 1, "Farm and stock haven't completed the bread chain (wheat "
                    + restored.getInventory().countItem(Items.WHEAT) + ", farm " + row(restored, CompanionWork.Kind.FARM)
                    + ", stock " + row(restored, CompanionWork.Kind.STOCK) + ")");
            for (BlockPos p : List.of(at(h, 23, 1, 24), at(h, 24, 1, 23), at(h, 25, 1, 24))) {
                var crop = h.getLevel().getBlockState(p);
                h.assertTrue(crop.is(Blocks.WHEAT) && !CompanionJobs.isMatureCrop(crop), "Crop was not replanted");
            }
            h.assertTrue(CompanionJobs.isMatureCrop(h.getLevel().getBlockState(at(h, 27, 1, 24))), "Outside crop was taken");
            h.assertValueEqual(row(restored, CompanionWork.Kind.STOCK).getIntOr("Completed", 0), 1, "Stock result wasn't measured");
        });
    }

    @GameTest(structure = ARENA, maxTicks = 240)
    public void work_plan_recovers_automatically_after_danger_and_preserves_configuration(GameTestHelper h) {
        var f = fixture(h);
        var c = f.mob();
        var field = at(h, 25, 1, 24);
        wheat(h, 25, 24);
        zone(h, f, CompanionZones.Kind.FIELDS, field, field);
        c.giveToInventory(new ItemStack(Items.WHEAT_SEEDS));
        command(f, "work farm add");
        c.work.tick(h.getLevel());
        h.assertTrue(c.work.is(CompanionWork.Kind.FARM), "Field service did not start");
        c.setHealth(5);
        run(c, new CompanionTaskGoal(c), 8);
        c.work.tick(h.getLevel());
        h.assertTrue(!c.hasTasks() && CompanionJobs.isMatureCrop(h.getLevel().getBlockState(field)), "Danger didn't halt field work");
        h.assertValueEqual(row(c, CompanionWork.Kind.FARM).getStringOr("State", ""), "DANGER", "Danger wasn't reported");
        c.setHealth(c.getMaxHealth());
        var goal = new CompanionTaskGoal(c);
        h.onEachTick(() -> {
            c.work.tick(h.getLevel());
            run(c, goal, 1);
        });
        h.succeedWhen(() -> {
            h.assertValueEqual(c.getInventory().countItem(Items.WHEAT), 1, "No automatic recovery after the retry delay");
            command(f, "Dienst Feld pausieren");
            var restored = restore(h, c);
            h.assertTrue(row(restored, CompanionWork.Kind.FARM).getBooleanOr("Paused", false), "Job pause was lost");
            restored.setOwner(UUID.randomUUID());
            h.assertTrue(!restored.work.enabled() && !restored.work.active(), "The previous owner authorized work for the new owner");
        });
    }

    @GameTest(structure = ARENA)
    public void work_plan_build_only_uses_approved_materials_and_preserves_its_budget(GameTestHelper h) {
        var f = fixture(h);
        var c = f.mob();
        var a = at(h, 23, 1, 24);
        var b = at(h, 24, 1, 23);
        zone(h, f, CompanionZones.Kind.BASE, a, b);
        c.setHome(at(h, 26, 1, 24));
        var approved = barrel(h, new BlockPos(26, 1, 24));
        var privateBox = barrel(h, new BlockPos(24, 1, 27));
        privateBox.setItem(0, new ItemStack(Items.BRICKS, 16));
        command(f, "work build add");
        command(f, "work build limit 1");
        c.build = new CompanionBuilder.BuildJob("test", List.of(
                new CompanionBuilder.Placement(a, Blocks.BRICKS.defaultBlockState()),
                new CompanionBuilder.Placement(b, Blocks.BRICKS.defaultBlockState())));
        c.queueBuild();
        h.assertTrue(!c.hasTasks(), "Build queued outside the configured scheduler");
        c.work.tick(h.getLevel());
        var goal = new CompanionTaskGoal(c);
        run(c, goal, 10);
        h.assertTrue(h.getLevel().getBlockState(a).isAir(), "Construction used an unauthorized chest");
        h.assertValueEqual(row(c, CompanionWork.Kind.BUILD).getStringOr("State", ""), "MATERIAL", "Missing material wasn't stored");
        var savedRetry = restore(h, c);
        h.assertTrue(row(savedRetry, CompanionWork.Kind.BUILD).getLongOr("Retry", 0) > 0, "Retry delay did not survive reload");
        approved.setItem(0, new ItemStack(Items.BRICKS, 2));
        command(f, "work build resume");
        c.work.tick(h.getLevel());
        run(c, goal, 12);
        c.work.tick(h.getLevel());
        h.assertTrue(h.getLevel().getBlockState(a).is(Blocks.BRICKS) && h.getLevel().getBlockState(b).isAir(),
                "Construction didn't honor its one-placement budget");
        h.assertValueEqual(approved.countItem(Items.BRICKS), 1, "The approved chest lost more than this turn needed");
        h.assertValueEqual(privateBox.countItem(Items.BRICKS), 16, "The private chest was touched");
        h.succeed();
    }

    @GameTest(structure = ARENA)
    public void work_plan_feeds_animal_pairs_and_trades_only_inside_approved_areas(GameTestHelper h) {
        var f = fixture(h);
        var c = f.mob();
        c.setHome(at(h, 26, 1, 24));
        var approved = barrel(h, new BlockPos(26, 1, 24));
        approved.setItem(0, new ItemStack(Items.WHEAT, 4));
        var cowA = h.spawn(EntityTypes.COW, new BlockPos(23, 1, 24));
        var cowB = h.spawn(EntityTypes.COW, new BlockPos(24, 1, 23));
        var outsideCow = h.spawn(EntityTypes.COW, new BlockPos(26, 1, 25));
        zone(h, f, CompanionZones.Kind.ANIMALS, at(h, 23, 1, 23), at(h, 24, 1, 24));
        command(f, "keep 2 wheat in stock");
        command(f, "work animals add");
        command(f, "work animals limit 2");
        command(f, "work animals budget 2 minecraft:wheat");
        c.work.tick(h.getLevel());
        var goal = new CompanionTaskGoal(c);
        run(c, goal, 12);
        c.work.tick(h.getLevel());
        h.assertTrue(cowA.isInLove() && cowB.isInLove() && !outsideCow.isInLove(), "The service fed the wrong animals");
        h.assertValueEqual(approved.countItem(Items.WHEAT), 2, "Animal service spent reserved wheat");
        h.assertValueEqual(row(c, CompanionWork.Kind.ANIMALS).getIntOr("Actions", 0), 2, "Feedings were not counted");

        var merchant = h.spawn(EntityTypes.VILLAGER, new BlockPos(24, 1, 25));
        var outsideMerchant = h.spawn(EntityTypes.VILLAGER, new BlockPos(25, 1, 24));
        merchant.setNoAi(true);
        outsideMerchant.setNoAi(true);
        var insideOffer = new MerchantOffer(new ItemCost(Items.WHEAT, 2), Optional.empty(), new ItemStack(Items.EMERALD), 12, 1, 0.05f);
        var outsideOffer = new MerchantOffer(new ItemCost(Items.WHEAT, 2), Optional.empty(), new ItemStack(Items.EMERALD), 12, 1, 0.05f);
        merchant.getOffers().clear();
        merchant.getOffers().add(insideOffer);
        outsideMerchant.getOffers().clear();
        outsideMerchant.getOffers().add(outsideOffer);
        zone(h, f, CompanionZones.Kind.BASE, at(h, 24, 1, 25), at(h, 24, 1, 25));
        c.giveToInventory(new ItemStack(Items.WHEAT, 6));
        command(f, "work trade add");
        command(f, "work trade limit 4");
        command(f, "work trade budget 2 minecraft:wheat");
        c.work.tick(h.getLevel());
        run(c, goal, 20);
        c.work.tick(h.getLevel());
        h.assertValueEqual(c.getInventory().countItem(Items.EMERALD), 1, "The trading service did not honor its limit");
        h.assertValueEqual(row(c, CompanionWork.Kind.TRADE).getStringOr("State", ""), "BUDGET", "Blocked purchase was not reported");
        h.assertValueEqual(outsideOffer.getUses(), 0, "The trading service used the outside villager");
        h.succeed();
    }

    @GameTest(structure = ARENA)
    public void work_plan_stock_budgets_cover_intermediates_fuel_and_both_inventories(GameTestHelper h) {
        var f = fixture(h);
        var c = f.mob();
        c.setHome(at(h, 26, 1, 24));
        var home = barrel(h, new BlockPos(26, 1, 24));
        h.setBlock(24, 1, 26, Blocks.FURNACE);
        home.setItem(0, new ItemStack(Items.OAK_PLANKS));
        home.setItem(1, new ItemStack(Items.COAL));
        c.giveToInventory(new ItemStack(Items.OAK_PLANKS));
        command(f, "keep 4 torches in stock");
        command(f, "work stock add");
        command(f, "work stock budget 0 minecraft:stick");
        c.work.tick(h.getLevel());
        h.assertValueEqual(home.countItem(Items.TORCH), 0, "The service consumed a prohibited crafted intermediate");
        h.assertValueEqual(home.countItem(Items.COAL), 1, "A failed recipe consumed coal");

        command(f, "work stock budget remove minecraft:stick");
        command(f, "work stock budget 1 minecraft:oak_planks");
        c.work.tick(h.getLevel());
        h.assertValueEqual(home.countItem(Items.OAK_PLANKS), 1, "The chest was charged separately from the carried budget");
        h.assertValueEqual(c.getInventory().countItem(Items.OAK_PLANKS), 1, "The shadow recipe changed the real inventory");

        command(f, "work stock budget 2 minecraft:oak_planks");
        c.work.tick(h.getLevel());
        h.assertValueEqual(home.countItem(Items.TORCH), 4, "The authorized recipe chain did not complete");
        home.setItem(3, new ItemStack(Items.COAL));
        home.setItem(4, new ItemStack(Items.SAND));
        command(f, "keep 1 glass in stock");
        command(f, "Dienst Vorrat Budget 0 minecraft:coal");
        c.work.tick(h.getLevel());
        h.assertValueEqual(home.countItem(Items.GLASS), 0, "Smelting bypassed its fuel budget");
        h.assertValueEqual(home.countItem(Items.SAND), 1, "Blocked smelting consumed sand");
        var restored = restore(h, c);
        var saved = row(restored, CompanionWork.Kind.STOCK);
        h.assertValueEqual(saved.getListOrEmpty("Budgets").size(), 2, "Budget settings did not survive reload");
        h.assertTrue(!saved.getListOrEmpty("History").isEmpty(), "Recent outcomes did not survive reload");
        h.succeed();
    }

    @GameTest(structure = ARENA)
    public void work_plan_seed_budget_prevents_harvest_that_cannot_be_replanted(GameTestHelper h) {
        var f = fixture(h);
        var c = f.mob();
        var field = at(h, 25, 1, 24);
        wheat(h, 25, 24);
        zone(h, f, CompanionZones.Kind.FIELDS, field, field);
        c.giveToInventory(new ItemStack(Items.WHEAT_SEEDS, 2));
        command(f, "work farm add");
        command(f, "work farm budget 0 minecraft:wheat_seeds");
        c.work.tick(h.getLevel());
        run(c, new CompanionTaskGoal(c), 8);
        h.assertTrue(CompanionJobs.isMatureCrop(h.getLevel().getBlockState(field)), "A prohibited seed left the field unplanted");
        h.assertValueEqual(row(c, CompanionWork.Kind.FARM).getStringOr("State", ""), "BUDGET", "Seed budget was not reported");
        command(f, "Dienst Feld Budget löschen minecraft:wheat_seeds");
        c.work.tick(h.getLevel());
        run(c, new CompanionTaskGoal(c), 8);
        h.assertTrue(h.getLevel().getBlockState(field).is(Blocks.WHEAT) && !CompanionJobs.isMatureCrop(h.getLevel().getBlockState(field)),
                "Removing the budget did not trigger harvest and replanting");
        h.succeed();
    }

    @GameTest(structure = ARENA, maxTicks = 240)
    public void work_plan_build_reports_forecast_and_reacts_to_new_approved_stock(GameTestHelper h) {
        var f = fixture(h);
        var c = f.mob();
        var spot = at(h, 23, 1, 24);
        zone(h, f, CompanionZones.Kind.BASE, spot, spot);
        c.setHome(at(h, 26, 1, 24));
        var approved = barrel(h, new BlockPos(26, 1, 24));
        h.setBlock(23, 1, 24, Blocks.DIRT);
        command(f, "work build add");
        command(f, "work build budget 0 minecraft:bricks");
        c.build = new CompanionBuilder.BuildJob("test", List.of(new CompanionBuilder.Placement(spot, Blocks.BRICKS.defaultBlockState())));
        c.queueBuild();
        c.work.tick(h.getLevel());
        var goal = new CompanionTaskGoal(c);
        run(c, goal, 8);
        h.assertTrue(h.getLevel().getBlockState(spot).is(Blocks.DIRT), "Construction removed an obstacle before checking its budget");
        h.assertValueEqual(row(c, CompanionWork.Kind.BUILD).getStringOr("State", ""), "BUDGET", "Construction budget wasn't reported");
        h.setBlock(23, 1, 24, Blocks.AIR);
        command(f, "work build budget 1 minecraft:bricks");
        c.work.tick(h.getLevel());
        run(c, goal, 8);
        h.assertValueEqual(row(c, CompanionWork.Kind.BUILD).getIntOr("Needed", 0), 1, "The exact shortage was not captured");
        var report = c.work.interpret(f.owner(), "Dienst Bau warum");
        h.assertTrue(report.getSiblings().stream().anyMatch(part ->
                part.getContents() instanceof net.minecraft.network.chat.contents.TranslatableContents translation
                        && translation.getKey().equals("server.begleiter.work.forecast.item")), "The build forecast did not list materials");
        approved.setItem(0, new ItemStack(Items.BRICKS));
        h.onEachTick(() -> {
            c.work.tick(h.getLevel());
            run(c, goal, 2);
        });
        h.succeedWhen(() -> {
            h.assertTrue(h.getLevel().getBlockState(spot).is(Blocks.BRICKS), "Newly delivered material did not wake the build");
            h.assertValueEqual(approved.countItem(Items.BRICKS), 0, "The build did not use the approved input");
        });
    }

    @GameTest(structure = ARENA)
    public void work_plan_exploration_retains_recent_visits_and_misses_after_reload(GameTestHelper h) {
        var f = fixture(h);
        var c = f.mob();
        zone(h, f, CompanionZones.Kind.ROAM, at(h, 20, 1, 20), at(h, 29, 1, 29));
        command(f, "work explore add");
        c.work.tick(h.getLevel());
        h.assertTrue(c.currentTask() != null, "No exploration point selected");
        BlockPos failure = c.currentTask().pos();
        c.work.taskFailed();
        command(f, "work explore resume");
        c.work.tick(h.getLevel());
        h.assertTrue(c.currentTask() != null && !c.currentTask().pos().equals(failure), "The failed point was immediately retried");
        BlockPos visited = c.currentTask().pos();
        c.work.reachedExploration(visited);
        c.work.tick(h.getLevel());
        var restored = restore(h, c);
        restored.work.interpret(f.owner(), "work explore resume");
        restored.work.tick(h.getLevel());
        h.assertTrue(restored.work.is(CompanionWork.Kind.EXPLORE), "Reloaded exploration did not start");
        h.assertTrue(Double.isInfinite(restored.work.explorationCost(h.getLevel(), failure)), "The failed point was forgotten on reload");
        h.assertTrue(restored.work.explorationCost(h.getLevel(), visited) > 0, "A recent visit was not penalized");
        h.succeed();
    }

    @GameTest(structure = ARENA)
    public void work_plan_storage_and_exploration_obey_scopes_and_manual_pause(GameTestHelper h) {
        var f = fixture(h);
        var c = f.mob();
        barrel(h, new BlockPos(26, 1, 24)).setItem(0, new ItemStack(Items.COBBLESTONE, 2));
        barrel(h, new BlockPos(24, 1, 27)).setItem(0, new ItemStack(Items.DIAMOND, 9));
        var allowed = at(h, 26, 1, 24);
        var outside = at(h, 24, 1, 27);
        zone(h, f, CompanionZones.Kind.STORAGE, allowed, allowed);
        zone(h, f, CompanionZones.Kind.ROAM, at(h, 22, 1, 22), at(h, 27, 1, 27));
        command(f, "work storage add");
        command(f, "work explore add");
        command(f, "work storage priority 5");
        c.work.tick(h.getLevel());
        h.assertTrue(c.work.is(CompanionWork.Kind.STORAGE), "Storage service wasn't selected");
        h.assertTrue(c.workStorage(h.getLevel(), allowed) != null && c.workStorage(h.getLevel(), outside) == null,
                "The storage service leaked access outside the approved area");
        command(f, "work off");
        h.assertTrue(!c.work.enabled() && !c.hasTasks(), "The owner's pause didn't release active background work");
        command(f, "work storage pause");
        command(f, "work on");
        c.work.tick(h.getLevel());
        h.assertTrue(c.work.is(CompanionWork.Kind.EXPLORE) && c.currentTask().spec() instanceof TaskSpec.MoveTo move
                && c.zones.get(CompanionZones.Kind.ROAM, h.getLevel()).contains(move.pos()), "Exploration picked a point outside its zone");
        h.assertTrue(!c.work.permitsChange(h.getLevel(), allowed), "Exploration was allowed to break or place blocks");
        c.setHome(allowed);
        c.giveToInventory(new ItemStack(Items.BREAD));
        command(f, "bring 1 bread into storage");
        h.assertTrue(!c.work.active() && c.work.enabled() && c.currentTask().spec() instanceof TaskSpec.Request,
                "Incoming player order did not take precedence");
        h.succeed();
    }
}
