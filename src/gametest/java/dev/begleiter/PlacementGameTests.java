package dev.begleiter;

import java.util.function.BiPredicate;
import net.fabricmc.fabric.api.gametest.v1.GameTest;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.DoorBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.DoubleBlockHalf;

/**
 * Release-Blocker B3: Setzen von Blöcken und Flüssigkeiten bucht Material erst nach bestätigter Weltänderung.
 * Die einzelne Weltänderung wird gezielt abgelehnt oder "unverändert" gemeldet; nichts darf verschwinden oder
 * doppelt entstehen.
 */
public class PlacementGameTests {
    private enum Fault { NONE, DENIED, UNCHANGED }

    private static final class TestCompanion extends CompanionEntity {
        private final Fault fault;
        private final BiPredicate<BlockPos, BlockState> applies;
        private int attempts;

        TestCompanion(ServerLevel level, Fault fault, BiPredicate<BlockPos, BlockState> applies) {
            super(CompanionMod.COMPANION, level);
            this.fault = fault;
            this.applies = applies;
        }

        @Override protected boolean setWorldBlock(ServerLevel level, BlockPos pos, BlockState st, int flags) {
            attempts++;
            if (!applies.test(pos, st)) return super.setWorldBlock(level, pos, st, flags);
            return switch (fault) {
                case NONE -> super.setWorldBlock(level, pos, st, flags);
                case DENIED -> false;
                case UNCHANGED -> true; // meldet Erfolg, die Welt bleibt aber, wie sie war
            };
        }
    }

    private static TestCompanion companion(GameTestHelper h, Fault fault, BiPredicate<BlockPos, BlockState> applies) {
        for (int x = 0; x < 8; x++) for (int z = 0; z < 8; z++) h.setBlock(x, 0, z, Blocks.STONE);
        var c = new TestCompanion(h.getLevel(), fault, applies);
        BlockPos pos = h.absolutePos(new BlockPos(2, 1, 2));
        c.snapTo(pos.getX() + .5, pos.getY(), pos.getZ() + .5, 0, 0);
        c.stay();
        return c; // nicht gespawnt: keine KI-Ticks im synchronen Test
    }

    private static TestCompanion companion(GameTestHelper h, Fault fault) {
        return companion(h, fault, (p, s) -> true);
    }

    private static int count(CompanionEntity c, net.minecraft.world.item.Item item) {
        int n = 0;
        for (int i = 0; i < c.getInventory().getContainerSize(); i++)
            if (c.getInventory().getItem(i).is(item)) n += c.getInventory().getItem(i).getCount();
        return n;
    }

    private static BlockPos water(GameTestHelper h) {
        h.setBlock(5, 1, 5, Blocks.WATER);
        return h.absolutePos(new BlockPos(5, 1, 5));
    }

    @GameTest
    public void eimerFuellenAbgelehntBehaeltEimerUndWasser(GameTestHelper h) {
        for (Fault f : new Fault[] {Fault.DENIED, Fault.UNCHANGED}) {
            var c = companion(h, f);
            BlockPos w = water(h);
            c.giveToInventory(new ItemStack(Items.BUCKET));
            h.assertTrue(!c.fillBucket(h.getLevel(), w), f + ": Füllen trotz Ablehnung gemeldet");
            h.assertValueEqual(count(c, Items.BUCKET), 1, f + ": leerer Eimer");
            h.assertValueEqual(count(c, Items.WATER_BUCKET), 0, f + ": Wasser aus dem Nichts");
            h.assertTrue(h.getLevel().getFluidState(w).isSource(), f + ": Quelle verschwunden");
        }
        h.succeed();
    }

    @GameTest
    public void eimerFuellenErfolgBuchtGenauEinmal(GameTestHelper h) {
        var c = companion(h, Fault.NONE);
        BlockPos w = water(h);
        c.giveToInventory(new ItemStack(Items.BUCKET));
        h.assertTrue(c.fillBucket(h.getLevel(), w), "Füllen fehlgeschlagen");
        h.assertValueEqual(count(c, Items.BUCKET), 0, "leerer Eimer");
        h.assertValueEqual(count(c, Items.WATER_BUCKET), 1, "Wassereimer");
        h.assertTrue(h.getLevel().getBlockState(w).isAir(), "Quelle noch da");
        h.succeed();
    }

