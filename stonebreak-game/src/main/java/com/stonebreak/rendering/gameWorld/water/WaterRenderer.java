package com.stonebreak.rendering.gameWorld.water;

import java.util.List;

import com.openmason.engine.rendering.shaders.ShaderProgram;
import com.openmason.engine.rendering.shaders.ShaderResourceLoader;
import com.openmason.engine.voxel.mms.mmsCore.MmsRenderableHandle;
import com.stonebreak.world.chunk.Chunk;
import org.joml.Matrix4f;
import org.joml.Vector3f;
import org.joml.Vector4f;
import org.lwjgl.opengl.GL13;
import org.lwjgl.opengl.GL14;
import org.lwjgl.opengl.GL30;

import static org.lwjgl.opengl.GL11.*;

/**
 * Dedicated water renderer — draws every chunk's water mesh with its own
 * shader in a single pass, replacing the old approach of redrawing the whole
 * chunk VAO with per-fragment water discards (world shader
 * {@code u_renderPass}/{@code u_translucentLayer}).
 *
 * <p>Water geometry is built into a separate per-chunk mesh by
 * {@code MmsCcoAdapter} (see the water-mesh attribute semantics documented in
 * {@code shaders/water/water.vert}) and swapped by {@code MmsMeshPipeline}
 * alongside the atlas handle. The surface pattern is fully procedural in the
 * fragment shader — no texture binds, no CPU tile animation.
 *
 * <p>Invoked by {@code WorldRenderer} immediately after the transparent
 * (ice/translucent-solid) pass, before transparent drops — the same
 * compositing slot the old water sub-pass occupied. Follows the engine
 * {@code CloudRenderer} pattern: owns its shader, saves/restores all GL state
 * it touches. Face culling is disabled so the surface is visible from below
 * ({@code gl_FrontFacing} flips the normal in the fragment shader).
 *
 * <p>Draws in two sub-passes so water is self-occluding (like ice): a
 * depth-only prepass writes the nearest water surface, then the color pass
 * (depth writes off, LEQUAL) blends exactly one water layer — water faces are
 * never visible through other water. Side effect: the nearest water surface's
 * depth remains in the depth buffer, so passes drawn after this one are
 * correctly occluded by water in front of them (WorldRenderer orders
 * see-through-water content — transparent drops, crack overlay — before this
 * pass).
 *
 * <p>Before the prepass the opaque scene depth is copied into a texture, so
 * the fragment shader can measure how much water each eye ray crosses before
 * it reaches whatever lies behind the surface, and colour/fade by that
 * thickness (density) rather than by the column depth under the face.
 */
public class WaterRenderer {

    /**
     * A FastLOD node's water-sheet mesh plus its crossfade opacity for this
     * frame (collected by {@code FastLodRenderPass} alongside the terrain
     * draw). Sheets render through this same shader as native water, so the
     * near/far water handover is seamless; the fade drives the same
     * screen-door dither the node's terrain mesh uses in the world shader.
     *
     * <p>With region batching active, only CROSSFADING sheets travel through
     * this record (they need a per-node {@code uLodFade}); fully-faded sheets
     * are bucketed into the {@code FastLodRegionBatcher}'s water regions and
     * draw as multidraws. Exactly one of {@code handle} (legacy per-node
     * mesh) / {@code regionHandle} (region-arena mesh) is non-null.
     */
    public record LodWaterNode(MmsRenderableHandle handle,
                               com.openmason.engine.voxel.mms.mmsRegion.MmsRegionMeshHandle regionHandle,
                               float fade) {
        void render() {
            if (regionHandle != null) {
                if (!regionHandle.isClosed() && !regionHandle.region().isDeleted()) {
                    regionHandle.render();
                }
            } else if (handle != null) {
                handle.render();
            }
        }
    }

    /** Texture unit of the opaque-scene depth copy (5 = shadow map, 7 = MMS quads). */
    public static final int SCENE_DEPTH_TEXTURE_UNIT = 6;

    private final ShaderProgram shader;
    private final Matrix4f invProjection = new Matrix4f();
    private final Vector4f viewport = new Vector4f();
    private final int[] viewportBuf = new int[4];
    private int sceneDepthTexture;
    private int sceneDepthWidth;
    private int sceneDepthHeight;

