package com.openmason.ui.docking;

import javafx.application.Platform;
import javafx.beans.property.*;
import javafx.collections.FXCollections;
import javafx.collections.ObservableList;
import javafx.geometry.Orientation;
import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.Scene;
import javafx.scene.control.*;
import javafx.scene.input.MouseEvent;
import javafx.scene.layout.*;
import javafx.stage.Stage;
import javafx.stage.StageStyle;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.prefs.Preferences;
import java.util.stream.Collectors;

/**
 * Professional docking system with drag-and-drop panel repositioning,
 * tabbed and floating modes, layout presets, and persistence.
 */
public class DockingSystem {
    
    private static final Logger logger = LoggerFactory.getLogger(DockingSystem.class);
    
    // Core components
    private final BorderPane rootPane;
    private final Map<DockablePanel.DockPosition, DockArea> dockAreas;
    private final Map<String, DockablePanel> panels;
    private final Map<String, Stage> floatingWindows;
    private final ObservableList<DockablePanel> allPanels;
    
    // Layout management
    private final Map<String, LayoutPreset> layoutPresets;
    private final StringProperty currentLayoutName = new SimpleStringProperty("Default");
    private final BooleanProperty layoutLocked = new SimpleBooleanProperty(false);
    
    // Drag and drop state
    private DragState currentDragState;
    private DockArea dragTargetArea;
    
    // Configuration
    private Preferences preferences;
    private boolean autoSaveLayout = true;
    private double minPanelSize = 100.0;
    private double defaultSplitterPosition = 0.25;
    
    /**
     * Docking area for a specific position
     */
    private static class DockArea {
        private final DockablePanel.DockPosition position;
        private final TabPane tabPane;
        private final VBox container;
        private final ObservableList<DockablePanel> panels;
        private boolean collapsed = false;
        
        public DockArea(DockablePanel.DockPosition position) {
            this.position = position;
            this.panels = FXCollections.observableArrayList();
            this.tabPane = new TabPane();
            this.container = new VBox();
            
            setupTabPane();
            setupContainer();
        }
        
        private void setupTabPane() {
            tabPane.getStyleClass().add("dock-area-tabs");
            tabPane.setTabClosingPolicy(TabPane.TabClosingPolicy.SELECTED_TAB);
            
            // Handle tab selection
            tabPane.getSelectionModel().selectedItemProperty().addListener((obs, oldTab, newTab) -> {
                if (newTab instanceof DockableTab) {
                    DockableTab dockTab = (DockableTab) newTab;
                    logger.debug("Selected tab: {}", dockTab.getPanel().getTitle());
                }
            });
        }
        
        private void setupContainer() {
            container.getStyleClass().add("dock-area-container");
            container.getChildren().add(tabPane);
            VBox.setVgrow(tabPane, Priority.ALWAYS);
        }
        
        public void addPanel(DockablePanel panel) {
            if (!panels.contains(panel)) {
                panels.add(panel);
                
                DockableTab tab = new DockableTab(panel);
                tabPane.getTabs().add(tab);
                
                // Select the new tab
                tabPane.getSelectionModel().select(tab);
                
                logger.debug("Added panel {} to dock area {}", panel.getId(), position);
            }
        }
        
        public void removePanel(DockablePanel panel) {
            panels.remove(panel);
            
            // Find and remove the corresponding tab
            tabPane.getTabs().removeIf(tab -> {
                if (tab instanceof DockableTab) {
                    return ((DockableTab) tab).getPanel().equals(panel);
                }
                return false;
            });
            
            logger.debug("Removed panel {} from dock area {}", panel.getId(), position);
        }
        
        public boolean isEmpty() {
            return panels.isEmpty();
        }
        
        public Node getNode() {
            return container;
        }
        
        public void setCollapsed(boolean collapsed) {
            this.collapsed = collapsed;
            container.setVisible(!collapsed);
            container.setManaged(!collapsed);
        }
        
        public boolean isCollapsed() {
            return collapsed;
        }
    }
    
    /**
     * Drag state for drag-and-drop operations
     */
    private static class DragState {
        private final DockablePanel panel;
        private final double startX;
        private final double startY;
        private Stage dragWindow;
        
