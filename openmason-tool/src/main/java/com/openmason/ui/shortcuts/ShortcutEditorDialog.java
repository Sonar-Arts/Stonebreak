package com.openmason.ui.shortcuts;

import javafx.application.Platform;
import javafx.beans.property.SimpleStringProperty;
import javafx.collections.FXCollections;
import javafx.collections.ObservableList;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Scene;
import javafx.scene.control.*;
import javafx.scene.control.cell.PropertyValueFactory;
import javafx.scene.input.KeyCode;
import javafx.scene.input.KeyCodeCombination;
import javafx.scene.input.KeyCombination;
import javafx.scene.input.KeyEvent;
import javafx.scene.layout.*;
import javafx.stage.FileChooser;
import javafx.stage.Modality;
import javafx.stage.Stage;
import javafx.stage.StageStyle;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.File;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.stream.Collectors;

/**
 * Professional keyboard shortcut editor dialog with search, categories,
 * conflict detection, and preset management.
 */
public class ShortcutEditorDialog extends Stage {
    
    private static final Logger logger = LoggerFactory.getLogger(ShortcutEditorDialog.class);
    
    private final KeyboardShortcutSystem shortcutSystem;
    
    // UI Components
    private ComboBox<KeyboardShortcutSystem.ShortcutPreset> presetCombo;
    private TextField searchField;
    private ComboBox<String> categoryFilter;
    private TableView<ShortcutTableRow> shortcutTable;
    private ListView<String> conflictsList;
    private Label statusLabel;
    private Button applyButton;
    private Button resetButton;
    private Button importButton;
    private Button exportButton;
    
    // Data
    private ObservableList<ShortcutTableRow> shortcutData;
    private ObservableList<String> conflictData;
    private boolean hasUnsavedChanges = false;
    
    /**
     * Table row representation of a shortcut
     */
    public static class ShortcutTableRow {
        private final KeyboardShortcutSystem.ShortcutAction shortcut;
        private final SimpleStringProperty name;
        private final SimpleStringProperty category;
        private final SimpleStringProperty currentKey;
        private final SimpleStringProperty defaultKey;
        private final SimpleStringProperty description;
        
        public ShortcutTableRow(KeyboardShortcutSystem.ShortcutAction shortcut, String categoryName) {
            this.shortcut = shortcut;
            this.name = new SimpleStringProperty(shortcut.getName());
            this.category = new SimpleStringProperty(categoryName);
            this.currentKey = new SimpleStringProperty(keyToString(shortcut.getCurrentKey()));
            this.defaultKey = new SimpleStringProperty(keyToString(shortcut.getDefaultKey()));
            this.description = new SimpleStringProperty(shortcut.getDescription());
        }
        
        private static String keyToString(KeyCombination key) {
            return key != null ? key.getDisplayText() : "";
        }
        
        // Getters for TableView
        public String getName() { return name.get(); }
        public String getCategory() { return category.get(); }
        public String getCurrentKey() { return currentKey.get(); }
        public String getDefaultKey() { return defaultKey.get(); }
        public String getDescription() { return description.get(); }
        
        public SimpleStringProperty nameProperty() { return name; }
        public SimpleStringProperty categoryProperty() { return category; }
        public SimpleStringProperty currentKeyProperty() { return currentKey; }
        public SimpleStringProperty defaultKeyProperty() { return defaultKey; }
        public SimpleStringProperty descriptionProperty() { return description; }
        
        public KeyboardShortcutSystem.ShortcutAction getShortcut() { return shortcut; }
        
        public void updateCurrentKey(KeyCombination newKey) {
            currentKey.set(keyToString(newKey));
        }
    }
    
    public ShortcutEditorDialog(Stage parent) {
        this.shortcutSystem = KeyboardShortcutSystem.getInstance();
        
        initOwner(parent);
        initModality(Modality.APPLICATION_MODAL);
        initStyle(StageStyle.DECORATED);
        
        setTitle("Keyboard Shortcuts - OpenMason");
        setWidth(900);
        setHeight(700);
        setMinWidth(800);
        setMinHeight(600);
        
        initializeUI();
        loadData();
        setupEventHandlers();
        
        // Center on parent
        if (parent != null) {
            setX(parent.getX() + (parent.getWidth() - getWidth()) / 2);
            setY(parent.getY() + (parent.getHeight() - getHeight()) / 2);
        }
    }
    
