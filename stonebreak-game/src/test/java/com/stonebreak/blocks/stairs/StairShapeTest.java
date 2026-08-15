package com.stonebreak.blocks.stairs;

import com.stonebreak.blocks.BlockType;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Step profile derived from the REAL stair SBOs on the test classpath.
 *
 * <p>Asserts asset-independent invariants — a staircase climbs, tops out at
 * the cell ceiling, starts low enough to walk onto, and turns with its facing —
 * rather than exact tread coordinates, so re-authoring the model with four
 * steps or a different rise keeps these green.
 */
class StairShapeTest {

    private static final float EPS = 1e-4f;
    /** How high a body can step without jumping; the first tread must clear it. */
    private static final float STEP_UP_LIMIT = 0.5f;

    @Test
    void everyStairBlockHasAClimbableProfile() {
        for (BlockType type : BlockType.values()) {
            if (!type.isStairs()) {
                continue;
            }
            StairShape shape = StairShape.of(type);

            float low = shape.heightAt(StairState.Facing.SOUTH, 0.5f, 0.05f);
            float high = shape.heightAt(StairState.Facing.SOUTH, 0.5f, 0.95f);

            assertTrue(low > 0f, type + " must be solid at its low end");
            assertTrue(low <= STEP_UP_LIMIT,
                    type + " first tread must be steppable, was " + low);
            assertEquals(1f, high, EPS, type + " must be full height at its tall end");
        }
    }

    @Test
    void theProfileOnlyEverClimbs() {
        StairShape shape = StairShape.of(BlockType.OAK_STAIRS);
        float previous = 0f;
        int rises = 0;
        for (float z = 0.02f; z < 1f; z += 0.02f) {
            float h = shape.heightAt(StairState.Facing.SOUTH, 0.5f, z);
            assertTrue(h >= previous - EPS,
                    "profile dipped at z=" + z + " (" + previous + " -> " + h + ")");
            if (h > previous + EPS) {
                rises++;
            }
            previous = h;
        }
        assertTrue(rises >= 2, "a staircase needs more than one step, found " + rises);
    }

    @Test
    void noStepIsTallerThanABodyCanClimb() {
        StairShape shape = StairShape.of(BlockType.OAK_STAIRS);
        float previous = shape.heightAt(StairState.Facing.SOUTH, 0.5f, 0.001f);
        for (float z = 0.002f; z < 1f; z += 0.001f) {
            float h = shape.heightAt(StairState.Facing.SOUTH, 0.5f, z);
            assertTrue(h - previous <= STEP_UP_LIMIT + EPS,
                    "step at z=" + z + " rises " + (h - previous) + ", too tall to walk up");
            previous = h;
        }
    }

    @Test
    void facingTurnsTheProfileWithTheModel() {
        StairShape shape = StairShape.of(BlockType.OAK_STAIRS);
        // The tall end sits on the side the stair ascends toward.
        assertEquals(1f, shape.heightAt(StairState.Facing.SOUTH, 0.5f, 0.95f), EPS);
        assertEquals(1f, shape.heightAt(StairState.Facing.NORTH, 0.5f, 0.05f), EPS);
        assertEquals(1f, shape.heightAt(StairState.Facing.EAST, 0.95f, 0.5f), EPS);
        assertEquals(1f, shape.heightAt(StairState.Facing.WEST, 0.05f, 0.5f), EPS);

        // ...and the low end on the opposite one.
        assertTrue(shape.heightAt(StairState.Facing.SOUTH, 0.5f, 0.05f) < 1f);
        assertTrue(shape.heightAt(StairState.Facing.NORTH, 0.5f, 0.95f) < 1f);
        assertTrue(shape.heightAt(StairState.Facing.EAST, 0.05f, 0.5f) < 1f);
        assertTrue(shape.heightAt(StairState.Facing.WEST, 0.95f, 0.5f) < 1f);
    }

    @Test
    void aFootprintRestsOnTheTallestStepItOverlaps() {
        StairShape shape = StairShape.of(BlockType.OAK_STAIRS);
        // A body straddling the whole cell stands on top, exactly as it would
        // on a stack of solid boxes.
        assertEquals(1f, shape.maxHeightIn(StairState.Facing.SOUTH, 0f, 0f, 1f, 1f), EPS);
        // One confined to the low end stands on the low tread.
        float low = shape.maxHeightIn(StairState.Facing.SOUTH, 0.4f, 0.0f, 0.6f, 0.1f);
        assertTrue(low > 0f && low < 1f, "low-end footprint should rest below the top, got " + low);
    }

    @Test
    void queriesOutsideTheCellAreClamped() {
        StairShape shape = StairShape.of(BlockType.OAK_STAIRS);
        assertEquals(shape.heightAt(StairState.Facing.SOUTH, 0f, 0f),
                shape.heightAt(StairState.Facing.SOUTH, -3f, -3f), EPS);
        assertEquals(shape.heightAt(StairState.Facing.SOUTH, 1f, 1f),
                shape.heightAt(StairState.Facing.SOUTH, 4f, 4f), EPS);
    }

    @Test
    void profilesAreCached() {
        assertTrue(StairShape.of(BlockType.OAK_STAIRS) == StairShape.of(BlockType.OAK_STAIRS),
                "profiles are built once per block type");
    }
}
