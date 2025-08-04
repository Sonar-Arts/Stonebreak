package com.openmason.texture;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Comprehensive Texture Performance Monitor - Phase 5 Optimization
 * 
 * Provides real-time performance monitoring and validation for the texture loading system:
 * - Continuous performance metrics collection
 * - Automatic performance regression detection
 * - Memory usage tracking and leak detection
 * - Load time analysis and optimization recommendations
 * - System health checks and validation
 * - Performance trend analysis and reporting
 * 
 * Key Metrics:
 * - Texture loading times and throughput
 * - Cache hit rates and efficiency
 * - Memory usage patterns and optimization
 * - System resource utilization
 * - Error rates and failure analysis
 */
public class TexturePerformanceMonitor implements AutoCloseable {
    
    private static final Logger logger = LoggerFactory.getLogger(TexturePerformanceMonitor.class);
    
    // Monitoring intervals
    private static final long MONITORING_INTERVAL_MS = 5000L; // 5 seconds
    private static final long HEALTH_CHECK_INTERVAL_MS = 30000L; // 30 seconds
    private static final long TREND_ANALYSIS_INTERVAL_MS = 60000L; // 1 minute
    
    // Performance thresholds
    private static final long LOAD_TIME_WARNING_MS = 200L;
    private static final long LOAD_TIME_CRITICAL_MS = 500L;
    private static final double CACHE_HIT_RATE_WARNING = 70.0;
    private static final double CACHE_HIT_RATE_CRITICAL = 50.0;
    private static final long MEMORY_WARNING_MB = 100L;
    private static final long MEMORY_CRITICAL_MB = 200L;
    
    // Monitoring components
    private final UnifiedTextureResourceManager resourceManager;
    private final OptimizedViewportTextureManager viewportManager;
    private final ScheduledExecutorService monitoringExecutor;
    
    // Performance metrics tracking
    private final Map<String, PerformanceMetric> metrics = new ConcurrentHashMap<>();
    private final List<PerformanceSnapshot> performanceHistory = new ArrayList<>();
    private final Queue<SystemAlert> activeAlerts = new ConcurrentLinkedQueue<>();
    
    // Monitoring state
    private final AtomicBoolean monitoringActive = new AtomicBoolean(false);
    private final AtomicLong monitoringCycles = new AtomicLong(0);
    private volatile long lastHealthCheck = 0L;
    
    /**
     * Performance metric tracking
     */
    private static class PerformanceMetric {
        private final String name;
        private final AtomicLong count = new AtomicLong(0);
        private final AtomicLong totalValue = new AtomicLong(0);
        private final AtomicLong minValue = new AtomicLong(Long.MAX_VALUE);
        private final AtomicLong maxValue = new AtomicLong(Long.MIN_VALUE);
        private final Queue<Long> recentValues = new ConcurrentLinkedQueue<>();
        
        public PerformanceMetric(String name) {
            this.name = name;
        }
        
        public void recordValue(long value) {
            count.incrementAndGet();
            totalValue.addAndGet(value);
            minValue.updateAndGet(current -> Math.min(current, value));
            maxValue.updateAndGet(current -> Math.max(current, value));
            
            // Keep only recent values for trend analysis
            recentValues.offer(value);
            while (recentValues.size() > 100) {
                recentValues.poll();
            }
        }
        
        public double getAverage() {
            long c = count.get();
            return c > 0 ? (double) totalValue.get() / c : 0.0;
        }
        
        public long getMin() { return minValue.get() == Long.MAX_VALUE ? 0 : minValue.get(); }
        public long getMax() { return maxValue.get() == Long.MIN_VALUE ? 0 : maxValue.get(); }
        public long getCount() { return count.get(); }
        
        public double getTrend() {
            List<Long> values = new ArrayList<>(recentValues);
            if (values.size() < 10) return 0.0;
            
            // Simple linear trend calculation
            int n = values.size();
            double sumX = n * (n - 1) / 2.0;
            double sumY = values.stream().mapToLong(Long::longValue).sum();
            double sumXY = 0;
            double sumX2 = 0;
            
            for (int i = 0; i < n; i++) {
                sumXY += i * values.get(i);
                sumX2 += i * i;
            }
            
            double slope = (n * sumXY - sumX * sumY) / (n * sumX2 - sumX * sumX);
            return slope;
        }
        
