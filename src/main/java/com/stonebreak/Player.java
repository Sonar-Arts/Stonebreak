package com.stonebreak;

import org.joml.Matrix4f;
import org.joml.Vector3f;
import org.joml.Vector3i;

/**
 * Represents the player in the game world.
 */
public class Player {      // Player settings
    private static final float PLAYER_HEIGHT = 1.8f;
    private static final float PLAYER_WIDTH = 0.6f;
    private static final float MOVE_SPEED = 35.0f; // Slightly reduced for better control
    private static final float SWIM_SPEED = 20.0f; // Swimming is slower than walking
    private static final float JUMP_FORCE = 8.0f;
    private static final float GRAVITY = 15.0f;
    private static final float WATER_GRAVITY = 4.0f; // Reduced gravity in water
    private static final float RAY_CAST_DISTANCE = 5.0f;
    private static final float ATTACK_ANIMATION_DURATION = 0.25f; // Duration of the arm swing animation (Minecraft-style timing)
      // Player state
    private final Vector3f position;
    private final Vector3f velocity;
    private boolean onGround;
    private boolean isAttacking;
    private boolean headInWater; // True if player's head is in water (for breathing)
    private boolean physicallyInWater; // True if any part of the player is in water (for physics)
    private float attackAnimationTime;
    
    // Breathing mechanics
    private static final float MAX_BREATH = 10.0f; // 10 seconds of air
    private float breathRemaining;
    private boolean isDrowning;
    
    // Camera
    private final Camera camera;
    
    // Reference to the world
    private final World world;
    
    // Inventory
    private final Inventory inventory;
    
    // Block breaking system
    private Vector3i breakingBlock; // The block currently being broken
    private float breakingProgress; // Progress from 0.0 to 1.0
    private float breakingTime; // Time spent breaking the current block
    
    // Walking sound system
    private float walkingSoundTimer; // Timer for walking sound intervals
    private static final float WALKING_SOUND_INTERVAL = 0.65f; // Play sound every 0.65 seconds while walking
    
    /**
     * Creates a new player in the specified world.
     */
    public Player(World world) {
        this.world = world;
        this.position = new Vector3f(0, 100, 0);
        this.velocity = new Vector3f(0, 0, 0);
        this.onGround = false;
        this.camera = new Camera();        this.inventory = new Inventory();
        this.isAttacking = false;
        this.headInWater = false;
        this.physicallyInWater = false;
        this.attackAnimationTime = 0.0f;
        this.breathRemaining = MAX_BREATH;
        this.isDrowning = false;
        this.breakingBlock = null;
        this.breakingProgress = 0.0f;
        this.breakingTime = 0.0f;
        this.walkingSoundTimer = 0.0f;
    }
      /**
     * Updates the player's position and camera.
     */
    public void update() {
        // Check if player is in water

        headInWater = isInWater(); // Check for breathing
        physicallyInWater = isPartiallyInWater(); // Check for physics

        // Water state changed - debug messages removed to prevent spam

        // Handle breathing underwater
        if (headInWater) {
            // Reduce breath while underwater
            breathRemaining -= Game.getDeltaTime();
            if (breathRemaining <= 0) {
                breathRemaining = 0;
                isDrowning = true;
                // Apply drowning damage (reduced health) here if health system is implemented
                // For now, just push the player upward to try to reach the surface
                if (velocity.y < 2.0f) {
                    velocity.y += 2.0f * Game.getDeltaTime();
                }
            }
        } else {
            // Recover breath when not underwater
            breathRemaining = Math.min(breathRemaining + Game.getDeltaTime() * 2.0f, MAX_BREATH);
            isDrowning = false;
        }

        // Apply gravity
        if (!onGround) {
            velocity.y -= (physicallyInWater ? WATER_GRAVITY : GRAVITY) * Game.getDeltaTime();
        }
        
        // Update position
        position.x += velocity.x * Game.getDeltaTime();
        handleCollisionX();
        
        position.y += velocity.y * Game.getDeltaTime();
        handleCollisionY();
        
        position.z += velocity.z * Game.getDeltaTime();
        handleCollisionZ();
        
        // Check if player is standing on solid ground
        checkGroundBeneath();
        
        // Dampen horizontal movement - reduced dampening for better control
        velocity.x *= 0.95f;
        velocity.z *= 0.95f;
        
        // Update camera position
        camera.setPosition(position.x, position.y + PLAYER_HEIGHT * 0.8f, position.z);

        // Update attack animation
        if (isAttacking) {
            attackAnimationTime += Game.getDeltaTime();
            if (attackAnimationTime >= ATTACK_ANIMATION_DURATION) {
                isAttacking = false;
                attackAnimationTime = 0.0f;
            }
        }
        
        // Update block breaking progress
        updateBlockBreaking();
        
        // Update walking sounds
        updateWalkingSounds();
    }
    
