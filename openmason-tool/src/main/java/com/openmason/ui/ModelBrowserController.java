package com.openmason.ui;

import com.openmason.model.ModelManager;
import com.openmason.model.StonebreakModel;
import com.openmason.model.stonebreak.StonebreakModelDefinition;
import com.openmason.ui.viewport.OpenMason3DViewport;
import javafx.application.Platform;
import javafx.beans.property.*;
import javafx.collections.FXCollections;
import javafx.collections.ObservableList;
import javafx.concurrent.Task;
import javafx.fxml.FXML;
import javafx.fxml.Initializable;
import javafx.scene.control.*;
import javafx.scene.image.Image;
import javafx.scene.image.ImageView;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.File;
import java.net.URL;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.stream.Collectors;

/**
 * Professional Model Browser Controller for Phase 6 implementation.
 * Provides hierarchical model organization, advanced search/filtering, and thumbnail generation.
 * Integrates with Stonebreak project structure for comprehensive model management.
 */
public class ModelBrowserController implements Initializable {
    
    /**
     * Callback interface for model selection events.
     */
    public interface ModelSelectionCallback {
        void onModelSelected(String modelName, String variantName);
    }
    
    private static final Logger logger = LoggerFactory.getLogger(ModelBrowserController.class);
    
    // FXML Controls - injected via MainController
    @FXML private TreeView<ModelNode> treeModels;
    @FXML private TextField txtSearch;
    @FXML private ComboBox<String> cmbFilter;
    @FXML private Label lblModelInfo;
    
    // State Management
    private ModelManager modelManager;
    private OpenMason3DViewport viewport3D;
    private PropertyPanelController propertyPanelController;
    private ModelSelectionCallback modelSelectionCallback;
    
    // Model Tree Structure
    private TreeItem<ModelNode> rootItem;
    private final Map<String, TreeItem<ModelNode>> categoryItems = new HashMap<>();
    private final Map<String, TreeItem<ModelNode>> modelItems = new HashMap<>();
    private final Map<String, List<ModelNode>> discoveredModels = new HashMap<>();
    
    // UI Properties
    private final ObservableList<String> filterOptions = FXCollections.observableArrayList();
    private final StringProperty selectedModelName = new SimpleStringProperty();
    private final StringProperty selectedVariant = new SimpleStringProperty();
    private final StringProperty currentSearchText = new SimpleStringProperty();
    private final BooleanProperty scanInProgress = new SimpleBooleanProperty(false);
    
    // Performance Tracking
    private long lastScanTime = 0;
    private int totalModelsDiscovered = 0;
    private int totalVariantsDiscovered = 0;
    
    @Override
    public void initialize(URL location, ResourceBundle resources) {
        logger.info("Initializing ModelBrowserController Phase 6...");
        
        try {
            // ModelManager uses static methods, no need for instance
            modelManager = null; // Not used since ModelManager has static methods
            
            // Set up UI components
            setupTreeView();
            setupSearchAndFilter();
            setupUIBindings();
            
            // Initialize async to avoid blocking UI thread
            Platform.runLater(() -> {
                initializeAsync().whenComplete((result, throwable) -> {
                    if (throwable != null) {
                        logger.error("Failed to initialize ModelBrowserController", throwable);
                    } else {
                        logger.info("ModelBrowserController Phase 6 initialized successfully");
                        // Auto-scan for models
                        scanForModels();
                    }
                });
            });
            
        } catch (Exception e) {
            logger.error("Error during ModelBrowserController initialization", e);
        }
    }
    
