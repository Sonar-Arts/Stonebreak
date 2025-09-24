package com.stonebreak.blocks.waterSystem.flowSim.algorithms;

import com.stonebreak.blocks.BlockType;
import com.stonebreak.blocks.waterSystem.WaterBlock;
import com.stonebreak.blocks.waterSystem.flowSim.core.FlowUpdateScheduler;
import com.stonebreak.world.World;
import com.stonebreak.world.operations.WorldConfiguration;
import org.joml.Vector3i;

import java.util.HashSet;
import java.util.Map;
import java.util.Set;

/**
 * Validates water flow rules and handles water placement/removal.
 * Implements Minecraft's water validation mechanics.
 *
 * Following Single Responsibility Principle - only handles flow validation.
 */
public class FlowValidator {

    private static final int MAX_HORIZONTAL_DISTANCE = 7;
    private static final int MAX_DEPTH = 7;

    private final Map<Vector3i, WaterBlock> waterBlocks;
    private final Set<Vector3i> sourceBlocks;
    private final FlowUpdateScheduler scheduler;

    public FlowValidator(Map<Vector3i, WaterBlock> waterBlocks, Set<Vector3i> sourceBlocks, FlowUpdateScheduler scheduler) {
        this.waterBlocks = waterBlocks;
        this.sourceBlocks = sourceBlocks;
        this.scheduler = scheduler;
    }

    /**
     * Checks if flowing water has a valid source.
     * Water can flow infinitely downward but only 7 blocks horizontally.
     * Ocean water (at or below sea level) is always considered valid.
     * Flow blocks cannot connect diagonally to source blocks.
     * Source blocks must have at least one unblocked side face to feed flow blocks.
     */
    public boolean hasValidSource(Vector3i pos, World world) {
        // First check if this is ocean water - ocean water is always valid
        WaterBlock currentWater = waterBlocks.get(pos);
        if (currentWater != null && currentWater.isOceanWater()) {
            return true; // Ocean water is always valid and stable
        }

        // Check if this is at ocean level (at or below sea level) - treat as ocean water
        if (pos.y <= WorldConfiguration.SEA_LEVEL) {
            return true; // Water at ocean level is considered stable
        }

        // Check if there's water directly above (infinite vertical flow)
        Vector3i abovePos = new Vector3i(pos.x, pos.y + 1, pos.z);
        BlockType aboveBlock = world.getBlockAt(abovePos.x, abovePos.y, abovePos.z);
        if (aboveBlock == BlockType.WATER) {
            return true; // Water can flow infinitely downward
        }

        // Check for adjacent water blocks that could be feeding this one
        Vector3i[] neighbors = {
            new Vector3i(pos.x + 1, pos.y, pos.z),
            new Vector3i(pos.x - 1, pos.y, pos.z),
            new Vector3i(pos.x, pos.y, pos.z + 1),
            new Vector3i(pos.x, pos.y, pos.z - 1)
        };

        for (Vector3i neighbor : neighbors) {
            WaterBlock neighborWater = waterBlocks.get(neighbor);
            if (neighborWater != null) {
                // Ocean water can support any adjacent water
                if (neighborWater.isOceanWater()) {
                    return true;
                }

                // Check if neighbor has lower or equal depth (can feed us)
                int ourDepth = waterBlocks.get(pos) != null ? waterBlocks.get(pos).getDepth() : MAX_DEPTH;
                if (neighborWater.getDepth() < ourDepth) {
                    return true;
                }
            }
        }

        // Check if there's a source block nearby with valid connection
        for (Vector3i sourcePos : sourceBlocks) {
            if (canSourceFeedFlowBlock(sourcePos, pos, world)) {
                return true;
            }
        }

        return false;
    }

    /**
     * Checks if there's a valid flow path between source and target.
     * This considers both horizontal spread limits and vertical flow.
     */
    public boolean hasValidFlowPath(Vector3i source, Vector3i target, World world) {
        // If target is directly below source, always valid (infinite vertical)
        if (source.x == target.x && source.z == target.z && source.y > target.y) {
            return true;
        }

        // For horizontal flow, check if within the 7-block limit
        int horizontalDistance = Math.abs(target.x - source.x) + Math.abs(target.z - source.z);

        // If on same level, simple distance check
        if (source.y == target.y) {
            return horizontalDistance <= MAX_HORIZONTAL_DISTANCE;
        }

        // If source is higher, water may have reset its depth when flowing down
        // Each level down allows another 7 blocks of horizontal spread
        if (source.y > target.y) {
            // Check if the horizontal distance is reasonable for the vertical drop
            return horizontalDistance <= MAX_HORIZONTAL_DISTANCE;
        }

        return false;
    }

