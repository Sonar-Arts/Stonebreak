package com.stonebreak.rendering.models.entities;

import com.openmason.engine.diagnostics.GpuMemoryTracker;
import com.openmason.engine.format.oma.AnimSampler;
import com.openmason.engine.format.oma.ParsedAnimClip;
import com.openmason.engine.format.oma.ParsedAnimTrack;
import com.stonebreak.mobs.sbe.MaterialImage;
import com.stonebreak.mobs.sbe.SbeEntityAsset;
import com.stonebreak.mobs.sbe.SbeFace;
import com.stonebreak.mobs.sbe.SbeModelGeometry;
import com.stonebreak.mobs.sbe.SbePart;
import com.stonebreak.rendering.shaders.ShaderProgram;
import org.joml.Matrix4f;
import org.joml.Vector3f;
import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL12;
import org.lwjgl.opengl.GL13;
import org.lwjgl.opengl.GL15;
import org.lwjgl.opengl.GL20;
import org.lwjgl.opengl.GL30;

import java.nio.ByteBuffer;
import java.nio.FloatBuffer;
import java.nio.IntBuffer;
import java.util.HashMap;
import java.util.IdentityHashMap;
import java.util.Map;

import static org.lwjgl.system.MemoryUtil.memAllocFloat;
import static org.lwjgl.system.MemoryUtil.memAllocInt;
import static org.lwjgl.system.MemoryUtil.memAlloc;
import static org.lwjgl.system.MemoryUtil.memFree;

/**
 * Entity-blind renderer for SBE-driven mobs.
 *
 * <p>Given a decoded {@link SbeEntityAsset} plus a variant name, a state name,
 * an animation time and a world transform, it renders the model — selecting the
 * variant geometry, sampling the state's animation clip per part, and drawing
 * each face with its own material texture. It knows nothing about cows or any
 * specific entity type; callers supply the entity-specific bindings.
 *
 * <p>GPU resources are uploaded lazily, per asset, on first render (on the GL
 * thread). Owned and driven by {@link EntityRenderer}.
 */
public final class SbeEntityRenderer {

    private ShaderProgram shader;
    private boolean initialized;
    private long trackedMeshBytes;
    private long trackedTextureBytes;

    /** GPU resources for one variant of one asset. */
    private static final class VariantGpu {
        int vao;
        int vbo;
        int uvVbo;
        int ebo;
        final Map<Integer, Integer> materialTextures = new HashMap<>();
    }

    /** Per-asset GPU resources, keyed by asset identity (assets are cached singletons). */
    private final Map<SbeEntityAsset, Map<String, VariantGpu>> assetGpu = new IdentityHashMap<>();

    /** Initialize the shared shader. Must run on the GL thread. */
    public void initialize() {
        if (initialized) return;
        createShader();
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
                if (texColor.a < 0.01) discard;

                if (underwaterFogDensity > 0.0) {
                    float dist = length(FragWorldPos - cameraPos);
                    float fogFactor = clamp(exp(-underwaterFogDensity * dist), 0.0, 1.0);
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
            System.err.println("Failed to create SBE entity shader: " + e.getMessage());
        }
    }

