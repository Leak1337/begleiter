package dev.begleiter.nav;

import static dev.begleiter.nav.BlockFlags.*;

/**
 * Die Bewegungen der Suche mit ihren Kostenfunktionen. Jede rechnet von einer Fußposition aus, schreibt Ziel und
 * Kosten in ein {@link MoveResult} (Kosten {@link Costs#INF} = geht nicht) und liest nur über den {@link NavContext}.
 */
public enum Move {
    TRAVERSE_N(Kind.TRAVERSE, 0, -1), TRAVERSE_S(Kind.TRAVERSE, 0, 1), TRAVERSE_E(Kind.TRAVERSE, 1, 0), TRAVERSE_W(Kind.TRAVERSE, -1, 0),
    DIAGONAL_NE(Kind.DIAGONAL, 1, -1), DIAGONAL_NW(Kind.DIAGONAL, -1, -1), DIAGONAL_SE(Kind.DIAGONAL, 1, 1), DIAGONAL_SW(Kind.DIAGONAL, -1, 1),
    ASCEND_N(Kind.ASCEND, 0, -1), ASCEND_S(Kind.ASCEND, 0, 1), ASCEND_E(Kind.ASCEND, 1, 0), ASCEND_W(Kind.ASCEND, -1, 0),
    DESCEND_N(Kind.DESCEND, 0, -1), DESCEND_S(Kind.DESCEND, 0, 1), DESCEND_E(Kind.DESCEND, 1, 0), DESCEND_W(Kind.DESCEND, -1, 0),
    PARKOUR_N(Kind.PARKOUR, 0, -1), PARKOUR_S(Kind.PARKOUR, 0, 1), PARKOUR_E(Kind.PARKOUR, 1, 0), PARKOUR_W(Kind.PARKOUR, -1, 0),
    PARKOUR_NE(Kind.PARKOUR, 1, -1), PARKOUR_NW(Kind.PARKOUR, -1, -1), PARKOUR_SE(Kind.PARKOUR, 1, 1), PARKOUR_SW(Kind.PARKOUR, -1, 1),
    // geradeaus, aber einen Block links/rechts versetzt landen
    PARKOUR_N_L(Kind.PARKOUR, 0, -1, 1), PARKOUR_N_R(Kind.PARKOUR, 0, -1, -1), PARKOUR_S_L(Kind.PARKOUR, 0, 1, 1),
    PARKOUR_S_R(Kind.PARKOUR, 0, 1, -1), PARKOUR_E_L(Kind.PARKOUR, 1, 0, 1), PARKOUR_E_R(Kind.PARKOUR, 1, 0, -1),
    PARKOUR_W_L(Kind.PARKOUR, -1, 0, 1), PARKOUR_W_R(Kind.PARKOUR, -1, 0, -1),
    PILLAR(Kind.PILLAR, 0, 0), DOWNWARD(Kind.DOWNWARD, 0, 0),
    CLIMB_UP(Kind.CLIMB_UP, 0, 0), CLIMB_DOWN(Kind.CLIMB_DOWN, 0, 0), SWIM_UP(Kind.SWIM_UP, 0, 0);

    public enum Kind { TRAVERSE, DIAGONAL, ASCEND, DESCEND, PARKOUR, PILLAR, DOWNWARD, CLIMB_UP, CLIMB_DOWN, SWIM_UP }

    public static final Move[] ALL = values();
    private static final double SQRT2 = Math.sqrt(2);
    /** Parkour: so tief darf man unter der Lücke höchstens fallen, ohne dass es als gefährlich zählt. */
    private static final int SAFE_GAP_DEPTH = 3;
    /** So tief darf er mit Wassereimer springen (Wasser kurz vor dem Aufprall setzen). */
    static final int BUCKET_FALL = 40;

    public final Kind kind;
    public final int dx, dz;
    /** Parkour: Landung so viele Blöcke quer zur Richtung versetzt (links +1, rechts −1). */
    public final int side;

