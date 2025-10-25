package com.openmason.ui.services;

import com.openmason.ui.viewport.OpenMason3DViewport;
import imgui.type.ImFloat;

/**
 * Performance monitoring service.
 * Follows Single Responsibility Principle - only manages performance metrics.
 */
public class PerformanceService {

    private float memoryUsage = 0.0f;
    private float frameRate = 0.0f;
    private final ImFloat progress = new ImFloat(0.0f);

    /**
     * Update memory usage metric.
     */
    public void updateMemoryUsage() {
        Runtime runtime = Runtime.getRuntime();
        long totalMemory = runtime.totalMemory();
        long freeMemory = runtime.freeMemory();
        long usedMemory = totalMemory - freeMemory;
        memoryUsage = usedMemory / (1024.0f * 1024.0f);
    }

    /**
     * Update frame rate metric from viewport.
     */
    public void updateFrameRate(OpenMason3DViewport viewport) {
        if (viewport != null) {
            frameRate = (float) viewport.getCurrentFPS();
        }
    }

    /**
     * Update all performance metrics.
     */
    public void updateAll(OpenMason3DViewport viewport) {
        updateMemoryUsage();
        updateFrameRate(viewport);
    }

    // Getters

    public float getMemoryUsage() {
        return memoryUsage;
    }

    public float getFrameRate() {
        return frameRate;
    }

    public ImFloat getProgress() {
        return progress;
    }

    public void setProgress(float progress) {
        this.progress.set(progress);
    }
}
