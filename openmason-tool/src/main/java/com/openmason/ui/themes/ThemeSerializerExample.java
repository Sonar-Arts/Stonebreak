package com.openmason.ui.themes;

import imgui.flag.ImGuiCol;
import imgui.flag.ImGuiStyleVar;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.File;
import java.io.IOException;
import java.util.Map;

/**
 * Example demonstrating ThemeSerializer usage for the Open Mason theming system.
 * Shows how to save, load, export, and import themes with proper error handling.
 */
public class ThemeSerializerExample {
    
    private static final Logger logger = LoggerFactory.getLogger(ThemeSerializerExample.class);
    
    public static void main(String[] args) {
        ThemeSerializer serializer = new ThemeSerializer();
        
        try {
            // Example 1: Create and save a custom theme
            demonstrateCustomThemeCreation(serializer);
            
            // Example 2: Export and import built-in theme
            demonstrateBuiltInThemeExport(serializer);
            
            // Example 3: Load all user themes
            demonstrateLoadAllThemes(serializer);
            
        } catch (Exception e) {
            logger.error("Error during theme serialization demonstration", e);
        }
    }
    
    /**
     * Demonstrate creating and saving a custom theme
     */
    private static void demonstrateCustomThemeCreation(ThemeSerializer serializer) {
        logger.info("=== Custom Theme Creation Example ===");
        
        try {
            // Create a custom theme
            ThemeDefinition customTheme = new ThemeDefinition(
                "ocean-blue", 
                "Ocean Blue", 
                "A custom ocean-inspired blue theme", 
                ThemeDefinition.ThemeType.USER_CUSTOM
            );
            
            // Set custom colors
            customTheme.setColor(ImGuiCol.WindowBg, 0.12f, 0.20f, 0.35f, 1.00f);      // Deep ocean blue
            customTheme.setColor(ImGuiCol.ChildBg, 0.15f, 0.25f, 0.40f, 1.00f);       // Lighter ocean blue
            customTheme.setColor(ImGuiCol.Text, 0.95f, 0.95f, 1.00f, 1.00f);          // Light blue-white text
            customTheme.setColor(ImGuiCol.Button, 0.20f, 0.40f, 0.70f, 0.80f);        // Ocean button
            customTheme.setColor(ImGuiCol.ButtonHovered, 0.25f, 0.50f, 0.85f, 1.00f); // Lighter on hover
            customTheme.setColor(ImGuiCol.CheckMark, 0.00f, 0.80f, 1.00f, 1.00f);     // Bright cyan
            
            // Set style variables
            customTheme.setStyleVar(ImGuiStyleVar.WindowRounding, 8.0f);
            customTheme.setStyleVar(ImGuiStyleVar.FrameRounding, 4.0f);
            customTheme.setStyleVar(ImGuiStyleVar.ScrollbarRounding, 12.0f);
            customTheme.setStyleVar(ImGuiStyleVar.GrabRounding, 4.0f);
            
            // Save the theme
            serializer.saveTheme(customTheme, "ocean-blue", ThemeRegistry.ThemeCategory.USER);
            
            logger.info("Successfully created and saved custom theme: {}", customTheme.getName());
            logger.info("Theme has {} colors and {} style variables", 
                       customTheme.getColorCount(), customTheme.getStyleVarCount());
            
        } catch (IOException e) {
            logger.error("Failed to save custom theme", e);
        } catch (IllegalArgumentException e) {
            logger.error("Invalid theme data", e);
        }
    }
    
    /**
     * Demonstrate exporting and importing a built-in theme
     */
    private static void demonstrateBuiltInThemeExport(ThemeSerializer serializer) {
        logger.info("=== Built-in Theme Export/Import Example ===");
        
        try {
            // Get a built-in theme
            ThemeDefinition darkTheme = ColorPalette.createDarkTheme();
            
            // Export to a file
            File exportFile = new File(System.getProperty("java.io.tmpdir"), "dark-theme-backup.theme.json");
            serializer.exportTheme(darkTheme, exportFile);
            
            logger.info("Exported '{}' theme to: {}", darkTheme.getName(), exportFile.getAbsolutePath());
            logger.info("Export file size: {} bytes", exportFile.length());
            
            // Import it back
            ThemeDefinition importedTheme = serializer.importTheme(exportFile);
            
            logger.info("Successfully imported theme: {}", importedTheme.getName());
            logger.info("Imported theme has {} colors and {} style variables",
                       importedTheme.getColorCount(), importedTheme.getStyleVarCount());
            
            // Verify data integrity
            boolean colorsMatch = darkTheme.getColorCount() == importedTheme.getColorCount();
            boolean styleVarsMatch = darkTheme.getStyleVarCount() == importedTheme.getStyleVarCount();
            
            if (colorsMatch && styleVarsMatch) {
                logger.info("✓ Data integrity verified - export/import successful");
            } else {
                logger.warn("✗ Data integrity check failed");
            }
            
            // Clean up
            if (exportFile.delete()) {
                logger.debug("Cleaned up temporary export file");
            }
            
        } catch (IOException e) {
            logger.error("Failed to export/import theme", e);
        }
    }
    
    /**
     * Demonstrate loading all themes from user directory
     */
    private static void demonstrateLoadAllThemes(ThemeSerializer serializer) {
        logger.info("=== Load All User Themes Example ===");
        
        try {
            // Load all user themes
            Map<String, ThemeDefinition> userThemes = serializer.loadAllThemes(ThemeRegistry.ThemeCategory.USER);
            
            logger.info("Found {} user theme(s) in local directory", userThemes.size());
            
            if (userThemes.isEmpty()) {
                logger.info("No user themes found. Create some custom themes first!");
            } else {
                // Display information about each theme
                for (Map.Entry<String, ThemeDefinition> entry : userThemes.entrySet()) {
                    String filename = entry.getKey();
                    ThemeDefinition theme = entry.getValue();
                    
                    logger.info("Theme: '{}' ({})", theme.getName(), filename);
                    logger.info("  Description: {}", theme.getDescription());
                    logger.info("  Type: {}", theme.getType().getDisplayName());
                    logger.info("  Colors: {}, Style Variables: {}", 
                               theme.getColorCount(), theme.getStyleVarCount());
                    logger.info("  Read-only: {}", theme.isReadOnly());
                }
            }
            
        } catch (Exception e) {
            logger.error("Failed to load user themes", e);
        }
    }
    
    /**
     * Demonstrate theme deletion (commented out to prevent accidental deletion)
     */
    @SuppressWarnings("unused")
    private static void demonstrateThemeDeletion(ThemeSerializer serializer) {
        logger.info("=== Theme Deletion Example (DISABLED) ===");
        
        // Uncomment to test deletion functionality
        /*
        try {
            boolean deleted = serializer.deleteTheme("ocean-blue", ThemeRegistry.ThemeCategory.USER);
            if (deleted) {
                logger.info("Successfully deleted theme");
            } else {
                logger.info("Theme not found for deletion");
            }
        } catch (IOException e) {
            logger.error("Failed to delete theme", e);
        }
        */
        
        logger.info("Theme deletion example is disabled to prevent accidental data loss");
        logger.info("Uncomment the code in demonstrateThemeDeletion() to test deletion");
    }
}