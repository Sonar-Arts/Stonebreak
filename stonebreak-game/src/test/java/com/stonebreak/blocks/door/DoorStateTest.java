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

    /**
     * {@code modelBoxToWorld} must transform a model-space box exactly like
     * the renderer's base transform (facing rotation about the model origin,
     * then the cell anchor): NORTH closed-panel-style box hugs the min-Z edge,
     * and every facing preserves the box's dimensions (axes swapped for the
     * 90°/270° facings) and passes Y through untouched.
     */
    @Test
    void modelBoxToWorldMatchesRendererBaseTransform() {
        int bx = 10;
        int by = 64;
        int bz = 20;
        // Asymmetric box so mirror errors can't cancel out.
        float[] model = {0.2f, 0.0f, 0.0f, 1.0f, 2.0f, 0.1f};
        float dx = model[3] - model[0];
        float dz = model[5] - model[2];

        for (DoorState.Facing f : DoorState.Facing.values()) {
            float[] w = new DoorState(DoorState.CLOSED, f).modelBoxToWorld(model, bx, by, bz);
            assertEquals(by + model[1], w[1], 1e-4, f.name());
            assertEquals(by + model[4], w[4], 1e-4, f.name());

            float wx = w[3] - w[0];
            float wz = w[5] - w[2];
            boolean swapped = f == DoorState.Facing.WEST || f == DoorState.Facing.EAST;
            assertEquals(swapped ? dz : dx, wx, 1e-4, f.name());
            assertEquals(swapped ? dx : dz, wz, 1e-4, f.name());

            // Verify against the actual JOML transform the renderer uses:
            // world = T(corner + anchor) * rotY(yaw) applied to the corners.
            Matrix4f base = new Matrix4f()
                    .translate(bx + f.anchorOffsetX(), by, bz + f.anchorOffsetZ())
                    .rotateY((float) Math.toRadians(f.yawDegrees()));
            Vector3f c0 = base.transformPosition(new Vector3f(model[0], model[1], model[2]));
            Vector3f c1 = base.transformPosition(new Vector3f(model[3], model[4], model[5]));
            assertEquals(Math.min(c0.x, c1.x), w[0], 1e-4, f.name());
            assertEquals(Math.max(c0.x, c1.x), w[3], 1e-4, f.name());
            assertEquals(Math.min(c0.z, c1.z), w[2], 1e-4, f.name());
            assertEquals(Math.max(c0.z, c1.z), w[5], 1e-4, f.name());
        }

        // NORTH keeps the box exactly where the model put it.
        float[] north = new DoorState(DoorState.CLOSED, DoorState.Facing.NORTH)
                .modelBoxToWorld(model, bx, by, bz);
        assertEquals(bx + model[0], north[0], 1e-4);
        assertEquals(bz + model[2], north[2], 1e-4);
    }
}