    Move(Kind kind, int dx, int dz) { this(kind, dx, dz, 0); }

    Move(Kind kind, int dx, int dz, int side) {
        this.kind = kind;
        this.dx = dx;
        this.dz = dz;
        this.side = side;
    }

    public void cost(NavContext c, int x, int y, int z, MoveResult r) {
        cost(c, x, y, z, r, null, 0, 0);
    }

    /** Nur die Suche darf vor einer neuen Simulation pausieren; Live-Prüfungen nutzen den öffentlichen Aufruf. */
    void cost(NavContext c, int x, int y, int z, MoveResult r, java.util.function.LongSupplier clock, long started, long budget) {
        r.reset(x + dx, y, z + dz);
        switch (kind) {
            case TRAVERSE -> traverse(c, x, y, z, r);
            case DIAGONAL -> diagonal(c, x, y, z, r);
            case ASCEND -> ascend(c, x, y, z, r);
            case DESCEND -> descend(c, x, y, z, r);
            case PARKOUR -> parkour(c, x, y, z, r, clock, started, budget);
            case PILLAR -> pillar(c, x, y, z, r);
            case DOWNWARD -> downward(c, x, y, z, r);
            case CLIMB_UP -> climb(c, x, y, z, r, 1);
            case CLIMB_DOWN -> climb(c, x, y, z, r, -1);
            case SWIM_UP -> swimUp(c, x, y, z, r);
        }
        if (r.yielded) return;
        if (!c.worldBorder.entirelyContains(r.x, r.z)) r.cost = Costs.INF;
        if (r.placed > 0 && !c.worldBorder.canPlaceAt(kind == Kind.PILLAR ? x : r.x, kind == Kind.PILLAR ? z : r.z))
            r.cost = Costs.INF;
        if (r.cost < Costs.INF && !c.safeSegment(x, y, z, r.x, r.y, r.z)) r.cost = Costs.INF;
    }

    /**
     * Im Wasser (auch Wasserfall) einen Block hochschwimmen: Füße und Ziel im Wasser, Kopf im Wasser oder frei. Aus der
     * Oberfläche heraus nicht – dort kann er nicht stehen; raus geht es mit ASCEND/TRAVERSE.
     */
    private void swimUp(NavContext c, int x, int y, int z, MoveResult r) {
        int head = fl(c, x, y + 2, z);
        if (!has(fl(c, x, y, z), SWIM) || !has(fl(c, x, y + 1, z), SWIM)) return;
        if (!has(head, SWIM) && !has(head, PASS)) return;
        r.y = y + 1;
        r.cost = Costs.WATER;
    }

    /** An einer Leiter/Ranke einen Block hoch oder runter (Füße müssen am Klettergewächs sein). */
    private void climb(NavContext c, int x, int y, int z, MoveResult r, int dir) {
        if (dir > 0) {
            if (!has(fl(c, x, y, z), CLIMB) || !has(fl(c, x, y + 1, z), CLIMB) || !has(fl(c, x, y + 2, z), PASS)) return;
            r.y = y + 1;
            r.cost = Costs.LADDER_UP;
        } else {
            if (!has(fl(c, x, y - 1, z), CLIMB)) return;
            r.y = y - 1;
            r.cost = Costs.LADDER_DOWN;
        }
    }

    // ------------------------------------------------------------------ Hilfen

    private static int fl(NavContext c, int x, int y, int z) { return c.blocks.flags(x, y, z); }
    private static boolean pass(NavContext c, int x, int y, int z) {
        int f = fl(c, x, y, z);
        return has(f, PASS) || (c.allowPortal && has(f, PORTAL))
                || (has(f, END_PORTAL) && c.canEnterEndPortal(x, y, z));
    }
    private static boolean standable(int f) { return has(f, STAND) && !has(f, AVOID_INTO); }
    private static boolean standable(NavContext c, int x, int y, int z) { return standable(fl(c, x, y, z)); }

