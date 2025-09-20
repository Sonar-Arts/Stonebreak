package com.stonebreak.ui.recipeScreen.renderers;

import com.stonebreak.rendering.UI.UIRenderer;
import org.lwjgl.nanovg.NVGColor;
import org.lwjgl.nanovg.NVGPaint;
import org.lwjgl.system.MemoryStack;

import java.util.HashMap;
import java.util.Map;

import static org.lwjgl.nanovg.NanoVG.*;
import static org.lwjgl.system.MemoryStack.stackPush;

/**
 * UI Style Renderer for Recipe UI Components
 */
public class RecipeUIStyleRenderer {

    // ===========================================
    // COLOR PALETTE SYSTEM
    // ===========================================

    public static class MaterialPalette {
        // Primary Material Colors
        public static final ColorSpec SURFACE_PRIMARY = new ColorSpec(78, 78, 78, 255);
        public static final ColorSpec SURFACE_SECONDARY = new ColorSpec(65, 65, 65, 255);
        public static final ColorSpec SURFACE_TERTIARY = new ColorSpec(55, 55, 55, 255);
        public static final ColorSpec SURFACE_QUATERNARY = new ColorSpec(45, 45, 45, 255);

        // Elevation Colors (Material Design inspired)
        public static final ColorSpec ELEVATION_0 = new ColorSpec(50, 50, 50, 255);   // Ground level
        public static final ColorSpec ELEVATION_1 = new ColorSpec(55, 55, 55, 255);   // Subtle raise
        public static final ColorSpec ELEVATION_2 = new ColorSpec(62, 62, 62, 255);   // Card level
        public static final ColorSpec ELEVATION_4 = new ColorSpec(70, 70, 70, 255);   // App bar
        public static final ColorSpec ELEVATION_8 = new ColorSpec(78, 78, 78, 255);   // Menu/dialog
        public static final ColorSpec ELEVATION_16 = new ColorSpec(88, 88, 88, 255);  // Modal

        // Accent Colors
        public static final ColorSpec ACCENT_GOLD = new ColorSpec(255, 215, 0, 255);
        public static final ColorSpec ACCENT_BLUE = new ColorSpec(100, 149, 237, 255);
        public static final ColorSpec ACCENT_GREEN = new ColorSpec(152, 251, 152, 255);
        public static final ColorSpec ACCENT_RED = new ColorSpec(255, 99, 71, 255);
        public static final ColorSpec ACCENT_PURPLE = new ColorSpec(147, 112, 219, 255);

        // Highlight Colors
        public static final ColorSpec HIGHLIGHT_SUBTLE = new ColorSpec(255, 255, 255, 15);
        public static final ColorSpec HIGHLIGHT_MODERATE = new ColorSpec(255, 255, 255, 30);
        public static final ColorSpec HIGHLIGHT_STRONG = new ColorSpec(255, 255, 255, 60);
        public static final ColorSpec HIGHLIGHT_INTENSE = new ColorSpec(255, 255, 255, 90);

        // Shadow Colors
        public static final ColorSpec SHADOW_SOFT = new ColorSpec(0, 0, 0, 20);
        public static final ColorSpec SHADOW_MEDIUM = new ColorSpec(0, 0, 0, 40);
        public static final ColorSpec SHADOW_HARD = new ColorSpec(0, 0, 0, 60);
        public static final ColorSpec SHADOW_DEEP = new ColorSpec(0, 0, 0, 80);

        // Border Colors
        public static final ColorSpec BORDER_LIGHT = new ColorSpec(198, 132, 66, 255);
        public static final ColorSpec BORDER_MEDIUM = new ColorSpec(165, 110, 55, 255);
        public static final ColorSpec BORDER_DARK = new ColorSpec(66, 44, 22, 255);
        public static final ColorSpec BORDER_DARKER = new ColorSpec(45, 30, 15, 255);

        // Interactive State Colors
        public static final ColorSpec STATE_NORMAL = SURFACE_SECONDARY;
        public static final ColorSpec STATE_HOVER = new ColorSpec(85, 85, 85, 255);
        public static final ColorSpec STATE_PRESSED = new ColorSpec(45, 45, 45, 255);
        public static final ColorSpec STATE_DISABLED = new ColorSpec(40, 40, 40, 128);
        public static final ColorSpec STATE_FOCUSED = new ColorSpec(100, 149, 237, 80);
        public static final ColorSpec STATE_SELECTED = new ColorSpec(255, 215, 0, 120);
    }

    // ===========================================
    // ANIMATION AND TRANSITION SYSTEM
    // ===========================================

