package dev.begleiter;

import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import net.fabricmc.fabric.api.client.gametest.v1.FabricClientGameTest;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.EntitySpawnReason;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.phys.Vec3;

/** Echte Client/Server-Verträglichkeit und Screenshot-Folge der Laufsteuerung. */
public final class NavMotionClientTest implements FabricClientGameTest {
    @Override public void runTest(ClientGameTestContext context) {
        try (var world = context.worldBuilder().create()) {
            world.getConnection().waitForChunksDownload();
            AtomicInteger id = new AtomicInteger();
            AtomicReference<BlockPos> origin = new AtomicReference<>();
            world.getServer().runOnServer(server -> {
                var player = server.getPlayerList().getPlayers().getFirst();
                var level = player.level();
                BlockPos base = player.blockPosition().offset(-12, 0, 5); origin.set(base);
                for (int x = 0; x < 48; x++) for (int z = 0; z < 12; z++) {
                    level.setBlockAndUpdate(base.offset(x, -1, z), Blocks.STONE.defaultBlockState());
                    for (int y = 0; y < 5; y++) level.setBlockAndUpdate(base.offset(x, y, z), Blocks.AIR.defaultBlockState());
                }
                for (int x : new int[]{12, 24, 36}) for (int z = 0; z < 12; z++)
                    if (x == 24 ? z > 3 : z < 8) for (int y = 0; y < 3; y++)
                        level.setBlockAndUpdate(base.offset(x, y, z), Blocks.BARRIER.defaultBlockState());
                for (int x = 0; x < 48; x++) for (int z : new int[]{0, 11}) for (int y = 0; y < 3; y++)
                    level.setBlockAndUpdate(base.offset(x, y, z), Blocks.BARRIER.defaultBlockState());
                var bot = CompanionMod.COMPANION.create(level, EntitySpawnReason.COMMAND);
                if (bot == null) throw new AssertionError("Bewegungstest: Begleiter fehlt");
                bot.setOwner(player.getUUID()); bot.stay(); bot.nav.trace.enable();
                bot.snapTo(base.getX() + 2.5, base.getY(), base.getZ() + 3.5, -90, 0);
                level.addFreshEntity(bot); id.set(bot.getId());
                player.teleportTo(level, base.getX() + 8, base.getY() + 3, base.getZ() - 6, java.util.Set.of(), 0, 20, true);
            });
            context.waitFor(mc -> mc.level != null && mc.level.getEntity(id.get()) instanceof CompanionEntity);
            faceBot(context, id.get());
            context.takeScreenshot("motion-start");
            world.getServer().runOnServer(server -> {
                var bot = (CompanionEntity) server.overworld().getEntity(id.get());
                bot.addTask(CompanionEntity.Task.moveTo(origin.get().offset(43, 0, 3), 1));
            });
            Vec3 previous = context.computeOnClient(mc -> mc.level.getEntity(id.get()).position());
            double distance = 0, maxStep = 0;
            for (int frame = 0; frame < 80; frame++) {
                context.waitTicks(2);
                Vec3 position = context.computeOnClient(mc -> mc.level.getEntity(id.get()).position());
                double step = position.distanceTo(previous); distance += step; maxStep = Math.max(maxStep, step); previous = position;
                if (frame == 25 || frame == 50) {
                    world.getServer().runOnServer(server -> {
                        var player = server.getPlayerList().getPlayers().getFirst();
                        var bot = server.overworld().getEntity(id.get());
                        player.teleportTo(player.level(), bot.getX() - 4, bot.getY() + 3, bot.getZ() - 6, java.util.Set.of(), -30, 20, true);
                    });
                    context.waitTicks(3);
                    faceBot(context, id.get());
                    context.waitTicks(1);
                    context.takeScreenshot("motion-curve-" + frame);
                }
            }
            if (distance < 10) throw new AssertionError("Client hat keine laufende Bewegung empfangen: " + distance);
            // This tests packet position continuity, not interpolated render smoothness or arbitrary latency.
            if (maxStep > 8) throw new AssertionError("Unerwarteter Positionssprung im Client: " + maxStep);
            world.getServer().waitFor(server -> {
                var bot = (CompanionEntity) server.overworld().getEntity(id.get());
                return bot != null && bot.currentTask() == null;
            });
            world.getServer().runOnServer(server -> {
                var bot = (CompanionEntity) server.overworld().getEntity(id.get());
                if (bot.getHealth() != bot.getMaxHealth()) throw new AssertionError("Bewegung verursacht Schaden");
                System.out.println("[Nav-Motion-Client] " + bot.nav.trace.metrics());
            });
            context.takeScreenshot("motion-arrived");
        }
    }

    private static void faceBot(ClientGameTestContext context, int id) {
        context.runOnClient(mc -> {
            Vec3 delta = mc.level.getEntity(id).getEyePosition().subtract(mc.player.getEyePosition());
            mc.player.setYRot((float) (Math.toDegrees(Math.atan2(delta.z, delta.x)) - 90));
            mc.player.setXRot((float) -Math.toDegrees(Math.atan2(delta.y, Math.hypot(delta.x, delta.z))));
        });
    }
}
