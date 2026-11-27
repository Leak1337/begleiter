package dev.begleiter.config;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonParseException;
import com.mojang.logging.LogUtils;

import net.fabricmc.loader.api.FabricLoader;

/**
 * Serverseitige Einstellungen in config/begleiter.json. Werte werden beim Laden auf sichere Grenzen gebracht;
 * eine kaputte Datei ersetzt nie die laufende gültige Konfiguration. Neu laden nur über den berechtigten Befehl.
 */
public final class BegleiterConfig {
    /** Muss man den Begleiter im Chat ansprechen? AUTO: nur im integrierten Einzelspieler allein ohne Ansprache. */
    public enum Address { ALWAYS, AUTO, NEVER }
    /** Wie viel er von sich aus erzählt (wird von der Gesprächigkeit ausgewertet). */
    public enum Talk { QUIET, NORMAL, CHATTY }

    public static final String FILE_NAME = "begleiter.json";
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();

    public Address address = Address.ALWAYS;
    public String prefix = "@";
    public Talk talk = Talk.NORMAL;
    public boolean xray = true;
    public int xrayRadius = 32;
    public boolean diamondBoost = true;
    public double navBudgetMillis = 6.0;
    public int gatherRadius = 24;
    public boolean realFurnaces = false;
    /** Für die Mehrspieler-Grundlagen vorgesehen; derzeit noch nicht durchgesetzt. */
    public int maxCompanionsPerPlayer = 3;
    /** Für die Mehrspieler-Grundlagen vorgesehen; derzeit noch nicht durchgesetzt. */
    public boolean offlineWork = false;

    private static volatile BegleiterConfig current = new BegleiterConfig();

    public static BegleiterConfig get() { return current; }

    /** Für Tests: Konfiguration direkt setzen (wird geprüft wie beim Laden). */
    public static void set(BegleiterConfig config) {
        config.sanitize();
        current = config;
    }

    public long navBudgetNanos() { return Math.round(navBudgetMillis * 1_000_000); }

    /** Grenzen und Ersatzwerte: Unsinniges wird korrigiert statt abgelehnt. */
    void sanitize() {
        if (address == null) address = Address.ALWAYS;
        if (talk == null) talk = Talk.NORMAL;
        if (prefix == null || prefix.isBlank() || prefix.length() > 4 || prefix.chars().anyMatch(Character::isLetterOrDigit))
            prefix = "@";
        xrayRadius = Math.clamp(xrayRadius, 8, 64);
        if (Double.isNaN(navBudgetMillis)) navBudgetMillis = 6.0;
        navBudgetMillis = Math.clamp(navBudgetMillis, 1.0, 20.0);
        gatherRadius = Math.clamp(gatherRadius, 8, 48);
        maxCompanionsPerPlayer = Math.clamp(maxCompanionsPerPlayer, 1, 16);
    }

    public static Path file() {
        return FabricLoader.getInstance().getConfigDir().resolve(FILE_NAME);
    }

    /** Beim Start: vorhandene Datei laden oder Vorgaben schreiben. Fehler lassen die Vorgaben stehen. */
    public static void loadOnStartup() {
        Path file = file();
        if (!Files.exists(file)) {
            write(file, current);
            return;
        }
        String error = reload(file);
        if (error != null) LogUtils.getLogger().error("[Begleiter] {} unreadable, using defaults: {}", FILE_NAME, error);
    }

    /** Neu laden; null bei Erfolg, sonst die Fehlermeldung (die laufende Konfiguration bleibt dann unverändert). */
    public static String reload(Path file) {
        try {
            String text = Files.readString(file);
            BegleiterConfig loaded = GSON.fromJson(text, BegleiterConfig.class);
            if (loaded == null) return "empty file";
            loaded.sanitize();
            current = loaded;
            return null;
        } catch (IOException | JsonParseException | IllegalStateException ex) {
            return ex.getClass().getSimpleName() + ": " + ex.getMessage();
        }
    }

    static void write(Path file, BegleiterConfig config) {
        try {
            Files.createDirectories(file.getParent());
            Files.writeString(file, GSON.toJson(config) + "\n");
        } catch (IOException ex) {
            LogUtils.getLogger().warn("[Begleiter] Could not write {}: {}", FILE_NAME, ex.toString());
        }
    }

    /** Für Tests und den Befehl: Konfiguration als JSON. */
    public String toJson() { return GSON.toJson(this); }
}
