package dev.begleiter;

import java.nio.file.Files;
import java.util.List;
import java.util.UUID;

import dev.begleiter.CompanionEntity.Task;
import dev.begleiter.task.TaskSpec;
import net.fabricmc.fabric.api.gametest.v1.GameTest;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.IntTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.NbtIo;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ClientInformation;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Blocks;

/** Real chat dispatch: addressing, language fallback, parameters and conditional intent execution. */
public class ChatAdapterGameTests {
    private static CompanionEntity companion(GameTestHelper h) {
        for (int x = 0; x < 8; x++) for (int z = 0; z < 8; z++) {
            h.setBlock(x, 0, z, Blocks.STONE);
            h.setBlock(x, 1, z, Blocks.DIRT);
        }
        var c = h.spawn(CompanionMod.COMPANION, new BlockPos(2, 2, 2));
        c.setNoAi(true);
        c.stay();
        c.setCustomName(Component.literal("Testbot"));
        return c;
    }

    private static ServerPlayer owner(GameTestHelper h, CompanionEntity c) {
        var p = h.makeMockServerPlayerInLevel();
        c.setOwner(p.getUUID());
        p.snapTo(c.getX(), c.getY(), c.getZ(), 0, 0);
        return p;
    }

    private static void language(ServerPlayer player, String language) {
        var old = player.clientInformation();
        player.updateOptions(new ClientInformation(language, old.viewDistance(), old.chatVisibility(), old.chatColors(),
                old.modelCustomisation(), old.mainHand(), old.textFilteringEnabled(), old.allowsListing(), old.particleStatus()));
    }

    private static void say(ServerPlayer player, String text) { CompanionBrain.onChat(player, "@ " + text); }

    private static List<Task> pair(GameTestHelper h, CompanionEntity c, ServerPlayer p, String de, String en) {
        List<Task> result = List.of();
        for (String locale : new String[]{"de_de", "en_us"}) {
            language(p, locale);
            c.cancelOrders();
            say(p, de);
            result = c.peekTasks(100);
            c.cancelOrders();
            say(p, en);
            h.assertTrue(result.equals(c.peekTasks(100)), "Different DE/EN tasks in " + locale + ": " + de + " / " + en);
        }
        return result;
    }

    @GameTest
    public void resourcesAndCountsWorkInBothClientLanguages(GameTestHelper h) {
        var c = companion(h); var p = owner(h, c);
        var tasks = pair(h, c, p, "Bring mir 10 Eisen", "bring me 10 iron");
        h.assertTrue(tasks.equals(List.of(Task.gather("eisen", 10), Task.give("eisen", 0))), "Iron quantity or recipient lost");
        tasks = pair(h, c, p, "Sammle Holz", "gather wood");
        h.assertTrue(tasks.equals(List.of(Task.gather("holz", 16))), "Default quantity or gather recipient wrong");
        tasks = pair(h, c, p, "Bring mir 99999 Eisen", "bring me 99999 iron");
        h.assertTrue(tasks.getFirst().equals(Task.gather("eisen", 256)), "Large count not bounded");
        h.succeed();
    }

    @GameTest
    public void itemRequestsAndWeakCountsReachThePlanner(GameTestHelper h) {
        var c = companion(h); var p = owner(h, c);
        c.giveToInventory(new ItemStack(Items.IRON_PICKAXE));
        c.giveToInventory(new ItemStack(Items.TORCH, 16));
        h.assertTrue(pair(h, c, p, "Mach mir eine Eisenspitzhacke", "make me an iron pickaxe")
                .equals(List.of(Task.request("minecraft:iron_pickaxe", 1, false))), "Fresh crafting ID or count lost");
        h.assertTrue(pair(h, c, p, "Bring mir ein paar Fackeln", "bring me a few torches")
                .equals(List.of(Task.give("item:minecraft:torch", 8))), "A few counted as one");
        c.giveToInventory(new ItemStack(Items.CLOCK));
        h.assertTrue(pair(h, c, p, "Hol mir clock", "get me clock")
                .equals(List.of(Task.give("item:minecraft:clock", 1))), "Registry fallback lost");
        h.succeed();
    }

