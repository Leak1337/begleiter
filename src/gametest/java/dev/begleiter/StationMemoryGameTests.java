package dev.begleiter;

import net.fabricmc.fabric.api.gametest.v1.GameTest;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.world.level.block.Blocks;

/** Stationen werden gemerkt und wiedergefunden, ohne erneut die ganze Umgebung abzusuchen. */
public class StationMemoryGameTests {
    private static CompanionEntity companion(GameTestHelper h) {
        for (int x = 0; x < 10; x++) for (int z = 0; z < 10; z++) h.setBlock(x, 0, z, Blocks.STONE);
        var c = h.spawn(CompanionMod.COMPANION, new BlockPos(2, 1, 2));
        c.setNoAi(true);
        c.stay();
        return c;
    }

    @GameTest
    public void rememberedAnvilIsFoundAgainWithoutAScan(GameTestHelper h) {
        var c = companion(h);
        h.setBlock(6, 1, 6, Blocks.CHIPPED_ANVIL);
        var at = h.absolutePos(new BlockPos(6, 1, 6));
        long before = CompanionStations.scanReads;
        h.assertTrue(at.equals(CompanionJobs.findAnvil(c, 24)), "Chipped anvil not found");
        long firstScan = CompanionStations.scanReads - before;
        h.assertTrue(firstScan > 0, "First search did not scan");
        var box = (long) (2 * 24 + 1) * (2 * 24 + 1) * 11;
        h.assertTrue(firstScan <= box, "All anvil states must share one scan, read " + firstScan);
        h.assertTrue(c.memory.nearest(CompanionMemory.Kind.STATION, "minecraft:chipped_anvil", h.getLevel(), at, 2) != null,
                "Found station not remembered");
        before = CompanionStations.scanReads;
        h.assertTrue(at.equals(CompanionJobs.findAnvil(c, 24)), "Remembered anvil not used");
        h.assertValueEqual(CompanionStations.scanReads - before, 0L, "Remembered station searched again");
        h.succeed();
    }

    @GameTest
    public void removedStationIsForgottenAndNotUsed(GameTestHelper h) {
        var c = companion(h);
        h.setBlock(5, 1, 5, Blocks.ANVIL);
        var at = h.absolutePos(new BlockPos(5, 1, 5));
        h.assertTrue(at.equals(CompanionJobs.findAnvil(c, 24)), "Anvil not found");
        h.setBlock(5, 1, 5, Blocks.AIR);
        // Nur der entfernte Amboss zählt: Nachbartests können eigene Ambosse in Reichweite haben.
        h.assertTrue(!at.equals(CompanionJobs.findAnvil(c, 24)), "Removed anvil still used");
        h.assertTrue(c.memory.nearest(CompanionMemory.Kind.STATION, "minecraft:anvil", h.getLevel(), at, 2) == null,
                "Removed anvil still remembered");
        h.succeed();
    }

    @GameTest
    public void unreachableStationIsSkippedForAnotherOne(GameTestHelper h) {
        var c = companion(h);
        h.setBlock(4, 1, 4, Blocks.FURNACE);
        h.setBlock(8, 1, 8, Blocks.FURNACE);
        var near = h.absolutePos(new BlockPos(4, 1, 4));
        var far = h.absolutePos(new BlockPos(8, 1, 8));
        h.assertTrue(near.equals(c.findStation("furnace", 24)), "Nearest furnace not found");
        c.markStationUnreachable(near);
        h.assertTrue(far.equals(c.findStation("furnace", 24)), "Unreachable furnace not skipped");
        h.succeed();
    }

    @GameTest
    public void stationOutsideTheSearchBoxIsNotTakenFromMemory(GameTestHelper h) {
        var c = companion(h);
        h.setBlock(9, 1, 9, Blocks.SMITHING_TABLE);
        var at = h.absolutePos(new BlockPos(9, 1, 9));
        h.assertTrue(at.equals(c.findStation("smithing_table", 24)), "Smithing table not found");
        h.assertTrue(c.findStation("smithing_table", 3) == null, "Remembered station outside the radius used");
        h.succeed();
    }
}
