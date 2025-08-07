package com.openmason.ui.themes;

import javafx.application.Platform;
import javafx.beans.binding.Bindings;
import javafx.collections.FXCollections;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Scene;
import javafx.scene.control.*;
import javafx.scene.layout.*;
import javafx.scene.paint.Color;
import javafx.stage.FileChooser;
import javafx.stage.Modality;
import javafx.stage.Stage;
import javafx.stage.StageStyle;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.File;
import java.util.Arrays;
import java.util.HashMap;
import java.util.stream.Collectors;
import java.util.Map;
import java.util.Optional;

/**
 * Professional theme customization dialog with real-time preview,
 * color picker, and custom theme creation capabilities.
 */
public class ThemeCustomizationDialog extends Stage {
    
    private static final Logger logger = LoggerFactory.getLogger(ThemeCustomizationDialog.class);
    
    private final ThemeManager themeManager;
    
    // UI Components
    private ComboBox<ThemeManager.Theme> themeCombo;
    private ComboBox<ThemeManager.UIDensity> densityCombo;
    private VBox colorCustomizationPanel;
    private Map<String, ColorPicker> colorPickers;
    private CheckBox previewModeCheck;
    private Button createCustomButton;
    private Button resetButton;
    private Button importButton;
    private Button exportButton;
    private Label statusLabel;
    
    // State
    private ThemeManager.Theme workingTheme;
    private boolean isCustomizing = false;
    
    public ThemeCustomizationDialog(Stage parent) {
        this.themeManager = ThemeManager.getInstance();
        this.colorPickers = new HashMap<>();
        
        initOwner(parent);
        initModality(Modality.APPLICATION_MODAL);
        initStyle(StageStyle.DECORATED);
        
        setTitle("Theme Customization - OpenMason");
        setWidth(800);
        setHeight(600);
        setMinWidth(700);
        setMinHeight(500);
        
        initializeUI();
        setupEventHandlers();
        loadCurrentConfiguration();
        
        // Center on parent
        if (parent != null) {
            setX(parent.getX() + (parent.getWidth() - getWidth()) / 2);
            setY(parent.getY() + (parent.getHeight() - getHeight()) / 2);
        }
    }
    
    private void initializeUI() {
        VBox root = new VBox(15);
        root.setPadding(new Insets(20));
        root.getStyleClass().add("theme-customization-dialog");
        
        // Header section
        root.getChildren().add(createHeaderSection());
        
        // Main content in tabs
        TabPane mainTabs = new TabPane();
        mainTabs.setTabClosingPolicy(TabPane.TabClosingPolicy.UNAVAILABLE);
        mainTabs.getStyleClass().add("main-tabs");
        
        // Theme selection tab
        Tab themeTab = new Tab("Themes", createThemeSelectionSection());
        themeTab.getStyleClass().add("theme-tab");
        
        // Color customization tab
        Tab colorTab = new Tab("Colors", createColorCustomizationSection());
        colorTab.getStyleClass().add("color-tab");
        
        // Preview tab
        Tab previewTab = new Tab("Preview", createPreviewSection());
        previewTab.getStyleClass().add("preview-tab");
        
        mainTabs.getTabs().addAll(themeTab, colorTab, previewTab);
        
        root.getChildren().add(mainTabs);
        VBox.setVgrow(mainTabs, Priority.ALWAYS);
        
        // Footer section
        root.getChildren().add(createFooterSection());
        
        Scene scene = new Scene(root);
        scene.getStylesheets().add(getClass().getResource("/css/dark-theme.css").toExternalForm());
        setScene(scene);
        
        // Register this scene with theme manager
        themeManager.registerScene(scene);
    }
    
    private VBox createHeaderSection() {
        VBox header = new VBox(10);
        header.getStyleClass().add("dialog-header");
        
        Label titleLabel = new Label("Theme Customization");
        titleLabel.getStyleClass().addAll("dialog-title", "h2");
        
        Label descLabel = new Label("Customize the appearance of OpenMason with built-in themes or create your own custom theme.");
        descLabel.getStyleClass().add("dialog-description");
        descLabel.setWrapText(true);
        
        // Preview mode toggle
        previewModeCheck = new CheckBox("Live Preview");
        previewModeCheck.getStyleClass().add("preview-mode-check");
        previewModeCheck.setTooltip(new Tooltip("Apply changes immediately for preview"));
        
        header.getChildren().addAll(titleLabel, descLabel, previewModeCheck);
        return header;
    }
    
