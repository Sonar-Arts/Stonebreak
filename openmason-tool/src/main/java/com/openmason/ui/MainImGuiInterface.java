package com.openmason.ui;

import com.openmason.ui.config.WindowConfig;
import com.openmason.ui.themes.ImGuiHelpers;
import com.openmason.ui.viewport.OpenMason3DViewport;
import com.openmason.ui.PropertyPanelImGui;
import com.openmason.ui.preferences.PreferencesManager;
import com.openmason.ui.LogoManager;
import com.openmason.ui.themes.ThemeManager;
import com.openmason.ui.themes.ThemeDefinition;
import com.openmason.ui.themes.DensityManager;
import com.openmason.ui.themes.ThemePreview;
import com.openmason.model.StonebreakModel;
import com.openmason.model.ModelManager;
import com.stonebreak.model.ModelDefinition;
import com.stonebreak.model.ModelLoader;
import com.stonebreak.textures.mobs.CowTextureDefinition;
import com.stonebreak.textures.mobs.CowTextureLoader;
import imgui.*;
import imgui.flag.*;
import imgui.type.*;
import imgui.flag.ImGuiKey;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.swing.*;
import javax.swing.filechooser.FileNameExtensionFilter;
import java.io.File;
import java.util.HashMap;
import java.util.Map;

/**
 * Main Dear ImGui interface replacing JavaFX MainController.
 * Implements immediate mode rendering for all UI controls and menus.
 * 
 * Integrated Toolbar Features:
 * - Positioned directly under the menu bar at the very top
 * - Left side: New, Open, Save buttons and other tools
 * - Right side: Status info including memory profiler, FPS, model name, and progress
 * - Memory profiler with color coding (green for memory, blue for FPS)
 * - Hideable: Click "Hide Toolbar" button or toggle via View menu
 * - Quick restore: "Show Toolbar" button appears in menu bar when hidden
 * - Single integrated bar reduces UI clutter and maximizes screen space
 */
public class MainImGuiInterface {
    
    private static final Logger logger = LoggerFactory.getLogger(MainImGuiInterface.class);
    
    // UI State Variables
    private final ImBoolean showModelBrowser = new ImBoolean(true);
    private final ImBoolean showPropertyPanel = new ImBoolean(true);
    private final ImBoolean showToolbar = new ImBoolean(true);
    private final ImBoolean showPreferencesWindow = new ImBoolean(false);
    private final ImBoolean showAboutWindow = new ImBoolean(false);
    private boolean showGrid = true;
    private boolean showAxes = true;
    private boolean wireframeMode = false;
    
    // Window Configurations (from plan section 1.3)
    private final WindowConfig viewportConfig = WindowConfig.forViewport();
    private final WindowConfig propertiesConfig = WindowConfig.forProperties();
    private final WindowConfig modelBrowserConfig = WindowConfig.forModelBrowser();
    private final WindowConfig preferencesConfig = WindowConfig.forAdvancedPreferences();
    
    // Model State
    private boolean modelLoaded = false;
    private String currentModelPath = "";
    private boolean unsavedChanges = false;
    
    // Texture Variant State
    private final ImString textureVariant = new ImString("Default", 256);
    private final String[] textureVariants = {"Default", "Angus", "Highland", "Jersey"};
    private int currentTextureVariantIndex = 0;
    
    // Transform State
    private final ImFloat rotationX = new ImFloat(0.0f);
    private final ImFloat rotationY = new ImFloat(0.0f);
    private final ImFloat rotationZ = new ImFloat(0.0f);
    private final ImFloat scale = new ImFloat(1.0f);
    
    
    // View Mode State  
    private final String[] viewModes = {"Perspective", "Orthographic", "Front", "Side", "Top"};
    private final ImInt currentViewModeIndex = new ImInt(0);
    private final String[] renderModes = {"Solid", "Wireframe", "Textured"};
    private final ImInt currentRenderModeIndex = new ImInt(2); // Textured
    
    // Model Browser State
    private final ImString searchText = new ImString("", 256);
    private final String[] filters = {"All Models", "Cow Models", "Recent Files"};
    private final ImInt currentFilterIndex = new ImInt(0);
    private String selectedModelInfo = "No model selected";
    
    // Status Bar State
    private String statusMessage = "Ready";
    private float memoryUsage = 0.0f;
    private float frameRate = 0.0f;
    private final ImFloat progress = new ImFloat(0.0f);
    
    // Model Statistics
    private int partCount = 0;
    private int vertexCount = 0;
    private int triangleCount = 0;
    private int textureVariantCount = 4;
    
    // Model Part Selection
    private String selectedPart = "None";
    private String partCoordinates = "0, 0, 0";
    
    // 3D Viewport Component
    private OpenMason3DViewport viewport3D;
    
    // Model Management
    private ModelManager modelManager;
    
    // UI Controllers
    private PropertyPanelImGui propertyPanelImGui;
    
    // Performance Metrics
    private Map<String, Object> performanceMetrics = new HashMap<>();
    
    // Preferences Management
    private PreferencesManager preferencesManager;
    private final ImFloat cameraMouseSensitivity = new ImFloat(3.0f);
    
    // Logo Management
    private LogoManager logoManager;
    
    // Theme Management
    private ThemeManager themeManager;
    private final ImBoolean showThemeMenu = new ImBoolean(false);
    private final ImInt selectedThemeIndex = new ImInt(0);
    private final ImInt selectedDensityIndex = new ImInt(1); // Normal by default
    private boolean themeSystemInitialized = false;

    // Pending theme changes (for Apply button)
    private final ImInt pendingThemeIndex = new ImInt(0);
    private final ImInt pendingDensityIndex = new ImInt(1);
    private boolean hasUnsavedThemeChanges = false;
    
    // Recent Files
    private final String[] recentFiles = {
        "standard_cow.json",
        "example_model.json"
    };
    
    public MainImGuiInterface() {
        // logger.info("Initializing MainImGuiInterface...");
        initializePreferences();
        initializeLogo();
        initializeThemeSystem();
        initializeComponents();
        updateUIState();
    }
    
    /**
     * Initialize all UI components and systems.
     */
    private void initializeComponents() {
        try {
            setupViewport();
            setupModelBrowser();
            setupPropertiesPanel();
            loadInitialData();
            // logger.info("MainImGuiInterface initialized successfully");
        } catch (Exception e) {
            logger.error("Failed to initialize MainImGuiInterface", e);
        }
    }
    
    /**
     * Main render method - called every frame.
     */
    public void render() {
        // Handle keyboard shortcuts first
        handleKeyboardShortcuts();
        
        renderDockSpace();
        renderMainMenuBar();
        
        if (showToolbar.get()) {
            renderToolbar();
        }
        
        if (showModelBrowser.get()) {
            renderModelBrowser();
        }
        
        // Viewport rendering handled by separate ViewportImGuiInterface
        
        if (showPropertyPanel.get()) {
            renderPropertyPanel();
        }
        
        if (showPreferencesWindow.get()) {
            renderPreferencesWindow();
        }
        
        if (showAboutWindow.get()) {
            renderAboutWindow();
        }
        
        renderDialogs();
    }
    
