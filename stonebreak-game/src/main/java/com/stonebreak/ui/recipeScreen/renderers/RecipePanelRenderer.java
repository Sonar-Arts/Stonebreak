package com.stonebreak.ui.recipeScreen.renderers;

import com.stonebreak.rendering.UI.UIRenderer;
import com.stonebreak.ui.recipeScreen.renderers.RecipeUIStyleRenderer.*;
import org.lwjgl.nanovg.NVGColor;
import org.lwjgl.nanovg.NVGPaint;
import org.lwjgl.system.MemoryStack;

import static org.lwjgl.nanovg.NanoVG.*;
import static org.lwjgl.system.MemoryStack.stackPush;

/**
 * Advanced Recipe Panel Renderer with Sophisticated Visual Design
 *
 * Features:
 * - Material design with elevation-based lighting and shadows
 * - Advanced typography with text effects and animations
 * - Recipe-specific themed styling and color schemes
 * - Interactive state management with smooth transitions
 * - Performance-optimized rendering with caching
 * - Rich visual feedback systems and accessibility features
 * - Integration with RecipeUIStyleRenderer for consistency
 */
public class RecipePanelRenderer {

    // ===========================================
    // RECIPE-SPECIFIC STYLING CONSTANTS
    // ===========================================

    public static class RecipeTheme {
        // Recipe Panel Colors (warmer, cooking-inspired palette)
        public static final ColorSpec PANEL_BACKGROUND = new ColorSpec(62, 48, 35, 245);
        public static final ColorSpec PANEL_BORDER = new ColorSpec(139, 94, 60, 255);
        public static final ColorSpec PANEL_HEADER = new ColorSpec(78, 59, 43, 255);

        // Title Styling
        public static final ColorSpec TITLE_PRIMARY = new ColorSpec(255, 235, 205, 255);
        public static final ColorSpec TITLE_SHADOW = new ColorSpec(20, 15, 10, 180);
        public static final ColorSpec TITLE_GLOW = new ColorSpec(255, 215, 0, 120);

        // Recipe Book Specific Colors
        public static final ColorSpec BOOK_SPINE = new ColorSpec(101, 67, 33, 255);
        public static final ColorSpec BOOK_PAGES = new ColorSpec(245, 235, 220, 255);
        public static final ColorSpec BOOK_BINDING = new ColorSpec(139, 94, 60, 255);

        // Interactive Elements
        public static final ColorSpec RECIPE_HIGHLIGHT = new ColorSpec(255, 193, 37, 160);
        public static final ColorSpec RECIPE_SELECTION = new ColorSpec(255, 140, 0, 200);

        // Depth and Shadows
        public static final ColorSpec DEEP_SHADOW = new ColorSpec(15, 10, 5, 100);
        public static final ColorSpec MEDIUM_SHADOW = new ColorSpec(25, 18, 12, 80);
        public static final ColorSpec LIGHT_SHADOW = new ColorSpec(35, 26, 18, 60);
    }

    // Panel animation states
    private static float panelAnimationTime = 0.0f;
    private static boolean isAnimating = false;

    private RecipePanelRenderer() {
        // Utility class - prevent instantiation
    }

    // ===========================================
    // ADVANCED PANEL RENDERING
    // ===========================================

    /**
     * Draws sophisticated recipe panel with material design and recipe book aesthetics
     */
    public static void drawRecipePanel(UIRenderer uiRenderer, int x, int y, int width, int height) {
        drawRecipePanel(uiRenderer, x, y, width, height, UIState.NORMAL, 0.0f);
    }

