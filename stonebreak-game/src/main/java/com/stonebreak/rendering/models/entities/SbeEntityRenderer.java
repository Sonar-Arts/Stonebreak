package com.stonebreak.rendering.models.entities;

import com.openmason.engine.diagnostics.GpuMemoryTracker;
import com.openmason.engine.rendering.RenderOrigin;
import com.openmason.engine.rendering.model.IndexedDrawBatch;
import com.stonebreak.mobs.sbe.AnimState;
import com.openmason.engine.entity.model.MaterialImage;
import com.openmason.engine.entity.model.EntityAsset;
import com.openmason.engine.entity.model.EntityGeometry;
import com.openmason.engine.entity.model.EntityPart;
import com.openmason.engine.rendering.model.draw.JointPaletteTexture;
import com.openmason.engine.rendering.model.draw.ModelSkinGlsl;
import com.openmason.engine.rendering.shaders.ShaderProgram;
import com.openmason.engine.rig.JointPalette;
import com.openmason.engine.rig.PackedSkin;
import com.stonebreak.rendering.gameWorld.fog.GroundFog;
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
import java.nio.ShortBuffer;
import java.util.HashMap;
import java.util.IdentityHashMap;
import java.util.Map;

import static org.lwjgl.system.MemoryUtil.memAllocFloat;
import static org.lwjgl.system.MemoryUtil.memAllocInt;
import static org.lwjgl.system.MemoryUtil.memAlloc;
import static org.lwjgl.system.MemoryUtil.memAllocShort;
import static org.lwjgl.system.MemoryUtil.memFree;
import com.stonebreak.rendering.gameWorld.UnderwaterFog;
import com.stonebreak.rendering.gameWorld.weather.WeatherFog;

