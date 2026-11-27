package dev.begleiter;

import java.util.HashMap;
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
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.Rotation;
import net.minecraft.world.level.block.state.BlockState;

/** Serverseitig gebundene Auswahl: der Client darf nur Standort und Drehung des freigegebenen Plans ändern. */
public final class CompanionPlacement {
    private CompanionPlacement() {}
    public static final int REACH = 32;
    public static final Rotation[] ROTATIONS = {Rotation.NONE, Rotation.CLOCKWISE_90,
            Rotation.CLOCKWISE_180, Rotation.COUNTERCLOCKWISE_90};
    /** schematic != null: Schematic-Datei (blueprint = ihr Name); sonst eingebaute Maschine. */
    private record Pending(UUID token, UUID bot, ServerLevel level, String blueprint, long expires,
                           CompanionSchematics.Schematic schematic, boolean withAir) {}
    /**
     * Vorschau einer Schematic-Datei: der Client kennt die Datei nicht, darum kommt der Plan mit (nur feste
     * Blöcke, außer withAir), kompakt kodiert über {@link CompanionSchematics#writePlan}. Als großes Paket
     * registriert, damit auch Schematics mit Hunderttausenden Blöcken eine Vorschau bekommen.
     */
    public record SchematicPreview(UUID token, String name, int rotation, int movement, boolean withAir,
                                   CompanionSchematics.Schematic plan) implements CustomPacketPayload {
        public static final Type<SchematicPreview> TYPE = new Type<>(CompanionMod.id("schematic_preview"));
        public static final StreamCodec<RegistryFriendlyByteBuf, SchematicPreview> CODEC = new StreamCodec<>() {
            public SchematicPreview decode(RegistryFriendlyByteBuf b) {
                UUID token = b.readUUID();
                String name = b.readUtf(128);
                int rotation = b.readVarInt(), movement = b.readVarInt();
                boolean withAir = b.readBoolean();
                return new SchematicPreview(token, name, rotation, movement, withAir, CompanionSchematics.readPlan(b, name));
            }
            public void encode(RegistryFriendlyByteBuf b, SchematicPreview p) {
                b.writeUUID(p.token); b.writeUtf(p.name, 128); b.writeVarInt(p.rotation); b.writeVarInt(p.movement);
                b.writeBoolean(p.withAir);
                CompanionSchematics.writePlan(b, p.plan, p.withAir);
            }
        };
        public Type<SchematicPreview> type() { return TYPE; }
    }
    private static final Map<UUID, Pending> PENDING = new HashMap<>();

    /** movement = aktuelle Bau-Bewegung des Begleiters (Ordinal von {@link CompanionEntity.BuildMovement}). */
    public record Preview(UUID token, String blueprint, int rotation, int movement) implements CustomPacketPayload {
        public static final Type<Preview> TYPE = new Type<>(CompanionMod.id("build_preview"));
        public static final StreamCodec<RegistryFriendlyByteBuf, Preview> CODEC = new StreamCodec<>() {
            public Preview decode(RegistryFriendlyByteBuf b) { return new Preview(b.readUUID(), b.readUtf(64), b.readVarInt(), b.readVarInt()); }
            public void encode(RegistryFriendlyByteBuf b, Preview p) {
                b.writeUUID(p.token); b.writeUtf(p.blueprint, 64); b.writeVarInt(p.rotation); b.writeVarInt(p.movement);
            }
        };
        public Type<Preview> type() { return TYPE; }
    }
    /** movement = in der Vorschau gewählte Bau-Bewegung (Ordinal), -1 = unverändert lassen. */
    public record Confirm(UUID token, BlockPos center, int rotation, boolean cancel, int movement) implements CustomPacketPayload {
        public Confirm(UUID token, BlockPos center, int rotation, boolean cancel) { this(token, center, rotation, cancel, -1); }
        public static final Type<Confirm> TYPE = new Type<>(CompanionMod.id("build_confirm"));
        public static final StreamCodec<RegistryFriendlyByteBuf, Confirm> CODEC = new StreamCodec<>() {
            public Confirm decode(RegistryFriendlyByteBuf b) {
                return new Confirm(b.readUUID(), b.readBlockPos(), b.readVarInt(), b.readBoolean(), b.readVarInt());
            }
            public void encode(RegistryFriendlyByteBuf b, Confirm p) {
                b.writeUUID(p.token); b.writeBlockPos(p.center); b.writeVarInt(p.rotation); b.writeBoolean(p.cancel);
                b.writeVarInt(p.movement);
            }
        };
        public Type<Confirm> type() { return TYPE; }
    }
    public record Feedback(UUID token, boolean finished, Component message) implements CustomPacketPayload {
        public static final Type<Feedback> TYPE = new Type<>(CompanionMod.id("build_feedback"));
        public static final StreamCodec<RegistryFriendlyByteBuf, Feedback> CODEC = new StreamCodec<>() {
            public Feedback decode(RegistryFriendlyByteBuf b) {
                return new Feedback(b.readUUID(), b.readBoolean(), ComponentSerialization.STREAM_CODEC.decode(b));
            }
            public void encode(RegistryFriendlyByteBuf b, Feedback p) {
                b.writeUUID(p.token); b.writeBoolean(p.finished); ComponentSerialization.STREAM_CODEC.encode(b, p.message);
            }
        };
        public Type<Feedback> type() { return TYPE; }
    }

