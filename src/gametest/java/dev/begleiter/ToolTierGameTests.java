package dev.begleiter;

import net.fabricmc.fabric.api.gametest.v1.GameTest;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.resources.Identifier;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;

/** Werkzeugstufen nach Fähigkeit (Tags, Abbaubarkeit, Haltbarkeit) ergeben die bisherige sinnvolle Rangfolge. */
public class ToolTierGameTests {
    private static final String[] MATERIALS = {"wooden", "golden", "stone", "copper", "iron", "diamond", "netherite"};
    private static final int[] EXPECTED = {1, 1, 2, 2, 3, 4, 5};
    private static final String[] KINDS = {"pickaxe", "axe", "shovel", "hoe", "sword"};

    @GameTest
    public void everyVanillaToolGetsItsFormerTier(GameTestHelper h) {
        int checked = 0;
        for (String kind : KINDS) {
            for (int m = 0; m < MATERIALS.length; m++) {
                var id = Identifier.withDefaultNamespace(MATERIALS[m] + "_" + kind);
                var item = BuiltInRegistries.ITEM.getValue(id);
                h.assertTrue(item != Items.AIR, "Missing vanilla tool " + id);
                int tier = CompanionCraft.toolTier(new ItemStack(item), kind);
                h.assertValueEqual(tier, EXPECTED[m], id + " tier");
                for (String other : KINDS)
                    if (!other.equals(kind)) h.assertValueEqual(CompanionCraft.toolTier(new ItemStack(item), other), 0, id + " counted as " + other);
                checked++;
            }
        }
        h.assertValueEqual(checked, 35, "Checked tools");
        h.succeed();
    }

    @GameTest
    public void nonToolsAndDamageDoNotChangeTheTier(GameTestHelper h) {
        h.assertValueEqual(CompanionCraft.toolTier(new ItemStack(Items.STICK), "pickaxe"), 0, "Stick is no pickaxe");
        h.assertValueEqual(CompanionCraft.toolTier(ItemStack.EMPTY, "sword"), 0, "Empty stack");
        var worn = new ItemStack(Items.IRON_PICKAXE);
        worn.setDamageValue(worn.getMaxDamage() - 1);
        h.assertValueEqual(CompanionCraft.toolTier(worn, "pickaxe"), 3, "Worn iron pickaxe keeps its ability");
        h.succeed();
    }

    @GameTest
    public void companionPicksItsBestToolByAbility(GameTestHelper h) {
        var c = h.spawn(CompanionMod.COMPANION, new net.minecraft.core.BlockPos(1, 1, 1));
        c.setNoAi(true);
        c.getInventory().clearContent();
        c.setItemInHand(net.minecraft.world.InteractionHand.MAIN_HAND, ItemStack.EMPTY);
        c.giveToInventory(new ItemStack(Items.GOLDEN_PICKAXE));
        c.giveToInventory(new ItemStack(Items.IRON_PICKAXE));
        c.giveToInventory(new ItemStack(Items.STONE_SWORD));
        h.assertValueEqual(CompanionCraft.tier(c, "pickaxe"), 3, "Best pickaxe tier");
        h.assertTrue(CompanionCraft.bestTool(c, "pickaxe").is(Items.IRON_PICKAXE), "Gold chosen over iron");
        h.assertValueEqual(CompanionCraft.tier(c, "sword"), 2, "Sword tier");
        h.assertValueEqual(CompanionCraft.tier(c, "axe"), 0, "No axe");
        h.succeed();
    }
}
