package dev.begleiter.task;

import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;

import dev.begleiter.CompanionEntity;
import dev.begleiter.CompanionEntity.Task;
import dev.begleiter.ai.DecisionOutcome;
import dev.begleiter.nav.Goal;

/**
 * Was ein Handler vom Aufgabenziel bekommt: Begleiter, Welt und die gemeinsame Navigation samt Feststeck-Erkennung.
 * Hinlaufen über diese Methoden zählt in die gemeinsame Stuck-Behandlung (springen, graben, überspringen).
 */
public interface TaskContext {
    CompanionEntity mob();

    ServerLevel level();

    /** Läuft zum Block; true, wenn die Füße höchstens √reachSqr entfernt sind. */
    boolean approach(BlockPos p, double reachSqr);

    /** Läuft zum Wesen; true, wenn es höchstens √reachSqr entfernt ist. */
    boolean approachEntity(Entity e, double reachSqr);

    /** Ziel mit der neuen Wegfindung; bei "unerreichbar" übernimmt für key eine Weile die alte Navigation. */
    boolean approachGoal(Goal g, BlockPos key);

    /** Ob die neue Wegfindung für dieses Ziel zuständig ist (nicht unter Wasser, nicht kurz nach Scheitern). */
    boolean useNewPathing(BlockPos p);

    /** Zählt einen Tick ohne Fortschritt als "kommt nicht weiter" mit (für Ausweichbewegungen ohne approach). */
    void trackMove();

    /** Fortschritt gemacht (Item aufgehoben, neues Ziel): Feststeck-Zähler zurück. */
    void resetStuck();

    /** Arbeit mit eigener Zeitgrenze (Feld, Zucht, Handel): Feststeck-Zähler höchstens bis max, nie überspringen. */
    void capStuck(int max);

    /** Erze am Weg vorn in die Warteschlange stellen; true = Warteschlange geändert. */
    boolean grabPassingOres(Task t);

    /** Intelligence 2.0 beschafft gerade selbst Vorrat: Wege und Quellen werden geprüft, statt blind loszulaufen. */
    boolean guardedSupply();

    /** Eigenen Plan abbrechen (Fehler melden, Warteschlange leeren, Planer kurz ruhen lassen); danach REPLACED. */
    void abortAutonomousPlan(DecisionOutcome.Reason reason);

    /** Nichts gefunden: Folgeaufgaben verwerfen, Fehler melden, Planer kurz später neu entscheiden lassen; danach REPLACED. */
    void failChain();

    /** Aufgabenübergreifendes Bergbau-Wissen dieses Begleiters. */
    MiningKnowledge mining();

    /** Spieler-Auftrag: Antwort in den Chat; eigener Plan: nur ins Log (sonst redet er bei jeder Runde). */
    default void say(String msg) {
        if (mob().goal == null) mob().tell(msg);
        mob().logInfo(msg);
    }

    default void say(net.minecraft.network.chat.Component msg) {
        if (mob().goal == null) mob().tell(msg);
        mob().logInfo(msg);
    }

}
