package dev.begleiter.llm;

import java.util.concurrent.CompletableFuture;

/**
 * Asynchroner Zugang zu einem Sprachmodell. Das Future wird auf einem Hintergrund-Thread erfüllt und endet nie
 * mit einer Exception; Ergebnisse vor Weltzugriffen per {@code server.execute(...)} auf den Server-Thread holen.
 */
public interface LlmClient {
    CompletableFuture<LlmResult> chat(LlmRequest request);
}
