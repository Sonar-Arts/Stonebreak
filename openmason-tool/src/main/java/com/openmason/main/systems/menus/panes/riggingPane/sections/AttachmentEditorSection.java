package com.openmason.main.systems.menus.panes.riggingPane.sections;

import com.openmason.engine.format.omo.OMOFormat;
import com.openmason.engine.rendering.model.gmr.parts.ModelPartDescriptor;
import com.openmason.engine.rendering.model.gmr.parts.ModelPartManager;
import com.openmason.main.systems.skeleton.AttachmentStore;
import com.openmason.main.systems.themes.utils.ImGuiComponents;
import com.openmason.main.systems.themes.utils.TransformGroupWidget;
import imgui.ImGui;
import imgui.type.ImFloat;
import imgui.type.ImString;

/**
 * Inspector for the currently-selected attachment point (socket). Uses the
 * shared {@link TransformGroupWidget} so the socket transform UI matches the
 * bone inspector style. Position/rotation/scale are authored in rest-pose
 * model space — the socket translates, rotates, and scales the model attached
 * to it at runtime. The parent-part combo sets the runtime animation binding.
 */
public class AttachmentEditorSection {

    private AttachmentStore attachmentStore;
    private ModelPartManager partManager;
    private final ImString nameBuffer = new ImString(64);
    private final ImFloat posX = new ImFloat(), posY = new ImFloat(), posZ = new ImFloat();
    private final ImFloat rotX = new ImFloat(), rotY = new ImFloat(), rotZ = new ImFloat();
    private final ImFloat sclX = new ImFloat(), sclY = new ImFloat(), sclZ = new ImFloat();
    private String lastSyncedId;
    private Runnable onAttachmentsChanged;

    public void setAttachmentStore(AttachmentStore store) { this.attachmentStore = store; }
    public void setPartManager(ModelPartManager partManager) { this.partManager = partManager; }
    public void setOnAttachmentsChanged(Runnable cb) { this.onAttachmentsChanged = cb; }

    /**
     * Render the inspector for the given socket id (may be null).
     */
    public void render(String selectedAttachmentId) {
        if (attachmentStore == null) return;

        ImGuiComponents.renderCompactSectionHeader("Socket Inspector");
        ImGui.spacing();

        OMOFormat.AttachmentPointEntry socket =
                selectedAttachmentId == null ? null : attachmentStore.getById(selectedAttachmentId);
        if (socket == null) {
            ImGui.textDisabled("Select a socket in the hierarchy");
            return;
        }

        syncIfChanged(socket);

        if (ImGui.inputText("Name", nameBuffer)) {
            String newName = nameBuffer.get().trim();
            if (!newName.isEmpty() && !newName.equals(socket.name())) {
                replace(new OMOFormat.AttachmentPointEntry(
                        socket.id(), newName, socket.parentPartId(), socket.parentPartName(),
                        socket.posX(), socket.posY(), socket.posZ(),
                        socket.rotX(), socket.rotY(), socket.rotZ(),
                        socket.scaleX(), socket.scaleY(), socket.scaleZ()));
            }
        }

        ImGui.spacing();
        renderParentCombo(socket);
        ImGui.spacing();

        boolean changed = false;
        changed |= TransformGroupWidget.render("Position", "socket_pos",
                posX, posY, posZ, 0.05f, "%.3f");
        ImGui.spacing();
        changed |= TransformGroupWidget.render("Rotation", "socket_rot",
                rotX, rotY, rotZ, 0.5f, "%.1f");
        ImGui.spacing();
        // Scale of the ATTACHED model, not of the marker — 1 leaves it unchanged.
        changed |= TransformGroupWidget.render("Scale", "socket_scl",
                sclX, sclY, sclZ, 0.02f, "%.3f");

        if (changed) {
            replace(new OMOFormat.AttachmentPointEntry(
                    socket.id(), socket.name(), socket.parentPartId(), socket.parentPartName(),
                    posX.get(), posY.get(), posZ.get(),
                    rotX.get(), rotY.get(), rotZ.get(),
                    sclX.get(), sclY.get(), sclZ.get()));
        }

        // Stable UUID — what undo/selection track and the attach_* MCP tools
        // accept. The NAME (above) is the runtime lookup key.
        ImGui.spacing();
        ImGui.textDisabled("ID: " + socket.id());
        ImGui.sameLine();
        if (ImGui.smallButton("Copy")) {
            ImGui.setClipboardText(socket.id());
        }
        if (ImGui.isItemHovered()) {
            ImGui.setTooltip("Copy the socket's UUID to the clipboard");
        }
    }

    /** Parent-part combo: "(model root)" or any part; sets the runtime binding. */
    private void renderParentCombo(OMOFormat.AttachmentPointEntry socket) {
        String currentLabel = socket.isModelRoot()
                ? "(model root)"
                : (socket.parentPartName() != null ? socket.parentPartName() : socket.parentPartId());
        if (ImGui.beginCombo("Parent Part", currentLabel)) {
            if (ImGui.selectable("(model root)", socket.isModelRoot())) {
                replace(new OMOFormat.AttachmentPointEntry(
                        socket.id(), socket.name(), null, null,
                        socket.posX(), socket.posY(), socket.posZ(),
                        socket.rotX(), socket.rotY(), socket.rotZ(),
                        socket.scaleX(), socket.scaleY(), socket.scaleZ()));
            }
            if (partManager != null) {
                for (ModelPartDescriptor part : partManager.getAllParts()) {
                    boolean selected = part.id().equals(socket.parentPartId());
                    if (ImGui.selectable(part.name(), selected)) {
                        replace(new OMOFormat.AttachmentPointEntry(
                                socket.id(), socket.name(), part.id(), part.name(),
                                socket.posX(), socket.posY(), socket.posZ(),
                                socket.rotX(), socket.rotY(), socket.rotZ(),
                                socket.scaleX(), socket.scaleY(), socket.scaleZ()));
                    }
                }
            }
            ImGui.endCombo();
        }
    }

    /** Pulls fresh values from the store when the selected socket changes. */
    private void syncIfChanged(OMOFormat.AttachmentPointEntry socket) {
        if (socket.id().equals(lastSyncedId)) return;
        nameBuffer.set(socket.name());
        posX.set(socket.posX());   posY.set(socket.posY());   posZ.set(socket.posZ());
        rotX.set(socket.rotX());   rotY.set(socket.rotY());   rotZ.set(socket.rotZ());
        sclX.set(socket.scaleX()); sclY.set(socket.scaleY()); sclZ.set(socket.scaleZ());
        lastSyncedId = socket.id();
    }

    private void replace(OMOFormat.AttachmentPointEntry entry) {
        attachmentStore.put(entry);
        lastSyncedId = null; // Force re-sync on next frame so subsequent edits pick up latest values.
        if (onAttachmentsChanged != null) onAttachmentsChanged.run();
    }
}