    /**
     * Draws advanced recipe panel with state management and animations
     */
    public static void drawRecipePanel(UIRenderer uiRenderer, int x, int y, int width, int height,
                                     UIState state, float animationProgress) {
        try (MemoryStack stack = stackPush()) {
            long vg = uiRenderer.getVG();
            float cornerRadius = 12.0f;

            // Update animation time
            panelAnimationTime += 16.0f; // Assuming 60 FPS

            // Draw book-like base shadow for depth
            drawBookBaseShadow(vg, x, y, width, height, cornerRadius, stack);

            // Draw main panel with sophisticated material design
            int elevation = switch (state) {
                case HOVER -> 6;
                case PRESSED -> 2;
                case FOCUSED, SELECTED -> 8;
                default -> 4;
            };

            drawAdvancedRecipePanel(vg, x, y, width, height, elevation, cornerRadius, animationProgress, stack);

            // Add recipe book spine effect
            drawBookSpineEffect(vg, x, y, width, height, cornerRadius, stack);

            // Add subtle page texture effect
            drawPageTextureEffect(vg, x, y, width, height, cornerRadius, animationProgress, stack);

            // Draw decorative corner elements
            drawDecorativeCorners(vg, x, y, width, height, cornerRadius, stack);

            // Add animated recipe sparkles for visual interest
            if (state == UIState.FOCUSED || state == UIState.SELECTED) {
                drawRecipeSparkles(vg, x, y, width, height, panelAnimationTime, stack);
            }
        }
    }

    /**
     * Draws book-like base shadow for authentic recipe book feel
     */
    private static void drawBookBaseShadow(long vg, float x, float y, float width, float height,
                                         float cornerRadius, MemoryStack stack) {
        // Large base shadow
        float shadowOffset = 8.0f;
        float shadowBlur = 16.0f;

        NVGPaint shadowPaint = NVGPaint.malloc(stack);
        nvgBoxGradient(vg, x + shadowOffset, y + shadowOffset, width, height,
                      cornerRadius, shadowBlur,
                      RecipeTheme.DEEP_SHADOW.toNVG(NVGColor.malloc(stack)),
                      RecipeTheme.DEEP_SHADOW.withAlpha(0).toNVG(NVGColor.malloc(stack)),
                      shadowPaint);

        nvgBeginPath(vg);
        nvgRoundedRect(vg, x + shadowOffset - shadowBlur, y + shadowOffset - shadowBlur,
                      width + 2 * shadowBlur, height + 2 * shadowBlur, cornerRadius + shadowBlur);
        nvgFillPaint(vg, shadowPaint);
        nvgFill(vg);
    }

    /**
     * Draws the main recipe panel with sophisticated styling
     */
    private static void drawAdvancedRecipePanel(long vg, float x, float y, float width, float height,
                                              int elevation, float cornerRadius, float animationProgress,
                                              MemoryStack stack) {
        // Main panel background with gradient
        NVGPaint panelPaint = NVGPaint.malloc(stack);
        nvgLinearGradient(vg, x, y, x, y + height,
                         RecipeTheme.PANEL_BACKGROUND.lighter(0.05f).toNVG(NVGColor.malloc(stack)),
                         RecipeTheme.PANEL_BACKGROUND.darker(0.1f).toNVG(NVGColor.malloc(stack)),
                         panelPaint);

        nvgBeginPath(vg);
        nvgRoundedRect(vg, x, y, width, height, cornerRadius);
        nvgFillPaint(vg, panelPaint);
        nvgFill(vg);

        // Elevation-based shadow
        RecipeUIStyleRenderer.drawAdvancedShadow(vg, x, y, width, height,
                                               elevation * 0.8f, elevation * 2.0f, cornerRadius,
                                               RecipeTheme.MEDIUM_SHADOW, stack);

        // Enhanced border with recipe book styling
        nvgBeginPath(vg);
        nvgRoundedRect(vg, x + 1, y + 1, width - 2, height - 2, cornerRadius - 1);
        nvgStrokeWidth(vg, 2.0f);
        nvgStrokeColor(vg, RecipeTheme.PANEL_BORDER.toNVG(NVGColor.malloc(stack)));
        nvgStroke(vg);

        // Inner highlight for depth
        nvgBeginPath(vg);
        nvgRoundedRect(vg, x + 3, y + 3, width - 6, height * 0.3f, cornerRadius - 3);
        nvgFillColor(vg, RecipeTheme.PANEL_BACKGROUND.lighter(0.15f).withAlpha(60).toNVG(NVGColor.malloc(stack)));
        nvgFill(vg);
    }

