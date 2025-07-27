package com.openmason.test;

import com.openmason.camera.ArcBallCamera;
import com.openmason.rendering.BufferManager;
import com.openmason.rendering.ModelRenderer;
import com.openmason.rendering.PerformanceOptimizer;
import com.openmason.model.ModelManager;
import com.openmason.model.StonebreakModel;
import com.openmason.texture.TextureManager;
import com.openmason.ui.viewport.OpenMason3DViewport;
import com.openmason.test.mocks.MockDriftFXContext;
import com.openmason.test.mocks.MockOpenGLContext;
import com.openmason.test.performance.PerformanceBenchmark;
import com.openmason.test.performance.FrameRateBenchmarkResult;
import com.openmason.test.performance.PerformanceValidationResult;

import org.joml.Matrix4f;
import org.joml.Vector3f;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.junit.jupiter.MockitoExtension;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import static org.junit.jupiter.api.Assertions.*;
import static org.awaitility.Awaitility.*;
import static java.time.Duration.ofSeconds;

/**
 * Comprehensive integration test for Phase 3 OpenMason components.
 * 
 * Tests the complete system integration including:
 * - DriftFX viewport functionality
 * - ArcBall camera system integration
 * - Performance monitoring and optimization
 * - Model rendering pipeline
 * - Error handling and graceful degradation
 * 
 * This test validates that all Phase 3 systems work together correctly
 * and meet professional-grade performance requirements.
 */
@ExtendWith(MockitoExtension.class)
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
public class Phase3IntegrationTest {
    
    private static final Logger logger = LoggerFactory.getLogger(Phase3IntegrationTest.class);
    
    // Test infrastructure
    private MockDriftFXContext mockDriftFXContext;
    private MockOpenGLContext mockOpenGLContext;
    private PerformanceBenchmark benchmark;
    
    // Core Phase 3 components
    private ArcBallCamera camera;
    private PerformanceOptimizer performanceOptimizer;
    private BufferManager bufferManager;
    private ModelRenderer modelRenderer;
    private ModelManager modelManager;
    private TextureManager textureManager;
    
    // Test models and data
    private StonebreakModel testModel;
    
    @BeforeAll
    void setUpSuite() {
        logger.info("Setting up Phase 3 Integration Test Suite");
        
        // Initialize test infrastructure
        mockDriftFXContext = new MockDriftFXContext();
        mockOpenGLContext = new MockOpenGLContext();
        benchmark = new PerformanceBenchmark("Phase3Integration");
        
        // Configure system properties for headless testing
        System.setProperty("java.awt.headless", "true");
        System.setProperty("testfx.headless", "true");
        System.setProperty("performance.test.mode", "true");
        
        logger.info("Phase 3 Integration Test Suite initialized");
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
        
        logger.debug("Test setup completed");
    }
    
    @AfterEach
    void tearDown() {
        logger.debug("Tearing down test");
        
        // Cleanup components
        cleanupComponents();
        
        logger.debug("Test teardown completed");
    }
    
    @AfterAll
    void tearDownSuite() {
        logger.info("Tearing down Phase 3 Integration Test Suite");
        
        // Final cleanup
        if (bufferManager != null) {
            bufferManager.cleanup();
        }
        
        logger.info("Phase 3 Integration Test Suite completed");
    }
    
    /**
     * Tests the complete system initialization sequence.
     */
    @Test
    @DisplayName("Phase 3 System Initialization")
    void testSystemInitialization() {
        logger.info("Testing Phase 3 system initialization");
        
        // Test component initialization timing
        benchmark.measureSingle(() -> {
            // All components should initialize successfully
            assertNotNull(camera, "ArcBall camera should be initialized");
            assertNotNull(performanceOptimizer, "Performance optimizer should be initialized");
            assertNotNull(bufferManager, "Buffer manager should be initialized");
            assertNotNull(modelRenderer, "Model renderer should be initialized");
            assertNotNull(modelManager, "Model manager should be initialized");
            assertNotNull(textureManager, "Texture manager should be initialized");
        }, "Complete system initialization");
        
        // Verify component states
        assertTrue(performanceOptimizer.isEnabled(), "Performance optimizer should be enabled");
        assertFalse(camera.isAnimating(), "Camera should not be animating initially");
        
        // Test integration points
        assertDoesNotThrow(() -> {
            performanceOptimizer.beginFrame();
            performanceOptimizer.endFrame();
        }, "Performance monitoring integration should work");
        
        logger.info("System initialization test completed successfully");
    }
    
