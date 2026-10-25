package com.stonebreak.rendering.models.entities;

import com.openmason.engine.entity.sbe.SbeEntityAsset;
import com.stonebreak.mobs.sbe.SbeEntityRegistry;
import com.stonebreak.vehicles.Dinghy;
import org.joml.Matrix4f;
import org.joml.Vector3f;

/**
 * Draws a dinghy from its SBE asset in the rest pose, with each oar turned by the boat's
 * {@link com.stonebreak.vehicles.OarStroke} so the rowing follows the rower's input.
 */
final class DinghyRenderer {

    private final SbeEntityRenderer sbeEntityRenderer;
    private final FallbackCubeRenderer fallback;

    DinghyRenderer(SbeEntityRenderer sbeEntityRenderer, FallbackCubeRenderer fallback) {
        this.sbeEntityRenderer = sbeEntityRenderer;
        this.fallback = fallback;
    }

    void render(Dinghy dinghy, Matrix4f viewMatrix, Matrix4f projectionMatrix,
                com.stonebreak.world.World world, Vector3f cameraPos) {
        SbeEntityAsset asset = SbeEntityRegistry.get(dinghy.getType().getSbeObjectId());
        if (asset == null) {
            fallback.render(dinghy, viewMatrix, projectionMatrix, world, cameraPos);
            return;
        }
        sbeEntityRenderer.render(asset, SbeEntityAsset.DEFAULT_VARIANT, null,
                SbePoseSolver.baseMatrix(dinghy.getPosition(), dinghy.getYaw(), dinghy.getScale()),
                viewMatrix, projectionMatrix, world, cameraPos, 0f, 0f,
                dinghy.getStroke()::extraRotationDeg);
    }
}