        public MetricSummary getSummary() {
            return new MetricSummary(name, getCount(), getAverage(), getMin(), getMax(), getTrend());
        }
    }
    
    /**
     * Performance snapshot for historical tracking
     */
    public static class PerformanceSnapshot {
        private final long timestamp;
        private final Map<String, MetricSummary> metrics;
        private final SystemHealthStatus healthStatus;
        private final long memoryUsage;
        private final double cpuUsage;
        
        public PerformanceSnapshot(Map<String, MetricSummary> metrics, SystemHealthStatus healthStatus,
                                 long memoryUsage, double cpuUsage) {
            this.timestamp = System.currentTimeMillis();
            this.metrics = new HashMap<>(metrics);
            this.healthStatus = healthStatus;
            this.memoryUsage = memoryUsage;
            this.cpuUsage = cpuUsage;
        }
        
        // Getters
        public long getTimestamp() { return timestamp; }
        public Map<String, MetricSummary> getMetrics() { return metrics; }
        public SystemHealthStatus getHealthStatus() { return healthStatus; }
        public long getMemoryUsage() { return memoryUsage; }
        public double getCpuUsage() { return cpuUsage; }
    }
    
    /**
     * Metric summary for reporting
     */
    public static class MetricSummary {
        private final String name;
        private final long count;
        private final double average;
        private final long min;
        private final long max;
        private final double trend;
        
        public MetricSummary(String name, long count, double average, long min, long max, double trend) {
            this.name = name;
            this.count = count;
            this.average = average;
            this.min = min;
            this.max = max;
            this.trend = trend;
        }
        
        // Getters
        public String getName() { return name; }
        public long getCount() { return count; }
        public double getAverage() { return average; }
        public long getMin() { return min; }
        public long getMax() { return max; }
        public double getTrend() { return trend; }
    }
    
    /**
     * System health status
     */
    public enum SystemHealthStatus {
        EXCELLENT,  // All metrics within optimal ranges
        GOOD,       // Minor performance variations
        WARNING,    // Performance degradation detected
        CRITICAL,   // Significant performance issues
        FAILED      // System malfunction detected
    }
    
    /**
     * System alert for performance issues
     */
    public static class SystemAlert {
        private final long timestamp;
        private final AlertSeverity severity;
        private final String category;
        private final String message;
        private final Map<String, Object> context;
        
        public SystemAlert(AlertSeverity severity, String category, String message, Map<String, Object> context) {
            this.timestamp = System.currentTimeMillis();
            this.severity = severity;
            this.category = category;
            this.message = message;
            this.context = context != null ? new HashMap<>(context) : Map.of();
        }
        
        public enum AlertSeverity {
            INFO, WARNING, CRITICAL
        }
        
        // Getters
        public long getTimestamp() { return timestamp; }
        public AlertSeverity getSeverity() { return severity; }
        public String getCategory() { return category; }
        public String getMessage() { return message; }
        public Map<String, Object> getContext() { return context; }
        
        @Override
        public String toString() {
            return String.format("[%s] %s: %s", severity, category, message);
        }
    }
    
    public TexturePerformanceMonitor(UnifiedTextureResourceManager resourceManager,
                                   OptimizedViewportTextureManager viewportManager) {
        this.resourceManager = resourceManager;
        this.viewportManager = viewportManager;
        
        // Initialize monitoring executor
        this.monitoringExecutor = Executors.newScheduledThreadPool(3, r -> {
            Thread t = new Thread(r, "TexturePerformanceMonitor-" + System.currentTimeMillis());
            t.setDaemon(true);
            t.setPriority(Thread.NORM_PRIORITY - 1);
            return t;
        });
        
        // Initialize metrics
        initializeMetrics();
        
        logger.info("Texture Performance Monitor initialized");
    }
    
