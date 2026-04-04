package com.openmason.main.systems.themes.registry;
import com.openmason.main.systems.themes.core.ThemeDefinition;

import imgui.flag.ImGuiCol;
import imgui.flag.ImGuiStyleVar;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Arrays;
import java.util.List;

/**
 * Built-in color schemes and theme creators.
 * Contains all built-in theme creation methods and color definitions.
 */
public class ColorPalette {
    private static final Logger logger = LoggerFactory.getLogger(ColorPalette.class);

    /**
     * Create Mason Spectrum Dark theme — the primary Open Mason theme.
     * Warm-neutral grays with a restrained steel-blue accent palette.
     */
    public static ThemeDefinition createMasonSpectrumDarkTheme() {
        ThemeDefinition theme = new ThemeDefinition("mason-spectrum-dark", "Mason Spectrum Dark",
            "Professional dark theme for extended usage", ThemeDefinition.ThemeType.BUILT_IN);

        // === Accent palette ===
        // Primary accent: muted steel-blue  #5B9BD5 → (0.357, 0.608, 0.835)
        // Accent hover:                     #6DADE5 → (0.427, 0.678, 0.898)
        // Accent active/pressed:            #4A88BF → (0.290, 0.533, 0.749)

        // === Base surface colours (warm-neutral) ===
        theme.setColor(ImGuiCol.WindowBg,       0.114f, 0.118f, 0.125f, 1.00f); // #1D1E20
        theme.setColor(ImGuiCol.ChildBg,        0.125f, 0.129f, 0.137f, 1.00f); // #202123
        theme.setColor(ImGuiCol.PopupBg,        0.098f, 0.102f, 0.110f, 0.98f); // #191A1C

        // === Frame colours (inputs, combo boxes, sliders) ===
        theme.setColor(ImGuiCol.FrameBg,        0.176f, 0.180f, 0.192f, 1.00f); // #2D2E31
        theme.setColor(ImGuiCol.FrameBgHovered, 0.220f, 0.224f, 0.239f, 1.00f); // #38393D
        theme.setColor(ImGuiCol.FrameBgActive,  0.263f, 0.267f, 0.282f, 1.00f); // #434448

        // === Title bar ===
        theme.setColor(ImGuiCol.TitleBg,          0.098f, 0.102f, 0.110f, 1.00f); // #191A1C
        theme.setColor(ImGuiCol.TitleBgActive,    0.137f, 0.141f, 0.149f, 1.00f); // #232425
        theme.setColor(ImGuiCol.TitleBgCollapsed,  0.078f, 0.082f, 0.090f, 0.75f); // #141517

        // === Menu bar ===
        theme.setColor(ImGuiCol.MenuBarBg, 0.114f, 0.118f, 0.125f, 1.00f);       // matches WindowBg

        // === Scrollbar ===
        theme.setColor(ImGuiCol.ScrollbarBg,          0.098f, 0.102f, 0.110f, 0.40f);
        theme.setColor(ImGuiCol.ScrollbarGrab,         0.290f, 0.294f, 0.310f, 0.80f);
        theme.setColor(ImGuiCol.ScrollbarGrabHovered,  0.380f, 0.384f, 0.400f, 0.90f);
        theme.setColor(ImGuiCol.ScrollbarGrabActive,   0.460f, 0.464f, 0.480f, 1.00f);

        // === Check mark / slider grab — accent ===
        theme.setColor(ImGuiCol.CheckMark,       0.357f, 0.608f, 0.835f, 1.00f); // #5B9BD5
        theme.setColor(ImGuiCol.SliderGrab,      0.357f, 0.608f, 0.835f, 0.85f);
        theme.setColor(ImGuiCol.SliderGrabActive, 0.427f, 0.678f, 0.898f, 1.00f);

        // === Buttons — neutral surface with accent on hover ===
        theme.setColor(ImGuiCol.Button,        0.200f, 0.204f, 0.216f, 1.00f); // #333437
        theme.setColor(ImGuiCol.ButtonHovered, 0.357f, 0.608f, 0.835f, 0.75f); // accent with alpha
        theme.setColor(ImGuiCol.ButtonActive,  0.290f, 0.533f, 0.749f, 1.00f); // accent pressed

        // === Headers (collapsing headers, selectables, tree nodes) ===
        theme.setColor(ImGuiCol.Header,        0.357f, 0.608f, 0.835f, 0.20f);
        theme.setColor(ImGuiCol.HeaderHovered, 0.357f, 0.608f, 0.835f, 0.55f);
        theme.setColor(ImGuiCol.HeaderActive,  0.357f, 0.608f, 0.835f, 0.78f);

        // === Separators ===
        theme.setColor(ImGuiCol.Separator,        0.255f, 0.259f, 0.275f, 0.65f);
        theme.setColor(ImGuiCol.SeparatorHovered, 0.357f, 0.608f, 0.835f, 0.60f);
        theme.setColor(ImGuiCol.SeparatorActive,  0.357f, 0.608f, 0.835f, 0.90f);

        // === Resize grip ===
        theme.setColor(ImGuiCol.ResizeGrip,        0.357f, 0.608f, 0.835f, 0.15f);
        theme.setColor(ImGuiCol.ResizeGripHovered, 0.357f, 0.608f, 0.835f, 0.50f);
        theme.setColor(ImGuiCol.ResizeGripActive,  0.357f, 0.608f, 0.835f, 0.85f);

        // === Tabs ===
        theme.setColor(ImGuiCol.Tab,                0.153f, 0.157f, 0.169f, 1.00f);
        theme.setColor(ImGuiCol.TabHovered,         0.357f, 0.608f, 0.835f, 0.65f);
        theme.setColor(ImGuiCol.TabActive,          0.200f, 0.208f, 0.224f, 1.00f);
        theme.setColor(ImGuiCol.TabUnfocused,       0.114f, 0.118f, 0.125f, 0.97f);
        theme.setColor(ImGuiCol.TabUnfocusedActive, 0.165f, 0.173f, 0.188f, 1.00f);

        // === Plot colours ===
        theme.setColor(ImGuiCol.PlotLines,            0.525f, 0.545f, 0.580f, 1.00f);
        theme.setColor(ImGuiCol.PlotLinesHovered,     0.949f, 0.502f, 0.306f, 1.00f);
        theme.setColor(ImGuiCol.PlotHistogram,        0.357f, 0.608f, 0.835f, 1.00f);
        theme.setColor(ImGuiCol.PlotHistogramHovered, 0.427f, 0.678f, 0.898f, 1.00f);

        // === Table ===
        theme.setColor(ImGuiCol.TableHeaderBg,     0.153f, 0.157f, 0.169f, 1.00f);
        theme.setColor(ImGuiCol.TableBorderStrong,  0.235f, 0.239f, 0.255f, 1.00f);
        theme.setColor(ImGuiCol.TableBorderLight,   0.192f, 0.196f, 0.212f, 1.00f);
        theme.setColor(ImGuiCol.TableRowBg,         0.00f, 0.00f, 0.00f, 0.00f);
        theme.setColor(ImGuiCol.TableRowBgAlt,      1.00f, 1.00f, 1.00f, 0.025f);

        // === Text ===
        theme.setColor(ImGuiCol.Text,         0.882f, 0.890f, 0.906f, 1.00f); // #E1E3E8 off-white
        theme.setColor(ImGuiCol.TextDisabled, 0.443f, 0.451f, 0.475f, 1.00f); // #71737A muted

        // === Borders ===
        theme.setColor(ImGuiCol.Border,       0.220f, 0.224f, 0.239f, 0.60f);
        theme.setColor(ImGuiCol.BorderShadow, 0.00f, 0.00f, 0.00f, 0.00f);

        // === Text selection / Nav / Modal ===
        theme.setColor(ImGuiCol.TextSelectedBg,        0.357f, 0.608f, 0.835f, 0.30f);
        theme.setColor(ImGuiCol.DragDropTarget,         0.357f, 0.608f, 0.835f, 0.90f);
        theme.setColor(ImGuiCol.NavHighlight,           0.357f, 0.608f, 0.835f, 0.70f);
        theme.setColor(ImGuiCol.NavWindowingHighlight,  1.00f, 1.00f, 1.00f, 0.60f);
        theme.setColor(ImGuiCol.NavWindowingDimBg,      0.00f, 0.00f, 0.00f, 0.20f);
        theme.setColor(ImGuiCol.ModalWindowDimBg,       0.00f, 0.00f, 0.00f, 0.45f);

        // === Rounding ===
        theme.setStyleVar(ImGuiStyleVar.WindowRounding,    4.0f);
        theme.setStyleVar(ImGuiStyleVar.ChildRounding,     4.0f);
        theme.setStyleVar(ImGuiStyleVar.FrameRounding,     3.0f);
        theme.setStyleVar(ImGuiStyleVar.PopupRounding,     4.0f);
        theme.setStyleVar(ImGuiStyleVar.ScrollbarRounding, 6.0f);
        theme.setStyleVar(ImGuiStyleVar.GrabRounding,      3.0f);
        theme.setStyleVar(ImGuiStyleVar.TabRounding,       3.0f);

        // === Border sizes ===
        theme.setStyleVar(ImGuiStyleVar.WindowBorderSize, 1.0f);
        theme.setStyleVar(ImGuiStyleVar.ChildBorderSize,  1.0f);
        theme.setStyleVar(ImGuiStyleVar.PopupBorderSize,  1.0f);
        theme.setStyleVar(ImGuiStyleVar.FrameBorderSize,  0.0f);

        // === Scrollbar & grab ===
        theme.setStyleVar(ImGuiStyleVar.ScrollbarSize, 12.0f);
        theme.setStyleVar(ImGuiStyleVar.GrabMinSize,   8.0f);
        theme.setStyleVar(ImGuiStyleVar.IndentSpacing, 16.0f);

        // === Spacing & padding (Vec2) ===
        theme.setStyleVarVec2(ImGuiStyleVar.WindowPadding,    8.0f, 8.0f);
        theme.setStyleVarVec2(ImGuiStyleVar.FramePadding,     6.0f, 4.0f);
        theme.setStyleVarVec2(ImGuiStyleVar.ItemSpacing,      8.0f, 5.0f);
        theme.setStyleVarVec2(ImGuiStyleVar.ItemInnerSpacing, 6.0f, 4.0f);
        theme.setStyleVarVec2(ImGuiStyleVar.CellPadding,      4.0f, 3.0f);
        theme.setStyleVarVec2(ImGuiStyleVar.ButtonTextAlign,  0.5f, 0.5f);
        theme.setStyleVarVec2(ImGuiStyleVar.WindowTitleAlign, 0.0f, 0.5f);

        logger.trace("Created Mason Spectrum Dark theme with {} colors, {} float style vars, {} vec2 style vars",
                     theme.getColorCount(), theme.getStyleVarCount(), theme.getStyleVarsVec2().size());

        theme.setReadOnly(true);
        return theme;
    }

