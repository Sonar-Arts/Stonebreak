package com.stonebreak.blocks.stairs;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Parsing, placement orientation and the cell-space rotation every shape
 * lookup goes through.
 */
class StairStateTest {

    private static final float EPS = 1e-5f;

    @Test
    void roundTripsThroughItsStateString() {
        for (StairState.Facing facing : StairState.Facing.values()) {
            StairState state = new StairState(facing);
            assertEquals(state, StairState.parse(state.toStateString()));
        }
    }

    @Test
    void stateStringIsTheStampCacheKey() {
        // The renderer registers rotated stamps under stateStringFor(); the two
        // must never drift apart or a placed stair falls back to the default mesh.
        for (StairState.Facing facing : StairState.Facing.values()) {
            assertEquals(new StairState(facing).toStateString(),
                    StairState.stateStringFor(facing));
        }
    }

    @Test
    void unreadableStatesFallBackToTheAuthoredOrientation() {
        assertEquals(StairState.Facing.SOUTH, StairState.parse(null).facing());
        assertEquals(StairState.Facing.SOUTH, StairState.parse("").facing());
        assertEquals(StairState.Facing.SOUTH, StairState.parse("door:state=Open").facing());
        assertEquals(StairState.Facing.SOUTH, StairState.parse("stairs:facing=SIDEWAYS").facing());
        // Unknown keys are ignored rather than rejected.
        assertEquals(StairState.Facing.EAST,
                StairState.parse("stairs:facing=EAST;half=top").facing());
    }

    @Test
    void recognisesOnlyItsOwnPrefix() {
        assertTrue(StairState.isStairState("stairs:facing=NORTH"));
        assertFalse(StairState.isStairState("door:state=Open;facing=NORTH"));
        assertFalse(StairState.isStairState(null));
    }

    @Test
    void placementAscendsTheWayThePlacerLooks() {
        // Camera convention: yaw 0 looks +X (east), yaw 90 looks +Z (south).
        assertEquals(StairState.Facing.EAST, StairState.placedFromYaw(0f).facing());
        assertEquals(StairState.Facing.SOUTH, StairState.placedFromYaw(90f).facing());
        assertEquals(StairState.Facing.WEST, StairState.placedFromYaw(180f).facing());
        assertEquals(StairState.Facing.NORTH, StairState.placedFromYaw(270f).facing());
        // Off-axis looks resolve to the dominant horizontal axis.
        assertEquals(StairState.Facing.EAST, StairState.placedFromYaw(40f).facing());
        assertEquals(StairState.Facing.SOUTH, StairState.placedFromYaw(50f).facing());
        // A look vector gives the same answer as the equivalent yaw.
        assertEquals(StairState.Facing.NORTH, StairState.placedFromLook(0.1f, -0.9f).facing());
    }

    @Test
    void quarterTurnsMatchTheFacings() {
        assertEquals(0, StairState.Facing.SOUTH.quarterTurns());
        assertEquals(1, StairState.Facing.EAST.quarterTurns());
        assertEquals(2, StairState.Facing.NORTH.quarterTurns());
        assertEquals(3, StairState.Facing.WEST.quarterTurns());
    }

    @Test
    void cellRotationIsTheInverseOfTheStampRotation() {
        // The stamp is turned by rotateY (x' = z, z' = -x for one quarter
        // turn); a shape query must undo exactly that, or collision drifts
        // away from what is drawn.
        float[] probe = {0.2f, 0.9f};
        for (StairState.Facing facing : StairState.Facing.values()) {
            float[] forward = rotateCell(probe[0], probe[1], facing.quarterTurns());
            float[] back = new StairState(facing).toModelCell(forward[0], forward[1]);
            assertEquals(probe[0], back[0], EPS, "x for " + facing);
            assertEquals(probe[1], back[1], EPS, "z for " + facing);
        }
    }

    @Test
    void theAuthoredFacingIsTheIdentityMapping() {
        float[] mapped = new StairState(StairState.Facing.SOUTH).toModelCell(0.25f, 0.75f);
        assertEquals(0.25f, mapped[0], EPS);
        assertEquals(0.75f, mapped[1], EPS);
    }

    @Test
    void aQuarterTurnMovesTheTallSideFromSouthToEast() {
        // The model's full-height side is at max Z; after one turn it must be
        // the max-X side, i.e. a point on the east edge maps back to the south
        // edge of the authored model.
        float[] mapped = new StairState(StairState.Facing.EAST).toModelCell(1.0f, 0.5f);
        assertEquals(0.5f, mapped[0], EPS);
        assertEquals(1.0f, mapped[1], EPS);
    }

    /** JOML rotateY in cell space, matching SBOStampRotator. */
    private static float[] rotateCell(float x, float z, int turns) {
        float u = x - 0.5f;
        float v = z - 0.5f;
        float[] r = switch (turns) {
            case 1 -> new float[]{v, -u};
            case 2 -> new float[]{-u, -v};
            case 3 -> new float[]{-v, u};
            default -> new float[]{u, v};
        };
        return new float[]{r[0] + 0.5f, r[1] + 0.5f};
    }
}
