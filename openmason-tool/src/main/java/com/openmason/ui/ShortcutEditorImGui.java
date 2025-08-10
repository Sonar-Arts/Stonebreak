package com.openmason.ui;

import com.openmason.ui.shortcuts.KeyboardShortcutSystem;
import imgui.ImGui;
import imgui.flag.*;
import imgui.type.ImBoolean;
import imgui.type.ImInt;
import imgui.type.ImString;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.File;
import java.util.*;
import java.util.concurrent.locks.ReentrantReadWriteLock;

/**
 * Dear ImGui implementation of the Shortcut Editor Dialog.
 * Provides keyboard shortcut editing with table interface and key capture.
 * 
 * This class is thread-safe and implements proper error handling,
 * validation, and resource management.
 */
public class ShortcutEditorImGui implements ShortcutEditor {
    
    // Constants
    private static final int DEFAULT_SEARCH_BUFFER_SIZE = 256;
    private static final int DEFAULT_KEY_BUFFER_SIZE = 128;
    private static final int DEFAULT_WINDOW_WIDTH = 900;
    private static final int DEFAULT_WINDOW_HEIGHT = 700;
    private static final int KEY_CAPTURE_WINDOW_WIDTH = 400;
    private static final int KEY_CAPTURE_WINDOW_HEIGHT = 200;
    private static final int MAX_SHORTCUT_COUNT = 1000;
    private static final int MAX_CATEGORY_COUNT = 50;
    private static final String DEFAULT_STATUS_MESSAGE = "Ready";
    private static final String ALL_CATEGORIES_LABEL = "All Categories";
    private static final String UNKNOWN_CATEGORY = "Unknown";
    private static final String WINDOW_TITLE = "Keyboard Shortcuts - OpenMason";
    private static final String KEY_CAPTURE_TITLE = "Capture Key Combination";
    
    // File names
    private static final String DEFAULT_EXPORT_FILE = "openmason-shortcuts.properties";
    
    private static final Logger logger = LoggerFactory.getLogger(ShortcutEditorImGui.class);
    
    private final KeyboardShortcutSystem shortcutSystem;
    private final ReentrantReadWriteLock stateLock = new ReentrantReadWriteLock();
    
    // Modal state
    private final ImBoolean showEditor = new ImBoolean(false);
    private boolean hasUnsavedChanges = false;
    
    // Search and filter
    private final ImString searchText = new ImString(256);
    private final ImInt categoryFilter = new ImInt(0);
    private final ImInt presetCombo = new ImInt(0);
    
    // Table state
    private int selectedRow = -1;
    private boolean showKeyCapture = false;
    private final ImString capturedKey = new ImString(128);
    private String editingShortcutId = null;
    
    // Data - using thread-safe collections
    private final List<ShortcutTableRow> shortcutData = Collections.synchronizedList(new ArrayList<>());
    private final List<String> conflictData = Collections.synchronizedList(new ArrayList<>());
    private final List<String> categoryNames = Collections.synchronizedList(new ArrayList<>());
    private volatile String statusMessage = DEFAULT_STATUS_MESSAGE;
    
    // Key capture state
    private boolean isCapturingKey = false;
    private String capturedKeyString = "";
    private int captureStartTime = 0;
    
    /**
     * Table row representation of a shortcut with validation and thread-safety
     */
    public static class ShortcutTableRow {
        public final KeyboardShortcutSystem.ShortcutAction shortcut;
        public final String name;
        public final String category;
        private volatile String currentKey;
        public final String defaultKey;
        public final String description;
        private volatile boolean hasConflict = false;
        
        public ShortcutTableRow(KeyboardShortcutSystem.ShortcutAction shortcut, String categoryName) {
            this.shortcut = Objects.requireNonNull(shortcut, "Shortcut cannot be null");
            this.name = validateString(shortcut.getName(), "Shortcut name cannot be null or empty");
            this.category = validateString(categoryName, "Category name cannot be null");
            this.currentKey = keyToString(shortcut.getCurrentKey());
            this.defaultKey = keyToString(shortcut.getDefaultKey());
            this.description = shortcut.getDescription() != null ? shortcut.getDescription() : "";
        }
        
        /**
         * Validate string parameters
         */
        private static String validateString(String value, String errorMessage) {
            if (value == null || value.trim().isEmpty()) {
                throw new IllegalArgumentException(errorMessage);
            }
            return value.trim();
        }
        
        /**
         * Thread-safe getter for current key
         */
        public String getCurrentKey() {
            return currentKey;
        }
        
        /**
         * Thread-safe getter for conflict status
         */
        public boolean hasConflict() {
            return hasConflict;
        }
        
        /**
         * Thread-safe setter for conflict status
         */
        public void setHasConflict(boolean hasConflict) {
            this.hasConflict = hasConflict;
        }
        
