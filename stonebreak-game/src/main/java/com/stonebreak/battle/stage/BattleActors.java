package com.stonebreak.battle.stage;

import com.stonebreak.battle.api.ActorPose;
import com.stonebreak.battle.api.BattleStageLayout;
import com.stonebreak.battle.api.BattleView;
import com.stonebreak.battle.api.CombatantId;
import com.stonebreak.mobs.entities.EntityType;
import com.stonebreak.mobs.sbe.EntityAttachments;
import com.stonebreak.mobs.sbe.SbeEntityAsset;
import com.stonebreak.mobs.sbe.SbeEntityRegistry;
import com.stonebreak.mobs.sbe.SbeModelGeometry;
import com.stonebreak.rendering.models.entities.EntityRenderer;
import org.joml.Matrix4f;
import org.joml.Vector3f;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Draws the two combatants as posed SBE previews: no entities, no AI, nothing in the arena's
 * {@code EntityManager}. Called from the arena branch of {@code WorldRenderer.renderWorld}, so the
 * actors land in the scene framebuffer and arena cover occludes them.
 */
public final class BattleActors {
    private BattleActors() {}

    private static final Logger logger = LoggerFactory.getLogger(BattleActors.class);

    /** Asset-only model: it has no {@link EntityType}, so it is resolved by object id. */
    public static final String ARCHON_OBJECT_ID = "stonebreak:ice_archon";
    public static final String ARCHON_VARIANT = "default";

    /** An actor this faded is not drawn at all. */
    private static final float MIN_VISIBLE_PRESENCE = 0.001f;

    private static boolean poseFailureLogged;
    private static boolean drawFailureLogged;
    private static boolean missingAssetLogged;

    /** New encounter: problems are logged once per encounter, not once per JVM. */
    static void resetDiagnostics() {
        poseFailureLogged = false;
        drawFailureLogged = false;
        missingAssetLogged = false;
    }

    /** Draws both actors when a Focus battle is running; a no-op otherwise. Main (GL) thread only. */
    public static void renderIfActive(EntityRenderer entityRenderer, Matrix4f projection, Matrix4f view) {
        BattleView battle = FocusBattle.view();
        BattleStageLayout layout = FocusBattle.layout();
        if (battle == null || layout == null || entityRenderer == null) {
            return;
        }

        try {
            draw(entityRenderer, battle, layout, projection, view);
        } catch (RuntimeException e) {
            // An exception escaping the world pass is a game crash; a missing actor is not.
            if (!drawFailureLogged) {
                drawFailureLogged = true;
                logger.error("Failed to draw the battle actors", e);
            }
        }
    }

    private static void draw(EntityRenderer entityRenderer, BattleView battle, BattleStageLayout layout,
                             Matrix4f projection, Matrix4f view) {
        ActorPose monkPose = poseOf(battle, CombatantId.MONK);
        ActorPose archonPose = poseOf(battle, CombatantId.ARCHON);
        Vector3f unitScale = new Vector3f(1f);

        if (monkPose.presence() > MIN_VISIBLE_PRESENCE) {
            ActorPlacement.Placement at = ActorPlacement.place(layout, CombatantId.MONK, monkPose,
                    restMinY(EntityType.REMOTE_PLAYER.getSbeObjectId(), SbeEntityAsset.DEFAULT_VARIANT), 1f, false);
            // LOCAL_PLAYER attachments: the monk wears the player's own hair and clothing.
            entityRenderer.renderPlayerPreview(monkPose.sbeState(), monkPose.clipTime(), at.position(),
                    at.yawDegrees(), unitScale, view, projection, EntityAttachments.LOCAL_PLAYER);
        }

        if (archonPose.presence() > MIN_VISIBLE_PRESENCE) {
            // The death clip carries its own collapse (artist handoff): never sink on top of it.
            ActorPlacement.Placement at = ActorPlacement.place(layout, CombatantId.ARCHON, archonPose,
                    restMinY(ARCHON_OBJECT_ID, ARCHON_VARIANT), 1f, false);
            entityRenderer.renderSbePreview(ARCHON_OBJECT_ID, ARCHON_VARIANT, archonPose.sbeState(),
                    archonPose.clipTime(), at.position(), at.yawDegrees(), unitScale, view, projection);
        }
    }

    /**
     * A render-path exception would take the whole game down, so a model that cannot answer yet
     * (or throws mid-frame) degrades to the idle pose instead.
     */
    private static ActorPose poseOf(BattleView battle, CombatantId id) {
        try {
            ActorPose pose = battle.combatant(id).pose();
            return pose != null ? pose : ActorPose.IDLE;
        } catch (RuntimeException e) {
            if (!poseFailureLogged) {
                poseFailureLogged = true;
                logger.warn("Battle model could not supply actor poses; drawing idle actors", e);
            }
            return ActorPose.IDLE;
        }
    }

    /** Rest-pose "feet" height of an asset's model space; 0 (origin at the feet) when unknown. */
    private static float restMinY(String objectId, String variant) {
        SbeEntityAsset asset = SbeEntityRegistry.get(objectId);
        if (asset == null && !missingAssetLogged) {
            // The preview renderer skips an unknown object id silently: without this the fight would
            // run against an invisible opponent with nothing in the log.
            missingAssetLogged = true;
            logger.error("Battle actor asset '{}' is not registered; it will not be drawn", objectId);
        }
        SbeModelGeometry geometry = asset == null ? null : asset.geometryFor(variant);
        return geometry == null ? 0f : geometry.restMinY();
    }
}
