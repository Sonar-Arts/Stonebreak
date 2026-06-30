package com.stonebreak.ui.characterCreation.renderers;

import com.stonebreak.player.CharacterStats;
import com.stonebreak.rendering.UI.masonryUI.MPainter;
import com.stonebreak.rendering.UI.masonryUI.MStyle;
import com.stonebreak.rendering.UI.masonryUI.MasonryUI;
import io.github.humbleui.skija.Canvas;
import io.github.humbleui.skija.Font;
import io.github.humbleui.skija.Paint;
import io.github.humbleui.skija.PaintMode;
import io.github.humbleui.types.RRect;

/**
 * Draws the inset frame that hosts the live 3D player model in the left panel,
 * plus the class/background labels beneath it. The model itself is rendered
 * with raw GL after the Skija frame closes (the Skija surface wraps the default
 * framebuffer), so this renderer only paints the frame and reports the preview
 * rect in window pixels for {@code SkijaCharacterCreationRenderer} to draw into.
 */
public final class CharacterModelPreviewRenderer {

    // Preview frame fills most of the panel width, sized tall for a full body.
    private static final float PREVIEW_WIDTH_RATIO = 0.70f;
    private static final float PREVIEW_HEIGHT_RATIO = 0.78f; // fraction of available height

    private static final int INSET_FILL   = 0xFF1E1E1E;
    private static final int INSET_BORDER = 0xFF0A0A0A;

    /** Preview rect in window pixels: {x, y, w, h}. */
    public record PreviewRect(float x, float y, float w, float h) {}

    /**
     * Paints the inset frame and labels and returns the rect the 3D model
     * should be rendered into (window pixels), or {@code null} if the panel is
     * too small to host a preview.
     */
    public PreviewRect render(Canvas canvas, MasonryUI ui,
                              float panelX, float panelY, float panelW, float panelH,
                              CharacterStats stats) {
        // Reserve space for the title (top) and the labels (bottom).
        float availableH = panelH - 90f;
        if (availableH <= 0f) return null;

        float previewW = panelW * PREVIEW_WIDTH_RATIO;
        float previewH = availableH * PREVIEW_HEIGHT_RATIO;
        float previewX = panelX + (panelW - previewW) / 2f;
        float previewY = panelY + 40f;

        drawInset(canvas, previewX, previewY, previewW, previewH);
        drawLabels(canvas, ui, panelX + panelW / 2f, previewY + previewH, stats);

        return new PreviewRect(previewX, previewY, previewW, previewH);
    }

    private static void drawInset(Canvas canvas, float x, float y, float w, float h) {
        try (Paint p = new Paint().setColor(INSET_FILL).setAntiAlias(true)) {
            canvas.drawRRect(RRect.makeXYWH(x, y, w, h, 4f), p);
        }
        try (Paint p = new Paint().setColor(INSET_BORDER).setAntiAlias(true)
                .setMode(PaintMode.STROKE).setStrokeWidth(1.5f)) {
            canvas.drawRRect(RRect.makeXYWH(x + 0.5f, y + 0.5f, w - 1f, h - 1f, 4f), p);
        }
    }

    private void drawLabels(Canvas canvas, MasonryUI ui, float cx, float previewBottom,
                            CharacterStats stats) {
        Font font = ui.fonts().get(MStyle.FONT_META);
        float labelY = previewBottom + 24f;

        String classLabel = "Class: " + stats.getCharacterClass();
        MPainter.drawCenteredStringWithShadow(canvas, classLabel, cx, labelY,
            font, MStyle.TEXT_ACCENT, MStyle.TEXT_SHADOW);

        String bgId   = stats.getSelectedBackground();
        String bgName = bgId != null ? capitalize(bgId) : "No Background";
        MPainter.drawCenteredStringWithShadow(canvas, "Background: " + bgName, cx, labelY + 18f,
            font, MStyle.TEXT_SECONDARY, MStyle.TEXT_SHADOW);
    }

    private static String capitalize(String s) {
        if (s == null || s.isEmpty()) return s;
        return Character.toUpperCase(s.charAt(0)) + s.substring(1);
    }
}
