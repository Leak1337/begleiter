package dev.begleiter;

import java.util.List;

import dev.begleiter.CompanionEntity.Task;
import dev.begleiter.task.TaskQueueCodec;
import dev.begleiter.task.TaskSpec;
import net.fabricmc.fabric.api.gametest.v1.GameTest;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.server.level.ClientInformation;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.Container;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Blocks;

/** Isolated recipe orders: actual chest/mining/crafting/owner transfer, and general registry lookup. */
public class RecipeRequestGameTests {
    private static final String ARENA = "begleiter-gametest:request_isolation";

    private static CompanionEntity companion(GameTestHelper h) {
        for (int x = 18; x <= 30; x++) for (int z = 18; z <= 30; z++) h.setBlock(x, 0, z, Blocks.STONE);
        var mob = h.spawn(CompanionMod.COMPANION, new BlockPos(24, 1, 24));
        mob.stay(); mob.setPersistenceRequired();
        return mob;
    }

    private static ServerPlayer owner(GameTestHelper h, CompanionEntity mob) {
        var player = h.makeMockServerPlayerInLevel();
        mob.setOwner(player.getUUID());
        player.snapTo(mob.getX(), mob.getY(), mob.getZ(), 0, 0);
        player.getInventory().clearContent();
        return player;
    }

    private static void language(ServerPlayer player, String language) {
        var old = player.clientInformation();
        player.updateOptions(new ClientInformation(language, old.viewDistance(), old.chatVisibility(), old.chatColors(),
                old.modelCustomisation(), old.mainHand(), old.textFilteringEnabled(), old.allowsListing(), old.particleStatus()));
    }

    private static int count(ServerPlayer owner, Item item) {
        int total = 0;
        for (int i = 0; i < owner.getInventory().getContainerSize(); i++)
            if (owner.getInventory().getItem(i).is(item)) total += owner.getInventory().getItem(i).getCount();
        return total;
    }

    private static Container barrel(GameTestHelper h, CompanionEntity mob, int x, Item item, int count) {
        BlockPos pos = new BlockPos(x, 1, 24);
        h.setBlock(pos, Blocks.BARREL);
        Container box = mob.containerAt(h.getLevel(), h.absolutePos(pos));
        box.setItem(0, new ItemStack(item, count));
        return box;
    }

    @GameTest(structure = ARENA)
    public void allRegistryIdsAndBilingualNamesResolveWithoutAnAllowlist(GameTestHelper h) {
        for (Item item : BuiltInRegistries.ITEM) {
            if (item == Items.AIR) continue;
            String id = BuiltInRegistries.ITEM.getKey(item).toString();
            h.assertTrue(CompanionItemNames.resolve(id).equals(List.of(item)), "ID not resolved exactly: " + id);
        }
        for (String name : List.of("Steinsäge", "Stonecutter", "stonecutters"))
            h.assertTrue(CompanionItemNames.resolve(name).equals(List.of(Items.STONECUTTER)), "Name missing: " + name);
        h.assertTrue(CompanionItemNames.resolve("Schwarzeichenholztreppe").equals(List.of(Items.DARK_OAK_STAIRS)), "German multiword lookup");
        h.assertTrue(CompanionItemNames.resolve("Dark Oak Stairs").equals(List.of(Items.DARK_OAK_STAIRS)), "English multiword lookup");
        for (String name : List.of("Prüfstab", "Test Rod", "Test Rods"))
            h.assertTrue(CompanionItemNames.resolve(name).equals(List.of(RegistryIdTestItems.STICK)), "Mod language lookup: " + name);
        h.assertTrue(CompanionItemNames.resolve("not-a-real-mod:stick").isEmpty(), "Unknown namespace mapped to vanilla");
        h.succeed();
    }

