package com.stonebreak.player.physics;

import static com.stonebreak.player.PlayerConstants.FLY_SPEED;
import static com.stonebreak.player.PlayerConstants.MOVE_SPEED;
import static com.stonebreak.player.PlayerConstants.SPRINT_MULTIPLIER;
import static com.stonebreak.player.PlayerConstants.SWIM_SPEED;
import static org.junit.jupiter.api.Assertions.assertEquals;

import com.stonebreak.core.Game;
import com.stonebreak.player.Camera;
import com.stonebreak.player.combat.HealthController;
import com.stonebreak.player.locomotion.FlightController;
import com.stonebreak.player.locomotion.JumpHandler;
import com.stonebreak.player.locomotion.SpectatorController;
import com.stonebreak.player.locomotion.SwimmingController;
import com.stonebreak.player.state.PhysicsState;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * Unit tests for {@link MovementController#processMovement} — the WASD input to
 * horizontal-velocity acceleration, with speed-mode selection (walk / sprint / swim / fly)
 * and camera-relative direction projection.
 */
class MovementControllerSpeedTest {

    private static final float DT = 1f / 60f;

    private PhysicsState state;
    private Camera camera;
    private FlightController flight;
    private MovementController movement;

    @BeforeEach
    void setUp() {
        Game.getInstance();
        Game.setDeltaTimeForTesting(DT);

        state = new PhysicsState();
        state.setWaterExitTime(10f);

        camera = new Camera();
        CollisionHandler collisionHandler = new CollisionHandler(state, null);
        flight = new FlightController(state);
        SwimmingController swimming = new SwimmingController(state, null);
        JumpHandler jumpHandler = new JumpHandler(state);
        HealthController health = new HealthController();
        SpectatorController spectator = new SpectatorController(state, flight, health);

        movement = new MovementController(state, camera, collisionHandler, flight,
                swimming, jumpHandler, spectator);
    }

    /** Shorthand for processMovement with jump=false and crouch=false. */
    private void move(boolean forward, boolean backward, boolean left, boolean right,
                      boolean shift, boolean sprinting, float speedMultiplier) {
        movement.processMovement(forward, backward, left, right,
                false, shift, false, sprinting, speedMultiplier);
    }

    // ── Ground walking ───────────────────────────────────────────────────────

    @Test
    void walkOnGroundAcceleratesAtMoveSpeed() {
        state.setOnGround(true);
        move(true, false, false, false, false, false, 1f);

        assertEquals(-MOVE_SPEED * DT, state.getVelocity().z, 1e-4f);
        assertEquals(0f, state.getVelocity().x, 1e-4f);
    }

    @Test
    void sprintOnGroundAppliesSprintMultiplier() {
        state.setOnGround(true);
        move(true, false, false, false, false, true, 1f);

        assertEquals(-MOVE_SPEED * SPRINT_MULTIPLIER * DT, state.getVelocity().z, 1e-4f);
    }

    // ── Airborne penalties ───────────────────────────────────────────────────

    @Test
    void airborneWalkIsReducedToEightyFivePercent() {
        // Not on ground, not sprinting → 0.85× move speed penalty.
        move(true, false, false, false, false, false, 1f);

        assertEquals(-MOVE_SPEED * 0.85f * DT, state.getVelocity().z, 1e-4f);
    }

    @Test
    void airborneSprintGetsNoAirPenalty() {
        // Characterization: the 0.85 air penalty applies to walking but not to sprinting,
        // so an airborne sprinter is 1.76× the airborne walker (1.5 / 0.85) rather than
        // the 1.5× seen on the ground.
        move(true, false, false, false, false, true, 1f);

        assertEquals(-MOVE_SPEED * SPRINT_MULTIPLIER * DT, state.getVelocity().z, 1e-4f);
    }

    // ── Swimming ─────────────────────────────────────────────────────────────

    @Test
    void swimmingUsesSwimSpeed() {
        state.setPhysicallyInWater(true);
        move(true, false, false, false, false, false, 1f);

        assertEquals(-SWIM_SPEED * DT, state.getVelocity().z, 1e-4f);
        // velocity.y is not asserted — applyVerticalSwimControl mutates it with idle buoyancy.
    }

    @Test
    void swimmingSprintAppliesSprintMultiplier() {
        state.setPhysicallyInWater(true);
        move(true, false, false, false, false, true, 1f);

        assertEquals(-SWIM_SPEED * SPRINT_MULTIPLIER * DT, state.getVelocity().z, 1e-4f);
    }

    @Test
    void waterSpeedTakesPrecedenceOverGroundSpeed() {
        // The speed-selection branches check physicallyInWater before onGround,
        // so swim speed always wins when both flags are true.
        state.setOnGround(true);
        state.setPhysicallyInWater(true);
        move(true, false, false, false, false, false, 1f);

        assertEquals(-SWIM_SPEED * DT, state.getVelocity().z, 1e-4f);
    }

    // ── Flying ───────────────────────────────────────────────────────────────

    @Test
    void flyingUsesFlySpeedAndShiftDoubles() {
        flight.setFlying(true);

        // shift=false → normal fly speed.
        move(true, false, false, false, false, false, 1f);
        assertEquals(-FLY_SPEED * DT, state.getVelocity().z, 1e-4f);

        // shift=true → doubled fly speed.
        // While flying, shift means "go faster" whereas on the ground the sprint flag
        // is what raises speed.
        state.getVelocity().set(0, 0, 0);
        move(true, false, false, false, true, false, 1f);
        assertEquals(-FLY_SPEED * 2f * DT, state.getVelocity().z, 1e-4f);
    }

    @Test
    void flyingOverridesWaterSpeed() {
        flight.setFlying(true);
        state.setPhysicallyInWater(true);
        move(true, false, false, false, false, false, 1f);

        assertEquals(-FLY_SPEED * DT, state.getVelocity().z, 1e-4f);
    }

    // ── Speed multiplier ─────────────────────────────────────────────────────

    @Test
    void speedMultiplierScalesLinearly() {
        // This is the channel the Ranger's Marked-Prey chase bonus and the stealth
        // movement penalty feed through.
        state.setOnGround(true);
        move(true, false, false, false, false, false, 1.3f);

        assertEquals(-MOVE_SPEED * 1.3f * DT, state.getVelocity().z, 1e-4f);
    }

    @Test
    void zeroSpeedMultiplierFreezesHorizontalMovement() {
        state.setOnGround(true);
        move(true, false, false, false, false, false, 0f);

        assertEquals(0f, state.getVelocity().x, 1e-4f);
        assertEquals(0f, state.getVelocity().z, 1e-4f);
    }

    // ── Input cancellation ───────────────────────────────────────────────────

    @Test
    void oppositeInputsCancelExactly() {
        state.setOnGround(true);

        // Forward + backward cancel.
        move(true, true, false, false, false, false, 1f);
        assertEquals(0f, state.getVelocity().x, 1e-4f);
        assertEquals(0f, state.getVelocity().z, 1e-4f);

        // Left + right cancel (fresh velocity).
        state.getVelocity().set(0, 0, 0);
        move(false, false, true, true, false, false, 1f);
        assertEquals(0f, state.getVelocity().x, 1e-4f);
        assertEquals(0f, state.getVelocity().z, 1e-4f);
    }

    // ── Direction projection ─────────────────────────────────────────────────

    @Test
    void diagonalMovementIsFasterThanCardinal() {
        // Characterization test: the inputs are summed without normalizing the combined
        // direction, so holding two keys accelerates ~1.41× faster than one key (sqrt(2)).
        state.setOnGround(true);
        move(true, false, false, true, false, false, 1f);

        assertEquals(MOVE_SPEED * DT, state.getVelocity().x, 1e-4f);
        assertEquals(-MOVE_SPEED * DT, state.getVelocity().z, 1e-4f);

        float magnitude = (float) Math.sqrt(
                state.getVelocity().x * state.getVelocity().x +
                state.getVelocity().z * state.getVelocity().z);
        assertEquals(MOVE_SPEED * DT * (float) Math.sqrt(2), magnitude, 1e-4f);
    }

    @Test
    void movementIsRelativeToCameraYaw() {
        camera.setYaw(0f); // front = (1, 0, 0), so "forward" moves along +X.
        state.setOnGround(true);
        move(true, false, false, false, false, false, 1f);

        assertEquals(MOVE_SPEED * DT, state.getVelocity().x, 1e-4f);
        assertEquals(0f, state.getVelocity().z, 1e-4f);
    }

    @Test
    void pitchDoesNotAffectHorizontalSpeed() {
        // Looking straight down: the controller projects front onto the XZ plane
        // and re-normalizes, so horizontal acceleration is unchanged.
        camera.setPitch(-60f);
        state.setOnGround(true);
        move(true, false, false, false, false, false, 1f);

        float horizontalSpeed = (float) Math.sqrt(
                state.getVelocity().x * state.getVelocity().x +
                state.getVelocity().z * state.getVelocity().z);
        assertEquals(MOVE_SPEED * DT, horizontalSpeed, 1e-4f);
        assertEquals(0f, state.getVelocity().y, 1e-4f);
    }

    // ── Accumulation ─────────────────────────────────────────────────────────

    @Test
    void repeatedCallsAccumulateVelocity() {
        // Confirms processMovement accelerates (adds) rather than setting a target speed.
        state.setOnGround(true);
        move(true, false, false, false, false, false, 1f);
        move(true, false, false, false, false, false, 1f);
        move(true, false, false, false, false, false, 1f);

        assertEquals(-3f * MOVE_SPEED * DT, state.getVelocity().z, 1e-3f);
    }
}