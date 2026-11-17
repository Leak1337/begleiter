package dev.begleiter.llm;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.HttpTimeoutException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executor;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicInteger;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import dev.begleiter.llm.LlmResult.Status;
import dev.begleiter.llm.LlmResult.ToolCall;

/**
 * Client für OpenAI-kompatible Chat-Completions ({@code POST <baseUrl>/chat/completions}): Groq, aber auch Ollama oder
 * LM Studio über eine lokale baseUrl. Jede Anfrage läuft durch das {@link LlmBudget}.
 */
public final class OpenAiCompatibleClient implements LlmClient {
    /** Netzwerkschicht, in Tests ersetzbar. */
    public interface Transport {
        CompletableFuture<Reply> post(URI uri, Map<String, String> headers, String body, Duration timeout);
    }

    /** @param retryAfter Wert des Retry-After-Headers oder null */
    public record Reply(int status, String body, String retryAfter) {}

    private static final int MAX_ERROR_CHARS = 200;

    private final LlmConfig config;
    private final LlmBudget budget;
    private final Transport transport;

    public OpenAiCompatibleClient(LlmConfig config, LlmBudget budget, Transport transport) {
        this.config = config;
        this.budget = budget;
        this.transport = transport;
    }

    public OpenAiCompatibleClient(LlmConfig config, LlmBudget budget) {
        this(config, budget, HttpTransport.INSTANCE);
    }

    @Override
    public CompletableFuture<LlmResult> chat(LlmRequest request) {
        if (!config.active()) {
            return CompletableFuture.completedFuture(LlmResult.failure(Status.DISABLED,
                    config.enabled() ? "no API key" : "LLM disabled", 0));
        }
        LlmBudget.Verdict verdict = budget.tryAcquire(request.owner());
        if (verdict != LlmBudget.Verdict.OK) {
            return CompletableFuture.completedFuture(LlmResult.failure(Status.BUDGET, verdict.name(), 0));
        }
        long start = System.nanoTime();
        Duration timeout = Duration.ofMillis(config.timeoutMs());
        CompletableFuture<Reply> sent;
        try {
            sent = transport.post(URI.create(config.baseUrl() + "/chat/completions"),
                    Map.of("Authorization", "Bearer " + config.apiKey(), "Content-Type", "application/json"),
                    body(request), timeout);
        } catch (RuntimeException ex) {
            sent = CompletableFuture.failedFuture(ex);
        }
        // Sicherheitsnetz, falls der Transport sein eigenes Timeout nicht einhält.
        return sent.orTimeout(config.timeoutMs() + 2000L, TimeUnit.MILLISECONDS).handle((reply, error) -> {
            try {
                long latency = (System.nanoTime() - start) / 1_000_000L;
                return error != null ? failure(error, latency) : interpret(reply, latency);
            } catch (RuntimeException ex) {
                return LlmResult.failure(Status.BAD_RESPONSE, ex.getClass().getSimpleName(), 0);
            } finally {
                budget.release(request.owner());
            }
        });
    }

    static String body(LlmRequest request) {
        JsonObject o = new JsonObject();
        o.addProperty("model", request.model());
        JsonArray messages = new JsonArray();
        for (LlmRequest.Message m : request.messages()) {
            JsonObject msg = new JsonObject();
            msg.addProperty("role", m.role());
            msg.addProperty("content", m.content());
            messages.add(msg);
        }
        o.add("messages", messages);
        o.addProperty("max_completion_tokens", request.maxTokens());
        o.addProperty("temperature", request.temperature());
        if (request.tools() != null && !request.tools().isEmpty()) {
            o.add("tools", request.tools());
            if (request.toolChoice() != null) o.addProperty("tool_choice", request.toolChoice());
        }
        if (request.reasoningEffort() != null) o.addProperty("reasoning_effort", request.reasoningEffort());
        return o.toString();
    }

    private LlmResult interpret(Reply reply, long latency) {
        int code = reply.status();
        if (code == 429) {
            budget.rateLimited(retryAfterMs(reply.retryAfter()));
            return LlmResult.failure(Status.RATE_LIMITED, "HTTP 429: " + errorMessage(reply.body()), latency);
        }
        if (code < 200 || code >= 300) {
            return LlmResult.failure(Status.HTTP_ERROR, "HTTP " + code + ": " + errorMessage(reply.body()), latency);
        }
        JsonObject root;
        try {
            JsonElement parsed = JsonParser.parseString(reply.body() == null ? "" : reply.body());
            if (!parsed.isJsonObject()) return LlmResult.failure(Status.BAD_RESPONSE, "not a JSON object", latency);
            root = parsed.getAsJsonObject();
        } catch (RuntimeException ex) {
            return LlmResult.failure(Status.BAD_RESPONSE, "malformed JSON", latency);
        }
        JsonObject message = firstMessage(root);
        if (message == null) return LlmResult.failure(Status.BAD_RESPONSE, "no choices", latency);
        String content = string(message.get("content"));
        List<ToolCall> calls = toolCalls(message.get("tool_calls"));
        if ((content == null || content.isBlank()) && calls.isEmpty()) {
            return LlmResult.failure(Status.BAD_RESPONSE, "empty reply", latency);
        }
        budget.succeeded();
        JsonObject usage = root.get("usage") instanceof JsonObject u ? u : new JsonObject();
        return LlmResult.ok(content, calls, latency, count(usage.get("prompt_tokens")), count(usage.get("completion_tokens")));
    }

