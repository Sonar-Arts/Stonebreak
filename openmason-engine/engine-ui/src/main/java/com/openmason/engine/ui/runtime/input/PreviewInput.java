package com.openmason.engine.ui.runtime.input;

import com.openmason.engine.ui.rendering.PreviewMapping;

import java.util.Objects;

/**
 * The editor preview's side of input (#288): converts screen coordinates of the displayed image
 * into the document's viewport pixels with {@link PreviewMapping} and feeds the same
 * {@link UiInputRouter} the game uses, so a preview behaves exactly like the game window.
 *
 * <p>Input stays within the displayed canvas: when the pointer is not over the image (outside
 * it, or another editor window covers it) the document only sees it through a live capture or
 * drag; otherwise the pointer has left. Keyboard and text are forwarded only while the preview
 * has keyboard focus, which the host decides; {@link #wantsKeyboard()} tells the host to keep
 * editor shortcuts away while the document is using the keyboard.
 */
public final class PreviewInput {

    private final UiInputRouter router;
    private PreviewMapping mapping = new PreviewMapping(0, 0, 1);

    public PreviewInput(UiInputRouter router) {
        this.router = Objects.requireNonNull(router, "router");
    }

    public UiInputRouter router() {
        return router;
    }

    /** Where the image was drawn this frame. */
    public void setMapping(PreviewMapping mapping) {
        this.mapping = Objects.requireNonNull(mapping, "mapping");
    }

    public PreviewMapping mapping() {
        return mapping;
    }

    /**
     * @param overImage the pointer is over the visible preview image
     * @return consumed by the document
     */
    public boolean pointerMove(float screenX, float screenY, boolean overImage) {
        if (!overImage && !engaged()) {
            router.pointerLeave();
            return false;
        }
        return router.pointerMove(mapping.canvasX(screenX), mapping.canvasY(screenY));
    }

    /** A button press or release at a screen point; presses only count over the image. */
    public boolean pointerButton(float screenX, float screenY, boolean overImage, int button, boolean down, int mods) {
        float x = mapping.canvasX(screenX);
        float y = mapping.canvasY(screenY);
        if (down) {
            return overImage && router.pointerDown(x, y, button, mods);
        }
        return router.pointerUp(x, y, button, mods);
    }

    public boolean wheel(float screenX, float screenY, boolean overImage, float dx, float dy, int mods) {
        return overImage && router.wheel(mapping.canvasX(screenX), mapping.canvasY(screenY), dx, dy, mods);
    }

    /** True while the document should own the keyboard: something in it has focus, or a modal is open. */
    public boolean wantsKeyboard() {
        return router.focus().focused() != null || router.focus().activeModal() != null;
    }

    private boolean engaged() {
        return router.pointerCapture() != null || router.drag().active();
    }
}
