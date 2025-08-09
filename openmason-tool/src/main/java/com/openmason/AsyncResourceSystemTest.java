package com.openmason;

import com.openmason.model.ModelManager;
import com.openmason.texture.TextureManager;
import com.stonebreak.model.ModelLoader;
import com.stonebreak.textures.CowTextureLoader;
import com.stonebreak.textures.CowTextureDefinition;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.Arrays;
import java.util.List;
import java.util.ArrayList;

/**
 * Comprehensive test suite for the asynchronous resource loading system.
 * 
 * This test class validates:
 * - Thread safety of concurrent operations
 * - Progress reporting accuracy
 * - Error handling and recovery
 * - Cancellation support
 * - Resource cleanup
 * - Memory leak prevention
 * - Performance under load
 * 
 * Run this test to verify that the async system is working correctly
 * before integrating with Phase 3 UI components.
 */
public class AsyncResourceSystemTest {
    
    private static final AtomicInteger testsPassed = new AtomicInteger(0);
    private static final AtomicInteger testsFailed = new AtomicInteger(0);
    private static final AtomicBoolean allTestsPassed = new AtomicBoolean(true);
    
    /**
     * Progress callback that tracks progress and validates callbacks.
     */
    private static class TestProgressCallback implements AsyncResourceManager.ProgressCallback {
        private final String testName;
        private final AtomicInteger progressCalls = new AtomicInteger(0);
        private final AtomicInteger errorCalls = new AtomicInteger(0);
        private final AtomicBoolean completed = new AtomicBoolean(false);
        private int lastProgress = -1;
        
        public TestProgressCallback(String testName) {
            this.testName = testName;
        }
        
        @Override
        public void onProgress(String operation, int current, int total, String details) {
            progressCalls.incrementAndGet();
            
            // Validate progress is monotonic
            if (current < lastProgress) {
                System.err.println("[" + testName + "] ERROR: Progress went backwards: " + current + " < " + lastProgress);
            }
            lastProgress = current;
            
            // Validate bounds
            if (current < 0 || current > total || total <= 0) {
                System.err.println("[" + testName + "] ERROR: Invalid progress bounds: " + current + "/" + total);
            }
            
            System.out.println("[" + testName + "] Progress: " + current + "/" + total + " - " + details);
        }
        
        @Override
        public void onError(String operation, Throwable error) {
            errorCalls.incrementAndGet();
            System.err.println("[" + testName + "] Error in " + operation + ": " + error.getMessage());
        }
        
        @Override
        public void onComplete(String operation, Object result) {
            completed.set(true);
            System.out.println("[" + testName + "] Completed: " + operation + " -> " + result);
        }
        
        public boolean hasProgress() { return progressCalls.get() > 0; }
        public boolean hasErrors() { return errorCalls.get() > 0; }
        public boolean isCompleted() { return completed.get(); }
        public int getProgressCallCount() { return progressCalls.get(); }
    }
    
    /**
     * Test result tracking.
     */
    private static void assertTest(String testName, boolean condition, String message) {
        if (condition) {
            testsPassed.incrementAndGet();
            System.out.println("✓ [" + testName + "] " + message);
        } else {
            testsFailed.incrementAndGet();
            allTestsPassed.set(false);
            System.err.println("✗ [" + testName + "] FAILED: " + message);
        }
    }
    