    /**
     * Draws book spine effect for authentic recipe book appearance
     */
    private static void drawBookSpineEffect(long vg, float x, float y, float width, float height,
                                          float cornerRadius, MemoryStack stack) {
        float spineWidth = 8.0f;

        // Left spine shadow
        NVGPaint spinePaint = NVGPaint.malloc(stack);
        nvgLinearGradient(vg, x, y, x + spineWidth * 2, y,
                         RecipeTheme.BOOK_SPINE.toNVG(NVGColor.malloc(stack)),
                         RecipeTheme.BOOK_SPINE.withAlpha(0).toNVG(NVGColor.malloc(stack)),
                         spinePaint);

        nvgBeginPath(vg);
        nvgRoundedRectVarying(vg, x, y, spineWidth * 2, height, cornerRadius, 0, 0, cornerRadius);
        nvgFillPaint(vg, spinePaint);
        nvgFill(vg);
    }

    /**
     * Draws subtle page texture effect for authentic paper feel
     */
    private static void drawPageTextureEffect(long vg, float x, float y, float width, float height,
                                            float cornerRadius, float animationProgress, MemoryStack stack) {
        // Subtle paper texture using scattered dots
        int texturePoints = 150;
        float textureAlpha = 15 + animationProgress * 10;

        for (int i = 0; i < texturePoints; i++) {
            float dotX = x + 20 + (float) Math.random() * (width - 40);
            float dotY = y + 20 + (float) Math.random() * (height - 40);
            float dotSize = 0.5f + (float) Math.random() * 1.0f;

            nvgBeginPath(vg);
            nvgCircle(vg, dotX, dotY, dotSize);
            nvgFillColor(vg, new ColorSpec(200, 180, 150, (int)textureAlpha).toNVG(NVGColor.malloc(stack)));
            nvgFill(vg);
        }
    }

    /**
     * Draws decorative corner elements for enhanced visual appeal
     */
    private static void drawDecorativeCorners(long vg, float x, float y, float width, float height,
                                            float cornerRadius, MemoryStack stack) {
        float decorSize = 12.0f;
        ColorSpec decorColor = RecipeTheme.BOOK_BINDING;

        // Top-left corner decoration
        drawCornerDecoration(vg, x + decorSize, y + decorSize, decorSize, decorColor, 0, stack);

        // Top-right corner decoration
        drawCornerDecoration(vg, x + width - decorSize, y + decorSize, decorSize, decorColor, 90, stack);

        // Bottom-right corner decoration
        drawCornerDecoration(vg, x + width - decorSize, y + height - decorSize, decorSize, decorColor, 180, stack);

        // Bottom-left corner decoration
        drawCornerDecoration(vg, x + decorSize, y + height - decorSize, decorSize, decorColor, 270, stack);
    }

    /**
     * Draws individual corner decoration element
     */
    private static void drawCornerDecoration(long vg, float centerX, float centerY, float size,
                                           ColorSpec color, float rotation, MemoryStack stack) {
        nvgSave(vg);
        nvgTranslate(vg, centerX, centerY);
        nvgRotate(vg, (float) Math.toRadians(rotation));

        // Draw decorative corner pattern
        nvgBeginPath(vg);
        nvgMoveTo(vg, -size/2, -size/2);
        nvgLineTo(vg, size/2, -size/4);
        nvgLineTo(vg, size/4, size/2);
        nvgLineTo(vg, -size/4, size/4);
        nvgClosePath(vg);
        nvgFillColor(vg, color.toNVG(NVGColor.malloc(stack)));
        nvgFill(vg);

        nvgRestore(vg);
    }

    /**
     * Draws animated sparkles for enhanced visual feedback
     */
    private static void drawRecipeSparkles(long vg, float x, float y, float width, float height,
                                         float animationTime, MemoryStack stack) {
        int sparkleCount = 8;

        for (int i = 0; i < sparkleCount; i++) {
            float sparkleX = x + 30 + (i * (width - 60) / sparkleCount);
            float sparkleY = y + 30 + (float) Math.sin(animationTime * 0.002f + i) * (height - 60) * 0.3f;
            float sparkleSize = 2.0f + (float) Math.sin(animationTime * 0.003f + i * 1.5f) * 1.5f;
            float sparkleAlpha = 100 + (float) Math.sin(animationTime * 0.004f + i * 2.0f) * 80;

            // Draw sparkle as a small star
            drawSparkle(vg, sparkleX, sparkleY, sparkleSize,
                       RecipeTheme.TITLE_GLOW.withAlpha((int)sparkleAlpha), stack);
        }
    }

