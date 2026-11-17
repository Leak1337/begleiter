package dev.begleiter;

import net.fabricmc.fabric.api.gametest.v1.GameTest;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.phys.Vec3;

/** Echte Rettungs-Einstiegspunkte und nur lesende Landeprüfungen in abgetrennten hohen Arenen. */
public class SafeTeleportGameTests {
    private static final String ARENA = "begleiter-gametest:teleport";

    private static final class OwnedCompanion extends CompanionEntity {
        ServerPlayer testOwner;
        net.minecraft.world.entity.Entity transferred;
        OwnedCompanion(ServerLevel level) { super(CompanionMod.COMPANION, level); }
        @Override public Player getOwner() { return testOwner != null && testOwner.level() == level() ? testOwner : null; }
        @Override public ServerPlayer getOwnerAnywhere() { return testOwner; }
        @Override public net.minecraft.world.entity.Entity teleport(net.minecraft.world.level.portal.TeleportTransition transition) {
            transferred = super.teleport(transition);
            return transferred;
        }
    }

    private static OwnedCompanion companion(GameTestHelper h) {
        var c = new OwnedCompanion(h.getLevel());
        c.snapTo(h.absoluteVec(new Vec3(1.5, 1, 1.5)));
        c.setNoAi(true); c.setPersistenceRequired(); c.buildModeOverride = false; c.follow();
        h.getLevel().addFreshEntity(c); h.setBlock(1, 0, 1, Blocks.STONE);
        return c;
    }

    private static void owner(GameTestHelper h, OwnedCompanion c, int y) {
        c.testOwner = new ServerPlayer(h.getLevel().getServer(), h.getLevel(),
                new com.mojang.authlib.GameProfile(java.util.UUID.randomUUID(), "teleport-owner"),
                net.minecraft.server.level.ClientInformation.createDefault()) {
            // Diese Tests prüfen Besitzersuche und Bewegung, ohne eine Netzwerksitzung zu öffnen.
            @Override public void sendSystemMessage(net.minecraft.network.chat.Component message) { }
        };
        c.setOwner(c.testOwner.getUUID());
        c.testOwner.setPos(h.absoluteVec(new Vec3(8.5, y, 8.5)));
    }

    private static void platform(GameTestHelper h, int feetY, Block block) {
        for (int x = 4; x <= 12; x++) for (int z = 4; z <= 12; z++) h.setBlock(x, feetY - 1, z, block);
    }

    @GameTest(structure = ARENA)
    public void followsFlyingOwnerOntoGroundAndResetsFalling(GameTestHelper h) {
        var c = companion(h); owner(h, c, 90); platform(h, 70, Blocks.STONE);
        c.fallDistance = 18; c.setDeltaMovement(0.2, -1.5, 0);
        new CompanionFollowGoal(c).tick();
        h.assertValueEqual(c.getBlockY(), h.absolutePos(new BlockPos(8, 70, 8)).getY(), "Auf Besitzerhöhe statt Boden teleportiert");
        h.assertTrue(c.position().distanceToSqr(c.testOwner.position()) <= 405, "Landeplatz nicht beim Besitzer");
        h.assertTrue(c.onGround() && c.fallDistance == 0 && c.getDeltaMovement().equals(Vec3.ZERO), "Alter Fallimpuls blieb erhalten");
        h.assertTrue(CompanionSafeTeleport.safe(c, h.getLevel(), c.position()), "Unsicherer Landeplatz");
        ServerTextGameTests.assertTranslatedContains(h, c.nav.eventComponent(), "sicheren Platz", "safe position");
        h.succeed();
    }

    @GameTest(structure = ARENA)
    public void waitsOverSeaAndDoesNotReuseOldWalkingPath(GameTestHelper h) {
        var c = companion(h); owner(h, c, 90); platform(h, 70, Blocks.WATER);
        Vec3 before = c.position();
        c.getNavigation().moveTo(c.getX() + 2, c.getY(), c.getZ(), 1);
        var follow = new CompanionFollowGoal(c); follow.tick();
        h.assertValueEqual(c.position(), before, "Ins Meer teleportiert");
        h.assertTrue(c.getNavigation().isDone(), "Alter Weg läuft während des Wartens weiter");
        ServerTextGameTests.assertTranslatedContains(h, c.nav.eventComponent(), "kein sicherer", "no safe");
        platform(h, 70, Blocks.STONE); c.tickCount = 20; follow.tick();
        h.assertValueEqual(c.getBlockY(), h.absolutePos(new BlockPos(8, 70, 8)).getY(), "Sicheren Platz später nicht neu geprüft");
        h.succeed();
    }