    /** Lava neben Füßen oder Kopf. */
    private static boolean lavaNear(NavContext c, int x, int y, int z) {
        for (int yy = y; yy <= y + 1; yy++)
            if (has(fl(c, x + 1, yy, z), LAVA) || has(fl(c, x - 1, yy, z), LAVA) || has(fl(c, x, yy, z + 1), LAVA)
                    || has(fl(c, x, yy, z - 1), LAVA) || has(fl(c, x, yy + 1, z), LAVA)) return true;
        return has(fl(c, x, y - 1, z), LAVA);
    }

    // ------------------------------------------------------------------ Bewegungen

    /** Holztür/Zauntor: öffnen statt abbauen (gilt als frei, kostet {@link Costs#OPEN_DOOR}). */
    private static boolean openable(int f) { return has(f, DOOR | GATE); }

    /** Frei machen: Tür/Tor öffnen, Wasser an der Oberfläche durchschwimmen (nur Füße), sonst abbauen. */
    private static double clear(NavContext c, int x, int y, int z, boolean feetCell) {
        int f = fl(c, x, y, z);
        if (openable(f)) return Costs.OPEN_DOOR;
        if (feetCell && has(f, SWIM)) return 0;
        return c.breakCost(x, y, z);
    }

    /** Geradeaus, ggf. Füße/Kopf freigraben (Türen öffnen); ohne Boden eine Brücke setzen; im Wasser schwimmen. */
    private void traverse(NavContext c, int x, int y, int z, MoveResult r) {
        int tx = x + dx, tz = z + dz;
        int headF = fl(c, tx, y + 1, tz), feetF = fl(c, tx, y, tz);
        boolean under = has(headF, SWIM) && has(feetF, SWIM);
        double head = (openable(headF) && openable(feetF)) || under ? 0 : clear(c, tx, y + 1, tz, false); // Tür: ein Block, zwei Hälften
        if (head >= Costs.INF) return;
        double feet = clear(c, tx, y, tz, true);
        if (feet >= Costs.INF || lavaNear(c, tx, y, tz)) return;
        if (has(feetF, SWIM)) {
            // an der Oberfläche schwimmen: Kopf über Wasser; Kopf im Wasser (Wasserfall, Tauchen) nur kurz und teurer
            if (!has(headF, PASS) && !under) return;
            r.cost = Costs.WATER * (under ? 2 : 1) + (has(fl(c, x, y, z), WATER) ? 0 : Costs.WATER_ENTRY);
            return;
        }
        int below = fl(c, tx, y - 1, tz);
        if (has(feetF, CLIMB) || (has(below, CLIMB) && !standable(below))) {
            r.cost = Costs.WALK + head + feet; // an eine Leiter/Ranke treten (unten oder oben am Ende)
        } else if (standable(below)) {
            double walk = has(below, SLOW) ? Costs.SOUL_SAND : Costs.WALK;
            if (head + feet == 0 && c.canSprint && !has(below, SLOW)) walk *= Costs.SPRINT_FACTOR;
            r.cost = walk + head + feet;
        } else if (c.allowPlace && has(below, PASS) && !has(below, LIQUID)) {
            r.cost = Costs.WALK + Costs.PLACE + head + feet;
            r.placed = 1;
        }
    }

