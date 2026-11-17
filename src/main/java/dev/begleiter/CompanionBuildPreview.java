package dev.begleiter;

import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.ComponentSerialization;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;

/**
 * Nur lesbares Katalogmodell ohne Platzierungsmarke und ohne Blockentity-Daten. Große Schematics überschreiten
 * die Vanilla-Paketgrenze, daher ist dieses Paket als großes (geteiltes) Paket registriert.
 * modelId bezeichnet eine bestimmte zwischengespeicherte Dateiversion, damit der Client sein Modell wiederverwendet.
 */
public record CompanionBuildPreview(int containerId, java.util.UUID npc, Component title,
                                    CompanionSchematics.Schematic plan, int catalogueSlot, java.util.UUID modelId) implements CustomPacketPayload {
    public CompanionBuildPreview(int containerId, java.util.UUID npc, Component title, CompanionSchematics.Schematic plan, int catalogueSlot) {
        this(containerId, npc, title, plan, catalogueSlot, new java.util.UUID(0, 0));
    }
    public CompanionBuildPreview(int containerId, java.util.UUID npc, Component title, CompanionSchematics.Schematic plan) {
        this(containerId, npc, title, plan, -1);
    }
    public static final Type<CompanionBuildPreview> TYPE = new Type<>(CompanionMod.id("build_catalog_preview"));
    public static final StreamCodec<RegistryFriendlyByteBuf, CompanionBuildPreview> CODEC = new StreamCodec<>() {
        public CompanionBuildPreview decode(RegistryFriendlyByteBuf b) {
            int container = b.readVarInt();
            var npc = b.readUUID();
            Component title = ComponentSerialization.STREAM_CODEC.decode(b);
            int slot = b.readVarInt();
            var modelId = b.readUUID();
            return new CompanionBuildPreview(container, npc, title, CompanionSchematics.readPlan(b, "preview"), slot, modelId);
        }
        public void encode(RegistryFriendlyByteBuf b, CompanionBuildPreview p) {
            b.writeVarInt(p.containerId); b.writeUUID(p.npc);
            ComponentSerialization.STREAM_CODEC.encode(b, p.title);
            b.writeVarInt(p.catalogueSlot);
            b.writeUUID(p.modelId);
            CompanionSchematics.writePlan(b, p.plan, false);
        }
    };
    @Override public Type<CompanionBuildPreview> type() { return TYPE; }
}
