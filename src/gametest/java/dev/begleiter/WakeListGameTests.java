package dev.begleiter;

import java.util.UUID;

import dev.begleiter.world.CompanionChunkLoader;
import dev.begleiter.world.CompanionWakeList;
import net.fabricmc.fabric.api.gametest.v1.GameTest;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.nbt.NbtOps;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.block.Blocks;

/** Arbeitende Begleiter werden gemerkt und nach einem Neustart über ein Weck-Ticket wieder geladen. */
public class WakeListGameTests {
    @GameTest(maxTicks = 100)
    public void workingSpotIsNotedAndForgottenWhenWorkEnds(GameTestHelper h) {
        var sl = h.getLevel();
        var id = UUID.randomUUID();
        var state = new CompanionChunkLoader.State();
        var pos = new BlockPos((-1_250_500 << 4) + 8, 80, (1_250_500 << 4) + 8);
        var list = CompanionWakeList.get(sl.getServer());
        CompanionChunkLoader.tick(state, sl, pos, true, id);
        var e = list.entry(id);
        h.assertTrue(e != null && e.chunk() == new ChunkPos(pos.getX() >> 4, pos.getZ() >> 4).pack()
                && e.dimension().equals(sl.dimension().identifier()), "Working spot not noted: " + e);
        CompanionChunkLoader.tick(state, sl, pos, false, id);
        h.assertTrue(list.entry(id) == null, "Entry kept after the work ended");
        h.assertTrue(!state.active(), "Ticket kept after the work ended");
        h.succeed();
    }

    @GameTest(maxTicks = 100)
    public void unloadingKeepsTheEntryAndDiscardingForgetsIt(GameTestHelper h) {
        for (int x = 0; x < 6; x++) for (int z = 0; z < 6; z++) h.setBlock(x, 0, z, Blocks.STONE);
        var c = h.spawn(CompanionMod.COMPANION, new BlockPos(2, 1, 2));
        c.setPersistenceRequired();
        c.stay();
        c.addTask(CompanionEntity.Task.travel(c.blockPosition().east(50)));
        var list = CompanionWakeList.get(h.getLevel().getServer());
        h.runAfterDelay(3, () -> {
            h.assertTrue(list.entry(c.getUUID()) != null, "Working companion not noted (needs " + c.needsLoadedChunks() + ", task " + c.currentTaskText() + ", noAi " + c.isNoAi() + ")");
            c.onRemoval(Entity.RemovalReason.UNLOADED_TO_CHUNK);
            h.assertTrue(list.entry(c.getUUID()) != null, "Unloading (save/stop) forgot the companion");
            c.discard();
            h.assertTrue(list.entry(c.getUUID()) == null, "Discarded companion still woken");
            h.succeed();
        });
    }

    @GameTest(maxTicks = 300)
    public void wakeLoadsTheRememberedChunkAndDropsSilentCompanions(GameTestHelper h) {
        var sl = h.getLevel();
        var server = sl.getServer();
        var list = new CompanionWakeList();
        var far = new ChunkPos(1_250_700, -1_250_700);
        h.assertTrue(sl.getChunkSource().getChunkNow(far.x(), far.z()) == null, "Fixture chunk already loaded");
        var silent = UUID.randomUUID();
        var back = UUID.randomUUID();
        list.note(silent, sl, far);
        list.note(back, sl, far);
        // Speichern/Laden wie beim Neustart
        var tag = CompanionWakeList.TYPE.codec().encodeStart(NbtOps.INSTANCE, list).getOrThrow();
        var loaded = CompanionWakeList.TYPE.codec().parse(NbtOps.INSTANCE, tag).getOrThrow();
        h.assertValueEqual(loaded.size(), 2, "Entries lost across save/load");
        loaded.wakeAll(server);
        loaded.note(back, sl, far); // dieser Begleiter meldet sich nach dem Wecken
        h.succeedWhen(() -> {
            h.assertTrue(sl.getChunkSource().getChunkNow(far.x(), far.z()) != null, "Remembered chunk not loaded by the wake ticket");
            loaded.endGraceNow(server);
            h.assertTrue(loaded.entry(silent) == null, "Companion that never reported back is still woken");
            h.assertTrue(loaded.entry(back) != null, "Companion that reported back was forgotten");
        });
    }
}
