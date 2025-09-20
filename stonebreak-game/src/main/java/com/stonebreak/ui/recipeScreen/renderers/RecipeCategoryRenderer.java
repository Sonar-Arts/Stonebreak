package com.stonebreak.ui.recipeScreen.renderers;

import com.stonebreak.input.InputHandler;
import com.stonebreak.rendering.UI.UIRenderer;
import com.stonebreak.ui.recipeScreen.renderers.RecipeUIStyleRenderer.*;
import org.joml.Vector2f;
import org.lwjgl.nanovg.NVGColor;
import org.lwjgl.nanovg.NVGPaint;
import org.lwjgl.system.MemoryStack;

import java.util.HashMap;
import java.util.Map;

import static org.lwjgl.nanovg.NanoVG.*;
import static org.lwjgl.system.MemoryStack.stackPush;

/**
 * Advanced Recipe Category Renderer with Sophisticated Visual Design
 *
 * Features:
 * - Recipe-themed category button styling with cooking-inspired colors
 * - Advanced visual effects with elevation and material design
 * - Smooth animation and transition support for interactive states
 * - Integration with RecipePanelRenderer for consistent theming
 * - Performance-optimized rendering with state management
 * - Rich visual feedback systems and accessibility features
 */
public class RecipeCategoryRenderer {

    // ===========================================
    // RECIPE CATEGORY THEME SYSTEM
    // ===========================================

    public static class CategoryTheme {
        // Category-specific color schemes
        public static final Map<String, ColorSpec> CATEGORY_COLORS = new HashMap<>() {{
            put("FOOD", new ColorSpec(255, 140, 0, 200));           // Orange - cooking
            put("TOOLS", new ColorSpec(150, 150, 150, 200));        // Gray - metal tools
            put("BUILDING", new ColorSpec(139, 94, 60, 200));       // Brown - wood/stone
            put("DECORATIVE", new ColorSpec(147, 112, 219, 200));   // Purple - decorative
            put("ADVANCED", new ColorSpec(255, 215, 0, 200));       // Gold - advanced crafting
            put("WEAPONS", new ColorSpec(220, 20, 60, 200));        // Crimson - weapons
            put("ARMOR", new ColorSpec(70, 130, 180, 200));         // Steel blue - armor
            put("MAGIC", new ColorSpec(138, 43, 226, 200));         // Blue violet - magic
            put("ALL", new ColorSpec(100, 149, 237, 200));          // Cornflower blue - all recipes
        }};

        // Button state colors
        public static final ColorSpec BUTTON_NORMAL = new ColorSpec(78, 59, 43, 255);
        public static final ColorSpec BUTTON_HOVER = new ColorSpec(98, 74, 54, 255);
        public static final ColorSpec BUTTON_SELECTED = new ColorSpec(139, 94, 60, 255);
        public static final ColorSpec BUTTON_PRESSED = new ColorSpec(58, 44, 32, 255);

        // Text colors
        public static final ColorSpec TEXT_NORMAL = new ColorSpec(255, 235, 205, 255);
        public static final ColorSpec TEXT_SELECTED = new ColorSpec(255, 255, 255, 255);
        public static final ColorSpec TEXT_SHADOW = new ColorSpec(20, 15, 10, 180);

        // Visual effects
        public static final ColorSpec GLOW_SELECTED = new ColorSpec(255, 215, 0, 120);
        public static final ColorSpec HIGHLIGHT_SUBTLE = new ColorSpec(255, 255, 255, 30);
    }

    // Animation state management
    private static final Map<String, AnimationState> buttonAnimations = new HashMap<>();
    private static final Map<String, Float> buttonHoverStates = new HashMap<>();

    private RecipeCategoryRenderer() {
        // Utility class - prevent instantiation
    }

    // ===========================================
    // ADVANCED CATEGORY BUTTON RENDERING
    // ===========================================

    /**
     * Draws sophisticated category buttons with recipe theming and animations
     */
    public static void drawCategoryButtons(UIRenderer uiRenderer, InputHandler inputHandler, int x, int y,
                                         int width, String[] categories, String selectedCategory) {
        int buttonSpacing = 8;
        int buttonWidth = (width - (categories.length - 1) * buttonSpacing) / categories.length;
        int buttonHeight = 32; // Increased height for better visual presence

        for (int i = 0; i < categories.length; i++) {
            int buttonX = x + i * (buttonWidth + buttonSpacing);
            boolean isSelected = categories[i].equals(selectedCategory);

            // Determine button state
            Vector2f mousePos = inputHandler.getMousePosition();
            boolean isHovering = mousePos.x >= buttonX && mousePos.x <= buttonX + buttonWidth &&
                               mousePos.y >= y && mousePos.y <= y + buttonHeight;

            UIState buttonState = isSelected ? UIState.SELECTED :
                                (isHovering ? UIState.HOVER : UIState.NORMAL);

            drawAdvancedCategoryButton(uiRenderer, buttonX, y, buttonWidth, buttonHeight,
                                     categories[i], buttonState, isSelected);
        }
    }

