package com.stonebreak.rendering.models.entities;

import com.stonebreak.mobs.entities.Entity;
import com.stonebreak.mobs.entities.EntityType;
import org.joml.Matrix4f;
import org.joml.Vector3f;
import org.lwjgl.opengl.GL11;

/**
 * Emissive, additively blended cube visuals for effect entities: fire bolt core,
 * null spike, leyline breach zone slab and caltrop clusters. Each type gets its
 * own flat-colour texture and outer-glow scale; the glow pass is drawn with the
 * cube VAO kept bound (issue #177).
 */
final class GlowCubeRenderer {
    private final SimpleCubePipeline pipeline;

    // 1x1 orange texture for the fire bolt core.
    private int fireBoltTexture;

    // 1x1 violet texture for the null spike core.
    private int nullSpikeTexture;

    // 1x1 cyan texture for the leyline breach zone slab.
    private int leylineZoneTexture;

    // 1x1 metallic texture for the Rogue's caltrop clusters.
    private int caltropTexture;

    GlowCubeRenderer(SimpleCubePipeline pipeline) {
        this.pipeline = pipeline;
    }

    void initialize() {
        // Bright orange-yellow for the fire bolt core
        fireBoltTexture = SolidColorTexture.create(255, 140, 0, 255);
        // Arcane violet for the null spike core
        nullSpikeTexture = SolidColorTexture.create(178, 102, 255, 255);
        // Translucent arcane cyan for the zone slab (alpha matters under additive blend)
        leylineZoneTexture = SolidColorTexture.create(64, 210, 255, 110);
        // Cool metallic steel with a faint emissive lift so clusters read in low light (additive blend).
        caltropTexture = SolidColorTexture.create(150, 160, 175, 200);
    }

    /** Whether this renderer draws the given entity type. */
    static boolean handles(EntityType type) {
        return type == EntityType.FIRE_BOLT
                || type == EntityType.NULL_SPIKE
                || type == EntityType.LEYLINE_BREACH_ZONE
                || type == EntityType.CALTROP_CLUSTER;
    }

    /** Dispatches an entity accepted by {@link #handles} to its glow-cube variant. */
    void render(Entity entity, Matrix4f viewMatrix, Matrix4f projectionMatrix, Vector3f cameraPos) {
        EntityType entityType = entity.getType();
        if (entityType == EntityType.FIRE_BOLT) {
            renderFireBolt(entity, viewMatrix, projectionMatrix, cameraPos);
        } else if (entityType == EntityType.NULL_SPIKE) {
            renderGlowCube(entity, nullSpikeTexture, 1.8f, viewMatrix, projectionMatrix, cameraPos);
        } else if (entityType == EntityType.LEYLINE_BREACH_ZONE) {
            renderGlowCube(entity, leylineZoneTexture, 1.0f, viewMatrix, projectionMatrix, cameraPos);
        } else if (entityType == EntityType.CALTROP_CLUSTER) {
            renderGlowCube(entity, caltropTexture, 1.4f, viewMatrix, projectionMatrix, cameraPos);
        }
    }

    private void renderFireBolt(Entity entity, Matrix4f viewMatrix, Matrix4f projectionMatrix,
                                Vector3f cameraPos) {
        // Once the bolt has impacted, the solid core is gone — only the fading
        // trail/impact particles remain (drawn by WorldRenderer).
        if (entity instanceof com.stonebreak.mobs.entities.FireBolt bolt && bolt.isImpacted()) {
            return;
        }

        renderGlowCube(entity, fireBoltTexture, 1.8f, viewMatrix, projectionMatrix, cameraPos);
    }

    /**
     * Draws an entity as an additively blended emissive cube (fire bolts, null spikes,
     * leyline zone slabs), with an optional larger outer glow layer.
     *
     * @param glowScale scale multiplier for the outer glow pass; {@code <= 1} skips it
     */
    private void renderGlowCube(Entity entity, int texture, float glowScale,
                                Matrix4f viewMatrix, Matrix4f projectionMatrix, Vector3f cameraPos) {
        pipeline.begin(texture, viewMatrix, projectionMatrix,
                cameraPos != null ? cameraPos : new Vector3f(0, 0, 0),
                0.0f, new Vector3f(0.1f, 0.3f, 0.5f),
                entity, false); // emissive — never world-lit

        Matrix4f modelMatrix = new Matrix4f()
                .translate(entity.getPosition())
                .rotateY((float) Math.toRadians(entity.getRotation().y))
                .scale(entity.getScale());
        pipeline.setModel(modelMatrix);

        // Additive blending gives a glow/emissive look
        GL11.glEnable(GL11.GL_BLEND);
        GL11.glBlendFunc(GL11.GL_SRC_ALPHA, GL11.GL_ONE);
        GL11.glDepthMask(false);

        pipeline.bindCube();
        pipeline.drawCube();

        if (glowScale > 1f) {
            // Render a larger, semi-transparent outer glow layer. Keep simpleCubeVAO
            // bound across both draws — unbinding between them (then calling
            // glDrawArrays with no VAO) is undefined and on some drivers picks up
            // whichever VAO another renderer last used. After the player switches
            // held items mid-flight, that "last VAO" becomes the new held item's
            // mesh, so the glow quads sample its vertex buffer and stretch the bolt
            // across the screen toward the hand position (issue #177).
            Matrix4f glowMatrix = new Matrix4f()
                    .translate(entity.getPosition())
                    .rotateY((float) Math.toRadians(entity.getRotation().y))
                    .scale(new Vector3f(entity.getScale()).mul(glowScale));
            pipeline.setModel(glowMatrix);
            pipeline.drawCube();
        }
        pipeline.unbindCube();

        GL11.glDepthMask(true);
        GL11.glDisable(GL11.GL_BLEND);

        pipeline.end();
    }

    void cleanup() {
        SolidColorTexture.delete(fireBoltTexture);
        SolidColorTexture.delete(nullSpikeTexture);
        SolidColorTexture.delete(leylineZoneTexture);
        SolidColorTexture.delete(caltropTexture);
    }
}
