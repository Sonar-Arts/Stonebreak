package com.stonebreak.mobs.entities.ai.perception;

import org.joml.Vector3f;

/**
 * Reads one player's body language over the last few seconds, for a mob that must not be caught:
 * how hard they are whipping the camera around, how long they have had their back to it, and
 * whether they are on the move. Fed one sample per AI tick by its owner.
 *
 * <p>Kept per stalker rather than per player: each one only cares about the player it hunts, and
 * "how long has their back been turned" is measured against that stalker's own position.
 */
public final class LookTracker {

    /** Seconds of history the averages cover. */
    static final float WINDOW_SECONDS = 3f;
    /** Further than this from dead ahead (degrees) and the player has their back to the watcher. */
    static final float BACK_TURNED_ANGLE_DEG = 110f;
    /** Average horizontal speed (blocks per second) above which a player counts as on the move. */
    static final float MOVING_SPEED = 1.5f;

    private float yawRate;          // smoothed |yaw change| in degrees per second
    private float speed;            // smoothed horizontal speed in blocks per second
    private float velX, velZ;       // smoothed horizontal velocity in blocks per second
    private float backTurned;       // seconds the back has stayed turned
    private boolean hasLast;
    private float lastYaw;
    private float lastX, lastZ;

    /**
     * Takes this tick's sample.
     *
     * @param look     unit direction the player looks
     * @param player   the player's feet
     * @param watcher  where the stalker is, for the back-turned clock
     */
    public void update(Vector3f look, Vector3f player, Vector3f watcher, float dt) {
        if (!(dt > 0f)) {
            return;
        }
        float yaw = (float) Math.toDegrees(Math.atan2(look.z, look.x));
        if (hasLast) {
            float delta = Math.abs(wrap(yaw - lastYaw));
            float moved = (float) Math.hypot(player.x - lastX, player.z - lastZ);
            // Exponential average with a time constant of the window: cheap, and no buffer to keep.
            float k = Math.min(1f, dt / WINDOW_SECONDS);
            yawRate += (delta / dt - yawRate) * k;
            speed += (moved / dt - speed) * k;
            velX += ((player.x - lastX) / dt - velX) * k;
            velZ += ((player.z - lastZ) / dt - velZ) * k;
        }
        hasLast = true;
        lastYaw = yaw;
        lastX = player.x;
        lastZ = player.z;

        Vector3f eye = new Vector3f(player.x, player.y, player.z);
        Vector3f target = new Vector3f(watcher.x, player.y, watcher.z);
        if (PlayerGaze.horizontalAngleTo(eye, look, target) >= BACK_TURNED_ANGLE_DEG) {
            backTurned += dt;
        } else {
            backTurned = 0f;
        }
    }

    /** How fast the player has been turning lately, in degrees per second. */
    public float franticness() {
        return yawRate;
    }

    /** Seconds the player has kept their back to the watcher; 0 while facing it. */
    public float backTurnedSeconds() {
        return backTurned;
    }

    /** Whether the player has been moving rather than standing and scanning. */
    public boolean isMoving() {
        return speed >= MOVING_SPEED;
    }

    /**
     * Where the player will be {@code seconds} from now if they keep going as they have been:
     * {@code player} carried along their recent average velocity. Standing still, that is where
     * they are.
     */
    public Vector3f predict(Vector3f player, float seconds, Vector3f out) {
        if (!isMoving()) {
            return out.set(player);
        }
        return out.set(player.x + velX * seconds, player.y, player.z + velZ * seconds);
    }

    /** Forgets everything, as when the stalker picks a new victim. */
    public void reset() {
        yawRate = 0f;
        speed = 0f;
        velX = 0f;
        velZ = 0f;
        backTurned = 0f;
        hasLast = false;
    }

    private static float wrap(float degrees) {
        float d = degrees % 360f;
        if (d > 180f) d -= 360f;
        if (d < -180f) d += 360f;
        return d;
    }
}
