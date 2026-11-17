package dev.begleiter.nav;

import net.minecraft.core.BlockPos;

/** Suchknoten: Fußposition, Kosten bisher (g), Schätzung bis zum Ziel (h), Vorgänger und die Bewegung dorthin. */
public final class Node {
    public final int x, y, z;
    public final long key;
    public final double h;
    public double g = Costs.INF;
    public double f;
    public Node prev;
    public Move move;
    /** Bis hierher gesetzte Blöcke (gegen den Müllblock-Vorrat). */
    public int placed;
    int heapIdx = -1;

    Node(int x, int y, int z, double h) {
        this.x = x;
        this.y = y;
        this.z = z;
        this.key = BlockPos.asLong(x, y, z);
        this.h = h;
    }

    public BlockPos pos() { return new BlockPos(x, y, z); }

    int distSq(Node o) {
        int dx = x - o.x, dy = y - o.y, dz = z - o.z;
        return dx * dx + dy * dy + dz * dz;
    }
}
