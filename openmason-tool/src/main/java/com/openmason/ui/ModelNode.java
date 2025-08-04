package com.openmason.ui;

import javafx.scene.image.Image;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.file.Path;
import java.time.LocalDateTime;
import java.util.*;

/**
 * Tree node representation for the model browser hierarchy.
 * Supports hierarchical organization: ROOT -> CATEGORY -> MODEL -> VARIANT
 * Each node type provides different metadata and functionality.
 */
public class ModelNode {
    
    private static final Logger logger = LoggerFactory.getLogger(ModelNode.class);
    
    public enum NodeType {
        ROOT,       // Top-level container (e.g., "Models")
        CATEGORY,   // Model category (e.g., "Mobs", "Blocks", "Items")
        MODEL,      // Specific model (e.g., "Cow", "Chicken", "Stone")
        VARIANT     // Model variant (e.g., "default", "angus", "highland", "jersey")
    }
    
    // Core properties
    private final String name;
    private final NodeType type;
    private final String displayName;
    
    // Model-specific properties
    private String modelPath;
    private String modelName;
    private String variantName;
    private Map<String, Object> metadata;
    
    // UI properties
    private Image thumbnail;
    private boolean thumbnailGenerated;
    private boolean isExpanded;
    private boolean isSelected;
    
    // Statistics and information
    private int partCount;
    private int vertexCount;
    private int triangleCount;
    private long fileSize;
    private LocalDateTime lastModified;
    private Set<String> availableVariants;
    
    /**
     * Create a new ModelNode.
     * @param name The internal name of the node
     * @param type The type of node (ROOT, CATEGORY, MODEL, VARIANT)
     */
    public ModelNode(String name, NodeType type) {
        this.name = name;
        this.type = type;
        this.displayName = generateDisplayName(name, type);
        this.metadata = new HashMap<>();
        this.availableVariants = new LinkedHashSet<>();
        this.thumbnailGenerated = false;
        this.isExpanded = false;
        this.isSelected = false;
        
        logger.debug("Created ModelNode: name='{}', type={}, displayName='{}'", name, type, displayName);
    }
    
    /**
     * Create a MODEL type node with detailed information.
     */
    public static ModelNode createModelNode(String modelName, String modelPath, 
                                          int partCount, int vertexCount, int triangleCount) {
        ModelNode node = new ModelNode(modelName, NodeType.MODEL);
        node.modelName = modelName;
        node.modelPath = modelPath;
        node.partCount = partCount;
        node.vertexCount = vertexCount;
        node.triangleCount = triangleCount;
        
        logger.debug("Created MODEL node: name='{}', parts={}, vertices={}, triangles={}", 
            modelName, partCount, vertexCount, triangleCount);
        return node;
    }
    
    /**
     * Create a VARIANT type node with texture variant information.
     */
    public static ModelNode createVariantNode(String variantName, String modelName, String texturePath) {
        ModelNode node = new ModelNode(variantName, NodeType.VARIANT);
        node.variantName = variantName;
        node.modelName = modelName;
        node.modelPath = texturePath;
        
        logger.debug("Created VARIANT node: variant='{}', model='{}', path='{}'", 
            variantName, modelName, texturePath);
        return node;
    }
    
    /**
     * Create a CATEGORY type node for organizing models.
     */
    public static ModelNode createCategoryNode(String categoryName, int modelCount) {
        ModelNode node = new ModelNode(categoryName, NodeType.CATEGORY);
        node.metadata.put("modelCount", modelCount);
        
        logger.debug("Created CATEGORY node: name='{}', modelCount={}", categoryName, modelCount);
        return node;
    }
    
    /**
     * Generate a user-friendly display name based on the internal name and type.
     */
    private String generateDisplayName(String name, NodeType type) {
        if (name == null || name.isEmpty()) {
            return "Unnamed " + type.name().toLowerCase();
        }
        
        switch (type) {
            case ROOT:
                return name; // Keep as-is for root nodes
                
            case CATEGORY:
                return capitalizeFirst(name);
                
            case MODEL:
                // Convert snake_case to Title Case
                return Arrays.stream(name.split("_"))
                    .map(this::capitalizeFirst)
                    .reduce((a, b) -> a + " " + b)
                    .orElse(capitalizeFirst(name));
                    
            case VARIANT:
                // Special handling for common variant names
                switch (name.toLowerCase()) {
                    case "default": return "Default";
                    case "angus": return "Angus";
                    case "highland": return "Highland";
                    case "jersey": return "Jersey";
                    default: return capitalizeFirst(name);
                }
                
            default:
                return capitalizeFirst(name);
        }
    }
    
