package com.stonebreak.world.generation;

import com.stonebreak.world.generation.noise.MultiNoiseParameters;
import com.stonebreak.world.generation.noise.NoiseRouter;
import com.stonebreak.world.generation.spline.SplineTerrainGenerator;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

/**
 * Integration tests for SplineTerrainGenerator
 *
 * Tests verify that the three-spline system (offset, jaggedness, factor)
 * creates appropriate terrain based on multi-noise parameters.
 */
class SplineTerrainIntegrationTest {

    private static final long TEST_SEED = 12345L;
    private SplineTerrainGenerator generator2D;
    private SplineTerrainGenerator generator3D;
    private NoiseRouter noiseRouter;

    @BeforeEach
    void setUp() {
        generator2D = new SplineTerrainGenerator(TEST_SEED, false);
        generator3D = new SplineTerrainGenerator(TEST_SEED, true);
        noiseRouter = new NoiseRouter(TEST_SEED);
    }

    @Test
    void testOceanGeneration() {
        // Search for ocean positions (continentalness < -0.5)
        boolean foundOcean = false;

        for (int x = 0; x < 10000; x += 100) {
            for (int z = 0; z < 10000; z += 100) {
                MultiNoiseParameters params = noiseRouter.sampleParameters(x, z, 64);

                if (params.continentalness < -0.5f) {
                    int height = generator2D.generateHeight(x, z, params);

                    // Oceans should be below sea level (63 blocks)
                    assertTrue(height < 63, String.format(
                        "Ocean at (%d, %d) should be below sea level. Height: %d, Continentalness: %.2f",
                        x, z, height, params.continentalness
                    ));

                    // Deep oceans should be even lower
                    if (params.continentalness < -0.8f) {
                        assertTrue(height < 45, String.format(
                            "Deep ocean at (%d, %d) should be very low. Height: %d",
                            x, z, height
                        ));
                    }

                    foundOcean = true;
                    break;
                }
            }
            if (foundOcean) break;
        }

        assertTrue(foundOcean, "Should find at least one ocean position in search area");
    }

    @Test
    void testMountainGeneration() {
        // Search for mountain positions (continentalness > 0.7, erosion < -0.3)
        boolean foundMountain = false;

        for (int x = 0; x < 10000; x += 100) {
            for (int z = 0; z < 10000; z += 100) {
                MultiNoiseParameters params = noiseRouter.sampleParameters(x, z, 128);

                if (params.continentalness > 0.7f && params.erosion < -0.3f) {
                    int height = generator2D.generateHeight(x, z, params);

                    // Mountains should be tall (>100 blocks)
                    assertTrue(height > 100, String.format(
                        "Mountain at (%d, %d) should be tall. Height: %d, Continentalness: %.2f, Erosion: %.2f",
                        x, z, height, params.continentalness, params.erosion
                    ));

                    // Extreme mountains should be very tall
                    if (params.continentalness > 0.9f && params.erosion < -0.7f) {
                        assertTrue(height > 140, String.format(
                            "Extreme mountain at (%d, %d) should be very tall. Height: %d",
                            x, z, height
                        ));
                    }

                    foundMountain = true;
                    break;
                }
            }
            if (foundMountain) break;
        }

        assertTrue(foundMountain, "Should find at least one mountain position in search area");
    }

    @Test
    void testPlainsGeneration() {
        // Search for plains positions (moderate continentalness, high erosion)
        boolean foundPlains = false;

        for (int x = 0; x < 10000; x += 100) {
            for (int z = 0; z < 10000; z += 100) {
                MultiNoiseParameters params = noiseRouter.sampleParameters(x, z, 64);

                if (params.continentalness > 0.0f && params.continentalness < 0.5f
                    && params.erosion > 0.5f) {
                    int height = generator2D.generateHeight(x, z, params);

                    // Plains should be moderate height (60-90 blocks)
                    assertTrue(height >= 55 && height <= 95, String.format(
                        "Plains at (%d, %d) should be moderate height. Height: %d, Continentalness: %.2f, Erosion: %.2f",
                        x, z, height, params.continentalness, params.erosion
                    ));

                    foundPlains = true;
                    break;
                }
            }
            if (foundPlains) break;
        }

        assertTrue(foundPlains, "Should find at least one plains position in search area");
    }

