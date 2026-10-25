package com.stonebreak.vehicles;

/**
 * How hard each oar is pulling, -1..1 (negative backs water). One value drives both the boat's
 * motion ({@link DinghyPhysics}) and the oar animation ({@link OarStroke}), so what the rower sees
 * is what moves the boat.
 *
 * <p>Steering is physically correct: the boat turns AWAY from the harder-pulling oar, so a right
 * turn works the left oar. Keys are on/off, so the key combo picks how hard to turn:
 * <pre>
 *   W        L 1    R 1        S        L -1    R -1
 *   W+D      L 1    R 0.5      S+D      L -0.5  R -1
 *   D        L 1    R 0        (A mirrors D)
 * </pre>
 * Backing water turns the bow the same way as pulling, so D always swings the bow right.
 */
public record OarInput(float left, float right) {

    public static final OarInput IDLE = new OarInput(0f, 0f);

    /** The inner oar's share of a gentle (drive + turn) stroke. */
    static final float GENTLE_INNER = 0.5f;

    public OarInput {
        left = clamp(left);
        right = clamp(right);
    }

    public static OarInput fromKeys(boolean forward, boolean backward, boolean turnLeft, boolean turnRight) {
        int drive = (forward ? 1 : 0) - (backward ? 1 : 0);
        int turn = (turnRight ? 1 : 0) - (turnLeft ? 1 : 0);
        if (drive == 0 && turn == 0) {
            return IDLE;
        }
        if (drive == 0) {
            // Hard turn: only the outer oar pulls.
            return turn > 0 ? new OarInput(1f, 0f) : new OarInput(0f, 1f);
        }
        float full = drive;
        float inner = drive * GENTLE_INNER;
        if (turn == 0) {
            return new OarInput(full, full);
        }
        // Pulling: the right turn works the left oar. Backing: the right turn backs the right oar.
        boolean leftWorks = (turn > 0) == (drive > 0);
        return leftWorks ? new OarInput(full, inner) : new OarInput(inner, full);
    }

    public boolean isIdle() {
        return left == 0f && right == 0f;
    }

    /** Forward drive, -1..1: the mean of both strokes. */
    public float thrust() {
        return (left + right) * 0.5f;
    }

    /**
     * Turn toward the bow's right, -1..1: the left side driven forward harder than the right.
     * Holds for backing too (backing the right oar harder drives the right side astern).
     */
    public float turnRight() {
        return left - right;
    }

    /** Wire form: each oar in half steps, packed into one byte per oar. */
    public byte encodeLeft() {
        return (byte) Math.round(left * 2f);
    }

    public byte encodeRight() {
        return (byte) Math.round(right * 2f);
    }

    public static OarInput decode(byte left, byte right) {
        return new OarInput(left / 2f, right / 2f);
    }

    private static float clamp(float v) {
        return Float.isNaN(v) ? 0f : Math.max(-1f, Math.min(1f, v));
    }
}
