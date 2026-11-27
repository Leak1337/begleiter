package dev.begleiter;

import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.minecraft.world.level.gamerules.GameRules;

/**
 * Nur für den GameTest-Server: hält die Uhr der Oberwelt am Vormittag an. Ohne das läuft der Tag-Nacht-Zyklus während
 * eines vollen Laufs weiter, nach gut zehn Minuten wird es Nacht, und spät einsortierte Tests ohne eigene Umgebung sehen
 * plötzlich Nachtlager statt ihres eigentlichen Bedürfnisses. Nachttests setzen ihre Zeit über die Umgebung
 * {@code begleiter-gametest:night}, die beim Abbau die vorige Uhrzeit wiederherstellt. Selbstspiel-Läufe bleiben unberührt.
 */
public class GameTestDaylight implements ModInitializer {
    static final long MORNING = 1000;

    @Override
    public void onInitialize() {
        // Loom setzt die Eigenschaft ohne Wert, Boolean.getBoolean wäre also immer false
        if (System.getProperty("fabric-api.gametest") == null) return;
        ServerLifecycleEvents.SERVER_STARTED.register(server -> {
            var level = server.overworld();
            level.getGameRules().set(GameRules.ADVANCE_TIME, false, server);
            level.clockManager().setTotalTicks(level.dimensionType().defaultClock().orElseThrow(), MORNING);
        });
    }
}
