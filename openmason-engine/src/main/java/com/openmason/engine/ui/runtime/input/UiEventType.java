package com.openmason.engine.ui.runtime.input;

/**
 * Every event the input router dispatches (#288). Each type either bubbles (trickle-down,
 * target, bubble-up) or does not (trickle-down and target only), following Unity UI Toolkit:
 * enter/leave, focus/blur and the drag enter/leave/end notifications are per-element and do
 * not bubble; everything a parent may want to handle for its children bubbles.
 */
public enum UiEventType {
    POINTER_DOWN(true),
    POINTER_UP(true),
    POINTER_MOVE(true),
    /** The pointer entered this element or one of its descendants (sent to each element of the entered chain). */
    POINTER_ENTER(false),
    POINTER_LEAVE(false),
    /** A captured or pressed pointer interaction ended without a release (focus loss, removal, screen close). */
    POINTER_CANCEL(true),
    /** Pressed and released over the same enabled element, or submitted from the keyboard or a controller. */
    CLICK(true),
    WHEEL(true),
    KEY_DOWN(true),
    KEY_UP(true),
    TEXT_INPUT(true),
    COMPOSITION(true),
    /** A navigation action: a direction, next or previous. */
    NAVIGATE(true),
    SUBMIT(true),
    CANCEL(true),
    FOCUS_IN(true),
    FOCUS_OUT(true),
    FOCUS(false),
    BLUR(false),
    /** A text field's value changed while editing. */
    CHANGE(true),
    /** A text field's value was committed (Enter, or blur with {@code commitOnBlur}). */
    COMMIT(true),
    /** Sent to the drag source when a drag would start; a handler supplies the payload. */
    DRAG_START(true),
    DRAG_ENTER(false),
    DRAG_LEAVE(false),
    /** The dragged payload is over this element; a handler accepts it with {@link DragEvent#acceptDrop()}. */
    DRAG_OVER(true),
    DRAG_DROP(true),
    /** Sent to the drag source exactly once per drag, with the outcome. */
    DRAG_END(false),
    /** A popup is being dismissed; preventing the default keeps it open. */
    DISMISS(false);

    private final boolean bubbles;

    UiEventType(boolean bubbles) {
        this.bubbles = bubbles;
    }

    public boolean bubbles() {
        return bubbles;
    }
}
