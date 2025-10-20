package com.openmason.ui.viewport.gizmo;

import com.openmason.ui.viewport.gizmo.interaction.GizmoInteractionHandler;
import com.openmason.ui.viewport.gizmo.interaction.GizmoPart;
import com.openmason.ui.viewport.gizmo.modes.IGizmoMode;
import com.openmason.ui.viewport.gizmo.modes.RotateMode;
import com.openmason.ui.viewport.gizmo.modes.ScaleMode;
import com.openmason.ui.viewport.gizmo.modes.TranslateMode;
import com.openmason.ui.viewport.state.TransformState;
import org.joml.Matrix4f;
import org.joml.Vector3f;
import org.lwjgl.opengl.GL30;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Main gizmo renderer that orchestrates all transform modes and handles rendering.
 *
 * <p>This class follows SOLID principles:
 * - Single Responsibility: Only orchestrates gizmo rendering
 * - Open/Closed: Extensible with new modes without modification
 * - Liskov Substitution: All modes are interchangeable via IGizmoMode
 * - Dependency Inversion: Depends on abstractions (IGizmoMode, GizmoState)
 * - RAII: Proper resource cleanup
 */
public class GizmoRenderer {

    private final GizmoState gizmoState;
    private final TransformState transformState;
    private final GizmoInteractionHandler interactionHandler;

    // Gizmo modes
    private final Map<GizmoState.Mode, IGizmoMode> modes = new HashMap<>();
    private IGizmoMode currentMode;

    // OpenGL resources
    private int shaderProgram = -1;
    private boolean initialized = false;

    /**
     * Creates a new GizmoRenderer.
     *
     * @param gizmoState The gizmo state to manage (must not be null)
     * @param transformState The transform state to modify (must not be null)
     * @throws IllegalArgumentException if any parameter is null
     */
    public GizmoRenderer(GizmoState gizmoState, TransformState transformState) {
        if (gizmoState == null) {
            throw new IllegalArgumentException("GizmoState cannot be null");
        }
        if (transformState == null) {
            throw new IllegalArgumentException("TransformState cannot be null");
        }

        this.gizmoState = gizmoState;
        this.transformState = transformState;
        this.interactionHandler = new GizmoInteractionHandler(gizmoState, transformState);

        // Create mode instances
        modes.put(GizmoState.Mode.TRANSLATE, new TranslateMode());
        modes.put(GizmoState.Mode.ROTATE, new RotateMode());
        modes.put(GizmoState.Mode.SCALE, new ScaleMode());

        // Set initial mode
        currentMode = modes.get(gizmoState.getCurrentMode());
    }

    /**
     * Initializes the gizmo renderer and all modes.
     * Must be called before rendering.
     *
     * @throws IllegalStateException if initialization fails
     */
    public void initialize() {
        if (initialized) {
            return; // Already initialized
        }

        try {
            // Load shaders
            shaderProgram = loadGizmoShaders();
            if (shaderProgram < 0) {
                throw new IllegalStateException("Failed to load gizmo shaders");
            }

            // Initialize all modes
            for (IGizmoMode mode : modes.values()) {
                mode.initialize();

                // Set shader program for each mode
                if (mode instanceof TranslateMode) {
                    ((TranslateMode) mode).setShaderProgram(shaderProgram);
                } else if (mode instanceof RotateMode) {
                    ((RotateMode) mode).setShaderProgram(shaderProgram);
                } else if (mode instanceof ScaleMode) {
                    ((ScaleMode) mode).setShaderProgram(shaderProgram);
                }
            }

            initialized = true;
        } catch (Exception e) {
            dispose(); // Clean up partial initialization
            throw new IllegalStateException("Failed to initialize GizmoRenderer", e);
        }
    }

