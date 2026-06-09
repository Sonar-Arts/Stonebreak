package com.openmason.engine.rendering.sky.clouds;

// Standard Library Imports
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;

// JOML Math Library
import org.joml.Matrix4f;
import org.joml.Vector3f;

// LWJGL OpenGL Static Imports
import static org.lwjgl.opengl.GL11.*;
import static org.lwjgl.opengl.GL15.*;
import static org.lwjgl.opengl.GL20.*;
import static org.lwjgl.opengl.GL30.*;

// Project Imports
import com.openmason.engine.rendering.shaders.ShaderProgram;

/**
 * Renderer for the Minecraft-style voxel cloud layer.
 *
 * <p>A single static mesh of blocky cloud cells is built once at construction
 * (see {@link CloudPattern} and {@link CloudMeshBuilder}). Because the pattern
 * is toroidal, the mesh is drawn in a 3x3 tiling around the camera so the cloud
 * layer appears seamless and infinite. Clouds drift continuously and are tinted
 * by a caller-supplied ambient light level so they darken at night.</p>
 *
 * <p>This is a sibling renderer to {@code SkyRenderer}; it is typically owned and
 * invoked by the world renderer immediately after the sky dome is drawn.</p>
 */
public class CloudRenderer {

    // --- Tunable cloud layer parameters --------------------------------------

    /** Grid width/height in cloud cells. */
    private static final int GRID_SIZE = 64;
    /** Fraction of sky covered by clouds. */
    private static final float COVERAGE = 0.45f;
    /** Seed for the (fixed) cloud formation pattern. */
    private static final long PATTERN_SEED = 20260517L;
    /** Altitude of the cloud layer, in world units (world is 256 tall). */
    private static final float CLOUD_Y = 192.0f;
    /** Horizontal drift speed of the cloud layer, in units per second. */
    private static final float DRIFT_SPEED = 0.6f;
    /** Base opacity of the clouds. */
    private static final float CLOUD_ALPHA = 0.8f;

    /** Total horizontal span of one mesh tile, in world units. */
    private static final float TILE_EXTENT = GRID_SIZE * CloudMeshBuilder.CELL_WIDTH;

    // --- GPU resources -------------------------------------------------------

    private int cloudVAO;
    private int cloudVBO;
    private int cloudEBO;
    private int indexCount;

    private ShaderProgram cloudShaderProgram;

    // Reused per-frame to avoid allocations.
    private final Matrix4f modelMatrix = new Matrix4f();
    private final Vector3f cloudColor = new Vector3f();

    public CloudRenderer() {
        CloudPattern pattern = new CloudPattern(GRID_SIZE, COVERAGE, PATTERN_SEED);
        CloudMeshBuilder.CloudMeshData mesh = CloudMeshBuilder.build(pattern);
        initializeOpenGLResources(mesh);
        initializeShaders();
    }

    private void initializeOpenGLResources(CloudMeshBuilder.CloudMeshData mesh) {
        this.indexCount = mesh.indexCount();

        cloudVAO = glGenVertexArrays();
        glBindVertexArray(cloudVAO);

        cloudVBO = glGenBuffers();
        glBindBuffer(GL_ARRAY_BUFFER, cloudVBO);
        glBufferData(GL_ARRAY_BUFFER, mesh.vertices(), GL_STATIC_DRAW);

        cloudEBO = glGenBuffers();
        glBindBuffer(GL_ELEMENT_ARRAY_BUFFER, cloudEBO);
        glBufferData(GL_ELEMENT_ARRAY_BUFFER, mesh.indices(), GL_STATIC_DRAW);

        int stride = CloudMeshBuilder.FLOATS_PER_VERTEX * Float.BYTES;
        // Attribute 0: position (x, y, z)
        glVertexAttribPointer(0, 3, GL_FLOAT, false, stride, 0);
        glEnableVertexAttribArray(0);
        // Attribute 1: flat face-shade factor
        glVertexAttribPointer(1, 1, GL_FLOAT, false, stride, 3 * Float.BYTES);
        glEnableVertexAttribArray(1);

        glBindVertexArray(0);
    }

    private void initializeShaders() {
        cloudShaderProgram = new ShaderProgram();
        try {
            cloudShaderProgram.createVertexShader(loadShaderSource("/shaders/clouds/clouds.vert"));
            cloudShaderProgram.createFragmentShader(loadShaderSource("/shaders/clouds/clouds.frag"));
            cloudShaderProgram.link();

            cloudShaderProgram.createUniform("projectionMatrix");
            cloudShaderProgram.createUniform("viewMatrix");
            cloudShaderProgram.createUniform("modelMatrix");
            cloudShaderProgram.createUniform("cameraPosition");
            cloudShaderProgram.createUniform("cloudColor");
            cloudShaderProgram.createUniform("cloudAlpha");
        } catch (Exception e) {
            throw new RuntimeException("Failed to initialize cloud shaders", e);
        }
    }

    private String loadShaderSource(String resourcePath) throws IOException {
        try (InputStream inputStream = getClass().getResourceAsStream(resourcePath)) {
            if (inputStream == null) {
                throw new IOException("Shader resource not found: " + resourcePath);
            }
            return new String(inputStream.readAllBytes(), StandardCharsets.UTF_8);
        }
    }

