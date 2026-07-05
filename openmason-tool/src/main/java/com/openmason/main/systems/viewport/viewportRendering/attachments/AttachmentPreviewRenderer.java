package com.openmason.main.systems.viewport.viewportRendering.attachments;

import com.openmason.engine.rendering.shaders.ShaderProgram;
import com.stonebreak.mobs.sbe.MaterialImage;
import com.stonebreak.mobs.sbe.SbeFace;
import com.stonebreak.mobs.sbe.SbeModelGeometry;
import com.stonebreak.mobs.sbe.SbePart;
import org.joml.Matrix4f;
import org.joml.Vector4f;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.ByteBuffer;
import java.util.HashMap;
import java.util.IdentityHashMap;
import java.util.Map;

import static org.lwjgl.opengl.GL11.*;
import static org.lwjgl.opengl.GL12.GL_CLAMP_TO_EDGE;
import static org.lwjgl.opengl.GL13.GL_TEXTURE0;
import static org.lwjgl.opengl.GL13.glActiveTexture;
import static org.lwjgl.opengl.GL15.*;
import static org.lwjgl.opengl.GL20.*;
import static org.lwjgl.opengl.GL30.*;
import static org.lwjgl.system.MemoryUtil.memAlloc;
import static org.lwjgl.system.MemoryUtil.memFree;

/**
 * Draws "socket test" models in the tool viewport: a decoded accessory
 * ({@link SbeModelGeometry}, loaded via the game's attachable-asset pipeline)
 * posed at a socket's world frame, so authors can adjust the socket live
 * against the model that will actually be attached in-game.
 *
 * <p>Rest pose only — at rest every SBE part matrix equals the base matrix, so
 * the whole mesh draws with one model transform; no animation sampling needed.
 * Textured faces use their material textures; untextured assets fall back to a
 * flat tint (mirroring the game's colored fallback). GPU resources are cached
 * per geometry (assets are cached singletons in the loader) and created lazily
 * on the GL thread.
 */
public class AttachmentPreviewRenderer {

    private static final Logger logger = LoggerFactory.getLogger(AttachmentPreviewRenderer.class);

    private static final Vector4f UNTEXTURED_TINT = new Vector4f(0.85f, 0.85f, 0.85f, 1f);

    private ShaderProgram shader;
    private boolean initialized = false;

    /** GPU resources for one geometry. */
    private static final class GeometryGpu {
        int vao;
        int vbo;
        int uvVbo;
        int ebo;
        final Map<Integer, Integer> materialTextures = new HashMap<>();
    }

    private final Map<SbeModelGeometry, GeometryGpu> gpuByGeometry = new IdentityHashMap<>();

    public void initialize() {
        if (initialized) return;

        String vertexShader = """
            #version 330 core
            layout (location = 0) in vec3 aPos;
            layout (location = 1) in vec2 aTexCoord;

            uniform mat4 uMVPMatrix;

            out vec2 TexCoord;

            void main() {
                gl_Position = uMVPMatrix * vec4(aPos, 1.0);
                TexCoord = aTexCoord;
            }
            """;

        String fragmentShader = """
            #version 330 core
            in vec2 TexCoord;
            out vec4 FragColor;

            uniform sampler2D uTexture;
            uniform vec4 uColor;
            uniform int uUseTexture;

            void main() {
                FragColor = uUseTexture == 1 ? texture(uTexture, TexCoord) : uColor;
                if (FragColor.a < 0.05) discard;
            }
            """;

        try {
            shader = new ShaderProgram();
            shader.createVertexShader(vertexShader);
            shader.createFragmentShader(fragmentShader);
            shader.link();
            shader.createUniform("uMVPMatrix");
            shader.createUniform("uTexture");
            shader.createUniform("uColor");
            shader.createUniform("uUseTexture");
            initialized = true;
            logger.debug("AttachmentPreviewRenderer initialized");
        } catch (Exception e) {
            logger.error("Failed to create attachment preview shader", e);
        }
    }

    public boolean isInitialized() {
        return initialized;
    }

