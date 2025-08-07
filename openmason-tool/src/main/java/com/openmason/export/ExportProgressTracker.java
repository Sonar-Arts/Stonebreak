package com.openmason.export;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;

/**
 * Export Progress Tracker for Open Mason Phase 8.
 * 
 * Provides comprehensive progress tracking and error handling for all export operations:
 * - Real-time progress monitoring with detailed metrics
 * - Error collection and categorization
 * - Performance analytics and bottleneck detection
 * - Resource usage monitoring
 * - Comprehensive logging and reporting
 */
public class ExportProgressTracker {
    
    private static final Logger logger = LoggerFactory.getLogger(ExportProgressTracker.class);
    
    // Operation types
    public enum OperationType {
        SCREENSHOT("High-Resolution Screenshot"),
        BATCH_EXPORT("Batch Export"),
        DOCUMENTATION("Technical Documentation"),
        ATLAS_EXPORT("Texture Atlas Export"),
        CUSTOM("Custom Operation");
        
        private final String displayName;
        
        OperationType(String displayName) {
            this.displayName = displayName;
        }
        
        public String getDisplayName() { return displayName; }
    }
    
    // Error severity levels
    public enum ErrorSeverity {
        LOW("Low", "Minor issues that don't affect overall operation"),
        MEDIUM("Medium", "Moderate issues that may impact quality"),
        HIGH("High", "Significant issues that affect functionality"),
        CRITICAL("Critical", "Severe issues that prevent operation completion");
        
        private final String level;
        private final String description;
        
        ErrorSeverity(String level, String description) {
            this.level = level;
            this.description = description;
        }
        
        public String getLevel() { return level; }
        public String getDescription() { return description; }
    }
    
    // Progress tracking data
    public static class ProgressData {
        private final String operationId;
        private final OperationType operationType;
        private final LocalDateTime startTime;
        private final AtomicReference<String> currentStage = new AtomicReference<>("Initializing");
        private final AtomicInteger totalTasks = new AtomicInteger(0);
        private final AtomicInteger completedTasks = new AtomicInteger(0);
        private final AtomicInteger failedTasks = new AtomicInteger(0);
        private final AtomicLong bytesProcessed = new AtomicLong(0);
        private final AtomicLong bytesTotal = new AtomicLong(0);
        private final Map<String, Object> customMetrics = new ConcurrentHashMap<>();
        private final List<ErrorReport> errors = Collections.synchronizedList(new ArrayList<>());
        private final List<String> completedItems = Collections.synchronizedList(new ArrayList<>());
        private volatile boolean cancelled = false;
        private volatile LocalDateTime endTime = null;
        private volatile String result = null;
        
        public ProgressData(String operationId, OperationType operationType) {
            this.operationId = operationId;
            this.operationType = operationType;
            this.startTime = LocalDateTime.now();
        }
        
        // Getters
        public String getOperationId() { return operationId; }
        public OperationType getOperationType() { return operationType; }
        public LocalDateTime getStartTime() { return startTime; }
        public String getCurrentStage() { return currentStage.get(); }
        public int getTotalTasks() { return totalTasks.get(); }
        public int getCompletedTasks() { return completedTasks.get(); }
        public int getFailedTasks() { return failedTasks.get(); }
        public long getBytesProcessed() { return bytesProcessed.get(); }
        public long getBytesTotal() { return bytesTotal.get(); }
        public Map<String, Object> getCustomMetrics() { return new HashMap<>(customMetrics); }
        public List<ErrorReport> getErrors() { return new ArrayList<>(errors); }
        public List<String> getCompletedItems() { return new ArrayList<>(completedItems); }
        public boolean isCancelled() { return cancelled; }
        public LocalDateTime getEndTime() { return endTime; }
        public String getResult() { return result; }
        
        // Progress calculations
        public double getTaskProgress() {
            int total = totalTasks.get();
            return total > 0 ? (double) (completedTasks.get() + failedTasks.get()) / total : 0.0;
        }
        
        public double getByteProgress() {
            long total = bytesTotal.get();
            return total > 0 ? (double) bytesProcessed.get() / total : 0.0;
        }
        
        public long getElapsedTime() {
            LocalDateTime end = endTime != null ? endTime : LocalDateTime.now();
            return java.time.Duration.between(startTime, end).toMillis();
        }
        
