package com.openmason.ui.shortcuts;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.*;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.prefs.Preferences;

/**
 * Keyboard shortcut system for OpenMason Tool.
 * Manages keyboard shortcuts and their actions across the application.
 */
public class KeyboardShortcutSystem {
    
    private static final Logger logger = LoggerFactory.getLogger(KeyboardShortcutSystem.class);
    private static volatile KeyboardShortcutSystem instance;
    
    private final Map<String, ShortcutAction> shortcuts = new ConcurrentHashMap<>();
    private final Map<String, String> keyMappings = new ConcurrentHashMap<>();
    private final Preferences prefs = Preferences.userNodeForPackage(KeyboardShortcutSystem.class);
    
    /**
     * Represents a shortcut category
     */
    public static class ShortcutCategory {
        private final String id;
        private final String name;
        private final String description;
        
        public ShortcutCategory(String id, String name, String description) {
            this.id = id;
            this.name = name;
            this.description = description;
        }
        
        public ShortcutCategory(String name, String description) {
            this.id = name.toLowerCase().replace(" ", "_");
            this.name = name;
            this.description = description;
        }
        
        public String getId() { return id; }
        public String getName() { return name; }
        public String getDescription() { return description; }
    }
    
    /**
     * Represents a shortcut conflict
     */
    public static class ShortcutConflict {
        private final String key;
        private final List<ShortcutAction> conflictingActions;
        
        public ShortcutConflict(String key, List<ShortcutAction> conflictingActions) {
            this.key = key;
            this.conflictingActions = new ArrayList<>(conflictingActions);
        }
        
        public String getKey() { return key; }
        public List<ShortcutAction> getConflictingActions() { return conflictingActions; }
    }
    
    /**
     * Predefined shortcut presets
     */
    public enum ShortcutPreset {
        DEFAULT("Default", "Standard OpenMason shortcuts"),
        BLENDER("Blender-like", "Shortcuts similar to Blender 3D"),
        MAYA("Maya-like", "Shortcuts similar to Autodesk Maya"),
        MAX("3ds Max-like", "Shortcuts similar to 3ds Max");
        
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
     * Represents a keyboard shortcut action
     */
    public static class ShortcutAction {
        private final String id;
        private final String name;
        private final String description;
        private final String category;
        private final Object defaultKey;
        private Object currentKey;
        private final Runnable action;
        
        public ShortcutAction(String id, String name, String description, String category, 
                            Object defaultKey, Runnable action) {
            this.id = id;
            this.name = name;
            this.description = description;
            this.category = category;
            this.defaultKey = defaultKey;
            this.currentKey = defaultKey;
            this.action = action;
        }
        
        public String getId() { return id; }
        public String getName() { return name; }
        public String getDescription() { return description; }
        public String getCategory() { return category; }
        public String getCategoryId() { return category.toLowerCase().replace(" ", "_"); }
        public Object getDefaultKey() { return defaultKey; }
        public Object getCurrentKey() { return currentKey; }
        
        public void setCurrentKey(Object key) {
            this.currentKey = key;
        }
        
        public void execute() {
            if (action != null) {
                try {
                    action.run();
                } catch (Exception e) {
                    logger.error("Error executing shortcut action: " + id, e);
                }
            }
        }
    }
    
    private KeyboardShortcutSystem() {
        initializeDefaultShortcuts();
        loadCustomShortcuts();
    }
    
    public static KeyboardShortcutSystem getInstance() {
        if (instance == null) {
            synchronized (KeyboardShortcutSystem.class) {
                if (instance == null) {
                    instance = new KeyboardShortcutSystem();
                }
            }
        }
        return instance;
    }
    
