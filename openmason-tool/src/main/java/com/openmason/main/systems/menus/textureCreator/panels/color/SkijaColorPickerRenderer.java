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
 * Skija-painted HSV picker: antialiased saturation/value square and vertical
 * hue bar, each composited into ImGui as an image item. Painting only — input
 * is handled by {@link ColorPickerInteraction} against the submitted items.
 *
 * Skia colors are ARGB (0xAARRGGBB); the editor's packed colors are RGBA
 * (0xAABBGGRR) — conversions stay inside this class.
 */
public final class SkijaColorPickerRenderer implements AutoCloseable {

    public static final float SV_SIZE = 180f;
    public static final float HUE_WIDTH = 24f;

    private static final float CORNER_RADIUS = 4f;

    // ARGB rainbow stops matching the HSV hue circle
    private static final int[] HUE_STOPS = {
            0xFFFF0000, 0xFFFFFF00, 0xFF00FF00,
            0xFF00FFFF, 0xFF0000FF, 0xFFFF00FF, 0xFFFF0000
    };

    private SkijaImGuiPanel svPanel;
    private SkijaImGuiPanel huePanel;

    /** True when a Skija context exists and panels can be created. */
    public boolean isAvailable() {
        return SkijaContext.getInstance() != null;
    }

    /** Submit the SV square as an ImGui image item. */
    public void drawSVSquare(ColorSelectionState state) {
        ensurePanels();
        svPanel.draw(SV_SIZE, SV_SIZE, canvas -> paintSVSquare(canvas, state));
    }

    /** Submit the hue bar as an ImGui image item. */
    public void drawHueBar(ColorSelectionState state) {
        ensurePanels();
        huePanel.draw(HUE_WIDTH, SV_SIZE, canvas -> paintHueBar(canvas, state));
    }

    private void ensurePanels() {
        if (svPanel == null) {
            SkijaContext context = SkijaContext.getInstance();
            if (context == null) {
                throw new IllegalStateException("Skija context not initialized");
            }
            svPanel = new SkijaImGuiPanel(context);
            huePanel = new SkijaImGuiPanel(context);
        }
    }

    private void paintSVSquare(Canvas canvas, ColorSelectionState state) {
        Rect rect = Rect.makeWH(SV_SIZE, SV_SIZE);
        RRect rrect = RRect.makeLTRB(0, 0, SV_SIZE, SV_SIZE, CORNER_RADIUS);

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
                0, 0, SV_SIZE, 0, new int[]{0xFFFFFFFF, 0x00FFFFFF});
             Paint sat = new Paint()) {
            sat.setAntiAlias(true);
            sat.setShader(satShader);
            canvas.drawRRect(rrect, sat);
        }

        // Value: transparent → black, top → bottom
        try (Shader valShader = Shader.makeLinearGradient(
                0, 0, 0, SV_SIZE, new int[]{0x00000000, 0xFF000000});
             Paint val = new Paint()) {
            val.setAntiAlias(true);
            val.setShader(valShader);
            canvas.drawRRect(rrect, val);
        }

        // Border
        try (Paint border = new Paint()) {
            border.setAntiAlias(true);
            border.setMode(PaintMode.STROKE);
            border.setStrokeWidth(1.5f);
            border.setColor(0xFF666666);
            canvas.drawRRect(rrect, border);
        }

        // Selection marker: AA double ring at (s, 1-v)
        float markerX = ColorUtils.clamp(state.getSaturation(), 0f, 1f) * SV_SIZE;
        float markerY = (1.0f - ColorUtils.clamp(state.getValue(), 0f, 1f)) * SV_SIZE;
        canvas.save();
        canvas.clipRect(rect);
        drawMarkerRing(canvas, markerX, markerY, 6f);
        canvas.restore();
    }

    private void paintHueBar(Canvas canvas, ColorSelectionState state) {
        RRect rrect = RRect.makeLTRB(0, 0, HUE_WIDTH, SV_SIZE, 3f);

        try (Shader rainbow = Shader.makeLinearGradient(0, 0, 0, SV_SIZE, HUE_STOPS);
             Paint paint = new Paint()) {
            paint.setAntiAlias(true);
            paint.setShader(rainbow);
            canvas.drawRRect(rrect, paint);
        }

        try (Paint border = new Paint()) {
            border.setAntiAlias(true);
            border.setMode(PaintMode.STROKE);
            border.setStrokeWidth(1.5f);
            border.setColor(0xFF666666);
            canvas.drawRRect(rrect, border);
        }

        // Marker: white pill outline spanning the bar at the current hue
        float y = ColorUtils.clamp(state.getHue() / 360f, 0f, 1f) * SV_SIZE;
        float half = 3f;
        float top = ColorUtils.clamp(y - half, 0, SV_SIZE - 2 * half);
        RRect marker = RRect.makeLTRB(1f, top, HUE_WIDTH - 1f, top + 2 * half, 3f);
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
    }
}
