package dev.begleiter;

import net.fabricmc.fabric.api.gametest.v1.GameTest;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.tags.ItemTags;
import net.minecraft.world.SimpleContainer;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Blocks;

/** Item-Identität an echten registrierten, gleichnamigen Items verschiedener Namensräume. */
public class CraftRegistryIdGameTests {
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
    public void itemResolutionPreservesNamespacesAndVanillaAliases(GameTestHelper h) {
        h.assertTrue(CompanionCraft.item("coal") == Items.COAL, "Vanilla-Alias nicht aufgelöst");
        h.assertTrue(CompanionCraft.item("minecraft:coal") == Items.COAL, "Vollständige Vanilla-ID nicht aufgelöst");
        h.assertTrue(CompanionCraft.item("begleiter-test:coal") == RegistryIdTestItems.COAL, "Namensraum verloren");
        for (String id : new String[]{null, "", ":coal", "minecraft:", "INVALID ID", "x:y:z", "missing:coal", "x".repeat(257)})
            h.assertTrue(CompanionCraft.item(id) == Items.AIR, "Ungültige/unbekannte ID aufgelöst: " + id);
        h.succeed();
    }

    @GameTest
    public void inventoryCountsDoNotCombineEqualPaths(GameTestHelper h) {
        var c = companion(h);
        c.giveToInventory(new ItemStack(Items.COAL, 7));
        c.giveToInventory(new ItemStack(RegistryIdTestItems.COAL, 19));
        h.assertValueEqual(CompanionCraft.count(c, "coal"), 7, "Vanilla-Alias zählt fremde Kohle");
        h.assertValueEqual(CompanionCraft.count(c, "minecraft:coal"), 7, "Vollständige Vanilla-ID");
        h.assertValueEqual(CompanionCraft.count(c, "begleiter-test:coal"), 19, "Fremde Kohle");
        h.assertValueEqual(CompanionCraft.count(c, "#coals"), 7, "Fremde Kohle als Vanilla-Brennstoff");
        h.succeed();
    }

    @GameTest
    public void actualRegisteredItemsStaySeparateInChestMemory(GameTestHelper h) {
        var memory = new CompanionMemory();
        BlockPos pos = h.absolutePos(new BlockPos(3, 1, 3));
        memory.noteChest(h.getLevel(), pos, new SimpleContainer(
                new ItemStack(Items.COAL, 7), new ItemStack(RegistryIdTestItems.COAL, 19)));
        var loaded = new CompanionMemory();
        loaded.load(memory.save());
        var vanilla = loaded.chestWith(Items.COAL, h.getLevel(), pos, 2);
        var foreign = loaded.chestWith(RegistryIdTestItems.COAL, h.getLevel(), pos, 2);
        h.assertTrue(vanilla != null && foreign == vanilla, "Kiste nicht wiedergefunden");
        h.assertValueEqual(vanilla.itemCount(Items.COAL), 7, "Vanilla-Bestand nach Reload");
        h.assertValueEqual(foreign.itemCount(RegistryIdTestItems.COAL), 19, "Fremder Bestand nach Reload");
        h.succeed();
    }

    @GameTest
    public void resourceCategoriesUseExactItemsAndKeepExplicitTags(GameTestHelper h) {
        h.assertTrue(Resources.COAL.item().test(new ItemStack(Items.COAL)), "Vanilla-Kohle nicht erkannt");
        h.assertTrue(!Resources.COAL.item().test(new ItemStack(RegistryIdTestItems.COAL)), "Fremde Kohle verwechselt");
        h.assertTrue(!Resources.IRON.item().test(new ItemStack(RegistryIdTestItems.RAW_IRON)), "Fremdes Roheisen verwechselt");
        h.assertTrue(Resources.COAL.block().test(Blocks.COAL_ORE.defaultBlockState()), "Vanilla-Erz nicht erkannt");
        var log = new ItemStack(RegistryIdTestItems.OAK_LOG);
        h.assertTrue(log.is(ItemTags.LOGS) && Resources.WOOD.item().test(log), "Expliziter Holz-Tag wird nicht respektiert");
        h.succeed();
    }

    @GameTest
    public void qualifiedVanillaCraftUsesSameRecipeAndStationDecision(GameTestHelper h) {
        for (String id : new String[]{"torch", "minecraft:torch"}) {
            var c = companion(h);
            c.giveToInventory(new ItemStack(Items.COAL));
            c.giveToInventory(new ItemStack(Items.STICK));
            h.assertTrue(!CompanionCraft.needsTable(id) && !CompanionCraft.needsTable(c, id), "Fackeln benötigen Werkbank: " + id);
            h.assertTrue(CompanionCraft.craft(c, id, 4), "Fackelrezept fehlgeschlagen: " + id);
            h.assertValueEqual(CompanionCraft.count(c, "minecraft:torch"), 4, "Fackelausgabe");
            h.assertValueEqual(CompanionCraft.count(c, "coal"), 0, "Kohle nicht genau einmal gebucht");
        }
        h.succeed();
    }

    @GameTest
    public void foreignRecipeDoesNotUseVanillaRecipeOrIngredients(GameTestHelper h) {
        var c = companion(h);
        c.giveToInventory(new ItemStack(Items.OAK_PLANKS, 2));
        h.assertTrue(!CompanionCraft.craft(c, "begleiter-test:stick", 4), "Fremdes Item mit Vanilla-Rezept hergestellt");
        h.assertValueEqual(CompanionCraft.count(c, "oak_planks"), 2, "Fremdes Rezept verbraucht Vanilla-Zutaten");
        h.assertValueEqual(CompanionCraft.count(c, "stick"), 0, "Ungewollte Vanilla-Stöcke erzeugt");
        h.assertValueEqual(CompanionCraft.count(c, "begleiter-test:stick"), 0, "Fremdes Ergebnis ohne Rezept erzeugt");
        h.succeed();
    }

