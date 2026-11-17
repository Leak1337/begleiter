package dev.begleiter.llm;

import java.util.ArrayList;
import java.util.Collection;
import java.util.EnumMap;
import java.util.EnumSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import dev.begleiter.chat.ChatCount;
import dev.begleiter.chat.ChatIntent;
import dev.begleiter.chat.ChatParams;
import dev.begleiter.chat.ChatParser;

/**
 * Übersetzt Sätze, die die Lexika nicht verstehen, per Tool-Call in dieselben {@link ChatIntent}s und
 * {@link ChatParams} wie der Parser. Das Modell kann nur vorhandene Befehle wählen; jeder Wert wird geprüft.
 * Ohne Minecraft-Abhängigkeit: Item- und Mob-IDs prüft der Aufrufer über {@link Ids}.
 */
public final class IntentTools {
    private IntentTools() {}

    public static final String COMMAND_TOOL = "befehl";
    public static final String QUESTION_TOOL = "rueckfrage";
    /** Kein Auftrag, sondern Gespräch: die Antwort schreibt eine zweite Anfrage mit Kontext ({@link ChatReply}). */
    public static final String CHAT_TOOL = "plaudern";
    /** Spielertexte sind kurze Chatzeilen; längere werden nicht gesendet. */
    public static final int MAX_TEXT = 256;

    /** Zerstörerische Befehle nur über den eindeutigen Lexikon-Satz, nie über eine Vermutung des Modells. */
    public static final Set<ChatIntent> EXCLUDED = EnumSet.of(ChatIntent.MEMORY_CLEAR);

    /** Prüft Registry-IDs ohne Namensraum ("iron_pickaxe", "sheep"). */
    public interface Ids {
        boolean item(String path);
        boolean mob(String path);

        /** Nur Form und Lexikon-Mobs; für Tests und als Notlösung ohne Registry. */
        Ids PATTERN_ONLY = new Ids() {
            @Override public boolean item(String path) { return true; }
            @Override public boolean mob(String path) { return ChatParser.mobKeys().contains(path); }
        };
    }

    public sealed interface Translation {
        /** @param farm bei BUILD_STRUCTURE: Weizenfeld statt Haus (der Parser liest das aus dem Satz) */
        record Command(ChatParser.Result result, boolean farm, String summary) implements Translation {}
        /** Modell braucht eine Angabe vom Spieler; Text ist bereits in Spielersprache. */
        record Question(String text) implements Translation {}
        /** Smalltalk oder Frage an den Begleiter; nichts ausführen, sondern frei antworten. */
        record Chat() implements Translation {}
        record Rejected(String reason, List<Problem> problems) implements Translation {
            public Rejected(String reason) { this(reason, List.of(new Problem(Problem.Code.LITERAL, "", reason))); }
            public Rejected { problems = List.copyOf(problems); }
        }
    }

    /** Nur Anzeigedaten; Prüfung und Diagnose hängen nicht von der Sprache ab. */
    public record Problem(Code code, String field, String value) {
        public enum Code { LITERAL, RESULT_FAILURE, NO_TOOL, EMPTY_QUESTION, NOT_OBJECT, UNKNOWN_INTENT,
            INVALID_FIELD, UNKNOWN_ID, MISSING_FIELD, MISSING_CHOICE }
        String legacy() {
            return switch (code) {
                case LITERAL -> value;
                case RESULT_FAILURE -> field + ": " + value;
                case NO_TOOL -> "no matching tool call";
                case EMPTY_QUESTION -> "empty clarification";
                case NOT_OBJECT -> "arguments are not a JSON object";
                case UNKNOWN_INTENT -> "unknown intent: " + value;
                case INVALID_FIELD -> field + " invalid: " + value;
                case UNKNOWN_ID -> field + " unknown: " + value;
                case MISSING_FIELD -> field + " without " + value;
                case MISSING_CHOICE -> field + " without resource or item";
            };
        }
    }

    private static Translation.Rejected rejected(Problem... problems) {
        return rejected(List.of(problems));
    }

    private static Translation.Rejected rejected(List<Problem> problems) {
        return new Translation.Rejected(String.join("; ", problems.stream().map(Problem::legacy).toList()), problems);
    }

    private static final Pattern ID = Pattern.compile("(?:[a-z0-9_.-]{1,64}:)?[a-z0-9_./-]{1,128}");
    private static final Pattern SCHEMATIC = Pattern.compile("[\\p{L}\\p{N} _.-]{1,48}");
    private static final Pattern WAYPOINT = Pattern.compile("[\\p{L}\\p{N} _-]{1,32}");