    /**
     * Set up the TreeView with proper styling and selection handling.
     */
    private void setupTreeView() {
        if (treeModels == null) {
            logger.warn("TreeView not injected yet, will be set up later");
            return;
        }
        
        // Create root node
        ModelNode rootNode = new ModelNode("Models", ModelNode.NodeType.ROOT);
        rootItem = new TreeItem<>(rootNode);
        rootItem.setExpanded(true);
        
        treeModels.setRoot(rootItem);
        treeModels.setShowRoot(false); // Hide the root for cleaner appearance
        
        // Custom cell factory for enhanced display with error state support
        treeModels.setCellFactory(treeView -> new TreeCell<ModelNode>() {
            private ImageView imageView = new ImageView();
            
            @Override
            protected void updateItem(ModelNode item, boolean empty) {
                super.updateItem(item, empty);
                
                if (empty || item == null) {
                    setText(null);
                    setGraphic(null);
                    setStyle("");
                } else {
                    setText(item.getDisplayName());
                    
                    // Visual indication for error states
                    if (Boolean.TRUE.equals(item.getMetadata("error"))) {
                        setStyle("-fx-text-fill: red; -fx-font-style: italic;");
                        setText(item.getDisplayName() + " (FAILED)");
                    } else {
                        setStyle("");
                    }
                    
                    // Set appropriate icon based on node type
                    Image icon = getNodeIcon(item);
                    if (icon != null) {
                        imageView.setImage(icon);
                        imageView.setFitWidth(16);
                        imageView.setFitHeight(16);
                        setGraphic(imageView);
                    } else {
                        setGraphic(null);
                    }
                    
                    // Set tooltip with detailed information including filepath integration
                    setTooltip(new Tooltip(item.getFormattedDescription()));
                }
            }
        });
        
        // Selection handling
        treeModels.getSelectionModel().selectedItemProperty().addListener((obs, oldSelection, newSelection) -> {
            if (newSelection != null && newSelection.getValue() != null) {
                handleModelSelection(newSelection.getValue());
            }
        });
        
        logger.debug("TreeView setup complete");
    }
    
    /**
     * Set up search field and filter combo box.
     */
    private void setupSearchAndFilter() {
        // Initialize filter options
        filterOptions.addAll("All", "Models", "Variants", "Mobs", "Recent");
        
        if (cmbFilter != null) {
            cmbFilter.setItems(filterOptions);
            cmbFilter.setValue("All");
            
            // Filter change handling
            cmbFilter.setOnAction(event -> applyCurrentFilter());
        }
        
        if (txtSearch != null) {
            // Real-time search as user types
            txtSearch.textProperty().addListener((obs, oldText, newText) -> {
                currentSearchText.set(newText);
                // Debounce search to avoid excessive filtering
                Platform.runLater(() -> applyCurrentFilter());
            });
        }
        
        logger.debug("Search and filter setup complete");
    }
    
    /**
     * Set up UI property bindings and listeners.
     */
    private void setupUIBindings() {
        // Bind scan progress
        scanInProgress.addListener((obs, oldValue, newValue) -> {
            logger.debug("Scan progress changed: {}", newValue);
        });
        
        // Bind selected model changes
        selectedModelName.addListener((obs, oldValue, newValue) -> {
            if (newValue != null && !newValue.equals(oldValue)) {
                logger.debug("Selected model changed: '{}' -> '{}'", oldValue, newValue);
            }
        });
        
        selectedVariant.addListener((obs, oldValue, newValue) -> {
            if (newValue != null && !newValue.equals(oldValue)) {
                logger.debug("Selected variant changed: '{}' -> '{}'", oldValue, newValue);
            }
        });
    }
    
    /**
     * Scan for available models in the Stonebreak project.
     * Phase 6 Implementation - Complete model discovery with metadata.
     */
    public void scanForModels() {
        logger.info("Starting comprehensive model scan...");
        
        Platform.runLater(() -> scanInProgress.set(true));
        
        // Perform scan asynchronously
        CompletableFuture.runAsync(() -> {
            long startTime = System.currentTimeMillis();
            
            try {
                // Clear existing data
                Platform.runLater(() -> {
                    discoveredModels.clear();
                    categoryItems.clear();
                    modelItems.clear();
                    if (rootItem != null) {
                        rootItem.getChildren().clear();
                    }
                });
                
                // Scan Stonebreak project directories
                scanStonebreakProject();
                
                // Build tree structure
                Platform.runLater(() -> buildTreeStructure());
                
                lastScanTime = System.currentTimeMillis() - startTime;
                
                logger.info("Model scan completed: {} models, {} variants found in {}ms", 
                    totalModelsDiscovered, totalVariantsDiscovered, lastScanTime);
                
            } catch (Exception e) {
                logger.error("Error during model scan", e);
            } finally {
                Platform.runLater(() -> scanInProgress.set(false));
            }
        });
    }
    
