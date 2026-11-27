package dev.begleiter;

import java.util.Set;

import net.fabricmc.fabric.api.gametest.v1.GameTest;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;

/** Die Datapack-Tags entscheiden fachlich genau wie die früheren Listen; Datapacks können sie erweitern. */
public class ItemTagGameTests {
    private static final Set<String> OLD_JUNK = Set.of("dirt", "coarse_dirt", "rooted_dirt", "cobblestone", "cobbled_deepslate",
            "netherrack", "stone", "andesite", "diorite", "granite", "tuff", "calcite", "deepslate", "blackstone", "basalt",
            "smooth_basalt", "end_stone", "dripstone_block", "mud", "soul_soil");
    private static final Set<String> OLD_TRASH = Set.of("rotten_flesh", "poisonous_potato", "spider_eye", "gravel", "sand",
            "red_sand", "kelp", "dead_bush", "short_grass", "tall_grass", "fern", "pointed_dripstone", "bone_block",
            "wheat_seeds", "beetroot_seeds", "pumpkin_seeds", "melon_seeds", "egg", "ink_sac");
    /** Vom Test-Datapack ergänzt (src/gametest/resources/data/begleiter/tags/item/junk.json). */
    private static final String TEST_JUNK = "moss_block";

    private static boolean oldPrecious(String id) {
        return id.endsWith("_ore") || id.startsWith("raw_") || id.endsWith("_ingot") || id.equals("diamond")
                || id.equals("emerald") || id.equals("ancient_debris") || id.equals("netherite_scrap") || id.equals("lapis_lazuli")
                || id.equals("redstone") || id.equals("coal") || id.equals("obsidian") || id.equals("ender_pearl") || id.equals("blaze_rod");
    }

    private static boolean oldKeepFixed(ItemStack s) {
        return s.is(net.minecraft.tags.ItemTags.SWORDS)
                || s.is(Items.TORCH) || s.is(Items.CRAFTING_TABLE) || s.is(Items.FURNACE) || s.is(Items.SHIELD) || s.is(Items.BUCKET)
                || s.is(Items.WATER_BUCKET) || s.is(Items.FLINT_AND_STEEL) || s.is(Items.BLAZE_ROD) || s.is(Items.BLAZE_POWDER)
                || s.is(Items.ENDER_PEARL) || s.is(Items.ENDER_EYE) || s.is(Items.BOW) || s.is(Items.ARROW) || s.is(Items.STRING)
                || s.is(Items.FEATHER) || s.is(Items.FLINT);
    }

    @GameTest
    public void tagsDecideLikeTheFormerListsForEveryItem(GameTestHelper h) {
        int checked = 0;
        for (Item item : BuiltInRegistries.ITEM) {
            var key = BuiltInRegistries.ITEM.getKey(item);
            boolean vanilla = "minecraft".equals(key.getNamespace());
            String id = key.getPath();
            boolean junk = vanilla && (OLD_JUNK.contains(id) || id.equals(TEST_JUNK));
            h.assertTrue(CompanionEntity.isJunk(item) == junk, "junk differs for " + key);
            h.assertTrue(CompanionEntity.isTrash(item) == (junk || vanilla && OLD_TRASH.contains(id)), "trash differs for " + key);
            h.assertTrue(CompanionEntity.isPrecious(item) == (vanilla && oldPrecious(id)), "precious differs for " + key);
            var stack = new ItemStack(item);
            h.assertTrue(stack.is(CompanionEntity.KEEP) == oldKeepFixed(stack), "keep differs for " + key);
            checked++;
        }
        h.assertTrue(checked > 1000, "Registry suspiciously small: " + checked);
        h.succeed();
    }

    @GameTest
    public void aDatapackCanExtendTheJunkTag(GameTestHelper h) {
        h.assertTrue(CompanionEntity.isJunk(Items.MOSS_BLOCK), "Test datapack entry not applied");
        h.assertTrue(CompanionEntity.isTrash(Items.MOSS_BLOCK), "Extended junk not treated as trash");
        h.assertTrue(!CompanionEntity.isJunk(Items.DIAMOND), "Diamond became junk");
        h.succeed();
    }
}
