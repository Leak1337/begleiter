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
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;

public final class CompanionWaypointMap {
    public static final int LIMIT = 64;
    private static final Map<UUID, Session> SESSIONS = new HashMap<>();
    private record Session(UUID token, UUID companion, ServerLevel level, long until, List<CompanionMemory.Place> points) {}
    public record Point(String name, int x, int y, int z) {}

    private CompanionWaypointMap() {}

    public record Open(UUID token, String dimension, BlockPos anchor, List<Point> points) implements CustomPacketPayload {
        public static final Type<Open> TYPE = new Type<>(CompanionMod.id("waypoint_map"));
        public Open {
            if (points.size() > LIMIT) throw new IllegalArgumentException("Too many waypoints");
            points = List.copyOf(points);
        }
        public static final StreamCodec<RegistryFriendlyByteBuf, Open> CODEC = new StreamCodec<>() {
            @Override public Open decode(RegistryFriendlyByteBuf b) {
                var token = b.readUUID(); var dimension = b.readUtf(128); var anchor = b.readBlockPos();
                int count = b.readVarInt();
                if (count < 0 || count > LIMIT) throw new IllegalArgumentException("Invalid waypoint count");
                var points = new java.util.ArrayList<Point>(count);
                for (int i = 0; i < count; i++) points.add(new Point(b.readUtf(128), b.readVarInt(), b.readVarInt(), b.readVarInt()));
                return new Open(token, dimension, anchor, points);
            }
            @Override public void encode(RegistryFriendlyByteBuf b, Open p) {
                b.writeUUID(p.token); b.writeUtf(p.dimension, 128); b.writeBlockPos(p.anchor); b.writeVarInt(p.points.size());
                for (var point : p.points) {
                    b.writeUtf(point.name, 128); b.writeVarInt(point.x); b.writeVarInt(point.y); b.writeVarInt(point.z);
                }
            }
        };
        @Override public Type<Open> type() { return TYPE; }
    }

    public record Select(UUID token, int index) implements CustomPacketPayload {
        public static final Type<Select> TYPE = new Type<>(CompanionMod.id("waypoint_select"));
        public static final StreamCodec<RegistryFriendlyByteBuf, Select> CODEC = new StreamCodec<>() {
            @Override public Select decode(RegistryFriendlyByteBuf b) { return new Select(b.readUUID(), b.readVarInt()); }
            @Override public void encode(RegistryFriendlyByteBuf b, Select p) { b.writeUUID(p.token); b.writeVarInt(p.index); }
        };
        @Override public Type<Select> type() { return TYPE; }
    }

    static void register() {
        PayloadTypeRegistry.clientboundPlay().register(Open.TYPE, Open.CODEC);
        PayloadTypeRegistry.serverboundPlay().register(Select.TYPE, Select.CODEC);
        ServerPlayNetworking.registerGlobalReceiver(Select.TYPE, (packet, ctx) -> ctx.server().execute(() ->
                ctx.player().sendSystemMessage(CompanionTexts.tr(select(ctx.player(), packet)
                        ? "waypoint.map.travel" : "waypoint.map.expired"))));
        ServerPlayConnectionEvents.DISCONNECT.register((handler, server) -> SESSIONS.remove(handler.getPlayer().getUUID()));
        net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents.SERVER_STOPPED.register(server ->
                SESSIONS.values().removeIf(session -> session.level.getServer() == server));
    }

    static Open prepare(ServerPlayer player, CompanionEntity companion) {
        if (!CompanionAccess.canUseMenu(player, companion)) return null;
        var level = player.level();
        var dimension = CompanionMemory.dim(level);
        var anchor = player.blockPosition();
        var points = companion.memory.places().stream().filter(point -> point.kind == CompanionMemory.Kind.WAYPOINT
                        && point.dim.equals(dimension) && point.key.length() <= 128)
                .sorted(java.util.Comparator.comparingDouble(point -> point.pos.distSqr(anchor))).limit(LIMIT).toList();
        UUID token = UUID.randomUUID();
        SESSIONS.put(player.getUUID(), new Session(token, companion.getUUID(), level, level.getGameTime() + 1200, points));
        return new Open(token, dimension, anchor, points.stream().map(point -> new Point(point.key,
                point.pos.getX() - anchor.getX(), point.pos.getY() - anchor.getY(), point.pos.getZ() - anchor.getZ())).toList());
    }

    static boolean open(ServerPlayer player, CompanionEntity companion) {
        if (!ServerPlayNetworking.canSend(player, Open.TYPE)) return false;
        var data = prepare(player, companion);
        if (data == null) return false;
        player.closeContainer();
        ServerPlayNetworking.send(player, data);
        return true;
    }

    static boolean select(ServerPlayer player, Select request) {
        var session = SESSIONS.get(player.getUUID());
        if (session == null || !session.token.equals(request.token)) return false;
        SESSIONS.remove(player.getUUID());
        var level = player.level();
        if (level != session.level || level.getGameTime() > session.until || request.index < 0
                || request.index >= session.points.size() || !(level.getEntity(session.companion) instanceof CompanionEntity companion)
                || !CompanionAccess.canControl(player, companion)) return false;
        var point = session.points.get(request.index);
        if (!companion.memory.places().contains(point) || !level.getWorldBorder().isWithinBounds(point.pos)
                || level.isOutsideBuildHeight(point.pos)) return false;
        companion.holdOrdersComponent();
        companion.stay();
        if (companion.blockPosition().distSqr(point.pos) > 24 * 24) companion.addTask(CompanionEntity.Task.travel(point.pos));
        companion.addTask(CompanionEntity.Task.moveTo(point.pos, 9));
        return true;
    }
}
