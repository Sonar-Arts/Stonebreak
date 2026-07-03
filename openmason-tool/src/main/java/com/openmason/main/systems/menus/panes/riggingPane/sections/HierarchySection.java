package com.openmason.main.systems.menus.panes.riggingPane.sections;

import com.openmason.engine.format.omo.OMOFormat;
import com.openmason.engine.rendering.model.gmr.parts.ModelPartDescriptor;
import com.openmason.engine.rendering.model.gmr.parts.ModelPartManager;
import com.openmason.engine.rendering.model.gmr.parts.PartMeshRebuilder;
import com.openmason.engine.rendering.model.gmr.parts.PartShapeFactory;
import com.openmason.engine.rendering.model.gmr.parts.PartTransform;
import com.openmason.main.systems.skeleton.BoneStore;
import com.openmason.main.systems.themes.utils.ImGuiComponents;
import imgui.ImDrawList;
import imgui.ImGui;
import imgui.ImVec2;
import imgui.ImVec4;
import imgui.flag.ImGuiCol;
import org.joml.Matrix4f;
import org.joml.Vector3f;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.function.Consumer;

/**
 * Unified hierarchy section for the Rigging pane.
 *
 * <p>Renders a single tree containing both {@link ModelPartDescriptor parts} and
 * {@link OMOFormat.BoneEntry bones}. A bone may be parented to a part by storing
 * the part's ID in its {@code parentBoneId} field — {@link BoneStore} resolves
 * cross-type parents through an external matrix resolver so transforms compose
 * correctly at render time.
 *
 * <p>Single responsibility: hierarchy UI for the active model. Delegates all
 * mutation to {@link ModelPartManager} (parts) and {@link BoneStore} (bones).
 */
public class HierarchySection {

    private static final Logger logger = LoggerFactory.getLogger(HierarchySection.class);
    private static final String PART_PAYLOAD = "OPENMASON_PART_REPARENT";
    private static final String BONE_PAYLOAD = "OPENMASON_BONE_REPARENT";

    private ModelPartManager partManager;
    private BoneStore boneStore;
    private com.openmason.main.systems.skeleton.AttachmentStore attachmentStore;
    private com.openmason.main.systems.skeleton.AttachmentPreviewStore attachmentPreviewStore;
    /** Opens the "Test Model..." file dialog for the given socket id. */
    private Consumer<String> onTestModelRequested;

    private Consumer<ModelPartDescriptor> onPartCreated;
    private Runnable onViewportInvalidationNeeded;
    private Runnable onOpenAddPartSlideout;
    private Runnable onOpenAddBoneSlideout;
    private Runnable onOpenPartTransformSlideout;
    /**
     * Notifies the viewport when the bone selection changes. The argument is the
     * newly-selected bone id, or {@code null} when selection moves to a part / clears.
     */
    private Consumer<String> onBoneSelectionChanged;
    /**
     * Notifies the viewport when the attachment point (socket) selection changes.
     * The argument is the newly-selected socket id, or {@code null} when selection
     * moves to a part/bone or clears.
     */
    private Consumer<String> onAttachmentSelectionChanged;

    /** Currently selected bone (parts use the part manager's own selection set). */
    private String selectedBoneId;

    public void setPartManager(ModelPartManager partManager) {
        this.partManager = partManager;
    }

    public void setBoneStore(BoneStore boneStore) {
        this.boneStore = boneStore;
    }

    public void setAttachmentStore(com.openmason.main.systems.skeleton.AttachmentStore store) {
        this.attachmentStore = store;
    }

    public void setAttachmentPreviewStore(com.openmason.main.systems.skeleton.AttachmentPreviewStore store) {
        this.attachmentPreviewStore = store;
    }

    public void setOnTestModelRequested(Consumer<String> cb) {
        this.onTestModelRequested = cb;
    }

