package dev.begleiter;

import java.util.UUID;
import java.util.List;
import java.util.ArrayList;
import dev.begleiter.ai.CompanionDecisionEngine;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.ComponentSerialization;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;

/** Nur Anzeigedaten. Inventar und Ausrüstung werden über die Container-Slots synchronisiert. */
public record CompanionSnapshot(int containerId, UUID npc, int page, String name, float health,
        float maxHealth, int mode, boolean paused, Component activityText, int queued, int usedSlots,
        int hunger, Component stageText, Component feedbackText, Component needsText, Component hazardText, Component targetText, int buildPercent, int materialTypes, int missingItems, List<MaterialLine> buildMaterials, int workType, boolean intelligenceEnabled, Component problemText, boolean hasProblem, boolean creativeBuild,
        int buildMovement)
        implements CustomPacketPayload {
    public record MaterialLine(String itemId, int needed, int owned) {}
    public static final Type<CompanionSnapshot> TYPE = new Type<>(CompanionMod.id("control_snapshot"));
    public static final StreamCodec<RegistryFriendlyByteBuf, CompanionSnapshot> CODEC = new StreamCodec<>() {
        @Override public CompanionSnapshot decode(RegistryFriendlyByteBuf b) {
            return new CompanionSnapshot(b.readVarInt(), b.readUUID(), b.readVarInt(), b.readUtf(4096),
                    b.readFloat(), b.readFloat(), b.readVarInt(), b.readBoolean(), readText(b),
                    b.readVarInt(), b.readVarInt(), b.readVarInt(), readText(b), readText(b),
                    readText(b), readText(b), readText(b), b.readVarInt(), b.readVarInt(), b.readVarInt(), readMaterials(b), b.readVarInt(), b.readBoolean(), readText(b), b.readBoolean(), b.readBoolean(),
                    b.readVarInt());
        }
        @Override public void encode(RegistryFriendlyByteBuf b, CompanionSnapshot s) {
            b.writeVarInt(s.containerId); b.writeUUID(s.npc); b.writeVarInt(s.page); b.writeUtf(s.name, 4096);
            b.writeFloat(s.health); b.writeFloat(s.maxHealth); b.writeVarInt(s.mode); b.writeBoolean(s.paused);
            writeText(b, s.activityText); b.writeVarInt(s.queued); b.writeVarInt(s.usedSlots); b.writeVarInt(s.hunger);
            writeText(b, s.stageText); writeText(b, s.feedbackText); writeText(b, s.needsText);
            writeText(b, s.hazardText); writeText(b, s.targetText);
            b.writeVarInt(s.buildPercent); b.writeVarInt(s.materialTypes); b.writeVarInt(s.missingItems);
            b.writeVarInt(s.buildMaterials.size());
            for (MaterialLine entry : s.buildMaterials) {
                b.writeUtf(entry.itemId(), 128); b.writeVarInt(entry.needed()); b.writeVarInt(entry.owned());
            }
            b.writeVarInt(s.workType); b.writeBoolean(s.intelligenceEnabled); writeText(b, s.problemText); b.writeBoolean(s.hasProblem);
            b.writeBoolean(s.creativeBuild); b.writeVarInt(s.buildMovement);
        }
    };

    private static Component readText(RegistryFriendlyByteBuf buffer) {
        return ComponentSerialization.STREAM_CODEC.decode(buffer);
    }

    private static void writeText(RegistryFriendlyByteBuf buffer, Component text) {
        ComponentSerialization.STREAM_CODEC.encode(buffer, text);
    }

    /** Textansichten werden erst beim empfangenden Client aufgelöst; die Seiten-API bleibt gleich. */
    public String activity() { return activityText.getString(); }
    public String stage() { return stageText.getString(); }
    public String feedback() { return feedbackText.getString(); }
    public String needs() { return needsText.getString(); }
    public String hazard() { return hazardText.getString(); }
    public String target() { return targetText.getString(); }

    /** Konstruktor für Aufrufer und Testdaten mit wörtlichem Anzeigetext. */
    public CompanionSnapshot(int containerId, UUID npc, int page, String name, float health,
            float maxHealth, int mode, boolean paused, String activity, int queued, int usedSlots,
            int hunger, String stage, String feedback, String needs, String hazard, String target,
            int buildPercent, int materialTypes, int missingItems, List<MaterialLine> buildMaterials,
            int workType, boolean intelligenceEnabled) {
        this(containerId, npc, page, name, health, maxHealth, mode, paused, CompanionTexts.literal(text(activity)), queued,
                usedSlots, hunger, CompanionTexts.literal(text(stage)), CompanionTexts.literal(text(feedback)),
                CompanionTexts.literal(text(needs)), CompanionTexts.literal(text(hazard)), CompanionTexts.literal(text(target)),
                buildPercent, materialTypes, missingItems, buildMaterials, workType, intelligenceEnabled, Component.empty(), false, false, 0);
    }

    private static List<MaterialLine> readMaterials(RegistryFriendlyByteBuf b) {
        int n = b.readVarInt();
        if (n < 0 || n > 9) throw new IllegalArgumentException("Invalid material count: " + n);
        List<MaterialLine> result = new ArrayList<>(n);
        for (int i = 0; i < n; i++) result.add(new MaterialLine(b.readUtf(128), b.readVarInt(), b.readVarInt()));
        return List.copyOf(result);
    }

    public static CompanionSnapshot capture(CompanionEntity c, int containerId, CompanionMenu.Page page) {
        return capture(c, containerId, page, page == CompanionMenu.Page.BUILD ? remaining(c) : java.util.Map.of());
    }

    /** Restmaterial des aktuellen Bauauftrags; leer ohne Bau oder im Kreativmodus. Kostet einen Durchlauf über den Bauplan. */
    static java.util.Map<net.minecraft.world.item.Item, Integer> remaining(CompanionEntity c) {
        return c.build != null && !c.creativeHelper() && c.level() instanceof net.minecraft.server.level.ServerLevel sl
                ? CompanionBuilder.remaining(sl, c.build) : java.util.Map.of();
    }

    /** Mit bereits berechnetem Restmaterial (das Menü rechnet es nur alle paar Sekunden neu). */
    static CompanionSnapshot capture(CompanionEntity c, int containerId, CompanionMenu.Page page,
            java.util.Map<net.minecraft.world.item.Item, Integer> materials) {
        return new CompanionSnapshot(containerId, c.getUUID(), page.id, text(c.getDisplayName().getString()),
                c.getHealth(), c.getMaxHealth(),
                c.isKeeper() ? 3 : c.isStaying() ? 1 : c.isAutonomous() ? 2 : c.duty() != null ? c.duty().mode : 0,
                c.isPaused(), CompanionTexts.activity(c), c.taskCount(), c.usedSlots(), c.food.level(),
                c.stageComponent(), c.uiFeedbackComponent(), CompanionTexts.needs(c), CompanionTexts.hazards(c),
                CompanionTexts.target(c),
                c.build == null ? -1 : c.build.percent(), materials.size(), missingItems(c, materials),
                materialLines(c, materials), workType(c), CompanionDecisionEngine.enabled(),
                c.problemText.isEmpty() ? Component.empty() : CompanionTexts.tr("problem", c.problemComponent()),
                !c.problemText.isEmpty(), c.creativeHelper(), c.buildMovement().ordinal());
    }

    /** 0 = kein Bau, 1 = bauen, 2 = beschaffen, 3 = pausiert, 4 = sonstige Aufgabe. */
    private static int workType(CompanionEntity c) {
        if (c.build == null) return 0;
        if (c.build.paused || c.isPaused()) return 3;
        var task = c.currentTask();
        if (task == null) return 4;
        return switch (task.type()) {
            case GATHER, TAKE, CRAFT, SMELT, COLLECT, MINE, TREE -> 2;
            case BUILD, BREAK, PLACE -> 1;
            default -> 4;
        };
    }

    private static List<MaterialLine> materialLines(CompanionEntity c, java.util.Map<net.minecraft.world.item.Item, Integer> materials) {
        List<MaterialLine> lines = new ArrayList<>();
        for (var entry : materials.entrySet()) {
            if (lines.size() >= 9) break;
            String id = net.minecraft.core.registries.BuiltInRegistries.ITEM.getKey(entry.getKey()).toString();
            int owned = 0;
            for (int i = 0; i < c.getInventory().getContainerSize(); i++) {
                var stack = c.getInventory().getItem(i);
                if (stack.is(entry.getKey())) owned += stack.getCount();
            }
            lines.add(new MaterialLine(id, entry.getValue(), owned));
        }
        return List.copyOf(lines);
    }

    /** Materialwerte aus dem bestehenden serverseitigen Bauplan; -1 bedeutet kein aktiver Auftrag. */
    private static int missingItems(CompanionEntity c, java.util.Map<net.minecraft.world.item.Item, Integer> materials) {
        int total = 0;
        for (var en : materials.entrySet())
            total += Math.max(0, en.getValue() - CompanionCraft.count(c,
                    net.minecraft.core.registries.BuiltInRegistries.ITEM.getKey(en.getKey()).toString()));
        return total;
    }

    /** Kompatibilität für bestehende Tests und Aufrufstellen mit dem bisherigen Snapshot-Schema. */
    public CompanionSnapshot(int containerId, UUID npc, int page, String name, float health,
            float maxHealth, int mode, boolean paused, String activity, int queued, int usedSlots,
            int hunger, String stage, String feedback, String needs, String hazard, String target) {
        this(containerId, npc, page, name, health, maxHealth, mode, paused, activity, queued,
                usedSlots, hunger, stage, feedback, needs, hazard, target, -1, 0, 0, List.of(), 0,
                CompanionDecisionEngine.enabled());
    }

    private static String text(String value) {
        if (value == null) return "";
        return value.length() <= 4096 ? value : value.substring(0, 4096);
    }

    public String modeText() {
        return CompanionTexts.tr("mode." + switch (mode) { case 1 -> "stay"; case 2 -> "free"; case 3 -> "keeper"; case 4 -> "farm"; case 5 -> "animals"; case 6 -> "trade"; case 7 -> "explore"; case 8 -> "base"; case 9 -> "storage"; case 10 -> "fetch"; default -> "follow"; }).getString();
    }

    @Override public Type<CompanionSnapshot> type() { return TYPE; }
}
