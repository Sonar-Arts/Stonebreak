package com.openmason.engine.ui.runtime.input;

/**
 * Committed text from the platform (one or more code points, never a lone surrogate), sent to
 * the focused element (#288). Preventing the default keeps a text field from inserting it, so a
 * handler can filter typing.
 */
public final class TextInputEvent extends UiEvent {

    private final String text;

    public TextInputEvent(double time, String text) {
        super(UiEventType.TEXT_INPUT, time);
        this.text = text;
    }

    public String text() {
        return text;
    }
}