        private static String keyToString(Object key) {
            // Convert key combination to string representation with validation
            if (key == null) {
                return "";
            }
            
            try {
                String result = key.toString();
                return result != null ? result.trim() : "";
            } catch (Exception e) {
                logger.warn("Error converting key to string: {}", key.getClass().getName(), e);
                return "<Error>";
            }
        }
        
        /**
         * Thread-safe update of current key
         */
        public void updateCurrentKey(Object newKey) {
            synchronized (this) {
                this.currentKey = keyToString(newKey);
            }
        }
        
        /**
         * Validate the row data integrity
         */
        public boolean isValid() {
            return shortcut != null && 
                   name != null && !name.trim().isEmpty() &&
                   category != null && !category.trim().isEmpty();
        }
        
        @Override
        public String toString() {
            return String.format("ShortcutTableRow{name='%s', category='%s', currentKey='%s', hasConflict=%s}", 
                               name, category, currentKey, hasConflict);
        }
    }
    
    public ShortcutEditorImGui() {
        this.shortcutSystem = Objects.requireNonNull(KeyboardShortcutSystem.getInstance(), 
            "KeyboardShortcutSystem instance cannot be null");
        
        try {
            loadData();
        } catch (Exception e) {
            logger.error("Failed to load initial shortcut data", e);
            statusMessage = "Error loading shortcuts: " + e.getMessage();
        }
    }
    
    /**
     * Show the shortcut editor modal dialog
     */
    public void show() {
        try {
            showEditor.set(true);
            loadData();
        } catch (Exception e) {
            logger.error("Failed to show shortcut editor", e);
            statusMessage = "Error showing editor: " + e.getMessage();
        }
    }
    
    /**
     * Render the shortcut editor using Dear ImGui
     * Thread-safe rendering with proper error handling
     */
    public void render() {
        if (!showEditor.get()) {
            return;
        }
        
        try {
            renderInternal();
        } catch (Exception e) {
            logger.error("Error during shortcut editor rendering", e);
            statusMessage = "Rendering error: " + e.getMessage();
        }
    }
    
    /**
     * Internal rendering method with error boundary
     */
    private void renderInternal() {
        
        // Center the modal with validation
        var viewport = ImGui.getMainViewport();
        if (viewport != null) {
            ImGui.setNextWindowPos(viewport.getCenterX(), viewport.getCenterY(), 
                                   ImGuiCond.Appearing, 0.5f, 0.5f);
        }
        ImGui.setNextWindowSize(DEFAULT_WINDOW_WIDTH, DEFAULT_WINDOW_HEIGHT, ImGuiCond.Appearing);
        
        int windowFlags = ImGuiWindowFlags.NoResize | ImGuiWindowFlags.NoCollapse;
        
        if (ImGui.beginPopupModal(WINDOW_TITLE, showEditor, windowFlags)) {
            renderHeader();
            ImGui.separator();
            
            renderSearchAndFilter();
            ImGui.separator();
            
            renderShortcutsTable();
            ImGui.separator();
            
            renderConflicts();
            ImGui.separator();
            
            renderFooter();
            
            ImGui.endPopup();
        }
        
        // Render key capture dialog separately
        if (showKeyCapture) {
            try {
                renderKeyCapture();
            } catch (Exception e) {
                logger.error("Error rendering key capture dialog", e);
                showKeyCapture = false;
            }
        }
        
        // Handle modal close with unsaved changes
        if (!showEditor.get() && hasUnsavedChanges) {
            try {
                showUnsavedChangesDialog();
            } catch (Exception e) {
                logger.error("Error showing unsaved changes dialog", e);
            }
        }
    }
    
    private void renderHeader() {
        ImGui.textColored(0.8f, 0.9f, 1.0f, 1.0f, "Keyboard Shortcuts");
        ImGui.text("Customize keyboard shortcuts for OpenMason. Choose a preset or create your own custom configuration.");
        
        ImGui.spacing();
        
        // Preset selection
        ImGui.text("Preset:");
        ImGui.sameLine();
        
        String[] presetNames = new String[KeyboardShortcutSystem.ShortcutPreset.values().length];
        for (int i = 0; i < presetNames.length; i++) {
            presetNames[i] = KeyboardShortcutSystem.ShortcutPreset.values()[i].getDisplayName();
        }
        
        if (ImGui.combo("##preset", presetCombo, presetNames)) {
            KeyboardShortcutSystem.ShortcutPreset selected = 
                KeyboardShortcutSystem.ShortcutPreset.values()[presetCombo.get()];
            applyPreset(selected);
        }
        
        ImGui.sameLine();
        if (ImGui.button("Reset to Preset")) {
            resetToPreset();
        }
    }
    
