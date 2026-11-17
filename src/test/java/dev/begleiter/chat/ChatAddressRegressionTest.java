package dev.begleiter.chat;

import java.util.List;
import java.util.Locale;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

import static org.junit.jupiter.api.Assertions.*;

/** Deutsche Anrede-Fälle aus der ersten Chat-Erkennung, jetzt gegen {@link ChatAddress}. */
class ChatAddressRegressionTest {
    private static final UUID MAX = new UUID(0, 1), MORITZ = new UUID(0, 2);
    private static final List<ChatAddress.Candidate> ONE = List.of(new ChatAddress.Candidate(MAX, "Max", 4));

    @ParameterizedTest
    @ValueSource(strings = {"ich spiele gleich", "komm schon", "stop", "Bring mir Holz", "maximal folge mir",
            "Maxine folge mir", "Kohlebegleiter spiele", "Reisebegleiter stopp", "   ", "", "@", "Max", "Begleiter",
            "@Karl folge mir", "Hallo @ folge mir", "@Karl Max folge mir"})
    void alltagUndUnklareAnredeBleibenOhneBefehl(String raw) {
        assertNull(ChatAddress.parse(raw, ONE));
    }

    @ParameterizedTest
    @CsvSource(delimiter = '|', value = {
            "@ folge mir|folge mir", "  @ folge mir!|folge mir", "Begleiter, folge mir|folge mir",
            "BEGLEITER: FOLGE MIR!|folge mir", "Max, folge mir|folge mir", "@Max folge mir|folge mir",
            "Bitte Max folge mir|bitte folge mir", "Folge mir, Max.|folge mir",
            "@Begleiter folge mir|folge mir", "Begleiter Max folge mir|max folge mir",
            "Max Begleiter folge mir|folge mir", "Max merk dir das als Max|merk dir das als max",
            "Begleiter merk dir das als Begleiter|merk dir das als begleiter"})
    void anredeWirdVomEigentlichenBefehlGetrennt(String raw, String expected) {
        var command = ChatAddress.parse(raw, ONE);
        assertNotNull(command);
        assertEquals(MAX, command.target());
        assertEquals(expected, command.text());
    }

    @Test
    void tuerkischeStandardLocaleVeraendertKeineAnrede() {
        Locale previous = Locale.getDefault();
        try {
            Locale.setDefault(Locale.forLanguageTag("tr-TR"));
            assertEquals("folge mir", ChatAddress.parse("MAX, FOLGE MIR!", ONE).text());
        } finally {
            Locale.setDefault(previous);
        }
    }

    @Test
    void nameGehtVorEntfernungUndEingabereihenfolge() {
        var candidates = List.of(new ChatAddress.Candidate(MORITZ, "Moritz", 1), new ChatAddress.Candidate(MAX, "Max", 25));
        assertEquals(MAX, ChatAddress.parse("Max folge mir", candidates).target());
        assertEquals(MORITZ, ChatAddress.parse("@ folge mir", candidates).target());
    }

    @Test
    void gleicherNameUndAbstandWerdenStabilAufgeloest() {
        var a = new ChatAddress.Candidate(MAX, "Max", 4);
        var b = new ChatAddress.Candidate(MORITZ, "Max", 4);
        assertEquals(MAX, ChatAddress.parse("Max folge mir", List.of(b, a)).target());
        assertEquals(MAX, ChatAddress.parse("Max folge mir", List.of(a, b)).target());
    }

    @Test
    void allgemeineAnredeVerwechseltBefehlswoerterNichtMitNamen() {
        var candidates = List.of(new ChatAddress.Candidate(MAX, "Max", 1), new ChatAddress.Candidate(MORITZ, "Folge", 25));
        var command = ChatAddress.parse("@ folge mir", candidates);
        assertEquals(MAX, command.target());
        assertEquals("folge mir", command.text());
        var resources = List.of(new ChatAddress.Candidate(MAX, "Max", 1), new ChatAddress.Candidate(MORITZ, "Holz", 25));
        assertEquals(MAX, ChatAddress.parse("Begleiter, bring mir Holz", resources).target());
        assertEquals("bring mir holz", ChatAddress.parse("Begleiter, bring mir Holz", resources).text());
    }

    @Test
    void verschiedeneGenannteNamenSindMehrdeutig() {
        assertNull(ChatAddress.parse("Max und Moritz folge mir", List.of(
                new ChatAddress.Candidate(MAX, "Max", 1), new ChatAddress.Candidate(MORITZ, "Moritz", 4))));
    }

    @Test
    void vollstaendigerMehrwortnameGehtVorEnthaltenemKurznamen() {
        var command = ChatAddress.parse("Max Mustermann, folge mir", List.of(
                new ChatAddress.Candidate(MAX, "Max", 1), new ChatAddress.Candidate(MORITZ, "Max Mustermann", 4)));
        assertEquals(MORITZ, command.target());
        assertEquals("folge mir", command.text());
    }

    @Test
    void unbrauchbareNamenSindKeineVersteckteAnsprache() {
        var candidates = List.of(new ChatAddress.Candidate(MAX, "✨", 1));
        assertNull(ChatAddress.parse("ich spiele gleich", candidates));
        assertEquals(MAX, ChatAddress.parse("@ folge mir", candidates).target());
    }

    @Test
    void leererUmkreisHatKeinZiel() {
        assertNull(ChatAddress.parse("@ folge mir", List.of()));
    }
}
