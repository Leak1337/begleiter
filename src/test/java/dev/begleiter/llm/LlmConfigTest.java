package dev.begleiter.llm;

import java.nio.file.Files;
import java.nio.file.Path;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import static org.junit.jupiter.api.Assertions.*;

class LlmConfigTest {
    @Test void missingFileWritesDisabledTemplate(@TempDir Path dir) throws Exception {
        Path file = dir.resolve("config").resolve(LlmConfig.FILE_NAME);
        LlmConfig config = LlmConfig.load(file);
        assertFalse(config.active());
        assertTrue(Files.isRegularFile(file));
        assertEquals(LlmConfig.DEFAULTS, LlmConfig.parse(Files.readString(file)));
    }

    @Test void enabledWithKeyIsActiveAndKeepsValues() {
        LlmConfig config = LlmConfig.parse("""
                {"enabled": true, "apiKey": " gsk_test123 ", "intentModel": "qwen/qwen3.8-27b",
                 "timeoutMs": 5000, "maxRequestsPerMinute": 10, "planner": "shadow"}""");
        assertTrue(config.active());
        assertEquals("gsk_test123", config.apiKey());
        assertEquals("qwen/qwen3.8-27b", config.intentModel());
        assertEquals("qwen/qwen3.8-27b", config.chatModel(), "chatModel folgt intentModel, wenn nicht gesetzt");
        assertEquals(5000, config.timeoutMs());
        assertEquals(10, config.maxRequestsPerMinute());
        assertEquals(LlmConfig.Planner.SHADOW, config.planner());
    }

    @Test void enabledWithoutKeyStaysInactive() {
        assertFalse(LlmConfig.parse("{\"enabled\": true, \"apiKey\": \"  \"}").active());
    }

    @Test void brokenOrWrongJsonFallsBackToDisabledDefaults() {
        assertEquals(LlmConfig.DEFAULTS, LlmConfig.parse("{kaputt"));
        assertEquals(LlmConfig.DEFAULTS, LlmConfig.parse("[1,2]"));
        LlmConfig typed = LlmConfig.parse("{\"enabled\": \"ja\", \"timeoutMs\": \"viel\", \"planner\": \"egal\"}");
        assertFalse(typed.enabled());
        assertEquals(LlmConfig.DEFAULTS.timeoutMs(), typed.timeoutMs());
        assertEquals(LlmConfig.Planner.OFF, typed.planner());
    }

    @Test void limitsAreClamped() {
        LlmConfig config = LlmConfig.parse("{\"timeoutMs\": 1, \"maxRequestsPerMinute\": -5, \"maxRequestsPerDay\": 1e12}");
        assertEquals(1000, config.timeoutMs());
        assertEquals(1, config.maxRequestsPerMinute());
        assertEquals(100_000, config.maxRequestsPerDay());
    }

    @Test void plainHttpOnlyForLocalServers() {
        assertEquals("http://localhost:11434/v1", LlmConfig.parse("{\"baseUrl\": \"http://localhost:11434/v1/\"}").baseUrl());
        assertEquals("http://127.0.0.1:1234/v1", LlmConfig.parse("{\"baseUrl\": \"http://127.0.0.1:1234/v1\"}").baseUrl());
        assertEquals(LlmConfig.DEFAULT_BASE_URL, LlmConfig.parse("{\"baseUrl\": \"http://example.com/v1\"}").baseUrl());
        assertEquals(LlmConfig.DEFAULT_BASE_URL, LlmConfig.parse("{\"baseUrl\": \"https://user:pw@example.com/v1\"}").baseUrl());
        assertEquals(LlmConfig.DEFAULT_BASE_URL, LlmConfig.parse("{\"baseUrl\": \"ftp://example.com\"}").baseUrl());
        assertEquals("https://example.com/v1", LlmConfig.parse("{\"baseUrl\": \"https://example.com/v1\"}").baseUrl());
    }

    @Test void invalidModelNamesFallBack() {
        LlmConfig config = LlmConfig.parse("{\"intentModel\": \"evil model; rm\", \"plannerModel\": \"\"}");
        assertEquals(LlmConfig.DEFAULTS.intentModel(), config.intentModel());
        assertEquals(LlmConfig.DEFAULTS.plannerModel(), config.plannerModel());
    }

    @Test void keyNeverAppearsInToStringAndIsRedacted() {
        LlmConfig config = LlmConfig.parse("{\"enabled\": true, \"apiKey\": \"gsk_geheim\"}");
        assertFalse(config.toString().contains("gsk_geheim"));
        assertEquals("Bearer *** ungültig", config.redact("Bearer gsk_geheim ungültig"));
    }

    @Test void writeEnabledKeepsKeyAndOtherFields(@TempDir Path dir) throws Exception {
        Path file = dir.resolve(LlmConfig.FILE_NAME);
        Files.writeString(file, "{\"enabled\": false, \"apiKey\": \"gsk_abc\", \"intentModel\": \"qwen/qwen3.8-27b\", \"eigenes\": 1}");
        LlmConfig.writeEnabled(file, true);
        LlmConfig config = LlmConfig.load(file);
        assertTrue(config.active());
        assertEquals("gsk_abc", config.apiKey());
        assertEquals("qwen/qwen3.8-27b", config.intentModel());
        assertTrue(Files.readString(file).contains("eigenes"));
        LlmConfig.writeEnabled(file, false);
        assertFalse(LlmConfig.load(file).enabled());
    }

    @Test void writeEnabledReplacesBrokenOrMissingFile(@TempDir Path dir) throws Exception {
        Path broken = dir.resolve("a").resolve(LlmConfig.FILE_NAME);
        LlmConfig.writeEnabled(broken, true);
        assertTrue(LlmConfig.load(broken).enabled());
        Files.writeString(broken, "{kaputt");
        LlmConfig.writeEnabled(broken, true);
        LlmConfig config = LlmConfig.load(broken);
        assertTrue(config.enabled());
        assertFalse(config.active(), "Vorlage hat keinen Key");
    }

    @Test void oversizedFileIsIgnored(@TempDir Path dir) throws Exception {
        Path file = dir.resolve(LlmConfig.FILE_NAME);
        Files.writeString(file, "{\"enabled\": true, \"apiKey\": \"k\", \"x\": \"" + "a".repeat(20_000) + "\"}");
        assertFalse(LlmConfig.load(file).active());
    }
}
