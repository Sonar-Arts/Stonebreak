package com.openmason.ui;

import com.openmason.ui.shortcuts.KeyboardShortcutSystem;
import com.openmason.ui.themes.ThemeManager;
import com.openmason.ui.help.HelpSystem;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Scene;
import javafx.scene.control.*;
import javafx.scene.layout.*;
import javafx.stage.Modality;
import javafx.stage.Stage;
import javafx.stage.StageStyle;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Advanced preferences dialog integrating all Phase 8 UI enhancement systems
 * with professional layout and comprehensive customization options.
 */
public class AdvancedPreferencesDialog extends Stage {
    
    private static final Logger logger = LoggerFactory.getLogger(AdvancedPreferencesDialog.class);
    
    private final AdvancedUIManager uiManager;
    private final ThemeManager themeManager;
    private final KeyboardShortcutSystem shortcutSystem;
    private final HelpSystem helpSystem;
    
    // UI Components
    private TabPane mainTabs;
    private boolean hasUnsavedChanges = false;
    
    public AdvancedPreferencesDialog(Stage parent) {
        this.uiManager = AdvancedUIManager.getInstance();
        this.themeManager = ThemeManager.getInstance();
        this.shortcutSystem = KeyboardShortcutSystem.getInstance();
        this.helpSystem = HelpSystem.getInstance();
        
        initOwner(parent);
        initModality(Modality.APPLICATION_MODAL);
        initStyle(StageStyle.DECORATED);
        
        setTitle("OpenMason Preferences");
        setWidth(900);
        setHeight(700);
        setMinWidth(800);
        setMinHeight(600);
        
        initializeUI();
        setupEventHandlers();
        
        // Center on parent
        if (parent != null) {
            setX(parent.getX() + (parent.getWidth() - getWidth()) / 2);
            setY(parent.getY() + (parent.getHeight() - getHeight()) / 2);
        }
    }
    
    private void initializeUI() {
        VBox root = new VBox(15);
        root.setPadding(new Insets(20));
        root.getStyleClass().add("preferences-dialog");
        
        // Header
        root.getChildren().add(createHeaderSection());
        
        // Main tabs
        mainTabs = new TabPane();
        mainTabs.setTabClosingPolicy(TabPane.TabClosingPolicy.UNAVAILABLE);
        mainTabs.getStyleClass().add("preferences-tabs");
        
        // Create tabs
        mainTabs.getTabs().addAll(
            createGeneralTab(),
            createAppearanceTab(),
            createShortcutsTab(),
            createInterfaceTab(),
            createHelpTab(),
            createAdvancedTab()
        );
        
        root.getChildren().add(mainTabs);
        VBox.setVgrow(mainTabs, Priority.ALWAYS);
        
        // Footer
        root.getChildren().add(createFooterSection());
        
        Scene scene = new Scene(root);
        scene.getStylesheets().add(getClass().getResource("/css/dark-theme.css").toExternalForm());
        setScene(scene);
        
        // Register scene with theme manager
        themeManager.registerScene(scene);
    }
    
    private VBox createHeaderSection() {
        VBox header = new VBox(10);
        header.getStyleClass().add("preferences-header");
        
        Label titleLabel = new Label("OpenMason Preferences");
        titleLabel.getStyleClass().addAll("dialog-title", "h2");
        
        Label descLabel = new Label("Customize OpenMason to match your workflow and preferences.");
        descLabel.getStyleClass().add("dialog-description");
        descLabel.setWrapText(true);
        
        header.getChildren().addAll(titleLabel, descLabel);
        return header;
    }
    
    private Tab createGeneralTab() {
        Tab tab = new Tab("General");
        ScrollPane content = new ScrollPane(createGeneralContent());
        content.setFitToWidth(true);
        content.getStyleClass().add("preferences-scroll");
        tab.setContent(content);
        return tab;
    }
    
