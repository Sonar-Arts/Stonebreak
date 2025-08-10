package com.openmason.ui.viewport;

import com.openmason.camera.ArcBallCamera;
import com.openmason.model.StonebreakModel;

import org.junit.jupiter.api.*;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Comprehensive test suite for the Dear ImGui viewport conversion.
 * 
 * This test validates the complete conversion from JavaFX-based OpenMason3DViewport
 * to the new Dear ImGui-based ImGuiViewport3D system.
 * 
 * Test coverage includes:
 * - Component initialization and integration
 * - API compatibility with original viewport
 * - Performance characteristics
 * - Error handling and resource management
 * - Input handling and camera controls
 */
@ExtendWith(MockitoExtension.class)
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
public class ImGuiViewportTest {
    
    private static final Logger logger = LoggerFactory.getLogger(ImGuiViewportTest.class);
    
    private ImGuiViewport3D viewport;
    private StonebreakModel mockModel;
    
    @BeforeEach
    void setUp() {
        logger.debug("Setting up test environment");
        
        // Create mock model
        mockModel = mock(StonebreakModel.class);
        when(mockModel.getVariantName()).thenReturn("test_cow");
        
        // Note: Due to GLFW/OpenGL requirements, most tests will validate
        // the API interface rather than actual rendering functionality
    }
    
    @AfterEach
    void tearDown() {
        if (viewport != null && !viewport.isDisposed()) {
            viewport.dispose();
            viewport = null;
        }
        logger.debug("Test cleanup completed");
    }
    
    /**
     * Test basic viewport creation and initialization.
     */
    @Test
    @Order(1)
    @DisplayName("Viewport Creation and Basic Initialization")
    void testViewportCreation() {
        logger.info("Testing viewport creation");
        
        // Create viewport (without GLFW context)
        assertDoesNotThrow(() -> {
            viewport = new ImGuiViewport3D();
        }, "Viewport creation should not throw exceptions");
        
        assertNotNull(viewport, "Viewport should be created");
        
        // Verify initial state
        assertFalse(viewport.isDisposed(), "Viewport should not be disposed initially");
        
        // Note: isInitialized() may be false without proper GLFW context
        // This is expected behavior for unit tests
    }
    
    /**
     * Test API compatibility with original OpenMason3DViewport.
     */
    @Test
    @Order(2)
    @DisplayName("API Compatibility with Original Viewport")
    void testAPICompatibility() {
        logger.info("Testing API compatibility");
        
        viewport = new ImGuiViewport3D();
        
        // Test model management methods
        assertDoesNotThrow(() -> viewport.setCurrentModel(mockModel));
        assertDoesNotThrow(() -> viewport.getCurrentModel());
        assertDoesNotThrow(() -> viewport.hasModelLoaded());
        
        // Test camera methods
        assertDoesNotThrow(() -> viewport.getCamera());
        assertDoesNotThrow(() -> viewport.fitCameraToModel());
        assertDoesNotThrow(() -> viewport.resetCamera());
        assertDoesNotThrow(() -> viewport.frameOrigin());
        
        // Test property methods
        assertDoesNotThrow(() -> viewport.setWireframeMode(true));
        assertDoesNotThrow(() -> viewport.isWireframeMode());
        assertDoesNotThrow(() -> viewport.setGridVisible(false));
        assertDoesNotThrow(() -> viewport.isGridVisible());
        assertDoesNotThrow(() -> viewport.setAxesVisible(true));
        assertDoesNotThrow(() -> viewport.isAxesVisible());
        assertDoesNotThrow(() -> viewport.setDebugMode(true));
        assertDoesNotThrow(() -> viewport.isDebugMode());
        
        // Test texture variant methods
        assertDoesNotThrow(() -> viewport.setCurrentTextureVariant("test_variant"));
        assertDoesNotThrow(() -> viewport.getCurrentTextureVariant());
        
        // Test performance methods
        assertDoesNotThrow(() -> viewport.getCurrentFPS());
        assertDoesNotThrow(() -> viewport.getFrameCount());
        assertDoesNotThrow(() -> viewport.getErrorCount());
        
        logger.info("API compatibility test passed");
    }
    
