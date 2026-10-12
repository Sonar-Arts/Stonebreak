package com.openmason.engine.ui.data;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.concurrent.ConcurrentLinkedQueue;

/**
 * Work handed to the UI thread (#289). Data sources, scopes and binding updates are confined
 * to the UI thread; a server thread, a network handler or an async action completion
 * {@link #post}s here and the host {@link #drain}s once per frame (the editor's fixture host
 * drains when a test or the preview says so, which keeps it deterministic).
 *
 * <p>The thread that drains is the UI thread: from the first drain on, UI-thread-only writes
 * ({@link DataCell#setState}, {@link DataCollection} edits) made from any other thread fail
 * loudly with {@link #checkOwner} instead of silently racing the listener lists.
 */
public final class UiThreadQueue {

    private static final Logger LOGGER = LoggerFactory.getLogger(UiThreadQueue.class);

    /**
     * Rounds of follow-up work one drain runs: tasks posted by a drained task run in the same
     * drain (an async completion that settles on the UI thread), but a task that keeps
     * re-posting itself is cut off and continues next frame instead of livelocking the frame.
     */
    static final int MAX_ROUNDS = 8;

    private final ConcurrentLinkedQueue<Runnable> tasks = new ConcurrentLinkedQueue<>();
    private volatile Thread owner;
    private boolean warnedLivelock;

    /** Safe from any thread. */
    public void post(Runnable task) {
        tasks.add(task);
    }

    /**
     * Runs everything posted so far, plus what those tasks post in turn (up to
     * {@value #MAX_ROUNDS} rounds; the rest waits for the next drain), on the calling (UI)
     * thread. A failing task is logged and does not stop the rest.
     *
     * @return tasks run
     */
    public int drain() {
        Thread current = Thread.currentThread();
        if (owner != current) {
            owner = current;
        }
        int n = 0;
        for (int round = 0; round < MAX_ROUNDS && !tasks.isEmpty(); round++) {
            int batch = tasks.size();
            for (int i = 0; i < batch; i++) {
                Runnable r = tasks.poll();
                if (r == null) {
                    break;
                }
                n++;
                try {
                    r.run();
                } catch (RuntimeException e) {
                    LOGGER.error("UI task failed", e);
                }
            }
        }
        if (!tasks.isEmpty() && !warnedLivelock) {
            warnedLivelock = true;
            LOGGER.warn("UI tasks still re-posting after {} rounds in one drain; the rest runs next frame", MAX_ROUNDS);
        }
        return n;
    }

    public boolean isEmpty() {
        return tasks.isEmpty();
    }

    /** The thread that drains (the UI thread), or {@code null} before the first drain. */
    public Thread owner() {
        return owner;
    }

    /**
     * Fails when called off the UI thread once one is known.
     *
     * @param what the operation, for the message
     * @throws IllegalStateException on another thread
     */
    public void checkOwner(String what) {
        Thread o = owner;
        if (o != null && o != Thread.currentThread()) {
            throw new IllegalStateException(what + " on thread " + Thread.currentThread().getName()
                + "; UI data is confined to " + o.getName() + " (post() from other threads)");
        }
    }
}