    private VBox createGeneralContent() {
        VBox content = new VBox(20);
        content.setPadding(new Insets(20));
        content.getStyleClass().add("preferences-content");
        
        // Application Settings
        VBox appSection = createPreferenceSection("Application Settings",
            "Configure general application behavior and startup options.");
        
        // Auto-save option
        CheckBox autoSaveCheck = new CheckBox("Auto-save layouts and settings");
        autoSaveCheck.setSelected(true);
        autoSaveCheck.getStyleClass().add("preference-checkbox");
        
        // Startup behavior
        VBox startupBox = new VBox(8);
        Label startupLabel = new Label("Startup Behavior:");
        startupLabel.getStyleClass().add("preference-label");
        
        ComboBox<String> startupCombo = new ComboBox<>();
        startupCombo.getItems().addAll(
            "Show getting started tutorial",
            "Open last used project",
            "Show empty workspace",
            "Open specific project..."
        );
        startupCombo.setValue("Show empty workspace");
        startupCombo.getStyleClass().add("preference-combo");
        
        startupBox.getChildren().addAll(startupLabel, startupCombo);
        
        // Recent files
        VBox recentBox = new VBox(8);
        Label recentLabel = new Label("Recent Files:");
        recentLabel.getStyleClass().add("preference-label");
        
        Spinner<Integer> recentSpinner = new Spinner<>(5, 50, 10, 1);
        recentSpinner.getStyleClass().add("preference-spinner");
        recentSpinner.setPrefWidth(100);
        
        Label recentDescLabel = new Label("Number of recent files to remember");
        recentDescLabel.getStyleClass().add("preference-description");
        
        recentBox.getChildren().addAll(recentLabel, recentSpinner, recentDescLabel);
        
        appSection.getChildren().addAll(autoSaveCheck, startupBox, recentBox);
        
        // Performance Settings
        VBox perfSection = createPreferenceSection("Performance Settings",
            "Adjust performance and memory usage settings.");
        
        // Memory settings
        VBox memoryBox = new VBox(8);
        Label memoryLabel = new Label("Memory Usage:");
        memoryLabel.getStyleClass().add("preference-label");
        
        Slider memorySlider = new Slider(512, 8192, 2048);
        memorySlider.setShowTickLabels(true);
        memorySlider.setShowTickMarks(true);
        memorySlider.setMajorTickUnit(1024);
        memorySlider.getStyleClass().add("preference-slider");
        
        Label memoryValueLabel = new Label("2048 MB");
        memoryValueLabel.getStyleClass().add("preference-value");
        
        memorySlider.valueProperty().addListener((obs, oldVal, newVal) -> 
            memoryValueLabel.setText(String.format("%.0f MB", newVal.doubleValue())));
        
        memoryBox.getChildren().addAll(memoryLabel, memorySlider, memoryValueLabel);
        
        perfSection.getChildren().addAll(memoryBox);
        
        content.getChildren().addAll(appSection, perfSection);
        return content;
    }
    
    private Tab createAppearanceTab() {
        Tab tab = new Tab("Appearance");
        ScrollPane content = new ScrollPane(createAppearanceContent());
        content.setFitToWidth(true);
        content.getStyleClass().add("preferences-scroll");
        tab.setContent(content);
        return tab;
    }
    