    /**
     * Tests ArcBall camera integration with viewport system.
     */
    @Test
    @DisplayName("ArcBall Camera Integration")
    void testArcBallCameraIntegration() {
        logger.info("Testing ArcBall camera integration");
        
        // Test camera matrix generation performance
        benchmark.measureSingle(() -> {
            Matrix4f viewMatrix = camera.getViewMatrix();
            Matrix4f projMatrix = camera.getProjectionMatrix(800, 600, 0.1f, 1000.0f);
            
            assertNotNull(viewMatrix, "View matrix should be generated");
            assertNotNull(projMatrix, "Projection matrix should be generated");
        }, "Camera matrix generation");
        
        // Test camera movement integration
        benchmark.measureSingle(() -> {
            // Test rotation
            camera.rotate(45.0f, 30.0f);
            camera.update(0.016f); // One frame update
            
            // Test zoom
            camera.zoom(1.0f);
            camera.update(0.016f);
            
            // Test pan
            camera.pan(50.0f, 50.0f);
            camera.update(0.016f);
            
            // Verify camera state consistency
            assertTrue(camera.getDistance() > 0, "Camera distance should be positive");
            assertNotNull(camera.getCameraPosition(), "Camera position should be available");
            assertNotNull(camera.getTarget(), "Camera target should be available");
        }, "Camera movement operations");
        
        // Test camera presets
        for (ArcBallCamera.CameraPreset preset : ArcBallCamera.CameraPreset.values()) {
            benchmark.measureSingle(() -> {
                camera.applyPreset(preset);
                camera.update(1.0f); // Complete interpolation
                
                // Verify preset was applied
                assertEquals(preset.azimuth, camera.getAzimuth(), 0.1f, 
                           "Preset azimuth should be applied: " + preset.displayName);
                assertEquals(preset.elevation, camera.getElevation(), 0.1f, 
                           "Preset elevation should be applied: " + preset.displayName);
            }, "Camera preset: " + preset.displayName);
        }
        
        logger.info("ArcBall camera integration test completed successfully");
    }
    
