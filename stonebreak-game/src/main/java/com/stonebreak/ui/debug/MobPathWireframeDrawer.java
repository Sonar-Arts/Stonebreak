package com.stonebreak.ui.debug;

import com.stonebreak.core.Game;
import com.stonebreak.mobs.entities.Entity;
import com.stonebreak.mobs.entities.EntityManager;
import com.stonebreak.mobs.entities.LivingEntity;
import com.stonebreak.mobs.entities.ai.MobBehaviorState;
import com.stonebreak.mobs.entities.ai.nav.AirPathAgent;
import com.stonebreak.mobs.entities.ai.nav.Path;
import com.stonebreak.mobs.entities.ai.nav.PathAgent;
import com.stonebreak.mobs.goose.Goose;
import com.stonebreak.network.MultiplayerSession;
import com.stonebreak.network.server.IntegratedServer;
import com.stonebreak.rendering.Renderer;
import com.stonebreak.rendering.UI.rendering.DebugRenderer;
import java.util.List;
import org.joml.Vector3f;
import org.joml.Vector4f;

/**
 * Draws the F3 entity debug view: every AI-driven mob's model as a
 * behaviour-coloured wireframe, plus the ground/air route it has planned
 * (remaining waypoints and a goal marker) as batched debug lines. Routes are
 * read from both the rendered and the authoritative entity managers so
 * server-simulated mobs are covered in single-player and hosted games.
 */
public final class MobPathWireframeDrawer {

    /** The route a mob still has to walk. */
    private static final Vector4f PATH_COLOR = new Vector4f(0.2f, 0.6f, 1.0f, 1.0f);
    /** Where it is trying to get to. */
    private static final Vector4f GOAL_COLOR = new Vector4f(1.0f, 0.25f, 0.85f, 1.0f);

    /** Half-size of the cross drawn at a mob's destination. */
    private static final float GOAL_MARKER_SIZE = 0.4f;

    /**
     * How far a ground route is lifted off the surface for drawing.
     *
     * <p>A waypoint sits at exactly the height the mob's feet will rest — the top face of the block
     * it stands on. Drawn there, with the depth test on, the line is coplanar with that face and
     * z-fights it into invisibility, which is why ground routes never appeared while the geese's
     * mid-air ones did. Small enough that the line still reads as being on the ground.
     */
    private static final float ROUTE_GROUND_LIFT = 0.25f;

    /** Reused between frames so the overlay does not allocate a list per mob per frame. */
    private final List<Vector3f> pathScratch = new java.util.ArrayList<>();

    /**
     * Renders debug wireframes for entities (called after UI rendering).
     *
     * <p>Each mob is outlined by re-drawing its actual model mesh as a see-through wireframe, so
     * the overlay tracks the animated model exactly, coloured by what it is currently doing.
     *
     * <p>The lines show the route each mob has <em>planned</em> — the waypoints still ahead of it,
     * and a marker at its destination. That is the useful view: it says where a mob has decided to
     * go and how it intends to get there, so a mob pressed against a wall is immediately either a
     * routing bug (no path, or a path through the wall) or a steering one (a sensible path it is
     * failing to walk).
     */
    public void render(Renderer renderer) {
        EntityManager rendered = Game.getEntityManager();
        if (rendered == null) {
            return;
        }

        // Wireframes go on the mobs actually on screen — the client's shadows.
        List<LivingEntity> renderedMobs = aiMobsOf(rendered);
        for (LivingEntity mob : renderedMobs) {
            renderer.renderEntityWireframe(mob, colorForState(mob));
        }

        // Planned routes — batched line drawing. Every AI-driven mob gets the same treatment, so
        // there is no per-mob code here and a future mob appears automatically.
        //
        // Both managers are drawn, rather than picking one. A mob's AI runs in exactly one of them
        // and contributes nothing from the other — a network shadow's route is permanently empty
        // because its AI is never ticked — so the union costs an empty pass and cannot silently
        // drop a source. Picking one would: replicated mobs navigate server-side, while
        // owner-local entities (the types that do not replicate) navigate here.
        DebugRenderer debug = renderer.getDebugRenderer();
        debug.beginBatch();
        try {
            drawRoutesOf(debug, renderedMobs);
            EntityManager authoritative = authoritativeEntitySource();
            if (authoritative != null && authoritative != rendered) {
                drawRoutesOf(debug, aiMobsOf(authoritative));
            }
        } finally {
            debug.endBatch();
        }

        // Sound emitters manage their own shader state — draw outside the batch.
        renderer.renderSoundEmitters(true);
    }

    /** The AI-driven living mobs of one manager. */
    private static List<LivingEntity> aiMobsOf(EntityManager manager) {
        List<LivingEntity> mobs = new java.util.ArrayList<>();
        for (Entity entity : manager.getAllEntities()) {
            if (entity.isAlive() && entity instanceof LivingEntity mob && mob.getAI() != null) {
                mobs.add(mob);
            }
        }
        return mobs;
    }

    private void drawRoutesOf(DebugRenderer debug, List<LivingEntity> mobs) {
        for (LivingEntity mob : mobs) {
            drawPlannedRoute(debug, mob);
            // A flying goose routes through the air domain instead, which the ground agent knows
            // nothing about — draw that too, or an airborne flock looks unnavigated.
            if (mob instanceof Goose goose && goose.flight().isAirborne()) {
                drawAirRoute(debug, goose.flight().route(), goose.getPosition());
            }
        }
    }