    private VBox createAppearanceContent() {
        VBox content = new VBox(20);
        content.setPadding(new Insets(20));
        content.getStyleClass().add("preferences-content");
        
        // Theme Settings
        VBox themeSection = createPreferenceSection("Theme Settings",
            "Customize the visual appearance of OpenMason.");
        
        // Current theme
        VBox themeBox = new VBox(8);
        Label themeLabel = new Label("Current Theme:");
        themeLabel.getStyleClass().add("preference-label");
        
        ComboBox<ThemeManager.Theme> themeCombo = new ComboBox<>();
        themeCombo.getItems().setAll(themeManager.getAvailableThemes());
        themeCombo.setValue(themeManager.getCurrentTheme());
        themeCombo.getStyleClass().add("preference-combo");
        themeCombo.setMaxWidth(Double.MAX_VALUE);
        
        Button customizeThemeButton = new Button("Customize Theme...");
        customizeThemeButton.getStyleClass().addAll("secondary-button");
        customizeThemeButton.setOnAction(e -> uiManager.showThemeCustomization());
        
        themeBox.getChildren().addAll(themeLabel, themeCombo, customizeThemeButton);
        
        // UI Density
        VBox densityBox = new VBox(8);
        Label densityLabel = new Label("UI Density:");
        densityLabel.getStyleClass().add("preference-label");
        
        ToggleGroup densityGroup = new ToggleGroup();
        HBox densityRadios = new HBox(15);
        
        for (ThemeManager.UIDensity density : ThemeManager.UIDensity.values()) {
            RadioButton radio = new RadioButton(density.getDisplayName());
            radio.setToggleGroup(densityGroup);
            radio.setUserData(density);
            radio.getStyleClass().add("preference-radio");
            
            if (density == themeManager.getCurrentDensity()) {
                radio.setSelected(true);
            }
            
            densityRadios.getChildren().add(radio);
        }
        
        densityBox.getChildren().addAll(densityLabel, densityRadios);
        
        themeSection.getChildren().addAll(themeBox, densityBox);
        
        // Font Settings
        VBox fontSection = createPreferenceSection("Font Settings",
            "Configure font sizes and styles for the interface.");
        
        // Font size
        VBox fontSizeBox = new VBox(8);
        Label fontSizeLabel = new Label("Interface Font Size:");
        fontSizeLabel.getStyleClass().add("preference-label");
        
        Slider fontSizeSlider = new Slider(8, 18, 12);
        fontSizeSlider.setShowTickLabels(true);
        fontSizeSlider.setShowTickMarks(true);
        fontSizeSlider.setMajorTickUnit(2);
        fontSizeSlider.getStyleClass().add("preference-slider");
        
        Label fontSizeValueLabel = new Label("12 pt");
        fontSizeValueLabel.getStyleClass().add("preference-value");
        
        fontSizeSlider.valueProperty().addListener((obs, oldVal, newVal) -> 
            fontSizeValueLabel.setText(String.format("%.0f pt", newVal.doubleValue())));
        
        fontSizeBox.getChildren().addAll(fontSizeLabel, fontSizeSlider, fontSizeValueLabel);
        
        fontSection.getChildren().addAll(fontSizeBox);
        
        content.getChildren().addAll(themeSection, fontSection);
        return content;
    }
    
    private Tab createShortcutsTab() {
        Tab tab = new Tab("Shortcuts");
        VBox content = new VBox(20);
        content.setPadding(new Insets(20));
        content.getStyleClass().add("preferences-content");
        
        // Shortcuts overview
        VBox shortcutsSection = createPreferenceSection("Keyboard Shortcuts",
            "Customize keyboard shortcuts for faster workflow.");
        
        // Current preset
        VBox presetBox = new VBox(8);
        Label presetLabel = new Label("Shortcut Preset:");
        presetLabel.getStyleClass().add("preference-label");
        
        ComboBox<KeyboardShortcutSystem.ShortcutPreset> presetCombo = new ComboBox<>();
        presetCombo.getItems().setAll(KeyboardShortcutSystem.ShortcutPreset.values());
        presetCombo.setValue(shortcutSystem.getCurrentPreset());
        presetCombo.getStyleClass().add("preference-combo");
        
        presetBox.getChildren().addAll(presetLabel, presetCombo);
        
        // Shortcut editor button
        Button editShortcutsButton = new Button("Edit Shortcuts...");
        editShortcutsButton.getStyleClass().addAll("primary-button");
        editShortcutsButton.setOnAction(e -> uiManager.showShortcutEditor());
        
        // Quick shortcut display
        VBox quickShortcutsBox = new VBox(8);
        Label quickLabel = new Label("Common Shortcuts:");
        quickLabel.getStyleClass().add("preference-label");
        
        GridPane shortcutsGrid = new GridPane();
        shortcutsGrid.setHgap(20);
        shortcutsGrid.setVgap(5);
        shortcutsGrid.getStyleClass().add("shortcuts-grid");
        
        // Add some common shortcuts
        addShortcutRow(shortcutsGrid, 0, "New Model:", "Ctrl+N");
        addShortcutRow(shortcutsGrid, 1, "Open Model:", "Ctrl+O");
        addShortcutRow(shortcutsGrid, 2, "Save Model:", "Ctrl+S");
        addShortcutRow(shortcutsGrid, 3, "Reset View:", "Home");
        addShortcutRow(shortcutsGrid, 4, "Toggle Wireframe:", "Z");
        
        quickShortcutsBox.getChildren().addAll(quickLabel, shortcutsGrid);
        
        shortcutsSection.getChildren().addAll(presetBox, editShortcutsButton, quickShortcutsBox);
        
        ScrollPane scrollContent = new ScrollPane(shortcutsSection);
        scrollContent.setFitToWidth(true);
        scrollContent.getStyleClass().add("preferences-scroll");
        
        content.getChildren().add(scrollContent);
        VBox.setVgrow(scrollContent, Priority.ALWAYS);
        
        tab.setContent(content);
        return tab;
    }
    