    private static final List<String> STANCES = List.of("AGGRESSIVE", "DEFENSIVE", "PASSIVE");
    private static final List<String> TERRAFORMS = List.of("FLATTEN", "SMOOTH", "HILL", "PIT", "FILL", "CUT");
    private static final List<String> SIZES = List.of("small", "normal", "large");
    private static final List<String> STYLES = List.of("wood", "stone", "birch");
    private static final List<String> TIERS = List.of("iron", "diamond", "netherite");
    private static final List<String> FILLERS = List.of("dirt", "cobblestone");

    /**
     * Kurzbeschreibungen für den Prompt: jedes Token zählt bei jeder Anfrage (Free-Tier-Tageslimit). Nur was der
     * Name nicht schon sagt; Pflichtfelder in Klammern, Wahlfelder hinter "opt".
     */
    static final Map<ChatIntent, String> DESCRIPTIONS = new EnumMap<>(ChatIntent.class);
    static {
        d(ChatIntent.WAYPOINT_SAVE, "save player's position (waypoint)");
        d(ChatIntent.WAYPOINT_FORGET, "(waypoint)");
        d(ChatIntent.WAYPOINT_LIST, "");
        d(ChatIntent.WAYPOINT_GO, "walk there (waypoint)");
        d(ChatIntent.PROJECT_PREVIEW, "preview next base project");
        d(ChatIntent.TERRAFORM, "reshape terrain (terraform) opt filler");
        d(ChatIntent.STANCE, "combat behaviour (stance)");
        d(ChatIntent.PROTECT, "guard the player");
        d(ChatIntent.MEMORY_CLEAR, "forget everything");
        d(ChatIntent.MEMORY_SUMMARY, "what it remembers");
        d(ChatIntent.WHERE_IS, "where something was seen (resource or item)");
        d(ChatIntent.CANCEL, "drop all orders");
        d(ChatIntent.PAUSE, "pause building");
        d(ChatIntent.RESUME, "");
        d(ChatIntent.EXPLAIN, "explain goals/needs");
        d(ChatIntent.STATUS, "");
        d(ChatIntent.SET_HOME, "use nearby chest as storage");
        d(ChatIntent.FARM, "harvest and replant");
        d(ChatIntent.BREED, "opt mob, count");
        d(ChatIntent.REPAIR, "repair own gear");
        d(ChatIntent.TRADE, "with villagers; opt trade_item, trade_count, sell, for_owner");
        d(ChatIntent.GEAR_UP, "improve own gear; opt tier");
        d(ChatIntent.KEEPER, "stay home, manage storage");
        d(ChatIntent.SORT, "sort chests");
        d(ChatIntent.DOOR, "place house door");
        d(ChatIntent.LIST_SCHEMATICS, "");
        d(ChatIntent.BUILD_SCHEMATIC, "(schematic=file search term) opt with_air");
        d(ChatIntent.BUILD_STRUCTURE, "house, or wheat field with farm=true; opt size, style");
        d(ChatIntent.FETCH_ANIMAL, "lead animal to player (mob)");
        d(ChatIntent.GIVE, "hand over resource, or everything");
        d(ChatIntent.OBTAIN, "get/craft a specific item (item) opt count");
        d(ChatIntent.MATERIALS_FOR, "bring recipe ingredients (item) opt count");
        d(ChatIntent.CRAFT_FOR_OWNER, "newly craft and deliver (item) opt count");
        d(ChatIntent.GATHER, "raw material (resource) opt count, to_owner");
        d(ChatIntent.FREE_ROAM, "play on its own");
        d(ChatIntent.FOLLOW, "");
        d(ChatIntent.STAY, "wait here");
    }

    private static void d(ChatIntent intent, String text) { DESCRIPTIONS.put(intent, text); }

    public static List<ChatIntent> offered() {
        List<ChatIntent> out = new ArrayList<>();
        for (ChatIntent intent : ChatIntent.values()) if (!EXCLUDED.contains(intent)) out.add(intent);
        return out;
    }

    /** Fertige Anfrage: Systemprompt, Spielersatz und beide Tools; das Modell muss genau eines aufrufen. */
    public static LlmRequest request(Object owner, String model, String playerText, String playerLanguage) {
        String text = playerText.length() > MAX_TEXT ? playerText.substring(0, MAX_TEXT) : playerText;
        LlmRequest request = new LlmRequest(owner, model,
                List.of(LlmRequest.Message.system(systemPrompt(playerLanguage)), LlmRequest.Message.user(text)),
                tools(), "required", 300, 0, null);
        // Nur die gpt-oss-Modelle kennen reasoning_effort "low"; andere lehnen unbekannte Werte ab.
        return model.contains("gpt-oss") ? request.withReasoningEffort("low") : request;
    }