    /**
     * Start performance monitoring
     */
    public void startMonitoring() {
        if (monitoringActive.compareAndSet(false, true)) {
            logger.info("Starting texture performance monitoring");
            
            // Schedule regular monitoring tasks
            monitoringExecutor.scheduleAtFixedRate(
                this::collectPerformanceMetrics,
                0,
                MONITORING_INTERVAL_MS,
                TimeUnit.MILLISECONDS
            );
            
            monitoringExecutor.scheduleAtFixedRate(
                this::performHealthCheck,
                HEALTH_CHECK_INTERVAL_MS,
                HEALTH_CHECK_INTERVAL_MS,
                TimeUnit.MILLISECONDS
            );
            
            monitoringExecutor.scheduleAtFixedRate(
                this::performTrendAnalysis,
                TREND_ANALYSIS_INTERVAL_MS,
                TREND_ANALYSIS_INTERVAL_MS,
                TimeUnit.MILLISECONDS
            );
            
            logger.info("Texture performance monitoring started successfully");
        }
    }
    
    /**
     * Stop performance monitoring
     */
    public void stopMonitoring() {
        if (monitoringActive.compareAndSet(true, false)) {
            logger.info("Stopping texture performance monitoring");
            
            // The scheduled tasks will check monitoringActive and stop naturally
            
            logger.info("Texture performance monitoring stopped");
        }
    }
    
    /**
     * Record a texture loading time
     */
    public void recordTextureLoadTime(String variantName, long loadTimeMs) {
        PerformanceMetric metric = metrics.get("textureLoadTime");
        if (metric != null) {
            metric.recordValue(loadTimeMs);
        }
        
        // Check for performance issues
        if (loadTimeMs > LOAD_TIME_CRITICAL_MS) {
            createAlert(SystemAlert.AlertSeverity.CRITICAL, "Performance", 
                "Critical texture load time: " + loadTimeMs + "ms for " + variantName,
                Map.of("variantName", variantName, "loadTime", loadTimeMs));
        } else if (loadTimeMs > LOAD_TIME_WARNING_MS) {
            createAlert(SystemAlert.AlertSeverity.WARNING, "Performance",
                "Slow texture load time: " + loadTimeMs + "ms for " + variantName,
                Map.of("variantName", variantName, "loadTime", loadTimeMs));
        }
    }
    
    /**
     * Record cache hit/miss
     */
    public void recordCacheAccess(boolean hit) {
        PerformanceMetric hitMetric = metrics.get("cacheHits");
        PerformanceMetric missMetric = metrics.get("cacheMisses");
        
        if (hit && hitMetric != null) {
            hitMetric.recordValue(1);
        } else if (!hit && missMetric != null) {
            missMetric.recordValue(1);
        }
    }
    
    /**
     * Record memory usage
     */
    public void recordMemoryUsage(long memoryUsageBytes) {
        PerformanceMetric metric = metrics.get("memoryUsage");
        if (metric != null) {
            metric.recordValue(memoryUsageBytes);
        }
        
        long memoryUsageMB = memoryUsageBytes / 1024 / 1024;
        if (memoryUsageMB > MEMORY_CRITICAL_MB) {
            createAlert(SystemAlert.AlertSeverity.CRITICAL, "Memory",
                "Critical memory usage: " + memoryUsageMB + "MB",
                Map.of("memoryUsageMB", memoryUsageMB));
        } else if (memoryUsageMB > MEMORY_WARNING_MB) {
            createAlert(SystemAlert.AlertSeverity.WARNING, "Memory",
                "High memory usage: " + memoryUsageMB + "MB",
                Map.of("memoryUsageMB", memoryUsageMB));
        }
    }
    
    /**
     * Get current performance report
     */
    public PerformanceReport getCurrentPerformanceReport() {
        Map<String, MetricSummary> currentMetrics = new HashMap<>();
        for (Map.Entry<String, PerformanceMetric> entry : metrics.entrySet()) {
            currentMetrics.put(entry.getKey(), entry.getValue().getSummary());
        }
        
        SystemHealthStatus healthStatus = assessSystemHealth();
        List<SystemAlert> recentAlerts = new ArrayList<>();
        
        // Get recent alerts (last 10)
        Iterator<SystemAlert> alertIter = activeAlerts.iterator();
        int alertCount = 0;
        while (alertIter.hasNext() && alertCount < 10) {
            recentAlerts.add(alertIter.next());
            alertCount++;
        }
        
        return new PerformanceReport(
            currentMetrics,
            healthStatus,
            recentAlerts,
            monitoringCycles.get(),
            getSystemResourceUsage()
        );
    }
    