    /**
     * Draws individual sparkle element
     */
    private static void drawSparkle(long vg, float x, float y, float size, ColorSpec color, MemoryStack stack) {
        nvgSave(vg);
        nvgTranslate(vg, x, y);

        // Draw 4-pointed star
        nvgBeginPath(vg);
        nvgMoveTo(vg, 0, -size);
        nvgLineTo(vg, size * 0.3f, -size * 0.3f);
        nvgLineTo(vg, size, 0);
        nvgLineTo(vg, size * 0.3f, size * 0.3f);
        nvgLineTo(vg, 0, size);
        nvgLineTo(vg, -size * 0.3f, size * 0.3f);
        nvgLineTo(vg, -size, 0);
        nvgLineTo(vg, -size * 0.3f, -size * 0.3f);
        nvgClosePath(vg);
        nvgFillColor(vg, color.toNVG(NVGColor.malloc(stack)));
        nvgFill(vg);

        nvgRestore(vg);
    }

    // ===========================================
    // ADVANCED TITLE RENDERING
    // ===========================================

    /**
     * Draws sophisticated recipe title with enhanced typography
     */
    public static void drawRecipeTitle(UIRenderer uiRenderer, float centerX, float centerY, String title) {
        drawRecipeTitle(uiRenderer, centerX, centerY, title, UIState.NORMAL, 0.0f);
    }

    /**
     * Draws advanced recipe title with state management and effects
     */
    public static void drawRecipeTitle(UIRenderer uiRenderer, float centerX, float centerY, String title,
                                     UIState state, float animationProgress) {
        try (MemoryStack stack = stackPush()) {
            long vg = uiRenderer.getVG();

            // Enhanced typography settings
            float baseFontSize = 28.0f;
            float stateMultiplier = switch (state) {
                case HOVER -> 1.1f;
                case PRESSED -> 0.95f;
                case FOCUSED, SELECTED -> 1.15f;
                default -> 1.0f;
            };

            float fontSize = baseFontSize * (stateMultiplier + animationProgress * 0.05f);

            // Draw title shadow for depth
            drawTitleShadow(vg, centerX, centerY, title, fontSize, stack);

            // Draw title glow effect for state feedback
            if (state == UIState.FOCUSED || state == UIState.SELECTED) {
                drawTitleGlow(vg, centerX, centerY, title, fontSize, animationProgress, stack);
            }

            // Draw main title text with enhanced styling
            drawMainTitleText(vg, centerX, centerY, title, fontSize, state, stack);

            // Add subtle text highlight
            drawTitleHighlight(vg, centerX, centerY, title, fontSize, stack);
        }
    }

    /**
     * Draws sophisticated title shadow
     */
    private static void drawTitleShadow(long vg, float centerX, float centerY, String title,
                                      float fontSize, MemoryStack stack) {
        RecipeUIStyleRenderer.RecipeFonts.setTitleFont(vg, fontSize);
        nvgTextAlign(vg, NVG_ALIGN_CENTER | NVG_ALIGN_MIDDLE);

        // Multiple shadow layers for depth
        float[] shadowOffsets = {3.0f, 2.0f, 1.0f};
        int[] shadowAlphas = {100, 130, 160};

        for (int i = 0; i < shadowOffsets.length; i++) {
            nvgFillColor(vg, RecipeTheme.TITLE_SHADOW.withAlpha(shadowAlphas[i]).toNVG(NVGColor.malloc(stack)));
            nvgText(vg, centerX + shadowOffsets[i], centerY + shadowOffsets[i], title);
        }
    }

