package dev.begleiter;

import net.fabricmc.fabric.api.gametest.v1.GameTest;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.core.component.DataComponents;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.util.ProblemReporter;
import net.minecraft.world.entity.EntitySpawnReason;
import net.minecraft.world.level.storage.TagValueInput;
import net.minecraft.world.level.storage.TagValueOutput;
import dev.begleiter.CompanionEntity.Task;
import dev.begleiter.CompanionEntity.TaskType;
import dev.begleiter.CompanionNeeds.Need;
import dev.begleiter.CompanionPlanFeedback.Status;

/**
 * Eigeninitiative: Essen für den Besitzer, Vorratsvorschläge aus Gewohnheiten, Graben in neues Gebiet, Speichern der
 * Zähler, Feldbau, Ausleuchten, Nachtarbeit, Rückgabe nach dem Tod des Besitzers und Hilferufe.
 */
public class InitiativeGameTests {
    private static CompanionEntity companion(GameTestHelper h) {
        for (int x = 0; x < 8; x++) for (int z = 0; z < 8; z++) h.setBlock(x, 0, z, Blocks.STONE);
        var c = h.spawn(CompanionMod.COMPANION, new BlockPos(2, 1, 2));
        c.setNoAi(true);
        c.stay();
        c.getInventory().clearContent();
        return c;
    }

    private static ServerPlayer owner(GameTestHelper h, CompanionEntity c) {
        ServerPlayer p = h.makeMockServerPlayerInLevel();
        p.setGameMode(GameType.SURVIVAL);
        c.setOwner(p.getUUID());
        BlockPos at = h.absolutePos(new BlockPos(4, 1, 2));
        p.snapTo(at.getX() + .5, at.getY(), at.getZ() + .5, 0, 0);
        p.getInventory().clearContent();
        return p;
    }

    @GameTest
    public void hungryOwnerGetsFoodButCompanionKeepsSome(GameTestHelper h) {
        var c = companion(h);
        var p = owner(h, c);
        c.getInventory().setItem(0, new ItemStack(Items.COOKED_BEEF, 8));
        c.getInventory().setItem(1, new ItemStack(Items.ROTTEN_FLESH, 16));
        p.getFoodData().setFoodLevel(4);
        c.initiative.ownerCare.tick(h.getLevel());
        h.assertValueEqual(CompanionCraft.count(c, "cooked_beef"), 4, "cooked beef left with the companion");
        h.assertValueEqual(CompanionCraft.count(c, "rotten_flesh"), 16, "rotten flesh is never handed out");
        var dropped = h.getLevel().getEntitiesOfClass(ItemEntity.class, p.getBoundingBox().inflate(3),
                ie -> ie.getItem().is(Items.COOKED_BEEF));
        h.assertTrue(dropped.stream().mapToInt(ie -> ie.getItem().getCount()).sum() == 4, "four cooked beef dropped for the owner");
        // Gleich danach nicht noch einmal (Abklingzeit), und ein satter Besitzer bekommt nichts
        c.initiative.ownerCare.tick(h.getLevel());
        h.assertValueEqual(CompanionCraft.count(c, "cooked_beef"), 4, "food handed out twice within the cooldown");
        h.succeed();
    }

    @GameTest
    public void ownerWithOwnFoodGetsNothing(GameTestHelper h) {
        var c = companion(h);
        var p = owner(h, c);
        c.getInventory().setItem(0, new ItemStack(Items.BREAD, 8));
        p.getInventory().setItem(0, new ItemStack(Items.APPLE, 2));
        p.getFoodData().setFoodLevel(4);
        c.initiative.ownerCare.tick(h.getLevel());
        h.assertValueEqual(CompanionCraft.count(c, "bread"), 8, "bread given although the owner carries food");
        h.succeed();
    }

