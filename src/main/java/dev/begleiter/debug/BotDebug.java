package dev.begleiter.debug;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import dev.begleiter.CompanionEntity;
import dev.begleiter.CompanionMod;
import dev.begleiter.CompanionTexts;
import net.minecraft.core.component.DataComponents;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.ComponentSerialization;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.world.item.ItemStack;

/** Begrenzte Debug-Schnappschüsse; übersetzte Texte löst erst der empfangende Client auf. */
public final class BotDebug {
    public static final int MAX_BOTS = 32, MAX_TASKS = 12, MAX_LOG = 40, MAX_INV = 24, MAX_PATH = 96, MAX_OBSTACLES = 8;
    private static final int TEXT = 200;
    public static final int LEVEL_OFF = 0, LEVEL_HUD = 1, LEVEL_FULL = 2;
    public static final int STATE_ACTIVE = 0, STATE_PAUSED = 1, STATE_ERROR = 2;
    public static final int A_PAUSE = 0, A_RESUME = 1, A_STEP = 2, A_CANCEL = 3, A_GOTO = 4, A_TELEPORT = 5, A_HEALTH = 6, A_HUNGER = 7;
    public static final int RECENT_SEC = 60, MAX_RECENT = 6;
    private BotDebug() {}

    public enum EventKind { NONE, PARKOUR, LOOT, CRAFT, PORTAL, FOOD, BUILD, DANGER, MEMORY }

    /** Einordnung für Aufrufer mit wörtlichem Text; Clients nutzen die übertragene Ereignisart. */
    public static EventKind eventKind(String s) {
        if (s.startsWith("Parkour")) return EventKind.PARKOUR;
        if (s.startsWith("Nehme") || (s.startsWith("Abbau: ") && (s.contains(" Ore ") || s.contains("Debris") || s.contains("Obsidian"))) || s.startsWith("Erz-Gespür")) return EventKind.LOOT;
        if (s.startsWith("Hergestellt") || s.contains("aufgestellt") || s.startsWith("Packe")) return EventKind.CRAFT;
        if (s.startsWith("Wasser auf Lava") || s.contains("Portal")) return EventKind.PORTAL;
        if (s.startsWith("Gegessen")) return EventKind.FOOD;
        if (s.startsWith("Anbau") || s.startsWith("Haus") || s.startsWith("Lager erweitert")) return EventKind.BUILD;
        if (s.startsWith("Stecke fest") || s.contains("Creeper") || s.startsWith("Angriff") || s.startsWith("Kampf")) return EventKind.DANGER;
        if (s.startsWith("Ich erinnere mich") || s.startsWith("Erinnerung stimmt nicht")) return EventKind.MEMORY;
        return EventKind.NONE;
    }

    public static int eventColor(String text) { return eventColor(eventKind(text)); }
    public static int eventColor(EventKind kind) {
        return switch (kind) {
            case PARKOUR -> 0xFF40E0FF; case LOOT -> 0xFF55FF55; case CRAFT -> 0xFF80A0FF;
            case PORTAL -> 0xFFD080FF; case FOOD -> 0xFFFFA040; case BUILD -> 0xFFFF80C0;
            case DANGER -> 0xFFFF6060; case MEMORY -> 0xFFFFE060; default -> 0;
        };
    }

    public record Entry(UUID id, int entityId, String name, String dim, double x, double y, double z,
            float health, float maxHealth, int hunger, int state, Component statusText) {
        public String status() { return statusText.getString(); }
    }

    public record TaskLine(CompanionEntity.TaskType kind, Component text) {}

    /** Ein Beispielstapel plus Gesamtzahl; Ausrüstungsslots sind 0..5, Inventar ist -1. */
    public record ItemLine(ItemStack stack, int total, int slot) {
        public ItemLine {
            if (total < 0 || slot < -1 || slot > 5) throw new IllegalArgumentException("Invalid debug item metadata");
            stack = stack.isEmpty() ? ItemStack.EMPTY : stack.copyWithCount(1);
        }
        public Component text() {
            if (slot < 0) return CompanionTexts.tr("materials.entry", total, stack.getHoverName());
            Component item = stack.isEmpty() ? Component.literal("-") : stack.getHoverName();
            if (stack.isDamageableItem()) item = CompanionTexts.tr("debug.item.durability", item, stack.getMaxDamage() - stack.getDamageValue());
            return CompanionTexts.tr("debug.item.slot", CompanionTexts.tr("debug.slot." + slot), item);
        }
    }