    public static String systemPrompt(String playerLanguage) {
        boolean german = playerLanguage != null && playerLanguage.toLowerCase(Locale.ROOT).startsWith("de");
        StringBuilder b = new StringBuilder();
        b.append("""
                Turn a Minecraft player's chat message to their companion into one tool call.
                befehl: best intent, only parameters the message states or clearly implies. item/mob are English \
                item ids (mod namespace allowed), mob paths (Kuh->cow). \
                Raw materials: GATHER; get items: OBTAIN; craft: CRAFT_FOR_OWNER.
                plaudern: chat/questions, not orders.
                rueckfrage: unclear order or missing value. Never invent values. \
                """);
        b.append("One short ").append(german ? "German" : "English").append(" sentence.\n");
        // Steht auch als Enum im Schema; ausgeschrieben ordnet das Modell Umschreibungen ("graues Metall") deutlich besser zu.
        b.append("Resources: ").append(String.join(", ", ChatParser.resourceKeys())).append('\n');
        b.append("Intents, (required fields):\n");
        for (ChatIntent intent : offered()) {
            String d = DESCRIPTIONS.get(intent);
            b.append(intent.name()).append(d.isEmpty() ? "" : " " + d).append('\n');
        }
        b.append("""
                e.g. "bisschen eisen besorgen" -> GATHER resource=eisen to_owner=true; \
                "kleines Haus aus Stein" -> BUILD_STRUCTURE size=small style=stone; "geh zu dem ort" -> rueckfrage; \
                "wie gehts dir" -> plaudern
                """);
        return b.toString();
    }

    public static JsonArray tools() {
        // Beschreibungen nur, wo Name und Enum nicht reichen; Grenzen prüft command() ohnehin.
        JsonObject p = new JsonObject();
        p.add("intent", enumProp(null, names(offered())));
        p.add("count", prop("integer", null));
        p.add("resource", enumProp(null, ChatParser.resourceKeys()));
        p.add("mob", strProp("or \"any\""));
        p.add("item", strProp(null));
        p.add("stance", enumProp(null, STANCES));
        p.add("terraform", enumProp(null, TERRAFORMS));
        p.add("filler", enumProp(null, FILLERS));
        p.add("size", enumProp(null, SIZES));
        p.add("style", enumProp(null, STYLES));
        p.add("farm", boolProp(null));
        p.add("tier", enumProp(null, TIERS));
        p.add("waypoint", strProp(null));
        p.add("to_owner", boolProp("bring result to player"));
        p.add("for_owner", boolProp(null));
        p.add("trade_item", strProp("wanted item, never emerald"));
        p.add("trade_count", prop("integer", null));
        p.add("sell", boolProp("sell for emeralds"));
        p.add("schematic", strProp(null));
        p.add("with_air", boolProp(null));
        JsonObject commandParams = object(p);
        JsonArray required = new JsonArray();
        required.add("intent");
        commandParams.add("required", required);

        JsonObject q = new JsonObject();
        q.add("frage", strProp(null));
        JsonObject questionParams = object(q);
        JsonArray requiredQ = new JsonArray();
        requiredQ.add("frage");
        questionParams.add("required", requiredQ);

        JsonArray tools = new JsonArray();
        tools.add(function(COMMAND_TOOL, "One order for the companion.", commandParams));
        tools.add(function(QUESTION_TOOL, "Ask the player back.", questionParams));
        tools.add(function(CHAT_TOOL, "Not an order: chat or question.", object(new JsonObject())));
        return tools;
    }

    /** Wertet die Modellantwort aus; alles Unbekannte oder Fehlende führt zu {@link Translation.Rejected}. */
    public static Translation translate(LlmResult result, String playerLanguage, Ids ids) {
        if (!result.ok()) return rejected(new Problem(Problem.Code.RESULT_FAILURE, result.status().name(), String.valueOf(result.error())));
        for (LlmResult.ToolCall call : result.toolCalls()) {
            if (call.name().equals(COMMAND_TOOL)) return command(call.arguments(), playerLanguage, ids);
            if (call.name().equals(QUESTION_TOOL)) return question(call.arguments());
            if (call.name().equals(CHAT_TOOL)) return new Translation.Chat();
        }
        return rejected(new Problem(Problem.Code.NO_TOOL, "", ""));
    }