    @GameTest
    public void thirdRequestSuggestsStockRuleAndYesCreatesIt(GameTestHelper h) {
        var c = companion(h);
        var p = owner(h, c);
        c.setHome(h.absolutePos(new BlockPos(5, 1, 5)));
        var habits = c.initiative.habits;
        h.assertTrue(habits.note(c, p, Items.TORCH, 10) == null, "suggested after one request");
        h.assertTrue(habits.note(c, p, Items.TORCH, 6) == null, "suggested after two requests");
        h.assertTrue(habits.note(c, p, Items.TORCH, 8) != null, "no suggestion after the third request");
        h.assertTrue(habits.answer(c, p, "something else") == null, "unrelated text taken as an answer");
        h.assertTrue(habits.answer(c, p, "Ja!") != null, "yes was not accepted");
        var torch = BuiltInRegistries.ITEM.getKey(Items.TORCH);
        var rule = c.stockRules.stream().filter(r -> r.item().equals(torch)).findFirst().orElse(null);
        h.assertTrue(rule != null && rule.target() == 24, "stock rule for 24 torches missing: " + rule);
        for (int i = 0; i < 3; i++) h.assertTrue(habits.note(c, p, Items.TORCH, 8) == null, "suggested again despite a rule");
        h.assertTrue(!habits.hasSuggestion(), "suggestion left open");
        h.succeed();
    }

    @GameTest
    public void declinedSuggestionIsNotRepeatedAndToolsAreIgnored(GameTestHelper h) {
        var c = companion(h);
        var p = owner(h, c);
        c.setHome(h.absolutePos(new BlockPos(5, 1, 5)));
        var habits = c.initiative.habits;
        habits.note(c, p, Items.BREAD, 4);
        habits.note(c, p, Items.BREAD, 4);
        h.assertTrue(habits.note(c, p, Items.BREAD, 4) != null, "no suggestion for bread");
        h.assertTrue(habits.answer(c, p, "no") != null, "no was not accepted");
        for (int i = 0; i < 4; i++) h.assertTrue(habits.note(c, p, Items.BREAD, 4) == null, "declined item suggested again");
        for (int i = 0; i < 4; i++) h.assertTrue(habits.note(c, p, Items.IRON_PICKAXE, 1) == null, "tool suggested as stock item");
        h.assertTrue(c.stockRules.isEmpty(), "rule created without consent");
        h.assertValueEqual(CompanionInitiative.Habits.suggestedAmount(40, 64), 64, "amount capped at a stack");
        h.assertValueEqual(CompanionInitiative.Habits.suggestedAmount(1, 16), 8, "small orders round up to 8");
        h.succeed();
    }

    @GameTest
    public void miningTurnsAwayFromSearchedCells(GameTestHelper h) {
        var c = companion(h);
        var cells = c.initiative.mining;
        BlockPos start = h.absolutePos(new BlockPos(2, 1, 2));
        h.assertTrue(cells.direction(c, h.getLevel(), Direction.NORTH) == Direction.NORTH, "turned away from fresh ground");
        for (int k = 1; k <= 3; k++) {
            BlockPos at = start.north(16 * k);
            c.snapTo(at.getX() + .5, at.getY(), at.getZ() + .5, 0, 0);
            cells.direction(c, h.getLevel(), Direction.NORTH);
        }
        c.snapTo(start.getX() + .5, start.getY(), start.getZ() + .5, 0, 0);
        Direction next = cells.direction(c, h.getLevel(), Direction.NORTH);
        h.assertTrue(next == Direction.EAST || next == Direction.WEST, "kept digging into searched cells: " + next);
        h.succeed();
    }
    // ------------------------------------------------------------------ Speichern (InitiativeV1)

    private static CompoundTag save(GameTestHelper h, CompanionEntity c) {
        TagValueOutput out = TagValueOutput.createWithContext(ProblemReporter.DISCARDING, h.getLevel().registryAccess());
        c.saveWithoutId(out);
        return out.buildResult();
    }

    private static CompanionEntity restored(GameTestHelper h, CompoundTag data) {
        CompanionEntity c = CompanionMod.COMPANION.create(h.getLevel(), EntitySpawnReason.LOAD);
        h.assertTrue(c != null, "companion could not be created");
        c.load(TagValueInput.create(ProblemReporter.DISCARDING, h.getLevel().registryAccess(), data));
        return c;
    }

