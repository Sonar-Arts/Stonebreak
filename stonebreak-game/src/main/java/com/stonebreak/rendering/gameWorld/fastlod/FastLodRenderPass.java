package com.stonebreak.rendering.gameWorld.fastlod;

import com.stonebreak.rendering.shaders.ShaderProgram;
import com.stonebreak.world.fastlod.FastLodManager;

/**
 * Draws the coarse distant-terrain ring built by {@link FastLodManager}.
 * Runs between the detail opaque pass and the transparent/water pass,
 * reusing the already-bound world shader and texture atlas. LOD geometry is
 * authored in world space, so the caller's existing identity model matrix
 * is correct — no extra uniform work beyond resetting the water/pass state.
 *
 * <p>The manager pre-warms LOD nodes for chunks just inside the native render
 * disk (see {@link com.stonebreak.world.fastlod.FastLodBandPolicy}). Those
 * preload entries must not be drawn because they'd z-fight with full-detail
 * chunk geometry; this pass culls them by Chebyshev distance so only entries
 * beyond the native-render radius reach the GPU.
 */
public final class FastLodRenderPass {

    public void render(ShaderProgram shader, FastLodManager manager,
                       int playerChunkX, int playerChunkZ) {
        if (manager == null) return;
        manager.applyGLUpdates();

        var entries = manager.visibleHandles();
        if (entries.isEmpty()) return;

        int inner = manager.innerRadius();

        shader.setUniform("u_renderPass", 0);
        shader.setUniform("u_waterDepthOffset", 0.0f);

        for (FastLodManager.Entry entry : entries) {
            int dx = Math.abs(entry.key.chunkX() - playerChunkX);
            int dz = Math.abs(entry.key.chunkZ() - playerChunkZ);
            if (Math.max(dx, dz) <= inner) continue;   // preload node — native pass already covers it
            entry.handle.render();
        }
    }
}
