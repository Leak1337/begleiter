package dev.begleiter;

import com.mojang.blaze3d.platform.InputConstants;
import dev.begleiter.client.gui.CompanionScreen;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;
import net.fabricmc.fabric.api.client.gametest.v1.FabricClientGameTest;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.EntitySpawnReason;
import net.minecraft.world.level.block.Blocks;

/** Menü → echte Vorschau → Tastendrehung/Höhe → Serverbestätigung und Abbruch. */
public final class FarmPlacementClientTest implements FabricClientGameTest {
    @Override public void runTest(ClientGameTestContext context) {
        context.runOnClient(mc -> { CompanionDebugOverlay.enabled = false; mc.options.guiScale().set(1); mc.resizeGui(); });
        AtomicReference<UUID> botId = new AtomicReference<>();
        AtomicReference<java.nio.file.Path> fixture = new AtomicReference<>();
        String fixtureName = "menu_preview_" + UUID.randomUUID().toString().replace("-", "");
        try (var world = context.worldBuilder().create()) {
            world.getConnection().waitForChunksDownload();
            var server = world.getServer();
            server.runOnServer(mcServer -> {
                var player = mcServer.getPlayerList().getPlayers().getFirst();
                var level = player.level();
                for (int x = -16; x <= 16; x++) for (int z = -20; z <= 16; z++) {
                    level.setBlock(new BlockPos(x, 80, z), Blocks.DIRT.defaultBlockState(), CompanionBuilder.PLACE_FLAGS);
                    for (int y = 81; y <= 90; y++) level.setBlock(new BlockPos(x, y, z), Blocks.AIR.defaultBlockState(), CompanionBuilder.PLACE_FLAGS);
                }
                player.teleportTo(level, 0.5, 81, -12.5, java.util.Set.of(), 0, 12, true);
                var bot = CompanionMod.COMPANION.create(level, EntitySpawnReason.COMMAND);
                if (bot == null) throw new AssertionError("Bot missing");
                bot.setOwner(player.getUUID()); bot.setNoAi(true); bot.stay();
                bot.snapTo(3.5, 81, -12.5, 0, 0); level.addFreshEntity(bot); botId.set(bot.getUUID());
                bot.openPanel(player, CompanionMenu.Page.BUILD);
                // All four requests deliberately arrive in the SAME server tick, as they can under network lag.
                var menu = (CompanionMenu) player.containerMenu;
                for (int i = 0; i < CompanionMenu.SCHEM_COUNT; i++)
                    if (!menu.clickMenuButton(player, CompanionMenu.BUILD_THUMBNAIL_BUTTON + i))
                        throw new AssertionError("Thumbnail burst dropped slot " + i);
            });
            context.waitForScreen(CompanionScreen.class);
            long loadStart = System.nanoTime();
            waitForThumbnails(context);
            System.out.println("[BuildPreviewTiming] First page ready after " + (System.nanoTime() - loadStart) / 1_000_000 + " ms");
            var firstPage = context.computeOnClient(mc -> {
                var screen = (CompanionScreen) mc.gui.screen();
                return java.util.stream.IntStream.range(0, CompanionMenu.SCHEM_COUNT)
                        .mapToObj(screen::buildThumbnail).toArray(dev.begleiter.client.gui.BuildPreviewModel[]::new);
            });
            String[] firstTitles = context.computeOnClient(mc -> java.util.stream.IntStream.range(0, CompanionMenu.SCHEM_COUNT)
                    .mapToObj(i -> ((CompanionScreen) mc.gui.screen()).container().getSlot(CompanionMenu.SCHEM_FIRST + i)
                            .getItem().getHoverName().getString()).toArray(String[]::new));
            click(context, 17);
            context.waitFor(mc -> java.util.stream.IntStream.range(0, CompanionMenu.SCHEM_COUNT).allMatch(i ->
                    !((CompanionScreen) mc.gui.screen()).container().getSlot(CompanionMenu.SCHEM_FIRST + i)
                            .getItem().getHoverName().getString().equals(firstTitles[i])));
            waitForThumbnails(context);
            click(context, 15);
            context.waitFor(mc -> java.util.stream.IntStream.range(0, CompanionMenu.SCHEM_COUNT).allMatch(i ->
                    ((CompanionScreen) mc.gui.screen()).container().getSlot(CompanionMenu.SCHEM_FIRST + i)
                            .getItem().getHoverName().getString().equals(firstTitles[i])));
            context.waitTicks(1);
            context.runOnClient(mc -> {
                var screen = (CompanionScreen) mc.gui.screen();
                for (int i = 0; i < firstPage.length; i++)
                    if (screen.buildThumbnail(i) != firstPage[i])
                        throw new AssertionError("Returning to a page " + (screen.buildThumbnail(i) == null ? "lost" : "rebuilt") + " thumbnail " + i);
            });
            System.out.println("[BuildPreviewTiming] Returning to the previous page reused all four models");
            context.runOnClient(mc -> ((CompanionScreen) mc.gui.screen()).send(CompanionMenu.BUILD_PANEL_BUTTON + 16));
            waitForThumbnails(context);
            context.runOnClient(mc -> {
                var screen = (CompanionScreen) mc.gui.screen();
                for (int i = 0; i < firstPage.length; i++)
                    if (screen.buildThumbnail(i) == firstPage[i])
                        throw new AssertionError("Refresh kept stale thumbnail " + i);
            });
            context.waitTicks(3);
            context.takeScreenshot("build-catalogue-3d-cards");
            context.runOnClient(mc -> { mc.options.guiScale().set(2); mc.resizeGui(); });
            context.waitTicks(3);
            context.takeScreenshot("build-catalogue-compact");
            context.runOnClient(mc -> { mc.options.guiScale().set(1); mc.resizeGui(); });
            context.waitTicks(3);
            context.clickScreenButton(context.computeOnClient(mc -> net.minecraft.client.resources.language.I18n.get("gui.begleiter.build.options")));
            context.waitTicks(3);
            context.takeScreenshot("build-catalogue-options");
            context.clickScreenButton(context.computeOnClient(mc -> net.minecraft.client.resources.language.I18n.get("gui.begleiter.build.catalogue")));
            click(context, 18);
            waitForModel(context);
            context.takeScreenshot("build-menu-sugar-cane-3d");
            var preparedSugarCane = context.computeOnClient(mc -> ((CompanionScreen) mc.gui.screen()).currentBuildPreviewModel());
            context.getInput().pressKey(InputConstants.KEY_ESCAPE);
            context.waitFor(mc -> mc.gui.screen() instanceof CompanionScreen s && !s.hasBuildPreview());
            click(context, 18);
            waitForModel(context);
            context.runOnClient(mc -> {
                if (((CompanionScreen) mc.gui.screen()).currentBuildPreviewModel() != preparedSugarCane)
                    throw new AssertionError("Reopening the same plan rebuilt its 3D model");
            });
            context.getInput().pressKey(InputConstants.KEY_ESCAPE);
            context.waitFor(mc -> mc.gui.screen() instanceof CompanionScreen s && !s.hasBuildPreview());
            // Mit den echten Katalogknöpfen zum Kürbisfarm-Plan blättern.
            for (int i = 0; i < 17 / CompanionMenu.SCHEM_COUNT; i++) {
                click(context, 17);
                context.waitTicks(3);
            }
            int pumpkinSlot = CompanionMenu.SCHEM_FIRST + 17 % CompanionMenu.SCHEM_COUNT;
            click(context, pumpkinSlot);
            waitForModel(context);
            context.takeScreenshot("build-menu-pumpkin-3d");
            context.runOnClient(mc -> {
                var screen = (CompanionScreen) mc.gui.screen();
                var event = new net.minecraft.client.input.MouseButtonEvent(screen.panelX() + 150, screen.panelY() + 100,
                        new net.minecraft.client.input.MouseButtonInfo(1, 0));
                screen.mouseClicked(event, false);
                screen.mouseDragged(event, 48, 15);
                screen.mouseReleased(event);
                screen.mouseScrolled(event.x(), event.y(), 0, 2);
            });
            context.waitTicks(3);
            context.takeScreenshot("build-menu-pumpkin-3d-rotated-zoomed");
            context.getInput().pressKey(InputConstants.KEY_ESCAPE);
            context.waitFor(mc -> mc.gui.screen() instanceof CompanionScreen s && !s.hasBuildPreview());
            server.runOnServer(mcServer -> {
                var bot = (CompanionEntity) mcServer.getPlayerList().getPlayers().getFirst().level().getEntity(botId.get());
                if (bot.build != null) throw new AssertionError("Inspecting the 3D model started construction");
            });
            click(context, pumpkinSlot);
            placeModel(context);
            context.waitFor(mc -> mc.gui.screen() == null && CompanionPlacementClient.active() && CompanionPlacementClient.center() != null);
            server.runOnServer(mcServer -> {
                var bot = (CompanionEntity) mcServer.getPlayerList().getPlayers().getFirst().level().getEntity(botId.get());
                if (bot.build != null) throw new AssertionError("Selecting a farm started construction");
            });
            int rotation = context.computeOnClient(mc -> CompanionPlacementClient.rotation());
            context.getInput().pressKey(InputConstants.KEY_R);
            context.waitFor(mc -> CompanionPlacementClient.rotation() == (rotation + 1) % 4);
            int oldY = context.computeOnClient(mc -> CompanionPlacementClient.center().getY());
            context.getInput().pressKey(InputConstants.KEY_PAGEUP);
            context.waitFor(mc -> CompanionPlacementClient.center() != null && CompanionPlacementClient.center().getY() == oldY + 1);
            context.waitTicks(3);
            context.takeScreenshot("farm-placement-rotated-raised");
            context.getInput().pressKey(InputConstants.KEY_PAGEDOWN);
            context.waitFor(mc -> CompanionPlacementClient.ready() && CompanionPlacementClient.center().getY() == oldY);
            context.takeScreenshot("farm-placement-ready");
            context.getInput().pressKey(InputConstants.KEY_RETURN);
            context.waitFor(mc -> !CompanionPlacementClient.active());
            server.runOnServer(mcServer -> {
                var player = mcServer.getPlayerList().getPlayers().getFirst();
                var bot = (CompanionEntity) player.level().getEntity(botId.get());
                if (bot.build == null || !bot.build.name.equals("kuerbisfarm")) throw new AssertionError("Confirmation did not start selected farm");
                bot.openPanel(player, CompanionMenu.Page.BUILD);
            });
            context.waitForScreen(CompanionScreen.class);
            // Survival/Kreativ-Knopf der Bauen-Seite: echter Mausklick, der Server schaltet um.
            boolean creativeBefore = context.computeOnClient(mc -> ((CompanionScreen) mc.gui.screen()).data().creativeBuild());
            context.waitTicks(3);
            context.takeScreenshot("build-page-mode-button");
            context.runOnClient(mc -> {
                // Mitte des Knopfs (233,163, 65x20); in 26.3 ist MouseButtonInfo 1 die linke Taste
                var screen = (CompanionScreen) mc.gui.screen();
                var event = new net.minecraft.client.input.MouseButtonEvent(screen.panelX() + (screen.wideBuild() ? 385 : 265), screen.panelY() + (screen.wideBuild() ? 255 : 173),
                        new net.minecraft.client.input.MouseButtonInfo(1, 0));
                screen.mouseClicked(event, false); screen.mouseReleased(event);
            });
            context.waitFor(mc -> mc.gui.screen() instanceof CompanionScreen s && s.data().creativeBuild() != creativeBefore);
            server.runOnServer(mcServer -> {
                var bot = (CompanionEntity) mcServer.getPlayerList().getPlayers().getFirst().level().getEntity(botId.get());
                if (bot.creativeHelper() == creativeBefore) throw new AssertionError("Build mode button did not switch");
            });
            context.waitTicks(3);
            context.takeScreenshot("build-page-mode-switched");
            click(context, pumpkinSlot);
            placeModel(context);
            context.waitFor(mc -> mc.gui.screen() == null && CompanionPlacementClient.active());
            context.getInput().pressKey(InputConstants.KEY_BACKSPACE);
            context.waitFor(mc -> !CompanionPlacementClient.active());
            server.runOnServer(mcServer -> {
                var bot = (CompanionEntity) mcServer.getPlayerList().getPlayers().getFirst().level().getEntity(botId.get());
                if (bot.build == null || !bot.build.name.equals("kuerbisfarm")) throw new AssertionError("Cancelling replaced previous build");
                try { fixture.set(TestSchematics.writeStructure(fixtureName, TestSchematics.hut())); }
                catch (java.io.IOException ex) { throw new java.io.UncheckedIOException(ex); }
                bot.openPanel(mcServer.getPlayerList().getPlayers().getFirst(), CompanionMenu.Page.BUILD);
            });
            context.waitForScreen(CompanionScreen.class);
            int fileSlot = -1;
            for (int page = 0; page < 20 && fileSlot < 0; page++) {
                context.waitTicks(3);
                fileSlot = context.computeOnClient(mc -> {
                    var menu = ((CompanionScreen) mc.gui.screen()).container();
                    for (int i = 18; i < CompanionMenu.SCHEM_FIRST + CompanionMenu.SCHEM_COUNT; i++)
                        if (menu.getSlot(i).getItem().getHoverName().getString().contains(fixtureName)) return i;
                    return -1;
                });
                if (fileSlot < 0) click(context, 17);
            }
            if (fileSlot < 0) throw new AssertionError("Server schematic missing from catalogue");
            int thumbnailIndex = fileSlot - CompanionMenu.SCHEM_FIRST;
            context.waitFor(mc -> ((CompanionScreen) mc.gui.screen()).buildThumbnail(thumbnailIndex) != null);
            context.waitTicks(3);
            context.takeScreenshot("build-catalogue-imported-schematic");
            click(context, fileSlot);
            waitForModel(context);
            context.takeScreenshot("build-menu-schematic-3d");
            var originalFileModel = context.computeOnClient(mc -> ((CompanionScreen) mc.gui.screen()).currentBuildPreviewModel());
            context.getInput().pressKey(InputConstants.KEY_ESCAPE);
            context.waitFor(mc -> mc.gui.screen() instanceof CompanionScreen s && !s.hasBuildPreview());
            server.runOnServer(mcServer -> {
                try {
                    var changed = TestSchematics.hut();
                    changed.put(BlockPos.ZERO, Blocks.GOLD_BLOCK.defaultBlockState());
                    TestSchematics.writeStructure(fixtureName, changed);
                    java.nio.file.Files.setLastModifiedTime(fixture.get(), java.nio.file.attribute.FileTime.fromMillis(System.currentTimeMillis() + 1000));
                } catch (java.io.IOException ex) { throw new java.io.UncheckedIOException(ex); }
            });
            click(context, fileSlot);
            waitForModel(context);
            context.runOnClient(mc -> {
                var updated = ((CompanionScreen) mc.gui.screen()).currentBuildPreviewModel();
                if (updated == originalFileModel || !updated.plan().blocks().get(BlockPos.ZERO).is(Blocks.GOLD_BLOCK))
                    throw new AssertionError("Changed schematic reused stale 3D geometry");
            });
            // Eine Dateiänderung nach dem Ansehen darf den Platzierungsplan nicht ändern.
            server.runOnServer(mcServer -> {
                try { java.nio.file.Files.delete(fixture.get()); }
                catch (java.io.IOException ex) { throw new java.io.UncheckedIOException(ex); }
            });
            placeModel(context);
            context.waitFor(mc -> mc.gui.screen() == null && CompanionPlacementClient.active());
            context.getInput().pressKey(InputConstants.KEY_BACKSPACE);
            context.waitFor(mc -> !CompanionPlacementClient.active());
        } finally {
            if (fixture.get() != null) {
                try { java.nio.file.Files.deleteIfExists(fixture.get()); }
                catch (java.io.IOException ex) { throw new RuntimeException(ex); }
            }
        }
    }

