package dev.begleiter.nav;

/**
 * Kosten der Wegfindung in Ticks. Lauf-Werte stammen aus der Spieler-Physik (Blöcke pro Sekunde); sie werden später
 * mit den Benchmark-Messungen am Begleiter kalibriert (Mob-Tempo 0,3 statt Spieler 0,1 × 4,317).
 */
public final class Costs {
    private Costs() {}

    /** Unmöglich: endlich, damit Summen nicht überlaufen. */
    public static final double INF = 1_000_000;

    public static final double WALK = 20 / 4.317;
    public static final double SPRINT = 20 / 5.612;
    public static final double SPRINT_FACTOR = SPRINT / WALK;
    public static final double SNEAK = 20 / 1.3;
    public static final double WATER = 20 / 2.2;
    public static final double SOUL_SAND = WALK * 2;
    public static final double LADDER_UP = 20 / 2.35;
    public static final double LADDER_DOWN = 20 / 3.0;
    /** Kante runtergehen: man fällt schon, bevor man die Blockmitte erreicht. */
    public static final double WALK_OFF_EDGE = WALK * 0.8;
    public static final double CENTER_AFTER_FALL = WALK - WALK_OFF_EDGE;

    // Zuschläge
    public static final double JUMP_PENALTY = 2;
    public static final double BREAK_PENALTY = 2;
    /** Pause zwischen zwei abgebauten Blöcken (wie beim Spieler). */
    public static final double BREAK_COOLDOWN = 5;
    public static final double PLACE = 20;
    public static final double WATER_ENTRY = 3;
    public static final double OPEN_DOOR = 5;
    public static final double BUCKET = 20;
    /** Je Leben Fallschaden. */
    public static final double DAMAGE = 25;
    /** Gerüst später wieder abbauen (ungefähr ein Erdblock mit Hand plus Weg). */
    public static final double SCAFFOLD_CLEANUP = 6;

    public static final int MAX_FALL_TABLE = 64;
    /** FALL[n] = Ticks, um n Blöcke zu fallen. */
    public static final double[] FALL = new double[MAX_FALL_TABLE + 1];
    /** Einen Block hochspringen: Steigzeit bis 1,25 über dem Boden, ab 0,25 zählt schon als oben. */
    public static final double JUMP;

    static {
        for (int n = 0; n <= MAX_FALL_TABLE; n++) FALL[n] = fallTicks(n);
        JUMP = fallTicks(1.25) - fallTicks(0.25);
    }

    /** Fallzeit für h Blöcke aus der Schwerkraft (0,08 pro Tick, Luftwiderstand 0,98), zwischen Ticks interpoliert. */
    public static double fallTicks(double h) {
        if (h <= 0) return 0;
        double v = 0, d = 0;
        for (int t = 0; t < 10_000; t++) {
            v = (v + 0.08) * 0.98;
            if (d + v >= h) return t + (h - d) / v;
            d += v;
        }
        return INF;
    }
}
