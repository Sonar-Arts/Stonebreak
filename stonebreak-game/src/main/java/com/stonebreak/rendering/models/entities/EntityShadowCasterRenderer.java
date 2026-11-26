package com.stonebreak.rendering.models.entities;

import com.openmason.engine.rendering.RenderOrigin;
import com.stonebreak.mobs.entities.Entity;
import com.stonebreak.mobs.entities.EntityType;
import org.joml.Matrix4f;
import org.joml.FrustumIntersection;
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
    private final Matrix4f lightViewProjection = new Matrix4f();
    private final FrustumIntersection lightFrustum = new FrustumIntersection();
    private final Vector3f centerScratch = new Vector3f();
    private final Vector3f playerScratch = new Vector3f();
    private record Caster(Entity entity, Vector3f position, float height, float bound) {}
    private final java.util.List<Caster> casters = new java.util.ArrayList<>();

    EntityShadowCasterRenderer(SbeEntityRenderer sbeEntityRenderer, SbeMobRenderer mobRenderer) {
        this.sbeEntityRenderer = sbeEntityRenderer;
        this.mobRenderer = mobRenderer;
    }

    /** One entity snapshot for all sun cascades and all torch faces this frame. */
    void prepare(com.stonebreak.world.World world) {
        casters.clear();
        var manager = com.stonebreak.core.Game.getEntityManager();
        if (manager == null) return;
        for (Entity entity : manager.getAllEntities()) {
            if (!entity.isAlive() || !EntityRenderer.isInRenderableChunk(entity, world)) continue;
            if (!SbeMobRenderer.handles(entity) && entity.getType() != EntityType.REMOTE_PLAYER
                    && entity.getType() != EntityType.ILLUSION_DECOY
                    && entity.getType() != EntityType.DINGHY) continue;
            float height = entity.getHeight();
            float bound = Math.max(height, Math.max(entity.getWidth(), entity.getLength())) + 1f;
            // Render space (exact double difference): the light views are render-space too.
            casters.add(new Caster(entity, RenderOrigin.toRender(entity.getPosition(), new Vector3f()), height, bound));
        }
    }

    /**
     * Called once per cascade by ShadowMapRenderer, with the cascade's light
     * matrices standing in for view/projection. The shadow FBO has no color
     * attachment, so only depth lands — the flat color is discarded.
     *
     * <p>{@code lightView} is a render-space view ({@link RenderOrigin}) — the
     * models draw from render-space matrices — while {@code cascadeCenter} and
     * the caster positions are world space, so the cull frustum is rebased back
     * to world space before testing them.
     *
     * @param excluded an entity that does not cast into this view (a torch holder, for its
     *                 own torch), or null
     */
    void render(com.stonebreak.player.Player player, Entity excluded,
                Matrix4f lightView, Matrix4f lightProj,
                org.joml.Vector3dc cascadeCenterWorld, float cascadeRadius) {
        float cullRadius = cascadeRadius + 8.0f;
        float cullRadiusSq = cullRadius * cullRadius;
        // Everything in render space: the light view, the caster snapshots and the cull centre.
        Vector3f cascadeCenter = RenderOrigin.toRender(cascadeCenterWorld, centerScratch);
        lightFrustum.set(lightProj.mul(lightView, lightViewProjection));
        for (Caster caster : casters) {
            if (caster.entity == excluded) continue;
            Vector3f pos = caster.position;
            float dx = pos.x - cascadeCenter.x;
            float dz = pos.z - cascadeCenter.z;
            if (dx * dx + dz * dz > cullRadiusSq) continue;
            // Especially important for six point-light views: only animate/draw a model
            // into faces it can touch. Padding includes limbs beyond the collision box.
            if (!lightFrustum.testSphere(pos.x, pos.y + caster.height * .5f, pos.z, caster.bound)) continue;
            renderEntityShadow(caster.entity, lightView, lightProj);
        }

        // The local player always casts - including first person, where the body model isn't drawn to screen but
        // its shadow still should be. Its graph palette = the drawn pose (head look, attack layer included).
        Vector3f playerRender = player != null ? RenderOrigin.toRender(player.getPosition(), playerScratch) : null;
        if (player != null && lightFrustum.testSphere(playerRender.x,
                playerRender.y + 1f, playerRender.z, 2f)) {
            castBody(PosedPlayerBody.of(player), lightView, lightProj);
        }
    }

    /** Depth-only draw of a player-shaped body: the palette and base its figure draws. */
    private void castBody(PosedPlayerBody body, Matrix4f lightView, Matrix4f lightProj) {
        if (body == null) return;
        sbeEntityRenderer.renderColoredPosed(body.asset(),
                com.openmason.engine.entity.model.EntityAsset.DEFAULT_VARIANT, body.palette(), body.base(),
                lightView, lightProj, SHADOW_CASTER_COLOR);
    }

    /** Depth-only draw of one entity, mirroring {@link EntityRenderer#renderEntity}'s SBE bindings. */
    private void renderEntityShadow(Entity entity, Matrix4f lightView, Matrix4f lightProj) {
        EntityType type = entity.getType();

        // Same generic SBE-mob path as renderEntity, through the flat-colored
        // depth-only route (color output is discarded by the shadow FBO).
        if (SbeMobRenderer.handles(entity)) {
            if (SbeMobRenderer.castsNoShadow((com.stonebreak.mobs.entities.LivingEntity) entity)) {
                return;
            }
            mobRenderer.renderColored((com.stonebreak.mobs.entities.LivingEntity) entity,
                    lightView, lightProj, SHADOW_CASTER_COLOR);
            return;
        }

        if ((type == EntityType.REMOTE_PLAYER || type == EntityType.ILLUSION_DECOY)
                && entity instanceof com.stonebreak.mobs.entities.RemotePlayer rp) {
            // The body's own graph palette: the shadow is the drawn pose (#457).
            castBody(PosedPlayerBody.of(rp), lightView, lightProj);
            return;
        }
        if (type == EntityType.DINGHY) {
            com.openmason.engine.entity.model.EntityAsset asset =
                    com.stonebreak.mobs.sbe.SbeEntityRegistry.get(type.getSbeObjectId());
            if (asset != null) {
                sbeEntityRenderer.renderColored(asset,
                        com.openmason.engine.entity.model.EntityAsset.DEFAULT_VARIANT, null, 0f,
                        entity.getPosition(), entity.getYaw(), entity.getScale(),
                        lightView, lightProj, SHADOW_CASTER_COLOR);
            }
            return;
        }
        // Everything else (drops, projectiles, effect volumes) doesn't cast.
    }
}
