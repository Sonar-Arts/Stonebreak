package com.stonebreak.mobs.chicken;

import org.joml.Vector3f;
import com.stonebreak.blocks.BlockType;

/**
 * Simple AI controller for chicken behavior.
 *
 * <p>Implements idle and wandering states (chickens do not graze), plus an
 * occasional wing-flap gesture played while standing idle. The wing-flap state
 * is transient: it holds for the duration of the {@code Wingflap} SBE clip and
 * then returns to idle.
 */
public class ChickenAI {
    private final Chicken chicken;

    private ChickenBehaviorState currentState;
    private float stateTimer;
    private float stateChangeTimer;

    // Behavior timing constants
    private static final float MIN_STATE_DURATION = 3.0f;
    private static final float MAX_STATE_DURATION = 8.0f;
    private static final float WANDER_DISTANCE = 8.0f;
    private static final float MOVEMENT_SPEED_MULTIPLIER = 0.8f;

    // Movement constants
    private static final float JUMP_COOLDOWN = 1.0f;
    private static final float OBSTACLE_LOOK_AHEAD = 0.15f; // probe gap past the body's leading edge

    // Obstacle hop: a stronger forward drive while airborne over an obstacle.
    // Airborne entities cannot auto-step, so a chicken whose footprint still
    // straddles a ledge edge as it descends gets pushed back off; the boost
    // ensures it clears the edge fully before coming down onto the block.
    private static final float HOP_SPEED = 2.2f;
    private static final float HOP_DURATION = 0.8f; // safety cap; cleared on landing
    private static final float ROTATION_SPEED = 180.0f; // degrees per second

    // Behavior weights (idle vs wander; remainder is wander)
    private static final float IDLE_CHANCE = 0.5f;

    // Wing-flap gesture
    private static final float WING_FLAP_DURATION = 1.2f;          // matches SB_Chicken.sbe Wingflap clip
    private static final float WING_FLAP_CHANCE_PER_SECOND = 0.15f; // while standing idle

    // Movement target for wandering
    private final Vector3f wanderTarget;
    private boolean hasWanderTarget;

    // Jump management
    private float jumpCooldownTimer;
    private float hopTimer; // >0 while mid-hop over an obstacle

    /**
     * Behavior states for the chicken AI. Mapped to SBE animation states by
     * {@code ChickenStateMapping}.
     */
    public enum ChickenBehaviorState {
        IDLE,       // Standing still
        WANDERING,  // Walking to random nearby locations
        WING_FLAP   // Transient: flapping wings while idle
    }

    /**
     * Creates a new AI controller for the specified chicken.
     */
    public ChickenAI(Chicken chicken) {
        this.chicken = chicken;
        this.currentState = ChickenBehaviorState.IDLE;
        this.stateTimer = 0.0f;
        this.stateChangeTimer = getRandomStateDuration();
        this.wanderTarget = new Vector3f();
        this.hasWanderTarget = false;
        this.jumpCooldownTimer = 0.0f;
        this.hopTimer = 0.0f;
    }

    /**
     * Updates the chicken's AI behavior.
     */
    public void update(float deltaTime) {
        if (!chicken.isAlive()) return;

        stateTimer += deltaTime;
        jumpCooldownTimer = Math.max(0.0f, jumpCooldownTimer - deltaTime);
        // The hop boost ends as soon as the chicken lands (or after a safety cap).
        if (chicken.isOnGround()) {
            hopTimer = 0.0f;
        } else {
            hopTimer = Math.max(0.0f, hopTimer - deltaTime);
        }

        if (currentState == ChickenBehaviorState.WING_FLAP) {
            // Hold the flap for the clip's length, then settle back to idle.
            if (stateTimer >= WING_FLAP_DURATION) {
                setState(ChickenBehaviorState.IDLE);
                stateChangeTimer = getRandomStateDuration();
            }
        } else {
            stateChangeTimer -= deltaTime;
            if (stateChangeTimer <= 0) {
                changeToRandomState();
                stateChangeTimer = getRandomStateDuration();
            }
            // Occasionally flap wings while standing idle.
            if (currentState == ChickenBehaviorState.IDLE
                    && Math.random() < WING_FLAP_CHANCE_PER_SECOND * deltaTime) {
                setState(ChickenBehaviorState.WING_FLAP);
            }
        }

        switch (currentState) {
            case IDLE, WING_FLAP -> handleIdleBehavior();
            case WANDERING -> handleWanderBehavior(deltaTime);
        }
    }

