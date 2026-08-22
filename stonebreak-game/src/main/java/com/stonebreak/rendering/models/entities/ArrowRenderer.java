package com.stonebreak.rendering.models.entities;

import com.openmason.engine.rendering.shaders.ShaderProgram;
import com.stonebreak.mobs.entities.Entity;
import org.joml.Matrix4f;
import org.joml.Vector3f;

/**
 * Draws arrow projectiles as an elongated brown cuboid (a scaled simple cube)
 * rotated to the arrow's travel yaw. Also holds the voxelized-sprite renderer
 * wired in by {@link EntityRenderer#initializeArrowRenderer} for a future
 * sprite-based arrow visual.
 */
final class ArrowRenderer {
    private final SimpleCubePipeline pipeline;

    // 1x1 brown texture for arrow projectiles.
    private int arrowTexture;

    // Voxelized sprite renderer for arrow projectiles (uses the main scene shader).
    private com.stonebreak.rendering.player.items.voxelization.VoxelizedSpriteRenderer arrowVoxelRenderer;

    ArrowRenderer(SimpleCubePipeline pipeline) {
        this.pipeline = pipeline;
    }

    void initialize() {
        arrowTexture = SolidColorTexture.create(139, 90, 43, 255); // saddle-brown
    }

    /**
     * Wires up the voxelized-sprite renderer used for arrow projectiles.
     * Must be called after initialize() and after the main scene shader is ready.
     */
    void initializeVoxelRenderer(ShaderProgram mainSceneShader) {
        if (mainSceneShader != null) {
            arrowVoxelRenderer = new com.stonebreak.rendering.player.items.voxelization.VoxelizedSpriteRenderer(mainSceneShader);
        }
    }

    /**
     * Renders an arrow as an elongated brown cylinder approximated with a scaled cube.
     * Rotated to face the arrow's velocity direction (stored in entity.rotation.y).
     */
    void render(Entity entity, Matrix4f viewMatrix, Matrix4f projectionMatrix) {
        pipeline.begin(arrowTexture, viewMatrix, projectionMatrix, new Vector3f(0, 0, 0),
                0.0f, new Vector3f(0.1f, 0.3f, 0.5f), entity, true);

        // Elongated along local Z (direction of travel); yaw from rotation.y
        Matrix4f modelMatrix = new Matrix4f()
                .translate(entity.getPosition())
                .rotateY((float) Math.toRadians(entity.getRotation().y))
                .scale(0.06f, 0.06f, 0.5f);
        pipeline.setModel(modelMatrix);

        pipeline.bindCube();
        pipeline.drawCube();
        pipeline.unbindCube();

        pipeline.end();
    }

    void cleanup() {
        SolidColorTexture.delete(arrowTexture);
    }
}
