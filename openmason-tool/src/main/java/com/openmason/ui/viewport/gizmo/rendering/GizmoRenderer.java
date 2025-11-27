package com.openmason.ui.viewport.gizmo.rendering;

import com.openmason.ui.viewport.gizmo.GizmoState;
import com.openmason.ui.viewport.gizmo.interaction.GizmoInteractionHandler;
import com.openmason.ui.viewport.gizmo.interaction.GizmoPart;
import com.openmason.ui.viewport.gizmo.modes.IGizmoMode;
import com.openmason.ui.viewport.gizmo.modes.RotateMode;
import com.openmason.ui.viewport.gizmo.modes.ScaleMode;
import com.openmason.ui.viewport.gizmo.modes.TranslateMode;
import com.openmason.ui.viewport.state.TransformState;
import com.openmason.ui.viewport.state.ViewportState;
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
 * Orchestrates transform gizmo rendering and interaction (translate, rotate, scale).
 * Extends SOLID principles with extensible mode system and proper resource management.
 */
public class GizmoRenderer {

    private final GizmoState gizmoState;
    private final TransformState transformState;
    private final GizmoInteractionHandler interactionHandler;
    private ViewportState viewportState;

    private final Map<GizmoState.Mode, IGizmoMode> modes = new HashMap<>();
    private IGizmoMode currentMode;

    private int shaderProgram = -1;
    private boolean initialized = false;

    /**
     * Creates a new GizmoRenderer.
     *
     * @param gizmoState The gizmo state to manage (must not be null)
     * @param transformState The transform state to modify (must not be null)
     * @param viewportState The viewport state for grid snapping (may be null initially)
     * @throws IllegalArgumentException if gizmoState or transformState is null
     */
    public GizmoRenderer(GizmoState gizmoState, TransformState transformState, ViewportState viewportState) {
        if (gizmoState == null) {
            throw new IllegalArgumentException("GizmoState cannot be null");
        }
        if (transformState == null) {
            throw new IllegalArgumentException("TransformState cannot be null");
        }

        this.gizmoState = gizmoState;
        this.transformState = transformState;
        this.viewportState = viewportState;
        this.interactionHandler = new GizmoInteractionHandler(gizmoState, transformState, viewportState);

        modes.put(GizmoState.Mode.TRANSLATE, new TranslateMode());
        modes.put(GizmoState.Mode.ROTATE, new RotateMode());
        modes.put(GizmoState.Mode.SCALE, new ScaleMode());

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
            shaderProgram = loadGizmoShaders();
            if (shaderProgram < 0) {
                throw new IllegalStateException("Failed to load gizmo shaders");
            }

            for (IGizmoMode mode : modes.values()) {
                mode.initialize();

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
            dispose();
            throw new IllegalStateException("Failed to initialize GizmoRenderer", e);
        }
    }

    public void render(Matrix4f viewMatrix, Matrix4f projectionMatrix) {
        if (!initialized) {
            throw new IllegalStateException("GizmoRenderer not initialized");
        }
        if (!gizmoState.isEnabled()) {
            return;
        }

        updateCurrentMode();
        if (currentMode == null) {
            return;
        }

        Matrix4f gizmoTransform = createGizmoTransform();
        Matrix4f viewProjection = new Matrix4f(projectionMatrix).mul(viewMatrix);

        boolean cullFaceEnabled = GL30.glIsEnabled(GL30.GL_CULL_FACE);

        configureRenderState();
        currentMode.render(gizmoTransform, viewProjection, gizmoState);
        restoreRenderState(cullFaceEnabled);
    }

    private void updateCurrentMode() {
        IGizmoMode newMode = modes.get(gizmoState.getCurrentMode());
        if (newMode != currentMode) {
            currentMode = newMode;
        }
    }

    private Matrix4f createGizmoTransform() {
        Vector3f position = new Vector3f(
            transformState.getPositionX(),
            transformState.getPositionY(),
            transformState.getPositionZ()
        );
        return new Matrix4f().identity().translate(position);
    }

    private void configureRenderState() {
        GL30.glEnable(GL30.GL_DEPTH_TEST);
        GL30.glDepthFunc(GL30.GL_ALWAYS);  // Always on top, never occluded
        GL30.glDisable(GL30.GL_CULL_FACE); // Show all rotation grabber sides
    }