    /**
     * Handles idle behavior - chicken stands still.
     */
    private void handleIdleBehavior() {
        Vector3f velocity = chicken.getVelocity();
        velocity.x = 0;
        velocity.z = 0;
        chicken.setVelocity(velocity);
        chicken.startIdling();
    }

    /**
     * Handles wandering behavior - chicken walks to random locations.
     */
    private void handleWanderBehavior(float deltaTime) {
        if (!hasWanderTarget || chicken.distanceTo(wanderTarget) < 1.5f) {
            generateNewWanderTarget();
        }
        if (hasWanderTarget) {
            moveTowardTarget(deltaTime);
        }
    }

    /**
     * Generates a new random wander target.
     */
    private void generateNewWanderTarget() {
        Vector3f chickenPos = chicken.getPosition();

        for (int attempts = 0; attempts < 10; attempts++) {
            float angle = (float) (Math.random() * 2 * Math.PI);
            float distance = 3.0f + (float) (Math.random() * (WANDER_DISTANCE - 3.0f));

            float targetX = chickenPos.x + (float) Math.cos(angle) * distance;
            float targetZ = chickenPos.z + (float) Math.sin(angle) * distance;

            int blockX = (int) Math.floor(targetX);
            int blockZ = (int) Math.floor(targetZ);
            float centerX = blockX + 0.5f;
            float centerZ = blockZ + 0.5f;

            float groundY = findGroundLevel(centerX, centerZ, chickenPos.y);
            if (groundY != Float.NEGATIVE_INFINITY) {
                wanderTarget.set(centerX, groundY, centerZ);
                hasWanderTarget = true;
                return;
            }
        }

        hasWanderTarget = false;
    }

    /**
     * Finds the ground level at a given position.
     */
    private float findGroundLevel(float x, float z, float startY) {
        int blockX = (int) Math.floor(x);
        int blockZ = (int) Math.floor(z);
        int startBlockY = (int) Math.floor(startY);

        for (int y = startBlockY + 5; y >= startBlockY - 10; y--) {
            BlockType block = chicken.getWorld().getBlockAt(blockX, y, blockZ);
            BlockType blockAbove = chicken.getWorld().getBlockAt(blockX, y + 1, blockZ);

            if (block != null && block.isSolid()
                    && (blockAbove == null || !blockAbove.isSolid())) {
                return y + 1.0f;
            }
        }

        return Float.NEGATIVE_INFINITY;
    }

    /**
     * Moves the chicken toward the wander target.
     */
    private void moveTowardTarget(float deltaTime) {
        Vector3f chickenPos = chicken.getPosition();
        Vector3f direction = new Vector3f(wanderTarget).sub(chickenPos);
        direction.y = 0;

        if (direction.length() > 0.1f) {
            direction.normalize();

            // The SB_Chicken.sbe model faces +Z (unlike the cow, which faces -Z),
            // so no 180° offset is applied — the chicken faces its travel direction.
            float targetYaw = (float) Math.toDegrees(Math.atan2(direction.x, direction.z));
            updateRotation(targetYaw, deltaTime);

            if (chicken.isOnGround() && jumpCooldownTimer <= 0
                    && shouldJumpObstacle(direction)) {
                chicken.jump();
                jumpCooldownTimer = JUMP_COOLDOWN;
                hopTimer = HOP_DURATION;
            }

            // Drive forward — faster while mid-hop so the chicken clears the
            // obstacle's edge fully before descending onto the block.
            float speed = (hopTimer > 0.0f)
                    ? HOP_SPEED
                    : chicken.getMoveSpeed() * MOVEMENT_SPEED_MULTIPLIER;
            Vector3f velocity = chicken.getVelocity();
            velocity.x = direction.x * speed;
            velocity.z = direction.z * speed;
            chicken.setVelocity(velocity);
        }
    }

