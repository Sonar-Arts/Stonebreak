package com.stonebreak.player.physics;

import com.stonebreak.blocks.BlockType;
import com.stonebreak.player.state.PhysicsState;
import com.stonebreak.world.World;
import org.joml.Vector3f;

import static com.stonebreak.player.PlayerConstants.PLAYER_HEIGHT;
import static com.stonebreak.player.PlayerConstants.PLAYER_WIDTH;

/**
 * Axis-separated collision resolution with automatic step-up onto partial and
 * full-height blocks. Each axis is resolved independently after its delta is added
 * to position; velocity is zeroed on hit to prevent sticking. Snow blocks report a
 * layered collision height via {@link World#getSnowHeight(int, int, int)}.
 */
public class CollisionHandler {

    private final PhysicsState state;
    private World world;

    public CollisionHandler(PhysicsState state, World world) {
        this.state = state;
        this.world = world;
    }

    public void setWorld(World world) {
        this.world = world;
    }

    public void resolveX() {
        Vector3f position = state.getPosition();
        Vector3f velocity = state.getVelocity();
        float halfWidth = PLAYER_WIDTH / 2;
        float correctedPositionX = position.x;
        boolean collisionOccurred = false;
        float stepUpHeight = 0.0f;

        float playerFootY = position.y;
        float playerHeadY = position.y + PLAYER_HEIGHT;
        float checkMinZ = position.z - halfWidth;
        float checkMaxZ = position.z + halfWidth;

        for (int yi = (int) Math.floor(playerFootY); yi < (int) Math.ceil(playerHeadY); yi++) {
            for (int zi = (int) Math.floor(checkMinZ); zi < (int) Math.ceil(checkMaxZ); zi++) {
                if (velocity.x < 0) {
                    float playerLeftEdge = position.x - halfWidth;
                    int blockToCheckX = (int) Math.floor(playerLeftEdge);
                    float blockHeight = getBlockCollisionHeight(blockToCheckX, yi, zi);
                    if (blockHeight > 0) {
                        float blockTop = yi + blockHeight;
                        float stepUpNeeded = blockTop - position.y;
                        float playerBaseY = (int) Math.floor(position.y);
                        float playerElevation = position.y - playerBaseY;
                        boolean canStepUp = (blockHeight < 1.0f) ||
                                (blockHeight == 1.0f && playerElevation >= 0.5f);
                        if (stepUpNeeded > 0.0f && stepUpNeeded <= 1.0f && canStepUp &&
                                yi == (int) Math.floor(playerFootY) && position.y >= yi) {
                            stepUpHeight = Math.max(stepUpHeight, stepUpNeeded);
                        } else if (yi > (int) Math.floor(playerFootY) && blockHeight <= 0.125f && stepUpHeight > 0.0f) {
                            // Single-layer snow on upper row during step-up — skip wall collision
                        } else if (position.y < blockTop && position.y + PLAYER_HEIGHT > yi) {
                            float potentialNewX = (float) (blockToCheckX + 1) + halfWidth;
                            if (!collisionOccurred || potentialNewX > correctedPositionX) {
                                correctedPositionX = potentialNewX;
                            }
                            collisionOccurred = true;
                        }
                    }
                } else if (velocity.x > 0) {
                    float playerRightEdge = position.x + halfWidth;
                    int blockToCheckX = (int) Math.floor(playerRightEdge);
                    float blockHeight = getBlockCollisionHeight(blockToCheckX, yi, zi);
                    if (blockHeight > 0) {
                        float blockTop = yi + blockHeight;
                        float stepUpNeeded = blockTop - position.y;
                        float playerBaseY = (int) Math.floor(position.y);
                        float playerElevation = position.y - playerBaseY;
                        boolean canStepUp = (blockHeight < 1.0f) ||
                                (blockHeight == 1.0f && playerElevation >= 0.5f);
                        if (stepUpNeeded > 0.0f && stepUpNeeded <= 1.0f && canStepUp &&
                                yi == (int) Math.floor(playerFootY) && position.y >= yi) {
                            stepUpHeight = Math.max(stepUpHeight, stepUpNeeded);
                        } else if (yi > (int) Math.floor(playerFootY) && blockHeight <= 0.125f && stepUpHeight > 0.0f) {
                            // Single-layer snow on upper row during step-up — skip wall collision
                        } else if (position.y < blockTop && position.y + PLAYER_HEIGHT > yi) {
                            float potentialNewX = (float) blockToCheckX - halfWidth;
                            if (!collisionOccurred || potentialNewX < correctedPositionX) {
                                correctedPositionX = potentialNewX;
                            }
                            collisionOccurred = true;
                        }
                    }
                }
            }
        }

        if (stepUpHeight > 0.0f && !collisionOccurred && state.isOnGround()) {
            position.y += stepUpHeight + 0.01f;
        } else if (collisionOccurred) {
            float originalX = position.x;
            position.x = correctedPositionX;
            if (velocity.x > 0 && correctedPositionX < originalX) velocity.x = 0;
            else if (velocity.x < 0 && correctedPositionX > originalX) velocity.x = 0;
        }
    }

