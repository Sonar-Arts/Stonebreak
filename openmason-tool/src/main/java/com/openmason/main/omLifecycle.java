package com.openmason.main;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.openmason.api.lifecycle.LifecycleListener;
import com.openmason.main.systems.threading.MainThreadExecutor;

import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;
import java.util.function.Supplier;

/**
 * Application lifecycle management for OpenMason.
 * Handles startup, shutdown, and resource management throughout the application lifecycle, and
 * tells the registered {@link LifecycleListener}s (plugin contributions, #379) about it on the UI
 * thread: {@code started} when the main loop is about to run, {@code ready} when background start-up
 * work finished, {@code shuttingDown} before the UI and GL context are torn down.
 */
public class omLifecycle {
    
    private static final Logger logger = LoggerFactory.getLogger(omLifecycle.class);
    
    private static final int SHUTDOWN_TIMEOUT_SECONDS = 10;
    
    /** The registered listeners, read at each event (plugins may register late). */
    private volatile Supplier<List<LifecycleListener>> listeners = List::of;
    private final ExecutorService backgroundExecutor;
    private boolean isStarted = false;
    private volatile boolean isShuttingDown = false;
    private boolean notifiedShuttingDown = false;

    /**
     * Initialize application lifecycle manager.
     */
    public omLifecycle() {
        this.backgroundExecutor = Executors.newCachedThreadPool(r -> {
            Thread thread = new Thread(r, "OpenMason-Background");
            thread.setDaemon(true);
            return thread;
        });
        
        // Register shutdown hook for graceful shutdown
        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            if (!isShuttingDown) {
                // logger.info("Shutdown hook triggered");
                performShutdown();
            }
        }, "OpenMason-Shutdown"));
    }
    
    /** Where the listeners come from (the lifecycle contributions). */
    public void setListeners(Supplier<List<LifecycleListener>> listeners) {
        this.listeners = java.util.Objects.requireNonNull(listeners, "listeners");
    }

    /**
     * Tells the listeners the editor is shutting down, while the UI and GL context still exist.
     * Call from the UI thread at the start of the shutdown sequence; runs once.
     */
    public void notifyShuttingDown() {
        if (notifiedShuttingDown) {
            return;
        }
        notifiedShuttingDown = true;
        notifyListeners(LifecycleListener::shuttingDown);
    }

    /**
     * Called when application has started (UI loaded).
     */
    public void onApplicationStarted() {
        if (isStarted) {
            logger.warn("Application already started");
            return;
        }
        
        isStarted = true;
        // logger.info("Application lifecycle started");
        
        // Notify listeners of startup
        notifyListeners(LifecycleListener::started);
        
        // Perform background initialization
        CompletableFuture.runAsync(this::performBackgroundInitialization, backgroundExecutor)
            .thenRun(() -> {
                // logger.info("Application ready");
                // Listeners run on the UI thread; drained by the main loop.
                MainThreadExecutor.post(() -> notifyListeners(LifecycleListener::ready));
            })
            .exceptionally(throwable -> {
                logger.error("Background initialization failed", throwable);
                return null;
            });
    }
    
    /**
     * Called when application should shutdown.
     */
    public void onApplicationShutdown() {
        if (isShuttingDown) {
            logger.warn("Shutdown already in progress");
            return;
        }
        
        performShutdown();
    }
    
    /**
     * Perform application shutdown sequence.
     */
    private void performShutdown() {
        isShuttingDown = true;
        // logger.info("Beginning application shutdown sequence");
        
        try {
            // Shutdown background executor
            shutdownExecutor();
            
            // logger.info("Application shutdown sequence completed");
            
        } catch (Exception e) {
            logger.error("Error during shutdown sequence", e);
        }
    }
    
    /**
     * Perform background initialization tasks.
     */
    private void performBackgroundInitialization() {
        try {
            // logger.debug("Starting background initialization...");

            // Pre-load critical resources
            preloadCriticalResources();
            
            // Initialize performance monitoring
            initializePerformanceMonitoring();
            
            // logger.debug("Background initialization completed");
            
        } catch (Exception e) {
            logger.error("Background initialization failed", e);
            throw new RuntimeException("Failed to initialize application", e);
        }
    }
    
    /**
     * Pre-load critical application resources.
     */
    private void preloadCriticalResources() {
        // logger.debug("Pre-loading critical resources...");
        
        // TODO: Pre-load essential models and textures
        // TODO: Initialize texture and model caches
        // TODO: Validate Stonebreak integration paths
        
        // logger.debug("Critical resources pre-loaded");
    }
    
    /**
     * Initialize performance monitoring systems.
     */
    private void initializePerformanceMonitoring() {
        // logger.debug("Initializing performance monitoring...");
        
        // TODO: Set up frame rate monitoring
        // TODO: Initialize memory usage tracking
        // TODO: Set up GPU performance monitoring
        
        // logger.debug("Performance monitoring initialized");
    }
    
    /**
     * Shutdown the background executor service.
     */
    private void shutdownExecutor() {
        // logger.debug("Shutting down background executor...");
        
        backgroundExecutor.shutdown();
        
        try {
            if (!backgroundExecutor.awaitTermination(SHUTDOWN_TIMEOUT_SECONDS, TimeUnit.SECONDS)) {
                logger.warn("Background executor did not terminate within timeout, forcing shutdown");
                backgroundExecutor.shutdownNow();
                
                if (!backgroundExecutor.awaitTermination(2, TimeUnit.SECONDS)) {
                    logger.error("Background executor could not be terminated");
                }
            }
        } catch (InterruptedException e) {
            logger.warn("Interrupted while waiting for executor shutdown");
            backgroundExecutor.shutdownNow();
            Thread.currentThread().interrupt();
        }
        
        // logger.debug("Background executor shutdown completed");
    }
    
    /**
     * Notify all listeners with the given action.
     */
    private void notifyListeners(Consumer<LifecycleListener> action) {
        for (LifecycleListener listener : listeners.get()) {
            try {
                action.accept(listener);
            } catch (Exception e) {
                logger.error("Error notifying lifecycle listener: {}", listener.getClass().getSimpleName(), e);
            }
        }
    }
}