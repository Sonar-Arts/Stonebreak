package com.openmason.ui.viewport.model;

import com.openmason.deprecated.LegacyCowModelManager;
import com.openmason.deprecated.LegacyCowStonebreakModel;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.concurrent.CompletableFuture;
import java.util.function.Consumer;

/**
 * Handles asynchronous model loading with progress callbacks.
 * Follows Single Responsibility Principle - only handles model loading.
 */
public class AsyncModelLoader {

    private static final Logger logger = LoggerFactory.getLogger(AsyncModelLoader.class);

    private CompletableFuture<Void> currentLoadingFuture = null;

    /**
     * Load model asynchronously with progress feedback.
     * Returns future that completes when model is loaded.
     */
    public CompletableFuture<Void> loadModelAsync(String modelName, Consumer<LegacyCowStonebreakModel> onSuccess,
                                                   Consumer<Throwable> onError) {
        if (currentLoadingFuture != null && !currentLoadingFuture.isDone()) {
            logger.warn("Model loading already in progress, ignoring request for: {}", modelName);
            return currentLoadingFuture;
        }

        logger.debug("Starting async model load: {}", modelName);

        // Create progress callback
        LegacyCowModelManager.ProgressCallback progressCallback = new LegacyCowModelManager.ProgressCallback() {
            @Override
            public void onProgress(String operation, int current, int total, String details) {
                logger.trace("Model loading progress: {}% - {}", (current * 100 / total), details);
            }

            @Override
            public void onError(String operation, Throwable error) {
                logger.error("Model loading error in {}: {}", operation, error.getMessage());
            }

            @Override
            public void onComplete(String operation, Object result) {
                logger.debug("Model loading operation complete: {}", operation);
            }
        };

        // Load model info asynchronously
        currentLoadingFuture = LegacyCowModelManager.loadModelInfoAsync(modelName,
                LegacyCowModelManager.LoadingPriority.HIGH, progressCallback)
            .thenCompose(modelInfo -> {
                if (modelInfo == null) {
                    throw new RuntimeException("Failed to load model info for: " + modelName);
                }

                logger.debug("Model info loaded successfully: {}", modelInfo);

                // Create StonebreakModel from ModelInfo
                LegacyCowStonebreakModel model = new LegacyCowStonebreakModel(modelInfo,
                    LegacyCowModelManager.getStaticModelParts(modelName));

                logger.debug("StonebreakModel created successfully");
                return CompletableFuture.completedFuture(model);
            })
            .thenAccept(model -> {
                // This runs on background thread
                logger.info("Model loaded successfully: {}", modelName);

                // Call success callback
                if (onSuccess != null) {
                    onSuccess.accept(model);
                }

                // Clear the loading future
                currentLoadingFuture = null;
            })
            .exceptionally(throwable -> {
                logger.error("Failed to load model {}: {}", modelName, throwable.getMessage());
                throwable.printStackTrace();

                // Call error callback
                if (onError != null) {
                    onError.accept(throwable);
                }

                currentLoadingFuture = null;
                return null;
            });

        return currentLoadingFuture;
    }

    /**
     * Cancel any pending model loading.
     */
    public void cancelLoading() {
        if (currentLoadingFuture != null && !currentLoadingFuture.isDone()) {
            logger.info("Cancelling pending model loading");
            currentLoadingFuture.cancel(true);
            currentLoadingFuture = null;
        }
    }

    /**
     * Check if a model is currently loading.
     */
    public boolean isLoading() {
        return currentLoadingFuture != null && !currentLoadingFuture.isDone();
    }

    /**
     * Get current loading future (for UI status display).
     */
    public CompletableFuture<Void> getCurrentLoadingFuture() {
        return currentLoadingFuture;
    }
}