    /**
     * Handles collision on the X axis.
     */
    private void handleCollisionX() {
        float halfWidth = PLAYER_WIDTH / 2;
        // position.x is the tentative position after adding velocity.x * deltaTime

        float correctedPositionX = position.x;
        boolean collisionOccurred = false;

        float playerFootY = position.y;
        float playerHeadY = position.y + PLAYER_HEIGHT;
        float checkMinZ = position.z - halfWidth; // Player's Z extent
        float checkMaxZ = position.z + halfWidth; // Player's Z extent

        // Iterate over the Y and Z cells the player's volume spans
        for (int yi = (int)Math.floor(playerFootY); yi < (int)Math.ceil(playerHeadY); yi++) {
            for (int zi = (int)Math.floor(checkMinZ); zi < (int)Math.ceil(checkMaxZ); zi++) {
                if (velocity.x < 0) { // Moving left
                    float playerLeftEdge = position.x - halfWidth; // Tentative left edge
                    int blockToCheckX = (int)Math.floor(playerLeftEdge);

                    if (world.getBlockAt(blockToCheckX, yi, zi).isSolid()) {
                        // Player needs to be positioned so its left edge is at blockToCheckX + 1
                        float potentialNewX = (float)(blockToCheckX + 1) + halfWidth;
                        if (!collisionOccurred || potentialNewX > correctedPositionX) { // Greater means less penetration
                            correctedPositionX = potentialNewX;
                        }
                        collisionOccurred = true;
                    }
                } else if (velocity.x > 0) { // Moving right
                    float playerRightEdge = position.x + halfWidth; // Tentative right edge
                    int blockToCheckX = (int)Math.floor(playerRightEdge);

                    if (world.getBlockAt(blockToCheckX, yi, zi).isSolid()) {
                        // Player needs to be positioned so its right edge is at blockToCheckX
                        float potentialNewX = (float)blockToCheckX - halfWidth;
                        if (!collisionOccurred || potentialNewX < correctedPositionX) { // Lesser means less penetration
                            correctedPositionX = potentialNewX;
                        }
                        collisionOccurred = true;
                    }
                }
            }
        }

        if (collisionOccurred) {
            position.x = correctedPositionX;
            // Sliding is allowed, so velocity.x is not zeroed here.
        }
    }

    /**
     * Handles collision on the Y axis.
     */
    private void handleCollisionY() {
        float halfWidth = PLAYER_WIDTH / 2;
        // position.y is the tentative position after adding velocity.y * deltaTime

        float correctedPositionY = position.y;
        boolean collisionOccurred = false;
        boolean downwardCollision = false; // Specifically for onGround state

        float playerMinX = position.x - halfWidth;
        float playerMaxX = position.x + halfWidth;
        float playerMinZ = position.z - halfWidth;
        float playerMaxZ = position.z + halfWidth;

        // Iterate over the X and Z cells the player's base spans
        for (int xi = (int)Math.floor(playerMinX); xi < (int)Math.ceil(playerMaxX); xi++) {
            for (int zi = (int)Math.floor(playerMinZ); zi < (int)Math.ceil(playerMaxZ); zi++) {
                if (velocity.y < 0) { // Moving down
                    int blockToCheckY = (int)Math.floor(position.y); // Block at player's feet
                    if (world.getBlockAt(xi, blockToCheckY, zi).isSolid()) {
                        // Player's base needs to be at blockToCheckY + 1
                        float potentialNewY = (float)(blockToCheckY + 1);
                        if (!collisionOccurred || potentialNewY > correctedPositionY) {
                            correctedPositionY = potentialNewY;
                        }
                        collisionOccurred = true;
                        downwardCollision = true;
                    }
                } else if (velocity.y > 0) { // Moving up
                    int blockToCheckY = (int)Math.floor(position.y + PLAYER_HEIGHT); // Block at player's head
                    if (world.getBlockAt(xi, blockToCheckY, zi).isSolid()) {
                        // Player's head needs to be at blockToCheckY, so base is blockToCheckY - PLAYER_HEIGHT
                        float potentialNewY = (float)blockToCheckY - PLAYER_HEIGHT;
                        if (!collisionOccurred || potentialNewY < correctedPositionY) {
                            correctedPositionY = potentialNewY;
                        }
                        collisionOccurred = true;
                    }
                }
            }
        }

        if (collisionOccurred) {
            position.y = correctedPositionY;
            velocity.y = 0; // Stop vertical movement on collision
            if (downwardCollision) {
                onGround = true;
            }
        } else {
            // If no collision occurred while moving downwards, player is not on ground
            if (velocity.y < 0) {
                onGround = false;
            }
        }
    }

