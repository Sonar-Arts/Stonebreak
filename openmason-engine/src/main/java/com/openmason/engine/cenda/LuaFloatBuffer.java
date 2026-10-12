package com.openmason.engine.cenda;

import java.lang.foreign.MemorySegment;
import java.lang.foreign.ValueLayout;

/**
 * A native float buffer shared with Lua (see {@link LuaState#bindBuffer}).
 * Lua appends with {@code name.emit(...)}; the host reads the floats in place,
 * so bulk per-frame data (draw lists, particle spawns) costs no JVM crossing
 * per element.
 */
public final class LuaFloatBuffer {

    private final LuaState state;
    private final int id;
    private final MemorySegment data;

    LuaFloatBuffer(LuaState state, int id, MemorySegment data) {
        this.state = state;
        this.id = id;
        this.data = data;
    }

    /** Floats appended by {@code emit} since the last {@link #reset()}. */
    public int cursor() {
        return state.bufferCursor(id);
    }

    public void reset() {
        state.bufferReset(id);
    }

    public float get(int i) {
        return data.getAtIndex(ValueLayout.JAVA_FLOAT, i);
    }

    public int capacity() {
        return (int) (data.byteSize() / Float.BYTES);
    }

    /** The backing native memory, valid until the state closes. */
    public MemorySegment segment() {
        return data;
    }
}
