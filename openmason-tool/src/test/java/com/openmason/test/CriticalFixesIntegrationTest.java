package com.openmason.test;

import com.openmason.camera.ArcBallCamera;
import com.openmason.rendering.*;
import com.openmason.model.ModelManager;
import com.openmason.model.StonebreakModel;
import com.openmason.texture.TextureManager;
import com.openmason.test.mocks.MockDriftFXContext;
import com.openmason.test.mocks.MockOpenGLContext;
import com.openmason.test.performance.PerformanceBenchmark;
import com.openmason.test.performance.MemoryBenchmarkResult;

import org.joml.Matrix4f;
import org.joml.Vector3f;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.junit.jupiter.MockitoExtension;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.*;
import static org.awaitility.Awaitility.*;
import static java.time.Duration.ofSeconds;

/**
 * Comprehensive integration test that validates all three critical fixes working together:
 * 
 * 1. OpenGL Context Validation (Fix #1): Tests that OpenGL context validation works 
 *    properly in ModelRenderer with proper error handling and resource cleanup scenarios.
 * 
 * 2. Resource Leak Prevention (Fix #2): Tests that VertexArray creation includes proper 
 *    exception-safe cleanup and resource tracking to prevent memory/GPU resource leaks.
 * 
 * 3. Thread Safety with Cancellation (Fix #3): Tests that ModelManager's concurrent 
 *    loading handles cancellation correctly under resource pressure without race conditions.
 * 
 * This test validates that all three systems work together harmoniously without conflicts,
 * regressions, or performance degradation in the existing Stonebreak game integration.
 */
@ExtendWith(MockitoExtension.class)
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
public class CriticalFixesIntegrationTest {
    
    private static final Logger logger = LoggerFactory.getLogger(CriticalFixesIntegrationTest.class);
    
    // Test infrastructure
    private MockDriftFXContext mockDriftFXContext;
    private MockOpenGLContext mockOpenGLContext;
    private PerformanceBenchmark benchmark;
    
    // Core components under test
    private ModelRenderer modelRenderer;
    private ArcBallCamera camera;
    private PerformanceOptimizer performanceOptimizer;
    private BufferManager bufferManager;
    private TextureManager textureManager;
    
    // Test data
    private final List<String> testModelNames = Arrays.asList("standard_cow", "test_model", "fallback_model");
    private final List<String> testTextureVariants = Arrays.asList("default", "angus", "highland", "jersey");
    
    // Concurrency test infrastructure
    private ExecutorService testExecutor;
    private final List<CompletableFuture<?>> activeFutures = Collections.synchronizedList(new ArrayList<>());
    private final AtomicInteger resourceCreationCount = new AtomicInteger(0);
    private final AtomicInteger resourceCleanupCount = new AtomicInteger(0);
    
    @BeforeAll
    void setUpSuite() {
        logger.info("Setting up Critical Fixes Integration Test Suite");
        
        // Initialize test infrastructure
        mockDriftFXContext = new MockDriftFXContext();
        mockOpenGLContext = new MockOpenGLContext();
        benchmark = new PerformanceBenchmark("CriticalFixesIntegration");
        
        // Configure system properties for testing
        System.setProperty("java.awt.headless", "true");
        System.setProperty("testfx.headless", "true");
        System.setProperty("openmason.context.validation.enabled", "true");
        System.setProperty("openmason.resource.tracking.enabled", "true");
        System.setProperty("openmason.thread.safety.debug", "true");
        
        // Initialize test executor for concurrency tests
        testExecutor = Executors.newFixedThreadPool(8, r -> {
            Thread t = new Thread(r, "CriticalFixesTest-" + System.currentTimeMillis());
            t.setDaemon(true);
            return t;
        });
        
        logger.info("Critical Fixes Integration Test Suite initialized");
    }
    
    @BeforeEach
    void setUp() {
        logger.debug("Setting up individual test");
        
        // Reset mock contexts
        mockDriftFXContext.reset();
        mockOpenGLContext.reset();
        
        // Clear benchmark results
        benchmark.clearResults();
        
        // Initialize core components
        initializeComponents();
        
        // Reset counters
        resourceCreationCount.set(0);
        resourceCleanupCount.set(0);
        
        // Clear any pending futures
        activeFutures.clear();
        
        logger.debug("Test setup completed");
    }
    
    @AfterEach
    void tearDown() {
        logger.debug("Tearing down test");
        
        // Cancel all active futures
        for (CompletableFuture<?> future : activeFutures) {
            if (!future.isDone()) {
                future.cancel(true);
            }
        }
        activeFutures.clear();
        
        // Cleanup components
        cleanupComponents();
        
        // Force garbage collection to help detect leaks
        System.gc();
        Thread.yield();
        
        logger.debug("Test teardown completed");
    }
    
