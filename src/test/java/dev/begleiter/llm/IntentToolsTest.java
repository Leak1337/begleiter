package dev.begleiter.llm;

import java.util.HashSet;
import java.util.List;
import java.util.Set;

import com.google.gson.JsonObject;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

import dev.begleiter.chat.ChatCount;
import dev.begleiter.chat.ChatIntent;
import dev.begleiter.chat.ChatParser;
import dev.begleiter.llm.IntentTools.Translation;

class IntentToolsTest {
    /** Wie die Registry: nur bekannte Items, Lexikon-Mobs plus ein Registry-Mob. */
    private static final IntentTools.Ids IDS = new IntentTools.Ids() {
        private final Set<String> items = Set.of("diamond_sword", "iron_pickaxe", "torch", "bread", "emerald", "oak_planks");
        @Override public boolean item(String path) { return items.contains(path); }
        @Override public boolean mob(String path) { return ChatParser.mobKeys().contains(path) || path.equals("mooshroom"); }
    };

    private static LlmResult call(String tool, String arguments) {
        return LlmResult.ok(null, List.of(new LlmResult.ToolCall("c1", tool, arguments)), 5, 0, 0);
    }

    private static Translation.Command command(String arguments) {
        Translation t = IntentTools.translate(call(IntentTools.COMMAND_TOOL, arguments), "de_de", IDS);
        return assertInstanceOf(Translation.Command.class, t, () -> "abgelehnt: " + t);
    }

    private static String rejected(String arguments) {
        Translation t = IntentTools.translate(call(IntentTools.COMMAND_TOOL, arguments), "de_de", IDS);
        return assertInstanceOf(Translation.Rejected.class, t, () -> "angenommen: " + t).reason();
    }

    @Test void everyOfferedIntentIsDescribedAndInTheSchema() {
        JsonObject command = IntentTools.tools().get(0).getAsJsonObject().getAsJsonObject("function");
        assertEquals(IntentTools.COMMAND_TOOL, command.get("name").getAsString());
        var intentEnum = command.getAsJsonObject("parameters").getAsJsonObject("properties").getAsJsonObject("intent").getAsJsonArray("enum");
        Set<String> inSchema = new HashSet<>();
        intentEnum.forEach(e -> inSchema.add(e.getAsString()));
        for (ChatIntent intent : ChatIntent.values()) {
            assertNotNull(IntentTools.DESCRIPTIONS.get(intent), "Beschreibung fehlt: " + intent);
            assertEquals(!IntentTools.EXCLUDED.contains(intent), inSchema.contains(intent.name()), intent.name());
        }
        assertFalse(inSchema.contains("MEMORY_CLEAR"));
        assertEquals(IntentTools.QUESTION_TOOL, IntentTools.tools().get(1).getAsJsonObject().getAsJsonObject("function").get("name").getAsString());
    }

    @Test void promptNamesEveryOfferedIntentAndAnswerLanguage() {
        String de = IntentTools.systemPrompt("de_de");
        for (ChatIntent intent : IntentTools.offered()) assertTrue(de.contains(intent.name() + "\n") || de.contains(intent.name() + " "), intent.name());
        assertTrue(de.contains("German sentence"));
        assertTrue(IntentTools.systemPrompt("en_us").contains("English sentence"));
        assertFalse(de.contains("MEMORY_CLEAR"));
        // Rohstoffe stehen als Enum im Schema, nicht noch einmal im Prompt.
        var resources = IntentTools.tools().get(0).getAsJsonObject().getAsJsonObject("function").getAsJsonObject("parameters")
                .getAsJsonObject("properties").getAsJsonObject("resource").getAsJsonArray("enum");
        assertEquals(ChatParser.resourceKeys().size(), resources.size());
    }

    /** Jedes Zeichen geht bei jeder Anfrage mit; grobe Obergrenze gegen schleichendes Wachstum. */
    @Test void promptAndSchemaStayCompact() {
        int chars = IntentTools.systemPrompt("de_de").length() + IntentTools.tools().toString().length();
        // 3664 Zeichen ≈ 1065 Prompt-Tokens bei Groq; das Plauder-Tool kostet etwa 250 Zeichen.
        assertTrue(chars < 4000, "Prompt + Schema: " + chars + " Zeichen");
    }

    @Test void resourceKeysMatchTheLexicon() {
        assertEquals(Set.of("holz", "stein", "kohle", "eisen", "gold", "diamant", "redstone", "lapis", "kupfer", "smaragd",
                "sand", "kies", "erde", "obsidian", "samen"), ChatParser.resourceKeys());
        assertTrue(ChatParser.mobKeys().containsAll(Set.of("sheep", "cow", "any")));
    }

