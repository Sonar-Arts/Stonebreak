package com.openmason.main.systems.threading;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.concurrent.Callable;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Executes runnables on the GL/main thread.
 *
 * <p>The main loop calls {@link #drain()} once per frame to run pending tasks.
 * Off-thread callers use {@link #submit(Callable)} and await the returned future
 * to get a value computed on the main thread (required for any OpenGL or
 * mesh-mutating work).
 */
public final class MainThreadExecutor {

    private static final Logger logger = LoggerFactory.getLogger(MainThreadExecutor.class);

    private static final ConcurrentLinkedQueue<Runnable> queue = new ConcurrentLinkedQueue<>();
    private static volatile Thread mainThread;

    private MainThreadExecutor() {}

    /**
     * Mark the calling thread as the main thread. Call once at app startup.
     */
    public static void bindToCurrentThread() {
        mainThread = Thread.currentThread();
    }

    /**
     * Run all pending tasks on the current (main) thread. Called per-frame.
     */
    public static void drain() {
        Runnable task;
        while ((task = queue.poll()) != null) {
            try {
                task.run();
            } catch (Throwable t) {
                logger.error("Main-thread task threw", t);
            }
        }
    }

    /**
     * Submit a task that returns a value, to be executed on the main thread.
     * If called from the main thread, runs synchronously and returns a completed future.
     */
    public static <T> CompletableFuture<T> submit(Callable<T> task) {
        if (mainThread != null && Thread.currentThread() == mainThread) {
            try {
                return CompletableFuture.completedFuture(task.call());
            } catch (Throwable t) {
                CompletableFuture<T> failed = new CompletableFuture<>();
                failed.completeExceptionally(t);
                return failed;
            }
        }

        CompletableFuture<T> future = new CompletableFuture<>();
        queue.offer(() -> {
            try {
                future.complete(task.call());
            } catch (Throwable t) {
                future.completeExceptionally(t);
            }
        });
        return future;
    }

    /**
     * Submit a fire-and-forget task to the main thread.
     */
    public static void post(Runnable task) {
        queue.offer(task);
    }
}
