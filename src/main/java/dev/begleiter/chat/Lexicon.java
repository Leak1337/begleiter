package dev.begleiter.chat;

import java.util.List;

/**
 * Wörter einer Sprache → Absichten und sprachneutrale Angaben. Ausführung, Zustand und Antworttexte
 * gehören nicht hierher.
 */
public interface Lexicon {
    /** "de" oder "en". */
    String language();

    /**
     * Alle passenden Absichten in Prioritätsreihenfolge. Die Ausführung nimmt die erste, die im aktuellen
     * Zustand möglich ist (z.B. PAUSE nur mit Bauauftrag), und fällt sonst wie bisher zur nächsten durch.
     */
    List<ChatIntent> intents(Sentence s);

    ChatParams params(Sentence s);
}
