package com.openmason.ui.menus;

import com.openmason.ui.services.ModelOperationService;
import com.openmason.ui.services.ViewportOperationService;
import com.openmason.ui.state.ModelState;
import com.openmason.ui.state.TransformState;
import com.openmason.ui.viewport.OpenMason3DViewport;
import imgui.ImGui;

/**
 * Tools menu handler.
 * Follows Single Responsibility Principle - only handles tools menu operations.
 */
public class ToolsMenuHandler {

    private final ModelState modelState;
    private final TransformState transformState;
    private final ModelOperationService modelOperations;
    private final ViewportOperationService viewportOperations;

    private OpenMason3DViewport viewport;

    public ToolsMenuHandler(ModelState modelState, TransformState transformState,
                            ModelOperationService modelOperations, ViewportOperationService viewportOperations) {
        this.modelState = modelState;
        this.transformState = transformState;
        this.modelOperations = modelOperations;
        this.viewportOperations = viewportOperations;
    }

    /**
     * Set viewport reference.
     */
    public void setViewport(OpenMason3DViewport viewport) {
        this.viewport = viewport;
    }

    /**
     * Render the tools menu.
     */
    public void render() {
        if (!ImGui.beginMenu("Tools")) {
            return;
        }

        if (ImGui.menuItem("Validate Model", "Ctrl+T", false, modelState.isModelLoaded())) {
            modelOperations.validateModel();
        }

        ImGui.separator();

        if (ImGui.beginMenu("Texture Variants")) {
            String[] variants = transformState.getTextureVariants();
            for (int i = 0; i < variants.length; i++) {
                boolean selected = (i == transformState.getCurrentTextureVariantIndex());
                if (ImGui.menuItem(variants[i], "Ctrl+" + (i + 1), selected)) {
                    viewportOperations.switchTextureVariant(viewport, transformState, variants[i]);
                }
            }
            ImGui.endMenu();
        }

        ImGui.endMenu();
    }
}
