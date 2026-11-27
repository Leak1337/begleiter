package dev.begleiter;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;

import net.fabricmc.fabric.api.gametest.v1.GameTest;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.network.chat.contents.TranslatableContents;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.Rotation;

/** Schematics werden im Hintergrund gelesen; Ergebnisse gelten nur, solange die Anfrage aktuell ist. */
public class SchematicLoaderGameTests {
    private static CompanionEntity companion(GameTestHelper h) {
        for (int x = 0; x < 8; x++) for (int z = 0; z < 8; z++) h.setBlock(x, 0, z, Blocks.STONE);
        var c = h.spawn(CompanionMod.COMPANION, new BlockPos(2, 1, 2));
        c.setNoAi(true);
        c.stay();
        return c;
    }

    private static ServerPlayer owner(GameTestHelper h, CompanionEntity c) {
        var p = h.makeMockServerPlayerInLevel();
        p.setGameMode(GameType.SURVIVAL);
        c.setOwner(p.getUUID());
        p.snapTo(c.getX(), c.getY(), c.getZ(), 0, 0);
        return p;
    }

    /** Kleinstes mitgeliefertes Schematic (der Testserver installiert sie beim Start). */
    private static Path smallest(GameTestHelper h) throws Exception {
        var files = CompanionSchematics.list();
        h.assertTrue(!files.isEmpty(), "No bundled schematic installed");
        return files.stream().min(Comparator.comparingLong(f -> {
            try { return Files.size(f); } catch (java.io.IOException e) { return Long.MAX_VALUE; }
        })).orElseThrow();
    }

    private static boolean key(net.minecraft.network.chat.Component reply, String suffix) {
        return reply != null && reply.getContents() instanceof TranslatableContents t && t.getKey().endsWith(suffix);
    }

    @GameTest(maxTicks = 200)
    public void schematicIsReadInTheBackgroundAndTheBuildStartsLater(GameTestHelper h) throws Exception {
        var c = companion(h);
        var p = owner(h, c);
        var reply = CompanionBrain.startSchematicReply(p, c, smallest(h), false, Rotation.NONE);
        h.assertTrue(key(reply, "schematic.loading"), "Expected a loading reply, got " + (reply == null ? null : reply.getString()));
        h.assertTrue(c.build == null, "Build started before the file was read");
        h.succeedWhen(() -> h.assertTrue(c.build != null && c.hasTasks(), "Build did not start after loading"));
    }

    @GameTest(maxTicks = 200)
    public void aNewOrderDiscardsALateSchematicResult(GameTestHelper h) throws Exception {
        var c = companion(h);
        var p = owner(h, c);
        var reply = CompanionBrain.startSchematicReply(p, c, smallest(h), false, Rotation.NONE);
        h.assertTrue(key(reply, "schematic.loading"), "Expected a loading reply");
        c.cancelAll(); // neuer Auftrag, bevor die Datei da ist
        h.runAfterDelay(60, () -> {
            h.assertTrue(SchematicLoader.pending() == 0 || c.build == null, "Loader still busy");
            h.assertTrue(c.build == null && !c.hasTasks(), "Stale schematic result replaced the newer order");
            h.succeed();
        });
    }

    @GameTest(maxTicks = 200)
    public void ownerChangeDiscardsALateSchematicResult(GameTestHelper h) throws Exception {
        var c = companion(h);
        var p = owner(h, c);
        CompanionBrain.startSchematicReply(p, c, smallest(h), false, Rotation.NONE);
        c.setOwner(java.util.UUID.randomUUID());
        h.runAfterDelay(60, () -> {
            h.assertTrue(c.build == null, "Result delivered after the owner changed");
            h.succeed();
        });
    }

    @GameTest
    public void tooLargeAndMissingFilesFailImmediatelyAndKeepWork(GameTestHelper h) throws Exception {
        var c = companion(h);
        var p = owner(h, c);
        var previous = CompanionEntity.Task.give(null, 0);
        c.addTask(previous);
        var dir = Files.createTempDirectory("begleiter-schem");
        var big = dir.resolve("huge.schem");
        try (var raf = new java.io.RandomAccessFile(big.toFile(), "rw")) {
            raf.setLength(16L * 1024 * 1024 + 1);
        }
        var reply = CompanionBrain.startSchematicReply(p, c, big, false, Rotation.NONE);
        h.assertTrue(key(reply, "schematic.unreadable") && reply.getString().contains("16 MiB"), "Oversized file not rejected up front");
        var missing = CompanionBrain.startSchematicReply(p, c, dir.resolve("missing.nbt"), false, Rotation.NONE);
        h.assertTrue(key(missing, "schematic.unreadable"), "Missing file not rejected up front");
        h.assertTrue(c.currentTask() == previous && c.build == null, "Failed schematic request replaced work");
        Files.deleteIfExists(big);
        Files.deleteIfExists(dir);
        h.succeed();
    }

    @GameTest
    public void brokenFileReportsAnErrorFromTheBackground(GameTestHelper h) throws Exception {
        var c = companion(h);
        var p = owner(h, c);
        var dir = Files.createTempDirectory("begleiter-schem");
        var broken = dir.resolve("broken.litematic");
        Files.write(broken, new byte[] {0x1f, (byte) 0x8b, 1, 2, 3, 4, 5});
        var reply = CompanionBrain.startSchematicReply(p, c, broken, false, Rotation.NONE);
        h.assertTrue(key(reply, "schematic.loading"), "Expected a loading reply");
        int before = c.logSize();
        h.succeedWhen(() -> {
            h.assertTrue(c.logSize() > before, "Read error not reported");
            h.assertTrue(c.build == null, "Broken file started a build");
        });
    }

    @GameTest
    public void schematicListIsCachedBriefly(GameTestHelper h) {
        var first = CompanionSchematics.list();
        h.assertTrue(first == CompanionSchematics.list(), "File list read again immediately");
        CompanionSchematics.forgetList();
        h.assertTrue(first != CompanionSchematics.list(), "Forgotten list not read again");
        h.succeed();
    }
}