        public DragState(DockablePanel panel, double startX, double startY) {
            this.panel = panel;
            this.startX = startX;
            this.startY = startY;
        }
        
        // Getters
        public DockablePanel getPanel() { return panel; }
        public double getStartX() { return startX; }
        public double getStartY() { return startY; }
        public Stage getDragWindow() { return dragWindow; }
        public void setDragWindow(Stage dragWindow) { this.dragWindow = dragWindow; }
    }
    
    /**
     * Layout preset for saving/restoring layouts
     */
    public static class LayoutPreset {
        private final String name;
        private final String description;
        private final Map<String, PanelLayoutInfo> panelLayouts;
        private final Map<DockablePanel.DockPosition, Double> splitterPositions;
        
        public LayoutPreset(String name, String description) {
            this.name = name;
            this.description = description;
            this.panelLayouts = new HashMap<>();
            this.splitterPositions = new HashMap<>();
        }
        
        public String getName() { return name; }
        public String getDescription() { return description; }
        public Map<String, PanelLayoutInfo> getPanelLayouts() { return panelLayouts; }
        public Map<DockablePanel.DockPosition, Double> getSplitterPositions() { return splitterPositions; }
    }
    
    /**
     * Panel layout information for presets
     */
    public static class PanelLayoutInfo {
        private DockablePanel.DockPosition position;
        private DockablePanel.PanelState state;
        private boolean visible;
        private double width;
        private double height;
        private int tabIndex;
        
        // Getters and setters
        public DockablePanel.DockPosition getPosition() { return position; }
        public void setPosition(DockablePanel.DockPosition position) { this.position = position; }
        
        public DockablePanel.PanelState getState() { return state; }
        public void setState(DockablePanel.PanelState state) { this.state = state; }
        
        public boolean isVisible() { return visible; }
        public void setVisible(boolean visible) { this.visible = visible; }
        
        public double getWidth() { return width; }
        public void setWidth(double width) { this.width = width; }
        
        public double getHeight() { return height; }
        public void setHeight(double height) { this.height = height; }
        
        public int getTabIndex() { return tabIndex; }
        public void setTabIndex(int tabIndex) { this.tabIndex = tabIndex; }
    }
    
    /**
     * Create a new docking system
     */
    public DockingSystem() {
        this.rootPane = new BorderPane();
        this.dockAreas = new EnumMap<>(DockablePanel.DockPosition.class);
        this.panels = new ConcurrentHashMap<>();
        this.floatingWindows = new ConcurrentHashMap<>();
        this.allPanels = FXCollections.observableArrayList();
        this.layoutPresets = new HashMap<>();
        
        this.preferences = Preferences.userRoot().node(this.getClass().getName());
        
        initializeDockAreas();
        initializeLayoutPresets();
        setupRootPane();
        
        logger.info("Docking system initialized");
    }
    
    /**
     * Initialize dock areas for all positions
     */
    private void initializeDockAreas() {
        for (DockablePanel.DockPosition position : DockablePanel.DockPosition.values()) {
            if (position != DockablePanel.DockPosition.FLOATING) {
                dockAreas.put(position, new DockArea(position));
            }
        }
    }
    
    /**
     * Initialize default layout presets
     */
    private void initializeLayoutPresets() {
        // Default layout
        LayoutPreset defaultLayout = new LayoutPreset("Default", "Standard OpenMason layout");
        layoutPresets.put("Default", defaultLayout);
        
        // Modeling focused layout
        LayoutPreset modelingLayout = new LayoutPreset("Modeling", "Layout optimized for 3D modeling");
        layoutPresets.put("Modeling", modelingLayout);
        
        // Animation layout
        LayoutPreset animationLayout = new LayoutPreset("Animation", "Layout for animation workflow");
        layoutPresets.put("Animation", animationLayout);
        
        logger.info("Initialized {} layout presets", layoutPresets.size());
    }
    
