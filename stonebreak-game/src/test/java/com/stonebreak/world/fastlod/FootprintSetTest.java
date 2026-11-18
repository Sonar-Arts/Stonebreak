package com.stonebreak.world.fastlod;

import org.junit.jupiter.api.Test;

import java.util.HashSet;
import java.util.List;
import java.util.Random;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class FootprintSetTest {

    private record F(int level, long x, long z) {
    }

    @Test
    void matchesAHashSetUnderRandomAddsAndRemoves() {
        FootprintSet set = new FootprintSet();
        Set<F> ref = new HashSet<>();
        Random r = new Random(7);
        for (int i = 0; i < 20_000; i++) {
            F f = new F(r.nextInt(4), r.nextInt(40) - 20, r.nextInt(40) - 20);
            if (r.nextInt(3) == 0) {
                assertEquals(ref.remove(f), set.remove(f.level(), f.x(), f.z()));
            } else {
                assertEquals(ref.add(f), set.add(f.level(), f.x(), f.z()));
            }
            assertEquals(ref.size(), set.size());
        }
        for (int level = 0; level < 4; level++) {
            for (long x = -21; x <= 21; x++) {
                for (long z = -21; z <= 21; z++) {
                    assertEquals(ref.contains(new F(level, x, z)), set.contains(level, x, z));
                }
            }
        }
    }

    @Test
    void coversBlockColumnsAtEveryLevelIncludingNegativeAndFarCoordinates() {
        FootprintSet set = new FootprintSet();
        set.add(0, -1, 0);           // blocks x [-32, 0), z [0, 32)
        set.add(3, 2, -1);           // 256-block footprint: x [512, 768), z [-256, 0)
        long far = 1L << 40;
        set.add(1, far >> 6, -(far >> 6)); // x [2^40, 2^40 + 64)
        assertTrue(set.covers(-32, 0));
        assertTrue(set.covers(-1, 31));
        assertFalse(set.covers(0, 0));
        assertFalse(set.covers(-33, 0));
        assertTrue(set.covers(512, -256));
        assertTrue(set.covers(767, -1));
        assertFalse(set.covers(768, -1));
        assertTrue(set.covers(far + 63, -far));
        assertFalse(set.covers(far + 64, -far));
        set.remove(3, 2, -1);
        assertFalse(set.covers(600, -10));
        for (F f : List.of(new F(0, -1, 0), new F(1, far >> 6, -(far >> 6)))) {
            assertTrue(set.remove(f.level(), f.x(), f.z()));
        }
        assertEquals(0, set.size());
        assertFalse(set.covers(-1, 31));
    }
}
