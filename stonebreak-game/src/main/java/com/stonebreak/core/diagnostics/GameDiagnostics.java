package com.stonebreak.core.diagnostics;

import com.stonebreak.core.Game;
import com.stonebreak.mobs.entities.EntityManager;
import com.stonebreak.ui.DebugOverlay;
import com.stonebreak.util.MemoryLeakDetector;
import com.openmason.engine.diagnostics.MemoryProfiler;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Memory / performance / debug-overlay diagnostic helpers previously hosted
 * on {@link Game}. Static methods kept for API parity — callers go through
 * {@code Game.xxx} which now delegates here.
 */
public final class GameDiagnostics {

    private static final Logger logger = LoggerFactory.getLogger(GameDiagnostics.class);

    private static long lastDebugTime = 0;
    private static long lastMemoryCheckTime = 0;

    private GameDiagnostics() {
    }

    /**
     * Prints a periodic memory + performance snapshot to stdout.
     * Rate-limited to one log every 5 seconds.
     */
    public static void displayDebugInfo() {
        long currentTime = System.currentTimeMillis();
        if (currentTime - lastDebugTime < 5000) {
            return;
        }
        lastDebugTime = currentTime;

        if (currentTime - lastMemoryCheckTime > 30000) {
            lastMemoryCheckTime = currentTime;

            Runtime runtime = Runtime.getRuntime();
            long maxMemory = runtime.maxMemory() / (1024 * 1024);
            long usedMemory = (runtime.totalMemory() - runtime.freeMemory()) / (1024 * 1024);
            double memoryUsagePercent = (double) usedMemory / maxMemory * 100;

            if (memoryUsagePercent > 98) {
                MemoryProfiler.getInstance().takeSnapshot("critical_memory_" + currentTime);
            } else if (memoryUsagePercent > 90) {
                MemoryProfiler.getInstance().takeSnapshot("high_memory_usage_" + currentTime);
            }
        }
    }

    /** Logs a one-line memory summary tagged with the supplied context. */
    public static void logDetailedMemoryInfo(String context) {
    }

    /**
     * Reports memory usage before/after a short wait.
     * Under ZGC we no longer invoke System.gc() directly.
     */
    public static void forceGCAndReport(String context) {
        Runtime runtime = Runtime.getRuntime();
        long beforeGC = (runtime.totalMemory() - runtime.freeMemory()) / (1024 * 1024);

        logger.debug("[GC] {} - Memory before GC: {}MB", context, beforeGC);
        logger.debug("[MEMORY] Relying on ZGC for optimal memory management");

        try {
            Thread.sleep(100);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }

        long afterGC = (runtime.totalMemory() - runtime.freeMemory()) / (1024 * 1024);
        long freed = beforeGC - afterGC;

        logger.debug("[GC] {} - Memory after GC: {}MB (freed {}{}MB)", context, afterGC, freed >= 0 ? "+" : "", freed);
    }

    public static void reportAllocations() {
        MemoryProfiler.getInstance().reportAllocations();
    }

    public static void printDetailedMemoryProfile() {
        MemoryProfiler.getInstance().printDetailedMemoryInfo();
    }

    public static void triggerMemoryLeakAnalysis() {
        MemoryLeakDetector detector = Game.getMemoryLeakDetector();
        if (detector != null) {
            detector.triggerLeakAnalysis();
        } else {
            logger.error("Memory leak detector not initialized!");
        }
    }

    /** Toggles the F3 debug overlay and clears cow-path debug data when hiding. */
    public static void toggleDebugOverlay() {
        DebugOverlay overlay = Game.getDebugOverlay();
        if (overlay == null) {
            return;
        }
        overlay.toggleVisibility();
        logger.debug("Debug overlay {}", overlay.isVisible() ? "enabled" : "disabled");

        if (!overlay.isVisible()) {
            EntityManager entityManager = Game.getEntityManager();
            if (entityManager != null) {
                entityManager.clearAllCowPaths();
            }
        }
    }
}