    /**
     * Estimates depth from surrounding water blocks.
     */
    public int estimateDepthFromSurroundings(Vector3i pos, World world) {
        // Check for adjacent water blocks to estimate depth
        Vector3i[] neighbors = {
            new Vector3i(pos.x + 1, pos.y, pos.z),
            new Vector3i(pos.x - 1, pos.y, pos.z),
            new Vector3i(pos.x, pos.y, pos.z + 1),
            new Vector3i(pos.x, pos.y, pos.z - 1),
            new Vector3i(pos.x, pos.y + 1, pos.z)
        };

        int minDepth = MAX_DEPTH;
        for (Vector3i neighbor : neighbors) {
            WaterBlock neighborWater = waterBlocks.get(neighbor);
            if (neighborWater != null) {
                minDepth = Math.min(minDepth, neighborWater.getDepth() + 1);
            }
        }

        return Math.min(minDepth, MAX_DEPTH);
    }

    /**
     * Removes water at a position.
     */
    public void removeWaterAt(Vector3i pos, World world) {
        waterBlocks.remove(pos);
        sourceBlocks.remove(pos);
        world.setBlockAt(pos.x, pos.y, pos.z, BlockType.AIR);

        // Schedule neighbor updates
        scheduler.scheduleNeighborUpdates(pos);
    }

    /**
     * Validates if a water block should continue to exist.
     */
    public boolean shouldWaterExist(Vector3i pos, World world) {
        // Check if block is still water in the world
        BlockType blockType = world.getBlockAt(pos.x, pos.y, pos.z);
        if (blockType != BlockType.WATER) {
            return false;
        }

        // Source blocks always exist
        if (sourceBlocks.contains(pos)) {
            return true;
        }

        // Check if it has a valid source
        return hasValidSource(pos, world);
    }

    /**
     * Updates water block state based on validation.
     */
    public void validateAndUpdateWater(Vector3i pos, World world) {
        if (!shouldWaterExist(pos, world)) {
            removeWaterAt(pos, world);
        }
    }

    /**
     * Checks if a source block can feed a specific flow block.
     * Prevents diagonal connections and ensures source has unblocked side faces.
     *
     * @param sourcePos Position of the source block
     * @param flowPos Position of the flow block
     * @param world The world instance
     * @return true if the source can feed the flow block
     */
    public boolean canSourceFeedFlowBlock(Vector3i sourcePos, Vector3i flowPos, World world) {
        // Calculate distance components
        int dx = Math.abs(flowPos.x - sourcePos.x);
        int dy = Math.abs(flowPos.y - sourcePos.y);
        int dz = Math.abs(flowPos.z - sourcePos.z);
        int horizontalDistance = dx + dz;

        // Check distance limits
        if (horizontalDistance > MAX_HORIZONTAL_DISTANCE || dy > 1) {
            return false;
        }

        // Prevent diagonal connections - flow blocks can only connect through cardinal directions
        if (isDiagonalConnection(sourcePos, flowPos)) {
            return false;
        }

        // Check if source block has at least one unblocked side face
        if (!hasUnblockedSideFaces(sourcePos, world)) {
            return false;
        }

        // If flow block is directly below source, always valid (vertical flow)
        if (sourcePos.x == flowPos.x && sourcePos.z == flowPos.z && sourcePos.y > flowPos.y) {
            return true;
        }

        // For horizontal connections, ensure there's a valid path
        return hasValidFlowPath(sourcePos, flowPos, world);
    }

    /**
     * Checks if the connection between two positions is diagonal.
     * Diagonal connections are not allowed between source and flow blocks.
     *
     * @param sourcePos Source block position
     * @param flowPos Flow block position
     * @return true if the connection is diagonal
     */
    private boolean isDiagonalConnection(Vector3i sourcePos, Vector3i flowPos) {
        int dx = Math.abs(flowPos.x - sourcePos.x);
        int dz = Math.abs(flowPos.z - sourcePos.z);

        // Diagonal if both x and z components are non-zero (on same Y level)
        // Vertical connections (only Y differs) are not diagonal
        if (sourcePos.y == flowPos.y) {
            return dx > 0 && dz > 0;
        }

        // For different Y levels, check if horizontal component is diagonal
        return dx > 0 && dz > 0;
    }

