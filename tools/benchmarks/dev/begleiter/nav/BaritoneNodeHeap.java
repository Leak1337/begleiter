/*
 * This file is adapted from Baritone for comparison benchmarks.
 * SPDX-License-Identifier: LGPL-3.0-or-later
 * Modified by Leak1337 on 2026-11-02; see THIRD_PARTY_NOTICES.md.
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
public final class BaritoneNodeHeap {

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
    // into some Node that fell out of cache ages ago. this was worth 15% by itself, i was not expecting that
    private double[] keys;

    /**
     * The size of the heap
     */
    private int size;

    public BaritoneNodeHeap() {
        this(INITIAL_CAPACITY);
    }

    public BaritoneNodeHeap(int size) {
        this.size = 0;
        this.array = new Node[size];
        this.keys = new double[size];
    }

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
