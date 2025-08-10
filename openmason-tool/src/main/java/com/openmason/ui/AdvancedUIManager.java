package com.openmason.ui;

import com.openmason.ui.docking.DockingSystemImGui;
import com.openmason.ui.help.ImGuiHelpBrowser;
import com.openmason.ui.themes.ThemeManager;
import imgui.ImGui;
import imgui.flag.ImGuiWindowFlags;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.HashMap;
import java.util.Map;

/**
 * Advanced ImGui UI Manager for integrating all UI enhancement systems
 * with the OpenMason ImGui architecture. Provides centralized management
 * and seamless integration of ImGui-based UI features.
 * Converted from JavaFX to Dear ImGui architecture.
 */
public class AdvancedUIManager {
    
    private static final Logger logger = LoggerFactory.getLogger(AdvancedUIManager.class);
    
    // Singleton instance
    private static AdvancedUIManager instance;
    
    // Core ImGui systems
    private final DockingSystemImGui dockingSystem;
    private final ThemeManager themeManager;
    private final ImGuiHelpBrowser helpBrowser;
    
    // Integration state
    private ImGuiManager imguiManager;
    private MainImGuiInterface mainInterface;
    private boolean initialized = false;
    
    // UI state
    private final Map<String, Boolean> panelVisibility = new HashMap<>();
    private final Map<String, Runnable> shortcutActions = new HashMap<>();
    
    private AdvancedUIManager() {
        this.dockingSystem = new DockingSystemImGui();
        this.themeManager = ThemeManager.getInstance();
        this.helpBrowser = new ImGuiHelpBrowser(new com.openmason.ui.help.HelpSystem());
        
        // Initialize panel visibility states
        initializePanelStates();
        
        // Initialize shortcut actions
        initializeShortcutActions();
        
        logger.info("Advanced ImGui UI Manager created");
    }
    
    public static synchronized AdvancedUIManager getInstance() {
        if (instance == null) {
            instance = new AdvancedUIManager();
        }
        return instance;
    }
    
    /**
     * Initialize the advanced ImGui UI systems with the main application components
     */
    public void initialize(ImGuiManager imguiManager, MainImGuiInterface mainInterface) {
        if (initialized) {
            logger.warn("Advanced UI Manager already initialized");
            return;
        }
        
        this.imguiManager = imguiManager;
        this.mainInterface = mainInterface;
        
        try {
            // Initialize docking system
            initializeDockingSystem();
            
            // Initialize theme system
            initializeThemeSystem();
            
            // Initialize help system
            initializeHelpSystem();
            
            // Connect systems together
            connectSystems();
            
            initialized = true;
            logger.info("Advanced ImGui UI Manager initialized successfully");
            
        } catch (Exception e) {
            logger.error("Failed to initialize Advanced UI Manager", e);
            throw new RuntimeException("Advanced UI Manager initialization failed", e);
        }
    }
    
    /**
     * Initialize panel states
     */
    private void initializePanelStates() {
        panelVisibility.put("model-browser", true);
        panelVisibility.put("property-panel", true);
        panelVisibility.put("help-browser", false);
        panelVisibility.put("performance-monitor", false);
        panelVisibility.put("preferences", false);
    }
    
    /**
     * Initialize shortcut actions for ImGui interface
     */
    private void initializeShortcutActions() {
        if (mainInterface == null) {
            logger.debug("Main interface not available yet for shortcut integration");
            return;
        }
        
        // File operations
        shortcutActions.put("Ctrl+N", this::newModel);
        shortcutActions.put("Ctrl+O", this::openModel);
        shortcutActions.put("Ctrl+S", this::saveModel);
        shortcutActions.put("Ctrl+Shift+S", this::saveModelAs);
        shortcutActions.put("Ctrl+E", this::exportModel);
        
        // View operations
        shortcutActions.put("Ctrl+R", this::resetView);
        shortcutActions.put("Ctrl+F", this::fitToView);
        shortcutActions.put("Ctrl+W", this::toggleWireframe);
        shortcutActions.put("Ctrl+G", this::toggleGrid);
        
        // Panel toggles
        shortcutActions.put("Ctrl+1", () -> togglePanel("model-browser"));
        shortcutActions.put("Ctrl+2", () -> togglePanel("property-panel"));
        shortcutActions.put("F1", () -> togglePanel("help-browser"));
        
        // Texture variants
        shortcutActions.put("1", () -> switchTextureVariant("default"));
        shortcutActions.put("2", () -> switchTextureVariant("angus"));
        shortcutActions.put("3", () -> switchTextureVariant("highland"));
        shortcutActions.put("4", () -> switchTextureVariant("jersey"));
        
        logger.info("Initialized {} shortcut actions", shortcutActions.size());
    }
    
