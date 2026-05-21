package com.stonebreak.player.combat;

/**
 * Manages bow draw state. Hold right-click draws the bow over DRAW_DURATION seconds;
 * release fires an arrow whose speed scales with how long it was drawn.
 */
public class BowController {

    private static final float DRAW_DURATION  = 3.0f;
    private static final float MIN_DRAW_TIME  = 0.25f; // minimum hold before arrow fires
    private static final float MIN_ARROW_SPEED = 15.0f;
    private static final float MAX_ARROW_SPEED = 45.0f;

    private boolean drawing;
    private float drawTime;

    public void startDrawing() {
        drawing = true;
        drawTime = 0.0f;
    }

    public void update(float deltaTime) {
        if (!drawing) return;
        drawTime = Math.min(drawTime + deltaTime, DRAW_DURATION);
    }

    /** Returns true if the arrow should fire (held long enough). Resets state either way. */
    public boolean releaseAndFire() {
        boolean shouldFire = drawing && drawTime >= MIN_DRAW_TIME;
        drawing = false;
        drawTime = 0.0f;
        return shouldFire;
    }

    public void cancel() {
        drawing = false;
        drawTime = 0.0f;
    }

    public boolean isDrawing() { return drawing; }
    public boolean isFullyDrawn() { return drawTime >= DRAW_DURATION; }

    /** [0, 1] draw completion. */
    public float getDrawProgress() {
        return Math.min(drawTime / DRAW_DURATION, 1.0f);
    }

    /** Arrow launch speed in m/s, proportional to draw progress. */
    public float getArrowSpeed() {
        return MIN_ARROW_SPEED + (MAX_ARROW_SPEED - MIN_ARROW_SPEED) * getDrawProgress();
    }

    /**
     * SBO state name for the bow sprite, or null for the unbent default.
     * States cycle through five visual stages evenly over DRAW_DURATION.
     */
    public String getBowSboState() {
        if (drawTime < DRAW_DURATION * 0.2f) return null;
        if (drawTime < DRAW_DURATION * 0.4f) return com.stonebreak.items.ItemType.BOW_STATE_DRAW1;
        if (drawTime < DRAW_DURATION * 0.6f) return com.stonebreak.items.ItemType.BOW_STATE_DRAW2;
        if (drawTime < DRAW_DURATION * 0.8f) return com.stonebreak.items.ItemType.BOW_STATE_DRAW3;
        return com.stonebreak.items.ItemType.BOW_STATE_DRAW4;
    }
}
