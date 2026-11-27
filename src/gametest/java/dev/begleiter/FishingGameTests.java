package dev.begleiter;

import net.fabricmc.fabric.api.gametest.v1.GameTest;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.util.RandomSource;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Blocks;

/**
 * Angeln (simuliert): feste Zufallsquelle liefert Beute und Verschleiß; kein Wasser, geschlossenes Wasser und
 * Unterbrechungen geben nichts. Ohne Wartezeit: die Tests zählen die Ticks selbst hoch.
 */
public class FishingGameTests {
    /** Steinblock 8×8, darin ein Teich 5×5, zwei tief (x 2–6, z 1–5, y 1–2); Ufer oben auf y 2. */
    private static void pond(GameTestHelper h, boolean open) {
        for (int x = 0; x < 8; x++)
            for (int z = 0; z < 8; z++)
                for (int y = 0; y <= 2; y++) {
                    boolean pool = open && x >= 2 && x <= 6 && z >= 1 && z <= 5 && y >= 1;
                    h.setBlock(x, y, z, pool ? Blocks.WATER : Blocks.STONE);
                }
        if (!open) h.setBlock(4, 2, 3, Blocks.WATER); // einzelne Pfütze
    }

    private static final BlockPos STAND = new BlockPos(1, 3, 3), WATER = new BlockPos(4, 2, 3);

    private static CompanionEntity angler(GameTestHelper h) {
        var c = h.spawn(CompanionMod.COMPANION, STAND);
        c.setNoAi(true);
        c.stay();
        c.getInventory().clearContent();
        BlockPos at = h.absolutePos(STAND);
        c.snapTo(at.getX() + .5, at.getY(), at.getZ() + .5, 0, 0);
        c.getInventory().setItem(0, new ItemStack(Items.FISHING_ROD));
        return c;
    }

    private static CompanionFishing.Result untilDone(GameTestHelper h, CompanionFishing.Session s, int from) {
        CompanionFishing.Result r = null;
        for (int now = from; now <= from + 800; now++) {
            r = s.tick(h.getLevel(), now);
            if (r.outcome() != CompanionFishing.Outcome.WAITING) return r;
        }
        return r;
    }

    private static int items(CompanionEntity c) {
        int n = 0;
        for (int i = 0; i < c.getInventory().getContainerSize(); i++) if (!c.getInventory().getItem(i).isEmpty()) n++;
        return n;
    }

    @GameTest
    public void fixedRandomCatchesAndWearsTheRod(GameTestHelper h) {
        pond(h, true);
        var c = angler(h);
        BlockPos water = h.absolutePos(WATER);
        h.assertTrue(CompanionFishing.validWater(h.getLevel(), water), "pond surface not fishable");
        h.assertTrue(CompanionFishing.openWater(h.getLevel(), water), "5x5 pond two deep is not open water");
        var spot = CompanionFishing.findSpot(c, h.getLevel(), 8);
        h.assertTrue(spot != null && spot.openWater(), "no open fishing spot found: " + spot);
        h.assertTrue(CompanionFishing.validWater(h.getLevel(), spot.water()), "spot water invalid");
        var s = new CompanionFishing.Session(c, h.absolutePos(STAND), water, RandomSource.create(42));
        var cast = s.tick(h.getLevel(), 0);
        h.assertTrue(cast.outcome() == CompanionFishing.Outcome.WAITING && s.casting(), "no cast: " + cast);
        var r = untilDone(h, s, 1);
        h.assertTrue(r.outcome() == CompanionFishing.Outcome.CAUGHT, "nothing caught within the vanilla wait: " + r);
        h.assertTrue(!r.loot().isEmpty(), "empty catch");
        ItemStack first = r.loot().getFirst();
        h.assertTrue(CompanionCraft.countAll(c, BuiltInRegistries.ITEM.getKey(first.getItem()).toString()) >= first.getCount(), "catch not in the inventory: " + first);
        ItemStack rod = c.getInventory().getItem(CompanionFishing.rodSlot(c));
        h.assertValueEqual(rod.getDamageValue(), 1, "rod wear per catch");
        h.assertValueEqual(s.caught(), 1, "catch count");
        // Gleiche Quelle, gleiche Beute
        var other = angler(h);
        var again = untilDone(h, new CompanionFishing.Session(other, h.absolutePos(STAND), water, RandomSource.create(42)), 0);
        h.assertTrue(again.outcome() == CompanionFishing.Outcome.CAUGHT
                && ItemStack.isSameItem(again.loot().getFirst(), first), "same seed gave a different catch: " + again.loot());
        h.succeed();
    }

