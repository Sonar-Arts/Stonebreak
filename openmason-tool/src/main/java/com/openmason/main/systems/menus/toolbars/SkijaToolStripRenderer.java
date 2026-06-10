package com.openmason.main.systems.menus.toolbars;

import com.openmason.main.systems.menus.textureCreator.icons.SkijaToolIconStore;
import com.openmason.main.systems.skija.SkijaContext;
import com.openmason.main.systems.skija.SkijaImGuiPanel;
import imgui.ImGui;
import imgui.ImVec4;
import imgui.flag.ImGuiCol;
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
 * Hover highlight uses the index from the previous frame (the image must be
 * painted before ImGui can hit-test it); at render framerates this is
 * imperceptible.
 */
public final class SkijaToolStripRenderer implements AutoCloseable {

    public static final float CELL_SIZE = 34f;
    public static final float CELL_SPACING = 4f;
    private static final float ICON_SIZE = 24f;
    private static final float CELL_ROUNDING = 5f;

    // Cell colors (ARGB)
    private static final int CELL_BG = 0x14FFFFFF;          // faint raised button face
    private static final int CELL_BORDER = 0x2EFFFFFF;      // subtle box outline
    private static final int HOVER_BG = 0x30FFFFFF;
    private static final int HOVER_BORDER = 0x55FFFFFF;
    private static final int SELECTED_BORDER = 0x66FFFFFF;

    private SkijaImGuiPanel panel;
    private SkijaToolIconStore iconStore;
    private int hoveredIndex = -1;

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
        int accent = currentAccentArgb();

        panel.draw(width, height, canvas ->
                paintStrip(canvas, iconKeys, selectedIndex, hoveredIndex, accent));

        hoveredIndex = computeHoveredIndex(iconKeys.size());
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
                            int selectedIndex, int hovered, int accentArgb) {
        for (int i = 0; i < iconKeys.size(); i++) {
            float cellY = i * (CELL_SIZE + CELL_SPACING);

            if (i == selectedIndex) {
                fillCell(canvas, cellY, accentArgb);
                strokeCell(canvas, cellY, SELECTED_BORDER);
            } else if (i == hovered) {
                fillCell(canvas, cellY, HOVER_BG);
                strokeCell(canvas, cellY, HOVER_BORDER);
            } else {
                fillCell(canvas, cellY, CELL_BG);
                strokeCell(canvas, cellY, CELL_BORDER);
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
    private int computeHoveredIndex(int cellCount) {
        if (!ImGui.isItemHovered()) {
            return -1;
        }
        float localY = ImGui.getMousePosY() - ImGui.getItemRectMinY();
        int index = (int) (localY / (CELL_SIZE + CELL_SPACING));
        if (index < 0 || index >= cellCount) {
            return -1;
        }
        // Exclude the spacing gap below each cell
        float withinCell = localY - index * (CELL_SIZE + CELL_SPACING);
        return withinCell <= CELL_SIZE ? index : -1;
    }

    /** Active theme accent (HeaderActive) converted to Skija ARGB. */
    private static int currentAccentArgb() {
        ImVec4 c = ImGui.getStyle().getColor(ImGuiCol.HeaderActive);
        int a = (int) (c.w * 255) & 0xFF;
        int r = (int) (c.x * 255) & 0xFF;
        int g = (int) (c.y * 255) & 0xFF;
        int b = (int) (c.z * 255) & 0xFF;
        return (a << 24) | (r << 16) | (g << 8) | b;
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
