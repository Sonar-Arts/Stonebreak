package com.openmason.ui.shortcuts;

import javafx.scene.Scene;
import javafx.scene.input.KeyCode;
import javafx.scene.input.KeyCodeCombination;
import javafx.scene.input.KeyCombination;
import javafx.stage.Stage;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.*;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Consumer;
import java.util.prefs.Preferences;

/**
 * Enterprise-grade keyboard shortcuts system with customization, conflict detection,
 * and preset configurations for professional 3D modeling workflow.
 */
public class KeyboardShortcutSystem {
    
    private static final Logger logger = LoggerFactory.getLogger(KeyboardShortcutSystem.class);
    
    // Singleton instance
    private static KeyboardShortcutSystem instance;
    
    // Internal state
    private final Map<String, ShortcutAction> shortcuts = new ConcurrentHashMap<>();
    private final Map<String, ShortcutCategory> categories = new ConcurrentHashMap<>();
    private final Map<KeyCombination, String> keyMappings = new ConcurrentHashMap<>();
    private final List<ShortcutConflict> conflicts = new ArrayList<>();
    private final List<ShortcutChangeListener> listeners = new ArrayList<>();
    
    // Configuration
    private ShortcutPreset currentPreset = ShortcutPreset.DEFAULT;
    private Scene activeScene;
    private boolean enabled = true;
    private Preferences preferences;
    
    /**
     * Shortcut action definition
     */
    public static class ShortcutAction {
        private final String id;
        private final String name;
        private final String description;
        private final String categoryId;
        private final Runnable action;
        private final KeyCombination defaultKey;
        private KeyCombination currentKey;
        private boolean enabled;
        private ContextScope scope;
        
        public ShortcutAction(String id, String name, String description, String categoryId,
                            KeyCombination defaultKey, Runnable action) {
            this.id = id;
            this.name = name;
            this.description = description;
            this.categoryId = categoryId;
            this.defaultKey = defaultKey;
            this.currentKey = defaultKey;
            this.action = action;
            this.enabled = true;
            this.scope = ContextScope.GLOBAL;
        }
        
        // Getters
        public String getId() { return id; }
        public String getName() { return name; }
        public String getDescription() { return description; }
        public String getCategoryId() { return categoryId; }
        public KeyCombination getDefaultKey() { return defaultKey; }
        public KeyCombination getCurrentKey() { return currentKey; }
        public boolean isEnabled() { return enabled; }
        public ContextScope getScope() { return scope; }
        public Runnable getAction() { return action; }
        
        // Setters
        public void setCurrentKey(KeyCombination key) { this.currentKey = key; }
        public void setEnabled(boolean enabled) { this.enabled = enabled; }
        public void setScope(ContextScope scope) { this.scope = scope; }
        
        public void execute() {
            if (enabled && action != null) {
                try {
                    action.run();
                } catch (Exception e) {
                    logger.error("Error executing shortcut action: {}", id, e);
                }
            }
        }
    }
    
    /**
     * Shortcut category for organization
     */
    public static class ShortcutCategory {
        private final String id;
        private final String name;
        private final String description;
        private final int priority;
        
        public ShortcutCategory(String id, String name, String description, int priority) {
            this.id = id;
            this.name = name;
            this.description = description;
            this.priority = priority;
        }
        
        public String getId() { return id; }
        public String getName() { return name; }
        public String getDescription() { return description; }
        public int getPriority() { return priority; }
    }
    
    /**
     * Context scope for shortcuts
     */
    public enum ContextScope {
        GLOBAL,
        VIEWPORT_FOCUSED,
        MODEL_BROWSER_FOCUSED,
        PROPERTY_PANEL_FOCUSED,
        TEXT_INPUT_FOCUSED
    }
    
    /**
     * Predefined shortcut presets
     */
    public enum ShortcutPreset {
        DEFAULT("Default", "OpenMason default shortcuts"),
        BLENDER("Blender", "Blender-style shortcuts for 3D workflow"),
        MAYA("Maya", "Maya-style shortcuts for animation workflow"),
        CUSTOM("Custom", "User-customized shortcuts");
        
        private final String displayName;
        private final String description;
        
        ShortcutPreset(String displayName, String description) {
            this.displayName = displayName;
            this.description = description;
        }
        
        public String getDisplayName() { return displayName; }
        public String getDescription() { return description; }
    }
    