    private ScrollPane createThemeSelectionSection() {
        VBox content = new VBox(15);
        content.setPadding(new Insets(15));
        content.getStyleClass().add("theme-selection-content");
        
        // Theme selection
        VBox themeBox = new VBox(8);
        themeBox.getStyleClass().add("theme-selection-box");
        
        Label themeLabel = new Label("Current Theme");
        themeLabel.getStyleClass().addAll("section-title", "h3");
        
        themeCombo = new ComboBox<>();
        themeCombo.setItems(themeManager.getAvailableThemes());
        themeCombo.setValue(themeManager.getCurrentTheme());
        themeCombo.getStyleClass().add("theme-combo");
        themeCombo.setMaxWidth(Double.MAX_VALUE);
        
        // Theme description
        Label themeDescLabel = new Label();
        themeDescLabel.getStyleClass().add("theme-description");
        themeDescLabel.setWrapText(true);
        themeDescLabel.textProperty().bind(Bindings.createStringBinding(() -> {
            ThemeManager.Theme selected = themeCombo.getValue();
            return selected != null ? selected.getDescription() : "";
        }, themeCombo.valueProperty()));
        
        themeBox.getChildren().addAll(themeLabel, themeCombo, themeDescLabel);
        
        // UI Density selection
        VBox densityBox = new VBox(8);
        densityBox.getStyleClass().add("density-selection-box");
        
        Label densityLabel = new Label("UI Density");
        densityLabel.getStyleClass().addAll("section-title", "h3");
        
        densityCombo = new ComboBox<>();
        densityCombo.getItems().setAll(ThemeManager.UIDensity.values());
        densityCombo.setValue(themeManager.getCurrentDensity());
        densityCombo.getStyleClass().add("density-combo");
        densityCombo.setMaxWidth(Double.MAX_VALUE);
        
        // Density description
        Label densityDescLabel = new Label();
        densityDescLabel.getStyleClass().add("density-description");
        densityDescLabel.setWrapText(true);
        densityDescLabel.textProperty().bind(Bindings.createStringBinding(() -> {
            ThemeManager.UIDensity selected = densityCombo.getValue();
            return selected != null ? selected.getDescription() : "";
        }, densityCombo.valueProperty()));
        
        densityBox.getChildren().addAll(densityLabel, densityCombo, densityDescLabel);
        
        // Theme management buttons
        HBox themeButtonsBox = new HBox(10);
        themeButtonsBox.setAlignment(Pos.CENTER_LEFT);
        themeButtonsBox.getStyleClass().add("theme-buttons-box");
        
        createCustomButton = new Button("Create Custom Theme");
        createCustomButton.getStyleClass().addAll("primary-button");
        createCustomButton.setOnAction(e -> createCustomTheme());
        
        Button deleteCustomButton = new Button("Delete Custom Theme");
        deleteCustomButton.getStyleClass().addAll("warning-button");
        deleteCustomButton.disableProperty().bind(Bindings.createBooleanBinding(() -> {
            ThemeManager.Theme selected = themeCombo.getValue();
            return selected == null || selected.getType() != ThemeManager.ThemeType.USER_CUSTOM;
        }, themeCombo.valueProperty()));
        deleteCustomButton.setOnAction(e -> deleteCustomTheme());
        
        themeButtonsBox.getChildren().addAll(createCustomButton, deleteCustomButton);
        
        content.getChildren().addAll(themeBox, densityBox, themeButtonsBox);
        
        ScrollPane scrollPane = new ScrollPane(content);
        scrollPane.setFitToWidth(true);
        scrollPane.getStyleClass().add("theme-selection-scroll");
        
        return scrollPane;
    }
    
    private ScrollPane createColorCustomizationSection() {
        colorCustomizationPanel = new VBox(10);
        colorCustomizationPanel.setPadding(new Insets(15));
        colorCustomizationPanel.getStyleClass().add("color-customization-panel");
        
        Label instructionLabel = new Label("Customize theme colors. Changes are applied in real-time when preview mode is enabled.");
        instructionLabel.getStyleClass().add("instruction-label");
        instructionLabel.setWrapText(true);
        
        colorCustomizationPanel.getChildren().add(instructionLabel);
        
        // Color pickers will be populated when a theme is selected
        updateColorCustomizationPanel();
        
        ScrollPane scrollPane = new ScrollPane(colorCustomizationPanel);
        scrollPane.setFitToWidth(true);
        scrollPane.getStyleClass().add("color-customization-scroll");
        
        return scrollPane;
    }
    
