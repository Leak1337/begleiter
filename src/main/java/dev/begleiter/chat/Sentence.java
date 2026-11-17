package dev.begleiter.chat;

import java.util.List;

/** Ein normalisierter Befehlssatz mit den Prüfungen, die beide Lexika brauchen. */
public record Sentence(String text, List<String> tokens) {
    public static Sentence of(String raw) {
        String text = ChatText.normalize(raw);
        return new Sentence(text, ChatText.tokens(text));
    }

    /** Ein Token ist genau eines der Wörter. */
    public boolean any(String... words) {
        for (String t : tokens) for (String w : words) if (t.equals(w)) return true;
        return false;
    }

    /** Ein Token beginnt mit einem der Präfixe ("reparier" trifft "reparieren"). */
    public boolean anyStart(String... prefixes) {
        for (String t : tokens) for (String p : prefixes) if (t.startsWith(p)) return true;
        return false;
    }

    /** Teilzeichenkette im ganzen Satz, wie die bisherige Erkennung ({@code text.contains}). */
    public boolean has(String... parts) {
        for (String p : parts) if (text.contains(p)) return true;
        return false;
    }

    /** Ganze Wortfolge im Satz. */
    public boolean phrase(String... phrases) {
        for (String p : phrases) if (ChatText.contains(text, p)) return true;
        return false;
    }

    /** Restliche Wörter ohne Füllwörter und Zahlen, z.B. der Schematic-Name; null wenn nichts übrig bleibt. */
    static String query(Sentence s, java.util.Set<String> filler) {
        List<String> words = s.tokens.stream().filter(t -> !filler.contains(t) && !t.matches("\\d+")).toList();
        return words.isEmpty() ? null : String.join(" ", words);
    }

    public Sentence after(String word) {
        int at = tokens.indexOf(word);
        if (at < 0) return this;
        List<String> rest = tokens.subList(at + 1, tokens.size());
        return new Sentence(String.join(" ", rest), rest);
    }
}