    @Test void gatherMatchesWhatTheParserProducesForAKnownSentence() {
        var parsed = ChatParser.parse("bring mir 8 eisen", "de_de");
        assertNotNull(parsed);
        var llm = command("{\"intent\":\"GATHER\",\"resource\":\"eisen\",\"count\":8,\"to_owner\":true}").result();
        assertEquals(parsed.intents().contains(ChatIntent.GATHER), true);
        assertEquals(ChatIntent.GATHER, llm.first());
        assertEquals(parsed.params().count(), llm.params().count());
        assertEquals(parsed.params().resource(), llm.params().resource());
        assertEquals(parsed.params().toOwner(), llm.params().toOwner());
        assertEquals("de", llm.language());
    }

    @Test void obtainNormalisesItemIds() {
        var cmd = command("{\"intent\":\"obtain\",\"item\":\"minecraft:Diamond_Sword\",\"count\":3}");
        assertEquals(ChatIntent.OBTAIN, cmd.result().first());
        assertEquals("diamond_sword", cmd.result().params().item());
        assertEquals(3, cmd.result().params().count());
    }

    @Test void recipeOrdersRequireARealItemAndKeepExplicitModNamespaces() {
        for (String intent : List.of("MATERIALS_FOR", "CRAFT_FOR_OWNER")) {
            assertTrue(rejected("{\"intent\":\"" + intent + "\"}").contains("without item"));
            assertEquals(intent, command("{\"intent\":\"" + intent + "\",\"item\":\"torch\",\"count\":4}")
                    .result().first().name());
        }
        IntentTools.Ids modIds = new IntentTools.Ids() {
            @Override public boolean item(String id) { return id.equals("example:torch") || IDS.item(id); }
            @Override public boolean mob(String id) { return IDS.mob(id); }
        };
        var translated = IntentTools.translate(call(IntentTools.COMMAND_TOOL,
                "{\"intent\":\"CRAFT_FOR_OWNER\",\"item\":\"example:torch\"}"), "en_us", modIds);
        assertEquals("example:torch", assertInstanceOf(Translation.Command.class, translated).result().params().item());
        assertInstanceOf(Translation.Rejected.class, IntentTools.translate(call(IntentTools.COMMAND_TOOL,
                "{\"intent\":\"MATERIALS_FOR\",\"item\":\"unknown:torch\"}"), "en_us", modIds));
    }

    @Test void germanWordsFromTheModelAreMappedThroughTheLexicon() {
        assertEquals("cow", command("{\"intent\":\"FETCH_ANIMAL\",\"mob\":\"kuh\"}").result().params().mob());
        assertEquals("torch", command("{\"intent\":\"OBTAIN\",\"item\":\"Fackel\"}").result().params().item());
        assertEquals("diamond_sword", command("{\"intent\":\"OBTAIN\",\"item\":\"diamantschwert\"}").result().params().item());
        assertTrue(rejected("{\"intent\":\"FETCH_ANIMAL\",\"mob\":\"drache\"}").contains("mob"));
    }

    @Test void inventedOrExcludedIntentsAreRejected() {
        assertTrue(rejected("{\"intent\":\"FLY_TO_MOON\"}").startsWith("unknown intent"));
        assertTrue(rejected("{\"intent\":\"MEMORY_CLEAR\"}").startsWith("unknown intent"));
        assertTrue(rejected("{\"resource\":\"eisen\"}").startsWith("unknown intent"));
        assertTrue(rejected("kein json").contains("JSON"));
    }

    @Test void invalidValuesAreRejectedNotGuessed() {
        assertTrue(rejected("{\"intent\":\"GATHER\",\"resource\":\"iron\"}").contains("resource"));
        assertTrue(rejected("{\"intent\":\"OBTAIN\",\"item\":\"laser_gun\"}").contains("item"));
        assertTrue(rejected("{\"intent\":\"OBTAIN\",\"item\":\"../../etc\"}").contains("item"));
        assertTrue(rejected("{\"intent\":\"STANCE\",\"stance\":\"BERSERK\"}").contains("stance"));
        assertTrue(rejected("{\"intent\":\"FETCH_ANIMAL\",\"mob\":\"dragon\"}").contains("mob"));
        assertTrue(rejected("{\"intent\":\"GATHER\",\"resource\":\"holz\",\"count\":0}").contains("count"));
        assertTrue(rejected("{\"intent\":\"GATHER\",\"resource\":\"holz\",\"count\":2.5}").contains("count"));
        assertTrue(rejected("{\"intent\":\"GATHER\",\"resource\":\"holz\",\"count\":\"viele\"}").contains("count"));
        assertTrue(rejected("{\"intent\":\"WAYPOINT_SAVE\",\"waypoint\":\"" + "x".repeat(40) + "\"}").contains("waypoint"));
    }