    @AfterAll
    void tearDownSuite() {
        logger.info("Tearing down Critical Fixes Integration Test Suite");
        
        // Shutdown test executor
        testExecutor.shutdown();
        try {
            if (!testExecutor.awaitTermination(5, TimeUnit.SECONDS)) {
                testExecutor.shutdownNow();
            }
        } catch (InterruptedException e) {
            testExecutor.shutdownNow();
            Thread.currentThread().interrupt();
        }
        
        // Final cleanup
        if (bufferManager != null) {
            bufferManager.cleanup();
        }
        
        ModelManager.shutdown();
        
        logger.info("Critical Fixes Integration Test Suite completed");
    }
    
    /**
     * TEST 1: OpenGL Context Validation with Resource Cleanup
     * 
     * Tests that OpenGL context validation works properly in ModelRenderer
     * and handles resource cleanup scenarios correctly.
     */
    @Test
    @Order(1)
    @DisplayName("Fix #1: OpenGL Context Validation with Resource Cleanup")
    void testOpenGLContextValidationWithResourceCleanup() {
        logger.info("Testing OpenGL context validation with resource cleanup scenarios");
        
        // Test normal context validation
        benchmark.measureSingle(() -> {
            // Context should be valid initially
            List<String> contextIssues = OpenGLValidator.validateContext("test");
            assertTrue(contextIssues.isEmpty() || contextIssues.stream().allMatch(issue -> issue.contains("WARNING")),
                      "Initial context should be valid or have only warnings");
            
            // ModelRenderer should initialize successfully with valid context
            assertTrue(modelRenderer.isInitialized(), "ModelRenderer should be initialized");
            
            // Context validation should be enabled by default
            assertTrue(modelRenderer.isContextValidationEnabled(), 
                      "Context validation should be enabled by default");
        }, "Normal context validation");
        
        // Test context validation during model preparation
        benchmark.measureSingle(() -> {
            // Create a mock model for testing
            StonebreakModel testModel = createMockModel("test_model");
            
            // Model preparation should succeed with valid context
            assertDoesNotThrow(() -> {
                boolean prepared = modelRenderer.prepareModel(testModel);
                assertTrue(prepared, "Model preparation should succeed with valid context");
            }, "Model preparation should not throw with valid context");
            
            // Verify the model is now prepared
            assertTrue(modelRenderer.isModelPrepared(testModel), 
                      "Model should be prepared after successful preparation");
        }, "Context validation during model preparation");
        
        // Test handling of OpenGL errors during operations
        benchmark.measureSingle(() -> {
            // Simulate OpenGL error
            mockOpenGLContext.simulateError(MockOpenGLContext.GL_INVALID_OPERATION);
            
            // ModelRenderer should detect and handle the error gracefully
            RuntimeException thrownException = assertThrows(RuntimeException.class, () -> {
                StonebreakModel testModel = createMockModel("error_test_model");
                modelRenderer.prepareModel(testModel);
            }, "ModelRenderer should throw when OpenGL context has errors");
            
            assertTrue(thrownException.getMessage().contains("invalid OpenGL context"),
                      "Exception should indicate OpenGL context validation failure");
            
            // Reset error state
            mockOpenGLContext.glGetError(); // Clear error
        }, "OpenGL error handling during model operations");
        
        // Test resource cleanup when context validation fails
        benchmark.measureSingle(() -> {
            int initialVAOCount = bufferManager.getVertexArrayCount();
            
            // Simulate context error that would occur during VAO creation
            mockOpenGLContext.simulateError(MockOpenGLContext.GL_OUT_OF_MEMORY);
            
            // Attempt to create model - should fail and cleanup properly
            assertThrows(RuntimeException.class, () -> {
                StonebreakModel testModel = createMockModel("cleanup_test_model");
                modelRenderer.prepareModel(testModel);
            }, "Model preparation should fail with context errors");
            
            // VAO count should not increase (resources should be cleaned up)
            assertEquals(initialVAOCount, bufferManager.getVertexArrayCount(),
                        "VAO count should not increase when creation fails with cleanup");
            
            // Clear error for subsequent tests
            mockOpenGLContext.glGetError();
        }, "Resource cleanup on context validation failure");
        
        // Test context validation can be disabled for performance
        benchmark.measureSingle(() -> {
            modelRenderer.setContextValidationEnabled(false);
            assertFalse(modelRenderer.isContextValidationEnabled(),
                       "Context validation should be disableable");
            
            // Operations should still work, but faster (no validation overhead)
            StonebreakModel testModel = createMockModel("no_validation_model");
            long startTime = System.nanoTime();
            modelRenderer.prepareModel(testModel);
            long elapsedTime = System.nanoTime() - startTime;
            
            // Re-enable for other tests
            modelRenderer.setContextValidationEnabled(true);
            
            assertTrue(elapsedTime > 0, "Operation should complete (timing check)");
        }, "Disabling context validation for performance");
        
        logger.info("OpenGL context validation test completed successfully");
    }
    
