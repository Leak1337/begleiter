package dev.begleiter.llm;

import java.util.List;

/**
 * Ergebnis einer Anfrage. Fehler sind normale Ergebnisse, keine Exceptions: Aufrufer fallen dann auf das Verhalten
 * ohne LLM zurück.
 */
public record LlmResult(Status status, String content, List<ToolCall> toolCalls, String error, long latencyMs,
        int promptTokens, int completionTokens) {

    public enum Status {
        OK,
        /** Aus oder kein Key. */
        DISABLED,
        /** Eigenes Budget (Minute/Tag/offene Anfrage/Pause) hat die Anfrage gar nicht erst gesendet. */
        BUDGET,
        /** Server meldet 429. */
        RATE_LIMITED,
        TIMEOUT,
        HTTP_ERROR,
        BAD_RESPONSE,
        NETWORK
    }

    /** @param arguments JSON-Text, wie vom Modell geliefert; vor Gebrauch validieren */
    public record ToolCall(String id, String name, String arguments) {}

    public LlmResult {
        toolCalls = toolCalls == null ? List.of() : List.copyOf(toolCalls);
    }

    public boolean ok() { return status == Status.OK; }

    public static LlmResult ok(String content, List<ToolCall> toolCalls, long latencyMs, int promptTokens, int completionTokens) {
        return new LlmResult(Status.OK, content, toolCalls, null, latencyMs, promptTokens, completionTokens);
    }

    public static LlmResult failure(Status status, String error, long latencyMs) {
        if (status == Status.OK) throw new IllegalArgumentException("OK is not a failure");
        return new LlmResult(status, null, List.of(), error, latencyMs, 0, 0);
    }
}
