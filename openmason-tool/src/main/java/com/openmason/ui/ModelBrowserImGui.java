package com.openmason.ui;

import com.openmason.model.ModelManager;
import com.stonebreak.model.ModelDefinition;
import com.openmason.ui.viewport.OpenMason3DViewport;
import imgui.ImGui;
import imgui.flag.*;
import imgui.type.ImBoolean;
import imgui.type.ImString;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.stream.Collectors;

/**
 * Model node data structure for tree display
 */
class ModelNode {
    private final String modelName;
    private final String modelPath;
    private int partCount;
    private int vertexCount;
    private int triangleCount;
    private long fileSize;
    private LocalDateTime lastModified;
    private final Map<String, Object> metadata;
    private final Set<String> availableVariants;
    
    private ModelNode(String modelName, String modelPath, int partCount, int vertexCount, int triangleCount) {
        if (modelName == null || modelName.trim().isEmpty()) {
            throw new IllegalArgumentException("Model name cannot be null or empty");
        }
        if (modelPath == null || modelPath.trim().isEmpty()) {
            throw new IllegalArgumentException("Model path cannot be null or empty");
        }
        
        this.modelName = modelName.trim();
        this.modelPath = modelPath.trim();
        this.partCount = Math.max(0, partCount);
        this.vertexCount = Math.max(0, vertexCount);
        this.triangleCount = Math.max(0, triangleCount);
        this.lastModified = LocalDateTime.now();
        this.metadata = new HashMap<>();
        this.availableVariants = new LinkedHashSet<>();
        this.fileSize = 0;
    }

    public static ModelNode createModelNode(String modelName, String modelPath, int partCount, int vertexCount, int triangleCount) {
        return new ModelNode(modelName, modelPath, partCount, vertexCount, triangleCount);
    }

    // Core getters
    public String getName() { return modelName; }
    public String getModelName() { return modelName; }
    public String getModelPath() { return modelPath; }
    public int getPartCount() { return partCount; }
    public int getVertexCount() { return vertexCount; }
    public int getTriangleCount() { return triangleCount; }
    public LocalDateTime getLastModified() { return lastModified; }
    public Map<String, Object> getMetadata() { return metadata; }
    public Set<String> getAvailableVariants() { return new LinkedHashSet<>(availableVariants); }
    public long getFileSize() { return fileSize; }
    
    // Display methods
    public String getDisplayName() {
        // Capitalize first letter and replace underscores with spaces
        String display = modelName.replace("_", " ");
        if (display.length() > 0) {
            display = display.substring(0, 1).toUpperCase() + display.substring(1);
        }
        return display;
    }
    
    // State methods
    public boolean isRecentlyAdded() {
        return lastModified.isAfter(LocalDateTime.now().minusHours(24));
    }
    
    public boolean hasError() {
        return Boolean.TRUE.equals(metadata.get("error")) || Boolean.TRUE.equals(metadata.get("loadingFailed"));
    }
    
    public String getErrorMessage() {
        Object errorMsg = metadata.get("errorMessage");
        return errorMsg != null ? errorMsg.toString() : "Unknown error";
    }
    
    // Mutation methods
    public void setMetadata(String key, Object value) {
        if (key != null && !key.trim().isEmpty()) {
            metadata.put(key.trim(), value);
        }
    }
    
    public void addVariant(String variantName) {
        if (variantName != null && !variantName.trim().isEmpty()) {
            availableVariants.add(variantName.trim().toLowerCase());
        }
    }
    
    public void setModelStatistics(int partCount, int vertexCount, int triangleCount, long fileSize) {
        this.partCount = Math.max(0, partCount);
        this.vertexCount = Math.max(0, vertexCount);
        this.triangleCount = Math.max(0, triangleCount);
        this.fileSize = Math.max(0, fileSize);
    }
    
    public void setLastModified(LocalDateTime lastModified) {
        this.lastModified = lastModified != null ? lastModified : LocalDateTime.now();
    }
    
    // Utility methods
    @Override
    public String toString() {
        return String.format("ModelNode{name='%s', parts=%d, variants=%d, error=%s}", 
                           modelName, partCount, availableVariants.size(), hasError());
    }
    
    @Override
    public boolean equals(Object obj) {
        if (this == obj) return true;
        if (obj == null || getClass() != obj.getClass()) return false;
        ModelNode modelNode = (ModelNode) obj;
        return Objects.equals(modelName, modelNode.modelName);
    }
    