    @GameTest(structure = ARENA)
    public void requestsDispatchInBothInputAndGameLanguages(GameTestHelper h) {
        var mob = companion(h); mob.setNoAi(true); var player = owner(h, mob);
        for (String locale : List.of("de_de", "en_us")) {
            language(player, locale);
            for (String words : List.of("Crafte mir 2 Steinsägen", "craft me 2 stonecutters")) {
                CompanionBrain.onChat(player, "@ " + words);
                h.assertTrue(mob.peekTasks(8).equals(List.of(Task.request("stonecutter", 2, false))), "Wrong crafting order: " + words);
            }
            for (String words : List.of("Bringe mir Zutaten für 2 Steinsägen", "bring me materials for 2 stonecutters")) {
                CompanionBrain.onChat(player, "@ " + words);
                h.assertTrue(mob.peekTasks(8).equals(List.of(Task.request("stonecutter", 2, true))), "Wrong ingredient order: " + words);
            }
            CompanionBrain.onChat(player, "@ craft me 5 begleiter-test:stick");
            h.assertTrue(mob.peekTasks(8).equals(List.of(Task.request("begleiter-test:stick", 5, false))), "Address removed namespace punctuation");
        }
        h.succeed();
    }

    @GameTest(structure = ARENA)
    public void invalidUncraftableAndUnclearOrdersPreserveExistingWork(GameTestHelper h) {
        var mob = companion(h); mob.setNoAi(true); var player = owner(h, mob);
        Task old = Task.gather("holz", 3); mob.addTask(old);
        for (String words : List.of("craft me an imaginary gadget", "craft me bedrock", "crafte mir eine Rüstung", "craft me 0 boats")) {
            CompanionBrain.onChat(player, "@ " + words);
            h.assertTrue(mob.currentTask() == old && mob.taskCount() == 1, "Rejected request replaced work: " + words);
        }
        CompanionBrain.onChat(player, "craft me a stonecutter");
        h.assertTrue(mob.currentTask() == old, "Unaddressed request executed");
        h.succeed();
    }

    @GameTest(structure = ARENA, maxTicks = 180)
    public void ingredientsAreDeliveredWithoutCraftingTheEndItem(GameTestHelper h) {
        var mob = companion(h); var player = owner(h, mob);
        mob.giveToInventory(new ItemStack(Items.OAK_PLANKS, 5));
        CompanionBrain.onChat(player, "@ Bringe mir Ressourcen für einen Stock");
        h.succeedWhen(() -> {
            h.assertTrue(!mob.hasTasks(), "Order still running");
            h.assertValueEqual(count(player, Items.OAK_PLANKS), 2, "Immediate ingredients not delivered");
            h.assertValueEqual(count(player, Items.STICK), 0, "End item crafted during ingredient order");
            h.assertValueEqual(CompanionCraft.count(mob, "oak_planks"), 3, "Extra stock given away");
        });
    }

    @GameTest(structure = ARENA, maxTicks = 240)
    public void freshlyCraftsAnArbitraryRecipeAndRetainsExistingEndStock(GameTestHelper h) {
        var mob = companion(h); var player = owner(h, mob);
        h.setBlock(26, 1, 24, Blocks.CRAFTING_TABLE);
        mob.giveToInventory(new ItemStack(Items.STONE, 6));
        mob.giveToInventory(new ItemStack(Items.IRON_INGOT, 2));
        mob.giveToInventory(new ItemStack(Items.STONECUTTER));
        Container box = barrel(h, mob, 22, Items.STONECUTTER, 3);
        CompanionBrain.onChat(player, "@ Craft me 2 stonecutters");
        h.succeedWhen(() -> {
            h.assertTrue(!mob.hasTasks(), "Order still running");
            h.assertValueEqual(count(player, Items.STONECUTTER), 2, "Fresh products not delivered");
            h.assertValueEqual(CompanionCraft.count(mob, "stonecutter"), 1, "Existing end stock used as fresh production");
            h.assertValueEqual(box.getItem(0).getCount(), 3, "Took ready-made end products from chest");
            h.assertValueEqual(CompanionCraft.count(mob, "stone"), 0, "Recipe inputs not consumed");
        });
    }

