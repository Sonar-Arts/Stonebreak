package com.stonebreak.rendering.gameWorld.fastlod;

import com.openmason.engine.rendering.shaders.ShaderProgram;
import com.stonebreak.rendering.gameWorld.ChunkFrustumCuller;
import com.stonebreak.rendering.gameWorld.water.WaterRenderer;
import com.stonebreak.world.fastlod.FastLodManager;
import com.stonebreak.world.operations.WorldConfiguration;
import org.lwjgl.opengl.GL30;

import java.util.ArrayList;
import java.util.List;

/**
 * Draws the coarse distant-terrain ring built by {@link FastLodManager}.
 * Runs between the detail opaque pass and the transparent/water pass,
 * reusing the already-bound world shader and texture atlas. LOD geometry is
 * authored in world space, so the caller's existing identity model matrix
 * is correct — no extra uniform work beyond resetting the water/pass state.
 *
 * <p>The manager pre-warms LOD nodes for chunks just inside the native render
 * disk (see {@link com.stonebreak.world.fastlod.FastLodBandPolicy}). Handover
 * between those nodes and full-detail chunks is a per-node crossfade rather
 * than a hard distance cut:
 * <ul>
 *   <li>Once the native chunk mesh at a node's column is on the GPU, the node
 *       dissolves out over ~{@code 1/FADE_OUT_PER_SEC} seconds via a
 *       screen-door dither in the world shader ({@code u_lodFade}) — no
 *       blending, so depth stays correct and the pass ordering is unchanged.
 *       The real chunk renders solid underneath for the whole fade, so the
 *       swap reads as a smooth sharpening instead of a pop.</li>
 *   <li>While the native mesh is still meshing/streaming, the preload node
 *       keeps drawing at full opacity — covering the hole that used to appear
 *       at the ring edge before the detail mesh landed.</li>
 *   <li>When a column leaves the native disk (player moving away), a node the
 *       native pass was covering snaps straight to solid — nothing else draws
 *       that column anymore, so fading in would flash a gap.</li>
 *   <li>Fresh nodes at the far edge dissolve in instead of popping.</li>
 * </ul>
 *
 * <p>Every surviving node is then frustum-tested against its chunk footprint
 * and the mesh's exact Y bounds (tracked at build time) — the resident ring
 * surrounds the player on all sides, so typically well over half the nodes
 * are behind or beside the camera and skipping them cuts thousands of draw
 * calls per frame at large LOD ranges. Fade state still advances for culled
 * nodes so transitions don't stall while the player looks away.
 */
public final class FastLodRenderPass {

    /** Column readiness query — true once the native chunk mesh is uploaded. */
    @FunctionalInterface
    public interface NativeChunkTest {
        boolean isRenderable(int chunkX, int chunkZ);
    }

    /** Dissolve-out speed once the native chunk covers a node (~0.4 s). */
    static final float FADE_OUT_PER_SEC = 2.5f;
    /** Dissolve-in speed for fresh nodes and hole-cover reveals (~0.3 s). */
    static final float FADE_IN_PER_SEC = 3.5f;
    /** Clamp for frame delta so hitches don't teleport fades. */
    private static final float MAX_FRAME_DT = 0.1f;

    private long lastFrameNanos;

    /** Crossfading region-resident nodes drawn individually after the batches. */
    private final List<FastLodManager.Entry> fadingRegionNodes = new ArrayList<>();
    /** Cached region-upload adapter; rebuilt only if the batcher instance changes. */
    private FastLodManager.RegionUploader regionUploader;
    private FastLodRegionBatcher adapterTarget;

