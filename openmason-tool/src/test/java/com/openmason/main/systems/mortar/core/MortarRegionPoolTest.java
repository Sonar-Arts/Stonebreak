package com.openmason.main.systems.mortar.core;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertSame;

/** Row-indexed region reuse: stable per index, released past the live count. No region paints, so no Skija context. */
class MortarRegionPoolTest {

    @Test
    void sameIndexReturnsTheSameRegionAcrossFrames() {
        try (MortarRegionPool pool = new MortarRegionPool()) {
            MortarRegion row2 = pool.get(2);
            assertSame(row2, pool.get(2));
            assertNotSame(pool.get(0), pool.get(1));
            assertNotSame(pool.get(1), row2);
        }
    }

    @Test
    void trimReleasesTrailingRegionsOnly() {
        try (MortarRegionPool pool = new MortarRegionPool()) {
            MortarRegion row0 = pool.get(0);
            MortarRegion row1 = pool.get(1);
            MortarRegion row2 = pool.get(2);

            pool.trim(2);
            assertSame(row0, pool.get(0));
            assertSame(row1, pool.get(1));
            assertNotSame(row2, pool.get(2), "a trimmed row gets a fresh region when it reappears");
        }
    }

    @Test
    void trimBeyondSizeIsANoOp() {
        try (MortarRegionPool pool = new MortarRegionPool()) {
            MortarRegion row0 = pool.get(0);
            pool.trim(10);
            assertSame(row0, pool.get(0));
        }
    }

    @Test
    void closeReleasesEverythingAndIsRepeatable() {
        MortarRegionPool pool = new MortarRegionPool();
        MortarRegion row0 = pool.get(0);
        pool.close();
        assertNotSame(row0, pool.get(0));
        pool.close();
        pool.close();
    }
}