    private void initializeUI() {
        VBox root = new VBox(10);
        root.setPadding(new Insets(15));
        root.getStyleClass().add("shortcut-editor-dialog");
        
        // Header section
        root.getChildren().add(createHeaderSection());
        
        // Main content in split pane
        SplitPane mainSplit = new SplitPane();
        mainSplit.setOrientation(javafx.geometry.Orientation.VERTICAL);
        mainSplit.setDividerPositions(0.75);
        
        // Shortcuts table section
        mainSplit.getItems().add(createShortcutsSection());
        
        // Conflicts section
        mainSplit.getItems().add(createConflictsSection());
        
        root.getChildren().add(mainSplit);
        VBox.setVgrow(mainSplit, Priority.ALWAYS);
        
        // Footer section
        root.getChildren().add(createFooterSection());
        
        Scene scene = new Scene(root);
        scene.getStylesheets().add(getClass().getResource("/css/dark-theme.css").toExternalForm());
        setScene(scene);
    }
    
    private VBox createHeaderSection() {
        VBox header = new VBox(10);
        header.getStyleClass().add("dialog-header");
        
        // Title and description
        Label titleLabel = new Label("Keyboard Shortcuts");
        titleLabel.getStyleClass().addAll("dialog-title", "h2");
        
        Label descLabel = new Label("Customize keyboard shortcuts for OpenMason. " +
                                   "Choose a preset or create your own custom configuration.");
        descLabel.getStyleClass().add("dialog-description");
        descLabel.setWrapText(true);
        
        // Preset selection
        HBox presetBox = new HBox(10);
        presetBox.setAlignment(Pos.CENTER_LEFT);
        
        Label presetLabel = new Label("Preset:");
        presetLabel.getStyleClass().add("form-label");
        
        presetCombo = new ComboBox<>();
        presetCombo.getItems().addAll(KeyboardShortcutSystem.ShortcutPreset.values());
        presetCombo.setValue(shortcutSystem.getCurrentPreset());
        presetCombo.getStyleClass().add("preset-combo");
        
        Button resetPresetButton = new Button("Reset to Preset");
        resetPresetButton.getStyleClass().addAll("secondary-button", "small-button");
        resetPresetButton.setOnAction(e -> resetToPreset());
        
        presetBox.getChildren().addAll(presetLabel, presetCombo, resetPresetButton);
        
        // Search and filter
        HBox filterBox = new HBox(10);
        filterBox.setAlignment(Pos.CENTER_LEFT);
        
        Label searchLabel = new Label("Search:");
        searchLabel.getStyleClass().add("form-label");
        
        searchField = new TextField();
        searchField.setPromptText("Search shortcuts...");
        searchField.getStyleClass().add("search-field");
        HBox.setHgrow(searchField, Priority.ALWAYS);
        
        Label categoryLabel = new Label("Category:");
        categoryLabel.getStyleClass().add("form-label");
        
        categoryFilter = new ComboBox<>();
        categoryFilter.getStyleClass().add("category-filter");
        
        filterBox.getChildren().addAll(searchLabel, searchField, categoryLabel, categoryFilter);
        
        header.getChildren().addAll(titleLabel, descLabel, presetBox, filterBox);
        return header;
    }
    