    private static void waitForThumbnails(ClientGameTestContext context) {
        context.waitFor(mc -> mc.gui.screen() instanceof CompanionScreen s
                && java.util.stream.IntStream.range(0, CompanionMenu.SCHEM_COUNT).allMatch(i -> s.buildThumbnail(i) != null));
    }

    private static void waitForModel(ClientGameTestContext context) {
        context.waitFor(mc -> mc.gui.screen() instanceof CompanionScreen s && s.hasBuildPreview());
        context.waitFor(mc -> ((CompanionScreen) mc.gui.screen()).currentBuildPreviewModel().ready());
        context.waitTicks(3);
        if (context.computeOnClient(mc -> CompanionPlacementClient.active()))
            throw new AssertionError("Inspecting the model opened world placement");
    }

    private static void placeModel(ClientGameTestContext context) {
        waitForModel(context);
        context.clickScreenButton(context.computeOnClient(mc ->
                net.minecraft.network.chat.Component.translatable("gui.begleiter.build.preview.place").getString()));
    }

    private static void click(ClientGameTestContext context, int index) {
        context.runOnClient(mc -> {
            var screen = (CompanionScreen) mc.gui.screen();
            int card = index - CompanionMenu.SCHEM_FIRST;
            int x = index == 17 ? (screen.wideBuild() ? 403 : 287)
                    : index == 15 ? (screen.wideBuild() ? 377 : 261) : 110 + card % 2 * (screen.wideBuild() ? 158 : 96) + 40;
            int y = index == 17 || index == 15 ? 49 : (screen.wideBuild() ? 65 : 62) + card / 2 * (screen.wideBuild() ? 88 : 51) + 20;
            var event = new net.minecraft.client.input.MouseButtonEvent(screen.panelX() + x,
                    screen.panelY() + y, new net.minecraft.client.input.MouseButtonInfo(1, 0));
            screen.mouseClicked(event, false); screen.mouseReleased(event);
        });
    }
}