    /**
     * Initialize default shortcuts for the application
     */
    private void initializeDefaultShortcuts() {
        // File operations
        registerShortcut("file.new", "New", "Create new model", "File", "Ctrl+N", () -> {});
        registerShortcut("file.open", "Open", "Open model", "File", "Ctrl+O", () -> {});
        registerShortcut("file.save", "Save", "Save model", "File", "Ctrl+S", () -> {});
        registerShortcut("file.save_as", "Save As", "Save model as", "File", "Ctrl+Shift+S", () -> {});
        registerShortcut("file.export", "Export", "Export model", "File", "Ctrl+E", () -> {});
        
        // Edit operations
        registerShortcut("edit.undo", "Undo", "Undo last action", "Edit", "Ctrl+Z", () -> {});
        registerShortcut("edit.redo", "Redo", "Redo last action", "Edit", "Ctrl+Y", () -> {});
        registerShortcut("edit.copy", "Copy", "Copy selection", "Edit", "Ctrl+C", () -> {});
        registerShortcut("edit.paste", "Paste", "Paste clipboard", "Edit", "Ctrl+V", () -> {});
        registerShortcut("edit.delete", "Delete", "Delete selection", "Edit", "Delete", () -> {});
        
        // View operations
        registerShortcut("view.zoom_in", "Zoom In", "Zoom in viewport", "View", "Ctrl++", () -> {});
        registerShortcut("view.zoom_out", "Zoom Out", "Zoom out viewport", "View", "Ctrl+-", () -> {});
        registerShortcut("view.fit_to_screen", "Fit to Screen", "Fit model to screen", "View", "Ctrl+0", () -> {});
        registerShortcut("view.wireframe", "Toggle Wireframe", "Toggle wireframe mode", "View", "W", () -> {});
        registerShortcut("view.grid", "Toggle Grid", "Toggle grid display", "View", "G", () -> {});
        
        // Navigation
        registerShortcut("nav.front", "Front View", "Switch to front view", "Navigation", "1", () -> {});
        registerShortcut("nav.back", "Back View", "Switch to back view", "Navigation", "3", () -> {});
        registerShortcut("nav.left", "Left View", "Switch to left view", "Navigation", "Ctrl+1", () -> {});
        registerShortcut("nav.right", "Right View", "Switch to right view", "Navigation", "Ctrl+3", () -> {});
        registerShortcut("nav.top", "Top View", "Switch to top view", "Navigation", "7", () -> {});
        registerShortcut("nav.bottom", "Bottom View", "Switch to bottom view", "Navigation", "Ctrl+7", () -> {});
        
        // Tools
        registerShortcut("tool.preferences", "Preferences", "Open preferences", "Tools", "Ctrl+P", () -> {});
        registerShortcut("tool.shortcuts", "Shortcuts", "Edit shortcuts", "Tools", "Ctrl+K", () -> {});
        
        // Help
        registerShortcut("help.about", "About", "Show about dialog", "Help", "F1", () -> {});
        registerShortcut("help.manual", "Manual", "Open user manual", "Help", "Shift+F1", () -> {});
    }
    
    /**
     * Register a new shortcut
     */
    public void registerShortcut(String id, String name, String description, String category, 
                                String defaultKey, Runnable action) {
        ShortcutAction shortcut = new ShortcutAction(id, name, description, category, defaultKey, action);
        shortcuts.put(id, shortcut);
        keyMappings.put(defaultKey, id);
        logger.debug("Registered shortcut: {} -> {}", defaultKey, id);
    }
    
    /**
     * Get all shortcuts
     */
    public Collection<ShortcutAction> getAllShortcuts() {
        return shortcuts.values();
    }
    
    /**
     * Get shortcuts by category
     */
    public List<ShortcutAction> getShortcutsByCategory(String category) {
        return shortcuts.values().stream()
            .filter(s -> category.equals(s.getCategory()))
            .sorted(Comparator.comparing(ShortcutAction::getName))
            .toList();
    }
    
    /**
     * Get all categories
     */
    public Set<String> getCategories() {
        return shortcuts.values().stream()
            .map(ShortcutAction::getCategory)
            .collect(java.util.stream.Collectors.toSet());
    }
    
    /**
     * Get all categories as ShortcutCategory objects
     */
    public List<ShortcutCategory> getAllCategories() {
        Map<String, String> categoryDescriptions = Map.of(
            "File", "File operations and project management",
            "Edit", "Editing and modification operations", 
            "View", "Viewport and display options",
            "Navigation", "Camera and view navigation",
            "Tools", "Application tools and utilities",
            "Help", "Help and documentation"
        );
        
        return getCategories().stream()
            .map(name -> new ShortcutCategory(name, categoryDescriptions.getOrDefault(name, "Shortcuts for " + name)))
            .sorted(Comparator.comparing(ShortcutCategory::getName))
            .toList();
    }
    
    /**
     * Update a shortcut's key binding
     */
    public boolean updateShortcut(String id, Object newKey) {
        ShortcutAction shortcut = shortcuts.get(id);
        if (shortcut == null) {
            return false;
        }
        
        String newKeyString = newKey.toString();
        
        // Check for conflicts
        if (hasConflict(newKeyString, id)) {
            logger.warn("Key conflict detected for: {}", newKeyString);
            return false;
        }
        
        // Remove old mapping
        String oldKey = shortcut.getCurrentKey().toString();
        keyMappings.remove(oldKey);
        
        // Set new mapping
        shortcut.setCurrentKey(newKey);
        keyMappings.put(newKeyString, id);
        
        // Save to preferences
        prefs.put("shortcut." + id, newKeyString);
        
        logger.debug("Updated shortcut {} from {} to {}", id, oldKey, newKeyString);
        return true;
    }
    
