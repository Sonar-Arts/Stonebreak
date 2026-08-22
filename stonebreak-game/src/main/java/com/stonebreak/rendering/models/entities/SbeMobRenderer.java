package com.stonebreak.rendering.models.entities;

import com.stonebreak.mobs.entities.Entity;
import com.stonebreak.mobs.entities.EntityType;
import com.stonebreak.mobs.entities.LivingEntity;
import org.joml.Matrix4f;
import org.joml.Vector3f;
import org.joml.Vector4f;

/**
 * The single generic path for every AI-driven SBE mob (cow, goose, ...): asset
 * from the registry by the type's object id, clip name from
 * {@link com.stonebreak.mobs.sbe.MobStateMapping}, clip time from
 * {@code MobAI.clipTime}, and ground anchoring so the feet rest on the collision
 * ground. Provides the lit colour draw (plus socket attachments), the
 * flat-colour depth-only draw, the debug wireframe, and the glossary preview.
 * New mobs need no renderer changes at all.
 */
final class SbeMobRenderer {
    private final SbeEntityRenderer sbeEntityRenderer;
    private final EntityAttachmentRenderer attachmentRenderer;

    SbeMobRenderer(SbeEntityRenderer sbeEntityRenderer, EntityAttachmentRenderer attachmentRenderer) {
        this.sbeEntityRenderer = sbeEntityRenderer;
        this.attachmentRenderer = attachmentRenderer;
    }

    /** Whether the entity is an SBE-driven mob with an AI (the only inputs this path needs). */
    static boolean handles(Entity entity) {
        return entity.getType().getSbeObjectId() != null
                && entity instanceof LivingEntity mob
                && mob.getAI() != null;
    }

    private static com.stonebreak.mobs.sbe.SbeEntityAsset assetFor(EntityType type) {
        return com.stonebreak.mobs.sbe.SbeEntityRegistry.get(type.getSbeObjectId());
    }

    private static String stateName(EntityType type, LivingEntity mob) {
        return com.stonebreak.mobs.sbe.MobStateMapping.sbeState(type, mob.getAI().getCurrentState());
    }

    private static float clipTime(LivingEntity mob) {
        return mob.getAI().clipTime(mob.getAnimationController().getTotalAnimationTime());
    }

    /** Lit, textured draw of the mob followed by its socket attachments. */
    void render(LivingEntity mob, Matrix4f viewMatrix, Matrix4f projectionMatrix,
                com.stonebreak.world.World world, Vector3f cameraPos) {
        EntityType entityType = mob.getType();
        com.stonebreak.mobs.sbe.SbeEntityAsset asset = assetFor(entityType);
        String stateName = stateName(entityType, mob);
        float clipTime = clipTime(mob);
        Vector3f anchoredPos = SbeRenderSupport.groundAnchoredPosition(mob, asset);
        sbeEntityRenderer.render(
                asset,
                mob.getTextureVariant(),
                stateName,
                clipTime,
                anchoredPos,
                mob.getRotation().y,
                mob.getScale(),
                viewMatrix, projectionMatrix, world, cameraPos);
        attachmentRenderer.render(mob, asset, mob.getTextureVariant(),
                com.stonebreak.mobs.sbe.AnimState.single(stateName, clipTime),
                anchoredPos, mob.getRotation().y, mob.getScale(), 0f, 0f,
                viewMatrix, projectionMatrix, world, cameraPos);
    }

    /** Flat-colored draw with the same bindings as {@link #render} (shadow-caster pass). */
    void renderColored(LivingEntity mob, Matrix4f viewMatrix, Matrix4f projectionMatrix, Vector4f color) {
        EntityType type = mob.getType();
        com.stonebreak.mobs.sbe.SbeEntityAsset asset = assetFor(type);
        sbeEntityRenderer.renderColored(
                asset,
                mob.getTextureVariant(),
                stateName(type, mob),
                clipTime(mob),
                SbeRenderSupport.groundAnchoredPosition(mob, asset), mob.getRotation().y, mob.getScale(),
                viewMatrix, projectionMatrix, color);
    }

    /** Debug wireframe with the same bindings as {@link #render}, so it tracks the model exactly. */
    void renderWireframe(LivingEntity mob, Matrix4f viewMatrix, Matrix4f projectionMatrix, Vector4f color) {
        EntityType entityType = mob.getType();
        com.stonebreak.mobs.sbe.SbeEntityAsset asset = assetFor(entityType);
        sbeEntityRenderer.renderWireframe(
                asset,
                mob.getTextureVariant(),
                stateName(entityType, mob),
                clipTime(mob),
                SbeRenderSupport.groundAnchoredPosition(mob, asset),
                mob.getRotation().y,
                mob.getScale(),
                viewMatrix, projectionMatrix, color);
    }

    /** Glossary/preview pose: caller-chosen variant and state, no live entity, no fog. */
    void renderPreview(EntityType type, String variant, String stateName,
                       float animationTime, Vector3f position, float yawDegrees,
                       Vector3f scale, Matrix4f viewMatrix, Matrix4f projectionMatrix) {
        com.stonebreak.mobs.sbe.SbeEntityAsset asset = assetFor(type);
        if (asset == null) return;
        sbeEntityRenderer.render(asset, variant, stateName, animationTime,
                position, yawDegrees, scale, viewMatrix, projectionMatrix, null, null);
    }
}
