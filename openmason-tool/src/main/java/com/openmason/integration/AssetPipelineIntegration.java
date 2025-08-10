package com.openmason.integration;

import com.openmason.texture.TextureManager;
import com.openmason.model.ModelManager;
import com.openmason.coordinates.CoordinateSystemValidator;
// Removed JavaFX Task dependency - using standard CompletableFuture
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.*;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.Map;
import java.util.List;
import java.util.ArrayList;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;

/**
 * Asset pipeline integration for seamless workflow with Stonebreak development.
 * Handles export to game directories, validation, and batch processing.
 */
public class AssetPipelineIntegration {
    private static final Logger logger = LoggerFactory.getLogger(AssetPipelineIntegration.class);
    
    private final TextureManager textureManager;
    private final ModelManager modelManager;
    private final CoordinateSystemValidator coordinateValidator;
    
    // Asset pipeline configuration
    private Path stonebreakProjectRoot;
    private Path gameTextureDirectory;
    private Path gameModelDirectory;
    private Path backupDirectory;
    
    // Pipeline status tracking
    private final Map<String, PipelineOperation> activeOperations = new ConcurrentHashMap<>();
    private final List<PipelineListener> pipelineListeners = new ArrayList<>();
    
    public AssetPipelineIntegration(TextureManager textureManager, ModelManager modelManager) {
        this.textureManager = textureManager;
        this.modelManager = modelManager;
        this.coordinateValidator = new CoordinateSystemValidator();
        
        initializePipelinePaths();
    }
    
    /**
     * Initializes asset pipeline directory paths.
     */
    private void initializePipelinePaths() {
        try {
            // Detect Stonebreak project root
            stonebreakProjectRoot = detectStonebreakProjectRoot();
            
            if (stonebreakProjectRoot != null) {
                gameTextureDirectory = stonebreakProjectRoot.resolve("stonebreak-game/src/main/resources/textures/mobs/cow");
                gameModelDirectory = stonebreakProjectRoot.resolve("stonebreak-game/src/main/resources/models/cow");
                backupDirectory = stonebreakProjectRoot.resolve("openmason-tool/backups");
                
                // Create directories if they don't exist
                Files.createDirectories(gameTextureDirectory);
                Files.createDirectories(gameModelDirectory);
                Files.createDirectories(backupDirectory);
                
                logger.info("Asset pipeline initialized with Stonebreak project at: {}", stonebreakProjectRoot);
            } else {
                logger.warn("Could not detect Stonebreak project root - some pipeline features will be unavailable");
            }
            
        } catch (Exception e) {
            logger.error("Failed to initialize asset pipeline paths", e);
        }
    }
    
    /**
     * Detects the Stonebreak project root directory.
     */
    private Path detectStonebreakProjectRoot() {
        // Start from current working directory and traverse up
        Path currentPath = Paths.get("").toAbsolutePath();
        
        while (currentPath != null) {
            // Look for Stonebreak project indicators
            if (Files.exists(currentPath.resolve("stonebreak-game")) &&
                Files.exists(currentPath.resolve("openmason-tool")) &&
                Files.exists(currentPath.resolve("pom.xml"))) {
                
                logger.info("Detected Stonebreak project root: {}", currentPath);
                return currentPath;
            }
            
            currentPath = currentPath.getParent();
        }
        
        return null;
    }
    