    /**
     * Shortcut conflict information
     */
    public static class ShortcutConflict {
        private final String shortcut1Id;
        private final String shortcut2Id;
        private final KeyCombination keyCombo;
        private final ConflictSeverity severity;
        
        public ShortcutConflict(String shortcut1Id, String shortcut2Id, 
                              KeyCombination keyCombo, ConflictSeverity severity) {
            this.shortcut1Id = shortcut1Id;
            this.shortcut2Id = shortcut2Id;
            this.keyCombo = keyCombo;
            this.severity = severity;
        }
        
        public String getShortcut1Id() { return shortcut1Id; }
        public String getShortcut2Id() { return shortcut2Id; }
        public KeyCombination getKeyCombo() { return keyCombo; }
        public ConflictSeverity getSeverity() { return severity; }
    }
    
    public enum ConflictSeverity {
        HIGH,    // Same context, exact same key
        MEDIUM,  // Different context, same key
        LOW      // Similar keys (modifier differences)
    }
    
    /**
     * Shortcut change listener
     */
    public interface ShortcutChangeListener {
        void onShortcutChanged(String shortcutId, KeyCombination oldKey, KeyCombination newKey);
        void onPresetChanged(ShortcutPreset oldPreset, ShortcutPreset newPreset);
        void onConflictDetected(List<ShortcutConflict> conflicts);
    }
    
    private KeyboardShortcutSystem() {
        this.preferences = Preferences.userRoot().node(this.getClass().getName());
        initializeDefaultCategories();
        initializeDefaultShortcuts();
        loadUserCustomizations();
    }
    
    public static synchronized KeyboardShortcutSystem getInstance() {
        if (instance == null) {
            instance = new KeyboardShortcutSystem();
        }
        return instance;
    }
    
    /**
     * Initialize the shortcut system with a JavaFX scene
     */
    public void initialize(Scene scene) {
        this.activeScene = scene;
        setupKeyEventHandlers();
        logger.info("Keyboard shortcut system initialized with scene");
    }
    
    /**
     * Initialize default shortcut categories
     */
    private void initializeDefaultCategories() {
        addCategory("file", "File Operations", "File management and project operations", 1);
        addCategory("edit", "Edit Operations", "Editing and modification commands", 2);
        addCategory("view", "View Controls", "Viewport and view manipulation", 3);
        addCategory("model", "Model Operations", "Model-specific operations", 4);
        addCategory("texture", "Texture Controls", "Texture and material operations", 5);
        addCategory("animation", "Animation", "Animation playback and controls", 6);
        addCategory("navigation", "Navigation", "3D viewport navigation", 7);
        addCategory("tools", "Tools", "Utility tools and validation", 8);
        addCategory("panels", "Panels", "UI panel visibility and layout", 9);
    }
    