        public boolean isComplete() {
            return endTime != null || cancelled;
        }
        
        public boolean hasErrors() {
            return !errors.isEmpty();
        }
        
        public long getErrorCount(ErrorSeverity severity) {
            return errors.stream().filter(e -> e.getSeverity() == severity).count();
        }
        
        // Setters (internal use)
        void setCurrentStage(String stage) { currentStage.set(stage); }
        void setTotalTasks(int total) { totalTasks.set(total); }
        void incrementCompletedTasks() { completedTasks.incrementAndGet(); }
        void incrementFailedTasks() { failedTasks.incrementAndGet(); }
        void setBytesProcessed(long bytes) { bytesProcessed.set(bytes); }
        void setBytesTotal(long bytes) { bytesTotal.set(bytes); }
        void addCustomMetric(String key, Object value) { customMetrics.put(key, value); }
        void addError(ErrorReport error) { errors.add(error); }
        void addCompletedItem(String item) { completedItems.add(item); }
        void setCancelled(boolean cancelled) { this.cancelled = cancelled; }
        void setEndTime(LocalDateTime endTime) { this.endTime = endTime; }
        void setResult(String result) { this.result = result; }
    }
    
    // Error report structure
    public static class ErrorReport {
        private final LocalDateTime timestamp;
        private final ErrorSeverity severity;
        private final String stage;
        private final String message;
        private final String details;
        private final Map<String, Object> context;
        
        public ErrorReport(ErrorSeverity severity, String stage, String message, String details, Map<String, Object> context) {
            this.timestamp = LocalDateTime.now();
            this.severity = severity;
            this.stage = stage;
            this.message = message;
            this.details = details;
            this.context = context != null ? new HashMap<>(context) : new HashMap<>();
        }
        
        public LocalDateTime getTimestamp() { return timestamp; }
        public ErrorSeverity getSeverity() { return severity; }
        public String getStage() { return stage; }
        public String getMessage() { return message; }
        public String getDetails() { return details; }
        public Map<String, Object> getContext() { return new HashMap<>(context); }
        
        @Override
        public String toString() {
            return String.format("[%s] %s at %s: %s - %s",
                severity.getLevel(),
                timestamp.format(DateTimeFormatter.ofPattern("HH:mm:ss")),
                stage,
                message,
                details);
        }
    }
    
    // Progress callback interface
    public interface ProgressCallback {
        void onProgressUpdate(ProgressData progressData);
        void onStageChanged(String operationId, String newStage);
        void onTaskCompleted(String operationId, String taskName);
        void onTaskFailed(String operationId, String taskName, Throwable error);
        void onError(String operationId, ErrorReport errorReport);
        void onComplete(String operationId, String result);
        void onCancelled(String operationId);
    }
    
    // Active operations tracking
    private final Map<String, ProgressData> activeOperations = new ConcurrentHashMap<>();
    private final List<ProgressCallback> globalCallbacks = Collections.synchronizedList(new ArrayList<>());
    private final Map<String, List<ProgressCallback>> operationCallbacks = new ConcurrentHashMap<>();
    private final AtomicInteger operationCounter = new AtomicInteger(0);
    
    // Performance monitoring
    private final Map<String, Long> stageTimings = new ConcurrentHashMap<>();
    private final Map<String, AtomicLong> resourceUsage = new ConcurrentHashMap<>();
    
    /**
     * Start tracking a new export operation.
     * 
     * @param operationType Type of operation being tracked
     * @param totalTasks Initial estimate of total tasks (can be updated)
     * @return Operation ID for tracking
     */
    public String startOperation(OperationType operationType, int totalTasks) {
        String operationId = operationType.name().toLowerCase() + "_" + 
                           System.currentTimeMillis() + "_" + 
                           operationCounter.incrementAndGet();
        
        ProgressData progressData = new ProgressData(operationId, operationType);
        progressData.setTotalTasks(totalTasks);
        
        activeOperations.put(operationId, progressData);
        
        logger.info("Started tracking operation: {} ({})", operationId, operationType.getDisplayName());
        
        // Notify callbacks
        notifyStageChanged(operationId, "Initializing");
        
        return operationId;
    }
    
