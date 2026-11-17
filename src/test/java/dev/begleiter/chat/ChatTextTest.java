package dev.begleiter.chat;

import java.util.List;
import java.util.Locale;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import static org.junit.jupiter.api.Assertions.*;

/** Normalisierung und Mengen in beiden Sprachen. */
class ChatTextTest {
    @ParameterizedTest
    @CsvSource(delimiter = '|', value = {
            "RÄUM die Kisten auf!|raeum die kisten auf", "ÄÖÜẞ|aeoeuess", "  Holz,   Stein.  |holz stein",
            "EISEN|eisen", "64 Eisen!|64 eisen", "Max-Mustermann|max mustermann",
            "@ FOLLOW ME!!|follow me", "what's up|what is up", "Don't fight|do not fight",
            "I'll get some wood|i will get some wood", "what’s that|what is that", "   stop   |stop",
            "geht's dir gut|geht s dir gut"})
    void normalisierung(String input, String expected) {
        assertEquals(expected, ChatText.normalize(input));
    }

    /** Feste Ergebnisse der ersten (rein deutschen) Normalisierung; deutsche Befehle dürfen sich nicht ändern. */
    @ParameterizedTest
    @CsvSource(delimiter = '|', value = {
            "Bau ein großes Steinhaus!|bau ein grosses steinhaus", "Hör auf|hoer auf",
            "Merk dir das als Mine|merk dir das als mine", "Züchte Kühe|zuechte kuehe"})
    void deutscheNormalisierungBleibtWieBisher(String input, String expected) {
        assertEquals(expected, ChatText.normalize(input));
    }

    @Test
    void tuerkischeStandardLocaleVeraendertKeineBefehle() {
        Locale previous = Locale.getDefault();
        try {
            Locale.setDefault(Locale.forLanguageTag("tr-TR"));
            assertEquals("eisen", ChatText.normalize("EISEN"));
            assertEquals("i will", ChatText.normalize("I'LL"));
        } finally {
            Locale.setDefault(previous);
        }
    }

    @ParameterizedTest
    @CsvSource(delimiter = '|', value = {
            // bisherige Bedeutung
            "10 eisen|16|10", "9999 eisen|16|256", "zwei kuehe|16|2", "ein stack holz|16|64",
            "viel holz|16|48", "eisen|16|16", "0 eisen|16|0", "drei buecher|0|3",
            // Grenzfälle
            "256 eisen|16|256", "257 eisen|16|256", "99999 eisen|16|256", "5 und 7 eisen|16|5",
            "zehn 5 eisen|16|5", "einen stapel holz|16|64", "ein schaf|0|1", "ein paar fackeln|1|8",
            // Englisch
            "ten iron|16|10", "a stack of wood|16|64", "a few torches|1|8", "many logs|16|48", "two cows|0|2",
            "257 iron|16|256", "iron|16|16", "0 iron|16|0", "twenty arrows|1|20"})
    void mengen(String text, int fallback, int expected) {
        assertEquals(expected, ChatCount.parse(List.of(text.split(" ")), fallback));
    }

    @Test
    void negativeZahlVerliertDasVorzeichenBeimNormalisieren() {
        // Festgehaltenes Verhalten: "-5" wird zu "5". Die Ausführung muss 0 ablehnen; "alles" bedeutet 0 nie.
        assertEquals(5, ChatCount.parse(ChatText.tokens(ChatText.normalize("-5 eisen")), 16));
    }
}
