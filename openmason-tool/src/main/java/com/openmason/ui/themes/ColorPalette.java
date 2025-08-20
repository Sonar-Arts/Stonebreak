package com.openmason.ui.themes;

import imgui.flag.ImGuiCol;
import imgui.flag.ImGuiStyleVar;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Arrays;
import java.util.List;

/**
 * Built-in color schemes and theme creators extracted from ThemeManager.
 * Contains all built-in theme creation methods and color definitions.
 * Estimated size: ~250 lines (extracted from lines 266-674 of ThemeManager)
 */
public class ColorPalette {
    private static final Logger logger = LoggerFactory.getLogger(ColorPalette.class);
    
    /**
     * Create professional dark theme (extracted from createDarkTheme)
     */
    public static ThemeDefinition createDarkTheme() {
        ThemeDefinition theme = new ThemeDefinition("dark", "Dark", 
            "Professional dark theme for extended usage", ThemeDefinition.ThemeType.BUILT_IN);
        theme.setReadOnly(true);
        
        // Window colors
        theme.setColor(ImGuiCol.WindowBg, 0.11f, 0.11f, 0.11f, 1.00f);           // #1c1c1c
        theme.setColor(ImGuiCol.ChildBg, 0.13f, 0.13f, 0.13f, 1.00f);            // #212121
        theme.setColor(ImGuiCol.PopupBg, 0.08f, 0.08f, 0.08f, 0.94f);            // #141414
        
        // Frame colors (inputs, buttons)
        theme.setColor(ImGuiCol.FrameBg, 0.23f, 0.23f, 0.23f, 1.00f);            // #3a3a3a
        theme.setColor(ImGuiCol.FrameBgHovered, 0.28f, 0.28f, 0.28f, 1.00f);     // #474747
        theme.setColor(ImGuiCol.FrameBgActive, 0.33f, 0.33f, 0.33f, 1.00f);      // #545454
        
        // Title bar
        theme.setColor(ImGuiCol.TitleBg, 0.12f, 0.12f, 0.12f, 1.00f);            // #1e1e1e
        theme.setColor(ImGuiCol.TitleBgActive, 0.16f, 0.16f, 0.16f, 1.00f);      // #292929
        theme.setColor(ImGuiCol.TitleBgCollapsed, 0.08f, 0.08f, 0.08f, 0.51f);   // #141414
        
        // Menu bar
        theme.setColor(ImGuiCol.MenuBarBg, 0.14f, 0.14f, 0.14f, 1.00f);          // #242424
        
        // Scrollbar
        theme.setColor(ImGuiCol.ScrollbarBg, 0.02f, 0.02f, 0.02f, 0.53f);        // #050505
        theme.setColor(ImGuiCol.ScrollbarGrab, 0.31f, 0.31f, 0.31f, 1.00f);      // #4f4f4f
        theme.setColor(ImGuiCol.ScrollbarGrabHovered, 0.41f, 0.41f, 0.41f, 1.00f); // #696969
        theme.setColor(ImGuiCol.ScrollbarGrabActive, 0.51f, 0.51f, 0.51f, 1.00f);  // #828282
        
        // Check mark
        theme.setColor(ImGuiCol.CheckMark, 0.00f, 0.60f, 1.00f, 1.00f);          // #0099ff (accent blue)
        
        // Slider
        theme.setColor(ImGuiCol.SliderGrab, 0.00f, 0.60f, 1.00f, 1.00f);         // #0099ff
        theme.setColor(ImGuiCol.SliderGrabActive, 0.00f, 0.47f, 0.80f, 1.00f);   // #0077cc
        
        // Button
        theme.setColor(ImGuiCol.Button, 0.00f, 0.60f, 1.00f, 0.40f);             // #0099ff with alpha
        theme.setColor(ImGuiCol.ButtonHovered, 0.00f, 0.60f, 1.00f, 1.00f);      // #0099ff
        theme.setColor(ImGuiCol.ButtonActive, 0.00f, 0.47f, 0.80f, 1.00f);       // #0077cc
        
        // Header (collapsing headers, selectables)
        theme.setColor(ImGuiCol.Header, 0.00f, 0.60f, 1.00f, 0.31f);             // #0099ff with alpha
        theme.setColor(ImGuiCol.HeaderHovered, 0.00f, 0.60f, 1.00f, 0.80f);      // #0099ff with alpha
        theme.setColor(ImGuiCol.HeaderActive, 0.00f, 0.60f, 1.00f, 1.00f);       // #0099ff
        
        // Separator
        theme.setColor(ImGuiCol.Separator, 0.33f, 0.33f, 0.33f, 0.50f);          // #545454
        theme.setColor(ImGuiCol.SeparatorHovered, 0.00f, 0.60f, 1.00f, 0.78f);   // #0099ff
        theme.setColor(ImGuiCol.SeparatorActive, 0.00f, 0.60f, 1.00f, 1.00f);    // #0099ff
        
        // Resize grip
        theme.setColor(ImGuiCol.ResizeGrip, 0.00f, 0.60f, 1.00f, 0.20f);         // #0099ff
        theme.setColor(ImGuiCol.ResizeGripHovered, 0.00f, 0.60f, 1.00f, 0.67f);  // #0099ff
        theme.setColor(ImGuiCol.ResizeGripActive, 0.00f, 0.60f, 1.00f, 0.95f);   // #0099ff
        
        // Tab
        theme.setColor(ImGuiCol.Tab, 0.18f, 0.35f, 0.58f, 0.86f);                // Dark blue
        theme.setColor(ImGuiCol.TabHovered, 0.00f, 0.60f, 1.00f, 0.80f);         // #0099ff
        theme.setColor(ImGuiCol.TabActive, 0.20f, 0.41f, 0.68f, 1.00f);          // Active blue
        theme.setColor(ImGuiCol.TabUnfocused, 0.07f, 0.10f, 0.15f, 0.97f);       // Unfocused
        theme.setColor(ImGuiCol.TabUnfocusedActive, 0.14f, 0.26f, 0.42f, 1.00f); // Unfocused active
        
        // Plot colors
        theme.setColor(ImGuiCol.PlotLines, 0.61f, 0.61f, 0.61f, 1.00f);          // Light gray
        theme.setColor(ImGuiCol.PlotLinesHovered, 1.00f, 0.43f, 0.35f, 1.00f);   // Orange
        theme.setColor(ImGuiCol.PlotHistogram, 0.90f, 0.70f, 0.00f, 1.00f);      // Yellow
        theme.setColor(ImGuiCol.PlotHistogramHovered, 1.00f, 0.60f, 0.00f, 1.00f); // Orange
        
        // Table
        theme.setColor(ImGuiCol.TableHeaderBg, 0.19f, 0.19f, 0.20f, 1.00f);      // #303134
        theme.setColor(ImGuiCol.TableBorderStrong, 0.31f, 0.31f, 0.35f, 1.00f);  // #4f4f59
        theme.setColor(ImGuiCol.TableBorderLight, 0.23f, 0.23f, 0.25f, 1.00f);   // #3a3a40
        theme.setColor(ImGuiCol.TableRowBg, 0.00f, 0.00f, 0.00f, 0.00f);         // Transparent
        theme.setColor(ImGuiCol.TableRowBgAlt, 1.00f, 1.00f, 1.00f, 0.06f);      // White with alpha
        
        // Text
        theme.setColor(ImGuiCol.Text, 1.00f, 1.00f, 1.00f, 1.00f);               // White
        theme.setColor(ImGuiCol.TextDisabled, 0.42f, 0.42f, 0.42f, 1.00f);       // Gray
        
        // Border
        theme.setColor(ImGuiCol.Border, 0.33f, 0.33f, 0.33f, 0.50f);             // #545454
        theme.setColor(ImGuiCol.BorderShadow, 0.00f, 0.00f, 0.00f, 0.00f);       // Transparent
        
        // Style variables for professional look
        theme.setStyleVar(ImGuiStyleVar.WindowRounding, 6.0f);
        theme.setStyleVar(ImGuiStyleVar.ChildRounding, 3.0f);
        theme.setStyleVar(ImGuiStyleVar.FrameRounding, 3.0f);
        theme.setStyleVar(ImGuiStyleVar.PopupRounding, 3.0f);
        theme.setStyleVar(ImGuiStyleVar.ScrollbarRounding, 9.0f);
        theme.setStyleVar(ImGuiStyleVar.GrabRounding, 3.0f);
        theme.setStyleVar(ImGuiStyleVar.TabRounding, 4.0f);
        theme.setStyleVar(ImGuiStyleVar.WindowBorderSize, 1.0f);
        theme.setStyleVar(ImGuiStyleVar.ChildBorderSize, 1.0f);
        theme.setStyleVar(ImGuiStyleVar.PopupBorderSize, 1.0f);
        theme.setStyleVar(ImGuiStyleVar.FrameBorderSize, 0.0f);
        
        logger.debug("Created dark theme with {} colors and {} style vars", 
                     theme.getColorCount(), theme.getStyleVarCount());
        return theme;
    }
    