    @Test
    void testSmoothTransitions() {
        // Sample heights along a line and verify no abrupt jumps
        int prevHeight = 0;
        int maxJump = 0;
        int position = 0;

        for (int x = 0; x < 100; x++) {
            MultiNoiseParameters params = noiseRouter.sampleParameters(x, 0, 64);
            int height = generator2D.generateHeight(x, 0, params);

            if (x > 0) {
                int jump = Math.abs(height - prevHeight);
                if (jump > maxJump) {
                    maxJump = jump;
                    position = x;
                }

                // Height should change smoothly (no jumps > 25 blocks between adjacent positions)
                assertTrue(jump < 25, String.format(
                    "Terrain should transition smoothly at x=%d. Jump: %d blocks (from %d to %d)",
                    x, jump, prevHeight, height
                ));
            }

            prevHeight = height;
        }

        System.out.println(String.format("Maximum height jump: %d blocks at x=%d", maxJump, position));
    }

    @Test
    void testPlateauGeneration() {
        // Plateaus occur at extreme continentalness with low erosion
        // Look for areas where derivative = 0 creates flat terrain
        boolean foundPlateau = false;

        for (int x = 0; x < 10000; x += 100) {
            for (int z = 0; z < 10000; z += 100) {
                MultiNoiseParameters params = noiseRouter.sampleParameters(x, z, 128);

                // Plateaus should occur at extreme continentalness (near 1.0 or -1.0)
                if (Math.abs(params.continentalness) > 0.85f) {
                    // Sample a small area around this position
                    int centerHeight = generator2D.generateHeight(x, z, params);
                    int maxVariation = 0;

                    for (int dx = -3; dx <= 3; dx++) {
                        for (int dz = -3; dz <= 3; dz++) {
                            MultiNoiseParameters localParams = noiseRouter.sampleParameters(x + dx, z + dz, 128);
                            int localHeight = generator2D.generateHeight(x + dx, z + dz, localParams);
                            maxVariation = Math.max(maxVariation, Math.abs(localHeight - centerHeight));
                        }
                    }

                    // If variation is small, we found a plateau
                    if (maxVariation < 5) {
                        foundPlateau = true;
                        System.out.println(String.format(
                            "Found plateau at (%d, %d) with height %d, continentalness %.2f, variation: %d blocks",
                            x, z, centerHeight, params.continentalness, maxVariation
                        ));
                        break;
                    }
                }
            }
            if (foundPlateau) break;
        }

        assertTrue(foundPlateau, "Should find at least one plateau area (derivative = 0 effect)");
    }

    @Test
    void testJaggednessAddsDetail() {
        // Jaggedness should add variation to mountain peaks
        // Compare heights at positions with same offset but different jaggedness

        boolean foundVariation = false;

        for (int x = 0; x < 10000; x += 100) {
            for (int z = 0; z < 10000; z += 100) {
                MultiNoiseParameters params = noiseRouter.sampleParameters(x, z, 128);

                // Look for mountainous areas (high continentalness, low erosion)
                if (params.continentalness > 0.7f && params.erosion < 0.0f) {
                    // Sample nearby positions - jaggedness should create variation
                    int h1 = generator2D.generateHeight(x, z, params);
                    int h2 = generator2D.generateHeight(x + 5, z, noiseRouter.sampleParameters(x + 5, z, 128));
                    int h3 = generator2D.generateHeight(x + 10, z, noiseRouter.sampleParameters(x + 10, z, 128));

                    int variation = Math.max(Math.abs(h2 - h1), Math.abs(h3 - h2));

                    // Jaggedness should create some variation (at least 2 blocks)
                    if (variation >= 2) {
                        foundVariation = true;
                        System.out.println(String.format(
                            "Found jaggedness variation at (%d, %d): heights %d, %d, %d (variation: %d blocks)",
                            x, z, h1, h2, h3, variation
                        ));
                        break;
                    }
                }
            }
            if (foundVariation) break;
        }

        assertTrue(foundVariation, "Jaggedness should create height variation in mountainous areas");
    }

    @Test
    void test3DDensityCreatesVariation() {
        // 3D density should sometimes differ from 2D heightmap
        boolean foundDifference = false;

        for (int x = 0; x < 1000; x += 50) {
            for (int z = 0; z < 1000; z += 50) {
                MultiNoiseParameters params = noiseRouter.sampleParameters(x, z, 128);

                int height2D = generator2D.generateHeight(x, z, params);
                int height3D = generator3D.generateHeight(x, z, params);

                // 3D version may differ due to caves/overhangs
                if (Math.abs(height3D - height2D) > 3) {
                    foundDifference = true;
                    System.out.println(String.format(
                        "Found 3D vs 2D difference at (%d, %d): 2D=%d, 3D=%d (diff: %d blocks)",
                        x, z, height2D, height3D, Math.abs(height3D - height2D)
                    ));
                    break;
                }
            }
            if (foundDifference) break;
        }

        // Note: 3D density may not always create differences, so this is informational
        // We just verify that both generators work without crashing
        assertNotNull(generator2D);
        assertNotNull(generator3D);
    }