    @GameTest
    public void interruptionAndLostWaterGiveNothing(GameTestHelper h) {
        pond(h, true);
        var c = angler(h);
        BlockPos stand = h.absolutePos(STAND), water = h.absolutePos(WATER);
        var s = new CompanionFishing.Session(c, stand, water, RandomSource.create(7));
        s.tick(h.getLevel(), 0);
        c.snapTo(stand.getX() + 4.5, stand.getY(), stand.getZ() + .5, 0, 0);
        var moved = s.tick(h.getLevel(), 5);
        h.assertTrue(moved.outcome() == CompanionFishing.Outcome.INTERRUPTED && moved.loot().isEmpty(), "walking away kept the cast: " + moved);
        for (int now = 6; now < 800; now++)
            h.assertTrue(s.tick(h.getLevel(), now).outcome() != CompanionFishing.Outcome.CAUGHT, "caught while away from the shore");
        c.snapTo(stand.getX() + .5, stand.getY(), stand.getZ() + .5, 0, 0);
        h.assertTrue(s.tick(h.getLevel(), 800).outcome() == CompanionFishing.Outcome.WAITING && s.casting(), "no new cast after returning");
        h.setBlock(WATER, Blocks.STONE);
        var gone = s.tick(h.getLevel(), 801);
        h.assertTrue(gone.outcome() == CompanionFishing.Outcome.INVALID_WATER && gone.loot().isEmpty(), "fished without water: " + gone);
        h.assertValueEqual(items(c), 1, "items appeared without a catch");
        h.assertValueEqual(c.getInventory().getItem(CompanionFishing.rodSlot(c)).getDamageValue(), 0, "rod worn without a catch");
        c.getInventory().clearContent();
        h.setBlock(WATER, Blocks.WATER);
        h.assertTrue(s.tick(h.getLevel(), 802).outcome() == CompanionFishing.Outcome.NO_ROD, "fished without a rod");
        h.succeed();
    }

    @GameTest
    public void puddleIsFishableButNotOpenWater(GameTestHelper h) {
        pond(h, false);
        var c = angler(h);
        BlockPos water = h.absolutePos(WATER);
        h.assertTrue(CompanionFishing.validWater(h.getLevel(), water), "puddle surface rejected");
        h.assertTrue(!CompanionFishing.openWater(h.getLevel(), water), "single block counted as open water");
        h.assertTrue(!CompanionFishing.validWater(h.getLevel(), water.below()), "stone counted as water");
        var spot = CompanionFishing.findSpot(c, h.getLevel(), 8);
        h.assertTrue(spot != null && !spot.openWater() && spot.water().equals(water), "puddle spot: " + spot);
        h.succeed();
    }

    @GameTest
    public void rodIsCraftedFromStringAndSticks(GameTestHelper h) {
        pond(h, true);
        var c = angler(h);
        c.getInventory().clearContent();
        h.assertTrue(!CompanionFishing.hasOrCanMakeRod(c), "rod without materials");
        c.giveToInventory(new ItemStack(Items.STRING, 2));
        c.giveToInventory(new ItemStack(Items.STICK, 3));
        h.assertTrue(CompanionFishing.hasOrCanMakeRod(c), "materials for a rod not recognised");
        h.assertTrue(CompanionCraft.craft(c, "fishing_rod", 1) && CompanionFishing.rodSlot(c) >= 0, "rod not crafted");
        h.succeed();
    }
}
