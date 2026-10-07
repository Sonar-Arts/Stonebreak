package com.openmason.engine.ui.runtime.binding;

/**
 * Where one binding stands (#289). Anything but {@link State#ACTIVE} means the target shows its
 * authored or default value, never a stale or half-built one.
 *
 * @param detail what is missing or wrong, for inspectors; empty when active
 */
public record BindingStatus(State state, String detail) {

    public enum State {
        /** The source's value (after conversion) is on the target. */
        ACTIVE,
        /** The source is ready and says {@code null}: the target shows its default. */
        NULL,
        LOADING,
        /** No such source or member right now. */
        MISSING,
        /** The source reported an error. */
        FAILED,
        /** The value, converter or target does not fit; reported as a diagnostic. */
        INVALID,
        /** {@code to-source}, or a {@code once} binding that already applied. */
        WRITE_ONLY
    }

    static final BindingStatus LOADING = new BindingStatus(State.LOADING, "");
    static final BindingStatus MISSING = new BindingStatus(State.MISSING, "");

    static BindingStatus of(State s, String detail) {
        return new BindingStatus(s, detail == null ? "" : detail);
    }
}
