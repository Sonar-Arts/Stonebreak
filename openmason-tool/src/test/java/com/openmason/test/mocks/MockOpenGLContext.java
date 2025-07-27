package com.openmason.test.mocks;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Mock OpenGL context for headless testing.
 * 
 * Simulates OpenGL state and operations without requiring actual OpenGL
 * hardware or drivers. This allows comprehensive testing of rendering
 * logic and OpenGL state management in a CI/CD environment.
 */
public class MockOpenGLContext {
    
    private static final Logger logger = LoggerFactory.getLogger(MockOpenGLContext.class);
    
    // OpenGL state simulation
    private final Map<Integer, Object> glState = new HashMap<>();
    private final AtomicBoolean contextActive = new AtomicBoolean(false);
    private final AtomicInteger errorState = new AtomicInteger(0); // GL_NO_ERROR
    
    // Performance simulation
    private volatile long frameStartTime = 0;
    private volatile int frameCount = 0;
    private volatile double simulatedFPS = 60.0;
    
    // Constants for OpenGL simulation
    public static final int GL_NO_ERROR = 0;
    public static final int GL_INVALID_ENUM = 0x0500;
    public static final int GL_INVALID_VALUE = 0x0501;
    public static final int GL_INVALID_OPERATION = 0x0502;
    public static final int GL_OUT_OF_MEMORY = 0x0505;
    
    public static final int GL_VERSION = 0x1F02;
    public static final int GL_VENDOR = 0x1F00;
    public static final int GL_RENDERER = 0x1F01;
    
    // Viewport state
    private int viewportX = 0;
    private int viewportY = 0;
    private int viewportWidth = 800;
    private int viewportHeight = 600;
    
    // Clear color state
    private float clearColorR = 0.0f;
    private float clearColorG = 0.0f;
    private float clearColorB = 0.0f;
    private float clearColorA = 1.0f;
    
    public MockOpenGLContext() {
        initializeDefaultState();
    }
    
    /**
     * Initializes default OpenGL state for testing.
     */
    private void initializeDefaultState() {
        // Set up basic OpenGL state
        glState.put(GL_VERSION, "4.6.0 Mock OpenGL Context");
        glState.put(GL_VENDOR, "OpenMason Test Framework");
        glState.put(GL_RENDERER, "Mock OpenGL Renderer");
        
        logger.debug("Mock OpenGL context initialized");
    }
    
    /**
     * Simulates glGetString() function.
     * 
     * @param name OpenGL string name constant
     * @return Simulated string value
     */
    public String glGetString(int name) {
        Object value = glState.get(name);
        return value != null ? value.toString() : "Unknown";
    }
    
    /**
     * Simulates glGetError() function.
     * 
     * @return Current error state
     */
    public int glGetError() {
        return errorState.getAndSet(GL_NO_ERROR);
    }
    
    /**
     * Sets an error state for testing error handling.
     * 
     * @param error OpenGL error code
     */
    public void setError(int error) {
        errorState.set(error);
    }
    
    /**
     * Simulates glViewport() function.
     * 
     * @param x Viewport X coordinate
     * @param y Viewport Y coordinate
     * @param width Viewport width
     * @param height Viewport height
     */
    public void glViewport(int x, int y, int width, int height) {
        this.viewportX = x;
        this.viewportY = y;
        this.viewportWidth = Math.max(1, width);
        this.viewportHeight = Math.max(1, height);
        
        logger.trace("Mock viewport set: {}x{} at ({}, {})", width, height, x, y);
    }
    
    /**
     * Simulates glClearColor() function.
     * 
     * @param r Red component
     * @param g Green component
     * @param b Blue component
     * @param a Alpha component
     */
    public void glClearColor(float r, float g, float b, float a) {
        this.clearColorR = r;
        this.clearColorG = g;
        this.clearColorB = b;
        this.clearColorA = a;
        
        logger.trace("Mock clear color set: ({}, {}, {}, {})", r, g, b, a);
    }
    
