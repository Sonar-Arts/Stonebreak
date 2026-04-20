package com.stonebreak.player.physics;

import com.stonebreak.player.state.PhysicsState;
import org.joml.Vector3f;

import static com.stonebreak.player.PlayerConstants.PLAYER_WIDTH;

/**
 * Samples the voxels just beneath the player's feet to confirm the onGround flag.
 * When the player walks off an edge while vertically still, collision resolution
 * leaves onGround=true; this check corrects that so gravity takes over.
 */
public class GroundChecker {

    private final PhysicsState state;
    private final CollisionHandler collisionHandler;

    public GroundChecker(PhysicsState state, CollisionHandler collisionHandler) {
        this.state = state;
        this.collisionHandler = collisionHandler;
    }

    public void check() {
        if (!state.isOnGround() || state.getVelocity().y != 0) return;

        Vector3f position = state.getPosition();
        float x1 = position.x - PLAYER_WIDTH / 2;
        float x2 = position.x + PLAYER_WIDTH / 2;
        float y = position.y - 0.1f;
        float z1 = position.z - PLAYER_WIDTH / 2;
        float z2 = position.z + PLAYER_WIDTH / 2;

        boolean blockBeneath = false;
        outer:
        for (float checkX = x1 + 0.1f; checkX <= x2 - 0.1f; checkX += 0.3f) {
            for (float checkZ = z1 + 0.1f; checkZ <= z2 - 0.1f; checkZ += 0.3f) {
                int blockX = (int) Math.floor(checkX);
                int blockY = (int) Math.floor(y);
                int blockZ = (int) Math.floor(checkZ);
                float blockHeight = collisionHandler.getBlockCollisionHeight(blockX, blockY, blockZ);
                if (blockHeight > 0) {
                    float blockTop = blockY + blockHeight;
                    if (blockTop >= y) {
                        blockBeneath = true;
                        break outer;
                    }
                }
            }
        }

        if (!blockBeneath) {
            state.setOnGround(false);
            if (state.getVelocity().y == 0) state.getVelocity().y = -0.1f;
        }
    }
}