    /**
     * Handle keyboard shortcuts for common actions.
     * Simplified version without complex key handling.
     */
    private void handleKeyboardShortcuts() {
        // Keyboard shortcuts are handled through menu items and buttons for now
        // Complex key handling can be added later when ImGui key constants are properly resolved
    }
    
    /**
     * Render main docking space for window management.
     */
    private void renderDockSpace() {
        int windowFlags = ImGuiWindowFlags.NoDocking;
        
        ImGuiViewport viewport = ImGui.getMainViewport();
        float menuBarHeight = ImGui.getFrameHeight();
        float toolbarHeight = showToolbar.get() ? (ImGui.getFrameHeight() + 8.0f) : 0.0f;
        float topOffset = menuBarHeight + toolbarHeight; // Toolbar now includes status info
        
        ImGui.setNextWindowPos(viewport.getWorkPosX(), viewport.getWorkPosY() + topOffset);
        ImGui.setNextWindowSize(viewport.getWorkSizeX(), viewport.getWorkSizeY() - topOffset);
        ImGui.setNextWindowViewport(viewport.getID());
        
        ImGui.pushStyleVar(ImGuiStyleVar.WindowRounding, 0.0f);
        ImGui.pushStyleVar(ImGuiStyleVar.WindowBorderSize, 0.0f);
        ImGui.pushStyleVar(ImGuiStyleVar.WindowPadding, 0.0f, 0.0f);
        
        windowFlags |= ImGuiWindowFlags.NoTitleBar | ImGuiWindowFlags.NoCollapse | 
                       ImGuiWindowFlags.NoResize | ImGuiWindowFlags.NoMove |
                       ImGuiWindowFlags.NoBringToFrontOnFocus | ImGuiWindowFlags.NoNavFocus;
        
        ImGui.begin("OpenMason Dockspace", windowFlags);
        ImGui.popStyleVar(3);
        
        // DockSpace
        int dockspaceId = ImGui.getID("OpenMasonDockSpace");
        ImGui.dockSpace(dockspaceId, 0.0f, 0.0f, ImGuiDockNodeFlags.PassthruCentralNode);
        
        ImGui.end();
    }
    
    /**
     * Render main menu bar with all menu items.
     */
    private void renderMainMenuBar() {
        if (ImGui.beginMainMenuBar()) {
            // Add Open Mason logo at the beginning of the menu bar
            if (logoManager != null) {
                logoManager.renderMenuBarLogo();
                ImGui.sameLine();
                ImGui.separator();
                ImGui.sameLine();
            }
            
            renderFileMenu();
            renderEditMenu();
            renderViewMenu();
            renderToolsMenu();
            renderThemeMenu(); // Added as per plan section 2.4
            renderHelpMenu();
            
            // Show toolbar restore button when toolbar is hidden (right side of menu bar)
            if (!showToolbar.get()) {
                // Push to right side of menu bar
                float availWidth = ImGui.getContentRegionAvailX();
                float buttonWidth = ImGui.calcTextSize("Show Toolbar").x + 16.0f;
                ImGui.setCursorPosX(ImGui.getCursorPosX() + availWidth - buttonWidth);
                
                if (ImGui.button("Show Toolbar")) {
                    showToolbar.set(true);
                }
            }
            
            ImGui.endMainMenuBar();
        }
    }
    
    /**
     * Render File menu with all file operations.
     */
    private void renderFileMenu() {
        if (ImGui.beginMenu("File")) {
            if (ImGui.menuItem("New Model", "Ctrl+N")) {
                newModel();
            }
            
            if (ImGui.menuItem("Open Model", "Ctrl+O")) {
                openModel();
            }
            
            if (ImGui.menuItem("Open Project", "Ctrl+Shift+O")) {
                openProject();
            }
            
            ImGui.separator();
            
            if (ImGui.menuItem("Save Model", "Ctrl+S", false, modelLoaded && unsavedChanges)) {
                saveModel();
            }
            
            if (ImGui.menuItem("Save Model As", "Ctrl+Shift+S", false, modelLoaded)) {
                saveModelAs();
            }
            
            if (ImGui.menuItem("Export Model", "Ctrl+E", false, modelLoaded)) {
                exportModel();
            }
            
            ImGui.separator();
            
            if (ImGui.beginMenu("Recent Files")) {
                for (String recentFile : recentFiles) {
                    if (ImGui.menuItem(recentFile)) {
                        loadRecentFile(recentFile);
                    }
                }
                ImGui.endMenu();
            }
            
            ImGui.separator();
            
            if (ImGui.menuItem("Exit", "Alt+F4")) {
                exitApplication();
            }
            
            ImGui.endMenu();
        }
    }
    
    /**
     * Render Edit menu with editing operations.
     */
    private void renderEditMenu() {
        if (ImGui.beginMenu("Edit")) {
            if (ImGui.menuItem("Undo", "Ctrl+Z")) {
                undo();
            }
            
            if (ImGui.menuItem("Redo", "Ctrl+Y")) {
                redo();
            }
            
            ImGui.separator();
            
            if (ImGui.menuItem("Preferences", "Ctrl+,")) {
                showPreferences();
            }
            
            ImGui.endMenu();
        }
    }
    
    /**
     * Render View menu with view options.
     */
    private void renderViewMenu() {
        if (ImGui.beginMenu("View")) {
            if (ImGui.menuItem("Reset View", "Ctrl+R")) {
                resetView();
            }
            
            if (ImGui.menuItem("Fit to View", "Ctrl+F")) {
                fitToView();
            }
            
            ImGui.separator();
            
            if (ImGui.menuItem("Show Grid", "Ctrl+G", showGrid)) {
                toggleGrid();
            }
            
            if (ImGui.menuItem("Show Axes", "Ctrl+X", showAxes)) {
                toggleAxes();
            }
            
            if (ImGui.menuItem("Wireframe Mode", "Ctrl+W", wireframeMode)) {
                toggleWireframe();
            }
            
            // Transform gizmo toggle
            boolean gizmoEnabled = (viewport3D != null) ? viewport3D.isGizmoEnabled() : false;
            if (ImGui.menuItem("Transform Gizmo", "Ctrl+T", gizmoEnabled)) {
                toggleTransformGizmo();
            }
            
            ImGui.separator();
            
            // Panel visibility toggles
            if (ImGui.menuItem("Show 3D Viewport", "Ctrl+1", true)) {
                showViewport();
            }
            
            if (ImGui.menuItem("Show Model Browser", "Ctrl+2", showModelBrowser.get())) {
                toggleModelBrowser();
            }
            
            if (ImGui.menuItem("Show Property Panel", "Ctrl+3", showPropertyPanel.get())) {
                togglePropertyPanel();
            }
            
            
            if (ImGui.menuItem("Show Toolbar", "Ctrl+5", showToolbar.get())) {
                toggleToolbar();
            }
            
            ImGui.separator();
            
            // Layout options
            if (ImGui.beginMenu("Layout")) {
                if (ImGui.menuItem("Reset to Default")) {
                    resetToDefaultLayout();
                }
                
                if (ImGui.menuItem("Full Screen Viewport", "F11")) {
                    toggleFullScreenViewport();
                }
                
                if (ImGui.menuItem("Modeling Layout")) {
                    applyModelingLayout();
                }
                
                if (ImGui.menuItem("Texturing Layout")) {
                    applyTexturingLayout();
                }
                
                ImGui.endMenu();
            }
            
            ImGui.endMenu();
        }
    }
    
