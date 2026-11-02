package com.stonebreak.rendering.models.entities;

import com.openmason.engine.entity.sbe.SbeEntityAsset;
import com.openmason.engine.entity.sbe.SbeModelGeometry;
import com.openmason.engine.rendering.RenderOrigin;
import com.openmason.engine.rendering.model.draw.ModelLighting;
import com.openmason.engine.rendering.model.draw.ModelRenderer;
import com.openmason.engine.rendering.model.draw.ModelShaderExtension;
import com.openmason.engine.rendering.model.draw.UploadedModel;
import com.openmason.engine.rendering.shaders.ShaderProgram;
import com.openmason.engine.rendering.shadow.ShadowUniforms;
import com.stonebreak.mobs.sbe.AnimState;
import com.stonebreak.rendering.gameWorld.UnderwaterFog;
import com.stonebreak.rendering.gameWorld.shadow.ShadowMapRenderer;
import com.stonebreak.rendering.gameWorld.weather.WeatherFog;
import com.stonebreak.rendering.lighting.DynamicLights;
import com.stonebreak.rendering.lighting.PointLightGlsl;
import com.stonebreak.rendering.lighting.SceneLight;
import org.joml.Matrix4f;
import org.joml.Vector3f;
import org.joml.Vector4f;

import java.util.HashMap;
import java.util.IdentityHashMap;
import java.util.Map;
import java.util.function.Consumer;

/**
 * Entity-blind renderer for SBE-driven models: Stonebreak's hooks around the
 * engine {@link ModelRenderer} (#90).
 *
 * <p>Given a decoded {@link SbeEntityAsset} plus a variant, an {@link AnimState}
 * and a world transform, it uploads the variant once ({@link UploadedModel}),
 * poses it ({@link SbePoseSolver} → engine pose solver) and draws it through the
 * engine's lit / flat / wireframe passes — the same draw path the Open Mason
 * viewport uses, so models look alike in both. What stays here is what only the
 * game knows: underwater and weather fog, time-of-day sky light and the sky-light
 * probe, torch point lights ({@link PointLightGlsl} spliced into the engine
 * shader) and the frame's sun shadow cascades.
 *
 * <p>GPU resources are uploaded lazily, per asset, on first render (on the GL
 * thread). Owned and driven by {@link EntityRenderer}.
 */
public final class SbeEntityRenderer {

    /** Torch point lights, fed to the engine shader's extra-light hooks. */
    static final String POINT_LIGHT_EXTENSION = PointLightGlsl.UNIFORMS
            + PointLightGlsl.FUNCTIONS
            + """
            vec3 modelExtraLight(vec3 worldPos, vec3 normal, float sunVisibility) {
                return pointLightContribution(worldPos, normal,
                        pointLightWeight(u_ambientLight, u_skyLight, sunVisibility));
            }
            vec3 modelApplyExtraLight(vec3 lit, vec3 albedo, vec3 extra) {
                return applyPointLight(lit, albedo, extra);
            }
            """;

    /** UI preview rig: full ambient, no sky-light probe, no torches, no shadows. */
    static final float UI_PREVIEW_AMBIENT = ModelLighting.PREVIEW_AMBIENT;

    private static final Vector3f DEFAULT_KEY_LIGHT = new Vector3f(0.4f, 0.8f, 0.4f).normalize();
    private static final Vector3f UNDERWATER_FOG_COLOR = new Vector3f(0.1f, 0.3f, 0.5f);

    private final ModelRenderer models = new ModelRenderer(
            new ModelShaderExtension(POINT_LIGHT_EXTENSION, ShadowMapRenderer.SHADOW_TEXTURE_UNIT));
    private boolean initialized;

    /** Sun-shadow state provider; null outside the world pipeline (UI previews). */
    private ShadowMapRenderer shadowMapRenderer;

    /** True while UI previews draw; see {@link #setUiPreviewLighting}. */
    private boolean uiPreviewLighting;