    public void resolveY() {
        Vector3f position = state.getPosition();
        Vector3f velocity = state.getVelocity();
        float halfWidth = PLAYER_WIDTH / 2;
        float correctedPositionY = position.y;
        boolean collisionOccurred = false;
        boolean downwardCollision = false;

        float playerMinX = position.x - halfWidth;
        float playerMaxX = position.x + halfWidth;
        float playerMinZ = position.z - halfWidth;
        float playerMaxZ = position.z + halfWidth;

        for (int xi = (int) Math.floor(playerMinX); xi < (int) Math.ceil(playerMaxX); xi++) {
            for (int zi = (int) Math.floor(playerMinZ); zi < (int) Math.ceil(playerMaxZ); zi++) {
                if (velocity.y < 0) {
                    int blockToCheckY = (int) Math.floor(position.y);
                    float blockHeight = getBlockCollisionHeight(xi, blockToCheckY, zi);
                    if (blockHeight > 0) {
                        float blockTop = blockToCheckY + blockHeight;
                        if (position.y < blockTop) {
                            if (!collisionOccurred || blockTop > correctedPositionY) {
                                correctedPositionY = blockTop;
                            }
                            collisionOccurred = true;
                            downwardCollision = true;
                        }
                    }
                } else if (velocity.y > 0) {
                    int blockToCheckY = (int) Math.floor(position.y + PLAYER_HEIGHT);
                    float blockHeight = getBlockCollisionHeight(xi, blockToCheckY, zi);
                    if (blockHeight > 0) {
                        float potentialNewY = (float) blockToCheckY - PLAYER_HEIGHT;
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
            velocity.y = 0;
            if (downwardCollision) state.setOnGround(true);
        } else if (velocity.y < 0) {
            state.setOnGround(false);
        }
    }

    public void resolveZ() {
        Vector3f position = state.getPosition();
        Vector3f velocity = state.getVelocity();
        float halfWidth = PLAYER_WIDTH / 2;
        float correctedPositionZ = position.z;
        boolean collisionOccurred = false;
        float stepUpHeight = 0.0f;

        float playerFootY = position.y;
        float playerHeadY = position.y + PLAYER_HEIGHT;
        float checkMinX = position.x - halfWidth;
        float checkMaxX = position.x + halfWidth;

        for (int yi = (int) Math.floor(playerFootY); yi < (int) Math.ceil(playerHeadY); yi++) {
            for (int xi = (int) Math.floor(checkMinX); xi < (int) Math.ceil(checkMaxX); xi++) {
                if (velocity.z < 0) {
                    float playerFrontEdge = position.z - halfWidth;
                    int blockToCheckZ = (int) Math.floor(playerFrontEdge);
                    float blockHeight = getBlockCollisionHeight(xi, yi, blockToCheckZ);
                    if (blockHeight > 0) {
                        float blockTop = yi + blockHeight;
                        float stepUpNeeded = blockTop - position.y;
                        float playerBaseY = (int) Math.floor(position.y);
                        float playerElevation = position.y - playerBaseY;
                        boolean canStepUp = (blockHeight < 1.0f) ||
                                (blockHeight == 1.0f && playerElevation >= 0.5f);
                        if (stepUpNeeded > 0.0f && stepUpNeeded <= 1.0f && canStepUp &&
                                yi == (int) Math.floor(playerFootY) && position.y >= yi) {
                            stepUpHeight = Math.max(stepUpHeight, stepUpNeeded);
                        } else if (yi > (int) Math.floor(playerFootY) && blockHeight <= 0.125f && stepUpHeight > 0.0f) {
                            // Single-layer snow on upper row during step-up — skip wall collision
                        } else if (position.y < blockTop && position.y + PLAYER_HEIGHT > yi) {
                            float potentialNewZ = (float) (blockToCheckZ + 1) + halfWidth;
                            if (!collisionOccurred || potentialNewZ > correctedPositionZ) {
                                correctedPositionZ = potentialNewZ;
                            }
                            collisionOccurred = true;
                        }
                    }
                } else if (velocity.z > 0) {
                    float playerBackEdge = position.z + halfWidth;
                    int blockToCheckZ = (int) Math.floor(playerBackEdge);
                    float blockHeight = getBlockCollisionHeight(xi, yi, blockToCheckZ);
                    if (blockHeight > 0) {
                        float blockTop = yi + blockHeight;
                        float stepUpNeeded = blockTop - position.y;
                        float playerBaseY = (int) Math.floor(position.y);
                        float playerElevation = position.y - playerBaseY;
                        boolean canStepUp = (blockHeight < 1.0f) ||
                                (blockHeight == 1.0f && playerElevation >= 0.5f);
                        if (stepUpNeeded > 0.0f && stepUpNeeded <= 1.0f && canStepUp &&
                                yi == (int) Math.floor(playerFootY) && position.y >= yi) {
                            stepUpHeight = Math.max(stepUpHeight, stepUpNeeded);
                        } else if (yi > (int) Math.floor(playerFootY) && blockHeight <= 0.125f && stepUpHeight > 0.0f) {
                            // Single-layer snow on upper row during step-up — skip wall collision
                        } else if (position.y < blockTop && position.y + PLAYER_HEIGHT > yi) {
                            float potentialNewZ = (float) blockToCheckZ - halfWidth;
                            if (!collisionOccurred || potentialNewZ < correctedPositionZ) {
                                correctedPositionZ = potentialNewZ;
                            }
                            collisionOccurred = true;
                        }
                    }
                }
            }
        }

        if (stepUpHeight > 0.0f && !collisionOccurred && state.isOnGround()) {
            position.y += stepUpHeight + 0.01f;
        } else if (collisionOccurred) {
            float originalZ = position.z;
            position.z = correctedPositionZ;
            if (velocity.z > 0 && correctedPositionZ < originalZ) velocity.z = 0;
            else if (velocity.z < 0 && correctedPositionZ > originalZ) velocity.z = 0;
        }
    }

    /**
     * Returns true if the player AABB currently overlaps any solid block. Used to warn
     * the player that exiting spectator mode will leave them stuck.
     */
    public boolean isPlayerInsideSolidBlock() {
        Vector3f position = state.getPosition();
        float halfWidth = PLAYER_WIDTH / 2;
        int minX = (int) Math.floor(position.x - halfWidth);
        int maxX = (int) Math.ceil(position.x + halfWidth);
        int minY = (int) Math.floor(position.y);
        int maxY = (int) Math.ceil(position.y + PLAYER_HEIGHT);
        int minZ = (int) Math.floor(position.z - halfWidth);
        int maxZ = (int) Math.ceil(position.z + halfWidth);

        for (int x = minX; x < maxX; x++) {
            for (int y = minY; y < maxY; y++) {
                for (int z = minZ; z < maxZ; z++) {
                    if (world.getBlockAt(x, y, z).isSolid()) {
                        return true;
                    }
                }
            }
        }
        return false;
    }

    public float getBlockCollisionHeight(int x, int y, int z) {
        BlockType block = world.getBlockAt(x, y, z);
        if (block == BlockType.SNOW) {
            return world.getSnowHeight(x, y, z);
        }
        return block.getCollisionHeight();
    }
}
