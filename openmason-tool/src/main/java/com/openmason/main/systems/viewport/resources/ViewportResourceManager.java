package com.openmason.main.systems.viewport.resources;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Manages all viewport OpenGL resources (framebuffer, geometry buffers).
 * Provides centralized RAII resource lifecycle management.
 * Follows Single Responsibility Principle - only handles resource management.
 */
public class ViewportResourceManager implements AutoCloseable {

    private static final Logger logger = LoggerFactory.getLogger(ViewportResourceManager.class);

    private final FramebufferResource framebuffer;
    private final GeometryBuffer testCube;
    private final GeometryBuffer grid;
    private boolean initialized = false;

    public ViewportResourceManager() {
        this.framebuffer = new FramebufferResource();
        this.testCube = new GeometryBuffer("TestCube");
        this.grid = new GeometryBuffer("Grid");
    }

    /**
     * Initialize all viewport resources.
     */
    public void initialize(int viewportWidth, int viewportHeight) {
        if (initialized) {
            logger.debug("Resource manager already initialized");
            return;
        }

        try {
            logger.info("Initializing viewport resources ({}x{})...", viewportWidth, viewportHeight);

            // Create framebuffer
            framebuffer.create(viewportWidth, viewportHeight);

            // Create test cube geometry
            testCube.uploadPositionData(createCubeVertices());

            // Create grid geometry
            grid.uploadPositionData(createGridVertices());

            // Validate all resources
            framebuffer.validate();
            testCube.validate();
            grid.validate();

            initialized = true;
            logger.info("Viewport resources initialized successfully");

        } catch (Exception e) {
            logger.error("Failed to initialize viewport resources", e);
            cleanup();
            throw new RuntimeException("Viewport resource initialization failed", e);
        }
    }

    /**
     * Resize framebuffer if dimensions changed.
     */
    public void resizeFramebuffer(int newWidth, int newHeight) {
        if (!initialized) {
            throw new IllegalStateException("Resource manager not initialized");
        }
        framebuffer.resize(newWidth, newHeight);
    }

    /**
     * Create cube vertices as triangles (36 vertices = 12 triangles = 6 faces).
     */
    private float[] createCubeVertices() {
        return new float[] {
            // Front face (z = 0.5)
            -0.5f, -0.5f,  0.5f,   0.5f, -0.5f,  0.5f,   0.5f,  0.5f,  0.5f,
            -0.5f, -0.5f,  0.5f,   0.5f,  0.5f,  0.5f,  -0.5f,  0.5f,  0.5f,

            // Back face (z = -0.5)
            -0.5f, -0.5f, -0.5f,  -0.5f,  0.5f, -0.5f,   0.5f,  0.5f, -0.5f,
            -0.5f, -0.5f, -0.5f,   0.5f,  0.5f, -0.5f,   0.5f, -0.5f, -0.5f,

            // Left face (x = -0.5)
            -0.5f, -0.5f, -0.5f,  -0.5f, -0.5f,  0.5f,  -0.5f,  0.5f,  0.5f,
            -0.5f, -0.5f, -0.5f,  -0.5f,  0.5f,  0.5f,  -0.5f,  0.5f, -0.5f,

            // Right face (x = 0.5)
             0.5f, -0.5f, -0.5f,   0.5f,  0.5f, -0.5f,   0.5f,  0.5f,  0.5f,
             0.5f, -0.5f, -0.5f,   0.5f,  0.5f,  0.5f,   0.5f, -0.5f,  0.5f,

            // Top face (y = 0.5)
            -0.5f,  0.5f, -0.5f,  -0.5f,  0.5f,  0.5f,   0.5f,  0.5f,  0.5f,
            -0.5f,  0.5f, -0.5f,   0.5f,  0.5f,  0.5f,   0.5f,  0.5f, -0.5f,

            // Bottom face (y = -0.5)
            -0.5f, -0.5f, -0.5f,   0.5f, -0.5f, -0.5f,   0.5f, -0.5f,  0.5f,
            -0.5f, -0.5f, -0.5f,   0.5f, -0.5f,  0.5f,  -0.5f, -0.5f,  0.5f
        };
    }

    /**
     * Create grid vertices (20x20 grid with 1.0f spacing).
     */
    private float[] createGridVertices() {
        int gridSize = 20;
        float gridSpacing = 1.0f;

        // Calculate number of lines (horizontal + vertical)
        int lineCount = (gridSize + 1) * 2;
        float[] gridVertices = new float[lineCount * 2 * 3]; // 2 points per line, 3 coordinates per point

        int index = 0;
        float halfSize = gridSize * gridSpacing * 0.5f;

        // Horizontal lines
        for (int i = 0; i <= gridSize; i++) {
            float z = -halfSize + i * gridSpacing;

            // Start point
            gridVertices[index++] = -halfSize;
            gridVertices[index++] = 0.0f;
            gridVertices[index++] = z;

            // End point
            gridVertices[index++] = halfSize;
            gridVertices[index++] = 0.0f;
            gridVertices[index++] = z;
        }

        // Vertical lines
        for (int i = 0; i <= gridSize; i++) {
            float x = -halfSize + i * gridSpacing;

            // Start point
            gridVertices[index++] = x;
            gridVertices[index++] = 0.0f;
            gridVertices[index++] = -halfSize;

            // End point
            gridVertices[index++] = x;
            gridVertices[index++] = 0.0f;
            gridVertices[index++] = halfSize;
        }

        return gridVertices;
    }

    /**
     * Clean up all resources.
     */
    private void cleanup() {
        framebuffer.close();
        testCube.close();
        grid.close();
        initialized = false;
    }

    @Override
    public void close() {
        if (initialized) {
            logger.info("Closing viewport resource manager");
            cleanup();
        }
    }

    // Getters
    public FramebufferResource getFramebuffer() { return framebuffer; }
    public GeometryBuffer getGrid() { return grid; }
    public boolean isInitialized() { return initialized; }

    /**
     * Validate that all resources are ready.
     */
    public void validate() {
        if (!initialized) {
            throw new IllegalStateException("Resource manager not initialized");
        }
        framebuffer.validate();
        testCube.validate();
        grid.validate();
    }
}
