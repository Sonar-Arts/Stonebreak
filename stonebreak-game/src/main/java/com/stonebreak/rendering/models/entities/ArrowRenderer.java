package com.stonebreak.rendering.models.entities;

import com.openmason.engine.rendering.RenderOrigin;
import com.stonebreak.items.ItemType;
import com.stonebreak.mobs.entities.Arrow;
import org.joml.Matrix4f;
import org.joml.Vector3f;

/**
 * Draws arrow projectiles as the arrow item's voxelized SBO sprite, pointed along the arrow's
 * flight path (pitch included, so it noses over as it arcs).
 *
 * <p>Voxelized items draw with the world shader in the drop pass, so the arrow is queued on the
 * {@link SocketItemQueue} like a mob's held item. In that socket frame the sprite lies in the XY
 * plane, one unit across, and the arrow sprite's top-left-to-bottom-right diagonal (head to
 * fletching) lands on the X axis with the head at -X. Two copies are queued, rolled 90° about the
 * shaft, so the arrow never vanishes edge-on.
 */
final class ArrowRenderer {

    /** Socket units → blocks; the sprite's diagonal is ~1.2 units, so the arrow is ~0.6 blocks long. */
    private static final float SCALE = 0.5f;
    /** Below this |heading.y| the world up is a usable reference axis for the arrow's roll. */
    private static final float NEAR_VERTICAL = 0.999f;
    private static final Vector3f WORLD_UP = new Vector3f(0, 1, 0);
    private static final Vector3f WORLD_NORTH = new Vector3f(0, 0, -1);

    private final SocketItemQueue queue;
    private final Matrix4f flight = new Matrix4f();
    private final Matrix4f copy = new Matrix4f();

    ArrowRenderer(SocketItemQueue queue) {
        this.queue = queue;
    }

    void render(Arrow arrow) {
        Vector3f heading = arrow.heading();
        Vector3f up = Math.abs(heading.y) < NEAR_VERTICAL ? WORLD_UP : WORLD_NORTH;
        Vector3f pos = arrow.getPosition();
        // rotateTowards maps local +Z onto the heading; rotateY(90°) then turns the sprite's
        // head (-X) onto local +Z.
        RenderOrigin.modelAt(flight, pos.x, pos.y, pos.z)
                .rotateTowards(heading, up)
                .rotateY((float) Math.toRadians(90.0))
                .scale(SCALE);
        queue.add(ItemType.ARROW, null, flight);
        queue.add(ItemType.ARROW, null, copy.set(flight).rotateX((float) Math.toRadians(90.0)));
    }
}