    /**
     * Check if a key has conflicts
     */
    public boolean hasConflict(String key, String excludeId) {
        String existingId = keyMappings.get(key);
        return existingId != null && !existingId.equals(excludeId);
    }
    
    /**
     * Get conflicting shortcuts for a key
     */
    public List<String> getConflicts(String key) {
        List<String> conflicts = new ArrayList<>();
        String conflictId = keyMappings.get(key);
        if (conflictId != null) {
            ShortcutAction conflict = shortcuts.get(conflictId);
            if (conflict != null) {
                conflicts.add(conflict.getName() + " (" + conflict.getCategory() + ")");
            }
        }
        return conflicts;
    }
    
    /**
     * Get all conflicts as ShortcutConflict objects
     */
    public List<ShortcutConflict> getConflicts() {
        Map<String, List<ShortcutAction>> conflictMap = new HashMap<>();
        
        // Group shortcuts by key to find conflicts
        for (ShortcutAction shortcut : shortcuts.values()) {
            String key = shortcut.getCurrentKey().toString();
            conflictMap.computeIfAbsent(key, k -> new ArrayList<>()).add(shortcut);
        }
        
        // Filter to only keys that have more than one shortcut
        List<ShortcutConflict> conflicts = new ArrayList<>();
        for (Map.Entry<String, List<ShortcutAction>> entry : conflictMap.entrySet()) {
            if (entry.getValue().size() > 1) {
                conflicts.add(new ShortcutConflict(entry.getKey(), entry.getValue()));
            }
        }
        
        return conflicts;
    }
    
    /**
     * Execute a shortcut by key
     */
    public boolean executeShortcut(String key) {
        String shortcutId = keyMappings.get(key);
        if (shortcutId != null) {
            ShortcutAction shortcut = shortcuts.get(shortcutId);
            if (shortcut != null) {
                shortcut.execute();
                return true;
            }
        }
        return false;
    }
    
    /**
     * Reset a shortcut to default
     */
    public void resetToDefault(String id) {
        ShortcutAction shortcut = shortcuts.get(id);
        if (shortcut != null) {
            String oldKey = shortcut.getCurrentKey().toString();
            keyMappings.remove(oldKey);
            
            shortcut.setCurrentKey(shortcut.getDefaultKey());
            keyMappings.put(shortcut.getDefaultKey().toString(), id);
            
            // Remove from preferences (will use default)
            prefs.remove("shortcut." + id);
            
            logger.debug("Reset shortcut {} to default: {}", id, shortcut.getDefaultKey());
        }
    }
    
    /**
     * Reset all shortcuts to default
     */
    public void resetAllToDefault() {
        for (ShortcutAction shortcut : shortcuts.values()) {
            resetToDefault(shortcut.getId());
        }
        logger.info("Reset all shortcuts to default");
    }
    
    /**
     * Load custom shortcuts from preferences
     */
    private void loadCustomShortcuts() {
        try {
            String[] keys = prefs.keys();
            for (String key : keys) {
                if (key.startsWith("shortcut.")) {
                    String shortcutId = key.substring(9); // Remove "shortcut." prefix
                    String customKey = prefs.get(key, null);
                    if (customKey != null) {
                        updateShortcut(shortcutId, customKey);
                    }
                }
            }
            logger.debug("Loaded {} custom shortcuts from preferences", keys.length);
        } catch (Exception e) {
            logger.error("Error loading custom shortcuts", e);
        }
    }
    
    /**
     * Save shortcuts to preferences
     */
    public void saveShortcuts() {
        try {
            for (ShortcutAction shortcut : shortcuts.values()) {
                if (!shortcut.getCurrentKey().equals(shortcut.getDefaultKey())) {
                    prefs.put("shortcut." + shortcut.getId(), shortcut.getCurrentKey().toString());
                } else {
                    prefs.remove("shortcut." + shortcut.getId());
                }
            }
            prefs.flush();
            logger.debug("Saved shortcuts to preferences");
        } catch (Exception e) {
            logger.error("Error saving shortcuts", e);
        }
    }
    
    /**
     * Export shortcuts to a properties format
     */
    public String exportShortcuts() {
        StringBuilder sb = new StringBuilder();
        sb.append("# OpenMason Keyboard Shortcuts\n");
        sb.append("# Generated on: ").append(new Date()).append("\n\n");
        
        Map<String, List<ShortcutAction>> byCategory = shortcuts.values().stream()
            .collect(java.util.stream.Collectors.groupingBy(ShortcutAction::getCategory));
        
        for (String category : byCategory.keySet().stream().sorted().toList()) {
            sb.append("[").append(category).append("]\n");
            List<ShortcutAction> categoryShortcuts = byCategory.get(category);
            categoryShortcuts.sort(Comparator.comparing(ShortcutAction::getName));
            
            for (ShortcutAction shortcut : categoryShortcuts) {
                sb.append(shortcut.getId()).append("=").append(shortcut.getCurrentKey()).append("\n");
            }
            sb.append("\n");
        }
        
        return sb.toString();
    }
    
