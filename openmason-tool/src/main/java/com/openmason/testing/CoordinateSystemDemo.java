package com.openmason.testing;

import com.openmason.model.ModelManager;
import com.openmason.validation.CoordinateSystemValidator;

/**
 * Demonstration and testing utility for the coordinate system alignment solution.
 * This class provides easy-to-use methods for testing and demonstrating the coordinate system fixes.
 * 
 * Use this class to verify that the coordinate system alignment is working correctly.
 */
public class CoordinateSystemDemo {
    
    /**
     * Runs a complete demonstration of the coordinate system alignment solution.
     * This method showcases all the key features and validates the system health.
     */
    public static void runCompleteDemo() {
        System.out.println("🚀 COORDINATE SYSTEM ALIGNMENT DEMONSTRATION");
        System.out.println("=============================================\n");
        
        try {
            // 1. System Health Check
            System.out.println("1. PERFORMING SYSTEM HEALTH CHECK...\n");
            String healthCheck = ModelManager.performSystemHealthCheck();
            System.out.println(healthCheck);
            System.out.println("\n" + "=".repeat(80) + "\n");
            
            // 2. Coordinate System Validation
            System.out.println("2. RUNNING COORDINATE SYSTEM VALIDATION...\n");
            CoordinateSystemValidator.ValidationReport report = CoordinateSystemValidator.validateCoordinateSystemAlignment();
            System.out.println(report.toString());
            System.out.println("\n" + "=".repeat(80) + "\n");
            
            // 3. Standard Cow Diagnostic
            System.out.println("3. STANDARD COW COORDINATE DIAGNOSTIC...\n");
            String cowDiagnostic = ModelManager.diagnoseStandardCowCoordinates();
            System.out.println(cowDiagnostic);
            System.out.println("\n" + "=".repeat(80) + "\n");
            
            // 4. Quick Validation
            System.out.println("4. QUICK VALIDATION CHECK...\n");
            boolean quickValid = CoordinateSystemValidator.quickValidation();
            boolean systemHealthy = ModelManager.isCoordinateSystemHealthy();
            
            System.out.println("Quick validation: " + (quickValid ? "✅ PASS" : "❌ FAIL"));
            System.out.println("System healthy: " + (systemHealthy ? "✅ HEALTHY" : "❌ UNHEALTHY"));
            
            // 5. Final Assessment
            System.out.println("\n5. FINAL ASSESSMENT");
            System.out.println("==================");
            
            boolean overallSuccess = report.allTestsPassed() && quickValid && systemHealthy;
            
            if (overallSuccess) {
                System.out.println("🎉 SUCCESS! Coordinate system is properly aligned!");
                System.out.println("✅ Open Mason will render at identical coordinates as Stonebreak");
                System.out.println("✅ All coordinate space mappings are correct");
                System.out.println("✅ System is ready for production use");
            } else {
                System.out.println("⚠️ ISSUES DETECTED! Coordinate system needs attention");
                System.out.println("❌ Some tests failed or system is unhealthy");
                System.out.println("❌ Please review the reports above and fix any issues");
                
                // Emergency diagnostic
                System.out.println("\n🚨 EMERGENCY DIAGNOSTIC:");
                String emergency = ModelManager.emergencyCoordinateDiagnostic("Demo detected coordinate system issues");
                System.out.println(emergency);
            }
            
        } catch (Exception e) {
            System.err.println("❌ CRITICAL ERROR during demonstration: " + e.getMessage());
            e.printStackTrace();
            
            // Emergency diagnostic for critical errors
            System.out.println("\n🚨 EMERGENCY DIAGNOSTIC:");
            String emergency = ModelManager.emergencyCoordinateDiagnostic("Critical error in demo: " + e.getMessage());
            System.out.println(emergency);
        }
        
        System.out.println("\n🏁 DEMONSTRATION COMPLETE");
    }
    
    /**
     * Runs a focused test on the standard cow model coordinate alignment.
     * This is the most critical test since standard_cow is the primary model with known coordinate issues.
     */
    public static void testStandardCowAlignment() {
        System.out.println("🐄 STANDARD COW COORDINATE ALIGNMENT TEST");
        System.out.println("=========================================\n");
        
        try {
            // Test the complete standard cow pipeline
            String testResults = ModelManager.testStandardCowModel();
            System.out.println(testResults);
            
            // Quick validation specific to standard cow
            System.out.println("\nQUICK STANDARD COW VALIDATION:");
            
            String mapped = ModelManager.CoordinateSpaceManager.getStonebreakCompatibleVariant("standard_cow");
            System.out.println("Mapping result: standard_cow -> " + mapped);
            
            boolean correctMapping = "standard_cow_baked".equals(mapped);
            System.out.println("Correct mapping: " + (correctMapping ? "✅ YES" : "❌ NO"));
            
            ModelManager.CoordinateSpace space = ModelManager.CoordinateSpaceManager.getCoordinateSpace(mapped);
            boolean correctSpace = (space == ModelManager.CoordinateSpace.STONEBREAK_COMPATIBLE);
            System.out.println("Coordinate space: " + space.getDisplayName() + " " + 
                              (correctSpace ? "✅ COMPATIBLE" : "❌ INCOMPATIBLE"));
            
            if (correctMapping && correctSpace) {
                System.out.println("\n🎉 STANDARD COW ALIGNMENT: SUCCESS!");
                System.out.println("✅ Open Mason will render standard_cow at identical coordinates as Stonebreak");
            } else {
                System.out.println("\n⚠️ STANDARD COW ALIGNMENT: ISSUES DETECTED!");
                System.out.println("❌ Coordinate alignment may be incorrect");
            }
            
        } catch (Exception e) {
            System.err.println("❌ Error during standard cow test: " + e.getMessage());
            e.printStackTrace();
        }
        
        System.out.println("\n🏁 STANDARD COW TEST COMPLETE");
    }
    
