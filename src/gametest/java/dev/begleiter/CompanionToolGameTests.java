package dev.begleiter;

import dev.begleiter.config.BegleiterConfig;
import dev.begleiter.task.TaskSpec;
import net.fabricmc.fabric.api.gametest.v1.GameTest;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.util.ProblemReporter;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.entity.AbstractFurnaceBlockEntity;
import net.minecraft.world.level.block.entity.FurnaceBlockEntity;
import net.minecraft.world.level.storage.TagValueInput;
import net.minecraft.world.level.storage.TagValueOutput;

public class CompanionToolGameTests {
    private static final String ARENA = "begleiter-gametest:request_isolation";
    private static final BlockPos START = new BlockPos(24, 1, 24);

    @SuppressWarnings("removal")
    private static CompanionEntity companion(GameTestHelper h) {
        for (int x = 19; x <= 29; x++) for (int z = 19; z <= 29; z++) h.setBlock(x, 0, z, Blocks.STONE);
        var mob = h.spawn(CompanionMod.COMPANION, START);
        mob.setNoAi(true);
        mob.setPersistenceRequired();
        mob.stay();
        var owner = h.makeMockServerPlayerInLevel();
        mob.setOwner(owner.getUUID());
        owner.snapTo(mob.getX() - 2, mob.getY(), mob.getZ(), 0, 0);
        return mob;
    }

    @GameTest(structure = ARENA)
    public void companionToolsWhistleRespectsOwnerAndCooldown(GameTestHelper h) {
        var mob = companion(h);
        ServerPlayer owner = mob.getOwnerAnywhere();
        var other = h.spawn(CompanionMod.COMPANION, START.west(2));
        other.stay();
        var whistle = CompanionMod.COMPANION_WHISTLE;
        owner.setItemInHand(InteractionHand.MAIN_HAND, new ItemStack(whistle));
        double distance = owner.distanceToSqr(mob);
        var remote = h.absolutePos(START.east(100));
        h.getLevel().getChunk(remote.getX() >> 4, remote.getZ() >> 4);
        mob.snapTo(remote.getX() + .5, remote.getY(), remote.getZ() + .5, 0, 0);
        whistle.use(h.getLevel(), owner, InteractionHand.MAIN_HAND);
        h.assertTrue(!mob.isStaying() && owner.distanceToSqr(mob) <= distance, "Whistle didn't recall the owned companion");
        h.assertTrue(other.isStaying(), "Whistle changed a companion belonging to someone else");
        owner.setShiftKeyDown(true);
        whistle.use(h.getLevel(), owner, InteractionHand.MAIN_HAND);
        h.assertTrue(!mob.isStaying(), "Cooldown allowed an immediate second use");
        for (int i = 0; i < 40; i++) owner.getCooldowns().tick();
        whistle.use(h.getLevel(), owner, InteractionHand.MAIN_HAND);
        h.assertTrue(mob.isStaying(), "Sneaking didn't switch the companion to waiting");
        h.succeed();
    }

    @GameTest(structure = ARENA)
    public void companionToolsWaypointMapUsesServerPointsAndRejectsStaleEntries(GameTestHelper h) {
        var mob = companion(h);
        var owner = mob.getOwnerAnywhere();
        var target = mob.blockPosition().east(40);
        for (int i = 0; i < 70; i++) mob.memory.setWaypoint("Point " + i, h.getLevel(), target.east(i));
        var open = CompanionWaypointMap.prepare(owner, mob);
        h.assertTrue(open != null && open.points().size() == CompanionWaypointMap.LIMIT, "Map ignored its point limit");
        h.assertTrue(!CompanionWaypointMap.select(owner, new CompanionWaypointMap.Select(java.util.UUID.randomUUID(), 0)),
                "Unrelated session was accepted");
        mob.memory.setWaypoint("Point 0", h.getLevel(), target.east(8));
        h.assertTrue(!CompanionWaypointMap.select(owner, new CompanionWaypointMap.Select(open.token(), 0)),
                "Modified waypoint was accepted from a stale map");
        mob.memory.setWaypoint("Point 0", h.getLevel(), target);
        var fresh = CompanionWaypointMap.prepare(owner, mob);
        h.assertTrue(CompanionWaypointMap.select(owner, new CompanionWaypointMap.Select(fresh.token(), 0)),
                "Current waypoint was rejected");
        h.assertTrue(mob.currentTask().spec() instanceof TaskSpec.Travel travel && target.equals(travel.pos()),
                "Travel did not use the server's stored waypoint");
        h.succeed();
    }