    /**
     * Tests performance monitoring and adaptive quality system.
     */
    @Test
    @DisplayName("Performance Monitoring Integration")
    void testPerformanceMonitoringIntegration() {
        logger.info("Testing performance monitoring integration");
        
        // Test performance measurement accuracy
        FrameRateBenchmarkResult frameRateResult = benchmark.measureFrameRate(() -> {
            performanceOptimizer.beginFrame();
            
            // Simulate rendering workload
            mockOpenGLContext.beginFrame();
            try {
                Thread.sleep(10); // Simulate 10ms render time
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            mockOpenGLContext.endFrame();
            
            performanceOptimizer.endFrame();
        }, 2.0, "Performance monitoring accuracy");
        
        // Validate performance measurements
        assertTrue(frameRateResult.getAverageFPS() > 30.0, 
                  "Measured FPS should be reasonable: " + frameRateResult.getAverageFPS());
        assertTrue(frameRateResult.getAverageFrameTime() > 5.0, 
                  "Frame time should reflect simulated workload");
        
        // Test adaptive quality functionality
        benchmark.measureSingle(() -> {
            // Test manual quality settings
            performanceOptimizer.setMSAALevel(2);
            performanceOptimizer.setRenderScale(0.75f);
            
            assertEquals(2, performanceOptimizer.getCurrentMSAALevel(), 
                        "MSAA level should be settable");
            assertEquals(0.75f, performanceOptimizer.getCurrentRenderScale(), 0.01f, 
                        "Render scale should be settable");
            
            // Re-enable adaptive quality
            performanceOptimizer.setAdaptiveQualityEnabled(true);
            assertTrue(performanceOptimizer.isAdaptiveQualityEnabled(), 
                      "Adaptive quality should be re-enableable");
        }, "Adaptive quality controls");
        
        // Test performance statistics
        PerformanceOptimizer.PerformanceStatistics stats = performanceOptimizer.getStatistics();
        assertNotNull(stats, "Performance statistics should be available");
        assertTrue(stats.totalFrames > 0, "Frame count should be tracked");
        
        logger.info("Performance monitoring integration test completed successfully");
    }
    
    /**\n     * Tests model rendering integration with camera and performance systems.\n     */\n    @Test\n    @DisplayName(\"Model Rendering Integration\")\n    void testModelRenderingIntegration() {\n        logger.info(\"Testing model rendering integration\");\n        \n        // Test model loading performance\n        benchmark.measureSingle(() -> {\n            // Note: In a real test, we would load an actual model\n            // For this integration test, we verify the rendering pipeline\n            \n            if (testModel != null) {\n                // Test model preparation\n                if (!modelRenderer.isModelPrepared(testModel)) {\n                    modelRenderer.prepareModel(testModel);\n                }\n                \n                assertTrue(modelRenderer.isModelPrepared(testModel), \n                          \"Model should be prepared for rendering\");\n            }\n        }, \"Model preparation\");\n        \n        // Test rendering pipeline integration\n        benchmark.measureSingle(() -> {\n            performanceOptimizer.beginFrame();\n            \n            // Get camera matrices\n            Matrix4f viewMatrix = camera.getViewMatrix();\n            Matrix4f projMatrix = camera.getProjectionMatrix(800, 600, 0.1f, 1000.0f);\n            \n            // Simulate rendering operations\n            mockOpenGLContext.glViewport(0, 0, 800, 600);\n            mockOpenGLContext.glClearColor(0.2f, 0.2f, 0.2f, 1.0f);\n            mockOpenGLContext.glClear(0x00004000 | 0x00000100); // GL_COLOR_BUFFER_BIT | GL_DEPTH_BUFFER_BIT\n            \n            // Test texture variant switching (if model available)\n            if (testModel != null) {\n                String[] variants = {\"default\", \"angus\", \"highland\", \"jersey\"};\n                for (String variant : variants) {\n                    // This would normally render with different texture variants\n                    // For integration test, we verify the call doesn't fail\n                    assertDoesNotThrow(() -> {\n                        modelRenderer.renderModel(testModel, variant);\n                    }, \"Texture variant switching should not fail: \" + variant);\n                }\n            }\n            \n            performanceOptimizer.endFrame();\n        }, \"Complete rendering pipeline\");\n        \n        // Test camera auto-fit functionality\n        benchmark.measureSingle(() -> {\n            Vector3f min = new Vector3f(-1, -1, -1);\n            Vector3f max = new Vector3f(1, 1, 1);\n            \n            camera.frameObject(min, max);\n            camera.update(1.0f); // Complete animation\n            \n            // Verify camera adjusted to frame object\n            Vector3f expectedCenter = new Vector3f(0, 0, 0);\n            Vector3f actualTarget = camera.getTarget();\n            \n            assertEquals(expectedCenter.x, actualTarget.x, 0.1f, \"Camera should center on object\");\n            assertEquals(expectedCenter.y, actualTarget.y, 0.1f, \"Camera should center on object\");\n            assertEquals(expectedCenter.z, actualTarget.z, 0.1f, \"Camera should center on object\");\n        }, \"Camera auto-fit\");\n        \n        logger.info(\"Model rendering integration test completed successfully\");\n    }\n    \n    /**\n     * Tests error handling and graceful degradation across all systems.\n     */\n    @Test\n    @DisplayName(\"Error Handling Integration\")\n    void testErrorHandlingIntegration() {\n        logger.info(\"Testing error handling integration\");\n        \n        // Test OpenGL error handling\n        benchmark.measureSingle(() -> {\n            // Simulate OpenGL error\n            mockOpenGLContext.simulateError(MockOpenGLContext.GL_INVALID_OPERATION);\n            \n            // System should continue functioning\n            performanceOptimizer.beginFrame();\n            performanceOptimizer.endFrame();\n            \n            // Performance monitoring should continue\n            PerformanceOptimizer.PerformanceStatistics stats = performanceOptimizer.getStatistics();\n            assertNotNull(stats, \"Performance statistics should remain available after GL error\");\n        }, \"OpenGL error handling\");\n        \n        // Test memory pressure handling\n        benchmark.measureSingle(() -> {\n            // Configure buffer manager for aggressive memory limits\n            bufferManager.setMemoryWarningThreshold(1024); // Very low threshold\n            \n            // System should handle memory pressure gracefully\n            assertDoesNotThrow(() -> {\n                for (int i = 0; i < 10; i++) {\n                    performanceOptimizer.beginFrame();\n                    performanceOptimizer.endFrame();\n                }\n            }, \"System should handle memory pressure\");\n        }, \"Memory pressure handling\");\n        \n        // Test camera constraint enforcement\n        benchmark.measureSingle(() -> {\n            // Test extreme camera values\n            camera.setDistance(-1.0f); // Invalid distance\n            assertTrue(camera.getDistance() >= 0.1f, \"Camera should enforce minimum distance\");\n            \n            camera.setDistance(1000.0f); // Excessive distance\n            assertTrue(camera.getDistance() <= 100.0f, \"Camera should enforce maximum distance\");\n            \n            // Test extreme elevation\n            camera.setOrientation(0, 100.0f); // Above maximum\n            assertTrue(camera.getElevation() <= 89.0f, \"Camera should enforce maximum elevation\");\n            \n            camera.setOrientation(0, -100.0f); // Below minimum\n            assertTrue(camera.getElevation() >= -89.0f, \"Camera should enforce minimum elevation\");\n        }, \"Camera constraint enforcement\");\n        \n        logger.info(\"Error handling integration test completed successfully\");\n    }\n    \n    /**\n     * Tests performance under continuous operation.\n     */\n    @Test\n    @DisplayName(\"Continuous Operation Performance\")\n    void testContinuousOperationPerformance() {\n        logger.info(\"Testing continuous operation performance\");\n        \n        // Test extended operation\n        FrameRateBenchmarkResult extendedResult = benchmark.measureFrameRate(() -> {\n            performanceOptimizer.beginFrame();\n            \n            // Simulate realistic frame operations\n            camera.update(0.016f);\n            \n            mockOpenGLContext.beginFrame();\n            mockOpenGLContext.glViewport(0, 0, 800, 600);\n            mockOpenGLContext.glClear(0x00004100); // GL_COLOR_BUFFER_BIT | GL_DEPTH_BUFFER_BIT\n            \n            // Simulate some camera movement\n            if (Math.random() < 0.1) { // 10% chance of camera movement\n                camera.rotate((float) (Math.random() * 10 - 5), (float) (Math.random() * 10 - 5));\n            }\n            \n            mockOpenGLContext.endFrame();\n            performanceOptimizer.endFrame();\n        }, 5.0, \"Extended operation test (5 seconds)\");\n        \n        // Validate performance requirements\n        PerformanceValidationResult validation = benchmark.validatePerformance(extendedResult);\n        \n        assertTrue(validation.isAcceptable(), \n                  \"Performance should be acceptable: \" + validation);\n        \n        if (validation.hasWarnings()) {\n            logger.warn(\"Performance warnings detected: {}\", \n                       String.join(\", \", validation.getWarnings()));\n        }\n        \n        if (validation.hasIssues()) {\n            fail(\"Performance issues detected: \" + String.join(\", \", validation.getIssues()));\n        }\n        \n        logger.info(\"Continuous operation performance test completed: {}\", extendedResult);\n    }\n    \n    /**\n     * Tests memory usage and leak detection.\n     */\n    @Test\n    @DisplayName(\"Memory Management Integration\")\n    void testMemoryManagementIntegration() {\n        logger.info(\"Testing memory management integration\");\n        \n        // Measure memory usage during typical operations\n        var memoryResult = benchmark.measureMemory(() -> {\n            performanceOptimizer.beginFrame();\n            \n            // Perform typical frame operations\n            camera.update(0.016f);\n            Matrix4f viewMatrix = camera.getViewMatrix();\n            Matrix4f projMatrix = camera.getProjectionMatrix(800, 600, 0.1f, 1000.0f);\n            \n            // Simulate buffer operations\n            mockOpenGLContext.glViewport(0, 0, 800, 600);\n            \n            performanceOptimizer.endFrame();\n        }, 1000, \"Typical frame operations (1000 iterations)\");\n        \n        // Validate memory usage\n        assertTrue(memoryResult.getAllocationRate() < 100000, // 100KB per ms\n                  \"Memory allocation rate should be reasonable: \" + memoryResult.getAllocationRate());\n        \n        // Test buffer manager memory tracking\n        if (bufferManager.getCurrentMemoryUsage() > 0) {\n            assertTrue(bufferManager.getCurrentMemoryUsage() < 1024 * 1024 * 100, // 100MB\n                      \"Buffer manager memory usage should be reasonable\");\n        }\n        \n        logger.info(\"Memory management integration test completed: {}\", memoryResult);\n    }\n    \n    // Private helper methods\n    \n    private void initializeComponents() {\n        try {\n            // Initialize ArcBall camera\n            camera = new ArcBallCamera();\n            \n            // Initialize performance optimizer\n            performanceOptimizer = new PerformanceOptimizer();\n            performanceOptimizer.setDebugPrefix(\"Phase3IntegrationTest\");\n            performanceOptimizer.setDebugMode(false); // Reduce log noise during testing\n            \n            // Get singleton instances\n            bufferManager = BufferManager.getInstance();\n            bufferManager.setMemoryTrackingEnabled(true);\n            bufferManager.setLeakDetectionEnabled(true);\n            \n            modelManager = ModelManager.getInstance();\n            textureManager = TextureManager.getInstance();\n            \n            // Initialize model renderer\n            modelRenderer = new ModelRenderer(\"Phase3IntegrationTest\");\n            modelRenderer.initialize();\n            \n            // Try to load a test model (may not be available in all test environments)\n            try {\n                testModel = modelManager.loadModel(\"standard_cow\", \"cow\");\n            } catch (Exception e) {\n                logger.debug(\"Test model not available, proceeding without: {}\", e.getMessage());\n            }\n            \n        } catch (Exception e) {\n            logger.error(\"Failed to initialize components\", e);\n            throw new RuntimeException(\"Component initialization failed\", e);\n        }\n    }\n    \n    private void cleanupComponents() {\n        try {\n            if (modelRenderer != null) {\n                modelRenderer.close();\n            }\n            \n            if (performanceOptimizer != null) {\n                performanceOptimizer.setEnabled(false);\n            }\n            \n            // Note: Singleton cleanup is handled by shutdown hooks\n            \n        } catch (Exception e) {\n            logger.warn(\"Error during component cleanup\", e);\n        }\n    }\n}"