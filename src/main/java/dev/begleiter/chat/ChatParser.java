package dev.begleiter.chat;

import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.TreeSet;

/**
 * Beide Lexika sind immer aktiv: zuerst das der Spielersprache, dann das andere. So versteht der Begleiter
 * auch einen deutschen Spieler, der englisch schreibt (und umgekehrt).
 */
public final class ChatParser {
    private ChatParser() {}

    /**
     * @param language Sprache des Lexikons, das den Satz erkannt hat ("de"/"en")
     * @param intents  passende Absichten in Prioritätsreihenfolge, nie leer
     */
    public record Result(String language, List<ChatIntent> intents, ChatParams params) {
        public ChatIntent first() { return intents.getFirst(); }
    }

    /**
     * @param command        Befehl ohne Anrede (z.B. aus {@link ChatAddress.Command#text()})
     * @param playerLanguage Minecraft-Sprachcode des Spielers ("de_de", "en_us", ...); null = Englisch
     * @return null, wenn kein Lexikon den Satz versteht
     */
    public static Result parse(String command, String playerLanguage) {
        ItemRequest request = ItemRequest.parse(command);
        if (request != null) return new Result(request.language(), List.of(request.intent()), request.params());
        Sentence sentence = Sentence.of(command);
        if (sentence.tokens().isEmpty()) return null;
        for (Lexicon lexicon : order(playerLanguage)) {
            List<ChatIntent> intents = lexicon.intents(sentence);
            if (!intents.isEmpty()) return new Result(lexicon.language(), intents, lexicon.params(sentence));
        }
        return null;
    }

    /** Alle Rohstoff-Schlüssel, die die Lexika liefern können ("holz", "eisen", ... wie in {@code Resources}). */
    public static Set<String> resourceKeys() {
        return Collections.unmodifiableSet(new TreeSet<>(LexiconEn.RESOURCES.values()));
    }

    /** Alle Tier-/Mob-Werte der Lexika ("sheep", ..., "any"). */
    public static Set<String> mobKeys() {
        Set<String> keys = new TreeSet<>(LexiconEn.MOBS.values());
        keys.addAll(LexiconDe.MOBS.values());
        return Collections.unmodifiableSet(keys);
    }

    /** Tier-ID für ein Wort aus einem der Lexika ("kuh", "cows" → "cow"), sonst null. */
    public static String lexiconMob(String words) {
        Sentence s = Sentence.of(words);
        String id = LexiconDe.mob(s);
        return id != null ? id : LexiconEn.mob(s);
    }

    /** Item-ID für ein Wort aus einem der Lexika ("fackel" → "torch", "eisenspitzhacke" → "iron_pickaxe"), sonst null. */
    public static String lexiconItem(String words) {
        Sentence s = Sentence.of(words);
        String id = LexiconDe.item(s);
        return id != null ? id : LexiconEn.item(s);
    }

    /** Feste Synonyme ergänzen die Übersetzungen aus der Registry; sie schränken die verfügbaren Items nicht ein. */
    public static java.util.Map<String, String> itemAliases() {
        var aliases = new java.util.LinkedHashMap<>(LexiconDe.ITEMS);
        aliases.putAll(LexiconEn.ITEMS);
        aliases.putAll(LexiconEn.PHRASE_ITEMS);
        LexiconDe.TOOLS.forEach((word, piece) -> aliases.putIfAbsent(word, ChatItems.toolOrArmor(null, piece)));
        LexiconEn.TOOLS.forEach((word, piece) -> aliases.putIfAbsent(word, ChatItems.toolOrArmor(null, piece)));
        return java.util.Collections.unmodifiableMap(aliases);
    }

    /** {@code de_*} → Deutsch zuerst, alles andere → Englisch zuerst (Entscheidung D1). */
    public static List<Lexicon> order(String playerLanguage) {
        boolean german = playerLanguage != null && playerLanguage.toLowerCase(Locale.ROOT).startsWith("de");
        return german ? List.of(LexiconDe.INSTANCE, LexiconEn.INSTANCE) : List.of(LexiconEn.INSTANCE, LexiconDe.INSTANCE);
    }
}
