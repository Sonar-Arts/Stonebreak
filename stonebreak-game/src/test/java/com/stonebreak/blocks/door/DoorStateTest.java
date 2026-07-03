package com.stonebreak.blocks.door;

import org.joml.Matrix4f;
import org.joml.Vector3f;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Door state string round-trip, toggling, placement facing, and the
 * facing→(yaw, anchor offset) math the AnimatedBlockRenderer relies on.
 */
class DoorStateTest {

    @Test
    void parseRoundTrip() {
        DoorState s = new DoorState(DoorState.OPEN, DoorState.Facing.EAST);
        assertEquals("door:state=Open;facing=EAST", s.toStateString());
        DoorState parsed = DoorState.parse(s.toStateString());
        assertEquals(DoorState.OPEN, parsed.renderState());
        assertEquals(DoorState.Facing.EAST, parsed.facing());
        assertTrue(parsed.isOpen());
    }

    @Test
    void parseIsTolerant() {
        // Null / foreign prefix / garbage all fall back to Closed + NORTH.
        for (String raw : new String[]{null, "", "furnace:state=Lit", "door:", "door:bogus", "door:state="}) {
            DoorState s = DoorState.parse(raw);
            assertEquals(DoorState.CLOSED, s.renderState(), "raw=" + raw);
            assertEquals(DoorState.Facing.NORTH, s.facing(), "raw=" + raw);
        }
        // Unknown keys ignored, known ones still read (forward-compat).
        DoorState s = DoorState.parse("door:state=Open;future=thing;facing=SOUTH");
        assertTrue(s.isOpen());
        assertEquals(DoorState.Facing.SOUTH, s.facing());
    }

    @Test
    void togglePreservesFacing() {
        DoorState closed = new DoorState(DoorState.CLOSED, DoorState.Facing.WEST);
        DoorState open = closed.toggled();
        assertTrue(open.isOpen());
        assertEquals(DoorState.Facing.WEST, open.facing());
        assertFalse(open.toggled().isOpen());
    }

    @Test
    void placementPicksNearestEdge() {
        // Placer standing north of the cell (smaller z) → panel on the NORTH edge.
        assertEquals(DoorState.Facing.NORTH, DoorState.Facing.nearestEdge(10.5, 8.0, 10, 10));
        assertEquals(DoorState.Facing.SOUTH, DoorState.Facing.nearestEdge(10.5, 13.0, 10, 10));
        assertEquals(DoorState.Facing.WEST, DoorState.Facing.nearestEdge(8.0, 10.5, 10, 10));
        assertEquals(DoorState.Facing.EAST, DoorState.Facing.nearestEdge(13.0, 10.5, 10, 10));
    }

    /**
     * The renderer draws the model at {@code corner + (anchorX, 0, anchorZ)}
     * rotated by {@code yawDegrees} about the model origin. Verify that for
     * every facing this places the closed panel's center (model-space
     * {@code (0.5, y, 0.05)}) on the claimed cell edge — i.e. the anchor
     * offsets really implement rotation about the cell center.
     */
    @Test
    void facingAnchorMathPutsPanelOnClaimedEdge() {
        Vector3f panelCenterModel = new Vector3f(0.5f, 0f, 0.05f);
        for (DoorState.Facing f : DoorState.Facing.values()) {
            Matrix4f base = new Matrix4f()
                    .translate(f.anchorOffsetX(), 0f, f.anchorOffsetZ())
                    .rotateY((float) Math.toRadians(f.yawDegrees()));
            Vector3f world = base.transformPosition(new Vector3f(panelCenterModel));
            // Cell-space coords in [0,1]; the panel center must hug one edge.
            switch (f) {
                case NORTH -> assertEquals(0.05f, world.z, 1e-4, f.name());
                case SOUTH -> assertEquals(0.95f, world.z, 1e-4, f.name());
                case WEST -> assertEquals(0.05f, world.x, 1e-4, f.name());
                case EAST -> assertEquals(0.95f, world.x, 1e-4, f.name());
            }
            // And it must stay inside the cell footprint.
            assertTrue(world.x > -1e-4 && world.x < 1f + 1e-4, f.name() + " x=" + world.x);
            assertTrue(world.z > -1e-4 && world.z < 1f + 1e-4, f.name() + " z=" + world.z);
        }
    }

    /** Closed panels: thin 2-tall box inside the cell, hugging the facing edge. */
    @Test
    void closedPanelAabbHugsFacingEdgeInsideCell() {
        int bx = 10;
        int by = 64;
        int bz = 20;
        float t = DoorState.PANEL_THICKNESS;
        for (DoorState.Facing f : DoorState.Facing.values()) {
            float[] b = new DoorState(DoorState.CLOSED, f).panelWorldAabb(bx, by, bz);
            assertEquals(by, b[1], 1e-4);
            assertEquals(by + DoorState.PANEL_HEIGHT, b[4], 1e-4);
            // Fully inside the cell footprint.
            assertTrue(b[0] >= bx - 1e-4 && b[3] <= bx + 1 + 1e-4, f.name());
            assertTrue(b[2] >= bz - 1e-4 && b[5] <= bz + 1 + 1e-4, f.name());
            switch (f) {
                case NORTH -> { assertEquals(bz, b[2], 1e-4); assertEquals(bz + t, b[5], 1e-4); }
                case SOUTH -> { assertEquals(bz + 1 - t, b[2], 1e-4); assertEquals(bz + 1, b[5], 1e-4); }
                case WEST -> { assertEquals(bx, b[0], 1e-4); assertEquals(bx + t, b[3], 1e-4); }
                case EAST -> { assertEquals(bx + 1 - t, b[0], 1e-4); assertEquals(bx + 1, b[3], 1e-4); }
            }
        }
    }

    /**
     * Open panels: the +90° hinge swing carries the panel across the
     * hinge-side cell boundary — the box must track that, not the cell.
     */
    @Test
    void openPanelAabbFollowsTheSwungModel() {
        int bx = 10;
        int by = 64;
        int bz = 20;
        float t = DoorState.PANEL_THICKNESS;

        // NORTH: closed panel spans x along the min-Z edge, hinge at the cell
        // corner (bx, bz); +90° swings it north into z ∈ [bz-1, bz].
        float[] b = new DoorState(DoorState.OPEN, DoorState.Facing.NORTH).panelWorldAabb(bx, by, bz);
        assertEquals(bx, b[0], 1e-4);
        assertEquals(bx + t, b[3], 1e-4);
        assertEquals(bz - 1, b[2], 1e-4);
        assertEquals(bz, b[5], 1e-4);
        assertEquals(by + DoorState.PANEL_HEIGHT, b[4], 1e-4);

        // Every open box must be thin on one horizontal axis and 1 long on the other.
        for (DoorState.Facing f : DoorState.Facing.values()) {
            float[] box = new DoorState(DoorState.OPEN, f).panelWorldAabb(bx, by, bz);
            float dx = box[3] - box[0];
            float dz = box[5] - box[2];
            assertTrue((Math.abs(dx - t) < 1e-4 && Math.abs(dz - 1f) < 1e-4)
                    || (Math.abs(dx - 1f) < 1e-4 && Math.abs(dz - t) < 1e-4), f.name());
        }
    }
}