    public WaterRenderer() {
        shader = new ShaderProgram();
        try {
            shader.createVertexShader(loadWaterShader("/shaders/water/water.vert"));
            shader.createFragmentShader(loadWaterShader("/shaders/water/water.frag"));
            shader.link();

            shader.createUniform("uProjection");
            shader.createUniform("u_quads");
            shader.bind();
            shader.setUniform("u_quads", com.openmason.engine.voxel.mms.mmsCore.MmsQuadCodec.QUAD_TEXTURE_UNIT);
            shader.unbind();
            shader.createUniform("uView");
            shader.createUniform("uTime");
            shader.createUniform("uWavesEnabled");
            shader.createUniform("uWaveFadeEnd");
            shader.createUniform("uSunDirection");
            shader.createUniform("uAmbientLight");
            shader.createUniform("uCameraPos");
            shader.createUniform("uFogColor");
            shader.createUniform("uFogStart");
            shader.createUniform("uFogEnd");
            shader.createUniform("uFogSpherical");
            shader.createUniform("uLodFade");
            shader.createUniform("uSceneDepth");
            shader.createUniform("uInvProjection");
            shader.createUniform("uViewport");
            shader.bind();
            shader.setUniform("uSceneDepth", SCENE_DEPTH_TEXTURE_UNIT);
            shader.unbind();
        } catch (Exception e) {
            throw new RuntimeException("Failed to initialize water shader", e);
        }
    }

    /** Lambda (not a method reference) so the caller-sensitive lookup happens in this module. */
    private static String loadWaterShader(String resourcePath) {
        return ShaderResourceLoader.load(resourcePath, path -> WaterRenderer.class.getResourceAsStream(path));
    }