    @Override
    public int hashCode() {
        return Objects.hash(modelName);
    }
}

/**
 * Dear ImGui implementation of the Model Browser.
 * Provides hierarchical model organization with tree structure and search functionality.
 */
public class ModelBrowserImGui {
    
    /**
     * Callback interface for model selection events.
     */
    public interface ModelSelectionCallback {
        void onModelSelected(String modelName, String variantName);
    }
    
    private static final Logger logger = LoggerFactory.getLogger(ModelBrowserImGui.class);
    
    // State Management
    private OpenMason3DViewport viewport3D;
    private PropertyPanelImGui propertyPanel;
    private ModelSelectionCallback modelSelectionCallback;
    
    // Model Tree Structure
    private final Map<String, List<ModelNode>> discoveredModels = new HashMap<>();
    private final Set<String> expandedNodes = new HashSet<>();
    
    // UI State
    private final ImString searchText = new ImString(256);
    private final ImBoolean filterModels = new ImBoolean(true);
    private final ImBoolean filterVariants = new ImBoolean(true);
    private final ImBoolean filterMobs = new ImBoolean(true);
    private final ImBoolean filterRecent = new ImBoolean(false);
    
    // Selected state
    private String selectedModelName = null;
    private String selectedVariant = "default";
    private ModelNode selectedNode = null;
    
    // Scan state
    private boolean scanInProgress = false;
    private String scanStatus = "Ready";
    
    // Performance tracking
    private long lastScanTime = 0;
    private int totalModelsDiscovered = 0;
    private int totalVariantsDiscovered = 0;
    
    public ModelBrowserImGui() {
        initialize();
    }
    
    private void initialize() {
        try {
            logger.info("Initializing ModelBrowserImGui...");
            
            // Validate initial state
            if (discoveredModels == null) {
                throw new IllegalStateException("Discovered models map is null");
            }
            if (expandedNodes == null) {
                throw new IllegalStateException("Expanded nodes set is null");
            }
            
            // Initialize expanded nodes
            expandedNodes.add("Cow Models");
            expandedNodes.add("Mobs");
            
            // Initialize asynchronously with proper error handling
            CompletableFuture.runAsync(() -> {
                try {
                    scanForModels();
                    logger.info("ModelBrowserImGui initialized successfully");
                } catch (Exception e) {
                    logger.error("Failed to initialize ModelBrowserImGui", e);
                    scanStatus = "Initialization failed: " + e.getMessage();
                }
            }).exceptionally(throwable -> {
                logger.error("Async initialization failed", throwable);
                scanStatus = "Async initialization failed: " + throwable.getMessage();
                return null;
            });
            
        } catch (Exception e) {
            logger.error("Critical error during ModelBrowserImGui initialization", e);
            scanStatus = "Critical initialization error: " + e.getMessage();
            throw new RuntimeException("Failed to initialize ModelBrowserImGui", e);
        }
    }
    
    /**
     * Render the model browser using Dear ImGui
     */
    public void render() {
        try {
            if (ImGui.begin("Model Browser", ImGuiWindowFlags.AlwaysAutoResize)) {
                renderSearchAndFilter();
                ImGui.separator();
                renderModelTree();
                ImGui.separator();
                renderModelInfo();
                ImGui.separator();
                renderActions();
            }
        } catch (Exception e) {
            logger.error("Error rendering model browser", e);
            ImGui.text("Error rendering model browser: " + e.getMessage());
        } finally {
            ImGui.end();
        }
    }
    
    private void renderSearchAndFilter() {
        if (ImGui.collapsingHeader("Search & Filter", ImGuiTreeNodeFlags.DefaultOpen)) {
            ImGui.indent();
            
            // Search field
            ImGui.text("Search:");
            if (ImGui.inputText("##search", searchText)) {
                // Search is applied in real-time during tree rendering
            }
            
            // Filter options
            ImGui.text("Show:");
            ImGui.checkbox("Models", filterModels);
            ImGui.sameLine();
            ImGui.checkbox("Variants", filterVariants);
            ImGui.sameLine();
            ImGui.checkbox("Mobs", filterMobs);
            ImGui.sameLine();
            ImGui.checkbox("Recent", filterRecent);
            
            // Scan button
            if (scanInProgress) {
                ImGui.textColored(0.0f, 1.0f, 1.0f, 1.0f, "[SCAN] Scanning...");
                ImGui.sameLine();
                ImGui.progressBar(-1.0f * (float)ImGui.getTime());
            } else {
                if (ImGui.button("Refresh Models")) {
                    scanForModels();
                }
            }
            
            ImGui.text("Status: " + scanStatus);
            
            ImGui.unindent();
        }
    }
    