    @GameTest
    public void habitsAndSearchedCellsSurviveReload(GameTestHelper h) {
        var c = companion(h);
        var p = owner(h, c);
        c.setHome(h.absolutePos(new BlockPos(5, 1, 5)));
        var habits = c.initiative.habits;
        habits.note(c, p, Items.TORCH, 10);
        habits.note(c, p, Items.TORCH, 6);
        for (int i = 0; i < 3; i++) habits.note(c, p, Items.BREAD, 4);
        h.assertTrue(habits.answer(c, p, "nein") != null, "no was not accepted");
        BlockPos start = h.absolutePos(new BlockPos(2, 1, 2));
        for (int k = 0; k < 3; k++) {
            BlockPos at = start.north(16 * k);
            c.snapTo(at.getX() + .5, at.getY(), at.getZ() + .5, 0, 0);
            c.initiative.mining.direction(c, h.getLevel(), Direction.NORTH);
        }
        int cells = c.initiative.mining.visited();
        CompoundTag data = save(h, c);
        h.assertTrue(data.contains("InitiativeV1"), "initiative data not saved");

        var back = restored(h, data);
        h.assertValueEqual(back.initiative.habits.asked(Items.TORCH), 2, "torch requests after reload");
        h.assertTrue(back.initiative.habits.declined(Items.BREAD), "declined suggestion forgotten");
        h.assertValueEqual(back.initiative.mining.visited(), cells, "searched cells after reload");
        back.setHome(h.absolutePos(new BlockPos(5, 1, 5)));
        h.assertTrue(back.initiative.habits.note(back, p, Items.TORCH, 8) != null, "third request after reload did not suggest");
        for (int i = 0; i < 3; i++)
            h.assertTrue(back.initiative.habits.note(back, p, Items.BREAD, 4) == null, "declined item suggested after reload");

        // Ältere Welt ohne den Teil: lädt normal, Zähler leer
        data.remove("InitiativeV1");
        var legacy = restored(h, data);
        h.assertValueEqual(legacy.initiative.habits.asked(Items.TORCH), 0, "legacy world invented requests");
        h.assertValueEqual(legacy.initiative.mining.visited(), 0, "legacy world invented cells");
        // Unbekanntes Item aus einem entfernten Mod wird beim Laden verworfen
        CompoundTag foreign = save(h, c);
        foreign.getCompoundOrEmpty("InitiativeV1").getListOrEmpty("Asks").getCompoundOrEmpty(0).putString("Item", "gone_mod:widget");
        h.assertTrue(restored(h, foreign).initiative.habits.declined(Items.BREAD), "one unknown item discarded the rest");
        h.succeed();
    }

    // ------------------------------------------------------------------ Feldbau und Ausleuchten

    private static final String ARENA = "begleiter-gametest:request_isolation";

    /** 48×48-Fläche aus Gras, Haus-Ursprung in der Mitte (nur gemerkt, nicht gebaut). */
    private static CompanionEntity baseArena(GameTestHelper h) {
        for (int x = 0; x < 48; x++) for (int z = 0; z < 48; z++) h.setBlock(x, 0, z, Blocks.GRASS_BLOCK);
        var c = h.spawn(CompanionMod.COMPANION, new BlockPos(22, 1, 22));
        c.setNoAi(true);
        c.stay();
        c.getInventory().clearContent();
        CompoundTag mem = new CompoundTag();
        mem.putLong("HouseOrigin", h.absolutePos(new BlockPos(20, 0, 20)).asLong());
        mem.putBoolean("HouseBuilt", true);
        c.loadMemory(mem);
        return c;
    }