    /**
     * Capitalize the first letter of a string.
     */
    private String capitalizeFirst(String str) {
        if (str == null || str.isEmpty()) {
            return str;
        }
        return str.substring(0, 1).toUpperCase() + str.substring(1).toLowerCase();
    }
    
    /**
     * Add a texture variant to this model node.
     * Only valid for MODEL type nodes.
     */
    public void addVariant(String variantName) {
        if (type != NodeType.MODEL) {
            logger.warn("Cannot add variant '{}' to non-MODEL node: {}", variantName, this);
            return;
        }
        
        availableVariants.add(variantName);
        logger.debug("Added variant '{}' to model '{}'. Total variants: {}", 
            variantName, name, availableVariants.size());
    }
    
    /**
     * Set model statistics for this node.
     * Only valid for MODEL type nodes.
     */
    public void setModelStatistics(int partCount, int vertexCount, int triangleCount, long fileSize) {
        if (type != NodeType.MODEL) {
            logger.warn("Cannot set model statistics for non-MODEL node: {}", this);
            return;
        }
        
        this.partCount = partCount;
        this.vertexCount = vertexCount;
        this.triangleCount = triangleCount;
        this.fileSize = fileSize;
        
        logger.debug("Set model statistics for '{}': parts={}, vertices={}, triangles={}, size={} bytes", 
            name, partCount, vertexCount, triangleCount, fileSize);
    }
    
    /**
     * Set thumbnail image for this node.
     */
    public void setThumbnail(Image thumbnail) {
        this.thumbnail = thumbnail;
        this.thumbnailGenerated = (thumbnail != null);
        
        if (thumbnail != null) {
            logger.debug("Set thumbnail for '{}': {}x{}", name, 
                (int)thumbnail.getWidth(), (int)thumbnail.getHeight());
        }
    }
    
    /**
     * Add custom metadata to this node.
     */
    public void setMetadata(String key, Object value) {
        metadata.put(key, value);
        logger.debug("Set metadata for '{}': {}={}", name, key, value);
    }
    
    /**
     * Get custom metadata from this node.
     */
    public Object getMetadata(String key) {
        return metadata.get(key);
    }
    
    /**
     * Check if this node matches a search filter.
     */
    public boolean matchesFilter(String searchText, String filterType) {
        if (searchText == null || searchText.trim().isEmpty()) {
            return matchesTypeFilter(filterType);
        }
        
        String searchLower = searchText.toLowerCase().trim();
        
        // Search in display name
        if (displayName.toLowerCase().contains(searchLower)) {
            return matchesTypeFilter(filterType);
        }
        
        // Search in internal name
        if (name.toLowerCase().contains(searchLower)) {
            return matchesTypeFilter(filterType);
        }
        
        // For MODEL nodes, search in available variants
        if (type == NodeType.MODEL) {
            for (String variant : availableVariants) {
                if (variant.toLowerCase().contains(searchLower)) {
                    return matchesTypeFilter(filterType);
                }
            }
        }
        
        // For VARIANT nodes, search in variant name
        if (type == NodeType.VARIANT && variantName != null) {
            if (variantName.toLowerCase().contains(searchLower)) {
                return matchesTypeFilter(filterType);
            }
        }
        
        return false;
    }
    
    /**
     * Check if this node matches a type filter.
     */
    private boolean matchesTypeFilter(String filterType) {
        if (filterType == null || filterType.isEmpty() || "All".equals(filterType)) {
            return true;
        }
        
        switch (filterType.toLowerCase()) {
            case "models":
                return type == NodeType.MODEL;
            case "variants":
                return type == NodeType.VARIANT;
            case "categories":
                return type == NodeType.CATEGORY;
            case "mobs":
                return type == NodeType.CATEGORY && "Mobs".equals(displayName) ||
                       type == NodeType.MODEL && isInCategory("Mobs");
            default:
                return true;
        }
    }
    