    /**
     * Handles collision on the Z axis.
     */
    private void handleCollisionZ() {
        float halfWidth = PLAYER_WIDTH / 2;
        // position.z is the tentative position after adding velocity.z * deltaTime

        float correctedPositionZ = position.z;
        boolean collisionOccurred = false;

        float playerFootY = position.y;
        float playerHeadY = position.y + PLAYER_HEIGHT;
        float checkMinX = position.x - halfWidth; // Player's X extent
        float checkMaxX = position.x + halfWidth; // Player's X extent

        // Iterate over the Y and X cells the player's volume spans
        for (int yi = (int)Math.floor(playerFootY); yi < (int)Math.ceil(playerHeadY); yi++) {
            for (int xi = (int)Math.floor(checkMinX); xi < (int)Math.ceil(checkMaxX); xi++) {
                if (velocity.z < 0) { // Moving towards -Z (front)
                    float playerFrontEdge = position.z - halfWidth; // Tentative front edge
                    int blockToCheckZ = (int)Math.floor(playerFrontEdge);

                    if (world.getBlockAt(xi, yi, blockToCheckZ).isSolid()) {
                        // Player needs to be positioned so its front edge is at blockToCheckZ + 1
                        float potentialNewZ = (float)(blockToCheckZ + 1) + halfWidth;
                        if (!collisionOccurred || potentialNewZ > correctedPositionZ) {
                            correctedPositionZ = potentialNewZ;
                        }
                        collisionOccurred = true;
                    }
                } else if (velocity.z > 0) { // Moving towards +Z (back)
                    float playerBackEdge = position.z + halfWidth; // Tentative back edge
                    int blockToCheckZ = (int)Math.floor(playerBackEdge);

                    if (world.getBlockAt(xi, yi, blockToCheckZ).isSolid()) {
                        // Player needs to be positioned so its back edge is at blockToCheckZ
                        float potentialNewZ = (float)blockToCheckZ - halfWidth;
                        if (!collisionOccurred || potentialNewZ < correctedPositionZ) {
                            correctedPositionZ = potentialNewZ;
                        }
                        collisionOccurred = true;
                    }
                }
            }
        }

        if (collisionOccurred) {
            position.z = correctedPositionZ;
            // Sliding is allowed, so velocity.z is not zeroed here.
        }
    }

    /**
     * Checks if there's a solid block beneath the player.
     * If not, sets onGround to false so the player will fall.
     */
    private void checkGroundBeneath() {
        if (onGround && velocity.y == 0) {
            // Only check if we're currently considered on ground and not moving vertically
            float x1 = position.x - PLAYER_WIDTH / 2;
            float x2 = position.x + PLAYER_WIDTH / 2;
            float y = position.y - 0.1f; // Check slightly below the player's feet
            float z1 = position.z - PLAYER_WIDTH / 2;
            float z2 = position.z + PLAYER_WIDTH / 2;
            
            boolean blockBeneath = false;
            
            // Check several points beneath the player to ensure there's ground
            for (float checkX = x1 + 0.1f; checkX <= x2 - 0.1f; checkX += 0.3f) {
                for (float checkZ = z1 + 0.1f; checkZ <= z2 - 0.1f; checkZ += 0.3f) {
                    if (world.getBlockAt((int) Math.floor(checkX), (int) Math.floor(y), (int) Math.floor(checkZ)).isSolid()) {
                        blockBeneath = true;
                        break;
                    }
                }
                if (blockBeneath) {
                    break;
                }
            }
            
            if (!blockBeneath) {
                onGround = false;
                // Add a small initial velocity to start falling
                if (velocity.y == 0) {
                    velocity.y = -0.1f;
                }
            }
        }
    }
    
    /**
     * Processes player movement based on input.
     */
    public void processMovement(boolean forward, boolean backward, boolean left, boolean right, boolean jump) {
        // Calculate movement direction in the XZ plane
        Vector3f frontDirection = new Vector3f(camera.getFront().x, 0, camera.getFront().z).normalize();
        Vector3f rightDirection = new Vector3f(camera.getRight().x, 0, camera.getRight().z).normalize();
        
        // Reduce speed when in air for better control, but not too much
        float speed = onGround ? (physicallyInWater ? SWIM_SPEED : MOVE_SPEED) : MOVE_SPEED * 0.85f;
        
        // Apply movement forces
        if (forward) {
            velocity.x += frontDirection.x * speed * Game.getDeltaTime();
            velocity.z += frontDirection.z * speed * Game.getDeltaTime();
        }
        
        if (backward) {
            velocity.x -= frontDirection.x * speed * Game.getDeltaTime();
            velocity.z -= frontDirection.z * speed * Game.getDeltaTime();
        }
        
        if (right) {
            velocity.x += rightDirection.x * speed * Game.getDeltaTime();
            velocity.z += rightDirection.z * speed * Game.getDeltaTime();
        }
        
        if (left) {
            velocity.x -= rightDirection.x * speed * Game.getDeltaTime();
            velocity.z -= rightDirection.z * speed * Game.getDeltaTime();
        }
        
        // Apply jump force
        if (jump && (onGround || physicallyInWater)) {
            velocity.y = JUMP_FORCE;
            onGround = false;
        }
    }
    
    /**
     * Processes mouse look based on mouse input.
     */
    public void processMouseLook(float xOffset, float yOffset) {
        camera.processMouseMovement(xOffset, yOffset);
    }
    