    /**
     * Draws the water meshes of the given chunks (expected back-to-front —
     * reuse the transparent pass's sorted list).
     *
     * @param chunksBackToFront chunks sorted farthest-first
     * @param projection        camera projection matrix
     * @param view              camera view matrix
     * @param cameraPos         camera world position
     * @param time              total elapsed time in seconds (drives waves/flow)
     * @param sunDirection      normalized sun direction (world shader convention)
     * @param ambientLight      ambient light level 0..1 from TimeOfDay
     * @param wavesEnabled      water animation setting (freezes waves + scroll)
     * @param fogColor          atmospheric fog color (sky color from TimeOfDay)
     * @param fogStart          distance where fog begins (blocks)
     * @param fogEnd            distance of full fog; {@code <= fogStart} disables
     * @param fogSpherical      measure those distances as a sphere around the
     *                          eye instead of a vertical cylinder — true
     *                          underwater, where the fog is a volume
     * @param lodWater          crossfading FastLOD water-sheet nodes for this frame
     *                          (nullable/empty when LOD is off); fully-faded sheets
     *                          come through {@code lodBatcher}'s buckets instead
     * @param lodBatcher        LOD region batcher holding this frame's solid
     *                          water-sheet buckets (null when LOD/regions off)
     * @param lodStamp          the batcher cycle stamp returned by this frame's
     *                          {@code FastLodRenderPass.render}
     */
    public void render(List<Chunk> chunksBackToFront, Matrix4f projection, Matrix4f view,
                       Vector3f cameraPos, float time, Vector3f sunDirection,
                       float ambientLight, boolean wavesEnabled,
                       Vector3f fogColor, float fogStart, float fogEnd, boolean fogSpherical,
                       List<LodWaterNode> lodWater,
                       com.stonebreak.rendering.gameWorld.fastlod.FastLodRegionBatcher lodBatcher,
                       int lodStamp) {
        // Save GL state we touch.
        boolean depthTestEnabled = glIsEnabled(GL_DEPTH_TEST);
        boolean cullFaceEnabled = glIsEnabled(GL_CULL_FACE);
        boolean blendEnabled = glIsEnabled(GL_BLEND);
        boolean depthMaskEnabled = glGetBoolean(GL_DEPTH_WRITEMASK);
        int currentDepthFunc = glGetInteger(GL_DEPTH_FUNC);
        int currentBlendSrc = glGetInteger(GL_BLEND_SRC);
        int currentBlendDst = glGetInteger(GL_BLEND_DST);
        boolean polygonOffsetEnabled = glIsEnabled(GL_POLYGON_OFFSET_FILL);
        float currentOffsetFactor = glGetFloat(GL_POLYGON_OFFSET_FACTOR);
        float currentOffsetUnits = glGetFloat(GL_POLYGON_OFFSET_UNITS);

        // Translucent water: depth-test against the world, standard alpha
        // blend, no culling (underwater views see back faces).
        glEnable(GL_DEPTH_TEST);
        glDepthFunc(GL_LEQUAL);
        glDisable(GL_CULL_FACE);
        glEnable(GL_BLEND);
        glBlendFunc(GL_SRC_ALPHA, GL_ONE_MINUS_SRC_ALPHA);
        // Pull water fragments a hair toward the camera: water side faces lie
        // exactly on cell-boundary planes shared with depth-written cutout
        // geometry (leaf/glass side faces) and bottom faces can coincide with
        // partial-block tops — without a bias those coplanar pairs z-fight as
        // sparkling speckles. Sub-texel offset, invisible otherwise. Applied
        // to both sub-passes below so their depths match exactly.
        glEnable(GL_POLYGON_OFFSET_FILL);
        glPolygonOffset(-1.0f, -1.0f);

        // Snapshot the opaque depth before the prepass overwrites it with the
        // water surface: the shader measures water thickness against it.
        int previousActiveTexture = glGetInteger(GL13.GL_ACTIVE_TEXTURE);
        GL13.glActiveTexture(GL13.GL_TEXTURE0 + SCENE_DEPTH_TEXTURE_UNIT);
        int previousTexture = glGetInteger(GL_TEXTURE_BINDING_2D);
        copySceneDepth();

        shader.bind();
        shader.setUniform("uProjection", projection);
        shader.setUniform("uInvProjection", invProjection.set(projection).invert());
        shader.setUniform("uViewport", viewport);
        shader.setUniform("uView", view);
        shader.setUniform("uTime", time);
        shader.setUniform("uWavesEnabled", wavesEnabled);
        // Waves fade to zero at the edge of the near-chunk range so wavy near water
        // meets the flat FastLOD sea sheets (which carry no displacement) seamlessly.
        shader.setUniform("uWaveFadeEnd",
            com.stonebreak.config.Settings.getInstance().getRenderDistance()
                * (float) com.stonebreak.world.operations.WorldConfiguration.CHUNK_SIZE);
        shader.setUniform("uSunDirection", sunDirection);
        shader.setUniform("uAmbientLight", ambientLight);
        com.stonebreak.rendering.lighting.DynamicLights.applyTo(shader);
        // Render space, to difference against the render-space mesh position...
        shader.setUniform("uCameraPos",
            com.openmason.engine.rendering.RenderOrigin.toRender(cameraPos, new org.joml.Vector3f()));
        // ...and the origin itself, for the wave/flow lattices, which are
        // world-space functions and must not slide when the origin steps.
        shader.setVec2("uRenderOrigin", new org.joml.Vector2f(
            com.openmason.engine.rendering.RenderOrigin.x(),
            com.openmason.engine.rendering.RenderOrigin.z()));
        shader.setUniform("uFogColor", fogColor);
        shader.setUniform("uFogStart", fogStart);
        shader.setUniform("uFogEnd", fogEnd);
        shader.setUniform("uFogSpherical", fogSpherical);

        // Two sub-passes make water self-occluding, like ice: looking through
        // water never shows other water faces (flowing step faces, far walls,
        // sealed junction overlaps).
        //
        // Pass 1 — depth-only prepass: writes the nearest water surface into
        // the depth buffer (no color output). LOD sheets participate so far
        // water self-occludes exactly like native water; their dither discard
        // runs here too, keeping both sub-passes' depths consistent.
        glColorMask(false, false, false, false);
        glDepthMask(true);
        drawWaterMeshes(chunksBackToFront, lodWater, lodBatcher, lodStamp);

        // Pass 2 — color pass: no depth writes; LEQUAL passes only fragments
        // on that nearest surface, so exactly one water layer blends.
        glColorMask(true, true, true, true);
        glDepthMask(false);
        drawWaterMeshes(chunksBackToFront, lodWater, lodBatcher, lodStamp);

        GL30.glBindVertexArray(0);

        shader.unbind();

        // Restore GL state.
        glBindTexture(GL_TEXTURE_2D, previousTexture);
        GL13.glActiveTexture(previousActiveTexture);
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
        if (polygonOffsetEnabled) {
            glEnable(GL_POLYGON_OFFSET_FILL);
        } else {
            glDisable(GL_POLYGON_OFFSET_FILL);
        }
        glPolygonOffset(currentOffsetFactor, currentOffsetUnits);
    }

