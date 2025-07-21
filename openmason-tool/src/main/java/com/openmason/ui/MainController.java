package com.openmason.ui;

import javafx.application.Platform;
import javafx.fxml.FXML;
import javafx.fxml.Initializable;
import javafx.scene.control.*;
import javafx.scene.image.Image;
import javafx.scene.image.ImageView;
import javafx.scene.paint.Color;
import javafx.scene.shape.*;
import javafx.scene.layout.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.InputStream;
import java.net.URL;
import java.util.ResourceBundle;

/**
 * Main controller for OpenMason application window.
 * Manages the primary UI layout, menu actions, and coordination between panels.
 */
public class MainController implements Initializable {
    
    private static final Logger logger = LoggerFactory.getLogger(MainController.class);
    
    // Menu Items
    @FXML private MenuItem menuNewModel;
    @FXML private MenuItem menuOpenModel;
    @FXML private MenuItem menuOpenProject;
    @FXML private MenuItem menuSaveModel;
    @FXML private MenuItem menuSaveModelAs;
    @FXML private MenuItem menuExportModel;
    @FXML private Menu menuRecentFiles;
    @FXML private MenuItem menuExit;
    @FXML private MenuItem menuUndo;
    @FXML private MenuItem menuRedo;
    @FXML private MenuItem menuPreferences;
    @FXML private MenuItem menuResetView;
    @FXML private MenuItem menuFitToView;
    @FXML private CheckMenuItem menuShowGrid;
    @FXML private CheckMenuItem menuShowAxes;
    @FXML private CheckMenuItem menuWireframeMode;
    @FXML private CheckMenuItem menuShowModelBrowser;
    @FXML private CheckMenuItem menuShowPropertyPanel;
    @FXML private CheckMenuItem menuShowStatusBar;
    @FXML private MenuItem menuValidateModel;
    @FXML private MenuItem menuGenerateTextures;
    @FXML private MenuItem menuAbout;
    
    // Toolbar Buttons
    @FXML private Button btnNewModel;
    @FXML private Button btnOpenModel;
    @FXML private Button btnSaveModel;
    @FXML private Button btnResetView;
    @FXML private Button btnZoomIn;
    @FXML private Button btnZoomOut;
    @FXML private Button btnFitToView;
    @FXML private ToggleButton btnWireframe;
    @FXML private ToggleButton btnShowGrid;
    @FXML private ToggleButton btnShowAxes;
    @FXML private Button btnValidateModel;
    @FXML private Button btnGenerateTextures;
    @FXML private Button btnSettings;
    @FXML private Label lblCurrentModel;
    
    // Main Layout Panels
    @FXML private SplitPane mainSplitPane;
    @FXML private AnchorPane leftPanel;
    @FXML private AnchorPane centerPanel;
    @FXML private AnchorPane rightPanel;
    
    // Model Browser
    @FXML private TextField txtSearch;
    @FXML private ComboBox<String> cmbFilter;
    @FXML private TreeView<String> treeModels;
    @FXML private Label lblModelInfo;
    
    // Viewport
    @FXML private AnchorPane viewportContainer;
    @FXML private ComboBox<String> cmbViewMode;
    @FXML private ComboBox<String> cmbRenderMode;
    
    // Properties Panel
    @FXML private ComboBox<String> cmbTextureVariant;
    @FXML private Slider sliderRotationX;
    @FXML private Slider sliderRotationY;
    @FXML private Slider sliderRotationZ;
    @FXML private TextField txtRotationX;
    @FXML private TextField txtRotationY;
    @FXML private TextField txtRotationZ;
    @FXML private Slider sliderScale;
    @FXML private TextField txtScale;
    @FXML private ComboBox<String> cmbAnimation;
    @FXML private Button btnPlayAnimation;
    @FXML private Button btnPauseAnimation;
    @FXML private Button btnStopAnimation;
    @FXML private Slider sliderAnimationTime;
    @FXML private Label lblPartCount;
    @FXML private Label lblVertexCount;
    @FXML private Label lblTriangleCount;
    @FXML private Label lblTextureVariants;
    @FXML private Button btnValidateProperties;
    @FXML private Button btnResetProperties;
    
    // Status Bar
    @FXML private HBox statusBar;
    @FXML private Label lblStatus;
    @FXML private Label lblProgress;
    @FXML private ProgressBar progressBar;
    @FXML private Label lblMemoryUsage;
    @FXML private Label lblFrameRate;
    
    // State
    private boolean modelLoaded = false;
    private String currentModelPath = null;
    private boolean unsavedChanges = false;
    
    @Override
    public void initialize(URL location, ResourceBundle resources) {
        logger.info("Initializing MainController...");
        
        try {
            setupMenuActions();
            setupToolbarIcons();
            setupToolbarActions();
            setupModelBrowser();
            setupViewport();
            setupPropertiesPanel();
            setupStatusBar();
            updateUIState();
            
            logger.info("MainController initialized successfully");
            
        } catch (Exception e) {
            logger.error("Failed to initialize MainController", e);
        }
    }
    