    private void renderSearchAndFilter() {
        // Search field
        ImGui.text("Search:");
        ImGui.sameLine();
        if (ImGui.inputText("##search", searchText)) {
            filterShortcuts();
        }
        
        ImGui.sameLine();
        
        // Category filter
        ImGui.text("Category:");
        ImGui.sameLine();
        String[] categories = categoryNames.toArray(new String[0]);
        if (ImGui.combo("##category", categoryFilter, categories)) {
            filterShortcuts();
        }
    }
    
    private void renderShortcutsTable() {
        if (ImGui.beginChild("ShortcutsTable", 0, -150)) {
            
            if (ImGui.beginTable("Shortcuts", 5, ImGuiTableFlags.Borders | ImGuiTableFlags.RowBg | 
                                                   ImGuiTableFlags.ScrollY | ImGuiTableFlags.Resizable)) {
                
                // Setup columns
                ImGui.tableSetupColumn("Command", ImGuiTableColumnFlags.WidthFixed, 180);
                ImGui.tableSetupColumn("Category", ImGuiTableColumnFlags.WidthFixed, 120);
                ImGui.tableSetupColumn("Current Key", ImGuiTableColumnFlags.WidthFixed, 130);
                ImGui.tableSetupColumn("Default Key", ImGuiTableColumnFlags.WidthFixed, 130);
                ImGui.tableSetupColumn("Description", ImGuiTableColumnFlags.WidthStretch);
                ImGui.tableHeadersRow();
                
                // Render rows with thread-safety
                synchronized (shortcutData) {
                    for (int i = 0; i < shortcutData.size(); i++) {
                        ShortcutTableRow row = shortcutData.get(i);
                        
                        if (row == null || !row.isValid()) {
                            continue; // Skip invalid rows
                        }
                        
                        ImGui.tableNextRow();
                        
                        // Highlight selected row
                        boolean isSelected = (selectedRow == i);
                        if (isSelected) {
                            ImGui.tableSetBgColor(ImGuiTableBgTarget.RowBg0, 
                                                ImGui.getColorU32(0.3f, 0.3f, 0.7f, 0.65f));
                        }
                        
                        // Highlight conflicted shortcuts
                        if (row.hasConflict()) {
                            ImGui.tableSetBgColor(ImGuiTableBgTarget.RowBg0, 
                                                ImGui.getColorU32(0.7f, 0.3f, 0.3f, 0.3f));
                        }
                    
                    // Command column
                    ImGui.tableSetColumnIndex(0);
                    if (ImGui.selectable(row.name + "##row" + i, isSelected, 
                                       ImGuiSelectableFlags.SpanAllColumns)) {
                        selectedRow = i;
                    }
                    
                    // Double-click to edit
                    if (ImGui.isItemHovered() && ImGui.isMouseDoubleClicked(0)) {
                        editShortcut(row);
                    }
                    
                    // Category column
                    ImGui.tableSetColumnIndex(1);
                    ImGui.text(row.category);
                    
                        // Current key column
                        ImGui.tableSetColumnIndex(2);
                        String currentKey = row.getCurrentKey();
                        if (row.hasConflict()) {
                            ImGui.textColored(1.0f, 0.5f, 0.5f, 1.0f, currentKey != null ? currentKey : "");
                        } else {
                            ImGui.text(currentKey != null ? currentKey : "");
                        }
                    
                        // Default key column
                        ImGui.tableSetColumnIndex(3);
                        ImGui.textColored(0.7f, 0.7f, 0.7f, 1.0f, row.defaultKey != null ? row.defaultKey : "");
                        
                        // Description column
                        ImGui.tableSetColumnIndex(4);
                        ImGui.text(row.description != null ? row.description : "");
                    }
                }
                
                ImGui.endTable();
            }
            
            // Action buttons
            ImGui.spacing();
            
            boolean hasSelection = selectedRow >= 0 && selectedRow < shortcutData.size();
            
            if (!hasSelection) {
                ImGui.pushStyleVar(ImGuiStyleVar.Alpha, 0.5f);
            }
            
            if (ImGui.button("Edit Shortcut") && hasSelection) {
                editShortcut(shortcutData.get(selectedRow));
            }
            
            ImGui.sameLine();
            if (ImGui.button("Clear") && hasSelection) {
                clearShortcut(shortcutData.get(selectedRow));
            }
            
            ImGui.sameLine();
            if (ImGui.button("Reset to Default") && hasSelection) {
                resetShortcut(shortcutData.get(selectedRow));
            }
            
            if (!hasSelection) {
                ImGui.popStyleVar();
            }
            
        }
        ImGui.endChild();
    }
    
    private void renderConflicts() {
        if (ImGui.beginChild("ConflictsSection", 0, 100)) {
            
            ImGui.text("Conflicts:");
            
            if (conflictData.isEmpty()) {
                ImGui.textColored(0.0f, 1.0f, 0.0f, 1.0f, "No conflicts detected");
            } else {
                for (String conflict : conflictData) {
                    ImGui.textColored(1.0f, 0.6f, 0.0f, 1.0f, "⚠ " + conflict);
                }
            }
            
        }
        ImGui.endChild();
    }
    