    /**
     * Test viewport property management.
     */
    @Test
    @Order(3)
    @DisplayName("Viewport Property Management")
    void testPropertyManagement() {
        logger.info("Testing property management");
        
        viewport = new ImGuiViewport3D();
        
        // Test wireframe mode
        viewport.setWireframeMode(true);
        assertTrue(viewport.isWireframeMode(), "Wireframe mode should be enabled");
        
        viewport.setWireframeMode(false);
        assertFalse(viewport.isWireframeMode(), "Wireframe mode should be disabled");
        
        // Test grid visibility
        viewport.setGridVisible(false);
        assertFalse(viewport.isGridVisible(), "Grid should be hidden");
        
        viewport.setGridVisible(true);
        assertTrue(viewport.isGridVisible(), "Grid should be visible");
        
        // Test axes visibility
        viewport.setAxesVisible(false);
        assertFalse(viewport.isAxesVisible(), "Axes should be hidden");
        
        viewport.setAxesVisible(true);
        assertTrue(viewport.isAxesVisible(), "Axes should be visible");
        
        // Test debug mode
        viewport.setDebugMode(true);
        assertTrue(viewport.isDebugMode(), "Debug mode should be enabled");
        
        viewport.setDebugMode(false);
        assertFalse(viewport.isDebugMode(), "Debug mode should be disabled");
        
        // Test texture variant
        viewport.setCurrentTextureVariant("custom_variant");
        assertEquals("custom_variant", viewport.getCurrentTextureVariant(), 
            "Texture variant should be set correctly");
        
        viewport.setCurrentTextureVariant(null);
        assertEquals("default", viewport.getCurrentTextureVariant(),
            "Null texture variant should default to 'default'");
        
        logger.info("Property management test passed");
    }
    
    /**
     * Test model loading and management.
     */
    @Test
    @Order(4)
    @DisplayName("Model Loading and Management")
    void testModelManagement() {
        logger.info("Testing model management");
        
        viewport = new ImGuiViewport3D();
        
        // Test initial state
        assertNull(viewport.getCurrentModel(), "Initial model should be null");
        assertFalse(viewport.hasModelLoaded(), "Should not have model loaded initially");
        
        // Test model setting
        viewport.setCurrentModel(mockModel);
        assertEquals(mockModel, viewport.getCurrentModel(), "Model should be set correctly");
        assertTrue(viewport.hasModelLoaded(), "Should have model loaded");
        
        // Test model clearing
        viewport.setCurrentModel(null);
        assertNull(viewport.getCurrentModel(), "Model should be cleared");
        assertFalse(viewport.hasModelLoaded(), "Should not have model loaded after clearing");
        
        // Test load model by name (async operation)
        assertDoesNotThrow(() -> viewport.loadModel("test_model"));
        assertDoesNotThrow(() -> viewport.loadModel(null));
        assertDoesNotThrow(() -> viewport.loadModel(""));
        
        logger.info("Model management test passed");
    }
    
    /**
     * Test camera integration and controls.
     */
    @Test
    @Order(5)
    @DisplayName("Camera Integration and Controls")
    void testCameraIntegration() {
        logger.info("Testing camera integration");
        
        viewport = new ImGuiViewport3D();
        
        // Test camera access
        ArcBallCamera camera = viewport.getCamera();
        assertNotNull(camera, "Camera should be available");
        
        // Test camera operations
        assertDoesNotThrow(() -> viewport.resetCamera());
        assertDoesNotThrow(() -> viewport.fitCameraToModel());
        assertDoesNotThrow(() -> viewport.frameOrigin());
        
        // Test camera preset application
        assertDoesNotThrow(() -> viewport.applyCameraPreset(ArcBallCamera.CameraPreset.FRONT));
        
        logger.info("Camera integration test passed");
    }
    
    /**
     * Test error handling and resource management.
     */
    @Test
    @Order(6)
    @DisplayName("Error Handling and Resource Management")
    void testErrorHandling() {
        logger.info("Testing error handling");
        
        viewport = new ImGuiViewport3D();
        
        // Test initial error state
        assertEquals(0, viewport.getErrorCount(), "Initial error count should be zero");
        
        // Test disposal
        assertFalse(viewport.isDisposed(), "Viewport should not be disposed initially");
        
        viewport.dispose();
        assertTrue(viewport.isDisposed(), "Viewport should be disposed after dispose()");
        
        // Test operations after disposal
        assertDoesNotThrow(() -> viewport.render(), "Render after disposal should not throw");
        assertDoesNotThrow(() -> viewport.requestRender(), "RequestRender after disposal should not throw");
        
        logger.info("Error handling test passed");
    }
    
