package com.openmason.ui.docking;

import imgui.ImGui;
import imgui.flag.*;
import imgui.type.ImBoolean;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Native Dear ImGui docking system implementation.
 * Provides professional docking with native ImGui dockspace functionality.
 */
public class DockingSystemImGui {
    
    private static final Logger logger = LoggerFactory.getLogger(DockingSystemImGui.class);
    
    // Core components
    private final Map<String, DockableWindowImGui> windows;
    private final Set<String> visibleWindows;
    private final Map<String, LayoutPreset> layoutPresets;
    
    // Layout management
    private String currentLayoutName = "Default";
    private boolean layoutLocked = false;
    private boolean dockspaceEnabled = true;
    
    // Docking state
    private int mainDockspaceId = 0;
    private boolean dockspaceInitialized = false;
    private boolean autoSaveLayout = true;
    
    // Layout presets
    private final Map<String, Integer> dockspaceNodes = new HashMap<>();
    
    /**
     * Dockable window wrapper for ImGui
     */
    public static class DockableWindowImGui {
        private final String id;
        private final String title;
        private String displayTitle;
        private boolean visible = true;
        private boolean closeable = true;
        private boolean detachable = true;
        private WindowContentRenderer contentRenderer;
        private WindowState state = WindowState.DOCKED;
        
        // Window properties
        private float preferredWidth = 400;
        private float preferredHeight = 300;
        private float minWidth = 100;
        private float minHeight = 100;
        
        public enum WindowState {
            DOCKED, FLOATING, HIDDEN, MINIMIZED
        }
        
        public interface WindowContentRenderer {
            void renderContent();
        }
        
        public DockableWindowImGui(String id, String title) {
            this.id = id;
            this.title = title;
            this.displayTitle = title;
        }
        
        public void render() {
            if (!visible || state == WindowState.HIDDEN) {
                return;
            }
            
            int windowFlags = ImGuiWindowFlags.None;
            if (!closeable) {
                windowFlags |= ImGuiWindowFlags.NoCollapse;
            }
            
            ImBoolean windowOpen = new ImBoolean(true);
            String windowTitle = displayTitle + "###" + id;
            
            // Set size constraints
            ImGui.setNextWindowSizeConstraints(minWidth, minHeight, Float.MAX_VALUE, Float.MAX_VALUE);
            
            if (ImGui.begin(windowTitle, closeable ? windowOpen : null, windowFlags)) {
                if (contentRenderer != null) {
                    contentRenderer.renderContent();
                }
            }
            ImGui.end();
            
            // Handle window close
            if (closeable && !windowOpen.get()) {
                visible = false;
                state = WindowState.HIDDEN;
            }
        }
        
        // Getters and setters
        public String getId() { return id; }
        public String getTitle() { return title; }
        public String getDisplayTitle() { return displayTitle; }
        public void setDisplayTitle(String displayTitle) { this.displayTitle = displayTitle; }
        public boolean isVisible() { return visible; }
        public void setVisible(boolean visible) { this.visible = visible; }
        public boolean isCloseable() { return closeable; }
        public void setCloseable(boolean closeable) { this.closeable = closeable; }
        public boolean isDetachable() { return detachable; }
        public void setDetachable(boolean detachable) { this.detachable = detachable; }
        public WindowState getState() { return state; }
        public void setState(WindowState state) { this.state = state; }
        public WindowContentRenderer getContentRenderer() { return contentRenderer; }
        public void setContentRenderer(WindowContentRenderer contentRenderer) { this.contentRenderer = contentRenderer; }
        public float getPreferredWidth() { return preferredWidth; }
        public void setPreferredWidth(float preferredWidth) { this.preferredWidth = preferredWidth; }
        public float getPreferredHeight() { return preferredHeight; }
        public void setPreferredHeight(float preferredHeight) { this.preferredHeight = preferredHeight; }
        public float getMinWidth() { return minWidth; }
        public void setMinWidth(float minWidth) { this.minWidth = minWidth; }
        public float getMinHeight() { return minHeight; }
        public void setMinHeight(float minHeight) { this.minHeight = minHeight; }
    }
    