    public static class AnimationState {
        private long startTime;
        private long duration;
        private float startValue;
        private float endValue;
        private boolean active;
        private EasingFunction easing;

        public AnimationState(float startValue, float endValue, long duration, EasingFunction easing) {
            this.startValue = startValue;
            this.endValue = endValue;
            this.duration = duration;
            this.easing = easing;
            this.startTime = System.currentTimeMillis();
            this.active = true;
        }

        public float getCurrentValue() {
            if (!active) return endValue;

            long elapsed = System.currentTimeMillis() - startTime;
            if (elapsed >= duration) {
                active = false;
                return endValue;
            }

            float progress = (float) elapsed / duration;
            float easedProgress = easing.apply(progress);
            return startValue + (endValue - startValue) * easedProgress;
        }

        public boolean isActive() { return active; }
    }

    @FunctionalInterface
    public interface EasingFunction {
        float apply(float t);

        EasingFunction LINEAR = t -> t;
        EasingFunction EASE_IN_OUT = t -> t < 0.5f ? 2 * t * t : -1 + (4 - 2 * t) * t;
        EasingFunction EASE_OUT_BACK = t -> 1 + (--t) * t * (2.7f * t + 1.7f);
        EasingFunction BOUNCE_OUT = t -> {
            if (t < 1/2.75f) return 7.5625f * t * t;
            else if (t < 2/2.75f) return 7.5625f * (t -= 1.5f/2.75f) * t + 0.75f;
            else if (t < 2.5f/2.75f) return 7.5625f * (t -= 2.25f/2.75f) * t + 0.9375f;
            else return 7.5625f * (t -= 2.625f/2.75f) * t + 0.984375f;
        };
    }

    // Animation state cache for performance
    private static final Map<String, AnimationState> animationCache = new HashMap<>();

    // ===========================================
    // INTERACTIVE STATE SYSTEM
    // ===========================================

    public enum UIState {
        NORMAL, HOVER, PRESSED, DISABLED, FOCUSED, SELECTED
    }

    public static class StateManager {
        private UIState currentState = UIState.NORMAL;
        private float stateTransition = 0.0f;
        private AnimationState transitionAnimation;

        public void setState(UIState newState) {
            if (newState != currentState) {
                currentState = newState;
                transitionAnimation = new AnimationState(stateTransition, 1.0f, 200, EasingFunction.EASE_IN_OUT);
            }
        }

        public UIState getState() { return currentState; }

        public float getTransitionProgress() {
            if (transitionAnimation != null) {
                stateTransition = transitionAnimation.getCurrentValue();
            }
            return stateTransition;
        }

        public ColorSpec getStateColor() {
            float progress = getTransitionProgress();
            ColorSpec baseColor = MaterialPalette.STATE_NORMAL;
            ColorSpec targetColor = switch (currentState) {
                case HOVER -> MaterialPalette.STATE_HOVER;
                case PRESSED -> MaterialPalette.STATE_PRESSED;
                case DISABLED -> MaterialPalette.STATE_DISABLED;
                case FOCUSED -> MaterialPalette.STATE_FOCUSED;
                case SELECTED -> MaterialPalette.STATE_SELECTED;
                default -> MaterialPalette.STATE_NORMAL;
            };
            return ColorSpec.lerp(baseColor, targetColor, progress);
        }
    }

    // ===========================================
    // PERFORMANCE OPTIMIZATION SYSTEM
    // ===========================================

    private static final Map<String, CachedPath> pathCache = new HashMap<>();
    private static final Map<String, CachedGradient> gradientCache = new HashMap<>();

    private static class CachedPath {
        long lastUsed;
        int hash;
        // In a real implementation, we'd cache the actual path data

        CachedPath(int hash) {
            this.hash = hash;
            this.lastUsed = System.currentTimeMillis();
        }
    }

    private static class CachedGradient {
        long lastUsed;
        int hash;

        CachedGradient(int hash) {
            this.hash = hash;
            this.lastUsed = System.currentTimeMillis();
        }
    }

    // ===========================================
    // COLOR SPECIFICATION SYSTEM
    // ===========================================

    public static class ColorSpec {
        public final int r, g, b, a;

        public ColorSpec(int r, int g, int b, int a) {
            this.r = Math.max(0, Math.min(255, r));
            this.g = Math.max(0, Math.min(255, g));
            this.b = Math.max(0, Math.min(255, b));
            this.a = Math.max(0, Math.min(255, a));
        }

        public ColorSpec withAlpha(int alpha) {
            return new ColorSpec(r, g, b, alpha);
        }

