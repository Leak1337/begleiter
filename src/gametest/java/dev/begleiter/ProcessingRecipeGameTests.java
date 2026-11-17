package dev.begleiter;

import java.util.List;

import dev.begleiter.CompanionEntity.Task;
import dev.begleiter.task.TaskQueueCodec;
import dev.begleiter.task.TaskSpec;
import net.fabricmc.fabric.api.gametest.v1.GameTest;
import net.minecraft.core.BlockPos;
import net.minecraft.core.component.DataComponents;
import net.minecraft.core.registries.Registries;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ClientInformation;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.crafting.SmithingTransformRecipe;
import net.minecraft.world.item.crafting.StonecutterRecipe;
import net.minecraft.world.item.enchantment.Enchantments;
import net.minecraft.world.level.block.Blocks;

/** Stationsrezepte aus dem Spiel mit echten Zutaten, echten Komponenten und genauer Lieferung. */
public class ProcessingRecipeGameTests {
    private static final String ARENA = "begleiter-gametest:request_isolation";
    private static final String SMITHING = "minecraft:netherite_sword_smithing";
    private static final String STONECUTTING = "minecraft:stone_slab_from_stone_stonecutting";

    private static CompanionEntity companion(GameTestHelper h) {
        for (int x = 18; x <= 30; x++) for (int z = 18; z <= 30; z++) h.setBlock(x, 0, z, Blocks.STONE);
        var mob = h.spawn(CompanionMod.COMPANION, new BlockPos(24, 1, 24));
        mob.stay(); mob.setPersistenceRequired();
        return mob;
    }

    private static ServerPlayer owner(GameTestHelper h, CompanionEntity mob) {
        var player = h.makeMockServerPlayerInLevel(); mob.setOwner(player.getUUID());
        player.snapTo(mob.getX(), mob.getY(), mob.getZ(), 0, 0); player.getInventory().clearContent();
        return player;
    }

    private static int count(ServerPlayer player, Item item) {
        int count = 0;
        for (int slot = 0; slot < player.getInventory().getContainerSize(); slot++)
            if (player.getInventory().getItem(slot).is(item)) count += player.getInventory().getItem(slot).getCount();
        return count;
    }

    private static ItemStack find(ServerPlayer player, Item item) {
        for (int slot = 0; slot < player.getInventory().getContainerSize(); slot++) {
            ItemStack stack = player.getInventory().getItem(slot);
            if (stack.is(item)) return stack;
        }
        return ItemStack.EMPTY;
    }

    private static void inputs(CompanionEntity mob, Item base) {
        mob.giveToInventory(new ItemStack(base));
        mob.giveToInventory(new ItemStack(Items.NETHERITE_INGOT));
        mob.giveToInventory(new ItemStack(Items.NETHERITE_UPGRADE_SMITHING_TEMPLATE));
    }

    @GameTest(structure = ARENA)
    public void indexesNativeRecipeFamiliesAndInvalidatesCachedRecipes(GameTestHelper h) {
        int stonecutting = 0, smithing = 0;
        for (var recipe : h.getLevel().recipeAccess().getRecipes()) {
            if (!(recipe.value() instanceof StonecutterRecipe) && !(recipe.value() instanceof SmithingTransformRecipe)) continue;
            var indexed = CompanionRecipes.processingRecipe(h.getLevel(), recipe.id().identifier().toString());
            h.assertTrue(indexed != null && !indexed.ingredients().isEmpty(), "Unindexed station recipe: " + recipe.id());
            if (recipe.value() instanceof StonecutterRecipe) stonecutting++; else smithing++;
        }
        h.assertTrue(stonecutting > 20 && smithing > 5, "Did not cover the actual recipe families");
        var old = CompanionRecipes.processingRecipe(h.getLevel(), SMITHING);
        CompanionRecipes.reset();
        h.assertTrue(CompanionRecipes.processingRecipe(h.getLevel(), SMITHING) != old, "Kept cached wrapper after invalidation");
        h.succeed();
    }

