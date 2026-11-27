package dev.begleiter;

import net.fabricmc.fabric.api.gametest.v1.GameTest;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.BarrelBlock;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.entity.ChestBlockEntity;

/** Sichtbares Hantieren: Kisten/Fässer öffnen sich echt, Items fliegen aus der Hand, Füttern bleibt korrekt gebucht. */
public class AnimationGameTests {
    private static CompanionEntity companion(GameTestHelper h) {
        for (int x = 0; x < 6; x++) for (int z = 0; z < 6; z++) h.setBlock(x, 0, z, Blocks.STONE);
        var c = h.spawn(CompanionMod.COMPANION, new BlockPos(1, 1, 1));
        c.stay();
        c.setNoAi(true);
        c.getInventory().clearContent();
        c.setItemSlot(EquipmentSlot.MAINHAND, ItemStack.EMPTY);
        return c;
    }

    @GameTest(maxTicks = 100)
    public void chestLidOpensOnUseAndClosesAgain(GameTestHelper h) {
        var c = companion(h);
        h.setBlock(2, 1, 1, Blocks.CHEST);
        BlockPos chest = h.absolutePos(new BlockPos(2, 1, 1));
        c.useContainer(h.getLevel(), chest);
        h.assertTrue(chest.equals(c.storage.openedContainerPos()), "Chest not marked open");
        h.runAfterDelay(5, () -> h.assertTrue(ChestBlockEntity.getOpenCount(h.getLevel(), chest) > 0, "Lid did not open"));
        h.runAfterDelay(CompanionStorage.CONTAINER_OPEN_TICKS + 15, () -> {
            h.assertTrue(c.storage.openedContainerPos() == null, "Still marked open");
            h.assertValueEqual(ChestBlockEntity.getOpenCount(h.getLevel(), chest), 0, "Lid stayed open");
            h.succeed();
        });
    }

    @GameTest
    public void barrelOpensAndClosesAndRemovalCloses(GameTestHelper h) {
        var c = companion(h);
        h.setBlock(2, 1, 1, Blocks.BARREL);
        BlockPos barrel = h.absolutePos(new BlockPos(2, 1, 1));
        c.useContainer(h.getLevel(), barrel);
        h.runAfterDelay(2, () -> {
            h.assertTrue(h.getLevel().getBlockState(barrel).getValue(BarrelBlock.OPEN), "Barrel did not open");
            c.discard();
        });
        h.runAfterDelay(10, () -> {
            h.assertTrue(!h.getLevel().getBlockState(barrel).getValue(BarrelBlock.OPEN), "Barrel stayed open after removal");
            h.succeed();
        });
    }

    @GameTest
    public void thrownItemsLeaveFromTheHand(GameTestHelper h) {
        var c = companion(h);
        var item = c.items.throwFromHand(h.getLevel(), new ItemStack(Items.DIRT, 5));
        h.assertTrue(item != null, "Throw rejected");
        h.assertTrue(item.getY() > c.getY() + 1.0, "Item not thrown from hand height: " + (item.getY() - c.getY()));
        h.assertTrue(item.getDeltaMovement().horizontalDistanceSqr() > 0.01, "Item has no throw motion");
        h.assertValueEqual(item.getItem().getCount(), 5, "Thrown stack size");
        h.succeed();
    }

    @GameTest
    public void feedingStillBooksOneFoodAndBreeds(GameTestHelper h) {
        var c = companion(h);
        var cow = h.spawn(net.minecraft.world.entity.EntityTypes.COW, new BlockPos(3, 1, 3));
        c.giveToInventory(new ItemStack(Items.WHEAT, 3));
        CompanionJobs.feed(c, cow);
        h.assertValueEqual(CompanionCraft.count(c, "wheat"), 2, "Wheat booked");
        h.assertTrue(cow.isInLove(), "Cow not in love");
        h.succeed();
    }
}
