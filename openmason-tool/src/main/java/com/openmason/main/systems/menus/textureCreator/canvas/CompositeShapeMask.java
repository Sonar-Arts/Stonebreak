package com.openmason.main.systems.menus.textureCreator.canvas;

/**
 * Composite shape mask that combines multiple masks with AND logic.
 *
 * <p>A pixel is editable only if ALL constituent masks agree.
 * Used when multiple constraints are active simultaneously
 * (e.g., a cube net layout mask AND a polygon face mask during
 * per-face editing on a cube net canvas).
 *
 * @see CanvasShapeMask
 */
public class CompositeShapeMask implements CanvasShapeMask {

    private final CanvasShapeMask[] masks;
    private final int width;
    private final int height;

    /**
     * Create a composite mask from two or more constituent masks.
     *
     * @param masks the masks to combine (all must have compatible dimensions)
     * @throws IllegalArgumentException if fewer than 2 masks or dimensions mismatch
     */
    public CompositeShapeMask(CanvasShapeMask... masks) {
        if (masks.length < 2) {
            throw new IllegalArgumentException("CompositeShapeMask requires at least 2 masks, got: " + masks.length);
        }

        this.width = masks[0].getWidth();
        this.height = masks[0].getHeight();

        for (int i = 1; i < masks.length; i++) {
            if (masks[i].getWidth() != width || masks[i].getHeight() != height) {
                throw new IllegalArgumentException(
                    "Mask dimension mismatch: expected " + width + "x" + height +
                    ", got " + masks[i].getWidth() + "x" + masks[i].getHeight() + " at index " + i);
            }
        }

        this.masks = masks.clone();
    }

    @Override
    public boolean isEditable(int x, int y) {
        for (CanvasShapeMask mask : masks) {
            if (!mask.isEditable(x, y)) {
                return false;
            }
        }
        return true;
    }

    @Override
    public int getWidth() {
        return width;
    }

    @Override
    public int getHeight() {
        return height;
    }

    /**
     * Find the first constituent mask that is an instance of the given type.
     *
     * @param type the mask type to search for
     * @param <T>  the mask type
     * @return the first matching mask, or {@code null} if none found
     */
    @SuppressWarnings("unchecked")
    public <T extends CanvasShapeMask> T findMask(Class<T> type) {
        for (CanvasShapeMask mask : masks) {
            if (type.isInstance(mask)) {
                return (T) mask;
            }
        }
        return null;
    }
}