    @GameTest
    public void wasserAufLavaAbgelehntMachtKeinObsidian(GameTestHelper h) {
        var c = companion(h, Fault.DENIED);
        h.setBlock(5, 1, 5, Blocks.LAVA);
        BlockPos lava = h.absolutePos(new BlockPos(5, 1, 5));
        c.giveToInventory(new ItemStack(Items.WATER_BUCKET));
        h.assertTrue(c.pourWater(h.getLevel(), lava).isEmpty(), "Obsidian gezählt, obwohl nichts gesetzt wurde");
        h.assertTrue(h.getLevel().getFluidState(lava).isSource(), "Lava verändert");
        h.succeed();
    }

    @GameTest
    public void tuerOhneObereHaelfteBleibtImInventar(GameTestHelper h) {
        var c = companion(h, Fault.DENIED,
                (p, s) -> s.getBlock() instanceof DoorBlock && s.getValue(DoorBlock.HALF) == DoubleBlockHalf.UPPER);
        c.giveToInventory(new ItemStack(Items.OAK_DOOR));
        BlockPos lower = h.absolutePos(new BlockPos(5, 1, 5));
        int slot = c.findItem(Items.OAK_DOOR);
        h.assertTrue(!CompanionDoors.place(h.getLevel(), c, lower, Direction.NORTH, slot), "Tür als gesetzt gemeldet");
        h.assertValueEqual(count(c, Items.OAK_DOOR), 1, "Tür verbraucht");
        h.assertTrue(h.getLevel().getBlockState(lower).isAir(), "Halbe Tür stehen geblieben");
        h.assertTrue(h.getLevel().getBlockState(lower.above()).isAir(), "Obere Hälfte ohne untere");
        h.succeed();
    }

    @GameTest
    public void tuerErfolgBuchtGenauEinmal(GameTestHelper h) {
        var c = companion(h, Fault.NONE);
        c.giveToInventory(new ItemStack(Items.OAK_DOOR, 2));
        BlockPos lower = h.absolutePos(new BlockPos(5, 1, 5));
        h.assertTrue(CompanionDoors.place(h.getLevel(), c, lower, Direction.NORTH, c.findItem(Items.OAK_DOOR)), "Tür nicht gesetzt");
        h.assertValueEqual(count(c, Items.OAK_DOOR), 1, "Türen im Inventar");
        h.assertTrue(h.getLevel().getBlockState(lower.above()).is(Blocks.OAK_DOOR), "Obere Hälfte fehlt");
        h.succeed();
    }

    @GameTest
    public void saatAbgelehntBleibtImInventar(GameTestHelper h) {
        for (Fault f : new Fault[] {Fault.DENIED, Fault.UNCHANGED}) {
            var c = companion(h, f);
            h.setBlock(5, 0, 5, Blocks.FARMLAND);
            h.setBlock(5, 1, 5, Blocks.AIR);
            BlockPos p = h.absolutePos(new BlockPos(5, 1, 5));
            c.giveToInventory(new ItemStack(Items.WHEAT_SEEDS, 3));
            h.assertValueEqual(CompanionJobs.workFarmSpot(h.getLevel(), c, p), 0, f + ": Pflanzen gemeldet");
            h.assertValueEqual(count(c, Items.WHEAT_SEEDS), 3, f + ": Saat verbraucht");
            h.assertTrue(h.getLevel().getBlockState(p).isAir(), f + ": Pflanze entstanden");
        }
        h.succeed();
    }

    @GameTest
    public void unveraendertGemeldetZaehltNichtAlsGesetzt(GameTestHelper h) {
        var c = companion(h, Fault.UNCHANGED);
        BlockPos p = h.absolutePos(new BlockPos(5, 1, 5));
        h.assertTrue(!c.placeConfirmed(h.getLevel(), p, Blocks.COBBLESTONE.defaultBlockState()),
                "Gemeldeter Erfolg ohne Weltänderung als gesetzt gezählt");
        h.assertValueEqual(c.attempts, 1, "Weltaktionen");
        h.succeed();
    }
}
