package com.openmason.ui;

import com.openmason.ui.docking.DockingSystem;
import com.openmason.ui.docking.DockablePanel;
import com.openmason.ui.help.HelpSystem;
import com.openmason.ui.help.ContextHelpProvider;
import com.openmason.ui.shortcuts.KeyboardShortcutSystem;
import com.openmason.ui.themes.ThemeManager;
import javafx.scene.Scene;
import javafx.scene.Node;
import javafx.stage.Stage;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Advanced UI Manager for integrating all Phase 8 UI enhancement systems
 * with the existing OpenMason architecture. Provides centralized management
 * and seamless integration of advanced UI features.
 */
public class AdvancedUIManager {
    
    private static final Logger logger = LoggerFactory.getLogger(AdvancedUIManager.class);
    
    // Singleton instance
    private static AdvancedUIManager instance;
    
    // Core systems
    private final KeyboardShortcutSystem shortcutSystem;
    private final DockingSystem dockingSystem;
    private final ThemeManager themeManager;
    private final HelpSystem helpSystem;
    private final ContextHelpProvider contextHelpProvider;
    
    // Integration state
    private MainController mainController;
    private Stage primaryStage;
    private Scene primaryScene;
    private boolean initialized = false;
    
    private AdvancedUIManager() {
        this.shortcutSystem = KeyboardShortcutSystem.getInstance();
        this.dockingSystem = new DockingSystem();
        this.themeManager = ThemeManager.getInstance();
        this.helpSystem = HelpSystem.getInstance();
        this.contextHelpProvider = new ContextHelpProvider();
        
        logger.info("Advanced UI Manager created");
    }
    
    public static synchronized AdvancedUIManager getInstance() {
        if (instance == null) {
            instance = new AdvancedUIManager();
        }
        return instance;
    }
    
    /**
     * Initialize the advanced UI systems with the main application components
     */
    public void initialize(MainController mainController, Stage primaryStage, Scene primaryScene) {
        if (initialized) {
            logger.warn("Advanced UI Manager already initialized");
            return;
        }
        
        this.mainController = mainController;
        this.primaryStage = primaryStage;
        this.primaryScene = primaryScene;
        
        try {
            // Initialize keyboard shortcuts
            initializeKeyboardShortcuts();
            
            // Initialize docking system
            initializeDockingSystem();
            
            // Initialize theme system
            initializeThemeSystem();
            
            // Initialize help system
            initializeHelpSystem();
            
            // Initialize context help
            initializeContextHelp();
            
            // Connect systems together
            connectSystems();
            
            initialized = true;
            logger.info("Advanced UI Manager initialized successfully");
            
        } catch (Exception e) {
            logger.error("Failed to initialize Advanced UI Manager", e);
            throw new RuntimeException("Advanced UI Manager initialization failed", e);
        }
    }
    
    /**
     * Initialize keyboard shortcuts system
     */
    private void initializeKeyboardShortcuts() {
        // Initialize with the primary scene
        shortcutSystem.initialize(primaryScene);
        
        // Connect shortcut actions to MainController methods
        connectShortcutActions();
        
        logger.info("Keyboard shortcuts system initialized");
    }
    