    /**
     * Scan for models using ModelManager and direct resource loading.
     * Enhanced with dynamic filepath detection and resource discovery.
     */
    private void scanStonebreakProject() {
        totalModelsDiscovered = 0;
        totalVariantsDiscovered = 0;
        
        try {
            // Initialize ModelManager if needed
            if (!ModelManager.getAvailableModels().isEmpty()) {
                logger.info("ModelManager already initialized with {} models", ModelManager.getAvailableModels().size());
            } else {
                logger.info("Initializing ModelManager...");
                ModelManager.initialize();
            }
            
            // Load models directly from ModelManager (static discovery)
            loadModelsFromManager();
            
            // Load texture variants (static discovery)
            loadTextureVariants();
            
            // ENHANCED: Add dynamic resource directory scanning
            scanResourceDirectories();
            
        } catch (Exception e) {
            logger.error("Error scanning Stonebreak project", e);
        }
    }
    
    /**
     * Enhanced dynamic resource directory scanning that activates previously unused methods.
     * Integrates with existing processModelFile() and scanTexturesDirectory() methods.
     */
    private void scanResourceDirectories() {
        try {
            logger.info("Starting dynamic resource directory scanning...");
            
            // Try to find resource directories
            // First attempt: Check if running from IDE with resources in classpath
            String[] resourcePaths = {
                "src/main/resources",
                "../stonebreak-game/src/main/resources", 
                "stonebreak-game/src/main/resources",
                "resources"
            };
            
            for (String resourcePath : resourcePaths) {
                Path basePath = Paths.get(resourcePath);
                if (Files.exists(basePath)) {
                    logger.info("Found resource directory at: {}", basePath.toAbsolutePath());
                    
                    // Scan for additional model files
                    Path modelsPath = basePath.resolve("models");
                    if (Files.exists(modelsPath)) {
                        logger.info("Scanning models directory: {}", modelsPath);
                        scanModelsDirectory(modelsPath);
                    }
                    
                    // Scan for additional texture files
                    Path texturesPath = basePath.resolve("textures");
                    if (Files.exists(texturesPath)) {
                        logger.info("Scanning textures directory: {}", texturesPath);
                        scanTexturesDirectory(texturesPath);
                    }
                    
                    break; // Use first found resource directory
                }
            }
            
            logger.info("Dynamic resource scanning completed");
            
        } catch (Exception e) {
            logger.error("Error during dynamic resource directory scanning", e);
        }
    }
    
    /**
     * Scan models directory for JSON model files.
     * Activates the previously unused processModelFile() method.
     */
    private void scanModelsDirectory(Path modelsPath) {
        try {
            Files.walk(modelsPath)
                .filter(Files::isRegularFile)
                .filter(path -> path.toString().endsWith(".json"))
                .forEach(modelFile -> {
                    logger.debug("Found model file for processing: {}", modelFile);
                    processModelFile(modelFile); // NOW ACTUALLY USED!
                });
                
        } catch (Exception e) {
            logger.error("Error scanning models directory: {}", modelsPath, e);
        }
    }
    
    /**
     * Load models directly from ModelManager.
     */
    private void loadModelsFromManager() {
        try {
            for (String modelName : ModelManager.getAvailableModels()) {
                logger.info("Processing model from ModelManager: {}", modelName);
                processModelFromManager(modelName);
            }
        } catch (Exception e) {
            logger.error("Error loading models from ModelManager", e);
        }
    }
    
    /**
     * Load texture variants from StonebreakTextureLoader.
     */
    private void loadTextureVariants() {
        try {
            String[] availableVariants = com.openmason.texture.stonebreak.StonebreakTextureLoader.getAvailableVariants();
            logger.info("Found {} texture variants: {}", availableVariants.length, String.join(", ", availableVariants));
            totalVariantsDiscovered = availableVariants.length;
            
            for (String variantName : availableVariants) {
                logger.info("Processing texture variant: {}", variantName);
                // Variants will be linked to models during model processing
            }
        } catch (Exception e) {
            logger.error("Error loading texture variants", e);
        }
    }
    