    public record LogLine(char level, int ageSec, int elapsedSec, int repetitions, EventKind kind, Component text, boolean recent) {
        public LogLine {
            if (level != 'I' && level != 'W' && level != 'E' || ageSec < 0 || elapsedSec < 0 || repetitions < 1)
                throw new IllegalArgumentException("Invalid debug log metadata");
        }
        public Component formatted() {
            return CompanionTexts.logLine(level, elapsedSec, repetitions, text);
        }
        public String legacyText() { return recent ? ageSec + "\t" + text.getString() + "\t" + repetitions : level + formatted().getString(); }
    }

    public record Detail(UUID id,
            Component modeText,
            Component missionText,
            Component planText,
            Component actionText,
            int actionSec,
            int aliveSec,
            Component reasonsText,
            Component needsText,
            Component hazardText,
            Component lastErrorText,
            int errorAgeSec,
            int diamond,
            int iron,
            int coal,
            String mine,
            int usedSlots,
            boolean navActive,
            int pathIndex,
            int pathLength,
            boolean partial,
            String moveType,
            float stepLength,
            int nodes,
            float ms,
            Component navEventText,
            String navGoal,
            int failures,
            long goal,
            List<TaskLine> taskLines,
            int taskTotal,
            long[] path,
            long[] obstacles,
            List<ItemLine> inventoryLines,
            List<ItemLine> equipmentLines,
            List<LogLine> logLines) {
        public static final long NO_POS = Long.MIN_VALUE;
        public String mode() { return modeText.getString(); }
        public String mission() { return missionText.getString(); }
        public String plan() { return planText.getString(); }
        public String action() { return actionText.getString(); }
        public String reasons() { return reasonsText.getString(); }
        public String needs() { return needsText.getString(); }
        public String hazard() { return hazardText.getString(); }
        public String lastError() { return lastErrorText.getString(); }
        public String navEvent() { return navEventText.getString(); }
        public List<String> tasks() { return taskLines.stream().map(line -> line.text().getString()).toList(); }
        public List<String> inventory() { return inventoryLines.stream().map(line -> line.text().getString()).toList(); }
        public List<String> equipment() { return equipmentLines.stream().map(line -> line.text().getString()).toList(); }
        public List<String> log() { return logLines.stream().map(LogLine::legacyText).toList(); }
    }

    public record Snapshot(boolean allowed, Component noticeText, UUID selected, List<Entry> bots, Detail detail) implements CustomPacketPayload {
        public Snapshot(boolean allowed, String notice, UUID selected, List<Entry> bots, Detail detail) {
            this(allowed, Component.literal(notice), selected, bots, detail);
        }
        public String notice() { return noticeText.getString(); }
        public static final Type<Snapshot> TYPE = new Type<>(CompanionMod.id("debug_snapshot_v2"));
        public static final StreamCodec<RegistryFriendlyByteBuf, Snapshot> CODEC = new StreamCodec<>() {
            @Override public Snapshot decode(RegistryFriendlyByteBuf b) {
                boolean allowed = b.readBoolean();
                Component notice = readText(b);
                UUID selected = b.readBoolean() ? b.readUUID() : null;
                int n = readCount(b, MAX_BOTS);
                List<Entry> bots = new ArrayList<>(n);
                for (int i = 0; i < n; i++) bots.add(new Entry(b.readUUID(), b.readVarInt(), b.readUtf(TEXT), b.readUtf(TEXT),
                        b.readDouble(), b.readDouble(), b.readDouble(), b.readFloat(), b.readFloat(), b.readVarInt(), b.readVarInt(), readText(b)));
                return new Snapshot(allowed, notice, selected, List.copyOf(bots), b.readBoolean() ? readDetail(b) : null);
            }
            @Override public void encode(RegistryFriendlyByteBuf b, Snapshot s) {
                b.writeBoolean(s.allowed); writeText(b, s.noticeText);
                b.writeBoolean(s.selected != null); if (s.selected != null) b.writeUUID(s.selected);
                int n = Math.min(s.bots.size(), MAX_BOTS); b.writeVarInt(n);
                for (int i = 0; i < n; i++) {
                    Entry e = s.bots.get(i);
                    b.writeUUID(e.id); b.writeVarInt(e.entityId); b.writeUtf(e.name, TEXT); b.writeUtf(e.dim, TEXT);
                    b.writeDouble(e.x); b.writeDouble(e.y); b.writeDouble(e.z); b.writeFloat(e.health); b.writeFloat(e.maxHealth);
                    b.writeVarInt(e.hunger); b.writeVarInt(e.state); writeText(b, e.statusText);
                }
                b.writeBoolean(s.detail != null); if (s.detail != null) writeDetail(b, s.detail);
            }
        };
        @Override public Type<Snapshot> type() { return TYPE; }
    }