    /**
     * Draws sophisticated category button with advanced styling and animations
     */
    public static void drawCategoryButton(UIRenderer uiRenderer, InputHandler inputHandler, int x, int y,
                                        int width, int height, String text, boolean selected) {
        Vector2f mousePos = inputHandler.getMousePosition();
        boolean isHovering = mousePos.x >= x && mousePos.x <= x + width &&
                           mousePos.y >= y && mousePos.y <= y + height;

        UIState buttonState = selected ? UIState.SELECTED :
                            (isHovering ? UIState.HOVER : UIState.NORMAL);

        drawAdvancedCategoryButton(uiRenderer, x, y, width, height, text, buttonState, selected);
    }

    /**
     * Core method for drawing advanced category buttons with sophisticated styling
     */
    private static void drawAdvancedCategoryButton(UIRenderer uiRenderer, int x, int y, int width, int height,
                                                 String category, UIState state, boolean selected) {
        try (MemoryStack stack = stackPush()) {
            long vg = uiRenderer.getVG();
            float cornerRadius = 8.0f;

            // Update animation states
            String animKey = category + "_" + x + "_" + y;
            float animationProgress = updateButtonAnimation(animKey, state);

            // Get category-specific color
            ColorSpec categoryColor = CategoryTheme.CATEGORY_COLORS.getOrDefault(
                category.toUpperCase(), CategoryTheme.CATEGORY_COLORS.get("ALL"));

            // Draw button with material design elevation
            int elevation = switch (state) {
                case HOVER -> 3;
                case PRESSED -> 1;
                case SELECTED -> 4;
                default -> 2;
            };

            drawMaterialCategoryButton(vg, x, y, width, height, elevation, cornerRadius,
                                     state, categoryColor, animationProgress, stack);

            // Draw button text with enhanced typography
            drawCategoryButtonText(vg, x + width / 2.0f, y + height / 2.0f, category,
                                 state, animationProgress, stack);

            // Add visual feedback effects
            if (state == UIState.SELECTED) {
                drawSelectionGlow(vg, x, y, width, height, cornerRadius, animationProgress, stack);
            }
        }
    }

    /**
     * Draws material design category button with elevation and sophisticated styling
     */
    private static void drawMaterialCategoryButton(long vg, float x, float y, float width, float height,
                                                 int elevation, float cornerRadius, UIState state,
                                                 ColorSpec categoryColor, float animationProgress,
                                                 MemoryStack stack) {
        // Button background with gradient
        ColorSpec baseColor = switch (state) {
            case HOVER -> CategoryTheme.BUTTON_HOVER;
            case PRESSED -> CategoryTheme.BUTTON_PRESSED;
            case SELECTED -> CategoryTheme.BUTTON_SELECTED;
            default -> CategoryTheme.BUTTON_NORMAL;
        };

        // Draw material surface with elevation
        RecipeUIStyleRenderer.drawMaterialSurface(vg, x, y, width, height, elevation, cornerRadius, stack);

        // Draw button background with gradient
        NVGPaint buttonPaint = NVGPaint.malloc(stack);
        nvgLinearGradient(vg, x, y, x, y + height,
                         baseColor.lighter(0.05f + animationProgress * 0.1f).toNVG(NVGColor.malloc(stack)),
                         baseColor.darker(0.05f).toNVG(NVGColor.malloc(stack)),
                         buttonPaint);

        nvgBeginPath(vg);
        nvgRoundedRect(vg, x, y, width, height, cornerRadius);
        nvgFillPaint(vg, buttonPaint);
        nvgFill(vg);

        // Enhanced border with category color accent
        nvgBeginPath(vg);
        nvgRoundedRect(vg, x + 1, y + 1, width - 2, height - 2, cornerRadius - 1);
        nvgStrokeWidth(vg, 1.5f);

        ColorSpec borderColor = state == UIState.SELECTED ?
            categoryColor : baseColor.lighter(0.2f);
        nvgStrokeColor(vg, borderColor.toNVG(NVGColor.malloc(stack)));
        nvgStroke(vg);

        // Add subtle highlight for depth
        nvgBeginPath(vg);
        nvgRoundedRect(vg, x + 2, y + 2, width - 4, height * 0.4f, cornerRadius - 2);
        nvgFillColor(vg, CategoryTheme.HIGHLIGHT_SUBTLE.toNVG(NVGColor.malloc(stack)));
        nvgFill(vg);
    }