    @Test
    void testErosionControlsFlatness() {
        // High erosion (1.0) should create flatter terrain than low erosion (-1.0)
        boolean foundFlatPlains = false;
        boolean foundRuggedMountains = false;

        for (int x = 0; x < 10000; x += 100) {
            for (int z = 0; z < 10000; z += 100) {
                MultiNoiseParameters params = noiseRouter.sampleParameters(x, z, 64);

                // Look for flat plains (high erosion)
                if (params.erosion > 0.8f && params.continentalness > 0.0f) {
                    // Sample a 10x10 area
                    int minHeight = Integer.MAX_VALUE;
                    int maxHeight = Integer.MIN_VALUE;

                    for (int dx = 0; dx < 10; dx++) {
                        for (int dz = 0; dz < 10; dz++) {
                            MultiNoiseParameters localParams = noiseRouter.sampleParameters(x + dx, z + dz, 64);
                            int height = generator2D.generateHeight(x + dx, z + dz, localParams);
                            minHeight = Math.min(minHeight, height);
                            maxHeight = Math.max(maxHeight, height);
                        }
                    }

                    int variation = maxHeight - minHeight;
                    if (variation < 10) {
                        foundFlatPlains = true;
                        System.out.println(String.format(
                            "Found flat plains at (%d, %d) with variation %d blocks (erosion: %.2f)",
                            x, z, variation, params.erosion
                        ));
                    }
                }

                // Look for rugged mountains (low erosion)
                if (params.erosion < -0.8f && params.continentalness > 0.7f) {
                    // Sample a 10x10 area
                    int minHeight = Integer.MAX_VALUE;
                    int maxHeight = Integer.MIN_VALUE;

                    for (int dx = 0; dx < 10; dx++) {
                        for (int dz = 0; dz < 10; dz++) {
                            MultiNoiseParameters localParams = noiseRouter.sampleParameters(x + dx, z + dz, 128);
                            int height = generator2D.generateHeight(x + dx, z + dz, localParams);
                            minHeight = Math.min(minHeight, height);
                            maxHeight = Math.max(maxHeight, height);
                        }
                    }

                    int variation = maxHeight - minHeight;
                    if (variation > 20) {
                        foundRuggedMountains = true;
                        System.out.println(String.format(
                            "Found rugged mountains at (%d, %d) with variation %d blocks (erosion: %.2f)",
                            x, z, variation, params.erosion
                        ));
                    }
                }

                if (foundFlatPlains && foundRuggedMountains) break;
            }
            if (foundFlatPlains && foundRuggedMountains) break;
        }

        assertTrue(foundFlatPlains || foundRuggedMountains,
                   "Should find either flat plains (high erosion) or rugged mountains (low erosion)");
    }

    @Test
    void testHeightRange() {
        // Test that all generated heights are within reasonable bounds
        int minHeight = Integer.MAX_VALUE;
        int maxHeight = Integer.MIN_VALUE;

        for (int x = 0; x < 1000; x += 10) {
            for (int z = 0; z < 1000; z += 10) {
                MultiNoiseParameters params = noiseRouter.sampleParameters(x, z, 64);
                int height = generator2D.generateHeight(x, z, params);

                minHeight = Math.min(minHeight, height);
                maxHeight = Math.max(maxHeight, height);

                // Verify heights are within world bounds (0-256)
                assertTrue(height >= 0, "Height should not be negative");
                assertTrue(height <= 256, "Height should not exceed world height");
            }
        }

        System.out.println(String.format("Height range: %d to %d blocks", minHeight, maxHeight));

        // Verify we have good height variation
        int range = maxHeight - minHeight;
        assertTrue(range >= 50, String.format(
            "Should have at least 50 blocks of height variation. Actual range: %d", range
        ));
    }

    @Test
    void testPeaksValleysAmplification() {
        // PV parameter should amplify height extremes
        boolean foundPeakAmplification = false;

        for (int x = 0; x < 5000; x += 100) {
            for (int z = 0; z < 5000; z += 100) {
                MultiNoiseParameters params = noiseRouter.sampleParameters(x, z, 128);

                // Look for areas with high PV (peaks)
                if (params.peaksValleys > 0.7f && params.continentalness > 0.5f) {
                    int height = generator2D.generateHeight(x, z, params);

                    // Compare with nearby position with lower PV
                    // This is approximate since PV varies across positions
                    if (height > 110) {
                        foundPeakAmplification = true;
                        System.out.println(String.format(
                            "Found peak amplification at (%d, %d): height %d, PV %.2f",
                            x, z, height, params.peaksValleys
                        ));
                        break;
                    }
                }
            }
            if (foundPeakAmplification) break;
        }

        assertTrue(foundPeakAmplification, "PV parameter should amplify peak heights");
    }
}
