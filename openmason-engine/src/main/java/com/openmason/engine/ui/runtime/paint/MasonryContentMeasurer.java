package com.openmason.engine.ui.runtime.paint;

import com.openmason.engine.cenda.FlexMeasure;
import com.openmason.engine.format.omui.UiValue;
import com.openmason.engine.ui.masonry.MPainter;
import com.openmason.engine.ui.masonry.MStyle;
import com.openmason.engine.ui.masonry.textures.MTexture;
import com.openmason.engine.ui.runtime.ContentMeasurer;
import com.openmason.engine.ui.runtime.UiElement;
import io.github.humbleui.skija.Font;
import io.github.humbleui.skija.FontMetrics;
import io.github.humbleui.skija.Typeface;

import java.util.HashMap;
import java.util.Map;
import java.util.function.Supplier;

/**
 * Intrinsic sizes and baselines from the Masonry font and textures, shared by layout and the
 * {@link UiPainter} so text is measured and placed by exactly the same metrics (#287).
 *
 * <p><b>Label rule.</b> A label is one line. Its intrinsic box is the advance width of its text
 * by the font's line height (descent − ascent) at {@code font-size × scale} (default
 * {@link MStyle#FONT_BUTTON}, the size today's buttons use). When the laid-out box is taller
 * than a line, the line is centred vertically; the baseline sits {@code −ascent} below the
 * line's top. That reproduces the legacy {@code y + h/2 + k·s} button-label placement as a
 * rule instead of a per-screen constant, and is what {@code align-items: baseline} aligns.
 *
 * <p>{@code Image} measures as its texture's pixel size × scale (integer asset scales stay
 * pixel-exact). Other types measure 0×0.
 */
public final class MasonryContentMeasurer implements ContentMeasurer, AutoCloseable {

    public static final float DEFAULT_FONT_SIZE = MStyle.FONT_BUTTON;

    private final Supplier<Typeface> typeface;
    private final UiPaintHost host;
    private final Map<Integer, Font> fonts = new HashMap<>();
    private Typeface lastTypeface;

    /** @param typeface the backend's typeface (may change at runtime; fonts rebuild then) */
    public MasonryContentMeasurer(Supplier<Typeface> typeface, UiPaintHost host) {
        this.typeface = typeface;
        this.host = host == null ? UiPaintHost.NONE : host;
    }

    @Override
    public void measure(UiElement el, float width, int widthMode, float height, int heightMode, float scale,
                        float[] out) {
        float w = 0;
        float h = 0;
        switch (el.type()) {
            case "Label" -> {
                Font font = font(el, scale);
                if (font != null) {
                    w = MPainter.measureWidth(font, el.text("text"));
                    h = lineHeight(font);
                }
            }
            case "Image" -> {
                MTexture t = host.texture(el.prop("source") instanceof UiValue.Str s ? s.value() : null);
                if (t != null) {
                    w = t.width() * scale;
                    h = t.height() * scale;
                }
            }
            default -> {
            }
        }
        out[0] = constrain(w, width, widthMode);
        out[1] = constrain(h, height, heightMode);
    }

    @Override
    public float baseline(UiElement el, float width, float height, float scale) {
        Font font = "Label".equals(el.type()) ? font(el, scale) : null;
        if (font == null) {
            return height;
        }
        FontMetrics m = font.getMetrics();
        float line = m.getDescent() - m.getAscent();
        return (height - line) / 2f - m.getAscent();
    }

    /** The font a label measures and paints with, or null without a typeface. */
    public Font font(UiElement el, float scale) {
        Typeface tf = typeface.get();
        if (tf == null) {
            return null;
        }
        if (tf != lastTypeface) {
            fonts.values().forEach(Font::close);
            fonts.clear();
            lastTypeface = tf;
        }
        float logical = (float) el.computedStyle().number("font-size", DEFAULT_FONT_SIZE);
        float px = Math.round(Math.max(1f, logical * scale) * 2f) / 2f; // half-pixel grid, like MFonts
        return fonts.computeIfAbsent(Math.round(px * 100f), k -> new Font(tf, px));
    }

    private static float lineHeight(Font font) {
        FontMetrics m = font.getMetrics();
        return (float) Math.ceil(m.getDescent() - m.getAscent());
    }

    private static float constrain(float v, float limit, int mode) {
        return switch (mode) {
            case FlexMeasure.EXACTLY -> limit;
            case FlexMeasure.AT_MOST -> Math.min(v, limit);
            default -> v;
        };
    }

    @Override
    public void close() {
        fonts.values().forEach(Font::close);
        fonts.clear();
        lastTypeface = null;
    }
}
