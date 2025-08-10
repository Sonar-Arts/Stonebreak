package com.openmason.ui.icons;

import org.lwjgl.BufferUtils;
import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL12;
import org.lwjgl.opengl.GL13;
import org.lwjgl.opengl.GL30;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.imageio.ImageIO;
import java.awt.*;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Placeholder classes to replace Apache Batik functionality.
 */
class TranscoderException extends Exception {
    public TranscoderException(String message) { super(message); }
    public TranscoderException(String message, Throwable cause) { super(message, cause); }
}

class TranscoderInput {
    public TranscoderInput(InputStream stream) { }
}

class TranscoderOutput {
    public TranscoderOutput(ByteArrayOutputStream stream) { }
}

class PNGTranscoder {
    public static final String KEY_WIDTH = "WIDTH";
    public static final String KEY_HEIGHT = "HEIGHT";
    public static final String KEY_BACKGROUND_COLOR = "BACKGROUND_COLOR";
    
    public void addTranscodingHint(String key, Object value) { }
    public void transcode(TranscoderInput input, TranscoderOutput output) throws TranscoderException {
        // Placeholder - no actual SVG transcoding
        throw new TranscoderException("SVG transcoding not available - Batik dependency removed");
    }
}

/**
 * Advanced texture-based icon system with SVG rasterization, texture atlas packing,
 * and Dear ImGui compatibility. Supports high-DPI scaling and theme variants.
 */
public class IconTextureManager {
    
    private static final Logger logger = LoggerFactory.getLogger(IconTextureManager.class);
    
    // Icon size constants
    public static final int ICON_SIZE_SMALL = 16;
    public static final int ICON_SIZE_MEDIUM = 24;
    public static final int ICON_SIZE_LARGE = 32;
    public static final int ICON_SIZE_XLARGE = 48;
    
    // Theme variants
    public enum IconTheme {
        LIGHT("light"),
        DARK("dark"),
        AUTO("auto");
        
        private final String name;
        
        IconTheme(String name) {
            this.name = name;
        }
        
        public String getName() {
            return name;
        }
    }
    
    // Icon definition
    public static class IconDefinition {
        private final String name;
        private final String svgPath;
        private final boolean hasThemeVariants;
        private final Map<Integer, Integer> sizeToTextureId;
        private final Map<String, Integer> themeVariantToTextureId;
        
        public IconDefinition(String name, String svgPath, boolean hasThemeVariants) {
            this.name = name;
            this.svgPath = svgPath;
            this.hasThemeVariants = hasThemeVariants;
            this.sizeToTextureId = new HashMap<>();
            this.themeVariantToTextureId = new HashMap<>();
        }
        
        public String getName() { return name; }
        public String getSvgPath() { return svgPath; }
        public boolean hasThemeVariants() { return hasThemeVariants; }
        public Map<Integer, Integer> getSizeToTextureId() { return sizeToTextureId; }
        public Map<String, Integer> getThemeVariantToTextureId() { return themeVariantToTextureId; }
    }
    
    // Texture atlas entry
    public static class AtlasEntry {
        private final int textureId;
        private final float u1, v1, u2, v2;
        private final int width, height;
        
        public AtlasEntry(int textureId, float u1, float v1, float u2, float v2, int width, int height) {
            this.textureId = textureId;
            this.u1 = u1;
            this.v1 = v1;
            this.u2 = u2;
            this.v2 = v2;
            this.width = width;
            this.height = height;
        }
        
        public int getTextureId() { return textureId; }
        public float getU1() { return u1; }
        public float getV1() { return v1; }
        public float getU2() { return u2; }
        public float getV2() { return v2; }
        public int getWidth() { return width; }
        public int getHeight() { return height; }
    }
    
    // Singleton instance
    private static IconTextureManager instance;
    
    // Icon registry and cache
    private final Map<String, IconDefinition> iconRegistry = new ConcurrentHashMap<>();
    private final Map<String, AtlasEntry> iconCache = new ConcurrentHashMap<>();
    private final Map<String, Integer> textureAtlases = new HashMap<>();
    
