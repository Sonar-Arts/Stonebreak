package com.openmason.main.systems.skija;

import io.github.humbleui.skija.Data;
import io.github.humbleui.skija.Font;
import io.github.humbleui.skija.FontMgr;
import io.github.humbleui.skija.Typeface;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.InputStream;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Lazily loads and caches Skia typefaces for the JetBrains Mono family used
 * throughout Open Mason, so Skija-painted widgets match the ImGui font.
 */
public final class SkijaFontStore {

    private static final Logger logger = LoggerFactory.getLogger(SkijaFontStore.class);

    public enum Weight {
        REGULAR("/masonFonts/JetBrainsMono-Regular.ttf"),
        MEDIUM("/masonFonts/JetBrainsMono-Medium.ttf"),
        BOLD("/masonFonts/JetBrainsMono-Bold.ttf");

        final String resourcePath;

        Weight(String resourcePath) {
            this.resourcePath = resourcePath;
        }
    }

    private static final Map<Weight, Typeface> TYPEFACES = new ConcurrentHashMap<>();

    private SkijaFontStore() {
    }

    /**
     * Cached typeface for the given weight, or null if the resource failed to
     * load (callers should fall back to Skia's default typeface).
     */
    public static Typeface typeface(Weight weight) {
        return TYPEFACES.computeIfAbsent(weight, SkijaFontStore::load);
    }

    /** Convenience: a new {@link Font} at the given size. Caller closes it. */
    public static Font font(Weight weight, float size) {
        return new Font(typeface(weight), size);
    }

    private static Typeface load(Weight weight) {
        try (InputStream in = SkijaFontStore.class.getResourceAsStream(weight.resourcePath)) {
            if (in == null) {
                logger.error("Font resource not found: {}", weight.resourcePath);
                return null;
            }
            byte[] bytes = in.readAllBytes();
            try (Data data = Data.makeFromBytes(bytes)) {
                return FontMgr.getDefault().makeFromData(data);
            }
        } catch (IOException e) {
            logger.error("Failed to load font {}", weight.resourcePath, e);
            return null;
        }
    }
}
