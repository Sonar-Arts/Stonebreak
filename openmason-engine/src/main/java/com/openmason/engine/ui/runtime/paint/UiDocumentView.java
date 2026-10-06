package com.openmason.engine.ui.runtime.paint;

import com.openmason.engine.ui.masonry.MasonryUI;
import com.openmason.engine.ui.runtime.UiDocumentInstance;
import com.openmason.engine.ui.runtime.UiElement;
import com.openmason.engine.ui.runtime.UiMetrics;
import com.openmason.engine.ui.runtime.binding.UiBinder;
import com.openmason.engine.ui.runtime.input.InputDevice;
import com.openmason.engine.ui.runtime.input.PointerEvent;
import com.openmason.engine.ui.runtime.input.UiInputRouter;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * One document on screen: the runtime instance, its painter and its {@link UiInputRouter}. The
 * game window and the Open Mason preview both host documents through this class, so styles,
 * layout, pointer, focus and keyboard behaviour are identical in both (#287, #288); only the
 * Masonry backend, the render target and how raw input is collected differ.
 *
 * <p>Hosts send raw input to {@link #input()} in viewport device pixels (the editor converts
 * first with {@code PreviewMapping}) and fall through to gameplay or editor shortcuts only when
 * it reports the input unconsumed. The pointer shortcuts below are the primary-button subset the
 * #287 tests and the dev overlay use.
 */
public final class UiDocumentView implements AutoCloseable {

    private final UiDocumentInstance ui;
    private final UiPainter painter;
    private final UiInputRouter router;
    private final List<Extension> extensions = new ArrayList<>();
    private UiBinder binder;

    /**
     * Something that lives and dies with a view: Lua code-behind (#292). {@link #frame} runs once
     * per host frame, {@link #close} before the binder and the document go away.
     */
    public interface Extension extends AutoCloseable {
        void frame(double dt);

        @Override
        void close();
    }

    public UiDocumentView(UiDocumentInstance ui, UiPainter painter) {
        this.ui = Objects.requireNonNull(ui, "ui");
        this.painter = Objects.requireNonNull(painter, "painter");
        this.router = new UiInputRouter(ui);
    }

    public UiDocumentInstance instance() {
        return ui;
    }

    /**
     * Gives this view the binder connecting its document to a host (#289): {@link #render} keeps
     * virtualized lists in step with layout, and {@link #close} releases the binder (its
     * subscriptions, pending actions and draft) before the document.
     */
    public UiDocumentView bind(UiBinder b) {
        if (b.instance() != ui) {
            throw new IllegalArgumentException("binder belongs to another document instance");
        }
        this.binder = b;
        return this;
    }

    /** The binder, or {@code null} for an unbound (static) document. */
    public UiBinder binder() {
        return binder;
    }

    /** Attaches {@code e}: it gets {@link #frame} calls and closes with this view. */
    public UiDocumentView extend(Extension e) {
        extensions.add(Objects.requireNonNull(e, "extension"));
        return this;
    }

    public List<Extension> extensions() {
        return List.copyOf(extensions);
    }

    /**
     * Advances the extensions (scripts and their animations) by {@code dt} seconds of UI time.
     * Hosts call it once per frame before {@link #render}, next to {@code input().tick(dt)}.
     */
    public void frame(double dt) {
        for (int i = 0; i < extensions.size(); i++) {
            extensions.get(i).frame(dt);
        }
    }

    /** The document's interaction model: route every host input event here. */
    public UiInputRouter input() {
        return router;
    }

    /**
     * Lays out at the frame's size, reconciles input state with the new geometry (hover under a
     * still pointer, focus on an element that disappeared, open scopes), and paints into
     * {@code masonry}'s open frame (the host calls {@code beginFrame}/{@code endFrame} around it
     * and clears first).
     */
    public void render(MasonryUI masonry, int width, int height, float uiScale, float pixelRatio) {
        ui.setMetrics(new UiMetrics(width, height, uiScale, pixelRatio));
        ui.update();
        router.sync();
        if (binder != null) {
            binder.sync();
        }
        ui.update();
        painter.paint(ui, masonry, router);
    }

    /** Pointer moved to device-pixel {@code (x, y)} on the frame. */
    public void pointerMove(float x, float y) {
        router.pointerMove(x, y);
    }

    /** Pointer left the frame. */
    public void pointerLeave() {
        router.pointerLeave();
    }

    public void pointerDown(float x, float y) {
        router.pointerDown(x, y, PointerEvent.PRIMARY, 0);
    }

    /** @return the clicked element (pressed and released over the same enabled element), or null */
    public UiElement pointerUp(float x, float y) {
        router.pointerUp(x, y, PointerEvent.PRIMARY, 0);
        return router.lastClick();
    }

    /** Focuses {@code el} from code (null clears); refused for elements that cannot take focus. */
    public void focus(UiElement el) {
        router.focus().focus(el, InputDevice.PROGRAM);
    }

    public UiElement focused() {
        return router.focus().focused();
    }

    @Override
    public void close() {
        for (int i = extensions.size() - 1; i >= 0; i--) {
            try {
                extensions.get(i).close();
            } catch (RuntimeException e) {
                ui.reportDiagnostic(com.openmason.engine.ui.runtime.UiRuntimeDiagnostic.error(
                    com.openmason.engine.ui.runtime.UiRuntimeDiagnostic.Code.SCRIPT_ERROR, "",
                    "closing a view extension failed: " + e));
            }
        }
        extensions.clear();
        router.screenClosed();
        if (binder != null) {
            binder.close();
        }
        ui.close();
    }
}