    // Current theme and DPI scaling
    private IconTheme currentTheme = IconTheme.DARK;
    private float dpiScale = 1.0f;
    
    // Atlas packing settings
    private static final int ATLAS_SIZE = 512;
    private static final int ATLAS_PADDING = 2;
    
    private IconTextureManager() {
        initializeDefaultIcons();
        logger.info("Icon texture manager initialized");
    }
    
    public static synchronized IconTextureManager getInstance() {
        if (instance == null) {
            instance = new IconTextureManager();
        }
        return instance;
    }
    
    /**
     * Initialize default icon set
     */
    private void initializeDefaultIcons() {
        // Register default icons (these would reference actual SVG resources)
        registerIcon("new", "/icons/new.svg", true);
        registerIcon("open", "/icons/open.svg", true);
        registerIcon("save", "/icons/save.svg", true);
        registerIcon("reset", "/icons/reset.svg", true);
        registerIcon("zoom-in", "/icons/zoom-in.svg", true);
        registerIcon("zoom-out", "/icons/zoom-out.svg", true);
        registerIcon("fit", "/icons/fit.svg", true);
        registerIcon("wireframe", "/icons/wireframe.svg", true);
        registerIcon("grid", "/icons/grid.svg", true);
        registerIcon("axes", "/icons/axes.svg", true);
        registerIcon("validate", "/icons/validate.svg", true);
        registerIcon("generate", "/icons/generate.svg", true);
        registerIcon("settings", "/icons/settings.svg", true);
        registerIcon("help", "/icons/help.svg", true);
        registerIcon("close", "/icons/close.svg", true);
        registerIcon("minimize", "/icons/minimize.svg", true);
        registerIcon("maximize", "/icons/maximize.svg", true);
        registerIcon("search", "/icons/search.svg", true);
        registerIcon("folder", "/icons/folder.svg", true);
        registerIcon("file", "/icons/file.svg", true);
        registerIcon("refresh", "/icons/refresh.svg", true);
        registerIcon("delete", "/icons/delete.svg", true);
        registerIcon("copy", "/icons/copy.svg", true);
        registerIcon("paste", "/icons/paste.svg", true);
        registerIcon("cut", "/icons/cut.svg", true);
        registerIcon("undo", "/icons/undo.svg", true);
        registerIcon("redo", "/icons/redo.svg", true);
    }
    
    /**
     * Register a new icon
     */
    public void registerIcon(String name, String svgPath, boolean hasThemeVariants) {
        iconRegistry.put(name, new IconDefinition(name, svgPath, hasThemeVariants));
        logger.debug("Registered icon: {} with path: {}", name, svgPath);
    }
    
    /**
     * Get icon texture for Dear ImGui rendering
     */
    public AtlasEntry getIconTexture(String iconName, int size) {
        return getIconTexture(iconName, size, currentTheme);
    }
    
    /**
     * Get icon texture with specific theme
     */
    public AtlasEntry getIconTexture(String iconName, int size, IconTheme theme) {
        String cacheKey = generateCacheKey(iconName, size, theme);
        
        AtlasEntry cached = iconCache.get(cacheKey);
        if (cached != null) {
            return cached;
        }
        
        IconDefinition iconDef = iconRegistry.get(iconName);
        if (iconDef == null) {
            logger.warn("Icon not found: {}", iconName);
            return createFallbackIcon(iconName, size);
        }
        
        try {
            // Rasterize SVG to texture
            BufferedImage iconImage = rasterizeSVG(iconDef, size, theme);
            AtlasEntry entry = addToTextureAtlas(iconImage, cacheKey);
            
            iconCache.put(cacheKey, entry);
            return entry;
            
        } catch (Exception e) {
            logger.error("Failed to load icon texture: {}", iconName, e);
            return createFallbackIcon(iconName, size);
        }
    }
    
