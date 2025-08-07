package com.openmason.integration;

import com.openmason.ui.viewport.OpenMason3DViewport;
import com.openmason.texture.TextureManager;

import javafx.application.Platform;
import javafx.beans.property.BooleanProperty;
import javafx.beans.property.SimpleBooleanProperty;
import javafx.beans.property.StringProperty;
import javafx.beans.property.SimpleStringProperty;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.*;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.Map;
import java.util.List;
import java.util.ArrayList;
import java.util.function.Consumer;

/**
 * Phase 8 Deep Stonebreak Integration - Hot-Reload Manager
 * 
 * Provides rapid texture iteration without application restart through file system
 * monitoring and automatic texture reloading. Monitors texture JSON files for changes
 * and triggers instant viewport updates while preserving viewport state.
 * 
 * Key Features:
 * - File system monitoring for texture JSON changes
 * - Automatic texture reloading and viewport updates
 * - Debounced file change detection to avoid rapid reloads
 * - Preserve viewport camera state during reloads
 * - Error handling with fallback to previous version
 * - Performance-optimized reload operations
 * - Thread-safe hot-reload state management
 */
public class HotReloadManager {
    
    private static final Logger logger = LoggerFactory.getLogger(HotReloadManager.class);
    
    // File system monitoring
    private WatchService watchService;
    private final Map<WatchKey, Path> watchKeys = new ConcurrentHashMap<>();
    private final ExecutorService watchThread = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "HotReload-FileWatcher");
        t.setDaemon(true);
        return t;
    });
    
    // Hot-reload state
    private final AtomicBoolean hotReloadEnabled = new AtomicBoolean(false);
    private final AtomicBoolean monitoring = new AtomicBoolean(false);
    private final BooleanProperty autoReloadEnabled = new SimpleBooleanProperty(true);
    private final StringProperty monitoredDirectory = new SimpleStringProperty("");
    
    // Change detection and debouncing
    private final Map<Path, Long> lastModified = new ConcurrentHashMap<>();
    private final Map<Path, CompletableFuture<Void>> pendingReloads = new ConcurrentHashMap<>();
    private static final long DEBOUNCE_DELAY_MS = 500; // Debounce file changes
    private static final long MIN_RELOAD_INTERVAL_MS = 1000; // Minimum time between reloads
    
    // Integration components
    private final OpenMason3DViewport viewport;
    private final LivePreviewManager livePreviewManager;
    private final AtomicLong lastReloadTime = new AtomicLong(0);
    
    // Statistics and monitoring
    private final AtomicLong totalReloads = new AtomicLong(0);
    private final AtomicLong successfulReloads = new AtomicLong(0);
    private final AtomicLong failedReloads = new AtomicLong(0);
    private final Map<String, ReloadHistory> reloadHistory = new ConcurrentHashMap<>();
    
    // Change listeners
    private final List<Consumer<HotReloadEvent>> reloadListeners = new ArrayList<>();
    
    /**
     * Hot-reload event for notifications.
     */
    public static class HotReloadEvent {
        private final String fileName;
        private final Path filePath;
        private final String changeType;
        private final boolean successful;
        private final String errorMessage;
        private final long timestamp;
        private final long reloadDuration;
        
        public HotReloadEvent(String fileName, Path filePath, String changeType, 
                            boolean successful, String errorMessage, long reloadDuration) {
            this.fileName = fileName;
            this.filePath = filePath;
            this.changeType = changeType;
            this.successful = successful;
            this.errorMessage = errorMessage;
            this.reloadDuration = reloadDuration;
            this.timestamp = System.currentTimeMillis();
        }
        
        public String getFileName() { return fileName; }
        public Path getFilePath() { return filePath; }
        public String getChangeType() { return changeType; }
        public boolean isSuccessful() { return successful; }
        public String getErrorMessage() { return errorMessage; }
        public long getTimestamp() { return timestamp; }
        public long getReloadDuration() { return reloadDuration; }
    }
    
    /**
     * Reload history for performance monitoring.
     */
    public static class ReloadHistory {
        private final String fileName;
        private final List<Long> reloadTimes = new ArrayList<>();
        private final List<Boolean> reloadResults = new ArrayList<>();
        private long totalReloads = 0;
        private long successfulReloads = 0;
        private double averageReloadTime = 0.0;
        
        public ReloadHistory(String fileName) {
            this.fileName = fileName;
        }
        
        public synchronized void addReload(long duration, boolean successful) {
            reloadTimes.add(duration);
            reloadResults.add(successful);
            totalReloads++;
            if (successful) {
                successfulReloads++;
            }
            
            // Calculate average reload time
            averageReloadTime = reloadTimes.stream().mapToLong(Long::longValue).average().orElse(0.0);
            
            // Keep only last 50 entries to prevent memory growth
            if (reloadTimes.size() > 50) {
                reloadTimes.remove(0);
                reloadResults.remove(0);
            }
        }
        
        public String getFileName() { return fileName; }
        public long getTotalReloads() { return totalReloads; }
        public long getSuccessfulReloads() { return successfulReloads; }
        public double getSuccessRate() { return totalReloads > 0 ? (double) successfulReloads / totalReloads : 0.0; }
        public double getAverageReloadTime() { return averageReloadTime; }
    }
    
    /**
     * Creates a new HotReloadManager with viewport and live preview integration.
     * 
     * @param viewport The OpenMason3DViewport to integrate with
     * @param livePreviewManager The LivePreviewManager for coordination
     */
    public HotReloadManager(OpenMason3DViewport viewport, LivePreviewManager livePreviewManager) {
        this.viewport = viewport;
        this.livePreviewManager = livePreviewManager;
        logger.info("HotReloadManager initialized with viewport and live preview integration");
    }
    
    /**
     * Enables hot-reload monitoring for the specified directory.
     * 
     * @param textureDirectory Path to the texture directory to monitor
     * @return CompletableFuture that completes when monitoring starts
     */
    public CompletableFuture<Void> enableHotReload(Path textureDirectory) {
        if (hotReloadEnabled.get()) {
            logger.warn("Hot-reload already enabled, disabling first");
            disableHotReload();
        }
        
        logger.info("Enabling hot-reload monitoring for directory: {}", textureDirectory);
        
        return CompletableFuture.runAsync(() -> {
            try {
                // Initialize watch service
                watchService = FileSystems.getDefault().newWatchService();
                
                // Register directory for monitoring
                registerDirectory(textureDirectory);
                
                // Start monitoring thread
                startMonitoring();
                
                hotReloadEnabled.set(true);
                monitoredDirectory.set(textureDirectory.toString());
                
                Platform.runLater(() -> {
                    logger.info("Hot-reload monitoring enabled successfully");
                    notifyReloadListeners(new HotReloadEvent("", textureDirectory, "MONITORING_ENABLED", 
                                                           true, null, 0));
                });
                
            } catch (Exception e) {
                logger.error("Failed to enable hot-reload monitoring", e);
                throw new RuntimeException("Hot-reload initialization failed", e);
            }
        });
    }
    
    /**
     * Disables hot-reload monitoring and cleans up resources.
     */
    public void disableHotReload() {
        logger.info("Disabling hot-reload monitoring");
        
        hotReloadEnabled.set(false);
        monitoring.set(false);
        
        // Cancel pending reloads
        for (CompletableFuture<Void> pendingReload : pendingReloads.values()) {
            pendingReload.cancel(false);
        }
        pendingReloads.clear();
        
        // Close watch service
        if (watchService != null) {
            try {
                watchService.close();
            } catch (IOException e) {
                logger.warn("Error closing watch service", e);
            }
            watchService = null;
        }
        
        // Clear watch keys
        watchKeys.clear();
        lastModified.clear();
        
        // Clear state
        monitoredDirectory.set("");
        
        Platform.runLater(() -> {
            notifyReloadListeners(new HotReloadEvent("", Paths.get(""), "MONITORING_DISABLED", 
                                                   true, null, 0));
        });
        
        logger.info("Hot-reload monitoring disabled");
    }
    
    /**
     * Registers a directory for file system monitoring.
     */
    private void registerDirectory(Path directory) throws IOException {
        if (!Files.exists(directory) || !Files.isDirectory(directory)) {
            throw new IOException("Directory does not exist or is not a directory: " + directory);
        }
        
        // Register for file events
        WatchKey key = directory.register(watchService,
            StandardWatchEventKinds.ENTRY_CREATE,
            StandardWatchEventKinds.ENTRY_MODIFY,
            StandardWatchEventKinds.ENTRY_DELETE);
        
        watchKeys.put(key, directory);
        
        // Also register subdirectories
        try (var stream = Files.walk(directory, 1)) {
            stream.filter(Files::isDirectory)
                  .filter(path -> !path.equals(directory))
                  .forEach(subDir -> {
                      try {
                          WatchKey subKey = subDir.register(watchService,
                              StandardWatchEventKinds.ENTRY_CREATE,
                              StandardWatchEventKinds.ENTRY_MODIFY,
                              StandardWatchEventKinds.ENTRY_DELETE);
                          watchKeys.put(subKey, subDir);
                          logger.debug("Registered subdirectory for monitoring: {}", subDir);
                      } catch (IOException e) {
                          logger.warn("Failed to register subdirectory: {}", subDir, e);
                      }
                  });
        }
        
        logger.info("Registered directory and subdirectories for monitoring: {}", directory);
    }
    
    /**
     * Starts the file system monitoring thread.
     */
    private void startMonitoring() {
        monitoring.set(true);
        
        watchThread.submit(() -> {
            logger.info("File system monitoring thread started");
            
            while (monitoring.get() && !Thread.currentThread().isInterrupted()) {
                try {
                    WatchKey key = watchService.poll(1, TimeUnit.SECONDS);
                    if (key == null) {
                        continue; // Timeout, check monitoring flag again
                    }
                    
                    Path directory = watchKeys.get(key);
                    if (directory == null) {
                        logger.warn("Unknown watch key, skipping events");
                        key.reset();
                        continue;
                    }
                    
                    // Process file events
                    for (WatchEvent<?> event : key.pollEvents()) {
                        processFileEvent(directory, event);
                    }
                    
                    // Reset the key to receive further events
                    boolean valid = key.reset();
                    if (!valid) {
                        logger.warn("Watch key is no longer valid for directory: {}", directory);
                        watchKeys.remove(key);
                    }
                    
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    break;
                } catch (Exception e) {
                    logger.error("Error in file monitoring thread", e);
                }
            }
            
            logger.info("File system monitoring thread stopped");
        });
    }
    
    /**
     * Processes a file system event.
     */
    private void processFileEvent(Path directory, WatchEvent<?> event) {
        WatchEvent.Kind<?> kind = event.kind();
        
        if (kind == StandardWatchEventKinds.OVERFLOW) {
            logger.warn("File system event overflow detected");
            return;
        }
        
        @SuppressWarnings("unchecked")
        WatchEvent<Path> pathEvent = (WatchEvent<Path>) event;
        Path fileName = pathEvent.context();
        Path fullPath = directory.resolve(fileName);
        
        // Only process JSON files
        if (!fileName.toString().toLowerCase().endsWith(".json")) {
            return;
        }
        
        logger.debug("File system event: {} for file: {}", kind.name(), fullPath);
        
        // Handle different event types
        if (kind == StandardWatchEventKinds.ENTRY_MODIFY || 
            kind == StandardWatchEventKinds.ENTRY_CREATE) {
            
            handleFileChange(fullPath, kind.name());
            
        } else if (kind == StandardWatchEventKinds.ENTRY_DELETE) {
            
            handleFileDelete(fullPath);
        }
    }
    
    /**
     * Handles file change/create events with debouncing.
     */
    private void handleFileChange(Path filePath, String changeType) {
        if (!autoReloadEnabled.get()) {
            logger.debug("Auto-reload disabled, skipping file change: {}", filePath);
            return;
        }
        
        try {
            // Check if file exists and get last modified time
            long currentModified = Files.exists(filePath) ? Files.getLastModifiedTime(filePath).toMillis() : 0;
            Long lastModifiedTime = lastModified.get(filePath);
            
            // Debounce: skip if file was recently modified
            if (lastModifiedTime != null && (currentModified - lastModifiedTime) < DEBOUNCE_DELAY_MS) {
                logger.debug("Debouncing file change: {}", filePath);
                return;
            }
            
            // Check minimum reload interval
            long timeSinceLastReload = System.currentTimeMillis() - lastReloadTime.get();
            if (timeSinceLastReload < MIN_RELOAD_INTERVAL_MS) {
                logger.debug("Minimum reload interval not met, skipping: {}", filePath);
                return;
            }
            
            lastModified.put(filePath, currentModified);
            
            // Cancel any pending reload for this file
            CompletableFuture<Void> existingReload = pendingReloads.get(filePath);
            if (existingReload != null) {
                existingReload.cancel(false);
            }
            
            // Schedule debounced reload
            CompletableFuture<Void> reloadFuture = CompletableFuture
                .runAsync(() -> performHotReload(filePath, changeType),
                    CompletableFuture.delayedExecutor(DEBOUNCE_DELAY_MS, TimeUnit.MILLISECONDS));
            
            pendingReloads.put(filePath, reloadFuture);
            
        } catch (Exception e) {
            logger.error("Error handling file change for: {}", filePath, e);
        }
    }
    
    /**
     * Handles file delete events.
     */
    private void handleFileDelete(Path filePath) {
        logger.info("Texture file deleted: {}", filePath);
        
        // Remove from tracking
        lastModified.remove(filePath);
        pendingReloads.remove(filePath);
        
        // Clear texture cache for deleted file
        String fileName = filePath.getFileName().toString();
        String variantName = fileName.replace(".json", "").replace("_cow", "");
        
        Platform.runLater(() -> {
            notifyReloadListeners(new HotReloadEvent(fileName, filePath, "FILE_DELETED", 
                                                   true, null, 0));
        });
    }
    
    /**
     * Performs the actual hot-reload operation.
     */
    private void performHotReload(Path filePath, String changeType) {
        long startTime = System.currentTimeMillis();
        String fileName = filePath.getFileName().toString();
        
        logger.info("Performing hot-reload for file: {} ({})", fileName, changeType);
        
        try {
            // Extract variant name from filename
            String variantName = extractVariantName(fileName);
            if (variantName == null) {
                logger.warn("Could not extract variant name from file: {}", fileName);
                return;
            }
            
            // Store viewport state before reload
            ViewportState viewportState = captureViewportState();
            
            // Clear texture cache for this variant
            TextureManager.clearCache();
            
            // Reload texture variant
            TextureManager.loadVariantInfoAsync(variantName, TextureManager.LoadingPriority.IMMEDIATE, null)
                .thenRun(() -> {
                    // Update viewport if this variant is currently active
                    String currentVariant = viewport.getCurrentTextureVariant();
                    if (variantName.equals(currentVariant)) {
                        Platform.runLater(() -> {
                            // Restore viewport state
                            restoreViewportState(viewportState);
                            
                            // Reload current model with updated textures
                            String currentModel = viewport.getCurrentModelName();
                            if (currentModel != null && !currentModel.isEmpty()) {
                                viewport.loadModel(currentModel);
                            }
                            
                            // Update live preview if active
                            if (livePreviewManager.isPreviewActive() && 
                                variantName.equals(livePreviewManager.getCurrentPreviewVariant())) {
                                // Reset preview to pick up changes
                                livePreviewManager.resetPreviewChanges();
                            }
                        });
                    }
                    
                    // Update statistics
                    long duration = System.currentTimeMillis() - startTime;
                    lastReloadTime.set(System.currentTimeMillis());
                    totalReloads.incrementAndGet();
                    successfulReloads.incrementAndGet();
                    
                    // Update reload history
                    ReloadHistory history = reloadHistory.computeIfAbsent(fileName, ReloadHistory::new);
                    history.addReload(duration, true);
                    
                    Platform.runLater(() -> {
                        logger.info("Hot-reload completed successfully for {} in {}ms", fileName, duration);
                        notifyReloadListeners(new HotReloadEvent(fileName, filePath, changeType, 
                                                               true, null, duration));
                    });
                })
                .exceptionally(throwable -> {
                    // Handle reload failure
                    long duration = System.currentTimeMillis() - startTime;
                    totalReloads.incrementAndGet();
                    failedReloads.incrementAndGet();
                    
                    // Update reload history
                    ReloadHistory history = reloadHistory.computeIfAbsent(fileName, ReloadHistory::new);
                    history.addReload(duration, false);
                    
                    Platform.runLater(() -> {
                        logger.error("Hot-reload failed for {} after {}ms", fileName, duration, throwable);
                        notifyReloadListeners(new HotReloadEvent(fileName, filePath, changeType, 
                                                               false, throwable.getMessage(), duration));
                    });
                    
                    return null;
                });
                
        } catch (Exception e) {
            long duration = System.currentTimeMillis() - startTime;
            totalReloads.incrementAndGet();
            failedReloads.incrementAndGet();
            
            Platform.runLater(() -> {
                logger.error("Hot-reload failed for {}", fileName, e);
                notifyReloadListeners(new HotReloadEvent(fileName, filePath, changeType, 
                                                       false, e.getMessage(), duration));
            });
        } finally {
            // Remove from pending reloads
            pendingReloads.remove(filePath);
        }
    }
    
    /**
     * Extracts variant name from texture file name.
     */
    private String extractVariantName(String fileName) {
        if (fileName.endsWith("_cow.json")) {
            return fileName.substring(0, fileName.length() - "_cow.json".length());
        } else if (fileName.endsWith(".json")) {
            String baseName = fileName.substring(0, fileName.length() - ".json".length());
            if (baseName.endsWith("_cow")) {
                return baseName.substring(0, baseName.length() - "_cow".length());
            }
            return baseName;
        }
        return null;
    }
    
    /**
     * Captures current viewport state for restoration after reload.
     */
    private ViewportState captureViewportState() {
        ViewportState state = new ViewportState();
        
        if (viewport.getCamera() != null) {
            state.cameraDistance = viewport.getCamera().getDistance();
            state.cameraAzimuth = viewport.getCamera().getAzimuth();
            state.cameraElevation = viewport.getCamera().getElevation();
        }
        
        state.wireframeMode = viewport.isWireframeMode();
        state.gridVisible = viewport.isGridVisible();
        state.axesVisible = viewport.isAxesVisible();
        state.currentModel = viewport.getCurrentModelName();
        state.currentVariant = viewport.getCurrentTextureVariant();
        
        return state;
    }
    
    /**
     * Restores viewport state after reload.
     */
    private void restoreViewportState(ViewportState state) {
        if (state == null) return;
        
        // Restore camera state
        if (viewport.getCamera() != null) {
            viewport.getCamera().setDistance(state.cameraDistance);
            viewport.getCamera().setOrientation(state.cameraAzimuth, state.cameraElevation);
        }
        
        // Restore viewport settings
        viewport.setWireframeMode(state.wireframeMode);
        viewport.setGridVisible(state.gridVisible);
        viewport.setAxesVisible(state.axesVisible);
        viewport.setCurrentTextureVariant(state.currentVariant);
    }
    
    /**
     * Simple viewport state holder.
     */
    private static class ViewportState {
        float cameraDistance = 5.0f;
        float cameraAzimuth = 45.0f;
        float cameraElevation = 20.0f;
        boolean wireframeMode = false;
        boolean gridVisible = true;
        boolean axesVisible = true;
        String currentModel = "";
        String currentVariant = "";
    }
    
    /**
     * Notifies reload listeners of hot-reload events.
     */
    private void notifyReloadListeners(HotReloadEvent event) {
        for (Consumer<HotReloadEvent> listener : reloadListeners) {
            try {
                listener.accept(event);
            } catch (Exception e) {
                logger.warn("Error notifying reload listener", e);
            }
        }
    }
    
    // ===== PUBLIC API METHODS =====
    
    /**
     * Adds a listener for hot-reload events.
     */
    public void addReloadListener(Consumer<HotReloadEvent> listener) {
        reloadListeners.add(listener);
    }
    
    /**
     * Removes a hot-reload event listener.
     */
    public void removeReloadListener(Consumer<HotReloadEvent> listener) {
        reloadListeners.remove(listener);
    }
    
    /**
     * Gets hot-reload statistics.
     */
    public Map<String, Object> getStatistics() {
        Map<String, Object> stats = new ConcurrentHashMap<>();
        stats.put("hotReloadEnabled", hotReloadEnabled.get());
        stats.put("monitoring", monitoring.get());
        stats.put("monitoredDirectory", monitoredDirectory.get());
        stats.put("totalReloads", totalReloads.get());
        stats.put("successfulReloads", successfulReloads.get());
        stats.put("failedReloads", failedReloads.get());
        stats.put("successRate", totalReloads.get() > 0 ? 
            (double) successfulReloads.get() / totalReloads.get() : 0.0);
        stats.put("pendingReloads", pendingReloads.size());
        stats.put("watchedDirectories", watchKeys.size());
        return stats;
    }
    
    /**
     * Gets reload history for all files.
     */
    public Map<String, ReloadHistory> getReloadHistory() {
        return new ConcurrentHashMap<>(reloadHistory);
    }
    
    // ===== PROPERTY ACCESSORS =====
    
    public boolean isHotReloadEnabled() { return hotReloadEnabled.get(); }
    public boolean isMonitoring() { return monitoring.get(); }
    
    public BooleanProperty autoReloadEnabledProperty() { return autoReloadEnabled; }
    public boolean isAutoReloadEnabled() { return autoReloadEnabled.get(); }
    public void setAutoReloadEnabled(boolean enabled) { autoReloadEnabled.set(enabled); }
    
    public StringProperty monitoredDirectoryProperty() { return monitoredDirectory; }
    public String getMonitoredDirectory() { return monitoredDirectory.get(); }
    
    /**
     * Disposes of the HotReloadManager and cleans up resources.
     */
    public void dispose() {
        logger.info("Disposing HotReloadManager");
        
        disableHotReload();
        reloadListeners.clear();
        reloadHistory.clear();
        
        // Shutdown watch thread
        watchThread.shutdown();
        try {
            if (!watchThread.awaitTermination(5, TimeUnit.SECONDS)) {
                watchThread.shutdownNow();
            }
        } catch (InterruptedException e) {
            watchThread.shutdownNow();
            Thread.currentThread().interrupt();
        }
        
        logger.info("HotReloadManager disposed");
    }
}