    private void renderModelTree() {
        if (ImGui.collapsingHeader("Models", ImGuiTreeNodeFlags.DefaultOpen)) {
            ImGui.indent();
            
            if (discoveredModels.isEmpty()) {
                ImGui.textDisabled("No models found. Click 'Refresh Models' to scan.");
                ImGui.unindent();
                return;
            }
            
            // Render categories
            for (Map.Entry<String, List<ModelNode>> entry : discoveredModels.entrySet()) {
                String categoryName = entry.getKey();
                List<ModelNode> models = entry.getValue();
                
                if (!shouldShowCategory(categoryName, models)) {
                    continue;
                }
                
                // Category header
                boolean categoryExpanded = expandedNodes.contains(categoryName);
                int treeFlags = ImGuiTreeNodeFlags.OpenOnArrow | ImGuiTreeNodeFlags.OpenOnDoubleClick;
                if (categoryExpanded) {
                    treeFlags |= ImGuiTreeNodeFlags.DefaultOpen;
                }
                
                boolean categoryNodeOpen = ImGui.treeNodeEx(categoryName + "##" + categoryName, 
                                                          treeFlags, categoryName + " (" + models.size() + ")");
                
                if (ImGui.isItemClicked()) {
                    if (categoryExpanded) {
                        expandedNodes.remove(categoryName);
                    } else {
                        expandedNodes.add(categoryName);
                    }
                }
                
                if (categoryNodeOpen) {
                    // Render models in category
                    for (ModelNode modelNode : models) {
                        if (!shouldShowModel(modelNode)) {
                            continue;
                        }
                        
                        renderModelNode(modelNode);
                    }
                    
                    ImGui.treePop();
                }
            }
            
            ImGui.unindent();
        }
    }
    
    private void renderModelNode(ModelNode modelNode) {
        String modelId = modelNode.getName() + "##model" + modelNode.getName();
        boolean modelExpanded = expandedNodes.contains(modelNode.getName());
        
        // Model node styling
        if (modelNode.hasError()) {
            ImGui.pushStyleColor(ImGuiCol.Text, 1.0f, 0.0f, 0.0f, 1.0f); // Red for errors
        }
        
        int treeFlags = ImGuiTreeNodeFlags.OpenOnArrow | ImGuiTreeNodeFlags.OpenOnDoubleClick;
        if (modelNode.getName().equals(selectedModelName)) {
            treeFlags |= ImGuiTreeNodeFlags.Selected;
        }
        if (modelExpanded) {
            treeFlags |= ImGuiTreeNodeFlags.DefaultOpen;
        }
        
        boolean modelNodeOpen = ImGui.treeNodeEx(modelId, treeFlags, 
                                               modelNode.getDisplayName() + getModelSuffix(modelNode));
        
        if (modelNode.hasError()) {
            ImGui.popStyleColor();
        }
        
        // Handle model selection
        if (ImGui.isItemClicked()) {
            handleModelSelection(modelNode);
            
            if (modelExpanded) {
                expandedNodes.remove(modelNode.getName());
            } else {
                expandedNodes.add(modelNode.getName());
            }
        }
        
        // Tooltip with model info
        if (ImGui.isItemHovered()) {
            ImGui.beginTooltip();
            ImGui.text("Model: " + modelNode.getName());
            if (modelNode.getPartCount() > 0) {
                ImGui.text("Parts: " + modelNode.getPartCount());
                ImGui.text("Vertices: " + modelNode.getVertexCount());
                ImGui.text("Triangles: " + modelNode.getTriangleCount());
            }
            if (!modelNode.getAvailableVariants().isEmpty()) {
                ImGui.text("Variants: " + modelNode.getAvailableVariants().size());
            }
            if (modelNode.hasError()) {
                ImGui.separator();
                ImGui.textColored(1.0f, 0.0f, 0.0f, 1.0f, "Error: " + modelNode.getErrorMessage());
            }
            ImGui.endTooltip();
        }
        
        if (modelNodeOpen) {
            // Render variants
            for (String variantName : modelNode.getAvailableVariants()) {
                if (!shouldShowVariant(variantName)) {
                    continue;
                }
                
                renderVariantNode(modelNode, variantName);
            }
            
            ImGui.treePop();
        }
    }
    