    @GameTest(structure = ARENA)
    public void bothLanguagesAcceptSmithingWithoutChangingTheTarget(GameTestHelper h) {
        var mob = companion(h); mob.setNoAi(true); var player = owner(h, mob);
        for (String locale : List.of("de_de", "en_us")) {
            var old = player.clientInformation();
            player.updateOptions(new ClientInformation(locale, old.viewDistance(), old.chatVisibility(), old.chatColors(),
                    old.modelCustomisation(), old.mainHand(), old.textFilteringEnabled(), old.allowsListing(), old.particleStatus()));
            for (String words : List.of("Crafte mir ein Netheritschwert", "craft me a netherite sword")) {
                CompanionBrain.onChat(player, "@ " + words);
                h.assertTrue(mob.peekTasks(10).equals(List.of(Task.request("netherite_sword", 1, false))), "Wrong smithing target: " + words);
            }
        }
        h.succeed();
    }

    @GameTest(structure = ARENA, maxTicks = 200)
    public void smithingPreservesNameDamageAndEnchantmentsThroughDelivery(GameTestHelper h) {
        var mob = companion(h); var player = owner(h, mob); h.setBlock(26, 1, 24, Blocks.SMITHING_TABLE);
        ItemStack sword = new ItemStack(Items.DIAMOND_SWORD); sword.setDamageValue(37);
        sword.set(DataComponents.CUSTOM_NAME, Component.literal("Keep this name"));
        sword.enchant(h.getLevel().registryAccess().lookupOrThrow(Registries.ENCHANTMENT).getOrThrow(Enchantments.SHARPNESS), 3);
        var enchantments = sword.get(DataComponents.ENCHANTMENTS);
        mob.giveToInventory(sword);
        mob.giveToInventory(new ItemStack(Items.NETHERITE_INGOT));
        mob.giveToInventory(new ItemStack(Items.NETHERITE_UPGRADE_SMITHING_TEMPLATE));
        CompanionBrain.onChat(player, "@ Crafte mir ein Netheritschwert");
        h.succeedWhen(() -> {
            h.assertTrue(!mob.hasTasks(), "Smithing still running");
            ItemStack result = find(player, Items.NETHERITE_SWORD);
            h.assertTrue(!result.isEmpty(), "Smithing result not delivered");
            h.assertValueEqual(result.getDamageValue(), 37, "Upgrade repaired the base for free");
            h.assertValueEqual(result.getHoverName().getString(), "Keep this name", "Custom name lost");
            h.assertTrue(enchantments.equals(result.get(DataComponents.ENCHANTMENTS)), "Enchantments lost");
            h.assertValueEqual(CompanionCraft.count(mob, "netherite_ingot"), 0, "Ingot not consumed");
            h.assertValueEqual(CompanionCraft.count(mob, "netherite_upgrade_smithing_template"), 0, "Template not consumed");
        });
    }

    @GameTest(structure = ARENA, maxTicks = 200)
    public void armorBaseWaitsForSmithingInsteadOfBeingWorn(GameTestHelper h) {
        var mob = companion(h); var player = owner(h, mob); h.setBlock(26, 1, 24, Blocks.SMITHING_TABLE);
        inputs(mob, Items.DIAMOND_CHESTPLATE);
        CompanionBrain.onChat(player, "@ craft me a netherite chestplate");
        h.succeedWhen(() -> {
            h.assertTrue(!mob.hasTasks(), "Armor smithing still running");
            h.assertValueEqual(count(player, Items.NETHERITE_CHESTPLATE), 1, "Base armor was equipped before processing");
            h.assertTrue(mob.getItemBySlot(net.minecraft.world.entity.EquipmentSlot.CHEST).isEmpty(), "Wore the owner's armor");
        });
    }

