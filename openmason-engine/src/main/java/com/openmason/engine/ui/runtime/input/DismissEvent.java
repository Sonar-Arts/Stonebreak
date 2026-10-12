package com.openmason.engine.ui.runtime.input;

/** A popup is being dismissed (#288). Preventing the default keeps it open. */
public final class DismissEvent extends UiEvent {

    private final DismissReason reason;

    public DismissEvent(double time, DismissReason reason) {
        super(UiEventType.DISMISS, time);
        this.reason = reason;
    }

    public DismissReason reason() {
        return reason;
    }
}
