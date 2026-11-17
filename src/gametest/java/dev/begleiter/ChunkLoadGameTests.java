package dev.begleiter;

import java.util.LinkedHashMap;
import java.util.Map;
import net.fabricmc.fabric.api.gametest.v1.GameTest;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Blocks;

/**
 * Release-Blocker B6: Planung und Suche lesen nur geladene Chunks. Alle gemerkten Ziele liegen weit draußen in
 * ungeladenen Chunks; danach darf keiner davon geladen sein.
 */
public class ChunkLoadGameTests {
    private static final int FAR = 200_000;
    /** Chunk -> Begleiter-Aufrufer, der ihn geladen hat (für eine Fehlermeldung, die den Täter nennt). */
    private static final Map<Long, String> LOADED_BY = new java.util.concurrent.ConcurrentHashMap<>();
    private static boolean listening;

    private static synchronized void listen() {
        if (listening) return;
        listening = true;
        net.fabricmc.fabric.api.event.lifecycle.v1.ServerChunkEvents.CHUNK_LOAD.register((level, chunk, generated) -> {
            StringBuilder who = new StringBuilder();
            for (StackTraceElement f : Thread.currentThread().getStackTrace())
                if (f.getClassName().startsWith("dev.begleiter.") && !f.getClassName().contains("ChunkLoadGameTests")
                        && who.length() < 600)
                    who.append("\n  at ").append(f);
            LOADED_BY.putIfAbsent(chunk.getPos().pack(), who.toString());
        });
    }

    private static Map<String, BlockPos> farTargets(GameTestHelper h) {
        BlockPos o = h.absolutePos(BlockPos.ZERO);
        Map<String, BlockPos> out = new LinkedHashMap<>();
        String[] names = {"home", "portal", "waypoint", "chest", "ore", "tree"};
        for (int i = 0; i < names.length; i++) out.put(names[i], new BlockPos(o.getX() + FAR, 64, o.getZ() + FAR + i * 64));
        return out;
    }

    private static CompanionEntity companion(GameTestHelper h, Map<String, BlockPos> far) {
        listen();
        for (int x = 0; x < 8; x++) for (int z = 0; z < 8; z++) h.setBlock(x, 0, z, Blocks.STONE);
        var c = h.spawn(CompanionMod.COMPANION, new BlockPos(2, 1, 2));
        c.buildModeOverride = false;
        var sl = h.getLevel();
        far.forEach((name, p) -> h.assertTrue(sl.getChunkSource().getChunkNow(p.getX() >> 4, p.getZ() >> 4) == null,
                "Fixture schon geladen: " + name));
        c.setHome(far.get("home"));
        c.setPortalPos(far.get("portal"));
        c.memory.setWaypoint("weit", sl, far.get("waypoint"));
        c.memory.remember(CompanionMemory.Kind.CHEST, "", sl, far.get("chest"));
        c.memory.remember(CompanionMemory.Kind.ORE, Resources.DIAMOND.key(), sl, far.get("ore"));
        c.memory.remember(CompanionMemory.Kind.TREE, "", sl, far.get("tree"));
        return c;
    }

    private static void assertNothingLoaded(GameTestHelper h, Map<String, BlockPos> far, String phase) {
        var chunks = h.getLevel().getChunkSource();
        far.forEach((name, p) -> h.assertTrue(chunks.getChunkNow(p.getX() >> 4, p.getZ() >> 4) == null,
                phase + " lädt den Chunk von " + name
                        + LOADED_BY.getOrDefault(net.minecraft.world.level.ChunkPos.pack(p.getX() >> 4, p.getZ() >> 4), "")));
    }

    @GameTest
    public void abfragenLadenKeineEntferntenChunks(GameTestHelper h) {
        var far = farTargets(h);
        var c = companion(h, far);
        c.stay();
        var sl = h.getLevel();
        c.storageChests(sl);
        assertNothingLoaded(h, far, "storageChests");
        c.nearestPortal(8);
        assertNothingLoaded(h, far, "nearestPortal");
        c.memory.verify(c, sl);
        assertNothingLoaded(h, far, "memory.verify");
        CompanionPlanner.obtain(c, Items.IRON_INGOT, 3);
        CompanionPlanner.obtain(c, Items.OAK_PLANKS, 8);
        CompanionPlanner.obtain(c, Items.DIAMOND, 1);
        assertNothingLoaded(h, far, "CompanionPlanner.obtain");
        h.succeed();
    }

    @GameTest(maxTicks = 400)
    public void entfernteBaustelleLaedtKeinenChunk(GameTestHelper h) {
        var far = farTargets(h);
        var c = companion(h, far);
        c.stay();
        BlockPos site = far.get("home").east(64);
        far.put("site", site);
        c.giveToInventory(new net.minecraft.world.item.ItemStack(Items.DIRT, 4));
        c.build = CompanionBuilder.create("weit", Map.of(site, Blocks.DIRT.defaultBlockState(),
                site.above(), Blocks.DIRT.defaultBlockState()));
        c.addTask(CompanionEntity.Task.build());
        h.runAfterDelay(200, () -> {
            assertNothingLoaded(h, far, "Bauauftrag");
            h.succeed();
        });
    }

    /** Hingehen und Abbauen mit Ziel in einem ungeladenen Chunk: die Wegfindung kennt "ungeladen", sie liest nicht nach. */
    @GameTest(maxTicks = 200)
    public void fernesLaufUndAbbauzielLaedtKeinenChunk(GameTestHelper h) {
        var far = farTargets(h);
        var c = companion(h, far);
        c.stay();
        BlockPos walkTo = far.get("home").east(128), dig = far.get("home").east(192);
        far.put("walk", walkTo);
        far.put("dig", dig);
        c.addTask(CompanionEntity.Task.moveTo(walkTo));
        c.addTask(CompanionEntity.Task.breakAt(dig, Blocks.STONE));
        h.runAfterDelay(60, () -> {
            assertNothingLoaded(h, far, "Hingehen");
            c.cancelAll();
            c.addTask(CompanionEntity.Task.breakAt(dig, Blocks.STONE));
            h.runAfterDelay(60, () -> {
                assertNothingLoaded(h, far, "Abbauen");
                h.succeed();
            });
        });
    }

    @GameTest(maxTicks = 400)
    public void autonomesPlanenLaedtKeineEntferntenChunks(GameTestHelper h) {
        var far = farTargets(h);
        var c = companion(h, far);
        c.freeRoam();
        h.runAfterDelay(300, () -> {
            assertNothingLoaded(h, far, "Autonomer Betrieb");
            h.succeed();
        });
    }
}
