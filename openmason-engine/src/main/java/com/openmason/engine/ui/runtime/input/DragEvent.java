package com.openmason.engine.ui.runtime.input;

/**
 * Drag and drop negotiation (#288).
 * <ul>
 *   <li>{@code DRAG_START} (to the pressed element, bubbles): call {@link #setPayload} to start
 *       a drag; without a payload the press stays an ordinary press.</li>
 *   <li>{@code DRAG_ENTER}/{@code DRAG_LEAVE}: per element, as the pointer moves with the payload.</li>
 *   <li>{@code DRAG_OVER} (bubbles): call {@link #acceptDrop()} to become the drop target; the
 *       element whose handler accepts is the target, not necessarily the hit element.</li>
 *   <li>{@code DRAG_DROP} (to the accepting element): take the payload; preventing the default
 *       rejects it and the drag ends {@link CancelReason#REJECTED}.</li>
 *   <li>{@code DRAG_END} (to the source, exactly once): {@link #session()}'s outcome.</li>
 * </ul>
 */
public final class DragEvent extends UiEvent {

    private final float x;
    private final float y;
    private final DragSession session;
    private Object payload;
    private boolean accepted;

    public DragEvent(UiEventType type, double time, float x, float y, DragSession session) {
        super(type, time);
        this.x = x;
        this.y = y;
        this.session = session;
    }

    public float x() {
        return x;
    }

    public float y() {
        return y;
    }

    /** The drag this event belongs to; null for {@code DRAG_START}. */
    public DragSession session() {
        return session;
    }

    /** {@code DRAG_START} only: starts the drag carrying {@code payload}. */
    public void setPayload(Object payload) {
        if (type() != UiEventType.DRAG_START) {
            throw new IllegalStateException("setPayload is only valid on DRAG_START");
        }
        this.payload = payload;
        stopPropagation();
    }

    public Object payload() {
        return session != null ? session.payload() : payload;
    }

    /** {@code DRAG_OVER} only: the current element accepts the payload. */
    public void acceptDrop() {
        if (type() != UiEventType.DRAG_OVER) {
            throw new IllegalStateException("acceptDrop is only valid on DRAG_OVER");
        }
        accepted = true;
        if (session != null) {
            session.accept(currentTarget());
        }
        stopPropagation();
    }

    boolean accepted() {
        return accepted;
    }
}
