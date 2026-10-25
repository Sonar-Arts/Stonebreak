package com.stonebreak.ui.debug;

import com.stonebreak.core.Game;
import com.stonebreak.mobs.entities.Entity;
import com.stonebreak.mobs.entities.EntityManager;
import com.stonebreak.mobs.entities.LivingEntity;
import com.stonebreak.mobs.entities.ai.AwarenessController;
import com.stonebreak.mobs.entities.ai.perception.Loudness;
import com.stonebreak.mobs.entities.ai.perception.PerceptionProfile;
import com.stonebreak.mobs.entities.ai.perception.PerceptionSnapshot;
import com.openmason.engine.ui.masonry.MStatPanel;
import com.stonebreak.player.Player;
import com.stonebreak.rendering.Renderer;
import com.stonebreak.rendering.UI.rendering.DebugRenderer;
import org.joml.Vector3f;
import org.joml.Vector4f;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * The F3 stealth-perception view (#321), which tuning a mob's {@link PerceptionProfile} depends on:
 * each aware mob's vision cone (out to its darkness-adjusted range) and hearing rings drawn in
 * the world — how far a sprint, a walk and a stealthed step carry to its ears — coloured by
 * awareness state, and a "Perception" section on the debug card listing the nearest mobs' state,
 * meter, live rate, alertness multiplier and what fed the rate.
 *
 * <p>Perception runs on the authoritative server mob, so the numbers come from that manager in
 * single-player and hosted games; a remote client has none and shows only the replicated state.
 */
public final class PerceptionDebug {

    /** Mobs further than this from the player are left off the card. */
    private static final float CARD_RADIUS = 32f;
    private static final int CARD_ROWS = 5;
    private static final int ARC_SEGMENTS = 16;
    private static final int CIRCLE_SEGMENTS = 32;

    private final List<Vector3f> scratch = new ArrayList<>();

    /** Draws every aware mob's cone and hearing circle as batched debug lines. */
    public void render(Renderer renderer) {
        List<LivingEntity> mobs = awareMobs();
        if (mobs.isEmpty()) {
            return;
        }
        DebugRenderer debug = renderer.getDebugRenderer();
        debug.beginBatch();
        try {
            for (LivingEntity mob : mobs) {
                AwarenessController awareness = mob.getAwareness();
                Vector4f color = colorFor(awareness.getState());
                drawCone(debug, mob, awareness, color);
                drawHearing(debug, mob, awareness.profile(), color);
            }
        } finally {
            debug.endBatch();
        }
    }

    /** Adds the "Perception" section for the nearest aware mobs; nothing when none are near. */
    public static void appendSection(MStatPanel panel) {
        Player player = Game.getPlayer();
        if (player == null) {
            return;
        }
        Vector3f at = player.getPosition();
        List<LivingEntity> near = new ArrayList<>();
        for (LivingEntity mob : awareMobs()) {
            if (mob.getPosition().distance(at) <= CARD_RADIUS) {
                near.add(mob);
            }
        }
        if (near.isEmpty()) {
            return;
        }
        near.sort(Comparator.comparingDouble(m -> m.getPosition().distanceSquared(at)));
        panel.section("Perception");
        for (LivingEntity mob : near.subList(0, Math.min(CARD_ROWS, near.size()))) {
            AwarenessController awareness = mob.getAwareness();
            PerceptionSnapshot s = awareness.snapshot();
            String memory = s.memory() > 0f ? String.format(" mem %.1fs", s.memory()) : "";
            panel.row(mob.getType().name() + " #" + mob.getNetworkId(), String.format(
                    "%s %.0f/%.0f %+.0f/s x%.1f (vis %.0f hear %.0f) light %.2f%s",
                    awareness.getState(), s.meter(), awareness.profile().alarmThreshold(), s.rate(),
                    awareness.profile().alertness().of(awareness.getState()),
                    s.vision(), s.hearing(), s.light(), memory));
        }
    }

    /**
     * The living mobs that track awareness, from the authoritative manager when this JVM has one
     * (where perception actually runs), else the rendered one.
     */
    private static List<LivingEntity> awareMobs() {
        EntityManager source = MobPathWireframeDrawer.authoritativeEntitySource();
        if (source == null) {
            source = Game.getEntityManager();
        }
        List<LivingEntity> mobs = new ArrayList<>();
        if (source == null) {
            return mobs;
        }
        for (Entity entity : source.getAllEntities()) {
            if (entity.isAlive() && entity instanceof LivingEntity mob && mob.getAwareness() != null) {
                mobs.add(mob);
            }
        }
        return mobs;
    }

    /** The forward cone at full strength, its side bands, and the arc at the vision range. */
    private void drawCone(DebugRenderer debug, LivingEntity mob, AwarenessController awareness, Vector4f color) {
        PerceptionProfile profile = awareness.profile();
        float range = awareness.snapshot().effRange() > 0f ? awareness.snapshot().effRange() : profile.visionRange();
        Vector3f eye = new Vector3f(mob.getPosition()).add(0f, mob.getType().getHeight() * 0.9f, 0f);
        Vector3f forward = mob.getForwardDirection();
        float yaw = (float) Math.atan2(forward.z, forward.x);
        float half = (float) Math.toRadians(profile.fovDeg() * 0.5f);

        scratch.clear();
        scratch.add(new Vector3f(eye));
        for (int i = 0; i <= ARC_SEGMENTS; i++) {
            scratch.add(pointAt(eye, yaw - half + 2f * half * i / ARC_SEGMENTS, range));
        }
        scratch.add(new Vector3f(eye));
        debug.drawPath(scratch, color);

        if (profile.sideVisionFactor() > 0f) {
            float side = (float) Math.toRadians(PerceptionProfile.SIDE_LIMIT_DEG);
            Vector4f faint = new Vector4f(color.x, color.y, color.z, 0.35f);
            for (float sign : new float[] {-1f, 1f}) {
                scratch.clear();
                scratch.add(new Vector3f(eye));
                scratch.add(pointAt(eye, yaw + sign * side, range * profile.sideVisionFactor()));
                debug.drawPath(scratch, faint);
            }
        }
    }

    /** One ring per movement loudness, fainter as the step gets quieter (unstealthed walk, then a base-stealth step). */
    private void drawHearing(DebugRenderer debug, LivingEntity mob, PerceptionProfile profile, Vector4f color) {
        float[] loudness = {Loudness.SPRINT, Loudness.WALK, Loudness.STEALTH};
        float[] alpha = {0.45f, 0.3f, 0.2f};
        for (int i = 0; i < loudness.length; i++) {
            drawCircle(debug, mob.getPosition(), Loudness.audibleRadius(loudness[i], profile.hearingRange()),
                    new Vector4f(color.x, color.y, color.z, alpha[i]));
        }
    }

    private void drawCircle(DebugRenderer debug, Vector3f center, float radius, Vector4f color) {
        scratch.clear();
        Vector3f c = new Vector3f(center).add(0f, 0.1f, 0f);
        for (int i = 0; i <= CIRCLE_SEGMENTS; i++) {
            scratch.add(pointAt(c, (float) (2.0 * Math.PI * i / CIRCLE_SEGMENTS), radius));
        }
        debug.drawPath(scratch, color);
    }

    private static Vector3f pointAt(Vector3f origin, float angle, float distance) {
        return new Vector3f(origin.x + (float) Math.cos(angle) * distance, origin.y,
                origin.z + (float) Math.sin(angle) * distance);
    }

    private static Vector4f colorFor(AwarenessController.AwarenessState state) {
        return switch (state) {
            case UNAWARE    -> new Vector4f(0.30f, 1.0f, 0.35f, 1.0f); // green
            case SUSPICIOUS -> new Vector4f(0.95f, 0.82f, 0.29f, 1.0f); // amber, as the "?"
            case ALERTED    -> new Vector4f(0.88f, 0.28f, 0.24f, 1.0f); // red, as the "!"
            case SEARCHING  -> new Vector4f(0.70f, 0.70f, 0.70f, 1.0f); // grey
        };
    }
}
