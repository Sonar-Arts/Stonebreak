package com.openmason.main.systems.mcp.approval;

/**
 * Single-slot approval gate backed by the in-tool {@code ApprovalDialog}: a
 * {@link PromptGate} whose answer is a plain {@link Decision}.
 */
public final class McpApprovalGate extends PromptGate<ApprovalGate.ApprovalRequest, ApprovalGate.Decision>
        implements ApprovalGate {

    public McpApprovalGate() {
        super("Approval", ApprovalRequest::timeout,
                Decision.BUSY, Decision.TIMEOUT, Decision.DECLINED);
    }
}