    @GameTest(structure = ARENA, maxTicks = 180)
    public void smithingIngredientOrderDeliversTemplateBaseAndAdditionWithoutAStation(GameTestHelper h) {
        var mob = companion(h); var player = owner(h, mob); inputs(mob, Items.DIAMOND_SWORD);
        CompanionBrain.onChat(player, "@ bring me resources for a netherite sword");
        h.succeedWhen(() -> {
            h.assertTrue(!mob.hasTasks(), "Ingredient order still running");
            h.assertValueEqual(count(player, Items.DIAMOND_SWORD), 1, "Base not delivered");
            h.assertValueEqual(count(player, Items.NETHERITE_INGOT), 1, "Addition not delivered");
            h.assertValueEqual(count(player, Items.NETHERITE_UPGRADE_SMITHING_TEMPLATE), 1, "Template not delivered");
            h.assertValueEqual(count(player, Items.NETHERITE_SWORD), 0, "End product manufactured in ingredient order");
        });
    }

    @GameTest(structure = ARENA, maxTicks = 200)
    public void stonecuttingUsesNativeYieldAndDeliversOnlyTheRequestedAmount(GameTestHelper h) {
        var mob = companion(h); mob.setNoAi(true); var player = owner(h, mob);
        h.setBlock(26, 1, 24, Blocks.STONECUTTER); mob.giveToInventory(new ItemStack(Items.STONE, 2));
        CompanionBrain.onChat(player, "@ craft me 3 stone slabs"); new CompanionTaskGoal(mob).tick();
        h.assertTrue(mob.peekTasks(30).stream().anyMatch(task -> task.spec() instanceof TaskSpec.Process), "Did not select the stonecutter");
        mob.setNoAi(false);
        h.succeedWhen(() -> {
            h.assertTrue(!mob.hasTasks(), "Stonecutting still running");
            h.assertValueEqual(count(player, Items.STONE_SLAB), 3, "Requested output count wrong");
            h.assertValueEqual(CompanionCraft.count(mob, "stone_slab"), 1, "Batch remainder wrong");
            h.assertValueEqual(CompanionCraft.count(mob, "stone"), 0, "Wrong input amount");
        });
    }

    @GameTest(structure = ARENA, maxTicks = 200)
    public void modStonecuttingRecipeWorksWithoutAnItemSpecificRule(GameTestHelper h) {
        var mob = companion(h); var player = owner(h, mob); h.setBlock(26, 1, 24, Blocks.STONECUTTER);
        mob.giveToInventory(new ItemStack(Items.OAK_PLANKS, 2));
        CompanionBrain.onChat(player, "@ Crafte mir 3 Prüfkohle");
        h.succeedWhen(() -> {
            h.assertTrue(!mob.hasTasks(), "Mod recipe still running");
            h.assertValueEqual(count(player, RegistryIdTestItems.COAL), 3, "Mod result not delivered");
            h.assertValueEqual(CompanionCraft.count(mob, "begleiter-test:coal"), 1, "Mod recipe yield wrong");
            h.assertValueEqual(CompanionCraft.count(mob, "oak_planks"), 0, "Mod recipe inputs not consumed");
        });
    }

    @GameTest(structure = ARENA, maxTicks = 180)
    public void stonecuttingIngredientOrderRoundsTheYieldWithoutBuildingTheEndStation(GameTestHelper h) {
        var mob = companion(h); var player = owner(h, mob);
        mob.giveToInventory(new ItemStack(Items.OAK_PLANKS, 2));
        CompanionBrain.onChat(player, "@ bring me resources for 3 begleiter-test:coal");
        h.succeedWhen(() -> {
            h.assertTrue(!mob.hasTasks(), "Ingredient-only stonecutting still running");
            h.assertValueEqual(count(player, Items.OAK_PLANKS), 2, "Did not round 3 outputs to 2 recipe runs");
            h.assertValueEqual(count(player, RegistryIdTestItems.COAL), 0, "Processed the end item during an ingredient order");
            h.assertTrue(mob.findStation("stonecutter", 16) == null, "Built an unnecessary end-product station");
        });
    }

