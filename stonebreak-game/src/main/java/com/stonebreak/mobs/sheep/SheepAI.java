package com.stonebreak.mobs.sheep;

import org.joml.Vector3f;
import com.stonebreak.player.Player;
import com.stonebreak.core.Game;
import com.stonebreak.blocks.BlockType;
import java.util.ArrayList;
import java.util.List;

public class SheepAI {
    private final Sheep sheep;

    private SheepBehaviorState currentState;
    private float stateTimer;
    private float stateChangeTimer;

    private static final float MIN_STATE_DURATION = 2.5f;
    private static final float MAX_STATE_DURATION = 7.0f;
    private static final float WANDER_DISTANCE = 8.0f;
    private static final float MOVEMENT_SPEED_MULTIPLIER = 0.85f;

    private static final float JUMP_COOLDOWN = 1.0f;
    private static final float OBSTACLE_CHECK_DISTANCE = 1.0f;
    private static final float ROTATION_SPEED = 200.0f;

    private static final float IDLE_CHANCE = 0.35f;
    private static final float WANDER_CHANCE = 0.45f;

    private final Vector3f wanderTarget;
    private boolean hasWanderTarget;
    private float jumpCooldownTimer;

    private final List<Vector3f> pathPoints;
    private static final int MAX_PATH_POINTS = 20;

    public enum SheepBehaviorState {
        IDLE,
        WANDERING,
        GRAZING
    }

    public SheepAI(Sheep sheep) {
        this.sheep = sheep;
        this.currentState = SheepBehaviorState.IDLE;
        this.stateTimer = 0.0f;
        this.stateChangeTimer = getRandomStateDuration();
        this.wanderTarget = new Vector3f();
        this.hasWanderTarget = false;
        this.jumpCooldownTimer = 0.0f;
        this.pathPoints = new ArrayList<>();
    }

    public void update(float deltaTime) {
        if (!sheep.isAlive()) return;

        stateTimer += deltaTime;
        stateChangeTimer -= deltaTime;

        jumpCooldownTimer -= deltaTime;
        if (jumpCooldownTimer < 0) jumpCooldownTimer = 0;

        if (stateChangeTimer <= 0) {
            changeToRandomState();
            stateChangeTimer = getRandomStateDuration();
        }

        switch (currentState) {
            case IDLE -> handleIdleBehavior();
            case WANDERING -> handleWanderBehavior(deltaTime);
            case GRAZING -> handleGrazeBehavior();
        }

        updatePathTracking();
        checkForPlayerNearby();
    }

    private void handleIdleBehavior() {
        Vector3f velocity = sheep.getVelocity();
        velocity.x = 0;
        velocity.z = 0;
        sheep.setVelocity(velocity);
        sheep.startIdling();
        sheep.setGrazing(false);
    }

    private void handleWanderBehavior(float deltaTime) {
        sheep.setGrazing(false);
        if (!hasWanderTarget || sheep.distanceTo(wanderTarget) < 1.5f) {
            generateNewWanderTarget();
        }
        if (hasWanderTarget) {
            moveTowardTarget(deltaTime);
        }
    }

    private void handleGrazeBehavior() {
        Vector3f velocity = sheep.getVelocity();
        velocity.x = 0;
        velocity.z = 0;
        sheep.setVelocity(velocity);
        sheep.setGrazing(true);
    }

    private void generateNewWanderTarget() {
        Vector3f sheepPos = sheep.getPosition();
        for (int attempts = 0; attempts < 10; attempts++) {
            float angle = (float) (Math.random() * 2 * Math.PI);
            float distance = 3.0f + (float) (Math.random() * (WANDER_DISTANCE - 3.0f));
            float targetX = sheepPos.x + (float) Math.cos(angle) * distance;
            float targetZ = sheepPos.z + (float) Math.sin(angle) * distance;
            int blockX = (int) Math.floor(targetX);
            int blockZ = (int) Math.floor(targetZ);
            float centerX = blockX + 0.5f;
            float centerZ = blockZ + 0.5f;
            float groundY = findGroundLevel(centerX, centerZ, sheepPos.y);
            if (groundY != Float.NEGATIVE_INFINITY) {
                wanderTarget.set(centerX, groundY, centerZ);
                hasWanderTarget = true;
                return;
            }
        }
        hasWanderTarget = false;
    }

