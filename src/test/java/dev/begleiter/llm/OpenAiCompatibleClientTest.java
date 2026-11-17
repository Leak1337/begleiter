package dev.begleiter.llm;

import java.net.URI;
import java.net.http.HttpTimeoutException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicLong;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

import dev.begleiter.llm.LlmResult.Status;

class OpenAiCompatibleClientTest {
    private static final LlmConfig ACTIVE = LlmConfig.parse("{\"enabled\": true, \"apiKey\": \"gsk_geheim\", \"timeoutMs\": 2000}");

    private final AtomicLong clock = new AtomicLong(1_000_000L);
    private final LlmBudget budget = new LlmBudget(10, 100, clock::get);
    private final List<String> sentBodies = new ArrayList<>();
    private final List<Map<String, String>> sentHeaders = new ArrayList<>();
    private final List<URI> sentUris = new ArrayList<>();

    private OpenAiCompatibleClient client(LlmConfig config, CompletableFuture<OpenAiCompatibleClient.Reply> reply) {
        return new OpenAiCompatibleClient(config, budget, (uri, headers, body, timeout) -> {
            sentUris.add(uri);
            sentHeaders.add(headers);
            sentBodies.add(body);
            return reply;
        });
    }

    private OpenAiCompatibleClient replying(int status, String body) {
        return client(ACTIVE, CompletableFuture.completedFuture(new OpenAiCompatibleClient.Reply(status, body, null)));
    }

    private static LlmRequest request(Object owner) {
        return LlmRequest.of(owner, "openai/gpt-oss-20b",
                List.of(LlmRequest.Message.system("Du bist ein Begleiter."), LlmRequest.Message.user("hol holz")));
    }

    @Test void parsesContentToolCallsAndUsage() {
        var result = replying(200, """
                {"choices":[{"message":{"role":"assistant","content":null,"tool_calls":[
                  {"id":"call_1","type":"function","function":{"name":"befehl","arguments":"{\\"intent\\":\\"GATHER\\"}"}}]}}],
                 "usage":{"prompt_tokens":120,"completion_tokens":15}}""").chat(request(new Object())).join();
        assertEquals(Status.OK, result.status());
        assertEquals(1, result.toolCalls().size());
        assertEquals("befehl", result.toolCalls().getFirst().name());
        assertEquals("GATHER", JsonParser.parseString(result.toolCalls().getFirst().arguments()).getAsJsonObject().get("intent").getAsString());
        assertEquals(120, result.promptTokens());
        assertEquals(15, result.completionTokens());
    }

    @Test void sendsOpenAiRequestWithBearerKey() {
        JsonArray tools = JsonParser.parseString("[{\"type\":\"function\",\"function\":{\"name\":\"befehl\"}}]").getAsJsonArray();
        replying(200, "{\"choices\":[{\"message\":{\"content\":\"ok\"}}]}")
                .chat(request(new Object()).withTools(tools, "required").withReasoningEffort("low")).join();
        assertEquals(URI.create("https://api.groq.com/openai/v1/chat/completions"), sentUris.getFirst());
        assertEquals("Bearer gsk_geheim", sentHeaders.getFirst().get("Authorization"));
        JsonObject body = JsonParser.parseString(sentBodies.getFirst()).getAsJsonObject();
        assertEquals("openai/gpt-oss-20b", body.get("model").getAsString());
        assertEquals(2, body.getAsJsonArray("messages").size());
        assertEquals("system", body.getAsJsonArray("messages").get(0).getAsJsonObject().get("role").getAsString());
        assertEquals("required", body.get("tool_choice").getAsString());
        assertEquals("low", body.get("reasoning_effort").getAsString());
        assertEquals(1, body.getAsJsonArray("tools").size());
    }

    @Test void disabledConfigSendsNothing() {
        var result = client(LlmConfig.DEFAULTS, new CompletableFuture<>()).chat(request(new Object())).join();
        assertEquals(Status.DISABLED, result.status());
        assertTrue(sentBodies.isEmpty());
    }