    /** Client → Server: welchen Bot und wie viel Detail der Client gerade braucht. */
    public record Select(UUID selected, int level, boolean path) implements CustomPacketPayload {
        public static final Type<Select> TYPE = new Type<>(CompanionMod.id("debug_select"));
        public static final StreamCodec<RegistryFriendlyByteBuf, Select> CODEC = new StreamCodec<>() {
            @Override public Select decode(RegistryFriendlyByteBuf b) {
                return new Select(b.readBoolean() ? b.readUUID() : null, b.readVarInt(), b.readBoolean());
            }
            @Override public void encode(RegistryFriendlyByteBuf b, Select s) {
                b.writeBoolean(s.selected != null);
                if (s.selected != null) b.writeUUID(s.selected);
                b.writeVarInt(s.level); b.writeBoolean(s.path);
            }
        };
        @Override public Type<Select> type() { return TYPE; }
    }

    /** Client → Server: eine Steueraktion (wird serverseitig auf Berechtigung geprüft). */
    public record Command(UUID bot, int action, int x, int y, int z) implements CustomPacketPayload {
        public static final Type<Command> TYPE = new Type<>(CompanionMod.id("debug_command"));
        public static final StreamCodec<RegistryFriendlyByteBuf, Command> CODEC = new StreamCodec<>() {
            @Override public Command decode(RegistryFriendlyByteBuf b) {
                return new Command(b.readUUID(), b.readVarInt(), b.readInt(), b.readInt(), b.readInt());
            }
            @Override public void encode(RegistryFriendlyByteBuf b, Command c) {
                b.writeUUID(c.bot); b.writeVarInt(c.action); b.writeInt(c.x); b.writeInt(c.y); b.writeInt(c.z);
            }
        };
        @Override public Type<Command> type() { return TYPE; }
    }

    private static int readCount(RegistryFriendlyByteBuf b, int max) {
        int n = b.readVarInt();
        if (n < 0 || n > max) throw new IllegalArgumentException("Invalid debug collection size: " + n);
        return n;
    }
    private static Component readText(RegistryFriendlyByteBuf b) { return ComponentSerialization.STREAM_CODEC.decode(b); }
    private static void writeText(RegistryFriendlyByteBuf b, Component text) { ComponentSerialization.STREAM_CODEC.encode(b, text); }
    private static void writeLongs(RegistryFriendlyByteBuf b, long[] data, int max) {
        int n = Math.min(data.length, max); b.writeVarInt(n); for (int i = 0; i < n; i++) b.writeLong(data[i]);
    }
    private static long[] readLongs(RegistryFriendlyByteBuf b, int max) {
        long[] data = new long[readCount(b, max)]; for (int i = 0; i < data.length; i++) data[i] = b.readLong(); return data;
    }
    private static void writeTasks(RegistryFriendlyByteBuf b, List<TaskLine> list) {
        int n = Math.min(list.size(), MAX_TASKS); b.writeVarInt(n);
        for (int i = 0; i < n; i++) { b.writeEnum(list.get(i).kind); writeText(b, list.get(i).text); }
    }
    private static List<TaskLine> readTasks(RegistryFriendlyByteBuf b) {
        int n = readCount(b, MAX_TASKS); List<TaskLine> out = new ArrayList<>(n);
        for (int i = 0; i < n; i++) out.add(new TaskLine(b.readEnum(CompanionEntity.TaskType.class), readText(b)));
        return List.copyOf(out);
    }
    private static void writeItems(RegistryFriendlyByteBuf b, List<ItemLine> list, int max) {
        int n = Math.min(list.size(), max); b.writeVarInt(n);
        for (int i = 0; i < n; i++) {
            ItemLine line = list.get(i); ItemStack.OPTIONAL_STREAM_CODEC.encode(b, displayStack(line.stack)); b.writeVarInt(line.total); b.writeVarInt(line.slot);
        }
    }
    /** Debug braucht nur Identität, Anzeigename und Haltbarkeit, nie Bücher, Behälterinhalte oder eigenes NBT. */
    private static ItemStack displayStack(ItemStack source) {
        if (source.isEmpty()) return ItemStack.EMPTY;
        ItemStack display = new ItemStack(source.getItem(), 1);
        if (source.has(DataComponents.CUSTOM_NAME)) display.set(DataComponents.CUSTOM_NAME, source.get(DataComponents.CUSTOM_NAME));
        else display.set(DataComponents.ITEM_NAME, source.getHoverName());
        if (source.has(DataComponents.UNBREAKABLE)) display.set(DataComponents.UNBREAKABLE, source.get(DataComponents.UNBREAKABLE));
        if (source.isDamageableItem()) {
            display.set(DataComponents.MAX_DAMAGE, source.getMaxDamage());
            display.set(DataComponents.DAMAGE, source.getDamageValue());
        }
        return display;
    }
    private static List<ItemLine> readItems(RegistryFriendlyByteBuf b, int max) {
        int n = readCount(b, max); List<ItemLine> out = new ArrayList<>(n);
        for (int i = 0; i < n; i++) out.add(new ItemLine(ItemStack.OPTIONAL_STREAM_CODEC.decode(b), b.readVarInt(), b.readVarInt()));
        return List.copyOf(out);
    }
    private static void writeLogs(RegistryFriendlyByteBuf b, List<LogLine> list) {
        int n = Math.min(list.size(), MAX_LOG); b.writeVarInt(n);
        for (int i = 0; i < n; i++) {
            LogLine line = list.get(i); b.writeByte(line.level); b.writeVarInt(line.ageSec); b.writeVarInt(line.elapsedSec);
            b.writeVarInt(line.repetitions); b.writeEnum(line.kind); writeText(b, line.text); b.writeBoolean(line.recent);
        }
    }
    private static List<LogLine> readLogs(RegistryFriendlyByteBuf b) {
        int n = readCount(b, MAX_LOG); List<LogLine> out = new ArrayList<>(n);
        for (int i = 0; i < n; i++) out.add(new LogLine((char) b.readUnsignedByte(), b.readVarInt(), b.readVarInt(), b.readVarInt(),
                b.readEnum(EventKind.class), readText(b), b.readBoolean()));
        return List.copyOf(out);
    }

