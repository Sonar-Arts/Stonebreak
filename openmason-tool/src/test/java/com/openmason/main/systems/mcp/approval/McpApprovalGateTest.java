package com.openmason.main.systems.mcp.approval;

import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.List;
import java.util.concurrent.CompletionStage;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

class McpApprovalGateTest {

    private static ApprovalGate.ApprovalRequest request(long timeoutMs) {
        return new ApprovalGate.ApprovalRequest("asset_open", "Open X?",
                List.of("detail"), Duration.ofMillis(timeoutMs));
    }

    @Test
    void approveResolvesAndFreesSlot() throws Exception {
        McpApprovalGate gate = new McpApprovalGate();
        CompletionStage<ApprovalGate.Decision> stage = gate.request(request(60_000));
        assertNotNull(gate.pending());
        gate.resolve(ApprovalGate.Decision.APPROVED);
        assertEquals(ApprovalGate.Decision.APPROVED, stage.toCompletableFuture().get());
        assertNull(gate.pending(), "slot must free after resolution");
    }

    @Test
    void declineResolves() throws Exception {
        McpApprovalGate gate = new McpApprovalGate();
        CompletionStage<ApprovalGate.Decision> stage = gate.request(request(60_000));
        gate.resolve(ApprovalGate.Decision.DECLINED);
        assertEquals(ApprovalGate.Decision.DECLINED, stage.toCompletableFuture().get());
    }

    @Test
    void secondRequestIsBusy() throws Exception {
        McpApprovalGate gate = new McpApprovalGate();
        gate.request(request(60_000));
        CompletionStage<ApprovalGate.Decision> second = gate.request(request(60_000));
        assertEquals(ApprovalGate.Decision.BUSY, second.toCompletableFuture().get());
        assertNotNull(gate.pending(), "first request must still be pending");
    }

    @Test
    void overdueRequestTimesOutOnRead() throws Exception {
        McpApprovalGate gate = new McpApprovalGate();
        CompletionStage<ApprovalGate.Decision> stage = gate.request(request(1));
        Thread.sleep(20);
        assertNull(gate.pending(), "expired request must not render");
        assertEquals(ApprovalGate.Decision.TIMEOUT, stage.toCompletableFuture().get());
    }

    @Test
    void shutdownDeclinesPending() throws Exception {
        McpApprovalGate gate = new McpApprovalGate();
        CompletionStage<ApprovalGate.Decision> stage = gate.request(request(60_000));
        gate.shutdown();
        assertEquals(ApprovalGate.Decision.DECLINED, stage.toCompletableFuture().get());
    }
}
