package com.openmason.util;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Duration;
import java.util.concurrent.Callable;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ThreadLocalRandom;
import java.util.function.Predicate;
import java.util.function.Supplier;

/**
 * Robust retry manager with exponential backoff, circuit breaker pattern, and comprehensive error recovery.
 * Provides resilient operation execution with configurable retry strategies and failure handling.
 */
public class RetryManager {
    
    private static final Logger logger = LoggerFactory.getLogger(RetryManager.class);
    
    /**
     * Configuration for retry operations.
     */
    public static class RetryConfig {
        private int maxAttempts = 3;
        private Duration initialDelay = Duration.ofMillis(100);
        private Duration maxDelay = Duration.ofSeconds(5);
        private double backoffMultiplier = 2.0;
        private double jitterFactor = 0.1; // 10% jitter
        private Predicate<Exception> retryablePredicate = e -> true;
        private boolean enableCircuitBreaker = false;
        private int circuitBreakerFailureThreshold = 5;
        private Duration circuitBreakerResetTimeout = Duration.ofMinutes(1);
        
        public RetryConfig maxAttempts(int maxAttempts) {
            this.maxAttempts = Math.max(1, maxAttempts);
            return this;
        }
        
        public RetryConfig initialDelay(Duration initialDelay) {
            this.initialDelay = initialDelay;
            return this;
        }
        
        public RetryConfig maxDelay(Duration maxDelay) {
            this.maxDelay = maxDelay;
            return this;
        }
        
        public RetryConfig backoffMultiplier(double backoffMultiplier) {
            this.backoffMultiplier = Math.max(1.0, backoffMultiplier);
            return this;
        }
        
        public RetryConfig jitterFactor(double jitterFactor) {
            this.jitterFactor = Math.max(0.0, Math.min(1.0, jitterFactor));
            return this;
        }
        
        public RetryConfig retryOn(Predicate<Exception> retryablePredicate) {
            this.retryablePredicate = retryablePredicate;
            return this;
        }
        
        public RetryConfig retryOn(Class<? extends Exception> exceptionClass) {
            this.retryablePredicate = exceptionClass::isInstance;
            return this;
        }
        
        public RetryConfig enableCircuitBreaker(int failureThreshold, Duration resetTimeout) {
            this.enableCircuitBreaker = true;
            this.circuitBreakerFailureThreshold = failureThreshold;
            this.circuitBreakerResetTimeout = resetTimeout;
            return this;
        }
        
        public RetryConfig disableCircuitBreaker() {
            this.enableCircuitBreaker = false;
            return this;
        }
    }
    
    /**
     * Circuit breaker states.
     */
    private enum CircuitBreakerState {
        CLOSED,    // Normal operation
        OPEN,      // Circuit is open (failing fast)
        HALF_OPEN  // Testing if service is recovered
    }
    
    /**
     * Circuit breaker implementation.
     */
    private static class CircuitBreaker {
        private volatile CircuitBreakerState state = CircuitBreakerState.CLOSED;
        private volatile int failureCount = 0;
        private volatile long lastFailureTime = 0;
        private final int failureThreshold;
        private final long resetTimeoutMs;
        
        public CircuitBreaker(int failureThreshold, Duration resetTimeout) {
            this.failureThreshold = failureThreshold;
            this.resetTimeoutMs = resetTimeout.toMillis();
        }
        
        public boolean canExecute() {
            switch (state) {
                case CLOSED:
                    return true;
                case OPEN:
                    if (System.currentTimeMillis() - lastFailureTime >= resetTimeoutMs) {
                        state = CircuitBreakerState.HALF_OPEN;
                        return true;
                    }
                    return false;
                case HALF_OPEN:
                    return true;
                default:
                    return false;
            }
        }
        
        public void recordSuccess() {
            failureCount = 0;
            state = CircuitBreakerState.CLOSED;
        }
        
        public void recordFailure() {
            failureCount++;
            lastFailureTime = System.currentTimeMillis();
            
            if (failureCount >= failureThreshold) {
                state = CircuitBreakerState.OPEN;
            }
        }
    }
    
    /**
     * Result of a retry operation.
     */
    public static class RetryResult<T> {
        private final T result;
        private final Exception lastException;
        private final int attemptCount;
        private final long totalDuration;
        private final boolean success;
        
        private RetryResult(T result, Exception lastException, int attemptCount, long totalDuration, boolean success) {
            this.result = result;
            this.lastException = lastException;
            this.attemptCount = attemptCount;
            this.totalDuration = totalDuration;
            this.success = success;
        }
        