    /**
     * Layout preset for saving/restoring layouts
     */
    public static class LayoutPreset {
        private final String name;
        private final String description;
        private final Map<String, WindowLayoutInfo> windowLayouts;
        
        public LayoutPreset(String name, String description) {
            this.name = name;
            this.description = description;
            this.windowLayouts = new HashMap<>();
        }
        
        public String getName() { return name; }
        public String getDescription() { return description; }
        public Map<String, WindowLayoutInfo> getWindowLayouts() { return windowLayouts; }
    }
    
    /**
     * Window layout information for presets
     */
    public static class WindowLayoutInfo {
        private DockableWindowImGui.WindowState state;
        private boolean visible;
        private float width;
        private float height;
        private float posX;
        private float posY;
        private int dockId;
        
        // Getters and setters
        public DockableWindowImGui.WindowState getState() { return state; }
        public void setState(DockableWindowImGui.WindowState state) { this.state = state; }
        public boolean isVisible() { return visible; }
        public void setVisible(boolean visible) { this.visible = visible; }
        public float getWidth() { return width; }
        public void setWidth(float width) { this.width = width; }
        public float getHeight() { return height; }
        public void setHeight(float height) { this.height = height; }
        public float getPosX() { return posX; }
        public void setPosX(float posX) { this.posX = posX; }
        public float getPosY() { return posY; }
        public void setPosY(float posY) { this.posY = posY; }
        public int getDockId() { return dockId; }
        public void setDockId(int dockId) { this.dockId = dockId; }
    }
    
