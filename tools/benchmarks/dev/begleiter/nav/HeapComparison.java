package dev.begleiter.nav;

import java.util.*;
import it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap;

/** Local comparison, using the real application Node and both heap implementations. */
public class HeapComparison {
    private static volatile long blackhole;
    private static Long expectedHeapChecksum;
    private static final int ROUNDS = 11;
    private static final Goal GOAL = new Goal.Block(200, 32, 200);

    public static void main(String[] args) {
        for (int count : new int[]{128, 2048, 32768}) {
            expectedHeapChecksum = null;
            Node[] nodes = new Node[count];
            for (int i = 0; i < count; i++) nodes[i] = new Node(i, 0, 0, 0);
            for (int i = 0; i < 12; i++) { heap(false, nodes); heap(true, nodes); }
            long[] oldTimes = new long[ROUNDS], newTimes = new long[ROUNDS];
            for (int i = 0; i < ROUNDS; i++) {
                // Alternate order to reduce warmup and CPU-frequency bias.
                if ((i & 1) == 0) { oldTimes[i] = heap(false, nodes); newTimes[i] = heap(true, nodes); }
                else { newTimes[i] = heap(true, nodes); oldTimes[i] = heap(false, nodes); }
            }
            report("heap-" + count, oldTimes, newTimes);
        }
        for (boolean sparse : new boolean[]{false, true}) {
            int[] xyz = coordinates(sparse);
            for (int i = 0; i < 10; i++) { map(false, xyz); map(true, xyz); }
            long[] oldTimes = new long[ROUNDS], newTimes = new long[ROUNDS];
            for (int i = 0; i < ROUNDS; i++) {
                if ((i & 1) == 0) { oldTimes[i] = map(false, xyz); newTimes[i] = map(true, xyz); }
                else { newTimes[i] = map(true, xyz); oldTimes[i] = map(false, xyz); }
            }
            report(sparse ? "map-sparse" : "map-local", oldTimes, newTimes);
            BaritoneCellMap cells = new BaritoneCellMap();
            NodeMap ours = new NodeMap();
            for (int i = 0; i < xyz.length; i += 3) {
                ours.getOrCreate(xyz[i], xyz[i+1], xyz[i+2], GOAL);
                cells.get(xyz[i], xyz[i+1], xyz[i+2]);
            }
            System.out.printf(Locale.ROOT, "storage %s: nodes=%d, cells=%d, cell-reference-slots=%d%n",
                    sparse ? "sparse" : "local", ours.size(), cells.cells.size(), cells.cells.size() * 2048);
        }
        System.out.println("checksum=" + blackhole);
    }

    private static long heap(boolean baritone, Node[] nodes) {
        BaritoneNodeHeap newer = new BaritoneNodeHeap();
        LegacyNodeHeap older = new LegacyNodeHeap();
        Random rnd = new Random(5319);
        for (Node n : nodes) {
            n.heapIdx = -1;
            n.f = rnd.nextDouble() * 100000;
            if (baritone) newer.add(n); else older.add(n);
        }
        long sum = 0, start = System.nanoTime();
        // Search-like mix: improve nodes, extract best, reopen with a fresh cost.
        for (int i = 0; i < 200000; i++) {
            Node n = nodes[rnd.nextInt(nodes.length)];
            n.f *= 0.7;
            if (baritone) newer.update(n); else older.update(n);
            Node taken = baritone ? newer.poll() : older.poll();
            sum += taken.x;
            taken.f = rnd.nextDouble() * 100000;
            if (baritone) newer.add(taken); else older.add(taken);
        }
        long elapsed = System.nanoTime() - start;
        if (expectedHeapChecksum != null && expectedHeapChecksum != sum)
            throw new AssertionError("Heap implementations extracted different nodes");
        expectedHeapChecksum = sum;
        blackhole = sum;
        return elapsed;
    }

    private static int[] coordinates(boolean sparse) {
        int[] xyz = new int[50000 * 3];
        Random rnd = new Random(7521);
        for (int i = 0; i < xyz.length; i += 3) {
            xyz[i] = sparse ? i / 3 : rnd.nextInt(80) - 40;
            xyz[i+1] = sparse ? 63 : rnd.nextInt(16) - 8;
            xyz[i+2] = sparse ? 0 : rnd.nextInt(80) - 40;
        }
        return xyz;
    }

    private static long map(boolean baritone, int[] xyz) {
        NodeMap ours = new NodeMap();
        BaritoneCellMap cells = new BaritoneCellMap();
        long start = System.nanoTime();
        long sum = 0;
        for (int repeat = 0; repeat < 4; repeat++) {
            for (int i = 0; i < xyz.length; i += 3) {
                Node n = baritone ? cells.get(xyz[i], xyz[i+1], xyz[i+2])
                        : ours.getOrCreate(xyz[i], xyz[i+1], xyz[i+2], GOAL);
                sum += n.x;
            }
        }
        long elapsed = System.nanoTime() - start;
        blackhole = sum;
        return elapsed;
    }

    private static void report(String label, long[] ours, long[] theirs) {
        Arrays.sort(ours); Arrays.sort(theirs);
        double a = ours[ROUNDS/2] / 1e6, b = theirs[ROUNDS/2] / 1e6;
        System.out.printf(Locale.ROOT, "%s: ours=%.3f ms, baritone=%.3f ms, change=%+.1f%%%n", label, a, b, 100*(b/a-1));
    }

}