    /**
     * Draws title glow effect for interactive states
     */
    private static void drawTitleGlow(long vg, float centerX, float centerY, String title,
                                    float fontSize, float animationProgress, MemoryStack stack) {
        RecipeUIStyleRenderer.RecipeFonts.setTitleFont(vg, fontSize + 2.0f);
        nvgTextAlign(vg, NVG_ALIGN_CENTER | NVG_ALIGN_MIDDLE);

        float glowIntensity = 0.3f + animationProgress * 0.4f;
        int glowAlpha = (int)(RecipeTheme.TITLE_GLOW.a * glowIntensity);

        nvgFillColor(vg, RecipeTheme.TITLE_GLOW.withAlpha(glowAlpha).toNVG(NVGColor.malloc(stack)));
        nvgText(vg, centerX, centerY, title);
    }

    /**
     * Draws the main title text with sophisticated styling
     */
    private static void drawMainTitleText(long vg, float centerX, float centerY, String title,
                                        float fontSize, UIState state, MemoryStack stack) {
        RecipeUIStyleRenderer.RecipeFonts.setTitleFont(vg, fontSize);
        nvgTextAlign(vg, NVG_ALIGN_CENTER | NVG_ALIGN_MIDDLE);

        // State-based color selection
        ColorSpec titleColor = switch (state) {
            case HOVER -> RecipeTheme.TITLE_PRIMARY.lighter(0.1f);
            case PRESSED -> RecipeTheme.TITLE_PRIMARY.darker(0.1f);
            case FOCUSED, SELECTED -> RecipeTheme.TITLE_PRIMARY.lighter(0.2f);
            default -> RecipeTheme.TITLE_PRIMARY;
        };

        nvgFillColor(vg, titleColor.toNVG(NVGColor.malloc(stack)));
        nvgText(vg, centerX, centerY, title);
    }

    /**
     * Draws subtle title highlight for enhanced depth
     */
    private static void drawTitleHighlight(long vg, float centerX, float centerY, String title,
                                         float fontSize, MemoryStack stack) {
        RecipeUIStyleRenderer.RecipeFonts.setTitleFont(vg, fontSize);
        nvgTextAlign(vg, NVG_ALIGN_CENTER | NVG_ALIGN_MIDDLE);

        ColorSpec highlightColor = new ColorSpec(255, 255, 255, 40);
        nvgFillColor(vg, highlightColor.toNVG(NVGColor.malloc(stack)));
        nvgText(vg, centerX, centerY - 1, title);
    }

    // ===========================================
    // RECIPE-SPECIFIC ENHANCEMENT METHODS
    // ===========================================

    /**
     * Draws recipe book page curl effect for enhanced realism
     */
    public static void drawPageCurlEffect(UIRenderer uiRenderer, float x, float y, float width, float height,
                                        float curlIntensity) {
        try (MemoryStack stack = stackPush()) {
            long vg = uiRenderer.getVG();

            float curlSize = 20.0f * curlIntensity;

            // Draw page curl shadow
            NVGPaint curlPaint = NVGPaint.malloc(stack);
            nvgLinearGradient(vg, x + width - curlSize, y + height - curlSize, x + width, y + height,
                             RecipeTheme.LIGHT_SHADOW.toNVG(NVGColor.malloc(stack)),
                             RecipeTheme.LIGHT_SHADOW.withAlpha(0).toNVG(NVGColor.malloc(stack)),
                             curlPaint);

            nvgBeginPath(vg);
            nvgMoveTo(vg, x + width - curlSize, y + height);
            nvgLineTo(vg, x + width, y + height - curlSize);
            nvgLineTo(vg, x + width, y + height);
            nvgClosePath(vg);
            nvgFillPaint(vg, curlPaint);
            nvgFill(vg);
        }
    }


    // ===========================================
    // PERFORMANCE AND UTILITY METHODS
    // ===========================================

    /**
     * Updates animation states for smooth visual transitions
     */
    public static void updateAnimations(float deltaTime) {
        if (isAnimating) {
            panelAnimationTime += deltaTime;
        }
    }

    /**
     * Enables or disables panel animations
     */
    public static void setAnimationEnabled(boolean enabled) {
        isAnimating = enabled;
        if (!enabled) {
            panelAnimationTime = 0.0f;
        }
    }

    /**
     * Performs cleanup of cached resources
     */
    public static void performCleanup() {
        RecipeUIStyleRenderer.performCacheCleanup(60000); // Clean resources older than 1 minute
    }
}