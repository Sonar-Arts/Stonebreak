package com.stonebreak.player.locomotion;

import static com.stonebreak.player.PlayerConstants.JUMP_FORCE;
import static com.stonebreak.player.PlayerConstants.WATER_BUOYANCY;
import static com.stonebreak.player.PlayerConstants.WATER_JUMP_BOOST;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.stonebreak.core.Game;
import com.stonebreak.player.state.PhysicsState;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * Unit tests for {@link JumpHandler} — ground jumps, double-jump, water buoyancy,
 * and jump-release damping. Headless: no World, Renderer, or OpenGL.
 */
class JumpHandlerTest {

    private static final float DT = 1f / 60f;

    private PhysicsState state;
    private JumpHandler jumpHandler;

    @BeforeEach
    void setUp() {
        state = new PhysicsState();
        state.setOnGround(true);
        state.getVelocity().set(0f, 0f, 0f);

        // Game singleton must exist before setDeltaTimeForTesting takes effect.
        Game.getInstance();
        Game.setDeltaTimeForTesting(DT);

        jumpHandler = new JumpHandler(state);
    }

    // ── Helpers (non-flying, non-water ground cases) ──────────────────────────

    /** Simulate a fresh press of the jump key (rising edge from false → true). */
    private void press() {
        jumpHandler.processJumpInput(true, false, false);
    }

    /** Simulate holding the jump key (no edge — previous call also had jump=true). */
    private void hold() {
        jumpHandler.processJumpInput(true, false, false);
    }

    /** Simulate releasing the jump key (falling edge true → false). */
    private void release() {
        jumpHandler.processJumpInput(false, false, false);
    }

    // ── Tests ──────────────────────────────────────────────────────────────────

    @Test
    void groundJumpSetsJumpForceAndLeavesGround() {
        state.setOnGround(true);
        press();

        assertEquals(JUMP_FORCE, state.getVelocity().y, 1e-4f);
        assertFalse(state.isOnGround());
    }

    @Test
    void holdingJumpDoesNotRetrigger() {
        state.setOnGround(true);
        press();
        assertEquals(JUMP_FORCE, state.getVelocity().y, 1e-4f);

        // Reset state for a second call — the key is still held, so this is a hold, not a press.
        state.getVelocity().y = 0f;
        state.setOnGround(true);
        hold();

        // Rising edge is required; the hold should not fire another jump.
        assertEquals(0f, state.getVelocity().y, 1e-4f);
    }

    @Test
    void releasingAndPressingAgainJumpsOnceMore() {
        state.setOnGround(true);
        press();
        release();

        // Land back on the ground for a second jump.
        state.setOnGround(true);
        state.getVelocity().y = 0f;
        press();

        assertEquals(JUMP_FORCE, state.getVelocity().y, 1e-4f);
    }

    @Test
    void noJumpWhileAirborneWithoutDoubleJumpFeat() {
        state.setOnGround(false);
        press();

        // Double jump is disabled by default, so nothing should happen.
        assertEquals(0f, state.getVelocity().y, 1e-4f);
    }

    @Test
    void doubleJumpFiresOnceWhenFeatEnabled() {
        jumpHandler.setCanDoubleJump(true);
        state.setOnGround(false);

        press();
        assertEquals(JUMP_FORCE, state.getVelocity().y, 1e-4f);

        release();
        state.getVelocity().y = 0f;

        // Second press should not fire — the double jump is already used.
        press();
        assertEquals(0f, state.getVelocity().y, 1e-4f);
    }

    @Test
    void doubleJumpRechargesOnGround() {
        jumpHandler.setCanDoubleJump(true);

        // Use the double jump while airborne.
        state.setOnGround(false);
        press();
        assertEquals(JUMP_FORCE, state.getVelocity().y, 1e-4f);

        release();

        // Touch ground — the frame that sees wasOnGround=true clears doubleJumpUsed.
        state.setOnGround(true);
        release();

        // Go airborne again; the double jump should be available. Zero the velocity first,
        // otherwise it still reads JUMP_FORCE from the first jump and the assertion below
        // would pass whether or not the recharge actually happened.
        state.setOnGround(false);
        state.getVelocity().y = 0f;
        press();
        assertEquals(JUMP_FORCE, state.getVelocity().y, 1e-4f);
    }

    @Test
    void noGroundJumpWhileFlying() {
        state.setOnGround(true);
        jumpHandler.processJumpInput(true, false, true); // flying = true

        assertEquals(0f, state.getVelocity().y, 1e-4f);
        assertTrue(state.isOnGround());
    }

    @Test
    void waterJumpPressAppliesBoostAndBuoyancy() {
        state.setPhysicallyInWater(true);
        state.setOnGround(false);
        press();

        // Both the press-boost and the held-buoyancy branch fire on the same frame
        // because they are two separate if-statements, not if/else.
        float expected = WATER_JUMP_BOOST + WATER_BUOYANCY * DT;
        assertEquals(expected, state.getVelocity().y, 1e-4f);
    }

    @Test
    void holdingJumpInWaterAppliesBuoyancyOnly() {
        state.setPhysicallyInWater(true);
        state.setOnGround(false);
        press();

        // Zero velocity and hold again — no press boost, only buoyancy.
        state.getVelocity().y = 0f;
        hold();

        assertEquals(WATER_BUOYANCY * DT, state.getVelocity().y, 1e-4f);
    }

    @Test
    void waterJumpDoesNotUseGroundJumpForce() {
        state.setOnGround(true);
        state.setPhysicallyInWater(true);
        press();

        // The ground-jump branch excludes water, so only the water result applies.
        float expected = WATER_JUMP_BOOST + WATER_BUOYANCY * DT;
        assertEquals(expected, state.getVelocity().y, 1e-4f);

        // onGround is not cleared by the water jump.
        assertTrue(state.isOnGround());
    }

    @Test
    void releasingJumpAfterWaterExitDampsRise() {
        state.setWasInWaterLastFrame(true);
        state.setOnGround(false);
        state.setPhysicallyInWater(false);

        press();
        state.getVelocity().y = 10f;
        release();

        assertEquals(3f, state.getVelocity().y, 1e-4f); // 10 * 0.3
    }

    @Test
    void releasingJumpInAirWithNoWaterHistoryDoesNotDamp() {
        state.setOnGround(false);
        state.setPhysicallyInWater(false);
        state.setWasInWaterLastFrame(false);

        press();
        state.getVelocity().y = 10f;
        release();

        assertEquals(10f, state.getVelocity().y, 1e-4f);
    }

    @Test
    void resetClearsPressLatch() {
        state.setOnGround(true);
        press();
        jumpHandler.reset();

        // After reset, the next true should count as a fresh rising edge.
        state.setOnGround(true);
        state.getVelocity().y = 0f;
        jumpHandler.processJumpInput(true, false, false);

        assertEquals(JUMP_FORCE, state.getVelocity().y, 1e-4f);
    }
}