    @GameTest(structure = ARENA, maxTicks = 180)
    public void combinesPartialIngredientsFromTwoChestsBeforeWorldGathering(GameTestHelper h) {
        var mob = companion(h); var player = owner(h, mob);
        Container one = barrel(h, mob, 22, Items.GOLD_INGOT, 4);
        Container two = barrel(h, mob, 26, Items.GOLD_INGOT, 4);
        mob.giveToInventory(new ItemStack(Items.APPLE));
        CompanionBrain.onChat(player, "@ bring me resources for a golden apple");
        h.succeedWhen(() -> {
            h.assertTrue(!mob.hasTasks(), "Order still running");
            h.assertValueEqual(count(player, Items.GOLD_INGOT), 8, "Chest amounts not combined");
            h.assertValueEqual(count(player, Items.APPLE), 1, "Apple not delivered");
            h.assertTrue(one.isEmpty() && two.isEmpty(), "Did not use both partial chests");
            h.assertValueEqual(count(player, Items.GOLDEN_APPLE), 0, "Crafted instead of delivering ingredients");
        });
    }

    @GameTest(structure = ARENA, maxTicks = 400)
    public void takesChestPartThenFindsAndProcessesOnlyMissingWorldMaterials(GameTestHelper h) {
        var mob = companion(h); var player = owner(h, mob);
        Container box = barrel(h, mob, 22, Items.OAK_PLANKS, 1);
        h.setBlock(26, 1, 26, Blocks.OAK_LOG);
        h.setBlock(26, 2, 26, Blocks.OAK_LEAVES); // ein Baum: laublose Stämme gelten als gebaut und bleiben stehen
        CompanionBrain.onChat(player, "@ bring me ingredients for a stick");
        h.succeedWhen(() -> {
            h.assertTrue(!mob.hasTasks(), "Order still running");
            h.assertValueEqual(count(player, Items.OAK_PLANKS), 2, "Missing world ingredient not processed and delivered");
            h.assertTrue(box.isEmpty(), "Chest portion skipped");
            h.assertTrue(!h.getBlockState(new BlockPos(26, 1, 26)).is(Blocks.OAK_LOG), "World resource not gathered");
            h.assertValueEqual(CompanionCraft.count(mob, "oak_planks"), 3, "Recipe batch remainder wrong");
        });
    }

    // Wege zur Tonne und zum Baum teilen sich im vollen Lauf das Navigationsbudget aller Tests (wie F-68/F-75).
    @GameTest(structure = ARENA, maxTicks = 1200)
    public void changedChestReplansFromActualPartialTake(GameTestHelper h) {
        var mob = companion(h); mob.setNoAi(true); var player = owner(h, mob);
        Container box = barrel(h, mob, 22, Items.OAK_PLANKS, 2);
        h.setBlock(26, 1, 26, Blocks.OAK_LOG);
        h.setBlock(26, 2, 26, Blocks.OAK_LEAVES); // ein Baum: laublose Stämme gelten als gebaut und bleiben stehen
        CompanionBrain.onChat(player, "@ bring me ingredients for a stick");
        new CompanionTaskGoal(mob).tick(); // Planen, solange die Kiste noch die zugesagte Menge enthält.
        box.setItem(0, new ItemStack(Items.OAK_PLANKS, 1));
        mob.setNoAi(false);
        h.succeedWhen(() -> {
            h.assertTrue(!mob.hasTasks(), "Order still running");
            h.assertValueEqual(count(player, Items.OAK_PLANKS), 2, "Changed chest caused an incomplete delivery");
            h.assertTrue(box.isEmpty(), "Partial take lost");
            h.assertTrue(!h.getBlockState(new BlockPos(26, 1, 26)).is(Blocks.OAK_LOG), "Missing material not gathered after source change");
        });
    }

