package com.stonebreak.world.chunk.operations;

public enum ChunkState {
    /**
     * Chunk has been created but blocks not yet populated
     */
    CREATED,
    
    /**
     * Block data has been generated/populated
     */
    BLOCKS_POPULATED,
    
    /**
     * Features (trees, structures) have been populated
     */
    FEATURES_POPULATED,
    
    /**
     * Block data has been modified and mesh needs regeneration
     */
    MESH_DIRTY,
    
    /**
     * Mesh generation is currently scheduled or in progress
     */
    MESH_GENERATING,
    
    /**
     * Mesh data has been generated on CPU and is ready for GPU upload
     */
    MESH_CPU_READY,
    
    /**
     * Mesh has been uploaded to GPU and is ready for rendering
     */
    MESH_GPU_UPLOADED,
    
    /**
     * Chunk is being unloaded and should not be processed
     */
    UNLOADING
}