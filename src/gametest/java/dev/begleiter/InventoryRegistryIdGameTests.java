package dev.begleiter;

import java.util.Optional;
import net.fabricmc.fabric.api.gametest.v1.GameTest;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.world.SimpleContainer;
import net.minecraft.world.entity.EntityTypes;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.trading.ItemCost;
import net.minecraft.world.item.trading.MerchantOffer;
import net.minecraft.world.level.block.Blocks;

/** Keine Vanilla-Inventarpolitik aufgrund eines fremden, gleichnamigen Item-Pfads. */
public class InventoryRegistryIdGameTests {
    private static CompanionEntity companion(GameTestHelper h) {
        var c = h.spawn(CompanionMod.COMPANION, new BlockPos(2, 1, 2));
        c.stay();
        c.setNoAi(true);
        c.getInventory().clearContent();
        return c;
    }

    @GameTest
    public void publicItemIdsKeepTheirNamespace(GameTestHelper h) {
        h.assertTrue(Resources.id(new ItemStack(Items.DIRT)).equals("minecraft:dirt"), "Vanilla-ID ohne Namensraum");
        h.assertTrue(Resources.id(new ItemStack(RegistryIdTestItems.DIRT)).equals("begleiter-test:dirt"), "Fremder Namensraum verloren");
        h.succeed();
    }

    @GameTest
    public void foreignNamesDoNotBecomeJunkOrScaffolding(GameTestHelper h) {
        var c = companion(h);
        c.giveToInventory(new ItemStack(RegistryIdTestItems.DIRT, 64));
        c.giveToInventory(new ItemStack(RegistryIdTestItems.COBBLESTONE, 64));
        h.assertTrue(!CompanionEntity.isJunk(RegistryIdTestItems.DIRT), "Fremde Erde als Gerüstmaterial");
        h.assertTrue(!CompanionEntity.isTrash(RegistryIdTestItems.ROTTEN_FLESH), "Fremdes Item als Gammelfleisch");
        h.assertValueEqual(c.scaffoldCount(), 0, "Fremdes Material als Gerüstvorrat gezählt");
        h.assertTrue(c.scaffoldItem() == null && c.usedSlotsWithoutClutter() == c.usedSlots(), "Fremdes Material zum Verbrauch/Abwurf gewählt");
        c.giveToInventory(new ItemStack(Items.DIRT, 8));
        h.assertValueEqual(c.scaffoldCount(), 8, "Vanilla-Gerüstmaterial nicht mehr nutzbar");
        h.succeed();
    }

    @GameTest
    public void fullInventoryDoesNotDiscardForeignTrashNames(GameTestHelper h) {
        var c = companion(h);
        for (int i = 0; i < c.getInventory().getContainerSize(); i++)
            c.getInventory().setItem(i, new ItemStack(RegistryIdTestItems.ROTTEN_FLESH, 64));
        h.assertTrue(!c.makeRoomFor(Items.DIAMOND, true), "Fremdes Item als Vanilla-Abfall abgeworfen");
        h.assertValueEqual(c.usedSlotsWithoutClutter(), c.usedSlots(), "Automatischer Abwurf enthält fremde Items");
        h.assertValueEqual(c.getInventory().getItem(0).getCount(), 64, "Fremder Stapel verändert");
        h.succeed();
    }

    @GameTest
    public void quotasDoNotKeepForeignCoalOrStone(GameTestHelper h) {
        var c = companion(h);
        c.giveToInventory(new ItemStack(RegistryIdTestItems.COAL, 5));
        c.giveToInventory(new ItemStack(RegistryIdTestItems.COBBLESTONE, 5));
        c.giveToInventory(new ItemStack(Items.COAL, 10));
        var storage = new SimpleContainer(9);
        h.assertValueEqual(c.depositInto(storage, true), 0, "Einlagern fehlgeschlagen");
        h.assertValueEqual(CompanionCraft.count(c, "begleiter-test:coal"), 0, "Fremde Kohle als Vanilla-Reserve behalten");
        h.assertValueEqual(CompanionCraft.count(c, "begleiter-test:cobblestone"), 0, "Fremden Stein als Reserve behalten");
        h.assertValueEqual(CompanionCraft.count(c, "minecraft:coal"), 8, "Vanilla-Reserve verändert");
        int total = 0;
        for (int i = 0; i < storage.getContainerSize(); i++) total += storage.getItem(i).getCount();
        h.assertValueEqual(total, 12, "Einlagermenge verloren oder verdoppelt");
        h.succeed();
    }

