package com.openmason.main.systems.mcp.approval;

import java.nio.file.Path;

/**
 * The user's answer to a {@link SaveSheetRequest}.
 *
 * @param status what happened
 * @param path   the chosen absolute target when {@code status == SAVED}, else null
 */
public record SaveSheetResult(Status status, Path path) {

    public enum Status {
        /** The user confirmed a target; {@link #path()} is set. */
        SAVED,
        DECLINED,
        TIMEOUT,
        /** Another question is already on screen. */
        BUSY,
        /** No UI is wired (headless / snapshot test). */
        UNAVAILABLE
    }

    public static final SaveSheetResult DECLINED = new SaveSheetResult(Status.DECLINED, null);
    public static final SaveSheetResult TIMEOUT = new SaveSheetResult(Status.TIMEOUT, null);
    public static final SaveSheetResult BUSY = new SaveSheetResult(Status.BUSY, null);
    public static final SaveSheetResult UNAVAILABLE = new SaveSheetResult(Status.UNAVAILABLE, null);

    public static SaveSheetResult saved(Path path) {
        return new SaveSheetResult(Status.SAVED, path);
    }

    public boolean saved() {
        return status == Status.SAVED;
    }
}