    /**
     * Decides whether the chicken should jump to clear an obstacle ahead.
     *
     * <p>Probes a single block column directly in the chicken's path, placed
     * just past its actual bounding-box leading edge (derived from the body's
     * width/length and the travel direction). This is precise — unlike a
     * whole-box collision query, which over-reports by up to a block from its
     * floor/ceil cell bounds and makes the chicken jump too early.
     *
     * <p>Returns true only when a solid block blocks the path at body level
     * but a one-block hop would clear it; taller walls are left for the wander
     * state to re-target around.
     */
    private boolean shouldJumpObstacle(Vector3f direction) {
        Vector3f pos = chicken.getPosition();

        // Distance from the chicken's centre to the leading edge of its
        // axis-aligned bounding box along the travel direction, plus a small gap.
        float reach = Math.abs(direction.x) * (chicken.getWidth() * 0.5f)
                + Math.abs(direction.z) * (chicken.getLength() * 0.5f)
                + OBSTACLE_LOOK_AHEAD;

        int blockX = (int) Math.floor(pos.x + direction.x * reach);
        int blockZ = (int) Math.floor(pos.z + direction.z * reach);
        int blockY = (int) Math.floor(pos.y);

        // Solid block at body level?
        BlockType ahead = chicken.getWorld().getBlockAt(blockX, blockY, blockZ);
        if (ahead == null || !ahead.isSolid()) {
            return false;
        }

        // Jump only if a one-block hop clears it (space for the chicken above).
        BlockType above1 = chicken.getWorld().getBlockAt(blockX, blockY + 1, blockZ);
        BlockType above2 = chicken.getWorld().getBlockAt(blockX, blockY + 2, blockZ);
        return (above1 == null || !above1.isSolid())
                && (above2 == null || !above2.isSolid());
    }

    /**
     * Updates the chicken's rotation smoothly.
     */
    private void updateRotation(float targetYaw, float deltaTime) {
        Vector3f currentRotation = chicken.getRotation();
        float currentYaw = currentRotation.y;

        float deltaYaw = targetYaw - currentYaw;
        while (deltaYaw > 180.0f) deltaYaw -= 360.0f;
        while (deltaYaw < -180.0f) deltaYaw += 360.0f;

        float maxRotation = ROTATION_SPEED * deltaTime;
        if (Math.abs(deltaYaw) > maxRotation) {
            deltaYaw = Math.signum(deltaYaw) * maxRotation;
        }

        chicken.setRotation(new Vector3f(currentRotation.x, currentYaw + deltaYaw, currentRotation.z));
    }

    /**
     * Changes to a random behavior state (idle or wandering).
     */
    private void changeToRandomState() {
        ChickenBehaviorState newState = (Math.random() < IDLE_CHANCE)
                ? ChickenBehaviorState.IDLE
                : ChickenBehaviorState.WANDERING;
        setState(newState);
    }

    /**
     * Sets the current behavior state.
     */
    public void setState(ChickenBehaviorState newState) {
        if (newState != currentState) {
            currentState = newState;
            stateTimer = 0.0f;
            if (newState == ChickenBehaviorState.WANDERING) {
                hasWanderTarget = false;
            }
        }
    }

    /**
     * Gets a random state duration between min and max.
     */
    private float getRandomStateDuration() {
        return MIN_STATE_DURATION + (float) (Math.random() * (MAX_STATE_DURATION - MIN_STATE_DURATION));
    }

    /**
     * Called when the chicken takes damage - resets to idle briefly.
     */
    public void onDamaged(float damage) {
        setState(ChickenBehaviorState.IDLE);
        stateChangeTimer = 2.0f;
    }

    // Getters
    public ChickenBehaviorState getCurrentState() { return currentState; }
    public float getStateTimer() { return stateTimer; }

    /**
     * Advances ONLY the state-timer clock, without running any AI decisions. Used on a client
     * network shadow (whose AI is otherwise frozen) so the one-shot Wingflap clip — which the
     * renderer samples from {@link #getStateTimer()} — plays through after a replicated state
     * change resets the timer via {@link #setState}.
     */
    public void advanceClientClock(float deltaTime) {
        stateTimer += deltaTime;
    }

    /**
     * Cleans up AI resources when the chicken is removed.
     */
    public void cleanup() {
        hasWanderTarget = false;
    }
}