    private void renderVariantNode(ModelNode modelNode, String variantName) {
        String variantId = variantName + "##variant" + modelNode.getName() + variantName;
        
        int treeFlags = ImGuiTreeNodeFlags.Leaf | ImGuiTreeNodeFlags.NoTreePushOnOpen;
        if (modelNode.getName().equals(selectedModelName) && variantName.equals(selectedVariant)) {
            treeFlags |= ImGuiTreeNodeFlags.Selected;
        }
        
        // Capitalize variant name for display
        String displayName = variantName.substring(0, 1).toUpperCase() + variantName.substring(1).toLowerCase();
        
        ImGui.treeNodeEx(variantId, treeFlags, "[T] " + displayName);
        
        // Handle variant selection
        if (ImGui.isItemClicked()) {
            handleVariantSelection(modelNode, variantName);
        }
        
        // Tooltip
        if (ImGui.isItemHovered()) {
            ImGui.beginTooltip();
            ImGui.text("Texture Variant: " + displayName);
            ImGui.text("Model: " + modelNode.getName());
            ImGui.endTooltip();
        }
    }
    
    private void renderModelInfo() {
        if (ImGui.collapsingHeader("Model Information")) {
            ImGui.indent();
            
            if (selectedNode != null) {
                ImGui.text("Selected Model: " + selectedNode.getDisplayName());
                
                if (selectedVariant != null && !selectedVariant.equals("default")) {
                    ImGui.text("Texture Variant: " + selectedVariant);
                }
                
                ImGui.separator();
                
                if (selectedNode.getPartCount() > 0) {
                    ImGui.bulletText("Parts: " + selectedNode.getPartCount());
                    ImGui.bulletText("Vertices: " + selectedNode.getVertexCount());
                    ImGui.bulletText("Triangles: " + selectedNode.getTriangleCount());
                }
                
                if (!selectedNode.getAvailableVariants().isEmpty()) {
                    ImGui.bulletText("Texture Variants: " + selectedNode.getAvailableVariants().size());
                    
                    ImGui.text("Available variants:");
                    for (String variant : selectedNode.getAvailableVariants()) {
                        ImGui.bulletText(variant.substring(0, 1).toUpperCase() + variant.substring(1).toLowerCase());
                    }
                }
                
                if (selectedNode.hasError()) {
                    ImGui.separator();
                    ImGui.textColored(1.0f, 0.0f, 0.0f, 1.0f, "Error: " + selectedNode.getErrorMessage());
                }
                
            } else {
                ImGui.textDisabled("No model selected");
            }
            
            ImGui.unindent();
        }
    }
    
    private void renderActions() {
        if (ImGui.collapsingHeader("Actions")) {
            ImGui.indent();
            
            if (selectedNode != null) {
                if (ImGui.button("Load Model")) {
                    loadSelectedModel();
                }
                
                if (!selectedNode.getAvailableVariants().isEmpty()) {
                    ImGui.sameLine();
                    if (ImGui.button("Generate Thumbnail")) {
                        generateThumbnail(selectedNode.getName(), selectedVariant);
                    }
                }
            } else {
                ImGui.textDisabled("Select a model to see available actions");
            }
            
            ImGui.separator();
            
            if (ImGui.button("Refresh All")) {
                scanForModels();
            }
            
            ImGui.sameLine();
            if (ImGui.button("Expand All")) {
                expandAll();
            }
            
            ImGui.sameLine();
            if (ImGui.button("Collapse All")) {
                collapseAll();
            }
            
            ImGui.unindent();
        }
    }
    
    // Filtering methods
    
    private boolean shouldShowCategory(String categoryName, List<ModelNode> models) {
        if (!filterModels.get() && categoryName.contains("Models")) {
            return false;
        }
        if (!filterMobs.get() && categoryName.equals("Mobs")) {
            return false;
        }
        
        String searchStr = searchText.get().toLowerCase();
        if (!searchStr.isEmpty()) {
            // Show category if any model matches search
            return models.stream().anyMatch(model -> shouldShowModel(model));
        }
        
        return true;
    }
    
    private boolean shouldShowModel(ModelNode modelNode) {
        String searchStr = searchText.get().toLowerCase();
        if (!searchStr.isEmpty()) {
            return modelNode.getName().toLowerCase().contains(searchStr) ||
                   modelNode.getDisplayName().toLowerCase().contains(searchStr) ||
                   modelNode.getAvailableVariants().stream().anyMatch(v -> v.toLowerCase().contains(searchStr));
        }
        
        return true;
    }
    