    /**
     * Copies the current depth buffer (opaque scene only at this point) into
     * {@link #sceneDepthTexture}, bound on the active unit, (re)allocating it
     * when the viewport size changes. Also records the viewport for the shader.
     */
    private void copySceneDepth() {
        glGetIntegerv(GL_VIEWPORT, viewportBuf);
        int w = Math.max(1, viewportBuf[2]);
        int h = Math.max(1, viewportBuf[3]);
        viewport.set(viewportBuf[0], viewportBuf[1], w, h);
        if (sceneDepthTexture == 0) {
            sceneDepthTexture = glGenTextures();
        }
        glBindTexture(GL_TEXTURE_2D, sceneDepthTexture);
        if (w != sceneDepthWidth || h != sceneDepthHeight) {
            glTexImage2D(GL_TEXTURE_2D, 0, GL14.GL_DEPTH_COMPONENT24, w, h, 0,
                    GL_DEPTH_COMPONENT, GL_UNSIGNED_INT, (java.nio.ByteBuffer) null);
            glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MIN_FILTER, GL_NEAREST);
            glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MAG_FILTER, GL_NEAREST);
            glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_WRAP_S, GL13.GL_CLAMP_TO_EDGE);
            glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_WRAP_T, GL13.GL_CLAMP_TO_EDGE);
            sceneDepthWidth = w;
            sceneDepthHeight = h;
        }
        glCopyTexSubImage2D(GL_TEXTURE_2D, 0, 0, 0, viewportBuf[0], viewportBuf[1], w, h);
    }

    private void drawWaterMeshes(List<Chunk> chunksBackToFront, List<LodWaterNode> lodWater,
                                 com.stonebreak.rendering.gameWorld.fastlod.FastLodRegionBatcher lodBatcher,
                                 int lodStamp) {
        // Native chunks render fully solid; the depth prepass makes draw order
        // between water meshes irrelevant (only the nearest layer survives),
        // so LOD sheets can simply follow the chunk list.
        shader.setUniform("uLodFade", 1.0f);
        if (com.stonebreak.rendering.gameWorld.regions.ChunkRegionRenderer.isEnabled()) {
            // One multidraw per region holding water geometry (water meshes
            // live in their own region arenas, same 40-byte vertex layout).
            com.stonebreak.rendering.gameWorld.regions.ChunkRegionRenderer.getInstance()
                .drawChunks(chunksBackToFront,
                    com.stonebreak.rendering.gameWorld.regions.ChunkRegionRenderer.LAYER_WATER);
        } else {
            for (Chunk chunk : chunksBackToFront) {
                if (chunk.hasWaterMesh()) {
                    chunk.renderWater();
                }
            }
        }
        // Fully-faded LOD sheets: one multidraw per touched LOD region
        // (buckets were filled by this frame's FastLodRenderPass; the same
        // stamp serves both water sub-passes).
        if (lodBatcher != null) {
            lodBatcher.drawWater(lodStamp);
        }
        if (lodWater == null || lodWater.isEmpty()) {
            return;
        }
        // Crossfading sheets (ring edges) draw individually with their fade.
        float boundFade = 1.0f;
        for (LodWaterNode node : lodWater) {
            if (node.fade() != boundFade) {
                shader.setUniform("uLodFade", node.fade());
                boundFade = node.fade();
            }
            node.render();
        }
        if (boundFade != 1.0f) {
            shader.setUniform("uLodFade", 1.0f);
        }
    }

    /** Releases the shader and depth copy. Water mesh handles are owned by their chunks. */
    public void cleanup() {
        if (shader != null) {
            shader.cleanup();
        }
        if (sceneDepthTexture != 0) {
            glDeleteTextures(sceneDepthTexture);
            sceneDepthTexture = 0;
        }
    }
}