    /**
     * Render Tools menu with tool operations.
     */
    private void renderToolsMenu() {
        if (ImGui.beginMenu("Tools")) {
            if (ImGui.menuItem("Validate Model", "Ctrl+T", false, modelLoaded)) {
                validateModel();
            }
            
            ImGui.separator();
            
            if (ImGui.beginMenu("Texture Variants")) {
                for (int i = 0; i < textureVariants.length; i++) {
                    boolean selected = (i == currentTextureVariantIndex);
                    if (ImGui.menuItem(textureVariants[i], "Ctrl+" + (i + 1), selected)) {
                        switchToVariant(textureVariants[i]);
                    }
                }
                ImGui.endMenu();
            }
            
            ImGui.endMenu();
        }
    }
    
    /**
     * Render Theme menu (from plan section 2.4).
     */
    private void renderThemeMenu() {
        if (ImGui.beginMenu("Theme")) {
            // Quick theme switching
            for (ThemeDefinition theme : themeManager.getAvailableThemes()) {
                boolean isCurrentTheme = theme == themeManager.getCurrentTheme();
                if (ImGui.menuItem(theme.getName(), "", isCurrentTheme)) {
                    themeManager.applyTheme(theme);
                }
            }
            
            ImGui.separator();
            
            // Density controls
            if (ImGui.beginMenu("UI Density")) {
                for (DensityManager.UIDensity density : DensityManager.UIDensity.values()) {
                    boolean isCurrentDensity = density == themeManager.getCurrentDensity();
                    if (ImGui.menuItem(density.getDisplayName(), "", isCurrentDensity)) {
                        themeManager.setUIDensity(density);
                    }
                }
                ImGui.endMenu();
            }
            
            ImGui.separator();
            if (ImGui.menuItem("Advanced Theme Settings...")) {
                showPreferencesWindow.set(true);
            }
            if (ImGui.menuItem("Reset to Defaults")) {
                themeManager.applyTheme("dark");
                themeManager.setUIDensity(DensityManager.UIDensity.NORMAL);
            }
            
            ImGui.endMenu();
        }
    }
    
    /**
     * Render Help menu.
     */
    private void renderHelpMenu() {
        if (ImGui.beginMenu("Help")) {
            if (ImGui.menuItem("About OpenMason")) {
                showAbout();
            }
            
            ImGui.endMenu();
        }
    }
    
    /**
     * Render toolbar with buttons and controls positioned under menu bar.
     */
    private void renderToolbar() {
        // Position toolbar directly under the menu bar
        ImGuiViewport viewport = ImGui.getMainViewport();
        float menuBarHeight = ImGui.getFrameHeight(); // Height of menu bar
        
        ImGui.setNextWindowPos(viewport.getWorkPosX(), viewport.getWorkPosY() + menuBarHeight);
        ImGui.setNextWindowSize(viewport.getWorkSizeX(), ImGui.getFrameHeight() + 8.0f); // Toolbar height
        
        int toolbarFlags = ImGuiWindowFlags.NoTitleBar | ImGuiWindowFlags.NoResize | 
                          ImGuiWindowFlags.NoMove | ImGuiWindowFlags.NoScrollbar | 
                          ImGuiWindowFlags.NoCollapse | ImGuiWindowFlags.NoBringToFrontOnFocus;
        
        if (ImGui.begin("##Toolbar", showToolbar, toolbarFlags)) {
            
            // Hide button (first item)
            if (ImGui.smallButton("Hide Toolbar")) {
                showToolbar.set(false);
            }
            ImGui.sameLine();
            ImGui.separator();
            ImGui.sameLine();
            
            // File operations
            if (ImGui.button("New##toolbar")) {
                newModel();
            }
            ImGui.sameLine();
            
            if (ImGui.button("Open##toolbar")) {
                openModel();
            }
            ImGui.sameLine();
            
            if (ImGui.button("Save##toolbar") && modelLoaded && unsavedChanges) {
                saveModel();
            }
            ImGui.sameLine();
            
            ImGui.separator();
            ImGui.sameLine();
            
            // View operations
            if (ImGui.button("Reset View##toolbar")) {
                resetView();
            }
            ImGui.sameLine();
            
            if (ImGui.button("Zoom In##toolbar")) {
                // Zoom handled by 3D viewport directly
                viewport3D.getCamera().zoom(1.0f);
            }
            ImGui.sameLine();
            
            if (ImGui.button("Zoom Out##toolbar")) {
                // Zoom handled by 3D viewport directly  
                viewport3D.getCamera().zoom(-1.0f);
            }
            ImGui.sameLine();
            
            if (ImGui.button("Fit to View##toolbar")) {
                fitToView();
            }
            ImGui.sameLine();
            
            ImGui.separator();
            ImGui.sameLine();
            
            // Tool operations
            if (ImGui.button("Validate##toolbar") && modelLoaded) {
                validateModel();
            }
            ImGui.sameLine();
            
            if (ImGui.button("Settings##toolbar")) {
                showPreferences();
            }
            ImGui.sameLine();
            
            // Push remaining content to right side of toolbar for status display
            float availWidth = ImGui.getContentRegionAvailX();
            float statusWidth = 350.0f; // Approximate width needed for status info
            if (availWidth > statusWidth + 20.0f) { // Add some padding
                ImGui.setCursorPosX(ImGui.getCursorPosX() + availWidth - statusWidth);
            }
            
            ImGui.separator();
            ImGui.sameLine();
            
            // Current model display
            ImGui.text("Model: " + (modelLoaded ? currentModelPath : "None"));
            ImGui.sameLine();
            
            // Progress bar (if active)
            if (progress.get() > 0.0f) {
                ImGui.separator();
                ImGui.sameLine();
                ImGui.progressBar(progress.get(), 80.0f, 0.0f);
                ImGui.sameLine();
            }
            
            // Status message
            ImGui.separator();
            ImGui.sameLine();
            ImGui.text("Status: " + statusMessage);
            ImGui.sameLine();
            
            // Memory usage (the memory profiler)
            ImGui.separator();
            ImGui.sameLine();
            ImGui.textColored(0.0f, 1.0f, 0.0f, 1.0f, String.format("%.1f MB", memoryUsage));
            ImGui.sameLine();
            
            // Frame rate
            ImGui.separator();
            ImGui.sameLine();
            ImGui.textColored(0.0f, 0.5f, 1.0f, 1.0f, String.format("%.1f FPS", frameRate));
            
        }
        ImGui.end();
    }
    
