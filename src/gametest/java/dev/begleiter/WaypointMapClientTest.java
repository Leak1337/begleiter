package dev.begleiter;

import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;

import dev.begleiter.client.gui.CompanionScreen;
import dev.begleiter.client.gui.WaypointMapScreen;
import dev.begleiter.task.TaskSpec;
import net.fabricmc.fabric.api.client.gametest.v1.FabricClientGameTest;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.client.input.MouseButtonInfo;
import net.minecraft.client.resources.language.I18n;
import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.EntitySpawnReason;

public final class WaypointMapClientTest implements FabricClientGameTest {
    @Override public void runTest(ClientGameTestContext context) {
        var id = new AtomicReference<UUID>();
        var destination = new AtomicReference<BlockPos>();
        try (var world = context.worldBuilder().create()) {
            world.getConnection().waitForChunksDownload();
            world.getServer().runOnServer(server -> {
                var player = server.getPlayerList().getPlayers().getFirst();
                var level = player.level();
                var bot = CompanionMod.COMPANION.create(level, EntitySpawnReason.COMMAND);
                bot.setOwner(player.getUUID());
                bot.snapTo(player.getX() + 2, player.getY(), player.getZ(), 0, 0);
                bot.setNoAi(true);
                bot.stay();
                level.addFreshEntity(bot);
                id.set(bot.getUUID());
                destination.set(player.blockPosition().east(36));
                bot.memory.setWaypoint("Mine", level, destination.get());
                bot.memory.setWaypoint("Camp", level, player.blockPosition().north(24));
                bot.openPanel(player, CompanionMenu.Page.WAYPOINTS);
            });
            context.waitForScreen(CompanionScreen.class);
            context.clickScreenButton(context.computeOnClient(mc -> I18n.get("gui.begleiter.waypoint_map.open")));
            context.waitForScreen(WaypointMapScreen.class);
            context.waitTicks(2);
            context.takeScreenshot("companion-waypoint-map");
            context.runOnClient(mc -> {
                var map = (WaypointMapScreen) mc.gui.screen();
                double scale = Math.clamp((Math.min(map.width, map.height) / 2.0 - 48) / 36, 0.000001, 4);
                map.mouseScrolled(map.width / 2.0, map.height / 2.0, 0, -1);
                double x = map.width / 2.0 + 36 * scale / 1.35;
                var marker = new MouseButtonEvent(x, map.height / 2.0, new MouseButtonInfo(0, 0));
                map.mouseClicked(marker, false);
                map.mouseClicked(marker, true);
            });
            context.waitFor(mc -> mc.gui.screen() == null);
            context.waitTicks(3);
            world.getServer().runOnServer(server -> {
                var player = server.getPlayerList().getPlayers().getFirst();
                var bot = (CompanionEntity) player.level().getEntity(id.get());
                if (bot == null || bot.currentTask() == null || !(bot.currentTask().spec() instanceof TaskSpec.Travel travel)
                        || !destination.get().equals(travel.pos())) throw new AssertionError("Map click didn't queue the server waypoint");
            });
        }
    }
}
