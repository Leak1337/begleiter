package dev.begleiter.nav;

/** Ergebnis einer Kostenrechnung, ein Objekt pro Suche wiederverwendet (keine Allokation je Nachbar). */
public final class MoveResult {
    public int x, y, z;
    public double cost;
    /** Blöcke, die diese Bewegung setzt (Brücke, Säule, Stufe). */
    public int placed;
    /** Interne Fortsetzung: noch kein gültiges Kostenresultat, keine negative Cacheaussage. */
    boolean yielded;

    void reset(int x, int y, int z) {
        this.x = x;
        this.y = y;
        this.z = z;
        this.cost = Costs.INF;
        this.placed = 0;
        this.yielded = false;
    }
}
