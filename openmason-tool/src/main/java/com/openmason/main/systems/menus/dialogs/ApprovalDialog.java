package com.openmason.main.systems.menus.dialogs;

import com.openmason.main.systems.mcp.approval.ApprovalGate;
import com.openmason.main.systems.mcp.approval.McpApprovalGate;
import imgui.ImGui;
import imgui.flag.ImGuiCond;
import imgui.flag.ImGuiWindowFlags;

/**
 * Modal front-end for {@link McpApprovalGate}: an agent (MCP client or the
 * assistant) asked to do something that replaces the user's working set, and
 * the user must approve it. Rendered every frame at app level (same pattern as
 * {@code UnsavedChangesDialog}).
 */
public final class ApprovalDialog {

    private static final String POPUP_ID = "Agent request##approvalDialog";

    private final McpApprovalGate gate;
    private final Runnable saveCurrentModel; // nullable — "save first" button when dirty
    private final java.util.function.BooleanSupplier hasUnsavedChanges;

    public ApprovalDialog(McpApprovalGate gate, Runnable saveCurrentModel,
                          java.util.function.BooleanSupplier hasUnsavedChanges) {
        this.gate = gate;
        this.saveCurrentModel = saveCurrentModel;
        this.hasUnsavedChanges = hasUnsavedChanges;
    }

    public void render() {
        McpApprovalGate.Pending pending = gate.pending();
        if (pending == null) {
            return;
        }
        if (!ImGui.isPopupOpen(POPUP_ID)) {
            ImGui.openPopup(POPUP_ID);
        }
        ImGui.setNextWindowSize(520, 0, ImGuiCond.Appearing);
        if (!ImGui.beginPopupModal(POPUP_ID,
                ImGuiWindowFlags.AlwaysAutoResize | ImGuiWindowFlags.NoSavedSettings)) {
            return;
        }
        ApprovalGate.ApprovalRequest req = pending.request();
        ImGui.textWrapped(req.title());
        ImGui.spacing();
        for (String line : req.detailLines()) {
            ImGui.bulletText(line);
        }
        boolean dirty = hasUnsavedChanges != null && hasUnsavedChanges.getAsBoolean();
        if (dirty) {
            ImGui.spacing();
            ImGui.textColored(1.0f, 0.65f, 0.2f, 1.0f,
                    "The current model has unsaved changes and will be replaced.");
        }
        long remaining = Math.max(0, pending.deadlineMillis() - System.currentTimeMillis()) / 1000;
        ImGui.spacing();
        ImGui.textDisabled("Times out in " + remaining + "s");
        ImGui.separator();

        if (ImGui.button("Approve")) {
            gate.resolve(ApprovalGate.Decision.APPROVED);
            ImGui.closeCurrentPopup();
        }
        if (dirty && saveCurrentModel != null) {
            ImGui.sameLine();
            if (ImGui.button("Save current first, then approve")) {
                saveCurrentModel.run();
                gate.resolve(ApprovalGate.Decision.APPROVED);
                ImGui.closeCurrentPopup();
            }
        }
        ImGui.sameLine();
        if (ImGui.button("Decline")) {
            gate.resolve(ApprovalGate.Decision.DECLINED);
            ImGui.closeCurrentPopup();
        }
        ImGui.endPopup();
    }
}