    private VBox createShortcutsSection() {
        VBox section = new VBox(5);
        section.getStyleClass().add("shortcuts-section");
        
        Label sectionLabel = new Label("Shortcuts");
        sectionLabel.getStyleClass().addAll("section-title", "h3");
        
        // Create shortcuts table
        shortcutTable = new TableView<>();
        shortcutTable.getStyleClass().add("shortcuts-table");
        shortcutTable.setRowFactory(tv -> {
            TableRow<ShortcutTableRow> row = new TableRow<>();
            row.setOnMouseClicked(event -> {
                if (event.getClickCount() == 2 && !row.isEmpty()) {
                    editShortcut(row.getItem());
                }
            });
            return row;
        });
        
        // Configure columns
        TableColumn<ShortcutTableRow, String> nameCol = new TableColumn<>("Command");
        nameCol.setCellValueFactory(new PropertyValueFactory<>("name"));
        nameCol.setPrefWidth(200);
        
        TableColumn<ShortcutTableRow, String> categoryCol = new TableColumn<>("Category");
        categoryCol.setCellValueFactory(new PropertyValueFactory<>("category"));
        categoryCol.setPrefWidth(120);
        
        TableColumn<ShortcutTableRow, String> currentKeyCol = new TableColumn<>("Current Key");
        currentKeyCol.setCellValueFactory(new PropertyValueFactory<>("currentKey"));
        currentKeyCol.setPrefWidth(150);
        
        TableColumn<ShortcutTableRow, String> defaultKeyCol = new TableColumn<>("Default Key");
        defaultKeyCol.setCellValueFactory(new PropertyValueFactory<>("defaultKey"));
        defaultKeyCol.setPrefWidth(150);
        
        TableColumn<ShortcutTableRow, String> descCol = new TableColumn<>("Description");
        descCol.setCellValueFactory(new PropertyValueFactory<>("description"));
        descCol.setPrefWidth(250);
        
        shortcutTable.getColumns().addAll(nameCol, categoryCol, currentKeyCol, defaultKeyCol, descCol);
        
        // Action buttons
        HBox actionBox = new HBox(10);
        actionBox.setAlignment(Pos.CENTER_LEFT);
        
        Button editButton = new Button("Edit Shortcut");
        editButton.getStyleClass().addAll("primary-button", "small-button");
        editButton.setOnAction(e -> editSelectedShortcut());
        
        Button clearButton = new Button("Clear");
        clearButton.getStyleClass().addAll("secondary-button", "small-button");
        clearButton.setOnAction(e -> clearSelectedShortcut());
        
        Button resetSingleButton = new Button("Reset to Default");
        resetSingleButton.getStyleClass().addAll("secondary-button", "small-button");
        resetSingleButton.setOnAction(e -> resetSelectedShortcut());
        
        actionBox.getChildren().addAll(editButton, clearButton, resetSingleButton);
        
        section.getChildren().addAll(sectionLabel, shortcutTable, actionBox);
        VBox.setVgrow(shortcutTable, Priority.ALWAYS);
        
        return section;
    }
    
    private VBox createConflictsSection() {
        VBox section = new VBox(5);
        section.getStyleClass().add("conflicts-section");
        
        Label sectionLabel = new Label("Conflicts");
        sectionLabel.getStyleClass().addAll("section-title", "h3");
        
        conflictsList = new ListView<>();
        conflictsList.getStyleClass().add("conflicts-list");
        conflictsList.setPrefHeight(120);
        
        section.getChildren().addAll(sectionLabel, conflictsList);
        return section;
    }
    
    private HBox createFooterSection() {
        HBox footer = new HBox(10);
        footer.setAlignment(Pos.CENTER_RIGHT);
        footer.getStyleClass().add("dialog-footer");
        
        // Status label (left-aligned)
        statusLabel = new Label("Ready");
        statusLabel.getStyleClass().add("status-label");
        HBox.setHgrow(statusLabel, Priority.ALWAYS);
        
        // Import/Export buttons
        importButton = new Button("Import...");
        importButton.getStyleClass().addAll("secondary-button");
        importButton.setOnAction(e -> importConfiguration());
        
        exportButton = new Button("Export...");
        exportButton.getStyleClass().addAll("secondary-button");
        exportButton.setOnAction(e -> exportConfiguration());
        
        // Main action buttons
        resetButton = new Button("Reset All");
        resetButton.getStyleClass().addAll("warning-button");
        resetButton.setOnAction(e -> resetAllShortcuts());
        
        applyButton = new Button("Apply");
        applyButton.getStyleClass().addAll("primary-button");
        applyButton.setOnAction(e -> applyChanges());
        
        Button cancelButton = new Button("Cancel");
        cancelButton.getStyleClass().addAll("secondary-button");
        cancelButton.setOnAction(e -> close());
        
        Button okButton = new Button("OK");
        okButton.getStyleClass().addAll("primary-button");
        okButton.setOnAction(e -> applyAndClose());
        
        footer.getChildren().addAll(statusLabel, importButton, exportButton, 
                                   resetButton, applyButton, cancelButton, okButton);
        
        return footer;
    }
    