    public static void register() {
        PayloadTypeRegistry.clientboundPlay().register(Preview.TYPE, Preview.CODEC);
        PayloadTypeRegistry.clientboundPlay().register(Feedback.TYPE, Feedback.CODEC);
        PayloadTypeRegistry.clientboundPlay().registerLarge(SchematicPreview.TYPE, SchematicPreview.CODEC,
                CompanionSchematics.MAX_PREVIEW_PACKET_BYTES);
        PayloadTypeRegistry.serverboundPlay().register(Confirm.TYPE, Confirm.CODEC);
        ServerPlayNetworking.registerGlobalReceiver(Confirm.TYPE, (request, ctx) -> ctx.server().execute(() ->
                ServerPlayNetworking.send(ctx.player(), confirm(ctx.player(), request))));
        ServerPlayConnectionEvents.DISCONNECT.register((handler, server) -> PENDING.remove(handler.getPlayer().getUUID()));
    }

    public static void reset() { PENDING.clear(); }

    /** Auch separat testbar; weder Auswahl noch Abbruch verändern einen laufenden Bauauftrag. */
    static Preview select(ServerPlayer player, CompanionEntity bot, CompanionMachines.Blueprint blueprint, Rotation extra) {
        if (!CompanionAccess.canManage(player, bot)) return null;
        UUID token = UUID.randomUUID();
        PENDING.put(player.getUUID(), new Pending(token, bot.getUUID(), player.level(),
                blueprint.id(), player.level().getGameTime() + 20 * 300, null, true));
        Rotation rotation = CompanionSchematics.rotationFor(player.getDirection()).getRotated(extra);
        return new Preview(token, blueprint.id(), java.util.Arrays.asList(ROTATIONS).indexOf(rotation), bot.buildMovement().ordinal());
    }

    /** Schematic-Datei zur Vorschau freigeben; null = nicht berechtigt oder zu groß für eine Vorschau. */
    static SchematicPreview selectSchematic(ServerPlayer player, CompanionEntity bot, CompanionSchematics.Schematic plan,
                                            boolean withAir, Rotation extra) {
        if (!CompanionAccess.canManage(player, bot)) return null;
        long sent = plan.blocks().values().stream().filter(s -> withAir || !s.isAir()).count();
        if (sent == 0) return null;
        UUID token = UUID.randomUUID();
        String name = plan.name().length() > 128 ? plan.name().substring(0, 128) : plan.name();
        PENDING.put(player.getUUID(), new Pending(token, bot.getUUID(), player.level(),
                name, player.level().getGameTime() + 20 * 300, plan, withAir));
        Rotation rotation = CompanionSchematics.rotationFor(player.getDirection()).getRotated(extra);
        return new SchematicPreview(token, name, java.util.Arrays.asList(ROTATIONS).indexOf(rotation),
                bot.buildMovement().ordinal(), withAir, plan);
    }

    /** Schematic-Datei mit Vorschau bauen; null = Vorschau läuft. Ohne Mod-Client oder zu groß: wie bisher direkt. */
    static Component beginSchematic(ServerPlayer player, CompanionEntity bot, java.nio.file.Path file, boolean withAir,
                                    Rotation extra) {
        if (!ServerPlayNetworking.canSend(player, SchematicPreview.TYPE))
            return CompanionBrain.startSchematicReply(player, bot, file, withAir, extra);
        return SchematicLoader.request(player, bot, file, (p, plan) -> beginSchematic(p, bot, plan, withAir, extra));
    }

    /** Den schon geprüften Plan verwenden, auch wenn sich die Datei ändert, während der Katalog offen ist. */
    static Component beginSchematic(ServerPlayer player, CompanionEntity bot, CompanionSchematics.Schematic plan,
                                     boolean withAir, Rotation extra) {
        if (!CompanionAccess.canManage(player, bot)) return CompanionTexts.tr("placement.expired");
        if (!ServerPlayNetworking.canSend(player, SchematicPreview.TYPE))
            return CompanionBrain.startSchematicReply(player, bot, plan, withAir, extra);
        if (plan.blocks().isEmpty()) return CompanionTexts.tr("schematic.empty", CompanionTexts.buildName(plan.name()));
        SchematicPreview preview = selectSchematic(player, bot, plan, withAir, extra);
        if (preview == null) return CompanionBrain.startSchematicReply(player, bot, plan, withAir, extra);
        player.closeContainer();
        ServerPlayNetworking.send(player, preview);
        return null;
    }

    static Component begin(ServerPlayer player, CompanionEntity bot, CompanionMachines.Blueprint blueprint, Rotation extra) {
        if (!ServerPlayNetworking.canSend(player, Preview.TYPE)) return CompanionTexts.tr("placement.unavailable");
        Preview preview = select(player, bot, blueprint, extra);
        if (preview == null) return CompanionTexts.tr("placement.expired");
        player.closeContainer();
        ServerPlayNetworking.send(player, preview);
        return null;
    }