    private boolean shouldShowVariant(String variantName) {
        if (!filterVariants.get()) {
            return false;
        }
        
        String searchStr = searchText.get().toLowerCase();
        if (!searchStr.isEmpty()) {
            return variantName.toLowerCase().contains(searchStr);
        }
        
        return true;
    }
    
    private String getModelSuffix(ModelNode modelNode) {
        StringBuilder suffix = new StringBuilder();
        
        if (modelNode.hasError()) {
            suffix.append(" [ERROR]");
        }
        
        if (modelNode.getPartCount() > 0) {
            suffix.append(" (").append(modelNode.getPartCount()).append(" parts)");
        }
        
        if (!modelNode.getAvailableVariants().isEmpty()) {
            suffix.append(" [").append(modelNode.getAvailableVariants().size()).append(" variants]");
        }
        
        return suffix.toString();
    }
    
    // Event handlers
    
    private void handleModelSelection(ModelNode modelNode) {
        if (modelNode == null) {
            logger.warn("Attempted to select null model node");
            return;
        }
        
        try {
            String modelName = modelNode.getName();
            if (modelName == null || modelName.trim().isEmpty()) {
                logger.warn("Model node has null or empty name: {}", modelNode);
                return;
            }
            
            selectedNode = modelNode;
            selectedModelName = modelName;
            selectedVariant = "default";
            
            logger.info("Model selected: {}", modelName);
            
            // Notify callback with error handling
            if (modelSelectionCallback != null) {
                try {
                    modelSelectionCallback.onModelSelected(modelName, "default");
                } catch (Exception e) {
                    logger.error("Error in model selection callback for model: {}", modelName, e);
                }
            }
            
            // Notify property panel with error handling
            if (propertyPanel != null) {
                try {
                    propertyPanel.loadTextureVariants(modelName);
                } catch (Exception e) {
                    logger.error("Error loading texture variants in property panel for model: {}", modelName, e);
                }
            }
            
            // Load in viewport with error handling
            try {
                loadSelectedModel();
            } catch (Exception e) {
                logger.error("Error loading model in viewport: {}", modelName, e);
            }
            
        } catch (Exception e) {
            logger.error("Unexpected error during model selection", e);
        }
    }
    
    private void handleVariantSelection(ModelNode modelNode, String variantName) {
        if (modelNode == null) {
            logger.warn("Attempted to select variant for null model node");
            return;
        }
        if (variantName == null || variantName.trim().isEmpty()) {
            logger.warn("Attempted to select null or empty variant name");
            return;
        }
        
        try {
            String modelName = modelNode.getName();
            String cleanVariantName = variantName.trim();
            
            if (modelName == null || modelName.trim().isEmpty()) {
                logger.warn("Model node has null or empty name: {}", modelNode);
                return;
            }
            
            selectedNode = modelNode;
            selectedModelName = modelName;
            selectedVariant = cleanVariantName;
            
            logger.info("Variant selected: {} for model {}", cleanVariantName, modelName);
            
            // Notify callback with error handling
            if (modelSelectionCallback != null) {
                try {
                    modelSelectionCallback.onModelSelected(modelName, cleanVariantName);
                } catch (Exception e) {
                    logger.error("Error in model selection callback for variant: {} of model: {}", cleanVariantName, modelName, e);
                }
            }
            
            // Notify property panel with error handling
            if (propertyPanel != null) {
                try {
                    propertyPanel.switchTextureVariant(cleanVariantName);
                } catch (Exception e) {
                    logger.error("Error switching texture variant in property panel: {} for model: {}", cleanVariantName, modelName, e);
                }
            }
            
            // Update viewport texture with error handling
            if (viewport3D != null) {
                try {
                    viewport3D.setCurrentTextureVariant(cleanVariantName);
                    viewport3D.requestRender();
                } catch (Exception e) {
                    logger.error("Error updating viewport texture variant: {} for model: {}", cleanVariantName, modelName, e);
                }
            } else {
                logger.debug("Viewport3D is null, cannot update texture variant");
            }
            
        } catch (Exception e) {
            logger.error("Unexpected error during variant selection", e);
        }
    }
    