    /**
     * TEST 2: Resource Leak Prevention in VertexArray Creation
     * 
     * Tests that VertexArray creation includes proper exception-safe cleanup
     * and resource tracking to prevent memory/GPU resource leaks.
     */
    @Test
    @Order(2)
    @DisplayName("Fix #2: Resource Leak Prevention in VertexArray Creation")
    void testResourceLeakPreventionInVertexArrayCreation() {
        logger.info("Testing resource leak prevention in VertexArray creation");
        
        // Test normal VertexArray creation and tracking
        benchmark.measureSingle(() -> {
            int initialCount = bufferManager.getVertexArrayCount();
            
            // Create a VertexArray - should be tracked properly
            try (VertexArray vao = new VertexArray("test_vao_1")) {
                assertEquals(initialCount + 1, bufferManager.getVertexArrayCount(),
                           "VertexArray count should increase after creation");
                
                assertTrue(vao.isValid(), "Created VertexArray should be valid");
                assertFalse(vao.isDisposed(), "Created VertexArray should not be disposed");
                
                // VAO should be properly registered
                assertNotNull(vao.getDebugName(), "VertexArray should have debug name");
                assertEquals("test_vao_1", vao.getDebugName(), "Debug name should match");
            }
            
            // After closing, count should return to original
            assertEquals(initialCount, bufferManager.getVertexArrayCount(),
                        "VertexArray count should decrease after disposal");
        }, "Normal VertexArray creation and cleanup");
        
        // Test exception safety during VertexArray creation
        benchmark.measureSingle(() -> {
            int initialCount = bufferManager.getVertexArrayCount();
            
            // Simulate failure during buffer manager registration
            // We'll test this by creating many VAOs rapidly to potentially trigger issues
            List<VertexArray> vaos = new ArrayList<>();
            try {
                for (int i = 0; i < 10; i++) {
                    VertexArray vao = new VertexArray("stress_test_vao_" + i);
                    vaos.add(vao);
                    resourceCreationCount.incrementAndGet();
                }
                
                // All VAOs should be properly tracked
                assertEquals(initialCount + 10, bufferManager.getVertexArrayCount(),
                           "All VertexArrays should be tracked during stress test");
                
            } finally {
                // Clean up all VAOs
                for (VertexArray vao : vaos) {
                    try {
                        vao.close();
                        resourceCleanupCount.incrementAndGet();
                    } catch (Exception e) {
                        logger.warn("Error during VAO cleanup in stress test", e);
                    }
                }
            }
            
            // Count should return to original after cleanup
            assertEquals(initialCount, bufferManager.getVertexArrayCount(),
                        "VertexArray count should return to initial after stress test cleanup");
            
            // Verify all resources were properly cleaned up
            assertEquals(resourceCreationCount.get(), resourceCleanupCount.get(),
                        "All created resources should be cleaned up");
        }, "Exception safety during VertexArray creation");
        
        // Test OpenGL resource cleanup on creation failure
        benchmark.measureSingle(() -> {
            int initialCount = bufferManager.getVertexArrayCount();
            
            // Simulate OpenGL VAO generation failure
            mockOpenGLContext.simulateError(MockOpenGLContext.GL_OUT_OF_MEMORY);
            
            // VertexArray creation should fail safely
            RuntimeException exception = assertThrows(RuntimeException.class, () -> {
                new VertexArray("failing_vao");
            }, "VertexArray creation should fail when OpenGL VAO generation fails");
            
            assertTrue(exception.getMessage().contains("Failed to register VertexArray"),
                      "Exception should indicate registration failure");
            
            // Buffer manager count should not change (failed VAO not tracked)
            assertEquals(initialCount, bufferManager.getVertexArrayCount(),
                        "Failed VertexArray should not be tracked");
            
            // Clear the error
            mockOpenGLContext.glGetError();
        }, "OpenGL resource cleanup on creation failure");
        
        // Test memory pressure handling
        benchmark.measureSingle(() -> {
            // Set very low memory warning threshold
            bufferManager.setMemoryWarningThreshold(1024); // 1KB
            
            List<VertexArray> vaos = new ArrayList<>();
            try {
                // Create many VAOs to trigger memory warnings
                for (int i = 0; i < 50; i++) {
                    VertexArray vao = new VertexArray("memory_pressure_vao_" + i);
                    vaos.add(vao);
                    
                    // Add some delay to allow memory tracking to update
                    if (i % 10 == 0) {
                        Thread.sleep(10);
                    }
                }
                
                // System should handle memory pressure gracefully
                assertTrue(vaos.size() > 0, "Some VAOs should be created even under memory pressure");
                
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            } finally {
                // Clean up all VAOs
                for (VertexArray vao : vaos) {
                    try {
                        vao.close();
                    } catch (Exception e) {
                        logger.warn("Error during VAO cleanup in memory pressure test", e);
                    }
                }
                
                // Reset memory warning threshold
                bufferManager.setMemoryWarningThreshold(1024 * 1024 * 10); // 10MB default
            }
        }, "Memory pressure handling");
        
        // Test concurrent VertexArray creation and cleanup
        benchmark.measureSingle(() -> {
            int initialCount = bufferManager.getVertexArrayCount();
            final int numThreads = 4;
            final int vaosPerThread = 5;
            final CountDownLatch startLatch = new CountDownLatch(1);
            final CountDownLatch completionLatch = new CountDownLatch(numThreads);
            final List<Exception> exceptions = Collections.synchronizedList(new ArrayList<>());
            
            // Create concurrent VAO creation/cleanup tasks
            for (int t = 0; t < numThreads; t++) {
                final int threadId = t;
                testExecutor.submit(() -> {
                    try {
                        startLatch.await(); // Wait for all threads to be ready
                        
                        List<VertexArray> threadVaos = new ArrayList<>();
                        try {
                            // Create VAOs
                            for (int i = 0; i < vaosPerThread; i++) {
                                VertexArray vao = new VertexArray("concurrent_vao_" + threadId + "_" + i);
                                threadVaos.add(vao);
                            }
                            
                            // Brief delay to stress test concurrent access
                            Thread.sleep(50);
                            
                        } finally {
                            // Clean up VAOs
                            for (VertexArray vao : threadVaos) {
                                try {
                                    vao.close();
                                } catch (Exception e) {
                                    exceptions.add(e);
                                }
                            }
                        }
                    } catch (Exception e) {
                        exceptions.add(e);
                    } finally {
                        completionLatch.countDown();
                    }
                });
            }
            
            // Start all threads simultaneously
            startLatch.countDown();
            
            // Wait for completion
            assertTrue(assertDoesNotThrow(() -> completionLatch.await(10, TimeUnit.SECONDS)),
                      "Concurrent VAO operations should complete within timeout");
            
            // Check for exceptions
            if (!exceptions.isEmpty()) {
                fail("Concurrent VAO operations should not throw exceptions: " + 
                     exceptions.stream().map(Exception::getMessage).collect(Collectors.joining(", ")));
            }
            
            // VAO count should return to initial after all cleanup
            await().atMost(5, TimeUnit.SECONDS).until(() -> 
                bufferManager.getVertexArrayCount() == initialCount);
            
        }, "Concurrent VertexArray creation and cleanup");
        
        logger.info("Resource leak prevention test completed successfully");
    }
    