    /**
     * Performance report class
     */
    public static class PerformanceReport {
        private final Map<String, MetricSummary> metrics;
        private final SystemHealthStatus healthStatus;
        private final List<SystemAlert> recentAlerts;
        private final long monitoringCycles;
        private final SystemResourceUsage resourceUsage;
        
        public PerformanceReport(Map<String, MetricSummary> metrics, SystemHealthStatus healthStatus,
                               List<SystemAlert> recentAlerts, long monitoringCycles,
                               SystemResourceUsage resourceUsage) {
            this.metrics = metrics;
            this.healthStatus = healthStatus;
            this.recentAlerts = recentAlerts;
            this.monitoringCycles = monitoringCycles;
            this.resourceUsage = resourceUsage;
        }
        
        // Getters
        public Map<String, MetricSummary> getMetrics() { return metrics; }
        public SystemHealthStatus getHealthStatus() { return healthStatus; }
        public List<SystemAlert> getRecentAlerts() { return recentAlerts; }
        public long getMonitoringCycles() { return monitoringCycles; }
        public SystemResourceUsage getResourceUsage() { return resourceUsage; }
    }
    
    /**
     * System resource usage information
     */
    public static class SystemResourceUsage {
        private final long totalMemory;
        private final long usedMemory;
        private final long freeMemory;
        private final double cpuUsage;
        private final int activeThreads;
        
        public SystemResourceUsage(long totalMemory, long usedMemory, long freeMemory,
                                 double cpuUsage, int activeThreads) {
            this.totalMemory = totalMemory;
            this.usedMemory = usedMemory;
            this.freeMemory = freeMemory;
            this.cpuUsage = cpuUsage;
            this.activeThreads = activeThreads;
        }
        
        // Getters
        public long getTotalMemory() { return totalMemory; }
        public long getUsedMemory() { return usedMemory; }
        public long getFreeMemory() { return freeMemory; }
        public double getCpuUsage() { return cpuUsage; }
        public int getActiveThreads() { return activeThreads; }
        
        public double getMemoryUtilization() {
            return totalMemory > 0 ? (double) usedMemory / totalMemory * 100 : 0;
        }
    }
    
    // Private methods
    
    private void initializeMetrics() {
        metrics.put("textureLoadTime", new PerformanceMetric("Texture Load Time"));
        metrics.put("cacheHits", new PerformanceMetric("Cache Hits"));
        metrics.put("cacheMisses", new PerformanceMetric("Cache Misses"));
        metrics.put("memoryUsage", new PerformanceMetric("Memory Usage"));
        metrics.put("textureSwitchTime", new PerformanceMetric("Texture Switch Time"));
    }
    
    private void collectPerformanceMetrics() {
        if (!monitoringActive.get()) return;
        
        try {
            monitoringCycles.incrementAndGet();
            
            // Collect metrics from resource manager
            if (resourceManager != null) {
                UnifiedTextureResourceManager.PerformanceStatistics stats = 
                    resourceManager.getPerformanceStatistics();
                
                recordMemoryUsage(stats.getMemoryUsage());
                
                // Record cache statistics
                long totalRequests = stats.getTotalRequests();
                long cacheHits = stats.getCacheHits();
                long cacheMisses = stats.getCacheMisses();
                
                if (totalRequests > 0) {
                    double hitRate = (double) cacheHits / totalRequests * 100;
                    checkCachePerformance(hitRate);
                }
            }
            
            // Collect metrics from viewport manager
            if (viewportManager != null) {
                OptimizedViewportTextureManager.ViewportPerformanceStatistics stats = 
                    viewportManager.getPerformanceStatistics();
                
                PerformanceMetric switchTimeMetric = metrics.get("textureSwitchTime");
                if (switchTimeMetric != null) {
                    switchTimeMetric.recordValue((long) stats.getAverageSwitchTime());
                }
            }
            
        } catch (Exception e) {
            logger.warn("Error collecting performance metrics", e);
        }
    }
    