    private void addShortcutRow(GridPane grid, int row, String action, String shortcut) {
        Label actionLabel = new Label(action);
        actionLabel.getStyleClass().add("shortcut-action");
        
        Label shortcutLabel = new Label(shortcut);
        shortcutLabel.getStyleClass().add("shortcut-key");
        
        grid.add(actionLabel, 0, row);
        grid.add(shortcutLabel, 1, row);
    }
    
    private Tab createInterfaceTab() {
        Tab tab = new Tab("Interface");
        ScrollPane content = new ScrollPane(createInterfaceContent());
        content.setFitToWidth(true);
        content.getStyleClass().add("preferences-scroll");
        tab.setContent(content);
        return tab;
    }
    
    private VBox createInterfaceContent() {
        VBox content = new VBox(20);
        content.setPadding(new Insets(20));
        content.getStyleClass().add("preferences-content");
        
        // Panel Settings
        VBox panelSection = createPreferenceSection("Panel Settings",
            "Configure the behavior of dockable panels and workspace layout.");
        
        // Panel behavior
        CheckBox autoHidePanelsCheck = new CheckBox("Auto-hide panels when not in use");
        autoHidePanelsCheck.getStyleClass().add("preference-checkbox");
        
        CheckBox snapToEdgesCheck = new CheckBox("Snap panels to window edges");
        snapToEdgesCheck.setSelected(true);
        snapToEdgesCheck.getStyleClass().add("preference-checkbox");
        
        CheckBox rememberLayoutCheck = new CheckBox("Remember panel layout between sessions");
        rememberLayoutCheck.setSelected(true);
        rememberLayoutCheck.getStyleClass().add("preference-checkbox");
        
        panelSection.getChildren().addAll(autoHidePanelsCheck, snapToEdgesCheck, rememberLayoutCheck);
        
        // Toolbar Settings
        VBox toolbarSection = createPreferenceSection("Toolbar Settings",
            "Customize toolbar appearance and behavior.");
        
        CheckBox showToolbarTextCheck = new CheckBox("Show text labels on toolbar buttons");
        showToolbarTextCheck.getStyleClass().add("preference-checkbox");
        
        CheckBox largeIconsCheck = new CheckBox("Use large toolbar icons");
        largeIconsCheck.getStyleClass().add("preference-checkbox");
        
        toolbarSection.getChildren().addAll(showToolbarTextCheck, largeIconsCheck);
        
        // Status Bar Settings
        VBox statusSection = createPreferenceSection("Status Bar Settings",
            "Configure information displayed in the status bar.");
        
        CheckBox showMemoryCheck = new CheckBox("Show memory usage");
        showMemoryCheck.setSelected(true);
        showMemoryCheck.getStyleClass().add("preference-checkbox");
        
        CheckBox showFPSCheck = new CheckBox("Show frame rate");
        showFPSCheck.setSelected(true);
        showFPSCheck.getStyleClass().add("preference-checkbox");
        
        CheckBox showProgressCheck = new CheckBox("Show operation progress");
        showProgressCheck.setSelected(true);
        showProgressCheck.getStyleClass().add("preference-checkbox");
        
        statusSection.getChildren().addAll(showMemoryCheck, showFPSCheck, showProgressCheck);
        
        content.getChildren().addAll(panelSection, toolbarSection, statusSection);
        return content;
    }
    
    private Tab createHelpTab() {
        Tab tab = new Tab("Help");
        ScrollPane content = new ScrollPane(createHelpContent());
        content.setFitToWidth(true);
        content.getStyleClass().add("preferences-scroll");
        tab.setContent(content);
        return tab;
    }
    