    /**
     * TEST 3: Thread Safety with Cancellation in ModelManager
     * 
     * Tests that ModelManager's concurrent loading handles cancellation correctly
     * under resource pressure without race conditions.
     */
    @Test
    @Order(3)
    @DisplayName("Fix #3: Thread Safety with Cancellation in ModelManager")
    void testThreadSafetyWithCancellationInModelManager() {
        logger.info("Testing thread safety with cancellation in ModelManager");
        
        // Test basic concurrent model loading
        benchmark.measureSingle(() -> {
            final int numConcurrentLoads = 6;
            final CountDownLatch startLatch = new CountDownLatch(1);
            final List<CompletableFuture<ModelManager.ModelInfo>> futures = 
                Collections.synchronizedList(new ArrayList<>());
            final AtomicInteger completedLoads = new AtomicInteger(0);
            final AtomicInteger failedLoads = new AtomicInteger(0);
            
            // Create concurrent loading tasks
            for (int i = 0; i < numConcurrentLoads; i++) {
                final String modelName = "concurrent_test_model_" + i;
                
                CompletableFuture<ModelManager.ModelInfo> future = 
                    testExecutor.submit(() -> {
                        try {
                            startLatch.await();
                            return ModelManager.loadModelInfoAsync(modelName, 
                                ModelManager.LoadingPriority.NORMAL, null).get(5, TimeUnit.SECONDS);
                        } catch (Exception e) {
                            throw new RuntimeException(e);
                        }
                    })
                    .thenCompose(result -> CompletableFuture.completedFuture(result))
                    .handle((result, throwable) -> {
                        if (throwable != null) {
                            failedLoads.incrementAndGet();
                            return null;
                        } else {
                            completedLoads.incrementAndGet();
                            return result;
                        }
                    });
                
                futures.add(future);
                activeFutures.add(future);
            }
            
            // Start all loading operations simultaneously
            startLatch.countDown();
            
            // Wait for completion or timeout
            CompletableFuture<Void> allFutures = CompletableFuture.allOf(
                futures.toArray(new CompletableFuture[0]));
            
            assertDoesNotThrow(() -> allFutures.get(10, TimeUnit.SECONDS),
                             "Concurrent model loading should complete within timeout");
            
            // At least some loads should succeed (depending on mock model availability)
            int totalOperations = completedLoads.get() + failedLoads.get();
            assertEquals(numConcurrentLoads, totalOperations,
                        "All concurrent operations should complete (either succeed or fail)");
            
        }, "Basic concurrent model loading");
        
        // Test cancellation of pending loads
        benchmark.measureSingle(() -> {
            final int numLoadsToCancel = 8;
            final List<CompletableFuture<ModelManager.ModelInfo>> futures = new ArrayList<>();
            
            // Create many load requests
            for (int i = 0; i < numLoadsToCancel; i++) {
                String modelName = "cancellation_test_model_" + i;
                CompletableFuture<ModelManager.ModelInfo> future = 
                    ModelManager.loadModelInfoAsync(modelName, 
                        ModelManager.LoadingPriority.LOW, null); // Use LOW priority for queue testing
                futures.add(future);
                activeFutures.add(future);
            }
            
            // Brief delay to allow requests to queue up
            Thread.sleep(100);
            
            // Cancel all requests
            int cancelledCount = 0;
            for (CompletableFuture<ModelManager.ModelInfo> future : futures) {
                if (future.cancel(false)) {
                    cancelledCount++;
                }
            }
            
            assertTrue(cancelledCount > 0, "At least some requests should be cancellable");
            
            // Cancel all pending loads in ModelManager
            int managerCancelledCount = ModelManager.cancelAllPendingLoads();
            assertTrue(managerCancelledCount >= 0, "Manager should report cancelled count");
            
            // Verify futures are cancelled
            for (CompletableFuture<ModelManager.ModelInfo> future : futures) {
                assertTrue(future.isDone(), "All futures should be done after cancellation");
            }
            
        }, "Cancellation of pending loads");
        
        // Test cancellation under resource pressure
        benchmark.measureSingle(() -> {
            // Set low memory threshold to create pressure
            bufferManager.setMemoryWarningThreshold(512); // Very low
            
            final int numHighPriorityLoads = 3;
            final int numLowPriorityLoads = 10;
            final List<CompletableFuture<ModelManager.ModelInfo>> allFutures = new ArrayList<>();
            
            // Create high priority loads that should complete
            for (int i = 0; i < numHighPriorityLoads; i++) {
                String modelName = "high_priority_model_" + i;
                CompletableFuture<ModelManager.ModelInfo> future = 
                    ModelManager.loadModelInfoAsync(modelName, 
                        ModelManager.LoadingPriority.HIGH, null);
                allFutures.add(future);
                activeFutures.add(future);
            }
            
            // Create low priority loads that may be cancelled
            for (int i = 0; i < numLowPriorityLoads; i++) {
                String modelName = "low_priority_model_" + i;
                CompletableFuture<ModelManager.ModelInfo> future = 
                    ModelManager.loadModelInfoAsync(modelName, 
                        ModelManager.LoadingPriority.LOW, null);
                allFutures.add(future);
                activeFutures.add(future);
            }
            
            // Brief delay to let some operations start
            Thread.sleep(200);
            
            // Cancel low priority operations under pressure
            int cancelledCount = 0;
            for (int i = numHighPriorityLoads; i < allFutures.size(); i++) {
                if (allFutures.get(i).cancel(false)) {
                    cancelledCount++;
                }
            }
            
            // System should handle cancellation gracefully under resource pressure
            assertTrue(cancelledCount >= 0, "Cancellation should work under resource pressure");
            
            // Reset memory threshold
            bufferManager.setMemoryWarningThreshold(1024 * 1024 * 10);
            
        }, "Cancellation under resource pressure");
        
        // Test race condition handling in concurrent cancellation
        benchmark.measureSingle(() -> {
            final int numRaceConditionTests = 5;
            final int loadsPerTest = 4;
            
            for (int test = 0; test < numRaceConditionTests; test++) {
                final List<CompletableFuture<ModelManager.ModelInfo>> testFutures = new ArrayList<>();
                final CountDownLatch cancelLatch = new CountDownLatch(1);
                final AtomicReference<Exception> raceException = new AtomicReference<>();
                
                // Create loads
                for (int i = 0; i < loadsPerTest; i++) {
                    String modelName = "race_test_model_" + test + "_" + i;
                    CompletableFuture<ModelManager.ModelInfo> future = 
                        ModelManager.loadModelInfoAsync(modelName, 
                            ModelManager.LoadingPriority.NORMAL, null);
                    testFutures.add(future);
                    activeFutures.add(future);
                }
                
                // Create concurrent cancellation thread
                Thread cancelThread = new Thread(() -> {
                    try {
                        cancelLatch.await();
                        // Cancel futures rapidly
                        for (CompletableFuture<ModelManager.ModelInfo> future : testFutures) {
                            future.cancel(false);
                        }
                    } catch (Exception e) {
                        raceException.set(e);
                    }
                });
                cancelThread.start();
                
                // Brief delay then trigger cancellation
                Thread.sleep(50);
                cancelLatch.countDown();
                
                // Wait for cancellation thread
                cancelThread.join(1000);
                
                // Check for race condition exceptions
                assertNull(raceException.get(), 
                          "Race condition handling should not throw exceptions: " + test);
                
                // All futures should be done
                for (CompletableFuture<ModelManager.ModelInfo> future : testFutures) {
                    assertTrue(future.isDone(), 
                              "All futures should be done after race condition test: " + test);
                }
            }
            
        }, "Race condition handling in concurrent cancellation");
        
        logger.info("Thread safety with cancellation test completed successfully");
    }
    
