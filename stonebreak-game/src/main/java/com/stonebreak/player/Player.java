package com.stonebreak.player;

import com.stonebreak.world.operations.WorldConfiguration;
import org.joml.Matrix4f;
import org.joml.Vector3f;
import org.joml.Vector3i;
import com.stonebreak.blocks.BlockType;
import com.stonebreak.items.Inventory;
import com.stonebreak.items.ItemStack;
import com.stonebreak.items.ItemType;

// Other package imports
import com.stonebreak.world.World;
import com.stonebreak.core.Game;
import com.stonebreak.blocks.Water;
import com.stonebreak.util.DropUtil;

/**
 * Represents the player in the game world.
 */
public class Player {      // Player settings
    private static final float PLAYER_HEIGHT = 1.8f;
    private static final float PLAYER_WIDTH = 0.6f;
    private static final float MOVE_SPEED = 31.0f; // Blocks per second (15% slower than 36.45, rounded)
    private static final float SWIM_SPEED = 11.5f; // Swimming is slower than walking (15% slower than 13.5, rounded)
    private static final float JUMP_FORCE = 8.5f; // Jump velocity
    private static final float GRAVITY = 40.0f; // Gravity acceleration
    private static final float WATER_GRAVITY = 12.0f; // Faster sinking in water
    private static final float WATER_BUOYANCY = 25.0f; // Faster swimming up when holding jump
    private static final float WATER_JUMP_BOOST = 5.0f; // Initial boost when starting to swim
    private static final float RAY_CAST_DISTANCE = 5.0f;
    private static final float ATTACK_ANIMATION_DURATION = 0.25f; // Duration of the arm swing animation (Minecraft-style timing)
      // Player state
    private final Vector3f position;
    private final Vector3f velocity;
    private boolean onGround;
    private boolean isAttacking;
    private boolean physicallyInWater; // True if any part of the player is in water (for physics)
    private boolean wasInWaterLastFrame; // Track water state changes
    private boolean justExitedWaterThisFrame; // Track immediate water exit for current frame
    private float waterExitTime = 0.0f; // Time since last water exit
    private static final float WATER_EXIT_ANTI_FLOAT_DURATION = 0.5f; // Duration to apply anti-floating after water exit (500ms)
    private float attackAnimationTime;
    
    
    // Camera
    private final Camera camera;
    
    // Reference to the world
    private final World world;

    // Block placement validation service
    private final IBlockPlacementService blockPlacementService;

    // Inventory
    private final Inventory inventory;
    
    // Block breaking system
    private Vector3i breakingBlock; // The block currently being broken
    private float breakingProgress; // Progress from 0.0 to 1.0
    private float breakingTime; // Time spent breaking the current block
    
    
    // Flight system
    private boolean flightEnabled = false; // Whether flight is enabled via command
    private boolean isFlying = false; // Whether player is currently flying
    private boolean wasJumpPressed = false; // Track previous frame's jump state
    private float lastSpaceKeyTime = 0.0f; // Time of last space key press for double-tap detection
    private float lastNormalJumpTime = 0.0f; // Time of last normal jump on land
    private static final float DOUBLE_TAP_WINDOW = 0.3f; // Time window for double-tap detection (300ms)
    private static final float NORMAL_JUMP_GRACE_PERIOD = 0.2f; // Grace period for normal jumps (200ms)
    private static final float FLY_SPEED = MOVE_SPEED * 2.5f; // Flight movement speed (250% of walking speed)
    private static final float FLY_VERTICAL_SPEED = 15.0f; // Vertical flight speed (ascent/descent)
    