    /** Extra flat brightness (0 = none) applied to subsequent draws. */
    private float selfGlow = 0f;

    private final Matrix4f scratchInverseView = new Matrix4f();

    /** Per-asset uploads, keyed by asset identity (assets are cached singletons), then variant. */
    private final Map<SbeEntityAsset, Map<String, UploadedModel>> assetGpu = new IdentityHashMap<>();

    private final Consumer<ShaderProgram> worldShaderState = shader -> {
        DynamicLights.applyTo(shader);
        if (shadowMapRenderer != null) {
            shadowMapRenderer.applyToShader(shader);
        }
    };

    private static final Consumer<ShaderProgram> PREVIEW_SHADER_STATE = shader -> {
        DynamicLights.applyNoneTo(shader);
        ShadowUniforms.applyDisabled(shader, ShadowMapRenderer.SHADOW_TEXTURE_UNIT);
    };

    /** Wires the cascaded-shadow state used when rendering entities in the world. */
    public void setShadowMapRenderer(ShadowMapRenderer renderer) {
        this.shadowMapRenderer = renderer;
    }

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

    public void setSelfGlow(float glow) {
        this.selfGlow = Math.max(0f, glow);
    }

    /** Initialize the shared shaders. Must run on the GL thread. */
    public void initialize() {
        if (initialized) return;
        models.initialize();
        initialized = true;
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
        render(asset, variantName, anim, baseMatrix, viewMatrix, projectionMatrix, world, cameraPos,
                headYawDeg, headPitchDeg, null);
    }

    /**
     * Matrix-based entry point with code-driven part rotations layered over the clip
     * (see {@link SbePoseSolver.PartOverrides}); {@code overrides} may be null.
     */
    public void render(SbeEntityAsset asset, String variantName, AnimState anim,
                       Matrix4f baseMatrix, Matrix4f viewMatrix, Matrix4f projectionMatrix,
                       com.stonebreak.world.World world, Vector3f cameraPos,
                       float headYawDeg, float headPitchDeg,
                       SbePoseSolver.PartOverrides overrides) {
        if (!initialized || asset == null) return;

        SbeModelGeometry geometry = asset.geometryFor(variantName);
        UploadedModel gpu = resolveVariantGpu(asset, variantName);
        if (geometry == null || gpu == null) return;

        models.drawLit(gpu.mesh(), gpu.plan(), gpu,
                SbePoseSolver.partMatrices(geometry, asset, anim, baseMatrix, headYawDeg, headPitchDeg, overrides),
                viewMatrix, projectionMatrix,
                lighting(world, cameraPos, baseMatrix, viewMatrix),
                uiPreviewLighting ? PREVIEW_SHADER_STATE : worldShaderState);
    }

