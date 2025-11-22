package com.openmason.ui.properties;

import com.openmason.deprecated.LegacyCowTextureVariantManager;
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
import imgui.type.ImBoolean;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.*;

/**
 * Property Panel coordinator following SOLID, DRY, YAGNI, and KISS principles.
 *
 * This class is a lightweight coordinator that composes specialized section components.
 * It has been refactored from a 1030-line monolithic class to a clean ~250-line coordinator.
 *
 * Architecture:
 * - Uses dependency injection for all dependencies
 * - Implements composition over inheritance
 * - Each section has a single responsibility
 * - All duplicated code extracted to utilities and components
 * - Removed all YAGNI features (simulated validation, unused metrics, etc.)
 *
 * @see com.openmason.ui.properties.interfaces.IPanelSection for section interface
 * @see com.openmason.ui.properties.state.TransformState for transform state management
 */
public class PropertyPanelImGui {

    private static final Logger logger = LoggerFactory.getLogger(PropertyPanelImGui.class);

    // Dependencies (injected)
    private final LegacyCowTextureVariantManager textureManager;
    private final FileDialogService fileDialogService;
    private final ModelState modelState;
    private final ThemeManager themeManager; // Store for getThemeManager() compatibility
    private final IThemeContext themeContext;
    private final ITransformState transformState;
    private IViewportConnector viewportConnector;

    // Section components (composition)
    private final ModelInfoSection modelInfoSection;
    private final TextureVariantSection textureVariantSection;  // For BROWSER models
    private final TextureChooserSection textureChooserSection;  // For NEW and OMO_FILE models
    private final TransformSection transformSection;
    private final DiagnosticsSection diagnosticsSection;

    // State
    private String currentModelName = null;
    private boolean initialized = false;
    private String lastViewportModelCheck = null;
    private BlockModel currentEditableModel = null;  // Current editable model for texture reloading

    // Compact/Advanced mode
    private final ImBoolean compactMode = new ImBoolean(true); // Default to compact mode

    /**
     * Create PropertyPanelImGui with dependency injection.
     *
     * @param themeManager ThemeManager instance (can be null for basic functionality)
     * @param fileDialogService File dialog service for texture selection
     * @param modelState Model state for checking model source
     */
    public PropertyPanelImGui(ThemeManager themeManager, FileDialogService fileDialogService, ModelState modelState) {
        // Initialize dependencies
        this.textureManager = LegacyCowTextureVariantManager.getInstance();
        this.fileDialogService = fileDialogService;
        this.modelState = modelState;
        this.themeManager = themeManager; // Store for getThemeManager() compatibility
        this.themeContext = new PanelThemeContext(themeManager);
        this.transformState = new TransformState();
        this.viewportConnector = null; // Set later via setViewport3D()

        // Initialize sections (composition)
        this.modelInfoSection = new ModelInfoSection();
        this.textureVariantSection = new TextureVariantSection();  // For BROWSER models
        this.textureChooserSection = new TextureChooserSection(fileDialogService, modelState);  // For editable models
        this.transformSection = new TransformSection(transformState);
        this.diagnosticsSection = new DiagnosticsSection();

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
            // Initialize TextureVariantManager
            if (textureManager != null) {
                textureManager.initialize();
            }

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
        textureVariantSection.setOnVariantChanged(variantName -> {
            switchTextureVariant(variantName);
        });

        // Texture chooser section callback (for NEW and OMO_FILE models)
        textureChooserSection.setOnTextureChanged(texturePath -> {
            logger.debug("Texture changed: {}", texturePath);
            // Texture change is handled by BlockModel.setTexturePath() (marks model dirty)
            // Reload the BlockModel in the viewport to apply the new texture
            if (currentEditableModel != null && viewportConnector != null && viewportConnector.isConnected()) {
                viewportConnector.reloadBlockModel(currentEditableModel);
                viewportConnector.requestRender();
                logger.info("Reloaded BlockModel with new texture: {}", texturePath);
            }
        });

        // Transform section callback
        transformSection.setOnTransformChanged(unused -> {
            // Transform changes are handled automatically by TransformSection
        });
    }

    /**
     * Render the properties panel using Dear ImGui.
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

            // Render sections based on mode (compact/full mode controlled via Preferences)
            if (compactMode.get()) {
                renderCompactMode();
            } else {
                renderFullMode();
            }

            // End bounded child region
            ImGui.endChild();
        }
        ImGui.end();

        // Restore default styling
        themeContext.restorePanelStyle();
    }

    /**
     * Render compact mode (essential controls only).
     */
    private void renderCompactMode() {
        // Render appropriate texture section based on model source
        ModelState.ModelSource source = modelState.getModelSource();
        if (source == ModelState.ModelSource.BROWSER) {
            textureVariantSection.render();
        } else {
            textureChooserSection.render();
        }
        ImGui.separator();
        transformSection.render();
    }