    @GameTest(structure = ARENA, maxTicks = 300)
    public void missingSmithingTableIsCraftedAndPlacedBeforeTheUpgrade(GameTestHelper h) {
        var mob = companion(h); var player = owner(h, mob); h.setBlock(26, 1, 24, Blocks.CRAFTING_TABLE);
        inputs(mob, Items.DIAMOND_SWORD);
        mob.giveToInventory(new ItemStack(Items.IRON_INGOT, 2)); mob.giveToInventory(new ItemStack(Items.OAK_PLANKS, 4));
        CompanionBrain.onChat(player, "@ craft me a netherite sword");
        h.succeedWhen(() -> {
            h.assertTrue(!mob.hasTasks(), "Station preparation still running");
            h.assertValueEqual(count(player, Items.NETHERITE_SWORD), 1, "Upgrade not delivered");
            h.assertTrue(mob.findStation("smithing_table", 16) != null, "Required station not placed");
            h.assertValueEqual(CompanionCraft.count(mob, "iron_ingot"), 0, "Station inputs not paid");
        });
    }

    @GameTest(structure = ARENA)
    public void missingInputsAndUnknownRecipesDoNotConsumeAnything(GameTestHelper h) {
        var mob = companion(h); mob.setNoAi(true);
        mob.giveToInventory(new ItemStack(Items.DIAMOND_SWORD)); mob.giveToInventory(new ItemStack(Items.NETHERITE_INGOT));
        h.assertTrue(CompanionRecipes.process(mob, h.getLevel(), SMITHING, Items.NETHERITE_SWORD, 1)
                == CompanionRecipes.ProcessingResult.MISSING_INPUT, "Missing template accepted");
        h.assertTrue(CompanionRecipes.process(mob, h.getLevel(), "begleiter-test:removed", Items.NETHERITE_SWORD, 1)
                == CompanionRecipes.ProcessingResult.INVALID_RECIPE, "Unknown recipe accepted");
        h.assertValueEqual(CompanionCraft.count(mob, "diamond_sword"), 1, "Base consumed on failure");
        h.assertValueEqual(CompanionCraft.count(mob, "netherite_ingot"), 1, "Addition consumed on failure");
        h.succeed();
    }

    @GameTest(structure = ARENA)
    public void fullOutputInventoryRollsBackTheEntireBatch(GameTestHelper h) {
        var mob = companion(h); mob.setNoAi(true);
        for (int slot = 0; slot < 35; slot++) mob.getInventory().setItem(slot, new ItemStack(Items.DIRT, 64));
        mob.getInventory().setItem(35, new ItemStack(Items.STONE, 64));
        h.assertTrue(CompanionRecipes.process(mob, h.getLevel(), STONECUTTING, Items.STONE_SLAB, 128)
                == CompanionRecipes.ProcessingResult.INVENTORY_FULL, "Allowed overflowing station output");
        h.assertValueEqual(CompanionCraft.count(mob, "stone"), 64, "Consumed input before output fit");
        h.assertValueEqual(CompanionCraft.count(mob, "stone_slab"), 0, "Partial output committed");
        h.succeed();
    }

    @GameTest(structure = ARENA)
    public void vanishedStationStopsDependenciesWithoutConsumingTheBase(GameTestHelper h) {
        var mob = companion(h); mob.setNoAi(true); var player = owner(h, mob); inputs(mob, Items.DIAMOND_SWORD);
        h.setBlock(26, 1, 24, Blocks.SMITHING_TABLE);
        mob.addTask(Task.process("netherite_sword", 1, SMITHING));
        mob.addTask(new Task(new TaskSpec.Request("netherite_sword", 1, false, 1, 0)));
        var goal = new CompanionTaskGoal(mob); goal.tick();
        h.assertTrue(mob.currentTask().spec() instanceof TaskSpec.Process, "Did not start processing");
        h.setBlock(26, 1, 24, Blocks.AIR); goal.tick();
        h.assertTrue(mob.isPaused() && mob.hasTasks(), "Missing station did not retain a paused chain");
        h.assertValueEqual(count(player, Items.NETHERITE_SWORD), 0, "Reported nonexistent production");
        h.assertValueEqual(CompanionCraft.count(mob, "diamond_sword"), 1, "Base consumed without station");
        h.assertValueEqual(CompanionCraft.count(mob, "netherite_upgrade_smithing_template"), 1, "Template consumed without station");
        h.succeed();
    }