    /**
     * Create clean light theme (extracted from createLightTheme)
     */
    public static ThemeDefinition createLightTheme() {
        ThemeDefinition theme = new ThemeDefinition("light", "Light", 
            "Clean light theme for bright environments", ThemeDefinition.ThemeType.BUILT_IN);
        theme.setReadOnly(true);
        
        // Window colors
        theme.setColor(ImGuiCol.WindowBg, 0.96f, 0.96f, 0.96f, 1.00f);           // #f5f5f5
        theme.setColor(ImGuiCol.ChildBg, 0.98f, 0.98f, 0.98f, 1.00f);            // #fafafa
        theme.setColor(ImGuiCol.PopupBg, 1.00f, 1.00f, 1.00f, 0.98f);            // White
        
        // Frame colors
        theme.setColor(ImGuiCol.FrameBg, 0.91f, 0.91f, 0.91f, 1.00f);            // #e8e8e8
        theme.setColor(ImGuiCol.FrameBgHovered, 0.86f, 0.86f, 0.86f, 1.00f);     // #dbdbdb
        theme.setColor(ImGuiCol.FrameBgActive, 0.81f, 0.81f, 0.81f, 1.00f);      // #cfcfcf
        
        // Title bar
        theme.setColor(ImGuiCol.TitleBg, 0.88f, 0.88f, 0.88f, 1.00f);            // #e0e0e0
        theme.setColor(ImGuiCol.TitleBgActive, 0.83f, 0.83f, 0.83f, 1.00f);      // #d4d4d4
        theme.setColor(ImGuiCol.TitleBgCollapsed, 0.94f, 0.94f, 0.94f, 0.51f);   // #f0f0f0
        
        // Menu bar
        theme.setColor(ImGuiCol.MenuBarBg, 0.93f, 0.93f, 0.93f, 1.00f);          // #ededed
        
        // Scrollbar
        theme.setColor(ImGuiCol.ScrollbarBg, 0.89f, 0.89f, 0.89f, 0.53f);        // #e3e3e3
        theme.setColor(ImGuiCol.ScrollbarGrab, 0.69f, 0.69f, 0.69f, 1.00f);      // #b0b0b0
        theme.setColor(ImGuiCol.ScrollbarGrabHovered, 0.59f, 0.59f, 0.59f, 1.00f); // #969696
        theme.setColor(ImGuiCol.ScrollbarGrabActive, 0.49f, 0.49f, 0.49f, 1.00f);  // #7d7d7d
        
        // Check mark
        theme.setColor(ImGuiCol.CheckMark, 0.00f, 0.40f, 0.80f, 1.00f);          // #0066cc
        
        // Slider
        theme.setColor(ImGuiCol.SliderGrab, 0.00f, 0.40f, 0.80f, 1.00f);         // #0066cc
        theme.setColor(ImGuiCol.SliderGrabActive, 0.00f, 0.32f, 0.64f, 1.00f);   // #0052a3
        
        // Button
        theme.setColor(ImGuiCol.Button, 0.00f, 0.40f, 0.80f, 0.40f);             // #0066cc with alpha
        theme.setColor(ImGuiCol.ButtonHovered, 0.00f, 0.40f, 0.80f, 1.00f);      // #0066cc
        theme.setColor(ImGuiCol.ButtonActive, 0.00f, 0.32f, 0.64f, 1.00f);       // #0052a3
        
        // Header
        theme.setColor(ImGuiCol.Header, 0.00f, 0.40f, 0.80f, 0.31f);             // #0066cc with alpha
        theme.setColor(ImGuiCol.HeaderHovered, 0.00f, 0.40f, 0.80f, 0.80f);      // #0066cc with alpha
        theme.setColor(ImGuiCol.HeaderActive, 0.00f, 0.40f, 0.80f, 1.00f);       // #0066cc
        
        // Separator
        theme.setColor(ImGuiCol.Separator, 0.67f, 0.67f, 0.67f, 0.50f);          // #ababab
        theme.setColor(ImGuiCol.SeparatorHovered, 0.00f, 0.40f, 0.80f, 0.78f);   // #0066cc
        theme.setColor(ImGuiCol.SeparatorActive, 0.00f, 0.40f, 0.80f, 1.00f);    // #0066cc
        
        // Resize grip
        theme.setColor(ImGuiCol.ResizeGrip, 0.00f, 0.40f, 0.80f, 0.20f);         // #0066cc
        theme.setColor(ImGuiCol.ResizeGripHovered, 0.00f, 0.40f, 0.80f, 0.67f);  // #0066cc
        theme.setColor(ImGuiCol.ResizeGripActive, 0.00f, 0.40f, 0.80f, 0.95f);   // #0066cc
        
        // Tab
        theme.setColor(ImGuiCol.Tab, 0.76f, 0.80f, 0.84f, 0.86f);                // Light blue gray
        theme.setColor(ImGuiCol.TabHovered, 0.00f, 0.40f, 0.80f, 0.80f);         // #0066cc
        theme.setColor(ImGuiCol.TabActive, 0.68f, 0.78f, 0.88f, 1.00f);          // Active light blue
        theme.setColor(ImGuiCol.TabUnfocused, 0.92f, 0.93f, 0.94f, 0.97f);       // Very light gray
        theme.setColor(ImGuiCol.TabUnfocusedActive, 0.74f, 0.82f, 0.90f, 1.00f); // Light blue
        
        // Plot colors
        theme.setColor(ImGuiCol.PlotLines, 0.39f, 0.39f, 0.39f, 1.00f);          // Dark gray
        theme.setColor(ImGuiCol.PlotLinesHovered, 1.00f, 0.43f, 0.35f, 1.00f);   // Orange
        theme.setColor(ImGuiCol.PlotHistogram, 0.90f, 0.70f, 0.00f, 1.00f);      // Yellow
        theme.setColor(ImGuiCol.PlotHistogramHovered, 1.00f, 0.60f, 0.00f, 1.00f); // Orange
        
        // Table
        theme.setColor(ImGuiCol.TableHeaderBg, 0.78f, 0.87f, 0.98f, 1.00f);      // Light blue
        theme.setColor(ImGuiCol.TableBorderStrong, 0.57f, 0.57f, 0.64f, 1.00f);  // Dark gray
        theme.setColor(ImGuiCol.TableBorderLight, 0.68f, 0.68f, 0.74f, 1.00f);   // Light gray
        theme.setColor(ImGuiCol.TableRowBg, 0.00f, 0.00f, 0.00f, 0.00f);         // Transparent
        theme.setColor(ImGuiCol.TableRowBgAlt, 0.30f, 0.30f, 0.30f, 0.09f);      // Dark with alpha
        
        // Text
        theme.setColor(ImGuiCol.Text, 0.20f, 0.20f, 0.20f, 1.00f);               // #333333
        theme.setColor(ImGuiCol.TextDisabled, 0.60f, 0.60f, 0.60f, 1.00f);       // #999999
        
        // Border
        theme.setColor(ImGuiCol.Border, 0.73f, 0.73f, 0.73f, 0.50f);             // #bababa
        theme.setColor(ImGuiCol.BorderShadow, 0.00f, 0.00f, 0.00f, 0.00f);       // Transparent
        
        // Style variables
        theme.setStyleVar(ImGuiStyleVar.WindowRounding, 4.0f);
        theme.setStyleVar(ImGuiStyleVar.ChildRounding, 2.0f);
        theme.setStyleVar(ImGuiStyleVar.FrameRounding, 2.0f);
        theme.setStyleVar(ImGuiStyleVar.PopupRounding, 2.0f);
        theme.setStyleVar(ImGuiStyleVar.ScrollbarRounding, 6.0f);
        theme.setStyleVar(ImGuiStyleVar.GrabRounding, 2.0f);
        theme.setStyleVar(ImGuiStyleVar.TabRounding, 3.0f);
        theme.setStyleVar(ImGuiStyleVar.WindowBorderSize, 1.0f);
        theme.setStyleVar(ImGuiStyleVar.ChildBorderSize, 1.0f);
        theme.setStyleVar(ImGuiStyleVar.PopupBorderSize, 1.0f);
        theme.setStyleVar(ImGuiStyleVar.FrameBorderSize, 0.0f);
        
        logger.debug("Created light theme with {} colors and {} style vars", 
                     theme.getColorCount(), theme.getStyleVarCount());
        return theme;
    }
    
