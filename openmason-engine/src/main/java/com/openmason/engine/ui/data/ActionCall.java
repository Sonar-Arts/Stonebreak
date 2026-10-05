package com.openmason.engine.ui.data;

import com.openmason.engine.format.omui.UiValue;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;

/**
 * One invocation of a host action (#289), as the UI sees it: pending, then exactly one final
 * state. Settlement and every callback happen on the UI thread; a callback that throws is
 * reported and never stops the others, the scope or the screen.
 */
public final class ActionCall {

    public enum State {
        PENDING,
        SUCCEEDED,
        FAILED,
        /** Cancelled by the caller, a duplicate click (CANCEL_PREVIOUS), screen close, reload or world change. */
        CANCELLED,
        /** Refused before running: a duplicate click under REJECT_WHILE_PENDING, or a closed scope. */
        REJECTED
    }

    private final ActionSpec spec;
    private final CallSite site;
    private final UiValue.Obj args;
    private final ActionContext context;
    private final UiScope scope;
    private final long epoch;
    private final List<Consumer<ActionCall>> callbacks = new ArrayList<>();
    private State state = State.PENDING;
    private UiValue result;
    private Throwable error;

    ActionCall(ActionSpec spec, CallSite site, UiValue.Obj args, UiScope scope, long generation, long epoch) {
        this.spec = spec;
        this.site = site;
        this.args = args;
        this.scope = scope;
        this.epoch = epoch;
        this.context = new ActionContext(site, generation);
    }

    static ActionCall rejected(ActionSpec spec, CallSite site, UiValue.Obj args, UiScope scope, String why) {
        ActionCall c = new ActionCall(spec, site, args, scope, -1, -1);
        c.state = State.REJECTED;
        c.error = new IllegalStateException(why);
        return c;
    }

    public ActionSpec spec() {
        return spec;
    }

    public CallSite site() {
        return site;
    }

    public UiValue.Obj args() {
        return args;
    }

    public State state() {
        return state;
    }

    public boolean isPending() {
        return state == State.PENDING;
    }

    /** The result once {@link State#SUCCEEDED}, else {@code null}. */
    public UiValue result() {
        return result;
    }

    /** Why the call failed, was rejected or cancelled; {@code null} otherwise. */
    public Throwable error() {
        return error;
    }

    /** Scope generation the call was made in. */
    public long generation() {
        return context.generation();
    }

    /** Host epoch (world/session) the call was made in. */
    public long epoch() {
        return epoch;
    }

    ActionContext context() {
        return context;
    }

    /**
     * Runs {@code callback} on the UI thread when the call settles (immediately when it already
     * has). Callbacks run in registration order.
     */
    public ActionCall whenSettled(Consumer<ActionCall> callback) {
        if (state == State.PENDING) {
            callbacks.add(callback);
        } else {
            runCallback(callback);
        }
        return this;
    }

    /** Cancels a pending call; a no-op once settled. */
    public void cancel() {
        cancel("cancelled by caller");
    }

    void cancel(String why) {
        if (state != State.PENDING) {
            return;
        }
        for (RuntimeException e : context.cancel()) {
            scope.problem(new UiProblem(UiProblem.Kind.CANCEL_HOOK_FAILED, site, spec.id() + ": " + e));
        }
        settle(State.CANCELLED, null, new java.util.concurrent.CancellationException(why));
    }

    void settle(State s, UiValue value, Throwable t) {
        if (state != State.PENDING) {
            return;
        }
        state = s;
        result = value;
        error = t;
        scope.settled(this);
        List<Consumer<ActionCall>> run = new ArrayList<>(callbacks);
        callbacks.clear();
        run.forEach(this::runCallback);
    }

    private void runCallback(Consumer<ActionCall> cb) {
        try {
            cb.accept(this);
        } catch (RuntimeException e) {
            scope.problem(new UiProblem(UiProblem.Kind.CALLBACK_FAILED, site, spec.id() + " callback: " + e));
        }
    }

    @Override
    public String toString() {
        return spec.id() + "[" + state + "]";
    }
}