    private LlmResult failure(Throwable error, long latency) {
        Throwable cause = error;
        while ((cause instanceof CompletionException || cause instanceof ExecutionException) && cause.getCause() != null) {
            cause = cause.getCause();
        }
        if (cause instanceof HttpTimeoutException || cause instanceof TimeoutException) {
            return LlmResult.failure(Status.TIMEOUT, "no reply after " + config.timeoutMs() + " ms", latency);
        }
        String message = cause.getMessage() == null ? "" : ": " + truncate(config.redact(cause.getMessage()));
        return LlmResult.failure(Status.NETWORK, cause.getClass().getSimpleName() + message, latency);
    }

    private static JsonObject firstMessage(JsonObject root) {
        if (!(root.get("choices") instanceof JsonArray choices) || choices.isEmpty()) return null;
        if (!(choices.get(0) instanceof JsonObject choice)) return null;
        return choice.get("message") instanceof JsonObject message ? message : null;
    }

    private static List<ToolCall> toolCalls(JsonElement element) {
        List<ToolCall> calls = new ArrayList<>();
        if (!(element instanceof JsonArray array)) return calls;
        for (JsonElement e : array) {
            if (!(e instanceof JsonObject call) || !(call.get("function") instanceof JsonObject function)) continue;
            String name = string(function.get("name"));
            if (name == null || name.isBlank()) continue;
            JsonElement args = function.get("arguments");
            String arguments = args == null || args.isJsonNull() ? "{}" : args.isJsonPrimitive() ? args.getAsString() : args.toString();
            calls.add(new ToolCall(string(call.get("id")), name, arguments));
        }
        return calls;
    }

    /** Fehlermeldung des Servers ({"error":{"message":...}}) gekürzt und ohne Key. */
    private String errorMessage(String body) {
        if (body == null || body.isBlank()) return "(leer)";
        String text = body;
        try {
            if (JsonParser.parseString(body) instanceof JsonObject o && o.get("error") instanceof JsonObject err
                    && string(err.get("message")) != null) {
                text = string(err.get("message"));
            }
        } catch (RuntimeException ignored) {
            // kein JSON: Rohtext gekürzt verwenden
        }
        return truncate(config.redact(text));
    }

    static long retryAfterMs(String header) {
        if (header == null) return 0;
        try {
            double seconds = Double.parseDouble(header.strip());
            return Double.isFinite(seconds) && seconds > 0 ? (long) Math.ceil(seconds * 1000) : 0;
        } catch (NumberFormatException ex) {
            return 0; // HTTP-Datum: exponentielle Pause genügt
        }
    }

    private static String truncate(String text) {
        String flat = text.replaceAll("\\s+", " ").strip();
        return flat.length() <= MAX_ERROR_CHARS ? flat : flat.substring(0, MAX_ERROR_CHARS) + "…";
    }

    private static String string(JsonElement e) {
        return e != null && e.isJsonPrimitive() && e.getAsJsonPrimitive().isString() ? e.getAsString() : null;
    }

    private static int count(JsonElement e) {
        return e != null && e.isJsonPrimitive() && e.getAsJsonPrimitive().isNumber() ? Math.max(0, e.getAsInt()) : 0;
    }

    /** Java-HttpClient auf Daemon-Threads, damit offene Anfragen das Beenden des Spiels nie aufhalten. */
    static final class HttpTransport implements Transport {
        static final HttpTransport INSTANCE = new HttpTransport();

        private final HttpClient http;

        private HttpTransport() {
            AtomicInteger n = new AtomicInteger();
            Executor executor = Executors.newCachedThreadPool(r -> {
                Thread t = new Thread(r, "begleiter-llm-" + n.incrementAndGet());
                t.setDaemon(true);
                return t;
            });
            http = HttpClient.newBuilder().executor(executor).connectTimeout(Duration.ofSeconds(5))
                    .followRedirects(HttpClient.Redirect.NEVER).build();
        }

        @Override
        public CompletableFuture<Reply> post(URI uri, Map<String, String> headers, String body, Duration timeout) {
            HttpRequest.Builder builder = HttpRequest.newBuilder(uri).timeout(timeout)
                    .POST(HttpRequest.BodyPublishers.ofString(body));
            headers.forEach(builder::header);
            return http.sendAsync(builder.build(), HttpResponse.BodyHandlers.ofString())
                    .thenApply(r -> new Reply(r.statusCode(), r.body(), r.headers().firstValue("retry-after").orElse(null)));
        }
    }
}