    static Translation question(String arguments) {
        JsonObject o = object(arguments);
        String text = o == null ? null : string(o, "frage");
        if (text == null || text.isBlank()) return rejected(new Problem(Problem.Code.EMPTY_QUESTION, "", ""));
        text = text.replaceAll("\\s+", " ").strip();
        return new Translation.Question(text.length() > 200 ? text.substring(0, 200) + "…" : text);
    }

    static Translation command(String arguments, String playerLanguage, Ids ids) {
        JsonObject o = object(arguments);
        if (o == null) return rejected(new Problem(Problem.Code.NOT_OBJECT, "", ""));
        String intentName = string(o, "intent");
        ChatIntent intent = null;
        for (ChatIntent offered : offered()) if (offered.name().equalsIgnoreCase(intentName == null ? "" : intentName.strip())) intent = offered;
        if (intent == null) return rejected(new Problem(Problem.Code.UNKNOWN_INTENT, "", String.valueOf(o.get("intent"))));

        List<Problem> problems = new ArrayList<>();
        int count = count(o, "count", problems);
        String resource = choice(o, "resource", ChatParser.resourceKeys(), problems);
        String mob = id(o, "mob", ids::mob, ChatParser::lexiconMob, problems);
        String item = id(o, "item", ids::item, ChatParser::lexiconItem, problems);
        String stance = choice(o, "stance", STANCES, problems);
        String terraform = choice(o, "terraform", TERRAFORMS, problems);
        String size = choice(o, "size", SIZES, problems);
        String style = choice(o, "style", STYLES, problems);
        if ("wood".equals(style)) style = null; // Holz ist der Standard der Ausführung
        String tier = choice(o, "tier", TIERS, problems);
        String filler = choice(o, "filler", FILLERS, problems);
        if ("dirt".equals(filler)) filler = null;
        String waypoint = text(o, "waypoint", WAYPOINT, problems);
        String tradeItem = id(o, "trade_item", ids::item, ChatParser::lexiconItem, problems);
        if ("emerald".equals(tradeItem)) tradeItem = null;
        int tradeCount = count(o, "trade_count", problems);
        String schematic = text(o, "schematic", SCHEMATIC, problems);
        if (!problems.isEmpty()) return rejected(problems);

        String missing = switch (intent) {
            case WAYPOINT_SAVE, WAYPOINT_FORGET, WAYPOINT_GO -> waypoint == null ? "waypoint" : null;
            case TERRAFORM -> terraform == null ? "terraform" : null;
            case STANCE -> stance == null ? "stance" : null;
            case BUILD_SCHEMATIC -> schematic == null ? "schematic" : null;
            case FETCH_ANIMAL -> mob == null ? "mob" : null;
            case OBTAIN, MATERIALS_FOR, CRAFT_FOR_OWNER -> item == null ? "item" : null;
            case GATHER -> resource == null ? "resource" : null;
            case WHERE_IS -> resource == null && item == null ? "resource oder item" : null;
            default -> null;
        };
        if (missing != null) return rejected(new Problem(intent == ChatIntent.WHERE_IS ? Problem.Code.MISSING_CHOICE
                : Problem.Code.MISSING_FIELD, intent.name(), intent == ChatIntent.WHERE_IS ? "" : missing));

        ChatParams params = new ChatParams(count, resource, mob, item, stance, terraform, size, style, tier, filler,
                waypoint, bool(o, "to_owner"), bool(o, "for_owner"), tradeItem, tradeCount, bool(o, "sell"), schematic,
                bool(o, "with_air"));
        String language = playerLanguage != null && playerLanguage.toLowerCase(Locale.ROOT).startsWith("de") ? "de" : "en";
        return new Translation.Command(new ChatParser.Result(language, List.of(intent), params),
                intent == ChatIntent.BUILD_STRUCTURE && bool(o, "farm"), summary(intent, o));
    }

    /** Kurzform für Debug-Ausgaben: Intent und die tatsächlich gesetzten Felder. */
    private static String summary(ChatIntent intent, JsonObject o) {
        StringBuilder b = new StringBuilder(intent.name());
        for (var e : o.entrySet()) {
            if (e.getKey().equals("intent") || e.getValue().isJsonNull()) continue;
            b.append(' ').append(e.getKey()).append('=').append(e.getValue().isJsonPrimitive() ? e.getValue().getAsString() : e.getValue());
        }
        return b.length() > 200 ? b.substring(0, 200) : b.toString();
    }

    // --- Validierung ---

    private static String choice(JsonObject o, String key, Collection<String> allowed, List<Problem> problems) {
        if (absent(o, key)) return null;
        String v = string(o, key);
        if (v != null) {
            String t = v.strip();
            for (String a : allowed) if (a.equalsIgnoreCase(t)) return a;
        }
        problems.add(new Problem(Problem.Code.INVALID_FIELD, key, String.valueOf(o.get(key))));
        return null;
    }

