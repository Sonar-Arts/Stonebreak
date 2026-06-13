package com.openmason.main.systems.skija;

import imgui.ImGui;
import imgui.type.ImBoolean;
import io.github.humbleui.skija.Font;
import io.github.humbleui.skija.Paint;
import io.github.humbleui.skija.PaintMode;
import io.github.humbleui.skija.Shader;
import io.github.humbleui.types.Rect;

/**
 * Interop spike panel: verifies Skija → FBO → ImGui composition without
 * corrupting GL state. Renders an antialiased hue ring, a linear gradient bar,
 * and text in the shared JetBrains Mono typeface.
 *
 * Enabled with {@code -Dopenmason.skija.test=true}. Temporary scaffolding for
 * the texture editor redesign; safe to delete once Skija widgets ship.
 */
public final class SkijaTestPanel implements AutoCloseable {

    public static final boolean ENABLED = Boolean.getBoolean("openmason.skija.test");

    private final ImBoolean visible = new ImBoolean(true);
    private SkijaImGuiPanel panel;

    public void render() {
        SkijaContext context = SkijaContext.getInstance();
        if (context == null || !visible.get()) {
            return;
        }
        if (panel == null) {
            panel = new SkijaImGuiPanel(context);
        }

        ImGui.setNextWindowSize(420, 480, imgui.flag.ImGuiCond.FirstUseEver);
        if (ImGui.begin("Skija Test", visible)) {
            float w = Math.max(64, ImGui.getContentRegionAvailX());
            float h = Math.max(64, ImGui.getContentRegionAvailY());
            panel.draw(w, h, canvas -> paint(canvas, w, h));

            if (ImGui.isItemHovered()) {
                ImGui.setTooltip(String.format("item-relative mouse: %.0f, %.0f",
                        panel.getItemRelativeMouseX(), panel.getItemRelativeMouseY()));
            }
        }
        ImGui.end();
    }

    private void paint(io.github.humbleui.skija.Canvas canvas, float w, float h) {
        float cx = w / 2f;
        float cy = h / 2f - 40f;
        float outer = Math.min(w, h) * 0.28f;

        // Antialiased hue ring via sweep-gradient stroke
        int[] hueColors = {
                0xFFFF0000, 0xFFFFFF00, 0xFF00FF00,
                0xFF00FFFF, 0xFF0000FF, 0xFFFF00FF, 0xFFFF0000
        };
        try (Shader sweep = Shader.makeSweepGradient(cx, cy, hueColors);
             Paint ring = new Paint()) {
            ring.setAntiAlias(true);
            ring.setMode(PaintMode.STROKE);
            ring.setStrokeWidth(outer * 0.35f);
            ring.setShader(sweep);
            canvas.drawCircle(cx, cy, outer, ring);
        }

        // Smooth linear gradient bar
        float barY = cy + outer + 40f;
        Rect bar = Rect.makeXYWH(24, barY, w - 48, 28);
        try (Shader grad = Shader.makeLinearGradient(
                bar.getLeft(), 0, bar.getRight(), 0,
                new int[]{0xFF1E1F22, 0xFF4B9FFF, 0xFFFFFFFF});
             Paint p = new Paint()) {
            p.setAntiAlias(true);
            p.setShader(grad);
            canvas.drawRRect(io.github.humbleui.types.RRect.makeLTRB(
                    bar.getLeft(), bar.getTop(), bar.getRight(), bar.getBottom(), 6f), p);
        }

        // Text in the shared UI typeface
        try (Font font = SkijaFontStore.font(SkijaFontStore.Weight.MEDIUM, 16f);
             Paint text = new Paint()) {
            text.setAntiAlias(true);
            text.setColor(0xFFE8E8E8);
            canvas.drawString("Skija " + Math.round(w) + "x" + Math.round(h)
                    + " — AA ring, gradient, text", 24, barY + 60, font, text);
        }
    }

    @Override
    public void close() {
        if (panel != null) {
            panel.close();
            panel = null;
        }
    }
}