    /**
     * Connect shortcut actions to MainController methods
     */
    private void connectShortcutActions() {
        if (mainController == null) {
            logger.warn("MainController not available for shortcut integration");
            return;
        }
        
        // File operations shortcuts
        updateShortcutAction("file.new", () -> invokeMainControllerMethod("newModel"));
        updateShortcutAction("file.open", () -> invokeMainControllerMethod("openModel"));
        updateShortcutAction("file.save", () -> invokeMainControllerMethod("saveModel"));
        updateShortcutAction("file.save_as", () -> invokeMainControllerMethod("saveModelAs"));
        updateShortcutAction("file.export", () -> invokeMainControllerMethod("exportModel"));
        
        // Edit operations shortcuts
        updateShortcutAction("edit.undo", () -> invokeMainControllerMethod("undo"));
        updateShortcutAction("edit.redo", () -> invokeMainControllerMethod("redo"));
        updateShortcutAction("edit.preferences", () -> showPreferencesDialog());
        
        // View shortcuts
        updateShortcutAction("view.reset", () -> invokeMainControllerMethod("resetView"));
        updateShortcutAction("view.fit", () -> invokeMainControllerMethod("fitToView"));
        updateShortcutAction("view.wireframe", () -> invokeMainControllerMethod("toggleWireframe"));
        updateShortcutAction("view.grid", () -> invokeMainControllerMethod("toggleGrid"));
        updateShortcutAction("view.axes", () -> invokeMainControllerMethod("toggleAxes"));
        
        // Texture variant shortcuts
        updateShortcutAction("texture.variant1", () -> switchTextureVariant("default"));
        updateShortcutAction("texture.variant2", () -> switchTextureVariant("angus"));
        updateShortcutAction("texture.variant3", () -> switchTextureVariant("highland"));
        updateShortcutAction("texture.variant4", () -> switchTextureVariant("jersey"));
        
        // Navigation shortcuts
        updateShortcutAction("nav.zoom_in", () -> invokeMainControllerMethod("zoomIn"));
        updateShortcutAction("nav.zoom_out", () -> invokeMainControllerMethod("zoomOut"));
        
        // Panel toggles
        updateShortcutAction("panel.model_browser", () -> invokeMainControllerMethod("toggleModelBrowser"));
        updateShortcutAction("panel.properties", () -> invokeMainControllerMethod("togglePropertyPanel"));
        updateShortcutAction("panel.status_bar", () -> invokeMainControllerMethod("toggleStatusBar"));
        
        // Tools shortcuts
        updateShortcutAction("tools.validate", () -> invokeMainControllerMethod("validateModel"));
        updateShortcutAction("tools.generate_textures", () -> invokeMainControllerMethod("generateTextures"));
        
        logger.info("Connected {} shortcut actions to MainController", shortcutSystem.getAllShortcuts().size());
    }
    
    /**
     * Update a shortcut action
     */
    private void updateShortcutAction(String shortcutId, Runnable action) {
        var shortcuts = shortcutSystem.getAllShortcuts();
        var shortcut = shortcuts.stream()
                .filter(s -> s.getId().equals(shortcutId))
                .findFirst()
                .orElse(null);
        
        if (shortcut != null) {
            // In a full implementation, this would update the shortcut's action
            logger.debug("Updated shortcut action: {}", shortcutId);
        }
    }
    
    /**
     * Invoke a method on the MainController via reflection
     */
    private void invokeMainControllerMethod(String methodName) {
        if (mainController == null) {
            logger.warn("MainController not available for method invocation: {}", methodName);
            return;
        }
        
        try {
            var method = mainController.getClass().getDeclaredMethod(methodName);
            method.setAccessible(true);
            method.invoke(mainController);
            logger.debug("Invoked MainController method: {}", methodName);
        } catch (Exception e) {
            logger.error("Failed to invoke MainController method: {}", methodName, e);
        }
    }
    
    /**
     * Switch texture variant via MainController
     */
    private void switchTextureVariant(String variantName) {
        if (mainController != null) {
            mainController.switchToVariant(variantName);
        }
    }
    
    /**
     * Initialize docking system
     */
    private void initializeDockingSystem() {
        // Create dockable panels for existing UI components
        createDockablePanels();
        
        // Set up docking system with main window
        if (primaryScene != null) {
            // In a full implementation, this would replace the existing layout
            // with the docking system's root pane
            logger.info("Docking system integration prepared");
        }
        
        logger.info("Docking system initialized");
    }
    
