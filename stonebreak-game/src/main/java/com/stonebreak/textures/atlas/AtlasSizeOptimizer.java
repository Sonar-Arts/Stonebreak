package com.stonebreak.textures.atlas;

import java.util.*;

/**
 * Calculates optimal atlas dimensions based on texture count and content.
 * Ensures efficient space utilization while maintaining GPU-friendly power-of-2 sizes.
 */
public class AtlasSizeOptimizer {
    
    // Size constraints
    public static final int MIN_ATLAS_SIZE = 256;
    public static final int MAX_ATLAS_SIZE = 4096;
    public static final int TEXTURE_TILE_SIZE = 16; // Each texture is 16x16 pixels
    
    // Utilization targets
    public static final double MIN_UTILIZATION = 0.7; // 70% minimum utilization
    public static final double TARGET_UTILIZATION = 0.85; // 85% target utilization
    
    /**
     * Represents an optimized atlas size configuration.
     */
    public static class AtlasSize {
        public final int width;
        public final int height;
        public final int tilesPerRow;
        public final int tilesPerColumn;
        public final int totalTileCapacity;
        public final int usedTiles;
        public final double utilization;
        public final boolean isSquare;
        public final boolean isPowerOfTwo;
        
        public AtlasSize(int width, int height, int usedTiles) {
            this.width = width;
            this.height = height;
            this.tilesPerRow = width / TEXTURE_TILE_SIZE;
            this.tilesPerColumn = height / TEXTURE_TILE_SIZE;
            this.totalTileCapacity = tilesPerRow * tilesPerColumn;
            this.usedTiles = usedTiles;
            this.utilization = (double) usedTiles / totalTileCapacity;
            this.isSquare = (width == height);
            this.isPowerOfTwo = isPowerOfTwo(width) && isPowerOfTwo(height);
        }
        
        @Override
        public String toString() {
            return String.format("AtlasSize{%dx%d, tiles=%dx%d, utilization=%.1f%%, square=%s, pow2=%s}", 
                               width, height, tilesPerRow, tilesPerColumn, 
                               utilization * 100, isSquare, isPowerOfTwo);
        }
    }
    
    /**
     * Texture requirement information for atlas sizing.
     */
    public static class TextureRequirements {
        public final int uniformBlockTextures;    // 16x16 block textures (1 tile each)
        public final int cubeCrossBlockTextures;  // 64x48 block textures (6 tiles each)  
        public final int itemTextures;            // 16x16 item textures (1 tile each)
        public final int errorTextures;           // Error textures (1 tile each)
        public final int totalTilesNeeded;
        
        public TextureRequirements(int uniformBlocks, int cubeCrossBlocks, int items, int errors) {
            this.uniformBlockTextures = uniformBlocks;
            this.cubeCrossBlockTextures = cubeCrossBlocks;
            this.itemTextures = items;
            this.errorTextures = errors;
            
            // Calculate total tiles needed
            this.totalTilesNeeded = uniformBlocks +           // 1 tile each
                                  (cubeCrossBlocks * 6) +     // 6 tiles each (TOP, BOTTOM, N, S, E, W)
                                  items +                     // 1 tile each
                                  errors;                     // 1 tile each
        }
        
        @Override
        public String toString() {
            return String.format("TextureRequirements{uniform=%d, cubeCross=%d, items=%d, error=%d, totalTiles=%d}", 
                               uniformBlockTextures, cubeCrossBlockTextures, itemTextures, errorTextures, totalTilesNeeded);
        }
    }
    
    /**
     * Calculates the optimal atlas size for given texture requirements.
     * @param requirements The texture requirements
     * @return Optimized AtlasSize configuration
     */
    public static AtlasSize calculateOptimalSize(TextureRequirements requirements) {
        if (requirements.totalTilesNeeded == 0) {
            return new AtlasSize(MIN_ATLAS_SIZE, MIN_ATLAS_SIZE, 0);
        }
        
        // Generate candidate sizes
        List<AtlasSize> candidates = generateCandidateSizes(requirements.totalTilesNeeded);
        
        // Score and select the best candidate
        AtlasSize bestSize = null;
        double bestScore = Double.NEGATIVE_INFINITY;
        
        for (AtlasSize candidate : candidates) {
            double score = scoreAtlasSize(candidate, requirements);
            if (score > bestScore) {
                bestScore = score;
                bestSize = candidate;
            }
        }
        
        System.out.println("AtlasSizeOptimizer: Selected " + bestSize + " (score: " + String.format("%.2f", bestScore) + ")");
        return bestSize;
    }
    
