package com.openmason.engine.ui.data;

import java.util.Objects;

/**
 * Something a scope noticed at run time that an author or host developer should see (#289):
 * a failed or mistyped action result, a callback that threw, a completion that arrived for a
 * screen, world or document revision that no longer exists. The runtime turns these into
 * element diagnostics; they never throw into the frame.
 */
public record UiProblem(Kind kind, CallSite site, String message) {

    public enum Kind {
        ACTION_FAILED,
        RESULT_MISMATCH,
        CALLBACK_FAILED,
        CANCEL_HOOK_FAILED,
        /** A completion for a closed scope, an older document revision or an earlier world; dropped. */
        STALE_COMPLETION,
        INVALID_EDIT
    }

    public UiProblem {
        Objects.requireNonNull(kind, "kind");
        Objects.requireNonNull(site, "site");
        Objects.requireNonNull(message, "message");
    }

    @Override
    public String toString() {
        return kind + " at " + site.describe() + ": " + message;
    }
}
