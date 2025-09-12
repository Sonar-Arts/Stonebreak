package com.stonebreak.world.save;

/**
 * Utility class for working with region coordinates in the binary save system.
 * Each region contains 32x32 chunks, providing efficient file organization.
 */
public class RegionCoordinate {
    
    /** Number of chunks per region in each dimension */
    public static final int CHUNKS_PER_REGION = 32;
    
    /** Bit shift for region coordinate calculation (log2(32) = 5) */
    private static final int REGION_SHIFT = 5;
    
    /** Mask for local chunk coordinate calculation (32 - 1 = 31) */
    private static final int LOCAL_CHUNK_MASK = 31;
    
    private final int regionX;
    private final int regionZ;
    
    /**
     * Create a region coordinate.
     * @param regionX Region X coordinate
     * @param regionZ Region Z coordinate
     */
    public RegionCoordinate(int regionX, int regionZ) {
        this.regionX = regionX;
        this.regionZ = regionZ;
    }
    
    /**
     * Create a region coordinate from chunk coordinates.
     * @param chunkX Chunk X coordinate
     * @param chunkZ Chunk Z coordinate
     * @return Region coordinate containing the specified chunk
     */
    public static RegionCoordinate fromChunk(int chunkX, int chunkZ) {
        return new RegionCoordinate(chunkX >> REGION_SHIFT, chunkZ >> REGION_SHIFT);
    }
    
    /**
     * Get the region X coordinate.
     * @return Region X coordinate
     */
    public int getRegionX() {
        return regionX;
    }
    
    /**
     * Get the region Z coordinate.
     * @return Region Z coordinate
     */
    public int getRegionZ() {
        return regionZ;
    }
    
    /**
     * Get the local chunk X coordinate within this region (0-31).
     * @param chunkX Global chunk X coordinate
     * @return Local chunk X coordinate within region
     */
    public static int getLocalChunkX(int chunkX) {
        return chunkX & LOCAL_CHUNK_MASK;
    }
    
    /**
     * Get the local chunk Z coordinate within this region (0-31).
     * @param chunkZ Global chunk Z coordinate
     * @return Local chunk Z coordinate within region
     */
    public static int getLocalChunkZ(int chunkZ) {
        return chunkZ & LOCAL_CHUNK_MASK;
    }
    
    /**
     * Get the chunk index within the region (0-1023).
     * Used for accessing chunk offset/length tables in region files.
     * @param chunkX Global chunk X coordinate
     * @param chunkZ Global chunk Z coordinate
     * @return Chunk index within region (localX + localZ * 32)
     */
    public static int getChunkIndex(int chunkX, int chunkZ) {
        int localX = getLocalChunkX(chunkX);
        int localZ = getLocalChunkZ(chunkZ);
        return localX + localZ * CHUNKS_PER_REGION;
    }
    
    /**
     * Get the region file name for this coordinate.
     * @return Region file name in format "r.X.Z.mcr"
     */
    public String getFileName() {
        return "r." + regionX + "." + regionZ + ".mcr";
    }
    
    /**
     * Check if this region contains the specified chunk.
     * @param chunkX Chunk X coordinate
     * @param chunkZ Chunk Z coordinate
     * @return True if this region contains the chunk
     */
    public boolean containsChunk(int chunkX, int chunkZ) {
        RegionCoordinate chunkRegion = fromChunk(chunkX, chunkZ);
        return chunkRegion.regionX == this.regionX && chunkRegion.regionZ == this.regionZ;
    }
    
    /**
     * Get the minimum chunk X coordinate in this region.
     * @return Minimum chunk X coordinate
     */
    public int getMinChunkX() {
        return regionX << REGION_SHIFT;
    }
    
    /**
     * Get the minimum chunk Z coordinate in this region.
     * @return Minimum chunk Z coordinate
     */
    public int getMinChunkZ() {
        return regionZ << REGION_SHIFT;
    }
    
    /**
     * Get the maximum chunk X coordinate in this region.
     * @return Maximum chunk X coordinate
     */
    public int getMaxChunkX() {
        return (regionX << REGION_SHIFT) + CHUNKS_PER_REGION - 1;
    }
    
    /**
     * Get the maximum chunk Z coordinate in this region.
     * @return Maximum chunk Z coordinate
     */
    public int getMaxChunkZ() {
        return (regionZ << REGION_SHIFT) + CHUNKS_PER_REGION - 1;
    }
    
    @Override
    public boolean equals(Object obj) {
        if (this == obj) return true;
        if (obj == null || getClass() != obj.getClass()) return false;
        RegionCoordinate that = (RegionCoordinate) obj;
        return regionX == that.regionX && regionZ == that.regionZ;
    }
    
    @Override
    public int hashCode() {
        return (regionX << 16) | (regionZ & 0xFFFF);
    }
    
    @Override
    public String toString() {
        return "Region[" + regionX + ", " + regionZ + "]";
    }
}