    @GameTest
    public void buildingMaterialsDoNotUseForeignStoneNames(GameTestHelper h) {
        var c = companion(h);
        c.giveToInventory(new ItemStack(RegistryIdTestItems.COBBLESTONE, 20));
        c.giveToInventory(new ItemStack(Items.COBBLESTONE, 3));
        h.assertTrue(!c.buildMaterials().containsKey(RegistryIdTestItems.COBBLESTONE), "Fremdes Item als Baustein eingeplant");
        h.assertValueEqual(c.buildMaterials().get(Items.COBBLESTONE), 3, "Vanilla-Baustein verloren");
        h.succeed();
    }

    @GameTest
    public void categoriesUseComponentsAndTagsInsteadOfForeignNames(GameTestHelper h) {
        h.assertTrue(CompanionJobs.category(new ItemStack(RegistryIdTestItems.RAW_IRON)) == CompanionJobs.Category.OTHER,
                "Fremdes Rohmaterial allein nach Namen einsortiert");
        h.assertTrue(CompanionJobs.category(new ItemStack(RegistryIdTestItems.IRON_PICKAXE)) == CompanionJobs.Category.OTHER,
                "Fremdes Item ohne Werkzeugfunktion als Spitzhacke einsortiert");
        h.assertTrue(CompanionJobs.category(new ItemStack(RegistryIdTestItems.OAK_LOG)) == CompanionJobs.Category.WOOD,
                "Expliziter Fremd-Holz-Tag ignoriert");
        h.assertTrue(CompanionJobs.category(new ItemStack(Items.RAW_IRON)) == CompanionJobs.Category.ORES, "Vanilla-Erzkategorie verloren");
        h.succeed();
    }

    @GameTest
    public void automaticTradingDoesNotSellForeignItemsByName(GameTestHelper h) {
        var c = companion(h);
        c.giveToInventory(new ItemStack(RegistryIdTestItems.DIRT, 64));
        var trader = h.spawn(EntityTypes.VILLAGER, new BlockPos(4, 1, 2));
        trader.setNoAi(true);
        trader.getOffers().clear();
        trader.getOffers().add(new MerchantOffer(new ItemCost(RegistryIdTestItems.DIRT, 1), Optional.empty(),
                new ItemStack(Items.EMERALD), 12, 1, .05f));
        h.assertTrue(CompanionJobs.findOffer(c, trader, null) == null, "Fremdes Item ohne eigene Wertpolitik zum Verkauf gewählt");
        h.assertValueEqual(CompanionCraft.count(c, "begleiter-test:dirt"), 64, "Fremdes Item verbraucht");
        h.assertTrue(CompanionEntity.isPrecious(Items.DIAMOND) && !CompanionEntity.isPrecious(RegistryIdTestItems.DIAMOND),
                "Wertklassifikation verwechselt Namensräume");
        h.succeed();
    }

    @GameTest
    public void foreignMatureCropIsNotHarvestedOrReplaced(GameTestHelper h) {
        var c = companion(h);
        var pos = h.absolutePos(new BlockPos(4, 1, 4));
        h.setBlock(4, 0, 4, Blocks.FARMLAND);
        var mature = RegistryIdTestItems.WHEAT.getStateForAge(RegistryIdTestItems.WHEAT.getMaxAge());
        h.getLevel().setBlockAndUpdate(pos, mature);
        c.giveToInventory(new ItemStack(Items.WHEAT_SEEDS, 8));
        h.assertTrue(!CompanionJobs.isMatureCrop(mature), "Nicht unterstützte Fremdpflanze als Vanilla-Weizen erkannt");
        h.assertValueEqual(CompanionJobs.workFarmSpot(h.getLevel(), c, pos), 0, "Fremdpflanze bearbeitet");
        h.assertTrue(h.getLevel().getBlockState(pos).equals(mature), "Fremdpflanze zerstört/ersetzt");
        h.assertValueEqual(CompanionCraft.count(c, "wheat_seeds"), 8, "Vanilla-Samen für Fremdpflanze verbraucht");
        h.succeed();
    }
}