    private void loadSelectedModel() {
        if (selectedNode == null) {
            logger.debug("No model selected, cannot load");
            return;
        }
        
        try {
            String modelName = selectedNode.getName();
            if (modelName == null || modelName.trim().isEmpty()) {
                logger.warn("Selected model has null or empty name: {}", selectedNode);
                return;
            }
            
            logger.info("Loading model: {}", modelName);
            
            if (viewport3D != null) {
                try {
                    viewport3D.loadModel(modelName);
                    logger.debug("Model load request sent to viewport: {}", modelName);
                } catch (Exception e) {
                    logger.error("Error loading model in viewport: {}", modelName, e);
                    // Mark model as having error
                    selectedNode.setMetadata("loadError", e.getMessage());
                }
            } else {
                logger.warn("viewport3D is null when trying to load model: {}", modelName);
            }
            
        } catch (Exception e) {
            logger.error("Unexpected error during model loading", e);
        }
    }
    
    private void expandAll() {
        for (String categoryName : discoveredModels.keySet()) {
            expandedNodes.add(categoryName);
            
            for (ModelNode model : discoveredModels.get(categoryName)) {
                expandedNodes.add(model.getName());
            }
        }
    }
    
    private void collapseAll() {
        expandedNodes.clear();
    }
    
    // Core functionality
    
    private void scanForModels() {
        if (scanInProgress) {
            logger.debug("Scan already in progress, skipping new scan request");
            return;
        }
        
        logger.info("Starting comprehensive model scan...");
        
        scanInProgress = true;
        scanStatus = "Scanning for models...";
        
        CompletableFuture.runAsync(() -> {
            long startTime = System.currentTimeMillis();
            
            try {
                // Validate state before clearing
                if (discoveredModels == null) {
                    throw new IllegalStateException("Discovered models map is null");
                }
                
                // Clear existing data safely
                synchronized (discoveredModels) {
                    discoveredModels.clear();
                }
                totalModelsDiscovered = 0;
                totalVariantsDiscovered = 0;
                
                // Scan Stonebreak project directories with error handling
                scanStonebreakProject();
                
                lastScanTime = System.currentTimeMillis() - startTime;
                
                scanStatus = String.format("Found %d models, %d variants in %dms", 
                                          totalModelsDiscovered, totalVariantsDiscovered, lastScanTime);
                
                logger.info("Model scan completed: {} models, {} variants found in {}ms", 
                           totalModelsDiscovered, totalVariantsDiscovered, lastScanTime);
                
            } catch (Exception e) {
                logger.error("Error during model scan", e);
                scanStatus = "Scan failed: " + e.getMessage();
            } finally {
                scanInProgress = false;
            }
        }).exceptionally(throwable -> {
            logger.error("Async model scan failed", throwable);
            scanInProgress = false;
            scanStatus = "Async scan failed: " + throwable.getMessage();
            return null;
        });
    }
    
    private void scanStonebreakProject() {
        try {
            // Initialize ModelManager if needed
            if (ModelManager.getAvailableModels().isEmpty()) {
                logger.info("Initializing ModelManager...");
                ModelManager.initialize();
            }
            
            // Load models from ModelManager
            loadModelsFromManager();
            
            // Load texture variants
            loadTextureVariants();
            
            // Enhanced resource directory scanning
            scanResourceDirectories();
            
        } catch (Exception e) {
            logger.error("Error scanning Stonebreak project", e);
        }
    }
    
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
            ModelDefinition.ModelPart[] modelParts = ModelManager.getStaticModelParts(modelName);
            if (modelParts == null || modelParts.length == 0) {
                logger.warn("No model parts found for: {}", modelName);
                return;
            }
            
            // Calculate model statistics
            int partCount = modelInfo.getPartCount();
            int vertexCount = partCount * 24; // 24 vertices per cubic part
            int triangleCount = partCount * 12; // 12 triangles per cubic part
            
            // Create model node
            String actualPath = getModelResourcePath(modelName);
            ModelNode modelNode = ModelNode.createModelNode(
                modelName, actualPath, partCount, vertexCount, triangleCount);
            
            // Add to discovered models
            String category = "Cow Models";
            discoveredModels.computeIfAbsent(category, k -> new ArrayList<>()).add(modelNode);
            totalModelsDiscovered++;
            
