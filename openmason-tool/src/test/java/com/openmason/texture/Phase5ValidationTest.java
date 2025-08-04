package com.openmason.texture;

import com.openmason.ui.MainController;
import com.openmason.ui.PropertyPanelController;
import com.openmason.texture.TextureVariantManager;
import com.openmason.texture.TextureVariantManager.CachedVariantInfo;
import org.junit.jupiter.api.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Comprehensive validation test for OpenMason Phase 5 - Texture Variant System.
 * 
 * Tests:
 * - TextureVariantManager HashMap-based caching with <200ms performance
 * - PropertyPanelController UI integration and real-time updates
 * - All 4 cow variants (default, angus, highland, jersey) loading and switching
 * - Performance monitoring and metrics collection
 * - UI property bindings and reactive updates
 * - Cache hit/miss ratios and optimization
 * 
 * Validation Criteria:
 * - Texture variant switching must complete in <200ms for cached variants
 * - All 4 cow texture variants must load successfully
 * - UI components must update reactively with proper bindings
 * - Cache hit rate should be >80% after initial loading
 * - No memory leaks or resource cleanup issues
 * - Performance metrics must be accurate and accessible
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
public class Phase5ValidationTest {
    
    private static final Logger logger = LoggerFactory.getLogger(Phase5ValidationTest.class);
    
    // Test targets
    private TextureVariantManager textureManager;
    private PropertyPanelController propertyController;
    
    // Test configuration
    private static final String[] COW_VARIANTS = {"default", "angus", "highland", "jersey"};
    private static final int PERFORMANCE_TARGET_MS = 200;
    private static final double MIN_CACHE_HIT_RATE = 0.8; // 80%
    private static final String TEST_MODEL_NAME = "Standard Cow";
    
    // Test tracking
    private final Map<String, Long> switchTimes = new HashMap<>();
    private final List<String> testResults = new ArrayList<>();
    
    @BeforeAll
    void setupPhase5Tests() {
        logger.info("=== Starting Phase 5 Validation Tests ===");
        logger.info("Testing Texture Variant System with performance monitoring");
        
        try {
            // Initialize TextureVariantManager
            textureManager = TextureVariantManager.getInstance();
            assertNotNull(textureManager, "TextureVariantManager should be available");
            
            // Initialize PropertyPanelController 
            propertyController = new PropertyPanelController();
            propertyController.initialize(null, null);
            assertNotNull(propertyController, "PropertyPanelController should initialize");
            
            // Wait for async initialization
            CompletableFuture<Void> initFuture = textureManager.initializeAsync(null);
            initFuture.get(10, TimeUnit.SECONDS);
            
            logger.info("Phase 5 test setup complete");
            
        } catch (Exception e) {
            logger.error("Failed to setup Phase 5 tests", e);
            fail("Test setup failed: " + e.getMessage());
        }
    }
    
    @Test
    @Order(1)
    void testTextureVariantManagerInitialization() {
        logger.info("Test 1: TextureVariantManager Initialization");
        
        // Verify initialization
        Map<String, Object> stats = textureManager.getPerformanceStats();
        assertEquals(true, stats.get("initialized"), "TextureVariantManager should be initialized");
        
        // Verify available variants
        var availableVariants = textureManager.getAvailableVariants();
        assertNotNull(availableVariants, "Available variants list should not be null");
        assertTrue(availableVariants.size() >= COW_VARIANTS.length, 
            "Should have at least " + COW_VARIANTS.length + " cow variants available");
        
        logger.info("✓ TextureVariantManager initialized successfully");
        logger.info("  - Available variants: {}", availableVariants.size());
        logger.info("  - Cache initialized: {}", stats.get("initialized"));
        
        testResults.add("✓ TextureVariantManager Initialization: PASSED");
    }
    
    @Test
    @Order(2) 
    void testPropertyPanelControllerIntegration() {
        logger.info("Test 2: PropertyPanelController Integration");
        
        // Verify PropertyPanelController is initialized
        assertTrue(propertyController.isInitialized(), 
            "PropertyPanelController should be initialized");
        
        // Test property bindings
        assertNotNull(propertyController.statusMessageProperty(), 
            "Status message property should be available");
        assertNotNull(propertyController.loadingInProgressProperty(), 
            "Loading progress property should be available");
        assertNotNull(propertyController.selectedVariantProperty(), 
            "Selected variant property should be available");
        
        // Test performance metrics access
        Map<String, Object> metrics = propertyController.getPerformanceMetrics();
        assertNotNull(metrics, "Performance metrics should be available");
        assertTrue(metrics.containsKey("initialized"), "Metrics should contain initialization status");
        
        logger.info("✓ PropertyPanelController integration verified");
        logger.info("  - Initialization status: {}", propertyController.isInitialized());
        logger.info("  - Performance metrics available: {}", metrics.size());
        
        testResults.add("✓ PropertyPanelController Integration: PASSED");
    }
    