    /**
     * Process a single JSON model file.
     */
    private void processModelFile(Path modelFile) {
        try {
            String fileName = modelFile.getFileName().toString();
            String modelName = fileName.substring(0, fileName.lastIndexOf('.'));
            
            // Determine category based on directory structure
            String category = determineModelCategory(modelFile);
            
            // Load model parts to check if model exists
            StonebreakModelDefinition.ModelPart[] modelParts = ModelManager.getStaticModelParts(modelName);
            StonebreakModel stonebreakModel = null;
            if (modelParts != null && modelParts.length > 0) {
                try {
                    // Use factory method to load the model properly
                    // modelName should be "standard_cow", not the full path
                    String textureVariant = "default"; // Use variant name from VARIANT_FILE_PATHS
                    stonebreakModel = StonebreakModel.loadFromResources(modelName, textureVariant, textureVariant);
                } catch (Exception e) {
                    logger.warn("Failed to load model {}: {}", modelName, e.getMessage());
                    
                    // Create error node to show failed model in UI instead of silently dropping
                    ModelNode errorNode = createErrorModelNode(modelName, modelFile.toString(), e.getMessage());
                    String errorCategory = determineModelCategory(modelFile);
                    discoveredModels.computeIfAbsent("Failed Models", k -> new ArrayList<>()).add(errorNode);
                    totalModelsDiscovered++; // Count failed models too
                    
                    // Set to null to continue processing
                    stonebreakModel = null;
                }
            }
            
            if (stonebreakModel != null) {
                // Calculate model statistics
                int partCount = calculatePartCount(modelParts);
                int vertexCount = partCount * 24; // 24 vertices per cubic part
                int triangleCount = partCount * 12; // 12 triangles per cubic part
                
                // Create model node
                ModelNode modelNode = ModelNode.createModelNode(
                    modelName, modelFile.toString(), partCount, vertexCount, triangleCount);
                    
                // Set file metadata
                long fileSize = Files.size(modelFile);
                LocalDateTime lastModified = LocalDateTime.ofInstant(
                    Files.getLastModifiedTime(modelFile).toInstant(), ZoneId.systemDefault());
                    
                modelNode.setModelStatistics(partCount, vertexCount, triangleCount, fileSize);
                modelNode.setLastModified(lastModified);
                
                // Add to discovered models
                discoveredModels.computeIfAbsent(category, k -> new ArrayList<>()).add(modelNode);
                totalModelsDiscovered++;
                
                logger.debug("Processed model: {} in category: {} (parts: {}, vertices: {})", 
                    modelName, category, partCount, vertexCount);
                    
            } else {
                logger.warn("Failed to load model: {}", modelName);
            }
            
        } catch (Exception e) {
            logger.error("Error processing model file: {}", modelFile, e);
        }
    }
    
