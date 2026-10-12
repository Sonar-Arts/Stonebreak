package com.openmason.engine.cenda;

/**
 * Intrinsic size of a measured Yoga leaf (text, images). Called on the layout thread from
 * inside {@link FlexLayoutTree#layout}; must not throw and must not touch the tree.
 */
@FunctionalInterface
public interface FlexMeasure {

    /** Yoga measure modes. */
    int UNDEFINED = 0;
    int EXACTLY = 1;
    int AT_MOST = 2;

    /**
     * @param id     the node's {@link FlexRecord#MEASURE_ID}
     * @param out    receives width and height in points
     */
    void measure(int id, float width, int widthMode, float height, int heightMode, float[] out);

    /**
     * Distance from the leaf's top to its first baseline at the laid-out size, for
     * {@code align-items: baseline}. Defaults to the bottom edge, Yoga's own fallback.
     */
    default float baseline(int id, float width, float height) {
        return height;
    }
}