    public void setOnPartCreated(Consumer<ModelPartDescriptor> cb) { this.onPartCreated = cb; }
    public void setOnViewportInvalidationNeeded(Runnable cb) { this.onViewportInvalidationNeeded = cb; }
    public void setOnOpenAddPartSlideout(Runnable cb) { this.onOpenAddPartSlideout = cb; }
    public void setOnOpenAddBoneSlideout(Runnable cb) { this.onOpenAddBoneSlideout = cb; }
    public void setOnOpenPartTransformSlideout(Runnable cb) { this.onOpenPartTransformSlideout = cb; }
    public void setOnBoneSelectionChanged(Consumer<String> cb) { this.onBoneSelectionChanged = cb; }
    public void setOnAttachmentSelectionChanged(Consumer<String> cb) { this.onAttachmentSelectionChanged = cb; }

    public String getSelectedBoneId() { return selectedBoneId; }

    /**
     * The selected socket id. Read from the {@link AttachmentStore} (the single
     * source of truth) so selections made elsewhere — the socket list, the face
     * panel's "Add Socket", MCP {@code attach_select} — highlight here too.
     */
    public String getSelectedAttachmentId() {
        return attachmentStore != null ? attachmentStore.getSelectedAttachmentId() : null;
    }

    public void render() {
        if (partManager == null || boneStore == null) {
            return;
        }

        List<ModelPartDescriptor> parts = partManager.getAllParts();
        List<OMOFormat.BoneEntry> bones = boneStore.getBones();
        List<OMOFormat.AttachmentPointEntry> sockets = attachmentStore != null
                ? attachmentStore.getPoints() : List.of();

        String headerText = "Hierarchy (" + parts.size() + " parts, " + bones.size() + " bones"
                + (sockets.isEmpty() ? "" : ", " + sockets.size() + " sockets") + ")";
        ImGuiComponents.renderCompactSectionHeader(headerText);
        ImGui.spacing();

        Set<String> allNodeIds = new HashSet<>();
        for (ModelPartDescriptor p : parts) allNodeIds.add(p.id());
        for (OMOFormat.BoneEntry b : bones) allNodeIds.add(b.id());

        renderRootDropTarget();

        for (ModelPartDescriptor p : parts) {
            if (p.parentId() == null || !allNodeIds.contains(p.parentId())) {
                renderPartNode(p, parts, bones, allNodeIds, 0);
            }
        }
        for (OMOFormat.BoneEntry b : bones) {
            if (b.isRoot() || !allNodeIds.contains(b.parentBoneId())) {
                renderBoneNode(b, parts, bones, allNodeIds, 0);
            }
        }
        // Root sockets (model-root bound or orphaned parent) at top level.
        for (OMOFormat.AttachmentPointEntry s : sockets) {
            if (s.isModelRoot() || !allNodeIds.contains(s.parentPartId())) {
                renderAttachmentNode(s, 0);
            }
        }

        ImGui.spacing();
        renderAddButtons();
    }

    // ========== Add buttons ==========

    private void renderAddButtons() {
        float w = (ImGui.getContentRegionAvailX() - 2 * ImGui.getStyle().getItemSpacingX()) / 3f;

        ImVec4 accent = ImGui.getStyle().getColor(ImGuiCol.HeaderActive);
        ImGui.pushStyleColor(ImGuiCol.Button, accent.x, accent.y, accent.z, 0.20f);
        ImGui.pushStyleColor(ImGuiCol.ButtonHovered, accent.x, accent.y, accent.z, 0.40f);
        ImGui.pushStyleColor(ImGuiCol.ButtonActive, accent.x, accent.y, accent.z, 0.55f);
        ImGui.pushStyleColor(ImGuiCol.Text, accent.x, accent.y, accent.z, 1.0f);

        if (ImGui.button("+ Part", w, 0) && onOpenAddPartSlideout != null) {
            onOpenAddPartSlideout.run();
        }
        ImGui.sameLine();
        if (ImGui.button("+ Bone", w, 0) && onOpenAddBoneSlideout != null) {
            onOpenAddBoneSlideout.run();
        }
        ImGui.sameLine();
        if (ImGui.button("+ Socket", w, 0)) {
            onSocketAdded();
        }

        ImGui.popStyleColor(4);
    }

    // ========== Part rendering ==========