    /**
     * Rasterize SVG to BufferedImage
     */
    private BufferedImage rasterizeSVG(IconDefinition iconDef, int size, IconTheme theme) 
            throws IOException, TranscoderException {
        
        // Adjust size for DPI scaling
        int actualSize = Math.round(size * dpiScale);
        
        String svgPath = iconDef.getSvgPath();
        if (iconDef.hasThemeVariants() && theme != IconTheme.AUTO) {
            // Look for theme-specific variant
            String themeSpecificPath = svgPath.replace(".svg", "-" + theme.getName() + ".svg");
            InputStream themeStream = getClass().getResourceAsStream(themeSpecificPath);
            if (themeStream != null) {
                svgPath = themeSpecificPath;
            }
        }
        
        InputStream svgStream = getClass().getResourceAsStream(svgPath);
        if (svgStream == null) {
            // Create programmatic fallback
            return createProgrammaticIcon(iconDef.getName(), actualSize, theme);
        }
        
        // Use Batik to rasterize SVG
        PNGTranscoder transcoder = new PNGTranscoder();
        transcoder.addTranscodingHint(PNGTranscoder.KEY_WIDTH, (float) actualSize);
        transcoder.addTranscodingHint(PNGTranscoder.KEY_HEIGHT, (float) actualSize);
        transcoder.addTranscodingHint(PNGTranscoder.KEY_BACKGROUND_COLOR, 
            theme == IconTheme.LIGHT ? Color.WHITE : Color.BLACK);
        
        TranscoderInput input = new TranscoderInput(svgStream);
        ByteArrayOutputStream outputStream = new ByteArrayOutputStream();
        TranscoderOutput output = new TranscoderOutput(outputStream);
        
        transcoder.transcode(input, output);
        
        ByteArrayInputStream inputStream = new ByteArrayInputStream(outputStream.toByteArray());
        return ImageIO.read(inputStream);
    }
    
    /**
     * Create programmatic fallback icon
     */
    private BufferedImage createProgrammaticIcon(String iconName, int size, IconTheme theme) {
        BufferedImage image = new BufferedImage(size, size, BufferedImage.TYPE_INT_ARGB);
        Graphics2D g2d = image.createGraphics();
        
        // Enable anti-aliasing
        g2d.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        
        Color iconColor = theme == IconTheme.LIGHT ? Color.BLACK : Color.WHITE;
        g2d.setColor(iconColor);
        
        int margin = size / 8;
        int iconSize = size - 2 * margin;
        
        switch (iconName.toLowerCase()) {
            case "new", "new_model" -> drawNewIcon(g2d, margin, iconSize);
            case "open", "open_model" -> drawOpenIcon(g2d, margin, iconSize);
            case "save", "save_model" -> drawSaveIcon(g2d, margin, iconSize);
            case "reset", "reset_view" -> drawResetIcon(g2d, margin, iconSize);
            case "zoom-in" -> drawZoomInIcon(g2d, margin, iconSize);
            case "zoom-out" -> drawZoomOutIcon(g2d, margin, iconSize);
            case "fit", "fit_to_view" -> drawFitIcon(g2d, margin, iconSize);
            case "wireframe" -> drawWireframeIcon(g2d, margin, iconSize);
            case "grid" -> drawGridIcon(g2d, margin, iconSize);
            case "axes" -> drawAxesIcon(g2d, margin, iconSize);
            case "validate" -> drawValidateIcon(g2d, margin, iconSize);
            case "generate" -> drawGenerateIcon(g2d, margin, iconSize);
            case "settings" -> drawSettingsIcon(g2d, margin, iconSize);
            case "help" -> drawHelpIcon(g2d, margin, iconSize);
            case "search" -> drawSearchIcon(g2d, margin, iconSize);
            default -> drawDefaultIcon(g2d, margin, iconSize);
        }
        
        g2d.dispose();
        return image;
    }
    
    // Icon drawing methods
    private void drawNewIcon(Graphics2D g2d, int x, int size) {
        g2d.fillRect(x, x, size * 3 / 4, size);
        g2d.drawLine(x + size * 3 / 4, x + size / 4, x + size, x);
        g2d.drawLine(x + size * 3 / 4, x + size / 4, x + size, x + size / 2);
    }
    
    private void drawOpenIcon(Graphics2D g2d, int x, int size) {
        g2d.drawRect(x, x + size / 3, size, size * 2 / 3);
        g2d.fillRect(x + size / 4, x, size / 2, size / 3);
    }
    
