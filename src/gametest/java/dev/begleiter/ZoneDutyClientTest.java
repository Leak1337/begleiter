package dev.begleiter;

import com.mojang.blaze3d.platform.InputConstants;
import dev.begleiter.CompanionZones.Kind;
import dev.begleiter.client.gui.CompanionScreen;
import dev.begleiter.client.gui.ZoneMapScreen;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicReference;
import net.fabricmc.fabric.api.client.gametest.v1.FabricClientGameTest;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.client.input.MouseButtonInfo;
import net.minecraft.client.resources.language.I18n;
import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.EntitySpawnReason;
import net.minecraft.world.level.block.Blocks;

/** Echte Karte: Menü öffnen, Rechteck ziehen, Serverbestätigung, gespeicherte Farben und Weltauswahl. */
public final class ZoneDutyClientTest implements FabricClientGameTest {
    @Override public void runTest(ClientGameTestContext context) {
        var botId = new AtomicReference<UUID>();
        context.runOnClient(mc -> CompanionDebugOverlay.enabled = false);
        try (var world = context.worldBuilder().create()) {
            world.getConnection().waitForChunksDownload();
            var server = world.getServer();
            server.runOnServer(s -> {
                var p = s.getPlayerList().getPlayers().getFirst(); var sl = p.level();
                for (int x = -24; x <= 24; x++) for (int z = -24; z <= 24; z++) {
                    sl.setBlock(new BlockPos(x, 80, z), Blocks.GRASS_BLOCK.defaultBlockState(), 3);
                    for (int y = 81; y <= 90; y++) sl.setBlock(new BlockPos(x, y, z), Blocks.AIR.defaultBlockState(), 3);
                }
                for (int x = -14; x <= -9; x++) for (int z = -3; z <= 3; z++)
                    sl.setBlock(new BlockPos(x, 80, z), Blocks.WATER.defaultBlockState(), 3);
                for (int x = 8; x <= 13; x++) for (int z = 2; z <= 7; z++)
                    sl.setBlock(new BlockPos(x, 80, z), Blocks.FARMLAND.defaultBlockState(), 3);
                for (int x = -8; x <= -4; x++) for (int z = 8; z <= 12; z++)
                    sl.setBlock(new BlockPos(x, 81, z), Blocks.OAK_PLANKS.defaultBlockState(), 3);
                p.teleportTo(sl, 0.5, 81, -8.5, java.util.Set.of(), 0, 35, true);
                var c = CompanionMod.COMPANION.create(sl, EntitySpawnReason.COMMAND);
                c.setOwner(p.getUUID()); c.stay(); c.setNoAi(true); c.snapTo(2.5, 81, -8.5, 0, 0);
                sl.addFreshEntity(c); botId.set(c.getUUID()); c.openPanel(p, CompanionMenu.Page.DUTIES);
            });
            context.waitForScreen(CompanionScreen.class);
            context.waitTicks(3);
            context.takeScreenshot("zone-duties-roaming");
            context.clickScreenButton(label(context, "gui.begleiter.duties.tab.1"));
            context.waitTicks(2); context.takeScreenshot("zone-duties-work");
            for (String language : new String[] {"de_de", "en_us"}) {
                var reload = new AtomicReference<CompletableFuture<Void>>();
                context.runOnClient(mc -> {
                    mc.options.languageCode = language; mc.getLanguageManager().setSelected(language); reload.set(mc.reloadResourcePacks());
                });
                context.waitFor(mc -> reload.get().isDone() && mc.gui.overlay() == null);
                context.clickScreenButton(label(context, "gui.begleiter.action.zones"));
                context.waitForScreen(ZoneMapScreen.class);
                context.waitFor(mc -> mc.gui.screen() instanceof ZoneMapScreen map && !map.sampling()); context.waitTicks(5);
                context.runOnClient(mc -> {
                    var screen = (ZoneMapScreen) mc.gui.screen();
                    // Die Karte ist bei allen Fenstergrößen links oben im Panel verankert.
                    int pw = Math.min(334, screen.width - 8), ph = Math.min(270, screen.height - 8);
                    int size = Math.min(192, Math.min(pw - 138, ph - 70));
                    int x = (screen.width - pw) / 2 + 8 + size / 2;
                    int y = (screen.height - ph) / 2 + 28 + size / 2;
                    var start = new MouseButtonEvent(x - size / 12., y - size / 12., new MouseButtonInfo(1, 0));
                    var end = new MouseButtonEvent(x + size / 12., y + size / 12., new MouseButtonInfo(1, 0));
                    screen.mouseClicked(start, false); screen.mouseDragged(end, size / 6., size / 6.); screen.mouseReleased(end);
                });
                context.waitFor(mc -> CompanionZoneClient.ready()); context.waitTicks(2);
                context.runOnClient(mc -> {
                    var screen = (ZoneMapScreen) mc.gui.screen();
                    int x = screen.width / 2 - 60, y = screen.height / 2;
                    screen.mouseScrolled(x, y, 0, 1);
                    screen.keyPressed(new net.minecraft.client.input.KeyEvent(InputConstants.KEY_RIGHT, 0, 0));
                    screen.keyPressed(new net.minecraft.client.input.KeyEvent(InputConstants.KEY_LEFT, 0, 0));
                    screen.mouseScrolled(x, y, 0, -1);
                });
                context.waitFor(mc -> mc.gui.screen() instanceof ZoneMapScreen map && !map.sampling());
                var mapTexture = context.computeOnClient(mc -> (net.minecraft.client.renderer.texture.DynamicTexture)
                        mc.getTextureManager().getTexture(((ZoneMapScreen) mc.gui.screen()).mapTexture()));
                context.runOnClient(mc -> mc.resizeGui());
                context.waitFor(mc -> mc.gui.screen() instanceof ZoneMapScreen map && !map.sampling());
                context.runOnClient(mc -> {
                    if (mc.getTextureManager().getTexture(((ZoneMapScreen) mc.gui.screen()).mapTexture()) != mapTexture)
                        throw new AssertionError("Resizing discarded the terrain cache");
                });
                context.takeScreenshot("zone-map-selected-" + language);
                context.clickScreenButton(label(context, "gui.begleiter.zones.save"));
                context.waitFor(mc -> CompanionZoneClient.session() == null && mc.gui.screen() == null);
                context.runOnClient(mc -> {
                    if (mapTexture.getPixels() != null && !mapTexture.getPixels().isClosed())
                        throw new AssertionError("Closing the map leaked its native image");
                });
                server.runOnServer(s -> {
                    var p = s.getPlayerList().getPlayers().getFirst(); var c = (CompanionEntity) p.level().getEntity(botId.get());
                    if (c.zones.get(Kind.BASE, p.level()) == null) throw new AssertionError("Map selection not saved");
                    c.openPanel(p, CompanionMenu.Page.DUTIES);
                });
                context.waitForScreen(CompanionScreen.class);
            }
            context.clickScreenButton(label(context, "gui.begleiter.action.zones")); context.waitForScreen(ZoneMapScreen.class);
                context.waitFor(mc -> mc.gui.screen() instanceof ZoneMapScreen map && !map.sampling());
            context.takeScreenshot("zone-map-saved-colors");
            context.clickScreenButton(context.computeOnClient(mc -> Kind.ROAM.title().getString()));
            context.clickScreenButton(label(context, "gui.begleiter.zones.world"));
            context.waitFor(mc -> mc.gui.screen() == null);
            context.getInput().pressKey(InputConstants.KEY_Z);
            context.waitFor(mc -> CompanionZoneClient.first() != null);
            context.waitTicks(2); context.getInput().pressKey(InputConstants.KEY_Z);
            context.waitFor(mc -> CompanionZoneClient.second() != null);
            context.takeScreenshot("zone-world-selection");
            context.getInput().pressKey(InputConstants.KEY_G);
            context.waitFor(mc -> CompanionZoneClient.session() == null);
            server.runOnServer(s -> {
                var p = s.getPlayerList().getPlayers().getFirst(); var c = (CompanionEntity) p.level().getEntity(botId.get());
                if (c.zones.get(Kind.ROAM, p.level()) == null) throw new AssertionError("World selection not saved");
                c.openPanel(p, CompanionMenu.Page.DUTIES);
            });
            context.waitForScreen(CompanionScreen.class);
            context.clickScreenButton(label(context, "gui.begleiter.action.zones")); context.waitForScreen(ZoneMapScreen.class);
                context.waitFor(mc -> mc.gui.screen() instanceof ZoneMapScreen map && !map.sampling());
            context.clickScreenButton(label(context, "gui.begleiter.zones.remove"));
            context.waitFor(mc -> CompanionZoneClient.session() == null);
            server.runOnServer(s -> {
                var p = s.getPlayerList().getPlayers().getFirst(); var c = (CompanionEntity) p.level().getEntity(botId.get());
                if (c.zones.get(Kind.BASE, p.level()) != null || c.zones.get(Kind.ROAM, p.level()) == null)
                    throw new AssertionError("Deleting a zone changed the wrong zones");
            });
            server.runOnServer(s -> {
                var p = s.getPlayerList().getPlayers().getFirst(); var c = (CompanionEntity) p.level().getEntity(botId.get());
                p.level().setBlock(new BlockPos(100, 80, -8), Blocks.STONE.defaultBlockState(), 3);
                c.snapTo(100.5, 81, -7.5, 0, 0); c.stay();
                if (p.distanceToSqr(c) < 144) throw new AssertionError("Remote test is still nearby");
            });
            context.getInput().pressKey(InputConstants.KEY_B);
            context.waitForScreen(CompanionScreen.class); context.waitTicks(12);
            context.runOnClient(mc -> ((CompanionScreen) mc.gui.screen()).send(CompanionMenu.PAGE_BUTTON + CompanionMenu.Page.DUTIES.id));
            context.waitFor(mc -> mc.gui.screen() instanceof CompanionScreen screen && screen.container().page() == CompanionMenu.Page.DUTIES);
            context.takeScreenshot("zone-remote-menu");
            context.clickScreenButton(label(context, "gui.begleiter.action.zones"));
            context.waitForScreen(ZoneMapScreen.class);
            context.waitFor(mc -> mc.gui.screen() instanceof ZoneMapScreen map && !map.sampling());
            context.takeScreenshot("zone-remote-map");
            context.getInput().pressKey(InputConstants.KEY_ESCAPE);
            context.waitFor(mc -> mc.gui.screen() == null && CompanionZoneClient.session() == null);
        }
    }
    private static String label(ClientGameTestContext context, String key) { return context.computeOnClient(mc -> I18n.get(key)); }
}
