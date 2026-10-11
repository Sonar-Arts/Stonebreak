package com.stonebreak.rendering.UI.components;

import com.openmason.engine.ui.masonry.MFonts;
import com.openmason.engine.ui.masonry.MPainter;
import io.github.humbleui.skija.Canvas;
import io.github.humbleui.skija.Font;

/**
 * The legacy world-space renderers' drawing of a {@link WorldMarker} (#301: split out of their
 * {@code render} methods, draw calls unchanged), so the projected markers they collect paint the same
 * whichever renderer owns the kind. The world HUD document is gated against this.
 */
final class WorldMarkerPaint {

    // Ranger quarry marker (QuarryMarkerRenderer)
    static final float MARKER_HALF_SIZE = 7f;
    static final float NAME_FONT_SIZE = 14f;
    static final float NAME_GAP = 6f;
    static final float HP_BAR_WIDTH = 56f;
    static final float HP_BAR_HEIGHT = 5f;
    static final float HP_BAR_GAP = 4f;
    static final int PREY_MARKER_FILL = 0xDC58B858; // hunter green
    static final int PREY_MARKER_BORDER = 0xFF1A3D1A;
    static final int NAME_COLOR = 0xFFFFFFFF;
    static final int NAME_SHADOW = 0x991A1A1A;
    static final int HP_BAR_BG = 0xC83C3C3C;
    static final int HP_BAR_FILL = 0xDCC83C32;
    static final int HP_BAR_BORDER = 0xFF000000;
    static final int TRAP_BORDER = 0xFF000000;

    private WorldMarkerPaint() {
    }

    /** A centred, shadowed label at its baseline: damage numbers, Doubt counts, awareness glyphs, name tags. */
    static void text(Canvas canvas, MFonts fonts, WorldMarker m) {
        Font font = fonts == null ? null : fonts.get(m.size());
        if (font == null) return;
        MPainter.drawCenteredStringWithShadow(canvas, m.text(), m.x(), m.y(), font, m.color(), m.shadow());
    }

    /** The Marked Prey indicator: a diamond, the prey's name above it, its health bar below. */
    static void prey(Canvas canvas, MFonts fonts, WorldMarker m) {
        float sx = m.x();
        float sy = m.y();
        drawDiamond(canvas, sx, sy, MARKER_HALF_SIZE, PREY_MARKER_FILL, PREY_MARKER_BORDER);

        Font font = fonts != null ? fonts.get(NAME_FONT_SIZE) : null;
        float nameBaseline = sy - MARKER_HALF_SIZE - NAME_GAP;
        if (font != null) {
            MPainter.drawCenteredStringWithShadow(canvas, m.text(), sx, nameBaseline, font, NAME_COLOR, NAME_SHADOW);
        }

        float hpFraction = m.fraction();
        float barX = sx - HP_BAR_WIDTH / 2f;
        float barY = sy + MARKER_HALF_SIZE + HP_BAR_GAP;
        MPainter.fillRect(canvas, barX, barY, HP_BAR_WIDTH, HP_BAR_HEIGHT, HP_BAR_BG);
        if (hpFraction > 0f) {
            MPainter.fillRect(canvas, barX, barY, HP_BAR_WIDTH * hpFraction, HP_BAR_HEIGHT, HP_BAR_FILL);
        }
        MPainter.strokeRect(canvas, barX, barY, HP_BAR_WIDTH, HP_BAR_HEIGHT, HP_BAR_BORDER, 1f);
    }

    /** The Snare trap: a square, orange while arming (and growing), green once live. */
    static void trap(Canvas canvas, WorldMarker m) {
        float half = m.half();
        MPainter.fillRect(canvas, m.x() - half, m.y() - half, half * 2f, half * 2f, m.color());
        MPainter.strokeRect(canvas, m.x() - half, m.y() - half, half * 2f, half * 2f, TRAP_BORDER, 1f);
    }

    /** A filled square rotated 45° — drawn with plain rects (no Skija Path needed). */
    private static void drawDiamond(Canvas canvas, float cx, float cy, float half, int fill, int border) {
        canvas.save();
        try {
            canvas.translate(cx, cy);
            canvas.rotate(45f);
            MPainter.fillRect(canvas, -half, -half, half * 2f, half * 2f, fill);
            MPainter.strokeRect(canvas, -half, -half, half * 2f, half * 2f, border, 1.5f);
        } finally {
            canvas.restore();
        }
    }
}
