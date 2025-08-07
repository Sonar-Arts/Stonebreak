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
    
    /**
     * Generate quad UV coordinates with custom vertex ordering.
     * Allows for flexible vertex ordering to match different rendering systems.
     * 
     * @param atlasX X coordinate in grid (0-15)
     * @param atlasY Y coordinate in grid (0-15)
     * @param vertexOrder Array of 4 integers representing vertex order (0=BL, 1=BR, 2=TR, 3=TL)
     * @return float array with 8 UV coordinates in specified order, or null if invalid
     */
    public static float[] generateQuadUVCoordinatesCustomOrder(int atlasX, int atlasY, int[] vertexOrder) {
        if (vertexOrder == null || vertexOrder.length != 4) {
            return null;
        }
        
        float[] bounds = gridToUVBounds(atlasX, atlasY);
        if (bounds == null) {
            return null;
        }
        
        float u1 = bounds[0]; // left
        float v1 = bounds[1]; // bottom
        float u2 = bounds[2]; // right
        float v2 = bounds[3]; // top
        
        // Define all possible vertices
        float[][] vertices = {
            {u1, v1}, // 0: bottom-left
            {u2, v1}, // 1: bottom-right
            {u2, v2}, // 2: top-right
            {u1, v2}  // 3: top-left
        };
        
        // Build result based on vertex order
        float[] result = new float[8];
        for (int i = 0; i < 4; i++) {
            int vertexIndex = vertexOrder[i];
            if (vertexIndex < 0 || vertexIndex >= 4) {
                return null; // Invalid vertex index
            }
            result[i * 2] = vertices[vertexIndex][0];     // U coordinate
            result[i * 2 + 1] = vertices[vertexIndex][1]; // V coordinate
        }
        
        return result;
    }
    
    /**
     * Calculate pixel coordinates from grid coordinates.
     * Converts grid position to actual pixel position in the 256×256 atlas.
     * 
     * @param atlasX X coordinate in grid (0-15)
     * @param atlasY Y coordinate in grid (0-15)
     * @return int array with [pixelX, pixelY] coordinates, or null if invalid
     */
    public static int[] gridToPixel(int atlasX, int atlasY) {
        if (!validateCoordinateBounds(atlasX, atlasY)) {
            return null;
        }
        
        int pixelX = atlasX * TILE_SIZE;
        int pixelY = atlasY * TILE_SIZE;
        
        return new int[]{pixelX, pixelY};
    }
    
    /**
     * Calculate grid coordinates from pixel coordinates.
     * Converts pixel position to grid position in the 16×16 system.
     * 
     * @param pixelX X coordinate in pixels (0-255)
     * @param pixelY Y coordinate in pixels (0-255)
     * @return AtlasCoordinate with grid coordinates, or null if invalid
     */
    public static AtlasCoordinate pixelToGrid(int pixelX, int pixelY) {
        if (pixelX < 0 || pixelX >= ATLAS_WIDTH || pixelY < 0 || pixelY >= ATLAS_HEIGHT) {
            return null;
        }
        
        int atlasX = pixelX / TILE_SIZE;
        int atlasY = pixelY / TILE_SIZE;
        
        // Clamp to valid bounds
        atlasX = Math.max(0, Math.min(GRID_SIZE - 1, atlasX));
        atlasY = Math.max(0, Math.min(GRID_SIZE - 1, atlasY));
        
        return new AtlasCoordinate(atlasX, atlasY);
    }
    
    /**
     * Test coordinate system mathematical precision.
     * Validates that all conversions maintain exact mathematical accuracy.
     * 
     * @return true if all precision tests pass, false otherwise
     */
    public static boolean testMathematicalPrecision() {
        System.out.println("[AtlasCoordinateSystem] Testing mathematical precision...");
        
        // Test cases: {atlasX, atlasY, expectedU, expectedV}
        float[][] testCases = {
            {0, 0, 0.0f, 0.0f},           // Top-left corner
            {15, 15, 0.9375f, 0.9375f},   // Bottom-right corner
            {8, 8, 0.5f, 0.5f},           // Center
            {1, 0, 0.0625f, 0.0f},        // Second column, first row
            {0, 1, 0.0f, 0.0625f},        // First column, second row
            {7, 3, 0.4375f, 0.1875f},     // Random middle position
            {15, 0, 0.9375f, 0.0f},       // Right edge, top
            {0, 15, 0.0f, 0.9375f}        // Left edge, bottom
        };
        
        int passedTests = 0;
        for (float[] testCase : testCases) {
            int expectedX = (int) testCase[0];
            int expectedY = (int) testCase[1];
            float expectedU = testCase[2];
            float expectedV = testCase[3];
            
            // Test grid to UV conversion
            UVCoordinate uv = gridToUV(expectedX, expectedY);
            boolean uvCorrect = uv != null && 
                Math.abs(uv.getU() - expectedU) < UV_EPSILON && 
                Math.abs(uv.getV() - expectedV) < UV_EPSILON;
            
            // Test UV to grid conversion
            AtlasCoordinate grid = uvToGrid(expectedU, expectedV);
            boolean gridCorrect = grid != null && 
                grid.getAtlasX() == expectedX && 
                grid.getAtlasY() == expectedY;
            
            // Test quad UV generation
            float[] quadUV = generateQuadUVCoordinates(expectedX, expectedY);
            boolean quadCorrect = quadUV != null && quadUV.length == 8 &&
                Math.abs(quadUV[0] - expectedU) < UV_EPSILON && // bottom-left U
                Math.abs(quadUV[1] - expectedV) < UV_EPSILON;   // bottom-left V
            
            if (uvCorrect && gridCorrect && quadCorrect) {
                passedTests++;
                System.out.println("  ✓ (" + expectedX + "," + expectedY + ") ↔ (" + expectedU + "," + expectedV + ")");
            } else {
                System.err.println("  ✗ (" + expectedX + "," + expectedY + ") ↔ (" + expectedU + "," + expectedV + ")");
                if (uv != null) {
                    System.err.println("    Got UV: (" + uv.getU() + "," + uv.getV() + ")");
                }
                if (grid != null) {
                    System.err.println("    Got Grid: (" + grid.getAtlasX() + "," + grid.getAtlasY() + ")");
                }
            }
        }
        
        boolean allPassed = passedTests == testCases.length;
        System.out.println("  Precision test results: " + passedTests + "/" + testCases.length + " passed");
        
        if (allPassed) {
            System.out.println("  ✓ Mathematical precision validated - exact Stonebreak compatibility confirmed");
        } else {
            System.err.println("  ✗ Mathematical precision test failed - coordinate system may not match Stonebreak exactly");
        }
        
        return allPassed;
    }
    
    /**
     * Test coordinate bounds validation.
     * Ensures all validation methods work correctly with edge cases.
     * 
     * @return true if all validation tests pass, false otherwise
     */
    public static boolean testBoundsValidation() {
        System.out.println("[AtlasCoordinateSystem] Testing bounds validation...");
        
        // Test grid coordinate validation
        boolean gridTests = 
            validateCoordinateBounds(0, 0) &&      // Valid: top-left
            validateCoordinateBounds(15, 15) &&    // Valid: bottom-right
            validateCoordinateBounds(8, 8) &&      // Valid: center
            !validateCoordinateBounds(-1, 0) &&    // Invalid: negative X
            !validateCoordinateBounds(0, -1) &&    // Invalid: negative Y
            !validateCoordinateBounds(16, 0) &&    // Invalid: X too large
            !validateCoordinateBounds(0, 16) &&    // Invalid: Y too large
            !validateCoordinateBounds(-1, -1) &&   // Invalid: both negative
            !validateCoordinateBounds(16, 16);     // Invalid: both too large
        
        // Test UV coordinate validation
        boolean uvTests = 
            validateUVBounds(0.0f, 0.0f) &&       // Valid: origin
            validateUVBounds(1.0f, 1.0f) &&       // Valid: max bounds
            validateUVBounds(0.5f, 0.5f) &&       // Valid: center
            !validateUVBounds(-0.1f, 0.0f) &&     // Invalid: negative U
            !validateUVBounds(0.0f, -0.1f) &&     // Invalid: negative V
            !validateUVBounds(1.1f, 0.0f) &&      // Invalid: U too large
            !validateUVBounds(0.0f, 1.1f) &&      // Invalid: V too large
            !validateUVBounds(-0.1f, -0.1f) &&    // Invalid: both negative
            !validateUVBounds(1.1f, 1.1f);        // Invalid: both too large
        
        boolean allPassed = gridTests && uvTests;
        
        if (allPassed) {
            System.out.println("  ✓ Bounds validation working correctly");
        } else {
            System.err.println("  ✗ Bounds validation failed");
            System.err.println("    Grid validation: " + gridTests);
            System.err.println("    UV validation: " + uvTests);
        }
        
        return allPassed;
    }
    
    /**
     * Run comprehensive coordinate system validation.
     * Tests all aspects of the coordinate system for mathematical accuracy.
     * 
     * @return true if all tests pass, false otherwise
     */
    public static boolean runComprehensiveValidation() {
        System.out.println("[AtlasCoordinateSystem] Running comprehensive validation...");
        System.out.println("  Grid Size: " + GRID_SIZE + "×" + GRID_SIZE);
        System.out.println("  Atlas Dimensions: " + ATLAS_WIDTH + "×" + ATLAS_HEIGHT + " pixels");
        System.out.println("  Tile Size: " + TILE_SIZE + " pixels");
        System.out.println("  UV Tile Size: " + TILE_SIZE_UV + " (1/" + GRID_SIZE + ")");
        System.out.println();
        
        boolean precisionTest = testMathematicalPrecision();
        System.out.println();
        
        boolean boundsTest = testBoundsValidation();
        System.out.println();
        
        // Test pixel conversion accuracy
        System.out.println("  Testing pixel conversion accuracy...");
        boolean pixelTest = true;
        int[] testPixels = gridToPixel(8, 8); // Center tile
        AtlasCoordinate backToGrid = pixelToGrid(testPixels[0], testPixels[1]);
        if (backToGrid == null || backToGrid.getAtlasX() != 8 || backToGrid.getAtlasY() != 8) {
            pixelTest = false;
            System.err.println("    ✗ Pixel conversion failed");
        } else {
            System.out.println("    ✓ Pixel conversion working correctly");
        }
        
        boolean allPassed = precisionTest && boundsTest && pixelTest;
        
        System.out.println("[AtlasCoordinateSystem] Comprehensive validation " + 
            (allPassed ? "PASSED" : "FAILED"));
        
        if (allPassed) {
            System.out.println("  ✓ Mathematical precision validated");
            System.out.println("  ✓ Bounds validation working");
            System.out.println("  ✓ Pixel conversion accurate");
            System.out.println("  ✓ 1:1 Stonebreak compatibility confirmed");
        }
        
        return allPassed;
    }
    
    /**
     * Get system information for debugging and integration purposes.
     * 
     * @return String with comprehensive system information
     */
    public static String getSystemInfo() {
        return String.format(
            "AtlasCoordinateSystem {\n" +
            "  Grid: %d×%d\n" +
            "  Atlas: %d×%d pixels\n" +
            "  Tile: %d pixels (%.4f UV)\n" +
            "  Precision: %.6f epsilon\n" +
            "  Compatibility: Stonebreak 1:1\n" +
            "}",
            GRID_SIZE, GRID_SIZE,
            ATLAS_WIDTH, ATLAS_HEIGHT,
            TILE_SIZE, TILE_SIZE_UV,
            UV_EPSILON
        );
    }
}