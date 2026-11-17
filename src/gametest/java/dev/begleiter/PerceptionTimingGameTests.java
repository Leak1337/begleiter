package dev.begleiter;

import net.fabricmc.fabric.api.gametest.v1.GameTest;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.world.level.block.Blocks;

import dev.begleiter.CompanionMemory.Kind;

/** Prüft echte Scanwirkungen über den normalen AI-Einstieg, ohne laufende Entity-Ticks. */
public class PerceptionTimingGameTests {
    private static CompanionEntity companion(GameTestHelper h, int id) {
        var c = new CompanionEntity(CompanionMod.COMPANION, h.getLevel());
        c.setId(id);
        c.setPos(h.absoluteVec(new net.minecraft.world.phys.Vec3(2.5, 25, 2.5)));
        c.setNoAi(true);
        c.stay();
        return c;
    }

    @GameTest
    public void perception_scans_run_on_different_ticks(GameTestHelper h) {
        h.setBlock(2, 15, 3, Blocks.CHEST);
        h.setBlock(3, 1, 3, Blocks.DIAMOND_ORE);
        var bots = new CompanionEntity[] { companion(h, 1000), companion(h, 1001) };
        var chests = new CompanionMemory.Place[2];
        int[] scanned = new int[2], sensed = new int[2];
        for (int i = 0; i < bots.length; i++) {
            chests[i] = bots[i].memory.remember(Kind.CHEST, "", h.getLevel(), h.absolutePos(new BlockPos(2, 15, 3)));
            chests[i].seen = -1;
        }
        for (int tick = 1; tick <= 5; tick++) {
            for (int i = 0; i < bots.length; i++) {
                bots[i].tickCount = tick;
                bots[i].customServerAiStep(h.getLevel());
                if (scanned[i] == 0 && chests[i].seen >= 0) scanned[i] = tick;
                if (sensed[i] == 0 && bots[i].memory.nearest(Kind.ORE, Resources.DIAMOND.key(),
                        h.getLevel(), h.absolutePos(new BlockPos(3, 1, 3)), 1) != null) sensed[i] = tick;
            }
        }
        h.assertTrue(scanned[0] > 0 && scanned[1] > 0 && scanned[0] != scanned[1],
                "Umgebungsscans müssen beide innerhalb von fünf Ticks und zeitversetzt laufen");
        h.assertTrue(sensed[0] > 0 && sensed[1] > 0 && sensed[0] != sensed[1],
                "Erzscans müssen beide innerhalb von zwei Ticks und zeitversetzt laufen");
        h.assertTrue(sensed[0] <= 2 && sensed[1] <= 2, "Erzscan wurde verzögert");
        h.succeed();
    }

    @GameTest
    public void perception_verification_keeps_its_frequency(GameTestHelper h) {
        var bots = new CompanionEntity[] { companion(h, 1000), companion(h, 1001) };
        var missing = h.absolutePos(new BlockPos(3, 25, 3));
        h.getLevel().setBlock(missing, Blocks.AIR.defaultBlockState(), 3);
        var places = new CompanionMemory.Place[2];
        int[] verified = new int[2];
        for (int i = 0; i < bots.length; i++)
            places[i] = bots[i].memory.remember(Kind.CHEST, "", h.getLevel(), missing);
        for (int tick = 1; tick <= 100; tick++) {
            for (int i = 0; i < bots.length; i++) {
                bots[i].tickCount = tick;
                bots[i].customServerAiStep(h.getLevel());
                if (verified[i] == 0 && !bots[i].memory.places().contains(places[i])) verified[i] = tick;
            }
        }
        h.assertTrue(verified[0] > 0 && verified[1] > 0 && verified[0] != verified[1],
                "Veraltete Kisten müssen innerhalb von 100 Ticks zeitversetzt entfernt werden");
        h.succeed();
    }
}
