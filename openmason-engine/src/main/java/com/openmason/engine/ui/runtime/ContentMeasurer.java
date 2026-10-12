package com.openmason.engine.ui.runtime;

/**
 * The host's intrinsic-size callback for measured widgets ({@code Label} text through the
 * host font, {@code Image} asset sizes). Called from inside layout on the UI thread; must not
 * throw or edit the tree.
 *
 * <p>Width and height constraints and the result are device pixels; modes are
 * {@link com.openmason.engine.cenda.FlexMeasure} constants (undefined, exactly, at-most).
 */
@FunctionalInterface
public interface ContentMeasurer {

    /**
     * @param scale device pixels per logical pixel, for converting {@code font-size}
     * @param out   receives width and height
     */
    void measure(UiElement element, float width, int widthMode, float height, int heightMode, float scale,
                 float[] out);

    /**
     * Distance in device pixels from the element's top to its first text baseline at the
     * laid-out size. Drives {@code align-items: baseline} and where a {@code Label} paints its
     * text. Defaults to the bottom edge.
     */
    default float baseline(UiElement element, float width, float height, float scale) {
        return height;
    }

    /**
     * Line metrics of a text-editing element ({@code TextField}) at {@code scale} (#288).
     * Defaults to an approximation from {@code font-size}; font-backed hosts measure the
     * real glyphs.
     */
    default TextLineMetrics textLine(UiElement element, float scale) {
        var fs = element.computedStyle().length("font-size");
        float size = fs.px(scale, 18 * scale) * element.owner().preferences().textScale();
        return TextLineMetrics.approximate(size);
    }

    /** Measures everything as 0×0; for documents without measured widgets. */
    ContentMeasurer NONE = (element, width, widthMode, height, heightMode, scale, out) -> {
        out[0] = 0;
        out[1] = 0;
    };
}