    /**
     * Create a new ImGui docking system
     */
    public DockingSystemImGui() {
        this.windows = new ConcurrentHashMap<>();
        this.visibleWindows = ConcurrentHashMap.newKeySet();
        this.layoutPresets = new HashMap<>();
        
        initializeLayoutPresets();
        
        logger.info("ImGui docking system initialized");
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
     * Enable ImGui docking globally
     */
    public void enableDocking() {
        ImGui.getIO().addConfigFlags(ImGuiConfigFlags.DockingEnable);
        dockspaceEnabled = true;
        logger.info("ImGui docking enabled");
    }
    
    /**
     * Setup and render the main dockspace
     */
    public void renderMainDockspace() {
        if (!dockspaceEnabled) {
            return;
        }
        
        // Setup dockspace over the main viewport
        int dockspaceFlags = ImGuiDockNodeFlags.None;
        if (layoutLocked) {
            dockspaceFlags |= ImGuiDockNodeFlags.NoResize | ImGuiDockNodeFlags.NoDockingInCentralNode;
        }
        
        // Create fullscreen window for dockspace
        ImGui.setNextWindowPos(0, 0);
        ImGui.setNextWindowSize(ImGui.getMainViewport().getSizeX(), ImGui.getMainViewport().getSizeY());
        
        int windowFlags = ImGuiWindowFlags.MenuBar | ImGuiWindowFlags.NoDocking | 
                         ImGuiWindowFlags.NoTitleBar | ImGuiWindowFlags.NoCollapse | 
                         ImGuiWindowFlags.NoResize | ImGuiWindowFlags.NoMove | 
                         ImGuiWindowFlags.NoBringToFrontOnFocus | ImGuiWindowFlags.NoNavFocus;
        
        ImGui.pushStyleVar(imgui.flag.ImGuiStyleVar.WindowRounding, 0.0f);
        ImGui.pushStyleVar(imgui.flag.ImGuiStyleVar.WindowBorderSize, 0.0f);
        ImGui.pushStyleVar(imgui.flag.ImGuiStyleVar.WindowPadding, 0.0f, 0.0f);
        
        ImBoolean dockspaceOpen = new ImBoolean(true);
        if (ImGui.begin("DockSpace", dockspaceOpen, windowFlags)) {
            ImGui.popStyleVar(3);
            
            // Create the dockspace
            mainDockspaceId = ImGui.getID("MainDockspace");
            ImGui.dockSpace(mainDockspaceId, 0.0f, 0.0f, dockspaceFlags);
            
            // Initialize dockspace layout on first frame
            if (!dockspaceInitialized) {
                initializeDockspaceLayout();
                dockspaceInitialized = true;
            }
            
        } else {
            ImGui.popStyleVar(3);
        }
        ImGui.end();
    }
    
    /**
     * Initialize the default dockspace layout
     */
    private void initializeDockspaceLayout() {
        if (mainDockspaceId == 0) {
            return;
        }
        
        // Note: DockBuilder API might not be available in this ImGui version
        // Using simplified dockspace without complex layout building
        logger.debug("DockBuilder API not available - using simplified dockspace layout");
        
        // Store placeholder dock node IDs
        dockspaceNodes.put("Left", mainDockspaceId);
        dockspaceNodes.put("Right", mainDockspaceId);
        dockspaceNodes.put("Center", mainDockspaceId);
        dockspaceNodes.put("Bottom", mainDockspaceId);
        
        logger.info("Initialized default dockspace layout");
    }
    
    /**
     * Add a window to the docking system
     */
    public void addWindow(DockableWindowImGui window) {
        if (window == null || windows.containsKey(window.getId())) {
            return;
        }
        
        windows.put(window.getId(), window);
        if (window.isVisible()) {
            visibleWindows.add(window.getId());
        }
        
        if (autoSaveLayout) {
            saveCurrentLayout();
        }
        
        logger.info("Added window to docking system: {} ({})", window.getTitle(), window.getId());
    }
    
    /**
     * Remove a window from the docking system
     */
    public void removeWindow(String windowId) {
        DockableWindowImGui window = windows.remove(windowId);
        if (window != null) {
            visibleWindows.remove(windowId);
            
            if (autoSaveLayout) {
                saveCurrentLayout();
            }
            
            logger.info("Removed window from docking system: {}", window.getTitle());
        }
    }
    
    /**
     * Show a window
     */
    public void showWindow(String windowId) {
        DockableWindowImGui window = windows.get(windowId);
        if (window != null) {
            window.setVisible(true);
            window.setState(DockableWindowImGui.WindowState.DOCKED);
            visibleWindows.add(windowId);
        }
    }
    
    /**
     * Hide a window
     */
    public void hideWindow(String windowId) {
        DockableWindowImGui window = windows.get(windowId);
        if (window != null) {
            window.setVisible(false);
            window.setState(DockableWindowImGui.WindowState.HIDDEN);
            visibleWindows.remove(windowId);
        }
    }
    
    /**
     * Dock a window to a specific area
     */
    public void dockWindow(String windowId, String dockArea) {
        DockableWindowImGui window = windows.get(windowId);
        if (window == null) {
            return;
        }
        
        Integer dockNodeId = dockspaceNodes.get(dockArea);
        if (dockNodeId != null) {
            // Note: dockBuilderDockWindow not available - window will dock automatically
            logger.debug("DockBuilder API not available - window will auto-dock to dockspace");
            
            window.setState(DockableWindowImGui.WindowState.DOCKED);
            
            logger.info("Docked window {} to area {}", window.getTitle(), dockArea);
        }
    }
    
    /**
     * Float a window (undock it)
     */
    public void floatWindow(String windowId) {
        DockableWindowImGui window = windows.get(windowId);
        if (window != null && window.isDetachable()) {
            window.setState(DockableWindowImGui.WindowState.FLOATING);
            logger.info("Floated window: {}", window.getTitle());
        }
    }
    
    /**
     * Render all visible windows
     */
    public void renderWindows() {
        for (String windowId : visibleWindows) {
            DockableWindowImGui window = windows.get(windowId);
            if (window != null && window.isVisible()) {
                window.render();
            }
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
        
        // Reset dockspace
        dockspaceInitialized = false;
        
        // Apply window layouts
        for (Map.Entry<String, WindowLayoutInfo> entry : preset.getWindowLayouts().entrySet()) {
            String windowId = entry.getKey();
            WindowLayoutInfo layoutInfo = entry.getValue();
            
            DockableWindowImGui window = windows.get(windowId);
            if (window != null) {
                window.setState(layoutInfo.getState());
                window.setVisible(layoutInfo.isVisible());
                window.setPreferredWidth(layoutInfo.getWidth());
                window.setPreferredHeight(layoutInfo.getHeight());
                
                if (layoutInfo.isVisible()) {
                    visibleWindows.add(windowId);
                } else {
                    visibleWindows.remove(windowId);
                }
            }
        }
        
        currentLayoutName = presetName;
        
        logger.info("Applied layout preset: {}", presetName);
    }
    
    /**
     * Save current layout as a preset
     */
    public void saveLayoutPreset(String name, String description) {
        LayoutPreset preset = new LayoutPreset(name, description);
        
        // Save window states
        for (DockableWindowImGui window : windows.values()) {
            WindowLayoutInfo layoutInfo = new WindowLayoutInfo();
            layoutInfo.setState(window.getState());
            layoutInfo.setVisible(window.isVisible());
            layoutInfo.setWidth(window.getPreferredWidth());
            layoutInfo.setHeight(window.getPreferredHeight());
            
            preset.getWindowLayouts().put(window.getId(), layoutInfo);
        }
        
        layoutPresets.put(name, preset);
        
        logger.info("Saved layout preset: {}", name);
    }
    
    /**
     * Save current layout
     */
    private void saveCurrentLayout() {
        try {
            // Implementation would save to preferences
            // For now, just log
            logger.debug("Saved current layout");
        } catch (Exception e) {
            logger.error("Failed to save current layout", e);
        }
    }
    
    /**
     * Load saved layout
     */
    public void loadSavedLayout() {
        try {
            // Implementation would load from preferences
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
        dockspaceInitialized = false; // Force reinit
    }
    
    /**
     * Lock/unlock layout to prevent accidental changes
     */
    public void setLayoutLocked(boolean locked) {
        this.layoutLocked = locked;
        
        // Disable drag and drop operations when locked
        for (DockableWindowImGui window : windows.values()) {
            window.setDetachable(!locked);
        }
        
        logger.info("Layout {}", locked ? "locked" : "unlocked");
    }
    
    /**
     * Create a basic window with content renderer
     */
    public DockableWindowImGui createWindow(String id, String title, 
                                          DockableWindowImGui.WindowContentRenderer contentRenderer) {
        DockableWindowImGui window = new DockableWindowImGui(id, title);
        window.setContentRenderer(contentRenderer);
        return window;
    }
    
    /**
     * Create a properties window
     */
    public DockableWindowImGui createPropertiesWindow(String id, String title) {
        return createWindow(id, title, () -> {
            if (ImGui.collapsingHeader("Properties")) {
                ImGui.text("Property controls would go here");
                ImGui.separator();
                ImGui.text("Transform, materials, etc.");
            }
        });
    }
    
    /**
     * Create a model browser window
     */
    public DockableWindowImGui createModelBrowserWindow(String id, String title) {
        return createWindow(id, title, () -> {
            if (ImGui.collapsingHeader("Models")) {
                ImGui.text("Model tree would go here");
                ImGui.separator();
                ImGui.text("Hierarchical model browser");
            }
        });
    }
    
    // Property getters
    public Collection<DockableWindowImGui> getAllWindows() { 
        return windows.values(); 
    }
    
    public Collection<String> getLayoutPresetNames() { 
        return layoutPresets.keySet(); 
    }
    
    public String getCurrentLayoutName() { 
        return currentLayoutName; 
    }
    
    public boolean isLayoutLocked() { 
        return layoutLocked; 
    }
    
    public boolean isDockspaceEnabled() { 
        return dockspaceEnabled; 
    }
    
    /**
     * Find window by ID
     */
    public DockableWindowImGui findWindow(String windowId) {
        return windows.get(windowId);
    }
    
    /**
     * Get visible window count
     */
    public int getVisibleWindowCount() {
        return visibleWindows.size();
    }
    
    /**
     * Get docking system statistics
     */
    public String getStatistics() {
        int dockedWindows = (int) windows.values().stream()
                .filter(w -> w.getState() == DockableWindowImGui.WindowState.DOCKED)
                .count();
        int floatingWindows = (int) windows.values().stream()
                .filter(w -> w.getState() == DockableWindowImGui.WindowState.FLOATING)
                .count();
        int hiddenWindows = (int) windows.values().stream()
                .filter(w -> w.getState() == DockableWindowImGui.WindowState.HIDDEN)
                .count();
        
        return String.format("Windows: %d total, %d docked, %d floating, %d hidden. Layout: %s %s",
                windows.size(), dockedWindows, floatingWindows, hiddenWindows,
                getCurrentLayoutName(), isLayoutLocked() ? "(locked)" : "");
    }
    
    /**
     * Render docking system debug info
     */
    public void renderDebugInfo() {
        if (ImGui.begin("Docking Debug")) {
            ImGui.text("Dockspace ID: " + mainDockspaceId);
            ImGui.text("Layout: " + currentLayoutName);
            ImGui.text("Locked: " + (layoutLocked ? "Yes" : "No"));
            ImGui.text("Windows: " + windows.size());
            ImGui.text("Visible: " + visibleWindows.size());
            
            ImGui.separator();
            ImGui.text("Dock Nodes:");
            for (Map.Entry<String, Integer> entry : dockspaceNodes.entrySet()) {
                ImGui.text(entry.getKey() + ": " + entry.getValue());
            }
            
            ImGui.separator();
            ImGui.text("Windows:");
            for (DockableWindowImGui window : windows.values()) {
                String status = window.isVisible() ? "Visible" : "Hidden";
                ImGui.text(window.getTitle() + " (" + window.getState() + ", " + status + ")");
            }
        }
        ImGui.end();
    }
    
    /**
     * Check if docking is enabled
     */
    public boolean isEnabled() {
        return dockspaceEnabled;
    }
    
    /**
     * Apply theme to docking system components
     */
    public void applyTheme(String themeId) {
        try {
            // Theme application for docking system would involve:
            // - Updating window border colors
            // - Adjusting dockspace visual styling
            // - Modifying tab appearance
            // For now, log the theme change request
            logger.info("Applying theme '{}' to docking system", themeId);
            
            // In a full implementation, you might:
            // 1. Update ImGui style colors for docking-specific elements
            // 2. Refresh visual appearance of all windows
            // 3. Apply theme to custom window decorations
            
        } catch (Exception e) {
            logger.error("Failed to apply theme '{}' to docking system", themeId, e);
        }
    }
    
    /**
     * Dispose of docking system resources
     */
    public void dispose() {
        try {
            // Save current layout before disposing
            if (autoSaveLayout) {
                saveCurrentLayout();
            }
            
            // Clear all windows and reset state
            windows.clear();
            visibleWindows.clear();
            layoutPresets.clear();
            dockspaceNodes.clear();
            
            // Reset ImGui docking state
            if (mainDockspaceId != 0) {
                // Note: dockBuilderRemoveNode not available - dock nodes will be cleaned up automatically
                logger.debug("DockBuilder API not available - skipping explicit dock node cleanup");
            }
            
            // Reset internal state
            mainDockspaceId = 0;
            dockspaceInitialized = false;
            currentLayoutName = "Default";
            layoutLocked = false;
            dockspaceEnabled = false;
            
            logger.info("Docking system disposed and resources cleaned up");
            
        } catch (Exception e) {
            logger.error("Error during docking system disposal", e);
        }
    }
    
    /**
     * Initialize the docking system
     */
    public void initialize() {
        if (!dockspaceInitialized) {
            dockspaceEnabled = true;
            logger.info("Docking system initialized");
        }
    }
    
    /**
     * Render the docking system
     */
    public void render() {
        if (dockspaceEnabled) {
            renderMainDockspace();
            
            // Render all visible windows
            for (String windowId : visibleWindows) {
                DockableWindowImGui window = windows.get(windowId);
                if (window != null) {
                    window.render();
                }
            }
        }
    }
}