    /**
     * Handle keyboard input for shortcuts (called from ImGui render loop)
     */
    public void handleKeyboardInput() {
        if (!initialized || imguiManager == null) {
            return;
        }
        
        // Check for shortcut combinations
        for (Map.Entry<String, Runnable> entry : shortcutActions.entrySet()) {
            if (isShortcutPressed(entry.getKey())) {
                entry.getValue().run();
                break; // Only handle one shortcut per frame
            }
        }
    }
    
    /**
     * Check if a keyboard shortcut is pressed
     * Note: Simplified implementation - ImGuiKey constants not available in this version
     */
    private boolean isShortcutPressed(String shortcut) {
        // TODO: Implement keyboard shortcut detection when ImGui key constants are available
        return false;
    }
    
    // Action methods for shortcuts
    private void newModel() {
        if (mainInterface != null) {
            mainInterface.createNewModel();
        }
    }
    
    private void openModel() {
        if (mainInterface != null) {
            mainInterface.openModel();
        }
    }
    
    private void saveModel() {
        if (mainInterface != null) {
            mainInterface.saveModel();
        }
    }
    
    private void saveModelAs() {
        if (mainInterface != null) {
            mainInterface.saveModelAs();
        }
    }
    
    private void exportModel() {
        if (mainInterface != null) {
            mainInterface.exportModel();
        }
    }
    
    private void resetView() {
        if (mainInterface != null) {
            mainInterface.resetView();
        }
    }
    
    private void fitToView() {
        if (mainInterface != null) {
            mainInterface.fitToView();
        }
    }
    
    private void toggleWireframe() {
        if (mainInterface != null) {
            mainInterface.toggleWireframe();
        }
    }
    
    private void toggleGrid() {
        if (mainInterface != null) {
            mainInterface.toggleGrid();
        }
    }
    
    /**
     * Switch texture variant via MainInterface
     */
    private void switchTextureVariant(String variantName) {
        if (mainInterface != null) {
            mainInterface.switchToVariant(variantName);
        }
    }
    
    /**
     * Initialize ImGui docking system
     */
    private void initializeDockingSystem() {
        // Initialize the docking system
        dockingSystem.initialize();
        
        // Set up default docking layout
        setupDefaultDockingLayout();
        
        logger.info("ImGui docking system initialized");
    }
    
    /**
     * Set up default ImGui docking layout
     */
    private void setupDefaultDockingLayout() {
        // This will be handled by the DockingSystemImGui during render
        logger.info("Default docking layout prepared");
    }
    
    /**
     * Initialize ImGui theme system
     */
    private void initializeThemeSystem() {
        // Apply current ImGui theme
        if (themeManager.getCurrentTheme() != null) {
            themeManager.applyImGuiTheme(themeManager.getCurrentTheme());
        }
        
        logger.info("ImGui theme system initialized");
    }
    
    /**
     * Initialize ImGui help system
     */
    private void initializeHelpSystem() {
        // ImGuiHelpBrowser is initialized in constructor
        logger.info("ImGui help system initialized");
    }
    
    /**
     * Render ImGui UI (called from main render loop)
     */
    public void render() {
        if (!initialized) {
            return;
        }
        
        // Handle keyboard shortcuts
        handleKeyboardInput();
        
        // Render docking system
        dockingSystem.render();
        
        // Render help browser if visible
        if (panelVisibility.get("help-browser")) {
            helpBrowser.render();
        }
        
        // Handle other UI panels
        renderPanels();
    }
    
    /**
     * Render UI panels
     */
    private void renderPanels() {
        // This method will coordinate panel rendering with the docking system
        logger.debug("Rendering UI panels");
    }
    