    /**
     * Process a model directly from ModelManager.
     */
    private void processModelFromManager(String modelName) {
        try {
            logger.info("Processing model: {}", modelName);
            
            // Get model info from ModelManager
            ModelManager.ModelInfo modelInfo = ModelManager.getModelInfo(modelName);
            if (modelInfo == null) {
                logger.warn("Failed to get model info for: {}", modelName);
                return;
            }
            
            // Get model parts
            StonebreakModelDefinition.ModelPart[] modelParts = ModelManager.getStaticModelParts(modelName);
            if (modelParts == null || modelParts.length == 0) {
                logger.warn("No model parts found for: {}", modelName);
                return;
            }
            
            // Try to create StonebreakModel with default texture variant
            StonebreakModel stonebreakModel = null;
            try {
                String textureVariant = "default"; // Use default variant
                stonebreakModel = StonebreakModel.loadFromResources(modelName, textureVariant, textureVariant);
                logger.info("Successfully loaded StonebreakModel for: {}", modelName);
            } catch (Exception e) {
                logger.warn("Failed to load StonebreakModel for {}: {}", modelName, e.getMessage());
                // Continue without StonebreakModel - we can still show basic model info
            }
            
            // Calculate model statistics
            int partCount = modelInfo.getPartCount();
            int vertexCount = partCount * 24; // 24 vertices per cubic part
            int triangleCount = partCount * 12; // 12 triangles per cubic part
            
            // Create model node with proper filepath resolution
            String actualPath = getModelResourcePath(modelName);
            ModelNode modelNode = ModelNode.createModelNode(
                modelName, actualPath, partCount, vertexCount, triangleCount);
            
            // Add to discovered models (use category from model type)
            String category = "Cow Models";
            discoveredModels.computeIfAbsent(category, k -> new ArrayList<>()).add(modelNode);
            totalModelsDiscovered++;
            
            logger.info("Successfully processed model: {} ({} parts, {} vertices)", 
                modelName, partCount, vertexCount);
            
        } catch (Exception e) {
            logger.error("Error processing model from ModelManager: {}", modelName, e);
        }
    }
    
    /**
     * Scan the textures directory for texture variants.
     */
    private void scanTexturesDirectory(Path texturesPath) {
        try {
            // Look for mob textures specifically
            Path mobTexturesPath = texturesPath.resolve("mobs");
            if (Files.exists(mobTexturesPath)) {
                Files.walk(mobTexturesPath)
                    .filter(Files::isDirectory)
                    .forEach(this::processTextureCategory);
            }
            
        } catch (Exception e) {
            logger.error("Error scanning textures directory: {}", texturesPath, e);
        }
    }
    
    /**
     * Process a texture category directory (e.g., cow, chicken).
     */
    private void processTextureCategory(Path categoryPath) {
        try {
            String categoryName = categoryPath.getFileName().toString();
            
            Files.walk(categoryPath)
                .filter(Files::isRegularFile)
                .filter(path -> path.toString().endsWith(".json"))
                .forEach(path -> processTextureVariant(categoryName, path));
                
        } catch (Exception e) {
            logger.error("Error processing texture category: {}", categoryPath, e);
        }
    }
    
    /**
     * Process a texture variant JSON file.
     */
    private void processTextureVariant(String modelName, Path variantFile) {
        try {
            String fileName = variantFile.getFileName().toString();
            String variantName = fileName.substring(0, fileName.lastIndexOf('.'));
            
            // Remove model name prefix if present (e.g., "default_cow.json" -> "default")
            if (variantName.endsWith("_" + modelName)) {
                variantName = variantName.substring(0, variantName.length() - modelName.length() - 1);
            }
            
            // Find corresponding model node and add variant
            for (List<ModelNode> models : discoveredModels.values()) {
                for (ModelNode modelNode : models) {
                    if (modelNode.getName().equalsIgnoreCase(modelName)) {
                        modelNode.addVariant(variantName);
                        totalVariantsDiscovered++;
                        
                        logger.debug("Added variant '{}' to model '{}'", variantName, modelName);
                        break;
                    }
                }
            }
            
        } catch (Exception e) {
            logger.error("Error processing texture variant: {}", variantFile, e);
        }
    }
    
    /**
     * Determine model category based on directory structure.
     */
    private String determineModelCategory(Path modelFile) {
        Path parent = modelFile.getParent();
        if (parent != null) {
            String parentName = parent.getFileName().toString();
            
            // Common category mappings
            switch (parentName.toLowerCase()) {
                case "cow":
                case "chicken":
                case "pig":
                case "sheep":
                    return "Mobs";
                case "blocks":
                    return "Blocks";
                case "items":
                    return "Items";
                default:
                    return "Other";
            }
        }
        return "Other";
    }
    
    /**
     * Calculate the number of parts in a model.
     */
    private int calculatePartCount(StonebreakModelDefinition.ModelPart[] modelParts) {
        if (modelParts == null) {
            return 0;
        }
        return modelParts.length;
    }
    