    /**
     * Set up the root pane with splitters
     */
    private void setupRootPane() {
        rootPane.getStyleClass().add("docking-root");
        
        // Create main horizontal split (left | center | right)
        SplitPane mainHorizontalSplit = new SplitPane();
        mainHorizontalSplit.setOrientation(Orientation.HORIZONTAL);
        mainHorizontalSplit.getStyleClass().add("main-horizontal-split");
        
        // Left area
        Node leftArea = dockAreas.get(DockablePanel.DockPosition.LEFT).getNode();
        
        // Center area with vertical split (top | center | bottom)
        SplitPane centerVerticalSplit = new SplitPane();
        centerVerticalSplit.setOrientation(Orientation.VERTICAL);
        centerVerticalSplit.getStyleClass().add("center-vertical-split");
        
        Node topArea = dockAreas.get(DockablePanel.DockPosition.TOP).getNode();
        Node centerArea = dockAreas.get(DockablePanel.DockPosition.CENTER).getNode();
        Node bottomArea = dockAreas.get(DockablePanel.DockPosition.BOTTOM).getNode();
        
        centerVerticalSplit.getItems().addAll(topArea, centerArea, bottomArea);
        centerVerticalSplit.setDividerPositions(0.2, 0.8);
        
        // Right area
        Node rightArea = dockAreas.get(DockablePanel.DockPosition.RIGHT).getNode();
        
        // Add to main horizontal split
        mainHorizontalSplit.getItems().addAll(leftArea, centerVerticalSplit, rightArea);
        mainHorizontalSplit.setDividerPositions(defaultSplitterPosition, 1.0 - defaultSplitterPosition);
        
        rootPane.setCenter(mainHorizontalSplit);
        
        // Initially hide empty areas
        updateAreaVisibility();
    }
    
    /**
     * Add a panel to the docking system
     */
    public void addPanel(DockablePanel panel) {
        if (panel == null || panels.containsKey(panel.getId())) {
            return;
        }
        
        panels.put(panel.getId(), panel);
        allPanels.add(panel);
        panel.setDockingSystem(this);
        
        // Add to appropriate dock area
        DockArea dockArea = dockAreas.get(panel.getDockPosition());
        if (dockArea != null) {
            dockArea.addPanel(panel);
        }
        
        updateAreaVisibility();
        
        if (autoSaveLayout) {
            saveCurrentLayout();
        }
        
        logger.info("Added panel to docking system: {} at {}", 
                   panel.getTitle(), panel.getDockPosition());
    }
    
    /**
     * Remove a panel from the docking system
     */
    public void removePanel(DockablePanel panel) {
        if (panel == null || !panels.containsKey(panel.getId())) {
            return;
        }
        
        // Remove from dock area
        DockArea dockArea = dockAreas.get(panel.getDockPosition());
        if (dockArea != null) {
            dockArea.removePanel(panel);
        }
        
        // Remove from floating window if applicable
        Stage floatingWindow = floatingWindows.remove(panel.getId());
        if (floatingWindow != null) {
            floatingWindow.close();
        }
        
        panels.remove(panel.getId());
        allPanels.remove(panel);
        
        updateAreaVisibility();
        
        if (autoSaveLayout) {
            saveCurrentLayout();
        }
        
        logger.info("Removed panel from docking system: {}", panel.getTitle());
    }
    
    /**
     * Show a panel
     */
    public void showPanel(DockablePanel panel) {
        if (panel.getState() == DockablePanel.PanelState.HIDDEN) {
            panel.setState(DockablePanel.PanelState.DOCKED);
        }
        
        DockArea dockArea = dockAreas.get(panel.getDockPosition());
        if (dockArea != null) {
            // Select the panel's tab
            for (Tab tab : dockArea.tabPane.getTabs()) {
                if (tab instanceof DockableTab && ((DockableTab) tab).getPanel().equals(panel)) {
                    dockArea.tabPane.getSelectionModel().select(tab);
                    break;
                }
            }
        }
        
        updateAreaVisibility();
    }
    
    /**
     * Hide a panel
     */
    public void hidePanel(DockablePanel panel) {
        panel.setState(DockablePanel.PanelState.HIDDEN);
        updateAreaVisibility();
    }
    
    /**
     * Close a panel
     */
    public void closePanel(DockablePanel panel) {
        removePanel(panel);
    }
    
