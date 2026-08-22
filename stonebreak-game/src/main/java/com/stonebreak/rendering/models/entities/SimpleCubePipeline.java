package com.stonebreak.rendering.models.entities;

import com.openmason.engine.rendering.shaders.ShaderProgram;
import com.stonebreak.mobs.entities.Entity;
import org.joml.Matrix4f;
import org.joml.Vector3f;
import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL13;
import org.lwjgl.opengl.GL15;
import org.lwjgl.opengl.GL20;
import org.lwjgl.opengl.GL30;

import java.nio.FloatBuffer;

import static org.lwjgl.system.MemoryUtil.memAllocFloat;
import static org.lwjgl.system.MemoryUtil.memFree;

/**
 * The shared GL resources behind every non-SBE entity draw: the textured
 * "simple cube" shader (opt-in sun/world lighting + underwater fog) and the
 * unit cube VAO it draws. {@link FallbackCubeRenderer}, {@link ArrowRenderer}
 * and {@link GlowCubeRenderer} are thin clients of this pipeline; it owns no
 * per-entity textures. Call sequence per draw: {@link #begin} → {@link #setModel}
 * → {@link #bindCube} → {@link #drawCube} → {@link #unbindCube} → {@link #end}.
 */
final class SimpleCubePipeline {
    private ShaderProgram shader;

    private int simpleCubeVAO;
    private int simpleCubeVBO;
    private int simpleCubeTexVBO;

    void initialize() {
        createShader();
        createSimpleCubeModel();
    }

    /**
     * Binds the shader, the texture on unit 0, and uploads the per-draw uniforms
     * shared by every simple-cube client (in the order the original per-entity
     * methods set them).
     */
    void begin(int texture, Matrix4f viewMatrix, Matrix4f projectionMatrix,
               Vector3f cameraPos, float fogDensity, Vector3f fogColor,
               Entity entity, boolean lit) {
        shader.bind();

        GL13.glActiveTexture(GL13.GL_TEXTURE0);
        GL11.glBindTexture(GL11.GL_TEXTURE_2D, texture);
        shader.setUniform("textureSampler", 0);
        shader.setUniform("view", viewMatrix);
        shader.setUniform("projection", projectionMatrix);
        shader.setUniform("cameraPos", cameraPos);
        shader.setUniform("underwaterFogDensity", fogDensity);
        shader.setUniform("underwaterFogColor", fogColor);
        applySimpleLighting(entity, lit);
    }

    /** Sets the model matrix for the next {@link #drawCube()} (shader must be bound). */
    void setModel(Matrix4f modelMatrix) {
        shader.setUniform("model", modelMatrix);
    }

    void bindCube() {
        GL30.glBindVertexArray(simpleCubeVAO);
    }

    /** Draws the 6 quad faces of the bound cube VAO. */
    void drawCube() {
        GL11.glDrawArrays(GL11.GL_QUADS, 0, 24); // 6 faces × 4 vertices
    }

    void unbindCube() {
        GL30.glBindVertexArray(0);
    }

    /** Unbinds the texture and shader after a draw. */
    void end() {
        GL11.glBindTexture(GL11.GL_TEXTURE_2D, 0);
        shader.unbind();
    }

    /**
     * Sets the simple-cube shader's lighting mode for the next draw. Lit geometry
     * (fallback cubes, arrows) samples the world sky light at the entity position;
     * emissive geometry (fire bolts, glow cubes) stays unlit. The shader must be bound.
     */
    private void applySimpleLighting(Entity entity, boolean lit) {
        shader.setBool("u_lightingEnabled", lit);
        if (!lit) {
            return;
        }
        com.stonebreak.world.TimeOfDay timeOfDay = com.stonebreak.core.Game.getTimeOfDay();
        if (timeOfDay != null) {
            shader.setFloat("u_ambientLight", timeOfDay.getAmbientLightLevel());
            shader.setVec3("u_sunDirection", timeOfDay.getSunDirection());
        } else {
            shader.setFloat("u_ambientLight", 1.0f);
            shader.setVec3("u_sunDirection", new Vector3f(0.4f, 0.8f, 0.4f).normalize());
        }
        shader.setFloat("u_entityLight",
                SbeEntityRenderer.sampleEntityLight(com.stonebreak.core.Game.getWorld(), entity.getPosition()));
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
            // Lighting is opt-in: emissive geometry (fire bolts, glow cubes)
            // keeps rendering unlit; plain entities get sun + world lighting.
            uniform bool u_lightingEnabled;
            uniform float u_ambientLight;
            uniform vec3 u_sunDirection;
            uniform float u_entityLight;

            void main() {
                vec4 texColor = texture(textureSampler, TexCoord);

                if (u_lightingEnabled) {
                    // Flat face normal from screen-space derivatives (no normal attribute).
                    vec3 normal = normalize(cross(dFdx(FragWorldPos), dFdy(FragWorldPos)));
                    float diff = max(dot(normal, normalize(u_sunDirection)), 0.0);
                    float brightness = u_ambientLight * (0.5 + 0.55 * diff);
                    brightness *= mix(0.3, 1.0, u_entityLight);
                    texColor = vec4(texColor.rgb * min(brightness, 1.0), texColor.a);
                }

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
            shader.bind();
            shader.setBool("u_lightingEnabled", false);
            shader.setFloat("u_ambientLight", 1.0f);
            shader.setVec3("u_sunDirection", new Vector3f(0.4f, 0.8f, 0.4f).normalize());
            shader.setFloat("u_entityLight", 1.0f);
            shader.unbind();
        } catch (Exception e) {
            System.err.println("Failed to create entity shader: " + e.getMessage());
        }
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

    void cleanup() {
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
    }
}
