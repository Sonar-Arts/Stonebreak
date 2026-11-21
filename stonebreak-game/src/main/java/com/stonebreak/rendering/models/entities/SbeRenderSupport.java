package com.stonebreak.rendering.models.entities;

import com.stonebreak.mobs.entities.LivingEntity;
import com.openmason.engine.entity.model.EntityAsset;
import com.openmason.engine.entity.model.EntityGeometry;
import org.joml.Vector3f;

/**
 * Stateless helpers shared by the SBE-driven entity sub-renderers: the mob
 * ground-anchoring contract and the textured/untextured asset test that decides
 * between {@link SbeEntityRenderer#render} and {@link SbeEntityRenderer#renderColored}.
 */
final class SbeRenderSupport {

    private SbeRenderSupport() {
    }

    /**
     * The render position that puts the mob model's rest-pose feet
     * ({@code geometry.restMinY}) exactly on the collision ground plane at
     * {@code position.y - legHeight}. This is the model-placement contract for
     * all mobs: a model authored with its origin {@code legHeight} above its
     * feet gets a zero offset; any other authoring (e.g. origin at the feet)
     * is corrected here instead of floating or sinking.
     */
    static org.joml.Vector3d groundAnchoredPosition(LivingEntity mob, EntityAsset asset) {
        org.joml.Vector3d position = mob.getPosition(); // a copy
        EntityGeometry geometry =
                asset == null ? null : asset.geometryFor(mob.getTextureVariant());
        if (geometry == null) {
            return position;
        }
        float offset = -mob.getLegHeight() - geometry.restMinY() * mob.getScale().y + mob.renderLift();
        position.y += offset;
        return position;
    }

    /**
     * Whether an SBE asset has baked textures. Untextured assets (geometry only,
     * no materials) must be drawn via {@link SbeEntityRenderer#renderColored};
     * the textured path would skip every face and render nothing.
     */
    static boolean isTextured(EntityAsset asset) {
        EntityGeometry geometry = asset == null ? null
                : asset.geometryFor(EntityAsset.DEFAULT_VARIANT);
        return geometry != null && !geometry.materials().isEmpty();
    }

    /** The shared player SBE asset (remote players, decoys, local third-person), or null. */
    static EntityAsset playerAsset() {
        return com.stonebreak.mobs.sbe.SbeEntityRegistry.get(
                com.stonebreak.mobs.entities.EntityType.REMOTE_PLAYER.getSbeObjectId());
    }
}