    private void performHealthCheck() {
        if (!monitoringActive.get()) return;
        
        try {
            lastHealthCheck = System.currentTimeMillis();
            
            SystemHealthStatus currentHealth = assessSystemHealth();
            
            if (currentHealth == SystemHealthStatus.CRITICAL || currentHealth == SystemHealthStatus.FAILED) {
                createAlert(SystemAlert.AlertSeverity.CRITICAL, "System Health",
                    "System health status: " + currentHealth,
                    Map.of("healthStatus", currentHealth));
            }
            
            // Create performance snapshot
            Map<String, MetricSummary> currentMetrics = new HashMap<>();
            for (Map.Entry<String, PerformanceMetric> entry : metrics.entrySet()) {
                currentMetrics.put(entry.getKey(), entry.getValue().getSummary());
            }
            
            SystemResourceUsage resourceUsage = getSystemResourceUsage();
            PerformanceSnapshot snapshot = new PerformanceSnapshot(
                currentMetrics, currentHealth, resourceUsage.getUsedMemory(), resourceUsage.getCpuUsage());
            
            // Keep only recent snapshots
            synchronized (performanceHistory) {
                performanceHistory.add(snapshot);
                while (performanceHistory.size() > 100) {
                    performanceHistory.remove(0);
                }
            }
            
        } catch (Exception e) {
            logger.warn("Error during health check", e);
        }
    }
    
    private void performTrendAnalysis() {
        if (!monitoringActive.get()) return;
        
        try {
            // Analyze trends in key metrics
            for (Map.Entry<String, PerformanceMetric> entry : metrics.entrySet()) {
                String metricName = entry.getKey();
                PerformanceMetric metric = entry.getValue();
                double trend = metric.getTrend();
                
                // Alert on negative trends
                if (metricName.equals("textureLoadTime") && trend > 5.0) {
                    createAlert(SystemAlert.AlertSeverity.WARNING, "Trend Analysis",
                        "Increasing trend in texture load times",
                        Map.of("metric", metricName, "trend", trend));
                }
            }
            
        } catch (Exception e) {
            logger.warn("Error during trend analysis", e);
        }
    }
    
    private SystemHealthStatus assessSystemHealth() {
        try {
            int criticalIssues = 0;
            int warningIssues = 0;
            
            // Check load times
            PerformanceMetric loadTimeMetric = metrics.get("textureLoadTime");
            if (loadTimeMetric != null && loadTimeMetric.getCount() > 0) {
                double avgLoadTime = loadTimeMetric.getAverage();
                if (avgLoadTime > LOAD_TIME_CRITICAL_MS) {
                    criticalIssues++;
                } else if (avgLoadTime > LOAD_TIME_WARNING_MS) {
                    warningIssues++;
                }
            }
            
            // Check cache hit rate
            double hitRate = calculateCacheHitRate();
            if (hitRate < CACHE_HIT_RATE_CRITICAL) {
                criticalIssues++;
            } else if (hitRate < CACHE_HIT_RATE_WARNING) {
                warningIssues++;
            }
            
            // Check memory usage
            PerformanceMetric memoryMetric = metrics.get("memoryUsage");
            if (memoryMetric != null && memoryMetric.getCount() > 0) {
                long avgMemoryMB = (long) (memoryMetric.getAverage() / 1024 / 1024);
                if (avgMemoryMB > MEMORY_CRITICAL_MB) {
                    criticalIssues++;
                } else if (avgMemoryMB > MEMORY_WARNING_MB) {
                    warningIssues++;
                }
            }
            
            // Determine overall health status
            if (criticalIssues > 0) {
                return SystemHealthStatus.CRITICAL;
            } else if (warningIssues > 2) {
                return SystemHealthStatus.WARNING;
            } else if (warningIssues > 0) {
                return SystemHealthStatus.GOOD;
            } else {
                return SystemHealthStatus.EXCELLENT;
            }
            
        } catch (Exception e) {
            logger.error("Error assessing system health", e);
            return SystemHealthStatus.FAILED;
        }
    }
    