    private float findGroundLevel(float x, float z, float startY) {
        int blockX = (int) Math.floor(x);
        int blockZ = (int) Math.floor(z);
        int startBlockY = (int) Math.floor(startY);
        for (int y = startBlockY + 5; y >= startBlockY - 10; y--) {
            BlockType block = sheep.getWorld().getBlockAt(blockX, y, blockZ);
            BlockType blockAbove = sheep.getWorld().getBlockAt(blockX, y + 1, blockZ);
            if (block != null && block.isSolid() && (blockAbove == null || !blockAbove.isSolid())) {
                return y + 1.0f;
            }
        }
        return Float.NEGATIVE_INFINITY;
    }

    private void moveTowardTarget(float deltaTime) {
        Vector3f sheepPos = sheep.getPosition();
        Vector3f direction = new Vector3f(wanderTarget).sub(sheepPos);
        direction.y = 0;
        if (direction.length() > 0.1f) {
            direction.normalize();
            float targetYaw = (float) Math.toDegrees(Math.atan2(direction.x, direction.z)) + 180.0f;
            updateSheepRotation(targetYaw, deltaTime);
            if (checkForObstacleAhead(direction)) {
                if (sheep.isOnGround() && jumpCooldownTimer <= 0) {
                    sheep.jump();
                    jumpCooldownTimer = JUMP_COOLDOWN;
                }
            }
            Vector3f velocity = sheep.getVelocity();
            velocity.x = direction.x * sheep.getMoveSpeed() * MOVEMENT_SPEED_MULTIPLIER;
            velocity.z = direction.z * sheep.getMoveSpeed() * MOVEMENT_SPEED_MULTIPLIER;
            sheep.setVelocity(velocity);
        }
    }

    private boolean checkForObstacleAhead(Vector3f direction) {
        Vector3f sheepPos = sheep.getPosition();
        float checkX = sheepPos.x + direction.x * OBSTACLE_CHECK_DISTANCE;
        float checkZ = sheepPos.z + direction.z * OBSTACLE_CHECK_DISTANCE;
        int blockX = (int) Math.floor(checkX);
        int blockY = (int) Math.floor(sheepPos.y);
        int blockZ = (int) Math.floor(checkZ);
        BlockType footBlock = sheep.getWorld().getBlockAt(blockX, blockY, blockZ);
        if (footBlock != null && footBlock.isSolid()) {
            BlockType above1 = sheep.getWorld().getBlockAt(blockX, blockY + 1, blockZ);
            BlockType above2 = sheep.getWorld().getBlockAt(blockX, blockY + 2, blockZ);
            return (above1 == null || !above1.isSolid()) && (above2 == null || !above2.isSolid());
        }
        BlockType upperBlock = sheep.getWorld().getBlockAt(blockX, blockY + 1, blockZ);
        if (upperBlock != null && upperBlock.isSolid()) {
            BlockType above2 = sheep.getWorld().getBlockAt(blockX, blockY + 2, blockZ);
            BlockType above3 = sheep.getWorld().getBlockAt(blockX, blockY + 3, blockZ);
            return (above2 == null || !above2.isSolid()) && (above3 == null || !above3.isSolid());
        }
        return false;
    }