    private void renderPartNode(ModelPartDescriptor part,
                                 List<ModelPartDescriptor> allParts,
                                 List<OMOFormat.BoneEntry> allBones,
                                 Set<String> allIds,
                                 int depth) {
        boolean isSelected = partManager.isPartSelected(part.id());
        boolean isHidden = !part.visible();

        ImGui.pushID(part.id());
        if (depth > 0) ImGui.indent(depth * 12.0f);

        drawSelectionStripe(isSelected);

        if (isHidden) ImGui.pushStyleColor(ImGuiCol.Text, 0.5f, 0.5f, 0.5f, 0.35f);
        if (ImGui.smallButton(isHidden ? " - " : " o ")) {
            partManager.setPartVisible(part.id(), isHidden);
            invalidateViewport();
        }
        if (isHidden) ImGui.popStyleColor();

        ImGui.sameLine();
        if (part.locked()) {
            ImGui.pushStyleColor(ImGuiCol.Text, 1.0f, 0.6f, 0.0f, 1.0f);
        } else {
            ImVec4 dim = ImGui.getStyle().getColor(ImGuiCol.TextDisabled);
            ImGui.pushStyleColor(ImGuiCol.Text, dim.x, dim.y, dim.z, 0.4f);
        }
        if (ImGui.smallButton(part.locked() ? " L " : " U ")) {
            partManager.setPartLocked(part.id(), !part.locked());
            invalidateViewport();
        }
        ImGui.popStyleColor();

        ImGui.sameLine();
        if (isHidden) ImGui.pushStyleColor(ImGuiCol.Text, 0.5f, 0.5f, 0.5f, 0.35f);
        if (ImGui.selectable(part.name(), isSelected)) {
            if (ImGui.getIO().getKeyCtrl()) {
                partManager.togglePartSelection(part.id());
            } else {
                partManager.deselectAllParts();
                partManager.selectPart(part.id());
                if (selectedBoneId != null) {
                    selectedBoneId = null;
                    if (onBoneSelectionChanged != null) onBoneSelectionChanged.accept(null);
                }
            }
            clearAttachmentSelection();
            if (onOpenPartTransformSlideout != null) onOpenPartTransformSlideout.run();
        }
        if (isHidden) ImGui.popStyleColor();

        // Drag source
        if (ImGui.beginDragDropSource()) {
            ImGui.setDragDropPayload(PART_PAYLOAD, part.id(), imgui.flag.ImGuiCond.Once);
            ImGui.text("Reparent: " + part.name());
            ImGui.endDragDropSource();
        }
        // Drop targets — accept either a part or a bone as child of this part
        if (ImGui.beginDragDropTarget()) {
            Object partPayload = ImGui.acceptDragDropPayload(PART_PAYLOAD);
            if (partPayload instanceof String draggedPartId && !draggedPartId.equals(part.id())) {
                if (partManager.setPartParent(draggedPartId, part.id())) invalidateViewport();
            }
            Object bonePayload = ImGui.acceptDragDropPayload(BONE_PAYLOAD);
            if (bonePayload instanceof String draggedBoneId) {
                reparentBone(draggedBoneId, part.id());
            }
            ImGui.endDragDropTarget();
        }

        if (ImGui.beginPopupContextItem()) {
            if (ImGui.menuItem("Duplicate")) {
                partManager.duplicatePart(part.id());
                invalidateViewport();
            }
            if (!part.isRoot()) {
                if (ImGui.menuItem("Detach to root")) {
                    partManager.setPartParent(part.id(), null);
                    invalidateViewport();
                }
            }
            if (allParts.size() > 1) {
                if (ImGui.menuItem("Delete")) {
                    partManager.removePart(part.id());
                    invalidateViewport();
                }
            } else {
                ImGui.textDisabled("Cannot delete last part");
            }
            ImGui.endPopup();
        }

        if (depth > 0) ImGui.unindent(depth * 12.0f);

        // Children: parts whose parentId == this part, then bones whose parentBoneId == this part,
        // then sockets whose parentPartId == this part
        for (ModelPartDescriptor child : allParts) {
            if (part.id().equals(child.parentId())) {
                renderPartNode(child, allParts, allBones, allIds, depth + 1);
            }
        }
        for (OMOFormat.BoneEntry bone : allBones) {
            if (part.id().equals(bone.parentBoneId())) {
                renderBoneNode(bone, allParts, allBones, allIds, depth + 1);
            }
        }
        if (attachmentStore != null) {
            for (OMOFormat.AttachmentPointEntry socket : attachmentStore.getPoints()) {
                if (part.id().equals(socket.parentPartId())) {
                    renderAttachmentNode(socket, depth + 1);
                }
            }
        }

        ImGui.popID();
    }