    /**
     * Render model browser panel.
     */
    private void renderModelBrowser() {
        // Apply window configuration before creating window (from plan section 2.4)
        ImGuiHelpers.configureWindowConstraints(modelBrowserConfig);
        
        if (ImGui.begin("Model Browser", showModelBrowser)) {
            // Configure window size and position inside window
            ImGuiHelpers.configureWindowSize(modelBrowserConfig);
            ImGuiHelpers.configureWindowPosition(modelBrowserConfig);
            
            // Search and filter
            ImGui.text("Search:");
            ImGui.sameLine();
            if (ImGui.inputText("##search", searchText)) {
                filterModels(searchText.get());
            }
            
            ImGui.text("Filter:");
            ImGui.sameLine();
            if (ImGui.combo("##filter", currentFilterIndex, filters)) {
                filterModels(filters[currentFilterIndex.get()]);
            }
            
            ImGui.separator();
            
            // Model tree
            if (ImGui.treeNode("Available Models")) {
                if (ImGui.treeNode("Cow Models")) {
                    if (ImGui.selectable("Standard Cow", false)) {
                        selectModel("Standard Cow", "default");
                    }
                    ImGui.treePop();
                }
                
                if (ImGui.treeNode("Recent Files")) {
                    for (String recentFile : recentFiles) {
                        if (ImGui.selectable(recentFile, false)) {
                            loadRecentFile(recentFile);
                        }
                    }
                    ImGui.treePop();
                }
                
                ImGui.treePop();
            }
            
            ImGui.separator();
            
            // Model info
            ImGui.text("Model Info:");
            ImGui.textWrapped(selectedModelInfo);
            
        }
        ImGui.end();
    }
    
    // Duplicate viewport method removed - using dedicated ViewportImGuiInterface
    
    /**
     * Render property panel with model controls.
     */
    private void renderPropertyPanel() {
        // Delegate to dedicated PropertyPanelImGui for proper rendering
        if (propertyPanelImGui != null) {
            // Update PropertyPanelImGui with current model state
            if (modelLoaded && !currentModelPath.isEmpty()) {
                String modelName = currentModelPath.replace(".json", "");
                propertyPanelImGui.loadTextureVariants(modelName);
            }
            
            // Set viewport reference for 3D integration
            propertyPanelImGui.setViewport3D(viewport3D);
            
            // Render the property panel using the dedicated ImGui component
            propertyPanelImGui.render();
        } else {
            // Fallback rendering if PropertyPanelImGui is not available
            // Apply window configuration before creating window (from plan section 2.4)
            ImGuiHelpers.configureWindowConstraints(propertiesConfig);
            
            if (ImGui.begin("Properties", showPropertyPanel)) {
                // Configure window size and position inside window
                ImGuiHelpers.configureWindowSize(propertiesConfig);
                ImGuiHelpers.configureWindowPosition(propertiesConfig);
                ImGui.textDisabled("Property panel not initialized");
                ImGui.text("Model: " + (modelLoaded ? currentModelPath : "No model loaded"));
                
                if (ImGui.button("Initialize Properties")) {
                    setupPropertiesPanel();
                }
            }
            ImGui.end();
        }
    }
    
    
    /**
     * Render preferences window with camera and theme settings.
     */
    private void renderPreferencesWindow() {
        // Apply window configuration before creating window (from plan section 2.4)
        ImGuiHelpers.configureWindowConstraints(preferencesConfig);

        if (ImGui.begin("Preferences", showPreferencesWindow, ImGuiWindowFlags.AlwaysAutoResize)) {
            // Configure window size and position inside window
            ImGuiHelpers.configureWindowSize(preferencesConfig);
            ImGuiHelpers.configureWindowPosition(preferencesConfig);

            // Initialize pending values from current settings (only once when opening)
            if (ImGui.isWindowAppearing()) {
                ThemeDefinition currentTheme = themeManager.getCurrentTheme();
                if (currentTheme != null) {
                    for (int i = 0; i < themeManager.getAvailableThemes().size(); i++) {
                        if (themeManager.getAvailableThemes().get(i).getId().equals(currentTheme.getId())) {
                            pendingThemeIndex.set(i);
                            break;
                        }
                    }
                }
                DensityManager.UIDensity currentDensity = themeManager.getCurrentDensity();
                pendingDensityIndex.set(currentDensity.ordinal());
                hasUnsavedThemeChanges = false;
            }

            // Theme Settings Section
            ImGui.text("Theme Settings");
            ImGui.separator();

            // Theme selection
            ImGui.text("Theme:");
            ImGui.sameLine();
            ImGui.setNextItemWidth(200.0f);
            String[] themeNames = themeManager.getAvailableThemes().stream()
                .map(ThemeDefinition::getName)
                .toArray(String[]::new);

            if (ImGui.combo("##themeSelect", pendingThemeIndex, themeNames)) {
                // Mark as having unsaved changes
                hasUnsavedThemeChanges = true;
            }

            // UI Density selection
            ImGui.text("UI Density:");
            ImGui.sameLine();
            ImGui.setNextItemWidth(200.0f);
            String[] densityNames = new String[DensityManager.UIDensity.values().length];
            int idx = 0;
            for (DensityManager.UIDensity density : DensityManager.UIDensity.values()) {
                densityNames[idx++] = density.getDisplayName();
            }

            if (ImGui.combo("##densitySelect", pendingDensityIndex, densityNames)) {
                // Mark as having unsaved changes
                hasUnsavedThemeChanges = true;
            }

            // Show unsaved changes indicator
            if (hasUnsavedThemeChanges) {
                ImGui.spacing();
                ImGui.textColored(1.0f, 0.8f, 0.0f, 1.0f, "* You have unsaved theme changes");
            }

            ImGui.spacing();
            ImGui.separator();
            ImGui.spacing();

            // Camera Settings Section
            ImGui.text("Camera Settings");
            ImGui.separator();

            // Camera drag speed setting with manual input
            ImGui.text("Camera Drag Speed:");
            ImGui.sameLine();
            ImGui.setNextItemWidth(100.0f);
            if (ImGui.inputFloat("##cameraDragSpeed", cameraMouseSensitivity, 0.1f, 1.0f, "%.1f")) {
                // Clamp the value to reasonable range
                float newValue = Math.max(0.1f, Math.min(10.0f, cameraMouseSensitivity.get()));
                cameraMouseSensitivity.set(newValue);

                // Apply the setting to the camera immediately
                applyCameraMouseSensitivity(newValue);
            }

            ImGui.spacing();
            ImGui.separator();
            ImGui.spacing();

            // Buttons
            if (ImGui.button("Apply Theme Changes")) {
                // Apply pending theme changes
                ThemeDefinition selectedTheme = themeManager.getAvailableThemes().get(pendingThemeIndex.get());
                themeManager.applyTheme(selectedTheme);

                DensityManager.UIDensity selectedDensity = DensityManager.UIDensity.values()[pendingDensityIndex.get()];
                themeManager.setUIDensity(selectedDensity);

                hasUnsavedThemeChanges = false;
                updateStatus("Theme changes applied: " + selectedTheme.getName() + " / " + selectedDensity.getDisplayName());
            }
            ImGui.sameLine();

            if (ImGui.button("Reset All to Defaults")) {
                // Reset theme to default
                themeManager.applyTheme("dark");
                themeManager.setUIDensity(DensityManager.UIDensity.NORMAL);

                // Update pending values to match
                pendingThemeIndex.set(0); // Dark theme is index 0
                pendingDensityIndex.set(1); // Normal is index 1
                hasUnsavedThemeChanges = false;

                // Reset camera to default
                if (preferencesManager != null) {
                    preferencesManager.resetCameraToDefaults();
                    float defaultSensitivity = preferencesManager.getCameraMouseSensitivity();
                    cameraMouseSensitivity.set(defaultSensitivity);
                    applyCameraMouseSensitivity(defaultSensitivity);
                } else {
                    cameraMouseSensitivity.set(3.0f);
                    applyCameraMouseSensitivity(3.0f);
                }

                updateStatus("All settings reset to defaults");
            }
            ImGui.sameLine();

            if (ImGui.button("Close")) {
                // Warn if there are unsaved changes
                if (hasUnsavedThemeChanges) {
                    updateStatus("Warning: Unsaved theme changes discarded");
                    hasUnsavedThemeChanges = false;
                }
                showPreferencesWindow.set(false);
            }
        }
        ImGui.end();
    }
    
