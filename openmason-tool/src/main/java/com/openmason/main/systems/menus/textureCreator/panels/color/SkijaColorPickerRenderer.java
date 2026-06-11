package com.openmason.main.systems.menus.textureCreator.panels.color;

import com.openmason.main.systems.menus.textureCreator.color.ColorUtils;
import com.openmason.main.systems.skija.SkijaContext;
import com.openmason.main.systems.skija.SkijaImGuiPanel;
import io.github.humbleui.skija.Canvas;
import io.github.humbleui.skija.Paint;
import io.github.humbleui.skija.PaintMode;
import io.github.humbleui.skija.Shader;
import io.github.humbleui.types.RRect;
import io.github.humbleui.types.Rect;

/**
 * Skija-painted color picker widgets: antialiased saturation/value square,
 * vertical hue bar, and horizontal alpha bar, each composited into ImGui as
 * an image item at caller-supplied sizes (the panel is responsive — widgets
 * stretch to the dock node's width). Painting only — input is handled by
 * {@link ColorPickerInteraction} against the submitted items.
 *
 * Skia colors are ARGB (0xAARRGGBB); the editor's packed colors are RGBA
 * (0xAABBGGRR) — conversions stay inside this class.
 */
public final class SkijaColorPickerRenderer implements AutoCloseable {

    public static final float HUE_WIDTH = 18f;

    private static final float CORNER_RADIUS = 4f;
    private static final int BORDER_COLOR = 0xFF666666;

    // ARGB rainbow stops matching the HSV hue circle
    private static final int[] HUE_STOPS = {
            0xFFFF0000, 0xFFFFFF00, 0xFF00FF00,
            0xFF00FFFF, 0xFF0000FF, 0xFFFF00FF, 0xFFFF0000
    };

    private SkijaImGuiPanel svPanel;
    private SkijaImGuiPanel huePanel;
    private SkijaImGuiPanel alphaPanel;

    /** True when a Skija context exists and panels can be created. */
    public boolean isAvailable() {
        return SkijaContext.getInstance() != null;
    }

    /** Submit the SV square as an ImGui image item at the given size. */
    public void drawSVSquare(ColorSelectionState state, float width, float height) {
        ensurePanels();
        svPanel.draw(width, height, canvas -> paintSVSquare(canvas, state, width, height));
    }

    /** Submit the hue bar ({@link #HUE_WIDTH} wide) at the given height. */
    public void drawHueBar(ColorSelectionState state, float height) {
        ensurePanels();
        huePanel.draw(HUE_WIDTH, height, canvas -> paintHueBar(canvas, state, height));
    }

    /** Submit the horizontal alpha bar at the given size. */
    public void drawAlphaBar(ColorSelectionState state, float width, float height) {
        ensurePanels();
        alphaPanel.draw(width, height, canvas -> paintAlphaBar(canvas, state, width, height));
    }

    private void ensurePanels() {
        if (svPanel == null) {
            SkijaContext context = SkijaContext.getInstance();
            if (context == null) {
                throw new IllegalStateException("Skija context not initialized");
            }
            svPanel = new SkijaImGuiPanel(context);
            huePanel = new SkijaImGuiPanel(context);
            alphaPanel = new SkijaImGuiPanel(context);
        }
    }

    private void paintSVSquare(Canvas canvas, ColorSelectionState state, float w, float h) {
        RRect rrect = RRect.makeLTRB(0, 0, w, h, CORNER_RADIUS);

        // Base: pure hue
        int[] rgb = ColorUtils.hsvToRgb(state.getHue(), 1.0f, 1.0f);
        int pureHueArgb = 0xFF000000 | (rgb[0] << 16) | (rgb[1] << 8) | rgb[2];
        try (Paint base = new Paint()) {
            base.setAntiAlias(true);
            base.setColor(pureHueArgb);
            canvas.drawRRect(rrect, base);
        }

        // Saturation: white → transparent, left → right
        try (Shader satShader = Shader.makeLinearGradient(
                0, 0, w, 0, new int[]{0xFFFFFFFF, 0x00FFFFFF});
             Paint sat = new Paint()) {
            sat.setAntiAlias(true);
            sat.setShader(satShader);
            canvas.drawRRect(rrect, sat);
        }

        // Value: transparent → black, top → bottom
        try (Shader valShader = Shader.makeLinearGradient(
                0, 0, 0, h, new int[]{0x00000000, 0xFF000000});
             Paint val = new Paint()) {
            val.setAntiAlias(true);
            val.setShader(valShader);
            canvas.drawRRect(rrect, val);
        }

        strokeBorder(canvas, rrect);

        // Selection marker: AA double ring at (s, 1-v)
        float markerX = ColorUtils.clamp(state.getSaturation(), 0f, 1f) * w;
        float markerY = (1.0f - ColorUtils.clamp(state.getValue(), 0f, 1f)) * h;
        canvas.save();
        canvas.clipRect(Rect.makeWH(w, h));
        drawMarkerRing(canvas, markerX, markerY, 6f);
        canvas.restore();
    }

