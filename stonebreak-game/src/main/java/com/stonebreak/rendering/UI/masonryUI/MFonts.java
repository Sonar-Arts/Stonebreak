package com.stonebreak.rendering.UI.masonryUI;

import com.stonebreak.rendering.UI.backend.skija.SkijaUIBackend;
import io.github.humbleui.skija.Font;
import io.github.humbleui.skija.Typeface;

import java.util.HashMap;
import java.util.Map;

/**
 * Lazy, per-size {@link Font} cache keyed on the exact pixel size so every
 * widget that asks for "button-size text" gets the same Skija Font object.
 *
 * Fetches the typeface from the backend on demand rather than at
 * construction — guards against startup ordering where a MasonryUI is built
 * before the backend has loaded assets, and against the typeface being swapped
 * later (e.g. a hypothetical theme reload).
 */
public final class MFonts {

    private final SkijaUIBackend backend;
    private final Map<Integer, Font> cache = new HashMap<>();
    private Typeface lastTypeface;

    public MFonts(SkijaUIBackend backend) {
        this.backend = backend;
    }

    public Font get(float size) {
        Typeface typeface = backend != null ? backend.getMinecraftTypeface() : null;
        if (typeface == null) return null;
        if (typeface != lastTypeface) {
            // Typeface swapped — drop stale Fonts so we rebuild against the new face.
            cache.values().forEach(Font::close);
            cache.clear();
            lastTypeface = typeface;
        }
        int key = Math.round(size * 100f);
        Font font = cache.get(key);
        if (font == null) {
            font = new Font(typeface, size);
            cache.put(key, font);
        }
        return font;
    }

    public void dispose() {
        cache.values().forEach(Font::close);
        cache.clear();
        lastTypeface = null;
    }
}