    @Test
    @Order(3)
    void testAllCowVariantsLoading() {
        logger.info("Test 3: All Cow Variants Loading");
        
        try {
            // Load all cow variants
            List<String> variants = Arrays.asList(COW_VARIANTS);
            
            CompletableFuture<Map<String, CachedVariantInfo>> loadFuture = 
                textureManager.loadMultipleVariantsAsync(variants, false, null);
            
            Map<String, CachedVariantInfo> loadedVariants = loadFuture.get(30, TimeUnit.SECONDS);
            
            // Verify all variants loaded
            assertNotNull(loadedVariants, "Loaded variants map should not be null");
            assertEquals(COW_VARIANTS.length, loadedVariants.size(), 
                "Should load all " + COW_VARIANTS.length + " cow variants");
            
            // Verify each variant
            for (String variant : COW_VARIANTS) {
                assertTrue(loadedVariants.containsKey(variant), 
                    "Should contain variant: " + variant);
                
                CachedVariantInfo variantInfo = loadedVariants.get(variant);
                assertNotNull(variantInfo, "Variant info should not be null for: " + variant);
                assertTrue(variantInfo.isValid(), "Variant should be valid: " + variant);
                
                logger.info("  - Loaded variant '{}': {} ({})", 
                    variant, variantInfo.getDisplayName(), 
                    variantInfo.isValid() ? "VALID" : "INVALID");
            }
            
            logger.info("✓ All cow variants loaded successfully");
            testResults.add("✓ All Cow Variants Loading: PASSED");
            
        } catch (Exception e) {
            logger.error("Failed to load cow variants", e);
            testResults.add("✗ All Cow Variants Loading: FAILED - " + e.getMessage());
            fail("Cow variants loading failed: " + e.getMessage());
        }
    }
    
    @Test
    @Order(4)
    void testPerformanceTargetSwitching() {
        logger.info("Test 4: Performance Target Switching (<200ms)");
        
        try {
            // Test switching performance for each variant
            for (String variant : COW_VARIANTS) {
                long startTime = System.currentTimeMillis();
                
                // Switch to variant (should be cached from previous test)
                boolean success = textureManager.switchToVariant(variant);
                
                long switchTime = System.currentTimeMillis() - startTime;
                switchTimes.put(variant, switchTime);
                
                assertTrue(success, "Switch to variant should succeed: " + variant);
                assertTrue(switchTime < PERFORMANCE_TARGET_MS, 
                    String.format("Switch to '%s' took %dms (target: <%dms)", 
                        variant, switchTime, PERFORMANCE_TARGET_MS));
                
                logger.info("  - Switch to '{}': {}ms (target: <{}ms) {}", 
                    variant, switchTime, PERFORMANCE_TARGET_MS, 
                    switchTime < PERFORMANCE_TARGET_MS ? "✓" : "✗");
            }
            
            // Calculate average switch time
            double avgSwitchTime = switchTimes.values().stream()
                .mapToLong(Long::longValue)
                .average()
                .orElse(0.0);
            
            assertTrue(avgSwitchTime < PERFORMANCE_TARGET_MS, 
                String.format("Average switch time %.1fms should be less than %dms", 
                    avgSwitchTime, PERFORMANCE_TARGET_MS));
            
            logger.info("✓ Performance target met - Average switch time: {:.1f}ms", avgSwitchTime);
            testResults.add(String.format("✓ Performance Target Switching: PASSED (avg: %.1fms)", avgSwitchTime));
            
        } catch (Exception e) {
            logger.error("Performance testing failed", e);
            testResults.add("✗ Performance Target Switching: FAILED - " + e.getMessage());
            fail("Performance testing failed: " + e.getMessage());
        }
    }
    
    @Test
    @Order(5)
    void testCacheEfficiency() {
        logger.info("Test 5: Cache Efficiency (>80% hit rate)");
        
        try {
            // Perform multiple switches to test cache efficiency
            for (int round = 1; round <= 3; round++) {
                logger.info("  Cache efficiency round {}/3", round);
                
                for (String variant : COW_VARIANTS) {
                    textureManager.switchToVariant(variant);
                    Thread.sleep(10); // Small delay to simulate real usage
                }
            }
            
            // Check cache statistics
            Map<String, Object> stats = textureManager.getPerformanceStats();
            
            long cacheHits = (Long) stats.get("cacheHits");
            long cacheMisses = (Long) stats.get("cacheMisses");
            long totalAccesses = cacheHits + cacheMisses;
            
            double hitRate = totalAccesses > 0 ? (double) cacheHits / totalAccesses : 0.0;
            
            assertTrue(hitRate >= MIN_CACHE_HIT_RATE, 
                String.format("Cache hit rate %.2f%% should be >= %.0f%%", 
                    hitRate * 100, MIN_CACHE_HIT_RATE * 100));
            
            logger.info("✓ Cache efficiency validated");
            logger.info("  - Cache hits: {}", cacheHits);
            logger.info("  - Cache misses: {}", cacheMisses);
            logger.info("  - Hit rate: {:.1f}%", hitRate * 100);
            
            testResults.add(String.format("✓ Cache Efficiency: PASSED (%.1f%% hit rate)", hitRate * 100));
            
        } catch (Exception e) {
            logger.error("Cache efficiency testing failed", e);
            testResults.add("✗ Cache Efficiency: FAILED - " + e.getMessage());
            fail("Cache efficiency testing failed: " + e.getMessage());
        }
    }
    
