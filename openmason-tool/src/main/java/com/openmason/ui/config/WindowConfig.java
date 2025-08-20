package com.openmason.ui.config;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Simple configuration data structure for ImGui windows.
 * Extends existing theme JSON structure instead of creating new configuration files.
 */
public class WindowConfig {
    private static final Logger logger = LoggerFactory.getLogger(WindowConfig.class);
    
    private String title;
    private int width = 400;
    private int height = 300;
    private int x = -1; // -1 means auto-position
    private int y = -1;
    private int minWidth = 100;
    private int minHeight = 100;
    private int maxWidth = 4000;
    private int maxHeight = 4000;
    private int flags = 0; // ImGui window flags
    
    /**
     * Default constructor
     */
    public WindowConfig() {
        this.title = "Default Window";
    }
    
    /**
     * Constructor with title
     */
    public WindowConfig(String title) {
        this.title = title != null ? title : "Untitled Window";
    }
    
    /**
     * Constructor with title and size
     */
    public WindowConfig(String title, int width, int height) {
        this(title);
        setSize(width, height);
    }
    
    /**
     * Constructor with full configuration
     */
    public WindowConfig(String title, int width, int height, int x, int y) {
        this(title, width, height);
        setPosition(x, y);
    }
    
    // Default configurations
    public static WindowConfig getDefault() {
        return new WindowConfig("Default Window").setSize(400, 300);
    }
    
    public static WindowConfig forViewport() {
        return new WindowConfig("3D Viewport")
            .setSize(800, 600)
            .setMinSize(400, 300)
            .setMaxSize(2000, 1500);
    }
    
    public static WindowConfig forProperties() {
        return new WindowConfig("Properties")
            .setSize(300, 500)
            .setMinSize(250, 400)
            .setMaxSize(600, 1000);
    }
    
    public static WindowConfig forModelBrowser() {
        return new WindowConfig("Model Browser")
            .setSize(350, 450)
            .setMinSize(300, 400)
            .setMaxSize(700, 900);
    }
    
    public static WindowConfig forAdvancedPreferences() {
        return new WindowConfig("Advanced Preferences")
            .setSize(500, 400)
            .setMinSize(450, 350)
            .setMaxSize(800, 600);
    }
    
    // Fluent API for easy configuration
    public WindowConfig setTitle(String title) { 
        this.title = title != null ? title : "Untitled Window"; 
        return this; 
    }
    
    public WindowConfig setSize(int width, int height) { 
        this.width = Math.max(minWidth, Math.min(maxWidth, width));
        this.height = Math.max(minHeight, Math.min(maxHeight, height));
        
        if (this.width != width || this.height != height) {
            logger.debug("Window size clamped from {}x{} to {}x{}", width, height, this.width, this.height);
        }
        
        return this; 
    }
    
    public WindowConfig setPosition(int x, int y) { 
        this.x = Math.max(-1, x); // -1 is allowed for auto-positioning
        this.y = Math.max(-1, y);
        return this; 
    }
    
    public WindowConfig setMinSize(int minWidth, int minHeight) {
        this.minWidth = Math.max(50, minWidth);   // Minimum sensible size
        this.minHeight = Math.max(50, minHeight);
        
        // Adjust current size if it's below new minimum
        if (this.width < this.minWidth || this.height < this.minHeight) {
            setSize(Math.max(this.width, this.minWidth), Math.max(this.height, this.minHeight));
        }
        
        return this;
    }
    
    public WindowConfig setMaxSize(int maxWidth, int maxHeight) {
        this.maxWidth = Math.max(this.minWidth, maxWidth);
        this.maxHeight = Math.max(this.minHeight, maxHeight);
        
        // Adjust current size if it's above new maximum
        if (this.width > this.maxWidth || this.height > this.maxHeight) {
            setSize(Math.min(this.width, this.maxWidth), Math.min(this.height, this.maxHeight));
        }
        
        return this;
    }
    
    public WindowConfig setFlags(int flags) {
        this.flags = flags;
        return this;
    }
    
    public WindowConfig addFlag(int flag) {
        this.flags |= flag;
        return this;
    }
    
    public WindowConfig removeFlag(int flag) {
        this.flags &= ~flag;
        return this;
    }
    
    // Validation and access methods
    public boolean hasSize() { 
        return width > 0 && height > 0; 
    }
    
    public boolean hasPosition() { 
        return x >= 0 && y >= 0; 
    }
    
    public boolean hasSizeConstraints() { 
        return minWidth > 100 || minHeight > 100 || maxWidth < 4000 || maxHeight < 4000; 
    }
    