    /**
     * Update the current stage of an operation.
     * 
     * @param operationId Operation ID
     * @param stage New stage name
     */
    public void updateStage(String operationId, String stage) {
        ProgressData progressData = activeOperations.get(operationId);
        if (progressData == null) {
            logger.warn("Attempted to update stage for unknown operation: {}", operationId);
            return;
        }
        
        // Record timing for previous stage
        String previousStage = progressData.getCurrentStage();
        if (previousStage != null && !previousStage.equals("Initializing")) {
            long stageTime = System.currentTimeMillis();
            stageTimings.put(operationId + "_" + previousStage, stageTime);
        }
        
        progressData.setCurrentStage(stage);
        
        logger.debug("Operation {} stage updated: {}", operationId, stage);
        
        // Notify callbacks
        notifyStageChanged(operationId, stage);
        notifyProgressUpdate(operationId);
    }
    
    /**
     * Update task progress for an operation.
     * 
     * @param operationId Operation ID
     * @param totalTasks Total number of tasks (updated estimate)
     */
    public void updateTotalTasks(String operationId, int totalTasks) {
        ProgressData progressData = activeOperations.get(operationId);
        if (progressData != null) {
            progressData.setTotalTasks(totalTasks);
            notifyProgressUpdate(operationId);
        }
    }
    
    /**
     * Mark a task as completed.
     * 
     * @param operationId Operation ID
     * @param taskName Name of completed task
     */
    public void taskCompleted(String operationId, String taskName) {
        ProgressData progressData = activeOperations.get(operationId);
        if (progressData == null) {
            logger.warn("Attempted to complete task for unknown operation: {}", operationId);
            return;
        }
        
        progressData.incrementCompletedTasks();
        progressData.addCompletedItem(taskName);
        
        logger.debug("Task completed for operation {}: {}", operationId, taskName);
        
        // Notify callbacks
        notifyTaskCompleted(operationId, taskName);
        notifyProgressUpdate(operationId);
    }
    
    /**
     * Mark a task as failed.
     * 
     * @param operationId Operation ID
     * @param taskName Name of failed task
     * @param error Error that caused the failure
     */
    public void taskFailed(String operationId, String taskName, Throwable error) {
        ProgressData progressData = activeOperations.get(operationId);
        if (progressData == null) {
            logger.warn("Attempted to fail task for unknown operation: {}", operationId);
            return;
        }
        
        progressData.incrementFailedTasks();
        
        // Create error report
        ErrorReport errorReport = new ErrorReport(
            ErrorSeverity.MEDIUM,
            progressData.getCurrentStage(),
            "Task failed: " + taskName,
            error.getMessage(),
            Map.of("taskName", taskName, "errorType", error.getClass().getSimpleName())
        );
        
        progressData.addError(errorReport);
        
        logger.warn("Task failed for operation {}: {} - {}", operationId, taskName, error.getMessage());
        
        // Notify callbacks
        notifyTaskFailed(operationId, taskName, error);
        notifyError(operationId, errorReport);
        notifyProgressUpdate(operationId);
    }
    
    /**
     * Report an error for an operation.
     * 
     * @param operationId Operation ID
     * @param severity Error severity
     * @param message Error message
     * @param details Additional error details
     * @param context Error context data
     */
    public void reportError(String operationId, ErrorSeverity severity, String message, String details, Map<String, Object> context) {
        ProgressData progressData = activeOperations.get(operationId);
        if (progressData == null) {
            logger.warn("Attempted to report error for unknown operation: {}", operationId);
            return;
        }
        
        ErrorReport errorReport = new ErrorReport(
            severity,
            progressData.getCurrentStage(),
            message,
            details,
            context
        );
        
        progressData.addError(errorReport);
        
        logger.error("Error reported for operation {}: [{}] {} - {}", 
            operationId, severity.getLevel(), message, details);
        
        // Notify callbacks
        notifyError(operationId, errorReport);
        notifyProgressUpdate(operationId);
    }
    
    /**
     * Update byte processing progress.
     * 
     * @param operationId Operation ID
     * @param bytesProcessed Bytes processed so far
     * @param bytesTotal Total bytes to process
     */
    public void updateByteProgress(String operationId, long bytesProcessed, long bytesTotal) {
        ProgressData progressData = activeOperations.get(operationId);
        if (progressData != null) {
            progressData.setBytesProcessed(bytesProcessed);
            progressData.setBytesTotal(bytesTotal);
            notifyProgressUpdate(operationId);
        }
    }
    
