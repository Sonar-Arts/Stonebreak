package com.openmason.ui.viewport.shaders;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.EnumMap;
import java.util.Map;

import static org.lwjgl.opengl.GL20.*;

/**
 * Manages creation, compilation, and lifecycle of all viewport shaders.
 * Centralizes shader management following Single Responsibility Principle.
 */
public class ShaderManager {

    private static final Logger logger = LoggerFactory.getLogger(ShaderManager.class);

    private final Map<ShaderType, ShaderProgram> shaderPrograms = new EnumMap<>(ShaderType.class);
    private boolean initialized = false;

    /**
     * Initialize all shader programs.
     */
    public void initialize() {
        if (initialized) {
            logger.debug("Shader manager already initialized");
            return;
        }

        try {
            // Create all shader programs
            shaderPrograms.put(ShaderType.BASIC, createBasicShader());
            shaderPrograms.put(ShaderType.MATRIX, createMatrixShader());
            shaderPrograms.put(ShaderType.GIZMO, createGizmoShader());

            initialized = true;
            logger.info("Shader manager initialized successfully - {} shader programs created", shaderPrograms.size());

        } catch (Exception e) {
            logger.error("Failed to initialize shader manager", e);
            cleanup();
            throw new RuntimeException("Shader manager initialization failed", e);
        }
    }

    /**
     * Get shader program by type.
     */
    public ShaderProgram getShaderProgram(ShaderType type) {
        if (!initialized) {
            throw new IllegalStateException("Shader manager not initialized");
        }
        ShaderProgram program = shaderPrograms.get(type);
        if (program == null) {
            throw new IllegalArgumentException("No shader program found for type: " + type);
        }
        return program;
    }

    /**
     * Create basic shader for simple geometry (grid, test cube).
     */
    private ShaderProgram createBasicShader() {
        logger.debug("Creating BASIC shader program");

        String vertexShaderSource = """
            #version 330 core
            layout (location = 0) in vec3 aPos;

            uniform mat4 uMVPMatrix;
            uniform vec3 uColor;

            out vec3 vertexColor;

            void main() {
                gl_Position = uMVPMatrix * vec4(aPos, 1.0);
                vertexColor = uColor;
            }
            """;

        String fragmentShaderSource = """
            #version 330 core
            in vec3 vertexColor;
            out vec4 FragColor;

            void main() {
                FragColor = vec4(vertexColor, 1.0);
            }
            """;

        int vertexShader = compileShader(GL_VERTEX_SHADER, vertexShaderSource, "BASIC_VERTEX");
        int fragmentShader = compileShader(GL_FRAGMENT_SHADER, fragmentShaderSource, "BASIC_FRAGMENT");
        int program = linkProgram(vertexShader, fragmentShader);

        int mvpLocation = glGetUniformLocation(program, "uMVPMatrix");
        int colorLocation = glGetUniformLocation(program, "uColor");

        logger.debug("BASIC shader created - program: {}, mvp: {}, color: {}", program, mvpLocation, colorLocation);
        return new ShaderProgram(ShaderType.BASIC, program, vertexShader, fragmentShader, mvpLocation, colorLocation);
    }

    /**
     * Create matrix shader with per-part transformation and texture support.
     */
    private ShaderProgram createMatrixShader() {
        logger.debug("Creating MATRIX shader program");

        String vertexShaderSource = """
            #version 330 core
            layout (location = 0) in vec3 aPos;
            layout (location = 1) in vec2 aTexCoord;

            uniform mat4 uMVPMatrix;     // View-Projection matrix (camera)
            uniform mat4 uModelMatrix;   // Model transformation matrix (per-part positioning)
            uniform vec3 uColor;
            uniform bool uUseTexture;    // Whether to use texture or solid color

            out vec3 vertexColor;
            out vec2 TexCoord;

            void main() {
                // Apply model transformation first, then MVP
                gl_Position = uMVPMatrix * uModelMatrix * vec4(aPos, 1.0);
                vertexColor = uColor;
                TexCoord = aTexCoord;
            }
            """;

        String fragmentShaderSource = """
            #version 330 core
            in vec3 vertexColor;
            in vec2 TexCoord;
            out vec4 FragColor;

            uniform sampler2D uTexture;
            uniform bool uUseTexture;

            void main() {
                if (uUseTexture) {
                    // Use texture color directly for proper cow texture display
                    FragColor = texture(uTexture, TexCoord);
                } else {
                    // Use solid color only
                    FragColor = vec4(vertexColor, 1.0);
                }
            }
            """;

        int vertexShader = compileShader(GL_VERTEX_SHADER, vertexShaderSource, "MATRIX_VERTEX");
        int fragmentShader = compileShader(GL_FRAGMENT_SHADER, fragmentShaderSource, "MATRIX_FRAGMENT");
        int program = linkProgram(vertexShader, fragmentShader);

        int mvpLocation = glGetUniformLocation(program, "uMVPMatrix");
        int colorLocation = glGetUniformLocation(program, "uColor");
        int modelLocation = glGetUniformLocation(program, "uModelMatrix");
        int textureLocation = glGetUniformLocation(program, "uTexture");
        int useTextureLocation = glGetUniformLocation(program, "uUseTexture");

        logger.debug("MATRIX shader created - program: {}, mvp: {}, model: {}, texture: {}",
                    program, mvpLocation, modelLocation, textureLocation);
        return new ShaderProgram(ShaderType.MATRIX, program, vertexShader, fragmentShader,
                                mvpLocation, colorLocation, modelLocation, textureLocation, useTextureLocation);
    }