    /**
     * Set up menu item actions.
     */
    private void setupMenuActions() {
        // File menu
        menuNewModel.setOnAction(e -> newModel());
        menuOpenModel.setOnAction(e -> openModel());
        menuOpenProject.setOnAction(e -> openProject());
        menuSaveModel.setOnAction(e -> saveModel());
        menuSaveModelAs.setOnAction(e -> saveModelAs());
        menuExportModel.setOnAction(e -> exportModel());
        menuExit.setOnAction(e -> exitApplication());
        
        // Edit menu
        menuUndo.setOnAction(e -> undo());
        menuRedo.setOnAction(e -> redo());
        menuPreferences.setOnAction(e -> showPreferences());
        
        // View menu
        menuResetView.setOnAction(e -> resetView());
        menuFitToView.setOnAction(e -> fitToView());
        menuShowGrid.setOnAction(e -> toggleGrid());
        menuShowAxes.setOnAction(e -> toggleAxes());
        menuWireframeMode.setOnAction(e -> toggleWireframe());
        menuShowModelBrowser.setOnAction(e -> toggleModelBrowser());
        menuShowPropertyPanel.setOnAction(e -> togglePropertyPanel());
        menuShowStatusBar.setOnAction(e -> toggleStatusBar());
        
        // Tools menu
        menuValidateModel.setOnAction(e -> validateModel());
        menuGenerateTextures.setOnAction(e -> generateTextures());
        
        // Help menu
        menuAbout.setOnAction(e -> showAbout());
    }
    
    /**
     * Set up toolbar icons with dark mode friendly graphics.
     */
    private void setupToolbarIcons() {
        try {
            // Create dark-mode friendly programmatic icons - properly sized for buttons
            setButtonGraphic(btnNewModel, createNewModelIcon(16));
            setButtonGraphic(btnOpenModel, createOpenModelIcon(16));
            setButtonGraphic(btnSaveModel, createSaveModelIcon(16));
            
            setButtonGraphic(btnResetView, createResetViewIcon(16));
            setButtonGraphic(btnZoomIn, createZoomInIcon(16));
            setButtonGraphic(btnZoomOut, createZoomOutIcon(16));
            setButtonGraphic(btnFitToView, createFitToViewIcon(16));
            
            setToggleButtonGraphic(btnWireframe, createWireframeIcon(16));
            setToggleButtonGraphic(btnShowGrid, createGridIcon(16));
            setToggleButtonGraphic(btnShowAxes, createAxesIcon(16));
            
            setButtonGraphic(btnValidateModel, createValidateIcon(16));
            setButtonGraphic(btnGenerateTextures, createTextureIcon(16));
            setButtonGraphic(btnSettings, createSettingsIcon(16));
            
            // Animation button icons
            setButtonGraphic(btnPlayAnimation, createPlayIcon(14));
            setButtonGraphic(btnPauseAnimation, createPauseIcon(14));
            setButtonGraphic(btnStopAnimation, createStopIcon(14));
            
            logger.info("Toolbar icons created successfully");
            
        } catch (Exception e) {
            logger.error("Failed to create toolbar icons", e);
        }
    }
    
    /**
     * Set up toolbar button actions.
     */
    private void setupToolbarActions() {
        btnNewModel.setOnAction(e -> newModel());
        btnOpenModel.setOnAction(e -> openModel());
        btnSaveModel.setOnAction(e -> saveModel());
        
        btnResetView.setOnAction(e -> resetView());
        btnZoomIn.setOnAction(e -> zoomIn());
        btnZoomOut.setOnAction(e -> zoomOut());
        btnFitToView.setOnAction(e -> fitToView());
        
        btnWireframe.setOnAction(e -> toggleWireframe());
        btnShowGrid.setOnAction(e -> toggleGrid());
        btnShowAxes.setOnAction(e -> toggleAxes());
        
        btnValidateModel.setOnAction(e -> validateModel());
        btnGenerateTextures.setOnAction(e -> generateTextures());
        btnSettings.setOnAction(e -> showPreferences());
    }
    
    /**
     * Set up model browser functionality.
     */
    private void setupModelBrowser() {
        // Initialize filter combo box
        cmbFilter.getItems().addAll("All Models", "Cow Models", "Recent Files");
        cmbFilter.setValue("All Models");
        
        // Set up search functionality
        txtSearch.textProperty().addListener((obs, oldText, newText) -> filterModels(newText));
        
        // Set up model tree
        TreeItem<String> rootItem = new TreeItem<>("Models");
        rootItem.setExpanded(true);
        
        // Add placeholder items
        TreeItem<String> cowModels = new TreeItem<>("Cow Models");
        cowModels.getChildren().addAll(
            new TreeItem<>("Standard Cow"),
            new TreeItem<>("Default Variant"),
            new TreeItem<>("Angus Variant"),
            new TreeItem<>("Highland Variant"),
            new TreeItem<>("Jersey Variant")
        );
        cowModels.setExpanded(true);
        
        rootItem.getChildren().add(cowModels);
        treeModels.setRoot(rootItem);
        
        // Set up selection listener
        treeModels.getSelectionModel().selectedItemProperty().addListener((obs, oldSelection, newSelection) -> {
            if (newSelection != null) {
                selectModel(newSelection.getValue());
            }
        });
    }
    