    @GameTest(structure = ARENA, maxTicks = 180)
    public void modRecipeAndBatchYieldWorkWithoutItemSpecificCode(GameTestHelper h) {
        var mob = companion(h); var player = owner(h, mob);
        mob.giveToInventory(new ItemStack(Items.IRON_NUGGET, 4));
        CompanionBrain.onChat(player, "@ Crafte mir 5 Prüfstab");
        h.succeedWhen(() -> {
            h.assertTrue(!mob.hasTasks(), "Order still running");
            h.assertValueEqual(count(player, RegistryIdTestItems.STICK), 5, "Mod result not delivered");
            h.assertValueEqual(CompanionCraft.count(mob, "begleiter-test:stick"), 1, "Batch excess wrong");
            h.assertValueEqual(CompanionCraft.count(mob, "iron_nugget"), 0, "Ingredient quantity wrong");
        });
    }

    @GameTest(structure = ARENA, maxTicks = 240)
    public void furnaceRecipeProducesTheRequestedNewAmountAlongsideExistingStock(GameTestHelper h) {
        var mob = companion(h); var player = owner(h, mob);
        h.setBlock(26, 1, 24, Blocks.FURNACE);
        mob.giveToInventory(new ItemStack(Items.IRON_INGOT, 2));
        mob.giveToInventory(new ItemStack(Items.RAW_IRON, 2));
        mob.giveToInventory(new ItemStack(Items.COAL));
        CompanionBrain.onChat(player, "@ Crafte mir 2 Eisenbarren");
        h.succeedWhen(() -> {
            h.assertTrue(!mob.hasTasks(), "Smelting order still running");
            h.assertValueEqual(count(player, Items.IRON_INGOT), 2, "New smelted amount not delivered");
            h.assertValueEqual(CompanionCraft.count(mob, "iron_ingot"), 2, "Existing end stock delivered as new production");
            h.assertValueEqual(CompanionCraft.count(mob, "raw_iron"), 0, "Raw materials not smelted");
            h.assertValueEqual(CompanionCraft.count(mob, "coal"), 0, "Fuel not consumed");
        });
    }

    @GameTest(structure = ARENA, maxTicks = 650)
    public void fullOwnerInventoryRetainsPartialDeliveryAcrossQueueReload(GameTestHelper h) {
        var mob = companion(h); var player = owner(h, mob);
        for (int slot = 0; slot < 35; slot++) player.getInventory().setItem(slot, new ItemStack(Items.COBBLESTONE, 64));
        mob.giveToInventory(new ItemStack(Items.OAK_BOAT)); mob.giveToInventory(new ItemStack(Items.OAK_BOAT));
        mob.addTask(new Task(new TaskSpec.Request("oak_boat", 2, false, 2, 0)));
        mob.addTask(new Task(new TaskSpec.Request("oak_boat", 2, false, 3, 0)));
        h.runAfterDelay(500, () -> {
            h.assertValueEqual(count(player, Items.OAK_BOAT), 1, "First delivery portion wrong");
            h.assertValueEqual(CompanionCraft.count(mob, "oak_boat"), 1, "Remainder dropped or duplicated");
            var loaded = TaskQueueCodec.load(TaskQueueCodec.save(mob.peekTasks(100), false));
            h.assertTrue(loaded.problem() == null && ((TaskSpec.Request) loaded.tasks().getFirst().spec()).count() == 1,
                    "Partial delivery remainder not persisted");
            mob.cancelAll(); loaded.tasks().forEach(mob::addTask);
            player.getInventory().setItem(0, ItemStack.EMPTY);
        });
        h.succeedWhen(() -> {
            h.assertValueEqual(count(player, Items.OAK_BOAT), 2, "Did not resume remaining delivery");
            h.assertTrue(!mob.hasTasks(), "Delivery never completed");
            h.assertValueEqual(CompanionCraft.count(mob, "oak_boat"), 0, "Undelivered remainder");
        });
    }