    /**
     * Draws the opaque LOD terrain meshes and collects the surviving nodes'
     * water sheets for the dedicated water pass later this frame — fades are
     * advanced here exactly once per frame, so the water pass reads the same
     * opacity the terrain rendered with. Fully-faded sheets are bucketed into
     * {@code batcher}'s water regions (drawn as multidraws under the returned
     * cycle stamp); crossfading sheets go into {@code lodWaterOut} with their
     * opacity.
     *
     * <p>With a non-null {@code batcher}, fully-faded terrain nodes draw as
     * ONE multidraw per 16×16-column LOD region instead of a VAO bind + draw
     * per node — at large LOD ranges this collapses thousands of draws into a
     * few dozen. Only nodes mid-crossfade (ring edges) still draw individually
     * for their {@code u_lodFade} uniform.
     *
     * @return the batcher cycle stamp for this frame's water buckets
     *         (0 when no batcher)
     */
    public int render(ShaderProgram shader, FastLodManager manager,
                      int playerChunkX, int playerChunkZ, ChunkFrustumCuller culler,
                      NativeChunkTest nativeChunks, FastLodRegionBatcher batcher,
                      List<WaterRenderer.LodWaterNode> lodWaterOut) {
        if (manager == null) return 0;
        if (batcher != null && adapterTarget != batcher) {
            adapterTarget = batcher;
            final FastLodRegionBatcher b = batcher;
            regionUploader = (water, cx, cz, mesh, minY, maxY) -> {
                float minX = cx * (float) WorldConfiguration.CHUNK_SIZE;
                float minZ = cz * (float) WorldConfiguration.CHUNK_SIZE;
                return b.upload(water ? FastLodRegionBatcher.LAYER_WATER
                                      : FastLodRegionBatcher.LAYER_TERRAIN,
                        cx, cz, mesh,
                        minX, minY, minZ,
                        minX + WorldConfiguration.CHUNK_SIZE, maxY,
                        minZ + WorldConfiguration.CHUNK_SIZE);
            };
        }
        manager.applyGLUpdates(batcher != null ? regionUploader : null);

        int stamp = (batcher != null) ? batcher.beginCycle() : 0;
        var entries = manager.visibleHandles();
        if (entries.isEmpty()) return stamp;

        long now = System.nanoTime();
        float dt = (lastFrameNanos == 0L) ? 0f
                : Math.min((now - lastFrameNanos) * 1e-9f, MAX_FRAME_DT);
        lastFrameNanos = now;

        int inner = manager.innerRadius();

        shader.setUniform("u_renderPass", 0);
        float boundFade = 1.0f;
        fadingRegionNodes.clear();

        for (FastLodManager.Entry entry : entries) {
            int dx = Math.abs(entry.key.chunkX() - playerChunkX);
            int dz = Math.abs(entry.key.chunkZ() - playerChunkZ);
            int dist = Math.max(dx, dz);

            boolean nativeReady = dist <= inner
                    && nativeChunks.isRenderable(entry.key.chunkX(), entry.key.chunkZ());
            float fade = updateFade(entry, dist, inner, nativeReady, dt);
            if (fade <= 0f) continue;

            float minX = entry.key.chunkX() * (float) WorldConfiguration.CHUNK_SIZE;
            float minZ = entry.key.chunkZ() * (float) WorldConfiguration.CHUNK_SIZE;
            if (!culler.isBoxVisible(minX, entry.minY, minZ,
                    minX + WorldConfiguration.CHUNK_SIZE, entry.maxY,
                    minZ + WorldConfiguration.CHUNK_SIZE)) {
                continue;
            }

            if (batcher != null && entry.regionHandle != null) {
                if (fade >= 1f) {
                    batcher.add(FastLodRegionBatcher.LAYER_TERRAIN, entry.regionHandle, stamp);
                } else {
                    fadingRegionNodes.add(entry);
                }
            } else if (entry.handle != null) {
                // Legacy per-node handle (regions disabled or mesh unqualified).
                if (fade != boundFade) {
                    shader.setUniform("u_lodFade", fade);
                    boundFade = fade;
                }
                entry.handle.render();
            }

            if (lodWaterOut != null) {
                if (batcher != null && entry.regionWaterHandle != null) {
                    if (fade >= 1f) {
                        batcher.add(FastLodRegionBatcher.LAYER_WATER, entry.regionWaterHandle, stamp);
                    } else {
                        lodWaterOut.add(new WaterRenderer.LodWaterNode(
                                null, entry.regionWaterHandle, fade));
                    }
                } else if (entry.waterHandle != null) {
                    lodWaterOut.add(new WaterRenderer.LodWaterNode(entry.waterHandle, null, fade));
                }
            }
        }

        if (batcher != null) {
            // Solid nodes: one multidraw per touched LOD region at full opacity.
            if (boundFade != 1.0f) {
                shader.setUniform("u_lodFade", 1.0f);
                boundFade = 1.0f;
            }
            batcher.drawTerrain(stamp);
            // The few crossfading nodes draw individually with their fade.
            for (int i = 0; i < fadingRegionNodes.size(); i++) {
                FastLodManager.Entry entry = fadingRegionNodes.get(i);
                if (entry.regionHandle.isClosed() || entry.regionHandle.region().isDeleted()) {
                    continue;
                }
                if (entry.fade != boundFade) {
                    shader.setUniform("u_lodFade", entry.fade);
                    boundFade = entry.fade;
                }
                entry.regionHandle.render();
            }
            if (!fadingRegionNodes.isEmpty()) {
                GL30.glBindVertexArray(0);
            }
        }

        // Everything after this pass renders solid.
        if (boundFade != 1.0f) {
            shader.setUniform("u_lodFade", 1.0f);
        }
        return stamp;
    }

    /**
     * Advances one node's crossfade state for this frame and returns the
     * opacity to draw it at (0 = skip). Pure state-machine step, extracted
     * for headless testing.
     */
    static float updateFade(FastLodManager.Entry entry, int dist, int inner,
                            boolean nativeReady, float dt) {
        if (dist <= inner && nativeReady) {
            // Detail mesh is live underneath — dissolve out over it.
            entry.nativeCovered = true;
            entry.fade = Math.max(0f, entry.fade - dt * FADE_OUT_PER_SEC);
        } else if (dist > inner && entry.nativeCovered) {
            // Column just left the native disk; the chunk that was covering
            // it no longer draws, so appear immediately — no gap flash.
            entry.nativeCovered = false;
            entry.fade = 1f;
        } else {
            // Hole cover inside the ring edge, or a fresh/uncovered ring node.
            entry.fade = Math.min(1f, entry.fade + dt * FADE_IN_PER_SEC);
        }
        return entry.fade;
    }
}
