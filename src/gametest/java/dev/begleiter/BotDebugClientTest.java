package dev.begleiter;

import com.mojang.blaze3d.platform.InputConstants;

import dev.begleiter.client.gui.BotDebugScreen;
import dev.begleiter.debug.BotDebugClient;
import dev.begleiter.debug.BotDebugConfig;
import net.fabricmc.fabric.api.client.gametest.v1.FabricClientGameTest;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestServerContext;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.EntitySpawnReason;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Blocks;

/** Echter Client + Einzelspieler-Server: HUD, ESP, Menü-Tabs, Steuerung, Despawn und Dimensionswechsel. */
public final class BotDebugClientTest implements FabricClientGameTest {
    private static CompanionEntity spawn(ServerPlayer player, double dx, double dz, String name) {
        var c = CompanionMod.COMPANION.create(player.level(), EntitySpawnReason.COMMAND);
        if (c == null) throw new AssertionError("Begleiter konnte nicht erzeugt werden");
        c.setOwner(player.getUUID());
        c.snapTo(player.getX() + dx, player.getY(), player.getZ() + dz, 0, 0);
        c.setCustomName(net.minecraft.network.chat.Component.literal(name));
        c.stay();
        player.level().addFreshEntity(c);
        return c;
    }

    @Override public void runTest(ClientGameTestContext context) {
        try (var world = context.worldBuilder().create()) {
            world.getConnection().waitForChunksDownload();
            TestServerContext server = world.getServer();
            context.runOnClient(mc -> { CompanionDebugOverlay.enabled = true; var c = BotDebugConfig.get(); c.hud = true; c.esp = BotDebugConfig.Esp.ALL; c.lines = true; });
            // Spieler schaut nach Süden (+z): erster Bot nahe davor, zweiter 12 Blöcke dahinter, dazwischen eine Wand
            server.runOnServer(mc -> {
                var player = mc.getPlayerList().getPlayers().getFirst();
                player.snapTo(player.getX(), player.getY(), player.getZ(), 0, 0);
                var level = player.level();
                BlockPos base = player.blockPosition();
                for (int y = 0; y < 4; y++)
                    for (int x = -3; x <= 3; x++) level.setBlockAndUpdate(base.offset(x, y, 6), Blocks.STONE.defaultBlockState());
                var a = spawn(player, 2, 3, "Alpha");
                a.addTask(CompanionEntity.Task.moveTo(base.offset(2, 0, 4)));
                a.addTask(CompanionEntity.Task.collect(false));
                spawn(player, -1, 12, "Beta");
            });
            context.waitFor(mc -> BotDebugClient.bots().size() == 2 && BotDebugClient.detail() != null);
            context.waitTicks(10);
            context.takeScreenshot("debug-hud-esp");

            // Menü mit F8 öffnen, jeden Tab fotografieren
            context.getInput().pressKey(InputConstants.KEY_F8);
            context.waitForScreen(BotDebugScreen.class);
            context.waitTicks(15);
            // Beschriftungen in der aktuellen Spielsprache (DE oder EN), nie als fester deutscher Text
            for (String tab : new String[] {"overview", "ai", "nav", "control", "logs", "display"}) {
                context.clickScreenButton(label(context, "debug.begleiter.tab." + tab));
                context.waitTicks(12);
                assertNoRawKeys(context, tab);
                context.takeScreenshot("debug-tab-" + tab);
            }

            // Steuerung: Pause wirkt serverseitig, Abbrechen verlangt Bestätigung
            String confirm = label(context, "debug.begleiter.confirm"), cancelTasks = label(context, "debug.begleiter.control.cancel");
            context.clickScreenButton(label(context, "debug.begleiter.tab.control"));
            context.waitTicks(5);
            context.clickScreenButton(label(context, "debug.begleiter.control.pause"));
            server.waitFor(mc -> allBots(mc).stream().anyMatch(CompanionEntity::isDebugFrozen));
            context.clickScreenButton(cancelTasks);
            context.waitTicks(4);
            assertNoRawKeys(context, "confirm");
            context.takeScreenshot("debug-confirm");
            context.getInput().pressKey(InputConstants.KEY_ESCAPE);
            context.waitTicks(4);
            context.waitForScreen(BotDebugScreen.class);
            if (context.tryClickScreenButton(confirm)) throw new AssertionError("Escape bricht die Bestätigung nicht ab");
            context.clickScreenButton(cancelTasks);
            context.waitTicks(3);
            context.clickScreenButton(confirm);
            server.waitFor(mc -> allBots(mc).stream().allMatch(b -> !b.hasTasks()));
            context.waitTicks(4);
            context.clickScreenButton(label(context, "debug.begleiter.control.goal.set"));
            server.waitFor(mc -> allBots(mc).stream().anyMatch(b -> b.currentTask() != null
                    && b.currentTask().type() == CompanionEntity.TaskType.MOVE_TO));

            // Anzeige: ESP durchschalten und speichern
            context.clickScreenButton(label(context, "debug.begleiter.tab.display"));
            context.waitTicks(4);
            context.clickScreenButton(label(context, "debug.begleiter.display.esp", label(context, "debug.begleiter.display.esp.all")));
            context.waitTicks(4);
            context.runOnClient(mc -> {
                if (BotDebugConfig.get().esp != BotDebugConfig.Esp.OFF) throw new AssertionError("ESP-Option nicht umgeschaltet");
            });
            context.clickScreenButton(label(context, "debug.begleiter.toggle", label(context, "debug.begleiter.display.path"),
                    label(context, "debug.begleiter.off")));
            context.waitTicks(4);
            context.takeScreenshot("debug-tab-anzeige-2");
            context.getInput().pressKey(InputConstants.KEY_F8);
            context.waitFor(mc -> mc.gui.screen() == null);

            // Pfad und ESP zurück; Despawn: der entfernte Bot verschwindet aus der Liste
            context.runOnClient(mc -> { var c = BotDebugConfig.get(); c.esp = BotDebugConfig.Esp.SELECTED; c.path = true; });
            // Alpha läuft los: Pfad, Ziel und Fortschritt kommen an
            server.runOnServer(mc -> allBots(mc).stream().filter(b -> b.getName().getString().equals("Alpha")).forEach(b -> {
                b.setDebugFrozen(false);
                b.cancelAll();
                b.addTask(CompanionEntity.Task.moveTo(b.blockPosition().offset(-10, 0, 3)));
            }));
            context.waitFor(mc -> BotDebugClient.detail() != null && BotDebugClient.detail().pathLength() > 0
                    && BotDebugClient.detail().path().length > 2);
            context.waitTicks(10);
            context.takeScreenshot("debug-esp-selected-path");
            server.runOnServer(mc -> allBots(mc).stream().filter(b -> b.getName().getString().equals("Beta")).forEach(b -> b.discard()));
            context.waitFor(mc -> BotDebugClient.bots().size() == 1);

            // Dimensionswechsel: Bot bleibt in der Liste, wird aber in der anderen Dimension nicht markiert
            server.runOnServer(mc -> {
                var player = mc.getPlayerList().getPlayers().getFirst();
                var nether = mc.getLevel(Level.NETHER);
                player.teleportTo(nether, 8, 100, 8, java.util.Set.of(), 0, 0, true);
            });
            context.waitFor(mc -> mc.level != null && mc.level.dimension() == Level.NETHER);
            context.waitTicks(25);
            // Der Schnappschuss entspricht dem Server: Bots in entladenen Chunks fehlen, sonst stimmt die Dimension
            java.util.Set<String> serverDims = new java.util.HashSet<>();
            server.runOnServer(mc -> allBots(mc).forEach(b -> serverDims.add(b.level().dimension().identifier().toString())));
            context.waitFor(mc -> BotDebugClient.bots().stream().map(e -> e.dim()).collect(java.util.stream.Collectors.toSet())
                    .equals(serverDims));
            context.waitTicks(10);
            context.takeScreenshot("debug-nether");
        }
    }

    private static String label(ClientGameTestContext context, String key, Object... args) {
        return context.computeOnClient(mc -> net.minecraft.client.resources.language.I18n.get(key, args));
    }

    /** Kein Knopf und kein Text im F8-Menü zeigt einen unübersetzten Schlüssel. */
    private static void assertNoRawKeys(ClientGameTestContext context, String where) {
        context.runOnClient(mc -> {
            for (var child : mc.gui.screen().children())
                if (child instanceof net.minecraft.client.gui.components.AbstractWidget w
                        && w.getMessage().getString().contains("begleiter."))
                    throw new AssertionError("Rohschlüssel im F8-Menü (" + where + "): " + w.getMessage().getString());
        });
    }

    private static java.util.List<CompanionEntity> allBots(net.minecraft.server.MinecraftServer mc) {
        java.util.List<CompanionEntity> out = new java.util.ArrayList<>();
        for (var level : mc.getAllLevels())
            for (var e : level.getAllEntities()) if (e instanceof CompanionEntity c && c.isAlive()) out.add(c);
        return out;
    }
}
