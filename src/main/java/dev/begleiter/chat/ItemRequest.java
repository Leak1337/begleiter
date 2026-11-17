package dev.begleiter.chat;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/** Ausdrückliche Rezeptaufträge. Die Zielwörter bleiben bis zur Namenssuche in der Registry unverändert. */
public record ItemRequest(ChatIntent intent, String target, int count, String language) {
    private static final Set<String> PREFIX = Set.of("mir", "uns", "me", "us", "bitte", "please", "ein", "eine",
            "einen", "einem", "einer", "a", "an", "the", "der", "die", "das", "den", "dem");
    private static final Set<String> END = Set.of("bitte", "please", "her");

    public static ItemRequest parse(String raw) {
        if (raw == null || raw.length() > 256) return null;
        // Keep identifier punctuation: Sentence intentionally removes it for ordinary chat.
        String text = raw.strip().replaceAll("[!?.,]+$", "").replaceAll("[\"“”„']+", " ");
        List<String> words = new ArrayList<>(List.of(text.split("\\s+")));
        while (!words.isEmpty() && Set.of("please", "bitte").contains(ChatText.normalize(words.getFirst()))) words.removeFirst();
        if (words.isEmpty()) return null;
        Sentence s = Sentence.of(String.join(" ", words));
        String verb = ChatText.normalize(words.getFirst());
        boolean make = Set.of("craft", "crafte", "craften", "make", "create", "produce", "mach", "mache",
                "stell", "stelle", "herstellen").contains(verb);
        boolean gather = Set.of("bring", "bringe", "bringen", "get", "fetch", "hol", "hole", "holen",
                "besorg", "besorge", "besorgen", "sammle", "sammel", "collect", "gather").contains(verb);
        boolean de = Set.of("crafte", "craften", "mach", "mache", "stell", "stelle", "herstellen", "bringe",
                "bringen", "hol", "hole", "holen", "besorg", "besorge", "besorgen", "sammle", "sammel").contains(verb)
                || s.any("mir", "uns", "fuer");
        int start = 1;
        ChatIntent intent;
        if (gather && s.any("ressourcen", "materialien", "zutaten", "resources", "materials", "ingredients")) {
            int noun = -1;
            for (int i = 1; i < words.size(); i++) {
                if (Set.of("ressourcen", "materialien", "zutaten", "resources", "materials", "ingredients")
                        .contains(ChatText.normalize(words.get(i)))) { noun = i; break; }
            }
            if (noun < 0 || noun + 1 >= words.size()
                    || !Set.of("fuer", "for").contains(ChatText.normalize(words.get(noun + 1)))) return null;
            start = noun + 2;
            intent = ChatIntent.MATERIALS_FOR;
        } else if (make) {
            // Aufträge für die eigene Ausrüstung laufen weiter über die Lexika.
            if (s.any("dich", "dein", "deine", "your", "yourself")) return null;
            if (!Set.of("craft", "crafte", "craften").contains(verb)
                    && !s.any("mir", "uns", "me", "us") && !s.tokens().getLast().equals("her")) return null;
            intent = ChatIntent.CRAFT_FOR_OWNER;
        } else return null;
        List<String> target = new ArrayList<>(words.subList(start, words.size()));
        while (!target.isEmpty() && PREFIX.contains(ChatText.normalize(target.getFirst()))) target.removeFirst();
        int count = 1;
        if (!target.isEmpty()) {
            String word = ChatText.normalize(target.getFirst());
            int n = ChatCount.find(List.of(word));
            if (n != ChatCount.NONE) { count = n; target.removeFirst(); }
        }
        while (!target.isEmpty() && PREFIX.contains(ChatText.normalize(target.getFirst()))) target.removeFirst();
        while (!target.isEmpty() && END.contains(ChatText.normalize(target.getLast()))) target.removeLast();
        return new ItemRequest(intent, String.join(" ", target), count, de ? "de" : "en");
    }

    public ChatParams params() {
        return new ChatParams(count, null, null, ChatParser.lexiconItem(target), null, null, null, null, null, null, null,
                true, true, null, ChatCount.NONE, false, null, false, target);
    }
}
