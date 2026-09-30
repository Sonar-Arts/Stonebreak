package com.openmason.main.systems.menus.toolbars;

import com.openmason.main.systems.menus.textureCreator.icons.SkijaToolIconStore;
import com.openmason.main.systems.mortar.parts.MortarIconButton;
import com.openmason.main.systems.mortar.theme.Argb;
import com.openmason.main.systems.mortar.theme.MortarTheme;
import com.openmason.main.systems.skija.SkijaContext;
import com.openmason.main.systems.skija.SkijaImGuiPanel;
import imgui.ImGui;
import io.github.humbleui.skija.Canvas;
import io.github.humbleui.skija.Paint;
import io.github.humbleui.skija.PaintMode;
import io.github.humbleui.types.RRect;

import java.util.List;

/**
 * Skija-painted vertical tool strip: vector SVG icons with antialiased
 * rounded selection/hover highlights, composited into ImGui as one image
 * item. Painting and cell geometry only — the caller owns tool switching,
 * tooltips, and popups, driven by the hovered index this renderer reports.
 *
 * Cell metrics are logical px scaled by the UI density
 * ({@link MortarTheme#scale}), matching MortarUI regions.
 *
 * Hover highlight uses the index from the previous frame (the image must be
 * painted before ImGui can hit-test it); at render framerates this is
 * imperceptible.
 */
public final class SkijaToolStripRenderer implements AutoCloseable {

    public static final float CELL_SIZE = 34f;
    public static final float CELL_SPACING = 4f;
    private static final float ICON_SIZE = 24f;
    private static final float CELL_ROUNDING = MortarIconButton.RADIUS;

    /**
     * Cell colors (ARGB) resolved from the live theme each frame, matching
     * {@link MortarIconButton}'s surface/border treatment so the strip reads
     * correctly on light themes as well as dark ones.
     */
    private record CellColors(int bg, int border, int hoverBg, int hoverBorder,
                              int selectedBg, int selectedBorder) {
        static CellColors of(MortarTheme theme) {
            return new CellColors(
                    Argb.withAlpha(theme.surfaceHover, 0.4f),
                    theme.border,
                    Argb.withAlpha(theme.surfaceHover, 0.9f),
                    Argb.lerp(theme.border, theme.borderStrong, 0.6f),
                    theme.accent,
                    Argb.shade(theme.accent, -0.25f));
        }
    }

    private SkijaImGuiPanel panel;
    private SkijaToolIconStore iconStore;
    private int hoveredIndex = -1;

    /** On-screen width of the strip at the current UI density (screen px). */
    public static float stripWidth() {
        return CELL_SIZE * MortarTheme.currentScale();
    }

    public boolean isAvailable() {
        return SkijaContext.getInstance() != null;
    }

    /**
     * Paint the strip for the given icon keys and submit it as an ImGui image
     * item sized to one cell column.
     *
     * @param iconKeys      icon-store key per tool, in display order
     * @param selectedIndex index of the active tool (-1 for none)
     * @return index of the cell under the mouse this frame, or -1
     */
    public int render(List<String> iconKeys, int selectedIndex) {
        ensureCreated();

        float width = CELL_SIZE;
        float height = iconKeys.size() * (CELL_SIZE + CELL_SPACING);
        MortarTheme theme = MortarTheme.capture();
        CellColors colors = CellColors.of(theme);
        float scale = theme.scale;

        panel.draw(width * scale, height * scale, canvas -> {
            canvas.save();
            canvas.scale(scale, scale);
            try {
                paintStrip(canvas, iconKeys, selectedIndex, hoveredIndex, colors);
            } finally {
                canvas.restore();
            }
        });

        hoveredIndex = computeHoveredIndex(iconKeys.size(), scale);
        return hoveredIndex;
    }

    private void ensureCreated() {
        if (panel == null) {
            SkijaContext context = SkijaContext.getInstance();
            if (context == null) {
                throw new IllegalStateException("Skija context not initialized");
            }
            panel = new SkijaImGuiPanel(context);
            iconStore = new SkijaToolIconStore();
        }
    }

    private void paintStrip(Canvas canvas, List<String> iconKeys,
                            int selectedIndex, int hovered, CellColors colors) {
        for (int i = 0; i < iconKeys.size(); i++) {
            float cellY = i * (CELL_SIZE + CELL_SPACING);

            if (i == selectedIndex) {
                fillCell(canvas, cellY, colors.selectedBg());
                strokeCell(canvas, cellY, colors.selectedBorder());
            } else if (i == hovered) {
                fillCell(canvas, cellY, colors.hoverBg());
                strokeCell(canvas, cellY, colors.hoverBorder());
            } else {
                fillCell(canvas, cellY, colors.bg());
                strokeCell(canvas, cellY, colors.border());
            }

            float iconOffset = (CELL_SIZE - ICON_SIZE) / 2f;
            iconStore.paint(canvas, iconKeys.get(i),
                    iconOffset, cellY + iconOffset, ICON_SIZE);
        }
    }

    private static final float STROKE_WIDTH = 1.5f;

    /** Cell rect inset by half the stroke so borders aren't clipped at the surface edge. */
    private static RRect cellRect(float cellY) {
        float inset = STROKE_WIDTH / 2f;
        return RRect.makeLTRB(inset, cellY + inset,
                CELL_SIZE - inset, cellY + CELL_SIZE - inset, CELL_ROUNDING);
    }

    private static void fillCell(Canvas canvas, float cellY, int argb) {
        try (Paint paint = new Paint()) {
            paint.setAntiAlias(true);
            paint.setColor(argb);
            canvas.drawRRect(cellRect(cellY), paint);
        }
    }

    private static void strokeCell(Canvas canvas, float cellY, int argb) {
        try (Paint paint = new Paint()) {
            paint.setAntiAlias(true);
            paint.setMode(PaintMode.STROKE);
            paint.setStrokeWidth(STROKE_WIDTH);
            paint.setColor(argb);
            canvas.drawRRect(cellRect(cellY), paint);
        }
    }

    /** Map the mouse position on the submitted image item to a cell index. */
    private int computeHoveredIndex(int cellCount, float scale) {
        if (!ImGui.isItemHovered()) {
            return -1;
        }
        float localY = (ImGui.getMousePosY() - ImGui.getItemRectMinY()) / scale;
        int index = (int) (localY / (CELL_SIZE + CELL_SPACING));
        if (index < 0 || index >= cellCount) {
            return -1;
        }
        // Exclude the spacing gap below each cell
        float withinCell = localY - index * (CELL_SIZE + CELL_SPACING);
        return withinCell <= CELL_SIZE ? index : -1;
    }

    @Override
    public void close() {
        if (iconStore != null) {
            iconStore.close();
            iconStore = null;
        }
        if (panel != null) {
            panel.close();
            panel = null;
        }
    }
}
