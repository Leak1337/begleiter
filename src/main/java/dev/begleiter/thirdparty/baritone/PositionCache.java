/*
 * Adapted from Baritone's BlockStateInterface position cache.
 * Copyright: leijurv and the Baritone contributors.
 * SPDX-License-Identifier: LGPL-3.0-or-later
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Lesser General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version. It is distributed WITHOUT ANY WARRANTY;
 * without even the implied warranty of MERCHANTABILITY or FITNESS FOR A
 * PARTICULAR PURPOSE. See LICENSES/LGPL-3.0-or-later.txt and GPL-3.0-or-later.txt.
 *
 * Upstream c55a9c5d9a333c9f0336179a96c13a178fcbc2d4,
 * src/main/java/baritone/utils/BlockStateInterface.java, get0/claimCache.
 * Changed for Begleiter: generic values, configurable bounded capacity and
 * generation tags instead of a sentinel key (every packed coordinate is valid).
 */
package dev.begleiter.thirdparty.baritone;

/** Bounded, direct-mapped lookup. Collisions evict; they never return another key's value. Not thread-safe. */
public final class PositionCache<T> {
    private final long[] keys;
    private final Object[] values;
    private final int[] generations;
    private final int shift;
    private int generation = 1;

    public PositionCache(int bits) {
        if (bits < 1 || bits > 20) throw new IllegalArgumentException("Cache bits must be in 1..20");
        keys = new long[1 << bits];
        values = new Object[1 << bits];
        generations = new int[1 << bits];
        shift = 64 - bits;
    }

    private int slot(long key) {
        return (int) ((key * 0x9E3779B97F4A7C15L) >>> shift);
    }

    /** Nonnegative index for a hit, -1 for a miss. A cached null is still a hit. */
    public int find(long key) {
        int slot = slot(key);
        return generations[slot] == generation && keys[slot] == key ? slot : -1;
    }

    @SuppressWarnings("unchecked")
    public T value(int slot) { return (T) values[slot]; }

    public void put(long key, T value) {
        int slot = slot(key);
        keys[slot] = key;
        values[slot] = value;
        generations[slot] = generation;
    }

    public void clear() {
        if (++generation == 0) {
            java.util.Arrays.fill(generations, 0);
            generation = 1;
        }
    }

    public int capacity() { return keys.length; }
}