    private static void writeDetail(RegistryFriendlyByteBuf b, Detail d) {
        b.writeUUID(d.id);
        writeText(b, d.modeText);
        writeText(b, d.missionText);
        writeText(b, d.planText);
        writeText(b, d.actionText);
        b.writeVarInt(d.actionSec);
        b.writeVarInt(d.aliveSec);
        writeText(b, d.reasonsText);
        writeText(b, d.needsText);
        writeText(b, d.hazardText);
        writeText(b, d.lastErrorText);
        b.writeVarInt(d.errorAgeSec);
        b.writeVarInt(d.diamond);
        b.writeVarInt(d.iron);
        b.writeVarInt(d.coal);
        b.writeUtf(d.mine, TEXT);
        b.writeVarInt(d.usedSlots);
        b.writeBoolean(d.navActive);
        b.writeVarInt(d.pathIndex);
        b.writeVarInt(d.pathLength);
        b.writeBoolean(d.partial);
        b.writeUtf(d.moveType, TEXT);
        b.writeFloat(d.stepLength);
        b.writeVarInt(d.nodes);
        b.writeFloat(d.ms);
        writeText(b, d.navEventText);
        b.writeUtf(d.navGoal, TEXT);
        b.writeVarInt(d.failures);
        b.writeLong(d.goal);
        writeTasks(b, d.taskLines);
        b.writeVarInt(d.taskTotal);
        writeLongs(b, d.path, MAX_PATH);
        writeLongs(b, d.obstacles, MAX_OBSTACLES);
        writeItems(b, d.inventoryLines, MAX_INV);
        writeItems(b, d.equipmentLines, 6);
        writeLogs(b, d.logLines);
    }

    private static Detail readDetail(RegistryFriendlyByteBuf b) {
        return new Detail(b.readUUID(),
                readText(b),
                readText(b),
                readText(b),
                readText(b),
                b.readVarInt(),
                b.readVarInt(),
                readText(b),
                readText(b),
                readText(b),
                readText(b),
                b.readVarInt(),
                b.readVarInt(),
                b.readVarInt(),
                b.readVarInt(),
                b.readUtf(TEXT),
                b.readVarInt(),
                b.readBoolean(),
                b.readVarInt(),
                b.readVarInt(),
                b.readBoolean(),
                b.readUtf(TEXT),
                b.readFloat(),
                b.readVarInt(),
                b.readFloat(),
                readText(b),
                b.readUtf(TEXT),
                b.readVarInt(),
                b.readLong(),
                readTasks(b),
                b.readVarInt(),
                readLongs(b, MAX_PATH),
                readLongs(b, MAX_OBSTACLES),
                readItems(b, MAX_INV),
                readItems(b, 6),
                readLogs(b));
    }
}