    /**
     * Set up 3D viewport.
     */
    private void setupViewport() {
        // Initialize view mode combo
        cmbViewMode.getItems().addAll("Perspective", "Orthographic");
        cmbViewMode.setValue("Perspective");
        
        // Initialize render mode combo
        cmbRenderMode.getItems().addAll("Solid", "Wireframe", "Textured");
        cmbRenderMode.setValue("Textured");
        
        // Set up listeners
        cmbViewMode.setOnAction(e -> changeViewMode());
        cmbRenderMode.setOnAction(e -> changeRenderMode());
    }
    
    /**
     * Set up properties panel.
     */
    private void setupPropertiesPanel() {
        // Initialize texture variant combo
        cmbTextureVariant.getItems().addAll("Default", "Angus", "Highland", "Jersey");
        cmbTextureVariant.setValue("Default");
        cmbTextureVariant.setOnAction(e -> changeTextureVariant());
        
        // Set up rotation sliders
        setupRotationSlider(sliderRotationX, txtRotationX);
        setupRotationSlider(sliderRotationY, txtRotationY);
        setupRotationSlider(sliderRotationZ, txtRotationZ);
        
        // Set up scale slider
        setupScaleSlider(sliderScale, txtScale);
        
        // Initialize animation combo
        cmbAnimation.getItems().addAll("IDLE", "WALKING", "GRAZING");
        cmbAnimation.setValue("IDLE");
        cmbAnimation.setOnAction(e -> changeAnimation());
        
        // Set up animation controls
        btnPlayAnimation.setOnAction(e -> playAnimation());
        btnPauseAnimation.setOnAction(e -> pauseAnimation());
        btnStopAnimation.setOnAction(e -> stopAnimation());
        
        // Set up property buttons
        btnValidateProperties.setOnAction(e -> validateModel());
        btnResetProperties.setOnAction(e -> resetProperties());
    }
    
    /**
     * Set up status bar.
     */
    private void setupStatusBar() {
        updateStatus("Ready");
        updateMemoryUsage();
        updateFrameRate();
        
        // Start periodic updates
        startPeriodicUpdates();
    }
    
    /**
     * Set up rotation slider with text field binding.
     */
    private void setupRotationSlider(Slider slider, TextField textField) {
        slider.valueProperty().addListener((obs, oldVal, newVal) -> {
            textField.setText(String.format("%.1f", newVal.doubleValue()));
            updateModelTransform();
        });
        
        textField.textProperty().addListener((obs, oldText, newText) -> {
            try {
                double value = Double.parseDouble(newText);
                if (value >= -180 && value <= 180) {
                    slider.setValue(value);
                }
            } catch (NumberFormatException e) {
                // Invalid input, ignore
            }
        });
    }
    
    /**
     * Set up scale slider with text field binding.
     */
    private void setupScaleSlider(Slider slider, TextField textField) {
        slider.valueProperty().addListener((obs, oldVal, newVal) -> {
            textField.setText(String.format("%.2f", newVal.doubleValue()));
            updateModelTransform();
        });
        
        textField.textProperty().addListener((obs, oldText, newText) -> {
            try {
                double value = Double.parseDouble(newText);
                if (value >= 0.1 && value <= 3.0) {
                    slider.setValue(value);
                }
            } catch (NumberFormatException e) {
                // Invalid input, ignore
            }
        });
    }
    
    /**
     * Update UI state based on current model.
     */
    private void updateUIState() {
        boolean hasModel = modelLoaded;
        
        // Update menu items
        menuSaveModel.setDisable(!hasModel || !unsavedChanges);
        menuSaveModelAs.setDisable(!hasModel);
        menuExportModel.setDisable(!hasModel);
        
        // Update toolbar buttons
        btnSaveModel.setDisable(!hasModel || !unsavedChanges);
        
        // Update model info
        if (hasModel) {
            lblCurrentModel.setText(currentModelPath != null ? currentModelPath : "Untitled Model");
        } else {
            lblCurrentModel.setText("No model loaded");
        }
        
        // Update properties panel
        cmbTextureVariant.setDisable(!hasModel);
        cmbAnimation.setDisable(!hasModel);
        btnPlayAnimation.setDisable(!hasModel);
        btnPauseAnimation.setDisable(!hasModel);
        btnStopAnimation.setDisable(!hasModel);
        sliderAnimationTime.setDisable(!hasModel);
        btnValidateProperties.setDisable(!hasModel);
        btnResetProperties.setDisable(!hasModel);
    }
    
    // Action Methods (Stubs for Phase 1)
    
