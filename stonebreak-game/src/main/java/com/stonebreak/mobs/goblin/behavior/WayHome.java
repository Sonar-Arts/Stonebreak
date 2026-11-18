package com.stonebreak.mobs.goblin.behavior;

import com.stonebreak.mobs.entities.ai.behavior.AiContext;
import com.stonebreak.mobs.goblin.Goblin;
import com.stonebreak.world.World;
import org.joml.Vector3f;

/**
 * Watches a camp goblin's walk home. One that has dropped down ledges it cannot climb back (a hunt
 * that ran downhill, say) never gets there — its route ends at the nearest point it can reach, so
 * the route's status says nothing; only its distance does. After {@link #STRANDED_SECONDS} without
 * getting a block closer, and with no player in sight of it to see it go, it is brought home — back on
 * its camp's roster, to come out at the fire, just as a goblin unloaded with a far chunk is.
 */
final class WayHome {

    /** Seconds without a way home before a goblin counts as stranded. */
    static final float STRANDED_SECONDS = 20.0f;
    /**
     * A player within this distance who has a clear line to the goblin would see it vanish: it
     * keeps trying instead. Rock between them hides it, however close.
     */
    static final float SIGHT_RADIUS = 64.0f;

    private float stranded;
    private float best = Float.MAX_VALUE;

    void reset() {
        stranded = 0.0f;
        best = Float.MAX_VALUE;
    }

    /**
     * Counts {@code dt} toward stranded while the goblin gets no closer to {@code home}.
     *
     * @return whether the goblin was brought home (it has left the world)
     */
    boolean check(Goblin goblin, AiContext context, Vector3f home, float dt) {
        float distance = context.distanceTo(home);
        if (distance < best - 1.0f) {
            best = distance; // on its way
            stranded = 0.0f;
        } else {
            stranded += dt;
        }
        World world = context.world();
        if (stranded < STRANDED_SECONDS || world == null || world.goblinCamps() == null) {
            return false;
        }
        Vector3f at = goblin.getPosition();
        var players = world.getPlayerLocator();
        Vector3f player = players.nearestPlayer(at, new Vector3f());
        Vector3f eye = new Vector3f(at.x, at.y + goblin.getHeight() * 0.9f, at.z);
        if (player != null && player.distance(at) < SIGHT_RADIUS && players.canSeeNearestPlayer(at, eye)) {
            return false;
        }
        reset();
        return world.goblinCamps().bringHome(goblin);
    }
}
