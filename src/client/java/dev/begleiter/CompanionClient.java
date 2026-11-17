package dev.begleiter;

import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.rendering.v1.EntityRendererRegistry;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.minecraft.client.gui.screens.MenuScreens;
import dev.begleiter.client.gui.CompanionScreen;

public class CompanionClient implements ClientModInitializer {
    @Override
    public void onInitializeClient() {
        MenuScreens.register(CompanionMod.CONTROL_MENU, CompanionScreen::new);
        net.fabricmc.fabric.api.client.rendering.v1.PictureInPictureRendererRegistry.register(
                context -> new dev.begleiter.client.gui.BuildPreviewRenderer<>(dev.begleiter.client.gui.BuildPreviewRenderer.State.class));
        net.fabricmc.fabric.api.client.rendering.v1.PictureInPictureRendererRegistry.register(
                context -> new dev.begleiter.client.gui.BuildPreviewRenderer<>(dev.begleiter.client.gui.BuildPreviewRenderer.Card0.class));
        net.fabricmc.fabric.api.client.rendering.v1.PictureInPictureRendererRegistry.register(
                context -> new dev.begleiter.client.gui.BuildPreviewRenderer<>(dev.begleiter.client.gui.BuildPreviewRenderer.Card1.class));
        net.fabricmc.fabric.api.client.rendering.v1.PictureInPictureRendererRegistry.register(
                context -> new dev.begleiter.client.gui.BuildPreviewRenderer<>(dev.begleiter.client.gui.BuildPreviewRenderer.Card2.class));
        net.fabricmc.fabric.api.client.rendering.v1.PictureInPictureRendererRegistry.register(
                context -> new dev.begleiter.client.gui.BuildPreviewRenderer<>(dev.begleiter.client.gui.BuildPreviewRenderer.Card3.class));
        ClientPlayNetworking.registerGlobalReceiver(CompanionBuildPreview.TYPE, (preview, context) ->
                context.client().execute(() -> {
                    if (context.client().gui.screen() instanceof CompanionScreen screen) screen.showBuildPreview(preview);
                }));
        ClientPlayNetworking.registerGlobalReceiver(CompanionSnapshot.TYPE, (snapshot, context) ->
                context.client().execute(() -> {
                    if (context.client().player != null
                            && context.client().player.containerMenu instanceof CompanionMenu menu)
                        menu.acceptSnapshot(snapshot);
                }));
        EntityRendererRegistry.register(CompanionMod.COMPANION, CompanionRenderer::new);
        CompanionPlacementClient.register();
        CompanionZoneClient.register();
        CompanionRemoteMenuClient.register();
        dev.begleiter.debug.BotDebugClient.register();
        CompanionDebugOverlay.register(); // Debug-HUD und Bot-ESP – zum Entfernen diese Zeilen + CompanionDebugOverlay löschen
        // Deutsche Item-Namen für die Chat-Erkennung aus dem installierten Spiel statt aus einer mitgelieferten Kopie.
        CompanionItemNames.addLanguageSource(CompanionClient::vanillaGerman);
    }

    private static java.util.List<com.google.gson.JsonObject> vanillaGerman() {
        var mc = net.minecraft.client.Minecraft.getInstance();
        if (mc == null) return java.util.List.of();
        var id = net.minecraft.resources.Identifier.withDefaultNamespace("lang/de_de.json");
        var out = new java.util.ArrayList<com.google.gson.JsonObject>();
        for (var resource : mc.getResourceManager().getResourceStack(id)) {
            try (var reader = resource.openAsReader()) {
                out.add(com.google.gson.JsonParser.parseReader(reader).getAsJsonObject());
            } catch (java.io.IOException | RuntimeException ignored) { }
        }
        return out;
    }
}