        public static <T> RetryResult<T> success(T result, int attemptCount, long totalDuration) {
            return new RetryResult<>(result, null, attemptCount, totalDuration, true);
        }
        
        public static <T> RetryResult<T> failure(Exception lastException, int attemptCount, long totalDuration) {
            return new RetryResult<>(null, lastException, attemptCount, totalDuration, false);
        }
        
        public T getResult() {
            return result;
        }
        
        public Exception getLastException() {
            return lastException;
        }
        
        public int getAttemptCount() {
            return attemptCount;
        }
        
        public long getTotalDurationMs() {
            return totalDuration;
        }
        
        public boolean isSuccess() {
            return success;
        }
        
        public T orElse(T defaultValue) {
            return success ? result : defaultValue;
        }
        
        public T orElseGet(Supplier<T> defaultSupplier) {
            return success ? result : defaultSupplier.get();
        }
        
        public T orElseThrow() throws Exception {
            if (success) {
                return result;
            } else {
                throw lastException;
            }
        }
        
        public <X extends Exception> T orElseThrow(Supplier<X> exceptionSupplier) throws X {
            if (success) {
                return result;
            } else {
                throw exceptionSupplier.get();
            }
        }
    }
    
    private final CircuitBreaker circuitBreaker;
    private final RetryConfig config;
    
    public RetryManager(RetryConfig config) {
        this.config = config;
        this.circuitBreaker = config.enableCircuitBreaker ? 
            new CircuitBreaker(config.circuitBreakerFailureThreshold, config.circuitBreakerResetTimeout) : null;
    }
    
    /**
     * Creates a default retry manager with sensible defaults.
     */
    public static RetryManager defaultManager() {
        return new RetryManager(new RetryConfig());
    }
    
    /**
     * Creates a retry manager with custom configuration.
     */
    public static RetryManager withConfig(RetryConfig config) {
        return new RetryManager(config);
    }
    
    /**
     * Executes a callable with retry logic.
     */
    public <T> RetryResult<T> execute(Callable<T> operation) {
        return execute(operation, "unnamed operation");
    }
    
    /**
     * Executes a callable with retry logic and operation name for logging.
     */
    public <T> RetryResult<T> execute(Callable<T> operation, String operationName) {
        long startTime = System.currentTimeMillis();
        Exception lastException = null;
        
        for (int attempt = 1; attempt <= config.maxAttempts; attempt++) {
            // Check circuit breaker
            if (circuitBreaker != null && !circuitBreaker.canExecute()) {
                lastException = new RuntimeException("Circuit breaker is OPEN for operation: " + operationName);
                logger.warn("Circuit breaker preventing execution of {}", operationName);
                break;
            }
            
            try {
                logger.debug("Executing {} (attempt {}/{})", operationName, attempt, config.maxAttempts);
                
                T result = operation.call();
                
                // Success - record in circuit breaker if enabled
                if (circuitBreaker != null) {
                    circuitBreaker.recordSuccess();
                }
                
                long duration = System.currentTimeMillis() - startTime;
                if (attempt > 1) {
                    logger.info("Operation {} succeeded on attempt {}/{} after {}ms", 
                               operationName, attempt, config.maxAttempts, duration);
                }
                
                return RetryResult.success(result, attempt, duration);
                
            } catch (Exception e) {
                lastException = e;
                
                // Check if this exception is retryable
                if (!config.retryablePredicate.test(e)) {
                    logger.error("Non-retryable exception in {}: {}", operationName, e.getMessage());
                    break;
                }
                
                // Record failure in circuit breaker
                if (circuitBreaker != null) {
                    circuitBreaker.recordFailure();
                }
                
                if (attempt < config.maxAttempts) {
                    long delay = calculateDelay(attempt);
                    logger.warn("Operation {} failed on attempt {}/{}: {}. Retrying in {}ms", 
                               operationName, attempt, config.maxAttempts, e.getMessage(), delay);
                    
                    try {
                        Thread.sleep(delay);
                    } catch (InterruptedException ie) {
                        Thread.currentThread().interrupt();
                        logger.error("Retry interrupted for operation {}", operationName);
                        break;
                    }
                } else {
                    logger.error("Operation {} failed after {} attempts: {}", 
                                operationName, config.maxAttempts, e.getMessage());
                }
            }
        }
        
        long duration = System.currentTimeMillis() - startTime;
        return RetryResult.failure(lastException, config.maxAttempts, duration);
    }
    