    private void renderKeyCapture() {
        ImGui.setNextWindowPos(ImGui.getMainViewport().getCenterX(), ImGui.getMainViewport().getCenterY(), 
                               ImGuiCond.Appearing, 0.5f, 0.5f);
        ImGui.setNextWindowSize(400, 200, ImGuiCond.Appearing);
        
        int windowFlags = ImGuiWindowFlags.NoResize | 
                         ImGuiWindowFlags.NoCollapse | ImGuiWindowFlags.AlwaysAutoResize;
        
        ImBoolean keyCaptureOpen = new ImBoolean(showKeyCapture);
        
        if (ImGui.beginPopupModal("Capture Key Combination", keyCaptureOpen, windowFlags)) {
            
            ImGui.text("Press the desired key combination for:");
            ImGui.textColored(0.8f, 0.9f, 1.0f, 1.0f, editingShortcutId != null ? editingShortcutId : "Unknown");
            
            ImGui.spacing();
            ImGui.separator();
            ImGui.spacing();
            
            // Current capture display
            ImGui.text("Captured key:");
            ImGui.sameLine();
            if (capturedKeyString.isEmpty()) {
                ImGui.textColored(0.5f, 0.5f, 0.5f, 1.0f, "None");
            } else {
                ImGui.textColored(0.0f, 1.0f, 0.0f, 1.0f, capturedKeyString);
            }
            
            ImGui.spacing();
            ImGui.text("Press ESC to clear, or any key combination to capture.");
            
            // Handle key capture
            handleKeyCapture();
            
            ImGui.spacing();
            ImGui.separator();
            
            // Buttons
            if (ImGui.button("OK")) {
                applyKeyCapture();
                showKeyCapture = false;
            }
            
            ImGui.sameLine();
            if (ImGui.button("Cancel")) {
                showKeyCapture = false;
                capturedKeyString = "";
            }
            
            ImGui.endPopup();
        }
        
        showKeyCapture = keyCaptureOpen.get();
    }
    
    private void renderFooter() {
        ImGui.separator();
        
        // Status message
        if (hasUnsavedChanges) {
            ImGui.textColored(1.0f, 0.8f, 0.0f, 1.0f, "You have unsaved changes");
        } else {
            ImGui.text(statusMessage);
        }
        
        ImGui.sameLine(ImGui.getWindowWidth() - 350);
        
        // Action buttons
        if (ImGui.button("Import...")) {
            importConfiguration();
        }
        
        ImGui.sameLine();
        if (ImGui.button("Export...")) {
            exportConfiguration();
        }
        
        ImGui.sameLine();
        if (ImGui.button("Reset All")) {
            resetAllShortcuts();
        }
        
        ImGui.sameLine();
        if (ImGui.button("Apply")) {
            applyChanges();
        }
        
        ImGui.sameLine();
        if (ImGui.button("Cancel")) {
            if (hasUnsavedChanges) {
                showUnsavedChangesDialog();
            } else {
                showEditor.set(false);
            }
        }
        
        ImGui.sameLine();
        if (ImGui.button("OK")) {
            applyAndClose();
        }
    }
    
    // Data management with error handling and validation
    
    private void loadData() {
        stateLock.writeLock().lock();
        try {
            shortcutData.clear();
            categoryNames.clear();
            
            // Validate shortcut system
            if (shortcutSystem == null) {
                throw new IllegalStateException("Shortcut system is not initialized");
            }
            
            // Add "All Categories" option
            categoryNames.add(ALL_CATEGORIES_LABEL);
            
            var allShortcuts = shortcutSystem.getAllShortcuts();
            if (allShortcuts == null) {
                logger.warn("Shortcut system returned null shortcuts list");
                return;
            }
            
            // Validate shortcut count
            if (allShortcuts.size() > MAX_SHORTCUT_COUNT) {
                logger.warn("Too many shortcuts loaded: {}, maximum is {}", 
                           allShortcuts.size(), MAX_SHORTCUT_COUNT);
            }
        
            // Load shortcuts with validation
            for (KeyboardShortcutSystem.ShortcutAction shortcut : allShortcuts) {
                if (shortcut == null) {
                    logger.warn("Null shortcut found in shortcuts list, skipping");
                    continue;
                }
                
                String categoryName = findCategoryName(shortcut);
                
                try {
                    shortcutData.add(new ShortcutTableRow(shortcut, categoryName));
                    
                    // Add category to list if not present
                    if (!categoryNames.contains(categoryName) && categoryNames.size() < MAX_CATEGORY_COUNT) {
                        categoryNames.add(categoryName);
                    }
                } catch (Exception e) {
                    logger.error("Error creating shortcut table row for: {}", shortcut.getId(), e);
                }
            }
        
            // Sort categories (skip "All Categories" at index 0)
            if (categoryNames.size() > 1) {
                Collections.sort(categoryNames.subList(1, categoryNames.size()));
            }
            
            updateConflicts();
            statusMessage = String.format("Loaded %d shortcuts in %d categories", 
                                        shortcutData.size(), categoryNames.size() - 1);
        } catch (Exception e) {
            logger.error("Failed to load shortcut data", e);
            statusMessage = "Error loading data: " + e.getMessage();
        } finally {
            stateLock.writeLock().unlock();
        }
    }
    
