package dev.begleiter.chat;

import java.util.List;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Pflicht-Befehlsliste: Jeder deutsche Satz und seine englische Entsprechung ergeben
 * dieselbe Absicht mit denselben sprachneutralen Angaben – egal welche Sprache der Spieler eingestellt hat.
 */
class LexiconTest {
    private static ChatParser.Result parse(String text, String language) {
        var result = ChatParser.parse(text, language);
        assertNotNull(result, "nicht verstanden: " + text + " (" + language + ")");
        return result;
    }

    @ParameterizedTest(name = "{0} = {1}")
    @CsvSource(delimiter = '|', value = {
            "Folge mir|follow me|FOLLOW",
            "Warte hier|stay here|STAY",
            "Warte hier|wait here|STAY",
            "Spiel selbstständig|play on your own|FREE_ROAM",
            "Hör auf|stop|CANCEL",
            "Stopp|cancel|CANCEL",
            "Weiter|continue|RESUME",
            "Weiter|resume|RESUME",
            "Bring mir 10 Eisen|bring me 10 iron|GATHER",
            "Mach mir eine Eisenspitzhacke|make me an iron pickaxe|CRAFT_FOR_OWNER",
            "Mach mir eine Eisenspitzhacke|craft me an iron pickaxe|CRAFT_FOR_OWNER",
            "Bau ein großes Steinhaus|build a large stone house|BUILD_SCHEMATIC",
            "Bau eine Farm|build a farm|BUILD_SCHEMATIC",
            "Ernte das Feld|harvest the field|FARM",
            "Züchte Kühe|breed cows|BREED",
            "Geh angeln|go fishing|FISH",
            "Reparier dein Werkzeug|repair your tools|REPAIR",
            "Kauf mir ein Buch|buy me a book|TRADE",
            "Verkauf dein Zeug|sell your stuff|TRADE",
            "Rüste dich aus|gear up|GEAR_UP",
            "Planier hier 15x15|flatten here 15x15|TERRAFORM",
            "Merk dir das als Mine|remember this as mine|WAYPOINT_SAVE",
            "Geh zur Mine|go to mine|WAYPOINT_GO",
            "Vergiss den Wegpunkt Mine|forget waypoint mine|WAYPOINT_FORGET",
            "Sei defensiv|be defensive|STANCE",
            "Beschütz mich|protect me|PROTECT",
            "Was weißt du?|what do you know?|MEMORY_SUMMARY",
            "Wo ist Eisen?|where is iron?|WHERE_IS",
            "Vergiss dein Gedächtnis|forget your memory|WAYPOINT_FORGET",
            "Warum machst du das?|why are you doing that?|EXPLAIN",
            "Status|status|STATUS",
            "Das ist dein Lager|this is your storage|SET_HOME",
            "Kümmer dich um das Lager|manage the storage|KEEPER",
            "Räum die Kisten auf|sort the chests|SORT",
            "Bau hier eine Tür ein|put a door here|DOOR",
            "Welche Schematics gibt es?|list schematics|LIST_SCHEMATICS",
            "Bau lager|build lager|BUILD_SCHEMATIC",
            "Plane eine kleine Basis|plan a small base|PROJECT_PREVIEW",
            "Pausiere|pause|PAUSE",
            "Bring mir zehn Eisen|bring me ten iron|GATHER",
            "Bring mir einen Stapel Holz|bring me a stack of wood|GATHER",
            "Bringe mir 4 Diamanten|bring me 4 diamonds|OBTAIN",
            "Hol ein Schaf|fetch a sheep|FETCH_ANIMAL",
            "Gib mir alles|give me everything|GIVE"})
    void deutschUndEnglischErgebenDenselbenAuftrag(String de, String en, ChatIntent expected) {
        for (String language : new String[] {"de_de", "en_us"}) {
            var d = parse(de, language);
            var e = parse(en, language);
            assertEquals(expected, d.first(), "DE " + de + " bei " + language);
            assertEquals(expected, e.first(), "EN " + en + " bei " + language);
            if (language.equals("de_de")) assertEquals("de", d.language(), de);
            assertEquals(d.params().count(), e.params().count(), "Menge");
            assertEquals(d.params().resource(), e.params().resource(), "Rohstoff");
            assertEquals(d.params().mob(), e.params().mob(), "Tier");
            assertEquals(d.params().stance(), e.params().stance(), "Kampfmodus");
            assertEquals(d.params().terraform(), e.params().terraform(), "Terraforming");
            assertEquals(d.params().size(), e.params().size(), "Größe");
            assertEquals(d.params().style(), e.params().style(), "Stil");
            if (expected == ChatIntent.GATHER || expected == ChatIntent.OBTAIN)
                assertEquals(d.params().toOwner(), e.params().toOwner(), "an Spieler");
            if (expected == ChatIntent.TRADE) {
                assertEquals(d.params().tradeItem(), e.params().tradeItem(), "Handelsware");
                assertEquals(d.params().sell(), e.params().sell(), "Verkauf");
            } else assertEquals(d.params().item(), e.params().item(), "Item");
        }
    }

