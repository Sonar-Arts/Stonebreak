package com.openmason.engine.net.server;

import java.util.ArrayList;
import java.util.List;

/**
 * Defers the flushes of one thread's sends until it ends the batch (issue #346): the tick
 * writes every packet of a tick, then flushes each connection it wrote to once. With
 * {@code TCP_NODELAY} on, a flush per packet put every small packet in its own TCP segment
 * and its own event-loop flush.
 *
 * <p>Only sends made on the thread that {@link #begin began} the batch are deferred; every
 * other thread (a load completing on a worker, a kick from the event loop) still flushes at
 * once, so nothing is stranded unflushed when the owner is not ticking. The pending list is
 * touched by the owner alone.
 */
public final class OutboundBatch {

    private volatile Thread owner;
    private final List<ServerConnection> pending = new ArrayList<>();

    /** Starts deferring the calling thread's flushes. */
    public void begin() {
        owner = Thread.currentThread();
    }

    /** Flushes every connection written to since the last flush; the batch stays open. */
    public void flush() {
        if (Thread.currentThread() != owner) {
            return;
        }
        for (int i = 0, n = pending.size(); i < n; i++) {
            pending.get(i).flushDeferred();
        }
        pending.clear();
    }

    /** Flushes and stops deferring. */
    public void end() {
        flush();
        if (Thread.currentThread() == owner) {
            owner = null;
        }
    }

    /** True while sends on the calling thread are deferred. */
    boolean defersCurrentThread() {
        return owner == Thread.currentThread();
    }

    /** {@code c} has deferred writes (called once per connection per flush, by the owner). */
    void enlist(ServerConnection c) {
        pending.add(c);
    }
}
