package com.stonebreak.network.sync;

/**
 * Fixed-point conversion helpers for {@code EntityMoveS2C} / position deltas.
 * Position is encoded as {@code short = round(blocks * 4096)} → ±8 blocks per
 * packet at ~0.00024-block resolution. Yaw is encoded as {@code short = round(degrees * 10)}
 * → ±3276° at 0.1° resolution.
 */
public final class EntityDeltaCodec {

    public static final float POS_SCALE = 4096f;
    public static final float YAW_SCALE = 10f;
    public static final float MAX_DELTA_BLOCKS = 7.99f; // safety margin under Short.MAX_VALUE / POS_SCALE

    private EntityDeltaCodec() {}

    public static short encodePosDelta(float blocks) {
        int v = Math.round(blocks * POS_SCALE);
        if (v > Short.MAX_VALUE) v = Short.MAX_VALUE;
        else if (v < Short.MIN_VALUE) v = Short.MIN_VALUE;
        return (short) v;
    }

    public static float decodePosDelta(short raw) {
        return raw / POS_SCALE;
    }

    public static short encodeYawDeg(float degrees) {
        // Wrap to a representable range first (yaw can drift unbounded).
        float wrapped = degrees % 3600f;
        int v = Math.round(wrapped * YAW_SCALE);
        if (v > Short.MAX_VALUE) v = Short.MAX_VALUE;
        else if (v < Short.MIN_VALUE) v = Short.MIN_VALUE;
        return (short) v;
    }

    public static float decodeYawDeg(short raw) {
        return raw / YAW_SCALE;
    }

    public static boolean fitsInDelta(float dx, float dy, float dz) {
        return Math.abs(dx) <= MAX_DELTA_BLOCKS
            && Math.abs(dy) <= MAX_DELTA_BLOCKS
            && Math.abs(dz) <= MAX_DELTA_BLOCKS;
    }
}