    /**
     * Add custom metric to operation tracking.
     * 
     * @param operationId Operation ID
     * @param metricName Metric name
     * @param value Metric value
     */
    public void addCustomMetric(String operationId, String metricName, Object value) {
        ProgressData progressData = activeOperations.get(operationId);
        if (progressData != null) {
            progressData.addCustomMetric(metricName, value);
            notifyProgressUpdate(operationId);
        }
    }
    
    /**
     * Complete an operation successfully.
     * 
     * @param operationId Operation ID
     * @param result Result description
     */
    public void completeOperation(String operationId, String result) {
        ProgressData progressData = activeOperations.get(operationId);
        if (progressData == null) {
            logger.warn("Attempted to complete unknown operation: {}", operationId);
            return;
        }
        
        progressData.setEndTime(LocalDateTime.now());
        progressData.setResult(result);
        
        logger.info("Operation completed: {} - {} (took {} ms)", 
            operationId, result, progressData.getElapsedTime());
        
        // Log performance metrics
        logPerformanceMetrics(progressData);
        
        // Notify callbacks
        notifyComplete(operationId, result);
        
        // Keep completed operation for a while for reporting
        scheduleCleanup(operationId, 300000); // 5 minutes
    }
    
    /**
     * Cancel an operation.
     * 
     * @param operationId Operation ID
     */
    public void cancelOperation(String operationId) {
        ProgressData progressData = activeOperations.get(operationId);
        if (progressData == null) {
            logger.warn("Attempted to cancel unknown operation: {}", operationId);
            return;
        }
        
        progressData.setCancelled(true);
        progressData.setEndTime(LocalDateTime.now());
        
        logger.info("Operation cancelled: {} (ran for {} ms)", operationId, progressData.getElapsedTime());
        
        // Notify callbacks
        notifyOperationCancelled(operationId);
        
        // Clean up immediately for cancelled operations
        scheduleCleanup(operationId, 60000); // 1 minute
    }
    
    /**
     * Get current progress data for an operation.
     * 
     * @param operationId Operation ID
     * @return Progress data, or null if operation not found
     */
    public ProgressData getProgressData(String operationId) {
        return activeOperations.get(operationId);
    }
    
    /**
     * Get all active operations.
     * 
     * @return Map of operation ID to progress data
     */
    public Map<String, ProgressData> getAllActiveOperations() {
        return new HashMap<>(activeOperations);
    }
    
    /**
     * Add a global progress callback (receives updates for all operations).
     * 
     * @param callback Progress callback
     */
    public void addGlobalCallback(ProgressCallback callback) {
        globalCallbacks.add(callback);
    }
    
    /**
     * Remove a global progress callback.
     * 
     * @param callback Progress callback to remove
     */
    public void removeGlobalCallback(ProgressCallback callback) {
        globalCallbacks.remove(callback);
    }
    
    /**
     * Add a callback for a specific operation.
     * 
     * @param operationId Operation ID
     * @param callback Progress callback
     */
    public void addOperationCallback(String operationId, ProgressCallback callback) {
        operationCallbacks.computeIfAbsent(operationId, k -> Collections.synchronizedList(new ArrayList<>()))
                          .add(callback);
    }
    
    /**
     * Remove a callback for a specific operation.
     * 
     * @param operationId Operation ID
     * @param callback Progress callback to remove
     */
    public void removeOperationCallback(String operationId, ProgressCallback callback) {
        List<ProgressCallback> callbacks = operationCallbacks.get(operationId);
        if (callbacks != null) {
            callbacks.remove(callback);
            if (callbacks.isEmpty()) {
                operationCallbacks.remove(operationId);
            }
        }
    }
    