    /**
     * Updates the block breaking progress.
     */
    private void updateBlockBreaking() {
        if (breakingBlock != null) {
            Vector3i currentTarget = raycast();
            
            // Check if player is still looking at the same block
            if (currentTarget == null || !currentTarget.equals(breakingBlock)) {
                // Reset breaking progress if looking away
                resetBlockBreaking();
                return;
            }
            
            BlockType blockType = world.getBlockAt(breakingBlock.x, breakingBlock.y, breakingBlock.z);
            
            // Check if block still exists and is breakable
            if (!blockType.isBreakable() || blockType == BlockType.AIR) {
                resetBlockBreaking();
                return;
            }
            
            // Update breaking progress
            float hardness = blockType.getHardness();
            if (hardness > 0 && hardness != Float.POSITIVE_INFINITY) {
                breakingTime += Game.getDeltaTime();
                breakingProgress = Math.min(breakingTime / hardness, 1.0f);
                
                // Check if block is fully broken
                if (breakingProgress >= 1.0f) {
                    // If breaking a water block, remove it from water simulation
                    if (blockType == BlockType.WATER) {
                        WaterEffects waterEffects = Game.getWaterEffects();
                        if (waterEffects != null) {
                            waterEffects.removeWaterSource(breakingBlock.x, breakingBlock.y, breakingBlock.z);
                        }
                    }
                    
                    // Break the block
                    world.setBlockAt(breakingBlock.x, breakingBlock.y, breakingBlock.z, BlockType.AIR);
                    inventory.addItem(blockType.getId());
                    resetBlockBreaking();
                }
            }
        }
    }
    
    /**
     * Resets the block breaking progress.
     */
    private void resetBlockBreaking() {
        breakingBlock = null;
        breakingProgress = 0.0f;
        breakingTime = 0.0f;
    }
    
    /**
     * Starts breaking a block or continues breaking the current block.
     */
    public void startBreakingBlock() {
        Vector3i blockPos = raycast();
        
        if (blockPos != null) {
            BlockType blockType = world.getBlockAt(blockPos.x, blockPos.y, blockPos.z);
            
            // Check if block is breakable
            if (blockType.isBreakable() && blockType != BlockType.AIR) {
                // If we're targeting a new block, reset progress
                if (breakingBlock == null || !breakingBlock.equals(blockPos)) {
                    breakingBlock = new Vector3i(blockPos);
                    breakingProgress = 0.0f;
                    breakingTime = 0.0f;
                }
                
                // Start attack animation
                if (!isAttacking) {
                    isAttacking = true;
                    attackAnimationTime = 0.0f;
                }
                
                // For instant break blocks (hardness 0 or very low), break immediately
                float hardness = blockType.getHardness();
                if (hardness <= 0.0f) {
                    world.setBlockAt(blockPos.x, blockPos.y, blockPos.z, BlockType.AIR);
                    inventory.addItem(blockType.getId());
                    resetBlockBreaking();
                }
            }
        }
    }
    
    /**
     * Stops breaking blocks when the player releases the break button.
     */
    public void stopBreakingBlock() {
        resetBlockBreaking();
    }
    
    /**
     * Places the currently selected block from the inventory where the player is looking.
     * Blocks can only be placed adjacent to existing blocks.
     */
    public void placeBlock() {
        int selectedBlockTypeId = inventory.getSelectedBlockTypeId();

        // Do nothing if no block is selected or if selected block is AIR
        if (selectedBlockTypeId == BlockType.AIR.getId()) {
            return;
        }

        // Check if player has this block type in inventory first
        if (!inventory.hasItem(selectedBlockTypeId)) {
            // This might happen if the selected item was depleted by other means
            // or if selection logic is flawed. For now, just return.
            return;
        }
        
        Vector3i hitBlockPos = raycast(); // This is the first non-air block hit by ray. Null if only air.
        Vector3i placePos;

        if (hitBlockPos != null) {
            BlockType hitBlockType = world.getBlockAt(hitBlockPos.x, hitBlockPos.y, hitBlockPos.z);
            
            if (hitBlockType == BlockType.WATER) {
                // For water blocks, place directly into the water's space (replace the water)
                placePos = new Vector3i(hitBlockPos);
            } else {
                // For solid blocks, try to place on an adjacent face
                placePos = findPlacePosition(hitBlockPos);
            }
        } else {
            // Player is aiming at AIR (or beyond reach, but raycast handles distance).
            // Determine the air block cell player is targeting.
            // Use rayOrigin similar to raycast()
            Vector3f rayOrigin = new Vector3f(position.x, position.y + PLAYER_HEIGHT * 0.8f, position.z);
            // Target point is at RAY_CAST_DISTANCE or slightly less to be within the last cell.
            Vector3f targetPointInAir = new Vector3f(camera.getFront()).mul(RAY_CAST_DISTANCE - 0.1f).add(rayOrigin);
            
            placePos = new Vector3i(
                (int)Math.floor(targetPointInAir.x),
                (int)Math.floor(targetPointInAir.y),
                (int)Math.floor(targetPointInAir.z)
            );
            // Ensure this calculated position is actually AIR or WATER, otherwise invalid.
            BlockType blockAtTargetPos = world.getBlockAt(placePos.x, placePos.y, placePos.z);
            if (blockAtTargetPos != BlockType.AIR && blockAtTargetPos != BlockType.WATER) {
                placePos = null; // Don't place if calculated target isn't air or water.
            }
        }
        
        if (placePos != null) {
            // Now we have a candidate placePos, either adjacent to a solid block or directly in an air block.
            // Final check: the block AT placePos must be AIR or WATER and must not intersect player.
            BlockType blockAtPos = world.getBlockAt(placePos.x, placePos.y, placePos.z);
            if (blockAtPos == BlockType.AIR || blockAtPos == BlockType.WATER) {
                if (intersectsWithPlayer(placePos)) {
                    // System.out.println("DEBUG: Placement at " + placePos + " denied due to intersection with player.");
                    return; // Collision with player, abort.
                }
                
                // Check if the placement position has at least one adjacent solid block
                // Exception: Allow placement on water blocks (replacing water doesn't require adjacent solid blocks)
                if (blockAtPos != BlockType.WATER && !hasAdjacentSolidBlock(placePos)) {
                    return; // Cannot place blocks in mid-air
                }
                
                // If replacing water, remove it from water simulation first
                if (blockAtPos == BlockType.WATER) {
                    WaterEffects waterEffects = Game.getWaterEffects();
                    if (waterEffects != null) {
                        waterEffects.removeWaterSource(placePos.x, placePos.y, placePos.z);
                    }
                }
                
                // All checks passed, place the block.
                BlockType blockType = BlockType.getById(selectedBlockTypeId);
                if (world.setBlockAt(placePos.x, placePos.y, placePos.z, blockType)) {
                    inventory.removeItem(selectedBlockTypeId);
                    
                    // If placing a water block, register it as a water source
                    if (blockType == BlockType.WATER) {
                        WaterEffects waterEffects = Game.getWaterEffects();
                        if (waterEffects != null) {
                            waterEffects.addWaterSource(placePos.x, placePos.y, placePos.z);
                        }
                    }
                }
            } else {
                // This case should ideally not be hit if logic above is correct.
                // System.out.println("DEBUG: Target final placement spot " + placePos + " is not AIR.");
            }
        } else {
            // No valid position found to place block (e.g. aimed at sky, or findPlacePosition failed, or calculated air spot was not air).
            // System.out.println("DEBUG: No valid placePos found for placement.");
        }
    }
    
