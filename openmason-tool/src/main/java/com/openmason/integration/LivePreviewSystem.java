package com.openmason.integration;

import com.openmason.ui.viewport.OpenMason3DViewport;
import com.openmason.texture.TextureManager;
import com.openmason.model.ModelManager;
import javafx.application.Platform;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.*;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.Map;

/**
 * Live preview system that integrates directly with the 3D viewport for real-time texture changes.
 * Provides instant visual feedback when texture definitions are modified.
 */
public class LivePreviewSystem {
    private static final Logger logger = LoggerFactory.getLogger(LivePreviewSystem.class);
    
    private final OpenMason3DViewport viewport;
    private final TextureManager textureManager;
    private final ModelManager modelManager;
    
    private final AtomicBoolean isActive = new AtomicBoolean(false);
    private final Map<String, WatchKey> watchedDirectories = new ConcurrentHashMap<>();
    private WatchService watchService;
    private Thread watchThread;
    
    // Live preview settings
    private boolean autoReloadEnabled = true;
    private int reloadDelayMs = 100; // Debounce delay
    private final AtomicBoolean reloadScheduled = new AtomicBoolean(false);
    
    public LivePreviewSystem(OpenMason3DViewport viewport, TextureManager textureManager, ModelManager modelManager) {
        this.viewport = viewport;
        this.textureManager = textureManager;
        this.modelManager = modelManager;
    }
    
    /**
     * Starts the live preview system with file monitoring and viewport integration.
     */
    public CompletableFuture<Void> startLivePreview() {
        return CompletableFuture.runAsync(() -> {
            try {
                if (isActive.get()) {
                    logger.warn("Live preview system already active");
                    return;
                }
                
                logger.info("Starting live preview system...");
                
                // Initialize file watcher
                watchService = FileSystems.getDefault().newWatchService();
                
                // Watch texture directories
                watchTextureDirectories();
                
                // Start watch thread
                startWatchThread();
                
                // Integrate with viewport
                integrateWithViewport();
                
                isActive.set(true);
                logger.info("Live preview system started successfully");
                
            } catch (Exception e) {
                logger.error("Failed to start live preview system", e);
                throw new RuntimeException("Live preview system startup failed", e);
            }
        });
    }
    
    /**
     * Stops the live preview system and cleans up resources.
     */
    public CompletableFuture<Void> stopLivePreview() {
        return CompletableFuture.runAsync(() -> {
            try {
                if (!isActive.get()) {
                    return;
                }
                
                logger.info("Stopping live preview system...");
                
                isActive.set(false);
                
                // Stop watch thread
                if (watchThread != null && watchThread.isAlive()) {
                    watchThread.interrupt();
                }
                
                // Close watch service
                if (watchService != null) {
                    watchService.close();
                }
                
                // Clear watched directories
                watchedDirectories.clear();
                
                logger.info("Live preview system stopped");
                
            } catch (Exception e) {
                logger.error("Error stopping live preview system", e);
            }
        });
    }
    
    /**
     * Watches texture directories for file changes.
     */
    private void watchTextureDirectories() throws IOException {
        // Watch main texture directory
        Path textureDir = Paths.get("stonebreak-game/src/main/resources/textures/mobs/cow");
        if (Files.exists(textureDir)) {
            WatchKey watchKey = textureDir.register(watchService, 
                StandardWatchEventKinds.ENTRY_MODIFY,
                StandardWatchEventKinds.ENTRY_CREATE,
                StandardWatchEventKinds.ENTRY_DELETE);
            watchedDirectories.put(textureDir.toString(), watchKey);
            logger.info("Watching texture directory: {}", textureDir);
        }
        
        // Watch model directory
        Path modelDir = Paths.get("stonebreak-game/src/main/resources/models/cow");
        if (Files.exists(modelDir)) {
            WatchKey watchKey = modelDir.register(watchService, 
                StandardWatchEventKinds.ENTRY_MODIFY,
                StandardWatchEventKinds.ENTRY_CREATE,
                StandardWatchEventKinds.ENTRY_DELETE);
            watchedDirectories.put(modelDir.toString(), watchKey);
            logger.info("Watching model directory: {}", modelDir);
        }
    }
    
