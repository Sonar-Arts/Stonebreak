package com.openmason.engine.ui.l10n;

/** A message pattern that does not parse; {@link #position()} is the offending char index. */
public final class MessageFormatException extends IllegalArgumentException {

    private final int position;

    public MessageFormatException(String message, int position) {
        super(message + " (at " + position + ")");
        this.position = position;
    }

    /** Index into the pattern where parsing failed. */
    public int position() {
        return position;
    }
}