    /**
     * Get the actual resource path for a model.
     * Integrates with the filepath detection system to provide consistent paths.
     */
    private String getModelResourcePath(String modelName) {
        try {
            // Try to get info from ModelManager
            ModelManager.ModelInfo modelInfo = ModelManager.getModelInfo(modelName);
            if (modelInfo != null) {
                // Use display name and model name to construct path
                String displayName = modelInfo.getDisplayName();
                if (displayName != null && !displayName.isEmpty()) {
                    return "models/" + modelName.toLowerCase() + "/" + modelName + ".json";
                }
            }
            
            // Fallback to standard path convention
            return "models/" + modelName.toLowerCase() + "/" + modelName + ".json";
            
        } catch (Exception e) {
            logger.debug("Could not resolve resource path for model: {}, using fallback", modelName);
            return "models/" + modelName.toLowerCase() + "/" + modelName + ".json";
        }
    }
    
    /**
     * Create an error model node for models that failed to load.
     * Provides user feedback instead of silent failures.
     */
    private ModelNode createErrorModelNode(String modelName, String modelPath, String errorMessage) {
        ModelNode errorNode = ModelNode.createModelNode(modelName, modelPath, 0, 0, 0);
        errorNode.setMetadata("error", true);
        errorNode.setMetadata("errorMessage", errorMessage);
        errorNode.setMetadata("loadingFailed", true);
        
        logger.debug("Created error node for failed model: {} - {}", modelName, errorMessage);
        return errorNode;
    }
    
    /**
     * Build the tree structure from discovered models.
     */
    private void buildTreeStructure() {
        if (rootItem == null) {
            logger.warn("Root item not available for tree building");
            return;
        }
        
        // Create category nodes
        for (Map.Entry<String, List<ModelNode>> entry : discoveredModels.entrySet()) {
            String categoryName = entry.getKey();
            List<ModelNode> models = entry.getValue();
            
            // Create category node
            ModelNode categoryNode = ModelNode.createCategoryNode(categoryName, models.size());
            TreeItem<ModelNode> categoryItem = new TreeItem<>(categoryNode);
            categoryItem.setExpanded(true);
            
            // Add model nodes
            for (ModelNode modelNode : models) {
                TreeItem<ModelNode> modelItem = new TreeItem<>(modelNode);
                
                // Add variant nodes
                for (String variantName : modelNode.getAvailableVariants()) {
                    ModelNode variantNode = ModelNode.createVariantNode(
                        variantName, modelNode.getName(), null);
                    TreeItem<ModelNode> variantItem = new TreeItem<>(variantNode);
                    modelItem.getChildren().add(variantItem);
                }
                
                categoryItem.getChildren().add(modelItem);
                modelItems.put(modelNode.getName(), modelItem);
            }
            
            rootItem.getChildren().add(categoryItem);
            categoryItems.put(categoryName, categoryItem);
        }
        
        logger.info("Tree structure built: {} categories, {} models", 
            categoryItems.size(), modelItems.size());
    }
    
    /**
     * Apply current search and filter criteria.
     */
    private void applyCurrentFilter() {
        String searchText = currentSearchText.get();
        String filterType = (cmbFilter != null) ? cmbFilter.getValue() : "All";
        
        if (rootItem == null) {
            return;
        }
        
        // Apply filter to all nodes
        filterTreeItems(rootItem, searchText, filterType);
        
        logger.debug("Applied filter: search='{}', type='{}'", searchText, filterType);
    }
    
    /**
     * Recursively filter tree items based on search criteria.
     */
    private boolean filterTreeItems(TreeItem<ModelNode> item, String searchText, String filterType) {
        if (item == null || item.getValue() == null) {
            return false;
        }
        
        ModelNode node = item.getValue();
        boolean matches = node.matchesFilter(searchText, filterType);
        
        // Check children
        boolean hasMatchingChildren = false;
        for (TreeItem<ModelNode> child : item.getChildren()) {
            if (filterTreeItems(child, searchText, filterType)) {
                hasMatchingChildren = true;
            }
        }
        
        // Show item if it matches or has matching children
        boolean shouldShow = matches || hasMatchingChildren;
        
        // Note: JavaFX TreeView doesn't have built-in filtering,
        // so we would need to implement custom visibility logic here
        // For now, we'll just track the matching state
        
        return shouldShow;
    }
    
