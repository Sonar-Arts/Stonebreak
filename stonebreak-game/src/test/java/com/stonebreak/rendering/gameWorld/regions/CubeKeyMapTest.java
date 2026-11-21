package com.stonebreak.rendering.gameWorld.regions;

import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.Map;
import java.util.Random;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;

/** The renderer's allocation-free cube map against a HashMap model, incl. far int64 keys and deletions. */
class CubeKeyMapTest {

    private record Key(long x, long y, long z) {}

    @Test
    void behavesLikeAHashMapUnderRandomOperations() {
        CubeKeyMap<Object> map = new CubeKeyMap<>(4);
        Map<Key, Object> model = new HashMap<>();
        Random rng = new Random(447);
        for (int step = 0; step < 200_000; step++) {
            // A dense neighbourhood (collision-prone) mixed with far int64 keys.
            long x = rng.nextBoolean() ? rng.nextInt(24) - 12 : rng.nextLong();
            long y = rng.nextInt(32) - 12;
            long z = rng.nextInt(24) - 12;
            Key k = new Key(x, y, z);
            switch (rng.nextInt(3)) {
                case 0 -> {
                    Object v = new Object();
                    assertSame(model.put(k, v), map.put(x, y, z, v));
                }
                case 1 -> assertSame(model.remove(k), map.remove(x, y, z));
                default -> assertSame(model.get(k), map.get(x, y, z));
            }
            assertEquals(model.size(), map.size());
        }
        for (Map.Entry<Key, Object> e : model.entrySet()) {
            assertSame(e.getValue(), map.get(e.getKey().x(), e.getKey().y(), e.getKey().z()));
        }
        int[] seen = {0};
        map.forEachValue(v -> seen[0]++);
        assertEquals(model.size(), seen[0]);
        map.clear();
        assertEquals(0, map.size());
        assertNull(map.get(0, 0, 0));
    }

    @Test
    void keysThatDifferInOneAxisNeverAlias() {
        CubeKeyMap<String> map = new CubeKeyMap<>();
        map.put(Long.MIN_VALUE, 0, 0, "a");
        map.put(0, Long.MIN_VALUE, 0, "b");
        map.put(0, 0, Long.MIN_VALUE, "c");
        map.put(1L << 32, 0, 0, "d");
        map.put(0, 0, 1, "e");
        assertEquals("a", map.get(Long.MIN_VALUE, 0, 0));
        assertEquals("b", map.get(0, Long.MIN_VALUE, 0));
        assertEquals("c", map.get(0, 0, Long.MIN_VALUE));
        assertEquals("d", map.get(1L << 32, 0, 0));
        assertEquals("e", map.get(0, 0, 1));
        assertNull(map.get(0, 0, 0));
    }
}
