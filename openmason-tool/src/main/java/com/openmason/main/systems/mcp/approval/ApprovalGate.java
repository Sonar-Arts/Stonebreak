package com.openmason.main.systems.mcp.approval;

import java.time.Duration;
import java.util.List;
import java.util.concurrent.CompletionStage;

/**
 * User-authorization seam for agent-initiated actions that replace the user's
 * working set (e.g. {@code asset_open} loading a model over unsaved work).
 *
 * <p>Implementations decide how the human answers — the in-tool modal dialog
 * for plain MCP clients, the assistant pane's inline approval cards for the
 * chat harness. Callers block on the returned stage from a background thread,
 * NEVER from inside a {@code MainThreadExecutor} task.
 */
public interface ApprovalGate {

    /** What the human decided (or failed to decide). */
    enum Decision {
        APPROVED, DECLINED, TIMEOUT, BUSY
    }

    /**
     * One pending question.
     *
     * @param action      machine-readable action id (e.g. "asset_open")
     * @param title       short human title ("Open 'Oak Door' into the editor?")
     * @param detailLines context lines shown in the dialog (paths, warnings)
     * @param timeout     how long the request may stay unanswered
     */
    record ApprovalRequest(String action, String title, List<String> detailLines,
                           Duration timeout) {
    }

    /**
     * Ask the user. Completes with the decision; completes {@code BUSY}
     * immediately when another request is already pending.
     */
    CompletionStage<Decision> request(ApprovalRequest request);
}
