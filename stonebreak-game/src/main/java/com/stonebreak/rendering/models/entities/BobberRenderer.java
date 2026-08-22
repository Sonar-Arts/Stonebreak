package com.stonebreak.rendering.models.entities;

import com.stonebreak.mobs.entities.Entity;
import org.joml.Matrix4f;
import org.joml.Vector3f;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Renders the fishing bobber through its SBE asset ({@code SB_Bobber.sbe},
 * loaded lazily on first use) with the render-time bob offset applied; falls
 * back to the plain cube when the asset is missing or its draw throws.
 */
final class BobberRenderer {
    private static final Logger LOGGER = LoggerFactory.getLogger(BobberRenderer.class);

    private final SbeEntityRenderer sbeEntityRenderer;
    private final FallbackCubeRenderer fallback;

    // Cached bobber SBE asset — loaded once on first render.
    private com.stonebreak.mobs.sbe.SbeEntityAsset bobberAsset;
    private boolean bobberAssetLoadAttempted = false;

    BobberRenderer(SbeEntityRenderer sbeEntityRenderer, FallbackCubeRenderer fallback) {
        this.sbeEntityRenderer = sbeEntityRenderer;
        this.fallback = fallback;
    }

    void render(Entity entity, Matrix4f viewMatrix, Matrix4f projectionMatrix,
                com.stonebreak.world.World world, Vector3f cameraPos) {
        if (!bobberAssetLoadAttempted) {
            bobberAssetLoadAttempted = true;
            try {
                bobberAsset = com.stonebreak.mobs.sbe.SbeEntityLoader.load("/sbe/Mobs/SB_Bobber.sbe");
            } catch (Exception e) {
                LOGGER.error("Failed to load bobber SBE: {}", e.getMessage());
            }
        }

        // Apply the gentle bob offset as a render-time Y offset (does not affect physics).
        float bobY = (entity instanceof com.stonebreak.mobs.entities.FishingBobber fb)
                ? fb.getBobOffset() : 0f;
        Vector3f renderPos = new Vector3f(entity.getPosition()).add(0, bobY, 0);

        if (bobberAsset != null) {
            try {
                sbeEntityRenderer.render(
                        bobberAsset,
                        com.stonebreak.mobs.sbe.SbeEntityAsset.DEFAULT_VARIANT,
                        null,
                        0.0f,
                        renderPos,
                        entity.getRotation().y,
                        entity.getScale(),
                        viewMatrix, projectionMatrix, world, cameraPos);
            } catch (Exception e) {
                LOGGER.error("Bobber render failed: {}: {}", e.getClass().getSimpleName(), e.getMessage(), e);
                fallback.render(entity, viewMatrix, projectionMatrix, world, cameraPos);
            }
        } else {
            fallback.render(entity, viewMatrix, projectionMatrix, world, cameraPos);
        }
    }
}