    private void loadData() {
        // Load shortcuts data
        shortcutData = FXCollections.observableArrayList();
        
        for (KeyboardShortcutSystem.ShortcutAction shortcut : shortcutSystem.getAllShortcuts()) {
            String categoryName = shortcutSystem.getAllCategories().stream()
                    .filter(cat -> cat.getId().equals(shortcut.getCategoryId()))
                    .map(KeyboardShortcutSystem.ShortcutCategory::getName)
                    .findFirst()
                    .orElse("Unknown");
            
            shortcutData.add(new ShortcutTableRow(shortcut, categoryName));
        }
        
        shortcutTable.setItems(shortcutData);
        
        // Load category filter
        List<String> categories = new ArrayList<>();
        categories.add("All Categories");
        categories.addAll(shortcutSystem.getAllCategories().stream()
                .map(KeyboardShortcutSystem.ShortcutCategory::getName)
                .sorted()
                .collect(Collectors.toList()));
        
        categoryFilter.getItems().setAll(categories);
        categoryFilter.setValue("All Categories");
        
        // Load conflicts
        updateConflictsList();
    }
    
    private void setupEventHandlers() {
        // Preset change handler
        presetCombo.setOnAction(e -> {
            KeyboardShortcutSystem.ShortcutPreset selected = presetCombo.getValue();
            if (selected != null && selected != shortcutSystem.getCurrentPreset()) {
                showConfirmation("Apply Preset", 
                    "Applying preset '" + selected.getDisplayName() + "' will replace current shortcuts. Continue?",
                    () -> applyPreset(selected));
            }
        });
        
        // Search and filter handlers
        searchField.textProperty().addListener((obs, oldText, newText) -> filterShortcuts());
        categoryFilter.setOnAction(e -> filterShortcuts());
        
        // Table selection handler
        shortcutTable.getSelectionModel().selectedItemProperty().addListener(
            (obs, oldSelection, newSelection) -> updateButtonStates());
        
        // Close confirmation
        setOnCloseRequest(e -> {
            if (hasUnsavedChanges) {
                e.consume();
                showUnsavedChangesDialog();
            }
        });
    }
    
    private void filterShortcuts() {
        String searchText = searchField.getText().toLowerCase();
        String selectedCategory = categoryFilter.getValue();
        
        ObservableList<ShortcutTableRow> filteredData = shortcutData.stream()
                .filter(row -> {
                    // Category filter
                    if (!"All Categories".equals(selectedCategory) && 
                        !selectedCategory.equals(row.getCategory())) {
                        return false;
                    }
                    
                    // Search filter
                    if (!searchText.isEmpty()) {
                        return row.getName().toLowerCase().contains(searchText) ||
                               row.getDescription().toLowerCase().contains(searchText) ||
                               row.getCurrentKey().toLowerCase().contains(searchText);
                    }
                    
                    return true;
                })
                .collect(Collectors.toCollection(FXCollections::observableArrayList));
        
        shortcutTable.setItems(filteredData);
        
        statusLabel.setText(String.format("Showing %d of %d shortcuts", 
                                         filteredData.size(), shortcutData.size()));
    }
    
    private void editSelectedShortcut() {
        ShortcutTableRow selected = shortcutTable.getSelectionModel().getSelectedItem();
        if (selected != null) {
            editShortcut(selected);
        }
    }
    
    private void editShortcut(ShortcutTableRow row) {
        KeyCombination currentKey = row.getShortcut().getCurrentKey();
        
        Optional<KeyCombination> result = showKeyInputDialog(
            "Edit Shortcut - " + row.getName(),
            "Press new key combination for '" + row.getName() + "':",
            currentKey
        );
        
        if (result.isPresent()) {
            KeyCombination newKey = result.get();
            if (shortcutSystem.updateShortcut(row.getShortcut().getId(), newKey)) {
                row.updateCurrentKey(newKey);
                hasUnsavedChanges = true;
                updateConflictsList();
                statusLabel.setText("Shortcut updated: " + row.getName());
            } else {
                showError("Shortcut Conflict", 
                         "The key combination is already in use by another shortcut.");
            }
        }
    }
    
    private void clearSelectedShortcut() {
        ShortcutTableRow selected = shortcutTable.getSelectionModel().getSelectedItem();
        if (selected != null) {
            if (shortcutSystem.updateShortcut(selected.getShortcut().getId(), null)) {
                selected.updateCurrentKey(null);
                hasUnsavedChanges = true;
                updateConflictsList();
                statusLabel.setText("Cleared shortcut: " + selected.getName());
            }
        }
    }
    
    private void resetSelectedShortcut() {
        ShortcutTableRow selected = shortcutTable.getSelectionModel().getSelectedItem();
        if (selected != null) {
            KeyCombination defaultKey = selected.getShortcut().getDefaultKey();
            if (shortcutSystem.updateShortcut(selected.getShortcut().getId(), defaultKey)) {
                selected.updateCurrentKey(defaultKey);
                hasUnsavedChanges = true;
                updateConflictsList();
                statusLabel.setText("Reset shortcut: " + selected.getName());
            }
        }
    }
    
