package com.openmason.engine.ui.data;

import java.util.Map;
import java.util.Objects;
import java.util.regex.Pattern;

/**
 * The contract of one host action (#289): a namespaced id, parameter and result schemas, the
 * host contract (capability) and version that introduced it, and how it behaves when invoked
 * again while a previous call is still pending.
 *
 * <p><b>Threading:</b> the handler is invoked on the UI thread and must not block; completion
 * may happen on any thread and is delivered back on the UI thread. <b>Cancellation:</b> closing
 * the scope, reloading the document or leaving the world cancels pending calls; a
 * {@link #cancellable} handler is told (its cancel hooks run), any other keeps running and its
 * result is dropped.
 *
 * @param id          {@code ns:dotted.name} ({@code stonebreak:network.resync})
 * @param contract    capability the action belongs to; its version must be at least {@code since}
 * @param since       contract version that introduced the action
 * @param params      argument object schema; unknown or mistyped arguments fail the call
 * @param result      result schema, checked when the call completes
 * @param reentrancy  duplicate-click policy, per scope
 * @param cancellable whether the handler honours cancellation
 */
public record ActionSpec(String id, HostContract contract, int since, DataType.Obj params, DataType result,
                         Reentrancy reentrancy, boolean cancellable) {

    private static final Pattern ID = Pattern.compile("[a-z0-9_-]+:[a-z0-9_-]+(\\.[a-z0-9_-]+)*");

    /** What a second invocation does while one is pending in the same scope. */
    public enum Reentrancy {
        /** Refused: the duplicate returns a {@code REJECTED} call and the handler is not run (button mashing). */
        REJECT_WHILE_PENDING,
        /** The pending call is cancelled and the new one runs (latest search wins). */
        CANCEL_PREVIOUS,
        /** Calls are independent. */
        PARALLEL
    }

    public ActionSpec {
        Objects.requireNonNull(id, "id");
        if (!ID.matcher(id).matches()) {
            throw new IllegalArgumentException("action id must be ns:dotted.name: " + id);
        }
        Objects.requireNonNull(contract, "contract");
        if (since < 1 || since > contract.version()) {
            throw new IllegalArgumentException(id + ": since " + since + " outside contract " + contract);
        }
        params = params == null ? DataType.object(Map.of()) : params;
        result = result == null ? DataType.ANY : result;
        reentrancy = reentrancy == null ? Reentrancy.REJECT_WHILE_PENDING : reentrancy;
    }

    /** A synchronous-style action with the default duplicate-click policy, introduced in version 1. */
    public static ActionSpec of(String id, HostContract contract, DataType.Obj params, DataType result) {
        return new ActionSpec(id, contract, 1, params, result, Reentrancy.REJECT_WHILE_PENDING, false);
    }

    public ActionSpec withReentrancy(Reentrancy r) {
        return new ActionSpec(id, contract, since, params, result, r, cancellable);
    }

    public ActionSpec withCancellable(boolean c) {
        return new ActionSpec(id, contract, since, params, result, reentrancy, c);
    }
}
