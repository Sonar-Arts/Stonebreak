package com.openmason.ui.properties.sections;

import com.openmason.model.editable.BlockModel;
import com.openmason.ui.dialogs.FileDialogService;
import com.openmason.ui.preferences.PreferencesPageRenderer;
import com.openmason.ui.properties.interfaces.IPanelSection;
import com.openmason.ui.state.ModelState;
import imgui.ImGui;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.function.Consumer;

/**
 * Texture chooser section for editable models (NEW and OMO_FILE).
 * Allows user to select .OMT or .PNG texture files.
 */
public class TextureChooserSection implements IPanelSection {

    private static final Logger logger = LoggerFactory.getLogger(TextureChooserSection.class);

    private final FileDialogService fileDialogService;
    private final ModelState modelState;
    private BlockModel currentModel;
    private boolean visible = true;
    private Consumer<Path> onTextureChanged;

    /**
     * Creates a new texture chooser section.
     *
     * @param fileDialogService service for showing file dialogs
     * @param modelState model state for checking source type
     */
    public TextureChooserSection(FileDialogService fileDialogService, ModelState modelState) {
        this.fileDialogService = fileDialogService;
        this.modelState = modelState;
    }

    @Override
    public void render() {
        if (!visible || currentModel == null) {
            return;
        }

        // Only show for editable models (NEW or OMO_FILE)
        ModelState.ModelSource source = modelState.getModelSource();
        if (source != ModelState.ModelSource.NEW && source != ModelState.ModelSource.OMO_FILE) {
            return;
        }

        // Use compact blue header box with JetBrains Mono Bold
        PreferencesPageRenderer.renderCompactSectionHeader("Texture");

        // Display current texture
        Path texturePath = currentModel.getTexturePath();
        if (texturePath != null) {
            ImGui.text("Current Texture:");
            ImGui.sameLine();
            ImGui.textDisabled(texturePath.getFileName().toString());

            ImGui.sameLine();
            if (ImGui.smallButton("Clear##ClearTexture")) {
                handleTextureClear();
            }
        } else {
            ImGui.textDisabled("No texture selected");
        }

        ImGui.spacing();

        // Choose texture button
        if (ImGui.button("Choose Texture...")) {
            handleTextureChoose();
        }

        if (ImGui.isItemHovered()) {
            ImGui.setTooltip("Select .OMT or .PNG texture file");
        }
    }

    @Override
    public boolean isVisible() {
        return visible;
    }

    @Override
    public String getSectionName() {
        return "Texture";
    }

    // Public API

    /**
     * Set the current editable model.
     *
     * @param model the block model to manage textures for
     */
    public void setModel(BlockModel model) {
        this.currentModel = model;
        logger.debug("Texture chooser updated with model: {}", model != null ? model.getName() : "null");
    }

    /**
     * Set the texture changed callback.
     * Called when the user selects or clears a texture.
     *
     * @param callback callback to invoke with the new texture path (or null if cleared)
     */
    public void setOnTextureChanged(Consumer<Path> callback) {
        this.onTextureChanged = callback;
    }

    /**
     * Set visibility of this section.
     *
     * @param visible true to show, false to hide
     */
    public void setVisible(boolean visible) {
        this.visible = visible;
    }

    // Private handlers

    /**
     * Handle texture selection through file dialog.
     */
    private void handleTextureChoose() {
        fileDialogService.showOpenTextureDialog(filePath -> {
            Path newTexturePath = Paths.get(filePath);
            currentModel.setTexturePath(newTexturePath);

            logger.info("Texture changed to: {}", filePath);

            if (onTextureChanged != null) {
                onTextureChanged.accept(newTexturePath);
            }
        });
    }

    /**
     * Handle texture clearing.
     */
    private void handleTextureClear() {
        currentModel.setTexturePath(null);

        logger.info("Texture cleared");

        if (onTextureChanged != null) {
            onTextureChanged.accept(null);
        }
    }
}
