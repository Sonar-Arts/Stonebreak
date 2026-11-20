package com.stonebreak.world.fastlod;

import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.Map;
import java.util.Random;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The boxing-free column map behind FastLOD's ring scan and the octree's snap window, against a HashMap. */
class LongKeyMapTest {

    @Test
    void behavesLikeAHashMapUnderRandomPutsGetsAndRemoves() {
        Random r = new Random(7);
        LongKeyMap<String> m = new LongKeyMap<>(4);
        Map<Long, String> ref = new HashMap<>();
        for (int op = 0; op < 200_000; op++) {
            // A small key space (lots of collisions, removals inside probe runs) with packed negative columns too.
            long key = ((long) (r.nextInt(64) - 32) << 32) | ((r.nextInt(64) - 32) & 0xFFFFFFFFL);
            switch (r.nextInt(4)) {
                case 0, 1 -> {
                    String v = "v" + r.nextInt(5);
                    assertEquals(ref.put(key, v), m.put(key, v));
                }
                case 2 -> {
                    String expected = r.nextBoolean() ? ref.get(key) : "v" + r.nextInt(5);
                    boolean refRemoved = expected != null && expected.equals(ref.get(key)) && ref.remove(key) != null;
                    assertEquals(refRemoved, m.remove(key, expected == null ? "none" : expected));
                }
                default -> assertEquals(ref.get(key), m.get(key));
            }
            assertEquals(ref.size(), m.size());
        }
        for (Map.Entry<Long, String> e : ref.entrySet()) {
            assertEquals(e.getValue(), m.get(e.getKey()));
        }
    }

    @Test
    void removeValuesIfAndAnyKey() {
        LongKeyMap<Long> m = new LongKeyMap<>(2);
        for (long k = 0; k < 100; k++) {
            m.put(k * 31, k);
        }
        assertTrue(m.anyKey(k -> k == 31 * 50));
        assertFalse(m.anyKey(k -> k == 7));
        m.removeValuesIf(v -> v % 2 == 0);
        assertEquals(50, m.size());
        for (long k = 0; k < 100; k++) {
            if (k % 2 == 0) assertNull(m.get(k * 31));
            else assertEquals(k, m.get(k * 31));
        }
        m.clear();
        assertTrue(m.isEmpty());
        assertNull(m.get(31));
    }
}
