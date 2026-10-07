package com.openmason.engine.ui.data;

import java.util.ArrayList;
import java.util.List;

/**
 * What a running action can see about its own call (#289): where it came from and whether the
 * UI still wants the result. Cancellation is cooperative: a cancellable handler polls
 * {@link #isCancelled()} or registers {@link #onCancel} hooks; a non-cancellable one runs to
 * completion and the UI just drops the result.
 */
public final class ActionContext {

    private final CallSite site;
    private final long generation;
    private final List<Runnable> cancelHooks = new ArrayList<>();
    private volatile boolean cancelled;

    ActionContext(CallSite site, long generation) {
        this.site = site;
        this.generation = generation;
    }

    public CallSite site() {
        return site;
    }

    /** The scope generation the call was made in. */
    public long generation() {
        return generation;
    }

    public boolean isCancelled() {
        return cancelled;
    }

    /** Runs {@code hook} on the UI thread if the call is cancelled (immediately if it already was). */
    public void onCancel(Runnable hook) {
        if (cancelled) {
            hook.run();
        } else {
            cancelHooks.add(hook);
        }
    }

    /** @return hooks that threw, for the caller to report; never throws itself */
    List<RuntimeException> cancel() {
        cancelled = true;
        List<RuntimeException> failures = new ArrayList<>();
        for (Runnable r : cancelHooks) {
            try {
                r.run();
            } catch (RuntimeException e) {
                failures.add(e);
            }
        }
        cancelHooks.clear();
        return failures;
    }
}
