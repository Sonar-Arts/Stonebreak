package com.stonebreak.blocks.waterSystem.states;

import com.stonebreak.blocks.BlockType;
import com.stonebreak.blocks.waterSystem.WaterBlock;
import com.stonebreak.blocks.waterSystem.types.FlowWaterType;
import com.stonebreak.blocks.waterSystem.types.SourceWaterType;
import com.stonebreak.blocks.waterSystem.types.WaterType;
import com.stonebreak.core.Game;
import com.stonebreak.world.World;
import org.joml.Vector3i;

/**
 * Implementation of water state management with proper rule enforcement.
 * Handles the critical rule that flow blocks always remain in FLOWING state.
 */
public class WaterStateManagerImpl implements WaterStateManager {

    @Override
    public WaterState determineState(WaterBlock waterBlock, Vector3i position) {
        WaterType waterType = waterBlock.getWaterType();

        // CRITICAL RULE: Flow blocks NEVER exit FLOWING state
        if (waterType instanceof FlowWaterType) {
            return WaterState.FLOWING;
        }

        // For source blocks, determine state based on conditions
        if (waterType instanceof SourceWaterType) {
            return determineSourceState(waterBlock, position);
        }

        // Fallback for unknown types
        return WaterState.STAGNANT;
    }

    @Override
    public boolean updateState(WaterBlock waterBlock, WaterState newState, Vector3i position) {
        WaterState currentState = waterBlock.getWaterState();

        // Validate transition
        if (!canTransition(waterBlock, currentState, newState)) {
            return false;
        }

        // Update state
        waterBlock.setWaterState(newState);
        return true;
    }

    @Override
    public boolean canTransition(WaterBlock waterBlock, WaterState currentState, WaterState newState) {
        WaterType waterType = waterBlock.getWaterType();

        // CRITICAL RULE: Flow blocks cannot change states
        if (waterType instanceof FlowWaterType) {
            return newState == WaterState.FLOWING;
        }

        // Source blocks can transition between states
        if (waterType instanceof SourceWaterType) {
            return true;
        }

        return false;
    }

    @Override
    public WaterState getDefaultState(WaterBlock waterBlock) {
        WaterType waterType = waterBlock.getWaterType();

        if (waterType instanceof FlowWaterType) {
            return WaterState.FLOWING;
        }

        if (waterType instanceof SourceWaterType) {
            return WaterState.STAGNANT; // Sources start stagnant until they begin flowing
        }

        return WaterState.STAGNANT;
    }

    @Override
    public boolean shouldBeVerticallyFlowing(WaterBlock waterBlock, Vector3i position) {
        World world = Game.getWorld();
        if (world == null) return false;

        // Check if there's air below
        Vector3i belowPos = new Vector3i(position.x, position.y - 1, position.z);
        BlockType belowBlock = world.getBlockAt(belowPos.x, belowPos.y, belowPos.z);

        return belowBlock == BlockType.AIR;
    }

    @Override
    public boolean shouldBeCresting(WaterBlock waterBlock, Vector3i position) {
        World world = Game.getWorld();
        if (world == null) return false;

        // Check if this is at an edge with air below
        if (!shouldBeVerticallyFlowing(waterBlock, position)) {
            return false;
        }

        // Check if water is at the edge of a solid block
        Vector3i[] directions = {
            new Vector3i(1, 0, 0),   // East
            new Vector3i(-1, 0, 0),  // West
            new Vector3i(0, 0, 1),   // South
            new Vector3i(0, 0, -1)   // North
        };

        for (Vector3i dir : directions) {
            Vector3i neighborPos = new Vector3i(position).add(dir);
            BlockType neighborBlock = world.getBlockAt(neighborPos.x, neighborPos.y, neighborPos.z);

            // If there's a solid block adjacent, this could be cresting water
            if (neighborBlock != BlockType.AIR && neighborBlock != BlockType.WATER) {
                return true;
            }
        }

        return false;
    }

    /**
     * Determines the appropriate state for a source block.
     */
    private WaterState determineSourceState(WaterBlock waterBlock, Vector3i position) {
        // Check if source should be vertically flowing
        if (shouldBeVerticallyFlowing(waterBlock, position)) {
            if (shouldBeCresting(waterBlock, position)) {
                return WaterState.CRESTING;
            }
            return WaterState.VERTICALLY_FLOWING;
        }

        // Check if source can generate horizontal flows
        if (canGenerateHorizontalFlow(position)) {
            return WaterState.FLOWING;
        }

        // Source is surrounded and cannot flow
        return WaterState.STAGNANT;
    }

    /**
     * Checks if a source at the given position can generate horizontal flow.
     */
    private boolean canGenerateHorizontalFlow(Vector3i position) {
        World world = Game.getWorld();
        if (world == null) return false;

        Vector3i[] directions = {
            new Vector3i(1, 0, 0),   // East
            new Vector3i(-1, 0, 0),  // West
            new Vector3i(0, 0, 1),   // South
            new Vector3i(0, 0, -1)   // North
        };

        for (Vector3i dir : directions) {
            Vector3i neighborPos = new Vector3i(position).add(dir);
            BlockType neighborBlock = world.getBlockAt(neighborPos.x, neighborPos.y, neighborPos.z);

            // Can flow to air or existing water
            if (neighborBlock == BlockType.AIR || neighborBlock == BlockType.WATER) {
                return true;
            }
        }

        return false;
    }
}