    // ========== Attachment point (socket) rendering ==========

    private void renderAttachmentNode(OMOFormat.AttachmentPointEntry socket, int depth) {
        boolean isSelected = socket.id().equals(getSelectedAttachmentId());

        ImGui.pushID(socket.id());
        if (depth > 0) ImGui.indent(depth * 12.0f);

        drawSelectionStripe(isSelected);

        // Socket marker — cyan diamond-ish symbol via colored small button
        ImGui.pushStyleColor(ImGuiCol.Text, 0.35f, 0.80f, 0.95f, 1.0f);
        ImGui.smallButton(" <> ");
        ImGui.popStyleColor();
        ImGui.sameLine();

        if (ImGui.selectable(socket.name(), isSelected)) {
            selectAttachment(socket.id());
        }

        if (ImGui.beginPopupContextItem()) {
            renderTestModelMenuItems(socket.id());
            if (ImGui.menuItem("Copy ID")) {
                ImGui.setClipboardText(socket.id());
            }
            if (ImGui.menuItem("Delete")) {
                boolean wasSelected = socket.id().equals(getSelectedAttachmentId());
                if (attachmentStore.remove(socket.id())) {
                    if (attachmentPreviewStore != null) {
                        attachmentPreviewStore.clear(socket.id());
                    }
                    if (wasSelected) {
                        selectAttachment(null);
                    }
                    invalidateViewport();
                }
            }
            ImGui.endPopup();
        }

        if (depth > 0) ImGui.unindent(depth * 12.0f);
        ImGui.popID();
    }

    /**
     * "Test Model..." / "Clear Test" context items for a socket — previews an
     * attachable asset mounted on the socket so its transform can be tuned
     * against the real model.
     */
    private void renderTestModelMenuItems(String socketId) {
        if (onTestModelRequested != null && ImGui.menuItem("Test Model...")) {
            onTestModelRequested.accept(socketId);
        }
        var preview = attachmentPreviewStore != null ? attachmentPreviewStore.get(socketId) : null;
        if (preview != null && ImGui.menuItem("Clear Test (" + preview.label() + ")")) {
            attachmentPreviewStore.clear(socketId);
        }
    }

    /** Clears any socket selection when a part or bone is picked instead. */
    private void clearAttachmentSelection() {
        if (getSelectedAttachmentId() != null) {
            if (attachmentStore != null) {
                attachmentStore.setSelectedAttachmentId(null);
            }
            if (onAttachmentSelectionChanged != null) onAttachmentSelectionChanged.accept(null);
        }
    }

    /**
     * Select a socket (null clears), keeping part/bone selection mutually
     * exclusive. Public so the socket list section routes its clicks through
     * the same path. Selection state itself lives in the {@link AttachmentStore};
     * the callback lets the viewport swap the gizmo target.
     */
    public void selectAttachment(String socketId) {
        if (attachmentStore != null) {
            attachmentStore.setSelectedAttachmentId(socketId);
        }
        if (socketId != null) {
            partManager.deselectAllParts();
            if (selectedBoneId != null) {
                selectedBoneId = null;
                if (onBoneSelectionChanged != null) onBoneSelectionChanged.accept(null);
            }
        }
        if (onAttachmentSelectionChanged != null) onAttachmentSelectionChanged.accept(socketId);
    }

