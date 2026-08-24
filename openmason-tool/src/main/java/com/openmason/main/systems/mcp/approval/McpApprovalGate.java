package com.openmason.main.systems.mcp.approval;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Single-slot approval gate backed by the in-tool {@code ApprovalDialog}.
 *
 * <p>One question at a time: a second request while one is pending resolves
 * {@code BUSY} immediately (the MCP caller gets a structured busy result and
 * retries later). Timeouts are enforced by the render-side countdown and by a
 * belt-and-braces check in {@link #pending()} readers.
 */
public final class McpApprovalGate implements ApprovalGate {

    private static final Logger logger = LoggerFactory.getLogger(McpApprovalGate.class);

    /** A live question waiting for the user. */
    public record Pending(ApprovalRequest request, long deadlineMillis,
                          CompletableFuture<Decision> future) {
    }

    private final AtomicReference<Pending> pending = new AtomicReference<>();

    @Override
    public CompletionStage<Decision> request(ApprovalRequest request) {
        CompletableFuture<Decision> future = new CompletableFuture<>();
        long deadline = System.currentTimeMillis() + request.timeout().toMillis();
        Pending candidate = new Pending(request, deadline, future);
        if (!pending.compareAndSet(null, candidate)) {
            future.complete(Decision.BUSY);
            return future;
        }
        logger.info("Approval requested: {} ({})", request.action(), request.title());
        // Whatever completes the future (dialog buttons, timeout sweep,
        // shutdown) frees the slot.
        future.whenComplete((d, t) -> pending.compareAndSet(candidate, null));
        return future;
    }

    /** The live question, or null. Expires overdue requests as a side effect. */
    public Pending pending() {
        Pending p = pending.get();
        if (p != null && System.currentTimeMillis() > p.deadlineMillis()) {
            p.future().complete(Decision.TIMEOUT);
            return null;
        }
        return p;
    }

    /** Resolve the current question (no-op when none or already resolved). */
    public void resolve(Decision decision) {
        Pending p = pending.get();
        if (p != null) {
            p.future().complete(decision);
        }
    }

    /** Deny anything outstanding (app shutdown). */
    public void shutdown() {
        resolve(Decision.DECLINED);
    }
}