    /**
     * Draw one preview geometry with the given model matrix (the socket's
     * world frame — position, orientation, AND scale — composed with the
     * viewport's model-level transform by the caller).
     */
    public void render(SbeModelGeometry geometry, Matrix4f modelMatrix,
                       Matrix4f viewMatrix, Matrix4f projectionMatrix) {
        if (!initialized || geometry == null || geometry.indices().length == 0) {
            return;
        }
        GeometryGpu gpu = gpuByGeometry.computeIfAbsent(geometry, this::upload);

        int previousProgram = glGetInteger(GL_CURRENT_PROGRAM);
        int previousTexture = glGetInteger(GL_TEXTURE_BINDING_2D);
        int previousVertexArray = glGetInteger(GL_VERTEX_ARRAY_BINDING);
        boolean wasCullFaceEnabled = glIsEnabled(GL_CULL_FACE);

        glEnable(GL_DEPTH_TEST);
        glDisable(GL_CULL_FACE); // SBE meshes are authored double-sided-agnostic

        shader.bind();
        Matrix4f mvp = new Matrix4f(projectionMatrix).mul(viewMatrix).mul(modelMatrix);
        shader.setUniform("uMVPMatrix", mvp);
        shader.setUniform("uTexture", 0);
        shader.setUniform("uColor", UNTEXTURED_TINT);

        glActiveTexture(GL_TEXTURE0);
        glBindVertexArray(gpu.vao);

        boolean textured = !gpu.materialTextures.isEmpty();
        shader.setUniform("uUseTexture", textured ? 1 : 0);

        for (SbePart part : geometry.parts()) {
            for (SbeFace face : part.faces()) {
                if (textured) {
                    Integer textureId = gpu.materialTextures.get(face.materialId());
                    if (textureId == null) continue;
                    glBindTexture(GL_TEXTURE_2D, textureId);
                }
                glDrawElements(GL_TRIANGLES, face.indexCount(),
                        GL_UNSIGNED_INT, (long) face.indexStart() * Integer.BYTES);
            }
        }

        glBindVertexArray(0);
        glBindTexture(GL_TEXTURE_2D, 0);
        shader.unbind();

        if (wasCullFaceEnabled) {
            glEnable(GL_CULL_FACE);
        }
        glUseProgram(previousProgram);
        glBindTexture(GL_TEXTURE_2D, previousTexture);
        glBindVertexArray(previousVertexArray);
    }

    private GeometryGpu upload(SbeModelGeometry geometry) {
        GeometryGpu gpu = new GeometryGpu();

        gpu.vao = glGenVertexArrays();
        gpu.vbo = glGenBuffers();
        gpu.uvVbo = glGenBuffers();
        gpu.ebo = glGenBuffers();

        glBindVertexArray(gpu.vao);

        glBindBuffer(GL_ARRAY_BUFFER, gpu.vbo);
        glBufferData(GL_ARRAY_BUFFER, geometry.vertices(), GL_STATIC_DRAW);
        glVertexAttribPointer(0, 3, GL_FLOAT, false, 3 * Float.BYTES, 0);
        glEnableVertexAttribArray(0);

        glBindBuffer(GL_ARRAY_BUFFER, gpu.uvVbo);
        glBufferData(GL_ARRAY_BUFFER, geometry.texCoords(), GL_STATIC_DRAW);
        glVertexAttribPointer(1, 2, GL_FLOAT, false, 2 * Float.BYTES, 0);
        glEnableVertexAttribArray(1);

        glBindBuffer(GL_ELEMENT_ARRAY_BUFFER, gpu.ebo);
        glBufferData(GL_ELEMENT_ARRAY_BUFFER, geometry.indices(), GL_STATIC_DRAW);

        glBindVertexArray(0);

        for (Map.Entry<Integer, MaterialImage> entry : geometry.materials().entrySet()) {
            gpu.materialTextures.put(entry.getKey(), uploadTexture(entry.getValue()));
        }

        logger.debug("Uploaded preview geometry: {} indices, {} materials",
                geometry.indices().length, gpu.materialTextures.size());
        return gpu;
    }

    private int uploadTexture(MaterialImage image) {
        int textureId = glGenTextures();
        glBindTexture(GL_TEXTURE_2D, textureId);
        glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MIN_FILTER, GL_NEAREST);
        glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MAG_FILTER, GL_NEAREST);
        glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_WRAP_S, GL_CLAMP_TO_EDGE);
        glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_WRAP_T, GL_CLAMP_TO_EDGE);

        ByteBuffer pixels = memAlloc(image.rgba().length);
        pixels.put(image.rgba()).flip();
        glTexImage2D(GL_TEXTURE_2D, 0, GL_RGBA8, image.width(), image.height(), 0,
                GL_RGBA, GL_UNSIGNED_BYTE, pixels);
        memFree(pixels);

        glBindTexture(GL_TEXTURE_2D, 0);
        return textureId;
    }

    public void cleanup() {
        if (!initialized) return;
        for (GeometryGpu gpu : gpuByGeometry.values()) {
            glDeleteVertexArrays(gpu.vao);
            glDeleteBuffers(gpu.vbo);
            glDeleteBuffers(gpu.uvVbo);
            glDeleteBuffers(gpu.ebo);
            for (int textureId : gpu.materialTextures.values()) {
                glDeleteTextures(textureId);
            }
        }
        gpuByGeometry.clear();
        if (shader != null) {
            shader.cleanup();
            shader = null;
        }
        initialized = false;
    }
}
