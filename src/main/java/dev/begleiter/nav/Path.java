package dev.begleiter.nav;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import net.minecraft.core.BlockPos;

/** Ein Weg: Positionen (Start zuerst) und die Bewegung von Position i nach i+1, mit den Kosten der Suche. */
public final class Path {
    public final List<BlockPos> positions;
    public final List<Move> moves;
    public final List<Double> costs;
    /** Ziel erreicht (sonst Teilweg). */
    public final boolean complete;
    private final double[] suffixCosts;

    private Path(List<BlockPos> positions, List<Move> moves, List<Double> costs, boolean complete) {
        this.positions = List.copyOf(positions);
        this.moves = List.copyOf(moves);
        this.costs = List.copyOf(costs);
        this.complete = complete;
        suffixCosts = new double[costs.size() + 1];
        for (int i = costs.size() - 1; i >= 0; i--) suffixCosts[i] = suffixCosts[i + 1] + costs.get(i);
    }

    static Path from(Node end, boolean complete) {
        List<BlockPos> pos = new ArrayList<>();
        List<Move> mv = new ArrayList<>();
        List<Double> cs = new ArrayList<>();
        for (Node n = end; n != null; n = n.prev) {
            pos.add(n.pos());
            if (n.prev != null) {
                mv.add(n.move);
                cs.add(n.g - n.prev.g);
            }
        }
        Collections.reverse(pos);
        Collections.reverse(mv);
        Collections.reverse(cs);
        return new Path(pos, mv, cs, complete);
    }

    public int length() { return moves.size(); }
    public BlockPos start() { return positions.getFirst(); }
    public BlockPos end() { return positions.getLast(); }

    public double totalCost() {
        return suffixCosts[0];
    }

    double remainingCost(int index) { return suffixCosts[Math.min(index, length())]; }

    Path suffix(int from) {
        return new Path(positions.subList(from, positions.size()), moves.subList(from, length()), costs.subList(from, length()), complete);
    }

    Path append(Path next) {
        if (!end().equals(next.start())) throw new IllegalArgumentException("path segments do not connect");
        var pos = new ArrayList<>(positions); pos.addAll(next.positions.subList(1, next.positions.size()));
        var mv = new ArrayList<>(moves); mv.addAll(next.moves);
        var cs = new ArrayList<>(costs); cs.addAll(next.costs);
        return new Path(pos, mv, cs, next.complete);
    }

    /**
     * Gegen die aktuelle Welt prüfen: jede Bewegung neu rechnen; landet sie woanders oder ist sie unmöglich geworden,
     * endet der Weg davor. Kosten = Minimum aus neu und gesucht.
     */
    public Path verify(NavContext ctx) {
        ctx.blocks.clearCache();
        MoveResult r = new MoveResult();
        List<BlockPos> pos = new ArrayList<>(List.of(positions.getFirst()));
        List<Move> mv = new ArrayList<>();
        List<Double> cs = new ArrayList<>();
        int placed = 0;
        for (int i = 0; i < moves.size(); i++) {
            BlockPos from = positions.get(i), to = positions.get(i + 1);
            moves.get(i).cost(ctx, from.getX(), from.getY(), from.getZ(), r);
            placed += r.placed;
            if (r.cost >= Costs.INF || r.x != to.getX() || r.y != to.getY() || r.z != to.getZ() || placed > ctx.throwaway)
                return new Path(pos, mv, cs, false);
            pos.add(to);
            mv.add(moves.get(i));
            cs.add(Math.min(r.cost, costs.get(i)));
        }
        return new Path(pos, mv, cs, complete);
    }

    /** Teilweg: das unsichere Ende abschneiden (auf 90 %, mindestens 30 Bewegungen bleiben). */
    public Path trimPartial() {
        if (complete || moves.size() <= 30) return this;
        int keep = Math.max(30, (int) (moves.size() * 0.9));
        return new Path(positions.subList(0, keep + 1), moves.subList(0, keep), costs.subList(0, keep), false);
    }

    public int count(Move.Kind kind) {
        int n = 0;
        for (Move m : moves) if (m.kind == kind) n++;
        return n;
    }
}
