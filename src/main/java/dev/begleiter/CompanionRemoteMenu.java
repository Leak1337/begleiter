package dev.begleiter;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import net.fabricmc.fabric.api.networking.v1.PayloadTypeRegistry;
import net.fabricmc.fabric.api.networking.v1.ServerPlayConnectionEvents;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.server.level.ServerPlayer;

/** Besitzergebundener Fernzugriff auf geladene Begleiter; keine Chunks laden und keine Client-Ziel-ID vertrauen. */
public final class CompanionRemoteMenu {
    private CompanionRemoteMenu() {}
    private static final Map<UUID, UUID> LAST = new HashMap<>();
    private static final Map<UUID, Integer> NEXT_OPEN = new HashMap<>();
    public record Request() implements CustomPacketPayload {
        public static final Type<Request> TYPE = new Type<>(CompanionMod.id("open_remote_menu"));
        public static final StreamCodec<RegistryFriendlyByteBuf, Request> CODEC = StreamCodec.unit(new Request());
        public Type<Request> type() { return TYPE; }
    }
    public static void register() {
        PayloadTypeRegistry.serverboundPlay().register(Request.TYPE, Request.CODEC);
        ServerPlayNetworking.registerGlobalReceiver(Request.TYPE, (request, ctx) -> ctx.server().execute(() -> open(ctx.player())));
        ServerPlayConnectionEvents.DISCONNECT.register((handler, server) -> {
            UUID player = handler.getPlayer().getUUID(); LAST.remove(player); NEXT_OPEN.remove(player);
        });
    }
    public static void reset() { LAST.clear(); NEXT_OPEN.clear(); }
    static void remember(ServerPlayer player, CompanionEntity companion) { LAST.put(player.getUUID(), companion.getUUID()); }
    static CompanionEntity find(ServerPlayer player) {
        UUID previous = LAST.get(player.getUUID());
        if (previous != null && player.level().getEntity(previous) instanceof CompanionEntity c
                && CompanionAccess.canControl(player, c)) return c;
        CompanionEntity best = null;
        double distance = Double.MAX_VALUE;
        for (var entity : player.level().getAllEntities()) {
            if (!(entity instanceof CompanionEntity c) || !CompanionAccess.canControl(player, c)) continue;
            double d = player.distanceToSqr(c);
            if (d < distance || d == distance && best != null && c.getUUID().compareTo(best.getUUID()) < 0) {
                best = c; distance = d;
            }
        }
        return best;
    }
    static void open(ServerPlayer player) {
        int now = player.level().getServer().getTickCount();
        if (!player.isAlive() || now < NEXT_OPEN.getOrDefault(player.getUUID(), Integer.MIN_VALUE)) return;
        NEXT_OPEN.put(player.getUUID(), now + 10);
        if (player.containerMenu != player.inventoryMenu) return;
        CompanionEntity c = find(player);
        if (c == null) player.sendSystemMessage(CompanionTexts.prefixed(CompanionTexts.tr("remote.none")));
        else c.openPanel(player, CompanionMenu.Page.MAIN, true);
    }
}