    private void newModel() {
        logger.info("New model action triggered");
        updateStatus("Creating new model...");
        // TODO: Implement new model creation
    }
    
    private void openModel() {
        logger.info("Open model action triggered");
        updateStatus("Opening model...");
        // TODO: Implement model file opening
    }
    
    private void openProject() {
        logger.info("Open project action triggered");
        updateStatus("Opening Stonebreak project...");
        // TODO: Implement Stonebreak project opening
    }
    
    private void saveModel() {
        logger.info("Save model action triggered");
        updateStatus("Saving model...");
        // TODO: Implement model saving
    }
    
    private void saveModelAs() {
        logger.info("Save model as action triggered");
        updateStatus("Saving model as...");
        // TODO: Implement save as functionality
    }
    
    private void exportModel() {
        logger.info("Export model action triggered");
        updateStatus("Exporting model...");
        // TODO: Implement model export
    }
    
    private void exitApplication() {
        logger.info("Exit application action triggered");
        Platform.exit();
    }
    
    private void undo() {
        logger.info("Undo action triggered");
        // TODO: Implement undo functionality
    }
    
    private void redo() {
        logger.info("Redo action triggered");
        // TODO: Implement redo functionality
    }
    
    private void showPreferences() {
        logger.info("Show preferences action triggered");
        // TODO: Implement preferences dialog
    }
    
    private void resetView() {
        logger.info("Reset view action triggered");
        updateStatus("Resetting view...");
        // TODO: Implement view reset
    }
    
    private void fitToView() {
        logger.info("Fit to view action triggered");
        updateStatus("Fitting to view...");
        // TODO: Implement fit to view
    }
    
    private void zoomIn() {
        logger.info("Zoom in action triggered");
        // TODO: Implement zoom in
    }
    
    private void zoomOut() {
        logger.info("Zoom out action triggered");
        // TODO: Implement zoom out
    }
    
    private void toggleGrid() {
        boolean showGrid = menuShowGrid.isSelected();
        btnShowGrid.setSelected(showGrid);
        logger.info("Toggle grid: {}", showGrid);
        // TODO: Implement grid toggle
    }
    
    private void toggleAxes() {
        boolean showAxes = menuShowAxes.isSelected();
        btnShowAxes.setSelected(showAxes);
        logger.info("Toggle axes: {}", showAxes);
        // TODO: Implement axes toggle
    }
    
    private void toggleWireframe() {
        boolean wireframe = menuWireframeMode.isSelected();
        btnWireframe.setSelected(wireframe);
        logger.info("Toggle wireframe: {}", wireframe);
        // TODO: Implement wireframe toggle
    }
    
    private void toggleModelBrowser() {
        boolean show = menuShowModelBrowser.isSelected();
        leftPanel.setVisible(show);
        leftPanel.setManaged(show);
        logger.info("Toggle model browser: {}", show);
    }
    
    private void togglePropertyPanel() {
        boolean show = menuShowPropertyPanel.isSelected();
        rightPanel.setVisible(show);
        rightPanel.setManaged(show);
        logger.info("Toggle property panel: {}", show);
    }
    
    private void toggleStatusBar() {
        boolean show = menuShowStatusBar.isSelected();
        statusBar.setVisible(show);
        statusBar.setManaged(show);
        logger.info("Toggle status bar: {}", show);
    }
    
    private void validateModel() {
        logger.info("Validate model action triggered");
        updateStatus("Validating model...");
        // TODO: Implement model validation
    }
    
    private void generateTextures() {
        logger.info("Generate textures action triggered");
        updateStatus("Generating textures...");
        // TODO: Implement texture generation
    }
    
    private void showAbout() {
        logger.info("Show about action triggered");
        // TODO: Implement about dialog
    }
    
    private void filterModels(String filter) {
        logger.debug("Filtering models with: {}", filter);
        // TODO: Implement model filtering
    }
    
    private void selectModel(String modelName) {
        logger.info("Selected model: {}", modelName);
        lblModelInfo.setText("Selected: " + modelName);
        // TODO: Implement model loading
    }
    
    private void changeViewMode() {
        String viewMode = cmbViewMode.getValue();
        logger.info("Changed view mode to: {}", viewMode);
        // TODO: Implement view mode change
    }
    
    private void changeRenderMode() {
        String renderMode = cmbRenderMode.getValue();
        logger.info("Changed render mode to: {}", renderMode);
        // TODO: Implement render mode change
    }
    
    private void changeTextureVariant() {
        String variant = cmbTextureVariant.getValue();
        logger.info("Changed texture variant to: {}", variant);
        updateStatus("Loading texture variant: " + variant);
        // TODO: Implement texture variant switching
    }
    
    private void changeAnimation() {
        String animation = cmbAnimation.getValue();
        logger.info("Changed animation to: {}", animation);
        // TODO: Implement animation change
    }
    
    private void playAnimation() {
        logger.info("Play animation action triggered");
        // TODO: Implement animation playback
    }
    