    /**
     * Create Mason Spectrum Light theme — warm gray surfaces to reduce eye strain.
     * Uses the same steel-blue accent as the dark variant for consistency.
     */
    public static ThemeDefinition createMasonSpectrumLightTheme() {
        ThemeDefinition theme = new ThemeDefinition("mason-spectrum-light", "Mason Spectrum Light",
            "Warm gray light theme — easy on the eyes", ThemeDefinition.ThemeType.BUILT_IN);

        // === Accent palette (shared with dark) ===
        // Primary accent: #3D7AB5 → (0.239, 0.478, 0.710) — slightly darker for light bg contrast
        // Accent hover:   #4A8AC5 → (0.290, 0.541, 0.773)
        // Accent pressed:  #2D6A9F → (0.176, 0.416, 0.624)

        // === Base surfaces — warm gray, NOT white ===
        theme.setColor(ImGuiCol.WindowBg,       0.835f, 0.835f, 0.831f, 1.00f); // #D5D5D4 warm mid-gray
        theme.setColor(ImGuiCol.ChildBg,        0.855f, 0.855f, 0.851f, 1.00f); // #DADAD9
        theme.setColor(ImGuiCol.PopupBg,        0.878f, 0.878f, 0.875f, 0.98f); // #E0E0DF

        // === Frame colours ===
        theme.setColor(ImGuiCol.FrameBg,        0.800f, 0.800f, 0.796f, 1.00f); // #CCCCCB
        theme.setColor(ImGuiCol.FrameBgHovered, 0.761f, 0.761f, 0.757f, 1.00f); // #C2C2C1
        theme.setColor(ImGuiCol.FrameBgActive,  0.722f, 0.722f, 0.718f, 1.00f); // #B8B8B7

        // === Title bar ===
        theme.setColor(ImGuiCol.TitleBg,          0.780f, 0.780f, 0.776f, 1.00f); // #C7C7C6
        theme.setColor(ImGuiCol.TitleBgActive,    0.757f, 0.757f, 0.753f, 1.00f); // #C1C1C0
        theme.setColor(ImGuiCol.TitleBgCollapsed,  0.820f, 0.820f, 0.816f, 0.75f); // #D1D1D0

        // === Menu bar ===
        theme.setColor(ImGuiCol.MenuBarBg, 0.835f, 0.835f, 0.831f, 1.00f);       // matches WindowBg

        // === Scrollbar ===
        theme.setColor(ImGuiCol.ScrollbarBg,          0.835f, 0.835f, 0.831f, 0.40f);
        theme.setColor(ImGuiCol.ScrollbarGrab,         0.620f, 0.620f, 0.616f, 0.80f);
        theme.setColor(ImGuiCol.ScrollbarGrabHovered,  0.541f, 0.541f, 0.537f, 0.90f);
        theme.setColor(ImGuiCol.ScrollbarGrabActive,   0.463f, 0.463f, 0.459f, 1.00f);

        // === Check mark / slider grab — accent ===
        theme.setColor(ImGuiCol.CheckMark,       0.239f, 0.478f, 0.710f, 1.00f); // #3D7AB5
        theme.setColor(ImGuiCol.SliderGrab,      0.239f, 0.478f, 0.710f, 0.85f);
        theme.setColor(ImGuiCol.SliderGrabActive, 0.176f, 0.416f, 0.624f, 1.00f);

        // === Buttons — slightly recessed surface with accent on hover ===
        theme.setColor(ImGuiCol.Button,        0.776f, 0.776f, 0.773f, 1.00f); // #C6C6C5
        theme.setColor(ImGuiCol.ButtonHovered, 0.290f, 0.541f, 0.773f, 0.75f); // accent hover
        theme.setColor(ImGuiCol.ButtonActive,  0.176f, 0.416f, 0.624f, 1.00f); // accent pressed

        // === Headers ===
        theme.setColor(ImGuiCol.Header,        0.239f, 0.478f, 0.710f, 0.22f);
        theme.setColor(ImGuiCol.HeaderHovered, 0.239f, 0.478f, 0.710f, 0.50f);
        theme.setColor(ImGuiCol.HeaderActive,  0.239f, 0.478f, 0.710f, 0.72f);

        // === Separators ===
        theme.setColor(ImGuiCol.Separator,        0.600f, 0.600f, 0.596f, 0.55f);
        theme.setColor(ImGuiCol.SeparatorHovered, 0.239f, 0.478f, 0.710f, 0.60f);
        theme.setColor(ImGuiCol.SeparatorActive,  0.239f, 0.478f, 0.710f, 0.90f);

        // === Resize grip ===
        theme.setColor(ImGuiCol.ResizeGrip,        0.239f, 0.478f, 0.710f, 0.15f);
        theme.setColor(ImGuiCol.ResizeGripHovered, 0.239f, 0.478f, 0.710f, 0.50f);
        theme.setColor(ImGuiCol.ResizeGripActive,  0.239f, 0.478f, 0.710f, 0.85f);

        // === Tabs ===
        theme.setColor(ImGuiCol.Tab,                0.796f, 0.796f, 0.792f, 1.00f);
        theme.setColor(ImGuiCol.TabHovered,         0.290f, 0.541f, 0.773f, 0.65f);
        theme.setColor(ImGuiCol.TabActive,          0.863f, 0.863f, 0.859f, 1.00f); // slightly lifted
        theme.setColor(ImGuiCol.TabUnfocused,       0.816f, 0.816f, 0.812f, 0.97f);
        theme.setColor(ImGuiCol.TabUnfocusedActive, 0.843f, 0.843f, 0.839f, 1.00f);

        // === Plot colours ===
        theme.setColor(ImGuiCol.PlotLines,            0.400f, 0.408f, 0.420f, 1.00f);
        theme.setColor(ImGuiCol.PlotLinesHovered,     0.886f, 0.396f, 0.259f, 1.00f);
        theme.setColor(ImGuiCol.PlotHistogram,        0.239f, 0.478f, 0.710f, 1.00f);
        theme.setColor(ImGuiCol.PlotHistogramHovered, 0.290f, 0.541f, 0.773f, 1.00f);

        // === Table ===
        theme.setColor(ImGuiCol.TableHeaderBg,     0.776f, 0.776f, 0.773f, 1.00f);
        theme.setColor(ImGuiCol.TableBorderStrong,  0.659f, 0.659f, 0.655f, 1.00f);
        theme.setColor(ImGuiCol.TableBorderLight,   0.737f, 0.737f, 0.733f, 1.00f);
        theme.setColor(ImGuiCol.TableRowBg,         0.00f, 0.00f, 0.00f, 0.00f);
        theme.setColor(ImGuiCol.TableRowBgAlt,      0.00f, 0.00f, 0.00f, 0.035f);

        // === Text — dark for readability, not pure black ===
        theme.setColor(ImGuiCol.Text,         0.157f, 0.161f, 0.173f, 1.00f); // #28292C
        theme.setColor(ImGuiCol.TextDisabled, 0.494f, 0.498f, 0.514f, 1.00f); // #7E7F83

        // === Borders ===
        theme.setColor(ImGuiCol.Border,       0.659f, 0.659f, 0.655f, 0.55f);
        theme.setColor(ImGuiCol.BorderShadow, 0.00f, 0.00f, 0.00f, 0.00f);

        // === Text selection / Nav / Modal ===
        theme.setColor(ImGuiCol.TextSelectedBg,        0.239f, 0.478f, 0.710f, 0.30f);
        theme.setColor(ImGuiCol.DragDropTarget,         0.239f, 0.478f, 0.710f, 0.90f);
        theme.setColor(ImGuiCol.NavHighlight,           0.239f, 0.478f, 0.710f, 0.70f);
        theme.setColor(ImGuiCol.NavWindowingHighlight,  0.00f, 0.00f, 0.00f, 0.60f);
        theme.setColor(ImGuiCol.NavWindowingDimBg,      0.00f, 0.00f, 0.00f, 0.12f);
        theme.setColor(ImGuiCol.ModalWindowDimBg,       0.00f, 0.00f, 0.00f, 0.35f);

        // === Rounding ===
        theme.setStyleVar(ImGuiStyleVar.WindowRounding,    4.0f);
        theme.setStyleVar(ImGuiStyleVar.ChildRounding,     4.0f);
        theme.setStyleVar(ImGuiStyleVar.FrameRounding,     3.0f);
        theme.setStyleVar(ImGuiStyleVar.PopupRounding,     4.0f);
        theme.setStyleVar(ImGuiStyleVar.ScrollbarRounding, 6.0f);
        theme.setStyleVar(ImGuiStyleVar.GrabRounding,      3.0f);
        theme.setStyleVar(ImGuiStyleVar.TabRounding,       3.0f);

        // === Border sizes ===
        theme.setStyleVar(ImGuiStyleVar.WindowBorderSize, 1.0f);
        theme.setStyleVar(ImGuiStyleVar.ChildBorderSize,  1.0f);
        theme.setStyleVar(ImGuiStyleVar.PopupBorderSize,  1.0f);
        theme.setStyleVar(ImGuiStyleVar.FrameBorderSize,  0.0f);

        // === Scrollbar & grab ===
        theme.setStyleVar(ImGuiStyleVar.ScrollbarSize, 12.0f);
        theme.setStyleVar(ImGuiStyleVar.GrabMinSize,   8.0f);
        theme.setStyleVar(ImGuiStyleVar.IndentSpacing, 16.0f);

        // === Spacing & padding (Vec2) ===
        theme.setStyleVarVec2(ImGuiStyleVar.WindowPadding,    8.0f, 8.0f);
        theme.setStyleVarVec2(ImGuiStyleVar.FramePadding,     6.0f, 4.0f);
        theme.setStyleVarVec2(ImGuiStyleVar.ItemSpacing,      8.0f, 5.0f);
        theme.setStyleVarVec2(ImGuiStyleVar.ItemInnerSpacing, 6.0f, 4.0f);
        theme.setStyleVarVec2(ImGuiStyleVar.CellPadding,      4.0f, 3.0f);
        theme.setStyleVarVec2(ImGuiStyleVar.ButtonTextAlign,  0.5f, 0.5f);
        theme.setStyleVarVec2(ImGuiStyleVar.WindowTitleAlign, 0.0f, 0.5f);

        logger.trace("Created Mason Spectrum Light theme with {} colors, {} float style vars, {} vec2 style vars",
                     theme.getColorCount(), theme.getStyleVarCount(), theme.getStyleVarsVec2().size());

        theme.setReadOnly(true);
        return theme;
    }

    /**
     * Get all available built-in themes
     */
    public static List<ThemeDefinition> getAllBuiltInThemes() {
        List<ThemeDefinition> themes = Arrays.asList(
            createMasonSpectrumDarkTheme(),
            createMasonSpectrumLightTheme()
        );
        logger.info("Generated {} built-in themes", themes.size());
        return themes;
    }

}