    @GameTest
    public void animalsRepairTradeAndGearKeepTypedParameters(GameTestHelper h) {
        var c = companion(h); var p = owner(h, c);
        h.assertTrue(pair(h, c, p, "Hol ein Schaf", "fetch a sheep").equals(List.of(Task.fetch("sheep"))), "Fetch target lost");
        h.assertTrue(pair(h, c, p, "Züchte 2 Kühe", "breed 2 cows").equals(List.of(Task.breed("cow", 2))), "Breed target/count lost");
        h.assertTrue(pair(h, c, p, "Kümmer dich um die Tiere", "take care of the animals")
                .equals(List.of(Task.breed(null, 0))), "Care was mistaken for cow");
        h.assertTrue(pair(h, c, p, "Reparier dein Werkzeug", "repair your tools")
                .equals(List.of(Task.repair(90))), "Repair not dispatched");
        h.assertTrue(pair(h, c, p, "Kauf mir 3 Bücher", "buy me 3 books")
                .equals(List.of(Task.trade("book", 3), Task.give("item:minecraft:book", 0))), "Purchase params lost");
        h.assertTrue(pair(h, c, p, "Verkauf dein Zeug", "sell your stuff")
                .equals(List.of(Task.trade(null, 0))), "Sell became purchase");
        var gear = pair(h, c, p, "Rüste dich mit Netherit aus", "gear up with netherite");
        h.assertTrue(gear.size() == 1 && gear.getFirst().spec() instanceof TaskSpec.GearUp g && g.tier() == 5,
                "Gear tier lost");
        h.succeed();
    }

    @GameTest
    public void controlAndCombatModesUseBothLexica(GameTestHelper h) {
        var c = companion(h); var p = owner(h, c);
        for (String locale : new String[]{"de_de", "en_us"}) {
            language(p, locale);
            say(p, "follow me"); h.assertTrue(!c.isStaying() && !c.isAutonomous(), "EN follow not applied");
            say(p, "warte hier"); h.assertTrue(c.isStaying(), "DE stay not applied");
            say(p, "play on your own"); h.assertTrue(c.isAutonomous(), "EN free roam not applied");
            say(p, "folge mir"); h.assertTrue(!c.isStaying() && !c.isAutonomous(), "DE follow not applied");
            say(p, "do not fight"); h.assertTrue(c.stance == CompanionCombat.Stance.PASSIVE, "Passive mode lost");
            say(p, "protect me"); h.assertTrue(c.stance == CompanionCombat.Stance.DEFENSIVE, "Protect did not defend");
            say(p, "sei aggressiv"); h.assertTrue(c.stance == CompanionCombat.Stance.AGGRESSIVE, "DE stance lost");
        }
        h.succeed();
    }

    @GameTest
    public void modeSwitchPauseResumeAndCancelPreserveTheirSemantics(GameTestHelper h) {
        var c = companion(h); var p = owner(h, c);
        say(p, "build a small house");
        var build = c.build;
        h.assertTrue(build != null, "No build queued");
        say(p, "follow me"); h.assertTrue(c.build == build && build.paused && !c.hasTasks(), "Mode discarded or continued build");
        say(p, "continue"); h.assertTrue(!build.paused, "English continue did not resume build");
        say(p, "pause"); h.assertTrue(build.paused, "English pause did not pause build");
        say(p, "weiter"); h.assertTrue(!build.paused, "German continue did not resume");
        say(p, "cancel"); h.assertTrue(c.build == null && !c.hasTasks(), "English cancel retained build");
        h.succeed();
    }

