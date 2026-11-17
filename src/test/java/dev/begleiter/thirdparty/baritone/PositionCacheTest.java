package dev.begleiter.thirdparty.baritone;

import java.util.Random;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class PositionCacheTest {
    @Test void allLongKeysIncludingSentinelsAreValid() {
        var cache = new PositionCache<String>(4);
        for (long key : new long[]{0, -1, Long.MIN_VALUE, Long.MAX_VALUE, -4096, 4095}) {
            cache.clear();
            assertEquals(-1, cache.find(key));
            cache.put(key, Long.toString(key));
            assertTrue(cache.find(key) >= 0);
            assertEquals(Long.toString(key), cache.value(cache.find(key)));
        }
    }

    @Test void collisionsNeverReturnAnotherPositionsState() {
        var cache = new PositionCache<Long>(3);
        var random = new Random(31337);
        long[] keys = random.longs(1000).toArray();
        for (long key : keys) {
            cache.put(key, key);
            for (long candidate : keys) {
                int slot = cache.find(candidate);
                if (slot >= 0) assertEquals(candidate, cache.value(slot).longValue());
            }
        }
        assertEquals(8, cache.capacity());
    }

    @Test void nullRepresentsCachedUnloadedPosition() {
        var cache = new PositionCache<Object>(2);
        cache.put(42, null);
        assertTrue(cache.find(42) >= 0);
        assertNull(cache.value(cache.find(42)));
        assertEquals(-1, cache.find(43));
    }

    @Test void clearAndReuseCannotResurrectPreviousValues() {
        var cache = new PositionCache<String>(3);
        cache.put(1, "old");
        cache.clear();
        assertEquals(-1, cache.find(1));
        cache.put(2, "new");
        assertEquals(-1, cache.find(1));
        assertEquals("new", cache.value(cache.find(2)));
        cache.put(2, "updated");
        assertEquals("updated", cache.value(cache.find(2)));
    }

    @Test void generationWrapInvalidatesEverySlot() throws Exception {
        var cache = new PositionCache<String>(3);
        cache.put(0, "old");
        var generation = PositionCache.class.getDeclaredField("generation");
        generation.setAccessible(true);
        generation.setInt(cache, -1);
        cache.clear();
        assertEquals(-1, cache.find(0));
        cache.put(0, "after wrap");
        assertEquals("after wrap", cache.value(cache.find(0)));
    }

    @Test void rejectsUnboundedOrInvalidCapacity() {
        assertThrows(IllegalArgumentException.class, () -> new PositionCache<>(0));
        assertThrows(IllegalArgumentException.class, () -> new PositionCache<>(21));
        assertThrows(IllegalArgumentException.class, () -> new PositionCache<>(-1));
        assertEquals(2, new PositionCache<>(1).capacity());
    }
}
