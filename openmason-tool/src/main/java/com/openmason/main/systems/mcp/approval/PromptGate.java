package com.openmason.main.systems.mcp.approval;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Duration;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Function;

/**
 * Single-slot "ask the human" gate shared by the approval dialog and the Save
 * Sheet.
 *
 * <p>One question at a time: a second request while one is pending resolves
 * with the busy answer immediately (the MCP caller gets a structured result
 * and retries later). Timeouts are enforced by the render-side countdown and
 * by a belt-and-braces check in {@link #pending()} readers. Callers block on
 * the returned stage from a background thread, never from inside a
 * {@code MainThreadExecutor} task — the dialog that answers renders there.
 *
 * @param <Q> the question
 * @param <A> the answer
 */
public class PromptGate<Q, A> {

    private static final Logger logger = LoggerFactory.getLogger(PromptGate.class);

    /** A live question waiting for the user. */
    public record Pending<Q, A>(Q request, long deadlineMillis, CompletableFuture<A> future) {
    }

    private final String label;
    private final Function<Q, Duration> timeoutOf;
    private final A busyAnswer;
    private final A timeoutAnswer;
    private final A shutdownAnswer;
    private final AtomicReference<Pending<Q, A>> pending = new AtomicReference<>();

    protected PromptGate(String label, Function<Q, Duration> timeoutOf,
                         A busyAnswer, A timeoutAnswer, A shutdownAnswer) {
        this.label = label;
        this.timeoutOf = timeoutOf;
        this.busyAnswer = busyAnswer;
        this.timeoutAnswer = timeoutAnswer;
        this.shutdownAnswer = shutdownAnswer;
    }

    /**
     * Ask the user. Completes with the answer; completes with the busy answer
     * immediately when another question is already pending.
     */
    public CompletionStage<A> request(Q question) {
        CompletableFuture<A> future = new CompletableFuture<>();
        long deadline = System.currentTimeMillis() + timeoutOf.apply(question).toMillis();
        Pending<Q, A> candidate = new Pending<>(question, deadline, future);
        if (!pending.compareAndSet(null, candidate)) {
            future.complete(busyAnswer);
            return future;
        }
        logger.info("{} requested: {}", label, question);
        // Whatever completes the future (dialog buttons, timeout sweep,
        // shutdown) frees the slot.
        future.whenComplete((d, t) -> pending.compareAndSet(candidate, null));
        return future;
    }

    /** The live question, or null. Expires overdue questions as a side effect. */
    public Pending<Q, A> pending() {
        Pending<Q, A> p = pending.get();
        if (p != null && System.currentTimeMillis() > p.deadlineMillis()) {
            p.future().complete(timeoutAnswer);
            return null;
        }
        return p;
    }

    /** Seconds left on the live question, or -1 when none is pending. */
    public long secondsRemaining() {
        Pending<Q, A> p = pending();
        return p == null ? -1 : Math.max(0, p.deadlineMillis() - System.currentTimeMillis()) / 1000;
    }

    /** Resolve the current question (no-op when none or already resolved). */
    public void resolve(A answer) {
        Pending<Q, A> p = pending.get();
        if (p != null) {
            p.future().complete(answer);
        }
    }

    /** Deny anything outstanding (app shutdown). */
    public void shutdown() {
        resolve(shutdownAnswer);
    }
}
