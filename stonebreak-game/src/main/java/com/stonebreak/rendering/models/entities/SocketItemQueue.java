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

    /**
     * One queued item: what to draw and the socket's world (render-origin) matrix. Food cooking on a
     * campfire also names what it {@code cooksInto} and how far along it is ({@code cooked}, 0..1),
     * and draws part-way between the two; {@code cooksInto} is null for everything else.
     */
    public record Entry(ItemType type, String state, Matrix4f socketMatrix, ItemType cooksInto, float cooked) {}

    private final List<Entry> entries = new ArrayList<>();
    private final List<Matrix4f> pool = new ArrayList<>();

    void add(ItemType type, String state, Matrix4f socketMatrix) {
        add(type, state, socketMatrix, null, 0.0f);
    }

    /** Queues {@code type} drawn {@code cooked} (0..1) of the way to {@code cooksInto}. */
    void add(ItemType type, String state, Matrix4f socketMatrix, ItemType cooksInto, float cooked) {
        int index = entries.size();
        if (index == pool.size()) {
            pool.add(new Matrix4f());
        }
        entries.add(new Entry(type, state, pool.get(index).set(socketMatrix), cooksInto, cooked));
    }

    /** This frame's items; valid until {@link #clear()}. */
    public List<Entry> entries() {
        return entries;
    }

    public void clear() {
        entries.clear();
    }
}
