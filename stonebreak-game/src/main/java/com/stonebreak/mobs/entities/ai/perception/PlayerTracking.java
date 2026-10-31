package com.stonebreak.mobs.entities.ai.perception;

import org.joml.Vector3f;

/**
 * How hard one player is hunting a stalker with their eyes, and where they last saw it. A glimpse
 * raises it a little, a stare a lot; it fades once the stalker is out of sight, slowly while the
 * player keeps looking where it was (they are still searching) and faster once they give up.
 *
 * <p>Kept per stalker, like {@link LookTracker}, and fed one sample per AI tick by its owner. Pure
 * state, so it can be driven from a test.
 */
public final class PlayerTracking {

    /** Level gained per second while the player stares right at the stalker. */
    static final float WATCHED_RISE = 0.8f;
    /** Level gained per second while it is merely somewhere in their view. */
    static final float GLIMPSED_RISE = 0.25f;
    /** Level lost per second out of sight while they still look where they last saw it. */
    static final float SEARCHING_DECAY = 0.04f;
    /** Level lost per second out of sight once they look elsewhere. */
    static final float DECAY = 0.12f;
    /** At or above this level the player counts as tracking it. */
    static final float TRACKING_LEVEL = 0.4f;

    private float level;
    private final Vector3f lastSeen = new Vector3f();
    private boolean hasLastSeen;

    /**
     * Takes this tick's sample.
     *
     * @param inView   whether the player could see the stalker
     * @param watched  whether they are looking straight at it
     * @param stalker  where the stalker is, remembered as the last sighting while it is seen
     * @param eye      the player's eye
     * @param look     unit direction the player looks, or null when unknown
     */
    public void update(boolean inView, boolean watched, Vector3f stalker, Vector3f eye, Vector3f look, float dt) {
        if (!(dt > 0f)) {
            return;
        }
        if (inView || watched) {
            lastSeen.set(stalker);
            hasLastSeen = true;
            level = Math.min(1f, level + (watched ? WATCHED_RISE : GLIMPSED_RISE) * dt);
            return;
        }
        boolean searching = hasLastSeen && look != null
                && PlayerGaze.inCone(eye, look, lastSeen, PlayerGaze.VIEW_HALF_ANGLE_DEG, Float.MAX_VALUE);
        level = Math.max(0f, level - (searching ? SEARCHING_DECAY : DECAY) * dt);
    }

    /** How hard they are tracking it, 0 (not at all) to 1. */
    public float level() {
        return level;
    }

    /** Whether they are tracking it hard enough that it should keep its distance. */
    public boolean isTracking() {
        return level >= TRACKING_LEVEL;
    }

    /** Where they last saw it, or null if they never have. Do not retain. */
    public Vector3f lastSeen() {
        return hasLastSeen ? lastSeen : null;
    }

    /** Forgets everything, as when the stalker picks a new victim. */
    public void reset() {
        level = 0f;
        hasLastSeen = false;
    }
}