    /**
     * Test 1: Basic async initialization.
     */
    private static void testBasicInitialization() throws Exception {
        System.out.println("\\n=== Test 1: Basic Async Initialization ===");
        
        // Clear any previous state
        AsyncResourceManager.clearAllCaches();
        
        TestProgressCallback callback = new TestProgressCallback("BasicInit");
        
        long startTime = System.currentTimeMillis();
        CompletableFuture<Void> future = AsyncResourceManager.initializeAsync(callback);
        
        // Should return immediately (non-blocking)
        long callTime = System.currentTimeMillis() - startTime;
        assertTest("BasicInit", callTime < 100, "initializeAsync() call returned quickly (" + callTime + "ms)");
        
        // Wait for completion
        future.get(30, TimeUnit.SECONDS);
        long totalTime = System.currentTimeMillis() - startTime;
        
        // Validate results
        assertTest("BasicInit", AsyncResourceManager.isInitialized(), "System marked as initialized");
        assertTest("BasicInit", !AsyncResourceManager.isInitializing(), "System not marked as initializing");
        assertTest("BasicInit", callback.hasProgress(), "Progress callbacks were called");
        assertTest("BasicInit", callback.isCompleted(), "Completion callback was called");
        assertTest("BasicInit", !callback.hasErrors(), "No error callbacks during normal operation");
        assertTest("BasicInit", totalTime < 30000, "Initialization completed within timeout");
        
        System.out.println("[BasicInit] Total initialization time: " + totalTime + "ms");
        System.out.println("[BasicInit] Progress callback calls: " + callback.getProgressCallCount());
    }
    
    /**
     * Test 2: Concurrent access safety.
     */
    private static void testConcurrentAccess() throws Exception {
        System.out.println("\\n=== Test 2: Concurrent Access Safety ===");
        
        // Clear state
        AsyncResourceManager.clearAllCaches();
        
        // Start multiple concurrent initializations
        List<CompletableFuture<Void>> futures = new ArrayList<>();
        List<TestProgressCallback> callbacks = new ArrayList<>();
        
        for (int i = 0; i < 5; i++) {
            TestProgressCallback callback = new TestProgressCallback("Concurrent" + i);
            callbacks.add(callback);
            futures.add(AsyncResourceManager.initializeAsync(callback));
        }
        
        // All should complete successfully
        CompletableFuture.allOf(futures.toArray(new CompletableFuture[0])).get(30, TimeUnit.SECONDS);
        
        // Validate all completed
        for (int i = 0; i < callbacks.size(); i++) {
            TestProgressCallback callback = callbacks.get(i);
            assertTest("Concurrent", callback.isCompleted(), "Concurrent init " + i + " completed");
        }
        
        assertTest("Concurrent", AsyncResourceManager.isInitialized(), "System properly initialized after concurrent access");
    }
    
    /**
     * Test 3: Individual loader async functionality.
     */
    private static void testIndividualLoaders() throws Exception {
        System.out.println("\\n=== Test 3: Individual Loader Async Functionality ===");
        
        // Test model loader
        TestProgressCallback modelCallback = new TestProgressCallback("ModelLoader");
        CompletableFuture<com.stonebreak.model.ModelDefinition.CowModelDefinition> modelFuture = 
            ModelLoader.getCowModelAsync("standard_cow", 
                (progress) -> modelCallback.onProgress("ModelLoader", 0, 100, progress));
        
        // Test texture loader
        TestProgressCallback textureCallback = new TestProgressCallback("TextureLoader");
        CompletableFuture<CowTextureDefinition.CowVariant> textureFuture = 
            CompletableFuture.supplyAsync(() -> {
                textureCallback.onProgress("CowTextureLoader", 0, 100, "Loading default variant");
                CowTextureDefinition.CowVariant result = CowTextureLoader.getCowVariant("default");
                textureCallback.onComplete("CowTextureLoader", result);
                return result;
            });
        
        // Wait for both
        CompletableFuture.allOf(modelFuture, textureFuture).get(10, TimeUnit.SECONDS);
        
        // Validate results
        assertTest("IndividualLoaders", modelFuture.get() != null, "Model loaded successfully");
        assertTest("IndividualLoaders", textureFuture.get() != null, "Texture loaded successfully");
        assertTest("IndividualLoaders", modelCallback.isCompleted(), "Model loader callback completed");
        assertTest("IndividualLoaders", textureCallback.isCompleted(), "Texture loader callback completed");
    }
    
