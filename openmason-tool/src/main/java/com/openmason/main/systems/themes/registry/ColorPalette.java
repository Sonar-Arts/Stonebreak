package com.openmason.main.systems.themes.registry;
import com.openmason.main.systems.themes.core.ThemeDefinition;

import imgui.flag.ImGuiCol;
import imgui.flag.ImGuiStyleVar;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Arrays;
import java.util.List;

/**
 * Built-in color schemes and theme creators extracted from ThemeManager.
 * Contains all built-in theme creation methods and color definitions.
 */
public class ColorPalette {
    private static final Logger logger = LoggerFactory.getLogger(ColorPalette.class);
    
    /**
     * Create professional dark theme
     */
    public static ThemeDefinition createDarkTheme() {
        ThemeDefinition theme = new ThemeDefinition("dark", "Dark",
            "Professional dark theme for extended usage", ThemeDefinition.ThemeType.BUILT_IN);

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

        // Mark as read-only AFTER all colors and styles are set
        theme.setReadOnly(true);
        return theme;
    }
    
    /**
     * Create clean light theme (extracted from createLightTheme)
     */
    public static ThemeDefinition createLightTheme() {
        ThemeDefinition theme = new ThemeDefinition("light", "Light",
            "Clean light theme for bright environments", ThemeDefinition.ThemeType.BUILT_IN);

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

        // Mark as read-only AFTER all colors and styles are set
        theme.setReadOnly(true);
        return theme;
    }

    /**
     * Create high contrast theme for accessibility (extracted from createHighContrastTheme)
     */
    public static ThemeDefinition createHighContrastTheme() {
        ThemeDefinition theme = new ThemeDefinition("high-contrast", "High Contrast",
                                         "High contrast theme for accessibility", ThemeDefinition.ThemeType.BUILT_IN);

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

        // Mark as read-only AFTER all colors and styles are set
        theme.setReadOnly(true);
        return theme;
    }

    /**
     * Create professional blue theme (extracted from createBlueTheme)
     */
    public static ThemeDefinition createBlueTheme() {
        ThemeDefinition theme = new ThemeDefinition("blue", "Blue",
            "Professional blue theme", ThemeDefinition.ThemeType.BUILT_IN);

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

        // Mark as read-only AFTER all colors and styles are set
        theme.setReadOnly(true);
        return theme;
    }