    @GameTest(structure = ARENA)
    public void waitsAboveLavaInsteadOfBlindCatchup(GameTestHelper h) {
        var c = companion(h); owner(h, c, 90); platform(h, 70, Blocks.LAVA);
        Vec3 before = c.position(); new CompanionFollowGoal(c).tick();
        h.assertValueEqual(c.position(), before, "Über Lava teleportiert"); h.succeed();
    }

    @GameTest(structure = ARENA)
    public void rejectsLowCeilingAndOccupiedLanding(GameTestHelper h) {
        var c = companion(h); platform(h, 70, Blocks.STONE);
        BlockPos feet = h.absolutePos(new BlockPos(8, 70, 8)); Vec3 at = Vec3.atBottomCenterOf(feet);
        h.getLevel().setBlock(feet.above(), Blocks.STONE.defaultBlockState(), 2);
        h.assertTrue(!CompanionSafeTeleport.move(c, h.getLevel(), at), "In niedrige Decke teleportiert");
        h.getLevel().setBlock(feet.above(), Blocks.AIR.defaultBlockState(), 2);
        var other = h.spawn(CompanionMod.COMPANION, new BlockPos(8, 70, 8)); other.setNoAi(true); other.stay();
        h.assertTrue(!CompanionSafeTeleport.move(c, h.getLevel(), at), "In anderen Begleiter teleportiert");
        other.discard();
        h.assertTrue(CompanionSafeTeleport.move(c, h.getLevel(), at), "Freier Platz wurde abgelehnt"); h.succeed();
    }

    @GameTest(structure = ARENA)
    public void rejectsContactHazardsAndFluidWithoutChangingWorldOrInventory(GameTestHelper h) {
        var c = companion(h); platform(h, 70, Blocks.STONE); c.giveToInventory(new ItemStack(Items.DIAMOND, 3));
        BlockPos feet = h.absolutePos(new BlockPos(8, 70, 8));
        Vec3 at = Vec3.atBottomCenterOf(feet), before = c.position();
        for (Block hazard : new Block[]{Blocks.FIRE, Blocks.SOUL_FIRE, Blocks.LAVA, Blocks.WATER,
                Blocks.MAGMA_BLOCK, Blocks.CACTUS, Blocks.CAMPFIRE, Blocks.POWDER_SNOW, Blocks.WITHER_ROSE,
                Blocks.SWEET_BERRY_BUSH, Blocks.POINTED_DRIPSTONE, Blocks.NETHER_PORTAL, Blocks.END_PORTAL}) {
            h.getLevel().setBlock(feet.below(), (hazard == Blocks.SOUL_FIRE ? Blocks.SOUL_SOIL : Blocks.STONE).defaultBlockState(), 2);
            h.getLevel().setBlock(feet, hazard.defaultBlockState(), 2);
            h.assertTrue(h.getLevel().getBlockState(feet).is(hazard), "Gefahr konnte im Test nicht aufgestellt werden: " + hazard);
            h.assertTrue(!CompanionSafeTeleport.move(c, h.getLevel(), at), "Gefahr akzeptiert: " + hazard);
            h.assertTrue(h.getLevel().getBlockState(feet).is(hazard), "Prüfung verändert Gefahr");
            h.assertValueEqual(c.position(), before, "Abgelehnte Landung verändert Position");
        }
        h.getLevel().setBlock(feet, Blocks.AIR.defaultBlockState(), 2);
        h.getLevel().setBlock(feet.below(), Blocks.MAGMA_BLOCK.defaultBlockState(), 2);
        h.assertTrue(!CompanionSafeTeleport.safe(c, h.getLevel(), at), "Magma als sicherer Boden akzeptiert");
        h.assertValueEqual(CompanionCraft.count(c, "diamond"), 3, "Prüfung verändert Inventar"); h.succeed();
    }

