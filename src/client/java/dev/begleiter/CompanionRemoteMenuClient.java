package dev.begleiter;

import com.mojang.blaze3d.platform.InputConstants;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.keymapping.v1.KeyMappingHelper;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.minecraft.client.KeyMapping;

/** Frei belegbare Taste zum Öffnen des eigenen Begleitermenüs, auch außerhalb der Client-Sichtweite. */
public final class CompanionRemoteMenuClient {
    private CompanionRemoteMenuClient() {}
    public static void register() {
        var category = KeyMapping.Category.register(CompanionMod.id("control"));
        var open = KeyMappingHelper.registerKeyMapping(new KeyMapping("key.begleiter.open_menu", InputConstants.KEY_B, category));
        ClientTickEvents.END_CLIENT_TICK.register(mc -> {
            boolean pressed = false;
            while (open.consumeClick()) pressed = true;
            if (!pressed || mc.player == null || !mc.player.isAlive() || mc.gui.screen() != null) return;
            if (ClientPlayNetworking.canSend(CompanionRemoteMenu.Request.TYPE))
                ClientPlayNetworking.send(new CompanionRemoteMenu.Request());
            else mc.gui.chatListener().handleSystemMessage(CompanionTexts.prefixed(CompanionTexts.tr("remote.client_required")), false);
        });
    }
}
