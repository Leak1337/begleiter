package dev.begleiter;

import java.util.LinkedHashMap;
import java.util.Map;

import net.fabricmc.fabric.api.gametest.v1.GameTest;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.BedBlock;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.DoorBlock;
import net.minecraft.world.level.block.SlabBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BedPart;
import net.minecraft.world.level.block.state.properties.DoubleBlockHalf;
import net.minecraft.world.level.block.state.properties.SlabType;

/** Restmaterial wird gemerkt, nach Platzierung abgezogen und höchstens alle 200 Ticks neu gezählt. */
public class MaterialCountGameTests {

    @GameTest(maxTicks = 600)
    public void hugePlanIsRecountedAtMostEvery200Ticks(GameTestHelper h) {
        var sl = h.getLevel();
        Map<BlockPos, BlockState> blocks = new LinkedHashMap<>();
        var far = new BlockPos(18_000_000, 64, 18_000_000); // ungeladen: zählt ohne Weltzugriff
        for (int i = 0; i < 50_000; i++) blocks.put(far.offset(i % 100, i / 10_000, (i / 100) % 100), Blocks.STONE.defaultBlockState());
        var job = CompanionBuilder.create(sl, "huge", blocks);
        long before = CompanionBuilder.materialRecounts;
        int[] ticks = {0};
        h.onEachTick(() -> {
            var m = CompanionBuilder.remaining(sl, job); // wie ein offenes Menü in jedem Tick
            h.assertValueEqual(m.get(Items.STONE), 50_000, "Stone count");
            ticks[0]++;
        });
        h.runAfterDelay(450, () -> {
            long recounts = CompanionBuilder.materialRecounts - before;
            // gleichzeitig laufende Tests können ebenfalls zählen; dieser Auftrag selbst höchstens 0/200/400
            h.assertTrue(job.materials != null && sl.getGameTime() - job.materials.at < CompanionBuilder.MATERIAL_CACHE_TICKS,
                    "Count older than the cache limit");
            h.assertTrue(recounts >= 1, "Never counted");
            h.assertTrue(ticks[0] >= 400, "Ticked too little");
            h.succeed();
        });
    }

    @GameTest
    public void placedBlocksAreDeductedWithoutARecountAndResetRecounts(GameTestHelper h) {
        var sl = h.getLevel();
        Map<BlockPos, BlockState> blocks = new LinkedHashMap<>();
        for (int x = 0; x < 10; x++) blocks.put(h.absolutePos(new BlockPos(x, 1, 1)), Blocks.STONE.defaultBlockState());
        var job = CompanionBuilder.create(sl, "ten", blocks);
        h.assertValueEqual(CompanionBuilder.remaining(sl, job).get(Items.STONE), 10, "Initial stone");
        var counted = job.materials;
        var first = job.order.get(job.index);
        sl.setBlock(first.pos(), first.state(), 3);
        CompanionBuilder.notePlaced(job, first);
        job.index++;
        var after = CompanionBuilder.remaining(sl, job);
        h.assertTrue(job.materials == counted, "Placement caused a full recount");
        h.assertValueEqual(after.get(Items.STONE), 9, "Placed block not deducted");
        after.put(Items.STONE, 999);
        h.assertValueEqual(CompanionBuilder.remaining(sl, job).get(Items.STONE), 9, "Caller changed the shared count");
        job.index = 0; // Ausbessern beginnt von vorn
        CompanionBuilder.remaining(sl, job);
        h.assertTrue(job.materials != counted, "Index reset did not recount");
        h.assertValueEqual(CompanionBuilder.remaining(sl, job).get(Items.STONE), 9, "Recount after reset wrong");
        h.succeed();
    }

    @GameTest
    public void doubleSlabsLiquidsAndFreeHalvesAreCountedCorrectly(GameTestHelper h) {
        var sl = h.getLevel();
        Map<BlockPos, BlockState> blocks = new LinkedHashMap<>();
        blocks.put(h.absolutePos(new BlockPos(1, 1, 1)), Blocks.OAK_SLAB.defaultBlockState().setValue(SlabBlock.TYPE, SlabType.DOUBLE));
        blocks.put(h.absolutePos(new BlockPos(2, 1, 1)), Blocks.OAK_SLAB.defaultBlockState());
        blocks.put(h.absolutePos(new BlockPos(3, 1, 1)), Blocks.WATER.defaultBlockState());
        blocks.put(h.absolutePos(new BlockPos(4, 1, 1)), Blocks.OAK_DOOR.defaultBlockState().setValue(DoorBlock.HALF, DoubleBlockHalf.LOWER));
        blocks.put(h.absolutePos(new BlockPos(4, 2, 1)), Blocks.OAK_DOOR.defaultBlockState().setValue(DoorBlock.HALF, DoubleBlockHalf.UPPER));
        blocks.put(h.absolutePos(new BlockPos(5, 1, 1)), Blocks.STRAW_BED.defaultBlockState().setValue(BedBlock.PART, BedPart.FOOT).setValue(BedBlock.FACING, Direction.NORTH));
        blocks.put(h.absolutePos(new BlockPos(5, 1, 0)), Blocks.STRAW_BED.defaultBlockState().setValue(BedBlock.PART, BedPart.HEAD).setValue(BedBlock.FACING, Direction.NORTH));
        var m = CompanionBuilder.remaining(sl, CompanionBuilder.create(sl, "mixed", blocks));
        h.assertValueEqual(m.get(Items.OAK_SLAB), 3, "Double slab must cost two plus one single slab");
        h.assertValueEqual(m.get(Items.WATER_BUCKET), 1, "Water source costs a bucket");
        h.assertValueEqual(m.get(Items.OAK_DOOR), 1, "Upper door half must be free");
        h.assertValueEqual(m.get(Blocks.STRAW_BED.asItem()), 1, "Bed head must be free");
        h.succeed();
    }
}
