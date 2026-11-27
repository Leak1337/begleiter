package dev.begleiter;

import dev.begleiter.world.CompanionChunkLoader;
import dev.begleiter.world.SafeLevel;
import net.fabricmc.fabric.api.gametest.v1.GameTest;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.chunk.status.ChunkStatus;

/**
 * Lesezugriffe laden nie einen Chunk nach; der Begleiter lädt bei Arbeit selbst Chunks über ein Ticket.
 * Jeder Test nutzt eigene, weit entfernte Chunks, die vorher sicher nicht geladen sind.
 */
public class SafeWorldGameTests {
    private static boolean loaded(ServerLevel sl, int cx, int cz) {
        return sl.getChunkSource().getChunkNow(cx, cz) != null;
    }

    @GameTest
    public void safeReadsOnAnUnloadedChunkStayUnknownAndLoadNothing(GameTestHelper h) {
        var sl = h.getLevel();
        var far = new BlockPos(20_000_008, 70, -20_000_008);
        h.assertTrue(!loaded(sl, far.getX() >> 4, far.getZ() >> 4), "Fixture chunk already loaded");
        h.assertTrue(SafeLevel.state(sl, far) == null && SafeLevel.fluid(sl, far) == null, "Unloaded block reported as known");
        h.assertTrue(!SafeLevel.loaded(sl, far) && !SafeLevel.is(sl, far, Blocks.AIR) && !SafeLevel.test(sl, far, s -> true),
                "Unknown block counted as a match");
        h.assertTrue(CompanionExpedition.nearestSpawner(sl, far, 16) == null, "Search found something in unloaded chunks");
        h.assertTrue(CompanionGathering.deepWater(sl, far), "Unknown water depth not treated conservatively");
        h.assertTrue(!loaded(sl, far.getX() >> 4, far.getZ() >> 4), "A read loaded the chunk");
        h.succeed();
    }

    @GameTest
    public void searchesAtTheEdgeOfLoadedChunksUseOnlyTheLoadedSide(GameTestHelper h) {
        var sl = h.getLevel();
        int cx = 1_250_100, cz = -1_250_100;
        h.assertTrue(!loaded(sl, cx + 1, cz), "Fixture neighbour already loaded");
        sl.getChunk(cx, cz, ChunkStatus.FULL, true); // nur dieser Chunk ist danach voll geladen
        h.assertTrue(loaded(sl, cx, cz) && !loaded(sl, cx + 1, cz), "Fixture: neighbour became fully loaded");
        int y = 120;
        var edge = new BlockPos((cx << 4) + 15, y, (cz << 4) + 8); // östlicher Rand, Nachbar dahinter ungeladen
        var spawner = edge.west();
        // ohne Nachbar-Updates setzen, sonst würde schon das Setzen den Nachbarn anfassen
        sl.setBlock(edge, Blocks.STONE.defaultBlockState(), Block.UPDATE_CLIENTS | Block.UPDATE_KNOWN_SHAPE);
        sl.setBlock(spawner, Blocks.SPAWNER.defaultBlockState(), Block.UPDATE_CLIENTS | Block.UPDATE_KNOWN_SHAPE);
        sl.setBlock(spawner.below(), Blocks.NETHER_BRICKS.defaultBlockState(), Block.UPDATE_CLIENTS | Block.UPDATE_KNOWN_SHAPE); // Festung
        h.assertTrue(spawner.equals(CompanionExpedition.nearestSpawner(sl, edge, 8)), "Spawner on the loaded side not found");
        var c = h.spawn(CompanionMod.COMPANION, new BlockPos(1, 2, 1));
        h.assertTrue(c.hazardAround(sl, edge, true), "Unloaded neighbour not treated as a hazard");
        h.assertTrue(SafeLevel.state(sl, edge.east()) == null && !SafeLevel.loaded(sl, edge, edge.east()), "Neighbour reported as known");
        h.assertTrue(!loaded(sl, cx + 1, cz), "Search or hazard check loaded the neighbouring chunk");
        h.succeed();
    }

    // Generates fresh terrain far out: on a loaded two-core runner, worldgen for the 7x7 area behind all other tests'
    // chunk work took longer than 400 ticks. The check is about the ticket, not worldgen speed.
    @GameTest(maxTicks = 2400)
    public void chunkTicketLoadsTheAreaAroundAWorkingCompanionAndIsReleased(GameTestHelper h) {
        var sl = h.getLevel();
        var at = new ChunkPos(-1_250_300, 1_250_300);
        h.assertTrue(!loaded(sl, at.x() + 1, at.z()), "Fixture chunk already loaded");
        var state = new CompanionChunkLoader.State();
        var pos = new BlockPos((at.x() << 4) + 8, 80, (at.z() << 4) + 8);
        h.assertTrue(!CompanionChunkLoader.simulatedAround(sl, pos), "Fixture area already simulated");
        CompanionChunkLoader.tick(state, sl, pos, true);
        h.assertTrue(state.active() && at.equals(state.chunk()), "Ticket not taken");
        h.succeedWhen(() -> {
            CompanionChunkLoader.tick(state, sl, pos, true);
            h.assertTrue(loaded(sl, at.x(), at.z()) && loaded(sl, at.x() + 1, at.z() - 1)
                    && loaded(sl, at.x() + CompanionChunkLoader.RADIUS - 1, at.z()), "Chunks around the companion not loaded yet");
            CompanionChunkLoader.tick(state, sl, pos, false);
            h.assertTrue(!state.active(), "Ticket kept after the work ended");
        });
    }

    @GameTest
    public void companionOnlyAsksForChunksWhileItHasWork(GameTestHelper h) {
        for (int x = 0; x < 6; x++) for (int z = 0; z < 6; z++) h.setBlock(x, 0, z, Blocks.STONE);
        var c = h.spawn(CompanionMod.COMPANION, new BlockPos(2, 1, 2));
        c.setPersistenceRequired();
        c.stay();
        h.assertTrue(!c.needsLoadedChunks(), "Idle companion keeps chunks loaded");
        c.addTask(CompanionEntity.Task.travel(c.blockPosition().east(200)));
        h.assertTrue(c.needsLoadedChunks(), "Working companion does not load chunks");
        c.pauseWork(CompanionTexts.tr("jobs.sort.none"));
        h.assertTrue(!c.needsLoadedChunks(), "Paused companion keeps chunks loaded");
        h.succeed();
    }
}