    @GameTest(structure = ARENA)
    public void checkpointPersistsBaselineAndRejectsBrokenRequestData(GameTestHelper h) {
        var task = new Task(new TaskSpec.Request("begleiter-test:stick", 5, false, 1, 3));
        var loaded = TaskQueueCodec.load(TaskQueueCodec.save(List.of(task), true));
        h.assertTrue(loaded.problem() == null && loaded.paused() && loaded.tasks().equals(List.of(task)), "Checkpoint changed on load");
        var bad = TaskQueueCodec.save(List.of(new Task(new TaskSpec.Request("stick", 0, false, 2, 0))), false);
        h.assertTrue(TaskQueueCodec.load(bad).problem() != null, "Malformed request was accepted");
        h.succeed();
    }

    @GameTest(structure = ARENA, maxTicks = 250)
    public void completeArmorIsCraftedForTheOwnerInsteadOfEquipped(GameTestHelper h) {
        var mob = companion(h); var player = owner(h, mob);
        h.setBlock(26, 1, 24, Blocks.CRAFTING_TABLE);
        mob.giveToInventory(new ItemStack(Items.IRON_INGOT, 24));
        CompanionBrain.onChat(player, "@ Crafte mir eine komplette Eisenrüstung");
        h.succeedWhen(() -> {
            h.assertTrue(!mob.hasTasks(), "Armor order still running");
            for (Item piece : List.of(Items.IRON_HELMET, Items.IRON_CHESTPLATE, Items.IRON_LEGGINGS, Items.IRON_BOOTS))
                h.assertValueEqual(count(player, piece), 1, "Requested armor not delivered: " + piece);
            for (var slot : List.of(net.minecraft.world.entity.EquipmentSlot.HEAD, net.minecraft.world.entity.EquipmentSlot.CHEST,
                    net.minecraft.world.entity.EquipmentSlot.LEGS, net.minecraft.world.entity.EquipmentSlot.FEET))
                h.assertTrue(mob.getItemBySlot(slot).isEmpty(), "Wore the owner's delivery");
        });
    }

    @GameTest(structure = ARENA)
    public void playerRepliesAndCheckpointsRemainTranslated(GameTestHelper h) {
        var mob = companion(h); mob.setNoAi(true); var player = owner(h, mob);
        var craft = CompanionChatActions.interpretReply(player, mob, "craft me a stonecutter", "en_us");
        ServerTextGameTests.assertTranslatedContains(h, craft, "Ich stelle dir", "I will craft");
        var materials = CompanionChatActions.interpretReply(player, mob, "bringe mir Ressourcen für Steinsäge", "en_us");
        ServerTextGameTests.assertTranslatedContains(h, materials, "Rezeptzutaten", "recipe ingredients");
        var missing = CompanionChatActions.interpretReply(player, mob, "crafte mir bedrock", "de_de");
        ServerTextGameTests.assertTranslatedContains(h, missing, "Herstellungsrezept", "crafting recipe");
        ServerTextGameTests.assertTranslatedContains(h, CompanionTexts.tr("request.owner_full"), "Inventar ist voll", "inventory is full");
        h.succeed();
    }

    @GameTest(structure = ARENA, maxTicks = 150)
    public void failedPrerequisiteNeverDeliversExistingProductsAsNewlyCrafted(GameTestHelper h) {
        var mob = companion(h); var player = owner(h, mob);
        mob.giveToInventory(new ItemStack(Items.STONECUTTER, 2));
        // Deliberately invalid station followed by a fresh-production checkpoint.
        mob.addTask(Task.station("bedrock", false));
        mob.addTask(new Task(new TaskSpec.Request("stonecutter", 1, false, 1, 1)));
        h.succeedWhen(() -> {
            h.assertTrue(mob.isPaused() && mob.hasTasks(), "Failed dependency did not retain a paused order");
            h.assertValueEqual(count(player, Items.STONECUTTER), 0, "Failure continued into delivery");
            h.assertValueEqual(CompanionCraft.count(mob, "stonecutter"), 2, "Existing stock lost");
        });
    }
}