    private void resetToPreset() {
        KeyboardShortcutSystem.ShortcutPreset current = presetCombo.getValue();
        if (current != null) {
            showConfirmation("Reset to Preset",
                "Reset all shortcuts to " + current.getDisplayName() + " preset?",
                () -> applyPreset(current));
        }
    }
    
    private void applyPreset(KeyboardShortcutSystem.ShortcutPreset preset) {
        shortcutSystem.applyPreset(preset);
        
        // Refresh table data
        for (ShortcutTableRow row : shortcutData) {
            KeyCombination currentKey = row.getShortcut().getCurrentKey();
            row.updateCurrentKey(currentKey);
        }
        
        hasUnsavedChanges = true;
        updateConflictsList();
        statusLabel.setText("Applied preset: " + preset.getDisplayName());
    }
    
    private void resetAllShortcuts() {
        showConfirmation("Reset All Shortcuts",
            "Reset all shortcuts to their default values?",
            () -> {
                shortcutSystem.applyPreset(KeyboardShortcutSystem.ShortcutPreset.DEFAULT);
                
                // Refresh table data
                for (ShortcutTableRow row : shortcutData) {
                    KeyCombination currentKey = row.getShortcut().getCurrentKey();
                    row.updateCurrentKey(currentKey);
                }
                
                presetCombo.setValue(KeyboardShortcutSystem.ShortcutPreset.DEFAULT);
                hasUnsavedChanges = true;
                updateConflictsList();
                statusLabel.setText("Reset all shortcuts to defaults");
            });
    }
    
    private void updateConflictsList() {
        conflictData = FXCollections.observableArrayList();
        
        for (KeyboardShortcutSystem.ShortcutConflict conflict : shortcutSystem.getConflicts()) {
            String severity = conflict.getSeverity().name();
            String description = String.format("[%s] %s conflicts with %s on key: %s",
                severity,
                getShortcutName(conflict.getShortcut1Id()),
                getShortcutName(conflict.getShortcut2Id()),
                conflict.getKeyCombo().getDisplayText());
            
            conflictData.add(description);
        }
        
        conflictsList.setItems(conflictData);
        
        // Update status
        if (!conflictData.isEmpty()) {
            statusLabel.setText(String.format("Warning: %d shortcut conflicts detected", 
                                             conflictData.size()));
        }
    }
    
    private String getShortcutName(String shortcutId) {
        return shortcutData.stream()
                .filter(row -> row.getShortcut().getId().equals(shortcutId))
                .map(ShortcutTableRow::getName)
                .findFirst()
                .orElse(shortcutId);
    }
    
    private void updateButtonStates() {
        boolean hasSelection = shortcutTable.getSelectionModel().getSelectedItem() != null;
        // Update button enabled states based on selection
    }
    
    private Optional<KeyCombination> showKeyInputDialog(String title, String message, 
                                                       KeyCombination currentKey) {
        Dialog<KeyCombination> dialog = new Dialog<>();
        dialog.setTitle(title);
        dialog.setHeaderText(message);
        dialog.initOwner(this);
        dialog.initModality(Modality.WINDOW_MODAL);
        
        // Create dialog content
        VBox content = new VBox(10);
        content.setPadding(new Insets(20));
        
        Label currentLabel = new Label("Current: " + 
            (currentKey != null ? currentKey.getDisplayText() : "None"));
        currentLabel.getStyleClass().add("current-key-label");
        
        TextField keyField = new TextField();
        keyField.setPromptText("Press key combination...");
        keyField.setEditable(false);
        keyField.getStyleClass().add("key-input-field");
        
        Label instructionLabel = new Label("Press the desired key combination, then click OK.");
        instructionLabel.getStyleClass().add("instruction-label");
        instructionLabel.setWrapText(true);
        
        content.getChildren().addAll(currentLabel, keyField, instructionLabel);
        
        dialog.getDialogPane().setContent(content);
        dialog.getDialogPane().getButtonTypes().addAll(ButtonType.OK, ButtonType.CANCEL);
        
        final KeyCombination[] capturedKey = {null};
        
        // Key capture handler
        keyField.setOnKeyPressed(event -> {
            event.consume();
            
            KeyCode code = event.getCode();
            if (code == KeyCode.ESCAPE) {
                capturedKey[0] = null;
                keyField.setText("None");
                return;
            }
            
            // Build key combination
            List<KeyCombination.Modifier> modifiers = new ArrayList<>();
            if (event.isControlDown()) modifiers.add(KeyCombination.CONTROL_DOWN);
            if (event.isShiftDown()) modifiers.add(KeyCombination.SHIFT_DOWN);
            if (event.isAltDown()) modifiers.add(KeyCombination.ALT_DOWN);
            if (event.isMetaDown()) modifiers.add(KeyCombination.META_DOWN);
            
            if (code != KeyCode.CONTROL && code != KeyCode.SHIFT && 
                code != KeyCode.ALT && code != KeyCode.META) {
                
                KeyCodeCombination combination = new KeyCodeCombination(code, 
                    modifiers.toArray(new KeyCombination.Modifier[0]));
                
                capturedKey[0] = combination;
                keyField.setText(combination.getDisplayText());
            }
        });
        
        // Focus the key field
        Platform.runLater(keyField::requestFocus);
        
        dialog.setResultConverter(buttonType -> {
            if (buttonType == ButtonType.OK) {
                return capturedKey[0];
            }
            return null;
        });
        
        return dialog.showAndWait();
    }
    