    /**
     * Finds where to place a new block relative to the hit block.
     */
    private Vector3i findPlacePosition(Vector3i hitBlock) {
        // Cast a ray to find which face of the block was hit
        Vector3f rayOrigin = new Vector3f(position.x, position.y + PLAYER_HEIGHT * 0.8f, position.z);
        Vector3f rayDirection = camera.getFront();
        
        // Check all 6 faces of the hit block
        float minDist = Float.MAX_VALUE;
        Vector3i placePos = null;
        
        // Top face (y+1)
        float dist = rayIntersectsPlane(rayOrigin, rayDirection, 
                new Vector3f(hitBlock.x + 0.5f, hitBlock.y + 1, hitBlock.z + 0.5f), 
                new Vector3f(0, 1, 0));
        if (dist > 0 && dist < minDist) {
            // Calculate hit point to validate it's within the face bounds
            Vector3f hitPoint = new Vector3f(rayDirection).mul(dist).add(rayOrigin);
            
            // Verify hit point is within the bounds of the block face
            float blockMinX = hitBlock.x;
            float blockMaxX = hitBlock.x + 1.0f;
            float blockMinZ = hitBlock.z;
            float blockMaxZ = hitBlock.z + 1.0f;
            
            if (hitPoint.x >= blockMinX && hitPoint.x <= blockMaxX &&
                hitPoint.z >= blockMinZ && hitPoint.z <= blockMaxZ) {
                placePos = new Vector3i(hitBlock.x, hitBlock.y + 1, hitBlock.z);
                minDist = dist;
            }
        }
        
        // Bottom face (y-1)
        dist = rayIntersectsPlane(rayOrigin, rayDirection, 
                new Vector3f(hitBlock.x + 0.5f, hitBlock.y, hitBlock.z + 0.5f), 
                new Vector3f(0, -1, 0));
        if (dist > 0 && dist < minDist) {
            // Calculate hit point to validate it's within the face bounds
            Vector3f hitPoint = new Vector3f(rayDirection).mul(dist).add(rayOrigin);
            
            // Verify hit point is within the bounds of the block face
            float blockMinX = hitBlock.x;
            float blockMaxX = hitBlock.x + 1.0f;
            float blockMinZ = hitBlock.z;
            float blockMaxZ = hitBlock.z + 1.0f;
            
            if (hitPoint.x >= blockMinX && hitPoint.x <= blockMaxX &&
                hitPoint.z >= blockMinZ && hitPoint.z <= blockMaxZ) {
                placePos = new Vector3i(hitBlock.x, hitBlock.y - 1, hitBlock.z);
                minDist = dist;
            }
        }
        
        // Front face (z+1)
        dist = rayIntersectsPlane(rayOrigin, rayDirection, 
                new Vector3f(hitBlock.x + 0.5f, hitBlock.y + 0.5f, hitBlock.z + 1), 
                new Vector3f(0, 0, 1));
        if (dist > 0 && dist < minDist) {
            // Calculate hit point to validate it's within the face bounds
            Vector3f hitPoint = new Vector3f(rayDirection).mul(dist).add(rayOrigin);
            
            // Verify hit point is within the bounds of the block face
            float blockMinX = hitBlock.x;
            float blockMaxX = hitBlock.x + 1.0f;
            float blockMinY = hitBlock.y;
            float blockMaxY = hitBlock.y + 1.0f;
            
            if (hitPoint.x >= blockMinX && hitPoint.x <= blockMaxX &&
                hitPoint.y >= blockMinY && hitPoint.y <= blockMaxY) {
                placePos = new Vector3i(hitBlock.x, hitBlock.y, hitBlock.z + 1);
                minDist = dist;
            }
        }
        
        // Back face (z-1)
        dist = rayIntersectsPlane(rayOrigin, rayDirection, 
                new Vector3f(hitBlock.x + 0.5f, hitBlock.y + 0.5f, hitBlock.z), 
                new Vector3f(0, 0, -1));
        if (dist > 0 && dist < minDist) {
            // Calculate hit point to validate it's within the face bounds
            Vector3f hitPoint = new Vector3f(rayDirection).mul(dist).add(rayOrigin);
            
            // Verify hit point is within the bounds of the block face
            float blockMinX = hitBlock.x;
            float blockMaxX = hitBlock.x + 1.0f;
            float blockMinY = hitBlock.y;
            float blockMaxY = hitBlock.y + 1.0f;
            
            if (hitPoint.x >= blockMinX && hitPoint.x <= blockMaxX &&
                hitPoint.y >= blockMinY && hitPoint.y <= blockMaxY) {
                placePos = new Vector3i(hitBlock.x, hitBlock.y, hitBlock.z - 1);
                minDist = dist;
            }
        }
        
        // Right face (x+1)
        dist = rayIntersectsPlane(rayOrigin, rayDirection, 
                new Vector3f(hitBlock.x + 1, hitBlock.y + 0.5f, hitBlock.z + 0.5f), 
                new Vector3f(1, 0, 0));
        if (dist > 0 && dist < minDist) {
            // Calculate hit point to validate it's within the face bounds
            Vector3f hitPoint = new Vector3f(rayDirection).mul(dist).add(rayOrigin);
            
            // Verify hit point is within the bounds of the block face
            float blockMinY = hitBlock.y;
            float blockMaxY = hitBlock.y + 1.0f;
            float blockMinZ = hitBlock.z;
            float blockMaxZ = hitBlock.z + 1.0f;
            
            if (hitPoint.y >= blockMinY && hitPoint.y <= blockMaxY &&
                hitPoint.z >= blockMinZ && hitPoint.z <= blockMaxZ) {
                placePos = new Vector3i(hitBlock.x + 1, hitBlock.y, hitBlock.z);
                minDist = dist;
            }
        }
        
        // Left face (x-1)
        dist = rayIntersectsPlane(rayOrigin, rayDirection, 
                new Vector3f(hitBlock.x, hitBlock.y + 0.5f, hitBlock.z + 0.5f), 
                new Vector3f(-1, 0, 0));
        if (dist > 0 && dist < minDist) {
            // Calculate hit point to validate it's within the face bounds
            Vector3f hitPoint = new Vector3f(rayDirection).mul(dist).add(rayOrigin);
            
            // Verify hit point is within the bounds of the block face
            float blockMinY = hitBlock.y;
            float blockMaxY = hitBlock.y + 1.0f;
            float blockMinZ = hitBlock.z;
            float blockMaxZ = hitBlock.z + 1.0f;
            
            if (hitPoint.y >= blockMinY && hitPoint.y <= blockMaxY &&
                hitPoint.z >= blockMinZ && hitPoint.z <= blockMaxZ) {
                placePos = new Vector3i(hitBlock.x - 1, hitBlock.y, hitBlock.z);
            }
        }
        
        // Check if place position would intersect with player
        if (placePos != null) {
            // Check if the block at the place position is air or water
            BlockType blockAtPos = world.getBlockAt(placePos.x, placePos.y, placePos.z);
            if (blockAtPos != BlockType.AIR && blockAtPos != BlockType.WATER) {
                return null;
            }
            
            // Check if block would intersect with player (last check to prioritize placement)
            if (intersectsWithPlayer(placePos)) {
                return null;
            }
        }
        
        return placePos;
    }
    
