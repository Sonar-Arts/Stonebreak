package com.stonebreak.rendering.UI.masonryUI;

import io.github.humbleui.skija.Canvas;
import io.github.humbleui.skija.Font;

import java.util.function.IntConsumer;

/**
 * Horizontal select-one-of-N tab strip. Tabs divide the widget's width
 * equally; the selected tab draws with the highlight fill, accent text and
 * an accent underline bar so the live tab reads at a glance.
 *
 * <p>Owns its own hover/selection index state — call {@link #updateHover}
 * from the mouse-move path and {@link #handleClick} from the click path,
 * exactly like the other MasonryUI widgets.
 */
public class MTabBar extends MWidget {

    private static final float TAB_GAP = 2f;
    private static final float UNDERLINE_H = 3f;

    private String[] tabs = new String[0];
    private int selectedIndex;
    private int hoveredIndex = -1;
    private float fontSize = MStyle.FONT_ITEM;
    private IntConsumer onSelect;

    public MTabBar(String... tabs) {
        if (tabs != null) this.tabs = tabs;
    }

    // ─────────────────────────────────────────────── Fluent config

    public MTabBar tabs(String... value) { this.tabs = value != null ? value : new String[0]; return this; }
    public MTabBar fontSize(float v) { this.fontSize = v; return this; }
    public MTabBar onSelect(IntConsumer callback) { this.onSelect = callback; return this; }

    /** Sets the selection without firing the callback (initial state, external sync). */
    public MTabBar selected(int index) {
        this.selectedIndex = clampIndex(index);
        return this;
    }

    public int selectedIndex() { return selectedIndex; }
    public int hoveredIndex() { return hoveredIndex; }
    public String[] tabs() { return tabs; }

    // Covariant returns keep fluent chains typed as MTabBar.
    @Override public MTabBar position(float x, float y) { super.position(x, y); return this; }
    @Override public MTabBar size(float w, float h) { super.size(w, h); return this; }
    @Override public MTabBar bounds(float x, float y, float w, float h) {
        super.bounds(x, y, w, h); return this;
    }

    // ─────────────────────────────────────────────── Interaction

    /** Index of the tab under {@code (px, py)}, or -1 when outside the bar. */
    public int tabAt(float px, float py) {
        if (tabs.length == 0 || !contains(px, py)) return -1;
        int index = (int) ((px - x) / (width / tabs.length));
        return Math.min(index, tabs.length - 1);
    }

    @Override
    public boolean updateHover(float mouseX, float mouseY) {
        hoveredIndex = tabAt(mouseX, mouseY);
        hovered = hoveredIndex >= 0;
        return hovered;
    }

    /**
     * Selects the clicked tab. Returns true when the click landed on the bar
     * (consumed) — the callback fires only when the selection actually moved.
     */
    public boolean handleClick(float mouseX, float mouseY) {
        int index = tabAt(mouseX, mouseY);
        if (index < 0) return false;
        select(index);
        return true;
    }

    /** Programmatic selection — fires the callback when the index changes. */
    public void select(int index) {
        int next = clampIndex(index);
        if (next == selectedIndex) return;
        selectedIndex = next;
        if (onSelect != null) onSelect.accept(next);
    }

    private int clampIndex(int index) {
        if (tabs.length == 0) return 0;
        return Math.max(0, Math.min(index, tabs.length - 1));
    }

    // ─────────────────────────────────────────────── Render

    @Override
    public void render(MasonryUI ui) {
        Canvas canvas = ui.canvas();
        if (canvas == null || tabs.length == 0 || width <= 0f || height <= 0f) return;

        Font font = fontFor(ui, fontSize);
        float slotW = width / tabs.length;
        float baseline = y + height / 2f + fontSize * 0.35f * textScale();

        for (int i = 0; i < tabs.length; i++) {
            float tx = x + i * slotW;
            float tw = slotW - (i < tabs.length - 1 ? TAB_GAP : 0f);
            boolean isSelected = i == selectedIndex;
            boolean isHovered = i == hoveredIndex;

            int fill = isSelected ? MStyle.BUTTON_FILL_HI
                    : isHovered ? MStyle.BUTTON_FILL
                    : MStyle.BUTTON_FILL_DIS;
            MPainter.stoneSurface(canvas, tx, y, tw, height, MStyle.BUTTON_RADIUS,
                    fill, MStyle.BUTTON_BORDER,
                    MStyle.BUTTON_HIGHLIGHT, MStyle.BUTTON_SHADOW, MStyle.BUTTON_DROP_SHADOW,
                    MStyle.BUTTON_NOISE_DARK, MStyle.BUTTON_NOISE_LIGHT);

            if (isSelected) {
                MPainter.fillRoundedRect(canvas, tx + 3f, y + height - UNDERLINE_H - 2f,
                        tw - 6f, UNDERLINE_H, UNDERLINE_H / 2f, MStyle.TEXT_ACCENT);
            }

            int color = isSelected ? MStyle.TEXT_ACCENT
                    : isHovered ? MStyle.TEXT_PRIMARY
                    : MStyle.TEXT_SECONDARY;
            MPainter.drawCenteredStringWithShadow(canvas, tabs[i], tx + tw / 2f, baseline,
                    font, color, MStyle.TEXT_SHADOW);
        }
    }
}
