package com.stonebreak.rendering.models.entities;

import com.openmason.engine.diagnostics.GpuMemoryTracker;
import com.stonebreak.mobs.sbe.AnimState;
import com.stonebreak.mobs.sbe.MaterialImage;
import com.stonebreak.mobs.sbe.SbeEntityAsset;
import com.stonebreak.mobs.sbe.SbeFace;
import com.stonebreak.mobs.sbe.SbeModelGeometry;
import com.openmason.engine.rendering.shaders.ShaderProgram;
import org.joml.Matrix4f;
import org.joml.Vector3f;
import org.joml.Vector4f;
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
    private ShaderProgram wireShader;
    private boolean initialized;
    private long trackedMeshBytes;
    private long trackedTextureBytes;

    /** Sun-shadow state provider; null outside the world pipeline (UI previews). */
    private com.stonebreak.rendering.gameWorld.shadow.ShadowMapRenderer shadowMapRenderer;

    /** Wires the cascaded-shadow state used when rendering entities in the world. */
    public void setShadowMapRenderer(com.stonebreak.rendering.gameWorld.shadow.ShadowMapRenderer renderer) {
        this.shadowMapRenderer = renderer;
    }

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

    /** Initialize the shared shaders. Must run on the GL thread. */
    public void initialize() {
        if (initialized) return;
        createShader();
        createWireShader();
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
            out float ViewDepth;

            void main() {
                vec4 worldPos = model * vec4(aPos, 1.0);
                FragWorldPos = worldPos.xyz;
                ViewDepth = -(view * worldPos).z;
                gl_Position = projection * view * worldPos;
                TexCoord = aTexCoord;
            }
            """;

        String fragmentShader = """
            #version 330 core
            out vec4 FragColor;

            in vec2 TexCoord;
            in vec3 FragWorldPos;
            in float ViewDepth;

            uniform sampler2D textureSampler;
            uniform vec3 cameraPos;
            uniform float underwaterFogDensity;
            uniform vec3 underwaterFogColor;
            uniform float u_ambientLight;
            uniform vec3 u_sunDirection;
            uniform float u_entityLight;
            """
            + com.openmason.engine.rendering.shadow.ShadowGlsl.UNIFORMS
            + com.openmason.engine.rendering.shadow.ShadowGlsl.FUNCTIONS
            + """
            void main() {
                vec4 texColor = texture(textureSampler, TexCoord);
                if (texColor.a < 0.01) discard;

                // SBE meshes carry no normals — derive the flat face normal from
                // screen-space derivatives. For visible fragments this is the
                // outward (viewer-side) normal, which is what lighting needs.
                vec3 normal = normalize(cross(dFdx(FragWorldPos), dFdy(FragWorldPos)));
                vec3 sunDir = normalize(u_sunDirection);
                float diff = max(dot(normal, sunDir), 0.0);
                float shadowFactor = csmShadowFactor(FragWorldPos, normal, ViewDepth);

                // Ambient + sun diffuse (both day/night scaled), then the sampled
                // sky light at the entity's position so mobs darken in caves.
                float brightness = u_ambientLight * (0.5 + 0.55 * diff * shadowFactor);
                brightness *= mix(0.3, 1.0, u_entityLight);
                vec3 lit = texColor.rgb * min(brightness, 1.0);

                if (underwaterFogDensity > 0.0) {
                    float dist = length(FragWorldPos - cameraPos);
                    float fogFactor = clamp(exp(-underwaterFogDensity * dist), 0.0, 1.0);
                    FragColor = mix(vec4(underwaterFogColor, texColor.a), vec4(lit, texColor.a), fogFactor);
                } else {
                    FragColor = vec4(lit, texColor.a);
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
            // Lighting defaults: fully lit so UI previews (glossary, character
            // creation) render bright without the per-frame environment update.
            shader.bind();
            shader.setFloat("u_ambientLight", 1.0f);
            shader.setVec3("u_sunDirection", new Vector3f(0.4f, 0.8f, 0.4f).normalize());
            shader.setFloat("u_entityLight", 1.0f);
            // The shadow sampler must live on its own unit even while disabled —
            // sharing unit 0 with the 2D textureSampler is a GL error on strict drivers.
            com.openmason.engine.rendering.shadow.ShadowUniforms.applyDisabled(shader,
                    com.stonebreak.rendering.gameWorld.shadow.ShadowMapRenderer.SHADOW_TEXTURE_UNIT);
            shader.unbind();
        } catch (Exception e) {
            System.err.println("Failed to create SBE entity shader: " + e.getMessage());
        }
    }

    /** Flat-colour shader used by the debug wireframe overlay. */
    private void createWireShader() {
        String vertexShader = """
            #version 330 core
            layout (location = 0) in vec3 aPos;

            uniform mat4 model;
            uniform mat4 view;
            uniform mat4 projection;

            void main() {
                gl_Position = projection * view * model * vec4(aPos, 1.0);
            }
            """;

        String fragmentShader = """
            #version 330 core
            out vec4 FragColor;

            uniform vec4 color;

            void main() {
                FragColor = color;
            }
            """;

        try {
            wireShader = new ShaderProgram();
            wireShader.createVertexShader(vertexShader);
            wireShader.createFragmentShader(fragmentShader);
            wireShader.link();
            wireShader.createUniform("model");
            wireShader.createUniform("view");
            wireShader.createUniform("projection");
            wireShader.createUniform("color");
        } catch (Exception e) {
            System.err.println("Failed to create SBE wireframe shader: " + e.getMessage());
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
        render(asset, variantName, stateName, animationTime, position, yawDegrees, scale,
                viewMatrix, projectionMatrix, world, cameraPos, 0f, 0f);
    }

    /**
     * Renders an SBE entity with an optional independent head turn. {@code headYawDeg}
     * and {@code headPitchDeg} rotate the part named "head" about its neck pivot,
     * relative to the body yaw baked into {@code yawDegrees}; pass {@code 0,0} for
     * no head turn. Used by the third-person local player so the head tracks the
     * cursor while the body faces the movement direction.
     */
    public void render(SbeEntityAsset asset, String variantName, String stateName,
                       float animationTime, Vector3f position, float yawDegrees,
                       Vector3f scale, Matrix4f viewMatrix, Matrix4f projectionMatrix,
                       com.stonebreak.world.World world, Vector3f cameraPos,
                       float headYawDeg, float headPitchDeg) {
        render(asset, variantName, AnimState.single(stateName, animationTime), position,
                yawDegrees, scale, viewMatrix, projectionMatrix, world, cameraPos,
                headYawDeg, headPitchDeg);
    }

    /**
     * Layered variant of {@link #render}: plays the {@link AnimState}'s base
     * clip plus any active overlays, resolving each overlay's part mask,
     * fades, and priority from its clip metadata.
     */
    public void render(SbeEntityAsset asset, String variantName, AnimState anim,
                       Vector3f position, float yawDegrees,
                       Vector3f scale, Matrix4f viewMatrix, Matrix4f projectionMatrix,
                       com.stonebreak.world.World world, Vector3f cameraPos,
                       float headYawDeg, float headPitchDeg) {
        // Base transform: the OMO model is placed exactly as authored in the
        // SBE — its model-space origin sits at the entity position, with no
        // re-anchoring. Part transforms come straight from the OMO/animation.
        render(asset, variantName, anim, SbePoseSolver.baseMatrix(position, yawDegrees, scale),
                viewMatrix, projectionMatrix, world, cameraPos, headYawDeg, headPitchDeg);
    }

    /**
     * Matrix-based entry point: renders the model with an arbitrary base
     * transform instead of position/yaw/scale — used to draw attached models
     * at a socket's world frame (see {@link SbePoseSolver#socketWorldMatrix}).
     */
    public void render(SbeEntityAsset asset, String variantName, AnimState anim,
                       Matrix4f baseMatrix, Matrix4f viewMatrix, Matrix4f projectionMatrix,
                       com.stonebreak.world.World world, Vector3f cameraPos,
                       float headYawDeg, float headPitchDeg) {
        if (!initialized || asset == null) return;

        SbeModelGeometry geometry = asset.geometryFor(variantName);
        VariantGpu gpu = resolveVariantGpu(asset, variantName);
        if (geometry == null || gpu == null) return;

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
        applyEnvironmentLighting(world, baseMatrix.getTranslation(new Vector3f()));

        GL13.glActiveTexture(GL13.GL_TEXTURE0);
        GL30.glBindVertexArray(gpu.vao);

        SbePoseSolver.forEachPartMatrix(geometry, asset, anim, baseMatrix,
                headYawDeg, headPitchDeg, (partMatrix, part) -> {
            shader.setUniform("model", partMatrix);
            for (SbeFace face : part.faces()) {
                Integer textureId = gpu.materialTextures.get(face.materialId());
                if (textureId == null) continue;
                GL11.glBindTexture(GL11.GL_TEXTURE_2D, textureId);
                GL11.glDrawElements(GL11.GL_TRIANGLES, face.indexCount(),
                        GL11.GL_UNSIGNED_INT, (long) face.indexStart() * Integer.BYTES);
            }
        });

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

    /**
     * Draws an SBE entity as a flat-coloured, see-through wireframe overlay.
     *
     * <p>This is the debug visualisation: instead of approximating the entity
     * with a bounding box, it re-draws the model's own triangles in line mode,
     * reusing the exact per-part transform pipeline of {@link #render} — the
     * same {@code base}, animation sampling and {@code M_rest^-1} delta. The
     * overlay therefore tracks the animated model perfectly by construction.
     * Depth testing is disabled so the wireframe is visible through geometry,
     * which makes it usable as an entity locator.
     *
     * @param color RGBA line colour
     */
    public void renderWireframe(SbeEntityAsset asset, String variantName, String stateName,
                                float animationTime, Vector3f position, float yawDegrees,
                                Vector3f scale, Matrix4f viewMatrix, Matrix4f projectionMatrix,
                                Vector4f color) {
        if (!initialized || asset == null || wireShader == null) return;

        SbeModelGeometry geometry = asset.geometryFor(variantName);
        VariantGpu gpu = resolveVariantGpu(asset, variantName);
        if (geometry == null || gpu == null) return;

        // Save GL state.
        int previousProgram = GL11.glGetInteger(GL20.GL_CURRENT_PROGRAM);
        int previousVertexArray = GL11.glGetInteger(GL30.GL_VERTEX_ARRAY_BINDING);
        boolean wasCullFaceEnabled = GL11.glIsEnabled(GL11.GL_CULL_FACE);
        boolean wasDepthTestEnabled = GL11.glIsEnabled(GL11.GL_DEPTH_TEST);
        int[] previousPolygonMode = new int[2];
        GL11.glGetIntegerv(GL11.GL_POLYGON_MODE, previousPolygonMode);
        float previousLineWidth = GL11.glGetFloat(GL11.GL_LINE_WIDTH);

        GL11.glDisable(GL11.GL_DEPTH_TEST);
        GL11.glDisable(GL11.GL_CULL_FACE);
        GL11.glPolygonMode(GL11.GL_FRONT_AND_BACK, GL11.GL_LINE);
        GL11.glLineWidth(1.5f);

        wireShader.bind();
        wireShader.setUniform("view", viewMatrix);
        wireShader.setUniform("projection", projectionMatrix);
        wireShader.setUniform("color", color);

        // Same base transform as render(): no re-anchoring of the model origin.
        Matrix4f base = SbePoseSolver.baseMatrix(position, yawDegrees, scale);

        GL30.glBindVertexArray(gpu.vao);

        SbePoseSolver.forEachPartMatrix(geometry, asset, AnimState.single(stateName, animationTime),
                base, 0f, 0f, (partMatrix, part) -> {
            wireShader.setUniform("model", partMatrix);
            for (SbeFace face : part.faces()) {
                GL11.glDrawElements(GL11.GL_TRIANGLES, face.indexCount(),
                        GL11.GL_UNSIGNED_INT, (long) face.indexStart() * Integer.BYTES);
            }
        });

        GL30.glBindVertexArray(0);
        wireShader.unbind();

        // Restore GL state.
        GL11.glPolygonMode(GL11.GL_FRONT_AND_BACK, previousPolygonMode[0]);
        GL11.glLineWidth(previousLineWidth);
        if (wasCullFaceEnabled) {
            GL11.glEnable(GL11.GL_CULL_FACE);
        }
        if (wasDepthTestEnabled) {
            GL11.glEnable(GL11.GL_DEPTH_TEST);
        }
        GL20.glUseProgram(previousProgram);
        GL30.glBindVertexArray(previousVertexArray);
    }

    /**
     * Render one SBE entity as a flat, solid-coloured (but fully shaded-by-depth)
     * model — used when the asset has no baked textures yet (e.g. an
     * authored-but-untextured player). Geometry, animation and transforms are
     * identical to {@link #render}; only the fragment colour differs: every face
     * is drawn in {@code color} with no texture binding. Once the asset gains
     * materials, callers should switch back to {@link #render}.
     *
     * @param color RGBA solid colour to fill the model with
     */
    public void renderColored(SbeEntityAsset asset, String variantName, String stateName,
                              float animationTime, Vector3f position, float yawDegrees,
                              Vector3f scale, Matrix4f viewMatrix, Matrix4f projectionMatrix,
                              Vector4f color) {
        renderColored(asset, variantName, stateName, animationTime, position, yawDegrees, scale,
                viewMatrix, projectionMatrix, color, 0f, 0f);
    }

    /** Flat-coloured variant of {@link #render} with optional head turn (see that method). */
    public void renderColored(SbeEntityAsset asset, String variantName, String stateName,
                              float animationTime, Vector3f position, float yawDegrees,
                              Vector3f scale, Matrix4f viewMatrix, Matrix4f projectionMatrix,
                              Vector4f color, float headYawDeg, float headPitchDeg) {
        renderColored(asset, variantName, AnimState.single(stateName, animationTime), position,
                yawDegrees, scale, viewMatrix, projectionMatrix, color, headYawDeg, headPitchDeg);
    }

    /** Layered flat-coloured variant — see {@link #render(SbeEntityAsset, String, AnimState,
     * Vector3f, float, Vector3f, Matrix4f, Matrix4f, com.stonebreak.world.World, Vector3f,
     * float, float)}. */
    public void renderColored(SbeEntityAsset asset, String variantName, AnimState anim,
                              Vector3f position, float yawDegrees,
                              Vector3f scale, Matrix4f viewMatrix, Matrix4f projectionMatrix,
                              Vector4f color, float headYawDeg, float headPitchDeg) {
        renderColored(asset, variantName, anim, SbePoseSolver.baseMatrix(position, yawDegrees, scale),
                viewMatrix, projectionMatrix, color, headYawDeg, headPitchDeg);
    }

    /**
     * Matrix-based flat-coloured entry point — see the matrix-based
     * {@link #render(SbeEntityAsset, String, AnimState, Matrix4f, Matrix4f,
     * Matrix4f, com.stonebreak.world.World, Vector3f, float, float)}.
     */
    public void renderColored(SbeEntityAsset asset, String variantName, AnimState anim,
                              Matrix4f baseMatrix, Matrix4f viewMatrix, Matrix4f projectionMatrix,
                              Vector4f color, float headYawDeg, float headPitchDeg) {
        if (!initialized || asset == null || wireShader == null) return;

        SbeModelGeometry geometry = asset.geometryFor(variantName);
        VariantGpu gpu = resolveVariantGpu(asset, variantName);
        if (geometry == null || gpu == null) return;

        // Save GL state.
        int previousProgram = GL11.glGetInteger(GL20.GL_CURRENT_PROGRAM);
        int previousVertexArray = GL11.glGetInteger(GL30.GL_VERTEX_ARRAY_BINDING);
        boolean wasCullFaceEnabled = GL11.glIsEnabled(GL11.GL_CULL_FACE);

        GL11.glEnable(GL11.GL_DEPTH_TEST);
        GL11.glDepthFunc(GL11.GL_LESS);
        GL11.glDisable(GL11.GL_CULL_FACE);

        wireShader.bind();
        wireShader.setUniform("view", viewMatrix);
        wireShader.setUniform("projection", projectionMatrix);
        wireShader.setUniform("color", color);

        GL30.glBindVertexArray(gpu.vao);

        SbePoseSolver.forEachPartMatrix(geometry, asset, anim, baseMatrix,
                headYawDeg, headPitchDeg, (partMatrix, part) -> {
            wireShader.setUniform("model", partMatrix);
            for (SbeFace face : part.faces()) {
                GL11.glDrawElements(GL11.GL_TRIANGLES, face.indexCount(),
                        GL11.GL_UNSIGNED_INT, (long) face.indexStart() * Integer.BYTES);
            }
        });

        GL30.glBindVertexArray(0);
        wireShader.unbind();

        if (wasCullFaceEnabled) {
            GL11.glEnable(GL11.GL_CULL_FACE);
        }
        GL20.glUseProgram(previousProgram);
        GL30.glBindVertexArray(previousVertexArray);
    }

    /**
     * Pushes the per-frame lighting environment to the (bound) entity shader:
     * time-of-day ambient + sun direction, the sampled sky light at the entity's
     * position (so mobs darken in caves and at night), and this frame's shadow
     * cascade state. Falls back to fully lit outside the world pipeline.
     */
    private void applyEnvironmentLighting(com.stonebreak.world.World world, Vector3f position) {
        com.stonebreak.world.TimeOfDay timeOfDay = com.stonebreak.core.Game.getTimeOfDay();
        if (timeOfDay != null) {
            shader.setFloat("u_ambientLight", timeOfDay.getAmbientLightLevel());
            shader.setVec3("u_sunDirection", timeOfDay.getSunDirection());
        } else {
            shader.setFloat("u_ambientLight", 1.0f);
            shader.setVec3("u_sunDirection", new Vector3f(0.4f, 0.8f, 0.4f).normalize());
        }
        shader.setFloat("u_entityLight", sampleEntityLight(world, position));
        if (shadowMapRenderer != null) {
            shadowMapRenderer.applyToShader(shader);
        }
    }

    /**
     * Sky-light probe at the entity's mid-body. Fully lit when the world (or the
     * entity's chunk) isn't available so entities are never mysteriously black.
     */
    static float sampleEntityLight(com.stonebreak.world.World world, Vector3f position) {
        if (world == null || position == null) {
            return 1.0f;
        }
        try {
            com.stonebreak.world.lighting.WorldLightingContext ctx =
                    new com.stonebreak.world.lighting.WorldLightingContext(world);
            return com.openmason.engine.voxel.lighting.VertexLightSampler.samplePointSky(
                    ctx, position.x, position.y + 0.5f, position.z);
        } catch (Exception ignored) {
            return 1.0f;
        }
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

    /** Release all GPU resources. */
    public void cleanup() {
        if (!initialized) return;
        if (shader != null) {
            shader.cleanup();
        }
        if (wireShader != null) {
            wireShader.cleanup();
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