    /**
     * Simulates glClear() function.
     * 
     * @param mask Clear mask (color, depth, etc.)
     */
    public void glClear(int mask) {
        // Simulate clear operation
        logger.trace("Mock clear operation with mask: 0x{}", Integer.toHexString(mask));
    }
    
    /**
     * Simulates glEnable() function.
     * 
     * @param cap OpenGL capability
     */
    public void glEnable(int cap) {
        glState.put(cap, true);
        logger.trace("Mock enabled capability: 0x{}", Integer.toHexString(cap));
    }
    
    /**
     * Simulates glDisable() function.
     * 
     * @param cap OpenGL capability
     */
    public void glDisable(int cap) {
        glState.put(cap, false);
        logger.trace("Mock disabled capability: 0x{}", Integer.toHexString(cap));
    }
    
    /**
     * Checks if a capability is enabled.
     * 
     * @param cap OpenGL capability
     * @return True if enabled
     */
    public boolean isEnabled(int cap) {
        Object state = glState.get(cap);
        return state instanceof Boolean && (Boolean) state;
    }
    
    /**
     * Begins a frame for performance simulation.
     */
    public void beginFrame() {
        frameStartTime = System.nanoTime();
        contextActive.set(true);
    }
    
    /**
     * Ends a frame and updates performance metrics.
     */
    public void endFrame() {
        if (frameStartTime > 0) {
            long frameTime = System.nanoTime() - frameStartTime;
            double frameTimeMs = frameTime / 1_000_000.0;
            
            // Update simulated FPS
            frameCount++;
            if (frameCount % 60 == 0) { // Update every 60 frames
                simulatedFPS = 1000.0 / frameTimeMs;
            }
            
            // Simulate frame timing variability
            if (frameTimeMs < 16.67) { // Target 60 FPS
                try {
                    Thread.sleep((long) (16.67 - frameTimeMs));
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            }
        }
        
        contextActive.set(false);
    }
    
    /**
     * Gets the current simulated FPS.
     * 
     * @return Simulated FPS
     */
    public double getSimulatedFPS() {
        return simulatedFPS;
    }
    
    /**
     * Sets the simulated FPS for testing different performance scenarios.
     * 
     * @param fps Target FPS to simulate
     */
    public void setSimulatedFPS(double fps) {
        this.simulatedFPS = Math.max(1.0, Math.min(240.0, fps));
    }
    
    /**
     * Gets the current viewport width.
     * 
     * @return Viewport width
     */
    public int getViewportWidth() {
        return viewportWidth;
    }
    
    /**
     * Gets the current viewport height.
     * 
     * @return Viewport height
     */
    public int getViewportHeight() {
        return viewportHeight;
    }
    
    /**
     * Checks if the context is currently active.
     * 
     * @return True if context is active
     */
    public boolean isContextActive() {
        return contextActive.get();
    }
    
    /**
     * Simulates a context error for testing error handling.
     * 
     * @param errorType Type of error to simulate
     */
    public void simulateError(int errorType) {
        setError(errorType);
        logger.debug("Simulated OpenGL error: 0x{}", Integer.toHexString(errorType));
    }
    
    /**
     * Resets the mock context to initial state.
     */
    public void reset() {
        glState.clear();
        initializeDefaultState();
        contextActive.set(false);
        errorState.set(GL_NO_ERROR);
        frameStartTime = 0;
        frameCount = 0;
        simulatedFPS = 60.0;
        
        // Reset viewport and clear color
        viewportX = 0;
        viewportY = 0;
        viewportWidth = 800;
        viewportHeight = 600;
        clearColorR = 0.0f;
        clearColorG = 0.0f;
        clearColorB = 0.0f;
        clearColorA = 1.0f;
        
        logger.debug("Mock OpenGL context reset");
    }
    
    /**
     * Gets current OpenGL state for testing validation.
     * 
     * @return Copy of current GL state
     */
    public Map<Integer, Object> getCurrentState() {
        return new HashMap<>(glState);
    }
}