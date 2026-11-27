package dev.begleiter;

import net.fabricmc.fabric.api.gametest.v1.GameTest;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;

/** Feste Rezeptliste bereitet nur vor, hergestellt und geschmolzen wird mit den echten Rezepten der Welt. */
public class RecipeSystemGameTests {
    private static CompanionEntity companion(GameTestHelper h) {
        var c = h.spawn(CompanionMod.COMPANION, new BlockPos(2, 1, 2));
        c.stay();
        c.setNoAi(true);
        c.getInventory().clearContent();
        c.setItemSlot(EquipmentSlot.MAINHAND, ItemStack.EMPTY);
        c.setItemSlot(EquipmentSlot.OFFHAND, ItemStack.EMPTY);
        return c;
    }

    @GameTest
    public void fixedListItemsAreBalancedByTheRealRecipe(GameTestHelper h) {
        var c = companion(h);
        c.giveToInventory(new ItemStack(Items.SPRUCE_PLANKS, 4));
        c.giveToInventory(new ItemStack(Items.BIRCH_PLANKS, 2));
        c.giveToInventory(new ItemStack(Items.IRON_INGOT, 4));
        h.assertTrue(CompanionCraft.craft(c, "shield", 1), "Shield from mixed planks failed");
        h.assertValueEqual(CompanionCraft.count(c, "shield"), 1, "Shield output");
        h.assertValueEqual(CompanionCraft.count(c, "#planks"), 0, "Planks not booked exactly");
        h.assertValueEqual(CompanionCraft.count(c, "iron_ingot"), 3, "Iron not booked exactly");
        h.assertTrue(CompanionCraft.craft(c, "bucket", 1), "Bucket failed");
        h.assertValueEqual(CompanionCraft.count(c, "iron_ingot"), 0, "Bucket iron");
        h.assertValueEqual(CompanionCraft.count(c, "bucket"), 1, "Bucket output");
        h.succeed();
    }

    @GameTest
    public void doorUsesTheWoodHeHasInsteadOfAnOakFallback(GameTestHelper h) {
        var c = companion(h);
        c.giveToInventory(new ItemStack(Items.SPRUCE_PLANKS, 6));
        h.assertTrue(CompanionCraft.craft(c, "oak_door", 3), "Door from spruce planks failed");
        h.assertValueEqual(CompanionCraft.count(c, "spruce_door"), 3, "Spruce doors");
        h.assertValueEqual(CompanionCraft.count(c, "oak_door"), 0, "Oak doors made from spruce");
        h.assertValueEqual(CompanionCraft.count(c, "#planks"), 0, "Planks left over");
        h.assertTrue(CompanionDoors.doorSlot(c) >= 0, "Spruce door not usable as a door");
        h.succeed();
    }

    @GameTest
    public void doorFromMixedPlanksUsesLogsOfOneWood(GameTestHelper h) {
        var c = companion(h);
        c.giveToInventory(new ItemStack(Items.OAK_PLANKS, 3));
        c.giveToInventory(new ItemStack(Items.SPRUCE_PLANKS, 3));
        c.giveToInventory(new ItemStack(Items.SPRUCE_LOG, 1));
        c.giveToInventory(new ItemStack(Items.BIRCH_LOG, 1));
        h.assertTrue(CompanionCraft.craft(c, "oak_door", 3), "Door from mixed planks plus a spruce log failed");
        h.assertValueEqual(CompanionCraft.count(c, "spruce_door"), 3, "Spruce doors");
        h.assertValueEqual(CompanionCraft.count(c, "spruce_planks"), 1, "Spruce planks left");
        h.assertValueEqual(CompanionCraft.count(c, "oak_planks"), 3, "Oak planks must stay");
        h.assertValueEqual(CompanionCraft.count(c, "birch_log"), 1, "Birch log must not be cut");
        h.succeed();
    }

    @GameTest
    public void doorFromMixedPlanksWithoutLogsFailsVisibly(GameTestHelper h) {
        var c = companion(h);
        c.giveToInventory(new ItemStack(Items.OAK_PLANKS, 3));
        c.giveToInventory(new ItemStack(Items.SPRUCE_PLANKS, 3));
        h.assertTrue(!CompanionCraft.craft(c, "oak_door", 3), "Door from three and three planks must fail");
        h.assertValueEqual(CompanionCraft.count(c, "#planks"), 6, "Planks lost on a failed door");
        h.assertTrue(!c.craftProblemComponent().getString().isEmpty(), "Failed door without a reason");
        h.succeed();
    }

    @GameTest
    public void recipeRemaindersComeBack(GameTestHelper h) {
        var c = companion(h);
        c.giveToInventory(new ItemStack(Items.MILK_BUCKET));
        c.giveToInventory(new ItemStack(Items.MILK_BUCKET));
        c.giveToInventory(new ItemStack(Items.MILK_BUCKET));
        c.giveToInventory(new ItemStack(Items.SUGAR, 2));
        c.giveToInventory(new ItemStack(Items.EGG));
        c.giveToInventory(new ItemStack(Items.WHEAT, 3));
        h.assertTrue(CompanionCraft.craft(c, "cake", 1), "Cake failed");
        h.assertValueEqual(CompanionCraft.count(c, "cake"), 1, "Cake output");
        h.assertValueEqual(CompanionCraft.count(c, "bucket"), 3, "Empty buckets not returned");
        h.assertValueEqual(CompanionCraft.count(c, "milk_bucket"), 0, "Milk not consumed");
        h.succeed();
    }

    @GameTest
    public void datapackRecipeForAForeignItemIsUsed(GameTestHelper h) {
        var c = companion(h);
        c.giveToInventory(new ItemStack(Items.IRON_NUGGET, 2));
        h.assertTrue(CompanionCraft.craft(c, "begleiter-test:stick", 3), "Datapack recipe not used");
        h.assertValueEqual(CompanionCraft.count(c, "begleiter-test:stick"), 3, "Foreign output");
        h.assertValueEqual(CompanionCraft.count(c, "iron_nugget"), 0, "Nuggets not consumed");
        h.assertValueEqual(CompanionCraft.count(c, "stick"), 0, "Vanilla sticks made");
        h.succeed();
    }

    @GameTest
    public void autoSmeltingUsesTheTagAndRealFurnaceRecipes(GameTestHelper h) {
        var c = companion(h);
        c.giveToInventory(new ItemStack(Items.COBBLESTONE, 4)); // hat ein Ofenrezept, steht aber nicht im Tag
        c.giveToInventory(new ItemStack(Items.BEEF, 2));
        c.giveToInventory(new ItemStack(Items.KELP, 3)); // per Test-Datapack im Tag
        c.giveToInventory(new ItemStack(Items.COAL, 1));
        h.assertValueEqual(CompanionCraft.rawFood(c), 5, "Raw food (beef + kelp)");
        h.assertTrue(CompanionCraft.hasSmeltable(c), "Nothing smeltable");
        h.assertValueEqual(CompanionCraft.smeltStep(c, 10), 5, "Smelted items");
        h.assertValueEqual(CompanionCraft.count(c, "cooked_beef"), 2, "Cooked beef");
        h.assertValueEqual(CompanionCraft.count(c, "dried_kelp"), 3, "Dried kelp");
        h.assertValueEqual(CompanionCraft.count(c, "cobblestone"), 4, "Cobblestone smelted without being asked");
        h.assertTrue(!CompanionCraft.hasSmeltable(c), "Still something to smelt");
        h.succeed();
    }
}