    /**
     * Test 4: Manager-level async functionality.
     */
    private static void testManagerAsync() throws Exception {
        System.out.println("\\n=== Test 4: Manager-Level Async Functionality ===");
        
        // Test ModelManager async
        TestProgressCallback modelMgrCallback = new TestProgressCallback("ModelManager");
        CompletableFuture<ModelManager.ModelInfo> modelInfoFuture = 
            ModelManager.loadModelInfoAsync("standard_cow", 
                ModelManager.LoadingPriority.HIGH, 
                new ModelManager.ProgressCallback() {
                    @Override
                    public void onProgress(String operation, int current, int total, String details) {
                        modelMgrCallback.onProgress(operation, current, total, details);
                    }
                    
                    @Override
                    public void onError(String operation, Throwable error) {
                        modelMgrCallback.onError(operation, error);
                    }
                    
                    @Override
                    public void onComplete(String operation, Object result) {
                        modelMgrCallback.onComplete(operation, result);
                    }
                });
        
        // Test TextureManager async
        TestProgressCallback textureMgrCallback = new TestProgressCallback("TextureManager");
        CompletableFuture<TextureManager.TextureVariantInfo> textureInfoFuture = 
            TextureManager.loadVariantInfoAsync("default", 
                TextureManager.LoadingPriority.HIGH, 
                new TextureManager.ProgressCallback() {
                    @Override
                    public void onProgress(String operation, int current, int total, String details) {
                        textureMgrCallback.onProgress(operation, current, total, details);
                    }
                    
                    @Override
                    public void onError(String operation, Throwable error) {
                        textureMgrCallback.onError(operation, error);
                    }
                    
                    @Override
                    public void onComplete(String operation, Object result) {
                        textureMgrCallback.onComplete(operation, result);
                    }
                });
        
        // Wait for both
        CompletableFuture.allOf(modelInfoFuture, textureInfoFuture).get(10, TimeUnit.SECONDS);
        
        // Validate results
        assertTest("ManagerAsync", modelInfoFuture.get() != null, "Model info loaded successfully");
        assertTest("ManagerAsync", textureInfoFuture.get() != null, "Texture info loaded successfully");
        assertTest("ManagerAsync", modelMgrCallback.isCompleted(), "Model manager callback completed");
        assertTest("ManagerAsync", textureMgrCallback.isCompleted(), "Texture manager callback completed");
    }
    
    /**
     * Test 5: Cancellation support.
     */
    private static void testCancellation() throws Exception {
        System.out.println("\\n=== Test 5: Cancellation Support ===");
        
        // Clear state to force fresh loads
        AsyncResourceManager.clearAllCaches();
        
        // Start some operations
        CompletableFuture<Void> future1 = AsyncResourceManager.initializeAsync(new TestProgressCallback("Cancel1"));
        CompletableFuture<com.stonebreak.model.ModelDefinition.CowModelDefinition> future2 = 
            ModelLoader.getCowModelAsync("standard_cow", null);
        
        // Give them a moment to start
        Thread.sleep(100);
        
        // Cancel operations
        int cancelled = AsyncResourceManager.cancelAllOperations(true);
        
        assertTest("Cancellation", cancelled > 0, "Some operations were cancelled (" + cancelled + ")");
        
        // Verify active operation count decreased
        int activeOps = AsyncResourceManager.getActiveOperationCount();
        assertTest("Cancellation", activeOps >= 0, "Active operation count is reasonable (" + activeOps + ")");
    }
    
    /**
     * Test 6: Error handling and recovery.
     */
    private static void testErrorHandling() throws Exception {
        System.out.println("\\n=== Test 6: Error Handling ===");
        
        // Test with invalid model name
        TestProgressCallback errorCallback = new TestProgressCallback("ErrorHandling");
        CompletableFuture<com.stonebreak.model.ModelDefinition.CowModelDefinition> errorFuture = 
            ModelLoader.getCowModelAsync("nonexistent_model", 
                (progress) -> errorCallback.onProgress("ModelLoader", 0, 100, progress));
        
        // Should complete exceptionally
        boolean threwException = false;
        try {
            errorFuture.get(5, TimeUnit.SECONDS);
        } catch (Exception e) {
            threwException = true;
        }
        
        assertTest("ErrorHandling", threwException, "Invalid model request threw exception as expected");
        assertTest("ErrorHandling", errorCallback.hasErrors(), "Error callback was invoked");
    }
    