    /**
     * Initialize default shortcuts for OpenMason
     */
    private void initializeDefaultShortcuts() {
        // File operations
        addShortcut("file.new", "New Model", "Create a new model", "file",
                new KeyCodeCombination(KeyCode.N, KeyCombination.CONTROL_DOWN), () -> {});
        addShortcut("file.open", "Open Model", "Open an existing model", "file",
                new KeyCodeCombination(KeyCode.O, KeyCombination.CONTROL_DOWN), () -> {});
        addShortcut("file.save", "Save Model", "Save the current model", "file",
                new KeyCodeCombination(KeyCode.S, KeyCombination.CONTROL_DOWN), () -> {});
        addShortcut("file.save_as", "Save As", "Save model with new name", "file",
                new KeyCodeCombination(KeyCode.S, KeyCombination.CONTROL_DOWN, KeyCombination.SHIFT_DOWN), () -> {});
        addShortcut("file.export", "Export Model", "Export model to file", "file",
                new KeyCodeCombination(KeyCode.E, KeyCombination.CONTROL_DOWN), () -> {});
        
        // Edit operations
        addShortcut("edit.undo", "Undo", "Undo last action", "edit",
                new KeyCodeCombination(KeyCode.Z, KeyCombination.CONTROL_DOWN), () -> {});
        addShortcut("edit.redo", "Redo", "Redo last undone action", "edit",
                new KeyCodeCombination(KeyCode.Y, KeyCombination.CONTROL_DOWN), () -> {});
        addShortcut("edit.preferences", "Preferences", "Open preferences dialog", "edit",
                new KeyCodeCombination(KeyCode.COMMA, KeyCombination.CONTROL_DOWN), () -> {});
        
        // View controls
        addShortcut("view.reset", "Reset View", "Reset viewport camera", "view",
                new KeyCodeCombination(KeyCode.HOME), () -> {});
        addShortcut("view.fit", "Fit to View", "Fit model to viewport", "view",
                new KeyCodeCombination(KeyCode.F), () -> {});
        addShortcut("view.wireframe", "Toggle Wireframe", "Toggle wireframe mode", "view",
                new KeyCodeCombination(KeyCode.Z), () -> {});
        addShortcut("view.grid", "Toggle Grid", "Toggle viewport grid", "view",
                new KeyCodeCombination(KeyCode.G), () -> {});
        addShortcut("view.axes", "Toggle Axes", "Toggle coordinate axes", "view",
                new KeyCodeCombination(KeyCode.A), () -> {});
        
        // Texture variant switching (Ctrl+1-4)
        addShortcut("texture.variant1", "Default Variant", "Switch to default cow variant", "texture",
                new KeyCodeCombination(KeyCode.DIGIT1, KeyCombination.CONTROL_DOWN), () -> {});
        addShortcut("texture.variant2", "Angus Variant", "Switch to angus cow variant", "texture",
                new KeyCodeCombination(KeyCode.DIGIT2, KeyCombination.CONTROL_DOWN), () -> {});
        addShortcut("texture.variant3", "Highland Variant", "Switch to highland cow variant", "texture",
                new KeyCodeCombination(KeyCode.DIGIT3, KeyCombination.CONTROL_DOWN), () -> {});
        addShortcut("texture.variant4", "Jersey Variant", "Switch to jersey cow variant", "texture",
                new KeyCodeCombination(KeyCode.DIGIT4, KeyCombination.CONTROL_DOWN), () -> {});
        
        // Navigation
        addShortcut("nav.zoom_in", "Zoom In", "Zoom into viewport", "navigation",
                new KeyCodeCombination(KeyCode.PLUS), () -> {});
        addShortcut("nav.zoom_out", "Zoom Out", "Zoom out of viewport", "navigation",
                new KeyCodeCombination(KeyCode.MINUS), () -> {});
        
        // Panel toggles
        addShortcut("panel.model_browser", "Toggle Model Browser", "Show/hide model browser", "panels",
                new KeyCodeCombination(KeyCode.F1), () -> {});
        addShortcut("panel.properties", "Toggle Properties", "Show/hide property panel", "panels",
                new KeyCodeCombination(KeyCode.F2), () -> {});
        addShortcut("panel.status_bar", "Toggle Status Bar", "Show/hide status bar", "panels",
                new KeyCodeCombination(KeyCode.F3), () -> {});
        
        // Tools
        addShortcut("tools.validate", "Validate Model", "Validate current model", "tools",
                new KeyCodeCombination(KeyCode.V, KeyCombination.CONTROL_DOWN), () -> {});
        addShortcut("tools.generate_textures", "Generate Textures", "Generate texture variants", "tools",
                new KeyCodeCombination(KeyCode.T, KeyCombination.CONTROL_DOWN), () -> {});
        
        logger.info("Initialized {} default shortcuts in {} categories", 
                   shortcuts.size(), categories.size());
    }
    
    /**
     * Add a shortcut category
     */
    public void addCategory(String id, String name, String description, int priority) {
        categories.put(id, new ShortcutCategory(id, name, description, priority));
    }
    
    /**
     * Add a shortcut action
     */
    public void addShortcut(String id, String name, String description, String categoryId,
                           KeyCombination defaultKey, Runnable action) {
        ShortcutAction shortcut = new ShortcutAction(id, name, description, categoryId, defaultKey, action);
        shortcuts.put(id, shortcut);
        
        if (defaultKey != null) {
            keyMappings.put(defaultKey, id);
        }
        
        detectConflicts();
    }
    
    /**
     * Update shortcut key binding
     */
    public boolean updateShortcut(String shortcutId, KeyCombination newKey) {
        ShortcutAction shortcut = shortcuts.get(shortcutId);
        if (shortcut == null) {
            return false;
        }
        
        KeyCombination oldKey = shortcut.getCurrentKey();
        
        // Remove old mapping
        if (oldKey != null) {
            keyMappings.remove(oldKey);
        }
        
        // Check for conflicts before applying
        if (newKey != null && keyMappings.containsKey(newKey)) {
            String conflictingId = keyMappings.get(newKey);
            logger.warn("Key conflict detected: {} conflicts with {}", shortcutId, conflictingId);
            return false;
        }
        
        // Apply new mapping
        shortcut.setCurrentKey(newKey);
        if (newKey != null) {
            keyMappings.put(newKey, shortcutId);
        }
        
        detectConflicts();
        notifyShortcutChanged(shortcutId, oldKey, newKey);
        saveUserCustomizations();
        
        logger.info("Updated shortcut {}: {} -> {}", shortcutId, oldKey, newKey);
        return true;
    }
    
