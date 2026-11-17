package dev.begleiter;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;

/**
 * Nur für Tests: schreibt jede Sekunde pro Bot eine Zeile in bot-trace.log (Laufverzeichnis), aber nur wenn sich Modus,
 * Aufgabe oder Block-Position geändert haben. Schlägt ein Test fehl, zeigt {@code python tools/testlog.py trace}, was die
 * Bots davor getan haben. Die Mod selbst enthält davon nichts.
 */
public class BotTrace implements ModInitializer {
    private static final int MAX_LINES = 20000;

    private final Map<UUID, String> last = new HashMap<>();
    private Path file;
    private int lines;

    @Override
    public void onInitialize() {
        file = Path.of("bot-trace.log");
        try {
            Files.writeString(file, "", StandardCharsets.UTF_8);
        } catch (IOException e) {
            return;
        }
        ServerTickEvents.END_SERVER_TICK.register(server -> {
            if (server.getTickCount() % 20 != 0 || lines >= MAX_LINES) return;
            StringBuilder out = new StringBuilder();
            for (var level : server.getAllLevels())
                for (var e : level.getAllEntities()) {
                    if (!(e instanceof CompanionEntity c)) continue;
                    String state = c.blockPosition().toShortString() + " | " + c.modeText() + (c.isPaused() ? " (pausiert)" : "")
                            + " | " + c.currentTaskText() + " | Leben " + (int) c.getHealth();
                    if (state.equals(last.put(c.getUUID(), state))) continue;
                    out.append(server.getTickCount()).append(' ').append(c.getUUID().toString(), 0, 8).append(' ')
                            .append(state).append('\n');
                    lines++;
                }
            if (out.length() == 0) return;
            try {
                Files.writeString(file, out, StandardCharsets.UTF_8, StandardOpenOption.APPEND);
            } catch (IOException ignored) {
            }
        });
    }
}