    /**
     * Exports texture definitions to the game asset directory.
     */
    public CompletableFuture<PipelineResult> exportTexturesToGame(List<String> variantNames) {
        return CompletableFuture.supplyAsync(() -> {
            String operationId = "export_textures_" + System.currentTimeMillis();
            PipelineOperation operation = new PipelineOperation(operationId, "Export Textures to Game", variantNames.size());
            
            try {
                activeOperations.put(operationId, operation);
                notifyPipelineListeners(operation, PipelineEventType.STARTED);
                
                if (gameTextureDirectory == null) {
                    throw new IllegalStateException("Game texture directory not available - ensure Stonebreak project is detected");
                }
                
                PipelineResult result = new PipelineResult(operationId);
                
                // Create backup before export
                createBackup("textures", gameTextureDirectory);
                
                for (String variantName : variantNames) {
                    try {
                        operation.setCurrentTask("Exporting " + variantName);
                        
                        // Validate texture before export (using complete validation)
                        CoordinateSystemValidator.ValidationResult validation = 
                            CoordinateSystemValidator.runCompleteValidation();
                        
                        if (!validation.isPassed()) {
                            result.addWarning("Validation issues found for " + variantName + ": " + validation.toString());
                        }
                        
                        // Export texture definition file
                        Path sourceFile = findTextureSourceFile(variantName);
                        Path targetFile = gameTextureDirectory.resolve(variantName + ".json");
                        
                        if (sourceFile != null && Files.exists(sourceFile)) {
                            Files.copy(sourceFile, targetFile, StandardCopyOption.REPLACE_EXISTING);
                            result.addSuccess("Exported " + variantName + " to " + targetFile);
                            logger.info("Exported texture {} to game directory", variantName);
                        } else {
                            result.addError("Source file not found for " + variantName);
                        }
                        
                        operation.incrementCompleted();
                        notifyPipelineListeners(operation, PipelineEventType.PROGRESS);
                        
                    } catch (Exception e) {
                        result.addError("Failed to export " + variantName + ": " + e.getMessage());
                        logger.error("Failed to export texture {}", variantName, e);
                    }
                }
                
                operation.setCompleted(true);
                notifyPipelineListeners(operation, PipelineEventType.COMPLETED);
                
                logger.info("Texture export completed: {} successes, {} errors, {} warnings",
                    result.getSuccessCount(), result.getErrorCount(), result.getWarningCount());
                
                return result;
                
            } catch (Exception e) {
                operation.setFailed(true);
                operation.setErrorMessage(e.getMessage());
                notifyPipelineListeners(operation, PipelineEventType.FAILED);
                
                PipelineResult errorResult = new PipelineResult(operationId);
                errorResult.addError("Pipeline operation failed: " + e.getMessage());
                return errorResult;
                
            } finally {
                activeOperations.remove(operationId);
            }
        });
    }
    
    /**
     * Validates all assets against game requirements.
     */
    public CompletableFuture<ValidationSummary> validateAllAssets() {
        return CompletableFuture.supplyAsync(() -> {
            String operationId = "validate_assets_" + System.currentTimeMillis();
            PipelineOperation operation = new PipelineOperation(operationId, "Validate All Assets", 4); // 4 cow variants
            
            try {
                activeOperations.put(operationId, operation);
                notifyPipelineListeners(operation, PipelineEventType.STARTED);
                
                ValidationSummary summary = new ValidationSummary();
                String[] variants = {"default_cow", "angus_cow", "highland_cow", "jersey_cow"};
                
                for (String variant : variants) {
                    operation.setCurrentTask("Validating " + variant);
                    
                    try {
                        // Validate coordinate system
                        CoordinateSystemValidator.ValidationResult result = 
                            CoordinateSystemValidator.runCompleteValidation();
                        
                        summary.addVariantResult(variant, result);
                        
                        if (result.isPassed()) {
                            logger.debug("Validation passed for {}", variant);
                        } else {
                            logger.warn("Validation issues for {}: {}", variant, result.toString());
                        }
                        
                    } catch (Exception e) {
                        summary.addValidationError(variant, "Validation exception: " + e.getMessage());
                        logger.error("Validation failed for {}", variant, e);
                    }
                    
                    operation.incrementCompleted();
                    notifyPipelineListeners(operation, PipelineEventType.PROGRESS);
                }
                
                operation.setCompleted(true);
                notifyPipelineListeners(operation, PipelineEventType.COMPLETED);
                
                logger.info("Asset validation completed: {} valid, {} with issues", 
                    summary.getValidCount(), summary.getInvalidCount());
                
                return summary;
                
            } catch (Exception e) {
                operation.setFailed(true);
                operation.setErrorMessage(e.getMessage());
                notifyPipelineListeners(operation, PipelineEventType.FAILED);
                throw new RuntimeException("Asset validation failed", e);
                
            } finally {
                activeOperations.remove(operationId);
            }
        });
    }
    