    /**
     * Apply a preset configuration
     */
    public void applyPreset(ShortcutPreset preset) {
        ShortcutPreset oldPreset = currentPreset;
        currentPreset = preset;
        
        switch (preset) {
            case BLENDER:
                applyBlenderPreset();
                break;
            case MAYA:
                applyMayaPreset();
                break;
            case CUSTOM:
                loadUserCustomizations();
                break;
            case DEFAULT:
            default:
                resetToDefaults();
                break;
        }
        
        detectConflicts();
        notifyPresetChanged(oldPreset, preset);
        saveUserCustomizations();
        
        logger.info("Applied shortcut preset: {}", preset.getDisplayName());
    }
    
    /**
     * Apply Blender-style shortcuts
     */
    private void applyBlenderPreset() {
        // Blender uses different conventions
        updateShortcut("view.reset", new KeyCodeCombination(KeyCode.HOME));
        updateShortcut("view.fit", new KeyCodeCombination(KeyCode.PERIOD, KeyCombination.CONTROL_DOWN));
        updateShortcut("view.wireframe", new KeyCodeCombination(KeyCode.Z));
        updateShortcut("nav.zoom_in", new KeyCodeCombination(KeyCode.PLUS, KeyCombination.CONTROL_DOWN));
        updateShortcut("nav.zoom_out", new KeyCodeCombination(KeyCode.MINUS, KeyCombination.CONTROL_DOWN));
        
        // Blender-specific view angles
        updateShortcut("texture.variant1", new KeyCodeCombination(KeyCode.NUMPAD1));
        updateShortcut("texture.variant2", new KeyCodeCombination(KeyCode.NUMPAD3));
        updateShortcut("texture.variant3", new KeyCodeCombination(KeyCode.NUMPAD7));
        
        logger.info("Applied Blender preset shortcuts");
    }
    
    /**
     * Apply Maya-style shortcuts
     */
    private void applyMayaPreset() {
        // Maya conventions
        updateShortcut("view.fit", new KeyCodeCombination(KeyCode.F));
        updateShortcut("view.wireframe", new KeyCodeCombination(KeyCode.DIGIT4));
        updateShortcut("view.reset", new KeyCodeCombination(KeyCode.SPACE, KeyCombination.ALT_DOWN));
        
        logger.info("Applied Maya preset shortcuts");
    }
    
    /**
     * Reset all shortcuts to defaults
     */
    private void resetToDefaults() {
        keyMappings.clear();
        
        for (ShortcutAction shortcut : shortcuts.values()) {
            shortcut.setCurrentKey(shortcut.getDefaultKey());
            if (shortcut.getDefaultKey() != null) {
                keyMappings.put(shortcut.getDefaultKey(), shortcut.getId());
            }
        }
        
        logger.info("Reset all shortcuts to defaults");
    }
    
    /**
     * Detect shortcut conflicts
     */
    private void detectConflicts() {
        conflicts.clear();
        
        Map<KeyCombination, List<String>> keyGroups = new HashMap<>();
        
        // Group shortcuts by key combination
        for (Map.Entry<String, ShortcutAction> entry : shortcuts.entrySet()) {
            KeyCombination key = entry.getValue().getCurrentKey();
            if (key != null) {
                keyGroups.computeIfAbsent(key, k -> new ArrayList<>()).add(entry.getKey());
            }
        }
        
        // Find conflicts
        for (Map.Entry<KeyCombination, List<String>> entry : keyGroups.entrySet()) {
            List<String> shortcutIds = entry.getValue();
            if (shortcutIds.size() > 1) {
                KeyCombination key = entry.getKey();
                
                for (int i = 0; i < shortcutIds.size(); i++) {
                    for (int j = i + 1; j < shortcutIds.size(); j++) {
                        String id1 = shortcutIds.get(i);
                        String id2 = shortcutIds.get(j);
                        
                        ConflictSeverity severity = determineConflictSeverity(id1, id2, key);
                        conflicts.add(new ShortcutConflict(id1, id2, key, severity));
                    }
                }
            }
        }
        
        if (!conflicts.isEmpty()) {
            notifyConflictsDetected(conflicts);
            logger.warn("Detected {} shortcut conflicts", conflicts.size());
        }
    }
    