    @Test void rateLimitStartsBackoffAndStopsFurtherRequests() {
        var reply = new OpenAiCompatibleClient.Reply(429, "{\"error\":{\"message\":\"Rate limit reached\"}}", "7");
        var c = client(ACTIVE, CompletableFuture.completedFuture(reply));
        var first = c.chat(request(new Object())).join();
        assertEquals(Status.RATE_LIMITED, first.status());
        assertTrue(first.error().contains("Rate limit reached"));
        assertEquals(7_000, budget.snapshot().backoffRemainingMs());
        var second = c.chat(request(new Object())).join();
        assertEquals(Status.BUDGET, second.status());
        assertEquals("BACKOFF", second.error());
        assertEquals(1, sentBodies.size());
    }

    @Test void httpErrorsAreRedactedAndTruncated() {
        var result = replying(401, "{\"error\":{\"message\":\"Invalid API Key gsk_geheim " + "x".repeat(500) + "\"}}")
                .chat(request(new Object())).join();
        assertEquals(Status.HTTP_ERROR, result.status());
        assertFalse(result.error().contains("gsk_geheim"));
        assertTrue(result.error().startsWith("HTTP 401: Invalid API Key ***"));
        assertTrue(result.error().length() < 230);
    }

    @Test void brokenAndEmptyResponsesAreBadResponses() {
        assertEquals(Status.BAD_RESPONSE, replying(200, "<html>").chat(request(new Object())).join().status());
        assertEquals(Status.BAD_RESPONSE, replying(200, "{\"choices\":[]}").chat(request(new Object())).join().status());
        assertEquals(Status.BAD_RESPONSE, replying(200, "{\"choices\":[{\"message\":{\"content\":\" \"}}]}")
                .chat(request(new Object())).join().status());
    }

    @Test void timeoutAndNetworkErrorsBecomeResultsAndReleaseOwner() {
        Object owner = new Object();
        var timeout = client(ACTIVE, CompletableFuture.failedFuture(new HttpTimeoutException("zu langsam")))
                .chat(request(owner)).join();
        assertEquals(Status.TIMEOUT, timeout.status());
        var network = client(ACTIVE, CompletableFuture.failedFuture(new java.net.ConnectException("kein Netz")))
                .chat(request(owner)).join();
        assertEquals(Status.NETWORK, network.status());
        assertTrue(network.error().startsWith("ConnectException"));
        assertEquals(0, budget.snapshot().inFlight());
    }

    @Test void throwingTransportDoesNotLeakOwner() {
        Object owner = new Object();
        var c = new OpenAiCompatibleClient(ACTIVE, budget, (uri, headers, body, timeout) -> { throw new IllegalStateException("kaputt"); });
        assertEquals(Status.NETWORK, c.chat(request(owner)).join().status());
        assertEquals(LlmBudget.Verdict.OK, budget.tryAcquire(owner));
    }

    @Test void secondRequestOfSameOwnerWaitsForFirst() {
        Object owner = new Object();
        var pending = new CompletableFuture<OpenAiCompatibleClient.Reply>();
        var c = client(ACTIVE, pending);
        var first = c.chat(request(owner));
        var second = c.chat(request(owner)).join();
        assertEquals(Status.BUDGET, second.status());
        assertEquals("BUSY", second.error());
        pending.complete(new OpenAiCompatibleClient.Reply(200, "{\"choices\":[{\"message\":{\"content\":\"hallo\"}}]}", null));
        assertEquals("hallo", first.join().content());
        assertEquals(0, budget.snapshot().inFlight());
    }

    @Test void retryAfterParsing() {
        assertEquals(0, OpenAiCompatibleClient.retryAfterMs(null));
        assertEquals(2_500, OpenAiCompatibleClient.retryAfterMs("2.5"));
        assertEquals(0, OpenAiCompatibleClient.retryAfterMs("Wed, 21 Oct 2026 07:28:00 GMT"));
        assertEquals(0, OpenAiCompatibleClient.retryAfterMs("-3"));
    }

    @Test void requestValidation() {
        assertThrows(IllegalArgumentException.class, () -> LlmRequest.of(null, "m", List.of(LlmRequest.Message.user("x"))));
        assertThrows(IllegalArgumentException.class, () -> LlmRequest.of(new Object(), " ", List.of(LlmRequest.Message.user("x"))));
        assertThrows(IllegalArgumentException.class, () -> LlmRequest.of(new Object(), "m", List.of()));
        assertEquals(8192, request(new Object()).withMaxTokens(1_000_000).maxTokens());
    }
}