    /**
     * Synchronizes assets between OpenMason and game directories.
     */
    public CompletableFuture<PipelineResult> synchronizeAssets(boolean dryRun) {
        return CompletableFuture.supplyAsync(() -> {
            String operationId = "sync_assets_" + System.currentTimeMillis();
            PipelineOperation operation = new PipelineOperation(operationId, 
                dryRun ? "Synchronize Assets (Dry Run)" : "Synchronize Assets", 8); // Textures + Models
            
            try {
                activeOperations.put(operationId, operation);
                notifyPipelineListeners(operation, PipelineEventType.STARTED);
                
                if (gameTextureDirectory == null || gameModelDirectory == null) {
                    throw new IllegalStateException("Game directories not available");
                }
                
                PipelineResult result = new PipelineResult(operationId);
                
                if (!dryRun) {
                    // Create backup before synchronization
                    createBackup("sync", gameTextureDirectory);
                    createBackup("sync", gameModelDirectory);
                }
                
                // Synchronize texture files
                synchronizeDirectory("textures", gameTextureDirectory, result, operation, dryRun);
                
                // Synchronize model files  
                synchronizeDirectory("models", gameModelDirectory, result, operation, dryRun);
                
                operation.setCompleted(true);
                notifyPipelineListeners(operation, PipelineEventType.COMPLETED);
                
                logger.info("Asset synchronization {}: {} operations", 
                    dryRun ? "analyzed" : "completed", result.getTotalCount());
                
                return result;
                
            } catch (Exception e) {
                operation.setFailed(true);
                operation.setErrorMessage(e.getMessage());
                notifyPipelineListeners(operation, PipelineEventType.FAILED);
                
                PipelineResult errorResult = new PipelineResult(operationId);
                errorResult.addError("Synchronization failed: " + e.getMessage());
                return errorResult;
                
            } finally {
                activeOperations.remove(operationId);
            }
        });
    }
    
    /**
     * Creates backup of a directory before making changes.
     */
    private void createBackup(String operation, Path sourceDirectory) throws IOException {
        if (backupDirectory == null || !Files.exists(sourceDirectory)) {
            return;
        }
        
        String timestamp = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyy-MM-dd_HH-mm-ss"));
        String backupName = operation + "_" + sourceDirectory.getFileName() + "_" + timestamp;
        Path backupPath = backupDirectory.resolve(backupName);
        
        copyDirectory(sourceDirectory, backupPath);
        logger.info("Created backup: {}", backupPath);
    }
    
    /**
     * Copies directory recursively.
     */
    private void copyDirectory(Path source, Path target) throws IOException {
        Files.walk(source).forEach(sourcePath -> {
            try {
                Path targetPath = target.resolve(source.relativize(sourcePath));
                if (Files.isDirectory(sourcePath)) {
                    Files.createDirectories(targetPath);
                } else {
                    Files.copy(sourcePath, targetPath, StandardCopyOption.REPLACE_EXISTING);
                }
            } catch (IOException e) {
                logger.error("Failed to copy {} to {}", sourcePath, target, e);
            }
        });
    }
    
