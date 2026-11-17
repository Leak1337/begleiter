package dev.begleiter;

import java.util.List;
import java.util.Map;
import dev.begleiter.CompanionEntity.Task;
import net.fabricmc.fabric.api.gametest.v1.GameTest;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.contents.TranslatableContents;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.block.Blocks;

/** Actual order side effects and multilingual replies, including native item components. */
public class OrderTextGameTests {
    private static CompanionEntity companion(GameTestHelper h) {
        for (int x = 0; x < 8; x++) for (int z = 0; z < 8; z++) h.setBlock(x, 0, z, Blocks.STONE);
        var c = h.spawn(CompanionMod.COMPANION, new BlockPos(2, 1, 2));
        c.setNoAi(true); c.stay(); return c;
    }

    private static ServerPlayer owner(GameTestHelper h, CompanionEntity c) {
        var p = h.makeMockServerPlayerInLevel();
        p.setGameMode(GameType.SURVIVAL); c.setOwner(p.getUUID());
        p.snapTo(c.getX(), c.getY(), c.getZ(), 0, 0);
        return p;
    }

    private static void translated(GameTestHelper h, Component reply, String german, String english) throws Exception {
        h.assertTrue(reply != null && reply.getContents() instanceof TranslatableContents, "Order reply flattened");
        ServerTextGameTests.inLanguage("de_de", () -> h.assertTrue(reply.getString().contains(german), reply.getString()));
        ServerTextGameTests.inLanguage("en_us", () -> {
            h.assertTrue(reply.getString().contains(english), reply.getString());
            h.assertTrue(!reply.getString().contains("server.begleiter."), "Unresolved translation: " + reply.getString());
        });
    }

    @GameTest
    public void resourcesAndPlannerRepliesKeepAmountsAndNativeItems(GameTestHelper h) throws Exception {
        var c = companion(h);
        var reply = CompanionBrain.orderGatherReply(c, Resources.IRON, 7, true);
        translated(h, reply, "7 Eisen", "7 iron");
        h.assertTrue(c.peekTasks(5).equals(List.of(Task.gather("eisen", 7), Task.give("eisen", 0))), "Gather task changed");
        c.giveToInventory(new ItemStack(Items.TORCH, 12));
        reply = CompanionBrain.orderObtainReply(c, Items.TORCH, 8, true);
        translated(h, reply, "8x", "already carrying 8x");
        h.assertTrue(((TranslatableContents) reply.getContents()).getArgs()[1] instanceof Component item
                && item.getContents() instanceof TranslatableContents, "Native item translation flattened");
        h.assertTrue(c.peekTasks(5).equals(List.of(Task.give("item:minecraft:torch", 8))), "Owned-item task changed");
        Task previous = c.currentTask();
        translated(h, CompanionBrain.orderObtainReply(c, RegistryIdTestItems.DIAMOND, 1, true), "weiß nicht", "do not know");
        h.assertTrue(c.currentTask() == previous, "Failed plan cancelled existing order");
        c.giveToInventory(new ItemStack(Items.OAK_LOG, 2));
        translated(h, CompanionBrain.orderObtainReply(c, Items.CRAFTING_TABLE, 1, false), "Ich besorge", "I will obtain");
        h.assertTrue(c.peekTasks(10).stream().anyMatch(t -> t.type() == CompanionEntity.TaskType.CRAFT), "Craft plan not queued");
        h.succeed();
    }

    @GameTest
    public void everydayOrdersKeepTargetsQuantitiesAndFailureState(GameTestHelper h) throws Exception {
        var c = companion(h); var p = owner(h, c);
        translated(h, CompanionBrain.orderBreedReply(c, "minecraft:cow", 2), "2x Nachwuchs", "2 offspring");
        h.assertTrue(c.peekTasks(5).equals(List.of(Task.breed("cow", 2))) && c.herdKeeping, "Breeding params changed");
        translated(h, CompanionBrain.orderTradeReply(c, Items.BOOK, 3, true), "3x", "buy 3x");
        h.assertTrue(c.peekTasks(5).equals(List.of(Task.trade("book", 3), Task.give("item:minecraft:book", 0))), "Trade params changed");
        translated(h, CompanionBrain.orderRepairReply(c), "Werkzeug", "tools and armor");
        h.assertTrue(c.peekTasks(5).equals(List.of(Task.repair(90))), "Repair task changed");
        Task previous = c.currentTask();
        translated(h, CompanionBrain.orderFarmReply(p, c), "kein Feld", "cannot see a field");
        h.assertTrue(c.currentTask() == previous, "Missing field cancelled existing work");
        translated(h, CompanionBrain.orderGearUpReply(c, 5), "Netherit", "netherite equipment");
        h.assertTrue(c.currentTask().spec() instanceof dev.begleiter.task.TaskSpec.GearUp gear && gear.tier() == 5, "Gear tier changed");
        h.succeed();
    }