    @GameTest
    public void foreignCoalCannotFuelVanillaSmelting(GameTestHelper h) {
        var c = companion(h);
        c.giveToInventory(new ItemStack(Items.RAW_IRON, 2));
        c.giveToInventory(new ItemStack(RegistryIdTestItems.COAL, 3));
        h.assertTrue(!CompanionCraft.hasFuel(c), "Fremde Kohle fälschlich als Brennstoff erkannt");
        h.assertValueEqual(CompanionCraft.smeltStep(c, 2), 0, "Ohne passenden Brennstoff geschmolzen");
        h.assertValueEqual(CompanionCraft.count(c, "raw_iron"), 2, "Roheisen ohne Brennstoff verbraucht");
        h.assertValueEqual(CompanionCraft.count(c, "begleiter-test:coal"), 3, "Fremde Kohle verbrannt");
        h.succeed();
    }

    @GameTest
    public void foreignInputCannotUseVanillaSmeltingRecipe(GameTestHelper h) {
        var c = companion(h);
        c.giveToInventory(new ItemStack(RegistryIdTestItems.RAW_IRON, 2));
        c.giveToInventory(new ItemStack(Items.COAL));
        h.assertTrue(!CompanionCraft.hasSmeltable(c), "Fremde Eingabe als Vanilla-Roheisen erkannt");
        h.assertValueEqual(CompanionCraft.smeltStep(c, 2), 0, "Fremde Eingabe mit Vanilla-Rezept geschmolzen");
        h.assertValueEqual(CompanionCraft.count(c, "begleiter-test:raw_iron"), 2, "Fremde Eingabe verbraucht");
        h.assertValueEqual(CompanionCraft.count(c, "coal"), 1, "Brennstoff ohne Rezept verbraucht");
        h.succeed();
    }

    @GameTest
    public void vanillaSmeltingStillConsumesExactInputAndFuel(GameTestHelper h) {
        var c = companion(h);
        c.giveToInventory(new ItemStack(Items.RAW_IRON, 2));
        c.giveToInventory(new ItemStack(Items.COAL));
        h.assertValueEqual(CompanionCraft.smeltStep(c, 2), 2, "Vanilla-Schmelzen fehlgeschlagen");
        h.assertValueEqual(CompanionCraft.count(c, "minecraft:iron_ingot"), 2, "Schmelzausgabe");
        h.assertValueEqual(CompanionCraft.count(c, "minecraft:coal"), 0, "Brennstoffbuchung");
        h.succeed();
    }

    @GameTest
    public void foreignToolDoesNotSatisfyVanillaOwnershipOrTier(GameTestHelper h) {
        var c = companion(h);
        c.setItemSlot(EquipmentSlot.MAINHAND, new ItemStack(RegistryIdTestItems.IRON_PICKAXE));
        h.assertTrue(!CompanionCraft.owns(c, "iron_pickaxe"), "Fremdes Werkzeug als Vanilla-Besitz");
        h.assertTrue(CompanionCraft.owns(c, "begleiter-test:iron_pickaxe"), "Fremdes Werkzeug nicht erkannt");
        h.assertValueEqual(CompanionCraft.tier(c, "pickaxe"), 0, "Fremdes Item erhält Vanilla-Werkzeugstufe");
        h.assertTrue(CompanionCraft.bestTool(c, "pickaxe").isEmpty(), "Fremdes Item als Vanilla-Werkzeug ausgewählt");
        h.assertTrue(!CompanionCraft.owns(c, "air"), "Leere Ausrüstung als Itembesitz gezählt");
        h.succeed();
    }

    @GameTest
    public void totalCountIncludesEquippedStackWithoutForeignItems(GameTestHelper h) {
        var c = companion(h);
        c.giveToInventory(new ItemStack(Items.COAL, 7));
        c.giveToInventory(new ItemStack(RegistryIdTestItems.COAL, 3));
        c.setItemSlot(EquipmentSlot.MAINHAND, new ItemStack(Items.COAL, 5));
        h.assertValueEqual(CompanionCraft.countAll(c, "minecraft:coal"), 12, "Ausgerüsteten Stapel falsch gezählt");
        h.assertValueEqual(CompanionCraft.countAll(c, "begleiter-test:coal"), 3, "Namensräume im Gesamtbestand vermischt");
        h.succeed();
    }

    @GameTest
    public void unknownForeignPlanksDoNotConsumeLogsOrCreateOak(GameTestHelper h) {
        var c = companion(h);
        var log = new ItemStack(RegistryIdTestItems.OAK_LOG);
        h.assertTrue(log.is(ItemTags.LOGS), "Test-Stamm hat keinen Holz-Tag");
        c.giveToInventory(log);
        h.assertValueEqual(CompanionCraft.convertLogsToPlanks(c, 4), 0, "Unbekannte Bretter durch Eiche ersetzt");
        h.assertValueEqual(CompanionCraft.count(c, "begleiter-test:oak_log"), 1, "Fremden Stamm ohne Ausgabe verbraucht");
        c.giveToInventory(new ItemStack(Items.OAK_LOG));
        h.assertValueEqual(CompanionCraft.convertLogsToPlanks(c, 4), 4, "Vanilla-Stamm nicht mehr umgewandelt");
        h.assertValueEqual(CompanionCraft.count(c, "minecraft:oak_planks"), 4, "Vanilla-Brettausgabe");
        h.assertValueEqual(CompanionCraft.count(c, "begleiter-test:oak_log"), 1, "Fremden Stamm mit umgewandelt");
        h.succeed();
    }
}
