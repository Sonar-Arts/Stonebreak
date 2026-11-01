package com.openmason.engine.ui.data;

import java.util.Objects;

/**
 * A call that does not fit the host contract (#289). Thrown synchronously to the caller (a
 * binding, a script, a graph) for problems with the call itself; a result that breaks its
 * schema fails the {@link ActionCall} with this as its error. The message always carries the
 * document and node of the call site. A data read or watch of a root the document does not
 * declare (#327) is a {@link Code#CAPABILITY_MISSING} too; {@link #actionId} is then the path.
 */
public final class UiActionException extends RuntimeException {

    public enum Code {
        UNKNOWN_ACTION,
        /**
         * The action (or data root) exists but its contract is not declared by the document, or
         * not at the version the action needs.
         */
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