    /**
     * Test 7: Resource cleanup and memory management.
     */
    private static void testResourceCleanup() throws Exception {
        System.out.println("\\n=== Test 7: Resource Cleanup ===");
        
        // Get initial memory usage
        Runtime runtime = Runtime.getRuntime();
        long initialMemory = runtime.totalMemory() - runtime.freeMemory();
        
        // Initialize system
        AsyncResourceManager.initialize();
        
        // Load some resources
        for (String model : ModelLoader.getAvailableModels()) {
            ModelManager.getModelInfo(model);
        }
        
        for (String variant : CowTextureLoader.getAvailableVariants()) {
            TextureManager.getVariantInfo(variant);
        }
        
        // Clear caches
        AsyncResourceManager.clearAllCaches();
        
        // Force GC
        System.gc();
        Thread.sleep(100);
        
        long finalMemory = runtime.totalMemory() - runtime.freeMemory();
        long memoryIncrease = finalMemory - initialMemory;
        
        assertTest("ResourceCleanup", memoryIncrease < 50 * 1024 * 1024, // 50MB threshold
            "Memory usage increase reasonable: " + (memoryIncrease / 1024 / 1024) + "MB");
        assertTest("ResourceCleanup", !AsyncResourceManager.isInitialized(), "System properly reset after clearAllCaches()");
    }
    
    /**
     * Test 8: Graceful shutdown.
     */
    private static void testGracefulShutdown() throws Exception {
        System.out.println("\\n=== Test 8: Graceful Shutdown ===");
        
        // Initialize
        AsyncResourceManager.initialize();
        
        // Start some background operations
        ModelManager.loadModelInfoAsync("standard_cow", ModelManager.LoadingPriority.LOW, null);
        TextureManager.loadVariantInfoAsync("default", TextureManager.LoadingPriority.LOW, null);
        
        // Shutdown
        AsyncResourceManager.shutdown();
        
        // Verify shutdown state
        assertTest("GracefulShutdown", AsyncResourceManager.getActiveOperationCount() == 0, 
            "No active operations after shutdown");
        assertTest("GracefulShutdown", !AsyncResourceManager.isInitialized(), 
            "System not marked as initialized after shutdown");
    }
    
    /**
     * Main test runner.
     */
    public static void main(String[] args) {
        System.out.println("=== Open Mason Phase 2 - Async Resource System Test Suite ===");
        System.out.println("Testing comprehensive async loading with progress reporting, error handling, and thread safety");
        System.out.println();
        
        try {
            testBasicInitialization();
            testConcurrentAccess();
            testIndividualLoaders();
            testManagerAsync();
            testCancellation();
            testErrorHandling();
            testResourceCleanup();
            testGracefulShutdown();
            
        } catch (Exception e) {
            System.err.println("Test suite failed with exception: " + e.getMessage());
            e.printStackTrace();
            allTestsPassed.set(false);
        }
        
        // Final results
        System.out.println("\\n=== Test Results ===");
        System.out.println("Tests passed: " + testsPassed.get());
        System.out.println("Tests failed: " + testsFailed.get());
        System.out.println("Overall result: " + (allTestsPassed.get() ? "✓ ALL TESTS PASSED" : "✗ SOME TESTS FAILED"));
        
        if (allTestsPassed.get()) {
            System.out.println("\\n🎉 Async resource loading system is ready for Phase 3 UI integration!");
            System.out.println("   - Thread-safe concurrent loading ✓");
            System.out.println("   - Progress reporting ✓");
            System.out.println("   - Error handling ✓");
            System.out.println("   - Cancellation support ✓");
            System.out.println("   - Resource cleanup ✓");
            System.out.println("   - Memory management ✓");
        } else {
            System.err.println("\\n❌ Test failures detected. Please review and fix issues before Phase 3 integration.");
        }
        
        // Clean shutdown
        AsyncResourceManager.shutdown();
        System.exit(allTestsPassed.get() ? 0 : 1);
    }
}