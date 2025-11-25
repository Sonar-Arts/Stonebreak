package com.openmason.ui.drop;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.concurrent.ConcurrentLinkedQueue;

/**
 * Thread-safe queue for pending file drops.
 *
 * This class solves a fundamental timing issue: GLFW drop callbacks fire during
 * glfwPollEvents(), which happens BEFORE ImGui.newFrame(). This means we cannot
 * check ImGui window state (focus, hover, visibility) when the drop occurs.
 *
 * Solution: Queue dropped files and process them during ImGui render when we
 * CAN check window state and route to the appropriate handler.
 *
 * Usage:
 * 1. GLFW drop callback calls PendingFileDrops.queue(filePaths)
 * 2. During ImGui render, visible windows check PendingFileDrops.hasPending()
 * 3. The appropriate window calls PendingFileDrops.poll() to get and process drops
 */
public class PendingFileDrops {

    private static final Logger logger = LoggerFactory.getLogger(PendingFileDrops.class);
    private static final ConcurrentLinkedQueue<String[]> pendingDrops = new ConcurrentLinkedQueue<>();

    /**
     * Queue file paths for later processing.
     * Called from GLFW drop callback.
     *
     * @param filePaths array of dropped file paths
     */
    public static void queue(String[] filePaths) {
        if (filePaths != null && filePaths.length > 0) {
            pendingDrops.offer(filePaths.clone());
            logger.debug("Queued {} file(s) for drop processing", filePaths.length);
        }
    }

    /**
     * Poll the next batch of dropped files.
     * Returns null if no pending drops.
     *
     * @return array of file paths, or null if queue is empty
     */
    public static String[] poll() {
        return pendingDrops.poll();
    }

    /**
     * Check if there are pending file drops to process.
     *
     * @return true if there are pending drops
     */
    public static boolean hasPending() {
        return !pendingDrops.isEmpty();
    }

    /**
     * Clear all pending drops.
     * Useful for cleanup or when changing contexts.
     */
    public static void clear() {
        pendingDrops.clear();
        logger.debug("Cleared pending file drops");
    }
}
