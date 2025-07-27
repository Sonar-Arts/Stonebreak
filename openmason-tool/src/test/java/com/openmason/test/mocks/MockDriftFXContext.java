package com.openmason.test.mocks;

import de.mihosoft.driftfx.RenderContext;
import org.lwjgl.opengl.GL;
import org.lwjgl.opengl.GLCapabilities;
import org.mockito.Mockito;

/**
 * Mock DriftFX RenderContext for headless testing.
 * 
 * Provides a simulated DriftFX rendering environment that allows testing
 * of OpenMason's 3D viewport functionality without requiring actual hardware
 * acceleration or GUI environment.
 */
public class MockDriftFXContext {
    
    private final RenderContext mockContext;
    private boolean glContextInitialized = false;
    
    public MockDriftFXContext() {
        this.mockContext = Mockito.mock(RenderContext.class);
        setupMockBehavior();
    }
    
    /**
     * Sets up mock behavior for the DriftFX context.
     */
    private void setupMockBehavior() {
        // Mock basic context operations
        Mockito.doAnswer(invocation -> {
            initializeGLContext();
            return null;
        }).when(mockContext).makeCurrent();
        
        Mockito.doAnswer(invocation -> {
            // Simulate context release
            return null;
        }).when(mockContext).releaseContext();
    }
    
    /**
     * Simulates OpenGL context initialization for testing.
     */
    private void initializeGLContext() {
        if (!glContextInitialized) {
            try {
                // Create mock GL capabilities for testing
                GLCapabilities capabilities = Mockito.mock(GLCapabilities.class);
                
                // Set up basic OpenGL state simulation
                Mockito.when(capabilities.OpenGL11).thenReturn(true);
                Mockito.when(capabilities.OpenGL20).thenReturn(true);
                Mockito.when(capabilities.OpenGL30).thenReturn(true);
                
                glContextInitialized = true;
            } catch (Exception e) {
                // In headless testing, OpenGL initialization may fail
                // This is expected and handled gracefully
            }
        }
    }
    
    /**
     * Gets the mocked RenderContext.
     * 
     * @return Mocked DriftFX RenderContext
     */
    public RenderContext getMockContext() {
        return mockContext;
    }
    
    /**
     * Checks if the GL context has been initialized.
     * 
     * @return True if GL context is initialized
     */
    public boolean isGLContextInitialized() {
        return glContextInitialized;
    }
    
    /**
     * Simulates a rendering frame.
     */
    public void simulateFrame() {
        // Simulate frame timing
        try {
            Thread.sleep(16); // Simulate 60 FPS frame time
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
    
    /**
     * Resets the mock context to initial state.
     */
    public void reset() {
        Mockito.reset(mockContext);
        glContextInitialized = false;
        setupMockBehavior();
    }
}