package com.stonebreak.rendering.UI.components;

import com.stonebreak.rendering.UI.core.BaseRenderer;
import org.lwjgl.nanovg.NVGColor;

import static org.lwjgl.nanovg.NanoVG.*;
import static org.lwjgl.system.MemoryStack.stackPush;
import org.lwjgl.system.MemoryStack;

/**
 * Modern crosshair renderer using NanoVG for smooth, anti-aliased rendering.
 * Supports different crosshair styles, colors, and customization options.
 */
public class CrosshairRenderer extends BaseRenderer {
    
    // Crosshair styles
    public enum CrosshairStyle {
        SIMPLE_CROSS,    // Traditional + shape
        DOT,            // Single center dot
        CIRCLE,         // Hollow circle
        SQUARE,         // Hollow square
        T_SHAPE,        // T-shaped crosshair
        PLUS_DOT        // + with center dot
    }
    
    // Configuration
    private CrosshairStyle style = CrosshairStyle.SIMPLE_CROSS;
    private float size = 16.0f;
    private float thickness = 2.0f;
    private float gap = 4.0f;
    private float opacity = 1.0f;
    private float[] color = {1.0f, 1.0f, 1.0f}; // RGB white
    private boolean outline = true;
    private float outlineThickness = 1.0f;
    private float[] outlineColor = {0.0f, 0.0f, 0.0f}; // RGB black
    
    public CrosshairRenderer(long vg) {
        super(vg);
    }
    
    /**
     * Renders the crosshair at the center of the screen.
     * @param windowWidth Current window width
     * @param windowHeight Current window height
     */
    public void renderCrosshair(int windowWidth, int windowHeight) {
        if (vg == 0) return;
        
        float centerX = windowWidth / 2.0f;
        float centerY = windowHeight / 2.0f;
        
        try (MemoryStack stack = stackPush()) {
            NVGColor fillColor = NVGColor.malloc(stack);
            NVGColor strokeColor = NVGColor.malloc(stack);
            
            // Set colors
            nvgRGBA((int)(color[0] * 255), (int)(color[1] * 255), (int)(color[2] * 255), (int)(opacity * 255), fillColor);
            nvgRGBA((int)(outlineColor[0] * 255), (int)(outlineColor[1] * 255), (int)(outlineColor[2] * 255), (int)(opacity * 255), strokeColor);
            
            switch (style) {
                case SIMPLE_CROSS -> renderSimpleCross(centerX, centerY, fillColor, strokeColor);
                case DOT -> renderDot(centerX, centerY, fillColor, strokeColor);
                case CIRCLE -> renderCircle(centerX, centerY, fillColor, strokeColor);
                case SQUARE -> renderSquare(centerX, centerY, fillColor, strokeColor);
                case T_SHAPE -> renderTShape(centerX, centerY, fillColor, strokeColor);
                case PLUS_DOT -> renderPlusDot(centerX, centerY, fillColor, strokeColor);
            }
        }
    }
    
    /**
     * Renders a traditional + shaped crosshair with perfect symmetry.
     */
    private void renderSimpleCross(float centerX, float centerY, NVGColor fillColor, NVGColor strokeColor) {
        // Calculate dimensions for perfect symmetry
        float halfSize = size / 2.0f;
        float halfGap = gap / 2.0f;
        float halfThickness = thickness / 2.0f;
        float armLength = halfSize - halfGap;
        
        nvgBeginPath(vg);
        
        // Left horizontal arm
        nvgRect(vg, centerX - halfSize, centerY - halfThickness, armLength, thickness);
        
        // Right horizontal arm  
        nvgRect(vg, centerX + halfGap, centerY - halfThickness, armLength, thickness);
        
        // Top vertical arm
        nvgRect(vg, centerX - halfThickness, centerY - halfSize, thickness, armLength);
        
        // Bottom vertical arm
        nvgRect(vg, centerX - halfThickness, centerY + halfGap, thickness, armLength);
        
        nvgFillColor(vg, fillColor);
        nvgFill(vg);
        
        if (outline) {
            nvgStrokeColor(vg, strokeColor);
            nvgStrokeWidth(vg, outlineThickness);
            nvgStroke(vg);
        }
    }
    
    /**
     * Renders a single center dot.
     */
    private void renderDot(float centerX, float centerY, NVGColor fillColor, NVGColor strokeColor) {
        nvgBeginPath(vg);
        nvgCircle(vg, centerX, centerY, size / 4.0f);
        nvgFillColor(vg, fillColor);
        nvgFill(vg);
        
        if (outline) {
            nvgStrokeColor(vg, strokeColor);
            nvgStrokeWidth(vg, outlineThickness);
            nvgStroke(vg);
        }
    }
    