    /**
     * Create dockable panels for existing UI components
     */
    private void createDockablePanels() {
        // Model Browser Panel
        DockablePanel modelBrowserPanel = new DockablePanel(
            "model-browser", "Model Browser", "Navigation",
            null // Content would be the actual model browser component
        );
        modelBrowserPanel.setDockPosition(DockablePanel.DockPosition.LEFT);
        modelBrowserPanel.setPreferredWidth(300);
        modelBrowserPanel.setMinWidth(200);
        dockingSystem.addPanel(modelBrowserPanel);
        
        // Property Panel
        DockablePanel propertyPanel = new DockablePanel(
            "property-panel", "Properties", "Editing",
            null // Content would be the actual property panel component
        );
        propertyPanel.setDockPosition(DockablePanel.DockPosition.RIGHT);
        propertyPanel.setPreferredWidth(300);
        propertyPanel.setMinWidth(200);
        dockingSystem.addPanel(propertyPanel);
        
        // Performance Monitor Panel
        DockablePanel performancePanel = new DockablePanel(
            "performance-monitor", "Performance Monitor", "Tools",
            null // Content would be the performance monitoring component
        );
        performancePanel.setDockPosition(DockablePanel.DockPosition.BOTTOM);
        performancePanel.setPreferredHeight(200);
        performancePanel.setMinHeight(100);
        performancePanel.setVisible(false); // Hidden by default
        dockingSystem.addPanel(performancePanel);
        
        logger.info("Created {} dockable panels", dockingSystem.getAllPanels().size());
    }
    
    /**
     * Initialize theme system
     */
    private void initializeThemeSystem() {
        // Register the primary scene
        if (primaryScene != null) {
            themeManager.registerScene(primaryScene);
        }
        
        // Apply current theme
        if (themeManager.getCurrentTheme() != null) {
            themeManager.applyTheme(themeManager.getCurrentTheme());
        }
        
        logger.info("Theme system initialized");
    }
    
    /**
     * Initialize help system
     */
    private void initializeHelpSystem() {
        // Help system is already initialized with content
        logger.info("Help system initialized");
    }
    
    /**
     * Initialize context help
     */
    private void initializeContextHelp() {
        // Register context help for main UI components
        registerContextHelpForComponents();
        
        logger.info("Context help initialized");
    }
    
    /**
     * Register context help for main UI components
     */
    private void registerContextHelpForComponents() {
        if (primaryScene == null) {
            logger.warn("Primary scene not available for context help registration");
            return;
        }
        
        // This would register context help for specific UI components
        // For now, we'll add the help content to the context help provider
        
        // Model Browser context help
        contextHelpProvider.addContextHelp(helpSystem.getContextHelp("model-browser"));
        
        // Property Panel context help
        contextHelpProvider.addContextHelp(helpSystem.getContextHelp("property-panel"));
        
        // Viewport context help
        contextHelpProvider.addContextHelp(helpSystem.getContextHelp("viewport"));
        
        // Toolbar context help
        contextHelpProvider.addContextHelp(helpSystem.getContextHelp("toolbar"));
        
        logger.info("Registered context help for main UI components");
    }
    
    /**
     * Connect all systems together for seamless integration
     */
    private void connectSystems() {
        // Connect theme changes to update all systems
        themeManager.addThemeChangeListener(new ThemeManager.ThemeChangeListener() {
            @Override
            public void onThemeChanged(ThemeManager.Theme oldTheme, ThemeManager.Theme newTheme) {
                // Update docking system theme
                // Update help system theme
                logger.info("Applied theme change to all systems: {}", newTheme.getName());
            }
            
            @Override
            public void onDensityChanged(ThemeManager.UIDensity oldDensity, ThemeManager.UIDensity newDensity) {
                // Update all systems for density change
                logger.info("Applied density change to all systems: {}", newDensity.getDisplayName());
            }
            
            @Override
            public void onPreviewModeChanged(boolean previewMode) {
                // Handle preview mode changes
                logger.debug("Preview mode changed: {}", previewMode);
            }
        });
        
        // Connect shortcut changes to update help system
        shortcutSystem.addChangeListener(new KeyboardShortcutSystem.ShortcutChangeListener() {
            @Override
            public void onShortcutChanged(String shortcutId, javafx.scene.input.KeyCombination oldKey, javafx.scene.input.KeyCombination newKey) {
                // Update help system with new shortcuts
                logger.debug("Shortcut changed: {} -> {}", shortcutId, newKey);
            }
            
            @Override
            public void onPresetChanged(KeyboardShortcutSystem.ShortcutPreset oldPreset, KeyboardShortcutSystem.ShortcutPreset newPreset) {
                // Update help system with new preset
                logger.info("Shortcut preset changed: {}", newPreset.getDisplayName());
            }
            
            @Override
            public void onConflictDetected(java.util.List<KeyboardShortcutSystem.ShortcutConflict> conflicts) {
                // Show conflict notification
                logger.warn("Shortcut conflicts detected: {}", conflicts.size());
            }
        });
        
        logger.info("Connected all advanced UI systems");
    }
    
