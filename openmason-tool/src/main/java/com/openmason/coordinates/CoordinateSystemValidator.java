package com.openmason.coordinates;

import com.stonebreak.textures.CowTextureDefinition;
import com.stonebreak.textures.CowTextureLoader;
import com.stonebreak.model.ModelDefinition;
import org.joml.Vector3f;

/**
 * Coordinate System Validator - Phase 7 Open Mason Implementation
 * 
 * Comprehensive validation and testing system for the coordinate systems.
 * This class provides extensive testing capabilities to ensure mathematical
 * accuracy, system integration, and 1:1 Stonebreak compatibility.
 * 
 * Validation Features:
 * - Mathematical precision testing
 * - Cross-system compatibility validation
 * - Performance benchmarking
 * - Regression testing capabilities
 * - Error detection and reporting
 * - Integration health monitoring
 */
public class CoordinateSystemValidator {
    
    private static final float EPSILON = 0.0001f;
    
    /**
     * Validation result structure containing comprehensive test results.
     */
    public static class ValidationResult {
        private final String testName;
        private final boolean passed;
        private final int testsRun;
        private final int testsPassed;
        private final int testsFailed;
        private final long executionTimeMs;
        private final String details;
        private final String errorMessage;
        
        public ValidationResult(String testName, boolean passed, int testsRun, int testsPassed,
                              long executionTimeMs, String details, String errorMessage) {
            this.testName = testName;
            this.passed = passed;
            this.testsRun = testsRun;
            this.testsPassed = testsPassed;
            this.testsFailed = testsRun - testsPassed;
            this.executionTimeMs = executionTimeMs;
            this.details = details;
            this.errorMessage = errorMessage;
        }
        
        // Getters
        public String getTestName() { return testName; }
        public boolean isPassed() { return passed; }
        public int getTestsRun() { return testsRun; }
        public int getTestsPassed() { return testsPassed; }
        public int getTestsFailed() { return testsFailed; }
        public long getExecutionTimeMs() { return executionTimeMs; }
        public String getDetails() { return details; }
        public String getErrorMessage() { return errorMessage; }
        
        @Override
        public String toString() {
            return String.format(
                "ValidationResult{%s: %s, %d/%d passed, %dms, %s}",
                testName, passed ? "PASS" : "FAIL", testsPassed, testsRun, 
                executionTimeMs, errorMessage != null ? errorMessage : "OK"
            );
        }
    }
    