    private void pauseAnimation() {
        logger.info("Pause animation action triggered");
        // TODO: Implement animation pause
    }
    
    private void stopAnimation() {
        logger.info("Stop animation action triggered");
        // TODO: Implement animation stop
    }
    
    private void resetProperties() {
        logger.info("Reset properties action triggered");
        sliderRotationX.setValue(0);
        sliderRotationY.setValue(0);
        sliderRotationZ.setValue(0);
        sliderScale.setValue(1.0);
        updateStatus("Properties reset");
    }
    
    private void updateModelTransform() {
        // TODO: Implement model transformation update
    }
    
    private void updateStatus(String message) {
        Platform.runLater(() -> lblStatus.setText(message));
    }
    
    private void updateMemoryUsage() {
        Runtime runtime = Runtime.getRuntime();
        long totalMemory = runtime.totalMemory();
        long freeMemory = runtime.freeMemory();
        long usedMemory = totalMemory - freeMemory;
        
        Platform.runLater(() -> 
            lblMemoryUsage.setText(String.format("Memory: %d MB", usedMemory / (1024 * 1024)))
        );
    }
    
    private void updateFrameRate() {
        Platform.runLater(() -> lblFrameRate.setText("FPS: 60")); // Placeholder
    }
    
    private void startPeriodicUpdates() {
        // Update memory and FPS every 2 seconds
        Platform.runLater(() -> {
            updateMemoryUsage();
            updateFrameRate();
        });
        
        // Schedule next update
        Platform.runLater(() -> {
            javafx.animation.Timeline timeline = new javafx.animation.Timeline(
                new javafx.animation.KeyFrame(
                    javafx.util.Duration.seconds(2),
                    e -> startPeriodicUpdates()
                )
            );
            timeline.play();
        });
    }
    
    /**
     * Set graphic for a button with dark mode support.
     */
    private void setButtonGraphic(Button button, Pane graphic) {
        graphic.getStyleClass().add("toolbar-icon");
        graphic.setMaxSize(Region.USE_PREF_SIZE, Region.USE_PREF_SIZE);
        graphic.setMinSize(Region.USE_PREF_SIZE, Region.USE_PREF_SIZE);
        button.setGraphic(graphic);
    }
    
    /**
     * Set graphic for a toggle button with dark mode support.
     */
    private void setToggleButtonGraphic(ToggleButton button, Pane graphic) {
        graphic.getStyleClass().add("toolbar-icon");
        graphic.setMaxSize(Region.USE_PREF_SIZE, Region.USE_PREF_SIZE);
        graphic.setMinSize(Region.USE_PREF_SIZE, Region.USE_PREF_SIZE);
        button.setGraphic(graphic);
    }
    
    // Icon creation methods for dark mode compatibility
    
    private Pane createNewModelIcon(int size) {
        Pane pane = new Pane();
        pane.setPrefSize(size, size);
        pane.setMaxSize(size, size);
        pane.setMinSize(size, size);
        
        // Center the content
        double centerX = size / 2.0;
        double centerY = size / 2.0;
        
        // Folder icon - centered
        Rectangle folder = new Rectangle(size * 0.6, size * 0.45);
        folder.setFill(Color.TRANSPARENT);
        folder.setStroke(Color.WHITE);
        folder.setStrokeWidth(1.2);
        folder.setLayoutX(centerX - folder.getWidth() / 2);
        folder.setLayoutY(centerY - folder.getHeight() / 2 + size * 0.05);
        
        // Plus sign in corner
        double plusSize = size * 0.15;
        Line plusH = new Line(size * 0.75 - plusSize/2, size * 0.25, size * 0.75 + plusSize/2, size * 0.25);
        Line plusV = new Line(size * 0.75, size * 0.25 - plusSize/2, size * 0.75, size * 0.25 + plusSize/2);
        plusH.setStroke(Color.WHITE);
        plusV.setStroke(Color.WHITE);
        plusH.setStrokeWidth(1.5);
        plusV.setStrokeWidth(1.5);
        
        pane.getChildren().addAll(folder, plusH, plusV);
        return pane;
    }
    
    private Pane createOpenModelIcon(int size) {
        Pane pane = new Pane();
        pane.setPrefSize(size, size);
        pane.setMaxSize(size, size);
        pane.setMinSize(size, size);
        
        double centerX = size / 2.0;
        double centerY = size / 2.0;
        
        // Folder icon - centered
        Rectangle folder = new Rectangle(size * 0.6, size * 0.45);
        folder.setFill(Color.TRANSPARENT);
        folder.setStroke(Color.WHITE);
        folder.setStrokeWidth(1.2);
        folder.setLayoutX(centerX - folder.getWidth() / 2);
        folder.setLayoutY(centerY - folder.getHeight() / 2 + size * 0.05);
        
        // Open tab
        Rectangle tab = new Rectangle(size * 0.25, size * 0.12);
        tab.setFill(Color.TRANSPARENT);
        tab.setStroke(Color.WHITE);
        tab.setStrokeWidth(1.2);
        tab.setLayoutX(centerX - tab.getWidth() / 2 - size * 0.1);
        tab.setLayoutY(centerY - tab.getHeight() / 2 - size * 0.15);
        
        pane.getChildren().addAll(folder, tab);
        return pane;
    }
    