    /**
     * Create high contrast theme for accessibility (extracted from createHighContrastTheme)
     */
    public static ThemeDefinition createHighContrastTheme() {
        ThemeDefinition theme = new ThemeDefinition("high-contrast", "High Contrast", 
                                         "High contrast theme for accessibility", ThemeDefinition.ThemeType.BUILT_IN);
        theme.setReadOnly(true);
        
        // Window colors - maximum contrast
        theme.setColor(ImGuiCol.WindowBg, 0.00f, 0.00f, 0.00f, 1.00f);           // Black
        theme.setColor(ImGuiCol.ChildBg, 0.05f, 0.05f, 0.05f, 1.00f);            // Very dark gray
        theme.setColor(ImGuiCol.PopupBg, 0.00f, 0.00f, 0.00f, 1.00f);            // Black
        
        // Frame colors - high contrast
        theme.setColor(ImGuiCol.FrameBg, 0.15f, 0.15f, 0.15f, 1.00f);            // Dark gray
        theme.setColor(ImGuiCol.FrameBgHovered, 0.25f, 0.25f, 0.25f, 1.00f);     // Medium gray
        theme.setColor(ImGuiCol.FrameBgActive, 0.35f, 0.35f, 0.35f, 1.00f);      // Light gray
        
        // Title bar
        theme.setColor(ImGuiCol.TitleBg, 0.00f, 0.00f, 0.00f, 1.00f);            // Black
        theme.setColor(ImGuiCol.TitleBgActive, 0.10f, 0.10f, 0.10f, 1.00f);      // Very dark gray
        theme.setColor(ImGuiCol.TitleBgCollapsed, 0.00f, 0.00f, 0.00f, 0.51f);   // Black
        
        // Menu bar
        theme.setColor(ImGuiCol.MenuBarBg, 0.05f, 0.05f, 0.05f, 1.00f);          // Very dark gray
        
        // Scrollbar
        theme.setColor(ImGuiCol.ScrollbarBg, 0.02f, 0.02f, 0.02f, 0.53f);        // Almost black
        theme.setColor(ImGuiCol.ScrollbarGrab, 0.80f, 0.80f, 0.80f, 1.00f);      // Light gray
        theme.setColor(ImGuiCol.ScrollbarGrabHovered, 0.90f, 0.90f, 0.90f, 1.00f); // Very light gray
        theme.setColor(ImGuiCol.ScrollbarGrabActive, 1.00f, 1.00f, 1.00f, 1.00f);  // White
        
        // Check mark - high visibility green
        theme.setColor(ImGuiCol.CheckMark, 0.00f, 1.00f, 0.00f, 1.00f);          // Bright green
        
        // Slider
        theme.setColor(ImGuiCol.SliderGrab, 0.00f, 1.00f, 0.00f, 1.00f);         // Bright green
        theme.setColor(ImGuiCol.SliderGrabActive, 0.00f, 0.80f, 0.00f, 1.00f);   // Dark green
        
        // Button
        theme.setColor(ImGuiCol.Button, 0.00f, 1.00f, 0.00f, 0.40f);             // Green with alpha
        theme.setColor(ImGuiCol.ButtonHovered, 0.00f, 1.00f, 0.00f, 1.00f);      // Bright green
        theme.setColor(ImGuiCol.ButtonActive, 0.00f, 0.80f, 0.00f, 1.00f);       // Dark green
        
        // Header
        theme.setColor(ImGuiCol.Header, 0.00f, 1.00f, 0.00f, 0.31f);             // Green with alpha
        theme.setColor(ImGuiCol.HeaderHovered, 0.00f, 1.00f, 0.00f, 0.80f);      // Green with alpha
        theme.setColor(ImGuiCol.HeaderActive, 0.00f, 1.00f, 0.00f, 1.00f);       // Bright green
        
        // Separator
        theme.setColor(ImGuiCol.Separator, 1.00f, 1.00f, 1.00f, 0.50f);          // White
        theme.setColor(ImGuiCol.SeparatorHovered, 0.00f, 1.00f, 0.00f, 0.78f);   // Green
        theme.setColor(ImGuiCol.SeparatorActive, 0.00f, 1.00f, 0.00f, 1.00f);    // Bright green
        
        // Resize grip
        theme.setColor(ImGuiCol.ResizeGrip, 0.00f, 1.00f, 0.00f, 0.20f);         // Green
        theme.setColor(ImGuiCol.ResizeGripHovered, 0.00f, 1.00f, 0.00f, 0.67f);  // Green
        theme.setColor(ImGuiCol.ResizeGripActive, 0.00f, 1.00f, 0.00f, 0.95f);   // Bright green
        
        // Tab
        theme.setColor(ImGuiCol.Tab, 0.58f, 0.58f, 0.58f, 0.86f);                // Gray
        theme.setColor(ImGuiCol.TabHovered, 0.00f, 1.00f, 0.00f, 0.80f);         // Green
        theme.setColor(ImGuiCol.TabActive, 0.68f, 0.68f, 0.68f, 1.00f);          // Light gray
        theme.setColor(ImGuiCol.TabUnfocused, 0.15f, 0.15f, 0.15f, 0.97f);       // Dark gray
        theme.setColor(ImGuiCol.TabUnfocusedActive, 0.42f, 0.42f, 0.42f, 1.00f); // Medium gray
        
        // Plot colors
        theme.setColor(ImGuiCol.PlotLines, 1.00f, 1.00f, 1.00f, 1.00f);          // White
        theme.setColor(ImGuiCol.PlotLinesHovered, 1.00f, 1.00f, 0.00f, 1.00f);   // Yellow
        theme.setColor(ImGuiCol.PlotHistogram, 1.00f, 1.00f, 0.00f, 1.00f);      // Yellow
        theme.setColor(ImGuiCol.PlotHistogramHovered, 1.00f, 0.00f, 0.00f, 1.00f); // Red
        
        // Table
        theme.setColor(ImGuiCol.TableHeaderBg, 0.19f, 0.19f, 0.20f, 1.00f);      // Dark gray
        theme.setColor(ImGuiCol.TableBorderStrong, 1.00f, 1.00f, 1.00f, 1.00f);  // White
        theme.setColor(ImGuiCol.TableBorderLight, 0.80f, 0.80f, 0.80f, 1.00f);   // Light gray
        theme.setColor(ImGuiCol.TableRowBg, 0.00f, 0.00f, 0.00f, 0.00f);         // Transparent
        theme.setColor(ImGuiCol.TableRowBgAlt, 1.00f, 1.00f, 1.00f, 0.06f);      // White with alpha
        
        // Text - maximum contrast
        theme.setColor(ImGuiCol.Text, 1.00f, 1.00f, 1.00f, 1.00f);               // White
        theme.setColor(ImGuiCol.TextDisabled, 0.50f, 0.50f, 0.50f, 1.00f);       // Medium gray
        
        // Border
        theme.setColor(ImGuiCol.Border, 1.00f, 1.00f, 1.00f, 0.50f);             // White
        theme.setColor(ImGuiCol.BorderShadow, 0.00f, 0.00f, 0.00f, 0.00f);       // Transparent
        
        // Style variables - sharp edges for accessibility
        theme.setStyleVar(ImGuiStyleVar.WindowRounding, 0.0f);
        theme.setStyleVar(ImGuiStyleVar.ChildRounding, 0.0f);
        theme.setStyleVar(ImGuiStyleVar.FrameRounding, 0.0f);
        theme.setStyleVar(ImGuiStyleVar.PopupRounding, 0.0f);
        theme.setStyleVar(ImGuiStyleVar.ScrollbarRounding, 0.0f);
        theme.setStyleVar(ImGuiStyleVar.GrabRounding, 0.0f);
        theme.setStyleVar(ImGuiStyleVar.TabRounding, 0.0f);
        theme.setStyleVar(ImGuiStyleVar.WindowBorderSize, 2.0f);
        theme.setStyleVar(ImGuiStyleVar.ChildBorderSize, 2.0f);
        theme.setStyleVar(ImGuiStyleVar.PopupBorderSize, 2.0f);
        theme.setStyleVar(ImGuiStyleVar.FrameBorderSize, 1.0f);
        
        logger.debug("Created high contrast theme with {} colors and {} style vars", 
                     theme.getColorCount(), theme.getStyleVarCount());
        return theme;
    }
    