    private VBox createHelpContent() {
        VBox content = new VBox(20);
        content.setPadding(new Insets(20));
        content.getStyleClass().add("preferences-content");
        
        // Help System Settings
        VBox helpSection = createPreferenceSection("Help System Settings",
            "Configure help system behavior and tutorial options.");
        
        CheckBox showTooltipsCheck = new CheckBox("Show context-sensitive tooltips");
        showTooltipsCheck.setSelected(true);
        showTooltipsCheck.getStyleClass().add("preference-checkbox");
        
        CheckBox autoShowHelpCheck = new CheckBox("Auto-show help for new features");
        autoShowHelpCheck.setSelected(true);
        autoShowHelpCheck.getStyleClass().add("preference-checkbox");
        
        CheckBox enableTutorialsCheck = new CheckBox("Enable interactive tutorials");
        enableTutorialsCheck.setSelected(true);
        enableTutorialsCheck.getStyleClass().add("preference-checkbox");
        
        helpSection.getChildren().addAll(showTooltipsCheck, autoShowHelpCheck, enableTutorialsCheck);
        
        // Tutorial Settings
        VBox tutorialSection = createPreferenceSection("Tutorial Settings",
            "Configure tutorial behavior and progress tracking.");
        
        CheckBox rememberProgressCheck = new CheckBox("Remember tutorial progress");
        rememberProgressCheck.setSelected(true);
        rememberProgressCheck.getStyleClass().add("preference-checkbox");
        
        CheckBox showTutorialTipsCheck = new CheckBox("Show tutorial tips during normal use");
        showTutorialTipsCheck.getStyleClass().add("preference-checkbox");
        
        tutorialSection.getChildren().addAll(rememberProgressCheck, showTutorialTipsCheck);
        
        // Help Actions
        VBox actionsSection = createPreferenceSection("Help Actions",
            "Quick access to help system features.");
        
        Button showHelpButton = new Button("Open Help Browser");
        showHelpButton.getStyleClass().addAll("secondary-button");
        showHelpButton.setOnAction(e -> uiManager.showHelp());
        
        Button startTutorialButton = new Button("Start Getting Started Tutorial");
        startTutorialButton.getStyleClass().addAll("secondary-button");
        startTutorialButton.setOnAction(e -> uiManager.startGettingStartedTutorial());
        
        HBox buttonBox = new HBox(10);
        buttonBox.getChildren().addAll(showHelpButton, startTutorialButton);
        
        actionsSection.getChildren().add(buttonBox);
        
        content.getChildren().addAll(helpSection, tutorialSection, actionsSection);
        return content;
    }
    
    private Tab createAdvancedTab() {
        Tab tab = new Tab("Advanced");
        ScrollPane content = new ScrollPane(createAdvancedContent());
        content.setFitToWidth(true);
        content.getStyleClass().add("preferences-scroll");
        tab.setContent(content);
        return tab;
    }
    