    private Pane createSaveModelIcon(int size) {
        Pane pane = new Pane();
        pane.setPrefSize(size, size);
        pane.setMaxSize(size, size);
        pane.setMinSize(size, size);
        
        double centerX = size / 2.0;
        double centerY = size / 2.0;
        
        // Floppy disk shape - centered
        Rectangle disk = new Rectangle(size * 0.5, size * 0.6);
        disk.setFill(Color.TRANSPARENT);
        disk.setStroke(Color.WHITE);
        disk.setStrokeWidth(1.2);
        disk.setLayoutX(centerX - disk.getWidth() / 2);
        disk.setLayoutY(centerY - disk.getHeight() / 2);
        
        Rectangle label = new Rectangle(size * 0.3, size * 0.15);
        label.setFill(Color.WHITE);
        label.setLayoutX(centerX - label.getWidth() / 2);
        label.setLayoutY(centerY - label.getHeight() / 2 - size * 0.15);
        
        pane.getChildren().addAll(disk, label);
        return pane;
    }
    
    private Pane createZoomInIcon(int size) {
        Pane pane = new Pane();
        pane.setPrefSize(size, size);
        pane.setMaxSize(size, size);
        pane.setMinSize(size, size);
        
        double centerX = size / 2.0;
        double centerY = size / 2.0;
        
        // Magnifying glass circle - centered and properly sized
        double radius = size * 0.25;
        Circle magnifier = new Circle(radius);
        magnifier.setFill(Color.TRANSPARENT);
        magnifier.setStroke(Color.WHITE);
        magnifier.setStrokeWidth(1.2);
        magnifier.setCenterX(centerX - size * 0.05);
        magnifier.setCenterY(centerY - size * 0.05);
        
        // Handle positioned correctly
        double handleStartX = magnifier.getCenterX() + radius * 0.7;
        double handleStartY = magnifier.getCenterY() + radius * 0.7;
        Line handle = new Line(handleStartX, handleStartY, 
                             handleStartX + size * 0.15, handleStartY + size * 0.15);
        handle.setStroke(Color.WHITE);
        handle.setStrokeWidth(1.5);
        
        // Plus sign inside circle - properly centered
        double plusSize = radius * 0.6;
        Line plusH = new Line(magnifier.getCenterX() - plusSize/2, magnifier.getCenterY(), 
                             magnifier.getCenterX() + plusSize/2, magnifier.getCenterY());
        Line plusV = new Line(magnifier.getCenterX(), magnifier.getCenterY() - plusSize/2,
                             magnifier.getCenterX(), magnifier.getCenterY() + plusSize/2);
        plusH.setStroke(Color.WHITE);
        plusV.setStroke(Color.WHITE);
        plusH.setStrokeWidth(1.2);
        plusV.setStrokeWidth(1.2);
        
        pane.getChildren().addAll(magnifier, handle, plusH, plusV);
        return pane;
    }
    
    private Pane createZoomOutIcon(int size) {
        Pane pane = new Pane();
        pane.setPrefSize(size, size);
        pane.setMaxSize(size, size);
        pane.setMinSize(size, size);
        
        double centerX = size / 2.0;
        double centerY = size / 2.0;
        
        // Magnifying glass circle - centered and properly sized
        double radius = size * 0.25;
        Circle magnifier = new Circle(radius);
        magnifier.setFill(Color.TRANSPARENT);
        magnifier.setStroke(Color.WHITE);
        magnifier.setStrokeWidth(1.2);
        magnifier.setCenterX(centerX - size * 0.05);
        magnifier.setCenterY(centerY - size * 0.05);
        
        // Handle positioned correctly
        double handleStartX = magnifier.getCenterX() + radius * 0.7;
        double handleStartY = magnifier.getCenterY() + radius * 0.7;
        Line handle = new Line(handleStartX, handleStartY, 
                             handleStartX + size * 0.15, handleStartY + size * 0.15);
        handle.setStroke(Color.WHITE);
        handle.setStrokeWidth(1.5);
        
        // Minus sign inside circle - properly centered
        double minusSize = radius * 0.6;
        Line minus = new Line(magnifier.getCenterX() - minusSize/2, magnifier.getCenterY(), 
                             magnifier.getCenterX() + minusSize/2, magnifier.getCenterY());
        minus.setStroke(Color.WHITE);
        minus.setStrokeWidth(1.2);
        
        pane.getChildren().addAll(magnifier, handle, minus);
        return pane;
    }
    