        public ColorSpec lighter(float factor) {
            return new ColorSpec(
                (int) Math.min(255, r + (255 - r) * factor),
                (int) Math.min(255, g + (255 - g) * factor),
                (int) Math.min(255, b + (255 - b) * factor),
                a
            );
        }

        public ColorSpec darker(float factor) {
            return new ColorSpec(
                (int) (r * (1 - factor)),
                (int) (g * (1 - factor)),
                (int) (b * (1 - factor)),
                a
            );
        }

        public static ColorSpec lerp(ColorSpec start, ColorSpec end, float t) {
            t = Math.max(0, Math.min(1, t));
            return new ColorSpec(
                (int) (start.r + (end.r - start.r) * t),
                (int) (start.g + (end.g - start.g) * t),
                (int) (start.b + (end.b - start.b) * t),
                (int) (start.a + (end.a - start.a) * t)
            );
        }

        public NVGColor toNVG(NVGColor color) {
            color.r(r / 255.0f);
            color.g(g / 255.0f);
            color.b(b / 255.0f);
            color.a(a / 255.0f);
            return color;
        }
    }

    private RecipeUIStyleRenderer() {
        // Utility class - prevent instantiation
    }

    // ===========================================
    // ADVANCED VISUAL EFFECTS
    // ===========================================

    /**
     * Renders a sophisticated material surface with elevation-based lighting
     */
    public static void drawMaterialSurface(long vg, float x, float y, float width, float height,
                                         int elevation, float cornerRadius, MemoryStack stack) {
        // Calculate shadow properties based on elevation
        float shadowOffset = elevation * 0.5f;
        float shadowBlur = elevation * 1.5f;
        ColorSpec shadowColor = elevation > 4 ? MaterialPalette.SHADOW_HARD : MaterialPalette.SHADOW_MEDIUM;

        // Draw shadow
        drawAdvancedShadow(vg, x, y, width, height, shadowOffset, shadowBlur, cornerRadius, shadowColor, stack);

        // Draw surface with gradient
        ColorSpec surfaceColor = switch (elevation) {
            case 0 -> MaterialPalette.ELEVATION_0;
            case 1 -> MaterialPalette.ELEVATION_1;
            case 2 -> MaterialPalette.ELEVATION_2;
            case 4 -> MaterialPalette.ELEVATION_4;
            case 8 -> MaterialPalette.ELEVATION_8;
            case 16 -> MaterialPalette.ELEVATION_16;
            default -> MaterialPalette.ELEVATION_2;
        };

        drawAdvancedRoundedRect(vg, x, y, width, height, cornerRadius, surfaceColor, stack);

        // Add subtle highlight for depth
        drawAdvancedHighlight(vg, x, y, width, height * 0.4f, cornerRadius,
                            MaterialPalette.HIGHLIGHT_SUBTLE, stack);
    }

    /**
     * Draws advanced shadow with blur and offset
     */
    public static void drawAdvancedShadow(long vg, float x, float y, float width, float height,
                                        float offset, float blur, float cornerRadius,
                                        ColorSpec shadowColor, MemoryStack stack) {
        NVGPaint shadowPaint = NVGPaint.malloc(stack);
        nvgBoxGradient(vg, x + offset, y + offset, width, height, cornerRadius, blur,
                      shadowColor.toNVG(NVGColor.malloc(stack)),
                      shadowColor.withAlpha(0).toNVG(NVGColor.malloc(stack)), shadowPaint);

        nvgBeginPath(vg);
        nvgRoundedRect(vg, x + offset - blur, y + offset - blur,
                      width + 2 * blur, height + 2 * blur, cornerRadius + blur);
        nvgFillPaint(vg, shadowPaint);
        nvgFill(vg);
    }

    /**
     * Draws rounded rectangle with sophisticated styling
     */
    public static void drawAdvancedRoundedRect(long vg, float x, float y, float width, float height,
                                             float cornerRadius, ColorSpec color, MemoryStack stack) {
        // Main surface
        nvgBeginPath(vg);
        nvgRoundedRect(vg, x, y, width, height, cornerRadius);
        nvgFillColor(vg, color.toNVG(NVGColor.malloc(stack)));
        nvgFill(vg);

        // Subtle inner border for definition
        nvgBeginPath(vg);
        nvgRoundedRect(vg, x + 0.5f, y + 0.5f, width - 1, height - 1, cornerRadius - 0.5f);
        nvgStrokeWidth(vg, 1.0f);
        nvgStrokeColor(vg, color.lighter(0.1f).toNVG(NVGColor.malloc(stack)));
        nvgStroke(vg);
    }

