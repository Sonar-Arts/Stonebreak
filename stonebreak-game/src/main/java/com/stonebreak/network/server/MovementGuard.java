package com.stonebreak.network.server;

import com.openmason.engine.net.pipeline.TokenBucket;

/**
 * How far a remote player may move between position reports (issue #336). A client that
 * reported a new far-away position every tick made the server generate (and later save) a
 * whole view of chunks around each one, and defeated every reach check.
 *
 * <p>Horizontal movement spends a distance budget that refills at a speed no legitimate
 * player reaches (sprinting, swimming, falling with momentum, abilities such as Shadow Step,
 * dashes and knockback all fit in the burst). A report further than the budget allows is
 * refused: the server keeps its position and tells the client to go back
 * ({@code PlayerPositionS2C}). Vertical movement is bounded only by the world's Y range — it
 * does not reach new chunks, and a long fall is legitimately fast.
 *
 * <p>Exempt: the host's own player, and every player while the world's cheats are on
 * ({@code /teleport} and creative flight are then allowed). A respawn is a server-checked
 * teleport to the world spawn and refills the budget.
 *
 * <p>Server tick thread only, like the rest of {@link ServerPlayer}.
 */
public final class MovementGuard {

    /** Sustained horizontal speed allowed, blocks per second. */
    public static final double SPEED_BLOCKS_PER_SECOND = 40.0;
    /** Horizontal distance allowed in one burst (a lag spike's bunched reports, a 12-block Shadow Step). */
    public static final double BURST_BLOCKS = 32.0;
    /** The first report may differ this much (horizontally) from the stored position it joined at. */
    public static final float FIRST_REPORT_SLACK = 64f;
    /** A respawn must land this close (horizontally) to the world spawn. */
    public static final float RESPAWN_SLACK = 16f;
    /** At most one correction per this long, so reports already in flight don't each trigger one. */
    static final long CORRECTION_INTERVAL_NANOS = 500_000_000L;

    private final TokenBucket budget = new TokenBucket(BURST_BLOCKS, SPEED_BLOCKS_PER_SECOND);
    private long lastCorrectionNanos;
    private boolean corrected;
    private int refusals;

    /**
     * Whether a move from {@code (fromX, fromZ)} to {@code (toX, toZ)} reported at
     * {@code nowNanos} is within the budget; an accepted move spends its distance.
     */
    public boolean allowMove(float fromX, float fromZ, float toX, float toZ, long nowNanos) {
        double distance = horizontalDistance(fromX, fromZ, toX, toZ);
        if (budget.tryTake(distance, nowNanos)) {
            return true;
        }
        refusals++;
        return false;
    }

    /** Whether a refused move should be answered with a correction now (rate-limited). */
    public boolean shouldCorrect(long nowNanos) {
        if (corrected && nowNanos - lastCorrectionNanos < CORRECTION_INTERVAL_NANOS) {
            return false;
        }
        corrected = true;
        lastCorrectionNanos = nowNanos;
        return true;
    }

    /** A server-accepted teleport (respawn): the next reports start from there with a full budget. */
    public void reset(long nowNanos) {
        budget.fill(nowNanos);
    }

    /** Moves refused so far (diagnostics, tests). */
    public int refusals() {
        return refusals;
    }

    public static double horizontalDistance(float fromX, float fromZ, float toX, float toZ) {
        double dx = (double) toX - fromX;
        double dz = (double) toZ - fromZ;
        return Math.sqrt(dx * dx + dz * dz);
    }
}
