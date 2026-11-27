package dev.begleiter;

import java.util.List;

import net.fabricmc.fabric.api.networking.v1.PayloadTypeRegistry;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.chat.Component;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.Identifier;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.Items;

/**
 * Menüknöpfe für Chat-Befehle: Der Client schickt einen festen englischen Befehlssatz (oder bei der freien
 * Eingabe den Text des Spielers) und der Server führt ihn über denselben Weg aus wie eine Chatzeile. So gibt es
 * keine zweite Logik neben dem Chat. Nur Aufträge, deren Chatform IDs verlieren würde, laufen über eine kleine
 * typisierte Form ({@code #obtain}, {@code #terrain}) direkt in die bestehenden Auftragsfunktionen.
 */
public final class CompanionMenuCommands {
    private CompanionMenuCommands() {}

    public static final int MAX_LENGTH = 256;

    /** {@code free}: freie Eingabe des Spielers (Sprache des Spielers, LLM-Rückfall wie im Chat). */
    public record Run(int containerId, boolean free, String command) implements CustomPacketPayload {
        public static final Type<Run> TYPE = new Type<>(CompanionMod.id("menu_command"));
        public static final StreamCodec<RegistryFriendlyByteBuf, Run> CODEC = new StreamCodec<>() {
            @Override public Run decode(RegistryFriendlyByteBuf b) {
                return new Run(b.readVarInt(), b.readBoolean(), b.readUtf(MAX_LENGTH));
            }
            @Override public void encode(RegistryFriendlyByteBuf b, Run p) {
                b.writeVarInt(p.containerId); b.writeBoolean(p.free); b.writeUtf(p.command, MAX_LENGTH);
            }
        };
        @Override public Type<Run> type() { return TYPE; }
    }

    static void register() {
        PayloadTypeRegistry.serverboundPlay().register(Run.TYPE, Run.CODEC);
        ServerPlayNetworking.registerGlobalReceiver(Run.TYPE, (packet, ctx) -> ctx.server().execute(() -> {
            if (ctx.player().containerMenu instanceof CompanionMenu menu && menu.containerId == packet.containerId())
                menu.runCommand(ctx.player(), packet.free(), packet.command());
        }));
    }

    /**
     * Führt einen Menübefehl aus. Liefert null, wenn ihn nichts verstanden hat; bei {@code free} startet der
     * Aufrufer dann wie im Chat die optionale LLM-Übersetzung.
     */
    static Component execute(ServerPlayer player, CompanionEntity c, boolean free, String command) {
        String text = command == null ? "" : command.strip();
        if (text.isEmpty() || text.length() > MAX_LENGTH) return null;
        if (!free && text.startsWith("#")) return typed(player, c, text);
        String language = free ? player.clientInformation().language() : "en_us";
        return CompanionChatActions.interpretReply(player, c, text, language);
    }

    private static Component typed(ServerPlayer player, CompanionEntity c, String text) {
        String[] parts = text.split(" ");
        switch (parts[0]) {
            case "#obtain" -> {
                if (parts.length != 3) return null;
                int count = count(parts[1]);
                Item item = item(parts[2]);
                if (item == null) return CompanionTexts.tr("stock.invalid_item", CompanionTexts.literal(parts[2]));
                if (count < 1) return CompanionTexts.tr("stock.invalid_count", dev.begleiter.chat.ChatCount.MAX);
                return CompanionChatActions.menuObtain(player, c, item, count);
            }
            case "#terrain" -> {
                if (parts.length != 3) return null;
                CompanionTerraform.Mode mode;
                try { mode = CompanionTerraform.Mode.valueOf(parts[1]); } catch (IllegalArgumentException ex) { return null; }
                int size = Math.clamp(count(parts[2]), 3, CompanionTerraform.MAX_SIZE);
                return CompanionBrain.orderTerraformReply(player, c, mode, List.of(String.valueOf(size), String.valueOf(size)),
                        net.minecraft.world.level.block.Blocks.DIRT);
            }
            default -> { return null; }
        }
    }

    private static int count(String value) {
        if (!value.matches("[0-9]{1,4}")) return -1;
        int n = Integer.parseInt(value);
        return n > dev.begleiter.chat.ChatCount.MAX ? -1 : n;
    }