    /**
     * Renders the gizmo at the specified position.
     *
     * @param camera View matrix
     * @param projection Projection matrix
     * @param viewportWidth Viewport width in pixels
     * @param viewportHeight Viewport height in pixels
     */
    public void render(Matrix4f viewMatrix, Matrix4f projectionMatrix,
                      int viewportWidth, int viewportHeight) {
        if (!initialized) {
            throw new IllegalStateException("GizmoRenderer not initialized");
        }
        if (!gizmoState.isEnabled()) {
            return; // Gizmo disabled
        }

        // Update mode if changed
        IGizmoMode newMode = modes.get(gizmoState.getCurrentMode());
        if (newMode != currentMode) {
            currentMode = newMode;
        }

        if (currentMode == null) {
            return; // No valid mode
        }

        // Create gizmo transform matrix at object position
        Vector3f gizmoPosition = new Vector3f(
            transformState.getPositionX(),
            transformState.getPositionY(),
            transformState.getPositionZ()
        );
        Matrix4f gizmoTransform = new Matrix4f().identity().translate(gizmoPosition);

        // Create view-projection matrix
        Matrix4f viewProjection = new Matrix4f(projectionMatrix).mul(viewMatrix);

        // Enable depth testing
        GL30.glEnable(GL30.GL_DEPTH_TEST);
        GL30.glDepthFunc(GL30.GL_LEQUAL);

        // Render current mode
        currentMode.render(gizmoTransform, viewProjection, gizmoState);

        GL30.glDisable(GL30.GL_DEPTH_TEST);
    }

    /**
     * Handles mouse movement for gizmo interaction.
     *
     * @param mouseX Mouse X position
     * @param mouseY Mouse Y position
     * @param viewMatrix View matrix
     * @param projectionMatrix Projection matrix
     * @param viewportWidth Viewport width
     * @param viewportHeight Viewport height
     */
    public void handleMouseMove(float mouseX, float mouseY,
                               Matrix4f viewMatrix, Matrix4f projectionMatrix,
                               int viewportWidth, int viewportHeight) {
        if (!initialized || !gizmoState.isEnabled()) {
            return;
        }

        // Update camera for raycasting
        interactionHandler.updateCamera(viewMatrix, projectionMatrix, viewportWidth, viewportHeight);

        // Get interactive parts from current mode
        Vector3f gizmoPosition = new Vector3f(
            transformState.getPositionX(),
            transformState.getPositionY(),
            transformState.getPositionZ()
        );
        List<GizmoPart> parts = currentMode.getInteractiveParts(gizmoPosition);

        // Handle mouse movement
        interactionHandler.handleMouseMove(mouseX, mouseY, parts);
    }

    /**
     * Handles mouse press for starting drag operations.
     *
     * @param mouseX Mouse X position
     * @param mouseY Mouse Y position
     * @return true if gizmo was clicked, false otherwise
     */
    public boolean handleMousePress(float mouseX, float mouseY) {
        if (!initialized || !gizmoState.isEnabled()) {
            return false;
        }

        return interactionHandler.handleMousePress(mouseX, mouseY);
    }

    /**
     * Handles mouse release for ending drag operations.
     *
     * @param mouseX Mouse X position
     * @param mouseY Mouse Y position
     */
    public void handleMouseRelease(float mouseX, float mouseY) {
        if (!initialized || !gizmoState.isEnabled()) {
            return;
        }

        interactionHandler.handleMouseRelease(mouseX, mouseY);
    }

    /**
     * Cycles to the next gizmo mode (Translate → Rotate → Scale → Translate).
     */
    public void cycleMode() {
        gizmoState.cycleMode();
    }

    /**
     * Sets the gizmo mode directly.
     *
     * @param mode The mode to set (must not be null)
     * @throws IllegalArgumentException if mode is null
     */
    public void setMode(GizmoState.Mode mode) {
        if (mode == null) {
            throw new IllegalArgumentException("Mode cannot be null");
        }
        gizmoState.setCurrentMode(mode);
    }

    /**
     * Gets the current gizmo mode.
     *
     * @return Current mode (never null)
     */
    public GizmoState.Mode getCurrentMode() {
        return gizmoState.getCurrentMode();
    }

    /**
     * Checks if the gizmo is currently being dragged.
     *
     * @return true if dragging, false otherwise
     */
    public boolean isDragging() {
        return gizmoState.isDragging();
    }