    @GameTest(structure = ARENA)
    public void companionToolsRealFurnaceUsesVanillaOutputAndResumesAfterReload(GameTestHelper h) {
        var before = BegleiterConfig.get();
        var config = new BegleiterConfig();
        config.realFurnaces = true;
        BegleiterConfig.set(config);
        try {
            var mob = companion(h);
            var pos = START.east();
            h.setBlock(pos, Blocks.FURNACE);
            var world = h.getLevel();
            var at = h.absolutePos(pos);
            var furnace = (FurnaceBlockEntity) world.getBlockEntity(at);
            mob.giveToInventory(new ItemStack(Items.RAW_IRON, 8));
            mob.giveToInventory(new ItemStack(Items.COAL, 2));
            mob.addTask(CompanionEntity.Task.smelt("iron_ingot", 8));
            var goal = new CompanionTaskGoal(mob);
            goal.tick();
            h.assertTrue(furnace.getItem(0).is(Items.RAW_IRON) && furnace.getItem(1).is(Items.COAL),
                    "Input and fuel did not enter the vanilla slots");
            h.assertValueEqual(CompanionCraft.count(mob, "raw_iron"), 7, "Input wasn't removed exactly once");
            var output = TagValueOutput.createWithContext(ProblemReporter.DISCARDING, world.registryAccess());
            mob.saveWithoutId(output);
            var loaded = new CompanionEntity(CompanionMod.COMPANION, world);
            loaded.load(TagValueInput.create(ProblemReporter.DISCARDING, world.registryAccess(), output.buildResult()));
            goal = new CompanionTaskGoal(loaded);
            goal.tick();
            h.assertValueEqual(CompanionCraft.count(loaded, "raw_iron"), 7, "Reload fed the same recipe twice");
            for (int made = 0; made < 8; made++) {
                for (int ticks = 0; furnace.getItem(2).isEmpty() && ticks < 202; ticks++) {
                    AbstractFurnaceBlockEntity.serverTick(world, at, world.getBlockState(at), furnace);
                    if (furnace.getItem(2).isEmpty()) goal.tick();
                }
                h.assertTrue(furnace.getItem(2).is(Items.IRON_INGOT), "Vanilla furnace didn't produce iron");
                h.assertValueEqual(CompanionCraft.count(loaded, "iron_ingot"), made, "Output bypassed the actual furnace");
                goal.tick();
                goal.tick();
            }
            h.assertValueEqual(CompanionCraft.count(loaded, "iron_ingot"), 8, "Output wasn't collected exactly once");
            h.assertValueEqual(CompanionCraft.count(loaded, "raw_iron"), 0, "Raw iron was duplicated");
            h.assertTrue(furnace.isEmpty() && !loaded.hasTasks(), "Finished furnace still had an input, output or pending task");
            h.succeed();
        } finally {
            BegleiterConfig.set(before);
        }
    }

    @GameTest(structure = ARENA)
    public void companionToolsRealFurnaceLeavesChangedInputsAlone(GameTestHelper h) {
        var before = BegleiterConfig.get();
        var config = new BegleiterConfig();
        config.realFurnaces = true;
        BegleiterConfig.set(config);
        try {
            var mob = companion(h);
            var pos = START.east();
            h.setBlock(pos, Blocks.FURNACE);
            var furnace = (FurnaceBlockEntity) h.getLevel().getBlockEntity(h.absolutePos(pos));
            mob.giveToInventory(new ItemStack(Items.RAW_IRON, 2));
            mob.giveToInventory(new ItemStack(Items.COAL, 2));
            furnace.setItem(0, new ItemStack(Items.SAND));
            mob.addTask(CompanionEntity.Task.smelt("iron_ingot", 2));
            var goal = new CompanionTaskGoal(mob);
            goal.tick();
            h.assertTrue(furnace.getItem(0).is(Items.SAND), "Companion overwrote the player's input");
            h.assertValueEqual(CompanionCraft.count(mob, "raw_iron"), 2, "Busy furnace consumed input");
            furnace.setItem(0, ItemStack.EMPTY);
            mob.addTask(CompanionEntity.Task.smelt("iron_ingot", 2));
            goal.tick();
            h.assertTrue(furnace.getItem(0).is(Items.RAW_IRON), "Companion didn't feed the now available furnace");
            furnace.setItem(0, new ItemStack(Items.SAND));
            goal.tick();
            h.assertTrue(furnace.getItem(0).is(Items.SAND) && !mob.hasTasks(), "Changed input wasn't left untouched");
            h.assertValueEqual(CompanionCraft.count(mob, "raw_iron"), 1, "Input was silently restored or fed twice");
            h.assertValueEqual(CompanionCraft.count(mob, "iron_ingot"), 0, "Companion received output for a changed input");
            h.succeed();
        } finally {
            BegleiterConfig.set(before);
        }
    }
}