    /**
     * Creates a new player in the specified world.
     */
    public Player(World world) {
        this.world = world;
        this.blockPlacementService = new BlockPlacementValidator(world);
        this.position = new Vector3f(0, 100, 0);
        this.velocity = new Vector3f(0, 0, 0);
        this.onGround = false;
        this.camera = new Camera();
        this.inventory = new Inventory();
        this.isAttacking = false;
        this.physicallyInWater = false;
        this.wasInWaterLastFrame = false;
        this.justExitedWaterThisFrame = false;
        this.attackAnimationTime = 0.0f;
        this.breakingBlock = null;
        this.breakingProgress = 0.0f;
        this.breakingTime = 0.0f;
        this.flightEnabled = false;
        this.isFlying = false;
        this.wasJumpPressed = false;
        this.lastSpaceKeyTime = 0.0f;
        this.lastNormalJumpTime = 0.0f;
    }
      /**
     * Updates the player's position and camera.
     */
    public void update() {
        // Check if player is in water
        physicallyInWater = isPartiallyInWater();
        
        // Store the water exit state before updating wasInWaterLastFrame
        justExitedWaterThisFrame = wasInWaterLastFrame && !physicallyInWater;
        
        // Check for water exit to cap velocity and stop floating
        if (justExitedWaterThisFrame) {
            // Player just exited water - stop upward velocity to prevent floating
            if (velocity.y > 0) {
                velocity.y = 0.0f;
            }
            waterExitTime = 0.0f;
        }
        
        wasInWaterLastFrame = physicallyInWater;
        
        // Update water exit timer
        if (!physicallyInWater && waterExitTime < WATER_EXIT_ANTI_FLOAT_DURATION) {
            waterExitTime += Game.getDeltaTime();
        } else if (physicallyInWater) {
            waterExitTime = WATER_EXIT_ANTI_FLOAT_DURATION + 1.0f;
        }
        
        // Anti-floating enforcement
        float currentTime = Game.getInstance().getTotalTimeElapsed();
        boolean withinNormalJumpGrace = (currentTime - lastNormalJumpTime) < NORMAL_JUMP_GRACE_PERIOD;
        boolean isInWaterExitAntiFloatPeriod = waterExitTime < WATER_EXIT_ANTI_FLOAT_DURATION;
        
        // Apply anti-floating if not in grace period or within water exit period
        if (!isFlying && !physicallyInWater && !onGround && velocity.y > 0.1f && (!withinNormalJumpGrace || isInWaterExitAntiFloatPeriod)) {
            if (isInWaterExitAntiFloatPeriod) {
                velocity.y *= 0.6f; // Aggressive reduction for water exits
            } else {
                velocity.y *= 0.75f; // Standard aggressive reduction
            }
            
            if (velocity.y > 0.8f) {
                velocity.y = 0.8f; // Hard cap for floating velocity
            }
        }

        // Apply gravity (unless flying)
        if (!onGround && !isFlying) {
            if (physicallyInWater) {
                velocity.y -= WATER_GRAVITY * Game.getDeltaTime();
            } else {
                if (isInWaterExitAntiFloatPeriod) {
                    velocity.y -= GRAVITY * 2.0f * Game.getDeltaTime(); // Double gravity after water exit
                } else {
                    velocity.y -= GRAVITY * Game.getDeltaTime();
                }
                
                // Safety check for water exit period
                if (velocity.y > 0.1f && !physicallyInWater && isInWaterExitAntiFloatPeriod) {
                    velocity.y = 0.0f;
                }
            }
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
        
        // Apply frame-rate independent dampening
        float deltaTime = Game.getDeltaTime();
        if (isFlying) {
            // Flight dampening - exponential decay
            float damping = 8.0f; // Higher damping for more responsive flight
            float dampingFactor = (float) Math.exp(-damping * deltaTime);
            velocity.x *= dampingFactor;
            velocity.y *= dampingFactor; // Apply dampening to Y-axis to stop floating
            velocity.z *= dampingFactor;
        } else {
            // Ground friction - exponential decay
            float friction = 5.0f; // Friction coefficient (reduced for better responsiveness)
            float frictionFactor = (float) Math.exp(-friction * deltaTime);
            velocity.x *= frictionFactor;
            velocity.z *= frictionFactor;
            
            // Apply Y dampening 
            if (physicallyInWater) {
                // Water resistance
                float waterDamping = 2.0f;
                float waterDampingFactor = (float) Math.exp(-waterDamping * deltaTime);
                velocity.y *= waterDampingFactor;
            } else {
                // Very light air resistance for natural jumping
                if (velocity.y > 0) {
                    float airDamping = 0.1f;
                    float airDampingFactor = (float) Math.exp(-airDamping * deltaTime);
                    velocity.y *= airDampingFactor;
                }
            }
        }
        
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
        Game.getSoundSystem().updatePlayerSounds(position, velocity, onGround, physicallyInWater);

        // Update audio listener position and orientation for proper 3D spatial audio
        // Only update if we have a valid world loaded
        if (Game.getWorld() != null) {
            Game.getSoundSystem().setListenerFromCamera(position, camera.getFront(), camera.getUp());
        }
    }
    
    /**
     * Handles collision on the X axis.
     */
    private void handleCollisionX() {
        float halfWidth = PLAYER_WIDTH / 2;
        // position.x is the tentative position after adding velocity.x * deltaTime

        float correctedPositionX = position.x;
        boolean collisionOccurred = false;
        float stepUpHeight = 0.0f; // Track the highest step-up needed

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

                    float blockHeight = getBlockCollisionHeight(blockToCheckX, yi, zi);
                    if (blockHeight > 0) {
                        // Check if player's Y position intersects with the block's collision height
                        float blockTop = yi + blockHeight;
                        
                        // Auto step-up for blocks up to 1 block high
                        // Calculate step-up height needed from player's current position
                        float stepUpNeeded = blockTop - position.y;
                        
                        // Calculate player's elevation above the base of their current position
                        float playerBaseY = (int)Math.floor(position.y);
                        float playerElevation = position.y - playerBaseY;
                        
                        // Allow step-up if:
                        // 1. Block is partial height (< 1.0), OR
                        // 2. Block is full height (= 1.0) AND player is elevated ≥ 0.5 blocks
                        boolean canStepUp = (blockHeight < 1.0f) || 
                                          (blockHeight == 1.0f && playerElevation >= 0.5f);
                        
                        if (stepUpNeeded > 0.0f && stepUpNeeded <= 1.0f && canStepUp &&
                            yi == (int)Math.floor(playerFootY) && position.y >= yi) {
                            // Player can automatically step up onto this block
                            stepUpHeight = Math.max(stepUpHeight, stepUpNeeded);
                        } else if (position.y < blockTop && position.y + PLAYER_HEIGHT > yi) {
                            // Normal collision - player needs to be positioned so its left edge is at blockToCheckX + 1
                            float potentialNewX = (float)(blockToCheckX + 1) + halfWidth;
                            if (!collisionOccurred || potentialNewX > correctedPositionX) { // Greater means less penetration
                                correctedPositionX = potentialNewX;
                            }
                            collisionOccurred = true;
                        }
                    }
                } else if (velocity.x > 0) { // Moving right
                    float playerRightEdge = position.x + halfWidth; // Tentative right edge
                    int blockToCheckX = (int)Math.floor(playerRightEdge);

                    float blockHeight = getBlockCollisionHeight(blockToCheckX, yi, zi);
                    if (blockHeight > 0) {
                        // Check if player's Y position intersects with the block's collision height
                        float blockTop = yi + blockHeight;
                        
                        // Auto step-up for blocks up to 1 block high
                        // Calculate step-up height needed from player's current position
                        float stepUpNeeded = blockTop - position.y;
                        
                        // Calculate player's elevation above the base of their current position
                        float playerBaseY = (int)Math.floor(position.y);
                        float playerElevation = position.y - playerBaseY;
                        
                        // Allow step-up if:
                        // 1. Block is partial height (< 1.0), OR
                        // 2. Block is full height (= 1.0) AND player is elevated ≥ 0.5 blocks
                        boolean canStepUp = (blockHeight < 1.0f) || 
                                          (blockHeight == 1.0f && playerElevation >= 0.5f);
                        
                        if (stepUpNeeded > 0.0f && stepUpNeeded <= 1.0f && canStepUp &&
                            yi == (int)Math.floor(playerFootY) && position.y >= yi) {
                            // Player can automatically step up onto this block
                            stepUpHeight = Math.max(stepUpHeight, stepUpNeeded);
                        } else if (position.y < blockTop && position.y + PLAYER_HEIGHT > yi) {
                            // Normal collision - player needs to be positioned so its right edge is at blockToCheckX
                            float potentialNewX = (float)blockToCheckX - halfWidth;
                            if (!collisionOccurred || potentialNewX < correctedPositionX) { // Lesser means less penetration
                                correctedPositionX = potentialNewX;
                            }
                            collisionOccurred = true;
                        }
                    }
                }
            }
        }

        // Apply step-up if needed and no regular collision occurred
        if (stepUpHeight > 0.0f && !collisionOccurred && onGround) {
            position.y += stepUpHeight + 0.01f; // Small extra height to ensure we're on top
        } else if (collisionOccurred) {
            position.x = correctedPositionX;
            // Sliding is allowed, so velocity.x is not zeroed here.
        }
    }

    /**
     * Gets the effective collision height of a block at the given position.
     * For most blocks this is 1.0 (full block) or 0.0 (no collision).
     * For snow blocks, this can be a partial height based on snow layers.
     */
    private float getBlockCollisionHeight(int x, int y, int z) {
        BlockType block = world.getBlockAt(x, y, z);
        if (block == BlockType.SNOW) {
            return world.getSnowHeight(x, y, z);
        }
        return block.getCollisionHeight();
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
                    float blockHeight = getBlockCollisionHeight(xi, blockToCheckY, zi);
                    if (blockHeight > 0) {
                        // Player's base needs to be at the top of the block
                        float blockTop = blockToCheckY + blockHeight;
                        if (position.y < blockTop) {
                            float potentialNewY = blockTop;
                            if (!collisionOccurred || potentialNewY > correctedPositionY) {
                                correctedPositionY = potentialNewY;
                            }
                            collisionOccurred = true;
                            downwardCollision = true;
                        }
                    }
                } else if (velocity.y > 0) { // Moving up
                    int blockToCheckY = (int)Math.floor(position.y + PLAYER_HEIGHT); // Block at player's head
                    float blockHeight = getBlockCollisionHeight(xi, blockToCheckY, zi);
                    if (blockHeight > 0) {
                        // Player's head needs to be at the bottom of the block, so base is blockToCheckY - PLAYER_HEIGHT
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
        float stepUpHeight = 0.0f; // Track the highest step-up needed

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

                    float blockHeight = getBlockCollisionHeight(xi, yi, blockToCheckZ);
                    if (blockHeight > 0) {
                        // Check if player's Y position intersects with the block's collision height
                        float blockTop = yi + blockHeight;
                        
                        // Auto step-up for blocks up to 1 block high
                        // Calculate step-up height needed from player's current position
                        float stepUpNeeded = blockTop - position.y;
                        
                        // Calculate player's elevation above the base of their current position
                        float playerBaseY = (int)Math.floor(position.y);
                        float playerElevation = position.y - playerBaseY;
                        
                        // Allow step-up if:
                        // 1. Block is partial height (< 1.0), OR
                        // 2. Block is full height (= 1.0) AND player is elevated ≥ 0.5 blocks
                        boolean canStepUp = (blockHeight < 1.0f) || 
                                          (blockHeight == 1.0f && playerElevation >= 0.5f);
                        
                        if (stepUpNeeded > 0.0f && stepUpNeeded <= 1.0f && canStepUp &&
                            yi == (int)Math.floor(playerFootY) && position.y >= yi) {
                            // Player can automatically step up onto this block
                            stepUpHeight = Math.max(stepUpHeight, stepUpNeeded);
                        } else if (position.y < blockTop && position.y + PLAYER_HEIGHT > yi) {
                            // Normal collision - player needs to be positioned so its front edge is at blockToCheckZ + 1
                            float potentialNewZ = (float)(blockToCheckZ + 1) + halfWidth;
                            if (!collisionOccurred || potentialNewZ > correctedPositionZ) {
                                correctedPositionZ = potentialNewZ;
                            }
                            collisionOccurred = true;
                        }
                    }
                } else if (velocity.z > 0) { // Moving towards +Z (back)
                    float playerBackEdge = position.z + halfWidth; // Tentative back edge
                    int blockToCheckZ = (int)Math.floor(playerBackEdge);

                    float blockHeight = getBlockCollisionHeight(xi, yi, blockToCheckZ);
                    if (blockHeight > 0) {
                        // Check if player's Y position intersects with the block's collision height
                        float blockTop = yi + blockHeight;
                        
                        // Auto step-up for blocks up to 1 block high
                        // Calculate step-up height needed from player's current position
                        float stepUpNeeded = blockTop - position.y;
                        
                        // Calculate player's elevation above the base of their current position
                        float playerBaseY = (int)Math.floor(position.y);
                        float playerElevation = position.y - playerBaseY;
                        
                        // Allow step-up if:
                        // 1. Block is partial height (< 1.0), OR
                        // 2. Block is full height (= 1.0) AND player is elevated ≥ 0.5 blocks
                        boolean canStepUp = (blockHeight < 1.0f) || 
                                          (blockHeight == 1.0f && playerElevation >= 0.5f);
                        
                        if (stepUpNeeded > 0.0f && stepUpNeeded <= 1.0f && canStepUp &&
                            yi == (int)Math.floor(playerFootY) && position.y >= yi) {
                            // Player can automatically step up onto this block
                            stepUpHeight = Math.max(stepUpHeight, stepUpNeeded);
                        } else if (position.y < blockTop && position.y + PLAYER_HEIGHT > yi) {
                            // Normal collision - player needs to be positioned so its back edge is at blockToCheckZ
                            float potentialNewZ = (float)blockToCheckZ - halfWidth;
                            if (!collisionOccurred || potentialNewZ < correctedPositionZ) {
                                correctedPositionZ = potentialNewZ;
                            }
                            collisionOccurred = true;
                        }
                    }
                }
            }
        }

        // Apply step-up if needed and no regular collision occurred
        if (stepUpHeight > 0.0f && !collisionOccurred && onGround) {
            position.y += stepUpHeight + 0.01f; // Small extra height to ensure we're on top
        } else if (collisionOccurred) {
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
                    int blockX = (int) Math.floor(checkX);
                    int blockY = (int) Math.floor(y);
                    int blockZ = (int) Math.floor(checkZ);
                    
                    float blockHeight = getBlockCollisionHeight(blockX, blockY, blockZ);
                    if (blockHeight > 0) {
                        // Check if the block's top surface is close enough to the player's feet
                        float blockTop = blockY + blockHeight;
                        if (blockTop >= y) { // Block surface is at or above the check point
                            blockBeneath = true;
                            break;
                        }
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
    public void processMovement(boolean forward, boolean backward, boolean left, boolean right, boolean jump, boolean shift) {
        // Calculate movement direction
        Vector3f frontDirection, rightDirection;
        float speed;
        
        if (isFlying) {
            // In flight mode, use XZ plane only (ignore pitch for forward/backward)
            frontDirection = new Vector3f(camera.getFront().x, 0, camera.getFront().z).normalize();
            rightDirection = new Vector3f(camera.getRight().x, 0, camera.getRight().z).normalize();
            // Double speed when holding shift (100% faster)
            speed = shift ? FLY_SPEED * 2.0f : FLY_SPEED;
        } else {
            // On ground, use XZ plane only
            frontDirection = new Vector3f(camera.getFront().x, 0, camera.getFront().z).normalize();
            rightDirection = new Vector3f(camera.getRight().x, 0, camera.getRight().z).normalize();
            speed = onGround ? (physicallyInWater ? SWIM_SPEED : MOVE_SPEED) : MOVE_SPEED * 0.85f;
        }
        
        // Apply movement forces
        if (forward) {
            velocity.x += frontDirection.x * speed * Game.getDeltaTime();
            velocity.z += frontDirection.z * speed * Game.getDeltaTime();
            // Y component is not affected by forward/backward in flight mode
        }
        
        if (backward) {
            velocity.x -= frontDirection.x * speed * Game.getDeltaTime();
            velocity.z -= frontDirection.z * speed * Game.getDeltaTime();
            // Y component is not affected by forward/backward in flight mode
        }
        
        if (right) {
            velocity.x += rightDirection.x * speed * Game.getDeltaTime();
            velocity.z += rightDirection.z * speed * Game.getDeltaTime();
            // Y component is not affected by strafe in flight mode
        }
        
        if (left) {
            velocity.x -= rightDirection.x * speed * Game.getDeltaTime();
            velocity.z -= rightDirection.z * speed * Game.getDeltaTime();
            // Y component is not affected by strafe in flight mode
        }
        
        // Handle jump/flight activation - only on initial press, not when held
        boolean jumpPressed = jump && !wasJumpPressed; // True only on the frame jump is first pressed
        
        if (jumpPressed) {
            if (flightEnabled) {
                // Check for double-tap to toggle flight
                float currentTime = Game.getInstance().getTotalTimeElapsed();
                if (currentTime - lastSpaceKeyTime <= DOUBLE_TAP_WINDOW) {
                    // Double-tap detected, toggle flight
                    isFlying = !isFlying;
                    if (isFlying) {
                        onGround = false;
                        velocity.y = 0; // Stop falling when entering flight
                    }
                    // Reset timer after successful double-tap to prevent accidental triggers
                    lastSpaceKeyTime = 0.0f;
                } else {
                    // First tap or tap outside window, record the time
                    lastSpaceKeyTime = currentTime;
                }
            }
            
            // Regular jump (only if not flying and on solid ground, not in water)
            if (!isFlying && onGround && !physicallyInWater) {
                velocity.y = JUMP_FORCE;
                onGround = false;
                lastNormalJumpTime = Game.getInstance().getTotalTimeElapsed(); // Record normal jump time
            }
            
            // Water jump: small initial boost when starting to swim
            if (!isFlying && physicallyInWater) {
                velocity.y += WATER_JUMP_BOOST;
            }
        }
        
        // Handle swimming up while jump key is held in water (but not flying)
        if (jump && !isFlying && physicallyInWater) {
            velocity.y += WATER_BUOYANCY * Game.getDeltaTime();
        }
        
        // Universal anti-floating safety check: prevent ANY upward movement when not in water and not on ground
        // This ensures floating is completely disabled outside of water, regardless of input
        float currentTime = Game.getInstance().getTotalTimeElapsed();
        boolean withinNormalJumpGrace = (currentTime - lastNormalJumpTime) < NORMAL_JUMP_GRACE_PERIOD;
        boolean isInWaterExitAntiFloatPeriod = waterExitTime < WATER_EXIT_ANTI_FLOAT_DURATION; // Check if within anti-float period
        
        // Apply anti-floating if: not in grace period OR within water exit anti-float period
        if (!isFlying && !physicallyInWater && !onGround && velocity.y > 0 && (!withinNormalJumpGrace || isInWaterExitAntiFloatPeriod)) {
            // Player has upward velocity while outside water - aggressively reduce it to prevent floating
            if (isInWaterExitAntiFloatPeriod) {
                // Extra aggressive for water exits
                velocity.y *= 0.5f; // Very aggressive reduction for water exits
            } else {
                // Standard aggressive reduction
                velocity.y *= 0.75f; // More aggressive reduction per frame to stop floating
            }
            
            // If holding jump key while floating outside water, apply additional penalty
            if (jump) {
                velocity.y *= 0.8f; // Stronger penalty for trying to use jump key while floating outside water
            }
            
            // Very low cap for floating velocity outside water
            if (velocity.y > 1.0f) {
                velocity.y = 1.0f; // Lower hard cap to prevent excessive floating
            }
        }
        
        // Handle jump key release - stop floating if jump key is released and player has upward velocity
        // Only apply this when player is in water or recently exited water to avoid affecting normal jumping
        boolean jumpReleased = !jump && wasJumpPressed; // True only on the frame jump is released
        if (jumpReleased && !isFlying && !onGround && velocity.y > 0 && (physicallyInWater || wasInWaterLastFrame)) {
            // Player released jump while floating upward from water - reduce upward velocity significantly
            velocity.y *= 0.3f; // Reduce upward velocity to 30% when jump is released
        }
        
        // Update previous jump state for next frame
        wasJumpPressed = jump;
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
            
            // Apply tool efficiency for pickaxe and axe
            float effectiveHardness = hardness;
            ItemStack selectedItem = inventory.getSelectedHotbarSlot();
            if (selectedItem != null && selectedItem.isTool()) {
                ItemType itemType = selectedItem.asItemType();
                if (itemType == ItemType.WOODEN_PICKAXE) {
                    if (blockType == BlockType.STONE || blockType == BlockType.SANDSTONE || blockType == BlockType.RED_SANDSTONE) {
                        effectiveHardness = hardness * 0.25f; // Mine 4x faster (treat as two hardness levels less)
                    }
                } else if (itemType == ItemType.WOODEN_AXE) {
                    // Check if this is a wooden block type
                    if (isWoodenBlock(blockType)) {
                        effectiveHardness = Math.max(0.1f, hardness - 2.0f); // Reduce hardness by 2.0, minimum 0.1f
                    }
                }
            }
            
            if (effectiveHardness > 0 && effectiveHardness != Float.POSITIVE_INFINITY) {
                breakingTime += Game.getDeltaTime();
                breakingProgress = Math.min(breakingTime / effectiveHardness, 1.0f);
                
                // Check if block is fully broken
                if (breakingProgress >= 1.0f) {
                    // If breaking a water block, remove it from water simulation
                    if (blockType == BlockType.WATER) {
                        Water.removeWaterSource(breakingBlock.x, breakingBlock.y, breakingBlock.z);
                    }
                    
                    // If breaking a snow block, remove snow layer data and spawn correct number of drops
                    if (blockType == BlockType.SNOW) {
                        int snowLayers = world.getSnowLayers(breakingBlock.x, breakingBlock.y, breakingBlock.z);
                        world.getSnowLayerManager().removeSnowLayers(breakingBlock.x, breakingBlock.y, breakingBlock.z);
                        
                    } else {
                    }
                    
                    // Create drops before breaking the block
                    Vector3f dropPosition = new Vector3f(breakingBlock.x + 0.5f, breakingBlock.y + 0.5f, breakingBlock.z + 0.5f);
                    DropUtil.handleBlockBroken(world, dropPosition, blockType);
                    
                    // Break the block
                    world.setBlockAt(breakingBlock.x, breakingBlock.y, breakingBlock.z, BlockType.AIR);

                    // Notify water system about block being broken
                    Water.onBlockBroken(breakingBlock.x, breakingBlock.y, breakingBlock.z);

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
                    // If breaking a snow block, remove snow layer data and spawn correct number of drops
                    if (blockType == BlockType.SNOW) {
                        int snowLayers = world.getSnowLayers(blockPos.x, blockPos.y, blockPos.z);
                        world.getSnowLayerManager().removeSnowLayers(blockPos.x, blockPos.y, blockPos.z);
                        
                    } else {
                    }
                    
                    // Create drops before breaking the block
                    Vector3f dropPosition = new Vector3f(blockPos.x + 0.5f, blockPos.y + 0.5f, blockPos.z + 0.5f);
                    DropUtil.handleBlockBroken(world, dropPosition, blockType);
                    
                    world.setBlockAt(blockPos.x, blockPos.y, blockPos.z, BlockType.AIR);

                    // Notify water system about block being broken
                    Water.onBlockBroken(blockPos.x, blockPos.y, blockPos.z);

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
     * Spawns a block drop at the specified location with some random velocity.
     */
    
    /**
     * Places the currently selected block from the inventory where the player is looking.
     * Blocks can only be placed adjacent to existing blocks.
     */
    public void placeBlock() {
        ItemStack selectedItem = inventory.getSelectedHotbarSlot();

        // Do nothing if no item is selected or if selected item is empty/air
        if (selectedItem == null || selectedItem.isEmpty()) {
            return;
        }

        // Check if this item can be placed as a block
        if (!selectedItem.isPlaceable()) {
            return; // Cannot place tools or other non-placeable items
        }

        // Get the BlockType from the placeable item
        BlockType selectedBlockType = selectedItem.asBlockType();
        if (selectedBlockType == null) {
            return; // Safety check - should not happen if isPlaceable() returned true
        }
        
        int selectedBlockTypeId = selectedBlockType.getId();
        
        Vector3i hitBlockPos = raycastForPlacement(); // This is the first solid (non-air, non-water) block hit by ray. Null if only air/water.
        Vector3i placePos;

        if (hitBlockPos != null) {
            BlockType hitBlockType = world.getBlockAt(hitBlockPos.x, hitBlockPos.y, hitBlockPos.z);
            BlockType blockTypeToPlace = BlockType.getById(selectedBlockTypeId);

            if (blockTypeToPlace == BlockType.SNOW && hitBlockType == BlockType.SNOW) {
                // For snow on snow, place directly into the snow's space (stack layers)
                placePos = new Vector3i(hitBlockPos);
            } else {
                // For solid blocks, try to place on an adjacent face
                placePos = findPlacePosition(hitBlockPos);
            }
        } else {
            // Player is aiming at AIR/WATER only (no solid blocks within reach).
            // Since water is treated like air for placement, blocks cannot be placed without adjacent solid blocks.
            placePos = null; // Cannot place blocks in mid-air or mid-water without solid block adjacency.
        }
        
        if (placePos != null) {
            // Now we have a candidate placePos, either adjacent to a solid block or directly in an air block.
            // Final check: the block AT placePos must be AIR or WATER and must not intersect player.
            BlockType blockAtPos = world.getBlockAt(placePos.x, placePos.y, placePos.z);
            BlockType blockTypeToPlace = BlockType.getById(selectedBlockTypeId);
            
            // Special handling for snow block stacking
            if (blockTypeToPlace == BlockType.SNOW && blockAtPos == BlockType.SNOW) {
                // Try to add a snow layer instead of placing a new block
                int currentLayers = world.getSnowLayers(placePos.x, placePos.y, placePos.z);
                if (currentLayers < 8) {
                    // Add a layer to existing snow
                    world.getSnowLayerManager().addSnowLayer(placePos.x, placePos.y, placePos.z);
                    inventory.removeItem(selectedItem.getItem(), 1);
                    // Trigger chunk rebuild since snow height changed
                    world.triggerChunkRebuild(placePos.x, placePos.y, placePos.z);
                    return;
                } else {
                    // Snow is already at max layers (8), try to place a new snow block above
                    Vector3i abovePos = new Vector3i(placePos.x, placePos.y + 1, placePos.z);
                    BlockType blockAbove = world.getBlockAt(abovePos.x, abovePos.y, abovePos.z);
                    if (blockAbove == BlockType.AIR) {
                        if (!blockPlacementService.wouldIntersectWithPlayer(abovePos, position, BlockType.SNOW, onGround)) {
                            // Place new snow block above
                            if (world.setBlockAt(abovePos.x, abovePos.y, abovePos.z, BlockType.SNOW)) {
                                world.getSnowLayerManager().setSnowLayers(abovePos.x, abovePos.y, abovePos.z, 1);
                                inventory.removeItem(selectedItem.getItem(), 1);

                                // Notify water system about block placement
                                Water.onBlockPlaced(abovePos.x, abovePos.y, abovePos.z);
                            }
                        }
                    }
                    return;
                }
            }
            
            if (blockAtPos == BlockType.AIR || blockAtPos == BlockType.WATER ||
                (blockTypeToPlace == BlockType.SNOW && blockAtPos == BlockType.SNOW)) {

                // Use the block placement validator to check placement validity
                BlockPlacementValidator.PlacementValidationResult validationResult =
                    blockPlacementService.validatePlacement(placePos, position, blockTypeToPlace, onGround);

                if (!validationResult.canPlace()) {
                    // Block placement is invalid - simply return without placing or moving player
                    return;
                }
                
                // Special handling for placing water on existing water
                if (blockAtPos == BlockType.WATER && selectedBlockType == BlockType.WATER) {
                    // Check if it's already a source
                    if (!Water.isWaterSource(placePos.x, placePos.y, placePos.z)) {
                        // Convert flow to source by triggering world block change
                        // This will call WaterSystem.onBlockChanged which uses cells.put() to force source
                        world.setBlockAt(placePos.x, placePos.y, placePos.z, BlockType.AIR); // Temp remove
                        world.setBlockAt(placePos.x, placePos.y, placePos.z, BlockType.WATER); // Replace as source
                        inventory.removeItem(selectedItem.getItem(), 1);
                    }
                    // If already a source, don't consume the bucket
                    return;
                }

                // If replacing water with non-water block, remove it from water simulation first
                if (blockAtPos == BlockType.WATER) {
                    Water.removeWaterSource(placePos.x, placePos.y, placePos.z);
                }

                // All checks passed, place the block.
                if (world.setBlockAt(placePos.x, placePos.y, placePos.z, selectedBlockType)) {
                    inventory.removeItem(selectedItem.getItem(), 1);

                    // If placing a water block, register it as a water source
                    if (selectedBlockType == BlockType.WATER) {
                        Water.addWaterSource(placePos.x, placePos.y, placePos.z);
                    }

                    // Notify water system about block placement (affects flow)
                    Water.onBlockPlaced(placePos.x, placePos.y, placePos.z);
                    
                    // If placing a snow block, initialize it with 1 layer
                    if (selectedBlockType == BlockType.SNOW) {
                        world.getSnowLayerManager().setSnowLayers(placePos.x, placePos.y, placePos.z, 1);
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
            if (blockPlacementService.wouldIntersectWithPlayer(placePos, position, null, onGround)) {
                return null;
            }
        }
        
        return placePos;
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
     * Treats water as transparent for block breaking/placing interactions.
     * @return The position of the first solid (non-air, non-water) block hit by ray, or null if no block was hit.
     */
    public Vector3i raycast() { // Changed to public
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

            // Skip air and water blocks - treat water as transparent for interactions
            if (blockType != BlockType.AIR && blockType != BlockType.WATER) {
                return new Vector3i(blockX, blockY, blockZ);
            }
        }

        return null;
    }

    /**
     * Performs a raycast specifically for block placement.
     * Treats water like air - looks for the first solid block to place adjacent to.
     * @return The position of the first solid (non-air, non-water) block hit by ray, or null if no solid block was hit.
     */
    private Vector3i raycastForPlacement() {
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

            // For placement, skip air and water blocks - only return solid blocks
            if (blockType != BlockType.AIR && blockType != BlockType.WATER) {
                return new Vector3i(blockX, blockY, blockZ);
            }
        }

        return null;
    }

    /**
     * Checks if the given block type is a wooden block that should be affected by axe efficiency.
     * @param blockType The block type to check
     * @return true if this is a wooden block
     */
    private boolean isWoodenBlock(BlockType blockType) {
        return blockType == BlockType.WOOD ||
               blockType == BlockType.WORKBENCH ||
               blockType == BlockType.PINE ||
               blockType == BlockType.ELM_WOOD_LOG ||
               blockType == BlockType.WOOD_PLANKS ||
               blockType == BlockType.PINE_WOOD_PLANKS ||
               blockType == BlockType.ELM_WOOD_PLANKS;
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
        // More precise water detection with tighter boundary checking
        float checkYBottom = position.y + 0.05f; // Slightly higher to avoid edge cases
        float checkYTop = position.y + PLAYER_HEIGHT - 0.05f; // Check just below the very top

        float halfWidth = PLAYER_WIDTH / 2.0f;
        float edgeInset = 0.1f; // Larger inset for more conservative boundary detection

        // Define a more conservative set of points to check
        Vector3f[] horizontalCheckOffsets = {
            new Vector3f(0, 0, 0),                                  // Center
            new Vector3f(0, 0, -halfWidth + edgeInset),             // Front edge (more conservative)
            new Vector3f(0, 0, halfWidth - edgeInset),              // Back edge (more conservative)
            new Vector3f(-halfWidth + edgeInset, 0, 0),             // Left edge (more conservative)
            new Vector3f(halfWidth - edgeInset, 0, 0),              // Right edge (more conservative)
        };

        // Use smaller vertical steps for more precise detection
        for (float yCurrent = checkYBottom; yCurrent <= checkYTop; yCurrent += 0.2f) {
            int blockY = (int) Math.floor(yCurrent);

            // Ensure y-check is within world bounds
            if (blockY < 0 || blockY >= WorldConfiguration.WORLD_HEIGHT) {
                continue;
            }

            for (Vector3f offset : horizontalCheckOffsets) {
                int blockX = (int) Math.floor(position.x + offset.x);
                int blockZ = (int) Math.floor(position.z + offset.z);

                if (world.getBlockAt(blockX, blockY, blockZ) == BlockType.WATER) {
                    return true;
                }
            }
        }
        
        // Additional check: ensure the player's feet aren't in water
        // This helps catch boundary cases where the player is just at the edge
        int feetBlockX = (int) Math.floor(position.x);
        int feetBlockY = (int) Math.floor(position.y + 0.1f); // Just above feet
        int feetBlockZ = (int) Math.floor(position.z);
        
        // The redundant if was removed, directly returning the result of the check.
        return world.getBlockAt(feetBlockX, feetBlockY, feetBlockZ) == BlockType.WATER;
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
     * Gets the player's velocity vector.
     */
    public Vector3f getVelocity() {
        return velocity;
    }

    public boolean isOnGround() {
        return onGround;
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
     * Handles flight ascent (space key while flying).
     */
    public void processFlightAscent(boolean shift) {
        if (isFlying) {
            float speed = shift ? FLY_VERTICAL_SPEED * 2.0f : FLY_VERTICAL_SPEED;
            velocity.y = speed; // Set velocity directly, don't accumulate
        }
    }
    
    /**
     * Handles flight descent (ctrl key while flying).
     */
    public void processFlightDescent(boolean shift) {
        if (isFlying) {
            float speed = shift ? FLY_VERTICAL_SPEED * 2.0f : FLY_VERTICAL_SPEED;
            velocity.y = -speed; // Set velocity directly, don't accumulate
        }
    }
    
    /**
     * Sets whether flight is enabled for this player.
     */
    public void setFlightEnabled(boolean enabled) {
        this.flightEnabled = enabled;
        if (!enabled) {
            // If flight is disabled, stop flying
            this.isFlying = false;
        }
    }
    
    /**
     * Returns whether flight is enabled for this player.
     */
    public boolean isFlightEnabled() {
        return flightEnabled;
    }
    
    /**
     * Returns whether the player is currently flying.
     */
    public boolean isFlying() {
        return isFlying;
    }

    /**
     * Attempts to drop an item as a block in front of the player.
     * @param itemToDrop The ItemStack to drop.
     * @return true if the item was successfully dropped, false otherwise.
     */
    public boolean attemptDropItemInFront(ItemStack itemToDrop) {
        if (itemToDrop == null || itemToDrop.isEmpty()) {
            return false;
        }

        // Check if the item can be placed as a block
        if (!itemToDrop.isPlaceable()) {
            return false; // Cannot drop non-placeable items (tools, etc.)
        }
        
        BlockType blockToPlace = itemToDrop.asBlockType();
        if (blockToPlace == null || blockToPlace == BlockType.AIR) {
            return false; // Cannot drop air
        }

        // Calculate a position 1 block in front of the player at foot level
        Vector3f front = camera.getFront();
        Vector3i dropPos = new Vector3i(
                (int) Math.floor(position.x + front.x),
                (int) Math.floor(position.y), // At foot level
                (int) Math.floor(position.z + front.z)
        );

        // Check if the target drop position is AIR and doesn't intersect with the player
        BlockType blockAtDropPos = world.getBlockAt(dropPos.x, dropPos.y, dropPos.z);
        if (blockAtDropPos == BlockType.AIR && !blockPlacementService.wouldIntersectWithPlayer(dropPos, position, blockToPlace, onGround)) {
            // Also ensure there is a solid block below the drop position to prevent floating items
            BlockType blockBelowDropPos = world.getBlockAt(dropPos.x, dropPos.y - 1, dropPos.z);
            if (!blockBelowDropPos.isSolid()) {
                // Try one block lower if initial position has no support
                dropPos.y = dropPos.y -1;
                blockAtDropPos = world.getBlockAt(dropPos.x, dropPos.y, dropPos.z);
                 if (blockAtDropPos != BlockType.AIR || blockPlacementService.wouldIntersectWithPlayer(dropPos, position, blockToPlace, onGround)) {
                    return false; // Lower position is also not suitable
                }
                 blockBelowDropPos = world.getBlockAt(dropPos.x, dropPos.y - 1, dropPos.z);
                 if (!blockBelowDropPos.isSolid()){
                    return false; // Still no solid ground below
                 }
            }

            if (world.setBlockAt(dropPos.x, dropPos.y, dropPos.z, blockToPlace)) {
                if (blockToPlace == BlockType.SNOW) {
                    world.getSnowLayerManager().setSnowLayers(dropPos.x, dropPos.y, dropPos.z, 1);
                }

                // Notify water system about block placement
                Water.onBlockPlaced(dropPos.x, dropPos.y, dropPos.z);
                System.out.println("Player dropped item " + blockToPlace.getName() + " at " + dropPos.x + ", " + dropPos.y + ", " + dropPos.z);
                return true;
            }
        }        return false;
    }
}
