package com.stonebreak.rendering.gameWorld.lod;

import com.stonebreak.rendering.shaders.ShaderProgram;
import com.stonebreak.world.lod.LodManager;

/**
 * Draws the coarse distant-terrain ring. Runs between the detail opaque pass
 * and the transparent/water pass, reusing the already-bound world shader and
 * texture atlas. LOD geometry is authored in world space, so the caller's
 * existing identity model matrix is correct — no extra uniform work.
 */
public final class LodRenderPass {

    public void render(ShaderProgram shader, LodManager lodManager) {
        if (lodManager == null) return;
        lodManager.applyGLUpdates();

        var entries = lodManager.visibleHandles();
        if (entries.isEmpty()) return;

        // Opaque-pass state: write depth, no blending. Matches the preceding
        // detail pass, so we just ensure u_renderPass is 0 in case a caller
        // changes order later.
        shader.setUniform("u_renderPass", 0);
        shader.setUniform("u_waterDepthOffset", 0.0f);

        for (LodManager.Entry entry : entries) {
            entry.handle.render();
        }
    }
}