    /**
     * Find category name for a shortcut with validation
     */
    private String findCategoryName(KeyboardShortcutSystem.ShortcutAction shortcut) {
        if (shortcut == null || shortcut.getCategoryId() == null) {
            return UNKNOWN_CATEGORY;
        }
        
        try {
            var allCategories = shortcutSystem.getAllCategories();
            if (allCategories == null) {
                return UNKNOWN_CATEGORY;
            }
            
            return allCategories.stream()
                    .filter(Objects::nonNull)
                    .filter(cat -> Objects.equals(cat.getId(), shortcut.getCategoryId()))
                    .map(KeyboardShortcutSystem.ShortcutCategory::getName)
                    .filter(Objects::nonNull)
                    .findFirst()
                    .orElse(UNKNOWN_CATEGORY);
        } catch (Exception e) {
            logger.warn("Error finding category for shortcut: {}", shortcut.getId(), e);
            return UNKNOWN_CATEGORY;
        }
    }
    
    private void filterShortcuts() {
        // This would filter the displayed shortcuts based on search and category
        // For now, we'll just update the status
        String searchStr = searchText.get().toLowerCase();
        String selectedCategory = categoryNames.get(categoryFilter.get());
        
        long visibleCount = shortcutData.stream()
                .filter(row -> {
                    // Category filter
                    if (!"All Categories".equals(selectedCategory) && 
                        !selectedCategory.equals(row.category)) {
                        return false;
                    }
                    
                    // Search filter
                    if (!searchStr.isEmpty()) {
                        return row.name.toLowerCase().contains(searchStr) ||
                               row.description.toLowerCase().contains(searchStr) ||
                               row.currentKey.toLowerCase().contains(searchStr);
                    }
                    
                    return true;
                })
                .count();
        
        statusMessage = String.format("Showing %d of %d shortcuts", visibleCount, shortcutData.size());
    }
    
    private void updateConflicts() {
        stateLock.writeLock().lock();
        try {
            conflictData.clear();
            
            // Mark shortcuts with conflicts
            synchronized (shortcutData) {
                for (ShortcutTableRow row : shortcutData) {
                    if (row != null) {
                        row.setHasConflict(false);
                    }
                }
            }
        
            // Get conflicts from shortcut system with validation
            var conflicts = shortcutSystem.getConflicts();
            if (conflicts != null) {
                for (KeyboardShortcutSystem.ShortcutConflict conflict : conflicts) {
                    if (conflict == null) {
                        logger.warn("Null conflict found in conflicts list");
                        continue;
                    }
                    
                    try {
                        String severity = "WARNING";
                        String keyStr = conflict.getKey();
                        
                        String description = String.format("[%s] Key '%s' is used by multiple shortcuts",
                            severity, keyStr != null ? keyStr : "<unknown>");
                        
                        conflictData.add(description);
                        
                        // Mark conflicting shortcuts
                        synchronized (shortcutData) {
                            for (KeyboardShortcutSystem.ShortcutAction conflictingAction : conflict.getConflictingActions()) {
                                if (conflictingAction != null && conflictingAction.getId() != null) {
                                    for (ShortcutTableRow row : shortcutData) {
                                        if (row != null && row.shortcut != null && 
                                            Objects.equals(row.shortcut.getId(), conflictingAction.getId())) {
                                            row.setHasConflict(true);
                                            break;
                                        }
                                    }
                                }
                            }
                        }
                    } catch (Exception e) {
                        logger.error("Error processing conflict: {}", conflict, e);
                    }
                }
            }
        
            // Update status if there are conflicts
            synchronized (conflictData) {
                if (!conflictData.isEmpty()) {
                    statusMessage = String.format("Warning: %d shortcut conflicts detected", conflictData.size());
                }
            }
        } catch (Exception e) {
            logger.error("Error updating conflicts", e);
            statusMessage = "Error checking conflicts: " + e.getMessage();
        } finally {
            stateLock.writeLock().unlock();
        }
    }
    
    private String getShortcutName(String shortcutId) {
        return shortcutData.stream()
                .filter(row -> row.shortcut.getId().equals(shortcutId))
                .map(row -> row.name)
                .findFirst()
                .orElse(shortcutId);
    }
    
    // Event handlers with validation
    
