package com.stonebreak.blocks.torch;

import org.joml.Matrix4f;
import org.joml.Vector3f;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Torch state string round-trip, placement-face rules, and the facing→yaw
 * convention: the authored Side pose leans off the −Z wall, and spinning it by
 * the facing's yaw about the cell center must land it on the named wall.
 */
class TorchStateTest {

    @Test
    void parseRoundTrip() {
        TorchState s = TorchState.side(TorchState.Facing.EAST);
        assertEquals("torch:state=Side;facing=EAST", s.toStateString());
        TorchState parsed = TorchState.parse(s.toStateString());
        assertTrue(parsed.isSide());
        assertEquals(TorchState.Facing.EAST, parsed.facing());
        assertEquals("torch:state=Ground;facing=NORTH", TorchState.ground().toStateString());
    }

    @Test
    void parseIsTolerant() {
        for (String raw : new String[]{null, "", "door:state=Open", "torch:", "torch:bogus", "torch:state="}) {
            TorchState s = TorchState.parse(raw);
            assertFalse(s.isSide(), "raw=" + raw);
            assertEquals(TorchState.Facing.NORTH, s.facing());
        }
        // A ground torch never carries a facing, whatever the string says.
        assertEquals(TorchState.Facing.NORTH, TorchState.parse("torch:state=Ground;facing=WEST").facing());
        // Unknown keys are ignored; case-insensitive values.
        TorchState s = TorchState.parse("torch:state=side;facing=south;foo=bar");
        assertTrue(s.isSide());
        assertEquals(TorchState.Facing.SOUTH, s.facing());
    }

    @Test
    void placementFaceRules() {
        assertFalse(TorchState.forPlacementNormal(0, 1, 0).isSide(), "top face → ground torch");
        assertNull(TorchState.forPlacementNormal(0, -1, 0), "underside refused");
        // Side faces: the wall lies opposite the face normal.
        assertEquals(TorchState.Facing.NORTH, TorchState.forPlacementNormal(0, 0, 1).facing());
        assertEquals(TorchState.Facing.SOUTH, TorchState.forPlacementNormal(0, 0, -1).facing());
        assertEquals(TorchState.Facing.WEST, TorchState.forPlacementNormal(1, 0, 0).facing());
        assertEquals(TorchState.Facing.EAST, TorchState.forPlacementNormal(-1, 0, 0).facing());
    }

    @Test
    void supportOffsetsPointAtTheHoldingBlock() {
        TorchState ground = TorchState.ground();
        assertEquals(0, ground.supportDx());
        assertEquals(-1, ground.supportDy());
        assertEquals(0, ground.supportDz());
        TorchState west = TorchState.side(TorchState.Facing.WEST);
        assertEquals(-1, west.supportDx());
        assertEquals(0, west.supportDy());
        assertEquals(0, west.supportDz());
    }

    @Test
    void yawRotatesTheAuthoredBaseOntoTheNamedWall() {
        // The authored Side model's base sits at model z = -0.44 (the −Z wall).
        Vector3f base = new Vector3f(0f, 0.16f, -0.44f);
        for (TorchState.Facing f : TorchState.Facing.values()) {
            TorchState s = TorchState.side(f);
            Vector3f world = new Matrix4f()
                    .translate(10.5f, 5f, 20.5f)
                    .rotateY((float) Math.toRadians(s.yawDegrees()))
                    .transformPosition(new Vector3f(base));
            // The base must be ~0.44 toward the wall block from the cell center.
            float dx = world.x - 10.5f;
            float dz = world.z - 20.5f;
            assertEquals(f.wallDx() * 0.44f, dx, 1e-4f, f.name());
            assertEquals(f.wallDz() * 0.44f, dz, 1e-4f, f.name());
            // And lie inside the targeting box for that facing.
            float[] box = s.worldAabb(10, 5, 20);
            assertTrue(world.x >= box[0] && world.x <= box[3], f + " x in box");
            assertTrue(world.z >= box[2] && world.z <= box[5], f + " z in box");
            assertTrue(world.y >= box[1] && world.y <= box[4], f + " y in box");
        }
    }

    @Test
    void emberSitsOffTheWallItHangsFrom() {
        Vector3f out = new Vector3f();
        TorchState.side(TorchState.Facing.EAST).emberPosition(0, 0, 0, out);
        assertTrue(out.x > 0.5f, "east torch ember leans toward +X wall");
        TorchState.ground().emberPosition(0, 0, 0, out);
        assertEquals(0.5f, out.x, 1e-6f);
        assertEquals(0.5f, out.z, 1e-6f);
        assertTrue(out.y > 0.5f && out.y < 0.7f, "ground ember at the stick's head");
    }
}