    @GameTest
    public void structureStylesSizesAndFarmPlansMatch(GameTestHelper h) {
        var c = companion(h); var p = owner(h, c);
        for (String[] commands : new String[][]{{"Bau ein großes Steinhaus", "build a large stone house"},
                {"Bau ein kleines Birkenhaus", "build a small birch house"}, {"Bau eine große Farm", "build a large farm"}}) {
            say(p, commands[0]);
            h.assertTrue(c.build != null, "DE structure not dispatched");
            var expected = c.build.order;
            c.cancelOrders();
            say(p, commands[1]);
            h.assertTrue(c.build != null && c.build.order.equals(expected), "English size/style/farm differs: " + commands[1]);
            h.assertTrue(h.getBlockState(new BlockPos(2, 1, 2)).is(Blocks.DIRT), "Chat changed world before execution");
            c.cancelOrders();
        }
        h.succeed();
    }

    @GameTest
    public void terrainDimensionsAndFillerMatch(GameTestHelper h) {
        var c = companion(h); var p = owner(h, c);
        h.setBlock(2, 1, 3, Blocks.AIR);
        h.setBlock(3, 2, 3, Blocks.GRAVEL);
        say(p, "Planier hier 5x5 mit Bruchstein");
        h.assertTrue(c.build != null, "DE terrain missing");
        var expected = c.build.order;
        c.cancelOrders();
        say(p, "flatten here 5x5 with cobblestone");
        h.assertTrue(c.build != null && c.build.terrainOnly && c.build.order.equals(expected), "Terrain filler/dimensions differ");
        h.assertTrue(c.build.order.stream().anyMatch(b -> b.state().is(Blocks.COBBLESTONE)), "Cobblestone filler lost");
        h.assertTrue(h.getBlockState(new BlockPos(3, 2, 3)).is(Blocks.GRAVEL), "Chat executed terrain immediately");
        h.succeed();
    }

    @GameTest
    public void waypointsAndConditionalMemoryIntentsWork(GameTestHelper h) {
        var c = companion(h); var p = owner(h, c);
        say(p, "remember this as iron mine");
        var waypoint = c.memory.waypoint("iron mine", h.getLevel());
        h.assertTrue(waypoint != null, "English waypoint name lost");
        say(p, "go to iron mine");
        h.assertTrue(c.isStaying() && c.currentTask().equals(Task.moveTo(waypoint.pos, 4)), "Waypoint travel not dispatched");
        say(p, "forget waypoint iron mine");
        h.assertTrue(c.memory.waypoint("iron mine", h.getLevel()) == null, "Waypoint not forgotten");
        c.memory.setWaypoint("eisen mine", h.getLevel(), p.blockPosition());
        say(p, "Vergiss Wegpunkt Mine");
        h.assertTrue(c.memory.waypoint("eisen mine", h.getLevel()) == null, "German waypoint shorthand lost");
        c.memory.setWaypoint("iron mine", h.getLevel(), p.blockPosition());
        say(p, "forget waypoint my mine");
        h.assertTrue(c.memory.waypoint("iron mine", h.getLevel()) == null, "English waypoint shorthand lost");
        say(p, "Merk dir das als Mine");
        say(p, "forget your memory");
        h.assertValueEqual(c.memory.size(), 0, "Unknown waypoint prevented memory-clear fallback");
        h.succeed();
    }

    @GameTest
    public void homeFarmSortingAndKeeperReachExistingActions(GameTestHelper h) {
        var c = companion(h); var p = owner(h, c);
        h.setBlock(3, 2, 2, Blocks.CHEST);
        say(p, "this is your storage");
        h.assertTrue(h.absolutePos(new BlockPos(3, 2, 2)).equals(c.getHome()), "English home was not set");
        var tasks = pair(h, c, p, "Räum die Kisten auf", "sort the chests");
        h.assertTrue(tasks.equals(List.of(Task.collect(true), Task.deliver(false), Task.sort(c.getHome()))), "Sort actions lost");
        h.setBlock(4, 1, 4, Blocks.FARMLAND);
        tasks = pair(h, c, p, "Kümmer dich um das Feld", "take care of the field");
        h.assertTrue(tasks.equals(List.of(Task.farm(p.blockPosition()), Task.collect(false), Task.deliver(true))),
                "Farm care fell through to keeper/animal");
        say(p, "take care of the storage");
        h.assertTrue(c.isKeeper() && !c.hasTasks(), "English keeper not activated");
        h.assertTrue(pair(h, c, p, "Gib mir Eisen", "give me iron").equals(List.of(Task.give("eisen", 0))),
                "Give resource lost");
        h.succeed();
    }

