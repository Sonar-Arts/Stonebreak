package com.openmason.engine.ui.runtime.paint;

import com.openmason.engine.cenda.FlexMeasure;
import com.openmason.engine.format.omui.UiValue;
import com.openmason.engine.ui.masonry.MPainter;
import com.openmason.engine.ui.masonry.MStyle;
import com.openmason.engine.ui.masonry.textures.MTexture;
import com.openmason.engine.ui.runtime.ContentMeasurer;
import com.openmason.engine.ui.runtime.TextLineMetrics;
import com.openmason.engine.ui.runtime.UiElement;
import com.openmason.engine.ui.runtime.UiTexts;
import com.openmason.engine.ui.text.TextBoundaries;
import com.openmason.engine.ui.text.TextMeasure;
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
 * pixel-exact). A {@code TextField} (#288) measures as its longest line (or placeholder) plus the
 * legacy {@code MTextField} insets, at {@link MStyle#FONT_ITEM} by default. Other types measure
 * 0×0. Text is the localized text ({@link UiTexts}); font sizes include the player's text
 * scale ({@code UiPreferences.textScale}).
 */
public final class MasonryContentMeasurer implements ContentMeasurer, AutoCloseable {

    public static final float DEFAULT_FONT_SIZE = MStyle.FONT_BUTTON;
    /** Default text field size, the legacy {@code MTextField}'s. */
    public static final float TEXT_FIELD_FONT_SIZE = MStyle.FONT_ITEM;
    private static final float TEXT_FIELD_PAD_X = 10f;
    private static final float TEXT_FIELD_PAD_Y = 6f;

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
                    w = MPainter.measureWidth(font, UiTexts.label(el));
                    h = lineHeight(font);
                }
            }
            case "TextField" -> {
                Font font = font(el, scale);
                if (font != null) {
                    String value = el.text("text");
                    String shown = value.isEmpty() ? UiTexts.placeholder(el) : value;
                    String[] lines = shown.split("\n", -1);
                    for (String line : lines) {
                        w = Math.max(w, MPainter.measureWidth(font, line));
                    }
                    w += 2 * TEXT_FIELD_PAD_X * scale;
                    h = lineHeight(font) * Math.max(1, lines.length) + 2 * TEXT_FIELD_PAD_Y * scale;
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
        boolean field = "TextField".equals(el.type());
        Font font = "Label".equals(el.type()) || field ? font(el, scale) : null;
        if (font == null) {
            return height;
        }
        FontMetrics m = font.getMetrics();
        float line = m.getDescent() - m.getAscent();
        if (field && el.prop("multiline") instanceof UiValue.Bool b && b.value()) {
            return TEXT_FIELD_PAD_Y * scale - m.getAscent();
        }
        return (height - line) / 2f - m.getAscent();
    }

    /** Real glyph advances of the element's font, by grapheme cluster (#288). */
    @Override
    public TextLineMetrics textLine(UiElement el, float scale) {
        Font font = font(el, scale);
        if (font == null) {
            return ContentMeasurer.super.textLine(el, scale);
        }
        FontMetrics m = font.getMetrics();
        return new TextLineMetrics(new FontMeasure(font), (float) Math.ceil(m.getDescent() - m.getAscent()),
            -m.getAscent());
    }

    private record FontMeasure(Font font) implements TextMeasure {
        @Override
        public float advance(String line, int index) {
            int i = TextBoundaries.snap(line, index);
            return i <= 0 ? 0 : MPainter.measureWidth(font, line.substring(0, i));
        }

        @Override
        public int indexAt(String line, float x) {
            int at = 0;
            float left = 0;
            while (at < line.length()) {
                int next = TextBoundaries.next(line, at);
                float right = MPainter.measureWidth(font, line.substring(0, next));
                if (x < (left + right) / 2f) {
                    return at;
                }
                at = next;
                left = right;
            }
            return line.length();
        }
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
        float fallback = "TextField".equals(el.type()) ? TEXT_FIELD_FONT_SIZE : DEFAULT_FONT_SIZE;
        float logical = (float) el.computedStyle().number("font-size", fallback);
        float textScale = el.owner().preferences().textScale();
        float px = Math.round(Math.max(1f, logical * scale * textScale) * 2f) / 2f; // half-pixel grid, like MFonts
        return fonts.computeIfAbsent(Math.round(px * 100f), k -> new Font(tf, px));
    }

    /** A font of {@code logical} px at {@code scale} (canvas text, #292), or null without a typeface. */
    public Font fontAt(float logical, float scale) {
        Typeface tf = typeface.get();
        if (tf == null) {
            return null;
        }
        if (tf != lastTypeface) {
            fonts.values().forEach(Font::close);
            fonts.clear();
            lastTypeface = tf;
        }
        float px = Math.round(Math.max(1f, logical * scale) * 2f) / 2f;
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
