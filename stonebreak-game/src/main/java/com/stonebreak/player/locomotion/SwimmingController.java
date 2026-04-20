package com.stonebreak.player.locomotion;

import com.stonebreak.blocks.BlockType;
import com.stonebreak.blocks.waterSystem.WaterFlowPhysics;
import com.stonebreak.core.Game;
import com.stonebreak.player.state.PhysicsState;
import com.stonebreak.world.World;
import com.stonebreak.world.operations.WorldConfiguration;
import org.joml.Vector3f;

import static com.stonebreak.player.PlayerConstants.CAMERA_EYE_OFFSET;
import static com.stonebreak.player.PlayerConstants.PLAYER_HEIGHT;
import static com.stonebreak.player.PlayerConstants.PLAYER_WIDTH;
import static com.stonebreak.player.PlayerConstants.WATER_EXIT_ANTI_FLOAT_DURATION;

/**
 * Tracks water-submersion state, applies buoyancy/flow forces, and enforces the
 * anti-floating window that prevents momentum from water exits launching the player
 * skyward. Exposes {@link #isInWater()} (eye-level, used by rendering overlays) and
 * {@link PhysicsState#isPhysicallyInWater()} (body-level, used by physics).
 */
public class SwimmingController {

    private final PhysicsState state;
    private World world;

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

        boolean justExited = state.wasInWaterLastFrame() && !inWater;
        state.setJustExitedWaterThisFrame(justExited);

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
            WaterFlowPhysics.applyWaterFlowForce(world, state.getPosition(), state.getVelocity(),
                    Game.getDeltaTime(), PLAYER_WIDTH, PLAYER_HEIGHT);
        }
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
        state.setWaterExitTime(0.0f);
    }
}
