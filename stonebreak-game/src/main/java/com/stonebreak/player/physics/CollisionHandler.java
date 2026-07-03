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

        // Door panels: model-tied boxes (thin, 2 tall, possibly crossing the
        // hinge-side cell boundary when open). Merged into the same correction
        // so cell and panel hits resolve together. No step-up — panels are walls.
        for (float[] b : nearbyDoorPanels()) {
            if (!overlapsPanel(b, position.x - halfWidth, playerFootY, checkMinZ,
                    position.x + halfWidth, playerHeadY, checkMaxZ)) {
                continue;
            }
            if (velocity.x < 0) {
                float candidate = b[3] + halfWidth;
                if (!collisionOccurred || candidate > correctedPositionX) correctedPositionX = candidate;
                collisionOccurred = true;
            } else if (velocity.x > 0) {
                float candidate = b[0] - halfWidth;
                if (!collisionOccurred || candidate < correctedPositionX) correctedPositionX = candidate;
                collisionOccurred = true;
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

        // Door panels: land on top of / bump under a panel. Gated to near the
        // panel surface so a lateral overlap (e.g. a door closed onto the
        // player) never teleports them the panel's full 2-block height.
        for (float[] b : nearbyDoorPanels()) {
            if (!(playerMaxX > b[0] && playerMinX < b[3] && playerMaxZ > b[2] && playerMinZ < b[5])) {
                continue;
            }
            if (velocity.y < 0 && position.y < b[4] && position.y > b[4] - 0.5f) {
                if (!collisionOccurred || b[4] > correctedPositionY) {
                    correctedPositionY = b[4];
                }
                collisionOccurred = true;
                downwardCollision = true;
            } else if (velocity.y > 0) {
                float head = position.y + PLAYER_HEIGHT;
                if (head > b[1] && head < b[1] + 0.5f) {
                    float candidate = b[1] - PLAYER_HEIGHT;
                    if (!collisionOccurred || candidate < correctedPositionY) {
                        correctedPositionY = candidate;
                    }
                    collisionOccurred = true;
                }
            }
        }

        if (collisionOccurred) {
            position.y = correctedPositionY;
            velocity.y = 0;
            if (downwardCollision) {
                state.setOnGround(true);
            } else {
                // Hit ceiling — clear ground flag so gravity can take over.
                state.setOnGround(false);
            }
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

        // Door panels — see resolveX for semantics.
        for (float[] b : nearbyDoorPanels()) {
            if (!overlapsPanel(b, checkMinX, playerFootY, position.z - halfWidth,
                    checkMaxX, playerHeadY, position.z + halfWidth)) {
                continue;
            }
            if (velocity.z < 0) {
                float candidate = b[5] + halfWidth;
                if (!collisionOccurred || candidate > correctedPositionZ) correctedPositionZ = candidate;
                collisionOccurred = true;
            } else if (velocity.z > 0) {
                float candidate = b[2] - halfWidth;
                if (!collisionOccurred || candidate < correctedPositionZ) correctedPositionZ = candidate;
                collisionOccurred = true;
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
                    // Route through the position-aware height so state-aware
                    // exceptions (doors, snow layers) agree with the sweep.
                    if (getBlockCollisionHeight(x, y, z) > 0.0f) {
                        return true;
                    }
                }
            }
        }
        // Door panels collide as model boxes, not cells.
        for (float[] b : nearbyDoorPanels()) {
            if (overlapsPanel(b, position.x - halfWidth, position.y, position.z - halfWidth,
                    position.x + halfWidth, position.y + PLAYER_HEIGHT, position.z + halfWidth)) {
                return true;
            }
        }
        return false;
    }

    public float getBlockCollisionHeight(int x, int y, int z) {
        BlockType block = world.getBlockAt(x, y, z);
        if (block == BlockType.SNOW) {
            return world.getSnowHeight(x, y, z);
        }
        if (block == BlockType.OAK_DOOR) {
            // Doors never collide as cells — the panel AABB pass below
            // resolves against the actual model box (thin, 2 blocks tall,
            // swung across the hinge boundary when open).
            return 0.0f;
        }
        return block.getCollisionHeight();
    }

    // ─── Door panel collision (model-tied AABBs) ─────────────────────────────

    /**
     * World AABBs of door panels near enough to touch the player. Boxes come
     * from {@link com.stonebreak.blocks.door.DoorState#panelWorldAabb} — the
     * exact settled pose of the rendered model (during the swing animation
     * the target pose collides). Doors are indexed by the world's
     * AnimatedBlockRegistry, so this is a short sparse list, not a scan.
     */
    private java.util.List<float[]> nearbyDoorPanels() {
        java.util.List<float[]> panels = new java.util.ArrayList<>(2);
        Vector3f position = state.getPosition();
        for (com.openmason.engine.util.BlockPos pos : world.getAnimatedBlockRegistry().positions()) {
            // Quick reject: a panel reaches at most 1 block outside its cell.
            if (Math.abs(pos.x() + 0.5f - position.x) > 3f
                    || Math.abs(pos.z() + 0.5f - position.z) > 3f
                    || position.y - pos.y() > com.stonebreak.blocks.door.DoorState.PANEL_HEIGHT + 1f
                    || pos.y() - position.y > PLAYER_HEIGHT + 1f) {
                continue;
            }
            if (world.getBlockAt(pos.x(), pos.y(), pos.z()) != BlockType.OAK_DOOR) {
                continue;
            }
            panels.add(com.stonebreak.blocks.door.DoorState
                    .parse(world.getBlockStateAt(pos.x(), pos.y(), pos.z()))
                    .panelWorldAabb(pos.x(), pos.y(), pos.z()));
        }
        return panels;
    }

    /** Player AABB vs panel box overlap. Box layout: {minX,minY,minZ,maxX,maxY,maxZ}. */
    private static boolean overlapsPanel(float[] b, float minX, float minY, float minZ,
                                         float maxX, float maxY, float maxZ) {
        return maxX > b[0] && minX < b[3]
                && maxY > b[1] && minY < b[4]
                && maxZ > b[2] && minZ < b[5];
    }
}
