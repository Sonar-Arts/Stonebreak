package com.stonebreak.rendering.models.entities;

import com.stonebreak.mobs.entities.Entity;
import com.stonebreak.mobs.entities.EntityType;
import org.joml.Matrix4f;
import org.joml.Vector3f;
import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL13;
import org.lwjgl.opengl.GL15;
import org.lwjgl.opengl.GL20;
import org.lwjgl.opengl.GL30;

import com.stonebreak.rendering.shaders.ShaderProgram;

import java.nio.ByteBuffer;
import java.nio.FloatBuffer;

import static org.lwjgl.system.MemoryUtil.*;

/**
 * Specialized entity renderer managed by the main Renderer.
 *
 * <p>Cows are rendered from the {@code SB_Cow.sbe} asset via {@link SbeEntityRenderer};
 * remote players use {@link RemotePlayerRenderer}; any other entity type falls
 * back to a simple textured cube.
 */
public class EntityRenderer {
    private ShaderProgram shader;
    private boolean initialized = false;

    // Simple cube model for fallback entities.
    private int simpleCubeVAO;
    private int simpleCubeVBO;
    private int simpleCubeTexVBO;

    // 1x1 white texture for the fallback cube.
    private int fallbackTexture;

    // Entity-blind renderer for SBE-driven mobs.
    private final SbeEntityRenderer sbeEntityRenderer = new SbeEntityRenderer();

    // Renderer for multiplayer remote players (cylinder).
    private final RemotePlayerRenderer remotePlayerRenderer = new RemotePlayerRenderer();

    /**
     * Initialize the entity renderer. Called by the main Renderer.
     */
    public void initialize() {
        if (initialized) return;

        createShader();
        createFallbackTexture();
        createSimpleCubeModel();
        sbeEntityRenderer.initialize();
        remotePlayerRenderer.initialize();
        initialized = true;
    }

    private void createShader() {
        String vertexShader = """
            #version 330 core
            layout (location = 0) in vec3 aPos;
            layout (location = 1) in vec2 aTexCoord;

            uniform mat4 model;
            uniform mat4 view;
            uniform mat4 projection;

            out vec2 TexCoord;
            out vec3 FragWorldPos;

            void main() {
                vec4 worldPos = model * vec4(aPos, 1.0);
                FragWorldPos = worldPos.xyz;
                gl_Position = projection * view * worldPos;
                TexCoord = aTexCoord;
            }
            """;

        String fragmentShader = """
            #version 330 core
            out vec4 FragColor;

            in vec2 TexCoord;
            in vec3 FragWorldPos;

            uniform sampler2D textureSampler;
            uniform vec3 cameraPos;
            uniform float underwaterFogDensity;
            uniform vec3 underwaterFogColor;

            void main() {
                vec4 texColor = texture(textureSampler, TexCoord);

                if (underwaterFogDensity > 0.0) {
                    float distance = length(FragWorldPos - cameraPos);
                    float fogFactor = exp(-underwaterFogDensity * distance);
                    fogFactor = clamp(fogFactor, 0.0, 1.0);
                    FragColor = mix(vec4(underwaterFogColor, texColor.a), texColor, fogFactor);
                } else {
                    FragColor = texColor;
                }
            }
            """;

        try {
            shader = new ShaderProgram();
            shader.createVertexShader(vertexShader);
            shader.createFragmentShader(fragmentShader);
            shader.link();

            shader.createUniform("model");
            shader.createUniform("view");
            shader.createUniform("projection");
            shader.createUniform("textureSampler");
            shader.createUniform("cameraPos");
            shader.createUniform("underwaterFogDensity");
            shader.createUniform("underwaterFogColor");
        } catch (Exception e) {
            System.err.println("Failed to create entity shader: " + e.getMessage());
        }
    }