    /**
     * Check if this model belongs to a specific category.
     */
    private boolean isInCategory(String categoryName) {
        // This would need to be implemented based on how the tree structure is built
        // For now, we'll use simple heuristics
        if ("Mobs".equals(categoryName)) {
            return name.toLowerCase().contains("cow") || 
                   name.toLowerCase().contains("chicken") ||
                   name.toLowerCase().contains("pig") ||
                   name.toLowerCase().contains("sheep");
        }
        return false;
    }
    
    /**
     * Get a formatted description for display in info panels.
     * Enhanced with error state display and better filepath integration.
     */
    public String getFormattedDescription() {
        StringBuilder desc = new StringBuilder();
        
        desc.append("Name: ").append(displayName).append("\n");
        desc.append("Type: ").append(type.name().toLowerCase()).append("\n");
        
        // Check for error state
        if (Boolean.TRUE.equals(metadata.get("error"))) {
            desc.append("Status: FAILED TO LOAD\n");
            Object errorMessage = metadata.get("errorMessage");
            if (errorMessage != null) {
                desc.append("Error: ").append(errorMessage).append("\n");
            }
        }
        
        if (type == NodeType.MODEL) {
            if (!Boolean.TRUE.equals(metadata.get("error"))) {
                desc.append("Parts: ").append(partCount).append("\n");
                desc.append("Vertices: ").append(vertexCount).append("\n");
                desc.append("Triangles: ").append(triangleCount).append("\n");
                desc.append("Variants: ").append(availableVariants.size()).append("\n");
                
                if (fileSize > 0) {
                    desc.append("File Size: ").append(formatFileSize(fileSize)).append("\n");
                }
            }
        }
        
        if (type == NodeType.VARIANT) {
            desc.append("Model: ").append(modelName).append("\n");
            desc.append("Variant: ").append(variantName).append("\n");
        }
        
        if (type == NodeType.CATEGORY) {
            Object modelCount = metadata.get("modelCount");
            if (modelCount != null) {
                desc.append("Models: ").append(modelCount).append("\n");
            }
        }
        
        // Always show filepath for better integration visibility
        if (modelPath != null && !modelPath.trim().isEmpty()) {
            desc.append("Resource Path: ").append(modelPath).append("\n");
        }
        
        return desc.toString().trim();
    }
    
    /**
     * Format file size in human-readable format.
     */
    private String formatFileSize(long bytes) {
        if (bytes < 1024) return bytes + " B";
        if (bytes < 1024 * 1024) return (bytes / 1024) + " KB";
        return (bytes / (1024 * 1024)) + " MB";
    }
    
    // Getters and Setters
    
    public String getName() {
        return name;
    }
    
    public NodeType getType() {
        return type;
    }
    
    public String getDisplayName() {
        return displayName;
    }
    
    public String getModelPath() {
        return modelPath;
    }
    
    public String getModelName() {
        return modelName;
    }
    
    public String getVariantName() {
        return variantName;
    }
    
    public Image getThumbnail() {
        return thumbnail;
    }
    
    public boolean isThumbnailGenerated() {
        return thumbnailGenerated;
    }
    
    public boolean isExpanded() {
        return isExpanded;
    }
    
    public void setExpanded(boolean expanded) {
        this.isExpanded = expanded;
    }
    
    public boolean isSelected() {
        return isSelected;
    }
    
    public void setSelected(boolean selected) {
        this.isSelected = selected;
    }
    
    public int getPartCount() {
        return partCount;
    }
    
    public int getVertexCount() {
        return vertexCount;
    }
    
    public int getTriangleCount() {
        return triangleCount;
    }
    
    public Set<String> getAvailableVariants() {
        return Collections.unmodifiableSet(availableVariants);
    }
    
    public LocalDateTime getLastModified() {
        return lastModified;
    }
    
    public void setLastModified(LocalDateTime lastModified) {
        this.lastModified = lastModified;
    }
    
    @Override
    public String toString() {
        return displayName;
    }
    
    @Override
    public boolean equals(Object obj) {
        if (this == obj) return true;
        if (obj == null || getClass() != obj.getClass()) return false;
        
        ModelNode modelNode = (ModelNode) obj;
        return type == modelNode.type && 
               Objects.equals(name, modelNode.name) &&
               Objects.equals(modelName, modelNode.modelName) &&
               Objects.equals(variantName, modelNode.variantName);
    }
    
    @Override
    public int hashCode() {
        return Objects.hash(name, type, modelName, variantName);
    }
}