    private void restoreRenderState(boolean cullFaceEnabled) {
        if (cullFaceEnabled) {
            GL30.glEnable(GL30.GL_CULL_FACE);
        }
        GL30.glDepthFunc(GL30.GL_LEQUAL);
        GL30.glDisable(GL30.GL_DEPTH_TEST);
    }

    /**
     * Handles mouse movement for gizmo interaction.
     */
    public void handleMouseMove(float mouseX, float mouseY,
                               Matrix4f viewMatrix, Matrix4f projectionMatrix,
                               int viewportWidth, int viewportHeight) {
        if (!initialized || !gizmoState.isEnabled()) {
            return;
        }

        interactionHandler.updateCamera(viewMatrix, projectionMatrix, viewportWidth, viewportHeight);

        Vector3f gizmoPosition = new Vector3f(
            transformState.getPositionX(),
            transformState.getPositionY(),
            transformState.getPositionZ()
        );
        List<GizmoPart> parts = currentMode.getInteractiveParts(gizmoPosition);

        interactionHandler.handleMouseMove(mouseX, mouseY, parts);
    }

    /**
     * Handles mouse press for starting drag operations.
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
     */
    public void handleMouseRelease(float mouseX, float mouseY) {
        if (!initialized || !gizmoState.isEnabled()) {
            return;
        }

        interactionHandler.handleMouseRelease(mouseX, mouseY);
    }

    /**
     * Sets the gizmo mode directly.
     * @throws IllegalArgumentException if mode is null
     */
    public void setMode(GizmoState.Mode mode) {
        if (mode == null) {
            throw new IllegalArgumentException("Mode cannot be null");
        }
        gizmoState.setCurrentMode(mode);
    }

    public GizmoState.Mode getCurrentMode() {
        return gizmoState.getCurrentMode();
    }

    public boolean isDragging() {
        return gizmoState.isDragging();
    }

    /**
     * Updates viewport state for grid snapping configuration.
     * @throws IllegalArgumentException if viewportState is null
     */
    public void updateViewportState(com.openmason.ui.viewport.state.ViewportState viewportState) {
        if (viewportState == null) {
            throw new IllegalArgumentException("ViewportState cannot be null");
        }

        this.viewportState = viewportState;

        if (interactionHandler != null) {
            interactionHandler.updateViewportState(viewportState);
        }
    }

    public void dispose() {
        for (IGizmoMode mode : modes.values()) {
            mode.dispose();
        }

        if (shaderProgram >= 0) {
            GL30.glDeleteProgram(shaderProgram);
            shaderProgram = -1;
        }

        initialized = false;
    }

    public boolean isInitialized() {
        return initialized;
    }

    public GizmoState getGizmoState() {
        return gizmoState;
    }

    /**
     * Loads and compiles gizmo shaders. Attaches individual shaders to program,
     * validates link, and cleans up shader objects.
     * @return Shader program ID, or -1 on failure
     */
    private int loadGizmoShaders() {
        try {
            String vertexSource = loadShaderSource("/shaders/gizmo.vert");
            int vertexShader = compileShader(vertexSource, GL30.GL_VERTEX_SHADER);
            if (vertexShader < 0) {
                return -1;
            }

            String fragmentSource = loadShaderSource("/shaders/gizmo.frag");
            int fragmentShader = compileShader(fragmentSource, GL30.GL_FRAGMENT_SHADER);
            if (fragmentShader < 0) {
                GL30.glDeleteShader(vertexShader);
                return -1;
            }

            int program = GL30.glCreateProgram();
            GL30.glAttachShader(program, vertexShader);
            GL30.glAttachShader(program, fragmentShader);
            GL30.glLinkProgram(program);

            int linkStatus = GL30.glGetProgrami(program, GL30.GL_LINK_STATUS);
            if (linkStatus == GL30.GL_FALSE) {
                String log = GL30.glGetProgramInfoLog(program);
                System.err.println("Gizmo shader link failed: " + log);
                GL30.glDeleteShader(vertexShader);
                GL30.glDeleteShader(fragmentShader);
                GL30.glDeleteProgram(program);
                return -1;
            }

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
     * Compiles a shader and validates compilation.
     * @param type GL_VERTEX_SHADER or GL_FRAGMENT_SHADER
     * @return Shader ID, or -1 on failure
     */
    private int compileShader(String source, int type) {
        int shader = GL30.glCreateShader(type);
        GL30.glShaderSource(shader, source);
        GL30.glCompileShader(shader);

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
