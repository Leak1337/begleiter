package dev.begleiter;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import net.fabricmc.fabric.api.networking.v1.PayloadTypeRegistry;
import net.fabricmc.fabric.api.networking.v1.ServerPlayConnectionEvents;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.minecraft.core.BlockPos;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.ComponentSerialization;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import dev.begleiter.CompanionZones.Kind;
import dev.begleiter.CompanionZones.Zone;

/** Eine kurzlebige, an Besitzer, Begleiter und Dimension gebundene Karten-/Weltauswahl. */
public final class CompanionZoneEditor {
    private CompanionZoneEditor() {}
    public static final int REACH = 96;
    private record Pending(UUID token, UUID bot, ServerLevel level, long expires) {}
    private static final Map<UUID, Pending> PENDING = new HashMap<>();
    public record Open(UUID token, BlockPos anchor, List<Zone> zones) implements CustomPacketPayload {
        public static final Type<Open> TYPE = new Type<>(CompanionMod.id("zone_open"));
        public static final StreamCodec<RegistryFriendlyByteBuf, Open> CODEC = new StreamCodec<>() {
            public Open decode(RegistryFriendlyByteBuf b) {
                UUID token = b.readUUID(); BlockPos anchor = b.readBlockPos(); int count = b.readVarInt();
                if (count < 0 || count > Kind.values().length) throw new IllegalArgumentException("Invalid zone count");
                var zones = new java.util.ArrayList<Zone>();
                for (int i = 0; i < count; i++) zones.add(new Zone(b.readEnum(Kind.class), b.readUtf(128), b.readBlockPos(), b.readBlockPos()));
                return new Open(token, anchor, List.copyOf(zones));
            }
            public void encode(RegistryFriendlyByteBuf b, Open p) {
                b.writeUUID(p.token); b.writeBlockPos(p.anchor); b.writeVarInt(p.zones.size());
                for (Zone z : p.zones) { b.writeEnum(z.kind()); b.writeUtf(z.dimension(), 128); b.writeBlockPos(z.min()); b.writeBlockPos(z.max()); }
            }
        };
        public Type<Open> type() { return TYPE; }
    }
    /** operation: 0 speichern, 1 ausgewählte Zone löschen, 2 abbrechen. */
    public record Edit(UUID token, Kind kind, BlockPos first, BlockPos second, int operation) implements CustomPacketPayload {
        public static final Type<Edit> TYPE = new Type<>(CompanionMod.id("zone_edit"));
        public static final StreamCodec<RegistryFriendlyByteBuf, Edit> CODEC = new StreamCodec<>() {
            public Edit decode(RegistryFriendlyByteBuf b) { return new Edit(b.readUUID(), b.readEnum(Kind.class), b.readBlockPos(), b.readBlockPos(), b.readVarInt()); }
            public void encode(RegistryFriendlyByteBuf b, Edit p) { b.writeUUID(p.token); b.writeEnum(p.kind); b.writeBlockPos(p.first); b.writeBlockPos(p.second); b.writeVarInt(p.operation); }
        };
        public Type<Edit> type() { return TYPE; }
    }
    public record Feedback(UUID token, boolean finished, Component message) implements CustomPacketPayload {
        public static final Type<Feedback> TYPE = new Type<>(CompanionMod.id("zone_feedback"));
        public static final StreamCodec<RegistryFriendlyByteBuf, Feedback> CODEC = new StreamCodec<>() {
            public Feedback decode(RegistryFriendlyByteBuf b) { return new Feedback(b.readUUID(), b.readBoolean(), ComponentSerialization.STREAM_CODEC.decode(b)); }
            public void encode(RegistryFriendlyByteBuf b, Feedback p) { b.writeUUID(p.token); b.writeBoolean(p.finished); ComponentSerialization.STREAM_CODEC.encode(b, p.message); }
        };
        public Type<Feedback> type() { return TYPE; }
    }
    public static void register() {
        PayloadTypeRegistry.clientboundPlay().register(Open.TYPE, Open.CODEC);
        PayloadTypeRegistry.clientboundPlay().register(Feedback.TYPE, Feedback.CODEC);
        PayloadTypeRegistry.serverboundPlay().register(Edit.TYPE, Edit.CODEC);
        ServerPlayNetworking.registerGlobalReceiver(Edit.TYPE, (p, ctx) -> ctx.server().execute(() ->
                ServerPlayNetworking.send(ctx.player(), edit(ctx.player(), p))));
        ServerPlayConnectionEvents.DISCONNECT.register((handler, server) -> PENDING.remove(handler.getPlayer().getUUID()));
    }
    public static void reset() { PENDING.clear(); }
    static Open select(ServerPlayer p, CompanionEntity c) {
        if (!CompanionAccess.canUseMenu(p, c)) return null;
        UUID token = UUID.randomUUID();
        PENDING.put(p.getUUID(), new Pending(token, c.getUUID(), p.level(), p.level().getGameTime() + 6000));
        return new Open(token, p.blockPosition(), c.zones.all());
    }
    static Component begin(ServerPlayer p, CompanionEntity c) {
        if (p == null || !ServerPlayNetworking.canSend(p, Open.TYPE)) return CompanionTexts.tr("zone.client_required");
        Open payload = select(p, c);
        if (payload == null) return CompanionTexts.tr("zone.denied");
        p.closeContainer();
        ServerPlayNetworking.send(p, payload);
        return null;
    }
    static Feedback edit(ServerPlayer p, Edit request) {
        Pending pending = PENDING.get(p.getUUID());
        if (pending == null || !pending.token.equals(request.token)) return feedback(request, true, "zone.expired");
        if (p.level() != pending.level || p.level().getGameTime() > pending.expires || !p.isAlive()
                || !(pending.level.getEntity(pending.bot) instanceof CompanionEntity c) || !c.isAlive() || !c.isOwner(p)) {
            PENDING.remove(p.getUUID());
            return feedback(request, true, "zone.expired");
        }
        if (request.operation == 2) { PENDING.remove(p.getUUID()); return feedback(request, true, "zone.cancelled"); }
        if (request.operation != 0 && request.operation != 1) return feedback(request, false, "zone.denied");
        if (request.operation == 0) {
            if (request.first.distSqr(p.blockPosition()) > REACH * REACH || request.second.distSqr(p.blockPosition()) > REACH * REACH)
                return feedback(request, false, "zone.too_far");
            Zone z = CompanionZones.between(request.kind, CompanionMemory.dim(pending.level), request.first, request.second);
            if (!z.valid()) return feedback(request, false, "zone.too_large");
            if (!loaded(pending.level, z)) return feedback(request, false, "zone.unloaded");
            c.zones.put(z);
        } else c.zones.remove(request.kind);
        // Alte automatisch geplante Arbeit nicht im neu markierten Bereich fortsetzen.
        if (c.duty() != null) { c.holdOrdersComponent(); c.stay(); }
        PENDING.remove(p.getUUID());
        return new Feedback(request.token, true, CompanionTexts.tr(request.operation == 0 ? "zone.saved" : "zone.removed", request.kind.title()));
    }
    private static Feedback feedback(Edit request, boolean finished, String key) { return new Feedback(request.token, finished, CompanionTexts.tr(key)); }
    static boolean loaded(ServerLevel sl, Zone z) {
        if (sl.isOutsideBuildHeight(z.min()) || sl.isOutsideBuildHeight(z.max())
                || !sl.getWorldBorder().isWithinBounds(z.min()) || !sl.getWorldBorder().isWithinBounds(z.max())) return false;
        for (int x = z.min().getX() >> 4; x <= z.max().getX() >> 4; x++)
            for (int zz = z.min().getZ() >> 4; zz <= z.max().getZ() >> 4; zz++)
                if (!sl.hasChunkAt(new BlockPos(x * 16, z.min().getY(), zz * 16))) return false;
        return true;
    }
}