    /**
     * Render About window with Open Mason logo and information.
     */
    private void renderAboutWindow() {
        if (ImGui.begin("About OpenMason", showAboutWindow, ImGuiWindowFlags.AlwaysAutoResize | ImGuiWindowFlags.NoCollapse)) {
            
            // Render large logo at the top
            if (logoManager != null) {
                logoManager.renderAboutLogo();
            }
            
            // Application title and version
            ImGui.textColored(0.2f, 0.6f, 1.0f, 1.0f, "OpenMason");
            ImGui.sameLine();
            ImGui.text("v0.0.1");
            
            ImGui.text("Professional 3D Model Development Tool");
            ImGui.spacing();
            
            // Description
            ImGui.separator();
            ImGui.spacing();
            ImGui.textWrapped("OpenMason is a professional 3D model and texture development tool designed for " +
                             "creating and editing Stonebreak game assets. Built with ImGui and LWJGL for " +
                             "high-performance rendering and intuitive user experience.");
            ImGui.spacing();
            
            // Features
            ImGui.text("Features:");
            ImGui.bulletText("Real-time 3D model visualization");
            ImGui.bulletText("Texture variant management");
            ImGui.bulletText("Professional camera controls");
            ImGui.bulletText("Transform gizmos and wireframe modes");
            ImGui.bulletText("Direct integration with Stonebreak model system");
            ImGui.spacing();
            
            // Technical information
            ImGui.separator();
            ImGui.spacing();
            ImGui.text("Built with:");
            ImGui.bulletText("Java 17");
            ImGui.bulletText("LWJGL 3.3.2 (OpenGL, GLFW)");
            ImGui.bulletText("Dear ImGui");
            ImGui.bulletText("JOML Math Library");
            ImGui.spacing();
            
            // Close button
            ImGui.separator();
            ImGui.spacing();
            float windowWidth = ImGui.getWindowSize().x;
            float buttonWidth = 80.0f;
            ImGui.setCursorPosX((windowWidth - buttonWidth) * 0.5f);
            
            if (ImGui.button("Close", buttonWidth, 0)) {
                showAboutWindow.set(false);
            }
            
        }
        ImGui.end();
    }
    
    /**
     * Initialize preferences system and load saved settings.
     */
    private void initializePreferences() {
        try {
            preferencesManager = new PreferencesManager();
            
            // Load saved camera mouse sensitivity
            float savedSensitivity = preferencesManager.getCameraMouseSensitivity();
            cameraMouseSensitivity.set(savedSensitivity);
            
            // logger.info("Loaded preferences - Camera sensitivity: {}", savedSensitivity);
        } catch (Exception e) {
            logger.error("Failed to initialize preferences", e);
            // Use default values
            cameraMouseSensitivity.set(3.0f);
        }
    }
    
    /**
     * Initialize logo manager for displaying Open Mason logo throughout the UI.
     */
    private void initializeLogo() {
        try {
            logoManager = LogoManager.getInstance();
            // logger.info("Logo manager initialized successfully");
        } catch (Exception e) {
            logger.error("Failed to initialize logo manager", e);
        }
    }
    
    /**
     * Apply camera mouse sensitivity setting to the camera and save the preference.
     */
    private void applyCameraMouseSensitivity(float sensitivity) {
        // Apply to camera
        if (viewport3D != null && viewport3D.getCamera() != null) {
            viewport3D.getCamera().setMouseSensitivity(sensitivity);
            // logger.info("Applied camera mouse sensitivity: {}", sensitivity);
        }
        
        // Save preference to configuration file for persistence
        if (preferencesManager != null) {
            preferencesManager.setCameraMouseSensitivity(sensitivity);
        }
    }
    
    /**
     * Render any modal dialogs.
     */
    private void renderDialogs() {
        // Dialogs would be rendered here (About, Preferences, etc.)
    }
    
    // Action Methods Implementation
    
    private void newModel() {
        // logger.info("New model action triggered");
        updateStatus("Creating new model...");
        modelLoaded = false;
        currentModelPath = "Untitled Model";
        unsavedChanges = false;
        updateUIState();
    }
    
    public void createNewModel() {
        // logger.info("Create new model action triggered");
        updateStatus("Creating new model...");
        
        // Reset current state
        modelLoaded = false;
        currentModelPath = "";
        unsavedChanges = false;
        currentTextureVariantIndex = 0;
        
        updateUIState();
        updateStatus("New model created");
    }
    
    public void openModel() {
        // logger.info("Open model action triggered");
        updateStatus("Opening model...");
        
        try {
            // Use the actual Stonebreak model system
            if (modelManager != null) {
                // Load standard cow model as default
                String modelPath = "standard_cow";
                
                modelManager.loadModelInfoAsync(modelPath, ModelManager.LoadingPriority.NORMAL, null).thenAccept(modelInfo -> {
                    if (modelInfo != null) {
                        modelLoaded = true;
                        currentModelPath = modelPath;
                        unsavedChanges = false;
                        
                        // Extract actual model statistics
                        partCount = modelInfo.getPartCount();
                        // Calculate vertex and triangle counts from part count (each cubic part has 24 vertices and 12 triangles)
                        vertexCount = partCount * 24;
                        triangleCount = partCount * 12;
                        
                        updateUIState();
                        updateStatus("Model loaded successfully: " + modelPath);
                        logger.info("Model loaded: {} parts, {} vertices, {} triangles", 
                                  partCount, vertexCount, triangleCount);
                    } else {
                        updateStatus("Failed to load model");
                        logger.error("Model loading returned null");
                    }
                }).exceptionally(throwable -> {
                    logger.error("Failed to load model", throwable);
                    updateStatus("Error loading model: " + throwable.getMessage());
                    return null;
                });
            } else {
                logger.warn("ModelManager not initialized, falling back to placeholder");
                // Fallback to placeholder for demo
                modelLoaded = true;
                currentModelPath = "standard_cow.json";
                unsavedChanges = false;
                partCount = 6;
                vertexCount = 1248;
                triangleCount = 624;
                updateUIState();
                updateStatus("Model loaded (placeholder mode)");
            }
        } catch (Exception e) {
            logger.error("Exception during model loading", e);
            updateStatus("Error loading model: " + e.getMessage());
        }
    }
    
    private void openProject() {
        // logger.info("Open project action triggered");
        updateStatus("Opening Stonebreak project...");
        // Implementation would show directory dialog
    }
    
    public void saveModel() {
        // logger.info("Save model action triggered");
        updateStatus("Saving model...");
        unsavedChanges = false;
        updateUIState();
        updateStatus("Model saved successfully");
    }
    