    /**
     * Checks if a block position has at least one adjacent solid block.
     * This prevents placing blocks in mid-air.
     */
    private boolean hasAdjacentSolidBlock(Vector3i blockPos) {
        // Check all 6 adjacent positions (up, down, north, south, east, west)
        Vector3i[] adjacentPositions = {
            new Vector3i(blockPos.x, blockPos.y + 1, blockPos.z), // Above
            new Vector3i(blockPos.x, blockPos.y - 1, blockPos.z), // Below
            new Vector3i(blockPos.x + 1, blockPos.y, blockPos.z), // East
            new Vector3i(blockPos.x - 1, blockPos.y, blockPos.z), // West
            new Vector3i(blockPos.x, blockPos.y, blockPos.z + 1), // North
            new Vector3i(blockPos.x, blockPos.y, blockPos.z - 1)  // South
        };
        
        for (Vector3i adjacentPos : adjacentPositions) {
            BlockType adjacentBlock = world.getBlockAt(adjacentPos.x, adjacentPos.y, adjacentPos.z);
            if (adjacentBlock.isSolid()) {
                return true;
            }
        }
        
        return false;
    }
    
    /**
     * Checks if a block at the specified position would intersect with the player.
     * Uses more precise AABB (Axis-Aligned Bounding Box) collision detection.
     * Uses the player's actual physics bounding box.
     */
    private boolean intersectsWithPlayer(Vector3i blockPos) {
        // Player's actual physics bounding box
        float halfPlayerWidth = PLAYER_WIDTH / 2;
        float pMinX = position.x - halfPlayerWidth;
        float pMaxX = position.x + halfPlayerWidth;
        float pMinY = position.y; // Player's feet
        float pMaxY = position.y + PLAYER_HEIGHT; // Player's head
        float pMinZ = position.z - halfPlayerWidth;
        float pMaxZ = position.z + halfPlayerWidth;
        
        // Block's bounding box
        float bMinX = blockPos.x;
        float bMaxX = blockPos.x + 1.0f;
        float bMinY = blockPos.y;
        float bMaxY = blockPos.y + 1.0f;
        float bMinZ = blockPos.z;
        float bMaxZ = blockPos.z + 1.0f;
        
        // Standard AABB collision check
        boolean collisionX = (pMinX < bMaxX) && (pMaxX > bMinX);
        boolean collisionY = (pMinY < bMaxY) && (pMaxY > bMinY); // True if Y volumes overlap
        boolean collisionZ = (pMinZ < bMaxZ) && (pMaxZ > bMinZ);
        
        // Return true if all three axes have overlapping volumes
        return collisionX && collisionY && collisionZ;
    }
    
