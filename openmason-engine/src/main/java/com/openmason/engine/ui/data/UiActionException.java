package com.openmason.engine.ui.data;

import java.util.Objects;

/**
 * A call that does not fit the host contract (#289). Thrown synchronously to the caller (a
 * binding, a script, a graph) for problems with the call itself; a result that breaks its
 * schema fails the {@link ActionCall} with this as its error. The message always carries the
 * document and node of the call site.
 */
public final class UiActionException extends RuntimeException {

    public enum Code {
        UNKNOWN_ACTION,
        /** The action exists but its contract is not offered at the version the document needs. */
        CAPABILITY_MISSING,
        PARAM_MISMATCH,
        RESULT_MISMATCH,
        SCOPE_CLOSED
    }

    private final Code code;
    private final CallSite site;
    private final String actionId;

    public UiActionException(Code code, CallSite site, String actionId, String detail) {
        super(code + " " + actionId + " at " + site.describe() + ": " + detail);
        this.code = Objects.requireNonNull(code, "code");
        this.site = site;
        this.actionId = actionId;
    }

    public Code code() {
        return code;
    }

    public CallSite site() {
        return site;
    }

    public String actionId() {
        return actionId;
    }
}