    /**
     * Create Mason Spectrum Light theme (extracted from Adobe Spectrum design system)
     */
    public static ThemeDefinition createAdobeSpectrumLightTheme() {
        ThemeDefinition theme = new ThemeDefinition("mason-spectrum-light", "Mason Spectrum Light",
            "Professional light theme based on Spectrum design system", ThemeDefinition.ThemeType.BUILT_IN);

        // Adobe Spectrum Light colors (hex values from Spectrum constants)
        // GRAY50 = 0xFFFFFF, GRAY75 = 0xFAFAFA, GRAY100 = 0xF5F5F5, GRAY200 = 0xEAEAEA
        // GRAY300 = 0xE1E1E1, GRAY400 = 0xCACACA, GRAY500 = 0xB3B3B3, GRAY600 = 0x8E8E8E
        // GRAY700 = 0x707070, GRAY800 = 0x4B4B4B, GRAY900 = 0x2C2C2C
        // CYAN400 = 0x1BB5C9, CYAN500 = 0x10AAC0, CYAN600 = 0x0C94A8

        // Text colors
        theme.setColor(ImGuiCol.Text, 0.294f, 0.294f, 0.294f, 1.00f);           // GRAY800 #4B4B4B
        theme.setColor(ImGuiCol.TextDisabled, 0.702f, 0.702f, 0.702f, 1.00f);   // GRAY500 #B3B3B3

        // Window colors
        theme.setColor(ImGuiCol.WindowBg, 0.961f, 0.961f, 0.961f, 1.00f);       // GRAY100 #F5F5F5
        theme.setColor(ImGuiCol.ChildBg, 0.00f, 0.00f, 0.00f, 0.00f);           // Transparent
        theme.setColor(ImGuiCol.PopupBg, 1.00f, 1.00f, 1.00f, 1.00f);           // GRAY50 #FFFFFF

        // Border colors - enhanced visibility
        theme.setColor(ImGuiCol.Border, 0.792f, 0.792f, 0.792f, 0.80f);         // GRAY400 #CACACA with higher opacity
        theme.setColor(ImGuiCol.BorderShadow, 0.00f, 0.00f, 0.00f, 0.00f);      // None/Transparent

        // Frame colors (inputs, buttons) - enhanced contrast
        theme.setColor(ImGuiCol.FrameBg, 1.00f, 1.00f, 1.00f, 1.00f);           // GRAY50 #FFFFFF (lighter for better separation)
        theme.setColor(ImGuiCol.FrameBgHovered, 0.882f, 0.882f, 0.882f, 1.00f); // GRAY300 #E1E1E1 (more noticeable hover)
        theme.setColor(ImGuiCol.FrameBgActive, 0.792f, 0.792f, 0.792f, 1.00f);  // GRAY400 #CACACA (distinct active state)

        // Title bar
        theme.setColor(ImGuiCol.TitleBg, 0.882f, 0.882f, 0.882f, 1.00f);        // GRAY300 #E1E1E1
        theme.setColor(ImGuiCol.TitleBgActive, 0.918f, 0.918f, 0.918f, 1.00f);  // GRAY200 #EAEAEA
        theme.setColor(ImGuiCol.TitleBgCollapsed, 0.792f, 0.792f, 0.792f, 1.00f); // GRAY400 #CACACA

        // Menu bar
        theme.setColor(ImGuiCol.MenuBarBg, 0.961f, 0.961f, 0.961f, 1.00f);      // GRAY100 #F5F5F5

        // Scrollbar
        theme.setColor(ImGuiCol.ScrollbarBg, 0.961f, 0.961f, 0.961f, 1.00f);    // GRAY100 #F5F5F5
        theme.setColor(ImGuiCol.ScrollbarGrab, 0.792f, 0.792f, 0.792f, 1.00f);  // GRAY400 #CACACA
        theme.setColor(ImGuiCol.ScrollbarGrabHovered, 0.557f, 0.557f, 0.557f, 1.00f); // GRAY600 #8E8E8E
        theme.setColor(ImGuiCol.ScrollbarGrabActive, 0.439f, 0.439f, 0.439f, 1.00f);  // GRAY700 #707070

        // Check mark
        theme.setColor(ImGuiCol.CheckMark, 0.063f, 0.667f, 0.753f, 1.00f);      // CYAN500 #10AAC0

        // Slider
        theme.setColor(ImGuiCol.SliderGrab, 0.439f, 0.439f, 0.439f, 1.00f);     // GRAY700 #707070
        theme.setColor(ImGuiCol.SliderGrabActive, 0.294f, 0.294f, 0.294f, 1.00f); // GRAY800 #4B4B4B

        // Button - enhanced contrast
        theme.setColor(ImGuiCol.Button, 1.00f, 1.00f, 1.00f, 1.00f);            // GRAY50 #FFFFFF (lighter for better separation)
        theme.setColor(ImGuiCol.ButtonHovered, 0.882f, 0.882f, 0.882f, 1.00f);  // GRAY300 #E1E1E1 (more noticeable hover)
        theme.setColor(ImGuiCol.ButtonActive, 0.792f, 0.792f, 0.792f, 1.00f);   // GRAY400 #CACACA (distinct active state)

        // Header (collapsing headers, selectables)
        theme.setColor(ImGuiCol.Header, 0.106f, 0.710f, 0.788f, 1.00f);         // CYAN400 #1BB5C9
        theme.setColor(ImGuiCol.HeaderHovered, 0.063f, 0.667f, 0.753f, 1.00f);  // CYAN500 #10AAC0
        theme.setColor(ImGuiCol.HeaderActive, 0.047f, 0.580f, 0.659f, 1.00f);   // CYAN600 #0C94A8

        // Separator - enhanced visibility
        theme.setColor(ImGuiCol.Separator, 0.702f, 0.702f, 0.702f, 0.70f);      // GRAY500 #B3B3B3 with higher opacity
        theme.setColor(ImGuiCol.SeparatorHovered, 0.557f, 0.557f, 0.557f, 1.00f); // GRAY600 #8E8E8E
        theme.setColor(ImGuiCol.SeparatorActive, 0.439f, 0.439f, 0.439f, 1.00f);  // GRAY700 #707070

        // Resize grip
        theme.setColor(ImGuiCol.ResizeGrip, 0.792f, 0.792f, 0.792f, 1.00f);     // GRAY400 #CACACA
        theme.setColor(ImGuiCol.ResizeGripHovered, 0.557f, 0.557f, 0.557f, 1.00f); // GRAY600 #8E8E8E
        theme.setColor(ImGuiCol.ResizeGripActive, 0.439f, 0.439f, 0.439f, 1.00f);  // GRAY700 #707070

        // Plot colors
        theme.setColor(ImGuiCol.PlotLines, 0.106f, 0.710f, 0.788f, 1.00f);      // CYAN400 #1BB5C9
        theme.setColor(ImGuiCol.PlotLinesHovered, 0.047f, 0.580f, 0.659f, 1.00f); // CYAN600 #0C94A8
        theme.setColor(ImGuiCol.PlotHistogram, 0.106f, 0.710f, 0.788f, 1.00f);  // CYAN400 #1BB5C9
        theme.setColor(ImGuiCol.PlotHistogramHovered, 0.047f, 0.580f, 0.659f, 1.00f); // CYAN600 #0C94A8

        // Text selection
        theme.setColor(ImGuiCol.TextSelectedBg, 0.106f, 0.710f, 0.788f, 0.35f); // CYAN400 with alpha

        // Drag and drop
        theme.setColor(ImGuiCol.DragDropTarget, 1.00f, 1.00f, 0.00f, 0.90f);    // Yellow

        // Navigation
        theme.setColor(ImGuiCol.NavHighlight, 0.173f, 0.173f, 0.173f, 0.04f);   // GRAY900 with alpha
        theme.setColor(ImGuiCol.NavWindowingHighlight, 1.00f, 1.00f, 1.00f, 0.70f);
        theme.setColor(ImGuiCol.NavWindowingDimBg, 0.80f, 0.80f, 0.80f, 0.20f);

        // Modal
        theme.setColor(ImGuiCol.ModalWindowDimBg, 0.20f, 0.20f, 0.20f, 0.35f);

        // Table colors
        theme.setColor(ImGuiCol.TableHeaderBg, 0.882f, 0.882f, 0.882f, 1.00f);  // GRAY300 #E1E1E1
        theme.setColor(ImGuiCol.TableBorderStrong, 0.792f, 0.792f, 0.792f, 1.00f); // GRAY400 #CACACA
        theme.setColor(ImGuiCol.TableBorderLight, 0.918f, 0.918f, 0.918f, 1.00f);  // GRAY200 #EAEAEA
        theme.setColor(ImGuiCol.TableRowBg, 0.00f, 0.00f, 0.00f, 0.00f);        // Transparent
        theme.setColor(ImGuiCol.TableRowBgAlt, 0.294f, 0.294f, 0.294f, 0.09f);  // GRAY800 with alpha

        // Tab colors - enhanced contrast
        theme.setColor(ImGuiCol.Tab, 0.882f, 0.882f, 0.882f, 1.00f);            // GRAY300 #E1E1E1 (darker for better separation)
        theme.setColor(ImGuiCol.TabHovered, 0.063f, 0.667f, 0.753f, 0.80f);     // CYAN500 with alpha
        theme.setColor(ImGuiCol.TabActive, 1.00f, 1.00f, 1.00f, 1.00f);         // GRAY50 #FFFFFF (much lighter/more visible)
        theme.setColor(ImGuiCol.TabUnfocused, 0.882f, 0.882f, 0.882f, 1.00f);   // GRAY300 #E1E1E1
        theme.setColor(ImGuiCol.TabUnfocusedActive, 0.961f, 0.961f, 0.961f, 1.00f); // GRAY100 #F5F5F5 (moderately visible)

        // Style variables - Adobe Spectrum uses rounded corners with GrabRounding = 4.0f
        theme.setStyleVar(ImGuiStyleVar.WindowRounding, 4.0f);
        theme.setStyleVar(ImGuiStyleVar.ChildRounding, 4.0f);
        theme.setStyleVar(ImGuiStyleVar.FrameRounding, 4.0f);
        theme.setStyleVar(ImGuiStyleVar.PopupRounding, 4.0f);
        theme.setStyleVar(ImGuiStyleVar.ScrollbarRounding, 4.0f);
        theme.setStyleVar(ImGuiStyleVar.GrabRounding, 4.0f);
        theme.setStyleVar(ImGuiStyleVar.TabRounding, 4.0f);
        theme.setStyleVar(ImGuiStyleVar.WindowBorderSize, 1.0f);
        theme.setStyleVar(ImGuiStyleVar.ChildBorderSize, 1.0f);
        theme.setStyleVar(ImGuiStyleVar.PopupBorderSize, 1.0f);
        theme.setStyleVar(ImGuiStyleVar.FrameBorderSize, 0.0f);

        logger.debug("Created Mason Spectrum Light theme with {} colors and {} style vars",
                     theme.getColorCount(), theme.getStyleVarCount());

        // Mark as read-only AFTER all colors and styles are set
        theme.setReadOnly(true);
        return theme;
    }