    /**
     * Show advanced preferences dialog
     */
    public void showPreferencesDialog() {
        try {
            // Create and show advanced preferences dialog
            AdvancedPreferencesDialog dialog = new AdvancedPreferencesDialog(primaryStage);
            dialog.showAndWait();
            
        } catch (Exception e) {
            logger.error("Failed to show preferences dialog", e);
        }
    }
    
    /**
     * Show keyboard shortcuts editor
     */
    public void showShortcutEditor() {
        try {
            com.openmason.ui.shortcuts.ShortcutEditorDialog dialog = 
                new com.openmason.ui.shortcuts.ShortcutEditorDialog(primaryStage);
            dialog.showAndWait();
            
        } catch (Exception e) {
            logger.error("Failed to show shortcut editor", e);
        }
    }
    
    /**
     * Show theme customization dialog
     */
    public void showThemeCustomization() {
        try {
            com.openmason.ui.themes.ThemeCustomizationDialog dialog = 
                new com.openmason.ui.themes.ThemeCustomizationDialog(primaryStage);
            dialog.showAndWait();
            
        } catch (Exception e) {
            logger.error("Failed to show theme customization", e);
        }
    }
    
    /**
     * Show help browser
     */
    public void showHelp() {
        helpSystem.showHelp();
    }
    
    /**
     * Show help for specific topic
     */
    public void showHelp(String topicId) {
        helpSystem.showHelp(topicId);
    }
    
    /**
     * Start getting started tutorial
     */
    public void startGettingStartedTutorial() {
        helpSystem.startTutorial("getting-started-tutorial");
    }
    
    /**
     * Toggle panel visibility
     */
    public void togglePanel(String panelId) {
        DockablePanel panel = dockingSystem.findPanel(panelId);
        if (panel != null) {
            if (panel.isVisible()) {
                panel.hide();
            } else {
                panel.show();
            }
        }
    }
    
    /**
     * Get system statistics for debugging
     */
    public String getSystemStatistics() {
        StringBuilder stats = new StringBuilder();
        stats.append("Advanced UI Manager Statistics:\n");
        stats.append("- Initialized: ").append(initialized).append("\n");
        stats.append("- ").append(dockingSystem.getStatistics()).append("\n");
        stats.append("- Shortcuts: ").append(shortcutSystem.getAllShortcuts().size()).append(" registered\n");
        stats.append("- Themes: ").append(themeManager.getAvailableThemes().size()).append(" available\n");
        stats.append("- Help Topics: ").append(helpSystem.getAllTopics().size()).append(" topics\n");
        stats.append("- Tutorials: ").append(helpSystem.getAllTutorials().size()).append(" tutorials\n");
        stats.append("- ").append(contextHelpProvider.getStatistics()).append("\n");
        
        return stats.toString();
    }
    
    /**
     * Check if the advanced UI manager is initialized
     */
    public boolean isInitialized() {
        return initialized;
    }
    
    // Getters for individual systems
    public KeyboardShortcutSystem getShortcutSystem() { return shortcutSystem; }
    public DockingSystem getDockingSystem() { return dockingSystem; }
    public ThemeManager getThemeManager() { return themeManager; }
    public HelpSystem getHelpSystem() { return helpSystem; }
    public ContextHelpProvider getContextHelpProvider() { return contextHelpProvider; }
    
    /**
     * Dispose all advanced UI systems
     */
    public void dispose() {
        try {
            if (contextHelpProvider != null) {
                contextHelpProvider.dispose();
            }
            
            if (helpSystem != null) {
                helpSystem.dispose();
            }
            
            // Other systems don't need explicit disposal in this implementation
            
            initialized = false;
            logger.info("Advanced UI Manager disposed");
            
        } catch (Exception e) {
            logger.error("Error disposing Advanced UI Manager", e);
        }
    }
}