    @GameTest(structure = ARENA)
    public void requiresWholeBodyInsideBorderAndRefusesUnsafeShelterCentering(GameTestHelper h) {
        var c = companion(h); platform(h, 1, Blocks.STONE);
        BlockPos feet = h.absolutePos(new BlockPos(8, 1, 8));
        c.snapTo(feet.getX() + 0.2, feet.getY(), feet.getZ() + 0.5, 0, 0);
        c.setOnGround(true); c.giveToInventory(new ItemStack(Items.DIRT, 16));
        var border = h.getLevel().getWorldBorder();
        double x = border.getCenterX(), z = border.getCenterZ(), size = border.getSize(); Vec3 before = c.position();
        try {
            border.setCenter(feet.getX() - 4.8, feet.getZ() + 0.5); border.setSize(11);
            h.assertTrue(!CompanionSafeTeleport.safe(c, h.getLevel(), Vec3.atBottomCenterOf(feet)), "Körper ragt über Weltgrenze");
            h.assertTrue(!c.sealIn(h.getLevel()), "Schutzraum zentriert außerhalb der Weltgrenze");
            h.assertValueEqual(c.position(), before, "Abgelehnte Zentrierung bewegt Begleiter");
            h.assertValueEqual(CompanionCraft.count(c, "dirt"), 16, "Abgelehnter Schutzraum verbraucht Material");
        } finally { border.setCenter(x, z); border.setSize(size); }
        h.succeed();
    }

    @GameTest(structure = ARENA)
    public void rejectsUnloadedDestinationWithoutLoadingChunks(GameTestHelper h) {
        var c = companion(h); var level = h.getLevel(); Vec3 before = c.position();
        Vec3 far = new Vec3(29_000_008.5, 70, 29_000_008.5); int chunk = 29_000_008 >> 4;
        h.assertTrue(level.getChunkSource().getChunkNow(chunk, chunk) == null, "Testziel ist bereits geladen");
        int loaded = level.getChunkSource().getLoadedChunksCount();
        h.assertTrue(CompanionSafeTeleport.nearOwner(c, level, far) == null, "Ungeladenen Platz bestätigt");
        h.assertTrue(!CompanionSafeTeleport.move(c, level, far), "In ungeladenen Chunk teleportiert");
        h.assertTrue(level.getChunkSource().getChunkNow(chunk, chunk) == null, "Zielchunk nachgeladen");
        h.assertValueEqual(level.getChunkSource().getLoadedChunksCount(), loaded, "Prüfung lädt Chunks");
        h.assertValueEqual(c.position(), before, "Ungeladenes Ziel verändert Position"); h.succeed();
    }

    @GameTest(structure = ARENA)
    public void loadedChunkEdgeDoesNotPullInUnknownNeighbor(GameTestHelper h) {
        var c = companion(h); var end = h.getLevel().getServer().getLevel(Level.END);
        end.getChunk(500, 500); // Ausdrücklicher Testaufbau, nicht Teil der Landeprüfung.
        // Die Generierung kann Nachbarchunks mit vorbereiten. Den tatsächlich geladenen Rand nur lesend suchen.
        int edge = -1;
        for (int cx = 500; cx >= 436; cx--)
            if (end.getChunkSource().getChunkNow(cx, 500) != null
                    && end.getChunkSource().getChunkNow(cx - 1, 500) == null) { edge = cx; break; }
        h.assertTrue(edge >= 436, "Keinen geladenen Chunkrand gefunden");
        BlockPos feet = new BlockPos(edge * 16, 70, 8008);
        int flags = Block.UPDATE_CLIENTS | Block.UPDATE_KNOWN_SHAPE;
        end.setBlock(feet.below(), Blocks.STONE.defaultBlockState(), flags);
        end.setBlock(feet, Blocks.AIR.defaultBlockState(), flags);
        end.setBlock(feet.above(), Blocks.AIR.defaultBlockState(), flags);
        h.assertTrue(end.getChunkSource().getChunkNow(edge - 1, 500) == null, "Testaufbau lädt Nachbarchunk");
        int loaded = end.getChunkSource().getLoadedChunksCount();
        h.assertTrue(!CompanionSafeTeleport.safe(c, end, Vec3.atBottomCenterOf(feet)), "Unbekannte Kollisionsnachbarn ignoriert");
        h.assertTrue(end.getChunkSource().getChunkNow(edge - 1, 500) == null, "Nachbarchunk durch Kollisionsprüfung geladen");
        h.assertValueEqual(end.getChunkSource().getLoadedChunksCount(), loaded, "Kollisionsprüfung lädt Chunks"); h.succeed();
    }

