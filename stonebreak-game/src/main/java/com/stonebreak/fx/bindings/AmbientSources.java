package com.stonebreak.fx.bindings;

import com.stonebreak.blocks.BlockType;
import org.joml.Vector3f;

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
    Vector3f position(int i, Vector3f out);

    /** No sources. */
    AmbientSources NONE = new AmbientSources() {
        @Override public int count() { return 0; }
        @Override public BlockType block(int i) { return null; }
        @Override public long key(int i) { return 0; }
        @Override public Vector3f position(int i, Vector3f out) { return out; }
    };

    /** A placed block's key: x and z in 26 bits each, y in 11 bits, top bit clear. */
    static long blockKey(int x, int y, int z) {
        return ((long) (x & 0x3FFFFFF) << 37) | ((long) (z & 0x3FFFFFF) << 11) | (y & 0x7FF);
    }

    /** A holder's key (an entity carrying the source): top bit set, so it never meets a block key. */
    static long holderKey(int id) {
        return Long.MIN_VALUE | (id & 0xFFFFFFFFL);
    }
}
