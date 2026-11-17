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

import it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap;

public final class BaritoneCellMap {
        private static final Goal GOAL = new Goal.Block(200, 32, 200);
        final Long2ObjectOpenHashMap<Node[]> cells = new Long2ObjectOpenHashMap<>();
        private Node[] previous;
        private long previousKey = Long.MIN_VALUE;
        Node get(int x, int y, int z) {
            long key = ((long)((x >> 4) & 0xFFFFFFF) << 36)
                    | ((long)((z >> 4) & 0xFFFFFFF) << 8) | ((y >> 3) & 0xFF);
            Node[] cell;
            if (key == previousKey) cell = previous;
            else {
                cell = cells.get(key);
                if (cell == null) { cell = new Node[2048]; cells.put(key, cell); }
                previous = cell; previousKey = key;
            }
            int index = ((y & 7) << 8) | ((z & 15) << 4) | (x & 15);
            Node n = cell[index];
            if (n == null) { n = new Node(x, y, z, GOAL.heuristic(x, y, z)); cell[index] = n; }
            return n;
        }
    }