    /**
     * Synchronizes files in a directory.
     */
    private void synchronizeDirectory(String type, Path gameDirectory, PipelineResult result, 
                                    PipelineOperation operation, boolean dryRun) throws IOException {
        
        // For simplicity, this implementation assumes files are managed by OpenMason
        // and should be copied to the game directory
        
        String[] variants = {"default_cow", "angus_cow", "highland_cow", "jersey_cow"};
        
        for (String variant : variants) {
            operation.setCurrentTask("Synchronizing " + type + " for " + variant);
            
            try {
                Path sourceFile = findSourceFile(type, variant);
                Path targetFile = gameDirectory.resolve(variant + ".json");
                
                if (sourceFile != null && Files.exists(sourceFile)) {
                    if (shouldSynchronize(sourceFile, targetFile)) {
                        if (dryRun) {
                            result.addInfo("Would synchronize " + variant + " (" + type + ")");
                        } else {
                            Files.copy(sourceFile, targetFile, StandardCopyOption.REPLACE_EXISTING);
                            result.addSuccess("Synchronized " + variant + " (" + type + ")");
                        }
                    } else {
                        result.addInfo("No changes needed for " + variant + " (" + type + ")");
                    }
                } else {
                    result.addWarning("Source file not found for " + variant + " (" + type + ")");
                }
                
            } catch (Exception e) {
                result.addError("Failed to synchronize " + variant + " (" + type + "): " + e.getMessage());
            }
            
            operation.incrementCompleted();
            notifyPipelineListeners(operation, PipelineEventType.PROGRESS);
        }
    }
    
    /**
     * Determines if files should be synchronized based on modification times.
     */
    private boolean shouldSynchronize(Path sourceFile, Path targetFile) throws IOException {
        if (!Files.exists(targetFile)) {
            return true; // Target doesn't exist, need to copy
        }
        
        return Files.getLastModifiedTime(sourceFile).compareTo(Files.getLastModifiedTime(targetFile)) > 0;
    }
    
    /**
     * Finds source file for a given type and variant.
     */
    private Path findSourceFile(String type, String variant) {
        if ("textures".equals(type)) {
            return findTextureSourceFile(variant);
        } else if ("models".equals(type)) {
            return findModelSourceFile(variant);
        }
        return null;
    }
    
    /**
     * Finds texture source file for a variant.
     */
    private Path findTextureSourceFile(String variant) {
        // Look in OpenMason resources directory
        Path resourcePath = Paths.get("openmason-tool/src/main/resources/textures/" + variant + ".json");
        if (Files.exists(resourcePath)) {
            return resourcePath;
        }
        
        // Look in game resources directory (for cases where files are already there)
        if (gameTextureDirectory != null) {
            Path gamePath = gameTextureDirectory.resolve(variant + ".json");
            if (Files.exists(gamePath)) {
                return gamePath;
            }
        }
        
        return null;
    }
    
    /**
     * Finds model source file for a variant.
     */
    private Path findModelSourceFile(String variant) {
        // Models are typically shared, so look for standard_cow.json
        Path resourcePath = Paths.get("openmason-tool/src/main/resources/models/standard_cow.json");
        if (Files.exists(resourcePath)) {
            return resourcePath;
        }
        
        if (gameModelDirectory != null) {
            Path gamePath = gameModelDirectory.resolve("standard_cow.json");
            if (Files.exists(gamePath)) {
                return gamePath;
            }
        }
        
        return null;
    }
    
    /**
     * Notifies pipeline listeners of events.
     */
    private void notifyPipelineListeners(PipelineOperation operation, PipelineEventType eventType) {
        for (PipelineListener listener : pipelineListeners) {
            try {
                listener.onPipelineEvent(operation, eventType);
            } catch (Exception e) {
                logger.error("Error notifying pipeline listener", e);
            }
        }
    }
    
    // Getter methods and listener management
    public void addPipelineListener(PipelineListener listener) {
        pipelineListeners.add(listener);
    }
    
    public void removePipelineListener(PipelineListener listener) {
        pipelineListeners.remove(listener);
    }
    
    public Map<String, PipelineOperation> getActiveOperations() {
        return new ConcurrentHashMap<>(activeOperations);
    }
    
    public boolean isStonebreakProjectDetected() {
        return stonebreakProjectRoot != null;
    }
    
    public Path getStonebreakProjectRoot() {
        return stonebreakProjectRoot;
    }
    
    // Nested classes for pipeline operations and results
    
    public static class PipelineOperation {
        private final String id;
        private final String name;
        private final int totalTasks;
        private int completedTasks = 0;
        private String currentTask = "";
        private boolean completed = false;
        private boolean failed = false;
        private String errorMessage = "";
        private final long startTime = System.currentTimeMillis();
        private long endTime = 0;
        