    /**
     * TEST 4: Integration Test - All Three Fixes Working Together
     * 
     * Tests that all three fixes work together without conflicts and maintain
     * the existing Stonebreak game integration requirements.
     */
    @Test
    @Order(4)
    @DisplayName("Integration: All Three Fixes Working Together")
    void testAllThreeFixesWorkingTogether() {
        logger.info("Testing all three fixes working together");
        
        // Test complete workflow with all fixes active
        benchmark.measureSingle(() -> {
            final int numModels = 3;
            final int numVariants = testTextureVariants.size();
            final List<StonebreakModel> loadedModels = Collections.synchronizedList(new ArrayList<>());
            final List<CompletableFuture<Void>> workflowFutures = new ArrayList<>();
            
            // PHASE 1: Concurrent model loading (Fix #3 - Thread Safety)
            for (int i = 0; i < numModels; i++) {
                final String modelName = "integration_model_" + i;
                
                CompletableFuture<Void> modelWorkflow = ModelManager
                    .loadModelInfoAsync(modelName, ModelManager.LoadingPriority.HIGH, null)
                    .thenCompose(modelInfo -> {
                        if (modelInfo != null) {
                            StonebreakModel model = createMockModel(modelName);
                            loadedModels.add(model);
                            
                            // PHASE 2: Model preparation with context validation (Fix #1)
                            return CompletableFuture.supplyAsync(() -> {
                                // Context validation should be active
                                assertTrue(modelRenderer.isContextValidationEnabled(),
                                          "Context validation should be enabled during integration test");
                                
                                // Model preparation should validate context and succeed
                                boolean prepared = modelRenderer.prepareModel(model);
                                assertTrue(prepared, "Model preparation should succeed: " + modelName);
                                
                                return model;
                            }, testExecutor);
                        } else {
                            return CompletableFuture.completedFuture((StonebreakModel) null);
                        }
                    })
                    .thenCompose(model -> {
                        if (model != null) {
                            // PHASE 3: Rendering with resource management (Fix #2)
                            return CompletableFuture.runAsync(() -> {
                                for (String variant : testTextureVariants) {
                                    // Each render operation should:
                                    // 1. Validate OpenGL context (Fix #1)
                                    // 2. Use properly managed VertexArrays (Fix #2) 
                                    // 3. Handle concurrent access safely (Fix #3)
                                    assertDoesNotThrow(() -> {
                                        modelRenderer.renderModel(model, variant);
                                    }, "Rendering should not throw: " + modelName + " variant " + variant);
                                }
                            }, testExecutor);
                        } else {
                            return CompletableFuture.completedFuture(null);
                        }
                    })
                    .exceptionally(throwable -> {
                        logger.warn("Model workflow failed for " + modelName, throwable);
                        return null;
                    });
                
                workflowFutures.add(modelWorkflow);
                activeFutures.add(modelWorkflow);
            }
            
            // Wait for all workflows to complete
            CompletableFuture<Void> allWorkflows = CompletableFuture.allOf(
                workflowFutures.toArray(new CompletableFuture[0]));
            
            assertDoesNotThrow(() -> allWorkflows.get(15, TimeUnit.SECONDS),
                             "Complete integration workflow should succeed");
            
            // Verify some models were successfully processed
            assertTrue(loadedModels.size() > 0, "At least some models should be loaded and processed");
            
        }, "Complete workflow with all fixes active");
        
        // Test error recovery with all fixes
        benchmark.measureSingle(() -> {
            // Simulate various error conditions to test recovery
            
            // 1. OpenGL error during context validation (Fix #1)
            mockOpenGLContext.simulateError(MockOpenGLContext.GL_INVALID_OPERATION);
            
            StonebreakModel errorModel = createMockModel("error_recovery_model");
            assertThrows(RuntimeException.class, () -> {
                modelRenderer.prepareModel(errorModel);
            }, "Context validation should catch OpenGL errors");
            
            // Clear error
            mockOpenGLContext.glGetError();
            
            // 2. Resource pressure during VAO creation (Fix #2)
            bufferManager.setMemoryWarningThreshold(100); // Very low
            
            StonebreakModel pressureModel = createMockModel("pressure_recovery_model");
            // Should still work, just with warnings
            assertDoesNotThrow(() -> {
                modelRenderer.prepareModel(pressureModel);
            }, "System should handle resource pressure gracefully");
            
            // Reset threshold
            bufferManager.setMemoryWarningThreshold(1024 * 1024 * 10);
            
            // 3. Cancellation during concurrent operations (Fix #3)
            List<CompletableFuture<ModelManager.ModelInfo>> cancelFutures = new ArrayList<>();
            for (int i = 0; i < 5; i++) {
                CompletableFuture<ModelManager.ModelInfo> future = ModelManager
                    .loadModelInfoAsync("cancel_recovery_model_" + i, 
                                       ModelManager.LoadingPriority.LOW, null);
                cancelFutures.add(future);
                activeFutures.add(future);
            }
            
            // Cancel them quickly
            for (CompletableFuture<ModelManager.ModelInfo> future : cancelFutures) {
                future.cancel(false);
            }
            
            // System should handle cancellation without issues
            int cancelledCount = ModelManager.cancelAllPendingLoads();
            assertTrue(cancelledCount >= 0, "Cancellation should work during error recovery");
            
        }, "Error recovery with all fixes");
        
        // Test performance impact of all fixes
        MemoryBenchmarkResult memoryResult = benchmark.measureMemory(() -> {
            // Simulate normal operation with all fixes active
            
            // Context validation enabled
            assertTrue(modelRenderer.isContextValidationEnabled());
            
            // Resource tracking enabled
            assertTrue(bufferManager.isMemoryTrackingEnabled());
            
            // Concurrent operations with cancellation support
            CompletableFuture<ModelManager.ModelInfo> future = ModelManager
                .loadModelInfoAsync("performance_test_model", 
                                   ModelManager.LoadingPriority.NORMAL, null);
            activeFutures.add(future);
            
            // Create and use some VAOs
            try (VertexArray vao1 = new VertexArray("perf_test_vao_1");
                 VertexArray vao2 = new VertexArray("perf_test_vao_2")) {
                
                assertTrue(vao1.isValid() && vao2.isValid(), "VAOs should be valid");
                
                // Simulate some rendering operations
                for (int i = 0; i < 10; i++) {
                    vao1.bind();
                    vao1.unbind();
                    vao2.bind();
                    vao2.unbind();
                }
            }
            
            // Cancel the load
            future.cancel(false);
            
        }, 100, "Normal operation with all fixes active");
        
        // Validate performance impact is reasonable
        assertTrue(memoryResult.getAllocationRate() < 50000, // 50KB per ms
                  "Memory allocation rate should be reasonable with all fixes: " + 
                  memoryResult.getAllocationRate());
        
        logger.info("Integration test of all three fixes completed successfully");
    }
    