            logger.info("Successfully processed model: {} ({} parts, {} vertices)", 
                       modelName, partCount, vertexCount);
            
        } catch (Exception e) {
            logger.error("Error processing model from ModelManager: {}", modelName, e);
        }
    }
    
    private void loadTextureVariants() {
        try {
            String[] availableVariants = com.stonebreak.textures.CowTextureLoader.getAvailableVariants();
            logger.info("Found {} texture variants: {}", availableVariants.length, String.join(", ", availableVariants));
            totalVariantsDiscovered = availableVariants.length;
            
            // Link variants to cow models
            for (List<ModelNode> models : discoveredModels.values()) {
                for (ModelNode modelNode : models) {
                    if (modelNode.getName().toLowerCase().contains("cow")) {
                        for (String variantName : availableVariants) {
                            modelNode.addVariant(variantName.toLowerCase());
                        }
                    }
                }
            }
            
        } catch (Exception e) {
            logger.error("Error loading texture variants", e);
        }
    }
    
    private void scanResourceDirectories() {
        try {
            logger.info("Starting dynamic resource directory scanning...");
            
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
                    
                    break; // Use first found resource directory
                }
            }
            
            logger.info("Dynamic resource scanning completed");
            
        } catch (Exception e) {
            logger.error("Error during dynamic resource directory scanning", e);
        }
    }
    
    private void scanModelsDirectory(Path modelsPath) {
        try {
            Files.walk(modelsPath)
                .filter(Files::isRegularFile)
                .filter(path -> path.toString().endsWith(".json"))
                .forEach(this::processModelFile);
                
        } catch (Exception e) {
            logger.error("Error scanning models directory: {}", modelsPath, e);
        }
    }
    
    private void processModelFile(Path modelFile) {
        try {
            String fileName = modelFile.getFileName().toString();
            String modelName = fileName.substring(0, fileName.lastIndexOf('.'));
            
            // Check if model already processed
            boolean alreadyProcessed = discoveredModels.values().stream()
                .flatMap(List::stream)
                .anyMatch(node -> node.getName().equals(modelName));
                
            if (alreadyProcessed) {
                return;
            }
            
            // Determine category based on directory structure
            String category = determineModelCategory(modelFile);
            
            // Load model parts to check if model exists
            ModelDefinition.ModelPart[] modelParts = ModelManager.getStaticModelParts(modelName);
            if (modelParts != null && modelParts.length > 0) {
                // Calculate model statistics
                int partCount = modelParts.length;
                int vertexCount = partCount * 24;
                int triangleCount = partCount * 12;
                
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
                // Create error node for failed models
                ModelNode errorNode = createErrorModelNode(modelName, modelFile.toString(), "Failed to load model parts");
                discoveredModels.computeIfAbsent("Failed Models", k -> new ArrayList<>()).add(errorNode);
                totalModelsDiscovered++;
            }
            
        } catch (Exception e) {
            logger.error("Error processing model file: {}", modelFile, e);
        }
    }
    
    private String determineModelCategory(Path modelFile) {
        Path parent = modelFile.getParent();
        if (parent != null) {
            String parentName = parent.getFileName().toString();
            
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
    
    private String getModelResourcePath(String modelName) {
        try {
            ModelManager.ModelInfo modelInfo = ModelManager.getModelInfo(modelName);
            if (modelInfo != null) {
                String displayName = modelInfo.getDisplayName();
                if (displayName != null && !displayName.isEmpty()) {
                    return "models/" + modelName.toLowerCase() + "/" + modelName + ".json";
                }
            }
            
            return "models/" + modelName.toLowerCase() + "/" + modelName + ".json";
            
        } catch (Exception e) {
            logger.debug("Could not resolve resource path for model: {}, using fallback", modelName);
            return "models/" + modelName.toLowerCase() + "/" + modelName + ".json";
        }
    }
    
    private ModelNode createErrorModelNode(String modelName, String modelPath, String errorMessage) {
        ModelNode errorNode = ModelNode.createModelNode(modelName, modelPath, 0, 0, 0);
        errorNode.setMetadata("error", true);
        errorNode.setMetadata("errorMessage", errorMessage);
        errorNode.setMetadata("loadingFailed", true);
        
        logger.debug("Created error node for failed model: {} - {}", modelName, errorMessage);
        return errorNode;
    }
    
    private void generateThumbnail(String modelName, String variant) {
        logger.debug("Generating thumbnail for model='{}', variant='{}'", modelName, variant);
        
        if (modelName == null || modelName.trim().isEmpty()) {
            logger.warn("Cannot generate thumbnail for null or empty model name");
            return;
        }
        
        CompletableFuture.runAsync(() -> {
            try {
                logger.info("Starting thumbnail generation for model '{}' with variant '{}'", modelName, variant);
                
                // Simulate thumbnail generation process
                Thread.sleep(100);
                
                // Find the model node and set a placeholder thumbnail indicator
                for (List<ModelNode> models : discoveredModels.values()) {
                    for (ModelNode modelNode : models) {
                        if (modelNode.getName().equals(modelName)) {
                            modelNode.setMetadata("thumbnailGenerated", true);
                            modelNode.setMetadata("thumbnailVariant", variant);
                            
                            logger.info("Thumbnail generation completed for model '{}' variant '{}'", modelName, variant);
                            return;
                        }
                    }
                }
                
            } catch (Exception e) {
                logger.error("Error generating thumbnail for model '{}' variant '{}'", modelName, variant, e);
            }
        });
    }
    
    // Public API methods
    
    public void setViewport3D(OpenMason3DViewport viewport) {
        if (viewport == null) {
            logger.warn("Setting null viewport3D reference");
        }
        this.viewport3D = viewport;
        logger.info("3D viewport reference {} for ModelBrowserImGui", viewport != null ? "set" : "cleared");
    }
    
    public void setPropertyPanel(PropertyPanelImGui propertyPanel) {
        if (propertyPanel == null) {
            logger.warn("Setting null propertyPanel reference");
        }
        this.propertyPanel = propertyPanel;
        logger.info("PropertyPanel reference {} for ModelBrowserImGui", propertyPanel != null ? "set" : "cleared");
    }
    
    public void setModelSelectionCallback(ModelSelectionCallback callback) {
        if (callback == null) {
            logger.warn("Setting null modelSelectionCallback reference");
        }
        this.modelSelectionCallback = callback;
        logger.info("ModelSelectionCallback reference {} for ModelBrowserImGui", callback != null ? "set" : "cleared");
    }
    
    public String getSelectedModelName() {
        return selectedModelName;
    }
    
    public String getSelectedVariant() {
        return selectedVariant != null ? selectedVariant : "default";
    }
    
    public boolean isScanInProgress() {
        return scanInProgress;
    }
    
    public String getScanStatus() {
        return scanStatus != null ? scanStatus : "Unknown";
    }
    
    public ModelNode getSelectedNode() {
        return selectedNode;
    }
    
    public boolean hasSelectedModel() {
        return selectedNode != null && selectedModelName != null && !selectedModelName.trim().isEmpty();
    }
    
    public Map<String, Object> getPerformanceMetrics() {
        Map<String, Object> metrics = new HashMap<>();
        
        try {
            metrics.put("lastScanTime", lastScanTime);
            metrics.put("totalModelsDiscovered", totalModelsDiscovered);
            metrics.put("totalVariantsDiscovered", totalVariantsDiscovered);
            
            // Safely get categories count
            int categoriesCount = 0;
            if (discoveredModels != null) {
                synchronized (discoveredModels) {
                    categoriesCount = discoveredModels.size();
                }
            }
            metrics.put("categoriesCount", categoriesCount);
            
            metrics.put("selectedModel", selectedModelName != null ? selectedModelName : "None");
            metrics.put("selectedVariant", selectedVariant != null ? selectedVariant : "default");
            metrics.put("scanInProgress", scanInProgress);
            metrics.put("scanStatus", scanStatus != null ? scanStatus : "Unknown");
            metrics.put("hasSelectedModel", hasSelectedModel());
            
            // Additional diagnostic info
            metrics.put("viewport3DSet", viewport3D != null);
            metrics.put("propertyPanelSet", propertyPanel != null);
            metrics.put("callbackSet", modelSelectionCallback != null);
            
        } catch (Exception e) {
            logger.error("Error collecting performance metrics", e);
            metrics.put("metricsError", e.getMessage());
        }
        
        return metrics;
    }
    
    /**
     * Dispose of resources and clean up state
     */
    public void dispose() {
        try {
            logger.info("Disposing ModelBrowserImGui resources...");
            
            // Clear references
            viewport3D = null;
            propertyPanel = null;
            modelSelectionCallback = null;
            
            // Clear collections safely
            if (discoveredModels != null) {
                synchronized (discoveredModels) {
                    discoveredModels.clear();
                }
            }
            
            if (expandedNodes != null) {
                expandedNodes.clear();
            }
            
            // Clear selected state
            selectedNode = null;
            selectedModelName = null;
            selectedVariant = null;
            
            // Clear UI state
            if (searchText != null) {
                searchText.set("");
            }
            
            // Reset scan state
            scanInProgress = false;
            scanStatus = "Disposed";
            
            logger.info("ModelBrowserImGui resources disposed successfully");
            
        } catch (Exception e) {
            logger.error("Error disposing ModelBrowserImGui resources", e);
        }
    }
}