    /**
     * @param lexicon Rückfall für Modelle, die trotz Prompt das deutsche Wort liefern ("kuh" statt "cow"): dieselbe
     *                Zuordnung wie der Parser, danach normale Prüfung
     */
    private static String id(JsonObject o, String key, java.util.function.Predicate<String> known,
            java.util.function.UnaryOperator<String> lexicon, List<Problem> problems) {
        if (absent(o, key)) return null;
        String v = string(o, key);
        String path = v == null ? null : v.strip().toLowerCase(Locale.ROOT);
        if (path != null && path.startsWith("minecraft:")) path = path.substring("minecraft:".length());
        if (path != null && !path.contains(":") && !(ID.matcher(path).matches() && known.test(path))) {
            String translated = lexicon.apply(path.replace('_', ' '));
            if (translated != null) path = translated;
        }
        if (path == null || !ID.matcher(path).matches() || !known.test(path)) {
            problems.add(new Problem(Problem.Code.UNKNOWN_ID, key, String.valueOf(o.get(key))));
            return null;
        }
        return path;
    }

    private static String text(JsonObject o, String key, Pattern allowed, List<Problem> problems) {
        if (absent(o, key)) return null;
        String v = string(o, key);
        String t = v == null ? null : v.replaceAll("\\s+", " ").strip().toLowerCase(Locale.ROOT);
        if (t == null || !allowed.matcher(t).matches()) {
            problems.add(new Problem(Problem.Code.INVALID_FIELD, key, String.valueOf(o.get(key))));
            return null;
        }
        return t;
    }

    private static int count(JsonObject o, String key, List<Problem> problems) {
        if (absent(o, key)) return ChatCount.NONE;
        JsonElement e = o.get(key);
        if (e.isJsonPrimitive() && e.getAsJsonPrimitive().isNumber()) {
            double v = e.getAsDouble();
            if (Double.isFinite(v) && v >= 1 && v == Math.rint(v)) return (int) Math.min(v, ChatCount.MAX);
        }
        problems.add(new Problem(Problem.Code.INVALID_FIELD, key, String.valueOf(e)));
        return ChatCount.NONE;
    }

    private static boolean bool(JsonObject o, String key) {
        JsonElement e = o.get(key);
        return e != null && e.isJsonPrimitive() && e.getAsJsonPrimitive().isBoolean() && e.getAsBoolean();
    }

    /** Modelle schicken für "nicht genannt" gern null oder "" statt das Feld wegzulassen. */
    private static boolean absent(JsonObject o, String key) {
        JsonElement e = o.get(key);
        return e == null || e.isJsonNull() || e.isJsonPrimitive() && e.getAsJsonPrimitive().isString() && e.getAsString().isBlank();
    }

    private static String string(JsonObject o, String key) {
        JsonElement e = o.get(key);
        return e != null && e.isJsonPrimitive() && e.getAsJsonPrimitive().isString() ? e.getAsString() : null;
    }

    private static JsonObject object(String json) {
        try {
            return JsonParser.parseString(json == null ? "" : json) instanceof JsonObject o ? o : null;
        } catch (RuntimeException ex) {
            return null;
        }
    }

    // --- Schema-Bausteine ---

    private static List<String> names(List<ChatIntent> intents) {
        return intents.stream().map(Enum::name).toList();
    }

    private static JsonObject enumProp(String description, Collection<String> values) {
        JsonObject o = strProp(description);
        JsonArray array = new JsonArray();
        values.forEach(array::add);
        o.add("enum", array);
        return o;
    }

    private static JsonObject strProp(String description) { return prop("string", description); }

    private static JsonObject boolProp(String description) { return prop("boolean", description); }

    private static JsonObject prop(String type, String description) {
        JsonObject o = new JsonObject();
        o.addProperty("type", type);
        if (description != null) o.addProperty("description", description);
        return o;
    }

    private static JsonObject object(JsonObject properties) {
        JsonObject o = new JsonObject();
        o.addProperty("type", "object");
        o.add("properties", properties);
        return o;
    }

    private static JsonObject function(String name, String description, JsonObject parameters) {
        JsonObject f = new JsonObject();
        f.addProperty("name", name);
        f.addProperty("description", description);
        f.add("parameters", parameters);
        JsonObject tool = new JsonObject();
        tool.addProperty("type", "function");
        tool.add("function", f);
        return tool;
    }
}