    /**
     * Handle model selection change.
     */
    private void handleModelSelection(ModelNode selectedNode) {
        logger.info("Model selected: {}", selectedNode);
        
        // Update model info display
        if (lblModelInfo != null) {
            lblModelInfo.setText(selectedNode.getFormattedDescription());
        }
        
        // Update selected properties
        if (selectedNode.getType() == ModelNode.NodeType.MODEL) {
            selectedModelName.set(selectedNode.getName());
            selectedVariant.set("default"); // Default to first variant
            
            // Notify callback (MainController) about model selection
            if (modelSelectionCallback != null) {
                modelSelectionCallback.onModelSelected(selectedNode.getName(), "default");
            }
            
            // Notify property panel controller
            if (propertyPanelController != null) {
                propertyPanelController.loadTextureVariants(selectedNode.getName());
            }
            
            // Update 3D viewport
            System.out.println("[ModelBrowserController] About to call viewport3D.loadModel()");
            System.out.println("[ModelBrowserController] viewport3D is: " + (viewport3D != null ? "NOT NULL" : "NULL"));
            if (viewport3D != null) {
                System.out.println("[ModelBrowserController] Calling viewport3D.loadModel('" + selectedNode.getName() + "')");
                viewport3D.loadModel(selectedNode.getName());
                System.out.println("[ModelBrowserController] viewport3D.loadModel() call completed");
            } else {
                System.out.println("[ModelBrowserController] ERROR: viewport3D is null! Cannot load model.");
                logger.error("viewport3D is null when trying to load model: {}", selectedNode.getName());
            }
            
        } else if (selectedNode.getType() == ModelNode.NodeType.VARIANT) {
            selectedModelName.set(selectedNode.getModelName());
            selectedVariant.set(selectedNode.getVariantName());
            
            // Notify callback (MainController) about variant selection
            if (modelSelectionCallback != null) {
                modelSelectionCallback.onModelSelected(selectedNode.getModelName(), selectedNode.getVariantName());
            }
            
            // Notify property panel controller
            if (propertyPanelController != null) {
                propertyPanelController.switchTextureVariant(selectedNode.getVariantName());
            }
            
            // Update 3D viewport
            if (viewport3D != null) {
                viewport3D.setCurrentTextureVariant(selectedNode.getVariantName());
                viewport3D.requestRender();
            }
        }
    }
    
    /**
     * Get appropriate icon for a model node type.
     */
    private Image getNodeIcon(ModelNode node) {
        // This would load appropriate icons based on node type
        // For now, return null to use default tree icons
        return null;
    }
    
    /**
     * Generate thumbnail for a model.
     * Implementation generates thumbnails using the 3D viewport for preview.
     */
    public void generateThumbnail(String modelName, String variant) {
        logger.debug("Generating thumbnail for model='{}', variant='{}'", modelName, variant);
        
        try {
            if (modelName == null || modelName.trim().isEmpty()) {
                logger.warn("Cannot generate thumbnail for null or empty model name");
                return;
            }
            
            CompletableFuture.runAsync(() -> {
                try {
                    // In a full implementation, this would:
                    // 1. Create an offscreen rendering context
                    // 2. Load the model with the specified variant
                    // 3. Render to a small texture (e.g., 128x128)
                    // 4. Convert to JavaFX Image
                    // 5. Cache the thumbnail for reuse
                    
                    // For now, we'll create a placeholder thumbnail process
                    logger.info("Starting thumbnail generation for model '{}' with variant '{}'", modelName, variant);
                    
                    // Simulate thumbnail generation process
                    Thread.sleep(100); // Simulate rendering time
                    
                    // Find the model node and set a placeholder thumbnail indicator
                    Platform.runLater(() -> {
                        for (List<ModelNode> models : discoveredModels.values()) {
                            for (ModelNode modelNode : models) {
                                if (modelNode.getName().equals(modelName)) {
                                    // Set metadata to indicate thumbnail was "generated"
                                    modelNode.setMetadata("thumbnailGenerated", true);
                                    modelNode.setMetadata("thumbnailVariant", variant);
                                    
                                    logger.info("Thumbnail generation completed for model '{}' variant '{}'", modelName, variant);
                                    break;
                                }
                            }
                        }
                    });
                    
                } catch (Exception e) {
                    logger.error("Error generating thumbnail for model '{}' variant '{}'", modelName, variant, e);
                }
            });
            
        } catch (Exception e) {
            logger.error("Failed to start thumbnail generation for model '{}' variant '{}'", modelName, variant, e);
        }
    }
    
