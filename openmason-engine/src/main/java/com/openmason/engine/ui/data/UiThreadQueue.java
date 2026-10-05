package com.openmason.engine.ui.data;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.concurrent.ConcurrentLinkedQueue;

/**
 * Work handed to the UI thread (#289). Data sources, scopes and binding updates are confined
 * to the UI thread; a server thread, a network handler or an async action completion
 * {@link #post}s here and the host {@link #drain}s once per frame (the editor's fixture host
 * drains when a test or the preview says so, which keeps it deterministic).
 */
public final class UiThreadQueue {

    private static final Logger LOGGER = LoggerFactory.getLogger(UiThreadQueue.class);

    private final ConcurrentLinkedQueue<Runnable> tasks = new ConcurrentLinkedQueue<>();

    /** Safe from any thread. */
    public void post(Runnable task) {
        tasks.add(task);
    }

    /**
     * Runs everything posted so far, plus anything those tasks post in turn, on the calling
     * (UI) thread. A failing task is logged and does not stop the rest.
     *
     * @return tasks run
     */
    public int drain() {
        int n = 0;
        Runnable r;
        while ((r = tasks.poll()) != null) {
            n++;
            try {
                r.run();
            } catch (RuntimeException e) {
                LOGGER.error("UI task failed", e);
            }
        }
        return n;
    }

    public boolean isEmpty() {
        return tasks.isEmpty();
    }
}