    /**
     * Draws sophisticated highlight gradient
     */
    public static void drawAdvancedHighlight(long vg, float x, float y, float width, float height,
                                           float cornerRadius, ColorSpec highlightColor, MemoryStack stack) {
        NVGPaint highlightPaint = NVGPaint.malloc(stack);
        nvgLinearGradient(vg, x, y, x, y + height,
                         highlightColor.toNVG(NVGColor.malloc(stack)),
                         highlightColor.withAlpha(0).toNVG(NVGColor.malloc(stack)), highlightPaint);

        nvgBeginPath(vg);
        nvgRoundedRectVarying(vg, x, y, width, height, cornerRadius, cornerRadius, 0, 0);
        nvgFillPaint(vg, highlightPaint);
        nvgFill(vg);
    }

    /**
     * Enhanced beveled border with modern materials and smooth transitions
     */
    public static void drawAdvancedBeveledBorder(long vg, float x, float y, float width, float height,
                                                MemoryStack stack, boolean raised, float intensity,
                                                float cornerRadius) {
        float borderWidth = 2.0f * intensity;

        ColorSpec lightColor = raised ? MaterialPalette.BORDER_LIGHT : MaterialPalette.BORDER_DARK;
        ColorSpec darkColor = raised ? MaterialPalette.BORDER_DARK : MaterialPalette.BORDER_LIGHT;

        // Top-left light edge
        nvgBeginPath(vg);
        nvgMoveTo(vg, x, y + height - cornerRadius);
        nvgLineTo(vg, x, y + cornerRadius);
        nvgQuadTo(vg, x, y, x + cornerRadius, y);
        nvgLineTo(vg, x + width - cornerRadius, y);
        nvgStrokeWidth(vg, borderWidth);
        nvgStrokeColor(vg, lightColor.toNVG(NVGColor.malloc(stack)));
        nvgStroke(vg);

        // Bottom-right dark edge
        nvgBeginPath(vg);
        nvgMoveTo(vg, x + width - cornerRadius, y);
        nvgQuadTo(vg, x + width, y, x + width, y + cornerRadius);
        nvgLineTo(vg, x + width, y + height - cornerRadius);
        nvgQuadTo(vg, x + width, y + height, x + width - cornerRadius, y + height);
        nvgLineTo(vg, x + cornerRadius, y + height);
        nvgStrokeWidth(vg, borderWidth);
        nvgStrokeColor(vg, darkColor.toNVG(NVGColor.malloc(stack)));
        nvgStroke(vg);
    }

    /**
     * Draws premium inventory slot with sophisticated materials and animations
     */
    public static void drawPremiumInventorySlot(UIRenderer uiRenderer, float slotX, float slotY,
                                              float slotSize, UIState state, float animationProgress) {
        try (MemoryStack stack = stackPush()) {
            long vg = uiRenderer.getVG();
            float cornerRadius = slotSize * 0.1f;

            // Determine colors based on state
            StateManager stateManager = new StateManager();
            stateManager.setState(state);
            ColorSpec baseColor = stateManager.getStateColor();

            // Draw material surface with elevation
            int elevation = switch (state) {
                case HOVER -> 2;
                case PRESSED -> 0;
                case FOCUSED, SELECTED -> 4;
                default -> 1;
            };

            drawMaterialSurface(vg, slotX, slotY, slotSize, slotSize, elevation, cornerRadius, stack);

            // Add state-specific effects
            if (state == UIState.FOCUSED || state == UIState.SELECTED) {
                drawGlowEffect(vg, slotX, slotY, slotSize, slotSize, cornerRadius,
                             state == UIState.FOCUSED ? MaterialPalette.STATE_FOCUSED : MaterialPalette.STATE_SELECTED,
                             animationProgress, stack);
            }

            // Inner slot area
            float padding = slotSize * 0.08f;
            drawAdvancedRoundedRect(vg, slotX + padding, slotY + padding,
                                  slotSize - 2 * padding, slotSize - 2 * padding,
                                  cornerRadius * 0.7f, baseColor.darker(0.1f), stack);

            // Enhanced beveled border
            drawAdvancedBeveledBorder(vg, slotX, slotY, slotSize, slotSize, stack, false,
                                    1.0f + animationProgress * 0.3f, cornerRadius);
        }
    }

