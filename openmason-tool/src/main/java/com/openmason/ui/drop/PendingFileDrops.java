package com.openmason.ui.drop;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.concurrent.ConcurrentLinkedQueue;

/**
 * Thread-safe queue for pending file drops.
 */
public class PendingFileDrops {

    private static final Logger logger = LoggerFactory.getLogger(PendingFileDrops.class);
    private static final ConcurrentLinkedQueue<String[]> pendingDrops = new ConcurrentLinkedQueue<>();

    /**
     * Queue file paths for later processing.
     */
    public static void queue(String[] filePaths) {
        if (filePaths != null && filePaths.length > 0) {
            pendingDrops.offer(filePaths.clone());
            logger.debug("Queued {} file(s) for drop processing", filePaths.length);
        }
    }

    /**
     * Poll the next batch of dropped files.
     */
    public static String[] poll() {
        return pendingDrops.poll();
    }

    /**
     * Check if there are pending file drops to process.
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
