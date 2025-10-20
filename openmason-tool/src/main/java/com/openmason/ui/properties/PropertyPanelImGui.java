package com.openmason.ui.properties;

import com.openmason.texture.TextureVariantManager;
import com.openmason.ui.properties.adapters.ViewportAdapter;
import com.openmason.ui.properties.interfaces.IThemeContext;
import com.openmason.ui.properties.interfaces.ITransformState;
import com.openmason.ui.properties.interfaces.IViewportConnector;
import com.openmason.ui.properties.sections.*;
import com.openmason.ui.properties.state.TransformState;
import com.openmason.ui.properties.theming.PanelThemeContext;
import com.openmason.ui.themes.core.ThemeManager;
import com.openmason.ui.viewport.OpenMason3DViewport;
import imgui.ImGui;
import imgui.flag.ImGuiWindowFlags;
import imgui.type.ImBoolean;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.*;
import java.util.concurrent.CompletableFuture;

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
    private final TextureVariantManager textureManager;
    private final ThemeManager themeManager; // Store for getThemeManager() compatibility
    private final IThemeContext themeContext;
    private final ITransformState transformState;
    private IViewportConnector viewportConnector;

    // Section components (composition)
    private final ModelInfoSection modelInfoSection;
    private final TextureVariantSection textureVariantSection;
    private final TransformSection transformSection;
    private final DiagnosticsSection diagnosticsSection;
    private final StatusSection statusSection;

    // State
    private String currentModelName = null;
    private boolean initialized = false;
    private String lastViewportModelCheck = null;

    // Compact/Advanced mode
    private final ImBoolean compactMode = new ImBoolean(true); // Default to compact mode

    /**
     * Create PropertyPanelImGui with dependency injection.
     *
     * @param themeManager ThemeManager instance (can be null for basic functionality)
     */
    public PropertyPanelImGui(ThemeManager themeManager) {
        // Initialize dependencies
        this.textureManager = TextureVariantManager.getInstance();
        this.themeManager = themeManager; // Store for getThemeManager() compatibility
        this.themeContext = new PanelThemeContext(themeManager);
        this.transformState = new TransformState();
        this.viewportConnector = null; // Set later via setViewport3D()

        // Initialize sections (composition)
        this.modelInfoSection = new ModelInfoSection();
        this.textureVariantSection = new TextureVariantSection();
        this.transformSection = new TransformSection(transformState);
        this.diagnosticsSection = new DiagnosticsSection();
        this.statusSection = new StatusSection(themeContext);

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
            statusSection.setStatusMessage("Ready");

        } catch (Exception e) {
            logger.error("Failed to initialize PropertyPanelImGui", e);
            statusSection.setStatusMessage("Initialization failed: " + e.getMessage());
        }
    }

    /**
     * Setup callbacks for section components.
     */
    private void setupSectionCallbacks() {
        // Texture variant section callback
        textureVariantSection.setOnVariantChanged(this::switchTextureVariant);

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

        if (ImGui.begin("Properties", ImGuiWindowFlags.AlwaysAutoResize)) {
            // Render sections based on mode (compact/full mode controlled via Preferences)
            if (compactMode.get()) {
                renderCompactMode();
            } else {
                renderFullMode();
            }
        }
        ImGui.end();

        // Restore default styling
        themeContext.restorePanelStyle();
    }

    /**
     * Render compact mode (essential controls only).
     */
    private void renderCompactMode() {
        textureVariantSection.render();
        ImGui.separator();
        transformSection.render();
        ImGui.separator();
        statusSection.render();
    }

    /**
     * Render full mode (all sections).
     */
    private void renderFullMode() {
        modelInfoSection.render();
        ImGui.separator();
        textureVariantSection.render();
        ImGui.separator();
        transformSection.render();
        ImGui.separator();
        diagnosticsSection.render();
        ImGui.separator();
        statusSection.render();
    }

    // Public API Methods

    /**
     * Set the 3D viewport.
     *
     * @param viewport The viewport instance
     */
    public void setViewport3D(OpenMason3DViewport viewport) {
        this.viewportConnector = new ViewportAdapter(viewport);

        // Update sections with viewport connector
        transformSection.setViewportConnector(viewportConnector);
        diagnosticsSection.setViewportConnector(viewportConnector);
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
        if (statusSection.isLoading()) {
            return;
        }

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

        statusSection.setLoading(true);
        statusSection.setStatusMessage("Loading texture variants for " + modelName + "...");

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

            statusSection.setLoading(false);
            if (modelName.toLowerCase().contains("cow")) {
                statusSection.setStatusMessage("Loaded " + variantsToLoad.size() + " texture variants");
            } else {
                statusSection.setStatusMessage("Model type not supported for texture variants");
            }

        } catch (Exception e) {
            logger.error("Error loading texture variants for model: {}", modelName, e);
            statusSection.setLoading(false);
            statusSection.setStatusMessage("Failed to load texture variants: " + e.getMessage());
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

        statusSection.setLoading(true);
        statusSection.setStatusMessage("Switching to " + variantName + " variant...");

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
                statusSection.setLoading(false);
                statusSection.setStatusMessage(String.format("Switched to %s variant (%dms)", variantName, switchTime));

                if (switchTime > 200) {
                    logger.warn("Texture variant switch took {}ms (target: <200ms)", switchTime);
                }
            } else {
                statusSection.setLoading(false);
                statusSection.setStatusMessage("Failed to switch to variant: " + variantName);
                logger.error("TextureVariantManager failed to switch to variant: {}", variantName);
            }

        } catch (Exception e) {
            logger.error("Error switching to texture variant: {}", variantName, e);
            statusSection.setLoading(false);
            statusSection.setStatusMessage("Error switching variant: " + e.getMessage());
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
     * Get the status message.
     *
     * @return Current status message
     */
    public String getStatusMessage() {
        return statusSection.getStatusMessage();
    }

    /**
     * Check if loading is in progress.
     *
     * @return true if loading
     */
    public boolean isLoadingInProgress() {
        return statusSection.isLoading();
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