    /**
     * Create professional blue theme (extracted from createBlueTheme)
     */
    public static ThemeDefinition createBlueTheme() {
        ThemeDefinition theme = new ThemeDefinition("blue", "Blue", 
            "Professional blue theme", ThemeDefinition.ThemeType.BUILT_IN);
        theme.setReadOnly(true);
        
        // Window colors - blue tinted
        theme.setColor(ImGuiCol.WindowBg, 0.12f, 0.16f, 0.23f, 1.00f);           // #1e2a3a
        theme.setColor(ImGuiCol.ChildBg, 0.16f, 0.23f, 0.30f, 1.00f);            // #2a3b4d
        theme.setColor(ImGuiCol.PopupBg, 0.08f, 0.12f, 0.16f, 0.94f);            // #151e2a
        
        // Frame colors
        theme.setColor(ImGuiCol.FrameBg, 0.21f, 0.29f, 0.37f, 1.00f);            // #354a5f
        theme.setColor(ImGuiCol.FrameBgHovered, 0.25f, 0.35f, 0.45f, 1.00f);     // Lighter blue
        theme.setColor(ImGuiCol.FrameBgActive, 0.29f, 0.41f, 0.53f, 1.00f);      // Even lighter blue
        
        // Title bar
        theme.setColor(ImGuiCol.TitleBg, 0.10f, 0.14f, 0.20f, 1.00f);            // Dark blue
        theme.setColor(ImGuiCol.TitleBgActive, 0.16f, 0.23f, 0.30f, 1.00f);      // Medium blue
        theme.setColor(ImGuiCol.TitleBgCollapsed, 0.08f, 0.12f, 0.16f, 0.51f);   // Very dark blue
        
        // Menu bar
        theme.setColor(ImGuiCol.MenuBarBg, 0.14f, 0.20f, 0.26f, 1.00f);          // Blue gray
        
        // Scrollbar
        theme.setColor(ImGuiCol.ScrollbarBg, 0.02f, 0.02f, 0.02f, 0.53f);        // Dark
        theme.setColor(ImGuiCol.ScrollbarGrab, 0.35f, 0.42f, 0.48f, 1.00f);      // Blue gray
        theme.setColor(ImGuiCol.ScrollbarGrabHovered, 0.45f, 0.52f, 0.58f, 1.00f); // Lighter blue gray
        theme.setColor(ImGuiCol.ScrollbarGrabActive, 0.55f, 0.62f, 0.68f, 1.00f);  // Even lighter
        
        // Check mark
        theme.setColor(ImGuiCol.CheckMark, 0.30f, 0.60f, 1.00f, 1.00f);          // #4d9fff
        
        // Slider
        theme.setColor(ImGuiCol.SliderGrab, 0.30f, 0.60f, 1.00f, 1.00f);         // #4d9fff
        theme.setColor(ImGuiCol.SliderGrabActive, 0.20f, 0.50f, 0.90f, 1.00f);   // #3380e6
        
        // Button
        theme.setColor(ImGuiCol.Button, 0.30f, 0.60f, 1.00f, 0.40f);             // #4d9fff with alpha
        theme.setColor(ImGuiCol.ButtonHovered, 0.30f, 0.60f, 1.00f, 1.00f);      // #4d9fff
        theme.setColor(ImGuiCol.ButtonActive, 0.20f, 0.50f, 0.90f, 1.00f);       // #3380e6
        
        // Header
        theme.setColor(ImGuiCol.Header, 0.30f, 0.60f, 1.00f, 0.31f);             // #4d9fff with alpha
        theme.setColor(ImGuiCol.HeaderHovered, 0.30f, 0.60f, 1.00f, 0.80f);      // #4d9fff with alpha
        theme.setColor(ImGuiCol.HeaderActive, 0.30f, 0.60f, 1.00f, 1.00f);       // #4d9fff
        
        // Separator
        theme.setColor(ImGuiCol.Separator, 0.35f, 0.42f, 0.48f, 0.50f);          // Blue gray
        theme.setColor(ImGuiCol.SeparatorHovered, 0.30f, 0.60f, 1.00f, 0.78f);   // #4d9fff
        theme.setColor(ImGuiCol.SeparatorActive, 0.30f, 0.60f, 1.00f, 1.00f);    // #4d9fff
        
        // Resize grip
        theme.setColor(ImGuiCol.ResizeGrip, 0.30f, 0.60f, 1.00f, 0.20f);         // #4d9fff
        theme.setColor(ImGuiCol.ResizeGripHovered, 0.30f, 0.60f, 1.00f, 0.67f);  // #4d9fff
        theme.setColor(ImGuiCol.ResizeGripActive, 0.30f, 0.60f, 1.00f, 0.95f);   // #4d9fff
        
        // Tab
        theme.setColor(ImGuiCol.Tab, 0.18f, 0.35f, 0.58f, 0.86f);                // Dark blue
        theme.setColor(ImGuiCol.TabHovered, 0.30f, 0.60f, 1.00f, 0.80f);         // #4d9fff
        theme.setColor(ImGuiCol.TabActive, 0.20f, 0.41f, 0.68f, 1.00f);          // Active blue
        theme.setColor(ImGuiCol.TabUnfocused, 0.07f, 0.10f, 0.15f, 0.97f);       // Very dark blue
        theme.setColor(ImGuiCol.TabUnfocusedActive, 0.14f, 0.26f, 0.42f, 1.00f); // Unfocused active
        
        // Plot colors
        theme.setColor(ImGuiCol.PlotLines, 0.78f, 0.83f, 0.88f, 1.00f);          // Light blue gray
        theme.setColor(ImGuiCol.PlotLinesHovered, 1.00f, 0.43f, 0.35f, 1.00f);   // Orange
        theme.setColor(ImGuiCol.PlotHistogram, 0.90f, 0.70f, 0.00f, 1.00f);      // Yellow
        theme.setColor(ImGuiCol.PlotHistogramHovered, 1.00f, 0.60f, 0.00f, 1.00f); // Orange
        
        // Table
        theme.setColor(ImGuiCol.TableHeaderBg, 0.19f, 0.19f, 0.20f, 1.00f);      // Dark
        theme.setColor(ImGuiCol.TableBorderStrong, 0.35f, 0.42f, 0.48f, 1.00f);  // Blue gray
        theme.setColor(ImGuiCol.TableBorderLight, 0.25f, 0.32f, 0.38f, 1.00f);   // Dark blue gray
        theme.setColor(ImGuiCol.TableRowBg, 0.00f, 0.00f, 0.00f, 0.00f);         // Transparent
        theme.setColor(ImGuiCol.TableRowBgAlt, 1.00f, 1.00f, 1.00f, 0.06f);      // White with alpha
        
        // Text
        theme.setColor(ImGuiCol.Text, 1.00f, 1.00f, 1.00f, 1.00f);               // White
        theme.setColor(ImGuiCol.TextDisabled, 0.42f, 0.48f, 0.54f, 1.00f);       // Blue gray
        
        // Border
        theme.setColor(ImGuiCol.Border, 0.35f, 0.42f, 0.48f, 0.50f);             // Blue gray
        theme.setColor(ImGuiCol.BorderShadow, 0.00f, 0.00f, 0.00f, 0.00f);       // Transparent
        
        // Style variables
        theme.setStyleVar(ImGuiStyleVar.WindowRounding, 5.0f);
        theme.setStyleVar(ImGuiStyleVar.ChildRounding, 3.0f);
        theme.setStyleVar(ImGuiStyleVar.FrameRounding, 3.0f);
        theme.setStyleVar(ImGuiStyleVar.PopupRounding, 3.0f);
        theme.setStyleVar(ImGuiStyleVar.ScrollbarRounding, 8.0f);
        theme.setStyleVar(ImGuiStyleVar.GrabRounding, 3.0f);
        theme.setStyleVar(ImGuiStyleVar.TabRounding, 4.0f);
        theme.setStyleVar(ImGuiStyleVar.WindowBorderSize, 1.0f);
        theme.setStyleVar(ImGuiStyleVar.ChildBorderSize, 1.0f);
        theme.setStyleVar(ImGuiStyleVar.PopupBorderSize, 1.0f);
        theme.setStyleVar(ImGuiStyleVar.FrameBorderSize, 0.0f);
        
        logger.debug("Created blue theme with {} colors and {} style vars", 
                     theme.getColorCount(), theme.getStyleVarCount());
        return theme;
    }
    
    /**
     * Get all available built-in themes
     */
    public static List<ThemeDefinition> getAllBuiltInThemes() {
        List<ThemeDefinition> themes = Arrays.asList(
            createDarkTheme(),
            createLightTheme(), 
            createHighContrastTheme(),
            createBlueTheme()
        );
        logger.info("Generated {} built-in themes", themes.size());
        return themes;
    }
    
    /**
     * Get a specific built-in theme by ID
     */
    public static ThemeDefinition getBuiltInTheme(String themeId) {
        switch (themeId.toLowerCase()) {
            case "dark":
                return createDarkTheme();
            case "light":
                return createLightTheme();
            case "high-contrast":
                return createHighContrastTheme();
            case "blue":
                return createBlueTheme();
            default:
                logger.warn("Unknown built-in theme ID: {}", themeId);
                return null;
        }
    }
    
    /**
     * Check if a theme ID corresponds to a built-in theme
     */
    public static boolean isBuiltInTheme(String themeId) {
        if (themeId == null) return false;
        String id = themeId.toLowerCase();
        return "dark".equals(id) || "light".equals(id) || "high-contrast".equals(id) || "blue".equals(id);
    }
}