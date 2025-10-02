package com.stonebreak.world.chunk.api.commonChunkOperations.state;

import com.stonebreak.world.chunk.api.commonChunkOperations.data.CcoChunkState;

import java.util.EnumMap;
import java.util.EnumSet;
import java.util.Map;
import java.util.Set;

/**
 * Defines valid state transitions for CCO chunks.
 * Immutable state transition graph ensuring chunks follow proper lifecycle.
 *
 * Thread-safe through immutability.
 */
public final class CcoStateTransition {

    private static final Map<CcoChunkState, Set<CcoChunkState>> VALID_TRANSITIONS;

    static {
        VALID_TRANSITIONS = new EnumMap<>(CcoChunkState.class);

        // EMPTY can transition to CREATED or UNLOADING
        VALID_TRANSITIONS.put(CcoChunkState.EMPTY,
                EnumSet.of(CcoChunkState.CREATED, CcoChunkState.UNLOADING));

        // CREATED can transition to BLOCKS_POPULATED or UNLOADING
        VALID_TRANSITIONS.put(CcoChunkState.CREATED,
                EnumSet.of(CcoChunkState.BLOCKS_POPULATED, CcoChunkState.UNLOADING));

        // BLOCKS_POPULATED can transition to FEATURES_POPULATED, MESH_DIRTY, or UNLOADING
        VALID_TRANSITIONS.put(CcoChunkState.BLOCKS_POPULATED,
                EnumSet.of(CcoChunkState.FEATURES_POPULATED, CcoChunkState.MESH_DIRTY, CcoChunkState.UNLOADING));

        // FEATURES_POPULATED can transition to MESH_DIRTY or UNLOADING
        VALID_TRANSITIONS.put(CcoChunkState.FEATURES_POPULATED,
                EnumSet.of(CcoChunkState.MESH_DIRTY, CcoChunkState.UNLOADING));

        // MESH_DIRTY can transition to MESH_GENERATING or UNLOADING
        VALID_TRANSITIONS.put(CcoChunkState.MESH_DIRTY,
                EnumSet.of(CcoChunkState.MESH_GENERATING, CcoChunkState.UNLOADING));

        // MESH_GENERATING can transition to MESH_CPU_READY, MESH_DIRTY (if interrupted), or UNLOADING
        VALID_TRANSITIONS.put(CcoChunkState.MESH_GENERATING,
                EnumSet.of(CcoChunkState.MESH_CPU_READY, CcoChunkState.MESH_DIRTY, CcoChunkState.UNLOADING));

        // MESH_CPU_READY can transition to MESH_GPU_UPLOADED, MESH_DIRTY (if blocks changed), or UNLOADING
        VALID_TRANSITIONS.put(CcoChunkState.MESH_CPU_READY,
                EnumSet.of(CcoChunkState.MESH_GPU_UPLOADED, CcoChunkState.MESH_DIRTY, CcoChunkState.UNLOADING));

        // MESH_GPU_UPLOADED can transition to READY, MESH_DIRTY (if blocks changed), or UNLOADING
        VALID_TRANSITIONS.put(CcoChunkState.MESH_GPU_UPLOADED,
                EnumSet.of(CcoChunkState.READY, CcoChunkState.MESH_DIRTY, CcoChunkState.UNLOADING));

        // READY can transition to ACTIVE, MESH_DIRTY, or UNLOADING
        VALID_TRANSITIONS.put(CcoChunkState.READY,
                EnumSet.of(CcoChunkState.ACTIVE, CcoChunkState.MESH_DIRTY, CcoChunkState.UNLOADING));

        // ACTIVE can transition back to READY, MESH_DIRTY, or UNLOADING
        VALID_TRANSITIONS.put(CcoChunkState.ACTIVE,
                EnumSet.of(CcoChunkState.READY, CcoChunkState.MESH_DIRTY, CcoChunkState.UNLOADING));

        // DATA_MODIFIED is a flag state that can coexist with others - no specific transitions

        // UNLOADING can transition to UNLOADED
        VALID_TRANSITIONS.put(CcoChunkState.UNLOADING,
                EnumSet.of(CcoChunkState.UNLOADED));

        // UNLOADED is terminal - no transitions out
        VALID_TRANSITIONS.put(CcoChunkState.UNLOADED, EnumSet.noneOf(CcoChunkState.class));
    }

    private CcoStateTransition() {
        // Utility class
    }

    /**
     * Checks if a state transition is valid.
     *
     * @param from Current state
     * @param to Desired state
     * @return true if transition is allowed
     */
    public static boolean isValidTransition(CcoChunkState from, CcoChunkState to) {
        if (from == null || to == null) {
            return false;
        }

        Set<CcoChunkState> allowedStates = VALID_TRANSITIONS.get(from);
        return allowedStates != null && allowedStates.contains(to);
    }

    /**
     * Gets all valid states that can be transitioned to from the given state.
     *
     * @param from Current state
     * @return Set of valid next states (immutable)
     */
    public static Set<CcoChunkState> getValidNextStates(CcoChunkState from) {
        if (from == null) {
            return EnumSet.noneOf(CcoChunkState.class);
        }

        Set<CcoChunkState> states = VALID_TRANSITIONS.get(from);
        return states != null ? EnumSet.copyOf(states) : EnumSet.noneOf(CcoChunkState.class);
    }

    /**
     * Checks if a state is terminal (no valid transitions out).
     *
     * @param state State to check
     * @return true if terminal
     */
    public static boolean isTerminalState(CcoChunkState state) {
        if (state == null) {
            return false;
        }

        Set<CcoChunkState> nextStates = VALID_TRANSITIONS.get(state);
        return nextStates == null || nextStates.isEmpty();
    }

    /**
     * Checks if a state can be in a state set (for multi-state scenarios).
     * Some states are mutually exclusive, some can coexist.
     *
     * @param state State to check
     * @param currentStates Current state set
     * @return true if state can be added to the set
     */
    public static boolean canCoexistWith(CcoChunkState state, Set<CcoChunkState> currentStates) {
        if (state == null || currentStates == null) {
            return false;
        }

        // DATA_MODIFIED can coexist with any state
        if (state == CcoChunkState.DATA_MODIFIED) {
            return true;
        }

        // UNLOADING cannot coexist with non-terminal states
        if (state == CcoChunkState.UNLOADING) {
            return currentStates.isEmpty();
        }

        // Mesh states are mutually exclusive
        Set<CcoChunkState> meshStates = EnumSet.of(
                CcoChunkState.MESH_DIRTY,
                CcoChunkState.MESH_GENERATING,
                CcoChunkState.MESH_CPU_READY,
                CcoChunkState.MESH_GPU_UPLOADED
        );

        if (meshStates.contains(state)) {
            for (CcoChunkState existing : currentStates) {
                if (meshStates.contains(existing) && existing != state) {
                    return false; // Can't have two mesh states simultaneously
                }
            }
        }

        return true;
    }
}