    /**
     * Checks if a source block has at least one unblocked side face.
     * Source blocks need unblocked side faces to generate flow to adjacent blocks.
     *
     * @param sourcePos Position of the source block
     * @param world The world instance
     * @return true if the source has at least one unblocked side face
     */
    private boolean hasUnblockedSideFaces(Vector3i sourcePos, World world) {
        // Check all four horizontal side faces (North, South, East, West)
        Vector3i[] sideFaces = {
            new Vector3i(sourcePos.x + 1, sourcePos.y, sourcePos.z), // East
            new Vector3i(sourcePos.x - 1, sourcePos.y, sourcePos.z), // West
            new Vector3i(sourcePos.x, sourcePos.y, sourcePos.z + 1), // South
            new Vector3i(sourcePos.x, sourcePos.y, sourcePos.z - 1)  // North
        };

        for (Vector3i facePos : sideFaces) {
            BlockType blockType = world.getBlockAt(facePos.x, facePos.y, facePos.z);

            // Side face is considered unblocked if it's air, water, or a destructible block
            if (blockType == BlockType.AIR ||
                blockType == BlockType.WATER ||
                canFlowToBlock(blockType)) {
                return true;
            }
        }

        return false; // All side faces are blocked
    }

    /**
     * Checks if water can flow to a specific block type.
     * Uses the same logic as FlowBlockInteraction for consistency.
     *
     * @param blockType The block type to check
     * @return true if water can flow to this block type
     */
    private boolean canFlowToBlock(BlockType blockType) {
        if (blockType == BlockType.AIR || blockType == BlockType.WATER) {
            return true;
        }

        // Check if it's a destructible block (like flowers)
        return blockType.isFlower(); // This matches FlowBlockInteraction.canFlowDestroy()
    }

    /**
     * Test method to validate the diagonal connection prevention logic.
     * This method can be used for debugging and testing the new behavior.
     *
     * @param sourcePos Source block position
     * @param flowPos Flow block position
     * @return Detailed validation result for testing
     */
    public String testDiagonalConnectionPrevention(Vector3i sourcePos, Vector3i flowPos, World world) {
        StringBuilder result = new StringBuilder();
        result.append("Testing connection from ").append(sourcePos).append(" to ").append(flowPos).append("\n");

        // Check distance
        int dx = Math.abs(flowPos.x - sourcePos.x);
        int dy = Math.abs(flowPos.y - sourcePos.y);
        int dz = Math.abs(flowPos.z - sourcePos.z);
        int horizontalDistance = dx + dz;

        result.append("Distance - dx:").append(dx).append(" dy:").append(dy).append(" dz:").append(dz)
              .append(" horizontal:").append(horizontalDistance).append("\n");

        // Check if diagonal
        boolean isDiagonal = isDiagonalConnection(sourcePos, flowPos);
        result.append("Is diagonal connection: ").append(isDiagonal).append("\n");

        // Check source side faces
        boolean hasUnblockedFaces = hasUnblockedSideFaces(sourcePos, world);
        result.append("Source has unblocked side faces: ").append(hasUnblockedFaces).append("\n");

        // Overall result
        boolean canFeed = canSourceFeedFlowBlock(sourcePos, flowPos, world);
        result.append("Can source feed flow block: ").append(canFeed).append("\n");

        return result.toString();
    }

    /**
     * Enhanced validation for complex source-flow scenarios.
     * Checks multiple source interactions and flow path conflicts.
     *
     * @param flowPos Position of the flow block to validate
     * @param world The world instance
     * @return ValidationResult with detailed information
     */
    public ValidationResult validateComplexSourceFlowConnection(Vector3i flowPos, World world) {
        ValidationResult result = new ValidationResult();
        result.flowPosition = new Vector3i(flowPos);
        result.isValid = false;

        // Find all potential source blocks that could feed this flow position
        Set<Vector3i> potentialSources = new HashSet<>();
        for (Vector3i sourcePos : sourceBlocks) {
            if (canSourceFeedFlowBlock(sourcePos, flowPos, world)) {
                potentialSources.add(new Vector3i(sourcePos));
            }
        }

        result.feedingSources = potentialSources;
        result.sourceCount = potentialSources.size();

        if (potentialSources.isEmpty()) {
            result.validationMessage = "No valid source blocks can feed this flow position";
            return result;
        }

        // Check for conflicting flow paths
        boolean hasConflicts = checkForFlowPathConflicts(flowPos, potentialSources, world);
        result.hasConflicts = hasConflicts;

        // Determine the dominant source (closest, highest priority)
        Vector3i dominantSource = findDominantSource(flowPos, potentialSources, world);
        result.dominantSource = dominantSource;

        if (dominantSource != null) {
            result.isValid = true;
            result.validationMessage = String.format("Valid flow connection from dominant source at %s", dominantSource);
        } else {
            result.validationMessage = "Unable to determine dominant source due to conflicts";
        }

        return result;
    }

