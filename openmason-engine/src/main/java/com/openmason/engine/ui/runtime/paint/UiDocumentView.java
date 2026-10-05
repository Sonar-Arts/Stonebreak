package com.openmason.engine.ui.runtime.paint;

import com.openmason.engine.ui.masonry.MasonryUI;
import com.openmason.engine.ui.runtime.UiDocumentInstance;
import com.openmason.engine.ui.runtime.UiElement;
import com.openmason.engine.ui.runtime.UiMetrics;

import java.util.HashSet;
import java.util.Objects;
import java.util.Set;

/**
 * One document on screen: the runtime instance, its painter, and the pointer states that drive
 * {@code :hover} and {@code :active}. The game window and the Open Mason preview both host
 * documents through this class, so state rules behave identically in both (#287); only the
 * Masonry backend and render target differ.
 *
 * <p>Pointer semantics follow USS: {@code :hover} is set on the element under the pointer and
 * every ancestor; {@code :active} on the pressed element and its ancestors until release. A
 * press and release on the same element is a click ({@link #pointerUp} returns it). Focus,
 * keyboard navigation and event dispatch belong to #288; {@link #focus} only sets the state.
 */
public final class UiDocumentView implements AutoCloseable {

    private final UiDocumentInstance ui;
    private final UiPainter painter;
    private final Set<UiElement> hovered = new HashSet<>();
    private final Set<UiElement> active = new HashSet<>();
    private UiElement pressed;
    private UiElement focused;
    private float lastX = Float.NaN;
    private float lastY = Float.NaN;

    public UiDocumentView(UiDocumentInstance ui, UiPainter painter) {
        this.ui = Objects.requireNonNull(ui, "ui");
        this.painter = Objects.requireNonNull(painter, "painter");
    }

    public UiDocumentInstance instance() {
        return ui;
    }

    /**
     * Lays out at the frame's size and paints into {@code masonry}'s open frame (the host
     * calls {@code beginFrame}/{@code endFrame} around it and clears first).
     */
    public void render(MasonryUI masonry, int width, int height, float uiScale, float pixelRatio) {
        ui.setMetrics(new UiMetrics(width, height, uiScale, pixelRatio));
        ui.update();
        if (!Float.isNaN(lastX)) {
            setHover(ui.hitTest(lastX, lastY)); // geometry may have moved under a still pointer
            ui.update();
        }
        painter.paint(ui, masonry);
    }

    /** Pointer moved to device-pixel {@code (x, y)} on the frame. */
    public void pointerMove(float x, float y) {
        lastX = x;
        lastY = y;
        setHover(ui.hitTest(x, y));
    }

    /** Pointer left the frame. */
    public void pointerLeave() {
        lastX = Float.NaN;
        lastY = Float.NaN;
        setHover(null);
    }

    public void pointerDown(float x, float y) {
        pointerMove(x, y);
        pressed = ui.hitTest(x, y);
        apply(active, chain(pressed), UiElement.ACTIVE);
    }

    /** @return the clicked element (pressed and released over the same element), or null */
    public UiElement pointerUp(float x, float y) {
        pointerMove(x, y);
        UiElement under = ui.hitTest(x, y);
        UiElement clicked = pressed != null && pressed == under && pressed.isEnabledInHierarchy() ? pressed : null;
        pressed = null;
        apply(active, Set.of(), UiElement.ACTIVE);
        return clicked;
    }

    /** Sets {@code :focus} on one element (null clears). */
    public void focus(UiElement el) {
        if (focused != null && !focused.isRemoved()) {
            focused.setState(UiElement.FOCUS, false);
        }
        focused = el;
        if (el != null) {
            el.setState(UiElement.FOCUS, true);
        }
    }

    public UiElement focused() {
        return focused;
    }

    private void setHover(UiElement target) {
        apply(hovered, chain(target), UiElement.HOVER);
    }

    private static Set<UiElement> chain(UiElement el) {
        Set<UiElement> out = new HashSet<>();
        for (UiElement e = el; e != null; e = e.parent()) {
            out.add(e);
        }
        return out;
    }

    private static void apply(Set<UiElement> current, Set<UiElement> next, String state) {
        for (UiElement e : current) {
            if (!next.contains(e) && !e.isRemoved()) {
                e.setState(state, false);
            }
        }
        for (UiElement e : next) {
            if (!current.contains(e)) {
                e.setState(state, true);
            }
        }
        current.clear();
        current.addAll(next);
    }

    @Override
    public void close() {
        ui.close();
    }
}
