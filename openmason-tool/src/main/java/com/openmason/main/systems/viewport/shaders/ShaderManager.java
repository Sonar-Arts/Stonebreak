package com.openmason.main.systems.viewport.shaders;

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
            shaderPrograms.put(ShaderType.FACE, createFaceShader());
            shaderPrograms.put(ShaderType.INFINITE_GRID, createInfiniteGridShader());

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
     * Create basic shader for simple geometry (vertices, grid, test cube).
     * Uses intensity multiplier for hover highlighting (same pattern as gizmo shader).
     */
    private ShaderProgram createBasicShader() {
        logger.debug("Creating BASIC shader program");

        String vertexShaderSource = """
            #version 330 core
            layout (location = 0) in vec3 aPos;
            layout (location = 1) in vec3 aColor;

            uniform mat4 uMVPMatrix;
            uniform float uIntensity;

            out vec3 vertexColor;

            void main() {
                gl_Position = uMVPMatrix * vec4(aPos, 1.0);
                vertexColor = aColor * uIntensity;
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
        int colorLocation = -1; // Not used for intensity-based highlighting

        logger.debug("BASIC shader created - program: {}, mvp: {}", program, mvpLocation);
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

            uniform mat4 uMVPMatrix;     // View-Projection matrix (viewportCamera)
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
                    // Sample texture color
                    vec4 texColor = texture(uTexture, TexCoord);

                    // Discard fully transparent pixels to prevent rendering artifacts
                    // This is critical for multi-layer composited textures where some areas
                    // may be transparent (alpha=0) from the PixelCanvas initialization
                    if (texColor.a < 0.01) {
                        discard;
                    }

                    FragColor = texColor;
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
     * Create face selection shader with alpha transparency support.
     * Used for rendering face overlays with hover and selection highlighting.
     */
    private ShaderProgram createFaceShader() {
        logger.debug("Creating FACE shader program");

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

            uniform float uAlpha;  // Alpha transparency for face overlays

            void main() {
                FragColor = vec4(vertexColor, uAlpha);
            }
            """;

        int vertexShader = compileShader(GL_VERTEX_SHADER, vertexShaderSource, "FACE_VERTEX");
        int fragmentShader = compileShader(GL_FRAGMENT_SHADER, fragmentShaderSource, "FACE_FRAGMENT");
        int program = linkProgram(vertexShader, fragmentShader);

        int mvpLocation = glGetUniformLocation(program, "uMVPMatrix");
        int colorLocation = glGetUniformLocation(program, "uColor");

        logger.debug("FACE shader created - program: {}, mvp: {}, color: {}", program, mvpLocation, colorLocation);
        return new ShaderProgram(ShaderType.FACE, program, vertexShader, fragmentShader, mvpLocation, colorLocation);
    }

    /**
     * Create infinite grid shader for procedurally generated infinite grid.
     * Renders a Blender-style infinite grid that fades with distance.
     */
    private ShaderProgram createInfiniteGridShader() {
        logger.debug("Creating INFINITE_GRID shader program");

        String vertexShaderSource = """
            #version 330 core
            layout (location = 0) in vec3 aPos;
            layout (location = 1) in vec2 aUV;

            uniform mat4 uViewMatrix;
            uniform mat4 uProjectionMatrix;

            out vec3 nearPoint;
            out vec3 farPoint;
            out mat4 fragView;
            out mat4 fragProj;

            // Unproject point from NDC to world space
            vec3 unprojectPoint(float x, float y, float z, mat4 view, mat4 projection) {
                mat4 viewInv = inverse(view);
                mat4 projInv = inverse(projection);
                vec4 unprojectedPoint = viewInv * projInv * vec4(x, y, z, 1.0);
                return unprojectedPoint.xyz / unprojectedPoint.w;
            }

            void main() {
                // Pass through position for full-screen quad
                gl_Position = vec4(aPos.xy, 0.0, 1.0);

                // Compute near and far points in world space
                nearPoint = unprojectPoint(aPos.x, aPos.y, 0.0, uViewMatrix, uProjectionMatrix);
                farPoint = unprojectPoint(aPos.x, aPos.y, 1.0, uViewMatrix, uProjectionMatrix);

                // Pass matrices to fragment shader
                fragView = uViewMatrix;
                fragProj = uProjectionMatrix;
            }
            """;

        String fragmentShaderSource = """
            #version 330 core

            in vec3 nearPoint;
            in vec3 farPoint;
            in mat4 fragView;
            in mat4 fragProj;

            uniform vec3 uCameraPosition;
            uniform float uGridScale;
            uniform float uGridLineWidth;
            uniform float uFadeDistance;
            uniform float uMaxDistance;
            uniform vec3 uPrimaryColor;
            uniform vec3 uSecondaryColor;
            uniform vec3 uAxisXColor;
            uniform vec3 uAxisZColor;
            uniform vec3 uFogColor;

            out vec4 FragColor;

            // Compute depth for proper depth testing
            float computeDepth(vec3 pos) {
                vec4 clipSpacePos = fragProj * fragView * vec4(pos, 1.0);
                float ndcDepth = clipSpacePos.z / clipSpacePos.w;
                // Convert NDC depth to [0, 1] for gl_FragDepth
                return (ndcDepth + 1.0) / 2.0;
            }

            // Compute multi-scale grid pattern with adaptive LOD like Blender
            vec4 grid(vec3 fragPos3D, float scale) {
                vec2 coord = fragPos3D.xz / scale;
                vec2 derivative = fwidth(coord);

                // Multi-scale grid (Blender-style: 1x, 10x, 100x)
                vec2 coord1 = coord;                    // Base grid (1x)
                vec2 coord10 = coord / 10.0;            // 10x larger grid

                // Calculate derivatives for each level
                vec2 deriv1 = derivative;
                vec2 deriv10 = derivative / 10.0;

                // Compute grid lines for each scale
                vec2 grid1 = abs(fract(coord1 - 0.5) - 0.5) / deriv1;
                vec2 grid10 = abs(fract(coord10 - 0.5) - 0.5) / deriv10;

                float line1 = min(grid1.x, grid1.y);
                float line10 = min(grid10.x, grid10.y);

                // Convert to line intensity (0 at line, 1 away from line)
                float gridLine1 = 1.0 - min(line1, 1.0);
                float gridLine10 = 1.0 - min(line10, 1.0);

                // Fade factors based on cell size
                // Small cells (1x) fade out when they get too small
                float fade1 = 1.0 - smoothstep(0.5, 2.0, max(deriv1.x, deriv1.y));

                // Large cells (10x) fade in when small cells fade out
                float fade10 = 1.0 - smoothstep(2.0, 5.0, max(deriv10.x, deriv10.y));

                // Apply smoothstep to create sharp lines
                gridLine1 = smoothstep(0.0, 0.15, gridLine1) * fade1;
                gridLine10 = smoothstep(0.0, 0.15, gridLine10) * fade10;

                // Combine both grid levels
                // 10x grid is slightly brighter/more opaque
                float finalGridLine = max(gridLine1 * 0.6, gridLine10);

                vec4 color = vec4(uPrimaryColor, finalGridLine);

                // Add axis lines (X and Z) - always visible, override grid
                // X-axis (red) - along the X direction at Z=0
                if (abs(fragPos3D.z) < uGridLineWidth * scale) {
                    color.rgb = uAxisXColor;
                    color.a = 1.0;
                }
                // Z-axis (blue) - along the Z direction at X=0
                if (abs(fragPos3D.x) < uGridLineWidth * scale) {
                    color.rgb = uAxisZColor;
                    color.a = 1.0;
                }

                return color;
            }

            void main() {
                // Compute intersection with ground plane (y = 0)
                float t = -nearPoint.y / (farPoint.y - nearPoint.y);

                // Discard if ray doesn't intersect the ground plane (looking up/down too much)
                if (t < 0.0) {
                    discard;
                }

                vec3 fragPos3D = nearPoint + t * (farPoint - nearPoint);

                // Compute depth
                float depth = computeDepth(fragPos3D);

                // Clamp depth to valid range instead of discarding
                // This allows the grid to extend beyond the far plane
                gl_FragDepth = clamp(depth, 0.0, 1.0);

                // Distance-based fading (centered on world origin where object is located)
                vec3 worldOrigin = vec3(0.0, 0.0, 0.0);
                float distanceFromOrigin = length(fragPos3D - worldOrigin);
                float fadeFactor = 1.0 - smoothstep(uFadeDistance, uMaxDistance, distanceFromOrigin);

                // Compute grid
                vec4 gridColor = grid(fragPos3D, uGridScale);

                // Apply distance fade to alpha
                gridColor.a *= fadeFactor;

                // Discard only completely empty grid squares (between grid lines)
                // This allows smooth alpha fade without hard cutoff
                if (gridColor.a <= 0.0) {
                    discard;
                }

                // Apply atmospheric fog - blend grid color with fog color based on distance from origin
                // Fog starts at fadeDistance and fully covers at maxDistance
                // This creates a vignette effect around the object at world origin
                float fogStart = uFadeDistance * 0.5; // Start fog earlier for smoother transition
                float fogFactor = smoothstep(fogStart, uMaxDistance, distanceFromOrigin);

                // Blend grid color with fog color
                vec3 finalColor = mix(gridColor.rgb, uFogColor, fogFactor);

                // Output final color with fog applied
                FragColor = vec4(finalColor, gridColor.a);
            }
            """;

        int vertexShader = compileShader(GL_VERTEX_SHADER, vertexShaderSource, "INFINITE_GRID_VERTEX");
        int fragmentShader = compileShader(GL_FRAGMENT_SHADER, fragmentShaderSource, "INFINITE_GRID_FRAGMENT");
        int program = linkProgram(vertexShader, fragmentShader);

        // Get uniform locations (using -1 for unused uniforms to match constructor)
        int mvpLocation = -1;  // Not used in infinite grid shader
        int colorLocation = -1; // Not used in infinite grid shader

        logger.debug("INFINITE_GRID shader created - program: {}", program);
        return new ShaderProgram(ShaderType.INFINITE_GRID, program, vertexShader, fragmentShader, mvpLocation, colorLocation);
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
