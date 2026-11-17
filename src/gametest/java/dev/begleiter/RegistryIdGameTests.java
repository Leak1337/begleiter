package dev.begleiter;

import net.fabricmc.fabric.api.gametest.v1.GameTest;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.world.SimpleContainer;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;

/** Vollständige Item-IDs und Migration der Kistenerinnerungen. Keine echten Spielstände. */
public class RegistryIdGameTests {
    private static BlockPos chest(GameTestHelper h) {
        return h.absolutePos(new BlockPos(2, 1, 2));
    }

    private static CompoundTag storedChest(GameTestHelper h, CompoundTag items) {
        CompoundTag place = new CompoundTag();
        place.putString("K", "CHEST");
        place.putString("D", CompanionMemory.dim(h.getLevel()));
        place.putLong("P", chest(h).asLong());
        place.put("Items", items);
        ListTag places = new ListTag();
        places.add(place);
        CompoundTag data = new CompoundTag();
        data.put("Places", places);
        return data;
    }

    @GameTest
    public void observationsUseFullItemIds(GameTestHelper h) {
        var memory = new CompanionMemory();
        memory.noteChest(h.getLevel(), chest(h), new SimpleContainer(
                new ItemStack(Items.COAL, 7), new ItemStack(Items.COAL, 3), new ItemStack(Items.RAW_GOLD, 4)));
        var place = memory.chestWith(Items.COAL, h.getLevel(), chest(h), 2);
        h.assertTrue(place != null && !place.items.containsKey("coal"), "Unqualifizierte Item-ID gemerkt");
        h.assertValueEqual(place.itemCount(Items.COAL), 10, "Beobachteter Kohlebestand");
        CompoundTag saved = memory.save();
        h.assertValueEqual(saved.getIntOr("V", -1), 1, "Speicherversion");
        var loaded = new CompanionMemory();
        loaded.load(saved);
        h.assertValueEqual(loaded.all().getFirst().itemCount(Items.RAW_GOLD), 4, "Goldbestand nach Reload");
        h.succeed();
    }

    @GameTest
    public void equalPathsKeepSeparateNamespacesAcrossReload(GameTestHelper h) {
        CompoundTag items = new CompoundTag();
        items.putInt("minecraft:coal", 7);
        items.putInt("example:coal", 31);
        var memory = new CompanionMemory();
        memory.load(storedChest(h, items));
        var loaded = new CompanionMemory();
        loaded.load(memory.save());
        var place = loaded.all().getFirst();
        h.assertValueEqual(place.items.size(), 2, "Namensräume zusammengelegt");
        h.assertValueEqual(place.itemCount(Items.COAL), 7, "Vanilla-Kohle mit fremdem Item verwechselt");
        h.assertValueEqual(place.items.get("example:coal"), 31, "Fremder Namensraum verloren");
        h.succeed();
    }

    @GameTest
    public void foreignPathDoesNotSatisfyVanillaChestSearch(GameTestHelper h) {
        CompoundTag items = new CompoundTag();
        items.putInt("example:coal", 31);
        var memory = new CompanionMemory();
        memory.load(storedChest(h, items));
        h.assertTrue(memory.chestWith(Items.COAL, h.getLevel(), chest(h), 2) == null,
                "Fremde Kohle erfüllt Suche nach Vanilla-Kohle");
        h.succeed();
    }

    @GameTest
    public void legacyItemIdsMigrateToMinecraft(GameTestHelper h) {
        CompoundTag items = new CompoundTag();
        items.putInt("coal", 8);
        items.putInt("raw_gold", 3);
        var memory = new CompanionMemory();
        memory.load(storedChest(h, items)); // Altdaten ohne V
        var place = memory.chestWith(Items.COAL, h.getLevel(), chest(h), 2);
        h.assertTrue(place != null && place.items.containsKey("minecraft:coal"), "Alte Kohle nicht migriert");
        h.assertValueEqual(place.itemCount(Items.RAW_GOLD), 3, "Altes Gold verloren");
        var savedItems = memory.save().getListOrEmpty("Places").getCompoundOrEmpty(0).getCompoundOrEmpty("Items");
        h.assertTrue(!savedItems.contains("coal") && savedItems.contains("minecraft:coal"), "Erneut im Altformat gespeichert");
        h.succeed();
    }

    @GameTest
    public void legacyAliasesDoNotDoubleRememberedStock(GameTestHelper h) {
        CompoundTag items = new CompoundTag();
        items.putInt("coal", 8);
        items.putInt("minecraft:coal", 12);
        var memory = new CompanionMemory();
        memory.load(storedChest(h, items));
        var place = memory.all().getFirst();
        h.assertValueEqual(place.items.size(), 1, "Alias als eigener Itemtyp gemerkt");
        h.assertValueEqual(place.itemCount(Items.COAL), 12, "Alias-Mengen addiert oder gültigen Bestand verloren");
        h.succeed();
    }

    @GameTest
    public void invalidItemIdsAndAmountsAreIgnored(GameTestHelper h) {
        CompoundTag items = new CompoundTag();
        for (String id : new String[]{"", "Not An Item", "minecraft:", ":coal", "a:b:c", "x".repeat(129)})
            items.putInt(id, 4);
        items.putInt("minecraft:coal", Integer.MAX_VALUE);
        items.putInt("minecraft:raw_gold", -1);
        items.putInt("minecraft:diamond", 0);
        items.putString("minecraft:stick", "many");
        var memory = new CompanionMemory();
        memory.load(storedChest(h, items));
        var place = memory.all().getFirst();
        h.assertValueEqual(place.items.size(), 1, "Ungültiger Eintrag geladen");
        h.assertValueEqual(place.itemCount(Items.COAL), 4096, "Mengenbegrenzung umgangen");
        h.succeed();
    }

    @GameTest
    public void unsupportedMemoryVersionIsDiscarded(GameTestHelper h) {
        var memory = new CompanionMemory();
        memory.noteChest(h.getLevel(), chest(h), new SimpleContainer(new ItemStack(Items.COAL, 3)));
        CompoundTag future = memory.save();
        future.putInt("V", 99);
        memory.load(future);
        h.assertValueEqual(memory.size(), 0, "Unbekanntes Speicherformat interpretiert");
        h.succeed();
    }
}
