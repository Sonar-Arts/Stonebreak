package com.openmason.main.systems.menus.panes.riggingPane.sections;

import com.openmason.engine.format.omo.OMOFormat;
import com.openmason.main.systems.skeleton.AttachmentStore;
import imgui.ImGui;
import imgui.ImVec4;
import imgui.flag.ImGuiCol;
import imgui.flag.ImGuiTreeNodeFlags;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;
import java.util.function.Consumer;

/**
 * Flat list of every attachment point (socket) on the current model, for the
 * Rigging pane. Complements the unified hierarchy (which nests sockets under
 * their host parts) with a single at-a-glance inventory: name, host part, and
 * short id per row. Clicking a row selects the socket (same selection the
 * hierarchy, gizmo, and Socket Inspector share via {@link AttachmentStore});
 * the context menu offers Delete and Copy ID (the stable UUID used by
 * undo/selection and the {@code attach_*} MCP tools).
 */
public class SocketListSection {

    private static final Logger logger = LoggerFactory.getLogger(SocketListSection.class);

    private AttachmentStore attachmentStore;
    private com.openmason.main.systems.skeleton.AttachmentPreviewStore attachmentPreviewStore;
    /**
     * Selection routing — wired to the hierarchy's socket-select so picking a
     * socket here also clears part/bone selection and swaps the gizmo target.
     * The argument is the socket id, or {@code null} to clear.
     */
    private Consumer<String> onSelectSocket;
    /** Opens the "Test Model..." file dialog for the given socket id. */
    private Consumer<String> onTestModelRequested;

    public void setAttachmentStore(AttachmentStore store) { this.attachmentStore = store; }
    public void setAttachmentPreviewStore(com.openmason.main.systems.skeleton.AttachmentPreviewStore store) {
        this.attachmentPreviewStore = store;
    }
    public void setOnSelectSocket(Consumer<String> cb) { this.onSelectSocket = cb; }
    public void setOnTestModelRequested(Consumer<String> cb) { this.onTestModelRequested = cb; }

    public void render() {
        if (attachmentStore == null) {
            return;
        }
        List<OMOFormat.AttachmentPointEntry> sockets = attachmentStore.getPoints();

        if (!ImGui.collapsingHeader("Sockets (" + sockets.size() + ")",
                ImGuiTreeNodeFlags.DefaultOpen)) {
            return;
        }

        if (sockets.isEmpty()) {
            ImGui.textDisabled("No sockets — add one from the hierarchy's");
            ImGui.textDisabled("\"+ Socket\" button or a selected face.");
            return;
        }

        String selectedId = attachmentStore.getSelectedAttachmentId();
        for (OMOFormat.AttachmentPointEntry socket : sockets) {
            renderRow(socket, socket.id().equals(selectedId));
        }
    }

    private void renderRow(OMOFormat.AttachmentPointEntry socket, boolean isSelected) {
        ImGui.pushID(socket.id());

        // Cyan socket glyph, matching the hierarchy node and viewport marker.
        ImGui.pushStyleColor(ImGuiCol.Text, 0.35f, 0.80f, 0.95f, 1.0f);
        ImGui.smallButton(" <> ");
        ImGui.popStyleColor();
        ImGui.sameLine();

        if (ImGui.selectable(socket.name(), isSelected)) {
            select(socket.id());
        }

        // Host part + short id, dimmed, on the same row when space allows.
        ImGui.sameLine();
        ImVec4 dim = ImGui.getStyle().getColor(ImGuiCol.TextDisabled);
        ImGui.pushStyleColor(ImGuiCol.Text, dim.x, dim.y, dim.z, 0.8f);
        String parent = socket.isModelRoot()
                ? "(root)"
                : (socket.parentPartName() != null ? socket.parentPartName() : socket.parentPartId());
        ImGui.text(parent + "  #" + shortId(socket.id()));
        ImGui.popStyleColor();

        if (ImGui.beginPopupContextItem("##socket_row_ctx")) {
            ImGui.textDisabled(socket.name());
            ImGui.separator();
            if (onTestModelRequested != null && ImGui.menuItem("Test Model...")) {
                onTestModelRequested.accept(socket.id());
            }
            var preview = attachmentPreviewStore != null
                    ? attachmentPreviewStore.get(socket.id()) : null;
            if (preview != null && ImGui.menuItem("Clear Test (" + preview.label() + ")")) {
                attachmentPreviewStore.clear(socket.id());
            }
            ImGui.separator();
            if (ImGui.menuItem("Copy ID")) {
                ImGui.setClipboardText(socket.id());
                logger.info("Copied socket id {} ('{}') to clipboard", socket.id(), socket.name());
            }
            if (ImGui.menuItem("Copy Name")) {
                ImGui.setClipboardText(socket.name());
            }
            ImGui.separator();
            if (ImGui.menuItem("Delete")) {
                boolean wasSelected = socket.id().equals(attachmentStore.getSelectedAttachmentId());
                if (attachmentStore.remove(socket.id())) {
                    if (attachmentPreviewStore != null) {
                        attachmentPreviewStore.clear(socket.id());
                    }
                    if (wasSelected) {
                        select(null);
                    }
                }
            }
            ImGui.endPopup();
        }
        if (ImGui.isItemHovered()) {
            ImGui.setTooltip("id: " + socket.id() + "\nRight-click for actions");
        }

        ImGui.popID();
    }

    private void select(String socketId) {
        if (onSelectSocket != null) {
            onSelectSocket.accept(socketId);
        } else if (attachmentStore != null) {
            attachmentStore.setSelectedAttachmentId(socketId);
        }
    }

    /** First UUID segment — enough to disambiguate visually without the full 36 chars. */
    private static String shortId(String id) {
        int dash = id.indexOf('-');
        return dash > 0 ? id.substring(0, dash) : id;
    }
}
