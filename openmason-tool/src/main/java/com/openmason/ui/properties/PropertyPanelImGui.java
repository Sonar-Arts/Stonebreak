package com.openmason.ui.properties;

import com.openmason.model.editable.BlockModel;
import com.openmason.ui.dialogs.FileDialogService;
import com.openmason.ui.properties.adapters.ViewportAdapter;
import com.openmason.ui.properties.interfaces.IThemeContext;
import com.openmason.ui.properties.interfaces.ITransformState;
import com.openmason.ui.properties.interfaces.IViewportConnector;
import com.openmason.ui.properties.sections.*;
import com.openmason.ui.properties.state.TransformState;
import com.openmason.ui.properties.theming.PanelThemeContext;
import com.openmason.ui.state.ModelState;
import com.openmason.ui.themes.core.ThemeManager;
import com.openmason.ui.ViewportController;
import imgui.ImGui;
import imgui.flag.ImGuiWindowFlags;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Property Panel coordinator
 */
public class PropertyPanelImGui {

    private static final Logger logger = LoggerFactory.getLogger(PropertyPanelImGui.class);

    // Dependencies (injected)
    private final ModelState modelState;
    private final ThemeManager themeManager; // Store for getThemeManager() compatibility
    private final IThemeContext themeContext;
    private IViewportConnector viewportConnector;

    // Section components (composition)
    private final TextureVariantSection textureVariantSection;  // For BROWSER models
    private final TextureChooserSection textureChooserSection;  // For NEW and OMO_FILE models
    private final TransformSection transformSection;

    // State
    private boolean initialized = false;
    private BlockModel currentEditableModel = null;  // Current editable model for texture reloading

    /**
     * Create PropertyPanelImGui with dependency injection.
     */
    public PropertyPanelImGui(ThemeManager themeManager, FileDialogService fileDialogService, ModelState modelState) {
        // Initialize dependencies
        this.modelState = modelState;
        this.themeManager = themeManager; // Store for getThemeManager() compatibility
        this.themeContext = new PanelThemeContext(themeManager);
        ITransformState transformState = new TransformState();
        this.viewportConnector = null; // Set later via setViewport3D()

        // Initialize sections (composition)
        this.textureVariantSection = new TextureVariantSection();  // For BROWSER models
        this.textureChooserSection = new TextureChooserSection(fileDialogService, modelState);  // For editable models
        this.transformSection = new TransformSection(transformState);

        // Configure section callbacks
        setupSectionCallbacks();

        // Initialize system
        initialize();

        if (themeContext.isAvailable()) {
            logger.debug("Theme system integration enabled for PropertyPanel");
        } else {
            logger.debug("PropertyPanel created without theme system");
        }
    }

    /**
     * Initialize the property panel.
     */
    private void initialize() {
        try {
            initialized = true;
        } catch (Exception e) {
            logger.error("Failed to initialize PropertyPanelImGui", e);
        }
    }

    /**
     * Setup callbacks for section components.
     */
    private void setupSectionCallbacks() {
        // Texture variant section callback (for BROWSER models)
        textureVariantSection.setOnVariantChanged(this::switchTextureVariant);

        // Texture chooser section callback (for NEW and OMO_FILE models)
        textureChooserSection.setOnTextureChanged(texturePath -> {
            logger.debug("Texture changed: {}", texturePath);
            // Texture change is handled by BlockModel.setTexturePath() (marks model dirty)
            // Reload the BlockModel in the viewport to apply the new texture
            if (currentEditableModel != null && viewportConnector != null && viewportConnector.isConnected()) {
                viewportConnector.reloadBlockModel(currentEditableModel);
                logger.info("Reloaded BlockModel with new texture: {}", texturePath);
            }
        });

        // Transform section callback
        transformSection.setOnTransformChanged(unused -> {
            // Transform changes are handled automatically by TransformSection
        });
    }

    /**
     * Render the standard properties panel using Dear ImGui.
     */
    public void render() {
        // Apply theme-aware styling
        themeContext.applyPanelStyle();

        // Set size constraints to prevent flickering during drag
        // Min size ensures all controls remain visible, max allows reasonable expansion
        ImGui.setNextWindowSizeConstraints(300, 200, 500, 800);

        // Configure window flags to prevent main window scrollbar
        // NoScrollbar prevents the window itself from having scrollbars
        // The child region will handle scrolling instead (like TextureEditorWindow)
        int windowFlags = ImGuiWindowFlags.NoScrollbar;

        if (ImGui.begin("Properties", windowFlags)) {
            // Create bounded child region to prevent infinite scrolling headers
            // Matches the pattern used in ColorPanel (texture editor)
            ImGui.beginChild("##properties_content", 0, 0, false);

            // Render standard properties: texture selection + transform controls
            ModelState.ModelSource source = modelState.getModelSource();
            if (source == ModelState.ModelSource.BROWSER) {
                textureVariantSection.render();
            } else {
                textureChooserSection.render();
            }
            ImGui.separator();
            transformSection.render();

            // End bounded child region
            ImGui.endChild();
        }
        ImGui.end();

        // Restore default styling
        themeContext.restorePanelStyle();
    }


    // Public API Methods

    /**
     * Set the 3D viewport.
     *
     * @param viewport The viewport instance
     */
    public void setViewport3D(ViewportController viewport) {
        this.viewportConnector = new ViewportAdapter(viewport);

        // Update sections with viewport connector
        transformSection.setViewportConnector(viewportConnector);
    }

    /**
     * Set the current editable model (for NEW and OMO_FILE models).
     * Updates the texture chooser section with the model reference.
     *
     * @param model The editable block model
     */
    public void setEditableModel(BlockModel model) {
        this.currentEditableModel = model;  // Store reference for texture reloading
        textureChooserSection.setModel(model);

        if (model != null) {
            logger.debug("Updated properties panel with editable model: {}", model.getName());
        } else {
            logger.debug("Cleared editable model from properties panel");
        }
    }

    /**
     * Load texture variants for a model (legacy cow functionality removed).
     * This method is deprecated and does nothing.
     *
     * @deprecated Legacy cow model support has been removed
     */
    @Deprecated
    public void loadTextureVariants() {
        logger.debug("loadTextureVariants called but legacy model loading is no longer supported");
    }

    /**
     * Switch to a different texture variant (legacy cow functionality removed).
     * This method is deprecated and does nothing.
     *
     * @param variantName The variant name (unused)
     * @deprecated Legacy cow model support has been removed
     */
    @Deprecated
    private void switchTextureVariant(String variantName) {
        logger.debug("switchTextureVariant called but legacy model variants are no longer supported");
    }

    /**
     * Check if initialized.
     */
    public boolean isInitialized() {
        return initialized;
    }

    /**
     * Get the theme manager (for compatibility).
     */
    public ThemeManager getThemeManager() {
        return themeManager;
    }
}