    /**
     * Render full mode (all sections).
     */
    private void renderFullMode() {
        modelInfoSection.render();
        ImGui.separator();

        // Render appropriate texture section based on model source
        ModelState.ModelSource source = modelState.getModelSource();
        if (source == ModelState.ModelSource.BROWSER) {
            textureVariantSection.render();
        } else {
            textureChooserSection.render();
        }
        ImGui.separator();

        transformSection.render();
        ImGui.separator();
        diagnosticsSection.render();
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
        diagnosticsSection.setViewportConnector(viewportConnector);
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
            modelInfoSection.setModelName(model.getName());
            logger.debug("Updated properties panel with editable model: {}", model.getName());
        } else {
            logger.debug("Cleared editable model from properties panel");
        }
    }

    /**
     * Load texture variants for a model.
     *
     * @param modelName The model name
     */
    public void loadTextureVariants(String modelName) {
        if (modelName == null || modelName.isEmpty()) {
            logger.warn("Cannot load texture variants for null or empty model name");
            return;
        }

        // Prevent repeated loading
        // (removed status section check)

        // Check if already loaded
        if (modelName.equals(this.currentModelName)) {
            String modelFileName = modelName.toLowerCase().replace(" ", "_");
            if (!modelFileName.equals(lastViewportModelCheck)) {
                ensureViewportHasModel(modelFileName);
                lastViewportModelCheck = modelFileName;
            }
            return;
        }

        this.currentModelName = modelName;
        lastViewportModelCheck = null;

        try {
            // Load variants based on model type
            List<String> variantsToLoad = getVariantsForModel(modelName);

            // Update sections
            textureVariantSection.setAvailableVariants(variantsToLoad.toArray(new String[0]));
            modelInfoSection.setModelName(modelName);
            modelInfoSection.setVariantCount(variantsToLoad.size());

            // Load model into viewport
            String modelFileName = modelName.toLowerCase().replace(" ", "_");
            if (viewportConnector != null && viewportConnector.isConnected()) {
                viewportConnector.loadModel(modelFileName);
            }

        } catch (Exception e) {
            logger.error("Error loading texture variants for model: {}", modelName, e);
        }
    }

    /**
     * Switch to a different texture variant.
     *
     * @param variantName The variant name
     */
    public void switchTextureVariant(String variantName) {
        if (variantName == null || variantName.isEmpty()) {
            logger.warn("Cannot switch to null or empty texture variant");
            return;
        }

        long startTime = System.currentTimeMillis();

        try {
            String variantLower = variantName.toLowerCase();

            // Switch variant using TextureVariantManager
            boolean success = textureManager.switchToVariant(variantLower);

            if (success) {
                // Update viewport
                if (viewportConnector != null && viewportConnector.isConnected()) {
                    viewportConnector.setTextureVariant(variantLower);
                    viewportConnector.requestRender();
                }

                long switchTime = System.currentTimeMillis() - startTime;

                if (switchTime > 200) {
                    logger.warn("Texture variant switch took {}ms (target: <200ms)", switchTime);
                }
            } else {
                logger.error("TextureVariantManager failed to switch to variant: {}", variantName);
            }

        } catch (Exception e) {
            logger.error("Error switching to texture variant: {}", variantName, e);
        }
    }

    /**
     * Set compact mode.
     * Called from PreferencesDialog when user changes the setting.
     *
     * @param compact true for compact mode, false for full mode
     */
    public void setCompactMode(boolean compact) {
        this.compactMode.set(compact);

        // Update section visibility based on mode
        modelInfoSection.setVisible(!compact);
        diagnosticsSection.setVisible(!compact);
        transformSection.setShowAdvancedOptions(!compact);

        logger.debug("UI mode changed to: {}", compact ? "Compact" : "Full");
    }


    /**
     * Get the selected variant.
     *
     * @return Selected variant name
     */
    public String getSelectedVariant() {
        return textureVariantSection.getSelectedVariant();
    }

    /**
     * Check if initialized.
     *
     * @return true if initialized
     */
    public boolean isInitialized() {
        return initialized;
    }

    /**
     * Get the theme manager (for compatibility).
     *
     * @return ThemeManager instance or null
     */
    public ThemeManager getThemeManager() {
        return themeManager;
    }

    /**
     * Check if theme system is available.
     *
     * @return true if available
     */
    public boolean isThemeSystemAvailable() {
        return themeContext.isAvailable();
    }

    // Private helper methods

    /**
     * Get variants for a model type.
     */
    private List<String> getVariantsForModel(String modelName) {
        if (modelName.toLowerCase().contains("cow")) {
            return Arrays.asList("Default", "Angus", "Highland", "Jersey");
        }
        return Arrays.asList("Default");
    }

    /**
     * Ensure viewport has the model loaded.
     */
    private void ensureViewportHasModel(String modelFileName) {
        if (viewportConnector == null || !viewportConnector.isConnected()) {
            return;
        }

        String currentViewportModel = viewportConnector.getCurrentModelName();
        boolean hasActualModel = viewportConnector.hasModel();

        if (!modelFileName.equals(currentViewportModel) || !hasActualModel) {
            viewportConnector.loadModel(modelFileName);
        }
    }
}
