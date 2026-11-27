package dev.begleiter.ai;

import java.util.UUID;

/**
 * Höchstens eine selbstständige Planung je Server-Tick. Eine Planung kostet je nach Rezeptkette einige
 * Millisekunden; planen mehrere Begleiter im selben Tick, addiert sich das zu einem spürbaren Ruckler. Wer keinen
 * Platz bekommt, fragt im nächsten Tick wieder. Der erste Abgewiesene hat dann Vorrang, solange er jeden Tick fragt;
 * fragt er nicht mehr (Auftrag bekommen, entladen), verfällt der Vorrang. Aufträge des Spielers laufen nicht hierüber.
 *
 * <p>Nur Serverthread; kennt keine Welt, nur Tickzahl und UUIDs.
 */
public final class PlanGate {
    private PlanGate() { }

    private static long grantedTick = Long.MIN_VALUE;
    private static UUID waiting;
    private static long waitingLastAsk;
    private static int grants, deferrals;

    /** Darf dieser Begleiter in diesem Tick selbstständig planen? */
    public static synchronized boolean tryEnter(long tick, UUID who) {
        if (grantedTick == tick) return defer(who, tick);
        boolean otherWaits = waiting != null && !waiting.equals(who) && waitingLastAsk >= tick - 1;
        if (otherWaits) return defer(who, tick);
        waiting = null;
        grantedTick = tick;
        grants++;
        return true;
    }

    private static boolean defer(UUID who, long tick) {
        if (waiting == null || waitingLastAsk < tick - 1) waiting = who;
        if (waiting.equals(who)) waitingLastAsk = tick;
        deferrals++;
        return false;
    }

    /** Zähler für Diagnose und Tests: [gewährt, aufgeschoben]. */
    public static synchronized int[] stats() { return new int[]{grants, deferrals}; }

    /** Serverende: nichts in die nächste Welt mitnehmen. */
    public static synchronized void reset() {
        grantedTick = Long.MIN_VALUE;
        waiting = null;
        waitingLastAsk = 0;
        grants = 0;
        deferrals = 0;
    }
}
