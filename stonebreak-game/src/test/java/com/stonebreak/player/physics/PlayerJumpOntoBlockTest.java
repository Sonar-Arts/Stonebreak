package com.stonebreak.player.physics;

import com.stonebreak.blocks.BlockType;
import com.stonebreak.core.Game;
import com.stonebreak.player.Camera;
import com.stonebreak.player.combat.HealthController;
import com.stonebreak.player.locomotion.FlightController;
import com.stonebreak.player.locomotion.JumpHandler;
import com.stonebreak.player.locomotion.SpectatorController;
import com.stonebreak.player.locomotion.SwimmingController;
import com.stonebreak.player.state.PhysicsState;
import com.stonebreak.world.TestWorld;
import com.stonebreak.world.operations.WorldConfiguration;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Regression: a player running at a one-block ledge and jumping ends up standing on it.
 *
 * <p>A jump (8.5 up against gravity 35, integrated per frame) peaks just under one block, so it
 * never clears a block by height alone: the player gets on top by MANTLING — once the feet are
 * within half a block of the ledge's top, the move climbs onto it (engine {@code AIR_STEP}). The
 * old per-axis resolver did this implicitly; the swept solver lost it until the flag existed.
 * Runs the real movement pipeline (input, jump, gravity, collision, damping) at several frame rates.
 */
class PlayerJumpOntoBlockTest {

    private static final int FLOOR = 10;
    private TestWorld world;

    @AfterEach
    void tearDown() {
        if (world != null) {
            world.cleanup();
        }
    }

    /** Floor at y = 10; a ledge {@code height} blocks tall from x = 8 on, across every z. */
    private void buildLedge(int height) {
        world = new TestWorld(new WorldConfiguration(8, 4), 1L, true);
        for (int x = 0; x < 16; x++) {
            for (int z = 0; z < 16; z++) {
                world.setBlockRaw(x, FLOOR, z, BlockType.STONE);
                for (int y = 1; y <= height && x >= 8; y++) {
                    world.setBlockRaw(x, FLOOR + y, z, BlockType.STONE);
                }
            }
        }
    }

    /** Runs forward (+X) and presses jump once at {@code jumpFrame}. Returns the final state. */
    private PhysicsState runAndJump(float dt, int frames, int jumpFrame) {
        Game.getInstance();
        Game.setDeltaTimeForTesting(dt);
        PhysicsState state = new PhysicsState();
        state.setWaterExitTime(10f);
        state.getPosition().set(5.5f, FLOOR + 1f, 8.5f);
        state.setOnGround(true);
        Camera camera = new Camera();
        camera.setYaw(0f);
        camera.setPitch(0f);
        assertTrue(camera.getFront().x > 0.99f, "camera faces +X");
        CollisionHandler collision = new CollisionHandler(state, world);
        FlightController flight = new FlightController(state);
        SwimmingController swimming = new SwimmingController(state, world);
        JumpHandler jump = new JumpHandler(state);
        SpectatorController spectator = new SpectatorController(state, flight, new HealthController());
        MovementController movement = new MovementController(state, camera, collision, flight, swimming, jump, spectator);
        for (int i = 0; i < frames; i++) {
            boolean jumping = i >= jumpFrame && i < jumpFrame + 3;
            movement.processMovement(true, false, false, false, jumping, false, false, false, 1f);
            movement.applyGravity();
            movement.integrateAndCollide();
            movement.applyDamping();
        }
        return state;
    }

    @Test
    void aRunningJumpLandsOnAOneBlockLedgeAtAnyFrameRate() {
        buildLedge(1);
        for (float fps : new float[] {30f, 60f, 144f}) {
            float dt = 1f / fps;
            // Jump about half a block before the ledge face (the body reaches it ~0.6 s in).
            PhysicsState s = runAndJump(dt, Math.round(2f * fps), Math.round(0.35f * fps));
            assertEquals(FLOOR + 2f, s.getPosition().y, 1e-3f, "standing on the ledge at " + fps + " fps");
            assertTrue(s.getPosition().x > 8.5f, "on top of it, not against it, at " + fps + " fps: x=" + s.getPosition().x);
            assertTrue(s.isOnGround());
        }
        Game.setDeltaTimeForTesting(1f / 60f);
    }

    @Test
    void aTwoBlockWallStillStopsAJump() {
        buildLedge(2);
        PhysicsState s = runAndJump(1f / 60f, 120, 20);
        assertEquals(8f - 0.3f, s.getPosition().x, 1e-3f);
        assertEquals(FLOOR + 1f, s.getPosition().y, 1e-3f);
        Game.setDeltaTimeForTesting(1f / 60f);
    }
}
