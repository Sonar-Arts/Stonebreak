package com.stonebreak.ui.worldSelect;

/**
 * Simple data class to represent the bounds of a UI section.
 * Used for layout-based rendering of sections in the world select screen.
 */
public class SectionBounds {

    public final float x;
    public final float y;
    public final float width;
    public final float height;

    public SectionBounds(float x, float y, float width, float height) {
        this.x = x;
        this.y = y;
        this.width = width;
        this.height = height;
    }

    /**
     * Gets the right edge of this section.
     */
    public float getRight() {
        return x + width;
    }

    /**
     * Gets the bottom edge of this section.
     */
    public float getBottom() {
        return y + height;
    }

    /**
     * Gets the center X coordinate of this section.
     */
    public float getCenterX() {
        return x + width / 2.0f;
    }

    /**
     * Gets the center Y coordinate of this section.
     */
    public float getCenterY() {
        return y + height / 2.0f;
    }

    /**
     * Checks if a point is within this section's bounds.
     */
    public boolean contains(float pointX, float pointY) {
        return pointX >= x && pointX <= getRight() &&
               pointY >= y && pointY <= getBottom();
    }

    /**
     * Gets the X coordinate of this section.
     */
    public float getX() {
        return x;
    }

    /**
     * Gets the Y coordinate of this section.
     */
    public float getY() {
        return y;
    }

    /**
     * Gets the width of this section.
     */
    public float getWidth() {
        return width;
    }

    /**
     * Gets the height of this section.
     */
    public float getHeight() {
        return height;
    }
}