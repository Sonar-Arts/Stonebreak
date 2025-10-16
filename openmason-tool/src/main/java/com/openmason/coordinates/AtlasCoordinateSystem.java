package com.openmason.coordinates;

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
 */
public class AtlasCoordinateSystem {
    
    // Fixed texture atlas parameters for exact Stonebreak compatibility
    public static final int GRID_SIZE = 16;
    public static final int ATLAS_WIDTH = 256;
    public static final int ATLAS_HEIGHT = 256;
    public static final int TILE_SIZE = 16; // pixels per tile (256 / 16 = 16)
    
    // Mathematical constants for precision
    public static final float TILE_SIZE_UV = 1.0f / GRID_SIZE; // 0.0625f
    public static final float UV_EPSILON = 0.0001f; // For floating point comparisons
    
    /**
     * Atlas coordinate structure representing a position in the 16×16 grid.
     */
    public static class AtlasCoordinate {
        private final int atlasX;
        private final int atlasY;
        
        public AtlasCoordinate(int atlasX, int atlasY) {
            this.atlasX = atlasX;
            this.atlasY = atlasY;
        }
        
        public int getAtlasX() { return atlasX; }
        public int getAtlasY() { return atlasY; }
        
        @Override
        public String toString() {
            return "AtlasCoordinate{" + atlasX + "," + atlasY + "}";
        }
        
        @Override
        public boolean equals(Object obj) {
            if (this == obj) return true;
            if (obj == null || getClass() != obj.getClass()) return false;
            AtlasCoordinate that = (AtlasCoordinate) obj;
            return atlasX == that.atlasX && atlasY == that.atlasY;
        }
        
        @Override
        public int hashCode() {
            return atlasX * 31 + atlasY;
        }
    }
    
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
     * Validates normalized UV coordinates.
     * Ensures coordinates are within the valid [0.0, 1.0] range.
     * 
     * @param u U coordinate (must be 0.0-1.0)
     * @param v V coordinate (must be 0.0-1.0)
     * @return true if coordinates are valid, false otherwise
     */
    public static boolean validateUVBounds(float u, float v) {
        return u >= 0.0f && u <= 1.0f && v >= 0.0f && v <= 1.0f;
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
     * Convert normalized UV coordinates back to grid coordinates.
     * Inverse of gridToUV for validation and conversion purposes.
     * 
     * @param u Normalized U coordinate (0.0-1.0)
     * @param v Normalized V coordinate (0.0-1.0)
     * @return AtlasCoordinate with grid coordinates, or null if invalid input
     */
    public static AtlasCoordinate uvToGrid(float u, float v) {
        if (!validateUVBounds(u, v)) {
            return null;
        }
        
        int atlasX = (int) Math.floor(u * GRID_SIZE);
        int atlasY = (int) Math.floor(v * GRID_SIZE);
        
        // Clamp to valid bounds to handle edge cases
        atlasX = Math.max(0, Math.min(GRID_SIZE - 1, atlasX));
        atlasY = Math.max(0, Math.min(GRID_SIZE - 1, atlasY));
        
        return new AtlasCoordinate(atlasX, atlasY);
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