    /**
     * Draws enhanced category button text with shadows and effects
     */
    private static void drawCategoryButtonText(long vg, float centerX, float centerY, String category,
                                             UIState state, float animationProgress, MemoryStack stack) {
        float baseFontSize = 13.0f;
        float stateMultiplier = switch (state) {
            case HOVER -> 1.05f;
            case PRESSED -> 0.98f;
            case SELECTED -> 1.1f;
            default -> 1.0f;
        };

        float fontSize = baseFontSize * (stateMultiplier + animationProgress * 0.03f);

        // Draw text shadow for depth
        nvgFontSize(vg, fontSize);
        nvgFontFace(vg, "sans-bold");
        nvgTextAlign(vg, NVG_ALIGN_CENTER | NVG_ALIGN_MIDDLE);
        nvgFillColor(vg, CategoryTheme.TEXT_SHADOW.toNVG(NVGColor.malloc(stack)));
        nvgText(vg, centerX + 1, centerY + 1, category);

        // Draw main text
        ColorSpec textColor = state == UIState.SELECTED ?
            CategoryTheme.TEXT_SELECTED : CategoryTheme.TEXT_NORMAL;

        nvgFillColor(vg, textColor.toNVG(NVGColor.malloc(stack)));
        nvgText(vg, centerX, centerY, category);

        // Add subtle text highlight
        if (state == UIState.SELECTED) {
            nvgFillColor(vg, CategoryTheme.HIGHLIGHT_SUBTLE.toNVG(NVGColor.malloc(stack)));
            nvgText(vg, centerX, centerY - 0.5f, category);
        }
    }

    /**
     * Draws selection glow effect for selected category buttons
     */
    private static void drawSelectionGlow(long vg, float x, float y, float width, float height,
                                        float cornerRadius, float animationProgress, MemoryStack stack) {
        float pulse = (float) (Math.sin(System.currentTimeMillis() * 0.003f) * 0.3f + 0.7f);
        float glowIntensity = 0.4f + pulse * 0.3f + animationProgress * 0.2f;

        RecipeUIStyleRenderer.drawGlowEffect(vg, x, y, width, height, cornerRadius,
                                           CategoryTheme.GLOW_SELECTED, glowIntensity, stack);
    }

    // ===========================================
    // ENHANCED CATEGORY INDICATORS
    // ===========================================

    /**
     * Draws recipe category indicator with themed styling
     */
    public static void drawRecipeCategoryIndicator(UIRenderer uiRenderer, float x, float y,
                                                 String category, ColorSpec categoryColor) {
        try (MemoryStack stack = stackPush()) {
            long vg = uiRenderer.getVG();

            float indicatorWidth = 80.0f;
            float indicatorHeight = 24.0f;
            float cornerRadius = 12.0f;

            // Draw category background
            nvgBeginPath(vg);
            nvgRoundedRect(vg, x, y, indicatorWidth, indicatorHeight, cornerRadius);
            nvgFillColor(vg, categoryColor.toNVG(NVGColor.malloc(stack)));
            nvgFill(vg);

            // Draw category border
            nvgBeginPath(vg);
            nvgRoundedRect(vg, x + 1, y + 1, indicatorWidth - 2, indicatorHeight - 2, cornerRadius - 1);
            nvgStrokeWidth(vg, 1.0f);
            nvgStrokeColor(vg, categoryColor.darker(0.3f).toNVG(NVGColor.malloc(stack)));
            nvgStroke(vg);

            // Draw category text
            nvgFontSize(vg, 12.0f);
            nvgFontFace(vg, "sans-bold");
            nvgTextAlign(vg, NVG_ALIGN_CENTER | NVG_ALIGN_MIDDLE);
            nvgFillColor(vg, new ColorSpec(255, 255, 255, 255).toNVG(NVGColor.malloc(stack)));
            nvgText(vg, x + indicatorWidth / 2, y + indicatorHeight / 2, category);
        }
    }

