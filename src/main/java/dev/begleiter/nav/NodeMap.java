package dev.begleiter.nav;

import it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap;

/** Alle bisher gesehenen Knoten, Schlüssel = gepackte Position (kein BlockPos-Objekt je Nachbar). */
public final class NodeMap {
    private final Long2ObjectOpenHashMap<Node> map = new Long2ObjectOpenHashMap<>(1024);
    /** Faktor auf die Heuristik (> 1 = zielstrebiger, Wege höchstens um diesen Faktor teurer als optimal). */
    private final double weight;

    public NodeMap() { this(1.0); }
    public NodeMap(double weight) { this.weight = weight; }

    public int size() { return map.size(); }

    public Node get(long key) { return map.get(key); }

    public Node getOrCreate(int x, int y, int z, Goal goal) {
        long key = net.minecraft.core.BlockPos.asLong(x, y, z);
        Node n = map.get(key);
        if (n == null) {
            n = new Node(x, y, z, goal.heuristic(x, y, z) * weight);
            map.put(key, n);
        }
        return n;
    }
}
