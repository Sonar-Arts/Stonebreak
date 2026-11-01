package com.openmason.engine.rendering.shadow;

import org.joml.Vector3f;
import org.joml.Vector3fc;
import java.util.Arrays;

/** Keeps each depth cube paired with its exact light position until its position or terrain changes. */
public final class PointShadowCache {
    private final Vector3f[] positions;
    private final boolean[] valid;

    public PointShadowCache(int capacity) {
        if (capacity <= 0) throw new IllegalArgumentException("Invalid cache size");
        positions = new Vector3f[capacity];
        valid = new boolean[capacity];
        for (int i = 0; i < capacity; i++) positions[i] = new Vector3f();
    }

    public boolean needsRefresh(int slot, Vector3fc position) {
        return !valid[slot] || !positions[slot].equals(position);
    }

    public void rendered(int slot, Vector3fc position) {
        positions[slot].set(position);
        valid[slot] = true;
    }

    public void retain(int count) { Arrays.fill(valid, count, valid.length, false); }

    public void invalidate() { Arrays.fill(valid, false); }
}