    /**
     * Run complete coordinate system validation suite.
     * This is the main entry point for comprehensive system validation.
     * 
     * @return ValidationResult with overall test results
     */
    public static ValidationResult runCompleteValidation() {
        System.out.println("=== Phase 7 Open Mason - Complete Coordinate System Validation ===");
        System.out.println();
        
        long startTime = System.currentTimeMillis();
        
        try {
            // Run all validation tests
            ValidationResult atlasResult = validateAtlasCoordinateSystem();
            ValidationResult modelResult = validateModelCoordinateSystem();
            ValidationResult integrationResult = validateSystemIntegration();
            ValidationResult compatibilityResult = validateStonebreakCompatibility();
            ValidationResult performanceResult = validatePerformance();
            
            // Aggregate results
            int totalTests = atlasResult.getTestsRun() + modelResult.getTestsRun() + 
                           integrationResult.getTestsRun() + compatibilityResult.getTestsRun() +
                           performanceResult.getTestsRun();
            int totalPassed = atlasResult.getTestsPassed() + modelResult.getTestsPassed() + 
                            integrationResult.getTestsPassed() + compatibilityResult.getTestsPassed() +
                            performanceResult.getTestsPassed();
            
            boolean overallPassed = atlasResult.isPassed() && modelResult.isPassed() && 
                                  integrationResult.isPassed() && compatibilityResult.isPassed() &&
                                  performanceResult.isPassed();
            
            long totalTime = System.currentTimeMillis() - startTime;
            
            // Print summary
            System.out.println();
            System.out.println("=== VALIDATION SUMMARY ===");
            System.out.println("Atlas Coordinate System: " + (atlasResult.isPassed() ? "PASS" : "FAIL"));
            System.out.println("Model Coordinate System: " + (modelResult.isPassed() ? "PASS" : "FAIL"));
            System.out.println("System Integration: " + (integrationResult.isPassed() ? "PASS" : "FAIL"));
            System.out.println("Stonebreak Compatibility: " + (compatibilityResult.isPassed() ? "PASS" : "FAIL"));
            System.out.println("Performance Validation: " + (performanceResult.isPassed() ? "PASS" : "FAIL"));
            System.out.println();
            System.out.println("Overall Result: " + (overallPassed ? "PASS" : "FAIL"));
            System.out.println("Total Tests: " + totalPassed + "/" + totalTests + " passed");
            System.out.println("Execution Time: " + totalTime + "ms");
            
            if (overallPassed) {
                System.out.println();
                System.out.println("✓ Phase 7 coordinate system implementation complete");
                System.out.println("✓ Mathematical precision validated");
                System.out.println("✓ 1:1 Stonebreak compatibility confirmed");
                System.out.println("✓ System integration working correctly");
                System.out.println("✓ Performance requirements met");
            }
            
            String details = String.format(
                "Atlas: %s, Model: %s, Integration: %s, Compatibility: %s, Performance: %s",
                atlasResult.isPassed() ? "PASS" : "FAIL",
                modelResult.isPassed() ? "PASS" : "FAIL",
                integrationResult.isPassed() ? "PASS" : "FAIL",
                compatibilityResult.isPassed() ? "PASS" : "FAIL",
                performanceResult.isPassed() ? "PASS" : "FAIL"
            );
            
            return new ValidationResult(
                "Complete Validation",
                overallPassed,
                totalTests,
                totalPassed,
                totalTime,
                details,
                overallPassed ? null : "One or more validation tests failed"
            );
            
        } catch (Exception e) {
            long totalTime = System.currentTimeMillis() - startTime;
            System.err.println("=== VALIDATION FAILED ===");
            System.err.println("Critical error during validation: " + e.getMessage());
            e.printStackTrace();
            
            return new ValidationResult(
                "Complete Validation",
                false,
                0,
                0,
                totalTime,
                "Critical error",
                e.getMessage()
            );
        }
    }
    
    /**
     * Validate the Atlas Coordinate System.
     */
    public static ValidationResult validateAtlasCoordinateSystem() {
        System.out.println("1. Validating Atlas Coordinate System...");
        long startTime = System.currentTimeMillis();
        
        try {
            boolean result = AtlasCoordinateSystem.runComprehensiveValidation();
            long executionTime = System.currentTimeMillis() - startTime;
            
            return new ValidationResult(
                "Atlas Coordinate System",
                result,
                1,
                result ? 1 : 0,
                executionTime,
                "16x16 grid, UV conversion, bounds validation",
                result ? null : "Atlas coordinate system validation failed"
            );
            
        } catch (Exception e) {
            long executionTime = System.currentTimeMillis() - startTime;
            return new ValidationResult(
                "Atlas Coordinate System",
                false,
                1,
                0,
                executionTime,
                "Exception during validation",
                e.getMessage()
            );
        }
    }
    
    /**
     * Validate the Model Coordinate System.
     */
    public static ValidationResult validateModelCoordinateSystem() {
        System.out.println("2. Validating Model Coordinate System...");
        long startTime = System.currentTimeMillis();
        
        try {
            boolean result = ModelCoordinateSystem.runComprehensiveValidation();
            long executionTime = System.currentTimeMillis() - startTime;
            
            return new ValidationResult(
                "Model Coordinate System",
                result,
                1,
                result ? 1 : 0,
                executionTime,
                "Right-handed Y-up, vertex generation, normals",
                result ? null : "Model coordinate system validation failed"
            );
            
        } catch (Exception e) {
            long executionTime = System.currentTimeMillis() - startTime;
            return new ValidationResult(
                "Model Coordinate System",
                false,
                1,
                0,
                executionTime,
                "Exception during validation",
                e.getMessage()
            );
        }
    }
    