    /**
     * The authoritative server's entity manager, or {@code null} when this JVM has none.
     *
     * <p>Replicated mobs are simulated on the authoritative server world; what a client renders are
     * interpolated network shadows. A shadow is a real {@code Cow} or {@code Goose} and so builds a
     * {@code MobAI} in its constructor — which is why every {@code getAI() != null} guard passes —
     * but {@code EntityManager.update} skips AI for shadows, so its route is permanently empty and
     * its goose never leaves the ground. The state-coloured wireframes do work on shadows, because
     * behaviour state arrives over the wire; only the routes are missing.
     *
     * <p>Single-player and hosting clients run that server in this same JVM, so the real mobs are
     * reachable. A remote client has no access to them and honestly draws no routes for replicated
     * mobs; that is the same gap the server-side footstep sounds have.
     *
     * <p>The entity list is a {@code CopyOnWriteArrayList} handed out as a copy, so iterating it
     * off the server tick is safe. The per-agent fields read from it are not synchronised — a
     * marker may lag a frame or land between two updates, which for a debug overlay is the right
     * trade against putting a lock in the navigation hot path.
     */
    private static EntityManager authoritativeEntitySource() {
        if (!MultiplayerSession.hasIntegratedServer()) {
            return null;
        }
        IntegratedServer server = MultiplayerSession.getServer();
        return server == null ? null : server.worldContext().entityManager();
    }

    /**
     * Draws a flying mob's air route the same way, so a leader steering round a peak shows the
     * corridor it chose and the wingmen following it can be read against that line.
     */
    private void drawAirRoute(DebugRenderer debug, AirPathAgent route, Vector3f mobPosition) {
        Path path = route.path();
        if (!path.isEmpty()) {
            pathScratch.clear();
            pathScratch.add(new Vector3f(mobPosition));
            for (int i = route.cursor(); i < path.size(); i++) {
                pathScratch.add(path.waypoint(i, new Vector3f()));
            }
            debug.drawPath(pathScratch, PATH_COLOR);
        }

        if (route.hasGoal()) {
            Vector3f goal = route.goal(new Vector3f());
            pathScratch.clear();
            pathScratch.add(new Vector3f(goal.x, goal.y - GOAL_MARKER_SIZE, goal.z));
            pathScratch.add(new Vector3f(goal.x, goal.y + GOAL_MARKER_SIZE, goal.z));
            debug.drawPath(pathScratch, GOAL_COLOR);
        }
    }

    private void drawPlannedRoute(DebugRenderer debug, LivingEntity mob) {
        PathAgent nav = mob.getAI().nav();
        Vector3f position = mob.getPosition();
        Path path = nav.path();

        if (!path.isEmpty()) {
            pathScratch.clear();
            // Start at the mob's feet — where its route is measured from — rather than its origin,
            // which sits a leg-length higher and made the first leg dive into the ground.
            pathScratch.add(new Vector3f(position.x,
                    position.y - mob.getLegHeight() + ROUTE_GROUND_LIFT, position.z));
            for (int i = nav.cursor(); i < path.size(); i++) {
                Vector3f waypoint = path.waypoint(i, new Vector3f());
                waypoint.y += ROUTE_GROUND_LIFT;
                pathScratch.add(waypoint);
            }
            debug.drawPath(pathScratch, PATH_COLOR);
        }

        if (nav.hasGoal()) {
            Vector3f goal = nav.goal(new Vector3f());
            float y = goal.y + ROUTE_GROUND_LIFT;
            pathScratch.clear();
            pathScratch.add(new Vector3f(goal.x - GOAL_MARKER_SIZE, y, goal.z));
            pathScratch.add(new Vector3f(goal.x + GOAL_MARKER_SIZE, y, goal.z));
            debug.drawPath(pathScratch, GOAL_COLOR);

            pathScratch.clear();
            pathScratch.add(new Vector3f(goal.x, y, goal.z - GOAL_MARKER_SIZE));
            pathScratch.add(new Vector3f(goal.x, y, goal.z + GOAL_MARKER_SIZE));
            debug.drawPath(pathScratch, GOAL_COLOR);
        }
    }

    /**
     * Picks the wireframe colour for what a mob is currently doing, so the overlay doubles as an
     * at-a-glance behaviour readout. One palette for every mob.
     */
    private Vector4f colorForState(LivingEntity mob) {
        MobBehaviorState state = mob.getAI() != null
                ? mob.getAI().getCurrentState() : MobBehaviorState.IDLE;
        return switch (state) {
            case IDLE                -> new Vector4f(0.25f, 0.85f, 1.0f, 1.0f); // cyan
            case WANDERING           -> new Vector4f(0.30f, 1.0f, 0.35f, 1.0f); // green
            case GRAZING, WING_FLAP  -> new Vector4f(1.0f, 0.80f, 0.20f, 1.0f); // amber
            case SWIMMING            -> new Vector4f(0.20f, 0.55f, 1.0f, 1.0f); // blue
            case FLYING              -> new Vector4f(1.0f, 0.45f, 0.15f, 1.0f); // orange
        };
    }
}
