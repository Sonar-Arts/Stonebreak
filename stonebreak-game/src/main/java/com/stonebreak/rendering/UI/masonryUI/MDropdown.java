package com.stonebreak.rendering.UI.masonryUI;

import io.github.humbleui.skija.Canvas;
import io.github.humbleui.skija.Font;

/**
 * Combo-box widget: a button that, when open, unrolls a list of choices below
 * itself. Draws in two phases — the closed button via {@link #render}, then
 * the open menu via {@link #renderOverlay} (pushed through
 * {@link MasonryUI#renderOverlays()} so it always paints last).
 *
 * Hit-testing for the open list lives on this widget so MouseHandler doesn't
 * need to understand menu geometry.
 */
public class MDropdown extends MButton {

    private String[] items;
    private int selectedIndex;
    private boolean open;
    private float itemHeight = 30f;
    private int hoveredItem = -1;
    private Runnable onSelectionChanged;

    public MDropdown(String text, String[] items) {
        super(text);
        this.items = items != null ? items : new String[0];
    }

    // ─────────────────────────────────────────────── Fluent config

    public MDropdown itemHeight(float h) { this.itemHeight = h; return this; }
    public MDropdown onSelect(Runnable callback) { this.onSelectionChanged = callback; return this; }

    @Override public MDropdown position(float x, float y) { super.position(x, y); return this; }
    @Override public MDropdown size(float w, float h) { super.size(w, h); return this; }
    @Override public MDropdown bounds(float x, float y, float w, float h) {
        super.bounds(x, y, w, h); return this;
    }

    public void setItems(String[] items) {
        this.items = items != null ? items : new String[0];
        if (selectedIndex >= this.items.length) selectedIndex = 0;
    }
    public String[] items() { return items; }

    public int selectedIndex() { return selectedIndex; }
    public void setSelectedIndex(int index) {
        if (items != null && index >= 0 && index < items.length) this.selectedIndex = index;
    }
    public String selectedItem() {
        return (items != null && selectedIndex >= 0 && selectedIndex < items.length) ? items[selectedIndex] : null;
    }

    public boolean isOpen() { return open; }
    public void open() { open = true; }
    public void close() { open = false; hoveredItem = -1; }
    public void toggle() { if (open) close(); else open(); }

    public float itemHeightPx() { return itemHeight; }
    public void setOnSelectionChanged(Runnable r) { this.onSelectionChanged = r; }

    // ─────────────────────────────────────────────── Interaction

    /**
     * Which list item (if any) is under the mouse. Returns -1 when closed or
     * outside the list rect.
     */
    public int itemUnderMouse(float mouseX, float mouseY) {
        if (!open || items.length == 0) return -1;
        if (mouseX < x || mouseX > x + width) return -1;
        float listY = y + height;
        float relY = mouseY - listY;
        if (relY < 0 || relY > items.length * itemHeight) return -1;
        return (int) (relY / itemHeight);
    }

    public void selectItem(int index) {
        if (index < 0 || index >= items.length) return;
        if (selectedIndex != index) {
            selectedIndex = index;
            if (onSelectionChanged != null) onSelectionChanged.run();
        }
        close();
    }

    /**
     * Nudge the selection by direction (-1 or +1) while open; fires the
     * change callback each step so the settings live-update.
     */
    public void adjustSelection(int direction) {
        if (items.length == 0) return;
        int next = Math.max(0, Math.min(items.length - 1, selectedIndex + direction));
        if (next != selectedIndex) {
            selectedIndex = next;
            if (onSelectionChanged != null) onSelectionChanged.run();
        }
    }

    @Override
    public boolean updateHover(float mouseX, float mouseY) {
        boolean overButton = super.updateHover(mouseX, mouseY);
        hoveredItem = itemUnderMouse(mouseX, mouseY);
        return overButton || hoveredItem >= 0;
    }

    /**
     * Mouse press handler: toggles the list when clicking the header,
     * commits the hovered item when clicking inside the open list, and
     * closes the list on any outside click. Returns true if the event was
     * consumed.
     */
    @Override
    public boolean handleClick(float mouseX, float mouseY) {
        if (contains(mouseX, mouseY)) {
            toggle();
            click();
            return true;
        }
        if (open) {
            int hit = itemUnderMouse(mouseX, mouseY);
            if (hit >= 0) {
                selectItem(hit);
            } else {
                close();
            }
            return true;
        }
        return false;
    }

    // ─────────────────────────────────────────────── Render

    @Override
    public void render(MasonryUI ui) {
        Canvas canvas = ui.canvas();
        if (canvas == null) return;
        drawBody(canvas);
        Font font = ui.fonts().get(fontSize);
        drawLabel(canvas, font);
        drawArrow(canvas);
        if (open) ui.pushOverlay(() -> renderOverlay(ui));
    }

    private void drawArrow(Canvas canvas) {
        float cx = x + width - 16f;
        float cy = y + height / 2f;
        // Small triangle pointing down (or up when open)
        float s = 5f;
        int color = hovered || selected ? MStyle.TEXT_ACCENT : MStyle.TEXT_PRIMARY;
        if (open) {
            MPainter.fillRect(canvas, cx - s, cy - 1f, s * 2f, 2f, color);
            MPainter.fillRect(canvas, cx - s + 2f, cy - 3f, s * 2f - 4f, 2f, color);
            MPainter.fillRect(canvas, cx - 1f, cy - 5f, 2f, 2f, color);
        } else {
            MPainter.fillRect(canvas, cx - s, cy - 3f, s * 2f, 2f, color);
            MPainter.fillRect(canvas, cx - s + 2f, cy - 1f, s * 2f - 4f, 2f, color);
            MPainter.fillRect(canvas, cx - 1f, cy + 1f, 2f, 2f, color);
        }
    }

    private void renderOverlay(MasonryUI ui) {
        Canvas canvas = ui.canvas();
        if (canvas == null || items == null || items.length == 0) return;
        float listX = x;
        float listY = y + height;
        float listH = items.length * itemHeight;

        MPainter.fillRect(canvas, listX, listY, width, listH, MStyle.DROPDOWN_FILL);

        Font font = ui.fonts().get(MStyle.FONT_DROPDOWN);
        for (int i = 0; i < items.length; i++) {
            float rowY = listY + i * itemHeight;
            int fill;
            if (i == hoveredItem) fill = MStyle.DROPDOWN_ITEM_HOVER;
            else if (i == selectedIndex) fill = MStyle.DROPDOWN_ITEM_CURRENT;
            else fill = MStyle.DROPDOWN_ITEM_FILL;
            if (fill != 0) MPainter.fillRect(canvas, listX, rowY, width, itemHeight, fill);
            MPainter.drawString(canvas, items[i], listX + 12f, rowY + itemHeight / 2f + 6f, font, MStyle.TEXT_PRIMARY);
        }

        MPainter.strokeRect(canvas, listX, listY, width, listH, MStyle.BUTTON_BORDER, 2f);
    }
}
