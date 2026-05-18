package com.stonebreak.ui.characterCreation.renderers;

import com.stonebreak.player.CharacterStats;
import com.stonebreak.rendering.UI.masonryUI.MPainter;
import com.stonebreak.rendering.UI.masonryUI.MStyle;
import com.stonebreak.rendering.UI.masonryUI.MasonryUI;
import io.github.humbleui.skija.Canvas;
import io.github.humbleui.skija.Font;
import io.github.humbleui.skija.Paint;
import io.github.humbleui.types.Rect;

/**
 * Draws the 2D cylinder silhouette that stands in for the character model in
 * the left panel. Composed of an ellipse top-cap, a body rect with edge shading,
 * and an ellipse bottom-cap so it reads as a 3D solid.
 */
public final class CharacterCylinderRenderer {

    // Cylinder fills ~55% of the panel width, capped so it doesn't overflow vertically
    private static final float CYL_PANEL_RATIO = 0.55f;
    private static final float CYL_ASPECT      = 2.2f;  // height = width * aspect
    private static final float ELLH_RATIO       = 0.12f; // ellipse cap = cylW * ratio

    private static final int BODY_COLOR      = 0xFF555555;
    private static final int EDGE_HIGHLIGHT  = 0x33FFFFFF;
    private static final int EDGE_SHADOW     = 0x33000000;
    private static final int TOP_CAP_COLOR   = 0xFF7A7A7A;
    private static final int BOTTOM_CAP_COLOR= 0xFF3A3A3A;

    public void render(Canvas canvas, MasonryUI ui,
                       float panelX, float panelY, float panelW, float panelH,
                       CharacterStats stats) {
        // Size the cylinder from panel dimensions so it fills the left panel
        float availableH = panelH - 90f; // reserve space for title (top) and labels (bottom)
        float cylW = panelW * CYL_PANEL_RATIO;
        float cylH = Math.min(cylW * CYL_ASPECT, availableH * 0.85f);
        // Recalculate width if height was clamped
        cylW = Math.min(cylW, cylH / CYL_ASPECT);
        float ellH = cylW * ELLH_RATIO;

        float cx    = panelX + panelW / 2f;
        float cy    = panelY + 40f + (availableH - cylH) / 2f + cylH / 2f;

        float bodyX = cx - cylW / 2f;
        float bodyY = cy - cylH / 2f + ellH / 2f;
        float bodyH = cylH - ellH;

        // Main body
        MPainter.fillRect(canvas, bodyX, bodyY, cylW, bodyH, BODY_COLOR);

        // Left-edge highlight
        MPainter.fillRect(canvas, bodyX, bodyY, 4f, bodyH, EDGE_HIGHLIGHT);
        // Right-edge shadow
        MPainter.fillRect(canvas, bodyX + cylW - 4f, bodyY, 4f, bodyH, EDGE_SHADOW);

        // Bottom cap (drawn before top so top visually appears above)
        try (Paint p = new Paint().setColor(BOTTOM_CAP_COLOR).setAntiAlias(true)) {
            canvas.drawOval(Rect.makeXYWH(bodyX, bodyY + bodyH - ellH / 2f, cylW, ellH), p);
        }

        // Top cap
        try (Paint p = new Paint().setColor(TOP_CAP_COLOR).setAntiAlias(true)) {
            canvas.drawOval(Rect.makeXYWH(bodyX, bodyY - ellH / 2f, cylW, ellH), p);
        }

        drawLabels(canvas, ui, cx, cy + cylH / 2f, stats);
    }

    private void drawLabels(Canvas canvas, MasonryUI ui, float cx, float cylBottom, CharacterStats stats) {
        Font font = ui.fonts().get(MStyle.FONT_META);
        float labelY = cylBottom + 24f;

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