    /** Invoked by the "+ Socket" button: adds a socket bound to the selected part (or model root). */
    private void onSocketAdded() {
        if (attachmentStore == null) return;

        String parentPartId = null;
        String parentPartName = null;
        Vector3f pos = new Vector3f();
        var selectedIds = partManager.getSelectedPartIds();
        if (!selectedIds.isEmpty()) {
            String partId = selectedIds.iterator().next();
            var partOpt = partManager.getPartById(partId);
            if (partOpt.isPresent()) {
                parentPartId = partId;
                parentPartName = partOpt.get().name();
                // Default the socket to the part's pivot so it appears on the part.
                Matrix4f partWorld = partManager.getEffectiveWorldMatrix(partId);
                if (partWorld != null) {
                    pos = partWorld.transformPosition(new Vector3f());
                }
            }
        }

        OMOFormat.AttachmentPointEntry entry = new OMOFormat.AttachmentPointEntry(
                UUID.randomUUID().toString(),
                "socket_" + (attachmentStore.size() + 1),
                parentPartId, parentPartName,
                pos.x, pos.y, pos.z,
                0f, 0f, 0f
        );
        attachmentStore.put(entry);
        selectAttachment(entry.id());
        invalidateViewport();

        logger.info("Added socket '{}' (parent={})", entry.name(),
                parentPartName == null ? "root" : parentPartName);
    }

    // ========== Bone rendering ==========

    private void renderBoneNode(OMOFormat.BoneEntry bone,
                                 List<ModelPartDescriptor> allParts,
                                 List<OMOFormat.BoneEntry> allBones,
                                 Set<String> allIds,
                                 int depth) {
        boolean isSelected = bone.id().equals(selectedBoneId);

        ImGui.pushID(bone.id());
        if (depth > 0) ImGui.indent(depth * 12.0f);

        drawSelectionStripe(isSelected);

        // Bone marker — small diamond-ish symbol via colored small button
        ImGui.pushStyleColor(ImGuiCol.Text, 1.00f, 0.85f, 0.30f, 1.0f);
        ImGui.smallButton(" * ");
        ImGui.popStyleColor();
        ImGui.sameLine();

        if (ImGui.selectable(bone.name(), isSelected)) {
            selectedBoneId = bone.id();
            partManager.deselectAllParts();
            clearAttachmentSelection();
            if (onBoneSelectionChanged != null) onBoneSelectionChanged.accept(bone.id());
        }

        if (ImGui.beginDragDropSource()) {
            ImGui.setDragDropPayload(BONE_PAYLOAD, bone.id(), imgui.flag.ImGuiCond.Once);
            ImGui.text("Reparent: " + bone.name());
            ImGui.endDragDropSource();
        }
        if (ImGui.beginDragDropTarget()) {
            Object bonePayload = ImGui.acceptDragDropPayload(BONE_PAYLOAD);
            if (bonePayload instanceof String draggedBoneId && !draggedBoneId.equals(bone.id())) {
                reparentBone(draggedBoneId, bone.id());
            }
            Object partPayload = ImGui.acceptDragDropPayload(PART_PAYLOAD);
            if (partPayload instanceof String draggedPartId) {
                snapBoneEndpointToPart(bone, draggedPartId);
                if (partManager.setPartParent(draggedPartId, bone.id())) invalidateViewport();
            }
            ImGui.endDragDropTarget();
        }

        if (ImGui.beginPopupContextItem()) {
            if (!bone.isRoot()) {
                if (ImGui.menuItem("Detach to root")) {
                    reparentBone(bone.id(), null);
                }
            }
            if (ImGui.menuItem("Delete")) {
                if (boneStore.remove(bone.id())) {
                    if (bone.id().equals(selectedBoneId)) {
                        selectedBoneId = null;
                        if (onBoneSelectionChanged != null) onBoneSelectionChanged.accept(null);
                    }
                    invalidateViewport();
                }
            }
            ImGui.endPopup();
        }

        if (depth > 0) ImGui.unindent(depth * 12.0f);

        // Children: parts whose parentId == this bone, then bones whose parentBoneId == this bone
        for (ModelPartDescriptor partChild : allParts) {
            if (bone.id().equals(partChild.parentId())) {
                renderPartNode(partChild, allParts, allBones, allIds, depth + 1);
            }
        }
        for (OMOFormat.BoneEntry child : allBones) {
            if (bone.id().equals(child.parentBoneId())) {
                renderBoneNode(child, allParts, allBones, allIds, depth + 1);
            }
        }

        ImGui.popID();
    }