    /** Schräg, ohne Abbauen; beide Ecken frei (der Mob bleibt sonst an der Kante hängen). */
    private void diagonal(NavContext c, int x, int y, int z, MoveResult r) {
        int tx = x + dx, tz = z + dz;
        if (!pass(c, tx, y, tz) || !pass(c, tx, y + 1, tz)) return;
        int below = fl(c, tx, y - 1, tz);
        if (!standable(below) || lavaNear(c, tx, y, tz)) return;
        boolean a = pass(c, tx, y, z) && pass(c, tx, y + 1, z);
        boolean b = pass(c, x, y, tz) && pass(c, x, y + 1, tz);
        if (!a || !b) return;
        // an Ecken nicht über Feuer/Kaktus/Magma streifen
        if (has(fl(c, tx, y, z) | fl(c, tx, y + 1, z) | fl(c, x, y, tz) | fl(c, x, y + 1, tz), AVOID_INTO)) return;
        if (has(fl(c, tx, y - 1, z) | fl(c, x, y - 1, tz), AVOID_INTO)) return;
        double walk = has(below, SLOW) ? Costs.SOUL_SAND : Costs.WALK;
        if (c.canSprint && !has(below, SLOW)) walk *= Costs.SPRINT_FACTOR;
        r.cost = walk * SQRT2;
    }

    /** Eine Stufe hoch: drei Blöcke frei (über dem Kopf, Ziel Füße und Kopf); fehlt die Stufe, wird sie gesetzt. */
    private void ascend(NavContext c, int x, int y, int z, MoveResult r) {
        int tx = x + dx, tz = z + dz;
        r.y = y + 1;
        int step = fl(c, tx, y, tz);
        double place = 0;
        if (!standable(step)) {
            if (!c.allowPlace || !has(step, PASS) || has(step, LIQUID)) return;
            place = Costs.PLACE;
            r.placed = 1;
        }
        double above = c.breakCost(x, y + 2, z);
        if (above >= Costs.INF) return;
        double head = c.breakCost(tx, y + 2, tz);
        if (head >= Costs.INF) return;
        double feet = c.breakCost(tx, y + 1, tz);
        if (feet >= Costs.INF || lavaNear(c, tx, y + 1, tz)) return;
        r.cost = Math.max(Costs.JUMP, Costs.WALK) + Costs.JUMP_PENALTY + above + head + feet + place;
    }

    /** Einen Block runter (Boden darunter ggf. abbauen) oder tiefer fallen, solange der Schaden vertretbar ist. */
    private void descend(NavContext c, int x, int y, int z, MoveResult r) {
        int tx = x + dx, tz = z + dz;
        double top = c.breakCost(tx, y + 1, tz);
        if (top >= Costs.INF) return;
        double mid = c.breakCost(tx, y, tz);
        if (mid >= Costs.INF) return;
        int f1 = fl(c, tx, y - 1, tz);
        if (has(f1, WATER) && !has(f1, LAVA) && has(fl(c, tx, y, tz), PASS)) {
            // vom Ufer ins Wasser (Oberfläche einen tiefer)
            r.y = y - 1;
            r.cost = Costs.WALK_OFF_EDGE + Costs.WATER_ENTRY + top + mid;
            return;
        }
        if (!has(f1, PASS)) {
            // fester Block: abbauen und eine Stufe tiefer auf dem Block darunter stehen
            double low = c.breakCost(tx, y - 1, tz);
            if (low >= Costs.INF || !standable(c, tx, y - 2, tz) || lavaNear(c, tx, y - 1, tz)) return;
            r.y = y - 1;
            r.cost = Costs.WALK_OFF_EDGE + Math.max(Costs.FALL[1], Costs.CENTER_AFTER_FALL) + top + mid + low;
            return;
        }
        boolean bucket = c.waterBucket && !c.nether;
        int maxFall = Math.min(Costs.MAX_FALL_TABLE, bucket ? Math.max(c.maxFall, BUCKET_FALL) : c.maxFall);
        // bis zur vollen Tabellentiefe suchen: Wasser fängt jeden Sturz ab, fester Boden zählt nur bis maxFall.
        // Ins Wasser nur, wenn der Sturz auf Boden nicht ginge – sonst springt er in jede Rinne (Farmen) und sitzt fest.
        for (int k = 2; k - 1 <= Costs.MAX_FALL_TABLE; k++) {
            int f = fl(c, tx, y - k, tz);
            if (has(f, WATER) && !has(f, LAVA) && k - 1 > c.maxFall) {
                if (lavaNear(c, tx, y - k, tz)) return;
                r.y = y - k; // Füße im Wasser, kein Fallschaden
                r.cost = Costs.WALK_OFF_EDGE + Costs.FALL[k - 1] + Costs.WATER_ENTRY + top + mid;
                return;
            }
            if (standable(f)) {
                int n = k - 1;
                if (n > Math.max(1, maxFall)) return;
                int land = y - n;
                if (lavaNear(c, tx, land, tz)) return;
                if (n > c.maxFall && ClutchPlan.water(c, new net.minecraft.core.BlockPos(tx, land, tz), n) == null) return;
                r.y = land;
                // zu tief für Leben/Rüstung: mit dem Wassereimer kurz vor dem Aufprall Wasser setzen
                double fall = n == 1 ? Math.max(Costs.FALL[1], Costs.CENTER_AFTER_FALL)
                        : n > c.maxFall ? Costs.FALL[n] + Costs.BUCKET : c.fallCost(n);
                if (fall >= Costs.INF) return;
                r.cost = Costs.WALK_OFF_EDGE + fall + top + mid;
                return;
            }
            if (!has(f, PASS)) return; // Wasser, Lava, ungeladen, Gefahr
        }
    }

