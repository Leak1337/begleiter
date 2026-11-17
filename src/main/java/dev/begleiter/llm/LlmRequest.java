package dev.begleiter.llm;

import java.util.List;

import com.google.gson.JsonArray;

/**
 * Eine Chat-Anfrage im OpenAI-Format.
 *
 * @param owner           Auftraggeber fürs Budget (meist der Begleiter); höchstens eine offene Anfrage je owner
 * @param tools           Tool-Definitionen im OpenAI-Format oder null
 * @param toolChoice      "auto", "required", "none" oder null (Serverstandard)
 * @param reasoningEffort "low"/"medium"/"high" für Reasoning-Modelle oder null
 */
public record LlmRequest(Object owner, String model, List<Message> messages, JsonArray tools, String toolChoice,
        int maxTokens, double temperature, String reasoningEffort) {

    public record Message(String role, String content) {
        public static Message system(String content) { return new Message("system", content); }
        public static Message user(String content) { return new Message("user", content); }
        public static Message assistant(String content) { return new Message("assistant", content); }
    }

    public LlmRequest {
        if (owner == null) throw new IllegalArgumentException("owner fehlt");
        if (model == null || model.isBlank()) throw new IllegalArgumentException("model fehlt");
        if (messages == null || messages.isEmpty()) throw new IllegalArgumentException("messages fehlen");
        messages = List.copyOf(messages);
        tools = tools == null ? null : tools.deepCopy();
        maxTokens = Math.max(1, Math.min(8192, maxTokens));
        temperature = Double.isFinite(temperature) ? Math.max(0, Math.min(2, temperature)) : 0;
    }

    public static LlmRequest of(Object owner, String model, List<Message> messages) {
        return new LlmRequest(owner, model, messages, null, null, 512, 0.2, null);
    }

    public LlmRequest withTools(JsonArray tools, String toolChoice) {
        return new LlmRequest(owner, model, messages, tools, toolChoice, maxTokens, temperature, reasoningEffort);
    }

    public LlmRequest withMaxTokens(int maxTokens) {
        return new LlmRequest(owner, model, messages, tools, toolChoice, maxTokens, temperature, reasoningEffort);
    }

    public LlmRequest withReasoningEffort(String effort) {
        return new LlmRequest(owner, model, messages, tools, toolChoice, maxTokens, temperature, effort);
    }
}
