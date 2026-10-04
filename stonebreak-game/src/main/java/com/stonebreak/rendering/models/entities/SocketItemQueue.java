package com.stonebreak.rendering.models.entities;

import com.stonebreak.items.ItemType;
import org.joml.Matrix4f;

import java.util.ArrayList;
import java.util.List;

/**
 * Held items posed on entity sockets this frame, waiting to be drawn.
 *
 * <p>The socket pose is only known while the host mob is posed (the SBE pass), but voxelized
 * items draw through the world shader with the drop pipeline. So the mob pass queues each item at
 * its socket matrix here and the drop pass drains it. Matrices are pooled across frames.
 */
public final class SocketItemQueue {

    /** One queued item: what to draw and the socket's world (render-origin) matrix. */
    public record Entry(ItemType type, String state, Matrix4f socketMatrix) {}

    private final List<Entry> entries = new ArrayList<>();
    private final List<Matrix4f> pool = new ArrayList<>();

    void add(ItemType type, String state, Matrix4f socketMatrix) {
        int index = entries.size();
        if (index == pool.size()) {
            pool.add(new Matrix4f());
        }
        entries.add(new Entry(type, state, pool.get(index).set(socketMatrix)));
    }

    /** This frame's items; valid until {@link #clear()}. */
    public List<Entry> entries() {
        return entries;
    }

    public void clear() {
        entries.clear();
    }
}