    /**
     * Renders the cloud layer. Should be called immediately after the sky dome,
     * before world geometry.
     *
     * @param projectionMatrix  the projection matrix
     * @param viewMatrix        the camera view matrix
     * @param cameraPosition    the camera position
     * @param totalTime         total elapsed game time, in seconds
     * @param ambientLightLevel ambient light level (0..1) driving the cloud tint
     */
    public void renderClouds(Matrix4f projectionMatrix, Matrix4f viewMatrix,
                             Vector3f cameraPosition, float totalTime, float ambientLightLevel) {
        // Save current OpenGL state.
        boolean depthTestEnabled = glIsEnabled(GL_DEPTH_TEST);
        boolean cullFaceEnabled = glIsEnabled(GL_CULL_FACE);
        boolean blendEnabled = glIsEnabled(GL_BLEND);
        boolean depthMaskEnabled = glGetBoolean(GL_DEPTH_WRITEMASK);
        int currentDepthFunc = glGetInteger(GL_DEPTH_FUNC);
        int currentBlendSrc = glGetInteger(GL_BLEND_SRC);
        int currentBlendDst = glGetInteger(GL_BLEND_DST);

        // Configure state for translucent cloud rendering.
        glEnable(GL_DEPTH_TEST);
        glDepthFunc(GL_LEQUAL); // Draw over the sky dome (which sits at max depth).
        glDepthMask(false); // Translucent: test depth but don't write it.
        glEnable(GL_CULL_FACE);
        glCullFace(GL_BACK);
        glEnable(GL_BLEND);
        glBlendFunc(GL_SRC_ALPHA, GL_ONE_MINUS_SRC_ALPHA);

        cloudShaderProgram.bind();
        cloudShaderProgram.setUniform("projectionMatrix", projectionMatrix);
        cloudShaderProgram.setUniform("viewMatrix", viewMatrix);
        cloudShaderProgram.setUniform("cameraPosition", cameraPosition);
        cloudShaderProgram.setUniform("cloudColor", computeCloudColor(ambientLightLevel));
        cloudShaderProgram.setUniform("cloudAlpha", CLOUD_ALPHA);

        // Drift wraps at the tile extent so the toroidal pattern stays seamless.
        float drift = (totalTime * DRIFT_SPEED) % TILE_EXTENT;
        float baseX = (float) Math.floor((cameraPosition.x - drift) / TILE_EXTENT) * TILE_EXTENT;
        float baseZ = (float) Math.floor(cameraPosition.z / TILE_EXTENT) * TILE_EXTENT;

        glBindVertexArray(cloudVAO);
        // Draw a 3x3 block of tiles so the camera is always covered.
        for (int ti = -1; ti <= 1; ti++) {
            for (int tj = -1; tj <= 1; tj++) {
                float originX = baseX + ti * TILE_EXTENT + drift;
                float originZ = baseZ + tj * TILE_EXTENT;
                modelMatrix.identity().translate(originX, CLOUD_Y, originZ);
                cloudShaderProgram.setUniform("modelMatrix", modelMatrix);
                glDrawElements(GL_TRIANGLES, indexCount, GL_UNSIGNED_INT, 0);
            }
        }
        glBindVertexArray(0);

        cloudShaderProgram.unbind();

        // Restore previous OpenGL state.
        if (depthTestEnabled) {
            glEnable(GL_DEPTH_TEST);
        } else {
            glDisable(GL_DEPTH_TEST);
        }
        if (cullFaceEnabled) {
            glEnable(GL_CULL_FACE);
        } else {
            glDisable(GL_CULL_FACE);
        }
        if (blendEnabled) {
            glEnable(GL_BLEND);
            glBlendFunc(currentBlendSrc, currentBlendDst);
        } else {
            glDisable(GL_BLEND);
        }
        glDepthMask(depthMaskEnabled);
        glDepthFunc(currentDepthFunc);
    }

    /**
     * Derives the cloud tint from the ambient light level: bright white during the
     * day, darkening toward a dim blue-grey at night.
     */
    private Vector3f computeCloudColor(float ambientLightLevel) {
        // Night clouds: dim blue-grey. Day clouds: near-white.
        float r = lerp(0.18f, 1.0f, ambientLightLevel);
        float g = lerp(0.19f, 1.0f, ambientLightLevel);
        float b = lerp(0.26f, 1.0f, ambientLightLevel);
        return cloudColor.set(r, g, b);
    }

    private static float lerp(float a, float b, float t) {
        return a + (b - a) * t;
    }

    /** Clean up OpenGL resources. */
    public void cleanup() {
        if (cloudShaderProgram != null) {
            cloudShaderProgram.cleanup();
        }
        if (cloudVAO != 0) {
            glDeleteVertexArrays(cloudVAO);
        }
        if (cloudVBO != 0) {
            glDeleteBuffers(cloudVBO);
        }
        if (cloudEBO != 0) {
            glDeleteBuffers(cloudEBO);
        }
    }
}