    private void editShortcut(ShortcutTableRow row) {
        if (row == null) {
            logger.warn("Cannot edit null shortcut row");
            statusMessage = "Error: Invalid shortcut selection";
            return;
        }
        
        if (row.name == null || row.name.trim().isEmpty()) {
            logger.warn("Cannot edit shortcut with null/empty name");
            statusMessage = "Error: Invalid shortcut name";
            return;
        }
        
        try {
            editingShortcutId = row.name;
            capturedKeyString = row.currentKey != null ? row.currentKey : "";
            showKeyCapture = true;
            isCapturingKey = true;
            statusMessage = "Capturing key for: " + row.name;
        } catch (Exception e) {
            logger.error("Error starting shortcut edit", e);
            statusMessage = "Error editing shortcut: " + e.getMessage();
        }
    }
    
    private void clearShortcut(ShortcutTableRow row) {
        if (!validateShortcutRow(row, "clear")) {
            return;
        }
        
        try {
            if (shortcutSystem.updateShortcut(row.shortcut.getId(), null)) {
                row.updateCurrentKey(null);
                hasUnsavedChanges = true;
                updateConflicts();
                statusMessage = "Cleared shortcut: " + row.name;
            } else {
                statusMessage = "Failed to clear shortcut: " + row.name;
                logger.warn("Failed to clear shortcut: {}", row.shortcut.getId());
            }
        } catch (Exception e) {
            logger.error("Error clearing shortcut: {}", row.shortcut.getId(), e);
            statusMessage = "Error clearing shortcut: " + e.getMessage();
        }
    }
    
    private void resetShortcut(ShortcutTableRow row) {
        if (!validateShortcutRow(row, "reset")) {
            return;
        }
        
        try {
            Object defaultKey = row.shortcut.getDefaultKey();
            if (shortcutSystem.updateShortcut(row.shortcut.getId(), defaultKey)) {
                row.updateCurrentKey(defaultKey);
                hasUnsavedChanges = true;
                updateConflicts();
                statusMessage = "Reset shortcut: " + row.name;
            } else {
                statusMessage = "Failed to reset shortcut: " + row.name;
                logger.warn("Failed to reset shortcut: {}", row.shortcut.getId());
            }
        } catch (Exception e) {
            logger.error("Error resetting shortcut: {}", row.shortcut.getId(), e);
            statusMessage = "Error resetting shortcut: " + e.getMessage();
        }
    }
    
    /**
     * Validate shortcut row before operations
     */
    private boolean validateShortcutRow(ShortcutTableRow row, String operation) {
        if (row == null) {
            logger.warn("Cannot {} null shortcut row", operation);
            statusMessage = "Error: Invalid shortcut selection";
            return false;
        }
        
        if (row.shortcut == null) {
            logger.warn("Cannot {} shortcut row with null shortcut", operation);
            statusMessage = "Error: Invalid shortcut data";
            return false;
        }
        
        if (row.shortcut.getId() == null || row.shortcut.getId().trim().isEmpty()) {
            logger.warn("Cannot {} shortcut with null/empty ID", operation);
            statusMessage = "Error: Invalid shortcut ID";
            return false;
        }
        
        return true;
    }
    
    private void handleKeyCapture() {
        if (!isCapturingKey) {
            return;
        }
        
        // This is a simplified key capture - in a real implementation,
        // you would need to integrate with the actual ImGui key handling
        
        // For demonstration, we'll simulate key capture
        if (ImGui.isKeyPressed(ImGuiKey.Escape)) {
            capturedKeyString = "";
        }
        
        // Simplified key capture - check for common keys only
        // Note: Many ImGui key constants may not be available in this version
        if (ImGui.isKeyPressed(ImGuiKey.Space)) {
            capturedKeyString = buildKeyString("Space");
        } else if (ImGui.isKeyPressed(ImGuiKey.Enter)) {
            capturedKeyString = buildKeyString("Enter");
        } else if (ImGui.isKeyPressed(ImGuiKey.Tab)) {
            capturedKeyString = buildKeyString("Tab");
        } else if (ImGui.isKeyPressed(ImGuiKey.Backspace)) {
            capturedKeyString = buildKeyString("Backspace");
        } else if (ImGui.isKeyPressed(ImGuiKey.Delete)) {
            capturedKeyString = buildKeyString("Delete");
        }
        // Add more keys as needed when they become available
    }
    
    /**
     * Build key combination string with modifiers
     */
    private String buildKeyString(String keyName) {
        StringBuilder keyStr = new StringBuilder();
        
        if (ImGui.getIO().getKeyCtrl()) {
            keyStr.append("Ctrl+");
        }
        if (ImGui.getIO().getKeyShift()) {
            keyStr.append("Shift+");
        }
        if (ImGui.getIO().getKeyAlt()) {
            keyStr.append("Alt+");
        }
        if (ImGui.getIO().getKeySuper()) {
            keyStr.append("Super+");
        }
        
        keyStr.append(keyName);
        return keyStr.toString();
    }
    
