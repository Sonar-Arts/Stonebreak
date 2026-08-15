package com.openmason.engine.util;

import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.Map;
import java.util.Random;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Contract tests for {@link LongIntHashMap}: primitive long-to-int open-addressing map
 * with backward-shift deletion. The randomized churn test mirrors the style used in
 * {@code MmsArenaAllocatorTest} to exercise collision chains under heavy mutation.
 */
class LongIntHashMapTest {

    @Test
    void putGetAndOverwrite() {
        LongIntHashMap map = new LongIntHashMap();
        map.put(42L, 100);
        assertEquals(100, map.get(42L, -1));

        map.put(42L, 200);
        assertEquals(200, map.get(42L, -1));
        assertEquals(1, map.size());
    }

    @Test
    void getReturnsDefaultForAbsentKeys() {
        LongIntHashMap map = new LongIntHashMap();
        assertEquals(-1, map.get(1L, -1));
        assertEquals(42, map.get(1L, 42));
    }

    @Test
    void zeroKeyIsFullySupported() {
        LongIntHashMap map = new LongIntHashMap();

        map.put(0L, 99);
        assertEquals(99, map.get(0L, -1));
        assertEquals(1, map.size());

        map.remove(0L);
        assertEquals(-1, map.get(0L, -1));
        assertEquals(0, map.size());

        // Removing again is a no-op.
        map.remove(0L);
        assertEquals(0, map.size());
    }

    @Test
    void negativeKeysWork() {
        LongIntHashMap map = new LongIntHashMap();

        map.put(-1L, 1);
        map.put(Long.MIN_VALUE, 2);
        map.put(-999L, 3);

        assertEquals(1, map.get(-1L, -1));
        assertEquals(2, map.get(Long.MIN_VALUE, -1));
        assertEquals(3, map.get(-999L, -1));
        assertEquals(3, map.size());

        map.remove(-1L);
        assertEquals(-1, map.get(-1L, -1));
        assertEquals(2, map.size());
    }

    @Test
    void removeIsNoOpForAbsentKey() {
        LongIntHashMap map = new LongIntHashMap();
        map.remove(999L);
        assertEquals(0, map.size());

        map.put(1L, 10);
        map.put(2L, 20);
        map.remove(999L);
        assertEquals(2, map.size());
    }

    @Test
    void growthPreservesAllEntries() {
        LongIntHashMap map = new LongIntHashMap();

        for (int i = 1; i <= 1000; i++) {
            map.put((long) i, i * 3);
        }
        assertEquals(1000, map.size());

        for (int i = 1; i <= 1000; i++) {
            assertEquals(i * 3, map.get((long) i, -1), "key " + i);
        }
    }

    @Test
    void removeIfRemovesMatchingKeysIncludingZero() {
        LongIntHashMap map = new LongIntHashMap();

        for (int i = 0; i <= 20; i++) {
            map.put((long) i, i);
        }

        map.removeIf(k -> k % 2 == 0);

        // Even keys (including 0) are gone.
        for (int i = 0; i <= 20; i++) {
            if (i % 2 == 0) {
                assertEquals(-1, map.get((long) i, -1), "key " + i + " should be removed");
            } else {
                assertEquals(i, map.get((long) i, -1), "key " + i + " should remain");
            }
        }

        assertEquals(10, map.size(), "10 odd keys remain (1,3,...,19)");
    }

    @Test
    void clearEmptiesTheMap() {
        LongIntHashMap map = new LongIntHashMap();

        for (int i = 0; i < 50; i++) {
            map.put((long) i, i * 2);
        }
        map.clear();

        assertEquals(0, map.size());
        assertTrue(map.isEmpty());
        assertEquals(-1, map.get(1L, -1));

        // Map is reusable after clear.
        map.put(1L, 42);
        assertEquals(1, map.size());
        assertEquals(42, map.get(1L, -1));
    }

    @Test
    void randomizedChurnMatchesJavaUtilHashMapReference() {
        Random rng = new Random(42);
        LongIntHashMap map = new LongIntHashMap();
        Map<Long, Integer> reference = new HashMap<>();

        for (int step = 0; step < 10_000; step++) {
            long key = rng.nextInt(129) - 64; // range -64..64, includes 0
            int value = rng.nextInt(1000);
            int roll = rng.nextInt(100);

            if (roll < 45) {
                // ~45% put
                map.put(key, value);
                reference.put(key, value);
            } else if (roll < 80) {
                // ~35% remove
                map.remove(key);
                reference.remove(key);
            } else if (roll < 95) {
                // ~15% get-check
                assertEquals(
                    reference.getOrDefault(key, -999),
                    map.get(key, -999),
                    "get mismatch at step " + step + " for key " + key
                );
            } else {
                // ~5% removeIf
                int r = rng.nextInt(4);
                map.removeIf(k -> (k & 3) == r);
                reference.keySet().removeIf(k -> (k & 3) == r);
            }
        }

        // Final consistency: sizes match and every reference entry reads back.
        assertEquals(reference.size(), map.size(), "sizes differ after churn");

        for (Map.Entry<Long, Integer> e : reference.entrySet()) {
            assertEquals(e.getValue(), map.get(e.getKey(), -999),
                "reference entry missing or wrong for key " + e.getKey());
        }

        // 200 keys outside the used range should all return default.
        for (int i = 1000; i < 1200; i++) {
            assertEquals(-999, map.get((long) i, -999), "out-of-range key " + i);
        }
    }
}