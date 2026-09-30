package com.openmason.main.systems.menus.dialogs;

import com.openmason.main.systems.mcp.approval.ApprovalGate;
import com.openmason.main.systems.mcp.approval.McpApprovalGate;
import imgui.ImGui;
import com.openmason.main.systems.themes.utils.ThemeColors;
import com.openmason.main.systems.themes.utils.ThemedWidgets;

/**
 * Modal front-end for {@link McpApprovalGate}: an agent (MCP client or the
 * assistant) asked to do something that replaces the user's working set, and
 * the user must approve it. Rendered every frame at app level (same pattern as
 * {@code UnsavedChangesDialog}).
 */
public final class ApprovalDialog {

    private static final String POPUP_ID = "Agent Request##approvalDialog";

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
        var pending = gate.pending();
        if (pending == null) {
            return;
        }
        if (!ImGui.isPopupOpen(POPUP_ID)) {
            ImGui.openPopup(POPUP_ID);
        }
        if (!ModalDialogs.begin(POPUP_ID, 520)) {
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
            ThemedWidgets.statusTextWrapped(ThemeColors.Tone.WARNING,
                    "The current model has unsaved changes and will be replaced.");
        }
        long remaining = Math.max(0, pending.deadlineMillis() - System.currentTimeMillis()) / 1000;
        ImGui.spacing();
        ImGui.textDisabled("Times out in " + remaining + "s");

        ModalDialogs.buttonsBegin();
        // Approving an agent action takes a deliberate click — Enter never approves
        if (ModalDialogs.primaryClickOnly("Approve")) {
            gate.resolve(ApprovalGate.Decision.APPROVED);
            ImGui.closeCurrentPopup();
        }
        if (dirty && saveCurrentModel != null) {
            if (ModalDialogs.secondary("Save Current First, Then Approve")) {
                saveCurrentModel.run();
                gate.resolve(ApprovalGate.Decision.APPROVED);
                ImGui.closeCurrentPopup();
            }
        }
        if (ModalDialogs.cancel("Deny")) {
            gate.resolve(ApprovalGate.Decision.DECLINED);
            ImGui.closeCurrentPopup();
        }
        ModalDialogs.end();
    }
}