    // ========== Drop target for root ==========

    private void renderRootDropTarget() {
        ImGui.pushID("##hierarchy_root_drop");
        ImVec4 dim = ImGui.getStyle().getColor(ImGuiCol.TextDisabled);
        ImGui.pushStyleColor(ImGuiCol.Text, dim.x, dim.y, dim.z, 0.5f);
        ImGui.textUnformatted("(Root)");
        ImGui.popStyleColor();
        if (ImGui.beginDragDropTarget()) {
            Object partPayload = ImGui.acceptDragDropPayload(PART_PAYLOAD);
            if (partPayload instanceof String draggedPartId) {
                if (partManager.setPartParent(draggedPartId, null)) invalidateViewport();
            }
            Object bonePayload = ImGui.acceptDragDropPayload(BONE_PAYLOAD);
            if (bonePayload instanceof String draggedBoneId) {
                reparentBone(draggedBoneId, null);
            }
            ImGui.endDragDropTarget();
        }
        ImGui.popID();
    }

    // ========== Bone ↔ part anchoring ==========

    /**
     * Solve for the bone's local-frame endpoint that places its tail exactly at the
     * given part's current world pivot, then zero the part's local position so it
     * lands on the tail with no visual jump. Invoked when the user drops a part onto
     * a bone in the hierarchy — the bone "extends" to meet the part rather than the
     * part snapping onto the existing tail.
     *
     * <p>Math: {@code tail_world = head_world × T(endpoint)}, so for the tail to
     * land at {@code partPivot} we need
     * {@code endpoint = head_world.inverse() × partPivot}.
     */
    private void snapBoneEndpointToPart(OMOFormat.BoneEntry bone, String partId) {
        var partOpt = partManager.getPartById(partId);
        if (partOpt.isEmpty()) return;
        ModelPartDescriptor part = partOpt.get();

        Matrix4f partWorld = partManager.getEffectiveWorldMatrix(partId);
        if (partWorld == null) return;
        Vector3f partPivot = partWorld.transformPosition(new Vector3f());

        Matrix4f headWorld = boneStore.getWorldTransform(bone.id());
        if (headWorld == null) return;

        Vector3f endpoint = new Matrix4f(headWorld).invert().transformPosition(new Vector3f(partPivot));

        boneStore.put(new OMOFormat.BoneEntry(
                bone.id(), bone.name(), bone.parentBoneId(),
                bone.originX(), bone.originY(), bone.originZ(),
                bone.posX(), bone.posY(), bone.posZ(),
                bone.rotX(), bone.rotY(), bone.rotZ(),
                endpoint.x, endpoint.y, endpoint.z
        ));

        // Zero the part's local position. Rotation/scale are preserved (they remain
        // relative to the new tail frame). Combined with the endpoint above, the
        // part's world pivot stays exactly where the user dropped it.
        PartTransform t = part.transform();
        partManager.setPartTransform(partId, new PartTransform(
                t.origin(),
                new Vector3f(0, 0, 0),
                new Vector3f(t.rotation()),
                new Vector3f(t.scale())
        ));
    }

    // ========== Bone reparenting helper ==========

    /**
     * Rewrites a bone with a new {@code parentBoneId}. Cycle-safe — refuses to set a
     * parent that is a descendant of the bone in question. The parent ID may be a
     * bone, a part, or {@code null} (root).
     */
    private void reparentBone(String boneId, String newParentId) {
        OMOFormat.BoneEntry bone = boneStore.getById(boneId);
        if (bone == null) return;
        if (newParentId != null && newParentId.equals(boneId)) return;
        if (newParentId != null && isBoneDescendant(newParentId, boneId)) {
            logger.warn("Refusing bone reparent: would create cycle ({} -> {})", boneId, newParentId);
            return;
        }
        boneStore.put(new OMOFormat.BoneEntry(
                bone.id(), bone.name(), newParentId,
                bone.originX(), bone.originY(), bone.originZ(),
                bone.posX(), bone.posY(), bone.posZ(),
                bone.rotX(), bone.rotY(), bone.rotZ(),
                bone.endpointX(), bone.endpointY(), bone.endpointZ()
        ));
        invalidateViewport();
    }