    /**
     * Render one SBE entity.
     *
     * @param asset         decoded SBE asset
     * @param variantName   appearance variant (case-insensitive; unknown → default)
     * @param stateName     SBE animation-state name (unknown/null → rest pose)
     * @param animationTime elapsed clip time in seconds
     * @param position      world position the model origin is placed at
     * @param yawDegrees    Y-axis rotation in degrees
     * @param scale         world scale
     * @param viewMatrix    camera view matrix
     * @param projectionMatrix camera projection matrix
     * @param world         world, for underwater fog detection (may be null)
     * @param cameraPos     camera position, for fog distance (may be null)
     */
    public void render(SbeEntityAsset asset, String variantName, String stateName,
                       float animationTime, Vector3f position, float yawDegrees,
                       Vector3f scale, Matrix4f viewMatrix, Matrix4f projectionMatrix,
                       com.stonebreak.world.World world, Vector3f cameraPos) {
        if (!initialized || asset == null) return;

        SbeModelGeometry geometry = asset.geometryFor(variantName);
        VariantGpu gpu = resolveVariantGpu(asset, variantName);
        if (geometry == null || gpu == null) return;

        ParsedAnimClip clip = asset.clipFor(stateName);
        float clipTime = 0f;
        Map<String, ParsedAnimTrack> tracksById = Map.of();
        if (clip != null) {
            clipTime = AnimSampler.wrapTime(animationTime, clip.duration(), clip.loop());
            tracksById = clip.trackByPartId();
        }

        // Save GL state.
        int previousProgram = GL11.glGetInteger(GL20.GL_CURRENT_PROGRAM);
        int previousTexture = GL11.glGetInteger(GL11.GL_TEXTURE_BINDING_2D);
        int previousVertexArray = GL11.glGetInteger(GL30.GL_VERTEX_ARRAY_BINDING);
        boolean wasCullFaceEnabled = GL11.glIsEnabled(GL11.GL_CULL_FACE);

        GL11.glEnable(GL11.GL_DEPTH_TEST);
        GL11.glDepthFunc(GL11.GL_LESS);
        GL11.glDisable(GL11.GL_CULL_FACE);

        shader.bind();
        shader.setUniform("view", viewMatrix);
        shader.setUniform("projection", projectionMatrix);
        shader.setUniform("textureSampler", 0);

        float fogDensity = 0.0f;
        Vector3f fogColor = new Vector3f(0.1f, 0.3f, 0.5f);
        if (world != null && cameraPos != null
                && world.isPositionUnderwater((int) Math.floor(cameraPos.x),
                        (int) Math.floor(cameraPos.y), (int) Math.floor(cameraPos.z))) {
            fogDensity = 0.15f;
        }
        shader.setUniform("cameraPos", cameraPos != null ? cameraPos : new Vector3f());
        shader.setUniform("underwaterFogDensity", fogDensity);
        shader.setUniform("underwaterFogColor", fogColor);

        // Base transform: the OMO model is placed exactly as authored in the
        // SBE — its model-space origin sits at the entity position, with no
        // re-anchoring. Part transforms come straight from the OMO/animation.
        Matrix4f base = new Matrix4f()
                .translate(position.x, position.y, position.z)
                .rotateY((float) Math.toRadians(yawDegrees))
                .scale(scale);

        GL13.glActiveTexture(GL13.GL_TEXTURE0);
        GL30.glBindVertexArray(gpu.vao);

        // The OMO mesh vertices are baked in model space — each part already
        // sits at its rest pose. So an un-animated part draws with the base
        // matrix alone; an animated part applies only the delta from its rest
        // pose: base * M_anim * M_rest^-1.
        Matrix4f partMatrix = new Matrix4f();
        Matrix4f restInverse = new Matrix4f();
        for (SbePart part : geometry.parts()) {
            // Parts are model-root parts; the base matrix is the parent.
            ParsedAnimTrack track = tracksById.get(part.id());
            if (track == null) {
                track = trackByName(clip, part.name());
            }

            if (track == null) {
                partMatrix.set(base);
            } else {
                AnimSampler.PartPose pose = AnimSampler.sample(track, clipTime);
                Vector3f origin = part.restOrigin();

                // M_rest^-1
                partTransform(restInverse.identity(),
                        part.restPos(), part.restRot(), part.restScale(), origin)
                        .invert();
                // base * M_anim * M_rest^-1
                partTransform(partMatrix.set(base),
                        pose.position(), pose.rotationDeg(), pose.scale(), origin)
                        .mul(restInverse);
            }
            shader.setUniform("model", partMatrix);

            for (SbeFace face : part.faces()) {
                Integer textureId = gpu.materialTextures.get(face.materialId());
                if (textureId == null) continue;
                GL11.glBindTexture(GL11.GL_TEXTURE_2D, textureId);
                GL11.glDrawElements(GL11.GL_TRIANGLES, face.indexCount(),
                        GL11.GL_UNSIGNED_INT, (long) face.indexStart() * Integer.BYTES);
            }
        }

        GL30.glBindVertexArray(0);
        GL11.glBindTexture(GL11.GL_TEXTURE_2D, 0);
        shader.unbind();

        if (wasCullFaceEnabled) {
            GL11.glEnable(GL11.GL_CULL_FACE);
        }
        GL20.glUseProgram(previousProgram);
        GL11.glBindTexture(GL11.GL_TEXTURE_2D, previousTexture);
        GL30.glBindVertexArray(previousVertexArray);
    }