    @GameTest(structure = ARENA, maxTicks = 200)
    public void namedBaseAndOtherIngredientsAreFetchedFromAChestBeforeSmithing(GameTestHelper h) {
        var mob = companion(h); var player = owner(h, mob); h.setBlock(26, 1, 24, Blocks.SMITHING_TABLE);
        BlockPos box = new BlockPos(22, 1, 24); h.setBlock(box, Blocks.BARREL);
        var contents = mob.containerAt(h.getLevel(), h.absolutePos(box));
        ItemStack sword = new ItemStack(Items.DIAMOND_SWORD); sword.setDamageValue(19);
        sword.set(DataComponents.CUSTOM_NAME, Component.literal("From storage"));
        contents.setItem(0, sword);
        contents.setItem(1, new ItemStack(Items.NETHERITE_INGOT));
        contents.setItem(2, new ItemStack(Items.NETHERITE_UPGRADE_SMITHING_TEMPLATE));
        CompanionBrain.onChat(player, "@ craft me a netherite sword");
        h.succeedWhen(() -> {
            h.assertTrue(!mob.hasTasks(), "Chest-backed smithing still running");
            ItemStack result = find(player, Items.NETHERITE_SWORD);
            h.assertTrue(!result.isEmpty() && result.getDamageValue() == 19, "Base stack not preserved during chest take");
            h.assertValueEqual(result.getHoverName().getString(), "From storage", "Chest stack components lost");
            h.assertTrue(contents.isEmpty(), "Ingredients were not taken from storage");
        });
    }

    @GameTest(structure = ARENA)
    public void optionalSlotsAndCustomSmithingResultsUseNativeAssembly(GameTestHelper h) {
        var mob = companion(h); mob.setNoAi(true);
        ItemStack base = new ItemStack(RegistryIdTestItems.STICK); base.set(DataComponents.CUSTOM_NAME, Component.literal("Mod base"));
        mob.giveToInventory(base); mob.giveToInventory(new ItemStack(Items.PAPER)); mob.giveToInventory(new ItemStack(Items.COPPER_INGOT));
        h.assertTrue(CompanionRecipes.process(mob, h.getLevel(), "begleiter-test:test_smithing", RegistryIdTestItems.RAW_IRON, 1)
                == CompanionRecipes.ProcessingResult.SUCCESS, "Custom smithing failed");
        h.assertValueEqual(mob.getInventory().getItem(mob.findItem(RegistryIdTestItems.RAW_IRON)).getHoverName().getString(), "Mod base", "Mod base components lost");
        mob.giveToInventory(new ItemStack(Items.AMETHYST_SHARD));
        h.assertTrue(CompanionRecipes.process(mob, h.getLevel(), "begleiter-test:test_optional_smithing", RegistryIdTestItems.DIAMOND, 1)
                == CompanionRecipes.ProcessingResult.SUCCESS, "Empty template/addition slots not retained");
        h.succeed();
    }

    @GameTest(structure = ARENA)
    public void selectedRecipePersistsAndNewTextsResolveInBothLanguages(GameTestHelper h) {
        var task = Task.process("netherite_sword", 2, SMITHING);
        var loaded = TaskQueueCodec.load(TaskQueueCodec.save(List.of(task), true));
        h.assertTrue(loaded.problem() == null && loaded.paused() && loaded.tasks().equals(List.of(task)), "Recipe ID changed on save/load");
        var invalid = new Task(new TaskSpec.Process("netherite_sword", 1, null));
        h.assertTrue(TaskQueueCodec.load(TaskQueueCodec.save(List.of(invalid), false)).problem() != null, "Missing recipe accepted");
        ServerTextGameTests.assertTranslatedContains(h, CompanionTexts.task(task), "An Station herstellen", "Process at station");
        ServerTextGameTests.assertTranslatedContains(h, CompanionTexts.tr("processing.missing_station", CompanionTexts.item("smithing_table")), "benötigte Station", "required station");
        ServerTextGameTests.assertTranslatedContains(h, CompanionTexts.tr("processing.inventory_full", CompanionTexts.item("netherite_sword")), "nicht genug Platz", "not enough inventory space");
        h.succeed();
    }
}