    @GameTest
    public void buildAndTerrainRepliesKeepMaterialsAndPauseSemantics(GameTestHelper h) throws Exception {
        var c = companion(h); var p = owner(h, c);
        translated(h, CompanionBrain.orderStructureReply(p, c, List.of("haus", "klein", "stein")), "Steinhaus", "stone house");
        h.assertTrue(c.build != null && c.currentTask().equals(Task.build()), "Build order changed");
        var job = c.build;
        translated(h, CompanionBrain.pauseBuildReply(c, true), "pausiert", "paused at");
        h.assertTrue(c.build == job && job.paused && !c.hasTasks(), "Pause lost the build");
        translated(h, CompanionBrain.pauseBuildReply(c, false), "weiter", "Resuming");
        h.assertTrue(c.build == job && !job.paused, "Resume lost the build");
        translated(h, CompanionBrain.cancelBuildReply(c), "abgebrochen", "cancelled");
        h.assertTrue(c.build == null && !c.hasTasks(), "Cancel retained build");
        h.setBlock(2, 0, 3, Blocks.AIR);
        translated(h, CompanionBrain.orderTerraformReply(p, c, CompanionTerraform.Mode.FLATTEN,
                List.of("3x3"), Blocks.COBBLESTONE), "Planieren 3x3", "Flatten 3x3");
        h.assertTrue(c.build != null && c.build.terrainOnly, "Terrain order lost its guard");
        var materials = CompanionTexts.materials(Map.of(Items.DIAMOND, 4));
        translated(h, CompanionTexts.tr("materials.required", materials), "4x", "Materials: 4x");
        h.succeed();
    }

    @GameTest
    public void waypointAndMemoryRepliesPreserveLiteralNamesAndDimensions(GameTestHelper h) throws Exception {
        var c = companion(h); var p = owner(h, c);
        var saved = CompanionChatActions.interpretReply(p, c, "remember this as iron mine", "en_us");
        translated(h, saved, "Gemerkt", "Remembered");
        h.assertTrue(c.memory.waypoint("iron mine", h.getLevel()) != null, "Waypoint not stored");
        c.memory.setWaypoint("Berta %s Ä", h.getLevel(), p.blockPosition().east(10));
        var list = CompanionChatActions.interpretReply(p, c, "which waypoints do you know", "en_us");
        translated(h, list, "Meine Wegpunkte", "My waypoints");
        ServerTextGameTests.inLanguage("en_us", () -> h.assertTrue(list.getString().contains("Berta %s Ä")
                && list.getString().contains("east"), list.getString()));
        translated(h, CompanionTexts.memorySummary(c), "Wegpunkt", "waypoint");
        translated(h, CompanionChatActions.interpretReply(p, c, "forget waypoint mine", "en_us"), "vergessen", "forgotten");
        h.assertTrue(c.memory.waypoint("iron mine", h.getLevel()) == null, "Partial waypoint name not forgotten");
        translated(h, CompanionChatActions.interpretReply(p, c, "forget your memory", "en_us"), "alles vergessen", "forgotten everything");
        h.assertTrue(c.memory.size() == 0, "Memory not cleared");
        h.succeed();
    }

    @GameTest
    public void failedSchematicReadKeepsOrdersAndCustomFilename(GameTestHelper h) throws Exception {
        var c = companion(h); var p = owner(h, c);
        Task previous = Task.give(null, 0); c.addTask(previous);
        var file = CompanionSchematics.folders().getFirst().resolve("missing_%s_Ä.nbt");
        var reply = CompanionBrain.startSchematicReply(p, c, file, false, net.minecraft.world.level.block.Rotation.NONE);
        translated(h, reply, "kann ich nicht lesen", "cannot read schematic");
        ServerTextGameTests.inLanguage("en_us", () -> h.assertTrue(reply.getString().contains("missing_%s_Ä.nbt"), reply.getString()));
        h.assertTrue(c.currentTask() == previous && c.build == null, "Failed schematic read replaced work");
        h.succeed();
    }

    @GameTest
    public void longWaypointRepliesStayBoundedWithoutDeletingPlaces(GameTestHelper h) throws Exception {
        var c = companion(h); var p = owner(h, c);
        for (int i = 0; i < 80; i++) c.memory.setWaypoint("place " + i + " " + "x".repeat(100), h.getLevel(), p.blockPosition());
        var reply = CompanionChatActions.interpretReply(p, c, "which waypoints do you know", "en_us");
        ServerTextGameTests.inLanguage("en_us", () -> {
            h.assertTrue(reply.getString().length() < 4096, "Waypoint reply grew beyond its display budget");
            h.assertTrue(reply.getString().contains("more)"), "Omitted places not announced");
        });
        h.assertValueEqual(c.memory.waypoints().size(), 80, "Display truncation deleted waypoints");
        h.succeed();
    }

    @GameTest
    public void menuOrderFeedbackRemainsTranslatedAfterSnapshotEncoding(GameTestHelper h) throws Exception {
        var c = companion(h); var p = owner(h, c);
        var reply = CompanionActions.get(8).handler().executeComponent(c, p, false);
        c.setUiFeedback(reply);
        var original = CompanionSnapshot.capture(c, 1, CompanionMenu.Page.MAIN);
        var buffer = new RegistryFriendlyByteBuf(io.netty.buffer.Unpooled.buffer(), h.getLevel().registryAccess());
        try {
            CompanionSnapshot.CODEC.encode(buffer, original);
            var decoded = CompanionSnapshot.CODEC.decode(buffer);
            ServerTextGameTests.inLanguage("en_us", () -> h.assertTrue(decoded.feedback().contains("bring you 16 wood"), decoded.feedback()));
            ServerTextGameTests.inLanguage("de_de", () -> h.assertTrue(decoded.feedback().contains("16 Holz"), decoded.feedback()));
        } finally { buffer.release(); }
        h.assertTrue(c.peekTasks(5).equals(List.of(Task.gather("holz", 16), Task.give("holz", 0))), "Menu order changed");
        h.succeed();
    }
}
