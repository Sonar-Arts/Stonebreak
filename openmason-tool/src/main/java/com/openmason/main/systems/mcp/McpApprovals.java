package com.openmason.main.systems.mcp;

import com.openmason.main.systems.mcp.approval.ApprovalGate;

import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/**
 * Blocking "may I replace the user's working set?" question over the
 * {@link ApprovalGate}, shared by every tool that needs it. Returns a
 * structured decline map, or null when the user approved.
 */
final class McpApprovals {

    private McpApprovals() {
    }

    static Map<String, Object> confirm(ApprovalGate gate, String action, String title,
                                       List<String> details, int timeoutSeconds) {
        if (gate == null) {
            return declined("approval_unavailable",
                    "the Open Mason UI is not running — this needs a human to approve");
        }
        int timeout = timeoutSeconds <= 0 ? 60 : Math.min(timeoutSeconds, 240);
        ApprovalGate.Decision decision;
        try {
            decision = gate.request(new ApprovalGate.ApprovalRequest(action, title,
                            new ArrayList<>(details), Duration.ofSeconds(timeout)))
                    .toCompletableFuture().get(timeout + 5L, TimeUnit.SECONDS);
        } catch (TimeoutException e) {
            return declined("timeout", "no user decision within " + timeout + "s");
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return declined("interrupted", "approval wait interrupted");
        } catch (ExecutionException e) {
            return declined("error", "approval failed: " + e.getCause());
        }
        return switch (decision) {
            case APPROVED -> null;
            case BUSY -> declined("busy",
                    "another confirmation dialog is already open in Open Mason — retry shortly");
            case DECLINED -> declined("user_declined", "the user declined the request");
            case TIMEOUT -> declined("timeout", "the request timed out without a user decision");
        };
    }

    static Map<String, Object> declined(String reason, String message) {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("opened", false);
        out.put("reason", reason);
        out.put("message", message);
        return out;
    }
}