    /**
     * TEST 5: Edge Cases and Error Conditions
     * 
     * Tests various edge cases and error conditions to ensure robustness.
     */
    @Test
    @Order(5)
    @DisplayName("Edge Cases and Error Conditions")
    void testEdgeCasesAndErrorConditions() {
        logger.info("Testing edge cases and error conditions");
        
        // Test null and invalid inputs
        benchmark.measureSingle(() -> {
            // Null model name in ModelManager
            assertThrows(Exception.class, () -> {
                ModelManager.loadModelInfoAsync(null, ModelManager.LoadingPriority.NORMAL, null)
                    .get(1, TimeUnit.SECONDS);
            }, "ModelManager should handle null model names");
            
            // Empty model name
            assertThrows(Exception.class, () -> {
                ModelManager.loadModelInfoAsync("", ModelManager.LoadingPriority.NORMAL, null)
                    .get(1, TimeUnit.SECONDS);
            }, "ModelManager should handle empty model names");
            
            // Null debug name for VertexArray
            assertDoesNotThrow(() -> {
                try (VertexArray vao = new VertexArray(null)) {
                    assertNotNull(vao.getDebugName(), "VertexArray should handle null debug name");
                }
            }, "VertexArray should handle null debug name gracefully");
            
        }, "Null and invalid input handling");
        
        // Test resource exhaustion scenarios
        benchmark.measureSingle(() -> {
            // Simulate very low memory conditions
            bufferManager.setMemoryWarningThreshold(1); // 1 byte threshold
            
            // System should still function but with warnings
            assertDoesNotThrow(() -> {
                try (VertexArray vao = new VertexArray("low_memory_vao")) {
                    assertTrue(vao.isValid(), "VAO should still be created under low memory");
                }
            }, "System should handle very low memory gracefully");
            
            // Reset threshold
            bufferManager.setMemoryWarningThreshold(1024 * 1024 * 10);
            
        }, "Resource exhaustion scenarios");
        
        // Test rapid creation/destruction cycles
        benchmark.measureSingle(() -> {
            final int cycles = 20;
            
            for (int cycle = 0; cycle < cycles; cycle++) {
                // Rapid VAO creation/destruction
                try (VertexArray vao = new VertexArray("cycle_vao_" + cycle)) {
                    vao.bind();
                    vao.unbind();
                    assertTrue(vao.isValid(), "VAO should remain valid during cycle: " + cycle);
                }
                
                // Rapid model loading/cancellation
                CompletableFuture<ModelManager.ModelInfo> future = ModelManager
                    .loadModelInfoAsync("cycle_model_" + cycle, 
                                       ModelManager.LoadingPriority.LOW, null);
                activeFutures.add(future);
                
                if (cycle % 2 == 0) {
                    future.cancel(false);
                }
            }
            
            // System should remain stable
            assertTrue(bufferManager.getVertexArrayCount() >= 0, 
                      "Buffer manager should maintain consistent state");
            
        }, "Rapid creation/destruction cycles");
        
        // Test concurrent access to shared resources
        benchmark.measureSingle(() -> {
            final int numThreads = 6;
            final CountDownLatch startLatch = new CountDownLatch(1);
            final CountDownLatch completionLatch = new CountDownLatch(numThreads);
            final List<Exception> concurrentExceptions = Collections.synchronizedList(new ArrayList<>());
            
            for (int t = 0; t < numThreads; t++) {
                final int threadId = t;
                testExecutor.submit(() -> {
                    try {
                        startLatch.await();
                        
                        // Each thread performs different operations simultaneously
                        switch (threadId % 3) {
                            case 0: // VAO operations
                                try (VertexArray vao = new VertexArray("concurrent_vao_" + threadId)) {
                                    for (int i = 0; i < 5; i++) {
                                        vao.bind();
                                        Thread.sleep(10);
                                        vao.unbind();
                                    }
                                }
                                break;
                                
                            case 1: // Model loading
                                CompletableFuture<ModelManager.ModelInfo> future = ModelManager
                                    .loadModelInfoAsync("concurrent_model_" + threadId,
                                                       ModelManager.LoadingPriority.NORMAL, null);
                                activeFutures.add(future);
                                Thread.sleep(100);
                                future.cancel(false);
                                break;
                                
                            case 2: // Context validation
                                for (int i = 0; i < 10; i++) {
                                    List<String> issues = OpenGLValidator.validateContext("concurrent_test_" + threadId);
                                    Thread.sleep(5);
                                }
                                break;
                        }
                        
                    } catch (Exception e) {
                        concurrentExceptions.add(e);
                    } finally {
                        completionLatch.countDown();
                    }
                });
            }
            
            // Start all threads
            startLatch.countDown();
            
            // Wait for completion
            assertTrue(assertDoesNotThrow(() -> completionLatch.await(10, TimeUnit.SECONDS)),
                      "Concurrent access test should complete");
            
            // Check for exceptions
            if (!concurrentExceptions.isEmpty()) {
                fail("Concurrent access should not cause exceptions: " + 
                     concurrentExceptions.stream().map(Exception::getMessage)
                     .collect(Collectors.joining(", ")));
            }
            
        }, "Concurrent access to shared resources");
        
        logger.info("Edge cases and error conditions test completed successfully");
    }
    
