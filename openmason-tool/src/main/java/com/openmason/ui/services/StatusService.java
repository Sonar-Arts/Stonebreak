package com.openmason.ui.services;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Centralized status message management.
 * Follows Single Responsibility Principle - only manages status messages.
 * Follows DRY - eliminates repeated status update code throughout the application.
 */
public class StatusService {

    private static final Logger logger = LoggerFactory.getLogger(StatusService.class);

    private String statusMessage = "Ready";

    /**
     * Update status message.
     */
    public void updateStatus(String message) {
        this.statusMessage = message;
        logger.debug("Status updated: {}", message);
    }

    /**
     * Reset status to default.
     */
    public void reset() {
        statusMessage = "Ready";
    }
}
