package com.openmason.main.systems.mortar.paint;

import com.openmason.main.systems.mortar.core.MortarFonts;
import com.openmason.main.systems.mortar.theme.MortarTheme;
import com.openmason.main.systems.skija.SkijaFontStore.Weight;
import io.github.humbleui.skija.Canvas;
import io.github.humbleui.skija.Font;
import io.github.humbleui.skija.MaskFilter;
import io.github.humbleui.skija.Paint;
import io.github.humbleui.skija.PaintMode;
import io.github.humbleui.skija.Shader;
import io.github.humbleui.types.RRect;
import io.github.humbleui.types.Rect;

import java.util.ArrayList;
import java.util.List;

/**
 * Reusable Skija drawing primitives for one MortarUI paint pass, bound to a
 * {@link Canvas}, the frame's {@link MortarTheme} tokens, and a shared
 * {@link MortarFonts} cache. Pure painting — no ImGui calls — so parts built on
 * it can be reasoned about (and the geometry helpers unit-tested) without a UI.
 *
 * <p>All colors are Skija ARGB ints. Text is drawn in the JetBrains Mono family
 * that matches the ImGui font.</p>
 */
public final class MortarPainter {

    /** Horizontal text anchoring for {@link #text}. */
    public enum Align {
        LEFT, CENTER, RIGHT
    }

    private final Canvas canvas;
    private final MortarTheme theme;
    private final MortarFonts fonts;

    public MortarPainter(Canvas canvas, MortarTheme theme, MortarFonts fonts) {
        this.canvas = canvas;
        this.theme = theme;
        this.fonts = fonts;
    }

    public Canvas canvas() {
        return canvas;
    }

    public MortarTheme theme() {
        return theme;
    }

    // ---- rectangles ------------------------------------------------------

    public void fillRoundRect(float x, float y, float w, float h, float radius, int argb) {
        try (Paint p = new Paint()) {
            p.setAntiAlias(true);
            p.setColor(argb);
            canvas.drawRRect(RRect.makeXYWH(x, y, w, h, radius), p);
        }
    }

    public void strokeRoundRect(float x, float y, float w, float h, float radius,
                                float strokeWidth, int argb) {
        float inset = strokeWidth / 2f;
        try (Paint p = new Paint()) {
            p.setAntiAlias(true);
            p.setMode(PaintMode.STROKE);
            p.setStrokeWidth(strokeWidth);
            p.setColor(argb);
            canvas.drawRRect(
                    RRect.makeXYWH(x + inset, y + inset, w - strokeWidth, h - strokeWidth, radius), p);
        }
    }

    public void fillRect(float x, float y, float w, float h, int argb) {
        try (Paint p = new Paint()) {
            p.setAntiAlias(true);
            p.setColor(argb);
            canvas.drawRect(Rect.makeXYWH(x, y, w, h), p);
        }
    }

    /**
     * A soft drop shadow under a rounded rect: a blurred fill offset downward.
     * {@code blurSigma} controls softness; keep it subtle (a few px).
     */
    public void dropShadow(float x, float y, float w, float h, float radius,
                           float dy, float blurSigma, int argb) {
        if (blurSigma <= 0f) {
            return;
        }
        try (Paint p = new Paint();
             MaskFilter blur = MaskFilter.makeBlur(io.github.humbleui.skija.FilterBlurMode.NORMAL, blurSigma)) {
            p.setAntiAlias(true);
            p.setColor(argb);
            p.setMaskFilter(blur);
            canvas.drawRRect(RRect.makeXYWH(x, y + dy, w, h, radius), p);
        }
    }

    /** Vertical or horizontal linear gradient filling a rounded rect. */
    public void gradientRoundRect(float x, float y, float w, float h, float radius,
                                  int[] argbStops, boolean vertical) {
        float x2 = vertical ? x : x + w;
        float y2 = vertical ? y + h : y;
        try (Shader shader = Shader.makeLinearGradient(x, y, x2, y2, argbStops);
             Paint p = new Paint()) {
            p.setAntiAlias(true);
            p.setShader(shader);
            canvas.drawRRect(RRect.makeXYWH(x, y, w, h, radius), p);
        }
    }

    // ---- text ------------------------------------------------------------

    public float measureWidth(String s, Weight weight, float size) {
        if (s == null || s.isEmpty()) {
            return 0f;
        }
        return fonts.get(weight, size).measureTextWidth(s);
    }