    @GameTest(structure = ARENA)
    public void farRecoveryKeepsOrderUntilLandingIsConfirmed(GameTestHelper h) {
        var c = companion(h); owner(h, c, 180);
        var task = CompanionEntity.Task.moveTo(c.blockPosition().east(2), 1); c.addTask(task); Vec3 before = c.position();
        c.tickCount = 100; c.customServerAiStep(h.getLevel());
        h.assertValueEqual(c.position(), before, "Ohne Landeplatz versetzt");
        h.assertTrue(c.currentTask() == task, "Erfolgloses Aufholen löscht Auftrag");
        platform(h, 160, Blocks.STONE); c.fallDistance = 19; c.setDeltaMovement(0, -2, 0);
        c.tickCount = 200; c.customServerAiStep(h.getLevel());
        h.assertValueEqual(c.getBlockY(), h.absolutePos(new BlockPos(8, 160, 8)).getY(), "Rückholung nutzt nicht den Boden");
        h.assertTrue(!c.hasTasks() && c.fallDistance == 0 && c.getDeltaMovement().equals(Vec3.ZERO), "Bestätigte Rückholung unvollständig");
        h.succeed();
    }

    @GameTest(structure = ARENA)
    public void emergencyFireEscapeUsesConfirmedDryGround(GameTestHelper h) {
        var c = companion(h); platform(h, 1, Blocks.STONE); BlockPos fire = h.absolutePos(new BlockPos(8, 1, 8));
        c.snapTo(Vec3.atBottomCenterOf(fire)); c.setRemainingFireTicks(100);
        h.getLevel().setBlock(fire, Blocks.FIRE.defaultBlockState(), 2);
        c.fallDistance = 12; c.setDeltaMovement(0, -1, 0); c.tickCount = 5; c.customServerAiStep(h.getLevel());
        h.assertTrue(!c.blockPosition().equals(fire) && CompanionSafeTeleport.safe(c, h.getLevel(), c.position()), "Feuerrettung ohne sicheren Platz");
        h.assertTrue(c.fallDistance == 0 && c.getDeltaMovement().equals(Vec3.ZERO), "Rettung behält alten Fallimpuls"); h.succeed();
    }

    @GameTest(structure = ARENA)
    public void dimensionCatchupRefusesUnloadedTarget(GameTestHelper h) {
        var c = companion(h); owner(h, c, 90); var end = h.getLevel().getServer().getLevel(Level.END);
        c.testOwner.setServerLevel(end); c.testOwner.setPos(29_000_008.5, 90, 29_000_008.5);
        Vec3 before = c.position(); int loaded = end.getChunkSource().getLoadedChunksCount();
        c.tickCount = 40; c.customServerAiStep(h.getLevel()); c.tickCount = 240; c.customServerAiStep(h.getLevel());
        h.assertTrue(!c.isRemoved() && c.level() == h.getLevel(), "Ohne geladenes Ziel Dimension gewechselt");
        h.assertValueEqual(c.position(), before, "Fehlgeschlagener Dimensionswechsel bewegt Begleiter");
        h.assertValueEqual(end.getChunkSource().getLoadedChunksCount(), loaded, "Dimensionsprüfung lädt Zielchunks"); h.succeed();
    }

    @GameTest(structure = ARENA)
    public void dimensionCatchupLandsOnLoadedGroundBelowOwner(GameTestHelper h) {
        var c = companion(h); owner(h, c, 90); var end = h.getLevel().getServer().getLevel(Level.END);
        // Ausdrücklicher Testaufbau: einen Zielchunk vorbereiten, getrennt von der nur lesenden Landesuche.
        end.getChunk(375, 375);
        for (int x = 6004; x <= 6012; x++) for (int z = 6004; z <= 6012; z++) {
            for (int y = 66; y <= 94; y++) end.setBlock(new BlockPos(x, y, z), Blocks.AIR.defaultBlockState(), 2);
            end.setBlock(new BlockPos(x, 69, z), Blocks.STONE.defaultBlockState(), 2);
        }
        c.testOwner.setServerLevel(end); c.testOwner.setPos(6008.5, 90, 6008.5);
        h.assertTrue(CompanionSafeTeleport.nearOwner(c, end, c.testOwner.position()) != null, "Vorbereiteter End-Landeplatz ist nicht geladen/sicher");
        c.tickCount = 40; c.customServerAiStep(h.getLevel()); c.fallDistance = 30;
        c.tickCount = 240; c.customServerAiStep(h.getLevel()); var arrived = c.transferred;
        h.assertTrue(arrived instanceof CompanionEntity, "Begleiter nicht in Zieldimension angekommen");
        var companion = (CompanionEntity) arrived;
        h.assertTrue(companion.level() == end && companion.getBlockY() == 70
                && CompanionSafeTeleport.safe(companion, end, companion.position()), "Dimensionswechsel landet unsicher");
        h.assertTrue(companion.fallDistance == 0 && companion.getDeltaMovement().equals(Vec3.ZERO), "Dimensionswechsel behält Fallimpuls");
        companion.discard(); h.succeed();
    }
}