    /** Hochbauen: springen und einen Block unter sich setzen. */
    private void pillar(NavContext c, int x, int y, int z, MoveResult r) {
        if (!c.allowPlace || has(fl(c, x, y, z), LIQUID | CLIMB)) return;
        double above = c.breakCost(x, y + 2, z);
        if (above >= Costs.INF || c.breakCost(x, y + 3, z) >= Costs.INF) return;
        r.y = y + 1;
        r.placed = 1;
        r.cost = Costs.JUMP + Costs.PLACE + Costs.JUMP_PENALTY + above + Costs.SCAFFOLD_CLEANUP;
    }

    /** Den Block unter sich abbauen und einen tiefer stehen. */
    private void downward(NavContext c, int x, int y, int z, MoveResult r) {
        if (pass(c, x, y - 1, z)) return;
        double floor = c.breakCost(x, y - 1, z);
        if (floor >= Costs.INF || !standable(c, x, y - 2, z) || lavaNear(c, x, y - 1, z)) return;
        r.y = y - 1;
        r.cost = Costs.FALL[1] + floor;
    }

    private static final int NO_LANDING = Integer.MIN_VALUE;

    /**
     * Parkour über eine Lücke: geradeaus (Lücke 1–4, ab 3 nur mit Sprint), schräg (bis 3 weit) oder einen Block
     * seitlich versetzt; gleich hoch, eine Stufe höher (Lücke 1–2) oder sicher tiefer, wenn man in die Lücke nicht
     * hinabsteigen kann. Nur an einer Kante (geradeaus kein Boden). Ob und wie der Sprung geht, entscheidet die
     * Simulation ({@link JumpSim#plan}); je Richtung zählt die erste Landestelle.
     */
    private void parkour(NavContext c, int x, int y, int z, MoveResult r, java.util.function.LongSupplier clock, long started, long budget) {
        if (!c.allowParkour || !pass(c, x, y + 2, z) || !standable(c, x, y - 1, z)) return;
        int fx = x + dx, fz = z + dz;
        if (standable(c, fx, y - 1, fz) || !pass(c, fx, y, fz) || !pass(c, fx, y + 1, fz) || !pass(c, fx, y + 2, fz)) return;
        boolean diagonal = dx != 0 && dz != 0;
        int sideX = -dz * side, sideZ = dx * side;
        int maxI = diagonal ? 3 : c.canSprint ? 5 : 3;
        int pitDrop = pitDrop(c, fx, y, fz);
        int risk = 0;
        for (int i = 2; i <= maxI; i++) {
            int gx = x + dx * (i - 1), gz = z + dz * (i - 1);
            int g = fallRisk(c, gx, y, gz);
            if (g < 0) return;
            // über tiefe Abgründe lieber sicher eine Brücke, wenn Blöcke da sind
            if (g >= 10 && c.allowPlace && c.throwaway > 0) return;
            risk = Math.max(risk, g);
            if (i > 2 && (!pass(c, gx, y, gz) || !pass(c, gx, y + 1, gz) || !pass(c, gx, y + 2, gz))) return;
            int tx = x + dx * i + sideX, tz = z + dz * i + sideZ;
            int ty = landingY(c, tx, y, tz, i, pitDrop);
            if (ty == NO_LANDING) continue;
            boolean drop = ty < y;
            if (lavaNear(c, tx, ty, tz) || !safeBeyond(c, tx + dx, ty, tz + dz)) {
                if (drop) continue;
                return;
            }
            JumpSim.Plan p = JumpSim.plan(c, x, y, z, tx, ty, tz, clock, started, budget, r);
            if (r.yielded) return;
            if (p == null) {
                if (drop) continue; // weiter hinten liegt vielleicht etwas gleich hoch
                return;
            }
            r.x = tx;
            r.y = ty;
            r.z = tz;
            r.cost = p.cost + risk;
            return;
        }
    }