    /** True when {@code candidate} is in the (bone-only) parent chain of {@code ancestorId}. */
    private boolean isBoneDescendant(String candidate, String ancestorId) {
        OMOFormat.BoneEntry walker = boneStore.getById(candidate);
        Set<String> visited = new HashSet<>();
        while (walker != null) {
            if (walker.id().equals(ancestorId)) return true;
            if (!visited.add(walker.id())) return false;
            if (walker.parentBoneId() == null) return false;
            walker = boneStore.getById(walker.parentBoneId());
        }
        return false;
    }

    // ========== Callbacks ==========

    /** Invoked by slideout callback when the user confirms adding a part. */
    public void onPartAdded(PartShapeFactory.Shape shape, String name) {
        if (partManager == null) return;

        PartMeshRebuilder.PartGeometry geometry = PartShapeFactory.createGeometry(
                shape, name, new Vector3f(1, 1, 1));
        ModelPartDescriptor newPart = partManager.addPartFromGeometry(name, geometry, new Vector3f(0, 0, 0));

        if (newPart != null && partManager.getPartCount() > 1) {
            float offset = partManager.getPartCount() * 1.5f;
            PartTransform offsetTransform = new PartTransform(
                    new Vector3f(0, 0, 0),
                    new Vector3f(offset, 0, 0),
                    new Vector3f(0, 0, 0),
                    new Vector3f(1, 1, 1)
            );
            partManager.setPartTransform(newPart.id(), offsetTransform);
            newPart = partManager.getPartById(newPart.id()).orElse(newPart);
        }

        if (onPartCreated != null && newPart != null) onPartCreated.accept(newPart);
        invalidateViewport();

        logger.info("Added {} part '{}'", shape.getDisplayName(), name);
    }

    /** Invoked by slideout callback when the user confirms adding a bone. */
    public void onBoneAdded(String name, String parentNodeId) {
        if (boneStore == null) return;

        String resolvedName = (name == null || name.isBlank())
                ? "bone_" + (boneStore.size() + 1)
                : name;

        // Default endpoint to (0, 1, 0) so freshly-created bones have a visible
        // shaft. Users can drag the endpoint elsewhere from the inspector.
        OMOFormat.BoneEntry entry = new OMOFormat.BoneEntry(
                UUID.randomUUID().toString(),
                resolvedName,
                parentNodeId,
                0f, 0f, 0f,
                0f, 0f, 0f,
                0f, 0f, 0f,
                0f, 1f, 0f
        );
        boneStore.put(entry);
        selectedBoneId = entry.id();
        invalidateViewport();

        logger.info("Added bone '{}' (parent={})", resolvedName, parentNodeId == null ? "root" : parentNodeId);
    }

    // ========== Helpers ==========

    private void drawSelectionStripe(boolean selected) {
        if (!selected) return;
        ImDrawList drawList = ImGui.getWindowDrawList();
        ImVec2 rowStart = ImGui.getCursorScreenPos();
        float rowWidth = ImGui.getContentRegionAvailX();
        float rowHeight = ImGui.getFrameHeight();
        ImVec4 accent = ImGui.getStyle().getColor(ImGuiCol.HeaderActive);
        drawList.addRectFilled(rowStart.x, rowStart.y,
                rowStart.x + rowWidth, rowStart.y + rowHeight,
                ImGui.colorConvertFloat4ToU32(accent.x, accent.y, accent.z, 0.15f), 3.0f);
        drawList.addRectFilled(rowStart.x, rowStart.y + 3,
                rowStart.x + 3, rowStart.y + rowHeight - 3,
                ImGui.colorConvertFloat4ToU32(accent.x, accent.y, accent.z, 0.6f), 1.0f);
    }

    private void invalidateViewport() {
        boneStore.invalidate();
        if (onViewportInvalidationNeeded != null) onViewportInvalidationNeeded.run();
    }
}