    /**
     * Generate a comprehensive report for an operation.
     * 
     * @param operationId Operation ID
     * @return Formatted report string
     */
    public String generateOperationReport(String operationId) {
        ProgressData progressData = activeOperations.get(operationId);
        if (progressData == null) {
            return "Operation not found: " + operationId;
        }
        
        StringBuilder report = new StringBuilder();
        report.append("EXPORT OPERATION REPORT\n");
        report.append("=======================\n\n");
        
        report.append("Operation ID: ").append(progressData.getOperationId()).append("\n");
        report.append("Type: ").append(progressData.getOperationType().getDisplayName()).append("\n");
        report.append("Start Time: ").append(progressData.getStartTime().format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss"))).append("\n");
        
        if (progressData.getEndTime() != null) {
            report.append("End Time: ").append(progressData.getEndTime().format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss"))).append("\n");
        }
        
        report.append("Duration: ").append(formatDuration(progressData.getElapsedTime())).append("\n");
        report.append("Status: ").append(progressData.isCancelled() ? "CANCELLED" : 
                                        progressData.isComplete() ? "COMPLETED" : "IN PROGRESS").append("\n");
        report.append("Current Stage: ").append(progressData.getCurrentStage()).append("\n\n");
        
        // Task progress
        report.append("TASK PROGRESS:\n");
        report.append("- Total Tasks: ").append(progressData.getTotalTasks()).append("\n");
        report.append("- Completed: ").append(progressData.getCompletedTasks()).append("\n");
        report.append("- Failed: ").append(progressData.getFailedTasks()).append("\n");
        report.append("- Progress: ").append(String.format("%.1f%%", progressData.getTaskProgress() * 100)).append("\n\n");
        
        // Byte progress
        if (progressData.getBytesTotal() > 0) {
            report.append("DATA PROGRESS:\n");
            report.append("- Bytes Processed: ").append(formatFileSize(progressData.getBytesProcessed())).append("\n");
            report.append("- Total Bytes: ").append(formatFileSize(progressData.getBytesTotal())).append("\n");
            report.append("- Progress: ").append(String.format("%.1f%%", progressData.getByteProgress() * 100)).append("\n\n");
        }
        
        // Custom metrics
        if (!progressData.getCustomMetrics().isEmpty()) {
            report.append("CUSTOM METRICS:\n");
            for (Map.Entry<String, Object> entry : progressData.getCustomMetrics().entrySet()) {
                report.append("- ").append(entry.getKey()).append(": ").append(entry.getValue()).append("\n");
            }
            report.append("\n");
        }
        
        // Errors
        if (progressData.hasErrors()) {
            report.append("ERRORS:\n");
            Map<ErrorSeverity, Long> errorCounts = new HashMap<>();
            for (ErrorSeverity severity : ErrorSeverity.values()) {
                long count = progressData.getErrorCount(severity);
                if (count > 0) {
                    errorCounts.put(severity, count);
                    report.append("- ").append(severity.getLevel()).append(": ").append(count).append("\n");
                }
            }
            report.append("\n");
            
            // Recent errors
            List<ErrorReport> recentErrors = progressData.getErrors();
            if (recentErrors.size() > 10) {
                recentErrors = recentErrors.subList(recentErrors.size() - 10, recentErrors.size());
            }
            
            report.append("RECENT ERRORS:\n");
            for (ErrorReport error : recentErrors) {
                report.append("- ").append(error.toString()).append("\n");
            }
            report.append("\n");
        }
        
        // Completed items
        if (!progressData.getCompletedItems().isEmpty()) {
            report.append("COMPLETED ITEMS:\n");
            List<String> items = progressData.getCompletedItems();
            for (int i = Math.max(0, items.size() - 10); i < items.size(); i++) {
                report.append("- ").append(items.get(i)).append("\n");
            }
            if (items.size() > 10) {
                report.append("... and ").append(items.size() - 10).append(" more\n");
            }
        }
        
        return report.toString();
    }
    
    // Private helper methods
    
    private void notifyProgressUpdate(String operationId) {
        ProgressData progressData = activeOperations.get(operationId);
        if (progressData == null) return;
        
        // Notify global callbacks
        globalCallbacks.forEach(callback -> {
            try {
                callback.onProgressUpdate(progressData);
            } catch (Exception e) {
                logger.error("Error in progress callback", e);
            }
        });
        
        // Notify operation-specific callbacks
        List<ProgressCallback> callbacks = operationCallbacks.get(operationId);
        if (callbacks != null) {
            callbacks.forEach(callback -> {
                try {
                    callback.onProgressUpdate(progressData);
                } catch (Exception e) {
                    logger.error("Error in operation callback", e);
                }
            });
        }
    }
    
    private void notifyStageChanged(String operationId, String newStage) {
        notifyAllCallbacks(operationId, callback -> callback.onStageChanged(operationId, newStage));
    }
    
    private void notifyTaskCompleted(String operationId, String taskName) {
        notifyAllCallbacks(operationId, callback -> callback.onTaskCompleted(operationId, taskName));
    }
    
    private void notifyTaskFailed(String operationId, String taskName, Throwable error) {
        notifyAllCallbacks(operationId, callback -> callback.onTaskFailed(operationId, taskName, error));
    }
    
    private void notifyError(String operationId, ErrorReport errorReport) {
        notifyAllCallbacks(operationId, callback -> callback.onError(operationId, errorReport));
    }
    
    private void notifyComplete(String operationId, String result) {
        notifyAllCallbacks(operationId, callback -> callback.onComplete(operationId, result));
    }
    
    private void notifyOperationCancelled(String operationId) {
        notifyAllCallbacks(operationId, callback -> callback.onCancelled(operationId));
    }
    
    private void notifyAllCallbacks(String operationId, Consumer<ProgressCallback> notifier) {
        // Notify global callbacks
        globalCallbacks.forEach(callback -> {
            try {
                notifier.accept(callback);
            } catch (Exception e) {
                logger.error("Error in progress callback", e);
            }
        });
        
        // Notify operation-specific callbacks
        List<ProgressCallback> callbacks = operationCallbacks.get(operationId);
        if (callbacks != null) {
            callbacks.forEach(callback -> {
                try {
                    notifier.accept(callback);
                } catch (Exception e) {
                    logger.error("Error in operation callback", e);
                }
            });
        }
    }
    
    private void logPerformanceMetrics(ProgressData progressData) {
        long totalTime = progressData.getElapsedTime();
        double tasksPerSecond = totalTime > 0 ? 
            (progressData.getCompletedTasks() * 1000.0) / totalTime : 0.0;
        
        logger.info("Performance metrics for {}: {} tasks in {} ms ({:.2f} tasks/sec)",
            progressData.getOperationId(),
            progressData.getCompletedTasks(),
            totalTime,
            tasksPerSecond);
        
        if (progressData.getBytesTotal() > 0) {
            double mbPerSecond = totalTime > 0 ? 
                (progressData.getBytesProcessed() / (1024.0 * 1024.0)) / (totalTime / 1000.0) : 0.0;
            
            logger.info("Data throughput for {}: {:.2f} MB/sec",
                progressData.getOperationId(), mbPerSecond);
        }
    }
    
    private void scheduleCleanup(String operationId, long delayMs) {
        Timer timer = new Timer(true);
        timer.schedule(new TimerTask() {
            @Override
            public void run() {
                activeOperations.remove(operationId);
                operationCallbacks.remove(operationId);
                logger.debug("Cleaned up operation: {}", operationId);
            }
        }, delayMs);
    }
    
    private String formatDuration(long milliseconds) {
        long seconds = milliseconds / 1000;
        if (seconds < 60) return seconds + " seconds";
        long minutes = seconds / 60;
        seconds = seconds % 60;
        if (minutes < 60) return minutes + ":" + String.format("%02d", seconds);
        long hours = minutes / 60;
        minutes = minutes % 60;
        return hours + ":" + String.format("%02d", minutes) + ":" + String.format("%02d", seconds);
    }
    
    private String formatFileSize(long bytes) {
        if (bytes < 1024) return bytes + " B";
        if (bytes < 1024 * 1024) return String.format("%.1f KB", bytes / 1024.0);
        if (bytes < 1024 * 1024 * 1024) return String.format("%.1f MB", bytes / (1024.0 * 1024.0));
        return String.format("%.1f GB", bytes / (1024.0 * 1024.0 * 1024.0));
    }
    
    /**
     * Shutdown the progress tracker.
     */
    public void shutdown() {
        logger.info("Shutting down Export Progress Tracker");
        
        // Cancel all active operations
        for (String operationId : new ArrayList<>(activeOperations.keySet())) {
            cancelOperation(operationId);
        }
        
        // Clear callbacks
        globalCallbacks.clear();
        operationCallbacks.clear();
        
        logger.info("Export Progress Tracker shutdown complete");
    }
}