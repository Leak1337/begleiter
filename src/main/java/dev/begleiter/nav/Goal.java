package dev.begleiter.nav;

import java.util.List;

import net.minecraft.core.BlockPos;

/**
 * Wohin die Suche will. Aufgaben liefern nur ein Ziel; wie man hinkommt, entscheidet die Wegfindung.
 * Die Heuristik schätzt die Restkosten in Ticks (Oktil-Abstand × Sprint, plus Höhe).
 */
public interface Goal {
    boolean isInGoal(int x, int y, int z);

    double heuristic(int x, int y, int z);

    static double xz(int dx, int dz) {
        dx = Math.abs(dx);
        dz = Math.abs(dz);
        int min = Math.min(dx, dz), max = Math.max(dx, dz);
        return (min * Math.sqrt(2) + (max - min)) * Costs.SPRINT;
    }

    /** dy = Ziel minus jetzt (positiv = hoch). */
    static double y(int dy) {
        return dy > 0 ? dy * Costs.JUMP : -dy * Costs.FALL[2] / 2;
    }

    /** Genau dieser Fußblock. */
    record Block(int x, int y, int z) implements Goal {
        public Block(BlockPos p) { this(p.getX(), p.getY(), p.getZ()); }
        public boolean isInGoal(int x, int y, int z) { return x == this.x && y == this.y && z == this.z; }
        public double heuristic(int x, int y, int z) { return xz(this.x - x, this.z - z) + Goal.y(this.y - y); }
    }

    /** Füße höchstens r Blöcke von pos entfernt. */
    record Near(int x, int y, int z, int rSq) implements Goal {
        public Near(BlockPos p, int r) { this(p.getX(), p.getY(), p.getZ(), r * r); }
        public boolean isInGoal(int x, int y, int z) {
            // Fernziele dürfen beim Quadrieren nicht überlaufen und als erreicht gelten.
            double dx = (double) x - this.x, dy = (double) y - this.y, dz = (double) z - this.z;
            return dx * dx + dy * dy + dz * dz <= rSq;
        }
        public double heuristic(int x, int y, int z) { return xz(this.x - x, this.z - z) + Goal.y(this.y - y); }
    }

    /** Waagerecht höchstens r Blöcke von (x, z), Höhe egal – Ende einer weiten Reise. */
    record NearXZ(int x, int z, int r) implements Goal {
        public boolean isInGoal(int x, int y, int z) {
            double dx = (double) x - this.x, dz = (double) z - this.z;
            return dx * dx + dz * dz <= (double) r * r;
        }
        public double heuristic(int x, int y, int z) {
            double dx = (double) x - this.x, dz = (double) z - this.z;
            double d = Math.sqrt(dx * dx + dz * dz);
            return Math.max(0, d - r) * Costs.SPRINT;
        }
    }

    /** Nur die Spalte zählt (Höhe egal) – für weite Reisen. */
    record XZ(int x, int z) implements Goal {
        public boolean isInGoal(int x, int y, int z) { return x == this.x && z == this.z; }
        public double heuristic(int x, int y, int z) { return xz(this.x - x, this.z - z); }
    }

    /** Neben einem Block stehen, um ihn abzubauen/zu setzen/zu öffnen (nicht im Block selbst). */
    record Adjacent(int x, int y, int z) implements Goal {
        public Adjacent(BlockPos p) { this(p.getX(), p.getY(), p.getZ()); }
        public boolean isInGoal(int x, int y, int z) {
            int dx = x - this.x, dy = y - this.y, dz = z - this.z;
            if (dx == 0 && dz == 0 && (dy == 0 || dy == -1)) return false; // im Block oder Kopf darin
            return Math.abs(dx) <= 1 && Math.abs(dz) <= 1 && dy >= -2 && dy <= 1;
        }
        public double heuristic(int x, int y, int z) {
            int dx = Math.max(0, Math.abs(this.x - x) - 1), dz = Math.max(0, Math.abs(this.z - z) - 1);
            return xz(dx, dz) + Goal.y(this.y - y);
        }
    }

    /** Mindestens r Blöcke (waagerecht) weg von pos – zum Fliehen. */
    record AwayFrom(int x, int z, int r) implements Goal {
        public boolean isInGoal(int x, int y, int z) {
            // wie Near: bei fernen Koordinaten nicht überlaufen
            double dx = (double) x - this.x, dz = (double) z - this.z;
            return dx * dx + dz * dz >= (double) r * r;
        }
        public double heuristic(int x, int y, int z) {
            double d = Math.sqrt((double) (x - this.x) * (x - this.x) + (double) (z - this.z) * (z - this.z));
            return Math.max(0, r - d) * Costs.SPRINT;
        }
    }

    /** Irgendeins von mehreren Zielen (z. B. alle bekannten Erze); A* nimmt das günstigste. */
    record AnyOf(List<Goal> goals) implements Goal {
        public boolean isInGoal(int x, int y, int z) {
            for (Goal g : goals) if (g.isInGoal(x, y, z)) return true;
            return false;
        }
        public double heuristic(int x, int y, int z) {
            double best = Costs.INF;
            for (Goal g : goals) best = Math.min(best, g.heuristic(x, y, z));
            return best;
        }
    }
}