    /**
     * Generates candidate atlas sizes that could fit the required tiles.
     * @param tilesNeeded Number of tiles that need to fit
     * @return List of potential AtlasSize configurations
     */
    private static List<AtlasSize> generateCandidateSizes(int tilesNeeded) {
        List<AtlasSize> candidates = new ArrayList<>();
        
        // Generate power-of-2 sizes from MIN to MAX
        for (int size = MIN_ATLAS_SIZE; size <= MAX_ATLAS_SIZE; size *= 2) {
            // Square atlas
            AtlasSize square = new AtlasSize(size, size, tilesNeeded);
            if (square.totalTileCapacity >= tilesNeeded) {
                candidates.add(square);
            }
            
            // Rectangular variants (2:1 and 1:2 ratios)
            if (size < MAX_ATLAS_SIZE) {
                AtlasSize wide = new AtlasSize(size * 2, size, tilesNeeded);
                if (wide.totalTileCapacity >= tilesNeeded && wide.width <= MAX_ATLAS_SIZE) {
                    candidates.add(wide);
                }
                
                AtlasSize tall = new AtlasSize(size, size * 2, tilesNeeded);
                if (tall.totalTileCapacity >= tilesNeeded && tall.height <= MAX_ATLAS_SIZE) {
                    candidates.add(tall);
                }
            }
        }
        
        // If no power-of-2 size works, try some non-power-of-2 options
        if (candidates.isEmpty()) {
            System.err.println("AtlasSizeOptimizer: Warning - no power-of-2 size fits " + tilesNeeded + " tiles");
            
            // Try some larger non-power-of-2 sizes as fallback
            int[] fallbackSizes = {3072, 5120, 6144, 8192};
            for (int size : fallbackSizes) {
                if (size > MAX_ATLAS_SIZE) break;
                
                AtlasSize fallback = new AtlasSize(size, size, tilesNeeded);
                if (fallback.totalTileCapacity >= tilesNeeded) {
                    candidates.add(fallback);
                    break; // Use the first one that fits
                }
            }
        }
        
        return candidates;
    }
    
    /**
     * Scores an atlas size based on multiple criteria.
     * Higher scores are better.
     * @param atlasSize The atlas size to score
     * @param requirements The texture requirements
     * @return Score value (higher is better)
     */
    private static double scoreAtlasSize(AtlasSize atlasSize, TextureRequirements requirements) {
        double score = 0.0;
        
        // Utilization score (prefer 80-90% utilization)
        double utilizationScore;
        if (atlasSize.utilization < MIN_UTILIZATION) {
            utilizationScore = atlasSize.utilization / MIN_UTILIZATION * 0.5; // Penalty for low utilization
        } else if (atlasSize.utilization <= TARGET_UTILIZATION) {
            utilizationScore = 1.0; // Optimal range
        } else {
            // Slight penalty for over-utilization (less room for growth)
            utilizationScore = 1.0 - (atlasSize.utilization - TARGET_UTILIZATION) * 0.5;
        }
        score += utilizationScore * 100; // Weight: 100 points
        
        // Size preference (smaller is generally better for memory)
        double sizeScore = 1.0 / (atlasSize.width * atlasSize.height / 1000000.0); // Inverse size score
        score += sizeScore * 20; // Weight: 20 points
        
        // Power-of-2 bonus (GPU friendly)
        if (atlasSize.isPowerOfTwo) {
            score += 30; // Weight: 30 points
        }
        
        // Square preference bonus (often more efficient)
        if (atlasSize.isSquare) {
            score += 10; // Weight: 10 points
        }
        
        // Penalty for very large atlases
        if (atlasSize.width > 2048 || atlasSize.height > 2048) {
            score -= 20; // Penalty for large sizes
        }
        
        // Bonus for common GPU-friendly sizes
        if ((atlasSize.width == 512 && atlasSize.height == 512) ||
            (atlasSize.width == 1024 && atlasSize.height == 1024)) {
            score += 15; // Common sizes bonus
        }
        
        return score;
    }
    
