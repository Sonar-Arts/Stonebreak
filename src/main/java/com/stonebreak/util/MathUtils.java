package com.stonebreak.util;

import org.joml.Vector3f;
import org.joml.Vector3i;

import com.stonebreak.world.World;
import com.stonebreak.blocks.BlockType;

/**
 * Utility class for math operations.
 */
public class MathUtils {

    /**
     * Converts world coordinates to chunk coordinates.
     * @param worldX World X coordinate
     * @param worldZ World Z coordinate
     * @return A Vector3i with chunk coordinates (x, 0, z)
     */
    public static Vector3i worldToChunkCoordinates(int worldX, int worldZ) {
        int chunkX = Math.floorDiv(worldX, World.CHUNK_SIZE);
        int chunkZ = Math.floorDiv(worldZ, World.CHUNK_SIZE);
        return new Vector3i(chunkX, 0, chunkZ);
    }
    
    /**
     * Converts world coordinates to local chunk coordinates.
     * @param worldX World X coordinate
     * @param worldY World Y coordinate
     * @param worldZ World Z coordinate
     * @return A Vector3i with local coordinates within a chunk
     */
    public static Vector3i worldToLocalCoordinates(int worldX, int worldY, int worldZ) {
        int localX = Math.floorMod(worldX, World.CHUNK_SIZE);
        int localZ = Math.floorMod(worldZ, World.CHUNK_SIZE);
        return new Vector3i(localX, worldY, localZ);
    }
    
    /**
     * Performs a raycast in the world and returns the first block hit.
     * @param origin The origin point of the ray
     * @param direction The direction of the ray
     * @param maxDistance The maximum distance to check
     * @param world The world to check against
     * @return The position of the hit block, or null if no block was hit
     */
    public static Vector3i raycast(Vector3f origin, Vector3f direction, float maxDistance, World world) {
        Vector3f normalizedDir = new Vector3f(direction).normalize();
        
        // Ray marching parameters
        float stepSize = 0.1f;
        int steps = (int) (maxDistance / stepSize);
        
        for (int i = 0; i < steps; i++) {
            float distance = i * stepSize;
            Vector3f point = new Vector3f(normalizedDir).mul(distance).add(origin);
            
            int blockX = (int) Math.floor(point.x);
            int blockY = (int) Math.floor(point.y);
            int blockZ = (int) Math.floor(point.z);
            
            // Check if the block is solid
            BlockType blockType = world.getBlockAt(blockX, blockY, blockZ);
            if (blockType != BlockType.AIR && blockType.isSolid()) {
                return new Vector3i(blockX, blockY, blockZ);
            }
        }
        
        return null;
    }
    
    /**
     * Calculates which face of a block was hit by a ray.
     * @param hitPos The position of the hit block
     * @param rayOrigin The origin of the ray
     * @param rayDir The direction of the ray
     * @return The face index (0=top, 1=bottom, 2=front, 3=back, 4=right, 5=left)
     */
    public static int getHitFace(Vector3i hitPos, Vector3f rayOrigin, Vector3f rayDir) {
        // Calculate hit point on the block surface
        Vector3f blockCenter = new Vector3f(
                hitPos.x + 0.5f,
                hitPos.y + 0.5f,
                hitPos.z + 0.5f
        );
        
        // Direction from center to hit point
        Vector3f directionToHit = new Vector3f(rayOrigin).sub(blockCenter);
        
        // Get the maximum component
        float absX = Math.abs(directionToHit.x);
        float absY = Math.abs(directionToHit.y);
        float absZ = Math.abs(directionToHit.z);
        
        // Determine which face was hit based on the largest component
        if (absX >= absY && absX >= absZ) {
            return directionToHit.x > 0 ? 5 : 4; // Left or Right
        } else if (absY >= absX && absY >= absZ) {
            return directionToHit.y > 0 ? 1 : 0; // Bottom or Top
        } else {
            return directionToHit.z > 0 ? 3 : 2; // Back or Front
        }
    }
    
    /**
     * Gets the block position adjacent to the given position in the specified direction.
     * @param pos The block position
     * @param face The face index (0=top, 1=bottom, 2=front, 3=back, 4=right, 5=left)
     * @return The adjacent block position
     */
    public static Vector3i getAdjacentBlockPos(Vector3i pos, int face) {
        switch (face) {
            case 0: return new Vector3i(pos.x, pos.y + 1, pos.z); // Top
            case 1: return new Vector3i(pos.x, pos.y - 1, pos.z); // Bottom
            case 2: return new Vector3i(pos.x, pos.y, pos.z + 1); // Front
            case 3: return new Vector3i(pos.x, pos.y, pos.z - 1); // Back
            case 4: return new Vector3i(pos.x + 1, pos.y, pos.z); // Right
            case 5: return new Vector3i(pos.x - 1, pos.y, pos.z); // Left
            default: return new Vector3i(pos);
        }
    }
}
