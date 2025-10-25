package com.stonebreak.textures.validation;

import java.io.File;

/**
 * Utility class to validate the new texture system JSON files
 */
public class TextureSystemValidator {
    
    /**
     * Validates both Block_ids.JSON and Item_ids.JSON files
     * @param blockIdsPath Path to Block_ids.JSON file
     * @param itemIdsPath Path to Item_ids.JSON file
     */
    public static void validateSystem(String blockIdsPath, String itemIdsPath) {
        System.out.println("=== Texture System Validation ===");
        System.out.println();
        
        File blockIdsFile = new File(blockIdsPath);
        File itemIdsFile = new File(itemIdsPath);
        
        // Validate Block_ids.JSON
        System.out.println("Validating Block_ids.JSON...");
        JSONSchemaValidator.ValidationResult blockResult = JSONSchemaValidator.validateBlockIds(blockIdsFile);
        printValidationResult(blockResult, "Block_ids.JSON");
        System.out.println();
        
        // Validate Item_ids.JSON
        System.out.println("Validating Item_ids.JSON...");
        JSONSchemaValidator.ValidationResult itemResult = JSONSchemaValidator.validateItemIds(itemIdsFile);
        printValidationResult(itemResult, "Item_ids.JSON");
        System.out.println();
        
        // Check for ID conflicts between blocks and items
        System.out.println("Checking for ID conflicts...");
        JSONSchemaValidator.ValidationResult conflictResult = JSONSchemaValidator.validateIdConflicts(blockIdsFile, itemIdsFile);
        printValidationResult(conflictResult, "ID Conflict Check");
        System.out.println();
        
        // Summary
        boolean allValid = blockResult.isValid() && itemResult.isValid() && conflictResult.isValid();
        System.out.println("=== Validation Summary ===");
        System.out.println("Overall Status: " + (allValid ? "VALID" : "INVALID"));
        
        if (!allValid) {
            System.out.println();
            System.out.println("Issues found:");
            if (!blockResult.isValid()) {
                System.out.println("- Block_ids.JSON: " + blockResult.getErrorMessage());
            }
            if (!itemResult.isValid()) {
                System.out.println("- Item_ids.JSON: " + itemResult.getErrorMessage());
            }
            if (!conflictResult.isValid()) {
                System.out.println("- ID Conflicts: " + conflictResult.getErrorMessage());
            }
        }
        
        System.out.println();
        System.out.println("Phase 3 Implementation Status: " + (allValid ? "COMPLETE" : "NEEDS ATTENTION"));
        System.out.println();
        System.out.println("Cube Net Format: 64x48 pixel files with 6 faces in cross layout");
        System.out.println("- TOP at (16,0), LEFT/FRONT/RIGHT/BACK at row (0,16,32,48) col 16, BOTTOM at (16,32)");
        System.out.println("- Single texture files contain entire cube net, not separate face files");
    }
    
    private static void printValidationResult(JSONSchemaValidator.ValidationResult result, String filename) {
        System.out.println("  File: " + filename);
        System.out.println("  Status: " + (result.isValid() ? "VALID" : "INVALID"));
        System.out.println("  Schema Version: " + result.getSchemaVersion());
        System.out.println("  Needs Migration: " + result.needsMigration());
        if (!result.isValid() && result.getErrorMessage() != null) {
            System.out.println("  Error: " + result.getErrorMessage());
        }
    }
    
    /**
     * Main method for testing the validation system
     */
    public static void main(String[] args) {
        String projectRoot = System.getProperty("user.dir");
        String blockIdsPath = projectRoot + "/stonebreak-game/src/main/resources/blocks/Block_ids.JSON";
        String itemIdsPath = projectRoot + "/stonebreak-game/src/main/resources/Items/Item_ids.JSON";
        
        validateSystem(blockIdsPath, itemIdsPath);
    }
}