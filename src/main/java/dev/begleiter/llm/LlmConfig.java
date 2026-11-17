package dev.begleiter.llm;

import java.io.IOException;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Locale;

import com.google.gson.GsonBuilder;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

/**
 * Inhalt von {@code config/begleiter-llm.json}. Ohne Key oder mit beschädigter Datei ist die LLM-Anbindung aus;
 * die Mod verhält sich dann wie ohne LLM. Der Key erscheint nie in {@link #toString()}.
 */
public record LlmConfig(boolean enabled, String apiKey, String baseUrl, String intentModel, String chatModel,
        String plannerModel, int timeoutMs, int maxRequestsPerMinute, int maxRequestsPerDay, Planner planner) {

    public enum Planner { OFF, SHADOW, ON }

    public static final String FILE_NAME = "begleiter-llm.json";
    public static final String DEFAULT_BASE_URL = "https://api.groq.com/openai/v1";
    /** Größere Dateien sind sicher kein handgeschriebener Config-Eintrag. */
    private static final long MAX_FILE_BYTES = 16 * 1024;

    /**
     * 6 pro Minute: Groqs Free-Tier erlaubt für gpt-oss-20b 8000 Tokens/Minute (gemessen 2026-11-03), eine
     * Intent-Anfrage braucht gut 1000. Ein 429 fängt das Budget trotzdem ab.
     */
    public static final LlmConfig DEFAULTS = new LlmConfig(false, "", DEFAULT_BASE_URL, "openai/gpt-oss-20b",
            "openai/gpt-oss-20b", "openai/gpt-oss-120b", 8000, 6, 500, Planner.OFF);

    public LlmConfig {
        apiKey = apiKey == null ? "" : apiKey.strip();
        baseUrl = validBaseUrl(baseUrl) ? stripSlash(baseUrl.strip()) : DEFAULT_BASE_URL;
        intentModel = model(intentModel, "openai/gpt-oss-20b");
        chatModel = model(chatModel, intentModel);
        plannerModel = model(plannerModel, "openai/gpt-oss-120b");
        timeoutMs = clamp(timeoutMs, 1000, 60_000);
        maxRequestsPerMinute = clamp(maxRequestsPerMinute, 1, 1000);
        maxRequestsPerDay = clamp(maxRequestsPerDay, 1, 100_000);
        if (planner == null) planner = Planner.OFF;
    }

    /** Anfragen werden nur mit eingeschaltetem Schalter und eingetragenem Key gestellt. */
    public boolean active() { return enabled && !apiKey.isEmpty(); }

    /** Entfernt den Key aus Texten, die geloggt oder im Chat gezeigt werden könnten. */
    public String redact(String text) {
        if (text == null || apiKey.length() < 4) return text;
        return text.replace(apiKey, "***");
    }

    @Override public String toString() {
        return "LlmConfig[enabled=" + enabled + ", key=" + (apiKey.isEmpty() ? "fehlt" : "gesetzt") + ", baseUrl=" + baseUrl
                + ", intentModel=" + intentModel + ", chatModel=" + chatModel + ", plannerModel=" + plannerModel
                + ", timeoutMs=" + timeoutMs + ", perMinute=" + maxRequestsPerMinute + ", perDay=" + maxRequestsPerDay
                + ", planner=" + planner + "]";
    }

    /** Fehlende Felder bekommen den Standardwert; kaputtes JSON ergibt {@link #DEFAULTS} (aus). */
    public static LlmConfig parse(String json) {
        JsonObject o;
        try {
            JsonElement root = JsonParser.parseString(json);
            if (!root.isJsonObject()) return DEFAULTS;
            o = root.getAsJsonObject();
        } catch (RuntimeException ex) {
            return DEFAULTS;
        }
        LlmConfig d = DEFAULTS;
        return new LlmConfig(bool(o, "enabled", d.enabled), str(o, "apiKey", d.apiKey), str(o, "baseUrl", d.baseUrl),
                str(o, "intentModel", d.intentModel), str(o, "chatModel", null), str(o, "plannerModel", d.plannerModel),
                integer(o, "timeoutMs", d.timeoutMs), integer(o, "maxRequestsPerMinute", d.maxRequestsPerMinute),
                integer(o, "maxRequestsPerDay", d.maxRequestsPerDay), planner(str(o, "planner", d.planner.name())));
    }

    /** Liest die Datei; fehlt sie, wird eine ausgeschaltete Vorlage zum Ausfüllen angelegt. */
    public static LlmConfig load(Path file) {
        try {
            if (!Files.isRegularFile(file)) {
                writeTemplate(file);
                return DEFAULTS;
            }
            if (Files.size(file) > MAX_FILE_BYTES) return DEFAULTS;
            return parse(Files.readString(file));
        } catch (IOException | RuntimeException ex) {
            return DEFAULTS;
        }
    }

    public static Path defaultFile() {
        return net.fabricmc.loader.api.FabricLoader.getInstance().getConfigDir().resolve(FILE_NAME);
    }

    static String template() {
        JsonObject o = new JsonObject();
        LlmConfig d = DEFAULTS;
        o.addProperty("enabled", d.enabled);
        o.addProperty("apiKey", "");
        o.addProperty("baseUrl", d.baseUrl);
        o.addProperty("intentModel", d.intentModel);
        o.addProperty("chatModel", d.chatModel);
        o.addProperty("plannerModel", d.plannerModel);
        o.addProperty("timeoutMs", d.timeoutMs);
        o.addProperty("maxRequestsPerMinute", d.maxRequestsPerMinute);
        o.addProperty("maxRequestsPerDay", d.maxRequestsPerDay);
        o.addProperty("planner", d.planner.name().toLowerCase(Locale.ROOT));
        return new GsonBuilder().setPrettyPrinting().create().toJson(o) + "\n";
    }

    /**
     * Setzt nur den Schalter {@code enabled} in der Datei; Key, Modelle und eigene Felder bleiben stehen.
     * Eine fehlende oder kaputte Datei wird durch die Vorlage mit dem gewünschten Schalter ersetzt.
     */
    public static void writeEnabled(Path file, boolean enabled) throws IOException {
        JsonObject o = null;
        if (Files.isRegularFile(file) && Files.size(file) <= MAX_FILE_BYTES) {
            try {
                if (JsonParser.parseString(Files.readString(file)) instanceof JsonObject existing) o = existing;
            } catch (RuntimeException ignored) {
                // kaputt: unten durch die Vorlage ersetzt
            }
        }
        if (o == null) o = JsonParser.parseString(template()).getAsJsonObject();
        o.addProperty("enabled", enabled);
        Files.createDirectories(file.getParent());
        Files.writeString(file, new GsonBuilder().setPrettyPrinting().create().toJson(o) + "\n");
    }

    private static void writeTemplate(Path file) {
        try {
            Files.createDirectories(file.getParent());
            Files.writeString(file, template());
        } catch (IOException | RuntimeException ex) {
            // ohne Schreibrechte bleibt die Anbindung einfach aus
        }
    }

    /** https überall; http nur lokal (Ollama, LM Studio), damit der Key nie unverschlüsselt das Gerät verlässt. */
    static boolean validBaseUrl(String url) {
        if (url == null || url.isBlank()) return false;
        try {
            URI uri = URI.create(url.strip());
            String scheme = uri.getScheme(), host = uri.getHost();
            if (host == null || uri.getUserInfo() != null || uri.getQuery() != null || uri.getFragment() != null) return false;
            if ("https".equalsIgnoreCase(scheme)) return true;
            return "http".equalsIgnoreCase(scheme)
                    && (host.equals("localhost") || host.equals("127.0.0.1") || host.equals("[::1]") || host.equals("::1"));
        } catch (IllegalArgumentException ex) {
            return false;
        }
    }

    private static String stripSlash(String url) {
        return url.endsWith("/") ? url.substring(0, url.length() - 1) : url;
    }

    private static String model(String value, String fallback) {
        if (value == null) return fallback;
        String v = value.strip();
        return v.isEmpty() || v.length() > 128 || !v.matches("[A-Za-z0-9._:/+-]+") ? fallback : v;
    }

    private static int clamp(int value, int min, int max) { return Math.max(min, Math.min(max, value)); }

    private static Planner planner(String value) {
        try {
            return Planner.valueOf(value.strip().toUpperCase(Locale.ROOT));
        } catch (RuntimeException ex) {
            return Planner.OFF;
        }
    }

    private static boolean bool(JsonObject o, String key, boolean fallback) {
        JsonElement e = o.get(key);
        return e != null && e.isJsonPrimitive() && e.getAsJsonPrimitive().isBoolean() ? e.getAsBoolean() : fallback;
    }

    private static String str(JsonObject o, String key, String fallback) {
        JsonElement e = o.get(key);
        return e != null && e.isJsonPrimitive() && e.getAsJsonPrimitive().isString() ? e.getAsString() : fallback;
    }

    private static int integer(JsonObject o, String key, int fallback) {
        JsonElement e = o.get(key);
        if (e == null || !e.isJsonPrimitive() || !e.getAsJsonPrimitive().isNumber()) return fallback;
        double v = e.getAsDouble();
        return Double.isFinite(v) ? (int) Math.max(Integer.MIN_VALUE, Math.min(Integer.MAX_VALUE, v)) : fallback;
    }
}
