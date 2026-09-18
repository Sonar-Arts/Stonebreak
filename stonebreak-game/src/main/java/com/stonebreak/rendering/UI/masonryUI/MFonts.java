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

    /**
     * Returns a font at {@code baseSize} multiplied by the current UI scale.
     * Use this for any text that lives inside scalable UI so labels grow and
     * shrink in step with the surrounding box geometry. The per-size cache means
     * each distinct scaled size is still built only once.
     */
    public Font getScaled(float baseSize) {
        return get(baseSize * com.stonebreak.config.Settings.getInstance().getUiScale());
    }

    /**
     * {@code baseSize × scale}, for UI that lays itself out at its own scale (a HUD that scales with
     * the window, a widget capped to fit) rather than at the global UI scale.
     */
    public Font get(float baseSize, float scale) {
        return get(Math.max(MIN_SIZE, baseSize * scale));
    }

    /**
     * The largest size at or below {@code size} at which {@code text} fits {@code maxWidth}, never
     * below {@code minFraction} of {@code size}. Shrinks in whole pixels.
     */
    public Font fit(String text, float size, float maxWidth, float minFraction) {
        float current = Math.max(MIN_SIZE, size);
        float floor = Math.max(MIN_SIZE, current * Math.max(0f, Math.min(1f, minFraction)));
        Font font = get(current);
        while (font != null && current > floor && MPainter.measureWidth(font, text) > maxWidth) {
            current = Math.max(floor, current - 1f);
            font = get(current);
        }
        return font;
    }

    /** {@code size}, reduced if a row only {@code rowHeight} tall could not hold it comfortably. */
    public Font forHeight(float size, float rowHeight) {
        return get(Math.max(MIN_SIZE, Math.min(size, rowHeight * 0.62f)));
    }

    private static final float MIN_SIZE = 6f;

    public Font get(float size) {
        // Half-pixel grid: the cache never evicts, and animated or window-derived sizes would
        // otherwise mint a new native Font for nearly every distinct float.
        size = Math.round(Math.max(MIN_SIZE, size) * 2f) / 2f;
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
