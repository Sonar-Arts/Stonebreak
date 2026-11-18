package com.stonebreak.fx.bindings;

import com.stonebreak.blocks.BlockType;
import org.joml.Vector3d;

/**
 * The world places that may run their block's {@code ambient} FX this frame (Stonebreak#437). The game feeds it
 * from the light selection {@code DynamicLights} already makes — the nearest emitting torches, lit furnaces and
 * torches other players hold — so ambient FX share that budget and cost nothing extra to find.
 */
public interface AmbientSources {

    /** Sources this frame. */
    int count();

    /** The block whose {@code ambient} bindings source {@code i} plays, or null when it plays none. */
    BlockType block(int i);

    /** Stable identity of source {@code i} across frames (a packed block position, or a holder). */
    long key(int i);

    /** World position the source emits from (a torch's ember, a furnace's mouth), into {@code out}. */
    Vector3d position(int i, Vector3d out);

    /**
     * Source {@code i}'s animation pulse this frame, {@code [0, 1]} (a torch's ember swell), or NaN when it has
     * none. Fed to the FX's {@code pulse} parameter ({@link com.stonebreak.fx.FxRuntime#PARAM_PULSE}).
     */
    default float pulse(int i) {
        return Float.NaN;
    }

    /** No sources. */
    AmbientSources NONE = new AmbientSources() {
        @Override public int count() { return 0; }
        @Override public BlockType block(int i) { return null; }
        @Override public long key(int i) { return 0; }
        @Override public Vector3d position(int i, Vector3d out) { return out; }
    };

    /**
     * A placed block's key: the full int64 cell hashed (chained splitmix64, no packed coordinates - any
     * position is valid), top bit clear so it never meets a holder key. Identity only: two cells sharing a
     * hash would merely swap ambient FX instances.
     */
    static long blockKey(long x, long y, long z) {
        return mix(mix(mix(x) + y) + z) & Long.MAX_VALUE;
    }

    private static long mix(long v) {
        v = (v ^ (v >>> 30)) * 0xBF58476D1CE4E5B9L;
        v = (v ^ (v >>> 27)) * 0x94D049BB133111EBL;
        return v ^ (v >>> 31);
    }

    /** A holder's key (an entity carrying the source): top bit set, so it never meets a block key. */
    static long holderKey(int id) {
        return Long.MIN_VALUE | (id & 0xFFFFFFFFL);
    }
}