    /**
     * Executes a runnable with retry logic.
     */
    public RetryResult<Void> execute(Runnable operation) {
        return execute(operation, "unnamed runnable operation");
    }
    
    /**
     * Executes a runnable with retry logic and operation name.
     */
    public RetryResult<Void> execute(Runnable operation, String operationName) {
        return execute(() -> {
            operation.run();
            return null;
        }, operationName);
    }
    
    /**
     * Executes a callable asynchronously with retry logic.
     */
    public <T> CompletableFuture<RetryResult<T>> executeAsync(Callable<T> operation) {
        return executeAsync(operation, "unnamed async operation");
    }
    
    /**
     * Executes a callable asynchronously with retry logic and operation name.
     */
    public <T> CompletableFuture<RetryResult<T>> executeAsync(Callable<T> operation, String operationName) {
        return CompletableFuture.supplyAsync(() -> execute(operation, operationName));
    }
    
    /**
     * Calculates the delay for the next retry attempt using exponential backoff with jitter.
     */
    private long calculateDelay(int attempt) {
        // Exponential backoff: initialDelay * (backoffMultiplier ^ (attempt - 1))
        double delayMs = config.initialDelay.toMillis() * Math.pow(config.backoffMultiplier, attempt - 1);
        
        // Apply maximum delay limit
        delayMs = Math.min(delayMs, config.maxDelay.toMillis());
        
        // Add jitter to prevent thundering herd
        if (config.jitterFactor > 0) {
            double jitter = delayMs * config.jitterFactor;
            double randomJitter = (ThreadLocalRandom.current().nextDouble() - 0.5) * 2 * jitter;
            delayMs += randomJitter;
        }
        
        return Math.max(0, (long) delayMs);
    }
    
    /**
     * Gets the current circuit breaker state (if enabled).
     */
    public String getCircuitBreakerState() {
        return circuitBreaker != null ? circuitBreaker.state.toString() : "DISABLED";
    }
    
    /**
     * Resets the circuit breaker (if enabled).
     */
    public void resetCircuitBreaker() {
        if (circuitBreaker != null) {
            circuitBreaker.failureCount = 0;
            circuitBreaker.state = CircuitBreakerState.CLOSED;
            logger.info("Circuit breaker manually reset");
        }
    }
    
    /**
     * Common retry configurations for typical use cases.
     */
    public static class CommonConfigs {
        
        /**
         * Configuration for quick operations (UI, local file access).
         */
        public static RetryConfig quickOperation() {
            return new RetryConfig()
                .maxAttempts(3)
                .initialDelay(Duration.ofMillis(50))
                .maxDelay(Duration.ofMillis(500))
                .backoffMultiplier(2.0);
        }
        
        /**
         * Configuration for network operations.
         */
        public static RetryConfig networkOperation() {
            return new RetryConfig()
                .maxAttempts(5)
                .initialDelay(Duration.ofMillis(200))
                .maxDelay(Duration.ofSeconds(10))
                .backoffMultiplier(2.0)
                .jitterFactor(0.2)
                .enableCircuitBreaker(3, Duration.ofMinutes(1));
        }
        
        /**
         * Configuration for database operations.
         */
        public static RetryConfig databaseOperation() {
            return new RetryConfig()
                .maxAttempts(4)
                .initialDelay(Duration.ofMillis(100))
                .maxDelay(Duration.ofSeconds(5))
                .backoffMultiplier(1.5)
                .enableCircuitBreaker(5, Duration.ofSeconds(30));
        }
        
        /**
         * Configuration for OpenGL operations.
         */
        public static RetryConfig openGLOperation() {
            return new RetryConfig()
                .maxAttempts(3)
                .initialDelay(Duration.ofMillis(10))
                .maxDelay(Duration.ofMillis(100))
                .backoffMultiplier(2.0)
                .retryOn(e -> !e.getMessage().contains("context"));
        }
        
        /**
         * Configuration for model loading operations.
         */
        public static RetryConfig modelLoadingOperation() {
            return new RetryConfig()
                .maxAttempts(3)
                .initialDelay(Duration.ofMillis(500))
                .maxDelay(Duration.ofSeconds(5))
                .backoffMultiplier(2.0)
                .retryOn(e -> !(e instanceof IllegalArgumentException));
        }
    }
}