    static Feedback confirm(ServerPlayer player, Confirm request) {
        Pending pending = PENDING.get(player.getUUID());
        if (pending == null || !pending.token.equals(request.token))
            return new Feedback(request.token, true, CompanionTexts.tr("placement.expired"));
        if (request.cancel) {
            PENDING.remove(player.getUUID());
            return new Feedback(request.token, true, CompanionTexts.tr("placement.cancelled"));
        }
        if (player.level() != pending.level || player.level().getGameTime() > pending.expires
                || !(pending.level.getEntity(pending.bot) instanceof CompanionEntity bot)
                || !CompanionAccess.canManage(player, bot)) {
            PENDING.remove(player.getUUID());
            return new Feedback(request.token, true, CompanionTexts.tr("placement.expired"));
        }
        if (request.rotation < 0 || request.rotation >= ROTATIONS.length
                || !player.blockPosition().closerThan(request.center, REACH + 1))
            return new Feedback(request.token, false, CompanionTexts.tr("placement.range", REACH));
        boolean file = pending.schematic != null;
        var blueprint = file ? null : CompanionMachines.find(pending.blueprint);
        var plan = file ? pending.schematic : blueprint.plan().get();
        var placed = CompanionSchematics.placeAtFull(plan, request.center, ROTATIONS[request.rotation], !file || pending.withAir);
        var blocks = placed.blocks();
        Component problem = prepare(pending.level, blocks);
        if (problem != null) return new Feedback(request.token, false, problem);
        var job = CompanionBrain.buildJob(pending.level, plan.name(), blocks, false);
        if (job == null) return new Feedback(request.token, false, CompanionTexts.tr("build.out_of_world"));
        job.data.putAll(placed.data());
        if (file) job.entities.addAll(placed.entities());
        var movements = CompanionEntity.BuildMovement.values();
        if (request.movement >= 0 && request.movement < movements.length) bot.buildMovementOverride = movements[request.movement];
        bot.cancelAll();
        bot.build = job;
        bot.queueBuild();
        PENDING.remove(player.getUUID());
        if (file) return new Feedback(request.token, true, CompanionBrain.withAirHint(
                CompanionTexts.tr("placement.schematic", CompanionTexts.buildName(plan.name()),
                plan.sizeX(), plan.sizeY(), plan.sizeZ(), job.total(), CompanionTexts.buildingMaterials(bot)),
                plan, pending.withAir));
        return new Feedback(request.token, true, CompanionTexts.tr("build.machine", blueprint.title(),
                plan.sizeX(), plan.sizeY(), plan.sizeZ(), CompanionTexts.buildingMaterials(bot), blueprint.help()));
    }

    /** Gelände darf geräumt werden; Gebäude, Flüssigkeiten und Block-Entities bleiben geschützt. */
    public static boolean replaceable(net.minecraft.world.level.LevelReader level, BlockPos pos, BlockState existing, BlockState target) {
        return existing.equals(target) || existing.isAir()
                || existing.getFluidState().isEmpty() && CompanionTerraform.natural(level, pos, existing);
    }

    /** Vor allen Weltlesezugriffen Grenzen/geladene Chunks prüfen, auch unter dem Fundament. */
    static Component prepare(ServerLevel level, Map<BlockPos, BlockState> blocks) {
        int bottom = blocks.keySet().stream().mapToInt(BlockPos::getY).min().orElse(0);
        for (BlockPos pos : blocks.keySet()) {
            if (level.isOutsideBuildHeight(pos) || !level.getWorldBorder().isWithinBounds(pos))
                return CompanionTexts.tr("build.out_of_world");
            if (!level.hasChunkAt(pos)) return CompanionTexts.tr("placement.unloaded");
        }
        for (var entry : blocks.entrySet()) {
            BlockPos pos = entry.getKey();
            if (level.getBlockEntity(pos) != null || !replaceable(level, pos, level.getBlockState(pos), entry.getValue()))
                return CompanionTexts.tr("placement.blocked", CompanionTexts.literal(pos.toShortString()));
            if (pos.getY() != bottom || entry.getValue().isAir()) continue;
            for (int depth = 1; depth <= 6; depth++) {
                BlockPos below = pos.below(depth);
                if (level.isOutsideBuildHeight(below)) break;
                BlockState state = level.getBlockState(below); // gleiche, bereits geprüfte Spalte
                if (!state.isAir() && !state.canBeReplaced() && state.getFluidState().isEmpty()) break;
                if (level.getBlockEntity(below) != null || !state.getFluidState().isEmpty())
                    return CompanionTexts.tr("placement.blocked", CompanionTexts.literal(below.toShortString()));
            }
        }
        // addFoundation kann bis zu sechs Schichten schreiben; keine Position unter die Baugrenze ergänzen.
        CompanionStructures.addFoundation(level, blocks, Blocks.COBBLESTONE);
        blocks.keySet().removeIf(level::isOutsideBuildHeight);
        return null;
    }
}