    /**
     * Landehöhe in der Spalte: gleich hoch, eine Stufe höher (nur kurz) oder sicher tiefer – aber höher als der Grund
     * der Lücke (sonst wäre es nur ein Sprung in die Grube).
     */
    private static int landingY(NavContext c, int tx, int y, int tz, int i, int pitDrop) {
        boolean feet = pass(c, tx, y, tz), head = pass(c, tx, y + 1, tz);
        if (feet && head && standable(c, tx, y - 1, tz)) return y;
        if (!feet && i <= 3 && standable(c, tx, y, tz) && head && pass(c, tx, y + 2, tz)) return y + 1;
        if (!feet || !head) return NO_LANDING;
        for (int d = 1; d <= c.maxFall && d < pitDrop; d++) {
            int f = fl(c, tx, y - 1 - d, tz);
            if (standable(f)) return y - d;
            if (!has(f, PASS)) return NO_LANDING;
        }
        return NO_LANDING;
    }

    /** Wie tief der Grund der Lücke unter den Füßen liegt (Fallhöhe); ohne Grund in Reichweite sehr groß. */
    private static int pitDrop(NavContext c, int x, int y, int z) {
        for (int k = 1; k <= Costs.MAX_FALL_TABLE; k++) {
            int f = fl(c, x, y - k, z);
            if (!has(f, PASS)) return k - 1;
        }
        return Integer.MAX_VALUE;
    }

    /** Hinter der Landung: nicht in Feuer/Kaktus laufen, keine Lava unter dem nächsten Block. */
    private static boolean safeBeyond(NavContext c, int x, int y, int z) {
        return !has(fl(c, x, y, z), AVOID_INTO) && !has(fl(c, x, y - 1, z), LAVA | AVOID_INTO);
    }

    /**
     * Was liegt unter der Lücke? -1 = Gefahr (Lava, Feuer, ungeladen, Leere ohne Grund), sonst Zusatzkosten nach
     * Tiefe. Ob ein Fehlsprung dort tödlich wäre, prüfen die Fehlpfade der Simulation.
     */
    private static int fallRisk(NavContext c, int x, int y, int z) {
        for (int k = 1; k <= 24; k++) {
            int f = fl(c, x, y - k, z);
            if (has(f, AVOID_INTO | UNLOADED)) return -1;
            if (has(f, WATER)) return 1;
            if (!has(f, PASS)) {
                int depth = k - 1;
                return depth <= SAFE_GAP_DEPTH ? 1 : 10 + depth;
            }
        }
        return -1;
    }
}