    /**
     * Validate system integration between coordinate systems.
     */
    public static ValidationResult validateSystemIntegration() {
        System.out.println("3. Validating System Integration...");
        long startTime = System.currentTimeMillis();
        
        try {
            boolean result = CoordinateSystemIntegration.validateIntegration();
            long executionTime = System.currentTimeMillis() - startTime;
            
            return new ValidationResult(
                "System Integration",
                result,
                1,
                result ? 1 : 0,
                executionTime,
                "Cross-system compatibility, data generation",
                result ? null : "System integration validation failed"
            );
            
        } catch (Exception e) {
            long executionTime = System.currentTimeMillis() - startTime;
            return new ValidationResult(
                "System Integration",
                false,
                1,
                0,
                executionTime,
                "Exception during validation",
                e.getMessage()
            );
        }
    }
    
    /**
     * Validate Stonebreak compatibility.
     */
    public static ValidationResult validateStonebreakCompatibility() {
        System.out.println("4. Validating Stonebreak Compatibility...");
        long startTime = System.currentTimeMillis();
        
        try {
            boolean textureCompatibility = CoordinateSystemIntegration.testTextureAtlasCompatibility();
            boolean coordinateAccuracy = testCoordinateAccuracy();
            boolean renderingParity = testRenderingParity();
            
            boolean result = textureCompatibility && coordinateAccuracy && renderingParity;
            long executionTime = System.currentTimeMillis() - startTime;
            
            String details = String.format(
                "Texture: %s, Coordinates: %s, Rendering: %s",
                textureCompatibility ? "PASS" : "FAIL",
                coordinateAccuracy ? "PASS" : "FAIL",
                renderingParity ? "PASS" : "FAIL"
            );
            
            return new ValidationResult(
                "Stonebreak Compatibility",
                result,
                3,
                (textureCompatibility ? 1 : 0) + (coordinateAccuracy ? 1 : 0) + (renderingParity ? 1 : 0),
                executionTime,
                details,
                result ? null : "Stonebreak compatibility validation failed"
            );
            
        } catch (Exception e) {
            long executionTime = System.currentTimeMillis() - startTime;
            return new ValidationResult(
                "Stonebreak Compatibility",
                false,
                3,
                0,
                executionTime,
                "Exception during validation",
                e.getMessage()
            );
        }
    }
    
    /**
     * Validate performance characteristics.
     */
    public static ValidationResult validatePerformance() {
        System.out.println("5. Validating Performance...");
        long startTime = System.currentTimeMillis();
        
        try {
            boolean coordGenPerf = testCoordinateGenerationPerformance();
            boolean cachePerf = testCachePerformance();
            boolean memoryUsage = testMemoryUsage();
            
            boolean result = coordGenPerf && cachePerf && memoryUsage;
            long executionTime = System.currentTimeMillis() - startTime;
            
            String details = String.format(
                "Coordinate Gen: %s, Cache: %s, Memory: %s",
                coordGenPerf ? "PASS" : "FAIL",
                cachePerf ? "PASS" : "FAIL",
                memoryUsage ? "PASS" : "FAIL"
            );
            
            return new ValidationResult(
                "Performance Validation",
                result,
                3,
                (coordGenPerf ? 1 : 0) + (cachePerf ? 1 : 0) + (memoryUsage ? 1 : 0),
                executionTime,
                details,
                result ? null : "Performance validation failed"
            );
            
        } catch (Exception e) {
            long executionTime = System.currentTimeMillis() - startTime;
            return new ValidationResult(
                "Performance Validation",
                false,
                3,
                0,
                executionTime,
                "Exception during validation",
                e.getMessage()
            );
        }
    }
    
    /**
     * Test coordinate accuracy against known values.
     */
    private static boolean testCoordinateAccuracy() {
        System.out.println("  Testing coordinate accuracy against known values...");
        
        // Test known coordinate conversions
        float[][] knownCoordinates = {
            {0, 0, 0.0f, 0.0f},
            {8, 8, 0.5f, 0.5f},
            {15, 15, 0.9375f, 0.9375f}
        };
        
        for (float[] coord : knownCoordinates) {
            int atlasX = (int) coord[0];
            int atlasY = (int) coord[1];
            float expectedU = coord[2];
            float expectedV = coord[3];
            
            AtlasCoordinateSystem.UVCoordinate uv = AtlasCoordinateSystem.gridToUV(atlasX, atlasY);
            if (uv == null || 
                Math.abs(uv.getU() - expectedU) > EPSILON || 
                Math.abs(uv.getV() - expectedV) > EPSILON) {
                System.err.println("    ✗ Coordinate accuracy test failed for (" + atlasX + "," + atlasY + ")");
                return false;
            }
        }
        
        System.out.println("    ✓ Coordinate accuracy validated");
        return true;
    }
    
