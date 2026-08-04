package com.openmason.engine.wayfind;

/**
 * Cooperative cancellation for an in-flight search.
 *
 * <p>The solver polls this every {@value AStar#CANCEL_POLL_INTERVAL} expansions, so cancelling is
 * effectively immediate without putting a volatile read in the inner loop. Callers cancel when the
 * result stopped mattering — the entity died, its goal moved, the world was torn down.
 *
 * <p>One token belongs to one search; it is not reusable once cancelled.
 */
public final class CancelToken {

    /** A token that is never cancelled; shared, allocation-free default. */
    public static final CancelToken NEVER = new CancelToken();

    private volatile boolean cancelled;

    /** Requests cancellation. Safe to call from any thread, any number of times. */
    public void cancel() {
        cancelled = true;
    }

    public boolean isCancelled() {
        return cancelled;
    }
}