    @GameTest(structure = ARENA, skyAccess = true)
    public void fieldIsPlannedOnlyWithIronAndWithoutFarmland(GameTestHelper h) {
        var c = baseArena(h);
        var base = c.initiative.base;
        h.assertTrue(!base.wantsField(h.getLevel()), "field wanted without a stone pickaxe");
        c.giveToInventory(new ItemStack(Items.IRON_PICKAXE));
        h.assertTrue(base.wantsField(h.getLevel()), "no field wanted with iron and no farmland");
        BlockPos site = CompanionInitiative.BaseCare.fieldSiteAt(h.getLevel(), h.absolutePos(new BlockPos(2, 0, 2)).getX(),
                h.absolutePos(new BlockPos(2, 0, 2)).getZ(), 9);
        h.assertTrue(site != null && site.getY() == h.absolutePos(new BlockPos(0, 1, 0)).getY(), "flat grass rejected: " + site);
        h.setBlock(5, 1, 5, Blocks.STONE);
        h.setBlock(5, 2, 5, Blocks.STONE);
        h.assertTrue(CompanionInitiative.BaseCare.fieldSiteAt(h.getLevel(), h.absolutePos(new BlockPos(2, 0, 2)).getX(),
                h.absolutePos(new BlockPos(2, 0, 2)).getZ(), 9) == null, "uneven site accepted");
        h.assertTrue(base.startField(h.getLevel()), "field not queued");
        h.assertTrue(c.build != null, "no build job for the field");
        h.assertTrue(!base.wantsField(h.getLevel()), "second field while the first is being built");
        // Vorhandenes Feld (12 Ackerboden): kein weiteres
        c.build = null;
        for (int i = 0; i < 12; i++) h.setBlock(30 + i % 6, 0, 30 + i / 6, Blocks.FARMLAND);
        h.assertTrue(base.hasField(h.getLevel(), true), "12 farmland not counted as a field");
        h.succeed();
    }

    @GameTest(structure = ARENA, skyAccess = true)
    public void darkSpotsAroundTheHouseGetTorchesOnce(GameTestHelper h) {
        var c = baseArena(h);
        var base = c.initiative.base;
        var spots = base.darkSpots(h.getLevel(), true);
        h.assertTrue(!spots.isEmpty() && spots.size() <= CompanionInitiative.BaseCare.MAX_TORCHES, "dark spots: " + spots.size());
        for (BlockPos a : spots) {
            h.assertTrue(h.getLevel().getBlockState(a.below()).is(Blocks.GRASS_BLOCK), "torch spot not on the ground: " + a);
            for (BlockPos b : spots) h.assertTrue(a == b || a.distSqr(b) >= 36, "torch spots too close: " + a + " " + b);
        }
        c.giveToInventory(new ItemStack(Items.TORCH, 3));
        h.assertTrue(base.startLighting(h.getLevel()), "no torches planned");
        long placeTasks = c.peekTasks(20).stream().filter(t -> t.type() == TaskType.PLACE && t.block() == Blocks.TORCH).count();
        h.assertValueEqual((int) placeTasks, 3, "one place task per torch carried");
        var tried = c.peekTasks(20).stream().map(Task::pos).toList();
        for (BlockPos p : base.darkSpots(h.getLevel(), true))
            h.assertTrue(!tried.contains(p), "spot planned again right away: " + p);
        h.succeed();
    }

    // ------------------------------------------------------------------ Nachtarbeit

    @GameTest
    public void nightChoresRunOncePerNight(GameTestHelper h) {
        var c = companion(h);
        CompoundTag mem = new CompoundTag();
        mem.putLong("HouseOrigin", h.absolutePos(BlockPos.ZERO).asLong());
        mem.putBoolean("HouseBuilt", true);
        c.loadMemory(mem);
        h.assertTrue(c.isInsideHouse(), "companion not inside the remembered house");
        c.giveToInventory(new ItemStack(Items.RAW_IRON, 3));
        c.giveToInventory(new ItemStack(Items.FURNACE));
        c.giveToInventory(new ItemStack(Items.COAL, 6));
        c.giveToInventory(new ItemStack(Items.STICK, 4));
        h.assertTrue(c.initiative.nightChore(h.getLevel()), "no night chore");
        h.assertTrue(c.peekTasks(5).stream().anyMatch(t -> t.type() == TaskType.SMELT), "smelting not queued: " + c.peekTasks(5));
        while (c.hasTasks()) c.finishTask();
        h.assertTrue(c.initiative.nightChore(h.getLevel()), "torches not made at night");
        h.assertTrue(CompanionCraft.count(c, "torch") >= 4, "no torches crafted");
        h.assertTrue(!c.initiative.nightChore(h.getLevel()), "chore without storage in the house");
        h.assertTrue(!c.initiative.nightChore(h.getLevel()) && !c.hasTasks(), "a chore repeated in the same night");
        c.tickCount += 6001; // nächste Nacht
        h.assertTrue(c.initiative.nightChore(h.getLevel()), "chores not reset for the next night");
        h.assertTrue(c.peekTasks(5).stream().anyMatch(t -> t.type() == TaskType.SMELT), "smelting not queued next night");
        // Draußen: keine Hausarbeit
        BlockPos out = h.absolutePos(new BlockPos(6, 1, 6));
        c.snapTo(out.getX() + .5, out.getY(), out.getZ() + .5, 0, 0);
        c.tickCount += 6001;
        h.assertTrue(!c.initiative.nightChore(h.getLevel()), "house chores outside the house");
        h.succeed();
    }