    private void drawSaveIcon(Graphics2D g2d, int x, int size) {
        g2d.drawRect(x, x, size, size);
        g2d.fillRect(x + size / 4, x + size / 4, size / 2, size / 2);
        g2d.drawLine(x + size / 8, x, x + size / 8, x + size / 4);
    }
    
    private void drawResetIcon(Graphics2D g2d, int x, int size) {
        int centerX = x + size / 2;
        int centerY = x + size / 2;
        int radius = size / 3;
        
        g2d.setStroke(new BasicStroke(2));
        g2d.drawArc(centerX - radius, centerY - radius, radius * 2, radius * 2, 45, 270);
        
        // Arrow
        int arrowX = centerX + radius - 3;
        int arrowY = centerY - radius + 3;
        g2d.drawLine(arrowX, arrowY, arrowX - 5, arrowY - 5);
        g2d.drawLine(arrowX, arrowY, arrowX - 5, arrowY + 5);
    }
    
    private void drawZoomInIcon(Graphics2D g2d, int x, int size) {
        int centerX = x + size / 3;
        int centerY = x + size / 3;
        int radius = size / 4;
        
        g2d.setStroke(new BasicStroke(2));
        g2d.drawOval(centerX - radius, centerY - radius, radius * 2, radius * 2);
        g2d.drawLine(centerX + radius, centerY + radius, x + size - 2, x + size - 2);
        
        // Plus sign
        g2d.drawLine(centerX - radius / 2, centerY, centerX + radius / 2, centerY);
        g2d.drawLine(centerX, centerY - radius / 2, centerX, centerY + radius / 2);
    }
    
    private void drawZoomOutIcon(Graphics2D g2d, int x, int size) {
        int centerX = x + size / 3;
        int centerY = x + size / 3;
        int radius = size / 4;
        
        g2d.setStroke(new BasicStroke(2));
        g2d.drawOval(centerX - radius, centerY - radius, radius * 2, radius * 2);
        g2d.drawLine(centerX + radius, centerY + radius, x + size - 2, x + size - 2);
        
        // Minus sign
        g2d.drawLine(centerX - radius / 2, centerY, centerX + radius / 2, centerY);
    }
    
    private void drawFitIcon(Graphics2D g2d, int x, int size) {
        g2d.setStroke(new BasicStroke(2));
        int[] dashPattern = {3, 3};
        g2d.setStroke(new BasicStroke(2, BasicStroke.CAP_BUTT, BasicStroke.JOIN_MITER, 10, 
            new float[]{3, 3}, 0));
        g2d.drawRect(x + 2, x + 2, size - 4, size - 4);
        
        // Corner brackets
        g2d.setStroke(new BasicStroke(2));
        g2d.drawLine(x, x, x + size / 4, x);
        g2d.drawLine(x, x, x, x + size / 4);
        g2d.drawLine(x + size, x, x + size - size / 4, x);
        g2d.drawLine(x + size, x, x + size, x + size / 4);
    }
    
    private void drawWireframeIcon(Graphics2D g2d, int x, int size) {
        g2d.setStroke(new BasicStroke(2));
        
        // Triangle wireframe
        int[] xPoints = {x + size / 2, x + size / 4, x + size * 3 / 4};
        int[] yPoints = {x + size / 4, x + size * 3 / 4, x + size * 3 / 4};
        
        g2d.drawLine(xPoints[0], yPoints[0], xPoints[1], yPoints[1]);
        g2d.drawLine(xPoints[1], yPoints[1], xPoints[2], yPoints[2]);
        g2d.drawLine(xPoints[2], yPoints[2], xPoints[0], yPoints[0]);
    }
    
    private void drawGridIcon(Graphics2D g2d, int x, int size) {
        g2d.setStroke(new BasicStroke(1));
        
        int gridSize = size / 4;
        for (int i = 0; i <= 4; i++) {
            int pos = x + i * gridSize;
            g2d.drawLine(pos, x, pos, x + size);
            g2d.drawLine(x, pos, x + size, pos);
        }
    }
    