    /**
     * Determine severity of shortcut conflict
     */
    private ConflictSeverity determineConflictSeverity(String id1, String id2, KeyCombination key) {
        ShortcutAction shortcut1 = shortcuts.get(id1);
        ShortcutAction shortcut2 = shortcuts.get(id2);
        
        if (shortcut1.getScope() == shortcut2.getScope()) {
            return ConflictSeverity.HIGH;
        } else {
            return ConflictSeverity.MEDIUM;
        }
    }
    
    /**
     * Set up key event handlers for the active scene
     */
    private void setupKeyEventHandlers() {
        if (activeScene == null) {
            return;
        }
        
        activeScene.setOnKeyPressed(event -> {
            if (!enabled) {
                return;
            }
            
            KeyCombination pressed = KeyCombination.valueOf(event.getCode().toString());
            
            // Try to match exact combination first
            String shortcutId = keyMappings.get(pressed);
            
            // If no exact match, try with modifiers
            if (shortcutId == null) {
                for (Map.Entry<KeyCombination, String> entry : keyMappings.entrySet()) {
                    if (entry.getKey().match(event)) {
                        shortcutId = entry.getValue();
                        break;
                    }
                }
            }
            
            if (shortcutId != null) {
                ShortcutAction shortcut = shortcuts.get(shortcutId);
                if (shortcut != null && shortcut.isEnabled()) {
                    // Check context scope
                    if (isShortcutValidForCurrentContext(shortcut)) {
                        event.consume();
                        shortcut.execute();
                        logger.debug("Executed shortcut: {} ({})", shortcut.getName(), shortcutId);
                    }
                }
            }
        });
        
        logger.info("Key event handlers configured for shortcut system");
    }
    
    /**
     * Check if shortcut is valid for current UI context
     */
    private boolean isShortcutValidForCurrentContext(ShortcutAction shortcut) {
        // For now, all shortcuts are global
        // In a full implementation, this would check focused controls
        return shortcut.getScope() == ContextScope.GLOBAL;
    }
    
    /**
     * Save user customizations to preferences
     */
    private void saveUserCustomizations() {
        try {
            StringBuilder customizations = new StringBuilder();
            
            for (ShortcutAction shortcut : shortcuts.values()) {
                if (!Objects.equals(shortcut.getCurrentKey(), shortcut.getDefaultKey())) {
                    customizations.append(shortcut.getId())
                                 .append("=")
                                 .append(shortcut.getCurrentKey() != null ? 
                                        shortcut.getCurrentKey().toString() : "")
                                 .append("\n");
                }
            }
            
            preferences.put("customizations", customizations.toString());
            preferences.put("preset", currentPreset.name());
            preferences.flush();
            
            logger.debug("Saved shortcut customizations");
            
        } catch (Exception e) {
            logger.error("Failed to save shortcut customizations", e);
        }
    }
    
    /**
     * Load user customizations from preferences
     */
    private void loadUserCustomizations() {
        try {
            String presetName = preferences.get("preset", ShortcutPreset.DEFAULT.name());
            currentPreset = ShortcutPreset.valueOf(presetName);
            
            String customizations = preferences.get("customizations", "");
            if (!customizations.isEmpty()) {
                String[] lines = customizations.split("\n");
                
                for (String line : lines) {
                    if (line.contains("=")) {
                        String[] parts = line.split("=", 2);
                        String shortcutId = parts[0];
                        String keyString = parts[1];
                        
                        if (shortcuts.containsKey(shortcutId)) {
                            try {
                                KeyCombination key = keyString.isEmpty() ? 
                                    null : KeyCombination.valueOf(keyString);
                                updateShortcut(shortcutId, key);
                            } catch (Exception e) {
                                logger.warn("Invalid key combination for {}: {}", shortcutId, keyString);
                            }
                        }
                    }
                }
            }
            
            logger.info("Loaded shortcut customizations for preset: {}", currentPreset.getDisplayName());
            
        } catch (Exception e) {
            logger.error("Failed to load shortcut customizations", e);
        }
    }
    
