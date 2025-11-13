package com.openmason.deprecated;

/**
 * Atlas Coordinate System - Phase 7 Open Mason Implementation
 *
 * Provides exact mathematical replication of Stonebreak's texture atlas coordinate system
 * for 1:1 rendering parity. This system handles the conversion between grid coordinates
 * and UV coordinates with perfect mathematical precision.
 *
 * Key Features:
 * - Exact 16×16 grid system (GRID_SIZE = 16)
 * - 256×256 pixel atlas (ATLAS_WIDTH/HEIGHT = 256)
 * - Precise UV conversion: u = atlasX / 16.0f, v = atlasY / 16.0f
 * - OpenGL quad UV coordinate generation (8 values per face)
 * - Comprehensive bounds checking and validation
 *
 * Mathematical Precision:
 * - Grid-to-UV: Exact division by 16.0f for floating point precision
 * - UV-to-Grid: Floor operation with bounds clamping
 * - Quad coordinates: Bottom-left, bottom-right, top-right, top-left order
 * - Validation: Ensures coordinates stay within [0-15] grid bounds
 *
 * @deprecated This coordinate system is only used by {@link com.openmason.deprecated.LegacyCowCoordinateSystemIntegration}
 *             for legacy cow model rendering. Block rendering uses the CBR API from stonebreak-game
 *             ({@link com.stonebreak.rendering.core.API.commonBlockResources.resources.CBRResourceManager})
 *             which has its own built-in coordinate and mesh management. This class was created for
 *             "Phase 7 Open Mason Implementation" but the integration never happened for blocks.
 *             Consider migrating cow rendering to use CBR API or stonebreak's texture systems directly.
 */
@Deprecated
public class LegacyCowAtlasCoordinateSystem {
    
    // Fixed texture atlas parameters for exact Stonebreak compatibility
    public static final int GRID_SIZE = 16;
    public static final int ATLAS_WIDTH = 256;
    public static final int ATLAS_HEIGHT = 256;
    public static final int TILE_SIZE = 16; // pixels per tile (256 / 16 = 16)
    
    // Mathematical constants for precision
    public static final float TILE_SIZE_UV = 1.0f / GRID_SIZE; // 0.0625f
    public static final float UV_EPSILON = 0.0001f; // For floating point comparisons
    
    /**
     * UV coordinate structure representing normalized texture coordinates.
     */
    public static class UVCoordinate {
        private final float u;
        private final float v;
        
        public UVCoordinate(float u, float v) {
            this.u = u;
            this.v = v;
        }
        
        public float getU() { return u; }
        public float getV() { return v; }
        
        @Override
        public String toString() {
            return String.format("UVCoordinate{%.4f,%.4f}", u, v);
        }
        
        @Override
        public boolean equals(Object obj) {
            if (this == obj) return true;
            if (obj == null || getClass() != obj.getClass()) return false;
            UVCoordinate that = (UVCoordinate) obj;
            return Math.abs(u - that.u) < UV_EPSILON && Math.abs(v - that.v) < UV_EPSILON;
        }
        
        @Override
        public int hashCode() {
            return Float.floatToIntBits(u) * 31 + Float.floatToIntBits(v);
        }
    }
    
    /**
     * Validates coordinate bounds for Stonebreak compatibility.
     * Ensures coordinates are within the 16×16 grid system.
     *
     * @param atlasX X coordinate in grid (must be 0-15)
     * @param atlasY Y coordinate in grid (must be 0-15)
     * @return true if coordinates are valid, false otherwise
     */
    public static boolean validateCoordinateBounds(int atlasX, int atlasY) {
        return atlasX >= 0 && atlasX < GRID_SIZE && atlasY >= 0 && atlasY < GRID_SIZE;
    }

    /**
     * Convert grid coordinates to normalized UV coordinates.
     * Exact mathematical formula: u = atlasX / 16.0f, v = atlasY / 16.0f
     * 
     * This method provides the core mathematical conversion that exactly replicates
     * Stonebreak's coordinate system for perfect rendering parity.
     * 
     * @param atlasX X coordinate in grid (0-15)
     * @param atlasY Y coordinate in grid (0-15)
     * @return UVCoordinate with normalized coordinates, or null if invalid input
     */
    public static UVCoordinate gridToUV(int atlasX, int atlasY) {
        if (!validateCoordinateBounds(atlasX, atlasY)) {
            return null;
        }
        
        float u = atlasX / (float) GRID_SIZE; // Exact division for precision
        float v = atlasY / (float) GRID_SIZE; // Exact division for precision
        
        return new UVCoordinate(u, v);
    }
    
    /**
     * Convert grid coordinates to normalized UV bounds (u1, v1, u2, v2).
     * Returns the UV coordinates for a complete grid tile.
     * 
     * @param atlasX X coordinate in grid (0-15)
     * @param atlasY Y coordinate in grid (0-15)
     * @return float array with [u1, v1, u2, v2] coordinates, or null if invalid
     */
    public static float[] gridToUVBounds(int atlasX, int atlasY) {
        if (!validateCoordinateBounds(atlasX, atlasY)) {
            return null;
        }
        
        float u1 = atlasX * TILE_SIZE_UV;
        float v1 = atlasY * TILE_SIZE_UV;
        float u2 = u1 + TILE_SIZE_UV;
        float v2 = v1 + TILE_SIZE_UV;
        
        return new float[]{u1, v1, u2, v2};
    }

    /**
     * Generate quad UV coordinates for OpenGL rendering.
     * Returns 8 UV coordinates for a quad (4 vertices × 2 coordinates).
     * 
     * Vertex order: bottom-left, bottom-right, top-right, top-left
     * This matches the OpenGL quad rendering convention used in Stonebreak.
     * 
     * @param atlasX X coordinate in grid (0-15)
     * @param atlasY Y coordinate in grid (0-15)
     * @return float array with 8 UV coordinates, or null if invalid input
     */
    public static float[] generateQuadUVCoordinates(int atlasX, int atlasY) {
        float[] bounds = gridToUVBounds(atlasX, atlasY);
        if (bounds == null) {
            return null;
        }
        
        float u1 = bounds[0]; // left
        float v1 = bounds[1]; // bottom
        float u2 = bounds[2]; // right
        float v2 = bounds[3]; // top
        
        // Return quad coordinates in OpenGL order
        return new float[]{
            u1, v1, // bottom-left
            u2, v1, // bottom-right
            u2, v2, // top-right
            u1, v2  // top-left
        };
    }
    
}