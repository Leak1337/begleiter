package dev.begleiter.debug;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.mojang.logging.LogUtils;

import net.fabricmc.loader.api.FabricLoader;

/** Clientseitige Einstellungen des Bot-Debugs, gespeichert in config/begleiter-debug.json. */
public final class BotDebugConfig {
    public enum Esp { OFF, SELECTED, ALL }
    public enum LineOrigin { CENTER, BOTTOM }

    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    private static final Path FILE = FabricLoader.getInstance().getConfigDir().resolve("begleiter-debug.json");
    public static final int[] RANGES = {32, 64, 128, 256, 512};

    public boolean hud = true;
    public int hudX = 4, hudY = 4;
    public float hudScale = 1f;
    public Esp esp = Esp.ALL;
    public boolean lines = true;
    public LineOrigin lineOrigin = LineOrigin.CENTER;
    public boolean nameTags = true;
    public boolean tagDistance = true;
    public boolean tagStatus = true;
    public boolean path = false;
    public int range = 128;

    private static BotDebugConfig instance;

    public static BotDebugConfig get() {
        if (instance == null) instance = load();
        return instance;
    }

    private static BotDebugConfig load() {
        try {
            if (Files.exists(FILE)) {
                BotDebugConfig c = GSON.fromJson(Files.readString(FILE), BotDebugConfig.class);
                if (c != null) { c.sanitize(); return c; }
            }
        } catch (IOException | RuntimeException ex) {
            LogUtils.getLogger().warn("[Begleiter-Debug] Config unreadable, using defaults: {}", ex.toString());
        }
        return new BotDebugConfig();
    }

    private void sanitize() {
        hudScale = Math.clamp(hudScale, 0.5f, 3f);
        hudX = Math.clamp(hudX, 0, 4000);
        hudY = Math.clamp(hudY, 0, 4000);
        range = Math.clamp(range, 8, 1024);
        if (esp == null) esp = Esp.ALL;
        if (lineOrigin == null) lineOrigin = LineOrigin.CENTER;
    }

    public void save() {
        sanitize();
        try {
            Files.createDirectories(FILE.getParent());
            Files.writeString(FILE, GSON.toJson(this));
        } catch (IOException ex) {
            LogUtils.getLogger().warn("[Begleiter-Debug] Config not saved: {}", ex.toString());
        }
    }

    public int nextRange() {
        for (int r : RANGES) if (r > range) return r;
        return RANGES[0];
    }
}
