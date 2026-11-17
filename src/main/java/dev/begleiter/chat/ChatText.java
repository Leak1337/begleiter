package dev.begleiter.chat;

import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * Sprachunabhängige Normalisierung von Chat-Eingaben. Deutsche Eingaben ergeben genau dieselbe Form wie
 * bisher (Kleinschreibung, Umlaute als ae/oe/ue/ss, nur a–z, 0–9 und einfache Leerzeichen). Zusätzlich werden
 * englische Verkürzungen vor dem Entfernen der Sonderzeichen aufgelöst ("what's" → "what is").
 */
public final class ChatText {
    private ChatText() {}

    private static final Pattern APOSTROPHES = Pattern.compile("[’‘`´]");
    private static final Map<String, String> CONTRACTIONS = Map.ofEntries(
            Map.entry("what's", "what is"), Map.entry("where's", "where is"), Map.entry("that's", "that is"),
            Map.entry("it's", "it is"), Map.entry("here's", "here is"), Map.entry("there's", "there is"),
            Map.entry("let's", "let us"), Map.entry("i'm", "i am"), Map.entry("you're", "you are"),
            Map.entry("we're", "we are"), Map.entry("i'll", "i will"), Map.entry("you'll", "you will"),
            Map.entry("i've", "i have"), Map.entry("i'd", "i would"), Map.entry("don't", "do not"),
            Map.entry("doesn't", "does not"), Map.entry("didn't", "did not"), Map.entry("can't", "can not"),
            Map.entry("cannot", "can not"), Map.entry("won't", "will not"), Map.entry("isn't", "is not"),
            Map.entry("aren't", "are not"), Map.entry("shouldn't", "should not"));
    private static final Pattern CONTRACTION = Pattern.compile("(?<![a-z])([a-z]+'[a-z]+)(?![a-z])");

    public static String normalize(String raw) {
        if (raw == null) return "";
        String text = APOSTROPHES.matcher(raw.toLowerCase(Locale.ROOT)).replaceAll("'");
        text = CONTRACTION.matcher(text).replaceAll(m -> CONTRACTIONS.getOrDefault(m.group(1), m.group(1)));
        return text.replace("ä", "ae").replace("ö", "oe").replace("ü", "ue").replace("ß", "ss")
                .replaceAll("[^a-z0-9 ]", " ").replaceAll("\\s+", " ").trim();
    }

    public static List<String> tokens(String normalized) {
        return normalized.isEmpty() ? List.of() : Arrays.asList(normalized.split(" "));
    }

    /** Ganzes Wort bzw. ganze Wortfolge. */
    static boolean contains(String text, String phrase) {
        return (" " + text + " ").contains(" " + phrase + " ");
    }

    static boolean startsWith(String text, String phrase) {
        return text.equals(phrase) || text.startsWith(phrase + " ");
    }

    static String removeFirst(String text, String phrase) {
        String padded = " " + text + " ";
        int at = padded.indexOf(" " + phrase + " ");
        if (at < 0) return text;
        return (padded.substring(0, at) + " " + padded.substring(at + phrase.length() + 2)).trim().replaceAll(" +", " ");
    }
}
