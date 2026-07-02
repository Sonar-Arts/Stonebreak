package com.openmason.engine.rendering.shaders;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.EnumMap;
import java.util.Map;

/**
 * Manages creation, compilation, and lifecycle of all viewport shaders.
 * Centralizes shader management following Single Responsibility Principle.
 *
 * <p>Each preset is built on the canonical {@link ShaderProgram} via its source-compilation
 * pipeline. Uniforms that callers read back as raw GL locations (via
 * {@link ShaderProgram#getUniformLocation(String)}) are explicitly registered with
 * {@link ShaderProgram#createUniform(String)}; uniforms set by name through the tolerant
 * {@code setMat4/setVec3/...} setters auto-register on first use and need no pre-declaration.
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
            shaderPrograms.put(ShaderType.VERTEX, createVertexShader());
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
     * Build a canonical {@link ShaderProgram} from vertex + fragment source.
     */
    private ShaderProgram buildProgram(String vertexSource, String fragmentSource) {
        ShaderProgram program = new ShaderProgram();
        program.createVertexShader(vertexSource);
        program.createFragmentShader(fragmentSource);
        program.link();
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

        ShaderProgram program = buildProgram(vertexShaderSource, fragmentShaderSource);
        logger.debug("BASIC shader created - program: {}", program.getProgramId());
        return program;
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
            uniform mat4 uViewMatrix;    // View matrix for camera-relative shading
            uniform vec3 uColor;
            uniform bool uUseTexture;    // Whether to use texture or solid color

            out vec3 vertexColor;
            out vec2 TexCoord;
            out vec3 fragPosView;        // View-space position for flat shading

            void main() {
                vec4 worldPos = uModelMatrix * vec4(aPos, 1.0);
                gl_Position = uMVPMatrix * worldPos;
                vertexColor = uColor;
                TexCoord = aTexCoord;
                fragPosView = (uViewMatrix * worldPos).xyz;
            }
            """;

        String fragmentShaderSource = """
            #version 330 core
            in vec3 vertexColor;
            in vec2 TexCoord;
            in vec3 fragPosView;
            out vec4 FragColor;

            uniform sampler2D uTexture;
            uniform bool uUseTexture;

            void main() {
                // Compute flat face normal from view-space position derivatives
                vec3 normal = normalize(cross(dFdx(fragPosView), dFdy(fragPosView)));

                // Camera-relative directional light (slightly above-right in view space)
                vec3 lightDir = normalize(vec3(0.3, 0.5, 0.8));
                float diffuse = max(dot(normal, lightDir), 0.0);

                // Ambient + diffuse shading (Blender-style solid mode feel)
                float lighting = 0.45 + 0.55 * diffuse;

                if (uUseTexture) {
                    vec4 texColor = texture(uTexture, TexCoord);
                    // Discard fully transparent pixels so the back face
                    // (or background) shows through erased regions.
                    if (texColor.a < 0.01) {
                        discard;
                    }
                    FragColor = vec4(texColor.rgb * lighting, texColor.a);
                } else {
                    FragColor = vec4(vertexColor * lighting, 1.0);
                }
            }
            """;

        ShaderProgram program = buildProgram(vertexShaderSource, fragmentShaderSource);

        // Register the uniforms that callers read back as raw GL locations
        // (ViewportRenderPipeline passes these locations into the block/item/SBT renderers).
        program.createUniform("uMVPMatrix");
        program.createUniform("uModelMatrix");
        program.createUniform("uTexture");
        program.createUniform("uUseTexture");

        logger.debug("MATRIX shader created - program: {}", program.getProgramId());
        return program;
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

        ShaderProgram program = buildProgram(vertexShaderSource, fragmentShaderSource);
        logger.debug("GIZMO shader created - program: {}", program.getProgramId());
        return program;
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

        ShaderProgram program = buildProgram(vertexShaderSource, fragmentShaderSource);
        logger.debug("FACE shader created - program: {}", program.getProgramId());
        return program;
    }

    /**
     * Create vertex point shader that renders circles with outlines via gl_PointCoord.
     * Replaces square GL_POINTS with smooth anti-aliased circles for a modern look.
     */
    private ShaderProgram createVertexShader() {
        logger.debug("Creating VERTEX shader program");

        String vertexShaderSource = """
            #version 330 core
            layout (location = 0) in vec3 aPos;
            layout (location = 1) in vec3 aColor;

            uniform mat4 uMVPMatrix;
            uniform float uIntensity;
            uniform float uPointSize;

            out vec3 vertexColor;
            out float vIntensity;

            void main() {
                gl_Position = uMVPMatrix * vec4(aPos, 1.0);
                gl_PointSize = uPointSize;
                vertexColor = aColor;
                vIntensity = uIntensity;
            }
            """;

        String fragmentShaderSource = """
            #version 330 core
            in vec3 vertexColor;
            in float vIntensity;
            out vec4 FragColor;

            void main() {
                // gl_PointCoord: (0,0) top-left to (1,1) bottom-right of the point sprite
                vec2 coord = gl_PointCoord * 2.0 - 1.0; // remap to [-1, 1]
                float dist = length(coord);

                // Discard outside the circle
                if (dist > 1.0) {
                    discard;
                }

                // Anti-aliased circle edge
                float outerEdge = 1.0 - smoothstep(0.85, 1.0, dist);

                // Outline ring: dark border between 0.6 and 0.85 radius
                float outlineRing = smoothstep(0.55, 0.65, dist) * (1.0 - smoothstep(0.8, 0.9, dist));

                // Compute fill color based on intensity
                vec3 fillColor;
                if (vIntensity <= 1.0) {
                    fillColor = vertexColor;
                } else {
                    // Blend toward white for hover/selection glow
                    float glowFactor = clamp((vIntensity - 1.0) / 2.0, 0.0, 1.0);
                    fillColor = mix(vertexColor, vec3(1.0), glowFactor);
                }

                // Dark outline color (semi-transparent dark version of the fill)
                vec3 outlineColor = fillColor * 0.2;

                // Combine: fill with outline ring overlay
                vec3 finalColor = mix(fillColor, outlineColor, outlineRing * 0.7);

                FragColor = vec4(finalColor, outerEdge);
            }
            """;

        ShaderProgram program = buildProgram(vertexShaderSource, fragmentShaderSource);
        logger.debug("VERTEX shader created - program: {}", program.getProgramId());
        return program;
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

            uniform float uGridScale;
            uniform float uLineWidthPx;
            uniform float uFadeDistance;
            uniform float uMaxDistance;
            uniform vec3 uMinorColor;
            uniform vec3 uMajorColor;
            uniform vec3 uAxisXColor;
            uniform vec3 uAxisZColor;
            uniform vec3 uFogColor;

            out vec4 FragColor;

            // A grid level starts fading out once its cells shrink below this many pixels
            const float MIN_PIXELS_PER_CELL = 4.0;

            // Compute depth for proper depth testing
            float computeDepth(vec3 pos) {
                vec4 clipSpacePos = fragProj * fragView * vec4(pos, 1.0);
                float ndcDepth = clipSpacePos.z / clipSpacePos.w;
                // Convert NDC depth to [0, 1] for gl_FragDepth
                return (ndcDepth + 1.0) / 2.0;
            }

            float log10(float x) {
                return log(x) / log(10.0);
            }

            // Anti-aliased coverage of a line whose center is distPx pixels away
            float lineAA(float distPx, float widthPx) {
                return 1.0 - smoothstep(widthPx * 0.5, widthPx * 0.5 + 1.0, distPx);
            }

            // Coverage of grid lines for one cell size; dudv is world units per pixel
            float gridAlpha(vec2 uv, float cellSize, vec2 dudv, float widthPx) {
                vec2 dist = abs(mod(uv + 0.5 * cellSize, cellSize) - 0.5 * cellSize) / dudv;
                return lineAA(min(dist.x, dist.y), widthPx);
            }

            void main() {
                // Compute intersection with ground plane (y = 0)
                float t = -nearPoint.y / (farPoint.y - nearPoint.y);

                // Discard if ray doesn't intersect the ground plane (looking up/down too much)
                if (t < 0.0) {
                    discard;
                }

                vec3 fragPos3D = nearPoint + t * (farPoint - nearPoint);

                // Clamp depth to valid range instead of discarding
                // This allows the grid to extend beyond the far plane
                gl_FragDepth = clamp(computeDepth(fragPos3D), 0.0, 1.0);

                vec2 uv = fragPos3D.xz;
                vec2 dudv = max(fwidth(uv), vec2(1e-6)); // world units per pixel

                // Continuous LOD: pick the power-of-ten cell size that keeps cells at
                // least MIN_PIXELS_PER_CELL wide, blending smoothly between levels so
                // the grid subdivides on zoom-in and merges on zoom-out at any scale
                float lod = max(0.0, log10(length(dudv) * MIN_PIXELS_PER_CELL / uGridScale) + 1.0);
                float lodFade = fract(lod);
                float lod0 = uGridScale * pow(10.0, floor(lod)); // finest visible level
                float lod1 = lod0 * 10.0;
                float lod2 = lod1 * 10.0;

                float a0 = gridAlpha(uv, lod0, dudv, uLineWidthPx);
                float a1 = gridAlpha(uv, lod1, dudv, uLineWidthPx);
                float a2 = gridAlpha(uv, lod2, dudv, uLineWidthPx);

                // Major lines stay solid; the finest level fades out as levels merge
                vec4 color;
                if (a2 > 0.0) {
                    color = vec4(uMajorColor, a2);
                } else if (a1 > 0.0) {
                    color = vec4(mix(uMajorColor, uMinorColor, lodFade), a1);
                } else {
                    color = vec4(uMinorColor, a0 * (1.0 - lodFade));
                }

                // Axis lines on top: slightly wider, anti-aliased in screen space
                float axisWidthPx = uLineWidthPx + 1.0;
                float xAxis = lineAA(abs(uv.y) / dudv.y, axisWidthPx); // X axis runs along z = 0
                float zAxis = lineAA(abs(uv.x) / dudv.x, axisWidthPx); // Z axis runs along x = 0
                color.rgb = mix(color.rgb, uAxisZColor, zAxis);
                color.a = max(color.a, zAxis);
                color.rgb = mix(color.rgb, uAxisXColor, xAxis);
                color.a = max(color.a, xAxis);

                // Fade relative to the camera (nearPoint sits on the near plane, which
                // is close enough) so the grid stays visible wherever the camera goes
                float viewDist = distance(fragPos3D, nearPoint);
                color.a *= 1.0 - smoothstep(uFadeDistance, uMaxDistance, viewDist);

                if (color.a <= 0.001) {
                    discard;
                }

                // Atmospheric fog toward the horizon, matched to the viewport background
                float fogFactor = smoothstep(uFadeDistance * 0.6, uMaxDistance, viewDist);
                color.rgb = mix(color.rgb, uFogColor, fogFactor);

                FragColor = color;
            }
            """;

        ShaderProgram program = buildProgram(vertexShaderSource, fragmentShaderSource);
        logger.debug("INFINITE_GRID shader created - program: {}", program.getProgramId());
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
