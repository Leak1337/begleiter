package dev.begleiter;

import net.fabricmc.fabric.api.gametest.v1.GameTest;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.world.level.block.Blocks;

/** Sammelplanung nutzt zuerst das Gedächtnis, begrenzt Wegprüfungen und meidet Fehlziele eine Minute. */
public class GatherPlanningGameTests {
    private static final String ARENA = "begleiter-gametest:bench";

    private static CompanionEntity companion(GameTestHelper h, int size) {
        for (int x = 0; x < size; x++) for (int z = 0; z < size; z++) h.setBlock(x, 0, z, Blocks.STONE);
        var c = h.spawn(CompanionMod.COMPANION, new BlockPos(2, 1, 2));
        c.setPersistenceRequired();
        c.stay(); // mit KI: nur dann steht er am Boden und die Vanilla-Wegsuche plant
        return c;
    }

    /** Sand oben auf einer 6 Blöcke hohen Glassäule: sichtbar, aber nicht erreichbar. */
    private static void pillar(GameTestHelper h, int x, int z) {
        for (int y = 1; y <= 6; y++) h.setBlock(x, y, z, Blocks.GLASS);
        h.setBlock(x, 7, z, Blocks.SAND);
    }

    @GameTest(structure = ARENA)
    public void unreachableTargetIsAvoidedForAMinute(GameTestHelper h) {
        var c = companion(h, 20);
        pillar(h, 8, 8);
        h.runAfterDelay(10, () -> {
        long before = CompanionGathering.pathChecks;
        var first = c.buildGather(s -> s.is(Blocks.SAND), 1, true);
        h.assertTrue(first.isEmpty(), "Unreachable sand planned");
        h.assertTrue(CompanionGathering.pathChecks - before >= 1, "No path check for the first attempt");
        before = CompanionGathering.pathChecks;
        var second = c.buildGather(s -> s.is(Blocks.SAND), 1, true);
        h.assertTrue(second.isEmpty(), "Unreachable sand planned on retry");
        h.assertValueEqual(CompanionGathering.pathChecks - before, 0L, "Known miss checked again");
        h.succeed();
        });
    }

    @GameTest(structure = ARENA)
    public void pathChecksAreCappedPerPlanning(GameTestHelper h) {
        var c = companion(h, 24);
        for (int i = 0; i < 20; i++) pillar(h, 4 + (i % 5) * 4, 4 + (i / 5) * 4);
        h.runAfterDelay(10, () -> {
            long before = CompanionGathering.pathChecks;
            c.buildGather(s -> s.is(Blocks.SAND), 5, true);
            long used = CompanionGathering.pathChecks - before;
            h.assertTrue(used >= 1 && used <= CompanionGathering.PATH_CHECKS, "Path checks not capped: " + used);
            h.succeed();
        });
    }

    @GameTest(structure = ARENA)
    public void rememberedOreIsPlannedWithoutAScan(GameTestHelper h) {
        var c = companion(h, 12);
        h.setBlock(5, 1, 2, Blocks.IRON_ORE);
        var at = h.absolutePos(new BlockPos(5, 1, 2));
        c.memory.remember(CompanionMemory.Kind.ORE, "eisen", h.getLevel(), at);
        h.runAfterDelay(10, () -> { // erst am Boden plant die Vanilla-Wegsuche
            long scans = CompanionGathering.scanReads;
            var plan = c.buildGather(s -> s.is(Blocks.IRON_ORE), 1);
            h.assertTrue(plan.size() == 1 && at.equals(plan.getFirst().pos()), "Remembered ore not planned: " + plan);
            h.assertValueEqual(CompanionGathering.scanReads - scans, 0L, "Area scanned although memory sufficed");
            h.succeed();
        });
    }

    @GameTest(structure = ARENA)
    public void withoutMemoryTheScanStillFindsTheNearestTarget(GameTestHelper h) {
        var c = companion(h, 12);
        h.setBlock(9, 1, 2, Blocks.IRON_ORE);
        h.setBlock(5, 1, 2, Blocks.IRON_ORE);
        h.runAfterDelay(10, () -> {
            long scans = CompanionGathering.scanReads;
            var plan = c.buildGather(s -> s.is(Blocks.IRON_ORE), 1);
            h.assertTrue(CompanionGathering.scanReads - scans > 0, "No scan without memory");
            h.assertTrue(plan.size() == 1 && h.absolutePos(new BlockPos(5, 1, 2)).equals(plan.getFirst().pos()), "Nearest ore not chosen: " + plan);
            h.succeed();
        });
    }
}
