package com.openmason.main.systems.menus.panes.riggingPane;

import com.openmason.engine.rendering.model.gmr.parts.PartShapeFactory;
import com.openmason.main.systems.ViewportController;
import com.openmason.main.systems.menus.panes.riggingPane.sections.AttachmentEditorSection;
import com.openmason.main.systems.menus.panes.riggingPane.sections.BoneEditorSection;
import com.openmason.main.systems.menus.panes.riggingPane.sections.HierarchySection;
import com.openmason.main.systems.menus.panes.riggingPane.sections.SocketListSection;
import com.openmason.main.systems.viewport.ViewportUIState;
import imgui.ImGui;
import imgui.flag.ImGuiWindowFlags;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Rigging pane — a single docked window containing a unified parts+bones
 * hierarchy tree, with a bone inspector that appears below the tree when a
 * bone is selected.
 */
public class RiggingPaneImGui {

    private static final Logger logger = LoggerFactory.getLogger(RiggingPaneImGui.class);
    public static final String WINDOW_TITLE = "Rigging";

    private final HierarchySection hierarchySection = new HierarchySection();
    private final SocketListSection socketListSection = new SocketListSection();
    private final BoneEditorSection boneInspector = new BoneEditorSection();
    private final AttachmentEditorSection attachmentInspector = new AttachmentEditorSection();

    private com.openmason.main.systems.menus.dialogs.FileDialogService fileDialogService;
    private com.openmason.main.systems.skeleton.AttachmentPreviewStore attachmentPreviewStore;

    /** Wire the native file-dialog service (for the socket "Test Model..." action). */
    public void setFileDialogService(com.openmason.main.systems.menus.dialogs.FileDialogService service) {
        this.fileDialogService = service;
    }

    public RiggingPaneImGui() {
    }

    /**
     * Connect the rigging pane to the active viewport. Wires the unified
     * hierarchy and the bone inspector to the viewport's part manager and
     * session skeleton.
     */
    public void setViewport(ViewportController viewport) {
        if (viewport == null) {
            return;
        }
        hierarchySection.setPartManager(viewport.getPartManager());
        hierarchySection.setBoneStore(viewport.getBoneStore());
        hierarchySection.setAttachmentStore(viewport.getAttachmentStore());
        hierarchySection.setOnPartCreated(viewport::assignDefaultMaterialToPartFaces);
        hierarchySection.setOnViewportInvalidationNeeded(viewport::invalidateSubRenderers);
        hierarchySection.setOnBoneSelectionChanged(viewport::onBoneSelectionChanged);
        hierarchySection.setOnAttachmentSelectionChanged(viewport::onAttachmentSelectionChanged);

        boneInspector.setBoneStore(viewport.getBoneStore());
        boneInspector.setOnSkeletonChanged(viewport::invalidateSubRenderers);

        attachmentInspector.setAttachmentStore(viewport.getAttachmentStore());
        attachmentInspector.setPartManager(viewport.getPartManager());
        attachmentInspector.setOnAttachmentsChanged(viewport::invalidateSubRenderers);

        // Socket list clicks route through the hierarchy's socket-select so
        // part/bone selection clears and the gizmo target swaps identically.
        socketListSection.setAttachmentStore(viewport.getAttachmentStore());
        socketListSection.setOnSelectSocket(hierarchySection::selectAttachment);

        // Socket test models: both socket UIs offer "Test Model..." which
        // routes here (owner of the file dialog + loader call).
        attachmentPreviewStore = viewport.getAttachmentPreviewStore();
        hierarchySection.setAttachmentPreviewStore(attachmentPreviewStore);
        hierarchySection.setOnTestModelRequested(this::openTestModelDialog);
        socketListSection.setAttachmentPreviewStore(attachmentPreviewStore);
        socketListSection.setOnTestModelRequested(this::openTestModelDialog);
    }

    /**
     * "Test Model..." on a socket: pick an attachable asset (.sbe/.sbo/.omo),
     * decode it through the game's own loader, and preview it mounted on the
     * socket — live against gizmo/inspector edits of the socket transform.
     */
    private void openTestModelDialog(String socketId) {
        if (fileDialogService == null || attachmentPreviewStore == null) {
            logger.warn("Test-model dialog unavailable — file dialog service not wired");
            return;
        }
        fileDialogService.showOpenAttachableModelDialog(filePath -> {
            try {
                var path = java.nio.file.Path.of(filePath);
                var asset = com.stonebreak.mobs.sbe.SbeEntityLoader.loadAttachable(path);
                attachmentPreviewStore.set(socketId, path.getFileName().toString(), asset);
                logger.info("Previewing '{}' on socket {}", path.getFileName(), socketId);
            } catch (Exception e) {
                logger.error("Failed to load test model: {}", filePath, e);
            }
        });
    }

    /**
     * Wire the Add Part / Add Bone / Part Transform slideouts to the viewport's
     * UI state. The rigging pane owns the hierarchy section so the slideout
     * callbacks live here.
     */
    public void wireSlideouts(ViewportUIState uiState, ViewportController viewport) {
        // Add Part slideout
        hierarchySection.setOnOpenAddPartSlideout(
                () -> uiState.toggleToolPane(ViewportUIState.ActiveToolPane.ADD_PART)
        );
        uiState.setAddPartCallback((shapeName, partName) -> {
            PartShapeFactory.Shape shape = PartShapeFactory.Shape.valueOf(shapeName);
            hierarchySection.onPartAdded(shape, partName);
        });

        // Add Bone slideout
        hierarchySection.setOnOpenAddBoneSlideout(
                () -> uiState.toggleToolPane(ViewportUIState.ActiveToolPane.ADD_BONE)
        );
        uiState.setAddBoneCallback((boneName, parentNodeId) ->
                hierarchySection.onBoneAdded(boneName, parentNodeId)
        );

        // Part Transform slideout
        hierarchySection.setOnOpenPartTransformSlideout(
                () -> uiState.toggleToolPane(ViewportUIState.ActiveToolPane.PART_TRANSFORM)
        );

        var partManager = viewport.getPartManager();
        uiState.setSelectedPartSupplier(() -> {
            var selectedIds = partManager.getSelectedPartIds();
            if (selectedIds.isEmpty()) return null;
            return partManager.getPartById(selectedIds.iterator().next()).orElse(null);
        });
        uiState.setApplyPartTransform(partManager::setPartTransform);
        uiState.setPartTransformInvalidator(viewport::invalidateSubRenderers);

        logger.debug("Rigging pane slideouts wired to viewport UI state");
    }

    public void render() {
        ImGui.setNextWindowSizeConstraints(300, 200, 500, 800);

        int windowFlags = ImGuiWindowFlags.NoScrollbar;
        if (ImGui.begin(WINDOW_TITLE, windowFlags)) {
            ImGui.beginChild("##rigging_content", 0, 0, false);

            hierarchySection.render();

            ImGui.spacing();
            socketListSection.render();

            ImGui.spacing();
            ImGui.separator();
            ImGui.spacing();
            if (hierarchySection.getSelectedAttachmentId() != null) {
                attachmentInspector.render(hierarchySection.getSelectedAttachmentId());
            } else {
                boneInspector.render(hierarchySection.getSelectedBoneId());
            }

            ImGui.endChild();
        }
        ImGui.end();
    }
}