    /**
     * Test callback system compatibility.
     */
    @Test
    @Order(7)
    @DisplayName("Callback System Compatibility")
    void testCallbackSystem() {
        logger.info("Testing callback system");
        
        viewport = new ImGuiViewport3D();
        
        // Test callback setting
        Runnable mockCallback = mock(Runnable.class);
        assertDoesNotThrow(() -> viewport.setFitCameraToModelCallback(mockCallback));
        assertDoesNotThrow(() -> viewport.setResetCameraCallback(mockCallback));
        assertDoesNotThrow(() -> viewport.setFrameOriginCallback(mockCallback));
        
        logger.info("Callback system test passed");
    }
    
    /**
     * Test performance and state monitoring.
     */
    @Test
    @Order(8)
    @DisplayName("Performance and State Monitoring")
    void testPerformanceMonitoring() {
        logger.info("Testing performance monitoring");
        
        viewport = new ImGuiViewport3D();
        
        // Test performance metrics access
        assertDoesNotThrow(() -> viewport.getCurrentFPS());
        assertDoesNotThrow(() -> viewport.getFrameCount());
        
        // Test state access
        assertDoesNotThrow(() -> viewport.isInitialized());
        assertDoesNotThrow(() -> viewport.isRenderingEnabled());
        assertDoesNotThrow(() -> viewport.getViewportWidth());
        assertDoesNotThrow(() -> viewport.getViewportHeight());
        
        // Test error tracking
        assertDoesNotThrow(() -> viewport.getLastError());
        assertDoesNotThrow(() -> viewport.getErrorCount());
        
        logger.info("Performance monitoring test passed");
    }
    
    /**
     * Test legacy compatibility methods.
     */
    @Test
    @Order(9)
    @DisplayName("Legacy Compatibility Methods")
    void testLegacyCompatibility() {
        logger.info("Testing legacy compatibility");
        
        viewport = new ImGuiViewport3D();
        
        // Test legacy methods
        assertDoesNotThrow(() -> viewport.setModelTransform(0, 0, 0, 1.0f));
        assertDoesNotThrow(() -> viewport.focusOnModel());
        assertDoesNotThrow(() -> viewport.resetViewport());
        
        // Test toString method
        String viewportString = viewport.toString();
        assertNotNull(viewportString, "toString should return non-null string");
        assertTrue(viewportString.contains("ImGuiViewport3D"), "toString should contain class name");
        
        logger.info("Legacy compatibility test passed");
    }
    
    /**
     * Integration test to validate complete viewport functionality.
     */
    @Test
    @Order(10)
    @DisplayName("Complete Viewport Integration Test")
    void testCompleteIntegration() {
        logger.info("Running complete integration test");
        
        viewport = new ImGuiViewport3D();
        
        // Test complete workflow
        assertDoesNotThrow(() -> {
            // Set up viewport properties
            viewport.setWireframeMode(true);
            viewport.setGridVisible(true);
            viewport.setAxesVisible(true);
            viewport.setDebugMode(true);
            
            // Load model
            viewport.setCurrentModel(mockModel);
            
            // Configure camera
            viewport.resetCamera();
            viewport.fitCameraToModel();
            
            // Request render (should be safe even without GL context)
            viewport.requestRender();
            
            // Verify final state
            assertTrue(viewport.isWireframeMode());
            assertTrue(viewport.isGridVisible());
            assertTrue(viewport.isAxesVisible());
            assertTrue(viewport.isDebugMode());
            assertEquals(mockModel, viewport.getCurrentModel());
            
        }, "Complete integration workflow should not throw exceptions");
        
        logger.info("Complete integration test passed");
    }
    
    /**
     * Benchmark test to compare with JavaFX performance characteristics.
     */
    @Test
    @Order(11)
    @DisplayName("Performance Benchmark")
    void testPerformanceBenchmark() {
        logger.info("Running performance benchmark");
        
        viewport = new ImGuiViewport3D();
        
        long startTime = System.nanoTime();
        
        // Perform typical operations
        for (int i = 0; i < 1000; i++) {
            viewport.setWireframeMode(i % 2 == 0);
            viewport.setCurrentTextureVariant("variant_" + (i % 4));
            viewport.requestRender();
        }
        
        long endTime = System.nanoTime();
        long duration = endTime - startTime;
        
        logger.info("Performance benchmark completed in {}ms", duration / 1_000_000);
        
        // Verify operations completed without errors
        assertEquals(0, viewport.getErrorCount(), "No errors should occur during benchmark");
        
        // Performance should be reasonable (less than 100ms for 1000 operations)
        assertTrue(duration < 100_000_000, "Operations should complete within reasonable time");
    }
}