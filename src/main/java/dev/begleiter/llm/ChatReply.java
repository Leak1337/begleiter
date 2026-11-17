package dev.begleiter.llm;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Freie Antwort des Begleiters: zweite, kleine Anfrage ohne Tools, nur wenn das Intent-Modell
 * {@link IntentTools#CHAT_TOOL} gewählt hat. Bekommt den {@link ContextSnapshot} und den {@link ChatHistory}-Verlauf;
 * die Antwort ist reiner Text und löst nie eine Aktion aus.
 */
public final class ChatReply {
    private ChatReply() {}

    public static final int MAX_REPLY = 240;

    public static LlmRequest request(Object owner, String model, String companionName, String playerLanguage,
            String context, List<LlmRequest.Message> history, String playerText) {
        List<LlmRequest.Message> messages = new ArrayList<>();
        messages.add(LlmRequest.Message.system(systemPrompt(companionName, playerLanguage, context)));
        messages.addAll(history);
        String text = playerText.length() > IntentTools.MAX_TEXT ? playerText.substring(0, IntentTools.MAX_TEXT) : playerText;
        messages.add(LlmRequest.Message.user(text));
        // gpt-oss zählt das Nachdenken zu den Completion-Tokens; 300 reichen für "low" plus zwei Sätze.
        LlmRequest request = new LlmRequest(owner, model, messages, null, null, 300, 0.7, null);
        return model.contains("gpt-oss") ? request.withReasoningEffort("low") : request;
    }

    static String systemPrompt(String companionName, String playerLanguage, String context) {
        boolean german = playerLanguage != null && playerLanguage.toLowerCase(Locale.ROOT).startsWith("de");
        String name = companionName == null || companionName.isBlank() ? dev.begleiter.CompanionEntity.DEFAULT_NAME : companionName.strip();
        return "You are " + name + ", the player's companion in Minecraft survival. Reply in " + (german ? "German" : "English")
                + ", casual, as yourself, at most two short sentences, no markdown. Use only facts from your state and the"
                + " conversation; never invent items, places or numbers. You cannot act in this reply: if the player wants"
                + " something done, tell them to ask you for it directly.\nYour state:\n" + context;
    }

    /** Modelltext für den Chat: eine Zeile, ohne Markdown und Anführungszeichen, gekürzt; null wenn leer. */
    public static String clean(String content) {
        if (content == null) return null;
        // Kein "_": Item-IDs wie iron_ingot sollen lesbar bleiben.
        String text = content.replaceAll("[*`#>]", "").replaceAll("\\s+", " ").strip();
        if (text.length() >= 2 && (text.startsWith("\"") && text.endsWith("\"") || text.startsWith("„") && text.endsWith("“")))
            text = text.substring(1, text.length() - 1).strip();
        if (text.isEmpty()) return null;
        if (text.length() <= MAX_REPLY) return text;
        // Am Satzende kürzen, wenn eines in der zweiten Hälfte liegt, sonst hart mit Auslassung.
        int cut = Math.max(text.lastIndexOf(". ", MAX_REPLY), Math.max(text.lastIndexOf("! ", MAX_REPLY), text.lastIndexOf("? ", MAX_REPLY)));
        return cut > MAX_REPLY / 2 ? text.substring(0, cut + 1) : text.substring(0, MAX_REPLY) + "…";
    }
}
