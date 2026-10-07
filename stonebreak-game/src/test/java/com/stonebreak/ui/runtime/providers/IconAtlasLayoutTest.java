package com.stonebreak.ui.runtime.providers;

import org.junit.jupiter.api.Test;

import java.util.HashSet;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class IconAtlasLayoutTest {

    @Test
    void cellsPackRowMajorAndNeverOverlap() {
        IconAtlasLayout<String> layout = new IconAtlasLayout<>(100);
        assertEquals(9, layout.cellsPerPage(32)); // 3 x 3, the 4-pixel remainder unused
        Set<String> rects = new HashSet<>();
        for (int i = 0; i < 9; i++) {
            IconAtlasLayout.Cell c = layout.allocate("b" + i, 32);
            assertEquals(0, c.page());
            assertEquals((i % 3) * 32, c.x());
            assertEquals((i / 3) * 32, c.y());
            assertTrue(c.x() + 32 <= 100 && c.y() + 32 <= 100);
            assertTrue(rects.add(c.x() + "," + c.y()), "cells overlap");
        }
        assertEquals(1, layout.pages(32));
    }

    @Test
    void aFullPageRollsOverToANewOne() {
        IconAtlasLayout<Integer> layout = new IconAtlasLayout<>(64);
        for (int i = 0; i < 4; i++) {
            layout.allocate(i, 32);
        }
        IconAtlasLayout.Cell fifth = layout.allocate(4, 32);
        assertEquals(1, fifth.page());
        assertEquals(0, fifth.x());
        assertEquals(0, fifth.y());
        assertEquals(2, layout.pages(32));
    }

    @Test
    void sameIconSameSizeIsOneCellAndSizesAreSeparate() {
        IconAtlasLayout<String> layout = new IconAtlasLayout<>(256);
        IconAtlasLayout.Cell a = layout.allocate("dirt", 48);
        assertSame(a, layout.allocate("dirt", 48));
        assertSame(a, layout.find("dirt", 48));
        assertNull(layout.find("dirt", 64), "a UI-scale change renders a new size, never resamples");
        IconAtlasLayout.Cell big = layout.allocate("dirt", 64);
        assertEquals(64, big.size());
        assertEquals(0, big.page());
        assertEquals(2, layout.cellCount());
    }

    @Test
    void clearForgetsEverything() {
        IconAtlasLayout<String> layout = new IconAtlasLayout<>(128);
        layout.allocate("a", 16);
        layout.clear();
        assertNull(layout.find("a", 16));
        assertEquals(0, layout.pages(16));
        assertEquals(0, layout.allocate("b", 16).x());
    }

    @Test
    void sizesOutsideAPageAreRejected() {
        IconAtlasLayout<String> layout = new IconAtlasLayout<>(64);
        assertThrows(IllegalArgumentException.class, () -> layout.allocate("a", 0));
        assertThrows(IllegalArgumentException.class, () -> layout.allocate("a", 65));
    }
}
