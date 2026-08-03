package com.stonebreak.player.physics;

import static com.stonebreak.player.PlayerConstants.GRAVITY;
import static com.stonebreak.player.PlayerConstants.WATER_HORIZONTAL_DRAG;
import static com.stonebreak.player.PlayerConstants.WATER_VERTICAL_DRAG;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

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
 * Unit tests for {@link MovementController#applyGravity} and
 * {@link MovementController#applyDamping} — gravity (context-sensitive) and
 * friction / drag (exponential decay, frame-rate independent).
 */
class MovementControllerPhysicsTest {

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

    // ── Gravity ──────────────────────────────────────────────────────────────

    @Test
    void gravityAcceleratesDownwardInAir() {
        // onGround=false, flying=false, not in anti-float period (waterExitTime=10).
        movement.applyGravity();

        assertEquals(-GRAVITY * DT, state.getVelocity().y, 1e-4f);
    }

    @Test
    void noGravityWhileOnGround() {
        state.setOnGround(true);
        movement.applyGravity();

        assertEquals(0f, state.getVelocity().y, 1e-4f);
    }

    @Test
    void noGravityWhileFlying() {
        flight.setFlying(true);
        movement.applyGravity();

        assertEquals(0f, state.getVelocity().y, 1e-4f);
    }

    @Test
    void gravityAccumulatesOverFrames() {
        // 60 calls at dt=1/60 → one simulated second of free fall, ~-35 m/s.
        for (int i = 0; i < 60; i++) {
            movement.applyGravity();
        }

        assertEquals(-GRAVITY * DT * 60f, state.getVelocity().y, 1e-3f);
    }

    @Test
    void waterExitAntiFloatDoublesGravity() {
        // waterExitTime=0 means isInWaterExitAntiFloatPeriod() is true (0 < 0.5).
        state.setWaterExitTime(0f);
        movement.applyGravity();

        assertEquals(-GRAVITY * 2f * DT, state.getVelocity().y, 1e-4f);
    }

    @Test
    void waterExitAntiFloatClampsUpwardVelocity() {
        // This is what stops water-exit momentum from launching the player:
        // double gravity is subtracted, then any remaining upward velocity above
        // 0.1f is hard-zeroed.
        state.setWaterExitTime(0f);
        state.getVelocity().y = 5f;
        movement.applyGravity();

        assertEquals(0f, state.getVelocity().y, 1e-4f);
    }

    // ── Damping ──────────────────────────────────────────────────────────────

    @Test
    void groundFrictionUsesExpectedFactor() {
        // Ground friction = 5.0.
        state.setOnGround(true);
        state.getVelocity().set(10f, 0f, 10f);
        movement.applyDamping();

        float factor = (float) Math.exp(-5f * DT);
        assertEquals(10f * factor, state.getVelocity().x, 1e-4f);
        assertEquals(10f * factor, state.getVelocity().z, 1e-4f);
    }

    @Test
    void airFrictionIsWeakerThanGroundFriction() {
        // Air friction = 2.5 (weaker than ground's 5.0, so more speed is retained).
        state.getVelocity().set(10f, 0f, 10f);
        movement.applyDamping();

        float airFactor = (float) Math.exp(-2.5f * DT);
        assertEquals(10f * airFactor, state.getVelocity().x, 1e-4f);

        // Verify ordering: air retains strictly more speed than ground.
        float groundFactor = (float) Math.exp(-5f * DT);
        assertTrue(state.getVelocity().x > 10f * groundFactor,
                "air should retain more speed than ground");
    }

    @Test
    void waterDragIsStrongerThanGroundFriction() {
        // Water horizontal drag = 6.5 (stronger than ground's 5.0).
        state.setPhysicallyInWater(true);
        state.getVelocity().set(10f, 0f, 10f);
        movement.applyDamping();

        float waterFactor = (float) Math.exp(-WATER_HORIZONTAL_DRAG * DT);
        assertEquals(10f * waterFactor, state.getVelocity().x, 1e-4f);

        // Verify ordering: water retains strictly less speed than ground.
        float groundFactor = (float) Math.exp(-5f * DT);
        assertTrue(state.getVelocity().x < 10f * groundFactor,
                "water should retain less speed than ground");
    }

    @Test
    void flyingDampsAllThreeAxesEqually() {
        // Flying: damping = 8.0 on all three axes.
        flight.setFlying(true);
        state.getVelocity().set(10f, 10f, 10f);
        movement.applyDamping();

        float factor = (float) Math.exp(-8f * DT);
        assertEquals(10f * factor, state.getVelocity().x, 1e-4f);
        assertEquals(10f * factor, state.getVelocity().y, 1e-4f);
        assertEquals(10f * factor, state.getVelocity().z, 1e-4f);
    }

    @Test
    void risingIsDampedButFallingIsNot() {
        // Characterization: air dampens upward velocity (y > 0) but leaves downward
        // velocity untouched — falling has no air drag at all, so there is no
        // terminal velocity; fall speed grows without bound.
        // Rising: velocity.y = 10, air damping = 0.1.
        state.getVelocity().y = 10f;
        movement.applyDamping();
        assertEquals(10f * (float) Math.exp(-0.1f * DT), state.getVelocity().y, 1e-4f);

        // Falling: velocity.y = -10, should be exactly unchanged.
        state.getVelocity().y = -10f;
        movement.applyDamping();
        assertEquals(-10f, state.getVelocity().y, 1e-4f);
    }

    @Test
    void waterDampsVerticalVelocity() {
        // Water: vertical damping is applied regardless of direction.
        state.setPhysicallyInWater(true);
        state.getVelocity().y = 10f;
        movement.applyDamping();

        float factor = (float) Math.exp(-WATER_VERTICAL_DRAG * DT);
        assertEquals(10f * factor, state.getVelocity().y, 1e-4f);
    }

    @Test
    void dampingIsFrameRateIndependent() {
        // Exponential decay composes exactly: e^(-f*dt1) * e^(-f*dt2) = e^(-f*(dt1+dt2)),
        // so one step at dt=0.1 should equal ten steps at dt=0.01.
        // Single large step.
        Game.setDeltaTimeForTesting(0.1f);
        state.getVelocity().x = 10f;
        movement.applyDamping();
        float afterBigStep = state.getVelocity().x;

        // Ten small steps (fresh harness).
        PhysicsState s2 = new PhysicsState();
        s2.setWaterExitTime(10f);
        s2.getVelocity().x = 10f;
        Camera c2 = new Camera();
        CollisionHandler ch2 = new CollisionHandler(s2, null);
        FlightController fl2 = new FlightController(s2);
        SwimmingController sw2 = new SwimmingController(s2, null);
        JumpHandler jh2 = new JumpHandler(s2);
        HealthController hc2 = new HealthController();
        SpectatorController sp2 = new SpectatorController(s2, fl2, hc2);
        MovementController mc2 = new MovementController(s2, c2, ch2, fl2, sw2, jh2, sp2);

        Game.setDeltaTimeForTesting(0.01f);
        for (int i = 0; i < 10; i++) {
            mc2.applyDamping();
        }
        float afterSmallSteps = s2.getVelocity().x;

        // Restore.
        Game.setDeltaTimeForTesting(DT);

        assertEquals(afterBigStep, afterSmallSteps, 1e-3f,
                "damping should be frame-rate independent");
    }

    @Test
    void steadyStateWalkSpeedDependsOnFrameRate() {
        // Characterization test: acceleration is applied once per frame while friction
        // is exponential in dt, so terminal walking speed rises with frame rate —
        // a player at 144 FPS walks measurably faster than one at 30 FPS.
        // This test pins current behavior; it is not asserting that the behavior is correct.
        float speed30 = simulateSteadyStateWalkSpeed(1f / 30f);
        float speed60 = simulateSteadyStateWalkSpeed(1f / 60f);
        float speed144 = simulateSteadyStateWalkSpeed(1f / 144f);
        Game.setDeltaTimeForTesting(DT);  // restore

        assertTrue(speed144 > speed60,
                "144 FPS speed (" + speed144 + ") should exceed 60 FPS (" + speed60 + ")");
        assertTrue(speed60 > speed30,
                "60 FPS speed (" + speed60 + ") should exceed 30 FPS (" + speed30 + ")");

        float diff = Math.abs(speed144 - speed30);
        assertTrue(diff > 0.03f * speed60,
                "30 Hz and 144 Hz should differ by more than 3% of 60 Hz result: " +
                String.format("%.4f", diff) + " vs " + String.format("%.4f", 0.03f * speed60));
    }

    /**
     * Simulates walking forward for 2000 frames at the given delta time and returns
     * the final horizontal speed. Builds a fresh harness each call.
     */
    private float simulateSteadyStateWalkSpeed(float testDt) {
        Game.setDeltaTimeForTesting(testDt);

        PhysicsState s = new PhysicsState();
        s.setWaterExitTime(10f);
        s.setOnGround(true);

        Camera c = new Camera();
        CollisionHandler ch = new CollisionHandler(s, null);
        FlightController fl = new FlightController(s);
        SwimmingController sw = new SwimmingController(s, null);
        JumpHandler jh = new JumpHandler(s);
        HealthController hc = new HealthController();
        SpectatorController sp = new SpectatorController(s, fl, hc);

        MovementController mc = new MovementController(s, c, ch, fl, sw, jh, sp);

        for (int i = 0; i < 2000; i++) {
            mc.processMovement(true, false, false, false, false, false, false, false, 1f);
            mc.applyDamping();
        }

        float vx = s.getVelocity().x;
        float vz = s.getVelocity().z;
        return (float) Math.sqrt(vx * vx + vz * vz);
    }
}