    /**
     * Create Mason Spectrum Dark theme (dark variant of Adobe Spectrum design system)
     */
    public static ThemeDefinition createAdobeSpectrumDarkTheme() {
        ThemeDefinition theme = new ThemeDefinition("mason-spectrum-dark", "Mason Spectrum Dark",
            "Professional dark theme based on Spectrum design system", ThemeDefinition.ThemeType.BUILT_IN);

        // Adobe Spectrum Dark colors (hex values from Spectrum constants)
        // GRAY50 = 0x252525, GRAY75 = 0x2F2F2F, GRAY100 = 0x323232, GRAY200 = 0x393939
        // GRAY300 = 0x3E3E3E, GRAY400 = 0x4D4D4D, GRAY500 = 0x5C5C5C, GRAY600 = 0x7B7B7B
        // GRAY700 = 0x999999, GRAY800 = 0xCDCDCD, GRAY900 = 0xFFFFFF
        // DARK_TEAL400 = 0x0D8A99, DARK_TEAL500 = 0x0A7080, DARK_TEAL600 = 0x075D6C

        // Text colors (bright for dark theme - improved readability)
        theme.setColor(ImGuiCol.Text, 0.90f, 0.90f, 0.90f, 1.00f);              // Enhanced GRAY800 for better contrast
        theme.setColor(ImGuiCol.TextDisabled, 0.482f, 0.482f, 0.482f, 1.00f);   // GRAY600 #7B7B7B (slightly brighter)

        // Window colors
        theme.setColor(ImGuiCol.WindowBg, 0.196f, 0.196f, 0.196f, 1.00f);       // GRAY100 #323232
        theme.setColor(ImGuiCol.ChildBg, 0.00f, 0.00f, 0.00f, 0.00f);           // Transparent
        theme.setColor(ImGuiCol.PopupBg, 0.145f, 0.145f, 0.145f, 1.00f);        // GRAY50 #252525

        // Border colors - enhanced visibility
        theme.setColor(ImGuiCol.Border, 0.302f, 0.302f, 0.302f, 0.80f);         // GRAY400 #4D4D4D with higher opacity
        theme.setColor(ImGuiCol.BorderShadow, 0.00f, 0.00f, 0.00f, 0.00f);      // None/Transparent

        // Frame colors (inputs, buttons) - enhanced contrast
        theme.setColor(ImGuiCol.FrameBg, 0.145f, 0.145f, 0.145f, 1.00f);        // GRAY50 #252525 (darker for better separation)
        theme.setColor(ImGuiCol.FrameBgHovered, 0.243f, 0.243f, 0.243f, 1.00f); // GRAY300 #3E3E3E (more noticeable hover)
        theme.setColor(ImGuiCol.FrameBgActive, 0.302f, 0.302f, 0.302f, 1.00f);  // GRAY400 #4D4D4D (distinct active state)

        // Title bar
        theme.setColor(ImGuiCol.TitleBg, 0.243f, 0.243f, 0.243f, 1.00f);        // GRAY300 #3E3E3E
        theme.setColor(ImGuiCol.TitleBgActive, 0.224f, 0.224f, 0.224f, 1.00f);  // GRAY200 #393939
        theme.setColor(ImGuiCol.TitleBgCollapsed, 0.302f, 0.302f, 0.302f, 1.00f); // GRAY400 #4D4D4D

        // Menu bar
        theme.setColor(ImGuiCol.MenuBarBg, 0.196f, 0.196f, 0.196f, 1.00f);      // GRAY100 #323232

        // Scrollbar
        theme.setColor(ImGuiCol.ScrollbarBg, 0.196f, 0.196f, 0.196f, 1.00f);    // GRAY100 #323232
        theme.setColor(ImGuiCol.ScrollbarGrab, 0.302f, 0.302f, 0.302f, 1.00f);  // GRAY400 #4D4D4D
        theme.setColor(ImGuiCol.ScrollbarGrabHovered, 0.482f, 0.482f, 0.482f, 1.00f); // GRAY600 #7B7B7B
        theme.setColor(ImGuiCol.ScrollbarGrabActive, 0.600f, 0.600f, 0.600f, 1.00f);  // GRAY700 #999999

        // Check mark
        theme.setColor(ImGuiCol.CheckMark, 0.039f, 0.439f, 0.502f, 1.00f);      // DARK_TEAL500 #0A7080

        // Slider
        theme.setColor(ImGuiCol.SliderGrab, 0.600f, 0.600f, 0.600f, 1.00f);     // GRAY700 #999999
        theme.setColor(ImGuiCol.SliderGrabActive, 0.804f, 0.804f, 0.804f, 1.00f); // GRAY800 #CDCDCD

        // Button - enhanced contrast
        theme.setColor(ImGuiCol.Button, 0.145f, 0.145f, 0.145f, 1.00f);         // GRAY50 #252525 (darker for better separation)
        theme.setColor(ImGuiCol.ButtonHovered, 0.243f, 0.243f, 0.243f, 1.00f);  // GRAY300 #3E3E3E (more noticeable hover)
        theme.setColor(ImGuiCol.ButtonActive, 0.302f, 0.302f, 0.302f, 1.00f);   // GRAY400 #4D4D4D (distinct active state)

        // Header (collapsing headers, selectables)
        theme.setColor(ImGuiCol.Header, 0.051f, 0.541f, 0.600f, 1.00f);         // DARK_TEAL400 #0D8A99
        theme.setColor(ImGuiCol.HeaderHovered, 0.039f, 0.439f, 0.502f, 1.00f);  // DARK_TEAL500 #0A7080
        theme.setColor(ImGuiCol.HeaderActive, 0.027f, 0.365f, 0.424f, 1.00f);   // DARK_TEAL600 #075D6C

        // Separator - enhanced visibility
        theme.setColor(ImGuiCol.Separator, 0.361f, 0.361f, 0.361f, 0.70f);      // GRAY500 #5C5C5C with higher opacity
        theme.setColor(ImGuiCol.SeparatorHovered, 0.482f, 0.482f, 0.482f, 1.00f); // GRAY600 #7B7B7B
        theme.setColor(ImGuiCol.SeparatorActive, 0.600f, 0.600f, 0.600f, 1.00f);  // GRAY700 #999999

        // Resize grip
        theme.setColor(ImGuiCol.ResizeGrip, 0.302f, 0.302f, 0.302f, 1.00f);     // GRAY400 #4D4D4D
        theme.setColor(ImGuiCol.ResizeGripHovered, 0.482f, 0.482f, 0.482f, 1.00f); // GRAY600 #7B7B7B
        theme.setColor(ImGuiCol.ResizeGripActive, 0.600f, 0.600f, 0.600f, 1.00f);  // GRAY700 #999999

        // Plot colors
        theme.setColor(ImGuiCol.PlotLines, 0.051f, 0.541f, 0.600f, 1.00f);      // DARK_TEAL400 #0D8A99
        theme.setColor(ImGuiCol.PlotLinesHovered, 0.027f, 0.365f, 0.424f, 1.00f); // DARK_TEAL600 #075D6C
        theme.setColor(ImGuiCol.PlotHistogram, 0.051f, 0.541f, 0.600f, 1.00f);  // DARK_TEAL400 #0D8A99
        theme.setColor(ImGuiCol.PlotHistogramHovered, 0.027f, 0.365f, 0.424f, 1.00f); // DARK_TEAL600 #075D6C

        // Text selection
        theme.setColor(ImGuiCol.TextSelectedBg, 0.051f, 0.541f, 0.600f, 0.35f); // DARK_TEAL400 with alpha

        // Drag and drop
        theme.setColor(ImGuiCol.DragDropTarget, 1.00f, 1.00f, 0.00f, 0.90f);    // Yellow

        // Navigation
        theme.setColor(ImGuiCol.NavHighlight, 1.00f, 1.00f, 1.00f, 0.04f);      // GRAY900 with alpha
        theme.setColor(ImGuiCol.NavWindowingHighlight, 1.00f, 1.00f, 1.00f, 0.70f);
        theme.setColor(ImGuiCol.NavWindowingDimBg, 0.80f, 0.80f, 0.80f, 0.20f);

        // Modal
        theme.setColor(ImGuiCol.ModalWindowDimBg, 0.20f, 0.20f, 0.20f, 0.35f);

        // Table colors
        theme.setColor(ImGuiCol.TableHeaderBg, 0.243f, 0.243f, 0.243f, 1.00f);  // GRAY300 #3E3E3E
        theme.setColor(ImGuiCol.TableBorderStrong, 0.302f, 0.302f, 0.302f, 1.00f); // GRAY400 #4D4D4D
        theme.setColor(ImGuiCol.TableBorderLight, 0.224f, 0.224f, 0.224f, 1.00f);  // GRAY200 #393939
        theme.setColor(ImGuiCol.TableRowBg, 0.00f, 0.00f, 0.00f, 0.00f);        // Transparent
        theme.setColor(ImGuiCol.TableRowBgAlt, 0.804f, 0.804f, 0.804f, 0.09f);  // GRAY800 with alpha

        // Tab colors - enhanced contrast
        theme.setColor(ImGuiCol.Tab, 0.145f, 0.145f, 0.145f, 1.00f);            // GRAY50 #252525 (inactive tabs darker for better separation)
        theme.setColor(ImGuiCol.TabHovered, 0.039f, 0.439f, 0.502f, 0.80f);     // DARK_TEAL500 with alpha
        theme.setColor(ImGuiCol.TabActive, 0.302f, 0.302f, 0.302f, 1.00f);      // GRAY400 #4D4D4D (active tab much lighter/more visible)
        theme.setColor(ImGuiCol.TabUnfocused, 0.145f, 0.145f, 0.145f, 1.00f);   // GRAY50 #252525
        theme.setColor(ImGuiCol.TabUnfocusedActive, 0.224f, 0.224f, 0.224f, 1.00f); // GRAY200 #393939 (moderately visible)

        // Style variables - Adobe Spectrum uses rounded corners with GrabRounding = 4.0f
        theme.setStyleVar(ImGuiStyleVar.WindowRounding, 4.0f);
        theme.setStyleVar(ImGuiStyleVar.ChildRounding, 4.0f);
        theme.setStyleVar(ImGuiStyleVar.FrameRounding, 4.0f);
        theme.setStyleVar(ImGuiStyleVar.PopupRounding, 4.0f);
        theme.setStyleVar(ImGuiStyleVar.ScrollbarRounding, 4.0f);
        theme.setStyleVar(ImGuiStyleVar.GrabRounding, 4.0f);
        theme.setStyleVar(ImGuiStyleVar.TabRounding, 4.0f);
        theme.setStyleVar(ImGuiStyleVar.WindowBorderSize, 1.0f);
        theme.setStyleVar(ImGuiStyleVar.ChildBorderSize, 1.0f);
        theme.setStyleVar(ImGuiStyleVar.PopupBorderSize, 1.0f);
        theme.setStyleVar(ImGuiStyleVar.FrameBorderSize, 0.0f);

        logger.debug("Created Mason Spectrum Dark theme with {} colors and {} style vars",
                     theme.getColorCount(), theme.getStyleVarCount());

        // Mark as read-only AFTER all colors and styles are set
        theme.setReadOnly(true);
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
            createBlueTheme(),
            createAdobeSpectrumLightTheme(),
            createAdobeSpectrumDarkTheme()
        );
        logger.info("Generated {} built-in themes", themes.size());
        return themes;
    }

}