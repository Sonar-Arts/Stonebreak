package com.openmason.engine.ui.runtime.paint;

import com.openmason.engine.cenda.FlexMeasure;
import com.openmason.engine.format.omui.UiValue;
import com.openmason.engine.ui.masonry.MPainter;
import com.openmason.engine.ui.masonry.MStyle;
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
 * <p><b>Label rule.</b> By default ({@code white-space: nowrap}) a label is one line. Its
 * intrinsic box is the advance width of its text by the font's line height (descent − ascent)
 * at {@code font-size × scale} (default {@link MStyle#FONT_BUTTON}, the size today's buttons
 * use). When the laid-out box is taller than a line, the line is centred vertically; the
 * baseline sits {@code −ascent} below the line's top. That reproduces the legacy
 * {@code y + h/2 + k·s} button-label placement as a rule instead of a per-screen constant, and
 * is what {@code align-items: baseline} aligns.
 *
 * <p><b>Wrapped and rich labels</b> ({@code ui-text}: {@code white-space: normal | pre-wrap},
 * {@code text-overflow: ellipsis}, {@code -sb-max-lines}, {@code rich: true}) go through
 * {@link TextLayout}: the intrinsic width is the widest line at the offered width (max-content
 * when unconstrained), the height is lines × line height, the block of lines is centred like
 * the single line, and the baseline is the first line's. Layouts are cached per element and
 * reused by painting, so a wrapped label is not re-broken every frame.
 *
 * <p>{@code Image} measures as its sprite's logical size × scale (a whole texture: its pixel
 * size; integer asset scales stay pixel-exact; a skin: its normal region). A {@code TextField} (#288) measures as its longest line (or placeholder) plus the
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
    /** Last text layout per label: measure and paint ask for the same width within a frame. */
    private final Map<UiElement, CachedLayout> layouts = new java.util.WeakHashMap<>();
    private Typeface lastTypeface;

    private record LayoutKey(String text, boolean rich, TextLayout.WhiteSpace ws, boolean ellipsis, int maxLines,
                             float fontPx, float width) {
    }

    private record CachedLayout(LayoutKey key, TextLayout.Result result) {
    }

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
                if (font != null && usesTextLayout(el)) {
                    TextLayout.Result r = labelLayout(el, widthMode == FlexMeasure.UNDEFINED ? Float.POSITIVE_INFINITY
                        : width, scale);
                    w = (float) Math.ceil(r.width());
                    h = lineHeight(font) * Math.max(1, r.lineCount());
                } else if (font != null) {
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
                UiImage img = host.image(el, el.prop("source") instanceof UiValue.Str s ? s.value() : null);
                UiImage.Region still = img == null ? null : img.still();
                if (still != null) {
                    w = (float) (still.sprite().layoutWidth() * scale);
                    h = (float) (still.sprite().layoutHeight() * scale);
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
        if (!field && usesTextLayout(el)) {
            int lines = Math.max(1, labelLayout(el, width, scale).lineCount());
            float block = (float) Math.ceil(line) * lines;
            return (height - block) / 2f - m.getAscent();
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

    /**
     * True when {@code el} is a {@code Label} that needs {@link TextLayout} (wrapping, ellipsis,
     * a line limit or rich text); plain one-line labels keep the legacy single-string path.
     */
    public boolean usesTextLayout(UiElement el) {
        if (!"Label".equals(el.type())) {
            return false;
        }
        var s = el.computedStyle();
        return el.prop("rich") instanceof UiValue.Bool b && b.value()
            || !"nowrap".equals(s.keyword("white-space", "nowrap"))
            || "ellipsis".equals(s.keyword("text-overflow", "clip"))
            || s.number("-sb-max-lines", 0) > 0;
    }

    /**
     * The lines of label {@code el} at {@code width} device pixels (infinite = unconstrained),
     * cached until the text, its styles, the font size or the width change.
     */
    public TextLayout.Result labelLayout(UiElement el, float width, float scale) {
        Font font = font(el, scale);
        var s = el.computedStyle();
        boolean rich = el.prop("rich") instanceof UiValue.Bool b && b.value();
        LayoutKey key = new LayoutKey(UiTexts.label(el), rich,
            TextLayout.WhiteSpace.of(s.keyword("white-space", "nowrap")),
            "ellipsis".equals(s.keyword("text-overflow", "clip")), (int) Math.max(0, s.number("-sb-max-lines", 0)),
            font == null ? 0 : font.getSize(), Float.isFinite(width) ? Math.round(width * 100f) / 100f : width);
        CachedLayout c = layouts.get(el);
        if (c != null && c.key().equals(key)) {
            return c.result();
        }
        TextLayout.Result r = font == null
            ? new TextLayout.Result(java.util.List.of(), 0, false)
            : TextLayout.layout(TextLayout.parse(key.text(), rich), key.ws(), key.width(), key.maxLines(),
                key.ellipsis(), (text, span) -> MPainter.measureWidth(font(el, scale, span.bold(), span.italic()), text));
        layouts.put(el, new CachedLayout(key, r));
        return r;
    }

    /** {@link #font(UiElement, float)} emboldened and/or slanted (rich text), or null without a typeface. */
    public Font font(UiElement el, float scale, boolean bold, boolean italic) {
        Font base = font(el, scale);
        if (base == null || !bold && !italic) {
            return base;
        }
        int key = Math.round(base.getSize() * 100f) * 4 + (bold ? 1 : 0) + (italic ? 2 : 0);
        return fonts.computeIfAbsent(-key, k -> {
            Font f = new Font(lastTypeface, base.getSize());
            f.setEmboldened(bold);
            if (italic) {
                f.setSkewX(-0.2f);
            }
            return f;
        });
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
            layouts.clear();
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
            layouts.clear();
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
        layouts.clear();
        lastTypeface = null;
    }
}
