package com.stonebreak.ui.theme;

import com.openmason.engine.ui.masonry.MPainter;
import com.openmason.engine.ui.masonry.MStyle;
import io.github.humbleui.skija.Canvas;
import io.github.humbleui.skija.Paint;
import io.github.humbleui.skija.PaintMode;
import io.github.humbleui.skija.Path;
import io.github.humbleui.skija.PathBuilder;
import io.github.humbleui.types.RRect;
import io.github.humbleui.types.Rect;

/**
 * Stonebreak's own Masonry shapes (moved out of the engine's {@code MPainter}, #384): the
 * container panel, the chat's folder tab and input well, the crafting arrow. Built from the
 * engine's stone look ({@link MPainter#stoneSurface}, {@link MStyle}); documents reach them
 * through {@link StonebreakUiTheme}.
 */
public final class StonePainter {

    private StonePainter() {
    }

    /** The container screens' panel: the stone panel on the translucent {@link MStyle#PANEL_FILL_TRANS}. */
    public static void containerPanel(Canvas canvas, float x, float y, float w, float h) {
        MPainter.stoneSurface(canvas, x, y, w, h, MStyle.PANEL_RADIUS,
                MStyle.PANEL_FILL_TRANS, MStyle.PANEL_BORDER,
                MStyle.PANEL_HIGHLIGHT, MStyle.PANEL_SHADOW, MStyle.PANEL_DROP_SHADOW,
                MStyle.PANEL_NOISE_DARK, MStyle.PANEL_NOISE_LIGHT);
    }

    /**
     * The chat's folder tab (#301, from the legacy chat renderer): a top-rounded body (radius
     * {@link MStyle#PANEL_RADIUS}, flat bottom so it merges with the panel below), a border on the top
     * and sides only (accent 1.5 px when active, the panel border 1 px otherwise) and a 1 px top
     * highlight. {@code (x, y, w, h)} is the body: the active tab is drawn 2 px taller and 3 px higher.
     */
    public static void folderTab(Canvas canvas, float x, float y, float w, float h, boolean active) {
        float r = MStyle.PANEL_RADIUS;
        try (Paint fill = new Paint().setColor(active ? MStyle.PANEL_FILL : MStyle.BUTTON_FILL).setAntiAlias(true)) {
            canvas.drawRRect(RRect.makeXYWH(x, y, w, h, r, r, 0f, 0f), fill);
        }
        try (Paint border = new Paint().setColor(active ? MStyle.TEXT_ACCENT : MStyle.PANEL_BORDER)
                .setMode(PaintMode.STROKE).setStrokeWidth(active ? 1.5f : 1f).setAntiAlias(true)) {
            canvas.drawRRect(RRect.makeXYWH(x + 0.5f, y + 0.5f, w - 1f, h - 1f, r, r, 0f, 0f), border);
        }
        MPainter.fillRect(canvas, x + 1f, y + 1f, w - 2f, 1f, MStyle.PANEL_HIGHLIGHT);
    }

    /**
     * The chat's recessed input well (#301, from the legacy chat renderer): a dark rounded track
     * (radius 3), a 2 px inner shadow strip under the top edge and a 1.5 px slider-blue rim.
     */
    public static void inputWell(Canvas canvas, float x, float y, float w, float h) {
        float r = 3f;
        try (Paint fill = new Paint().setColor(MStyle.SLIDER_TRACK).setAntiAlias(true)) {
            canvas.drawRRect(RRect.makeXYWH(x, y, w, h, r), fill);
        }
        int shadow = Math.round(((MStyle.PANEL_SHADOW >>> 24) & 0xFF) * 0.6f);
        MPainter.fillRect(canvas, x + 1f, y + 1f, w - 2f, 2f, (Math.max(0, Math.min(255, shadow)) << 24)
                | (MStyle.PANEL_SHADOW & 0xFFFFFF));
        try (Paint border = new Paint().setColor(MStyle.SLIDER_FILL)
                .setMode(PaintMode.STROKE).setStrokeWidth(1.5f).setAntiAlias(true)) {
            canvas.drawRRect(RRect.makeXYWH(x + 0.5f, y + 0.5f, w - 1f, h - 1f, r), border);
        }
    }

    /**
     * Right-pointing arrow drawn as a vector glyph: a horizontal shaft plus a
     * filled triangular head whose single tip meets the right edge of the box.
     * The head is a real triangle (not a right-aligned diamond), so the arrow
     * always points toward the output slot.
     *
     * Used by inventory, workbench, and the recipe detail pane. Vertical
     * centring against the output slot is handled by
     * {@link #craftingArrowPlacement(float, float, float, float, float)}.
     */
    public static void craftingArrow(Canvas canvas, float x, float y, float w, float h, int color) {
        if (canvas == null || w <= 0f || h <= 0f) return;
        float centerY = y + h / 2f;
        float shaftLen = w * 0.55f;
        float shaftH   = Math.min(h, Math.max(2f, h * 0.28f));
        float headHalf = h * 0.42f;
        float faceX    = x + shaftLen; // flat face where the head meets the shaft

        try (Paint fill = new Paint().setColor(color).setAntiAlias(true)) {
            canvas.drawRect(Rect.makeXYWH(x, centerY - shaftH / 2f, shaftLen, shaftH), fill);
            try (PathBuilder pb = new PathBuilder()) {
                pb.moveTo(faceX, centerY - headHalf);
                pb.lineTo(x + w, centerY);
                pb.lineTo(faceX, centerY + headHalf);
                pb.closePath();
                try (Path path = pb.build()) {
                    canvas.drawPath(path, fill);
                }
            }
        }
    }

    /**
     * Auto-aligning placement for the crafting arrow: horizontally centred in
     * the gap between the input grid's right edge and the output slot, and
     * vertically centred on the output slot. This works for any grid size
     * (2×2 inventory, 3×3 workbench) and any uiScale because it derives every
     * dimension from the surrounding slots.
     *
     * @return the arrow's top-left corner as a two-element array {@code {x, y}}
     */
    public static float[] craftingArrowPlacement(float gridRightX, float outputX,
                                                 float outputY, float slotSize,
                                                 float arrowSize) {
        float x = gridRightX + (outputX - gridRightX - arrowSize) / 2f;
        float y = outputY + (slotSize - arrowSize) / 2f;
        return new float[]{x, y};
    }
}