    private double calculateCacheHitRate() {
        PerformanceMetric hitsMetric = metrics.get("cacheHits");
        PerformanceMetric missesMetric = metrics.get("cacheMisses");
        
        if (hitsMetric == null || missesMetric == null) return 100.0;
        
        long hits = hitsMetric.getCount();
        long misses = missesMetric.getCount();
        long total = hits + misses;
        
        return total > 0 ? (double) hits / total * 100 : 100.0;
    }
    
    private void checkCachePerformance(double hitRate) {
        if (hitRate < CACHE_HIT_RATE_CRITICAL) {
            createAlert(SystemAlert.AlertSeverity.CRITICAL, "Cache Performance",
                "Critical cache hit rate: " + String.format("%.1f%%", hitRate),
                Map.of("hitRate", hitRate));
        } else if (hitRate < CACHE_HIT_RATE_WARNING) {
            createAlert(SystemAlert.AlertSeverity.WARNING, "Cache Performance",
                "Low cache hit rate: " + String.format("%.1f%%", hitRate),
                Map.of("hitRate", hitRate));
        }
    }
    
    private void createAlert(SystemAlert.AlertSeverity severity, String category, String message, Map<String, Object> context) {
        SystemAlert alert = new SystemAlert(severity, category, message, context);
        activeAlerts.offer(alert);
        
        // Keep only recent alerts
        while (activeAlerts.size() > 50) {
            activeAlerts.poll();
        }
        
        // Log based on severity
        switch (severity) {
            case CRITICAL -> logger.error("CRITICAL ALERT: {}", alert);
            case WARNING -> logger.warn("WARNING: {}", alert);
            case INFO -> logger.info("INFO: {}", alert);
        }
    }
    
    private SystemResourceUsage getSystemResourceUsage() {
        Runtime runtime = Runtime.getRuntime();
        long totalMemory = runtime.totalMemory();
        long freeMemory = runtime.freeMemory();
        long usedMemory = totalMemory - freeMemory;
        
        // Simple CPU usage approximation (would need more sophisticated implementation for accuracy)
        double cpuUsage = 0.0;
        
        int activeThreads = Thread.activeCount();
        
        return new SystemResourceUsage(totalMemory, usedMemory, freeMemory, cpuUsage, activeThreads);
    }
    
    @Override
    public void close() {
        stopMonitoring();
        
        monitoringExecutor.shutdown();
        try {
            if (!monitoringExecutor.awaitTermination(5, TimeUnit.SECONDS)) {
                monitoringExecutor.shutdownNow();
            }
        } catch (InterruptedException e) {
            monitoringExecutor.shutdownNow();
            Thread.currentThread().interrupt();
        }
        
        logger.info("Texture Performance Monitor closed");
    }
    
    /**
     * Print comprehensive performance report
     */
    public void printPerformanceReport() {
        PerformanceReport report = getCurrentPerformanceReport();
        
        logger.info("=== Texture Performance Monitor Report ===");
        logger.info("System Health: {}", report.getHealthStatus());
        logger.info("Monitoring Cycles: {}", report.getMonitoringCycles());
        
        SystemResourceUsage usage = report.getResourceUsage();
        logger.info("Memory Usage: {:.1f}% ({}/{} MB)", 
            usage.getMemoryUtilization(),
            usage.getUsedMemory() / 1024 / 1024,
            usage.getTotalMemory() / 1024 / 1024);
        
        logger.info("Performance Metrics:");
        for (Map.Entry<String, MetricSummary> entry : report.getMetrics().entrySet()) {
            MetricSummary metric = entry.getValue();
            logger.info("  {}: Avg={:.1f}, Min={}, Max={}, Count={}, Trend={:.2f}",
                metric.getName(), metric.getAverage(), metric.getMin(), 
                metric.getMax(), metric.getCount(), metric.getTrend());
        }
        
        if (!report.getRecentAlerts().isEmpty()) {
            logger.info("Recent Alerts:");
            for (SystemAlert alert : report.getRecentAlerts()) {
                logger.info("  {}", alert);
            }
        }
    }
}