    /**
     * Calculates the intersection of a ray with a plane.
     * @return The distance along the ray to the intersection, or -1 if no intersection.
     */
    private float rayIntersectsPlane(Vector3f rayOrigin, Vector3f rayDirection, Vector3f planePoint, Vector3f planeNormal) {
        float denominator = rayDirection.dot(planeNormal);
        
        // Check if ray and plane are nearly parallel
        // Using a slightly larger epsilon for better numerical stability
        if (Math.abs(denominator) < 0.001f) {
            return -1; // Ray is parallel to the plane
        }
        
        Vector3f toPlane = new Vector3f(planePoint).sub(rayOrigin);
        float t = toPlane.dot(planeNormal) / denominator;
        
        // Only return positive distance (in front of the ray)
        return t > 0.001f ? t : -1;
    }
    
    /**
     * Performs a raycast from the player's view to find the block they're looking at.
     * Uses a smaller step size for better precision.
     * @return The position of the first non-air block hit by ray, or null if no block was hit.
     */
    private Vector3i raycast() {
        Vector3f rayOrigin = new Vector3f(position.x, position.y + PLAYER_HEIGHT * 0.8f, position.z);
        Vector3f rayDirection = camera.getFront();
        
        // Use a smaller step size for more accurate raycasting
        float stepSize = 0.025f;
        
        // Perform ray marching
        for (float distance = 0; distance < RAY_CAST_DISTANCE; distance += stepSize) {
            Vector3f point = new Vector3f(rayDirection).mul(distance).add(rayOrigin);
            
            int blockX = (int) Math.floor(point.x);
            int blockY = (int) Math.floor(point.y);
            int blockZ = (int) Math.floor(point.z);
            
            BlockType blockType = world.getBlockAt(blockX, blockY, blockZ);
            
            if (blockType != BlockType.AIR) {
                return new Vector3i(blockX, blockY, blockZ);
            }
        }
        
        return null;
    }
      /**
     * Checks if the player is in water.
     * @return true if the player's head is in water
     */
    public boolean isInWater() {
        // Check block at eye level (head)
        int eyeBlockX = (int) Math.floor(position.x);
        int eyeBlockY = (int) Math.floor(position.y + PLAYER_HEIGHT * 0.8f);
        int eyeBlockZ = (int) Math.floor(position.z);
        
        return world.getBlockAt(eyeBlockX, eyeBlockY, eyeBlockZ) == BlockType.WATER;
    }
    
    /**
     * Checks if any part of the player is in water.
     * @return true if any part of the player is in water
     */
    private boolean isPartiallyInWater() {
        // Check slightly above feet up to player height to ensure full body check
        // Small epsilon added to checkYStart to avoid issues if position.y is exactly on a block boundary
        // and ensure the lowest part of the player is checked.
        float checkYBottom = position.y + 0.01f;
        float checkYTop = position.y + PLAYER_HEIGHT - 0.01f; // Check just below the very top

        float halfWidth = PLAYER_WIDTH / 2.0f;
        float nearEdgeOffset = 0.01f; // To check just inside the player's boundary

        // Define a set of points to check horizontally relative to player's center
        // Checking center, and points near the edges of the player's bounding box
        Vector3f[] horizontalCheckOffsets = {
            new Vector3f(0, 0, 0),                                  // Center
            new Vector3f(0, 0, -halfWidth + nearEdgeOffset),        // Front edge
            new Vector3f(0, 0, halfWidth - nearEdgeOffset),         // Back edge
            new Vector3f(-halfWidth + nearEdgeOffset, 0, 0),        // Left edge
            new Vector3f(halfWidth - nearEdgeOffset, 0, 0),         // Right edge
            // Optional: corner checks if needed for very specific interactions
            // new Vector3f(-halfWidth + nearEdgeOffset, 0, -halfWidth + nearEdgeOffset), // Front-Left
            // new Vector3f( halfWidth - nearEdgeOffset, 0, -halfWidth + nearEdgeOffset), // Front-Right
            // new Vector3f(-halfWidth + nearEdgeOffset, 0,  halfWidth - nearEdgeOffset), // Back-Left
            // new Vector3f( halfWidth - nearEdgeOffset, 0,  halfWidth - nearEdgeOffset)  // Back-Right
        };

        // Iterate vertically through the player's height at a reasonable step
        for (float yCurrent = checkYBottom; yCurrent <= checkYTop; yCurrent += 0.4f) { // Adjusted step for thoroughness
            int blockY = (int) Math.floor(yCurrent);

            // Ensure y-check is within world bounds
            if (blockY < 0 || blockY >= World.WORLD_HEIGHT) {
                continue;
            }

            for (Vector3f offset : horizontalCheckOffsets) {
                int blockX = (int) Math.floor(position.x + offset.x);
                int blockZ = (int) Math.floor(position.z + offset.z);

                if (world.getBlockAt(blockX, blockY, blockZ) == BlockType.WATER) {
                    // This debug line can be helpful to pinpoint which check triggers water detection
                    // System.out.println("DEBUG: isPartiallyInWater TRUE at [" + blockX + "," + blockY + "," + blockZ + "] for player Y: " + yCurrent + " offset: " + offset);
                    return true;
                }
            }
        }
        return false;
    }
    