    private void importConfiguration() {
        FileChooser fileChooser = new FileChooser();
        fileChooser.setTitle("Import Shortcuts Configuration");
        fileChooser.getExtensionFilters().add(
            new FileChooser.ExtensionFilter("Properties Files", "*.properties"));
        
        File file = fileChooser.showOpenDialog(this);
        if (file != null) {
            try {
                shortcutSystem.importConfiguration(file);
                
                // Refresh data
                loadData();
                hasUnsavedChanges = true;
                statusLabel.setText("Imported configuration from: " + file.getName());
                
            } catch (Exception e) {
                logger.error("Failed to import configuration", e);
                showError("Import Error", "Failed to import configuration: " + e.getMessage());
            }
        }
    }
    
    private void exportConfiguration() {
        FileChooser fileChooser = new FileChooser();
        fileChooser.setTitle("Export Shortcuts Configuration");
        fileChooser.setInitialFileName("openmason-shortcuts.properties");
        fileChooser.getExtensionFilters().add(
            new FileChooser.ExtensionFilter("Properties Files", "*.properties"));
        
        File file = fileChooser.showSaveDialog(this);
        if (file != null) {
            try {
                shortcutSystem.exportConfiguration(file);
                statusLabel.setText("Exported configuration to: " + file.getName());
                
            } catch (Exception e) {
                logger.error("Failed to export configuration", e);
                showError("Export Error", "Failed to export configuration: " + e.getMessage());
            }
        }
    }
    
    private void applyChanges() {
        // Changes are applied immediately in this implementation
        hasUnsavedChanges = false;
        statusLabel.setText("Changes applied");
    }
    
    private void applyAndClose() {
        applyChanges();
        close();
    }
    
    private void showUnsavedChangesDialog() {
        Alert alert = new Alert(Alert.AlertType.CONFIRMATION);
        alert.setTitle("Unsaved Changes");
        alert.setHeaderText("You have unsaved changes");
        alert.setContentText("Do you want to apply your changes before closing?");
        
        alert.getButtonTypes().setAll(ButtonType.YES, ButtonType.NO, ButtonType.CANCEL);
        
        Optional<ButtonType> result = alert.showAndWait();
        if (result.isPresent()) {
            switch (result.get().getButtonData()) {
                case YES:
                    applyChanges();
                    close();
                    break;
                case NO:
                    close();
                    break;
                case CANCEL_CLOSE:
                default:
                    // Do nothing - keep dialog open
                    break;
            }
        }
    }
    
    private void showConfirmation(String title, String message, Runnable action) {
        Alert alert = new Alert(Alert.AlertType.CONFIRMATION);
        alert.setTitle(title);
        alert.setHeaderText(null);
        alert.setContentText(message);
        
        Optional<ButtonType> result = alert.showAndWait();
        if (result.isPresent() && result.get() == ButtonType.OK) {
            action.run();
        }
    }
    
    private void showError(String title, String message) {
        Alert alert = new Alert(Alert.AlertType.ERROR);
        alert.setTitle(title);
        alert.setHeaderText(null);
        alert.setContentText(message);
        alert.showAndWait();
    }
}