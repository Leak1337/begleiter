package dev.begleiter;

/**
 * Reine Regeln des simulierten Angelns ohne Minecraft-Abhängigkeit: Wartezeit bis zum Biss und welche
 * Beutegruppe gezogen wird. Die Gewichte folgen der Vanilla-Tabelle {@code gameplay/fishing}: Fisch 85 (Qualität −1),
 * Schrott 10 (−2), Schatz 5 (+2, nur in offenem Wasser); wirksames Gewicht = max(0, ⌊Gewicht + Qualität × Glück⌋).
 */
public final class FishingRules {
    public enum Catch { FISH, JUNK, TREASURE }

    /** Vanilla: 100–600 Ticks bis etwas anbeißt, je Köder-Stufe 5 s weniger. */
    public static final int MIN_WAIT = 100;
    public static final int WAIT_SPREAD = 500;
    public static final int LURE_TICKS = 100;
    /** Untergrenze, auch mit hohem Köder (Vanilla zieht die Köderzeit nur von der Wartezeit ab). */
    public static final int FLOOR_WAIT = 20;
    /** Höchstens so lange ohne Fang, dann gibt die Aufgabe auf (begrenzte Wartezeit). */
    public static final int GIVE_UP_TICKS = 2400;

    private FishingRules() { }

    /** roll aus [0, WAIT_SPREAD]; lure = Köder-Stufe (0–3+). */
    public static int waitTicks(int roll, int lure) {
        int r = Math.clamp(roll, 0, WAIT_SPREAD);
        return Math.max(FLOOR_WAIT, MIN_WAIT + r - Math.max(0, lure) * LURE_TICKS);
    }

    static int fishWeight(float luck) { return weight(85, -1, luck); }
    static int junkWeight(float luck) { return weight(10, -2, luck); }
    static int treasureWeight(float luck, boolean openWater) { return openWater ? weight(5, 2, luck) : 0; }

    private static int weight(int base, int quality, float luck) {
        return Math.max(0, (int) Math.floor(base + quality * luck));
    }

    /** Summe der wirksamen Gewichte (Obergrenze für roll). */
    public static int totalWeight(float luck, boolean openWater) {
        return fishWeight(luck) + junkWeight(luck) + treasureWeight(luck, openWater);
    }

    /** roll aus [0, totalWeight); Reihenfolge wie in der Vanilla-Tabelle: Fisch, Schrott, Schatz. */
    public static Catch pick(int roll, float luck, boolean openWater) {
        int total = totalWeight(luck, openWater);
        if (total <= 0) return Catch.FISH;
        int r = Math.floorMod(roll, total);
        if ((r -= fishWeight(luck)) < 0) return Catch.FISH;
        if ((r -= junkWeight(luck)) < 0) return Catch.JUNK;
        return Catch.TREASURE;
    }

    /** Vorgabe für "angel" ohne Anzahl. */
    public static final int DEFAULT_CATCHES = 6;
    public static final int MAX_CATCHES = 64;

    public static int catches(int requested) {
        return requested <= 0 ? DEFAULT_CATCHES : Math.min(requested, MAX_CATCHES);
    }
}