    @Test
    void angabenSindStabileIds() {
        var gather = parse("bring me 10 iron", "en_us").params();
        assertEquals(10, gather.count());
        assertEquals("eisen", gather.resource());
        assertTrue(gather.toOwner());
        assertEquals("iron_pickaxe", parse("Mach mir eine Eisenspitzhacke", "de_de").params().item());
        assertEquals("diamond_sword", parse("make me a diamond sword", "en_us").params().item());
        assertEquals("iron_chestplate", parse("craft me a chestplate", "en_us").params().item());
        assertEquals("stone_pickaxe", parse("Mach mir eine Spitzhacke", "de_de").params().item());
        assertEquals(64, parse("bring me a stack of wood", "en_us").params().count());
        assertEquals("holz", parse("bring me a stack of wood", "en_us").params().resource());
        assertEquals("cow", parse("breed cows", "en_us").params().mob());
        assertEquals("DEFENSIVE", parse("be defensive", "en_us").params().stance());
        assertEquals("FLATTEN", parse("flatten here 15x15", "en_us").params().terraform());
        assertEquals("mine", parse("Merk dir das als meine Mine", "de_de").params().waypoint());
        assertEquals("mine", parse("remember this as my mine", "en_us").params().waypoint());
        assertEquals("lager", parse("Bau lager", "de_de").params().schematic());
        assertEquals("lager", parse("build lager with air", "en_us").params().schematic());
        assertTrue(parse("build lager with air", "en_us").params().withAir());
        assertEquals("book", parse("trade emeralds for books", "en_us").params().tradeItem());
        assertEquals("book", parse("Tausch Smaragde gegen Bücher", "de_de").params().tradeItem());
        assertEquals("large", parse("build a large stone house", "en_us").params().size());
        assertEquals("small", parse("Plane eine kleine Basis", "de_de").params().size());
    }

    @Test
    void zustandsabhaengigeAbsichtenBleibenInDerAlternativliste() {
        // "Pause" ohne Bauauftrag fällt in der Ausführung wie bisher zur nächsten Absicht durch.
        assertEquals(List.of(ChatIntent.WAYPOINT_FORGET, ChatIntent.MEMORY_CLEAR, ChatIntent.MEMORY_SUMMARY, ChatIntent.CANCEL),
                parse("Vergiss dein Gedächtnis", "de_de").intents());
        assertEquals(List.of(ChatIntent.WAYPOINT_FORGET, ChatIntent.MEMORY_CLEAR, ChatIntent.MEMORY_SUMMARY),
                parse("forget your memory", "en_us").intents());
        assertTrue(parse("Bringe mir 4 Diamanten", "de_de").intents().contains(ChatIntent.GATHER),
                "ohne Rezeptweg bleibt Rohstoff-Sammeln als Alternative");
        assertTrue(parse("build a large stone house", "en_us").intents().contains(ChatIntent.BUILD_STRUCTURE));
        assertTrue(parse("Bau ein großes Steinhaus", "de_de").intents().contains(ChatIntent.BUILD_STRUCTURE));
    }

    @ParameterizedTest
    @ValueSource(strings = {"hallo", "hello there", "the weather is nice", "das Wetter ist schön", "gute Nacht",
            "good night"})
    void alltagssaetzeSindKeinAuftrag(String text) {
        // Ob überhaupt jemand angesprochen wurde, entscheidet vorher ChatAddress ("ich hole mal Holz" → niemand).
        for (String language : new String[] {"de_de", "en_us"})
            assertNull(ChatParser.parse(text, language), text);
    }

    @Test
    void spielerspracheBestimmtNurDieReihenfolge() {
        assertEquals(List.of(LexiconDe.INSTANCE, LexiconEn.INSTANCE), ChatParser.order("de_de"));
        assertEquals(List.of(LexiconDe.INSTANCE, LexiconEn.INSTANCE), ChatParser.order("de_at"));
        assertEquals(List.of(LexiconEn.INSTANCE, LexiconDe.INSTANCE), ChatParser.order("en_us"));
        assertEquals(List.of(LexiconEn.INSTANCE, LexiconDe.INSTANCE), ChatParser.order("fr_fr"));
        assertEquals(List.of(LexiconEn.INSTANCE, LexiconDe.INSTANCE), ChatParser.order(null));
        assertEquals("en", parse("follow me", "de_de").language());
        assertEquals("de", parse("folge mir", "en_us").language());
    }

    @Test
    void englischeWoerterKollidierenNichtMitWortanfaengen() {
        assertEquals("sheep", parse("catch a sheep", "en_us").params().mob(), "catch ist kein cat");
        assertNull(parse("make me a pickaxe", "en_us").params().mob());
        assertEquals("stone_pickaxe", parse("make me a pickaxe", "en_us").params().item(), "axe in pickaxe zählt nicht");
    }
}