    /**
     * The lit pass's environment: underwater fog by camera depth, else weather fog
     * (never in previews); the preview rig, or the sky's ambient + key light and the
     * sampled sky light at the model.
     */
    private ModelLighting lighting(com.stonebreak.world.World world, Vector3f cameraPos,
                                   Matrix4f baseMatrix, Matrix4f viewMatrix) {
        float fogDensity = 0.0f;
        Vector3f fogColor = new Vector3f(UNDERWATER_FOG_COLOR);
        if (world != null && cameraPos != null
                && world.isPositionUnderwater((int) Math.floor(cameraPos.x),
                        (int) Math.floor(cameraPos.y), (int) Math.floor(cameraPos.z))) {
            // Scaled by depth, so an entity dissolves at the same range as the
            // seabed behind it (see UnderwaterFog).
            fogDensity = UnderwaterFog.density(cameraPos.y);
        } else if (cameraPos != null && !uiPreviewLighting) {
            // Rain and snow fog, as the terrain behind the entity gets it.
            fogDensity = WeatherFog.density();
            WeatherFog.color(fogColor);
        }
        // cameraPos arrives in world coordinates (the underwater probe above needs
        // it that way); the shader differences it against a render-space fragment
        // position, so it crosses over here.
        Vector3f renderCamera = cameraPos != null ? RenderOrigin.toRender(cameraPos, new Vector3f()) : new Vector3f();

        float ambient;
        Vector3f keyDirection;
        float skyLight;
        if (uiPreviewLighting) {
            ambient = UI_PREVIEW_AMBIENT;
            keyDirection = uiPreviewKeyLight(viewMatrix, scratchInverseView, new Vector3f());
            skyLight = 1.0f;
        } else {
            if (com.stonebreak.core.Game.getTimeOfDay() != null) {
                ambient = SceneLight.ambient();
                keyDirection = SceneLight.keyDirection();
            } else {
                ambient = 1.0f;
                keyDirection = DEFAULT_KEY_LIGHT;
            }
            // baseMatrix is render-space; the sky-light probe is a world block lookup.
            skyLight = sampleEntityLight(world,
                    RenderOrigin.toWorld(baseMatrix.getTranslation(new Vector3f()), new Vector3f()));
        }
        return new ModelLighting(ambient, keyDirection, skyLight, selfGlow,
                fogDensity, fogColor, renderCamera, ModelLighting.DEFAULT_BASE_COLOR);
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
        if (!initialized || asset == null) return;

        SbeModelGeometry geometry = asset.geometryFor(variantName);
        UploadedModel gpu = resolveVariantGpu(asset, variantName);
        if (geometry == null || gpu == null) return;

        // Same base transform and pose as render(): no re-anchoring of the model origin.
        Matrix4f base = SbePoseSolver.baseMatrix(position, yawDegrees, scale);
        models.drawWireframe(gpu.mesh(), gpu.plan(),
                SbePoseSolver.partMatrices(geometry, asset, AnimState.single(stateName, animationTime),
                        base, 0f, 0f, null),
                viewMatrix, projectionMatrix, color);
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
        if (!initialized || asset == null) return;

        SbeModelGeometry geometry = asset.geometryFor(variantName);
        UploadedModel gpu = resolveVariantGpu(asset, variantName);
        if (geometry == null || gpu == null) return;

        models.drawFlat(gpu.mesh(), gpu.plan(),
                SbePoseSolver.partMatrices(geometry, asset, anim, baseMatrix, headYawDeg, headPitchDeg, null),
                viewMatrix, projectionMatrix, color);
    }

    /**
     * The UI key light in world space (toward the light, unit length): the engine
     * preview rig's camera-space key light rotated by the inverse view.
     */
    static Vector3f uiPreviewKeyLight(Matrix4f viewMatrix, Matrix4f scratch, Vector3f out) {
        return ModelLighting.previewKeyLight(viewMatrix, scratch, out);
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

    /** Returns the variant's GPU upload, uploading every variant of the asset on first use. */
    private UploadedModel resolveVariantGpu(SbeEntityAsset asset, String variantName) {
        Map<String, UploadedModel> variants = assetGpu.get(asset);
        if (variants == null) {
            variants = new HashMap<>();
            for (Map.Entry<String, SbeModelGeometry> e : asset.variants().entrySet()) {
                variants.put(e.getKey(), UploadedModel.upload(e.getValue()));
            }
            assetGpu.put(asset, variants);
        }
        if (variantName != null) {
            for (Map.Entry<String, UploadedModel> e : variants.entrySet()) {
                if (e.getKey().equalsIgnoreCase(variantName)) {
                    return e.getValue();
                }
            }
        }
        return variants.get(SbeEntityAsset.DEFAULT_VARIANT);
    }

    /** Release all GPU resources. */
    public void cleanup() {
        if (!initialized) return;
        models.cleanup();
        for (Map<String, UploadedModel> variants : assetGpu.values()) {
            for (UploadedModel gpu : variants.values()) {
                gpu.close();
            }
        }
        assetGpu.clear();
        initialized = false;
    }
}