/**
 * Entity-blind renderer for SBE-driven mobs.
 *
 * <p>Given a decoded {@link EntityAsset} plus a variant name, a state name,
 * an animation time and a world transform, it renders the model — selecting the
 * variant geometry, sampling the state's animation clip per part, and drawing
 * cached contiguous material batches for each part. It knows nothing about cows or any
 * specific entity type; callers supply the entity-specific bindings.
 *
 * <p>Posing is the engine's native rig ({@link SbePoseSolver#pose}): each draw poses the model once
 * into a {@link JointPalette}, uploads it as a buffer texture ({@link JointPaletteTexture}, unit
 * {@link JointPaletteTexture#DEFAULT_UNIT}) and the vertex shaders skin every vertex on the GPU
 * ({@link ModelSkinGlsl}: per-vertex joints/weights on attributes 2/3; a rigid part's vertices carry
 * its one joint at weight 1). Parts still draw in their own batches (per-part {@link Look} uniforms,
 * material textures) with an identity {@code model} matrix.
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

    /** The Shade's ground fog; null outside the world pipeline (UI previews). */
    private GroundFog groundFog;

    /** Wires the ground fog, so creatures in it fade as the terrain behind them does. */
    public void setGroundFog(GroundFog fog) {
        this.groundFog = fog;
    }

    /** UI preview rig: full ambient, no sky-light probe, no torches, no shadows. */
    static final float UI_PREVIEW_AMBIENT = 1.0f;
    /**
     * UI preview key light, in <em>camera</em> space (toward the light): above, a
     * little to the left and in front of the viewer. Camera-relative so an orbiting
     * preview camera always sees the same lit side.
     */
    private static final Vector3f UI_PREVIEW_KEY_LIGHT_VIEW = new Vector3f(-0.35f, 0.6f, 0.7f).normalize();

    /** True while UI previews draw; see {@link #setUiPreviewLighting}. */
    private boolean uiPreviewLighting;
    private final Matrix4f scratchInverseView = new Matrix4f();
    private final Vector3f scratchKeyLight = new Vector3f();

    /**
     * Switches textured draws between the world's lighting (time of day, sky
     * light, torches, sun shadows) and the fixed UI preview rig. Previews
     * (character creation, character sheet, glossary) draw into their own
     * viewport over the UI, so the world's day/night must not reach them.
     *
     * @return the previous mode, for the caller to restore
     */
    public boolean setUiPreviewLighting(boolean enabled) {
        boolean previous = uiPreviewLighting;
        uiPreviewLighting = enabled;
        return previous;
    }

    /**
     * How see-through and how glowing the next {@link #render} is — a veiled Vestige is a faint
     * shimmer with its eyes still lit. {@link #NONE} (the default) draws as authored.
     *
     * @param bodyAlpha opacity of every part not in {@code glowParts}
     * @param shimmer   0..1 strength of the heat-haze flicker on those parts
     * @param glowParts names of the parts that glow by their own light
     * @param glowAlpha opacity of the glowing parts
     */
    public record Look(float bodyAlpha, float shimmer, java.util.Set<String> glowParts, float glowAlpha) {
        public static final Look NONE = new Look(1f, 0f, java.util.Set.of(), 1f);

        boolean isTranslucent() {
            return bodyAlpha < 1f || glowAlpha < 1f;
        }
    }

    private Look look = Look.NONE;

    /** Sets the {@link Look} of the next draws; callers reset it to {@link Look#NONE} after. */
    public void setLook(Look look) {
        this.look = look != null ? look : Look.NONE;
    }

    /** Posed joints of the draw in flight (GL thread only). */
    private final JointPalette palette = new JointPalette();
    /** GPU copy of {@link #palette}, re-filled per draw. */
    private final JointPaletteTexture paletteTexture = new JointPaletteTexture();
    private static final Matrix4f IDENTITY = new Matrix4f();

    /** GPU resources for one variant of one asset. */
    private static final class VariantGpu {
        int vao;
        int vbo;
        int uvVbo;
        int jointVbo;
        int weightVbo;
        int ebo;
        SbeDrawPlan drawPlan;
        final Map<Integer, Integer> materialTextures = new HashMap<>();
    }

    /** Per-asset GPU resources, keyed by asset identity (assets are cached singletons). */
    private final Map<EntityAsset, Map<String, VariantGpu>> assetGpu = new IdentityHashMap<>();

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
            """ + ModelSkinGlsl.VERTEX + """

            uniform mat4 model;
            uniform mat4 view;
            uniform mat4 projection;

            out vec2 TexCoord;
            out vec3 FragWorldPos;
            out float ViewDepth;

            void main() {
                vec4 worldPos = model * skinPosition(aPos);
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
            uniform float u_selfGlow;
            uniform float u_alpha;    // 1 opaque; less draws the part see-through
            uniform float u_emissive; // 0 lit as usual, 1 glowing by its own light
            uniform float u_shimmer;  // 0..1 heat-haze flicker on a veiled body seen up close
            uniform float u_time;
            """
            + com.openmason.engine.rendering.shadow.ShadowGlsl.UNIFORMS
            + com.openmason.engine.rendering.shadow.ShadowGlsl.FUNCTIONS
            + com.stonebreak.rendering.lighting.PointLightGlsl.UNIFORMS
            + com.stonebreak.rendering.lighting.PointLightGlsl.FUNCTIONS
            + GroundFog.glsl()
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
                // Dynamic point lights (torches) and the model's own glow (a
                // torch is lit by its flame) add on top of the sky/sun term.
                // Torchlight + the model's own glow, capped so overlapping
                // lights fill the texture up to full brightness, never past it.
                vec3 torch = pointLightContribution(FragWorldPos, normal,
                        pointLightWeight(u_ambientLight, u_entityLight, shadowFactor)) + vec3(u_selfGlow);
                lit = applyPointLight(lit, texColor.rgb, torch);
                vec3 glow = texColor.rgb * 1.6;
                lit = mix(lit, glow, u_emissive);

                if (underwaterFogDensity > 0.0) {
                    float dist = length(FragWorldPos - cameraPos);
                    float fogFactor = clamp(exp(-underwaterFogDensity * dist), 0.0, 1.0);
                    FragColor = mix(vec4(underwaterFogColor, texColor.a), vec4(lit, texColor.a), fogFactor);
                } else {
                    FragColor = vec4(lit, texColor.a);
                }
                // The Shade's night fog, as the terrain behind the creature gets it.
                FragColor.rgb = applyGroundFog(FragColor.rgb, cameraPos, FragWorldPos - cameraPos);
                // Glowing parts cut through the fog — eyes in the dark.
                FragColor.rgb = mix(FragColor.rgb, glow, u_emissive * 0.5);

                float alpha = u_alpha;
                if (u_shimmer > 0.0) {
                    float wave = 0.5 + 0.5 * sin(FragWorldPos.y * 14.0 + FragWorldPos.x * 9.0 + u_time * 7.0);
                    alpha += u_shimmer * (0.05 + 0.10 * wave);
                    FragColor.rgb = mix(FragColor.rgb, vec3(0.75, 0.85, 1.0), u_shimmer * 0.3 * wave);
                }
                FragColor.a = texColor.a * clamp(alpha, 0.0, 1.0);
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
            shader.createUniform("u_jointPalette");
            shader.createUniform("u_skinned");
            // Lighting defaults: fully lit until the first draw sets the real
            // environment (or the UI preview rig, see applyUiPreviewLighting).
            shader.bind();
            shader.setFloat("u_ambientLight", UI_PREVIEW_AMBIENT);
            shader.setVec3("u_sunDirection", new Vector3f(0.4f, 0.8f, 0.4f).normalize());
            shader.setFloat("u_entityLight", 1.0f);
            shader.setUniform("u_jointPalette", JointPaletteTexture.DEFAULT_UNIT);
            shader.setUniform("u_skinned", false);
            GroundFog.applyDisabled(shader);
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
            """ + ModelSkinGlsl.VERTEX + """

            uniform mat4 model;
            uniform mat4 view;
            uniform mat4 projection;

            void main() {
                gl_Position = projection * view * model * skinPosition(aPos);
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
            wireShader.createUniform("u_jointPalette");
            wireShader.createUniform("u_skinned");
            wireShader.bind();
            wireShader.setUniform("u_jointPalette", JointPaletteTexture.DEFAULT_UNIT);
            wireShader.setUniform("u_skinned", false);
            wireShader.unbind();
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
    public void render(EntityAsset asset, String variantName, String stateName,
                       float animationTime, Vector3f position, float yawDegrees,
                       Vector3f scale, Matrix4f viewMatrix, Matrix4f projectionMatrix,
                       com.stonebreak.world.World world, org.joml.Vector3dc cameraPos) {
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
    public void render(EntityAsset asset, String variantName, String stateName,
                       float animationTime, Vector3f position, float yawDegrees,
                       Vector3f scale, Matrix4f viewMatrix, Matrix4f projectionMatrix,
                       com.stonebreak.world.World world, org.joml.Vector3dc cameraPos,
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
    public void render(EntityAsset asset, String variantName, AnimState anim,
                       Vector3f position, float yawDegrees,
                       Vector3f scale, Matrix4f viewMatrix, Matrix4f projectionMatrix,
                       com.stonebreak.world.World world, org.joml.Vector3dc cameraPos,
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
    public void render(EntityAsset asset, String variantName, AnimState anim,
                       Matrix4f baseMatrix, Matrix4f viewMatrix, Matrix4f projectionMatrix,
                       com.stonebreak.world.World world, org.joml.Vector3dc cameraPos,
                       float headYawDeg, float headPitchDeg) {
        render(asset, variantName, anim, baseMatrix, viewMatrix, projectionMatrix, world, cameraPos,
                headYawDeg, headPitchDeg, null);
    }

    /**
     * Matrix-based entry point with code-driven part rotations layered over the clip
     * (see {@link SbePoseSolver.PartOverrides}); {@code overrides} may be null.
     */
    public void render(EntityAsset asset, String variantName, AnimState anim,
                       Matrix4f baseMatrix, Matrix4f viewMatrix, Matrix4f projectionMatrix,
                       com.stonebreak.world.World world, org.joml.Vector3dc cameraPos,
                       float headYawDeg, float headPitchDeg,
                       SbePoseSolver.PartOverrides overrides) {
        if (!initialized || asset == null) return;

        EntityGeometry geometry = asset.geometryFor(variantName);
        VariantGpu gpu = resolveVariantGpu(asset, variantName);
        if (geometry == null || gpu == null) return;

        // Save GL state.
        int previousProgram = GL11.glGetInteger(GL20.GL_CURRENT_PROGRAM);
        int previousActiveTexture = GL11.glGetInteger(GL13.GL_ACTIVE_TEXTURE);
        GL13.glActiveTexture(GL13.GL_TEXTURE0);
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
                && world.isPositionUnderwater((long) Math.floor(cameraPos.x()),
                        (long) Math.floor(cameraPos.y()), (long) Math.floor(cameraPos.z()))) {
            // Scaled by depth, so an entity dissolves at the same range as the
            // seabed behind it (see UnderwaterFog).
            fogDensity = UnderwaterFog.density((float) cameraPos.y());
        } else if (cameraPos != null && !uiPreviewLighting) {
            // Rain and snow fog, as the terrain behind the entity gets it.
            fogDensity = WeatherFog.density();
            WeatherFog.color(fogColor);
        }
        // cameraPos arrives in world coordinates (the underwater probe above needs
        // it that way); the shader differences it against a render-space fragment
        // position, so it crosses over here.
        shader.setUniform("cameraPos", cameraPos != null
                ? RenderOrigin.toRender(cameraPos, new Vector3f())
                : new Vector3f());
        shader.setUniform("underwaterFogDensity", fogDensity);
        shader.setUniform("underwaterFogColor", fogColor);
        if (groundFog != null && !uiPreviewLighting) {
            groundFog.applyTo(shader);
        } else {
            GroundFog.applyDisabled(shader);
        }
        if (uiPreviewLighting) {
            applyUiPreviewLighting(viewMatrix);
        } else {
            // baseMatrix is render-space; the sky-light probe is a world block lookup.
            applyEnvironmentLighting(world,
                    RenderOrigin.toWorld(baseMatrix.getTranslation(new Vector3f()), new org.joml.Vector3d()));
        }

        GL13.glActiveTexture(GL13.GL_TEXTURE0);
        GL30.glBindVertexArray(gpu.vao);

        Look look = this.look;
        boolean translucent = look.isTranslucent();
        boolean wasBlendEnabled = GL11.glIsEnabled(GL11.GL_BLEND);
        shader.setFloat("u_alpha", 1f);
        shader.setFloat("u_emissive", 0f);
        shader.setFloat("u_shimmer", 0f);
        shader.setFloat("u_time", com.stonebreak.core.Game.getInstance() != null
                ? com.stonebreak.core.Game.getInstance().getTotalTimeElapsed() : 0f);
        if (translucent) {
            // See-through parts must not hide what is behind them from later draws.
            GL11.glEnable(GL11.GL_BLEND);
            GL11.glBlendFunc(GL11.GL_SRC_ALPHA, GL11.GL_ONE_MINUS_SRC_ALPHA);
            GL11.glDepthMask(false);
        }

        beginSkin(shader, geometry, asset, anim, baseMatrix, headYawDeg, headPitchDeg, overrides);
        java.util.List<EntityPart> parts = geometry.parts();
        for (int i = 0; i < parts.size(); i++) {
            EntityPart part = parts.get(i);
            if (look != Look.NONE) {
                boolean glows = look.glowParts().contains(part.name());
                shader.setFloat("u_alpha", glows ? look.glowAlpha() : look.bodyAlpha());
                shader.setFloat("u_emissive", glows ? 1f : 0f);
                shader.setFloat("u_shimmer", glows ? 0f : look.shimmer());
            }
            for (IndexedDrawBatch batch : gpu.drawPlan.forPart(part).textured()) {
                GL11.glBindTexture(GL11.GL_TEXTURE_2D, gpu.materialTextures.get(batch.materialId()));
                GL11.glDrawElements(GL11.GL_TRIANGLES, batch.indexCount(),
                        GL11.GL_UNSIGNED_INT, batch.byteOffset());
            }
        }
        endSkin(shader);

        if (translucent) {
            GL11.glDepthMask(true);
            if (!wasBlendEnabled) {
                GL11.glDisable(GL11.GL_BLEND);
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
        GL13.glActiveTexture(previousActiveTexture);
        GL30.glBindVertexArray(previousVertexArray);
    }

    // ─── World-entity overloads: double world positions, made camera-relative in double ──────────────

    /** {@link #render(EntityAsset, String, String, float, Vector3f, float, Vector3f, Matrix4f, Matrix4f,
     *  com.stonebreak.world.World, org.joml.Vector3dc)} at a double world position. */
    public void render(EntityAsset asset, String variantName, String stateName,
                       float animationTime, org.joml.Vector3dc position, float yawDegrees,
                       Vector3f scale, Matrix4f viewMatrix, Matrix4f projectionMatrix,
                       com.stonebreak.world.World world, org.joml.Vector3dc cameraPos) {
        render(asset, variantName, AnimState.single(stateName, animationTime),
                SbePoseSolver.baseMatrix(position, yawDegrees, scale),
                viewMatrix, projectionMatrix, world, cameraPos, 0f, 0f);
    }

    /** Layered variant at a double world position (head turn as the Vector3f form). */
    public void render(EntityAsset asset, String variantName, AnimState anim,
                       org.joml.Vector3dc position, float yawDegrees,
                       Vector3f scale, Matrix4f viewMatrix, Matrix4f projectionMatrix,
                       com.stonebreak.world.World world, org.joml.Vector3dc cameraPos,
                       float headYawDeg, float headPitchDeg) {
        render(asset, variantName, anim, SbePoseSolver.baseMatrix(position, yawDegrees, scale),
                viewMatrix, projectionMatrix, world, cameraPos, headYawDeg, headPitchDeg);
    }

    /** Flat-coloured draw at a double world position. */
    public void renderColored(EntityAsset asset, String variantName, String stateName,
                              float animationTime, org.joml.Vector3dc position, float yawDegrees,
                              Vector3f scale, Matrix4f viewMatrix, Matrix4f projectionMatrix,
                              Vector4f color) {
        renderColored(asset, variantName, AnimState.single(stateName, animationTime),
                SbePoseSolver.baseMatrix(position, yawDegrees, scale),
                viewMatrix, projectionMatrix, color, 0f, 0f);
    }

    /** Layered flat-coloured draw at a double world position. */
    public void renderColored(EntityAsset asset, String variantName, AnimState anim,
                              org.joml.Vector3dc position, float yawDegrees,
                              Vector3f scale, Matrix4f viewMatrix, Matrix4f projectionMatrix,
                              Vector4f color, float headYawDeg, float headPitchDeg) {
        renderColored(asset, variantName, anim, SbePoseSolver.baseMatrix(position, yawDegrees, scale),
                viewMatrix, projectionMatrix, color, headYawDeg, headPitchDeg);
    }

    /** Debug wireframe at a double world position. */
    public void renderWireframe(EntityAsset asset, String variantName, String stateName,
                                float animationTime, org.joml.Vector3dc position, float yawDegrees,
                                Vector3f scale, Matrix4f viewMatrix, Matrix4f projectionMatrix,
                                Vector4f color) {
        renderWireframe(asset, variantName, stateName, animationTime,
                SbePoseSolver.baseMatrix(position, yawDegrees, scale), viewMatrix, projectionMatrix, color);
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
    public void renderWireframe(EntityAsset asset, String variantName, String stateName,
                                float animationTime, Vector3f position, float yawDegrees,
                                Vector3f scale, Matrix4f viewMatrix, Matrix4f projectionMatrix,
                                Vector4f color) {
        renderWireframe(asset, variantName, stateName, animationTime,
                SbePoseSolver.baseMatrix(position, yawDegrees, scale), viewMatrix, projectionMatrix, color);
    }

    private void renderWireframe(EntityAsset asset, String variantName, String stateName,
                                 float animationTime, Matrix4f base, Matrix4f viewMatrix,
                                 Matrix4f projectionMatrix, Vector4f color) {
        if (!initialized || asset == null || wireShader == null) return;

        EntityGeometry geometry = asset.geometryFor(variantName);
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

        GL30.glBindVertexArray(gpu.vao);

        int previousActiveTexture = GL11.glGetInteger(GL13.GL_ACTIVE_TEXTURE);
        beginSkin(wireShader, geometry, asset, AnimState.single(stateName, animationTime),
                base, 0f, 0f, null);
        drawUntexturedParts(geometry, gpu);
        endSkin(wireShader);
        GL13.glActiveTexture(previousActiveTexture);

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
    public void renderColored(EntityAsset asset, String variantName, String stateName,
                              float animationTime, Vector3f position, float yawDegrees,
                              Vector3f scale, Matrix4f viewMatrix, Matrix4f projectionMatrix,
                              Vector4f color) {
        renderColored(asset, variantName, stateName, animationTime, position, yawDegrees, scale,
                viewMatrix, projectionMatrix, color, 0f, 0f);
    }

    /** Flat-coloured variant of {@link #render} with optional head turn (see that method). */
    public void renderColored(EntityAsset asset, String variantName, String stateName,
                              float animationTime, Vector3f position, float yawDegrees,
                              Vector3f scale, Matrix4f viewMatrix, Matrix4f projectionMatrix,
                              Vector4f color, float headYawDeg, float headPitchDeg) {
        renderColored(asset, variantName, AnimState.single(stateName, animationTime), position,
                yawDegrees, scale, viewMatrix, projectionMatrix, color, headYawDeg, headPitchDeg);
    }

    /** Layered flat-coloured variant — see {@link #render(EntityAsset, String, AnimState,
     * Vector3f, float, Vector3f, Matrix4f, Matrix4f, com.stonebreak.world.World, Vector3f,
     * float, float)}. */
    public void renderColored(EntityAsset asset, String variantName, AnimState anim,
                              Vector3f position, float yawDegrees,
                              Vector3f scale, Matrix4f viewMatrix, Matrix4f projectionMatrix,
                              Vector4f color, float headYawDeg, float headPitchDeg) {
        renderColored(asset, variantName, anim, SbePoseSolver.baseMatrix(position, yawDegrees, scale),
                viewMatrix, projectionMatrix, color, headYawDeg, headPitchDeg);
    }

    /**
     * Matrix-based flat-coloured entry point — see the matrix-based
     * {@link #render(EntityAsset, String, AnimState, Matrix4f, Matrix4f,
     * Matrix4f, com.stonebreak.world.World, Vector3f, float, float)}.
     */
    public void renderColored(EntityAsset asset, String variantName, AnimState anim,
                              Matrix4f baseMatrix, Matrix4f viewMatrix, Matrix4f projectionMatrix,
                              Vector4f color, float headYawDeg, float headPitchDeg) {
        if (!initialized || asset == null || wireShader == null) return;

        EntityGeometry geometry = asset.geometryFor(variantName);
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

        int previousActiveTexture = GL11.glGetInteger(GL13.GL_ACTIVE_TEXTURE);
        beginSkin(wireShader, geometry, asset, anim, baseMatrix, headYawDeg, headPitchDeg, null);
        drawUntexturedParts(geometry, gpu);
        endSkin(wireShader);
        GL13.glActiveTexture(previousActiveTexture);

        GL30.glBindVertexArray(0);
        wireShader.unbind();

        if (wasCullFaceEnabled) {
            GL11.glEnable(GL11.GL_CULL_FACE);
        }
        GL20.glUseProgram(previousProgram);
        GL30.glBindVertexArray(previousVertexArray);
    }

    /**
     * Poses the model once (native rig), uploads the palette to its buffer texture on
     * {@link JointPaletteTexture#DEFAULT_UNIT} and switches the bound {@code program} to GPU skinning
     * with an identity {@code model}: every part then draws straight from the shared VAO.
     */
    private void beginSkin(ShaderProgram program, EntityGeometry geometry, EntityAsset asset, AnimState anim,
                           Matrix4f baseMatrix, float headYawDeg, float headPitchDeg,
                           SbePoseSolver.PartOverrides overrides) {
        SbePoseSolver.pose(geometry, asset, anim, baseMatrix, headYawDeg, headPitchDeg, overrides, palette);
        paletteTexture.upload(palette);
        paletteTexture.bind(JointPaletteTexture.DEFAULT_UNIT);
        program.setUniform("u_skinned", true);
        program.setUniform("model", IDENTITY);
    }

    /** Back to rigid (unskinned) draws; unbinds the palette from its unit (active unit 0 after). */
    private static void endSkin(ShaderProgram program) {
        program.setUniform("u_skinned", false);
        JointPaletteTexture.unbind(JointPaletteTexture.DEFAULT_UNIT);
    }

    /** Every part's untextured batches (flat / wireframe passes; skinning already bound). */
    private static void drawUntexturedParts(EntityGeometry geometry, VariantGpu gpu) {
        for (EntityPart part : geometry.parts()) {
            for (IndexedDrawBatch batch : gpu.drawPlan.forPart(part).untextured()) {
                GL11.glDrawElements(GL11.GL_TRIANGLES, batch.indexCount(),
                        GL11.GL_UNSIGNED_INT, batch.byteOffset());
            }
        }
    }

    /**
     * Pushes the per-frame lighting environment to the (bound) entity shader:
     * the sky's ambient + key light direction (sun, or moon at night), the sampled sky light at the entity's
     * position (so mobs darken in caves and at night), and this frame's shadow
     * cascade state. Falls back to fully lit outside the world pipeline.
     */
    private void applyEnvironmentLighting(com.stonebreak.world.World world, org.joml.Vector3dc position) {
        com.stonebreak.world.TimeOfDay timeOfDay = com.stonebreak.core.Game.getTimeOfDay();
        if (timeOfDay != null) {
            shader.setFloat("u_ambientLight", com.stonebreak.rendering.lighting.SceneLight.ambient());
            shader.setVec3("u_sunDirection", com.stonebreak.rendering.lighting.SceneLight.keyDirection());
        } else {
            shader.setFloat("u_ambientLight", 1.0f);
            shader.setVec3("u_sunDirection", new Vector3f(0.4f, 0.8f, 0.4f).normalize());
        }
        shader.setFloat("u_entityLight", sampleEntityLight(world, position));
        shader.setFloat("u_selfGlow", selfGlow);
        com.stonebreak.rendering.lighting.DynamicLights.applyTo(shader);
        if (shadowMapRenderer != null) {
            shadowMapRenderer.applyToShader(shader);
        }
    }

    /**
     * Pushes the fixed UI preview rig to the (bound) entity shader: full ambient,
     * a camera-relative key light, and every world-driven term switched off —
     * no time of day, no sky-light probe, no torches, no sun or torch shadows.
     */
    private void applyUiPreviewLighting(Matrix4f viewMatrix) {
        shader.setFloat("u_ambientLight", UI_PREVIEW_AMBIENT);
        shader.setVec3("u_sunDirection", uiPreviewKeyLight(viewMatrix, scratchInverseView, scratchKeyLight));
        shader.setFloat("u_entityLight", 1.0f);
        shader.setFloat("u_selfGlow", selfGlow);
        com.stonebreak.rendering.lighting.DynamicLights.applyNoneTo(shader);
        com.openmason.engine.rendering.shadow.ShadowUniforms.applyDisabled(shader,
                com.stonebreak.rendering.gameWorld.shadow.ShadowMapRenderer.SHADOW_TEXTURE_UNIT);
    }

    /**
     * The UI key light in world space (toward the light, unit length): the
     * camera-space {@link #UI_PREVIEW_KEY_LIGHT_VIEW} rotated by the inverse view.
     */
    static Vector3f uiPreviewKeyLight(Matrix4f viewMatrix, Matrix4f scratch, Vector3f out) {
        out.set(UI_PREVIEW_KEY_LIGHT_VIEW);
        if (viewMatrix == null) return out;
        return scratch.set(viewMatrix).invert().transformDirection(out).normalize();
    }

    /** Extra flat brightness (0 = none) applied to subsequent draws; see {@code u_selfGlow}. */
    private float selfGlow = 0f;

    public void setSelfGlow(float glow) {
        this.selfGlow = Math.max(0f, glow);
    }

    /**
     * Sky-light probe at the entity's mid-body. Fully lit when the world (or the
     * entity's chunk) isn't available so entities are never mysteriously black.
     */
    static float sampleEntityLight(com.stonebreak.world.World world, org.joml.Vector3dc position) {
        if (world == null || position == null) {
            return 1.0f;
        }
        try {
            com.stonebreak.world.lighting.WorldLightingContext ctx =
                    new com.stonebreak.world.lighting.WorldLightingContext(world);
            return com.openmason.engine.voxel.lighting.VertexLightSampler.samplePointSky(
                    ctx, (float) position.x(), (float) position.y() + 0.5f, (float) position.z());
        } catch (Exception ignored) {
            return 1.0f;
        }
    }

    /** Returns the variant's GPU resources, uploading the asset on first use. */
    private VariantGpu resolveVariantGpu(EntityAsset asset, String variantName) {
        Map<String, VariantGpu> variants = assetGpu.get(asset);
        if (variants == null) {
            variants = new HashMap<>();
            for (Map.Entry<String, EntityGeometry> e : asset.variants().entrySet()) {
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
        return variants.get(EntityAsset.DEFAULT_VARIANT);
    }

    private VariantGpu uploadVariant(EntityGeometry geometry) {
        VariantGpu gpu = new VariantGpu();
        gpu.drawPlan = new SbeDrawPlan(geometry);

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

        // Skin attributes (ModelSkinGlsl, same layout as the engine ModelGpuMesh): 4 x uint16 joints
        // on attribute 2, 4 x unorm8 weights on attribute 3. Rigid parts: their joint at weight 1.
        PackedSkin skin = geometry.rig().packed();
        int skinVertices = skin.vertexCount();
        ShortBuffer jointBuffer = memAllocShort(Math.max(1, skin.joints().length));
        jointBuffer.put(skin.joints()).flip();
        gpu.jointVbo = GL15.glGenBuffers();
        GL15.glBindBuffer(GL15.GL_ARRAY_BUFFER, gpu.jointVbo);
        GL15.glBufferData(GL15.GL_ARRAY_BUFFER, jointBuffer, GL15.GL_STATIC_DRAW);
        GL30.glVertexAttribIPointer(ModelSkinGlsl.JOINTS_LOCATION, 4, GL11.GL_UNSIGNED_SHORT, 8, 0L);
        GL20.glEnableVertexAttribArray(ModelSkinGlsl.JOINTS_LOCATION);
        memFree(jointBuffer);

        ByteBuffer weightBuffer = memAlloc(Math.max(1, skin.weights().length));
        weightBuffer.put(skin.weights()).flip();
        gpu.weightVbo = GL15.glGenBuffers();
        GL15.glBindBuffer(GL15.GL_ARRAY_BUFFER, gpu.weightVbo);
        GL15.glBufferData(GL15.GL_ARRAY_BUFFER, weightBuffer, GL15.GL_STATIC_DRAW);
        GL20.glVertexAttribPointer(ModelSkinGlsl.WEIGHTS_LOCATION, 4, GL11.GL_UNSIGNED_BYTE, true, 4, 0L);
        GL20.glEnableVertexAttribArray(ModelSkinGlsl.WEIGHTS_LOCATION);
        memFree(weightBuffer);

        IntBuffer indexBuffer = memAllocInt(geometry.indices().length);
        indexBuffer.put(geometry.indices()).flip();
        GL15.glBindBuffer(GL15.GL_ELEMENT_ARRAY_BUFFER, gpu.ebo);
        GL15.glBufferData(GL15.GL_ELEMENT_ARRAY_BUFFER, indexBuffer, GL15.GL_STATIC_DRAW);

        GL30.glBindVertexArray(0);

        long meshBytes = (long) geometry.vertices().length * Float.BYTES
                + (long) geometry.texCoords().length * Float.BYTES
                + (long) geometry.indices().length * Integer.BYTES
                + (long) skinVertices * 12L;
        GpuMemoryTracker.getInstance().track(GpuMemoryTracker.Category.ENTITY_MESH, meshBytes);
        trackedMeshBytes += meshBytes;

        memFree(vertexBuffer);
        memFree(uvBuffer);
        memFree(indexBuffer);

        // OMO files can retain many unused editor materials. Only upload the
        // textures actually referenced by this geometry's textured batches.
        for (int materialId : gpu.drawPlan.materialIds()) {
            gpu.materialTextures.put(materialId, uploadTexture(geometry.materials().get(materialId)));
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
                GL15.glDeleteBuffers(gpu.jointVbo);
                GL15.glDeleteBuffers(gpu.weightVbo);
                GL15.glDeleteBuffers(gpu.ebo);
                for (int textureId : gpu.materialTextures.values()) {
                    GL11.glDeleteTextures(textureId);
                }
            }
        }
        assetGpu.clear();
        paletteTexture.close();

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