    private void drawAxesIcon(Graphics2D g2d, int x, int size) {
        g2d.setStroke(new BasicStroke(2));
        
        int centerX = x + size / 2;
        int centerY = x + size / 2;
        
        // X axis (red) - draw in current color
        g2d.drawLine(x + 2, centerY, x + size - 2, centerY);
        // Y axis (green) - draw in current color  
        g2d.drawLine(centerX, x + 2, centerX, x + size - 2);
        
        // Arrow heads
        g2d.drawLine(x + size - 6, centerY - 3, x + size - 2, centerY);
        g2d.drawLine(x + size - 6, centerY + 3, x + size - 2, centerY);
        g2d.drawLine(centerX - 3, x + 6, centerX, x + 2);
        g2d.drawLine(centerX + 3, x + 6, centerX, x + 2);
    }
    
    private void drawValidateIcon(Graphics2D g2d, int x, int size) {
        g2d.setStroke(new BasicStroke(3));
        
        // Checkmark
        int[] xPoints = {x + size / 4, x + size / 2, x + size * 3 / 4};
        int[] yPoints = {x + size / 2, x + size * 3 / 4, x + size / 4};
        
        g2d.drawLine(xPoints[0], yPoints[0], xPoints[1], yPoints[1]);
        g2d.drawLine(xPoints[1], yPoints[1], xPoints[2], yPoints[2]);
    }
    
    private void drawGenerateIcon(Graphics2D g2d, int x, int size) {
        int centerX = x + size / 2;
        int centerY = x + size / 2;
        int radius = size / 3;
        
        g2d.setStroke(new BasicStroke(2));
        g2d.drawOval(centerX - radius, centerY - radius, radius * 2, radius * 2);
        
        // Gear teeth (simplified)
        for (int i = 0; i < 8; i++) {
            double angle = i * Math.PI / 4;
            int x1 = (int) (centerX + radius * Math.cos(angle));
            int y1 = (int) (centerY + radius * Math.sin(angle));
            int x2 = (int) (centerX + (radius + 3) * Math.cos(angle));
            int y2 = (int) (centerY + (radius + 3) * Math.sin(angle));
            g2d.drawLine(x1, y1, x2, y2);
        }
    }
    
    private void drawSettingsIcon(Graphics2D g2d, int x, int size) {
        drawGenerateIcon(g2d, x, size); // Similar to generate for now
    }
    
    private void drawHelpIcon(Graphics2D g2d, int x, int size) {
        int centerX = x + size / 2;
        int centerY = x + size / 2;
        int radius = size / 3;
        
        g2d.setStroke(new BasicStroke(2));
        g2d.drawOval(centerX - radius, centerY - radius, radius * 2, radius * 2);
        
        // Question mark
        g2d.setFont(new Font("SansSerif", Font.BOLD, size / 2));
        FontMetrics fm = g2d.getFontMetrics();
        String text = "?";
        int textWidth = fm.stringWidth(text);
        int textHeight = fm.getAscent();
        g2d.drawString(text, centerX - textWidth / 2, centerY + textHeight / 4);
    }
    
    private void drawSearchIcon(Graphics2D g2d, int x, int size) {
        int centerX = x + size / 3;
        int centerY = x + size / 3;
        int radius = size / 4;
        
        g2d.setStroke(new BasicStroke(2));
        g2d.drawOval(centerX - radius, centerY - radius, radius * 2, radius * 2);
        g2d.drawLine(centerX + radius, centerY + radius, x + size - 2, x + size - 2);
    }
    
    private void drawDefaultIcon(Graphics2D g2d, int x, int size) {
        g2d.fillRect(x + size / 4, x + size / 4, size / 2, size / 2);
    }
    
    /**
     * Add image to texture atlas
     */
    private AtlasEntry addToTextureAtlas(BufferedImage image, String key) {
        // For simplicity, create individual textures for now
        // In a full implementation, this would pack into atlas textures
        int textureId = createOpenGLTexture(image);
        
        return new AtlasEntry(textureId, 0.0f, 0.0f, 1.0f, 1.0f, 
            image.getWidth(), image.getHeight());
    }
    
