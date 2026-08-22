package com.stonebreak.rendering.models.entities;

import com.stonebreak.mobs.entities.Entity;
import org.joml.Matrix4f;
import org.joml.Vector3f;

/**
 * Last-resort entity visual: a white, world-lit unit cube scaled/rotated by the
 * entity transform, with underwater fog when the camera is submerged. Used for
 * any entity type no dedicated renderer claims and as the bobber's fallback.
 */
final class FallbackCubeRenderer {
    private final SimpleCubePipeline pipeline;

    // 1x1 white texture for the fallback cube.
    private int fallbackTexture;

    FallbackCubeRenderer(SimpleCubePipeline pipeline) {
        this.pipeline = pipeline;
    }

    void initialize() {
        fallbackTexture = SolidColorTexture.create(255, 255, 255, 255);
    }

    void render(Entity entity, Matrix4f viewMatrix, Matrix4f projectionMatrix,
                com.stonebreak.world.World world, Vector3f cameraPos) {
        float fogDensity = 0.0f;
        Vector3f fogColor = new Vector3f(0.1f, 0.3f, 0.5f);
        if (world != null && cameraPos != null
                && world.isPositionUnderwater((int) Math.floor(cameraPos.x),
                        (int) Math.floor(cameraPos.y), (int) Math.floor(cameraPos.z))) {
            fogDensity = 0.15f;
        }

        pipeline.begin(fallbackTexture, viewMatrix, projectionMatrix,
                cameraPos != null ? cameraPos : new Vector3f(0, 0, 0),
                fogDensity, fogColor, entity, true);

        Matrix4f modelMatrix = new Matrix4f()
            .translate(entity.getPosition())
            .rotateY((float) Math.toRadians(entity.getRotation().y))
            .scale(entity.getScale());

        pipeline.setModel(modelMatrix);

        pipeline.bindCube();
        pipeline.drawCube();
        pipeline.unbindCube();

        pipeline.end();
    }

    void cleanup() {
        SolidColorTexture.delete(fallbackTexture);
    }
}