    private VBox createPreviewSection() {
        VBox content = new VBox(15);
        content.setPadding(new Insets(15));
        content.getStyleClass().add("preview-content");
        
        Label previewLabel = new Label("Theme Preview");
        previewLabel.getStyleClass().addAll("section-title", "h3");
        
        // Preview sample UI elements
        VBox previewSamples = new VBox(10);
        previewSamples.getStyleClass().add("preview-samples");
        
        // Sample buttons
        HBox buttonsBox = new HBox(10);
        buttonsBox.setAlignment(Pos.CENTER_LEFT);
        
        Button primaryButton = new Button("Primary Button");
        primaryButton.getStyleClass().addAll("primary-button");
        
        Button secondaryButton = new Button("Secondary Button");
        secondaryButton.getStyleClass().addAll("secondary-button");
        
        Button warningButton = new Button("Warning Button");
        warningButton.getStyleClass().addAll("warning-button");
        
        buttonsBox.getChildren().addAll(primaryButton, secondaryButton, warningButton);
        
        // Sample form elements
        VBox formBox = new VBox(8);
        formBox.getStyleClass().add("preview-form");
        
        TextField textField = new TextField("Sample text field");
        textField.getStyleClass().add("preview-text-field");
        
        ComboBox<String> comboBox = new ComboBox<>();
        comboBox.getItems().addAll("Option 1", "Option 2", "Option 3");
        comboBox.setValue("Option 1");
        comboBox.getStyleClass().add("preview-combo");
        
        CheckBox checkBox = new CheckBox("Sample checkbox");
        checkBox.getStyleClass().add("preview-checkbox");
        
        Slider slider = new Slider(0, 100, 50);
        slider.getStyleClass().add("preview-slider");
        
        formBox.getChildren().addAll(
            new Label("Form Elements:"),
            textField, comboBox, checkBox, slider
        );
        
        // Sample list
        ListView<String> listView = new ListView<>();
        listView.getItems().addAll("List Item 1", "List Item 2", "List Item 3");
        listView.setPrefHeight(100);
        listView.getStyleClass().add("preview-list");
        
        previewSamples.getChildren().addAll(
            new Label("Buttons:"),
            buttonsBox,
            formBox,
            new Label("List View:"),
            listView
        );
        
        content.getChildren().addAll(previewLabel, previewSamples);
        return content;
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
        importButton = new Button("Import Theme...");
        importButton.getStyleClass().addAll("secondary-button");
        importButton.setOnAction(e -> importTheme());
        
        exportButton = new Button("Export Theme...");
        exportButton.getStyleClass().addAll("secondary-button");
        exportButton.setOnAction(e -> exportTheme());
        
        // Reset button
        resetButton = new Button("Reset");
        resetButton.getStyleClass().addAll("warning-button");
        resetButton.setOnAction(e -> resetTheme());
        
        // Apply and close buttons
        Button applyButton = new Button("Apply");
        applyButton.getStyleClass().addAll("primary-button");
        applyButton.setOnAction(e -> applyChanges());
        
        Button cancelButton = new Button("Cancel");
        cancelButton.getStyleClass().addAll("secondary-button");
        cancelButton.setOnAction(e -> cancelChanges());
        
        Button okButton = new Button("OK");
        okButton.getStyleClass().addAll("primary-button");
        okButton.setOnAction(e -> applyAndClose());
        
        footer.getChildren().addAll(statusLabel, importButton, exportButton, 
                                   resetButton, applyButton, cancelButton, okButton);
        
        return footer;
    }
    
    private void setupEventHandlers() {
        // Theme selection change
        themeCombo.setOnAction(e -> {
            ThemeManager.Theme selected = themeCombo.getValue();
            if (selected != null) {
                selectTheme(selected);
            }
        });
        
        // UI density change
        densityCombo.setOnAction(e -> {
            ThemeManager.UIDensity selected = densityCombo.getValue();
            if (selected != null) {
                themeManager.setUIDensity(selected);
                statusLabel.setText("Changed UI density to: " + selected.getDisplayName());
            }
        });
        
        // Preview mode toggle
        previewModeCheck.setOnAction(e -> {
            boolean previewMode = previewModeCheck.isSelected();
            if (previewMode) {
                themeManager.enterPreviewMode();
                statusLabel.setText("Preview mode enabled");
            } else {
                themeManager.exitPreviewMode();
                statusLabel.setText("Preview mode disabled");
            }
        });
        
        // Close confirmation
        setOnCloseRequest(e -> {
            if (themeManager.isPreviewMode()) {
                e.consume();
                showExitConfirmation();
            }
        });
    }
    