    private void updateSheepRotation(float targetYaw, float deltaTime) {
        Vector3f currentRotation = sheep.getRotation();
        float currentYaw = currentRotation.y;
        float deltaYaw = targetYaw - currentYaw;
        while (deltaYaw > 180.0f) deltaYaw -= 360.0f;
        while (deltaYaw < -180.0f) deltaYaw += 360.0f;
        float maxRotation = ROTATION_SPEED * deltaTime;
        if (Math.abs(deltaYaw) > maxRotation) {
            deltaYaw = Math.signum(deltaYaw) * maxRotation;
        }
        sheep.setRotation(new Vector3f(currentRotation.x, currentYaw + deltaYaw, currentRotation.z));
    }

    private void changeToRandomState() {
        float random = (float) Math.random();
        SheepBehaviorState newState;
        if (random < IDLE_CHANCE) {
            newState = SheepBehaviorState.IDLE;
        } else if (random < IDLE_CHANCE + WANDER_CHANCE) {
            newState = SheepBehaviorState.WANDERING;
        } else {
            newState = SheepBehaviorState.GRAZING;
        }
        if (newState == currentState && Math.random() < 0.5) {
            SheepBehaviorState[] states = SheepBehaviorState.values();
            do {
                newState = states[(int) (Math.random() * states.length)];
            } while (newState == currentState);
        }
        setState(newState);
    }

    public void setState(SheepBehaviorState newState) {
        if (newState != currentState) {
            currentState = newState;
            stateTimer = 0.0f;
            if (newState == SheepBehaviorState.WANDERING) {
                hasWanderTarget = false;
            }
        }
    }

    private float getRandomStateDuration() {
        return MIN_STATE_DURATION + (float) (Math.random() * (MAX_STATE_DURATION - MIN_STATE_DURATION));
    }

    private void checkForPlayerNearby() {
        Player player = Game.getPlayer();
        if (player != null) {
            sheep.distanceTo(player.getPosition());
        }
    }

    public void onDamaged(float damage) {
        setState(SheepBehaviorState.WANDERING);
        stateChangeTimer = 4.0f;
        Player player = Game.getPlayer();
        if (player != null) {
            Vector3f sheepPos = sheep.getPosition();
            Vector3f fleeDir = new Vector3f(sheepPos).sub(player.getPosition());
            fleeDir.y = 0;
            if (fleeDir.length() > 0.1f) {
                fleeDir.normalize();
                float targetX = sheepPos.x + fleeDir.x * 10.0f;
                float targetZ = sheepPos.z + fleeDir.z * 10.0f;
                float groundY = findGroundLevel(targetX, targetZ, sheepPos.y);
                if (groundY != Float.NEGATIVE_INFINITY) {
                    setWanderTarget(new Vector3f(targetX, groundY, targetZ));
                }
            }
        }
    }

    public SheepBehaviorState getCurrentState() { return currentState; }
    public float getStateTimer() { return stateTimer; }
    public boolean hasWanderTarget() { return hasWanderTarget; }

    public void setWanderTarget(Vector3f target) {
        this.wanderTarget.set(target);
        this.hasWanderTarget = true;
    }

    public void clearWanderTarget() {
        this.hasWanderTarget = false;
    }

    private void updatePathTracking() {
        if (com.stonebreak.core.Game.getDebugOverlay() == null ||
                !com.stonebreak.core.Game.getDebugOverlay().isVisible()) {
            if (!pathPoints.isEmpty()) pathPoints.clear();
            return;
        }
        Vector3f currentPos = sheep.getPosition();
        if (currentState == SheepBehaviorState.WANDERING || pathPoints.isEmpty()) {
            if (pathPoints.isEmpty() || pathPoints.get(pathPoints.size() - 1).distance(currentPos) > 0.5f) {
                pathPoints.add(new Vector3f(currentPos));
                if (pathPoints.size() > MAX_PATH_POINTS) pathPoints.remove(0);
            }
        } else if (currentState == SheepBehaviorState.IDLE && stateTimer > 3.0f) {
            pathPoints.clear();
        }
    }

    public List<Vector3f> getPathPoints() { return new ArrayList<>(pathPoints); }

    public void cleanup() {
        hasWanderTarget = false;
        pathPoints.clear();
    }
}
