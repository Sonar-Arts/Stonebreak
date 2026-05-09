package com.stonebreak.ui.characterCreation;

/**
 * Pure layout math. Given a window size, computes panel rectangles shared by
 * every renderer and mouse handler so hit-testing and drawing stay in lockstep.
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
}