    /**
     * Connect all ImGui systems together for seamless integration
     */
    private void connectSystems() {
        // Note: Theme change listening not available in current ThemeManager implementation
        logger.debug("Theme change listener not available - themes applied directly");
        
        logger.info("Connected all ImGui advanced UI systems");
    }
    
    /**
     * Show advanced preferences
     */
    public void showPreferences() {
        try {
            panelVisibility.put("preferences", true);
            logger.info("Showing ImGui preferences panel");
            
        } catch (Exception e) {
            logger.error("Failed to show preferences", e);
        }
    }
    
    /**
     * Show keyboard shortcuts editor
     */
    public void showShortcutEditor() {
        try {
            panelVisibility.put("shortcut-editor", true);
            logger.info("Showing ImGui shortcut editor");
            
        } catch (Exception e) {
            logger.error("Failed to show shortcut editor", e);
        }
    }
    
    /**
     * Show theme customization
     */
    public void showThemeCustomization() {
        try {
            panelVisibility.put("theme-customization", true);
            logger.info("Showing ImGui theme customization");
            
        } catch (Exception e) {
            logger.error("Failed to show theme customization", e);
        }
    }
    
    /**
     * Show ImGui help browser
     */
    public void showHelp() {
        panelVisibility.put("help-browser", true);
        helpBrowser.show();
    }
    
    /**
     * Show help for specific topic
     */
    public void showHelp(String topicId) {
        panelVisibility.put("help-browser", true);
        // Get the actual HelpTopic from the help system
        com.openmason.ui.help.HelpSystem.HelpTopic topic = new com.openmason.ui.help.HelpSystem().getTopic(topicId);
        helpBrowser.showTopic(topic);
    }
    
    /**
     * Start getting started tutorial
     */
    public void startGettingStartedTutorial() {
        panelVisibility.put("help-browser", true);
        helpBrowser.startTutorial("getting-started");
    }
    
    /**
     * Toggle panel visibility
     */
    public void togglePanel(String panelId) {
        Boolean currentState = panelVisibility.get(panelId);
        if (currentState != null) {
            panelVisibility.put(panelId, !currentState);
            logger.debug("Toggled panel {}: {}", panelId, !currentState);
        } else {
            panelVisibility.put(panelId, true);
            logger.debug("Created and showed panel: {}", panelId);
        }
    }
    
    /**
     * Get system statistics for debugging
     */
    public String getSystemStatistics() {
        StringBuilder stats = new StringBuilder();
        stats.append("ImGui Advanced UI Manager Statistics:\n");
        stats.append("- Initialized: ").append(initialized).append("\n");
        stats.append("- Docking System: ").append(dockingSystem.isEnabled()).append("\n");
        stats.append("- Shortcut Actions: ").append(shortcutActions.size()).append(" registered\n");
        stats.append("- Themes: ").append(themeManager.getAvailableThemes().size()).append(" available\n");
        stats.append("- Visible Panels: ").append(panelVisibility.values().stream().mapToInt(b -> b ? 1 : 0).sum()).append("/").append(panelVisibility.size()).append("\n");
        stats.append("- Help Browser: ").append(helpBrowser.isInitialized()).append("\n");
        
        return stats.toString();
    }
    
    /**
     * Check if the advanced UI manager is initialized
     */
    public boolean isInitialized() {
        return initialized;
    }
    
    // Getters for individual systems
    public DockingSystemImGui getDockingSystem() { return dockingSystem; }
    public ThemeManager getThemeManager() { return themeManager; }
    public ImGuiHelpBrowser getHelpBrowser() { return helpBrowser; }
    public Map<String, Boolean> getPanelVisibility() { return new HashMap<>(panelVisibility); }
    public Map<String, Runnable> getShortcutActions() { return new HashMap<>(shortcutActions); }
    
    /**
     * Dispose all ImGui advanced UI systems
     */
    public void dispose() {
        try {
            if (helpBrowser != null) {
                helpBrowser.dispose();
            }
            
            if (dockingSystem != null) {
                dockingSystem.dispose();
            }
            
            // Clear state
            panelVisibility.clear();
            shortcutActions.clear();
            
            initialized = false;
            logger.info("ImGui Advanced UI Manager disposed");
            
        } catch (Exception e) {
            logger.error("Error disposing ImGui Advanced UI Manager", e);
        }
    }
}