    // ------------------------------------------------------------------ Rückgabe nach dem Tod des Besitzers

    @GameTest
    public void ownerItemsAreReturnedAfterDeath(GameTestHelper h) {
        var c = companion(h);
        var p = owner(h, c);
        c.giveToInventory(new ItemStack(Items.COBBLESTONE, 10)); // eigene Steine behält er
        ItemStack sword = new ItemStack(Items.DIAMOND_SWORD);
        sword.set(DataComponents.CUSTOM_NAME, Component.literal("Kays Schwert"));
        p.setHealth(0f);
        c.initiative.ownerCare.tick(h.getLevel());
        var at = p.position();
        var swordDrop = new ItemEntity(h.getLevel(), at.x, at.y, at.z, sword.copy());
        var stoneDrop = new ItemEntity(h.getLevel(), at.x, at.y, at.z, new ItemStack(Items.COBBLESTONE, 5));
        h.getLevel().addFreshEntity(swordDrop);
        h.getLevel().addFreshEntity(stoneDrop);
        p.setHealth(20f);
        c.initiative.ownerCare.tick(h.getLevel()); // merkt sich die Items und läuft hin
        h.assertTrue(c.peekTasks(3).stream().anyMatch(t -> t.type() == TaskType.MOVE_TO), "did not walk to the death spot");
        for (ItemEntity ie : new ItemEntity[]{swordDrop, stoneDrop}) { // Aufheben nachstellen (ohne KI)
            c.giveToInventory(ie.getItem().copy());
            ie.discard();
        }
        c.initiative.ownerCare.tick(h.getLevel());
        h.assertValueEqual(CompanionCraft.count(c, "cobblestone"), 10, "own cobblestone given away or picked up stone kept");
        h.assertTrue(c.findItem(Items.DIAMOND_SWORD) < 0, "owner's sword kept");
        var dropped = h.getLevel().getEntitiesOfClass(ItemEntity.class, p.getBoundingBox().inflate(4), ItemEntity::isAlive);
        h.assertTrue(dropped.stream().anyMatch(ie -> ItemStack.isSameItemSameComponents(ie.getItem(), sword)), "named sword not returned");
        h.assertValueEqual(dropped.stream().filter(ie -> ie.getItem().is(Items.COBBLESTONE)).mapToInt(ie -> ie.getItem().getCount()).sum(),
                5, "returned cobblestone");
        h.succeed();
    }

    // ------------------------------------------------------------------ Hilferufe

    private static void failSupplyPlan(CompanionEntity c) {
        c.goal = Need.SUPPLY;
        c.activeSupplyResource = "kohle";
        c.activeSupplyApproach = "mining";
        c.addTask(Task.gather("kohle", 5));
        c.planFeedback.begin(c, Need.SUPPLY, null);
        while (c.hasTasks()) c.finishTask();
        c.planFeedback.completeIfIdle(c);
    }

    @GameTest
    public void threeFailuresInARowAskForHelpOnce(GameTestHelper h) {
        var c = companion(h);
        owner(h, c);
        for (int i = 1; i <= 3; i++) {
            failSupplyPlan(c);
            h.assertTrue(c.planFeedback.last() != null && c.planFeedback.last().status() == Status.FAILURE, "plan " + i + " did not fail");
            c.initiative.reports(h.getLevel());
            h.assertValueEqual(c.initiative.helpRequests(), i == 3 ? 1 : 0, "help requests after failure " + i);
        }
        // Erneute Fehlschläge innerhalb von 10 Minuten: kein zweiter Hilferuf
        for (int i = 0; i < 3; i++) {
            failSupplyPlan(c);
            c.initiative.reports(h.getLevel());
        }
        h.assertValueEqual(c.initiative.helpRequests(), 1, "help requested again within the cooldown");
        h.succeed();
    }
}