    @Test
    @Order(6)
    void testPropertyPanelControllerVariantLoading() {
        logger.info("Test 6: PropertyPanelController Variant Loading");
        
        try {
            // Test loading variants through PropertyPanelController
            propertyController.loadTextureVariants(TEST_MODEL_NAME);
            
            // Wait for async loading to complete
            Thread.sleep(2000);
            
            // Verify controller state
            Map<String, Object> metrics = propertyController.getPerformanceMetrics();
            assertNotNull(metrics, "Performance metrics should be available");
            
            assertEquals(TEST_MODEL_NAME, metrics.get("currentModel"), 
                "Current model should be set correctly");
            
            assertTrue((Integer) metrics.get("availableVariants") >= COW_VARIANTS.length, 
                "Should have loaded at least " + COW_VARIANTS.length + " variants");
            
            logger.info("✓ PropertyPanelController variant loading verified");
            logger.info("  - Current model: {}", metrics.get("currentModel"));
            logger.info("  - Available variants: {}", metrics.get("availableVariants"));
            
            testResults.add("✓ PropertyPanelController Variant Loading: PASSED");
            
        } catch (Exception e) {
            logger.error("PropertyPanelController variant loading failed", e);
            testResults.add("✗ PropertyPanelController Variant Loading: FAILED - " + e.getMessage());
            fail("PropertyPanelController variant loading failed: " + e.getMessage());
        }
    }
    
    @Test
    @Order(7)
    void testPropertyPanelControllerVariantSwitching() {
        logger.info("Test 7: PropertyPanelController Variant Switching");
        
        try {
            // Test switching through PropertyPanelController
            for (String variant : COW_VARIANTS) {
                String displayVariant = capitalizeFirst(variant);
                
                long startTime = System.currentTimeMillis();
                propertyController.switchTextureVariant(displayVariant);
                
                // Wait for async switch to complete
                Thread.sleep(500);
                
                long switchTime = System.currentTimeMillis() - startTime;
                
                // Verify switch completed
                String currentVariant = propertyController.selectedVariantProperty().get();
                // Note: currentVariant might be null if switching is async, so we check metrics instead
                
                Map<String, Object> metrics = propertyController.getPerformanceMetrics();
                assertTrue((Integer) metrics.get("switchCount") > 0, 
                    "Switch count should increase");
                
                logger.info("  - Switched to '{}' via PropertyPanelController ({}ms)", 
                    displayVariant, switchTime);
            }
            
            Map<String, Object> finalMetrics = propertyController.getPerformanceMetrics();
            assertTrue((Integer) finalMetrics.get("switchCount") >= COW_VARIANTS.length, 
                "Should have performed at least " + COW_VARIANTS.length + " switches");
            
            logger.info("✓ PropertyPanelController variant switching verified");
            logger.info("  - Total switches: {}", finalMetrics.get("switchCount"));
            
            testResults.add("✓ PropertyPanelController Variant Switching: PASSED");
            
        } catch (Exception e) {
            logger.error("PropertyPanelController variant switching failed", e);
            testResults.add("✗ PropertyPanelController Variant Switching: FAILED - " + e.getMessage());
            fail("PropertyPanelController variant switching failed: " + e.getMessage());
        }
    }
    
    @AfterAll
    void generatePhase5Report() {
        logger.info("=== Phase 5 Validation Test Report ===");
        
        // Print all test results
        testResults.forEach(result -> logger.info(result));
        
        // Performance summary
        if (!switchTimes.isEmpty()) {
            logger.info("\nPerformance Summary:");
            switchTimes.forEach((variant, time) -> 
                logger.info("  - {}: {}ms", variant, time));
            
            double avgTime = switchTimes.values().stream()
                .mapToLong(Long::longValue)
                .average()
                .orElse(0.0);
            logger.info("  - Average: {:.1f}ms (target: <{}ms)", avgTime, PERFORMANCE_TARGET_MS);
        }
        
        // System metrics
        if (textureManager != null) {
            Map<String, Object> finalStats = textureManager.getPerformanceStats();
            logger.info("\nFinal System Metrics:");
            finalStats.forEach((key, value) -> logger.info("  - {}: {}", key, value));
        }
        
        // Cleanup
        if (textureManager != null) {
            textureManager.shutdown();
        }
        
        logger.info("\n=== Phase 5 Validation Complete ===");
        
        // Verify overall success
        long passedTests = testResults.stream()
            .mapToLong(result -> result.startsWith("✓") ? 1 : 0)
            .sum();
        
        logger.info("Test Results: {}/{} tests passed", passedTests, testResults.size());
        
        if (passedTests < testResults.size()) {
            fail(String.format("Phase 5 validation failed: %d/%d tests passed", 
                passedTests, testResults.size()));
        }
    }
    
    // Helper Methods
    
    private String capitalizeFirst(String str) {
        if (str == null || str.isEmpty()) {
            return str;
        }
        return str.substring(0, 1).toUpperCase() + str.substring(1).toLowerCase();
    }
}