package dev.begleiter.chat;

import java.util.List;
import java.util.Map;

/**
 * Mengen aus Chat-Wörtern in beiden Sprachen. Eine Ziffernfolge gilt sofort (höchstens {@link #MAX}, auch bei
 * mehr als vier Ziffern), ein Stapel sofort 64, sonst das erste Zahlwort, sonst 1 bei einem Artikel.
 * Kommt keine Menge vor, gilt der Standardwert des Auftrags.
 */
public final class ChatCount {
    private ChatCount() {}

    public static final int MAX = 256;
    /** Rückgabe von {@link #find}, wenn der Satz keine Menge nennt. */
    public static final int NONE = -1;

    /** Artikel bedeuten 1, solange kein Zahlwort folgt ("ein Schaf" = 1, "ein paar" = 8, "a few" = 8). */
    private static final java.util.Set<String> ARTICLES = java.util.Set.of("ein", "eine", "einen", "einem", "a", "an");
    private static final Map<String, Integer> WORDS = Map.ofEntries(
            // Deutsch
            Map.entry("eins", 1), Map.entry("zwei", 2), Map.entry("drei", 3), Map.entry("vier", 4),
            Map.entry("fuenf", 5), Map.entry("sechs", 6), Map.entry("sieben", 7), Map.entry("acht", 8),
            Map.entry("neun", 9), Map.entry("zehn", 10), Map.entry("zwanzig", 20), Map.entry("dreissig", 30),
            Map.entry("fuenfzig", 50), Map.entry("hundert", 100), Map.entry("paar", 8),
            Map.entry("viel", 48), Map.entry("viele", 48), Map.entry("stack", 64), Map.entry("stapel", 64),
            // Englisch
            Map.entry("one", 1), Map.entry("two", 2), Map.entry("three", 3), Map.entry("four", 4),
            Map.entry("five", 5), Map.entry("six", 6), Map.entry("seven", 7), Map.entry("eight", 8),
            Map.entry("nine", 9), Map.entry("ten", 10), Map.entry("eleven", 11), Map.entry("twelve", 12),
            Map.entry("dozen", 12), Map.entry("fifteen", 15), Map.entry("twenty", 20), Map.entry("thirty", 30),
            Map.entry("forty", 40), Map.entry("fifty", 50), Map.entry("hundred", 100), Map.entry("few", 8),
            Map.entry("couple", 2), Map.entry("many", 48), Map.entry("lots", 48), Map.entry("stacks", 64));

    public static int parse(List<String> tokens, int fallback) {
        int found = find(tokens);
        return found == NONE ? fallback : found;
    }

    /** Menge im Satz oder {@link #NONE}. Sehr lange Ziffernfolgen werden wie große Zahlen begrenzt. */
    public static int find(List<String> tokens) {
        int result = NONE;
        boolean article = false;
        for (String t : tokens) {
            if (!t.isEmpty() && t.chars().allMatch(Character::isDigit))
                return t.length() > 4 ? MAX : Math.min(Integer.parseInt(t), MAX);
            if (ARTICLES.contains(t)) { article = true; continue; }
            Integer n = WORDS.get(t);
            if (n == null) continue;
            if (n == 64) return 64;
            if (result == NONE) result = n;
        }
        return result == NONE && article ? 1 : result;
    }
}