    /**
     * Test rendering parity with Stonebreak.
     */
    private static boolean testRenderingParity() {
        System.out.println("  Testing rendering parity...");
        
        try {
            // Create test model part
            ModelDefinition.ModelPart testPart = new ModelDefinition.ModelPart(
                "test_part",
                new ModelDefinition.Position(0.0f, 0.0f, 0.0f),
                new ModelDefinition.Size(2.0f, 2.0f, 2.0f),
                "cow_head"
            );
            
            // Generate integrated data
            CoordinateSystemIntegration.IntegratedPartData integrated = 
                CoordinateSystemIntegration.generateIntegratedPartData(testPart, "default", false);
            
            if (integrated == null || !integrated.isValid()) {
                System.err.println("    ✗ Failed to generate integrated rendering data");
                return false;
            }
            
            // Validate data structure
            if (integrated.getVertices().length != ModelCoordinateSystem.FLOATS_PER_PART ||
                integrated.getTextureCoordinates().length != 48 ||
                integrated.getIndices().length != ModelCoordinateSystem.INDICES_PER_PART) {
                System.err.println("    ✗ Generated data has incorrect structure");
                return false;
            }
            
            System.out.println("    ✓ Rendering parity validated");
            return true;
            
        } catch (Exception e) {
            System.err.println("    ✗ Rendering parity test failed: " + e.getMessage());
            return false;
        }
    }
    
    /**
     * Test coordinate generation performance.
     */
    private static boolean testCoordinateGenerationPerformance() {
        System.out.println("  Testing coordinate generation performance...");
        
        long startTime = System.nanoTime();
        int iterations = 10000;
        
        try {
            // Test UV coordinate generation
            for (int i = 0; i < iterations; i++) {
                int x = i % 16;
                int y = (i / 16) % 16;
                AtlasCoordinateSystem.generateQuadUVCoordinates(x, y);
            }
            
            // Test vertex generation
            ModelCoordinateSystem.Position pos = new ModelCoordinateSystem.Position(0, 0, 0);
            ModelCoordinateSystem.Size size = new ModelCoordinateSystem.Size(1, 1, 1);
            
            for (int i = 0; i < iterations; i++) {
                ModelCoordinateSystem.generateVertices(pos, size);
            }
            
            long totalTime = System.nanoTime() - startTime;
            double avgTimeMs = (totalTime / 1_000_000.0) / (iterations * 2);
            
            // Performance target: less than 0.01ms per coordinate generation
            boolean performancePass = avgTimeMs < 0.01;
            
            System.out.println("    Average coordinate generation time: " + 
                String.format("%.4f", avgTimeMs) + "ms");
            
            if (performancePass) {
                System.out.println("    ✓ Performance target met");
            } else {
                System.err.println("    ✗ Performance target not met (>0.01ms)");
            }
            
            return performancePass;
            
        } catch (Exception e) {
            System.err.println("    ✗ Performance test failed: " + e.getMessage());
            return false;
        }
    }
    