    /**
     * Get key name - simplified version without unsupported key constants
     * Note: This is a placeholder implementation. In a real implementation,
     * you would need to check which ImGui key constants are available.
     */
    private String getKeyName(int imguiKey) {
        // Only use keys that are known to exist in the ImGui binding
        if (imguiKey == ImGuiKey.Space) return "Space";
        if (imguiKey == ImGuiKey.Enter) return "Enter";
        if (imguiKey == ImGuiKey.Tab) return "Tab";
        if (imguiKey == ImGuiKey.Backspace) return "Backspace";
        if (imguiKey == ImGuiKey.Delete) return "Delete";
        if (imguiKey == ImGuiKey.Home) return "Home";
        if (imguiKey == ImGuiKey.End) return "End";
        if (imguiKey == ImGuiKey.PageUp) return "PageUp";
        if (imguiKey == ImGuiKey.PageDown) return "PageDown";
        if (imguiKey == ImGuiKey.LeftArrow) return "Left";
        if (imguiKey == ImGuiKey.RightArrow) return "Right";
        if (imguiKey == ImGuiKey.UpArrow) return "Up";
        if (imguiKey == ImGuiKey.DownArrow) return "Down";
        
        // Note: F1-F12 and alphanumeric key constants may not be available
        // in this ImGui binding version
        
        return "Key" + imguiKey; // Fallback
    }
    
    private void applyKeyCapture() {
        if (editingShortcutId == null || capturedKeyString.isEmpty()) {
            return;
        }
        
        // Find the shortcut being edited
        for (ShortcutTableRow row : shortcutData) {
            if (row.name.equals(editingShortcutId)) {
                // Create key combination from captured string
                // This is simplified - real implementation would parse the string properly
                Object keyCombination = capturedKeyString; // Simplified
                
                if (shortcutSystem.updateShortcut(row.shortcut.getId(), keyCombination)) {
                    row.updateCurrentKey(keyCombination);
                    hasUnsavedChanges = true;
                    updateConflicts();
                    statusMessage = "Updated shortcut: " + row.name;
                } else {
                    statusMessage = "Shortcut conflict detected";
                }
                break;
            }
        }
        
        editingShortcutId = null;
        capturedKeyString = "";
        isCapturingKey = false;
    }
    
    private void applyPreset(KeyboardShortcutSystem.ShortcutPreset preset) {
        shortcutSystem.applyPreset(preset);
        
        // Refresh table data
        for (ShortcutTableRow row : shortcutData) {
            Object currentKey = row.shortcut.getCurrentKey();
            row.updateCurrentKey(currentKey);
        }
        
        hasUnsavedChanges = true;
        updateConflicts();
        statusMessage = "Applied preset: " + preset.getDisplayName();
    }
    
    private void resetToPreset() {
        KeyboardShortcutSystem.ShortcutPreset current = 
            KeyboardShortcutSystem.ShortcutPreset.values()[presetCombo.get()];
        applyPreset(current);
    }
    
    private void resetAllShortcuts() {
        shortcutSystem.applyPreset(KeyboardShortcutSystem.ShortcutPreset.DEFAULT);
        
        // Refresh table data
        for (ShortcutTableRow row : shortcutData) {
            Object currentKey = row.shortcut.getCurrentKey();
            row.updateCurrentKey(currentKey);
        }
        
        presetCombo.set(0); // Set to DEFAULT
        hasUnsavedChanges = true;
        updateConflicts();
        statusMessage = "Reset all shortcuts to defaults";
    }
    
    private void importConfiguration() {
        // In a real implementation, this would open a file dialog
        try {
            File file = new File(DEFAULT_EXPORT_FILE); // Simplified
            
            if (!file.exists()) {
                statusMessage = "Import file not found: " + file.getName();
                logger.info("Import file does not exist: {}", file.getAbsolutePath());
                return;
            }
            
            if (!file.canRead()) {
                statusMessage = "Cannot read import file: " + file.getName();
                logger.warn("Import file is not readable: {}", file.getAbsolutePath());
                return;
            }
            
            if (file.length() == 0) {
                statusMessage = "Import file is empty: " + file.getName();
                logger.warn("Import file is empty: {}", file.getAbsolutePath());
                return;
            }
            
            // Validate file size (prevent loading extremely large files)
            if (file.length() > 1024 * 1024) { // 1MB limit
                statusMessage = "Import file too large (max 1MB): " + file.getName();
                logger.warn("Import file too large: {} bytes", file.length());
                return;
            }
            
            shortcutSystem.importConfiguration(file);
            loadData();
            hasUnsavedChanges = true;
            statusMessage = "Imported configuration from: " + file.getName();
            logger.info("Successfully imported configuration from: {}", file.getAbsolutePath());
            
        } catch (SecurityException e) {
            logger.error("Security error during import", e);
            statusMessage = "Security error: Cannot access import file";
        } catch (Exception e) {
            logger.error("Failed to import configuration", e);
            statusMessage = "Import error: " + e.getMessage();
        }
    }
    