    private Pane createResetViewIcon(int size) {
        Pane pane = new Pane();
        pane.setPrefSize(size, size);
        pane.setMaxSize(size, size);
        pane.setMinSize(size, size);
        
        double centerX = size / 2.0;
        double centerY = size / 2.0;
        
        // Circular arrow - centered
        Circle circle = new Circle(size * 0.25);
        circle.setFill(Color.TRANSPARENT);
        circle.setStroke(Color.WHITE);
        circle.setStrokeWidth(1.2);
        circle.setCenterX(centerX);
        circle.setCenterY(centerY);
        
        // Arrow pointing clockwise
        Polygon arrow = new Polygon();
        double arrowSize = size * 0.1;
        arrow.getPoints().addAll(new Double[]{
            centerX + size * 0.25, centerY - size * 0.05,  // tip
            centerX + size * 0.15, centerY - size * 0.15,  // left
            centerX + size * 0.15, centerY - size * 0.1,   // inner left
            centerX + size * 0.2, centerY - size * 0.1,    // inner right
            centerX + size * 0.2, centerY + size * 0.05,   // bottom
            centerX + size * 0.25, centerY + size * 0.05   // right
        });
        arrow.setFill(Color.WHITE);
        
        pane.getChildren().addAll(circle, arrow);
        return pane;
    }
    
    private Pane createFitToViewIcon(int size) {
        Pane pane = new Pane();
        pane.setPrefSize(size, size);
        
        Rectangle outer = new Rectangle(size * 0.8, size * 0.8);
        outer.setFill(Color.TRANSPARENT);
        outer.setStroke(Color.WHITE);
        outer.setStrokeWidth(1.5);
        outer.setLayoutX(size * 0.1);
        outer.setLayoutY(size * 0.1);
        
        Rectangle inner = new Rectangle(size * 0.4, size * 0.4);
        inner.setFill(Color.WHITE);
        inner.setLayoutX(size * 0.3);
        inner.setLayoutY(size * 0.3);
        
        pane.getChildren().addAll(outer, inner);
        return pane;
    }
    
    private Pane createWireframeIcon(int size) {
        Pane pane = new Pane();
        pane.setPrefSize(size, size);
        
        // Wireframe cube
        Line[] lines = {
            new Line(size * 0.2, size * 0.3, size * 0.7, size * 0.3), // top front
            new Line(size * 0.7, size * 0.3, size * 0.7, size * 0.8), // right front
            new Line(size * 0.7, size * 0.8, size * 0.2, size * 0.8), // bottom front
            new Line(size * 0.2, size * 0.8, size * 0.2, size * 0.3), // left front
            new Line(size * 0.3, size * 0.2, size * 0.8, size * 0.2), // top back
            new Line(size * 0.8, size * 0.2, size * 0.8, size * 0.7), // right back
            new Line(size * 0.8, size * 0.7, size * 0.3, size * 0.7), // bottom back
            new Line(size * 0.3, size * 0.7, size * 0.3, size * 0.2), // left back
            new Line(size * 0.2, size * 0.3, size * 0.3, size * 0.2), // connect 1
            new Line(size * 0.7, size * 0.3, size * 0.8, size * 0.2), // connect 2
            new Line(size * 0.7, size * 0.8, size * 0.8, size * 0.7), // connect 3
            new Line(size * 0.2, size * 0.8, size * 0.3, size * 0.7)  // connect 4
        };
        
        for (Line line : lines) {
            line.setStroke(Color.WHITE);
            line.setStrokeWidth(1);
        }
        
        pane.getChildren().addAll(lines);
        return pane;
    }
    
    private Pane createGridIcon(int size) {
        Pane pane = new Pane();
        pane.setPrefSize(size, size);
        pane.setMaxSize(size, size);
        pane.setMinSize(size, size);
        
        // Grid lines - centered and properly spaced
        double gridSize = size * 0.6;
        double startX = (size - gridSize) / 2;
        double startY = (size - gridSize) / 2;
        
        // Create 3x3 grid
        for (int i = 0; i <= 3; i++) {
            // Vertical lines
            Line vLine = new Line(startX + i * gridSize / 3, startY, 
                                 startX + i * gridSize / 3, startY + gridSize);
            vLine.setStroke(Color.WHITE);
            vLine.setStrokeWidth(1);
            pane.getChildren().add(vLine);
            
            // Horizontal lines  
            Line hLine = new Line(startX, startY + i * gridSize / 3,
                                 startX + gridSize, startY + i * gridSize / 3);
            hLine.setStroke(Color.WHITE);
            hLine.setStrokeWidth(1);
            pane.getChildren().add(hLine);
        }
        
        return pane;
    }
    
    private Pane createAxesIcon(int size) {
        Pane pane = new Pane();
        pane.setPrefSize(size, size);
        
        // X axis (red)
        Line xAxis = new Line(size * 0.5, size * 0.5, size * 0.8, size * 0.5);
        xAxis.setStroke(Color.rgb(255, 100, 100));
        xAxis.setStrokeWidth(2);
        
        // Y axis (green)
        Line yAxis = new Line(size * 0.5, size * 0.5, size * 0.5, size * 0.2);
        yAxis.setStroke(Color.rgb(100, 255, 100));
        yAxis.setStrokeWidth(2);
        
        // Z axis (blue)
        Line zAxis = new Line(size * 0.5, size * 0.5, size * 0.3, size * 0.7);
        zAxis.setStroke(Color.rgb(100, 100, 255));
        zAxis.setStrokeWidth(2);
        
        pane.getChildren().addAll(xAxis, yAxis, zAxis);
        return pane;
    }
    
