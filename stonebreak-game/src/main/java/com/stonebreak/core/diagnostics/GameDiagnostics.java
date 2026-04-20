package com.stonebreak.core.diagnostics;

import com.stonebreak.core.Game;
import com.stonebreak.mobs.entities.EntityManager;
import com.stonebreak.ui.DebugOverlay;
import com.stonebreak.util.MemoryLeakDetector;
import com.stonebreak.util.MemoryProfiler;

/**
 * Memory / performance / debug-overlay diagnostic helpers previously hosted
 * on {@link Game}. Static methods kept for API parity — callers go through
 * {@code Game.xxx} which now delegates here.
 */
public final class GameDiagnostics {

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

        MemoryProfiler profiler = MemoryProfiler.getInstance();
        if (currentTime - lastMemoryCheckTime > 30000) {
            profiler.checkMemoryPressure();
            lastMemoryCheckTime = currentTime;
        }

        Runtime runtime = Runtime.getRuntime();
        long maxMemory = runtime.maxMemory() / (1024 * 1024);
        long totalMemory = runtime.totalMemory() / (1024 * 1024);
        long freeMemory = runtime.freeMemory() / (1024 * 1024);
        long usedMemory = totalMemory - freeMemory;
        double memoryUsagePercent = (double) usedMemory / maxMemory * 100;

        int chunkCount = 0;
        int meshPendingCount = 0;
        int glUploadPendingCount = 0;
        if (Game.getWorld() != null) {
            chunkCount = Game.getWorld().getLoadedChunkCount();
            meshPendingCount = Game.getWorld().getPendingMeshBuildCount();
            glUploadPendingCount = Game.getWorld().getPendingGLUploadCount();
        }

        String playerPos = "Unknown";
        if (Game.getPlayer() != null) {
            org.joml.Vector3f pos = Game.getPlayer().getPosition();
            playerPos = String.format("%.1f, %.1f, %.1f", pos.x, pos.y, pos.z);
        }

        System.out.println("========== MEMORY & PERFORMANCE DEBUG ==========");
        System.out.printf("Memory Usage: %d/%d MB (%.1f%% of max %d MB)%n",
                         usedMemory, totalMemory, memoryUsagePercent, maxMemory);
        System.out.printf("Free Memory: %d MB%n", freeMemory);
        System.out.printf("Chunks: %d loaded, %d pending mesh, %d pending GL%n",
                         chunkCount, meshPendingCount, glUploadPendingCount);
        System.out.printf("Player Position: %s%n", playerPos);
        System.out.printf("FPS: %d%n", Math.round(1.0f / Game.getDeltaTime()));
        System.out.printf("Delta Time: %.3f ms%n", Game.getDeltaTime() * 1000);

        if (memoryUsagePercent > 90) {
            System.out.println("⚠️  HIGH: Memory usage above 90% - ZGC will manage automatically");
            profiler.takeSnapshot("high_memory_usage_" + currentTime);
        }
        if (memoryUsagePercent > 98) {
            System.out.println("🚨 CRITICAL: Memory usage above 98% - emergency cleanup triggered!");
            profiler.takeSnapshot("critical_memory_" + currentTime);
        }

        System.out.println("===============================================");
    }

    /** Logs a one-line memory summary tagged with the supplied context. */
    public static void logDetailedMemoryInfo(String context) {
        Runtime runtime = Runtime.getRuntime();
        long maxMemory = runtime.maxMemory() / (1024 * 1024);
        long totalMemory = runtime.totalMemory() / (1024 * 1024);
        long freeMemory = runtime.freeMemory() / (1024 * 1024);
        long usedMemory = totalMemory - freeMemory;

        System.out.printf("[MEMORY] %s - Used: %dMB, Total: %dMB, Max: %dMB, Free: %dMB%n",
                         context, usedMemory, totalMemory, maxMemory, freeMemory);
    }

    /**
     * Reports memory usage before/after a short wait.
     * Under ZGC we no longer invoke System.gc() directly.
     */
    public static void forceGCAndReport(String context) {
        Runtime runtime = Runtime.getRuntime();
        long beforeGC = (runtime.totalMemory() - runtime.freeMemory()) / (1024 * 1024);

        System.out.printf("[GC] %s - Memory before GC: %dMB%n", context, beforeGC);
        System.out.println("[MEMORY] Relying on ZGC for optimal memory management");

        try {
            Thread.sleep(100);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }

        long afterGC = (runtime.totalMemory() - runtime.freeMemory()) / (1024 * 1024);
        long freed = beforeGC - afterGC;

        System.out.printf("[GC] %s - Memory after GC: %dMB (freed %+dMB)%n", context, afterGC, freed);
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
            System.err.println("Memory leak detector not initialized!");
        }
    }

    /** Toggles the F3 debug overlay and clears cow-path debug data when hiding. */
    public static void toggleDebugOverlay() {
        DebugOverlay overlay = Game.getDebugOverlay();
        if (overlay == null) {
            return;
        }
        overlay.toggleVisibility();
        System.out.println("Debug overlay " + (overlay.isVisible() ? "enabled" : "disabled"));

        if (!overlay.isVisible()) {
            EntityManager entityManager = Game.getEntityManager();
            if (entityManager != null) {
                entityManager.clearAllCowPaths();
            }
        }
    }
}
