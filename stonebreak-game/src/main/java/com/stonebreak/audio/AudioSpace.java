package com.stonebreak.audio;

import com.openmason.engine.audio.SoundSystem;
import org.joml.Vector3dc;
import org.joml.Vector3f;

/**
 * Where the native mixer ({@code ca_*}, float positions) sits in the 64-bit world: an AUDIO ORIGIN near the
 * listener, snapped to a {@value #GRID}-block grid. Every position handed to the mixer - the listener and every
 * positional sound - is {@code world - origin}, subtracted in double and only then cast to float, so a sound
 * a million blocks out is as precise as one at spawn (a float holds ~1/1000 block within the grid cell).
 *
 * <p>The origin moves only when the listener leaves its cell, so a long-lived positional voice set once keeps
 * its place while the listener walks around (a fully listener-relative position would drift). When it does
 * move, voices already playing keep their old relative position for the rest of their life - a one-off glitch
 * on short sounds every {@value #GRID} blocks of travel.
 *
 * <p>Thread-safe: the origin is one immutable record behind a volatile field, read whole.
 */
public final class AudioSpace {

    /** Origin grid, blocks: the listener is never more than ~1.5 cells from the origin. */
    public static final double GRID = 1024.0;

    private record Origin(double x, double y, double z) {
    }

    private static volatile Origin origin = new Origin(0, 0, 0);

    private AudioSpace() {
    }

    /**
     * Places the listener (the camera, world space) and its orientation, moving the audio origin first when
     * the listener has left the origin's cell. Call once per frame from the thread that owns the camera.
     */
    public static void setListener(SoundSystem sounds, Vector3dc eye, Vector3f front, Vector3f up) {
        Origin o = follow(eye.x(), eye.y(), eye.z());
        if (sounds != null) {
            sounds.setListenerFromCamera(new Vector3f((float) (eye.x() - o.x), (float) (eye.y() - o.y),
                (float) (eye.z() - o.z)), front, up);
        }
    }

    /** Moves the origin to the grid cell of (x, y, z) when that is a different cell; returns the current origin. */
    static Origin follow(double x, double y, double z) {
        Origin o = origin;
        double ox = snap(x), oy = snap(y), oz = snap(z);
        if (ox != o.x || oy != o.y || oz != o.z) {
            o = new Origin(ox, oy, oz);
            origin = o;
        }
        return o;
    }

    private static double snap(double v) {
        return Math.floor(v / GRID) * GRID;
    }

    /** Mixer X of world x. */
    public static float x(double worldX) {
        return (float) (worldX - origin.x);
    }

    /** Mixer Y of world y. */
    public static float y(double worldY) {
        return (float) (worldY - origin.y);
    }

    /** Mixer Z of world z. */
    public static float z(double worldZ) {
        return (float) (worldZ - origin.z);
    }

    /** The mixer position of a world position, into {@code dest}. */
    public static Vector3f toMixer(double worldX, double worldY, double worldZ, Vector3f dest) {
        Origin o = origin;
        return dest.set((float) (worldX - o.x), (float) (worldY - o.y), (float) (worldZ - o.z));
    }

    /** The current origin, world space ({x, y, z}) - debug/tests. */
    public static double[] origin() {
        Origin o = origin;
        return new double[]{o.x, o.y, o.z};
    }

    /** Back to the world origin (tests, world change). */
    public static void reset() {
        origin = new Origin(0, 0, 0);
    }
}