    public boolean isValidSize() {
        return width >= minWidth && width <= maxWidth && 
               height >= minHeight && height <= maxHeight;
    }
    
    public boolean isValidPosition() {
        return (x == -1 && y == -1) || (x >= 0 && y >= 0);
    }
    
    public boolean isValid() {
        return title != null && !title.trim().isEmpty() && 
               isValidSize() && isValidPosition();
    }
    
    // Getters
    public String getTitle() { 
        return title != null ? title : "Untitled"; 
    }
    
    public int getWidth() { return width; }
    public int getHeight() { return height; }
    public int getX() { return x; }
    public int getY() { return y; }
    public int getMinWidth() { return minWidth; }
    public int getMinHeight() { return minHeight; }
    public int getMaxWidth() { return maxWidth; }
    public int getMaxHeight() { return maxHeight; }
    public int getFlags() { return flags; }
    
    // Utility methods
    public float getAspectRatio() {
        return height != 0 ? (float) width / height : 1.0f;
    }
    
    public int getArea() {
        return width * height;
    }
    
    public boolean hasFlag(int flag) {
        return (flags & flag) != 0;
    }
    
    /**
     * Create a copy of this configuration
     */
    public WindowConfig copy() {
        WindowConfig copy = new WindowConfig(this.title);
        copy.width = this.width;
        copy.height = this.height;
        copy.x = this.x;
        copy.y = this.y;
        copy.minWidth = this.minWidth;
        copy.minHeight = this.minHeight;
        copy.maxWidth = this.maxWidth;
        copy.maxHeight = this.maxHeight;
        copy.flags = this.flags;
        return copy;
    }
    
    /**
     * Reset to default values
     */
    public WindowConfig reset() {
        this.width = 400;
        this.height = 300;
        this.x = -1;
        this.y = -1;
        this.minWidth = 100;
        this.minHeight = 100;
        this.maxWidth = 4000;
        this.maxHeight = 4000;
        this.flags = 0;
        return this;
    }
    
    /**
     * Apply constraints to ensure values are within bounds
     */
    public WindowConfig validate() {
        // Ensure minimums are sensible
        minWidth = Math.max(50, minWidth);
        minHeight = Math.max(50, minHeight);
        
        // Ensure maximums are reasonable
        maxWidth = Math.max(minWidth, Math.min(4000, maxWidth));
        maxHeight = Math.max(minHeight, Math.min(4000, maxHeight));
        
        // Clamp current size to constraints
        width = Math.max(minWidth, Math.min(maxWidth, width));
        height = Math.max(minHeight, Math.min(maxHeight, height));
        
        // Validate position
        if (x < -1) x = -1;
        if (y < -1) y = -1;
        
        // Ensure title is not null
        if (title == null || title.trim().isEmpty()) {
            title = "Untitled Window";
        }
        
        return this;
    }
    
    /**
     * Scale the configuration by a factor (useful for DPI scaling)
     */
    public WindowConfig scale(float factor) {
        if (factor <= 0) {
            logger.warn("Invalid scale factor: {}", factor);
            return this;
        }
        
        this.width = Math.round(this.width * factor);
        this.height = Math.round(this.height * factor);
        this.minWidth = Math.round(this.minWidth * factor);
        this.minHeight = Math.round(this.minHeight * factor);
        this.maxWidth = Math.round(this.maxWidth * factor);
        this.maxHeight = Math.round(this.maxHeight * factor);
        
        if (hasPosition()) {
            this.x = Math.round(this.x * factor);
            this.y = Math.round(this.y * factor);
        }
        
        return validate(); // Ensure scaled values are still valid
    }
    
    @Override
    public String toString() {
        return String.format("WindowConfig{title='%s', size=%dx%d, pos=(%d,%d), " +
                           "minSize=%dx%d, maxSize=%dx%d, flags=%d}", 
                           title, width, height, x, y, 
                           minWidth, minHeight, maxWidth, maxHeight, flags);
    }
    
    @Override
    public boolean equals(Object obj) {
        if (this == obj) return true;
        if (obj == null || getClass() != obj.getClass()) return false;
        
        WindowConfig that = (WindowConfig) obj;
        return width == that.width &&
               height == that.height &&
               x == that.x &&
               y == that.y &&
               minWidth == that.minWidth &&
               minHeight == that.minHeight &&
               maxWidth == that.maxWidth &&
               maxHeight == that.maxHeight &&
               flags == that.flags &&
               title.equals(that.title);
    }
    
    @Override
    public int hashCode() {
        int result = title.hashCode();
        result = 31 * result + width;
        result = 31 * result + height;
        result = 31 * result + x;
        result = 31 * result + y;
        result = 31 * result + flags;
        return result;
    }
}