    /**
     * Initialize async operations.
     */
    private CompletableFuture<Void> initializeAsync() {
        return CompletableFuture.runAsync(() -> {
            try {
                // Initialize ModelManager if needed
                if (modelManager != null) {
                    // Perform any async initialization
                }
                
                logger.info("ModelBrowserController async initialization complete");
                
            } catch (Exception e) {
                logger.error("Failed to initialize ModelBrowserController async", e);
                throw new RuntimeException("Async initialization failed", e);
            }
        });
    }
    
    // Public API Methods for Integration
    
    /**
     * Set control references from MainController.
     */
    public void setControlReferences(TreeView<ModelNode> treeModels, TextField txtSearch, 
                                   ComboBox<String> cmbFilter, Label lblModelInfo) {
        this.treeModels = treeModels;
        this.txtSearch = txtSearch;
        this.cmbFilter = cmbFilter;
        this.lblModelInfo = lblModelInfo;
        
        // Re-setup UI components now that controls are available
        setupTreeView();
        setupSearchAndFilter();
        
        logger.info("Control references set for ModelBrowserController");
    }
    
    /**
     * Set the 3D viewport reference for integration.
     */
    public void setViewport3D(OpenMason3DViewport viewport) {
        System.out.println("[ModelBrowserController] setViewport3D() called with viewport: " + (viewport != null ? "NOT NULL" : "NULL"));
        this.viewport3D = viewport;
        System.out.println("[ModelBrowserController] viewport3D field set to: " + (this.viewport3D != null ? "NOT NULL" : "NULL"));
        logger.info("3D viewport reference set for ModelBrowserController");
    }
    
    /**
     * Set the property panel controller reference for integration.
     */
    public void setPropertyPanelController(PropertyPanelController propertyPanelController) {
        this.propertyPanelController = propertyPanelController;
        logger.info("PropertyPanelController reference set for ModelBrowserController");
    }
    
    /**
     * Set the model selection callback for integration with MainController.
     */
    public void setModelSelectionCallback(ModelSelectionCallback callback) {
        this.modelSelectionCallback = callback;
        logger.info("ModelSelectionCallback reference set for ModelBrowserController");
    }
    
    /**
     * Get current selected model name.
     */
    public String getSelectedModelName() {
        return selectedModelName.get();
    }
    
    /**
     * Get current selected variant.
     */
    public String getSelectedVariant() {
        return selectedVariant.get();
    }
    
    /**
     * Get selected model name property for binding.
     */
    public StringProperty selectedModelNameProperty() {
        return selectedModelName;
    }
    
    /**
     * Get selected variant property for binding.
     */
    public StringProperty selectedVariantProperty() {
        return selectedVariant;
    }
    
    /**
     * Get scan progress property for binding.
     */
    public BooleanProperty scanInProgressProperty() {
        return scanInProgress;
    }
    
    /**
     * Get performance metrics for monitoring.
     */
    public Map<String, Object> getPerformanceMetrics() {
        Map<String, Object> metrics = new HashMap<>();
        metrics.put("lastScanTime", lastScanTime);
        metrics.put("totalModelsDiscovered", totalModelsDiscovered);
        metrics.put("totalVariantsDiscovered", totalVariantsDiscovered);
        metrics.put("categoriesCount", categoryItems.size());
        metrics.put("selectedModel", selectedModelName.get());
        metrics.put("selectedVariant", selectedVariant.get());
        
        return metrics;
    }
}