    /**
     * Create OpenGL texture from BufferedImage
     */
    private int createOpenGLTexture(BufferedImage image) {
        int width = image.getWidth();
        int height = image.getHeight();
        
        // Convert image to ByteBuffer
        int[] pixels = new int[width * height];
        image.getRGB(0, 0, width, height, pixels, 0, width);
        
        ByteBuffer buffer = BufferUtils.createByteBuffer(width * height * 4);
        for (int y = 0; y < height; y++) {
            for (int x = 0; x < width; x++) {
                int pixel = pixels[y * width + x];
                buffer.put((byte) ((pixel >> 16) & 0xFF)); // Red
                buffer.put((byte) ((pixel >> 8) & 0xFF));  // Green
                buffer.put((byte) (pixel & 0xFF));         // Blue
                buffer.put((byte) ((pixel >> 24) & 0xFF)); // Alpha
            }
        }
        buffer.flip();
        
        // Create OpenGL texture
        int textureId = GL11.glGenTextures();
        GL11.glBindTexture(GL11.GL_TEXTURE_2D, textureId);
        
        GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_MIN_FILTER, GL11.GL_LINEAR);
        GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_MAG_FILTER, GL11.GL_LINEAR);
        GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_WRAP_S, GL12.GL_CLAMP_TO_EDGE);
        GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_WRAP_T, GL12.GL_CLAMP_TO_EDGE);
        
        GL11.glTexImage2D(GL11.GL_TEXTURE_2D, 0, GL11.GL_RGBA, width, height, 0, 
            GL11.GL_RGBA, GL11.GL_UNSIGNED_BYTE, buffer);
        
        GL30.glGenerateMipmap(GL11.GL_TEXTURE_2D);
        GL11.glBindTexture(GL11.GL_TEXTURE_2D, 0);
        
        logger.debug("Created OpenGL texture: {} ({}x{})", textureId, width, height);
        return textureId;
    }
    
    /**
     * Create fallback icon for missing icons
     */
    private AtlasEntry createFallbackIcon(String iconName, int size) {
        BufferedImage fallback = createProgrammaticIcon(iconName, size, currentTheme);
        String cacheKey = "fallback_" + iconName + "_" + size;
        return addToTextureAtlas(fallback, cacheKey);
    }
    
    /**
     * Generate cache key
     */
    private String generateCacheKey(String iconName, int size, IconTheme theme) {
        return String.format("%s_%d_%s", iconName, size, theme.getName());
    }
    
    /**
     * Set current theme
     */
    public void setTheme(IconTheme theme) {
        this.currentTheme = theme;
        // Clear cache to force regeneration with new theme
        clearCache();
        logger.info("Icon theme changed to: {}", theme.getName());
    }
    
    /**
     * Set DPI scaling factor
     */
    public void setDpiScale(float scale) {
        this.dpiScale = Math.max(0.5f, Math.min(4.0f, scale));
        clearCache();
        logger.info("Icon DPI scale changed to: {}", this.dpiScale);
    }
    
    /**
     * Get DPI-scaled icon size
     */
    public int getScaledIconSize(int baseSize) {
        return Math.round(baseSize * dpiScale);
    }
    
    /**
     * Clear icon cache
     */
    public void clearCache() {
        // Delete OpenGL textures
        for (AtlasEntry entry : iconCache.values()) {
            GL11.glDeleteTextures(entry.getTextureId());
        }
        
        iconCache.clear();
        logger.debug("Icon cache cleared");
    }
    
    /**
     * Get cache statistics
     */
    public Map<String, Integer> getCacheStats() {
        Map<String, Integer> stats = new HashMap<>();
        stats.put("cached_icons", iconCache.size());
        stats.put("registered_icons", iconRegistry.size());
        stats.put("texture_atlases", textureAtlases.size());
        return stats;
    }
    
    /**
     * Cleanup resources
     */
    public void dispose() {
        clearCache();
        
        for (Integer atlasId : textureAtlases.values()) {
            GL11.glDeleteTextures(atlasId);
        }
        textureAtlases.clear();
        
        logger.info("Icon texture manager disposed");
    }
    
    public IconTheme getCurrentTheme() {
        return currentTheme;
    }
    
    public float getDpiScale() {
        return dpiScale;
    }
}