    @GameTest
    public void queriesAndProjectPreviewDoNotReplaceOrders(GameTestHelper h) {
        var c = companion(h); var p = owner(h, c);
        Task previous = Task.give(null, 0); c.addTask(previous);
        for (String query : new String[]{"where is iron", "where is water", "where is bread", "where is wheat seed"}) {
            String reply = CompanionChatActions.interpret(p, c, query, "en_us");
            h.assertTrue(reply != null, "Query not resolved: " + query);
            if (query.endsWith("seed")) h.assertTrue(!reply.contains("Wasser"), "Seed confused with German lake");
            h.assertTrue(c.currentTask() == previous, "Query replaced the order");
        }
        say(p, "plan a base");
        h.assertTrue(c.currentTask() == previous && c.build == null, "Project preview started a build or discarded work");
        h.assertTrue(h.getBlockState(new BlockPos(2, 1, 2)).is(Blocks.DIRT), "Preview changed terrain");
        h.succeed();
    }

    @GameTest
    public void ignoredChatNamesAndOwnersRemainIsolated(GameTestHelper h) {
        var c = companion(h); var p = owner(h, c);
        int logs = c.logSize();
        CompanionBrain.onChat(p, "follow me");
        CompanionBrain.onChat(p, "the weather is nice");
        CompanionBrain.onChat(p, "@unknown follow me");
        h.assertTrue(c.isStaying() && c.logSize() == logs, "Unaddressed/unknown name changed companion");
        var other = h.spawn(CompanionMod.COMPANION, new BlockPos(5, 2, 5));
        other.setNoAi(true); other.stay(); other.setOwner(p.getUUID()); other.setCustomName(Component.literal("Moritz"));
        CompanionBrain.onChat(p, "@Moritz follow me");
        h.assertTrue(c.isStaying() && !other.isStaying(), "Named English command selected wrong companion");
        c.setOwner(UUID.randomUUID());
        CompanionBrain.onChat(p, "@Testbot follow me");
        h.assertTrue(c.isStaying(), "Foreign companion accepted English chat");
        h.succeed();
    }

    private static ListTag ints(int... values) {
        var list = new ListTag(); for (int value : values) list.add(IntTag.valueOf(value)); return list;
    }

    @GameTest
    public void schematicQueryAirAndRotationReachTheBuildPlan(GameTestHelper h) throws Exception {
        var c = companion(h); var p = owner(h, c);
        String name = "adapter" + UUID.randomUUID().toString().replace("-", "");
        var folder = CompanionSchematics.folders().getFirst(); Files.createDirectories(folder);
        var file = folder.resolve(name + ".nbt");
        var data = new CompoundTag(); data.put("size", ints(2, 1, 1));
        var palette = new ListTag();
        for (String block : new String[]{"minecraft:stone", "minecraft:air"}) {
            var entry = new CompoundTag(); entry.putString("Name", block); palette.add(entry);
        }
        data.put("palette", palette);
        var blocks = new ListTag();
        for (int x = 0; x < 2; x++) {
            var entry = new CompoundTag(); entry.put("pos", ints(x, 0, 0)); entry.putInt("state", x); blocks.add(entry);
        }
        data.put("blocks", blocks);
        try {
            NbtIo.writeCompressed(data, file);
            say(p, "Bau " + name + " mit Luft gedreht 90");
            h.assertTrue(c.build != null && c.build.total() == 2, "DE schematic/air lost");
            var expected = c.build.order;
            c.cancelOrders();
            say(p, "build " + name + " with air rotated 90");
            h.assertTrue(c.build != null && c.build.order.equals(expected), "EN schematic/air/rotation differs");
            c.cancelOrders();
            say(p, "build " + name);
            h.assertTrue(c.build != null && c.build.total() == 1, "Schematic without air clears blocks");
        } finally { Files.deleteIfExists(file); }
        h.succeed();
    }
}