    /**
     * Create gizmo shader (isolated from model pipeline).
     */
    private ShaderProgram createGizmoShader() {
        logger.debug("Creating GIZMO shader program");

        String vertexShaderSource = """
            #version 330 core
            layout (location = 0) in vec3 aPos;

            uniform mat4 uMVPMatrix;
            uniform vec3 uColor;

            out vec3 vertexColor;

            void main() {
                gl_Position = uMVPMatrix * vec4(aPos, 1.0);
                vertexColor = uColor;
            }
            """;

        String fragmentShaderSource = """
            #version 330 core
            in vec3 vertexColor;
            out vec4 FragColor;

            void main() {
                FragColor = vec4(vertexColor, 1.0);
            }
            """;

        int vertexShader = compileShader(GL_VERTEX_SHADER, vertexShaderSource, "GIZMO_VERTEX");
        int fragmentShader = compileShader(GL_FRAGMENT_SHADER, fragmentShaderSource, "GIZMO_FRAGMENT");
        int program = linkProgram(vertexShader, fragmentShader);

        int mvpLocation = glGetUniformLocation(program, "uMVPMatrix");
        int colorLocation = glGetUniformLocation(program, "uColor");

        logger.debug("GIZMO shader created - program: {}, mvp: {}, color: {}", program, mvpLocation, colorLocation);
        return new ShaderProgram(ShaderType.GIZMO, program, vertexShader, fragmentShader, mvpLocation, colorLocation);
    }

    /**
     * Compile a shader from source.
     */
    private int compileShader(int type, String source, String name) {
        int shader = glCreateShader(type);
        if (shader == 0) {
            throw new RuntimeException("Failed to create shader: " + name);
        }

        glShaderSource(shader, source);
        glCompileShader(shader);

        // Check compilation status
        int success = glGetShaderi(shader, GL_COMPILE_STATUS);
        if (success == GL_FALSE) {
            String infoLog = glGetShaderInfoLog(shader);
            glDeleteShader(shader);
            throw new RuntimeException("Shader compilation failed (" + name + "): " + infoLog);
        }

        logger.trace("Shader compiled successfully: {}", name);
        return shader;
    }

    /**
     * Link vertex and fragment shaders into a program.
     */
    private int linkProgram(int vertexShader, int fragmentShader) {
        int program = glCreateProgram();
        if (program == 0) {
            throw new RuntimeException("Failed to create shader program");
        }

        glAttachShader(program, vertexShader);
        glAttachShader(program, fragmentShader);
        glLinkProgram(program);

        // Check linking status
        int success = glGetProgrami(program, GL_LINK_STATUS);
        if (success == GL_FALSE) {
            String infoLog = glGetProgramInfoLog(program);
            glDeleteProgram(program);
            throw new RuntimeException("Program linking failed: " + infoLog);
        }

        logger.trace("Shader program linked successfully: {}", program);
        return program;
    }

    /**
     * Clean up all shader resources.
     */
    public void cleanup() {
        logger.debug("Cleaning up shader manager");
        for (ShaderProgram program : shaderPrograms.values()) {
            if (program != null) {
                program.cleanup();
            }
        }
        shaderPrograms.clear();
        initialized = false;
        logger.info("Shader manager cleanup complete");
    }

    /**
     * Check if shader manager is initialized.
     */
    public boolean isInitialized() {
        return initialized;
    }
}
