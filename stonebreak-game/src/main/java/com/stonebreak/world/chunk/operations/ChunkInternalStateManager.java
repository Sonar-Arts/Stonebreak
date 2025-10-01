package com.stonebreak.world.chunk.operations;

import java.util.EnumSet;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Thread-safe manager for chunk state using atomic operations.
 * Replaces scattered boolean flags with a coherent state system.
 */
public class ChunkInternalStateManager {
    
    private final AtomicReference<EnumSet<ChunkState>> stateSet = new AtomicReference<>(EnumSet.of(ChunkState.CREATED));
    
    /**
     * Atomically adds a state to the chunk.
     * @param state The state to add
     * @return true if the state was added (wasn't already present)
     */
    public boolean addState(ChunkState state) {
        return stateSet.updateAndGet(current -> {
            EnumSet<ChunkState> newSet = EnumSet.copyOf(current);
            newSet.add(state);
            return newSet;
        }).contains(state) && !getPreviousStateSet().contains(state);
    }
    
    /**
     * Atomically removes a state from the chunk.
     * @param state The state to remove
     * @return true if the state was removed (was previously present)
     */
    public boolean removeState(ChunkState state) {
        return stateSet.updateAndGet(current -> {
            EnumSet<ChunkState> newSet = EnumSet.copyOf(current);
            newSet.remove(state);
            return newSet;
        }).contains(state) != getPreviousStateSet().contains(state);
    }
    
    /**
     * Atomically transitions from one state to another.
     * @param fromState The state that must be present for transition to occur
     * @param toState The state to transition to
     * @return true if transition was successful
     */
    public boolean transitionState(ChunkState fromState, ChunkState toState) {
        return stateSet.updateAndGet(current -> {
            if (current.contains(fromState)) {
                EnumSet<ChunkState> newSet = EnumSet.copyOf(current);
                newSet.remove(fromState);
                newSet.add(toState);
                return newSet;
            }
            return current;
        }).contains(toState);
    }
    
    /**
     * Checks if the chunk has a specific state.
     * @param state The state to check for
     * @return true if the chunk has this state
     */
    public boolean hasState(ChunkState state) {
        return stateSet.get().contains(state);
    }
    
    /**
     * Checks if the chunk has any of the specified states.
     * @param states The states to check for
     * @return true if the chunk has at least one of these states
     */
    public boolean hasAnyState(ChunkState... states) {
        EnumSet<ChunkState> current = stateSet.get();
        for (ChunkState state : states) {
            if (current.contains(state)) {
                return true;
            }
        }
        return false;
    }
    
    /**
     * Checks if the chunk has all of the specified states.
     * @param states The states to check for
     * @return true if the chunk has all of these states
     */
    public boolean hasAllStates(ChunkState... states) {
        EnumSet<ChunkState> current = stateSet.get();
        for (ChunkState state : states) {
            if (!current.contains(state)) {
                return false;
            }
        }
        return true;
    }
    
    /**
     * Gets a copy of the current state set.
     * @return Immutable copy of current states
     */
    public EnumSet<ChunkState> getCurrentStates() {
        return EnumSet.copyOf(stateSet.get());
    }
    
    /**
     * Convenience method to check if chunk is ready for rendering.
     * @return true if mesh is uploaded to GPU and chunk is not unloading
     */
    public boolean isRenderable() {
        EnumSet<ChunkState> current = stateSet.get();
        return current.contains(ChunkState.MESH_GPU_UPLOADED) && 
               !current.contains(ChunkState.UNLOADING);
    }
    
    /**
     * Convenience method to check if mesh data is ready for GL upload.
     * @return true if mesh CPU data is ready and not currently generating
     */
    public boolean isMeshReadyForUpload() {
        EnumSet<ChunkState> current = stateSet.get();
        return current.contains(ChunkState.MESH_CPU_READY) && 
               !current.contains(ChunkState.MESH_GENERATING) &&
               !current.contains(ChunkState.UNLOADING);
    }
    
    /**
     * Convenience method to check if mesh needs regeneration.
     * @return true if mesh is dirty and not currently generating
     */
    public boolean needsMeshGeneration() {
        EnumSet<ChunkState> current = stateSet.get();
        return current.contains(ChunkState.MESH_DIRTY) && 
               !current.contains(ChunkState.MESH_GENERATING) &&
               !current.contains(ChunkState.UNLOADING);
    }
    
    /**
     * Marks the chunk as having dirty mesh data that needs regeneration.
     */
    public void markMeshDirty() {
        stateSet.updateAndGet(current -> {
            EnumSet<ChunkState> newSet = EnumSet.copyOf(current);
            newSet.add(ChunkState.MESH_DIRTY);
            newSet.remove(ChunkState.MESH_CPU_READY);
            // Keep MESH_GPU_UPLOADED so the old mesh continues rendering
            // while a new mesh is being generated and uploaded.
            return newSet;
        });
    }

    /**
     * Clears the mesh dirty flag. Used by save system after chunk is saved.
     */
    public void clearMeshDirty() {
        removeState(ChunkState.MESH_DIRTY);
    }

    /**
     * Checks if the chunk's mesh is dirty.
     * @return true if chunk has unsaved changes
     */
    public boolean isMeshDirty() {
        return hasState(ChunkState.MESH_DIRTY);
    }
    
    /**
     * Marks mesh generation as started.
     * @return true if transition was successful (was dirty, now generating)
     */
    public boolean markMeshGenerating() {
        return transitionState(ChunkState.MESH_DIRTY, ChunkState.MESH_GENERATING);
    }
    
    /**
     * Marks mesh generation as completed successfully.
     * @return true if transition was successful (was generating, now CPU ready)
     */
    public boolean markMeshCpuReady() {
        return transitionState(ChunkState.MESH_GENERATING, ChunkState.MESH_CPU_READY);
    }
    
    /**
     * Marks mesh as uploaded to GPU.
     * @return true if transition was successful (was CPU ready, now GPU uploaded)
     */
    public boolean markMeshGpuUploaded() {
        return transitionState(ChunkState.MESH_CPU_READY, ChunkState.MESH_GPU_UPLOADED);
    }
    
    /**
     * Helper method to get the previous state set for comparison operations.
     * This is a workaround for atomic operations that need to know the previous value.
     */
    private EnumSet<ChunkState> previousStateSet;
    
    private EnumSet<ChunkState> getPreviousStateSet() {
        EnumSet<ChunkState> current = stateSet.get();
        EnumSet<ChunkState> previous = this.previousStateSet;
        this.previousStateSet = EnumSet.copyOf(current);
        return previous != null ? previous : EnumSet.noneOf(ChunkState.class);
    }
}
