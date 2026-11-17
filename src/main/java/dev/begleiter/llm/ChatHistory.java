package dev.begleiter.llm;

import java.util.ArrayDeque;
import java.util.List;

/**
 * Kurzer Gesprächsverlauf eines Begleiters für freie Antworten. Nur im Speicher, nach Neustart leer; alte
 * Nachrichten fallen heraus, damit der Kontext klein bleibt (Groq Free-Tier: 8000 Tokens pro Minute).
 */
public final class ChatHistory {
    public static final int MAX_MESSAGES = 6;
    static final int MAX_CHARS = 240;

    private final ArrayDeque<LlmRequest.Message> messages = new ArrayDeque<>();

    public synchronized void add(String playerText, String reply) {
        messages.addLast(LlmRequest.Message.user(shorten(playerText)));
        messages.addLast(LlmRequest.Message.assistant(shorten(reply)));
        while (messages.size() > MAX_MESSAGES) messages.removeFirst();
    }

    public synchronized List<LlmRequest.Message> messages() { return List.copyOf(messages); }

    public synchronized void clear() { messages.clear(); }

    private static String shorten(String text) {
        String flat = text == null ? "" : text.replaceAll("\\s+", " ").strip();
        return flat.length() <= MAX_CHARS ? flat : flat.substring(0, MAX_CHARS) + "…";
    }
}