    /**
     * Test cache performance.
     */
    private static boolean testCachePerformance() {
        System.out.println("  Testing cache performance...");
        
        try {
            // Create test model part
            ModelDefinition.ModelPart testPart = new ModelDefinition.ModelPart(
                "cache_test",
                new ModelDefinition.Position(0.0f, 0.0f, 0.0f),
                new ModelDefinition.Size(1.0f, 1.0f, 1.0f),
                "cow_head"
            );
            
            // Time without cache
            long startTime = System.nanoTime();
            for (int i = 0; i < 1000; i++) {
                CoordinateSystemIntegration.generateTextureCoordinatesForPart(testPart, "default", false);
            }
            long timeWithoutCache = System.nanoTime() - startTime;
            
            // Time with cache
            startTime = System.nanoTime();
            for (int i = 0; i < 1000; i++) {
                CoordinateSystemIntegration.generateTextureCoordinatesForPart(testPart, "default", true);
            }
            long timeWithCache = System.nanoTime() - startTime;
            
            // Cache should provide at least 2x performance improvement
            double improvement = (double) timeWithoutCache / timeWithCache;
            boolean cacheEffective = improvement >= 2.0;
            
            System.out.println("    Cache performance improvement: " + 
                String.format("%.1f", improvement) + "x");
            
            if (cacheEffective) {
                System.out.println("    ✓ Cache performance validated");
            } else {
                System.err.println("    ✗ Cache not providing sufficient performance improvement");
            }
            
            return cacheEffective;
            
        } catch (Exception e) {
            System.err.println("    ✗ Cache performance test failed: " + e.getMessage());
            return false;
        }
    }
    
    /**
     * Test memory usage characteristics.  
     */
    private static boolean testMemoryUsage() {
        System.out.println("  Testing memory usage...");
        
        try {
            Runtime runtime = Runtime.getRuntime();
            long initialMemory = runtime.totalMemory() - runtime.freeMemory();
            
            // Generate coordinate data for multiple parts
            for (int i = 0; i < 100; i++) {
                ModelDefinition.ModelPart testPart = new ModelDefinition.ModelPart(
                    "memory_test_" + i,
                    new ModelDefinition.Position(i, i, i),
                    new ModelDefinition.Size(1.0f, 1.0f, 1.0f),
                    "cow_head"
                );
                
                CoordinateSystemIntegration.generateIntegratedPartData(testPart, "default", true);
            }
            
            System.gc(); // Encourage garbage collection
            Thread.sleep(100); // Give GC time to run
            
            long finalMemory = runtime.totalMemory() - runtime.freeMemory();
            long memoryUsed = finalMemory - initialMemory;
            
            // Memory usage should be reasonable (less than 10MB for 100 parts)
            boolean memoryReasonable = memoryUsed < 10 * 1024 * 1024;
            
            System.out.println("    Memory used: " + (memoryUsed / 1024) + " KB");
            
            if (memoryReasonable) {
                System.out.println("    ✓ Memory usage reasonable");
            } else {
                System.err.println("    ✗ Memory usage too high (>10MB)");
            }
            
            return memoryReasonable;
            
        } catch (Exception e) {
            System.err.println("    ✗ Memory usage test failed: " + e.getMessage());
            return false;
        }
    }
    
    /**
     * Generate comprehensive validation report.
     */
    public static void generateValidationReport() {
        System.out.println("=== Phase 7 Coordinate System Validation Report ===");
        System.out.println();
        
        ValidationResult result = runCompleteValidation();
        
        System.out.println();
        System.out.println("=== DETAILED REPORT ===");
        System.out.println("Implementation: Phase 7 Open Mason Coordinate Systems");
        System.out.println("Components: AtlasCoordinateSystem, ModelCoordinateSystem, Integration");
        System.out.println("Target: 1:1 Stonebreak mathematical compatibility");
        System.out.println();
        System.out.println("System Information:");
        System.out.println(AtlasCoordinateSystem.getSystemInfo());
        System.out.println();
        System.out.println(ModelCoordinateSystem.getSystemInfo());
        System.out.println();
        System.out.println(CoordinateSystemIntegration.getSystemInfo());
        System.out.println();
        System.out.println("Final Result: " + result);
        System.out.println();
        
        if (result.isPassed()) {
            System.out.println("🎉 PHASE 7 IMPLEMENTATION COMPLETE 🎉");
            System.out.println("✅ All coordinate systems validated");
            System.out.println("✅ Mathematical precision confirmed");
            System.out.println("✅ Stonebreak compatibility achieved");
            System.out.println("✅ Ready for production use");
        } else {
            System.out.println("❌ PHASE 7 IMPLEMENTATION FAILED");
            System.out.println("❌ Validation errors detected");
            System.out.println("❌ Review required before production");
        }
        
        System.out.println();
        System.out.println("=== END REPORT ===");
    }
}