    /**
     * Renders a hollow circle crosshair.
     */
    private void renderCircle(float centerX, float centerY, NVGColor fillColor, NVGColor strokeColor) {
        nvgBeginPath(vg);
        nvgCircle(vg, centerX, centerY, size / 2.0f);
        nvgStrokeColor(vg, fillColor);
        nvgStrokeWidth(vg, thickness);
        nvgStroke(vg);
        
        if (outline) {
            nvgBeginPath(vg);
            nvgCircle(vg, centerX, centerY, size / 2.0f + outlineThickness);
            nvgCircle(vg, centerX, centerY, size / 2.0f - outlineThickness);
            nvgPathWinding(vg, NVG_HOLE);
            nvgFillColor(vg, strokeColor);
            nvgFill(vg);
        }
    }
    
    /**
     * Renders a hollow square crosshair.
     */
    private void renderSquare(float centerX, float centerY, NVGColor fillColor, NVGColor strokeColor) {
        float halfSize = size / 2.0f;
        nvgBeginPath(vg);
        nvgRect(vg, centerX - halfSize, centerY - halfSize, size, size);
        nvgStrokeColor(vg, fillColor);
        nvgStrokeWidth(vg, thickness);
        nvgStroke(vg);
        
        if (outline) {
            nvgBeginPath(vg);
            nvgRect(vg, centerX - halfSize - outlineThickness, centerY - halfSize - outlineThickness, 
                   size + 2*outlineThickness, size + 2*outlineThickness);
            nvgRect(vg, centerX - halfSize + outlineThickness, centerY - halfSize + outlineThickness, 
                   size - 2*outlineThickness, size - 2*outlineThickness);
            nvgPathWinding(vg, NVG_HOLE);
            nvgFillColor(vg, strokeColor);
            nvgFill(vg);
        }
    }
    
    /**
     * Renders a T-shaped crosshair with perfect symmetry.
     */
    private void renderTShape(float centerX, float centerY, NVGColor fillColor, NVGColor strokeColor) {
        // Calculate dimensions for perfect symmetry
        float halfSize = size / 2.0f;
        float halfGap = gap / 2.0f;
        float halfThickness = thickness / 2.0f;
        
        nvgBeginPath(vg);
        
        // Horizontal line (top of T) - centered above the gap
        nvgRect(vg, centerX - halfSize, centerY - halfGap - thickness, size, thickness);
        
        // Vertical line (stem of T) - centered below the gap
        nvgRect(vg, centerX - halfThickness, centerY + halfGap, thickness, halfSize - halfGap);
        
        nvgFillColor(vg, fillColor);
        nvgFill(vg);
        
        if (outline) {
            nvgStrokeColor(vg, strokeColor);
            nvgStrokeWidth(vg, outlineThickness);
            nvgStroke(vg);
        }
    }
    
    /**
     * Renders a + with a center dot.
     */
    private void renderPlusDot(float centerX, float centerY, NVGColor fillColor, NVGColor strokeColor) {
        // First render the cross
        renderSimpleCross(centerX, centerY, fillColor, strokeColor);
        
        // Then add the center dot
        nvgBeginPath(vg);
        nvgCircle(vg, centerX, centerY, gap / 3.0f);
        nvgFillColor(vg, fillColor);
        nvgFill(vg);
        
        if (outline) {
            nvgStrokeColor(vg, strokeColor);
            nvgStrokeWidth(vg, outlineThickness);
            nvgStroke(vg);
        }
    }
    
    // Configuration setters
    public void setStyle(CrosshairStyle style) {
        this.style = style;
    }
    
    public void setSize(float size) {
        this.size = Math.max(4.0f, size);
    }
    
    public void setThickness(float thickness) {
        this.thickness = Math.max(1.0f, thickness);
    }
    
    public void setGap(float gap) {
        this.gap = Math.max(0.0f, gap);
    }
    
    public void setOpacity(float opacity) {
        this.opacity = Math.max(0.0f, Math.min(1.0f, opacity));
    }
    
    public void setColor(float r, float g, float b) {
        this.color[0] = Math.max(0.0f, Math.min(1.0f, r));
        this.color[1] = Math.max(0.0f, Math.min(1.0f, g));
        this.color[2] = Math.max(0.0f, Math.min(1.0f, b));
    }
    
    public void setOutline(boolean outline) {
        this.outline = outline;
    }
    
    public void setOutlineThickness(float thickness) {
        this.outlineThickness = Math.max(0.5f, thickness);
    }
    
    public void setOutlineColor(float r, float g, float b) {
        this.outlineColor[0] = Math.max(0.0f, Math.min(1.0f, r));
        this.outlineColor[1] = Math.max(0.0f, Math.min(1.0f, g));
        this.outlineColor[2] = Math.max(0.0f, Math.min(1.0f, b));
    }
    
    // Getters
    public CrosshairStyle getStyle() { return style; }
    public float getSize() { return size; }
    public float getThickness() { return thickness; }
    public float getGap() { return gap; }
    public float getOpacity() { return opacity; }
    public float[] getColor() { return color.clone(); }
    public boolean hasOutline() { return outline; }
    public float getOutlineThickness() { return outlineThickness; }
    public float[] getOutlineColor() { return outlineColor.clone(); }
}