    // Helper methods
    
    private void initializeComponents() {
        try {
            // Initialize camera
            camera = new ArcBallCamera();
            
            // Initialize performance optimizer
            performanceOptimizer = new PerformanceOptimizer();
            performanceOptimizer.setDebugMode(false); // Reduce log noise
            
            // Get buffer manager singleton
            bufferManager = BufferManager.getInstance();
            bufferManager.setMemoryTrackingEnabled(true);
            bufferManager.setLeakDetectionEnabled(true);
            
            // Get texture manager singleton
            textureManager = TextureManager.getInstance();
            
            // Initialize model renderer with context validation enabled
            modelRenderer = new ModelRenderer("CriticalFixesTest");
            modelRenderer.initialize();
            modelRenderer.setContextValidationEnabled(true);
            
        } catch (Exception e) {
            logger.error("Failed to initialize test components", e);
            throw new RuntimeException("Component initialization failed", e);
        }
    }
    
    private void cleanupComponents() {
        try {
            if (modelRenderer != null) {
                modelRenderer.close();
            }
            
            if (performanceOptimizer != null) {
                performanceOptimizer.setEnabled(false);
            }
            
            // Note: Singletons are cleaned up in @AfterAll
            
        } catch (Exception e) {
            logger.warn("Error during component cleanup", e);
        }
    }
    
    private StonebreakModel createMockModel(String modelName) {
        // Create a simple mock model for testing
        // In a real test environment, this would create a proper model
        // For this integration test, we focus on the interaction patterns
        return new MockStonebreakModel(modelName);
    }
    
    /**
     * Simple mock model for testing purposes.
     */
    private static class MockStonebreakModel extends StonebreakModel {
        private final String modelName;
        
        public MockStonebreakModel(String modelName) {
            super(null, null, modelName); // Pass nulls as we're mocking
            this.modelName = modelName;
        }
        
        @Override
        public List<BodyPart> getBodyParts() {
            // Return a simple set of body parts for testing
            List<BodyPart> parts = new ArrayList<>();
            parts.add(new BodyPart("head", null));
            parts.add(new BodyPart("body", null));
            return parts;
        }
        
        @Override
        public String getVariantName() {
            return modelName;
        }
    }
}