    private VBox createAdvancedContent() {
        VBox content = new VBox(20);
        content.setPadding(new Insets(20));
        content.getStyleClass().add("preferences-content");
        
        // System Information
        VBox systemSection = createPreferenceSection("System Information",
            "Advanced system information and diagnostics.");
        
        TextArea systemInfoArea = new TextArea();
        systemInfoArea.setEditable(false);
        systemInfoArea.setPrefRowCount(10);
        systemInfoArea.getStyleClass().add("system-info-area");
        systemInfoArea.setText(uiManager.getSystemStatistics());
        
        Button refreshInfoButton = new Button("Refresh");
        refreshInfoButton.getStyleClass().addAll("secondary-button", "small-button");
        refreshInfoButton.setOnAction(e -> systemInfoArea.setText(uiManager.getSystemStatistics()));
        
        systemSection.getChildren().addAll(systemInfoArea, refreshInfoButton);
        
        // Debug Settings
        VBox debugSection = createPreferenceSection("Debug Settings",
            "Advanced debugging and logging options.");
        
        CheckBox enableLoggingCheck = new CheckBox("Enable verbose logging");
        enableLoggingCheck.getStyleClass().add("preference-checkbox");
        
        CheckBox showDebugInfoCheck = new CheckBox("Show debug information in UI");
        showDebugInfoCheck.getStyleClass().add("preference-checkbox");
        
        debugSection.getChildren().addAll(enableLoggingCheck, showDebugInfoCheck);
        
        // Reset Settings
        VBox resetSection = createPreferenceSection("Reset Settings",
            "Reset various aspects of the application to defaults.");
        
        Button resetPreferencesButton = new Button("Reset All Preferences");
        resetPreferencesButton.getStyleClass().addAll("warning-button");
        
        Button resetLayoutButton = new Button("Reset Interface Layout");
        resetLayoutButton.getStyleClass().addAll("warning-button");
        
        Button resetShortcutsButton = new Button("Reset Keyboard Shortcuts");
        resetShortcutsButton.getStyleClass().addAll("warning-button");
        
        VBox resetButtonsBox = new VBox(10);
        resetButtonsBox.getChildren().addAll(resetPreferencesButton, resetLayoutButton, resetShortcutsButton);
        
        resetSection.getChildren().add(resetButtonsBox);
        
        content.getChildren().addAll(systemSection, debugSection, resetSection);
        return content;
    }
    
    private VBox createPreferenceSection(String title, String description) {
        VBox section = new VBox(12);
        section.getStyleClass().add("preference-section");
        
        Label titleLabel = new Label(title);
        titleLabel.getStyleClass().addAll("section-title", "h3");
        
        if (description != null && !description.isEmpty()) {
            Label descLabel = new Label(description);
            descLabel.getStyleClass().add("section-description");
            descLabel.setWrapText(true);
            section.getChildren().addAll(titleLabel, descLabel);
        } else {
            section.getChildren().add(titleLabel);
        }
        
        return section;
    }
    
    private HBox createFooterSection() {
        HBox footer = new HBox(10);
        footer.setAlignment(Pos.CENTER_RIGHT);
        footer.getStyleClass().add("preferences-footer");
        
        Button resetButton = new Button("Reset to Defaults");
        resetButton.getStyleClass().addAll("warning-button");
        resetButton.setOnAction(e -> resetToDefaults());
        
        Button cancelButton = new Button("Cancel");
        cancelButton.getStyleClass().addAll("secondary-button");
        cancelButton.setOnAction(e -> close());
        
        Button applyButton = new Button("Apply");
        applyButton.getStyleClass().addAll("primary-button");
        applyButton.setOnAction(e -> applyChanges());
        
        Button okButton = new Button("OK");
        okButton.getStyleClass().addAll("primary-button");
        okButton.setOnAction(e -> applyAndClose());
        
        footer.getChildren().addAll(resetButton, cancelButton, applyButton, okButton);
        return footer;
    }
    
    private void setupEventHandlers() {
        // Handle window closing
        setOnCloseRequest(e -> {
            if (hasUnsavedChanges) {
                e.consume();
                showUnsavedChangesDialog();
            }
        });
    }
    
    private void resetToDefaults() {
        Alert confirm = new Alert(Alert.AlertType.CONFIRMATION);
        confirm.setTitle("Reset to Defaults");
        confirm.setHeaderText("Reset all preferences to defaults?");
        confirm.setContentText("This will reset all preferences to their default values. This action cannot be undone.");
        
        confirm.showAndWait().ifPresent(response -> {
            if (response == ButtonType.OK) {
                // Reset all systems to defaults
                themeManager.applyTheme("dark");
                themeManager.setUIDensity(ThemeManager.UIDensity.STANDARD);
                shortcutSystem.applyPreset(KeyboardShortcutSystem.ShortcutPreset.DEFAULT);
                
                logger.info("Reset all preferences to defaults");
            }
        });
    }
    
    private void applyChanges() {
        // Apply any pending changes
        hasUnsavedChanges = false;
        logger.info("Applied preference changes");
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
        
        alert.showAndWait().ifPresent(response -> {
            switch (response.getButtonData()) {
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
        });
    }
    
    @Override
    public void close() {
        // Unregister scene from theme manager
        themeManager.unregisterScene(getScene());
        super.close();
    }
}