    /** Returns the variant's GPU resources, uploading the asset on first use. */
    private VariantGpu resolveVariantGpu(SbeEntityAsset asset, String variantName) {
        Map<String, VariantGpu> variants = assetGpu.get(asset);
        if (variants == null) {
            variants = new HashMap<>();
            for (Map.Entry<String, SbeModelGeometry> e : asset.variants().entrySet()) {
                variants.put(e.getKey(), uploadVariant(e.getValue()));
            }
            assetGpu.put(asset, variants);
        }
        if (variantName != null) {
            for (Map.Entry<String, VariantGpu> e : variants.entrySet()) {
                if (e.getKey().equalsIgnoreCase(variantName)) {
                    return e.getValue();
                }
            }
        }
        return variants.get(SbeEntityAsset.DEFAULT_VARIANT);
    }

    private VariantGpu uploadVariant(SbeModelGeometry geometry) {
        VariantGpu gpu = new VariantGpu();

        gpu.vao = GL30.glGenVertexArrays();
        gpu.vbo = GL15.glGenBuffers();
        gpu.uvVbo = GL15.glGenBuffers();
        gpu.ebo = GL15.glGenBuffers();

        GL30.glBindVertexArray(gpu.vao);

        FloatBuffer vertexBuffer = memAllocFloat(geometry.vertices().length);
        vertexBuffer.put(geometry.vertices()).flip();
        GL15.glBindBuffer(GL15.GL_ARRAY_BUFFER, gpu.vbo);
        GL15.glBufferData(GL15.GL_ARRAY_BUFFER, vertexBuffer, GL15.GL_STATIC_DRAW);
        GL20.glVertexAttribPointer(0, 3, GL11.GL_FLOAT, false, 3 * Float.BYTES, 0);
        GL20.glEnableVertexAttribArray(0);

        FloatBuffer uvBuffer = memAllocFloat(geometry.texCoords().length);
        uvBuffer.put(geometry.texCoords()).flip();
        GL15.glBindBuffer(GL15.GL_ARRAY_BUFFER, gpu.uvVbo);
        GL15.glBufferData(GL15.GL_ARRAY_BUFFER, uvBuffer, GL15.GL_STATIC_DRAW);
        GL20.glVertexAttribPointer(1, 2, GL11.GL_FLOAT, false, 2 * Float.BYTES, 0);
        GL20.glEnableVertexAttribArray(1);

        IntBuffer indexBuffer = memAllocInt(geometry.indices().length);
        indexBuffer.put(geometry.indices()).flip();
        GL15.glBindBuffer(GL15.GL_ELEMENT_ARRAY_BUFFER, gpu.ebo);
        GL15.glBufferData(GL15.GL_ELEMENT_ARRAY_BUFFER, indexBuffer, GL15.GL_STATIC_DRAW);

        GL30.glBindVertexArray(0);

        long meshBytes = (long) geometry.vertices().length * Float.BYTES
                + (long) geometry.texCoords().length * Float.BYTES
                + (long) geometry.indices().length * Integer.BYTES;
        GpuMemoryTracker.getInstance().track(GpuMemoryTracker.Category.ENTITY_MESH, meshBytes);
        trackedMeshBytes += meshBytes;

        memFree(vertexBuffer);
        memFree(uvBuffer);
        memFree(indexBuffer);

        // Upload one texture per material.
        for (Map.Entry<Integer, MaterialImage> entry : geometry.materials().entrySet()) {
            gpu.materialTextures.put(entry.getKey(), uploadTexture(entry.getValue()));
        }
        return gpu;
    }