    private void createFallbackTexture() {
        fallbackTexture = GL11.glGenTextures();
        GL11.glBindTexture(GL11.GL_TEXTURE_2D, fallbackTexture);
        GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_MIN_FILTER, GL11.GL_NEAREST);
        GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_MAG_FILTER, GL11.GL_NEAREST);

        ByteBuffer whitePixel = ByteBuffer.allocateDirect(4);
        whitePixel.put((byte) 255).put((byte) 255).put((byte) 255).put((byte) 255).flip();
        GL11.glTexImage2D(GL11.GL_TEXTURE_2D, 0, GL11.GL_RGBA, 1, 1, 0,
                GL11.GL_RGBA, GL11.GL_UNSIGNED_BYTE, whitePixel);
        GL11.glBindTexture(GL11.GL_TEXTURE_2D, 0);
    }

    private void createSimpleCubeModel() {
        // Simple cube for fallback entity rendering
        float[] vertices = {
            // Front face
            -0.5f, -0.5f,  0.5f,  0.5f, -0.5f,  0.5f,  0.5f,  0.5f,  0.5f,  -0.5f,  0.5f,  0.5f,
            // Back face
            -0.5f, -0.5f, -0.5f,  0.5f, -0.5f, -0.5f,  0.5f,  0.5f, -0.5f,  -0.5f,  0.5f, -0.5f,
            // Left face
            -0.5f, -0.5f, -0.5f, -0.5f, -0.5f,  0.5f, -0.5f,  0.5f,  0.5f,  -0.5f,  0.5f, -0.5f,
            // Right face
             0.5f, -0.5f, -0.5f,  0.5f, -0.5f,  0.5f,  0.5f,  0.5f,  0.5f,   0.5f,  0.5f, -0.5f,
            // Top face
            -0.5f,  0.5f, -0.5f,  0.5f,  0.5f, -0.5f,  0.5f,  0.5f,  0.5f,  -0.5f,  0.5f,  0.5f,
            // Bottom face
            -0.5f, -0.5f, -0.5f,  0.5f, -0.5f, -0.5f,  0.5f, -0.5f,  0.5f,  -0.5f, -0.5f,  0.5f
        };

        float[] texCoords = {
            0.0f, 1.0f,  1.0f, 1.0f,  1.0f, 0.0f,  0.0f, 0.0f, // Front
            0.0f, 1.0f,  1.0f, 1.0f,  1.0f, 0.0f,  0.0f, 0.0f, // Back
            0.0f, 1.0f,  1.0f, 1.0f,  1.0f, 0.0f,  0.0f, 0.0f, // Left
            0.0f, 1.0f,  1.0f, 1.0f,  1.0f, 0.0f,  0.0f, 0.0f, // Right
            0.0f, 1.0f,  1.0f, 1.0f,  1.0f, 0.0f,  0.0f, 0.0f, // Top
            0.0f, 1.0f,  1.0f, 1.0f,  1.0f, 0.0f,  0.0f, 0.0f  // Bottom
        };

        simpleCubeVAO = GL30.glGenVertexArrays();
        simpleCubeVBO = GL15.glGenBuffers();
        simpleCubeTexVBO = GL15.glGenBuffers();

        GL30.glBindVertexArray(simpleCubeVAO);

        FloatBuffer vertexBuffer = memAllocFloat(vertices.length);
        vertexBuffer.put(vertices).flip();
        GL15.glBindBuffer(GL15.GL_ARRAY_BUFFER, simpleCubeVBO);
        GL15.glBufferData(GL15.GL_ARRAY_BUFFER, vertexBuffer, GL15.GL_STATIC_DRAW);
        GL20.glVertexAttribPointer(0, 3, GL11.GL_FLOAT, false, 3 * Float.BYTES, 0);
        GL20.glEnableVertexAttribArray(0);

        FloatBuffer texCoordBuffer = memAllocFloat(texCoords.length);
        texCoordBuffer.put(texCoords).flip();
        GL15.glBindBuffer(GL15.GL_ARRAY_BUFFER, simpleCubeTexVBO);
        GL15.glBufferData(GL15.GL_ARRAY_BUFFER, texCoordBuffer, GL15.GL_STATIC_DRAW);
        GL20.glVertexAttribPointer(1, 2, GL11.GL_FLOAT, false, 2 * Float.BYTES, 0);
        GL20.glEnableVertexAttribArray(1);

        GL30.glBindVertexArray(0);

        memFree(vertexBuffer);
        memFree(texCoordBuffer);
    }

    /**
     * Render an entity. Called by the main Renderer.
     */
    public void renderEntity(Entity entity, Matrix4f viewMatrix, Matrix4f projectionMatrix) {
        renderEntity(entity, viewMatrix, projectionMatrix, null, null);
    }

    /**
     * Render an entity with underwater fog support.
     *
     * @param entity           The entity to render
     * @param viewMatrix       The view matrix
     * @param projectionMatrix The projection matrix
     * @param world            The world (for underwater detection), can be null
     * @param cameraPos        The camera position (for fog distance), can be null
     */
    public void renderEntity(Entity entity, Matrix4f viewMatrix, Matrix4f projectionMatrix,
                            com.stonebreak.world.World world, Vector3f cameraPos) {
        if (!initialized || !entity.isAlive()) return;

        EntityType entityType = entity.getType();

        if (entityType == EntityType.REMOTE_PLAYER
                && entity instanceof com.stonebreak.mobs.entities.RemotePlayer rp) {
            remotePlayerRenderer.render(rp, viewMatrix, projectionMatrix);
            return;
        }

        if (entityType == EntityType.COW && entity instanceof com.stonebreak.mobs.cow.Cow cow) {
            // The SBE asset comes from the registry by the entity type's object
            // id; only the variant and AI-state → animation-state mapping are
            // cow-specific. The renderer itself stays entity-blind.
            sbeEntityRenderer.render(
                    com.stonebreak.mobs.sbe.SbeEntityRegistry.get(entityType.getSbeObjectId()),
                    cow.getTextureVariant(),
                    com.stonebreak.mobs.sbe.CowStateMapping.sbeState(cow.getAI().getCurrentState()),
                    cow.getAnimationController().getTotalAnimationTime(),
                    cow.getPosition(),
                    cow.getRotation().y,
                    cow.getScale(),
                    viewMatrix, projectionMatrix, world, cameraPos);
            return;
        }

        renderSimpleEntity(entity, viewMatrix, projectionMatrix, world, cameraPos);
    }

    private void renderSimpleEntity(Entity entity, Matrix4f viewMatrix, Matrix4f projectionMatrix,
                                    com.stonebreak.world.World world, Vector3f cameraPos) {
        shader.bind();

        GL13.glActiveTexture(GL13.GL_TEXTURE0);
        GL11.glBindTexture(GL11.GL_TEXTURE_2D, fallbackTexture);
        shader.setUniform("textureSampler", 0);

        shader.setUniform("view", viewMatrix);
        shader.setUniform("projection", projectionMatrix);

        float fogDensity = 0.0f;
        Vector3f fogColor = new Vector3f(0.1f, 0.3f, 0.5f);
        if (world != null && cameraPos != null
                && world.isPositionUnderwater((int) Math.floor(cameraPos.x),
                        (int) Math.floor(cameraPos.y), (int) Math.floor(cameraPos.z))) {
            fogDensity = 0.15f;
        }

        shader.setUniform("cameraPos", cameraPos != null ? cameraPos : new Vector3f(0, 0, 0));
        shader.setUniform("underwaterFogDensity", fogDensity);
        shader.setUniform("underwaterFogColor", fogColor);

        Matrix4f modelMatrix = new Matrix4f()
            .translate(entity.getPosition())
            .rotateY((float) Math.toRadians(entity.getRotation().y))
            .scale(entity.getScale());

        shader.setUniform("model", modelMatrix);

        GL30.glBindVertexArray(simpleCubeVAO);
        GL11.glDrawArrays(GL11.GL_QUADS, 0, 24); // 6 faces × 4 vertices
        GL30.glBindVertexArray(0);

        GL11.glBindTexture(GL11.GL_TEXTURE_2D, 0);
        shader.unbind();
    }

    /**
     * Cleanup method called by the main Renderer.
     */
    public void cleanup() {
        if (!initialized) return;

        if (shader != null) {
            shader.cleanup();
        }
        if (simpleCubeVAO != 0) {
            GL30.glDeleteVertexArrays(simpleCubeVAO);
        }
        if (simpleCubeVBO != 0) {
            GL15.glDeleteBuffers(simpleCubeVBO);
        }
        if (simpleCubeTexVBO != 0) {
            GL15.glDeleteBuffers(simpleCubeTexVBO);
        }
        if (fallbackTexture != 0) {
            GL11.glDeleteTextures(fallbackTexture);
        }

        sbeEntityRenderer.cleanup();
        remotePlayerRenderer.cleanup();
        initialized = false;
    }
}
