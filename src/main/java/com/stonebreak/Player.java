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
    private static final float MOVE_SPEED = 40.0f; // Increased to better balance with gravity
    private static final float SWIM_SPEED = 20.0f; // Swimming is slower than walking
    private static final float JUMP_FORCE = 8.0f;
    private static final float GRAVITY = 20.0f;
    private static final float WATER_GRAVITY = 4.0f; // Reduced gravity in water
    private static final float RAY_CAST_DISTANCE = 5.0f;
    private static final float ATTACK_ANIMATION_DURATION = 0.3f; // Duration of the arm swing animation
      // Player state
    private Vector3f position;
    private Vector3f velocity;
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
    private Camera camera;
    
    // Reference to the world
    private World world;
    
    // Inventory
    private Inventory inventory;
    
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
    }
      /**
     * Updates the player's position and camera.
     */
    public void update() {
        // Check if player is in water
        headInWater = isInWater(); // Check for breathing
        physicallyInWater = isPartiallyInWater(); // Check for physics

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
    }
    
    /**
     * Handles collision on the X axis.
     */
    private void handleCollisionX() {
        float x1 = position.x - PLAYER_WIDTH / 2;
        float x2 = position.x + PLAYER_WIDTH / 2;
        float y1 = position.y;
        float y2 = position.y + PLAYER_HEIGHT;
        float z1 = position.z - PLAYER_WIDTH / 2;
        float z2 = position.z + PLAYER_WIDTH / 2;
        
        boolean collision = false;
        
        // Check all blocks in the player's bounding box
        for (int y = (int) Math.floor(y1); y < (int) Math.ceil(y2); y++) {
            for (int z = (int) Math.floor(z1); z < (int) Math.ceil(z2); z++) {
                // Check left side
                if (velocity.x < 0) {
                    if (world.getBlockAt((int) Math.floor(x1), y, z).isSolid()) {
                        position.x = (float) Math.floor(x1) + 1 + PLAYER_WIDTH / 2;
                        velocity.x = 0;
                        collision = true;
                        break;
                    }
                }
                
                // Check right side
                if (velocity.x > 0) {
                    if (world.getBlockAt((int) Math.floor(x2), y, z).isSolid()) {
                        position.x = (float) Math.floor(x2) - PLAYER_WIDTH / 2;
                        velocity.x = 0;
                        collision = true;
                        break;
                    }
                }
            }
            
            if (collision) {
                break;
            }
        }
    }
    
    /**
     * Handles collision on the Y axis.
     */
    private void handleCollisionY() {
        float x1 = position.x - PLAYER_WIDTH / 2;
        float x2 = position.x + PLAYER_WIDTH / 2;
        float y1 = position.y;
        float y2 = position.y + PLAYER_HEIGHT;
        float z1 = position.z - PLAYER_WIDTH / 2;
        float z2 = position.z + PLAYER_WIDTH / 2;
        
        boolean collision = false;
        
        // Check all blocks in the player's bounding box
        for (int x = (int) Math.floor(x1); x < (int) Math.ceil(x2); x++) {
            for (int z = (int) Math.floor(z1); z < (int) Math.ceil(z2); z++) {
                // Check bottom side
                if (velocity.y < 0) {
                    if (world.getBlockAt(x, (int) Math.floor(y1), z).isSolid()) {
                        position.y = (float) Math.floor(y1) + 1;
                        velocity.y = 0;
                        onGround = true;
                        collision = true;
                        break;
                    }
                }
                
                // Check top side
                if (velocity.y > 0) {
                    if (world.getBlockAt(x, (int) Math.floor(y2), z).isSolid()) {
                        position.y = (float) Math.floor(y2) - PLAYER_HEIGHT;
                        velocity.y = 0;
                        collision = true;
                        break;
                    }
                }
            }
            
            if (collision) {
                break;
            }
        }
        
        // Check if still on ground
        if (!collision && velocity.y < 0) {
            onGround = false;
        }
    }
    
    /**
     * Handles collision on the Z axis.
     */
    private void handleCollisionZ() {
        float x1 = position.x - PLAYER_WIDTH / 2;
        float x2 = position.x + PLAYER_WIDTH / 2;
        float y1 = position.y;
        float y2 = position.y + PLAYER_HEIGHT;
        float z1 = position.z - PLAYER_WIDTH / 2;
        float z2 = position.z + PLAYER_WIDTH / 2;
        
        boolean collision = false;
        
        // Check all blocks in the player's bounding box
        for (int x = (int) Math.floor(x1); x < (int) Math.ceil(x2); x++) {
            for (int y = (int) Math.floor(y1); y < (int) Math.ceil(y2); y++) {
                // Check front side
                if (velocity.z < 0) {
                    if (world.getBlockAt(x, y, (int) Math.floor(z1)).isSolid()) {
                        position.z = (float) Math.floor(z1) + 1 + PLAYER_WIDTH / 2;
                        velocity.z = 0;
                        collision = true;
                        break;
                    }
                }
                
                // Check back side
                if (velocity.z > 0) {
                    if (world.getBlockAt(x, y, (int) Math.floor(z2)).isSolid()) {
                        position.z = (float) Math.floor(z2) - PLAYER_WIDTH / 2;
                        velocity.z = 0;
                        collision = true;
                        break;
                    }
                }
            }
            
            if (collision) {
                break;
            }
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
            velocity.z += rightDirection.z * Game.getDeltaTime();
        }
        
        if (left) {
            velocity.x -= rightDirection.x * speed * Game.getDeltaTime();
            velocity.z -= rightDirection.z * Game.getDeltaTime();
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
     * Breaks the block the player is looking at.
     */
    public void breakBlock() {
        Vector3i blockPos = raycast();
        
        if (blockPos != null) {
            // Animation is now triggered by InputHandler
            // isAttacking = true;
            // attackAnimationTime = 0.0f;

            BlockType blockType = world.getBlockAt(blockPos.x, blockPos.y, blockPos.z);
            
            // Check if block is breakable
            if (blockType.isBreakable()) {
                // Remove block
                world.setBlockAt(blockPos.x, blockPos.y, blockPos.z, BlockType.AIR);
                
                // Add to inventory
                inventory.addItem(blockType.getId());
                

            }
        }
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
        
        // Try to place block against existing blocks
        Vector3i blockPos = raycast();
        
        if (blockPos != null) {
            // Find the face of the block
            Vector3i placePos = findPlacePosition(blockPos);
            
            if (placePos != null) {
                // Place block
                world.setBlockAt(placePos.x, placePos.y, placePos.z, BlockType.getById(selectedBlockTypeId));
                
                // Remove from inventory
                inventory.removeItem(selectedBlockTypeId);
  
            } else {
            }
        } else {

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
                minDist = dist;
            }
        }
        
        // Check if place position would intersect with player
        if (placePos != null) {
            // Check if the block at the place position is air
            BlockType blockAtPos = world.getBlockAt(placePos.x, placePos.y, placePos.z);
            if (blockAtPos != BlockType.AIR) {
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
     * Checks if a block at the specified position would intersect with the player.
     * Uses more precise AABB (Axis-Aligned Bounding Box) collision detection.
     * Allows placing blocks at feet level when not directly under the player.
     */
    private boolean intersectsWithPlayer(Vector3i blockPos) {
        // Player's bounding box (reduced significantly to allow placing blocks closer to the player)
        float x1 = position.x - (PLAYER_WIDTH / 2) * 0.8f;
        float x2 = position.x + (PLAYER_WIDTH / 2) * 0.8f;
        
        // Special handling for blocks at feet level
        // Check if the block is at the player's feet level
        boolean isAtFeetLevel = (blockPos.y == Math.floor(position.y));
        
        // Use different Y coordinates based on whether the block is at feet level
        float y1 = position.y + (isAtFeetLevel ? 0.2f : 0.0f); // Allow blocks at feet level
        float y2 = position.y + PLAYER_HEIGHT * 0.95f; // Reduced height slightly
        
        float z1 = position.z - (PLAYER_WIDTH / 2) * 0.8f;
        float z2 = position.z + (PLAYER_WIDTH / 2) * 0.8f;
        
        // Block's bounding box
        float blockX1 = blockPos.x;
        float blockX2 = blockPos.x + 1.0f;
        float blockY1 = blockPos.y;
        float blockY2 = blockPos.y + 1.0f;
        float blockZ1 = blockPos.z;
        float blockZ2 = blockPos.z + 1.0f;
        
        // Calculate the player's center position on the XZ plane
        float playerCenterX = position.x;
        float playerCenterZ = position.z;
        
        // Calculate the block's center position on the XZ plane
        float blockCenterX = blockPos.x + 0.5f;
        float blockCenterZ = blockPos.z + 0.5f;
        
        // For blocks at feet level, check if the block is directly under the player's center
        if (isAtFeetLevel) {
            // Distance from player center to block center on XZ plane
            float distX = Math.abs(playerCenterX - blockCenterX);
            float distZ = Math.abs(playerCenterZ - blockCenterZ);
            
            // If the block is not directly under the center of the player (with some margin),
            // allow placement even if it intersects slightly with the player's bounding box
            if (distX > 0.25f || distZ > 0.25f) {
                // Relaxed check for blocks at feet level that aren't directly under the player
                return (x1 < blockX2) && (x2 > blockX1) &&
                       (y1 < blockY2) && (y2 > blockY1) &&
                       (z1 < blockZ2) && (z2 > blockZ1) &&
                       (distX < 0.4f && distZ < 0.4f); // Only block if very close to center
            }
        }
        
        // Standard AABB collision check for other cases
        return (x1 < blockX2) && (x2 > blockX1) &&
               (y1 < blockY2) && (y2 > blockY1) &&
               (z1 < blockZ2) && (z2 > blockZ1);
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
     * @return The position of the hit block, or null if no block was hit.
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
        // Check multiple points inside the player's body
        for (float y = position.y; y <= position.y + PLAYER_HEIGHT; y += 0.5f) {
            int blockX = (int) Math.floor(position.x);
            int blockY = (int) Math.floor(y);
            int blockZ = (int) Math.floor(position.z);
            
            if (world.getBlockAt(blockX, blockY, blockZ) == BlockType.WATER) {
                return true;
            }
        }
        return false;    }
    
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
     */
    public float getAttackAnimationProgress() {
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
}