    private int uploadTexture(MaterialImage image) {
        int textureId = GL11.glGenTextures();
        GL11.glBindTexture(GL11.GL_TEXTURE_2D, textureId);
        GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_MIN_FILTER, GL11.GL_NEAREST);
        GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_MAG_FILTER, GL11.GL_NEAREST);
        GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_WRAP_S, GL12.GL_CLAMP_TO_EDGE);
        GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_WRAP_T, GL12.GL_CLAMP_TO_EDGE);

        ByteBuffer pixels = memAlloc(image.rgba().length);
        pixels.put(image.rgba()).flip();
        GL11.glTexImage2D(GL11.GL_TEXTURE_2D, 0, GL11.GL_RGBA8,
                image.width(), image.height(), 0,
                GL11.GL_RGBA, GL11.GL_UNSIGNED_BYTE, pixels);
        memFree(pixels);

        GL11.glBindTexture(GL11.GL_TEXTURE_2D, 0);

        long bytes = (long) image.width() * image.height() * 4L;
        GpuMemoryTracker.getInstance().track(GpuMemoryTracker.Category.TEXTURE_ATLAS, bytes);
        trackedTextureBytes += bytes;
        return textureId;
    }

    /**
     * Post-multiplies a part's local TRS transform onto {@code dest}:
     * {@code T(pos) * T(origin) * R(rot) * S(scale) * T(-origin)}, where
     * {@code rot} is Euler degrees and {@code origin} is the rotation pivot.
     */
    private static Matrix4f partTransform(Matrix4f dest, Vector3f pos, Vector3f rotDeg,
                                          Vector3f scale, Vector3f origin) {
        return dest
                .translate(pos)
                .translate(origin)
                .rotateXYZ((float) Math.toRadians(rotDeg.x),
                        (float) Math.toRadians(rotDeg.y),
                        (float) Math.toRadians(rotDeg.z))
                .scale(scale)
                .translate(-origin.x, -origin.y, -origin.z);
    }

    /** Fallback track lookup by part name when the part UUID does not match. */
    private static ParsedAnimTrack trackByName(ParsedAnimClip clip, String partName) {
        if (clip == null || partName == null) return null;
        for (ParsedAnimTrack track : clip.tracks()) {
            if (partName.equals(track.partName())) {
                return track;
            }
        }
        return null;
    }

    /** Release all GPU resources. */
    public void cleanup() {
        if (!initialized) return;
        if (shader != null) {
            shader.cleanup();
        }
        for (Map<String, VariantGpu> variants : assetGpu.values()) {
            for (VariantGpu gpu : variants.values()) {
                GL30.glDeleteVertexArrays(gpu.vao);
                GL15.glDeleteBuffers(gpu.vbo);
                GL15.glDeleteBuffers(gpu.uvVbo);
                GL15.glDeleteBuffers(gpu.ebo);
                for (int textureId : gpu.materialTextures.values()) {
                    GL11.glDeleteTextures(textureId);
                }
            }
        }
        assetGpu.clear();

        if (trackedMeshBytes > 0) {
            GpuMemoryTracker.getInstance()
                    .untrack(GpuMemoryTracker.Category.ENTITY_MESH, trackedMeshBytes);
            trackedMeshBytes = 0;
        }
        if (trackedTextureBytes > 0) {
            GpuMemoryTracker.getInstance()
                    .untrack(GpuMemoryTracker.Category.TEXTURE_ATLAS, trackedTextureBytes);
            trackedTextureBytes = 0;
        }
        initialized = false;
    }
}