    /**
     * Get shortcut by ID
     */
    public ShortcutAction getShortcut(String id) {
        return shortcuts.get(id);
    }
    
    /**
     * Check if system has unsaved changes
     */
    public boolean hasUnsavedChanges() {
        for (ShortcutAction shortcut : shortcuts.values()) {
            String currentKey = shortcut.getCurrentKey().toString();
            String savedKey = prefs.get("shortcut." + shortcut.getId(), shortcut.getDefaultKey().toString());
            if (!currentKey.equals(savedKey)) {
                return true;
            }
        }
        return false;
    }
    
    /**
     * Apply a shortcut preset
     */
    public void applyPreset(ShortcutPreset preset) {
        switch (preset) {
            case DEFAULT:
                resetAllToDefault();
                break;
            case BLENDER:
                applyBlenderPreset();
                break;
            case MAYA:
                applyMayaPreset();
                break;
            case MAX:
                applyMaxPreset();
                break;
        }
        logger.info("Applied preset: {}", preset.getDisplayName());
    }
    
    /**
     * Apply Blender-like shortcuts
     */
    private void applyBlenderPreset() {
        updateShortcut("file.new", "Ctrl+N");
        updateShortcut("file.open", "Ctrl+O");
        updateShortcut("file.save", "Ctrl+S");
        updateShortcut("file.save_as", "Ctrl+Alt+S");
        updateShortcut("view.zoom_in", "Plus");
        updateShortcut("view.zoom_out", "Minus");
        updateShortcut("nav.front", "Numpad1");
        updateShortcut("nav.back", "Ctrl+Numpad1");
        updateShortcut("nav.right", "Numpad3");
        updateShortcut("nav.left", "Ctrl+Numpad3");
        updateShortcut("nav.top", "Numpad7");
        updateShortcut("nav.bottom", "Ctrl+Numpad7");
    }
    
    /**
     * Apply Maya-like shortcuts
     */
    private void applyMayaPreset() {
        updateShortcut("file.new", "Ctrl+N");
        updateShortcut("file.open", "Ctrl+O");
        updateShortcut("file.save", "Ctrl+S");
        updateShortcut("view.fit_to_screen", "F");
        updateShortcut("view.wireframe", "4");
        updateShortcut("nav.front", "Alt+1");
        updateShortcut("nav.back", "Alt+3");
        updateShortcut("nav.top", "Alt+2");
    }
    
    /**
     * Apply 3ds Max-like shortcuts
     */
    private void applyMaxPreset() {
        updateShortcut("file.new", "Ctrl+N");
        updateShortcut("file.open", "Ctrl+O");
        updateShortcut("file.save", "Ctrl+S");
        updateShortcut("view.zoom_in", "Ctrl+Plus");
        updateShortcut("view.zoom_out", "Ctrl+Minus");
        updateShortcut("view.fit_to_screen", "Ctrl+Alt+Z");
        updateShortcut("nav.front", "F");
        updateShortcut("nav.back", "B");
        updateShortcut("nav.left", "L");
        updateShortcut("nav.right", "R");
        updateShortcut("nav.top", "T");
        updateShortcut("nav.bottom", "U");
    }
    
    /**
     * Import configuration from file
     */
    public void importConfiguration(File file) throws IOException {
        Properties props = new Properties();
        try (FileInputStream fis = new FileInputStream(file)) {
            props.load(fis);
        }
        
        for (String key : props.stringPropertyNames()) {
            String shortcutKey = props.getProperty(key);
            updateShortcut(key, shortcutKey);
        }
        
        logger.info("Imported {} shortcuts from {}", props.size(), file.getName());
    }
    
    /**
     * Export configuration to file
     */
    public void exportConfiguration(File file) throws IOException {
        Properties props = new Properties();
        
        for (ShortcutAction shortcut : shortcuts.values()) {
            props.setProperty(shortcut.getId(), shortcut.getCurrentKey().toString());
        }
        
        try (FileOutputStream fos = new FileOutputStream(file)) {
            props.store(fos, "OpenMason Keyboard Shortcuts - " + new Date());
        }
        
        logger.info("Exported {} shortcuts to {}", props.size(), file.getName());
    }
}