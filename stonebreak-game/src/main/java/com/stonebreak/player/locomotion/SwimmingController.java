package com.stonebreak.player.locomotion;

import com.stonebreak.blocks.BlockType;
import com.stonebreak.blocks.waterSystem.WaterFlowPhysics;
import com.stonebreak.blocks.waterSystem.WaterSubmersion;
import com.stonebreak.core.Game;
import com.stonebreak.player.state.PhysicsState;
import com.stonebreak.world.World;
import com.stonebreak.world.operations.WorldConfiguration;
import org.joml.Vector3f;

import static com.stonebreak.player.PlayerConstants.CAMERA_EYE_OFFSET;
import static com.stonebreak.player.PlayerConstants.PLAYER_HEIGHT;
import static com.stonebreak.player.PlayerConstants.PLAYER_WIDTH;
import static com.stonebreak.player.PlayerConstants.WATER_EXIT_ANTI_FLOAT_DURATION;
import static com.stonebreak.player.PlayerConstants.WATER_IDLE_BUOYANCY_ACCEL;
import static com.stonebreak.player.PlayerConstants.WATER_IDLE_BUOYANCY_DRIFT_SPEED;
import static com.stonebreak.player.PlayerConstants.WATER_SURFACE_BOB_AMPLITUDE;
import static com.stonebreak.player.PlayerConstants.WATER_SURFACE_BOB_FRACTION;
import static com.stonebreak.player.PlayerConstants.WATER_SURFACE_BOB_SPEED;
import static com.stonebreak.player.PlayerConstants.WATER_SWIM_DOWN_ACCEL;

/**
 * Tracks water-submersion state, applies buoyancy/flow forces, and enforces the
 * anti-floating window that prevents momentum from water exits launching the player
 * skyward. Exposes {@link #isInWater()} (eye-level, used by rendering overlays) and
 * {@link PhysicsState#isPhysicallyInWater()} (body-level, used by physics).
 */
public class SwimmingController {

    private final PhysicsState state;
    private World world;
    private float submersionFraction;
    private float currentFlowStrength;

    public SwimmingController(PhysicsState state, World world) {
        this.state = state;
        this.world = world;
    }

    public void setWorld(World world) {
        this.world = world;
    }

    /** Called once per frame at the start of update(), before physics integration. */
    public void updateWaterState() {
        boolean inWater = isPartiallyInWater();
        state.setPhysicallyInWater(inWater);
        submersionFraction = computeSubmersionFraction();

        boolean justExited = state.wasInWaterLastFrame() && !inWater;
        state.setJustExitedWaterThisFrame(justExited);
        state.setJustEnteredWaterThisFrame(!state.wasInWaterLastFrame() && inWater);

        if (justExited && state.getVelocity().y > 0) {
            state.getVelocity().y = 0.0f;
        }
        if (justExited) {
            state.setWaterExitTime(0.0f);
        }

        state.setWasInWaterLastFrame(inWater);

        if (!inWater && state.getWaterExitTime() < WATER_EXIT_ANTI_FLOAT_DURATION) {
            state.setWaterExitTime(state.getWaterExitTime() + Game.getDeltaTime());
        } else if (inWater) {
            state.setWaterExitTime(WATER_EXIT_ANTI_FLOAT_DURATION + 1.0f);
        }
    }

    public boolean isInWaterExitAntiFloatPeriod() {
        return state.getWaterExitTime() < WATER_EXIT_ANTI_FLOAT_DURATION;
    }

    /**
     * Pre-integration anti-floating enforcement (mirrors the in-update velocity damping
     * that ran in the original Player.update()).
     */
    public void applyAntiFloatingPreIntegration(boolean flying, float lastNormalJumpTime, float normalJumpGrace) {
        float currentTime = Game.getInstance().getTotalTimeElapsed();
        boolean withinGrace = (currentTime - lastNormalJumpTime) < normalJumpGrace;
        boolean antiFloatPeriod = isInWaterExitAntiFloatPeriod();
        Vector3f velocity = state.getVelocity();

        if (!flying && !state.isPhysicallyInWater() && !state.isOnGround() &&
                velocity.y > 0.1f && (!withinGrace || antiFloatPeriod)) {
            velocity.y *= antiFloatPeriod ? 0.6f : 0.75f;
            if (velocity.y > 0.8f) velocity.y = 0.8f;
        }
    }

    public void applyWaterFlow(boolean flying) {
        if (state.isPhysicallyInWater() && !flying) {
            Vector3f flow = WaterFlowPhysics.applyWaterFlowForce(world, state.getPosition(), state.getVelocity(),
                    Game.getDeltaTime(), PLAYER_WIDTH, PLAYER_HEIGHT);
            currentFlowStrength = flow.length();
        } else {
            currentFlowStrength = 0.0f;
        }
    }