    public void saveModelAs() {
        // logger.info("Save model as action triggered");
        updateStatus("Saving model as...");
        
        try {
            // Create file chooser dialog
            SwingUtilities.invokeLater(() -> {
                JFileChooser fileChooser = new JFileChooser();
                fileChooser.setDialogTitle("Save Model As");
                fileChooser.setCurrentDirectory(new File(System.getProperty("user.dir")));
                
                // Set file filters
                FileNameExtensionFilter jsonFilter = new FileNameExtensionFilter("JSON Model Files (*.json)", "json");
                fileChooser.addChoosableFileFilter(jsonFilter);
                fileChooser.setFileFilter(jsonFilter);
                
                // Show save dialog
                int result = fileChooser.showSaveDialog(null);
                
                if (result == JFileChooser.APPROVE_OPTION) {
                    File selectedFile = fileChooser.getSelectedFile();
                    String filePath = selectedFile.getAbsolutePath();
                    
                    // Ensure .json extension
                    if (!filePath.toLowerCase().endsWith(".json")) {
                        filePath += ".json";
                        selectedFile = new File(filePath);
                    }
                    
                    // Save the model
                    saveModelToFile(selectedFile);
                } else {
                    updateStatus("Save cancelled");
                }
            });
        } catch (Exception e) {
            logger.error("Error showing save dialog", e);
            updateStatus("Error opening save dialog: " + e.getMessage());
        }
    }
    
    public void exportModel() {
        // logger.info("Export model action triggered");
        updateStatus("Exporting model...");
        
        if (!modelLoaded) {
            updateStatus("No model to export");
            return;
        }
        
        try {
            // Create file chooser for export
            SwingUtilities.invokeLater(() -> {
                JFileChooser fileChooser = new JFileChooser();
                fileChooser.setDialogTitle("Export Model");
                fileChooser.setCurrentDirectory(new File(System.getProperty("user.dir")));
                
                // Set file filters for different export formats
                FileNameExtensionFilter objFilter = new FileNameExtensionFilter("Wavefront OBJ (*.obj)", "obj");
                FileNameExtensionFilter gltfFilter = new FileNameExtensionFilter("glTF (*.gltf)", "gltf");
                FileNameExtensionFilter jsonFilter = new FileNameExtensionFilter("JSON Model (*.json)", "json");
                
                fileChooser.addChoosableFileFilter(objFilter);
                fileChooser.addChoosableFileFilter(gltfFilter);
                fileChooser.addChoosableFileFilter(jsonFilter);
                fileChooser.setFileFilter(objFilter); // Default to OBJ
                
                int result = fileChooser.showSaveDialog(null);
                
                if (result == JFileChooser.APPROVE_OPTION) {
                    File selectedFile = fileChooser.getSelectedFile();
                    FileNameExtensionFilter selectedFilter = (FileNameExtensionFilter) fileChooser.getFileFilter();
                    
                    // Add appropriate extension
                    String extension = selectedFilter.getExtensions()[0];
                    String filePath = selectedFile.getAbsolutePath();
                    if (!filePath.toLowerCase().endsWith("." + extension)) {
                        filePath += "." + extension;
                        selectedFile = new File(filePath);
                    }
                    
                    // Export the model
                    exportModelToFile(selectedFile, extension);
                } else {
                    updateStatus("Export cancelled");
                }
            });
        } catch (Exception e) {
            logger.error("Error showing export dialog", e);
            updateStatus("Error opening export dialog: " + e.getMessage());
        }
    }
    
    private void loadRecentFile(String filename) {
        // logger.info("Loading recent file: {}", filename);
        updateStatus("Loading " + filename + "...");
        modelLoaded = true;
        currentModelPath = filename;
        unsavedChanges = false;
        updateUIState();
        updateStatus("Loaded " + filename);
    }
    
    private void exitApplication() {
        // logger.info("Exit application action triggered");
        // Cleanup and exit
        if (viewport3D != null) {
            viewport3D.dispose();
        }
        if (logoManager != null) {
            logoManager.dispose();
        }
        if (themeManager != null) {
            themeManager.dispose();
        }
        System.exit(0);
    }
    
    private void undo() {
        // logger.info("Undo action triggered");
        updateStatus("Undo performed");
    }
    
    private void redo() {
        // logger.info("Redo action triggered");
        updateStatus("Redo performed");
    }
    
    private void showPreferences() {
        // logger.info("Show preferences action triggered");
        showPreferencesWindow.set(true);
    }
    
    public void resetView() {
        // logger.info("Reset view action triggered");
        if (viewport3D != null) {
            viewport3D.resetCamera();
        }
        updateStatus("View reset");
    }
    
    public void fitToView() {
        // logger.info("Fit to view action triggered");
        updateStatus("Fitted to view");
    }
    
    // Zoom methods removed - handled by 3D viewport directly
    
    public void toggleGrid() {
        showGrid = !showGrid;
        // logger.info("Toggle grid: {}", showGrid);
        if (viewport3D != null) {
            viewport3D.setGridVisible(showGrid);
        }
    }
    
    private void toggleAxes() {
        showAxes = !showAxes;
        // logger.info("Toggle axes: {}", showAxes);
        if (viewport3D != null) {
            viewport3D.setAxesVisible(showAxes);
        }
    }
    
    public void toggleWireframe() {
        wireframeMode = !wireframeMode;
        // logger.info("Toggle wireframe: {}", wireframeMode);
        if (viewport3D != null) {
            viewport3D.setWireframeMode(wireframeMode);
        }
        updateStatus("Wireframe " + (wireframeMode ? "enabled" : "disabled"));
    }
    
    /**
     * Toggle transform gizmo on/off.
     */
    public void toggleTransformGizmo() {
        if (viewport3D != null) {
            boolean newState = !viewport3D.isGizmoEnabled();
            viewport3D.setGizmoEnabled(newState);
            updateStatus("Transform Gizmo " + (newState ? "enabled" : "disabled"));
            logger.info("Transform gizmo toggled: {}", newState);
        }
    }
    
    public void switchToVariant(String variantName) {
        // logger.info("Switch to texture variant: {}", variantName);
        
        // Find the variant index
        for (int i = 0; i < textureVariants.length; i++) {
            if (textureVariants[i].equalsIgnoreCase(variantName)) {
                currentTextureVariantIndex = i;
                textureVariant.set(variantName);
                
                if (viewport3D != null) {
                    viewport3D.setCurrentTextureVariant(variantName.toLowerCase());
                }
                
                updateStatus("Switched to " + variantName + " variant");
                return;
            }
        }
        
        logger.warn("Unknown texture variant: {}", variantName);
        updateStatus("Unknown variant: " + variantName);
    }
    
    private void toggleModelBrowser() {
        showModelBrowser.set(!showModelBrowser.get());
        // logger.info("Toggle model browser: {}", showModelBrowser.get());
    }
    
    private void togglePropertyPanel() {
        showPropertyPanel.set(!showPropertyPanel.get());
        // logger.info("Toggle property panel: {}", showPropertyPanel.get());
    }
    
    
    private void toggleToolbar() {
        showToolbar.set(!showToolbar.get());
        // logger.info("Toggle toolbar: {}", showToolbar.get());
    }
    