    /**
     * Draw text horizontally anchored per {@code align} at {@code x}, vertically
     * centered on {@code cy}.
     */
    public void text(String s, float x, float cy, Align align, Weight weight, float size, int argb) {
        if (s == null || s.isEmpty()) {
            return;
        }
        Font font = fonts.get(weight, size);
        float width = font.measureTextWidth(s);
        float drawX = switch (align) {
            case LEFT -> x;
            case CENTER -> x - width / 2f;
            case RIGHT -> x - width;
        };
        float baseline = cy - (font.getMetrics()._ascent + font.getMetrics()._descent) / 2f;
        try (Paint p = new Paint()) {
            p.setAntiAlias(true);
            p.setColor(argb);
            canvas.drawString(s, drawX, baseline, font, p);
        }
    }

    /**
     * Draw text truncated with an ellipsis to fit {@code maxWidth}. Left-anchored
     * at {@code x}, vertically centered on {@code cy}.
     */
    public void textEllipsized(String s, float x, float cy, float maxWidth,
                               Weight weight, float size, int argb) {
        if (s == null || s.isEmpty()) {
            return;
        }
        Font font = fonts.get(weight, size);
        String fitted = ellipsize(s, font, maxWidth);
        float baseline = cy - (font.getMetrics()._ascent + font.getMetrics()._descent) / 2f;
        try (Paint p = new Paint()) {
            p.setAntiAlias(true);
            p.setColor(argb);
            canvas.drawString(fitted, x, baseline, font, p);
        }
    }

    /**
     * Word-wrap {@code s} into up to {@code maxLines} lines within
     * {@code maxWidth}, left-anchored at {@code x} starting at top {@code topY}.
     * No ellipsis — overflow beyond {@code maxLines} is simply not drawn (the
     * card shows a "simple" view; the full text lives in the preview). Returns
     * the height consumed so the caller can stack content below it.
     */
    public float textWrapped(String s, float x, float topY, float maxWidth,
                             Weight weight, float size, int argb, int maxLines) {
        if (s == null || s.isEmpty() || maxLines <= 0) {
            return 0f;
        }
        Font font = fonts.get(weight, size);
        float lineH = size * 1.35f;
        List<String> lines = wrap(s, font, maxWidth, maxLines);

        float verticalCenterOffset = (font.getMetrics()._ascent + font.getMetrics()._descent) / 2f;
        try (Paint p = new Paint()) {
            p.setAntiAlias(true);
            p.setColor(argb);
            for (int i = 0; i < lines.size(); i++) {
                float cy = topY + i * lineH + lineH / 2f;
                canvas.drawString(lines.get(i), x, cy - verticalCenterOffset, font, p);
            }
        }
        return lines.size() * lineH;
    }

    private static List<String> wrap(String s, Font font, float maxWidth, int maxLines) {
        List<String> lines = new ArrayList<>();
        StringBuilder current = new StringBuilder();
        for (String word : s.trim().split("\\s+")) {
            String trial = current.length() == 0 ? word : current + " " + word;
            if (current.length() == 0 || font.measureTextWidth(trial) <= maxWidth) {
                current.setLength(0);
                current.append(trial);
            } else {
                lines.add(current.toString());
                if (lines.size() >= maxLines) {
                    return lines;
                }
                current.setLength(0);
                current.append(word);
            }
        }
        if (current.length() > 0 && lines.size() < maxLines) {
            lines.add(current.toString());
        }
        return lines;
    }

    // ---- clipping --------------------------------------------------------

    /** Clip subsequent drawing to a rounded rect until {@link #popClip()}. */
    public void pushClip(float x, float y, float w, float h, float radius) {
        canvas.save();
        canvas.clipRRect(RRect.makeXYWH(x, y, w, h, radius), true);
    }

    public void popClip() {
        canvas.restore();
    }

    private static String ellipsize(String s, Font font, float maxWidth) {
        if (font.measureTextWidth(s) <= maxWidth) {
            return s;
        }
        String ellipsis = "...";
        float ellipsisW = font.measureTextWidth(ellipsis);
        if (ellipsisW > maxWidth) {
            return "";
        }
        int lo = 0;
        int hi = s.length();
        while (lo < hi) {
            int mid = (lo + hi + 1) >>> 1;
            float w = font.measureTextWidth(s.substring(0, mid)) + ellipsisW;
            if (w <= maxWidth) {
                lo = mid;
            } else {
                hi = mid - 1;
            }
        }
        return s.substring(0, lo) + ellipsis;
    }
}