    /**
     * Ctrl-held swim-down / idle buoyancy. Jump-held swim-up is handled separately by
     * {@link com.stonebreak.player.locomotion.JumpHandler} (which owns the jump key); this
     * only fires when jump is NOT held, so the two never fight over vertical velocity. Ctrl
     * is a dedicated sink key, independent of shift (sprint) — the two can be held together
     * to sprint-swim while sinking. With no vertical input, the player drifts gently toward
     * the surface rather than sinking or hanging perfectly still, plus a small
     * treading-water bob once shallow.
     */
    public void applyVerticalSwimControl(boolean jump, boolean ctrl, boolean flying) {
        if (flying || jump || !state.isPhysicallyInWater()) {
            return;
        }
        Vector3f velocity = state.getVelocity();
        float dt = Game.getDeltaTime();

        if (ctrl) {
            velocity.y -= WATER_SWIM_DOWN_ACCEL * dt;
            return;
        }

        if (velocity.y < WATER_IDLE_BUOYANCY_DRIFT_SPEED) {
            velocity.y = Math.min(WATER_IDLE_BUOYANCY_DRIFT_SPEED, velocity.y + WATER_IDLE_BUOYANCY_ACCEL * dt);
        }
        if (submersionFraction < WATER_SURFACE_BOB_FRACTION) {
            float bob = (float) Math.sin(Game.getInstance().getTotalTimeElapsed() * WATER_SURFACE_BOB_SPEED) * WATER_SURFACE_BOB_AMPLITUDE;
            velocity.y += bob * dt;
        }
    }

    /** Magnitude of the current-flow force last applied to the player, 0 when not in flowing water. */
    public float getCurrentFlowStrength() {
        return currentFlowStrength;
    }

    /** Fraction (0..1) of the player's height currently submerged, smoothed across the surface boundary. */
    public float getSubmersionFraction() {
        return submersionFraction;
    }

    /**
     * Shared with every mob via {@link WaterSubmersion}, so a player and a cow standing in the same
     * pond agree on where its surface is.
     */
    private float computeSubmersionFraction() {
        Vector3f p = state.getPosition();
        return WaterSubmersion.fractionAt(world, p.x, p.y, p.z, PLAYER_HEIGHT);
    }

    /** Eye-level water check — used by rendering overlays and public API. */
    public boolean isInWater() {
        Vector3f p = state.getPosition();
        int eyeX = (int) Math.floor(p.x);
        int eyeY = (int) Math.floor(p.y + CAMERA_EYE_OFFSET);
        int eyeZ = (int) Math.floor(p.z);
        return world.getBlockAt(eyeX, eyeY, eyeZ) == BlockType.WATER;
    }

    public boolean isPartiallyInWater() {
        Vector3f p = state.getPosition();
        float checkYBottom = p.y + 0.05f;
        float checkYTop = p.y + PLAYER_HEIGHT - 0.05f;
        float halfWidth = PLAYER_WIDTH / 2.0f;
        float edgeInset = 0.1f;

        Vector3f[] offsets = {
                new Vector3f(0, 0, 0),
                new Vector3f(0, 0, -halfWidth + edgeInset),
                new Vector3f(0, 0, halfWidth - edgeInset),
                new Vector3f(-halfWidth + edgeInset, 0, 0),
                new Vector3f(halfWidth - edgeInset, 0, 0),
        };

        for (float y = checkYBottom; y <= checkYTop; y += 0.2f) {
            int by = (int) Math.floor(y);
            if (by < 0 || by >= WorldConfiguration.WORLD_HEIGHT) continue;
            for (Vector3f off : offsets) {
                int bx = (int) Math.floor(p.x + off.x);
                int bz = (int) Math.floor(p.z + off.z);
                if (world.getBlockAt(bx, by, bz) == BlockType.WATER) return true;
            }
        }
        int feetX = (int) Math.floor(p.x);
        int feetY = (int) Math.floor(p.y + 0.1f);
        int feetZ = (int) Math.floor(p.z);
        return world.getBlockAt(feetX, feetY, feetZ) == BlockType.WATER;
    }

    public void reset() {
        state.setPhysicallyInWater(false);
        state.setWasInWaterLastFrame(false);
        state.setJustExitedWaterThisFrame(false);
        state.setJustEnteredWaterThisFrame(false);
        state.setWaterExitTime(0.0f);
        submersionFraction = 0.0f;
        currentFlowStrength = 0.0f;
    }
}