    /**
     * Draws sophisticated glow effect with pulsing animation
     */
    public static void drawGlowEffect(long vg, float x, float y, float width, float height,
                                    float cornerRadius, ColorSpec glowColor, float intensity,
                                    MemoryStack stack) {
        float glowSize = 8.0f * intensity;

        NVGPaint glowPaint = NVGPaint.malloc(stack);
        nvgBoxGradient(vg, x - glowSize, y - glowSize, width + 2 * glowSize, height + 2 * glowSize,
                      cornerRadius + glowSize, glowSize,
                      glowColor.withAlpha((int)(glowColor.a * intensity)).toNVG(NVGColor.malloc(stack)),
                      glowColor.withAlpha(0).toNVG(NVGColor.malloc(stack)), glowPaint);

        nvgBeginPath(vg);
        nvgRoundedRect(vg, x - glowSize, y - glowSize,
                      width + 2 * glowSize, height + 2 * glowSize, cornerRadius + glowSize);
        nvgFillPaint(vg, glowPaint);
        nvgFill(vg);
    }

    /**
     * Draws sophisticated animated dot pattern for empty slots
     */
    public static void drawAnimatedEmptySlotPattern(long vg, float slotX, float slotY, float slotSize,
                                                  float animationTime, MemoryStack stack) {
        float centerX = slotX + slotSize / 2;
        float centerY = slotY + slotSize / 2;
        float dotSize = slotSize * 0.03f;
        float spacing = slotSize * 0.12f;

        // Animated wave effect
        for (int dy = -1; dy <= 1; dy++) {
            for (int dx = -1; dx <= 1; dx++) {
                if (dx == 0 && dy == 0) continue; // Skip center

                float dotX = centerX + dx * spacing;
                float dotY = centerY + dy * spacing;

                // Wave animation based on distance from center
                float distance = (float) Math.sqrt(dx * dx + dy * dy);
                float wave = (float) Math.sin(animationTime * 0.003f + distance * 1.5f) * 0.5f + 0.5f;
                float alpha = 40 + (int)(wave * 60);

                nvgBeginPath(vg);
                nvgCircle(vg, dotX, dotY, dotSize * (0.7f + wave * 0.3f));
                nvgFillColor(vg, new ColorSpec(160, 160, 160, (int)alpha).toNVG(NVGColor.malloc(stack)));
                nvgFill(vg);
            }
        }
    }

    /**
     * Draws sophisticated pulsing highlight for selected items
     */
    public static void drawPulsingHighlight(long vg, float x, float y, float width, float height,
                                          float cornerRadius, float pulseTime, MemoryStack stack) {
        float pulse = (float) (Math.sin(pulseTime * 0.004f) * 0.5f + 0.5f);
        float glowIntensity = 0.3f + pulse * 0.4f;

        ColorSpec highlightColor = MaterialPalette.ACCENT_GOLD.withAlpha((int)(120 * glowIntensity));
        drawGlowEffect(vg, x, y, width, height, cornerRadius, highlightColor, glowIntensity, stack);
    }

    /**
     * Legacy compatibility methods (enhanced versions of original methods)
     */

    public static void drawBeveledBorder(long vg, int x, int y, int width, int height, MemoryStack stack, boolean raised) {
        drawAdvancedBeveledBorder(vg, x, y, width, height, stack, raised, 1.0f, 4.0f);
    }

    public static void drawInventorySlot(UIRenderer uiRenderer, int slotX, int slotY, int slotSize) {
        drawPremiumInventorySlot(uiRenderer, slotX, slotY, slotSize, UIState.NORMAL, 0.0f);
    }

    public static void drawEmptySlotPattern(long vg, int slotX, int slotY, int slotSize, MemoryStack stack) {
        drawAnimatedEmptySlotPattern(vg, slotX, slotY, slotSize, System.currentTimeMillis(), stack);
    }

    public static NVGColor nvgRGBA(int r, int g, int b, int a, NVGColor color) {
        return new ColorSpec(r, g, b, a).toNVG(color);
    }

    // ===========================================
    // PERFORMANCE UTILITIES
    // ===========================================

    /**
     * Cleans up cached resources older than specified time
     */
    public static void performCacheCleanup(long maxAge) {
        long currentTime = System.currentTimeMillis();
        pathCache.entrySet().removeIf(entry -> currentTime - entry.getValue().lastUsed > maxAge);
        gradientCache.entrySet().removeIf(entry -> currentTime - entry.getValue().lastUsed > maxAge);
        animationCache.entrySet().removeIf(entry -> !entry.getValue().isActive());
    }

    /**
     * Gets or creates an animation state for smooth transitions
     */
    public static AnimationState getOrCreateAnimation(String key, float startValue, float endValue,
                                                    long duration, EasingFunction easing) {
        return animationCache.computeIfAbsent(key, k -> new AnimationState(startValue, endValue, duration, easing));
    }
}