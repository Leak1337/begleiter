// MIT: Begleiter heap before the Baritone comparison, retained as a benchmark baseline.
package dev.begleiter.nav;

import java.util.Arrays;

/**
 * Binär-Heap nach f für die offene Liste. Index 0 bleibt leer, Kinder von i sind 2i und 2i+1. Jeder Knoten kennt seine
 * Stelle im Heap, deshalb gibt es keine Duplikate: sinken seine Kosten, wird er nur nach oben gesiebt.
 */
public final class LegacyNodeHeap {
    private Node[] a = new Node[1024];
    private int size;

    public int size() { return size; }
    public boolean isEmpty() { return size == 0; }
    public boolean contains(Node n) { return n.heapIdx > 0; }

    public void add(Node n) {
        if (++size == a.length) a = Arrays.copyOf(a, a.length * 2);
        a[size] = n;
        n.heapIdx = size;
        up(size);
    }

    /** Kosten von n sind gesunken. */
    public void update(Node n) { up(n.heapIdx); }

    public Node poll() {
        Node top = a[1];
        Node last = a[size];
        a[size--] = null;
        if (size > 0) {
            a[1] = last;
            last.heapIdx = 1;
            down(1);
        }
        top.heapIdx = -1;
        return top;
    }

    private void up(int i) {
        Node n = a[i];
        while (i > 1) {
            int p = i >> 1;
            if (a[p].f <= n.f) break;
            a[i] = a[p];
            a[i].heapIdx = i;
            i = p;
        }
        a[i] = n;
        n.heapIdx = i;
    }

    private void down(int i) {
        Node n = a[i];
        while (true) {
            int c = i << 1;
            if (c > size) break;
            if (c < size && a[c + 1].f < a[c].f) c++;
            if (a[c].f >= n.f) break;
            a[i] = a[c];
            a[i].heapIdx = i;
            i = c;
        }
        a[i] = n;
        n.heapIdx = i;
    }
}
