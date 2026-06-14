package com.openmason.main.systems.mortar.core;

import com.openmason.main.systems.skija.SkijaFontStore;
import com.openmason.main.systems.skija.SkijaFontStore.Weight;
import io.github.humbleui.skija.Font;

import java.util.HashMap;
import java.util.Map;

/**
 * Caches long-lived Skija {@link Font} objects keyed by (weight, size).
 * {@link SkijaFontStore} already caches typefaces, but creates a fresh
 * {@code Font} per call; constructing one every frame inside paint would leak
 * native handles until GC. MortarUI paints through this cache so each
 * (weight, size) is allocated once and closed on {@link #close()}.
 *
 * <p>Owned by a {@link MortarRegion} (or shared); single-threaded UI use.</p>
 */
public final class MortarFonts implements AutoCloseable {

    private final Map<Long, Font> cache = new HashMap<>();

    /** A cached font for the given weight at the given size (rounded to 0.5px). */
    public Font get(Weight weight, float size) {
        float rounded = Math.round(size * 2.0f) / 2.0f;
        long key = (((long) weight.ordinal()) << 32) | (Float.floatToIntBits(rounded) & 0xFFFFFFFFL);
        return cache.computeIfAbsent(key, k -> SkijaFontStore.font(weight, rounded));
    }

    @Override
    public void close() {
        for (Font font : cache.values()) {
            if (font != null) {
                font.close();
            }
        }
        cache.clear();
    }
}