    @Test void requiredParametersAreEnforced() {
        assertEquals("GATHER without resource", rejected("{\"intent\":\"GATHER\"}"));
        assertEquals("OBTAIN without item", rejected("{\"intent\":\"OBTAIN\",\"count\":2}"));
        assertEquals("WAYPOINT_GO without waypoint", rejected("{\"intent\":\"WAYPOINT_GO\"}"));
        assertEquals("TERRAFORM without terraform", rejected("{\"intent\":\"TERRAFORM\"}"));
        assertEquals("WHERE_IS without resource or item", rejected("{\"intent\":\"WHERE_IS\"}"));
        assertEquals(ChatIntent.STATUS, command("{\"intent\":\"STATUS\"}").result().first());
    }

    @Test void nullAndEmptyMeanNotGiven() {
        var params = command("{\"intent\":\"FOLLOW\",\"count\":null,\"item\":\"\",\"resource\":null,\"stance\":\" \"}").result().params();
        assertEquals(ChatCount.NONE, params.count());
        assertNull(params.item());
        assertNull(params.resource());
        assertNull(params.stance());
    }

    @Test void valuesAreMappedToTheParserConventions() {
        assertEquals(ChatCount.MAX, command("{\"intent\":\"GATHER\",\"resource\":\"holz\",\"count\":9999}").result().params().count());
        var house = command("{\"intent\":\"BUILD_STRUCTURE\",\"style\":\"wood\",\"size\":\"Small\"}");
        assertNull(house.result().params().style(), "Holz ist der Standard und wird wie beim Parser nicht gesetzt");
        assertEquals("small", house.result().params().size());
        assertFalse(house.farm());
        assertTrue(command("{\"intent\":\"BUILD_STRUCTURE\",\"farm\":true}").farm());
        assertFalse(command("{\"intent\":\"STATUS\",\"farm\":true}").farm());
        assertNull(command("{\"intent\":\"TERRAFORM\",\"terraform\":\"flatten\",\"filler\":\"dirt\"}").result().params().filler());
        assertEquals("FLATTEN", command("{\"intent\":\"TERRAFORM\",\"terraform\":\"flatten\"}").result().params().terraform());
        assertNull(command("{\"intent\":\"TRADE\",\"trade_item\":\"emerald\"}").result().params().tradeItem());
        assertEquals("mooshroom", command("{\"intent\":\"FETCH_ANIMAL\",\"mob\":\"mooshroom\"}").result().params().mob());
        assertEquals("eisenmine", command("{\"intent\":\"WAYPOINT_GO\",\"waypoint\":\"  Eisenmine \"}").result().params().waypoint());
    }

    @Test void questionsAndFailures() {
        var q = IntentTools.translate(call(IntentTools.QUESTION_TOOL, "{\"frage\":\"Zu welchem   Wegpunkt?\"}"), "de_de", IDS);
        assertEquals(new Translation.Question("Zu welchem Wegpunkt?"), q);
        assertInstanceOf(Translation.Rejected.class, IntentTools.translate(call(IntentTools.QUESTION_TOOL, "{\"frage\":\"\"}"), "de_de", IDS));
        assertInstanceOf(Translation.Rejected.class, IntentTools.translate(call("andere_funktion", "{}"), "de_de", IDS));
        assertEquals(new Translation.Chat(), IntentTools.translate(call(IntentTools.CHAT_TOOL, "{}"), "de_de", IDS));
        assertInstanceOf(Translation.Rejected.class, IntentTools.translate(LlmResult.ok("Hallo!", List.of(), 1, 0, 0), "de_de", IDS));
        var failed = IntentTools.translate(LlmResult.failure(LlmResult.Status.TIMEOUT, "zu langsam", 8000), "de_de", IDS);
        var rejected = assertInstanceOf(Translation.Rejected.class, failed);
        assertEquals("TIMEOUT: zu langsam", rejected.reason());
        assertEquals(List.of(new IntentTools.Problem(IntentTools.Problem.Code.RESULT_FAILURE, "TIMEOUT", "zu langsam")), rejected.problems());
    }

    @Test void englishPlayersGetEnglishLanguageTag() {
        var t = IntentTools.translate(call(IntentTools.COMMAND_TOOL, "{\"intent\":\"FOLLOW\"}"), "en_us", IDS);
        assertEquals("en", assertInstanceOf(Translation.Command.class, t).result().language());
    }

    @Test void requestForcesAToolAndTrimsText() {
        var gptOss = IntentTools.request(new Object(), "openai/gpt-oss-20b", "x".repeat(1000), "de_de");
        assertEquals("required", gptOss.toolChoice());
        assertEquals("low", gptOss.reasoningEffort());
        assertEquals(IntentTools.MAX_TEXT, gptOss.messages().get(1).content().length());
        assertEquals(3, gptOss.tools().size());
        assertNull(IntentTools.request(new Object(), "qwen/qwen3.8-27b", "hallo", "de_de").reasoningEffort());
    }
}
