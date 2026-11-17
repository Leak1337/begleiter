package dev.begleiter.chat;

import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

import static org.junit.jupiter.api.Assertions.*;

/** Anrede mit Leak/Bot/Begleiter/Companion und eigenem Namen. */
class ChatAddressTest {
    private static final UUID MAX = new UUID(0, 1), MORITZ = new UUID(0, 2);
    private static final List<ChatAddress.Candidate> ONE = List.of(new ChatAddress.Candidate(MAX, "Max", 4));

    @ParameterizedTest
    @ValueSource(strings = {"ich spiele gleich", "komm schon", "stop", "Bring mir Holz", "maximal folge mir",
            "Maxine folge mir", "Kohlebegleiter spiele", "Reisebegleiter stopp", "   ", "", "@", "Max", "Begleiter",
            "@Karl folge mir", "Hallo @ folge mir", "@Karl Max folge mir",
            // Englisch und neue Anredewörter
            "I'll get some wood", "der Bot hängt schon wieder", "the bot is stuck again", "Botschaft folge mir",
            "Leak1337 folge mir", "robot follow me", "leakage follow me", "@Moritz follow me", "@Moritz folge mir",
            "Leak", "Bot", "@Leak", "hello companion follow me"})
    void alltagUndUnklareAnredeBleibenOhneBefehl(String raw) {
        assertNull(ChatAddress.parse(raw, ONE), raw);
    }

    @ParameterizedTest
    @CsvSource(delimiter = '|', value = {
            "@ folge mir|folge mir", "  @ folge mir!|folge mir", "Begleiter, folge mir|folge mir",
            "BEGLEITER: FOLGE MIR!|folge mir", "Max, folge mir|folge mir", "@Max folge mir|folge mir",
            "Bitte Max folge mir|bitte folge mir", "Folge mir, Max.|folge mir",
            "@Begleiter folge mir|folge mir", "Begleiter Max folge mir|max folge mir",
            "Max Begleiter folge mir|folge mir", "Max merk dir das als Max|merk dir das als max",
            "Begleiter merk dir das als Begleiter|merk dir das als begleiter",
            // Leak und Bot
            "Leak, folge mir|folge mir", "@Leak folge mir|folge mir", "Folge mir, Leak|folge mir",
            "Kannst du mir helfen, Leak?|kannst du mir helfen", "Bot, folge mir|folge mir", "@Bot folge mir|folge mir",
            "Leak, follow me|follow me", "@Leak follow me|follow me", "Follow me, Leak|follow me",
            "Bot, follow me|follow me", "@Bot follow me|follow me", "Companion, follow me|follow me",
            "@companion follow me|follow me", "Max, follow me|follow me", "@Max follow me|follow me",
            "@ what's up?|what is up"})
    void anredeWirdVomEigentlichenBefehlGetrennt(String raw, String expected) {
        var command = ChatAddress.parse(raw, ONE);
        assertNotNull(command, raw);
        assertEquals(MAX, command.target());
        assertEquals(expected, command.text());
    }

    @Test
    void leakUndBotWirkenAuchNachUmbenennen() {
        // Der einzige Begleiter heißt "Max": allgemeine Anredewörter wählen trotzdem ihn.
        for (String raw : new String[] {"Leak, folge mir", "Bot, folge mir", "Begleiter, folge mir"})
            assertEquals(MAX, ChatAddress.parse(raw, ONE).target(), raw);
    }

    @Test
    void allgemeineAnredeWaehltDenNaechstenEigenenNamenGenau() {
        var two = List.of(new ChatAddress.Candidate(MORITZ, "Moritz", 1), new ChatAddress.Candidate(MAX, "Max", 25));
        assertEquals(MORITZ, ChatAddress.parse("Leak, folge mir", two).target());
        assertEquals(MORITZ, ChatAddress.parse("Bot, follow me", two).target());
        assertEquals(MAX, ChatAddress.parse("Max, folge mir", two).target());
        assertEquals(MAX, ChatAddress.parse("@Max follow me", two).target());
    }

    @Test
    void begleiterNamensLeakGiltAlsAllgemeineAnrede() {
        var two = List.of(new ChatAddress.Candidate(MORITZ, "Leak", 25), new ChatAddress.Candidate(MAX, "Max", 1));
        var command = ChatAddress.parse("Leak, folge mir", two);
        assertEquals(MAX, command.target(), "bei allgemeiner Anrede gilt der nächste");
        assertEquals("folge mir", command.text());
    }

    @Test
    void nameGehtVorEntfernungUndEingabereihenfolge() {
        var candidates = List.of(new ChatAddress.Candidate(MORITZ, "Moritz", 1), new ChatAddress.Candidate(MAX, "Max", 25));
        assertEquals(MAX, ChatAddress.parse("Max folge mir", candidates).target());
        assertEquals(MORITZ, ChatAddress.parse("@ folge mir", candidates).target());
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
    void leererUmkreisHatKeinZiel() {
        assertNull(ChatAddress.parse("@ folge mir", List.of()));
    }

    @Test
    void anredewoerterSindVollstaendig() {
        assertEquals(List.of("leak", "begleiter", "bot", "companion"), ChatAddress.addressWords());
    }
}