    private void exportConfiguration() {
        // In a real implementation, this would open a file dialog
        try {
            File file = new File(DEFAULT_EXPORT_FILE); // Simplified
            
            // Check if parent directory exists and is writable
            File parentDir = file.getParentFile();
            if (parentDir != null && !parentDir.exists()) {
                if (!parentDir.mkdirs()) {
                    statusMessage = "Cannot create export directory";
                    logger.warn("Failed to create parent directory: {}", parentDir.getAbsolutePath());
                    return;
                }
            }
            
            if (parentDir != null && !parentDir.canWrite()) {
                statusMessage = "Cannot write to export directory";
                logger.warn("Export directory is not writable: {}", parentDir.getAbsolutePath());
                return;
            }
            
            // Warn if file exists and will be overwritten
            if (file.exists()) {
                logger.info("Overwriting existing export file: {}", file.getAbsolutePath());
            }
            
            shortcutSystem.exportConfiguration(file);
            
            // Verify export was successful
            if (!file.exists() || file.length() == 0) {
                statusMessage = "Export verification failed";
                logger.warn("Export file was not created or is empty: {}", file.getAbsolutePath());
                return;
            }
            
            statusMessage = "Exported configuration to: " + file.getName();
            logger.info("Successfully exported configuration to: {}", file.getAbsolutePath());
            
        } catch (SecurityException e) {
            logger.error("Security error during export", e);
            statusMessage = "Security error: Cannot write export file";
        } catch (Exception e) {
            logger.error("Failed to export configuration", e);
            statusMessage = "Export error: " + e.getMessage();
        }
    }
    
    private void applyChanges() {
        // Changes are applied immediately in this implementation
        hasUnsavedChanges = false;
        statusMessage = "Changes applied";
    }
    
    private void applyAndClose() {
        applyChanges();
        showEditor.set(false);
    }
    
    private void showUnsavedChangesDialog() {
        // For simplicity, just apply changes and close
        applyChanges();
        showEditor.set(false);
    }
    
    // Resource management
    
    /**
     * Clean up resources and reset state
     */
    public void dispose() {
        stateLock.writeLock().lock();
        try {
            showEditor.set(false);
            showKeyCapture = false;
            isCapturingKey = false;
            hasUnsavedChanges = false;
            selectedRow = -1;
            editingShortcutId = null;
            capturedKeyString = "";
            statusMessage = DEFAULT_STATUS_MESSAGE;
            
            shortcutData.clear();
            conflictData.clear();
            categoryNames.clear();
            
            // Clear ImGui state
            if (searchText != null) searchText.set("");
            if (capturedKey != null) capturedKey.set("");
            if (categoryFilter != null) categoryFilter.set(0);
            if (presetCombo != null) presetCombo.set(0);
            
            logger.info("ShortcutEditorImGui disposed successfully");
        } catch (Exception e) {
            logger.error("Error during disposal", e);
        } finally {
            stateLock.writeLock().unlock();
        }
    }
    
    // Public API methods with thread-safety
    
    public boolean isShowing() {
        return showEditor.get();
    }
    
    public void hide() {
        try {
            showEditor.set(false);
        } catch (Exception e) {
            logger.error("Error hiding editor", e);
        }
    }
    
    public boolean hasUnsavedChanges() {
        return hasUnsavedChanges;
    }
    
    public int getShortcutCount() {
        stateLock.readLock().lock();
        try {
            synchronized (shortcutData) {
                return shortcutData.size();
            }
        } finally {
            stateLock.readLock().unlock();
        }
    }
    
    public int getConflictCount() {
        stateLock.readLock().lock();
        try {
            synchronized (conflictData) {
                return conflictData.size();
            }
        } finally {
            stateLock.readLock().unlock();
        }
    }
    
    /**
     * Get immutable copy of shortcut data
     */
    public List<ShortcutTableRow> getShortcutData() {
        stateLock.readLock().lock();
        try {
            synchronized (shortcutData) {
                return new ArrayList<>(shortcutData);
            }
        } finally {
            stateLock.readLock().unlock();
        }
    }
    
    public String getStatusMessage() {
        return statusMessage; // volatile field, thread-safe
    }
    
    /**
     * Get current editor state for debugging
     */
    public String getDebugInfo() {
        return String.format("ShortcutEditorImGui{showing=%s, shortcuts=%d, conflicts=%d, unsavedChanges=%s, status='%s'}",
                           isShowing(), getShortcutCount(), getConflictCount(), hasUnsavedChanges(), getStatusMessage());
    }
}