    /**
     * Draws enhanced category indicator strip with recipe theme
     */
    public static void drawCategoryIndicatorStrip(UIRenderer uiRenderer, float x, float y, float width,
                                                 String[] categories, String selectedCategory) {
        try (MemoryStack stack = stackPush()) {
            long vg = uiRenderer.getVG();
            float stripHeight = 4.0f;
            float cornerRadius = 2.0f;

            // Background strip
            nvgBeginPath(vg);
            nvgRoundedRect(vg, x, y, width, stripHeight, cornerRadius);
            nvgFillColor(vg, CategoryTheme.BUTTON_NORMAL.darker(0.3f).toNVG(NVGColor.malloc(stack)));
            nvgFill(vg);

            // Active category indicator
            if (selectedCategory != null) {
                int selectedIndex = -1;
                for (int i = 0; i < categories.length; i++) {
                    if (categories[i].equals(selectedCategory)) {
                        selectedIndex = i;
                        break;
                    }
                }

                if (selectedIndex >= 0) {
                    float indicatorWidth = width / categories.length;
                    float indicatorX = x + selectedIndex * indicatorWidth;

                    ColorSpec categoryColor = CategoryTheme.CATEGORY_COLORS.getOrDefault(
                        selectedCategory.toUpperCase(), CategoryTheme.CATEGORY_COLORS.get("ALL"));

                    nvgBeginPath(vg);
                    nvgRoundedRect(vg, indicatorX, y, indicatorWidth, stripHeight, cornerRadius);
                    nvgFillColor(vg, categoryColor.toNVG(NVGColor.malloc(stack)));
                    nvgFill(vg);
                }
            }
        }
    }

    /**
     * Draws category badge with count and styling
     */
    public static void drawCategoryBadge(UIRenderer uiRenderer, float x, float y, String category,
                                       int recipeCount, boolean selected) {
        try (MemoryStack stack = stackPush()) {
            long vg = uiRenderer.getVG();

            ColorSpec categoryColor = CategoryTheme.CATEGORY_COLORS.getOrDefault(
                category.toUpperCase(), CategoryTheme.CATEGORY_COLORS.get("ALL"));

            // Use the sophisticated category indicator
            drawRecipeCategoryIndicator(uiRenderer, x, y, category, categoryColor);

            // Add recipe count if needed
            if (recipeCount > 0) {
                String countText = String.valueOf(recipeCount);
                nvgFontSize(vg, 10.0f);
                nvgFontFace(vg, "sans-bold");
                nvgTextAlign(vg, NVG_ALIGN_CENTER | NVG_ALIGN_MIDDLE);
                nvgFillColor(vg, new ColorSpec(255, 255, 255, 200).toNVG(NVGColor.malloc(stack)));
                nvgText(vg, x + 65, y + 12, countText);
            }
        }
    }

    // ===========================================
    // ANIMATION AND STATE MANAGEMENT
    // ===========================================

    /**
     * Updates button animation state and returns current progress
     */
    private static float updateButtonAnimation(String buttonKey, UIState newState) {
        Float currentHover = buttonHoverStates.get(buttonKey);
        if (currentHover == null) currentHover = 0.0f;

        float targetHover = switch (newState) {
            case HOVER, SELECTED -> 1.0f;
            default -> 0.0f;
        };

        // Smooth animation
        float animationSpeed = 0.1f;
        float newHover = currentHover + (targetHover - currentHover) * animationSpeed;
        buttonHoverStates.put(buttonKey, newHover);

        return newHover;
    }

    /**
     * Clears animation states for performance
     */
    public static void clearAnimationStates() {
        buttonAnimations.clear();
        buttonHoverStates.clear();
    }

    // ===========================================
    // UTILITY METHODS
    // ===========================================

    /**
     * Gets category color for external use
     */
    public static ColorSpec getCategoryColor(String category) {
        return CategoryTheme.CATEGORY_COLORS.getOrDefault(
            category.toUpperCase(), CategoryTheme.CATEGORY_COLORS.get("ALL"));
    }

    /**
     * Registers a custom category color
     */
    public static void registerCategoryColor(String category, ColorSpec color) {
        CategoryTheme.CATEGORY_COLORS.put(category.toUpperCase(), color);
    }

    /**
     * Performs cleanup of cached resources
     */
    public static void performCleanup() {
        clearAnimationStates();
        RecipeUIStyleRenderer.performCacheCleanup(60000);
    }
}