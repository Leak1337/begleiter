/*
 * This file is derived from Baritone.
 * SPDX-License-Identifier: LGPL-3.0-or-later
 *
 * Modified by Leak1337 for Begleiter on 2026-11-02:
 * - Renamed package/class and methods to preserve Begleiter's NodeHeap API.
 * - Uses Begleiter Node fields f/heapIdx; removed IOpenSet; added contains.
 * - Rejects invalid capacities; comparison notes adjusted.
 * Upstream: https://github.com/cabaletta/baritone/blob/c55a9c5d9a333c9f0336179a96c13a178fcbc2d4/src/main/java/baritone/pathing/calc/openset/BinaryHeapOpenSet.java
 *
 * Baritone is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Lesser General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * Baritone is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU Lesser General Public License for more details.
 *
 * You should have received a copy of the GNU Lesser General Public License
 * along with Baritone.  If not, see <https://www.gnu.org/licenses/>.
 */

package dev.begleiter.nav;

import java.util.Arrays;

/**
 * A binary heap implementation of an open set. This is the one used in the AStarPathFinder.
 *
 * @author leijurv
 */
public final class NodeHeap {

    /**
     * The initial capacity of the heap (2^10)
     */
    private static final int INITIAL_CAPACITY = 1024;

    /**
     * The array backing the heap
     */
    private Node[] array;

    // keys[i] is always array[i].f
    // so sifting compares doubles that are right next to each other instead of chasing a pointer per level
    // into a Node that is no longer in the CPU cache.
    private double[] keys;

    /**
     * The size of the heap
     */
    private int size;

    public NodeHeap() {
        this(INITIAL_CAPACITY);
    }

    public NodeHeap(int size) {
        if (size < 2) throw new IllegalArgumentException("Heap capacity must be at least 2");
        this.size = 0;
        this.array = new Node[size];
        this.keys = new double[size];
    }

    public boolean contains(Node node) { return node.heapIdx > 0; }

    public int size() {
        return size;
    }

    public final void add(Node value) {
        if (size >= array.length - 1) {
            array = Arrays.copyOf(array, array.length << 1);
            keys = Arrays.copyOf(keys, keys.length << 1);
        }
        size++;
        siftUp(size, value, value.f);
    }

    public final void update(Node val) {
        // cost only ever goes down so it only ever goes up (the heap i mean)
        siftUp(val.heapIdx, val, val.f);
    }

    private void siftUp(int index, Node val, double cost) {
        Node[] array = this.array;
        double[] keys = this.keys;
        int parentInd = index >>> 1;
        while (index > 1 && keys[parentInd] > cost) {
            Node parentNode = array[parentInd];
            array[index] = parentNode;
            keys[index] = keys[parentInd];
            parentNode.heapIdx = index;
            index = parentInd;
            parentInd = index >>> 1;
        }
        array[index] = val;
        keys[index] = cost;
        val.heapIdx = index;
    }

    public final boolean isEmpty() {
        return size == 0;
    }

    public final Node poll() {
        if (size == 0) {
            throw new IllegalStateException("Cannot remove from empty heap");
        }
        Node[] array = this.array;
        double[] keys = this.keys;
        Node result = array[1];
        result.heapIdx = -1;
        Node val = array[size];
        double cost = keys[size];
        array[size] = null;
        size--;
        if (size == 0) {
            return result;
        }
        int index = 1;
        int child = 2;
        while (child <= size) {
            double childCost = keys[child];
            if (child < size && keys[child + 1] < childCost) {
                child++;
                childCost = keys[child];
            }
            if (cost <= childCost) {
                break;
            }
            Node childNode = array[child];
            array[index] = childNode;
            keys[index] = childCost;
            childNode.heapIdx = index;
            index = child;
            child = index << 1;
        }
        array[index] = val;
        keys[index] = cost;
        val.heapIdx = index;
        return result;
    }
}