    /**
     * Demonstrates the coordinate space management features.
     */
    public static void demonstrateCoordinateSpaceManagement() {
        System.out.println("🗺️ COORDINATE SPACE MANAGEMENT DEMONSTRATION");
        System.out.println("============================================\n");
        
        try {
            // Show available coordinate spaces
            System.out.println("AVAILABLE COORDINATE SPACES:");
            for (ModelManager.CoordinateSpace space : ModelManager.CoordinateSpace.values()) {
                System.out.println("  " + space.name() + ": " + space.getDisplayName());
                System.out.println("    Description: " + space.getDescription());
            }
            
            System.out.println("\nMODEL VARIANT MAPPINGS:");
            
            // Test various models
            String[] testModels = {"standard_cow", "nonexistent_model", "standard_cow_baked"};
            
            for (String model : testModels) {
                String compatible = ModelManager.CoordinateSpaceManager.getStonebreakCompatibleVariant(model);
                ModelManager.CoordinateSpace space = ModelManager.CoordinateSpaceManager.getCoordinateSpace(compatible);
                boolean hasVariant = ModelManager.CoordinateSpaceManager.hasCompatibleVariant(model);
                
                System.out.println("  Model: " + model);
                System.out.println("    Compatible variant: " + compatible);
                System.out.println("    Coordinate space: " + space.getDisplayName());
                System.out.println("    Has variant mapping: " + hasVariant);
                
                // Validate compatibility
                ModelManager.CoordinateValidationResult validation = 
                    ModelManager.CoordinateSpaceManager.validateCoordinateCompatibility(model, compatible);
                System.out.println("    Validation: " + (validation.isCompatible() ? "✅ COMPATIBLE" : "❌ INCOMPATIBLE"));
                System.out.println();
            }
            
            System.out.println("COORDINATE COMPATIBILITY MATRIX:");
            System.out.println("  standard_cow + standard_cow      = INCOMPATIBLE (raw coords vs raw coords)");
            System.out.println("  standard_cow + standard_cow_baked = COMPATIBLE (raw request -> baked coords)");
            System.out.println("  This ensures Open Mason uses the same coordinates as Stonebreak EntityRenderer");
            
        } catch (Exception e) {
            System.err.println("❌ Error during coordinate space demonstration: " + e.getMessage());
            e.printStackTrace();
        }
        
        System.out.println("\n🏁 COORDINATE SPACE DEMONSTRATION COMPLETE");
    }
    
    /**
     * Runs just the essential validation without extensive output.
     * Suitable for automated testing or integration into other systems.
     */
    public static boolean runEssentialValidation() {
        try {
            // Core validation checks
            boolean quickValid = CoordinateSystemValidator.quickValidation();
            boolean systemHealthy = ModelManager.isCoordinateSystemHealthy();
            
            // Check critical mapping
            String mapped = ModelManager.CoordinateSpaceManager.getStonebreakCompatibleVariant("standard_cow");
            boolean correctMapping = "standard_cow_baked".equals(mapped);
            
            return quickValid && systemHealthy && correctMapping;
            
        } catch (Exception e) {
            System.err.println("Essential validation failed: " + e.getMessage());
            return false;
        }
    }
    
    /**
     * Main method for running demonstrations from command line.
     */
    public static void main(String[] args) {
        if (args.length == 0) {
            // Run complete demo by default
            runCompleteDemo();
        } else {
            String command = args[0].toLowerCase();
            switch (command) {
                case "complete", "full", "demo" -> runCompleteDemo();
                case "cow", "standard", "standard_cow" -> testStandardCowAlignment();
                case "spaces", "coordinate", "management" -> demonstrateCoordinateSpaceManagement();
                case "quick", "essential", "validate" -> {
                    boolean valid = runEssentialValidation();
                    System.out.println("Essential validation: " + (valid ? "✅ PASS" : "❌ FAIL"));
                    System.exit(valid ? 0 : 1);
                }
                default -> {
                    System.out.println("Usage: CoordinateSystemDemo [complete|cow|spaces|quick]");
                    System.out.println("  complete - Run full demonstration (default)");
                    System.out.println("  cow      - Test standard cow alignment");
                    System.out.println("  spaces   - Demonstrate coordinate space management");
                    System.out.println("  quick    - Run essential validation only");
                }
            }
        }
    }
}