    /**
     * Sets the player's position.
     */
    public void setPosition(float x, float y, float z) {
        position.set(x, y, z);
        camera.setPosition(x, y + PLAYER_HEIGHT * 0.8f, z);
    }
    
    /**
     * Gets the player's position.
     */
    public Vector3f getPosition() {
        return position;
    }
    
    /**
     * Gets the player's camera.
     */
    public Camera getCamera() {
        return camera;
    }
    
    /**
     * Gets the player's inventory.
     */
    public Inventory getInventory() {
        return inventory;
    }
      /**
     * Gets the view matrix from the camera.
     */
    public Matrix4f getViewMatrix() {
        return camera.getViewMatrix();
    }

    /**
     * Gets whether the player is currently attacking.
     */
    public boolean isAttacking() {
        return isAttacking;
    }

    /**
     * Gets the current progress of the attack animation (0.0 to 1.0).
     * Uses Minecraft-style easing curve for more natural animation.
     */
    public float getAttackAnimationProgress() {
        if (!isAttacking) {
            return 0.0f;
        }
        float progress = Math.min(attackAnimationTime / ATTACK_ANIMATION_DURATION, 1.0f);
        // Apply easing curve - fast start, slow end (like Minecraft)
        return (float) (1.0 - Math.pow(1.0 - progress, 2.0));
    }
    
    /**
     * Gets the raw attack animation progress without easing (0.0 to 1.0).
     */
    public float getRawAttackAnimationProgress() {
        if (!isAttacking) {
            return 0.0f;
        }
        return Math.min(attackAnimationTime / ATTACK_ANIMATION_DURATION, 1.0f);
    }

    /**
     * Gets the current breath percentage (0.0 to 1.0).
     */
    public float getBreathPercentage() {
        return breathRemaining / MAX_BREATH;
    }
    
    /**
     * Checks if the player is drowning.
     */
    public boolean isDrowning() {
        return isDrowning;
    }
    
    /**
     * Gets the player's velocity vector.
     */
    public Vector3f getVelocity() {
        return velocity;
    }

    /**
     * Initiates the attack animation.
     */
    public void startAttackAnimation() {
        if (!isAttacking) { // Prevent restarting animation if already attacking
            isAttacking = true;
            attackAnimationTime = 0.0f;
        }
    }
    
    /**
     * Gets the block currently being broken.
     */
    public Vector3i getBreakingBlock() {
        return breakingBlock;
    }
    
    /**
     * Gets the breaking progress (0.0 to 1.0).
     */
    public float getBreakingProgress() {
        return breakingProgress;
    }
    
    /**
     * Updates walking sound effects based on player movement.
     */
    private void updateWalkingSounds() {
        // Calculate horizontal movement speed
        float horizontalSpeed = (float) Math.sqrt(velocity.x * velocity.x + velocity.z * velocity.z);
        boolean isMoving = horizontalSpeed > 0.5f && onGround && !physicallyInWater; // Lower threshold for movement
        
        // Removed debug logging for cleaner output
        
        if (isMoving) {
            walkingSoundTimer += Game.getDeltaTime();
            
            // Play walking sound at intervals
            if (walkingSoundTimer >= WALKING_SOUND_INTERVAL) {
                // Check what block type the player is standing on
                int blockX = (int) Math.floor(position.x);
                int blockY = (int) Math.floor(position.y - 0.1f); // Slightly below feet to get ground block
                int blockZ = (int) Math.floor(position.z);
                
                BlockType groundBlock = world.getBlockAt(blockX, blockY, blockZ);
                
                // Play appropriate walking sound based on block type
                SoundSystem soundSystem = Game.getSoundSystem();
                if (soundSystem != null) {
                    if (groundBlock == BlockType.GRASS) {
                        soundSystem.playSoundWithVolume("grasswalk", 0.3f);
                    } else if (groundBlock == BlockType.SAND || groundBlock == BlockType.RED_SAND) {
                        soundSystem.playSoundWithVolume("sandwalk", 0.3f);
                    }
                }
                
                walkingSoundTimer = 0.0f;
            }
        } else {
            // Reset timer when not moving
            walkingSoundTimer = 0.0f;
        }
    }
}