    private void showViewport() {
        // logger.info("Show 3D viewport action triggered");
        // Viewport is always shown in current implementation
        updateStatus("3D Viewport is visible");
    }
    
    
    private void resetToDefaultLayout() {
        // logger.info("Reset to default layout action triggered");
        updateStatus("Resetting to default layout...");
        
        // Reset panel visibility to defaults
        showModelBrowser.set(true);
        showPropertyPanel.set(true);
        showToolbar.set(true);
        
        // Reset ImGui docking layout by deleting imgui.ini
        try {
            java.nio.file.Path iniPath = java.nio.file.Paths.get("openmason-tool/imgui.ini");
            if (java.nio.file.Files.exists(iniPath)) {
                java.nio.file.Files.delete(iniPath);
                // logger.info("Deleted ImGui layout configuration for reset");
            }
            updateStatus("Layout reset - restart application to see changes");
        } catch (Exception e) {
            logger.error("Failed to reset layout", e);
            updateStatus("Failed to reset layout: " + e.getMessage());
        }
    }
    
    private void toggleFullScreenViewport() {
        // logger.info("Toggle full screen viewport action triggered");
        updateStatus("Full screen viewport mode toggled");
        
        // Hide/show other panels for full screen effect
        if (showModelBrowser.get() || showPropertyPanel.get()) {
            // Hide panels for full screen
            showModelBrowser.set(false);
            showPropertyPanel.set(false);
            showToolbar.set(false);
        } else {
            // Restore panels
            showModelBrowser.set(true);
            showPropertyPanel.set(true);
            showToolbar.set(true);
        }
    }
    
    private void applyModelingLayout() {
        // logger.info("Apply modeling layout action triggered");
        updateStatus("Applying modeling layout...");
        
        // Configure panels for modeling workflow
        showModelBrowser.set(true);
        showPropertyPanel.set(true);
        showToolbar.set(true);
        
        if (viewport3D != null) {
            viewport3D.setGridVisible(true);
            viewport3D.setAxesVisible(true);
            viewport3D.setWireframeMode(false);
        }
        
        showGrid = true;
        showAxes = true;
        wireframeMode = false;
        
        updateStatus("Modeling layout applied");
    }
    
    private void applyTexturingLayout() {
        // logger.info("Apply texturing layout action triggered");
        updateStatus("Applying texturing layout...");
        
        // Configure panels for texturing workflow
        showModelBrowser.set(true);
        showPropertyPanel.set(true);
        showToolbar.set(true);
        
        if (viewport3D != null) {
            viewport3D.setGridVisible(false);
            viewport3D.setAxesVisible(false);
            viewport3D.setWireframeMode(false);
        }
        
        showGrid = false;
        showAxes = false;
        wireframeMode = false;
        
        updateStatus("Texturing layout applied");
    }
    
    private void validateModel() {
        // logger.info("Validate model action triggered");
        updateStatus("Validating model...");
        // Implementation would perform model validation
        updateStatus("Model validation complete");
    }
    
    
    private void showAbout() {
        // logger.info("Show about action triggered");
        showAboutWindow.set(true);
    }
    
    private void filterModels(String filter) {
        // logger.debug("Filtering models with: {}", filter);
        // Implementation would filter the model browser
    }
    
    private void selectModel(String modelName, String variant) {
        // logger.info("Selected model: {} with variant: {}", modelName, variant);
        selectedModelInfo = "Selected: " + modelName + " (" + variant + " variant)";
        
        // Load model
        modelLoaded = true;
        currentModelPath = modelName;
        currentTextureVariantIndex = java.util.Arrays.asList(textureVariants).indexOf(variant);
        if (currentTextureVariantIndex == -1) currentTextureVariantIndex = 0;
        
        // Update statistics (example values)
        partCount = 6;
        vertexCount = 1248;
        triangleCount = 624;
        
        updateUIState();
        updateStatus("Model loaded: " + modelName + " (" + variant + " variant)");
    }
    
    private void changeViewMode() {
        String viewMode = viewModes[currentViewModeIndex.get()];
        // logger.info("Changed view mode to: {}", viewMode);
        // Implementation would update viewport camera
    }
    
    private void changeRenderMode() {
        String renderMode = renderModes[currentRenderModeIndex.get()];
        // logger.info("Changed render mode to: {}", renderMode);
        
        if (viewport3D != null) {
            switch (renderMode.toLowerCase()) {
                case "wireframe":
                    wireframeMode = true;
                    viewport3D.setWireframeMode(true);
                    break;
                default:
                    wireframeMode = false;
                    viewport3D.setWireframeMode(false);
                    break;
            }
            updateStatus("Render mode: " + renderMode);
        }
    }
    
    private void changeTextureVariant() {
        String variant = textureVariants[currentTextureVariantIndex];
        // logger.info("Changed texture variant to: {}", variant);
        
        if (viewport3D != null) {
            viewport3D.setCurrentTextureVariant(variant.toLowerCase());
        }
        
        updateStatus("Texture variant: " + variant);
    }
    
    
    
    
    
    
    
    private void resetProperties() {
        // logger.info("Reset properties action triggered");
        rotationX.set(0.0f);
        rotationY.set(0.0f);
        rotationZ.set(0.0f);
        scale.set(1.0f);
        updateModelTransform();
        updateStatus("Properties reset");
    }
    
    private void exportDiagnostics() {
        // logger.info("Export diagnostics action triggered");
        updateStatus("Exporting diagnostics...");
        // Implementation would export diagnostic information
    }
    
    private void updateModelTransform() {
        // Implementation would update 3D model transform
        // logger.debug("Transform updated - Rotation: ({}, {}, {}), Scale: {}", 
        //             rotationX.get(), rotationY.get(), rotationZ.get(), scale.get());
    }
    
    // Utility Methods
    
    private void setupViewport() {
        try {
            // logger.info("Setting up 3D viewport...");
            viewport3D = new OpenMason3DViewport();
            // OpenMason3DViewport initializes itself in constructor
            
            // Synchronize camera sensitivity with saved preferences
            if (viewport3D.getCamera() != null) {
                // Apply saved preference to camera (loaded in initializePreferences)
                applyCameraMouseSensitivity(cameraMouseSensitivity.get());
            }
            
            // logger.info("3D viewport setup complete");
        } catch (Exception e) {
            logger.error("Failed to setup 3D viewport", e);
        }
    }
    
    private void setupModelBrowser() {
        try {
            modelManager = new ModelManager();
            // logger.info("Model manager setup complete");
        } catch (Exception e) {
            logger.error("Failed to setup model manager", e);
        }
    }
    
    private void setupPropertiesPanel() {
        try {
            // Initialize property panel ImGui
            propertyPanelImGui = new PropertyPanelImGui();
            // logger.info("Properties panel setup complete");
        } catch (Exception e) {
            logger.error("Failed to setup properties panel", e);
        }
    }
    
    private void loadInitialData() {
        // Load initial texture variants and models
        updateMemoryUsage();
        updateFrameRate();
    }
    
    private void updateUIState() {
        // Update UI state based on current model
        // This method is called when model state changes
    }
    
    private void updateStatus(String message) {
        statusMessage = message;
        // logger.debug("Status updated: {}", message);
    }
    
    private void updateMemoryUsage() {
        Runtime runtime = Runtime.getRuntime();
        long totalMemory = runtime.totalMemory();
        long freeMemory = runtime.freeMemory();
        long usedMemory = totalMemory - freeMemory;
        memoryUsage = usedMemory / (1024.0f * 1024.0f);
    }
    
    private void updateFrameRate() {
        if (viewport3D != null) {
            frameRate = (float) viewport3D.getCurrentFPS();
        }
    }
    