    /**
     * Estimates texture requirements from file lists.
     * @param blockTextures Array of block texture filenames
     * @param itemTextures Array of item texture filenames
     * @return TextureRequirements object with estimated counts
     */
    public static TextureRequirements estimateRequirements(String[] blockTextures, String[] itemTextures) {
        int uniformBlocks = 0;
        int cubeCrossBlocks = 0;
        int errorTextures = 0;
        
        // Analyze block textures
        for (String fileName : blockTextures) {
            if (fileName.equals("Errockson.gif")) {
                errorTextures++;
            } else if (fileName.contains("_cube") || fileName.contains("_cross") || 
                      isKnownCubeCrossTexture(fileName)) {
                cubeCrossBlocks++;
            } else {
                uniformBlocks++;
            }
        }
        
        // Items are always uniform 16x16
        int items = itemTextures.length;
        
        return new TextureRequirements(uniformBlocks, cubeCrossBlocks, items, errorTextures);
    }
    
    /**
     * Checks if a filename is known to be a cube cross texture.
     * This is a heuristic based on known texture patterns.
     * @param fileName The texture filename
     * @return true if likely to be cube cross format
     */
    private static boolean isKnownCubeCrossTexture(String fileName) {
        // Most block textures that need different faces would be cube cross
        // For now, assume most blocks are uniform unless specifically marked
        String[] cubeCrossPatterns = {
            "log_", "wood_log", "sandstone_", "workbench"
        };
        
        for (String pattern : cubeCrossPatterns) {
            if (fileName.contains(pattern)) {
                return true;
            }
        }
        
        return false;
    }
    
    /**
     * Checks if a number is a power of 2.
     * @param n The number to check
     * @return true if n is a power of 2
     */
    public static boolean isPowerOfTwo(int n) {
        return n > 0 && (n & (n - 1)) == 0;
    }
    
    /**
     * Gets the next power of 2 greater than or equal to n.
     * @param n The input number
     * @return Next power of 2
     */
    public static int nextPowerOfTwo(int n) {
        if (n <= 0) return 1;
        if (isPowerOfTwo(n)) return n;
        
        int power = 1;
        while (power < n) {
            power <<= 1;
        }
        return power;
    }
    
    /**
     * Calculates atlas size for a simple tile count (legacy compatibility).
     * @param tileCount Number of tiles needed
     * @return Optimal square atlas size
     */
    public static int calculateLegacyAtlasSize(int tileCount) {
        if (tileCount <= 0) return MIN_ATLAS_SIZE;
        
        // Calculate minimum dimension needed
        int tilesPerSide = (int) Math.ceil(Math.sqrt(tileCount));
        int pixelsPerSide = tilesPerSide * TEXTURE_TILE_SIZE;
        
        // Round up to next power of 2
        int atlasSize = nextPowerOfTwo(pixelsPerSide);
        
        // Ensure within bounds
        atlasSize = Math.max(atlasSize, MIN_ATLAS_SIZE);
        atlasSize = Math.min(atlasSize, MAX_ATLAS_SIZE);
        
        return atlasSize;
    }
    
    /**
     * Prints analysis of texture requirements and sizing recommendations.
     * @param requirements The texture requirements
     */
    public static void printSizingAnalysis(TextureRequirements requirements) {
        System.out.println("=== Atlas Size Optimization Analysis ===");
        System.out.println(requirements);
        
        AtlasSize optimal = calculateOptimalSize(requirements);
        System.out.println("Recommended: " + optimal);
        
        // Show some alternatives
        System.out.println("\nAlternative sizes:");
        List<AtlasSize> candidates = generateCandidateSizes(requirements.totalTilesNeeded);
        candidates.sort((a, b) -> Double.compare(scoreAtlasSize(b, requirements), scoreAtlasSize(a, requirements)));
        
        for (int i = 0; i < Math.min(3, candidates.size()); i++) {
            AtlasSize candidate = candidates.get(i);
            double score = scoreAtlasSize(candidate, requirements);
            System.out.println("  " + candidate + " (score: " + String.format("%.1f", score) + ")");
        }
        
        System.out.println("========================================");
    }
}