    private Pane createValidateIcon(int size) {
        Pane pane = new Pane();
        pane.setPrefSize(size, size);
        
        Circle circle = new Circle(size * 0.4);
        circle.setFill(Color.TRANSPARENT);
        circle.setStroke(Color.LIGHTGREEN);
        circle.setStrokeWidth(2);
        circle.setCenterX(size * 0.5);
        circle.setCenterY(size * 0.5);
        
        // Check mark
        Polyline check = new Polyline();
        check.getPoints().addAll(new Double[]{
            size * 0.35, size * 0.5,
            size * 0.45, size * 0.6,
            size * 0.65, size * 0.4
        });
        check.setStroke(Color.LIGHTGREEN);
        check.setStrokeWidth(2);
        check.setFill(Color.TRANSPARENT);
        
        pane.getChildren().addAll(circle, check);
        return pane;
    }
    
    private Pane createTextureIcon(int size) {
        Pane pane = new Pane();
        pane.setPrefSize(size, size);
        
        Rectangle rect1 = new Rectangle(size * 0.3, size * 0.3);
        rect1.setFill(Color.rgb(255, 200, 100));
        rect1.setLayoutX(size * 0.1);
        rect1.setLayoutY(size * 0.1);
        
        Rectangle rect2 = new Rectangle(size * 0.3, size * 0.3);
        rect2.setFill(Color.rgb(100, 200, 255));
        rect2.setLayoutX(size * 0.4);
        rect2.setLayoutY(size * 0.4);
        
        Rectangle rect3 = new Rectangle(size * 0.25, size * 0.25);
        rect3.setFill(Color.rgb(200, 100, 255));
        rect3.setLayoutX(size * 0.6);
        rect3.setLayoutY(size * 0.2);
        
        pane.getChildren().addAll(rect1, rect2, rect3);
        return pane;
    }
    
    private Pane createSettingsIcon(int size) {
        Pane pane = new Pane();
        pane.setPrefSize(size, size);
        
        Circle center = new Circle(size * 0.15);
        center.setFill(Color.TRANSPARENT);
        center.setStroke(Color.WHITE);
        center.setStrokeWidth(1.5);
        center.setCenterX(size * 0.5);
        center.setCenterY(size * 0.5);
        
        // Gear teeth (simplified)
        Rectangle[] teeth = new Rectangle[8];
        for (int i = 0; i < 8; i++) {
            teeth[i] = new Rectangle(size * 0.06, size * 0.15);
            teeth[i].setFill(Color.WHITE);
            double angle = i * 45;
            double x = size * 0.5 + Math.cos(Math.toRadians(angle)) * size * 0.35 - size * 0.03;
            double y = size * 0.5 + Math.sin(Math.toRadians(angle)) * size * 0.35 - size * 0.075;
            teeth[i].setLayoutX(x);
            teeth[i].setLayoutY(y);
            teeth[i].setRotate(angle);
        }
        
        pane.getChildren().add(center);
        pane.getChildren().addAll(teeth);
        return pane;
    }
    
    private Pane createPlayIcon(int size) {
        Pane pane = new Pane();
        pane.setPrefSize(size, size);
        
        Polygon triangle = new Polygon();
        triangle.getPoints().addAll(new Double[]{
            size * 0.3, size * 0.2,
            size * 0.3, size * 0.8,
            size * 0.8, size * 0.5
        });
        triangle.setFill(Color.LIGHTGREEN);
        
        pane.getChildren().add(triangle);
        return pane;
    }
    
    private Pane createPauseIcon(int size) {
        Pane pane = new Pane();
        pane.setPrefSize(size, size);
        
        Rectangle bar1 = new Rectangle(size * 0.15, size * 0.6);
        bar1.setFill(Color.ORANGE);
        bar1.setLayoutX(size * 0.3);
        bar1.setLayoutY(size * 0.2);
        
        Rectangle bar2 = new Rectangle(size * 0.15, size * 0.6);
        bar2.setFill(Color.ORANGE);
        bar2.setLayoutX(size * 0.55);
        bar2.setLayoutY(size * 0.2);
        
        pane.getChildren().addAll(bar1, bar2);
        return pane;
    }
    
    private Pane createStopIcon(int size) {
        Pane pane = new Pane();
        pane.setPrefSize(size, size);
        
        Rectangle square = new Rectangle(size * 0.6, size * 0.6);
        square.setFill(Color.LIGHTCORAL);
        square.setLayoutX(size * 0.2);
        square.setLayoutY(size * 0.2);
        
        pane.getChildren().add(square);
        return pane;
    }
}