    /**
     * Disposes of all OpenGL resources.
     */
    public void dispose() {
        // Dispose all modes
        for (IGizmoMode mode : modes.values()) {
            mode.dispose();
        }

        // Delete shader program
        if (shaderProgram >= 0) {
            GL30.glDeleteProgram(shaderProgram);
            shaderProgram = -1;
        }

        initialized = false;
    }

    /**
     * Checks if the renderer has been initialized.
     *
     * @return true if initialized, false otherwise
     */
    public boolean isInitialized() {
        return initialized;
    }

    /**
     * Gets the gizmo state for external configuration.
     *
     * @return The gizmo state (never null)
     */
    public GizmoState getGizmoState() {
        return gizmoState;
    }

    /**
     * Loads and compiles the gizmo shaders.
     *
     * @return Shader program ID, or -1 on failure
     */
    private int loadGizmoShaders() {
        try {
            // Load vertex shader
            String vertexSource = loadShaderSource("/shaders/gizmo.vert");
            int vertexShader = compileShader(vertexSource, GL30.GL_VERTEX_SHADER);
            if (vertexShader < 0) {
                return -1;
            }

            // Load fragment shader
            String fragmentSource = loadShaderSource("/shaders/gizmo.frag");
            int fragmentShader = compileShader(fragmentSource, GL30.GL_FRAGMENT_SHADER);
            if (fragmentShader < 0) {
                GL30.glDeleteShader(vertexShader);
                return -1;
            }

            // Link program
            int program = GL30.glCreateProgram();
            GL30.glAttachShader(program, vertexShader);
            GL30.glAttachShader(program, fragmentShader);
            GL30.glLinkProgram(program);

            // Check link status
            int linkStatus = GL30.glGetProgrami(program, GL30.GL_LINK_STATUS);
            if (linkStatus == GL30.GL_FALSE) {
                String log = GL30.glGetProgramInfoLog(program);
                System.err.println("Gizmo shader link failed: " + log);
                GL30.glDeleteShader(vertexShader);
                GL30.glDeleteShader(fragmentShader);
                GL30.glDeleteProgram(program);
                return -1;
            }

            // Clean up individual shaders (no longer needed after linking)
            GL30.glDeleteShader(vertexShader);
            GL30.glDeleteShader(fragmentShader);

            return program;

        } catch (Exception e) {
            System.err.println("Failed to load gizmo shaders: " + e.getMessage());
            return -1;
        }
    }

    /**
     * Loads shader source code from resources.
     *
     * @param path Resource path to shader file
     * @return Shader source code
     * @throws Exception if loading fails
     */
    private String loadShaderSource(String path) throws Exception {
        InputStream stream = getClass().getResourceAsStream(path);
        if (stream == null) {
            throw new Exception("Shader file not found: " + path);
        }

        StringBuilder source = new StringBuilder();
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(stream, StandardCharsets.UTF_8))) {
            String line;
            while ((line = reader.readLine()) != null) {
                source.append(line).append("\n");
            }
        }

        return source.toString();
    }

    /**
     * Compiles a shader.
     *
     * @param source Shader source code
     * @param type Shader type (GL_VERTEX_SHADER or GL_FRAGMENT_SHADER)
     * @return Shader ID, or -1 on failure
     */
    private int compileShader(String source, int type) {
        int shader = GL30.glCreateShader(type);
        GL30.glShaderSource(shader, source);
        GL30.glCompileShader(shader);

        // Check compile status
        int compileStatus = GL30.glGetShaderi(shader, GL30.GL_COMPILE_STATUS);
        if (compileStatus == GL30.GL_FALSE) {
            String log = GL30.glGetShaderInfoLog(shader);
            String typeName = (type == GL30.GL_VERTEX_SHADER) ? "vertex" : "fragment";
            System.err.println("Gizmo " + typeName + " shader compile failed: " + log);
            GL30.glDeleteShader(shader);
            return -1;
        }

        return shader;
    }
}
