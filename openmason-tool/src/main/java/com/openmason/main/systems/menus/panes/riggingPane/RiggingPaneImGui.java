package com.openmason.main.systems.menus.panes.riggingPane;

import com.openmason.engine.rendering.model.gmr.parts.PartShapeFactory;
import com.openmason.main.systems.ViewportController;
import com.openmason.main.systems.menus.panes.riggingPane.sections.BoneEditorSection;
import com.openmason.main.systems.menus.panes.riggingPane.sections.HierarchySection;
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
    private final BoneEditorSection boneInspector = new BoneEditorSection();

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
        hierarchySection.setOnPartCreated(viewport::assignDefaultMaterialToPartFaces);
        hierarchySection.setOnViewportInvalidationNeeded(viewport::invalidateSubRenderers);
        hierarchySection.setOnBoneSelectionChanged(viewport::onBoneSelectionChanged);

        boneInspector.setBoneStore(viewport.getBoneStore());
        boneInspector.setOnSkeletonChanged(viewport::invalidateSubRenderers);
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
            ImGui.separator();
            ImGui.spacing();
            boneInspector.render(hierarchySection.getSelectedBoneId());

            ImGui.endChild();
        }
        ImGui.end();
    }
}