    private void paintHueBar(Canvas canvas, ColorSelectionState state, float height) {
        RRect rrect = RRect.makeLTRB(0, 0, HUE_WIDTH, height, 3f);

        try (Shader rainbow = Shader.makeLinearGradient(0, 0, 0, height, HUE_STOPS);
             Paint paint = new Paint()) {
            paint.setAntiAlias(true);
            paint.setShader(rainbow);
            canvas.drawRRect(rrect, paint);
        }

        strokeBorder(canvas, rrect);

        // Pill inset 2px so its 3px outer stroke isn't clipped at the surface edge
        float y = ColorUtils.clamp(state.getHue() / 360f, 0f, 1f) * height;
        drawPillMarker(canvas, 2f, clampPill(y, height), HUE_WIDTH - 4f, false);
    }

    private void paintAlphaBar(Canvas canvas, ColorSelectionState state, float w, float h) {
        RRect rrect = RRect.makeLTRB(0, 0, w, h, 3f);

        // Checkerboard, clipped to the rounded bar
        canvas.save();
        canvas.clipRRect(rrect, true);
        float cell = h / 2f;
        try (Paint light = new Paint(); Paint dark = new Paint()) {
            light.setColor(0xFFCCCCCC);
            dark.setColor(0xFF999999);
            int cols = (int) Math.ceil(w / cell);
            for (int row = 0; row < 2; row++) {
                for (int col = 0; col < cols; col++) {
                    Paint p = ((row + col) % 2 == 0) ? light : dark;
                    canvas.drawRect(Rect.makeXYWH(col * cell, row * cell, cell, cell), p);
                }
            }
        }

        // Transparent → opaque gradient of the current color
        int[] rgba = state.getRgba();
        int rgbPart = (rgba[0] << 16) | (rgba[1] << 8) | rgba[2];
        try (Shader grad = Shader.makeLinearGradient(
                0, 0, w, 0, new int[]{rgbPart, 0xFF000000 | rgbPart});
             Paint paint = new Paint()) {
            paint.setAntiAlias(true);
            paint.setShader(grad);
            canvas.drawRRect(rrect, paint);
        }
        canvas.restore();

        strokeBorder(canvas, rrect);

        // Pill inset 2px so its 3px outer stroke isn't clipped at the surface edge
        float x = (state.getAlpha() / 255f) * w;
        drawPillMarker(canvas, clampPill(x, w), 2f, h - 4f, true);
    }

    private static void strokeBorder(Canvas canvas, RRect rrect) {
        try (Paint border = new Paint()) {
            border.setAntiAlias(true);
            border.setMode(PaintMode.STROKE);
            border.setStrokeWidth(1.5f);
            border.setColor(BORDER_COLOR);
            canvas.drawRRect(rrect, border);
        }
    }

    /** Keep a 6px pill fully inside [0, extent]. */
    private static float clampPill(float pos, float extent) {
        float half = 3f;
        return ColorUtils.clamp(pos - half, 0, extent - 2 * half);
    }

    /**
     * White-on-black pill marker. Vertical pill spanning bar height when
     * {@code vertical}, horizontal pill spanning bar width otherwise.
     *
     * @param vertical true for the alpha bar (pill at x = pos, full height),
     *                 false for the hue bar (pill at y = pos, full width)
     */
    private static void drawPillMarker(Canvas canvas, float a, float b, float span, boolean vertical) {
        RRect marker = vertical
                ? RRect.makeLTRB(a, b, a + 6f, b + span, 3f)
                : RRect.makeLTRB(a, b, a + span, b + 6f, 3f);
        try (Paint outer = new Paint()) {
            outer.setAntiAlias(true);
            outer.setMode(PaintMode.STROKE);
            outer.setStrokeWidth(3f);
            outer.setColor(0xFF000000);
            canvas.drawRRect(marker, outer);
        }
        try (Paint inner = new Paint()) {
            inner.setAntiAlias(true);
            inner.setMode(PaintMode.STROKE);
            inner.setStrokeWidth(1.5f);
            inner.setColor(0xFFFFFFFF);
            canvas.drawRRect(marker, inner);
        }
    }

    private static void drawMarkerRing(Canvas canvas, float x, float y, float radius) {
        try (Paint glow = new Paint()) {
            glow.setAntiAlias(true);
            glow.setColor(0x40FFFFFF);
            canvas.drawCircle(x, y, radius + 3f, glow);
        }
        try (Paint outer = new Paint()) {
            outer.setAntiAlias(true);
            outer.setMode(PaintMode.STROKE);
            outer.setStrokeWidth(3f);
            outer.setColor(0xFF000000);
            canvas.drawCircle(x, y, radius, outer);
        }
        try (Paint inner = new Paint()) {
            inner.setAntiAlias(true);
            inner.setMode(PaintMode.STROKE);
            inner.setStrokeWidth(1.5f);
            inner.setColor(0xFFFFFFFF);
            canvas.drawCircle(x, y, radius, inner);
        }
    }

    @Override
    public void close() {
        if (svPanel != null) {
            svPanel.close();
            svPanel = null;
        }
        if (huePanel != null) {
            huePanel.close();
            huePanel = null;
        }
        if (alphaPanel != null) {
            alphaPanel.close();
            alphaPanel = null;
        }
    }
}