    /**
     * Checks for conflicts between multiple flow paths converging at a position.
     */
    private boolean checkForFlowPathConflicts(Vector3i flowPos, Set<Vector3i> sources, World world) {
        if (sources.size() <= 1) {
            return false; // No conflicts with single source
        }

        // Check if sources are creating competing flow patterns
        for (Vector3i source1 : sources) {
            for (Vector3i source2 : sources) {
                if (source1.equals(source2)) continue;

                // Check if the two sources create conflicting flow directions
                if (hasConflictingFlowDirections(source1, source2, flowPos)) {
                    return true;
                }
            }
        }

        return false;
    }

    /**
     * Checks if two sources create conflicting flow directions at a target position.
     */
    private boolean hasConflictingFlowDirections(Vector3i source1, Vector3i source2, Vector3i target) {
        // Calculate flow vectors from each source to target
        Vector3i flow1 = new Vector3i(target).sub(source1);
        Vector3i flow2 = new Vector3i(target).sub(source2);

        // If flows are in completely opposite directions, they conflict
        int dotProduct = flow1.x * flow2.x + flow1.y * flow2.y + flow1.z * flow2.z;
        float magnitude1 = (float) Math.sqrt(flow1.x * flow1.x + flow1.y * flow1.y + flow1.z * flow1.z);
        float magnitude2 = (float) Math.sqrt(flow2.x * flow2.x + flow2.y * flow2.y + flow2.z * flow2.z);

        if (magnitude1 > 0 && magnitude2 > 0) {
            float cosAngle = dotProduct / (magnitude1 * magnitude2);
            return cosAngle < -0.5f; // Flows are more than 120 degrees apart
        }

        return false;
    }

    /**
     * Finds the dominant source among multiple candidates.
     * Priority: Ocean water > Closer sources > Higher sources
     */
    private Vector3i findDominantSource(Vector3i flowPos, Set<Vector3i> sources, World world) {
        Vector3i dominantSource = null;
        double bestScore = Double.NEGATIVE_INFINITY;

        for (Vector3i source : sources) {
            double score = calculateSourcePriority(source, flowPos, world);
            if (score > bestScore) {
                bestScore = score;
                dominantSource = source;
            }
        }

        return dominantSource;
    }

    /**
     * Calculates priority score for a source block.
     * Higher score = higher priority.
     */
    private double calculateSourcePriority(Vector3i source, Vector3i target, World world) {
        double score = 0.0;

        // Ocean water gets highest priority
        WaterBlock sourceWater = waterBlocks.get(source);
        if (sourceWater != null && sourceWater.isOceanWater()) {
            score += 1000.0;
        }

        // Closer sources get higher priority
        double distance = source.distance(target);
        score += (MAX_HORIZONTAL_DISTANCE - distance) * 10.0;

        // Higher sources get slightly higher priority (gravity)
        score += (source.y - target.y) * 2.0;

        // Sources with more unblocked faces get higher priority
        int unblockedFaces = countUnblockedSideFaces(source, world);
        score += unblockedFaces * 5.0;

        return score;
    }

    /**
     * Counts the number of unblocked side faces for a source block.
     */
    private int countUnblockedSideFaces(Vector3i sourcePos, World world) {
        int count = 0;
        Vector3i[] sideFaces = {
            new Vector3i(sourcePos.x + 1, sourcePos.y, sourcePos.z),
            new Vector3i(sourcePos.x - 1, sourcePos.y, sourcePos.z),
            new Vector3i(sourcePos.x, sourcePos.y, sourcePos.z + 1),
            new Vector3i(sourcePos.x, sourcePos.y, sourcePos.z - 1)
        };

        for (Vector3i facePos : sideFaces) {
            BlockType blockType = world.getBlockAt(facePos.x, facePos.y, facePos.z);
            if (canFlowToBlock(blockType)) {
                count++;
            }
        }

        return count;
    }

    /**
     * Result class for complex source-flow validation.
     */
    public static class ValidationResult {
        public Vector3i flowPosition;
        public boolean isValid;
        public String validationMessage;
        public Set<Vector3i> feedingSources = new HashSet<>();
        public int sourceCount;
        public boolean hasConflicts;
        public Vector3i dominantSource;

        @Override
        public String toString() {
            StringBuilder sb = new StringBuilder();
            sb.append("ValidationResult{");
            sb.append("position=").append(flowPosition);
            sb.append(", valid=").append(isValid);
            sb.append(", sources=").append(sourceCount);
            sb.append(", conflicts=").append(hasConflicts);
            if (dominantSource != null) {
                sb.append(", dominant=").append(dominantSource);
            }
            sb.append(", message='").append(validationMessage).append("'");
            sb.append('}');
            return sb.toString();
        }
    }
}