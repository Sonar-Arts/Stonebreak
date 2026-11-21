package com.stonebreak.util;

import org.joml.Vector3dc;
import org.joml.Vector3f;

/**
 * Small helpers for double world positions (#445: worlds span the full int64 block range, so
 * world positions are {@code double} and block cells are {@code long}).
 */
public final class Positions {

    private Positions() {
    }

    /** The block cell holding world coordinate {@code v} (floor, as a long). */
    public static long cell(double v) {
        return (long) Math.floor(v);
    }

    /** Squared horizontal (XZ) distance between two world positions. */
    public static double horizontalDistanceSquared(Vector3dc a, Vector3dc b) {
        double dx = a.x() - b.x();
        double dz = a.z() - b.z();
        return dx * dx + dz * dz;
    }

    /**
     * {@code to - from} as a float vector: a direction or offset between two nearby world positions
     * (exact enough for anything within a few thousand blocks of each other, wherever they are).
     */
    public static Vector3f offset(Vector3dc from, Vector3dc to, Vector3f dest) {
        return dest.set((float) (to.x() - from.x()), (float) (to.y() - from.y()), (float) (to.z() - from.z()));
    }

    /** {@link #offset(Vector3dc, Vector3dc, Vector3f)} into a new vector. */
    public static Vector3f offset(Vector3dc from, Vector3dc to) {
        return offset(from, to, new Vector3f());
    }
}
