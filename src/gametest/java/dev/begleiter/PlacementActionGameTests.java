package dev.begleiter;

import net.fabricmc.fabric.api.gametest.v1.GameTest;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.material.Fluids;

/** Abgelehnte Setz-/Flüssigkeitsaktionen buchen nichts; eine erfolgreiche genau einmal. */
public class PlacementActionGameTests {
    private enum Fault { NONE, DENIED, UNCHANGED, EXCEPTION }

    private static final class TestCompanion extends CompanionEntity {
        private final Fault fault;

        TestCompanion(ServerLevel level, Fault fault) {
            super(CompanionMod.COMPANION, level);
            this.fault = fault;
        }

        @Override protected boolean setWorldBlock(ServerLevel level, BlockPos pos, BlockState state, int flags) {
            return switch (fault) {
                case NONE -> super.setWorldBlock(level, pos, state, flags);
                case DENIED -> false;
                case UNCHANGED -> true; // meldet Erfolg, ändert aber nichts
                case EXCEPTION -> throw new IllegalStateException("simulierter Weltfehler beim Setzen");
            };
        }
    }

    private static TestCompanion companion(GameTestHelper h, Fault fault) {
        var c = new TestCompanion(h.getLevel(), fault);
        BlockPos pos = h.absolutePos(new BlockPos(2, 1, 2));
        c.snapTo(pos.getX() + .5, pos.getY(), pos.getZ() + .5, 0, 0);
        c.stay();
        return c; // nicht gespawnt: keine parallelen KI-Ticks
    }

    private static BlockPos water(GameTestHelper h) {
        h.setBlock(4, 0, 2, Blocks.STONE);
        h.setBlock(4, 1, 2, Blocks.WATER);
        return h.absolutePos(new BlockPos(4, 1, 2));
    }

    @GameTest
    public void fillingABucketBooksOnlyAConfirmedPickup(GameTestHelper h) {
        for (Fault fault : Fault.values()) {
            var c = companion(h, fault);
            BlockPos p = water(h);
            c.getInventory().setItem(0, new ItemStack(Items.BUCKET));
            boolean filled = c.portals.fillBucket(h.getLevel(), p);
            boolean ok = fault == Fault.NONE;
            h.assertTrue(filled == ok, fault + ": fillBucket returned " + filled);
            h.assertValueEqual(CompanionCraft.count(c, "water_bucket"), ok ? 1 : 0, fault + ": water buckets");
            h.assertValueEqual(CompanionCraft.count(c, "bucket"), ok ? 0 : 1, fault + ": empty buckets");
            h.assertTrue(h.getLevel().getFluidState(p).isSourceOfType(Fluids.WATER) != ok, fault + ": water source state");
        }
        h.succeed();
    }

    @GameTest
    public void pouringOnLavaMakesObsidianOnlyWhenConfirmed(GameTestHelper h) {
        for (Fault fault : Fault.values()) {
            var c = companion(h, fault);
            h.setBlock(5, 0, 5, Blocks.STONE);
            h.setBlock(5, 1, 5, Blocks.LAVA);
            BlockPos lava = h.absolutePos(new BlockPos(5, 1, 5));
            c.getInventory().setItem(0, new ItemStack(Items.WATER_BUCKET));
            var made = c.portals.pourWater(h.getLevel(), lava);
            boolean ok = fault == Fault.NONE;
            h.assertTrue(made.contains(lava) == ok, fault + ": obsidian reported " + made);
            h.assertTrue(h.getLevel().getBlockState(lava).is(Blocks.OBSIDIAN) == ok, fault + ": world block");
            h.assertValueEqual(CompanionCraft.count(c, "water_bucket"), 1, fault + ": water bucket must stay");
        }
        h.succeed();
    }

    @GameTest
    public void placeConfirmedSurvivesAWorldException(GameTestHelper h) {
        var c = companion(h, Fault.EXCEPTION);
        BlockPos p = h.absolutePos(new BlockPos(3, 1, 3));
        h.assertTrue(!c.placeConfirmed(h.getLevel(), p, Blocks.STONE.defaultBlockState()), "Exception counted as placed");
        h.assertTrue(h.getLevel().getBlockState(p).isAir(), "World changed despite the exception");
        h.succeed();
    }
}
