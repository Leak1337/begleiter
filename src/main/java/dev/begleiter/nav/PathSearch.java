package dev.begleiter.nav;

import java.util.Objects;
import java.util.function.LongSupplier;
import net.minecraft.core.BlockPos;

/**
 * A* in Zeitscheiben: {@link #step(long)} pausiert zwischen kleinen Bewegungspaketen, vor jeder neuen Sprungsimulation
 * einzeln, und kann im nächsten Tick weitermachen.
 * Einzelne Kostenberechnungen und JVM-Pausen können die Zeitscheibe weiterhin überziehen.
 * Findet er das Ziel (noch) nicht, gibt es den besten Teilweg: für mehrere Gewichte den Knoten mit kleinstem
 * h + g/Gewicht, genommen wird das erste Gewicht, dessen Knoten mehr als 5 Blöcke vom Start weg ist.
 */
public final class PathSearch {
    public enum Status { RUNNING, FOUND, PARTIAL, FAILED }

    private static final double[] COEFF = {1.5, 2, 2.5, 3, 4, 5, 10};
    private static final double MIN_IMPROVE = 0.01;
    private static final int MIN_PARTIAL_DIST_SQ = 5 * 5;
    /** Gesamt-Rechenzeit: mit brauchbarem Teilweg / ohne. */
    public static final long LIMIT_PROGRESS = 500_000_000L, LIMIT_NO_PROGRESS = 2_000_000_000L;

    private final NavContext ctx;
    private final Goal goal;
    private final LongSupplier clock;
    private final boolean fineSlices;
    /** Gewicht der Heuristik (auf den Benchmark-Strecken gemessen). */
    public static final double WEIGHT = 1.5;

    private final NodeMap map;
    private final NodeHeap open = new NodeHeap();
    private final MoveResult r = new MoveResult();
    private final Node start;
    private final Node[] best = new Node[COEFF.length];
    private final double[] bestVal = new double[COEFF.length];
    private Node found;
    private Node expanding;
    private int nextMove;
    private Status status = Status.RUNNING;
    private int expanded;
    private long nanos;
    /** Für Tests und Notfälle: höchstens so viele Knoten. */
    public int maxNodes = Integer.MAX_VALUE;

    public PathSearch(NavContext ctx, BlockPos start, Goal goal) { this(ctx, start, goal, WEIGHT); }

    public PathSearch(NavContext ctx, BlockPos start, Goal goal, double weight) {
        this(ctx, start, goal, weight, System::nanoTime, true);
    }

    /** Austauschbare Uhr und grobe Referenz ausschließlich für Komponentenvergleich und Messungen. */
    PathSearch(NavContext ctx, BlockPos start, Goal goal, double weight, LongSupplier clock, boolean fineSlices) {
        this.ctx = ctx;
        this.goal = goal;
        this.clock = Objects.requireNonNull(clock);
        this.fineSlices = fineSlices;
        this.map = new NodeMap(weight);
        this.start = map.getOrCreate(start.getX(), start.getY(), start.getZ(), goal);
        this.start.g = 0;
        this.start.f = this.start.h;
        open.add(this.start);
        for (int i = 0; i < COEFF.length; i++) {
            best[i] = this.start;
            bestVal[i] = this.start.h;
        }
    }

    /**
     * Startblock: die Füße, wenn er auf etwas steht; sonst ein begehbarer Nachbar ganz nah (steht auf der Kante) oder,
     * wenn er fällt, der Block, auf dem er landen wird.
     */
    public static BlockPos startFor(NavContext ctx, net.minecraft.world.entity.Entity mob) {
        BlockPos p = mob.blockPosition();
        BlockCache b = ctx.blocks;
        if (BlockFlags.has(b.flags(p.getX(), p.getY() - 1, p.getZ()), BlockFlags.STAND)) return p;
        BlockPos bestN = null;
        double bestD = 1.0;
        for (int dx = -1; dx <= 1; dx++)
            for (int dz = -1; dz <= 1; dz++) {
                int x = p.getX() + dx, z = p.getZ() + dz;
                if (!BlockFlags.has(b.flags(x, p.getY() - 1, z), BlockFlags.STAND)
                        || !BlockFlags.has(b.flags(x, p.getY(), z), BlockFlags.PASS)
                        || !BlockFlags.has(b.flags(x, p.getY() + 1, z), BlockFlags.PASS)) continue;
                double ex = mob.getX() - (x + 0.5), ez = mob.getZ() - (z + 0.5);
                double d = Math.sqrt(ex * ex + ez * ez);
                if (d < bestD) { bestD = d; bestN = new BlockPos(x, p.getY(), z); }
            }
        if (bestN != null) return bestN;
        for (int y = p.getY() - 1; y > b.minY && p.getY() - y <= Costs.MAX_FALL_TABLE; y--) {
            int f = b.flags(p.getX(), y - 1, p.getZ());
            if (!BlockFlags.has(f, BlockFlags.PASS)) return new BlockPos(p.getX(), y, p.getZ());
        }
        return p;
    }