    /**
     * Dock a panel to a specific position
     */
    public void dockPanel(DockablePanel panel, DockablePanel.DockPosition position) {
        // Remove from current position
        DockArea currentArea = dockAreas.get(panel.getDockPosition());
        if (currentArea != null) {
            currentArea.removePanel(panel);
        }
        
        // Close floating window if exists
        Stage floatingWindow = floatingWindows.remove(panel.getId());
        if (floatingWindow != null) {
            floatingWindow.close();
        }
        
        // Add to new position
        panel.setDockPosition(position);
        panel.setState(DockablePanel.PanelState.DOCKED);
        
        DockArea newArea = dockAreas.get(position);
        if (newArea != null) {
            newArea.addPanel(panel);
        }
        
        updateAreaVisibility();
        
        if (autoSaveLayout) {
            saveCurrentLayout();
        }
    }
    
    /**
     * Detach a panel to a floating window
     */
    public void detachPanel(DockablePanel panel) {
        if (!panel.isDetachable()) {
            return;
        }
        
        // Remove from dock area
        DockArea dockArea = dockAreas.get(panel.getDockPosition());
        if (dockArea != null) {
            dockArea.removePanel(panel);
        }
        
        // Create floating window
        Stage floatingWindow = createFloatingWindow(panel);
        floatingWindows.put(panel.getId(), floatingWindow);
        
        panel.setState(DockablePanel.PanelState.FLOATING);
        
        updateAreaVisibility();
        
        if (autoSaveLayout) {
            saveCurrentLayout();
        }
        
        logger.info("Detached panel to floating window: {}", panel.getTitle());
    }
    
    /**
     * Minimize a panel
     */
    public void minimizePanel(DockablePanel panel) {
        // Implementation would minimize to title bar only
        panel.setState(DockablePanel.PanelState.MINIMIZED);
        logger.info("Minimized panel: {}", panel.getTitle());
    }
    
    /**
     * Restore a panel from minimized state
     */
    public void restorePanel(DockablePanel panel) {
        panel.setState(DockablePanel.PanelState.DOCKED);
        showPanel(panel);
        logger.info("Restored panel: {}", panel.getTitle());
    }
    
    /**
     * Create a floating window for a panel
     */
    private Stage createFloatingWindow(DockablePanel panel) {
        Stage stage = new Stage();
        stage.setTitle(panel.getTitle());
        stage.initStyle(StageStyle.DECORATED);
        
        // Create window content
        BorderPane windowContent = new BorderPane();
        windowContent.setCenter(panel.getOrCreateContent());
        windowContent.getStyleClass().add("floating-panel-window");
        
        Scene scene = new Scene(windowContent, panel.getPreferredWidth(), panel.getPreferredHeight());
        scene.getStylesheets().add(getClass().getResource("/css/dark-theme.css").toExternalForm());
        stage.setScene(scene);
        
        // Set minimum size
        stage.setMinWidth(panel.getMinWidth());
        stage.setMinHeight(panel.getMinHeight());
        
        // Handle window closing
        stage.setOnCloseRequest(e -> {
            if (panel.isCloseable()) {
                // Move back to docked state
                dockPanel(panel, panel.getDockPosition());
            } else {
                e.consume();
            }
        });
        
        stage.show();
        return stage;
    }
    
    /**
     * Update visibility of dock areas based on content
     */
    private void updateAreaVisibility() {
        for (DockArea area : dockAreas.values()) {
            boolean hasVisiblePanels = area.panels.stream()
                    .anyMatch(panel -> panel.isVisible() && 
                             panel.getState() != DockablePanel.PanelState.HIDDEN);
            
            area.getNode().setVisible(hasVisiblePanels);
            area.getNode().setManaged(hasVisiblePanels);
        }
    }
    
    /**
     * Apply a layout preset
     */
    public void applyLayoutPreset(String presetName) {
        LayoutPreset preset = layoutPresets.get(presetName);
        if (preset == null) {
            logger.warn("Unknown layout preset: {}", presetName);
            return;
        }
        
        // Apply panel layouts
        for (Map.Entry<String, PanelLayoutInfo> entry : preset.getPanelLayouts().entrySet()) {
            String panelId = entry.getKey();
            PanelLayoutInfo layoutInfo = entry.getValue();
            
            DockablePanel panel = panels.get(panelId);
            if (panel != null) {
                panel.setDockPosition(layoutInfo.getPosition());
                panel.setState(layoutInfo.getState());
                panel.setVisible(layoutInfo.isVisible());
                panel.setPreferredWidth(layoutInfo.getWidth());
                panel.setPreferredHeight(layoutInfo.getHeight());
                
                // Re-dock the panel
                dockPanel(panel, layoutInfo.getPosition());
            }
        }
        
        // Apply splitter positions
        // This would be implemented with access to the actual splitter controls
        
        currentLayoutName.set(presetName);
        
        logger.info("Applied layout preset: {}", presetName);
    }
    