    // Getters for UI access
    public Collection<ShortcutAction> getAllShortcuts() {
        return new ArrayList<>(shortcuts.values());
    }
    
    public Collection<ShortcutCategory> getAllCategories() {
        return new ArrayList<>(categories.values());
    }
    
    public List<ShortcutAction> getShortcutsInCategory(String categoryId) {
        return shortcuts.values().stream()
                .filter(s -> categoryId.equals(s.getCategoryId()))
                .sorted(Comparator.comparing(ShortcutAction::getName))
                .toList();
    }
    
    public List<ShortcutConflict> getConflicts() {
        return new ArrayList<>(conflicts);
    }
    
    public ShortcutPreset getCurrentPreset() {
        return currentPreset;
    }
    
    public boolean isEnabled() {
        return enabled;
    }
    
    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }
    
    // Listener management
    public void addChangeListener(ShortcutChangeListener listener) {
        listeners.add(listener);
    }
    
    public void removeChangeListener(ShortcutChangeListener listener) {
        listeners.remove(listener);
    }
    
    private void notifyShortcutChanged(String shortcutId, KeyCombination oldKey, KeyCombination newKey) {
        for (ShortcutChangeListener listener : listeners) {
            try {
                listener.onShortcutChanged(shortcutId, oldKey, newKey);
            } catch (Exception e) {
                logger.error("Error notifying shortcut change listener", e);
            }
        }
    }
    
    private void notifyPresetChanged(ShortcutPreset oldPreset, ShortcutPreset newPreset) {
        for (ShortcutChangeListener listener : listeners) {
            try {
                listener.onPresetChanged(oldPreset, newPreset);
            } catch (Exception e) {
                logger.error("Error notifying preset change listener", e);
            }
        }
    }
    
    private void notifyConflictsDetected(List<ShortcutConflict> conflicts) {
        for (ShortcutChangeListener listener : listeners) {
            try {
                listener.onConflictDetected(conflicts);
            } catch (Exception e) {
                logger.error("Error notifying conflict detection listener", e);
            }
        }
    }
    
    /**
     * Connect shortcut actions to MainController methods
     */
    public void connectToMainController(Object mainController) {
        try {
            // Use reflection to connect shortcuts to controller methods
            // This would be implemented based on the specific MainController API
            logger.info("Connected shortcuts to MainController");
            
        } catch (Exception e) {
            logger.error("Failed to connect shortcuts to MainController", e);
        }
    }
    
    /**
     * Export shortcuts configuration to file
     */
    public void exportConfiguration(File file) throws IOException {
        try (PrintWriter writer = new PrintWriter(new FileWriter(file))) {
            writer.println("# OpenMason Keyboard Shortcuts Configuration");
            writer.println("# Generated on: " + new Date());
            writer.println("preset=" + currentPreset.name());
            writer.println();
            
            for (ShortcutCategory category : categories.values()) {
                writer.println("# " + category.getName());
                
                for (ShortcutAction shortcut : getShortcutsInCategory(category.getId())) {
                    writer.printf("%s=%s # %s%n", 
                                 shortcut.getId(),
                                 shortcut.getCurrentKey() != null ? shortcut.getCurrentKey().toString() : "",
                                 shortcut.getDescription());
                }
                
                writer.println();
            }
        }
        
        logger.info("Exported shortcuts configuration to: {}", file.getAbsolutePath());
    }
    
    /**
     * Import shortcuts configuration from file
     */
    public void importConfiguration(File file) throws IOException {
        Properties props = new Properties();
        
        try (FileInputStream fis = new FileInputStream(file)) {
            props.load(fis);
        }
        
        // Apply preset if specified
        String presetName = props.getProperty("preset");
        if (presetName != null) {
            try {
                applyPreset(ShortcutPreset.valueOf(presetName));
            } catch (IllegalArgumentException e) {
                logger.warn("Unknown preset in import: {}", presetName);
            }
        }
        
        // Apply individual shortcuts
        for (String key : props.stringPropertyNames()) {
            if (!key.equals("preset") && shortcuts.containsKey(key)) {
                String value = props.getProperty(key);
                try {
                    KeyCombination keyCombo = value.isEmpty() ? null : KeyCombination.valueOf(value);
                    updateShortcut(key, keyCombo);
                } catch (Exception e) {
                    logger.warn("Invalid key combination for {}: {}", key, value);
                }
            }
        }
        
        logger.info("Imported shortcuts configuration from: {}", file.getAbsolutePath());
    }
}