        public PipelineOperation(String id, String name, int totalTasks) {
            this.id = id;
            this.name = name;
            this.totalTasks = totalTasks;
        }
        
        // Getters and setters
        public String getId() { return id; }
        public String getName() { return name; }
        public int getTotalTasks() { return totalTasks; }
        public int getCompletedTasks() { return completedTasks; }
        public String getCurrentTask() { return currentTask; }
        public boolean isCompleted() { return completed; }
        public boolean isFailed() { return failed; }
        public String getErrorMessage() { return errorMessage; }
        public long getStartTime() { return startTime; }
        public long getEndTime() { return endTime; }
        
        public void incrementCompleted() { completedTasks++; }
        public void setCurrentTask(String task) { this.currentTask = task; }
        public void setCompleted(boolean completed) { 
            this.completed = completed;
            if (completed) this.endTime = System.currentTimeMillis();
        }
        public void setFailed(boolean failed) { 
            this.failed = failed;
            if (failed) this.endTime = System.currentTimeMillis();
        }
        public void setErrorMessage(String errorMessage) { this.errorMessage = errorMessage; }
        
        public double getProgress() {
            return totalTasks > 0 ? (double) completedTasks / totalTasks : 0.0;
        }
    }
    
    public static class PipelineResult {
        private final String operationId;
        private final List<String> successes = new ArrayList<>();
        private final List<String> errors = new ArrayList<>();
        private final List<String> warnings = new ArrayList<>();
        private final List<String> info = new ArrayList<>();
        
        public PipelineResult(String operationId) {
            this.operationId = operationId;
        }
        
        public void addSuccess(String message) { successes.add(message); }
        public void addError(String message) { errors.add(message); }
        public void addWarning(String message) { warnings.add(message); }
        public void addInfo(String message) { info.add(message); }
        
        public String getOperationId() { return operationId; }
        public List<String> getSuccesses() { return new ArrayList<>(successes); }
        public List<String> getErrors() { return new ArrayList<>(errors); }
        public List<String> getWarnings() { return new ArrayList<>(warnings); }
        public List<String> getInfo() { return new ArrayList<>(info); }
        
        public int getSuccessCount() { return successes.size(); }
        public int getErrorCount() { return errors.size(); }
        public int getWarningCount() { return warnings.size(); }
        public int getInfoCount() { return info.size(); }
        public int getTotalCount() { return successes.size() + errors.size() + warnings.size() + info.size(); }
        
        public boolean isSuccessful() { return errors.isEmpty(); }
    }
    
    public static class ValidationSummary {
        private final Map<String, CoordinateSystemValidator.ValidationResult> variantResults = new ConcurrentHashMap<>();
        private final List<String> globalErrors = new ArrayList<>();
        
        public void addVariantResult(String variant, CoordinateSystemValidator.ValidationResult result) {
            variantResults.put(variant, result);
        }
        
        public void addValidationError(String variant, String error) {
            globalErrors.add(variant + ": " + error);
        }
        
        public Map<String, CoordinateSystemValidator.ValidationResult> getVariantResults() {
            return new ConcurrentHashMap<>(variantResults);
        }
        
        public List<String> getGlobalErrors() { return new ArrayList<>(globalErrors); }
        
        public int getValidCount() {
            return (int) variantResults.values().stream().filter(CoordinateSystemValidator.ValidationResult::isPassed).count();
        }
        
        public int getInvalidCount() {
            return variantResults.size() - getValidCount() + globalErrors.size();
        }
        
        public boolean isAllValid() {
            return globalErrors.isEmpty() && variantResults.values().stream().allMatch(CoordinateSystemValidator.ValidationResult::isPassed);
        }
    }
    
    public interface PipelineListener {
        void onPipelineEvent(PipelineOperation operation, PipelineEventType eventType);
    }
    
    public enum PipelineEventType {
        STARTED, PROGRESS, COMPLETED, FAILED
    }
}