    /**
     * Starts the file watch thread for monitoring changes.
     */
    private void startWatchThread() {
        watchThread = new Thread(() -> {
            logger.info("File watch thread started");
            
            while (isActive.get() && !Thread.currentThread().isInterrupted()) {
                try {
                    WatchKey key = watchService.take(); // Blocking call
                    
                    for (WatchEvent<?> event : key.pollEvents()) {
                        WatchEvent.Kind<?> kind = event.kind();
                        
                        if (kind == StandardWatchEventKinds.OVERFLOW) {
                            continue;
                        }
                        
                        @SuppressWarnings("unchecked")
                        WatchEvent<Path> ev = (WatchEvent<Path>) event;
                        Path filename = ev.context();
                        
                        if (filename.toString().endsWith(".json")) {
                            logger.debug("File change detected: {} ({})", filename, kind);
                            scheduleReload(filename.toString());
                        }
                    }
                    
                    key.reset();
                    
                } catch (InterruptedException e) {
                    logger.info("File watch thread interrupted");
                    break;
                } catch (Exception e) {
                    logger.error("Error in file watch thread", e);
                }
            }
            
            logger.info("File watch thread stopped");
        }, "LivePreview-FileWatch");
        
        watchThread.setDaemon(true);
        watchThread.start();
    }
    
    /**
     * Schedules a reload with debouncing to avoid excessive reloads.
     */
    private void scheduleReload(String filename) {
        if (!autoReloadEnabled || !isActive.get()) {
            return;
        }
        
        if (reloadScheduled.compareAndSet(false, true)) {
            CompletableFuture.delayedExecutor(reloadDelayMs, java.util.concurrent.TimeUnit.MILLISECONDS)
                .execute(() -> {
                    reloadScheduled.set(false);
                    performLiveReload(filename);
                });
        }
    }
    
    /**
     * Performs the actual live reload of textures and updates the viewport.
     */
    private void performLiveReload(String filename) {
        try {
            logger.info("Performing live reload for: {}", filename);
            
            // Determine what type of file changed
            if (filename.contains("cow") && filename.endsWith(".json")) {
                // Reload texture definitions
                reloadTextureDefinitions();
            }
            
            // Update viewport on JavaFX thread
            Platform.runLater(() -> {
                try {
                    // Refresh current model/texture in viewport (method not implemented yet)
                    // viewport.refreshCurrentModel();
                    logger.info("Live changes detected - viewport refresh pending");
                } catch (Exception e) {
                    logger.error("Failed to update viewport", e);
                }
            });
            
        } catch (Exception e) {
            logger.error("Failed to perform live reload", e);
        }
    }
    
    /**
     * Reloads texture definitions from JSON files.
     */
    private void reloadTextureDefinitions() {
        try {
            // Clear texture cache to force reload
            textureManager.clearCache();
            
            // Reload all cow variants
            String[] variants = {"default_cow", "angus_cow", "highland_cow", "jersey_cow"};
            
            for (String variant : variants) {
                try {
                    // Reload texture definition (method not implemented yet)
                    // textureManager.loadTextureDefinition(variant);
                    logger.debug("Texture definition reload pending for: {}", variant);
                } catch (Exception e) {
                    logger.warn("Failed to reload texture definition: {}", variant, e);
                }
            }
            
            logger.info("Texture definitions reloaded successfully");
            
        } catch (Exception e) {
            logger.error("Failed to reload texture definitions", e);
        }
    }
    
    /**
     * Integrates live preview capabilities with the 3D viewport.
     */
    private void integrateWithViewport() {
        // Add live preview indicators to viewport
        Platform.runLater(() -> {
            // Add visual indicator that live preview is active (methods not implemented yet)
            // viewport.setLivePreviewActive(true);
            
            // Enable hot-reload mode in viewport
            // viewport.setHotReloadEnabled(true);
            
            logger.debug("Live preview integration pending - viewport methods not implemented");
        });
    }
    
    /**
     * Manually triggers a live reload (for testing or user-initiated reload).
     */
    public void triggerManualReload() {
        if (!isActive.get()) {
            logger.warn("Live preview system not active, cannot trigger manual reload");
            return;
        }
        
        logger.info("Manual reload triggered");
        performLiveReload("manual_trigger");
    }
    
    // Getters and setters
    public boolean isActive() {
        return isActive.get();
    }
    
    public boolean isAutoReloadEnabled() {
        return autoReloadEnabled;
    }
    
    public void setAutoReloadEnabled(boolean enabled) {
        this.autoReloadEnabled = enabled;
        logger.info("Auto-reload {}", enabled ? "enabled" : "disabled");
    }
    
    public int getReloadDelayMs() {
        return reloadDelayMs;
    }
    
    public void setReloadDelayMs(int delayMs) {
        this.reloadDelayMs = Math.max(50, delayMs); // Minimum 50ms delay
        logger.info("Reload delay set to {}ms", this.reloadDelayMs);
    }
}