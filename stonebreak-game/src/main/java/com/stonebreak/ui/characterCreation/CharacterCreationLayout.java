package com.stonebreak.ui.characterCreation;

import com.openmason.engine.ui.masonry.MPainter;
import com.openmason.engine.ui.masonry.MStyle;
import com.openmason.engine.ui.masonry.MasonryUI;
import io.github.humbleui.skija.Font;

/**
 * Pure layout math. Given a window size, computes the panel rectangles, the tab strip and the
 * preview frame the UI host publishes to the shipped document (#301).
 */
public final class CharacterCreationLayout {

    public record Rect(float x, float y, float width, float height) {
        public float right()   { return x + width; }
        public float bottom()  { return y + height; }
        public float centerX() { return x + width / 2f; }
        public float centerY() { return y + height / 2f; }
        public boolean contains(float px, float py) {
            return px >= x && px <= x + width && py >= y && py <= y + height;
        }
    }

    static final float FOOTER_HEIGHT    = 72f;
    static final float LEFT_PANEL_RATIO = 0.35f;
    static final float TAB_BAR_HEIGHT   = 40f;
    static final float TAB_BAR_TOP_PAD  = 8f;

    private static final float TAB_W   = 150f;
    private static final float TAB_H   = 36f;
    private static final float TAB_GAP = 4f;

    // Preview frame fills most of the panel width, sized tall for a full body.
    private static final float PREVIEW_WIDTH_RATIO = 0.70f;
    private static final float PREVIEW_HEIGHT_RATIO = 0.78f; // fraction of available height

    public Rect leftPanel(int w, int h) {
        float pw = Math.max(1, w);
        float ph = Math.max(1, h);
        return new Rect(0f, 0f, pw * LEFT_PANEL_RATIO, ph - FOOTER_HEIGHT);
    }

    public Rect rightPanel(int w, int h) {
        float pw = Math.max(1, w);
        float ph = Math.max(1, h);
        float lpw = pw * LEFT_PANEL_RATIO;
        return new Rect(lpw, 0f, pw - lpw, ph - FOOTER_HEIGHT);
    }

    public Rect footer(int w, int h) {
        float pw = Math.max(1, w);
        float ph = Math.max(1, h);
        return new Rect(0f, ph - FOOTER_HEIGHT, pw, FOOTER_HEIGHT);
    }

    public Rect tabBar(Rect rightPanel) {
        return new Rect(
            rightPanel.x(),
            rightPanel.y() + TAB_BAR_TOP_PAD,
            rightPanel.width(),
            TAB_BAR_HEIGHT
        );
    }

    public Rect tabContent(Rect rightPanel, Rect tabBar) {
        float contentY = tabBar.bottom() + 4f;
        return new Rect(
            rightPanel.x(),
            contentY,
            rightPanel.width(),
            rightPanel.bottom() - contentY
        );
    }

    /** One tab of the strip: left edge, width, label font size and baseline below the strip's top. */
    public record TabMetric(float x, float width, float fontSize, float baseline) {}

    /**
     * The tab strip in {@code tabBar}: tabs sized to their FONT_META labels (+24, at most {@value #TAB_W}),
     * shrunk together to fit, labels shrunk to fit their tab.
     */
    public static TabMetric[] tabMetrics(MasonryUI ui, Rect tabBar) {
        CharacterCreationTab[] tabs = CharacterCreationTab.values();
        Font labelFont = ui.fonts().get(MStyle.FONT_META);
        float[] widths = new float[tabs.length];
        float totalWidth = 0f;
        for (int i = 0; i < tabs.length; i++) {
            widths[i] = Math.min(TAB_W, MPainter.measureWidth(labelFont, tabs[i].displayName()) + 24f);
            totalWidth += widths[i];
        }
        float available = tabBar.width() - 16f - (tabs.length - 1) * TAB_GAP;
        float fit = Math.min(1f, available / totalWidth);
        TabMetric[] out = new TabMetric[tabs.length];
        float nextX = tabBar.x() + 8f;
        for (int i = 0; i < tabs.length; i++) {
            float tabW = widths[i] * fit;
            float labelWidth = MPainter.measureWidth(labelFont, tabs[i].displayName());
            float fontSize = MStyle.FONT_META * Math.min(1f, Math.max(1f, tabW - 12f) / Math.max(1f, labelWidth));
            out[i] = new TabMetric(nextX, tabW, fontSize, TAB_H * 0.5f + fontSize * 0.38f);
            nextX += tabW + TAB_GAP;
        }
        return out;
    }

    /** Preview rect in window pixels: {x, y, w, h}. */
    public record PreviewRect(float x, float y, float w, float h) {}

    /** The 3D preview's frame in the left panel, or null when the panel is too small. */
    public static PreviewRect previewRect(float panelX, float panelY, float panelW, float panelH) {
        // Reserve space for the title (top) and the labels (bottom).
        float availableH = panelH - 90f;
        if (availableH <= 0f) return null;

        float previewW = panelW * PREVIEW_WIDTH_RATIO;
        float previewH = availableH * PREVIEW_HEIGHT_RATIO;
        float previewX = panelX + (panelW - previewW) / 2f;
        float previewY = panelY + 40f;
        return new PreviewRect(previewX, previewY, previewW, previewH);
    }
}