    private void loadCurrentConfiguration() {
        themeCombo.setValue(themeManager.getCurrentTheme());
        densityCombo.setValue(themeManager.getCurrentDensity());
        previewModeCheck.setSelected(themeManager.isPreviewMode());
        workingTheme = themeManager.getCurrentTheme();
        updateColorCustomizationPanel();
    }
    
    private void selectTheme(ThemeManager.Theme theme) {
        workingTheme = theme;
        
        if (previewModeCheck.isSelected()) {
            themeManager.previewTheme(theme);
        } else {
            themeManager.applyTheme(theme);
        }
        
        updateColorCustomizationPanel();
        statusLabel.setText("Selected theme: " + theme.getName());
    }
    
    private void updateColorCustomizationPanel() {
        // Clear existing color pickers
        colorCustomizationPanel.getChildren().removeIf(node -> node instanceof VBox && 
            ((VBox) node).getStyleClass().contains("color-picker-box"));
        colorPickers.clear();
        
        if (workingTheme == null) {
            return;
        }
        
        // Create color pickers for theme colors
        for (Map.Entry<String, Color> entry : workingTheme.getColors().entrySet()) {
            String colorName = entry.getKey();
            Color color = entry.getValue();
            
            VBox colorBox = createColorPickerBox(colorName, color);
            colorCustomizationPanel.getChildren().add(colorBox);
        }
    }
    
    private VBox createColorPickerBox(String colorName, Color initialColor) {
        VBox colorBox = new VBox(5);
        colorBox.getStyleClass().add("color-picker-box");
        
        // Color name label
        Label nameLabel = new Label(formatColorName(colorName));
        nameLabel.getStyleClass().add("color-name-label");
        
        // Color picker
        ColorPicker colorPicker = new ColorPicker(initialColor);
        colorPicker.getStyleClass().add("theme-color-picker");
        colorPicker.setMaxWidth(Double.MAX_VALUE);
        
        // Store reference for later access
        colorPickers.put(colorName, colorPicker);
        
        // Handle color changes
        colorPicker.setOnAction(e -> {
            if (workingTheme != null && !workingTheme.isReadOnly()) {
                Color newColor = colorPicker.getValue();
                workingTheme.setColor(colorName, newColor);
                
                if (previewModeCheck.isSelected()) {
                    themeManager.previewTheme(workingTheme);
                }
                
                statusLabel.setText("Updated color: " + formatColorName(colorName));
            }
        });
        
        // Disable picker for read-only themes
        colorPicker.setDisable(workingTheme != null && workingTheme.isReadOnly());
        
        colorBox.getChildren().addAll(nameLabel, colorPicker);
        return colorBox;
    }
    
    private String formatColorName(String colorName) {
        // Convert "background_primary" to "Background Primary"
        return Arrays.stream(colorName.split("_"))
                .map(word -> word.substring(0, 1).toUpperCase() + word.substring(1).toLowerCase())
                .collect(Collectors.joining(" "));
    }
    
    private void createCustomTheme() {
        TextInputDialog dialog = new TextInputDialog();
        dialog.setTitle("Create Custom Theme");
        dialog.setHeaderText("Create a new custom theme");
        dialog.setContentText("Theme name:");
        
        Optional<String> result = dialog.showAndWait();
        if (result.isPresent() && !result.get().trim().isEmpty()) {
            String themeName = result.get().trim();
            
            // Ask for description
            TextInputDialog descDialog = new TextInputDialog();
            descDialog.setTitle("Theme Description");
            descDialog.setHeaderText("Describe your custom theme");
            descDialog.setContentText("Description (optional):");
            
            String description = descDialog.showAndWait().orElse("");
            
            // Create theme based on current selection
            ThemeManager.Theme basedOn = themeCombo.getValue();
            ThemeManager.Theme customTheme = themeManager.createCustomTheme(themeName, description, basedOn);
            
            // Update UI
            themeCombo.setValue(customTheme);
            workingTheme = customTheme;
            updateColorCustomizationPanel();
            
            statusLabel.setText("Created custom theme: " + themeName);
        }
    }
    
