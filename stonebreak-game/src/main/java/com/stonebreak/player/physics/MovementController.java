package com.stonebreak.player.physics;

import com.stonebreak.core.Game;
import com.stonebreak.player.Camera;
import com.stonebreak.player.locomotion.FlightController;
import com.stonebreak.player.locomotion.JumpHandler;
import com.stonebreak.player.locomotion.SpectatorController;
import com.stonebreak.player.locomotion.SwimmingController;
import com.stonebreak.player.state.PhysicsState;
import org.joml.Vector3f;

import static com.stonebreak.player.PlayerConstants.FLY_SPEED;
import static com.stonebreak.player.PlayerConstants.GRAVITY;
import static com.stonebreak.player.PlayerConstants.MOVE_SPEED;
import static com.stonebreak.player.PlayerConstants.NORMAL_JUMP_GRACE_PERIOD;
import static com.stonebreak.player.PlayerConstants.SWIM_SPEED;
import static com.stonebreak.player.PlayerConstants.WATER_GRAVITY;

/**
 * Integrates player velocity into position with collision resolution, applies gravity
 * (context-sensitive based on swimming/flight/water-exit), applies friction and air/
 * water dampening, and processes WASD + jump input into horizontal velocity.
 */
public class MovementController {

    private final PhysicsState state;
    private final Camera camera;
    private final CollisionHandler collisionHandler;
    private final FlightController flight;
    private final SwimmingController swimming;
    private final JumpHandler jumpHandler;
    private final SpectatorController spectator;

    public MovementController(PhysicsState state, Camera camera, CollisionHandler collisionHandler,
                              FlightController flight, SwimmingController swimming, JumpHandler jumpHandler,
                              SpectatorController spectator) {
        this.state = state;
        this.camera = camera;
        this.collisionHandler = collisionHandler;
        this.flight = flight;
        this.swimming = swimming;
        this.jumpHandler = jumpHandler;
        this.spectator = spectator;
    }

    public void applyGravity() {
        if (state.isOnGround() || flight.isFlying()) return;
        Vector3f velocity = state.getVelocity();
        float dt = Game.getDeltaTime();
        if (state.isPhysicallyInWater()) {
            velocity.y -= WATER_GRAVITY * dt;
        } else if (swimming.isInWaterExitAntiFloatPeriod()) {
            velocity.y -= GRAVITY * 2.0f * dt;
            if (velocity.y > 0.1f) velocity.y = 0.0f;
        } else {
            velocity.y -= GRAVITY * dt;
        }
    }

    public void integrateAndCollide() {
        Vector3f position = state.getPosition();
        Vector3f velocity = state.getVelocity();
        float dt = Game.getDeltaTime();

        if (spectator.isActive()) {
            position.x += velocity.x * dt;
            position.y += velocity.y * dt;
            position.z += velocity.z * dt;
            return;
        }

        position.x += velocity.x * dt;
        collisionHandler.resolveX();

        position.y += velocity.y * dt;
        collisionHandler.resolveY();

        position.z += velocity.z * dt;
        collisionHandler.resolveZ();
    }

    public void applyDamping() {
        Vector3f velocity = state.getVelocity();
        float dt = Game.getDeltaTime();

        if (flight.isFlying()) {
            float damping = 8.0f;
            float factor = (float) Math.exp(-damping * dt);
            velocity.x *= factor;
            velocity.y *= factor;
            velocity.z *= factor;
            return;
        }

        float friction = 5.0f;
        float frictionFactor = (float) Math.exp(-friction * dt);
        velocity.x *= frictionFactor;
        velocity.z *= frictionFactor;

        if (state.isPhysicallyInWater()) {
            float waterDamping = 2.0f;
            float waterFactor = (float) Math.exp(-waterDamping * dt);
            velocity.y *= waterFactor;
        } else if (velocity.y > 0) {
            float airDamping = 0.1f;
            float airFactor = (float) Math.exp(-airDamping * dt);
            velocity.y *= airFactor;
        }
    }

    /**
     * Processes WASD + jump + sprint into velocity. Jump handling splits across
     * {@link FlightController} (toggle) and {@link JumpHandler} (ground/water jump).
     */
    public void processMovement(boolean forward, boolean backward, boolean left, boolean right,
                                boolean jump, boolean shift) {
        Vector3f velocity = state.getVelocity();
        Vector3f front = camera.getFront();
        Vector3f rightVec = camera.getRight();
        Vector3f frontDirection = new Vector3f(front.x, 0, front.z).normalize();
        Vector3f rightDirection = new Vector3f(rightVec.x, 0, rightVec.z).normalize();

        float speed;
        if (flight.isFlying()) {
            speed = shift ? FLY_SPEED * 2.0f : FLY_SPEED;
        } else {
            speed = state.isOnGround()
                    ? (state.isPhysicallyInWater() ? SWIM_SPEED : MOVE_SPEED)
                    : MOVE_SPEED * 0.85f;
        }

        float dt = Game.getDeltaTime();
        if (forward) {
            velocity.x += frontDirection.x * speed * dt;
            velocity.z += frontDirection.z * speed * dt;
        }
        if (backward) {
            velocity.x -= frontDirection.x * speed * dt;
            velocity.z -= frontDirection.z * speed * dt;
        }
        if (right) {
            velocity.x += rightDirection.x * speed * dt;
            velocity.z += rightDirection.z * speed * dt;
        }
        if (left) {
            velocity.x -= rightDirection.x * speed * dt;
            velocity.z -= rightDirection.z * speed * dt;
        }

        boolean jumpPressed = jump && !jumpHandler.wasJumpPressed();
        boolean flightToggled = jumpPressed && !spectator.isActive() && flight.handleJumpPressForToggle();
        jumpHandler.processJumpInput(jump, flightToggled, flight.isFlying());

        // In-input anti-floating safety (mirror of the original Player.processMovement tail).
        float currentTime = Game.getInstance().getTotalTimeElapsed();
        boolean withinGrace = (currentTime - jumpHandler.getLastNormalJumpTime()) < NORMAL_JUMP_GRACE_PERIOD;
        boolean antiFloatPeriod = swimming.isInWaterExitAntiFloatPeriod();
        if (!flight.isFlying() && !state.isPhysicallyInWater() && !state.isOnGround() &&
                velocity.y > 0 && (!withinGrace || antiFloatPeriod)) {
            velocity.y *= antiFloatPeriod ? 0.5f : 0.75f;
            if (jump) velocity.y *= 0.8f;
            if (velocity.y > 1.0f) velocity.y = 1.0f;
        }
    }
}
