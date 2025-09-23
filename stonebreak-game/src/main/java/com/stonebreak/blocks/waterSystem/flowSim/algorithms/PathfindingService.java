package com.stonebreak.blocks.waterSystem.flowSim.algorithms;

import com.stonebreak.blocks.BlockType;
import com.stonebreak.world.World;
import org.joml.Vector3i;

import java.util.*;

/**
 * Provides pathfinding services for water flow calculations.
 * Implements Minecraft's edge detection algorithms for waterfall creation.
 *
 * Following Single Responsibility Principle - only handles pathfinding logic.
 */
public class PathfindingService {

    private static final int MIN_FLOW_WEIGHT = 1000;

    /**
     * Finds all edges within a certain distance and returns their distances.
     * This implements Minecraft's edge detection for flow direction preference.
     */
    public Map<Vector3i, Integer> findEdgeDistances(Vector3i startPos, World world, int maxDistance) {
        Map<Vector3i, Integer> edgeDistances = new HashMap<>();

        Queue<PathNode> queue = new ArrayDeque<>();
        Set<Vector3i> visited = new HashSet<>();

        queue.offer(new PathNode(startPos, 0));
        visited.add(startPos);

        Vector3i[] directions = {
            new Vector3i(1, 0, 0), new Vector3i(-1, 0, 0),
            new Vector3i(0, 0, 1), new Vector3i(0, 0, -1)
        };

        while (!queue.isEmpty()) {
            PathNode current = queue.poll();

            // Skip if we've exceeded the search distance
            if (current.distance > maxDistance) {
                continue;
            }

            // Check if this position is an edge (can flow down)
            Vector3i downPos = new Vector3i(current.pos.x, current.pos.y - 1, current.pos.z);
            if (current.distance > 0) { // Don't check the starting position itself
                BlockType belowBlock = world.getBlockAt(downPos.x, downPos.y, downPos.z);
                if (belowBlock == BlockType.AIR) {
                    // This is an edge! Water can fall here
                    edgeDistances.putIfAbsent(current.pos, current.distance);
                }
            }

            // Continue searching if we haven't reached max distance
            if (current.distance < maxDistance) {
                for (Vector3i dir : directions) {
                    Vector3i nextPos = new Vector3i(current.pos).add(dir);

                    if (!visited.contains(nextPos)) {
                        // Check if position is traversable (air or water)
                        BlockType blockType = world.getBlockAt(nextPos.x, nextPos.y, nextPos.z);
                        if (blockType == BlockType.AIR || blockType == BlockType.WATER) {
                            visited.add(nextPos);
                            queue.offer(new PathNode(nextPos, current.distance + 1));
                        }
                    }
                }
            }
        }

        return edgeDistances;
    }

    /**
     * Finds the shortest path to a downward flow opportunity (edge detection).
     * This implements Minecraft's edge detection for creating waterfalls.
     */
    public int findShortestPathToDown(Vector3i startPos, World world, int maxDistance) {
        // Check immediate down first
        Vector3i immediateDown = new Vector3i(startPos.x, startPos.y - 1, startPos.z);
        if (canFlowTo(immediateDown, world)) {
            return 0; // Can flow down immediately
        }

        // Search within maxDistance blocks for edges (Minecraft behavior)
        Queue<PathNode> queue = new ArrayDeque<>();
        Set<Vector3i> visited = new HashSet<>();

        queue.offer(new PathNode(startPos, 0));
        visited.add(startPos);

        Vector3i[] directions = {
            new Vector3i(1, 0, 0), new Vector3i(-1, 0, 0),
            new Vector3i(0, 0, 1), new Vector3i(0, 0, -1)
        };

        while (!queue.isEmpty()) {
            PathNode current = queue.poll();

            if (current.distance >= maxDistance) {
                continue;
            }

            // Check if we can flow down from this position (edge detection)
            Vector3i downPos = new Vector3i(current.pos.x, current.pos.y - 1, current.pos.z);
            BlockType belowBlock = world.getBlockAt(downPos.x, downPos.y, downPos.z);
            if (belowBlock == BlockType.AIR) {
                return current.distance;
            }

            // Explore adjacent positions
            for (Vector3i dir : directions) {
                Vector3i nextPos = new Vector3i(current.pos).add(dir);

                if (!visited.contains(nextPos)) {
                    // Check if position is traversable (air or water)
                    BlockType blockType = world.getBlockAt(nextPos.x, nextPos.y, nextPos.z);
                    if (blockType == BlockType.AIR || blockType == BlockType.WATER) {
                        visited.add(nextPos);
                        queue.offer(new PathNode(nextPos, current.distance + 1));
                    }
                }
            }
        }

        return -1; // No edge found within range
    }

    /**
     * Checks if water can flow to a position.
     */
    public boolean canFlowTo(Vector3i pos, World world) {
        if (pos.y < 0 || pos.y >= 256) { // Using fixed world height for now
            return false;
        }

        BlockType blockType = world.getBlockAt(pos.x, pos.y, pos.z);
        return blockType == BlockType.AIR || blockType == BlockType.WATER;
    }

    /**
     * Helper class for pathfinding nodes.
     */
    public static class PathNode {
        final Vector3i pos;
        final int distance;

        public PathNode(Vector3i pos, int distance) {
            this.pos = new Vector3i(pos);
            this.distance = distance;
        }
    }
}