package com.stonebreak.blocks.anim;

import com.stonebreak.blocks.BlockType;
import com.stonebreak.blocks.door.DoorState;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Settled model bounds derived from the REAL SB_Oak_Door.sbo on the test
 * classpath. Asserts asset-independent invariants (not exact coordinates), so
 * re-authoring the door — new hinge origin, new thickness, opposite swing —
 * keeps these green as long as the shape pipeline poses the geometry the same
 * way the renderer does.
 */
class AnimatedBlockShapesTest {

    private static final float EPS = 1e-3f;

    @Test
    void closedBoundsEqualRestGeometryBounds() {
        float[] closed = AnimatedBlockShapes.settledModelAabb(BlockType.OAK_DOOR, DoorState.CLOSED);
        // The Closed clip's end pose is rotation 0 — identical to rest, so the
        // box must be the raw mesh bounds: taller than a cell (a 2-tall door)
        // and thin on one horizontal axis.
        float dx = closed[3] - closed[0];
        float dy = closed[4] - closed[1];
        float dz = closed[5] - closed[2];
        assertTrue(dy > 1.5f, "door should be ~2 blocks tall, got " + dy);
        assertTrue(Math.min(dx, dz) < 0.5f, "panel should be thin, got " + dx + "x" + dz);
        assertTrue(Math.max(dx, dz) > 0.5f, "panel should span the cell, got " + dx + "x" + dz);
    }

    @Test
    void openBoundsAreTheClosedPanelSwungNinetyDegrees() {
        float[] closed = AnimatedBlockShapes.settledModelAabb(BlockType.OAK_DOOR, DoorState.CLOSED);
        float[] open = AnimatedBlockShapes.settledModelAabb(BlockType.OAK_DOOR, DoorState.OPEN);

        // Same height, and a pure Y-swing swaps the horizontal extents.
        assertEquals(closed[1], open[1], EPS);
        assertEquals(closed[4], open[4], EPS);
        float closedDx = closed[3] - closed[0];
        float closedDz = closed[5] - closed[2];
        float openDx = open[3] - open[0];
        float openDz = open[5] - open[2];
        assertEquals(closedDx, openDz, EPS, "90° swing must swap horizontal extents");
        assertEquals(closedDz, openDx, EPS, "90° swing must swap horizontal extents");

        // And the two poses must actually differ (the door really swings).
        boolean moved = Math.abs(closed[0] - open[0]) > EPS || Math.abs(closed[2] - open[2]) > EPS
                || Math.abs(closed[3] - open[3]) > EPS || Math.abs(closed[5] - open[5]) > EPS;
        assertTrue(moved, "open pose must differ from closed pose");
    }

    @Test
    void boundsAreCachedAndStable() {
        float[] a = AnimatedBlockShapes.settledModelAabb(BlockType.OAK_DOOR, DoorState.OPEN);
        float[] b = AnimatedBlockShapes.settledModelAabb(BlockType.OAK_DOOR, DoorState.OPEN);
        assertEquals(a[0], b[0], 0f);
        assertEquals(a[5], b[5], 0f);
    }

    @Test
    void unknownStateFallsBackSafely() {
        float[] box = AnimatedBlockShapes.settledModelAabb(BlockType.OAK_DOOR, "NoSuchState");
        // No clip for the state → rest-pose bounds (never a crash, never empty).
        assertTrue(box[3] > box[0] && box[4] > box[1] && box[5] > box[2]);
    }
}