    public Status status() { return status; }
    public int nodes() { return expanded; }
    public long nanos() { return nanos; }
    public boolean done() { return status != Status.RUNNING; }

    /** Rechnet bis zu budgetNanos weiter. Gibt den Status danach zurück. */
    public Status step(long budgetNanos) {
        if (done() || budgetNanos <= 0) return status;
        long t0 = clock.getAsLong();
        ctx.blocks.beginSearch();
        try {
            int minY = ctx.blocks.minY, maxY = ctx.blocks.maxY;
            searchLoop: while (expanding != null || !open.isEmpty()) {
                if (expanded > 0 && clock.getAsLong() - t0 >= budgetNanos) break;
                if (expanding == null) {
                    if (expanded >= maxNodes) { finish(); break; }
                    Node n = open.poll();
                    expanded++;
                    if (goal.isInGoal(n.x, n.y, n.z)) {
                        found = n;
                        status = Status.FOUND;
                        break;
                    }
                    expanding = n;
                    nextMove = 0;
                }
                Node n = expanding;
                while (nextMove < Move.ALL.length) {
                    Move m = Move.ALL[nextMove];
                    // Parkour prüft direkt vor neuen Simulationen; seine schnellen Cachetreffer dürfen weiterlaufen.
                    if (fineSlices && (nextMove & 7) == 0 && m.kind != Move.Kind.PARKOUR
                            && clock.getAsLong() - t0 >= budgetNanos) break searchLoop;
                    m.cost(ctx, n.x, n.y, n.z, r, fineSlices ? clock : null, t0, budgetNanos);
                    if (r.yielded) break searchLoop;
                    nextMove++;
                    if (r.cost >= Costs.INF || r.y < minY || r.y > maxY) continue;
                    if (r.placed > 0 && n.placed + r.placed > ctx.throwaway) continue;
                    double g = n.g + r.cost * ctx.favor(r.x, r.y, r.z);
                    Node nb = map.getOrCreate(r.x, r.y, r.z, goal);
                    if (g >= nb.g - MIN_IMPROVE) continue;
                    nb.prev = n;
                    nb.move = m;
                    nb.g = g;
                    nb.f = g + nb.h;
                    nb.placed = n.placed + r.placed;
                    if (open.contains(nb)) open.update(nb); else open.add(nb);
                    for (int i = 0; i < COEFF.length; i++) {
                        double v = nb.h + nb.g / COEFF[i];
                        if (v < bestVal[i] - MIN_IMPROVE) {
                            best[i] = nb;
                            bestVal[i] = v;
                        }
                    }
                }
                expanding = null;
            }
            nanos += Math.max(0, clock.getAsLong() - t0);
            if (status == Status.RUNNING) {
                if (expanding == null && open.isEmpty()) finish();
                else if (nanos > (partialNode() != null ? LIMIT_PROGRESS : LIMIT_NO_PROGRESS)) finish();
            }
            return status;
        } finally {
            ctx.blocks.endSearch(done());
            if (done()) ctx.finishSearchCaches();
        }
    }

    private void finish() {
        status = partialNode() != null ? Status.PARTIAL : Status.FAILED;
    }

    private Node partialNode() {
        for (Node n : best) if (n.distSq(start) > MIN_PARTIAL_DIST_SQ) return n;
        return null;
    }

    /** Ganzer Weg (FOUND), bester Teilweg bis jetzt (auch während RUNNING) oder null. */
    public Path path() {
        if (found != null) return Path.from(found, true);
        Node p = partialNode();
        return p == null ? null : Path.from(p, false);
    }
}