    private static Item item(String id) {
        Identifier key = Identifier.tryParse(id);
        if (key == null || !BuiltInRegistries.ITEM.containsKey(key)) return null;
        Item item = BuiltInRegistries.ITEM.getValue(key);
        return item == Items.AIR ? null : item;
    }

    // ---- Befehlsvorlagen für die Menüknöpfe (englische Chatform, von GameTests gegen den Server geprüft) ----

    public static String id(Item item) { return BuiltInRegistries.ITEM.getKey(item).toString(); }

    public static String obtain(Item item, int count) { return "#obtain " + count + " " + id(item); }
    public static String craft(Item item, int count) { return "craft me " + count + " " + id(item); }
    public static String materials(Item item, int count) { return "bring me resources for " + count + " " + id(item); }
    public static String store(Item item, int count, boolean urgent) {
        return (urgent ? "urgent " : "") + "bring " + count + " " + id(item) + " into storage";
    }
    public static String keepStock(Item item, int count) { return "keep " + count + " " + id(item) + " in stock"; }
    public static String clearStock(Item item) { return "clear stock rule for " + id(item); }
    public static String stockGather(Item item, boolean gather) { return "stock rule " + id(item) + (gather ? " gather" : " local"); }
    public static final String STOCK_LIST = "stock rules", STOCK_CLEAR = "clear stock rules",
            WOOD_SCOPED = "harvest wood only in roam zone", WOOD_FREE = "clear wood zone limit";

    /** Hintergrundarbeiten in der Reihenfolge der Menüknöpfe (Namen wie im Chatbefehl {@code work <job>}). */
    public static final List<String> WORK_JOBS = List.of("farm", "animals", "storage", "stock", "explore", "build", "trade");
    public static String work(String job, String action) { return "work " + job(job) + " " + action; }
    public static String workValue(String job, String setting, int value) { return work(job, setting) + " " + value; }
    public static String workBudget(String job, Item item, int count) { return work(job, "budget") + " " + count + " " + id(item); }
    public static String workBudgetRemove(String job, Item item) { return work(job, "budget") + " remove " + id(item); }
    public static final String WORK_ON = "work on", WORK_OFF = "work off", WORK_LIST = "work list";
    private static String job(String job) {
        if (!WORK_JOBS.contains(job)) throw new IllegalArgumentException("Unknown work job: " + job);
        return job;
    }

    public static final String SURVIVAL_IRON = "play survival to iron armor", SURVIVAL_DRAGON = "play survival to the dragon",
            SURVIVAL_STATUS = "survival goal", SURVIVAL_PAUSE = "pause survival goal",
            SURVIVAL_RESUME = "resume survival goal", SURVIVAL_CLEAR = "clear survival goal";
    public static final String GO_FISHING = "go fishing";
    public static String catchFish(int count) { return "catch " + Math.max(1, count) + " fish"; }
    public static String terrain(CompanionTerraform.Mode mode, int size) { return "#terrain " + mode.name() + " " + size; }

    public static final String STATUS = "status", EXPLAIN = "why", KNOWLEDGE = "what do you know",
            TALK_LESS = "talk less", TALK_MORE = "talk more", PROTECT = "protect me", DOOR = "install a door",
            MEMORY_CLEAR = "clear your memory", PROJECT_PREVIEW = "show project preview", YES = "yes", NO = "no",
            SCHEMATICS = "show schematics", WAYPOINT_LIST = "which waypoints do you know";
    /** Fragen nach bekannten Orten; Schlüssel für die Knopfbeschriftung und Chatwort. */
    public static final List<String> WHERE_TOPICS = List.of("iron", "coal", "gold", "diamonds", "redstone", "wood", "water", "lava");
    public static String whereIs(String topic) {
        if (!WHERE_TOPICS.contains(topic)) throw new IllegalArgumentException("Unknown topic: " + topic);
        return "where is " + topic;
    }

    /** Wegpunktnamen aus dem Eingabefeld: nur Buchstaben, Ziffern und Leerzeichen, wie sie der Chat auch speichert. */
    public static String waypointName(String raw) {
        String name = dev.begleiter.chat.ChatText.normalize(raw);
        return name.length() > 32 ? name.substring(0, 32).strip() : name;
    }
    public static String waypointSave(String name) { return "remember this as " + waypointName(name); }
    public static String waypointGo(String name) { return "go to " + waypointName(name); }
    public static String waypointForget(String name) { return "forget waypoint " + waypointName(name); }
}