    private void deleteCustomTheme() {
        ThemeManager.Theme selected = themeCombo.getValue();
        if (selected != null && selected.getType() == ThemeManager.ThemeType.USER_CUSTOM) {
            Alert confirm = new Alert(Alert.AlertType.CONFIRMATION);
            confirm.setTitle("Delete Custom Theme");
            confirm.setHeaderText("Delete custom theme?");
            confirm.setContentText("Are you sure you want to delete the theme '" + selected.getName() + "'?");
            
            Optional<ButtonType> result = confirm.showAndWait();
            if (result.isPresent() && result.get() == ButtonType.OK) {
                if (themeManager.deleteCustomTheme(selected.getId())) {
                    statusLabel.setText("Deleted custom theme: " + selected.getName());
                    
                    // Select default theme
                    themeCombo.setValue(themeManager.getTheme("dark"));
                }
            }
        }
    }
    
    private void importTheme() {
        FileChooser fileChooser = new FileChooser();
        fileChooser.setTitle("Import Theme");
        fileChooser.getExtensionFilters().add(
            new FileChooser.ExtensionFilter("Theme Files", "*.properties"));
        
        File file = fileChooser.showOpenDialog(this);
        if (file != null) {
            try {
                ThemeManager.Theme importedTheme = themeManager.importTheme(file);
                themeCombo.setValue(importedTheme);
                statusLabel.setText("Imported theme: " + importedTheme.getName());
                
            } catch (Exception e) {
                logger.error("Failed to import theme", e);
                showError("Import Error", "Failed to import theme: " + e.getMessage());
            }
        }
    }
    
    private void exportTheme() {
        ThemeManager.Theme selected = themeCombo.getValue();
        if (selected == null) {
            return;
        }
        
        FileChooser fileChooser = new FileChooser();
        fileChooser.setTitle("Export Theme");
        fileChooser.setInitialFileName(selected.getName().toLowerCase().replaceAll("\\s+", "-") + ".properties");
        fileChooser.getExtensionFilters().add(
            new FileChooser.ExtensionFilter("Theme Files", "*.properties"));
        
        File file = fileChooser.showSaveDialog(this);
        if (file != null) {
            try {
                themeManager.exportTheme(selected, file);
                statusLabel.setText("Exported theme: " + selected.getName());
                
            } catch (Exception e) {
                logger.error("Failed to export theme", e);
                showError("Export Error", "Failed to export theme: " + e.getMessage());
            }
        }
    }
    
    private void resetTheme() {
        if (workingTheme != null && !workingTheme.isReadOnly()) {
            Alert confirm = new Alert(Alert.AlertType.CONFIRMATION);
            confirm.setTitle("Reset Theme");
            confirm.setHeaderText("Reset theme colors?");
            confirm.setContentText("This will reset all customizations for the current theme.");
            
            Optional<ButtonType> result = confirm.showAndWait();
            if (result.isPresent() && result.get() == ButtonType.OK) {
                // Reset theme colors to defaults
                // Implementation would restore original colors
                updateColorCustomizationPanel();
                statusLabel.setText("Reset theme: " + workingTheme.getName());
            }
        }
    }
    
    private void applyChanges() {
        if (themeManager.isPreviewMode()) {
            themeManager.commitPreviewTheme();
        }
        statusLabel.setText("Changes applied");
    }
    
    private void cancelChanges() {
        if (themeManager.isPreviewMode()) {
            themeManager.exitPreviewMode();
        }
        close();
    }
    
    private void applyAndClose() {
        applyChanges();
        close();
    }
    
    private void showExitConfirmation() {
        Alert alert = new Alert(Alert.AlertType.CONFIRMATION);
        alert.setTitle("Exit Theme Customization");
        alert.setHeaderText("You are in preview mode");
        alert.setContentText("Do you want to apply the current theme before closing?");
        
        alert.getButtonTypes().setAll(ButtonType.YES, ButtonType.NO, ButtonType.CANCEL);
        
        Optional<ButtonType> result = alert.showAndWait();
        if (result.isPresent()) {
            switch (result.get().getButtonData()) {
                case YES:
                    applyChanges();
                    close();
                    break;
                case NO:
                    cancelChanges();
                    break;
                case CANCEL_CLOSE:
                default:
                    // Do nothing - keep dialog open
                    break;
            }
        }
    }
    
    private void showError(String title, String message) {
        Alert alert = new Alert(Alert.AlertType.ERROR);
        alert.setTitle(title);
        alert.setHeaderText(null);
        alert.setContentText(message);
        alert.showAndWait();
    }
    
    @Override
    public void close() {
        // Unregister scene from theme manager
        themeManager.unregisterScene(getScene());
        super.close();
    }
}