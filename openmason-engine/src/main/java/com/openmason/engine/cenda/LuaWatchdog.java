package com.openmason.engine.cenda;

import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.LockSupport;

/**
 * Wall-clock deadline for Lua callbacks. A daemon thread samples each watched
 * state's {@link LuaState#watchToken()}; a call still running after the
 * deadline is interrupted and ends with {@link LuaState#ERR_DEADLINE}.
 *
 * <p>Costs the UI thread nothing per call (no hook, no extra downcall): the VM
 * only compares two words at loop back-jumps and calls. Use it as the hang
 * breaker; {@link LuaState#setBudget} remains for exact instruction counts at
 * roughly 2x VM cost.
 */
public final class LuaWatchdog implements AutoCloseable {

    private record Watch(LuaState state, long deadlineNanos, long[] seen) {
    }

    private final CopyOnWriteArrayList<Watch> watches = new CopyOnWriteArrayList<>();
    private final long pollNanos;
    private final Thread thread;
    private final Object sampleLock = new Object();
    private volatile boolean running = true;

    public LuaWatchdog(long pollMicros) {
        this.pollNanos = TimeUnit.MICROSECONDS.toNanos(pollMicros);
        this.thread = Thread.ofPlatform().daemon().name("lua-watchdog").start(this::loop);
    }

    /** Interrupt any single call on {@code state} that runs longer than {@code deadlineMillis}. */
    public void watch(LuaState state, double deadlineMillis) {
        watches.add(new Watch(state, (long) (deadlineMillis * 1e6), new long[]{0L, 0L}));
    }

    /**
     * Stops watching. Must be called before {@code state.close()}: it waits for
     * an in-flight sample, so the watchdog never touches a freed state.
     */
    public void unwatch(LuaState state) {
        synchronized (sampleLock) {
            watches.removeIf(w -> w.state == state);
        }
    }

    private void loop() {
        while (running) {
            long now = System.nanoTime();
            synchronized (sampleLock) {
                for (Watch w : watches) {
                    long token = w.state.watchToken();
                    if (token != w.seen[0]) {
                        w.seen[0] = token;
                        w.seen[1] = now;
                    } else if (token != 0 && now - w.seen[1] > w.deadlineNanos) {
                        w.state.interrupt(token);
                    }
                }
            }
            LockSupport.parkNanos(pollNanos);
        }
    }

    @Override
    public void close() {
        running = false;
        LockSupport.unpark(thread);
        try {
            thread.join(1000);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