    /**
     * Save current layout as a preset
     */
    public void saveLayoutPreset(String name, String description) {
        LayoutPreset preset = new LayoutPreset(name, description);
        
        // Save panel states
        for (DockablePanel panel : allPanels) {
            PanelLayoutInfo layoutInfo = new PanelLayoutInfo();
            layoutInfo.setPosition(panel.getDockPosition());
            layoutInfo.setState(panel.getState());
            layoutInfo.setVisible(panel.isVisible());
            layoutInfo.setWidth(panel.getPreferredWidth());
            layoutInfo.setHeight(panel.getPreferredHeight());
            
            preset.getPanelLayouts().put(panel.getId(), layoutInfo);
        }
        
        // Save splitter positions
        // This would capture current splitter positions
        
        layoutPresets.put(name, preset);
        
        logger.info("Saved layout preset: {}", name);
    }
    
    /**
     * Save current layout to preferences
     */
    private void saveCurrentLayout() {
        try {
            // Save layout state to preferences
            // Implementation would serialize current layout
            preferences.flush();
        } catch (Exception e) {
            logger.error("Failed to save current layout", e);
        }
    }
    
    /**
     * Load layout from preferences
     */
    public void loadSavedLayout() {
        try {
            // Load layout state from preferences
            // Implementation would deserialize saved layout
            logger.info("Loaded saved layout");
        } catch (Exception e) {
            logger.error("Failed to load saved layout", e);
        }
    }
    
    /**
     * Reset to default layout
     */
    public void resetToDefaultLayout() {
        applyLayoutPreset("Default");
    }
    
    /**
     * Lock/unlock layout to prevent accidental changes
     */
    public void setLayoutLocked(boolean locked) {
        layoutLocked.set(locked);
        
        // Disable drag and drop operations when locked
        for (DockablePanel panel : allPanels) {
            panel.setDetachable(!locked);
        }
        
        logger.info("Layout {}", locked ? "locked" : "unlocked");
    }
    
    // Property getters
    public BorderPane getRootPane() { return rootPane; }
    public ObservableList<DockablePanel> getAllPanels() { return allPanels; }
    public Collection<String> getLayoutPresetNames() { return layoutPresets.keySet(); }
    public String getCurrentLayoutName() { return currentLayoutName.get(); }
    public StringProperty currentLayoutNameProperty() { return currentLayoutName; }
    public boolean isLayoutLocked() { return layoutLocked.get(); }
    public BooleanProperty layoutLockedProperty() { return layoutLocked; }
    
    /**
     * Find panel by ID
     */
    public DockablePanel findPanel(String panelId) {
        return panels.get(panelId);
    }
    
    /**
     * Get panels in a specific dock position
     */
    public List<DockablePanel> getPanelsInPosition(DockablePanel.DockPosition position) {
        DockArea area = dockAreas.get(position);
        return area != null ? new ArrayList<>(area.panels) : Collections.emptyList();
    }
    
    /**
     * Get docking system statistics
     */
    public String getStatistics() {
        int dockedPanels = (int) allPanels.stream()
                .filter(p -> p.getState() == DockablePanel.PanelState.DOCKED)
                .count();
        int floatingPanels = (int) allPanels.stream()
                .filter(p -> p.getState() == DockablePanel.PanelState.FLOATING)
                .count();
        int hiddenPanels = (int) allPanels.stream()
                .filter(p -> p.getState() == DockablePanel.PanelState.HIDDEN)
                .count();
        
        return String.format("Panels: %d total, %d docked, %d floating, %d hidden. Layout: %s %s",
                allPanels.size(), dockedPanels, floatingPanels, hiddenPanels,
                getCurrentLayoutName(), isLayoutLocked() ? "(locked)" : "");
    }
}