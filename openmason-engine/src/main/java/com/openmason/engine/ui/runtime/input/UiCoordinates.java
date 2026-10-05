package com.openmason.engine.ui.runtime.input;

import com.openmason.engine.ui.rendering.PreviewMapping;
import com.openmason.engine.ui.runtime.UiElement;
import com.openmason.engine.ui.runtime.UiMetrics;
import com.openmason.engine.ui.runtime.UiRect;

/**
 * The three coordinate spaces input crosses (#288):
 * <ul>
 *   <li><b>pixel</b>: device (framebuffer) pixels of the document's viewport, what layout,
 *       painting, hit tests and {@link UiInputRouter} use;</li>
 *   <li><b>UI</b>: logical pixels as authored, {@code pixel / (uiScale × pixelRatio)}, what
 *       scripts and documents speak;</li>
 *   <li><b>editor</b>: screen coordinates of Open Mason's preview image, converted to pixel
 *       space by {@link PreviewMapping} (zoom is a view transform, never a layout input).</li>
 * </ul>
 * An element's local space is its {@link #elementTransform} inverted.
 */
public final class UiCoordinates {

    private UiCoordinates() {
    }

    public static float toLogical(UiMetrics m, float pixels) {
        return pixels / m.scale();
    }

    public static float toPixels(UiMetrics m, float logical) {
        return logical * m.scale();
    }

    /** Editor screen point → viewport pixels. */
    public static float[] fromEditor(PreviewMapping mapping, float screenX, float screenY) {
        return new float[]{mapping.canvasX(screenX), mapping.canvasY(screenY)};
    }

    /** Viewport pixels → editor screen point. */
    public static float[] toEditor(PreviewMapping mapping, float x, float y) {
        return new float[]{mapping.screenX(x), mapping.screenY(y)};
    }

    /** True when a viewport point lies on the displayed canvas (edges inclusive at the origin). */
    public static boolean onCanvas(UiMetrics m, float x, float y) {
        return x >= 0 && y >= 0 && x < m.viewportWidth() && y < m.viewportHeight();
    }

    /**
     * Element-local → viewport pixels: the element's placed rect origin (layout, ancestor
     * scroll offsets and {@code translate-x/y} are already in {@link UiElement#rect()}).
     * {@code scale}/{@code rotate} compose here when #295 applies them.
     */
    public static UiTransform elementTransform(UiElement el) {
        UiRect r = el.rect();
        return UiTransform.translate(r.x(), r.y());
    }

    /** Viewport pixels → element-local pixels. */
    public static float[] toLocal(UiElement el, float x, float y) {
        UiTransform inv = elementTransform(el).inverse();
        return new float[]{inv.applyX(x, y), inv.applyY(x, y)};
    }
}