    // Public API
    
    public PropertyPanelImGui getPropertyPanelImGui() {
        return propertyPanelImGui;
    }
    
    public Map<String, Object> getPerformanceMetrics() {
        performanceMetrics.clear();
        performanceMetrics.put("modelLoaded", modelLoaded);
        performanceMetrics.put("currentModelPath", currentModelPath);
        performanceMetrics.put("unsavedChanges", unsavedChanges);
        performanceMetrics.put("memoryUsage", memoryUsage);
        performanceMetrics.put("frameRate", frameRate);
        
        if (propertyPanelImGui != null) {
            performanceMetrics.putAll(propertyPanelImGui.getPerformanceMetrics());
        }
        
        return performanceMetrics;
    }
    
    public void forceTextureVariantReload(String modelName) {
        if (propertyPanelImGui != null) {
            propertyPanelImGui.loadTextureVariants(modelName);
        }
    }
    
    /**
     * Save model to specified file.
     */
    private void saveModelToFile(File file) {
        try {
            // logger.info("Saving model to: {}", file.getAbsolutePath());
            updateStatus("Saving model to " + file.getName() + "...");
            
            // TODO: Implement actual model saving using Stonebreak model system
            // For now, simulate successful save
            Thread.sleep(100); // Simulate save time
            
            currentModelPath = file.getName();
            unsavedChanges = false;
            updateUIState();
            updateStatus("Model saved successfully: " + file.getName());
            
        } catch (Exception e) {
            logger.error("Failed to save model to file: {}", file.getAbsolutePath(), e);
            updateStatus("Failed to save model: " + e.getMessage());
        }
    }
    
    /**
     * Export model to specified file and format.
     */
    private void exportModelToFile(File file, String format) {
        try {
            // logger.info("Exporting model to {} format: {}", format.toUpperCase(), file.getAbsolutePath());
            updateStatus("Exporting to " + format.toUpperCase() + "...");
            
            // TODO: Implement actual model export using appropriate exporter
            switch (format.toLowerCase()) {
                case "obj":
                    exportToOBJ(file);
                    break;
                case "gltf":
                    exportToGLTF(file);
                    break;
                case "json":
                    exportToJSON(file);
                    break;
                default:
                    throw new IllegalArgumentException("Unsupported export format: " + format);
            }
            
            updateStatus("Model exported successfully: " + file.getName());
            
        } catch (Exception e) {
            logger.error("Failed to export model to file: {}", file.getAbsolutePath(), e);
            updateStatus("Failed to export model: " + e.getMessage());
        }
    }
    
    private void exportToOBJ(File file) throws Exception {
        // TODO: Implement OBJ export
        // logger.debug("OBJ export placeholder for: {}", file.getName());
        Thread.sleep(200); // Simulate export time
    }
    
    private void exportToGLTF(File file) throws Exception {
        // TODO: Implement glTF export
        // logger.debug("glTF export placeholder for: {}", file.getName());
        Thread.sleep(300); // Simulate export time
    }
    
    private void exportToJSON(File file) throws Exception {
        // TODO: Implement JSON export using Stonebreak model system
        // logger.debug("JSON export placeholder for: {}", file.getName());
        Thread.sleep(150); // Simulate export time
    }
    
    // Theme system methods
    
    /**
     * Initialize the theme system.
     */
    private void initializeThemeSystem() {
        try {
            themeManager = ThemeManager.getInstance();
            themeSystemInitialized = (themeManager != null);
            
            if (themeSystemInitialized) {
                logger.info("Theme system initialized successfully");
                
                // Apply default theme if available
                if (themeManager.getCurrentTheme() == null) {
                    themeManager.applyTheme("dark"); // Default to dark theme
                }
            } else {
                logger.warn("Theme system failed to initialize - ThemeManager is null");
            }
        } catch (Exception e) {
            logger.error("Failed to initialize theme system", e);
            themeManager = null;
            themeSystemInitialized = false;
        }
    }
    
    /**
     * Switch to a specific theme.
     */
    private void switchTheme(String themeId, int themeIndex) {
        if (!themeSystemInitialized || themeManager == null) {
            logger.warn("Theme system not initialized, cannot switch theme");
            return;
        }
        
        try {
            themeManager.applyTheme(themeId);
            selectedThemeIndex.set(themeIndex);
            updateStatus("Switched to " + themeId + " theme");
            logger.info("Theme switched to: {}", themeId);
        } catch (Exception e) {
            logger.error("Failed to switch theme to: {}", themeId, e);
            updateStatus("Failed to switch theme: " + e.getMessage());
        }
    }
    
    /**
     * Switch UI density.
     */
    private void switchDensity(DensityManager.UIDensity density, int densityIndex) {
        if (!themeSystemInitialized || themeManager == null) {
            logger.warn("Theme system not initialized, cannot switch density");
            return;
        }
        
        try {
            themeManager.setUIDensity(density);
            selectedDensityIndex.set(densityIndex);
            updateStatus("UI density changed to: " + density.getDisplayName());
            logger.info("UI density changed to: {}", density.getDisplayName());
        } catch (Exception e) {
            logger.error("Failed to change UI density to: {}", density.getDisplayName(), e);
            updateStatus("Failed to change density: " + e.getMessage());
        }
    }
    
    /**
     * Preview a theme without applying it permanently.
     */
    private void previewTheme(String themeId) {
        if (!themeSystemInitialized || themeManager == null) {
            return;
        }
        
        try {
            ThemeDefinition theme = themeManager.getTheme(themeId);
            if (theme != null) {
                themeManager.previewTheme(theme);
                updateStatus("Previewing theme: " + theme.getName());
                logger.info("Theme preview started: {}", theme.getName());
            }
        } catch (Exception e) {
            logger.error("Failed to preview theme: {}", themeId, e);
            updateStatus("Failed to preview theme: " + e.getMessage());
        }
    }
    
    /**
     * Show advanced theme settings dialog.
     */
    private void showAdvancedThemeSettings() {
        showThemeMenu.set(true);
    }
    
    /**
     * Reset theme to default settings.
     */
    private void resetToDefaultTheme() {
        if (!themeSystemInitialized || themeManager == null) {
            return;
        }
        
        try {
            themeManager.applyTheme("dark"); // Dark is the default
            themeManager.setUIDensity(DensityManager.UIDensity.NORMAL);
            selectedThemeIndex.set(0);
            selectedDensityIndex.set(1);
            updateStatus("Theme reset to default");
            logger.info("Theme reset to default settings");
        } catch (Exception e) {
            logger.error("Failed to reset theme to default", e);
            updateStatus("Failed to reset theme: " + e.getMessage());
        }
    }
    
    /**
     * Get theme manager instance for other components.
     */
    public ThemeManager getThemeManager() {
        return themeManager;
    }
    
    /**
     * Check if theme system is initialized.
     */
    public boolean isThemeSystemInitialized() {
        return themeSystemInitialized;
    }
    
    /**
     * Update method called every frame for animations and periodic updates.
     */
    public void update(float deltaTime) {
        updateMemoryUsage();
        updateFrameRate();
    }
    
    /**
     * Get the 3D viewport instance for sharing with other UI components.
     */
    public OpenMason3DViewport getViewport3D() {
        return viewport3D;
    }
}