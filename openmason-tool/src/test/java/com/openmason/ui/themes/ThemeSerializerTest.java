package com.openmason.ui.themes;

import imgui.ImVec4;
import imgui.flag.ImGuiCol;
import imgui.flag.ImGuiStyleVar;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.io.TempDir;

import java.io.File;
import java.io.IOException;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Test class for ThemeSerializer functionality.
 * Verifies JSON serialization/deserialization and file I/O operations.
 */
public class ThemeSerializerTest {
    
    private ThemeSerializer serializer;
    
    @TempDir
    Path tempDir;
    
    @BeforeEach
    void setUp() {
        serializer = new ThemeSerializer();
    }
    
    @Test
    void testExportAndImportTheme() throws IOException {
        // Create a test theme
        ThemeDefinition originalTheme = new ThemeDefinition(
            "test-theme", 
            "Test Theme", 
            "A theme for testing serialization", 
            ThemeDefinition.ThemeType.USER_CUSTOM
        );
        
        // Add some colors and style variables
        originalTheme.setColor(ImGuiCol.WindowBg, 0.1f, 0.2f, 0.3f, 1.0f);
        originalTheme.setColor(ImGuiCol.Text, 1.0f, 1.0f, 1.0f, 1.0f);
        originalTheme.setStyleVar(ImGuiStyleVar.WindowRounding, 5.0f);
        originalTheme.setStyleVar(ImGuiStyleVar.FrameRounding, 3.0f);
        
        // Export theme to temporary file
        File exportFile = tempDir.resolve("test-theme.json").toFile();
        serializer.exportTheme(originalTheme, exportFile);
        
        // Verify file was created
        assertTrue(exportFile.exists(), "Export file should exist");
        assertTrue(exportFile.length() > 0, "Export file should not be empty");
        
        // Import theme from file
        ThemeDefinition importedTheme = serializer.importTheme(exportFile);
        
        // Verify theme properties
        assertNotNull(importedTheme, "Imported theme should not be null");
        assertEquals(originalTheme.getId(), importedTheme.getId());
        assertEquals(originalTheme.getName(), importedTheme.getName());
        assertEquals(originalTheme.getDescription(), importedTheme.getDescription());
        
        // Verify colors were preserved
        ImVec4 windowBg = importedTheme.getColor(ImGuiCol.WindowBg);
        assertNotNull(windowBg, "Window background color should be preserved");
        assertEquals(0.1f, windowBg.x, 0.001f);
        assertEquals(0.2f, windowBg.y, 0.001f);
        assertEquals(0.3f, windowBg.z, 0.001f);
        assertEquals(1.0f, windowBg.w, 0.001f);
        
        ImVec4 textColor = importedTheme.getColor(ImGuiCol.Text);
        assertNotNull(textColor, "Text color should be preserved");
        assertEquals(1.0f, textColor.x, 0.001f);
        assertEquals(1.0f, textColor.y, 0.001f);
        assertEquals(1.0f, textColor.z, 0.001f);
        
        // Verify style variables were preserved
        Float windowRounding = importedTheme.getStyleVar(ImGuiStyleVar.WindowRounding);
        assertNotNull(windowRounding, "Window rounding should be preserved");
        assertEquals(5.0f, windowRounding, 0.001f);
        
        Float frameRounding = importedTheme.getStyleVar(ImGuiStyleVar.FrameRounding);
        assertNotNull(frameRounding, "Frame rounding should be preserved");
        assertEquals(3.0f, frameRounding, 0.001f);
    }
    
    @Test
    void testExportBuiltInTheme() throws IOException {
        // Get a built-in theme
        ThemeDefinition darkTheme = ColorPalette.createDarkTheme();
        
        // Export to temporary file
        File exportFile = tempDir.resolve("dark-theme-export.json").toFile();
        
        // Should not throw exception even for read-only theme
        assertDoesNotThrow(() -> {
            serializer.exportTheme(darkTheme, exportFile);
        });
        
        // Verify file was created and has content
        assertTrue(exportFile.exists());
        assertTrue(exportFile.length() > 100); // Should have substantial content
        
        // Import and verify
        ThemeDefinition importedTheme = serializer.importTheme(exportFile);
        assertEquals(darkTheme.getId(), importedTheme.getId());
        assertEquals(darkTheme.getName(), importedTheme.getName());
        assertEquals(darkTheme.getColorCount(), importedTheme.getColorCount());
        assertEquals(darkTheme.getStyleVarCount(), importedTheme.getStyleVarCount());
    }
    
    @Test
    void testInvalidThemeHandling() {
        // Test null theme export
        File exportFile = tempDir.resolve("invalid.json").toFile();
        
        assertThrows(IllegalArgumentException.class, () -> {
            serializer.exportTheme(null, exportFile);
        });
        
        // Test null destination
        ThemeDefinition theme = ColorPalette.createDarkTheme();
        assertThrows(IllegalArgumentException.class, () -> {
            serializer.exportTheme(theme, null);
        });
        
        // Test import from non-existent file
        File nonExistentFile = tempDir.resolve("does-not-exist.json").toFile();
        assertThrows(IOException.class, () -> {
            serializer.importTheme(nonExistentFile);
        });
    }
    
    @Test
    void testThemeValidationOnImport() throws IOException {
        // Create a theme with invalid data
        ThemeDefinition invalidTheme = new ThemeDefinition();
        // Leave ID and name as null (invalid)
        
        File exportFile = tempDir.resolve("invalid-theme.json").toFile();
        
        // Should throw during export due to validation
        assertThrows(IllegalArgumentException.class, () -> {
            serializer.exportTheme(invalidTheme, exportFile);
        });
    }
}