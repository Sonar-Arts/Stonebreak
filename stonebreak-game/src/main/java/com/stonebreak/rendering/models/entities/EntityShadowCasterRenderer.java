package com.stonebreak.rendering.models.entities;

import com.stonebreak.mobs.entities.Entity;
import com.stonebreak.mobs.entities.EntityType;
import org.joml.Matrix4f;
import org.joml.Vector3f;
import org.joml.Vector4f;

/**
 * Depth-only shadow-caster pass: draws every shadow-casting entity (SBE mobs,
 * remote players, decoys) plus the local player through the SBE flat-colored
 * path into the currently bound shadow framebuffer, culled to the cascade's
 * radius. Drops, projectiles and effect volumes don't cast.
 */
final class EntityShadowCasterRenderer {
    /** Flat white used by the depth-only shadow-caster path (color output is discarded). */
    private static final Vector4f SHADOW_CASTER_COLOR = new Vector4f(1f, 1f, 1f, 1f);

    private final SbeEntityRenderer sbeEntityRenderer;
    private final SbeMobRenderer mobRenderer;

    EntityShadowCasterRenderer(SbeEntityRenderer sbeEntityRenderer, SbeMobRenderer mobRenderer) {
        this.sbeEntityRenderer = sbeEntityRenderer;
        this.mobRenderer = mobRenderer;
    }

    /**
     * Called once per cascade by ShadowMapRenderer, with the cascade's light
     * matrices standing in for view/projection. The shadow FBO has no color
     * attachment, so only depth lands — the flat color is discarded.
     */
    void render(com.stonebreak.player.Player player,
                Matrix4f lightView, Matrix4f lightProj,
                Vector3f cascadeCenter, float cascadeRadius) {
        float cullRadius = cascadeRadius + 8.0f;
        float cullRadiusSq = cullRadius * cullRadius;
        com.stonebreak.mobs.entities.EntityManager entityManager =
                com.stonebreak.core.Game.getEntityManager();
        com.stonebreak.world.World world = com.stonebreak.core.Game.getWorld();
        if (entityManager != null) {
            for (Entity entity : entityManager.getAllEntities()) {
                if (!entity.isAlive()) continue;
                if (!EntityRenderer.isInRenderableChunk(entity, world)) continue;
                Vector3f pos = entity.getPosition();
                float dx = pos.x - cascadeCenter.x;
                float dz = pos.z - cascadeCenter.z;
                if (dx * dx + dz * dz > cullRadiusSq) continue;
                renderEntityShadow(entity, lightView, lightProj);
            }
        }

        // The local player always casts — including first person, where the body
        // model isn't drawn to screen but its shadow still should be.
        if (player != null) {
            com.stonebreak.mobs.sbe.SbeEntityAsset asset = SbeRenderSupport.playerAsset();
            if (asset != null) {
                sbeEntityRenderer.renderColored(asset,
                        com.stonebreak.mobs.sbe.SbeEntityAsset.DEFAULT_VARIANT,
                        com.stonebreak.mobs.sbe.PlayerStateMapping.sbeState(player.getBaseMovementState()),
                        player.getBodyEventTime(),
                        player.getPosition(), player.getBodyYaw(), new Vector3f(1f, 1f, 1f),
                        lightView, lightProj, SHADOW_CASTER_COLOR);
            }
        }
    }

    /** Depth-only draw of one entity, mirroring {@link EntityRenderer#renderEntity}'s SBE bindings. */
    private void renderEntityShadow(Entity entity, Matrix4f lightView, Matrix4f lightProj) {
        EntityType type = entity.getType();

        // Same generic SBE-mob path as renderEntity, through the flat-colored
        // depth-only route (color output is discarded by the shadow FBO).
        if (SbeMobRenderer.handles(entity)) {
            mobRenderer.renderColored((com.stonebreak.mobs.entities.LivingEntity) entity,
                    lightView, lightProj, SHADOW_CASTER_COLOR);
            return;
        }

        if ((type == EntityType.REMOTE_PLAYER || type == EntityType.ILLUSION_DECOY)
                && entity instanceof com.stonebreak.mobs.entities.RemotePlayer rp) {
            com.stonebreak.mobs.sbe.SbeEntityAsset asset = SbeRenderSupport.playerAsset();
            if (asset != null) {
                sbeEntityRenderer.renderColored(asset,
                        com.stonebreak.mobs.sbe.SbeEntityAsset.DEFAULT_VARIANT,
                        com.stonebreak.mobs.sbe.PlayerStateMapping.sbeState(rp.getMovementState()),
                        rp.getAnimationController().getTotalAnimationTime(),
                        rp.getPosition(), rp.getBodyYaw(), rp.getScale(),
                        lightView, lightProj, SHADOW_CASTER_COLOR);
            }
        }
        // Everything else (drops, projectiles, effect volumes) doesn't cast.
    }
}
